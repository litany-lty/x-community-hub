package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.RawSnapshotModels.InboundInteraction;
import com.xops.hub.model.RawSnapshotModels.InboundKind;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.PaidCapabilityGate;
import com.xops.hub.provider.PaidCapabilityGate.RetryState;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class ElidlicomDataBusinessGuaranteesTest {

    private final ObjectMapper mapper = new ObjectMapper()
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
    void guarantee1And3_immediacyWithoutHourlyCacheLockAndIncrementalDeltaSpeed(@TempDir Path dir) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AtomicBoolean publishBreakingTweet = new AtomicBoolean(false);
        AtomicLong secondLiveTweetEpoch = new AtomicLong(0L);
        AtomicInteger freeProfileHits = new AtomicInteger();
        AtomicInteger freeSearchHits = new AtomicInteger();
        AtomicInteger freeMetricHits = new AtomicInteger();
        AtomicInteger paidGraphHits = new AtomicInteger();

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.setExecutor(Executors.newCachedThreadPool());
        freeServer.createContext("/2/profile/", ex -> {
            freeProfileHits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            String uname = path.substring(path.lastIndexOf('/') + 1);
            String uid = "elidlicom".equalsIgnoreCase(uname) ? "9000" : "3001";
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.set("user", fxUser(uid, uname, 1280, 310, 88));
            writeJson(ex, 200, body);
        });
        freeServer.createContext("/2/search", ex -> {
            freeSearchHits.incrementAndGet();
            String q = ex.getRequestURI().getRawQuery() == null ? "" : java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.putObject("cursor").put("bottom", "");
            ArrayNode results = body.putArray("results");
            if (q.contains("from:elidlicom")) {
                long secEpoch = secondLiveTweetEpoch.get();
                if (secEpoch > 0L) {
                    results.add(fxStatus("990006", "9000", "elidlicom", "第二次秒级新增推文", secEpoch, 600, 40, 6, 3, 9));
                }
                if (publishBreakingTweet.get() && q.contains("since_time:" + lastSyncEpochFromQuery(q))) {
                    long sinceSec = Long.parseLong(lastSyncEpochFromQuery(q));
                    if (now.getEpochSecond() - 2 >= sinceSec) {
                        results.add(fxStatus("990001", "9000", "elidlicom", "刚刚发布的新动态", now.getEpochSecond() - 2, 500, 30, 5, 2, 8));
                    }
                }
            } else if (q.contains("from:peer_cached")) {
                if (publishBreakingTweet.get()) {
                    results.add(fxStatus("990005", "3001", "peer_cached", "普通用户新动态", now.getEpochSecond() - 2, 100, 5, 1, 0, 1));
                }
            }
            writeJson(ex, 200, body);
        });
        freeServer.createContext("/2/status/", ex -> {
            freeMetricHits.incrementAndGet();
            String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.set("status", fxStatus(id, "9000", "elidlicom", "cached post", now.getEpochSecond() - 3600, 1200, 66, 8, 4, 12));
            writeJson(ex, 200, body);
        });
        freeServer.start();

        HttpServer paidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.setExecutor(Executors.newCachedThreadPool());
        paidServer.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.contains("followings") || path.contains("followers")) {
                paidGraphHits.incrementAndGet();
            }
            ObjectNode ok = mapper.createObjectNode().put("has_next_page", false);
            ok.putArray("followings");
            ok.putArray("followers");
            ok.putArray("tweets");
            ok.putArray("users");
            writeJson(ex, 200, ok);
        });
        paidServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setTwitterapiIoKey("owner-paid-key");
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");

        // Pre-seed @elidlicom with 90-day full history + a recent 24h post synced 30 seconds ago (with complete graph & fresh metrics)
        XUserSummary owner = new XUserSummary("9000", "elidlicom", "Litany", "", "", true, 1280, 310, 88, "2020");
        Instant lastSync = now.minusSeconds(30);
        RawTweet hist90d = new RawTweet("990003", "90天前历史推文", now.minus(Duration.ofDays(90)),
                TweetKind.POST, "", null, List.of(), false, false, 3000, 120, 15, 9, 25, false, "990003", "");
        RawTweet recent1h = new RawTweet("990002", "1小时前已缓存推文", now.minusSeconds(3600),
                TweetKind.POST, "", null, List.of(), false, false, 1200, 66, 8, 4, 12, false, "990002", "");
        AccountStore.GraphUpdate seededGraph = new AccountStore.GraphUpdate(
                List.of(), List.of(), Set.of(), false, false, false, false);
        // Set follower/following counts to 0 on seeded owner so empty graph matches 0/0 without truncation, then update server to match
        XUserSummary exactOwner = new XUserSummary("9000", "elidlicom", "Litany", "", "", true, 0, 0, 88, "2020");
        AccountStore.StoredAccount seededOwner = AccountStore.merge(
                null, exactOwner, lastSync, List.of(hist90d, recent1h), List.of(),
                List.of(new long[]{now.minus(Duration.ofDays(90)).getEpochSecond(), lastSync.getEpochSecond()}),
                List.of(new long[]{now.minus(Duration.ofDays(90)).getEpochSecond(), lastSync.getEpochSecond()}),
                seededGraph, Map.of("990002", lastSync.getEpochSecond(), "990003", lastSync.getEpochSecond()));
        seededOwner = AccountStore.withMetricAttempts(seededOwner, List.of("990002"), lastSync);
        seededOwner = AccountStore.withDailyRefreshState(seededOwner, lastSync.getEpochSecond(), lastSync.getEpochSecond());
        AccountStore.write(dir.resolve("users/by-id/9000/store_v" + AccountStore.VERSION + ".json.gz"), seededOwner, mapper);

        // Also seed @peer_cached synced 30 seconds ago with completed daily state (subject to 1-hour cache restriction)
        XUserSummary peer = new XUserSummary("3001", "peer_cached", "Peer", "", "", false, 0, 0, 10, "2023");
        AccountStore.StoredAccount seededPeer = AccountStore.merge(
                null, peer, lastSync, List.of(), List.of(),
                List.of(new long[]{now.minus(Duration.ofHours(25)).getEpochSecond(), lastSync.getEpochSecond()}),
                List.of(new long[]{now.minus(Duration.ofHours(25)).getEpochSecond(), lastSync.getEpochSecond()}),
                seededGraph, Map.of());
        seededPeer = AccountStore.withDailyRefreshState(seededPeer, lastSync.getEpochSecond(), lastSync.getEpochSecond());
        AccountStore.write(dir.resolve("users/by-id/3001/store_v" + AccountStore.VERSION + ".json.gz"), seededPeer, mapper);

        // Make free profile match 0/0 counts so graph reuse is exact
        freeServer.removeContext("/2/profile/");
        freeServer.createContext("/2/profile/", ex -> {
            freeProfileHits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            String uname = path.substring(path.lastIndexOf('/') + 1);
            String uid = "elidlicom".equalsIgnoreCase(uname) ? "9000" : "3001";
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.set("user", fxUser(uid, uname, 0, 0, 88));
            writeJson(ex, 200, body);
        });

        SharedApiExecutor pool = new SharedApiExecutor(props);
        MutableClock clock = new MutableClock(now.toEpochMilli(), ZoneId.of("Asia/Shanghai"));
        UpstreamBudgetGuard guard = new UpstreamBudgetGuard(props, true);
        TwitterApiIoProvider provider = new TwitterApiIoProvider(props, mapper, pool, guard);
        CommunityHubService hub = new CommunityHubService(
                props, new DemoXDataProvider(), provider, new OfficialXApiV2Provider(props, mapper), mapper);
        ReflectionTestUtils.setField(hub, "paidCapabilityGate", new PaidCapabilityGate(clock));
        try {
            // Publish a new tweet 2 seconds ago (only 30s after lastSync -> inside 1h cache window!)
            publishBreakingTweet.set(true);

            // Non-owner (@peer_cached) is restricted by the 1-hour cache window -> returns cached bundle without hitting upstream
            MultiPeriodDashboardBundle peerBundle = hub.analyzeWorkspaceBundle(
                    "peer_cached", ReportPeriod.HOURS_24, "TWITTERAPI_IO", true, "127.0.0.1", "3001", false);
            assertThat(peerBundle.hours24().fromCache()).isTrue();
            assertThat(peerBundle.hours24().activity().originalPosts()).isZero();

            // Guarantee 3 (Fast SWR read): preferStored=true serves @elidlicom cached store immediately and triggers background delta sync
            long swrStartNs = System.nanoTime();
            MultiPeriodDashboardBundle fastSwr = hub.analyzeWorkspaceBundle(
                    "elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "9000", false, true);
            long swrMs = Duration.ofNanos(System.nanoTime() - swrStartNs).toMillis();
            assertThat(fastSwr.hours24().fromCache()).isTrue();
            assertThat(swrMs).isLessThan(500L);

            long waitDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!"SUCCEEDED".equals(hub.workspaceRefreshStatus("elidlicom", "TWITTERAPI_IO", "9000").state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(15L);
            }
            assertThat(hub.workspaceRefreshStatus("elidlicom", "TWITTERAPI_IO", "9000").state()).isEqualTo("SUCCEEDED");

            // Guarantee 1 (Immediacy) + Guarantee 2 (Completeness) + Guarantee 3 (Delta-only speed):
            // 1 second later a second tweet is published; direct refresh for @elidlicom bypasses the 1-hour cache lock,
            // reuses cached metrics & graph (0 metric/graph calls!), fetches ONLY the latest 1s delta, and preserves 90-day history!
            Thread.sleep(1_100L);
            secondLiveTweetEpoch.set(Instant.now().getEpochSecond() - 1L);
            freeMetricHits.set(0);
            paidGraphHits.set(0);
            MultiPeriodDashboardBundle liveOwner = hub.analyzeWorkspaceBundle(
                    "elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO", true, "127.0.0.1", "9000", false);
            assertThat(liveOwner.hours24().fromCache()).isFalse();
            assertThat(liveOwner.hours24().activity().originalPosts()).isEqualTo(3); // 990002 + 990001 + 990006!
            assertThat(freeMetricHits.get()).isZero(); // Recent cached metrics (<180s) recognized & reused without re-fetching!
            assertThat(paidGraphHits.get()).isZero();  // Unchanged graph counts recognized & reused!

            AccountStore.StoredAccount persisted = AccountStore.read(
                    dir.resolve("users/by-id/9000/store_v" + AccountStore.VERSION + ".json.gz"), mapper);
            assertThat(persisted.tweets()).extracting(AccountStore.StoredTweet::id)
                    .containsExactlyInAnyOrder("990006", "990001", "990002", "990003");
        } finally {
            hub.stopBackgroundRefreshes();
            pool.shutdown();
            freeServer.stop(0);
            paidServer.stop(0);
        }
    }

    @Test
    void guarantee4And5_serverConsistencyFreeBatchRetryAndSecondActiveSyncRecovery(@TempDir Path dir) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MutableClock clock = new MutableClock(now.toEpochMilli(), ZoneId.of("Asia/Shanghai"));
        AtomicBoolean outageMode = new AtomicBoolean(false);
        Map<String, AtomicInteger> statusAttempts = new ConcurrentHashMap<>();
        AtomicInteger paidTweetBatchHits = new AtomicInteger();

        HttpServer freeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        freeServer.setExecutor(Executors.newCachedThreadPool());
        freeServer.createContext("/2/profile/elidlicom", ex -> {
            if (outageMode.get()) {
                writeJson(ex, 503, mapper.createObjectNode().put("code", 503).put("message", "unavailable"));
                return;
            }
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.set("user", fxUser("9000", "elidlicom", 2048, 512, 300));
            writeJson(ex, 200, body);
        });
        freeServer.createContext("/2/search", ex -> {
            if (outageMode.get()) {
                // Simulate FxTwitter /search 503 unavailable -> trips 30m search circuit!
                writeJson(ex, 503, mapper.createObjectNode().put("code", 503).put("message", "Search unavailable"));
                return;
            }
            String q = ex.getRequestURI().getRawQuery() == null ? "" : java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.putObject("cursor").put("bottom", "");
            ArrayNode results = body.putArray("results");
            if (q.contains("from:elidlicom")) {
                results.add(fxStatus("990009", "9000", "elidlicom", "与服务器完全一致的最新推文",
                        now.getEpochSecond() - 120, 8888, 222, 33, 44, 55));
            }
            writeJson(ex, 200, body);
        });
        freeServer.createContext("/2/status/", ex -> {
            String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
            int attempt = statusAttempts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            // Simulate batch failure on 2 tweets ("1002", "1005") during wave 1 (exceeding inline 3 attempts so wave 1 fails them),
            // then succeeding on FxTwitterClient's batch-level retry wave!
            if (("1002".equals(id) || "1005".equals(id)) && attempt <= 3) {
                writeJson(ex, 503, mapper.createObjectNode().put("code", 503));
                return;
            }
            ObjectNode body = mapper.createObjectNode().put("code", 200);
            body.set("status", fxStatus(id, "9000", "elidlicom", "batch metric post " + id,
                    now.getEpochSecond() - 7200, 4000, 100, 10, 20, 30));
            writeJson(ex, 200, body);
        });
        freeServer.start();

        HttpServer paidServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        paidServer.setExecutor(Executors.newCachedThreadPool());
        paidServer.createContext("/", ex -> {
            if (outageMode.get()) {
                writeJson(ex, 503, mapper.createObjectNode().put("error", "paid upstream 503"));
                return;
            }
            if (ex.getRequestURI().getPath().contains("/twitter/tweets")) {
                paidTweetBatchHits.incrementAndGet();
            }
            ObjectNode ok = mapper.createObjectNode().put("has_next_page", false);
            ok.putArray("followings");
            ok.putArray("followers");
            ok.putArray("tweets");
            ok.putArray("users");
            writeJson(ex, 200, ok);
        });
        paidServer.start();

        XOpsProperties props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setTwitterapiIoKey("owner-paid-key");
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + paidServer.getAddress().getPort());
        props.setFxtwitterEnabled(true);
        props.setFxtwitterBaseUrl("http://127.0.0.1:" + freeServer.getAddress().getPort() + "/2");
        props.setApiPoolCoreSize(6);
        props.setApiPoolMaxSize(8);

        SharedApiExecutor pool = new SharedApiExecutor(props);
        UpstreamBudgetGuard guard = new UpstreamBudgetGuard(props, true);
        PaidCapabilityGate gate = new PaidCapabilityGate(clock);
        TwitterApiIoProvider provider = new TwitterApiIoProvider(props, mapper, pool, guard);
        CommunityHubService hub = new CommunityHubService(
                props, new DemoXDataProvider(), provider, new OfficialXApiV2Provider(props, mapper), mapper);
        ReflectionTestUtils.setField(hub, "paidCapabilityGate", gate);
        try {
            // 1. Guarantee 5A: Free metric batch of 6 tweets recovers "1002" and "1005" via free batch idempotent retry wave
            // without needing paid /twitter/tweets fallback, and without re-requesting already-succeeded 1001/1003/1004/1006!
            com.xops.hub.provider.FxTwitterClient fxClient = new com.xops.hub.provider.FxTwitterClient(props, mapper, pool);
            var batchJson = fxClient.fetchAsync(
                    "https://api.twitterapi.io/twitter/tweets?tweet_ids=1001,1002,1003,1004,1005,1006",
                    Instant.now().plusSeconds(10),
                    null).join();
            assertThat(batchJson.path("tweets")).hasSize(6);
            assertThat(batchJson.path("__incomplete_ids")).isEmpty();
            assertThat(statusAttempts.get("1001").get()).isEqualTo(1);
            assertThat(statusAttempts.get("1003").get()).isEqualTo(1);
            assertThat(statusAttempts.get("1002").get()).isEqualTo(4); // 3 inline attempts in wave 1 + 1st attempt in batch retry wave!
            assertThat(paidTweetBatchHits.get()).isZero();

            // 2. Seed @elidlicom with an initial snapshot, then trigger total outage -> exhaust all 3 10-minute retries
            XUserSummary seedOwner = new XUserSummary("9000", "elidlicom", "Litany", "", "", true, 0, 0, 1, "2020");
            AccountStore.StoredAccount seedStore = AccountStore.merge(
                    null, seedOwner, now.minus(Duration.ofHours(2)), List.of(), List.of(),
                    List.of(new long[]{now.minus(Duration.ofDays(5)).getEpochSecond(), now.minus(Duration.ofHours(2)).getEpochSecond()}),
                    List.of(new long[]{now.minus(Duration.ofDays(5)).getEpochSecond(), now.minus(Duration.ofHours(2)).getEpochSecond()}),
                    new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false), Map.of());
            AccountStore.write(dir.resolve("users/by-id/9000/store_v" + AccountStore.VERSION + ".json.gz"), seedStore, mapper);

            outageMode.set(true);
            // Active sync during outage serves fallback snapshot & registers 10m idempotent retry
            MultiPeriodDashboardBundle fallback = hub.analyzeWorkspaceBundle(
                    "elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO", true, "127.0.0.1", "9000", false);
            assertThat(fallback.hours24().fromCache()).isTrue();

            String retryKey = gate.allRetrySnapshots().keySet().stream()
                    .filter(k -> k.startsWith("workspace-sync:elidlicom:")).findFirst().orElseThrow();
            for (int r = 1; r <= 3; r++) {
                clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
                gate.runDueRetries();
            }
            assertThat(gate.getRetrySnapshot(retryKey).state()).isEqualTo(RetryState.EXHAUSTED);

            // 3. Guarantee 5B + Guarantee 4: Upstream recovers! A SECOND active sync (forceRefresh=true)
            // MUST NOT be stuck in EXHAUSTED or 30m search circuit — it re-opens free FxTwitter, syncs exact server data,
            // and marks the retry ledger SUCCEEDED!
            outageMode.set(false);
            MultiPeriodDashboardBundle recovered = hub.analyzeWorkspaceBundle(
                    "elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO", true, "127.0.0.1", "9000", false);
            assertThat(recovered.hours24().fromCache()).isFalse();
            assertThat(recovered.hours24().profile().followersCount()).isEqualTo(2048);
            assertThat(recovered.hours24().profile().followingCount()).isEqualTo(512);
            assertThat(recovered.hours24().profile().totalPostsCount()).isEqualTo(300);
            AccountStore.StoredAccount recoveredStore = AccountStore.read(
                    dir.resolve("users/by-id/9000/store_v" + AccountStore.VERSION + ".json.gz"), mapper);
            assertThat(recoveredStore.tweets()).extracting(AccountStore.StoredTweet::id).contains("990009");
            var syncedTweet = recoveredStore.tweets().stream()
                    .filter(t -> "990009".equals(t.id())).findFirst().orElseThrow();
            assertThat(syncedTweet.stats()[0]).isEqualTo(8888L);
            assertThat(syncedTweet.stats()[1]).isEqualTo(222L);
            assertThat(syncedTweet.stats()[2]).isEqualTo(33L);
            assertThat(syncedTweet.stats()[3]).isEqualTo(44L);
            assertThat(syncedTweet.stats()[4]).isEqualTo(55L);
            assertThat(gate.getRetrySnapshot(retryKey).state()).isEqualTo(RetryState.SUCCEEDED);

            // And if another outage happens later and user triggers another active sync, EXHAUSTED/SUCCEEDED resets cleanly to PENDING!
            gate.scheduleIdempotentRetry(retryKey, "twitterapi_io_read", "elidlicom", "9000", "sim fail", () -> {
                throw new RuntimeException("still down");
            }, false);
            for (int r = 1; r <= 3; r++) {
                clock.advanceMillis(PaidCapabilityGate.RETRY_INTERVAL_MS);
                gate.runDueRetries();
            }
            assertThat(gate.getRetrySnapshot(retryKey).state()).isEqualTo(RetryState.EXHAUSTED);
            var revived = gate.scheduleIdempotentRetry(
                    retryKey, "twitterapi_io_read", "elidlicom", "9000", "second active trigger", () -> {}, true);
            assertThat(revived.state()).isEqualTo(RetryState.PENDING);
            assertThat(revived.retryCount()).isZero();
        } finally {
            hub.stopBackgroundRefreshes();
            pool.shutdown();
            freeServer.stop(0);
            paidServer.stop(0);
        }
    }

    private ObjectNode fxUser(String id, String handle, int followers, int following, int statuses) {
        return mapper.createObjectNode()
                .put("id", id)
                .put("screen_name", handle)
                .put("name", handle)
                .put("followers", followers)
                .put("following", following)
                .put("statuses", statuses)
                .put("likes", 10)
                .put("media_count", 0)
                .put("joined", "Mon Jan 01 00:00:00 +0000 2024");
    }

    private ObjectNode fxStatus(String id, String uid, String handle, String text, long createdEpochSec,
                                int views, int likes, int replies, int reposts, int bookmarks) {
        ObjectNode node = mapper.createObjectNode()
                .put("type", "status")
                .put("id", id)
                .put("text", text)
                .put("created_timestamp", createdEpochSec)
                .put("views", views)
                .put("likes", likes)
                .put("replies", replies)
                .put("reposts", reposts)
                .put("quotes", 0)
                .put("bookmarks", bookmarks);
        node.putNull("replying_to");
        node.set("author", fxUser(uid, handle, 2048, 512, 300));
        return node;
    }

    private static String lastSyncEpochFromQuery(String query) {
        var m = java.util.regex.Pattern.compile("since_time:([0-9]+)").matcher(query);
        return m.find() ? m.group(1) : "0";
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange ex, int status, ObjectNode node) throws java.io.IOException {
        byte[] bytes = node.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
