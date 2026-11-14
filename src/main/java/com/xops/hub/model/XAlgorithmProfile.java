package com.xops.hub.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 可热更新的 X 推荐算法证据参数与本地公开样本启发式配置。
 * 严格区分两层：
 * 1) {@link UpstreamEvidenceSpec}：逐字对齐 https://github.com/xai-org/x-algorithm 源码（vm-ranker / home-mixer / xai-value-model / scarecrow / under-the-hood）真实生产常量与公式；
 * 2) {@link ScoringWeights} / {@link CadenceAndReachRules} / {@link OpsPlaybookRules} / {@link DraftCheckerRules}：
 *    面向公开 API 可见指标与发帖前结构预检的本地代理参数，严禁编造上游不存在的文案词表或标签数硬规则。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record XAlgorithmProfile(
        String version,
        String name,
        String updatedAt,
        String sourceUrl,
        List<String> changelog,
        UpstreamEvidenceSpec upstreamEvidence,
        ScoringWeights scoring,
        CadenceAndReachRules cadenceAndReach,
        OpsPlaybookRules opsPlaybook,
        DraftCheckerRules draftChecker
) {

    public XAlgorithmProfile(
            String version,
            String name,
            String updatedAt,
            String sourceUrl,
            List<String> changelog,
            ScoringWeights scoring,
            CadenceAndReachRules cadenceAndReach,
            OpsPlaybookRules opsPlaybook,
            DraftCheckerRules draftChecker
    ) {
        this(
                version,
                name,
                updatedAt,
                sourceUrl,
                changelog,
                UpstreamEvidenceSpec.defaults(),
                scoring,
                cadenceAndReach,
                opsPlaybook,
                draftChecker
        );
    }

    public static XAlgorithmProfile builtinFallback() {
        return new XAlgorithmProfile(
                "2026.11-phoenix-hm",
                "Phoenix + Home Mixer",
                "2026-11-15",
                "https://github.com/xai-org/x-algorithm/tree/590f3926e5bc",
                List.of(
                        "对齐 xai-org/x-algorithm (commit 590f3926e5bc, param.rs 2026-11-12) 真实 vm_ranker / ValueModelWeights 参数（作用于个性化预测概率 P(action)，非原始互动计数乘数）",
                        "接入 BidirectionalFollowReplyWeightBoost (+15.0)：互关账号的原创/引用帖 reply 预测权重由 5.0 提升至 20.0；评论排序同步纳入 focalAuthorReplySeq / favByRootAuthorSeq",
                        "接入 OONRetweetReplyFilter 与 PostFusionMultiplier 精确公式：纯转推与回复在公域过滤丢弃、关注内按 0.75x 折算；同作者单批多帖按 (1-0.25)*0.5^k+0.25 衰减",
                        "接入 Scarecrow 反垃圾规则证据（BBQDuplicateTextProd 重复文本 COPYPASTA_SPAM、bot/6754 低质外链+@非粉丝提及）与 vm-ranker DPP(theta=0.65) 语义去重；剔除主观文案词表猜测"
                ),
                UpstreamEvidenceSpec.defaults(),
                ScoringWeights.defaults(),
                CadenceAndReachRules.defaults(),
                OpsPlaybookRules.defaults(),
                DraftCheckerRules.defaults()
        );
    }

    public XAlgorithmProfile normalizedWithDefaults(XAlgorithmProfile fallback) {
        XAlgorithmProfile fb = fallback != null ? fallback : builtinFallback();
        UpstreamEvidenceSpec fbEv = fb.upstreamEvidence() != null ? fb.upstreamEvidence() : UpstreamEvidenceSpec.defaults();
        String ver = (version != null && !version.isBlank()) ? version.trim() : fb.version();
        String nm = (name != null && !name.isBlank()) ? name.trim() : fb.name();
        String upd = (updatedAt != null && !updatedAt.isBlank()) ? updatedAt.trim() : fb.updatedAt();
        String src = (sourceUrl != null && !sourceUrl.isBlank()) ? sourceUrl.trim() : fb.sourceUrl();
        List<String> logs = (changelog != null && !changelog.isEmpty())
                ? changelog.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList()
                : fb.changelog();
        if (logs.isEmpty()) {
            logs = fb.changelog();
        }
        return new XAlgorithmProfile(
                ver,
                nm,
                upd,
                src,
                List.copyOf(logs),
                upstreamEvidence != null ? upstreamEvidence.normalizedWithDefaults(fbEv) : fbEv,
                scoring != null ? scoring.normalizedWithDefaults(fb.scoring()) : fb.scoring(),
                cadenceAndReach != null ? cadenceAndReach.normalizedWithDefaults(fb.cadenceAndReach()) : fb.cadenceAndReach(),
                opsPlaybook != null ? opsPlaybook.normalizedWithDefaults(fb.opsPlaybook()) : fb.opsPlaybook(),
                draftChecker != null ? draftChecker.normalizedWithDefaults(fb.draftChecker()) : fb.draftChecker()
        );
    }

    public void validateStrict() {
        if (version == null || version.isBlank() || version.trim().length() > 64) {
            throw new IllegalArgumentException("算法版本号不能为空且长度不超过 64 字符");
        }
        if (name == null || name.isBlank() || name.trim().length() > 80) {
            throw new IllegalArgumentException("算法名称不能为空且长度不超过 80 字符");
        }
        if (upstreamEvidence != null) {
            upstreamEvidence.validateStrict();
        }
        if (scoring != null) {
            scoring.validateStrict();
        }
        if (cadenceAndReach != null) {
            cadenceAndReach.validateStrict();
        }
        if (opsPlaybook != null) {
            opsPlaybook.validateStrict();
        }
        if (draftChecker != null) {
            draftChecker.validateStrict();
        }
    }

    private static double finiteOrDefault(double val, double def, double min, double max) {
        if (!Double.isFinite(val)) {
            return def;
        }
        return Math.max(min, Math.min(max, val));
    }

    private static double positiveOrDefault(double val, double def, double min, double max) {
        if (!Double.isFinite(val) || val <= 0.0) {
            return def;
        }
        return Math.max(min, Math.min(max, val));
    }

    private static void requireFiniteRange(double val, double min, double max, String field) {
        if (!Double.isFinite(val) || val < min || val > max) {
            throw new IllegalArgumentException("字段 " + field + " 超出有效范围 [" + min + ", " + max + "]");
        }
    }

    private static void requirePositiveRange(double val, double min, double max, String field) {
        if (!Double.isFinite(val) || val <= 0.0 || val < min || val > max) {
            throw new IllegalArgumentException("字段 " + field + " 必须为正数且在 [" + min + ", " + max + "] 范围内");
        }
    }

    /**
     * 逐字对应上游 xai-org/x-algorithm (vm-ranker/params.rs, home-mixer/params/param.rs, xai-value-model/scoring.rs,
     * home-mixer/filters/*, home-mixer/scorers/author_cold_start.rs, under-the-hood/strato/columns/underTheHoodReport.User.strato)
     * 的可验证源码常量。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstreamEvidenceSpec(
            String upstreamCommit,
            String paramSyncedAt,
            String activeRankerService,
            // Positive ValueModelWeights (vm-ranker/params.rs, multiplying predicted P(action) or continuous secs)
            double shareViaCopyLinkWeight,
            double replyWeight,
            double bidirectionalFollowReplyWeightBoost,
            double bidirectionalFollowDwellWeightBoost,
            double quoteWeight,
            double shareViaDmWeight,
            double followAuthorWeight,
            double shareWeight,
            double retweetWeight,
            double favoriteWeight,
            double contClickDwellTimeWeight,
            double clickWeight,
            double openLinkWeight,
            double videoOpenWeight,
            double photoExpandWeight,
            double quotedClickWeight,
            double dwellWeight,
            double postUnexploredWeight,
            double contDwellTimeWeight,
            double vqvWeight,
            double profileClickWeight,
            int minVideoDurationMs,
            // Negative ValueModelWeights (vm-ranker/params.rs)
            double reportWeight,
            double muteAuthorWeight,
            double notInterestedWeight,
            double blockAuthorWeight,
            double notDwelledWeight,
            // Pipeline & PostFusionMultiplier (xai-value-model/scoring.rs, home-mixer/filters/*, vm-ranker/dpp.rs)
            int maxCandidateAgeHours,
            boolean enableAuthorDiversity,
            double authorDiversityDecay,
            double authorDiversityFloor,
            double oonWeightFactor,
            double topicOonWeightFactor,
            boolean oonRescoreInNetworkRepliesRetweets,
            boolean enablePhoenixOonReplies,
            boolean dppEnabled,
            double dppTheta,
            int dppMaxSelectedRank,
            // AuthorColdStart (home-mixer/scorers/author_cold_start.rs + param.rs)
            boolean coldStartEnabled,
            long coldStartFollowerCap,
            int coldStartImpressionThreshold,
            int coldStartMaxPostAgeSecs,
            int coldStartSlotMin,
            int coldStartSlotMax,
            // Under The Hood official transparency report eligibility (underTheHoodReport.User.strato)
            int underTheHoodMinAccountAgeDays,
            int underTheHoodMinPriorMonthPosts,
            int underTheHoodPublishLagDays
    ) {
        public static UpstreamEvidenceSpec defaults() {
            return new UpstreamEvidenceSpec(
                    "590f3926e5bc",
                    "2026-11-12T16:00:36Z",
                    "vm_ranker",
                    20.0,
                    5.0,
                    15.0,
                    0.0,
                    5.0,
                    5.0,
                    4.0,
                    2.0,
                    1.0,
                    0.5,
                    0.4,
                    0.3,
                    0.2,
                    0.07,
                    0.05,
                    0.05,
                    0.05,
                    0.02,
                    0.004,
                    0.0,
                    0.0,
                    10_000,
                    -234.0,
                    -58.8,
                    -47.52,
                    -31.2,
                    -0.02,
                    48,
                    true,
                    0.5,
                    0.25,
                    0.75,
                    0.5,
                    true,
                    false,
                    true,
                    0.65,
                    150,
                    true,
                    50_000L,
                    200,
                    7200,
                    15,
                    16,
                    365,
                    10,
                    10
            );
        }

        /**
         * 对应 xai-value-model/scoring.rs: diversity_multiplier(decay_factor, floor, k) = (1 - floor) * decay^k + floor。
         */
        public double authorDiversityMultiplier(int sameAuthorBatchRankIndex) {
            if (!enableAuthorDiversity || sameAuthorBatchRankIndex <= 0) {
                return 1.0;
            }
            return (1.0 - authorDiversityFloor) * Math.pow(authorDiversityDecay, sameAuthorBatchRankIndex) + authorDiversityFloor;
        }

        /**
         * 对应 home-mixer/filters/oon_retweet_reply_filter.rs + xai-value-model/scoring.rs: oon_applies。
         */
        public double postFusionOonMultiplier(boolean inNetwork, boolean isReply, boolean isRetweet) {
            if (!inNetwork) {
                if (isRetweet || (isReply && !enablePhoenixOonReplies)) {
                    return 0.0;
                }
                return oonWeightFactor;
            }
            if ((isReply || isRetweet) && oonRescoreInNetworkRepliesRetweets) {
                return oonWeightFactor;
            }
            return 1.0;
        }

        /**
         * 对应 xai-value-model/weights.rs: reply_weight_for(candidate)。
         */
        public double effectiveReplyWeight(boolean isReply, boolean isRetweet, boolean isMutualFollowAuthor) {
            if (!isReply && !isRetweet && isMutualFollowAuthor && bidirectionalFollowReplyWeightBoost != 0.0) {
                return replyWeight + bidirectionalFollowReplyWeightBoost;
            }
            return replyWeight;
        }

        public UpstreamEvidenceSpec normalizedWithDefaults(UpstreamEvidenceSpec fb) {
            String commit = (upstreamCommit != null && !upstreamCommit.isBlank()) ? upstreamCommit.trim() : fb.upstreamCommit();
            String synced = (paramSyncedAt != null && !paramSyncedAt.isBlank()) ? paramSyncedAt.trim() : fb.paramSyncedAt();
            String ranker = (activeRankerService != null && !activeRankerService.isBlank()) ? activeRankerService.trim() : fb.activeRankerService();
            return new UpstreamEvidenceSpec(
                    commit,
                    synced,
                    ranker,
                    finiteOrDefault(shareViaCopyLinkWeight, fb.shareViaCopyLinkWeight(), 0.0, 500.0),
                    finiteOrDefault(replyWeight, fb.replyWeight(), 0.0, 500.0),
                    finiteOrDefault(bidirectionalFollowReplyWeightBoost, fb.bidirectionalFollowReplyWeightBoost(), 0.0, 500.0),
                    finiteOrDefault(bidirectionalFollowDwellWeightBoost, fb.bidirectionalFollowDwellWeightBoost(), 0.0, 500.0),
                    finiteOrDefault(quoteWeight, fb.quoteWeight(), 0.0, 500.0),
                    finiteOrDefault(shareViaDmWeight, fb.shareViaDmWeight(), 0.0, 500.0),
                    finiteOrDefault(followAuthorWeight, fb.followAuthorWeight(), 0.0, 500.0),
                    finiteOrDefault(shareWeight, fb.shareWeight(), 0.0, 500.0),
                    finiteOrDefault(retweetWeight, fb.retweetWeight(), 0.0, 500.0),
                    finiteOrDefault(favoriteWeight, fb.favoriteWeight(), 0.0, 500.0),
                    finiteOrDefault(contClickDwellTimeWeight, fb.contClickDwellTimeWeight(), 0.0, 500.0),
                    finiteOrDefault(clickWeight, fb.clickWeight(), 0.0, 500.0),
                    finiteOrDefault(openLinkWeight, fb.openLinkWeight(), -100.0, 500.0),
                    finiteOrDefault(videoOpenWeight, fb.videoOpenWeight(), 0.0, 500.0),
                    finiteOrDefault(photoExpandWeight, fb.photoExpandWeight(), 0.0, 500.0),
                    finiteOrDefault(quotedClickWeight, fb.quotedClickWeight(), 0.0, 500.0),
                    finiteOrDefault(dwellWeight, fb.dwellWeight(), 0.0, 500.0),
                    finiteOrDefault(postUnexploredWeight, fb.postUnexploredWeight(), 0.0, 500.0),
                    finiteOrDefault(contDwellTimeWeight, fb.contDwellTimeWeight(), 0.0, 500.0),
                    finiteOrDefault(vqvWeight, fb.vqvWeight(), 0.0, 500.0),
                    finiteOrDefault(profileClickWeight, fb.profileClickWeight(), 0.0, 500.0),
                    minVideoDurationMs > 0 ? Math.min(600_000, minVideoDurationMs) : fb.minVideoDurationMs(),
                    finiteOrDefault(reportWeight, fb.reportWeight(), -5000.0, 0.0),
                    finiteOrDefault(muteAuthorWeight, fb.muteAuthorWeight(), -5000.0, 0.0),
                    finiteOrDefault(notInterestedWeight, fb.notInterestedWeight(), -5000.0, 0.0),
                    finiteOrDefault(blockAuthorWeight, fb.blockAuthorWeight(), -5000.0, 0.0),
                    finiteOrDefault(notDwelledWeight, fb.notDwelledWeight(), -500.0, 0.0),
                    maxCandidateAgeHours > 0 ? Math.min(720, maxCandidateAgeHours) : fb.maxCandidateAgeHours(),
                    enableAuthorDiversity,
                    positiveOrDefault(authorDiversityDecay, fb.authorDiversityDecay(), 0.01, 1.0),
                    positiveOrDefault(authorDiversityFloor, fb.authorDiversityFloor(), 0.01, 1.0),
                    positiveOrDefault(oonWeightFactor, fb.oonWeightFactor(), 0.01, 1.0),
                    positiveOrDefault(topicOonWeightFactor, fb.topicOonWeightFactor(), 0.01, 1.0),
                    oonRescoreInNetworkRepliesRetweets,
                    enablePhoenixOonReplies,
                    dppEnabled,
                    positiveOrDefault(dppTheta, fb.dppTheta(), 0.01, 0.999),
                    dppMaxSelectedRank > 0 ? Math.min(2000, dppMaxSelectedRank) : fb.dppMaxSelectedRank(),
                    coldStartEnabled,
                    coldStartFollowerCap > 0 ? Math.min(10_000_000L, coldStartFollowerCap) : fb.coldStartFollowerCap(),
                    coldStartImpressionThreshold > 0 ? Math.min(100_000, coldStartImpressionThreshold) : fb.coldStartImpressionThreshold(),
                    coldStartMaxPostAgeSecs > 0 ? Math.min(172_800, coldStartMaxPostAgeSecs) : fb.coldStartMaxPostAgeSecs(),
                    Math.max(0, Math.min(200, coldStartSlotMin)),
                    Math.max(Math.max(1, coldStartSlotMin), Math.min(200, coldStartSlotMax > 0 ? coldStartSlotMax : fb.coldStartSlotMax())),
                    underTheHoodMinAccountAgeDays > 0 ? Math.min(3650, underTheHoodMinAccountAgeDays) : fb.underTheHoodMinAccountAgeDays(),
                    underTheHoodMinPriorMonthPosts > 0 ? Math.min(1000, underTheHoodMinPriorMonthPosts) : fb.underTheHoodMinPriorMonthPosts(),
                    underTheHoodPublishLagDays > 0 ? Math.min(90, underTheHoodPublishLagDays) : fb.underTheHoodPublishLagDays()
            );
        }

        public void validateStrict() {
            requirePositiveRange(authorDiversityDecay, 0.01, 1.0, "upstreamEvidence.authorDiversityDecay");
            requirePositiveRange(authorDiversityFloor, 0.01, 1.0, "upstreamEvidence.authorDiversityFloor");
            requirePositiveRange(oonWeightFactor, 0.01, 1.0, "upstreamEvidence.oonWeightFactor");
            requireFiniteRange(replyWeight, 0.0, 500.0, "upstreamEvidence.replyWeight");
            requireFiniteRange(bidirectionalFollowReplyWeightBoost, 0.0, 500.0, "upstreamEvidence.bidirectionalFollowReplyWeightBoost");
            requireFiniteRange(reportWeight, -5000.0, 0.0, "upstreamEvidence.reportWeight");
            if (maxCandidateAgeHours < 1 || maxCandidateAgeHours > 720) {
                throw new IllegalArgumentException("upstreamEvidence.maxCandidateAgeHours 必须在 [1, 720] 小时内");
            }
        }
    }

    /**
     * 已发布推文的本地公开互动代理评分参数（仅基于公开可见互动率与已核实帖内接话，不对字数或配图凭空赋分）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoringWeights(
            double baseScore,
            int minScore,
            int maxScore,
            double replyMaxPoints,
            double replyRatePivotPct,
            double replyRateCoef,
            double replyCountCap,
            double replyCountCoef,
            double authorReplyEngagementBonus,
            double dwellBookmarkMaxPoints,
            double bookmarkRatePivotPct,
            double bookmarkRateCoef,
            int longTextMinChars,
            double longTextBonus,
            double mediaDwellBonus,
            double shareQuoteMaxPoints,
            double repostRatePivotPct,
            double repostRateCoef,
            double reachMultipleCap,
            double reachMultipleCoef,
            double quotePostBonus,
            double likeMaxPoints,
            double likeRatePivotPct,
            double likeRateCoef,
            double likeCountCap,
            double likeCountCoef,
            double compositeReplyWeight,
            double compositeRepostWeight,
            double compositeBookmarkWeight,
            double compositeLikeWeight,
            double compositeViewLogWeight,
            double compositeAuthorEngagedBonus
    ) {
        public static ScoringWeights defaults() {
            // 排序方向严格对齐 vm-ranker 正向动作优先级（reply=5.0/互关20.0, quote=5.0, share*=2~20, retweet=1.0, favorite=0.5）；
            // longTextBonus 与 mediaDwellBonus 默认置 0，杜绝无互动证据时仅凭字数或附件凭空加分。
            return new ScoringWeights(
                    18.0, 15, 99,
                    32.0, 1.2, 19.0, 12.0, 0.9, 5.0,
                    20.0, 0.8, 14.0, 140, 0.0, 0.0,
                    26.0, 0.8, 14.0, 11.0, 6.5, 3.0,
                    14.0, 2.5, 11.0, 4.0, 0.1,
                    5.0, 4.0, 3.0, 1.0, 2.5, 4.0
            );
        }

        public ScoringWeights normalizedWithDefaults(ScoringWeights fb) {
            int safeMin = Math.max(0, Math.min(50, minScore > 0 ? minScore : fb.minScore()));
            int safeMax = Math.max(safeMin + 10, Math.min(100, maxScore > 0 ? maxScore : fb.maxScore()));
            return new ScoringWeights(
                    finiteOrDefault(baseScore, fb.baseScore(), 0.0, 50.0),
                    safeMin,
                    safeMax,
                    finiteOrDefault(replyMaxPoints, fb.replyMaxPoints(), 0.0, 60.0),
                    positiveOrDefault(replyRatePivotPct, fb.replyRatePivotPct(), 0.05, 50.0),
                    finiteOrDefault(replyRateCoef, fb.replyRateCoef(), 0.0, 50.0),
                    finiteOrDefault(replyCountCap, fb.replyCountCap(), 0.0, 40.0),
                    finiteOrDefault(replyCountCoef, fb.replyCountCoef(), 0.0, 20.0),
                    finiteOrDefault(authorReplyEngagementBonus, fb.authorReplyEngagementBonus(), 0.0, 25.0),
                    finiteOrDefault(dwellBookmarkMaxPoints, fb.dwellBookmarkMaxPoints(), 0.0, 60.0),
                    positiveOrDefault(bookmarkRatePivotPct, fb.bookmarkRatePivotPct(), 0.05, 50.0),
                    finiteOrDefault(bookmarkRateCoef, fb.bookmarkRateCoef(), 0.0, 50.0),
                    longTextMinChars > 0 ? Math.min(2000, longTextMinChars) : fb.longTextMinChars(),
                    finiteOrDefault(longTextBonus, fb.longTextBonus(), 0.0, 25.0),
                    finiteOrDefault(mediaDwellBonus, fb.mediaDwellBonus(), 0.0, 25.0),
                    finiteOrDefault(shareQuoteMaxPoints, fb.shareQuoteMaxPoints(), 0.0, 60.0),
                    positiveOrDefault(repostRatePivotPct, fb.repostRatePivotPct(), 0.05, 50.0),
                    finiteOrDefault(repostRateCoef, fb.repostRateCoef(), 0.0, 50.0),
                    finiteOrDefault(reachMultipleCap, fb.reachMultipleCap(), 0.0, 40.0),
                    finiteOrDefault(reachMultipleCoef, fb.reachMultipleCoef(), 0.0, 30.0),
                    finiteOrDefault(quotePostBonus, fb.quotePostBonus(), 0.0, 20.0),
                    finiteOrDefault(likeMaxPoints, fb.likeMaxPoints(), 0.0, 60.0),
                    positiveOrDefault(likeRatePivotPct, fb.likeRatePivotPct(), 0.05, 50.0),
                    finiteOrDefault(likeRateCoef, fb.likeRateCoef(), 0.0, 50.0),
                    finiteOrDefault(likeCountCap, fb.likeCountCap(), 0.0, 40.0),
                    finiteOrDefault(likeCountCoef, fb.likeCountCoef(), 0.0, 20.0),
                    finiteOrDefault(compositeReplyWeight, fb.compositeReplyWeight(), 0.0, 30.0),
                    finiteOrDefault(compositeRepostWeight, fb.compositeRepostWeight(), 0.0, 30.0),
                    finiteOrDefault(compositeBookmarkWeight, fb.compositeBookmarkWeight(), 0.0, 30.0),
                    finiteOrDefault(compositeLikeWeight, fb.compositeLikeWeight(), 0.0, 30.0),
                    finiteOrDefault(compositeViewLogWeight, fb.compositeViewLogWeight(), 0.0, 30.0),
                    finiteOrDefault(compositeAuthorEngagedBonus, fb.compositeAuthorEngagedBonus(), 0.0, 30.0)
            );
        }

        public void validateStrict() {
            requireFiniteRange(baseScore, 0.0, 50.0, "scoring.baseScore");
            if (minScore < 0 || maxScore > 100 || minScore >= maxScore) {
                throw new IllegalArgumentException("scoring.minScore / maxScore 区间不合法");
            }
            requirePositiveRange(replyRatePivotPct, 0.05, 50.0, "scoring.replyRatePivotPct");
            requirePositiveRange(bookmarkRatePivotPct, 0.05, 50.0, "scoring.bookmarkRatePivotPct");
            requirePositiveRange(repostRatePivotPct, 0.05, 50.0, "scoring.repostRatePivotPct");
            requirePositiveRange(likeRatePivotPct, 0.05, 50.0, "scoring.likeRatePivotPct");
            requireFiniteRange(authorReplyEngagementBonus, 0.0, 25.0, "scoring.authorReplyEngagementBonus");
            requireFiniteRange(replyMaxPoints, 0.0, 60.0, "scoring.replyMaxPoints");
            requireFiniteRange(dwellBookmarkMaxPoints, 0.0, 60.0, "scoring.dwellBookmarkMaxPoints");
            requireFiniteRange(shareQuoteMaxPoints, 0.0, 60.0, "scoring.shareQuoteMaxPoints");
            requireFiniteRange(likeMaxPoints, 0.0, 60.0, "scoring.likeMaxPoints");
        }
    }

    /**
     * 本地公开样本观测阈值与发帖节律代理参数（注：externalLinkPenalty 默认 0，因 xai-value-model 中 open_link=+0.2 非固定负权重）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CadenceAndReachRules(
            long minRecommendedGapMinutes,
            long severeRapidFireGapMinutes,
            int severeRapidFirePenalty,
            int mildRapidFirePenalty,
            int externalLinkPenalty,
            int linkLossNoticePct,
            int linkLossWarnPct,
            int controversyMinReplies,
            double controversyReplyToPositiveRatio,
            int controversyPenalty,
            long oonMinViewsFloor,
            double oonFollowerMultiple,
            int lowReachMinFollowers,
            double lowReachFollowerRatio,
            int cliffDropPctThreshold
    ) {
        public static CadenceAndReachRules defaults() {
            return new CadenceAndReachRules(
                    180L, 60L, 12, 6,
                    0, 20, 25,
                    5, 1.8, 8,
                    150L, 1.0, 80, 0.05, -60
            );
        }

        public CadenceAndReachRules normalizedWithDefaults(CadenceAndReachRules fb) {
            long minGap = minRecommendedGapMinutes > 0 ? Math.min(1440L, minRecommendedGapMinutes) : fb.minRecommendedGapMinutes();
            long severeGap = severeRapidFireGapMinutes > 0 ? Math.min(minGap, severeRapidFireGapMinutes) : Math.min(minGap, fb.severeRapidFireGapMinutes());
            return new CadenceAndReachRules(
                    minGap,
                    severeGap,
                    Math.max(0, Math.min(50, severeRapidFirePenalty)),
                    Math.max(0, Math.min(50, mildRapidFirePenalty)),
                    Math.max(0, Math.min(50, externalLinkPenalty)),
                    Math.max(1, Math.min(95, linkLossNoticePct > 0 ? linkLossNoticePct : fb.linkLossNoticePct())),
                    Math.max(1, Math.min(95, linkLossWarnPct > 0 ? linkLossWarnPct : fb.linkLossWarnPct())),
                    Math.max(1, Math.min(100, controversyMinReplies > 0 ? controversyMinReplies : fb.controversyMinReplies())),
                    positiveOrDefault(controversyReplyToPositiveRatio, fb.controversyReplyToPositiveRatio(), 0.5, 20.0),
                    Math.max(0, Math.min(50, controversyPenalty)),
                    oonMinViewsFloor > 0 ? Math.min(1_000_000L, oonMinViewsFloor) : fb.oonMinViewsFloor(),
                    positiveOrDefault(oonFollowerMultiple, fb.oonFollowerMultiple(), 0.2, 50.0),
                    lowReachMinFollowers > 0 ? Math.min(100_000, lowReachMinFollowers) : fb.lowReachMinFollowers(),
                    positiveOrDefault(lowReachFollowerRatio, fb.lowReachFollowerRatio(), 0.001, 0.9),
                    cliffDropPctThreshold < 0 ? Math.max(-95, cliffDropPctThreshold) : fb.cliffDropPctThreshold()
            );
        }

        public void validateStrict() {
            if (minRecommendedGapMinutes <= 0 || minRecommendedGapMinutes > 1440) {
                throw new IllegalArgumentException("cadenceAndReach.minRecommendedGapMinutes 必须在 [1, 1440] 分钟内");
            }
            if (severeRapidFireGapMinutes <= 0 || severeRapidFireGapMinutes > minRecommendedGapMinutes) {
                throw new IllegalArgumentException("cadenceAndReach.severeRapidFireGapMinutes 必须大于 0 且不超过 minRecommendedGapMinutes");
            }
            if (severeRapidFirePenalty < 0 || mildRapidFirePenalty < 0 || externalLinkPenalty < 0 || controversyPenalty < 0) {
                throw new IllegalArgumentException("惩罚扣分值不能为负数");
            }
            requirePositiveRange(controversyReplyToPositiveRatio, 0.5, 20.0, "cadenceAndReach.controversyReplyToPositiveRatio");
            requirePositiveRange(oonFollowerMultiple, 0.2, 50.0, "cadenceAndReach.oonFollowerMultiple");
            requirePositiveRange(lowReachFollowerRatio, 0.001, 0.9, "cadenceAndReach.lowReachFollowerRatio");
            if (cliffDropPctThreshold >= 0 || cliffDropPctThreshold < -95) {
                throw new IllegalArgumentException("cadenceAndReach.cliffDropPctThreshold 必须在 [-95, -1] 范围内");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpsPlaybookRules(
            double targetReplyToPostRatioMin,
            double targetReplyToPostRatioMax,
            double overReplyLowPostRatio,
            int formatAdvantageThresholdPct,
            double highBookmarkRatePct,
            int maxRecommendations,
            double intimacyOutReplyWeight,
            double intimacyInReplyWeight,
            double intimacyMutualPairWeight,
            double intimacyQuoteWeight,
            double intimacyMutualFollowBonus,
            double intimacyUnrepliedBonus
    ) {
        public static OpsPlaybookRules defaults() {
            return new OpsPlaybookRules(
                    2.0, 6.0, 8.0, 25, 0.35, 10,
                    3.0, 4.2, 3.5, 2.8, 5.0, 3.0
            );
        }

        public OpsPlaybookRules normalizedWithDefaults(OpsPlaybookRules fb) {
            double rMin = positiveOrDefault(targetReplyToPostRatioMin, fb.targetReplyToPostRatioMin(), 0.2, 20.0);
            double rMax = Math.max(rMin, positiveOrDefault(targetReplyToPostRatioMax, fb.targetReplyToPostRatioMax(), 0.5, 30.0));
            return new OpsPlaybookRules(
                    rMin,
                    rMax,
                    Math.max(rMax, positiveOrDefault(overReplyLowPostRatio, fb.overReplyLowPostRatio(), 1.0, 50.0)),
                    Math.max(5, Math.min(200, formatAdvantageThresholdPct > 0 ? formatAdvantageThresholdPct : fb.formatAdvantageThresholdPct())),
                    positiveOrDefault(highBookmarkRatePct, fb.highBookmarkRatePct(), 0.05, 10.0),
                    Math.max(1, Math.min(16, maxRecommendations > 0 ? maxRecommendations : fb.maxRecommendations())),
                    finiteOrDefault(intimacyOutReplyWeight, fb.intimacyOutReplyWeight(), 0.0, 20.0),
                    finiteOrDefault(intimacyInReplyWeight, fb.intimacyInReplyWeight(), 0.0, 20.0),
                    finiteOrDefault(intimacyMutualPairWeight, fb.intimacyMutualPairWeight(), 0.0, 20.0),
                    finiteOrDefault(intimacyQuoteWeight, fb.intimacyQuoteWeight(), 0.0, 20.0),
                    finiteOrDefault(intimacyMutualFollowBonus, fb.intimacyMutualFollowBonus(), 0.0, 20.0),
                    finiteOrDefault(intimacyUnrepliedBonus, fb.intimacyUnrepliedBonus(), 0.0, 20.0)
            );
        }

        public void validateStrict() {
            requirePositiveRange(targetReplyToPostRatioMin, 0.2, 20.0, "opsPlaybook.targetReplyToPostRatioMin");
            requirePositiveRange(targetReplyToPostRatioMax, 0.5, 30.0, "opsPlaybook.targetReplyToPostRatioMax");
            if (maxRecommendations < 1 || maxRecommendations > 16) {
                throw new IllegalArgumentException("opsPlaybook.maxRecommendations 必须在 [1, 16] 之间");
            }
            requireFiniteRange(intimacyOutReplyWeight, 0.0, 20.0, "opsPlaybook.intimacyOutReplyWeight");
            requireFiniteRange(intimacyInReplyWeight, 0.0, 20.0, "opsPlaybook.intimacyInReplyWeight");
            requireFiniteRange(intimacyMutualPairWeight, 0.0, 20.0, "opsPlaybook.intimacyMutualPairWeight");
            requireFiniteRange(intimacyQuoteWeight, 0.0, 20.0, "opsPlaybook.intimacyQuoteWeight");
        }
    }

    /**
     * 发帖前算法管道预检规则（100% 映射上游真实过滤器与衰减机制，不含任何主观钩子词、标签个数或字数加减分）：
     * 1) leadingMentionOonFilterPenalty -> OONRetweetReplyFilter + SelfReplyChainFilter + oon_applies（句首 @ 被作回复处理，公域丢弃、关注内 0.75x、失去互关 +15.0 reply 加成）
     * 2) authorDiversityMaxPenalty / queueCollisionPenalty -> AgeFilter(48h) + scoring.rs diversity_multiplier((1-floor)*decay^k+floor)
     * 3) exactDuplicateTextPenalty / nearDuplicateTextPenalty / duplicateSimilarityThreshold -> Scarecrow BBQDuplicateTextProd(COPYPASTA_SPAM) + vm-ranker DPP(theta=0.65)
     * 4) urlWithNonFollowerMentionPenalty -> Scarecrow LQ_Tweets_With_LQ_URL_Verdict_At_Mention_To_NonFollower_v2 (bot/6754)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftCheckerRules(
            int baseScore,
            int minScore,
            int maxScore,
            int excellentThreshold,
            int goodThreshold,
            int leadingMentionOonFilterPenalty,
            int authorDiversityMaxPenalty,
            int queueCollisionPenalty,
            int exactDuplicateTextPenalty,
            int nearDuplicateTextPenalty,
            double duplicateSimilarityThreshold,
            int urlWithNonFollowerMentionPenalty
    ) {
        public static DraftCheckerRules defaults() {
            return new DraftCheckerRules(
                    100, 15, 100, 88, 70,
                    28,
                    26,
                    12,
                    35,
                    18,
                    0.65,
                    18
            );
        }

        public DraftCheckerRules normalizedWithDefaults(DraftCheckerRules fb) {
            int safeMin = Math.max(0, Math.min(50, minScore > 0 ? minScore : fb.minScore()));
            int safeMax = Math.max(safeMin + 10, Math.min(100, maxScore > 0 ? maxScore : fb.maxScore()));
            int safeGood = Math.max(safeMin, Math.min(safeMax, goodThreshold > 0 ? goodThreshold : fb.goodThreshold()));
            int safeExcel = Math.max(safeGood, Math.min(safeMax, excellentThreshold > 0 ? excellentThreshold : fb.excellentThreshold()));
            return new DraftCheckerRules(
                    Math.max(50, Math.min(100, baseScore > 0 ? baseScore : fb.baseScore())),
                    safeMin,
                    safeMax,
                    safeExcel,
                    safeGood,
                    Math.max(0, Math.min(60, leadingMentionOonFilterPenalty)),
                    Math.max(0, Math.min(60, authorDiversityMaxPenalty)),
                    Math.max(0, Math.min(40, queueCollisionPenalty)),
                    Math.max(0, Math.min(70, exactDuplicateTextPenalty)),
                    Math.max(0, Math.min(50, nearDuplicateTextPenalty)),
                    positiveOrDefault(duplicateSimilarityThreshold, fb.duplicateSimilarityThreshold(), 0.35, 0.98),
                    Math.max(0, Math.min(50, urlWithNonFollowerMentionPenalty))
            );
        }

        public void validateStrict() {
            if (baseScore < 50 || baseScore > 100) {
                throw new IllegalArgumentException("draftChecker.baseScore 必须在 [50, 100] 范围内");
            }
            if (minScore < 0 || maxScore > 100 || minScore >= maxScore) {
                throw new IllegalArgumentException("draftChecker.minScore / maxScore 区间不合法");
            }
            if (leadingMentionOonFilterPenalty < 0 || authorDiversityMaxPenalty < 0 || queueCollisionPenalty < 0
                    || exactDuplicateTextPenalty < 0 || nearDuplicateTextPenalty < 0 || urlWithNonFollowerMentionPenalty < 0) {
                throw new IllegalArgumentException("draftChecker 管道风险扣分项不能为负数");
            }
            requirePositiveRange(duplicateSimilarityThreshold, 0.35, 0.98, "draftChecker.duplicateSimilarityThreshold");
        }
    }
}
