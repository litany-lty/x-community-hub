package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
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

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.xops.hub.assistant.AssistantStore.object;
import static com.xops.hub.assistant.AutoRepostService.AutoPublishMode.REWRITE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewriteRecoveryBoundaryTest {
    private static final String SOURCE = "The team measured release cycles across six departments and published its initial estimates.";
    private static final String CORRECTION = "The team withdrew its earlier forecasts after independent observers discovered a different result.";
    private static final String FINAL = "Independent final draft about release planning.";
    private static final String NEW_DRAFT = "A freshly assessed alternative draft.";
    private static final String OWNER = "42";
    private static final String MATERIAL = "101";
    @TempDir Path data;
    private final MutableClock clock = new MutableClock();
    private final PiAgentViralJudge model = mock(PiAgentViralJudge.class);
    private final XOAuthService oauth = mock(XOAuthService.class);
    private final XPublishClient publisher = mock(XPublishClient.class);
    private AssistantStore store;
    private AutoRepostService service;

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-27T01:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        void advance(Duration elapsed) { now = now.plus(elapsed); }
    }

    @BeforeEach void setup() {
        store = new AssistantStore(data, new ObjectMapper());
        save("meta", "owner", object().put("accountId", OWNER).put("bindingSource", "EXPLICIT").put("username", "owner"));
        save("source", "7", object().put("authorId", "7").put("username", "source").put("status", "ACTIVE").put("autoRewriteEnabled", true));
        when(oauth.getValidWriteAccessToken(OWNER, false)).thenReturn("local-only");
        when(publisher.createTweet(anyString(), anyString(), eq(List.of()), isNull())).thenReturn("911199");
        when(model.evaluateAndWriteRewrite(any())).thenReturn(approved(NEW_DRAFT));
        service = new AutoRepostService(store, mock(SourceCollector.class), model, oauth, publisher, clock);
    }

    @AfterEach void stop() { service.shutdown(); }

    private ObjectNode get(String kind, String id) { return store.tx(tx -> tx.get(kind, id)); }
    private ObjectNode save(String kind, String id, ObjectNode row) { return store.tx(tx -> tx.put(kind, id, row)); }
    private ObjectNode source() { return get("source", "7"); }
    private ObjectNode record() { return get("auto_rewrite", MATERIAL); }
    private void cache(ObjectNode row) { store.tx(tx -> { tx.cacheRewriteMaterial(row, "topic"); return null; }); }
    private ObjectNode sourcePost(int days, int views) {
        return object().put("id", MATERIAL).put("kind", "POST").put("authorId", "7").put("username", "source")
                .put("text", SOURCE).put("views", views).put("publishedAt", clock.millis() - Duration.ofDays(days).toMillis());
    }
    private ObjectNode finalRecord(String status) {
        return object().put("materialId", MATERIAL).put("authorId", "7").put("ownerAccountId", OWNER)
                .put("topicId", "topic").put("status", status).put("rewrittenText", FINAL);
    }
    private PiAgentViralJudge.RewriteJudgment approved(String text) {
        return new PiAgentViralJudge.RewriteJudgment(true, 94, "", "", "approved", text);
    }
    private void waitForRetry() throws Exception {
        ((ExecutorService) ReflectionTestUtils.getField(service, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    @Test void aChangingViewCountPreservesTheFrozenDraftFromOlderPreviewFingerprints() throws Exception {
        ObjectNode post = sourcePost(9, 20100);
        cache(post);
        save("auto_rewrite", MATERIAL, finalRecord("FAILED").put("previewMaterialFingerprint", AutoRepostService.previewMaterialFingerprint(post)));
        cache(post.deepCopy().put("views", 20101));
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
        verifyNoInteractions(model);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void correctedSourcesRequireExplicitReassessmentEvenForUnversionedLegacyBackfills(boolean modern) throws Exception {
        ObjectNode first = sourcePost(0, 30000);
        cache(first);
        ObjectNode saved = finalRecord("FAILED");
        if (modern) saved.put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(first));
        save("auto_rewrite", MATERIAL, saved);
        cache(first.deepCopy().put("text", CORRECTION));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        verifyNoInteractions(model, publisher);
        service.retry(MATERIAL, REWRITE, true);
        waitForRetry();
        verify(publisher).createTweet("local-only", NEW_DRAFT, List.of(), null);
    }

    @Test void aKnownCorrectionRemainsBlockedAfterEverySourceSnapshotExpires() {
        ObjectNode original = sourcePost(9, 30000);
        cache(original);
        save("auto_rewrite", MATERIAL, finalRecord("FAILED").put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(original)));
        cache(original.deepCopy().put("text", CORRECTION));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE, true)).getStatusCode());
        clock.advance(Duration.ofDays(31));
        assertNull(store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis())));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        verifyNoInteractions(oauth, model, publisher);
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "AUTH_REQUIRED", "SKIPPED_COOLDOWN",
            "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS", "SKIPPED_LIMIT"})
    void explicitRetryReusesExpiredFinalsAndStillDemandsABody(String status) throws Exception {
        ObjectNode missing = finalRecord(status);
        missing.remove("rewrittenText");
        save("auto_rewrite", MATERIAL, missing);
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        assertEquals(status, record().path("status").asText());
        save("auto_rewrite", MATERIAL, finalRecord(status));
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
        verifyNoInteractions(model);
    }

    @ParameterizedTest @ValueSource(strings = {"SENDING", "UNCERTAIN", "OWNER_REVIEW_REQUIRED", "PUBLISHED", "SKIPPED"})
    void unavailableSourceNeverMakesAnIneligibleStatusRetryable(String status) {
        save("auto_rewrite", MATERIAL, finalRecord(status));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        assertEquals(status, record().path("status").asText());
        verifyNoInteractions(oauth, model, publisher);
    }

    @Test void anInterruptedAssessmentCannotTreatLeftoverTextAsAnApprovedExpiredFinal() {
        save("auto_rewrite", MATERIAL, finalRecord("JUDGE_FAILED"));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        assertEquals("JUDGE_FAILED", record().path("status").asText());
        verifyNoInteractions(oauth, model, publisher);
    }

    @ParameterizedTest @ValueSource(strings = {"SENDING", "UNCERTAIN"})
    void archiveChangeNeverLetsTheNewOwnerVerifyOrClaimAnUnattributedAttempt(String status) {
        save("meta", "owner", object().put("accountId", "former").put("username", "former-handle"));
        ObjectNode old = finalRecord(status);
        old.remove("ownerAccountId");
        save("auto_rewrite", MATERIAL, old);
        save("publication", "legacy-manual", object().put("source", "MANUAL_QUOTE_REPOST").put("status", "UNCERTAIN"));
        String restored = AuthorIdentity.ARCHIVE_ACCOUNT_ID;
        store.tx(tx -> tx.observeOwner(restored, "restored", clock.millis()));
        service.recoverStaleRecords();
        assertTrue(record().path("ownerReviewRequired").asBoolean());
        assertTrue(get("publication", "legacy-manual").path("ownerReviewRequired").asBoolean());
        ObjectNode decision = object().put("version", record().path("version").asInt()).put("published", false);
        assertEquals(403, assertThrows(XDataProviderException.class, () -> service.verifyAutomaticPublication(restored, MATERIAL, REWRITE, decision)).getStatusCode());
        save("meta", "settings", object().put("enabled", true));
        save("source", "7", source().put("status", "ACTIVE").put("autoRewriteEnabled", true));
        assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE));
        verifyNoInteractions(model, publisher);
    }

    @Test void verifiedBindingWithNoInterveningOwnerKeepsItsLegacyReceiptActionable() throws Exception {
        ObjectNode sameOwner = finalRecord("UNCERTAIN");
        sameOwner.remove("ownerAccountId");
        save("auto_rewrite", MATERIAL, sameOwner);
        ObjectNode decision = object().put("version", record().path("version").asInt()).put("published", false);
        assertEquals("FAILED", service.verifyAutomaticPublication(OWNER, MATERIAL, REWRITE, decision).path("status").asText());
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        assertEquals(OWNER, record().path("ownerAccountId").asText());
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
    }

    @Test void retainedMaterialAndItsPurgeCannotRollBackCorrectedHistoryOrMetrics() {
        ObjectNode first = sourcePost(9, 300).put("ownerImportedAt", clock.millis() - Duration.ofDays(2).toMillis());
        store.tx(tx -> { AssistantWorkflow.ingest(tx, first); return null; });
        cache(first);
        ObjectNode moreViews = first.deepCopy().put("views", 50000);
        store.tx(tx -> { AssistantWorkflow.ingest(tx, moreViews); return null; });
        assertEquals(50000, get("material", MATERIAL).path("views").asInt());
        cache(moreViews.deepCopy().put("text", CORRECTION));
        when(model.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(NEW_DRAFT));
        var archived = service.previewRewrite("7", object().put("materialId", MATERIAL));
        assertEquals(CORRECTION, archived.at("/material/text").asText());
        assertFalse(archived.at("/rewrite/candidate").asBoolean(true));
        verifyNoInteractions(model);
        store.purgeExpiredContent(clock.millis(), clock.millis() - Duration.ofDays(30).toMillis());
        assertNull(get("material", MATERIAL));
        ObjectNode retained = store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis()));
        assertEquals(CORRECTION, retained.path("text").asText());
        assertEquals(50000, retained.path("views").asInt());
    }

    @Test void aLaterRegularIngestUpdatesTheExistingThirtyDaySnapshotForHistoricalReads() {
        ObjectNode first = sourcePost(9, 300);
        cache(first);
        store.tx(tx -> { AssistantWorkflow.ingest(tx, first); return null; });
        ObjectNode corrected = first.deepCopy().put("text", CORRECTION).put("views", 54000);
        store.tx(tx -> { AssistantWorkflow.ingest(tx, corrected); return null; });
        ObjectNode retained = store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis()));
        assertEquals(CORRECTION, retained.path("text").asText());
        assertEquals(54000, retained.path("views").asInt());
        List<ObjectNode> history = store.tx(tx -> tx.rewriteWindow("7", "source", clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS, clock.millis()));
        assertEquals(1, history.size());
        assertEquals(CORRECTION, history.get(0).path("text").asText());
        cache(corrected.deepCopy().put("text", "The most recent observation separately revises the field methodology and its reported results."));
        save("material", MATERIAL, get("material", MATERIAL).put("ownerImportedAt", clock.millis()));
        assertTrue(store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis())).path("text").asText().startsWith("The most recent"));
    }

    @Test void retryAfterMovingTheMaterialChecksItsCurrentTopicBeforeSending() {
        ObjectNode material = sourcePost(1, 30000);
        save("material", MATERIAL, material);
        save("material", "202", material.deepCopy().put("id", "202"));
        save("membership", MATERIAL, object().put("topicId", "former"));
        save("membership", "202", object().put("topicId", "destination"));
        ObjectNode former = object(); former.putArray("materialIds").add(MATERIAL);
        ObjectNode destination = object(); destination.putArray("materialIds").add("202");
        int formerVersion = save("topic", "former", former).path("version").asInt();
        int destinationVersion = save("topic", "destination", destination).path("version").asInt();
        save("auto_rewrite", MATERIAL, finalRecord("FAILED").put("topicId", "former"));
        store.tx(tx -> AssistantTopics.move(tx, List.of(MATERIAL), "destination",
                object().put("former", formerVersion).put("destination", destinationVersion), "OWNER"));
        save("auto_rewrite", "202", object().put("materialId", "202").put("topicId", "destination").put("status", "UNCERTAIN")
                .put("dispatchedAt", clock.millis() - Duration.ofHours(3).toMillis()));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        verifyNoInteractions(model, oauth, publisher);
    }

    @ParameterizedTest @ValueSource(strings = {"media", "expired", "views"})
    void automaticGenerationMustStillQualifyWhenItsModelReturns(String change) {
        ObjectNode post = sourcePost(0, 30000);
        cache(post);
        when(model.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
            if (change.equals("expired")) clock.now = Instant.parse("2026-11-27T16:00:00Z");
            else if (change.equals("media")) cache(post.deepCopy().put("hasMedia", true));
            else cache(post.deepCopy().put("views", 1));
            return approved(NEW_DRAFT);
        });
        assertEquals("", service.processAuthorRewriteOnce(source(), OWNER, "owner"));
        assertEquals("JUDGE_FAILED", record().path("status").asText());
        assertFalse(record().has("rewrittenText"));
        verifyNoInteractions(oauth, publisher);
    }

    @Test void aNewOwnerPostDuringTheHttpPermitWaitBlocksTheRewriteRequest() throws Exception {
        var config = new XOpsProperties();
        config.setOfficialBaseUrl("http://127.0.0.1:1/2");
        var guard = mock(UpstreamBudgetGuard.class);
        doAnswer(call -> {
            save("publication", "other-post", object().put("status", "PUBLISHED").put("publishedAt", clock.millis()).put("publishedTweetId", "910001"));
            return null;
        }).when(guard).acquirePermit(any(java.net.URI.class), anyString());
        var http = mock(HttpClient.class);
        var pool = new SharedApiExecutor(config);
        var real = new XPublishClient(config, new ObjectMapper(), pool, guard);
        ReflectionTestUtils.setField(real, "httpClient", http);
        var queued = new AutoRepostService(store, mock(SourceCollector.class), model, oauth, real, clock);
        try {
            cache(sourcePost(0, 30000));
            assertEquals("", queued.processAuthorRewriteOnce(source(), OWNER, "owner"));
            assertEquals("SKIPPED_COOLDOWN", record().path("status").asText());
            assertEquals(NEW_DRAFT, record().path("rewrittenText").asText());
            verifyNoInteractions(http);
        } finally { queued.shutdown(); pool.shutdown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anAmbiguousStandaloneQueueCopyBlocksRetryOfTheFrozenRewrite(boolean registered) {
        save("auto_rewrite", MATERIAL, finalRecord("FAILED"));
        var config = new XOpsProperties();
        config.setDiskCacheDir(data.resolve("scheduler").toString());
        var queue = new com.xops.hub.service.ScheduledTweetService(config, new ObjectMapper(), oauth, publisher, clock);
        queue.setAssistantStore(store);
        ReflectionTestUtils.setField(service, "scheduledTweetService", queue);
        when(oauth.getTokenCapability(OWNER)).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(publisher.createTweet("local-only", FINAL, List.of())).thenThrow(new XPublishClient.XPublishException(504, "模拟端已收到正文", true));
        try {
            String jobId = queue.createScheduledTweet(OWNER, "owner", FINAL, clock.millis() + 60_000, "from-card", List.of()).id();
            clock.advance(Duration.ofSeconds(61));
            queue.processDueJobsForAccount(OWNER);
            var guard = get("scheduled_delivery", jobId);
            assertEquals("UNCERTAIN", guard.path("status").asText());
            if (registered) {
                new AssistantPublishing(store, queue, oauth, publisher).verifyScheduledPublication(OWNER, jobId,
                        object().put("version", guard.path("version").asInt()).put("published", true).put("tweetId", "345678"));
            }
            clock.advance(Duration.ofHours(4));
            assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
            assertEquals("FAILED", record().path("status").asText());
            verify(publisher).createTweet("local-only", FINAL, List.of());
            verifyNoMoreInteractions(publisher);
            verifyNoInteractions(model);
        } finally { queue.shutdown(); }
    }

    @Test void reachingTheDailyLimitLeavesTheExplicitFailedDraftRetryableTomorrow() throws Exception {
        save("auto_rewrite", MATERIAL, finalRecord("FAILED"));
        save("meta", "settings", object().put("enabled", true).put("autoRepostDailyLimit", 1).put("autoRepostCooldownMinutes", 0));
        save("auto_repost", "202", object().put("status", "PUBLISHED").put("materialId", "202").put("publishedTweetId", "911001").put("publishedAt", clock.millis()));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        assertEquals("FAILED", record().path("status").asText());
        clock.advance(Duration.ofDays(1));
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
    }

    @Test void theDashboardKeepsOldRetryableFinalsSeparateFromTheRecentActivityWindow() {
        save("auto_rewrite", MATERIAL, finalRecord("SKIPPED_QUIET_HOURS"));
        save("auto_rewrite", "202", finalRecord("FAILED").put("materialId", "202").put("ownerAccountId", "different"));
        for (int index = 0; index < 24; index++) {
            save("auto_repost", "new-" + index, object().put("status", "SKIPPED"));
        }
        var sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        var controller = new AssistantController(store, sources, mock(AssistantWorker.class), mock(AssistantPublishing.class), new ObjectMapper());
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute("assistantOwner", new AuthenticatedXUser(OWNER, "owner", "Owner", "", false, clock.instant()));
        var actions = controller.dashboard(20, 0, null, "", "", "", "", "score", request).path("retryableAutoPublications");
        assertEquals(1, actions.size());
        assertEquals(MATERIAL, actions.get(0).path("materialId").asText());
        assertFalse(actions.get(0).path("canReassess").asBoolean());
    }

    @Test void publishedTimelineAndPreviewShowTheDeliveredTextAndManualVariantsHaveTheirOwnLabel() {
        cache(sourcePost(0, 30000));
        when(model.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(FINAL));
        ObjectNode request = object().put("materialId", MATERIAL);
        assertEquals(FINAL, service.previewRewrite("7", request).at("/rewrite/rewrittenText").asText());
        assertEquals(MATERIAL, service.processAuthorRewriteOnce(source(), OWNER, "owner"));
        assertEquals(NEW_DRAFT, service.authorTimeline("7", object()).at("/items/0/rewrite/rewrittenText").asText());
        assertEquals(NEW_DRAFT, service.previewRewrite("7", request).at("/rewrite/rewrittenText").asText());
        var variant = service.previewRewrite("7", request.put("forceRegenerate", true)).path("rewrite");
        assertTrue(variant.path("unsentPreview").asBoolean());
        assertTrue(variant.path("publishedTweetUrl").asText().isBlank());
        clock.advance(Duration.ofDays(1));
        var historical = service.previewRewrite("7", request).path("rewrite");
        assertEquals("PUBLISHED", historical.path("status").asText());
        assertEquals(NEW_DRAFT, historical.path("rewrittenText").asText());
        assertFalse(historical.path("publishedTweetUrl").asText().isBlank());
        assertFalse(historical.path("candidate").asBoolean(true));
        verify(model, times(2)).evaluateAndWriteRewrite(any(), eq(true));
        verify(publisher).createTweet("local-only", NEW_DRAFT, List.of(), null);
    }

    @Test void definitiveModelFailuresAreExcludedFromTheWouldRewriteFilter() {
        cache(sourcePost(0, 30000));
        when(model.evaluateAndWriteRewrite(any())).thenThrow(new IllegalStateException("model unavailable"));
        assertEquals("", service.processAuthorRewriteOnce(source(), OWNER, "owner"));
        var timeline = service.authorTimeline("7", object());
        assertEquals(0, timeline.at("/summary/wouldRewriteCount").asInt());
        assertEquals("JUDGE_FAILED", timeline.at("/items/0/rewrite/status").asText());
    }
}
