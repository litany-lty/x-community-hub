package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EditorialDeliveryBoundaryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final XOAuthService auth = mock(XOAuthService.class);
    private final XPublishClient publisher = mock(XPublishClient.class);
    private final TestClock clock = new TestClock();
    private final List<Post> requests = new ArrayList<>();
    private AssistantStore store;
    private AssistantPublishing publishing;
    private ScheduledTweetService queue;
    private String topicA;
    private String topicB;
    private boolean timeout;

    record Post(String body, String original) { }

    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-27T02:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }

    @BeforeEach void open() {
        store = new AssistantStore(directory.resolve("db"), mapper);
        store.tx(tx -> tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT")
                .put("accountId", "42").put("username", "owner")));
        var config = new XOpsProperties();
        config.setDiskCacheDir(directory.resolve("data").toString());
        queue = new ScheduledTweetService(config, mapper, auth, publisher, clock);
        queue.setAssistantStore(store);
        publishing = new AssistantPublishing(store, queue, auth, publisher);
        when(auth.getTokenCapability("42")).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(auth.getValidWriteAccessToken("42", false)).thenReturn("local-token");
        when(publisher.createTweet(eq("local-token"), anyString(), anyList(), nullable(String.class)))
                .thenAnswer(call -> receive(call.getArgument(1), call.getArgument(3)));
        when(publisher.createTweet(eq("local-token"), anyString(), anyList()))
                .thenAnswer(call -> receive(call.getArgument(1), null));
        topicA = seed("101");
        topicB = seed("202");
    }

    @AfterEach void close() { queue.shutdown(); }

    private String receive(String body, String original) {
        requests.add(new Post(body, original));
        if (timeout) {
            timeout = false;
            throw new XPublishClient.XPublishException(504, "模拟请求已收到，客户端等待超时", true);
        }
        return "9900" + requests.size();
    }

    private String seed(String id) {
        return store.tx(tx -> {
            String topic = AssistantWorkflow.ingestDirectLinkMaterial(tx, object().put("id", id).put("text", "原文 " + id)
                    .put("authorId", "77").put("username", "author").put("sourceUrl", "https://x.com/author/status/" + id));
            tx.put("draft", "draft-" + id, object().put("topicId", topic).put("materialId", id).put("text", "评论 " + id));
            return topic;
        });
    }

    private void move(String material, String target) {
        store.tx(tx -> {
            String origin = AssistantTopics.topicOf(tx, material);
            ObjectNode versions = object().put(origin, tx.get("topic", origin).path("version").asInt());
            if (tx.get("topic", target) != null) versions.put(target, tx.get("topic", target).path("version").asInt());
            return AssistantTopics.move(tx, List.of(material), target, versions, "OWNER");
        });
    }

    private ObjectNode direct(String draft, int version, String key, boolean quote) {
        return publishing.publishNow("42", "owner", draft,
                object().put("version", version).put("clientRequestId", key).put("quoteSource", quote));
    }

    private ScheduledTweetJob schedule(int version, String key, String body, boolean quote) {
        long at = clock.millis() + 60_000;
        var source = publishing.prepare("42", "draft-101", version, key, body, at, List.of(), quote, null);
        var job = queue.createScheduledTweet("42", "owner", body, at, key, List.of(), source, source.quoteTweetId(), null);
        publishing.record(job);
        return job;
    }

    private void dispatch() {
        clock.now = clock.now.plusSeconds(61);
        queue.processDueJobsForAccount("42");
    }

    private static void conflict(Runnable action) {
        assertEquals(409, assertThrows(XDataProviderException.class, action::run).getStatusCode());
    }

    @Test void immediateTimeoutBlocksNewScheduleAndAlreadyQueuedSend() {
        schedule(1, "queued", "评论 101", true);
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "immediate", true));
        conflict(() -> schedule(1, "new", "评论 101", true));
        dispatch();
        assertEquals(List.of(new Post("评论 101", "101")), requests);
        assertEquals(ScheduledTweetStatus.FAILED, queue.listJobs("42", "owner").items().get(0).status());
    }

    @Test void scheduledTimeoutBlocksOtherEntrypointsBeforeAnyDashboardRefresh() {
        schedule(1, "queued", "评论 101", true);
        timeout = true;
        dispatch();
        assertTrue(queue.listJobs("42", "owner").items().get(0).uncertainDelivery());
        conflict(() -> direct("draft-101", 1, "now", true));
        conflict(() -> direct("draft-101", 1, "plain", false));
        var manual = new AutoRepostService(store, null, null, auth, publisher, clock);
        try {
            conflict(() -> manual.publishManualQuote("42", "owner", "77", object().put("materialId", "101")
                    .put("commentText", "改了附文再试").put("clientRequestId", "manual")));
        } finally { manual.shutdown(); }
        assertEquals(List.of(new Post("评论 101", "101")), requests);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void anUncertainDraftCannotSwitchBetweenQuoteAndOriginal(boolean quotedFirst) {
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "first", quotedFirst));
        conflict(() -> direct("draft-101", 1, "second", !quotedFirst));
        assertEquals(1, requests.size());
    }

    @Test void verifyingAnImmediatePostAsPublishedAlsoPreventsAnEquivalentScheduledCopy() {
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "timeout", true));
        var receipt = store.tx(tx -> tx.get("publication", "direct-timeout"));
        publishing.verifyPublication("42", "direct-timeout",
                object().put("version", receipt.path("version").asInt()).put("published", true).put("tweetId", "99110"));
        conflict(() -> direct("draft-101", 1, "plain-copy", false));
        conflict(() -> schedule(1, "schedule-copy", "评论 101", true));
        assertEquals(1, requests.size());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cancelledAndDeletedScheduledReceiptsCanBeVerifiedWithoutSending(boolean published) {
        String jobId = schedule(1, "verified-schedule", "评论 101", true).id();
        timeout = true;
        dispatch();
        queue.cancelJob("42", jobId);
        queue.deleteJob("42", jobId);
        publishing.reconcile("42");
        var guard = store.tx(tx -> tx.get("scheduled_delivery", jobId));
        conflict(() -> direct("draft-101", 1, "before-check", false));
        ObjectNode decision = object().put("version", guard.path("version").asInt()).put("published", published);
        if (published) decision.put("tweetId", "99009");
        var result = publishing.verifyScheduledPublication("42", jobId, decision);
        assertEquals(published ? "PUBLISHED" : "FAILED", result.path("status").asText());
        assertEquals(result, publishing.verifyScheduledPublication("42", jobId, decision));
        publishing.reconcile("42");
        conflict(() -> publishing.verifyScheduledPublication("42", jobId,
                decision.deepCopy().put("published", !published).put("tweetId", "11223")));
        assertEquals(1, requests.size());
        if (published) {
            conflict(() -> direct("draft-101", 1, "already-sent", false));
            assertEquals(1, requests.size());
        } else {
            direct("draft-101", 1, "allowed-after-check", true);
            assertEquals(2, requests.size());
        }
    }

    @Test void aVerifiedQueueReceiptRestoresItsGuardIfTheDatabaseWriteWasInterrupted() {
        String jobId = schedule(1, "recover-schedule", "评论 101", true).id();
        timeout = true;
        dispatch();
        var guard = store.tx(tx -> tx.get("scheduled_delivery", jobId));
        queue.registerDelivery("42", jobId, guard.path("attemptCount").asInt(), "90222");
        publishing.reconcile("42");
        ObjectNode result = publishing.verifyScheduledPublication("42", jobId,
                object().put("version", guard.path("version").asInt()).put("published", true).put("tweetId", "90222"));
        assertEquals("90222", result.path("publishedTweetId").asText());
        conflict(() -> direct("draft-101", 1, "after-restart", false));
        assertEquals(1, requests.size());
    }

    @Test void verifiedStandaloneScheduleBlocksTheSameDraftAtTheOtherEntry() {
        String body = "评论 101";
        var job = queue.createScheduledTweet("42", "owner", body, clock.millis() + 60_000, "ordinary", List.of());
        timeout = true;
        dispatch();
        conflict(() -> direct("draft-101", 1, "while-pending", false));
        var row = store.tx(tx -> tx.get("scheduled_delivery", job.id()));
        publishing.verifyScheduledPublication("42", job.id(), object().put("version", row.path("version").asInt())
                .put("published", true).put("tweetId", "70001"));
        conflict(() -> direct("draft-101", 1, "after-confirmation", false));
        assertEquals(List.of(new Post(body, null)), requests);
    }

    @Test void anOrdinaryJobCannotBeAddedOrSentAroundACheckedDirectTimeout() {
        String body = "评论 101";
        long at = clock.millis() + 60_000;
        var existing = queue.createScheduledTweet("42", "owner", body, at, "before-timeout", List.of());
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "timeout", false));
        conflict(() -> queue.createScheduledTweet("42", "owner", body, at + 86_400_000, "new-entry", List.of()));
        var row = store.tx(tx -> tx.get("publication", "direct-timeout"));
        publishing.verifyPublication("42", "direct-timeout", object().put("version", row.path("version").asInt())
                .put("published", true).put("tweetId", "70002"));
        assertEquals(existing.id(), queue.createScheduledTweet("42", "owner", body, at, "before-timeout", List.of()).id());
        conflict(() -> queue.createScheduledTweet("42", "owner", body, at + 86_400_000, "after-confirmation", List.of()));
        dispatch();
        assertEquals(ScheduledTweetStatus.FAILED, queue.listJobs("42", "owner").items().get(0).status());
        assertEquals(List.of(new Post(body, null)), requests);
    }

    @Test void aRecoveredDirectReceiptProtectsTheSameQuoteInTheSourcePopup() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("db/assistant.sqlite"));
             var sql = db.createStatement()) {
            sql.execute("""
                    CREATE TRIGGER delay_success BEFORE INSERT ON revisions
                    WHEN new.kind='publication' AND json_extract(new.data,'$.status')='PUBLISHED'
                    BEGIN SELECT RAISE(FAIL,'delayed receipt'); END""");
            var error = assertThrows(XDataProviderException.class, () -> direct("draft-101", 1, "lost", true));
            assertEquals(503, error.getStatusCode());
            sql.execute("DROP TRIGGER delay_success");
        }
        publishing.reconcile("42");
        assertEquals("99001", store.tx(tx -> tx.get("publication", "direct-lost")).path("publishedTweetId").asText());
        var manual = new AutoRepostService(store, null, null, auth, publisher, clock);
        try {
            conflict(() -> manual.publishManualQuote("42", "owner", "77", object().put("materialId", "101")
                    .put("commentText", "评论 101").put("clientRequestId", "other")));
        } finally { manual.shutdown(); }
        assertEquals(List.of(new Post("评论 101", "101")), requests);
    }

    @Test void settledDeliveryCleanupPreservesTheVerifiedDraftFence() throws Exception {
        var job = schedule(1, "retained-draft", "评论 101", true);
        timeout = true;
        dispatch();
        var row = store.tx(tx -> tx.get("scheduled_delivery", job.id()));
        publishing.verifyScheduledPublication("42", job.id(), object().put("version", row.path("version").asInt())
                .put("published", true).put("tweetId", "77009"));
        long cutoff = AssistantRetention.cutoffMillis(System.currentTimeMillis());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("db/assistant.sqlite"));
             var sql = db.prepareStatement("UPDATE heads SET changed=? WHERE kind='scheduled_delivery' AND id=?")) {
            sql.setLong(1, cutoff - 1);
            sql.setString(2, job.id());
            assertEquals(1, sql.executeUpdate());
        }
        store.purgeExpiredContent(cutoff);
        publishing.reconcile("42");
        conflict(() -> direct("draft-101", 1, "after-cleanup", true));
        assertEquals(List.of(new Post("评论 101", "101")), requests);
    }

    @ParameterizedTest @ValueSource(strings = {"direct", "manual", "schedule"})
    void mergingIntoAnUncertainTopicAtTheHttpPermitPreventsASecondPost(String entry) throws Exception {
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-202", 1, "other", true));
        var posted = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2/tweets", exchange -> {
            try (exchange) {
                posted.incrementAndGet();
                byte[] response = "{\"data\":{\"id\":\"918270\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var props = new XOpsProperties();
        props.setDiskCacheDir(directory.resolve("loopback-queue").toString());
        props.setOfficialProxyUrl("");
        props.setOfficialBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        var remote = new com.xops.hub.provider.SharedApiExecutor(props);
        var gate = mock(com.xops.hub.provider.UpstreamBudgetGuard.class);
        doAnswer(call -> { move("101", topicB); return null; }).when(gate).acquirePermit(any(java.net.URI.class), eq("x_tweet_create"));
        var localClient = new XPublishClient(props, mapper, remote, gate);
        var localQueue = new ScheduledTweetService(props, mapper, auth, localClient, clock);
        localQueue.setAssistantStore(store);
        var localPublishing = new AssistantPublishing(store, localQueue, auth, localClient);
        var manual = new AutoRepostService(store, null, null, auth, localClient, clock);
        try {
            switch (entry) {
                case "direct" -> conflict(() -> localPublishing.publishNow("42", "owner", "draft-101",
                        object().put("version", 1).put("clientRequestId", "permit").put("quoteSource", true)));
                case "manual" -> conflict(() -> manual.publishManualQuote("42", "owner", "77", object()
                        .put("materialId", "101").put("commentText", "评论 101").put("clientRequestId", "permit")));
                case "schedule" -> {
                    long at = clock.millis() + 60_000;
                    var src = localPublishing.prepare("42", "draft-101", 1, "permit", "评论 101", at, List.of(), true, "101");
                    localPublishing.record(localQueue.createScheduledTweet("42", "owner", "评论 101", at, "permit", List.of(), src, "101", null));
                    clock.now = clock.now.plusSeconds(61);
                    localQueue.processDueJobsForAccount("42");
                    assertEquals(ScheduledTweetStatus.FAILED, localQueue.listJobs("42", "owner").items().get(0).status());
                }
            }
            assertEquals(0, posted.get());
            assertEquals("UNCERTAIN", store.tx(tx -> tx.get("publication", "direct-other")).path("status").asText());
        } finally { manual.shutdown(); localQueue.shutdown(); remote.shutdown(); server.stop(0); }
    }

    @Test void twoDraftsForDifferentOriginalsMayUseTheSameCaption() {
        String text = "两条不同原帖都用这一句点评。";
        long at = clock.millis() + 60_000;
        for (String original : List.of("101", "202")) {
            String id = "draft-" + original;
            store.tx(tx -> tx.put("draft", id, tx.get("draft", id).put("text", text)));
            var source = publishing.prepare("42", id, 2, "intent-" + original, text, at, List.of(), true, original);
            publishing.record(queue.createScheduledTweet("42", "owner", text, at, "intent-" + original,
                    List.of(), source, original, null));
            at += 100;
        }
        dispatch();
        assertEquals(List.of(new Post(text, "101"), new Post(text, "202")), requests);
        assertTrue(queue.listJobs("42", "owner").items().stream().allMatch(job -> job.status() == ScheduledTweetStatus.PUBLISHED));
    }

    @Test void aPostIdCanBeConfirmedForOnlyOneDeliveryEvenWhenDecisionsRace() throws Exception {
        var scheduled = schedule(1, "original-schedule", "评论 101", true);
        timeout = true;
        dispatch();
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-202", 1, "other-original", true));
        var queued = store.tx(tx -> tx.get("scheduled_delivery", scheduled.id()));
        var immediate = store.tx(tx -> tx.get("publication", "direct-other-original"));
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var confirmations = List.<java.util.concurrent.Callable<ObjectNode>>of(
                    () -> publishing.verifyScheduledPublication("42", scheduled.id(), object().put("published", true)
                            .put("tweetId", "99123").put("version", queued.path("version").asInt())),
                    () -> publishing.verifyPublication("42", "direct-other-original", object().put("published", true)
                            .put("tweetId", "99123").put("version", immediate.path("version").asInt())));
            var futures = confirmations.stream().map(action -> pool.submit(() -> {
                start.await();
                try {
                    assertEquals("PUBLISHED", action.call().path("status").asText());
                    return true;
                } catch (XDataProviderException error) {
                    assertEquals(409, error.getStatusCode());
                    return false;
                }
            })).toList();
            start.countDown();
            var queueWon = futures.get(0).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(queueWon, futures.get(1).get(5, java.util.concurrent.TimeUnit.SECONDS));
            publishing.reconcile("42");
            assertEquals(queueWon ? "PUBLISHED" : "UNCERTAIN",
                    store.tx(tx -> tx.get("scheduled_delivery", scheduled.id())).path("status").asText());
            assertEquals(queueWon ? "UNCERTAIN" : "PUBLISHED",
                    store.tx(tx -> tx.get("publication", "direct-other-original")).path("status").asText());
            assertEquals(queueWon, "99123".equals(queue.listJobs("42", "owner").items().get(0).publishedTweetId()));
            assertEquals(2, requests.size());
        } finally { pool.shutdownNow(); }
    }

    @Test void interruptedQueueVerificationReservesThePostIdUntilBothStoresAgree() throws Exception {
        String jobId = queue.createScheduledTweet("42", "owner", "独立排期", clock.millis() + 60_000, "ordinary", List.of()).id();
        timeout = true;
        dispatch();
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-202", 1, "second", true));
        var guard = store.tx(tx -> tx.get("scheduled_delivery", jobId));
        var immediate = store.tx(tx -> tx.get("publication", "direct-second"));
        ObjectNode registration = object().put("published", true).put("tweetId", "77700").put("version", guard.path("version").asInt());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("db/assistant.sqlite"));
             var sql = db.createStatement()) {
            sql.execute("""
                    CREATE TRIGGER delay_queue_sync BEFORE INSERT ON revisions
                    WHEN new.kind='scheduled_delivery' AND json_extract(new.data,'$.status')='PUBLISHED'
                    BEGIN SELECT RAISE(FAIL,'pause local sync'); END""");
            assertThrows(IllegalStateException.class, () -> publishing.verifyScheduledPublication("42", jobId, registration));
            conflict(() -> publishing.verifyPublication("42", "direct-second", object().put("published", true)
                    .put("tweetId", "77700").put("version", immediate.path("version").asInt())));
            sql.execute("DROP TRIGGER delay_queue_sync");
        }
        publishing.reconcile("42");
        assertEquals("77700", publishing.verifyScheduledPublication("42", jobId, registration).path("publishedTweetId").asText());
        assertNull(store.tx(tx -> tx.get("scheduled_verification", jobId)));
        assertEquals(2, requests.size());
    }

    @Test void mergeAndSplitMoveThePendingFenceWithTheMaterial() {
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "first", true));
        move("101", topicB);
        conflict(() -> direct("draft-202", 1, "other", true));
        move("101", "split-a");
        direct("draft-202", 1, "other", true);
        assertEquals(List.of(new Post("评论 101", "101"), new Post("评论 202", "202")), requests);
    }

    @ParameterizedTest @ValueSource(strings = {"direct", "new", "update"})
    void afterSplitEveryEntryUsesBothTheBodyAndTheOriginalOfTheAcceptedRevision(String action) {
        move("202", topicA);
        ScheduledTweetJob existing = action.equals("update") ? schedule(1, "existing", "评论 101", true) : null;
        move("202", "split-b");
        store.tx(tx -> tx.put("draft", "draft-101", tx.get("draft", "draft-101")
                .put("materialId", "202").put("text", "新版只谈 B")));
        switch (action) {
            case "direct" -> direct("draft-101", 2, "now", true);
            case "new" -> { schedule(2, "new", "新版只谈 B", true); dispatch(); }
            case "update" -> {
                var updated = publishing.update("42", "owner", "draft-101", object().put("intentId", "existing")
                        .put("operationId", "take-v2").put("version", 2).put("expectedUpdatedAt", existing.updatedAtEpochMs()));
                assertEquals("202", updated.path("requestedQuoteTweetId").asText());
                var original = publishing.prepare("42", "draft-101", 1, "existing", "评论 101",
                        existing.scheduledAtEpochMs(), List.of(), true, "101");
                assertEquals("101", original.quoteTweetId());
                dispatch();
            }
            default -> fail("未知入口");
        }
        assertEquals(List.of(new Post("新版只谈 B", "202")), requests);
    }

    @Test void anExplicitStandaloneJobKeepsItsSupportingPermalinkInTheBody() {
        String body = "独立稿旁证\nhttps://x.com/other/status/8080";
        schedule(1, "standalone", body, false);
        dispatch();
        assertEquals(List.of(new Post(body, null)), requests);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failedLocalWriteAfterAnAmbiguousSendCanBeVerifiedAfterRecovery(boolean restart) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("db/assistant.sqlite"));
             var statement = db.createStatement()) {
            statement.execute("CREATE TRIGGER fail_unknown BEFORE INSERT ON revisions WHEN new.kind='publication' "
                    + "AND json_extract(new.data,'$.status')='UNCERTAIN' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            timeout = true;
            assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "unknown", true));
            statement.execute("DROP TRIGGER fail_unknown");
        }
        if (restart) {
            publishing = new AssistantPublishing(store, queue, auth, publisher);
            publishing.recoverInterruptedPublishes();
        } else publishing.reconcile("42");
        ObjectNode row = store.tx(tx -> tx.get("publication", "direct-unknown"));
        assertEquals("UNCERTAIN", row.path("status").asText());
        publishing.verifyPublication("42", "direct-unknown", object().put("version", row.path("version").asInt())
                .put("published", true).put("tweetId", "99955"));
        assertEquals(1, requests.size());
    }

    @ParameterizedTest @ValueSource(strings = {"MANUAL_QUOTE_REPOST", "DIRECT_QUOTE_PUBLISH"})
    void anUnboundLegacyReceiptCannotBeAssignedAcrossAnOwnerRestore(String source) {
        store.tx(tx -> {
            tx.put("meta", "owner", object().put("accountId", "old").put("username", "former"));
            tx.put("publication", "legacy", object().put("source", source).put("status", "UNCERTAIN"));
            return tx.observeOwner(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "restored", clock.millis());
        });
        ObjectNode legacy = store.tx(tx -> tx.get("publication", "legacy"));
        conflict(() -> publishing.verifyPublication(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "legacy",
                object().put("published", false).put("version", legacy.path("version").asInt())));
        assertEquals(0, requests.size());
    }

    enum FallbackOutcome { PENDING, VERIFIED_AFTER_TIMEOUT, QUEUE_VERIFIED, RECOVERED_RECEIPT }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FallbackOutcome.class)
    void aLinkFallbackCannotBeSentAgainThroughAnOrdinarySchedule(FallbackOutcome outcome) throws Exception {
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var pending = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.HttpExchange>();
        var receiver = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/2/tweets", exchange -> {
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            if (bodies.size() == 2 && outcome == FallbackOutcome.VERIFIED_AFTER_TIMEOUT) {
                pending.set(exchange); // Wait for the production HTTP client's read timeout.
                return;
            }
            int status = bodies.size() == 1 ? 403 : outcome == FallbackOutcome.RECOVERED_RECEIPT ? 201 : 503;
            String json = switch (status) {
                case 403 -> "{\"detail\":\"You can only reply to or quote posts where you are mentioned or are the author.\"}";
                case 201 -> "{\"data\":{\"id\":\"16111\"}}";
                default -> "{\"detail\":\"No acknowledgement\"}";
            };
            try (exchange) {
                byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        receiver.start();
        var config = new XOpsProperties();
        config.setOfficialProxyUrl("");
        config.setOfficialBaseUrl("http://127.0.0.1:" + receiver.getAddress().getPort() + "/2");
        config.setDiskCacheDir(directory.resolve("http").toString());
        var executor = new com.xops.hub.provider.SharedApiExecutor(config);
        try {
            var client = new XPublishClient(config, mapper, executor);
            queue.shutdown();
            queue = new ScheduledTweetService(config, mapper, auth, client, clock);
            queue.setAssistantStore(store);
            publishing = new AssistantPublishing(store, queue, auth, client);
            if (outcome == FallbackOutcome.QUEUE_VERIFIED) {
                var job = schedule(1, "linked-job", "评论 101", true);
                dispatch();
                var claim = store.tx(tx -> tx.get("scheduled_delivery", job.id()));
                assertEquals("UNCERTAIN", claim.path("status").asText());
                publishing.verifyScheduledPublication("42", job.id(), positiveDecision(claim, "16111"));
                var recorded = queue.listJobs("42", "owner").items().get(0).publicationReceipt();
                assertEquals(XPublishClient.PublicationMode.QUOTE_LINK, recorded.mode());
                assertEquals(bodies.get(1).path("text").asText(), recorded.text());
                assertEquals(recorded.text(), store.tx(tx -> tx.get("publication", "linked-job")).path("text").asText());
            } else if (outcome == FallbackOutcome.RECOVERED_RECEIPT) {
                try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("db/assistant.sqlite"));
                     var sql = db.createStatement()) {
                    rejectDirectReceiptWrites(sql);
                    assertEquals(503, assertThrows(XDataProviderException.class, () -> direct("draft-101", 1, "wire", true)).getStatusCode());
                    assertNotNull(store.pendingReceipt("direct-accepted", "direct-wire"));
                    sql.execute("DROP TRIGGER fail_receipt_writes");
                }
                publishing = new AssistantPublishing(store, queue, auth, client);
                publishing.reconcile("42");
                var recovered = store.tx(tx -> tx.get("publication", "direct-wire"));
                assertTrue(recovered.path("receiptRecovered").asBoolean());
                assertEquals("16111", recovered.path("publishedTweetId").asText());
            } else {
                var err = assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "wire", true));
                assertTrue(err.isDeliveryUncertain());
                if (outcome == FallbackOutcome.VERIFIED_AFTER_TIMEOUT) {
                    assertInstanceOf(java.net.http.HttpTimeoutException.class, err.getCause());
                    publishing.verifyPublication("42", "direct-wire",
                            positiveDecision(store.tx(tx -> tx.get("publication", "direct-wire")), "16111"));
                }
            }
            assertEquals(2, bodies.size());
            assertEquals("101", bodies.get(0).path("quote_tweet_id").asText());
            String actual = bodies.get(1).path("text").asText();
            assertTrue(actual.contains("/status/101"));
            conflict(() -> queue.createScheduledTweet("42", "owner", actual, clock.millis() + 60_000, "ordinary-copy", List.of()));
            dispatch();
            assertEquals(2, bodies.size(), "Only the refused native quote and its one link fallback may reach the receiver");
        } finally {
            if (pending.get() != null) pending.get().close();
            receiver.stop(0);
            executor.shutdown();
        }
    }

    private static ObjectNode positiveDecision(ObjectNode row, String tweetId) {
        return object().put("published", true).put("tweetId", tweetId).put("version", row.path("version").asInt());
    }

    private static void rejectDirectReceiptWrites(java.sql.Statement sql) throws java.sql.SQLException {
        sql.execute("""
                CREATE TRIGGER fail_receipt_writes BEFORE INSERT ON revisions
                WHEN new.kind='publication' AND new.id='direct-wire' AND json_extract(new.data,'$.status') IN ('PUBLISHED','UNCERTAIN')
                BEGIN SELECT RAISE(FAIL,'simulated receipt storage outage'); END
                """);
    }

    @ParameterizedTest @ValueSource(booleans = { false, true })
    void aDifferentDraftIdDoesNotAuthorizeTheSameStandalonePost(boolean verified) {
        var copy = store.tx(tx -> tx.get("draft", "draft-101"));
        copy.remove(List.of("id", "version", "updatedAt"));
        store.tx(tx -> tx.put("draft", "another-draft", copy));
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "first", false));
        if (verified) publishing.verifyPublication("42", "direct-first",
                positiveDecision(store.tx(tx -> tx.get("publication", "direct-first")), "65555"));
        long due = clock.millis() + 60_000;
        conflict(() -> {
            var source = publishing.prepare("42", "another-draft", 1, "second", "评论 101", due, List.of(), false, null);
            queue.createScheduledTweet("42", "owner", "评论 101", due, "second", List.of(), source, null, null);
        });
        dispatch();
        assertEquals(List.of(new Post("评论 101", null)), requests);
    }

    @ParameterizedTest @ValueSource(booleans = { false, true })
    void acceptedScheduledIdSurvivesAFailedQueueFileWrite(boolean unavailableUntilRestart) throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var failingMapper = new ObjectMapper() {
            @Override public byte[] writeValueAsBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                byte[] bytes = super.writeValueAsBytes(value);
                if (new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("\"status\":\"PUBLISHED\"")
                        && (attempts.incrementAndGet() == 1 || unavailableUntilRestart))
                    throw new com.fasterxml.jackson.core.JsonProcessingException("simulated queue write failure") {};
                return bytes;
            }
        };
        var config = new XOpsProperties();
        config.setDiskCacheDir(directory.resolve("fail-queue").toString());
        queue.shutdown();
        queue = new ScheduledTweetService(config, failingMapper, auth, publisher, clock);
        queue.setAssistantStore(store);
        var original = queue.createScheduledTweet("42", "owner", "评论 101", clock.millis() + 60_000, "scheduled", List.of());
        Path journal = directory.resolve("fail-queue/users/by-id/42/scheduled-receipts/" + original.id() + ".json");
        if (unavailableUntilRestart) {
            assertThrows(IllegalStateException.class, this::dispatch);
            assertEquals("99001", mapper.readTree(journal.toFile()).at("/receipt/tweetId").asText());
        } else {
            dispatch();
        }
        assertTrue(attempts.get() >= 1);
        queue.shutdown();
        queue = new ScheduledTweetService(config, mapper, auth, publisher, clock);
        queue.setAssistantStore(store);
        publishing = new AssistantPublishing(store, queue, auth, publisher);
        publishing.reconcile("42");
        var saved = queue.listJobs("42", "owner").items().get(0);
        assertEquals(ScheduledTweetStatus.PUBLISHED, saved.status());
        assertEquals("99001", saved.publishedTweetId());
        assertFalse(java.nio.file.Files.exists(journal));
        var receipt = store.tx(tx -> tx.get("scheduled_delivery", original.id()));
        assertEquals("99001", receipt.path("publishedTweetId").asText());
        conflict(() -> publishing.verifyScheduledPublication("42", original.id(),
                object().put("published", false).put("version", receipt.path("version").asInt())));
        conflict(() -> direct("draft-101", 1, "other-entry", false));
        assertEquals(List.of(new Post("评论 101", null)), requests);
    }

    @Test void restoringAnOlderQueueCannotUndoAPositiveDecision() throws Exception {
        var first = queue.createScheduledTweet("42", "owner", "评论 101", clock.millis() + 60_000, "scheduled", List.of());
        timeout = true;
        dispatch();
        Path file = directory.resolve("data/users/by-id/42/scheduled_tweets_v1.json");
        byte[] before = java.nio.file.Files.readAllBytes(file);
        var pending = queue.listJobs("42", "owner").items().get(0);
        var guard = store.tx(tx -> tx.get("scheduled_delivery", first.id()));
        publishing.verifyScheduledPublication("42", first.id(), positiveDecision(guard, "235612"));
        java.nio.file.Files.write(file, before);
        org.springframework.test.util.ReflectionTestUtils.setField(queue, "environment", new org.springframework.mock.env.MockEnvironment());
        conflict(() -> queue.confirmAndResend("42", "owner", first.id(), pending.confirmToken()));
        queue.processDueJobsForAccount("42");
        assertEquals("235612", queue.listJobs("42", "owner").items().get(0).publishedTweetId());
        var restored = store.tx(tx -> tx.get("scheduled_delivery", first.id()));
        assertEquals("PUBLISHED", restored.path("status").asText());
        assertEquals("235612", restored.path("publishedTweetId").asText());
        conflict(() -> direct("draft-101", 1, "other-entry", false));
        assertEquals(1, requests.size());
    }

    @Test void aJournaledPlatformIdIsReservedBeforeEitherDatabaseWriteRecovers() throws Exception {
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-202", 1, "other", true));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("db/assistant.sqlite"));
             var sql = db.createStatement()) {
            rejectDirectReceiptWrites(sql);
            assertEquals(503, assertThrows(XDataProviderException.class, () -> direct("draft-101", 1, "wire", true)).getStatusCode());
            assertEquals("99002", store.pendingReceipt("direct-accepted", "direct-wire").at("/receipt/publishedTweetId").asText());
            var unrelated = store.tx(tx -> tx.get("publication", "direct-other"));
            conflict(() -> publishing.verifyPublication("42", "direct-other", positiveDecision(unrelated, "99002")));
            sql.execute("DROP TRIGGER fail_receipt_writes");
        }
        publishing.reconcile("42");
        assertEquals("99002", store.tx(tx -> tx.get("publication", "direct-wire")).path("publishedTweetId").asText());
        assertEquals("UNCERTAIN", store.tx(tx -> tx.get("publication", "direct-other")).path("status").asText());
        assertEquals(2, requests.size());
    }

    @Test void anOldOwnersPendingPostDoesNotBlockANewOwnersIndependentQuote() {
        timeout = true;
        assertThrows(XPublishClient.XPublishException.class, () -> direct("draft-101", 1, "old", true));
        move("202", topicA);
        store.tx(tx -> tx.put("meta", "owner", object().put("accountId", "43").put("username", "next").put("bindingSource", "EXPLICIT")));
        when(auth.getValidWriteAccessToken("43", false)).thenReturn("local-token");
        var manual = new AutoRepostService(store, null, null, auth, publisher, clock);
        try {
            manual.publishManualQuote("43", "next", "77", object().put("clientRequestId", "independent")
                    .put("materialId", "202").put("commentText", "另一个原帖的新评论"));
            var old = store.tx(tx -> tx.get("publication", "direct-old"));
            conflict(() -> publishing.verifyPublication("43", "direct-old",
                    object().put("published", false).put("version", old.path("version").asInt())));
            assertEquals(List.of(new Post("评论 101", "101"), new Post("另一个原帖的新评论", "202")), requests);
        } finally {
            manual.shutdown();
        }
    }
}
