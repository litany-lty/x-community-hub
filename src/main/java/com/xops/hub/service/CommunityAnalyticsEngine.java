package com.xops.hub.service;

import com.xops.hub.model.AnalyticsReportModels.*;
import com.xops.hub.model.RawSnapshotModels.*;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯领域分析引擎：支持「48小时动态窗口（默认）/ 7天动态窗口 / 30天动态窗口」三维联动切片，
 * 针对高频社区运营账号内置实际样本覆盖天数校准与直观运营指标计算。
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

    public CommunityDashboardReport analyze(
            RawAccountSnapshot snapshot,
            int requestedDays,
            ZoneId zoneId,
            boolean fromCache
    ) {
        Instant fetched = snapshot.fetchedAt() != null ? snapshot.fetchedAt() : Instant.now();
        return analyze(snapshot, requestedDays, zoneId, fromCache, 3600L, fetched.plusSeconds(3600L));
    }

    public CommunityDashboardReport analyze(
            RawAccountSnapshot snapshot,
            int requestedDays,
            ZoneId zoneId,
            boolean fromCache,
            long cacheTtlSeconds,
            Instant expiresAt
    ) {
        int windowDays = resolvePeriodDays(requestedDays);
        String periodCode = windowDays == 2 ? "HOURS_48" : (windowDays == 7 ? "WEEK" : "MONTH");
        String periodLabel = windowDays == 2 ? "48小时动态窗口" : (windowDays == 7 ? "7天动态窗口" : "30天动态窗口");
        String reportShortTitle = windowDays == 2 ? "48h战报" : (windowDays == 7 ? "7天战报" : "30天战报");

        Instant refNow = snapshot.fetchedAt() != null ? snapshot.fetchedAt() : Instant.now();
        Instant cutoff = windowDays == 2
                ? refNow.minus(Duration.ofHours(48))
                : refNow.minus(Duration.ofDays(windowDays));
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
        Instant effectiveCutoff = coverageComplete ? cutoff : coverageStart;

        List<RawTweet> windowTimeline = allTimeline.stream()
                .filter(t -> t.createdAt() != null && !t.createdAt().isBefore(effectiveCutoff) && !t.createdAt().isAfter(refNow))
                .sorted(Comparator.comparing(RawTweet::createdAt).reversed())
                .toList();
        List<InboundInteraction> windowInbound = allInbound.stream()
                .filter(i -> i.createdAt() != null && !i.createdAt().isBefore(effectiveCutoff) && !i.createdAt().isAfter(refNow))
                .sorted(Comparator.comparing(InboundInteraction::createdAt).reversed())
                .toList();

        boolean sampleCapped = !coverageComplete;
        double coveredHours = coverageComplete ? windowDays * 24.0
                : Math.max(0.0, Duration.between(coverageStart, refNow).toMinutes() / 60.0);
        double effectiveCoveredDays = coverageComplete
                ? windowDays
                : Math.min(windowDays, coveredHours / 24.0);
        String coverageNote = coverageComplete ? "" : String.format(
                "实际数据覆盖 %s 起（约 %s），更早数据仍在补齐",
                coverageStart.atZone(zoneId).format(RANGE_HOUR_FMT),
                coveredHours >= 48 ? String.format("%.1f 天", coveredHours / 24.0) : String.format("%.0f 小时", Math.floor(coveredHours))
        );

        Map<String, RelationshipEntry> relByKey = indexRelationships(snapshot.relationships(), selfKey);

        ActivityOverview activity = buildActivityOverview(
                periodCode,
                periodLabel,
                windowDays,
                effectiveCoveredDays,
                sampleCapped,
                windowTimeline,
                cutoff,
                refNow,
                zoneId,
                coverageNote
        );
        InteractionOverview interactions = buildInteractionOverview(
                selfKey,
                allTimeline,
                allInbound,
                windowTimeline,
                windowInbound,
                relByKey,
                snapshot.followersTruncated(),
                snapshot.followingsTruncated()
        );
        FollowAuditOverview followAudit = buildFollowAuditOverview(
                relByKey,
                interactions.peerStats(),
                periodLabel,
                snapshot.followersTruncated(),
                snapshot.followingsTruncated(),
                sampleCapped,
                observedInteractionPeers(windowTimeline, windowInbound, selfKey)
        );
        Map<String, Long> mainPostGapMinutesById = computeMainPostGapsMinutes(allTimeline);

        ContentTimingOverview contentTiming = buildContentTimingOverview(
                snapshot.profile(),
                windowTimeline,
                mainPostGapMinutesById,
                zoneId
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
                refNow,
                zoneId
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
                map.put(key, new RelationshipEntry(
                        preferRicherUser(prev.user(), entry.user()),
                        verified != null ? verified.iFollowThem() : prev.iFollowThem() || entry.iFollowThem(),
                        verified != null ? verified.theyFollowMe() : prev.theyFollowMe() || entry.theyFollowMe(),
                        verified != null
                ));
            }
        }
        return map;
    }

    private ActivityOverview buildActivityOverview(
            String periodCode,
            String periodLabel,
            int windowDays,
            double effectiveCoveredDays,
            boolean sampleCapped,
            List<RawTweet> timeline,
            Instant cutoff,
            Instant refNow,
            ZoneId zoneId,
            String coverageNote
    ) {
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
        if ("HOURS_48".equals(periodCode)) {
            // 从滚动起点划分 48 个小时桶，相邻两个小时合为一根柱；仅最后一桶包含窗口终点。
            ZonedDateTime zCutoff = cutoff.atZone(zoneId);
            windowRangeText = zCutoff.format(RANGE_HOUR_FMT) + " – " + zNow.format(RANGE_HOUR_FMT);
            chartTitle = "48小时动态窗口逐时段发帖与回复分布";
            chartSubtitle = (sampleCapped ? coverageNote + " · " : "")
                    + "从窗口起点按 2 小时统计 · 最后时段含终点";

            int[][] slotCounts = new int[24][5];
            long[][] slotMetrics = new long[24][4];
            Set<Integer> activeHourSet = new HashSet<>();
            for (RawTweet t : timeline) {
                int hourIndex = t.createdAt().equals(refNow) ? 47
                        : (int) Duration.between(cutoff, t.createdAt()).toHours();
                activeHourSet.add(hourIndex);
                int bucketIndex = hourIndex / 2;
                accumulateTweetBucket(t, slotCounts[bucketIndex], slotMetrics[bucketIndex]);
            }
            DateTimeFormatter compactSlotFmt = DateTimeFormatter.ofPattern("dd日HH:mm");
            trendSeries = new ArrayList<>(24);
            for (int b = 0; b < 24; b++) {
                ZonedDateTime slot = zCutoff.plusHours(b * 2L);
                int[] c = slotCounts[b];
                long[] m = slotMetrics[b];
                String fullLbl = slot.format(RANGE_HOUR_FMT) + " – "
                        + slot.plusHours(2).format(RANGE_HOUR_FMT);
                trendSeries.add(new TrendBucketPoint(
                        slot.format(compactSlotFmt), fullLbl,
                        c[0], c[1], c[2], c[3], c[4], m[0], (int) Math.min(Integer.MAX_VALUE, m[1]), (int) m[2], (int) m[3]
                ));
            }
            activeUnits = activeHourSet.size();
            totalUnits = 48;
            activeProgressLabel = String.format("活跃 %d / 48 小时", activeUnits);
        } else {
            // 7天动态窗口 或 30天动态窗口：动态起止精确到分钟，且保证滚动窗口首段动态不漏计
            ZonedDateTime zCutoff = cutoff.atZone(zoneId);
            LocalDate endDate = zNow.toLocalDate();
            LocalDate startDate = endDate.minusDays(windowDays - 1L);
            windowRangeText = zCutoff.format(RANGE_HOUR_FMT) + " – " + zNow.format(RANGE_HOUR_FMT);
            chartTitle = windowDays == 7 ? "7天动态窗口每日发帖 vs 回复趋势" : "30天动态窗口每日发帖 vs 回复趋势";
            chartSubtitle = sampleCapped
                    ? coverageNote + "，日均指标已按实际覆盖天数计算"
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
        String advice = buildActivityAdvice(periodCode, posts, quotes, replies, replyToPostRatio, dailyAvgReplies, avgEngagementRatePct);
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
        if ("HOURS_48".equals(periodCode)) {
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
            double erPct
    ) {
        int authored = posts + quotes;
        if (authored + replies == 0) {
            return "所选周期内暂无发帖或回复记录；可按自己的节奏尝试原创内容与同频交流，再观察公开互动变化。";
        }
        if (ratio < 1.0) {
            return String.format("当前「回复/发帖比」为 %.1fx（原创及引用 %d 条、回复 %d 条），偏向单向内容发布；可尝试参与核心推友讨论，再观察回访评论是否变化。", ratio, authored, replies);
        }
        if (ratio > 8.0 && authored <= 2) {
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
            boolean followingsTruncated
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

            // 主账本严格以「我去评他帖数 vs 他来评我帖数」为核心（若双方仅发生引用无评论，则仅在无评论时回退展示引用）
            boolean hasPostComments = (myPostVisits + peerPostVisits) > 0;
            if (!hasPostComments && (myQuotes + peerQuotes) == 0) {
                continue;
            }

            int outbound = hasPostComments ? myPostVisits : myQuotes;
            int inbound = hasPostComments ? peerPostVisits : peerQuotes;
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

            // 严格遵循四维清晰定义：
            // 1. 我没回谁 (I_OWE)：我的推文下他来评论过 (peerPostVisits > 0)，但我没有去他发布过的推文下评论 (myPostVisits == 0)
            // 2. 评论待回复 (UNREPLIED_POST)：别人评论了我，但我还没有回复他 (unrepliedPosts > 0，作为独立待办筛选维度)
            // 3. 谁没回我 (THEY_OWE)：我去他发布过的推文下评论过 (myPostVisits > 0)，但我的推文下他没有来评论过 (peerPostVisits == 0)
            // 4. 互相评论 (MUTUAL)：我去过他推文下评论 (myPostVisits > 0)，他也来过我的推文下评论 (peerPostVisits > 0)
            String reciprocityCode;
            String relationType;
            if (!hasPostComments) {
                reciprocityCode = "QUOTE_ONLY";
                relationType = "🔁 仅引用互动";
            } else if (peerPostVisits > 0 && myPostVisits == 0) {
                reciprocityCode = "I_OWE";
                relationType = unrepliedPosts > 0 ? "🙋‍♂️ 没去评TA·评论待回" : "🙋‍♂️ 我没去评 TA 帖";
                inboundHeavyCount++;
            } else if (myPostVisits > 0 && peerPostVisits == 0) {
                reciprocityCode = "THEY_OWE";
                relationType = "👀 TA 没来评我帖";
                outboundHeavyCount++;
            } else {
                reciprocityCode = "MUTUAL";
                relationType = unrepliedPosts > 0 ? "🤝 互相评论·评论待回" : "🤝 互相评论";
                balancedCount++;
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

            double intimacyScore = round1(
                    myPostVisits * 3.0
                            + peerPostVisits * 4.0
                            + (Math.min(myPostVisits, peerPostVisits) * 3.2)
                            + (myQuotes + peerQuotes) * 2.5
                            + (mutual ? 4.0 : 0.0)
                            + (unrepliedPosts > 0 ? 2.5 : 0.0)
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

        peerStats.sort(Comparator.comparingDouble(PeerInteractionStat::intimacyScore).reversed()
                .thenComparing(Comparator.comparingInt(PeerInteractionStat::totalInteractions).reversed()));

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

    private FollowAuditOverview buildFollowAuditOverview(
            Map<String, RelationshipEntry> relByKey,
            List<PeerInteractionStat> peerStats,
            String periodLabel,
            boolean followersTruncated,
            boolean followingsTruncated,
            boolean activityIncomplete,
            Set<String> observedInteractionPeers
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
            int myOut = stat != null ? stat.myRepliesToPeer() : 0;
            int theirIn = stat != null ? stat.peerRepliesToMe() : 0;
            int quotes = stat != null ? stat.myQuotesOfPeer() + stat.peerQuotesOfMe() : 0;
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
                        myOut, theirIn, totalInter, category, label, rec, url
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
                        myOut, theirIn, totalInter, category, label, rec, url
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
                        myOut, theirIn, totalInter, category, label, rec, url
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
            ZoneId zoneId
    ) {
        String username = profile.username();
        int followers = Math.max(0, profile.followersCount());
        List<RawTweet> authored = timeline.stream()
                .filter(CommunityAnalyticsEngine::isIndependentMainPost)
                .toList();

        int[] slotHours = {0, 3, 6, 9, 12, 15, 18, 21};
        // count, known views sum, ER engagements, known-view count, ER views sum, ER sample count
        long[][][] bucket = new long[7][8][6];
        // count, views, likes, replies, ER engagements, view count, like count, reply count, ER views, ER count
        long[][] fmtAgg = new long[3][10];

        for (RawTweet t : authored) {
            ZonedDateTime zdt = t.createdAt().atZone(zoneId);
            int dowIdx = zdt.getDayOfWeek().getValue() - 1;
            int slotIdx = Math.min(7, zdt.getHour() / 3);
            long views = Math.max(0L, t.viewCount());
            bucket[dowIdx][slotIdx][0]++;
            int fmtIdx = t.isThread() ? 2 : (t.hasMedia() ? 1 : 0);
            fmtAgg[fmtIdx][0]++;
            if (metricKnown(t, "views")) {
                bucket[dowIdx][slotIdx][1] += views;
                bucket[dowIdx][slotIdx][3]++;
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
            if (rateKnown(t)) {
                long eng = Math.min(engagements(t), views);
                bucket[dowIdx][slotIdx][2] += eng;
                bucket[dowIdx][slotIdx][4] += views;
                bucket[dowIdx][slotIdx][5]++;
                fmtAgg[fmtIdx][4] += eng;
                fmtAgg[fmtIdx][8] += views;
                fmtAgg[fmtIdx][9]++;
            }
        }

        double maxCellScore = 0.01;
        double[][] rawCellEr = new double[7][8];
        for (int d = 0; d < 7; d++) {
            for (int s = 0; s < 8; s++) {
                long cnt = bucket[d][s][5];
                long views = bucket[d][s][4];
                long eng = bucket[d][s][2];
                double er = safeErPct(eng, views);
                rawCellEr[d][s] = er;
                // 兼顾互动率与总曝光，防止 5 浏览 1 赞的极小样本虚高霸榜
                double weighted = cnt > 0 ? er * Math.log10(Math.max(20, views)) : 0.0;
                if (weighted > maxCellScore) {
                    maxCellScore = weighted;
                }
            }
        }

        List<HeatmapCell> cells = new ArrayList<>(56);
        List<GoldenSlot> slotCandidates = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            for (int s = 0; s < 8; s++) {
                int hour = slotHours[s];
                int cnt = (int) bucket[d][s][0];
                int viewCount = (int) bucket[d][s][3];
                int rateCount = (int) bucket[d][s][5];
                long avgViews = viewCount > 0 ? Math.round((double) bucket[d][s][1] / viewCount) : 0L;
                double er = round2(rawCellEr[d][s]);
                double weighted = rateCount > 0 ? rawCellEr[d][s] * Math.log10(Math.max(20, bucket[d][s][4])) : 0.0;
                double intensity = cnt > 0 ? round2(Math.min(1.0, weighted / maxCellScore)) : 0.0;
                cells.add(new HeatmapCell(d + 1, DAY_LABELS[d], hour, cnt, avgViews, er, intensity, viewCount, rateCount));
                if (cnt > 0 && weighted > 0.0) {
                    String slotLabel = String.format("%s %02d:00–%02d:00", DAY_LABELS[d], hour, (hour + 3) == 24 ? 24 : hour + 3);
                    slotCandidates.add(new GoldenSlot(slotLabel, rateCount,
                            Math.round((double) bucket[d][s][4] / rateCount), er));
                }
            }
        }

        slotCandidates.sort(Comparator
                .comparingDouble((GoldenSlot g) -> g.avgEngagementRatePct() * Math.log10(Math.max(25, g.avgViews() * g.postCount())))
                .reversed());
        List<GoldenSlot> goldenSlots = slotCandidates.stream().limit(3).toList();

        String[][] fmtMeta = {
                {"TEXT", "纯文本观点贴"},
                {"MEDIA", "配图 / 视频贴"},
                {"THREAD", "长文 / 串推 Thread"}
        };
        List<ContentFormatStat> formatStats = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int cnt = (int) fmtAgg[i][0];
            long views = fmtAgg[i][1];
            formatStats.add(new ContentFormatStat(
                    fmtMeta[i][0],
                    fmtMeta[i][1],
                    cnt,
                    fmtAgg[i][5] > 0 ? Math.round((double) views / fmtAgg[i][5]) : 0L,
                    fmtAgg[i][6] > 0 ? round1((double) fmtAgg[i][2] / fmtAgg[i][6]) : 0.0,
                    fmtAgg[i][7] > 0 ? round1((double) fmtAgg[i][3] / fmtAgg[i][7]) : 0.0,
                    safeErPct(fmtAgg[i][4], fmtAgg[i][8]),
                    (int) fmtAgg[i][5], (int) fmtAgg[i][6], (int) fmtAgg[i][7], (int) fmtAgg[i][9]
            ));
        }

        // 按公开互动量排序；评分为本地启发式参考，不代表平台内部权重或推荐来源。
        List<TopPostCard> topPosts = authored.stream()
                .map(t -> buildScoredTopPostCard(t, username, followers, mainPostGapMinutesById.get(t.id()), zoneId))
                .sorted(Comparator.comparingDouble((TopPostCard p) ->
                        p.unavailableMetrics().isEmpty()
                                ? p.replyCount() * 4.0 + p.retweetCount() * 3.0 + p.bookmarkCount() * 3.0 + p.likeCount() * 2.0
                                        + Math.log10(Math.max(10, p.viewCount())) * 2.5
                                : Double.NEGATIVE_INFINITY
                ).reversed())
                .limit(24)
                .toList();

        return new ContentTimingOverview(
                zoneId.getId(),
                goldenSlots,
                formatStats,
                cells,
                topPosts
        );
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
                .filter(p -> p.myRepliesToPeer() > 0 && p.peerRepliesToMe() > 0)
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
        return richnessScore(b) > richnessScore(a) ? b : a;
    }

    private static int richnessScore(XUserSummary u) {
        int s = 0;
        if (u.displayName() != null && !u.displayName().isBlank() && !u.displayName().equals(u.username())) s += 3;
        if (u.avatarUrl() != null && !u.avatarUrl().isBlank()) s += 3;
        if (u.bio() != null && !u.bio().isBlank()) s += 2;
        if (u.followersCount() > 0) s += 2;
        return s;
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
            ZoneId zoneId
    ) {
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
        } else if (views >= Math.max(150L, followersCount) && reachMultiple >= 1.0) {
            reachTierCode = "OON_VIRAL";
            reachTierLabel = String.format("📊 浏览为粉丝数 %.1f 倍", reachMultiple);
        } else if (followersCount >= 80 && views > 0 && views < followersCount * 0.05) {
            reachTierCode = "LOW_REACH";
            reachTierLabel = "📉 浏览量低于粉丝数 5%";
        } else {
            reachTierCode = "IN_NETWORK";
            reachTierLabel = "📊 其他浏览量样本（来源未知）";
        }

        boolean extLink = hasExternalUrl(t);
        boolean rapidFire = gapMinutesSincePrev != null && gapMinutesSincePrev < 180L;
        boolean controversyRisk = metricKnown(t, "replies") && metricKnown(t, "likes") && metricKnown(t, "bookmarks")
                && replies >= 5 && replies > (likes + bookmarks) * 1.8;

        // 公开计数与内容特征的本地启发式评分，不能还原平台权重、停留时长或分发状态。
        long effViews = Math.max(50L, views);
        double replyRate = Math.min(8.0, replies * 100.0 / effViews);
        double bmPct = Math.min(8.0, bookmarks * 100.0 / effViews);
        double rtPct = Math.min(8.0, retweets * 100.0 / effViews);
        double lkPct = Math.min(12.0, likes * 100.0 / effViews);

        double replyScore = Math.min(30.0, Math.sqrt(replyRate / 1.2) * 18.0 + Math.min(12.0, replies * 0.9));
        double dwellBookmarkScore = Math.min(24.0, Math.sqrt(bmPct / 0.8) * 14.0
                + (t.isThread() || (t.text() != null && t.text().length() >= 140) ? 5.0 : 0.0)
                + (t.hasMedia() ? 4.0 : 0.0));
        double shareOutScore = Math.min(24.0, Math.sqrt(rtPct / 0.8) * 13.0
                + Math.min(11.0, Math.log10(Math.max(1.0, reachMultiple * 10.0)) * 6.5));
        double favScore = Math.min(18.0, Math.sqrt(lkPct / 2.5) * 14.0 + Math.min(4.0, likes * 0.1));

        double rawScore = 18.0 + replyScore + dwellBookmarkScore + shareOutScore + favScore;
        List<String> posSignals = new ArrayList<>(3);
        List<String> penaltySignals = new ArrayList<>(3);

        if ("OON_VIRAL".equals(reachTierCode)) {
            posSignals.add(reachTierLabel);
        }
        if (bookmarks >= 3 && bmRate >= 0.35) {
            posSignals.add("🔖 收藏占比较高（本地参考）");
        } else if (t.isThread()) {
            posSignals.add("🧵 串推内容（本地参考）");
        }
        if (replies >= 4 && !controversyRisk) {
            posSignals.add("💬 评论较多（本地参考）");
        }

        if (rapidFire) {
            int deduct = gapMinutesSincePrev < 60L ? 14 : 8;
            rawScore -= deduct;
            penaltySignals.add(gapMinutesSincePrev < 60L
                    ? String.format("⏱️ 距上帖%d分（本地分-%d）", gapMinutesSincePrev, deduct)
                    : String.format("⏱️ 3h内连发（本地分-%d）", deduct));
        }
        if (extLink) {
            rawScore -= 8.0;
            penaltySignals.add("🔗 正文含外链（本地分-8）");
        }
        if (controversyRisk) {
            rawScore -= 10.0;
            penaltySignals.add("💬 评多赞少（本地分-10，原因未知）");
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

        int finalScore = (int) Math.max(15L, Math.min(99L, Math.round(rawScore)));
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
            Instant refNow,
            ZoneId zoneId
    ) {
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
        int nativeCount = 0;
        int extViewCount = 0;
        int nativeViewCount = 0;
        long nativeViewsSum = 0L;
        int controversyCount = 0;
        int controversySampleCount = 0;

        for (RawTweet t : windowMain) {
            long v = Math.max(0L, t.viewCount());
            double mult = followers > 0 ? (double) v / followers : 0.0;
            if (metricKnown(t, "views")) {
                evalCount++;
                if (v >= Math.max(150L, followers) && mult >= 1.0) oonCount++;
                else if (followers >= 80 && v > 0 && v < followers * 0.05) lowReachCount++;
                else inNetCount++;
            }

            Long gap = mainPostGapMinutesById.get(t.id());
            if (gap != null && gap < 180L) {
                rapidFireCount++;
            }

            if (hasExternalUrl(t)) {
                extLinkCount++;
                if (metricKnown(t, "views")) {
                    extLinkViewsSum += v;
                    extViewCount++;
                }
            } else {
                nativeCount++;
                if (metricKnown(t, "views")) {
                    nativeViewsSum += v;
                    nativeViewCount++;
                }
            }

            if (metricKnown(t, "replies") && metricKnown(t, "likes") && metricKnown(t, "bookmarks")) {
                controversySampleCount++;
                if (t.replyCount() >= 5 && t.replyCount() > (t.likeCount() + t.bookmarkCount()) * 1.8) controversyCount++;
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
        boolean cliffDrop = recentVsBasePct <= -60
                || (evalCount >= 3 && lowReachCount * 2 >= evalCount);

        String diversityAdvice = rapidFireCount == 0
                ? "ℹ️ 样本中未发现间隔不足 3 小时的主推；无法据此判断平台折叠或降权"
                : String.format("⏱️ 样本中 %d 条主推间隔不足 3 小时；可尝试调整节奏并观察变化，无法据此确认平台折叠或降权", rapidFireCount);

        String contentRuleAdvice;
        if (linkLossPct >= 20) {
            contentRuleAdvice = String.format("🔗 样本中带外链主推均浏览低 %d%%（%d vs %d）；题材、发布时间和观察时长可能不同，不能据此认定外链造成差异", linkLossPct, extAvg, nativeAvg);
        } else if (extLinkCount > 0) {
            contentRuleAdvice = String.format("🔗 样本中 %d 条主推正文带外链；可比较不同链接位置的后续表现，当前数据无法确认其影响", extLinkCount);
        } else if (controversyCount > 0) {
            contentRuleAdvice = String.format("💬 样本中 %d 条推文评论数明显高于点赞收藏；原因未知，无法据此推断负面反馈或平台处罚", controversyCount);
        } else {
            contentRuleAdvice = "ℹ️ 样本中未出现上述外链或评论比例特征；这不代表平台评分或处罚状态";
        }

        // 本地 Under The Hood 自查门槛（注册满 1 年 + 近 30 天动态 ≥ 10 条），实际资格由官方核验。
        long postsLast30d = allTimeline.stream()
                .filter(t -> t.createdAt() != null && !t.createdAt().isBefore(cut30d) && !t.createdAt().isAfter(refNow))
                .count();
        Boolean ageOk = isAccountOverOneYearOld(profile.joinedDate(), refNow, zoneId);
        boolean uthEligible = Boolean.TRUE.equals(ageOk) && postsLast30d >= 10;
        String uthHint = ageOk == null
                ? "ℹ️ 注册日期缺失或无法解析，无法确认是否注册满1年；实际查看资格请前往官方页核验"
                : (uthEligible
                ? "✅ 本地样本满足注册满1年且近30天动态≥10条，实际查看资格及报告请前往官方页核验"
                : "ℹ️ 本地样本尚未满足注册满1年且近30天动态≥10条，实际查看资格请前往官方页核验");

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
            healthSummary = recentVsBasePct <= -60
                    ? String.format("公开样本中近 7 天主推均浏览较前期低 %d%%；内容、观察时长等因素未受控，无法据此判断限流状态", Math.abs(recentVsBasePct))
                    : String.format("%s公开样本中 %d/%d 条主推浏览量低于粉丝数的 5%%；浏览量不等于独立触达人数，无法据此判断限流状态", periodLabel, lowReachCount, evalCount);
        } else if (rapidFireCount > 0 || linkLossPct >= 25) {
            healthLevel = "WARN";
            healthTitle = "🟡 公开样本有待观察项";
            healthSummary = rapidFireCount > 0
                    ? String.format("样本中 %d 条主推间隔不足 3 小时；可调整节奏后继续观察，当前数据无法判断限流或折叠状态", rapidFireCount)
                    : String.format("样本中带外链主推均浏览较无外链低 %d%%；这是相关性观察，无法确认因果效果或限流状态", linkLossPct);
        } else {
            healthLevel = "GOOD";
            healthTitle = "📊 公开样本浏览量观察";
            healthSummary = String.format("%s公开样本共 %d 条主推，其中 %d 条浏览量达到粉丝数且不少于 150（样本占比 %.0f%%）；浏览可能重复，无法确认来源、独立触达人数或限流状态",
                    periodLabel, evalCount, oonCount, oonRatePct);
        }

        boolean metricsIncomplete = windowMain.stream().anyMatch(t -> !t.unavailableMetrics().isEmpty());
        String metricsNote = metricsIncomplete
                ? String.format("公开指标不完整；浏览观察使用 %d/%d 条已知样本，未知数未按0计入，外链与历史对比也仅使用已知浏览样本", evalCount, windowMain.size()) : "";
        if (!metricsNote.isEmpty()) healthSummary += "；" + metricsNote;
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
                "https://x.com/i/jf/under_the_hood",
                evalCount,
                extViewCount,
                nativeViewCount,
                recent7dMain.size(),
                prev8to30dMain.size(),
                controversySampleCount,
                recent7dMain.size() >= 2 && prev8to30dMain.size() >= 2 && avgPrev >= 100L,
                extViewCount > 0 && nativeViewCount > 0 && nativeAvg > 0,
                metricsNote
        );
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
