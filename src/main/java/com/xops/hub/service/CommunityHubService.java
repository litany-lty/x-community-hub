package com.xops.hub.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.assistant.AssistantStore;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.AnalyticsReportModels.AccessPolicy;
import com.xops.hub.model.AnalyticsReportModels.BackgroundRefreshStatus;
import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.DailyCacheStatus;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.AnalyticsReportModels.PeerInteractionStat;
import com.xops.hub.model.AnalyticsReportModels.ServerCapabilities;
import com.xops.hub.model.AnalyticsReportModels.UsageCheckinResponse;
import com.xops.hub.model.RawSnapshotModels.InboundInteraction;
import com.xops.hub.model.RawSnapshotModels.ProviderMode;
import com.xops.hub.model.RawSnapshotModels.RawAccountSnapshot;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.RelationshipEntry;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.PaidCapabilityGate;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.store.AccountStore;
import com.xops.hub.store.OwnerArchiveAccess;
import com.xops.hub.util.HandleNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Service
public class CommunityHubService {

    private static final Logger log = LoggerFactory.getLogger(CommunityHubService.class);
    public static final String AUTHOR_USERNAME = "elidlicom";
    private static final String AUTHOR_DISPLAY_NAME = "Litany（李丰一）";
    private static final String AUTHOR_AVATAR_URL = "/author-avatar.webp";
    private static final String AUTHOR_BANNER_URL = "/author-banner.webp";
    private static final String AUTHOR_DEFAULT_BIO = "Java Dev & AI | Studying Chinese Metaphysics(Dao·道) ䷊";
    public static final String AUTHOR_REPO_URL = "https://github.com/litany-lty/x-community-hub";
    public static final String AUTHOR_COST_STORY =
            "我目前负债十几个、失业、每个月靠贷款交房租。确实承担不起每个月过多的API费用。现在希望通过X平台解决人生危机。也希望朋友们可以谅解。如果限制让你感到不爽，可以到git仓库看开源代码，本地部署与线上效果一致。";
    public static final int TOP_FREE_FULL_RANK = 5;
    public static final int TOP_AUTO_SYNC_RANK = 50;
    public static final String SCHEDULE_OWNER_REQUIRED = "定时发布仅向已验证的作者账号开放。";
    private static final long DAILY_REFRESH_SECONDS = 3600L;
    private static final int MAX_SYNCED_DATES = 30;
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final int MAX_MEMORY_STORES = 200;
    /** 按独立认证账号限流：每人独立计数互不连坐（5 分钟已覆盖请求走内存缓存不扣额度） */
    private static final int ACCOUNT_LIVE_SYNC_LIMIT_PER_MINUTE = 30;
    /** 逐人继续核验（单批最多 100 次关系查询）独立防刷上限，避免恶意脚本高频刷干额度 */
    private static final int ACCOUNT_CONTINUE_RELATIONS_PER_MINUTE = 4;
    private static final int ACCOUNT_CONTINUE_RELATIONS_PER_HOUR = 10;
    private static final long AUTHOR_LIVE_LOOKUP_NEGATIVE_COOLDOWN_MS = 600_000L;
    /** 共享出口 IP 防洪下限；实际阈值随配置的并发用户容量扩展。 */
    private static final int IP_FLOOD_GUARD_PER_MINUTE = 600;
    private static final java.util.regex.Pattern SAFE_HANDLE_KEY_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9_]{1,15}$");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserUsageEntry(
            int usageCount,
            int lastModalShownAtCount,
            Instant optimisticFollowedAt,
            Instant updatedAt,
            List<String> syncedDates,
            long completedReportCycle,
            Instant lastReportSyncAt
    ) {
        @JsonCreator
        public UserUsageEntry {
            usageCount = Math.max(0, usageCount);
            syncedDates = syncedDates == null ? List.of() : List.copyOf(syncedDates);
        }

        public UserUsageEntry(int usageCount, int lastModalShownAtCount, Instant optimisticFollowedAt, Instant updatedAt) {
            this(usageCount, lastModalShownAtCount, optimisticFollowedAt, updatedAt, List.of(), 0, null);
        }
    }

    public record ScheduledPublishAccess(
            boolean allowed,
            boolean followsAuthor,
            Integer authorInteractionRank,
            String reason
    ) {
    }

    public record LongHistoryCandidate(XUserSummary user, int rank, boolean owner,
            int qualificationWindowDays, boolean provisional, long qualificationAt) {
    }

    private record CachedAuthorRanks(
            WeakReference<AccountStore.StoredAccount> source,
            long evidenceExpiresAt,
            Map<String, Integer> rankById,
            Set<String> followerIds
    ) {
    }

    public record CachedAuthorProfile(
            XUserSummary profile,
            Instant expiresAt
    ) {
    }

    private record CachedSnapshot(
            RawAccountSnapshot snapshot,
            MultiPeriodDashboardBundle bundle,
            long ttlSeconds,
            Instant expiresAt,
            String algoFingerprint,
            Instant computedAt
    ) {
    }

    private record ResolvedBundleHolder(
            MultiPeriodDashboardBundle bundle,
            boolean fromCache,
            long ttlSeconds,
            Instant expiresAt
    ) {
    }

    private record RateWindow(long minuteEpoch, int count) {
    }

    private final XOpsProperties properties;
    private final DemoXDataProvider demoProvider;
    private final TwitterApiIoProvider twitterApiIoProvider;
    private final OfficialXApiV2Provider officialXApiV2Provider;
    private final ObjectMapper objectMapper;
    private final XAlgorithmProfileRegistry algoProfileRegistry;
    private final CommunityAnalyticsEngine analyticsEngine;
    private final AssistantStore assistantStore;
    @Autowired(required = false)
    private com.xops.hub.provider.PaidCapabilityGate paidCapabilityGate;

    public void setPaidCapabilityGate(com.xops.hub.provider.PaidCapabilityGate paidCapabilityGate) {
        this.paidCapabilityGate = paidCapabilityGate;
    }

    private final ConcurrentHashMap<String, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();
    private static final int ACCOUNT_LOCK_STRIPES = 256;
    private final Object[] fetchLocks = newLocks();
    /** 仅在未配置磁盘目录时启用的有界内存存储兜底（配置磁盘目录时不在内存保留重复副本） */
    private final ConcurrentHashMap<String, AccountStore.StoredAccount> memoryStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> accountLiveRateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> accountRelationMinuteMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> accountRelationHourMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> ipLiveRateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserUsageEntry> usageMap = new ConcurrentHashMap<>();
    private final Object[] usageLocks = newLocks();
    private final Object legacyUsageLock = new Object();
    private final Object authorLookupLock = new Object();
    private volatile boolean legacyUsageHydrated = false;
    private volatile CachedAuthorProfile cachedAuthorProfile = null;
    private volatile long authorLiveLookupCooldownUntilMs = 0L;
    private volatile CachedAuthorRanks cachedAuthorRanks = null;
    private final AtomicBoolean top50AutoSyncRunning = new AtomicBoolean(false);
    private final Object top50CheckpointLock = new Object();
    private final ExecutorService top50Workers = Executors.newFixedThreadPool(3, task -> {
        Thread t = new Thread(task, "community-top50-sync");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Top50Retry> volatileTop50Retries = new ConcurrentHashMap<>();
    private final AtomicInteger activeForegroundSyncs = new AtomicInteger(0);
    private final AtomicBoolean silentHistoryRunning = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Long> silentHistoryLastAttemptMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> silentHistoryDegradedCooldownUntilMs = new ConcurrentHashMap<>();
    private volatile long silentHistoryBudgetCooldownUntilMs = 0L;
    private final ConcurrentHashMap<String, AtomicBoolean> backgroundRefreshes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BackgroundRefreshStatus> backgroundRefreshStatuses = new ConcurrentHashMap<>();
    private final ExecutorService backgroundRefreshExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "community-background-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService silentHistoryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "community-silent-history");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    public CommunityHubService(
            XOpsProperties properties,
            DemoXDataProvider demoProvider,
            TwitterApiIoProvider twitterApiIoProvider,
            OfficialXApiV2Provider officialXApiV2Provider,
            ObjectMapper objectMapper
    ) {
        this(properties, demoProvider, twitterApiIoProvider, officialXApiV2Provider, objectMapper, null, null);
    }

    public CommunityHubService(
            XOpsProperties properties,
            DemoXDataProvider demoProvider,
            TwitterApiIoProvider twitterApiIoProvider,
            OfficialXApiV2Provider officialXApiV2Provider,
            ObjectMapper objectMapper,
            XAlgorithmProfileRegistry algoProfileRegistry
    ) {
        this(properties, demoProvider, twitterApiIoProvider, officialXApiV2Provider, objectMapper,
                algoProfileRegistry, null);
    }

    @Autowired
    public CommunityHubService(
            XOpsProperties properties,
            DemoXDataProvider demoProvider,
            TwitterApiIoProvider twitterApiIoProvider,
            OfficialXApiV2Provider officialXApiV2Provider,
            ObjectMapper objectMapper,
            XAlgorithmProfileRegistry algoProfileRegistry,
            AssistantStore assistantStore
    ) {
        this.properties = properties;
        this.demoProvider = demoProvider;
        this.twitterApiIoProvider = twitterApiIoProvider;
        this.officialXApiV2Provider = officialXApiV2Provider;
        this.objectMapper = objectMapper;
        this.algoProfileRegistry = algoProfileRegistry;
        this.assistantStore = assistantStore;
        this.analyticsEngine = algoProfileRegistry != null
                ? new CommunityAnalyticsEngine(algoProfileRegistry::currentProfile)
                : new CommunityAnalyticsEngine();
    }

    private String currentAlgoFingerprint() {
        return algoProfileRegistry != null ? algoProfileRegistry.currentState().fingerprint() : "builtin";
    }

    private void putSnapshotCache(
            String cacheKey,
            RawAccountSnapshot snapshot,
            MultiPeriodDashboardBundle bundle,
            long ttlSeconds,
            Instant expiresAt,
            String algoFingerprint
    ) {
        snapshotCache.put(cacheKey, new CachedSnapshot(snapshot, bundle, ttlSeconds, expiresAt, algoFingerprint, Instant.now()));
    }

    public ServerCapabilities getCapabilities() {
        return new ServerCapabilities(
                properties.hasOAuthClient(),
                properties.isRequireLoginSelfOnly(),
                Math.max(0L, properties.getCacheTtlSeconds()),
                resolveZoneId().getId(),
                properties.isFxtwitterEnabled()
        );
    }

    public String getActiveProviderName() {
        return resolveProviderMode(null).name();
    }

    public int getActiveCacheSize() {
        return snapshotCache.size();
    }

    /**
     * 查询指定维度的社区运营报告（直接复用预计算的三维度 Bundle，零重复计算）。
     * 5 分钟服务端缓存期内不重复读取存储或请求上游。
     */
    public CommunityDashboardReport analyzeAccount(
            String rawHandle,
            int days,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp
    ) {
        return analyzeAccount(rawHandle, days, requestedProvider, forceRefresh, clientIp, null);
    }

    public CommunityDashboardReport analyzeAccount(String rawHandle, int days, String requestedProvider,
            boolean forceRefresh, String clientIp, String expectedAccountId) {
        int normalizedDays = CommunityAnalyticsEngine.resolvePeriodDays(days);
        MultiPeriodDashboardBundle bundle = analyzeAccountBundle(rawHandle, normalizedDays, requestedProvider, forceRefresh, clientIp, expectedAccountId);
        if (normalizedDays == 2) {
            return bundle.day();
        }
        if (normalizedDays == 7) {
            return bundle.week();
        }
        return bundle.month();
    }

    /**
     * 按需递进加载并返回同源「30天动态窗口 / 7天动态窗口 / 48小时动态窗口」三套完整报表：
     * - 默认首屏仅拉取覆盖 48 小时的数据（极致省成本、秒开）；
     * - 当用户切换至 7 天或 30 天且当前缓存未覆盖目标窗口时，直接从上次停下的分页游标向历史增量续拉并合并（48h ⊆ 7d ⊆ 30d，已有数据零重复加载）；
     * - 5 分钟服务端缓存期内，已覆盖的任意维度切换零网络请求、零重拉。
     */
    public MultiPeriodDashboardBundle analyzeAccountBundle(
            String rawHandle,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp
    ) {
        return analyzeAccountBundle(rawHandle, 2, requestedProvider, forceRefresh, clientIp);
    }

    public MultiPeriodDashboardBundle analyzeAccountBundle(
            String rawHandle,
            int targetDays,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp
    ) {
        return analyzeAccountBundle(rawHandle, targetDays, requestedProvider, forceRefresh, clientIp, null);
    }

    public MultiPeriodDashboardBundle analyzeAccountBundle(String rawHandle, int targetDays, String requestedProvider,
            boolean forceRefresh, String clientIp, String expectedAccountId) {
        return analyzeAccountBundle(rawHandle, targetDays, requestedProvider, forceRefresh, clientIp, expectedAccountId, false);
    }

    public MultiPeriodDashboardBundle analyzeAccountBundle(String rawHandle, int targetDays, String requestedProvider,
            boolean forceRefresh, String clientIp, String expectedAccountId, boolean continueRelations) {
        int requiredDays = CommunityAnalyticsEngine.resolvePeriodDays(targetDays);
        // 严格执行 1 小时内不做重新刷新动作：仅当已有缓存尚未覆盖更大目标时间窗时才做游标续拉
        ResolvedBundleHolder holder = obtainBundle(rawHandle, requiredDays, requestedProvider, clientIp, expectedAccountId, continueRelations);
        return refreshBundleTimestamps(holder.bundle(), holder.fromCache(), holder.ttlSeconds(), holder.expiresAt());
    }

    /**
     * 按已累计的同步天数、作者关注关系与互动排名返回工作台维度分析。
     */
    public CommunityDashboardReport analyzeWorkspaceReport(
            String rawHandle,
            ReportPeriod period,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp,
            String expectedAccountId
    ) {
        ReportPeriod target = period != null ? period : ReportPeriod.HOURS_24;
        MultiPeriodDashboardBundle bundle = analyzeWorkspaceBundle(
                rawHandle, target, requestedProvider, forceRefresh, clientIp, expectedAccountId, false);
        AccessPolicy policy = bundle.accessPolicy();
        if (policy != null && policy.unlockedPeriods() != null && !policy.unlockedPeriods().contains(target.code)) {
            String reason = policy.lockedReasons() != null
                    ? policy.lockedReasons().getOrDefault(target.code, "该时间维度尚未解锁")
                    : "该时间维度尚未解锁";
            throw new XDataProviderException(403, reason);
        }
        return switch (target) {
            case HOURS_24 -> bundle.hours24();
            case HOURS_48 -> bundle.day();
            case WEEK -> bundle.week();
            case MONTH -> bundle.month();
        };
    }

    public MultiPeriodDashboardBundle analyzeWorkspaceBundle(
            String rawHandle,
            ReportPeriod targetPeriod,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp,
            String expectedAccountId,
            boolean continueRelations
    ) {
        return analyzeWorkspaceBundle(rawHandle, targetPeriod, requestedProvider, forceRefresh,
                clientIp, expectedAccountId, continueRelations, false);
    }

    public MultiPeriodDashboardBundle analyzeWorkspaceBundle(
            String rawHandle,
            ReportPeriod targetPeriod,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp,
            String expectedAccountId,
            boolean continueRelations,
            boolean preferStored
    ) {
        String handleKey = validateIsolatedHandleKey(HandleNormalizer.normalize(rawHandle));
        String key = resolveProviderMode(requestedProvider).name() + ":" + accountOwnerKey(handleKey, expectedAccountId);
        BackgroundRefreshStatus previous = backgroundRefreshStatuses.get(key);
        MultiPeriodDashboardBundle bundle = analyzeWorkspaceBundle(rawHandle, targetPeriod, requestedProvider, forceRefresh,
                clientIp, expectedAccountId, continueRelations, false, preferStored);
        BackgroundRefreshStatus status = backgroundRefreshStatuses.get(key);
        if (status == null) return bundle;
        boolean queuedDuringRequest = preferStored && bundle.hours24() != null && bundle.hours24().fromCache()
                && (previous == null || !Objects.equals(previous.refreshId(), status.refreshId()) || "RUNNING".equals(previous.state()));
        if (queuedDuringRequest && Set.of("SUCCEEDED", "PARTIAL", "CACHED").contains(status.state())) {
            return bundle.withBackgroundRefreshStatus(status.withNewerResult());
        }
        if (!queuedDuringRequest && !"RUNNING".equals(status.state()) && (forceRefresh || bundle.dailyCacheStatus() == null
                || status.cycleStartedAt() == null || !status.cycleStartedAt().equals(bundle.dailyCacheStatus().cycleStartedAt()))) return bundle;
        return bundle.withBackgroundRefreshStatus(status);
    }

    private MultiPeriodDashboardBundle analyzeWorkspaceBundle(
            String rawHandle,
            ReportPeriod targetPeriod,
            String requestedProvider,
            boolean forceRefresh,
            String clientIp,
            String expectedAccountId,
            boolean continueRelations,
            boolean backgroundExecution,
            boolean preferStored
    ) {
        return OwnerArchiveAccess.duringRefresh(properties.getDiskCacheDir(), expectedAccountId,
                () -> loadWorkspaceBundle(rawHandle, targetPeriod, requestedProvider, forceRefresh, clientIp,
                        expectedAccountId, continueRelations, backgroundExecution, preferStored));
    }

    private MultiPeriodDashboardBundle loadWorkspaceBundle(String rawHandle, ReportPeriod targetPeriod,
            String requestedProvider, boolean forceRefresh, String clientIp, String expectedAccountId,
            boolean continueRelations, boolean backgroundExecution, boolean preferStored) {
        ReportPeriod resolvedPeriod = targetPeriod != null ? targetPeriod : ReportPeriod.HOURS_24;
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);
        ProviderMode resolvedMode = resolveProviderMode(requestedProvider);
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        String cacheKey = resolvedMode.name() + ":" + ownerKey;
        long configuredTtl = resolvedMode == ProviderMode.DEMO
                ? Math.max(0L, properties.getCacheTtlSeconds()) : DAILY_REFRESH_SECONDS;
        ZoneId zone = resolveZoneId();
        String today = LocalDate.now(zone).toString();

        Instant requestedAt = Instant.now();
        Object lock = fetchLockFor(ownerKey);
        AtomicBoolean runningRefresh = backgroundRefreshes.get(cacheKey);
        CachedSnapshot activeSnapshot = snapshotCache.get(cacheKey);
        if (!backgroundExecution && !continueRelations && (!forceRefresh || preferStored)
                && runningRefresh != null && runningRefresh.get()
                && cacheIdentityMatches(activeSnapshot, handleKey, expectedAccountId)) {
            return refreshWorkspaceTimestamps(activeSnapshot.bundle(), true,
                    activeSnapshot.ttlSeconds(), activeSnapshot.expiresAt(), activeSnapshot.bundle().accessPolicy());
        }
        synchronized (lock) {
            Instant now = Instant.now();
            UserUsageEntry quotaEntry = readUsageSnapshot(ownerKey);

            String algoFp = currentAlgoFingerprint();
            CachedSnapshot active = snapshotCache.get(cacheKey);
            boolean cacheValid = configuredTtl > 0 && isValidAccountCacheHit(active, handleKey, expectedAccountId, now);

            Integer initialRank = resolveAuthorInteractionRank(handleKey, expectedAccountId);

            if (continueRelations) {
                if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                    throw new XDataProviderException(400, "当前数据源不支持逐人继续核验");
                }
                boolean isOwner = isAuthorIdentity(handleKey, expectedAccountId);
                if (!isOwner && (!twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null)) {
                    if (paidCapabilityGate != null) {
                        paidCapabilityGate.scheduleIdempotentRetry(
                                "continue-relations:" + handleKey,
                                "twitterapi_io_relation",
                                handleKey,
                                expectedAccountId,
                                com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE,
                                () -> {}
                        );
                    }
                    throw new XDataProviderException(403, com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE);
                }
                checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
                checkContinueRelationsRateLimit(handleKey, expectedAccountId);
                AccountStore.StoredAccount stored = loadStore(handleKey, expectedAccountId);
                if (stored != null) verifyAccountOwner(stored.profile(), handleKey, expectedAccountId);
                TwitterApiIoProvider.SyncOutcome continued;
                activeForegroundSyncs.incrementAndGet();
                try {
                    continued = isOwner
                            ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                    handleKey, expectedAccountId, true,
                                    () -> twitterApiIoProvider.continueRelationships(handle, stored, expectedAccountId))
                            : twitterApiIoProvider.continueRelationships(handle, stored, expectedAccountId);
                } finally {
                    activeForegroundSyncs.decrementAndGet();
                }
                if (isOwner && continued.degraded() && paidCapabilityGate != null) {
                    paidCapabilityGate.scheduleIdempotentRetry(
                            "continue-relations:elidlicom:" + today,
                            "twitterapi_io_relation",
                            com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                            expectedAccountId,
                            "逐人关系核验存在未完成项",
                            () -> analyzeWorkspaceBundle(handle, ReportPeriod.HOURS_24, requestedProvider, false, clientIp, expectedAccountId, true), false, true
                    );
                } else if (isOwner && paidCapabilityGate != null) {
                    paidCapabilityGate.markSucceeded("continue-relations:elidlicom:" + today);
                }
                verifyAccountOwner(continued.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, continued.store());
                RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(continued.store(),
                        "本次仅继续逐人关系核验；未成功确认的对象仍保留未知。", continued.degraded());
                Integer rankAfter = resolveAuthorInteractionRank(handleKey, snap.profile().id());
                UserUsageEntry latestUsage = usageWithProvenCompletion(readUsageSnapshot(ownerKey), continued.store(), zone);
                Instant expires = dailyRefreshExpiresAt(continued.store(), latestUsage, now);
                AccessPolicy policy = buildAccessPolicy(handleKey, snap, latestUsage, zone, today, rankAfter);
                MultiPeriodDashboardBundle bundle = buildWorkspaceBundle(
                        snap, policy, false, configuredTtl, expires, false,
                        TwitterApiIoProvider.pendingRelationshipChecks(continued.store()))
                        .withDailyCacheStatus(dailyCacheStatus(continued.store(), latestUsage, continued.degraded()));
                if (configuredTtl > 0) putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expires, algoFp);
                return refreshWorkspaceTimestamps(bundle, false, configuredTtl, expires, policy);
            }

            // Every request reads real persistence under the same account lock, including cold starts.
            boolean authorCaller = isAuthorIdentity(handleKey, expectedAccountId);
            if (authorCaller && forceRefresh) {
                if (twitterApiIoProvider != null) {
                    twitterApiIoProvider.resetFreeCircuitForActiveSync();
                }
                if (paidCapabilityGate != null) {
                    paidCapabilityGate.resetByPrefix("silent-history:elidlicom");
                }
            }
            AccountStore.StoredAccount rawStored = resolvedMode == ProviderMode.DEMO
                    ? null : loadStore(handleKey, expectedAccountId);
            if (rawStored != null) verifyAccountOwner(rawStored.profile(), handleKey, expectedAccountId);
            AccountStore.StoredAccount stored = AccountStore.pruneToRollingWindow(rawStored, now,
                    rawStored != null && retainsOwnerHistory(rawStored.profile()));
            quotaEntry = usageWithProvenCompletion(quotaEntry, stored, zone);
            boolean completedDaily = isDailyRefreshComplete(stored);
            boolean enforceHourlyCache = !authorCaller;
            boolean freshDaily = enforceHourlyCache && completedDaily && completedWithinHour(stored, quotaEntry, now);
            boolean exhaustedDailyAttempts = enforceHourlyCache
                    && canContinueDailyRefresh(stored, now)
                    && !dailyCanContinue(stored);
            if (preferStored && forceRefresh && !backgroundExecution && resolvedMode != ProviderMode.DEMO
                    && stored != null && !freshDaily && !exhaustedDailyAttempts) {
                return serveStoredAndScheduleRefresh(
                        cacheKey, rawHandle, handleKey, resolvedPeriod, requestedProvider, clientIp, expectedAccountId,
                        stored, active, completedDaily, quotaEntry, zone, today, initialRank, configuredTtl, now, algoFp,
                        "已返回最近一次完整入库快照；后台刷新已受理。");
            }
            Instant checkedNow = Instant.now();
            boolean coalesced = !backgroundExecution && active != null && active != activeSnapshot
                    && active.computedAt() != null && !active.computedAt().isBefore(requestedAt);
            BackgroundRefreshStatus latestBg = backgroundRefreshStatuses.get(cacheKey);
            long recentBgWindowSeconds = 120L;
            boolean recentBgResult = !backgroundExecution && latestBg != null
                    && "SUCCEEDED".equals(latestBg.state()) && latestBg.completedAt() != null
                    && active != null && active.computedAt() != null
                    && (latestBg.startedAt() == null || !active.computedAt().isBefore(latestBg.startedAt().minusSeconds(2)))
                    && Duration.between(latestBg.completedAt(), checkedNow).getSeconds() < recentBgWindowSeconds;
            boolean justComputedSnapshot = !backgroundExecution && !forceRefresh && active != null
                    && active.computedAt() != null
                    && Duration.between(active.computedAt(), checkedNow).getSeconds() < 15L;
            boolean recentDegradedCooldown = !authorCaller && !backgroundExecution && active != null && active.computedAt() != null
                    && active.bundle() != null && active.bundle().dailyCacheStatus() != null
                    && active.bundle().dailyCacheStatus().lastAttemptDegraded()
                    && Duration.between(active.computedAt(), checkedNow).getSeconds() < 60;
            boolean reuseRecentAttempt = (!completedDaily || recentDegradedCooldown)
                    && (coalesced || (!forceRefresh && (recentBgResult || justComputedSnapshot || recentDegradedCooldown)));
            if (resolvedMode != ProviderMode.DEMO && stored != null
                    && reuseRecentAttempt && !freshDaily
                    && cacheIdentityMatches(active, handleKey, expectedAccountId)
                    && Objects.equals(algoFp, active.algoFingerprint())
                    && active.bundle() != null && active.bundle().hours24() != null) {
                AccessPolicy policy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
                return refreshWorkspaceTimestamps(active.bundle(), true, active.ttlSeconds(), active.expiresAt(), policy)
                        .withDailyCacheStatus(active.bundle().dailyCacheStatus() != null
                                ? active.bundle().dailyCacheStatus() : dailyCacheStatus(stored, quotaEntry, false));
            }
            if (resolvedMode != ProviderMode.DEMO && stored != null && (freshDaily || exhaustedDailyAttempts)) {
                Instant warmExpires = dailyRefreshExpiresAt(stored, quotaEntry, now);
                if (active != null && active.bundle() != null && active.snapshot() != null
                        && Objects.equals(stored.syncedAtInstant(), active.snapshot().fetchedAt())
                        && Objects.equals(algoFp, active.algoFingerprint())
                        && active.bundle().hasMoreHistoryForTarget() == !completedDaily) {
                    AccessPolicy policy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
                    AccessPolicy cachedPolicy = active.bundle().accessPolicy();
                    if (cachedPolicy != null
                            && Objects.equals(cachedPolicy.unlockedPeriods(), policy.unlockedPeriods())
                            && cachedPolicy.accumulatedSummaryMode() == policy.accumulatedSummaryMode()) {
                        return refreshWorkspaceTimestamps(active.bundle(), true, configuredTtl, warmExpires, policy)
                                .withDailyCacheStatus(dailyCacheStatus(stored, quotaEntry, false));
                    }
                }
                RawAccountSnapshot warmSnap = TwitterApiIoProvider.snapshotOf(stored, "", !completedDaily);
                warmSnap = withNotice(warmSnap, buildDailyRefreshNotice(stored, completedDaily));
                AccessPolicy policy = buildAccessPolicy(handleKey, warmSnap, quotaEntry, zone, today, initialRank);
                MultiPeriodDashboardBundle warmBundle = buildWorkspaceBundle(
                        warmSnap, policy, true, configuredTtl, warmExpires, !completedDaily,
                        TwitterApiIoProvider.pendingRelationshipChecks(stored)).withDailyCacheStatus(dailyCacheStatus(stored, quotaEntry, false));
                putSnapshotCache(cacheKey, warmSnap, warmBundle, configuredTtl, warmExpires, algoFp);
                return refreshWorkspaceTimestamps(warmBundle, true, configuredTtl, warmExpires, policy);
            }
            if (preferStored && !forceRefresh && !backgroundExecution && !continueRelations
                    && resolvedMode != ProviderMode.DEMO && stored != null
                    && !freshDaily && !exhaustedDailyAttempts
                    && !reuseRecentAttempt) {
                return serveStoredAndScheduleRefresh(
                        cacheKey, rawHandle, handleKey, resolvedPeriod, requestedProvider, clientIp, expectedAccountId,
                        stored, active, completedDaily, quotaEntry, zone, today, initialRank, configuredTtl, now, algoFp,
                        "已返回最近一次完整入库快照；后台刷新已受理。");
            }
            if (resolvedMode == ProviderMode.DEMO && cacheValid && !forceRefresh) {
                AccessPolicy policy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
                MultiPeriodDashboardBundle demoBundle = buildWorkspaceBundle(active.snapshot(), policy, true,
                        active.ttlSeconds(), active.expiresAt(), false, 0);
                return refreshWorkspaceTimestamps(demoBundle, true, active.ttlSeconds(), active.expiresAt(), policy);
            }

            if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                AccountStore.StoredAccount officialStored = null;
                RawAccountSnapshot snap;
                try {
                    snap = switch (resolvedMode) {
                    case X_OFFICIAL_V2 -> {
                        boolean officialOwner = isAuthorIdentity(handleKey, expectedAccountId);
                        if (!officialOwner && paidCapabilityGate != null) {
                            throw new XDataProviderException(403, com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE);
                        }
                        checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
                        RawAccountSnapshot fetched = officialOwner
                                ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                        handleKey, expectedAccountId, true,
                                        () -> officialXApiV2Provider.fetchSnapshot(handle, null, expectedAccountId))
                                : officialXApiV2Provider.fetchSnapshot(handle, null, expectedAccountId);
                        verifyAccountOwner(fetched.profile(), handleKey, expectedAccountId);
                        Instant syncAt = fetched.fetchedAt() != null ? fetched.fetchedAt() : now;
                        Instant cutoff24 = syncAt.minus(Duration.ofHours(ReportPeriod.HOURS_24.windowHours));
                        List<RawTweet> tweets24 = (fetched.timeline() != null ? fetched.timeline() : List.<RawTweet>of()).stream()
                                .filter(t -> t != null && t.createdAt() != null && !t.createdAt().isBefore(cutoff24))
                                .toList();
                        List<InboundInteraction> inbound24 = (fetched.inboundInteractions() != null ? fetched.inboundInteractions() : List.<InboundInteraction>of()).stream()
                                .filter(i -> i != null && i.createdAt() != null && !i.createdAt().isBefore(cutoff24))
                                .toList();
                        List<XUserSummary> fg = new ArrayList<>();
                        List<XUserSummary> fr = new ArrayList<>();
                        Set<String> back = new LinkedHashSet<>();
                        if (fetched.relationships() != null) {
                            for (RelationshipEntry r : fetched.relationships()) {
                                if (r == null || r.user() == null) continue;
                                if (r.iFollowThem()) fg.add(r.user());
                                if (r.theyFollowMe()) fr.add(r.user());
                                if (r.iFollowThem() && r.theyFollowMe() && r.user().username() != null) {
                                    back.add(r.user().username().toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                        // Earlier code asserted a whole day from any bounded first page. Discard those
                        // unproven intervals while retaining the actual historical rows.
                        AccountStore.StoredAccount provenBase = AccountStore.verifiedCoverageBase(stored);
                        List<long[]> tweetProof = officialCoverage(fetched.timelineTailTime(), fetched.timelineExhausted(), cutoff24, syncAt);
                        List<long[]> inboundProof = officialCoverage(fetched.mentionsTailTime(), fetched.mentionsExhausted(), cutoff24, syncAt);
                        AccountStore.StoredAccount mergedOfficial = AccountStore.merge(
                                provenBase,
                                fetched.profile(),
                                syncAt,
                                tweets24,
                                inbound24,
                                tweetProof,
                                inboundProof,
                                new AccountStore.GraphUpdate(fg, fr, back, fetched.followersTruncated(), fetched.followingsTruncated(),
                                        fetched.followingsRequestFailed(), fetched.followersRequestFailed()), Map.of(), officialOwner
                        );
                        boolean full = streamCoversDaily(tweetProof, syncAt.getEpochSecond())
                                && streamCoversDaily(inboundProof, syncAt.getEpochSecond())
                                && !fetched.degradedPartial();
                        long priorCompletion = stored == null ? 0 : stored.dailyRefreshedAt();
                        mergedOfficial = AccountStore.withDailyRefreshState(mergedOfficial, syncAt.getEpochSecond(), priorCompletion);
                        if (full && pendingDailyMetrics(mergedOfficial) == 0) {
                            mergedOfficial = AccountStore.withDailyRefreshState(mergedOfficial, syncAt.getEpochSecond(), syncAt.getEpochSecond());
                        }
                        officialStored = saveReportStore(handleKey, expectedAccountId, stored, mergedOfficial, quotaEntry);
                        yield TwitterApiIoProvider.snapshotOf(officialStored, fetched.bannerNotice(), fetched.degradedPartial());
                    }
                    case DEMO -> demoSnapshotForAccount(handle, expectedAccountId);
                    default -> throw new XDataProviderException(500, "不支持的数据源模式");
                    };
                } catch (XDataProviderException ex) {
                    if (resolvedMode != ProviderMode.X_OFFICIAL_V2 || stored == null || !temporaryReportFailure(ex)) throw ex;
                    return reportAfterTemporaryFailure(stored, ex, handleKey, quotaEntry, zone, today, initialRank,
                            configuredTtl, now, cacheKey, algoFp);
                }
                verifyAccountOwner(snap.profile(), handleKey, expectedAccountId);
                boolean complete = officialStored == null || isDailyRefreshComplete(officialStored);
                harvestAuthorProfileFromSnapshot(snap);
                UserUsageEntry updatedUsage = complete && officialStored != null
                        ? recordWorkspaceSyncSuccess(ownerKey, stored, officialStored, zone) : quotaEntry;
                Instant expiresAt = officialStored != null ? dailyRefreshExpiresAt(officialStored, updatedUsage, now)
                        : now.plusSeconds(configuredTtl);
                Integer rankAfter = resolveAuthorInteractionRank(handleKey, snap.profile().id());
                AccessPolicy policy = buildAccessPolicy(handleKey, snap, updatedUsage, zone, today, rankAfter);
                MultiPeriodDashboardBundle bundle = buildWorkspaceBundle(
                        snap, policy, false, configuredTtl, expiresAt, !complete, 0);
                if (officialStored != null) bundle = bundle.withDailyCacheStatus(dailyCacheStatus(officialStored, updatedUsage, false));
                if (configuredTtl > 0) {
                    pruneCacheIfNeeded();
                    putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expiresAt, algoFp);
                }
                return refreshWorkspaceTimestamps(bundle, false, configuredTtl, expiresAt, policy);
            }

            checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
            // 单次统一只加载最近 24 小时数据，每日合并写入物理存储，并动态保留近 30 天滚动窗口
            boolean lightweightDaily = true;
            int targetSyncHours = ReportPeriod.HOURS_24.windowHours;

            boolean bindPaidScope = authorCaller || !twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null;
            String scopedCaller = authorCaller ? com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME : handleKey;
            if (authorCaller && forceRefresh && twitterApiIoProvider != null) {
                twitterApiIoProvider.resetFreeCircuitForActiveSync();
            }
            TwitterApiIoProvider.SyncOutcome outcome;
            AccountStore.StoredAccount savedReport;
            activeForegroundSyncs.incrementAndGet();
            try {
                AccountStore.StoredAccount baseStore = stored;
                boolean staleOwnerFreeHead = authorCaller && stored != null
                        && twitterApiIoProvider.canReadTimelineForFree("")
                        && (now.getEpochSecond() - stored.syncedAt() >= 45L);
                boolean doHeadSync = (authorCaller && forceRefresh) || staleOwnerFreeHead || !canContinueDailyRefresh(stored, now);
                outcome = bindPaidScope
                        ? com.xops.hub.provider.PaidCapabilityGate.withCaller(handleKey, expectedAccountId, authorCaller,
                                () -> twitterApiIoProvider.syncStoreByHours(
                                        handle, baseStore, targetSyncHours, doHeadSync, lightweightDaily, null, expectedAccountId))
                        : twitterApiIoProvider.syncStoreByHours(
                                handle, baseStore, targetSyncHours, doHeadSync, lightweightDaily, null, expectedAccountId);
                verifyAccountOwner(outcome.store().profile(), handleKey, expectedAccountId);
                savedReport = saveReportStore(handleKey, expectedAccountId, stored, outcome.store(), quotaEntry);
                if (paidCapabilityGate != null && (authorCaller || properties.isFxtwitterEnabled())) {
                    String retryCategory = authorCaller ? "twitterapi_io_read" : "fxtwitter_free_read";
                    String syncRetryKey = (authorCaller ? "workspace-sync:elidlicom:" : "workspace-free-sync:" + handleKey + ":")
                            + Math.max(1L, outcome.store().dailyRefreshStartedAt());
                    String dayRetryKey = (authorCaller ? "workspace-sync:elidlicom:" : "workspace-free-sync:" + handleKey + ":") + today;
                    if (outcome.degraded()) {
                        paidCapabilityGate.scheduleIdempotentRetry(
                                syncRetryKey,
                                retryCategory,
                                scopedCaller,
                                expectedAccountId,
                                authorCaller ? "工作台付费同步存在未完成项" : "工作台免费公开数据同步存在未完成项",
                                () -> analyzeWorkspaceBundle(handle, ReportPeriod.HOURS_24, requestedProvider, true, clientIp, expectedAccountId, false),
                                forceRefresh, authorCaller
                        );
                    } else {
                        paidCapabilityGate.markSucceeded(syncRetryKey);
                        paidCapabilityGate.markSucceeded(dayRetryKey);
                    }
                }
            } catch (XDataProviderException ex) {
                boolean retryableFreeFailure = !authorCaller
                        && properties.isFxtwitterEnabled()
                        && ex.getStatusCode() != 401
                        && ex.getStatusCode() != 403
                        && ex.getStatusCode() != 404;
                if (paidCapabilityGate != null && (authorCaller || retryableFreeFailure)) {
                    String retryCategory = authorCaller ? "twitterapi_io_read" : "fxtwitter_free_read";
                    String errRetryKey = (authorCaller ? "workspace-sync:elidlicom:" : "workspace-free-sync:" + handleKey + ":") + today;
                    paidCapabilityGate.scheduleIdempotentRetry(
                            errRetryKey,
                            retryCategory,
                            scopedCaller,
                            expectedAccountId,
                            ex.getMessage(),
                            () -> analyzeWorkspaceBundle(handle, ReportPeriod.HOURS_24, requestedProvider, true, clientIp, expectedAccountId, false),
                            forceRefresh, authorCaller
                    );
                }
                if (stored != null && stored.profile() != null && temporaryReportFailure(ex)) {
                    return reportAfterTemporaryFailure(stored, ex, handleKey, quotaEntry, zone, today, initialRank,
                            configuredTtl, now, cacheKey, algoFp);
                }
                throw ex;
            } finally {
                activeForegroundSyncs.decrementAndGet();
            }

            RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(outcome.store(), "", outcome.degraded());
            harvestAuthorProfileFromSnapshot(snap);
            String resolvedAccountId = snap.profile() != null ? snap.profile().id() : expectedAccountId;
            Integer rankAfter = resolveAuthorInteractionRank(handleKey, resolvedAccountId);

            boolean complete = isDailyRefreshComplete(outcome.store());
            UserUsageEntry updatedUsage = complete
                    ? recordWorkspaceSyncSuccess(ownerKey, stored, savedReport, zone) : quotaEntry;
            AccessPolicy policy = buildAccessPolicy(handleKey, snap, updatedUsage, zone, today, rankAfter);
            snap = withNotice(snap, buildDailyRefreshNotice(outcome.store(), complete));

            Instant expiresAt = dailyRefreshExpiresAt(savedReport, updatedUsage, now);
            boolean hasMore = !complete;
            long cycleAnchor = outcome.store().dailyRefreshStartedAt();
            boolean continuationBlocked = !complete && cycleAnchor > 0
                    && reportContinuationBlocked(outcome.store(),
                            !streamCoversDaily(outcome.store().tweetCoverage(), cycleAnchor),
                            !streamCoversDaily(outcome.store().inboundCoverage(), cycleAnchor),
                            unattemptedDailyMetrics(outcome.store()) > 0);
            MultiPeriodDashboardBundle bundle = buildWorkspaceBundle(
                    snap, policy, false, configuredTtl, expiresAt, hasMore,
                    TwitterApiIoProvider.pendingRelationshipChecks(outcome.store()))
                    .withDailyCacheStatus(dailyCacheStatus(savedReport, updatedUsage, outcome.dailyMetricBatchFailed()
                            || (outcome.degraded() && !outcome.progressed() && !continuationBlocked)));
            if (configuredTtl > 0) {
                pruneCacheIfNeeded();
                putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expiresAt, algoFp);
            }
            return refreshWorkspaceTimestamps(bundle, false, configuredTtl, expiresAt, policy);
        }
    }

    private static boolean temporaryReportFailure(XDataProviderException ex) {
        return switch (ex.getStatusCode()) {
            case 429, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    private MultiPeriodDashboardBundle reportAfterTemporaryFailure(AccountStore.StoredAccount stored,
            XDataProviderException failure, String handleKey, UserUsageEntry usage, ZoneId zone, String today,
            Integer rank, long configuredTtl, Instant now, String cacheKey, String algoFingerprint) {
        log.warn("workspace_refresh refresh_id={} stage=snapshot_fallback result=served status={}",
                MDC.get("workspaceRefreshId"), failure.getStatusCode());
        String notice = com.xops.hub.provider.UpstreamBudgetGuard.isBudgetReached(failure) ? failure.getMessage()
                : failure.getStatusCode() == 429 ? "本次更新受到预算或请求频率限制，显示已入库数据。"
                : "本次更新未完成，显示已入库数据（" + stored.tweets().size() + " 条动态 / "
                        + stored.inbound().size() + " 条互动），上次完整同步时间已保留。";
        RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(AccountStore.withoutRelationshipEvidence(stored), notice, true);
        long ttl = Math.min(30L, configuredTtl > 0 ? configuredTtl : 30L);
        Instant expires = dailyRefreshExpiresAt(stored, usage, now);
        AccessPolicy policy = buildAccessPolicy(handleKey, snap, usage, zone, today, rank);
        var bundle = buildWorkspaceBundle(snap, policy, true, ttl, expires, true,
                TwitterApiIoProvider.pendingRelationshipChecks(stored)).withDailyCacheStatus(dailyCacheStatus(stored, usage, true));
        if (configuredTtl > 0) {
            pruneCacheIfNeeded();
            putSnapshotCache(cacheKey, snap, bundle, ttl, expires, algoFingerprint);
        }
        return refreshWorkspaceTimestamps(bundle, true, ttl, expires, policy);
    }

    private MultiPeriodDashboardBundle serveStoredAndScheduleRefresh(
            String cacheKey, String rawHandle, String handleKey, ReportPeriod resolvedPeriod,
            String requestedProvider, String clientIp, String expectedAccountId,
            AccountStore.StoredAccount stored, CachedSnapshot active, boolean completedDaily,
            UserUsageEntry quotaEntry, ZoneId zone, String today, Integer initialRank,
            long configuredTtl, Instant now, String algoFp, String notice
    ) {
        Instant expires = dailyRefreshExpiresAt(stored, quotaEntry, now);
        if (active != null && active.bundle() != null && active.snapshot() != null
                && cacheIdentityMatches(active, handleKey, expectedAccountId)
                && Objects.equals(stored.syncedAtInstant(), active.snapshot().fetchedAt())
                && Objects.equals(algoFp, active.algoFingerprint())) {
            AccessPolicy cachedPolicy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
            scheduleWorkspaceRefresh(cacheKey, rawHandle, resolvedPeriod, requestedProvider,
                    clientIp, expectedAccountId);
            return refreshWorkspaceTimestamps(active.bundle(), true, configuredTtl, expires, cachedPolicy)
                    .withDailyCacheStatus(dailyCacheStatus(stored, quotaEntry, false));
        }
        RawAccountSnapshot persisted = TwitterApiIoProvider.snapshotOf(stored, notice, !completedDaily);
        AccessPolicy policy = buildAccessPolicy(handleKey, persisted, quotaEntry, zone, today, initialRank);
        MultiPeriodDashboardBundle immediate = buildWorkspaceBundle(
                persisted, policy, true, configuredTtl, expires, !completedDaily,
                TwitterApiIoProvider.pendingRelationshipChecks(stored))
                .withDailyCacheStatus(dailyCacheStatus(stored, quotaEntry, false));
        if (configuredTtl > 0) putSnapshotCache(cacheKey, persisted, immediate,
                configuredTtl, expires, algoFp);
        scheduleWorkspaceRefresh(cacheKey, rawHandle, resolvedPeriod, requestedProvider,
                clientIp, expectedAccountId);
        return refreshWorkspaceTimestamps(immediate, true, configuredTtl, expires, policy);
    }

    private void scheduleWorkspaceRefresh(String key, String handle, ReportPeriod period,
            String provider, String clientIp, String accountId) {
        if (backgroundRefreshStatuses.size() > 1024) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(1));
            backgroundRefreshStatuses.entrySet().removeIf(entry -> entry.getValue().completedAt() != null
                    && entry.getValue().completedAt().isBefore(cutoff));
        }
        Instant startedAt = Instant.now();
        long acceptedNanos = System.nanoTime();
        String refreshId = java.util.UUID.randomUUID().toString();
        AtomicBoolean gate = new AtomicBoolean(true);
        synchronized (backgroundRefreshes) {
            var current = backgroundRefreshStatuses.get(key);
            if (backgroundRefreshes.containsKey(key) && current != null && "RUNNING".equals(current.state())) return;
            backgroundRefreshes.put(key, gate);
            backgroundRefreshStatuses.put(key, new BackgroundRefreshStatus("RUNNING", startedAt, null, "", refreshId));
        }
        backgroundRefreshExecutor.submit(() -> {
            try (var scope = MDC.putCloseable("workspaceRefreshId", refreshId)) {
                log.info("workspace_refresh refresh_id={} stage=queue elapsed_ms={} result=started",
                        refreshId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedNanos));
                MultiPeriodDashboardBundle bundle = analyzeWorkspaceBundle(
                        handle, period, provider, true, clientIp, accountId, false, true, false);
                DailyCacheStatus prevStatus = bundle.dailyCacheStatus();
                Instant cycleStart = prevStatus != null ? prevStatus.cycleStartedAt() : null;
                int batchStep = 1;
                while (batchStep < 12
                        && !Thread.currentThread().isInterrupted()
                        && prevStatus != null
                        && prevStatus.canContinue()
                        && !prevStatus.lastAttemptDegraded()
                        && cycleStart != null) {
                    MultiPeriodDashboardBundle nextBundle = analyzeWorkspaceBundle(
                            handle, ReportPeriod.HOURS_24, provider, false, clientIp, accountId, false, true, false);
                    bundle = nextBundle;
                    DailyCacheStatus nextStatus = nextBundle.dailyCacheStatus();
                    if (nextStatus == null
                            || !Objects.equals(cycleStart, nextStatus.cycleStartedAt())
                            || !dailyStatusProgressed(prevStatus, nextStatus)) {
                        break;
                    }
                    prevStatus = nextStatus;
                    batchStep++;
                }
                DailyCacheStatus finalStatus = bundle.dailyCacheStatus();
                boolean degraded = finalStatus != null && finalStatus.lastAttemptDegraded();
                boolean cached = bundle.hours24() != null && bundle.hours24().fromCache();
                String result = cached && degraded ? "FAILED" : bundle.hasMoreHistoryForTarget() || degraded ? "PARTIAL"
                        : cached ? "CACHED" : "SUCCEEDED";
                String error = cached && degraded ? "上游刷新失败，已保留上次入库的数据" : "";
                backgroundRefreshStatuses.put(key, new BackgroundRefreshStatus(result, startedAt, Instant.now(),
                        error, refreshId, finalStatus == null ? null : finalStatus.cycleStartedAt()));
                log.info("workspace_refresh refresh_id={} stage=complete elapsed_ms={} result={}", refreshId,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedNanos), result);
            } catch (RuntimeException ex) {
                log.warn("workspace_refresh refresh_id={} stage=complete elapsed_ms={} result=failed reason={} status={}",
                        refreshId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedNanos),
                        ex.getClass().getSimpleName(), ex instanceof XDataProviderException failure ? failure.getStatusCode() : 0);
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                if (message.length() > 300) message = message.substring(0, 300);
                backgroundRefreshStatuses.put(key,
                        new BackgroundRefreshStatus("FAILED", startedAt, Instant.now(), message, refreshId));
            } finally {
                backgroundRefreshes.remove(key, gate);
            }
        });
    }

    public BackgroundRefreshStatus requestWorkspaceRefresh(String rawHandle, ReportPeriod period,
            String provider, String clientIp, String accountId) {
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);
        String key = resolveProviderMode(provider).name() + ":" + accountOwnerKey(handleKey, accountId);
        scheduleWorkspaceRefresh(key, handle, period != null ? period : ReportPeriod.HOURS_24,
                provider, clientIp, accountId);
        return backgroundRefreshStatuses.getOrDefault(key,
                new BackgroundRefreshStatus("FAILED", Instant.now(), Instant.now(), "后台刷新未能受理"));
    }

    public BackgroundRefreshStatus workspaceRefreshStatus(String rawHandle, String provider, String accountId) {
        String handleKey = validateIsolatedHandleKey(HandleNormalizer.normalize(rawHandle));
        String key = resolveProviderMode(provider).name() + ":" + accountOwnerKey(handleKey, accountId);
        return backgroundRefreshStatuses.getOrDefault(key,
                new BackgroundRefreshStatus("IDLE", null, null, ""));
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmOwnerSnapshotCacheOnReady() {
        if (resolvePreferredModeQuietly() != ProviderMode.TWITTERAPI_IO) return;
        silentHistoryExecutor.submit(() -> {
            try {
                AccountStore.StoredAccount raw = findAuthorStoredAccount();
                if (raw == null || raw.profile() == null || raw.syncedAtInstant() == null) return;
                Instant now = Instant.now();
                AccountStore.StoredAccount stored = AccountStore.pruneToRollingWindow(raw, now, retainsOwnerHistory(raw.profile()));
                String handleKey = validateIsolatedHandleKey(stored.profile().username());
                String accountId = stored.profile().id();
                String ownerKey = accountOwnerKey(handleKey, accountId);
                String cacheKey = ProviderMode.TWITTERAPI_IO.name() + ":" + ownerKey;
                if (snapshotCache.containsKey(cacheKey)) return;
                boolean completedDaily = isDailyRefreshComplete(stored);
                RawAccountSnapshot persisted = TwitterApiIoProvider.snapshotOf(stored, "", !completedDaily);
                ZoneId zone = resolveZoneId();
                String today = LocalDate.now(zone).toString();
                UserUsageEntry quotaEntry = usageWithProvenCompletion(readUsageSnapshot(ownerKey), stored, zone);
                Integer rank = resolveAuthorInteractionRank(handleKey, accountId);
                AccessPolicy policy = buildAccessPolicy(handleKey, persisted, quotaEntry, zone, today, rank);
                long ttl = DAILY_REFRESH_SECONDS;
                Instant expires = dailyRefreshExpiresAt(stored, quotaEntry, now);
                MultiPeriodDashboardBundle warm = buildWorkspaceBundle(
                        persisted, policy, true, ttl, expires, !completedDaily,
                        TwitterApiIoProvider.pendingRelationshipChecks(stored))
                        .withDailyCacheStatus(dailyCacheStatus(stored, quotaEntry, false));
                putSnapshotCache(cacheKey, persisted, warm, ttl, expires, currentAlgoFingerprint());
                log.info("Pre-warmed stored workspace bundle for @{} ({} tweets, {} inbound)",
                        handleKey, stored.tweets().size(), stored.inbound().size());
            } catch (Exception ex) {
                log.debug("Skipped startup owner snapshot cache warm-up: {}", ex.getMessage());
            }
        });
    }

    public long latestCachedOwnerPostEpochMs(String accountId, String username) {
        long latest = 0L;
        if (accountId == null || accountId.isBlank()) return 0L;
        for (CachedSnapshot entry : snapshotCache.values()) {
            RawAccountSnapshot snap = entry == null ? null : entry.snapshot();
            if (snap == null || snap.profile() == null || snap.timeline() == null) continue;
            if (!accountId.equals(snap.profile().id())) continue;
            for (RawTweet t : snap.timeline()) {
                if (t == null || t.createdAt() == null || t.selfThreadReply()) continue;
                if (t.kind() == com.xops.hub.model.RawSnapshotModels.TweetKind.POST
                        || t.kind() == com.xops.hub.model.RawSnapshotModels.TweetKind.QUOTE) {
                    latest = Math.max(latest, t.createdAt().toEpochMilli());
                }
            }
        }
        return latest;
    }

    @PreDestroy
    public void stopBackgroundRefreshes() {
        top50Workers.shutdownNow();
        backgroundRefreshExecutor.shutdownNow();
        silentHistoryExecutor.shutdownNow();
    }

    public boolean hasActiveForegroundWork() {
        return activeForegroundSyncs.get() > 0
                || backgroundRefreshes.values().stream().anyMatch(AtomicBoolean::get);
    }

    private boolean canUsePublicOrPaidProvider() {
        return properties.hasTwitterApiIoKey()
                || (twitterApiIoProvider != null && twitterApiIoProvider.canReadTimelineForFree(""));
    }

    public boolean isSilentHistoryEnabled() {
        return properties.isTop10SilentHistoryEnabled()
                && resolvePreferredModeQuietly() == ProviderMode.TWITTERAPI_IO
                && canUsePublicOrPaidProvider();
    }

    private static List<long[]> officialCoverage(Instant oldest, boolean exhausted, Instant cutoff, Instant anchor) {
        if (exhausted) return List.of(new long[]{cutoff.getEpochSecond(), anchor.getEpochSecond()});
        if (oldest == null || oldest.isAfter(anchor)) return List.of();
        long from = Math.max(oldest.getEpochSecond(), cutoff.getEpochSecond());
        return from < anchor.getEpochSecond() ? List.of(new long[]{from, anchor.getEpochSecond()}) : List.of();
    }

    private static boolean streamCoversDaily(List<long[]> coverage, long anchor) {
        return anchor > 0 && AccountStore.missingRanges(coverage, anchor - 86_400L, anchor).isEmpty();
    }

    private int pendingDailyMetrics(AccountStore.StoredAccount stored) {
        if (stored == null) return 0;
        long anchor = stored.dailyRefreshStartedAt() > 0 ? stored.dailyRefreshStartedAt() : stored.syncedAt();
        boolean owner = retainsOwnerHistory(stored.profile());
        return (int) stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor)
                .filter(TwitterApiIoProvider::isDailyMetricTarget)
                .filter(t -> !TwitterApiIoProvider.dailyMetricsFresh(t, anchor, owner)).count();
    }

    private int unattemptedDailyMetrics(AccountStore.StoredAccount stored) {
        if (stored == null) return 0;
        long anchor = stored.dailyRefreshStartedAt() > 0 ? stored.dailyRefreshStartedAt() : stored.syncedAt();
        boolean owner = retainsOwnerHistory(stored.profile());
        return (int) stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor)
                .filter(TwitterApiIoProvider::isDailyMetricTarget)
                .filter(t -> TwitterApiIoProvider.dailyMetricsUnattempted(t, anchor, owner)).count();
    }

    private boolean dailyCanContinue(AccountStore.StoredAccount stored) {
        if (stored == null || stored.dailyRefreshStartedAt() <= 0) return false;
        long anchor = stored.dailyRefreshStartedAt();
        boolean needTimeline = !streamCoversDaily(stored.tweetCoverage(), anchor);
        boolean needInbound = !streamCoversDaily(stored.inboundCoverage(), anchor);
        boolean needMetrics = unattemptedDailyMetrics(stored) > 0;
        if (!needTimeline && !needInbound && !needMetrics) return false;
        return !reportContinuationBlocked(stored, needTimeline, needInbound, needMetrics);
    }

    private boolean reportContinuationBlocked(AccountStore.StoredAccount stored, boolean timeline, boolean inbound, boolean metrics) {
        if (twitterApiIoProvider == null) return false;
        XUserSummary profile = stored.profile();
        if (profile == null) return true;
        return PaidCapabilityGate.withCaller(profile.username(), profile.id(), isAuthorIdentity(profile.username(), profile.id()),
                () -> twitterApiIoProvider.isDailyContinuationBlocked(timeline, inbound, metrics));
    }

    private static boolean dailyStatusProgressed(DailyCacheStatus prev, DailyCacheStatus next) {
        if (prev == null || next == null) return false;
        if (next.unattemptedMetricPosts() < prev.unattemptedMetricPosts()) return true;
        if (!prev.timelineCovered() && next.timelineCovered()) return true;
        if (!prev.inboundCovered() && next.inboundCovered()) return true;
        if (next.recentPosts() > prev.recentPosts() || next.recentInbound() > prev.recentInbound()) return true;
        if (next.timelineCoveredFrom() != null
                && (prev.timelineCoveredFrom() == null || next.timelineCoveredFrom().isBefore(prev.timelineCoveredFrom()))) {
            return true;
        }
        return next.inboundCoveredFrom() != null
                && (prev.inboundCoveredFrom() == null || next.inboundCoveredFrom().isBefore(prev.inboundCoveredFrom()));
    }

    private boolean isDailyRefreshComplete(AccountStore.StoredAccount stored) {
        return stored != null && stored.dailyRefreshedAt() > 0
                && stored.dailyRefreshStartedAt() == stored.dailyRefreshedAt()
                && stored.syncedAt() == stored.dailyRefreshedAt()
                && streamCoversDaily(stored.tweetCoverage(), stored.dailyRefreshedAt())
                && streamCoversDaily(stored.inboundCoverage(), stored.dailyRefreshedAt())
                && pendingDailyMetrics(stored) == 0;
    }

    private static boolean canContinueDailyRefresh(AccountStore.StoredAccount stored, Instant now) {
        return stored != null && stored.dailyRefreshStartedAt() > stored.dailyRefreshedAt()
                && stored.syncedAt() == stored.dailyRefreshStartedAt()
                && now.getEpochSecond() >= stored.dailyRefreshStartedAt()
                && now.getEpochSecond() < stored.dailyRefreshStartedAt() + DAILY_REFRESH_SECONDS;
    }

    private static Instant lastReportCompletion(AccountStore.StoredAccount stored, UserUsageEntry usage) {
        if (stored == null || stored.dailyRefreshedAt() <= 0) return null;
        long cycle = stored.dailyRefreshedAt();
        if (stored.reportCompletedAt() >= cycle) return Instant.ofEpochSecond(stored.reportCompletedAt());
        if (usage != null && usage.completedReportCycle() == cycle && usage.lastReportSyncAt() != null
                && usage.lastReportSyncAt().getEpochSecond() >= cycle) {
            return usage.lastReportSyncAt();
        }
        // Earlier stores recorded only the coverage anchor of the completed cycle.
        return Instant.ofEpochSecond(cycle);
    }

    private static boolean completedWithinHour(AccountStore.StoredAccount stored, UserUsageEntry usage, Instant now) {
        Instant completed = lastReportCompletion(stored, usage);
        return completed != null && !now.isBefore(completed) && now.isBefore(completed.plusSeconds(DAILY_REFRESH_SECONDS));
    }

    private static UserUsageEntry usageWithProvenCompletion(UserUsageEntry usage, AccountStore.StoredAccount store, ZoneId zone) {
        if (store == null || store.dailyRefreshedAt() <= 0 || store.reportCompletedAt() < store.dailyRefreshedAt()) return usage;
        Instant finished = Instant.ofEpochSecond(store.reportCompletedAt());
        String day = finished.atZone(zone).toLocalDate().toString();
        if (finished.isAfter(Instant.now()) || usage.syncedDates().contains(day)) return usage;
        List<String> dates = new ArrayList<>(usage.syncedDates());
        dates.add(day);
        return new UserUsageEntry(usage.usageCount(), usage.lastModalShownAtCount(), usage.optimisticFollowedAt(),
                usage.updatedAt(), dates, usage.completedReportCycle(), usage.lastReportSyncAt());
    }

    private boolean completedAutoSyncDay(AccountStore.StoredAccount stored, UserUsageEntry usage, ZoneId zone, Instant now) {
        if (!isDailyRefreshComplete(stored)) return false;
        Instant completed = lastReportCompletion(stored, usage);
        var today = now.atZone(zone).toLocalDate();
        return completed != null && !completed.isAfter(now) && today.equals(completed.atZone(zone).toLocalDate())
                && (stored.reportCompletedAt() >= stored.dailyRefreshedAt()
                    || (usage.completedReportCycle() == 0 || usage.completedReportCycle() == stored.dailyRefreshedAt())
                        && usage.syncedDates().contains(today.toString()));
    }

    private Instant dailyRefreshExpiresAt(AccountStore.StoredAccount stored, UserUsageEntry usage, Instant now) {
        if (canContinueDailyRefresh(stored, now) && !dailyCanContinue(stored)) {
            return Instant.ofEpochSecond(stored.dailyRefreshStartedAt() + DAILY_REFRESH_SECONDS);
        }
        return isDailyRefreshComplete(stored)
                ? lastReportCompletion(stored, usage).plusSeconds(DAILY_REFRESH_SECONDS)
                : (stored != null && stored.syncedAtInstant() != null ? stored.syncedAtInstant() : now);
    }

    private DailyCacheStatus dailyCacheStatus(AccountStore.StoredAccount stored, UserUsageEntry usage, boolean lastAttemptDegraded) {
        long anchor = Math.max(stored.dailyRefreshStartedAt(), stored.syncedAt());
        int posts = (int) stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor).count();
        int inbound = (int) stored.inbound().stream()
                .filter(i -> i.createdAt() >= anchor - 86_400L && i.createdAt() <= anchor).count();
        long latestMetricFetchedAt = stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor)
                .filter(t -> t.metricsTimeObserved() && t.metricsFetchedAt() > 0 && t.unavailableMetrics().isEmpty())
                .mapToLong(AccountStore.StoredTweet::metricsFetchedAt).max().orElse(0);
        Instant completed = lastReportCompletion(stored, usage);
        boolean canContinue = dailyCanContinue(stored);
        Instant nextCheck = completed == null ? null : completed.plusSeconds(DAILY_REFRESH_SECONDS);
        if (!canContinue && canContinueDailyRefresh(stored, Instant.now())) {
            nextCheck = Instant.ofEpochSecond(stored.dailyRefreshStartedAt() + DAILY_REFRESH_SECONDS);
        }
        return new DailyCacheStatus(stored.syncedAtInstant(),
                stored.dailyRefreshStartedAt() > 0 ? Instant.ofEpochSecond(stored.dailyRefreshStartedAt()) : null,
                completed,
                streamCoversDaily(stored.tweetCoverage(), anchor), streamCoversDaily(stored.inboundCoverage(), anchor),
                posts, inbound, pendingDailyMetrics(stored), unattemptedDailyMetrics(stored),
                canContinue, lastAttemptDegraded,
                anchor > 0 ? Instant.ofEpochSecond(AccountStore.contiguousFrom(stored.tweetCoverage(), anchor)) : null,
                anchor > 0 ? Instant.ofEpochSecond(AccountStore.contiguousFrom(stored.inboundCoverage(), anchor)) : null,
                latestMetricFetchedAt > 0 ? Instant.ofEpochSecond(latestMetricFetchedAt) : null, nextCheck);
    }

    private String buildDailyRefreshNotice(AccountStore.StoredAccount stored, boolean complete) {
        return (complete ? "最近 24 小时数据与指标已完成本轮同步；满 1 小时后再次检查更新。"
                : (dailyCanContinue(stored) ? "本轮最近 24 小时数据或指标尚未补齐；继续补充可获取数据，未计为完整新鲜缓存。"
                    : "本轮可请求的数据已尝试完毕，仍有未确认项；保留原值和缺失状态，下一小时再检查，不重复请求本轮失败项。"))
                + "已入库 " + stored.tweets().size() + " 条动态、" + stored.inbound().size() + " 条互动；历史数据仍按原覆盖范围累计。";
    }

    /** 显式查看本人已入库快照；不访问上游、不写盘，也不改变默认刷新缓存。 */
    public MultiPeriodDashboardBundle analyzeStoredAccountBundle(String rawHandle, String expectedAccountId) {
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);
        if (expectedAccountId == null || expectedAccountId.isBlank()) {
            throw new XDataProviderException(401, "请先使用 X 账号授权登录后查看本人已入库报表");
        }
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        AccountStore.StoredAccount rawStored = loadStore(handleKey, expectedAccountId);
        if (rawStored == null || rawStored.syncedAt() <= 0) {
            throw new XDataProviderException(404, "当前登录账号暂无已入库快照，请先使用正常刷新获取数据");
        }
        verifyAccountOwner(rawStored.profile(), handleKey, expectedAccountId);
        AccountStore.StoredAccount stored = AccountStore.pruneToRollingWindow(rawStored, Instant.now(),
                retainsOwnerHistory(rawStored.profile()));
        RawAccountSnapshot snapshot = TwitterApiIoProvider.snapshotOf(stored,
                "正在查看已入库快照；本次未同步上游，时间窗以原数据同步时间为准。", stored.graphRefreshFailed());
        long ttl = Math.max(0L, properties.getCacheTtlSeconds());
        Instant expiresAt = stored.syncedAtInstant().plusSeconds(ttl);
        ZoneId zone = resolveZoneId();
        String today = LocalDate.now(zone).toString();
        UserUsageEntry usage = usageWithProvenCompletion(readUsageSnapshot(ownerKey), stored, zone);
        Integer rank = resolveAuthorInteractionRank(handleKey, expectedAccountId);
        AccessPolicy policy = buildAccessPolicy(handleKey, snapshot, usage, zone, today, rank);
        return buildWorkspaceBundle(snapshot, policy, true, ttl, expiresAt, false,
                TwitterApiIoProvider.pendingRelationshipChecks(stored)).withDailyCacheStatus(dailyCacheStatus(stored, usage, false));
    }

    private ResolvedBundleHolder obtainBundle(
            String rawHandle,
            int requiredDays,
            String requestedProvider,
            String clientIp,
            String expectedAccountId,
            boolean continueRelations
    ) {
        return OwnerArchiveAccess.duringRefresh(properties.getDiskCacheDir(), expectedAccountId,
                () -> loadPeriodBundle(rawHandle, requiredDays, requestedProvider, clientIp, expectedAccountId, continueRelations));
    }

    private ResolvedBundleHolder loadPeriodBundle(String rawHandle, int requiredDays, String requestedProvider,
            String clientIp, String expectedAccountId, boolean continueRelations) {
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);

        ProviderMode resolvedMode = resolveProviderMode(requestedProvider);
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        String cacheKey = resolvedMode.name() + ":" + ownerKey;
        long configuredTtl = Math.max(0L, properties.getCacheTtlSeconds());

        String algoFp = currentAlgoFingerprint();
        if (!continueRelations && configuredTtl > 0) {
            CachedSnapshot memHit = snapshotCache.get(cacheKey);
            if (isValidAccountCacheHit(memHit, handleKey, expectedAccountId, Instant.now())
                    && Objects.equals(algoFp, memHit.algoFingerprint())
                    && (resolvedMode != ProviderMode.TWITTERAPI_IO || memHit.snapshot().coveredWindowDays() >= requiredDays)) {
                return new ResolvedBundleHolder(memHit.bundle(), true, memHit.ttlSeconds(), memHit.expiresAt());
            }
        }

        Object lock = fetchLockFor(ownerKey);
        synchronized (lock) {
            Instant now = Instant.now();
            CachedSnapshot active = snapshotCache.get(cacheKey);
            boolean cacheValid = configuredTtl > 0 && isValidAccountCacheHit(active, handleKey, expectedAccountId, now);
            if (continueRelations) {
                if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                    throw new XDataProviderException(400, "当前数据源不支持逐人继续核验");
                }
                boolean isOwner = isAuthorIdentity(handleKey, expectedAccountId);
                if (!isOwner && (!twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null)) {
                    if (paidCapabilityGate != null) {
                        paidCapabilityGate.scheduleIdempotentRetry(
                                "continue-relations:" + handleKey,
                                "twitterapi_io_relation",
                                handleKey,
                                expectedAccountId,
                                com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE,
                                () -> {}
                        );
                    }
                    throw new XDataProviderException(403, com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE);
                }
                checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
                checkContinueRelationsRateLimit(handleKey, expectedAccountId);
                AccountStore.StoredAccount stored = loadStore(handleKey, expectedAccountId);
                if (stored != null) verifyAccountOwner(stored.profile(), handleKey, expectedAccountId);
                TwitterApiIoProvider.SyncOutcome continued;
                activeForegroundSyncs.incrementAndGet();
                try {
                    continued = isOwner
                            ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                    handleKey, expectedAccountId, true,
                                    () -> twitterApiIoProvider.continueRelationships(handle, stored, expectedAccountId))
                            : twitterApiIoProvider.continueRelationships(handle, stored, expectedAccountId);
                } finally {
                    activeForegroundSyncs.decrementAndGet();
                }
                String obtainToday = LocalDate.now(resolveZoneId()).toString();
                if (isOwner && continued.degraded() && paidCapabilityGate != null) {
                    paidCapabilityGate.scheduleIdempotentRetry(
                            "continue-relations:elidlicom:" + obtainToday,
                            "twitterapi_io_relation",
                            com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                            expectedAccountId,
                            "逐人关系核验存在未完成项",
                            () -> analyzeWorkspaceBundle(handle, ReportPeriod.HOURS_24, requestedProvider, false, clientIp, expectedAccountId, true), false, true
                    );
                } else if (isOwner && paidCapabilityGate != null) {
                    paidCapabilityGate.markSucceeded("continue-relations:elidlicom:" + obtainToday);
                }
                verifyAccountOwner(continued.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, continued.store());
                RawAccountSnapshot snapshot = TwitterApiIoProvider.snapshotOf(continued.store(),
                        "本次仅继续逐人关系核验；未成功确认的对象仍保留未知。", continued.degraded());
                Instant fallbackSynced = continued.store().syncedAtInstant() != null
                        ? continued.store().syncedAtInstant()
                        : (stored != null && stored.syncedAtInstant() != null ? stored.syncedAtInstant() : Instant.now());
                Instant expires = active != null ? active.expiresAt()
                        : fallbackSynced.plusSeconds(configuredTtl);
                MultiPeriodDashboardBundle bundle = computeBundleOnce(snapshot, false, configuredTtl, expires, false,
                        TwitterApiIoProvider.pendingRelationshipChecks(continued.store()));
                if (configuredTtl > 0) putSnapshotCache(cacheKey, snapshot, bundle, configuredTtl, expires, algoFp);
                return new ResolvedBundleHolder(bundle, false, configuredTtl, expires);
            }
            if (cacheValid && (resolvedMode != ProviderMode.TWITTERAPI_IO || active.snapshot().coveredWindowDays() >= requiredDays)) {
                MultiPeriodDashboardBundle cachedBundle = active.bundle();
                if (!Objects.equals(algoFp, active.algoFingerprint())) {
                    int pendingChecks = cachedBundle != null ? cachedBundle.pendingRelationshipChecks() : 0;
                    boolean hasMore = cachedBundle != null && cachedBundle.hasMoreHistoryForTarget();
                    cachedBundle = computeBundleOnce(
                            active.snapshot(), true, active.ttlSeconds(), active.expiresAt(), hasMore, pendingChecks);
                    putSnapshotCache(cacheKey, active.snapshot(), cachedBundle,
                            active.ttlSeconds(), active.expiresAt(), algoFp);
                }
                return new ResolvedBundleHolder(cachedBundle, true, active.ttlSeconds(), active.expiresAt());
            }

            if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                // 演示 / 官方 API 模式：不做持久化，只做 5 分钟服务端内存缓存
                RawAccountSnapshot snap = switch (resolvedMode) {
                    case X_OFFICIAL_V2 -> {
                        boolean officialOwner = isAuthorIdentity(handleKey, expectedAccountId);
                        if (!officialOwner && paidCapabilityGate != null) {
                            throw new XDataProviderException(403, com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE);
                        }
                        checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
                        yield officialOwner
                                ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                        handleKey, expectedAccountId, true,
                                        () -> officialXApiV2Provider.fetchSnapshot(handle, null, expectedAccountId))
                                : officialXApiV2Provider.fetchSnapshot(handle, null, expectedAccountId);
                    }
                    case DEMO -> demoSnapshotForAccount(handle, expectedAccountId);
                    default -> throw new XDataProviderException(500, "不支持的数据源模式");
                };
                verifyAccountOwner(snap.profile(), handleKey, expectedAccountId);
                Instant expiresAt = now.plusSeconds(configuredTtl);
                harvestAuthorProfileFromSnapshot(snap);
                MultiPeriodDashboardBundle bundle = computeBundleOnce(snap, false, configuredTtl, expiresAt, false);
                if (configuredTtl > 0) {
                    pruneCacheIfNeeded();
                    putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expiresAt, algoFp);
                }
                return new ResolvedBundleHolder(bundle, false, configuredTtl, expiresAt);
            }

            // TwitterAPI.io：以每账号独立隔离的持久化存储为准，只请求尚未入库的时间段
            checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
            AccountStore.StoredAccount stored = loadStore(handleKey, expectedAccountId);
            boolean headSync = !cacheValid;
            boolean authorCaller = isAuthorIdentity(handleKey, expectedAccountId);
            boolean bindPaidScope = authorCaller || !twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null;
            String callerName = authorCaller ? com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME : handleKey;
            TwitterApiIoProvider.SyncOutcome outcome;
            try {
                java.util.function.Supplier<TwitterApiIoProvider.SyncOutcome> syncCall = () -> expectedAccountId == null
                        ? twitterApiIoProvider.syncStore(handle, stored, requiredDays, headSync, null)
                        : twitterApiIoProvider.syncStore(handle, stored, requiredDays, headSync, null, expectedAccountId);
                outcome = bindPaidScope
                        ? com.xops.hub.provider.PaidCapabilityGate.withCaller(handleKey, expectedAccountId, authorCaller, syncCall)
                        : syncCall.get();
                verifyAccountOwner(outcome.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, outcome.store());
                if (paidCapabilityGate != null && (authorCaller || properties.isFxtwitterEnabled())) {
                    String bundleKey = (authorCaller ? "bundle-sync:elidlicom:" : "bundle-free-sync:" + handleKey + ":")
                            + LocalDate.now(resolveZoneId());
                    if (outcome.degraded()) {
                        paidCapabilityGate.scheduleIdempotentRetry(
                                bundleKey,
                                authorCaller ? "twitterapi_io_read" : "fxtwitter_free_read",
                                callerName,
                                expectedAccountId,
                                "快照同步存在未完成项",
                                () -> obtainBundle(handle, requiredDays, requestedProvider, clientIp, expectedAccountId, false), false, authorCaller
                        );
                    } else {
                        paidCapabilityGate.markSucceeded(bundleKey);
                    }
                }
            } catch (XDataProviderException ex) {
                if (paidCapabilityGate != null
                        && (authorCaller || (properties.isFxtwitterEnabled() && ex.getStatusCode() != 401 && ex.getStatusCode() != 403 && ex.getStatusCode() != 404))) {
                    String bundleKey = (authorCaller ? "bundle-sync:elidlicom:" : "bundle-free-sync:" + handleKey + ":")
                            + LocalDate.now(resolveZoneId());
                    paidCapabilityGate.scheduleIdempotentRetry(
                            bundleKey,
                            authorCaller ? "twitterapi_io_read" : "fxtwitter_free_read",
                            callerName,
                            expectedAccountId,
                            ex.getMessage(),
                            () -> obtainBundle(handle, requiredDays, requestedProvider, clientIp, expectedAccountId, false), false, authorCaller
                    );
                }
                if (stored != null && stored.profile() != null
                        && (ex.getStatusCode() == 503 || ex.getStatusCode() == 502 || ex.getStatusCode() == 504
                        || ex.getStatusCode() == 500 || ex.getStatusCode() == 429)) {
                    log.warn("Peak queue/rate guard triggered for @{}; serving persisted store immediately: {}", handle, ex.getMessage());
                    RawAccountSnapshot fallbackSnap = TwitterApiIoProvider.snapshotOf(
                            AccountStore.withoutRelationshipEvidence(stored), "", true);
                    fallbackSnap = withNotice(fallbackSnap,
                            "当前访问高峰排队较多，已直接秒开本地已入库数据（" + stored.tweets().size() + " 条动态 / "
                                    + stored.inbound().size() + " 条互动），稍后将自动增量同步最新部分。");
                    long shortTtl = Math.min(30L, configuredTtl > 0 ? configuredTtl : 30L);
                    Instant shortExpires = now.plusSeconds(shortTtl);
                    MultiPeriodDashboardBundle fallbackBundle = computeBundleOnce(fallbackSnap, false, shortTtl, shortExpires, false);
                    if (configuredTtl > 0) {
                        pruneCacheIfNeeded();
                        putSnapshotCache(cacheKey, fallbackSnap, fallbackBundle, shortTtl, shortExpires, algoFp);
                    }
                    return new ResolvedBundleHolder(fallbackBundle, false, shortTtl, shortExpires);
                }
                throw ex;
            }

            RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(outcome.store(), "", outcome.degraded());
            snap = withNotice(snap, buildStoreNotice(outcome, snap));
            Instant expiresAt = headSync ? now.plusSeconds(configuredTtl) : active.expiresAt();
            boolean hasMore = snap.coveredWindowDays() < requiredDays && outcome.progressed();
            harvestAuthorProfileFromSnapshot(snap);
            MultiPeriodDashboardBundle bundle = computeBundleOnce(snap, false, configuredTtl, expiresAt, hasMore,
                    TwitterApiIoProvider.pendingRelationshipChecks(outcome.store()));
            if (configuredTtl > 0) {
                pruneCacheIfNeeded();
                putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expiresAt, algoFp);
            }
            return new ResolvedBundleHolder(bundle, false, configuredTtl, expiresAt);
        }
    }

    private RawAccountSnapshot demoSnapshotForAccount(String handle, String expectedAccountId) {
        RawAccountSnapshot s = demoProvider.generateSnapshot(handle, null);
        if (expectedAccountId == null) return s;
        XUserSummary p = s.profile();
        XUserSummary owner = new XUserSummary(expectedAccountId, p.username(), p.displayName(), p.avatarUrl(),
                p.bio(), p.verified(), p.followersCount(), p.followingCount(), p.totalPostsCount(), p.joinedDate(),
                p.bannerUrl());
        // 仅在明确选择 DEMO 时绑定会话身份；数据仍标为演示，真实数据源始终核验上游 ID。
        return new RawAccountSnapshot(ProviderMode.DEMO, false, s.bannerNotice(), s.fetchedAt(), owner,
                s.timeline(), s.inboundInteractions(), s.relationships(), s.followersTruncated(), s.degradedPartial(),
                s.coveredWindowDays(), s.timelineTailTime(), s.timelineNextCursor(), s.timelineExhausted(),
                s.mentionsTailTime(), s.mentionsNextCursor(), s.mentionsExhausted(), s.followingsTruncated());
    }

    private String buildStoreNotice(TwitterApiIoProvider.SyncOutcome outcome, RawAccountSnapshot snap) {
        AccountStore.StoredAccount st = outcome.store();
        int days = snap.coveredWindowDays();
        String scope = days >= 30 ? "已入库完整 30 天" : (days >= 7 ? "已入库近 7 天" : (days >= 2 ? "已入库近 48 小时" : "近 48 小时数据仍在补齐"));
        StringBuilder sb = new StringBuilder(String.format(
                "实时数据（%s）：%d 条动态、%d 条互动、%d 位关注、%d 位粉丝；本次补齐未入库历史（%d 页）。",
                scope, st.tweets().size(), st.inbound().size(), st.followings().size(), st.followers().size(), outcome.apiPages()));
        if (outcome.graphReused()) {
            sb.append("关注数与粉丝数未变，沿用已入库的关注关系。");
        }
        if (outcome.degraded()) {
            sb.append("部分请求暂时失败，下次会从断点继续补齐。");
        }
        if (st.followersTruncated() && st.profile() != null) {
            sb.append(String.format("主页粉丝数 %d，已采集 %d 位，名单完整性存疑，缺席对象的回关状态待确认。",
                    st.profile().followersCount(), st.followers().size()));
        }
        if (st.followingsTruncated()) sb.append("关注名单不完整，未出现的账号不能认定为未关注。");
        sb.append("互动指标为各推文最近采集快照；每次热更新最多额外刷新50条历史推文，其余历史指标可能尚未更新。");
        return sb.toString();
    }

    private static RawAccountSnapshot withNotice(RawAccountSnapshot s, String notice) {
        return new RawAccountSnapshot(
                s.resolvedProvider(), s.liveData(), notice, s.fetchedAt(), s.profile(), s.timeline(), s.inboundInteractions(),
                s.relationships(), s.followersTruncated(), s.degradedPartial(), s.coveredWindowDays(),
                s.timelineTailTime(), s.timelineNextCursor(), s.timelineExhausted(),
                s.mentionsTailTime(), s.mentionsNextCursor(), s.mentionsExhausted(), s.followingsTruncated(),
                s.followingsRequestFailed(), s.followersRequestFailed()
        );
    }

    private static String validateIsolatedHandleKey(String handle) {
        String key = (handle == null ? "" : handle.trim()).toLowerCase(Locale.ROOT);
        if (!SAFE_HANDLE_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("非法的 X 账号标识，已拒绝访问");
        }
        return key;
    }

    private static boolean isValidAccountCacheHit(CachedSnapshot hit, String expectedHandleKey, String expectedAccountId, Instant now) {
        return cacheIdentityMatches(hit, expectedHandleKey, expectedAccountId)
                && now.isBefore(hit.expiresAt());
    }

    private static boolean cacheIdentityMatches(CachedSnapshot hit, String expectedHandleKey, String expectedAccountId) {
        return hit != null
                && hit.snapshot() != null
                && hit.snapshot().profile() != null
                && (expectedAccountId == null ? expectedHandleKey.equalsIgnoreCase(hit.snapshot().profile().username())
                        : expectedAccountId.equals(hit.snapshot().profile().id()));
    }

    private static String accountOwnerKey(String handleKey, String accountId) {
        if (accountId == null) return handleKey;
        if (!accountId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new XDataProviderException(401, "登录账号 ID 无效，请重新授权登录");
        }
        return "id:" + accountId;
    }

    private static void verifyAccountOwner(XUserSummary profile, String handleKey, String accountId) {
        if (profile == null || (accountId != null && !accountId.equals(profile.id()))) {
            throw new XDataProviderException(409, "账号 ID 校验未通过，用户名可能已被其他账号使用，请重新授权登录");
        }
        if (accountId == null && !handleKey.equalsIgnoreCase(profile.username())) {
            throw new XDataProviderException(409, "账号用户名已变更，请重新登录确认账号，并联系管理员迁移旧用户名数据后重试");
        }
    }

    private Path accountStoreFile(String handleKey, String accountId) {
        if (accountId == null) return resolveUserStoreFile(handleKey);
        accountOwnerKey(handleKey, accountId);
        String dir = properties.getDiskCacheDir();
        return dir == null || dir.isBlank() ? null
                : Path.of(dir).resolve("users").resolve("by-id").resolve(accountId)
                        .resolve("store_v" + AccountStore.VERSION + ".json.gz");
    }

    private AccountStore.StoredAccount loadStore(String handleKey, String accountId) {
        return AccountStore.verifiedCoverageBase(readAccountSnapshot(handleKey, accountId));
    }

    private AccountStore.StoredAccount readAccountSnapshot(String handleKey, String accountId) {
        if (accountId == null) return loadStore(handleKey);
        String ownerKey = accountOwnerKey(handleKey, accountId);
        Path file = accountStoreFile(handleKey, accountId);
        AccountStore.StoredAccount stored = file == null ? memoryStores.get(ownerKey) : AccountStore.read(file, objectMapper);
        if (stored != null) {
            verifyAccountOwner(stored.profile(), handleKey, accountId);
            return stored;
        }
        if (file == null) {
            return memoryStores.values().stream().filter(s -> belongsToId(s, accountId))
                    .max(Comparator.comparingLong(AccountStore.StoredAccount::syncedAt)).orElse(null);
        }
        Path root = Path.of(properties.getDiskCacheDir());
        for (Path path : List.of(resolveUserStoreFile(handleKey), legacyFlatStoreFile(handleKey),
                root.resolve("twitterapi_io_v2_" + handleKey + ".json"))) {
            var match = readLegacyAccount(path);
            if (belongsToId(match, accountId)) return match;
        }
        // A login may carry a new handle while the only restored copy still has its old filename.
        return legacySnapshotFiles(root).stream().map(this::readLegacyAccount)
                .filter(s -> belongsToId(s, accountId))
                .max(Comparator.comparingLong(AccountStore.StoredAccount::syncedAt)).orElse(null);
    }

    private static boolean belongsToId(AccountStore.StoredAccount store, String accountId) {
        return store != null && store.profile() != null && accountId.equals(store.profile().id());
    }

    private List<Path> legacySnapshotFiles(Path root) {
        try {
            return AccountSnapshotFiles.legacyFiles(root);
        } catch (java.io.IOException ex) {
            log.warn("Could not enumerate legacy account snapshots: {}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private AccountStore.StoredAccount readLegacyAccount(Path path) {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        return path.getFileName().toString().endsWith(".json") ? readLegacySnapshot(path) : AccountStore.read(path, objectMapper);
    }

    private Path userIsolatedDir(String handleKey) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        return Path.of(dir).resolve("users").resolve(safeKey);
    }

    public Path resolveUserStoreFile(String handleKey) {
        Path uDir = userIsolatedDir(handleKey);
        return uDir != null ? uDir.resolve("store_v" + AccountStore.VERSION + ".json.gz") : null;
    }

    private Path legacyFlatStoreFile(String handleKey) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        return Path.of(dir).resolve("store_v" + AccountStore.VERSION + "_" + safeKey + ".json.gz");
    }

    private AccountStore.StoredAccount loadStore(String handleKey) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        Path file = resolveUserStoreFile(safeKey);
        AccountStore.StoredAccount s = file != null ? AccountStore.read(file, objectMapper) : null;
        if (s == null && file != null) {
            // 平滑迁移旧版扁平目录文件（迁移后删除旧文件，统一归入 users/<handle>/ 专属目录）
            Path legacyFlat = legacyFlatStoreFile(safeKey);
            if (legacyFlat != null && Files.isRegularFile(legacyFlat)) {
                AccountStore.StoredAccount fromFlat = AccountStore.read(legacyFlat, objectMapper);
                if (isOwnedByAccount(fromFlat, safeKey)) {
                    try {
                        AccountStore.write(file, fromFlat, objectMapper, retainsOwnerHistory(fromFlat.profile()));
                        Files.deleteIfExists(legacyFlat);
                    } catch (Exception ignored) {
                    }
                    s = fromFlat;
                }
            }
        }
        if (s == null && file != null) {
            Path rootDir = Path.of(properties.getDiskCacheDir());
            s = readLegacySnapshot(rootDir.resolve("twitterapi_io_v2_" + safeKey + ".json"));
        }
        if (s == null && file == null) {
            s = memoryStores.get(safeKey);
        }
        if (s != null && !isOwnedByAccount(s, safeKey)) {
            log.warn("Rejected store data mismatch: expected @{}, found @{}",
                    safeKey, s.profile() != null ? s.profile().username() : "null");
            return null;
        }
        return s;
    }

    private static boolean isOwnedByAccount(AccountStore.StoredAccount store, String expectedHandleKey) {
        return store != null
                && store.profile() != null
                && store.profile().username() != null
                && expectedHandleKey.equalsIgnoreCase(store.profile().username());
    }

    /** 旧版快照仅在内存中转换，缺少分页尾部的流保留已采集行和覆盖缺口。 */
    private AccountStore.StoredAccount readLegacySnapshot(Path legacy) {
        if (!Files.isRegularFile(legacy)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(Files.readString(legacy, StandardCharsets.UTF_8));
            RawAccountSnapshot old = objectMapper.treeToValue(root.path("snapshot"), RawAccountSnapshot.class);
            if (old == null || old.profile() == null || old.fetchedAt() == null || old.resolvedProvider() == ProviderMode.DEMO) {
                return null;
            }
            long at = old.fetchedAt().getEpochSecond();
            List<XUserSummary> fg = new java.util.ArrayList<>();
            List<XUserSummary> fr = new java.util.ArrayList<>();
            java.util.Set<String> back = new java.util.HashSet<>();
            for (RelationshipEntry r : old.relationships() == null ? List.<RelationshipEntry>of() : old.relationships()) {
                if (r == null || r.user() == null) continue;
                if (r.iFollowThem()) fg.add(r.user());
                if (r.theyFollowMe()) fr.add(r.user());
                if (r.iFollowThem() && r.theyFollowMe()) back.add(r.user().username().toLowerCase(Locale.ROOT));
            }
            return AccountStore.merge(
                    null, old.profile(), old.fetchedAt(), old.timeline() == null ? List.of() : old.timeline(),
                    old.inboundInteractions() == null ? List.of() : old.inboundInteractions(),
                    old.timelineTailTime() == null ? List.of() : List.of(new long[]{old.timelineTailTime().getEpochSecond(), at}),
                    old.mentionsTailTime() == null ? List.of() : List.of(new long[]{old.mentionsTailTime().getEpochSecond(), at}),
                    new AccountStore.GraphUpdate(fg, fr, back, old.followersTruncated(), old.followingsTruncated(),
                            old.followingsRequestFailed(), old.followersRequestFailed()), Map.of(), retainsOwnerHistory(old.profile())
            );
        } catch (Exception ex) {
            log.debug("Skipped legacy snapshot migration: {}", ex.getMessage());
            return null;
        }
    }

    private AccountStore.StoredAccount saveReportStore(String handle, String accountId, AccountStore.StoredAccount previous,
            AccountStore.StoredAccount candidate, UserUsageEntry usage) {
        long cycle = candidate.dailyRefreshedAt();
        long finished = candidate.reportCompletedAt();
        if (cycle > 0 && finished < cycle) {
            if (previous != null && previous.dailyRefreshedAt() == cycle && previous.reportCompletedAt() >= cycle) {
                finished = previous.reportCompletedAt();
            } else if (usage.completedReportCycle() == cycle && usage.lastReportSyncAt() != null) {
                finished = usage.lastReportSyncAt().getEpochSecond();
            } else if ((previous == null || cycle > previous.dailyRefreshedAt()) && isDailyRefreshComplete(candidate)) {
                finished = Math.max(cycle, Instant.now().getEpochSecond());
            }
            if (finished >= cycle) candidate = AccountStore.withDailyRefreshState(candidate, candidate.dailyRefreshStartedAt(), cycle, finished);
        }
        saveStore(handle, accountId, candidate);
        return candidate;
    }

    private void saveStore(String handleKey, String accountId, AccountStore.StoredAccount store) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        String ownerKey = accountOwnerKey(safeKey, accountId);
        verifyAccountOwner(store != null ? store.profile() : null, safeKey, accountId);
        if (accountId == null && !isOwnedByAccount(store, safeKey)) {
            throw new IllegalStateException("数据隔离校验未通过：禁止将 @"
                    + (store != null && store.profile() != null ? store.profile().username() : "null")
                    + " 的数据写入 @" + safeKey + " 存储区");
        }
        Path file = accountStoreFile(safeKey, accountId);
        if (file == null) {
            if (memoryStores.size() >= MAX_MEMORY_STORES && !memoryStores.containsKey(ownerKey)) {
                Iterator<String> it = memoryStores.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            memoryStores.put(ownerKey, AccountStore.pruneToRollingWindow(store, store.syncedAtInstant(),
                    retainsOwnerHistory(store.profile())));
            return;
        }
        // 已启用磁盘落盘时不再向 memoryStores 常驻重复副本，避免成千上万历史用户累积占内存
        memoryStores.remove(ownerKey);
        String refreshId = MDC.get("workspaceRefreshId");
        long writeStarted = System.nanoTime();
        try {
            boolean archiveOwner = retainsOwnerHistory(store.profile());
            AccountStore.write(file, store, objectMapper, archiveOwner);
            Path legacyFlat = accountId == null ? legacyFlatStoreFile(safeKey) : null;
            if (legacyFlat != null) {
                Files.deleteIfExists(legacyFlat);
            }
            if (refreshId != null) log.info("workspace_refresh refresh_id={} stage=persist elapsed_ms={} result=written",
                    refreshId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStarted));
        } catch (Exception ex) {
            log.warn("workspace_refresh refresh_id={} stage=persist elapsed_ms={} result=failed reason={}",
                    refreshId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStarted), ex.getClass().getSimpleName());
            throw new XDataProviderException(503, "采集数据保存失败，已停止本次更新，请稍后重试");
        }
    }

    private MultiPeriodDashboardBundle computeBundleOnce(
            RawAccountSnapshot snapshot,
            boolean fromCache,
            long ttlSeconds,
            Instant expiresAt,
            boolean hasMoreHistoryForTarget
    ) {
        return computeBundleOnce(snapshot, fromCache, ttlSeconds, expiresAt, hasMoreHistoryForTarget, 0);
    }

    private MultiPeriodDashboardBundle computeBundleOnce(RawAccountSnapshot snapshot, boolean fromCache,
            long ttlSeconds, Instant expiresAt, boolean hasMoreHistoryForTarget, int pendingRelationshipChecks) {
        ZoneId zone = resolveZoneId();
        int effectiveCoveredDays = snapshot.coveredWindowDays();
        Instant coveredUntil = computeJointCoveredUntil(snapshot);
        return new MultiPeriodDashboardBundle(
                analyticsEngine.analyze(snapshot, 30, zone, fromCache, ttlSeconds, expiresAt),
                analyticsEngine.analyze(snapshot, 7, zone, fromCache, ttlSeconds, expiresAt),
                analyticsEngine.analyze(snapshot, 2, zone, fromCache, ttlSeconds, expiresAt),
                effectiveCoveredDays,
                hasMoreHistoryForTarget,
                coveredUntil,
                pendingRelationshipChecks
        );
    }

    private Instant computeJointCoveredUntil(RawAccountSnapshot snap) {
        Instant refNow = snap.fetchedAt() != null ? snap.fetchedAt() : Instant.now();
        Instant c30 = TwitterApiIoProvider.cutoffForWindowDays(refNow, 30);
        Instant tBound = snap.timelineExhausted() ? c30 : (snap.timelineTailTime() != null ? snap.timelineTailTime() : refNow);
        Instant mBound = snap.mentionsExhausted() ? c30 : (snap.mentionsTailTime() != null ? snap.mentionsTailTime() : refNow);
        return tBound.isAfter(mBound) ? tBound : mBound;
    }

    private MultiPeriodDashboardBundle refreshBundleTimestamps(
            MultiPeriodDashboardBundle bundle,
            boolean fromCache,
            long ttlSeconds,
            Instant expiresAt
    ) {
        long remain = Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        return new MultiPeriodDashboardBundle(
                withLiveCacheMeta(bundle.month(), fromCache, ttlSeconds, remain, expiresAt),
                withLiveCacheMeta(bundle.week(), fromCache, ttlSeconds, remain, expiresAt),
                withLiveCacheMeta(bundle.day(), fromCache, ttlSeconds, remain, expiresAt),
                bundle.coveredWindowDays(),
                fromCache ? false : bundle.hasMoreHistoryForTarget(),
                bundle.coveredUntil(),
                bundle.pendingRelationshipChecks(),
                withLiveCacheMeta(bundle.hours24(), fromCache, ttlSeconds, remain, expiresAt),
                bundle.accessPolicy(),
                bundle.dailyCacheStatus()
        );
    }

    private MultiPeriodDashboardBundle refreshWorkspaceTimestamps(
            MultiPeriodDashboardBundle bundle,
            boolean fromCache,
            long ttlSeconds,
            Instant expiresAt,
            AccessPolicy freshPolicy
    ) {
        long remain = bundle.hasMoreHistoryForTarget() ? 0L
                : Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        return new MultiPeriodDashboardBundle(
                withLiveCacheMeta(bundle.month(), fromCache, ttlSeconds, remain, expiresAt),
                withLiveCacheMeta(bundle.week(), fromCache, ttlSeconds, remain, expiresAt),
                withLiveCacheMeta(bundle.day(), fromCache, ttlSeconds, remain, expiresAt),
                bundle.coveredWindowDays(),
                bundle.hasMoreHistoryForTarget(),
                bundle.coveredUntil(),
                bundle.pendingRelationshipChecks(),
                withLiveCacheMeta(bundle.hours24(), fromCache, ttlSeconds, remain, expiresAt),
                freshPolicy != null ? freshPolicy : bundle.accessPolicy(),
                bundle.dailyCacheStatus()
        );
    }

    private MultiPeriodDashboardBundle buildWorkspaceBundle(
            RawAccountSnapshot snapshot,
            AccessPolicy policy,
            boolean fromCache,
            long ttlSeconds,
            Instant expiresAt,
            boolean hasMoreHistoryForTarget,
            int pendingRelationshipChecks
    ) {
        ZoneId zone = resolveZoneId();
        // 工作台统一采用「单次只拉 24h + 30 天滚动物理落盘」，对已入库滚动窗口数据统一按累计汇总窗口计算，
        // 避免 top5Full/作者账号在覆盖尾部存在未闭合缺口或历史空档时将已落盘推文和互动截空为 0；
        // AccessPolicy.accumulatedSummaryMode 字段契约与 dailyCacheStatus 覆盖缺口提示保持不变。
        boolean useAccumulatedWindow = true;
        List<String> unlocked = policy != null && policy.unlockedPeriods() != null
                ? policy.unlockedPeriods()
                : List.of(ReportPeriod.HOURS_24.code);
        CommunityDashboardReport hours24 = analyticsEngine.analyze(
                snapshot, ReportPeriod.HOURS_24, useAccumulatedWindow, zone, fromCache, ttlSeconds, expiresAt);
        CommunityDashboardReport hours48 = unlocked.contains(ReportPeriod.HOURS_48.code)
                ? analyticsEngine.analyze(snapshot, ReportPeriod.HOURS_48, useAccumulatedWindow, zone, fromCache, ttlSeconds, expiresAt)
                : null;
        CommunityDashboardReport week = unlocked.contains(ReportPeriod.WEEK.code)
                ? analyticsEngine.analyze(snapshot, ReportPeriod.WEEK, useAccumulatedWindow, zone, fromCache, ttlSeconds, expiresAt)
                : null;
        CommunityDashboardReport month = unlocked.contains(ReportPeriod.MONTH.code)
                ? analyticsEngine.analyze(snapshot, ReportPeriod.MONTH, useAccumulatedWindow, zone, fromCache, ttlSeconds, expiresAt)
                : null;
        return new MultiPeriodDashboardBundle(
                month,
                week,
                hours48,
                snapshot.coveredWindowDays(),
                hasMoreHistoryForTarget,
                computeJointCoveredUntil(snapshot),
                pendingRelationshipChecks,
                hours24,
                policy
        );
    }

    private CommunityDashboardReport withLiveCacheMeta(
            CommunityDashboardReport r,
            boolean fromCache,
            long ttlSeconds,
            long remainSeconds,
            Instant expiresAt
    ) {
        if (r == null) {
            return null;
        }
        return new CommunityDashboardReport(
                r.provider(),
                r.liveData(),
                fromCache,
                ttlSeconds,
                remainSeconds,
                r.fetchedAt(),
                expiresAt,
                r.bannerNotice(),
                r.periodCode(),
                r.periodLabel(),
                r.windowDays(),
                r.profile(),
                r.activity(),
                r.interactions(),
                r.followAudit(),
                r.contentTiming(),
                r.innerCircle(),
                r.algorithmHealth()
        );
    }

    private ProviderMode resolveProviderMode(String requestedProvider) {
        ProviderMode cfg = ProviderMode.fromString(properties.getDefaultProvider(), ProviderMode.AUTO);
        if (cfg == ProviderMode.DEMO) {
            return ProviderMode.DEMO;
        }
        if (!properties.isRequireLoginSelfOnly() && "DEMO".equalsIgnoreCase(requestedProvider)) {
            return ProviderMode.DEMO;
        }
        if (cfg != ProviderMode.AUTO) {
            if (cfg == ProviderMode.TWITTERAPI_IO && !canUsePublicOrPaidProvider())
                throw new XDataProviderException(400, "指定 TwitterAPI.io 数据源但未配置 API Key");
            if (cfg == ProviderMode.X_OFFICIAL_V2 && !properties.hasOfficialBearerToken())
                throw new XDataProviderException(400, "指定 X 官方数据源但未配置 Bearer Token");
            return cfg;
        }
        if (canUsePublicOrPaidProvider()) {
            return ProviderMode.TWITTERAPI_IO;
        }
        if (properties.hasOfficialBearerToken()) {
            return ProviderMode.X_OFFICIAL_V2;
        }
        return ProviderMode.DEMO;
    }

    /**
     * 主限流按归一化账号 handleKey 独立计数，每人每分钟最多 30 次实时增量批次，缓存命中不计入。
     * 共享出口 IP 上限为 max(600, 配置并发用户数 × 30)，默认 50 人对应 1500 次/分钟，
     * 避免共享 NAT 的正常用户在各自额度内先撞到旧 600 次总量限制；上游仍受统一付费预算约束。
     */
    private void checkLiveSyncRateLimit(String handleKey, String clientIp) {
        checkLiveSyncRateLimit(handleKey, clientIp, null);
    }

    private void checkLiveSyncRateLimit(String handleKey, String clientIp, String expectedAccountId) {
        if (isAuthorIdentity(handleKey, expectedAccountId)) {
            return;
        }
        String safeAccount = validateIsolatedHandleKey(handleKey);
        long nowMinute = Instant.now().getEpochSecond() / 60L;

        if (accountLiveRateMap.size() > 1024) {
            accountLiveRateMap.entrySet().removeIf(e -> e.getValue().minuteEpoch() < nowMinute);
        }
        RateWindow accountWin = accountLiveRateMap.compute(safeAccount, (k, prev) -> {
            if (prev == null || prev.minuteEpoch() != nowMinute) {
                return new RateWindow(nowMinute, 1);
            }
            return new RateWindow(nowMinute, prev.count() + 1);
        });
        if (accountWin.count() > ACCOUNT_LIVE_SYNC_LIMIT_PER_MINUTE) {
            throw new XDataProviderException(429, "当前账号 @" + safeAccount + " 同步过于频繁，请稍等片刻后再试");
        }

        String ipKey = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
        if (ipKey.length() > 64) {
            ipKey = ipKey.substring(0, 64);
        }
        if (ipLiveRateMap.size() > 1024) {
            ipLiveRateMap.entrySet().removeIf(e -> e.getValue().minuteEpoch() < nowMinute);
        }
        RateWindow ipWin = ipLiveRateMap.compute(ipKey, (k, prev) -> {
            if (prev == null || prev.minuteEpoch() != nowMinute) {
                return new RateWindow(nowMinute, 1);
            }
            return new RateWindow(nowMinute, prev.count() + 1);
        });
        long sharedIpLimit = Math.max((long) IP_FLOOD_GUARD_PER_MINUTE,
                Math.max(1L, properties.getMaxConcurrentUsers()) * ACCOUNT_LIVE_SYNC_LIMIT_PER_MINUTE);
        if (ipWin.count() > sharedIpLimit) {
            throw new XDataProviderException(429, "当前网络出口短时请求过高，请稍等片刻后再试");
        }
    }

    private void checkContinueRelationsRateLimit(String handleKey) {
        checkContinueRelationsRateLimit(handleKey, null);
    }

    private void checkContinueRelationsRateLimit(String handleKey, String expectedAccountId) {
        if (isAuthorIdentity(handleKey, expectedAccountId)) {
            return;
        }
        String safeAccount = validateIsolatedHandleKey(handleKey);
        long nowSec = Instant.now().getEpochSecond();
        long nowMinute = nowSec / 60L;
        long nowHour = nowSec / 3600L;

        if (accountRelationMinuteMap.size() > 1024) {
            accountRelationMinuteMap.entrySet().removeIf(e -> e.getValue().minuteEpoch() < nowMinute);
        }
        RateWindow minWin = accountRelationMinuteMap.compute(safeAccount, (k, prev) ->
                (prev == null || prev.minuteEpoch() != nowMinute)
                        ? new RateWindow(nowMinute, 1)
                        : new RateWindow(nowMinute, prev.count() + 1));
        if (minWin.count() > ACCOUNT_CONTINUE_RELATIONS_PER_MINUTE) {
            throw new XDataProviderException(429, "逐人关系核验点击过于频繁，请稍候十几秒再继续核验");
        }

        if (accountRelationHourMap.size() > 1024) {
            accountRelationHourMap.entrySet().removeIf(e -> e.getValue().minuteEpoch() < nowHour);
        }
        RateWindow hourWin = accountRelationHourMap.compute(safeAccount, (k, prev) ->
                (prev == null || prev.minuteEpoch() != nowHour)
                        ? new RateWindow(nowHour, 1)
                        : new RateWindow(nowHour, prev.count() + 1));
        if (hourWin.count() > ACCOUNT_CONTINUE_RELATIONS_PER_HOUR) {
            throw new XDataProviderException(429, "当前小时逐人关系核验批次已达安全上限，请稍后再继续核验");
        }
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = 3_600_000L)
    public void pruneExpiredAccountRecords() {
        Instant now = Instant.now();
        Instant cutoff = Instant.ofEpochSecond(now.minus(AccountStore.RECORD_RETENTION).getEpochSecond());
        String ownerId = com.xops.hub.model.AuthorIdentity.trustedId(assistantStore == null ? null : assistantStore.boundOwnerId());
        memoryStores.forEach((key, ignored) -> {
            synchronized (fetchLockFor(key)) {
                memoryStores.computeIfPresent(key, (k, current) -> current.profile() == null || ownerId.equals(current.profile().id())
                        ? current : AccountStore.pruneToRollingWindow(current, now, false));
            }
        });
        snapshotCache.forEach((key, cached) -> {
            RawAccountSnapshot snapshot = cached.snapshot();
            if (snapshot == null || snapshot.profile() == null || ownerId.equals(snapshot.profile().id())) return;
            boolean expired = snapshot.timeline().stream().anyMatch(t -> t.createdAt() != null && t.createdAt().isBefore(cutoff))
                    || snapshot.inboundInteractions().stream().anyMatch(i -> i.createdAt() != null && i.createdAt().isBefore(cutoff));
            if (expired) snapshotCache.remove(key, cached);
        });
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) return;
        Path root = Path.of(dir);
        try {
            new AccountCacheRetention(objectMapper, this::fetchLockFor, ownerId, now).pruneDirectory(root);
            AccountStore.evictExpiredDiskReads(root, now, ownerId);
        } catch (java.io.IOException ex) {
            log.warn("Could not finish rolling account cache maintenance: {}", ex.getClass().getSimpleName());
        }
    }

    private void pruneCacheIfNeeded() {
        Instant now = Instant.now();
        if (snapshotCache.size() >= MAX_CACHE_ENTRIES) {
            snapshotCache.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
            if (snapshotCache.size() >= MAX_CACHE_ENTRIES) {
                Iterator<Map.Entry<String, CachedSnapshot>> it = snapshotCache.entrySet().iterator();
                while (it.hasNext() && snapshotCache.size() >= MAX_CACHE_ENTRIES) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    private ZoneId resolveZoneId() {
        try {
            return ZoneId.of(properties.getTimezone());
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static Object[] newLocks() {
        Object[] locks = new Object[ACCOUNT_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private Object fetchLockFor(String ownerKey) {
        // All providers write the same account store; the lock must survive cache eviction.
        return fetchLocks[(ownerKey.hashCode() & 0x7fffffff) % fetchLocks.length];
    }

    private Object usageLockFor(String ownerKey) {
        return usageLocks[(ownerKey.hashCode() & 0x7fffffff) % usageLocks.length];
    }

    /**
     * 记录已登录用户进入工作台的使用次数（按每账号独立文件隔离读写，互不加全局锁）：
     * - 第 3 次使用：弹出一次作者说明，展示 @elidlicom 资料并引导关注；
     * - 若尚未关注 @elidlicom：逢 10 次（第 10、20、30… 次）弹出一次关注提示；已关注则不打扰。
     */
    public UsageCheckinResponse recordUsageCheckin(String rawHandle) {
        return recordUsageCheckin(rawHandle, null);
    }

    public UsageCheckinResponse recordUsageCheckin(String rawHandle, String expectedAccountId) {
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        boolean selfIsAuthor = isAuthorIdentity(handleKey, expectedAccountId);

        int newCount;
        boolean followingAuthor;
        boolean shouldShow = false;
        String reasonCode = "NONE";
        String badgeText = "";
        String modalTitle = "";
        String modalMessage = "";

        synchronized (usageLockFor(ownerKey)) {
            UserUsageEntry prev = loadUserUsage(ownerKey);
            newCount = prev.usageCount() + 1;
            followingAuthor = checkIfUserFollowsAuthor(handleKey, expectedAccountId);

            if (newCount == 3 && prev.lastModalShownAtCount() != 3) {
                shouldShow = true;
                reasonCode = "THIRD_USE_INTRO";
                badgeText = "第 3 次使用 · 作者说明";
                modalTitle = "你好，我是作者 @%s";
                modalMessage = (followingAuthor || selfIsAuthor)
                        ? "这是你第 3 次使用本工作台！因为每次使用这个网站服务，我都是真实付费调用 API 的。感谢你已经关注 @%s 结下这份善缘，后续更新都会第一时间在 X 分享！"
                        : "这是你第 3 次使用本工作台！因为每次使用这个网站服务，我都是真实付费调用 API 为你拉取数据的。免费开放给大家用，结个善缘，求关注 @%s！";
            } else if (newCount >= 10 && newCount % 10 == 0 && !followingAuthor && !selfIsAuthor
                    && prev.lastModalShownAtCount() != newCount) {
                shouldShow = true;
                reasonCode = "EVERY_10_REMINDER";
                badgeText = "第 " + newCount + " 次使用 · 结个善缘";
                modalTitle = "已陪伴你使用 " + newCount + " 次";
                modalMessage = "因为每次使用这个网站服务，我都是真实付费调用 API 的。如果这个工作台对你有帮助，结个善缘，求关注 @%s！关注后将不再弹出此提示。";
            }

            UserUsageEntry updated = new UserUsageEntry(
                    newCount,
                    shouldShow ? newCount : prev.lastModalShownAtCount(),
                    prev.optimisticFollowedAt(),
                    Instant.now(),
                    prev.syncedDates(), prev.completedReportCycle(), prev.lastReportSyncAt()
            );
            saveUserUsage(ownerKey, updated);
        }

        boolean allowProfileLookup = shouldShow;
        XUserSummary authorSummary = PaidCapabilityGate.withCaller(handle, expectedAccountId, selfIsAuthor,
                () -> resolveAuthorProfile(allowProfileLookup));
        if (shouldShow) {
            modalMessage = modalMessage.formatted(authorSummary.username()) + "\n\n" + AUTHOR_COST_STORY
                    + "\n开源仓库（可本地部署，与线上一致）：" + AUTHOR_REPO_URL;
        }
        return new UsageCheckinResponse(
                handle,
                newCount,
                shouldShow,
                reasonCode,
                badgeText,
                modalTitle.formatted(authorSummary.username()),
                modalMessage,
                followingAuthor,
                selfIsAuthor,
                authorSummary,
                "https://x.com/i/user/" + authorSummary.id(),
                "https://x.com/intent/follow?user_id=" + authorSummary.id()
        );
    }

    public UsageCheckinResponse getAuthorCardPreview(String rawHandle) {
        return getAuthorCardPreview(rawHandle, null);
    }

    public UsageCheckinResponse getAuthorCardPreview(String rawHandle, String expectedAccountId) {
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        boolean selfIsAuthor = isAuthorIdentity(handleKey, expectedAccountId);
        int currentCount;
        boolean followingAuthor;
        synchronized (usageLockFor(ownerKey)) {
            UserUsageEntry prev = loadUserUsage(ownerKey);
            currentCount = Math.max(1, prev.usageCount());
            followingAuthor = checkIfUserFollowsAuthor(handleKey, expectedAccountId);
        }
        XUserSummary authorSummary = PaidCapabilityGate.withCaller(handle, expectedAccountId, selfIsAuthor,
                () -> resolveAuthorProfile(true));
        String badgeText = "第 " + currentCount + " 次使用 · 作者说明";
        String modalTitle = "你好，我是作者 @" + authorSummary.username();
        String modalMessage = ((followingAuthor && !selfIsAuthor)
                ? "因为每次使用这个网站服务，我都是真实付费调用 API 的。感谢你已经关注 @%s 结下这份善缘，后续更新都会第一时间在 X 分享！"
                : "因为每次使用这个网站服务，我都是真实付费调用 API 为大家拉取数据的。免费开放使用，结个善缘，求关注 @%s！").formatted(authorSummary.username())
                + "\n\n" + AUTHOR_COST_STORY + "\n开源仓库（可本地部署，与线上一致）：" + AUTHOR_REPO_URL;
        return new UsageCheckinResponse(
                handle,
                currentCount,
                true,
                "MANUAL_PREVIEW",
                badgeText,
                modalTitle,
                modalMessage,
                followingAuthor,
                selfIsAuthor,
                authorSummary,
                "https://x.com/i/user/" + authorSummary.id(),
                "https://x.com/intent/follow?user_id=" + authorSummary.id()
        );
    }

    /** 保留旧端点兼容性：点击只能打开意向页，关注状态仅以已采集关系为证据。 */
    public Map<String, Object> markAuthorFollowedOptimistically(String rawHandle) {
        return markAuthorFollowedOptimistically(rawHandle, null);
    }

    public Map<String, Object> markAuthorFollowedOptimistically(String rawHandle, String expectedAccountId) {
        String handle = HandleNormalizer.normalize(rawHandle);
        String handleKey = validateIsolatedHandleKey(handle);
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        int currentCount;
        synchronized (usageLockFor(ownerKey)) {
            currentCount = loadUserUsage(ownerKey).usageCount();
        }
        boolean following = checkIfUserFollowsAuthor(handleKey, expectedAccountId);
        return Map.of("ok", true, "username", handle, "usageCount", currentCount,
                "followingAuthor", following, "pendingConfirmation", !following);
    }

    private boolean checkIfUserFollowsAuthor(String handleKey, String expectedAccountId) {
        if (isAuthorIdentity(handleKey, expectedAccountId)) return true;
        RawAccountSnapshot snap = findLatestSnapshotForUser(handleKey, expectedAccountId);
        if (snap != null) {
            harvestAuthorProfileFromSnapshot(snap);
        }
        return doesUserFollowAuthor(handleKey, expectedAccountId, snap);
    }

    public boolean isAuthorAccount(String accountId, String rawUsername) {
        return isAuthorIdentity(rawUsername, accountId);
    }

    private String trustedAuthorId() {
        return AuthorIdentity.trustedId(assistantStore == null ? null : assistantStore.boundOwnerId());
    }

    private String currentAuthorHandle(XUserSummary observed) {
        var binding = assistantStore == null ? null : assistantStore.tx(AssistantStore.Tx::ownerBinding);
        String id = AuthorIdentity.trustedId(binding == null ? null : binding.path("accountId").asText());
        String boundHandle = binding == null ? "" : binding.path("username").asText("");
        if (!boundHandle.isBlank() && binding.path("usernameObservedAt").asLong() > 0) return boundHandle;
        if (observed != null && id.equals(observed.id()) && observed.username() != null && !observed.username().isBlank()) {
            return observed.username();
        }
        CachedAuthorProfile cached = cachedAuthorProfile;
        if (cached != null && cached.profile() != null && id.equals(cached.profile().id())
                && cached.profile().username() != null && !cached.profile().username().isBlank()) return cached.profile().username();
        return boundHandle.isBlank() ? AUTHOR_USERNAME : boundHandle;
    }

    private boolean isAuthorIdentity(String handleKey, String accountId) {
        if (accountId == null || accountId.isBlank()) return false;
        try {
            return trustedAuthorId().equals(accountId);
        } catch (RuntimeException ex) {
            log.warn("Content-assistant owner binding unavailable during access check");
            return false;
        }
    }

    private boolean doesUserFollowAuthor(String handleKey, String expectedAccountId, RawAccountSnapshot currentSnapshot) {
        if (isAuthorIdentity(handleKey, expectedAccountId)) {
            return true;
        }
        RawAccountSnapshot snap = currentSnapshot != null
                ? currentSnapshot
                : findLatestSnapshotForUser(handleKey, expectedAccountId);
        if (snap != null && snap.relationships() != null) {
            String authorId = trustedAuthorId();
            for (RelationshipEntry r : snap.relationships()) {
                if (r != null && r.user() != null && authorId.equals(r.user().id())) {
                    if (r.iFollowThem()) {
                        return true;
                    }
                    if (r.relationshipVerified()) {
                        return false;
                    }
                }
            }
        }
        CachedAuthorRanks ranks = getOrComputeAuthorRanks();
        return ranks != null && expectedAccountId != null && !expectedAccountId.isBlank()
                && ranks.followerIds().contains(expectedAccountId.trim());
    }

    private ProviderMode resolvePreferredModeQuietly() {
        try {
            return resolveProviderMode(null);
        } catch (XDataProviderException ex) {
            return ProviderMode.fromString(properties.getDefaultProvider(), ProviderMode.AUTO);
        }
    }

    private RawAccountSnapshot findLatestSnapshotForUser(String handleKey, String expectedAccountId) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        String ownerKey = accountOwnerKey(safeKey, expectedAccountId);
        ProviderMode preferredMode = resolvePreferredModeQuietly();
        CachedSnapshot exactMem = preferredMode != null ? snapshotCache.get(preferredMode.name() + ":" + ownerKey) : null;
        if (cacheIdentityMatches(exactMem, safeKey, expectedAccountId)) {
            return exactMem.snapshot();
        }
        for (Map.Entry<String, CachedSnapshot> entry : snapshotCache.entrySet()) {
            if (entry.getKey().endsWith(":" + ownerKey)
                    && cacheIdentityMatches(entry.getValue(), safeKey, expectedAccountId)) {
                return entry.getValue().snapshot();
            }
        }
        AccountStore.StoredAccount stored = loadStore(safeKey, expectedAccountId);
        return stored != null && stored.profile() != null ? TwitterApiIoProvider.snapshotOf(stored, "", false) : null;
    }

    private void harvestAuthorProfileFromSnapshot(RawAccountSnapshot snap) {
        if (snap == null) {
            return;
        }
        String authorId = trustedAuthorId();
        if (snap.profile() != null && authorId.equals(snap.profile().id())) {
            cacheAuthorProfile(snap.profile());
            return;
        }
        if (snap.relationships() != null) {
            for (RelationshipEntry r : snap.relationships()) {
                if (r != null && r.user() != null && authorId.equals(r.user().id())) {
                    cacheAuthorProfile(r.user());
                    return;
                }
            }
        }
    }

    private XUserSummary resolveAuthorProfile(boolean allowLiveLookupIfMissing) {
        Instant now = Instant.now();
        String authorId = trustedAuthorId();
        CachedAuthorProfile mem = this.cachedAuthorProfile;
        if (mem != null && mem.profile() != null && authorId.equals(mem.profile().id()) && now.isBefore(mem.expiresAt())) {
            return withCanonicalAuthorIdentity(mem.profile());
        }
        CachedAuthorProfile disk = readDiskAuthorProfile();
        if (disk != null && (disk.profile() == null || !authorId.equals(disk.profile().id()))) disk = null;
        if (disk != null && disk.profile() != null && now.isBefore(disk.expiresAt())) {
            this.cachedAuthorProfile = disk;
            return withCanonicalAuthorIdentity(disk.profile());
        }
        if (allowLiveLookupIfMissing && System.currentTimeMillis() >= authorLiveLookupCooldownUntilMs) {
            synchronized (authorLookupLock) {
                Instant recheckNow = Instant.now();
                CachedAuthorProfile recheckMem = this.cachedAuthorProfile;
                if (recheckMem != null && recheckMem.profile() != null && authorId.equals(recheckMem.profile().id())
                        && recheckNow.isBefore(recheckMem.expiresAt())) {
                    return withCanonicalAuthorIdentity(recheckMem.profile());
                }
                if (System.currentTimeMillis() >= authorLiveLookupCooldownUntilMs) {
                    String lookupHandle = currentAuthorHandle(disk != null ? disk.profile() : null);
                    XUserSummary live = twitterApiIoProvider.fetchPublicUserSummaryQuietly(lookupHandle);
                    if (live != null && authorId.equals(live.id())) {
                        authorLiveLookupCooldownUntilMs = 0L;
                        XUserSummary merged = withCanonicalAuthorIdentity(live);
                        cacheAuthorProfile(merged);
                        return merged;
                    }
                    authorLiveLookupCooldownUntilMs = System.currentTimeMillis() + AUTHOR_LIVE_LOOKUP_NEGATIVE_COOLDOWN_MS;
                }
            }
        }
        if (disk != null && disk.profile() != null) {
            return withCanonicalAuthorIdentity(disk.profile());
        }
        return withCanonicalAuthorIdentity(null);
    }

    private XUserSummary withCanonicalAuthorIdentity(XUserSummary raw) {
        String handle = currentAuthorHandle(raw);
        if (raw == null) {
            return new XUserSummary(
                    trustedAuthorId(),
                    handle,
                    AUTHOR_DISPLAY_NAME,
                    AUTHOR_AVATAR_URL,
                    AUTHOR_DEFAULT_BIO,
                    true,
                    0,
                    0,
                    0,
                    "",
                    AUTHOR_BANNER_URL
            );
        }
        String dName = (raw.displayName() != null && !raw.displayName().isBlank() && !raw.displayName().equalsIgnoreCase(AUTHOR_USERNAME))
                ? raw.displayName()
                : AUTHOR_DISPLAY_NAME;
        String bio = (raw.bio() != null && !raw.bio().isBlank())
                ? raw.bio()
                : AUTHOR_DEFAULT_BIO;
        return new XUserSummary(
                raw.id() != null ? raw.id() : "",
                handle,
                dName,
                AUTHOR_AVATAR_URL,
                bio,
                true,
                raw.followersCount(),
                raw.followingCount(),
                raw.totalPostsCount(),
                raw.joinedDate() != null ? raw.joinedDate() : "",
                AUTHOR_BANNER_URL
        );
    }

    private void cacheAuthorProfile(XUserSummary summary) {
        if (summary == null || summary.username() == null || summary.username().isBlank()) {
            return;
        }
        XUserSummary canonical = withCanonicalAuthorIdentity(summary);
        CachedAuthorProfile entry = new CachedAuthorProfile(canonical, Instant.now().plus(Duration.ofHours(24)));
        this.cachedAuthorProfile = entry;
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Path folder = Path.of(dir);
            Files.createDirectories(folder);
            Path target = folder.resolve("author_profile_elidlicom.json");
            Path tmp = folder.resolve("author_profile_elidlicom.json.tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(entry), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveEx) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.debug("Ignored author profile cache write: {}", ex.getMessage());
        }
    }

    private CachedAuthorProfile readDiskAuthorProfile() {
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(dir).resolve("author_profile_elidlicom.json");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), CachedAuthorProfile.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private UserUsageEntry readUsageSnapshot(String ownerKey) {
        synchronized (usageLockFor(ownerKey)) {
            return loadUserUsage(ownerKey);
        }
    }

    private UserUsageEntry recordWorkspaceSyncSuccess(
            String ownerKey, AccountStore.StoredAccount previousStore, AccountStore.StoredAccount completedStore, ZoneId zone
    ) {
        synchronized (usageLockFor(ownerKey)) {
            UserUsageEntry prev = loadUserUsage(ownerKey);
            if (!isDailyRefreshComplete(completedStore)) return prev;
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            long cycle = prev.completedReportCycle();
            Instant finished = prev.lastReportSyncAt();
            long completed = completedStore.dailyRefreshedAt();
            boolean firstCompletion = (previousStore == null || completed > previousStore.dailyRefreshedAt())
                    && completed > cycle;
            Instant success = lastReportCompletion(completedStore, prev);
            if (firstCompletion) {
                cycle = completed;
                finished = completedStore.reportCompletedAt() > 0
                        ? Instant.ofEpochSecond(completedStore.reportCompletedAt()) : now;
                success = finished;
            }
            Instant previous = previousStore != null && previousStore.dailyRefreshedAt() > 0
                    && (previousStore.reportCompletedAt() >= previousStore.dailyRefreshedAt()
                    || prev.completedReportCycle() == previousStore.dailyRefreshedAt() && prev.lastReportSyncAt() != null
                    || previousStore.coverageProofVersion() >= AccountStore.VERIFIED_COVERAGE_VERSION && isDailyRefreshComplete(previousStore))
                    ? lastReportCompletion(previousStore, prev) : null;
            List<String> mergedDates = mergeSyncedDates(prev.syncedDates(),
                    previous == null ? null : previous.atZone(zone).toLocalDate().toString(),
                    success.atZone(zone).toLocalDate().toString());
            if (cycle == prev.completedReportCycle() && mergedDates.equals(prev.syncedDates())) return prev;
            UserUsageEntry next = new UserUsageEntry(prev.usageCount(), prev.lastModalShownAtCount(),
                    prev.optimisticFollowedAt(), now, mergedDates, cycle, finished);
            saveUserUsage(ownerKey, next);
            return next;
        }
    }

    private static List<String> mergeSyncedDates(List<String> existing, String priorDate, String today) {
        String minRollingDate = null;
        if (today != null && today.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                minRollingDate = LocalDate.parse(today.trim()).minusDays(30).toString();
            } catch (Exception ignored) {
                minRollingDate = null;
            }
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (existing != null) {
            for (String d : existing) {
                if (d == null || d.isBlank()) continue;
                String trimmed = d.trim();
                if (minRollingDate != null && trimmed.matches("\\d{4}-\\d{2}-\\d{2}") && trimmed.compareTo(minRollingDate) < 0) {
                    continue;
                }
                set.add(trimmed);
            }
        }
        if (priorDate != null && !priorDate.isBlank()) {
            String trimmedPrior = priorDate.trim();
            if (minRollingDate == null || !trimmedPrior.matches("\\d{4}-\\d{2}-\\d{2}") || trimmedPrior.compareTo(minRollingDate) >= 0) {
                set.add(trimmedPrior);
            }
        }
        if (today != null && !today.isBlank()) {
            set.add(today.trim());
        }
        List<String> sorted = new ArrayList<>(set);
        sorted.sort(String::compareTo);
        if (sorted.size() > MAX_SYNCED_DATES) {
            sorted = new ArrayList<>(sorted.subList(sorted.size() - MAX_SYNCED_DATES, sorted.size()));
        }
        return List.copyOf(sorted);
    }

    private AccessPolicy buildAccessPolicy(
            String handleKey,
            RawAccountSnapshot snapshot,
            UserUsageEntry usage,
            ZoneId zone,
            String today,
            Integer authorRank
    ) {
        String snapAccountId = snapshot != null && snapshot.profile() != null ? snapshot.profile().id() : null;
        boolean selfIsAuthor = isAuthorIdentity(handleKey, snapAccountId);
        boolean followsAuthor = selfIsAuthor || doesUserFollowAuthor(handleKey, snapAccountId, snapshot);
        boolean top5Full = selfIsAuthor || (authorRank != null && authorRank <= TOP_FREE_FULL_RANK);
        boolean top50AutoSync = !selfIsAuthor && authorRank != null && authorRank <= TOP_AUTO_SYNC_RANK && top50SyncConfigured();
        ScheduledPublishAccess schedAccess = computeScheduledPublishAccess(selfIsAuthor, followsAuthor, authorRank);

        LocalDate reportDate = today == null ? LocalDate.now(zone) : LocalDate.parse(today);
        int accumulatedDays = 0;
        if (usage != null) {
            Set<String> succeeded = new java.util.HashSet<>(usage.syncedDates());
            for (int offset = 0; offset < MAX_SYNCED_DATES; offset++) {
                if (succeeded.contains(reportDate.minusDays(offset).toString())) accumulatedDays++;
            }
        }

        List<String> unlocked = new ArrayList<>();
        Map<String, String> lockedReasons = new LinkedHashMap<>();
        unlocked.add(ReportPeriod.HOURS_24.code);

        if (top5Full || accumulatedDays >= 2) {
            unlocked.add(ReportPeriod.HOURS_48.code);
        } else {
            lockedReasons.put(ReportPeriod.HOURS_48.code,
                    String.format("近 30 天内累计 2 天完成同步后解锁 48 小时窗口（当前 %d/2 天）。", accumulatedDays));
        }

        if (top5Full || accumulatedDays >= 7) {
            unlocked.add(ReportPeriod.WEEK.code);
        } else {
            lockedReasons.put(ReportPeriod.WEEK.code,
                    String.format("近 30 天内累计 7 天完成同步后解锁 7 天窗口（当前 %d/7 天）。", accumulatedDays));
        }

        if (top5Full || accumulatedDays >= 30) {
            unlocked.add(ReportPeriod.MONTH.code);
        } else {
            lockedReasons.put(ReportPeriod.MONTH.code,
                    String.format("近 30 天内累计 30 天完成同步后解锁 30 天窗口（当前 %d/30 天）。", accumulatedDays));
        }

        boolean accumulatedSummaryMode = !top5Full;

        String accumulationNote = top5Full
                ? (selfIsAuthor
                        ? "作者账号已解锁全部时间维度；本人账号与付费数据全量持久化入库。"
                        : String.format("你与作者的互动排名第 %d，已解锁全部时间维度。%s",
                                authorRank, top50AutoSync
                                        ? "系统每日自动同步最近 24 小时数据并落盘，保留近 30 天。"
                                        : "可手动刷新最近 24 小时数据，保留近 30 天。"))
                : (top50AutoSync
                        ? String.format("你与作者的互动排名第 %d。系统每日自动同步最近 24 小时数据，近 30 天内已完成同步 %d 天。",
                                authorRank, accumulatedDays)
                        : String.format("当前滚动窗口已累计 %d/30 天，单次加载最近 24 小时，动态保留近 30 天数据。",
                                accumulatedDays));

        return new AccessPolicy(
                accumulatedDays,
                followsAuthor,
                currentAuthorHandle(snapshot == null ? null : snapshot.profile()),
                authorRank,
                top5Full,
                top50AutoSync,
                List.copyOf(unlocked),
                Map.copyOf(lockedReasons),
                accumulatedSummaryMode,
                accumulationNote,
                AUTHOR_COST_STORY,
                AUTHOR_REPO_URL,
                schedAccess.allowed(),
                schedAccess.reason(),
                trustedAuthorId()
        );
    }

    public ScheduledPublishAccess evaluateScheduledPublishAccess(String accountId, String rawUsername) {
        if (rawUsername == null || rawUsername.isBlank()) {
            return computeScheduledPublishAccess(false, false, null);
        }
        String handleKey;
        try {
            String handle = HandleNormalizer.normalize(rawUsername);
            handleKey = validateIsolatedHandleKey(handle);
        } catch (RuntimeException ex) {
            return computeScheduledPublishAccess(false, false, null);
        }
        boolean selfIsAuthor = isAuthorIdentity(handleKey, accountId);
        boolean followsAuthor = selfIsAuthor || doesUserFollowAuthor(handleKey, accountId, null);
        Integer authorRank = resolveAuthorInteractionRank(handleKey, accountId);
        return computeScheduledPublishAccess(selfIsAuthor, followsAuthor, authorRank);
    }

    private static ScheduledPublishAccess computeScheduledPublishAccess(
            boolean selfIsAuthor,
            boolean followsAuthor,
            Integer authorRank
    ) {
        if (selfIsAuthor) {
            return new ScheduledPublishAccess(true, true, authorRank, null);
        }
        return new ScheduledPublishAccess(
                false,
                followsAuthor,
                authorRank,
                SCHEDULE_OWNER_REQUIRED
        );
    }

    private int computeStoredCoveredDays(AccountStore.StoredAccount stored) {
        if (stored == null || stored.syncedAtInstant() == null) return 0;
        return TwitterApiIoProvider.snapshotOf(stored, "", false).coveredWindowDays();
    }

    public Integer resolveAuthorInteractionRank(String handleKey, String accountId) {
        if (isAuthorIdentity(handleKey, accountId)) {
            return 0;
        }
        CachedAuthorRanks ranks = getOrComputeAuthorRanks();
        if (ranks == null) {
            return null;
        }
        return accountId != null && !accountId.isBlank() ? ranks.rankById().get(accountId.trim()) : null;
    }

    private static final Comparator<PeerInteractionStat> PEER_RANK_ORDER =
            Comparator.comparingInt((PeerInteractionStat p) -> p.myRepliesToPeer() + p.peerRepliesToMe()).reversed()
                    .thenComparing(Comparator.comparingInt(PeerInteractionStat::peerRepliesToMe).reversed())
                    .thenComparing(Comparator.comparingDouble(PeerInteractionStat::intimacyScore).reversed())
                    .thenComparing((PeerInteractionStat p) -> p.peer() != null && p.peer().verified(), Comparator.reverseOrder())
                    .thenComparing(PeerInteractionStat::latestInteractionAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(p -> p.peer() != null && p.peer().username() != null
                            ? p.peer().username().toLowerCase(Locale.ROOT) : "");

    /** Durable long-history archive candidate: restricted strictly to @elidlicom (non-owner accounts keep 30d rolling window). */
    public List<LongHistoryCandidate> longHistoryCandidates() {
        AccountStore.StoredAccount authorStore = findAuthorStoredAccount();
        if (authorStore == null || !retainsOwnerHistory(authorStore.profile())) {
            return List.of();
        }
        RawAccountSnapshot snapshot = TwitterApiIoProvider.snapshotOf(authorStore, "", false);
        int qualificationDays = snapshot.coveredWindowDays();
        boolean provisional = qualificationDays < 30;
        return List.of(new LongHistoryCandidate(
                authorStore.profile(),
                0,
                true,
                qualificationDays,
                provisional,
                authorStore.syncedAt() * 1000L
        ));
    }

    @Scheduled(fixedDelayString = "${xops.top10-silent-history-poll-ms:45000}")
    public void scheduledSilentTop10HistoryWarmup() {
        if (!isSilentHistoryEnabled() || hasActiveForegroundWork()) {
            return;
        }
        if (!silentHistoryRunning.compareAndSet(false, true)) {
            return;
        }
        silentHistoryExecutor.submit(() -> {
            try {
                runOneSilentTop10HistoryStepNow();
            } catch (RuntimeException ex) {
                log.debug("Owner history warmup skipped: {}", ex.getMessage());
            } finally {
                silentHistoryRunning.set(false);
            }
        });
    }

    /**
     * 后台分片补齐作者账号的长期历史。
     * - 前台存在任何实时同步或后台手动刷新任务时立即让路；
     * - 网络拉取在账号锁之外进行，仅在毫秒级磁盘合并时持锁，不阻塞用户当前使用。
     */
    public boolean runOneSilentTop10HistoryStepNow() {
        if (resolvePreferredModeQuietly() != ProviderMode.TWITTERAPI_IO || !canUsePublicOrPaidProvider()) {
            return false;
        }
        if (hasActiveForegroundWork()) {
            return false;
        }
        long precheckNowMs = System.currentTimeMillis();
        if (precheckNowMs < silentHistoryBudgetCooldownUntilMs) {
            return false;
        }
        if (twitterApiIoProvider.isBackgroundPaidBudgetExhausted()
                && !twitterApiIoProvider.canReadTimelineForFree("")) {
            silentHistoryBudgetCooldownUntilMs = precheckNowMs + 300_000L;
            return false;
        }
        return OwnerArchiveAccess.duringRefresh(properties.getDiskCacheDir(), trustedAuthorId(), this::refreshSilentHistory);
    }

    private boolean refreshSilentHistory() {
        List<LongHistoryCandidate> candidates = longHistoryCandidates();
        if (candidates.isEmpty()) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        Instant now = Instant.ofEpochMilli(nowMs);
        LongHistoryCandidate chosen = null;
        long oldestAttempt = Long.MAX_VALUE;
        final String ownerSilentTimelineKey = "silent-history:elidlicom";
        final String ownerSilentInboundKey = "silent-history:elidlicom:inbound";
        for (LongHistoryCandidate candidate : candidates) {
            XUserSummary u = candidate.user();
            if (u == null || u.id() == null || u.id().isBlank() || u.username() == null || u.username().isBlank()) {
                continue;
            }
            String handleKey;
            try {
                handleKey = validateIsolatedHandleKey(HandleNormalizer.normalize(u.username()));
            } catch (RuntimeException ex) {
                continue;
            }
            boolean isOwnerCand = candidate.owner() || isAuthorIdentity(handleKey, u.id());
            boolean ownerTimelineExhausted = isOwnerCand && paidCapabilityGate != null
                    && (paidCapabilityGate.isExhausted("silent-history:elidlicom")
                        || paidCapabilityGate.isExhausted(ownerSilentTimelineKey));
            if (ownerTimelineExhausted) {
                continue;
            }
            String ownerKey = accountOwnerKey(handleKey, u.id());
            String cacheKey = ProviderMode.TWITTERAPI_IO.name() + ":" + ownerKey;
            AtomicBoolean bg = backgroundRefreshes.get(cacheKey);
            if (bg != null && bg.get()) {
                continue;
            }
            long cooldownUntil = silentHistoryDegradedCooldownUntilMs.getOrDefault(ownerKey, 0L);
            if (cooldownUntil > nowMs) {
                continue;
            }
            AccountStore.StoredAccount existing = loadStore(handleKey, u.id());
            if (!needsSilent30DayBackfill(existing, now)) {
                continue;
            }
            if (candidate.owner()) {
                chosen = candidate;
                break;
            }
            long lastAttempt = silentHistoryLastAttemptMs.getOrDefault(ownerKey, 0L);
            if (lastAttempt < oldestAttempt) {
                oldestAttempt = lastAttempt;
                chosen = candidate;
            }
        }
        if (chosen == null || hasActiveForegroundWork()) {
            return false;
        }
        XUserSummary targetUser = chosen.user();
        String handle = HandleNormalizer.normalize(targetUser.username());
        String handleKey = validateIsolatedHandleKey(handle);
        String uid = targetUser.id().trim();
        String ownerKey = accountOwnerKey(handleKey, uid);
        String cacheKey = ProviderMode.TWITTERAPI_IO.name() + ":" + ownerKey;

        AccountStore.StoredAccount before = loadStore(handleKey, uid);
        if (before != null) {
            verifyAccountOwner(before.profile(), handleKey, uid);
        }
        silentHistoryLastAttemptMs.put(ownerKey, nowMs);
        boolean chosenOwner = chosen.owner() || isAuthorIdentity(handleKey, uid);
        boolean bindSilentScope = chosenOwner || !twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null;
        boolean usePureFreeSilent = twitterApiIoProvider.isFreeInboundConfigured();
        boolean ownerInboundPaused = chosenOwner && usePureFreeSilent && paidCapabilityGate != null
                && paidCapabilityGate.isExhausted(ownerSilentInboundKey);
        boolean freeSearchCircuitOpen = chosenOwner && usePureFreeSilent && twitterApiIoProvider.isFreeSearchCircuitOpen();
        boolean skipInboundThisTick = ownerInboundPaused || freeSearchCircuitOpen;
        java.util.function.Supplier<TwitterApiIoProvider.SyncOutcome> silentCall = () -> {
            if (!usePureFreeSilent) {
                return twitterApiIoProvider.syncStoreSilentHistoryStep(handle, before, 3, uid);
            }
            return skipInboundThisTick
                    ? twitterApiIoProvider.syncStoreSilentHistoryStep(handle, before, 3, uid, true, true)
                    : twitterApiIoProvider.syncStoreSilentHistoryStep(handle, before, 3, uid, true);
        };
        TwitterApiIoProvider.SyncOutcome outcome = bindSilentScope
                ? com.xops.hub.provider.PaidCapabilityGate.withCaller(handleKey, uid, chosenOwner, silentCall)
                : silentCall.get();
        // If free inbound search circuit is open and not yet exhausted, register inbound idempotent retry separately without blocking timeline!
        if (chosenOwner && usePureFreeSilent && !ownerInboundPaused && freeSearchCircuitOpen && paidCapabilityGate != null) {
            paidCapabilityGate.scheduleIdempotentRetry(
                    ownerSilentInboundKey,
                    "fxtwitter_free_read",
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                    uid,
                    "免费入站搜索暂不可用",
                    () -> {
                        twitterApiIoProvider.resetFreeCircuitForActiveSync();
                        runOneSilentTop10HistoryStepNow();
                    }
            );
        } else if (chosenOwner && !skipInboundThisTick && !outcome.degraded() && paidCapabilityGate != null) {
            paidCapabilityGate.markSucceeded(ownerSilentInboundKey);
        }
        if (outcome.degraded() && !outcome.progressed()) {
            long cooldownUntil = System.currentTimeMillis() + com.xops.hub.provider.PaidCapabilityGate.RETRY_INTERVAL_MS;
            silentHistoryDegradedCooldownUntilMs.put(ownerKey, cooldownUntil);
            if (chosenOwner && paidCapabilityGate != null) {
                paidCapabilityGate.scheduleIdempotentRetry(
                        ownerSilentTimelineKey,
                        usePureFreeSilent ? "fxtwitter_free_read" : "twitterapi_io_read",
                        com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                        uid,
                        "静默历史回填暂未推进",
                        () -> {
                            silentHistoryDegradedCooldownUntilMs.remove(ownerKey);
                            twitterApiIoProvider.resetFreeCircuitForActiveSync();
                            runOneSilentTop10HistoryStepNow();
                        }, false, !usePureFreeSilent
                );
            }
            if (!chosenOwner && twitterApiIoProvider.isBackgroundPaidBudgetExhausted()) {
                silentHistoryBudgetCooldownUntilMs = cooldownUntil;
            }
        } else {
            silentHistoryDegradedCooldownUntilMs.remove(ownerKey);
            if (chosenOwner && paidCapabilityGate != null) {
                paidCapabilityGate.markSucceeded(ownerSilentTimelineKey);
                paidCapabilityGate.markSucceeded("silent-history:elidlicom");
            }
        }
        if (outcome.store() == null || outcome.store().profile() == null || (!outcome.progressed() && before != null)) {
            return false;
        }
        ZoneId zone = resolveZoneId();
        String today = LocalDate.now(zone).toString();
        String algoFp = currentAlgoFingerprint();
        Object lock = fetchLockFor(ownerKey);
        synchronized (lock) {
            AccountStore.StoredAccount latest = loadStore(handleKey, uid);
            if (latest != null) {
                verifyAccountOwner(latest.profile(), handleKey, uid);
            }
            AccountStore.StoredAccount merged = AccountStore.mergeStoredSlice(before, latest, outcome.store(),
                    retainsOwnerHistory(outcome.store().profile()));
            verifyAccountOwner(merged.profile(), handleKey, uid);
            saveStore(handleKey, uid, merged);
            if (retainsOwnerHistory(merged.profile())) {
                cacheAuthorProfile(merged.profile());
            }
            CachedSnapshot cached = snapshotCache.get(cacheKey);
            if (cached != null && cacheIdentityMatches(cached, handleKey, uid)) {
                boolean completedDaily = isDailyRefreshComplete(merged);
                RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(merged, "", !completedDaily);
                snap = withNotice(snap, buildDailyRefreshNotice(merged, completedDaily));
                UserUsageEntry usage = usageWithProvenCompletion(readUsageSnapshot(ownerKey), merged, zone);
                Instant expires = dailyRefreshExpiresAt(merged, usage, Instant.now());
                Integer rank = resolveAuthorInteractionRank(handleKey, uid);
                AccessPolicy policy = buildAccessPolicy(handleKey, snap, usage, zone, today, rank);
                MultiPeriodDashboardBundle updatedBundle = buildWorkspaceBundle(
                        snap, policy, true, DAILY_REFRESH_SECONDS, expires, !completedDaily,
                        TwitterApiIoProvider.pendingRelationshipChecks(merged))
                        .withDailyCacheStatus(dailyCacheStatus(merged, usage, false));
                putSnapshotCache(cacheKey, snap, updatedBundle, DAILY_REFRESH_SECONDS, expires, algoFp);
            }
        }
        return true;
    }

    private boolean retainsOwnerHistory(XUserSummary profile) {
        return profile != null && AccountStore.retainsFullHistory(profile.username(), profile.id(),
                assistantStore == null ? null : assistantStore.boundOwnerId());
    }

    private boolean needsSilent30DayBackfill(AccountStore.StoredAccount stored, Instant now) {
        if (stored == null || stored.profile() == null || stored.syncedAtInstant() == null) {
            return true;
        }
        boolean ownerFullHistory = retainsOwnerHistory(stored.profile());
        if (Duration.between(stored.syncedAtInstant(), now).getSeconds() >= (ownerFullHistory ? 120L : 21_600L)) {
            return true;
        }
        long to = stored.syncedAt();
        long from = TwitterApiIoProvider.cutoffForWindowDays(stored.syncedAtInstant(), 30).getEpochSecond();
        if (!AccountStore.missingRanges(stored.tweetCoverage(), from, to).isEmpty()
                || !AccountStore.missingRanges(stored.inboundCoverage(), from, to).isEmpty()) {
            return true;
        }
        if (ownerFullHistory && stored.tweets().size() < stored.profile().totalPostsCount()) {
            Instant joined = TwitterApiIoProvider.parseAccountJoinedInstant(stored.profile().joinedDate());
            if (joined != null && AccountStore.contiguousFrom(stored.tweetCoverage(), to) > joined.getEpochSecond()) {
                return true;
            }
        }
        return false;
    }

    private CachedAuthorRanks getOrComputeAuthorRanks() {
        AccountStore.StoredAccount authorStore = findAuthorStoredAccount();
        if (authorStore == null || authorStore.syncedAt() <= 0 || authorStore.profile() == null) {
            return null;
        }
        CachedAuthorRanks existing = this.cachedAuthorRanks;
        long now = Instant.now().getEpochSecond();
        if (existing != null && existing.source().get() == authorStore && now < existing.evidenceExpiresAt()) {
            return existing;
        }
        RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(authorStore, "", false);
        CommunityDashboardReport monthReport = analyticsEngine.analyze(
                snap, ReportPeriod.MONTH, true, resolveZoneId(), true, 0L, snap.fetchedAt());
        List<PeerInteractionStat> ranked = new ArrayList<>(monthReport.interactions().peerStats());
        ranked.sort(PEER_RANK_ORDER);

        Map<String, Integer> byId = new LinkedHashMap<>();
        int rank = 1;
        for (PeerInteractionStat p : ranked) {
            if (p.peer() == null) continue;
            String uid = p.peer().id() != null ? p.peer().id().trim() : "";
            if (!uid.isEmpty()) {
                byId.putIfAbsent(uid, rank);
            }
            rank++;
        }
        Set<String> followerIds = new LinkedHashSet<>();
        if (snap.relationships() != null) {
            for (RelationshipEntry r : snap.relationships()) {
                if (r != null && r.user() != null && r.theyFollowMe()) {
                    String uid = r.user().id() != null ? r.user().id().trim() : "";
                    if (!uid.isEmpty()) {
                        followerIds.add(uid);
                    }
                }
            }
        }
        CachedAuthorRanks computed = new CachedAuthorRanks(
                new WeakReference<>(authorStore),
                authorStore.relationshipEvidence().stream().mapToLong(e -> e.checkedAt() + 300)
                        .filter(expiry -> expiry > now).min().orElse(Long.MAX_VALUE),
                Map.copyOf(byId),
                Set.copyOf(followerIds)
        );
        this.cachedAuthorRanks = computed;
        return computed;
    }

    private AccountStore.StoredAccount findAuthorStoredAccount() {
        var binding = assistantStore == null ? null : assistantStore.tx(AssistantStore.Tx::ownerBinding);
        String id = com.xops.hub.model.AuthorIdentity.trustedId(binding == null ? null : binding.path("accountId").asText());
        String handle = binding == null ? AUTHOR_USERNAME : binding.path("username").asText(AUTHOR_USERNAME);
        try {
            return loadStore(handle.toLowerCase(Locale.ROOT), id);
        } catch (XDataProviderException ex) {
            if (ex.getStatusCode() != 409) throw ex;
            return null;
        }
    }

    /**
     * 每日定时为与 @elidlicom 互动排名前 50、且已登录落盘存有个人快照的活跃用户同步一次最新 24h 数据。
     * 未曾登录或未落盘的用户不会纳入定时同步，严控上游 API 开销。
     */
    @Scheduled(cron = "${xops.top50-auto-sync-cron:0 20 23 * * *}", zone = "${xops.timezone:Asia/Shanghai}")
    public void scheduledSyncTop50ActivePeers() {
        startTop50Sync().exceptionally(error -> {
            log.warn("Could not dispatch daily Top-50 sync", error);
            return 0;
        });
    }

    public int runTop50AutoSyncNow() {
        return startTop50Sync().join();
    }

    private CompletableFuture<Integer> startTop50Sync() {
        if (!top50SyncConfigured() || !top50AutoSyncRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        ZoneId zone = resolveZoneId();
        return CompletableFuture.supplyAsync(() -> {
            CachedAuthorRanks ranks = getOrComputeAuthorRanks();
            Map<String, String> accounts = new LinkedHashMap<>();
            if (ranks != null) {
                listPersistedStores(ranks).forEach(s -> accounts.put(s.profile().id(), s.profile().username()));
            }
            var roster = new Top50Roster(LocalDate.now(zone).toString(), accounts);
            if (paidCapabilityGate != null) stageTop50Roster(roster);
            return accounts;
        }, top50Workers).thenCompose(accounts -> {
            var tasks = accounts.entrySet().stream()
                    .map(entry -> CompletableFuture.supplyAsync(() -> runTop50Account(entry.getValue(), entry.getKey(), null, zone),
                            top50Workers).exceptionally(error -> {
                        log.warn("Could not complete daily sync for @{}", entry.getValue(), error);
                        return false;
                    })).toList();
            return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> (int) tasks.stream().filter(CompletableFuture::join).count());
        }).whenComplete((count, error) -> top50AutoSyncRunning.set(false));
    }

    private record Top50Roster(String day, Map<String, String> accounts) {}

    private Path top50RosterFile() {
        String dir = properties.getDiskCacheDir();
        return dir == null || dir.isBlank() ? null : Path.of(dir, "top50_batch_v1.json");
    }

    /** Journal the whole roster before creating individual checkpoints or starting the first request. */
    private void stageTop50Roster(Top50Roster roster) {
        synchronized (top50CheckpointLock) {
            Path file = top50RosterFile();
            recoverTop50Roster(file);
            if (roster.accounts().isEmpty()) return;
            if (file != null) writeTop50File(file, roster);
            checkpointTop50Roster(roster);
            if (file != null) writeTop50File(file, null);
        }
    }

    private void recoverTop50Roster(Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            checkpointTop50Roster(objectMapper.readValue(file.toFile(), Top50Roster.class));
            writeTop50File(file, null);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not recover daily Top-50 roster", ex);
        }
    }

    private void checkpointTop50Roster(Top50Roster roster) {
        roster.accounts().forEach((uid, handle) -> {
            Top50Retry existing = readTop50Retry(uid);
            if (existing == null || (existing.attempts() >= PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES
                    && !roster.day().equals(existing.scheduledDay()))) {
                writeTop50Retry(uid, new Top50Retry(handle, uid, roster.day(), 0, 0, "等待首次同步"));
            }
        });
    }

    private record Top50Retry(String handle, String accountId, String scheduledDay, int attempts, long dueAt, String error) {
        String key() { return "top50-free-sync:id:" + accountId + ":" + scheduledDay; }
    }

    private Path top50RetryFile(String uid) {
        Path store = accountStoreFile("_", uid);
        return store == null ? null : store.resolveSibling("top50_retry_v1.json");
    }

    private Top50Retry readTop50Retry(String uid) {
        Path file = top50RetryFile(uid);
        if (file == null) return volatileTop50Retries.get(uid);
        if (!Files.isRegularFile(file)) return null;
        try {
            Top50Retry retry = objectMapper.readValue(file.toFile(), Top50Retry.class);
            if (!uid.equals(retry.accountId())) throw new IllegalStateException("Top-50 checkpoint account mismatch");
            return retry;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not read Top-50 checkpoint", ex);
        }
    }

    private void writeTop50Retry(String uid, Top50Retry retry) {
        Path file = top50RetryFile(uid);
        synchronized (top50CheckpointLock) {
            if (file == null) {
                if (retry == null) volatileTop50Retries.remove(uid);
                else volatileTop50Retries.put(uid, retry);
            } else {
                writeTop50File(file, retry);
            }
        }
    }

    private void writeTop50File(Path file, Object value) {
        try {
            if (value == null) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "top50_retry_", ".tmp");
            try {
                objectMapper.writeValue(tmp.toFile(), value);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not persist Top-50 checkpoint", ex);
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restorePendingTop50Sync() {
        if (paidCapabilityGate == null || !top50SyncConfigured()) return;
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            volatileTop50Retries.values().forEach(this::restoreTop50Retry);
            return;
        }
        synchronized (top50CheckpointLock) {
            recoverTop50Roster(top50RosterFile());
        }
        Path root = Path.of(dir, "users", "by-id");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> accounts = Files.list(root)) {
            accounts.filter(p -> Files.isRegularFile(p.resolve("top50_retry_v1.json"))).forEach(p -> {
                try {
                    String uid = p.getFileName().toString();
                    synchronized (top50CheckpointLock) {
                        Top50Retry pending = readTop50Retry(uid);
                        if (pending != null) restoreTop50Retry(pending);
                    }
                } catch (RuntimeException ex) {
                    log.warn("Could not restore a Top-50 checkpoint: {}", ex.getMessage());
                }
            });
        } catch (java.io.IOException ex) {
            log.warn("Could not enumerate Top-50 checkpoints: {}", ex.getMessage());
        }
    }

    private void restoreTop50Retry(Top50Retry retry) {
        if (retry.dueAt() == 0) {
            top50Workers.execute(() -> runTop50Account(retry.handle(), retry.accountId(), null, resolveZoneId()));
            return;
        }
        paidCapabilityGate.restorePendingRetry(retry.key(), "fxtwitter_free_read", retry.handle(), retry.accountId(),
                retry.attempts(), retry.dueAt(), retry.error(),
                () -> runTop50Account(retry.handle(), retry.accountId(), retry.key(), resolveZoneId()));
    }

    private void keepTop50Pending(Top50Retry retry, String failure, boolean defer) {
        if (paidCapabilityGate == null) return;
        String key = retry.key();
        long due = paidCapabilityGate.nextRetryTimeMs();
        boolean deferred = defer && paidCapabilityGate.deferRunningRetry(key, due);
        var scheduled = paidCapabilityGate.scheduleIdempotentRetry(key, "fxtwitter_free_read", retry.handle(), retry.accountId(), failure,
                () -> runTop50Account(retry.handle(), retry.accountId(), key, resolveZoneId()));
        int attempts = Math.max(0, scheduled.retryCount() - (deferred ? 1 : 0));
        writeTop50Retry(retry.accountId(), new Top50Retry(retry.handle(), retry.accountId(), retry.scheduledDay(), attempts, due, failure));
    }

    private boolean runTop50Account(String handle, String uid, String retryKey, ZoneId zone) {
        String ownerKey = accountOwnerKey(handle, uid);
        synchronized (fetchLockFor(ownerKey)) {
            String today = LocalDate.now(zone).toString();
            Top50Retry pending = paidCapabilityGate == null ? null : readTop50Retry(uid);
            if (paidCapabilityGate != null && retryKey == null && pending == null) return false;
            if (retryKey != null && (pending == null || !pending.key().equals(retryKey))) {
                var receipt = paidCapabilityGate.getRetrySnapshot(retryKey);
                if (receipt != null && receipt.state() != PaidCapabilityGate.RetryState.SUCCEEDED) paidCapabilityGate.discardRetry(retryKey);
                return false;
            }
            var stored = loadStore(handle, uid);
            if (retryKey != null || pending != null && pending.dueAt() == 0) {
                var ranks = getOrComputeAuthorRanks();
                if (stored == null || !top50SyncConfigured() || ranks != null && !isTopAutoSyncCandidate(stored, ranks)) {
                    writeTop50Retry(uid, null);
                    paidCapabilityGate.discardRetry(pending.key());
                    return false;
                }
                if (ranks == null) {
                    keepTop50Pending(pending, "等待作者互动排名恢复", true);
                    return false;
                }
            }
            if (stored == null) return false;
            verifyAccountOwner(stored.profile(), handle, uid);
            String currentHandle = validateIsolatedHandleKey(stored.profile().username());
            Instant now = Instant.now();
            if (completedAutoSyncDay(stored, readUsageSnapshot(ownerKey), zone, now)) {
                if (pending != null) {
                    paidCapabilityGate.markSucceeded(pending.key());
                    writeTop50Retry(uid, null);
                }
                return false;
            }
            if (retryKey == null && pending != null && pending.dueAt() > 0) {
                if (pending.attempts() < PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES) {
                    restoreTop50Retry(pending);
                    return false;
                }
                if (today.equals(pending.scheduledDay())) return false;
            }
            String scheduledDay = pending == null ? today : pending.scheduledDay();
            int attempts = retryKey == null ? 0 : paidCapabilityGate.getRetrySnapshot(retryKey).retryCount();
            Top50Retry current = new Top50Retry(currentHandle, uid, scheduledDay, attempts,
                    paidCapabilityGate == null ? 0 : paidCapabilityGate.nextRetryTimeMs(), "等待完成最近 24 小时同步");
            boolean head = !canContinueDailyRefresh(stored, now);
            if (!head && !dailyCanContinue(stored)) {
                keepTop50Pending(current, "等待免费数据源恢复或下一轮可检查时间", true);
                return false;
            }
            if (paidCapabilityGate != null) writeTop50Retry(uid, current);
            Top50Batch result;
            try {
                result = syncTop50Batches(currentHandle, uid, stored, head);
            } catch (RuntimeException ex) {
                if (ex instanceof XDataProviderException provider && provider.getStatusCode() == 403
                        && PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE.equals(provider.getMessage())
                        && properties.isFxtwitterEnabled() && twitterApiIoProvider.isFreeSearchCircuitOpen()) {
                    keepTop50Pending(current, "等待免费搜索退避结束", true);
                    return false;
                }
                if (ex instanceof XDataProviderException provider && Set.of(401, 403, 404, 409).contains(provider.getStatusCode())) {
                    if (paidCapabilityGate != null) {
                        writeTop50Retry(uid, null);
                        paidCapabilityGate.discardRetry(current.key());
                    }
                    return false;
                }
                keepTop50Pending(current, ex.getMessage(), false);
                return false;
            }
            if (!isDailyRefreshComplete(result.store())) {
                keepTop50Pending(current, "前 50 活跃推友每日免费同步存在未完成项", result.defer());
                return false;
            }
            recordWorkspaceSyncSuccess(ownerKey, stored, result.store(), zone);
            if (paidCapabilityGate != null) {
                writeTop50Retry(uid, null);
                paidCapabilityGate.markSucceeded(current.key());
            }
            return true;
        }
    }

    private record Top50Batch(AccountStore.StoredAccount store, boolean defer) {}

    private Top50Batch syncTop50Batches(String handle, String uid,
            AccountStore.StoredAccount initial, boolean startCycle) {
        String handleKey = validateIsolatedHandleKey(handle);
        String ownerKey = accountOwnerKey(handleKey, uid);
        UserUsageEntry usage = readUsageSnapshot(ownerKey);
        boolean bindScope = !twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null;
        var current = initial;
        var previous = dailyCacheStatus(initial, usage, false);
        for (int batch = 1; ; batch++) {
            var base = current;
            boolean head = batch == 1 && startCycle;
            var outcome = bindScope
                    ? com.xops.hub.provider.PaidCapabilityGate.withCaller(handleKey, uid, false,
                            () -> twitterApiIoProvider.syncStoreByHours(handle, base, 24, head, true, null, uid))
                    : twitterApiIoProvider.syncStoreByHours(handle, base, 24, head, true, null, uid);
            current = outcome.store();
            verifyAccountOwner(current.profile(), handleKey, uid);
            current = saveReportStore(handleKey, uid, base, current, usage);
            snapshotCache.remove(ProviderMode.TWITTERAPI_IO.name() + ":" + ownerKey);
            if (isDailyRefreshComplete(current)) return new Top50Batch(current, false);
            var next = dailyCacheStatus(current, usage, false);
            if (!next.canContinue() || batch >= 20 || Thread.currentThread().isInterrupted()) return new Top50Batch(current, true);
            if (outcome.degraded() || !(head && outcome.progressed()) && !dailyStatusProgressed(previous, next)) {
                return new Top50Batch(current, false);
            }
            previous = next;
        }
    }

    private List<AccountStore.StoredAccount> listPersistedStores(CachedAuthorRanks ranks) {
        List<AccountStore.StoredAccount> result = new ArrayList<>();
        Set<String> seenOwnerKeys = new LinkedHashSet<>();
        String dir = properties.getDiskCacheDir();
        if (dir != null && !dir.isBlank()) {
            Path usersRoot = Path.of(dir).resolve("users");
            Path byIdRoot = usersRoot.resolve("by-id");
            if (Files.isDirectory(byIdRoot)) {
                try (Stream<Path> dirs = Files.list(byIdRoot)) {
                    for (Path p : (Iterable<Path>) dirs::iterator) {
                        Path storeFile = p.resolve("store_v" + AccountStore.VERSION + ".json.gz");
                        if (!Files.isRegularFile(storeFile)) continue;
                        AccountStore.StoredAccount st = AccountStore.read(storeFile, objectMapper);
                        if (st != null && st.profile() != null && p.getFileName().toString().equals(st.profile().id())) {
                            String key = "id:" + st.profile().id();
                            if (isTopAutoSyncCandidate(st, ranks) && seenOwnerKeys.add(key)) {
                                result.add(st);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Failed listing by-id persisted stores: {}", ex.getMessage());
                }
            }
            Map<String, AccountStore.StoredAccount> legacy = new LinkedHashMap<>();
            for (Path path : legacySnapshotFiles(Path.of(dir))) {
                var candidate = readLegacyAccount(path);
                if (!isTopAutoSyncCandidate(candidate, ranks)) continue;
                String key = "id:" + candidate.profile().id();
                if (!seenOwnerKeys.contains(key) && readUsageSnapshot(key).usageCount() > 0) {
                    legacy.merge(key, candidate, (a, b) -> a.syncedAt() >= b.syncedAt() ? a : b);
                }
            }
            seenOwnerKeys.addAll(legacy.keySet());
            result.addAll(legacy.values());
        }
        for (var entry : memoryStores.entrySet()) {
            AccountStore.StoredAccount mem = entry.getValue();
            if (mem != null && mem.profile() != null && mem.profile().username() != null) {
                String uid = mem.profile().id();
                String key = (uid != null && !uid.isBlank())
                        ? "id:" + uid
                        : mem.profile().username().toLowerCase(Locale.ROOT);
                if (isTopAutoSyncCandidate(mem, ranks)
                        && (key.equals(entry.getKey()) || readUsageSnapshot(key).usageCount() > 0)
                        && seenOwnerKeys.add(key)) {
                    result.add(mem);
                }
            }
        }
        return result;
    }

    private boolean isTopAutoSyncCandidate(AccountStore.StoredAccount store, CachedAuthorRanks ranks) {
        if (store == null || store.profile() == null || store.profile().username() == null) return false;
        if (retainsOwnerHistory(store.profile())) return false;
        Integer rank = store.profile().id() != null ? ranks.rankById().get(store.profile().id()) : null;
        return rank != null && rank <= TOP_AUTO_SYNC_RANK;
    }

    private boolean top50SyncConfigured() {
        return properties.isTop50AutoSyncEnabled() && resolvePreferredModeQuietly() == ProviderMode.TWITTERAPI_IO
                && twitterApiIoProvider != null && (properties.isFxtwitterEnabled()
                    || paidCapabilityGate == null && twitterApiIoProvider.isUsingLoopbackBaseUrl() && properties.hasTwitterApiIoKey());
    }

    private Path userUsageFile(String handleKey) {
        Path store = handleKey.startsWith("id:") ? accountStoreFile("_", handleKey.substring(3)) : resolveUserStoreFile(handleKey);
        return store != null ? store.getParent().resolve("usage_v1.json") : null;
    }

    private UserUsageEntry loadUserUsage(String handleKey) {
        String safeKey = handleKey.startsWith("id:")
                ? accountOwnerKey("_", handleKey.substring(3)) : validateIsolatedHandleKey(handleKey);
        Path uFile = userUsageFile(safeKey);
        if (uFile != null && Files.isRegularFile(uFile)) {
            try {
                UserUsageEntry entry = objectMapper.readValue(Files.readString(uFile, StandardCharsets.UTF_8), UserUsageEntry.class);
                if (entry != null) {
                    return entry;
                }
            } catch (Exception ex) {
                log.debug("Ignored unreadable user usage file for @{}: {}", safeKey, ex.getMessage());
            }
        }
        if (!safeKey.startsWith("id:")) ensureLegacyUsageHydratedOnce();
        UserUsageEntry fromLegacyOrMem = usageMap.get(safeKey);
        if (fromLegacyOrMem != null) {
            return fromLegacyOrMem;
        }
        return new UserUsageEntry(0, 0, null, Instant.now());
    }

    private void saveUserUsage(String handleKey, UserUsageEntry entry) {
        String safeKey = handleKey.startsWith("id:")
                ? accountOwnerKey("_", handleKey.substring(3)) : validateIsolatedHandleKey(handleKey);
        Path uFile = userUsageFile(safeKey);
        if (uFile == null) {
            if (usageMap.size() >= MAX_CACHE_ENTRIES && !usageMap.containsKey(safeKey)) {
                Iterator<String> it = usageMap.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            usageMap.put(safeKey, entry);
            return;
        }
        usageMap.remove(safeKey);
        try {
            Files.createDirectories(uFile.getParent());
            Path tmp = uFile.resolveSibling("usage_v1." + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L) + ".tmp");
            try {
                Files.writeString(tmp, objectMapper.writeValueAsString(entry), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, uFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception moveEx) {
                    Files.move(tmp, uFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception ex) {
            log.warn("Failed to persist isolated usage stats for @{}: {}", safeKey, ex.getMessage());
        }
    }

    private void ensureLegacyUsageHydratedOnce() {
        if (legacyUsageHydrated) {
            return;
        }
        synchronized (legacyUsageLock) {
            if (legacyUsageHydrated) {
                return;
            }
            legacyUsageHydrated = true;
            String dir = properties.getDiskCacheDir();
            if (dir == null || dir.isBlank()) {
                return;
            }
            try {
                Path file = Path.of(dir).resolve("usage_stats_v1.json");
                if (!Files.isRegularFile(file)) {
                    return;
                }
                Map<String, UserUsageEntry> loaded = objectMapper.readValue(
                        Files.readString(file, StandardCharsets.UTF_8),
                        new TypeReference<Map<String, UserUsageEntry>>() {
                        }
                );
                if (loaded != null) {
                    usageMap.putAll(loaded);
                }
            } catch (Exception ex) {
                log.debug("Ignored unreadable legacy usage stats file: {}", ex.getMessage());
            }
        }
    }
}
