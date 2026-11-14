package com.xops.hub;

import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsAuditRegressionTest {
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static XUserSummary user(String name) {
        return user(name, "2020-01-01");
    }

    private static XUserSummary user(String name, String joinedDate) {
        return new XUserSummary(name, name, name, "", "", false, 100, 10, 20, joinedDate);
    }

    private static RawTweet tweet(String id, Instant at, TweetKind kind, String peer, List<String> mentions) {
        return new RawTweet(id, id, at, kind, peer, peer == null ? null : user(peer), mentions,
                false, false, 100, 1, 0, 0, 0, false,
                kind == TweetKind.REPLY ? "peer-root-" + id : id,
                kind == TweetKind.REPLY ? "peer-root-" + id : "");
    }

    private static CommunityDashboardReport report(Instant now, XUserSummary profile,
            List<RawTweet> timeline, List<InboundInteraction> inbound, List<RelationshipEntry> relationships,
            boolean followersTruncated, boolean followingsTruncated) {
        int followingSample = (int) relationships.stream().filter(RelationshipEntry::iFollowThem).count();
        var snapshotProfile = new XUserSummary(profile.id(), profile.username(), profile.displayName(),
                profile.avatarUrl(), profile.bio(), profile.verified(), profile.followersCount(),
                followingSample, profile.totalPostsCount(), profile.joinedDate());
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", now, snapshotProfile,
                timeline, inbound, relationships, followersTruncated, false,
                30, null, "", true, null, "", true, followingsTruncated);
        return new CommunityAnalyticsEngine().analyze(snapshot, 7, ZONE, false);
    }

    private static CommunityDashboardReport report(List<RawTweet> timeline,
            List<InboundInteraction> inbound, List<RelationshipEntry> relationships,
            boolean followersTruncated, boolean followingsTruncated) {
        return report(NOW, user("self"), timeline, inbound, relationships,
                followersTruncated, followingsTruncated);
    }

    @Test
    void unknownFollowBackMustNotCountAsSuggestedUnfollow() {
        var result = report(
                List.of(tweet("reply", NOW.minusSeconds(100), TweetKind.REPLY, "peer", List.of())),
                List.of(), List.of(new RelationshipEntry(user("peer"), true, false)), true, false);
        assertThat(result.followAudit().suggestedUnfollowCount()).isZero();
        assertThat(result.followAudit().notFollowingBackCount()).isZero();
        assertThat(result.followAudit().notFollowingBackList()).isEmpty();
        assertThat(result.followAudit().unknownFollowBackCount()).isEqualTo(1);
        assertThat(result.interactions().peerStats().get(0).relationshipPortrait())
                .contains("关注关系待核实").doesNotContain("建议减少单向消耗");
        assertThat(result.innerCircle().shareCard().followBackRateLabel()).contains("样本回关率", "未知关系");
        assertThat(result.innerCircle().shareCard().shareTweetText()).contains("样本回关率", "未知关系")
                .doesNotContain("关注回关率");
    }

    @Test
    void newerMentionMustNotDisappearBeforeOlderReplyCreatesPeer() {
        var result = report(List.of(
                tweet("mention", NOW.minusSeconds(100), TweetKind.POST, null, List.of("peer")),
                tweet("reply", NOW.minusSeconds(200), TweetKind.REPLY, "peer", List.of())),
                List.of(), List.of(), false, false);
        assertThat(result.interactions().peerStats()).hasSize(1);
        assertThat(result.interactions().peerStats().get(0).myMentionsOfPeer()).isEqualTo(1);
    }

    @Test
    void inboundMentionBeforeReplyMustAlsoBeCounted() {
        var result = report(List.of(), List.of(
                new InboundInteraction("mention", user("peer"), NOW.minusSeconds(100), InboundKind.MENTION_ME, "hi"),
                new InboundInteraction("reply", user("peer"), NOW.minusSeconds(200), InboundKind.REPLY_TO_ME, "reply",
                        "self-root", "self-root", "self")),
                List.of(), false, false);
        assertThat(result.interactions().peerStats()).hasSize(1);
        assertThat(result.interactions().peerStats().get(0).peerMentionsOfMe()).isEqualTo(1);
    }

    @Test
    void mentionsAloneMustNotEnterCommentAndQuoteLeaderboard() {
        var result = report(
                List.of(tweet("mention", NOW.minusSeconds(100), TweetKind.POST, null, List.of("outboundPeer"))),
                List.of(new InboundInteraction("in", user("inboundPeer"), NOW.minusSeconds(100), InboundKind.MENTION_ME, "hi")),
                List.of(), false, false);
        assertThat(result.interactions().peerStats()).isEmpty();
        assertThat(result.interactions().totalInteractedPeers()).isZero();
    }

    @Test
    void truncatedFollowingsMustNotRecommendFollowingFansAlreadyPossiblyFollowed() {
        var relationships = List.of(
                new RelationshipEntry(user("fan"), false, true),
                new RelationshipEntry(user("mutual"), true, true));
        var sampled = report(List.of(), List.of(), relationships, false, true);
        assertThat(sampled.followAudit().followingsSampleTruncated()).isTrue();
        assertThat(sampled.followAudit().unknownFollowingCount()).isEqualTo(1);
        assertThat(sampled.followAudit().fansNotFollowedBackCount()).isZero();
        assertThat(sampled.followAudit().fansNotFollowedBackList()).isEmpty();
        assertThat(sampled.followAudit().mutualCount()).isEqualTo(1);
        assertThat(sampled.innerCircle().shareCard().shareTweetText()).contains("样本回关率", "关注名单未完整");

        var complete = report(List.of(), List.of(), relationships, false, false);
        assertThat(complete.followAudit().fansNotFollowedBackCount()).isEqualTo(1);
        assertThat(complete.followAudit().unknownFollowingCount()).isZero();
        assertThat(complete.innerCircle().shareCard().followBackRateLabel()).isEqualTo("关注回关率 100.0%");
    }

    @Test
    void accountFromLastDecemberIsNotOneYearOldInJanuary() {
        var now = Instant.parse("2026-01-10T00:00:00Z");
        var result = ageReport("2025-12-20T00:00:00Z", now);
        assertThat(result.algorithmHealth().underTheHoodEligible()).isFalse();
    }

    @Test
    void accountAgeMustReachTheFullAnniversary() {
        assertThat(ageReport("2025-11-14", NOW).algorithmHealth().underTheHoodEligible()).isFalse();
        assertThat(ageReport("2025-11-13", NOW).algorithmHealth().underTheHoodEligible()).isTrue();
        assertThat(ageReport("Thu Nov 13 00:00:00 +0000 2025", NOW)
                .algorithmHealth().underTheHoodEligible()).isTrue();
        String singleDigitTwitterDate = "Tue Nov 5 00:00:00 +0000 2024";
        assertThat(ageReport(singleDigitTwitterDate, Instant.parse("2025-11-04T08:00:00Z"))
                .algorithmHealth().underTheHoodEligible()).isFalse();
        assertThat(ageReport(singleDigitTwitterDate, Instant.parse("2025-11-05T08:00:00Z"))
                .algorithmHealth().underTheHoodEligible()).isTrue();
    }

    @Test
    void missingOrIncompleteJoinedDateCannotConfirmEligibility() {
        for (String joinedDate : new String[]{null, "", "Joined 2020", "2025-02-30"}) {
            var health = ageReport(joinedDate, NOW).algorithmHealth();
            assertThat(health.underTheHoodEligible()).as("joined date: %s", joinedDate).isFalse();
            assertThat(health.underTheHoodHint()).contains("注册日期缺失或无法解析", "无法核验");
        }
    }

    private static CommunityDashboardReport ageReport(String joinedDate, Instant now) {
        var posts = IntStream.range(0, 10)
                .mapToObj(i -> tweet("post-" + i, now.minusSeconds(100 + i), TweetKind.POST, null, List.of()))
                .toList();
        return report(now, user("self", joinedDate), posts, List.of(), List.of(), false, false);
    }
}
