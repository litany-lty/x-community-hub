package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.TwitterApiIoProvider.TextPost;
import com.xops.hub.provider.TwitterApiIoProvider.TextPostPage;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewriteDiscoveryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private AssistantStore store;

    @BeforeEach void openStore() {
        store = new AssistantStore(directory, mapper);
    }

    private static ObjectNode source(String id, int interval) {
        return object().put("authorId", id).put("username", "author" + id).put("instanceId", "source-" + id)
                .put("autoRewriteEnabled", true).put("status", "ACTIVE").put("intervalMinutes", interval);
    }

    private void putPost(AssistantStore.Tx tx, String id, String author, String text, long at, int views, int likes) {
        tx.put("material", id, object().put("id", id).put("authorId", author).put("username", "author" + author)
                .put("text", text).put("kind", "POST").put("textStatus", "TEXT_ONLY").put("publishedAt", at)
                .put("authorFollowersCount", 10_000).put("views", views).put("likes", likes));
    }

    @Test void patrolInterleavesDifferentAuthorsByHeatAndExposureEvenAfterAModelRejection() {
        Clock clock = Clock.fixed(LocalDate.now(AssistantRetention.ZONE).atTime(9, 0).atZone(AssistantRetention.ZONE).toInstant(), AssistantRetention.ZONE);
        long now = clock.millis();
        String hot = "Distributed databases use consensus to coordinate replicas after a regional network partition.";
        String middle = "Medical auditors compared independently collected outcomes across three separate hospitals.";
        String low = "Weather station operators published a new set of measurements for seasonal wind patterns.";
        store.tx(tx -> {
            tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "owner"));
            tx.put("meta", "owner_latest_tweet", object().put("accountId", "42").put("username", "owner").put("checkedAt", now));
            tx.put("source", "8", source("8", 1));
            tx.put("source", "7", source("7", 30));
            putPost(tx, "81001", "8", hot, now - 300_000, 950_000, 15_000);
            putPost(tx, "81002", "8", low, now - 60_000, 9_000, 90);
            putPost(tx, "71001", "7", middle, now - 120_000, 90_000, 1_200);
            return null;
        });
        var judge = mock(PiAgentViralJudge.class);
        List<String> assessed = new ArrayList<>();
        when(judge.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
            assessed.add(call.<PiAgentViralJudge.RewriteInput>getArgument(0).sourceText());
            return new PiAgentViralJudge.RewriteJudgment(false, 20, "", "", "本例仅记录候选顺序", "");
        });
        var publish = mock(XPublishClient.class);
        var service = new AutoRepostService(store, null, judge, mock(XOAuthService.class), publish, clock);
        try {
            service.runOwnerIdleRepostPatrol();
            assertEquals(List.of(hot, middle, low), assessed);
            verifyNoInteractions(publish);
        } finally {
            service.shutdown();
        }
    }

    @Test void patrolStopsRefreshingOtherSourcesWhenThePublishingAccountChanges() {
        Clock clock = Clock.fixed(LocalDate.now(AssistantRetention.ZONE).atTime(9, 0).atZone(AssistantRetention.ZONE).toInstant(), AssistantRetention.ZONE);
        store.tx(tx -> {
            tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "owner"));
            tx.put("meta", "owner_latest_tweet", object().put("accountId", "42").put("username", "owner").put("checkedAt", clock.millis()));
            tx.put("source", "8", source("8", 1));
            return tx.put("source", "7", source("7", 30));
        });
        var collector = mock(SourceCollector.class);
        when(collector.syncSourceMaterialsForRewrite(any(), anyLong())).thenAnswer(call -> {
            store.tx(tx -> tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "99").put("username", "other")));
            return List.of();
        });
        var judge = mock(PiAgentViralJudge.class);
        var publish = mock(XPublishClient.class);
        var service = new AutoRepostService(store, collector, judge, mock(XOAuthService.class), publish, clock);
        try {
            service.runOwnerIdleRepostPatrol();
            verify(collector, times(1)).syncSourceMaterialsForRewrite(any(), anyLong());
            verifyNoInteractions(judge, publish);
        } finally {
            service.shutdown();
        }
    }

    @Test void discoveryDropsAnOldCursorAtMidnightAndStopsAtTheFirstNonPinnedPostFromYesterday() {
        Instant midnight = LocalDate.now(AssistantRetention.ZONE).atStartOfDay(AssistantRetention.ZONE).toInstant();
        Clock clock = Clock.fixed(midnight.plusSeconds(60), AssistantRetention.ZONE);
        var provider = mock(TwitterApiIoProvider.class);
        var collector = new SourceCollector(store, provider, null, mapper, clock);
        ObjectNode configured = store.tx(tx -> tx.put("source", "7", source("7", 5)
                .put("rewriteCursor", "yesterday-page").put("rewriteCursorProvider", SourceCollector.TIMELINE_PROVIDER)
                .put("rewriteCursorEndpoint", SourceCollector.TIMELINE_ENDPOINT).put("rewriteBackfillComplete", false)
                .put("rewriteLastAttemptAt", midnight.minusSeconds(60).toEpochMilli())));
        TextPost current = post("701", midnight);
        TextPost pinnedOld = post("702", midnight.minusSeconds(1000));
        when(pinnedOld.pinned()).thenReturn(true);
        TextPost old = post("703", midnight.minusMillis(1));
        when(provider.fetchTextPostsPage(eq("author7"), eq("7"), eq(""), eq(40), any()))
                .thenReturn(new TextPostPage(null, List.of(pinnedOld, current, old), "older-page", false));
        try {
            var posts = collector.syncSourceMaterialsForRewrite(configured, AutoRepostService.REWRITE_MAX_AGE_MS);
            assertEquals(List.of("701"), posts.stream().map(row -> row.path("id").asText()).toList());
            verify(provider).fetchTextPostsPage(eq("author7"), eq("7"), eq(""), eq(40), any());
            var after = store.tx(tx -> tx.get("source", "7"));
            assertTrue(after.path("rewriteBackfillComplete").asBoolean());
            assertTrue(after.path("rewriteCursor").asText().isBlank());
            assertNull(store.tx(tx -> tx.get("material", "703")), "dedicated discovery must not ingest yesterday's material");
        } finally {
            collector.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void manualFirstUseCanGenerateFromTodaysOnDemandOriginal(boolean alreadyHasYesterday, boolean focusedOnYesterday) {
        Instant midnight = LocalDate.now(AssistantRetention.ZONE).atStartOfDay(AssistantRetention.ZONE).toInstant();
        Clock clock = Clock.fixed(midnight.plusSeconds(3600), AssistantRetention.ZONE);
        store.tx(tx -> {
            tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "owner"));
            tx.put("source", "7", source("7", 5).put("autoRewriteEnabled", false));
            if (alreadyHasYesterday) putPost(tx, "700", "7", "Yesterday the same author reported strong traffic for a different independent story.",
                    midnight.minusMillis(1).toEpochMilli(), 500_000, 4_000);
            return null;
        });
        var provider = mock(TwitterApiIoProvider.class);
        var firstPage = new TextPostPage(null, List.of(post("701", midnight.plusSeconds(30))), "", true);
        when(provider.fetchTextPostsPage(eq("author7"), eq("7"), eq(""), eq(10), any()))
                .thenReturn(firstPage);
        var collector = new SourceCollector(store, provider, null, mapper, clock);
        var judge = mock(PiAgentViralJudge.class);
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(new PiAgentViralJudge.RewriteJudgment(
                true, 91, "", "", "", "An independently written preview for this freshly observed original."));
        var publish = mock(XPublishClient.class);
        var service = new AutoRepostService(store, collector, judge, mock(XOAuthService.class), publish, clock);
        try {
            var request = object().put("rewriteToday", true);
            if (focusedOnYesterday) request.put("materialId", "700");
            var timeline = service.authorTimeline("7", request);
            assertEquals(1, timeline.at("/summary/wouldRewriteCount").asInt());
            var preview = service.previewRewrite("7", object().put("materialId", "701"));
            assertEquals("701", preview.at("/material/id").asText());
            assertFalse(preview.at("/rewrite/previewId").asText().isBlank());
            assertNotNull(store.tx(tx -> tx.rewriteMaterial("701", clock.millis())));
            verify(provider, times(1)).fetchTextPostsPage(eq("author7"), eq("7"), eq(""), eq(10), any());
            verifyNoInteractions(publish);
        } finally {
            service.shutdown();
            collector.stop();
        }
    }

    private TextPost post(String id, Instant time) {
        TextPost post = mock(TextPost.class);
        when(post.id()).thenReturn(id);
        when(post.authorId()).thenReturn("7");
        when(post.username()).thenReturn("author7");
        when(post.publishedAt()).thenReturn(time);
        when(post.kind()).thenReturn(TweetKind.POST);
        when(post.text()).thenReturn("A long original observation on the economics of distributed storage, sampled in the field. " + id);
        when(post.views()).thenReturn(25_000L);
        return post;
    }
}
