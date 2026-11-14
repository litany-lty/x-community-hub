package com.xops.hub;

import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthAnalyticsTest {
    private static final Instant NOW = Instant.parse("2026-11-13T02:37:00Z");
    private static final XUserSummary SELF = user("self", 50);
    private static final XUserSummary PEER = user("peer", 100);

    @Test
    void knownRootVisitsStayDeduplicatedWhileMissingRootOwnershipIsExcluded() {
        var direct = reply("direct", "peer-root", "peer-root");
        var followup = reply("followup", "peer-root", "peer-comment");
        var unknown = reply("unknown", "", "peer-comment");
        var inboundUnknown = new InboundInteraction("unknown-in", PEER, NOW.minusSeconds(100),
                InboundKind.REPLY_TO_ME, "reply to an unidentified comment");
        var result = report(7, List.of(direct, followup, unknown), List.of(inboundUnknown));
        assertThat(result.interactions().peerStats()).hasSize(1);
        var peer = result.interactions().peerStats().get(0);
        assertThat(peer.myRepliesToPeer()).isEqualTo(1);
        assertThat(peer.peerRepliesToMe()).isZero();
        assertThat(result.activity().replies()).isEqualTo(3);
    }

    @Test
    void rollingBucketsUseTheSameOriginAndIncludeOnlyTheSnapshotInterval() {
        Instant start = NOW.minus(Duration.ofHours(48));
        var result = report(2, List.of(
                post("start", start, 10, "first"),
                post("natural-hour", start.plusSeconds(23 * 60), 20, "same rolling hour"),
                post("next-hour", start.plusSeconds(3600), 30, "second rolling hour"),
                post("next-chart-bucket", start.plusSeconds(7200), 40, "third rolling hour"),
                post("end", NOW, 50, "closed final endpoint"),
                post("before", start.minusNanos(1), 1000, "outside"),
                post("after", NOW.plusNanos(1), 1000, "outside")), List.of());
        var activity = result.activity();
        assertThat(activity.activeUnits()).isEqualTo(4);
        assertThat(activity.totalUnits()).isEqualTo(48);
        assertThat(activity.originalPosts()).isEqualTo(5);
        assertThat(activity.totalViews()).isEqualTo(150);
        var series = activity.trendSeries();
        assertThat(series).hasSize(24);
        assertThat(series.get(0).posts()).isEqualTo(3);
        assertThat(series.get(0).views()).isEqualTo(60);
        assertThat(series.get(1).posts()).isEqualTo(1);
        assertThat(series.get(23).posts()).isEqualTo(1);
        assertThat(series.stream().mapToInt(b -> b.posts()).sum()).isEqualTo(activity.originalPosts());
    }

    @Test
    void linkAndPostingCadenceAdviceSeparatesPublicObservationsFromPlatformClaims() {
        var result = report(7, List.of(
                post("native", NOW.minusSeconds(7200), 200, "native post"),
                post("linked", NOW.minusSeconds(6600), 20, "https://example.org/article")), List.of());
        var health = result.algorithmHealth();
        assertThat(health.healthSummary()).contains("样本", "无法判断").doesNotContain("账号未被限流");
        assertThat(health.diversityAdvice()).contains("无法据此确认");
        assertThat(health.contentRuleAdvice()).contains("不能据此认定外链造成差异");
        var card = result.contentTiming().topPosts().stream()
                .filter(c -> c.id().equals("native")).findFirst().orElseThrow();
        assertThat(card.followerReachMultiple()).isEqualTo(4.0);
        assertThat(card.reachTierLabel()).contains("浏览为粉丝数 4.0 倍");
        assertThat(card.algoTierLabel()).contains("本地启发式");
    }

    private static XUserSummary user(String name, int followers) {
        return new XUserSummary(name, name, name, "", "", false, followers, 10, 20, "2020-01-01");
    }

    private static RawTweet reply(String id, String root, String parent) {
        return new RawTweet(id, id, NOW.minusSeconds(100), TweetKind.REPLY,
                PEER.username(), PEER, List.of(), false, false, 10, 0, 0, 0, 0, false, root, parent);
    }

    private static RawTweet post(String id, Instant at, long views, String text) {
        return new RawTweet(id, text, at, TweetKind.POST, null, null, List.of(),
                false, false, views, 0, 0, 0, 0, false, id, "");
    }

    private static CommunityDashboardReport report(int days, List<RawTweet> tweets,
            List<InboundInteraction> inbound) {
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW,
                SELF, tweets, inbound, List.of());
        return new CommunityAnalyticsEngine().analyze(snapshot, days, ZoneId.of("Asia/Shanghai"), false);
    }
}
