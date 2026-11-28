package com.xops.hub;

import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionalBusinessGapTest {
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");
    private static final XUserSummary SELF = user("self");
    private static final XUserSummary PEER = user("peer");

    @Test
    void quoteOnlyInteractionNeverBecomesACommentVisitOrUnfollowSuggestion() {
        var result = report(List.of(tweet("quote", NOW.minusSeconds(100), TweetKind.QUOTE, "", 100)),
                List.of(), List.of(new RelationshipEntry(PEER, true, false)), false);
        var item = result.followAudit().notFollowingBackList().get(0);
        assertThat(item.myRepliesToThem()).isZero();
        assertThat(item.theirRepliesToMe()).isZero();
        assertThat(item.totalInteractions()).isEqualTo(1);
        assertThat(item.auditLabel()).contains("引用");
        assertThat(item.recommendation()).doesNotContain("你去评 TA 1 帖", "零互动");
        assertThat(result.followAudit().suggestedUnfollowCount()).isZero();
    }

    @Test
    void incompleteActivityNeverJustifiesCleaningAFollowAndStaysDisclosedInSharing() {
        var result = report(List.of(), List.of(), List.of(new RelationshipEntry(PEER, true, false)), true);
        assertThat(result.activity().sampleCapped()).isTrue();
        assertThat(result.followAudit().notFollowingBackCount()).isEqualTo(1);
        assertThat(result.followAudit().suggestedUnfollowCount()).isZero();
        var item = result.followAudit().notFollowingBackList().get(0);
        assertThat(item.auditCategory()).isEqualTo("ACTIVITY_INCOMPLETE");
        assertThat(item.recommendation()).contains("覆盖未完整").doesNotContain("可优先清理");
        assertThat(result.activity().personaTag()).contains("样本");
        assertThat(result.activity().summaryAdvice()).contains(result.activity().coverageNote());
        assertThat(result.innerCircle().shareCard().periodLabel()).contains("样本");
        assertThat(result.innerCircle().shareCard().shareTweetText()).contains(result.activity().coverageNote());
    }

    @Test
    void accumulatedWindowsKeepObservedVisitsWithoutSuggestingUnfollowAcrossCoverageGaps() {
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, SELF,
                List.of(tweet("visited", NOW.minusSeconds(4 * 3600), TweetKind.REPLY, "external-root", 10)),
                List.of(), List.of(new RelationshipEntry(PEER, true, false),
                        new RelationshipEntry(user("quiet"), true, false)), false, true, 0,
                NOW.minusSeconds(86400), "", false, NOW.minusSeconds(7200), "", false, false);
        var report = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                true, ZoneId.of("Asia/Shanghai"), false, 0, NOW);

        assertThat(report.interactions().totalOutboundActions()).isEqualTo(1);
        assertThat(report.followAudit().suggestedUnfollowCount()).isZero();
        assertThat(report.followAudit().notFollowingBackList()).hasSize(2)
                .allSatisfy(item -> assertThat(item.auditCategory()).isEqualTo("ACTIVITY_INCOMPLETE"));
        assertThat(report.interactions().peerStats().get(0).relationshipPortrait()).doesNotContain("建议减少单向消耗");
    }

    @Test
    void pendingCommentRemainsInWarmupEvenWhenMyVisitsAreMoreNumerous() {
        var result = report(List.of(
                tweet("out-a", NOW.minusSeconds(500), TweetKind.REPLY, "peer-root-a", 10),
                tweet("out-b", NOW.minusSeconds(400), TweetKind.REPLY, "peer-root-b", 10)),
                List.of(new InboundInteraction("in", PEER, NOW.minusSeconds(100), InboundKind.REPLY_TO_ME,
                        "question", "my-root", "my-root", "self")), List.of(), false);
        assertThat(result.interactions().peerStats().get(0).unrepliedInMyPostCount()).isEqualTo(1);
        assertThat(result.innerCircle().needWarmupPeers()).extracting(m -> m.user().username()).containsExactly("peer");
    }

    @Test
    void postsWithoutAnyViewEvidenceRemainVisibleWithoutInventedMetrics() {
        var result = report(List.of(metricPost("unobserved", 0, 0, Set.of("views"))),
                List.of(), List.of(), false);
        assertThat(result.contentTiming().topPosts()).hasSize(1);
        assertThat(result.contentTiming().formatStats().get(0).viewSampleCount()).isZero();
    }

    @Test
    void dailyPaceUsesTheActualPartialDayRatherThanPretendingItWasAWholeDay() {
        var result = report(List.of(
                tweet("a", NOW.minusSeconds(100), TweetKind.POST, "", 100),
                tweet("b", NOW.minusSeconds(200), TweetKind.POST, "", 100)), List.of(), List.of(), true);
        assertThat(result.activity().dailyAvgPosts()).isEqualTo(48.0);
        assertThat(result.activity().summaryAdvice()).contains("样本");
    }

    @Test
    void aTwoWayPartnerIsNotRemovedFromTheActiveCircleByBeingACoreFan() {
        var result = report(List.of(tweet("out", NOW.minusSeconds(200), TweetKind.REPLY, "peer-root", 10)),
                List.of(new InboundInteraction("in", PEER, NOW.minusSeconds(100), InboundKind.REPLY_TO_ME,
                        "hello", "my-root", "my-root", "self")), List.of(), false);
        assertThat(result.innerCircle().coreFans()).extracting(m -> m.user().username()).containsExactly("peer");
        assertThat(result.innerCircle().activeMutuals()).extracting(m -> m.user().username()).containsExactly("peer");
    }

    @Test
    void unclassifiedThreadRepliesAreNotCalledZeroInteractionOrUsedToSuggestCleaning() {
        var result = report(List.of(tweet("unknown-root", NOW.minusSeconds(100), TweetKind.REPLY, "", 10)),
                List.of(), List.of(new RelationshipEntry(PEER, true, false)), false);
        assertThat(result.interactions().peerStats()).isEmpty();
        var item = result.followAudit().notFollowingBackList().get(0);
        assertThat(item.myRepliesToThem()).isZero();
        assertThat(item.auditCategory()).isEqualTo("OTHER_INTERACTION");
        assertThat(item.recommendation()).contains("已观察到").doesNotContain("无任何", "优先清理");
        assertThat(result.followAudit().suggestedUnfollowCount()).isZero();
    }

    @Test
    void missingViewsDoNotDiluteKnownViewMeansOrCreateAnErDenominator() {
        var result = report(List.of(metricPost("known", 100, 10, Set.of()),
                metricPost("unknown-views", 0, 20, Set.of("views"))), List.of(), List.of(), false);
        assertThat(result.activity().originalPosts()).isEqualTo(2);
        assertThat(result.activity().avgViewsPerPost()).isEqualTo(100);
        assertThat(result.activity().mainPostViewSampleCount()).isEqualTo(1);
        assertThat(result.activity().totalViews()).isEqualTo(100);
        assertThat(result.activity().avgEngagementRatePct()).isEqualTo(10.0);
        assertThat(result.activity().engagementRateSampleCount()).isEqualTo(1);
        var format = result.contentTiming().formatStats().get(0);
        assertThat(format.postCount()).isEqualTo(2);
        assertThat(format.avgViews()).isEqualTo(100);
        assertThat(format.viewSampleCount()).isEqualTo(1);
    }

    @Test
    void missingLikesAreNeitherZeroLikesNorZeroErAndCannotEarnAScoreOrRank() {
        var result = report(List.of(metricPost("known", 100, 10, Set.of()),
                metricPost("unknown-likes", 900, 0, Set.of("likes"))), List.of(), List.of(), false);
        assertThat(result.activity().avgViewsPerPost()).isEqualTo(500);
        assertThat(result.activity().avgLikesPerPost()).isEqualTo(10.0);
        assertThat(result.activity().mainPostLikeSampleCount()).isEqualTo(1);
        assertThat(result.activity().avgEngagementRatePct()).isEqualTo(10.0);
        assertThat(result.activity().engagementRateSampleCount()).isEqualTo(1);
        assertThat(result.activity().metricsNote()).contains("未知值未按0计入");
        var cards = result.contentTiming().topPosts();
        assertThat(cards.get(0).id()).isEqualTo("known");
        assertThat(cards.get(1).unavailableMetrics()).containsExactly("likes");
        assertThat(cards.get(1).algoScore()).isEqualTo(-1);
        var unknownOnly = report(List.of(metricPost("unknown", 900, 0, Set.of("likes"))),
                List.of(), List.of(), false);
        assertThat(unknownOnly.activity().engagementRateSampleCount()).isZero();
        assertThat(unknownOnly.innerCircle().shareCard().shareTweetText()).contains("平均互动率 --");
        assertThat(unknownOnly.contentTiming().formatStats().get(0).engagementRateSampleCount()).isZero();
    }

    @Test
    void aKnownZeroViewIsPreservedButHasNoDefinedErUnlikeAnUnknownMetric() {
        var result = report(List.of(metricPost("zero", 0, 0, Set.of()),
                metricPost("unknown", 0, 0, RawTweet.ALL_METRICS)), List.of(), List.of(), false);
        assertThat(result.activity().originalPosts()).isEqualTo(2);
        assertThat(result.activity().totalViews()).isZero();
        assertThat(result.activity().viewSampleCount()).isEqualTo(1);
        assertThat(result.activity().mainPostViewSampleCount()).isEqualTo(1);
        assertThat(result.activity().engagementRateSampleCount()).isZero();
        assertThat(result.contentTiming().topPosts()).filteredOn(p -> p.id().equals("zero"))
                .allSatisfy(p -> assertThat(p.unavailableMetrics()).isEmpty());
        assertThat(result.contentTiming().formatStats().get(0).viewSampleCount()).isEqualTo(1);
        assertThat(result.contentTiming().formatStats().get(0).engagementRateSampleCount()).isZero();
        assertThat(result.innerCircle().shareCard().shareTweetText()).contains("平均互动率 --");
    }

    private static RawTweet metricPost(String id, long views, int likes, Set<String> unavailableMetrics) {
        return new RawTweet(id, id, NOW.minusSeconds(100), TweetKind.POST, null, null,
                List.of(), false, false, views, likes, 0, 0, 0, false, id, "", unavailableMetrics);
    }

    private static XUserSummary user(String name) {
        return new XUserSummary(name, name, name, "", "", false, 100, 1, 20, "2020-01-01");
    }

    private static RawTweet tweet(String id, Instant at, TweetKind kind, String root, long views) {
        boolean targeted = kind == TweetKind.REPLY || kind == TweetKind.QUOTE;
        return new RawTweet(id, id, at, kind, targeted ? "peer" : null, targeted ? PEER : null,
                List.of(), false, false, views, 0, 0, 0, 0, false,
                kind == TweetKind.REPLY ? root : id, kind == TweetKind.REPLY ? root : "");
    }

    private static CommunityDashboardReport report(List<RawTweet> timeline, List<InboundInteraction> inbound,
            List<RelationshipEntry> relationships, boolean partial) {
        Instant tail = partial ? NOW.minusSeconds(3600) : null;
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, SELF,
                timeline, inbound, relationships, false, false, partial ? 0 : 30,
                tail, "", !partial, tail, "", !partial, false);
        return new CommunityAnalyticsEngine().analyze(snapshot, 7, ZoneId.of("Asia/Shanghai"), false);
    }
}
