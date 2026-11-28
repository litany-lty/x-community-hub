package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TodayRewriteSelectionTest {
    private static final long MIDNIGHT = Instant.parse("2026-11-26T16:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;
    private static final String OWNER = "42";
    private static final String AUTHOR = "7";
    private static final String DRAFT = "The final analysis explains what changed for the release team.";
    private static final PiAgentViralJudge.RewriteJudgment APPROVED =
            new PiAgentViralJudge.RewriteJudgment(true, 93, "", "", "qualified", DRAFT);
    @TempDir Path data;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final SourceCollector collector = mock(SourceCollector.class);
    private final PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
    private final XOAuthService oauth = mock(XOAuthService.class);
    private final XPublishClient publisher = mock(XPublishClient.class);
    private AssistantStore store;
    private AutoRepostService service;
    private ObjectNode source;

    private static class MutableClock extends Clock {
        long now = MIDNIGHT + 7 * HOUR;
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }

    @BeforeEach void setup() {
        store = new AssistantStore(data, mapper);
        source = store.tx(tx -> {
            tx.put("meta", "owner", object().put("accountId", OWNER).put("bindingSource", "EXPLICIT").put("username", "owner"));
            return tx.put("source", AUTHOR, object().put("authorId", AUTHOR).put("username", "source")
                    .put("status", "ACTIVE").put("autoRewriteEnabled", true));
        });
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(APPROVED);
        when(judge.evaluateAndWriteRewrite(any())).thenReturn(APPROVED);
        when(oauth.getValidWriteAccessToken(OWNER, false)).thenReturn("local-fixture");
        when(publisher.createTweet(anyString(), anyString(), eq(List.of()), isNull())).thenReturn("930000");
        service = new AutoRepostService(store, collector, judge, oauth, publisher, clock);
    }

    @AfterEach void stop() { service.shutdown(); }

    private ObjectNode post(String id, long published, long views, Long followers) {
        var m = object().put("id", id).put("authorId", AUTHOR).put("username", "source").put("kind", "POST")
                .put("text", "The project team collected independent timing measurements for release " + id + ".")
                .put("publishedAt", published).put("ownerImportedAt", clock.now).put("views", views);
        if (followers != null) m.put("authorFollowersCount", followers);
        return store.tx(tx -> tx.put("material", id, m));
    }

    private JsonNode rewrite(ObjectNode timeline, String id) {
        for (var item : timeline.path("items")) {
            if (id.equals(item.at("/material/id").asText())) return item.path("rewrite");
        }
        return fail("Missing timeline item " + id);
    }

    @Test void onlyTheShanghaiDayIsEligibleAndTheHottestExposureLeadsBothPreviewAndPatrol() {
        post("100", MIDNIGHT - 1, 5_000_000, 100L);
        post("101", MIDNIGHT, 60_000, 2_000L);
        post("102", clock.now, 60_000, 300_000L);
        post("103", clock.now + 1, 9_000_000, 1L);
        post("104", clock.now, 60_000, null);
        post("105", clock.now, 60_000, 0L);
        var timeline = service.authorTimeline(AUTHOR, object());
        assertEquals(4, timeline.at("/summary/wouldRewriteCount").asInt());
        assertFalse(rewrite(timeline, "100").path("withinWindow").asBoolean(true));
        assertFalse(rewrite(timeline, "103").path("candidate").asBoolean(true));
        assertEquals(1, rewrite(timeline, "101").path("rank").asInt());
        assertEquals(2, rewrite(timeline, "102").path("rank").asInt());
        assertEquals(3, rewrite(timeline, "104").path("rank").asInt());
        assertEquals(4, rewrite(timeline, "105").path("rank").asInt());
        assertEquals(30.0, rewrite(timeline, "101").path("exposureRate").asDouble());
        assertTrue(rewrite(timeline, "104").path("exposureRate").isNull());
        assertTrue(rewrite(timeline, "105").path("exposureRate").isNull());
        var best = service.previewRewrite(AUTHOR, object());
        assertEquals("101", best.at("/material/id").asText());
        assertEquals(30.0, best.at("/rewrite/exposureRate").asDouble());
        assertEquals("101", service.processAuthorRewriteOnce(source, OWNER, "owner"));
    }

    @Test void missingPostFollowerCountsUseTheSourceProfileAndUnknownViewsStayUnknown() {
        store.tx(tx -> tx.put("source", AUTHOR, source.put("followersCount", 40_000)));
        post("101", MIDNIGHT, 100_000, null);
        post("102", clock.now, 9_000, null);
        var unknownViews = post("103", clock.now, 0, 100L);
        unknownViews.remove("views");
        store.tx(tx -> tx.put("material", "103", unknownViews.put("likes", 90)));
        var timeline = service.authorTimeline(AUTHOR, object());
        assertEquals(2.5, rewrite(timeline, "101").path("exposureRate").asDouble());
        assertEquals(1, rewrite(timeline, "101").path("rank").asInt());
        assertTrue(rewrite(timeline, "103").path("exposureRate").isNull());
    }

    @Test void equalExposureFavorsTheHotterPostAndSmallLowTrafficPostsStayOut() {
        post("101", MIDNIGHT, 50_000, 10_000L);
        post("102", clock.now, 10_000, 2_000L);
        post("103", clock.now, 100, 1L);
        var timeline = service.authorTimeline(AUTHOR, object());
        assertEquals(5.0, rewrite(timeline, "101").path("exposureRate").asDouble());
        assertEquals(5.0, rewrite(timeline, "102").path("exposureRate").asDouble());
        assertEquals(1, rewrite(timeline, "101").path("rank").asInt());
        assertEquals(2, rewrite(timeline, "102").path("rank").asInt());
        assertFalse(rewrite(timeline, "103").path("candidate").asBoolean(true));
        assertEquals("101", service.previewRewrite(AUTHOR, object()).at("/material/id").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aLowSuitabilityManualDraftStaysVisibleUntilMidnightOrAQuoteDelivery(boolean quoteDelivery) {
        post("101", MIDNIGHT, 60_000, 1_000L);
        post("102", clock.now, 10_000, 4_000L);
        var unsuitable = new PiAgentViralJudge.RewriteJudgment(false, 37, "", "", "requires editorial judgment", DRAFT);
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(unsuitable, APPROVED);
        var req = object().put("materialId", "101");
        var manual = service.previewRewrite(AUTHOR, req).path("rewrite");
        assertTrue(manual.path("manualReviewPreview").asBoolean());
        assertFalse(manual.path("candidate").asBoolean(true));
        assertEquals(DRAFT, manual.path("rewrittenText").asText());
        var timeline = service.authorTimeline(AUTHOR, object());
        assertEquals(1, timeline.at("/summary/wouldRewriteCount").asInt());
        assertTrue(rewrite(timeline, "101").path("manualReviewPreview").asBoolean());
        assertEquals(0, rewrite(timeline, "101").path("rank").asInt());
        assertTrue(service.previewRewrite(AUTHOR, req).at("/rewrite/manualReviewPreview").asBoolean());
        assertEquals("102", service.previewRewrite(AUTHOR, object()).at("/material/id").asText());

        if (quoteDelivery) {
            store.tx(tx -> tx.put("auto_repost", "101", object().put("ownerAccountId", OWNER).put("authorId", AUTHOR)
                    .put("materialId", "101").put("status", "PUBLISHED").put("publishedTweetId", "939999").put("publishedAt", clock.now)));
        } else {
            clock.now = MIDNIGHT + 24 * HOUR;
        }
        assertFalse(rewrite(service.authorTimeline(AUTHOR, object()), "101").path("manualReviewPreview").asBoolean());
        assertFalse(service.previewRewrite(AUTHOR, req).at("/rewrite/manualReviewPreview").asBoolean());
        verify(judge, times(2)).evaluateAndWriteRewrite(any(), eq(true));
        verifyNoInteractions(oauth, publisher);
    }

    @Test void aReviewedMorningDraftRemainsInALongTimelineAlongsideTheTopAutomaticCandidates() {
        for (int i = 0; i < 20; i++) post(Integer.toString(100 + i), clock.now, 1, 5_000L);
        for (int i = 0; i < 7; i++) post(Integer.toString(200 + i), MIDNIGHT, 20_000 + i * 1_000L, 5_000L);
        assertEquals(1, rewrite(service.authorTimeline(AUTHOR, object()), "206").path("rank").asInt());
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(
                new PiAgentViralJudge.RewriteJudgment(false, 30, "", "", "requires editorial judgment", DRAFT));
        service.previewRewrite(AUTHOR, object().put("materialId", "206"));
        var timeline = service.authorTimeline(AUTHOR, object());
        assertEquals(6, timeline.at("/summary/wouldRewriteCount").asInt());
        assertTrue(rewrite(timeline, "206").path("manualReviewPreview").asBoolean());
        assertEquals(1, rewrite(timeline, "205").path("rank").asInt());
    }

    @Test void yesterdaysCachedPreviewCannotBeReusedOrRegeneratedButItsPublicationStillReads() {
        post("101", MIDNIGHT, 30_000, 1_000L);
        var req = object().put("materialId", "101");
        assertFalse(service.previewRewrite(AUTHOR, req).at("/rewrite/previewId").asText().isBlank());
        clock.now = MIDNIGHT + 24 * HOUR;
        for (boolean force : List.of(false, true)) {
            var stale = service.previewRewrite(AUTHOR, req.put("forceRegenerate", force)).path("rewrite");
            assertFalse(stale.path("candidate").asBoolean(true));
            assertFalse(stale.path("cached").asBoolean(true));
            assertTrue(stale.path("rewrittenText").asText().isBlank());
            assertTrue(stale.path("previewId").asText().isBlank());
        }
        assertEquals(404, assertThrows(XDataProviderException.class, () -> service.previewRewrite(AUTHOR, object())).getStatusCode());
        assertEquals("", service.processAuthorRewriteOnce(source, OWNER, "owner"));
        var receipt = store.tx(tx -> tx.put("auto_rewrite", "101", object().put("materialId", "101")
                .put("authorId", AUTHOR).put("ownerAccountId", OWNER).put("status", "PUBLISHED").put("rewrittenText", DRAFT)
                .put("publishedTweetId", "930001").put("publishedTweetUrl", "https://x.com/owner/status/930001")));
        var history = service.previewRewrite(AUTHOR, req.put("forceRegenerate", true)).path("rewrite");
        assertEquals("PUBLISHED", history.path("status").asText());
        assertEquals(DRAFT, history.path("rewrittenText").asText());
        assertEquals(0, history.path("rank").asInt());
        assertFalse(history.path("candidate").asBoolean(true));
        assertEquals(receipt, store.tx(tx -> tx.get("auto_rewrite", "101")));
        verify(judge, times(1)).evaluateAndWriteRewrite(any(), eq(true));
        verifyNoMoreInteractions(judge);
        verifyNoInteractions(oauth, publisher);
    }

    @Test void crossingMidnightDuringPreviewDoesNotCreateOrReturnAnActionableDraft() {
        clock.now = MIDNIGHT + 24 * HOUR - 1;
        post("101", clock.now - HOUR, 15_000, 1_000L);
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenAnswer(call -> { clock.now++; return APPROVED; });
        var result = service.previewRewrite(AUTHOR, object().put("materialId", "101")).path("rewrite");
        assertFalse(result.path("withinWindow").asBoolean(true));
        assertFalse(result.path("candidate").asBoolean(true));
        assertTrue(result.path("rewrittenText").asText().isBlank());
        assertTrue(result.path("previewId").asText().isBlank());
        assertTrue(store.tx(tx -> tx.all("rewrite_preview")).isEmpty());
        verifyNoInteractions(oauth, publisher);
    }

    @Test void crossingMidnightDuringFetchPreventsTheAutomaticClaimAndModelCall() {
        post("101", MIDNIGHT, 15_000, 1_000L);
        when(collector.syncSourceMaterialsForRewrite(any(), anyLong())).thenAnswer(call -> {
            clock.now = MIDNIGHT + 24 * HOUR;
            return List.of();
        });
        assertEquals("", service.processAuthorRewriteOnce(source, OWNER, "owner"));
        assertNull(store.tx(tx -> tx.get("auto_rewrite", "101")));
        verifyNoInteractions(judge, oauth, publisher);
    }

    @Test void crossingMidnightWhileWaitingForTheHttpPermitPreventsAnyWireSend() {
        clock.now = MIDNIGHT + 24 * HOUR - 1;
        post("101", MIDNIGHT, 15_000, 1_000L);
        var props = new XOpsProperties();
        props.setOfficialBaseUrl("http://127.0.0.1:1/2");
        props.setAutoRepostQuietStartHour(0);
        props.setAutoRepostQuietEndHour(0);
        var guard = mock(UpstreamBudgetGuard.class);
        doAnswer(call -> { clock.now = MIDNIGHT + 24 * HOUR; return null; }).when(guard).acquirePermit(any(URI.class), anyString());
        var pool = new SharedApiExecutor(props);
        var http = mock(HttpClient.class);
        var real = new XPublishClient(props, mapper, pool, guard);
        ReflectionTestUtils.setField(real, "httpClient", http);
        var delayed = new AutoRepostService(store, collector, judge, oauth, real, clock);
        ReflectionTestUtils.setField(delayed, "properties", props);
        try {
            assertEquals("", delayed.processAuthorRewriteOnce(source, OWNER, "owner"));
            verify(guard).acquirePermit(any(URI.class), anyString());
            verifyNoInteractions(http);
            assertNull(store.tx(tx -> tx.get("publication", "auto-rewrite-101")));
        } finally {
            delayed.shutdown();
            pool.shutdown();
        }
    }
}
