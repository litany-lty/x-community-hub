package com.xops.hub;

import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.EmpiricalAlgoCalibration;
import com.xops.hub.model.AnalyticsReportModels.OpsRecommendationItem;
import com.xops.hub.model.AnalyticsReportModels.ResidualAnomalyPost;
import com.xops.hub.model.RawSnapshotModels.ProviderMode;
import com.xops.hub.model.RawSnapshotModels.RawAccountSnapshot;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResidualReviewScopeTest {
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void currentPeriodBoundaryKeepsHistoryOnlyAsBaseline() {
        CommunityDashboardReport atBoundary = reportWith(candidate("boundary", NOW.minus(Duration.ofHours(24)), 120));
        assertThat(atBoundary.activity().periodCode()).isEqualTo("HOURS_24");
        assertThat(atBoundary.algorithmHealth().calibration().samplePostCount()).isEqualTo(7);
        assertThat(atBoundary.algorithmHealth().calibration().medianViews()).isEqualTo(1000);
        assertThat(atBoundary.algorithmHealth().calibration().residualAnomalies())
                .extracting(ResidualAnomalyPost::id).containsExactly("boundary");

        CommunityDashboardReport outside = reportWith(candidate("old", NOW.minus(Duration.ofHours(24)).minusSeconds(1), 120));
        assertThat(outside.algorithmHealth().calibration().samplePostCount()).isEqualTo(7);
        assertThat(outside.algorithmHealth().calibration().residualAnomalies()).isEmpty();
        assertThat(reviews(outside)).isEmpty();

        CommunityDashboardReport future = reportWith(candidate("future", NOW.plusSeconds(1), 120));
        assertThat(future.algorithmHealth().calibration().samplePostCount()).isEqualTo(6);
        assertThat(future.algorithmHealth().calibration().residualAnomalies()).isEmpty();
    }

    @Test
    void sparseViewsAndPriorOnlyInteractionDoNotProduceReviewCards() {
        for (long views : List.of(8L, 99L)) {
            CommunityDashboardReport sparse = reportWith(candidate("sparse", NOW.minus(Duration.ofHours(6)), views));
            assertThat(sparse.algorithmHealth().calibration().predictionUnlocked()).isTrue();
            assertThat(sparse.algorithmHealth().calibration().residualAnomalies()).isEmpty();
            assertThat(reviews(sparse)).isEmpty();
        }

        List<RawTweet> posts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            posts.add(post("history-" + i, NOW.minus(Duration.ofDays(3)).minus(Duration.ofHours(i * 4L)),
                    1000, i == 5 ? 200 : 1, i == 5 ? 100 : 0, i == 5 ? 10 : 0));
        }
        posts.add(post("prior-only", NOW.minus(Duration.ofHours(6)), 100, 0, 0, 0));
        CommunityDashboardReport priorOnly = report(posts);
        EmpiricalAlgoCalibration calibration = priorOnly.algorithmHealth().calibration();
        double smoothed = calibration.corpusSamples().stream()
                .filter(sample -> sample.id().equals("prior-only")).findFirst().orElseThrow()
                .realizedVmUtilityPer1k();
        assertThat(smoothed).isGreaterThan(calibration.medianVmUtilityPer1k());
        assertThat(calibration.residualAnomalies()).isEmpty();
        assertThat(reviews(priorOnly)).isEmpty();
    }

    @Test
    void reviewRequiresEnoughNonRapidHistoricalSamples() {
        List<RawTweet> tooFew = historicalPosts(4);
        tooFew.add(candidate("candidate", NOW.minus(Duration.ofHours(6)), 120));
        assertThat(report(tooFew).algorithmHealth().calibration().residualAnomalies()).isEmpty();

        List<RawTweet> rapidHistory = historicalPosts(5);
        rapidHistory.set(0, post("rapid-history", rapidHistory.get(1).createdAt().plusSeconds(1800),
                1000, 20, 4, 2));
        rapidHistory.add(candidate("candidate", NOW.minus(Duration.ofHours(6)), 120));
        CommunityDashboardReport insufficientIndependentSamples = report(rapidHistory);
        assertThat(insufficientIndependentSamples.algorithmHealth().calibration().predictionUnlocked()).isTrue();
        assertThat(insufficientIndependentSamples.algorithmHealth().calibration().residualAnomalies()).isEmpty();
    }

    @Test
    void newlyPublishedPostWaitsForMinimumObservationWindow() {
        CommunityDashboardReport early = reportWith(candidate("early", NOW.minus(Duration.ofHours(3)).plusSeconds(1), 120));
        assertThat(early.algorithmHealth().calibration().residualAnomalies()).isEmpty();
        assertThat(reviews(early)).isEmpty();

        CommunityDashboardReport observed = reportWith(candidate("observed", NOW.minus(Duration.ofHours(3)), 120));
        assertThat(observed.algorithmHealth().calibration().residualAnomalies())
                .extracting(ResidualAnomalyPost::id).containsExactly("observed");
    }

    @Test
    void matureCurrentCandidateUsesRecordedFactsAndADirectPostLink() {
        CommunityDashboardReport report = reportWith(candidate("current", NOW.minus(Duration.ofHours(6)), 100));
        EmpiricalAlgoCalibration calibration = report.algorithmHealth().calibration();
        assertThat(calibration.residualAnomalies()).hasSize(1);
        ResidualAnomalyPost post = calibration.residualAnomalies().get(0);
        assertThat(post.actualViews()).isEqualTo(100);
        assertThat(post.actualViews()).isLessThan(calibration.medianViews());
        assertThat(post.reasonHint()).isEqualTo("已采集浏览 100，历史单帖中位数 1000。");

        assertThat(reviews(report)).hasSize(1);
        OpsRecommendationItem review = reviews(report).get(0);
        assertThat(review.priority()).isEqualTo("LOW");
        assertThat(review.signalTag()).isEqualTo("复盘");
        assertThat(review.title()).isEqualTo("浏览量低于历史常态");
        assertThat(review.detail()).contains(post.createdLabel(), post.textPreview(),
                "已采集浏览 100", "历史单帖中位数 1000");
        assertThat(review.actionTarget()).isNull();
        assertThat(review.externalUrl()).isEqualTo("https://x.com/review_user/status/current");
        assertThat(review.detail() + post.reasonHint())
                .doesNotContain("互动热烈", "互动很高", "敏感词", "短链", "限流", "下界", "通常可达", "not_interested", "mute");
        assertThat(report.algorithmHealth().opsRecommendations())
                .extracting(OpsRecommendationItem::code).doesNotContain("RESIDUAL_ANOMALY_REVIEW");
    }

    @Test
    void matchingHistoricalMedianIsNotLabeledAsLower() {
        CommunityDashboardReport report = reportWith(candidate("equal", NOW.minus(Duration.ofHours(6)), 1000));
        assertThat(report.algorithmHealth().calibration().medianViews()).isEqualTo(1000);
        assertThat(report.algorithmHealth().calibration().residualAnomalies()).isEmpty();
        assertThat(reviews(report)).isEmpty();
    }

    private static List<OpsRecommendationItem> reviews(CommunityDashboardReport report) {
        return report.algorithmHealth().opsRecommendations().stream()
                .filter(item -> item.code().equals("REVIEW_LOW_VIEW_POST")).toList();
    }

    private static CommunityDashboardReport reportWith(RawTweet candidate) {
        List<RawTweet> posts = historicalPosts(6);
        posts.add(candidate);
        return report(posts);
    }

    private static List<RawTweet> historicalPosts(int count) {
        List<RawTweet> posts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            posts.add(post("history-" + i, NOW.minus(Duration.ofDays(3)).minus(Duration.ofHours(i * 4L)),
                    1000, 20, 4, 2));
        }
        return posts;
    }

    private static RawTweet candidate(String id, Instant createdAt, long views) {
        return post(id, createdAt, views, 10, 3, 1);
    }

    private static RawTweet post(String id, Instant createdAt, long views, int likes, int replies, int reposts) {
        return new RawTweet(id, "这是一条需要复盘的实际推文 " + id, createdAt,
                TweetKind.POST, null, null, List.of(), false, false,
                views, likes, replies, reposts, 0, false, id, "");
    }

    private static CommunityDashboardReport report(List<RawTweet> posts) {
        XUserSummary self = new XUserSummary("review-user-id", "review_user", "Review user", "", "",
                false, 200, 10, posts.size(), "2020-01-01");
        RawAccountSnapshot snapshot = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", NOW, self,
                posts, List.of(), List.of(),
                false, false, 30, null, "", true, null, "", true, false);
        return new CommunityAnalyticsEngine().analyze(snapshot,
                CommunityAnalyticsEngine.ReportPeriod.HOURS_24, false, ZONE, false,
                3600L, NOW.plusSeconds(3600));
    }
}
