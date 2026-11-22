package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxTwitterClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService serverThreads;
    private SharedApiExecutor pool;
    private XOpsProperties properties;
    private FxTwitterClient client;
    private volatile Function<HttpExchange, Reply> handler;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/2/", exchange -> {
            seen.add(new Seen(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("User-Agent"),
                    exchange.getRequestHeaders().getFirst("X-API-Key"), exchange.getRequestHeaders().getFirst("Authorization")));
            Reply reply = handler.apply(exchange);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        properties = new XOpsProperties();
        properties.setFxtwitterEnabled(true);
        properties.setFxtwitterBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        properties.setTwitterapiIoKey("must-not-leave-paid-provider");
        properties.setApiPoolCoreSize(1);
        properties.setApiPoolMaxSize(1);
        properties.setApiPoolQueueCapacity(20);
        properties.setApiQueueTimeoutMs(5000);
        pool = new SharedApiExecutor(properties);
        client = new FxTwitterClient(properties, mapper, pool);
    }

    @AfterEach
    void stop() {
        if (pool != null) pool.shutdown();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
    }

    @Test
    void profilePreservesZeroAndIdentifiesApplicationWithoutPaidCredentials() throws Exception {
        ObjectNode profile = user("100", "Self").put("followers", 0).put("following", 0).put("statuses", 0);
        profile.withObject("verification").put("verified", false);
        handler = exchange -> json(mapper.createObjectNode().put("code", 200).set("user", profile));
        AtomicBoolean started = new AtomicBoolean();
        JsonNode result = fetch("/twitter/user/info?userName=sELF", started);
        assertThat(result.path("_source").asText()).isEqualTo("fxtwitter");
        assertThat(result.path("data").path("id").asText()).isEqualTo("100");
        assertThat(result.path("data").path("userName").asText()).isEqualTo("Self");
        assertThat(result.path("data").path("followers").asInt()).isZero();
        assertThat(result.path("data").path("statusesCount").asInt()).isZero();
        assertThat(result.path("data").path("isBlueVerified").asBoolean()).isFalse();
        assertThat(started).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).userAgent()).contains("x-community-hub/");
        assertThat(seen.get(0).apiKey()).isNull();
        assertThat(seen.get(0).authorization()).isNull();
    }

    @Test
    void profileRejectsWrongAccountAndMissingCountsInsteadOfInventingZero() {
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(user("200", "SomeoneElse"));
        ObjectNode missing = user("100", "Self");
        missing.remove("followers");
        invalid.add(missing);
        invalid.add(user("not-a-user-id", "Self"));
        for (ObjectNode profile : invalid) {
            handler = exchange -> json(mapper.createObjectNode().put("code", 200).set("user", profile));
            assertFailed("/twitter/user/info?userName=Self");
        }
        handler = exchange -> json(mapper.createObjectNode().put("code", 200).set("user", user("200", "Self")));
        assertFailed("/twitter/user/info?userName=id%3A100");
    }

    @Test
    void goldAndGovernmentVerificationDoNotOverwriteBlueVerification() throws Exception {
        for (String type : List.of("organization", "government")) {
            ObjectNode profile = user("100", "Self");
            profile.withObject("verification").put("type", type);
            handler = exchange -> json(mapper.createObjectNode().put("code", 200).set("user", profile));
            JsonNode result = fetch("/twitter/user/info?userName=Self", new AtomicBoolean());
            assertThat(result.path("data").has("isBlueVerified")).isFalse();
            assertThat(result.path("data").has("verified")).isFalse();
        }
    }

    @Test
    void searchKeepsLongTextMediaMentionsAndRelationsAndSeparatesCursors() throws Exception {
        String longText = "正文".repeat(220) + " @Peer";
        ObjectNode first = status("101", 201).put("text", longText).put("is_note_tweet", true);
        first.set("raw_text", mapper.createObjectNode().put("text", longText).set("facets", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("type", "mention").put("original", "Peer").put("id", "200"))));
        first.set("quote", status("98", 190).set("author", user("200", "Peer")));
        first.set("media", mapper.createObjectNode().set("all", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("type", "photo").put("url", "https://pbs.twimg.com/photo.jpg"))));
        ObjectNode page = page("a+b=", first, status("102", 200));
        handler = exchange -> json(page);
        String query = "from:Self since_time:190 until_time:220";
        JsonNode result = fetch(searchUrl(query, ""), new AtomicBoolean());
        JsonNode tweet = result.path("tweets").get(0);
        assertThat(tweet.path("text").asText()).isEqualTo(longText);
        assertThat(tweet.path("quoted_tweet").path("author").path("userName").asText()).isEqualTo("Peer");
        assertThat(tweet.path("entities").path("user_mentions").get(0).path("screen_name").asText()).isEqualTo("Peer");
        assertThat(tweet.path("extendedEntities").path("media")).hasSize(1);
        assertThat(tweet.path("conversationId").asText()).isEqualTo("101");
        assertThat(result.path("next_cursor").asText()).isEqualTo("fx:a+b=");
        assertThat(result.path("has_next_page").asBoolean()).isTrue();
        assertThat(seen.get(0).uri()).contains("feed=latest", "count=30");
        handler = exchange -> json(page("", status("103", 199)));
        fetch(searchUrl(query, "fx:a+b="), new AtomicBoolean());
        assertThat(seen.get(1).uri()).contains("cursor=a%2Bb%3D");
        assertThat(client.supports("https://paid.example" + searchUrl(query, "paid-cursor"))).isFalse();
    }

    @Test
    void searchRejectsPagesThatWouldAdvanceCoverageAcrossWrongOrUnverifiableData() {
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(page("", status("101", 200), status("102", 201)));
        invalid.add(page("", status("101", 220)));
        invalid.add(page("", status("101", 189)));
        invalid.add(page("", status("101", 200).set("author", user("200", "Other"))));
        invalid.add(page("", status("101", 200).set("reposted_by", user("100", "Self"))));
        invalid.add(page("", status("101", 200).set("replying_to", mapper.createObjectNode()
                .put("status", "97").put("screen_name", "Self"))));
        invalid.add(page("next"));
        invalid.add(page("", mapper.createObjectNode().put("type", "tombstone").put("id", "101")));
        invalid.add(page("", status("101", 200).put("created_at", Instant.ofEpochSecond(201).toString())));
        ObjectNode malformedCursor = page("", status("101", 200));
        malformedCursor.withObject("cursor").put("bottom", 12);
        invalid.add(malformedCursor);
        for (ObjectNode page : invalid) {
            handler = exchange -> json(page);
            assertFailed(searchUrl("from:Self since_time:190 until_time:220", ""));
        }
    }

    @Test
    void inboundSearchRequiresActualReplyMentionOrQuoteRelationship() throws Exception {
        String query = "(to:Self OR @Self OR quoted_user_id:100) -from:Self since_time:190 until_time:220";
        ObjectNode unrelated = status("101", 210).set("author", user("200", "Peer"));
        handler = exchange -> json(page("", unrelated));
        assertFailed(searchUrl(query, ""));
        ObjectNode reply = unrelated.deepCopy().set("replying_to", mapper.createObjectNode().put("status", "90").put("screen_name", "Self"));
        ObjectNode mention = status("102", 209).set("author", user("200", "Peer"));
        mention.set("raw_text", mapper.createObjectNode().put("text", "@Self hello").set("facets", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("type", "mention").put("id", "100").put("original", "Self"))));
        ObjectNode quote = status("103", 208).set("author", user("200", "Peer"));
        quote.set("quote", status("91", 190));
        handler = exchange -> json(page("", reply, mention, quote));
        assertFailed(searchUrl(query, ""));
        handler = exchange -> json(page("", mention, quote));
        assertThat(fetch(searchUrl(query, ""), new AtomicBoolean()).path("tweets")).hasSize(2);
    }

    @Test
    void twoNewRepliesShareOneFreeThreadLookupAcrossPagesAndKeepTheirDirectParent() throws Exception {
        ObjectNode first = status("110", 210);
        ObjectNode second = status("109", 209);
        ObjectNode parent = status("98", 198);
        for (var child : List.of(first, second)) {
            child.putObject("replying_to").put("status", "98").put("screen_name", "Self");
        }
        parent.putObject("replying_to").put("status", "90").put("screen_name", "Self");
        ObjectNode thread = mapper.createObjectNode().put("code", 200);
        thread.set("status", parent);
        thread.putArray("thread").add(status("77", 77)).add(status("90", 180)).add(parent);
        handler = exchange -> exchange.getRequestURI().getPath().equals("/2/search")
                ? json(page("", first, second)) : json(thread);

        var roots = SearchReplyRoots.empty();
        String url = "https://paid.example" + searchUrl("from:Self since_time:200 until_time:220", "");
        for (int i = 0; i < 2; i++) {
            JsonNode tweets = client.fetchAsync(url, Instant.now().plusSeconds(5), null, roots).join().path("tweets");
            assertThat(tweets).hasSize(2);
            for (var tweet : tweets) {
                assertThat(tweet.path("conversationId").asText()).isEqualTo("90");
                assertThat(tweet.path("inReplyToId").asText()).isEqualTo("98");
            }
        }
        assertThat(seen).filteredOn(req -> req.uri().equals("/2/thread/98")).hasSize(1);
    }

    @Test
    void truncatedThreadCannotClaimUnconnectedRootAndIsNotRequestedTwiceInSameSync() {
        ObjectNode reply = status("110", 210);
        reply.putObject("replying_to").put("status", "98").put("screen_name", "Self");
        ObjectNode parent = status("98", 198);
        parent.putObject("replying_to").put("status", "95").put("screen_name", "Self");
        ObjectNode truncated = mapper.createObjectNode().put("code", 200);
        truncated.set("status", parent);
        truncated.putArray("thread").add(status("90", 180));
        handler = exchange -> exchange.getRequestURI().getPath().equals("/2/search")
                ? json(page("", reply)) : json(truncated);
        var roots = SearchReplyRoots.empty();
        String url = "https://paid.example" + searchUrl("from:Self since_time:200 until_time:220", "");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.fetchAsync(url, Instant.now().plusSeconds(5), null, roots).join())
                    .hasCauseInstanceOf(XDataProviderException.class);
        }
        assertThat(seen).filteredOn(req -> req.uri().equals("/2/thread/98")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "metrics"})
    void stalledFreeReadLeavesTimeToSendPaidFallback(String kind) throws Exception {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setTwitterapiIoBaseUrl(base);
        properties.setDiskCacheDir("");
        var provider = new TwitterApiIoProvider(properties, mapper, pool);
        Object pacer = ReflectionTestUtils.invokeMethod(provider, "pacerFor", "fixture-key");
        CountDownLatch freeStarted = new CountDownLatch(1), unblockFree = new CountDownLatch(1);
        CountDownLatch paidStarted = new CountDownLatch(1);
        List<String> paidUris = new CopyOnWriteArrayList<>();
        handler = exchange -> {
            freeStarted.countDown();
            try {
                unblockFree.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(503, "{}");
        };
        server.createContext("/twitter/", exchange -> {
            paidUris.add(exchange.getRequestURI().toString());
            paidStarted.countDown();
            byte[] body = "{\"tweets\":[{\"id\":\"101\"}],\"has_next_page\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
            finally { exchange.close(); }
        });
        String path = kind.equals("search") ? searchUrl("from:Self", "fx:page2") : "/twitter/tweets?tweet_ids=101";
        Instant deadline = Instant.now().plusMillis(2700);
        CompletableFuture<JsonNode> result = kind.equals("search")
                ? ReflectionTestUtils.invokeMethod(provider, "getJsonWithRetryAsync", base + path, "fixture-key",
                        pacer, deadline, SearchReplyRoots.empty())
                : ReflectionTestUtils.invokeMethod(provider, "getJsonOnceBeforeAsync", base + path, "fixture-key",
                        pacer, deadline, new AtomicBoolean(), 3000L, true, new AtomicBoolean());
        try {
            assertThat(freeStarted.await(800, TimeUnit.MILLISECONDS)).isTrue();
            long waitMs = java.time.Duration.between(Instant.now(), deadline.minusMillis(350)).toMillis();
            assertThat(paidStarted.await(waitMs, TimeUnit.MILLISECONDS)).as("paid HTTP started with time remaining").isTrue();
            assertThat(result.get(2, TimeUnit.SECONDS).path("tweets").get(0).path("id").asText()).isEqualTo("101");
            assertThat(paidUris).singleElement().satisfies(uri -> {
                assertThat(uri).startsWith(kind.equals("search") ? "/twitter/tweet/advanced_search?" : "/twitter/tweets?");
                assertThat(uri).doesNotContain("cursor=");
            });
        } finally {
            result.cancel(true);
            unblockFree.countDown();
        }
    }

    @Test
    void detailUsesAuthoredTextAndKeepsImmediateParentWithoutGuessingConversationRoot() throws Exception {
        String authored = "图片 https://t.co/original";
        ObjectNode row = status("101", 200).put("text", "图片");
        row.withObject("/raw_text").put("text", authored);
        row.set("replying_to", mapper.createObjectNode().put("status", "97").put("screen_name", "Self"));
        handler = exchange -> json(mapper.createObjectNode().put("code", 200).set("status", row));
        JsonNode detail = fetch("/twitter/tweets?tweet_ids=101", new AtomicBoolean()).path("tweets").get(0);
        assertThat(detail.path("text").asText()).isEqualTo(authored);
        assertThat(detail.path("inReplyToId").asText()).isEqualTo("97");
        assertThat(detail.has("conversationId")).isFalse();

        row.withObject("/raw_text").put("text", "");
        assertThat(fetch("/twitter/tweets?tweet_ids=101", new AtomicBoolean()).path("tweets").get(0).path("text").asText()).isEmpty();
        row.remove("raw_text");
        assertThat(fetch("/twitter/tweets?tweet_ids=101", new AtomicBoolean()).path("tweets").get(0).path("text").asText()).isEqualTo("图片");
    }

    @Test
    void metricBatchKeepsUnknownCountsAndReportsEveryFailedIdWithoutDeletingIt() throws Exception {
        ObjectNode valid = status("101", 200).put("likes", 0).put("bookmarks", 0).putNull("views");
        handler = exchange -> switch (exchange.getRequestURI().getPath()) {
            case "/2/status/101" -> json(mapper.createObjectNode().put("code", 200).set("status", valid));
            case "/2/status/102" -> json(mapper.createObjectNode().put("code", 200).set("status", status("999", 200)));
            case "/2/status/103" -> new Reply(404, "{\"code\":404,\"status\":null}");
            default -> throw new AssertionError("Unexpected request");
        };
        long before = Instant.now().getEpochSecond();
        JsonNode result = fetch("/twitter/tweets?tweet_ids=101,102,103", new AtomicBoolean());
        assertThat(result.path("tweets")).hasSize(1);
        JsonNode tweet = result.path("tweets").get(0);
        assertThat(tweet.path("likeCount").asInt()).isZero();
        assertThat(tweet.path("bookmarkCount").asInt()).isZero();
        assertThat(tweet.has("viewCount")).isFalse();
        assertThat(tweet.path("__observed_at").asLong()).isBetween(before, Instant.now().getEpochSecond());
        assertThat(strings(result.path("__attempted_ids"))).containsExactly("101", "102", "103");
        assertThat(strings(result.path("__incomplete_ids"))).containsExactly("102", "103");
        assertThat(result.has("deleted_ids")).isFalse();
        assertThat(seen).hasSize(3);
    }

    @Test
    void metricReadsNeverBlockTheirOwnExecutorAndAreGroupedAtMostFourAtATime() throws Exception {
        pool.shutdown();
        properties.setApiPoolCoreSize(8);
        properties.setApiPoolMaxSize(8);
        pool = new SharedApiExecutor(properties);
        client = new FxTwitterClient(properties, mapper, pool);
        CountDownLatch firstGroup = new CountDownLatch(4), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        handler = exchange -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            firstGroup.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("First group never released");
                String id = exchange.getRequestURI().getPath().substring("/2/status/".length());
                return json(mapper.createObjectNode().put("code", 200).set("status", status(id, 200)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            } finally {
                active.decrementAndGet();
            }
        };
        CompletableFuture<JsonNode> future = client.fetchAsync("https://paid.example/twitter/tweets?tweet_ids=101,102,103,104,105,106",
                Instant.now().plusSeconds(5), new AtomicBoolean());
        try {
            assertThat(firstGroup.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(seen).hasSize(4);
        } finally {
            release.countDown();
        }
        JsonNode result = future.get(4, TimeUnit.SECONDS);
        assertThat(result.path("tweets")).hasSize(6);
        assertThat(result.path("__incomplete_ids")).isEmpty();
        assertThat(maximum.get()).isLessThanOrEqualTo(4);
    }

    @Test
    void expiredQueuedMetricsAreIncompleteAndNeverStartLateRequests() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> blocker = pool.submitAsync(() -> { occupied.countDown(); release.await(); return null; });
        assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
        handler = exchange -> { throw new AssertionError("Expired request reached HTTP server"); };
        AtomicBoolean started = new AtomicBoolean();
        try {
            JsonNode result = client.fetchAsync("https://paid.example/twitter/tweets?tweet_ids=101,102,103,104,105",
                    Instant.now().plusMillis(100), started).get(2, TimeUnit.SECONDS);
            assertThat(result.path("__attempted_ids")).isEmpty();
            assertThat(strings(result.path("__incomplete_ids"))).containsExactly("101", "102", "103", "104", "105");
            assertThat(started).isFalse();
        } finally {
            release.countDown();
        }
        blocker.get(1, TimeUnit.SECONDS);
        pool.submitAsync(() -> null).get(1, TimeUnit.SECONDS);
        assertThat(seen).isEmpty();
    }

    @Test
    void rejectsHttpBodyAndJsonFailuresAndHonorsDisabledConfiguration() {
        for (Reply reply : List.of(new Reply(500, "{\"code\":200}"), new Reply(200, "{\"code\":404}"),
                new Reply(200, "<html>unavailable</html>"))) {
            handler = exchange -> reply;
            assertFailed("/twitter/user/info?userName=Self");
        }
        properties.setFxtwitterEnabled(false);
        assertThat(client.supports("https://paid.example/twitter/user/info?userName=Self")).isFalse();
        assertThat(client.supports("https://paid.example/twitter/user/followers?userName=Self")).isFalse();
    }

    @Test
    void search503UnavailableOpensSearchCircuitBreakerWhileKeepingProfileAndStatusEnabled() throws Exception {
        String search = searchUrl("from:Self since_time:190 until_time:220", "");
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/2/search")) {
                return new Reply(503, "{\"code\":503,\"message\":\"X/Twitter search is unavailable due to current API limitations.\"}");
            }
            if (exchange.getRequestURI().getPath().equals("/2/profile/Self")) {
                return json(mapper.createObjectNode().put("code", 200).set("user", user("100", "Self")));
            }
            return json(mapper.createObjectNode().put("code", 200).set("status", status("101", 200)));
        };

        assertThat(client.supports("https://paid.example" + search)).isTrue();
        assertFailed(search);
        assertThat(seen).hasSize(1);

        // Circuit breaker is now open for /search, avoiding repeated wasted calls/timeouts
        assertThat(client.supports("https://paid.example" + search)).isFalse();
        // Profile and status endpoints remain active on FxTwitter
        assertThat(client.supports("https://paid.example/twitter/user/info?userName=Self")).isTrue();
        assertThat(client.supports("https://paid.example/twitter/tweets?tweet_ids=101")).isTrue();
        JsonNode profile = fetch("/twitter/user/info?userName=Self", new AtomicBoolean());
        assertThat(profile.path("data").path("id").asText()).isEqualTo("100");
    }

    private JsonNode fetch(String path, AtomicBoolean started) throws Exception {
        return client.fetchAsync("https://paid.example" + path, Instant.now().plusSeconds(5), started).get(6, TimeUnit.SECONDS);
    }

    private void assertFailed(String path) {
        assertThatThrownBy(() -> fetch(path, new AtomicBoolean())).hasCauseInstanceOf(XDataProviderException.class);
    }

    private ObjectNode user(String id, String handle) {
        ObjectNode user = mapper.createObjectNode().put("id", id).put("screen_name", handle).put("name", handle)
                .put("followers", 12).put("following", 5).put("statuses", 20)
                .put("joined", "Wed Jan 01 00:00:00 +0000 2020");
        user.putObject("verification").put("verified", true).put("type", "individual");
        return user;
    }

    private ObjectNode status(String id, long timestamp) {
        ObjectNode status = mapper.createObjectNode().put("type", "status").put("id", id).put("text", "Example")
                .put("created_timestamp", timestamp).put("created_at", Instant.ofEpochSecond(timestamp).toString())
                .put("likes", 2).put("replies", 3).put("reposts", 4).put("quotes", 5).put("bookmarks", 6).put("views", 70)
                .putNull("replying_to").putNull("reposted_by").putNull("media");
        status.set("author", user("100", "Self"));
        status.set("raw_text", mapper.createObjectNode().put("text", "Example").set("facets", mapper.createArrayNode()));
        return status;
    }

    private ObjectNode page(String cursor, ObjectNode... tweets) {
        ObjectNode page = mapper.createObjectNode().put("code", 200);
        ArrayNode results = page.putArray("results");
        for (ObjectNode tweet : tweets) results.add(tweet);
        page.putObject("cursor").putNull("top").put("bottom", cursor);
        return page;
    }

    private static String searchUrl(String query, String cursor) {
        return "/twitter/tweet/advanced_search?queryType=Latest&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }

    private Reply json(JsonNode node) { return new Reply(200, node.toString()); }
    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
    private record Reply(int status, String body) { }
    private record Seen(String uri, String userAgent, String apiKey, String authorization) { }
}
