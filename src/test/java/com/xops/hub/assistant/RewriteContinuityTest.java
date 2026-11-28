package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.ScheduledTweetModels.RewriteSource;
import com.xops.hub.model.ScheduledTweetModels.CreateScheduledTweetJsonRequest;
import com.xops.hub.model.ScheduledTweetModels.BatchCreateItemMeta;
import com.xops.hub.model.ScheduledTweetModels.BatchCreateJsonRequest;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.controller.ScheduledTweetController;
import com.xops.hub.service.ScheduledTweetService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
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

class RewriteContinuityTest {
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
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void correctionSurvivesUncertainDeliveryOrAuthorizationFailureAndSourceExpiry(boolean uncertain) {
        var original = sourcePost(0, 30_000);
        cache(original);
        if (uncertain) {
            when(publisher.createTweet(anyString(), anyString(), eq(List.of()), isNull()))
                    .thenThrow(new XPublishClient.XPublishException(504, "simulated lost response", true));
        } else {
            when(oauth.getValidWriteAccessToken(OWNER, false)).thenAnswer(call -> {
                cache(original.deepCopy().put("text", CORRECTION));
                throw new IllegalStateException("simulated unavailable token");
            });
        }
        service.processAuthorRewriteOnce(source(), OWNER, "owner");
        if (uncertain) cache(original.deepCopy().put("text", CORRECTION));
        assertTrue(record().path("rewriteSourceNeedsReview").asBoolean());
        assertEquals(uncertain ? "UNCERTAIN" : "AUTH_REQUIRED", record().path("status").asText());
        assertEquals(NEW_DRAFT, record().path("rewrittenText").asText());
        clock.advance(Duration.ofDays(31));
        store.purgeExpiredContent(clock.millis(), clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
        assertNull(store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis())));
        if (uncertain) service.verifyAutomaticPublication(OWNER, MATERIAL, REWRITE,
                object().put("version", record().path("version").asInt()).put("published", false));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
        verify(publisher, times(uncertain ? 1 : 0)).createTweet(anyString(), anyString(), eq(List.of()), isNull());
    }

    private ScheduledTweetService queue(String account) {
        var config = new XOpsProperties();
        config.setDiskCacheDir(data.resolve("scheduler").toString());
        var queue = new ScheduledTweetService(config, new ObjectMapper(), oauth, publisher, clock);
        queue.setAssistantStore(store);
        ReflectionTestUtils.setField(service, "scheduledTweetService", queue);
        when(oauth.getTokenCapability(account)).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        return queue;
    }

    @Test void deletingAConfirmedOrdinarySchedulePreservesItsCooldown() throws Exception {
        save("auto_rewrite", MATERIAL, finalRecord("FAILED"));
        var queue = queue(OWNER);
        try {
            when(publisher.createTweet("local-only", "other content", List.of())).thenReturn("900011");
            var job = queue.createScheduledTweet(OWNER, "owner", "other content", clock.millis() + 60_000, "other-work", List.of());
            clock.advance(Duration.ofSeconds(61));
            queue.processDueJobsForAccount(OWNER);
            assertEquals(ScheduledTweetStatus.PUBLISHED, queue.listJobs(OWNER,"owner").items().get(0).status());
            assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE));
            queue.deleteJob(OWNER, job.id());
            assertEquals("PUBLISHED", get("scheduled_delivery",job.id()).path("status").asText());
            assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE)).getStatusCode());
            clock.advance(Duration.ofHours(2));
            service.retry(MATERIAL, REWRITE);
            waitForRetry();
            verify(publisher).createTweet("local-only", FINAL, List.of(), null);
        } finally { queue.shutdown(); }
    }

    private String restoreToNewOwner() {
        save("meta", "owner", object().put("accountId", OWNER).put("username", "former"));
        String nextOwner = AuthorIdentity.ARCHIVE_ACCOUNT_ID;
        store.observeOAuthOwner(new AuthenticatedXUser(nextOwner, "restored", "Restored", "", false, clock.instant()));
        save("meta", "settings", object().put("enabled", true));
        save("source", "7", source().put("status", "ACTIVE").put("autoRewriteEnabled", true));
        return nextOwner;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void queueRechecksTheBoundOwnerOfAnEditedRewriteAtDispatch(boolean rebound) {
        var original = sourcePost(1, 30_000);
        cache(original);
        var row = save("auto_rewrite", MATERIAL, finalRecord("SKIPPED_LIMIT")
                .put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(original)));
        var reference = new RewriteSource(OWNER, MATERIAL, row.path("version").asInt());
        String edited = "Owner's edited final from the timeline.";
        var queue = queue(OWNER);
        try {
            long due = clock.millis() + 60_000;
            var publishing = new AssistantPublishing(store, queue, oauth, publisher);
            var source = publishing.prepareRewrite(OWNER, reference, "rw-intent", edited, due, List.of(), null);
            var job = queue.createScheduledTweet(OWNER, "owner", edited, due, "rw-intent", List.of(), source);
            assertEquals(reference, job.assistantSource().rewriteSource());
            if (rebound) restoreToNewOwner();
            when(publisher.createTweet("local-only", edited, List.of())).thenReturn("919104");
            clock.advance(Duration.ofSeconds(61));
            queue.processDueJobsForAccount(OWNER);
            var status = queue.listJobs(OWNER,"owner").items().get(0).status();
            if (rebound) {
                assertNotEquals(ScheduledTweetStatus.PUBLISHED, status);
                verifyNoInteractions(publisher);
            } else {
                assertEquals(ScheduledTweetStatus.PUBLISHED, status);
                verify(publisher).createTweet("local-only", edited, List.of());
            }
        } finally { queue.shutdown(); }
    }

    @Test void cacheOnlyCorrectionFromTheEarlierCollectorIsMarkedOnReopenBeforeExpiry() throws Exception {
        var original = sourcePost(29, 30_000);
        cache(original);
        save("auto_rewrite", MATERIAL, finalRecord("UNCERTAIN")
                .put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(original)));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("assistant.sqlite"));
             var update = connection.prepareStatement("UPDATE rewrite_material_cache SET data=? WHERE id=?")) {
            update.setString(1, original.deepCopy().put("text", CORRECTION).toString());
            update.setString(2, MATERIAL);
            assertEquals(1, update.executeUpdate());
        }
        service.shutdown();
        store = new AssistantStore(data, new ObjectMapper());
        service = new AutoRepostService(store, mock(SourceCollector.class), model, oauth, publisher, clock);
        clock.advance(Duration.ofDays(2));
        store.purgeExpiredContent(clock.millis(), clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
        assertNull(store.tx(tx -> tx.rewriteMaterial(MATERIAL,clock.millis())));
        assertTrue(record().path("rewriteSourceNeedsReview").asBoolean());
        service.verifyAutomaticPublication(OWNER, MATERIAL, REWRITE,
                object().put("version", record().path("version").asInt()).put("published", false));
        assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL,REWRITE));
        verifyNoInteractions(publisher,model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED","UNCERTAIN","FAILED","OWNER_REVIEW_REQUIRED","SKIPPED_LIMIT","SKIPPED_QUIET_HOURS","SKIPPED_COOLDOWN"})
    void restoredOwnerCannotReadPreviouslyRetainedDrafts(String status) {
        var original = sourcePost(0, 30_000);
        cache(original);
        save("auto_rewrite", MATERIAL, finalRecord(status).put("previewMaterialFingerprint", AutoRepostService.previewMaterialFingerprint(original)));
        String nextOwner = restoreToNewOwner();
        var timeline = service.authorTimeline("7", object().put("materialId", MATERIAL));
        assertEquals(nextOwner, timeline.at("/owner/accountId").asText());
        assertFalse(timeline.toString().contains(FINAL));
        when(model.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(NEW_DRAFT));
        assertEquals(NEW_DRAFT, service.previewRewrite("7", object().put("materialId", MATERIAL)).at("/rewrite/rewrittenText").asText());
        assertEquals(OWNER, record().path("ownerAccountId").asText());
        assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE));
        clock.advance(Duration.ofDays(1));
        var historical = service.previewRewrite("7", object().put("materialId", MATERIAL).put("forceRegenerate", true));
        assertEquals(SOURCE, historical.at("/material/text").asText());
        assertFalse(historical.at("/rewrite/candidate").asBoolean(true));
        assertTrue(historical.at("/rewrite/rewrittenText").asText().isBlank());
        verify(model, times(1)).evaluateAndWriteRewrite(any(), eq(true));
        verifyNoInteractions(publisher);
    }

    @Test void restoredOwnerCannotEnqueueAnOldDraftAndCanPublishAnIndependentOrdinarySchedule() throws Exception {
        var retained = save("auto_rewrite", MATERIAL, finalRecord("SKIPPED_LIMIT"));
        var reference = new RewriteSource(OWNER, MATERIAL, retained.path("version").asInt());
        String nextOwner = restoreToNewOwner();
        var queue = queue(nextOwner);
        try {
            var controller = new ScheduledTweetController(queue, new ObjectMapper());
            ReflectionTestUtils.setField(controller, "publishing", new AssistantPublishing(store, queue));
            var request = new MockHttpServletRequest();
            var session = request.getSession();
            session.setAttribute(com.xops.hub.controller.XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser(nextOwner,"restored","Restored","",false,clock.instant()));
            var response = new MockHttpServletResponse();
            long due = clock.millis() + 60_000;
            var stale = new CreateScheduledTweetJsonRequest(FINAL, due, "stale", null, null, null, null, reference);
            assertEquals(409, assertThrows(XDataProviderException.class, () -> controller.createJson(stale,request,response)).getStatusCode());
            var batch = new BatchCreateJsonRequest(List.of(new BatchCreateItemMeta("edited old final",due,"batch-stale",null,null,null,null,reference)));
            assertEquals(409, assertThrows(XDataProviderException.class, () -> controller.createBatchJson(batch,request,response)).getStatusCode());
            var plain = new CreateScheduledTweetJsonRequest(FINAL, due, "legacy", null, null);
            assertEquals(409, assertThrows(XDataProviderException.class, () -> controller.createJson(plain,request,response)).getStatusCode());
            assertTrue(queue.listJobs(nextOwner,"restored").items().isEmpty());
            String body = "A separate ordinary post written by B.";
            when(oauth.getValidWriteAccessToken(nextOwner,false)).thenReturn("token-B");
            when(publisher.createTweet("token-B",body,List.of())).thenReturn("919102");
            controller.createJson(new CreateScheduledTweetJsonRequest(body,due,"new-post",null,null),request,response);
            clock.advance(Duration.ofSeconds(61));
            queue.processDueJobsForAccount(nextOwner);
            assertEquals(ScheduledTweetStatus.PUBLISHED, queue.listJobs(nextOwner,"restored").items().get(0).status());
            verify(publisher).createTweet("token-B",body,List.of());
            verifyNoMoreInteractions(publisher);
        } finally { queue.shutdown(); }
    }

    @Test void restartBeforeDispatchRestoresApprovedExpiredRetry() throws Exception {
        save("auto_rewrite", MATERIAL, finalRecord("FAILED"));
        var executor = (ExecutorService) ReflectionTestUtils.getField(service, "executor");
        var active = new java.util.concurrent.CountDownLatch(1);
        var hold = new java.util.concurrent.CountDownLatch(1);
        executor.submit(() -> {
            active.countDown();
            hold.await(10, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(active.await(5, TimeUnit.SECONDS));
        assertEquals("EVALUATING", service.retry(MATERIAL, REWRITE).path("status").asText());
        service.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        verifyNoInteractions(publisher, model);
        service = new AutoRepostService(new AssistantStore(data, new ObjectMapper()), mock(SourceCollector.class), model, oauth, publisher, clock);
        service.recoverStaleRecords();
        assertEquals("FAILED", record().path("status").asText());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
        verifyNoInteractions(model);
    }

    @Test void automaticResumeCrossingBeijingMidnightKeepsFinalForExplicitRetry() throws Exception {
        var original = sourcePost(0, 30_000);
        cache(original);
        save("auto_rewrite", MATERIAL, finalRecord("SKIPPED_COOLDOWN")
                .put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(original)));
        when(oauth.getValidWriteAccessToken(OWNER, false)).thenAnswer(call -> {
            clock.now = Instant.parse("2026-11-27T16:00:00Z");
            return "local-only";
        });
        assertEquals("", service.processAuthorRewriteOnce(source(), OWNER, "owner"));
        assertEquals("FAILED", record().path("status").asText());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        assertFalse(record().path("rewriteSourceNeedsReview").asBoolean());
        verifyNoInteractions(publisher, model);
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void retainedImportKeepsWindowCorrectionAfterExpiryAndAcrossReopen(boolean legacyDivergence) throws Exception {
        var original = sourcePost(1, 30_000)
                .put("publishedAt", clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS + Duration.ofHours(1).toMillis())
                .put("ownerImportedAt", clock.millis());
        store.tx(tx -> { AssistantWorkflow.ingest(tx, original); return null; });
        var corrected = original.deepCopy().put("text", CORRECTION);
        if (legacyDivergence) {
            cache(original);
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("assistant.sqlite"));
                 var update = connection.prepareStatement("UPDATE rewrite_material_cache SET data=? WHERE id=?")) {
                update.setString(1, corrected.toString());
                update.setString(2, MATERIAL);
                assertEquals(1, update.executeUpdate());
            }
            service.shutdown();
            store = new AssistantStore(data, new ObjectMapper());
            service = new AutoRepostService(store, mock(SourceCollector.class), model, oauth, publisher, clock);
        } else cache(corrected);
        assertEquals(CORRECTION, get("material", MATERIAL).path("text").asText());
        assertEquals(original.path("ownerImportedAt"), get("material", MATERIAL).path("ownerImportedAt"));
        clock.advance(Duration.ofHours(2));
        store.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()), clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
        when(model.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(NEW_DRAFT));
        var archived = service.previewRewrite("7", object().put("materialId", MATERIAL));
        assertEquals(CORRECTION, archived.at("/material/text").asText());
        assertFalse(archived.at("/rewrite/candidate").asBoolean(true));
        assertTrue(archived.at("/rewrite/rewrittenText").asText().isBlank());
        verifyNoInteractions(model);
        assertEquals(CORRECTION, service.authorTimeline("7", object()).at("/items/0/material/text").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVALUATING","SENDING","UNCERTAIN","JUDGE_FAILED"})
    void singleCachedPreviewUsesCurrentTimelineState(String status) {
        cache(sourcePost(0, 30_000));
        when(model.evaluateAndWriteRewrite(any(),eq(true))).thenReturn(approved(NEW_DRAFT));
        service.previewRewrite("7", object().put("materialId", MATERIAL));
        save("auto_rewrite", MATERIAL, finalRecord(status));
        var list = service.authorTimeline("7", object()).at("/items/0/rewrite");
        var detail = service.previewRewrite("7", object().put("materialId", MATERIAL)).path("rewrite");
        assertEquals(status, detail.path("status").asText());
        assertFalse(detail.path("candidate").asBoolean());
        assertEquals(list.path("status"), detail.path("status"));
        assertEquals(list.path("candidate"), detail.path("candidate"));
        verify(model, times(1)).evaluateAndWriteRewrite(any(),eq(true));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void latePreviewUsesActualPublishedOrUncertainFinal(boolean uncertain) {
        cache(sourcePost(0, 30_000));
        if (uncertain) when(publisher.createTweet(anyString(),anyString(),eq(List.of()),isNull()))
                .thenThrow(new XPublishClient.XPublishException(504, "simulated timeout", true));
        when(model.evaluateAndWriteRewrite(any(),eq(true))).thenAnswer(call -> {
            assertEquals(MATERIAL, service.processAuthorRewriteOnce(source(), OWNER, "owner"));
            return approved(FINAL);
        });
        var detail = service.previewRewrite("7", object().put("materialId", MATERIAL)).path("rewrite");
        assertEquals(uncertain ? "UNCERTAIN" : "PUBLISHED", detail.path("status").asText());
        assertEquals(NEW_DRAFT, detail.path("rewrittenText").asText());
        assertFalse(detail.path("candidate").asBoolean(true));
        assertEquals(!uncertain, !detail.path("publishedTweetUrl").asText().isBlank());
    }

    @Test void lowerTrafficDisablesReassessmentAndKeepsTheSameOwnersFrozenRetry() throws Exception {
        clock.now = Instant.now();
        var original = sourcePost(0, 30_000);
        cache(original);
        save("auto_rewrite", MATERIAL, finalRecord("FAILED").put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(original)));
        var collector = mock(SourceCollector.class);
        when(collector.styleSamples()).thenReturn(object());
        var controller = new AssistantController(store, collector, mock(AssistantWorker.class), mock(AssistantPublishing.class), new ObjectMapper());
        var request = new MockHttpServletRequest();
        request.setAttribute("assistantOwner", new AuthenticatedXUser(OWNER,"owner","Owner","",false,clock.instant()));
        assertTrue(controller.dashboard(20,0,null,"","","","","score", request).at("/retryableAutoPublications/0/canReassess").asBoolean());
        cache(original.deepCopy().put("views", 1));
        var actions = controller.dashboard(20,0,null,"","","","","score", request).path("retryableAutoPublications");
        assertEquals(1, actions.size());
        assertFalse(actions.get(0).path("canReassess").asBoolean(true));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(MATERIAL, REWRITE,true)).getStatusCode());
        assertEquals("FAILED", record().path("status").asText());
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("local-only", FINAL, List.of(), null);
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void aFormerOwnersActivityDoesNotConsumeTheNewOwnersCooldownOrQuota(boolean limit) throws Exception {
        var historical = finalRecord("PUBLISHED").put("materialId","202").put("topicId","other-topic")
                .put("publishedAt", clock.millis() - 60_000).put("publishedTweetId","910002");
        save("auto_rewrite", "202", historical);
        save("auto_repost", "202", historical);
        save("publication", "auto-repost-202", historical.put("source","AUTO_QUOTE_REPOST"));
        save("scheduled_delivery", "222", historical);
        String next = restoreToNewOwner();
        save("meta", "settings", object().put("enabled",true).put("autoRepostDailyLimit",limit ? 1 : 3)
                .put("autoRepostCooldownMinutes",limit ? 0 : 60));
        save("auto_rewrite", MATERIAL, finalRecord("FAILED").put("ownerAccountId",next));
        when(oauth.getValidWriteAccessToken(next,false)).thenReturn("token-B");
        assertEquals(0L, store.<Long>tx(tx -> service.resolveLatestOwnerTweetEpochMs(tx,next,"restored",MATERIAL,clock.millis())));
        assertEquals(0, store.<Integer>tx(tx -> AutoRepostService.autoPublicationsToday(tx,clock.millis())));
        assertTrue(store.<Boolean>tx(tx -> tx.repostActivityForCooldown(clock.millis() - Duration.ofHours(16).toMillis()).isEmpty()));
        service.retry(MATERIAL, REWRITE);
        waitForRetry();
        verify(publisher).createTweet("token-B",FINAL,List.of(),null);
        assertEquals(1, store.<Integer>tx(tx -> AutoRepostService.autoPublicationsToday(tx,clock.millis())));
        verifyNoInteractions(model);
    }
}
