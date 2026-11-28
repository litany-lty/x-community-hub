package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.*;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HourlyDailyCacheTest {
    @TempDir Path cacheDir;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final XUserSummary self = new XUserSummary("test-self-id", "hourly_self", "Synthetic Self", "", "", false, 0, 0, 0, "");
    TwitterApiIoProvider twitter;
    OfficialXApiV2Provider official;
    CommunityHubService service;
    XOpsProperties properties;

    @BeforeEach
    void setup() {
        properties = new XOpsProperties();
        properties.setDefaultProvider("TWITTERAPI_IO");
        properties.setTwitterapiIoKey("isolated-test-key");
        properties.setDiskCacheDir(cacheDir.toString());
        properties.setCacheTtlSeconds(300);
        twitter = mock(TwitterApiIoProvider.class);
        official = mock(OfficialXApiV2Provider.class);
        service = new CommunityHubService(properties, new DemoXDataProvider(), twitter, official, mapper);
    }

    @Test
    void coldStartAndButtonReuseCompleteHourButReadPersistedExpiryEveryTime() throws Exception {
        long now = Instant.now().getEpochSecond();
        var fresh = store(now - 600, true);
        persist(fresh);
        byte[] before = Files.readAllBytes(storeFile());
        var first = load(true);
        var cold = new CommunityHubService(properties, new DemoXDataProvider(), twitter, official, mapper)
                .analyzeWorkspaceBundle(self.username(), ReportPeriod.HOURS_24, null, true, "test", self.id(), false);
        assertThat(first.hours24().fetchedAt()).isEqualTo(fresh.syncedAtInstant());
        assertThat(cold.hours24().nextRefreshAt()).isEqualTo(Instant.ofEpochSecond(now - 600 + 3600));
        assertThat(first.dailyCacheStatus().lastCompletedAt()).isEqualTo(fresh.syncedAtInstant());
        assertThat(Files.readAllBytes(storeFile())).isEqualTo(before);
        verifyNoInteractions(twitter, official);

        persist(store(now - 3600, true));
        var updated = store(now, true);
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(updated, false));
        var refreshed = load(true);
        assertThat(refreshed.hours24().fetchedAt()).isEqualTo(updated.syncedAtInstant());
        verify(twitter, times(1)).syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id()));
    }

    @Test
    void completedContinuationStartsOneHourWhenItFinishesAndCachedViewsDoNotMoveIt() throws Exception {
        long now = Instant.now().getEpochSecond();
        properties.setTimezone(zoneAtLocalTime(now, 300).getId());
        long cycle = now - 900;
        long prior = now - 7200;
        var partial = AccountStore.withDailyRefreshState(AccountStore.withCoverage(store(cycle, false),
                List.of(new long[]{cycle - 3600, cycle}), List.of()), cycle, prior);
        persist(partial);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(false), eq(true), isNull(), anyString()))
                .thenReturn(outcome(partial, true), outcome(store(cycle, true), false));
        var first = load(true);
        assertThat(first.dailyCacheStatus().lastCompletedAt()).isEqualTo(Instant.ofEpochSecond(prior));
        assertThat(first.dailyCacheStatus().canContinue()).isTrue();
        var completed = load(true);
        Instant finish = completed.dailyCacheStatus().lastCompletedAt();
        assertThat(finish.getEpochSecond()).isBetween(now, Instant.now().getEpochSecond());
        assertThat(completed.hours24().nextRefreshAt()).isEqualTo(finish.plusSeconds(3600));
        Files.delete(storeFile().resolveSibling("usage_v1.json"));
        var restarted = new CommunityHubService(properties, new DemoXDataProvider(), twitter, official, mapper)
                .analyzeWorkspaceBundle(self.username(), ReportPeriod.HOURS_24, null, true, "test", self.id(), false);
        assertThat(restarted.hours24().fromCache()).isTrue();
        assertThat(restarted.dailyCacheStatus().lastCompletedAt()).isEqualTo(finish);
        assertThat(restarted.accessPolicy().accumulatedDays()).isOne();
        assertThat(service.analyzeStoredAccountBundle(self.username(), self.id()).accessPolicy().accumulatedDays()).isOne();
        assertThat(restarted.hours24().nextRefreshAt()).isEqualTo(completed.hours24().nextRefreshAt());
        var policy = mapper.valueToTree(restarted.accessPolicy());
        assertThat(policy.has("dailyRefreshLimit") || policy.has("dailyRefreshRemaining") || policy.has("dailyRefreshUsed")).isFalse();
        verify(twitter, times(2)).syncStoreByHours(anyString(), any(), eq(24), eq(false), eq(true), isNull(), anyString());
    }

    @Test
    void midnightAndAnOlderCoverageAnchorDoNotShortenTheCompletedHour() throws Exception {
        long now = Instant.now().getEpochSecond();
        long cycle = now - 3800;
        Instant finish = Instant.ofEpochSecond(now - 1200);
        ZoneId zone = zoneAtLocalTime(now, 300);
        properties.setTimezone(zone.getId());
        persist(store(cycle, true));
        var usage = mapper.createObjectNode().put("completedReportCycle", cycle).put("lastReportSyncAt", finish.toString())
                .put("quotaDate", finish.atZone(zone).toLocalDate().toString()).put("manualRefreshUsedToday", 1);
        usage.putArray("syncedDates").add(finish.atZone(zone).toLocalDate().toString());
        mapper.writeValue(storeFile().resolveSibling("usage_v1.json").toFile(), usage);
        when(twitter.syncStoreByHours(anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), isNull(), anyString()))
                .thenReturn(outcome(store(now, true), false));
        var cached = load(true);
        assertThat(cached.hours24().fromCache()).isTrue();
        assertThat(cached.dailyCacheStatus().lastCompletedAt()).isEqualTo(finish);
        assertThat(cached.hours24().nextRefreshAt()).isEqualTo(finish.plusSeconds(3600));
        verifyNoInteractions(twitter, official);
    }

    @Test
    void top50CompletesAllThreeUsefulMetricBatchesBeforeMarkingTheDaySuccessful() throws Exception {
        long cycle = Instant.now().getEpochSecond();
        rankPeers(cycle, List.of(self));
        persist(store(cycle - 7200, true));
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(metricBatch(cycle, 50), false));
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(metricBatch(cycle, 100), false), outcome(metricBatch(cycle, 101), false));
        assertThat(service.runTop50AutoSyncNow()).isEqualTo(1);
        assertThat(AccountStore.read(storeFile(), mapper).dailyRefreshedAt()).isEqualTo(cycle);
        assertThat(service.runTop50AutoSyncNow()).isZero();
        verify(twitter, times(2)).syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id()));
    }

    private AccountStore.StoredAccount metricBatch(long cycle, int updated) {
        var sample = store(cycle, updated == 101);
        var rows = new java.util.ArrayList<AccountStore.StoredTweet>();
        for (int i = 0; i < 101; i++) {
            long measured = i < updated ? cycle : cycle - 500;
            rows.add(new AccountStore.StoredTweet("post-" + i, cycle - 100, 'P', "post", null, List.of(), 0,
                    null, null, null, measured, measured, Set.of(), true));
        }
        return new AccountStore.StoredAccount(sample.version(), self, cycle, Map.of(), rows, List.of(),
                sample.tweetCoverage(), sample.inboundCoverage(), List.of(), List.of(), List.of(), 0, false, false,
                false, List.of(), Map.of(), cycle, sample.dailyRefreshedAt(), Map.of(), Map.of(), sample.coverageProofVersion());
    }

    private void rankPeers(long now, List<XUserSummary> peers) throws Exception {
        properties.setFxtwitterEnabled(true);
        Instant anchor = Instant.ofEpochSecond(now);
        var author = new XUserSummary(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "renamed_author", "Synthetic Owner", "", "", false, 0, 0, 0, "");
        var replies = peers.stream().map(peer -> new RawTweet("reply-" + peer.id(), "reply", anchor.minusSeconds(60),
                TweetKind.REPLY, peer.username(), peer, List.of(peer.username()), false, false, 1, 0, 0, 0, 0, false,
                "thread-" + peer.id(), "thread-" + peer.id())).toList();
        var coverage = List.of(new long[]{now - 30 * 86400L, now});
        var participants = AccountStore.merge(null, author, anchor, replies, List.of(), coverage, coverage, null);
        AccountStore.write(cacheDir.resolve("users/by-id").resolve(author.id()).resolve("store_v1.json.gz"), participants, mapper);
    }

    @Test
    void top50SkipsAnAlreadyCompletedLocalDayButManualHourlyChecksAndPartialRecoveryStillRun() throws Exception {
        long now = Instant.now().getEpochSecond();
        Instant anchor = Instant.ofEpochSecond(now);
        ZoneId zone = zoneAtLocalTime(now, 12 * 3600);
        properties.setTimezone(zone.getId());
        rankPeers(now, List.of(self));
        long morning = now - 7200;
        persist(store(morning, true));
        var usage = new CommunityHubService.UserUsageEntry(1, 0, null, anchor,
                List.of(anchor.atZone(zone).toLocalDate().toString()), morning, Instant.ofEpochSecond(morning));
        mapper.writeValue(storeFile().resolveSibling("usage_v1.json").toFile(), usage);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString()))
                .thenReturn(outcome(store(now - 15, true), false));
        assertThat(service.runTop50AutoSyncNow()).isZero();
        assertThat(load(true).hours24().fromCache()).isFalse();
        assertThat(service.runTop50AutoSyncNow()).isZero();
        var partial = AccountStore.withDailyRefreshState(AccountStore.withCoverage(store(now - 5, false),
                List.of(), List.of()), now - 5, now - 15);
        persist(partial);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(false), eq(true), isNull(), anyString()))
                .thenReturn(outcome(store(now - 5, true), false));
        assertThat(service.runTop50AutoSyncNow()).isEqualTo(1);
        assertThat(service.runTop50AutoSyncNow()).isZero();
        verify(twitter).syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString());
        verify(twitter).syncStoreByHours(anyString(), any(), eq(24), eq(false), eq(true), isNull(), anyString());
    }

    private static ZoneId zoneAtLocalTime(long epoch, int desiredSecondOfDay) {
        int utcSecond = Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).toLocalTime().toSecondOfDay();
        return ZoneOffset.ofTotalSeconds(Math.floorMod(desiredSecondOfDay - utcSecond + 43200, 86400) - 43200);
    }

    @Test
    void backgroundReportsFallbackAndPartialAndDoesNotAttachAnOldTerminalResultToAnActiveCheck() throws Exception {
        long now = Instant.now().getEpochSecond();
        persist(store(now - 5000, true));
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString()))
                .thenThrow(new XDataProviderException(503, "upstream unavailable"))
                .thenReturn(outcome(store(now - 4000, true), false), outcome(metricBatch(now, 50), false));
        assertThat(runBackground()).isEqualTo("FAILED");
        assertThat(runBackground()).isEqualTo("SUCCEEDED");
        persist(store(now - 7000, true));
        var later = load(true);
        assertThat(later.dailyCacheStatus().canContinue()).isTrue();
        assertThat(later.backgroundRefreshStatus()).isNull();
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(false), eq(true), isNull(), anyString()))
                .thenReturn(outcome(metricBatch(now, 50), false));
        assertThat(runBackground()).isEqualTo("PARTIAL");
        service.stopBackgroundRefreshes();
    }

    private String runBackground() throws Exception {
        service.requestWorkspaceRefresh(self.username(), ReportPeriod.HOURS_24, null, "test", self.id());
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            var result = service.workspaceRefreshStatus(self.username(), null, self.id());
            if (!"RUNNING".equals(result.state())) return result.state();
            Thread.sleep(5);
        }
        throw new AssertionError("background refresh did not finish");
    }

    @Test
    void singleOfficialPeriodReturnsVerifiedStoredReportWhenTheBudgetRefusesARead() throws Exception {
        long at = Instant.now().getEpochSecond() - 7200;
        XUserSummary author = new XUserSummary(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "Owner", "", "", false, 0, 0, 0, "");
        var ownerStore = AccountStore.merge(null, author, Instant.ofEpochSecond(at), List.of(), List.of(),
                List.of(new long[]{at - 86400, at}), List.of(new long[]{at - 86400, at}), null);
        AccountStore.write(cacheDir.resolve("users/by-id").resolve(author.id()).resolve("store_v1.json.gz"),
                AccountStore.withDailyRefreshState(ownerStore, at, at), mapper);
        properties.setDefaultProvider("X_OFFICIAL_V2");
        properties.setOfficialBearerToken("test-only");
        service.setPaidCapabilityGate(new PaidCapabilityGate());
        when(official.fetchSnapshot(author.username(), null, author.id())).thenThrow(new XDataProviderException(429, "budget closed"));
        var report = service.analyzeWorkspaceReport(author.username(), ReportPeriod.HOURS_24, null, false, "test", author.id());
        assertThat(report.fromCache()).isTrue();
        assertThat(report.fetchedAt()).isEqualTo(Instant.ofEpochSecond(at));
        assertThat(report.nextRefreshAt()).isEqualTo(Instant.ofEpochSecond(at + 3600));
        assertThat(report.bannerNotice()).contains("预算");
        doThrow(new XDataProviderException(403, "authorization denied")).when(official).fetchSnapshot(author.username(), null, author.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.analyzeWorkspaceReport(
                author.username(), ReportPeriod.HOURS_24, null, true, "test", author.id()))
                .isInstanceOfSatisfying(XDataProviderException.class, error -> assertThat(error.getStatusCode()).isEqualTo(403));
        Files.delete(cacheDir.resolve("users/by-id").resolve(author.id()).resolve("store_v1.json.gz"));
        doThrow(new XDataProviderException(429, "budget closed")).when(official).fetchSnapshot(author.username(), null, author.id());
        var cold = new CommunityHubService(properties, new DemoXDataProvider(), twitter, official, mapper);
        cold.setPaidCapabilityGate(new PaidCapabilityGate());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cold.analyzeWorkspaceReport(
                author.username(), ReportPeriod.HOURS_24, null, false, "test", author.id()))
                .isInstanceOfSatisfying(XDataProviderException.class, error -> assertThat(error.getStatusCode()).isEqualTo(429));
    }

    @Test
    void partialReportChecksTheActualOrdinaryAccountBeforeOfferingContinuation() throws Exception {
        long at = Instant.now().getEpochSecond() - 60;
        var pending = AccountStore.withCoverage(store(at, false), List.of(new long[]{at - 86400, at}), List.of());
        persist(pending);
        when(twitter.isDailyContinuationBlocked(false, true, false))
                .thenAnswer(call -> PaidCapabilityGate.isCurrentCallerForbidden());
        var archived = service.analyzeStoredAccountBundle(self.username(), self.id());
        assertThat(archived.dailyCacheStatus().canContinue()).isFalse();
        assertThat(load(true).dailyCacheStatus().canContinue()).isFalse();
        verify(twitter, never()).syncStoreByHours(anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), any(), anyString());
    }

    @Test
    void rankedPeerReceivesAutoSyncOnlyForAConfiguredScheduler() throws Exception {
        long at = Instant.now().getEpochSecond();
        rankPeers(at, List.of(self));
        persist(store(at, true));
        properties.setTop50AutoSyncEnabled(false);
        var disabled = service.analyzeStoredAccountBundle(self.username(), self.id()).accessPolicy();
        assertThat(disabled.top50AutoSync()).isFalse();
        assertThat(disabled.accumulationNote()).contains("手动刷新").doesNotContain("每日");
        properties.setTop50AutoSyncEnabled(true);
        properties.setDefaultProvider("X_OFFICIAL_V2");
        properties.setOfficialBearerToken("test-only");
        var officialOnly = service.analyzeStoredAccountBundle(self.username(), self.id()).accessPolicy();
        assertThat(officialOnly.top50AutoSync()).isFalse();
        assertThat(officialOnly.accumulationNote()).contains("手动刷新").doesNotContain("每日");
        properties.setDefaultProvider("TWITTERAPI_IO");
        properties.setFxtwitterEnabled(true);
        var enabled = service.analyzeStoredAccountBundle(self.username(), self.id()).accessPolicy();
        assertThat(enabled.top50AutoSync()).isTrue();
        assertThat(enabled.accumulationNote()).contains("每日自动同步");
    }

    @Test
    void flatLegacyPeerNeedsRegisteredIdToBeScheduledAndIsOnlySyncedOnce() throws Exception {
        long at = Instant.now().getEpochSecond();
        var other = new XUserSummary("unregistered", "unregistered", "No login", "", "", false, 0, 0, 0, "");
        rankPeers(at, List.of(self, other));
        Path flat = cacheDir.resolve("store_v1_" + self.username() + ".json.gz");
        AccountStore.write(flat, store(at - 7200, false), mapper);
        AccountStore.write(cacheDir.resolve("store_v1_unregistered.json.gz"), storeFor(other, at - 7200, false), mapper);
        Files.createDirectories(storeFile().getParent());
        mapper.writeValue(storeFile().resolveSibling("usage_v1.json").toFile(),
                new CommunityHubService.UserUsageEntry(1, 0, null, Instant.ofEpochSecond(at - 7200)));
        service.setPaidCapabilityGate(new PaidCapabilityGate());
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(store(at, true), false));
        assertThat(service.runTop50AutoSyncNow()).isOne();
        assertThat(service.runTop50AutoSyncNow()).isZero();
        assertThat(flat).exists();
        assertThat(storeFile()).exists();
        verify(twitter, times(1)).syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), anyString());
    }

    @Test
    void explicitDemoAndAutomaticDemoLeaveTheRealSuccessLedgerUntouched() throws Exception {
        Instant yesterday = Instant.now().minusSeconds(86400);
        var usage = new CommunityHubService.UserUsageEntry(1, 0, null, yesterday,
                List.of(yesterday.atZone(ZoneId.of(properties.getTimezone())).toLocalDate().toString()),
                yesterday.getEpochSecond(), yesterday);
        Files.createDirectories(storeFile().getParent());
        Path file = storeFile().resolveSibling("usage_v1.json");
        mapper.writeValue(file.toFile(), usage);
        byte[] before = Files.readAllBytes(file);
        properties.setFxtwitterEnabled(false);
        properties.setOfficialBearerToken("");
        properties.setTwitterapiIoKey("");
        for (String source : List.of("DEMO", "AUTO")) {
            properties.setDefaultProvider(source);
            var report = load(true);
            assertThat(report.hours24().liveData()).isFalse();
            assertThat(report.accessPolicy().accumulatedDays()).isOne();
            assertThat(report.day()).isNull();
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
        }
        assertThat(storeFile()).doesNotExist();
    }

    @Test
    void rankedPeerWhoAcquiredTheFormerAuthorHandleGetsOnlyFreeDailySync() throws Exception {
        long at = Instant.now().getEpochSecond();
        var peer = new XUserSummary(self.id(), "elidlicom", "New handle owner", "", "", false, 0, 0, 0, "");
        rankPeers(at, List.of(peer));
        persist(storeFor(peer, at - 7300, true));
        service.setPaidCapabilityGate(new PaidCapabilityGate());
        assertThat(service.analyzeStoredAccountBundle(peer.username(), peer.id()).accessPolicy().top50AutoSync()).isTrue();
        when(twitter.syncStoreByHours(eq(peer.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(peer.id())))
                .thenAnswer(call -> {
                    assertThat(PaidCapabilityGate.isCurrentCallerForbidden()).isTrue();
                    return outcome(storeFor(peer, at, true), false);
                });
        assertThat(service.runTop50AutoSyncNow()).isOne();
    }

    @Test
    void twoTop50RetriesAreIsolatedAndAnOutstandingFreeCircuitSurvivesRestart() throws Exception {
        long at = Instant.now().getEpochSecond();
        var peer = new XUserSummary("second-peer", "other_peer", "Second", "", "", false, 0, 0, 0, "");
        rankPeers(at, List.of(self, peer));
        persist(store(at - 8000, true));
        var second = AccountStore.merge(null, peer, Instant.ofEpochSecond(at - 8000), List.of(), List.of(), List.of(), List.of(), null);
        AccountStore.write(cacheDir.resolve("users/by-id").resolve(peer.id()).resolve("store_v1.json.gz"), second, mapper);
        var clock = mock(java.time.Clock.class);
        var millis = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        when(clock.millis()).thenAnswer(inv -> millis.get());
        PaidCapabilityGate gate = new PaidCapabilityGate(clock);
        service.setPaidCapabilityGate(gate);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), anyString()))
                .thenThrow(new XDataProviderException(503, "temporary free outage"));
        assertThat(service.runTop50AutoSyncNow()).isZero();
        assertThat(gate.allRetrySnapshots()).hasSize(2);
        String selfKey = gate.allRetrySnapshots().keySet().stream().filter(k -> k.contains(self.id())).findFirst().orElseThrow();
        millis.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1);
        gate.runDueRetries();
        verify(twitter, times(2)).syncStoreByHours(eq(self.username()), any(), eq(24), anyBoolean(), eq(true), isNull(), eq(self.id()));
        verify(twitter, times(2)).syncStoreByHours(eq(peer.username()), any(), eq(24), anyBoolean(), eq(true), isNull(), eq(peer.id()));
        assertThat(gate.getRetrySnapshot(selfKey).retryCount()).isOne();

        var recovered = new CommunityHubService(properties, new DemoXDataProvider(), twitter, official, mapper);
        var newGate = new PaidCapabilityGate(clock);
        recovered.setPaidCapabilityGate(newGate);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(recovered, "restorePendingTop50Sync");
        assertThat(newGate.allRetrySnapshots()).hasSize(2);
        assertThat(newGate.getRetrySnapshot(selfKey).retryCount()).isOne();
        var pending = AccountStore.withCoverage(store(at, false), List.of(new long[]{at - 86400, at}), List.of());
        persist(pending);
        when(twitter.isDailyContinuationBlocked(false, true, false)).thenReturn(true);
        millis.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1);
        newGate.runDueRetries();
        assertThat(newGate.getRetrySnapshot(selfKey).state()).isEqualTo(PaidCapabilityGate.RetryState.PENDING);
        assertThat(newGate.getRetrySnapshot(selfKey).retryCount()).isOne();
        verify(twitter, times(2)).syncStoreByHours(eq(self.username()), any(), eq(24), anyBoolean(), eq(true), isNull(), eq(self.id()));
        when(twitter.isDailyContinuationBlocked(false, true, false)).thenReturn(false);
        doReturn(outcome(store(at, true), false)).when(twitter).syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id()));
        millis.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1);
        newGate.runDueRetries();
        assertThat(newGate.getRetrySnapshot(selfKey).state()).isEqualTo(PaidCapabilityGate.RetryState.SUCCEEDED);
        assertThat(AccountStore.read(storeFile(), mapper).reportCompletedAt()).isGreaterThanOrEqualTo(at);
        var finalGate = new PaidCapabilityGate(clock);
        recovered.setPaidCapabilityGate(finalGate);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(recovered, "restorePendingTop50Sync");
        assertThat(finalGate.allRetrySnapshots()).isEmpty();
    }

    @Test
    void freeRetryCompletesItsOwnAccountWhileTheDailyGroupWaitsOnAnotherPeer() throws Exception {
        long at = Instant.now().getEpochSecond();
        properties.setTimezone(zoneAtLocalTime(at, 43200).getId());
        var peer = new XUserSummary("busy-peer", "busy_peer", "Busy peer", "", "", false, 0, 0, 0, "");
        rankPeers(at, List.of(self, peer));
        persist(store(at - 8000, true));
        Path peerFile = cacheDir.resolve("users/by-id").resolve(peer.id()).resolve("store_v1.json.gz");
        AccountStore.write(peerFile, AccountStore.withDailyRefreshState(storeFor(peer, at, true), at, at, at), mapper);
        var clock = mock(java.time.Clock.class);
        var millis = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        when(clock.millis()).thenAnswer(call -> millis.get());
        var gate = new PaidCapabilityGate(clock);
        service.setPaidCapabilityGate(gate);
        when(twitter.isFreeSearchCircuitOpen()).thenReturn(true);
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id())))
                .thenThrow(new XDataProviderException(403, PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE))
                .thenReturn(outcome(store(at, true), false));
        assertThat(service.runTop50AutoSyncNow()).isZero();
        assertThat(gate.allRetrySnapshots()).hasSize(1);

        AccountStore.write(peerFile, storeFor(peer, at - 8000, true), mapper);
        var busy = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(twitter.syncStoreByHours(eq(peer.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(peer.id())))
                .thenAnswer(call -> {
                    busy.countDown();
                    assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                    return outcome(storeFor(peer, at, true), false);
                });
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            var trigger = executor.submit(service::scheduledSyncTop50ActivePeers);
            assertThat(busy.await(2, TimeUnit.SECONDS)).isTrue();
            trigger.get(1, TimeUnit.SECONDS);
            millis.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1);
            executor.submit(gate::dispatchDueRetries).get(1, TimeUnit.SECONDS);
            assertThat(executor.submit(() -> "other scheduled work").get(1, TimeUnit.SECONDS)).isEqualTo("other scheduled work");
            org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(gate.allRetrySnapshots().values()).allMatch(row -> row.state() == PaidCapabilityGate.RetryState.SUCCEEDED));
            assertThat(gate.allRetrySnapshots().values()).allMatch(row -> row.state() == PaidCapabilityGate.RetryState.SUCCEEDED);
            assertThat(AccountStore.read(storeFile(), mapper).reportCompletedAt()).isGreaterThanOrEqualTo(at);
            release.countDown();
            org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(AccountStore.read(peerFile, mapper).reportCompletedAt()).isGreaterThanOrEqualTo(at));
        } finally {
            release.countDown();
            executor.shutdownNow();
            gate.stopRetryWorkers();
            service.stopBackgroundRefreshes();
        }
    }

    @Test
    void aRestartRecoversUnstartedAccountsAndTheWholeRosterIfCheckpointCreationWasInterrupted(@TempDir Path restored) throws Exception {
        long at = Instant.now().getEpochSecond();
        var peers = java.util.stream.IntStream.range(0, 6).mapToObj(i ->
                new XUserSummary("restart-" + i, "restart_" + i, "Synthetic", "", "", false, 0, 0, 0, "")).toList();
        rankPeers(at, peers);
        for (var peer : peers) {
            AccountStore.write(cacheDir.resolve("users/by-id/" + peer.id() + "/store_v1.json.gz"), storeFor(peer, at - 7200, false), mapper);
        }
        service.setPaidCapabilityGate(new PaidCapabilityGate());
        var busy = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), anyString()))
                .thenAnswer(call -> {
                    busy.countDown();
                    release.await(8, TimeUnit.SECONDS);
                    return outcome(storeFor(call.<AccountStore.StoredAccount>getArgument(1).profile(), at, true), false);
                });
        var restoredProps = new XOpsProperties();
        restoredProps.setDefaultProvider("TWITTERAPI_IO");
        restoredProps.setFxtwitterEnabled(true);
        restoredProps.setTop50AutoSyncEnabled(true);
        restoredProps.setDiskCacheDir(restored.toString());
        var recoveredTwitter = mock(TwitterApiIoProvider.class);
        when(recoveredTwitter.canReadTimelineForFree("")).thenReturn(true);
        when(recoveredTwitter.syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), anyString()))
                .thenAnswer(call -> outcome(storeFor(call.<AccountStore.StoredAccount>getArgument(1).profile(), at, true), false));
        var recovered = new CommunityHubService(restoredProps, new DemoXDataProvider(), recoveredTwitter, official, mapper);
        var clock = mock(java.time.Clock.class);
        var millis = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        when(clock.millis()).thenAnswer(call -> millis.get());
        var gate = new PaidCapabilityGate(clock);
        recovered.setPaidCapabilityGate(gate);
        try {
            service.scheduledSyncTop50ActivePeers();
            assertThat(busy.await(3, TimeUnit.SECONDS)).isTrue();
            var pending = new java.util.ArrayList<String>();
            for (var peer : peers) {
                Path checkpoint = cacheDir.resolve("users/by-id/" + peer.id() + "/top50_retry_v1.json");
                assertThat(checkpoint).exists();
                if (mapper.readTree(checkpoint.toFile()).path("dueAt").asLong() == 0) pending.add(peer.id());
            }
            assertThat(pending).isNotEmpty();
            try (var files = Files.walk(cacheDir)) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".gz") || p.toString().endsWith(".json")).toList()) {
                    Path copy = restored.resolve(cacheDir.relativize(file));
                    Files.createDirectories(copy.getParent());
                    Files.copy(file, copy);
                }
            }
            String interrupted = pending.get(pending.size() - 1);
            Files.delete(restored.resolve("users/by-id/" + interrupted + "/top50_retry_v1.json"));
            var roster = peers.stream().collect(java.util.stream.Collectors.toMap(XUserSummary::id, XUserSummary::username));
            mapper.writeValue(restored.resolve("top50_batch_v1.json").toFile(),
                    Map.of("day", LocalDate.now(ZoneId.of(properties.getTimezone())).toString(), "accounts", roster));
            recovered.restorePendingTop50Sync();
            millis.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1000);
            gate.runDueRetries();
            org.awaitility.Awaitility.await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                for (var peer : peers) {
                    Path dir = restored.resolve("users/by-id/" + peer.id());
                    assertThat(dir.resolve("top50_retry_v1.json")).doesNotExist();
                    assertThat(AccountStore.read(dir.resolve("store_v1.json.gz"), mapper).reportCompletedAt()).isGreaterThanOrEqualTo(at);
                }
            });
            verify(recoveredTwitter, times(peers.size())).syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), anyString());
        } finally {
            release.countDown();
            recovered.stopBackgroundRefreshes();
            service.stopBackgroundRefreshes();
        }
    }

    @Test
    void metricClockUsesOnlyObservedSuccessfulReadingsInsideTheAnchoredDay() throws Exception {
        long anchor = Instant.now().minusSeconds(600).getEpochSecond();
        var legacy = new RawTweet("legacy", "legacy", Instant.ofEpochSecond(anchor - 200), TweetKind.POST,
                null, null, List.of(), false, false, 10, 1, 0, 0, 0);
        var old = AccountStore.merge(store(anchor, true), self, Instant.ofEpochSecond(anchor),
                List.of(legacy), List.of(), List.of(), List.of(), null);
        persist(old);
        var oldStatus = mapper.valueToTree(service.analyzeStoredAccountBundle(self.username(), self.id()).dailyCacheStatus());
        assertThat(oldStatus.has("latestMetricFetchedAt")).isTrue();
        assertThat(oldStatus.get("latestMetricFetchedAt").isNull()).isTrue();

        var recent = new RawTweet("observed", "observed", Instant.ofEpochSecond(anchor - 100), TweetKind.POST,
                null, null, List.of(), false, false, 100, 7, 2, 1, 3);
        var outside = new RawTweet("outside", "outside", Instant.ofEpochSecond(anchor - 86401), TweetKind.POST,
                null, null, List.of(), false, false, 100, 7, 2, 1, 3);
        var measured = AccountStore.merge(old, self, Instant.ofEpochSecond(anchor), List.of(recent, outside),
                List.of(), List.of(), List.of(), null, Map.of("observed", anchor + 10, "outside", anchor + 200));
        var partial = new RawTweet("observed", "partial", recent.createdAt(), TweetKind.POST,
                null, null, List.of(), false, false, 120, 8, 2, 1, 0, false, "observed", "", Set.of("bookmarks"));
        measured = AccountStore.merge(measured, self, Instant.ofEpochSecond(anchor), List.of(partial),
                List.of(), List.of(), List.of(), null, Map.of("observed", anchor + 100));
        measured = AccountStore.withMetricAttempts(measured, List.of("observed"), Instant.ofEpochSecond(anchor + 300));
        persist(measured);
        var status = mapper.valueToTree(service.analyzeStoredAccountBundle(self.username(), self.id()).dailyCacheStatus());
        assertThat(mapper.treeToValue(status.get("latestMetricFetchedAt"), Instant.class))
                .isEqualTo(Instant.ofEpochSecond(anchor + 10));
        assertThat(AccountStore.read(storeFile(), mapper).tweets()).anySatisfy(row -> {
            assertThat(row.id()).isEqualTo("observed");
            assertThat(mapper.valueToTree(row).path("mo").asBoolean()).isTrue();
            assertThat(row.metricsFetchedAt()).isEqualTo(anchor + 10);
        });
        verifyNoInteractions(twitter, official);
    }

    @Test
    void incompleteCycleContinuesWithoutHourlySuppressionOrManualQuota() throws Exception {
        long now = Instant.now().getEpochSecond();
        var started = AccountStore.withCoverage(store(now - 30, false),
                List.of(new long[]{now - 3600, now - 30}), List.of(new long[]{now - 3600, now - 30}));
        persist(started);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        var used = mapper.createObjectNode().put("quotaDate", today).put("initialLoadedToday", true).put("manualRefreshUsedToday", 1);
        used.putArray("syncedDates").add(today);
        mapper.writeValue(storeFile().resolveSibling("usage_v1.json").toFile(), used);
        var partial = AccountStore.withCoverage(started,
                List.of(new long[]{now - 3600, now - 30}), List.of(new long[]{now - 3600, now - 30}));
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(partial, true), outcome(store(now - 30, true), false));
        var incomplete = load(true);
        assertThat(incomplete.hasMoreHistoryForTarget()).isTrue();
        assertThat(incomplete.hours24().cacheRemainingSeconds()).isZero();
        assertThat(incomplete.dailyCacheStatus().timelineCovered()).isFalse();
        assertThat(incomplete.dailyCacheStatus().lastCompletedAt()).isNull();
        var complete = load(true);
        assertThat(complete.hasMoreHistoryForTarget()).isFalse();
        assertThat(complete.dailyCacheStatus().lastCompletedAt()).isNotNull();
        assertThat(complete.hours24().cacheRemainingSeconds()).isPositive();
        verify(twitter, times(2)).syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id()));
    }

    @Test
    void concurrentChecksShareOneChargedRefresh() throws Exception {
        long now = Instant.now().getEpochSecond();
        persist(store(now - 4000, true));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString()))
                .thenAnswer(call -> {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return outcome(store(now, true), false);
                });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> load(true));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> load(true));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).hours24().fetchedAt())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).hours24().fetchedAt());
            verify(twitter, times(1)).syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingMetricsAndLegacyTimestampCannotPretendToBeComplete() throws Exception {
        long now = Instant.now().getEpochSecond();
        var base = store(now - 20, true);
        var tweet = new AccountStore.StoredTweet("synthetic-post", now - 100, 'P', "Synthetic", "", List.of(), 0,
                new long[]{0, 0, 0, 0, 0}, "synthetic-post", "", now - 1000, now - 20, Set.of());
        var staleMetrics = new AccountStore.StoredAccount(base.version(), self, base.syncedAt(), base.users(), List.of(tweet),
                base.inbound(), base.tweetCoverage(), base.inboundCoverage(), base.followings(), base.followers(), base.explicitFollowedBy(),
                base.graphFetchedAt(), false, false, false, base.relationshipEvidence(), base.relationshipAttemptedAt(),
                base.dailyRefreshStartedAt(), base.dailyRefreshedAt());
        persist(staleMetrics);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString()))
                .thenReturn(outcome(AccountStore.withDailyRefreshState(staleMetrics, now, 0), true));
        var report = load(false);
        assertThat(report.dailyCacheStatus().pendingMetricPosts()).isEqualTo(1);
        assertThat(report.hours24().cacheRemainingSeconds()).isZero();
        assertThat(report.hasMoreHistoryForTarget()).isTrue();
        verify(twitter).syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString());

        var json = mapper.valueToTree(base);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove(List.of("ds", "df"));
        var legacy = mapper.treeToValue(json, AccountStore.StoredAccount.class);
        assertThat(legacy.dailyRefreshStartedAt()).isZero();
        assertThat(legacy.dailyRefreshedAt()).isZero();
    }

    @Test
    void attemptedMissingMetricsReadOnlyUntilNextHourWhileRealGapsCanStillContinue() throws Exception {
        long now = Instant.now().getEpochSecond();
        var base = store(now - 20, false);
        var terminal = withMissingMetricRow(base);
        persist(terminal);
        var first = load(true);
        var cold = new CommunityHubService(properties, new DemoXDataProvider(), twitter, official, mapper)
                .analyzeWorkspaceBundle(self.username(), ReportPeriod.HOURS_24, null, true, "test", self.id(), false);
        assertThat(first.dailyCacheStatus().pendingMetricPosts()).isEqualTo(1);
        assertThat(first.dailyCacheStatus().unattemptedMetricPosts()).isZero();
        assertThat(first.dailyCacheStatus().canContinue()).isFalse();
        assertThat(cold.dailyCacheStatus().canContinue()).isFalse();
        assertThat(first.dailyCacheStatus().lastCompletedAt()).isNull();
        assertThat(first.hours24().cacheRemainingSeconds()).isZero();
        assertThat(first.hours24().nextRefreshAt()).isEqualTo(Instant.ofEpochSecond(base.dailyRefreshStartedAt() + 3600));
        assertThat(first.hasMoreHistoryForTarget()).isTrue();
        verifyNoInteractions(twitter, official);

        var gap = AccountStore.withCoverage(terminal,
                List.of(new long[]{now - 3600, now - 20}), terminal.inboundCoverage());
        persist(gap);
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(gap, true));
        var resumable = load(true);
        assertThat(resumable.dailyCacheStatus().canContinue()).isTrue();
        assertThat(resumable.dailyCacheStatus().lastAttemptDegraded()).isFalse();
        assertThat(resumable.dailyCacheStatus().timelineCoveredFrom()).isEqualTo(Instant.ofEpochSecond(now - 3600));
        verify(twitter).syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id()));

        persist(withMissingMetricRow(store(now - 3600, false)));
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(store(now, true), false));
        assertThat(load(true).dailyCacheStatus().lastCompletedAt()).isEqualTo(Instant.ofEpochSecond(now));
        verify(twitter).syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id()));
    }

    @Test
    void failedHourlyUpdateKeepsOldAnchorAndDoesNotHideRetryForAnHour() throws Exception {
        long now = Instant.now().getEpochSecond();
        var old = store(now - 4000, true);
        persist(old);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString()))
                .thenThrow(new XDataProviderException(503, "synthetic busy"));
        var first = load(true);
        var second = load(true);
        assertThat(first.hours24().fetchedAt()).isEqualTo(old.syncedAtInstant());
        assertThat(second.hours24().cacheRemainingSeconds()).isZero();
        assertThat(AccountStore.read(storeFile(), mapper).dailyRefreshedAt()).isEqualTo(old.dailyRefreshedAt());
        verify(twitter, times(2)).syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString());
    }

    @Test
    void partialUsefulMetricBatchCanContinueButHardBatchFailureStopsCurrentChain() throws Exception {
        long now = Instant.now().getEpochSecond();
        persist(store(now - 4000, true));
        var attempted = withMissingMetricRow(store(now, false));
        var missing = attempted.tweets().get(0);
        var unattempted = new AccountStore.StoredTweet("synthetic-unattempted", now - 100, 'P', "Synthetic", "", List.of(), 0,
                new long[]{10, 1, 0, 0, 0}, "synthetic-unattempted", "", now - 1000, now - 1000, Set.of());
        var remaining = new AccountStore.StoredAccount(attempted.version(), self, now, attempted.users(), List.of(missing, unattempted),
                attempted.inbound(), attempted.tweetCoverage(), attempted.inboundCoverage(), attempted.followings(), attempted.followers(),
                attempted.explicitFollowedBy(), attempted.graphFetchedAt(), false, false, false,
                attempted.relationshipEvidence(), attempted.relationshipAttemptedAt(), now, 0);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), anyString()))
                .thenReturn(new TwitterApiIoProvider.SyncOutcome(remaining, true, true, true, 1, 1, 2, false, false),
                        new TwitterApiIoProvider.SyncOutcome(remaining, true, true, true, 1, 1, 2, false, true));
        var partial = load(true);
        assertThat(partial.dailyCacheStatus().canContinue()).isTrue();
        assertThat(partial.dailyCacheStatus().unattemptedMetricPosts()).isEqualTo(1);
        assertThat(partial.dailyCacheStatus().pendingMetricPosts()).isEqualTo(2);
        assertThat(partial.dailyCacheStatus().lastAttemptDegraded()).isFalse();
        assertThat(load(true).dailyCacheStatus().lastAttemptDegraded()).isTrue();
    }

    @Test
    void coalescedFallbackKeepsFailureStatusWithoutDuplicatingUpstream() throws Exception {
        long now = Instant.now().getEpochSecond();
        persist(store(now - 4000, true));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(twitter.syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString()))
                .thenAnswer(call -> {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    throw new XDataProviderException(503, "synthetic busy");
                });
        CompletableFuture<MultiPeriodDashboardBundle> first = new CompletableFuture<>();
        CompletableFuture<MultiPeriodDashboardBundle> second = new CompletableFuture<>();
        Thread firstThread = new Thread(() -> {
            try { first.complete(load(true)); } catch (Throwable ex) { first.completeExceptionally(ex); }
        });
        Thread secondThread = new Thread(() -> {
            try { second.complete(load(true)); } catch (Throwable ex) { second.completeExceptionally(ex); }
        });
        firstThread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            secondThread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (secondThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(secondThread.getState()).isEqualTo(Thread.State.BLOCKED);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).dailyCacheStatus().lastAttemptDegraded()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).dailyCacheStatus().lastAttemptDegraded()).isTrue();
            verify(twitter, times(1)).syncStoreByHours(anyString(), any(), eq(24), eq(true), eq(true), isNull(), anyString());
        } finally {
            release.countDown();
            firstThread.join(5000);
            secondThread.join(5000);
        }
    }

    @Test
    void officialBoundedFirstPageCannotRecycleOldFalseWholeDayCoverage() throws Exception {
        long now = Instant.now().getEpochSecond();
        var legacy = mapper.valueToTree(AccountStore.withDailyRefreshState(store(now - 4000, true), 0, 0));
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("cp");
        persist(mapper.treeToValue(legacy, AccountStore.StoredAccount.class));
        properties.setDefaultProvider("X_OFFICIAL_V2");
        properties.setOfficialBearerToken("isolated-official-token");
        var anchor = Instant.ofEpochSecond(now);
        var bounded = new RawAccountSnapshot(ProviderMode.X_OFFICIAL_V2, true, "Synthetic bounded page", anchor,
                self, List.of(), List.of(), List.of(), false, true, 0,
                anchor.minusSeconds(1800), "next", false, null, "", false, false);
        when(official.fetchSnapshot(self.username(), null, self.id())).thenReturn(bounded);
        var report = load(true);
        var stored = AccountStore.read(storeFile(), mapper);
        assertThat(stored.tweetCoverage()).singleElement().satisfies(iv -> {
            assertThat(iv[0]).isEqualTo(now - 1800);
            assertThat(iv[1]).isEqualTo(now);
        });
        assertThat(stored.inboundCoverage()).isEmpty();
        assertThat(stored.dailyRefreshedAt()).isZero();
        assertThat(report.hasMoreHistoryForTarget()).isTrue();
        assertThat(report.hours24().cacheRemainingSeconds()).isZero();
    }

    @Test
    void blockedSearchBudgetSettlesCurrentHourWithoutStuckContinueOrDegradedWarning() throws Exception {
        long now = Instant.now().getEpochSecond();
        persist(store(now - 4000, true));
        var partialInbound = AccountStore.withCoverage(store(now, false),
                List.of(new long[]{now - 86_400L, now}),
                List.of(new long[]{now - 86_400L, now - 3600L}));
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id())))
                .thenReturn(new TwitterApiIoProvider.SyncOutcome(partialInbound, false, true, true, 0, 0, 0, false, false));
        when(twitter.isDailyContinuationBlocked(eq(false), eq(true), eq(false))).thenReturn(true);

        var settled = load(true);
        assertThat(settled.dailyCacheStatus().canContinue()).isFalse();
        assertThat(settled.dailyCacheStatus().lastAttemptDegraded()).isFalse();
        assertThat(settled.hours24().bannerNotice()).startsWith("本轮可请求的数据已尝试完毕");
        assertThat(settled.hours24().nextRefreshAt()).isEqualTo(Instant.ofEpochSecond(now + 3600));

        // Follow-up check within the same hour reuses settled cache instead of hammering blocked upstream
        var second = load(true);
        assertThat(second.hours24().fromCache()).isTrue();
        assertThat(second.dailyCacheStatus().canContinue()).isFalse();
        verify(twitter, times(1)).syncStoreByHours(eq(self.username()), any(), eq(24), eq(true), eq(true), isNull(), eq(self.id()));
    }

    private MultiPeriodDashboardBundle load(boolean refresh) {
        return service.analyzeWorkspaceBundle(self.username(), ReportPeriod.HOURS_24, null, refresh, "test", self.id(), false);
    }

    private AccountStore.StoredAccount store(long at, boolean complete) {
        return storeFor(self, at, complete);
    }

    private AccountStore.StoredAccount storeFor(XUserSummary account, long at, boolean complete) {
        var base = AccountStore.merge(null, account, Instant.ofEpochSecond(at), List.of(), List.of(),
                List.of(new long[]{at - 86_400L, at}), List.of(new long[]{at - 86_400L, at}), null);
        return AccountStore.withDailyRefreshState(base, at, complete ? at : 0);
    }

    private TwitterApiIoProvider.SyncOutcome outcome(AccountStore.StoredAccount store, boolean degraded) {
        return new TwitterApiIoProvider.SyncOutcome(store, true, degraded, true, 1);
    }

    private AccountStore.StoredAccount withMissingMetricRow(AccountStore.StoredAccount base) {
        long anchor = base.dailyRefreshStartedAt();
        var row = new AccountStore.StoredTweet("synthetic-missing-post", anchor - 100, 'P', "Primary post", "", List.of(), 0,
                new long[]{10, 1, 0, 0, 0}, "synthetic-missing-post", "", anchor - 1000, anchor, Set.of());
        return new AccountStore.StoredAccount(base.version(), self, base.syncedAt(), base.users(), List.of(row),
                base.inbound(), base.tweetCoverage(), base.inboundCoverage(), base.followings(), base.followers(), base.explicitFollowedBy(),
                base.graphFetchedAt(), false, false, false, base.relationshipEvidence(), base.relationshipAttemptedAt(), anchor, 0,
                base.followingOrderById(), base.followerOrderById(), base.coverageProofVersion());
    }

    private void persist(AccountStore.StoredAccount stored) throws Exception {
        AccountStore.write(storeFile(), stored, mapper);
    }

    private Path storeFile() {
        return cacheDir.resolve("users/by-id").resolve(self.id()).resolve("store_v1.json.gz");
    }
}
