package com.xops.hub;

import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.FollowAuditOverview;
import com.xops.hub.model.AnalyticsReportModels.PeerInteractionStat;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactFollowAnalyticsTest {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final XUserSummary SELF = user("self");
    private static final XUserSummary POSITIVE = user("known-positive");
    private static final XUserSummary NEGATIVE = user("known-negative");
    private static final XUserSummary UNKNOWN = user("unknown");

    @Test
    void mixedEvidencePreservesVerifiedPositiveAndNegativeWithoutTreatingUnknownAsNegative() {
        var result = report(List.of(
                new RelationshipEntry(POSITIVE, true, true, true),
                new RelationshipEntry(NEGATIVE, true, false, true),
                new RelationshipEntry(UNKNOWN, true, false)), false);
        var audit = result.followAudit();
        assertThat(audit.followersSampleTruncated()).isTrue();
        assertThat(audit.mutualList()).extracting(i -> i.user().username()).containsExactly(POSITIVE.username());
        assertThat(audit.notFollowingBackList()).extracting(i -> i.user().username()).containsExactly(NEGATIVE.username());
        assertThat(audit.suggestedUnfollowCount()).isEqualTo(1);
        assertThat(audit.unknownFollowBackCount()).isEqualTo(1);
        assertThat(audit.followBackRateComplete()).isFalse();
        assertThat(audit.followBackRateLabel()).contains("样本回关率", "未知关系");
        assertThat(peer(result, POSITIVE).followStateKnown()).isTrue();
        assertThat(peer(result, NEGATIVE).followStateKnown()).isTrue();
        assertThat(peer(result, UNKNOWN).followStateKnown()).isFalse();
        assertThat(peer(result, POSITIVE).relationshipPortrait()).contains("互关好友").doesNotContain("待核实");
        assertThat(peer(result, NEGATIVE).relationshipPortrait()).contains("未回关", "建议减少单向消耗");
        assertThat(peer(result, UNKNOWN).relationshipPortrait()).contains("关注关系待核实").doesNotContain("建议减少单向消耗");
    }

    @Test
    void allFollowedAccountsCanHaveCompleteRateWhileTheOverallFollowerListStillHasGaps() {
        var relationships = List.of(new RelationshipEntry(POSITIVE, true, true, true),
                new RelationshipEntry(NEGATIVE, true, false, true));
        var result = report(relationships, false);
        assertThat(result.followAudit().followersSampleTruncated()).isTrue();
        assertThat(result.followAudit().followBackRateComplete()).isTrue();
        assertThat(result.followAudit().followBackRatePct()).isEqualTo(50.0);
        assertThat(result.followAudit().followBackRateLabel()).contains("关注回关率 50.0%", "粉丝名单未完整")
                .doesNotContain("样本回关率", "含未知关系");
        assertThat(result.innerCircle().shareCard().followBackRateLabel()).isEqualTo(result.followAudit().followBackRateLabel());
        assertThat(report(relationships, true).followAudit().followBackRateComplete()).isFalse();
    }

    @Test
    void verifiedRecordOverridesStaleListEvidenceInEitherInputOrder() {
        var stale = new RelationshipEntry(NEGATIVE, true, true);
        var verified = new RelationshipEntry(NEGATIVE, true, false, true);
        for (var relationships : List.of(List.of(stale, verified), List.of(verified, stale))) {
            var result = report(relationships, false);
            assertThat(result.followAudit().notFollowingBackList()).extracting(i -> i.user().username())
                    .containsExactly(NEGATIVE.username());
            assertThat(result.followAudit().mutualCount()).isZero();
            assertThat(result.followAudit().unknownFollowBackCount()).isZero();
            assertThat(peer(result, NEGATIVE).theyFollowMe()).isFalse();
            assertThat(peer(result, NEGATIVE).relationshipPortrait()).contains("未回关");
        }
    }

    @Test
    void verifiedFanDoesNotLoseKnownFollowingAbsenceWhenFollowingListIsIncomplete() {
        var result = report(List.of(new RelationshipEntry(POSITIVE, false, true, true),
                new RelationshipEntry(UNKNOWN, false, true)), true);
        assertThat(result.followAudit().fansNotFollowedBackList()).extracting(i -> i.user().username())
                .containsExactly(POSITIVE.username());
        assertThat(result.followAudit().unknownFollowingCount()).isEqualTo(1);
        assertThat(peer(result, POSITIVE).relationshipPortrait()).contains("关注我的粉丝(未回关)");
        assertThat(peer(result, UNKNOWN).relationshipPortrait()).contains("关注关系待核实");
    }

    @Test
    void previousOverviewConstructorKeepsTheRateCompletenessContract() {
        var overview = new FollowAuditOverview(2, 1, true, 1, 1, 0, 1, 0, 50.0,
                List.of(), List.of(), List.of(), false, 0, 0, "关注回关率 50.0%");
        assertThat(overview.followBackRateComplete()).isTrue();
    }

    private static XUserSummary user(String name) {
        return new XUserSummary(name, name, name, "", "", false, 100, 3, 20, "2020-01-01");
    }

    private static PeerInteractionStat peer(CommunityDashboardReport result, XUserSummary user) {
        return result.interactions().peerStats().stream().filter(p -> p.peer().id().equals(user.id()))
                .findFirst().orElseThrow();
    }

    private static CommunityDashboardReport report(List<RelationshipEntry> relationships, boolean followingsTruncated) {
        var tweets = relationships.stream().map(RelationshipEntry::user).distinct().map(peer ->
                new RawTweet("reply-" + peer.id(), "reply", NOW.minusSeconds(100), TweetKind.REPLY,
                        peer.username(), peer, List.of(), false, false, 10, 0, 0, 0, 0,
                        false, "root-" + peer.id(), "root-" + peer.id())).toList();
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, SELF,
                tweets, List.of(), relationships, true, false, followingsTruncated);
        return new CommunityAnalyticsEngine().analyze(snapshot, 7, ZoneId.of("Asia/Shanghai"), false);
    }
}
