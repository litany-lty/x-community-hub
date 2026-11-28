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

    @Test void sparseInboundPageKeepsRestoredBodyAndHistoryUsesOnlyVerifiedOwnerCounts() {
        var mapper = new ObjectMapper();
        var store = new AssistantStore(tempDir, mapper);
        var provider = mock(TwitterApiIoProvider.class);
        var hub = mock(CommunityHubService.class);
        var at = Instant.parse("2026-11-25T08:00:00Z");
        var owner = new XUserSummary(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "renamed", "Author",
                "", "", false, 0, 0, 0, "");
        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", owner.id()).put("username", owner.username()));
            tx.put("history_source", owner.id(), mapper.createObjectNode().put("authorId", owner.id())
                    .put("username", owner.username()).put("eligible", true).put("timelineSavedCount", 90));
            tx.put("history_material", "unverified", mapper.createObjectNode().put("username", "elidlicom"));
            tx.put("history_material", "verified", mapper.createObjectNode().put("authorId", owner.id()));
            tx.put("history_interaction", owner.id() + ":reply", mapper.createObjectNode().put("targetAuthorId", owner.id())
                    .put("peerId", "peer").put("text", "complete archived reply").put("sourceUrl", "archived-url"));
            return null;
        });
        when(hub.isSilentHistoryEnabled()).thenReturn(true);
        when(provider.isFreeInboundConfigured()).thenReturn(true);
        when(provider.canReadInboundForFree("")).thenReturn(true);
        var reply = new TwitterApiIoProvider.TextInteraction("reply", owner.id(), owner.username(), "peer", "visitor",
                at, com.xops.hub.model.RawSnapshotModels.InboundKind.REPLY_TO_ME, "complete", "");
        when(provider.fetchInboundTextPage(eq(owner.username()), eq(owner.id()), anyString(), eq(100), any(), eq(true)))
                .thenReturn(new TwitterApiIoProvider.TextInteractionPage(owner, List.of(reply), "", true));
        var collector = new SourceCollector(store, provider, hub, mapper, Clock.fixed(at, ZoneId.of("UTC")));
        try {
            assertThat(collector.history().path("materials").asLong()).isOne();
            assertThat(collector.history().path("sources").get(0).path("timelineSavedCount").asLong()).isOne();
            collector.tick();
            verify(provider).fetchInboundTextPage(eq(owner.username()), eq(owner.id()), anyString(), eq(100), any(), eq(true));
            var row = record(store, "history_interaction", owner.id() + ":reply");
            assertThat(row.path("text").asText()).isEqualTo("complete archived reply");
            assertThat(row.path("sourceUrl").asText()).isEqualTo("archived-url");
        } finally { collector.stop(); }
    }

    @Test void overlappingArchivePagesMergeTextSourcesAndOnlyReplaceMeasuredMetrics() {
        var mapper = new ObjectMapper();
        var store = new AssistantStore(tempDir, mapper);
        var provider = mock(TwitterApiIoProvider.class);
        var community = mock(CommunityHubService.class);
        var owner = new XUserSummary("101", "author", "Archive Author", "https://example.test/avatar", "bio", true, 517, 0, 0, "");
        var at = Instant.parse("2026-11-25T08:00:00Z");
        store.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", owner.id()).put("username", owner.username())));
        store.tx(tx -> tx.put("history_material", "post", mapper.createObjectNode().put("username", "former")
                .put("text", "a longer unverified text that must remain separate").put("likes", 9999)));
        when(community.isSilentHistoryEnabled()).thenReturn(true);
        when(community.longHistoryCandidates()).thenReturn(List.of(
                new CommunityHubService.LongHistoryCandidate(owner, 0, true, 30, false, at.toEpochMilli())));
        when(provider.isFreeInboundConfigured()).thenReturn(true);
        when(provider.canReadTimelineForFree(anyString())).thenReturn(true);
        var full = new TextPost("post", "101", "author", at, TweetKind.QUOTE, "完整的作者正文", "q", "完整的引用正文",
                "peer", "quoted", "a", "https://example.test/article", "完整的长文内容", "QUOTED", "",
                "FULL", "FULL", "FULL", "ARTICLE_FULL", true, "https://x.com/author/status/post", "",
                false, true, "ROW_ID_VERIFIED", 471L, 10L, null, 2L, 9L);
        var preview = new TextPost("post", "101", "author", at, TweetKind.QUOTE, "摘要", "q", "",
                "", "", "a", "", "预览", "QUOTED", "", "PREVIEW", "MISSING", "PREVIEW", "QUOTE_TEXT_MISSING",
                false, "", "", false, false, "ROW_ID_VERIFIED", null, 0L, 3L, null, null);
        var changedTarget = new TextPost("post", "101", "author", at, TweetKind.QUOTE, "", "q2", "",
                "", "", "a2", "", "新预览", "QUOTED", "", "MISSING", "MISSING", "PREVIEW", "QUOTE_TEXT_MISSING",
                false, "", "", false, false, "ROW_ID_VERIFIED", null, null, null, null, null);
        when(provider.fetchHistoryPostsPage(eq("author"), eq("101"), anyString(), eq(100), any(), eq(true)))
                .thenReturn(new TextPostPage(owner, List.of(full), "fx:1", false),
                        new TextPostPage(null, List.of(preview), "fx:2", false),
                        new TextPostPage(null, List.of(preview), "fx:3", false),
                        new TextPostPage(null, List.of(changedTarget), "", true));
        var clock = mock(Clock.class, CALLS_REAL_METHODS);
        var observed = new AtomicReference<>(at);
        when(clock.instant()).thenAnswer(call -> observed.get());
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        var collector = new SourceCollector(store, provider, community, mapper, clock);
        try {
            collector.tick();
            var original = record(store, "history_material", "post");
            assertThat(original.path("version").asInt()).isEqualTo(2);
            assertThat(original.path("text").asText()).isEqualTo(full.text());
            assertThat(record(store, "history_material_unverified", "post").path("likes").asLong()).isEqualTo(9999);
            collector.tick();
            var merged = record(store, "history_material", "post");
            for (String field : List.of("text", "authorTextStatus", "quotedText", "quotedTextStatus", "quotedId",
                    "quotedAuthorId", "quotedUsername", "articleText", "articleTextStatus", "articleId", "articleRole",
                    "articleUrl", "textStatus", "sourceUrl", "hasMedia", "views", "reposts", "bookmarks")) {
                assertThat(merged.get(field)).as(field).isEqualTo(original.get(field));
            }
            assertThat(merged.path("authorName").asText()).isEqualTo(owner.displayName());
            assertThat(merged.path("authorFollowersCount").asInt()).isEqualTo(517);
            assertThat(merged.path("likes").asLong(-1)).isZero();
            assertThat(merged.path("replies").asLong(-1)).isEqualTo(3);
            assertThat(merged.path("pinned").asBoolean()).isFalse();
            observed.set(at.plusSeconds(3600));
            collector.tick();
            var confirmed = record(store, "history_material", "post");
            assertThat(confirmed.path("seedEvidenceAt").asLong()).isEqualTo(observed.get().toEpochMilli());
            assertThat(confirmed.path("likes").asLong(-1)).isZero();
            assertThat(confirmed.path("seedMetricTimes").path("views").asLong()).isEqualTo(at.toEpochMilli());
            assertThat(record(store, "history_source", "101").path("timelineSavedCount").asInt()).isOne();
            collector.tick();
            var changed = record(store, "history_material", "post");
            assertThat(changed.path("quotedText").asText()).isEmpty();
            assertThat(changed.path("quotedAuthorId").asText()).isEmpty();
            assertThat(changed.path("articleUrl").asText()).isEmpty();
            assertThat(changed.path("articleText").asText()).isEqualTo("新预览");
            assertThat(changed.path("articleTextStatus").asText()).isEqualTo("PREVIEW");
        } finally { collector.stop(); }
    }

    @Test void authorSourceLookupAndBackgroundStyleUseBoundIdentityAndBudget() throws Exception {
        var mapper = new ObjectMapper();
        var store = new AssistantStore(tempDir.resolve("assistant"), mapper);
        store.tx(tx -> tx.put("meta","owner",mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId","101").put("username","elidlicom")));
        var props = new com.xops.hub.config.XOpsProperties();
        props.setDiskCacheDir(tempDir.resolve("budget").toString());
        props.setHourlyUpstreamApiBudget(1);
        var guard = new UpstreamBudgetGuard(props, true);
        var provider = mock(TwitterApiIoProvider.class);
        var account = new XUserSummary("202","source","Source","","",false,1,1,1,"");
        when(provider.resolvePublicAuthor("source")).thenAnswer(call -> {
            guard.acquirePermit("https://api.twitterapi.io/twitter/user/info?userName=source", "twitterapi_io_read");
            return account;
        });
        var checked = new java.util.concurrent.CompletableFuture<Integer>();
        when(provider.fetchTextPostsPage(eq("elidlicom"),eq("101"),eq(""),eq(100),any())).thenAnswer(call -> {
            try {
                guard.acquirePermit("https://api.twitterapi.io/twitter/user/last_tweets?userName=elidlicom","twitterapi_io_read");
                checked.complete(200);
                return new TextPostPage(account,List.of(),"",true);
            } catch (XDataProviderException e) {
                checked.complete(e.getStatusCode());
                throw e;
            }
        });
        var collector = new SourceCollector(store,provider,mock(CommunityHubService.class),mapper);
        try {
            assertThat(collector.add("source").path("authorId").asText()).isEqualTo("202");
            collector.refreshStyleSamples();
            assertThat(checked.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(429);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !collector.styleSamples().at("/coverage/running").asBoolean());
            assertThat(guard.snapshotMetrics().get("totalBillableCalls")).isEqualTo(1L);
        } finally { collector.stop(); }
    }

    @Test
    void localCollectionRequiresUserActionEvenWithAnEnabledOwner() {
        var mapper = new ObjectMapper();
        var store = new AssistantStore(tempDir, mapper);
        store.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "101")
                .put("username", "elidlicom")));
        var provider = mock(TwitterApiIoProvider.class);
        when(provider.fetchHistoryPostsPage(eq("elidlicom"), eq("101"), eq(""), eq(100), any()))
                .thenReturn(new TextPostPage(null, List.of(), "", true));
        var community = mock(CommunityHubService.class);
        var collector = new SourceCollector(store, provider, community, mapper);
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(collector, "backgroundCollectionEnabled", false);
            collector.tick();
            collector.scheduledStyleRefresh();
            verifyNoInteractions(provider, community);
            collector.retryHistoryNow();
            verify(provider, timeout(2500).times(1))
                    .fetchHistoryPostsPage(eq("elidlicom"), eq("101"), eq(""), eq(100), any());
        } finally { collector.stop(); }
    }

    @Test
    void restartRecoversHistoryRetryAtStoredCursorWithoutStartingAnotherRetryCycle() {
        Clock now = Clock.fixed(Instant.parse("2026-11-28T02:00:00Z"), ZoneId.of("UTC"));
        long lastAttempt = now.millis() - SourceCollector.HISTORY_FAILURE_BACKOFF_MS - 1;
        var mapper = new ObjectMapper();
        var store = new AssistantStore(tempDir, mapper);
        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "101").put("username", "elidlicom"));
            return tx.put("history_source", "101", mapper.createObjectNode().put("timelineRetryCount", 2)
                    .put("timelineError", "模拟超时").put("timelineRetryExhausted", false)
                    .put("timelineCursor", "fx:next").put("timelineLastAttemptAt", lastAttempt).put("eligible", true));
        });
        var provider = mock(TwitterApiIoProvider.class);
        when(provider.isFreeInboundConfigured()).thenReturn(true);
        when(provider.canReadTimelineForFree(anyString())).thenReturn(true);
        when(provider.fetchHistoryPostsPage(eq("elidlicom"), eq("101"), eq("fx:next"), eq(100), any(), eq(true)))
                .thenReturn(new TextPostPage(null, List.of(), "", true));
        var reopened = new AssistantStore(tempDir, mapper);
        var collector = new SourceCollector(reopened, provider, mock(CommunityHubService.class), mapper, now);
        collector.setPaidCapabilityGate(new com.xops.hub.provider.PaidCapabilityGate(now));
        try {
            collector.tick();
            collector.tick();
            verify(provider, times(1)).fetchHistoryPostsPage(eq("elidlicom"), eq("101"), eq("fx:next"), eq(100), any(), eq(true));
            var recovered = record(reopened, "history_source", "101");
            assertThat(recovered.path("timelineRetryCount").asInt()).isZero();
            assertThat(recovered.path("timelineBackfillComplete").asBoolean()).isTrue();
        } finally { collector.stop(); }
    }

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
                tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
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
                tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
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
                tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
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
        return post(id, text, publishedAt, pinned, "author-id", "author");
    }

    private static TextPost post(String id, String text, long publishedAt, boolean pinned, String authorId, String username) {
        return new TextPost(id, authorId, username, Instant.ofEpochMilli(publishedAt), TweetKind.POST,
                text, "", "", "", "", "", "", "", "", text, "FULL", "NOT_PRESENT", "NOT_PRESENT", "TEXT_ONLY", false,
                "https://x.com/" + username + "/status/" + id, id, false, pinned, "ROW_ID_VERIFIED",
                null, null, null, null, null);
    }

    @Test
    void historyAndSilentStepRetryThreeTimesViaPaidCapabilityGateThenPauseUntilManualTrigger() throws Exception {
        ZoneId utc = ZoneId.of("UTC");
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-25T08:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override public ZoneId getZone() { return utc; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        com.xops.hub.provider.PaidCapabilityGate gate = new com.xops.hub.provider.PaidCapabilityGate(mutableClock);
        AssistantStore store = new AssistantStore(tempDir.resolve("retry-pause-test"), mapper);
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);

        XUserSummary owner = new XUserSummary("2095392916734976000", "elidlicom", "Litany", "", "", true, 650, 450, 7000, "");
        when(community.isSilentHistoryEnabled()).thenReturn(true);
        when(community.hasActiveForegroundWork()).thenReturn(false);
        when(community.longHistoryCandidates()).thenReturn(List.of(
                new CommunityHubService.LongHistoryCandidate(owner, 0, true, 30, false, nowRef.get().toEpochMilli())));

        when(provider.isFreeInboundConfigured()).thenReturn(true);
        when(provider.canReadTimelineForFree(anyString())).thenReturn(true);
        when(provider.canReadInboundForFree(anyString())).thenReturn(true);
        when(provider.isPageReadBudgetExhausted()).thenReturn(false);
        when(provider.isBackgroundPaidBudgetExhausted()).thenReturn(false);

        when(provider.fetchInboundTextPage(eq("elidlicom"), eq("2095392916734976000"), anyString(), eq(100), any(), eq(true)))
                .thenReturn(new TwitterApiIoProvider.TextInteractionPage(owner, List.of(), "", true));
        java.util.concurrent.atomic.AtomicBoolean timelineFails = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(provider.fetchHistoryPostsPage(eq("elidlicom"), eq("2095392916734976000"), anyString(), eq(100), any(), eq(true)))
                .thenAnswer(inv -> {
                    if (timelineFails.get()) {
                        throw new XDataProviderException(503, "免费公开动态暂不可用，已保留当前进度");
                    }
                    return new TextPostPage(owner, List.of(post("p-revived", "恢复采集成功", nowRef.get().toEpochMilli(),
                            false, owner.id(), owner.username())), "", true);
                });

        SourceCollector collector = new SourceCollector(store, provider, community, mapper, mutableClock);
        collector.setPaidCapabilityGate(gate);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "2095392916734976000").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("collectionEnabled", true));
                return null;
            });

            // Initial tick -> inbound hasn't run yet, timeline goes first and fails (retryCount=1, not exhausted)
            collector.tick();
            String tlRetryKey = "history-source:2095392916734976000:timeline:";
            verify(provider, times(1)).fetchHistoryPostsPage(eq("elidlicom"), eq("2095392916734976000"), eq(""), eq(100), any(), eq(true));
            verify(provider, never()).fetchHistoryPostsPage(anyString(), anyString(), anyString(), anyInt(), any());
            var afterInit = record(store, "history_source", "2095392916734976000");
            assertThat(afterInit.path("timelineRetryCount").asInt()).isEqualTo(1);
            assertThat(afterInit.path("timelineRetryExhausted").asBoolean()).isFalse();
            assertThat(gate.isExhausted(tlRetryKey)).isFalse();

            // Second normal tick runs inbound (succeeds) and must NOT re-trigger timeline while retryCount > 0
            collector.tick();
            collector.tick();
            verify(provider, times(1)).fetchHistoryPostsPage(eq("elidlicom"), eq("2095392916734976000"), eq(""), eq(100), any(), eq(true));

            // 3 scheduled 10-minute retries via PaidCapabilityGate
            for (int attempt = 1; attempt <= 3; attempt++) {
                nowRef.set(nowRef.get().plusSeconds(601));
                gate.runDueRetries();
            }
            verify(provider, times(4)).fetchHistoryPostsPage(eq("elidlicom"), eq("2095392916734976000"), eq(""), eq(100), any(), eq(true));
            verify(provider, times(3)).resetFreeCircuitForActiveSync();

            var afterExhausted = record(store, "history_source", "2095392916734976000");
            assertThat(afterExhausted.path("timelineRetryExhausted").asBoolean()).isTrue();
            assertThat(afterExhausted.path("timelineRetryCount").asInt()).isEqualTo(4);
            assertThat(afterExhausted.path("timelineError").asText()).contains("等待手动触发重试");
            assertThat(gate.isExhausted(tlRetryKey)).isTrue();
            assertThat(SourceCollector.historyDimensionDue(afterExhausted, "timeline", nowRef.get().plusSeconds(3600).toEpochMilli(), true)).isFalse();

            // Subsequent ticks and runDueRetries make 0 upstream calls
            nowRef.set(nowRef.get().plusSeconds(1200));
            gate.runDueRetries();
            collector.tick();
            verify(provider, times(4)).fetchHistoryPostsPage(eq("elidlicom"), eq("2095392916734976000"), eq(""), eq(100), any(), eq(true));

            // Manual trigger revives history collection
            timelineFails.set(false);
            collector.retryHistoryNow();
            assertThat(gate.isExhausted(tlRetryKey)).isFalse();
            verify(provider, timeout(2000).times(5))
                    .fetchHistoryPostsPage(eq("elidlicom"), eq("2095392916734976000"), eq(""), eq(100), any(), eq(true));
            // Wait briefly for commitHistoryTimeline on worker thread
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline
                    && !record(store, "history_source", "2095392916734976000").path("timelineBackfillComplete").asBoolean(false)) {
                Thread.sleep(20);
            }
            var afterRevive = record(store, "history_source", "2095392916734976000");
            assertThat(afterRevive.path("timelineBackfillComplete").asBoolean()).isTrue();
            assertThat(afterRevive.path("timelineRetryExhausted").asBoolean()).isFalse();
            assertThat(afterRevive.path("timelineRetryCount").asInt()).isZero();
            assertThat(record(store, "history_material", "p-revived")).isNotNull();
        } finally {
            collector.stop();
        }

        // Also verify CommunityHubService.runOneSilentTop10HistoryStepNow() pauses @elidlicom after 3 failed retries
        com.xops.hub.config.XOpsProperties props = new com.xops.hub.config.XOpsProperties();
        props.setDiskCacheDir(tempDir.resolve("silent-pause-cache").toString());
        props.setTwitterapiIoKey("valid-test-key");
        props.setFxtwitterEnabled(true);
        TwitterApiIoProvider silentProvider = mock(TwitterApiIoProvider.class);
        when(silentProvider.isFreeInboundConfigured()).thenReturn(true);
        when(silentProvider.canReadTimelineForFree(anyString())).thenReturn(true);
        when(silentProvider.isBackgroundPaidBudgetExhausted()).thenReturn(false);
        when(silentProvider.syncStoreSilentHistoryStep(eq("elidlicom"), any(), eq(3), eq("2095392916734976000"), eq(true)))
                .thenReturn(new TwitterApiIoProvider.SyncOutcome(null, false, true, true, 0));

        CommunityHubService hub = new CommunityHubService(props, new com.xops.hub.provider.DemoXDataProvider(), silentProvider, null, mapper);
        hub.setPaidCapabilityGate(gate);
        // Seed author store with stale syncedAt so longHistoryCandidates() returns @elidlicom and needsSilent30DayBackfill() is true
        var staleStore = new com.xops.hub.store.AccountStore.StoredAccount(
                com.xops.hub.store.AccountStore.VERSION, owner,
                Instant.now().minusSeconds(7200).getEpochSecond(),
                java.util.Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), 0L, false);
        com.xops.hub.store.AccountStore.write(
                tempDir.resolve("silent-pause-cache").resolve("users").resolve("by-id").resolve("2095392916734976000").resolve("store_v1.json.gz"),
                staleStore, mapper);

        assertThat(hub.runOneSilentTop10HistoryStepNow()).isFalse();
        verify(silentProvider, times(1)).syncStoreSilentHistoryStep(eq("elidlicom"), any(), eq(3), eq("2095392916734976000"), eq(true));
        for (int attempt = 1; attempt <= 3; attempt++) {
            nowRef.set(nowRef.get().plusSeconds(601));
            gate.runDueRetries();
        }
        verify(silentProvider, times(4)).syncStoreSilentHistoryStep(eq("elidlicom"), any(), eq(3), eq("2095392916734976000"), eq(true));
        assertThat(gate.isExhausted("silent-history:elidlicom")).isTrue();

        // Further silent steps must make 0 calls while exhausted
        nowRef.set(nowRef.get().plusSeconds(1200));
        gate.runDueRetries();
        assertThat(hub.runOneSilentTop10HistoryStepNow()).isFalse();
        verify(silentProvider, times(4)).syncStoreSilentHistoryStep(eq("elidlicom"), any(), eq(3), eq("2095392916734976000"), eq(true));
    }
}
