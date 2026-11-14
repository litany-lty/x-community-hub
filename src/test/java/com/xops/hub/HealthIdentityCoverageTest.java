package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.*;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class HealthIdentityCoverageTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @TempDir Path temp;

    private XUserSummary user(String id, String handle) {
        return new XUserSummary(id, handle, handle, "", "", false, 0, 0, 0, "");
    }

    private AccountStore.StoredAccount stored(String id, String handle) {
        return AccountStore.merge(null, user(id, handle), Instant.now(), List.of(), List.of(),
                List.of(), List.of(), null);
    }

    private XOpsProperties properties() {
        var p = new XOpsProperties();
        p.setDiskCacheDir(temp.toString());
        p.setDefaultProvider("TWITTERAPI_IO");
        p.setTwitterapiIoKey("local-fixture-key");
        return p;
    }

    private HttpServer server(Function<String, String> reply) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = reply.apply(exchange.getRequestURI().toString()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        return server;
    }

    @Test void authenticatedCacheCannotReuseSameHandleFromAnotherId() {
        var p = properties();
        p.setDefaultProvider("X_OFFICIAL_V2");
        p.setOfficialBearerToken("local-fixture-token");
        var current = new AtomicReference<>(user("first-id", "same_handle"));
        var requests = new AtomicInteger();
        var pool = new SharedApiExecutor(p);
        var provider = new OfficialXApiV2Provider(p, mapper, pool) {
            @Override public RawAccountSnapshot fetchSnapshot(String handle, String token) {
                requests.incrementAndGet();
                return new RawAccountSnapshot(ProviderMode.X_OFFICIAL_V2, true, "", Instant.now(),
                        current.get(), List.of(), List.of(), List.of());
            }
        };
        try {
            var hub = new CommunityHubService(p, new DemoXDataProvider(), null, provider, mapper);
            hub.analyzeAccountBundle("same_handle", 2, null, false, "test", "first-id");
            current.set(user("second-id", "same_handle"));
            var second = hub.analyzeAccountBundle("same_handle", 2, null, false, "test", "second-id");
            assertThat(second.day().profile().id()).isEqualTo("second-id");
            assertThat(requests.get()).isEqualTo(2);
        } finally { pool.shutdown(); }
    }

    @Test void staleHandleStoreCannotSeedNewOwnerAndRejectedIdentityCannotPersist() throws Exception {
        var streams = new AtomicInteger();
        var server = server(path -> {
            if (path.contains("/info?")) return "{\"data\":{\"id\":\"new-id\",\"userName\":\"same_handle\",\"followers\":0,\"following\":0}}";
            streams.incrementAndGet();
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p = properties();
        p.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var pool = new SharedApiExecutor(p);
        try {
            var hub = new CommunityHubService(p, new DemoXDataProvider(), new TwitterApiIoProvider(p, mapper, pool), null, mapper);
            Path legacy = hub.resolveUserStoreFile("same_handle");
            AccountStore.write(legacy, stored("old-id", "same_handle"), mapper);
            byte[] original = Files.readAllBytes(legacy);
            assertThatThrownBy(() -> hub.analyzeAccountBundle("same_handle", 2, null, false, "test", "old-id"))
                    .isInstanceOf(XDataProviderException.class).hasMessageContaining("ID");
            assertThat(streams.get()).isZero();
            assertThat(Files.exists(temp.resolve("users/by-id/old-id/store_v1.json.gz"))).isFalse();
            var result = hub.analyzeAccountBundle("same_handle", 2, null, false, "test", "new-id");
            assertThat(result.day().profile().id()).isEqualTo("new-id");
            var persisted = AccountStore.read(temp.resolve("users/by-id/new-id/store_v1.json.gz"), mapper);
            assertThat(persisted.profile().id()).isEqualTo("new-id");
            assertThat(Files.readAllBytes(legacy)).isEqualTo(original);
        } finally { pool.shutdown(); server.stop(0); }
    }

    @Test void historyExpansionChecksCurrentOwnerBeforeCollectingStreams() throws Exception {
        var streams = new AtomicInteger();
        var server = server(path -> {
            if (path.contains("/info?")) return "{\"data\":{\"id\":\"new-id\",\"userName\":\"same_handle\"}}";
            streams.incrementAndGet();
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p = properties();
        p.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var pool = new SharedApiExecutor(p);
        try {
            var provider = new TwitterApiIoProvider(p, mapper, pool);
            assertThatThrownBy(() -> provider.syncStore("same_handle", stored("old-id", "same_handle"), 7, false, null))
                    .isInstanceOf(XDataProviderException.class).hasMessageContaining("ID");
            assertThat(streams.get()).isZero();
        } finally { pool.shutdown(); server.stop(0); }
    }

    @Test void endedFollowerPagesBelowProfileTotalRemainUncertainAndAreNotReused() throws Exception {
        var followerCalls = new AtomicInteger();
        var server = server(path -> {
            if (path.contains("/info?")) return "{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"followers\":43,\"following\":29}}";
            if (path.contains("/followers?")) {
                followerCalls.incrementAndGet();
                return userPage("followers", 40);
            }
            if (path.contains("/followings?")) return userPage("followings", 29);
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p = properties();
        p.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var pool = new SharedApiExecutor(p);
        try {
            var provider = new TwitterApiIoProvider(p, mapper, pool);
            var first = provider.syncStore("self", null, 2, true, null);
            assertThat(first.store().followers()).hasSize(40);
            assertThat(first.store().followings()).hasSize(29);
            assertThat(TwitterApiIoProvider.snapshotOf(first.store(), "", false).followersTruncated()).isTrue();
            assertThat(first.store().followingsTruncated()).isFalse();
            var second = provider.syncStore("self", first.store(), 2, true, null);
            assertThat(second.graphReused()).isFalse();
            assertThat(followerCalls.get()).isEqualTo(2);
        } finally { pool.shutdown(); server.stop(0); }
    }

    private String userPage(String key, int count) {
        var users = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) users.add(Map.of("id", "peer" + i, "userName", "peer" + i));
        try { return mapper.writeValueAsString(Map.of(key, users, "has_next_page", false)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void sameIdRenameRefusesOldHistoryWithAnActionableMessage() {
        var old = stored("stable-id", "old_handle");
        assertThatThrownBy(() -> AccountStore.merge(old, user("stable-id", "new_handle"), Instant.now(),
                List.of(), List.of(), List.of(), List.of(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("用户名已变更").hasMessageContaining("重新登录");
    }

    @Test void missingCoverageMetadataDoesNotClaimACompleteWindow() throws Exception {
        var bundle = mapper.readValue("{\"month\":null,\"week\":null,\"day\":null}", MultiPeriodDashboardBundle.class);
        assertThat(bundle.coveredWindowDays()).isZero();
        assertThat(new MultiPeriodDashboardBundle(null, null, null).coveredWindowDays()).isZero();
    }

    @Test void legacyFollowClicksDoNotBecomeConfirmedRelationshipsAndUsageIsIdIsolated() throws Exception {
        var p = properties();
        Path usage = temp.resolve("users/self/usage_v1.json");
        Files.createDirectories(usage.getParent());
        String previous = "{\"usageCount\":8,\"lastModalShownAtCount\":3,\"optimisticFollowedAt\":\"2026-11-13T02:00:00Z\",\"updatedAt\":\"2026-11-13T02:00:00Z\"}";
        Files.writeString(usage, previous);
        var hub = new CommunityHubService(p, new DemoXDataProvider(), null, null, mapper);
        var legacy = hub.markAuthorFollowedOptimistically("self");
        assertThat(legacy).containsEntry("followingAuthor", false).containsEntry("pendingConfirmation", true);
        assertThat(Files.readString(usage)).isEqualTo(previous);
        assertThat(hub.markAuthorFollowedOptimistically("self", "different-id")).containsEntry("usageCount", 0);
    }
}
