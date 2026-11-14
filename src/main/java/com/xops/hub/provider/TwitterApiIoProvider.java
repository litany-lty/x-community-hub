package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.store.AccountStore;
import com.xops.hub.model.RawSnapshotModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final long FREE_TIER_INTERVAL_MS = 5300L;
    private static final int MAX_429_RETRIES = 3;
    private static final long SNAPSHOT_SOFT_DEADLINE_SECONDS = 15L;
    private static final int MAX_STREAM_PAGES_PER_BATCH = 32;

    private static final class KeyPacer {
        long nextAllowedAtMs = 0L;
        long lastBackoffUntilMs = 0L;
        int distinct429Windows = 0;
        volatile boolean freeTierSlowMode = false;
        volatile long intervalMs = PAID_INTERVAL_MS;

        synchronized long reserveWaitMs(boolean isLocalMock) {
            if (isLocalMock && distinct429Windows == 0) {
                return 0L;
            }
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedAtMs);
            nextAllowedAtMs = slot + intervalMs;
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
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, KeyPacer> pacersByKey = new ConcurrentHashMap<>();

    public TwitterApiIoProvider(XOpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new SharedApiExecutor(properties));
    }

    @Autowired
    public TwitterApiIoProvider(XOpsProperties properties, ObjectMapper objectMapper, SharedApiExecutor sharedExecutor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sharedExecutor = sharedExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
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
        JsonNode root = getJsonWithRetry(baseUrl + "/twitter/user/info?userName=X", apiKey, pacer);
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

    /** 由持久化存储生成分析快照，覆盖天数按两类数据各自连续入库的范围计算 */
    public static RawAccountSnapshot snapshotOf(AccountStore.StoredAccount store, String notice, boolean degraded) {
        RawAccountSnapshot probe = AccountStore.toSnapshot(store, notice, degraded, 1);
        int days = computeCoveredWindowDays(probe.fetchedAt(), probe.timelineExhausted(), probe.timelineTailTime(),
                probe.mentionsExhausted(), probe.mentionsTailTime());
        return AccountStore.toSnapshot(store, notice, degraded, days);
    }

    public static Instant cutoffForWindowDays(Instant refNow, int targetWindowDays) {
        long hours = targetWindowDays <= 2 ? 49L : (targetWindowDays <= 7 ? 169L : 721L);
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
            int apiPages
    ) {
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
        boolean doHead = headSync || !warmExisting;
        Instant deadline = isLocalMockBaseUrl()
                ? Instant.now().plusSeconds(60)
                : Instant.now().plusSeconds(SNAPSHOT_SOFT_DEADLINE_SECONDS);
        AtomicBoolean degraded = new AtomicBoolean(false);

        List<long[]> tweetCoverage = existing != null ? existing.tweetCoverage() : List.of();
        List<long[]> inboundCoverage = existing != null ? existing.inboundCoverage() : List.of();

        // 扩窗不能只信旧资料：用户名可能已转让，须在拉取新流之前核对当前账号 ID。
        String boundId = expectedAccountId != null ? expectedAccountId
                : existing != null && existing.profile() != null ? existing.profile().id() : null;
        XUserSummary verifiedProfile = null;
        if (expectedAccountId != null || !doHead) {
            verifiedProfile = parseValidatedUserProfile(getJsonWithRetry(
                    baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer), username);
            requireSameAccountId(verifiedProfile, boundId);
        }
        final XUserSummary preflightProfile = verifiedProfile;

        XUserSummary profile;
        Instant syncTo;
        boolean graphReusable;
        boolean fetchGraph;
        StreamFill tweets;
        StreamFill inbound;
        PagedUserResult followings;
        PagedUserResult followers;

        try {
            if (!doHead) {
                profile = existing.profile();
                syncTo = existing.syncedAtInstant();
                graphReusable = false;
                fetchGraph = false;
                String self = profile.username();
                int pageBudget = pacer.freeTierSlowMode ? 3 : MAX_STREAM_PAGES_PER_BATCH;
                Instant cutoff30 = cutoffForWindowDays(syncTo, 30);
                Instant targetCutoff = cutoffForWindowDays(syncTo, targetWindowDays);
                long headTo = syncTo.getEpochSecond();

                CompletableFuture<StreamFill> tweetsF = startTweetsFillAsync(
                        baseUrl, encodedUser, self, tweetCoverage, targetCutoff, cutoff30, headTo, false,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<StreamFill> inboundF = startInboundFillAsync(
                        baseUrl, encodedUser, self, inboundCoverage, targetCutoff, cutoff30, headTo, false,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture.allOf(tweetsF, inboundF).join();
                tweets = tweetsF.join();
                inbound = inboundF.join();
                followings = null;
                followers = null;
            } else if (warmExisting) {
                // 热更新重叠加速：已有持久化账号时，用户资料刷新与推文/提及增量拉取同时在共用线程池并发启动，省去串行等待资料 RTT
                syncTo = Instant.now();
                String knownSelf = existing.profile().username();
                int pageBudget = pacer.freeTierSlowMode ? 3 : MAX_STREAM_PAGES_PER_BATCH;
                Instant cutoff30 = cutoffForWindowDays(syncTo, 30);
                Instant targetCutoff = cutoffForWindowDays(syncTo, targetWindowDays);
                long headTo = syncTo.getEpochSecond();

                CompletableFuture<XUserSummary> profileF = preflightProfile != null
                        ? CompletableFuture.completedFuture(preflightProfile)
                        : getJsonWithRetryAsync(baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer)
                                .thenApply(root -> parseValidatedUserProfile(root, username));
                CompletableFuture<HeadGraphStage> headStageF = profileF.thenCompose(freshProfile -> {
                     requireSameAccountId(freshProfile, boundId);
                     boolean reuse = existing.hasGraph()
                             && !existing.graphRefreshFailed() && !existing.followersTruncated() && !existing.followingsTruncated()
                             && existing.profile().followingCount() == freshProfile.followingCount()
                             && existing.profile().followersCount() == freshProfile.followersCount()
                             && Duration.between(Instant.ofEpochSecond(existing.graphFetchedAt()), syncTo).toHours() < 6;
                     if (reuse) {
                         return CompletableFuture.completedFuture(new HeadGraphStage(freshProfile, true, false, null, null));
                     }
                     CompletableFuture<PagedUserResult> fgF = freshProfile.followingCount() > 0
                             ? fetchPagedUsersAsync(
                                     baseUrl + "/twitter/user/followings?userName=" + encodedUser + "&pageSize=200",
                                     apiKey, pacer, pacer.freeTierSlowMode ? 1 : Math.max(1, properties.getMaxFollowingPages()),
                                     freshProfile.followingCount(), "followings", deadline, degraded)
                             : CompletableFuture.completedFuture(null);
                     CompletableFuture<PagedUserResult> frF = freshProfile.followersCount() > 0
                             ? fetchFollowersAsync(
                                     baseUrl, encodedUser, freshProfile.id(),
                                     apiKey, pacer, pacer.freeTierSlowMode ? 1 : Math.max(1, properties.getMaxFollowerPages()),
                                     freshProfile.followersCount(), deadline, degraded)
                             : CompletableFuture.completedFuture(null);
                     return CompletableFuture.allOf(fgF, frF)
                             .thenApply(v -> new HeadGraphStage(freshProfile, false, true, fgF.join(), frF.join()));
                 });

                CompletableFuture<StreamFill> tweetsF = startTweetsFillAsync(
                        baseUrl, encodedUser, knownSelf, tweetCoverage, targetCutoff, cutoff30, headTo, true,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<StreamFill> inboundF = startInboundFillAsync(
                        baseUrl, encodedUser, knownSelf, inboundCoverage, targetCutoff, cutoff30, headTo, true,
                        apiKey, pacer, pageBudget, deadline, degraded);

                CompletableFuture.allOf(headStageF, tweetsF, inboundF).join();
                HeadGraphStage stage = headStageF.join();
                profile = stage.profile();
                graphReusable = stage.graphReusable();
                fetchGraph = stage.fetchGraph();
                followings = stage.followings();
                followers = stage.followers();
                tweets = tweetsF.join();
                inbound = inboundF.join();
            } else {
                // 首次冷启动：先校验账号存在性，随后在共用线程池中多路并发采集动态、互动与关注关系
                profile = preflightProfile != null ? preflightProfile : parseValidatedUserProfile(
                        getJsonWithRetry(baseUrl + "/twitter/user/info?userName=" + encodedUser, apiKey, pacer), username);
                syncTo = Instant.now();
                graphReusable = false;
                fetchGraph = true;
                String self = profile.username();
                int pageBudget = pacer.freeTierSlowMode ? 3 : MAX_STREAM_PAGES_PER_BATCH;
                Instant cutoff30 = cutoffForWindowDays(syncTo, 30);
                Instant targetCutoff = cutoffForWindowDays(syncTo, targetWindowDays);
                long headTo = syncTo.getEpochSecond();

                CompletableFuture<StreamFill> tweetsF = startTweetsFillAsync(
                        baseUrl, encodedUser, self, tweetCoverage, targetCutoff, cutoff30, headTo, true,
                        apiKey, pacer, pageBudget, deadline, degraded);
                CompletableFuture<StreamFill> inboundF = startInboundFillAsync(
                        baseUrl, encodedUser, self, inboundCoverage, targetCutoff, cutoff30, headTo, true,
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
                    followings != null && followings.failed(), followers != null && followers.failed());
        }

        if (graph != null && graph.followersTruncated() && !graph.followingsFailed()) {
            Map<String, Long> attemptedAt = new ConcurrentHashMap<>(existing != null ? existing.relationshipAttemptedAt() : Map.of());
            List<AccountStore.RelationshipEvidence> exact = verifyMissingFollowers(
                    baseUrl, profile.username(), graph.followings(), graph.followers(),
                    existing != null ? existing.relationshipEvidence() : List.of(), Long.MAX_VALUE,
                    attemptedAt, apiKey, pacer, deadline, degraded);
            boolean followingChanged = exact.stream().anyMatch(e -> !e.following());
            graph = new AccountStore.GraphUpdate(graph.followings(), graph.followers(), graph.explicitFollowedBy(),
                    graph.followersTruncated(), graph.followingsTruncated() || followingChanged,
                    graph.followingsFailed(), graph.followersFailed(), exact, attemptedAt);
        }

        List<RawTweet> newTweets = new ArrayList<>(tweets.items().size());
        tweets.items().forEach(o -> newTweets.add((RawTweet) o));
        List<InboundInteraction> newInbound = new ArrayList<>(inbound.items().size());
        inbound.items().forEach(o -> newInbound.add((InboundInteraction) o));

        List<String> refreshIds = List.of();
        // One bounded batch per head sync. Rotate by attempt age; repair unknown main posts first among peers.
        if (doHead && warmExisting && !Instant.now().isAfter(deadline)) {
            Set<String> seen = new HashSet<>();
            newTweets.forEach(t -> seen.add(t.id()));
            refreshIds = existing.tweets().stream()
                    .filter(t -> t.createdAt() >= syncTo.minus(Duration.ofDays(30)).getEpochSecond())
                    .filter(t -> !seen.contains(t.id()))
                    .sorted(java.util.Comparator.comparingLong(AccountStore.StoredTweet::metricsAttemptedAt)
                            .thenComparingInt(t -> !t.unavailableMetrics().isEmpty()
                                    && ((t.kind() == 'P' && (t.flags() & 4) == 0) || t.kind() == 'Q') ? 0 : 1))
                    .limit(50).map(AccountStore.StoredTweet::id).toList();
            if (!refreshIds.isEmpty()) {
                JsonNode refreshed = getJsonOnceBeforeAsync(baseUrl + "/twitter/tweets?tweet_ids="
                        + URLEncoder.encode(String.join(",", refreshIds), StandardCharsets.UTF_8),
                        apiKey, pacer, deadline, degraded, 15_000, false).join();
                for (JsonNode node : extractArray(refreshed, "tweets", "data")) {
                    if (refreshIds.contains(node.path("id").asText(""))) {
                        newTweets.add(parseRawTweet(node, profile.username()));
                    }
                }
            }
        }

        String self = profile.username();
        AccountStore.StoredAccount merged = AccountStore.merge(
                existing, profile, syncTo, newTweets, newInbound, tweets.coverage(), inbound.coverage(), graph);
        if (!refreshIds.isEmpty()) merged = AccountStore.withMetricAttempts(merged, refreshIds, syncTo);
        boolean progressed = tweets.coverage().stream()
                .anyMatch(r -> !AccountStore.missingRanges(tweetCoverage, r[0], r[1]).isEmpty())
                || inbound.coverage().stream()
                .anyMatch(r -> !AccountStore.missingRanges(inboundCoverage, r[0], r[1]).isEmpty());
        int pages = tweets.pages() + inbound.pages();
        log.info("Synced TwitterAPI.io store for @{} in {} ms (head={}, targetDays={}, pages={}, newTweets={}, newInbound={}, graph={}, storedTweets={}, storedInbound={}, degraded={})",
                self, System.currentTimeMillis() - startMs, doHead, targetWindowDays, pages,
                newTweets.size(), newInbound.size(), fetchGraph ? "fetched" : (doHead ? "reused" : "skipped"),
                merged.tweets().size(), merged.inbound().size(), degraded.get());
        return new SyncOutcome(merged, progressed, degraded.get(), doHead && graphReusable, pages);
    }

    public static int pendingRelationshipChecks(AccountStore.StoredAccount store) {
        if (store == null || !store.followersTruncated() || store.graphRefreshFailed()) return 0;
        return (int) AccountStore.toSnapshot(store, "", false, 0).relationships().stream()
                .filter(r -> r.iFollowThem() && !r.theyFollowMe() && !r.relationshipVerified())
                .count();
    }

    /** 仅应用户明确点击续查调用，不抓取历史或重新分页图谱。 */
    public SyncOutcome continueRelationships(String username, AccountStore.StoredAccount store, String expectedAccountId) {
        Instant now = Instant.now();
        if (store == null || !store.hasGraph() || store.graphRefreshFailed()
                || now.getEpochSecond() - store.graphFetchedAt() >= 300) {
            throw new XDataProviderException(409, "关注图谱已过期或不可用，请在缓存间隔结束后刷新数据，再继续核验关系");
        }
        if (pendingRelationshipChecks(store) == 0) return new SyncOutcome(store, false, false, true, 0);
        if (AccountStore.followingUsers(store).stream().anyMatch(p -> p.id() == null || p.id().isBlank())) {
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
        long validAfter = Math.max(store.graphFetchedAt(), now.minusSeconds(300).getEpochSecond() + 1);
        List<AccountStore.RelationshipEvidence> checked = verifyMissingFollowers(baseUrl, profile.username(),
                AccountStore.followingUsers(store), AccountStore.followerUsers(store), store.relationshipEvidence(),
                validAfter, attemptedAt, apiKey, pacer, deadline, degraded);
        Map<String, AccountStore.RelationshipEvidence> combined = new java.util.LinkedHashMap<>();
        store.relationshipEvidence().forEach(e -> combined.put(e.peerId(), e));
        checked.forEach(e -> combined.put(e.peerId(), e));
        var result = AccountStore.withRelationshipEvidence(store, new ArrayList<>(combined.values()), attemptedAt);
        return new SyncOutcome(result, !checked.isEmpty(), degraded.get(), true, 0);
    }

    private List<AccountStore.RelationshipEvidence> verifyMissingFollowers(
            String baseUrl, String self, List<XUserSummary> followings, List<XUserSummary> followers,
            List<AccountStore.RelationshipEvidence> previousChecks,
            long validEvidenceAfter,
            Map<String, Long> attemptedAt,
            String apiKey, KeyPacer pacer, Instant deadline, AtomicBoolean degraded) {
        Set<String> returnedFollowers = new HashSet<>();
        followers.stream().map(XUserSummary::id).filter(id -> id != null && !id.isBlank()).forEach(returnedFollowers::add);
        Set<String> seenIds = new HashSet<>();
        Map<String, Long> lastChecked = new HashMap<>();
        Map<String, Long> checkedHandles = new HashMap<>();
        previousChecks.forEach(e -> lastChecked.merge(e.peerId(), e.checkedAt() * 1000, Math::max));
        attemptedAt.forEach((id, at) -> lastChecked.merge(id, at, Math::max));
        previousChecks.stream().filter(e -> e.handle() != null)
                .forEach(e -> checkedHandles.merge(e.peerId() + ":" + e.handle().toLowerCase(Locale.ROOT), e.checkedAt(), Math::max));
        Set<String> currentCandidates = new HashSet<>();
        followings.stream().filter(p -> p.id() != null && !p.id().isBlank() && !returnedFollowers.contains(p.id()))
                .forEach(p -> currentCandidates.add(p.id()));
        attemptedAt.keySet().retainAll(currentCandidates);
        List<XUserSummary> candidates = followings.stream()
                .filter(p -> p.id() != null && !p.id().isBlank() && p.username() != null && !p.username().isBlank())
                .filter(p -> !returnedFollowers.contains(p.id()))
                .filter(p -> checkedHandles.getOrDefault(p.id() + ":" + p.username().toLowerCase(Locale.ROOT), 0L) < validEvidenceAfter)
                .filter(p -> seenIds.add(p.id()))
                .sorted(java.util.Comparator.comparingLong(p -> lastChecked.getOrDefault(p.id(), 0L)))
                .limit(100).toList();
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
            String baseUrl, String encodedUser, String self, List<long[]> coverage,
            Instant targetCutoff, Instant cutoff30, long headTo, boolean allowNativeHead,
            String apiKey, KeyPacer pacer, int pageBudget, Instant deadline, AtomicBoolean degraded
    ) {
        return fillStreamAsync(
                baseUrl,
                baseUrl + "/twitter/user/last_tweets?userName=" + encodedUser + "&includeReplies=true",
                "from:" + self,
                coverage, targetCutoff, cutoff30, headTo, allowNativeHead,
                apiKey, pacer, pageBudget, deadline, degraded,
                node -> {
                    RawTweet t = parseRawTweet(node, self);
                    return t == null ? null : new Parsed(t, t.createdAt(),
                            node.path("isPin").asBoolean(false) || node.path("isPinned").asBoolean(false));
                });
    }

    private CompletableFuture<StreamFill> startInboundFillAsync(
            String baseUrl, String encodedUser, String self, List<long[]> coverage,
            Instant targetCutoff, Instant cutoff30, long headTo, boolean allowNativeHead,
            String apiKey, KeyPacer pacer, int pageBudget, Instant deadline, AtomicBoolean degraded
    ) {
        return fillStreamAsync(
                baseUrl,
                baseUrl + "/twitter/user/mentions?userName=" + encodedUser,
                "(to:" + self + " OR @" + self + ") -from:" + self,
                coverage, targetCutoff, cutoff30, headTo, allowNativeHead,
                apiKey, pacer, pageBudget, deadline, degraded,
                node -> {
                    String rawDate = firstNonBlank(node.path("createdAt").asText(""), node.path("created_at").asText(""));
                    Instant ts = rawDate.isBlank() ? null : parseFlexibleTimestamp(rawDate);
                    InboundInteraction i = parseInbound(node, self);
                    return new Parsed(i, i != null ? i.createdAt() : ts, false);
                });
    }

    private record Parsed(Object item, Instant createdAt, boolean pinned) {
    }

    private record CollectedBatch(Instant tail, boolean valid) {
    }

    private record StreamFill(List<Object> items, List<long[]> coverage, int pages) {
    }

    private record StreamContext(
            String baseUrl,
            String nativeUrl,
            String sliceQuery,
            Instant cutoff30,
            String apiKey,
            KeyPacer pacer,
            Instant deadline,
            AtomicBoolean degraded,
            Function<JsonNode, Parsed> parser,
            List<Object> items,
            List<long[]> newCoverage,
            AtomicInteger pagesLeft,
            AtomicInteger pagesUsed
    ) {
    }

    /**
     * 非阻塞分页与自适应并发时间分片补齐：
     * - 每一页 HTTP 请求独立投递至 {@link SharedApiExecutor}，不在工作线程内阻塞等待其它请求；
     * - 首页先做单次探测（稀疏账号 1 次请求即完成，绝不多花 API 额度）；
     * - 当探测确认该时间段存在多页高密度数据且线程池处于低峰空闲时，自动把剩余时间窗切分为多路子区间并行拉取；
     * - 高峰期线程池自动将分片度收敛为 1，按请求粒度 FIFO 公平调度。
     */
    private CompletableFuture<StreamFill> fillStreamAsync(
            String baseUrl,
            String nativeUrl,
            String sliceQuery,
            List<long[]> coverage,
            Instant targetCutoff,
            Instant cutoff30,
            long headTo,
            boolean allowNativeHead,
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
            return CompletableFuture.completedFuture(new StreamFill(List.of(), List.of(), 0));
        }
        StreamContext ctx = new StreamContext(
                baseUrl, nativeUrl, sliceQuery, cutoff30, apiKey, pacer, deadline, degraded, parser,
                Collections.synchronizedList(new ArrayList<>()),
                Collections.synchronizedList(new ArrayList<>()),
                new AtomicInteger(pageBudget),
                new AtomicInteger(0)
        );
        return fillGapAtAsync(ctx, gaps, 0, headTo, allowNativeHead)
                .thenApply(v -> new StreamFill(new ArrayList<>(ctx.items()), new ArrayList<>(ctx.newCoverage()), ctx.pagesUsed().get()));
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
        return getJsonOptionalAsync(url, ctx.apiKey(), ctx.pacer(), ctx.degraded()).thenCompose(root -> {
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

    private CompletableFuture<Void> fillSearchRangeWithAdaptiveShardsAsync(StreamContext ctx, long a, long b) {
        if (b <= a) {
            ctx.newCoverage().add(new long[]{a, b});
            return CompletableFuture.completedFuture(null);
        }
        if (!tryAcquirePage(ctx)) {
            return CompletableFuture.completedFuture(null);
        }
        String probeUrl = buildSearchUrl(ctx.baseUrl(), ctx.sliceQuery(), a, b + 1, "");
        return getJsonOptionalAsync(probeUrl, ctx.apiKey(), ctx.pacer(), ctx.degraded()).thenCompose(root -> {
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

            if ((probeTail != null && probeTail.getEpochSecond() <= a) || batch.isEmpty() || next.isEmpty()) {
                ctx.newCoverage().add(new long[]{a, b});
                return CompletableFuture.completedFuture(null);
            }

            // 首页探测确认还有后续分页：低峰期按经验密度把剩余 [a, probeTail] 切分为多路并行分片；高峰期自动保持 1 路续翻
            if (probeTail != null && probeTail.getEpochSecond() < b && probeTail.getEpochSecond() > a + 300) {
                long remB = probeTail.getEpochSecond();
                long page1Span = Math.max(60L, b - remB);
                long remSpan = remB - a;
                int estPages = (int) Math.min(64L, (remSpan + page1Span - 1) / page1Span);
                int shards = sharedExecutor.suggestSliceConcurrency(
                        remSpan, estPages, ctx.pagesLeft().get(), ctx.pacer().freeTierSlowMode);
                if (shards >= 2) {
                    ctx.newCoverage().add(new long[]{remB, b});
                    List<CompletableFuture<Void>> shardFutures = new ArrayList<>(shards);
                    // 从最新分片往更早分片依次入队，保证预算受限时最新连续区间优先完成
                    for (int i = shards - 1; i >= 0; i--) {
                        long subA = a + (remSpan * i) / shards;
                        long subB = (i == shards - 1) ? remB : (a + (remSpan * (i + 1)) / shards);
                        if (subB > subA) {
                            shardFutures.add(runSearchSliceLoopAsync(ctx, subA, subB, subB + 1, "", null));
                        }
                    }
                    return CompletableFuture.allOf(shardFutures.toArray(CompletableFuture[]::new));
                }
            }
            return runSearchSliceLoopAsync(ctx, a, b, b + 1, next, probeTail);
        });
    }

    private CompletableFuture<Void> runSearchSliceLoopAsync(
            StreamContext ctx,
            long subA,
            long subB,
            long untilExclusive,
            String cursor,
            Instant tailSoFar
    ) {
        if (!tryAcquirePage(ctx)) {
            recordPartialIfAny(ctx, subA, subB, tailSoFar);
            return CompletableFuture.completedFuture(null);
        }
        String url = buildSearchUrl(ctx.baseUrl(), ctx.sliceQuery(), subA, untilExclusive, cursor);
        return getJsonOptionalAsync(url, ctx.apiKey(), ctx.pacer(), ctx.degraded()).thenCompose(root -> {
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
            if (!next.isEmpty() && next.equals(cursor)) {
                ctx.degraded().set(true);
                recordPartialIfAny(ctx, subA, subB, tailSoFar);
                return CompletableFuture.completedFuture(null);
            }
            if ((tail != null && tail.getEpochSecond() <= subA)
                    || batch.isEmpty()
                    || next.isEmpty()) {
                ctx.newCoverage().add(new long[]{subA, subB});
                return CompletableFuture.completedFuture(null);
            }
            return runSearchSliceLoopAsync(ctx, subA, subB, untilExclusive, next, tail);
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
            if (p.item() != null && p.createdAt() != null && !p.createdAt().isBefore(ctx.cutoff30())) {
                ctx.items().add(p.item());
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
        if (tail != null && tail.getEpochSecond() < b) {
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
            boolean failed
    ) {
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
        PagedUserResult incomplete = new PagedUserResult(listed.users(), listed.explicitFollowedByKeys(), true, false);
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
                return new PagedUserResult(users, listed.explicitFollowedByKeys(), !complete, false);
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
        return sharedExecutor.<JsonNode>submitAsync(() -> {
            long waitMs = pacer.reserveWaitMsBefore(isLocalMockBaseUrl(), deadline.toEpochMilli());
            if (waitMs < 0) return null;
            if (waitMs > 0) Thread.sleep(waitMs);
            long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
            if (remainingMs <= 0) return null;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(finishBeforeDeadline ? Math.min(requestTimeoutMs, remainingMs) : requestTimeoutMs))
                    .header("X-API-Key", apiKey).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) pacer.onRateLimited429(isLocalMockBaseUrl());
            return parseValidatedResponse(response, pacer);
        }).exceptionally(ex -> {
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof XDataProviderException xe && (xe.getStatusCode() == 401 || xe.getStatusCode() == 402)) throw xe;
            degraded.set(true);
            return null;
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
        return attemptGetJsonAsync(url, apiKey, pacer, isLocalMockBaseUrl(), 0);
    }

    private CompletableFuture<JsonNode> attemptGetJsonAsync(
            String url,
            String apiKey,
            KeyPacer pacer,
            boolean localMock,
            int attempt
    ) {
        return sharedExecutor.submitAsync(() -> {
            pacer.acquireSlot(localMock);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-API-Key", apiKey)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (XDataProviderException e) {
                throw e;
            } catch (Exception e) {
                throw new XDataProviderException(502, "连接 TwitterAPI.io 失败：" + e.getMessage(), e);
            }
        }).thenCompose(resp -> {
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
        return getJsonWithRetryAsync(url, apiKey, pacer).exceptionally(ex -> {
            Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
            if (cause instanceof XDataProviderException xe && (xe.getStatusCode() == 401 || xe.getStatusCode() == 402)) {
                throw xe;
            }
            if (partialDegraded != null) {
                partialDegraded.set(true);
            }
            com.fasterxml.jackson.databind.node.ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("__degraded", true);
            return fallback;
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
        String bio = firstNonBlank(
                node.path("biography").asText(""),
                node.path("description").asText(""),
                node.path("bio").asText("")
        );
        boolean verified = node.path("isBlueVerified").asBoolean(false) || node.path("verified").asBoolean(false);
        int followers = firstPositiveInt(node, "followers", "followers_count");
        int following = firstPositiveInt(node, "following", "following_count", "friends_count");
        int statuses = firstPositiveInt(node, "statusesCount", "statuses_count", "tweet_count");
        String created = node.path("createdAt").asText("");
        return new XUserSummary(
                node.path("id").asText(""),
                uname,
                name,
                avatar,
                bio,
                verified,
                followers,
                following,
                statuses,
                created
        );
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

    private RawTweet parseRawTweet(JsonNode t, String selfUsername) {
        String id = t.path("id").asText("");
        if (id.isBlank()) return null;
        String text = t.path("text").asText("");
        Instant createdAt = parseFlexibleTimestamp(t.path("createdAt").asText(""));

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
        boolean quotesSelf = quotedAuthor.equalsIgnoreCase(selfUsername);
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
