package com.xops.hub.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipOrderProviderTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void directPaginationPreservesProviderOrderAcrossPages() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        try (Fixture fixture = new Fixture(uri -> {
            pages.incrementAndGet();
            return uri.getRawQuery().contains("cursor=")
                    ? "{\"followings\":[{\"id\":\"oldest-id\",\"userName\":\"oldest\"}],\"has_next_page\":false}"
                    : "{\"followings\":[{\"id\":\"newest-id\",\"userName\":\"newest\"},{\"id\":\"middle-id\",\"userName\":\"middle\"}],\"has_next_page\":true,\"next_cursor\":\"older-page\"}";
        })) {
            CompletableFuture<?> pending = ReflectionTestUtils.invokeMethod(fixture.provider,
                    "fetchPagedUsersAsync", fixture.baseUrl + "/twitter/user/followings?userName=self",
                    "local-order-test-key", fixture.pacer, 3, 3, "followings",
                    Instant.now().plusSeconds(10), new AtomicBoolean());
            Object result = pending.get(5, TimeUnit.SECONDS);
            assertThat(order(result)).containsExactlyInAnyOrderEntriesOf(
                    Map.of("newest-id", 0, "middle-id", 1, "oldest-id", 2));
            assertThat(users(result)).extracting(XUserSummary::id)
                    .containsExactly("newest-id", "middle-id", "oldest-id");
            assertThat(pages.get()).isEqualTo(2);
        }
    }

    @Test
    void supplementalBatchProfilesDoNotGainAnInventedFollowOrder() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        AtomicInteger idCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(uri -> {
            if (uri.getPath().endsWith("/followers_ids")) {
                idCalls.incrementAndGet();
                return "{\"status\":\"success\",\"ids\":[\"older-id\",\"missing-id\",\"newest-id\"],\"has_next_page\":false,\"next_cursor\":\"0\"}";
            }
            if (uri.getPath().endsWith("/batch_info_by_ids")) {
                batchCalls.incrementAndGet();
                return "{\"status\":\"success\",\"users\":[{\"id\":\"missing-id\",\"userName\":\"missing\"}]}";
            }
            listCalls.incrementAndGet();
            return "{\"followers\":[{\"id\":\"newest-id\",\"userName\":\"newest\"},{\"id\":\"older-id\",\"userName\":\"older\"}],\"has_next_page\":false}";
        })) {
            CompletableFuture<?> pending = ReflectionTestUtils.invokeMethod(fixture.provider,
                    "fetchFollowersAsync", fixture.baseUrl, "self", "self-id", "local-order-test-key",
                    fixture.pacer, 3, 3, Instant.now().plusSeconds(10), new AtomicBoolean());
            Object result = pending.get(5, TimeUnit.SECONDS);
            assertThat(users(result)).extracting(XUserSummary::id)
                    .containsExactly("newest-id", "older-id", "missing-id");
            assertThat(order(result)).containsExactlyInAnyOrderEntriesOf(Map.of("newest-id", 0, "older-id", 1));
            assertThat(order(result)).doesNotContainKey("missing-id");
            assertThat(listCalls.get()).isEqualTo(1);
            assertThat(idCalls.get()).isEqualTo(1);
            assertThat(batchCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void partialGraphRefreshPreservesCachedOrderIncludingUnknownLegacyOrder() throws Exception {
        AtomicInteger followingCalls = new AtomicInteger();
        AtomicInteger followerCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) {
                return "{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":2,\"followers\":2}}";
            }
            if (uri.getPath().endsWith("/followings")) {
                followingCalls.incrementAndGet();
                return "{\"followings\":[],\"has_next_page\":false}";
            }
            if (uri.getPath().endsWith("/followers")) {
                followerCalls.incrementAndGet();
                return "{\"followers\":[{\"id\":\"new-fan-id\",\"userName\":\"new_fan\"},{\"id\":\"old-fan-id\",\"userName\":\"old_fan\"}],\"has_next_page\":false}";
            }
            return "{\"tweets\":[],\"has_next_page\":false}";
        })) {
            List<Map<String, Integer>> cachedOrders = List.of(Map.of(), Map.of("first-id", 7, "second-id", 21));
            for (Map<String, Integer> cachedOrder : cachedOrders) {
                Instant now = Instant.now();
                XUserSummary self = new XUserSummary("self-id", "self", "Self", "", "", false, 1, 2, 0, "");
                var graph = new AccountStore.GraphUpdate(
                        List.of(user("first-id", "first"), user("second-id", "second")),
                        List.of(user("old-fan-id", "old_fan")), Set.of(), false, false, false, false,
                        List.of(), Map.of(), cachedOrder, Map.of());
                List<long[]> coverage = List.of(new long[]{now.minusSeconds(30L * 24 * 3600).getEpochSecond(), now.getEpochSecond()});
                var existing = AccountStore.merge(null, self, now, List.of(), List.of(), coverage, coverage, graph);
                var refreshed = fixture.provider.syncStore("self", existing, 2, true, null).store();
                assertThat(refreshed.followingOrderById()).isEqualTo(cachedOrder);
                assertThat(refreshed.followerOrderById())
                        .containsExactlyInAnyOrderEntriesOf(Map.of("new-fan-id", 0, "old-fan-id", 1));
            }
            assertThat(followingCalls.get()).isZero();
            assertThat(followerCalls.get()).isEqualTo(2);
        }
    }

    private static XUserSummary user(String id, String username) {
        return new XUserSummary(id, username, username, "", "", false, 1, 1, 0, "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> order(Object result) {
        return ReflectionTestUtils.invokeMethod(result, "orderById");
    }

    @SuppressWarnings("unchecked")
    private static List<XUserSummary> users(Object result) {
        return ReflectionTestUtils.invokeMethod(result, "users");
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final SharedApiExecutor executor;
        private final TwitterApiIoProvider provider;
        private final String baseUrl;
        private final Object pacer;

        private Fixture(Function<URI, String> response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = response.apply(exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var properties = new XOpsProperties();
            properties.setDiskCacheDir(directory.toString());
            properties.setTwitterapiIoBaseUrl(baseUrl);
            properties.setTwitterapiIoKey("local-order-test-key");
            properties.setApiPoolCoreSize(2);
            properties.setApiPoolMaxSize(4);
            properties.setApiPoolQueueCapacity(16);
            executor = new SharedApiExecutor(properties);
            provider = new TwitterApiIoProvider(properties, mapper, executor);
            pacer = ReflectionTestUtils.invokeMethod(provider, "pacerFor", "local-order-test-key");
        }

        @Override
        public void close() {
            executor.shutdown();
            server.stop(0);
        }
    }
}
