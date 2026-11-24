package com.xops.hub.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class SelectiveFreeRoutingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temp;

    @Test
    void unaffectedEndpointsWorkViaFreeFxTwitterWithBlankKeyAndPreserveExistingGraph() throws Exception {
        AtomicInteger paidHits = new AtomicInteger();
        AtomicInteger freeHits = new AtomicInteger();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        HttpServer paidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.createContext("/", ex -> {
            paidHits.incrementAndGet();
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        paidServer.start();

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.createContext("/", ex -> {
            freeHits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            ObjectNode body = MAPPER.createObjectNode().put("code", 200);
            if (path.equals("/2/profile/alice")) {
                body.set("user", freeUser("1001", "alice", 320, 80));
            } else if (path.equals("/2/profile/alice/statuses")) {
                body.putObject("cursor").put("bottom", "");
                ArrayNode statuses = body.putArray("results");
                statuses.add(freeStatus("9001", "1001", "alice", now.minusSeconds(600)));
            } else if (path.equals("/2/search")) {
                body.putObject("cursor").put("bottom", "");
                body.putArray("results");
            } else if (path.equals("/2/status/9001")) {
                body.set("status", freeStatus("9001", "1001", "alice", now.minusSeconds(600)));
            }
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setTwitterapiIoKey(""); // Explicitly blank key!
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");
        props.setDiskCacheDir(temp.resolve("cache").toString());

        SharedApiExecutor pool = new SharedApiExecutor(props);
        try {
            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, MAPPER, pool);

            // 1. resolvePublicAuthor & fetchPublicUserSummaryQuietly
            XUserSummary resolved = provider.resolvePublicAuthor("alice");
            assertThat(resolved.id()).isEqualTo("1001");
            assertThat(provider.fetchPublicUserSummaryQuietly("alice")).isNotNull();

            // 2. observeOwner & observePost
            ObjectNode ownerObs = provider.observeOwner("1001", "alice");
            assertThat(ownerObs.path("followers").asLong()).isEqualTo(320L);
            ObjectNode postObs = provider.observePost("1001", "9001");
            assertThat(postObs.path("views").asLong()).isEqualTo(1500L);
            assertThat(postObs.path("likes").asLong()).isEqualTo(42L);

            // 3. fetchTextPostsPage (SourceCollector / style sampling)
            var postPage = provider.fetchTextPostsPage("alice", "1001", "", 20, Instant.now().plusSeconds(10));
            assertThat(postPage.posts()).hasSize(1);

            // 4. syncStoreByHours preserves previously stored follow graph when key is blank!
            XUserSummary self = new XUserSummary("1001", "alice", "Alice", "", "", false, 320, 80, 10, "");
            XUserSummary friend = new XUserSummary("2002", "bob", "Bob", "", "", false, 100, 50, 5, "");
            AccountStore.GraphUpdate existingGraph = new AccountStore.GraphUpdate(
                    List.of(friend), List.of(friend), Set.of("bob"), false, false, false, false,
                    List.of(), Map.of(), Map.of("2002", 0), Map.of("2002", 0));
            AccountStore.StoredAccount seeded = AccountStore.merge(
                    null, self, now.minusSeconds(7200), List.of(), List.of(), List.of(), List.of(), existingGraph, Map.of());

            var synced = provider.syncStoreByHours("alice", seeded, 24, true, true, null, "1001");
            assertThat(synced.store().tweets()).extracting(AccountStore.StoredTweet::id).contains("9001");
            assertThat(AccountStore.followingUsers(synced.store())).extracting(XUserSummary::username).containsExactly("bob");
            assertThat(paidHits.get()).isZero();
            assertThat(freeHits.get()).isGreaterThan(0);
        } finally {
            pool.shutdown();
            paidServer.stop(0);
            freeServer.stop(0);
        }
    }

    @Test
    void pureFreeEndpointsDoNotFallbackToPaidInNonLocalModeWhileAffectedEndpointsKeepPaidSupport() throws Exception {
        AtomicInteger paidFollowHits = new AtomicInteger();
        AtomicInteger freeFailHits = new AtomicInteger();

        HttpServer paidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.createContext("/twitter/user/mentions", ex -> {
            paidFollowHits.incrementAndGet();
            ObjectNode root = MAPPER.createObjectNode().put("has_next_page", false);
            root.putArray("tweets");
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        paidServer.start();

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.createContext("/2/profile/alice", ex -> {
            ObjectNode body = MAPPER.createObjectNode().put("code", 200);
            body.set("user", freeUser("1001", "alice", 10, 5));
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.createContext("/2/status/404999", ex -> {
            freeFailHits.incrementAndGet();
            byte[] bytes = "{\"code\":404,\"message\":\"Not found\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(404, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setTwitterapiIoKey("valid-test-key");
        // Affected endpoint (`fetchInboundTextPage` -> `/twitter/user/mentions`) uses paidServer
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");
        props.setDiskCacheDir(temp.resolve("cache2").toString());

        SharedApiExecutor pool = new SharedApiExecutor(props);
        try {
            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, MAPPER, pool);
            var mentions = provider.fetchInboundTextPage("alice", "1001", "", 20, Instant.now().plusSeconds(10));
            assertThat(mentions.exhausted()).isTrue();
            assertThat(paidFollowHits.get()).isEqualTo(1); // Affected endpoint still calls TwitterAPI.io!
        } finally {
            pool.shutdown();
            paidServer.stop(0);
            freeServer.stop(0);
        }
    }

    @Test
    void metricFailureDoesNotAbortConcurrentStreamPaginationAndMissingMetricVerifiesViaPaidWhenConfigured() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicInteger searchPageCalls = new AtomicInteger();
        AtomicInteger paidMetricCalls = new AtomicInteger();

        AtomicBoolean failPaidMetrics = new AtomicBoolean(false);
        HttpServer paidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.createContext("/twitter/tweets", ex -> {
            paidMetricCalls.incrementAndGet();
            if (failPaidMetrics.get()) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.putArray("tweets"); // confirms deleted-9099 was deleted
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        paidServer.createContext("/twitter/tweet/advanced_search", ex -> {
            String query = ex.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("cursor=page-2");
            searchPageCalls.incrementAndGet();
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rows = root.putArray("tweets");
            if (query != null && query.contains("from:alice") && !query.contains("-from:alice")) {
                if (!secondPage) {
                    rows.add(paidPost("9101", "1001", "alice", now.minusSeconds(1200)));
                    root.put("has_next_page", true).put("next_cursor", "page-2");
                } else {
                    rows.add(paidPost("9102", "1001", "alice", now.minusSeconds(95_000)));
                    root.put("has_next_page", false);
                }
            } else {
                root.put("has_next_page", false);
            }
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        paidServer.start();

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.createContext("/2/profile/alice", ex -> {
            ObjectNode body = MAPPER.createObjectNode().put("code", 200);
            body.set("user", freeUser("1001", "alice", 0, 0));
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.createContext("/2/status/9099", ex -> {
            byte[] bytes = "{\"code\":404,\"message\":\"Not found\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(404, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.createContext("/2/search", ex -> {
            byte[] bytes = "{\"code\":503,\"message\":\"search unavailable\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(503, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setTwitterapiIoKey("");
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");
        props.setDiskCacheDir(temp.resolve("cache3").toString());
        props.setSplitInboundSearchQueries(false);

        SharedApiExecutor pool = new SharedApiExecutor(props);
        try {
            XUserSummary self = new XUserSummary("1001", "alice", "Alice", "", "", false, 0, 0, 10, "");
            var staleTweet = new AccountStore.StoredTweet("9099", now.minusSeconds(1800).getEpochSecond(), 'P',
                    "deleted post", null, List.of(), 0, new long[]{10, 1, 0, 0, 0}, null, null,
                    now.minusSeconds(7200).getEpochSecond(), now.minusSeconds(7200).getEpochSecond(), Set.of(), true);
            var seeded = new AccountStore.StoredAccount(AccountStore.VERSION, self, now.minusSeconds(7200).getEpochSecond(),
                    Map.of(), List.of(staleTweet), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    now.minusSeconds(3600).getEpochSecond(), false, false, false, List.of(), Map.of(), 0, 0);

            // 1) Fast-failing metric batch (404 + paid 500) must NOT abort concurrent multi-page stream pagination!
            props.setTwitterapiIoKey("valid-test-key");
            failPaidMetrics.set(true);
            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, MAPPER, pool);
            var degradedMetricRun = provider.syncStoreByHours("alice", seeded, 24, true, true, null, "1001");
            long from24First = degradedMetricRun.store().syncedAt() - 86_400L;
            assertThat(AccountStore.missingRanges(degradedMetricRun.store().tweetCoverage(), from24First, degradedMetricRun.store().syncedAt())).isEmpty();
            assertThat(AccountStore.missingRanges(degradedMetricRun.store().inboundCoverage(), from24First, degradedMetricRun.store().syncedAt())).isEmpty();
            assertThat(degradedMetricRun.store().tweets()).extracting(AccountStore.StoredTweet::id)
                    .contains("9101", "9102", "9099");

            // 2) Normal paid verification of missing free ID prunes deleted tweet and completes daily refresh cleanly
            failPaidMetrics.set(false);
            var synced = provider.syncStoreByHours("alice", seeded, 24, true, true, null, "1001");
            long from24 = synced.store().syncedAt() - 86_400L;
            assertThat(AccountStore.missingRanges(synced.store().tweetCoverage(), from24, synced.store().syncedAt())).isEmpty();
            assertThat(AccountStore.missingRanges(synced.store().inboundCoverage(), from24, synced.store().syncedAt())).isEmpty();
            assertThat(synced.store().tweets()).extracting(AccountStore.StoredTweet::id)
                    .contains("9101", "9102")
                    .doesNotContain("9099");
            assertThat(paidMetricCalls.get()).isEqualTo(2);
            assertThat(synced.dailyDataComplete()).isTrue();
        } finally {
            pool.shutdown();
            paidServer.stop(0);
            freeServer.stop(0);
        }
    }

    private static ObjectNode paidPost(String id, String authorId, String handle, Instant createdAt) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("id", id)
                .put("text", "post " + id)
                .put("createdAt", createdAt.toString())
                .put("conversationId", id)
                .put("viewCount", 100)
                .put("likeCount", 5)
                .put("replyCount", 1)
                .put("retweetCount", 0)
                .put("quoteCount", 0)
                .put("bookmarkCount", 2);
        node.putObject("author").put("id", authorId).put("userName", handle).put("name", "Alice");
        return node;
    }

    private static ObjectNode freeUser(String id, String screenName, int followers, int following) {
        return MAPPER.createObjectNode()
                .put("type", "profile")
                .put("id", id)
                .put("screen_name", screenName)
                .put("name", "Alice")
                .put("avatar_url", "")
                .put("description", "bio")
                .put("followers", followers)
                .put("following", following)
                .put("statuses", 10);
    }

    private static ObjectNode freeStatus(String id, String authorId, String screenName, Instant createdAt) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("type", "status")
                .put("id", id)
                .put("text", "Deep technical insight on distributed systems")
                .put("created_at", createdAt.toString())
                .put("created_timestamp", createdAt.getEpochSecond())
                .put("views", 1500)
                .put("likes", 42)
                .put("replies", 5)
                .put("reposts", 8)
                .put("bookmarks", 11);
        node.set("author", freeUser(authorId, screenName, 320, 80));
        return node;
    }

    @Test
    void transientTimeline404RetriesOnFreeAndPersistentFailureFallsBackOnlyForPrivilegedOwner() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicInteger freeTimelineHits = new AtomicInteger();
        AtomicInteger paidTimelineHits = new AtomicInteger();
        AtomicBoolean alwaysFailFree = new AtomicBoolean(false);

        HttpServer paidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.createContext("/twitter/user/last_tweets", ex -> {
            paidTimelineHits.incrementAndGet();
            ObjectNode root = MAPPER.createObjectNode().put("has_next_page", false);
            root.putArray("tweets").add(paidPost("9502", "1001", "alice", now.minusSeconds(120)));
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        paidServer.start();

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.createContext("/2/profile/alice/statuses", ex -> {
            int hit = freeTimelineHits.incrementAndGet();
            if (alwaysFailFree.get() || hit == 1) {
                byte[] bogus404 = "{\"code\":404,\"results\":[],\"cursor\":{\"top\":null,\"bottom\":null}}"
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(404, bogus404.length);
                ex.getResponseBody().write(bogus404);
                ex.close();
                return;
            }
            ObjectNode body = MAPPER.createObjectNode().put("code", 200);
            body.putObject("cursor").put("bottom", "");
            body.putArray("results").add(freeStatus("9501", "1001", "alice", now.minusSeconds(90)));
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        freeServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setTwitterapiIoKey("valid-test-key");
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");
        props.setDiskCacheDir(temp.resolve("cache-404").toString());

        SharedApiExecutor pool = new SharedApiExecutor(props);
        try {
            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, MAPPER, pool);

            // 1. Transient bogus 404 recovers via inline free retry without hitting paid API
            var recoveredPage = PaidCapabilityGate.withCaller("elidlicom", "mon-1", () ->
                    provider.fetchTextPostsPage("alice", "1001", "", 20, Instant.now().plusSeconds(10)));
            assertThat(recoveredPage.posts()).extracting(TwitterApiIoProvider.TextPost::id).containsExactly("9501");
            assertThat(freeTimelineHits.get()).isEqualTo(2);
            assertThat(paidTimelineHits.get()).isZero();

            // 2. Persistent free 404 falls back to paid TwitterAPI.io for privileged owner @elidlicom
            alwaysFailFree.set(true);
            freeTimelineHits.set(0);
            var fallbackPage = PaidCapabilityGate.withCaller("elidlicom", "mon-2", () ->
                    provider.fetchTextPostsPage("alice", "1001", "", 20, Instant.now().plusSeconds(10)));
            assertThat(fallbackPage.posts()).extracting(TwitterApiIoProvider.TextPost::id).containsExactly("9502");
            assertThat(freeTimelineHits.get()).isEqualTo(3);
            assertThat(paidTimelineHits.get()).isEqualTo(1);
        } finally {
            pool.shutdown();
            paidServer.stop(0);
            freeServer.stop(0);
        }
    }
}
