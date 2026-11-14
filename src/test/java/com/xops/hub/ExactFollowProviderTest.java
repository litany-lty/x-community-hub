package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.RelationshipEntry;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class ExactFollowProviderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @TempDir Path temp;

    record Reply(int status, String body) {
        static Reply ok(String body) { return new Reply(200, body); }
    }

    class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newCachedThreadPool();
        final SharedApiExecutor pool;
        final XOpsProperties properties;
        final TwitterApiIoProvider provider;
        Fixture(Function<URI, Reply> reply) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                Reply r = reply.apply(exchange.getRequestURI());
                byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(r.status(), bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
            var p = properties = new XOpsProperties();
            p.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            p.setTwitterapiIoKey("local-fixture");
            p.setApiPoolCoreSize(4);
            p.setApiPoolMaxSize(8);
            pool = new SharedApiExecutor(p);
            provider = new TwitterApiIoProvider(p, mapper, pool);
        }
        @Override public void close() { pool.shutdown(); server.stop(0); handlers.shutdownNow(); }
    }

    private Reply profile(int following, int followers) {
        return Reply.ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":"
                + following + ",\"followers\":" + followers + "}}");
    }
    private Reply page(String key, String... handles) {
        var users = new ArrayList<Map<String, String>>();
        for (String h : handles) users.add(Map.of("id", "id-" + h, "userName", h));
        try { return Reply.ok(mapper.writeValueAsString(Map.of(key, users, "has_next_page", false))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Reply empty() { return Reply.ok("{\"tweets\":[],\"has_next_page\":false}"); }
    private Reply relationship(boolean following, boolean followedBy) {
        return Reply.ok("{\"status\":\"success\",\"data\":{\"following\":" + following + ",\"followed_by\":" + followedBy + "}}");
    }
    private String target(URI uri) {
        Map<String, String> query = new HashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertThat(query.get("source_user_name")).isEqualTo("self");
        return query.get("target_user_name");
    }
    private RelationshipEntry relation(AccountStore.StoredAccount store, String handle) {
        return TwitterApiIoProvider.snapshotOf(store, "", false).relationships().stream()
                .filter(r -> r.user().username().equals(handle)).findFirst().orElseThrow();
    }

    @Test void missingFollowersAreCheckedInBothDirectionsAndEvidenceSurvivesStorageAndExpansion() throws Exception {
        var queries = ConcurrentHashMap.<String>newKeySet();
        var exactCalls = new AtomicInteger();
        var phase = new AtomicInteger();
        try (var f = new Fixture(uri -> {
            String path = uri.getPath();
            if (path.endsWith("/info")) return profile(3, 2);
            if (path.endsWith("/followings")) return page("followings", "listed", "nonback", "missed");
            if (path.endsWith("/followers")) return phase.get() == 0 ? page("followers", "listed") : new Reply(500, "{}");
            if (path.endsWith("/check_follow_relationship")) {
                String peer = target(uri); queries.add(peer); exactCalls.incrementAndGet();
                if (phase.get() > 0) return Reply.ok("{\"status\":\"success\",\"data\":{}}");
                return relationship(true, peer.equals("missed"));
            }
            return empty();
        })) {
            var first = f.provider.syncStore("self", null, 2, true, null);
            assertThat(queries).containsExactlyInAnyOrder("nonback", "missed");
            assertThat(relation(first.store(), "nonback")).satisfies(r -> {
                assertThat(r.relationshipVerified()).isTrue(); assertThat(r.iFollowThem()).isTrue(); assertThat(r.theyFollowMe()).isFalse();
            });
            assertThat(relation(first.store(), "missed")).satisfies(r -> {
                assertThat(r.relationshipVerified()).isTrue(); assertThat(r.theyFollowMe()).isTrue();
                assertThat(r.user().id()).isEqualTo("id-missed");
            });
            assertThat(relation(first.store(), "listed").relationshipVerified()).isFalse();
            assertThat(first.store().followersTruncated()).isTrue();
            Path file = temp.resolve("store.json.gz");
            AccountStore.write(file, first.store(), mapper);
            var reread = AccountStore.read(file, mapper);
            assertThat(relation(reread, "nonback").relationshipVerified()).isTrue();
            var staleJson = mapper.valueToTree(reread);
            for (var e : staleJson.path("re")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) e).put("checkedAt", java.time.Instant.now().minusSeconds(301).getEpochSecond());
            }
            var stale = mapper.treeToValue(staleJson, AccountStore.StoredAccount.class);
            assertThat(relation(stale, "nonback").relationshipVerified()).isFalse();
            f.properties.setDiskCacheDir(temp.resolve("hub").toString());
            var unavailable = new TwitterApiIoProvider(f.properties, mapper, f.pool) {
                @Override public SyncOutcome syncStore(String username, AccountStore.StoredAccount existing,
                        int days, boolean headSync, String apiKey, String expectedId) {
                    throw new com.xops.hub.provider.XDataProviderException(503, "local fixture unavailable");
                }
            };
            var hub = new com.xops.hub.service.CommunityHubService(f.properties,
                    new com.xops.hub.provider.DemoXDataProvider(), unavailable, null, mapper);
            AccountStore.write(hub.resolveUserStoreFile("self"), reread, mapper);
            var fallback = hub.analyzeAccountBundle("self", 2, null, false, "local", "self-id");
            assertThat(fallback.day().followAudit().notFollowingBackCount()).isZero();
            assertThat(fallback.day().followAudit().unknownFollowBackCount()).isEqualTo(2);
            var expanded = f.provider.syncStore("self", reread, 7, false, null);
            assertThat(expanded.store().relationshipEvidence()).isEqualTo(first.store().relationshipEvidence());
            assertThat(exactCalls.get()).isEqualTo(2);
            phase.set(1);
            var refreshed = f.provider.syncStore("self", expanded.store(), 7, true, null);
            assertThat(refreshed.store().graphRefreshFailed()).isTrue();
            assertThat(refreshed.store().relationshipEvidence()).isEmpty();
            assertThat(relation(refreshed.store(), "nonback").relationshipVerified()).isFalse();
            var legacy = mapper.valueToTree(first.store());
            ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("re");
            assertThat(mapper.treeToValue(legacy, AccountStore.StoredAccount.class).relationshipEvidence()).isEmpty();
        }
    }

    @Test void sameHandleInFollowersWithDifferentIdDoesNotSkipExactVerification() throws Exception {
        var calls = new AtomicInteger();
        try (var f = new Fixture(uri -> {
            String path = uri.getPath();
            if (path.endsWith("/info")) return profile(1, 2);
            if (path.endsWith("/followings")) return Reply.ok("{\"followings\":[{\"id\":\"new-peer-id\",\"userName\":\"reused\"}],\"has_next_page\":false}");
            if (path.endsWith("/followers")) return Reply.ok("{\"followers\":[{\"id\":\"old-peer-id\",\"userName\":\"reused\"}],\"has_next_page\":false}");
            if (path.endsWith("/check_follow_relationship")) {
                calls.incrementAndGet();
                return relationship(true, false);
            }
            return empty();
        })) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(relation(result.store(), "reused")).satisfies(r -> {
                assertThat(r.user().id()).isEqualTo("new-peer-id");
                assertThat(r.relationshipVerified()).isTrue();
                assertThat(r.theyFollowMe()).isFalse();
            });
        }
    }

    @Test void malformedFailedAndRateLimitedChecksRemainUnknownWithoutRetry() throws Exception {
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        try (var f = new Fixture(uri -> {
            String path = uri.getPath();
            if (path.endsWith("/info")) return profile(4, 1);
            if (path.endsWith("/followings")) return page("followings", "missing", "wrongtype", "failed", "limited");
            if (path.endsWith("/followers")) return Reply.ok("{\"followers\":[{\"userName\":\"missing\"}],\"has_next_page\":false}");
            if (path.endsWith("/check_follow_relationship")) {
                String peer = target(uri); calls.computeIfAbsent(peer, ignored -> new AtomicInteger()).incrementAndGet();
                return switch (peer) {
                    case "missing" -> Reply.ok("{\"status\":\"success\",\"data\":{\"following\":true}}");
                    case "wrongtype" -> Reply.ok("{\"status\":\"success\",\"data\":{\"following\":true,\"followed_by\":\"false\"}}");
                    case "failed" -> Reply.ok("{\"status\":\"error\",\"data\":{\"following\":true,\"followed_by\":false}}");
                    default -> new Reply(429, "{}");
                };
            }
            return empty();
        })) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(calls).hasSize(4);
            assertThat(calls.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
            assertThat(result.store().followers()).isEmpty();
            assertThat(result.store().followersTruncated()).isTrue();
            assertThat(result.store().relationshipEvidence()).isEmpty();
            assertThat(TwitterApiIoProvider.snapshotOf(result.store(), "", false).relationships())
                    .allSatisfy(r -> assertThat(r.relationshipVerified()).isFalse());
        }
    }

    @Test void exactFollowingChangesMarkTheGraphUncertainWithoutChangingProfileCounts() throws Exception {
        try (var f = new Fixture(uri -> {
            String path = uri.getPath();
            if (path.endsWith("/info")) return profile(1, 1);
            if (path.endsWith("/followings")) return page("followings", "changed");
            if (path.endsWith("/followers")) return page("followers");
            if (path.endsWith("/check_follow_relationship")) return relationship(false, true);
            return empty();
        })) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(relation(result.store(), "changed")).satisfies(r -> {
                assertThat(r.relationshipVerified()).isTrue(); assertThat(r.iFollowThem()).isFalse(); assertThat(r.theyFollowMe()).isTrue();
            });
            assertThat(result.store().followingsTruncated()).isTrue();
            assertThat(result.store().profile().followingCount()).isEqualTo(1);
            assertThat(result.store().followers()).isEmpty();
        }
    }

    @Test void relationshipChecksAreLimitedToOneHundredCandidatesAndFourConcurrentRequests() throws Exception {
        var calls = new AtomicInteger(); var active = new AtomicInteger(); var maxActive = new AtomicInteger();
        String[] peers = java.util.stream.IntStream.range(0, 105).mapToObj(i -> "peer" + i).toArray(String[]::new);
        try (var f = new Fixture(uri -> {
            String path = uri.getPath();
            if (path.endsWith("/info")) return profile(105, 1);
            if (path.endsWith("/followings")) return page("followings", peers);
            if (path.endsWith("/followers")) return page("followers");
            if (path.endsWith("/check_follow_relationship")) {
                calls.incrementAndGet(); maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); }
                return relationship(true, false);
            }
            return empty();
        })) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(calls.get()).isEqualTo(100);
            assertThat(maxActive.get()).isBetween(1, 4);
            assertThat(result.store().relationshipEvidence()).hasSize(100);
            assertThat(relation(result.store(), "peer104").relationshipVerified()).isFalse();
        }
    }
}
