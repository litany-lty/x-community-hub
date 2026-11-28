package com.xops.hub.provider;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.service.XOAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpstreamBudgetGuardTest {
    @TempDir Path directory;
    private static final URI PROFILE = URI.create("http://127.0.0.1:9999/twitter/user/info?userName=test");
    private static final URI EXTERNAL_PAID_PROFILE = URI.create("https://api.twitterapi.io/twitter/user/info?userName=test");
    private XOpsProperties properties() {
        XOpsProperties p = new XOpsProperties();
        p.setDiskCacheDir(directory.toString());
        p.setHourlyUpstreamApiBudget(1000);
        p.setDailyUpstreamApiBudget(2000);
        return p;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void hourlyBudgetRollsAtLocalHourEvenAfterRestart(boolean countCap) {
        var p = properties();
        p.setTimezone("Asia/Kolkata");
        if (countCap) p.setHourlyUpstreamApiBudget(1);
        else p.setHourlyUpstreamUsdBudget("0.000180");
        var first = new UpstreamBudgetGuard(p, true, clock("2026-11-28T06:50:00Z"));
        first.acquirePermit(PROFILE, "twitterapi_io_read");
        var sameHour = new UpstreamBudgetGuard(p, true, clock("2026-11-28T07:05:00Z"));
        assertThatThrownBy(() -> sameHour.acquirePermit(PROFILE, "twitterapi_io_read"))
                .isInstanceOf(XDataProviderException.class);
        var next = new UpstreamBudgetGuard(p, true, clock("2026-11-28T07:30:00Z"));
        next.acquirePermit(PROFILE, "twitterapi_io_read");
        assertThat(next.snapshotMetrics().get("totalBillableCalls")).isEqualTo(2L);
    }

    @Test void oldUtcBucketKeepsItsReservationsWhenTheLocalHourOverlaps() throws Exception {
        var p = properties();
        p.setTimezone("Asia/Kolkata");
        p.setHourlyUpstreamApiBudget(1);
        var at = Instant.parse("2026-11-28T06:50:00Z");
        new UpstreamBudgetGuard(p, true, Clock.fixed(at, ZoneOffset.UTC)).acquirePermit(PROFILE, "twitterapi_io_read");
        var file = directory.resolve("upstream-budget.json");
        var json = new ObjectMapper();
        var old = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(file.toFile());
        json.writeValue(file.toFile(), old.put("version", 1).put("hour", at.getEpochSecond() / 3600));
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true, clock("2026-11-28T07:05:00Z"))
                .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
        new UpstreamBudgetGuard(p, true, clock("2026-11-28T07:30:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThat(json.readTree(file.toFile()).path("totalCalls").asLong()).isEqualTo(2);
    }
    @Test void concurrentGuardInstancesShareDurableAdmissionAndCannotOverspend() throws Exception {
        XOpsProperties p = properties();
        p.setHourlyUpstreamApiBudget(7);
        List<UpstreamBudgetGuard> guards = List.of(new UpstreamBudgetGuard(p, true), new UpstreamBudgetGuard(p, true));
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
            assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
                .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
        } finally { executor.shutdownNow(); }
    }
    @Test void moneyIsReservedBeforeSendingAndSurvivesRestart() {
        XOpsProperties p = properties();
        p.setHourlyUpstreamUsdBudget("0.00035");
        new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
    }
    @Test void dailyAndMonthlyLimitsSurviveHourAndDayRollover() {
        XOpsProperties p = properties();
        p.setDailyUpstreamUsdBudget("0.00035");
        p.setMonthlyUpstreamUsdBudget("0.00035");
        new UpstreamBudgetGuard(p, true, clock("2026-11-15T01:00:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true, clock("2026-11-15T02:00:00Z"))
            .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true, clock("2026-11-16T01:00:00Z"))
            .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
        new UpstreamBudgetGuard(p, true, clock("2026-12-01T01:00:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
    }
    @Test void backwardsClockDoesNotResetConsumedBudget() {
        XOpsProperties p = properties(); p.setHourlyUpstreamApiBudget(1);
        new UpstreamBudgetGuard(p, true, clock("2026-11-15T02:00:00Z")).acquirePermit(PROFILE, "twitterapi_io_read");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true, clock("2026-11-15T01:00:00Z"))
            .acquirePermit(PROFILE, "twitterapi_io_read")).isInstanceOf(XDataProviderException.class);
    }
    @Test void corruptOrMissingInitializedLedgerBlocksCallsInsteadOfResetting() throws Exception {
        XOpsProperties p = properties(); new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read");
        Path ledger = directory.resolve("upstream-budget.json");
        Files.writeString(ledger, "{\"version\":1");
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("记录暂不可用");
        Files.delete(ledger);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("记录暂不可用");
    }
    @Test void invalidLedgerPathStopsAdmissionAndZeroMeansNoSpending() throws Exception {
        XOpsProperties p = properties(); Files.createDirectory(directory.resolve("upstream-budget.json"));
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class);
        p.setDiskCacheDir(directory.resolve("zero").toString()); p.setHourlyUpstreamApiBudget(0);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
    }
    @Test void loopbackMockDoesNotTouchRealLedgerButEnforcementCanBeTested() {
        XOpsProperties p = properties(); p.setHourlyUpstreamApiBudget(0);
        URI mock = URI.create("http://127.0.0.1:9999/twitter/user/info");
        new UpstreamBudgetGuard(p).acquirePermit(mock, "twitterapi_io_read");
        assertThat(directory.resolve("upstream-budget.json")).doesNotExist();
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(mock, "twitterapi_io_read"))
            .isInstanceOf(XDataProviderException.class);
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(EXTERNAL_PAID_PROFILE, "twitterapi_io_read"))
            .isInstanceOfSatisfying(XDataProviderException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(403));
        PaidCapabilityGate.withCaller("elidlicom", "budget-test-owner", true, () -> {
            assertThatThrownBy(() -> new UpstreamBudgetGuard(p).acquirePermit(EXTERNAL_PAID_PROFILE, "twitterapi_io_read"))
                    .isInstanceOf(XDataProviderException.class).hasMessageContaining("预算");
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"hourCalls","dayCalls","hourUsd","dayUsd","monthUsd"})
    void anyZeroLimitClosesOwnerAdmissionAndPreflight(String zero) {
        XOpsProperties p = properties();
        switch (zero) {
            case "hourCalls" -> p.setHourlyUpstreamApiBudget(0);
            case "dayCalls" -> p.setDailyUpstreamApiBudget(0);
            case "hourUsd" -> p.setHourlyUpstreamUsdBudget("0");
            case "dayUsd" -> p.setDailyUpstreamUsdBudget("0");
            case "monthUsd" -> p.setMonthlyUpstreamUsdBudget("0");
        }
        var guard = new UpstreamBudgetGuard(p, true);
        PaidCapabilityGate.runWithCaller("elidlicom","101", true,() -> {
            assertThat(guard.canAfford(PROFILE.toString(),"twitterapi_io_read")).isFalse();
            assertThat(guard.canAffordRequests(PROFILE.toString(),"twitterapi_io_read",2)).isFalse();
            assertThat(guard.canAffordBackground(PROFILE.toString(),"twitterapi_io_read")).isFalse();
            assertThatThrownBy(() -> guard.acquirePermit(PROFILE,"twitterapi_io_read")).isInstanceOf(XDataProviderException.class)
                    .hasMessageContaining("预算");
            assertThat(guard.snapshotMetrics().get("totalBillableCalls")).isEqualTo(0L);
        });
    }

    @Test void oauthSpendsSharedBudgetAndRestartedOwnerCannotDispatchTweetOrUpload() throws Exception {
        var p = properties();
        p.setHourlyUpstreamApiBudget(2);
        p.setOauthClientId("local-client");
        p.setOfficialBaseUrl("http://127.0.0.1:9999/2");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var transport = mock(HttpClient.class);
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        when(transport.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(call -> {
            URI endpoint = ((HttpRequest)call.getArgument(0)).uri();
            assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
            paths.add(endpoint.getPath());
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            String body = switch (endpoint.getPath()) {
                case "/2/oauth2/token" -> "{\"access_token\":\"synthetic\",\"token_type\":\"bearer\",\"refresh_token\":\"refresh\",\"expires_in\":3600,\"scope\":\"users.read tweet.read tweet.write media.write offline.access\"}";
                case "/2/users/me" -> "{\"data\":{\"id\":\"101\",\"username\":\"elidlicom\",\"name\":\"Owner\"}}";
                default -> "{\"data\":{\"id\":\"999\"}}";
            };
            when(response.body()).thenReturn(body);
            return response;
        });
        var pool = new SharedApiExecutor(p);
        try {
            var guard = new UpstreamBudgetGuard(p, true);
            var oauth = new XOAuthService(p,mapper,pool,guard);
            ReflectionTestUtils.setField(oauth,"httpClient",transport);
            var request = oauth.createPublishAuthorizationRequest("http://localhost:8080","101");
            PaidCapabilityGate.runWithCaller("elidlicom","101", true, () -> oauth.handleCallbackResult("local-code",request.state()));
            assertThat(paths).containsExactly("/2/oauth2/token","/2/users/me");
            var restarted = new UpstreamBudgetGuard(p,true);
            var restartedOauth = new XOAuthService(p,mapper,pool,restarted);
            ReflectionTestUtils.setField(restartedOauth,"httpClient",transport);
            var publisher = new XPublishClient(p,mapper,pool,restarted);
            ReflectionTestUtils.setField(publisher,"httpClient",transport);
            var started = new java.util.concurrent.atomic.AtomicBoolean();
            PaidCapabilityGate.runWithCaller("elidlicom","101", true,() -> {
                assertThat(restartedOauth.getValidWriteAccessToken("101",true)).isEqualTo("synthetic");
                assertThatThrownBy(() -> XPublishClient.withPublicationReceipt("text",null,() -> started.set(true),
                        () -> publisher.createTweet("synthetic","text",List.of())))
                        .isInstanceOfSatisfying(XPublishClient.XPublishException.class, e -> assertThat(e.isDeliveryUncertain()).isFalse());
                assertThatThrownBy(() -> publisher.uploadMedia("synthetic", new byte[]{1}, "image/png","file.png"))
                        .isInstanceOfSatisfying(XPublishClient.XPublishException.class, e -> assertThat(e.isDeliveryUncertain()).isFalse());
                assertThat(started).isFalse();
                var tokens = new com.xops.hub.store.XOAuthTokenStore(p,mapper);
                tokens.save(new com.xops.hub.store.XOAuthTokenStore.StoredOAuthToken("101","elidlicom","expired","refresh",
                        1L,XOAuthService.PUBLISH_OAUTH_SCOPE,1L));
                assertThatThrownBy(() -> restartedOauth.getValidWriteAccessToken("101",true)).isInstanceOf(XDataProviderException.class)
                        .hasMessageContaining("预算");
                assertThat(tokens.load("101").refreshToken()).isEqualTo("refresh");
            });
            assertThat(paths).containsExactly("/2/oauth2/token","/2/users/me");
            assertThat(restarted.snapshotMetrics().get("totalBillableCalls")).isEqualTo(2L);
        } finally { pool.shutdown(); }
    }

    @Test void backgroundMediaRetryIsChargedPerTransmittedStepAndStopsMidUpload() throws Exception {
        var p = properties();
        p.setHourlyUpstreamApiBudget(2);
        p.setOfficialBaseUrl("http://127.0.0.1:9999/2");
        var guard = new UpstreamBudgetGuard(p,true);
        PaidCapabilityGate.runWithCaller("elidlicom","101", true,() -> {
            guard.requireAffordable(p.getOfficialBaseUrl()+"/media/upload/initialize","x_media_upload");
            guard.requireAffordable(p.getOfficialBaseUrl()+"/media/upload/initialize","x_media_upload");
        });
        assertThat(guard.snapshotMetrics().get("totalBillableCalls")).isEqualTo(0L);
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1L);
        var retries = new PaidCapabilityGate(clock);
        var pool = new SharedApiExecutor(p);
        try {
            var publisher = new XPublishClient(p,new ObjectMapper(),pool,guard);
            var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var http = mock(HttpClient.class);
            when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(call -> {
                var uri = ((HttpRequest)call.getArgument(0)).uri();
                assertThat(uri.getHost()).isEqualTo("127.0.0.1");
                paths.add(uri.getPath());
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn("{\"data\":{\"id\":\"99\"}}");
                return response;
            });
            ReflectionTestUtils.setField(publisher,"httpClient",http);
            retries.scheduleIdempotentRetry("upload-retry","x_media_upload","elidlicom","101","local fixture",
                    () -> publisher.uploadMedia("synthetic",new byte[]{1},"image/png","image.png"), false, true);
            when(clock.millis()).thenReturn(1L+PaidCapabilityGate.RETRY_INTERVAL_MS);
            retries.runDueRetries();
            assertThat(paths).containsExactly("/2/media/upload/initialize","/2/media/upload/99/append");
            assertThat(retries.getRetrySnapshot("upload-retry").retryCount()).isEqualTo(1);
            when(clock.millis()).thenReturn(1L+2*PaidCapabilityGate.RETRY_INTERVAL_MS);
            retries.runDueRetries();
            assertThat(paths).hasSize(2);
            assertThat(guard.snapshotMetrics().get("totalBillableCalls")).isEqualTo(2L);
            assertThat(guard.snapshotMetrics().get("callsByCategory")).isEqualTo(java.util.Map.of("x_media_upload",2L));
        } finally { pool.shutdown(); }
    }

    @Test void paidFallbackKeepsItsOriginalCallerWhenAFreeRequestCompletesOnAnotherThread() {
        var guard = new UpstreamBudgetGuard(properties(), true);
        var freeRequest = new CompletableFuture<String>();
        var fallback = PaidCapabilityGate.withCaller("elidlicom","101", true, () ->
                TwitterApiIoProvider.handleComposeCancellable(freeRequest, (response, error) -> {
                    guard.acquirePermit(EXTERNAL_PAID_PROFILE,"twitterapi_io_read");
                    return CompletableFuture.completedFuture(PaidCapabilityGate.currentScope().accountId());
                }));
        PaidCapabilityGate.runWithCaller("reader","202",() -> {
            freeRequest.complete("fallback");
            assertThat(PaidCapabilityGate.currentScope().accountId()).isEqualTo("202");
        });
        assertThat(fallback.join()).isEqualTo("101");
        assertThat(guard.snapshotMetrics().get("totalBillableCalls")).isEqualTo(1L);
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
        assertThatThrownBy(() -> new UpstreamBudgetGuard(properties(), true).acquirePermit(
            URI.create("http://127.0.0.1:9999/new-paid-endpoint"), "twitterapi_io_read"))
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
        assertThatThrownBy(() -> new UpstreamBudgetGuard(p, true).acquirePermit(PROFILE, "twitterapi_io_read"))
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
