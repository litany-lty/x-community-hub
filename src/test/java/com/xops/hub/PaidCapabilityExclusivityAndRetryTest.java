package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.assistant.AssistantStore;
import com.xops.hub.assistant.SourceCollector;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.model.RawSnapshotModels.InboundInteraction;
import com.xops.hub.model.RawSnapshotModels.InboundKind;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.PaidCapabilityGate;
import com.xops.hub.provider.PaidCapabilityGate.RetrySnapshot;
import com.xops.hub.provider.PaidCapabilityGate.RetryState;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.provider.XPublishClient.XPublishException;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaidCapabilityExclusivityAndRetryTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final class MutableClock extends Clock {
        private final AtomicLong epochMs;
        private final ZoneId zone;

        MutableClock(long initialEpochMs, ZoneId zone) {
            this.epochMs = new AtomicLong(initialEpochMs);
            this.zone = zone;
        }

        void advanceMillis(long deltaMs) {
            epochMs.addAndGet(deltaMs);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(epochMs.get(), zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMs.get());
        }
    }

    @Test
    void paidCapabilitiesAllowOnlyElidlicomAndBypassAllBudgetAccountingLimits(@TempDir Path dir) throws Exception {
        XOpsProperties props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setTwitterapiIoKey("test-paid-key");
        props.setFxtwitterEnabled(false);
        props.setHourlyUpstreamApiBudget(1);
        props.setDailyUpstreamApiBudget(1);
        props.setHourlyUpstreamUsdBudget("0.001");
        props.setDailyUpstreamUsdBudget("0.001");
        props.setMonthlyUpstreamUsdBudget("0.001");

        UpstreamBudgetGuard budgetGuard = new UpstreamBudgetGuard(props, true);
        URI paidReadUri = URI.create("https://api.twitterapi.io/twitter/user/last_tweets?userName=test");
        URI paidRelUri = URI.create("https://api.twitterapi.io/twitter/user/check_follow_relationship");
        URI paidPublishUri = URI.create("https://api.x.com/2/tweets");

        // 1. Non-elidlicom callers fail immediately with HTTP 403 across all paid categories
        for (String nonOwner : List.of("alice", "peer_rank_1", "")) {
            assertThatThrownBy(() -> PaidCapabilityGate.withCaller(nonOwner, "2001", () -> {
                budgetGuard.acquirePermit(paidReadUri, "twitterapi_io_read");
                return null;
            }))
                    .isInstanceOf(XDataProviderException.class)
                    .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));

            assertThatThrownBy(() -> PaidCapabilityGate.withCaller(nonOwner, "2001", () -> {
                budgetGuard.acquirePermit(paidRelUri, "twitterapi_io_relation");
                return null;
            }))
                    .isInstanceOf(XDataProviderException.class)
                    .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));

            assertThatThrownBy(() -> PaidCapabilityGate.withCaller(nonOwner, "2001", () -> {
                budgetGuard.acquirePermit(paidPublishUri, "x_tweet_create");
                return null;
            }))
                    .isInstanceOf(XDataProviderException.class)
                    .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));

            boolean canBg = PaidCapabilityGate.withCaller(
                    nonOwner, "2001", () -> budgetGuard.canAffordBackground(paidReadUri.toString(), "twitterapi_io_read"));
            assertThat(canBg).isFalse();
        }

        // Unscoped real (non-loopback) paid calls also fail closed
        assertThatThrownBy(() -> budgetGuard.acquirePermit(paidReadUri, "twitterapi_io_read"))
                .isInstanceOf(XDataProviderException.class)
                .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));

        // 2. @elidlicom bypasses ALL hourly call & daily/monthly USD budget caps without throttling
        PaidCapabilityGate.runWithCaller("elidlicom", "9000", () -> {
            for (int i = 0; i < 25; i++) {
                budgetGuard.acquirePermit(paidReadUri, "twitterapi_io_read");
                budgetGuard.acquirePermit(paidRelUri, "twitterapi_io_relation");
                budgetGuard.acquirePermit(paidPublishUri, "x_tweet_create");
            }
            assertThat(budgetGuard.canAffordBackground(paidReadUri.toString(), "twitterapi_io_read")).isTrue();
            assertThat(budgetGuard.canAffordBackground(paidRelUri.toString(), "twitterapi_io_relation")).isTrue();
        });
    }

    @Test
    void idempotentTenMinuteRetryExecutesAtMostThreeTimesAndScheduledTweetsRetryIdempotently(@TempDir Path dir) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T05:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"));
        PaidCapabilityGate gate = new PaidCapabilityGate(clock);

        // 1. Failing paid action retries strictly at +10m, +20m, +30m (max 3 attempts) and deduplicates identical keys
        AtomicInteger attempts = new AtomicInteger();
        List<String> observedCallerUsers = new ArrayList<>();
        Runnable failingPaidOp = () -> {
            attempts.incrementAndGet();
            observedCallerUsers.add(PaidCapabilityGate.currentScope() != null ? PaidCapabilityGate.currentScope().username() : "");
            throw new XDataProviderException(503, "TwitterAPI.io temporary 503");
        };

        RetrySnapshot firstReg = gate.scheduleIdempotentRetry(
                "op:elidlicom:batch-1", "twitterapi_io_read", "elidlicom", "9000", "initial 503", failingPaidOp);
        RetrySnapshot duplicateReg = gate.scheduleIdempotentRetry(
                "op:elidlicom:batch-1", "twitterapi_io_read", "elidlicom", "9000", "duplicate 503", failingPaidOp);
        assertThat(duplicateReg.nextRetryAtEpochMs()).isEqualTo(firstReg.nextRetryAtEpochMs());
        assertThat(duplicateReg.retryCount()).isZero();

        // Before 10 minutes: no retry fired
        clock.advanceMillis(9 * 60_000L + 59_000L);
        gate.runDueRetries();
        assertThat(attempts.get()).isZero();

        // Retry #1 at +10m
        clock.advanceMillis(1_000L);
        gate.runDueRetries();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(gate.getRetrySnapshot("op:elidlicom:batch-1").state()).isEqualTo(RetryState.PENDING);
        assertThat(gate.getRetrySnapshot("op:elidlicom:batch-1").retryCount()).isEqualTo(1);

        // Retry #2 at +20m
        clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
        gate.runDueRetries();
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(gate.getRetrySnapshot("op:elidlicom:batch-1").state()).isEqualTo(RetryState.PENDING);

        // Retry #3 at +30m -> transitions to EXHAUSTED
        clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
        gate.runDueRetries();
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(gate.getRetrySnapshot("op:elidlicom:batch-1").state()).isEqualTo(RetryState.EXHAUSTED);
        assertThat(observedCallerUsers).containsExactly("elidlicom", "elidlicom", "elidlicom");

        // +40m and re-registration of exhausted key do NOT execute a 4th attempt
        gate.scheduleIdempotentRetry(
                "op:elidlicom:batch-1", "twitterapi_io_read", "elidlicom", "9000", "late 503", failingPaidOp);
        clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
        gate.runDueRetries();
        assertThat(attempts.get()).isEqualTo(3);

        // 2. ScheduledTweetService: @elidlicom bypasses daily cap and retries transient 429/503 up to 3 times at 10m intervals
        XOpsProperties props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setSchedulerEnabled(false);

        XOAuthService oauthService = mock(XOAuthService.class);
        when(oauthService.getTokenCapability("9000"))
                .thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(oauthService.getValidWriteAccessToken("9000", false)).thenReturn("valid-write-token");

        AtomicInteger publishCalls = new AtomicInteger();
        XPublishClient publishClient = mock(XPublishClient.class);
        when(publishClient.createTweet(anyString(), anyString(), anyList())).thenAnswer(inv -> {
            int n = publishCalls.incrementAndGet();
            if (n <= 2) {
                throw new XPublishException(429, "X API upstream rate limit 429", false);
            }
            return "tw-published-ok";
        });

        CommunityHubService hubGate = new CommunityHubService(
                props, new DemoXDataProvider(), new TwitterApiIoProvider(props, objectMapper),
                new OfficialXApiV2Provider(props, objectMapper), objectMapper);
        ScheduledTweetService tweetService = new ScheduledTweetService(props, objectMapper, oauthService, publishClient, hubGate, clock);
        try {
            long firstAt = clock.millis() + 30_000L;
            long secondAt = firstAt + 40 * 60_000L;
            ScheduledTweetJob job1 = tweetService.createScheduledTweet("9000", "elidlicom", "First unmetered owner post", firstAt, "req-1", List.of());
            ScheduledTweetJob job2 = tweetService.createScheduledTweet("9000", "elidlicom", "Second unmetered owner post", secondAt, "req-2", List.of());
            assertThat(job2.id()).isNotEqualTo(job1.id());

            // Non-elidlicom user fails immediately with 403 when attempting to schedule
            assertThatThrownBy(() -> tweetService.createScheduledTweet("3001", "alice", "Blocked post", firstAt, "req-alice", List.of()))
                    .isInstanceOf(XDataProviderException.class)
                    .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));

            // Initial dispatch at firstAt -> fails with 429 -> scheduled for +10m retry #1
            clock.advanceMillis(35_000L);
            tweetService.processDueJobsForAccount("9000");
            ScheduledTweetJob afterInitialFail = tweetService.listJobs("9000", "elidlicom").items().stream()
                    .filter(j -> j.id().equals(job1.id())).findFirst().orElseThrow();
            assertThat(afterInitialFail.status()).isEqualTo(ScheduledTweetStatus.SCHEDULED);
            assertThat(afterInitialFail.attemptCount()).isEqualTo(1);
            assertThat(afterInitialFail.lastError()).contains("10 分钟后幂等重试");

            // Before +10m -> still waiting
            clock.advanceMillis(5 * 60_000L);
            tweetService.processDueJobsForAccount("9000");
            assertThat(publishCalls.get()).isEqualTo(1);

            // At +10m -> retry #1 fires -> fails with 429 -> scheduled for +20m retry #2
            clock.advanceMillis(5 * 60_000L + 1_000L);
            tweetService.processDueJobsForAccount("9000");
            assertThat(publishCalls.get()).isEqualTo(2);

            // At +20m -> retry #2 fires -> succeeds -> PUBLISHED
            clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
            tweetService.processDueJobsForAccount("9000");
            assertThat(publishCalls.get()).isEqualTo(3);
            ScheduledTweetJob published = tweetService.listJobs("9000", "elidlicom").items().stream()
                    .filter(j -> j.id().equals(job1.id())).findFirst().orElseThrow();
            assertThat(published.status()).isEqualTo(ScheduledTweetStatus.PUBLISHED);
            assertThat(published.publishedTweetId()).isEqualTo("tw-published-ok");
        } finally {
            tweetService.shutdown();
        }
    }

    @Test
    void elidlicomRetainsFullHistoryAndPaidDataWhileOtherAccountsKeepThirtyDayRollingWindow(@TempDir Path dir) throws Exception {
        Instant now = Instant.parse("2026-11-24T06:00:00Z");
        Instant ninetyDaysAgo = now.minus(Duration.ofDays(90));
        Instant tenDaysAgo = now.minus(Duration.ofDays(10));
        MutableClock clock = new MutableClock(now.toEpochMilli(), ZoneId.of("Asia/Shanghai"));

        XUserSummary owner = new XUserSummary("9000", "elidlicom", "Litany", "", "", true, 500, 100, 50, "");
        XUserSummary peer = new XUserSummary("3001", "alice_peer", "Alice", "", "", false, 100, 80, 20, "");

        List<RawTweet> sampleTweets = List.of(
                new RawTweet("old_90d", "90d tweet", ninetyDaysAgo, TweetKind.POST, "", null, List.of(), false, false, 50, 5, 1, 0, 0, false, "old_90d", ""),
                new RawTweet("recent_10d", "10d tweet", tenDaysAgo, TweetKind.POST, "", null, List.of(), false, false, 80, 8, 2, 1, 0, false, "recent_10d", "")
        );
        List<InboundInteraction> sampleInbound = List.of(
                new InboundInteraction("in_90d", peer, ninetyDaysAgo, InboundKind.REPLY_TO_ME, "old reply", "old_90d", "old_90d", "elidlicom"),
                new InboundInteraction("in_10d", peer, tenDaysAgo, InboundKind.REPLY_TO_ME, "recent reply", "recent_10d", "recent_10d", "elidlicom")
        );
        List<long[]> fullCoverage = List.of(new long[]{ninetyDaysAgo.getEpochSecond(), now.getEpochSecond()});

        // 1. @elidlicom retains 90-day tweets, paid inbound mentions, and full coverage without 30-day pruning
        AccountStore.StoredAccount ownerStored = AccountStore.merge(
                null, owner, now, sampleTweets, sampleInbound, fullCoverage, fullCoverage, null);
        AccountStore.StoredAccount ownerAfterPrune = AccountStore.pruneToRollingWindow(ownerStored, now.plus(Duration.ofDays(30)));
        assertThat(ownerAfterPrune.tweets()).extracting(AccountStore.StoredTweet::id).containsExactlyInAnyOrder("old_90d", "recent_10d");
        assertThat(ownerAfterPrune.inbound()).extracting(AccountStore.StoredInbound::id).containsExactlyInAnyOrder("in_90d", "in_10d");
        assertThat(ownerAfterPrune.tweetCoverage().get(0)[0]).isEqualTo(ninetyDaysAgo.getEpochSecond());

        // 2. Non-elidlicom account prunes >30-day data on merge and rolling-window check
        AccountStore.StoredAccount peerStored = AccountStore.merge(
                null, peer, now, sampleTweets, sampleInbound, fullCoverage, fullCoverage, null);
        assertThat(peerStored.tweets()).extracting(AccountStore.StoredTweet::id).containsExactly("recent_10d");
        assertThat(peerStored.inbound()).extracting(AccountStore.StoredInbound::id).containsExactly("in_10d");

        // 3. SourceCollector retires & hides non-owner history_source rows (@iamtheqwen) while keeping @elidlicom active
        AssistantStore assistantStore = new AssistantStore(dir.resolve("assistant"), objectMapper);
        assistantStore.tx(tx -> {
            tx.put("meta", "owner", objectMapper.createObjectNode().put("accountId", "9000").put("username", "elidlicom"));
            tx.put("meta", "settings", objectMapper.createObjectNode().put("enabled", true).put("collectionEnabled", true));
            tx.put("history_source", "7001", objectMapper.createObjectNode()
                    .put("authorId", "7001").put("username", "iamtheqwen").put("owner", false).put("rank", 1)
                    .put("eligible", true).put("inboundCursor", "cur-1").put("inboundError", "HTTP 503"));
            tx.put("history_source", "9000", objectMapper.createObjectNode()
                    .put("authorId", "9000").put("username", "elidlicom").put("owner", true).put("rank", 0)
                    .put("eligible", true).put("timelineError", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE)
                    .put("timelineBackfillComplete", true).put("inboundBackfillComplete", true)
                    .put("lastTimelineCheckAt", System.currentTimeMillis())
                    .put("lastInboundCheckAt", System.currentTimeMillis()));
            return null;
        });

        CommunityHubService hubMock = mock(CommunityHubService.class);
        when(hubMock.longHistoryCandidates()).thenReturn(List.of(
                new CommunityHubService.LongHistoryCandidate(owner, 0, true, 30, false, now.toEpochMilli())
        ));
        TwitterApiIoProvider providerMock = mock(TwitterApiIoProvider.class);
        SourceCollector collector = new SourceCollector(assistantStore, providerMock, hubMock, objectMapper);
        try {
            ReflectionTestUtils.invokeMethod(collector, "tick");
            var historyView = collector.history();
            assertThat(historyView.path("sources")).hasSize(1);
            assertThat(historyView.path("sources").get(0).path("username").asText()).isEqualTo("elidlicom");
            assertThat(historyView.path("sources").get(0).path("timelineError").asText()).isEmpty();

            var retiredPeer = assistantStore.tx(tx -> tx.get("history_source", "7001"));
            assertThat(retiredPeer.path("eligible").asBoolean()).isFalse();
            assertThat(retiredPeer.path("inboundCursor").asText()).isEmpty();
        } finally {
            ReflectionTestUtils.invokeMethod(collector, "stop");
        }

        // 4. CommunityHubService blocks paid syncs for non-owner when PaidCapabilityGate is wired and FxTwitter free read is disabled
        HttpServer mockPaidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger serverHits = new AtomicInteger();
        mockPaidServer.createContext("/", ex -> {
            serverHits.incrementAndGet();
            byte[] ok = "{\"data\":{\"tweets\":[]}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        mockPaidServer.start();
        SharedApiExecutor exec = null;
        try {
            XOpsProperties hubProps = new XOpsProperties();
            hubProps.setDiskCacheDir(dir.resolve("hub").toString());
            hubProps.setDefaultProvider("TWITTERAPI_IO");
            hubProps.setTwitterapiIoKey("k");
            hubProps.setTwitterapiIoBaseUrl("http://127.0.0.1:" + mockPaidServer.getAddress().getPort());
            hubProps.setFxtwitterEnabled(false);

            exec = new SharedApiExecutor(hubProps);
            UpstreamBudgetGuard guard = new UpstreamBudgetGuard(hubProps, true);
            TwitterApiIoProvider provider = new TwitterApiIoProvider(hubProps, objectMapper, exec, guard);
            CommunityHubService hub = new CommunityHubService(
                    hubProps, new DemoXDataProvider(), provider, new OfficialXApiV2Provider(hubProps, objectMapper),
                    objectMapper, null, assistantStore);
            ReflectionTestUtils.setField(hub, "paidCapabilityGate", new PaidCapabilityGate(clock));
            try {
                assertThatThrownBy(() -> hub.analyzeWorkspaceBundle(
                        "alice_peer", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "3001", false))
                        .isInstanceOf(XDataProviderException.class)
                        .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));
                assertThat(serverHits.get()).isZero();
            } finally {
                hub.stopBackgroundRefreshes();
            }
        } finally {
            if (exec != null) exec.shutdown();
            mockPaidServer.stop(0);
        }

        // 5. Static HTML hides paid feature entry points by default for public & non-elidlicom visitors
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("data-tab=\"follow-audit\" data-paid-entry type=\"button\" hidden");
        assertThat(html).contains("data-tab=\"schedule\" data-paid-entry type=\"button\" hidden");
        assertThat(html).contains("id=\"scheduleShareCardBtn\" data-paid-entry hidden");
    }

    @Test
    void freeApiCallsCoalesceInFlightRetryTransientFailuresAndRunTenMinuteIdempotentRetries(@TempDir Path dir) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T07:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"));

        // 1. FxTwitterClient: transient 502/429 retries idempotently, 404 does NOT retry, and concurrent identical GETs coalesce
        AtomicInteger profileHits = new AtomicInteger();
        AtomicInteger notFoundHits = new AtomicInteger();
        AtomicInteger coalescedHits = new AtomicInteger();
        java.util.concurrent.CountDownLatch inFlightEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseInFlight = new java.util.concurrent.CountDownLatch(1);

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        freeServer.createContext("/2/profile/transient_user", ex -> {
            int n = profileHits.incrementAndGet();
            if (n == 1) {
                byte[] err = "{\"code\":502}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(502, err.length);
                ex.getResponseBody().write(err);
                ex.close();
                return;
            }
            if (n == 2) {
                byte[] err = "{\"code\":429}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(429, err.length);
                ex.getResponseBody().write(err);
                ex.close();
                return;
            }
            byte[] ok = ("{\"code\":200,\"user\":{\"id\":\"5001\",\"screen_name\":\"transient_user\",\"name\":\"Transient\","
                    + "\"followers\":12,\"following\":5,\"statuses\":3,\"likes\":1,\"media_count\":0,\"joined\":\"2024\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        freeServer.createContext("/2/status/404888", ex -> {
            notFoundHits.incrementAndGet();
            byte[] nf = "{\"code\":404}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, nf.length);
            ex.getResponseBody().write(nf);
            ex.close();
        });
        freeServer.createContext("/2/profile/coalesced_user", ex -> {
            coalescedHits.incrementAndGet();
            inFlightEntered.countDown();
            try {
                releaseInFlight.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            byte[] ok = ("{\"code\":200,\"user\":{\"id\":\"5002\",\"screen_name\":\"coalesced_user\",\"name\":\"Coalesced\","
                    + "\"followers\":20,\"following\":8,\"statuses\":6,\"likes\":2,\"media_count\":0,\"joined\":\"2024\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        freeServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setDiskCacheDir(dir.resolve("free-cache").toString());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");
        props.setApiPoolCoreSize(4);
        props.setApiPoolMaxSize(4);
        SharedApiExecutor pool = new SharedApiExecutor(props);
        try {
            com.xops.hub.provider.FxTwitterClient fxClient = new com.xops.hub.provider.FxTwitterClient(props, objectMapper, pool);

            // Transient 502 -> 429 -> 200 succeeds cleanly via inline idempotent retry
            var recovered = fxClient.fetchAsync(
                    "https://api.twitterapi.io/twitter/user/info?userName=transient_user",
                    Instant.now().plusSeconds(5),
                    null).join();
            assertThat(recovered.path("data").path("id").asText()).isEqualTo("5001");
            assertThat(profileHits.get()).isEqualTo(3);

            // Non-retryable 404 fails in a single attempt without wasting retries
            var metrics404 = fxClient.fetchAsync(
                    "https://api.twitterapi.io/twitter/tweets?tweet_ids=404888",
                    Instant.now().plusSeconds(5),
                    null).join();
            assertThat(metrics404.path("__incomplete_ids")).hasSize(1);
            assertThat(notFoundHits.get()).isEqualTo(1);

            // Concurrent identical GETs coalesce onto a single upstream HTTP call
            var f1 = fxClient.fetchAsync(
                    "https://api.twitterapi.io/twitter/user/info?userName=coalesced_user",
                    Instant.now().plusSeconds(5),
                    null);
            assertThat(inFlightEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var f2 = fxClient.fetchAsync(
                    "https://api.twitterapi.io/twitter/user/info?userName=coalesced_user",
                    Instant.now().plusSeconds(5),
                    null);
            releaseInFlight.countDown();
            assertThat(f1.join().path("data").path("id").asText()).isEqualTo("5002");
            assertThat(f2.join().path("data").path("id").asText()).isEqualTo("5002");
            assertThat(coalescedHits.get()).isEqualTo(1);

            // 2. Non-elidlicom free API 10-minute idempotent retry executes for free category while paid categories stay blocked
            PaidCapabilityGate gate = new PaidCapabilityGate(clock);
            UpstreamBudgetGuard budgetGuard = new UpstreamBudgetGuard(props, true);
            AtomicInteger freeRetryRuns = new AtomicInteger();
            URI paidUri = URI.create("https://api.twitterapi.io/twitter/user/last_tweets?userName=alice_peer");

            gate.scheduleIdempotentRetry(
                    "workspace-free-sync:alice_peer:2026-11-24",
                    "fxtwitter_free_read",
                    "alice_peer",
                    "3001",
                    "公开数据服务暂不可用（HTTP 503）",
                    () -> {
                        int run = freeRetryRuns.incrementAndGet();
                        // Inside non-owner free retry scope, any paid category MUST STILL fail closed with 403!
                        assertThatThrownBy(() -> budgetGuard.acquirePermit(paidUri, "twitterapi_io_read"))
                                .isInstanceOf(XDataProviderException.class)
                                .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));
                        if (run == 1) {
                            throw new XDataProviderException(503, "FxTwitter still 503");
                        }
                    }
            );

            clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
            gate.runDueRetries();
            assertThat(freeRetryRuns.get()).isEqualTo(1);
            assertThat(gate.getRetrySnapshot("workspace-free-sync:alice_peer:2026-11-24").state()).isEqualTo(RetryState.PENDING);

            clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
            gate.runDueRetries();
            assertThat(freeRetryRuns.get()).isEqualTo(2);
            assertThat(gate.getRetrySnapshot("workspace-free-sync:alice_peer:2026-11-24").state()).isEqualTo(RetryState.SUCCEEDED);
        } finally {
            releaseInFlight.countDown();
            pool.shutdown();
            freeServer.stop(0);
        }
    }
}
