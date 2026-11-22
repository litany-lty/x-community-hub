package com.xops.hub;

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
            while (!"SUCCEEDED".equals(service.workspaceRefreshStatus("owner", "TWITTERAPI_IO", "owner-id").state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(10);
            }
            assertThat(service.workspaceRefreshStatus("owner", "TWITTERAPI_IO", "owner-id").state()).isEqualTo("SUCCEEDED");

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

    @Test
    void preferStoredReturnsUnclosedSnapshotImmediatelyAndStartsBackgroundRefresh() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(temp.resolve("swr").toString());
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
            var immediate = service.analyzeWorkspaceBundle(
                    "swr_owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "swr-id", false, true);
            assertThat(immediate.hours24().fromCache()).isTrue();
            assertThat(immediate.backgroundRefreshStatus()).isNotNull();
            assertThat(immediate.backgroundRefreshStatus().state()).isEqualTo("RUNNING");
            assertThat(upstreamStarted.await(1, TimeUnit.SECONDS)).isTrue();

            releaseUpstream.countDown();
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"SUCCEEDED".equals(service.workspaceRefreshStatus("swr_owner", "TWITTERAPI_IO", "swr-id").state())
                    && System.nanoTime() < waitDeadline) {
                Thread.sleep(10);
            }
            assertThat(service.workspaceRefreshStatus("swr_owner", "TWITTERAPI_IO", "swr-id").state()).isEqualTo("SUCCEEDED");

            var afterReload = service.analyzeWorkspaceBundle(
                    "swr_owner", ReportPeriod.HOURS_24, "TWITTERAPI_IO", false, "127.0.0.1", "swr-id", false, false);
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
}
