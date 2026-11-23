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
}
