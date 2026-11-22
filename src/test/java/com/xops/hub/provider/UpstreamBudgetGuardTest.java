package com.xops.hub.provider;
import com.xops.hub.config.XOpsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class UpstreamBudgetGuardTest {
    @TempDir Path directory;
    private static final URI PROFILE = URI.create("https://api.twitterapi.io/twitter/user/info?userName=test");
    private XOpsProperties properties() {
        XOpsProperties p = new XOpsProperties();
        p.setDiskCacheDir(directory.toString());
        p.setHourlyUpstreamApiBudget(1000);
        p.setDailyUpstreamApiBudget(2000);
        return p;
    }
    @Test void concurrentGuardInstancesShareDurableAdmissionAndCannotOverspend() throws Exception {
        XOpsProperties p = properties();
        p.setHourlyUpstreamApiBudget(7);
        List<UpstreamBudgetGuard> guards = List.of(new UpstreamBudgetGuard(p), new UpstreamBudgetGuard(p));
        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                UpstreamBudgetGuard guard = guards.get(i % guards.size());
                attempts.add(() -> {
                    try { guard.acquirePermit(PROFILE, "twitterapi_io_read"); return true; }
                    catch (XDataProviderException ex) { assertThat(ex.getStatusCode()).isEqualTo(429); return false; }
                });
            }
            long admitted = 0;
            for (var result : executor.invokeAll(attempts)) if (result.get()) admitted++;
            assertThat(admitted).isEqualTo(7);
            assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read"))
                .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
        } finally { executor.shutdownNow(); }
    }
    @Test void moneyIsReservedBeforeSendingAndSurvivesRestart() {
        XOpsProperties p = properties();
        p.setHourlyUpstreamUsdBudget("0.00035");
        new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
    }
    @Test void dailyAndMonthlyLimitsSurviveHourAndDayRollover() {
        XOpsProperties p = properties();
        p.setDailyUpstreamUsdBudget("0.00035");
        p.setMonthlyUpstreamUsdBudget("0.00035");
        new UpstreamBudgetGuard(p, false, clock("2026-11-15T01:00:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, false, clock("2026-11-15T02:00:00Z"))
            .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, false, clock("2026-11-16T01:00:00Z"))
            .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
        new UpstreamBudgetGuard(p, false, clock("2026-12-01T01:00:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
    }
    @Test void backwardsClockDoesNotResetConsumedBudget() {
        XOpsProperties p = properties(); p.setHourlyUpstreamApiBudget(1);
        new UpstreamBudgetGuard(p, false, clock("2026-11-15T02:00:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, false, clock("2026-11-15T01:00:00Z"))
            .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
    }
    @Test void corruptOrMissingInitializedLedgerBlocksCallsInsteadOfResetting() throws Exception {
        XOpsProperties p = properties(); new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read");
        Path ledger = directory.resolve("upstream-budget.json");
        Files.writeString(ledger, "{\"version\":1");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("记录暂不可用");
        Files.delete(ledger);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("记录暂不可用");
    }
    @Test void invalidLedgerPathStopsAdmissionAndZeroMeansNoSpending() throws Exception {
        XOpsProperties p = properties(); Files.createDirectory(directory.resolve("upstream-budget.json"));
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class);
        p.setDiskCacheDir(directory.resolve("zero").toString()); p.setHourlyUpstreamApiBudget(0);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
    }
    @Test void loopbackMockDoesNotTouchRealLedgerButEnforcementCanBeTested() {
        XOpsProperties p = properties(); p.setHourlyUpstreamApiBudget(0);
        URI mock = URI.create("http://127.0.0.1:9999/twitter/user/info");
        new UpstreamBudgetGuard(p).acquirePermit(mock, "twitterapi_io_read");
        assertThat(directory.resolve("upstream-budget.json")).doesNotExist();
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(mock, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class);
    }
    @Test void estimatesUseResourceCountsAndUnknownEndpointsCannotBypassAccounting() {
        assertThat(UpstreamBudgetGuard.estimateMicros(URI.create("https://api.twitterapi.io/twitter/article?tweet_id=1"),
                "twitterapi_io_read",200_000)).isEqualTo(1_000);
        assertThat(UpstreamBudgetGuard.estimateMicros(URI.create("https://api.twitterapi.io/twitter/tweets?tweet_ids=1%2C2%2C3"),
            "twitterapi_io_read", 200000)).isEqualTo(450);
        assertThat(UpstreamBudgetGuard.estimateMicros(URI.create("https://api.x.com/2/users/1/followers?max_results=200"),
            "x_official_read", 200000)).isEqualTo(2_000_000);
        assertThat(UpstreamBudgetGuard.estimateMicros(URI.create("https://api.x.com/2/tweets"),
            "x_tweet_create", 200000)).isEqualTo(200_000);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(properties()).acquirePermit(
            URI.create("https://api.twitterapi.io/new-paid-endpoint"), "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("未登记");
    }
    @Test void invalidBudgetCannotSilentlyDisableProtection() {
        XOpsProperties p = properties(); p.setDailyUpstreamUsdBudget("-1");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p)).isInstanceOf(IllegalArgumentException.class);
        p.setDailyUpstreamUsdBudget("NaN");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void providersStopBeforeHttpWhenSharedBudgetIsExhausted() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"data\":{\"id\":\"100\",\"username\":\"tester\",\"name\":\"Tester\",\"public_metrics\":{}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        XOpsProperties p = properties();
        p.setOfficialBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        p.setOfficialBearerToken("synthetic-test-token");
        p.setHourlyUpstreamApiBudget(1);
        SharedApiExecutor pool = new SharedApiExecutor(p);
        try {
            var guard = new UpstreamBudgetGuard(p, true);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var provider = new OfficialXApiV2Provider(p, mapper, pool, guard);
            assertThatThrownBy(() -> provider.fetchSnapshot("tester", null))
                .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
            var publisher = new XPublishClient(p, mapper, pool, guard);
            assertThatThrownBy(() -> publisher.createTweet("synthetic", "test", List.of()))
                .isInstanceOf(XPublishClient.XPublishException.class).hasMessageContaining("预算");
            assertThatThrownBy(() -> publisher.uploadMedia("synthetic", new byte[]{1}, "image/png", "test.png"))
                .isInstanceOf(XPublishClient.XPublishException.class).hasMessageContaining("预算");
            assertThat(calls).hasValue(1);
        } finally { pool.shutdown(); server.stop(0); }
    }

    @Test void disabledSnapshotDiskSupportsLocalMocksButNeverEnablesUnmeteredPaidCalls() {
        XOpsProperties p = properties();
        p.setDiskCacheDir("");
        var guard = new UpstreamBudgetGuard(p);
        guard.acquirePermit(URI.create("http://127.0.0.1:9999/twitter/user/info"), "twitterapi_io_read");
        assertThatThrownBy(() -> guard.acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("持久化预算目录");
    }

    @Test void twitterApiIoPageReadPropagatesBudgetLimitWithoutFakeTimeoutOrProfileLeak() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"status\":\"success\",\"data\":{\"id\":\"817386\",\"userName\":\"Techmeme\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        XOpsProperties p = properties();
        p.setTwitterapiIoKey("synthetic-key");
        p.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        // $0.0020 (2,000 micros) can afford /twitter/user/info (180 micros), but NOT /twitter/user/last_tweets (15,000 micros).
        p.setDailyUpstreamUsdBudget("0.0020");
        SharedApiExecutor pool = new SharedApiExecutor(p);
        try {
            var guard = new UpstreamBudgetGuard(p, true);
            assertThat(guard.canAfford(p.getTwitterapiIoBaseUrl() + "/twitter/user/info?userName=Techmeme", "twitterapi_io_read")).isTrue();
            assertThat(guard.canAfford(p.getTwitterapiIoBaseUrl() + "/twitter/user/last_tweets?userName=Techmeme", "twitterapi_io_read")).isFalse();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var provider = new TwitterApiIoProvider(p, mapper, pool, guard);
            assertThat(provider.isPageReadBudgetExhausted()).isTrue();
            assertThatThrownBy(() -> provider.fetchTextPostsPage("Techmeme", "817386", "", 100, Instant.now().plusSeconds(30)))
                    .isInstanceOfSatisfying(XDataProviderException.class, ex -> {
                        assertThat(ex.getStatusCode()).isEqualTo(429);
                        assertThat(ex.getMessage()).isEqualTo(UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
                    });
            assertThat(calls).hasValue(0);
            assertThat(guard.snapshotMetrics().get("budgetRejectedCalls")).isEqualTo(1L);
        } finally {
            pool.shutdown();
            server.stop(0);
        }
    }

    @Test void freeFxTwitterTimelineRunsWithoutChargingOrBlockingEvenWhenPaidBudgetIsExhausted() throws Exception {
        var paidCalls = new java.util.concurrent.atomic.AtomicInteger();
        var freeCalls = new java.util.concurrent.atomic.AtomicInteger();
        var paidServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.createContext("/", exchange -> {
            paidCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2/profile/Techmeme/statuses", exchange -> {
            freeCalls.incrementAndGet();
            String body = "{\"code\":200,\"results\":[{\"type\":\"status\",\"id\":\"2124458308216238209\","
                    + "\"text\":\"AI security news\",\"raw_text\":{\"text\":\"AI security news\",\"facets\":[]},"
                    + "\"created_timestamp\":1795345305,\"created_at\":\"Sun Nov 22 11:01:45 +0000 2026\","
                    + "\"likes\":3,\"replies\":5,\"reposts\":1,\"quotes\":0,\"bookmarks\":1,\"views\":2044,"
                    + "\"replying_to\":null,\"reposted_by\":null,\"media\":null,"
                    + "\"author\":{\"id\":\"817386\",\"screen_name\":\"Techmeme\",\"name\":\"Techmeme\","
                    + "\"followers\":428034,\"following\":1001,\"statuses\":213559,"
                    + "\"verification\":{\"verified\":true,\"type\":\"individual\"}}}],"
                    + "\"cursor\":{\"top\":null,\"bottom\":\"next-1\"}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/2/profile/Techmeme", exchange -> {
            freeCalls.incrementAndGet();
            String body = "{\"code\":200,\"user\":{\"id\":\"817386\",\"screen_name\":\"Techmeme\",\"name\":\"Techmeme\","
                    + "\"followers\":428034,\"following\":1001,\"statuses\":213559,"
                    + "\"verification\":{\"verified\":true,\"type\":\"individual\"}}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        paidServer.start();
        server.start();
        XOpsProperties p = properties();
        p.setTwitterapiIoKey("synthetic-key");
        p.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        p.setFxtwitterEnabled(true);
        p.setFxtwitterBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        p.setDailyUpstreamUsdBudget("0.0001"); // Exhausted for any paid read
        SharedApiExecutor pool = new SharedApiExecutor(p);
        try {
            var guard = new UpstreamBudgetGuard(p, true);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var provider = new TwitterApiIoProvider(p, mapper, pool, guard);
            assertThat(provider.isPageReadBudgetExhausted()).isTrue();
            assertThat(provider.canReadTimelineForFree("")).isTrue();
            var page = provider.fetchTextPostsPage("Techmeme", "817386", "", 100, Instant.now().plusSeconds(30));
            assertThat(page.posts()).hasSize(1);
            assertThat(page.posts().get(0).id()).isEqualTo("2124458308216238209");
            assertThat(page.nextCursor()).isEqualTo("fx:next-1");
            assertThat(paidCalls).hasValue(0);
            assertThat(freeCalls).hasValue(1);
            assertThat(guard.snapshotMetrics().get("budgetRejectedCalls")).isEqualTo(0L);
        } finally {
            pool.shutdown();
            paidServer.stop(0);
            server.stop(0);
        }
    }

    private static Clock clock(String time) { return Clock.fixed(Instant.parse(time), ZoneOffset.UTC); }
}
