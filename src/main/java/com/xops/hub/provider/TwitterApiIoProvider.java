package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.store.AccountStore;
import com.xops.hub.store.BlueVerifiedCache;
import com.xops.hub.model.RawSnapshotModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 方案 A 核心数据引擎 —— 对接 TwitterAPI.io 实时公开接口：
 * 1. 所有外部 HTTP 请求统一经由全局有界线程池 {@link SharedApiExecutor} 异步非阻塞调度（杜绝嵌套死锁与无限排队）；
 * 2. 低峰期对大跨度未入库时间段自动按密度切分多路并发拉取，高峰期自动收缩为单路公平交错；
 * 3. 无锁/微锁时间槽限速器（KeyPacer）：槽位预约与 sleep 解耦，多路并发重叠跨境网络 RTT，遇 429 自动降档保护。
 */
@Component
public class TwitterApiIoProvider {

    private static final Logger log = LoggerFactory.getLogger(TwitterApiIoProvider.class);
    private static final DateTimeFormatter TWITTER_DATE_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final Pattern LEADING_MENTION_PATTERN =
            Pattern.compile("^@([A-Za-z0-9_]{1,15})\\b");

    private static final long PAID_INTERVAL_MS = 50L;
    private static final long INITIAL_FETCH_PAID_INTERVAL_MS = PAID_INTERVAL_MS / 2;
    private static final long FREE_TIER_INTERVAL_MS = 5300L;
    private static final int MAX_429_RETRIES = 3;
    private static final long SNAPSHOT_SOFT_DEADLINE_SECONDS = 15L;
    // Stop the free phase before the existing sync deadline so the paid request can be admitted.
    private static final long PAID_FALLBACK_RESERVE_MS = 1500;
    private static final int MAX_STREAM_PAGES_PER_BATCH = 32;

    private static final class KeyPacer {
        long nextAllowedAtMs = 0L;
        long lastBackoffUntilMs = 0L;
        int distinct429Windows = 0;
        volatile boolean freeTierSlowMode = false;
        volatile long intervalMs = PAID_INTERVAL_MS;
        final AtomicInteger activeInitialFetches = new AtomicInteger(0);

        synchronized long reserveWaitMs(boolean isLocalMock) {
            if (isLocalMock && distinct429Windows == 0) {
                return 0L;
            }
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedAtMs);
            long stepMs = (!freeTierSlowMode && distinct429Windows == 0 && activeInitialFetches.get() > 0)
                    ? INITIAL_FETCH_PAID_INTERVAL_MS
                    : intervalMs;
            nextAllowedAtMs = slot + stepMs;
            return slot - now;
        }

        synchronized long reserveWaitMsBefore(boolean isLocalMock, long deadlineMs) {
            if (!(isLocalMock && distinct429Windows == 0)
                    && Math.max(System.currentTimeMillis(), nextAllowedAtMs) >= deadlineMs) return -1;
            return reserveWaitMs(isLocalMock);
        }

        void acquireSlot(boolean isLocalMock) {
            long waitMs = reserveWaitMs(isLocalMock);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new XDataProviderException(502, "请求排队等待被中断");
                }
            }
        }

        synchronized void onSuccess200() {
            distinct429Windows = 0;
            if (freeTierSlowMode) {
                freeTierSlowMode = false;
                intervalMs = PAID_INTERVAL_MS;
            }
        }

        synchronized void onRateLimited429(boolean isLocalMock) {
            long now = System.currentTimeMillis();
            if (isLocalMock) {
                distinct429Windows++;
                intervalMs = 120L;
                nextAllowedAtMs = Math.max(nextAllowedAtMs, now + 150L);
                return;
            }
            // 多线程并发时同一退避窗口内的重复 429 不重复累加，避免误判为免费慢速档
            if (now < lastBackoffUntilMs) {
                return;
            }
            distinct429Windows++;
            if (distinct429Windows <= 2) {
                intervalMs = 140L;
                lastBackoffUntilMs = now + 1100L;
                nextAllowedAtMs = Math.max(nextAllowedAtMs, lastBackoffUntilMs);
            } else {
                freeTierSlowMode = true;
                intervalMs = FREE_TIER_INTERVAL_MS;
                lastBackoffUntilMs = now + 5350L;
                nextAllowedAtMs = Math.max(nextAllowedAtMs, lastBackoffUntilMs);
            }
        }
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final SharedApiExecutor sharedExecutor;
    private final UpstreamBudgetGuard budgetGuard;
    private final HttpClient httpClient;
    private final BlueVerifiedCache blueVerifiedCache;
    private final FxTwitterClient fxTwitterClient;
    private final ConcurrentHashMap<String, KeyPacer> pacersByKey = new ConcurrentHashMap<>();

    public TwitterApiIoProvider(XOpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new SharedApiExecutor(properties), new UpstreamBudgetGuard(properties));
    }

    public TwitterApiIoProvider(XOpsProperties properties, ObjectMapper objectMapper, SharedApiExecutor sharedExecutor) {
        this(properties, objectMapper, sharedExecutor, new UpstreamBudgetGuard(properties));
    }

    @Autowired
    public TwitterApiIoProvider(
            XOpsProperties properties,
            ObjectMapper objectMapper,
            SharedApiExecutor sharedExecutor,
            @Autowired(required = false) UpstreamBudgetGuard budgetGuard
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sharedExecutor = sharedExecutor;
        this.budgetGuard = budgetGuard != null ? budgetGuard : new UpstreamBudgetGuard(properties);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.blueVerifiedCache = new BlueVerifiedCache(properties != null ? properties.getDiskCacheDir() : "", objectMapper);
        this.fxTwitterClient = new FxTwitterClient(properties, objectMapper, sharedExecutor);
    }

    public BlueVerifiedCache getBlueVerifiedCache() {
        return blueVerifiedCache;
    }

    public String verifyApiKey(String candidateKey) {
        String apiKey = (candidateKey != null && !candidateKey.isBlank())
                ? candidateKey.trim()
                : properties.getTwitterapiIoKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new XDataProviderException(400, "请输入有效的 TwitterAPI.io API Key");
        }
        String baseUrl = trimTrailingSlash(properties.getTwitterapiIoBaseUrl());
        KeyPacer pacer = pacerFor(apiKey);
        // 密钥校验必须实际访问付费服务，不能被免 Key 的资料接口误判成功。
        JsonNode root;
        try {
            root = attemptGetJsonAsync(baseUrl + "/twitter/user/info?userName=X", apiKey, pacer, isLocalMockBaseUrl(), 0).join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
        JsonNode userNode = root.hasNonNull("data") ? root.get("data") : root;
        String uname = firstNonBlank(userNode.path("userName").asText(""), userNode.path("username").asText("X"));
        return pacer.freeTierSlowMode
                ? "密钥验证通过（检测到免费试用档 1次/5秒 限速，已自动开启稳态排队保护）"
                : "TwitterAPI.io 密钥验证通过（测试读取 @" + uname + " 成功）";
    }

    public XUserSummary fetchPublicUserSummaryQuietly(String username) {
        try {
            if (!properties.hasTwitterApiIoKey() || isLocalMockBaseUrl() || username == null || username.isBlank()) {
                return null;
            }
            String apiKey = properties.getTwitterapiIoKey().trim();
            String baseUrl = trimTrailingSlash(properties.getTwitterapiIoBaseUrl());
            String encodedUser = URLEncoder.encode(username.trim(), StandardCharsets.UTF_8);
            KeyPacer pacer = pacerFor(apiKey);
            JsonNode userRoot = getJsonWithRetry(baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer);
            JsonNode userNode = userRoot.hasNonNull("data") ? userRoot.get("data") : userRoot;
            if (userNode == null || userNode.isMissingNode()
                    || firstNonBlank(userNode.path("userName").asText(""), userNode.path("username").asText("")).isBlank()) {
                return null;
            }
            return parseUserNode(userNode, username.trim());
        } catch (Exception ex) {
            log.debug("Skipped live author profile lookup for @{}: {}", username, ex.getMessage());
            return null;
        }
    }

    /** 一次性拉取最近 48 小时快照（不落库，主要用于校验与单测） */
    public RawAccountSnapshot fetchSnapshot(String username, String overrideApiKey) {
        SyncOutcome out = syncStore(username, null, 2, true, overrideApiKey);
        return snapshotOf(out.store(), "", out.degraded());
    }

    /** 由持久化存储生成分析快照，覆盖天数按两类数据各自连续入库的范围计算（单次转换，避免双倍对象分配） */
    public static RawAccountSnapshot snapshotOf(AccountStore.StoredAccount store, String notice, boolean degraded) {
        Instant syncedAt = store.syncedAtInstant() != null ? store.syncedAtInstant() : Instant.now();
        long anchor = syncedAt.getEpochSecond();
        Instant cutoff30 = syncedAt.minus(AccountStore.RETENTION);
        Instant tFrom = Instant.ofEpochSecond(AccountStore.contiguousFrom(store.tweetCoverage(), anchor));
        Instant iFrom = Instant.ofEpochSecond(AccountStore.contiguousFrom(store.inboundCoverage(), anchor));
        int days = computeCoveredWindowDays(syncedAt, !tFrom.isAfter(cutoff30), tFrom, !iFrom.isAfter(cutoff30), iFrom);
        return AccountStore.toSnapshot(store, notice, degraded, days);
    }

    /**
     * 24h 小时级指标刷新目标：仅对原创帖/串推（P）、引用帖（Q）及存在缺失字段的记录做小时级指标刷新，
     * 不每小时重复重拉大量已缓存的普通 @回复（R）与纯转推（T）。
     */
    public static boolean isDailyMetricTarget(AccountStore.StoredTweet t) {
        return t != null && (t.kind() == 'P' || t.kind() == 'Q' || !t.unavailableMetrics().isEmpty());
    }

    public static Instant cutoffForWindowDays(Instant refNow, int targetWindowDays) {
        long hours = targetWindowDays <= 2 ? 49L : (targetWindowDays <= 7 ? 169L : 721L);
        return refNow.minus(Duration.ofHours(hours));
    }

    public static Instant cutoffForWindowHours(Instant refNow, int targetWindowHours) {
        long hours = Math.max(1L, targetWindowHours) + 1L;
        return refNow.minus(Duration.ofHours(hours));
    }

    public static int computeCoveredWindowDays(
            Instant refNow,
            boolean timelineExhausted,
            Instant timelineTailTime,
            boolean mentionsExhausted,
            Instant mentionsTailTime
    ) {
        Instant c30 = cutoffForWindowDays(refNow, 30);
        Instant c7 = cutoffForWindowDays(refNow, 7);
        Instant c2 = cutoffForWindowDays(refNow, 2);
        if (streamCovers(timelineExhausted, timelineTailTime, c30)
                && streamCovers(mentionsExhausted, mentionsTailTime, c30)) {
            return 30;
        }
        if (streamCovers(timelineExhausted, timelineTailTime, c7)
                && streamCovers(mentionsExhausted, mentionsTailTime, c7)) {
            return 7;
        }
        if (streamCovers(timelineExhausted, timelineTailTime, c2)
                && streamCovers(mentionsExhausted, mentionsTailTime, c2)) {
            return 2;
        }
        return 1;
    }

    private static boolean streamCovers(boolean exhausted, Instant tailTime, Instant cutoff) {
        return exhausted || (tailTime != null && !tailTime.isAfter(cutoff));
    }

    /**
     * 本次同步结果。apiPages：本次实际请求的推文分页数（用于说明「只拉了新增部分」）。
     */
    public record SyncOutcome(
            AccountStore.StoredAccount store,
            boolean progressed,
            boolean degraded,
            boolean graphReused,
            int apiPages,
            int pendingDailyMetricUpdates,
            int incompleteDailyMetricUpdates,
            boolean dailyDataComplete,
            boolean dailyMetricBatchFailed
    ) {
        public SyncOutcome(AccountStore.StoredAccount store, boolean progressed, boolean degraded,
                boolean graphReused, int apiPages) {
            this(store, progressed, degraded, graphReused, apiPages, 0, 0, false, false);
        }
        public SyncOutcome(AccountStore.StoredAccount store, boolean progressed, boolean degraded,
                boolean graphReused, int apiPages, int pendingDailyMetricUpdates,
                int incompleteDailyMetricUpdates, boolean dailyDataComplete) {
            this(store, progressed, degraded, graphReused, apiPages, pendingDailyMetricUpdates,
                    incompleteDailyMetricUpdates, dailyDataComplete, false);
        }
    }

    /**
     * 增量同步持久化存储：只请求「目标时间窗内尚未入库」的时间段。
     *
     * @param headSync true = 缓存到期后的常规同步（补最新数据 + 资料 + 关注关系）；false = 只为更大时间窗向更早补齐
     */
    private record HeadGraphStage(
            XUserSummary profile,
            boolean graphReusable,
            boolean fetchGraph,
            PagedUserResult followings,
            PagedUserResult followers
    ) {
    }

    public SyncOutcome syncStore(
            String username,
            AccountStore.StoredAccount existing,
            int targetWindowDays,
            boolean headSync,
            String overrideApiKey
    ) {
        return syncStore(username, existing, targetWindowDays, headSync, overrideApiKey, null);
    }

    public SyncOutcome syncStore(String username, AccountStore.StoredAccount existing, int targetWindowDays,
            boolean headSync, String overrideApiKey, String expectedAccountId) {
        int hours = targetWindowDays <= 2 ? 48 : (targetWindowDays <= 7 ? 7 * 24 : 30 * 24);
        return syncStoreByHours(username, existing, hours, headSync, false, overrideApiKey, expectedAccountId);
    }

    public SyncOutcome syncStoreByHours(
            String username,
            AccountStore.StoredAccount existing,
            int targetWindowHours,
            boolean headSync,
            boolean lightweightDailyOnly,
            String overrideApiKey,
            String expectedAccountId
    ) {
        String apiKey = (overrideApiKey != null && !overrideApiKey.isBlank())
                ? overrideApiKey.trim()
                : properties.getTwitterapiIoKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new XDataProviderException(400, "服务端尚未配置 TwitterAPI.io API Key，请在 application.yml 中配置 xops.twitterapi-io-key");
        }
        long startMs = System.currentTimeMillis();
        String baseUrl = trimTrailingSlash(properties.getTwitterapiIoBaseUrl());
        String encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8);
        KeyPacer pacer = pacerFor(apiKey);
        boolean warmExisting = existing != null && existing.syncedAtInstant() != null && existing.profile() != null;
        boolean initialFetch = !warmExisting;
        boolean doHead = headSync || !warmExisting
                || (lightweightDailyOnly && existing.dailyRefreshStartedAt() <= 0);
        int effectiveHours = lightweightDailyOnly ? Math.min(24, Math.max(1, targetWindowHours)) : Math.max(24, targetWindowHours);
        Instant deadline = isLocalMockBaseUrl()
                ? Instant.now().plusSeconds(60)
                : Instant.now().plusSeconds(SNAPSHOT_SOFT_DEADLINE_SECONDS);
        AtomicBoolean degraded = new AtomicBoolean(false);
        if (initialFetch) {
            pacer.activeInitialFetches.incrementAndGet();
        }
        try {

        List<long[]> tweetCoverage = existing != null ? existing.tweetCoverage() : List.of();
        List<long[]> inboundCoverage = existing != null ? existing.inboundCoverage() : List.of();

        // 扩窗不能只信旧资料：用户名可能已转让，须在拉取新流之前核对当前账号 ID。
        // 同一小时 daily 周期内的续查（!doHead && lightweightDailyOnly）在周期起点已完成归属核验，不再重复请求 user/info。
        String boundId = expectedAccountId != null ? expectedAccountId
                : existing != null && existing.profile() != null ? existing.profile().id() : null;
        boolean verifiedInActiveDailyCycle = !doHead && lightweightDailyOnly && warmExisting
                && boundId != null && !boundId.isBlank()
                && boundId.equals(existing.profile().id())
                && existing.profile().username().equalsIgnoreCase(username)
                && existing.dailyRefreshStartedAt() > 0
                && Instant.now().getEpochSecond() - existing.dailyRefreshStartedAt() < 3600L;
        XUserSummary verifiedProfile = null;
        if (!verifiedInActiveDailyCycle && (expectedAccountId != null || !doHead)) {
            verifiedProfile = parseValidatedUserProfile(getJsonWithRetry(
                    baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer), username);
            requireSameAccountId(verifiedProfile, boundId);
        }
        final XUserSummary preflightProfile = verifiedProfile;
        SearchReplyRoots searchRoots = properties.isFxtwitterEnabled()
                ? SearchReplyRoots.from(existing, boundId, username) : SearchReplyRoots.empty();

        XUserSummary profile;
        Instant syncTo;
        long dailyCycleStart;
        boolean graphReusable;
        boolean fetchGraph;
        StreamFill tweets;
        StreamFill inbound;
        PagedUserResult followings;
        PagedUserResult followers;
        DailyMetricRefreshResult parallelDailyMetrics = DailyMetricRefreshResult.empty();

        try {
            if (!doHead) {
                profile = existing.profile();
                syncTo = existing.syncedAtInstant();
                dailyCycleStart = lightweightDailyOnly ? existing.dailyRefreshStartedAt() : 0L;
                graphReusable = false;
                fetchGraph = false;
                String self = profile.username();
                int pageBudget = pacer.freeTierSlowMode ? 3 : (lightweightDailyOnly ? 16 : MAX_STREAM_PAGES_PER_BATCH);
                Instant cutoff30 = cutoffForWindowDays(syncTo, 30);
                Instant targetCutoff = cutoffForWindowHours(syncTo, effectiveHours);
                long headTo = syncTo.getEpochSecond();

                CompletableFuture<StreamFill> tweetsF = startTweetsFillAsync(
                        baseUrl, searchRoots, encodedUser, profile, tweetCoverage, targetCutoff, cutoff30, headTo, false, false,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<StreamFill> inboundF = startInboundFillAsync(
                        baseUrl, searchRoots, encodedUser, self, profile.id(), inboundCoverage, targetCutoff, cutoff30, headTo, false, false,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<DailyMetricRefreshResult> metricsF = lightweightDailyOnly
                        ? startDailyMetricsRefreshAsync(baseUrl, existing, syncTo, dailyCycleStart, apiKey, pacer, deadline, degraded)
                        : CompletableFuture.completedFuture(DailyMetricRefreshResult.empty());
                CompletableFuture.allOf(tweetsF, inboundF, metricsF).join();
                tweets = tweetsF.join();
                inbound = inboundF.join();
                parallelDailyMetrics = metricsF.join();
                followings = null;
                followers = null;
            } else if (warmExisting) {
                // 热更新重叠加速：已有持久化账号时，推文/互动未缓存区间与 24h 主帖指标刷新并发启动
                Instant nowSync = Instant.now();
                long sinceLastSyncSec = Duration.between(existing.syncedAtInstant(), nowSync).getSeconds();
                syncTo = (sinceLastSyncSec >= 0 && sinceLastSyncSec < 60)
                        ? existing.syncedAtInstant()
                        : nowSync;
                dailyCycleStart = lightweightDailyOnly ? syncTo.getEpochSecond() : 0L;
                String knownSelf = existing.profile().username();
                int pageBudget = pacer.freeTierSlowMode ? 3 : (lightweightDailyOnly ? 16 : MAX_STREAM_PAGES_PER_BATCH);
                boolean allowNativeHead = !lightweightDailyOnly;
                Instant cutoff30 = cutoffForWindowDays(syncTo, 30);
                Instant targetCutoff = cutoffForWindowHours(syncTo, effectiveHours);
                long headTo = syncTo.getEpochSecond();

                CompletableFuture<XUserSummary> profileF = preflightProfile != null
                        ? CompletableFuture.completedFuture(preflightProfile)
                        : getJsonWithRetryAsync(baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer)
                                .thenApply(root -> parseValidatedUserProfile(root, username));
                CompletableFuture<HeadGraphStage> headStageF = profileF.thenCompose(freshProfile -> {
                    requireSameAccountId(freshProfile, boundId);
                    long graphAgeHours = existing.hasGraph()
                            ? Duration.between(Instant.ofEpochSecond(existing.graphFetchedAt()), syncTo).toHours()
                            : Long.MAX_VALUE;
                    boolean baseGraphValid = existing.hasGraph()
                            && !existing.graphRefreshFailed()
                            && graphAgeHours < (lightweightDailyOnly ? 24 : 6);
                    boolean reuseFg = baseGraphValid
                            && !existing.followingsTruncated()
                            && existing.profile().followingCount() == freshProfile.followingCount();
                    boolean reuseFr = baseGraphValid
                            && !existing.followersTruncated()
                            && existing.profile().followersCount() == freshProfile.followersCount();
                    if (reuseFg && reuseFr) {
                        return CompletableFuture.completedFuture(new HeadGraphStage(freshProfile, true, false, null, null));
                    }
                    CompletableFuture<PagedUserResult> fgF = reuseFg
                            ? CompletableFuture.completedFuture(new PagedUserResult(
                                    AccountStore.followingUsers(existing),
                                    new HashSet<>(existing.explicitFollowedBy()),
                                    existing.followingsTruncated(),
                                    false, existing.followingOrderById()))
                            : (freshProfile.followingCount() > 0
                                    ? fetchPagedUsersAsync(
                                            baseUrl + "/twitter/user/followings?userName=" + encodedUser + "&pageSize=200",
                                            apiKey, pacer, pacer.freeTierSlowMode ? 1 : Math.max(1, properties.getMaxFollowingPages()),
                                            freshProfile.followingCount(), "followings", deadline, degraded)
                                    : CompletableFuture.completedFuture(null));
                    CompletableFuture<PagedUserResult> frF = reuseFr
                            ? CompletableFuture.completedFuture(new PagedUserResult(
                                    AccountStore.followerUsers(existing),
                                    Set.of(),
                                    existing.followersTruncated(),
                                    false, existing.followerOrderById()))
                            : (freshProfile.followersCount() > 0
                                    ? fetchFollowersAsync(
                                            baseUrl, encodedUser, freshProfile.id(),
                                            apiKey, pacer, pacer.freeTierSlowMode ? 1 : Math.max(1, properties.getMaxFollowerPages()),
                                            freshProfile.followersCount(), deadline, degraded)
                                    : CompletableFuture.completedFuture(null));
                    return CompletableFuture.allOf(fgF, frF)
                            .thenApply(v -> new HeadGraphStage(freshProfile, false, true, fgF.join(), frF.join()));
                });

                CompletableFuture<StreamFill> tweetsF = startTweetsFillAsync(
                        baseUrl, searchRoots, encodedUser, existing.profile(), tweetCoverage, targetCutoff, cutoff30, headTo, allowNativeHead, false,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<StreamFill> inboundF = startInboundFillAsync(
                        baseUrl, searchRoots, encodedUser, knownSelf, existing.profile().id(), inboundCoverage, targetCutoff, cutoff30, headTo, allowNativeHead, false,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<DailyMetricRefreshResult> metricsF = lightweightDailyOnly
                        ? startDailyMetricsRefreshAsync(baseUrl, existing, syncTo, dailyCycleStart, apiKey, pacer, deadline, degraded)
                        : CompletableFuture.completedFuture(DailyMetricRefreshResult.empty());

                CompletableFuture.allOf(headStageF, tweetsF, inboundF, metricsF).join();
                HeadGraphStage stage = headStageF.join();
                profile = stage.profile();
                graphReusable = stage.graphReusable();
                fetchGraph = stage.fetchGraph();
                followings = stage.followings();
                followers = stage.followers();
                tweets = tweetsF.join();
                inbound = inboundF.join();
                parallelDailyMetrics = metricsF.join();
            } else {
                // 首次冷启动：先校验账号存在性，随后在共用线程池中以双倍并发采集动态、互动与关注关系
                profile = preflightProfile != null ? preflightProfile : parseValidatedUserProfile(
                        getJsonWithRetry(baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer), username);
                syncTo = Instant.now();
                dailyCycleStart = lightweightDailyOnly ? syncTo.getEpochSecond() : 0L;
                graphReusable = false;
                fetchGraph = true;
                String self = profile.username();
                int pageBudget = pacer.freeTierSlowMode ? 3 : (lightweightDailyOnly ? 32 : MAX_STREAM_PAGES_PER_BATCH * 2);
                boolean allowNativeHead = !lightweightDailyOnly;
                Instant cutoff30 = cutoffForWindowDays(syncTo, 30);
                Instant targetCutoff = cutoffForWindowHours(syncTo, effectiveHours);
                long headTo = syncTo.getEpochSecond();

                CompletableFuture<StreamFill> tweetsF = startTweetsFillAsync(
                        baseUrl, searchRoots, encodedUser, profile, tweetCoverage, targetCutoff, cutoff30, headTo, allowNativeHead, true,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<StreamFill> inboundF = startInboundFillAsync(
                        baseUrl, searchRoots, encodedUser, self, profile.id(), inboundCoverage, targetCutoff, cutoff30, headTo, allowNativeHead, true,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<PagedUserResult> followingsF = profile.followingCount() > 0
                        ? fetchPagedUsersAsync(
                                baseUrl + "/twitter/user/followings?userName=" + encodedUser + "&pageSize=200",
                                apiKey, pacer, pacer.freeTierSlowMode ? 1 : Math.max(1, properties.getMaxFollowingPages()),
                                profile.followingCount(), "followings", deadline, degraded)
                        : CompletableFuture.completedFuture(null);
                CompletableFuture<PagedUserResult> followersF = profile.followersCount() > 0
                        ? fetchFollowersAsync(
                                baseUrl, encodedUser, profile.id(),
                                apiKey, pacer, pacer.freeTierSlowMode ? 1 : Math.max(1, properties.getMaxFollowerPages()),
                                profile.followersCount(), deadline, degraded)
                        : CompletableFuture.completedFuture(null);

                CompletableFuture.allOf(tweetsF, inboundF, followingsF, followersF).join();
                tweets = tweetsF.join();
                inbound = inboundF.join();
                followings = followingsF.join();
                followers = followersF.join();
            }
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new XDataProviderException(502, "并发拉取 TwitterAPI.io 数据异常：" + ce.getMessage(), ce);
        }

        AccountStore.GraphUpdate graph = null;
        if (fetchGraph) {
            List<XUserSummary> fg = followings != null ? followings.users() : List.of();
            List<XUserSummary> fr = followers != null ? followers.users() : List.of();
            boolean truncated = (followers != null && followers.truncated())
                    || profile.followersCount() != fr.size();
            graph = new AccountStore.GraphUpdate(fg, fr,
                    followings != null ? followings.explicitFollowedByKeys() : Set.of(), truncated,
                    (followings != null && followings.truncated()) || profile.followingCount() != fg.size(),
                    followings != null && followings.failed(), followers != null && followers.failed(),
                    List.of(), Map.of(),
                    followings != null ? followings.orderById() : Map.of(),
                    followers != null ? followers.orderById() : Map.of());
        }

        if (!lightweightDailyOnly && graph != null && graph.followersTruncated() && !graph.followingsFailed()) {
            Map<String, Long> attemptedAt = new ConcurrentHashMap<>(existing != null ? existing.relationshipAttemptedAt() : Map.of());
            Set<String> followerIds = new HashSet<>();
            graph.followers().forEach(peer -> followerIds.add(peer.id()));
            List<XUserSummary> missing = graph.followings().stream()
                    .filter(peer -> !followerIds.contains(peer.id())).toList();
            List<AccountStore.RelationshipEvidence> exact = verifyRelationshipCandidates(
                    baseUrl, profile.username(), missing, existing != null ? existing.relationshipEvidence() : List.of(),
                    attemptedAt, apiKey, pacer, deadline, degraded);
            boolean followingChanged = exact.stream().anyMatch(e -> !e.following());
            graph = new AccountStore.GraphUpdate(graph.followings(), graph.followers(), graph.explicitFollowedBy(),
                    graph.followersTruncated(), graph.followingsTruncated() || followingChanged,
                    graph.followingsFailed(), graph.followersFailed(), exact, attemptedAt,
                    graph.followingOrderById(), graph.followerOrderById());
        }

        List<RawTweet> streamTweets = new ArrayList<>(tweets.items().size());
        tweets.items().forEach(o -> streamTweets.add((RawTweet) o));
        Set<String> streamTweetIds = new HashSet<>(streamTweets.size());
        streamTweets.forEach(t -> streamTweetIds.add(t.id()));

        List<RawTweet> newTweets = new ArrayList<>(streamTweets.size() + parallelDailyMetrics.refreshedTweets().size());
        newTweets.addAll(streamTweets);
        Map<String, Long> metricsObservedAt = new HashMap<>(tweets.metricsObservedAt());
        List<InboundInteraction> newInbound = new ArrayList<>(inbound.items().size());
        inbound.items().forEach(o -> newInbound.add((InboundInteraction) o));

        List<String> refreshIds = List.of();
        AtomicBoolean metricsRequestStarted = new AtomicBoolean(false);
        boolean metricsProgressed = false;
        boolean dailyMetricBatchFailed = false;
        Set<String> confirmedDeletedIds = new LinkedHashSet<>();
        Map<String, XUserSummary> extraUsers = new LinkedHashMap<>();

        if (lightweightDailyOnly) {
            refreshIds = parallelDailyMetrics.attemptedIds();
            metricsRequestStarted.set(parallelDailyMetrics.requestStarted());
            metricsProgressed = parallelDailyMetrics.progressed();
            dailyMetricBatchFailed = parallelDailyMetrics.batchFailed();
            confirmedDeletedIds.addAll(parallelDailyMetrics.confirmedDeletedIds());
            confirmedDeletedIds.removeAll(streamTweetIds);
            extraUsers.putAll(parallelDailyMetrics.extraUsers());
            for (RawTweet mt : parallelDailyMetrics.refreshedTweets()) {
                if (!streamTweetIds.contains(mt.id())) {
                    newTweets.add(mt);
                    Long obs = parallelDailyMetrics.observedAtById().get(mt.id());
                    if (obs != null) {
                        metricsObservedAt.put(mt.id(), obs);
                    }
                }
            }
        } else if (doHead && warmExisting && !Instant.now().isAfter(deadline)) {
            refreshIds = existing.tweets().stream()
                    .filter(t -> t.createdAt() >= syncTo.minus(Duration.ofHours(720)).getEpochSecond())
                    .filter(t -> !streamTweetIds.contains(t.id()))
                    .sorted(java.util.Comparator.comparingLong(AccountStore.StoredTweet::metricsAttemptedAt)
                            .thenComparingInt(t -> !t.unavailableMetrics().isEmpty()
                                    && ((t.kind() == 'P' && (t.flags() & 4) == 0) || t.kind() == 'Q') ? 0 : 1))
                    .limit(50).map(AccountStore.StoredTweet::id).toList();
            if (!refreshIds.isEmpty()) {
                JsonNode refreshed = getJsonOnceBeforeAsync(baseUrl + "/twitter/tweets?tweet_ids="
                        + URLEncoder.encode(String.join(",", refreshIds), StandardCharsets.UTF_8),
                        apiKey, pacer, deadline, degraded, 15_000, false, metricsRequestStarted, existing).join();
                if (includesFreeData(refreshed)) {
                    refreshIds = jsonIds(refreshed, "__attempted_ids");
                    if (!jsonIds(refreshed, "__incomplete_ids").isEmpty()) degraded.set(true);
                }
                long observedAt = Instant.now().getEpochSecond();
                Set<String> returned = new HashSet<>();
                for (JsonNode node : extractArray(refreshed, "tweets", "data")) {
                    if (refreshIds.contains(node.path("id").asText(""))) {
                        JsonNode authorNode = node.path("author");
                        XUserSummary author = authorNode.isObject() ? parseUserNode(authorNode, "") : null;
                        if (!matchesTweetAuthor(author, existing.profile())) {
                            degraded.set(true);
                            continue;
                        }
                        RawTweet parsed = parseMetricTweet(node, profile.username(), existing, includesFreeData(refreshed));
                        if (parsed == null || parsed.createdAt() == null) {
                            degraded.set(true);
                            continue;
                        }
                        returned.add(parsed.id());
                        newTweets.add(parsed);
                        metricsObservedAt.put(parsed.id(), observedAt(node, observedAt));
                        if (author != null && !author.username().isBlank()) {
                            extraUsers.put(author.username().toLowerCase(Locale.ROOT), author);
                        }
                    }
                }
                metricsProgressed = !returned.isEmpty();
            }
        }

        // 若主页总帖数较上次同步净减（且已发现的删帖数未解释全部净减），对 24h 内普通回复/转推补做一次轻量存在性核验
        if (lightweightDailyOnly && doHead && warmExisting && !degraded.get()
                && existing.profile().totalPostsCount() > 0 && profile.totalPostsCount() > 0
                && !Instant.now().isAfter(deadline)) {
            Set<String> priorIds = new HashSet<>(existing.tweets().size());
            existing.tweets().forEach(t -> priorIds.add(t.id()));
            int trulyNewCount = 0;
            for (RawTweet st : streamTweets) {
                if (!priorIds.contains(st.id())) {
                    trulyNewCount++;
                }
            }
            if (profile.totalPostsCount() < existing.profile().totalPostsCount() + trulyNewCount - confirmedDeletedIds.size()) {
                long from24 = syncTo.minus(Duration.ofHours(24)).getEpochSecond();
                long to24 = syncTo.getEpochSecond();
                List<String> replyCheckIds = existing.tweets().stream()
                        .filter(t -> t.createdAt() >= from24 && t.createdAt() <= to24)
                        .filter(t -> !isDailyMetricTarget(t) && !streamTweetIds.contains(t.id()))
                        .sorted(java.util.Comparator.comparingLong(AccountStore.StoredTweet::createdAt).reversed())
                        .limit(50)
                        .map(AccountStore.StoredTweet::id)
                        .toList();
                if (!replyCheckIds.isEmpty()) {
                    DailyMetricRefreshResult replySweep = fetchTweetBatchChunksAsync(
                            baseUrl, existing, replyCheckIds, apiKey, pacer, deadline, degraded).join();
                    confirmedDeletedIds.addAll(replySweep.confirmedDeletedIds());
                    extraUsers.putAll(replySweep.extraUsers());
                    for (RawTweet rt : replySweep.refreshedTweets()) {
                        if (!streamTweetIds.contains(rt.id())) {
                            newTweets.add(rt);
                            Long obs = replySweep.observedAtById().get(rt.id());
                            if (obs != null) metricsObservedAt.put(rt.id(), obs);
                        }
                    }
                    if (!replySweep.confirmedDeletedIds().isEmpty()) {
                        metricsProgressed = true;
                    }
                }
            }
        }

        String self = profile.username();
        AccountStore.StoredAccount merged = AccountStore.merge(
                existing, profile, syncTo, newTweets, newInbound, tweets.coverage(), inbound.coverage(), graph, metricsObservedAt);
        if (!refreshIds.isEmpty() && metricsRequestStarted.get()) {
            merged = AccountStore.withMetricAttempts(merged, refreshIds, Instant.now());
        }
        if (!confirmedDeletedIds.isEmpty()) {
            merged = AccountStore.withoutTweets(merged, confirmedDeletedIds);
        }

        long nowSec = Instant.now().getEpochSecond();
        int blueLimit = initialFetch ? 200 : 100;
        List<String> missingBlueIds = !lightweightDailyOnly || doHead
                ? collectUncachedInteractionUserIds(merged, nowSec, blueLimit, lightweightDailyOnly ? nowSec - 86400L : Long.MIN_VALUE)
                : List.of();
        if (!missingBlueIds.isEmpty() && !Instant.now().isAfter(deadline)) {
            int blueChunkSize = (initialFetch && !pacer.freeTierSlowMode && missingBlueIds.size() > 25) ? 25 : missingBlueIds.size();
            List<CompletableFuture<JsonNode>> blueFutures = new ArrayList<>();
            for (int i = 0; i < missingBlueIds.size(); i += blueChunkSize) {
                List<String> subIds = missingBlueIds.subList(i, Math.min(i + blueChunkSize, missingBlueIds.size()));
                String batchUrl = baseUrl + "/twitter/user/batch_info_by_ids?userIds="
                        + URLEncoder.encode(String.join(",", subIds), StandardCharsets.UTF_8);
                blueFutures.add(getJsonOnceBeforeAsync(
                        batchUrl, apiKey, pacer, deadline, new AtomicBoolean(false), 8_000, false));
            }
            CompletableFuture.allOf(blueFutures.toArray(CompletableFuture[]::new)).join();
            for (CompletableFuture<JsonNode> bf : blueFutures) {
                JsonNode batchRoot = bf.join();
                for (JsonNode uNode : extractArray(batchRoot, "users", "data")) {
                    if (uNode.path("unavailable").asBoolean(false)) continue;
                    XUserSummary enriched = parseUserNode(uNode, "");
                    if (!enriched.username().isBlank()) {
                        extraUsers.put(enriched.username().toLowerCase(Locale.ROOT), enriched);
                    }
                }
            }
        }
        merged = AccountStore.applyBlueVerifiedCache(merged, extraUsers, blueVerifiedCache, nowSec);
        blueVerifiedCache.flushIfDirty();
        boolean progressed = tweets.coverage().stream()
                .anyMatch(r -> !AccountStore.missingRanges(tweetCoverage, r[0], r[1]).isEmpty())
                || inbound.coverage().stream()
                .anyMatch(r -> !AccountStore.missingRanges(inboundCoverage, r[0], r[1]).isEmpty());
        int pendingDailyMetrics = 0;
        int incompleteDailyMetrics = 0;
        boolean dailyComplete = false;
        if (lightweightDailyOnly) {
            long from = syncTo.minus(Duration.ofHours(24)).getEpochSecond();
            long to = syncTo.getEpochSecond();
            for (AccountStore.StoredTweet tweet : merged.tweets()) {
                if (tweet.createdAt() < from || tweet.createdAt() > to || !isDailyMetricTarget(tweet)) continue;
                if (tweet.metricsAttemptedAt() < dailyCycleStart) pendingDailyMetrics++;
                if (tweet.metricsFetchedAt() < dailyCycleStart || !tweet.unavailableMetrics().isEmpty()) incompleteDailyMetrics++;
            }
            dailyComplete = !degraded.get() && incompleteDailyMetrics == 0
                    && AccountStore.missingRanges(merged.tweetCoverage(), from, to).isEmpty()
                    && AccountStore.missingRanges(merged.inboundCoverage(), from, to).isEmpty();
            merged = AccountStore.withDailyRefreshState(merged, dailyCycleStart,
                    dailyComplete ? dailyCycleStart : (existing != null ? existing.dailyRefreshedAt() : 0));
            progressed |= metricsProgressed;
        }
        int pages = tweets.pages() + inbound.pages();
        log.info("Synced TwitterAPI.io store for @{} in {} ms (head={}, targetHours={}, lightDaily={}, pages={}, newTweets={}, deletedTweets={}, newInbound={}, graph={}, storedTweets={}, storedInbound={}, degraded={})",
                self, System.currentTimeMillis() - startMs, doHead, effectiveHours, lightweightDailyOnly, pages,
                newTweets.size(), confirmedDeletedIds.size(), newInbound.size(), fetchGraph ? "fetched" : (doHead ? "reused" : "skipped"),
                merged.tweets().size(), merged.inbound().size(), degraded.get());
        return new SyncOutcome(merged, progressed, degraded.get(), doHead && graphReusable, pages,
                pendingDailyMetrics, incompleteDailyMetrics, dailyComplete, dailyMetricBatchFailed);
        } finally {
            if (initialFetch) {
                pacer.activeInitialFetches.decrementAndGet();
            }
        }
    }

    public static int pendingRelationshipChecks(AccountStore.StoredAccount store) {
        return pendingRelationshipPeers(store).size();
    }

    private static List<XUserSummary> pendingRelationshipPeers(AccountStore.StoredAccount store) {
        if (store == null || store.graphRefreshFailed() || (!store.followersTruncated() && !store.followingsTruncated())) {
            return List.of();
        }
        return AccountStore.toSnapshot(store, "", false, 0).relationships().stream()
                .filter(r -> !r.relationshipVerified() && r.iFollowThem() != r.theyFollowMe())
                .filter(r -> r.iFollowThem() ? store.followersTruncated() : store.followingsTruncated())
                .map(RelationshipEntry::user).toList();
    }

    /** 仅应用户明确点击续查调用，不抓取历史或重新分页图谱。 */
    public SyncOutcome continueRelationships(String username, AccountStore.StoredAccount store, String expectedAccountId) {
        Instant now = Instant.now();
        if (store == null || !store.hasGraph() || store.graphRefreshFailed()
                || now.getEpochSecond() - store.graphFetchedAt() >= 300) {
            throw new XDataProviderException(409, "关注图谱已过期或不可用，请在缓存间隔结束后刷新数据，再继续核验关系");
        }
        List<XUserSummary> pending = pendingRelationshipPeers(store);
        if (pending.isEmpty()) return new SyncOutcome(store, false, false, true, 0);
        if (pending.stream().anyMatch(p -> p.id() == null || p.id().isBlank())) {
            throw new XDataProviderException(409, "旧图谱缺少推友 ID，请在缓存间隔结束后刷新图谱，再继续核验关系");
        }
        String apiKey = properties.getTwitterapiIoKey();
        if (apiKey == null || apiKey.isBlank()) throw new XDataProviderException(400, "服务端尚未配置 TwitterAPI.io API Key");
        String baseUrl = trimTrailingSlash(properties.getTwitterapiIoBaseUrl());
        KeyPacer pacer = pacerFor(apiKey);
        Instant deadline = now.plusSeconds(isLocalMockBaseUrl() ? 60 : SNAPSHOT_SOFT_DEADLINE_SECONDS);
        XUserSummary profile = parseValidatedUserProfile(getJsonWithRetry(baseUrl + "/twitter/user/info?userName="
                + URLEncoder.encode(username, StandardCharsets.UTF_8), apiKey, pacer), username);
        requireSameAccountId(profile, expectedAccountId != null ? expectedAccountId : store.profile().id());
        if (!profile.username().equalsIgnoreCase(store.profile().username())
                || profile.followingCount() != store.profile().followingCount()
                || profile.followersCount() != store.profile().followersCount()) {
            throw new XDataProviderException(409, "账号资料或关系人数已变化，请在缓存间隔结束后刷新图谱，再继续核验关系");
        }
        AtomicBoolean degraded = new AtomicBoolean(false);
        Map<String, Long> attemptedAt = new ConcurrentHashMap<>(store.relationshipAttemptedAt());
        List<AccountStore.RelationshipEvidence> checked = verifyRelationshipCandidates(
                baseUrl, profile.username(), pending, store.relationshipEvidence(), attemptedAt, apiKey, pacer, deadline, degraded);
        Map<String, AccountStore.RelationshipEvidence> combined = new java.util.LinkedHashMap<>();
        store.relationshipEvidence().forEach(e -> combined.put(e.peerId(), e));
        checked.forEach(e -> combined.put(e.peerId(), e));
        var result = AccountStore.withRelationshipEvidence(store, new ArrayList<>(combined.values()), attemptedAt);
        return new SyncOutcome(result, !checked.isEmpty(), degraded.get(), true, 0);
    }

    private List<AccountStore.RelationshipEvidence> verifyRelationshipCandidates(
            String baseUrl, String self, List<XUserSummary> peers,
            List<AccountStore.RelationshipEvidence> previousChecks, Map<String, Long> attemptedAt,
            String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded) {
        Map<String, Long> lastChecked = new HashMap<>(attemptedAt);
        previousChecks.forEach(e -> lastChecked.merge(e.peerId(), e.checkedAt() * 1000, Math::max));
        Set<String> seenIds = new HashSet<>();
        List<XUserSummary> candidates = peers.stream()
                .filter(p -> p.id() != null && !p.id().isBlank() && p.username() != null && !p.username().isBlank())
                .filter(p -> seenIds.add(p.id()))
                .sorted(java.util.Comparator.comparingLong(p -> lastChecked.getOrDefault(p.id(), 0L)))
                .limit(100).toList();
        attemptedAt.keySet().retainAll(seenIds);
        List<AccountStore.RelationshipEvidence> evidence = new ArrayList<>();
        // 每批最多 4 个单次请求，沿用共享有界池和 KeyPacer；不重试，也不为补核验扩展同步期限。
        for (int offset = 0; offset < candidates.size() && Instant.now().isBefore(deadline); offset += 4) {
            List<CompletableFuture<AccountStore.RelationshipEvidence>> pending = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + 4, candidates.size()); i++) {
                pending.add(checkRelationshipOnce(baseUrl, self, candidates.get(i), apiKey, pacer, deadline, degraded, attemptedAt));
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
            for (var future : pending) {
                AccountStore.RelationshipEvidence checked = future.join();
                if (checked != null) evidence.add(checked);
            }
        }
        return evidence;
    }

    private CompletableFuture<AccountStore.RelationshipEvidence> checkRelationshipOnce(
            String baseUrl, String self, XUserSummary peer, String apiKey, KeyPacer pacer,
            Instant deadline, AtomicBoolean degraded, Map<String, Long> attemptedAt) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) return CompletableFuture.completedFuture(null);
        String url = baseUrl + "/twitter/user/check_follow_relationship?source_user_name="
                + URLEncoder.encode(self, StandardCharsets.UTF_8) + "&target_user_name="
                + URLEncoder.encode(peer.username(), StandardCharsets.UTF_8);
        return sharedExecutor.<AccountStore.RelationshipEvidence>submitAsync(() -> {
            long budgetMs = Duration.between(Instant.now(), deadline).toMillis();
            if (budgetMs <= 0) return null;
            long waitMs = pacer.reserveWaitMsBefore(isLocalMockBaseUrl(), deadline.toEpochMilli());
            if (waitMs < 0 || waitMs >= budgetMs) return null;
            if (waitMs > 0) Thread.sleep(waitMs);
            budgetMs = Duration.between(Instant.now(), deadline).toMillis();
            if (budgetMs <= 0) return null;
            budgetGuard.acquirePermit(url, "twitterapi_io_relation");
            // 预算落盘也可能排队；实际发送前重新计算期限，过期预留不退款。
            budgetMs = Duration.between(Instant.now(), deadline).toMillis();
            if (Thread.currentThread().isInterrupted() || budgetMs <= 0) {
                degraded.set(true);
                return null;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.min(10_000, budgetMs)))
                    .header("X-API-Key", apiKey).header("Accept", "application/json").GET().build();
            attemptedAt.put(peer.id(), Instant.now().toEpochMilli());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) pacer.onRateLimited429(isLocalMockBaseUrl());
            if (response.statusCode() != 200) {
                degraded.set(true);
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!"success".equals(root.path("status").asText())
                    || !data.path("following").isBoolean() || !data.path("followed_by").isBoolean()) {
                degraded.set(true);
                return null;
            }
            pacer.onSuccess200();
            return new AccountStore.RelationshipEvidence(peer.id(), peer.username(),
                    data.get("following").booleanValue(), data.get("followed_by").booleanValue(),
                    Instant.now().getEpochSecond());
        }).completeOnTimeout(null, remaining, java.util.concurrent.TimeUnit.MILLISECONDS).exceptionally(ex -> {
            degraded.set(true);
            return null;
        });
    }

    private static void requireSameAccountId(XUserSummary profile, String expectedId) {
        if (expectedId == null || expectedId.isBlank() || !expectedId.equals(profile.id())) {
            throw new XDataProviderException(409, "账号 ID 已变更或缺失，已停止同步，请重新授权登录");
        }
    }

    private XUserSummary parseValidatedUserProfile(JsonNode userRoot, String requestedUsername) {
        JsonNode userNode = userRoot.hasNonNull("data") ? userRoot.get("data") : userRoot;
        if (userNode == null || userNode.isMissingNode()
                || firstNonBlank(userNode.path("userName").asText(""), userNode.path("username").asText("")).isBlank()) {
            throw new XDataProviderException(404, "在 X 上未找到公开账号 @" + requestedUsername + "，请核对用户名是否正确");
        }
        return parseUserNode(userNode, requestedUsername);
    }

    private CompletableFuture<StreamFill> startTweetsFillAsync(
            String baseUrl, SearchReplyRoots searchRoots, String encodedUser, XUserSummary profile, List<long[]> coverage,
            Instant targetCutoff, Instant cutoff30, long headTo, boolean allowNativeHead, boolean initialFetch,
            String apiKey, KeyPacer pacer, int pageBudget, Instant deadline, AtomicBoolean degraded
    ) {
        String self = profile.username();
        return fillStreamAsync(
                baseUrl, searchRoots,
                baseUrl + "/twitter/user/last_tweets?userName=" + encodedUser + "&includeReplies=true",
                "from:" + self, profile.id(),
                coverage, targetCutoff, cutoff30, headTo, allowNativeHead, initialFetch,
                apiKey, pacer, pageBudget, deadline, degraded,
                node -> {
                    RawTweet t = parseRawTweet(node, self);
                    JsonNode authorNode = node.path("author");
                    XUserSummary author = authorNode.isObject() ? parseUserNode(authorNode, "") : null;
                    if (t == null || !matchesTweetAuthor(author, profile)) return new Parsed(null, null, false, false);
                    return new Parsed(t, t.createdAt(),
                            node.path("isPin").asBoolean(false) || node.path("isPinned").asBoolean(false));
                });
    }

    private record DailyMetricRefreshResult(
            List<String> attemptedIds,
            List<RawTweet> refreshedTweets,
            Map<String, Long> observedAtById,
            Set<String> confirmedDeletedIds,
            Map<String, XUserSummary> extraUsers,
            boolean requestStarted,
            boolean progressed,
            boolean batchFailed
    ) {
        static DailyMetricRefreshResult empty() {
            return new DailyMetricRefreshResult(List.of(), List.of(), Map.of(), Set.of(), Map.of(), false, false, false);
        }
    }

    private CompletableFuture<DailyMetricRefreshResult> startDailyMetricsRefreshAsync(
            String baseUrl,
            AccountStore.StoredAccount existing,
            Instant syncTo,
            long dailyCycleStart,
            String apiKey,
            KeyPacer pacer,
            Instant deadline,
            AtomicBoolean degraded
    ) {
        if (existing == null || existing.profile() == null || Instant.now().isAfter(deadline)) {
            return CompletableFuture.completedFuture(DailyMetricRefreshResult.empty());
        }
        long from = syncTo.minus(Duration.ofHours(24)).getEpochSecond();
        long to = syncTo.getEpochSecond();
        List<String> candidateIds = existing.tweets().stream()
                .filter(t -> t.createdAt() >= from && t.createdAt() <= to)
                .filter(TwitterApiIoProvider::isDailyMetricTarget)
                .filter(t -> t.metricsAttemptedAt() < dailyCycleStart)
                .sorted(java.util.Comparator.comparingLong(AccountStore.StoredTweet::metricsAttemptedAt)
                        .thenComparingInt(t -> !t.unavailableMetrics().isEmpty()
                                && ((t.kind() == 'P' && (t.flags() & 4) == 0) || t.kind() == 'Q') ? 0 : 1))
                .limit(50)
                .map(AccountStore.StoredTweet::id)
                .toList();
        if (candidateIds.isEmpty()) {
            return CompletableFuture.completedFuture(DailyMetricRefreshResult.empty());
        }
        return fetchTweetBatchChunksAsync(baseUrl, existing, candidateIds, apiKey, pacer, deadline, degraded);
    }

    private CompletableFuture<DailyMetricRefreshResult> fetchTweetBatchChunksAsync(
            String baseUrl,
            AccountStore.StoredAccount existing,
            List<String> ids,
            String apiKey,
            KeyPacer pacer,
            Instant deadline,
            AtomicBoolean degraded
    ) {
        if (ids.isEmpty() || Instant.now().isAfter(deadline)) {
            return CompletableFuture.completedFuture(DailyMetricRefreshResult.empty());
        }
        // 上游 /twitter/tweets 单批 50 条串行水合耗时约 11~14s；线上付费档拆为 15 条并发分片可将总墙钟耗时压至 ~3s
        int chunkSize = (isLocalMockBaseUrl() || pacer.freeTierSlowMode) ? 50 : 15;
        List<List<String>> chunks = new ArrayList<>();
        List<AtomicBoolean> chunkStartedFlags = new ArrayList<>();
        List<CompletableFuture<JsonNode>> chunkFutures = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<String> chunkIds = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            AtomicBoolean started = new AtomicBoolean(false);
            String url = baseUrl + "/twitter/tweets?tweet_ids="
                    + URLEncoder.encode(String.join(",", chunkIds), StandardCharsets.UTF_8);
            chunks.add(chunkIds);
            chunkStartedFlags.add(started);
            chunkFutures.add(getJsonOnceBeforeAsync(url, apiKey, pacer, deadline, degraded, 25_000, false, started, existing));
        }
        return CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture[]::new)).thenApply(v -> {
            List<String> attemptedIds = new ArrayList<>();
            List<RawTweet> refreshedTweets = new ArrayList<>();
            Map<String, Long> observedAtById = new LinkedHashMap<>();
            Set<String> confirmedDeletedIds = new LinkedHashSet<>();
            Map<String, XUserSummary> extraUsers = new LinkedHashMap<>();
            boolean anyStarted = false;
            boolean anyProgressed = false;
            boolean anyBatchFailed = false;
            String selfUsername = existing.profile().username();

            for (int c = 0; c < chunks.size(); c++) {
                if (!chunkStartedFlags.get(c).get()) {
                    continue;
                }
                anyStarted = true;
                List<String> chunkIds = chunks.get(c);
                JsonNode refreshed = chunkFutures.get(c).join();
                attemptedIds.addAll(includesFreeData(refreshed) ? jsonIds(refreshed, "__attempted_ids") : chunkIds);
                if (refreshed == null || !hasArray(refreshed, "tweets", "data")) {
                    degraded.set(true);
                    anyBatchFailed = true;
                    continue;
                }
                if (includesFreeData(refreshed) && !jsonIds(refreshed, "__incomplete_ids").isEmpty()) {
                    degraded.set(true);
                    anyBatchFailed = true;
                }
                long observedAt = Instant.now().getEpochSecond();
                Set<String> chunkSet = new HashSet<>(chunkIds);
                Set<String> presentIds = new HashSet<>();
                Set<String> paidConfirmedMissing = new HashSet<>(jsonIds(refreshed, "__confirmed_deleted_ids"));
                boolean chunkRejectedAny = false;
                int validCount = 0;
                for (JsonNode node : extractArray(refreshed, "tweets", "data")) {
                    String nodeId = node.path("id").asText("");
                    if (!chunkSet.contains(nodeId)) {
                        continue;
                    }
                    presentIds.add(nodeId);
                    JsonNode authorNode = node.path("author");
                    XUserSummary author = authorNode.isObject() ? parseUserNode(authorNode, "") : null;
                    if (!matchesTweetAuthor(author, existing.profile())) {
                        degraded.set(true);
                        chunkRejectedAny = true;
                        continue;
                    }
                    RawTweet parsed = parseMetricTweet(node, selfUsername, existing, includesFreeData(refreshed));
                    if (parsed == null || parsed.createdAt() == null) {
                        degraded.set(true);
                        chunkRejectedAny = true;
                        continue;
                    }
                    validCount++;
                    refreshedTweets.add(parsed);
                    observedAtById.put(parsed.id(), observedAt(node, observedAt));
                    if (author != null && !author.username().isBlank()) {
                        extraUsers.put(author.username().toLowerCase(Locale.ROOT), author);
                    }
                }
                int deletedInChunk = 0;
                for (String reqId : chunkIds) {
                    if (!presentIds.contains(reqId)
                            && (!includesFreeData(refreshed) || paidConfirmedMissing.contains(reqId))) {
                        confirmedDeletedIds.add(reqId);
                        deletedInChunk++;
                    }
                }
                if (validCount > 0 || deletedInChunk > 0) {
                    anyProgressed = true;
                }
                if (chunkRejectedAny && validCount == 0 && deletedInChunk == 0) {
                    anyBatchFailed = true;
                }
            }
            return new DailyMetricRefreshResult(
                    attemptedIds, refreshedTweets, observedAtById, confirmedDeletedIds,
                    extraUsers, anyStarted, anyProgressed, anyBatchFailed);
        });
    }

    private CompletableFuture<StreamFill> startInboundFillAsync(
            String baseUrl, SearchReplyRoots searchRoots, String encodedUser, String self, String selfId, List<long[]> coverage,
            Instant targetCutoff, Instant cutoff30, long headTo, boolean allowNativeHead, boolean initialFetch,
            String apiKey, KeyPacer pacer, int pageBudget, Instant deadline, AtomicBoolean degraded
    ) {
        String inboundQuery = "(to:" + self + " OR @" + self
                + (selfId != null && !selfId.isBlank() ? " OR quoted_user_id:" + selfId : "")
                + ") -from:" + self;
        return fillStreamAsync(
                baseUrl, searchRoots,
                baseUrl + "/twitter/user/mentions?userName=" + encodedUser,
                inboundQuery, null,
                coverage, targetCutoff, cutoff30, headTo, allowNativeHead, initialFetch,
                apiKey, pacer, pageBudget, deadline, degraded,
                node -> {
                    String rawDate = firstNonBlank(node.path("createdAt").asText(""), node.path("created_at").asText(""));
                    Instant ts = rawDate.isBlank() ? null : parseFlexibleTimestamp(rawDate);
                    InboundInteraction i = parseInbound(node, self, selfId);
                    return new Parsed(i, i != null ? i.createdAt() : ts, false);
                });
    }

    private record Parsed(Object item, Instant createdAt, boolean pinned, boolean valid) {
        Parsed(Object item, Instant createdAt, boolean pinned) {
            this(item, createdAt, pinned, true);
        }
    }

    private record CollectedBatch(Instant tail, boolean valid) {
    }

    private record StreamFill(List<Object> items, List<long[]> coverage, int pages, Map<String, Long> metricsObservedAt) {
    }

    private record StreamContext(
            String baseUrl,
            SearchReplyRoots searchRoots,
            String nativeUrl,
            String sliceQuery,
            String expectedAuthorId,
            Instant cutoff30,
            String apiKey,
            KeyPacer pacer,
            Instant deadline,
            AtomicBoolean degraded,
            Function<JsonNode, Parsed> parser,
            List<Object> items,
            Map<String, Long> metricsObservedAt,
            List<long[]> newCoverage,
            AtomicInteger pagesLeft,
            AtomicInteger pagesUsed,
            boolean initialFetch
    ) {
    }

    /**
     * 非阻塞分页与自适应并发时间分片补齐：
     * - 每一页 HTTP 请求独立投递至 {@link SharedApiExecutor}，不在工作线程内阻塞等待其它请求；
     * - 首页先做单次探测（稀疏账号 1 次请求即完成，绝不多花 API 额度）；
     * - 当探测确认该时间段存在多页高密度数据且线程池处于低峰空闲时，自动把剩余时间窗切分为多路子区间并行拉取（首次拉取并发翻倍）；
     * - 高峰期线程池自动将分片度收敛为 1，按请求粒度 FIFO 公平调度。
     */
    private CompletableFuture<StreamFill> fillStreamAsync(
            String baseUrl,
            SearchReplyRoots searchRoots,
            String nativeUrl,
            String sliceQuery,
            String expectedAuthorId,
            List<long[]> coverage,
            Instant targetCutoff,
            Instant cutoff30,
            long headTo,
            boolean allowNativeHead,
            boolean initialFetch,
            String apiKey,
            KeyPacer pacer,
            int pageBudget,
            Instant deadline,
            AtomicBoolean degraded,
            Function<JsonNode, Parsed> parser
    ) {
        long from = Math.max(targetCutoff.getEpochSecond(), cutoff30.getEpochSecond());
        List<long[]> gaps = AccountStore.missingRanges(coverage, from, headTo);
        if (gaps.isEmpty() || pageBudget <= 0) {
            return CompletableFuture.completedFuture(new StreamFill(List.of(), List.of(), 0, Map.of()));
        }
        StreamContext ctx = new StreamContext(
                baseUrl, searchRoots, nativeUrl, sliceQuery, expectedAuthorId, cutoff30, apiKey, pacer, deadline, degraded, parser,
                Collections.synchronizedList(new ArrayList<>()),
                new ConcurrentHashMap<>(),
                Collections.synchronizedList(new ArrayList<>()),
                new AtomicInteger(pageBudget),
                new AtomicInteger(0),
                initialFetch
        );
        return fillGapAtAsync(ctx, gaps, 0, headTo, allowNativeHead)
                .thenApply(v -> new StreamFill(new ArrayList<>(ctx.items()), new ArrayList<>(ctx.newCoverage()),
                        ctx.pagesUsed().get(), Map.copyOf(ctx.metricsObservedAt())));
    }

    private CompletableFuture<Void> fillGapAtAsync(
            StreamContext ctx,
            List<long[]> gaps,
            int idx,
            long headTo,
            boolean allowNativeHead
    ) {
        if (idx >= gaps.size() || !hasBudgetAndTime(ctx)) {
            return CompletableFuture.completedFuture(null);
        }
        long a = gaps.get(idx)[0];
        long b = gaps.get(idx)[1];
        boolean nativeHead = allowNativeHead && b >= headTo;

        CompletableFuture<Void> gapFuture = nativeHead
                ? runNativeHeadAsync(ctx, a, b, "", null, 0, ctx.pacer().freeTierSlowMode ? 1 : 2)
                : fillSearchRangeWithAdaptiveShardsAsync(ctx, a, b);

        return gapFuture.thenCompose(v -> {
            if (!AccountStore.missingRanges(new ArrayList<>(ctx.newCoverage()), a, b).isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return fillGapAtAsync(ctx, gaps, idx + 1, headTo, allowNativeHead);
        });
    }

    private CompletableFuture<Void> runNativeHeadAsync(
            StreamContext ctx,
            long a,
            long b,
            String cursor,
            Instant tailSoFar,
            int pageIdx,
            int maxNativePages
    ) {
        if (!tryAcquirePage(ctx)) {
            recordPartialIfAny(ctx, a, b, tailSoFar);
            return CompletableFuture.completedFuture(null);
        }
        String url = ctx.nativeUrl()
                + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return getJsonOptionalAsync(url, ctx.apiKey(), ctx.pacer(), ctx.degraded(), ctx.deadline(), ctx.searchRoots()).thenCompose(root -> {
            if (root.path("__degraded").asBoolean(false)) {
                recordPartialIfAny(ctx, a, b, tailSoFar);
                return CompletableFuture.completedFuture(null);
            }
            List<JsonNode> batch = extractArray(root, "tweets", "data");
            if (!hasArray(root, "tweets", "data") || invalidPagination(root, batch)) {
                ctx.degraded().set(true);
                return CompletableFuture.completedFuture(null);
            }
            CollectedBatch collected = collectBatchAndTail(ctx, batch, cursor.isEmpty(), tailSoFar);
            if (!collected.valid()) return CompletableFuture.completedFuture(null);
            Instant tail = collected.tail();
            if (tail != null && tail.getEpochSecond() <= a) {
                ctx.newCoverage().add(new long[]{a, b});
                return CompletableFuture.completedFuture(null);
            }
            String next = extractNextCursor(root);
            if (pageIdx + 1 < maxNativePages && !batch.isEmpty() && !next.isEmpty() && !next.equals(cursor)) {
                return runNativeHeadAsync(ctx, a, b, next, tail, pageIdx + 1, maxNativePages);
            }
            if (tail != null && tail.getEpochSecond() < b) {
                ctx.newCoverage().add(new long[]{Math.max(a, tail.getEpochSecond()), b});
            }
            long searchUpper = tail != null ? Math.min(b, tail.getEpochSecond()) : b;
            if (searchUpper <= a) {
                ctx.newCoverage().add(new long[]{a, b});
                return CompletableFuture.completedFuture(null);
            }
            return fillSearchRangeWithAdaptiveShardsAsync(ctx, a, searchUpper);
        });
    }

    private CompletableFuture<JsonNode> getSearchPageAsync(StreamContext ctx, String url, Set<String> seenCursors) {
        var result = getJsonOptionalAsync(url, ctx.apiKey(), ctx.pacer(), ctx.degraded(), ctx.deadline(), ctx.searchRoots());
        return handleComposeCancellable(result, (page, error) -> {
            if (error != null) return CompletableFuture.failedFuture(error);
            if (!includesFreeData(page)) return CompletableFuture.completedFuture(page);
            String next = extractNextCursor(page);
            boolean repeatsCursor = !next.isEmpty() && seenCursors.contains(next);
            if (!repeatsCursor && !hasConflictingSearchAuthor(ctx, page)) return CompletableFuture.completedFuture(page);
            // Restart the same window once. If both sources fail, preserve individually valid rows and the coverage gap.
            var paid = getPaidJsonOnceBeforeAsync(withoutFreeCursor(url), ctx.apiKey(), ctx.pacer(), ctx.deadline(),
                    ctx.degraded(), 10_000, false, null);
            return handleComposeCancellable(paid, (verified, failure) -> {
                if (failure != null) return CompletableFuture.failedFuture(failure);
                return CompletableFuture.completedFuture(hasArray(verified, "tweets", "data") ? verified : page);
            });
        });
    }

    private static boolean hasConflictingSearchAuthor(StreamContext ctx, JsonNode page) {
        if (ctx.expectedAuthorId() == null || ctx.expectedAuthorId().isBlank()) return false;
        for (JsonNode tweet : page.path("tweets")) {
            if (!ctx.expectedAuthorId().equals(tweet.path("author").path("id").asText())) return true;
        }
        return false;
    }

    private CompletableFuture<Void> fillSearchRangeWithAdaptiveShardsAsync(StreamContext ctx, long a, long b) {
        if (b <= a) {
            ctx.newCoverage().add(new long[]{a, b});
            return CompletableFuture.completedFuture(null);
        }
        if (!tryAcquirePage(ctx)) {
            return CompletableFuture.completedFuture(null);
        }
        String probeUrl = buildSearchUrl(ctx.baseUrl(), ctx.sliceQuery(), a, b + 1, "");
        return getSearchPageAsync(ctx, probeUrl, Set.of()).thenCompose(root -> {
            if (root.path("__degraded").asBoolean(false)) {
                return CompletableFuture.completedFuture(null);
            }
            List<JsonNode> batch = extractArray(root, "tweets", "data");
            if (!hasArray(root, "tweets", "data") || invalidPagination(root, batch)) {
                ctx.degraded().set(true);
                return CompletableFuture.completedFuture(null);
            }
            CollectedBatch collected = collectBatchAndTail(ctx, batch, false, null);
            if (!collected.valid()) return CompletableFuture.completedFuture(null);
            Instant probeTail = collected.tail();
            String next = extractNextCursor(root);

            // since_time is inclusive: a page ending at that second may have more rows on the next page.
            if ((probeTail != null && probeTail.getEpochSecond() < a) || batch.isEmpty() || next.isEmpty()) {
                ctx.newCoverage().add(new long[]{a, b});
                return CompletableFuture.completedFuture(null);
            }

            // 首页探测确认还有后续分页：低峰期按经验密度把剩余 [a, probeTail] 切分为多路并行分片（首次拉取并发翻倍）；高峰期自动保持 1 路续翻
            if (probeTail != null && probeTail.getEpochSecond() < b && probeTail.getEpochSecond() > a + 300) {
                long remB = probeTail.getEpochSecond();
                long page1Span = Math.max(60L, b - remB);
                long remSpan = remB - a;
                int estPages = (int) Math.min(64L, (remSpan + page1Span - 1) / page1Span);
                int effectiveEstPages = ctx.initialFetch() ? Math.min(64, Math.max(2, estPages * 2)) : estPages;
                int shards = sharedExecutor.suggestSliceConcurrency(
                        remSpan, effectiveEstPages, ctx.pagesLeft().get(), ctx.pacer().freeTierSlowMode, ctx.initialFetch());
                if (shards >= 2) {
                    ctx.newCoverage().add(new long[]{remB, b});
                    List<CompletableFuture<Void>> shardFutures = new ArrayList<>(shards);
                    // 从最新分片往更早分片依次入队，保证预算受限时最新连续区间优先完成
                    for (int i = shards - 1; i >= 0; i--) {
                        long subA = a + (remSpan * i) / shards;
                        long subB = (i == shards - 1) ? remB : (a + (remSpan * (i + 1)) / shards);
                        if (subB > subA) {
                            shardFutures.add(runSearchSliceLoopAsync(ctx, subA, subB, subB + 1, "", null, new HashSet<>()));
                        }
                    }
                    return CompletableFuture.allOf(shardFutures.toArray(CompletableFuture[]::new));
                }
            }
            return runSearchSliceLoopAsync(ctx, a, b, b + 1, next, probeTail, new HashSet<>());
        });
    }

    private CompletableFuture<Void> runSearchSliceLoopAsync(
            StreamContext ctx,
            long subA,
            long subB,
            long untilExclusive,
            String cursor,
            Instant tailSoFar,
            Set<String> seenCursors
    ) {
        // Cursors belong to this query window; parallel shards must keep independent histories.
        if (!seenCursors.add(cursor)) {
            ctx.degraded().set(true);
            recordPartialIfAny(ctx, subA, subB, tailSoFar);
            return CompletableFuture.completedFuture(null);
        }
        if (!tryAcquirePage(ctx)) {
            recordPartialIfAny(ctx, subA, subB, tailSoFar);
            return CompletableFuture.completedFuture(null);
        }
        String url = buildSearchUrl(ctx.baseUrl(), ctx.sliceQuery(), subA, untilExclusive, cursor);
        return getSearchPageAsync(ctx, url, seenCursors).thenCompose(root -> {
            if (root.path("__degraded").asBoolean(false)) {
                recordPartialIfAny(ctx, subA, subB, tailSoFar);
                return CompletableFuture.completedFuture(null);
            }
            List<JsonNode> batch = extractArray(root, "tweets", "data");
            if (!hasArray(root, "tweets", "data") || invalidPagination(root, batch)) {
                ctx.degraded().set(true);
                return CompletableFuture.completedFuture(null);
            }
            CollectedBatch collected = collectBatchAndTail(ctx, batch, false, tailSoFar);
            if (!collected.valid()) return CompletableFuture.completedFuture(null);
            Instant tail = collected.tail();
            String next = extractNextCursor(root);
            if (!next.isEmpty() && seenCursors.contains(next)) {
                ctx.degraded().set(true);
                recordPartialIfAny(ctx, subA, subB, tailSoFar);
                return CompletableFuture.completedFuture(null);
            }
            if ((tail != null && tail.getEpochSecond() < subA)
                    || batch.isEmpty()
                    || next.isEmpty()) {
                ctx.newCoverage().add(new long[]{subA, subB});
                return CompletableFuture.completedFuture(null);
            }
            return runSearchSliceLoopAsync(ctx, subA, subB, untilExclusive, next, tail, seenCursors);
        });
    }

    private static String buildSearchUrl(String baseUrl, String sliceQuery, long sinceInclusive, long untilExclusive, String cursor) {
        return baseUrl + "/twitter/tweet/advanced_search?queryType=Latest&query="
                + URLEncoder.encode(sliceQuery + " since_time:" + sinceInclusive + " until_time:" + untilExclusive, StandardCharsets.UTF_8)
                + (cursor == null || cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }

    private static CollectedBatch collectBatchAndTail(
            StreamContext ctx,
            List<JsonNode> batch,
            boolean nativeTopPage,
            Instant tailSoFar
    ) {
        Instant tail = tailSoFar;
        boolean valid = true;
        long observedAt = Instant.now().getEpochSecond();
        for (int idx = 0; idx < batch.size(); idx++) {
            if (parseFlexibleTimestamp(firstNonBlank(batch.get(idx).path("createdAt").asText(""),
                    batch.get(idx).path("created_at").asText(""))) == null) {
                ctx.degraded().set(true);
                valid = false;
                continue;
            }
            Parsed p = ctx.parser().apply(batch.get(idx));
            if (p == null) {
                continue;
            }
            if (!p.valid()) {
                ctx.degraded().set(true);
                valid = false;
                continue;
            }
            if (p.item() != null && p.createdAt() != null && !p.createdAt().isBefore(ctx.cutoff30())) {
                synchronized (ctx.items()) {
                    ctx.items().add(p.item());
                    if (p.item() instanceof RawTweet tweet) ctx.metricsObservedAt().put(tweet.id(), observedAt(batch.get(idx), observedAt));
                }
            }
            boolean pinnedLike = p.pinned()
                    || (nativeTopPage && idx == 0 && batch.size() > 1 && isOlderThanNext(batch, ctx.parser()));
            if (!pinnedLike && p.createdAt() != null && (tail == null || p.createdAt().isBefore(tail))) {
                tail = p.createdAt();
            }
        }
        return new CollectedBatch(tail, valid);
    }

    private static void recordPartialIfAny(StreamContext ctx, long a, long b, Instant tail) {
        // A failed continuation at the inclusive lower-bound second has not covered the whole window.
        if (tail != null && tail.getEpochSecond() > a && tail.getEpochSecond() < b) {
            ctx.newCoverage().add(new long[]{Math.max(a, tail.getEpochSecond()), b});
        }
    }

    private static boolean hasBudgetAndTime(StreamContext ctx) {
        return !ctx.degraded().get()
                && ctx.pagesLeft().get() > 0
                && (ctx.pagesUsed().get() == 0 || !Instant.now().isAfter(ctx.deadline()));
    }

    private static boolean tryAcquirePage(StreamContext ctx) {
        if (!hasBudgetAndTime(ctx)) {
            return false;
        }
        while (true) {
            int cur = ctx.pagesLeft().get();
            if (cur <= 0) {
                return false;
            }
            if (ctx.pagesLeft().compareAndSet(cur, cur - 1)) {
                ctx.pagesUsed().incrementAndGet();
                return true;
            }
        }
    }

    private static boolean isOlderThanNext(List<JsonNode> batch, Function<JsonNode, Parsed> parser) {
        Parsed first = parser.apply(batch.get(0));
        Parsed second = parser.apply(batch.get(1));
        return first != null && second != null && first.createdAt() != null && second.createdAt() != null
                && first.createdAt().isBefore(second.createdAt());
    }

    private KeyPacer pacerFor(String apiKey) {
        if (pacersByKey.size() > 64) {
            pacersByKey.clear();
        }
        return pacersByKey.computeIfAbsent(apiKey, k -> new KeyPacer());
    }

    private boolean isLocalMockBaseUrl() {
        String b = properties.getTwitterapiIoBaseUrl();
        return b != null && (b.contains("127.0.0.1") || b.contains("localhost"));
    }

    private record PagedUserResult(
            List<XUserSummary> users,
            Set<String> explicitFollowedByKeys,
            boolean truncated,
            boolean failed,
            Map<String, Integer> orderById
    ) {
        private PagedUserResult {
            orderById = orderById != null ? Map.copyOf(orderById) : Map.of();
        }

        // Only direct followers/followings pagination has the provider's documented newest-first order.
        private PagedUserResult(List<XUserSummary> users, Set<String> explicitFollowedByKeys,
                                boolean truncated, boolean failed) {
            this(users, explicitFollowedByKeys, truncated, failed, listedOrder(users));
        }

        private static Map<String, Integer> listedOrder(List<XUserSummary> users) {
            Map<String, Integer> order = new LinkedHashMap<>();
            for (int i = 0; i < users.size(); i++) {
                XUserSummary user = users.get(i);
                if (user != null && user.id() != null && !user.id().isBlank()) order.putIfAbsent(user.id(), i);
            }
            return order;
        }
    }

    private CompletableFuture<PagedUserResult> fetchFollowersAsync(
            String baseUrl, String encodedUser, String selfId, String apiKey, KeyPacer pacer,
            int maxPages, int expectedTotal, Instant deadline, AtomicBoolean degraded) {
        return fetchPagedUsersAsync(baseUrl + "/twitter/user/followers?userName=" + encodedUser + "&pageSize=200",
                apiKey, pacer, maxPages, expectedTotal, "followers", deadline, degraded).thenCompose(listed -> {
            if (listed.failed() || listed.truncated() || listed.users().size() >= expectedTotal
                    || selfId == null || selfId.isBlank() || !Instant.now().isBefore(deadline)) {
                return CompletableFuture.completedFuture(listed);
            }
            return supplementFollowersAsync(baseUrl, selfId, listed, expectedTotal, apiKey, pacer, deadline, degraded);
        });
    }

    private CompletableFuture<PagedUserResult> supplementFollowersAsync(
            String baseUrl, String selfId, PagedUserResult listed, int expectedTotal,
            String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded) {
        PagedUserResult incomplete = new PagedUserResult(listed.users(), listed.explicitFollowedByKeys(),
                true, false, listed.orderById());
        String idsUrl = baseUrl + "/twitter/user/followers_ids?userId="
                + URLEncoder.encode(selfId, StandardCharsets.UTF_8) + "&count=5000";
        return getJsonOnceBeforeAsync(idsUrl, apiKey, pacer, deadline, degraded).thenCompose(idsRoot -> {
            if (idsRoot == null || !"success".equalsIgnoreCase(idsRoot.path("status").asText())
                    || !idsRoot.path("ids").isArray() || !idsRoot.path("has_next_page").isBoolean()
                    || idsRoot.path("has_next_page").asBoolean()
                    || !Set.of("", "0").contains(idsRoot.path("next_cursor").asText(""))) {
                return CompletableFuture.completedFuture(incomplete);
            }
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (JsonNode node : idsRoot.path("ids")) {
                if (!node.isTextual() || node.asText().isBlank()) return CompletableFuture.completedFuture(incomplete);
                ids.add(node.asText());
            }
            Set<String> knownIds = new HashSet<>();
            Set<String> handles = new HashSet<>();
            listed.users().forEach(user -> {
                knownIds.add(user.id());
                handles.add(user.username().toLowerCase(Locale.ROOT));
            });
            Set<String> missing = new java.util.LinkedHashSet<>(ids);
            missing.removeAll(knownIds);
            if (missing.isEmpty() || missing.size() > 20 || !Instant.now().isBefore(deadline)) {
                return CompletableFuture.completedFuture(incomplete);
            }
            String batchUrl = baseUrl + "/twitter/user/batch_info_by_ids?userIds="
                    + URLEncoder.encode(String.join(",", missing), StandardCharsets.UTF_8);
            return getJsonOnceBeforeAsync(batchUrl, apiKey, pacer, deadline, degraded).thenApply(usersRoot -> {
                if (usersRoot == null || !"success".equalsIgnoreCase(usersRoot.path("status").asText())
                        || !usersRoot.path("users").isArray()) return incomplete;
                List<XUserSummary> users = new ArrayList<>(listed.users());
                for (JsonNode node : usersRoot.path("users")) {
                    if (node.path("unavailable").asBoolean(false)) continue;
                    XUserSummary user = parseUserNode(node, "");
                    if (!missing.contains(user.id()) || user.username().isBlank() || knownIds.contains(user.id())
                            || !handles.add(user.username().toLowerCase(Locale.ROOT))) continue;
                    knownIds.add(user.id());
                    users.add(user);
                }
                boolean complete = ids.size() == expectedTotal && knownIds.equals(ids);
                // Batch-info additions prove membership but carry no follow-order provenance.
                return new PagedUserResult(users, listed.explicitFollowedByKeys(), !complete, false, listed.orderById());
            });
        });
    }

    /** Supplemental reads are single attempts; admission always uses the original sync deadline. */
    private CompletableFuture<JsonNode> getJsonOnceBeforeAsync(
            String url, String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded) {
        return getJsonOnceBeforeAsync(url, apiKey, pacer, deadline, degraded, 10_000, true);
    }

    private CompletableFuture<JsonNode> getJsonOnceBeforeAsync(
            String url, String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded,
            long requestTimeoutMs, boolean finishBeforeDeadline) {
        return getJsonOnceBeforeAsync(url, apiKey, pacer, deadline, degraded, requestTimeoutMs, finishBeforeDeadline, null);
    }

    private CompletableFuture<JsonNode> getJsonOnceBeforeAsync(
            String url, String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded,
            long requestTimeoutMs, boolean finishBeforeDeadline, AtomicBoolean requestStarted) {
        return getJsonOnceBeforeAsync(url, apiKey, pacer, deadline, degraded, requestTimeoutMs, finishBeforeDeadline, requestStarted, null);
    }

    private CompletableFuture<JsonNode> getJsonOnceBeforeAsync(
            String url, String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded,
            long requestTimeoutMs, boolean finishBeforeDeadline, AtomicBoolean requestStarted, AccountStore.StoredAccount metricStore) {
        if (useFreeSource(url)) {
            return handleComposeCancellable(fxTwitterClient.fetchAsync(url, deadline.minusMillis(PAID_FALLBACK_RESERVE_MS), requestStarted),
                    (free, error) -> {
                        if (error != null) {
                            logFreeFallback(url, error);
                            return getPaidJsonOnceBeforeAsync(withoutFreeCursor(url), apiKey, pacer, deadline,
                                    degraded, requestTimeoutMs, finishBeforeDeadline, requestStarted);
                        }
                        Set<String> disputed = rejectConflictingMetrics(free, metricStore);
                        List<String> missing = jsonIds(free, "__incomplete_ids");
                        if (missing.isEmpty()) return CompletableFuture.completedFuture(free);
                        if (!Instant.now().isBefore(deadline)) {
                            degraded.set(true);
                            return CompletableFuture.completedFuture(free);
                        }
                        String missingUrl = trimTrailingSlash(properties.getTwitterapiIoBaseUrl())
                                + "/twitter/tweets?tweet_ids="
                                + URLEncoder.encode(String.join(",", missing), StandardCharsets.UTF_8);
                        AtomicBoolean paidStarted = new AtomicBoolean(false);
                        log.info("x_read_fallback source=fxtwitter target=twitterapi_io path=/twitter/tweets missing={}", missing.size());
                        return handleComposeCancellable(getPaidJsonOnceBeforeAsync(missingUrl, apiKey, pacer, deadline, degraded,
                                requestTimeoutMs, finishBeforeDeadline, paidStarted), (paid, paidError) -> {
                                    if (paidError != null) degraded.set(true);
                                    if (paidStarted.get() && requestStarted != null) requestStarted.set(true);
                                    return CompletableFuture.completedFuture(
                                            mergeFreeMetricResults(free, paid, missing, disputed, paidStarted.get(), degraded));
                                });
                    });
        }
        return getPaidJsonOnceBeforeAsync(url, apiKey, pacer, deadline, degraded,
                requestTimeoutMs, finishBeforeDeadline, requestStarted);
    }

    private CompletableFuture<JsonNode> getPaidJsonOnceBeforeAsync(
            String url, String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded,
            long requestTimeoutMs, boolean finishBeforeDeadline, AtomicBoolean requestStarted) {
        CompletableFuture<JsonNode> request = sharedExecutor.submitAsync(() -> {
            long waitMs = pacer.reserveWaitMsBefore(isLocalMockBaseUrl(), deadline.toEpochMilli());
            if (waitMs < 0) return null;
            if (waitMs > 0) Thread.sleep(waitMs);
            long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
            if (remainingMs <= 0) return null;
            budgetGuard.acquirePermit(url, "twitterapi_io_read");
            remainingMs = Duration.between(Instant.now(), deadline).toMillis();
            if (Thread.currentThread().isInterrupted() || remainingMs <= 0) {
                degraded.set(true);
                return null;
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(finishBeforeDeadline ? Math.min(requestTimeoutMs, remainingMs) : requestTimeoutMs))
                    .header("X-API-Key", apiKey).header("Accept", "application/json").GET().build();
            if (requestStarted != null) requestStarted.set(true);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) pacer.onRateLimited429(isLocalMockBaseUrl());
            return parseValidatedResponse(response, pacer);
        });
        return handleComposeCancellable(request, (root, ex) -> {
            if (ex == null) return CompletableFuture.completedFuture(root);
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof XDataProviderException xe && (xe.getStatusCode() == 401 || xe.getStatusCode() == 402)) throw xe;
            degraded.set(true);
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<PagedUserResult> fetchPagedUsersAsync(
            String baseEndpointUrl,
            String apiKey,
            KeyPacer pacer,
            int maxPages,
            int expectedTotalHint,
            String primaryKey,
            Instant deadline,
            AtomicBoolean partialDegraded
    ) {
        List<XUserSummary> all = Collections.synchronizedList(new ArrayList<>());
        Set<String> seenUsers = Collections.synchronizedSet(new HashSet<>());
        Set<String> explicitFollowedBy = Collections.synchronizedSet(new HashSet<>());
        return stepPagedUsersAsync(
                baseEndpointUrl, apiKey, pacer, maxPages, expectedTotalHint, primaryKey,
                deadline, partialDegraded, all, seenUsers, explicitFollowedBy, "", 0
        );
    }

    private CompletableFuture<PagedUserResult> stepPagedUsersAsync(
            String baseEndpointUrl,
            String apiKey,
            KeyPacer pacer,
            int maxPages,
            int expectedTotalHint,
            String primaryKey,
            Instant deadline,
            AtomicBoolean partialDegraded,
            List<XUserSummary> all,
            Set<String> seenUsers,
            Set<String> explicitFollowedBy,
            String cursor,
            int page
    ) {
        if (page >= maxPages) {
            return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all), new HashSet<>(explicitFollowedBy), true, false));
        }
        if (page > 0 && Instant.now().isAfter(deadline)) {
            return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all), new HashSet<>(explicitFollowedBy), true, false));
        }
        String url = baseEndpointUrl
                + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return getJsonOptionalAsync(url, apiKey, pacer, partialDegraded).thenCompose(root -> {
            if (root.path("__degraded").asBoolean(false)) {
                return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all), new HashSet<>(explicitFollowedBy), true, true));
            }
            List<JsonNode> batch = extractArray(root, primaryKey, "users", "data");
            if (!hasArray(root, primaryKey, "users", "data") || invalidPagination(root, batch)) {
                partialDegraded.set(true);
                return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all),
                        new HashSet<>(explicitFollowedBy), true, true));
            }
            if (batch.isEmpty()) {
                return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all),
                        new HashSet<>(explicitFollowedBy), !extractNextCursor(root).isEmpty(), false));
            }
            for (JsonNode uNode : batch) {
                XUserSummary u = parseUserNode(uNode, "");
                if (u.id().isBlank() || u.username().isBlank()) continue;
                String uKey = u.username().toLowerCase(Locale.ROOT);
                // 身份按稳定 ID 去重；存储字典以 handle 为键，同名记录也不能重复撑满资料人数。
                if (!seenUsers.add("id:" + u.id()) || !seenUsers.add("handle:" + uKey)) continue;
                all.add(u);
                if (uNode.path("followed_by").asBoolean(false)
                        || uNode.path("is_followed_by").asBoolean(false)
                        || uNode.path("followedBy").asBoolean(false)) {
                    explicitFollowedBy.add(uKey);
                }
            }
            String nextCursor = extractNextCursor(root);
            if (nextCursor.isEmpty() || nextCursor.equals(cursor)) {
                return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all), new HashSet<>(explicitFollowedBy), !nextCursor.isEmpty(), false));
            }
            if (pacer.freeTierSlowMode || page == maxPages - 1) {
                return CompletableFuture.completedFuture(new PagedUserResult(new ArrayList<>(all), new HashSet<>(explicitFollowedBy), true, false));
            }
            return stepPagedUsersAsync(
                    baseEndpointUrl, apiKey, pacer, maxPages, expectedTotalHint, primaryKey,
                    deadline, partialDegraded, all, seenUsers, explicitFollowedBy, nextCursor, page + 1
            );
        });
    }

    private String extractNextCursor(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return "";
        }
        boolean hasNextRoot = root.path("has_next_page").asBoolean(false)
                || root.path("has_more").asBoolean(false);
        boolean hasNextData = root.path("data").path("has_next_page").asBoolean(false)
                || root.path("data").path("next_cursor").asBoolean(false)
                || root.path("data").path("has_more").asBoolean(false);
        String next = firstNonBlank(
                root.path("next_cursor").asText(""),
                root.path("data").path("next_cursor").asText("")
        );
        if ((hasNextRoot || hasNextData) && !next.isBlank() && !"0".equals(next)) {
            return next;
        }
        return "";
    }

    private boolean invalidPagination(JsonNode root, List<JsonNode> batch) {
        boolean more = root.path("has_next_page").asBoolean(false) || root.path("has_more").asBoolean(false)
                || root.path("data").path("has_next_page").asBoolean(false)
                || root.path("data").path("has_more").asBoolean(false);
        return more && (batch.isEmpty() || extractNextCursor(root).isBlank());
    }

    private JsonNode getJsonWithRetry(String url, String apiKey, KeyPacer pacer) {
        try {
            return getJsonWithRetryAsync(url, apiKey, pacer).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new XDataProviderException(502, "连接 TwitterAPI.io 失败：" + ce.getMessage(), ce);
        }
    }

    private CompletableFuture<JsonNode> getJsonWithRetryAsync(String url, String apiKey, KeyPacer pacer) {
        return getJsonWithRetryAsync(url, apiKey, pacer, Instant.now().plusSeconds(SNAPSHOT_SOFT_DEADLINE_SECONDS), SearchReplyRoots.empty());
    }

    private CompletableFuture<JsonNode> getJsonWithRetryAsync(String url, String apiKey, KeyPacer pacer, Instant deadline, SearchReplyRoots searchRoots) {
        if (useFreeSource(url)) {
            return handleComposeCancellable(fxTwitterClient.fetchAsync(url, deadline.minusMillis(PAID_FALLBACK_RESERVE_MS), null, searchRoots), (root, error) -> {
                if (error == null) return CompletableFuture.completedFuture(root);
                logFreeFallback(url, error);
                if (!Instant.now().isBefore(deadline)) {
                    return CompletableFuture.<JsonNode>failedFuture(new XDataProviderException(504,
                            "免费数据读取超时，已保留原数据，请稍后继续更新"));
                }
                return attemptGetJsonAsync(withoutFreeCursor(url), apiKey, pacer, isLocalMockBaseUrl(), 0);
            });
        }
        return attemptGetJsonAsync(url, apiKey, pacer, isLocalMockBaseUrl(), 0);
    }

    /** Keep cancellation attached to the active request, including a newly scheduled fallback or retry. */
    static <T, U> CompletableFuture<U> handleComposeCancellable(
            CompletableFuture<T> source,
            BiFunction<? super T, Throwable, CompletableFuture<U>> transition) {
        CompletableFuture<U> result = new CompletableFuture<>();
        Object gate = new Object();
        CompletableFuture<?>[] active = {source}; // Guarded by gate across the source-to-fallback transition.
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                final CompletableFuture<?> request;
                synchronized (gate) {
                    request = active[0];
                }
                request.cancel(true);
            }
        });
        source.whenComplete((value, error) -> {
            final CompletableFuture<U> next;
            try {
                synchronized (gate) {
                    if (result.isDone()) return;
                    next = isCancellation(error) ? CompletableFuture.failedFuture(error) : transition.apply(value, error);
                    active[0] = next;
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                return;
            }
            // cancel() can mark the result while waiting for this transition's gate.
            // Complete/cancel children outside the gate to avoid nested chain lock inversion.
            if (result.isCancelled()) {
                next.cancel(true);
                return;
            }
            next.whenComplete((mapped, failure) -> {
                if (failure == null) result.complete(mapped);
                else if (isCancellation(failure)) result.cancel(true);
                else result.completeExceptionally(failure);
            });
        });
        return result;
    }

    private static boolean isCancellation(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof CancellationException;
    }

    private boolean useFreeSource(String url) {
        // 旧的本地模拟端点不能因启用默认配置而发出真实网络请求；免费模拟端点须显式配置。
        return fxTwitterClient.supports(url)
                && (!isLocalMockBaseUrl() || isLoopbackUrl(properties.getFxtwitterBaseUrl()));
    }

    private static boolean isLoopbackUrl(String value) {
        try {
            String host = URI.create(value).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String withoutFreeCursor(String url) {
        int question = url.indexOf('?');
        if (question < 0) return url;
        List<String> query = new ArrayList<>();
        for (String part : url.substring(question + 1).split("&")) {
            String[] pair = part.split("=", 2);
            if ("cursor".equals(pair[0]) && pair.length == 2
                    && URLDecoder.decode(pair[1], StandardCharsets.UTF_8).startsWith("fx:")) continue;
            query.add(part);
        }
        return url.substring(0, question) + (query.isEmpty() ? "" : "?" + String.join("&", query));
    }

    private static void logFreeFallback(String url, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        // 只记端点和错误类别，查询文本、密钥与用户正文不写入日志。
        log.info("x_read_fallback source=fxtwitter target=twitterapi_io path={} reason={}",
                URI.create(url).getPath(), cause.getClass().getSimpleName());
    }

    private static boolean includesFreeData(JsonNode root) {
        return root != null && root.path("_source").asText("").startsWith("fxtwitter");
    }

    private static List<String> jsonIds(JsonNode root, String name) {
        if (root == null || !root.path(name).isArray()) return List.of();
        List<String> ids = new ArrayList<>();
        for (JsonNode id : root.path(name)) if (id.isTextual() && !id.asText().isBlank()) ids.add(id.asText());
        return ids;
    }

    private Set<String> rejectConflictingMetrics(JsonNode free, AccountStore.StoredAccount existing) {
        if (existing == null || free.path("tweets").isEmpty()) return Set.of();
        Set<String> disputed = new LinkedHashSet<>();
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode row : free.path("tweets")) {
            String id = row.path("id").asText();
            byId.put(id, row);
            if (!matchesTweetAuthor(parseUserNode(row.path("author"), ""), existing.profile())) disputed.add(id);
        }
        for (AccountStore.StoredTweet previous : existing.tweets()) {
            JsonNode row = byId.get(previous.id());
            if (row != null && !matchesStoredMetric(previous, parseFlexibleTimestamp(row.path("createdAt").asText()),
                    row.path("inReplyToId").asText(""))) disputed.add(previous.id());
        }
        if (!disputed.isEmpty()) {
            var rows = free.path("tweets").elements();
            while (rows.hasNext()) {
                if (disputed.contains(rows.next().path("id").asText())) rows.remove();
            }
            var incomplete = ((com.fasterxml.jackson.databind.node.ObjectNode) free).withArray("__incomplete_ids");
            disputed.forEach(incomplete::add);
        }
        return disputed;
    }

    private JsonNode mergeFreeMetricResults(JsonNode free, JsonNode paid, List<String> requested, Set<String> disputed,
            boolean paidStarted, AtomicBoolean degraded) {
        var merged = ((com.fasterxml.jackson.databind.node.ObjectNode) free).deepCopy();
        Set<String> pending = new LinkedHashSet<>(requested);
        Set<String> attempts = new LinkedHashSet<>(jsonIds(free, "__attempted_ids"));
        if (paidStarted) attempts.addAll(requested);
        var tweets = merged.withArray("tweets");
        boolean paidBatchComplete = paidStarted && hasArray(paid, "tweets", "data");
        for (JsonNode tweet : extractArray(paid, "tweets", "data")) {
            if (pending.remove(tweet.path("id").asText(""))) tweets.add(tweet);
            else paidBatchComplete = false;
        }
        merged.put("_source", "fxtwitter+twitterapi_io");
        var attempted = merged.putArray("__attempted_ids");
        attempts.forEach(attempted::add);
        var confirmedDeleted = merged.putArray("__confirmed_deleted_ids");
        // Only the original paid provider's successful batch can apply its existing absence policy.
        // Free 404s, failed/unsent paid requests and malformed responses remain unconfirmed.
        if (paidBatchComplete) {
            // A conflicting live row is evidence of a disagreement, even when the paid list is empty.
            for (String id : pending) if (!disputed.contains(id)) confirmedDeleted.add(id);
            pending.retainAll(disputed);
        }
        var incomplete = merged.putArray("__incomplete_ids");
        pending.forEach(incomplete::add);
        if (!pending.isEmpty()) degraded.set(true);
        return merged;
    }

    private CompletableFuture<JsonNode> attemptGetJsonAsync(
            String url,
            String apiKey,
            KeyPacer pacer,
            boolean localMock,
            int attempt
    ) {
        CompletableFuture<HttpResponse<String>> request = sharedExecutor.submitAsync(() -> {
            pacer.acquireSlot(localMock);
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-API-Key", apiKey)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                budgetGuard.acquirePermit(url, "twitterapi_io_read");
                if (Thread.currentThread().isInterrupted()) {
                    throw new XDataProviderException(503, "外部请求已中止，未发送付费请求");
                }
                return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (XDataProviderException e) {
                throw e;
            } catch (Exception e) {
                throw new XDataProviderException(502, "连接 TwitterAPI.io 失败：" + e.getMessage(), e);
            }
        });
        return handleComposeCancellable(request, (resp, error) -> {
            if (error != null) return CompletableFuture.failedFuture(error);
            if (resp.statusCode() == 429) {
                pacer.onRateLimited429(localMock);
                if (attempt < MAX_429_RETRIES) {
                    return attemptGetJsonAsync(url, apiKey, pacer, localMock, attempt + 1);
                }
                return CompletableFuture.failedFuture(new XDataProviderException(
                        429, "TwitterAPI.io 触发速率限制（免费试用档限每 5 秒 1 次请求），已排队重试仍繁忙，请隔 6 秒再试"));
            }
            try {
                return CompletableFuture.completedFuture(parseValidatedResponse(resp, pacer));
            } catch (Throwable ex) {
                return CompletableFuture.failedFuture(ex);
            }
        });
    }

    private JsonNode parseValidatedResponse(HttpResponse<String> resp, KeyPacer pacer) throws Exception {
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new XDataProviderException(401, "TwitterAPI.io 密钥无效或未授权（HTTP " + resp.statusCode() + "）");
        }
        if (resp.statusCode() == 402) {
            throw new XDataProviderException(402, "TwitterAPI.io 账户免费/充值 Credits 额度已用尽（HTTP 402）");
        }
        if (resp.statusCode() == 404) {
            throw new XDataProviderException(404, "请求的 X 账号不存在或处于私密锁推状态");
        }
        if (resp.statusCode() >= 400) {
            throw new XDataProviderException(502, "上游 TwitterAPI.io 返回异常状态码 HTTP " + resp.statusCode());
        }
        JsonNode json = objectMapper.readTree(resp.body());
        if ("error".equalsIgnoreCase(json.path("status").asText(""))) {
            String msg = firstNonBlank(json.path("message").asText(""), json.path("msg").asText("上游接口报错"));
            throw new XDataProviderException(400, "TwitterAPI.io 返回错误：" + msg);
        }
        pacer.onSuccess200();
        return json;
    }

    private CompletableFuture<JsonNode> getJsonOptionalAsync(
            String url,
            String apiKey,
            KeyPacer pacer,
            AtomicBoolean partialDegraded
    ) {
        return getJsonOptionalAsync(url, apiKey, pacer, partialDegraded,
                Instant.now().plusSeconds(SNAPSHOT_SOFT_DEADLINE_SECONDS), SearchReplyRoots.empty());
    }

    private CompletableFuture<JsonNode> getJsonOptionalAsync(
            String url, String apiKey, KeyPacer pacer, AtomicBoolean partialDegraded, Instant deadline, SearchReplyRoots searchRoots) {
        return handleComposeCancellable(getJsonWithRetryAsync(url, apiKey, pacer, deadline, searchRoots), (root, ex) -> {
            if (ex == null) return CompletableFuture.completedFuture(root);
            Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
            if (cause instanceof XDataProviderException xe && (xe.getStatusCode() == 401 || xe.getStatusCode() == 402)) {
                throw xe;
            }
            if (partialDegraded != null) {
                partialDegraded.set(true);
            }
            com.fasterxml.jackson.databind.node.ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("__degraded", true);
            return CompletableFuture.completedFuture(fallback);
        });
    }

    private List<JsonNode> extractArray(JsonNode root, String... candidatePaths) {
        List<JsonNode> result = new ArrayList<>();
        if (root == null || root.isMissingNode()) {
            return result;
        }
        if (root.isArray()) {
            root.forEach(result::add);
            return result;
        }
        for (String path : candidatePaths) {
            JsonNode direct = root.get(path);
            if (direct != null && direct.isArray()) {
                direct.forEach(result::add);
                return result;
            }
            JsonNode underData = root.path("data").get(path);
            if (underData != null && underData.isArray()) {
                underData.forEach(result::add);
                return result;
            }
        }
        return result;
    }

    private static boolean hasArray(JsonNode root, String... candidatePaths) {
        if (root == null) return false;
        if (root.isArray()) return true;
        for (String path : candidatePaths) {
            if (root.path(path).isArray() || root.path("data").path(path).isArray()) return true;
        }
        return false;
    }

    private static Set<String> unavailablePublicMetrics(JsonNode node) {
        Set<String> unavailable = new HashSet<>();
        String[][] fields = {{"views", "viewCount"}, {"likes", "likeCount"}, {"replies", "replyCount"},
                {"reposts", "retweetCount", "quoteCount"}, {"bookmarks", "bookmarkCount"}};
        for (String[] metric : fields) {
            for (int i = 1; i < metric.length; i++) {
                if (!node.path(metric[i]).isIntegralNumber() || node.path(metric[i]).asLong() < 0) unavailable.add(metric[0]);
            }
        }
        return unavailable;
    }

    private XUserSummary parseUserNode(JsonNode node, String fallbackUsername) {
        String uname = firstNonBlank(
                node.path("userName").asText(""),
                node.path("username").asText(""),
                node.path("screen_name").asText(""),
                fallbackUsername
        );
        String name = firstNonBlank(node.path("name").asText(""), uname);
        String avatar = firstNonBlank(
                node.path("profilePicture").asText(""),
                node.path("profile_image_url_https").asText("")
        ).replace("_normal.", "_400x400.");
        if (avatar.startsWith("https://pbs.twimg.com/profile_images/")) {
            avatar = avatar.replace("_200x200.", "_400x400.");
        }
        String banner = firstNonBlank(
                node.path("coverPicture").asText(""),
                node.path("profile_banner_url").asText(""),
                node.path("profileBannerUrl").asText("")
        );
        String bio = firstNonBlank(
                node.path("biography").asText(""),
                node.path("description").asText(""),
                node.path("bio").asText("")
        );
        boolean hasExplicitBlueField = node.has("isBlueVerified")
                || node.has("blue_verified")
                || node.has("is_blue_verified")
                || node.has("ext_is_blue_verified");
        boolean parsedBlue = node.path("isBlueVerified").asBoolean(false)
                || node.path("blue_verified").asBoolean(false)
                || node.path("is_blue_verified").asBoolean(false)
                || node.path("ext_is_blue_verified").asBoolean(false)
                || node.path("verified").asBoolean(false);
        String uid = node.path("id").asText("");
        long nowSec = Instant.now().getEpochSecond();
        boolean verified;
        if (hasExplicitBlueField || parsedBlue) {
            verified = blueVerifiedCache.observeAuthoritative(uid, uname, parsedBlue, nowSec);
        } else {
            Boolean cached = blueVerifiedCache.resolveValid(uid, uname, nowSec);
            verified = cached != null ? cached : false;
        }
        int followers = firstPositiveInt(node, "followers", "followers_count");
        int following = firstPositiveInt(node, "following", "following_count", "friends_count");
        int statuses = firstPositiveInt(node, "statusesCount", "statuses_count", "tweet_count");
        String created = node.path("createdAt").asText("");
        return new XUserSummary(
                uid,
                uname,
                name,
                avatar,
                bio,
                verified,
                followers,
                following,
                statuses,
                created,
                banner
        );
    }

    private List<String> collectUncachedInteractionUserIds(AccountStore.StoredAccount merged, long nowSec, int limit, long activityCutoff) {
        if (merged == null || merged.users() == null || merged.users().isEmpty()) {
            return List.of();
        }
        Set<String> peerHandles = new java.util.LinkedHashSet<>();
        if (merged.tweets() != null) {
            for (AccountStore.StoredTweet t : merged.tweets()) {
                if (t.createdAt() < activityCutoff || (activityCutoff != Long.MIN_VALUE && t.createdAt() > nowSec)) continue;
                if (t.target() != null && !t.target().isBlank()) {
                    peerHandles.add(t.target().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (merged.inbound() != null) {
            for (AccountStore.StoredInbound i : merged.inbound()) {
                if (i.createdAt() < activityCutoff || (activityCutoff != Long.MIN_VALUE && i.createdAt() > nowSec)) continue;
                if (i.peer() != null && !i.peer().isBlank()) {
                    peerHandles.add(i.peer().toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (String h : peerHandles) {
            AccountStore.StoredUser u = merged.users().get(h);
            if (u == null || u.id() == null || u.id().isBlank()) {
                continue;
            }
            if (!u.verified() && u.verifiedAt() == 0L
                    && blueVerifiedCache.resolveValid(u.id(), h, nowSec) == null) {
                ids.add(u.id());
                if (ids.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static int firstPositiveInt(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isNumber()) {
                return v.asInt(0);
            }
        }
        return 0;
    }

    private static boolean matchesTweetAuthor(XUserSummary author, XUserSummary expected) {
        return author == null || ((author.id().isBlank() || author.id().equals(expected.id()))
                && (author.username().isBlank() || author.username().equalsIgnoreCase(expected.username())));
    }

    private static long observedAt(JsonNode node, long fallback) {
        JsonNode value = node.path("__observed_at");
        return value.isIntegralNumber() && value.asLong() > 0 && value.asLong() <= fallback
                ? value.asLong() : fallback;
    }

    private static boolean matchesStoredMetric(AccountStore.StoredTweet previous, Instant createdAt, String parent) {
        return createdAt != null && previous.createdAt() == createdAt.getEpochSecond()
                && (previous.inReplyToId() == null || previous.inReplyToId().isBlank() || previous.inReplyToId().equals(parent));
    }

    private RawTweet parseMetricTweet(JsonNode node, String self, AccountStore.StoredAccount existing, boolean fromFree) {
        RawTweet tweet = parseRawTweet(node, self);
        if (!fromFree || tweet == null || tweet.createdAt() == null || existing == null) return tweet;
        AccountStore.StoredTweet previous = existing.tweets().stream()
                .filter(t -> t.id().equals(tweet.id())).findFirst().orElse(null);
        if (previous == null) return tweet;
        if (!matchesStoredMetric(previous, tweet.createdAt(), tweet.inReplyToId())) return null;
        String knownConversation = previous.conversationId() == null ? previous.id()
                : "-".equals(previous.conversationId()) ? "" : previous.conversationId();
        if (!tweet.conversationId().isBlank() || knownConversation.isBlank()) return tweet;
        // FxTwitter 给出直接父帖，未提供根会话；更新指标时保留已有的根会话证据。
        return new RawTweet(tweet.id(), tweet.text(), tweet.createdAt(), tweet.kind(), tweet.targetUsername(),
                tweet.targetUser(), tweet.mentionedUsernames(), tweet.hasMedia(), tweet.isThread(),
                tweet.viewCount(), tweet.likeCount(), tweet.replyCount(), tweet.retweetCount(), tweet.bookmarkCount(),
                tweet.selfThreadReply(), knownConversation, tweet.inReplyToId(), tweet.unavailableMetrics());
    }

    private RawTweet parseRawTweet(JsonNode t, String selfUsername) {
        String id = t.path("id").asText("");
        if (id.isBlank()) return null;
        String text = t.path("text").asText("");
        Instant createdAt = parseFlexibleTimestamp(firstNonBlank(t.path("createdAt").asText(""), t.path("created_at").asText("")));

        boolean isRetweet = t.hasNonNull("retweeted_tweet") || text.startsWith("RT @");
        boolean isQuote = !isRetweet && t.hasNonNull("quoted_tweet");
        String replyToField = firstNonBlank(
                t.path("inReplyToUsername").asText(""),
                t.path("in_reply_to_screen_name").asText("")
        );
        boolean isReply = !isRetweet && (t.path("isReply").asBoolean(false) || !replyToField.isBlank());

        TweetKind kind = isRetweet ? TweetKind.RETWEET
                : (isReply ? TweetKind.REPLY : (isQuote ? TweetKind.QUOTE : TweetKind.POST));

        String targetUsername = null;
        XUserSummary targetUser = null;
        boolean selfThreadReply = false;
        if (isReply) {
            targetUsername = replyToField;
            if (targetUsername.isBlank()) {
                Matcher m = LEADING_MENTION_PATTERN.matcher(text.trim());
                if (m.find()) {
                    targetUsername = m.group(1);
                }
            }
            if (targetUsername != null && targetUsername.equalsIgnoreCase(selfUsername)) {
                kind = TweetKind.POST;
                targetUsername = null;
                selfThreadReply = true;
            }
        } else if (isQuote) {
            JsonNode qtAuthor = t.path("quoted_tweet").path("author");
            if (!qtAuthor.isMissingNode()) {
                targetUser = parseUserNode(qtAuthor, "");
                targetUsername = targetUser.username();
            }
        }

        List<String> mentions = new ArrayList<>();
        JsonNode mentionNodes = t.path("entities").path("user_mentions");
        if (mentionNodes.isArray()) {
            for (JsonNode mn : mentionNodes) {
                String mu = firstNonBlank(mn.path("screen_name").asText(""), mn.path("userName").asText(""));
                if (!mu.isBlank()) mentions.add(mu);
            }
        }

        boolean hasMedia = (t.path("extendedEntities").path("media").isArray() && !t.path("extendedEntities").path("media").isEmpty())
                || (t.path("entities").path("media").isArray() && !t.path("entities").path("media").isEmpty())
                || (t.path("media").isArray() && !t.path("media").isEmpty());
        boolean isThread = selfThreadReply || text.length() >= 220 || text.contains("🧵");

        String conversationId = firstNonBlank(
                t.path("conversationId").asText(""),
                t.path("conversation_id").asText(""),
                (!isReply && !isRetweet) ? id : ""
        );
        String inReplyToId = firstNonBlank(
                t.path("inReplyToId").asText(""),
                t.path("in_reply_to_status_id_str").asText(""),
                t.path("in_reply_to_tweet_id").asText(""),
                t.path("inReplyToStatusId").asText("")
        );

        return new RawTweet(
                id,
                text,
                createdAt,
                kind,
                targetUsername,
                targetUser,
                mentions,
                hasMedia,
                isThread,
                Math.max(0L, t.path("viewCount").asLong(0L)),
                Math.max(0, t.path("likeCount").asInt(0)),
                Math.max(0, t.path("replyCount").asInt(0)),
                Math.max(0, t.path("retweetCount").asInt(0) + t.path("quoteCount").asInt(0)),
                Math.max(0, t.path("bookmarkCount").asInt(0)),
                selfThreadReply,
                conversationId,
                inReplyToId,
                unavailablePublicMetrics(t)
        );
    }

    private InboundInteraction parseInbound(JsonNode m, String selfUsername) {
        return parseInbound(m, selfUsername, null);
    }

    private InboundInteraction parseInbound(JsonNode m, String selfUsername, String selfId) {
        String text = m.path("text").asText("");
        if (m.hasNonNull("retweeted_tweet") || text.startsWith("RT @")) {
            return null;
        }
        JsonNode authorNode = m.path("author");
        if (authorNode.isMissingNode()) return null;
        XUserSummary peer = parseUserNode(authorNode, "");
        if (peer.username().isBlank() || peer.username().equalsIgnoreCase(selfUsername)) {
            return null;
        }
        String replyTo = firstNonBlank(
                m.path("inReplyToUsername").asText(""),
                m.path("in_reply_to_screen_name").asText("")
        );
        String conversationId = firstNonBlank(
                m.path("conversationId").asText(""),
                m.path("conversation_id").asText("")
        );
        String inReplyToId = firstNonBlank(
                m.path("inReplyToId").asText(""),
                m.path("in_reply_to_status_id_str").asText(""),
                m.path("in_reply_to_tweet_id").asText(""),
                m.path("inReplyToStatusId").asText("")
        );
        boolean isReplyFlag = m.path("isReply").asBoolean(false) || !inReplyToId.isBlank() || !replyTo.isBlank();

        String quotedAuthor = firstNonBlank(m.path("quoted_tweet").path("author").path("userName").asText(""),
                m.path("quoted_tweet").path("author").path("username").asText(""));
        String quotedAuthorId = m.path("quoted_tweet").path("author").path("id").asText("");
        boolean quotesSelf = quotedAuthor.equalsIgnoreCase(selfUsername)
                || (selfId != null && !selfId.isBlank() && selfId.equals(quotedAuthorId));
        InboundKind kind;
        if (quotesSelf && !isReplyFlag) {
            kind = InboundKind.QUOTE_ME;
        } else if (replyTo.equalsIgnoreCase(selfUsername) || (replyTo.isBlank() && isReplyFlag)) {
            kind = InboundKind.REPLY_TO_ME;
        } else if (quotesSelf) {
            kind = InboundKind.QUOTE_ME;
        } else {
            kind = InboundKind.MENTION_ME;
        }
        return new InboundInteraction(
                m.path("id").asText(""),
                peer,
                parseFlexibleTimestamp(firstNonBlank(m.path("createdAt").asText(""), m.path("created_at").asText(""))),
                kind,
                text,
                conversationId,
                inReplyToId,
                replyTo
        );
    }

    static Instant parseFlexibleTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        try {
            return Instant.parse(cleaned);
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(cleaned, TWITTER_DATE_FMT).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(cleaned, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
