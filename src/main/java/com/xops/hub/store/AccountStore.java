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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 单账号 30 天滚动持久化存储（GZIP 压缩 JSON，短字段名，只保留分析必需字段）。
 * <p>
 * 用「已入库时间区间」标记哪些时间段的数据已完整拉取：之后只请求区间之外的部分，超过 30 天的数据与区间自动裁掉。
 */
public final class AccountStore {

    public static final int VERSION = 1;
    /** 保留 30 天 + 1 小时余量，与拉取侧 30 天截止点一致 */
    public static final Duration RETENTION = Duration.ofHours(721);
    private static final int REPLY_TEXT_MAX = 120;
    private static final int BIO_MAX = 100;

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
            @JsonProperty("j") String joinedDate
    ) {
        public StoredUser(String displayName, String avatarUrl, boolean verified, int followers, int following, String bio) {
            this(displayName, avatarUrl, verified, followers, following, bio, null, 0, null);
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
            @JsonProperty("um") @JsonInclude(JsonInclude.Include.ALWAYS) Set<String> unavailableMetrics
    ) {
        public StoredTweet {
            unavailableMetrics = unavailableMetrics == null ? RawTweet.ALL_METRICS : Set.copyOf(unavailableMetrics);
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

    /** 别人对我的互动。k: R=回复我 Q=引用我 M=提及我；ru 仅在被回复人不是本人时保存 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record StoredInbound(
            @JsonProperty("i") String id,
            @JsonProperty("c") long createdAt,
            @JsonProperty("k") char kind,
            @JsonProperty("p") String peer,
            @JsonProperty("x") String snippet,
            @JsonProperty("cv") String conversationId,
            @JsonProperty("r") String inReplyToId,
            @JsonProperty("ru") String inReplyToUser
    ) {
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
            @JsonProperty("ra") Map<String, Long> relationshipAttemptedAt
    ) {
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
        StoredAccount b = base != null ? base : empty();
        if (b.profile() != null) {
            if (profile == null || b.profile().id() == null || b.profile().id().isBlank()
                    || profile.id() == null || profile.id().isBlank()
                    || !b.profile().id().equals(profile.id())) {
                throw new IllegalStateException("数据隔离保护：账号 ID 不一致或缺失，禁止合并持久化数据");
            }
            if (b.profile().username() == null || profile.username() == null
                    || !b.profile().username().equalsIgnoreCase(profile.username())) {
                throw new IllegalStateException("账号用户名已变更，请重新登录确认账号，并联系管理员迁移旧用户名数据后重试");
            }
        }
        String selfKey = profile != null ? key(profile.username()) : (b.profile() != null ? key(b.profile().username()) : "");
        long minKeep = syncedAt.minus(RETENTION).getEpochSecond();

        Map<String, StoredUser> users = new LinkedHashMap<>(b.users());
        Map<String, StoredTweet> tweets = new LinkedHashMap<>();
        for (StoredTweet t : b.tweets()) {
            tweets.put(t.id(), t);
        }
        for (RawTweet t : newTweets) {
            if (t == null || t.id() == null || t.id().isBlank() || t.createdAt() == null) {
                continue;
            }
            if (t.targetUser() != null) {
                putUser(users, t.targetUser());
            }
            StoredTweet next = toStored(t, syncedAt.getEpochSecond());
            StoredTweet previous = tweets.get(t.id());
            if (previous != null && !next.unavailableMetrics().isEmpty()) {
                long[] stats = next.stats() != null ? next.stats().clone() : new long[5];
                long[] oldStats = previous.stats() != null ? previous.stats() : new long[5];
                Set<String> unavailable = new HashSet<>(next.unavailableMetrics());
                String[] metricNames = {"views", "likes", "replies", "reposts", "bookmarks"};
                for (int i = 0; i < metricNames.length; i++) {
                    if (unavailable.contains(metricNames[i]) && !previous.unavailableMetrics().contains(metricNames[i])) {
                        stats[i] = oldStats[i];
                        unavailable.remove(metricNames[i]);
                    }
                }
                next = new StoredTweet(next.id(), next.createdAt(), next.kind(), next.text(), next.target(), next.mentions(),
                        next.flags(), stats, next.conversationId(), next.inReplyToId(), previous.metricsFetchedAt(),
                        next.metricsAttemptedAt(), unavailable);
            }
            tweets.put(t.id(), next);
        }
        Map<String, StoredInbound> inbound = new LinkedHashMap<>();
        for (StoredInbound i : b.inbound()) {
            inbound.put(i.id(), i);
        }
        for (InboundInteraction i : newInbound) {
            if (i == null || i.peer() == null || i.createdAt() == null) {
                continue;
            }
            putUser(users, i.peer());
            String id = (i.tweetId() == null || i.tweetId().isBlank())
                    ? ("x" + i.createdAt().getEpochSecond() + key(i.peer().username()))
                    : i.tweetId();
            inbound.put(id, toStored(i, id, selfKey));
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
                for (XUserSummary u : graph.followings()) {
                    putUser(users, u);
                    followings.add(key(u.username()));
                }
                explicitBack = new ArrayList<>(graph.explicitFollowedBy());
                followingsTruncated = graph.followingsTruncated();
            }
            if (!graph.followersFailed()) {
                followers = new ArrayList<>();
                followersTruncated = graph.followersTruncated();
                for (XUserSummary u : graph.followers()) {
                    StoredUser known = users.get(key(u.username()));
                    if (followings.contains(key(u.username())) && known != null && known.id() != null
                            && u.id() != null && !u.id().isBlank() && !known.id().equals(u.id())) {
                        // 同名异 ID 不是互关；保留当前关注对象的身份，并显式保留粉丝图谱缺口。
                        followersTruncated = true;
                        continue;
                    }
                    putUser(users, u);
                    followers.add(key(u.username()));
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

        long syncedAtEpoch = Math.max(b.syncedAt(), syncedAt.getEpochSecond());
        return new StoredAccount(
                VERSION,
                profile != null ? profile : b.profile(),
                syncedAtEpoch,
                users,
                new ArrayList<>(keptTweets),
                new ArrayList<>(keptInbound),
                clipIntervals(tc, minKeep),
                clipIntervals(ic, minKeep),
                followings,
                followers,
                explicitBack,
                graphAt,
                followersTruncated, followingsTruncated, graphRefreshFailed,
                graph != null ? graph.relationshipEvidence() : b.relationshipEvidence(),
                graph != null ? graph.relationshipAttemptedAt() : b.relationshipAttemptedAt()
        );
    }

    /** Attempt time advances even for deleted/private IDs; metric values retain their last successful timestamp. */
    public static StoredAccount withMetricAttempts(StoredAccount s, List<String> ids, Instant attemptedAt) {
        Set<String> attempted = new HashSet<>(ids);
        List<StoredTweet> tweets = s.tweets().stream().map(t -> !attempted.contains(t.id()) ? t :
                new StoredTweet(t.id(), t.createdAt(), t.kind(), t.text(), t.target(), t.mentions(), t.flags(),
                        t.stats(), t.conversationId(), t.inReplyToId(), t.metricsFetchedAt(), attemptedAt.getEpochSecond(), t.unavailableMetrics())).toList();
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), tweets, s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(), s.relationshipEvidence(), s.relationshipAttemptedAt());
    }

    /** 整次同步失败时，保留历史数据，但逐人核验不能继续作为本轮确认结果。 */
    public static StoredAccount withoutRelationshipEvidence(StoredAccount s) {
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), s.tweets(), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(), List.of(), s.relationshipAttemptedAt());
    }

    public static StoredAccount withRelationshipEvidence(StoredAccount s, List<RelationshipEvidence> evidence,
            Map<String, Long> attemptedAt) {
        return new StoredAccount(s.version(), s.profile(), s.syncedAt(), s.users(), s.tweets(), s.inbound(),
                s.tweetCoverage(), s.inboundCoverage(), s.followings(), s.followers(), s.explicitFollowedBy(),
                s.graphFetchedAt(), s.followersTruncated(), s.followingsTruncated(), s.graphRefreshFailed(), evidence, attemptedAt);
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
            Map<String, Long> relationshipAttemptedAt
    ) {
        public GraphUpdate {
            relationshipEvidence = relationshipEvidence != null ? List.copyOf(relationshipEvidence) : List.of();
            relationshipAttemptedAt = relationshipAttemptedAt != null ? Map.copyOf(relationshipAttemptedAt) : Map.of();
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
        String selfName = s.profile() != null ? s.profile().username() : "";
        for (StoredInbound i : s.inbound()) {
            inbound.add(new InboundInteraction(
                    i.id(),
                    toUser(i.peer(), s.users().get(i.peer())),
                    Instant.ofEpochSecond(i.createdAt()),
                    i.kind() == 'Q' ? InboundKind.QUOTE_ME : (i.kind() == 'M' ? InboundKind.MENTION_ME : InboundKind.REPLY_TO_ME),
                    nz(i.snippet()),
                    nz(i.conversationId()),
                    nz(i.inReplyToId()),
                    i.inReplyToUser() != null ? i.inReplyToUser() : (i.kind() == 'R' ? selfName : "")
            ));
        }

        Map<String, RelationshipEntry> rel = new LinkedHashMap<>();
        Set<String> explicitBack = new HashSet<>(s.explicitFollowedBy());
        Set<String> followerSet = new HashSet<>(s.followers());
        for (String k : s.followings()) {
            rel.put(k, new RelationshipEntry(toUser(k, s.users().get(k)), true, explicitBack.contains(k) || followerSet.contains(k)));
        }
        for (String k : s.followers()) {
            if (!rel.containsKey(k)) {
                rel.put(k, new RelationshipEntry(toUser(k, s.users().get(k)), false, true));
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
            rel.put(k, new RelationshipEntry(verifiedPeer, evidence.following(), evidence.followedBy(), true));
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
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            StoredAccount s = mapper.readValue(in, StoredAccount.class);
            return s != null && s.version() == VERSION ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void write(Path file, StoredAccount s, ObjectMapper mapper) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + "."
                + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L) + ".tmp");
        try {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                mapper.writeValue(out, s);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ==================== 字段转换 ====================

    private static StoredTweet toStored(RawTweet t, long metricsFetchedAt) {
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
                metricsFetchedAt, t.unavailableMetrics()
        );
    }

    private static StoredInbound toStored(InboundInteraction i, String id, String selfKey) {
        char k = switch (i.kind()) {
            case QUOTE_ME -> 'Q';
            case MENTION_ME -> 'M';
            case REPLY_TO_ME -> 'R';
        };
        String ru = nz(i.inReplyToUsername());
        boolean ruIsSelf = key(ru).equals(selfKey);
        return new StoredInbound(
                id,
                i.createdAt().getEpochSecond(),
                k,
                key(i.peer().username()),
                clip(i.snippet(), REPLY_TEXT_MAX),
                emptyToNull(nz(i.conversationId())),
                emptyToNull(nz(i.inReplyToId())),
                (k == 'R' && ruIsSelf) || ru.isBlank() ? null : ru
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
        XUserSummary target = t.target() != null ? toUser(t.target(), users.get(t.target())) : null;
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

    private static void putUser(Map<String, StoredUser> users, XUserSummary u) {
        if (u == null || u.username() == null || u.username().isBlank()) {
            return;
        }
        String k = key(u.username());
        StoredUser prev = users.get(k);
        StoredUser next = new StoredUser(
                emptyToNull(u.displayName() != null && !u.displayName().equals(u.username()) ? u.displayName() : null),
                emptyToNull(u.avatarUrl()),
                u.verified(),
                Math.max(0, u.followersCount()),
                Math.max(0, u.followingCount()),
                emptyToNull(clip(u.bio(), BIO_MAX)),
                emptyToNull(u.id()), u.totalPostsCount(), emptyToNull(u.joinedDate())
        );
        // 只用信息更完整的资料覆盖（推文里附带的用户信息有时比关注列表更简略）
        if (prev == null || (next.id() != null && !next.id().equals(prev.id())) || richness(next) >= richness(prev)) {
            if (prev != null && next.id() == null) {
                next = new StoredUser(next.displayName(), next.avatarUrl(), next.verified(), next.followers(), next.following(),
                        next.bio(), prev.id(), next.totalPosts(), next.joinedDate());
            }
            users.put(k, next);
        }
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
