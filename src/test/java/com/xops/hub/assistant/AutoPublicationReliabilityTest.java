package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.PaidCapabilityGate;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.xops.hub.assistant.AssistantStore.object;
import static com.xops.hub.assistant.AutoRepostService.AutoPublishMode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AutoPublicationReliabilityTest {
    private static final String TARGET = "101";
    @TempDir Path data;
    private final MutableClock clock = new MutableClock();
    private final XOAuthService auth = mock(XOAuthService.class);
    private final XPublishClient publisher = mock(XPublishClient.class);
    private final PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
    private final PaidCapabilityGate gate = new PaidCapabilityGate(clock);
    private ReceiptFaultStore store;
    private AutoRepostService service;
    private ExecutorService worker;

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-27T01:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        void advanceMinutes(int minutes) { now = now.plus(Duration.ofMinutes(minutes)); }
    }

    private static class ReceiptFaultStore extends AssistantStore {
        volatile int failures;
        ReceiptFaultStore(Path data) { super(data, new ObjectMapper()); }

        @Override public synchronized <T> T tx(Function<Tx, T> action) {
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("Simulated receipt write failure");
            }
            return super.tx(action);
        }
    }

    @BeforeEach
    void setUp() {
        store = new ReceiptFaultStore(data);
        put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "elidlicom"));
        put("source", "7", object().put("authorId", "7").put("username", "source").put("status", "ACTIVE")
                .put("autoRepostEnabled", true));
        when(auth.getValidWriteAccessToken("42", false)).thenReturn("local-token");
        var collector = mock(SourceCollector.class);
        when(collector.fetchTweetMaterialById(TARGET)).thenReturn(object().put("id", TARGET).put("authorId", "7").put("username", "source"));
        service = new AutoRepostService(store, collector, judge, auth, publisher, clock);
        ReflectionTestUtils.setField(service, "paidGate", gate);
        worker = (ExecutorService) ReflectionTestUtils.getField(service, "executor");
    }

    @AfterEach void stop() { service.shutdown(); }

    private ObjectNode sourcePost(String id, String authorId, String username, long publishedAt) {
        return object().put("id", id).put("kind", "POST").put("authorId", authorId).put("username", username)
                .put("sourceUrl", "https://x.com/" + username + "/status/" + id)
                .put("text", "The source reports a new product release today.").put("publishedAt", publishedAt).put("views", 2000);
    }

    private PiAgentViralJudge.ViralJudgment qualifyingQuote(String category, String comment) {
        return new PiAgentViralJudge.ViralJudgment(true, 96, true, 96, 96, 96, 96, category, "Release", "Reason", comment);
    }

    /** Runs the production publisher against a loopback receiver, including its real fallback requests. */
    private final class QuoteEndpoint implements AutoCloseable {
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final SharedApiExecutor pool;
        final AutoRepostService quotes;

        QuoteEndpoint(int... responses) throws Exception {
            var mapper = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/2/tweets", exchange -> {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                int status = requests.size() <= responses.length ? responses[requests.size() - 1] : 201;
                ObjectNode body = object();
                if (status == 201) body.putObject("data").put("id", "91777");
                else body.put("detail", status == 403
                        ? "You can only reply to or quote posts where you are mentioned or are the author."
                        : "No conclusive publication receipt");
                byte[] bytes = mapper.writeValueAsBytes(body);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
            var props = new XOpsProperties();
            props.setOfficialBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
            props.setDiskCacheDir(data.resolve("quote-endpoint").toString());
            pool = new SharedApiExecutor(props);
            quotes = new AutoRepostService(store, mock(SourceCollector.class), judge, auth,
                    new XPublishClient(props, mapper, pool), clock);
        }

        @Override public void close() {
            quotes.shutdown();
            pool.shutdown();
            server.stop(0);
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"regular", "archive", "before-upgrade"})
    void observedQuoteCorrectionSurvivesExpiryAndVerification(String updatePath) throws Exception {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 60_000);
        store.tx(tx -> { AssistantWorkflow.ingest(tx, original); return null; });
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("ROUTINE", "Rollout is live."));
        try (var endpoint = new QuoteEndpoint(503)) {
            endpoint.quotes.processAuthorBatch(get("source", "7"), List.of(original));
            assertEquals("UNCERTAIN", get("auto_repost", TARGET).path("status").asText());
            var corrected = original.deepCopy().put("text", "Correction: the rollout was cancelled.");
            store.tx(tx -> {
                if ("archive".equals(updatePath)) tx.cacheRewriteMaterial(corrected, "");
                else AssistantWorkflow.ingest(tx, corrected);
                return null;
            });
            assertEquals(corrected.path("text"), get("material", TARGET).path("text"));
            if ("before-upgrade".equals(updatePath)) {
                // An older database retained the corrected material but had no correction marker.
                var legacy = get("auto_repost", TARGET);
                legacy.remove("quoteSourceNeedsReview");
                put("auto_repost", TARGET, legacy);
                var reopened = new AssistantStore(data, new ObjectMapper());
                assertTrue(reopened.tx(tx -> tx.get("auto_repost", TARGET)).path("quoteSourceNeedsReview").asBoolean());
            }
            clock.advanceMinutes(31 * 24 * 60);
            store.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()),
                    clock.millis() - AutoRepostService.REWRITE_MAX_AGE_MS);
            assertNull(store.tx(tx -> tx.rewriteMaterial(TARGET, clock.millis())));
            var pending = get("auto_repost", TARGET);
            endpoint.quotes.verifyAutomaticPublication("42", TARGET, AutoPublishMode.REPOST,
                    object().put("published", false).put("version", pending.path("version").asInt()));
            var unavailable = assertThrows(XDataProviderException.class, () -> endpoint.quotes.retry(TARGET, AutoPublishMode.REPOST));
            assertEquals(409, unavailable.getStatusCode());
            verify(judge).evaluate(any());
            assertEquals(1, endpoint.requests.size());
            assertEquals("Rollout is live.", endpoint.requests.get(0).path("text").asText());
        }
    }

    @Test void conflictingRegularAndArchivedAuthorsBlockAReservedAutomaticRetry() throws Exception {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 1_000);
        put("material", TARGET, original);
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("ROUTINE", "Seven reported this."));
        when(auth.getValidWriteAccessToken("42", false)).thenAnswer(call -> {
            var other = sourcePost(TARGET, "8", "other", original.path("publishedAt").asLong());
            store.tx(tx -> {
                tx.put("material", TARGET, other);
                tx.cacheRewriteMaterial(other, "");
                tx.put("material", TARGET, original);
                return null;
            });
            throw new RuntimeException("Token unavailable");
        }).thenReturn("local-token");
        try (var endpoint = new QuoteEndpoint()) {
            endpoint.quotes.processAuthorBatch(get("source", "7"), List.of(original));
            assertEquals("AUTH_REQUIRED", get("auto_repost", TARGET).path("status").asText());
            assertEquals("7", get("material", TARGET).path("authorId").asText());
            assertEquals(409, assertThrows(XDataProviderException.class,
                    () -> endpoint.quotes.retry(TARGET, AutoPublishMode.REPOST)).getStatusCode());
            assertTrue(endpoint.requests.isEmpty());
        }
    }

    @Test void manualQuoteRechecksOriginalAuthorAfterAcquiringToken() throws Exception {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 1_000);
        store.tx(tx -> { AssistantWorkflow.ingest(tx, original); return null; });
        when(auth.getValidWriteAccessToken("42", false)).thenAnswer(call -> {
            assertEquals("SENDING", get("publication", "manual-quote-reserved").path("status").asText());
            var other = sourcePost(TARGET, "8", "other", original.path("publishedAt").asLong());
            store.tx(tx -> { AssistantWorkflow.ingest(tx, other); return null; });
            return "local-token";
        });
        try (var endpoint = new QuoteEndpoint()) {
            var request = object().put("materialId", TARGET).put("commentText", "According to the source.")
                    .put("sourceUrl", original.path("sourceUrl").asText()).put("clientRequestId", "reserved");
            assertEquals(409, assertThrows(XDataProviderException.class,
                    () -> endpoint.quotes.publishManualQuote("42", "elidlicom", "7", request)).getStatusCode());
            assertEquals("FAILED", get("publication", "manual-quote-reserved").path("status").asText());
            assertTrue(endpoint.requests.isEmpty());
        }
    }

    @Test void onlyAnExplicitlyEmptyOwnerTimelineAllowsRetry() throws Exception {
        var mapper = new ObjectMapper();
        var owner = object().put("id", "42").put("userName", "elidlicom");
        var terminal = object().put("has_next_page", false);
        var response = new java.util.concurrent.atomic.AtomicReference<>(terminal);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ObjectNode body = exchange.getRequestURI().getPath().endsWith("/info")
                    ? object().set("data", owner) : response.get();
            var bytes = mapper.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var props = new XOpsProperties();
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTwitterapiIoKey("fixture");
        props.setDiskCacheDir(data.resolve("timeline-provider").toString());
        var pool = new SharedApiExecutor(props);
        var collector = new SourceCollector(store, new TwitterApiIoProvider(props, mapper, pool), null, mapper, Clock.systemUTC());
        var quotes = new AutoRepostService(store, collector, judge, auth, publisher, clock);
        try {
            failed(AutoPublishMode.REPOST);
            for (ObjectNode incomplete : List.of(terminal, terminal.deepCopy().putNull("tweets"),
                    terminal.deepCopy().put("tweets", "unknown"), terminal.deepCopy().put("status", "error"))) {
                response.set(incomplete);
                assertEquals(503, assertThrows(XDataProviderException.class, () -> quotes.retry(TARGET, AutoPublishMode.REPOST)).getStatusCode());
                assertEquals("FAILED", get("auto_repost", TARGET).path("status").asText());
            }
            verifyNoInteractions(publisher);
            var empty = terminal.deepCopy();
            empty.putArray("tweets");
            response.set(empty);
            assertEquals(0L, collector.fetchLatestAccountPostEpochMs("elidlicom", "42"));
            when(publisher.createTweet(anyString(), anyString(), anyList(), eq(TARGET))).thenReturn("90111");
            quotes.retry(TARGET, AutoPublishMode.REPOST);
            ((ExecutorService) ReflectionTestUtils.getField(quotes, "executor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals("PUBLISHED", get("auto_repost", TARGET).path("status").asText());
        } finally { quotes.shutdown(); collector.stop(); pool.shutdown(); server.stop(0); }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void verifiedAutomaticQuoteReflectsItsLastHttpRequest(int refusals) throws Exception {
        int[] responses = new int[refusals + 1];
        java.util.Arrays.fill(responses, 403);
        responses[refusals] = 503;
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 1_000);
        put("material", TARGET, original);
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("ROUTINE", "Rollout confirmed."));
        try (var endpoint = new QuoteEndpoint(responses)) {
            endpoint.quotes.processAuthorBatch(get("source", "7"), List.of(original));
            var pending = get("auto_repost", TARGET);
            assertEquals("UNCERTAIN", pending.path("status").asText());
            JsonNode wire = endpoint.requests.get(refusals);
            var confirmed = endpoint.quotes.verifyAutomaticPublication("42", TARGET, AutoPublishMode.REPOST,
                    object().put("version", pending.path("version").asInt()).put("published", true).put("tweetId", "90666"));
            var receipt = get("publication", "auto-repost-" + TARGET);
            String mode = List.of("QUOTE", "QUOTE_LINK", "STANDALONE_FALLBACK").get(refusals);
            for (var record : List.of(confirmed, receipt)) {
                assertEquals(mode, record.path("publicationMode").asText());
                assertEquals(wire.path("text"), record.path("text"));
                assertEquals(wire.path("quote_tweet_id"), record.path("quoteTweetId"));
                assertEquals(TARGET, record.path("requestedQuoteTweetId").asText());
            }
            assertEquals("Rollout confirmed.", receipt.path("requestedText").asText());
            var timeline = endpoint.quotes.authorTimeline("7", object().put("materialId", TARGET));
            var decision = timeline.path("items").get(0).path("decision");
            assertEquals(wire.path("text"), decision.path("publishedText"));
            assertEquals(mode, decision.path("publicationMode").asText());
            assertEquals(refusals + 1, endpoint.requests.size());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aNegativeJudgmentCannotDiscardACorrectionMadeDuringEvaluation(boolean batch) {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 60_000);
        put("material", TARGET, original);
        var corrected = original.deepCopy().put("text", "Correction: the major model has shipped worldwide.");
        PiAgentViralJudge.ViralJudgment negative = new PiAgentViralJudge.ViralJudgment(
                false, 1, false, 1, 1, 1, 1, "ROUTINE", "Old story", "No launch", "");
        Runnable correct = () -> store.tx(tx -> { AssistantWorkflow.ingest(tx, corrected); return null; });
        List<ObjectNode> observations;
        if (batch) {
            var second = sourcePost("202", "7", "source", clock.millis() - 90_000);
            put("material", "202", second);
            observations = List.of(original, second);
            when(judge.evaluateAccountBatch(any())).thenAnswer(call -> {
                correct.run();
                return new PiAgentViralJudge.AccountBatchJudgment("", java.util.Map.of(TARGET, negative, "202", negative));
            });
        } else {
            observations = List.of(original);
            when(judge.evaluate(any())).thenAnswer(call -> { correct.run(); return negative; });
        }
        service.processAuthorBatch(get("source", "7"), observations);
        assertEquals("JUDGE_FAILED", get("auto_repost", TARGET).path("status").asText());
        if (batch) assertEquals("SKIPPED", get("auto_repost", "202").path("status").asText());
        verifyNoInteractions(publisher);
        doAnswer(call -> {
            var input = (PiAgentViralJudge.EvaluationInput) call.getArgument(0);
            assertEquals(corrected.path("text").asText(), input.postText());
            return qualifyingQuote("MODEL_RELEASE", "It shipped.");
        }).when(judge).evaluate(any());
        when(publisher.createTweet(anyString(), anyString(), anyList(), eq(TARGET))).thenReturn("90901");
        service.processAuthorBatch(get("source", "7"), List.of(corrected));
        assertEquals("PUBLISHED", get("auto_repost", TARGET).path("status").asText());
        verify(publisher).createTweet("local-token", "It shipped.", List.of(), TARGET);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void reopenedQuoteUsesTheLaterGenerationWhenBatchAndSingleOverlap(boolean batchStartsFirst) throws Exception {
        put("material", TARGET, sourcePost(TARGET, "7", "source", clock.millis() - 1_000));
        var started = new CountDownLatch(1);
        var finishOlder = new CountDownLatch(1);
        when(judge.evaluateAccountBatch(any())).thenAnswer(call -> {
            if (batchStartsFirst) {
                started.countDown();
                assertTrue(finishOlder.await(5, TimeUnit.SECONDS));
            }
            return new PiAgentViralJudge.AccountBatchJudgment(TARGET,
                    java.util.Map.of(TARGET, qualifyingQuote("ROUTINE", "Account batch comment.")));
        });
        when(judge.evaluate(any())).thenAnswer(call -> {
            if (!batchStartsFirst) {
                started.countDown();
                assertTrue(finishOlder.await(5, TimeUnit.SECONDS));
            }
            return qualifyingQuote("ROUTINE", "Single comment.");
        });
        var req = object().put("materialId", TARGET).put("forceRegenerate", true);
        var older = java.util.concurrent.CompletableFuture.supplyAsync(() -> batchStartsFirst
                ? service.evaluateAuthorOnce("7", req) : service.previewRepost("7", req));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            if (batchStartsFirst) service.previewRepost("7", req); else service.evaluateAuthorOnce("7", req);
            finishOlder.countDown();
            var completed = older.get(5, TimeUnit.SECONDS);
            if (batchStartsFirst) assertTrue(completed.path("regeneratedCommentIds").isEmpty());
            String expected = batchStartsFirst ? "Single comment." : "Account batch comment.";
            var reopened = object().put("materialId", TARGET);
            var timeline = service.authorTimeline("7", reopened).path("items").get(0).path("decision");
            assertEquals(expected, timeline.path("commentText").asText());
            assertEquals(expected, service.previewRepost("7", reopened).path("preview").path("commentText").asText());
            verify(judge).evaluate(any());
            verify(judge).evaluateAccountBatch(any());
        } finally { finishOlder.countDown(); }
    }

    @Test void ineligibleRowsCannotHideAnOlderEligibleQuoteFromPatrol() {
        var negative = new PiAgentViralJudge.ViralJudgment(false, 1, false, 1, 1, 1, 1, "ROUTINE", "", "Routine", "");
        when(judge.evaluate(any())).thenReturn(negative);
        for (int i = 0; i < 12; i++) {
            var id = Integer.toString(3000 + i);
            var row = sourcePost(id, "7", "source", clock.millis() - 60_000L * (i + 1))
                    .put("text", "Source reported small event number " + i);
            put("material", id, row);
            service.processAuthorBatch(get("source", "7"), List.of(row));
            assertEquals("SKIPPED", get("auto_repost", id).path("status").asText());
        }
        clock.advanceMinutes(3 * 24 * 60);
        var original = sourcePost(TARGET, "7", "source", clock.millis() - Duration.ofDays(9).toMillis());
        store.tx(tx -> { tx.cacheRewriteMaterial(original, ""); return null; });
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("ROUTINE", "Eligible original."));
        when(publisher.createTweet(anyString(), anyString(), anyList(), eq(TARGET))).thenReturn("90999");
        var result = service.runOwnerIdleRepostPatrol();
        assertEquals(TARGET, result.path("materialId").asText());
        assertEquals("PUBLISHED", result.path("status").asText());
        verify(judge, times(13)).evaluate(any());
        verify(publisher).createTweet("local-token", "Eligible original.", List.of(), TARGET);
    }

    @Test void rebindingTheOwnerClearsThePublishedCommentAndLinkFromTheNextAccountsPreview() {
        put("material", TARGET, sourcePost(TARGET, "7", "source", clock.millis() - 1_000));
        when(publisher.createTweet(anyString(), anyString(), anyList(), eq(TARGET))).thenReturn("904242");
        service.publishManualQuote("42", "elidlicom", "7",
                object().put("materialId", TARGET).put("commentText", "Account A.").put("clientRequestId", "owner-a"));
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("ROUTINE", "Owner A preview."),
                qualifyingQuote("ROUTINE", "Owner B preview."));
        var selected = object().put("materialId", TARGET);
        var old = service.previewRepost("7", selected.deepCopy().put("forceRegenerate", true)).path("preview");
        assertEquals("PUBLISHED", old.path("persistedStatus").asText());
        assertTrue(old.path("publishedTweetUrl").asText().endsWith("/904242"));
        put("meta", "owner", object().put("accountId", "42").put("username", "former"));
        String next = com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID;
        store.observeOAuthOwner(new AuthenticatedXUser(next, "restored", "Restored", "", false, clock.instant()));
        put("meta", "settings", object().put("enabled", true));
        put("source", "7", get("source", "7").put("status", "ACTIVE").put("autoRepostEnabled", true));
        var newOwner = service.authorTimeline("7", selected);
        assertEquals(next, newOwner.path("owner").path("accountId").asText());
        var card = newOwner.path("items").get(0).path("decision");
        assertNotEquals("PUBLISHED", card.path("persistedStatus").asText());
        assertTrue(card.path("publishedTweetUrl").asText().isBlank());
        assertTrue(card.path("commentText").asText().isBlank());
        assertEquals("Owner B preview.", service.previewRepost("7", selected).path("preview").path("commentText").asText());
        when(auth.getValidWriteAccessToken(next, false)).thenReturn("next-owner-token");
        when(publisher.createTweet("next-owner-token", "Account A.", List.of(), TARGET)).thenReturn("908888");
        var ownReceipt = service.publishManualQuote(next, "restored", "7", selected.deepCopy()
                .put("commentText", "Account A.").put("clientRequestId", "owner-b"));
        assertEquals("908888", ownReceipt.path("publishedTweetId").asText());
        assertTrue(service.previewRepost("7", selected.deepCopy().put("receiptOnly", true))
                .at("/preview/publishedTweetUrl").asText().endsWith("/908888"));
    }

    private TwitterApiIoProvider.TextPost listenerPost(String text, Instant published) {
        return new TwitterApiIoProvider.TextPost("7777", "777", "followtest", published,
                com.xops.hub.model.RawSnapshotModels.TweetKind.POST, text, "", "", "", "", "", "", "", "",
                text, "FULL", "NOT_PRESENT", "NOT_PRESENT", "TEXT_ONLY", false,
                "https://x.com/followtest/status/7777", "7777", false, false, "ROW_ID_VERIFIED", 100L, 10L, 1L, 1L, 0L);
    }

    @Test void aCorrectionToAnAlreadyRejectedPostReentersTheNormalListenerHook() {
        var provider = mock(TwitterApiIoProvider.class);
        var author = new XUserSummary("777", "followtest", "Author", "", "", true, 100, 10, 20, "");
        when(provider.resolvePublicAuthor("followtest")).thenReturn(author);
        put("meta", "settings", object().put("enabled", true).put("collectionEnabled", true));
        var collector = new SourceCollector(store, provider, mock(com.xops.hub.service.CommunityHubService.class), new ObjectMapper(), clock);
        try {
            collector.add("@followtest");
            put("source", "777", get("source", "777").put("autoRepostEnabled", true));
            collector.setBatchIngestHook(service::processAuthorBatch);
            clock.advanceMinutes(10);
            var first = listenerPost("The source has no confirmed release date.", clock.instant().minusSeconds(20));
            var corrected = listenerPost("The source confirmed the launch today.", first.publishedAt());
            when(provider.fetchTextPostsPage(eq("followtest"), eq("777"), eq(""), eq(100), any()))
                    .thenReturn(new TwitterApiIoProvider.TextPostPage(author, List.of(first), "", true))
                    .thenReturn(new TwitterApiIoProvider.TextPostPage(author, List.of(corrected), "", true));
            when(judge.evaluate(any())).thenReturn(new PiAgentViralJudge.ViralJudgment(
                    false, 5, false, 5, 5, 5, 5, "ROUTINE", "", "Unconfirmed", ""))
                    .thenAnswer(call -> {
                        var input = (PiAgentViralJudge.EvaluationInput) call.getArgument(0);
                        assertEquals(corrected.text(), input.postText());
                        return qualifyingQuote("MODEL_RELEASE", "Confirmed today.");
                    });
            when(publisher.createTweet(anyString(), anyString(), anyList(), eq("7777"))).thenReturn("90902");
            collector.tick();
            assertEquals("SKIPPED", get("auto_repost", "7777").path("status").asText());
            clock.advanceMinutes(10);
            collector.tick();
            assertEquals("PUBLISHED", get("auto_repost", "7777").path("status").asText());
            verify(judge, times(2)).evaluate(any());
            verify(publisher).createTweet("local-token", "Confirmed today.", List.of(), "7777");
        } finally { collector.stop(); }
    }

    @Test void listenerTickUpdatesTheProfileUsedByForcedQuoteEvaluation() {
        var mapper = new ObjectMapper();
        var provider = mock(TwitterApiIoProvider.class);
        var community = mock(com.xops.hub.service.CommunityHubService.class);
        var old = new XUserSummary("777", "followtest", "Author", "", "", true, 125, 10, 20, "");
        var newer = new XUserSummary("777", "followtest", "Author", "", "", true, 50_000, 10, 20, "");
        when(provider.resolvePublicAuthor("followtest")).thenReturn(old);
        put("meta", "settings", object().put("enabled", true).put("collectionEnabled", true));
        var collector = new SourceCollector(store, provider, community, mapper, clock);
        try {
            collector.add("@followtest");
            assertEquals(125, get("source", "777").path("followersCount").asInt());
            clock.advanceMinutes(10);
            String text = "A detailed release by a monitored source.";
            var post = listenerPost(text, clock.instant().minusSeconds(20));
            when(provider.fetchTextPostsPage(eq("followtest"), eq("777"), eq(""), eq(100), any()))
                    .thenReturn(new TwitterApiIoProvider.TextPostPage(newer, List.of(post), "", true));
            collector.tick();
            assertEquals(50_000, get("source", "777").path("followersCount").asInt());
            assertEquals(50_000, get("material", "7777").path("authorFollowersCount").asInt());
            when(judge.evaluate(any())).thenAnswer(call -> {
                var input = (PiAgentViralJudge.EvaluationInput) call.getArgument(0);
                assertEquals(50_000, input.attentionEvidence().path("authorFollowersCount").asInt());
                return qualifyingQuote("ROUTINE", "Updated author evidence.");
            });
            var preview = service.previewRepost("777", object().put("materialId", "7777").put("forceRegenerate", true));
            assertEquals("Updated author evidence.", preview.path("preview").path("commentText").asText());
        } finally { collector.stop(); }
    }

    @Test void aCorrectionDuringEvaluationRequiresAFreshComment() throws Exception {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 60_000L);
        put("material", TARGET, original);
        when(judge.evaluate(any())).thenAnswer(call -> {
            put("material", TARGET, original.deepCopy().put("text", "Correction: the planned rollout was cancelled."));
            return qualifyingQuote("ROUTINE", "The rollout is live.");
        }).thenReturn(qualifyingQuote("ROUTINE", "The rollout was cancelled."));
        when(publisher.createTweet(anyString(), anyString(), anyList(), eq(TARGET))).thenReturn("90101");
        service.processAuthorBatch(get("source", "7"), List.of(original));
        assertEquals("JUDGE_FAILED", get("auto_repost", TARGET).path("status").asText());
        assertTrue(get("auto_repost", TARGET).path("commentText").asText().isEmpty());
        verifyNoInteractions(publisher);
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        verify(publisher).createTweet(anyString(), contains("was cancelled"), anyList(), eq(TARGET));
        assertEquals("PUBLISHED", get("auto_repost", TARGET).path("status").asText());
    }

    @Test void archiveOnlyAuthorConflictBlocksAutomaticRecordRetry() {
        failed(AutoPublishMode.REPOST);
        var archived = sourcePost(TARGET, "8", "new_author", clock.millis() - Duration.ofDays(8).toMillis());
        store.tx(tx -> { tx.cacheRewriteMaterial(archived, ""); return null; });
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(TARGET, AutoPublishMode.REPOST)).getStatusCode());
        assertEquals("FAILED", get("auto_repost", TARGET).path("status").asText());
        verifyNoInteractions(judge, publisher);
    }

    @Test void uncertainManualQuoteSurvivesAReceiptWriteFailureWithoutRestartOrResending() {
        put("material", TARGET, sourcePost(TARGET, "7", "source", clock.millis() - 1000));
        var req = object().put("materialId", TARGET).put("commentText", "Manual").put("clientRequestId", "lost");
        String key = "manual-quote-lost";
        when(publisher.createTweet("local-token", "Manual", List.of(), TARGET)).thenAnswer(call -> {
            AssistantPublishing.markRequestDispatched(store, key, "42", clock.millis());
            store.failures = 1;
            throw new XPublishClient.XPublishException(504, "response lost", true);
        });
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42", "elidlicom", "7", req));
        assertFalse(store.pendingReceipts("direct-outcome").isEmpty());
        var publishing = new AssistantPublishing(store, null);
        publishing.reconcile("42");
        var awaiting = get("publication", key);
        assertEquals("UNCERTAIN", awaiting.path("status").asText());
        assertTrue(store.pendingReceipts("direct-outcome").isEmpty());
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.publishManualQuote("42", "elidlicom", "7", req)).getStatusCode());
        var verified = publishing.verifyPublication("42", key, object().put("version", awaiting.path("version").asInt())
                .put("published", true).put("tweetId", "91024"));
        assertEquals("91024", verified.path("publishedTweetId").asText());
        assertEquals("91024", service.publishManualQuote("42", "elidlicom", "7", req).path("publishedTweetId").asText());
        verify(publisher).createTweet("local-token", "Manual", List.of(), TARGET);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void manuallyConfirmedFallbackRetainsTheActualAttemptedPayload(int refusals) throws Exception {
        var mapper = new ObjectMapper();
        var posts = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2/tweets", exchange -> {
            posts.add(mapper.readTree(exchange.getRequestBody()));
            if (posts.size() <= refusals) {
                var bytes = mapper.writeValueAsBytes(object().put("detail", "You can only reply to or quote posts where you are mentioned or are the author."));
                exchange.sendResponseHeaders(403, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } else exchange.close();
        });
        server.start();
        var props = new XOpsProperties();
        props.setOfficialBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        props.setDiskCacheDir(data.resolve("publish-budget").toString());
        var pool = new SharedApiExecutor(props);
        var manual = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, new XPublishClient(props, mapper, pool), clock);
        try {
            put("material", TARGET, sourcePost(TARGET, "7", "source", clock.millis() - 1000));
            String comment = refusals == 1 ? "Manual" : "Manual https://x.com/source/status/101?s=20&t=token-";
            var request = object().put("materialId", TARGET).put("clientRequestId", "fallback").put("commentText", comment);
            assertTrue(assertThrows(XPublishClient.XPublishException.class, () -> manual.publishManualQuote("42", "elidlicom", "7", request)).isDeliveryUncertain());
            assertEquals(refusals + 1, posts.size());
            JsonNode payload = posts.get(refusals);
            assertFalse(payload.has("quote_tweet_id"));
            if (refusals == 1) assertEquals("Manual\n\nhttps://x.com/source/status/101", payload.path("text").asText());
            else assertEquals("Manual\n\n（消息源：source）", payload.path("text").asText());
            String key = "manual-quote-fallback";
            var pending = get("publication", key);
            var confirmed = new AssistantPublishing(store, null).verifyPublication("42", key, object()
                    .put("version", pending.path("version").asInt()).put("published", true).put("tweetId", "90106"));
            assertEquals(refusals == 1 ? "QUOTE_LINK" : "STANDALONE_FALLBACK", confirmed.path("publicationMode").asText());
            assertEquals(payload.path("text").asText(), confirmed.path("text").asText());
            assertEquals(comment, confirmed.path("requestedText").asText());
            assertEquals(TARGET, confirmed.path("requestedQuoteTweetId").asText());
            assertFalse(confirmed.has("quoteTweetId"));
            assertFalse(confirmed.has("quotedTweetId"));
            var item = manual.authorTimeline("7", object().put("materialId", TARGET)).path("items").get(0).path("decision");
            assertEquals("https://x.com/i/status/90106", item.path("publishedTweetUrl").asText());
            assertEquals(payload.path("text").asText(), item.path("publishedText").asText());
        } finally { manual.shutdown(); pool.shutdown(); server.stop(0); }
    }

    @Test void patrolRetryRetainsItsSixteenHourSourceLockEvenForARelease() {
        String id = "9811";
        put("material", id, sourcePost(id, "7", "source", clock.millis() - 60_000L));
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("MODEL_RELEASE", "Important model release."));
        when(publisher.createTweet(anyString(), anyString(), anyList(), eq(id)))
                .thenThrow(new XPublishClient.XPublishException(429, "definite remote rejection", false)).thenReturn("98101");
        service.runOwnerIdleRepostPatrol();
        assertEquals("FAILED", get("auto_repost", id).path("status").asText());
        put("material", "202", sourcePost("202", "7", "source", clock.millis() - 1000));
        when(publisher.createTweet("local-token", "Other comment", List.of(), "202")).thenReturn("90202");
        service.publishManualQuote("42", "elidlicom", "7", object().put("clientRequestId", "another").put("materialId", "202").put("commentText", "Other comment"));
        clock.advanceMinutes(90);
        assertEquals("NO_ELIGIBLE_CANDIDATE", service.runOwnerIdleRepostPatrol().path("status").asText());
        gate.runDueRetries();
        assertEquals("SKIPPED_AUTHOR_COOLDOWN", get("auto_repost", id).path("status").asText());
        verify(publisher).createTweet(anyString(), anyString(), anyList(), eq(id));
        var items = service.authorTimeline("7", object().put("materialId", id)).path("items");
        var row = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(item -> id.equals(item.path("material").path("id").asText())).findFirst().orElseThrow();
        var single = service.previewRepost("7", object().put("materialId", id)).path("preview");
        for (var decision : List.of(row.path("decision"), single)) {
            assertEquals("SKIP_AUTHOR_COOLDOWN", decision.path("decisionCode").asText());
            assertFalse(decision.path("wouldAutoRepost").asBoolean());
            assertFalse(decision.path("authorCooldownExempt").asBoolean());
        }
        clock.advanceMinutes(24 * 60);
        gate.runDueRetries();
        assertEquals("PUBLISHED", get("auto_repost", id).path("status").asText());
        verify(publisher, times(2)).createTweet(anyString(), anyString(), anyList(), eq(id));
    }

    @Test void importedUnmonitoredAuthorsResolveByStableIdOrSavedHandle() {
        String id = "90871";
        put("material", id, sourcePost(id, "710", "outsider", clock.millis() - 1000));
        var request = object().put("materialId", id);
        for (String key : List.of("710", "@outsider")) {
            var row = service.authorTimeline(key, request);
            assertEquals("710", row.path("source").path("id").asText());
            assertEquals(id, row.path("items").get(0).path("material").path("id").asText());
        }
        assertEquals(404, assertThrows(XDataProviderException.class, () -> service.authorTimeline("711", request)).getStatusCode());
    }

    @Test void theRecentRepostFallbackCanClaimAnArchiveOnlyOriginal() {
        String id = "9812";
        var archived = sourcePost(id, "7", "source", clock.millis() - Duration.ofDays(9).toMillis());
        store.tx(tx -> { tx.cacheRewriteMaterial(archived, "archive-topic"); return null; });
        assertNull(get("material", id));
        assertTrue(service.authorTimeline("7", object().put("materialId", id)).path("items").findValuesAsText("id").contains(id));
        when(judge.evaluate(any())).thenReturn(qualifyingQuote("ROUTINE", "Comment."));
        when(publisher.createTweet(anyString(), anyString(), anyList(), eq(id))).thenReturn("90103");
        var result = service.runOwnerIdleRepostPatrol();
        assertEquals("RECENT_FALLBACK", result.path("repostSourceTier").asText());
        assertEquals("PUBLISHED", result.path("status").asText());
        assertEquals("archive-topic", get("auto_repost", id).path("topicId").asText());
        verify(publisher).createTweet(anyString(), anyString(), anyList(), eq(id));
    }

    @Test void profileOnlyFollowerChangesInvalidateCachedQuoteEvidence() {
        put("material", TARGET, sourcePost(TARGET, "7", "source", clock.millis() - 1000).put("authorFollowersCount", 125));
        put("source", "7", get("source", "7").put("followersCount", 125));
        when(judge.evaluate(any())).thenAnswer(call -> {
            var input = (PiAgentViralJudge.EvaluationInput) call.getArgument(0);
            String followers = input.attentionEvidence().path("authorFollowersCount").asText();
            return new PiAgentViralJudge.ViralJudgment(true, 96, 95, 95, 96, "GENERAL", followers, "Reason", followers);
        });
        var req = object().put("materialId", TARGET).put("forceWriteComment", true);
        assertEquals("125", service.previewRepost("7", req).path("preview").path("summary").asText());
        for (int followers : List.of(50_000, 75)) {
            put("source", "7", get("source", "7").put("followersCount", followers));
            assertEquals(Integer.toString(followers), service.previewRepost("7", req).path("preview").path("summary").asText());
            assertEquals(Integer.toString(followers), service.previewRepost("7", req).path("preview").path("summary").asText());
        }
        verify(judge, times(3)).evaluate(any());
    }

    @Test void anArchiveCorrectionFeedsBothTimelineAndSingleQuoteEvaluation() {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - Duration.ofDays(9).toMillis());
        put("material", TARGET, original);
        String correction = "The source retracted the launch and confirmed the shutdown.";
        store.tx(tx -> { tx.cacheRewriteMaterial(original.deepCopy().put("text", correction), ""); return null; });
        when(judge.evaluate(any())).thenAnswer(call -> {
            var input = (PiAgentViralJudge.EvaluationInput) call.getArgument(0);
            assertEquals(correction, input.postText());
            return qualifyingQuote("GENERAL", "The original launch was retracted.");
        });
        var req = object().put("materialId", TARGET).put("forceRegenerate", true);
        assertEquals(correction, service.authorTimeline("7", req).path("items").get(0).path("material").path("text").asText());
        var preview = service.previewRepost("7", req);
        assertEquals(correction, preview.path("material").path("text").asText());
        assertTrue(preview.path("preview").path("commentText").asText().contains("retracted"));
    }

    @Test void explicitQuoteRetryChecksForExternalPostsBeforeClaiming() {
        failed(AutoPublishMode.REPOST);
        var source = (SourceCollector) ReflectionTestUtils.getField(service, "sources");
        when(source.fetchLatestAccountPostEpochMs("elidlicom", "42")).thenReturn(clock.millis() - 60_000L);
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(TARGET, AutoPublishMode.REPOST)).getStatusCode());
        assertEquals("FAILED", get("auto_repost", TARGET).path("status").asText());
        verify(source).fetchLatestAccountPostEpochMs("elidlicom", "42");
        verifyNoInteractions(judge, publisher);
    }

    @Test void longQuoteEvaluationRefreshesTheOwnerTimelineBeforePublishing() {
        var source = (SourceCollector) ReflectionTestUtils.getField(service, "sources");
        var external = new java.util.concurrent.atomic.AtomicLong();
        when(source.fetchLatestAccountPostEpochMs("elidlicom", "42")).thenAnswer(call -> external.get());
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 1000L);
        put("material", TARGET, original);
        when(judge.evaluate(any())).thenAnswer(call -> {
            clock.advanceMinutes(8);
            external.set(clock.millis() - 60_000L);
            return qualifyingQuote("GENERAL", "Comment.");
        });
        service.processAuthorBatch(get("source", "7"), List.of(original));
        assertEquals("SKIPPED_COOLDOWN", get("auto_repost", TARGET).path("status").asText());
        verify(source, times(2)).fetchLatestAccountPostEpochMs("elidlicom", "42");
        verifyNoInteractions(publisher);
    }

    @Test void aPatrolCannotClaimForAnOwnerBoundDuringSourceRetrieval() {
        var original = sourcePost(TARGET, "7", "source", clock.millis() - 1000L);
        put("material", TARGET, original);
        var source = (SourceCollector) ReflectionTestUtils.getField(service, "sources");
        when(source.syncSourceMaterialsForPatrol(any(), anyLong())).thenAnswer(call -> {
            put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "43").put("username", "newowner"));
            return List.of(original);
        });
        assertEquals("NO_ELIGIBLE_CANDIDATE", service.runOwnerIdleRepostPatrol().path("status").asText());
        assertNull(get("auto_repost", TARGET));
        verifyNoInteractions(judge, auth, publisher);
    }

    @Test void ownerTimelineRejectsUndatedMainPostsAndFollowsBothPinnedAliases() throws Exception {
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var first = new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
        var pageTwoReads = new java.util.concurrent.atomic.AtomicInteger();
        var owner = object().put("id", "42").put("userName", "elidlicom");
        var old = object().put("id", "10011").put("text", "Old post").put("createdAt", Instant.now().minus(Duration.ofDays(4)).toString());
        old.set("author", owner);
        var recent = old.deepCopy().put("id", "10022").put("createdAt", Instant.now().minusSeconds(90).toString());
        server.createContext("/", exchange -> {
            ObjectNode response = object();
            if (exchange.getRequestURI().getPath().endsWith("/info")) response.set("data", owner);
            else if (exchange.getRequestURI().getRawQuery().contains("cursor=older")) {
                pageTwoReads.incrementAndGet();
                response.putArray("tweets").add(recent);
                response.put("has_next_page", false);
            } else response = first.get();
            var bytes = mapper.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var props = new XOpsProperties();
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTwitterapiIoKey("fixture");
        props.setDiskCacheDir(data.resolve("timeline-budget").toString());
        var pool = new SharedApiExecutor(props);
        var source = new SourceCollector(store, new TwitterApiIoProvider(props, mapper, pool), null, mapper, Clock.systemUTC());
        try {
            var undated = recent.deepCopy();
            undated.remove("createdAt");
            var page = object().put("has_next_page", false);
            page.putArray("tweets").add(undated);
            first.set(page);
            assertEquals(502, assertThrows(XDataProviderException.class, () -> source.fetchLatestAccountPostEpochMs("elidlicom", "42")).getStatusCode());
            page.withArray("tweets").add(old);
            assertThrows(XDataProviderException.class, () -> source.fetchLatestAccountPostEpochMs("elidlicom", "42"));
            for (String field : List.of("isPin", "isPinned")) {
                var pinned = object().put("has_next_page", true).put("next_cursor", "older");
                pinned.putArray("tweets").add(old.deepCopy().put(field, true));
                first.set(pinned);
                assertEquals(Instant.parse(recent.path("createdAt").asText()).toEpochMilli(), source.fetchLatestAccountPostEpochMs("elidlicom", "42"));
            }
            assertEquals(2, pageTwoReads.get());
        } finally { source.stop(); pool.shutdown(); server.stop(0); }
    }

    @Test void manualQuoteVerifiesTheAuthorAndPostAndKeepsArchivedTopicAndAuthor() {
        long at = clock.millis() - 60_000;
        for (String id : List.of(TARGET, "202")) {
            put("material", id, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                    .put("text", "Original reporting for " + id).put("publishedAt", at));
            put("membership", id, object().put("topicId", "event"));
        }
        var topic = object();
        topic.putArray("materialIds").add(TARGET).add("202");
        put("topic", "event", topic);
        var request = object().put("materialId", TARGET).put("commentText", "First").put("clientRequestId", "first");
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.publishManualQuote("42", "elidlicom",
                "7", request.put("sourceUrl", "https://x.com/source/status/202"))).getStatusCode());
        request.remove("sourceUrl");
        assertEquals(409, assertThrows(XDataProviderException.class,
                () -> service.publishManualQuote("42", "elidlicom", "another", request)).getStatusCode());
        verifyNoInteractions(publisher);
        when(publisher.createTweet("local-token", "First", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(502, "response lost", true));
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42", "elidlicom", "7", request));
        clock.advanceMinutes(3 * 24 * 60);
        store.tx(tx -> tx.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()),
                clock.millis() - Duration.ofDays(30).toMillis()));
        assertNull(get("material", "202"));
        var next = object().put("materialId", "202").put("commentText", "Second").put("clientRequestId", "second");
        assertEquals(409, assertThrows(XDataProviderException.class,
                () -> service.publishManualQuote("42", "elidlicom", "7", next)).getStatusCode());
        var first = get("publication", "manual-quote-first");
        new AssistantPublishing(store, null).verifyPublication("42", "manual-quote-first",
                object().put("version", first.path("version").asInt()).put("published", false));
        when(publisher.createTweet("local-token", "Second", List.of(), "202")).thenReturn("9007");
        var receipt = service.publishManualQuote("42", "elidlicom", "7", next);
        assertEquals("event", receipt.path("topicId").asText());
        assertEquals("42", receipt.path("ownerAccountId").asText());
        assertEquals("7", get("auto_repost", "202").path("authorId").asText());
        assertEquals("source", get("auto_repost", "202").path("username").asText());
        assertNotNull(AutoRepostService.findRecentSameAuthorRepost(store.tx(tx -> tx.repostActivityForCooldown(at)),
                "7", "new-handle", "303", clock.millis()));
    }

    @Test void ordinaryIngestChecksTheOwnerTimelineBeforeEvaluating() {
        var collector = (SourceCollector) ReflectionTestUtils.getField(service, "sources");
        when(collector.fetchLatestAccountPostEpochMs("elidlicom", "42")).thenReturn(clock.millis() - 60_000);
        put("material", TARGET, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                .put("text", "Source reporting with enough detail for the candidate.").put("publishedAt", clock.millis() - 120_000));
        service.processAuthorBatch(get("source", "7"), List.of(get("material", TARGET)));
        assertEquals("SKIPPED_COOLDOWN", get("auto_repost", TARGET).path("status").asText());
        verify(collector).fetchLatestAccountPostEpochMs("elidlicom", "42");
        verifyNoInteractions(judge, publisher);
    }

    @Test void ownerTimelineFollowsFilteredPagesAndNeverTreatsARepeatedCursorAsAnEmptyAccount() {
        var provider = mock(TwitterApiIoProvider.class);
        var owner = mock(XUserSummary.class);
        when(owner.id()).thenReturn("42");
        var post = mock(TwitterApiIoProvider.TextPost.class);
        when(post.kind()).thenReturn(com.xops.hub.model.RawSnapshotModels.TweetKind.POST);
        when(post.authorId()).thenReturn("42");
        when(post.publishedAt()).thenReturn(clock.instant().minusSeconds(60));
        var filtered = new TwitterApiIoProvider.TextPostPage(owner, List.of(), "next", false);
        when(provider.fetchTextPostsPage(eq("elidlicom"), eq("42"), eq(""), anyInt(), any())).thenReturn(filtered);
        when(provider.fetchTextPostsPage(eq("elidlicom"), eq("42"), eq("next"), anyInt(), any()))
                .thenReturn(new TwitterApiIoProvider.TextPostPage(owner, List.of(post), "", true)).thenReturn(filtered);
        var collector = new SourceCollector(store, provider, null, new ObjectMapper(), clock);
        try {
            assertEquals(clock.millis() - 60_000, collector.fetchLatestAccountPostEpochMs("elidlicom", "42"));
            assertEquals(-1L, collector.fetchLatestAccountPostEpochMs("elidlicom", "42"));
        } finally { collector.stop(); }
    }

    @Test void automaticRetryWaitsForAnotherReceiptWithoutUsingItsAttempts() throws Exception {
        failed(AutoPublishMode.REPOST);
        for (String id : List.of(TARGET, "202")) put("membership", id, object().put("topicId", "event"));
        put("material", "202", object().put("authorId", "7").put("username", "source"));
        when(publisher.createTweet("local-token", "Prepared body", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(429, "rejected", false)).thenReturn("9191");
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        when(publisher.createTweet("local-token", "Other", List.of(), "202")).thenThrow(new XPublishClient.XPublishException(502, "lost", true));
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42", "elidlicom", "7",
                object().put("materialId", "202").put("commentText", "Other").put("clientRequestId", "other")));
        for (int i = 0; i < 3; i++) { clock.advanceMinutes(10); gate.runDueRetries(); }
        assertEquals(0, get("auto_repost", TARGET).path("autoRetryAttempts").asInt());
        assertEquals(0, gate.getRetrySnapshot("auto-repost:" + TARGET).retryCount());
        assertEquals(PaidCapabilityGate.RetryState.PENDING, gate.getRetrySnapshot("auto-repost:" + TARGET).state());
        var pending = get("publication", "manual-quote-other");
        new AssistantPublishing(store, null).verifyPublication("42", "manual-quote-other",
                object().put("version", pending.path("version").asInt()).put("published", false));
        clock.advanceMinutes(10);
        gate.runDueRetries();
        assertEquals("9191", get("auto_repost", TARGET).path("publishedTweetId").asText());
        verify(publisher, times(2)).createTweet("local-token", "Prepared body", List.of(), TARGET);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void dashboardAndSourceTimelineRestoreAcceptedManualReceiptWithoutAnotherSend(boolean dashboardFirst) {
        put("material", TARGET, object().put("authorId", "7").put("username", "source")
                .put("text", "Original reporting with stable author attribution.").put("publishedAt", clock.millis() - 1000));
        when(publisher.createTweet("local-token", "Manual", List.of(), TARGET)).thenAnswer(call -> {
            store.failures = 2;
            return "9887";
        });
        var request = object().put("materialId", TARGET).put("commentText", "Manual").put("clientRequestId", "receipt");
        assertEquals("9887", service.publishManualQuote("42", "elidlicom", "7", request).path("publishedTweetId").asText());
        assertEquals("SENDING", get("publication", "manual-quote-receipt").path("status").asText());
        var restarted = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, publisher, clock);
        try {
            if (dashboardFirst) {
                var dashboardSources = mock(SourceCollector.class);
                when(dashboardSources.styleSamples()).thenReturn(object());
                var controller = new AssistantController(store, dashboardSources, mock(AssistantWorker.class),
                        mock(AssistantPublishing.class), new ObjectMapper());
                ReflectionTestUtils.setField(controller, "autoRepostService", restarted);
                var environment = new org.springframework.mock.env.MockEnvironment().withProperty("xops.test-disable-chatgpt-oauth", "true");
                for (String property : List.of(AssistantConnection.OPENAI_KEY_ENV, AssistantConnection.GOOGLE_KEY_ENV,
                        AssistantConnection.MINIMAX_KEY_ENV, AssistantConnection.DEEPSEEK_KEY_ENV, AssistantIntelligence.API_KEY_ENV))
                    environment.setProperty(property, "fixture");
                ReflectionTestUtils.setField(controller, "environment", environment);
                var servlet = new org.springframework.mock.web.MockHttpServletRequest();
                servlet.setAttribute("assistantOwner", new AuthenticatedXUser("42", "elidlicom", "Owner", "", false, clock.instant()));
                var dashboard = controller.dashboard(20, 0, 0, "", "", "", "", "score", servlet);
                assertTrue(dashboard.path("publications").findValuesAsText("publishedTweetId").contains("9887"));
                assertEquals("9887", get("publication", "manual-quote-receipt").path("publishedTweetId").asText());
            }
            var decision = restarted.authorTimeline("7", object()).path("items").get(0).path("decision");
            assertEquals("PUBLISHED", decision.path("persistedStatus").asText());
            assertEquals("https://x.com/elidlicom/status/9887", decision.path("publishedTweetUrl").asText());
            assertEquals("9887", restarted.publishManualQuote("42", "elidlicom", "7", request).path("publishedTweetId").asText());
            verify(publisher).createTweet("local-token", "Manual", List.of(), TARGET);
        } finally { restarted.shutdown(); }
    }

    @Test void timelineIncludesVerifiedManualQuotesAndHighEngagementOlderReceipts() {
        for (int i = 0; i < 23; i++) {
            put("material", Integer.toString(7100+i), object().put("authorId", "7").put("username", "source").put("kind", "POST")
                    .put("text", "A self-contained post with original reporting for item " + i).put("publishedAt", clock.millis() - (25-i) * 60_000L)
                    .put("views", i == 0 ? 80_000 : 60));
        }
        put("auto_repost", "7100", object().put("materialId", "7100").put("status", "PUBLISHED")
                .put("publishedTweetUrl", "https://x.com/i/status/9858").put("publishedAt", clock.millis() - 1000));
        when(publisher.createTweet("local-token", "Manual", List.of(), "7122")).thenThrow(new XPublishClient.XPublishException(502, "lost", true));
        var request = object().put("materialId", "7122").put("commentText", "Manual").put("clientRequestId", "verify");
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42", "elidlicom", "7", request));
        var row = get("publication", "manual-quote-verify");
        new AssistantPublishing(store, null).verifyPublication("42", "manual-quote-verify",
                object().put("version", row.path("version").asInt()).put("published", true).put("tweetId", "9888"));
        var items = service.authorTimeline("7", object().put("materialId", "7101")).path("items");
        for (String id : List.of("7100", "7122")) {
            var item = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                    .filter(it -> id.equals(it.path("material").path("id").asText())).findFirst().orElseThrow();
            assertEquals("PUBLISHED", item.path("decision").path("persistedStatus").asText());
            assertTrue(item.path("decision").path("publishedTweetUrl").asText().endsWith(id.equals("7100") ? "/9858" : "/9888"));
        }
        assertTrue(items.findValuesAsText("id").contains("7101"));
    }

    @Test void manualQuoteCardsFollowTheDispatchedReceiptThroughBothVerificationOutcomes() throws Exception {
        var material = sourcePost(TARGET, "7", "source", clock.millis() - 1000);
        put("material", TARGET, material);
        var request = object().put("materialId", TARGET).put("clientRequestId", "first").put("commentText", "My exact comment");
        var read = object().put("materialId", TARGET).put("receiptOnly", true);
        try (var endpoint = new QuoteEndpoint(503, 503)) {
            var initial = endpoint.quotes.previewRepost("7", read);
            assertEquals("42", initial.at("/owner/accountId").asText());
            assertEquals("7", initial.at("/material/authorId").asText());
            assertEquals(AutoRepostService.quoteSourceFingerprint(material), initial.at("/material/sourceFingerprint").asText());
            assertEquals(0, initial.at("/preview/receiptRevision").asLong(-1));
            long[] reservation = {0};
            when(auth.getValidWriteAccessToken("42", false)).thenAnswer(call -> {
                var sending = endpoint.quotes.previewRepost("7", read).path("preview");
                assertEquals("SENDING", sending.path("decisionCode").asText());
                assertEquals("SENDING", sending.path("persistedStatus").asText());
                assertEquals("My exact comment", sending.path("commentText").asText());
                reservation[0] = sending.path("receiptRevision").asLong();
                assertTrue(reservation[0] > 0);
                return "local-token";
            });
            assertThrows(XPublishClient.XPublishException.class, () -> endpoint.quotes.publishManualQuote("42", "elidlicom", "7", request));
            var uncertain = endpoint.quotes.previewRepost("7", read).path("preview");
            assertTrue(uncertain.path("receiptRevision").asLong() > reservation[0]);
            for (var card : List.of(uncertain,
                    endpoint.quotes.previewRepost("7", object().put("materialId", TARGET)).path("preview"),
                    endpoint.quotes.authorTimeline("7", object()).at("/items/0/decision"),
                    endpoint.quotes.evaluateAuthorOnce("7", object().put("forceRegenerate", true)).at("/items/0/decision"))) {
                assertEquals("UNCERTAIN", card.path("persistedStatus").asText());
                assertEquals("UNCERTAIN", card.path("decisionCode").asText());
                assertEquals("My exact comment", card.path("commentText").asText());
                assertEquals("My exact comment", card.path("publishedText").asText());
                assertEquals(uncertain.path("receiptRevision"), card.path("receiptRevision"));
                assertFalse(card.path("wouldAutoRepost").asBoolean());
            }
            var second = request.deepCopy().put("clientRequestId", "second");
            assertEquals(409, assertThrows(XDataProviderException.class,
                    () -> endpoint.quotes.publishManualQuote("42", "elidlicom", "7", second)).getStatusCode());
            var verify = new AssistantPublishing(store, null);
            var first = get("publication", "manual-quote-first");
            verify.verifyPublication("42", "manual-quote-first", object().put("version", first.path("version").asInt()).put("published", false));
            for (var card : List.of(endpoint.quotes.previewRepost("7", read).path("preview"),
                    endpoint.quotes.authorTimeline("7", object()).at("/items/0/decision"))) {
                assertEquals("FAILED", card.path("persistedStatus").asText());
                assertTrue(card.path("receiptRevision").asLong() > uncertain.path("receiptRevision").asLong());
            }
            var released = endpoint.quotes.previewRepost("7", read).path("preview");
            for (String field : List.of("reason", "publishedText", "publishedTweetUrl", "publicationMode"))
                assertEquals("", released.path(field).asText("missing"));
            var firstAttempt = endpoint.quotes.previewRepost("7", read.deepCopy().put("clientRequestId", "first")).path("preview");
            assertEquals(released.path("receiptRevision"), firstAttempt.path("requestReceiptRevision"));
            var beforeSecond = endpoint.quotes.previewRepost("7", read.deepCopy().put("clientRequestId", "second")).path("preview");
            assertEquals(0, beforeSecond.path("requestReceiptRevision").asLong(-1));
            assertThrows(XPublishClient.XPublishException.class, () -> endpoint.quotes.publishManualQuote("42", "elidlicom", "7", second));
            var beforeVerification = endpoint.quotes.previewRepost("7", read.deepCopy().put("clientRequestId", "second")).path("preview");
            assertTrue(beforeVerification.path("requestReceiptRevision").asLong() > released.path("receiptRevision").asLong());
            var pending = get("publication", "manual-quote-second");
            verify.verifyPublication("42", "manual-quote-second", object().put("version", pending.path("version").asInt())
                    .put("published", true).put("tweetId", "9800"));
            for (var card : List.of(endpoint.quotes.previewRepost("7", read).path("preview"),
                    endpoint.quotes.authorTimeline("7", object()).at("/items/0/decision"))) {
                assertEquals("PUBLISHED", card.path("decisionCode").asText());
                assertEquals("PUBLISHED", card.path("persistedStatus").asText());
                assertEquals("My exact comment", card.path("publishedText").asText());
                assertTrue(card.path("publishedTweetUrl").asText().endsWith("/9800"));
                assertTrue(card.path("receiptRevision").asLong() > beforeVerification.path("receiptRevision").asLong());
            }
            assertEquals(2, endpoint.requests.size());
            verifyNoInteractions(judge);
        }
    }

    @Test void anInFlightPreviewAndItsCacheCannotOverwriteAReceiptForTheCurrentOwnerAndPost() {
        var material = sourcePost(TARGET, "7", "source", clock.millis() - 1000);
        put("material", TARGET, material);
        var req = object().put("materialId", TARGET);
        when(judge.evaluate(any())).thenAnswer(call -> {
            put("auto_repost", TARGET, object().put("ownerAccountId", "42").put("authorId", "7").put("status", "UNCERTAIN")
                    .put("dispatchedAt", clock.millis()).put("commentText", "The dispatched comment."));
            return qualifyingQuote("GENERAL", "A late preview.");
        });
        var old = service.previewRepost("7", req);
        for (var card : List.of(old.path("preview"), service.authorTimeline("7", req).at("/items/0/decision"))) {
            assertEquals("UNCERTAIN", card.path("decisionCode").asText());
            assertEquals("The dispatched comment.", card.path("commentText").asText());
        }
        service.verifyAutomaticPublication("42", TARGET, AutoPublishMode.REPOST, object()
                .put("version", get("auto_repost", TARGET).path("version").asInt()).put("published", false));
        var verified = service.previewRepost("7", req.deepCopy().put("receiptOnly", true)).path("preview");
        assertEquals("FAILED", verified.path("persistedStatus").asText());
        put("publication", "other-owner", object().put("ownerAccountId", "43").put("requestedQuoteTweetId", TARGET)
                .put("source", "MANUAL_QUOTE_REPOST").put("status", "PUBLISHED").put("text", "Another account"));
        put("publication", "other-post", object().put("ownerAccountId", "42").put("materialId", TARGET).put("requestedQuoteTweetId", "102")
                .put("source", "MANUAL_QUOTE_REPOST").put("status", "SENDING").put("text", "Another original"));
        assertEquals(verified, service.previewRepost("7", req.deepCopy().put("receiptOnly", true)).path("preview"));
        assertTrue(verified.path("receiptRevision").asLong() > old.at("/preview/receiptRevision").asLong());
    }

    @Test void quoteAliasesSelectTheLatestAttemptAndKeepAnEarlierConfirmedPostAfterAnUnsentVerification() {
        long now = clock.millis();
        var original = sourcePost(TARGET, "7", "source", now - 2000);
        put("material", TARGET, original);
        put("material", "imported", original.deepCopy().put("id", "imported"));
        put("publication", "manual-quote-new", object().put("source", "MANUAL_LINK_REPOST").put("ownerAccountId", "42")
                .put("authorId", "7").put("materialId", "imported").put("requestedQuoteTweetId", TARGET)
                .put("requestedText", "My comment").put("text", "My comment https://x.com/source/status/101")
                .put("dispatchedAt", now).put("status", "UNCERTAIN"));
        put("publication", "manual-quote-older", object().put("source", "MANUAL_QUOTE_REPOST")
                .put("materialId", TARGET).put("quoteTweetId", TARGET).put("text", "Earlier comment")
                .put("publishedTweetUrl", "https://x.com/i/status/9123").put("publishedAt", now - 1000).put("status", "PUBLISHED"));
        var items = service.authorTimeline("7", object()).path("items");
        assertEquals(2, items.size());
        for (var item : items) {
            assertEquals("UNCERTAIN", item.at("/decision/decisionCode").asText());
            assertEquals("My comment", item.at("/decision/commentText").asText());
            assertEquals("My comment https://x.com/source/status/101", item.at("/decision/publishedText").asText());
            assertEquals("", item.at("/decision/publishedTweetUrl").asText());
        }
        var row = get("publication", "manual-quote-new");
        new AssistantPublishing(store, null).verifyPublication("42", row.path("id").asText(),
                object().put("version", row.path("version").asInt()).put("published", false));
        var released = service.previewRepost("7", object().put("materialId", "imported").put("receiptOnly", true)).path("preview");
        assertEquals("PUBLISHED", released.path("persistedStatus").asText());
        assertTrue(released.path("receiptRevision").asLong() > items.get(0).at("/decision/receiptRevision").asLong());
        assertTrue(released.path("publishedTweetUrl").asText().endsWith("/9123"));
    }

    @Test void aPreviewRequestMustStillBelongToTheLoggedInOwnerWhenItReturns() throws Exception {
        put("material", TARGET, sourcePost(TARGET, "7", "source", clock.millis() - 1000));
        var mapper = new ObjectMapper();
        var controller = new AssistantController(store, mock(SourceCollector.class), mock(AssistantWorker.class),
                mock(AssistantPublishing.class), mapper);
        ReflectionTestUtils.setField(controller, "autoRepostService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        var session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser("42", "elidlicom", "Owner", "", false, clock.instant()));
        String url = "/api/content-assistant/sources/7/repost-preview";
        var read = object().put("materialId", TARGET).put("receiptOnly", true);
        var response = mvc.perform(post(url).session(session).header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json").content(read.toString())).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(0, mapper.readTree(response.getContentAsByteArray()).at("/preview/receiptRevision").asLong(-1));
        when(judge.evaluate(any())).thenAnswer(call -> {
            put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "43").put("username", "rebound"));
            return qualifyingQuote("ROUTINE", "A late result");
        });
        mvc.perform(post(url).session(session).header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                .content(read.put("receiptOnly", false).toString())).andExpect(status().isForbidden());
    }

    @Test void replayReadsAJournaledManualReceiptWhileDatabaseStillRejectsPublishedRows() throws Exception {
        var request = object().put("materialId", TARGET).put("commentText", "Manual").put("clientRequestId", "first");
        when(publisher.createTweet("local-token", "Manual", List.of(), TARGET)).thenReturn("9865");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("assistant.sqlite"));
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_receipt BEFORE INSERT ON revisions
                    WHEN NEW.kind='publication' AND json_extract(NEW.data,'$.status')='PUBLISHED'
                    BEGIN SELECT RAISE(FAIL, 'simulated receipt storage failure'); END
                    """);
            assertEquals("9865", service.publishManualQuote("42", "elidlicom", "7", request).path("publishedTweetId").asText());
            assertEquals("9865", service.publishManualQuote("42", "elidlicom", "7", request.put("clientRequestId", "replay"))
                    .path("publishedTweetId").asText());
            var knownReceipt = service.previewRepost("7", object().put("materialId", TARGET).put("receiptOnly", true)).path("preview");
            assertEquals("PUBLISHED", knownReceipt.path("decisionCode").asText());
            assertTrue(knownReceipt.path("publishedTweetUrl").asText().endsWith("/9865"));
            assertEquals(409, assertThrows(XDataProviderException.class,
                    () -> service.requireReceiptReady("manual-quote-first")).getStatusCode());
            statement.execute("DROP TRIGGER reject_receipt");
        }
        service.reconcileAcceptedReceipts();
        assertEquals("9865", get("publication", "manual-quote-first").path("publishedTweetId").asText());
        verify(publisher).createTweet("local-token", "Manual", List.of(), TARGET);
    }

    @Test void bookmarkAndFollowerChangesInvalidateAStoredPreview() {
        put("material", TARGET, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                .put("text", "A first-hand report on an upcoming release.").put("publishedAt", clock.millis() - 60_000)
                .put("views", 1000).put("likes", 10).put("bookmarks", 0).put("authorFollowersCount", 125));
        when(judge.evaluate(any())).thenAnswer(call -> {
            PiAgentViralJudge.EvaluationInput input = call.getArgument(0);
            String evidence = input.attentionEvidence().path("weightedEngagementRatePct").asDouble()
                    + "/" + input.attentionEvidence().path("authorFollowersCount").asLong();
            return new PiAgentViralJudge.ViralJudgment(true, 96, 95, 95, 96, "ROUTINE", evidence, "Reason", evidence);
        });
        var body = object().put("materialId", TARGET).put("forceWriteComment", true);
        assertEquals("1.0/125", service.previewRepost("7", body).path("preview").path("summary").asText());
        put("material", TARGET, get("material", TARGET).put("bookmarks", 100).put("authorFollowersCount", 50_000));
        assertEquals("21.0/50000", service.previewRepost("7", body).path("preview").path("summary").asText());
        verify(judge, times(2)).evaluate(any());
    }

    @Test void accountBatchNamesOnlyRowsForWhichItProducedAComment() {
        for (int i = 0; i < 9; i++) {
            put("material", Integer.toString(7100 + i), object().put("authorId", "7").put("username", "source").put("kind", "POST")
                    .put("text", "Recent public reporting for batch candidate " + i).put("publishedAt", clock.millis() - (10 - i) * 60_000L));
        }
        var generated = new PiAgentViralJudge.ViralJudgment(true, 95, true, 95, 95, 95, 95,
                "GENERAL", "Update", "Reason", "A new comment for the selected post.");
        when(judge.evaluateAccountBatch(any())).thenReturn(new PiAgentViralJudge.AccountBatchJudgment("7108", java.util.Map.of("7108", generated)));
        var result = service.evaluateAuthorOnce("7", object().put("materialId", "7100").put("forceRegenerate", true));
        assertTrue(result.path("items").findValuesAsText("id").contains("7100"));
        assertEquals(1, result.path("regeneratedCommentIds").size());
        assertEquals("7108", result.path("regeneratedCommentIds").get(0).asText());
    }

    private void put(String table, String id, ObjectNode record) { store.tx(tx -> tx.put(table, id, record)); }
    private ObjectNode get(String table, String id) { return store.tx(tx -> tx.get(table, id)); }
    private void finish() throws Exception { worker.submit(() -> {}).get(10, TimeUnit.SECONDS); }

    private void failed(AutoPublishMode mode) {
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        if (rewrite) put("source", "7", get("source", "7").put("autoRewriteEnabled", true));
        put(rewrite ? "auto_rewrite" : "auto_repost", TARGET, object().put("materialId", TARGET)
                .put("authorId", "7").put("username", "source").put("status", "FAILED")
                .put(rewrite ? "rewrittenText" : "commentText", "Prepared body"));
    }

    @Test void anEnabledSourceCannotClaimAReassignedHandlesMaterial() {
        put("source", "7", object().put("authorId", "7").put("username", "shared")
                .put("status", "ACTIVE").put("autoRepostEnabled", true));
        put("source", "8", object().put("authorId", "8").put("username", "shared")
                .put("status", "ACTIVE").put("autoRepostEnabled", false));
        put("material", TARGET, object().put("authorId", "8").put("username", "shared").put("kind", "POST")
                .put("text", "The new holder of this handle shares a detailed model release today.")
                .put("publishedAt", clock.millis()));
        put("auto_repost", "9000", object().put("authorId", "7").put("status", "SKIPPED_COOLDOWN")
                .put("deferredPendingEval", true).put("deferredUntilMs", clock.millis() - 1));
        service.recheckDeferredCooldownCandidates();
        service.processAuthorBatch(get("source", "7"), List.of(get("material", TARGET)));
        assertNull(get("auto_repost", TARGET));
        verifyNoInteractions(judge, auth, publisher);
    }

    @Test void quotePreviewUsesTheSelectedOlderPostAndRejectsAnotherAuthorsId() {
        for (int i = 0; i < 21; i++) {
            put("material", Integer.toString(7100 + i), object().put("authorId", "7").put("username", "source").put("kind", "POST")
                    .put("text", "A self-contained post covering update " + i).put("publishedAt", clock.millis() - (21 - i) * 60000L)
                    .put("views", i == 0 ? 80000 : 60));
        }
        ObjectNode requested = object().put("materialId", "7100").put("forceWriteComment", true);
        assertTrue(service.authorTimeline("7", object()).path("items").findValuesAsText("id").contains("7100"));
        when(judge.evaluate(any())).thenReturn(new PiAgentViralJudge.ViralJudgment(true, 96, 95, 95, 96, "ROUTINE", "Topic", "Reason", "One comment."));
        assertEquals("7100", service.previewRepost("7", requested).path("material").path("id").asText());
        store.tx(tx -> {
            tx.cacheRewriteMaterial(object().put("id", "7000").put("authorId", "7").put("username", "source").put("kind", "POST")
                    .put("text", "An older article still available in the author's timeline.")
                    .put("publishedAt", clock.millis() - Duration.ofDays(9).toMillis()).put("views", 90000), "");
            return null;
        });
        assertEquals("7000", service.previewRepost("7", requested.put("materialId", "7000")).path("material").path("id").asText());
        ObjectNode legacySource = get("source", "7");
        legacySource.remove("authorId");
        put("source", "7", legacySource);
        put("material", "999", object().put("authorId", "8").put("username", "source").put("text", "Another author's post."));
        for (String id : List.of("999", "998"))
            assertEquals(404, assertThrows(XDataProviderException.class, () -> service.previewRepost("7", requested.put("materialId", id))).getStatusCode());
        verify(judge, times(2)).evaluate(any());
        verifyNoInteractions(publisher);
    }

    @Test void restoredCooldownRefreshesTheOwnerTimelineAndRetainsTheCandidateOnFailure() {
        var sources = (SourceCollector) ReflectionTestUtils.getField(service, "sources");
        long recent = clock.millis() - 60000L;
        when(sources.fetchLatestAccountPostEpochMs("elidlicom", "42"))
                .thenThrow(new XDataProviderException(503, "Timeline unavailable")).thenReturn(recent);
        put("material", TARGET, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                .put("publishedAt", recent).put("text", "A sufficiently long post from the selected monitored author."));
        put("auto_repost", TARGET, object().put("materialId", TARGET).put("authorId", "7").put("username", "source")
                .put("status", "SKIPPED_COOLDOWN").put("deferredPendingEval", true).put("deferredUntilMs", clock.millis() - 1));
        service.recheckDeferredCooldownCandidates();
        assertTrue(get("auto_repost", TARGET).path("deferredPendingEval").asBoolean());
        clock.advanceMinutes(10);
        service.recheckDeferredCooldownCandidates();
        ObjectNode pending = get("auto_repost", TARGET);
        assertEquals("SKIPPED_COOLDOWN", pending.path("status").asText());
        assertEquals(recent + AutoRepostService.resolveOwnerTweetRequiredGapMs(recent), pending.path("deferredUntilMs").asLong());
        verify(sources, times(2)).fetchLatestAccountPostEpochMs("elidlicom", "42");
        verifyNoInteractions(judge, publisher);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cooldownDefersRetriesAcrossRestartUntilTheFirstPermittedSend(boolean onlyOnTimeline) throws Exception {
        failed(AutoPublishMode.REPOST);
        when(publisher.createTweet("local-token", "Prepared body", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(429, "Definite rejection", false)).thenReturn("9901");
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        clock.advanceMinutes(1);
        long recent = clock.millis();
        long next = recent + AutoRepostService.resolveOwnerTweetRequiredGapMs(recent);
        if (onlyOnTimeline) {
            var sources = (SourceCollector) ReflectionTestUtils.getField(service, "sources");
            when(sources.fetchLatestAccountPostEpochMs("elidlicom", "42")).thenReturn(recent);
        } else {
            put("publication", "manual-post", object().put("status", "PUBLISHED").put("publishedAt", recent));
        }
        for (int check = 0; check < 3; check++) {
            clock.advanceMinutes(10);
            gate.runDueRetries();
        }
        String key = "auto-repost:" + TARGET;
        assertEquals(PaidCapabilityGate.RetryState.PENDING, gate.getRetrySnapshot(key).state());
        assertEquals(0, gate.getRetrySnapshot(key).retryCount());
        assertEquals(next, get("auto_repost", TARGET).path("autoRetryAt").asLong());
        assertEquals(0, get("auto_repost", TARGET).path("autoRetryAttempts").asInt());
        verify(publisher).createTweet("local-token", "Prepared body", List.of(), TARGET);
        var recovered = new PaidCapabilityGate(clock);
        ReflectionTestUtils.setField(service, "paidGate", recovered);
        service.recoverStaleRecords();
        assertEquals(next, recovered.getRetrySnapshot(key).nextRetryAtEpochMs());
        clock.now = Instant.ofEpochMilli(next);
        recovered.runDueRetries();
        assertEquals("PUBLISHED", get("auto_repost", TARGET).path("status").asText());
        verify(publisher, times(2)).createTweet("local-token", "Prepared body", List.of(), TARGET);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void quietHoursAreEnforcedInsideTheHttpQueueAndImmediatelyBeforeTheRequest(boolean crossAtPermit) throws Exception {
        clock.now = Instant.parse("2026-11-27T03:59:59.500Z");
        put("material", TARGET, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                .put("text", "A sufficiently long post from the monitored source.").put("publishedAt", clock.millis() - 5000L));
        var props = new XOpsProperties();
        props.setOfficialBaseUrl("http://127.0.0.1:1/2");
        var pool = new SharedApiExecutor(props) {
            @Override public <T> T execute(java.util.concurrent.Callable<T> action) {
                if (!crossAtPermit) clock.now = clock.now.plusSeconds(1);
                return super.execute(action);
            }
        };
        var budget = mock(UpstreamBudgetGuard.class);
        if (crossAtPermit) doAnswer(call -> { clock.now = clock.now.plusSeconds(1); return null; })
                .when(budget).acquirePermit(any(java.net.URI.class), anyString());
        var http = mock(java.net.http.HttpClient.class);
        var client = new XPublishClient(props, new ObjectMapper(), pool, budget);
        ReflectionTestUtils.setField(client, "httpClient", http);
        when(judge.evaluate(any())).thenReturn(new PiAgentViralJudge.ViralJudgment(true, 96, 95, 95, 96, "ROUTINE", "Topic", "Reason", "One comment."));
        var quotes = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, client, clock);
        try {
            quotes.processAuthorBatch(get("source", "7"), List.of(get("material", TARGET)));
            assertEquals("SKIPPED_QUIET_HOURS", get("auto_repost", TARGET).path("status").asText());
            assertNull(get("publication", "auto-repost-" + TARGET));
            verifyNoInteractions(http);
            if (!crossAtPermit) verifyNoInteractions(budget);
        } finally { quotes.shutdown(); pool.shutdown(); }
    }

    @Test void sourceChangesAreRecheckedBeforeSendingAndOnRecoveredRetries() throws Exception {
        failed(AutoPublishMode.REPOST);
        put("material", TARGET, object().put("authorId", "7").put("username", "shared"));
        when(auth.getValidWriteAccessToken("42", false)).thenAnswer(call -> {
            put("material", TARGET, object().put("authorId", "8").put("username", "shared"));
            return "token";
        });
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        assertEquals("FAILED", get("auto_repost", TARGET).path("status").asText());
        assertThrows(XDataProviderException.class, () -> service.retry(TARGET, AutoPublishMode.REPOST));
        put("auto_repost", TARGET, get("auto_repost", TARGET).put("autoRetryAt", clock.millis() + 100));
        service.recoverStaleRecords();
        clock.advanceMinutes(11);
        gate.runDueRetries();
        verify(auth, times(1)).getValidWriteAccessToken("42", false);
        verifyNoInteractions(publisher);
    }

    @Test void revocationWhileWaitingForTheHttpExecutorStopsBothThePermitAndThePost() throws Exception {
        failed(AutoPublishMode.REPOST);
        var props = new XOpsProperties();
        props.setOfficialBaseUrl("http://127.0.0.1:1/2");
        var pool = new SharedApiExecutor(props) {
            @Override public <T> T execute(java.util.concurrent.Callable<T> action) {
                put("source", "7", get("source", "7").put("autoRepostEnabled", false));
                return super.execute(action);
            }
        };
        var budget = mock(UpstreamBudgetGuard.class);
        var http = mock(java.net.http.HttpClient.class);
        var client = new XPublishClient(props, new ObjectMapper(), pool, budget);
        ReflectionTestUtils.setField(client, "httpClient", http);
        var quotes = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, client, clock);
        ReflectionTestUtils.setField(quotes, "paidGate", gate);
        try {
            quotes.retry(TARGET, AutoPublishMode.REPOST);
            ((ExecutorService) ReflectionTestUtils.getField(quotes, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            verifyNoInteractions(budget, http);
            assertEquals("FAILED", get("auto_repost", TARGET).path("status").asText());
            assertNull(gate.getRetrySnapshot("auto-repost:" + TARGET));
        } finally {
            quotes.shutdown();
            pool.shutdown();
        }
    }

    @Test void restoredRetriesCountOnlyThePreviousDefiniteRemoteRefusal() {
        failed(AutoPublishMode.REPOST);
        for (int retry = 1; retry <= 3; retry++) {
            var row = get("auto_repost", TARGET);
            put("auto_repost", TARGET, row.put("status", "SENDING").put("automaticRetryRunning", true).put("autoRetryAttempts", retry - 1));
            row.remove("automaticRetryRunning");
            put("auto_repost", TARGET, row.put("status", "FAILED").put("autoRetryAttempts", retry)
                    .put("error", retry == 1 ? "X 官方接口拒绝发布" : UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE));
        }
        service.recoverStaleRecords();
        assertEquals(1, gate.getRetrySnapshot("auto-repost:" + TARGET).retryCount());
        assertEquals(1, get("auto_repost", TARGET).path("autoRetryAttempts").asInt());
        assertTrue(get("auto_repost", TARGET).path("autoRetryAt").asLong() > clock.millis());
    }

    @Test void patrolChecksTheVerifiedHandleAndWaitsWhenItCannotReadTheOwnersLatestPost() {
        put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "newname"));
        put("history_material", "old-post", object().put("authorId", "42").put("kind", "POST")
                .put("publishedAt", clock.millis() - Duration.ofHours(5).toMillis()));
        var sources = mock(SourceCollector.class);
        when(sources.fetchLatestAccountPostEpochMs("newname", "42"))
                .thenThrow(new XDataProviderException(503, "timeline unavailable"))
                .thenReturn(clock.millis() - 60_000L);
        var patrol = new AutoRepostService(store, sources, judge, auth, publisher, clock);
        try {
            assertEquals("OWNER_CHECK_FAILED", patrol.runOwnerIdleRepostPatrol().path("status").asText());
            assertEquals("RECENTLY_TWEETED", patrol.runOwnerIdleRepostPatrol().path("status").asText());
            verify(sources, times(2)).fetchLatestAccountPostEpochMs("newname", "42");
            verifyNoInteractions(judge, auth, publisher);
        } finally {
            patrol.shutdown();
        }
    }

    @Test @SuppressWarnings("unchecked")
    void localTweetBudgetDefersWithoutSpendingDeliveryRetriesAndSendsOnceTheNextDay() throws Exception {
        var properties = new XOpsProperties();
        properties.setDiskCacheDir(data.resolve("budget").toString());
        properties.setOfficialBaseUrl("http://127.0.0.1:1/2");
        properties.setDailyUpstreamApiBudget(1);
        var guard = new UpstreamBudgetGuard(properties, true);
        ReflectionTestUtils.setField(guard, "clock", clock);
        var pool = new SharedApiExecutor(properties);
        var client = new XPublishClient(properties, new ObjectMapper(), pool, guard);
        var http = mock(java.net.http.HttpClient.class);
        var ok = mock(java.net.http.HttpResponse.class);
        when(ok.statusCode()).thenReturn(201);
        when(ok.body()).thenReturn("{\"data\":{\"id\":\"9014\"}}");
        when(http.send(any(java.net.http.HttpRequest.class), any())).thenReturn(ok);
        ReflectionTestUtils.setField(client, "httpClient", http);
        var quotes = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, client, clock);
        ReflectionTestUtils.setField(quotes, "paidGate", gate);
        try {
            PaidCapabilityGate.withCaller("renamed", "42", true, () -> {
                guard.acquirePermit(java.net.URI.create("http://127.0.0.1:1/2/tweets"), "x_tweet_create");
                return null;
            });
            failed(AutoPublishMode.REPOST);
            quotes.retry(TARGET, AutoPublishMode.REPOST);
            ((ExecutorService) ReflectionTestUtils.getField(quotes, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 5; i++) {
                clock.advanceMinutes(10);
                gate.runDueRetries();
            }
            verifyNoInteractions(http);
            assertEquals(0, gate.getRetrySnapshot("auto-repost:" + TARGET).retryCount());
            assertEquals(0, get("auto_repost", TARGET).path("autoRetryAttempts").asInt());
            assertTrue(get("auto_repost", TARGET).path("autoRetryAt").asLong() > clock.millis());
            var restarted = new PaidCapabilityGate(clock);
            ReflectionTestUtils.setField(quotes, "paidGate", restarted);
            quotes.recoverStaleRecords();
            clock.advanceMinutes(24 * 60);
            restarted.runDueRetries();
            verify(http).send(any(java.net.http.HttpRequest.class), any());
            assertEquals("PUBLISHED", get("auto_repost", TARGET).path("status").asText());
        } finally {
            quotes.shutdown();
            pool.shutdown();
        }
    }

    @ParameterizedTest @EnumSource(AutoPublishMode.class)
    void acceptedPostWithAFailedReceiptWriteNeverSendsAgain(AutoPublishMode mode) throws Exception {
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        failed(mode);
        String quoteId = rewrite ? null : TARGET;
        when(publisher.createTweet("local-token", "Prepared body", List.of(), quoteId)).thenAnswer(call -> {
            store.failures = 1;
            return "9001";
        });
        service.retry(TARGET, mode);
        finish();
        clock.advanceMinutes(10);
        gate.runDueRetries();
        finish();
        ObjectNode auto = get(rewrite ? "auto_rewrite" : "auto_repost", TARGET);
        assertEquals("PUBLISHED", auto.path("status").asText());
        assertEquals("9001", auto.path("publishedTweetId").asText());
        ObjectNode publication = get("publication", (rewrite ? "auto-rewrite-" : "auto-repost-") + TARGET);
        assertEquals(rewrite ? "ORIGINAL" : "QUOTE", publication.path("publicationMode").asText());
        assertThrows(XDataProviderException.class, () -> service.retry(TARGET, mode));
        verify(publisher, times(1)).createTweet("local-token", "Prepared body", List.of(), quoteId);
    }

    @ParameterizedTest @EnumSource(AutoPublishMode.class)
    void twoFailedReceiptWritesRecoverTheAcceptedPostOnRestart(AutoPublishMode mode) throws Exception {
        failed(mode);
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        String quoteId = rewrite ? null : TARGET;
        when(publisher.createTweet("local-token", "Prepared body", List.of(), quoteId)).thenAnswer(call -> {
            store.failures = 2;
            return "9002";
        });
        service.retry(TARGET, mode);
        finish();
        assertEquals(1, store.pendingReceipts("auto-and-manual").size());
        var restarted = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, publisher, clock);
        try { restarted.recoverStaleRecords(); } finally { restarted.shutdown(); }
        var record = get(rewrite ? "auto_rewrite" : "auto_repost", TARGET);
        assertEquals("PUBLISHED", record.path("status").asText());
        assertEquals("9002", record.path("publishedTweetId").asText());
        assertEquals("42", record.path("ownerAccountId").asText());
        assertTrue(store.pendingReceipts("auto-and-manual").isEmpty());
        assertThrows(XDataProviderException.class, () -> service.retry(TARGET, mode));
        clock.advanceMinutes(10);
        gate.runDueRetries();
        verify(publisher, times(1)).createTweet("local-token", "Prepared body", List.of(), quoteId);
    }

    @Test void retryOnlyRewritesTheSelectedMaterialEvenWhenAnotherRanksHigher() throws Exception {
        failed(AutoPublishMode.REPOST);
        put("source", "7", object().put("authorId", "7").put("username", "source").put("status", "ACTIVE")
                .put("autoRewriteEnabled", true));
        for (String id : List.of(TARGET, "202")) {
            boolean newer = id.equals("202");
            put("material", id, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                    .put("text", "A verifiable observation about how ordinary writing decisions affect a published article. Candidate " + id)
                    .put("publishedAt", clock.millis() - (newer ? 3_600_000L : 86_400_000L))
                    .put("views", newer ? 90000 : 10000).put("likes", newer ? 800 : 80));
        }
        put("auto_rewrite", TARGET, object().put("materialId", TARGET).put("authorId", "7").put("status", "JUDGE_FAILED"));
        when(judge.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
            PiAgentViralJudge.RewriteInput input = call.getArgument(0);
            return new PiAgentViralJudge.RewriteJudgment(true, 94, "", "", "", "Rewritten " + input.sourceText().substring(input.sourceText().length() - 3));
        });
        when(publisher.createTweet("local-token", "Rewritten 101", List.of(), null)).thenReturn("9003");
        var controller = new AssistantController(store, mock(SourceCollector.class), mock(AssistantWorker.class),
                mock(AssistantPublishing.class), new ObjectMapper());
        ReflectionTestUtils.setField(controller, "autoRepostService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        String retryPath = "/api/content-assistant/auto-reposts/" + TARGET + "/retry";
        var session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser("42", "elidlicom", "Owner", "", false, clock.instant()));
        mvc.perform(post(retryPath).session(session).header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isConflict());
        mvc.perform(post(retryPath).session(session).header("X-Requested-With", "XMLHttpRequest").param("mode", "REWRITE"))
                .andExpect(status().isAccepted());
        finish();
        assertEquals("PUBLISHED", get("auto_rewrite", TARGET).path("status").asText());
        assertEquals("FAILED", get("auto_repost", TARGET).path("status").asText());
        assertNull(get("auto_rewrite", "202"));
        assertThrows(XDataProviderException.class, () -> service.retry(TARGET, AutoPublishMode.REPOST));
        verify(publisher).createTweet("local-token", "Rewritten 101", List.of(), null);
        verifyNoMoreInteractions(publisher);
    }

    @Test void missingOriginalMaterialDoesNotPublishASubstitute() {
        put("auto_rewrite", TARGET, object().put("materialId", TARGET).put("authorId", "7").put("status", "JUDGE_FAILED"));
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry(TARGET, AutoPublishMode.REWRITE)).getStatusCode());
        assertEquals("JUDGE_FAILED", get("auto_rewrite", TARGET).path("status").asText());
        verifyNoInteractions(judge, publisher);
    }

    @Test void automaticRetryDefersQuietHoursWithoutUsingItsBudget() throws Exception {
        clock.now = Instant.parse("2026-11-27T03:55:00Z"); // 11:55 Beijing
        failed(AutoPublishMode.REPOST);
        when(publisher.createTweet("local-token", "Prepared body", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(429, "Definitive rejection", false)).thenReturn("9004");
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        clock.advanceMinutes(10);
        gate.runDueRetries();
        verify(publisher).createTweet("local-token", "Prepared body", List.of(), TARGET);
        var pending = gate.getRetrySnapshot("auto-repost:" + TARGET);
        assertEquals(0, pending.retryCount());
        assertEquals(PaidCapabilityGate.RetryState.PENDING, pending.state());
        assertEquals(Instant.parse("2026-11-27T16:00:05Z").toEpochMilli(), pending.nextRetryAtEpochMs());
        clock.now = Instant.ofEpochMilli(pending.nextRetryAtEpochMs());
        gate.runDueRetries();
        assertEquals("PUBLISHED", get("auto_repost", TARGET).path("status").asText());
        verify(publisher, times(2)).createTweet("local-token", "Prepared body", List.of(), TARGET);
    }

    @ParameterizedTest @EnumSource(AutoPublishMode.class)
    void authorCanVerifyOlderAutomaticRecordsAndReplayTheDecisionWithoutPosting(AutoPublishMode mode) throws Exception {
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        String table = rewrite ? "auto_rewrite" : "auto_repost";
        String receipt = rewrite ? "auto-rewrite-" : "auto-repost-";
        long dispatch = clock.millis() - 90000L;
        ObjectNode uncertain = object().put("materialId", TARGET).put("ownerAccountId", "42").put("authorId", "7")
                .put("topicId", "one-topic").put("status", "UNCERTAIN").put("dispatchedAt", dispatch)
                .put(rewrite ? "rewrittenText" : "commentText", "Original requested body").put("quoteTweetId", TARGET);
        put(table, TARGET, uncertain);
        long yesterday = dispatch - Duration.ofDays(1).toMillis();
        put(table, "303", uncertain.deepCopy().put("materialId", "303").put("dispatchedAt", yesterday));
        put(table, "404", uncertain.deepCopy().put("materialId", "404").put("ownerAccountId", "another-owner").put("dispatchedAt", yesterday));
        for (int i = 0; i < 23; i++) put(table, "newer-" + i, object().put("status", "SKIPPED"));
        var sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        var mapper = new ObjectMapper();
        var controller = new AssistantController(store, sources, mock(AssistantWorker.class), mock(AssistantPublishing.class), mapper);
        ReflectionTestUtils.setField(controller, "autoRepostService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        var session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser("42", "elidlicom", "Owner", "", false, clock.instant()));
        String base = "/api/content-assistant/";
        var dashboard = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "dashboard").session(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        var rows = mapper.readTree(dashboard.getContentAsByteArray()).path("unconfirmedPublications");
        assertEquals(2, rows.size());
        assertTrue(rows.findValuesAsText("materialId").contains(TARGET));
        assertEquals(mode.name(), rows.get(0).path("autoKind").asText());
        String path = base + "auto-reposts/" + TARGET + "/verify";
        ObjectNode yes = object().put("version", 1).put("published", true).put("tweetId", "9123");
        for (int click = 0; click < 2; click++) {
            mvc.perform(post(path).param("mode", mode.name()).session(session).header("X-Requested-With", "XMLHttpRequest")
                    .contentType("application/json").content(yes.toString())).andExpect(status().isOk());
        }
        assertEquals(2, get(table, TARGET).path("version").asInt());
        ObjectNode confirmed = get("publication", receipt + TARGET);
        assertEquals("9123", confirmed.path("publishedTweetId").asText());
        assertEquals(dispatch, confirmed.path("publishedAt").asLong());
        assertEquals("Original requested body", confirmed.path("requestedText").asText());
        assertEquals(1, (int) store.tx(tx -> AutoRepostService.autoPublicationsToday(tx, clock.millis())));
        ObjectNode no = object().put("version", 1).put("published", false);
        assertThrows(XDataProviderException.class, () -> service.verifyAutomaticPublication("42", TARGET, mode, no));
        assertEquals(403, assertThrows(XDataProviderException.class, () -> service.verifyAutomaticPublication("42", "404", mode, no)).getStatusCode());
        service.verifyAutomaticPublication("42", "303", mode, no);
        assertEquals("FAILED", get(table, "303").path("status").asText());
        assertNull(get("publication", receipt + "303"));
        verifyNoInteractions(publisher, judge);
    }

    @Test void persistentDefinitiveRefusalStopsAfterExactlyThreeAutomaticAttempts() throws Exception {
        failed(AutoPublishMode.REPOST);
        when(publisher.createTweet("local-token", "Prepared body", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(429, "Definitive rejection", false));
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        for (int i = 0; i < 5; i++) {
            clock.advanceMinutes(10);
            gate.runDueRetries();
        }
        var snapshot = gate.getRetrySnapshot("auto-repost:" + TARGET);
        assertEquals(PaidCapabilityGate.RetryState.EXHAUSTED, snapshot.state());
        assertEquals(3, snapshot.retryCount());
        verify(publisher, times(4)).createTweet("local-token", "Prepared body", List.of(), TARGET);
    }

    @Test void restartRestoresOriginalRetryDeadlineAndRemainingAttempts() throws Exception {
        failed(AutoPublishMode.REPOST);
        when(publisher.createTweet("local-token", "Prepared body", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(429, "Definitive rejection", false));
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        PaidCapabilityGate current = gate;
        for (int attempt = 0; attempt < 3; attempt++) {
            long due = current.getRetrySnapshot("auto-repost:" + TARGET).nextRetryAtEpochMs();
            clock.advanceMinutes(5);
            current = new PaidCapabilityGate(clock);
            ReflectionTestUtils.setField(service, "paidGate", current);
            service.recoverStaleRecords();
            var recovered = current.getRetrySnapshot("auto-repost:" + TARGET);
            assertNotNull(recovered);
            assertEquals(attempt, recovered.retryCount());
            assertEquals(due, recovered.nextRetryAtEpochMs());
            current.runDueRetries();
            verify(publisher, times(attempt + 1)).createTweet("local-token", "Prepared body", List.of(), TARGET);
            clock.advanceMinutes(5);
            current.runDueRetries();
        }
        current = new PaidCapabilityGate(clock);
        ReflectionTestUtils.setField(service, "paidGate", current);
        service.recoverStaleRecords();
        assertNull(current.getRetrySnapshot("auto-repost:" + TARGET));
        verify(publisher, times(4)).createTweet("local-token", "Prepared body", List.of(), TARGET);
    }

    @Test void manualQuoteCanResumeAfterAuthorizationAndReplayItsReceipt() {
        ObjectNode request = object().put("materialId", TARGET).put("commentText", "Comment").put("clientRequestId", "manual");
        when(auth.getValidWriteAccessToken("42", false)).thenThrow(new XDataProviderException(403, "Missing scope")).thenReturn("local-token");
        when(publisher.createTweet("local-token", "Comment", List.of(), TARGET)).thenReturn("9005");
        assertThrows(XDataProviderException.class, () -> service.publishManualQuote("42", "elidlicom", "source", request));
        assertEquals("FAILED", get("publication", "manual-quote-manual").path("status").asText());
        verifyNoInteractions(publisher);
        for (String key : List.of("manual", "manual", "new-client-key")) {
            request.put("clientRequestId", key);
            ObjectNode receipt = service.publishManualQuote("42", "elidlicom", "source", request);
            assertEquals("9005", receipt.path("publishedTweetId").asText());
            assertEquals("QUOTE", receipt.path("publicationMode").asText());
        }
        verify(publisher).createTweet("local-token", "Comment", List.of(), TARGET);
    }

    @Test void dailyAutomaticAllowanceUsesReceiptsAndSurvivesManualQuoteOfSameMaterial() throws Exception {
        put("material", "202", object().put("authorId", "9").put("username", "other-source"));
        when(publisher.createTweet("local-token", "Manual", List.of(), "202")).thenReturn("9007");
        var manual = object().put("clientRequestId", "manual-only").put("materialId", "202").put("commentText", "Manual");
        service.publishManualQuote("42", "elidlicom", "other-source", manual);
        assertEquals(0, (int) store.tx(tx -> AutoRepostService.autoPublicationsToday(tx, clock.millis())));
        clock.advanceMinutes(125);
        failed(AutoPublishMode.REPOST);
        when(publisher.createTweet("local-token", "Prepared body", List.of(), TARGET)).thenReturn("9008");
        service.retry(TARGET, AutoPublishMode.REPOST);
        finish();
        when(publisher.createTweet("local-token", "Another comment", List.of(), TARGET)).thenReturn("9009");
        service.publishManualQuote("42", "elidlicom", "source", manual.put("clientRequestId", "manual-after-auto")
                .put("materialId", TARGET).put("commentText", "Another comment"));
        assertEquals("9008", get("auto_repost", TARGET).path("publishedTweetId").asText());
        assertEquals(1, (int) store.tx(tx -> AutoRepostService.autoPublicationsToday(tx, clock.millis())));
        // An older release already overwrote this projection with the later manual receipt.
        put("auto_repost", TARGET, get("auto_repost", TARGET).put("manual", true).put("publishedTweetId", "9009"));
        assertEquals(1, (int) store.tx(tx -> AutoRepostService.autoPublicationsToday(tx, clock.millis())));
        put("auto_rewrite", "303", object().put("status", "UNCERTAIN").put("dispatchedAt", clock.millis()));
        assertEquals(2, (int) store.tx(tx -> AutoRepostService.autoPublicationsToday(tx, clock.millis())));
    }

    @Test void verifiedManualQuoteKeepsSameAuthorCooldownAfterMaterialRetention() throws Exception {
        long old = clock.millis() - Duration.ofDays(15).toMillis();
        put("material", "202", object().put("kind","POST").put("authorId","7").put("username","previous-name")
                .put("publishedAt",old).put("text","Archived source post"));
        when(publisher.createTweet("local-token","Manual",List.of(),"202"))
                .thenThrow(new XPublishClient.XPublishException(504,"Lost response",true));
        var request = object().put("clientRequestId","review").put("commentText","Manual").put("materialId","202");
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42","elidlicom","previous-name",request));
        store.purgeExpiredContent(clock.millis()-Duration.ofDays(5).toMillis());
        assertNull(get("material","202"));
        var row = get("publication","manual-quote-review");
        var verified = new AssistantPublishing(store,null).verifyPublication("42", "manual-quote-review",
                object().put("version",row.path("version").asInt()).put("published",true).put("tweetId","9006"));
        assertEquals("7", verified.path("authorId").asText());
        clock.advanceMinutes(125);
        failed(AutoPublishMode.REPOST);
        assertTrue(assertThrows(XDataProviderException.class, () -> service.retry(TARGET,AutoPublishMode.REPOST)).getMessage().contains("16 小时"));
        clock.advanceMinutes(16 * 60);
        when(publisher.createTweet("local-token","Prepared body",List.of(),TARGET)).thenReturn("9012");
        service.retry(TARGET,AutoPublishMode.REPOST);
        finish();
        verify(publisher).createTweet("local-token","Prepared body",List.of(),TARGET);
    }

    @Test void retentionPreservesUnresolvedSendsAndArchivePatrolReadsACompletedAutoReceipt() {
        long old = clock.millis() - Duration.ofDays(15).toMillis();
        for (String status : List.of("SENDING","UNCERTAIN","RETRY_QUEUED","EVALUATING","FAILED","PUBLISHED")) {
            var row = object().put("sourcePublishedAt",old).put("status",status);
            if ("FAILED".equals(status)) row.put("autoRetryAt",clock.millis()+60_000);
            put("auto_repost",status,row);
        }
        put("auto_repost",TARGET,object().put("sourcePublishedAt",old).put("publishedAt",old).put("status","PUBLISHED").put("publishedTweetId","9900"));
        put("publication","auto-repost-"+TARGET,object().put("source","AUTO_QUOTE_REPOST").put("materialId",TARGET)
                .put("status","PUBLISHED").put("publishedAt",old).put("publishedTweetId","9900"));
        store.purgeExpiredContent(clock.millis()-Duration.ofDays(5).toMillis());
        for (String status : List.of("SENDING","UNCERTAIN","RETRY_QUEUED","EVALUATING","FAILED","PUBLISHED"))
            assertNotNull(get("auto_repost",status), status);
        assertNull(get("auto_repost",TARGET));
        put("source","7",object().put("authorId","7").put("username","source").put("status","ACTIVE").put("autoRepostEnabled",true));
        put("material",TARGET,object().put("kind","POST").put("authorId","7").put("username","source").put("publishedAt",old)
                .put("text","Archived source ready for the patrol review with enough detail."));
        service.runOwnerIdleRepostPatrol();
        assertEquals("SKIPPED_DUPLICATE",get("auto_repost",TARGET).path("status").asText());
        verifyNoInteractions(judge,publisher);
        put("material","303",object().put("kind","POST").put("authorId","7").put("username","source").put("publishedAt",old)
                .put("text","A separate archived source remains eligible for the same patrol."));
        when(judge.evaluate(any())).thenReturn(new PiAgentViralJudge.ViralJudgment(true,95,true,95,95,95,95,"GENERAL","","","Approved"));
        when(publisher.createTweet("local-token","Approved",List.of(),"303")).thenReturn("9013");
        assertEquals("303",service.runOwnerIdleRepostPatrol().path("materialId").asText());
        verify(publisher).createTweet("local-token","Approved",List.of(),"303");
    }

    @Test void manualQuoteWithLostResponseCannotReplayUnderADifferentKey() {
        ObjectNode request = object().put("materialId", TARGET).put("commentText", "Comment").put("clientRequestId", "first");
        when(publisher.createTweet("local-token", "Comment", List.of(), TARGET))
                .thenThrow(new XPublishClient.XPublishException(504, "Lost response", true));
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42", "elidlicom", "source", request));
        request.put("clientRequestId", "second");
        assertEquals(409, assertThrows(XDataProviderException.class,
                () -> service.publishManualQuote("42", "elidlicom", "source", request)).getStatusCode());
        verify(publisher).createTweet("local-token", "Comment", List.of(), TARGET);
    }

    @ParameterizedTest @EnumSource(AutoPublishMode.class)
    void automaticSelectionSkipsMaterialWithAnUnconfirmedManualReceipt(AutoPublishMode mode) {
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        put("source", "7", object().put("authorId", "7").put("username", "source").put("status", "ACTIVE")
                .put("autoRepostEnabled", !rewrite).put("autoRewriteEnabled", rewrite));
        put("material", TARGET, object().put("authorId", "7").put("username", "source").put("kind", "POST")
                .put("text", "A verifiable observation about how ordinary writing decisions affect a published article.")
                .put("publishedAt", clock.millis()).put("views", 12000).put("likes", 90));
        when(judge.evaluate(any())).thenReturn(new PiAgentViralJudge.ViralJudgment(true, 95, true,
                95, 95, 95, 95, "GENERAL", "", "", "Automatic comment"));
        when(judge.evaluateAndWriteRewrite(any())).thenReturn(
                new PiAgentViralJudge.RewriteJudgment(true, 95, "", "", "", "Automatic rewrite"));
        when(publisher.createTweet(eq("local-token"), anyString(), eq(List.of()), nullable(String.class))).thenAnswer(call -> {
            if ("Manual comment".equals(call.getArgument(1))) throw new XPublishClient.XPublishException(504, "Lost response", true);
            return "9009";
        });
        var request = object().put("clientRequestId", "manual-lost").put("materialId", TARGET).put("commentText", "Manual comment");
        assertThrows(XPublishClient.XPublishException.class, () -> service.publishManualQuote("42", "elidlicom", "source", request));
        clock.advanceMinutes(125);
        if (rewrite) service.processAuthorRewriteOnce(get("source", "7"), "42", "elidlicom");
        else service.processAuthorBatch(get("source", "7"), List.of(get("material", TARGET)));
        verify(publisher).createTweet("local-token", "Manual comment", List.of(), TARGET);
        verifyNoMoreInteractions(publisher);
        verifyNoInteractions(judge);
    }

    @ParameterizedTest @EnumSource(AutoPublishMode.class)
    void manualQuoteCannotRaceAutomaticDispatch(AutoPublishMode mode) throws Exception {
        failed(mode);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String quoteId = mode == AutoPublishMode.REWRITE ? null : TARGET;
        when(publisher.createTweet("local-token", "Prepared body", List.of(), quoteId)).thenAnswer(call -> {
            sending.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return "9010";
        });
        when(publisher.createTweet("local-token", "Manual comment", List.of(), TARGET)).thenReturn("9011");
        service.retry(TARGET, mode);
        try {
            assertTrue(sending.await(10, TimeUnit.SECONDS));
            var request = object().put("clientRequestId", "manual-concurrent").put("materialId", TARGET).put("commentText", "Manual comment");
            assertEquals(409, assertThrows(XDataProviderException.class,
                    () -> service.publishManualQuote("42", "elidlicom", "source", request)).getStatusCode());
        } finally {
            release.countDown();
            finish();
        }
        verify(publisher).createTweet("local-token", "Prepared body", List.of(), quoteId);
        verifyNoMoreInteractions(publisher);
    }

    @Test void replayAfterQuoteFallbackAndLocalWriteFailureUsesTheOneAcceptedHttpPost() throws Exception {
        var mapper = new ObjectMapper();
        var bodies = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2/tweets", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody());
            bodies.add(body);
            boolean nativeQuote = body.has("quote_tweet_id");
            var result = nativeQuote ? object().put("detail", "You can only reply to or quote posts where you are mentioned or are the author.")
                    : object().set("data", object().put("id", "9006"));
            byte[] response = mapper.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
            if (!nativeQuote) store.failures = 1;
            exchange.sendResponseHeaders(nativeQuote ? 403 : 201, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        var properties = new XOpsProperties();
        properties.setOfficialBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        properties.setDiskCacheDir(data.resolve("api-budget").toString());
        var pool = new SharedApiExecutor(properties);
        var liveClient = new XPublishClient(properties, mapper, pool);
        var manual = new AutoRepostService(store, null, judge, auth, liveClient, clock);
        try {
            put("material", TARGET, object().put("authorId", "7").put("username", "source"));
            var request = object().put("clientRequestId", "manual-fallback").put("materialId", TARGET).put("commentText", "Comment");
            ObjectNode first = manual.publishManualQuote("42", "elidlicom", "source", request);
            ObjectNode replay = manual.publishManualQuote("42", "elidlicom", "source", request.put("clientRequestId", "after-reload"));
            assertEquals("9006", replay.path("publishedTweetId").asText());
            assertEquals("QUOTE_LINK", first.path("publicationMode").asText());
            assertFalse(first.hasNonNull("quoteTweetId"));
            assertEquals(TARGET, first.path("requestedQuoteTweetId").asText());
            assertEquals(2, bodies.size());
            assertEquals(TARGET, bodies.get(0).path("quote_tweet_id").asText());
            assertFalse(bodies.get(1).has("quote_tweet_id"));
            assertEquals("Comment\n\nhttps://x.com/source/status/101", bodies.get(1).path("text").asText());
            assertEquals(bodies.get(1).path("text").asText(), replay.path("text").asText());
        } finally {
            manual.shutdown();
            pool.shutdown();
            server.stop(0);
        }
    }
}
