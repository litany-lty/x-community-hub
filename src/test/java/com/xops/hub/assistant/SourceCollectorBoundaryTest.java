package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.TwitterApiIoProvider.TextPost;
import com.xops.hub.provider.TwitterApiIoProvider.TextPostPage;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityHubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCollectorBoundaryTest {
    @TempDir Path tempDir;

    @Test
    void scanUsesThirtyMinuteOverlapWithoutLettingAnOldPinnedPostStopPagination() {
        long enabledAt = 1_000_000L;
        long lastScanStartedAt = enabledAt + 2 * 60 * 60 * 1000L;
        long boundary = SourceCollector.listenerScanSince(enabledAt, lastScanStartedAt);
        assertThat(boundary).isEqualTo(lastScanStartedAt - SourceCollector.SCAN_OVERLAP_MS);
        assertThat(SourceCollector.listenerScanSince(lastScanStartedAt, lastScanStartedAt))
                .isEqualTo(lastScanStartedAt);

        TextPost oldPinned = post("pinned", boundary - 1, true);
        TextPost current = post("current", boundary + 1, false);
        assertThat(SourceCollector.crossedListenerBoundary(List.of(oldPinned, current), boundary)).isFalse();
        assertThat(SourceCollector.crossedListenerBoundary(
                List.of(oldPinned, current, post("old", boundary - 1, false)), boundary)).isTrue();
    }

    @Test
    void cursorBindingJitterAndExpiredCursorRecoveryRecordGapAndRescanFromAnchor() {
        ObjectMapper mapper = new ObjectMapper();
        var record = mapper.createObjectNode().put("cursor", "c-1")
                .put("cursorProvider", "twitterapi.io").put("cursorEndpoint", "user/last_tweets");
        assertThat(SourceCollector.boundCursor(record, "cursor", "twitterapi.io", "user/last_tweets")).isEqualTo("c-1");
        assertThat(SourceCollector.boundCursor(record, "cursor", "other-provider", "user/last_tweets")).isEmpty();
        assertThat(SourceCollector.boundCursor(record, "cursor", "twitterapi.io", "other/endpoint")).isEmpty();

        assertThat(SourceCollector.sourceJitterMs("author-42")).isBetween(0L, 45_000L);
        var timeoutSource = mapper.createObjectNode().put("id", "817386").put("jitterMs", 0L)
                .put("lastAttemptAt", 1_000_000L).put("error", "读取超时，已保留当前进度");
        assertThat(SourceCollector.sourceDue(timeoutSource, 1_000_000L + 60_000L, 300_000L)).isFalse();
        assertThat(SourceCollector.sourceDue(timeoutSource, 1_000_000L + 95_000L, 300_000L)).isTrue();
        assertThat(SourceCollector.shouldResetExpiredCursor(new XDataProviderException(400, "Invalid cursor"), 1)).isTrue();
        assertThat(SourceCollector.shouldResetExpiredCursor(new XDataProviderException(429, "Rate limit"), 2)).isFalse();
        assertThat(SourceCollector.shouldResetExpiredCursor(new RuntimeException("transient"), 1)).isFalse();
        assertThat(SourceCollector.shouldResetExpiredCursor(new RuntimeException("stagnant"), 2)).isTrue();

        AssistantStore store = new AssistantStore(tempDir, mapper);
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(community.longHistoryCandidates()).thenReturn(List.of());
        XUserSummary author = new XUserSummary("u-openai", "OpenAI", "OpenAI", "", "", true, 100, 10, 50, "");
        when(provider.resolvePublicAuthor(eq("OpenAI"))).thenReturn(author);

        SourceCollector collector = new SourceCollector(store, provider, community, mapper);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("collectionEnabled", true));
                return null;
            });
            collector.add("@OpenAI");
            long now = System.currentTimeMillis();
            when(provider.fetchTextPostsPage(eq("OpenAI"), eq("u-openai"), eq(""), eq(100), any()))
                    .thenReturn(new TextPostPage(author, List.of(post("p1", now + 5_000L, false)), "stale-cursor", false))
                    .thenReturn(new TextPostPage(author, List.of(post("p2", now + 2_000L, false)), "", true));
            when(provider.fetchTextPostsPage(eq("OpenAI"), eq("u-openai"), eq("stale-cursor"), eq(100), any()))
                    .thenThrow(new XDataProviderException(400, "Invalid cursor token"));

            collector.tick();
            var afterPage1 = store.tx(tx -> tx.get("source", "u-openai"));
            assertThat(afterPage1.path("cursor").asText()).isEqualTo("stale-cursor");

            store.tx(tx -> tx.put("source", "u-openai", tx.get("source", "u-openai").put("lastAttemptAt", now - 10_000L)));
            collector.tick();
            var afterExpired = store.tx(tx -> tx.get("source", "u-openai"));
            assertThat(afterExpired.path("cursor").asText()).isEmpty();
            assertThat(afterExpired.path("anchorSince").asLong()).isGreaterThan(0L);
            assertThat(afterExpired.path("gaps").size()).isOne();
            assertThat(afterExpired.path("gaps").get(0).path("reason").asText()).contains("Invalid cursor");

            store.tx(tx -> tx.put("source", "u-openai", tx.get("source", "u-openai").put("lastAttemptAt", 1L)));
            collector.tick();
            var recovered = store.tx(tx -> tx.get("source", "u-openai"));
            assertThat(recovered.path("error").asText()).isEmpty();
            assertThat(recovered.has("anchorSince")).isFalse();
            var savedMaterial = store.tx(tx -> tx.get("material", "p2"));
            assertThat(savedMaterial).isNotNull();
        } finally {
            collector.stop();
        }
    }

    @Test
    void budgetExhaustionAndHistoryFailuresBackoffWithoutResettingCursors() {
        ObjectMapper mapper = new ObjectMapper();
        var budgetEx = new XDataProviderException(429, UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
        assertThat(SourceCollector.shouldResetExpiredCursor(budgetEx, 10)).isFalse();

        var budgetSource = mapper.createObjectNode().put("id", "817386").put("jitterMs", 0L)
                .put("lastAttemptAt", 1_000_000L).put("error", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
        assertThat(SourceCollector.sourceDue(budgetSource, 1_000_000L + 300_000L, 300_000L)).isFalse();
        assertThat(SourceCollector.sourceDue(budgetSource, 1_000_000L + SourceCollector.BUDGET_BACKOFF_MS, 300_000L)).isTrue();

        var failedHistory = mapper.createObjectNode()
                .put("timelineBackfillComplete", false)
                .put("timelineCursor", "c-keep")
                .put("timelineLastAttemptAt", 2_000_000L)
                .put("timelineError", "连接 TwitterAPI.io 失败");
        assertThat(SourceCollector.historyDimensionDue(failedHistory, "timeline", 2_000_000L + 30_000L)).isFalse();
        assertThat(SourceCollector.historyDimensionDue(failedHistory, "timeline",
                2_000_000L + SourceCollector.HISTORY_FAILURE_BACKOFF_MS)).isTrue();

        var budgetHistory = failedHistory.deepCopy().put("timelineError", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
        assertThat(SourceCollector.historyDimensionDue(budgetHistory, "timeline", 2_000_000L + 120_000L)).isFalse();
        assertThat(SourceCollector.historyDimensionDue(budgetHistory, "timeline",
                2_000_000L + SourceCollector.BUDGET_BACKOFF_MS)).isTrue();

        AssistantStore store = new AssistantStore(tempDir.resolve("budget-pause"), mapper);
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(provider.isPageReadBudgetExhausted()).thenReturn(true);
        SourceCollector collector = new SourceCollector(store, provider, community, mapper);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("collectionEnabled", true));
                tx.put("source", "817386", mapper.createObjectNode()
                        .put("authorId", "817386").put("username", "Techmeme").put("status", "ACTIVE")
                        .put("instanceId", "inst-1").put("cursor", "page-2")
                        .put("cursorProvider", SourceCollector.TIMELINE_PROVIDER)
                        .put("cursorEndpoint", SourceCollector.TIMELINE_ENDPOINT)
                        .put("cursorFailures", 0).put("lastAttemptAt", 1_000L)
                        .put("error", "读取超时，已保留当前进度"));
                return null;
            });
            collector.tick();
            verify(provider, never()).fetchTextPostsPage(anyString(), anyString(), anyString(), anyInt(), any());
            verify(provider, never()).fetchHistoryPostsPage(anyString(), anyString(), anyString(), anyInt(), any());
            var reconciled = store.tx(tx -> tx.get("source", "817386"));
            assertThat(reconciled.path("error").asText()).isEqualTo(UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
            assertThat(reconciled.path("cursor").asText()).isEqualTo("page-2");
            assertThat(reconciled.path("cursorFailures").asInt()).isZero();
        } finally {
            collector.stop();
        }
    }

    @Test
    void collectsOnlyTodayAndYesterdayAndDeletesExpiredTopicsMaterialsAndDraftsWhileKeepingReviews() throws Exception {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        AtomicReference<Instant> currentInstant = new AtomicReference<>(
                ZonedDateTime.of(2026, 11, 23, 15, 30, 0, 0, shanghai).toInstant());
        Clock mutableClock = new Clock() {
            @Override public ZoneId getZone() { return shanghai; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentInstant.get(); }
        };

        ObjectMapper mapper = new ObjectMapper();
        AssistantStore store = new AssistantStore(tempDir.resolve("two-day-window"), mapper);
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(community.longHistoryCandidates()).thenReturn(List.of());
        XUserSummary author = new XUserSummary("u-openai", "OpenAI", "OpenAI", "", "", true, 100, 10, 50, "");
        when(provider.resolvePublicAuthor(eq("OpenAI"))).thenReturn(author);

        SourceCollector collector = new SourceCollector(store, provider, community, mapper, mutableClock);
        AssistantWorker worker = new AssistantWorker(store, mock(AssistantIntelligence.class), collector);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("collectionEnabled", true).put("modelEnabled", false));
                tx.put("style_sample", "style-old", mapper.createObjectNode().put("textRole", "AUTHOR").put("text", "本人早期原创").put("publishedAt", 1_000_000L));
                tx.put("history_material", "hist-old", mapper.createObjectNode().put("id", "hist-old").put("publishedAt", 1_000_000L));
                tx.put("history_interaction", "owner-1:int-old", mapper.createObjectNode().put("id", "int-old").put("publishedAt", 1_000_000L));
                return null;
            });

            collector.add("@OpenAI");
            long pinnedOldAt = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, shanghai).toInstant().toEpochMilli();
            long todayAt = ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, shanghai).toInstant().toEpochMilli();
            long yesterdayStartAt = ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli();
            long beforeYesterdayAt = yesterdayStartAt - 1L;

            when(provider.fetchTextPostsPage(eq("OpenAI"), eq("u-openai"), eq(""), eq(100), any()))
                    .thenReturn(new TextPostPage(author, List.of(
                            post("pinned-old", "2026 年初置顶旧公告", pinnedOldAt, true),
                            post("m-today", "今日新模型架构技术发布", todayAt, false),
                            post("m-yesterday", "昨日算力集群扩容进展", yesterdayStartAt, false),
                            post("m-too-old", "前天更早的过期历史帖", beforeYesterdayAt, false)
                    ), "older-cursor", false));

            collector.tick();

            // Only today and yesterday are collected; pagination stops without retaining older-cursor.
            assertThat(record(store, "material", "m-today")).isNotNull();
            assertThat(record(store, "material", "m-yesterday")).isNotNull();
            assertThat(record(store, "material", "pinned-old")).isNull();
            assertThat(record(store, "material", "m-too-old")).isNull();
            assertThat(record(store, "source", "u-openai").path("cursor").asText()).isEmpty();

            // Attach a draft, research, task, and published observation to yesterday's topic, plus a mixed topic.
            String yesterdayTopicId = store.tx(tx -> AssistantTopics.topicOf(tx, "m-yesterday"));
            String todayTopicId = store.tx(tx -> AssistantTopics.topicOf(tx, "m-today"));
            store.tx(tx -> {
                var researchNode = mapper.createObjectNode().put("summary", "昨日研究");
                researchNode.putObject("snapshot").put("topicId", yesterdayTopicId);
                tx.put("research", "task-y", researchNode);
                tx.put("task", "task-y", mapper.createObjectNode().put("kind", "RESEARCH").put("status", "READY")
                        .put("topicId", yesterdayTopicId).put("materialId", "m-yesterday").put("draftId", "draft-y"));
                tx.put("fingerprint", "fp-y", mapper.createObjectNode().put("taskId", "task-y"));
                tx.put("draft", "draft-y", mapper.createObjectNode().put("topicId", yesterdayTopicId)
                        .put("materialId", "m-yesterday").put("researchId", "task-y").put("text", "昨日初稿"));
                tx.put("draft", "draft-today", mapper.createObjectNode().put("topicId", todayTopicId)
                        .put("materialId", "m-today").put("text", "今日初稿"));
                tx.put("publication", "pub-y", mapper.createObjectNode().put("topicId", yesterdayTopicId)
                        .put("draftId", "draft-y").put("publishedTweetId", "pub-tweet-1").put("publishedAt", yesterdayStartAt + 3_600_000L));
                tx.put("observation", "pub-tweet-1:24", mapper.createObjectNode().put("postId", "pub-tweet-1")
                        .put("topicId", yesterdayTopicId).put("draftId", "draft-y").put("status", "OBSERVED").put("views", 1200));

                // Also add a second post on 11-22 merged into todayTopicId so we verify partial member pruning.
                AssistantWorkflow.ingest(tx, mapper.createObjectNode().put("id", "m-yesterday-merged")
                        .put("username", "OpenAI").put("text", "昨日同事件前情").put("textStatus", "TEXT_ONLY")
                        .put("publishedAt", yesterdayStartAt + 60_000L));
                String tempTopic = AssistantTopics.topicOf(tx, "m-yesterday-merged");
                var versions = mapper.createObjectNode()
                        .put(tempTopic, tx.get("topic", tempTopic).path("version").asInt())
                        .put(todayTopicId, tx.get("topic", todayTopicId).path("version").asInt());
                AssistantTopics.move(tx, List.of("m-yesterday-merged"), todayTopicId, versions, "OWNER");
                return null;
            });

            // Advance clock to 2026-11-24 00:05+08:00: 2026-11-22 is now before yesterday (2026-11-23).
            currentInstant.set(ZonedDateTime.of(2026, 11, 24, 0, 5, 0, 0, shanghai).toInstant());
            collector.tick();

            // 2026-11-22 material, topic, draft, research, task, fingerprint are all deleted.
            assertThat(record(store, "material", "m-yesterday")).isNull();
            assertThat(record(store, "material", "m-yesterday-merged")).isNull();
            assertThat(record(store, "membership", "m-yesterday")).isNull();
            assertThat(record(store, "topic", yesterdayTopicId)).isNull();
            assertThat(record(store, "draft", "draft-y")).isNull();
            assertThat(record(store, "task", "task-y")).isNull();
            assertThat(record(store, "research", "task-y")).isNull();
            assertThat(record(store, "fingerprint", "fp-y")).isNull();

            // 2026-11-23 (now yesterday) survives, and its merged topic pruned the expired member cleanly.
            assertThat(record(store, "material", "m-today")).isNotNull();
            assertThat(record(store, "draft", "draft-today")).isNotNull();
            var survivingTopic = record(store, "topic", todayTopicId);
            assertThat(survivingTopic).isNotNull();
            assertThat(survivingTopic.path("materialIds")).hasSize(1);
            assertThat(survivingTopic.path("materialIds").get(0).asText()).isEqualTo("m-today");

            // Published review observations, style samples, and long-term history remain intact.
            assertThat(record(store, "observation", "pub-tweet-1:24")).isNotNull();
            assertThat(record(store, "publication", "pub-y")).isNotNull();
            assertThat(record(store, "style_sample", "style-old")).isNotNull();
            assertThat(record(store, "history_material", "hist-old")).isNotNull();
            assertThat(record(store, "history_interaction", "owner-1:int-old")).isNotNull();

            // Resuming a source after multi-day pause clamps scanSince to yesterday 00:00+08:00.
            long fiveDaysAgo = ZonedDateTime.of(2026, 11, 19, 8, 0, 0, 0, shanghai).toInstant().toEpochMilli();
            store.tx(tx -> tx.put("source", "u-openai",
                    tx.get("source", "u-openai").deepCopy().put("status", "PAUSED").put("lastCompleteAt", fiveDaysAgo)));
            var resumed = collector.change("u-openai", "resume");
            long nov23Start = ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli();
            assertThat(resumed.path("scanSince").asLong()).isEqualTo(nov23Start);

            // Dashboard & topicDetail via AssistantController also purge expired records and return clean counts.
            store.tx(tx -> {
                AssistantWorkflow.ingest(tx, mapper.createObjectNode().put("id", "m-stale-direct")
                        .put("username", "OpenAI").put("text", "直接写入的前天旧帖").put("textStatus", "TEXT_ONLY")
                        .put("publishedAt", nov23Start - 1L));
                return null;
            });
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, collector, worker, mock(AssistantPublishing.class), mapper)).build();
            var session = new org.springframework.mock.web.MockHttpSession();
            session.setAttribute(com.xops.hub.controller.XOAuthController.SESSION_AUTH_USER,
                    new com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, Instant.now()));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/content-assistant/dashboard").session(session))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.topicCount").value(1))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.draftedTopicCount").value(1))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.materialCount").value(1));
            assertThat(record(store, "material", "m-stale-direct")).isNull();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/content-assistant/topics/" + todayTopicId + "/detail").session(session))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.materials.length()").value(1));
        } finally {
            worker.close();
            collector.stop();
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode record(AssistantStore store, String kind, String id) {
        return store.tx(tx -> tx.get(kind, id));
    }

    private static TextPost post(String id, long publishedAt, boolean pinned) {
        return post(id, "text", publishedAt, pinned);
    }

    private static TextPost post(String id, String text, long publishedAt, boolean pinned) {
        return new TextPost(id, "author-id", "author", Instant.ofEpochMilli(publishedAt), TweetKind.POST,
                text, "", "", "", "", "", "", "", "", text, "FULL", "NOT_PRESENT", "NOT_PRESENT", "TEXT_ONLY", false,
                "https://x.com/author/status/" + id, id, false, pinned, "ROW_ID_VERIFIED",
                null, null, null, null, null);
    }
}
