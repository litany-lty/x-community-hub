package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class FxTwitterIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final XUserSummary SELF = new XUserSummary("100", "self", "Self", "", "", false, 0, 0, 20, "");
    @TempDir Path temp;

    @Test void freeProfileSearchAndMetricRefreshPersistRealZeroWithoutAnyPaidRead() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant oldSync = now.minusSeconds(7200);
        Instant cachedAt = oldSync.minusSeconds(3600);
        var old = store(oldSync, List.of(cached("101", cachedAt)));
        AtomicInteger paidReads = new AtomicInteger();
        List<String> freePaths = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            paidReads.incrementAndGet();
            return new Reply(500, "{}");
        }); var free = new MockServer(uri -> {
            freePaths.add(uri.getPath());
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/status/101")) {
                ObjectNode zero = freeStatus("101", cachedAt);
                for (String field : List.of("views", "likes", "replies", "reposts", "quotes", "bookmarks")) zero.put(field, 0);
                return detail(zero);
            }
            if (uri.getPath().equals("/2/search")) {
                return query(uri).getOrDefault("q", "").startsWith("from:self ")
                        ? search("", freeStatus("103", now.minusSeconds(30))) : search("");
            }
            return new Reply(404, "{\"code\":404}");
        }); var f = new ProviderFixture(paid, free)) {
            long observedAfter = Instant.now().getEpochSecond();
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(paidReads.get()).isZero();
            assertThat(freePaths).contains("/2/profile/self", "/2/search", "/2/status/101");
            assertThat(result.store().profile().id()).isEqualTo("100");
            assertThat(result.store().tweets()).extracting(AccountStore.StoredTweet::id).containsExactlyInAnyOrder("101", "103");
            var refreshed = tweet(result.store(), "101");
            // AccountStore compacts a fully observed all-zero array to null; availability still proves real zero.
            assertThat(refreshed.stats()).isNull();
            assertThat(refreshed.unavailableMetrics()).isEmpty();
            assertThat(refreshed.metricsFetchedAt()).isGreaterThanOrEqualTo(observedAfter);
            assertThat(result.dailyDataComplete()).isTrue();
        }
    }

    @Test void missingFreeMetricKeepsPreviousValueAndObservationTimeAndKnownThreadRoot() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant oldSync = now.minusSeconds(7200);
        Instant created = oldSync.minusSeconds(3600);
        RawTweet reply = new RawTweet("102", "cached reply", created, TweetKind.POST, null, null, List.of(),
                false, true, 80, 8, 2, 3, 9, true, "900", "901", Set.of());
        var old = store(oldSync, List.of(reply));
        AtomicInteger paidReads = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidReads.incrementAndGet();
            return new Reply(500, "{}");
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/search")) return search("");
            if (uri.getPath().equals("/2/status/102")) {
                ObjectNode row = freeStatus("102", created);
                row.put("text", "fresh reply");
                row.put("likes", 0);
                row.putNull("bookmarks");
                row.set("replying_to", MAPPER.createObjectNode().put("screen_name", "self").put("status", "901"));
                return detail(row);
            }
            return new Reply(404, "{\"code\":404}");
        }); var f = new ProviderFixture(paid, free)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            var refreshed = tweet(result.store(), "102");
            assertThat(paidReads.get()).isZero();
            assertThat(refreshed.stats()[1]).isZero();
            assertThat(refreshed.stats()[4]).isEqualTo(9);
            assertThat(refreshed.metricsFetchedAt()).isEqualTo(tweet(old, "102").metricsFetchedAt());
            assertThat(refreshed.conversationId()).isEqualTo("900");
            assertThat(refreshed.inReplyToId()).isEqualTo("901");
            assertThat(result.incompleteDailyMetricUpdates()).isEqualTo(1);
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "wrong-id"})
    void unavailableOrWrongFreeDetailAndFailedPaidSupplementNeverDeleteCachedTweet(String mode) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant oldSync = now.minusSeconds(7200);
        Instant created = oldSync.minusSeconds(3600);
        var old = store(oldSync, List.of(cached("101", created)));
        List<URI> paidRequests = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            paidRequests.add(uri);
            return new Reply(500, "{}");
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/search")) return search("");
            if (uri.getPath().equals("/2/status/101")) {
                return mode.equals("unavailable") ? new Reply(404, "{\"code\":404,\"status\":null}")
                        : detail(freeStatus("999", created));
            }
            return new Reply(404, "{\"code\":404}");
        }); var f = new ProviderFixture(paid, free)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(paidRequests).anySatisfy(uri -> {
                assertThat(uri.getPath()).isEqualTo("/twitter/tweets");
                assertThat(query(uri).get("tweet_ids")).isEqualTo("101");
            });
            assertThat(result.store().tweets()).extracting(AccountStore.StoredTweet::id).containsExactly("101");
            assertThat(tweet(result.store(), "101").stats()).containsExactly(80, 8, 2, 3, 9);
            assertThat(tweet(result.store(), "101").metricsFetchedAt()).isEqualTo(tweet(old, "101").metricsFetchedAt());
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"repost", "foreign-author", "same-handle-wrong-id", "out-of-order"})
    void invalidFreeSearchPageRestartsPaidWindowWithoutLeakingFreeCursor(String mode) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // Keep the remaining span below five minutes so this exercises cursor continuation, not adaptive shards.
        var old = store(now.minusSeconds(120), List.of());
        AtomicInteger ownFreePages = new AtomicInteger();
        List<URI> paidRequests = Collections.synchronizedList(new ArrayList<>());
        List<String> freeCursors = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            paidRequests.add(uri);
            if (uri.getPath().equals("/twitter/tweet/advanced_search")) {
                return paidPage(paidStatus("201", now.minusSeconds(60)), paidStatus("202", now.minusSeconds(90)));
            }
            return new Reply(500, "{}");
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/search")) {
                Map<String, String> params = query(uri);
                if (!params.getOrDefault("q", "").startsWith("from:self ")) return search("");
                freeCursors.add(params.getOrDefault("cursor", ""));
                if (ownFreePages.incrementAndGet() == 1) return search("free-page-two", freeStatus("201", now.minusSeconds(60)));
                if (mode.equals("out-of-order")) {
                    return search("", freeStatus("203", now.minusSeconds(95)), freeStatus("204", now.minusSeconds(80)));
                }
                ObjectNode invalid = freeStatus("999", now.minusSeconds(90));
                invalid.set("author", freeUser("200", mode.equals("same-handle-wrong-id") ? "self" : "peer"));
                if (mode.equals("repost")) invalid.set("reposted_by", freeUser("100", "self"));
                return search("", invalid);
            }
            return new Reply(404, "{\"code\":404}");
        }); var f = new ProviderFixture(paid, free)) {
            var result = f.provider.syncStoreByHours("self", old, 24, true, true, null, "100");
            assertThat(ownFreePages.get()).isEqualTo(2);
            assertThat(freeCursors).containsExactly("", "free-page-two");
            assertThat(paidRequests).singleElement().satisfies(uri -> {
                assertThat(uri.getPath()).isEqualTo("/twitter/tweet/advanced_search");
                assertThat(query(uri)).doesNotContainKey("cursor");
                assertThat(query(uri).get("query")).contains("from:self", "since_time:", "until_time:");
            });
            assertThat(result.store().tweets()).extracting(AccountStore.StoredTweet::id).containsExactlyInAnyOrder("201", "202");
            assertThat(AccountStore.missingRanges(result.store().tweetCoverage(), now.minusSeconds(120).getEpochSecond(),
                    result.store().syncedAt())).isEmpty();
        }
    }

    @Test void apiKeyVerificationCannotSucceedThroughFreeProfile() throws Exception {
        AtomicInteger paidCalls = new AtomicInteger();
        AtomicInteger freeCalls = new AtomicInteger();
        try (var paid = new MockServer(uri -> {
            paidCalls.incrementAndGet();
            return new Reply(401, "{\"status\":\"error\"}");
        }); var free = new MockServer(uri -> {
            freeCalls.incrementAndGet();
            return new Reply(200, MAPPER.createObjectNode().put("code", 200).set("user", freeUser("100", "X")).toString());
        }); var f = new ProviderFixture(paid, free)) {
            assertThatThrownBy(() -> f.provider.verifyApiKey("invalid-key"))
                    .isInstanceOf(XDataProviderException.class)
                    .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(401));
            assertThat(paidCalls.get()).isEqualTo(1);
            assertThat(freeCalls.get()).isZero();
        }
    }

    private AccountStore.StoredAccount store(Instant anchor, List<RawTweet> tweets) {
        var coverage = List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()});
        return AccountStore.merge(null, SELF, anchor, tweets, List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
    }

    private RawTweet cached(String id, Instant at) {
        return new RawTweet(id, "cached " + id, at, TweetKind.POST, null, null, List.of(), false, false, 80, 8, 2, 3, 9);
    }

    private AccountStore.StoredTweet tweet(AccountStore.StoredAccount account, String id) {
        return account.tweets().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    private static ObjectNode freeUser(String id, String handle) {
        ObjectNode user = MAPPER.createObjectNode().put("type", "profile").put("id", id).put("screen_name", handle)
                .put("name", handle).put("followers", 0).put("following", 0).put("statuses", 20)
                .put("description", "").put("avatar_url", "").put("banner_url", "")
                .put("joined", "Wed Mar 22 21:50:14 +0000 2006").put("protected", false);
        user.set("verification", MAPPER.createObjectNode().put("verified", false));
        return user;
    }

    private static Reply freeProfile() {
        return new Reply(200, MAPPER.createObjectNode().put("code", 200).set("user", freeUser("100", "self")).toString());
    }

    private static ObjectNode freeStatus(String id, Instant at) {
        ObjectNode tweet = MAPPER.createObjectNode().put("type", "status").put("id", id).put("text", "text " + id)
                .put("url", "https://x.com/self/status/" + id).put("created_at", at.toString())
                .put("created_timestamp", at.getEpochSecond()).put("views", 100).put("likes", 7)
                .put("replies", 2).put("reposts", 3).put("quotes", 1).put("bookmarks", 5)
                .putNull("replying_to").putNull("reposted_by").putNull("media");
        tweet.set("author", freeUser("100", "self"));
        tweet.set("raw_text", MAPPER.createObjectNode().put("text", "text " + id).set("facets", MAPPER.createArrayNode()));
        return tweet;
    }

    private static Reply detail(ObjectNode tweet) {
        return new Reply(200, MAPPER.createObjectNode().put("code", 200).set("status", tweet).toString());
    }

    private static Reply search(String cursor, ObjectNode... tweets) {
        ObjectNode root = MAPPER.createObjectNode().put("code", 200);
        ArrayNode rows = root.putArray("results");
        for (ObjectNode tweet : tweets) rows.add(tweet);
        root.set("cursor", MAPPER.createObjectNode().put("bottom", cursor).put("top", ""));
        return new Reply(200, root.toString());
    }

    private static ObjectNode paidStatus(String id, Instant at) {
        ObjectNode row = MAPPER.createObjectNode().put("id", id).put("text", "paid text " + id).put("createdAt", at.toString())
                .put("viewCount", 100).put("likeCount", 7).put("replyCount", 2).put("retweetCount", 3)
                .put("quoteCount", 1).put("bookmarkCount", 5);
        row.set("author", MAPPER.createObjectNode().put("id", "100").put("userName", "self"));
        return row;
    }

    private static Reply paidPage(ObjectNode... tweets) {
        ObjectNode root = MAPPER.createObjectNode().put("has_next_page", false);
        for (ObjectNode tweet : tweets) root.withArray("tweets").add(tweet);
        if (tweets.length == 0) root.putArray("tweets");
        return new Reply(200, root.toString());
    }

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

    private final class ProviderFixture implements AutoCloseable {
        final SharedApiExecutor pool;
        final TwitterApiIoProvider provider;

        ProviderFixture(MockServer paid, MockServer free) {
            XOpsProperties properties = new XOpsProperties();
            properties.setTwitterapiIoBaseUrl(paid.baseUrl());
            properties.setTwitterapiIoKey("local-fixture");
            properties.setFxtwitterEnabled(true);
            properties.setFxtwitterBaseUrl(free.baseUrl() + "/2");
            properties.setDiskCacheDir(temp.toString());
            // All flows, including the free-to-paid fallback, must work without nested worker waits.
            properties.setApiPoolCoreSize(1);
            properties.setApiPoolMaxSize(1);
            pool = new SharedApiExecutor(properties);
            provider = new TwitterApiIoProvider(properties, MAPPER, pool);
        }

        @Override public void close() { pool.shutdown(); }
    }
}
