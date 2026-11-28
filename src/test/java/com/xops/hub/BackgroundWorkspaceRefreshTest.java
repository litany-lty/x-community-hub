package com.xops.hub;

import static com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundWorkspaceRefreshTest {
    @TempDir Path temp;

    @Test
    void persistedRefreshReturnsImmediatelyAndConcurrentReaderDoesNotWaitForUpstream() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(temp.toString());
        properties.setTwitterapiIoKey("fixture-key");
        properties.setDefaultProvider("TWITTERAPI_IO");
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        CountDownLatch upstreamStarted = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger syncCalls = new java.util.concurrent.atomic.AtomicInteger();
        TwitterApiIoProvider provider = new TwitterApiIoProvider(properties, mapper, pool) {
            @Override
            public SyncOutcome syncStoreByHours(String username, AccountStore.StoredAccount existing,
                    int hours, boolean head, boolean daily, String key, String expectedId) {
                syncCalls.incrementAndGet();
                upstreamStarted.countDown();
                try {
                    if (!releaseUpstream.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return new SyncOutcome(existing, false, true, true, 0);
            }
        };
        Instant now = Instant.now();
        XUserSummary profile = new XUserSummary("owner-id", "owner", "Owner", "", "", false, 0, 0, 0, "");
        AccountStore.StoredAccount stored = AccountStore.merge(null, profile, now, List.of(), List.of(),
                List.of(), List.of(), new AccountStore.GraphUpdate(
                        List.of(), List.of(), Set.of(), false, false, false, false));
        AccountStore.write(temp.resolve("users/by-id/owner-id/store_v" + AccountStore.VERSION + ".json.gz"), stored, mapper);
        CommunityHubService service = new CommunityHubService(
                properties, new DemoXDataProvider(), provider, null, mapper);
        var readers = Executors.newSingleThreadExecutor();
        try {
            var immediate = service.analyzeWorkspaceBundle(
                    "owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", true, "127.0.0.1", "owner-id", false, true);
            assertThat(immediate.backgroundRefreshStatus().state()).isEqualTo("RUNNING");
            assertThat(upstreamStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var read = readers.submit(() -> service.analyzeWorkspaceBundle(
                    "owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "owner-id", false));
            assertThat(read.get(500, TimeUnit.MILLISECONDS).backgroundRefreshStatus().state()).isEqualTo("RUNNING");

            releaseUpstream.countDown();
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"PARTIAL".equals(service.workspaceRefreshStatus("owner", "TWITTERAPI_IO", "owner-id").state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(10);
            }
            assertThat(service.workspaceRefreshStatus("owner", "TWITTERAPI_IO", "owner-id").state()).isEqualTo("PARTIAL");

            var afterComplete = service.analyzeWorkspaceBundle(
                    "owner", ReportPeriod.WEEK, "TWITTERAPI_IO", false, "127.0.0.1", "owner-id", false);
            assertThat(afterComplete.hours24().fromCache()).isTrue();
            assertThat(syncCalls.get()).isEqualTo(1);
        } finally {
            releaseUpstream.countDown();
            readers.shutdownNow();
            service.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preferStoredReturnsUnclosedSnapshotImmediatelyAndStartsBackgroundRefresh(boolean finishesBeforeResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(temp.resolve("swr").toString());
        properties.setTwitterapiIoKey("fixture-key");
        properties.setDefaultProvider("TWITTERAPI_IO");
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        CountDownLatch upstreamStarted = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(finishesBeforeResponse ? 0 : 1);
        java.util.concurrent.atomic.AtomicInteger syncCalls = new java.util.concurrent.atomic.AtomicInteger();
        TwitterApiIoProvider provider = new TwitterApiIoProvider(properties, mapper, pool) {
            @Override
            public SyncOutcome syncStoreByHours(String username, AccountStore.StoredAccount existing,
                    int hours, boolean head, boolean daily, String key, String expectedId) {
                syncCalls.incrementAndGet();
                upstreamStarted.countDown();
                try {
                    if (!releaseUpstream.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                Instant doneAt = Instant.now();
                long from = doneAt.minusSeconds(24 * 3600L).getEpochSecond();
                long to = doneAt.getEpochSecond();
                AccountStore.StoredAccount refreshed = AccountStore.merge(
                        existing, existing.profile(), doneAt, List.of(), List.of(),
                        List.of(new long[]{from, to}), List.of(new long[]{from, to}), null);
                refreshed = AccountStore.withDailyRefreshState(refreshed, to, to);
                return new SyncOutcome(refreshed, true, false, true, 1);
            }
        };
        Instant now = Instant.now();
        XUserSummary profile = new XUserSummary("swr-id", "swr_owner", "SWR Owner", "", "", false, 0, 0, 0, "");
        AccountStore.StoredAccount stored = AccountStore.merge(null, profile, now.minusSeconds(3600), List.of(), List.of(),
                List.of(), List.of(), new AccountStore.GraphUpdate(
                        List.of(), List.of(), Set.of(), false, false, false, false));
        AccountStore.write(temp.resolve("swr/users/by-id/swr-id/store_v" + AccountStore.VERSION + ".json.gz"), stored, mapper);
        CommunityHubService service = new CommunityHubService(
                properties, new DemoXDataProvider(), provider, null, mapper);
        try {
            if (finishesBeforeResponse) {
                var direct = Mockito.mock(java.util.concurrent.ExecutorService.class);
                Mockito.when(direct.submit(Mockito.any(Runnable.class))).thenAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
                ReflectionTestUtils.setField(service, "backgroundRefreshExecutor", direct);
            }
            var immediate = service.analyzeWorkspaceBundle(
                    "swr_owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "swr-id", false, true);
            assertThat(immediate.hours24().fromCache()).isTrue();
            assertThat(immediate.backgroundRefreshStatus()).isNotNull();
            assertThat(immediate.backgroundRefreshStatus().state()).isEqualTo(finishesBeforeResponse ? "SUCCEEDED" : "RUNNING");
            assertThat(immediate.backgroundRefreshStatus().newerResultAvailable()).isEqualTo(finishesBeforeResponse);
            assertThat(upstreamStarted.await(1, TimeUnit.SECONDS)).isTrue();

            releaseUpstream.countDown();
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"SUCCEEDED".equals(service.workspaceRefreshStatus("swr_owner", "TWITTERAPI_IO", "swr-id").state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(10);
            }
            assertThat(service.workspaceRefreshStatus("swr_owner", "TWITTERAPI_IO", "swr-id").state()).isEqualTo("SUCCEEDED");

            var afterReload = service.analyzeStoredAccountBundle("swr_owner", "swr-id");
            assertThat(afterReload.hours24().fromCache()).isTrue();
            assertThat(afterReload.hasMoreHistoryForTarget()).isFalse();
            assertThat(syncCalls.get()).isEqualTo(1);
        } finally {
            releaseUpstream.countDown();
            service.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    @Test
    void coldRefreshHasASeparateAcceptanceStatusInsteadOfHoldingThePageRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(temp.resolve("cold").toString());
        properties.setTwitterapiIoKey("fixture-key");
        properties.setDefaultProvider("TWITTERAPI_IO");
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        CountDownLatch upstreamStarted = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        TwitterApiIoProvider provider = new TwitterApiIoProvider(properties, mapper, pool) {
            @Override
            public SyncOutcome syncStoreByHours(String username, AccountStore.StoredAccount existing,
                    int hours, boolean head, boolean daily, String key, String expectedId) {
                upstreamStarted.countDown();
                try {
                    if (!releaseUpstream.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                XUserSummary profile = new XUserSummary(expectedId, username, username, "", "", false, 0, 0, 0, "");
                AccountStore.StoredAccount fresh = AccountStore.merge(null, profile, Instant.now(), List.of(), List.of(),
                        List.of(), List.of(), new AccountStore.GraphUpdate(
                                List.of(), List.of(), Set.of(), false, false, false, false));
                return new SyncOutcome(fresh, true, false, false, 1);
            }
        };
        CommunityHubService service = new CommunityHubService(
                properties, new DemoXDataProvider(), provider, null, mapper);
        try {
            var accepted = service.requestWorkspaceRefresh(
                    "cold_owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", "127.0.0.1", "cold-id");
            assertThat(accepted.state()).isEqualTo("RUNNING");
            assertThat(java.util.UUID.fromString(accepted.refreshId())).isNotNull();
            assertThat(upstreamStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var fromStatus = service.workspaceRefreshStatus("cold_owner", "TWITTERAPI_IO", "cold-id");
            assertThat(fromStatus.state()).isEqualTo("RUNNING");
            assertThat(fromStatus.refreshId()).isEqualTo(accepted.refreshId());
        } finally {
            releaseUpstream.countDown();
            service.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    @Test
    void backgroundRefreshContinuesResumableDailyBatchesUntilComplete() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(temp.resolve("multi").toString());
        properties.setTwitterapiIoKey("fixture-key");
        properties.setFxtwitterEnabled(true);
        properties.setFxtwitterBaseUrl("http://127.0.0.1/2");
        properties.setDefaultProvider("TWITTERAPI_IO");
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        Instant cycleAt = Instant.now().minusSeconds(5);
        long anchor = cycleAt.getEpochSecond();
        XUserSummary profile = new XUserSummary("multi-id", "multi_owner", "Multi Owner", "", "", false, 0, 0, 2, "");
        java.util.concurrent.atomic.AtomicInteger syncCalls = new java.util.concurrent.atomic.AtomicInteger();
        TwitterApiIoProvider provider = new TwitterApiIoProvider(properties, mapper, pool) {
            @Override
            public SyncOutcome syncStoreByHours(String username, AccountStore.StoredAccount existing,
                    int hours, boolean head, boolean daily, String key, String expectedId) {
                int call = syncCalls.incrementAndGet();
                long from = anchor - 24 * 3600L;
                if (call == 1) {
                    var unattempted = new AccountStore.StoredTweet("p-2", anchor - 120, 'P', "post 2", null, List.of(),
                            0, new long[]{10, 1, 0, 0, 0}, null, null, anchor - 4000, anchor - 4000, Set.of(), true);
                    var partial = new AccountStore.StoredAccount(AccountStore.VERSION, profile, anchor, java.util.Map.of(),
                            List.of(unattempted), List.of(), List.of(new long[]{from, anchor}), List.of(new long[]{from, anchor}),
                            List.of(), List.of(), List.of(), anchor, false, false, false, List.of(), java.util.Map.of(),
                            anchor, 0);
                    return new SyncOutcome(partial, true, false, true, 1, 1, 1, false, false);
                }
                var refreshed = new AccountStore.StoredTweet("p-2", anchor - 120, 'P', "post 2", null, List.of(),
                        0, new long[]{50, 5, 0, 0, 0}, null, null, anchor + 1, anchor + 1, Set.of(), true);
                var complete = new AccountStore.StoredAccount(AccountStore.VERSION, profile, anchor, java.util.Map.of(),
                        List.of(refreshed), List.of(), List.of(new long[]{from, anchor}), List.of(new long[]{from, anchor}),
                        List.of(), List.of(), List.of(), anchor, false, false, false, List.of(), java.util.Map.of(),
                        anchor, anchor);
                return new SyncOutcome(complete, true, false, false, 0, 0, 0, true, false);
            }
        };
        AccountStore.StoredAccount old = AccountStore.merge(null, profile, cycleAt.minusSeconds(3600), List.of(), List.of(),
                List.of(), List.of(), new AccountStore.GraphUpdate(
                        List.of(), List.of(), Set.of(), false, false, false, false));
        AccountStore.write(temp.resolve("multi/users/by-id/multi-id/store_v" + AccountStore.VERSION + ".json.gz"), old, mapper);
        CommunityHubService service = new CommunityHubService(
                properties, new DemoXDataProvider(), provider, null, mapper);
        try {
            var immediate = service.analyzeWorkspaceBundle(
                    "multi_owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "multi-id", false, true);
            assertThat(immediate.backgroundRefreshStatus().state()).isEqualTo("RUNNING");
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!"SUCCEEDED".equals(service.workspaceRefreshStatus("multi_owner", "TWITTERAPI_IO", "multi-id").state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(10);
            }
            assertThat(service.workspaceRefreshStatus("multi_owner", "TWITTERAPI_IO", "multi-id").state()).isEqualTo("SUCCEEDED");
            assertThat(syncCalls.get()).isEqualTo(2);

            var reloaded = service.analyzeWorkspaceBundle(
                    "multi_owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "multi-id", false, false);
            assertThat(reloaded.dailyCacheStatus().canContinue()).isFalse();
            assertThat(reloaded.dailyCacheStatus().unattemptedMetricPosts()).isZero();
            assertThat(reloaded.hasMoreHistoryForTarget()).isFalse();
            assertThat(reloaded.hours24().bannerNotice()).startsWith("最近 24 小时数据与指标已完成本轮同步");
        } finally {
            service.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    @Test
    void privilegedOwnerSilentReloadAfterBackgroundRefreshReusesFreshBundleWithoutReBlockingForeground() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(temp.resolve("elidlicom-swr").toString());
        properties.setTwitterapiIoKey("fixture-key");
        properties.setDefaultProvider("TWITTERAPI_IO");
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        java.util.concurrent.atomic.AtomicInteger syncCalls = new java.util.concurrent.atomic.AtomicInteger();
        TwitterApiIoProvider provider = new TwitterApiIoProvider(properties, mapper, pool) {
            @Override
            public SyncOutcome syncStoreByHours(String username, AccountStore.StoredAccount existing,
                    int hours, boolean head, boolean daily, String key, String expectedId) {
                syncCalls.incrementAndGet();
                Instant doneAt = Instant.now();
                long from = doneAt.minusSeconds(24 * 3600L).getEpochSecond();
                long to = doneAt.getEpochSecond();
                AccountStore.StoredAccount refreshed = AccountStore.merge(
                        existing, existing.profile(), doneAt, List.of(), List.of(),
                        List.of(new long[]{from, to}), List.of(new long[]{from, to}), null);
                refreshed = AccountStore.withDailyRefreshState(refreshed, to, 0);
                return new SyncOutcome(refreshed, true, true, true, 1);
            }
        };
        Instant now = Instant.now();
        XUserSummary profile = new XUserSummary(ARCHIVE_ACCOUNT_ID, "elidlicom", "Litany", "", "", true, 1200, 300, 100, "");
        AccountStore.StoredAccount stored = AccountStore.merge(null, profile, now.minusSeconds(3600), List.of(), List.of(),
                List.of(), List.of(), new AccountStore.GraphUpdate(
                        List.of(), List.of(), Set.of(), true, false, false, false));
        AccountStore.write(temp.resolve("elidlicom-swr/users/by-id/" + ARCHIVE_ACCOUNT_ID + "/store_v" + AccountStore.VERSION + ".json.gz"), stored, mapper);
        CommunityHubService service = new CommunityHubService(
                properties, new DemoXDataProvider(), provider, null, mapper);
        try {
            var immediate = service.analyzeWorkspaceBundle(
                    "elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", ARCHIVE_ACCOUNT_ID, false, true);
            assertThat(immediate.hours24().fromCache()).isTrue();
            assertThat(immediate.backgroundRefreshStatus().state()).isEqualTo("RUNNING");

            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"PARTIAL".equals(service.workspaceRefreshStatus("elidlicom", "TWITTERAPI_IO", ARCHIVE_ACCOUNT_ID).state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(10);
            }
            assertThat(service.workspaceRefreshStatus("elidlicom", "TWITTERAPI_IO", ARCHIVE_ACCOUNT_ID).state()).isEqualTo("PARTIAL");
            assertThat(syncCalls.get()).isEqualTo(1);

            // Simulate browser poll firing >5s later: reloadFromServer (forceRefresh=false) must STILL serve in-memory bundle without a 2nd upstream sync!
            Thread.sleep(5_200L);
            var afterReload = service.analyzeWorkspaceBundle(
                    "elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", ARCHIVE_ACCOUNT_ID, false, false);
            assertThat(afterReload.hours24().fromCache()).isTrue();
            assertThat(syncCalls.get()).isEqualTo(1);
        } finally {
            service.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }
}
