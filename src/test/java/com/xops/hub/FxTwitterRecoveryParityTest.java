package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.FxTwitterClient;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Third cross-check: recovery must preserve verified data and paid-call admission. */
@Timeout(15)
class FxTwitterRecoveryParityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final XUserSummary SELF = new XUserSummary("100", "self", "Self", "", "", false, 0, 0, 20, "");
    @TempDir Path temp;

    @Test void repeatedCursorAcrossSeveralPagesStopsWithoutClaimingUnseenCoverage() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant anchor = now.minusSeconds(120);
        var old = store(anchor, List.of());
        List<String> ownCursors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger paidReads = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidReads.incrementAndGet();
            return new Reply(500, "{}");
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return profile();
            if (uri.getPath().equals("/2/search")) {
                var params = query(uri);
                if (!params.getOrDefault("q", "").startsWith("from:self ")) return search("");
                String cursor = params.getOrDefault("cursor", "");
                ownCursors.add(cursor);
                return switch (cursor) {
                    case "" -> search("A", freeStatus("201", now.minusSeconds(30)));
                    case "A" -> search("B", freeStatus("202", now.minusSeconds(60)));
                    // An invalid cursor page reaching the lower bound must not seal the coverage gap.
                    default -> search("A", freeStatus("203", anchor));
                };
            }
            return unavailable();
        }); var f = new Fixture(paid, free, false)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(ownCursors).containsExactly("", "A", "B");
            assertThat(paidReads.get()).isZero();
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
            assertThat(result.store().tweets()).extracting(AccountStore.StoredTweet::id)
                    .containsExactlyInAnyOrder("201", "202", "203");
            assertThat(AccountStore.missingRanges(result.store().tweetCoverage(),
                    anchor.getEpochSecond(), result.store().syncedAt())).isNotEmpty();
        }
    }

    @Test void successfulPaidSupplementConfirmsMissingTweetAndMatchesOriginalPaidDeletion() throws Exception {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = anchor.minusSeconds(3600);
        var old = store(anchor, List.of(cached("101", created), cached("102", created.minusSeconds(1))));
        List<URI> paidReads = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger freeReads = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidReads.add(uri);
            if (uri.getPath().equals("/twitter/user/info")) {
                ObjectNode user = MAPPER.createObjectNode().put("id", "100").put("userName", "self")
                        .put("name", "Self").put("followers", 0).put("following", 0).put("statusesCount", 20).put("isBlueVerified", false);
                return new Reply(200, MAPPER.createObjectNode().set("data", user).toString());
            }
            if (uri.getPath().equals("/twitter/tweets") && query(uri).get("tweet_ids").contains("101")) {
                // The pure-paid control sees the same surviving fact and the same missing ID.
                return paidRows(paidStatus("101", created).put("text", "text 101").put("conversationId", "101"));
            }
            return paidRows();
        }); var free = new MockServer(uri -> { freeReads.incrementAndGet(); return regularFree(uri, created); });
             var f = new Fixture(paid, free, false)) {
            long observationStart = Instant.now().getEpochSecond();
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(paidReads).singleElement().satisfies(uri -> {
                assertThat(uri.getPath()).isEqualTo("/twitter/tweets");
                assertThat(query(uri).get("tweet_ids")).isEqualTo("102");
            });
            assertThat(result.store().tweets()).extracting(AccountStore.StoredTweet::id)
                    .containsExactly("101");
            assertThat(tweet(result.store(), "101").stats()).containsExactly(100, 7, 2, 4, 5);
            assertThat(result.incompleteDailyMetricUpdates()).isZero();
            assertThat(result.dailyMetricBatchFailed()).isFalse();
            assertThat(result.degraded()).isFalse();
            assertThat(result.dailyDataComplete()).isTrue();
            int freeReadsBeforeControl = freeReads.get();
            try (var original = new Fixture(paid, free, false)) {
                original.properties.setFxtwitterEnabled(false);
                var control = original.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
                assertThat(freeReads.get()).isEqualTo(freeReadsBeforeControl);
                assertThat(control.degraded()).isFalse();
                assertThat(control.dailyDataComplete()).isTrue();
                assertThat(tweet(result.store(), "101").metricsFetchedAt()).isGreaterThanOrEqualTo(observationStart);
                assertThat(tweet(control.store(), "101").metricsFetchedAt()).isGreaterThanOrEqualTo(observationStart);
                assertThat(normalizeObservedTimes(result.store().tweets(), observationStart))
                        .isEqualTo(normalizeObservedTimes(control.store().tweets(), observationStart));
            }
        }
    }

    @Test void malformedPaidSupplementWithoutTweetArrayKeepsUnconfirmedCachedTweet() throws Exception {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = anchor.minusSeconds(3600);
        var old = store(anchor, List.of(cached("101", created), cached("102", created.minusSeconds(1))));
        List<URI> paidReads = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            paidReads.add(uri);
            return new Reply(200, "{\"status\":\"success\"}");
        }); var free = new MockServer(uri -> regularFree(uri, created));
             var f = new Fixture(paid, free, false)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(paidReads).singleElement().satisfies(uri -> {
                assertThat(uri.getPath()).isEqualTo("/twitter/tweets");
                assertThat(query(uri).get("tweet_ids")).isEqualTo("102");
            });
            assertThat(result.store().tweets()).extracting(AccountStore.StoredTweet::id)
                    .containsExactlyInAnyOrder("101", "102");
            assertThat(tweet(result.store(), "101").stats()).containsExactly(100, 7, 2, 4, 5);
            assertUnchanged(tweet(old, "102"), tweet(result.store(), "102"));
            assertThat(result.incompleteDailyMetricUpdates()).isEqualTo(1);
            assertThat(result.dailyMetricBatchFailed()).isTrue();
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    @Test void foreignAuthorInPaidSupplementCannotOverwriteCachedOwnTweet() throws Exception {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = anchor.minusSeconds(3600);
        var old = store(anchor, List.of(cached("101", created), cached("102", created.minusSeconds(1))));
        AtomicInteger paidReads = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidReads.incrementAndGet();
            ObjectNode foreign = paidStatus("102", created.minusSeconds(1));
            foreign.set("author", MAPPER.createObjectNode().put("id", "200").put("userName", "peer"));
            foreign.put("text", "incorrect author data").put("likeCount", 999);
            return paidRows(foreign);
        }); var free = new MockServer(uri -> regularFree(uri, created));
             var f = new Fixture(paid, free, false)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(paidReads.get()).isEqualTo(1);
            assertThat(tweet(result.store(), "101").stats()).containsExactly(100, 7, 2, 4, 5);
            assertUnchanged(tweet(old, "102"), tweet(result.store(), "102"));
            assertThat(result.store().profile().id()).isEqualTo("100");
            assertThat(result.incompleteDailyMetricUpdates()).isEqualTo(1);
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    @Test void deadlineKeepsSuccessfulRowsAndSeparatesStartedFromNeverSentIds() throws Exception {
        Instant created = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        List<String> freeReads = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger paidReads = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidReads.incrementAndGet();
            return paidRows();
        }); var free = new MockServer(uri -> {
            String id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            freeReads.add(id);
            if (id.equals("302")) {
                secondStarted.countDown();
                awaitGate(releaseSecond);
            }
            return detail(freeStatus(id, created));
        }); var f = new Fixture(paid, free, false)) {
            try {
                AtomicBoolean started = new AtomicBoolean();
                CompletableFuture<JsonNode> pending = new FxTwitterClient(f.properties, MAPPER, f.pool)
                        .fetchAsync(paid.baseUrl() + "/twitter/tweets?tweet_ids=301,302,303,304,305",
                                Instant.now().plusMillis(1200), started);
                assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
                JsonNode result = pending.get(3, TimeUnit.SECONDS);
                assertThat(started.get()).isTrue();
                assertThat(ids(result.path("tweets"), "id")).containsExactly("301");
                assertThat(ids(result.path("__attempted_ids"), null)).containsExactly("301", "302");
                assertThat(ids(result.path("__incomplete_ids"), null)).containsExactly("302", "303", "304", "305");
                releaseSecond.countDown();
                f.drainWorker();
                assertThat(freeReads).containsExactly("301", "302");
                assertThat(paidReads.get()).isZero();
            } finally {
                releaseSecond.countDown();
            }
        }
    }

    @Test void cancellingEitherOuterReadPreventsLatePaidFallback() throws Exception {
        // Exercise the two independent production compositions: ordinary read and supplemental batch.
        for (String kind : List.of("profile", "metrics")) {
            CountDownLatch freeStarted = new CountDownLatch(1);
            CountDownLatch releaseFree = new CountDownLatch(1);
            AtomicInteger paidReads = new AtomicInteger();
            try (var paid = new MockServer(uri -> {
                paidReads.incrementAndGet();
                return paidRows();
            }); var free = new MockServer(uri -> {
                freeStarted.countDown();
                awaitGate(releaseFree);
                return unavailable();
            }); var f = new Fixture(paid, free, false)) {
                try {
                    CompletableFuture<JsonNode> pending = startRead(f, paid, kind);
                    assertThat(freeStarted.await(2, TimeUnit.SECONDS)).as(kind + " free request started").isTrue();
                    assertThat(pending.cancel(true)).isTrue();
                    releaseFree.countDown();
                    // A single worker and two barriers also drain a fallback queued by free completion.
                    f.drainWorker();
                    f.drainWorker();
                    assertThat(pending.isCancelled()).isTrue();
                    assertThat(paidReads.get()).as(kind + " must not bill after cancellation").isZero();
                } finally {
                    releaseFree.countDown();
                }
            }
        }
    }

    @Test void cancellingAfterFreeFailureRemovesAlreadyQueuedPaidFallback() throws Exception {
        for (String kind : List.of("profile", "metrics")) {
            CountDownLatch freeStarted = new CountDownLatch(1);
            CountDownLatch releaseFree = new CountDownLatch(1);
            CountDownLatch workerHeld = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            AtomicInteger paidReads = new AtomicInteger();
            try (var paid = new MockServer(uri -> {
                paidReads.incrementAndGet();
                return paidRows();
            }); var free = new MockServer(uri -> {
                freeStarted.countDown();
                awaitGate(releaseFree);
                return unavailable();
            }); var f = new Fixture(paid, free, false)) {
                try {
                    CompletableFuture<JsonNode> pending = startRead(f, paid, kind);
                    assertThat(freeStarted.await(2, TimeUnit.SECONDS)).isTrue();
                    // FIFO blocker is queued before the failed free read schedules its paid fallback.
                    CompletableFuture<Boolean> blocker = f.pool.submitAsync(() -> {
                        workerHeld.countDown();
                        awaitGate(releaseWorker);
                        return true;
                    });
                    releaseFree.countDown();
                    assertThat(workerHeld.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(pending.isDone()).as(kind + " fallback is queued behind blocker").isFalse();
                    assertThat(pending.cancel(true)).isTrue();
                    releaseWorker.countDown();
                    assertThat(blocker.get(3, TimeUnit.SECONDS)).isTrue();
                    f.drainWorker();
                    assertThat(paidReads.get()).as(kind + " queued fallback must be removed on cancel").isZero();
                    assertThat(f.guard.snapshotMetrics()).containsEntry("localMockCalls", 0L);
                } finally {
                    releaseFree.countDown();
                    releaseWorker.countDown();
                }
            }
        }
    }

    @Test void exhaustedExistingBudgetBlocksPaidSupplementAndPreservesFreeProgress() throws Exception {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = anchor.minusSeconds(3600);
        var old = store(anchor, List.of(cached("101", created), cached("102", created.minusSeconds(1))));
        AtomicInteger paidReads = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidReads.incrementAndGet();
            return paidRows(paidStatus("102", created.minusSeconds(1)));
        }); var free = new MockServer(uri -> regularFree(uri, created));
             var f = new Fixture(paid, free, true)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(paidReads.get()).isZero();
            assertThat(f.guard.snapshotMetrics()).containsEntry("hourlyUsed", 0L).containsEntry("budgetRejectedCalls", 1L);
            assertThat(tweet(result.store(), "101").stats()).containsExactly(100, 7, 2, 4, 5);
            assertUnchanged(tweet(old, "102"), tweet(result.store(), "102"));
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyMetricBatchFailed()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    private static CompletableFuture<JsonNode> startRead(Fixture fixture, MockServer paid, String kind) {
        Object pacer = ReflectionTestUtils.invokeMethod(fixture.provider, "pacerFor", "local-fixture");
        return kind.equals("profile")
                ? ReflectionTestUtils.invokeMethod(fixture.provider, "getJsonWithRetryAsync",
                        paid.baseUrl() + "/twitter/user/info?userName=self", "local-fixture", pacer)
                : ReflectionTestUtils.invokeMethod(fixture.provider, "getJsonOnceBeforeAsync",
                        paid.baseUrl() + "/twitter/tweets?tweet_ids=101", "local-fixture", pacer,
                        Instant.now().plusSeconds(5), new AtomicBoolean(), 5000L, true, new AtomicBoolean());
    }

    private static void assertUnchanged(AccountStore.StoredTweet before, AccountStore.StoredTweet after) {
        assertThat(after.text()).isEqualTo(before.text());
        assertThat(after.stats()).containsExactly(before.stats());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.metricsFetchedAt()).isEqualTo(before.metricsFetchedAt());
    }

    private static JsonNode normalizeObservedTimes(List<AccountStore.StoredTweet> tweets, long observationStart) {
        JsonNode rows = MAPPER.valueToTree(tweets);
        for (JsonNode row : rows) {
            for (String field : List.of("mt", "ma")) {
                if (row.path(field).isIntegralNumber() && row.path(field).asLong() >= observationStart) {
                    ((ObjectNode) row).put(field, observationStart);
                }
            }
        }
        return rows;
    }

    private static List<String> ids(JsonNode rows, String field) {
        List<String> ids = new ArrayList<>();
        for (JsonNode row : rows) ids.add(field == null ? row.asText() : row.path(field).asText());
        return ids;
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Reply regularFree(URI uri, Instant created) {
        return switch (uri.getPath()) {
            case "/2/profile/self" -> profile();
            case "/2/search" -> search("");
            case "/2/status/101" -> detail(freeStatus("101", created));
            default -> unavailable();
        };
    }

    private static AccountStore.StoredAccount store(Instant anchor, List<RawTweet> tweets) {
        var coverage = List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()});
        return AccountStore.merge(null, SELF, anchor, tweets, List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
    }

    private static RawTweet cached(String id, Instant at) {
        return new RawTweet(id, "cached " + id, at, TweetKind.POST, null, null, List.of(), false, false, 80, 8, 2, 3, 9);
    }

    private static AccountStore.StoredTweet tweet(AccountStore.StoredAccount account, String id) {
        return account.tweets().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    private static ObjectNode freeUser() {
        ObjectNode user = MAPPER.createObjectNode().put("type", "profile").put("id", "100").put("screen_name", "self")
                .put("name", "Self").put("followers", 0).put("following", 0).put("statuses", 20)
                .put("description", "").put("avatar_url", "").put("banner_url", "")
                .put("joined", "Wed Mar 22 21:50:14 +0000 2006").put("protected", false);
        user.set("verification", MAPPER.createObjectNode().put("verified", false));
        return user;
    }

    private static Reply profile() {
        return new Reply(200, MAPPER.createObjectNode().put("code", 200).set("user", freeUser()).toString());
    }

    private static ObjectNode freeStatus(String id, Instant created) {
        ObjectNode row = MAPPER.createObjectNode().put("type", "status").put("id", id).put("text", "text " + id)
                .put("url", "https://x.com/self/status/" + id).put("created_at", created.toString())
                .put("created_timestamp", created.getEpochSecond()).put("views", 100).put("likes", 7)
                .put("replies", 2).put("reposts", 3).put("quotes", 1).put("bookmarks", 5)
                .putNull("replying_to").putNull("reposted_by").putNull("media");
        row.set("author", freeUser());
        row.set("raw_text", MAPPER.createObjectNode().put("text", "text " + id).set("facets", MAPPER.createArrayNode()));
        return row;
    }

    private static Reply detail(ObjectNode status) {
        return new Reply(200, MAPPER.createObjectNode().put("code", 200).set("status", status).toString());
    }

    private static Reply search(String cursor, ObjectNode... statuses) {
        ObjectNode root = MAPPER.createObjectNode().put("code", 200);
        ArrayNode rows = root.putArray("results");
        for (ObjectNode status : statuses) rows.add(status);
        root.set("cursor", MAPPER.createObjectNode().put("bottom", cursor).put("top", ""));
        return new Reply(200, root.toString());
    }

    private static ObjectNode paidStatus(String id, Instant created) {
        ObjectNode row = MAPPER.createObjectNode().put("id", id).put("text", "paid text " + id).put("createdAt", created.toString())
                .put("viewCount", 100).put("likeCount", 7).put("replyCount", 2).put("retweetCount", 3)
                .put("quoteCount", 1).put("bookmarkCount", 5);
        row.set("author", MAPPER.createObjectNode().put("id", "100").put("userName", "self"));
        return row;
    }

    private static Reply paidRows(ObjectNode... statuses) {
        ObjectNode root = MAPPER.createObjectNode().put("has_next_page", false).put("next_cursor", "");
        ArrayNode rows = root.putArray("tweets");
        for (ObjectNode status : statuses) rows.add(status);
        return new Reply(200, root.toString());
    }

    private static Reply unavailable() { return new Reply(500, "{\"code\":500}"); }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&")) {
            String[] pair = parameter.split("=", 2);
            params.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }

    private record Reply(int status, String body) { }

    private static final class MockServer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newCachedThreadPool();

        MockServer(Function<URI, Reply> response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                Reply result = response.apply(exchange.getRequestURI());
                byte[] body = result.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.status(), body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); handlers.shutdownNow(); }
    }

    private final class Fixture implements AutoCloseable {
        final XOpsProperties properties;
        final SharedApiExecutor pool;
        final UpstreamBudgetGuard guard;
        final TwitterApiIoProvider provider;

        Fixture(MockServer paid, MockServer free, boolean exhaustedBudget) {
            properties = new XOpsProperties();
            properties.setTwitterapiIoBaseUrl(paid.baseUrl());
            properties.setTwitterapiIoKey("local-fixture");
            properties.setFxtwitterEnabled(true);
            properties.setFxtwitterBaseUrl(free.baseUrl() + "/2");
            properties.setDiskCacheDir(temp.toString());
            properties.setApiPoolCoreSize(1);
            properties.setApiPoolMaxSize(1);
            if (exhaustedBudget) properties.setHourlyUpstreamApiBudget(0);
            pool = new SharedApiExecutor(properties);
            guard = new UpstreamBudgetGuard(properties, exhaustedBudget);
            provider = new TwitterApiIoProvider(properties, MAPPER, pool, guard);
        }

        void drainWorker() throws Exception { pool.submitAsync(() -> true).get(3, TimeUnit.SECONDS); }
        @Override public void close() { pool.shutdown(); }
    }
}
