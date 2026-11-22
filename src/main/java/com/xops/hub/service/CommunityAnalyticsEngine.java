package com.xops.hub.service;

import com.xops.hub.model.AnalyticsReportModels.*;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.model.XAlgorithmProfile;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯领域分析引擎：支持「24小时 / 48小时 / 7天 / 30天动态窗口」多维切片，
 * 支持注入可热更新的 XAlgorithmProfile，驱动单帖本地启发式评分与运营推荐生成。
 */
public class CommunityAnalyticsEngine {

    private static final String[] DAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final DateTimeFormatter SHORT_DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter RANGE_DATE_FMT = DateTimeFormatter.ofPattern("MM月dd日");
    private static final DateTimeFormatter RANGE_HOUR_FMT = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    private static final DateTimeFormatter HOUR_LABEL_FMT = DateTimeFormatter.ofPattern("HH:00");
    private static final DateTimeFormatter POST_TIME_FMT = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter TWITTER_JOINED_DATE_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss Z uuuu", Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.STRICT);

    private final Supplier<XAlgorithmProfile> profileSupplier;

    public CommunityAnalyticsEngine() {
        this(XAlgorithmProfileRegistry::defaultProfile);
    }

    public CommunityAnalyticsEngine(Supplier<XAlgorithmProfile> profileSupplier) {
        this.profileSupplier = profileSupplier != null ? profileSupplier : XAlgorithmProfileRegistry::defaultProfile;
    }

    private XAlgorithmProfile activeAlgoProfile() {
        XAlgorithmProfile raw = profileSupplier.get();
        XAlgorithmProfile fallback = XAlgorithmProfileRegistry.defaultProfile();
        return raw != null ? raw.normalizedWithDefaults(fallback) : fallback;
    }

    public CommunityDashboardReport analyze(
            RawAccountSnapshot snapshot,
            int requestedDays,
            ZoneId zoneId,
            boolean fromCache
    ) {
        Instant fetched = snapshot.fetchedAt() != null ? snapshot.fetchedAt() : Instant.now();
        return analyze(snapshot, requestedDays, zoneId, fromCache, 3600L, fetched.plusSeconds(3600L));
    }

    public enum ReportPeriod {
        HOURS_24("HOURS_24", "24小时动态窗口", "24h战报", 1, 24, false),
        HOURS_48("HOURS_48", "48小时动态窗口", "48h战报", 2, 48, false),
        WEEK("WEEK", "7天动态窗口", "7天战报", 7, 7 * 24, true),
        MONTH("MONTH", "30天动态窗口", "30天战报", 30, 30 * 24, true);

        public final String code;
        public final String label;
        public final String shortTitle;
        public final int windowDays;
        public final int windowHours;
        public final boolean dailyBuckets;

        ReportPeriod(String code, String label, String shortTitle, int windowDays, int windowHours, boolean dailyBuckets) {
            this.code = code;
            this.label = label;
            this.shortTitle = shortTitle;
            this.windowDays = windowDays;
            this.windowHours = windowHours;
            this.dailyBuckets = dailyBuckets;
        }

        public static ReportPeriod fromDays(int requestedDays) {
            int resolved = resolvePeriodDays(requestedDays);
            return resolved == 2 ? HOURS_48 : (resolved == 7 ? WEEK : MONTH);
        }
    }

    public CommunityDashboardReport analyze(
            RawAccountSnapshot snapshot,
            int requestedDays,
            ZoneId zoneId,
            boolean fromCache,
            long cacheTtlSeconds,
            Instant expiresAt
    ) {
        return analyze(snapshot, ReportPeriod.fromDays(requestedDays), false, zoneId, fromCache, cacheTtlSeconds, expiresAt);
    }

    public CommunityDashboardReport analyze(
            RawAccountSnapshot snapshot,
            ReportPeriod period,
            boolean accumulatedSummaryMode,
            ZoneId zoneId,
            boolean fromCache,
            long cacheTtlSeconds,
            Instant expiresAt
    ) {
        ReportPeriod resolvedPeriod = period != null ? period : ReportPeriod.HOURS_24;
        int windowDays = resolvedPeriod.windowDays;
        String periodCode = resolvedPeriod.code;
        String periodLabel = resolvedPeriod.label;
        String reportShortTitle = resolvedPeriod.shortTitle;

        Instant refNow = snapshot.fetchedAt() != null ? snapshot.fetchedAt() : Instant.now();
        Instant cutoff = resolvedPeriod.dailyBuckets
                ? refNow.minus(Duration.ofDays(resolvedPeriod.windowDays))
                : refNow.minus(Duration.ofHours(resolvedPeriod.windowHours));
        String selfKey = snapshot.profile().username().toLowerCase(Locale.ROOT);

        List<RawTweet> allTimeline = snapshot.timeline() != null ? snapshot.timeline() : List.of();
        List<InboundInteraction> allInbound = snapshot.inboundInteractions() != null
                ? snapshot.inboundInteractions()
                : List.of();

        // 两类数据（我的动态 / 别人对我的互动）各自真实覆盖到的最早时间；取两者中较晚者作为「共同覆盖起点」，
        // 保证两边在同一时间段内对比，避免一边有数据、另一边是盲区导致「我没回谁 / 评论待回复」误判
        Instant timelineStart = snapshot.timelineExhausted() ? null
                : (snapshot.timelineTailTime() != null ? snapshot.timelineTailTime()
                : allTimeline.stream().map(RawTweet::createdAt).filter(ts -> ts != null).min(Comparator.naturalOrder()).orElse(refNow));
        Instant mentionsStart = snapshot.mentionsExhausted() ? null
                : (snapshot.mentionsTailTime() != null ? snapshot.mentionsTailTime()
                : allInbound.stream().map(InboundInteraction::createdAt).filter(ts -> ts != null).min(Comparator.naturalOrder()).orElse(refNow));
        Instant coverageStart = timelineStart == null ? mentionsStart
                : (mentionsStart == null ? timelineStart : (timelineStart.isAfter(mentionsStart) ? timelineStart : mentionsStart));
        boolean coverageComplete = coverageStart == null || !coverageStart.isAfter(cutoff);
        boolean sampleCapped = !coverageComplete && !accumulatedSummaryMode;
        Instant effectiveCutoff = (coverageComplete || accumulatedSummaryMode) ? cutoff : coverageStart;

        List<RawTweet> windowTimeline = allTimeline.stream()
                .filter(t -> t.createdAt() != null && !t.createdAt().isBefore(effectiveCutoff) && !t.createdAt().isAfter(refNow))
                .sorted(Comparator.comparing(RawTweet::createdAt).reversed())
                .toList();
        List<InboundInteraction> windowInbound = allInbound.stream()
                .filter(i -> i.createdAt() != null && !i.createdAt().isBefore(effectiveCutoff) && !i.createdAt().isAfter(refNow))
                .sorted(Comparator.comparing(InboundInteraction::createdAt).reversed())
                .toList();

        double fullWindowDays = resolvedPeriod.windowHours / 24.0;
        double coveredHours = coverageComplete ? resolvedPeriod.windowHours
                : Math.max(0.0, Duration.between(coverageStart, refNow).toMinutes() / 60.0);
        double effectiveCoveredDays = (coverageComplete || accumulatedSummaryMode)
                ? fullWindowDays
                : Math.min(fullWindowDays, coveredHours / 24.0);
        String coverageNote;
        if (coverageComplete) {
            coverageNote = "";
        } else if (accumulatedSummaryMode) {
            coverageNote = "按每日累计最后一次同步快照汇总（非全量回溯）";
        } else {
            coverageNote = String.format(
                    "实际数据覆盖 %s 起（约 %s），更早数据仍在补齐",
                    coverageStart.atZone(zoneId).format(RANGE_HOUR_FMT),
                    coveredHours >= 48 ? String.format("%.1f 天", coveredHours / 24.0) : String.format("%.0f 小时", Math.floor(coveredHours))
            );
        }

        XAlgorithmProfile algo = activeAlgoProfile();
        Map<String, RelationshipEntry> relByKey = indexRelationships(snapshot.relationships(), selfKey);

        ActivityOverview activity = buildActivityOverview(
                resolvedPeriod,
                effectiveCoveredDays,
                sampleCapped,
                windowTimeline,
                cutoff,
                refNow,
                zoneId,
                coverageNote,
                algo
        );
        InteractionOverview interactions = buildInteractionOverview(
                selfKey,
                allTimeline,
                allInbound,
                windowTimeline,
                windowInbound,
                relByKey,
                snapshot.followersTruncated(),
                snapshot.followingsTruncated(),
                algo
        );
        FollowAuditOverview followAudit = buildFollowAuditOverview(
                relByKey,
                interactions.peerStats(),
                periodLabel,
                snapshot.followersTruncated(),
                snapshot.followingsTruncated(),
                sampleCapped,
                observedInteractionPeers(windowTimeline, windowInbound, selfKey),
                quoteCountsByPeer(windowTimeline, windowInbound, selfKey)
        );
        Map<String, Long> mainPostGapMinutesById = computeMainPostGapsMinutes(allTimeline);
        Set<String> authorEngagedMainPostIds = computeAuthorEngagedMainPostIds(allTimeline, allInbound, selfKey);

        ContentTimingOverview contentTiming = buildContentTimingOverview(
                snapshot.profile(),
                windowTimeline,
                mainPostGapMinutesById,
                authorEngagedMainPostIds,
                zoneId,
                algo
        );
        InnerCircleOverview innerCircle = buildInnerCircleOverview(
                snapshot.profile(),
                reportShortTitle,
                periodLabel,
                activity,
                interactions,
                followAudit
        );
        AlgorithmHealthSummary algorithmHealth = buildAlgorithmHealthSummary(
                snapshot.profile(),
                periodLabel,
                allTimeline,
                windowTimeline,
                mainPostGapMinutesById,
                authorEngagedMainPostIds,
                activity,
                interactions,
                followAudit,
                contentTiming,
                refNow,
                zoneId,
                algo
        );

        Instant safeExpiresAt = expiresAt != null ? expiresAt : refNow.plusSeconds(cacheTtlSeconds);
        long remainingSeconds = Math.max(0L, Duration.between(Instant.now(), safeExpiresAt).getSeconds());

        return new CommunityDashboardReport(
                snapshot.resolvedProvider().name(),
                snapshot.liveData(),
                fromCache,
                cacheTtlSeconds,
                remainingSeconds,
                refNow,
                safeExpiresAt,
                snapshot.bannerNotice(),
                periodCode,
                periodLabel,
                windowDays,
                snapshot.profile(),
                activity,
                interactions,
                followAudit,
                contentTiming,
                innerCircle,
                algorithmHealth
        );
    }

    public static int resolvePeriodDays(int requestedDays) {
        if (requestedDays <= 2) {
            return 2;
        }
        if (requestedDays <= 7) {
            return 7;
        }
        return 30;
    }

    private Map<String, RelationshipEntry> indexRelationships(List<RelationshipEntry> entries, String selfKey) {
        Map<String, RelationshipEntry> map = new LinkedHashMap<>();
        if (entries == null) {
            return map;
        }
        for (RelationshipEntry entry : entries) {
            if (entry == null || entry.user() == null || entry.user().username() == null) {
                continue;
            }
            String key = entry.user().username().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || key.equals(selfKey)) {
                continue;
            }
            RelationshipEntry prev = map.get(key);
            if (prev == null) {
                map.put(key, entry);
            } else {
                // 逐人双向核验比列表中的在场/缺席证据更直接，不能把已核验的 false 与旧 true 合并。
                RelationshipEntry verified = entry.relationshipVerified() ? entry
                        : (prev.relationshipVerified() ? prev : null);
                boolean sameIdentity = prev.user().id() != null && !prev.user().id().isBlank()
                        && prev.user().id().equals(entry.user().id());
                map.put(key, new RelationshipEntry(
                        preferRicherUser(prev.user(), entry.user()),
                        verified != null ? verified.iFollowThem() : prev.iFollowThem() || entry.iFollowThem(),
                        verified != null ? verified.theyFollowMe() : prev.theyFollowMe() || entry.theyFollowMe(),
                        verified != null,
                        sameIdentity ? (prev.followingOrder() != null ? prev.followingOrder() : entry.followingOrder()) : null,
                        sameIdentity ? (prev.followerOrder() != null ? prev.followerOrder() : entry.followerOrder()) : null
                ));
            }
        }
        return map;
    }

    private ActivityOverview buildActivityOverview(
            ReportPeriod period,
            double effectiveCoveredDays,
            boolean sampleCapped,
            List<RawTweet> timeline,
            Instant cutoff,
            Instant refNow,
            ZoneId zoneId,
            String coverageNote,
            XAlgorithmProfile algo
    ) {
        String periodCode = period.code;
        String periodLabel = period.label;
        int windowDays = period.windowDays;
        int posts = 0;
        int replies = 0;
        int quotes = 0;
        int retweets = 0;
        long totalViews = 0;
        long contentTweetViews = 0;
        long contentTweetLikes = 0;
        long contentTweetEngagements = 0;
        int contentTweetCount = 0;
        int viewSampleCount = 0;
        int mainPostViewSampleCount = 0;
        int mainPostLikeSampleCount = 0;
        int engagementRateSampleCount = 0;
        long rateViews = 0L;

        for (RawTweet tweet : timeline) {
            boolean viewKnown = metricKnown(tweet, "views");
            long views = viewKnown ? Math.max(0L, tweet.viewCount()) : 0L;
            if (tweet.kind() != TweetKind.RETWEET && viewKnown) {
                totalViews += views;
                viewSampleCount++;
            }
            boolean main = isIndependentMainPost(tweet);
            if (main) {
                contentTweetCount++;
                if (viewKnown) {
                    contentTweetViews += views;
                    mainPostViewSampleCount++;
                }
                if (metricKnown(tweet, "likes")) {
                    contentTweetLikes += Math.max(0, tweet.likeCount());
                    mainPostLikeSampleCount++;
                }
                if (rateKnown(tweet)) {
                    contentTweetEngagements += Math.min(engagements(tweet), views);
                    rateViews += views;
                    engagementRateSampleCount++;
                }
            }
            switch (tweet.kind()) {
                case POST -> { if (!tweet.selfThreadReply()) posts++; }
                case REPLY -> replies++;
                case QUOTE -> quotes++;
                case RETWEET -> retweets++;
            }
        }

        List<TrendBucketPoint> trendSeries;
        int activeUnits;
        int totalUnits;
        String activeProgressLabel;
        String windowRangeText;
        String chartTitle;
        String chartSubtitle;

        ZonedDateTime zNow = refNow.atZone(zoneId);
        if (!period.dailyBuckets) {
            // 24h / 48h 小时级动态窗口：24h 按 1 小时统计，48h 按 2 小时合并（仅最后一桶含终点）。
            int totalHours = period.windowHours;
            int bucketHours = period == ReportPeriod.HOURS_48 ? 2 : 1;
            int slotCount = totalHours / bucketHours;
            ZonedDateTime zCutoff = cutoff.atZone(zoneId);
            windowRangeText = zCutoff.format(RANGE_HOUR_FMT) + " – " + zNow.format(RANGE_HOUR_FMT);
            chartTitle = period == ReportPeriod.HOURS_48
                    ? "48小时动态窗口逐时段发帖与回复分布"
                    : "24小时动态窗口逐时发帖与回复分布";
            String notePrefix = (coverageNote != null && !coverageNote.isEmpty()) ? coverageNote + " · " : "";
            chartSubtitle = notePrefix + (bucketHours == 2
                    ? "从窗口起点按 2 小时统计 · 最后时段含终点"
                    : "从窗口起点按 1 小时统计 · 最后时段含终点");

            int[][] slotCounts = new int[slotCount][5];
            long[][] slotMetrics = new long[slotCount][4];
            Set<Integer> activeHourSet = new HashSet<>();
            for (RawTweet t : timeline) {
                int rawHour = t.createdAt().equals(refNow) ? totalHours - 1
                        : (int) Duration.between(cutoff, t.createdAt()).toHours();
                int hourIndex = Math.max(0, Math.min(totalHours - 1, rawHour));
                activeHourSet.add(hourIndex);
                int bucketIndex = Math.min(slotCount - 1, hourIndex / bucketHours);
                accumulateTweetBucket(t, slotCounts[bucketIndex], slotMetrics[bucketIndex]);
            }
            DateTimeFormatter compactSlotFmt = DateTimeFormatter.ofPattern("dd日HH:mm");
            trendSeries = new ArrayList<>(slotCount);
            for (int b = 0; b < slotCount; b++) {
                ZonedDateTime slot = zCutoff.plusHours((long) b * bucketHours);
                int[] c = slotCounts[b];
                long[] m = slotMetrics[b];
                String fullLbl = slot.format(RANGE_HOUR_FMT) + " – "
                        + slot.plusHours(bucketHours).format(RANGE_HOUR_FMT);
                trendSeries.add(new TrendBucketPoint(
                        slot.format(compactSlotFmt), fullLbl,
                        c[0], c[1], c[2], c[3], c[4], m[0], (int) Math.min(Integer.MAX_VALUE, m[1]), (int) m[2], (int) m[3]
                ));
            }
            activeUnits = activeHourSet.size();
            totalUnits = totalHours;
            activeProgressLabel = String.format("活跃 %d / %d 小时", activeUnits, totalHours);
        } else {
            // 7天动态窗口 或 30天动态窗口：动态起止精确到分钟，且保证滚动窗口首段动态不漏计
            ZonedDateTime zCutoff = cutoff.atZone(zoneId);
            LocalDate endDate = zNow.toLocalDate();
            LocalDate startDate = endDate.minusDays(windowDays - 1L);
            windowRangeText = zCutoff.format(RANGE_HOUR_FMT) + " – " + zNow.format(RANGE_HOUR_FMT);
            chartTitle = windowDays == 7 ? "7天动态窗口每日发帖 vs 回复趋势" : "30天动态窗口每日发帖 vs 回复趋势";
            chartSubtitle = (coverageNote != null && !coverageNote.isEmpty())
                    ? (sampleCapped ? coverageNote + "，日均指标已按实际覆盖天数计算" : coverageNote)
                    : "点按或悬停柱体查看单日发帖、回复与浏览明细";

            Map<LocalDate, int[]> dayCounts = new LinkedHashMap<>();
            Map<LocalDate, long[]> dayMetrics = new LinkedHashMap<>();
            for (int i = 0; i < windowDays; i++) {
                LocalDate d = startDate.plusDays(i);
                dayCounts.put(d, new int[5]);
                dayMetrics.put(d, new long[4]);
            }
            Set<LocalDate> activeDaySet = new HashSet<>();
            for (RawTweet t : timeline) {
                LocalDate rawDate = t.createdAt().atZone(zoneId).toLocalDate();
                LocalDate bucketDate = rawDate.isBefore(startDate) ? startDate : rawDate;
                int[] c = dayCounts.get(bucketDate);
                long[] m = dayMetrics.get(bucketDate);
                if (c != null) {
                    activeDaySet.add(bucketDate);
                    accumulateTweetBucket(t, c, m);
                }
            }
            boolean hasEarlyTail = zCutoff.toLocalDate().isBefore(startDate);
            trendSeries = new ArrayList<>(windowDays);
            for (Map.Entry<LocalDate, int[]> entry : dayCounts.entrySet()) {
                LocalDate d = entry.getKey();
                int[] c = entry.getValue();
                long[] m = dayMetrics.get(d);
                String shortLbl = windowDays == 7
                        ? DAY_LABELS[d.getDayOfWeek().getValue() - 1] + " " + d.format(SHORT_DATE_FMT)
                        : d.format(SHORT_DATE_FMT);
                String fullLbl = (hasEarlyTail && d.equals(startDate))
                        ? zCutoff.format(RANGE_HOUR_FMT) + " – " + d.format(RANGE_DATE_FMT) + " 23:59"
                        : d.format(RANGE_DATE_FMT) + "（" + DAY_LABELS[d.getDayOfWeek().getValue() - 1] + "）";
                trendSeries.add(new TrendBucketPoint(
                        shortLbl,
                        fullLbl,
                        c[0], c[1], c[2], c[3], c[4], m[0], (int) Math.min(Integer.MAX_VALUE, m[1]), (int) m[2], (int) m[3]
                ));
            }
            activeUnits = activeDaySet.size();
            totalUnits = sampleCapped ? Math.max(activeUnits, (int) Math.ceil(effectiveCoveredDays)) : windowDays;
            activeProgressLabel = sampleCapped
                    ? String.format("采样覆盖近 %d 天（活跃 %d 天）", totalUnits, activeUnits)
                    : String.format("活跃 %d / %d 天", activeUnits, windowDays);
        }

        int totalActions = posts + replies + quotes + retweets;
        double replyToPostRatio = (posts + quotes) > 0
                ? round1((double) replies / (posts + quotes))
                : (replies > 0 ? round1(replies) : 0.0);
        double replySharePct = totalActions > 0 ? round1(replies * 100.0 / totalActions) : 0.0;

        double dailyAvgPosts = effectiveCoveredDays > 0 ? round1((posts + quotes) / effectiveCoveredDays) : 0.0;
        double dailyAvgReplies = effectiveCoveredDays > 0 ? round1(replies / effectiveCoveredDays) : 0.0;

        long avgViewsPerPost = mainPostViewSampleCount > 0 ? Math.round((double) contentTweetViews / mainPostViewSampleCount) : 0L;
        double avgLikesPerPost = mainPostLikeSampleCount > 0 ? round1((double) contentTweetLikes / mainPostLikeSampleCount) : 0.0;
        double avgEngagementRatePct = safeErPct(contentTweetEngagements, rateViews);
        boolean metricsIncomplete = timeline.stream().filter(t -> t.kind() != TweetKind.RETWEET)
                .anyMatch(t -> !t.unavailableMetrics().isEmpty());
        String metricsNote = metricsIncomplete
                ? String.format("部分公开指标缺失，未知值未按0计入；主推均浏览有 %d/%d 条已知样本，公开互动率有 %d/%d 条可用样本，均值与评分仅反映可用样本",
                        mainPostViewSampleCount, contentTweetCount, engagementRateSampleCount, contentTweetCount)
                : (contentTweetCount > 0 && engagementRateSampleCount == 0 ? "主推浏览数为0，暂无可计算公开互动率的样本" : "");

        int healthScore = computeHealthScore(
                periodCode, activeUnits, totalUnits, dailyAvgPosts, dailyAvgReplies, avgEngagementRatePct
        );
        String personaTag = classifyPersona(dailyAvgPosts, dailyAvgReplies, replyToPostRatio, avgEngagementRatePct, totalActions);
        if (contentTweetCount > 0 && engagementRateSampleCount == 0) healthScore = -1;
        String advice = buildActivityAdvice(periodCode, posts, quotes, replies, replyToPostRatio, dailyAvgReplies, avgEngagementRatePct, algo);
        if (engagementRateSampleCount == 0) advice = advice.replace(String.format("平均互动率 %.2f%%", avgEngagementRatePct), "平均互动率暂无可用样本");
        if (!metricsNote.isEmpty()) advice += " " + metricsNote;
        if (sampleCapped) {
            personaTag = totalActions == 0 ? "样本不足 · 暂无活动记录" : "样本观察 · " + personaTag;
            advice = coverageNote + "；以下仅按已采集样本观察，日均为按覆盖时长折算，不代表完整周期。"
                    + advice.replace("所选周期内", "已采集样本中").replace("本周期", "已采集样本");
        }

        return new ActivityOverview(
                periodCode,
                periodLabel,
                windowRangeText,
                windowDays,
                round1(effectiveCoveredDays),
                sampleCapped,
                posts,
                replies,
                quotes,
                retweets,
                totalActions,
                replyToPostRatio,
                replySharePct,
                dailyAvgPosts,
                dailyAvgReplies,
                totalViews,
                avgViewsPerPost,
                avgLikesPerPost,
                avgEngagementRatePct,
                activeUnits,
                totalUnits,
                activeProgressLabel,
                healthScore,
                personaTag,
                advice,
                chartTitle,
                chartSubtitle,
                trendSeries,
                coverageNote,
                viewSampleCount,
                mainPostViewSampleCount,
                mainPostLikeSampleCount,
                engagementRateSampleCount,
                metricsNote
        );
    }

    private void accumulateTweetBucket(RawTweet tweet, int[] counts, long[] metrics) {
        switch (tweet.kind()) {
            case POST -> {
                if (!tweet.selfThreadReply()) {
                    counts[0]++;
                } else {
                    counts[4]++;
                }
            }
            case REPLY -> counts[1]++;
            case QUOTE -> counts[2]++;
            case RETWEET -> counts[3]++;
        }
        if (tweet.kind() != TweetKind.RETWEET) {
            if (metricKnown(tweet, "views")) {
                metrics[0] += Math.max(0L, tweet.viewCount());
                metrics[2]++;
            }
            if (engagementKnown(tweet)) {
                metrics[1] += engagements(tweet);
                metrics[3]++;
            }
        }
    }

    private int computeHealthScore(
            String periodCode,
            int activeUnits,
            int totalUnits,
            double dailyAvgPosts,
            double dailyAvgReplies,
            double erPct
    ) {
        if (dailyAvgPosts == 0 && dailyAvgReplies == 0) {
            return 0;
        }
        double consistencyScore;
        if ("HOURS_24".equals(periodCode)) {
            consistencyScore = Math.min(30.0, (activeUnits * 30.0) / 6.0);
        } else if ("HOURS_48".equals(periodCode)) {
            // 48 小时内活跃满 10 个小时以上即视为高连续性
            consistencyScore = Math.min(30.0, (activeUnits * 30.0) / 10.0);
        } else {
            consistencyScore = Math.min(30.0, (activeUnits * 30.0) / Math.max(1, totalUnits));
        }
        double outputScore = Math.min(25.0, (dailyAvgPosts / 1.5) * 25.0);
        double yapScore = Math.min(25.0, (dailyAvgReplies / 10.0) * 25.0);
        double erScore = Math.min(20.0, erPct * 3.2);
        return (int) Math.round(Math.min(99.0, consistencyScore + outputScore + yapScore + erScore));
    }

    private String classifyPersona(
            double dailyAvgPosts,
            double dailyAvgReplies,
            double replyRatio,
            double erPct,
            int totalActions
    ) {
        if (totalActions == 0) {
            return "本周期静默休整";
        }
        if (dailyAvgReplies >= 15 && replyRatio >= 3.0) {
            return "高频互动深耕达人";
        }
        if (dailyAvgPosts >= 1.0 && dailyAvgReplies >= 5.0 && erPct >= 3.0) {
            return "高粘性双向社区主理人";
        }
        if (dailyAvgPosts >= 1.0 && replyRatio < 0.8) {
            return "原创主导型内容创作者";
        }
        if (dailyAvgReplies > 0 && dailyAvgPosts < 0.3) {
            return "评论区暖场互动派";
        }
        return "均衡成长型社区推友";
    }

    private String buildActivityAdvice(
            String periodCode,
            int posts,
            int quotes,
            int replies,
            double ratio,
            double dailyReplies,
            double erPct,
            XAlgorithmProfile algo
    ) {
        int authored = posts + quotes;
        if (authored + replies == 0) {
            return "所选周期内暂无发帖或回复记录；可按自己的节奏尝试原创内容与同频交流，再观察公开互动变化。";
        }
        double targetMin = algo.opsPlaybook().targetReplyToPostRatioMin();
        double overRatio = algo.opsPlaybook().overReplyLowPostRatio();
        if (ratio < 1.0) {
            return String.format("当前「回复/发帖比」为 %.1fx（原创及引用 %d 条、回复 %d 条），偏向单向内容发布；可尝试参与核心推友讨论，再观察回访评论是否变化。", ratio, authored, replies);
        }
        if (ratio < targetMin) {
            return String.format("当前「回复/发帖比」为 %.1fx（原创及引用 %d 条、回复 %d 条），略低于 %.1fx 推荐互动比；可补充同频评论与主帖接话。", ratio, authored, replies, targetMin);
        }
        if (ratio > overRatio && authored <= 2) {
            return String.format("评论区非常勤奋（已回复 %d 条，回复比 %.1fx），公开样本以回复为主；可尝试补充原创观点，实际回访和转粉效果仍需观察。", replies, ratio);
        }
        return String.format("本周期产出原创/引用 %d 条、互动回复 %d 条（回复比 %.1fx，日均互动 %.1f 条，平均互动率 %.2f%%），可持续观察内容输出与社区互动变化。",
                authored, replies, ratio, dailyReplies, erPct);
    }

    private static final class MutablePeerAcc {
        XUserSummary user;
        // 严格按「独立根帖」去重：1 个帖子算 1 次，同一帖内多次盖楼合并为 1 次
        final Set<String> myVisitedPeerPostIds = new LinkedHashSet<>();
        final Set<String> peerVisitedMyPostIds = new LinkedHashSet<>();
        // 辅助精准度追踪：原始评论条数、对方在自己帖内接话数、我在自己帖内礼貌回复数、我漏回对方评论的帖子
        int myRawCommentCount;
        int peerRawCommentCount;
        final Set<String> peerInThreadRepliesOnTheirPostIds = new LinkedHashSet<>();
        final Set<String> myRepliedInMyOwnPostIds = new LinkedHashSet<>();
        final Set<String> unrepliedMyPostIds = new LinkedHashSet<>();
        String latestUnrepliedTweetUrl = "";
        String unrepliedSnippet = "";

        final Set<String> myQuotePostIds = new LinkedHashSet<>();
        final Set<String> peerQuotePostIds = new LinkedHashSet<>();
        int myMentionsOfPeer;
        int peerMentionsOfMe;
        String recentSnippet = "";

        MutablePeerAcc(XUserSummary user) {
            this.user = user;
        }

        void mergeUser(XUserSummary candidate) {
            this.user = preferRicherUser(this.user, candidate);
        }

        void noteSnippet(String text) {
            if ((recentSnippet == null || recentSnippet.isBlank()) && text != null && !text.isBlank()) {
                this.recentSnippet = clipSnippet(text);
            }
        }

        private static String clipSnippet(String text) {
            String singleLine = text.replaceAll("\\s+", " ").trim();
            int cpCount = singleLine.codePointCount(0, singleLine.length());
            if (cpCount > 95) {
                int endIdx = singleLine.offsetByCodePoints(0, 95);
                return singleLine.substring(0, endIdx) + "…";
            }
            return singleLine;
        }
    }

    private static String normId(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String normKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 核心推友互动引擎（严格遵循「1个帖子算1次，同帖回复不重复计次」规则）：
     * 1. 我去他那边推文评论（myRepliesToPeer）：仅统计我评论过的「对方独立主帖数」（按根帖 conversationId 去重）；
     *    - 我在对方同一个帖子里追评/盖楼多条，仅算 1 次；
     *    - 我在「自己的帖子」底下礼貌回复对方的评论，绝不计入「我去评他帖」！
     * 2. 他来我这边推文评论（peerRepliesToMe）：仅统计对方评论过的「我方独立主帖数」（按我的根帖 conversationId 去重）；
     *    - 对方在我同一个帖子里评论/追评多条，仅算 1 次；
     *    - 对方在「他自己的帖子」底下回复我的评论，绝不计入「他来评我帖」（单独记入 peerInThreadRepliesOnTheirPost）！
     * 3. 精准识别「我没回谁（含我帖内漏回评论 + 我未回访他主帖）」「谁没回我」与全息关系定性。
     */
    private InteractionOverview buildInteractionOverview(
            String selfKey,
            List<RawTweet> allTimeline,
            List<InboundInteraction> allInbound,
            List<RawTweet> windowTimeline,
            List<InboundInteraction> windowInbound,
            Map<String, RelationshipEntry> relByKey,
            boolean followersTruncated,
            boolean followingsTruncated,
            XAlgorithmProfile algo
    ) {
        // 第一步：基于全量 30 天快照构建「我方主帖归属索引」与「外部主帖帖主索引」，确保跨时间窗评论也能 100% 识别帖主
        Set<String> myOwnedRootPostIds = new HashSet<>();
        Set<String> myOutboundCommentTweetIds = new HashSet<>();
        for (RawTweet t : allTimeline) {
            if (t == null) continue;
            String tid = normId(t.id());
            String cid = normId(t.conversationId());
            String rid = normId(t.inReplyToId());
            if ((t.kind() == TweetKind.POST && !t.selfThreadReply()) || t.kind() == TweetKind.QUOTE) {
                if (!tid.isEmpty()) myOwnedRootPostIds.add(tid);
                if (!cid.isEmpty()) myOwnedRootPostIds.add(cid);
            } else if (t.selfThreadReply() && !cid.isEmpty() && cid.equals(rid)) {
                myOwnedRootPostIds.add(cid);
            }
        }
        for (InboundInteraction in : allInbound) {
            if (in == null) continue;
            String cid = normId(in.conversationId());
            String rid = normId(in.inReplyToId());
            String rUser = normKey(in.inReplyToUsername());
            // 对方直接评论根帖（inReplyToId == conversationId）且被回复人是本人 -> 该根帖 100% 属于我方主帖（即使发于 30 天前）
            if (!cid.isEmpty() && cid.equals(rid) && rUser.equals(selfKey)) {
                myOwnedRootPostIds.add(cid);
            }
        }

        Map<String, String> externalRootOwnerByConvId = new HashMap<>();
        Map<String, Set<String>> myRepliedPeersInMyPost = new HashMap<>();
        Set<String> myDirectlyRepliedTweetIds = new HashSet<>();
        Set<String> peerCommentIdsOnMyPosts = new HashSet<>();

        for (InboundInteraction in : allInbound) {
            if (in == null || in.kind() != InboundKind.REPLY_TO_ME) continue;
            String cid = normId(in.conversationId());
            String tid = normId(in.tweetId());
            if (!cid.isEmpty() && myOwnedRootPostIds.contains(cid) && !tid.isEmpty()) {
                peerCommentIdsOnMyPosts.add(tid);
            }
        }

        for (RawTweet t : allTimeline) {
            if (t == null || t.kind() != TweetKind.REPLY || t.selfThreadReply()) continue;
            String tid = normId(t.id());
            String cid = normId(t.conversationId());
            String rid = normId(t.inReplyToId());
            String targetKey = normKey(t.targetUsername());
            if (!rid.isEmpty()) {
                myDirectlyRepliedTweetIds.add(rid);
            }
            boolean inMyOwnPost = (!cid.isEmpty() && myOwnedRootPostIds.contains(cid))
                    || (!rid.isEmpty() && peerCommentIdsOnMyPosts.contains(rid));
            if (inMyOwnPost) {
                if (!cid.isEmpty() && !targetKey.isEmpty() && !targetKey.equals(selfKey)) {
                    myRepliedPeersInMyPost.computeIfAbsent(cid, k -> new HashSet<>()).add(targetKey);
                }
            } else {
                if (!tid.isEmpty()) {
                    myOutboundCommentTweetIds.add(tid);
                }
                if (!cid.isEmpty() && cid.equals(rid) && !targetKey.isEmpty() && !targetKey.equals(selfKey)) {
                    externalRootOwnerByConvId.put(cid, targetKey);
                }
            }
        }

        // 第二步：在所选动态时间窗内，严格按「1个帖子算1次」统计双边串门评论
        Map<String, MutablePeerAcc> accMap = new HashMap<>();
        int filteredSamePostReplies = 0;

        for (RawTweet tweet : windowTimeline) {
            String primaryTargetKey = null;
            if (tweet.targetUsername() != null && !tweet.targetUsername().isBlank()) {
                String candidateKey = normKey(tweet.targetUsername());
                if (!candidateKey.equals(selfKey)) {
                    primaryTargetKey = candidateKey;
                    if (tweet.kind() == TweetKind.REPLY && !tweet.selfThreadReply()) {
                        String cid = normId(tweet.conversationId());
                        String rid = normId(tweet.inReplyToId());
                        boolean inMyOwnPost = (!cid.isEmpty() && myOwnedRootPostIds.contains(cid))
                                || (!rid.isEmpty() && peerCommentIdsOnMyPosts.contains(rid));

                        if (inMyOwnPost) {
                            // 我在自己帖子下回复对方评论：属于礼貌回评，绝不计入「我去他那边推文评论」次数
                            MutablePeerAcc acc = getOrCreatePeer(accMap, candidateKey, tweet.targetUser(), relByKey);
                            acc.myRepliedInMyOwnPostIds.add(!cid.isEmpty() ? cid : ("own_" + normId(tweet.id())));
                        } else if (!cid.isEmpty()) {
                            String knownOwner = externalRootOwnerByConvId.get(cid);
                            // 根帖作者未知或属于第三方时，不把父评论作者认作根帖作者。
                            if (candidateKey.equals(knownOwner)) {
                                MutablePeerAcc acc = getOrCreatePeer(accMap, candidateKey, tweet.targetUser(), relByKey);
                                acc.myRawCommentCount++;
                                if (!acc.myVisitedPeerPostIds.add(cid)) {
                                    // 同一个对方帖子下我多次追评/盖楼：只算 1 次，其余合并过滤
                                    filteredSamePostReplies++;
                                }
                                acc.noteSnippet("我去评 TA 帖：" + tweet.text());
                            }
                        }
                    } else if (tweet.kind() == TweetKind.QUOTE) {
                        MutablePeerAcc acc = getOrCreatePeer(accMap, candidateKey, tweet.targetUser(), relByKey);
                        acc.myQuotePostIds.add(normId(tweet.id()).isEmpty() ? ("q_" + acc.myQuotePostIds.size()) : normId(tweet.id()));
                        acc.noteSnippet("我引用 TA：" + tweet.text());
                    }
                }
            }

            if (tweet.mentionedUsernames() != null && tweet.kind() != TweetKind.RETWEET) {
                Set<String> seenInTweet = new HashSet<>();
                for (String mentioned : tweet.mentionedUsernames()) {
                    String mKey = normKey(mentioned);
                    if (mKey.isEmpty() || mKey.equals(selfKey) || mKey.equals(primaryTargetKey) || !seenInTweet.add(mKey)) {
                        continue;
                    }
                    // 提及独立建账；仅提及对象仍由下方评论/引用主榜规则排除。
                    getOrCreatePeer(accMap, mKey, null, relByKey).myMentionsOfPeer++;
                }
            }
        }

        for (InboundInteraction inbound : windowInbound) {
            if (inbound.peer() == null || inbound.peer().username() == null) {
                continue;
            }
            String pKey = normKey(inbound.peer().username());
            if (pKey.isEmpty() || pKey.equals(selfKey)) {
                continue;
            }
            switch (inbound.kind()) {
                case REPLY_TO_ME -> {
                    String cid = normId(inbound.conversationId());
                    String rid = normId(inbound.inReplyToId());
                    String tid = normId(inbound.tweetId());

                    boolean replyToMyOutboundComment = !rid.isEmpty() && myOutboundCommentTweetIds.contains(rid);
                    if (!cid.isEmpty()) {
                        if (myOwnedRootPostIds.contains(cid) && !replyToMyOutboundComment) {
                            MutablePeerAcc acc = getOrCreatePeer(accMap, pKey, inbound.peer(), relByKey);
                            acc.peerRawCommentCount++;
                            if (!acc.peerVisitedMyPostIds.add(cid)) {
                                // 对方在我同一个帖子下多次评论/追评：只算 1 次
                                filteredSamePostReplies++;
                            }
                            acc.noteSnippet("TA 来评我帖：" + inbound.snippet());

                            boolean iRepliedInPost = myRepliedPeersInMyPost.getOrDefault(cid, Set.of()).contains(pKey)
                                    || (!tid.isEmpty() && myDirectlyRepliedTweetIds.contains(tid));
                            if (!iRepliedInPost) {
                                acc.unrepliedMyPostIds.add(cid);
                                if (acc.latestUnrepliedTweetUrl.isEmpty() && !tid.isEmpty()) {
                                    acc.latestUnrepliedTweetUrl = "https://x.com/" + inbound.peer().username() + "/status/" + tid;
                                    acc.unrepliedSnippet = MutablePeerAcc.clipSnippet("🚨 待回评 TA 评论：" + inbound.snippet());
                                }
                            }
                        } else {
                            // 对方在他自己的帖子（或外部帖子）下回复我的评论：不算「他来我推文评论」
                            MutablePeerAcc acc = getOrCreatePeer(accMap, pKey, inbound.peer(), relByKey);
                            acc.peerInThreadRepliesOnTheirPostIds.add(cid);
                        }
                    } else if (replyToMyOutboundComment) {
                        MutablePeerAcc acc = getOrCreatePeer(accMap, pKey, inbound.peer(), relByKey);
                        acc.peerInThreadRepliesOnTheirPostIds.add(rid);
                    }
                }
                case QUOTE_ME -> {
                    MutablePeerAcc acc = getOrCreatePeer(accMap, pKey, inbound.peer(), relByKey);
                    String qKey = normId(inbound.tweetId()).isEmpty() ? ("iq_" + acc.peerQuotePostIds.size()) : normId(inbound.tweetId());
                    acc.peerQuotePostIds.add(qKey);
                    acc.noteSnippet("TA 引用我帖：" + inbound.snippet());
                }
                case MENTION_ME -> {
                    getOrCreatePeer(accMap, pKey, inbound.peer(), relByKey).peerMentionsOfMe++;
                }
            }
        }

        int totalOutbound = 0;
        int totalInbound = 0;
        for (MutablePeerAcc acc : accMap.values()) {
            totalOutbound += acc.myVisitedPeerPostIds.size();
            totalInbound += acc.peerVisitedMyPostIds.size();
        }
        int grandTotalInteractions = Math.max(1, totalOutbound + totalInbound);

        int balancedCount = 0;
        int outboundHeavyCount = 0;
        int inboundHeavyCount = 0;
        int unrepliedMyPostPeers = 0;
        List<PeerInteractionStat> peerStats = new ArrayList<>(accMap.size());

        for (Map.Entry<String, MutablePeerAcc> entry : accMap.entrySet()) {
            String pKey = entry.getKey();
            MutablePeerAcc acc = entry.getValue();

            int myPostVisits = acc.myVisitedPeerPostIds.size();
            int peerPostVisits = acc.peerVisitedMyPostIds.size();
            int myQuotes = acc.myQuotePostIds.size();
            int peerQuotes = acc.peerQuotePostIds.size();

            // 主账本严格以「我去评他帖数 vs 他来评我帖数」为核心，1 帖只计 1 次；纯引用/提及不进入评论关系列表
            boolean hasPostComments = (myPostVisits + peerPostVisits) > 0;
            if (!hasPostComments) {
                continue;
            }

            int outbound = myPostVisits;
            int inbound = peerPostVisits;
            int total = outbound + inbound;
            if (total == 0) {
                continue;
            }

            int unrepliedPosts = acc.unrepliedMyPostIds.size();
            int peerInThread = acc.peerInThreadRepliesOnTheirPostIds.size();
            int myInOwnPost = acc.myRepliedInMyOwnPostIds.size();

            RelationshipEntry rel = relByKey.get(pKey);
            boolean iFollow = rel != null && rel.iFollowThem();
            boolean theyFollow = rel != null && rel.theyFollowMe();
            boolean mutual = iFollow && theyFollow;

            double outboundSharePct = round1(outbound * 100.0 / total);
            String replyRatioLabel = myPostVisits + " : " + peerPostVisits;
            String quoteRatioLabel = myQuotes + " : " + peerQuotes;
            String mentionRatioLabel = acc.myMentionsOfPeer + " : " + acc.peerMentionsOfMe;

            double returnRatePct;
            String returnRateLabel;
            if (myPostVisits > 0) {
                returnRatePct = round1(peerPostVisits * 100.0 / myPostVisits);
                returnRateLabel = Math.round(returnRatePct) + "%";
            } else if (peerPostVisits > 0) {
                returnRatePct = 999.0;
                returnRateLabel = "TA来评 " + peerPostVisits + " 帖";
            } else {
                returnRatePct = myQuotes > 0 ? round1(peerQuotes * 100.0 / myQuotes) : 100.0;
                returnRateLabel = "仅引用 " + quoteRatioLabel;
            }

            double interactionSharePct = hasPostComments
                    ? round1((myPostVisits + peerPostVisits) * 100.0 / grandTotalInteractions)
                    : 0.0;

            if (unrepliedPosts > 0) {
                unrepliedMyPostPeers++;
            }

            // 严格遵循四维清晰定义（> 5 帖差额不再算互相评论）：
            // 1. 我没回谁 (I_OWE)：他来评但我未去评，或他来评比我去评多出 > 5 帖
            // 2. 评论待回复 (UNREPLIED_POST)：别人评论了我，但我还没有回复他 (unrepliedPosts > 0)
            // 3. 谁没回我 (THEY_OWE)：我去评但他未来评，或我去评比他来评多出 > 5 帖
            // 4. 互相评论 (MUTUAL)：双方均有主帖互评且双向帖数差值 <= 5 条
            String reciprocityCode;
            String relationType;
            if (peerPostVisits > 0 && myPostVisits == 0) {
                reciprocityCode = "I_OWE";
                relationType = unrepliedPosts > 0 ? "🙋‍♂️ 没去评TA·评论待回" : "🙋‍♂️ 我没去评 TA 帖";
                inboundHeavyCount++;
            } else if (myPostVisits > 0 && peerPostVisits == 0) {
                reciprocityCode = "THEY_OWE";
                relationType = "👀 TA 没来评我帖";
                outboundHeavyCount++;
            } else {
                int diff = Math.abs(myPostVisits - peerPostVisits);
                if (diff <= 5) {
                    reciprocityCode = "MUTUAL";
                    relationType = unrepliedPosts > 0 ? "🤝 互相评论·评论待回" : "🤝 互相评论";
                    balancedCount++;
                } else if (peerPostVisits - myPostVisits > 5) {
                    reciprocityCode = "I_OWE";
                    relationType = unrepliedPosts > 0 ? "🙋‍♂️ 差额>5帖·评论待回" : "🙋‍♂️ TA评我多>5帖";
                    inboundHeavyCount++;
                } else {
                    reciprocityCode = "THEY_OWE";
                    relationType = "👀 我评TA多>5帖";
                    outboundHeavyCount++;
                }
            }

            boolean relationshipVerified = rel != null && rel.relationshipVerified();
            boolean followStateKnown = relationshipVerified || ((iFollow || !followingsTruncated)
                    && (theyFollow || !followersTruncated));
            String followPrefix = !followStateKnown ? "关注关系待核实" : mutual
                    ? "互关好友"
                    : (iFollow
                            ? (acc.user.followersCount() >= 25_000 ? "单向关注大V" : "我单向关注(未回关)")
                            : (theyFollow ? "关注我的粉丝(未回关)" : "未互关推友"));

            String relationshipPortrait = buildRelationshipPortrait(
                    followPrefix, theyFollow, relationshipVerified || !followersTruncated, acc.user.followersCount(),
                    myPostVisits, peerPostVisits, unrepliedPosts, peerInThread, myInOwnPost,
                    hasPostComments, reciprocityCode, quoteRatioLabel
            );

            String displaySnippet = !acc.unrepliedSnippet.isBlank() ? acc.unrepliedSnippet : acc.recentSnippet;

            XAlgorithmProfile.OpsPlaybookRules playbook = algo.opsPlaybook();
            double intimacyScore = round1(
                    myPostVisits * playbook.intimacyOutReplyWeight()
                            + peerPostVisits * playbook.intimacyInReplyWeight()
                            + (Math.min(myPostVisits, peerPostVisits) * playbook.intimacyMutualPairWeight())
                            + (myQuotes + peerQuotes) * playbook.intimacyQuoteWeight()
                            + (mutual ? playbook.intimacyMutualFollowBonus() : 0.0)
                            + (unrepliedPosts > 0 ? playbook.intimacyUnrepliedBonus() : 0.0)
            );

            peerStats.add(new PeerInteractionStat(
                    acc.user,
                    iFollow,
                    theyFollow,
                    myPostVisits,
                    peerPostVisits,
                    acc.myRawCommentCount,
                    acc.peerRawCommentCount,
                    peerInThread,
                    myInOwnPost,
                    unrepliedPosts,
                    acc.latestUnrepliedTweetUrl,
                    myQuotes,
                    peerQuotes,
                    acc.myMentionsOfPeer,
                    acc.peerMentionsOfMe,
                    outbound,
                    inbound,
                    total,
                    replyRatioLabel,
                    quoteRatioLabel,
                    mentionRatioLabel,
                    outboundSharePct,
                    returnRatePct,
                    returnRateLabel,
                    interactionSharePct,
                    intimacyScore,
                    reciprocityCode,
                    relationType,
                    relationshipPortrait,
                    displaySnippet,
                    followStateKnown
            ));
        }

        // 排序规则：蓝V标识排在前面，其次按双方评论总数（我去评 + TA来评）倒序
        peerStats.sort(Comparator
                .comparing((PeerInteractionStat p) -> p.peer() != null && p.peer().verified()).reversed()
                .thenComparing(Comparator.comparingInt((PeerInteractionStat p) -> p.myRepliesToPeer() + p.peerRepliesToMe()).reversed())
                .thenComparing(Comparator.comparingInt(PeerInteractionStat::peerRepliesToMe).reversed()));

        double overallReturnRate = totalOutbound > 0
                ? round1(totalInbound * 100.0 / totalOutbound)
                : (totalInbound > 0 ? 100.0 : 0.0);

        return new InteractionOverview(
                peerStats.size(),
                totalOutbound,
                totalInbound,
                balancedCount,
                outboundHeavyCount,
                inboundHeavyCount,
                unrepliedMyPostPeers,
                filteredSamePostReplies,
                overallReturnRate,
                peerStats
        );
    }

    private static String buildRelationshipPortrait(
            String followPrefix,
            boolean theyFollow,
            boolean followerAbsenceKnown,
            int followersCount,
            int myPostVisits,
            int peerPostVisits,
            int unrepliedPosts,
            int peerInThread,
            int myInOwnPost,
            boolean hasPostComments,
            String reciprocityCode,
            String quoteRatioLabel
    ) {
        if (!hasPostComments) {
            return String.format("%s · 本周期仅引用推文互动（引 %s），暂无主帖评论串门", followPrefix, quoteRatioLabel);
        }
        if (peerPostVisits > 0 && myPostVisits == 0) {
            if (unrepliedPosts > 0) {
                return String.format("%s · TA 来评我 %d 帖（另有 %d 帖评论待回复），尚未去评 TA 主帖",
                        followPrefix, peerPostVisits, unrepliedPosts);
            }
            return String.format("%s · TA 来评我 %d 帖%s，尚未去评 TA 发布过的推文",
                    followPrefix, peerPostVisits,
                    myInOwnPost > 0 ? "（已在自己帖内回复 TA）" : "");
        }
        if (myPostVisits > 0 && peerPostVisits == 0) {
            if (peerInThread > 0) {
                return String.format("%s · 我去评 TA %d 帖，TA 没来评我帖（仅在 TA 帖内礼貌接话 %d 帖）",
                        followPrefix, myPostVisits, peerInThread);
            }
            return String.format("%s · 我去评 TA %d 帖，TA 没来我的推文下评论过%s",
                    followPrefix, myPostVisits,
                    (!theyFollow && followerAbsenceKnown && followersCount < 25_000) ? "（建议减少单向消耗）" : "");
        }
        if ("I_OWE".equals(reciprocityCode) && myPostVisits > 0) {
            return String.format("%s · TA 来评我 %d 帖 vs 我去评 TA %d 帖（差 %d 帖 > 5，不再算作互相评论）%s",
                    followPrefix, peerPostVisits, myPostVisits, peerPostVisits - myPostVisits,
                    unrepliedPosts > 0 ? "；另有 " + unrepliedPosts + " 帖评论待回复" : "");
        }
        if ("THEY_OWE".equals(reciprocityCode) && peerPostVisits > 0) {
            return String.format("%s · 我去评 TA %d 帖 vs TA 来评我 %d 帖（差 %d 帖 > 5，不再算作互相评论）",
                    followPrefix, myPostVisits, peerPostVisits, myPostVisits - peerPostVisits);
        }
        if (unrepliedPosts > 0) {
            return String.format("%s · 互相评论（已去评 TA %d 帖 : TA 来评 %d 帖），我有 %d 帖尚未在评论区回复 TA",
                    followPrefix, myPostVisits, peerPostVisits, unrepliedPosts);
        }
        return String.format("%s · 互相评论（我去评 %d 帖 : TA 来评 %d 帖）",
                followPrefix, myPostVisits, peerPostVisits);
    }

    private MutablePeerAcc getOrCreatePeer(
            Map<String, MutablePeerAcc> accMap,
            String key,
            XUserSummary candidate,
            Map<String, RelationshipEntry> relByKey
    ) {
        MutablePeerAcc existing = accMap.get(key);
        XUserSummary relUser = relByKey.containsKey(key) ? relByKey.get(key).user() : null;
        XUserSummary best = preferRicherUser(relUser, candidate);
        if (best == null) {
            best = new XUserSummary(key, key, key, "", "", false, 0, 0, 0, "");
        }
        if (existing == null) {
            MutablePeerAcc created = new MutablePeerAcc(best);
            accMap.put(key, created);
            return created;
        }
        existing.mergeUser(best);
        return existing;
    }

    // 关系体检保留所有已观察到的交流证据；缺根帖归属的回复不能变成“零互动”。
    private Set<String> observedInteractionPeers(List<RawTweet> timeline, List<InboundInteraction> inbound, String selfKey) {
        Set<String> peers = new HashSet<>();
        for (RawTweet tweet : timeline) {
            if (tweet.kind() == TweetKind.RETWEET) continue;
            if (tweet.kind() == TweetKind.REPLY || tweet.kind() == TweetKind.QUOTE) {
                peers.add(normKey(tweet.targetUsername()));
            }
            if (tweet.mentionedUsernames() != null) {
                for (String username : tweet.mentionedUsernames()) peers.add(normKey(username));
            }
        }
        for (InboundInteraction interaction : inbound) {
            if (interaction.peer() != null) peers.add(normKey(interaction.peer().username()));
        }
        peers.remove("");
        peers.remove(selfKey);
        return peers;
    }

    private Map<String, Integer> quoteCountsByPeer(
            List<RawTweet> timeline,
            List<InboundInteraction> inbound,
            String selfKey
    ) {
        Map<String, Set<String>> outQuotes = new HashMap<>();
        Map<String, Set<String>> inQuotes = new HashMap<>();
        for (RawTweet tweet : timeline) {
            if (tweet == null || tweet.kind() != TweetKind.QUOTE || tweet.targetUsername() == null) continue;
            String key = normKey(tweet.targetUsername());
            if (key.isEmpty() || key.equals(selfKey)) continue;
            Set<String> ids = outQuotes.computeIfAbsent(key, k -> new HashSet<>());
            ids.add(normId(tweet.id()).isEmpty() ? ("q_" + ids.size()) : normId(tweet.id()));
        }
        for (InboundInteraction interaction : inbound) {
            if (interaction == null || interaction.kind() != InboundKind.QUOTE_ME || interaction.peer() == null) continue;
            String key = normKey(interaction.peer().username());
            if (key.isEmpty() || key.equals(selfKey)) continue;
            Set<String> ids = inQuotes.computeIfAbsent(key, k -> new HashSet<>());
            ids.add(normId(interaction.tweetId()).isEmpty() ? ("iq_" + ids.size()) : normId(interaction.tweetId()));
        }
        Map<String, Integer> totals = new HashMap<>();
        Set<String> allKeys = new HashSet<>(outQuotes.keySet());
        allKeys.addAll(inQuotes.keySet());
        for (String key : allKeys) {
            int count = outQuotes.getOrDefault(key, Set.of()).size() + inQuotes.getOrDefault(key, Set.of()).size();
            if (count > 0) totals.put(key, count);
        }
        return totals;
    }

    private FollowAuditOverview buildFollowAuditOverview(
            Map<String, RelationshipEntry> relByKey,
            List<PeerInteractionStat> peerStats,
            String periodLabel,
            boolean followersTruncated,
            boolean followingsTruncated,
            boolean activityIncomplete,
            Set<String> observedInteractionPeers,
            Map<String, Integer> quoteCountsByPeer
    ) {
        Map<String, PeerInteractionStat> statByKey = new HashMap<>();
        for (PeerInteractionStat stat : peerStats) {
            statByKey.put(stat.peer().username().toLowerCase(Locale.ROOT), stat);
        }

        int followingCount = 0;
        int followersCount = 0;
        List<FollowAuditItem> notFollowingBack = new ArrayList<>();
        List<FollowAuditItem> fansNotFollowedBack = new ArrayList<>();
        List<FollowAuditItem> mutuals = new ArrayList<>();
        int suggestedUnfollowCount = 0;
        int bigAccountCount = 0;
        int unknownFollowBackCount = 0;
        int unknownFollowingCount = 0;

        for (Map.Entry<String, RelationshipEntry> entry : relByKey.entrySet()) {
            RelationshipEntry rel = entry.getValue();
            if (rel.iFollowThem()) followingCount++;
            if (rel.theyFollowMe()) followersCount++;
        }

        for (Map.Entry<String, RelationshipEntry> entry : relByKey.entrySet()) {
            String key = entry.getKey();
            RelationshipEntry rel = entry.getValue();

            PeerInteractionStat stat = statByKey.get(key);
            double intimacyScore = stat != null ? stat.intimacyScore() : 0.0;
            // A relationship check can change its category, but cannot invent membership in an ordered source list.
            Integer relationshipOrder = rel.iFollowThem() ? rel.followingOrder() : rel.followerOrder();
            int myOut = stat != null ? stat.myRepliesToPeer() : 0;
            int theirIn = stat != null ? stat.peerRepliesToMe() : 0;
            int quotes = stat != null
                    ? (stat.myQuotesOfPeer() + stat.peerQuotesOfMe())
                    : quoteCountsByPeer.getOrDefault(key, 0);
            int totalInter = myOut + theirIn + quotes;
            XUserSummary u = rel.user();
            String url = "https://x.com/" + u.username();

            if (rel.iFollowThem() && !rel.theyFollowMe()) {
                if (followersTruncated && !rel.relationshipVerified()) {
                    unknownFollowBackCount++;
                    continue;
                }
                String category;
                String label;
                String rec;
                if (u.followersCount() >= 25_000) {
                    category = "BIG_ACCOUNT";
                    label = "高粉大V/资讯号";
                    rec = "粉丝量 ≥ 2.5万，单向关注看内容属常态，已归入防误取关名单";
                    bigAccountCount++;
                } else if (activityIncomplete) {
                    category = "ACTIVITY_INCOMPLETE";
                    label = "互动覆盖待补齐";
                    rec = "互动数据覆盖未完整；当前未观察到的回访不代表整个周期没有互动，补齐后再决定是否调整关注";
                } else if (quotes > 0) {
                    category = "HAS_QUOTE_INTERACTION";
                    label = "引用互动·未回关";
                    rec = String.format("%s内有 %d 次引用互动（我去评 %d 帖 : TA 来评 %d 帖）；引用与主帖互评分别统计，可结合内容偏好保留关注", periodLabel, quotes, myOut, theirIn);
                } else if (myOut + theirIn == 0 && observedInteractionPeers.contains(key)) {
                    category = "OTHER_INTERACTION";
                    label = "其他互动·未回关";
                    rec = "已观察到回复、提及或帖内接话；暂无可确认的主帖互评证据，不能据此判为零互动，可结合对话与内容偏好决定是否保留关注";
                } else if (myOut >= 1 && theirIn == 0) {
                    category = "ONE_WAY_EFFORT";
                    label = "我去评帖仍未回";
                    rec = String.format("%s内你去评 TA %d 帖但对方零回评我帖且未回关，建议停止消耗或取关", periodLabel, myOut);
                    suggestedUnfollowCount++;
                } else if (totalInter == 0) {
                    category = "ZERO_INTERACTION";
                    label = "零互动未回关";
                    rec = periodLabel + "内双方无任何主帖互评且对方未回关，可优先清理优化关注比";
                    suggestedUnfollowCount++;
                } else {
                    category = "HAS_INTERACTION";
                    label = "来评我帖·未回关";
                    rec = String.format("%s内对方来评了你 %d 帖但尚未点关注，属于可转化的准互关推友，别误删", periodLabel, theirIn);
                }
                notFollowingBack.add(new FollowAuditItem(
                        u, "NOT_FOLLOWING_BACK", true, false,
                        myOut, theirIn, totalInter, category, label, rec, url, relationshipOrder, intimacyScore
                ));
            } else if (!rel.iFollowThem() && rel.theyFollowMe()) {
                if (followingsTruncated && !rel.relationshipVerified()) {
                    unknownFollowingCount++;
                    continue;
                }
                String category = theirIn >= 1 ? "LOYAL_FAN" : "NEW_FOLLOWER";
                String label = theirIn >= 1 ? "来评暖场铁粉" : "关注我的粉丝";
                String rec = theirIn >= 1
                        ? String.format("%s内主动来评你 %d 帖（你去评 %d 帖）且已关注你，强烈建议回关+回访巩固关系！", periodLabel, theirIn, myOut)
                        : "对方已关注你，若主页方向同频可礼貌回关";
                fansNotFollowedBack.add(new FollowAuditItem(
                        u, "FAN_ONLY", false, true,
                        myOut, theirIn, totalInter, category, label, rec, url, relationshipOrder, intimacyScore
                ));
            } else if (rel.iFollowThem() && rel.theyFollowMe()) {
                String category;
                String label;
                String rec;
                if (myOut > 0 && theirIn > 0) {
                    category = totalInter >= 4 ? "CORE_MUTUAL" : "ACTIVE_MUTUAL";
                    label = totalInter >= 4 ? "高频双向互评" : "双向互评好友";
                    rec = String.format("互关且双向互评（我去评 %d 帖 : TA 来评 %d 帖），保持良性互动", myOut, theirIn);
                } else if (theirIn > 0) {
                    category = "ACTIVE_MUTUAL";
                    label = "互关·我未回访";
                    rec = String.format("互关好友，TA 来评我 %d 帖但我尚未去评 TA 主帖，建议去主页留评回访", theirIn);
                } else if (myOut > 0) {
                    category = "ACTIVE_MUTUAL";
                    label = "互关·TA未回评";
                    rec = String.format("互关好友，我去评 TA %d 帖但 TA 本周期尚未回评我帖", myOut);
                } else {
                    category = "SILENT_MUTUAL";
                    label = "互关·静默零互评";
                    rec = periodLabel + "内双方均未去对方主帖评论，可去主页评论破冰唤醒";
                }
                mutuals.add(new FollowAuditItem(
                        u, "MUTUAL", true, true,
                        myOut, theirIn, totalInter, category, label, rec, url, relationshipOrder, intimacyScore
                ));
            }
        }

        notFollowingBack.sort(Comparator
                .comparingInt((FollowAuditItem item) -> switch (item.auditCategory()) {
                    case "ONE_WAY_EFFORT" -> 0;
                    case "ZERO_INTERACTION" -> 1;
                    case "HAS_INTERACTION" -> 2;
                    default -> 3;
                })
                .thenComparingInt(item -> item.user().followersCount()));

        fansNotFollowedBack.sort(Comparator.comparingInt(FollowAuditItem::theirRepliesToMe).reversed()
                .thenComparing(Comparator.comparingInt((FollowAuditItem i) -> i.user().followersCount()).reversed()));

        mutuals.sort(Comparator.comparingInt(FollowAuditItem::totalInteractions).reversed());

        double followBackRate = followingCount > 0
                ? round1(mutuals.size() * 100.0 / followingCount)
                : 0.0;

        boolean followBackRateComplete = !followingsTruncated && unknownFollowBackCount == 0;
        String followBackRateLabel;
        if (followBackRateComplete) {
            followBackRateLabel = String.format("关注回关率 %.1f%%", followBackRate)
                    + (followersTruncated ? "（关注对象关系已确认；粉丝名单未完整）" : "");
        } else {
            String incompleteReason = followingsTruncated
                    ? (followersTruncated ? "关注及粉丝名单未完整" : "关注名单未完整")
                    : "粉丝名单未完整";
            if (unknownFollowBackCount > 0) incompleteReason += "，含未知关系";
            followBackRateLabel = String.format("样本回关率 %.1f%%（%s）", followBackRate, incompleteReason);
        }

        return new FollowAuditOverview(
                followingCount,
                followersCount,
                followersTruncated,
                mutuals.size(),
                notFollowingBack.size(),
                fansNotFollowedBack.size(),
                suggestedUnfollowCount,
                bigAccountCount,
                followBackRate,
                notFollowingBack,
                fansNotFollowedBack,
                mutuals,
                followingsTruncated,
                unknownFollowBackCount,
                unknownFollowingCount,
                followBackRateLabel,
                followBackRateComplete
        );
    }

    private ContentTimingOverview buildContentTimingOverview(
            XUserSummary profile,
            List<RawTweet> timeline,
            Map<String, Long> mainPostGapMinutesById,
            Set<String> authorEngagedMainPostIds,
            ZoneId zoneId,
            XAlgorithmProfile algo
    ) {
        String username = profile.username();
        int followers = Math.max(0, profile.followersCount());
        XAlgorithmProfile.ScoringWeights scoring = algo.scoring();
        List<RawTweet> authored = timeline.stream()
                .filter(CommunityAnalyticsEngine::isIndependentMainPost)
                .toList();

        // count, views, likes, replies, ER engagements, view count, like count, reply count, ER views, ER count
        long[][] fmtAgg = new long[3][10];
        long[] fmtBookmarkSum = new long[3];
        int[] fmtBookmarkCount = new int[3];
        long[] fmtAlgoScoreSum = new long[3];
        int[] fmtAlgoScoreCount = new int[3];

        for (RawTweet t : authored) {
            long views = Math.max(0L, t.viewCount());
            int fmtIdx = t.isThread() ? 2 : (t.hasMedia() ? 1 : 0);
            fmtAgg[fmtIdx][0]++;
            if (metricKnown(t, "views")) {
                fmtAgg[fmtIdx][1] += views;
                fmtAgg[fmtIdx][5]++;
            }
            if (metricKnown(t, "likes")) {
                fmtAgg[fmtIdx][2] += Math.max(0, t.likeCount());
                fmtAgg[fmtIdx][6]++;
            }
            if (metricKnown(t, "replies")) {
                fmtAgg[fmtIdx][3] += Math.max(0, t.replyCount());
                fmtAgg[fmtIdx][7]++;
            }
            if (metricKnown(t, "bookmarks")) {
                fmtBookmarkSum[fmtIdx] += Math.max(0, t.bookmarkCount());
                fmtBookmarkCount[fmtIdx]++;
            }
            if (rateKnown(t)) {
                long eng = Math.min(engagements(t), views);
                fmtAgg[fmtIdx][4] += eng;
                fmtAgg[fmtIdx][8] += views;
                fmtAgg[fmtIdx][9]++;
            }
        }

        // 先计算所有主推评分，用于各体裁平均规则分汇总及排序
        List<TopPostCard> allScoredCards = authored.stream()
                .map(t -> buildScoredTopPostCard(
                        t, username, followers, mainPostGapMinutesById.get(t.id()),
                        t.id() != null && authorEngagedMainPostIds.contains(t.id()), zoneId, algo))
                .toList();

        for (TopPostCard card : allScoredCards) {
            if (card.algoScore() >= 0) {
                int fmtIdx = card.isThread() ? 2 : (card.hasMedia() ? 1 : 0);
                fmtAlgoScoreSum[fmtIdx] += card.algoScore();
                fmtAlgoScoreCount[fmtIdx]++;
            }
        }

        String[][] fmtMeta = {
                {"TEXT", "纯文本观点贴"},
                {"MEDIA", "配图 / 视频贴"},
                {"THREAD", "长文 / 串推 Thread"}
        };
        long totalViewKnownSum = 0L;
        int totalViewKnownCnt = 0;
        long[] fmtAvgViews = new long[3];
        for (int i = 0; i < 3; i++) {
            if (fmtAgg[i][5] > 0) {
                fmtAvgViews[i] = Math.round((double) fmtAgg[i][1] / fmtAgg[i][5]);
                totalViewKnownSum += fmtAgg[i][1];
                totalViewKnownCnt += (int) fmtAgg[i][5];
            }
        }
        long overallAvgViews = totalViewKnownCnt > 0 ? Math.round((double) totalViewKnownSum / totalViewKnownCnt) : 0L;
        int bestViewsIdx = -1;
        long bestViewsVal = 0L;
        for (int i = 0; i < 3; i++) {
            if (fmtAgg[i][0] > 0 && fmtAgg[i][5] > 0 && fmtAvgViews[i] > bestViewsVal) {
                bestViewsVal = fmtAvgViews[i];
                bestViewsIdx = i;
            }
        }

        List<ContentFormatStat> formatStats = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int cnt = (int) fmtAgg[i][0];
            long avgV = fmtAvgViews[i];
            double avgBm = fmtBookmarkCount[i] > 0 ? round1((double) fmtBookmarkSum[i] / fmtBookmarkCount[i]) : 0.0;
            int avgAlgo = fmtAlgoScoreCount[i] > 0 ? (int) Math.round((double) fmtAlgoScoreSum[i] / fmtAlgoScoreCount[i]) : 0;
            String advice = buildFormatAlgoAdvice(
                    fmtMeta[i][0], cnt, avgV, overallAvgViews, i == bestViewsIdx,
                    avgBm, fmtBookmarkCount[i], fmtAlgoScoreCount[i] > 0 ? avgAlgo : -1, algo.upstreamEvidence());
            formatStats.add(new ContentFormatStat(
                    fmtMeta[i][0],
                    fmtMeta[i][1],
                    cnt,
                    avgV,
                    fmtAgg[i][6] > 0 ? round1((double) fmtAgg[i][2] / fmtAgg[i][6]) : 0.0,
                    fmtAgg[i][7] > 0 ? round1((double) fmtAgg[i][3] / fmtAgg[i][7]) : 0.0,
                    safeErPct(fmtAgg[i][4], fmtAgg[i][8]),
                    (int) fmtAgg[i][5], (int) fmtAgg[i][6], (int) fmtAgg[i][7], (int) fmtAgg[i][9],
                    avgBm,
                    fmtBookmarkCount[i],
                    avgAlgo,
                    fmtAlgoScoreCount[i],
                    advice
            ));
        }

        // 按公开互动量与当前生效算法权重排序；评分为本地启发式参考，不代表平台内部权重或推荐来源。
        List<TopPostCard> topPosts = allScoredCards.stream()
                .sorted(Comparator.comparingDouble((TopPostCard p) ->
                        p.unavailableMetrics().isEmpty()
                                ? p.replyCount() * scoring.compositeReplyWeight()
                                        + p.retweetCount() * scoring.compositeRepostWeight()
                                        + p.bookmarkCount() * scoring.compositeBookmarkWeight()
                                        + p.likeCount() * scoring.compositeLikeWeight()
                                        + Math.log10(Math.max(10, p.viewCount())) * scoring.compositeViewLogWeight()
                                        + (p.id() != null && authorEngagedMainPostIds.contains(p.id())
                                                ? scoring.compositeAuthorEngagedBonus() : 0.0)
                                : Double.NEGATIVE_INFINITY
                ).reversed())
                .limit(24)
                .toList();

        return new ContentTimingOverview(
                zoneId.getId(),
                formatStats,
                topPosts
        );
    }

    private static String buildFormatAlgoAdvice(
            String formatCode,
            int postCount,
            long avgViews,
            long overallAvgViews,
            boolean isBestViewsFormat,
            double avgBookmarks,
            int bookmarkSampleCount,
            int avgAlgoScore,
            XAlgorithmProfile.UpstreamEvidenceSpec ev
    ) {
        if (postCount == 0) {
            return switch (formatCode) {
                case "MEDIA" -> String.format("本周期暂无图文或视频（视频满 %d 秒计入有效观看），可补充测试。",
                        ev.minVideoDurationMs() / 1000);
                case "THREAD" -> "本周期暂无长推或串推，适合拆解步骤或深度复盘。";
                default -> "本周期暂无纯文本推文，纯文本更依赖评论互动与转发分享。";
            };
        }
        if (isBestViewsFormat && overallAvgViews > 0L) {
            int liftPct = (int) Math.round((avgViews - overallAvgViews) * 100.0 / overallAvgViews);
            if (liftPct >= 15) {
                return String.format("本账号当前表现最佳体裁，均浏览比整体高 %d%%。", liftPct);
            }
        }
        if ("TEXT".equals(formatCode)) {
            if (bookmarkSampleCount > 0 && avgBookmarks < 1.0) {
                return String.format("当前均收藏 %.1f，可尝试配图或分点排版提升留存与分享。", avgBookmarks);
            }
            return "纯文本主要靠评论互动与复制分享带动扩散，注意避免用 @ 开头。";
        }
        if ("MEDIA".equals(formatCode)) {
            return String.format("图片点击展开与视频观看有助延长停留（视频建议不少于 %d 秒）。",
                    ev.minVideoDurationMs() / 1000);
        }
        return "串推在单次刷新中只展示热度最高的 1 条，首条建议直接亮出结论。";
    }

    private InnerCircleOverview buildInnerCircleOverview(
            XUserSummary profile,
            String reportShortTitle,
            String periodLabel,
            ActivityOverview activity,
            InteractionOverview interactions,
            FollowAuditOverview followAudit
    ) {
        List<PeerInteractionStat> allPeers = interactions.peerStats();

        List<CircleMember> coreFans = allPeers.stream()
                .filter(p -> p.peerRepliesToMe() > 0)
                .sorted(Comparator.comparingInt(PeerInteractionStat::peerRepliesToMe).reversed()
                        .thenComparing(Comparator.comparingDouble(PeerInteractionStat::intimacyScore).reversed()))
                .limit(6)
                .map(p -> new CircleMember(
                        p.peer(),
                        "CORE",
                        "核心铁粉",
                        p.myRepliesToPeer(),
                        p.peerRepliesToMe(),
                        p.totalInteractions(),
                        p.intimacyScore(),
                        p.iFollowThem() && p.theyFollowMe(),
                        String.format("来评我 %d 帖 · 我去评 %d 帖（回礼率 %s）",
                                p.peerRepliesToMe(), p.myRepliesToPeer(), p.returnRateLabel())
                ))
                .toList();

        List<CircleMember> activeMutuals = allPeers.stream()
                .filter(p -> "MUTUAL".equals(p.reciprocityCode()))
                .limit(6)
                .map(p -> new CircleMember(
                        p.peer(),
                        "ACTIVE",
                        "双向互评",
                        p.myRepliesToPeer(),
                        p.peerRepliesToMe(),
                        p.totalInteractions(),
                        p.intimacyScore(),
                        p.iFollowThem() && p.theyFollowMe(),
                        String.format("我去评 %d 帖 : 他来评 %d 帖 · 占总互动 %.1f%%",
                                p.myRepliesToPeer(), p.peerRepliesToMe(), p.interactionSharePct())
                ))
                .toList();

        List<CircleMember> needWarmup = allPeers.stream()
                .filter(p -> p.unrepliedInMyPostCount() > 0 || "I_OWE".equals(p.reciprocityCode())
                        || p.peerRepliesToMe() > p.myRepliesToPeer())
                .sorted(Comparator
                        .comparingInt(PeerInteractionStat::unrepliedInMyPostCount).reversed()
                        .thenComparing(Comparator.comparingInt((PeerInteractionStat p) -> p.peerRepliesToMe() - p.myRepliesToPeer()).reversed()))
                .limit(6)
                .map(p -> new CircleMember(
                        p.peer(),
                        "WARMUP",
                        p.unrepliedInMyPostCount() > 0 ? "漏回评·待回访" : "待回访他帖",
                        p.myRepliesToPeer(),
                        p.peerRepliesToMe(),
                        p.totalInteractions(),
                        p.intimacyScore(),
                        p.iFollowThem() && p.theyFollowMe(),
                        p.unrepliedInMyPostCount() > 0
                                ? String.format("TA 来评你 %d 帖（你有 %d 帖未在评论区回 TA，去评 TA %d 帖），快去补回！",
                                        p.peerRepliesToMe(), p.unrepliedInMyPostCount(), p.myRepliesToPeer())
                                : String.format("TA 来评你 %d 帖，你仅去评 TA %d 帖，去对方最新推文留评回访！",
                                        p.peerRepliesToMe(), p.myRepliesToPeer())
                ))
                .toList();

        List<String> topBuddyHandles = coreFans.stream()
                .limit(3)
                .map(m -> "@" + m.user().username())
                .toList();

        String buddiesMention = topBuddyHandles.isEmpty()
                ? "期待结识更多同频推友"
                : "最佳互动搭子：" + String.join(" ", topBuddyHandles);

        int authoredTotal = activity.originalPosts() + activity.quotes();
        String sharePeriodLabel = activity.sampleCapped() ? periodLabel + " · 样本" : periodLabel;
        String shareTweetText = String.format(
                "📊 我的 X 社区运营%s（@%s｜%s）\n\n" +
                "✍️ 原创及引用 %d 条 ｜ 💬 评论互动 %d 条（回复比 %.1fx）\n" +
                "⚡ 平均互动率 %s ｜ 🤝 %s\n" +
                "🏆 运营状态：%s（综合评分 %s）\n" +
                "💎 %s\n\n" +
                "#BuildInPublic #X运营",
                reportShortTitle,
                profile.username(),
                sharePeriodLabel,
                authoredTotal,
                activity.replies(),
                activity.replyToPostRatio(),
                activity.engagementRateSampleCount() > 0 ? String.format("%.2f%%", activity.avgEngagementRatePct()) : "--（暂无可用样本）",
                followAudit.followBackRateLabel(),
                activity.personaTag(),
                activity.activityHealthScore() >= 0 ? activity.activityHealthScore() + "/100" : "--（指标不足）",
                buddiesMention
        );
        if (activity.sampleCapped()) {
            shareTweetText += "\n\n⚠️ " + activity.coverageNote() + "；以上为已采集样本，非完整周期结果。";
        }
        if (!activity.metricsNote().isEmpty()) shareTweetText += "\n⚠️ " + activity.metricsNote();

        ShareCardPayload shareCard = new ShareCardPayload(
                "@" + profile.username() + " · X 社区运营" + reportShortTitle,
                "X 社区运营" + reportShortTitle,
                activity.personaTag(),
                sharePeriodLabel,
                activity.windowDays(),
                authoredTotal,
                activity.replies(),
                activity.replyToPostRatio(),
                activity.avgEngagementRatePct(),
                followAudit.followBackRatePct(),
                activity.activityHealthScore(),
                topBuddyHandles,
                shareTweetText,
                followAudit.followBackRateLabel(),
                activity.engagementRateSampleCount(),
                activity.metricsNote()
        );

        return new InnerCircleOverview(coreFans, activeMutuals, needWarmup, shareCard);
    }

    private static XUserSummary preferRicherUser(XUserSummary a, XUserSummary b) {
        if (a == null) return b;
        if (b == null) return a;
        XUserSummary chosen = richnessScore(b) > richnessScore(a) ? b : a;
        boolean verified = a.verified() || b.verified();
        if (chosen.verified() == verified) {
            return chosen;
        }
        return new XUserSummary(
                chosen.id(), chosen.username(), chosen.displayName(), chosen.avatarUrl(),
                chosen.bio(), verified, chosen.followersCount(), chosen.followingCount(),
                chosen.totalPostsCount(), chosen.joinedDate());
    }

    private static int richnessScore(XUserSummary u) {
        int s = 0;
        if (u.displayName() != null && !u.displayName().isBlank() && !u.displayName().equals(u.username())) s += 3;
        if (u.avatarUrl() != null && !u.avatarUrl().isBlank()) s += 3;
        if (u.bio() != null && !u.bio().isBlank()) s += 2;
        if (u.followersCount() > 0) s += 2;
        return s;
    }

    private static Set<String> computeAuthorEngagedMainPostIds(
            List<RawTweet> allTimeline,
            List<InboundInteraction> allInbound,
            String selfKey
    ) {
        Map<String, String> convToMainPostId = new HashMap<>();
        Map<String, String> inboundCommentToMainPostId = new HashMap<>();
        for (RawTweet t : allTimeline) {
            if (!isIndependentMainPost(t)) continue;
            String tid = normId(t.id());
            String cid = normId(t.conversationId());
            if (!tid.isEmpty()) convToMainPostId.put(tid, tid);
            if (!cid.isEmpty()) convToMainPostId.putIfAbsent(cid, !tid.isEmpty() ? tid : cid);
        }
        for (InboundInteraction in : allInbound) {
            if (in == null || in.kind() != InboundKind.REPLY_TO_ME) continue;
            String cid = normId(in.conversationId());
            String tid = normId(in.tweetId());
            String ownerMain = !cid.isEmpty() ? convToMainPostId.get(cid) : null;
            if (ownerMain != null && !tid.isEmpty()) {
                inboundCommentToMainPostId.put(tid, ownerMain);
            }
        }
        Set<String> engaged = new HashSet<>();
        for (RawTweet t : allTimeline) {
            if (t == null || t.kind() != TweetKind.REPLY || t.selfThreadReply()) continue;
            String targetKey = normKey(t.targetUsername());
            if (!targetKey.isEmpty() && selfKey.equals(targetKey)) continue;
            String cid = normId(t.conversationId());
            String rid = normId(t.inReplyToId());
            if (!rid.isEmpty()) {
                String ownerByInbound = inboundCommentToMainPostId.get(rid);
                if (ownerByInbound != null) {
                    engaged.add(ownerByInbound);
                    continue;
                }
            }
            if (!targetKey.isEmpty() && !cid.isEmpty()) {
                String ownerByConv = convToMainPostId.get(cid);
                if (ownerByConv != null) {
                    engaged.add(ownerByConv);
                }
            }
        }
        return engaged;
    }

    private static boolean isIndependentMainPost(RawTweet t) {
        return t != null && ((t.kind() == TweetKind.POST && !t.selfThreadReply()) || t.kind() == TweetKind.QUOTE);
    }

    private static Map<String, Long> computeMainPostGapsMinutes(List<RawTweet> allTimeline) {
        Map<String, Long> gapMap = new HashMap<>();
        if (allTimeline == null || allTimeline.isEmpty()) {
            return gapMap;
        }
        List<RawTweet> chronological = allTimeline.stream()
                .filter(t -> isIndependentMainPost(t) && t.createdAt() != null && t.id() != null)
                .sorted(Comparator.comparing(RawTweet::createdAt))
                .toList();
        Instant prev = null;
        for (RawTweet t : chronological) {
            if (prev != null) {
                long gapMin = Math.max(0L, Duration.between(prev, t.createdAt()).toMinutes());
                gapMap.put(t.id(), gapMin);
            }
            prev = t.createdAt();
        }
        return gapMap;
    }

    static boolean hasExternalUrl(RawTweet t) {
        String text = t.text() != null ? t.text() : "";
        Matcher m = URL_PATTERN.matcher(text);
        int totalUrls = 0;
        boolean hasNonTcoOrXDomain = false;
        while (m.find()) {
            totalUrls++;
            String url = m.group().toLowerCase(Locale.ROOT);
            if (!url.startsWith("https://t.co/")
                    && !url.startsWith("http://t.co/")
                    && !url.contains("://x.com/")
                    && !url.contains("://twitter.com/")) {
                hasNonTcoOrXDomain = true;
            }
        }
        if (totalUrls == 0) {
            return false;
        }
        if (hasNonTcoOrXDomain) {
            return true;
        }
        // 配图/视频贴或引用贴末尾会自动附带 1 个原生 t.co 媒体短链，不视为站外导流链接
        if (t.hasMedia() || t.kind() == TweetKind.QUOTE) {
            return totalUrls >= 2;
        }
        return true;
    }

    private TopPostCard buildScoredTopPostCard(
            RawTweet t,
            String username,
            int followersCount,
            Long gapMinutesSincePrev,
            boolean authorEngaged,
            ZoneId zoneId,
            XAlgorithmProfile algo
    ) {
        XAlgorithmProfile.ScoringWeights scoring = algo.scoring();
        XAlgorithmProfile.CadenceAndReachRules rules = algo.cadenceAndReach();
        XAlgorithmProfile.OpsPlaybookRules playbook = algo.opsPlaybook();

        long views = Math.max(0L, t.viewCount());
        int likes = Math.max(0, t.likeCount());
        int replies = Math.max(0, t.replyCount());
        int retweets = Math.max(0, t.retweetCount());
        int bookmarks = Math.max(0, t.bookmarkCount());
        int eng = engagementKnown(t) ? (int) Math.min(Integer.MAX_VALUE, engagements(t)) : 0;
        double er = rateKnown(t) ? safeErPct(eng, views) : 0.0;
        double bmRate = safeErPct(bookmarks, views);

        double reachMultiple = followersCount > 0 ? round2((double) views / followersCount) : 0.0;
        String reachTierCode;
        String reachTierLabel;
        if (!metricKnown(t, "views")) {
            reachTierCode = "UNKNOWN";
            reachTierLabel = "浏览数未知";
        } else if (views >= Math.max(rules.oonMinViewsFloor(), followersCount) && reachMultiple >= rules.oonFollowerMultiple()) {
            reachTierCode = "OON_VIRAL";
            reachTierLabel = String.format("📊 浏览为粉丝数 %.1f 倍", reachMultiple);
        } else if (followersCount >= rules.lowReachMinFollowers() && views > 0 && views < followersCount * rules.lowReachFollowerRatio()) {
            reachTierCode = "LOW_REACH";
            reachTierLabel = String.format("📉 浏览量低于粉丝数 %d%%", Math.round(rules.lowReachFollowerRatio() * 100.0));
        } else {
            reachTierCode = "IN_NETWORK";
            reachTierLabel = "📊 其他浏览量样本（来源未知）";
        }

        boolean extLink = hasExternalUrl(t);
        boolean rapidFire = gapMinutesSincePrev != null && gapMinutesSincePrev < rules.minRecommendedGapMinutes();
        boolean controversyRisk = metricKnown(t, "replies") && metricKnown(t, "likes") && metricKnown(t, "bookmarks")
                && replies >= rules.controversyMinReplies()
                && replies > (likes + bookmarks) * rules.controversyReplyToPositiveRatio();

        // 公开计数与内容特征的本地启发式评分，不能还原平台权重、停留时长或分发状态。
        long effViews = Math.max(50L, views);
        double replyRate = Math.min(8.0, replies * 100.0 / effViews);
        double bmPct = Math.min(8.0, bookmarks * 100.0 / effViews);
        double rtPct = Math.min(8.0, retweets * 100.0 / effViews);
        double lkPct = Math.min(12.0, likes * 100.0 / effViews);

        double replyScore = Math.min(scoring.replyMaxPoints(),
                Math.sqrt(replyRate / scoring.replyRatePivotPct()) * scoring.replyRateCoef()
                        + Math.min(scoring.replyCountCap(), replies * scoring.replyCountCoef()));
        if (authorEngaged) {
            replyScore += scoring.authorReplyEngagementBonus();
        }
        double dwellBookmarkScore = Math.min(scoring.dwellBookmarkMaxPoints(),
                Math.sqrt(bmPct / scoring.bookmarkRatePivotPct()) * scoring.bookmarkRateCoef()
                        + (t.isThread() || (t.text() != null && t.text().length() >= scoring.longTextMinChars()) ? scoring.longTextBonus() : 0.0)
                        + (t.hasMedia() ? scoring.mediaDwellBonus() : 0.0));
        double shareOutScore = Math.min(scoring.shareQuoteMaxPoints(),
                Math.sqrt(rtPct / scoring.repostRatePivotPct()) * scoring.repostRateCoef()
                        + Math.min(scoring.reachMultipleCap(), Math.log10(Math.max(1.0, reachMultiple * 10.0)) * scoring.reachMultipleCoef()));
        if (t.kind() == TweetKind.QUOTE) {
            shareOutScore += scoring.quotePostBonus();
        }
        double favScore = Math.min(scoring.likeMaxPoints(),
                Math.sqrt(lkPct / scoring.likeRatePivotPct()) * scoring.likeRateCoef()
                        + Math.min(scoring.likeCountCap(), likes * scoring.likeCountCoef()));

        double rawScore = scoring.baseScore() + replyScore + dwellBookmarkScore + shareOutScore + favScore;
        List<String> posSignals = new ArrayList<>(4);
        List<String> penaltySignals = new ArrayList<>(3);

        if ("OON_VIRAL".equals(reachTierCode)) {
            posSignals.add(String.format("浏览 %.1fx 粉丝", reachMultiple));
        }
        if (authorEngaged) {
            posSignals.add("帖内作者接话");
        }
        if (bookmarks >= 3 && bmRate >= playbook.highBookmarkRatePct()) {
            posSignals.add("高收藏率");
        } else if (t.isThread()) {
            posSignals.add("串推");
        }
        if (replies >= 4 && !controversyRisk) {
            posSignals.add("评论活跃");
        }

        if (rapidFire) {
            boolean severe = gapMinutesSincePrev < rules.severeRapidFireGapMinutes();
            int deduct = severe ? rules.severeRapidFirePenalty() : rules.mildRapidFirePenalty();
            rawScore -= deduct;
            penaltySignals.add(severe
                    ? String.format("距上条仅 %d 分钟", gapMinutesSincePrev)
                    : String.format("%dh 内连发", Math.max(1L, Math.round(rules.minRecommendedGapMinutes() / 60.0))));
        }
        if (extLink && rules.externalLinkPenalty() > 0) {
            rawScore -= rules.externalLinkPenalty();
            penaltySignals.add("含外链");
        }
        if (controversyRisk && rules.controversyPenalty() > 0) {
            rawScore -= rules.controversyPenalty();
            penaltySignals.add("评多赞少");
        }

        List<String> signals = new ArrayList<>(3);
        int posQuota = Math.max(0, 3 - penaltySignals.size());
        for (int i = 0; i < Math.min(posQuota, posSignals.size()); i++) {
            signals.add(posSignals.get(i));
        }
        for (String pen : penaltySignals) {
            if (signals.size() < 3) {
                signals.add(pen);
            }
        }
        if (signals.isEmpty()) {
            signals.add(reachTierLabel);
        }

        int finalScore = (int) Math.max(scoring.minScore(), Math.min(scoring.maxScore(), Math.round(rawScore)));
        String tierLabel = finalScore >= 80 ? "本地启发式：较高"
                : finalScore >= 62 ? "本地启发式：偏高"
                : finalScore >= 45 ? "本地启发式：中等"
                : "本地启发式：偏低";

        if (!t.unavailableMetrics().isEmpty()) {
            finalScore = -1;
            tierLabel = "公开指标不完整 · 暂不评分或排名";
            signals.clear();
            signals.add("缺失指标：" + String.join("、", new TreeSet<>(t.unavailableMetrics())));
        }
        return new TopPostCard(
                t.id(),
                t.text(),
                t.createdAt(),
                t.createdAt().atZone(zoneId).format(POST_TIME_FMT),
                t.kind().name(),
                t.hasMedia(),
                t.isThread(),
                views,
                likes,
                replies,
                retweets,
                bookmarks,
                eng,
                er,
                bmRate,
                finalScore,
                tierLabel,
                reachTierCode,
                reachTierLabel,
                reachMultiple,
                extLink,
                rapidFire,
                List.copyOf(signals),
                "https://x.com/" + username + "/status/" + t.id(),
                t.unavailableMetrics()
        );
    }

    private AlgorithmHealthSummary buildAlgorithmHealthSummary(
            XUserSummary profile,
            String periodLabel,
            List<RawTweet> allTimeline,
            List<RawTweet> windowTimeline,
            Map<String, Long> mainPostGapMinutesById,
            Set<String> authorEngagedMainPostIds,
            ActivityOverview activity,
            InteractionOverview interactions,
            FollowAuditOverview followAudit,
            ContentTimingOverview contentTiming,
            Instant refNow,
            ZoneId zoneId,
            XAlgorithmProfile algo
    ) {
        XAlgorithmProfile.CadenceAndReachRules rules = algo.cadenceAndReach();
        int followers = Math.max(0, profile.followersCount());
        List<RawTweet> windowMain = windowTimeline.stream()
                .filter(CommunityAnalyticsEngine::isIndependentMainPost)
                .toList();

        Instant latestMainPostAt = allTimeline.stream()
                .filter(t -> isIndependentMainPost(t) && t.createdAt() != null && !t.createdAt().isAfter(refNow))
                .map(RawTweet::createdAt)
                .max(Comparator.naturalOrder())
                .orElse(null);

        int evalCount = 0;
        int oonCount = 0;
        int inNetCount = 0;
        int lowReachCount = 0;
        int rapidFireCount = 0;
        int extLinkCount = 0;
        long extLinkViewsSum = 0L;
        int extViewCount = 0;
        int nativeViewCount = 0;
        long nativeViewsSum = 0L;
        int controversyCount = 0;
        int controversySampleCount = 0;
        int authorEngagedPostCount = 0;
        int bookmarkKnownMainCount = 0;
        int highBookmarkMainCount = 0;

        for (RawTweet t : windowMain) {
            long v = Math.max(0L, t.viewCount());
            double mult = followers > 0 ? (double) v / followers : 0.0;
            if (metricKnown(t, "views")) {
                evalCount++;
                if (v >= Math.max(rules.oonMinViewsFloor(), followers) && mult >= rules.oonFollowerMultiple()) oonCount++;
                else if (followers >= rules.lowReachMinFollowers() && v > 0 && v < followers * rules.lowReachFollowerRatio()) lowReachCount++;
                else inNetCount++;
            }

            if (metricKnown(t, "views") && v > 0 && metricKnown(t, "bookmarks")) {
                bookmarkKnownMainCount++;
                double bmPct = Math.max(0L, t.bookmarkCount()) * 100.0 / v;
                if (bmPct >= algo.opsPlaybook().highBookmarkRatePct()) {
                    highBookmarkMainCount++;
                }
            }

            if (t.id() != null && authorEngagedMainPostIds.contains(t.id())) {
                authorEngagedPostCount++;
            }

            Long gap = mainPostGapMinutesById.get(t.id());
            if (gap != null && gap < rules.minRecommendedGapMinutes()) {
                rapidFireCount++;
            }

            if (hasExternalUrl(t)) {
                extLinkCount++;
                if (metricKnown(t, "views")) {
                    extLinkViewsSum += v;
                    extViewCount++;
                }
            } else {
                if (metricKnown(t, "views")) {
                    nativeViewsSum += v;
                    nativeViewCount++;
                }
            }

            if (metricKnown(t, "replies") && metricKnown(t, "likes") && metricKnown(t, "bookmarks")) {
                controversySampleCount++;
                if (t.replyCount() >= rules.controversyMinReplies()
                        && t.replyCount() > (t.likeCount() + t.bookmarkCount()) * rules.controversyReplyToPositiveRatio()) {
                    controversyCount++;
                }
            }
        }

        double oonRatePct = evalCount > 0 ? round1(oonCount * 100.0 / evalCount) : 0.0;
        long extAvg = extViewCount > 0 ? Math.round((double) extLinkViewsSum / extViewCount) : 0L;
        long nativeAvg = nativeViewCount > 0 ? Math.round((double) nativeViewsSum / nativeViewCount) : 0L;
        int linkLossPct = (extViewCount > 0 && nativeViewCount > 0 && nativeAvg > extAvg && nativeAvg > 0)
                ? (int) Math.round((nativeAvg - extAvg) * 100.0 / nativeAvg)
                : 0;

        // 比较公开样本均浏览变化；发帖时长、样本完整性、题材等均可能影响结果，不能诊断限流。
        Instant cut7d = refNow.minus(Duration.ofDays(7));
        Instant cut30d = refNow.minus(Duration.ofDays(30));
        List<RawTweet> recent7dMain = allTimeline.stream()
                .filter(t -> isIndependentMainPost(t) && metricKnown(t, "views") && t.createdAt() != null
                        && !t.createdAt().isBefore(cut7d) && !t.createdAt().isAfter(refNow))
                .toList();
        List<RawTweet> prev8to30dMain = allTimeline.stream()
                .filter(t -> isIndependentMainPost(t) && metricKnown(t, "views") && t.createdAt() != null
                        && t.createdAt().isBefore(cut7d) && !t.createdAt().isBefore(cut30d))
                .toList();

        long avg7d = recent7dMain.isEmpty() ? 0L
                : Math.round(recent7dMain.stream().mapToLong(t -> Math.max(0L, t.viewCount())).average().orElse(0.0));
        long avgPrev = prev8to30dMain.isEmpty() ? 0L
                : Math.round(prev8to30dMain.stream().mapToLong(t -> Math.max(0L, t.viewCount())).average().orElse(0.0));

        int recentVsBasePct = (recent7dMain.size() >= 2 && prev8to30dMain.size() >= 2 && avgPrev >= 100L)
                ? (int) Math.round((avg7d - avgPrev) * 100.0 / avgPrev)
                : 0;
        boolean cliffDrop = recentVsBasePct <= rules.cliffDropPctThreshold()
                || (evalCount >= 3 && lowReachCount * 2 >= evalCount);

        String gapLabel = (rules.minRecommendedGapMinutes() % 60L == 0L)
                ? (rules.minRecommendedGapMinutes() / 60L) + " 小时"
                : rules.minRecommendedGapMinutes() + " 分钟";
        String diversityAdvice = rapidFireCount == 0
                ? String.format("ℹ️ 样本中未发现间隔不足 %s 的主推；无法据此判断平台折叠或降权", gapLabel)
                : String.format("⏱️ 样本中 %d 条主推间隔不足 %s；可尝试调整节奏并观察变化，无法据此确认平台折叠或降权", rapidFireCount, gapLabel);

        String contentRuleAdvice;
        if (linkLossPct >= rules.linkLossNoticePct()) {
            contentRuleAdvice = String.format("🔗 样本中带外链主推均浏览低 %d%%（%d vs %d）；题材、发布时间和观察时长可能不同，不能据此认定外链造成差异", linkLossPct, extAvg, nativeAvg);
        } else if (extLinkCount > 0) {
            contentRuleAdvice = String.format("🔗 样本中 %d 条主推正文带外链；可比较不同链接位置的后续表现，当前数据无法确认其影响", extLinkCount);
        } else if (controversyCount > 0) {
            contentRuleAdvice = String.format("💬 样本中 %d 条推文评论数明显高于点赞收藏；原因未知，无法据此推断负面反馈或平台处罚", controversyCount);
        } else {
            contentRuleAdvice = "ℹ️ 样本中未出现上述外链或评论比例特征；这不代表平台评分或处罚状态";
        }

        // 对照上游 underTheHoodReport.User.strato（满 365 天 + 上一自然月发帖 ≥ 10 条，月结 10 天后出报）；本地用近 30 天公开样本近似预估。
        int uthMinPosts = algo.upstreamEvidence().underTheHoodMinPriorMonthPosts();
        long postsLast30d = allTimeline.stream()
                .filter(t -> t.createdAt() != null && !t.createdAt().isBefore(cut30d) && !t.createdAt().isAfter(refNow))
                .count();
        Boolean ageOk = isAccountOverOneYearOld(profile.joinedDate(), refNow, zoneId);
        boolean uthEligible = Boolean.TRUE.equals(ageOk) && postsLast30d >= uthMinPosts;
        String uthHint = ageOk == null
                ? "ℹ️ 注册日期缺失或无法解析，无法核验上游满365天门槛；实际查看资格请前往官方页核验"
                : (uthEligible
                ? String.format("✅ 本地样本满足注册满1年且近30天动态≥%d条（上游源码按上一自然月≥%d条且功能开关放行核定），实际资格请前往官方页核验", uthMinPosts, uthMinPosts)
                : String.format("ℹ️ 本地样本尚未满足注册满1年且近30天动态≥%d条（上游源码按上一自然月≥%d条核定），实际资格请前往官方页核验", uthMinPosts, uthMinPosts));

        String healthLevel;
        String healthTitle;
        String healthSummary;
        if (evalCount == 0) {
            healthLevel = "UNKNOWN";
            healthTitle = "ℹ️ 暂无可用主推浏览样本";
            healthSummary = periodLabel + "内暂无浏览指标已知的独立主推样本，无法评价浏览量或平台分发状态";
        } else if (cliffDrop) {
            healthLevel = "ALERT";
            healthTitle = "🔴 公开样本浏览量偏低";
            healthSummary = recentVsBasePct <= rules.cliffDropPctThreshold()
                    ? String.format("公开样本中近 7 天主推均浏览较前期低 %d%%；内容、观察时长等因素未受控，无法据此判断限流状态", Math.abs(recentVsBasePct))
                    : String.format("%s公开样本中 %d/%d 条主推浏览量低于粉丝数的 %d%%；浏览量不等于独立触达人数，无法据此判断限流状态",
                            periodLabel, lowReachCount, evalCount, Math.round(rules.lowReachFollowerRatio() * 100.0));
        } else if (rapidFireCount > 0 || linkLossPct >= rules.linkLossWarnPct()) {
            healthLevel = "WARN";
            healthTitle = "🟡 公开样本有待观察项";
            healthSummary = rapidFireCount > 0
                    ? String.format("样本中 %d 条主推间隔不足 %s；可调整节奏后继续观察，当前数据无法判断限流或折叠状态", rapidFireCount, gapLabel)
                    : String.format("样本中带外链主推均浏览较无外链低 %d%%；这是相关性观察，无法确认因果效果或限流状态", linkLossPct);
        } else {
            healthLevel = "GOOD";
            healthTitle = "📊 公开样本浏览量观察";
            healthSummary = String.format("%s公开样本共 %d 条主推，其中 %d 条浏览量达到粉丝数且不少于 %d（样本占比 %.0f%%）；浏览可能重复，无法确认来源、独立触达人数或限流状态",
                    periodLabel, evalCount, oonCount, rules.oonMinViewsFloor(), oonRatePct);
        }

        boolean metricsIncomplete = windowMain.stream().anyMatch(t -> !t.unavailableMetrics().isEmpty());
        String metricsNote = metricsIncomplete
                ? String.format("公开指标不完整；浏览观察使用 %d/%d 条已知样本，未知数未按0计入，外链与历史对比也仅使用已知浏览样本", evalCount, windowMain.size()) : "";
        if (!metricsNote.isEmpty()) healthSummary += "；" + metricsNote;

        boolean recentComparisonAvailable = recent7dMain.size() >= 2 && prev8to30dMain.size() >= 2 && avgPrev >= 100L;
        boolean linkComparisonAvailable = extViewCount > 0 && nativeViewCount > 0 && nativeAvg > 0;
        String underTheHoodUrl = "https://x.com/i/jf/under_the_hood";

        EmpiricalAlgoCalibration calibration = buildEmpiricalCalibration(
                profile,
                allTimeline,
                windowMain,
                mainPostGapMinutesById,
                authorEngagedMainPostIds,
                interactions,
                followAudit,
                contentTiming,
                refNow,
                zoneId,
                algo
        );

        List<OpsRecommendationItem> opsRecommendations = buildOpsRecommendations(
                periodLabel,
                activity,
                interactions,
                followAudit,
                contentTiming,
                rapidFireCount,
                gapLabel,
                extLinkCount,
                linkLossPct,
                linkComparisonAvailable,
                controversyCount,
                cliffDrop,
                evalCount,
                oonCount,
                authorEngagedPostCount,
                windowMain.size(),
                bookmarkKnownMainCount,
                highBookmarkMainCount,
                underTheHoodUrl,
                calibration,
                algo
        );

        return new AlgorithmHealthSummary(
                healthLevel,
                healthTitle,
                healthSummary,
                evalCount,
                oonCount,
                inNetCount,
                lowReachCount,
                oonRatePct,
                rapidFireCount,
                latestMainPostAt,
                diversityAdvice,
                extLinkCount,
                extAvg,
                nativeAvg,
                linkLossPct,
                controversyCount,
                contentRuleAdvice,
                cliffDrop,
                recentVsBasePct,
                uthEligible,
                uthHint,
                underTheHoodUrl,
                evalCount,
                extViewCount,
                nativeViewCount,
                recent7dMain.size(),
                prev8to30dMain.size(),
                controversySampleCount,
                recentComparisonAvailable,
                linkComparisonAvailable,
                metricsNote,
                algo.version(),
                algo.name(),
                authorEngagedPostCount,
                opsRecommendations,
                calibration
        );
    }

    private List<OpsRecommendationItem> buildOpsRecommendations(
            String periodLabel,
            ActivityOverview activity,
            InteractionOverview interactions,
            FollowAuditOverview followAudit,
            ContentTimingOverview contentTiming,
            int rapidFireCount,
            String gapLabel,
            int extLinkCount,
            int linkLossPct,
            boolean linkComparisonAvailable,
            int controversyCount,
            boolean cliffDrop,
            int evalCount,
            int oonCount,
            int authorEngagedPostCount,
            int mainPostCount,
            int bookmarkKnownMainCount,
            int highBookmarkMainCount,
            String underTheHoodUrl,
            EmpiricalAlgoCalibration calibration,
            XAlgorithmProfile algo
    ) {
        XAlgorithmProfile.UpstreamEvidenceSpec ev = algo.upstreamEvidence();
        XAlgorithmProfile.OpsPlaybookRules playbook = algo.opsPlaybook();
        XAlgorithmProfile.CadenceAndReachRules rules = algo.cadenceAndReach();
        List<OpsRecommendationItem> items = new ArrayList<>();

        int unrepliedPeers = interactions != null ? interactions.unrepliedMyPostPeers() : 0;
        if (unrepliedPeers > 0) {
            items.add(new OpsRecommendationItem(
                    "AUTHOR_THREAD_REPLY",
                    "HIGH",
                    "互动",
                    String.format("回复 %d 条待回的主推评论", unrepliedPeers),
                    String.format("%s内有 %d 位推友评论了你的推文尚未回复；互关好友评论的推荐权重约为普通评论的 %.0f 倍，及时接话能直接带动二次推荐。",
                            periodLabel, unrepliedPeers, Math.max(1.0, ev.effectiveReplyWeight(false, false, true) / Math.max(1.0, ev.replyWeight()))),
                    "peerFilter:UNREPLIED_POST",
                    null
            ));
        }

        int iOwePeers = interactions != null ? interactions.inboundHeavyPeers() : 0;
        if (iOwePeers > 0) {
            List<String> sampleHandles = interactions.peerStats().stream()
                    .filter(p -> "I_OWE".equals(p.reciprocityCode()) && p.peer() != null && p.peer().username() != null)
                    .sorted(Comparator.comparingInt(PeerInteractionStat::unrepliedInMyPostCount).reversed()
                            .thenComparing(Comparator.comparingInt(PeerInteractionStat::peerRepliesToMe).reversed()))
                    .limit(2)
                    .map(p -> "@" + p.peer().username())
                    .toList();
            String peerHint = sampleHandles.isEmpty() ? "" : "（如 " + String.join("、", sampleHandles) + "）";
            items.add(new OpsRecommendationItem(
                    "WARMUP_CORE_PEERS",
                    "HIGH",
                    "回访",
                    String.format("回访 %d 位常来互动的推友", iOwePeers),
                    String.format("这些推友%s近期常来给你评论，去对方最新推文下互动有助于保持双方在时间线里的优先展示。", peerHint),
                    "peerFilter:I_OWE",
                    null
            ));
        }

        if (cliffDrop) {
            items.add(new OpsRecommendationItem(
                    "EXPOSURE_DROP_CHECK",
                    "HIGH",
                    "自检",
                    "近期推文浏览明显偏低",
                    "近 7 天推文浏览量较此前明显回落，可对比近期题材节奏并前往 X 官方状态页排查。",
                    null,
                    underTheHoodUrl
            ));
        }

        if (controversyCount > 0) {
            items.add(new OpsRecommendationItem(
                    "CONTROVERSY_GUARD",
                    "MEDIUM",
                    "风控",
                    String.format("留意 %d 条评多赞少的争议推文", controversyCount),
                    "评论远多于点赞收藏时，容易伴随「不感兴趣 / 屏蔽 / 举报」等重度减分反馈，建议减少易引发对立的表述。",
                    "tab:timing|scrollId:topTweetsList",
                    null
            ));
        }

        if (linkComparisonAvailable && linkLossPct >= rules.linkLossNoticePct()) {
            items.add(new OpsRecommendationItem(
                    "EXT_LINK_SELF_REPLY",
                    "MEDIUM",
                    "外链",
                    String.format("带外链推文均浏览偏低 %d%%", linkLossPct),
                    "外链跳出过快会拉低帖子停留时长，可尝试「正文发核心观点 + 第一条自评放链接」对比效果。",
                    "tab:timing|focusDraft:1",
                    null
            ));
        } else if (extLinkCount > 0) {
            items.add(new OpsRecommendationItem(
                    "EXT_LINK_SELF_REPLY",
                    "NORMAL",
                    "外链",
                    String.format("有 %d 条推文含外链 · 避免短链与陌生 @", extLinkCount),
                    "普通外链本身不扣分，但请勿使用第三方跳转短链，也不要在同一条推文里同时放外链并 @未关注你的人。",
                    "tab:timing|focusDraft:1",
                    null
            ));
        }

        if (rapidFireCount > 0) {
            items.add(new OpsRecommendationItem(
                    "CADENCE_SPACING",
                    "MEDIUM",
                    "节奏",
                    String.format("%d 条推文发布间隔不足 %s", rapidFireCount, gapLabel),
                    String.format("短时间连续发帖会让多条推文撞在同一批刷新里，第 2 条推荐权重会折损至约 %.0f%%，建议用定时队列拉开间隔。",
                            ev.authorDiversityMultiplier(1) * 100.0),
                    "tab:schedule",
                    null
            ));
        }

        if (contentTiming != null && contentTiming.formatStats() != null) {
            List<ContentFormatStat> knownFormats = contentTiming.formatStats().stream()
                    .filter(f -> f.viewSampleCount() > 0 && f.avgViews() > 0)
                    .sorted(Comparator.comparingDouble(ContentFormatStat::avgAlgoScore)
                            .thenComparingLong(ContentFormatStat::avgViews).reversed())
                    .toList();
            long overallAvgViews = activity != null ? activity.avgViewsPerPost() : 0L;
            if (knownFormats.size() >= 2 && overallAvgViews > 0L) {
                ContentFormatStat best = knownFormats.get(0);
                int liftPct = (int) Math.round((best.avgViews() - overallAvgViews) * 100.0 / overallAvgViews);
                boolean clearAlgoLead = best.algoScoreSampleCount() > 0
                        && knownFormats.get(1).algoScoreSampleCount() > 0
                        && (best.avgAlgoScore() - knownFormats.get(1).avgAlgoScore()) >= 8.0
                        && liftPct >= 0;
                if (liftPct >= playbook.formatAdvantageThresholdPct() || clearAlgoLead) {
                    String headline = liftPct >= playbook.formatAdvantageThresholdPct()
                            ? String.format("「%s」均浏览高出 %d%%", best.formatLabel(), liftPct)
                            : String.format("「%s」互动表现最佳", best.formatLabel());
                    items.add(new OpsRecommendationItem(
                            "WINNING_FORMAT",
                            "MEDIUM",
                            "体裁",
                            headline,
                            String.format("近期 %d 条「%s」平均浏览 %d，明显优于其他体裁，同类话题可优先沿用该形式。",
                                    best.viewSampleCount(), best.formatLabel(), best.avgViews()),
                            "tab:timing|scrollId:formatCardsGrid",
                            null
                    ));
                }
            } else {
                int textCount = contentTiming.formatStats().stream()
                        .filter(f -> "TEXT".equals(f.formatCode()))
                        .mapToInt(ContentFormatStat::postCount)
                        .sum();
                int richCount = contentTiming.formatStats().stream()
                        .filter(f -> !"TEXT".equals(f.formatCode()))
                        .mapToInt(ContentFormatStat::postCount)
                        .sum();
                if (textCount >= 3 && richCount == 0) {
                    items.add(new OpsRecommendationItem(
                            "WINNING_FORMAT",
                            "NORMAL",
                            "体裁",
                            "近期全为纯文字 · 可尝试配图或长推",
                            String.format("已连续发布 %d 条纯文字推文，配图或 %d 秒以上视频能带来额外的点击展开与停留。",
                                    textCount, ev.minVideoDurationMs() / 1000),
                            "tab:timing|focusDraft:1",
                            null
                    ));
                }
            }
        }

        if (bookmarkKnownMainCount >= 3 && (highBookmarkMainCount == 0 || highBookmarkMainCount * 4 < bookmarkKnownMainCount)) {
            String bmHeadline = highBookmarkMainCount == 0
                    ? "干货收藏偏少 · 多用清单或步骤拆解"
                    : String.format("高收藏推文占 %d/%d · 增加可保存的实用干货", highBookmarkMainCount, bookmarkKnownMainCount);
            items.add(new OpsRecommendationItem(
                    "BOOKMARK_DWELL_STRUCTURE",
                    "MEDIUM",
                    "深度",
                    bmHeadline,
                    "复制链接分享和私信转发在推荐中占最高权重，把经验整理成清晰的步骤、清单或对比图更容易引发保存与转发。",
                    "tab:timing|focusDraft:1",
                    null
            ));
        }

        if (evalCount >= 3) {
            int oonPct = (int) Math.round(oonCount * 100.0 / evalCount);
            if (oonCount == 0 || oonPct < 20) {
                String oonHeadline = oonCount == 0
                        ? "破圈推文偏少 · 增加可引发引用转发的观点"
                        : String.format("破圈推文占 %d%% · 强化引用与分享动机", oonPct);
                items.add(new OpsRecommendationItem(
                        "OON_QUOTE_REPOST_HOOK",
                        "MEDIUM",
                        "破圈",
                        oonHeadline,
                        String.format("近 %d 条主推中有 %d 条浏览超过粉丝数；只有原创和引用推文能进入陌生人推荐流，鲜明结论更容易带动引用转发。",
                                evalCount, oonCount),
                        "tab:timing|focusDraft:1",
                        null
                ));
            }
        }

        if (mainPostCount >= 3 && authorEngagedPostCount == 0) {
            items.add(new OpsRecommendationItem(
                    "AUTHOR_REPLY_DESIGN",
                    "MEDIUM",
                    "评论区",
                    "发帖后在自家评论区补充互动",
                    String.format("近 %s %d 条主推下均未见你的跟进回复；发推后在评论区补充背景或回答读者提问，能显著抬高帖子热度。",
                            periodLabel, mainPostCount),
                    "tab:timing|focusDraft:1",
                    null
            ));
        }

        if (activity != null) {
            int authored = activity.originalPosts() + activity.quotes();
            if (authored == 0) {
                items.add(new OpsRecommendationItem(
                        "CONTENT_PACE",
                        "NORMAL",
                        "发帖",
                        "本周期尚无原创或引用推文",
                        "纯回复和纯转推不会推荐给陌生人，发一条独立原创或引用点评才能获得新读者曝光。",
                        "tab:timing|focusDraft:1",
                        null
                ));
            } else if (activity.replyToPostRatio() < playbook.targetReplyToPostRatioMin()) {
                items.add(new OpsRecommendationItem(
                        "CONTENT_PACE",
                        "NORMAL",
                        "互动",
                        String.format("回复/发帖比 %.1fx 偏低 · 多参与同行讨论", activity.replyToPostRatio()),
                        String.format("日常回复与发帖比保持在 %.0f–%.0f 倍更稳健，常去同领域推友评论区交流能提升你的主页回访。",
                                playbook.targetReplyToPostRatioMin(), playbook.targetReplyToPostRatioMax()),
                        "tab:interactions",
                        null
                ));
            } else if (activity.replyToPostRatio() > playbook.overReplyLowPostRatio() && authored <= 2) {
                items.add(new OpsRecommendationItem(
                        "CONTENT_PACE",
                        "NORMAL",
                        "发帖",
                        "回复很多但主推偏少 · 把好回复整理成主推",
                        "别人的评论区无法帮你触达陌生受众，把反响好的长回复提炼成独立推文能带来更多新关注。",
                        "tab:timing|focusDraft:1",
                        null
                ));
            }
        }

        if (followAudit != null && followAudit.fansNotFollowedBackCount() > 0) {
            long loyalCommenters = followAudit.fansNotFollowedBackList() != null
                    ? followAudit.fansNotFollowedBackList().stream().filter(f -> "LOYAL_FAN".equals(f.auditCategory())).count()
                    : 0L;
            String title = loyalCommenters > 0
                    ? String.format("%d 位常评粉丝待回关（共 %d 位）", loyalCommenters, followAudit.fansNotFollowedBackCount())
                    : String.format("%d 位粉丝待回关", followAudit.fansNotFollowedBackCount());
            items.add(new OpsRecommendationItem(
                    "LOYAL_FAN_FOLLOWBACK",
                    "NORMAL",
                    "互关",
                    title,
                    String.format("回关常来互动的粉丝后，对方评论给你的推文带来的推荐加成会提升至原来的 %.0f 倍。",
                            Math.max(1.0, ev.effectiveReplyWeight(false, false, true) / Math.max(1.0, ev.replyWeight()))),
                    "followTab:FAN_ONLY",
                    null
            ));
        }

        if (calibration != null && calibration.replyTemplateRisk().copyPastaRiskTriggered()) {
            ReplyTemplateRiskSummary rtr = calibration.replyTemplateRisk();
            items.add(new OpsRecommendationItem(
                    "REPLY_TEMPLATE_DEDUP",
                    "HIGH",
                    "防刷屏",
                    String.format("检测到 %d 条高度相似的回复话术", rtr.maxClusterSize()),
                    String.format("近期有 %d/%d 条回复文字几乎一致（如「%s」），重复套用固定话术容易被识别为刷屏并折叠评论。",
                            rtr.duplicateReplyCount(), rtr.evaluatedReplyCount(), rtr.sampleTemplateText()),
                    "tab:interactions",
                    null
            ));
        }

        if (calibration != null) {
            for (ResidualAnomalyPost post : calibration.residualAnomalies()) {
                items.add(new OpsRecommendationItem(
                        "REVIEW_LOW_VIEW_POST",
                        "LOW",
                        "复盘",
                        "浏览量低于历史常态",
                        String.format("%s「%s」；已采集浏览 %d，历史单帖中位数 %d。",
                                post.createdLabel(), post.textPreview(), post.actualViews(), calibration.medianViews()),
                        null,
                        post.tweetUrl()
                ));
            }
        }

        if (activity != null && !activity.sampleCapped() && followAudit != null && followAudit.suggestedUnfollowCount() > 0) {
            items.add(new OpsRecommendationItem(
                    "CLEAN_LOW_SIGNAL_FOLLOWS",
                    "NORMAL",
                    "关注清理",
                    String.format("%d 个长期无互动未回关账号可清理", followAudit.suggestedUnfollowCount()),
                    "适度清理长期单向且零互动的关注账号，有助于保持账号关系健康与时间线清爽。",
                    "followTab:NOT_FOLLOWING_BACK|followCat:SUGGEST_CLEAN",
                    null
            ));
        }

        int cap = Math.max(1, playbook.maxRecommendations());
        return items.size() <= cap ? List.copyOf(items) : List.copyOf(items.subList(0, cap));
    }

    private static final int MIN_CALIBRATION_POSTS = 6;
    private static final double CALIBRATION_PRIOR_VIEWS = 100.0;
    private static final long MIN_REVIEW_OBSERVATION_MINUTES = 180L;
    private static final Set<String> KNOWN_URL_SHORTENERS = Set.of(
            "bit.ly", "tinyurl.com", "t.cn", "is.gd", "cutt.ly", "buff.ly",
            "ow.ly", "rebrand.ly", "shorturl.at", "rb.gy", "soo.gd"
    );
    private static final Pattern LIST_STRUCTURE_RE = Pattern.compile(
            "(^|\\n)\\s*(?:[-•*✅💡⚠️🔹▶️]|\\d+[\\.、\\)）])\\s+", Pattern.MULTILINE
    );
    private static final Pattern MENTION_STRIP_RE = Pattern.compile("(^|[^\\w])@[A-Za-z0-9_]{1,15}");
    private static final Pattern ASCII_TOKEN_RE = Pattern.compile("[a-z0-9_]{2,}");
    private static final Pattern URL_HOST_RE = Pattern.compile("^https?://([^/\\s:?#]+)", Pattern.CASE_INSENSITIVE);

    private EmpiricalAlgoCalibration buildEmpiricalCalibration(
            XUserSummary profile,
            List<RawTweet> allTimeline,
            List<RawTweet> windowMain,
            Map<String, Long> mainPostGapMinutesById,
            Set<String> authorEngagedMainPostIds,
            InteractionOverview interactions,
            FollowAuditOverview followAudit,
            ContentTimingOverview contentTiming,
            Instant refNow,
            ZoneId zoneId,
            XAlgorithmProfile algo
    ) {
        XAlgorithmProfile.UpstreamEvidenceSpec ev = algo.upstreamEvidence();
        XAlgorithmProfile.CadenceAndReachRules rules = algo.cadenceAndReach();
        int followers = Math.max(0, profile.followersCount());
        String username = profile.username() != null ? profile.username() : "x";

        // 1. 反推本账号互关评论占比与真实期望有效 reply 权重（xai-value-model/weights.rs:603）
        long totalInboundReplies = 0L;
        long mutualInboundReplies = 0L;
        if (interactions != null && interactions.peerStats() != null) {
            for (PeerInteractionStat p : interactions.peerStats()) {
                int r = Math.max(0, p.peerRepliesToMe());
                if (r > 0) {
                    totalInboundReplies += r;
                    if (p.iFollowThem() && p.theyFollowMe()) {
                        mutualInboundReplies += r;
                    }
                }
            }
        }
        boolean mutualShareKnown = totalInboundReplies >= 3L;
        double mutualShareRatio = 0.0;
        if (mutualShareKnown) {
            mutualShareRatio = (double) mutualInboundReplies / totalInboundReplies;
        } else if (followAudit != null && followAudit.followersSampleCount() >= 5 && !followAudit.followersSampleTruncated()) {
            mutualShareRatio = Math.min(1.0, (double) followAudit.mutualCount() / followAudit.followersSampleCount());
            mutualShareKnown = true;
        }
        double effectiveReplyWeight = round2(ev.replyWeight() + ev.bidirectionalFollowReplyWeightBoost() * mutualShareRatio);
        double mutualSharePct = round1(mutualShareRatio * 100.0);

        // 2. 检测评论区模板复读风险（Scarecrow BBQDuplicateTextRepliesProd）
        ReplyTemplateRiskSummary replyRisk = evaluateReplyTemplateRisk(allTimeline, refNow);

        // 3. 收集历史有效独立主推样本（公开指标完整且 views > 0）
        List<RawTweet> validMain = allTimeline.stream()
                .filter(t -> isIndependentMainPost(t)
                        && t.createdAt() != null
                        && !t.createdAt().isAfter(refNow)
                        && rateKnown(t))
                .sorted(Comparator.comparing(RawTweet::createdAt))
                .toList();

        // 3. 外链域名实测表现账本（DomainReachStat）
        List<DomainReachStat> domainStats = buildDomainReachStats(validMain);

        boolean coldStartEligible = ev.coldStartEnabled() && followers <= ev.coldStartFollowerCap();
        if (validMain.isEmpty()) {
            return new EmpiricalAlgoCalibration(
                    0, MIN_CALIBRATION_POSTS, false,
                    mutualShareKnown, mutualSharePct, effectiveReplyWeight,
                    0L, 0L, 0L, 0.0, 0.0,
                    coldStartEligible, 0, 0.0,
                    0.0, 0.0, 16,
                    List.of(), domainStats, replyRisk, List.of()
            );
        }

        // 5. 计算全样本先验动作率（Empirical Bayes Beta-Binomial 平滑，priorViews = 100）
        long sumViews = 0L;
        long sumReplies = 0L;
        long sumReposts = 0L;
        long sumLikes = 0L;
        int coldStartPassCount = 0;
        for (RawTweet t : validMain) {
            long v = Math.max(1L, t.viewCount());
            sumViews += v;
            sumReplies += Math.max(0, t.replyCount());
            sumReposts += Math.max(0, t.retweetCount());
            sumLikes += Math.max(0, t.likeCount());
            // home-mixer/scorers/author_cold_start.rs: Beta(0.75, 49.25) -> 先验均值 1.5% fav/views
            if ((double) Math.max(0, t.likeCount()) / v >= 0.015) {
                coldStartPassCount++;
            }
        }
        double priorReplyRate = sumViews > 0 ? (double) sumReplies / sumViews : 0.005;
        double priorRepostRate = sumViews > 0 ? (double) sumReposts / sumViews : 0.003;
        double priorLikeRate = sumViews > 0 ? (double) sumLikes / sumViews : 0.015;
        double priorViewsStrength = CALIBRATION_PRIOR_VIEWS;
        // 公开 API repost 计数包含纯转推(1.0)与部分引用(5.0)，采用无夸大的保守合成系数
        double compositeRepostVmWeight = 0.75 * ev.retweetWeight() + 0.25 * ev.quoteWeight();

        int n = validMain.size();
        double[] utilities = new double[n];
        double[] observedUtilities = new double[n];
        long[] viewsArr = new long[n];
        for (int i = 0; i < n; i++) {
            RawTweet t = validMain.get(i);
            long v = Math.max(1L, t.viewCount());
            viewsArr[i] = v;
            double pReply = (Math.max(0, t.replyCount()) + priorViewsStrength * priorReplyRate) / (v + priorViewsStrength);
            double pRepost = (Math.max(0, t.retweetCount()) + priorViewsStrength * priorRepostRate) / (v + priorViewsStrength);
            double pLike = (Math.max(0, t.likeCount()) + priorViewsStrength * priorLikeRate) / (v + priorViewsStrength);
            double utilPer1k = 1000.0 * (effectiveReplyWeight * pReply
                    + compositeRepostVmWeight * pRepost
                    + ev.favoriteWeight() * pLike);
            utilities[i] = round2(utilPer1k);
            observedUtilities[i] = 1000.0 * (
                    effectiveReplyWeight * Math.max(0, t.replyCount())
                    + compositeRepostVmWeight * Math.max(0, t.retweetCount())
                    + ev.favoriteWeight() * Math.max(0, t.likeCount())) / v;
        }

        // 计算分位统计量与每条样本的综合历史分位（0..100）
        long[] sortedViews = viewsArr.clone();
        java.util.Arrays.sort(sortedViews);
        long p25Views = quantileLong(sortedViews, 0.25);
        long medianViews = quantileLong(sortedViews, 0.50);
        long p75Views = quantileLong(sortedViews, 0.75);
        double medianReachMult = followers > 0 ? round2((double) medianViews / followers) : 0.0;

        double[] sortedUtil = utilities.clone();
        java.util.Arrays.sort(sortedUtil);
        double medianUtil = quantileDouble(sortedUtil, 0.50);

        int[] rankPercentiles = computeCompositePercentiles(viewsArr, utilities);

        // 构造校准样本数组（按时间升序构造，随后取最新 40 条供前端实时核回归）
        List<CalibrationPostSample> allSamples = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            RawTweet t = validMain.get(i);
            String rawText = t.text() != null ? t.text() : "";
            String cleanText = URL_PATTERN.matcher(rawText).replaceAll(" ").trim();
            int cleanLen = cleanText.codePointCount(0, cleanText.length());
            boolean hasExt = hasExternalUrl(t);
            String domain = hasExt ? extractPrimaryExternalDomain(t) : "";
            boolean structuredList = isStructuredListText(rawText);
            boolean hasQuestion = rawText.indexOf('?') >= 0 || rawText.indexOf('？') >= 0;
            boolean engaged = t.id() != null && authorEngagedMainPostIds.contains(t.id());
            ZonedDateTime zdt = t.createdAt().atZone(zoneId);
            String fmtCode = t.isThread() ? "THREAD"
                    : (t.hasMedia() ? "MEDIA" : (t.kind() == TweetKind.QUOTE ? "QUOTE" : "TEXT"));
            double mult = followers > 0 ? round2((double) viewsArr[i] / followers) : 0.0;
            List<String> shingles = extractCalibrationShingles(cleanText);
            String preview = buildShortPreview(cleanText.isEmpty() ? rawText : cleanText, 48);
            allSamples.add(new CalibrationPostSample(
                    t.id(),
                    zdt.format(POST_TIME_FMT),
                    fmtCode,
                    cleanLen,
                    structuredList,
                    hasQuestion,
                    hasExt,
                    domain,
                    engaged,
                    viewsArr[i],
                    mult,
                    utilities[i],
                    rankPercentiles[i],
                    shingles,
                    preview,
                    "https://x.com/" + username + "/status/" + t.id()
            ));
        }

        // 6. 留一交叉验证（LOOCV）回测模型在该账号上的真实样本外误差与区间命中率
        boolean unlocked = n >= MIN_CALIBRATION_POSTS;
        double loocvMae = 0.0;
        double loocvHitRate = 0.0;
        int avgHalfBand = 16;
        if (unlocked) {
            double absErrSum = 0.0;
            int hitCount = 0;
            int bandSum = 0;
            for (int i = 0; i < n; i++) {
                KernelPred pred = predictLeaveOneOut(allSamples, i);
                absErrSum += Math.abs(pred.predictedPercentile - allSamples.get(i).rankPercentile());
                bandSum += pred.halfBand;
                if (allSamples.get(i).rankPercentile() >= pred.predictedPercentile - pred.halfBand
                        && allSamples.get(i).rankPercentile() <= pred.predictedPercentile + pred.halfBand) {
                    hitCount++;
                }
            }
            loocvMae = round1(absErrSum / n);
            loocvHitRate = round1(hitCount * 100.0 / n);
            avgHalfBand = Math.max(10, Math.min(22, (int) Math.round((double) bandSum / n)));
        }

        // 历史样本继续作基线；只将当前统计周期内、已有足够观测的主推列为复盘线索。
        Set<String> currentPeriodIds = new HashSet<>();
        for (RawTweet post : windowMain) {
            if (post.id() != null) currentPeriodIds.add(post.id());
        }
        List<ResidualAnomalyPost> anomalies = unlocked
                ? detectResidualAnomalies(validMain, allSamples, observedUtilities, currentPeriodIds,
                        mainPostGapMinutesById, rules.minRecommendedGapMinutes(), refNow, medianUtil, p25Views, medianViews)
                : List.of();

        List<CalibrationPostSample> exportedCorpus = n <= 40
                ? allSamples
                : allSamples.subList(n - 40, n);
        double coldPassRate = round1(coldStartPassCount * 100.0 / n);

        return new EmpiricalAlgoCalibration(
                n,
                MIN_CALIBRATION_POSTS,
                unlocked,
                mutualShareKnown,
                mutualSharePct,
                effectiveReplyWeight,
                p25Views,
                medianViews,
                p75Views,
                medianReachMult,
                medianUtil,
                coldStartEligible,
                coldStartPassCount,
                coldPassRate,
                loocvMae,
                loocvHitRate,
                avgHalfBand,
                anomalies,
                domainStats,
                replyRisk,
                exportedCorpus
        );
    }

    private record KernelPred(double predictedPercentile, int halfBand) {
    }

    private static KernelPred predictLeaveOneOut(List<CalibrationPostSample> samples, int holdoutIdx) {
        CalibrationPostSample q = samples.get(holdoutIdx);
        Set<String> qSet = new HashSet<>(q.shingles());
        double priorWeight = 1.6;
        double wSum = priorWeight;
        double wySum = priorWeight * 50.0;

        int n = samples.size();
        double[] weights = new double[n];
        for (int j = 0; j < n; j++) {
            if (j == holdoutIdx) continue;
            CalibrationPostSample s = samples.get(j);
            double jaccard = computeShingleJaccard(qSet, s.shingles());
            double fmtMatch = q.formatCode().equals(s.formatCode()) ? 1.0 : 0.0;
            double extMatch = (q.hasExternalLink() == s.hasExternalLink()) ? 1.0 : 0.0;
            double domainMatch = (q.hasExternalLink() && !q.externalDomain().isEmpty()
                    && q.externalDomain().equals(s.externalDomain())) ? 1.0 : 0.0;
            double listMatch = (q.structuredList() == s.structuredList()) ? 1.0 : 0.0;
            double qMatch = (q.hasQuestion() == s.hasQuestion()) ? 1.0 : 0.0;
            double lenDiff = Math.abs(Math.log1p(q.cleanCharLen()) - Math.log1p(s.cleanCharLen()));

            double z = 2.6 * jaccard
                    + 0.85 * fmtMatch
                    + 0.35 * extMatch
                    + 0.55 * domainMatch
                    + 0.30 * listMatch
                    + 0.25 * qMatch
                    - 0.30 * Math.min(2.5, lenDiff);
            double w = Math.max(0.05, Math.exp(z) - 0.85);
            weights[j] = w;
            wSum += w;
            wySum += w * s.rankPercentile();
        }
        double mean = wySum / wSum;
        double varNum = priorWeight * Math.pow(50.0 - mean, 2);
        for (int j = 0; j < n; j++) {
            if (j == holdoutIdx) continue;
            varNum += weights[j] * Math.pow(samples.get(j).rankPercentile() - mean, 2);
        }
        double std = Math.sqrt(varNum / wSum);
        int halfBand = Math.max(10, Math.min(22, (int) Math.round(std * 0.9)));
        return new KernelPred(mean, halfBand);
    }

    private static List<ResidualAnomalyPost> detectResidualAnomalies(
            List<RawTweet> validMain,
            List<CalibrationPostSample> samples,
            double[] observedUtilities,
            Set<String> currentPeriodIds,
            Map<String, Long> gapMap,
            long minGapMinutes,
            Instant refNow,
            double medianUtil,
            long p25Views,
            long medianViews
    ) {
        // 仅在非连发样本上拟合 OLS: log10(views + 10) = a + b * log10(util + 1)
        List<Integer> cleanIdx = new ArrayList<>();
        for (int i = 0; i < validMain.size(); i++) {
            RawTweet t = validMain.get(i);
            Long gap = gapMap.get(t.id());
            if (gap == null || gap >= minGapMinutes) {
                cleanIdx.add(i);
            }
        }
        if (cleanIdx.size() < MIN_CALIBRATION_POSTS) {
            return List.of();
        }
        double sumX = 0.0;
        double sumY = 0.0;
        for (int idx : cleanIdx) {
            sumX += Math.log10(samples.get(idx).realizedVmUtilityPer1k() + 1.0);
            sumY += Math.log10(samples.get(idx).viewCount() + 10.0);
        }
        double meanX = sumX / cleanIdx.size();
        double meanY = sumY / cleanIdx.size();
        double sxx = 0.0;
        double sxy = 0.0;
        for (int idx : cleanIdx) {
            double dx = Math.log10(samples.get(idx).realizedVmUtilityPer1k() + 1.0) - meanX;
            double dy = Math.log10(samples.get(idx).viewCount() + 10.0) - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
        }
        double slope = sxx > 1e-6 ? Math.max(0.0, Math.min(2.5, sxy / sxx)) : 0.0;
        double intercept = meanY - slope * meanX;

        Instant observedSince = refNow.minus(Duration.ofMinutes(
                Math.max(MIN_REVIEW_OBSERVATION_MINUTES, minGapMinutes)));
        List<ResidualAnomalyPost> found = new ArrayList<>();
        for (int idx : cleanIdx) {
            CalibrationPostSample s = samples.get(idx);
            RawTweet post = validMain.get(idx);
            if (!currentPeriodIds.contains(s.id()) || post.createdAt().isAfter(observedSince)
                    || s.viewCount() < CALIBRATION_PRIOR_VIEWS || s.viewCount() >= medianViews
                    || observedUtilities[idx] < Math.max(1.0, medianUtil)
                    || s.realizedVmUtilityPer1k() < Math.max(1.0, medianUtil)) {
                continue;
            }
            double expectedLogV = intercept + slope * Math.log10(s.realizedVmUtilityPer1k() + 1.0);
            double expectedV = Math.max(20.0, Math.pow(10.0, expectedLogV) - 10.0);
            // Fixed comparison threshold for candidate selection, not a confidence interval.
            long expectedFloor = Math.round(expectedV * 0.45);
            if (expectedFloor >= 30L && s.viewCount() < expectedFloor && s.viewCount() <= p25Views) {
                found.add(new ResidualAnomalyPost(
                        s.id(),
                        s.createdLabel(),
                        s.textPreview(),
                        s.viewCount(),
                        expectedFloor,
                        s.realizedVmUtilityPer1k(),
                        s.tweetUrl(),
                        String.format("已采集浏览 %d，历史单帖中位数 %d。", s.viewCount(), medianViews)
                ));
            }
        }
        found.sort(Comparator.comparingDouble((ResidualAnomalyPost a) -> (double) a.actualViews() / Math.max(1L, a.expectedViewsFloor())));
        return found.size() <= 3 ? List.copyOf(found) : List.copyOf(found.subList(0, 3));
    }

    private static List<DomainReachStat> buildDomainReachStats(List<RawTweet> validMain) {
        long nativeSum = 0L;
        int nativeCnt = 0;
        Map<String, List<Long>> byDomain = new LinkedHashMap<>();
        for (RawTweet t : validMain) {
            long v = Math.max(0L, t.viewCount());
            if (hasExternalUrl(t)) {
                String domain = extractPrimaryExternalDomain(t);
                if (!domain.isBlank()) {
                    byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(v);
                }
            } else {
                nativeSum += v;
                nativeCnt++;
            }
        }
        if (byDomain.isEmpty()) {
            return List.of();
        }
        long nativeAvg = nativeCnt > 0 ? Math.max(1L, Math.round((double) nativeSum / nativeCnt)) : 0L;
        List<DomainReachStat> out = new ArrayList<>();
        for (Map.Entry<String, List<Long>> e : byDomain.entrySet()) {
            String domain = e.getKey();
            List<Long> vs = e.getValue();
            long avg = Math.round(vs.stream().mapToLong(Long::longValue).average().orElse(0.0));
            int vsNativePct = nativeAvg > 0 ? (int) Math.round((avg - nativeAvg) * 100.0 / nativeAvg) : 0;
            boolean shortener = KNOWN_URL_SHORTENERS.contains(domain);
            out.add(new DomainReachStat(domain, vs.size(), avg, vsNativePct, shortener));
        }
        out.sort(Comparator.comparingInt(DomainReachStat::postCount).reversed()
                .thenComparingLong(DomainReachStat::avgViews));
        return out.size() <= 8 ? List.copyOf(out) : List.copyOf(out.subList(0, 8));
    }

    private static ReplyTemplateRiskSummary evaluateReplyTemplateRisk(List<RawTweet> allTimeline, Instant refNow) {
        Map<String, List<String>> clusters = new LinkedHashMap<>();
        int evaluated = 0;
        for (RawTweet t : allTimeline) {
            if (t.kind() != TweetKind.REPLY || t.createdAt() == null || t.createdAt().isAfter(refNow)) {
                continue;
            }
            String raw = t.text() != null ? t.text() : "";
            String stripped = URL_PATTERN.matcher(raw).replaceAll(" ");
            stripped = MENTION_STRIP_RE.matcher(stripped).replaceAll(" ").trim();
            String norm = stripped.toLowerCase(Locale.ROOT)
                    .replaceAll("[\\s\\p{P}\\p{S}]+", "");
            if (norm.codePointCount(0, norm.length()) < 4) {
                continue;
            }
            evaluated++;
            if (norm.codePointCount(0, norm.length()) < 8 || norm.codePoints().distinct().count() < 4) {
                continue;
            }
            clusters.computeIfAbsent(norm, k -> new ArrayList<>()).add(stripped);
        }
        int dupTotal = 0;
        int maxCluster = 0;
        String sampleText = "";
        for (List<String> list : clusters.values()) {
            if (list.size() >= 2) {
                dupTotal += list.size();
            }
            if (list.size() > maxCluster) {
                maxCluster = list.size();
                sampleText = buildShortPreview(list.get(0), 36);
            }
        }
        boolean risk = maxCluster >= 3;
        return new ReplyTemplateRiskSummary(evaluated, dupTotal, maxCluster, risk, risk || maxCluster >= 2 ? sampleText : "");
    }

    private static int[] computeCompositePercentiles(long[] views, double[] utilities) {
        int n = views.length;
        int[] out = new int[n];
        if (n == 1) {
            out[0] = 50;
            return out;
        }
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double vRank = empiricalPercentileRank(views[i], views);
            double uRank = empiricalPercentileRankDouble(utilities[i], utilities);
            raw[i] = 0.55 * vRank + 0.45 * uRank;
        }
        for (int i = 0; i < n; i++) {
            out[i] = (int) Math.round(empiricalPercentileRankDouble(raw[i], raw) * 100.0);
        }
        return out;
    }

    private static double empiricalPercentileRank(long val, long[] arr) {
        int less = 0;
        int eq = 0;
        for (long v : arr) {
            if (v < val) less++;
            else if (v == val) eq++;
        }
        return (less + 0.5 * Math.max(0, eq - 1)) / Math.max(1, arr.length - 1);
    }

    private static double empiricalPercentileRankDouble(double val, double[] arr) {
        int less = 0;
        int eq = 0;
        for (double v : arr) {
            if (v < val - 1e-9) less++;
            else if (Math.abs(v - val) <= 1e-9) eq++;
        }
        return (less + 0.5 * Math.max(0, eq - 1)) / Math.max(1, arr.length - 1);
    }

    private static long quantileLong(long[] sorted, double q) {
        if (sorted.length == 0) return 0L;
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        return Math.round(sorted[lo] * (hi - pos) + sorted[hi] * (pos - lo));
    }

    private static double quantileDouble(double[] sorted, double q) {
        if (sorted.length == 0) return 0.0;
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        return round2(sorted[lo] * (hi - pos) + sorted[hi] * (pos - lo));
    }

    private static String extractPrimaryExternalDomain(RawTweet t) {
        String text = t.text() != null ? t.text() : "";
        Matcher m = URL_PATTERN.matcher(text);
        boolean sawTco = false;
        while (m.find()) {
            String rawUrl = m.group();
            Matcher hm = URL_HOST_RE.matcher(rawUrl);
            if (!hm.find()) continue;
            String host = hm.group(1).toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.startsWith("mobile.")) host = host.substring(7);
            if ("x.com".equals(host) || "twitter.com".equals(host)) {
                continue;
            }
            if ("t.co".equals(host)) {
                sawTco = true;
                continue;
            }
            return host;
        }
        return sawTco ? "t.co" : "";
    }

    private static boolean isStructuredListText(String rawText) {
        if (rawText == null || rawText.isBlank()) return false;
        long nonEmptyLines = rawText.lines().map(String::trim).filter(s -> !s.isEmpty()).count();
        return nonEmptyLines >= 3 || LIST_STRUCTURE_RE.matcher(rawText).find();
    }

    static List<String> extractCalibrationShingles(String cleanText) {
        if (cleanText == null || cleanText.isBlank()) {
            return List.of();
        }
        String norm = cleanText.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher wm = ASCII_TOKEN_RE.matcher(norm);
        while (wm.find() && set.size() < 64) {
            set.add("w:" + wm.group());
        }
        String compact = norm.replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        int[] cps = compact.codePoints().toArray();
        if (cps.length == 1 && set.size() < 64) {
            set.add("c:" + new String(cps, 0, 1));
        } else {
            for (int i = 0; i < cps.length - 1 && set.size() < 64; i++) {
                if (isCjkCodePoint(cps[i]) || isCjkCodePoint(cps[i + 1])) {
                    set.add("g:" + new String(cps, i, 2));
                }
            }
        }
        return List.copyOf(set);
    }

    private static boolean isCjkCodePoint(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    private static double computeShingleJaccard(Set<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String tok : b) {
            if (a.contains(tok)) inter++;
        }
        int union = a.size() + b.size() - inter;
        return union > 0 ? (double) inter / union : 0.0;
    }

    private static String buildShortPreview(String text, int maxCodePoints) {
        String singleLine = (text == null ? "" : text).replaceAll("\\s+", " ").trim();
        if (singleLine.codePointCount(0, singleLine.length()) <= maxCodePoints) {
            return singleLine;
        }
        int endIdx = singleLine.offsetByCodePoints(0, maxCodePoints);
        return singleLine.substring(0, endIdx) + "…";
    }

    private static Boolean isAccountOverOneYearOld(String joinedDate, Instant refNow, ZoneId zoneId) {
        if (joinedDate == null || joinedDate.isBlank()) {
            return null;
        }
        LocalDate joined;
        String value = joinedDate.trim();
        try {
            joined = Instant.parse(value).atZone(zoneId).toLocalDate();
        } catch (DateTimeParseException instantError) {
            try {
                joined = ZonedDateTime.parse(value, TWITTER_JOINED_DATE_FMT).withZoneSameInstant(zoneId).toLocalDate();
            } catch (DateTimeParseException twitterError) {
                try {
                    joined = LocalDate.parse(value);
                } catch (DateTimeParseException dateError) {
                    return null;
                }
            }
        }
        return !refNow.atZone(zoneId).toLocalDate().isBefore(joined.plusYears(1));
    }

    private static boolean metricKnown(RawTweet tweet, String metric) {
        return !tweet.unavailableMetrics().contains(metric);
    }

    private static boolean engagementKnown(RawTweet tweet) {
        return metricKnown(tweet, "likes") && metricKnown(tweet, "replies")
                && metricKnown(tweet, "reposts") && metricKnown(tweet, "bookmarks");
    }

    private static boolean rateKnown(RawTweet tweet) {
        return metricKnown(tweet, "views") && tweet.viewCount() > 0 && engagementKnown(tweet);
    }

    private static long engagements(RawTweet tweet) {
        return (long) Math.max(0, tweet.likeCount()) + Math.max(0, tweet.replyCount())
                + Math.max(0, tweet.retweetCount()) + Math.max(0, tweet.bookmarkCount());
    }

    private static double safeErPct(long engagements, long views) {
        if (views <= 0L || engagements <= 0L) {
            return 0.0;
        }
        return round2(Math.min(100.0, engagements * 100.0 / views));
    }

    private static double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
