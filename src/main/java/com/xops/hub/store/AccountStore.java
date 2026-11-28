package com.xops.hub.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.RawSnapshotModels.InboundInteraction;
import com.xops.hub.model.RawSnapshotModels.InboundKind;
import com.xops.hub.model.RawSnapshotModels.ProviderMode;
import com.xops.hub.model.RawSnapshotModels.RawAccountSnapshot;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.RelationshipEntry;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 单账号 30 天滚动持久化存储（GZIP 压缩 JSON，短字段名，只保留分析必需字段）。
 * <p>
 * 用「已入库时间区间」标记哪些时间段的数据已完整拉取：之后只请求区间之外的部分，超过 30 天的数据与区间自动裁掉。
 */
public final class AccountStore {

    public static final int VERSION = 1;
    public static final int VERIFIED_COVERAGE_VERSION = 1;
    public static final Duration RECORD_RETENTION = Duration.ofDays(30);
    /** 分页覆盖区间多留 1 小时，以便下轮在交界处复用采集证据。 */
    public static final Duration RETENTION = RECORD_RETENTION.plusHours(1);
    private static final int REPLY_TEXT_MAX = 120;
    private static final int BIO_MAX = 100;
    private static final int MAX_READ_CACHE_ENTRIES = 64;
    private static final List<String> METRIC_NAMES = List.of("views", "likes", "replies", "reposts", "bookmarks");

    private record CachedDiskStore(byte[] gzBytes, StoredAccount store) {
    }

    private static final ConcurrentHashMap<Path, CachedDiskStore> READ_CACHE = new ConcurrentHashMap<>();

    private AccountStore() {
    }

    // ==================== 存储结构 ====================

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record StoredUser(
            @JsonProperty("n") String displayName,
            @JsonProperty("a") String avatarUrl,
            @JsonProperty("v") boolean verified,
            @JsonProperty("f") int followers,
            @JsonProperty("g") int following,
            @JsonProperty("b") String bio,
            @JsonProperty("id") String id,
            @JsonProperty("p") int totalPosts,
            @JsonProperty("j") String joinedDate,
            @JsonProperty("va") long verifiedAt
    ) {
        public StoredUser(String displayName, String avatarUrl, boolean verified, int followers, int following, String bio,
                String id, int totalPosts, String joinedDate) {
            this(displayName, avatarUrl, verified, followers, following, bio, id, totalPosts, joinedDate, 0L);
        }

        public StoredUser(String displayName, String avatarUrl, boolean verified, int followers, int following, String bio) {
            this(displayName, avatarUrl, verified, followers, following, bio, null, 0, null, 0L);
        }
    }

    /** 我的动态。k: P=原创 R=回复 Q=引用 T=转推；f 位标记：1=含媒体 2=串推 4=自回复串推楼层；s=[浏览,赞,回复,转推,收藏]；cv 省略=与 id 相同，"-"=无 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record StoredTweet(
            @JsonProperty("i") String id,
            @JsonProperty("c") long createdAt,
            @JsonProperty("k") char kind,
            @JsonProperty("x") String text,
            @JsonProperty("t") String target,
            @JsonProperty("m") List<String> mentions,
            @JsonProperty("f") int flags,
            @JsonProperty("s") long[] stats,
            @JsonProperty("cv") String conversationId,
            @JsonProperty("r") String inReplyToId,
            @JsonProperty("mt") long metricsFetchedAt,
            @JsonProperty("ma") long metricsAttemptedAt,
            @JsonProperty("um") @JsonInclude(JsonInclude.Include.ALWAYS) Set<String> unavailableMetrics,
            @JsonProperty("mo") boolean metricsTimeObserved,
            @JsonProperty("ti") String targetId,
            @JsonProperty("ms") long[] metricEvidenceAt
    ) {
        public StoredTweet {
            unavailableMetrics = unavailableMetrics == null ? RawTweet.ALL_METRICS : Set.copyOf(unavailableMetrics);
        }
        public StoredTweet(String id, long createdAt, char kind, String text, String target, List<String> mentions,
                int flags, long[] stats, String conversationId, String inReplyToId, long metricsFetchedAt, long metricsAttemptedAt,
                Set<String> unavailableMetrics, boolean metricsTimeObserved, String targetId) {
            this(id, createdAt, kind, text, target, mentions, flags, stats, conversationId, inReplyToId,
                    metricsFetchedAt, metricsAttemptedAt, unavailableMetrics, metricsTimeObserved, targetId, null);
        }
        public StoredTweet(String id, long createdAt, char kind, String text, String target, List<String> mentions,
                int flags, long[] stats, String conversationId, String inReplyToId, long metricsFetchedAt, long metricsAttemptedAt,
                Set<String> unavailableMetrics, boolean metricsTimeObserved) {
            this(id, createdAt, kind, text, target, mentions, flags, stats, conversationId, inReplyToId,
                    metricsFetchedAt, metricsAttemptedAt, unavailableMetrics, metricsTimeObserved, null);
        }
        public StoredTweet(String id, long createdAt, char kind, String text, String target, List<String> mentions,
                int flags, long[] stats, String conversationId, String inReplyToId, long metricsFetchedAt, long metricsAttemptedAt,
                Set<String> unavailableMetrics) {
            this(id, createdAt, kind, text, target, mentions, flags, stats, conversationId, inReplyToId,
                    metricsFetchedAt, metricsAttemptedAt, unavailableMetrics, false);
        }
        public StoredTweet(String id, long createdAt, char kind, String text, String target, List<String> mentions,
                int flags, long[] stats, String conversationId, String inReplyToId, long metricsFetchedAt, long metricsAttemptedAt) {
            this(id, createdAt, kind, text, target, mentions, flags, stats, conversationId, inReplyToId,
                    metricsFetchedAt, metricsAttemptedAt, Set.of());
        }
        public StoredTweet(String id, long createdAt, char kind, String text, String target, List<String> mentions,
                int flags, long[] stats, String conversationId, String inReplyToId) {
            this(id, createdAt, kind, text, target, mentions, flags, stats, conversationId, inReplyToId, 0, 0);
        }
    }

    /** ru 是已观察到的被回复人；旧缓存缺失 ru 时不能从记录类型推断根帖归属。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record StoredInbound(
            @JsonProperty("i") String id,
            @JsonProperty("c") long createdAt,
            @JsonProperty("k") char kind,
            @JsonProperty("p") String peer,
            @JsonProperty("x") String snippet,
            @JsonProperty("cv") String conversationId,
            @JsonProperty("r") String inReplyToId,
            @JsonProperty("ru") String inReplyToUser,
            @JsonProperty("pi") String peerId,
            @JsonProperty("ri") String inReplyToUserId
    ) {
        public StoredInbound(String id, long createdAt, char kind, String peer, String snippet,
                String conversationId, String inReplyToId, String inReplyToUser, String peerId) {
            this(id, createdAt, kind, peer, snippet, conversationId, inReplyToId, inReplyToUser, peerId, null);
        }

        StoredInbound withProfileEvidence(XUserSummary originalProfile) {
            if ((inReplyToUserId != null && !inReplyToUserId.isBlank()) || kind != 'R' || inReplyToUser == null || inReplyToUser.isBlank()
                    || originalProfile == null || !inReplyToUser.equalsIgnoreCase(originalProfile.username())) return this;
            return new StoredInbound(id, createdAt, kind, peer, snippet, conversationId, inReplyToId,
                    inReplyToUser, peerId, originalProfile.id());
        }
        public StoredInbound(String id, long createdAt, char kind, String peer, String snippet,
                String conversationId, String inReplyToId, String inReplyToUser) {
            this(id, createdAt, kind, peer, snippet, conversationId, inReplyToId, inReplyToUser, null);
        }
    }

    /** 同一轮关系图谱下的逐人双向核验；缺失/失败响应不产生证据。 */
    public record RelationshipEvidence(String peerId, String handle, boolean following, boolean followedBy, long checkedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record StoredAccount(
            @JsonProperty("ver") int version,
            @JsonProperty("me") XUserSummary profile,
            @JsonProperty("at") long syncedAt,
            @JsonProperty("u") Map<String, StoredUser> users,
            @JsonProperty("t") List<StoredTweet> tweets,
            @JsonProperty("i") List<StoredInbound> inbound,
            @JsonProperty("tc") List<long[]> tweetCoverage,
            @JsonProperty("ic") List<long[]> inboundCoverage,
            @JsonProperty("fg") List<String> followings,
            @JsonProperty("fr") List<String> followers,
            @JsonProperty("fb") List<String> explicitFollowedBy,
            @JsonProperty("gt") long graphFetchedAt,
            @JsonProperty("gx") boolean followersTruncated,
            @JsonProperty("gy") boolean followingsTruncated,
            @JsonProperty("gf") boolean graphRefreshFailed,
            @JsonProperty("re") List<RelationshipEvidence> relationshipEvidence,
            @JsonProperty("ra") Map<String, Long> relationshipAttemptedAt,
            @JsonProperty("ds") long dailyRefreshStartedAt,
            @JsonProperty("df") long dailyRefreshedAt,
            @JsonProperty("fo") Map<String, Integer> followingOrderById,
            @JsonProperty("fro") Map<String, Integer> followerOrderById,
            @JsonProperty("cp") int coverageProofVersion,
            @JsonProperty("dc") long reportCompletedAt
    ) {
        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage, List<long[]> inboundCoverage,
                List<String> followings, List<String> followers, List<String> explicitFollowedBy, long graphFetchedAt,
                boolean followersTruncated, boolean followingsTruncated, boolean graphRefreshFailed,
                List<RelationshipEvidence> relationshipEvidence, Map<String, Long> relationshipAttemptedAt,
                long dailyRefreshStartedAt, long dailyRefreshedAt, Map<String, Integer> followingOrderById,
                Map<String, Integer> followerOrderById, int coverageProofVersion) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage, followings, followers,
                    explicitFollowedBy, graphFetchedAt, followersTruncated, followingsTruncated, graphRefreshFailed,
                    relationshipEvidence, relationshipAttemptedAt, dailyRefreshStartedAt, dailyRefreshedAt,
                    followingOrderById, followerOrderById, coverageProofVersion, 0);
        }

        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage, List<long[]> inboundCoverage,
                List<String> followings, List<String> followers, List<String> explicitFollowedBy, long graphFetchedAt,
                boolean followersTruncated, boolean followingsTruncated, boolean graphRefreshFailed,
                List<RelationshipEvidence> relationshipEvidence, Map<String, Long> relationshipAttemptedAt,
                long dailyRefreshStartedAt, long dailyRefreshedAt, Map<String, Integer> followingOrderById,
                Map<String, Integer> followerOrderById) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage, followings, followers,
                    explicitFollowedBy, graphFetchedAt, followersTruncated, followingsTruncated, graphRefreshFailed,
                    relationshipEvidence, relationshipAttemptedAt, dailyRefreshStartedAt, dailyRefreshedAt,
                    followingOrderById, followerOrderById, 0);
        }

        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage,
                List<long[]> inboundCoverage, List<String> followings, List<String> followers,
                List<String> explicitFollowedBy, long graphFetchedAt, boolean followersTruncated,
                boolean followingsTruncated, boolean graphRefreshFailed, List<RelationshipEvidence> relationshipEvidence,
                Map<String, Long> relationshipAttemptedAt, long dailyRefreshStartedAt, long dailyRefreshedAt) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage,
                    followings, followers, explicitFollowedBy, graphFetchedAt, followersTruncated,
                    followingsTruncated, graphRefreshFailed, relationshipEvidence, relationshipAttemptedAt,
                    dailyRefreshStartedAt, dailyRefreshedAt, Map.of(), Map.of());
        }

        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage,
                List<long[]> inboundCoverage, List<String> followings, List<String> followers,
                List<String> explicitFollowedBy, long graphFetchedAt, boolean followersTruncated,
                boolean followingsTruncated, boolean graphRefreshFailed, List<RelationshipEvidence> relationshipEvidence,
                Map<String, Long> relationshipAttemptedAt) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage,
                    followings, followers, explicitFollowedBy, graphFetchedAt, followersTruncated,
                    followingsTruncated, graphRefreshFailed, relationshipEvidence, relationshipAttemptedAt, 0, 0);
        }
        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage,
                List<long[]> inboundCoverage, List<String> followings, List<String> followers,
                List<String> explicitFollowedBy, long graphFetchedAt, boolean followersTruncated,
                boolean followingsTruncated, boolean graphRefreshFailed, List<RelationshipEvidence> relationshipEvidence) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage,
                    followings, followers, explicitFollowedBy, graphFetchedAt, followersTruncated,
                    followingsTruncated, graphRefreshFailed, relationshipEvidence, Map.of());
        }
        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage,
                List<long[]> inboundCoverage, List<String> followings, List<String> followers,
                List<String> explicitFollowedBy, long graphFetchedAt, boolean followersTruncated,
                boolean followingsTruncated, boolean graphRefreshFailed) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage,
                    followings, followers, explicitFollowedBy, graphFetchedAt, followersTruncated,
                    followingsTruncated, graphRefreshFailed, List.of());
        }
        public StoredAccount(int version, XUserSummary profile, long syncedAt, Map<String, StoredUser> users,
                List<StoredTweet> tweets, List<StoredInbound> inbound, List<long[]> tweetCoverage,
                List<long[]> inboundCoverage, List<String> followings, List<String> followers,
                List<String> explicitFollowedBy, long graphFetchedAt, boolean followersTruncated) {
            this(version, profile, syncedAt, users, tweets, inbound, tweetCoverage, inboundCoverage,
                    followings, followers, explicitFollowedBy, graphFetchedAt, followersTruncated, false, false);
        }
        public StoredAccount {
            users = users != null ? users : new LinkedHashMap<>();
            tweets = tweets != null ? tweets : new ArrayList<>();
            inbound = inbound != null ? inbound : new ArrayList<>();
            tweetCoverage = tweetCoverage != null ? tweetCoverage : new ArrayList<>();
            inboundCoverage = inboundCoverage != null ? inboundCoverage : new ArrayList<>();
            followings = followings != null ? followings : new ArrayList<>();
            followers = followers != null ? followers : new ArrayList<>();
            explicitFollowedBy = explicitFollowedBy != null ? explicitFollowedBy : new ArrayList<>();
            relationshipEvidence = relationshipEvidence != null ? List.copyOf(relationshipEvidence) : List.of();
            relationshipAttemptedAt = relationshipAttemptedAt != null ? Map.copyOf(relationshipAttemptedAt) : Map.of();
            // Absent metadata in older caches is unknown; never infer it from a possibly supplemented list.
            followingOrderById = followingOrderById != null ? Map.copyOf(followingOrderById) : Map.of();
            followerOrderById = followerOrderById != null ? Map.copyOf(followerOrderById) : Map.of();
            followersTruncated = followersTruncated || (profile != null && profile.followersCount() != followers.size());
            followingsTruncated = followingsTruncated || (profile != null && profile.followingCount() != followings.size());
        }

        @JsonIgnore
        public Instant syncedAtInstant() {
            return syncedAt > 0 ? Instant.ofEpochSecond(syncedAt) : null;
        }

        @JsonIgnore
        public boolean hasGraph() {
            return graphFetchedAt > 0;
        }
    }

    public static StoredAccount empty() {
        return new StoredAccount(VERSION, null, 0, null, null, null, null, null, null, null, null, 0, false);
    }

    // ==================== 时间区间（已入库范围） ====================

    /** 仅合并真实重叠区间，并按时间升序；时间精度不允许抹去尚未采集的缺口。 */
    public static List<long[]> mergeIntervals(List<long[]> raw) {
        List<long[]> sorted = new ArrayList<>();
        for (long[] r : raw) {
            if (r != null && r.length == 2 && r[1] >= r[0]) {
                sorted.add(new long[]{r[0], r[1]});
            }
        }
        sorted.sort(Comparator.comparingLong(r -> r[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] r : sorted) {
            if (!out.isEmpty() && r[0] <= out.get(out.size() - 1)[1]) {
                long[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /** [from, to] 中尚未入库的时间段，按「最新优先」排序 */
    public static List<long[]> missingRanges(List<long[]> coverage, long from, long to) {
        List<long[]> gaps = new ArrayList<>();
        long cursor = to;
        List<long[]> merged = mergeIntervals(coverage);
        for (int i = merged.size() - 1; i >= 0 && cursor > from; i--) {
            long[] iv = merged.get(i);
            if (iv[0] > cursor) {
                continue;
            }
            if (iv[1] < cursor) {
                gaps.add(new long[]{Math.max(iv[1], from), cursor});
            }
            cursor = Math.min(cursor, iv[0]);
        }
        if (cursor > from) {
            gaps.add(new long[]{from, cursor});
        }
        return gaps;
    }

    /** 从 anchor 往前连续已入库到的最早时间；anchor 不在任何区间内则返回 anchor 本身 */
    public static long contiguousFrom(List<long[]> coverage, long anchor) {
        long cursor = anchor;
        List<long[]> merged = mergeIntervals(coverage);
        for (int i = merged.size() - 1; i >= 0; i--) {
            long[] iv = merged.get(i);
            if (iv[1] >= cursor && iv[0] <= cursor) {
                cursor = iv[0];
            }
        }
        return cursor;
    }

    private static List<long[]> clipIntervals(List<long[]> coverage, long minFrom) {
        List<long[]> out = new ArrayList<>();
        for (long[] iv : mergeIntervals(coverage)) {
            if (iv[1] < minFrom) {
                continue;
            }
            out.add(new long[]{Math.max(iv[0], minFrom), iv[1]});
        }
        return out;
    }

    // ==================== 写入 / 裁剪 ====================

    /**
     * 合并本次新拉到的数据与已入库区间，并裁掉超过 30 天的内容。新数据覆盖同 id 旧数据（顺带刷新浏览、点赞等指标）。
     */
    public static StoredAccount merge(
            StoredAccount base,
            XUserSummary profile,
            Instant syncedAt,
            List<RawTweet> newTweets,
            List<InboundInteraction> newInbound,
            List<long[]> newTweetCoverage,
            List<long[]> newInboundCoverage,
            GraphUpdate graph
    ) {
        return merge(base, profile, syncedAt, newTweets, newInbound, newTweetCoverage, newInboundCoverage, graph, Map.of());
    }

    /** Stream coverage keeps its anchor; per-record metric timestamps describe when the response was observed. */
    public static StoredAccount merge(
            StoredAccount base, XUserSummary profile, Instant syncedAt,
            List<RawTweet> newTweets, List<InboundInteraction> newInbound,
            List<long[]> newTweetCoverage, List<long[]> newInboundCoverage, GraphUpdate graph,
            Map<String, Long> metricsObservedAt
    ) {
        return merge(base, profile, syncedAt, newTweets, newInbound, newTweetCoverage, newInboundCoverage, graph,
                metricsObservedAt, retainsUnboundAuthorHistory(profile));
    }

    public static StoredAccount merge(
            StoredAccount base, XUserSummary profile, Instant syncedAt, List<RawTweet> newTweets,
            List<InboundInteraction> newInbound, List<long[]> newTweetCoverage, List<long[]> newInboundCoverage,
            GraphUpdate graph, Map<String, Long> metricsObservedAt, boolean retainHistory
    ) {
        StoredAccount b = base != null ? base : empty();
        if (b.profile() != null) {
            if (profile == null || b.profile().id() == null || b.profile().id().isBlank()
                    || profile.id() == null || profile.id().isBlank()
                    || !b.profile().id().equals(profile.id())) {
                throw new IllegalStateException("数据隔离保护：账号 ID 不一致或缺失，禁止合并持久化数据");
            }
        }
        String selfKey = profile != null ? key(profile.username()) : (b.profile() != null ? key(b.profile().username()) : "");
        long minKeep = retainHistory ? 0L : syncedAt.minus(RECORD_RETENTION).getEpochSecond();
        long minAttemptMillis = retainHistory ? 0L : syncedAt.minus(RECORD_RETENTION).toEpochMilli();
        long minCoverage = retainHistory ? 0L : syncedAt.minus(RETENTION).getEpochSecond();

        long syncEpoch = syncedAt.getEpochSecond();
        Map<String, StoredUser> users = new LinkedHashMap<>(b.users());
        Set<String> graphHandles = new HashSet<>(b.followings());
        graphHandles.addAll(b.followers());
        Map<String, StoredTweet> tweets = new LinkedHashMap<>();
        for (StoredTweet t : b.tweets()) {
            tweets.put(t.id(), stampMetrics(t, b.syncedAt()));
        }
        for (RawTweet t : newTweets) {
            if (t == null || t.id() == null || t.id().isBlank() || t.createdAt() == null) {
                continue;
            }
            if (t.targetUser() != null) {
                putInteractionUser(users, graphHandles, t.targetUser(), syncEpoch);
            }
            long observedAt = metricsObservedAt.getOrDefault(t.id(), syncEpoch);
            StoredTweet next = stampMetrics(toStored(t, observedAt, metricsObservedAt.containsKey(t.id())), observedAt);
            StoredTweet previous = tweets.get(t.id());
            tweets.put(t.id(), previous == null ? next : mergeTweet(previous, next));
        }
        Map<String, StoredInbound> inbound = new LinkedHashMap<>();
        for (StoredInbound i : b.inbound()) {
            inbound.put(i.id(), i.withProfileEvidence(b.profile()));
        }
        for (InboundInteraction i : newInbound) {
            if (i == null || i.peer() == null || i.createdAt() == null) {
                continue;
            }
            putInteractionUser(users, graphHandles, i.peer(), syncEpoch);
            String id = (i.tweetId() == null || i.tweetId().isBlank())
                    ? ("x" + i.createdAt().getEpochSecond() + key(i.peer().username()))
                    : i.tweetId();
            StoredInbound next = toStored(i, id);
            StoredInbound previous = inbound.get(id);
            inbound.put(id, mergeInbound(previous, next));
        }

        List<StoredTweet> keptTweets = tweets.values().stream()
                .filter(t -> t.createdAt() >= minKeep)
                .sorted(Comparator.comparingLong(StoredTweet::createdAt).reversed())
                .toList();
        List<StoredInbound> keptInbound = inbound.values().stream()
                .filter(i -> i.createdAt() >= minKeep)
                .sorted(Comparator.comparingLong(StoredInbound::createdAt).reversed())
                .toList();

        List<long[]> tc = new ArrayList<>(b.tweetCoverage());
        tc.addAll(newTweetCoverage);
        List<long[]> ic = new ArrayList<>(b.inboundCoverage());
        ic.addAll(newInboundCoverage);

        List<String> followings = b.followings();
        List<String> followers = b.followers();
        Map<String, Integer> followingOrderById = b.followingOrderById();
        Map<String, Integer> followerOrderById = b.followerOrderById();
        List<String> explicitBack = b.explicitFollowedBy();
        long graphAt = b.graphFetchedAt();
        boolean followersTruncated = b.followersTruncated();
        boolean followingsTruncated = b.followingsTruncated();
        boolean graphRefreshFailed = b.graphRefreshFailed();
        if (graph != null) {
            graphRefreshFailed = graph.followingsFailed() || graph.followersFailed();
            if (graph.followingsFailed()) followingsTruncated = true;
            if (graph.followersFailed()) followersTruncated = true;
            if (!graph.followingsFailed()) {
                followings = new ArrayList<>();
                followingOrderById = graph.followingOrderById();
                followingsTruncated = graph.followingsTruncated();
                Set<String> retainedFollowers = graph.followersFailed() ? new HashSet<>(followers) : Set.of();
                for (XUserSummary u : graph.followings()) {
                    String handle = key(u.username());
                    if (retainedFollowers.contains(handle) && conflictingUserId(users.get(handle), u)) {
                        // The cached follower and the current following cannot share this handle's identity.
                        followingsTruncated = true;
                        continue;
                    }
                    putUser(users, u, syncEpoch);
                    followings.add(handle);
                }
                explicitBack = new ArrayList<>(graph.explicitFollowedBy());
            }
            if (!graph.followersFailed()) {
                followers = new ArrayList<>();
                followerOrderById = graph.followerOrderById();
                followersTruncated = graph.followersTruncated();
                Set<String> followingHandles = new HashSet<>(followings);
                for (XUserSummary u : graph.followers()) {
                    String handle = key(u.username());
                    if (followingHandles.contains(handle) && conflictingUserId(users.get(handle), u)) {
                        // 同名异 ID 不是互关；保留当前关注对象的身份，并显式保留粉丝图谱缺口。
                        followersTruncated = true;
                        continue;
                    }
                    putUser(users, u, syncEpoch);
                    followers.add(handle);
                }
                if (graph.followingsFailed() && !followersTruncated) {
                    // A complete current follower list supersedes old positive follow-back observations.
                    explicitBack = new ArrayList<>(explicitBack);
                    explicitBack.retainAll(new HashSet<>(followers));
                }
            }
            if (!graphRefreshFailed) graphAt = syncedAt.getEpochSecond();
        }

        // 用户字典只保留仍被引用的推友，避免无限增长
        Set<String> referenced = new HashSet<>(followings);
        referenced.addAll(followers);
        keptTweets.forEach(t -> {
            if (t.target() != null) referenced.add(t.target());
        });
        keptInbound.forEach(i -> referenced.add(i.peer()));
        users.keySet().retainAll(referenced);

        List<RelationshipEvidence> rawEvidence = graph != null ? graph.relationshipEvidence() : b.relationshipEvidence();
        List<RelationshipEvidence> keptEvidence = rawEvidence.stream()
                .filter(e -> e != null && e.checkedAt() >= minKeep)
                .toList();
        Map<String, Long> rawAttempted = graph != null ? graph.relationshipAttemptedAt() : b.relationshipAttemptedAt();
        Map<String, Long> keptAttempted = new LinkedHashMap<>();
        rawAttempted.forEach((k, ts) -> {
            if (k != null && ts != null && ts >= minAttemptMillis) {
                keptAttempted.put(k, ts);
            }
        });

        long syncedAtEpoch = Math.max(b.syncedAt(), syncedAt.getEpochSecond());
        return new StoredAccount(
                VERSION,
                profile != null ? profile : b.profile(),
                syncedAtEpoch,
                users,
                new ArrayList<>(keptTweets),
                new ArrayList<>(keptInbound),
                clipIntervals(tc, minCoverage),
                clipIntervals(ic, minCoverage),
                followings,
                followers,
                explicitBack,
                graphAt,
                followersTruncated, followingsTruncated, graphRefreshFailed,
                keptEvidence,
                keptAttempted,
                b.dailyRefreshStartedAt(), b.dailyRefreshedAt(), followingOrderById, followerOrderById,
                b.profile() == null || (b.tweetCoverage().isEmpty() && b.inboundCoverage().isEmpty())
                        ? VERIFIED_COVERAGE_VERSION : b.coverageProofVersion(), b.reportCompletedAt()
        );
    }

    /**
     * 无锁后台静默历史分片回填后的并发安全合并：在毫秒级锁内把后台补齐的推文/互动/覆盖区间无损并入最新状态，
     * 绝不回退前台刚更新的指标、关系图谱或小时刷新进度。
     */
    public static StoredAccount mergeStoredSlice(StoredAccount before, StoredAccount latest, StoredAccount sliceResult) {
        StoredAccount source = latest != null && latest.profile() != null ? latest : sliceResult;
        return mergeStoredSlice(before, latest, sliceResult,
                source != null && retainsUnboundAuthorHistory(source.profile()));
    }

    public static StoredAccount mergeStoredSlice(StoredAccount before, StoredAccount latest, StoredAccount sliceResult, boolean retainHistory) {
        if (latest == null || latest.profile() == null) return sliceResult;
        if (sliceResult == null || sliceResult.profile() == null) return latest;
        if (latest.profile().id() == null || latest.profile().id().isBlank()
                || !latest.profile().id().equals(sliceResult.profile().id())) {
            throw new IllegalStateException("数据隔离保护：静默历史合并账号身份不一致");
        }
        boolean sliceNewer = sliceResult.syncedAt() >= latest.syncedAt();
        XUserSummary profile = sliceNewer ? sliceResult.profile() : latest.profile();
        long syncedAt = Math.max(latest.syncedAt(), sliceResult.syncedAt());
        long minKeep = retainHistory ? 0L : syncedAt - RECORD_RETENTION.toSeconds();
        long minAttemptMillis = minKeep * 1000;
        long minCoverage = retainHistory ? 0L : syncedAt - RETENTION.toSeconds();

        Map<String, StoredUser> users = new LinkedHashMap<>(sliceNewer ? latest.users() : sliceResult.users());
        users.putAll(sliceNewer ? sliceResult.users() : latest.users());

        // A foreground refresh may have confirmed deletion while the background request was in flight.
        Set<String> removedDuringFetch = new HashSet<>();
        if (before != null) before.tweets().forEach(t -> removedDuringFetch.add(t.id()));
        latest.tweets().forEach(t -> removedDuringFetch.remove(t.id()));
        Map<String, StoredTweet> tweets = new LinkedHashMap<>();
        for (StoredTweet t : sliceResult.tweets()) {
            if (!removedDuringFetch.contains(t.id())) tweets.put(t.id(), stampMetrics(t, sliceResult.syncedAt()));
        }
        for (StoredTweet t : latest.tweets()) {
            StoredTweet cur = stampMetrics(t, latest.syncedAt());
            StoredTweet bg = tweets.get(cur.id());
            if (bg == null) {
                tweets.put(cur.id(), cur);
            } else {
                tweets.put(cur.id(), sliceResult.syncedAt() > latest.syncedAt() ? mergeTweet(cur, bg) : mergeTweet(bg, cur));
            }
        }

        Map<String, StoredInbound> inbound = new LinkedHashMap<>();
        for (StoredInbound i : sliceResult.inbound()) inbound.put(i.id(), i.withProfileEvidence(sliceResult.profile()));
        for (StoredInbound row : latest.inbound()) {
            StoredInbound cur = row.withProfileEvidence(latest.profile());
            inbound.put(cur.id(), mergeInbound(inbound.get(cur.id()), cur));
        }

        boolean latestProven = latest.coverageProofVersion() >= VERIFIED_COVERAGE_VERSION;
        boolean sliceProven = sliceResult.coverageProofVersion() >= VERIFIED_COVERAGE_VERSION;
        List<long[]> tc = new ArrayList<>();
        List<long[]> ic = new ArrayList<>();
        if (latestProven || !sliceProven) {
            tc.addAll(latest.tweetCoverage());
            ic.addAll(latest.inboundCoverage());
        }
        if (sliceProven || !latestProven) {
            tc.addAll(sliceResult.tweetCoverage());
            ic.addAll(sliceResult.inboundCoverage());
        } else if (before != null && before.coverageProofVersion() < VERIFIED_COVERAGE_VERSION) {
            // A foreground migration discarded the old ranges while this slice was fetching new gaps.
            sliceResult.tweetCoverage().forEach(r -> tc.addAll(missingRanges(before.tweetCoverage(), r[0], r[1])));
            sliceResult.inboundCoverage().forEach(r -> ic.addAll(missingRanges(before.inboundCoverage(), r[0], r[1])));
        }

        StoredAccount graphOwner = latest.graphFetchedAt() >= sliceResult.graphFetchedAt() ? latest : sliceResult;
        for (String handle : graphOwner.followings()) {
            if (graphOwner.users().containsKey(handle)) users.put(handle, graphOwner.users().get(handle));
        }
        for (String handle : graphOwner.followers()) {
            if (graphOwner.users().containsKey(handle)) users.put(handle, graphOwner.users().get(handle));
        }
        Map<String, RelationshipEvidence> evidenceByPeer = new LinkedHashMap<>();
        for (RelationshipEvidence e : sliceResult.relationshipEvidence()) {
            if (e != null && e.peerId() != null && e.checkedAt() >= minKeep) evidenceByPeer.put(e.peerId(), e);
        }
        for (RelationshipEvidence e : latest.relationshipEvidence()) {
            if (e != null && e.peerId() != null && e.checkedAt() >= minKeep) {
                RelationshipEvidence prev = evidenceByPeer.get(e.peerId());
                if (prev == null || e.checkedAt() >= prev.checkedAt()) evidenceByPeer.put(e.peerId(), e);
            }
        }
        Map<String, Long> attempted = new LinkedHashMap<>();
        sliceResult.relationshipAttemptedAt().forEach((k, ts) -> {
            if (k != null && ts != null && ts >= minAttemptMillis) attempted.put(k, ts);
        });
        latest.relationshipAttemptedAt().forEach((k, ts) -> {
            if (k != null && ts != null && ts >= minAttemptMillis) attempted.merge(k, ts, Math::max);
        });

        StoredAccount combined = new StoredAccount(
                VERSION,
                profile,
                syncedAt,
                users,
                tweets.values().stream().filter(t -> t.createdAt() >= minKeep)
                        .sorted(Comparator.comparingLong(StoredTweet::createdAt).reversed()).toList(),
                inbound.values().stream().filter(i -> i.createdAt() >= minKeep)
                        .sorted(Comparator.comparingLong(StoredInbound::createdAt).reversed()).toList(),
                clipIntervals(tc, minCoverage),
                clipIntervals(ic, minCoverage),
                graphOwner.followings(),
                graphOwner.followers(),
                graphOwner.explicitFollowedBy(),
                graphOwner.graphFetchedAt(),
                graphOwner.followersTruncated(),
                graphOwner.followingsTruncated(),
                graphOwner.graphRefreshFailed(),
                new ArrayList<>(evidenceByPeer.values()),
                attempted,
                Math.max(latest.dailyRefreshStartedAt(), sliceResult.dailyRefreshStartedAt()),
                Math.max(latest.dailyRefreshedAt(), sliceResult.dailyRefreshedAt()),
                graphOwner.followingOrderById(),
                graphOwner.followerOrderById(), latestProven || sliceProven ? VERIFIED_COVERAGE_VERSION : 0,
                latest.dailyRefreshedAt() == sliceResult.dailyRefreshedAt()
                        ? Math.max(latest.reportCompletedAt(), sliceResult.reportCompletedAt())
                        : latest.dailyRefreshedAt() > sliceResult.dailyRefreshedAt() ? latest.reportCompletedAt() : sliceResult.reportCompletedAt()
        );
        return pruneToRollingWindow(combined, Instant.ofEpochSecond(syncedAt), retainHistory);
    }

    private static boolean retainsUnboundAuthorHistory(XUserSummary profile) {
        return profile != null && com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID.equals(profile.id());
    }

    public static boolean retainsFullHistory(String username, String accountId, String boundOwnerId) {
        return com.xops.hub.model.AuthorIdentity.trustedId(boundOwnerId).equals(accountId);
    }

    /**
     * 普通账号保留基准时间之前 30 天的记录。覆盖区间保留翻页边界，作者使用长期归档。
     */
    public static StoredAccount pruneToRollingWindow(StoredAccount s, Instant referenceNow) {
        return pruneToRollingWindow(s, referenceNow, s != null && retainsUnboundAuthorHistory(s.profile()));
    }

    public static StoredAccount pruneToRollingWindow(StoredAccount s, Instant referenceNow, boolean retainHistory) {
        if (s == null) {
            return null;
        }
        if (retainHistory) {
            return s;
        }
        Instant ref = referenceNow != null
                ? referenceNow
                : (s.syncedAtInstant() != null ? s.syncedAtInstant() : Instant.now());
        long minKeep = ref.minus(RECORD_RETENTION).getEpochSecond();
        long minAttemptMillis = ref.minus(RECORD_RETENTION).toEpochMilli();
        long minCoverage = ref.minus(RETENTION).getEpochSecond();

        boolean hasStaleTweet = s.tweets().stream().anyMatch(t -> t.createdAt() < minKeep);
        boolean hasStaleInbound = s.inbound().stream().anyMatch(i -> i.createdAt() < minKeep);
        boolean hasStaleTc = s.tweetCoverage().stream().anyMatch(iv -> iv != null && iv.length == 2 && iv[0] < minCoverage);
        boolean hasStaleIc = s.inboundCoverage().stream().anyMatch(iv -> iv != null && iv.length == 2 && iv[0] < minCoverage);
        boolean hasStaleEvidence = s.relationshipEvidence().stream().anyMatch(e -> e == null || e.checkedAt() < minKeep);
        boolean hasStaleAttempt = s.relationshipAttemptedAt().values().stream().anyMatch(ts -> ts == null || ts < minAttemptMillis);
        if (!hasStaleTweet && !hasStaleInbound && !hasStaleTc && !hasStaleIc && !hasStaleEvidence && !hasStaleAttempt) {
            return s;
        }

        List<StoredTweet> keptTweets = s.tweets().stream()
                .filter(t -> t.createdAt() >= minKeep)
                .sorted(Comparator.comparingLong(StoredTweet::createdAt).reversed())
                .toList();
        List<StoredInbound> keptInbound = s.inbound().stream()
                .filter(i -> i.createdAt() >= minKeep)
                .sorted(Comparator.comparingLong(StoredInbound::createdAt).reversed())
                .toList();
        List<RelationshipEvidence> keptEvidence = s.relationshipEvidence().stream()
                .filter(e -> e != null && e.checkedAt() >= minKeep)
                .toList();
        Map<String, Long> keptAttempted = new LinkedHashMap<>();
        s.relationshipAttemptedAt().forEach((k, ts) -> {
            if (k != null && ts != null && ts >= minAttemptMillis) {
                keptAttempted.put(k, ts);
            }
        });

        Map<String, StoredUser> users = new LinkedHashMap<>(s.users());
        Set<String> referenced = new HashSet<>(s.followings());
        referenced.addAll(s.followers());
        keptTweets.forEach(t -> {
            if (t.target() != null) referenced.add(t.target());
        });
        keptInbound.forEach(i -> referenced.add(i.peer()));
        users.keySet().retainAll(referenced);

        return new StoredAccount(
                s.version(),
                s.profile(),
                s.syncedAt(),
                users,
                new ArrayList<>(keptTweets),
                new ArrayList<>(keptInbound),
                clipIntervals(s.tweetCoverage(), minCoverage),
                clipIntervals(s.inboundCoverage(), minCoverage),
                s.followings(),
                s.followers(),
                s.explicitFollowedBy(),
                s.graphFetchedAt(),
                s.followersTruncated(),
                s.followingsTruncated(),
                s.graphRefreshFailed(),
                keptEvidence,
                keptAttempted,
                s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt()
        );
    }

    /** Attempt time advances even for deleted/private IDs; metric values retain their last successful timestamp. */
    public static StoredAccount withMetricAttempts(StoredAccount s, List<String> ids, Instant attemptedAt) {
        Set<String> attempted = new HashSet<>(ids);
        List<StoredTweet> tweets = s.tweets().stream().map(t -> !attempted.contains(t.id()) ? t :
                new StoredTweet(t.id(), t.createdAt(), t.kind(), t.text(), t.target(), t.mentions(), t.flags(),
                        t.stats(), t.conversationId(), t.inReplyToId(), t.metricsFetchedAt(),
                        Math.max(t.metricsAttemptedAt(), attemptedAt.getEpochSecond()), t.unavailableMetrics(),
                        t.metricsTimeObserved(), t.targetId(), metricTimes(t, s.syncedAt()))).toList();
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), tweets, s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(), s.relationshipEvidence(), s.relationshipAttemptedAt(), s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt());
    }

    /** 剔除上游已确认删除/不可见的推文记录，避免已删推文持续污染 24h 计数并阻塞完整刷新标记。 */
    public static StoredAccount withoutTweets(StoredAccount s, Collection<String> deletedIds) {
        if (s == null || deletedIds == null || deletedIds.isEmpty()) {
            return s;
        }
        Set<String> removed = deletedIds instanceof Set<String> set ? set : new HashSet<>(deletedIds);
        List<StoredTweet> keptTweets = s.tweets().stream()
                .filter(t -> !removed.contains(t.id()))
                .toList();
        if (keptTweets.size() == s.tweets().size()) {
            return s;
        }
        Map<String, StoredUser> users = new LinkedHashMap<>(s.users());
        Set<String> referenced = new HashSet<>(s.followings());
        referenced.addAll(s.followers());
        keptTweets.forEach(t -> {
            if (t.target() != null) referenced.add(t.target());
        });
        s.inbound().forEach(i -> referenced.add(i.peer()));
        users.keySet().retainAll(referenced);
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), users, new ArrayList<>(keptTweets), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(),
                s.relationshipEvidence(), s.relationshipAttemptedAt(), s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt());
    }

    /** 整次同步失败时，保留历史数据，但逐人核验不能继续作为本轮确认结果。 */
    public static StoredAccount withoutRelationshipEvidence(StoredAccount s) {
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), s.tweets(), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(), List.of(), s.relationshipAttemptedAt(), s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt());
    }

    public static StoredAccount withRelationshipEvidence(StoredAccount s, List<RelationshipEvidence> evidence,
            Map<String, Long> attemptedAt) {
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), s.tweets(), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(), evidence, attemptedAt, s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt());
    }

    /** The completed timestamp advances only after both daily streams and their metric refresh finish. */
    public static StoredAccount withDailyRefreshState(StoredAccount s, long startedAt, long completedAt) {
        return withDailyRefreshState(s, startedAt, completedAt, completedAt == s.dailyRefreshedAt() ? s.reportCompletedAt() : 0);
    }

    public static StoredAccount withDailyRefreshState(StoredAccount s, long startedAt, long completedAt, long finishedAt) {
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), s.tweets(), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(),
                s.relationshipEvidence(), s.relationshipAttemptedAt(), startedAt, completedAt, s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), finishedAt);
    }

    /** Keep observed rows while replacing coverage claims whose source cannot be proven. */
    public static StoredAccount verifiedCoverageBase(StoredAccount store) {
        if (store == null || store.coverageProofVersion() >= VERIFIED_COVERAGE_VERSION) return store;
        StoredAccount cleared = withCoverage(store, List.of(), List.of());
        if (store.dailyRefreshedAt() > 0 && store.reportCompletedAt() < store.dailyRefreshedAt()) {
            return withDailyRefreshState(cleared, 0, 0, 0);
        }
        return cleared;
    }

    public static StoredAccount withCoverage(StoredAccount s, List<long[]> tweets, List<long[]> inbound) {
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), s.tweets(), s.inbound(),
                tweets, inbound, s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(),
                s.relationshipEvidence(), s.relationshipAttemptedAt(), s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt());
    }

    public record GraphUpdate(
            List<XUserSummary> followings,
            List<XUserSummary> followers,
            Set<String> explicitFollowedBy,
            boolean followersTruncated,
            boolean followingsTruncated,
            boolean followingsFailed,
            boolean followersFailed,
            List<RelationshipEvidence> relationshipEvidence,
            Map<String, Long> relationshipAttemptedAt,
            Map<String, Integer> followingOrderById,
            Map<String, Integer> followerOrderById
    ) {
        public GraphUpdate {
            relationshipEvidence = relationshipEvidence != null ? List.copyOf(relationshipEvidence) : List.of();
            relationshipAttemptedAt = relationshipAttemptedAt != null ? Map.copyOf(relationshipAttemptedAt) : Map.of();
            followingOrderById = followingOrderById != null ? Map.copyOf(followingOrderById) : Map.of();
            followerOrderById = followerOrderById != null ? Map.copyOf(followerOrderById) : Map.of();
        }
        public GraphUpdate(List<XUserSummary> followings, List<XUserSummary> followers,
                Set<String> explicitFollowedBy, boolean followersTruncated, boolean followingsTruncated,
                boolean followingsFailed, boolean followersFailed, List<RelationshipEvidence> relationshipEvidence,
                Map<String, Long> relationshipAttemptedAt) {
            this(followings, followers, explicitFollowedBy, followersTruncated, followingsTruncated,
                    followingsFailed, followersFailed, relationshipEvidence, relationshipAttemptedAt, Map.of(), Map.of());
        }
        public GraphUpdate(List<XUserSummary> followings, List<XUserSummary> followers,
                Set<String> explicitFollowedBy, boolean followersTruncated, boolean followingsTruncated,
                boolean followingsFailed, boolean followersFailed, List<RelationshipEvidence> relationshipEvidence) {
            this(followings, followers, explicitFollowedBy, followersTruncated, followingsTruncated,
                    followingsFailed, followersFailed, relationshipEvidence, Map.of());
        }
        public GraphUpdate(List<XUserSummary> followings, List<XUserSummary> followers,
                Set<String> explicitFollowedBy, boolean followersTruncated, boolean followingsTruncated,
                boolean followingsFailed, boolean followersFailed) {
            this(followings, followers, explicitFollowedBy, followersTruncated, followingsTruncated,
                    followingsFailed, followersFailed, List.of());
        }
        public GraphUpdate(List<XUserSummary> followings, List<XUserSummary> followers,
                Set<String> explicitFollowedBy, boolean followersTruncated) {
            this(followings, followers, explicitFollowedBy, followersTruncated, false, false, false);
        }
    }

    // ==================== 读出为分析快照 ====================

    public static List<XUserSummary> followingUsers(StoredAccount s) {
        return s.followings().stream().map(k -> toUser(k, s.users().get(k))).toList();
    }

    public static List<XUserSummary> followerUsers(StoredAccount s) {
        return s.followers().stream().map(k -> toUser(k, s.users().get(k))).toList();
    }

    /** 转为分析引擎使用的快照：覆盖范围按「从最近一次同步时间往前连续入库到哪里」如实计算 */
    public static RawAccountSnapshot toSnapshot(StoredAccount s, String bannerNotice, boolean degraded, int coveredWindowDays) {
        Instant syncedAt = s.syncedAtInstant() != null ? s.syncedAtInstant() : Instant.now();
        long anchor = syncedAt.getEpochSecond();
        Instant cutoff30 = syncedAt.minus(RETENTION);
        Instant tFrom = Instant.ofEpochSecond(contiguousFrom(s.tweetCoverage(), anchor));
        Instant iFrom = Instant.ofEpochSecond(contiguousFrom(s.inboundCoverage(), anchor));

        List<RawTweet> timeline = new ArrayList<>(s.tweets().size());
        for (StoredTweet t : s.tweets()) {
            timeline.add(toRaw(t, s.users()));
        }
        List<InboundInteraction> inbound = new ArrayList<>(s.inbound().size());
        for (StoredInbound i : s.inbound()) {
            inbound.add(new InboundInteraction(
                    i.id(),
                    recordedUser(i.peer(), i.peerId(), s.users().get(i.peer())),
                    Instant.ofEpochSecond(i.createdAt()),
                    i.kind() == 'Q' ? InboundKind.QUOTE_ME : (i.kind() == 'M' ? InboundKind.MENTION_ME : InboundKind.REPLY_TO_ME),
                    nz(i.snippet()),
                    nz(i.conversationId()),
                    nz(i.inReplyToId()),
                    nz(i.inReplyToUser()), nz(i.inReplyToUserId())
            ));
        }

        Map<String, RelationshipEntry> rel = new LinkedHashMap<>();
        Set<String> explicitBack = new HashSet<>(s.explicitFollowedBy());
        Set<String> followerSet = new HashSet<>(s.followers());
        for (String k : s.followings()) {
            XUserSummary user = toUser(k, s.users().get(k));
            rel.put(k, new RelationshipEntry(user, true, explicitBack.contains(k) || followerSet.contains(k), false,
                    s.followingOrderById().get(user.id()), s.followerOrderById().get(user.id())));
        }
        for (String k : s.followers()) {
            if (!rel.containsKey(k)) {
                XUserSummary user = toUser(k, s.users().get(k));
                rel.put(k, new RelationshipEntry(user, false, true, false,
                        s.followingOrderById().get(user.id()), s.followerOrderById().get(user.id())));
            }
        }

        // 与默认 5 分钟快照缓存一致；图谱可复用不意味着逐人结果能作为数小时后的实时结论。
        long oldestValidCheck = Instant.now().minus(Duration.ofMinutes(5)).getEpochSecond();
        for (RelationshipEvidence evidence : s.relationshipEvidence()) {
            String k = key(evidence.handle());
            if (evidence.peerId() == null || evidence.peerId().isBlank() || evidence.checkedAt() <= 0
                    || evidence.checkedAt() < s.graphFetchedAt() || evidence.checkedAt() <= oldestValidCheck
                    || !rel.containsKey(k)) continue;
            XUserSummary p = rel.get(k).user();
            if (p.id() == null || p.id().isBlank() || !p.id().equals(evidence.peerId())) continue;
            XUserSummary verifiedPeer = new XUserSummary(evidence.peerId(), p.username(), p.displayName(),
                    p.avatarUrl(), p.bio(), p.verified(), p.followersCount(), p.followingCount(), p.totalPostsCount(), p.joinedDate());
            RelationshipEntry previous = rel.get(k);
            rel.put(k, new RelationshipEntry(verifiedPeer, evidence.following(), evidence.followedBy(), true,
                    previous.followingOrder(), previous.followerOrder()));
        }

        boolean tEx = !tFrom.isAfter(cutoff30);
        boolean iEx = !iFrom.isAfter(cutoff30);
        return new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO,
                true,
                bannerNotice,
                syncedAt,
                s.profile(),
                timeline,
                inbound,
                new ArrayList<>(rel.values()),
                s.followersTruncated(),
                degraded,
                coveredWindowDays,
                tFrom, "", tEx,
                iFrom, "", iEx, s.followingsTruncated()
        );
    }

    // ==================== 文件读写 ====================

    public static StoredAccount read(Path file, ObjectMapper mapper) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Path cacheKey = file.toAbsolutePath().normalize();
        try {
            byte[] gz = Files.readAllBytes(file);
            CachedDiskStore cached = READ_CACHE.get(cacheKey);
            if (cached != null && Arrays.equals(cached.gzBytes(), gz)) {
                return cached.store();
            }
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                StoredAccount s = mapper.readValue(in, StoredAccount.class);
                StoredAccount valid = s != null && s.version() == VERSION ? s : null;
                if (valid != null) {
                    cacheDiskStore(cacheKey, gz, valid);
                } else {
                    READ_CACHE.remove(cacheKey);
                }
                return valid;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void write(Path file, StoredAccount s, ObjectMapper mapper) throws java.io.IOException {
        write(file, s, mapper, s != null && retainsUnboundAuthorHistory(s.profile()));
    }

    public static void write(Path file, StoredAccount s, ObjectMapper mapper, boolean retainHistory) throws java.io.IOException {
        StoredAccount normalized = s != null && s.syncedAtInstant() != null
                ? pruneToRollingWindow(s, s.syncedAtInstant(), retainHistory)
                : s;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32_768);
        try (GZIPOutputStream out = new GZIPOutputStream(buf)) {
            mapper.writeValue(out, normalized);
        }
        byte[] gz = buf.toByteArray();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + "."
                + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L) + ".tmp");
        try {
            Files.write(tmp, gz);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (normalized != null && normalized.version() == VERSION) {
                cacheDiskStore(file.toAbsolutePath().normalize(), gz, normalized);
            } else {
                READ_CACHE.remove(file.toAbsolutePath().normalize());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** A store removed or replaced outside this process can still have a decoded copy in the read cache. */
    public static void evictExpiredDiskReads(Path root, Instant now, String ownerId) {
        Path base = root.toAbsolutePath().normalize();
        READ_CACHE.forEach((path, cached) -> {
            StoredAccount account = cached.store();
            if (path.startsWith(base) && account.profile() != null && !ownerId.equals(account.profile().id())
                    && pruneToRollingWindow(account, now, false) != account) READ_CACHE.remove(path, cached);
        });
    }

    private static void cacheDiskStore(Path key, byte[] gzBytes, StoredAccount store) {
        if (READ_CACHE.size() >= MAX_READ_CACHE_ENTRIES && !READ_CACHE.containsKey(key)) {
            var it = READ_CACHE.keySet().iterator();
            if (it.hasNext()) {
                READ_CACHE.remove(it.next());
            }
        }
        READ_CACHE.put(key, new CachedDiskStore(gzBytes, store));
    }

    // ==================== 字段转换 ====================

    private static long metricValue(StoredTweet tweet, int index) {
        return tweet.stats() != null && index < tweet.stats().length ? tweet.stats()[index] : 0;
    }

    private static int metricRank(StoredTweet tweet, int index) {
        if (!tweet.unavailableMetrics().contains(METRIC_NAMES.get(index))) return 2;
        return metricValue(tweet, index) != 0 ? 1 : 0;
    }

    // Legacy rows share a timestamp. Capture it before advancing any attempt or account clock.
    private static long[] metricTimes(StoredTweet tweet, long snapshotAt) {
        if (tweet.metricEvidenceAt() != null && tweet.metricEvidenceAt().length == METRIC_NAMES.size()) {
            return tweet.metricEvidenceAt();
        }
        long when = tweet.metricsFetchedAt() > 0 ? tweet.metricsFetchedAt()
                : tweet.metricsAttemptedAt() > 0 ? tweet.metricsAttemptedAt() : snapshotAt;
        long[] times = new long[METRIC_NAMES.size()];
        for (int i = 0; i < times.length; i++) {
            if (metricRank(tweet, i) > 0) times[i] = when;
        }
        return times;
    }

    private static StoredTweet stampMetrics(StoredTweet t, long snapshotAt) {
        long[] times = metricTimes(t, snapshotAt);
        if (times == t.metricEvidenceAt()) return t;
        return new StoredTweet(t.id(), t.createdAt(), t.kind(), t.text(), t.target(), t.mentions(), t.flags(), t.stats(),
                t.conversationId(), t.inReplyToId(), t.metricsFetchedAt(), t.metricsAttemptedAt(),
                t.unavailableMetrics(), t.metricsTimeObserved(), t.targetId(), times);
    }

    private static boolean conflictingLink(String left, String right) {
        return left != null && !left.isBlank() && !"-".equals(left) && right != null && !right.isBlank()
                && !"-".equals(right) && !left.equals(right);
    }

    private static boolean extendsPreview(String full, String preview) {
        String longer = nz(full).stripTrailing(), shorter = nz(preview).stripTrailing();
        if (longer.length() <= shorter.length()) return false;
        if (shorter.endsWith("…")) shorter = shorter.substring(0, shorter.length() - 1).stripTrailing();
        else if (shorter.endsWith("...")) shorter = shorter.substring(0, shorter.length() - 3).stripTrailing();
        return longer.startsWith(shorter);
    }

    private static StoredInbound mergeInbound(StoredInbound previous, StoredInbound next) {
        if (previous == null || next.createdAt() != previous.createdAt() || next.kind() != previous.kind()
                || conflictingLink(next.inReplyToId(), previous.inReplyToId())
                || conflictingLink(next.inReplyToUserId(), previous.inReplyToUserId())) return next;
        boolean samePeer = next.peerId() != null && previous.peerId() != null
                ? next.peerId().equals(previous.peerId()) : Objects.equals(next.peer(), previous.peer());
        if (!samePeer) return next;
        String text = extendsPreview(previous.snippet(), next.snippet())
                ? previous.snippet() : next.snippet();
        return new StoredInbound(next.id(), next.createdAt(), next.kind(), next.peer(), text,
                nz(next.conversationId()).isBlank() ? previous.conversationId() : next.conversationId(),
                next.inReplyToId() != null ? next.inReplyToId() : previous.inReplyToId(),
                next.inReplyToUser() != null ? next.inReplyToUser() : previous.inReplyToUser(),
                next.peerId() != null ? next.peerId() : previous.peerId(),
                next.inReplyToUserId() != null ? next.inReplyToUserId() : previous.inReplyToUserId());
    }

    private static StoredTweet mergeTweet(StoredTweet older, StoredTweet newer) {
        if (older.createdAt() > 0 && newer.createdAt() > 0 && older.createdAt() != newer.createdAt()) return newer;
        String text = newer.text(), conversation = newer.conversationId(), targetId = newer.targetId();
        boolean compatibleTarget = !conflictingLink(older.inReplyToId(), newer.inReplyToId())
                && !conflictingLink(older.targetId(), newer.targetId())
                && (older.targetId() != null && newer.targetId() != null || !conflictingLink(key(older.target()), key(newer.target())));
        boolean sameLink = compatibleTarget && older.kind() == newer.kind();
        boolean mainPost = newer.kind() == 'P' || newer.kind() == 'Q';
        boolean mainBefore = older.kind() == 'P' || older.kind() == 'Q';
        if (compatibleTarget && (sameLink || mainPost && mainBefore) && (extendsPreview(older.text(), text)
                || sameLink && mainPost && nz(older.text()).stripTrailing().length() > nz(text).stripTrailing().length())) {
            text = older.text();
        }
        if (older.kind() == newer.kind() && Objects.equals(older.inReplyToId(), newer.inReplyToId())
                && Objects.equals(older.target(), newer.target())) {
            // The parent tweet identifies the conversation even if a peer handle was rebound to another ID.
            if ("-".equals(conversation) && (sameLink || !nz(newer.inReplyToId()).isBlank())) conversation = older.conversationId();
            if (targetId == null && sameLink) targetId = older.targetId();
        }
        long[] stats = new long[METRIC_NAMES.size()], times = new long[METRIC_NAMES.size()];
        Set<String> unavailable = new HashSet<>();
        for (int i = 0; i < stats.length; i++) {
            int compare = Integer.compare(metricRank(older, i), metricRank(newer, i));
            if (compare == 0) compare = Long.compare(older.metricEvidenceAt()[i], newer.metricEvidenceAt()[i]);
            if (compare == 0) compare = Long.compare(metricValue(older, i), metricValue(newer, i));
            StoredTweet winner = compare > 0 ? older : newer;
            stats[i] = metricValue(winner, i);
            times[i] = winner.metricEvidenceAt()[i];
            if (winner.unavailableMetrics().contains(METRIC_NAMES.get(i))) unavailable.add(METRIC_NAMES.get(i));
        }
        long fetched = Math.max(older.metricsFetchedAt(), newer.metricsFetchedAt());
        boolean observed = fetched > 0 && (older.metricsFetchedAt() == fetched && older.metricsTimeObserved()
                || newer.metricsFetchedAt() == fetched && newer.metricsTimeObserved());
        return new StoredTweet(newer.id(), newer.createdAt(), newer.kind(), text, newer.target(), newer.mentions(),
                newer.flags(), Arrays.stream(stats).allMatch(value -> value == 0) ? null : stats, conversation, newer.inReplyToId(), fetched,
                Math.max(older.metricsAttemptedAt(), newer.metricsAttemptedAt()), unavailable, observed, targetId, times);
    }

    private static StoredTweet toStored(RawTweet t, long metricsFetchedAt, boolean metricsTimeObserved) {
        char k = switch (t.kind()) {
            case POST -> 'P';
            case REPLY -> 'R';
            case QUOTE -> 'Q';
            case RETWEET -> 'T';
        };
        boolean fullText = t.kind() == TweetKind.POST || t.kind() == TweetKind.QUOTE;
        int flags = (t.hasMedia() ? 1 : 0) | (t.isThread() ? 2 : 0) | (t.selfThreadReply() ? 4 : 0);
        long[] stats = {t.viewCount(), t.likeCount(), t.replyCount(), t.retweetCount(), t.bookmarkCount()};
        boolean allZero = true;
        for (long v : stats) {
            if (v != 0) {
                allZero = false;
                break;
            }
        }
        String cv = nz(t.conversationId());
        List<String> mentions = t.mentionedUsernames() == null || t.mentionedUsernames().isEmpty() ? null
                : t.mentionedUsernames().stream().map(AccountStore::key).toList();
        return new StoredTweet(
                t.id(),
                t.createdAt().getEpochSecond(),
                k,
                fullText ? nz(t.text()) : clip(t.text(), REPLY_TEXT_MAX),
                t.targetUsername() == null || t.targetUsername().isBlank() ? null : key(t.targetUsername()),
                mentions,
                flags,
                allZero ? null : stats,
                cv.equals(t.id()) ? null : (cv.isEmpty() ? "-" : cv),
                emptyToNull(nz(t.inReplyToId())), t.unavailableMetrics().isEmpty() ? metricsFetchedAt : 0,
                metricsFetchedAt, t.unavailableMetrics(), metricsTimeObserved && t.unavailableMetrics().isEmpty(),
                t.targetUser() != null && key(t.targetUser().username()).equals(key(t.targetUsername()))
                        ? emptyToNull(t.targetUser().id()) : null
        );
    }

    private static StoredInbound toStored(InboundInteraction i, String id) {
        char k = switch (i.kind()) {
            case QUOTE_ME -> 'Q';
            case MENTION_ME -> 'M';
            case REPLY_TO_ME -> 'R';
        };
        return new StoredInbound(
                id,
                i.createdAt().getEpochSecond(),
                k,
                key(i.peer().username()),
                clip(i.snippet(), REPLY_TEXT_MAX),
                emptyToNull(nz(i.conversationId())),
                emptyToNull(nz(i.inReplyToId())),
                emptyToNull(i.inReplyToUsername()), emptyToNull(i.peer().id()), emptyToNull(i.inReplyToUserId())
        );
    }

    private static RawTweet toRaw(StoredTweet t, Map<String, StoredUser> users) {
        TweetKind kind = switch (t.kind()) {
            case 'R' -> TweetKind.REPLY;
            case 'Q' -> TweetKind.QUOTE;
            case 'T' -> TweetKind.RETWEET;
            default -> TweetKind.POST;
        };
        long[] s = t.stats() != null && t.stats().length == 5 ? t.stats() : new long[5];
        XUserSummary target = t.target() != null ? recordedUser(t.target(), t.targetId(), users.get(t.target())) : null;
        return new RawTweet(
                t.id(),
                nz(t.text()),
                Instant.ofEpochSecond(t.createdAt()),
                kind,
                t.target(),
                target,
                t.mentions() != null ? t.mentions() : List.of(),
                (t.flags() & 1) != 0,
                (t.flags() & 2) != 0,
                s[0], (int) s[1], (int) s[2], (int) s[3], (int) s[4],
                (t.flags() & 4) != 0,
                t.conversationId() == null ? t.id() : ("-".equals(t.conversationId()) ? "" : t.conversationId()),
                nz(t.inReplyToId()), t.unavailableMetrics()
        );
    }

    private static XUserSummary recordedUser(String handle, String recordedId, StoredUser current) {
        if (recordedId != null && current != null && recordedId.equals(current.id())) return toUser(handle, current);
        return new XUserSummary(nz(recordedId), handle, handle, "", "", false, 0, 0, 0, "");
    }

    private static boolean conflictingUserId(StoredUser known, XUserSummary observed) {
        return known != null && known.id() != null && observed.id() != null && !observed.id().isBlank()
                && !known.id().equals(observed.id());
    }

    private static void putInteractionUser(Map<String, StoredUser> users, Set<String> graphHandles, XUserSummary user, long nowEpoch) {
        String handle = key(user.username());
        StoredUser previous = users.get(handle);
        if (graphHandles.contains(handle) && previous != null && previous.id() != null && !previous.id().equals(user.id())) return;
        putUser(users, user, nowEpoch);
    }

    private static void putUser(Map<String, StoredUser> users, XUserSummary u, long nowEpoch) {
        if (u == null || u.username() == null || u.username().isBlank()) {
            return;
        }
        String k = key(u.username());
        StoredUser prev = users.get(k);
        boolean sameAccount = prev == null || emptyToNull(u.id()) == null || prev.id() == null
                || u.id().equals(prev.id());
        boolean nextVerified = u.verified();
        long nextVerifiedAt = u.verified() ? nowEpoch : 0L;
        if (prev != null && sameAccount) {
            boolean within15Days = prev.verifiedAt() > 0L && nowEpoch - prev.verifiedAt() < BlueVerifiedCache.TTL_SECONDS;
            if (prev.verified() && (within15Days || prev.verifiedAt() == 0L)) {
                // 蓝V以第一次为主，15天内不被无蓝V字段的列表接口覆盖
                nextVerified = true;
                nextVerifiedAt = prev.verifiedAt() > 0L ? prev.verifiedAt() : nowEpoch;
            } else if (within15Days) {
                nextVerified = prev.verified();
                nextVerifiedAt = prev.verifiedAt();
            } else if (!prev.verified() && prev.verifiedAt() == 0L && u.verified()) {
                nextVerified = true;
                nextVerifiedAt = nowEpoch;
            }
        }

        StoredUser next = new StoredUser(
                emptyToNull(u.displayName() != null && !u.displayName().equals(u.username()) ? u.displayName() : null),
                emptyToNull(u.avatarUrl()),
                nextVerified,
                Math.max(0, u.followersCount()),
                Math.max(0, u.followingCount()),
                emptyToNull(clip(u.bio(), BIO_MAX)),
                emptyToNull(u.id()), u.totalPostsCount(), emptyToNull(u.joinedDate()),
                nextVerifiedAt
        );
        // 只用信息更完整的资料覆盖（推文里附带的用户信息有时比关注列表更简略）
        if (prev == null || !sameAccount || richness(next) >= richness(prev)) {
            if (prev != null && sameAccount && next.id() == null) {
                next = new StoredUser(next.displayName(), next.avatarUrl(), next.verified(), next.followers(), next.following(),
                        next.bio(), prev.id(), next.totalPosts(), next.joinedDate(), next.verifiedAt());
            }
            users.put(k, next);
        } else if (prev.verified() != nextVerified || prev.verifiedAt() != nextVerifiedAt) {
            users.put(k, new StoredUser(prev.displayName(), prev.avatarUrl(), nextVerified, prev.followers(),
                    prev.following(), prev.bio(), prev.id(), prev.totalPosts(), prev.joinedDate(), nextVerifiedAt));
        }
    }

    /** 用全局 15 天蓝V缓存及批量补充的用户资料回填账户字典（以第一次观测为主，满 15 天刷新）。 */
    public static StoredAccount applyBlueVerifiedCache(
            StoredAccount s,
            Map<String, XUserSummary> extraUsersByHandle,
            BlueVerifiedCache cache,
            long nowEpoch
    ) {
        if (s == null) {
            return null;
        }
        Map<String, StoredUser> users = new LinkedHashMap<>(s.users());
        if (extraUsersByHandle != null) {
            extraUsersByHandle.values().forEach(u -> putUser(users, u, nowEpoch));
        }
        if (cache != null) {
            for (Map.Entry<String, StoredUser> entry : new ArrayList<>(users.entrySet())) {
                StoredUser u = entry.getValue();
                if (u == null) continue;
                BlueVerifiedCache.Entry cached = cache.resolveValidEntry(u.id(), entry.getKey(), nowEpoch);
                if (cached != null) {
                    boolean keepFirst = u.verifiedAt() > 0L && nowEpoch - u.verifiedAt() < BlueVerifiedCache.TTL_SECONDS;
                    boolean resolvedV = keepFirst ? u.verified() : cached.verified();
                    long resolvedAt = keepFirst ? u.verifiedAt() : cached.firstObservedAt();
                    if (u.verified() != resolvedV || u.verifiedAt() != resolvedAt) {
                        users.put(entry.getKey(), new StoredUser(u.displayName(), u.avatarUrl(), resolvedV, u.followers(),
                                u.following(), u.bio(), u.id(), u.totalPosts(), u.joinedDate(), resolvedAt));
                    }
                }
            }
        }
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), users, s.tweets(), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(),
                s.relationshipEvidence(), s.relationshipAttemptedAt(), s.dailyRefreshStartedAt(), s.dailyRefreshedAt(), s.followingOrderById(), s.followerOrderById(), s.coverageProofVersion(), s.reportCompletedAt());
    }

    private static int richness(StoredUser u) {
        return (u.displayName() != null ? 3 : 0) + (u.avatarUrl() != null ? 3 : 0) + (u.bio() != null ? 2 : 0) + (u.followers() > 0 ? 2 : 0);
    }

    private static XUserSummary toUser(String k, StoredUser u) {
        if (u == null) {
            return new XUserSummary("", k, k, "", "", false, 0, 0, 0, "");
        }
        return new XUserSummary(nz(u.id()), k, u.displayName() != null ? u.displayName() : k, nz(u.avatarUrl()), nz(u.bio()),
                u.verified(), u.followers(), u.following(), u.totalPosts(), nz(u.joinedDate()));
    }

    private static String key(String username) {
        return username == null ? "" : username.replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.codePointCount(0, s.length()) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max));
    }
}
