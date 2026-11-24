package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
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
        var used = new CommunityHubService.UserUsageEntry(0, 0, null, Instant.now(), today, true, 1, List.of(today));
        Files.writeString(storeFile().resolveSibling("usage_v1.json"), mapper.writeValueAsString(used));
        var partial = AccountStore.withCoverage(started,
                List.of(new long[]{now - 3600, now - 30}), List.of(new long[]{now - 3600, now - 30}));
        when(twitter.syncStoreByHours(eq(self.username()), any(), eq(24), eq(false), eq(true), isNull(), eq(self.id())))
                .thenReturn(outcome(partial, true), outcome(store(now - 30, true), false));
        var incomplete = load(true);
        assertThat(incomplete.hasMoreHistoryForTarget()).isTrue();
        assertThat(incomplete.hours24().cacheRemainingSeconds()).isZero();
        assertThat(incomplete.dailyCacheStatus().timelineCovered()).isFalse();
        assertThat(incomplete.accessPolicy().dailyRefreshUsed()).isEqualTo(1);
        var complete = load(true);
        assertThat(complete.hasMoreHistoryForTarget()).isFalse();
        assertThat(complete.accessPolicy().dailyRefreshUsed()).isEqualTo(1);
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
        persist(AccountStore.withDailyRefreshState(store(now - 4000, true), 0, 0));
        properties.setDefaultProvider("X_OFFICIAL_V2");
        properties.setOfficialBearerToken("isolated-official-token");
        var anchor = Instant.ofEpochSecond(now);
        var bounded = new RawAccountSnapshot(ProviderMode.X_OFFICIAL_V2, true, "Synthetic bounded page", anchor,
                self, List.of(), List.of(), List.of(), false, true, 0,
                anchor.minusSeconds(1800), "next", false, null, "", false, false);
        when(official.fetchSnapshot(self.username(), null)).thenReturn(bounded);
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
        var base = AccountStore.merge(null, self, Instant.ofEpochSecond(at), List.of(), List.of(),
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
                base.graphFetchedAt(), false, false, false, base.relationshipEvidence(), base.relationshipAttemptedAt(), anchor, 0);
    }

    private void persist(AccountStore.StoredAccount stored) throws Exception {
        AccountStore.write(storeFile(), stored, mapper);
    }

    private Path storeFile() {
        return cacheDir.resolve("users/by-id").resolve(self.id()).resolve("store_v1.json.gz");
    }
}
