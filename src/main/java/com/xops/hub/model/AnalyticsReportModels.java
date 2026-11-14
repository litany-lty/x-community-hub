package com.xops.hub.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class AnalyticsReportModels {

    private AnalyticsReportModels() {
    }

    // 1. 发帖与回复活跃度看板（48小时动态窗口逐时 / 7天动态窗口逐日 / 30天动态窗口逐日）
    public record TrendBucketPoint(
            String label,
            String fullLabel,
            int posts,
            int replies,
            int quotes,
            int retweets,
            int threadReplies,
            long views,
            int engagements,
            int viewSampleCount,
            int engagementSampleCount
    ) {
    }

    public record ActivityOverview(
            String periodCode,
            String periodLabel,
            String windowRangeText,
            int windowDays,
            double effectiveCoveredDays,
            boolean sampleCapped,
            int originalPosts,
            int replies,
            int quotes,
            int retweets,
            int totalActions,
            double replyToPostRatio,
            double replySharePct,
            double dailyAvgPosts,
            double dailyAvgReplies,
            long totalViews,
            long avgViewsPerPost,
            double avgLikesPerPost,
            double avgEngagementRatePct,
            int activeUnits,
            int totalUnits,
            String activeProgressLabel,
            int activityHealthScore,
            String personaTag,
            String summaryAdvice,
            String chartTitle,
            String chartSubtitle,
            List<TrendBucketPoint> trendSeries,
            String coverageNote,
            int viewSampleCount,
            int mainPostViewSampleCount,
            int mainPostLikeSampleCount,
            int engagementRateSampleCount,
            String metricsNote
    ) {
    }

    // 2. 推友双向互动率深度拆解（按根帖 1帖=1次 严格去重，剥离同帖盖楼与本帖内礼貌回评）
    public record PeerInteractionStat(
            RawSnapshotModels.XUserSummary peer,
            boolean iFollowThem,
            boolean theyFollowMe,
            int myRepliesToPeer,
            int peerRepliesToMe,
            int myRawCommentCount,
            int peerRawCommentCount,
            int peerInThreadRepliesOnTheirPost,
            int myRepliesInMyOwnPost,
            int unrepliedInMyPostCount,
            String latestUnrepliedTweetUrl,
            int myQuotesOfPeer,
            int peerQuotesOfMe,
            int myMentionsOfPeer,
            int peerMentionsOfMe,
            int outboundTotal,
            int inboundTotal,
            int totalInteractions,
            String replyRatioLabel,
            String quoteRatioLabel,
            String mentionRatioLabel,
            double outboundSharePct,
            double returnRatePct,
            String returnRateLabel,
            double interactionSharePct,
            double intimacyScore,
            String reciprocityCode,
            String relationshipType,
            String relationshipPortrait,
            String recentSnippet,
            boolean followStateKnown
    ) {
        public PeerInteractionStat(
                RawSnapshotModels.XUserSummary peer, boolean iFollowThem, boolean theyFollowMe,
                int myRepliesToPeer, int peerRepliesToMe, int myRawCommentCount, int peerRawCommentCount,
                int peerInThreadRepliesOnTheirPost, int myRepliesInMyOwnPost, int unrepliedInMyPostCount,
                String latestUnrepliedTweetUrl, int myQuotesOfPeer, int peerQuotesOfMe,
                int myMentionsOfPeer, int peerMentionsOfMe, int outboundTotal, int inboundTotal,
                int totalInteractions, String replyRatioLabel, String quoteRatioLabel, String mentionRatioLabel,
                double outboundSharePct, double returnRatePct, String returnRateLabel,
                double interactionSharePct, double intimacyScore, String reciprocityCode,
                String relationshipType, String relationshipPortrait, String recentSnippet
        ) {
            this(peer, iFollowThem, theyFollowMe, myRepliesToPeer, peerRepliesToMe, myRawCommentCount,
                    peerRawCommentCount, peerInThreadRepliesOnTheirPost, myRepliesInMyOwnPost,
                    unrepliedInMyPostCount, latestUnrepliedTweetUrl, myQuotesOfPeer, peerQuotesOfMe,
                    myMentionsOfPeer, peerMentionsOfMe, outboundTotal, inboundTotal, totalInteractions,
                    replyRatioLabel, quoteRatioLabel, mentionRatioLabel, outboundSharePct, returnRatePct,
                    returnRateLabel, interactionSharePct, intimacyScore, reciprocityCode, relationshipType,
                    relationshipPortrait, recentSnippet, iFollowThem && theyFollowMe);
        }
    }

    public record InteractionOverview(
            int totalInteractedPeers,
            int totalOutboundActions,
            int totalInboundActions,
            int balancedMutualPeers,
            int outboundHeavyPeers,
            int inboundHeavyPeers,
            int unrepliedMyPostPeers,
            int filteredSamePostReplies,
            double overallReturnRatePct,
            List<PeerInteractionStat> peerStats
    ) {
    }

    // 3. 关注关系体检：逐人核验或完整名单中的缺席可确认为未关注；未知关系单独计数。
    public record FollowAuditItem(
            RawSnapshotModels.XUserSummary user,
            String relationState,
            boolean iFollowThem,
            boolean theyFollowMe,
            int myRepliesToThem,
            int theirRepliesToMe,
            int totalInteractions,
            String auditCategory,
            String auditLabel,
            String recommendation,
            String profileUrl
    ) {
    }

    // followBackRatePct 保留样本数值以兼容消费者；展示与分享应使用含完整性说明的 followBackRateLabel。
    // unknownFollowBackCount 为已关注但无法确认对方回关的样本；unknownFollowingCount 为粉丝中无法确认我是否关注的样本。
    public record FollowAuditOverview(
            int followingSampleCount,
            int followersSampleCount,
            boolean followersSampleTruncated,
            int mutualCount,
            int notFollowingBackCount,
            int fansNotFollowedBackCount,
            int suggestedUnfollowCount,
            int bigAccountUnreciprocatedCount,
            double followBackRatePct,
            List<FollowAuditItem> notFollowingBackList,
            List<FollowAuditItem> fansNotFollowedBackList,
            List<FollowAuditItem> mutualList,
            boolean followingsSampleTruncated,
            int unknownFollowBackCount,
            int unknownFollowingCount,
            String followBackRateLabel,
            boolean followBackRateComplete
    ) {
        public FollowAuditOverview(
                int followingSampleCount, int followersSampleCount, boolean followersSampleTruncated,
                int mutualCount, int notFollowingBackCount, int fansNotFollowedBackCount,
                int suggestedUnfollowCount, int bigAccountUnreciprocatedCount, double followBackRatePct,
                List<FollowAuditItem> notFollowingBackList, List<FollowAuditItem> fansNotFollowedBackList,
                List<FollowAuditItem> mutualList, boolean followingsSampleTruncated,
                int unknownFollowBackCount, int unknownFollowingCount, String followBackRateLabel
        ) {
            this(followingSampleCount, followersSampleCount, followersSampleTruncated, mutualCount,
                    notFollowingBackCount, fansNotFollowedBackCount, suggestedUnfollowCount,
                    bigAccountUnreciprocatedCount, followBackRatePct, notFollowingBackList,
                    fansNotFollowedBackList, mutualList, followingsSampleTruncated,
                    unknownFollowBackCount, unknownFollowingCount, followBackRateLabel,
                    !followingsSampleTruncated && unknownFollowBackCount == 0);
        }
    }

    // 4. 黄金发帖时段与爆款推文诊断
    public record HeatmapCell(
            int dayOfWeek,
            String dayLabel,
            int hour,
            int postCount,
            long avgViews,
            double avgEngagementRatePct,
            double intensity,
            int viewSampleCount,
            int engagementRateSampleCount
    ) {
    }

    public record GoldenSlot(
            String label,
            int postCount,
            long avgViews,
            double avgEngagementRatePct
    ) {
    }

    public record ContentFormatStat(
            String formatCode,
            String formatLabel,
            int postCount,
            long avgViews,
            double avgLikes,
            double avgReplies,
            double avgEngagementRatePct,
            int viewSampleCount,
            int likeSampleCount,
            int replySampleCount,
            int engagementRateSampleCount
    ) {
    }

    public record TopPostCard(
            String id,
            String text,
            Instant createdAt,
            String createdLabel,
            String kind,
            boolean hasMedia,
            boolean isThread,
            long viewCount,
            int likeCount,
            int replyCount,
            int retweetCount,
            int bookmarkCount,
            int totalEngagements,
            double engagementRatePct,
            double bookmarkRatePct,
            int algoScore,
            String algoTierLabel,
            String reachTierCode,
            String reachTierLabel,
            double followerReachMultiple,
            boolean hasExternalLink,
            boolean rapidFirePenalty,
            List<String> algoSignals,
            String tweetUrl,
            Set<String> unavailableMetrics
    ) {
    }

    // X 开源推荐算法（xai-org/x-algorithm）分发健康度与限流自检摘要
    public record AlgorithmHealthSummary(
            String healthLevel,
            String healthTitle,
            String healthSummary,
            int evaluatedPostCount,
            int outOfNetworkPostCount,
            int inNetworkPostCount,
            int lowReachPostCount,
            double outOfNetworkRatePct,
            int rapidFirePostCount,
            Instant latestMainPostAt,
            String diversityAdvice,
            int externalLinkPostCount,
            long externalLinkAvgViews,
            long nativePostAvgViews,
            int linkViewLossPct,
            int controversyRiskPostCount,
            String contentRuleAdvice,
            boolean exposureCliffDrop,
            int recentVsBaselinePct,
            boolean underTheHoodEligible,
            String underTheHoodHint,
            String underTheHoodUrl,
            int viewSampleCount,
            int externalLinkViewSampleCount,
            int nativeViewSampleCount,
            int recentViewSampleCount,
            int baselineViewSampleCount,
            int controversySampleCount,
            boolean recentComparisonAvailable,
            boolean linkComparisonAvailable,
            String metricsNote
    ) {
    }

    public record ContentTimingOverview(
            String timezone,
            List<GoldenSlot> goldenSlots,
            List<ContentFormatStat> formatStats,
            List<HeatmapCell> heatmapCells,
            List<TopPostCard> topPosts
    ) {
    }

    // 5. 社区核心铁粉圈层 & X 原生晒单卡片
    public record CircleMember(
            RawSnapshotModels.XUserSummary user,
            String tier,
            String badge,
            int myRepliesToPeer,
            int peerRepliesToMe,
            int totalInteractions,
            double intimacyScore,
            boolean mutualFollow,
            String highlightReason
    ) {
    }

    public record ShareCardPayload(
            String headline,
            String reportTitle,
            String personaTag,
            String periodLabel,
            int windowDays,
            int originalPosts,
            int replies,
            double replyToPostRatio,
            double avgEngagementRatePct,
            double followBackRatePct,
            int activityHealthScore,
            List<String> topBuddyHandles,
            String shareTweetText,
            String followBackRateLabel,
            int engagementRateSampleCount,
            String metricsNote
    ) {
    }

    public record InnerCircleOverview(
            List<CircleMember> coreFans,
            List<CircleMember> activeMutuals,
            List<CircleMember> needWarmupPeers,
            ShareCardPayload shareCard
    ) {
    }

    // 汇总报告
    public record CommunityDashboardReport(
            String provider,
            boolean liveData,
            boolean fromCache,
            long cacheTtlSeconds,
            long cacheRemainingSeconds,
            Instant fetchedAt,
            Instant nextRefreshAt,
            String bannerNotice,
            String periodCode,
            String periodLabel,
            int windowDays,
            RawSnapshotModels.XUserSummary profile,
            ActivityOverview activity,
            InteractionOverview interactions,
            FollowAuditOverview followAudit,
            ContentTimingOverview contentTiming,
            InnerCircleOverview innerCircle,
            AlgorithmHealthSummary algorithmHealth
    ) {
    }

    public record MultiPeriodDashboardBundle(
            CommunityDashboardReport month,
            CommunityDashboardReport week,
            CommunityDashboardReport day,
            int coveredWindowDays,
            boolean hasMoreHistoryForTarget,
            Instant coveredUntil,
            int pendingRelationshipChecks
    ) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public MultiPeriodDashboardBundle {
            // 0 表示尚无完整窗口的证据；旧数据缺失该字段也保持未知，不能补成完整 30 天。
            coveredWindowDays = Math.max(0, coveredWindowDays);
        }

        public MultiPeriodDashboardBundle(
                CommunityDashboardReport month, CommunityDashboardReport week, CommunityDashboardReport day,
                int coveredWindowDays, boolean hasMoreHistoryForTarget, Instant coveredUntil
        ) {
            this(month, week, day, coveredWindowDays, hasMoreHistoryForTarget, coveredUntil, 0);
        }

        public MultiPeriodDashboardBundle(
                CommunityDashboardReport month,
                CommunityDashboardReport week,
                CommunityDashboardReport day
        ) {
            this(month, week, day, 0, false, null, 0);
        }
    }

    public record ServerCapabilities(
            boolean oauthConfigured,
            boolean requireLoginSelfOnly,
            long cacheTtlSeconds,
            String timezone
    ) {
    }

    public record PresetAccount(
            String username,
            String displayName,
            String roleTag
    ) {
    }

    public record AuthenticatedXUser(
            String id,
            String username,
            String displayName,
            String avatarUrl,
            boolean verified,
            Instant loginAt
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record AuthStatusResponse(
            boolean oauthConfigured,
            String suggestedCallbackUrl,
            boolean loggedIn,
            AuthenticatedXUser user
    ) {
    }

    public record UsageCheckinResponse(
            String username,
            int usageCount,
            boolean shouldShowAuthorModal,
            String modalReasonCode,
            String modalBadgeText,
            String modalTitle,
            String modalMessage,
            boolean followingAuthor,
            boolean selfIsAuthor,
            RawSnapshotModels.XUserSummary authorProfile,
            String authorProfileUrl,
            String authorFollowIntentUrl
    ) {
    }
}
