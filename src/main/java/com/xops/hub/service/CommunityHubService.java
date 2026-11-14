package com.xops.hub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.AnalyticsReportModels.ServerCapabilities;
import com.xops.hub.model.AnalyticsReportModels.UsageCheckinResponse;
import com.xops.hub.model.RawSnapshotModels.ProviderMode;
import com.xops.hub.model.RawSnapshotModels.RawAccountSnapshot;
import com.xops.hub.model.RawSnapshotModels.RelationshipEntry;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.store.AccountStore;
import com.xops.hub.util.HandleNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CommunityHubService {

    private static final Logger log = LoggerFactory.getLogger(CommunityHubService.class);
    public static final String AUTHOR_USERNAME = "elidlicom";
    private static final String AUTHOR_DISPLAY_NAME = "Litany（李丰一）";
    private static final String AUTHOR_AVATAR_URL = "/author-avatar.webp";
    private static final String AUTHOR_DEFAULT_BIO = "Java Dev | Studying Chinese Metaphysics(Dao·道) ䷀ · 易道心学创始人 · elidli.com";
    private static final String AUTHOR_PROFILE_URL = "https://x.com/elidlicom";
    private static final String AUTHOR_FOLLOW_INTENT_URL = "https://x.com/intent/follow?screen_name=elidlicom";
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final int MAX_MEMORY_STORES = 200;
    /** 按独立认证账号限流：每人独立计数互不连坐（5 分钟已覆盖请求走内存缓存不扣额度） */
    private static final int ACCOUNT_LIVE_SYNC_LIMIT_PER_MINUTE = 30;
    /** 单公网出口 IP 防洪兜底上限：允许同一公司/校园/活动 Wi-Fi 或 NAT 下百人同时正常登录加载零误伤 */
    private static final int IP_FLOOD_GUARD_PER_MINUTE = 600;
    private static final java.util.regex.Pattern SAFE_HANDLE_KEY_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9_]{1,15}$");

    public record UserUsageEntry(
            int usageCount,
            int lastModalShownAtCount,
            Instant optimisticFollowedAt,
            Instant updatedAt
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
            Instant expiresAt
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
    private final CommunityAnalyticsEngine analyticsEngine;

    private final ConcurrentHashMap<String, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> fetchLocks = new ConcurrentHashMap<>();
    /** 仅在未配置磁盘目录时启用的有界内存存储兜底（配置磁盘目录时不在内存保留重复副本） */
    private final ConcurrentHashMap<String, AccountStore.StoredAccount> memoryStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> accountLiveRateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> ipLiveRateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserUsageEntry> usageMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> usageLocks = new ConcurrentHashMap<>();
    private final Object legacyUsageLock = new Object();
    private volatile boolean legacyUsageHydrated = false;
    private volatile CachedAuthorProfile cachedAuthorProfile = null;

    public CommunityHubService(
            XOpsProperties properties,
            DemoXDataProvider demoProvider,
            TwitterApiIoProvider twitterApiIoProvider,
            OfficialXApiV2Provider officialXApiV2Provider,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.demoProvider = demoProvider;
        this.twitterApiIoProvider = twitterApiIoProvider;
        this.officialXApiV2Provider = officialXApiV2Provider;
        this.objectMapper = objectMapper;
        this.analyticsEngine = new CommunityAnalyticsEngine();
    }

    public ServerCapabilities getCapabilities() {
        return new ServerCapabilities(
                properties.hasOAuthClient(),
                properties.isRequireLoginSelfOnly(),
                Math.max(0L, properties.getCacheTtlSeconds()),
                resolveZoneId().getId()
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

    /** 显式查看本人已入库快照；不访问上游、不写盘，也不改变默认刷新缓存。 */
    public MultiPeriodDashboardBundle analyzeStoredAccountBundle(String rawHandle, String expectedAccountId) {
        String handleKey = validateIsolatedHandleKey(HandleNormalizer.normalize(rawHandle));
        if (expectedAccountId == null || expectedAccountId.isBlank()) {
            throw new XDataProviderException(401, "请先使用 X 账号授权登录后查看本人已入库报表");
        }
        String ownerKey = accountOwnerKey(handleKey, expectedAccountId);
        Path file = accountStoreFile(handleKey, expectedAccountId);
        AccountStore.StoredAccount stored = file == null
                ? memoryStores.get(ownerKey) : AccountStore.read(file, objectMapper);
        if (stored == null || stored.syncedAt() <= 0) {
            throw new XDataProviderException(404, "当前登录账号暂无已入库快照，请先使用正常刷新获取数据");
        }
        verifyAccountOwner(stored.profile(), handleKey, expectedAccountId);
        RawAccountSnapshot snapshot = TwitterApiIoProvider.snapshotOf(stored,
                "正在查看已入库快照；本次未同步上游，时间窗以原数据同步时间为准。", stored.graphRefreshFailed());
        long ttl = Math.max(0L, properties.getCacheTtlSeconds());
        Instant expiresAt = stored.syncedAtInstant().plusSeconds(ttl);
        return computeBundleOnce(snapshot, true, ttl, expiresAt, false,
                TwitterApiIoProvider.pendingRelationshipChecks(stored));
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

        if (!continueRelations && configuredTtl > 0) {
            CachedSnapshot memHit = snapshotCache.get(cacheKey);
            if (isValidAccountCacheHit(memHit, handleKey, expectedAccountId, Instant.now())
                    && (resolvedMode != ProviderMode.TWITTERAPI_IO || memHit.snapshot().coveredWindowDays() >= requiredDays)) {
                return new ResolvedBundleHolder(memHit.bundle(), true, memHit.ttlSeconds(), memHit.expiresAt());
            }
        }

        Object lock = fetchLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            CachedSnapshot active = snapshotCache.get(cacheKey);
            boolean cacheValid = configuredTtl > 0 && isValidAccountCacheHit(active, handleKey, expectedAccountId, now);
            if (continueRelations) {
                if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                    throw new XDataProviderException(400, "当前数据源不支持逐人继续核验");
                }
                checkLiveSyncRateLimit(handleKey, clientIp);
                AccountStore.StoredAccount stored = loadStore(handleKey, expectedAccountId);
                if (stored != null) verifyAccountOwner(stored.profile(), handleKey, expectedAccountId);
                var continued = twitterApiIoProvider.continueRelationships(handle, stored, expectedAccountId);
                verifyAccountOwner(continued.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, continued.store());
                RawAccountSnapshot snapshot = TwitterApiIoProvider.snapshotOf(continued.store(),
                        "本次仅继续逐人关系核验；未成功确认的对象仍保留未知。", continued.degraded());
                Instant expires = active != null ? active.expiresAt()
                        : stored.syncedAtInstant().plusSeconds(configuredTtl);
                MultiPeriodDashboardBundle bundle = computeBundleOnce(snapshot, false, configuredTtl, expires, false,
                        TwitterApiIoProvider.pendingRelationshipChecks(continued.store()));
                if (configuredTtl > 0) snapshotCache.put(cacheKey, new CachedSnapshot(snapshot, bundle, configuredTtl, expires));
                return new ResolvedBundleHolder(bundle, false, configuredTtl, expires);
            }
            if (cacheValid && (resolvedMode != ProviderMode.TWITTERAPI_IO || active.snapshot().coveredWindowDays() >= requiredDays)) {
                return new ResolvedBundleHolder(active.bundle(), true, active.ttlSeconds(), active.expiresAt());
            }

            if (resolvedMode != ProviderMode.TWITTERAPI_IO) {
                // 演示 / 官方 API 模式：不做持久化，只做 5 分钟服务端内存缓存
                RawAccountSnapshot snap = switch (resolvedMode) {
                    case X_OFFICIAL_V2 -> {
                        checkLiveSyncRateLimit(handleKey, clientIp);
                        yield officialXApiV2Provider.fetchSnapshot(handle, null);
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
                    snapshotCache.put(cacheKey, new CachedSnapshot(snap, bundle, configuredTtl, expiresAt));
                }
                return new ResolvedBundleHolder(bundle, false, configuredTtl, expiresAt);
            }

            // TwitterAPI.io：以每账号独立隔离的 30 天滚动持久化存储为准，只请求尚未入库的时间段
            checkLiveSyncRateLimit(handleKey, clientIp);
            AccountStore.StoredAccount stored = loadStore(handleKey, expectedAccountId);
            boolean headSync = !cacheValid;
            TwitterApiIoProvider.SyncOutcome outcome;
            try {
                outcome = expectedAccountId == null
                        ? twitterApiIoProvider.syncStore(handle, stored, requiredDays, headSync, null)
                        : twitterApiIoProvider.syncStore(handle, stored, requiredDays, headSync, null, expectedAccountId);
                verifyAccountOwner(outcome.store().profile(), handleKey, expectedAccountId);
                saveStore(handleKey, expectedAccountId, outcome.store());
            } catch (XDataProviderException ex) {
                if (stored != null && stored.profile() != null
                        && (ex.getStatusCode() == 503 || ex.getStatusCode() == 429)) {
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
                        snapshotCache.put(cacheKey, new CachedSnapshot(fallbackSnap, fallbackBundle, shortTtl, shortExpires));
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
                snapshotCache.put(cacheKey, new CachedSnapshot(snap, bundle, configuredTtl, expiresAt));
            }
            return new ResolvedBundleHolder(bundle, false, configuredTtl, expiresAt);
        }
    }

    private RawAccountSnapshot demoSnapshotForAccount(String handle, String expectedAccountId) {
        RawAccountSnapshot s = demoProvider.generateSnapshot(handle, null);
        if (expectedAccountId == null) return s;
        XUserSummary p = s.profile();
        XUserSummary owner = new XUserSummary(expectedAccountId, p.username(), p.displayName(), p.avatarUrl(),
                p.bio(), p.verified(), p.followersCount(), p.followingCount(), p.totalPostsCount(), p.joinedDate());
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
        return hit != null
                && hit.snapshot() != null
                && hit.snapshot().profile() != null
                && expectedHandleKey.equalsIgnoreCase(hit.snapshot().profile().username())
                && (expectedAccountId == null || expectedAccountId.equals(hit.snapshot().profile().id()))
                && now.isBefore(hit.expiresAt());
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
        try {
            AccountStore.write(file, store, objectMapper);
            Path legacyFlat = accountId == null ? legacyFlatStoreFile(safeKey) : null;
            if (legacyFlat != null) {
                Files.deleteIfExists(legacyFlat);
            }
        } catch (Exception ex) {
            log.warn("Failed to persist account store for @{}: {}", safeKey, ex.getMessage());
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
                bundle.pendingRelationshipChecks()
        );
    }

    private CommunityDashboardReport withLiveCacheMeta(
            CommunityDashboardReport r,
            boolean fromCache,
            long ttlSeconds,
            long remainSeconds,
            Instant expiresAt
    ) {
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
            if (cfg == ProviderMode.TWITTERAPI_IO && !properties.hasTwitterApiIoKey())
                throw new XDataProviderException(400, "指定 TwitterAPI.io 数据源但未配置 API Key");
            if (cfg == ProviderMode.X_OFFICIAL_V2 && !properties.hasOfficialBearerToken())
                throw new XDataProviderException(400, "指定 X 官方数据源但未配置 Bearer Token");
            return cfg;
        }
        if (properties.hasTwitterApiIoKey()) {
            return ProviderMode.TWITTERAPI_IO;
        }
        if (properties.hasOfficialBearerToken()) {
            return ProviderMode.X_OFFICIAL_V2;
        }
        return ProviderMode.DEMO;
    }

    /**
     * 限流按账号严格分离，互不连坐、不误伤同网络百人并发：
     * 1. 主限流按归一化账号 handleKey 独立计数（每人 30 次/分钟实时增量批次；5 分钟缓存命中不计入）；
     * 2. 单出口 IP 仅设 600 次/分钟防洪上限，同一公司/校园 Wi-Fi 或反代出口下百名正常登录用户同时访问零误伤。
     */
    private void checkLiveSyncRateLimit(String handleKey, String clientIp) {
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
        if (ipWin.count() > IP_FLOOD_GUARD_PER_MINUTE) {
            throw new XDataProviderException(429, "当前网络出口短时请求过高，请稍等片刻后再试");
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
        if (fetchLocks.size() > MAX_CACHE_ENTRIES * 2) {
            fetchLocks.keySet().removeIf(k -> !snapshotCache.containsKey(k));
        }
        if (usageLocks.size() > MAX_CACHE_ENTRIES * 2) {
            usageLocks.clear();
        }
    }

    private ZoneId resolveZoneId() {
        try {
            return ZoneId.of(properties.getTimezone());
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private Object usageLockFor(String handleKey) {
        if (usageLocks.size() > MAX_CACHE_ENTRIES * 2) {
            usageLocks.clear();
        }
        return usageLocks.computeIfAbsent(handleKey, k -> new Object());
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
        boolean selfIsAuthor = AUTHOR_USERNAME.equalsIgnoreCase(handleKey);

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
                modalTitle = "你好，我是作者 @elidlicom";
                modalMessage = (followingAuthor || selfIsAuthor)
                        ? "这是你第 3 次使用本工作台！因为每次使用这个网站服务，我都是真实付费调用 API 的。感谢你已经关注 @elidlicom 结下这份善缘，后续更新都会第一时间在 X 分享！"
                        : "这是你第 3 次使用本工作台！因为每次使用这个网站服务，我都是真实付费调用 API 为你拉取数据的。免费开放给大家用，结个善缘，求关注 @elidlicom！";
            } else if (newCount >= 10 && newCount % 10 == 0 && !followingAuthor && !selfIsAuthor
                    && prev.lastModalShownAtCount() != newCount) {
                shouldShow = true;
                reasonCode = "EVERY_10_REMINDER";
                badgeText = "第 " + newCount + " 次使用 · 结个善缘";
                modalTitle = "已陪伴你使用 " + newCount + " 次";
                modalMessage = "因为每次使用这个网站服务，我都是真实付费调用 API 的。如果这个工作台对你有帮助，结个善缘，求关注 @elidlicom！关注后将不再弹出此提示。";
            }

            UserUsageEntry updated = new UserUsageEntry(
                    newCount,
                    shouldShow ? newCount : prev.lastModalShownAtCount(),
                    prev.optimisticFollowedAt(),
                    Instant.now()
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
        boolean selfIsAuthor = AUTHOR_USERNAME.equalsIgnoreCase(handleKey);
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
        String modalMessage = (followingAuthor && !selfIsAuthor)
                ? "因为每次使用这个网站服务，我都是真实付费调用 API 的。感谢你已经关注 @elidlicom 结下这份善缘，后续更新都会第一时间在 X 分享！"
                : "因为每次使用这个网站服务，我都是真实付费调用 API 为大家拉取数据的。免费开放使用，结个善缘，求关注 @elidlicom！";
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
        if (AUTHOR_USERNAME.equalsIgnoreCase(handleKey)) return true;
        RawAccountSnapshot snap = findLatestSnapshotForUser(handleKey, expectedAccountId);
        if (snap == null || snap.relationships() == null) return false;
        harvestAuthorProfileFromSnapshot(snap);
        return snap.relationships().stream().anyMatch(r -> r != null && r.user() != null
                && AUTHOR_USERNAME.equalsIgnoreCase(r.user().username()) && r.iFollowThem());
    }

    private RawAccountSnapshot findLatestSnapshotForUser(String handleKey, String expectedAccountId) {
        String safeKey = validateIsolatedHandleKey(handleKey);
        String ownerKey = accountOwnerKey(safeKey, expectedAccountId);
        ProviderMode preferredMode = resolveProviderMode(null);
        CachedSnapshot exactMem = snapshotCache.get(preferredMode.name() + ":" + ownerKey);
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
        if (allowLiveLookupIfMissing) {
            XUserSummary live = twitterApiIoProvider.fetchPublicUserSummaryQuietly(AUTHOR_USERNAME);
            if (live != null) {
                XUserSummary merged = withCanonicalAuthorIdentity(live);
                cacheAuthorProfile(merged);
                return merged;
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
                    ""
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
                raw.joinedDate() != null ? raw.joinedDate() : ""
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
