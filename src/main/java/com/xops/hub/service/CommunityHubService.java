package com.xops.hub.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.assistant.AssistantStore;
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
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.store.AccountStore;
import com.xops.hub.util.HandleNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

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
    public static final String AUTHOR_PROFILE_URL = "https://x.com/elidlicom";
    public static final String AUTHOR_FOLLOW_INTENT_URL = "https://x.com/intent/follow?screen_name=elidlicom";
    public static final String AUTHOR_REPO_URL = "https://github.com/litany-lty/x-community-hub";
    public static final String AUTHOR_COST_STORY =
            "我目前负债十几个、失业、每个月靠贷款交房租。确实承担不起每个月过多的API费用。现在希望通过X平台解决人生危机。也希望朋友们可以谅解。如果限制让你感到不爽，可以到git仓库看开源代码，本地部署与线上效果一致。";
    public static final int DAILY_MANUAL_REFRESH_LIMIT = 1;
    public static final int TOP_FREE_FULL_RANK = 5;
    public static final int TOP_AUTO_SYNC_RANK = 50;
    public static final int TOP_SCHEDULED_PUBLISH_RANK = 100;
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
            String quotaDate,
            boolean initialLoadedToday,
            int manualRefreshUsedToday,
            List<String> syncedDates
    ) {
        @JsonCreator
        public UserUsageEntry {
            usageCount = Math.max(0, usageCount);
            manualRefreshUsedToday = Math.max(0, manualRefreshUsedToday);
            syncedDates = syncedDates == null ? List.of() : List.copyOf(syncedDates);
        }

        public UserUsageEntry(int usageCount, int lastModalShownAtCount, Instant optimisticFollowedAt, Instant updatedAt) {
            this(usageCount, lastModalShownAtCount, optimisticFollowedAt, updatedAt, null, false, 0, List.of());
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
            long authorSyncedAt,
            Map<String, Integer> rankById,
            Map<String, Integer> rankByUsername,
            Set<String> followerIds,
            Set<String> followerUsernames
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
    private volatile Path cachedAuthorStoreFile = null;
    private final AtomicBoolean top50AutoSyncRunning = new AtomicBoolean(false);
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
     * 按当前已累计天数、作者关注关系、@elidlicom 互动排名（前5全量 / 前50每日自动同步）及每日刷新配额返回工作台维度分析。
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
        MultiPeriodDashboardBundle bundle = analyzeWorkspaceBundle(rawHandle, targetPeriod, requestedProvider, forceRefresh,
                clientIp, expectedAccountId, continueRelations, false, preferStored);
        String handleKey = validateIsolatedHandleKey(HandleNormalizer.normalize(rawHandle));
        String key = resolveProviderMode(requestedProvider).name() + ":" + accountOwnerKey(handleKey, expectedAccountId);
        BackgroundRefreshStatus status = backgroundRefreshStatuses.get(key);
        return status != null ? bundle.withBackgroundRefreshStatus(status) : bundle;
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
            boolean sameDay = today.equals(quotaEntry.quotaDate());
            boolean initialLoadedToday = sameDay && quotaEntry.initialLoadedToday();

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
                                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, expectedAccountId,
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
                            () -> analyzeWorkspaceBundle(handle, ReportPeriod.HOURS_24, requestedProvider, false, clientIp, expectedAccountId, true)
                    );
                } else if (isOwner && paidCapabilityGate != null) {
                    paidCapabilityGate.markSucceeded("continue-relations:elidlicom:" + today);
                }
                verifyAccountOwner(continued.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, continued.store());
                RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(continued.store(),
                        "本次仅继续逐人关系核验；未成功确认的对象仍保留未知。", continued.degraded());
                Instant fallbackSynced = continued.store().syncedAtInstant() != null
                        ? continued.store().syncedAtInstant()
                        : (stored != null && stored.syncedAtInstant() != null ? stored.syncedAtInstant() : now);
                Instant expires = active != null ? active.expiresAt() : fallbackSynced.plusSeconds(configuredTtl);
                Integer rankAfter = resolveAuthorInteractionRank(handleKey, snap.profile().id());
                UserUsageEntry latestUsage = readUsageSnapshot(ownerKey);
                AccessPolicy policy = buildAccessPolicy(handleKey, snap, latestUsage, zone, today, rankAfter);
                MultiPeriodDashboardBundle bundle = buildWorkspaceBundle(
                        snap, policy, false, configuredTtl, expires, false,
                        TwitterApiIoProvider.pendingRelationshipChecks(continued.store()))
                        .withDailyCacheStatus(dailyCacheStatus(continued.store(), continued.degraded()));
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
                    paidCapabilityGate.resetByPrefix("history-source:");
                    paidCapabilityGate.resetByPrefix("workspace-sync:elidlicom:");
                }
            }
            AccountStore.StoredAccount rawStored = resolvedMode == ProviderMode.DEMO
                    ? null : loadStore(handleKey, expectedAccountId);
            if (rawStored != null) verifyAccountOwner(rawStored.profile(), handleKey, expectedAccountId);
            AccountStore.StoredAccount stored = AccountStore.pruneToRollingWindow(rawStored, now);
            boolean completedDaily = isDailyRefreshComplete(stored);
            boolean enforceHourlyCache = !authorCaller;
            boolean freshDaily = enforceHourlyCache && completedDaily && now.getEpochSecond() >= stored.dailyRefreshedAt()
                    && now.getEpochSecond() < stored.dailyRefreshedAt() + DAILY_REFRESH_SECONDS;
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
            if (resolvedMode != ProviderMode.DEMO && stored != null
                    && (coalesced || (!forceRefresh && (recentBgResult || justComputedSnapshot || recentDegradedCooldown))) && !freshDaily
                    && cacheIdentityMatches(active, handleKey, expectedAccountId)
                    && Objects.equals(algoFp, active.algoFingerprint())
                    && active.bundle() != null && active.bundle().hours24() != null) {
                AccessPolicy policy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
                return refreshWorkspaceTimestamps(active.bundle(), true, active.ttlSeconds(), active.expiresAt(), policy)
                        .withDailyCacheStatus(active.bundle().dailyCacheStatus() != null
                                ? active.bundle().dailyCacheStatus() : dailyCacheStatus(stored));
            }
            if (resolvedMode != ProviderMode.DEMO && stored != null && (freshDaily || exhaustedDailyAttempts)) {
                Instant warmExpires = dailyRefreshExpiresAt(stored, now);
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
                                .withDailyCacheStatus(dailyCacheStatus(stored));
                    }
                }
                RawAccountSnapshot warmSnap = TwitterApiIoProvider.snapshotOf(stored, "", !completedDaily);
                warmSnap = withNotice(warmSnap, buildDailyRefreshNotice(stored, completedDaily));
                AccessPolicy policy = buildAccessPolicy(handleKey, warmSnap, quotaEntry, zone, today, initialRank);
                MultiPeriodDashboardBundle warmBundle = buildWorkspaceBundle(
                        warmSnap, policy, true, configuredTtl, warmExpires, !completedDaily,
                        TwitterApiIoProvider.pendingRelationshipChecks(stored)).withDailyCacheStatus(dailyCacheStatus(stored));
                putSnapshotCache(cacheKey, warmSnap, warmBundle, configuredTtl, warmExpires, algoFp);
                return refreshWorkspaceTimestamps(warmBundle, true, configuredTtl, warmExpires, policy);
            }
            if (preferStored && !forceRefresh && !backgroundExecution && !continueRelations
                    && resolvedMode != ProviderMode.DEMO && stored != null
                    && !freshDaily && !exhaustedDailyAttempts
                    && !(coalesced || recentBgResult || justComputedSnapshot || recentDegradedCooldown)) {
                return serveStoredAndScheduleRefresh(
                        cacheKey, rawHandle, handleKey, resolvedPeriod, requestedProvider, clientIp, expectedAccountId,
                        stored, active, completedDaily, quotaEntry, zone, today, initialRank, configuredTtl, now, algoFp,
                        "已返回最近一次完整入库快照；后台刷新已受理。");
            }
            if (resolvedMode == ProviderMode.DEMO && cacheValid && !forceRefresh && initialLoadedToday) {
                AccessPolicy policy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
                MultiPeriodDashboardBundle demoBundle = buildWorkspaceBundle(active.snapshot(), policy, true,
                        active.ttlSeconds(), active.expiresAt(), false, 0);
                return refreshWorkspaceTimestamps(demoBundle, true, active.ttlSeconds(), active.expiresAt(), policy);
            }

            if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                String priorOfficialDate = stored != null && stored.syncedAtInstant() != null
                        ? stored.syncedAtInstant().atZone(zone).toLocalDate().toString()
                        : null;
                AccountStore.StoredAccount officialStored = null;
                RawAccountSnapshot snap = switch (resolvedMode) {
                    case X_OFFICIAL_V2 -> {
                        boolean officialOwner = isAuthorIdentity(handleKey, expectedAccountId);
                        if (!officialOwner && paidCapabilityGate != null) {
                            throw new XDataProviderException(403, com.xops.hub.provider.PaidCapabilityGate.PAID_FORBIDDEN_MESSAGE);
                        }
                        checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
                        RawAccountSnapshot fetched = officialOwner
                                ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                        com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, expectedAccountId,
                                        () -> officialXApiV2Provider.fetchSnapshot(handle, null))
                                : officialXApiV2Provider.fetchSnapshot(handle, null);
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
                        AccountStore.StoredAccount provenBase = stored != null
                                ? AccountStore.withCoverage(stored, List.of(), List.of()) : null;
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
                                new AccountStore.GraphUpdate(fg, fr, back, fetched.followersTruncated(), fetched.followingsTruncated(), false, false)
                        );
                        boolean full = streamCoversDaily(tweetProof, syncAt.getEpochSecond())
                                && streamCoversDaily(inboundProof, syncAt.getEpochSecond())
                                && !fetched.degradedPartial();
                        mergedOfficial = AccountStore.withDailyRefreshState(mergedOfficial,
                                syncAt.getEpochSecond(), full ? syncAt.getEpochSecond() : 0);
                        officialStored = mergedOfficial;
                        saveStore(handleKey, expectedAccountId, mergedOfficial);
                        yield TwitterApiIoProvider.snapshotOf(mergedOfficial, fetched.bannerNotice(), fetched.degradedPartial());
                    }
                    case DEMO -> demoSnapshotForAccount(handle, expectedAccountId);
                    default -> throw new XDataProviderException(500, "不支持的数据源模式");
                };
                verifyAccountOwner(snap.profile(), handleKey, expectedAccountId);
                boolean complete = officialStored == null || isDailyRefreshComplete(officialStored);
                Instant expiresAt = officialStored != null ? dailyRefreshExpiresAt(officialStored, now)
                        : now.plusSeconds(configuredTtl);
                harvestAuthorProfileFromSnapshot(snap);
                UserUsageEntry updatedUsage = complete
                        ? recordWorkspaceSyncSuccess(ownerKey, today, false, priorOfficialDate) : quotaEntry;
                Integer rankAfter = resolveAuthorInteractionRank(handleKey, snap.profile().id());
                AccessPolicy policy = buildAccessPolicy(handleKey, snap, updatedUsage, zone, today, rankAfter);
                MultiPeriodDashboardBundle bundle = buildWorkspaceBundle(
                        snap, policy, false, configuredTtl, expiresAt, !complete, 0);
                if (configuredTtl > 0) {
                    pruneCacheIfNeeded();
                    putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expiresAt, algoFp);
                }
                return refreshWorkspaceTimestamps(bundle, false, configuredTtl, expiresAt, policy);
            }

            checkLiveSyncRateLimit(handleKey, clientIp, expectedAccountId);
            String priorSyncDate = stored != null && stored.syncedAtInstant() != null
                    ? stored.syncedAtInstant().atZone(zone).toLocalDate().toString()
                    : null;
            // 单次统一只加载最近 24 小时数据，每日合并写入物理存储，并动态保留近 30 天滚动窗口
            boolean lightweightDaily = true;
            int targetSyncHours = ReportPeriod.HOURS_24.windowHours;

            boolean bindPaidScope = authorCaller || !twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null;
            String scopedCaller = authorCaller ? com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME : handleKey;
            if (forceRefresh && twitterApiIoProvider != null) {
                twitterApiIoProvider.resetFreeCircuitForActiveSync();
            }
            TwitterApiIoProvider.SyncOutcome outcome;
            activeForegroundSyncs.incrementAndGet();
            try {
                AccountStore.StoredAccount baseStore = stored;
                boolean staleOwnerFreeHead = authorCaller && stored != null
                        && twitterApiIoProvider.canReadTimelineForFree("")
                        && (now.getEpochSecond() - stored.syncedAt() >= 45L);
                boolean doHeadSync = (authorCaller && forceRefresh) || staleOwnerFreeHead || !canContinueDailyRefresh(stored, now);
                outcome = bindPaidScope
                        ? com.xops.hub.provider.PaidCapabilityGate.withCaller(scopedCaller, expectedAccountId,
                                () -> twitterApiIoProvider.syncStoreByHours(
                                        handle, baseStore, targetSyncHours, doHeadSync, lightweightDaily, null, expectedAccountId))
                        : twitterApiIoProvider.syncStoreByHours(
                                handle, baseStore, targetSyncHours, doHeadSync, lightweightDaily, null, expectedAccountId);
                verifyAccountOwner(outcome.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, outcome.store());
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
                                forceRefresh
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
                            forceRefresh
                    );
                }
                if (stored != null && stored.profile() != null
                        && (ex.getStatusCode() == 503 || ex.getStatusCode() == 502 || ex.getStatusCode() == 504
                        || ex.getStatusCode() == 500 || ex.getStatusCode() == 429)) {
                    log.warn("workspace_refresh refresh_id={} stage=snapshot_fallback result=served status={}",
                            MDC.get("workspaceRefreshId"), ex.getStatusCode());
                    RawAccountSnapshot fallbackSnap = TwitterApiIoProvider.snapshotOf(
                            AccountStore.withoutRelationshipEvidence(stored), "", true);
                    fallbackSnap = withNotice(fallbackSnap,
                            "当前访问高峰排队较多，已直接秒开本地已入库数据（" + stored.tweets().size() + " 条动态 / "
                                    + stored.inbound().size() + " 条互动），未扣减今日刷新次数。");
                    long shortTtl = Math.min(30L, configuredTtl > 0 ? configuredTtl : 30L);
                    Instant shortExpires = dailyRefreshExpiresAt(stored, now);
                    AccessPolicy fallbackPolicy = buildAccessPolicy(handleKey, fallbackSnap, quotaEntry, zone, today, initialRank);
                    MultiPeriodDashboardBundle fallbackBundle = buildWorkspaceBundle(
                            fallbackSnap, fallbackPolicy, true, shortTtl, shortExpires, true,
                            TwitterApiIoProvider.pendingRelationshipChecks(stored))
                            .withDailyCacheStatus(dailyCacheStatus(stored, true));
                    if (configuredTtl > 0) {
                        pruneCacheIfNeeded();
                        putSnapshotCache(cacheKey, fallbackSnap, fallbackBundle, shortTtl, shortExpires, algoFp);
                    }
                    return refreshWorkspaceTimestamps(fallbackBundle, true, shortTtl, shortExpires, fallbackPolicy);
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
                    ? recordWorkspaceSyncSuccess(ownerKey, today, false, priorSyncDate) : quotaEntry;
            AccessPolicy policy = buildAccessPolicy(handleKey, snap, updatedUsage, zone, today, rankAfter);
            snap = withNotice(snap, buildDailyRefreshNotice(outcome.store(), complete));

            Instant expiresAt = dailyRefreshExpiresAt(outcome.store(), now);
            boolean hasMore = !complete;
            long cycleAnchor = outcome.store().dailyRefreshStartedAt();
            boolean continuationBlocked = !complete && cycleAnchor > 0 && twitterApiIoProvider != null
                    && twitterApiIoProvider.isDailyContinuationBlocked(
                            !streamCoversDaily(outcome.store().tweetCoverage(), cycleAnchor),
                            !streamCoversDaily(outcome.store().inboundCoverage(), cycleAnchor),
                            unattemptedDailyMetrics(outcome.store()) > 0);
            MultiPeriodDashboardBundle bundle = buildWorkspaceBundle(
                    snap, policy, false, configuredTtl, expiresAt, hasMore,
                    TwitterApiIoProvider.pendingRelationshipChecks(outcome.store()))
                    .withDailyCacheStatus(dailyCacheStatus(outcome.store(), outcome.dailyMetricBatchFailed()
                            || (outcome.degraded() && !outcome.progressed() && !continuationBlocked)));
            if (configuredTtl > 0) {
                pruneCacheIfNeeded();
                putSnapshotCache(cacheKey, snap, bundle, configuredTtl, expiresAt, algoFp);
            }
            return refreshWorkspaceTimestamps(bundle, false, configuredTtl, expiresAt, policy);
        }
    }

    private MultiPeriodDashboardBundle serveStoredAndScheduleRefresh(
            String cacheKey, String rawHandle, String handleKey, ReportPeriod resolvedPeriod,
            String requestedProvider, String clientIp, String expectedAccountId,
            AccountStore.StoredAccount stored, CachedSnapshot active, boolean completedDaily,
            UserUsageEntry quotaEntry, ZoneId zone, String today, Integer initialRank,
            long configuredTtl, Instant now, String algoFp, String notice
    ) {
        Instant expires = dailyRefreshExpiresAt(stored, now);
        if (active != null && active.bundle() != null && active.snapshot() != null
                && cacheIdentityMatches(active, handleKey, expectedAccountId)
                && Objects.equals(stored.syncedAtInstant(), active.snapshot().fetchedAt())
                && Objects.equals(algoFp, active.algoFingerprint())) {
            AccessPolicy cachedPolicy = buildAccessPolicy(handleKey, active.snapshot(), quotaEntry, zone, today, initialRank);
            scheduleWorkspaceRefresh(cacheKey, rawHandle, resolvedPeriod, requestedProvider,
                    clientIp, expectedAccountId);
            return refreshWorkspaceTimestamps(active.bundle(), true, configuredTtl, expires, cachedPolicy)
                    .withDailyCacheStatus(dailyCacheStatus(stored));
        }
        RawAccountSnapshot persisted = TwitterApiIoProvider.snapshotOf(stored, notice, !completedDaily);
        AccessPolicy policy = buildAccessPolicy(handleKey, persisted, quotaEntry, zone, today, initialRank);
        MultiPeriodDashboardBundle immediate = buildWorkspaceBundle(
                persisted, policy, true, configuredTtl, expires, !completedDaily,
                TwitterApiIoProvider.pendingRelationshipChecks(stored))
                .withDailyCacheStatus(dailyCacheStatus(stored));
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
        AtomicBoolean gate = backgroundRefreshes.computeIfAbsent(key, ignored -> new AtomicBoolean());
        if (!gate.compareAndSet(false, true)) return;
        Instant startedAt = Instant.now();
        long acceptedNanos = System.nanoTime();
        String refreshId = java.util.UUID.randomUUID().toString();
        backgroundRefreshStatuses.put(key, new BackgroundRefreshStatus("RUNNING", startedAt, null, "", refreshId));
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
                backgroundRefreshStatuses.put(key,
                        new BackgroundRefreshStatus("SUCCEEDED", startedAt, Instant.now(), "", refreshId));
                boolean partial = bundle.hasMoreHistoryForTarget()
                        || (bundle.dailyCacheStatus() != null && bundle.dailyCacheStatus().lastAttemptDegraded());
                log.info("workspace_refresh refresh_id={} stage=complete elapsed_ms={} result={}", refreshId,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedNanos), partial ? "partial" : "complete");
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
                AccountStore.StoredAccount stored = AccountStore.pruneToRollingWindow(raw, now);
                String handleKey = validateIsolatedHandleKey(stored.profile().username());
                String accountId = stored.profile().id();
                String ownerKey = accountOwnerKey(handleKey, accountId);
                String cacheKey = ProviderMode.TWITTERAPI_IO.name() + ":" + ownerKey;
                if (snapshotCache.containsKey(cacheKey)) return;
                boolean completedDaily = isDailyRefreshComplete(stored);
                RawAccountSnapshot persisted = TwitterApiIoProvider.snapshotOf(stored, "", !completedDaily);
                ZoneId zone = resolveZoneId();
                String today = LocalDate.now(zone).toString();
                UserUsageEntry quotaEntry = readUsageSnapshot(ownerKey);
                Integer rank = resolveAuthorInteractionRank(handleKey, accountId);
                AccessPolicy policy = buildAccessPolicy(handleKey, persisted, quotaEntry, zone, today, rank);
                long ttl = DAILY_REFRESH_SECONDS;
                Instant expires = dailyRefreshExpiresAt(stored, now);
                MultiPeriodDashboardBundle warm = buildWorkspaceBundle(
                        persisted, policy, true, ttl, expires, !completedDaily,
                        TwitterApiIoProvider.pendingRelationshipChecks(stored))
                        .withDailyCacheStatus(dailyCacheStatus(stored));
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
        String normUser = username == null ? "" : HandleNormalizer.normalize(username);
        for (CachedSnapshot entry : snapshotCache.values()) {
            RawAccountSnapshot snap = entry == null ? null : entry.snapshot();
            if (snap == null || snap.profile() == null || snap.timeline() == null) continue;
            boolean sameOwner = (accountId != null && !accountId.isBlank() && accountId.equals(snap.profile().id()))
                    || (!normUser.isBlank() && normUser.equalsIgnoreCase(snap.profile().username()));
            if (!sameOwner) continue;
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

    private static int pendingDailyMetrics(AccountStore.StoredAccount stored) {
        if (stored == null) return 0;
        long anchor = stored.dailyRefreshStartedAt() > 0 ? stored.dailyRefreshStartedAt() : stored.syncedAt();
        return (int) stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor)
                .filter(TwitterApiIoProvider::isDailyMetricTarget)
                .filter(t -> t.metricsFetchedAt() < anchor || !t.unavailableMetrics().isEmpty()).count();
    }

    private static int unattemptedDailyMetrics(AccountStore.StoredAccount stored) {
        if (stored == null) return 0;
        long anchor = stored.dailyRefreshStartedAt() > 0 ? stored.dailyRefreshStartedAt() : stored.syncedAt();
        return (int) stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor)
                .filter(TwitterApiIoProvider::isDailyMetricTarget)
                .filter(t -> t.metricsAttemptedAt() < anchor).count();
    }

    private boolean dailyCanContinue(AccountStore.StoredAccount stored) {
        if (stored == null || stored.dailyRefreshStartedAt() <= 0) return false;
        long anchor = stored.dailyRefreshStartedAt();
        boolean needTimeline = !streamCoversDaily(stored.tweetCoverage(), anchor);
        boolean needInbound = !streamCoversDaily(stored.inboundCoverage(), anchor);
        boolean needMetrics = unattemptedDailyMetrics(stored) > 0;
        if (!needTimeline && !needInbound && !needMetrics) return false;
        return twitterApiIoProvider == null
                || !twitterApiIoProvider.isDailyContinuationBlocked(needTimeline, needInbound, needMetrics);
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

    private static boolean isDailyRefreshComplete(AccountStore.StoredAccount stored) {
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

    private Instant dailyRefreshExpiresAt(AccountStore.StoredAccount stored, Instant now) {
        if (canContinueDailyRefresh(stored, now) && !dailyCanContinue(stored)) {
            return Instant.ofEpochSecond(stored.dailyRefreshStartedAt() + DAILY_REFRESH_SECONDS);
        }
        return isDailyRefreshComplete(stored)
                ? Instant.ofEpochSecond(stored.dailyRefreshedAt() + DAILY_REFRESH_SECONDS)
                : (stored != null && stored.syncedAtInstant() != null ? stored.syncedAtInstant() : now);
    }

    private DailyCacheStatus dailyCacheStatus(AccountStore.StoredAccount stored) {
        return dailyCacheStatus(stored, false);
    }

    private DailyCacheStatus dailyCacheStatus(AccountStore.StoredAccount stored, boolean lastAttemptDegraded) {
        long anchor = Math.max(stored.dailyRefreshStartedAt(), stored.syncedAt());
        int posts = (int) stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor).count();
        int inbound = (int) stored.inbound().stream()
                .filter(i -> i.createdAt() >= anchor - 86_400L && i.createdAt() <= anchor).count();
        long latestMetricFetchedAt = stored.tweets().stream()
                .filter(t -> t.createdAt() >= anchor - 86_400L && t.createdAt() <= anchor)
                .filter(t -> t.metricsTimeObserved() && t.metricsFetchedAt() > 0 && t.unavailableMetrics().isEmpty())
                .mapToLong(AccountStore.StoredTweet::metricsFetchedAt).max().orElse(0);
        return new DailyCacheStatus(stored.syncedAtInstant(),
                stored.dailyRefreshStartedAt() > 0 ? Instant.ofEpochSecond(stored.dailyRefreshStartedAt()) : null,
                stored.dailyRefreshedAt() > 0 ? Instant.ofEpochSecond(stored.dailyRefreshedAt()) : null,
                streamCoversDaily(stored.tweetCoverage(), anchor), streamCoversDaily(stored.inboundCoverage(), anchor),
                posts, inbound, pendingDailyMetrics(stored), unattemptedDailyMetrics(stored),
                dailyCanContinue(stored), lastAttemptDegraded,
                anchor > 0 ? Instant.ofEpochSecond(AccountStore.contiguousFrom(stored.tweetCoverage(), anchor)) : null,
                anchor > 0 ? Instant.ofEpochSecond(AccountStore.contiguousFrom(stored.inboundCoverage(), anchor)) : null,
                latestMetricFetchedAt > 0 ? Instant.ofEpochSecond(latestMetricFetchedAt) : null);
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
        Path file = accountStoreFile(handleKey, expectedAccountId);
        AccountStore.StoredAccount rawStored = file == null
                ? memoryStores.get(ownerKey) : AccountStore.read(file, objectMapper);
        if (rawStored == null || rawStored.syncedAt() <= 0) {
            throw new XDataProviderException(404, "当前登录账号暂无已入库快照，请先使用正常刷新获取数据");
        }
        verifyAccountOwner(rawStored.profile(), handleKey, expectedAccountId);
        AccountStore.StoredAccount stored = AccountStore.pruneToRollingWindow(rawStored, rawStored.syncedAtInstant());
        RawAccountSnapshot snapshot = TwitterApiIoProvider.snapshotOf(stored,
                "正在查看已入库快照；本次未同步上游，时间窗以原数据同步时间为准。", stored.graphRefreshFailed());
        long ttl = Math.max(0L, properties.getCacheTtlSeconds());
        Instant expiresAt = stored.syncedAtInstant().plusSeconds(ttl);
        ZoneId zone = resolveZoneId();
        String today = LocalDate.now(zone).toString();
        UserUsageEntry usage = readUsageSnapshot(ownerKey);
        UserUsageEntry effectiveUsage = usage;
        int coveredDays = Math.max(0, snapshot.coveredWindowDays());
        if (coveredDays > effectiveUsage.syncedDates().size()) {
            List<String> syntheticDates = new ArrayList<>(effectiveUsage.syncedDates());
            for (int i = syntheticDates.size(); i < coveredDays; i++) {
                syntheticDates.add("stored-day-" + (i + 1));
            }
            effectiveUsage = new UserUsageEntry(
                    effectiveUsage.usageCount(),
                    effectiveUsage.lastModalShownAtCount(),
                    effectiveUsage.optimisticFollowedAt(),
                    effectiveUsage.updatedAt(),
                    effectiveUsage.quotaDate(),
                    effectiveUsage.initialLoadedToday(),
                    effectiveUsage.manualRefreshUsedToday(),
                    syntheticDates
            );
        }
        Integer rank = resolveAuthorInteractionRank(handleKey, expectedAccountId);
        AccessPolicy policy = buildAccessPolicy(handleKey, snapshot, effectiveUsage, zone, today, rank);
        return buildWorkspaceBundle(snapshot, policy, true, ttl, expiresAt, false,
                TwitterApiIoProvider.pendingRelationshipChecks(stored)).withDailyCacheStatus(dailyCacheStatus(stored));
    }

    private ResolvedBundleHolder obtainBundle(
            String rawHandle,
            int requiredDays,
            String requestedProvider,
            String clientIp,
            String expectedAccountId,
            boolean continueRelations
    ) {
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
                                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, expectedAccountId,
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
                            () -> analyzeWorkspaceBundle(handle, ReportPeriod.HOURS_24, requestedProvider, false, clientIp, expectedAccountId, true)
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
                                        com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                                        expectedAccountId,
                                        () -> officialXApiV2Provider.fetchSnapshot(handle, null))
                                : officialXApiV2Provider.fetchSnapshot(handle, null);
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
                        ? com.xops.hub.provider.PaidCapabilityGate.withCaller(callerName, expectedAccountId, syncCall)
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
                                () -> obtainBundle(handle, requiredDays, requestedProvider, clientIp, expectedAccountId, false)
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
                            () -> obtainBundle(handle, requiredDays, requestedProvider, clientIp, expectedAccountId, false)
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
                s.mentionsTailTime(), s.mentionsNextCursor(), s.mentionsExhausted(), s.followingsTruncated()
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
                && expectedHandleKey.equalsIgnoreCase(hit.snapshot().profile().username())
                && (expectedAccountId == null || expectedAccountId.equals(hit.snapshot().profile().id()));
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
        if (!handleKey.equalsIgnoreCase(profile.username())) {
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
        if (accountId == null) return loadStore(handleKey);
        String ownerKey = accountOwnerKey(handleKey, accountId);
        Path file = accountStoreFile(handleKey, accountId);
        AccountStore.StoredAccount stored = file == null ? memoryStores.get(ownerKey) : AccountStore.read(file, objectMapper);
        if (stored != null) {
            verifyAccountOwner(stored.profile(), handleKey, accountId);
            return stored;
        }
        // 旧用户名目录只读校验，身份未确认前不迁移、不删除也不用于增量采集。
        Path legacy = resolveUserStoreFile(handleKey);
        stored = legacy == null ? memoryStores.get(handleKey) : AccountStore.read(legacy, objectMapper);
        if (stored == null) {
            Path flat = legacyFlatStoreFile(handleKey);
            if (flat != null) stored = AccountStore.read(flat, objectMapper);
        }
        if (stored == null && file != null) {
            stored = migrateLegacySnapshot(Path.of(properties.getDiskCacheDir()).resolve("twitterapi_io_v2_" + handleKey + ".json"));
        }
        return stored != null && stored.profile() != null && accountId.equals(stored.profile().id())
                && handleKey.equalsIgnoreCase(stored.profile().username()) ? stored : null;
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
                        AccountStore.write(file, fromFlat, objectMapper);
                        Files.deleteIfExists(legacyFlat);
                    } catch (Exception ignored) {
                    }
                    s = fromFlat;
                }
            }
        }
        if (s == null && file != null) {
            Path rootDir = Path.of(properties.getDiskCacheDir());
            s = migrateLegacySnapshot(rootDir.resolve("twitterapi_io_v2_" + safeKey + ".json"));
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

    /** 一次性迁移旧版整份快照：复用其中已拉到的数据与关注关系，覆盖范围按它真实拉到的最早时间记录 */
    private AccountStore.StoredAccount migrateLegacySnapshot(Path legacy) {
        if (!Files.isRegularFile(legacy)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(Files.readString(legacy, StandardCharsets.UTF_8));
            RawAccountSnapshot old = objectMapper.treeToValue(root.path("snapshot"), RawAccountSnapshot.class);
            if (old == null || old.profile() == null || old.fetchedAt() == null || old.timelineTailTime() == null || old.mentionsTailTime() == null) {
                return null;
            }
            long at = old.fetchedAt().getEpochSecond();
            List<XUserSummary> fg = new java.util.ArrayList<>();
            List<XUserSummary> fr = new java.util.ArrayList<>();
            java.util.Set<String> back = new java.util.HashSet<>();
            for (RelationshipEntry r : old.relationships()) {
                if (r.user() == null) continue;
                if (r.iFollowThem()) fg.add(r.user());
                if (r.theyFollowMe()) fr.add(r.user());
                if (r.iFollowThem() && r.theyFollowMe()) back.add(r.user().username().toLowerCase(Locale.ROOT));
            }
            AccountStore.StoredAccount migrated = AccountStore.merge(
                    null, old.profile(), old.fetchedAt(), old.timeline(), old.inboundInteractions(),
                    List.of(new long[]{old.timelineTailTime().getEpochSecond(), at}),
                    List.of(new long[]{old.mentionsTailTime().getEpochSecond(), at}),
                    new AccountStore.GraphUpdate(fg, fr, back, old.followersTruncated(), old.followingsTruncated(), false, false)
            );
            log.info("Migrated legacy snapshot into compact store for @{} (tweets={}, inbound={})",
                    old.profile().username(), migrated.tweets().size(), migrated.inbound().size());
            return migrated;
        } catch (Exception ex) {
            log.debug("Skipped legacy snapshot migration: {}", ex.getMessage());
            return null;
        }
    }

    private void saveStore(String handleKey, String accountId, AccountStore.StoredAccount store) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        String ownerKey = accountOwnerKey(safeKey, accountId);
        verifyAccountOwner(store != null ? store.profile() : null, safeKey, accountId);
        if (!isOwnedByAccount(store, safeKey)) {
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
            memoryStores.put(ownerKey, store);
            return;
        }
        // 已启用磁盘落盘时不再向 memoryStores 常驻重复副本，避免成千上万历史用户累积占内存
        memoryStores.remove(ownerKey);
        String refreshId = MDC.get("workspaceRefreshId");
        long writeStarted = System.nanoTime();
        try {
            AccountStore.write(file, store, objectMapper);
            if (AUTHOR_USERNAME.equalsIgnoreCase(safeKey)) {
                this.cachedAuthorStoreFile = file;
            }
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

            String storySuffix = "\n\n" + AUTHOR_COST_STORY + "\n开源仓库（可本地部署，与线上一致）：" + AUTHOR_REPO_URL;
            if (newCount == 3 && prev.lastModalShownAtCount() != 3) {
                shouldShow = true;
                reasonCode = "THIRD_USE_INTRO";
                badgeText = "第 3 次使用 · 作者说明";
                modalTitle = "你好，我是作者 @elidlicom";
                modalMessage = ((followingAuthor || selfIsAuthor)
                        ? "这是你第 3 次使用本工作台！因为每次使用这个网站服务，我都是真实付费调用 API 的。感谢你已经关注 @elidlicom 结下这份善缘，后续更新都会第一时间在 X 分享！"
                        : "这是你第 3 次使用本工作台！因为每次使用这个网站服务，我都是真实付费调用 API 为你拉取数据的。免费开放给大家用，结个善缘，求关注 @elidlicom！")
                        + storySuffix;
            } else if (newCount >= 10 && newCount % 10 == 0 && !followingAuthor && !selfIsAuthor
                    && prev.lastModalShownAtCount() != newCount) {
                shouldShow = true;
                reasonCode = "EVERY_10_REMINDER";
                badgeText = "第 " + newCount + " 次使用 · 结个善缘";
                modalTitle = "已陪伴你使用 " + newCount + " 次";
                modalMessage = "因为每次使用这个网站服务，我都是真实付费调用 API 的。如果这个工作台对你有帮助，结个善缘，求关注 @elidlicom！关注后将不再弹出此提示。"
                        + storySuffix;
            }

            UserUsageEntry updated = new UserUsageEntry(
                    newCount,
                    shouldShow ? newCount : prev.lastModalShownAtCount(),
                    prev.optimisticFollowedAt(),
                    Instant.now(),
                    prev.quotaDate(),
                    prev.initialLoadedToday(),
                    prev.manualRefreshUsedToday(),
                    prev.syncedDates()
            );
            saveUserUsage(ownerKey, updated);
        }

        XUserSummary authorSummary = resolveAuthorProfile(shouldShow);
        return new UsageCheckinResponse(
                handle,
                newCount,
                shouldShow,
                reasonCode,
                badgeText,
                modalTitle,
                modalMessage,
                followingAuthor,
                selfIsAuthor,
                authorSummary,
                AUTHOR_PROFILE_URL,
                AUTHOR_FOLLOW_INTENT_URL
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
        XUserSummary authorSummary = resolveAuthorProfile(true);
        String badgeText = "第 " + currentCount + " 次使用 · 作者说明";
        String modalTitle = "你好，我是作者 @elidlicom";
        String modalMessage = ((followingAuthor && !selfIsAuthor)
                ? "因为每次使用这个网站服务，我都是真实付费调用 API 的。感谢你已经关注 @elidlicom 结下这份善缘，后续更新都会第一时间在 X 分享！"
                : "因为每次使用这个网站服务，我都是真实付费调用 API 为大家拉取数据的。免费开放使用，结个善缘，求关注 @elidlicom！")
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
                AUTHOR_PROFILE_URL,
                AUTHOR_FOLLOW_INTENT_URL
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
        if (accountId == null || accountId.isBlank()) return false;
        String handleKey = null;
        if (rawUsername != null && !rawUsername.isBlank()) {
            try {
                handleKey = validateIsolatedHandleKey(HandleNormalizer.normalize(rawUsername));
            } catch (RuntimeException ignored) {
                handleKey = null;
            }
        }
        return isAuthorIdentity(handleKey, accountId);
    }

    /** Owner benefits require the verified OAuth id to match previously observed author identity (or bind first @elidlicom login). */
    private boolean isAuthorIdentity(String handleKey, String accountId) {
        if (accountId == null || accountId.isBlank()) return false;
        if (assistantStore != null) {
            try {
                if (assistantStore.isOwner(accountId)) return true;
                if (assistantStore.hasOwnerBinding()) return false;
            } catch (RuntimeException ex) {
                log.warn("Content-assistant owner binding unavailable during access check");
            }
        }
        AccountStore.StoredAccount author = findAuthorStoredAccount();
        if (author != null && author.profile() != null && author.profile().id() != null && !author.profile().id().isBlank()) {
            boolean match = accountId.equals(author.profile().id());
            if (match && assistantStore != null) {
                bindAssistantOwnerQuietly(accountId, AUTHOR_USERNAME);
            }
            return match;
        }
        CachedAuthorProfile cached = cachedAuthorProfile;
        if (cached != null && cached.profile() != null && cached.profile().id() != null && !cached.profile().id().isBlank()
                && AUTHOR_USERNAME.equalsIgnoreCase(cached.profile().username())) {
            boolean match = accountId.equals(cached.profile().id());
            if (match && assistantStore != null) {
                bindAssistantOwnerQuietly(accountId, AUTHOR_USERNAME);
            }
            return match;
        }
        if (handleKey != null && AUTHOR_USERNAME.equalsIgnoreCase(handleKey)) {
            if (assistantStore != null) {
                return bindAssistantOwnerQuietly(accountId, AUTHOR_USERNAME);
            }
            return true;
        }
        return false;
    }

    private boolean bindAssistantOwnerQuietly(String accountId, String username) {
        if (assistantStore == null || accountId == null || accountId.isBlank()) return false;
        try {
            return assistantStore.authorize(new com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser(
                    accountId, username, username, "", true, Instant.now()));
        } catch (RuntimeException ex) {
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
            for (RelationshipEntry r : snap.relationships()) {
                if (r != null && r.user() != null && AUTHOR_USERNAME.equalsIgnoreCase(r.user().username())) {
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
        if (ranks != null) {
            if (expectedAccountId != null && !expectedAccountId.isBlank()
                    && ranks.followerIds().contains(expectedAccountId.trim())) {
                return true;
            }
            if (handleKey != null && !handleKey.isBlank()
                    && ranks.followerUsernames().contains(handleKey.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        if (exactMem != null && exactMem.snapshot() != null && exactMem.snapshot().profile() != null
                && safeKey.equalsIgnoreCase(exactMem.snapshot().profile().username())
                && (expectedAccountId == null || expectedAccountId.equals(exactMem.snapshot().profile().id()))) {
            return exactMem.snapshot();
        }
        for (Map.Entry<String, CachedSnapshot> entry : snapshotCache.entrySet()) {
            if (entry.getKey().endsWith(":" + ownerKey)
                    && entry.getValue().snapshot() != null
                    && entry.getValue().snapshot().profile() != null
                    && safeKey.equalsIgnoreCase(entry.getValue().snapshot().profile().username())
                    && (expectedAccountId == null || expectedAccountId.equals(entry.getValue().snapshot().profile().id()))) {
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
        if (snap.profile() != null && AUTHOR_USERNAME.equalsIgnoreCase(snap.profile().username())) {
            cacheAuthorProfile(snap.profile());
            return;
        }
        if (snap.relationships() != null) {
            for (RelationshipEntry r : snap.relationships()) {
                if (r != null && r.user() != null && AUTHOR_USERNAME.equalsIgnoreCase(r.user().username())) {
                    cacheAuthorProfile(r.user());
                    return;
                }
            }
        }
    }

    private XUserSummary resolveAuthorProfile(boolean allowLiveLookupIfMissing) {
        Instant now = Instant.now();
        CachedAuthorProfile mem = this.cachedAuthorProfile;
        if (mem != null && mem.profile() != null && now.isBefore(mem.expiresAt())) {
            return withCanonicalAuthorIdentity(mem.profile());
        }
        CachedAuthorProfile disk = readDiskAuthorProfile();
        if (disk != null && disk.profile() != null && now.isBefore(disk.expiresAt())) {
            this.cachedAuthorProfile = disk;
            return withCanonicalAuthorIdentity(disk.profile());
        }
        if (allowLiveLookupIfMissing && System.currentTimeMillis() >= authorLiveLookupCooldownUntilMs) {
            synchronized (authorLookupLock) {
                Instant recheckNow = Instant.now();
                CachedAuthorProfile recheckMem = this.cachedAuthorProfile;
                if (recheckMem != null && recheckMem.profile() != null && recheckNow.isBefore(recheckMem.expiresAt())) {
                    return withCanonicalAuthorIdentity(recheckMem.profile());
                }
                if (System.currentTimeMillis() >= authorLiveLookupCooldownUntilMs) {
                    XUserSummary live = twitterApiIoProvider.fetchPublicUserSummaryQuietly(AUTHOR_USERNAME);
                    if (live != null) {
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
        if (raw == null) {
            return new XUserSummary(
                    "",
                    AUTHOR_USERNAME,
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
                AUTHOR_USERNAME,
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
            String ownerKey,
            String today,
            boolean incrementManualRefresh,
            String legacyPriorDate
    ) {
        synchronized (usageLockFor(ownerKey)) {
            UserUsageEntry prev = loadUserUsage(ownerKey);
            boolean sameDay = today.equals(prev.quotaDate());
            int nextManualUsed = sameDay ? prev.manualRefreshUsedToday() : 0;
            if (incrementManualRefresh) {
                nextManualUsed++;
            }
            List<String> mergedDates = mergeSyncedDates(prev.syncedDates(), legacyPriorDate, today);
            UserUsageEntry next = new UserUsageEntry(
                    prev.usageCount(),
                    prev.lastModalShownAtCount(),
                    prev.optimisticFollowedAt(),
                    Instant.now(),
                    today,
                    true,
                    nextManualUsed,
                    mergedDates
            );
            saveUserUsage(ownerKey, next);
            return next;
        }
    }

    private UserUsageEntry recordAccumulatedDateOnly(String ownerKey, String today, String legacyPriorDate) {
        synchronized (usageLockFor(ownerKey)) {
            UserUsageEntry prev = loadUserUsage(ownerKey);
            List<String> mergedDates = mergeSyncedDates(prev.syncedDates(), legacyPriorDate, today);
            if (mergedDates.equals(prev.syncedDates())) {
                return prev;
            }
            UserUsageEntry next = new UserUsageEntry(
                    prev.usageCount(),
                    prev.lastModalShownAtCount(),
                    prev.optimisticFollowedAt(),
                    Instant.now(),
                    prev.quotaDate(),
                    prev.initialLoadedToday(),
                    prev.manualRefreshUsedToday(),
                    mergedDates
            );
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
        boolean top50AutoSync = selfIsAuthor || (authorRank != null && authorRank <= TOP_AUTO_SYNC_RANK);
        ScheduledPublishAccess schedAccess = computeScheduledPublishAccess(selfIsAuthor, followsAuthor, authorRank);

        String minRollingDate = today != null && today.trim().matches("\\d{4}-\\d{2}-\\d{2}")
                ? LocalDate.parse(today.trim()).minusDays(30).toString()
                : null;
        int accumulatedDays = 0;
        if (usage != null && usage.syncedDates() != null) {
            for (String d : usage.syncedDates()) {
                if (d == null || d.isBlank()) continue;
                String trimmed = d.trim();
                if (minRollingDate != null && trimmed.matches("\\d{4}-\\d{2}-\\d{2}") && trimmed.compareTo(minRollingDate) < 0) {
                    continue;
                }
                accumulatedDays++;
            }
            accumulatedDays = Math.min(MAX_SYNCED_DATES, accumulatedDays);
        }
        if (accumulatedDays == 0 && snapshot != null && snapshot.fetchedAt() != null) {
            accumulatedDays = 1;
        }

        List<String> unlocked = new ArrayList<>();
        Map<String, String> lockedReasons = new LinkedHashMap<>();
        unlocked.add(ReportPeriod.HOURS_24.code);

        if (top5Full || accumulatedDays >= 2) {
            unlocked.add(ReportPeriod.HOURS_48.code);
        } else {
            lockedReasons.put(ReportPeriod.HOURS_48.code,
                    String.format("需累计满 2 天物理存储数据后解锁 48 小时窗口（当前 %d/2 天，单次加载 24 小时、每日落盘累计）", accumulatedDays));
        }

        if (top5Full || accumulatedDays >= 7) {
            unlocked.add(ReportPeriod.WEEK.code);
        } else {
            lockedReasons.put(ReportPeriod.WEEK.code,
                    String.format("需累计满 7 天物理存储数据后解锁 7 天窗口（当前 %d/7 天，单次加载 24 小时、每日落盘累计）", accumulatedDays));
        }

        if (top5Full || accumulatedDays >= 30) {
            unlocked.add(ReportPeriod.MONTH.code);
        } else {
            lockedReasons.put(ReportPeriod.MONTH.code,
                    String.format("需累计满 30 天物理存储数据后解锁 30 天窗口（当前 %d/30 天，动态保留近 30 天窗口数据）", accumulatedDays));
        }

        boolean sameDay = usage != null && today.equals(usage.quotaDate());
        int used = sameDay ? usage.manualRefreshUsedToday() : 0;
        // The owner has no product-level daily refresh quota. Technical concurrency, deadline and
        // upstream budget protection still apply to every account, including the owner.
        int limit = selfIsAuthor ? Integer.MAX_VALUE : DAILY_MANUAL_REFRESH_LIMIT;
        int remaining = Math.max(0, limit - used);
        boolean accumulatedSummaryMode = !top5Full;

        String accumulationNote = top5Full
                ? (selfIsAuthor
                        ? "作者账号已解锁全部时间维度；本人账号与付费数据全量持久化入库。"
                        : String.format("你是 @%s 互动前 %d 好朋友（第 %d 名），已解锁全部时间维度；单次加载 24 小时并每日物理落盘，动态保留近 30 天窗口数据。",
                                AUTHOR_USERNAME, TOP_FREE_FULL_RANK, authorRank))
                : (top50AutoSync
                        ? String.format("你是 @%s 互动前 %d（第 %d 名），系统每日自动同步最近 24 小时数据并物理落盘；当前滚动窗口已累计 %d/30 天。",
                                AUTHOR_USERNAME, TOP_AUTO_SYNC_RANK, authorRank, accumulatedDays)
                        : String.format("当前滚动窗口已累计 %d/30 天：单次仅加载 24 小时，每日物理落盘累计，动态保留近 30 天数据（与 @%s 互动前 50 享每日自动同步）。",
                                accumulatedDays, AUTHOR_USERNAME));

        return new AccessPolicy(
                accumulatedDays,
                followsAuthor,
                AUTHOR_USERNAME,
                authorRank,
                top5Full,
                top50AutoSync,
                List.copyOf(unlocked),
                Map.copyOf(lockedReasons),
                limit,
                used,
                remaining,
                today,
                accumulatedSummaryMode,
                accumulationNote,
                AUTHOR_COST_STORY,
                AUTHOR_REPO_URL,
                schedAccess.allowed(),
                schedAccess.reason()
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
                "定时发布付费能力仅对 @" + AUTHOR_USERNAME + " 开放使用。"
        );
    }

    private int computeStoredCoveredDays(AccountStore.StoredAccount stored) {
        if (stored == null || stored.syncedAtInstant() == null) return 0;
        return TwitterApiIoProvider.snapshotOf(stored, "", false).coveredWindowDays();
    }

    private String buildAccumulationBannerNotice(
            AccountStore.StoredAccount st,
            RawAccountSnapshot snap,
            boolean top5Full,
            Integer rank
    ) {
        if (top5Full) {
            String extra = rank != null && rank > 0
                    ? String.format("（@%s 互动第 %d 名·已解锁全时间窗口）", AUTHOR_USERNAME, rank)
                    : "";
            return String.format(
                    "单次加载 24 小时并已完成物理落盘（当前滚动保留近 30 天共 %d 条动态、%d 条互动）%s",
                    st.tweets().size(), st.inbound().size(), extra);
        }
        String rankInfo = (rank != null && rank <= TOP_AUTO_SYNC_RANK)
                ? String.format("你位列 @%s 互动前 %d（第 %d 名），每日自动同步最近 24 小时并落盘；", AUTHOR_USERNAME, TOP_AUTO_SYNC_RANK, rank)
                : "";
        return String.format(
                "每日物理存储模式：%d 条动态、%d 条互动已落盘。%s单次仅加载 24 小时，动态保留近 30 天窗口数据。",
                st.tweets().size(), st.inbound().size(), rankInfo);
    }

    public Integer resolveAuthorInteractionRank(String handleKey, String accountId) {
        if (isAuthorIdentity(handleKey, accountId)) {
            return 0;
        }
        CachedAuthorRanks ranks = getOrComputeAuthorRanks();
        if (ranks == null) {
            return null;
        }
        if (accountId != null && !accountId.isBlank()) {
            Integer byId = ranks.rankById().get(accountId);
            if (byId != null) return byId;
        }
        return handleKey != null ? ranks.rankByUsername().get(handleKey.toLowerCase(Locale.ROOT)) : null;
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
        AccountStore.StoredAccount authorStore = null;
        if (assistantStore != null) {
            var owner = assistantStore.tx(tx -> tx.get("meta", "owner"));
            if (owner != null && !owner.path("accountId").asText("").isBlank()) {
                String accountId = owner.path("accountId").asText();
                String username = owner.path("username").asText(AUTHOR_USERNAME).toLowerCase(Locale.ROOT);
                Path file = accountStoreFile(username, accountId);
                authorStore = file == null ? memoryStores.get(accountOwnerKey(username, accountId))
                        : AccountStore.read(file, objectMapper);
                if (authorStore == null) authorStore = loadStore(username);
                if (authorStore == null || authorStore.profile() == null || !accountId.equals(authorStore.profile().id())) {
                    return List.of();
                }
            }
        }
        if (authorStore == null) {
            authorStore = findAuthorStoredAccount();
        }
        if (authorStore == null || authorStore.profile() == null
                || authorStore.profile().id() == null || authorStore.profile().id().isBlank()
                || !com.xops.hub.provider.PaidCapabilityGate.isPrivilegedUsername(authorStore.profile().username())) {
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
                log.debug("Silent top-10 history warmup skipped: {}", ex.getMessage());
            } finally {
                silentHistoryRunning.set(false);
            }
        });
    }

    /**
     * 执行单步低优静默历史缓存（覆盖 @elidlicom 本人及互动前 10 账号）：
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
        String silentCaller = chosenOwner ? com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME : handleKey;
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
                ? com.xops.hub.provider.PaidCapabilityGate.withCaller(silentCaller, uid, silentCall)
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
                        }
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
            AccountStore.StoredAccount merged = AccountStore.mergeStoredSlice(latest, outcome.store());
            verifyAccountOwner(merged.profile(), handleKey, uid);
            saveStore(handleKey, uid, merged);
            if (AUTHOR_USERNAME.equalsIgnoreCase(merged.profile().username())) {
                cacheAuthorProfile(merged.profile());
            }
            CachedSnapshot cached = snapshotCache.get(cacheKey);
            if (cached != null && cacheIdentityMatches(cached, handleKey, uid)) {
                boolean completedDaily = isDailyRefreshComplete(merged);
                Instant expires = dailyRefreshExpiresAt(merged, Instant.now());
                RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(merged, "", !completedDaily);
                snap = withNotice(snap, buildDailyRefreshNotice(merged, completedDaily));
                UserUsageEntry usage = readUsageSnapshot(ownerKey);
                Integer rank = resolveAuthorInteractionRank(handleKey, uid);
                AccessPolicy policy = buildAccessPolicy(handleKey, snap, usage, zone, today, rank);
                MultiPeriodDashboardBundle updatedBundle = buildWorkspaceBundle(
                        snap, policy, true, DAILY_REFRESH_SECONDS, expires, !completedDaily,
                        TwitterApiIoProvider.pendingRelationshipChecks(merged))
                        .withDailyCacheStatus(dailyCacheStatus(merged));
                putSnapshotCache(cacheKey, snap, updatedBundle, DAILY_REFRESH_SECONDS, expires, algoFp);
            }
        }
        return true;
    }

    private static boolean needsSilent30DayBackfill(AccountStore.StoredAccount stored, Instant now) {
        if (stored == null || stored.profile() == null || stored.syncedAtInstant() == null) {
            return true;
        }
        boolean ownerFullHistory = AccountStore.retainsFullHistory(stored.profile().username());
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
        if (existing != null && existing.authorSyncedAt() == authorStore.syncedAt()) {
            return existing;
        }
        RawAccountSnapshot snap = TwitterApiIoProvider.snapshotOf(authorStore, "", false);
        CommunityDashboardReport monthReport = analyticsEngine.analyze(
                snap, ReportPeriod.MONTH, true, resolveZoneId(), true, 0L, snap.fetchedAt());
        List<PeerInteractionStat> ranked = new ArrayList<>(monthReport.interactions().peerStats());
        ranked.sort(PEER_RANK_ORDER);

        Map<String, Integer> byId = new LinkedHashMap<>();
        Map<String, Integer> byUsername = new LinkedHashMap<>();
        int rank = 1;
        for (PeerInteractionStat p : ranked) {
            if (p.peer() == null) continue;
            String uname = p.peer().username() != null ? p.peer().username().trim().toLowerCase(Locale.ROOT) : "";
            String uid = p.peer().id() != null ? p.peer().id().trim() : "";
            if (!uid.isEmpty()) {
                byId.putIfAbsent(uid, rank);
            }
            if (!uname.isEmpty()) {
                byUsername.putIfAbsent(uname, rank);
            }
            rank++;
        }
        Set<String> followerIds = new LinkedHashSet<>();
        Set<String> followerUsernames = new LinkedHashSet<>();
        if (snap.relationships() != null) {
            for (RelationshipEntry r : snap.relationships()) {
                if (r != null && r.user() != null && r.theyFollowMe()) {
                    String uid = r.user().id() != null ? r.user().id().trim() : "";
                    String uname = r.user().username() != null ? r.user().username().trim().toLowerCase(Locale.ROOT) : "";
                    if (!uid.isEmpty()) {
                        followerIds.add(uid);
                    }
                    if (!uname.isEmpty()) {
                        followerUsernames.add(uname);
                    }
                }
            }
        }
        CachedAuthorRanks computed = new CachedAuthorRanks(
                authorStore.syncedAt(),
                Map.copyOf(byId),
                Map.copyOf(byUsername),
                Set.copyOf(followerIds),
                Set.copyOf(followerUsernames)
        );
        this.cachedAuthorRanks = computed;
        return computed;
    }

    private AccountStore.StoredAccount findAuthorStoredAccount() {
        String dir = properties.getDiskCacheDir();
        if (dir != null && !dir.isBlank()) {
            Path known = this.cachedAuthorStoreFile;
            if (known != null && Files.isRegularFile(known)) {
                AccountStore.StoredAccount hit = AccountStore.read(known, objectMapper);
                if (isOwnedByAccount(hit, AUTHOR_USERNAME)) {
                    return hit;
                }
                this.cachedAuthorStoreFile = null;
            }
            Path byIdRoot = Path.of(dir).resolve("users").resolve("by-id");
            if (Files.isDirectory(byIdRoot)) {
                AccountStore.StoredAccount best = null;
                Path bestFile = null;
                try (Stream<Path> dirs = Files.list(byIdRoot)) {
                    for (Path p : dirs.toList()) {
                        Path storeFile = p.resolve("store_v" + AccountStore.VERSION + ".json.gz");
                        if (!Files.isRegularFile(storeFile)) continue;
                        AccountStore.StoredAccount candidate = AccountStore.read(storeFile, objectMapper);
                        if (isOwnedByAccount(candidate, AUTHOR_USERNAME)) {
                            if (best == null || candidate.syncedAt() > best.syncedAt()) {
                                best = candidate;
                                bestFile = storeFile;
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Failed scanning by-id stores for @{}: {}", AUTHOR_USERNAME, ex.getMessage());
                }
                if (best != null) {
                    this.cachedAuthorStoreFile = bestFile;
                    return best;
                }
            }
            AccountStore.StoredAccount legacy = loadStore(AUTHOR_USERNAME);
            if (legacy != null) return legacy;
        }
        for (AccountStore.StoredAccount mem : memoryStores.values()) {
            if (isOwnedByAccount(mem, AUTHOR_USERNAME)) {
                return mem;
            }
        }
        return null;
    }

    /**
     * 每日定时为与 @elidlicom 互动排名前 50、且已登录落盘存有个人快照的活跃用户同步一次最新 24h 数据。
     * 未曾登录或未落盘的用户不会纳入定时同步，严控上游 API 开销。
     */
    @Scheduled(cron = "${xops.top50-auto-sync-cron:0 20 23 * * *}", zone = "${xops.timezone:Asia/Shanghai}")
    public void scheduledSyncTop50ActivePeers() {
        if (!properties.isTop50AutoSyncEnabled()) {
            return;
        }
        runTop50AutoSyncNow();
    }

    public int runTop50AutoSyncNow() {
        if (resolveProviderMode(null) != ProviderMode.TWITTERAPI_IO || !canUsePublicOrPaidProvider()) {
            return 0;
        }
        if (!top50AutoSyncRunning.compareAndSet(false, true)) {
            return 0;
        }
        int syncedCount = 0;
        try {
            CachedAuthorRanks ranks = getOrComputeAuthorRanks();
            if (ranks == null) {
                return 0;
            }
            List<AccountStore.StoredAccount> registeredStores = listPersistedStores(ranks);
            ZoneId zone = resolveZoneId();
            String today = LocalDate.now(zone).toString();
            for (AccountStore.StoredAccount st : registeredStores) {
                if (st == null || st.profile() == null || st.profile().username() == null) {
                    continue;
                }
                String uname = st.profile().username().trim();
                String uid = st.profile().id() != null ? st.profile().id().trim() : null;
                String handleKey = validateIsolatedHandleKey(uname);
                if (AUTHOR_USERNAME.equalsIgnoreCase(handleKey)) {
                    continue;
                }
                Integer rank = uid != null && !uid.isEmpty() ? ranks.rankById().get(uid) : null;
                if (rank == null) {
                    rank = ranks.rankByUsername().get(handleKey);
                }
                if (rank == null || rank > TOP_AUTO_SYNC_RANK) {
                    continue;
                }
                String ownerKey = accountOwnerKey(handleKey, uid);
                String lockKey = ProviderMode.TWITTERAPI_IO.name() + ":" + ownerKey;
                Object lock = fetchLockFor(ownerKey);
                try {
                    synchronized (lock) {
                        AccountStore.StoredAccount latest = loadStore(handleKey, uid);
                        AccountStore.StoredAccount base = latest != null ? latest : st;
                        Instant checkedAt = Instant.now();
                        if (isDailyRefreshComplete(base)
                                && checkedAt.getEpochSecond() < base.dailyRefreshedAt() + DAILY_REFRESH_SECONDS) {
                            continue;
                        }
                        String priorDate = base.syncedAtInstant() != null
                                ? base.syncedAtInstant().atZone(zone).toLocalDate().toString()
                                : null;
                        boolean doHead = !canContinueDailyRefresh(base, checkedAt);
                        boolean bindTop50Scope = !twitterApiIoProvider.isUsingLoopbackBaseUrl() || paidCapabilityGate != null;
                        var outcome = bindTop50Scope
                                ? com.xops.hub.provider.PaidCapabilityGate.withCaller(
                                        handleKey, uid,
                                        () -> twitterApiIoProvider.syncStoreByHours(uname, base, 24, doHead, true, null, uid))
                                : twitterApiIoProvider.syncStoreByHours(uname, base, 24, doHead, true, null, uid);
                        verifyAccountOwner(outcome.store().profile(), handleKey, uid);
                        saveStore(handleKey, uid, outcome.store());
                        snapshotCache.remove(lockKey);
                        String top50RetryKey = "top50-free-sync:" + ownerKey + ":" + today;
                        if (isDailyRefreshComplete(outcome.store())) {
                            recordAccumulatedDateOnly(ownerKey, today, priorDate);
                            syncedCount++;
                            if (paidCapabilityGate != null) {
                                paidCapabilityGate.markSucceeded(top50RetryKey);
                            }
                        } else if (outcome.degraded() && paidCapabilityGate != null && properties.isFxtwitterEnabled()) {
                            paidCapabilityGate.scheduleIdempotentRetry(
                                    top50RetryKey,
                                    "fxtwitter_free_read",
                                    handleKey,
                                    uid,
                                    "前 50 活跃推友每日免费同步存在未完成项",
                                    this::runTop50AutoSyncNow
                            );
                        }
                    }
                } catch (Exception ex) {
                    if (paidCapabilityGate != null && properties.isFxtwitterEnabled()
                            && !(ex instanceof XDataProviderException xde && xde.getStatusCode() == 403)) {
                        paidCapabilityGate.scheduleIdempotentRetry(
                                "top50-free-sync:" + ownerKey + ":" + today,
                                "fxtwitter_free_read",
                                handleKey,
                                uid,
                                ex.getMessage(),
                                this::runTop50AutoSyncNow
                        );
                    }
                    log.warn("Top-50 daily auto-sync failed for @{} (rank={}): {}", uname, rank, ex.getMessage());
                }
            }
            if (syncedCount > 0) {
                log.info("Top-50 daily auto-sync completed for {} registered peer account(s).", syncedCount);
            }
            return syncedCount;
        } finally {
            top50AutoSyncRunning.set(false);
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
                        if (st != null && st.profile() != null && st.profile().id() != null) {
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
            if (Files.isDirectory(usersRoot)) {
                try (Stream<Path> dirs = Files.list(usersRoot)) {
                    for (Path p : (Iterable<Path>) dirs::iterator) {
                        if ("by-id".equals(p.getFileName().toString())) continue;
                        Path storeFile = p.resolve("store_v" + AccountStore.VERSION + ".json.gz");
                        if (!Files.isRegularFile(storeFile)) continue;
                        AccountStore.StoredAccount st = AccountStore.read(storeFile, objectMapper);
                        if (st != null && st.profile() != null && st.profile().username() != null) {
                            String uid = st.profile().id();
                            String key = (uid != null && !uid.isBlank())
                                    ? "id:" + uid
                                    : st.profile().username().toLowerCase(Locale.ROOT);
                            if (isTopAutoSyncCandidate(st, ranks) && seenOwnerKeys.add(key)) {
                                result.add(st);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Failed listing handle persisted stores: {}", ex.getMessage());
                }
            }
        }
        for (AccountStore.StoredAccount mem : memoryStores.values()) {
            if (mem != null && mem.profile() != null && mem.profile().username() != null) {
                String uid = mem.profile().id();
                String key = (uid != null && !uid.isBlank())
                        ? "id:" + uid
                        : mem.profile().username().toLowerCase(Locale.ROOT);
                if (isTopAutoSyncCandidate(mem, ranks) && seenOwnerKeys.add(key)) {
                    result.add(mem);
                }
            }
        }
        return result;
    }

    private boolean isTopAutoSyncCandidate(AccountStore.StoredAccount store, CachedAuthorRanks ranks) {
        if (store == null || store.profile() == null || store.profile().username() == null) return false;
        String handle = store.profile().username().toLowerCase(Locale.ROOT);
        if (AUTHOR_USERNAME.equals(handle)) return false;
        Integer rank = store.profile().id() != null ? ranks.rankById().get(store.profile().id()) : null;
        if (rank == null) rank = ranks.rankByUsername().get(handle);
        return rank != null && rank <= TOP_AUTO_SYNC_RANK;
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
