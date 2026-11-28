package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RewriteHandoffHttpTest extends LocalPublicationHttpFixture {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    static final String MATERIAL = "10551";
    static final String PREVIEW = BASE + "/sources/7/rewrite-preview";
    static final String RETRY = BASE + "/auto-reposts/" + MATERIAL + "/retry?mode=REWRITE";
    static final String ORIGINAL = "The committee published its independent estimate of a ten percent budget increase.";
    static final String CORRECTED = "The committee withdrew its estimate after auditors confirmed a decline in the final budget.";
    static final String FINAL = "The published budget now points to ten percent growth.";
    static final String NEW_FINAL = "The latest audit changes the outlook for next year's budget.";
    final PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
    AutoRepostService service;

    @BeforeEach void assembleRewrite() {
        clock.now = LocalDate.now(BEIJING).atTime(9, 0).atZone(BEIJING).toInstant();
        due = clock.millis() + 60_000;
        store.tx(tx -> tx.put("source", "7", object().put("authorId", "7").put("username", "source")
                .put("status", "ACTIVE").put("autoRewriteEnabled", true)));
        service = new AutoRepostService(store, collector, judge, oauth, client, clock);
        ReflectionTestUtils.setField(drafts, "autoRepostService", service);
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(FINAL));
    }

    @AfterEach void stopRewrite() { service.shutdown(); }

    private PiAgentViralJudge.RewriteJudgment approved(String text) {
        return new PiAgentViralJudge.RewriteJudgment(true, 91, "", "", "approved", text);
    }

    private ObjectNode original(long ageMillis) {
        return object().put("id", MATERIAL).put("authorId", "7").put("username", "source").put("kind", "POST")
                .put("text", ORIGINAL).put("publishedAt", clock.millis() - ageMillis).put("views", 30_000);
    }

    private void cache(ObjectNode material) {
        store.tx(tx -> { tx.cacheRewriteMaterial(material, "budget"); return null; });
    }

    private ObjectNode retained(ObjectNode material, String status) {
        return store.tx(tx -> tx.put("auto_rewrite", MATERIAL, object().put("materialId", MATERIAL).put("authorId", "7")
                .put("ownerAccountId", ACCOUNT).put("topicId", "budget").put("status", status).put("rewrittenText", FINAL)
                .put("suitabilityScore", 91).put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(material))));
    }

    private ObjectNode record() { return store.tx(tx -> tx.get("auto_rewrite", MATERIAL)); }

    private void waitForWork() throws Exception {
        ((ExecutorService) ReflectionTestUtils.getField(service, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private ObjectNode request(String route, ObjectNode body, int expected) throws Exception {
        var req = body == null ? get(route) : post(route).contentType("application/json").header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "http://localhost").content(json.writeValueAsBytes(body));
        var response = mvc.perform(req.session(session)).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(expected, response.getStatus(), content);
        return (ObjectNode) json.readTree(content);
    }

    private ObjectNode schedule(String body, String key, JsonNode reference) {
        var req = object().put("text", body).put("clientRequestId", key).put("scheduledAtEpochMs", due);
        if (reference != null) req.set("rewriteSource", reference);
        return req;
    }

    private ObjectNode reference(JsonNode rewrite, boolean includePreviewId) {
        var ref = object().put("ownerAccountId", ACCOUNT).put("materialId", MATERIAL)
                .put("recordVersion", rewrite.path("savedDraftVersion").asInt());
        if (includePreviewId) ref.put("previewId", rewrite.path("previewId").textValue());
        return ref;
    }

    private void dispatch(String account) {
        clock.now = Instant.ofEpochMilli(due + 1000);
        scheduler.processDueJobsForAccount(account);
    }

    private void advanceToBeijingMidnight() {
        clock.now = clock.instant().atZone(BEIJING).toLocalDate().plusDays(1).atStartOfDay(BEIJING).toInstant();
    }

    private AuthenticatedXUser restoreOwner() {
        store.tx(tx -> tx.put("meta", "owner", object().put("accountId", ACCOUNT).put("username", "former")));
        var nextOwner = new AuthenticatedXUser(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "restored", "Restored", "", false, clock.instant());
        store.observeOAuthOwner(nextOwner);
        when(oauth.getTokenCapability(nextOwner.id())).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(oauth.getValidWriteAccessToken(nextOwner.id(), false)).thenReturn("local-new-owner");
        return nextOwner;
    }

    @Test void legacySourceCardCannotTransferEitherOfTheFormerOwnersFinals() throws Exception {
        var original = original(60_000);
        cache(original);
        retained(original, "SKIPPED_LIMIT");
        var oldCard = request(BASE + "/sources/7/timeline", object().put("materialId", MATERIAL), 200);
        assertEquals(FINAL, oldCard.at("/items/0/rewrite/rewrittenText").asText());
        when(judge.evaluateAndWriteRewrite(any())).thenReturn(approved(NEW_FINAL));
        when(oauth.getValidWriteAccessToken(ACCOUNT, false)).thenThrow(new IllegalStateException("test token expired"));
        request(RETRY + "&reassess=true", object(), 202);
        waitForWork();
        assertEquals(NEW_FINAL, record().path("rewrittenText").asText());

        var next = restoreOwner();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, next);
        request(QUEUE, schedule(NEW_FINAL, "latest", null), 409);
        var cardPath = runtime.resolve("old-card.json");
        json.writeValue(cardPath.toFile(), oldCard);
        var browser = new ProcessBuilder("node", "src/test/js/fixtures/legacy-rewrite-schedule.cjs",
                cardPath.toString(), Long.toString(due), next.id()).redirectErrorStream(true).start();
        assertTrue(browser.waitFor(10, TimeUnit.SECONDS));
        String serialized = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, browser.exitValue(), serialized);
        var payload = (ObjectNode) json.readTree(serialized);
        assertEquals(3, payload.size());
        assertEquals(FINAL, payload.path("text").asText());
        request(QUEUE, payload, 409);
        assertTrue(request(QUEUE, null, 200).path("items").isEmpty());
        request(QUEUE, schedule("Independent text written by the restored account.", "independent", null), 200);
        dispatch(next.id());
        assertEquals(1, captured.size());
        assertEquals("Independent text written by the restored account.", captured.get(0).path("text").asText());
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,false,false", "true,true,false", "false,true,false",
                "true,true,true", "false,true,true"})
    void correctedPreviewCannotEnterOrLeaveTheQueue(boolean correctBeforeCreate, boolean purge, boolean edited) throws Exception {
        var original = original(60_000);
        if (purge) due += Duration.ofDays(31).toMillis();
        cache(original);
        var preview = request(PREVIEW, object().put("materialId", MATERIAL), 200).path("rewrite");
        assertEquals(FINAL, preview.path("rewrittenText").asText());
        assertEquals(0, preview.path("savedDraftVersion").asInt(-1));
        var ref = reference(preview, edited);
        var req = schedule(edited ? FINAL + "!" : FINAL, "preview", ref);
        if (!correctBeforeCreate) request(QUEUE, req, 200);
        cache(original.deepCopy().put("text", CORRECTED));
        if (purge) {
            clock.now = clock.instant().plus(Duration.ofDays(31));
            store.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()), clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
            assertNull(store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis())));
            scheduler.setAssistantStore(new AssistantStore(runtime.resolve("assistant"), json));
        }
        if (correctBeforeCreate) request(QUEUE, req, 409);
        dispatch(ACCOUNT);
        if (!correctBeforeCreate) assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertTrue(captured.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void confirmedCurrentPreviewsAllowAnEditedSchedule(boolean previouslyCorrected) throws Exception {
        var original = original(60_000);
        cache(original);
        if (previouslyCorrected) {
            request(PREVIEW, object().put("materialId", MATERIAL), 200);
            cache(original.deepCopy().put("text", CORRECTED));
            when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(NEW_FINAL));
        }
        var preview = request(PREVIEW, object().put("materialId", MATERIAL), 200).path("rewrite");
        String edited = preview.path("rewrittenText").asText() + "!";
        // A pre-upgrade card has no previewId. Current cards also identify edits after a known correction.
        request(QUEUE, schedule(edited, "current-preview", reference(preview, previouslyCorrected)), 200);
        dispatch(ACCOUNT);
        assertEquals("PUBLISHED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertEquals(edited, captured.get(0).path("text").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bodyOnlyQueueRechecksKnownCorrections(boolean correctBeforeCreate) throws Exception {
        var original = original(60_000);
        cache(original);
        retained(original, "SKIPPED_LIMIT");
        if (!correctBeforeCreate) request(QUEUE, schedule(FINAL, "legacy", null), 200);
        cache(original.deepCopy().put("text", CORRECTED));
        assertTrue(record().path("rewriteSourceNeedsReview").asBoolean());
        if (correctBeforeCreate) request(QUEUE, schedule(FINAL, "legacy", null), 409);
        dispatch(ACCOUNT);
        if (!correctBeforeCreate) assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertTrue(captured.isEmpty());
    }

    @Test void formerOwnersBodyOnlyQueueBlocksItsRewriteAndDeliversItsOrdinaryPost() throws Exception {
        var original = original(60_000);
        cache(original);
        retained(original, "SKIPPED_LIMIT");
        request(QUEUE, schedule(FINAL, "old-queue", null), 200);
        restoreOwner();
        dispatch(ACCOUNT);
        assertTrue(captured.isEmpty());
        assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
        due = clock.millis() + 60_000;
        request(QUEUE, schedule("Independent post from this regular account.", "regular", null), 200);
        dispatch(ACCOUNT);
        assertEquals(1, captured.size());
        assertEquals("Independent post from this regular account.", captured.get(0).path("text").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void punctuationEditCannotRepeatAnUncertainRewrite(boolean alreadyQueued) throws Exception {
        var original = original(60_000);
        cache(original);
        var saved = retained(original, "SKIPPED_LIMIT");
        var ref = object().put("materialId", MATERIAL).put("ownerAccountId", ACCOUNT).put("recordVersion", saved.path("version").asInt());
        var edited = schedule(FINAL + "!", "edited", ref);
        if (alreadyQueued) request(QUEUE, edited, 200);
        failNextPost = true;
        request(RETRY, object(), 202);
        waitForWork();
        assertEquals("UNCERTAIN", record().path("status").asText());
        if (!alreadyQueued) request(QUEUE, edited, 409);
        dispatch(ACCOUNT);
        assertEquals(1, captured.size());
        assertEquals(FINAL, captured.get(0).path("text").asText());
        if (alreadyQueued) assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"hasMedia", "selfThreadReply"})
    void explicitSameDayRefetchInvalidatesTheRetainedFinal(String change) throws Exception {
        String url = "https://x.com/source/status/" + MATERIAL;
        var material = original(60_000).put("sourceUrl", url).put("hasMedia", false).put("selfThreadReply", false);
        when(collector.fetchTweetMaterialById(MATERIAL)).thenReturn(material, material.deepCopy().put(change, true));
        request(BASE + "/research-link", object().put("url", url).put("clientRequestId", "first"), 202);
        retained(material, "FAILED");
        request(BASE + "/research-link", object().put("url", url).put("clientRequestId", "refresh").put("regenerate", true), 202);
        verify(collector, times(2)).fetchTweetMaterialById(MATERIAL);
        assertTrue(store.<Boolean>tx(tx -> tx.get("material", MATERIAL).path(change).asBoolean()));
        assertTrue(record().path("rewriteSourceNeedsReview").asBoolean());
        request(RETRY, object(), 409);
        assertTrue(captured.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void freshOwnerTimelineGuardsSavedRetriesAndLongRunningPatrols(boolean savedRetry) throws Exception {
        var material = original(savedRetry ? Duration.ofDays(1).toMillis() : 60_000);
        cache(material);
        var latest = new AtomicLong(savedRetry ? clock.millis() - 60_000 : 0);
        when(collector.fetchLatestAccountPostEpochMs("elidlicom", ACCOUNT)).thenAnswer(call -> latest.get());
        if (savedRetry) {
            retained(material, "FAILED");
            request(RETRY, object(), 202);
            waitForWork();
            verifyNoInteractions(judge);
        } else {
            when(judge.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
                clock.now = clock.instant().plus(Duration.ofMinutes(8));
                latest.set(clock.millis() - 60_000);
                return approved(FINAL);
            });
            service.runOwnerIdleRepostPatrol();
        }
        verify(collector, times(savedRetry ? 1 : 2)).fetchLatestAccountPostEpochMs("elidlicom", ACCOUNT);
        assertEquals("SKIPPED_COOLDOWN", record().path("status").asText());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        assertTrue(captured.isEmpty());
    }
    @Test void correctionFromAnOlderCollectorIsRecoveredBeforeTheOnlyPreviewSourceExpires() throws Exception {
        var material = original(60_000);
        due += Duration.ofDays(31).toMillis();
        cache(material);
        var preview = request(PREVIEW, object().put("materialId", MATERIAL), 200).path("rewrite");
        request(QUEUE, schedule(FINAL, "restored-preview", reference(preview, false)), 200);
        var database = runtime.resolve("assistant").resolve("assistant.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var update = connection.prepareStatement("UPDATE rewrite_material_cache SET data=? WHERE id=?")) {
            update.setString(1, material.deepCopy().put("text", CORRECTED).toString());
            update.setString(2, MATERIAL);
            assertEquals(1, update.executeUpdate());
        }
        store = new AssistantStore(database.getParent(), json);
        clock.now = clock.instant().plus(Duration.ofDays(31));
        store.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()), clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
        assertNull(store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis())));
        scheduler.setAssistantStore(store);
        dispatch(ACCOUNT);
        assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertTrue(captured.isEmpty());
    }

    @Test void upgradingRetainsCorrectionsBeforeRegularHistoryIsPurgedAndOnlyTheLatestWindowCopySurvives() throws Exception {
        var original = original(Duration.ofDays(9).toMillis());
        store.tx(tx -> tx.put("material", MATERIAL, original));
        cache(original);
        var unversioned = object().put("ownerAccountId", ACCOUNT).put("materialId", MATERIAL).put("recordVersion", 0);
        request(QUEUE, schedule("Edited text from an older browser.", "legacy-edited", unversioned), 200);
        store.tx(tx -> tx.put("material", MATERIAL, original.deepCopy().put("text", CORRECTED)));

        // Earlier releases stored both material revisions, but had no persistent preview fingerprint.
        var database = runtime.resolve("assistant").resolve("assistant.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var revisions = connection.prepareStatement("DELETE FROM revisions WHERE kind='rewrite_source' AND id=?");
             var head = connection.prepareStatement("DELETE FROM heads WHERE kind='rewrite_source' AND id=?")) {
            revisions.setString(1, MATERIAL);
            head.setString(1, MATERIAL);
            assertTrue(revisions.executeUpdate() > 0);
            assertEquals(1, head.executeUpdate());
        }
        store = new AssistantStore(database.getParent(), json);
        store.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()), clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
        assertNull(store.tx(tx -> tx.get("material", MATERIAL)));
        assertEquals(CORRECTED, store.tx(tx -> tx.rewriteMaterial(MATERIAL, clock.millis())).path("text").asText());
        scheduler.setAssistantStore(store);
        dispatch(ACCOUNT);
        assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertTrue(captured.isEmpty());
    }

    @Test void unavailableOwnerTimelinePreservesTheFinalForALaterVerifiedRetry() throws Exception {
        var material = original(60_000);
        cache(material);
        retained(material, "FAILED");
        when(collector.fetchLatestAccountPostEpochMs("elidlicom", ACCOUNT)).thenReturn(-1L, 0L);
        request(RETRY, object(), 202);
        waitForWork();
        assertEquals("SKIPPED_COOLDOWN", record().path("status").asText());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        assertTrue(captured.isEmpty());
        request(RETRY, object(), 202);
        waitForWork();
        assertEquals("PUBLISHED", record().path("status").asText());
        assertEquals(FINAL, captured.get(0).path("text").asText());
    }

    private void restoreForeignFinalHistory(ObjectNode material) {
        ObjectNode first = retained(material, "FAILED");
        store.tx(tx -> tx.put("auto_rewrite", MATERIAL, first.put("rewrittenText", "A different, subsequently edited final.")));
    }

    private ObjectNode secondSourceCard(String account, boolean preview, String finalText) throws Exception {
        String secondId = "10662";
        ObjectNode second = original(60_000).put("id", secondId)
                .put("text", "Independent observers studied changes to greenhouse irrigation in two remote counties.");
        cache(second);
        var ref = object().put("ownerAccountId", account).put("materialId", secondId);
        if (preview) {
            when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved(finalText));
            var card = request(PREVIEW, object().put("materialId", secondId), 200).path("rewrite");
            assertEquals(finalText, card.path("rewrittenText").asText());
            return ref.put("recordVersion", 0).put("previewId", card.path("previewId").asText());
        }
        ObjectNode saved = store.tx(tx -> tx.put("auto_rewrite", secondId, object().put("materialId", secondId).put("authorId", "7")
                .put("ownerAccountId", account).put("rewrittenText", finalText).put("status", "SKIPPED_LIMIT")
                .put("rewriteSourceFingerprint", AutoRepostService.rewriteSourceFingerprint(second))));
        return ref.put("recordVersion", saved.path("version").asInt());
    }

    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,true"})
    void validSourceCardCannotAuthorizePastedForeignOrWithdrawnText(boolean foreign, boolean knownAtCreate, boolean preview) throws Exception {
        var material = original(60_000);
        cache(material);
        String account = ACCOUNT;
        if (foreign) {
            if (knownAtCreate) restoreForeignFinalHistory(material);
            var next = restoreOwner();
            account = next.id();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER, next);
        } else {
            retained(material, "FAILED");
            if (knownAtCreate) cache(material.deepCopy().put("text", CORRECTED));
        }
        ObjectNode ref = secondSourceCard(account, preview, NEW_FINAL);
        request(QUEUE, schedule(FINAL, "pasted", ref), knownAtCreate ? 409 : 200);
        if (!knownAtCreate) {
            if (foreign) restoreForeignFinalHistory(material);
            else cache(material.deepCopy().put("text", CORRECTED));
        }
        dispatch(account);
        if (!knownAtCreate) assertEquals("FAILED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertTrue(captured.isEmpty());
    }

    @Test void identicalTextWithAnIndependentApprovalByTheNewOwnerCanBeScheduled() throws Exception {
        var material = original(60_000);
        cache(material);
        restoreForeignFinalHistory(material);
        var next = restoreOwner();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, next);
        var ref = secondSourceCard(next.id(), true, FINAL);
        request(QUEUE, schedule(FINAL, "independently-approved", ref), 200);
        dispatch(next.id());
        assertEquals("PUBLISHED", request(QUEUE, null, 200).at("/items/0/status").asText());
        assertEquals(FINAL, captured.get(0).path("text").asText());
        verify(oauth).getValidWriteAccessToken(next.id(), false);
    }

    @ParameterizedTest @ValueSource(strings = {"traffic", "expiry", "automatic-quote", "manual-quote"})
    void latePreviewReportsTheCurrentSourceAndCandidateStatus(String change) throws Exception {
        var material = original(60_000);
        if (change.equals("expiry"))
            store.tx(tx -> { AssistantWorkflow.ingest(tx, material.deepCopy().put("ownerImportedAt", clock.millis())); return null; });
        cache(material);
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenAnswer(call -> {
            switch (change) {
                case "traffic" -> cache(material.deepCopy().put("views", 1));
                case "expiry" -> advanceToBeijingMidnight();
                case "automatic-quote", "manual-quote" -> {
                    var receipt = object().put("ownerAccountId", ACCOUNT).put("materialId", MATERIAL).put("authorId", "7")
                            .put("status", "PUBLISHED").put("publishedTweetId", "109998").put("publishedAt", clock.millis());
                    if (change.equals("manual-quote"))
                        store.tx(tx -> tx.put("publication", "manual-source", receipt.put("source", "MANUAL_QUOTE")));
                    else store.tx(tx -> tx.put("auto_repost", MATERIAL, receipt));
                }
            }
            return approved(FINAL);
        });
        var req = object().put("materialId", MATERIAL);
        var result = request(PREVIEW, req, 200);
        var item = request(BASE + "/sources/7/timeline", req, 200).at("/items/0");
        assertFalse(item.at("/rewrite/candidate").asBoolean(true));
        assertFalse(result.at("/rewrite/candidate").asBoolean(true));
        assertEquals(item.at("/rewrite/ineligibleReason"), result.at("/rewrite/ineligibleReason"));
        assertEquals(item.at("/material/views"), result.at("/material/views"));
        if (change.equals("expiry")) {
            assertFalse(result.at("/rewrite/withinWindow").asBoolean(true));
            assertTrue(result.at("/rewrite/rewrittenText").asText().isBlank());
            assertTrue(result.at("/rewrite/previewId").asText().isBlank());
            verify(judge).evaluateAndWriteRewrite(any(), eq(true));
        }
        if (change.equals("traffic")) assertFalse(result.at("/rewrite/highTraffic").asBoolean(true));
        assertTrue(captured.isEmpty());
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void losingEligibilityDuringReassessmentKeepsThePriorApprovedRetry(boolean expires, boolean positiveVerdict) throws Exception {
        var material = original(60_000);
        cache(material);
        retained(material, "FAILED");
        when(judge.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
            if (expires) advanceToBeijingMidnight();
            else cache(material.deepCopy().put("views", 1));
            return positiveVerdict ? approved(NEW_FINAL) : new PiAgentViralJudge.RewriteJudgment(false, 15, "", "", "rejected", "");
        });
        request(RETRY + "&reassess=true", object(), 202);
        waitForWork();
        assertEquals("FAILED", record().path("status").asText());
        assertEquals(FINAL, record().path("rewrittenText").asText());
        assertFalse(record().path("rewriteSourceNeedsReview").asBoolean());
        assertTrue(captured.isEmpty());
        request(RETRY, object(), 202);
        waitForWork();
        assertEquals("PUBLISHED", record().path("status").asText());
        assertEquals(FINAL, captured.get(0).path("text").asText());
        assertEquals(1, captured.size());
        verify(judge, times(1)).evaluateAndWriteRewrite(any());
    }
}
