package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.AnalyticsReportModels.ContentTimingOverview;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RepresentativePostRankingTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final XUserSummary OWNER = new XUserSummary("1", "owner", "Owner", "", "", false, 100, 0, 0, "");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void everyFormatAndMetricRanksThePeriodBeforeTakingTwentyFour() {
        var posts = new ArrayList<RawTweet>();
        for (int i = 1; i <= 100; i++) posts.add(post("high-" + i, i, false, false, 5000, 5000, 0, 0, Set.of()));
        posts.add(post("views", 200, false, false, 1_000_000, 0, 0, 0, Set.of()));
        posts.add(post("bookmarks", 201, false, false, 1000, 0, 0, 10, Set.of()));
        posts.add(post("replies", 202, false, false, 1000, 0, 10, 0, Set.of()));
        posts.add(post("media", 203, true, false, 100, 1, 0, 0, Set.of()));
        posts.add(post("unknown", 205, true, true, 0, 0, 0, 0, Set.of("views")));
        posts.add(post("zero", 206, true, true, 0, 0, 0, 0, Set.of()));
        posts.add(post("outside", 86401, false, false, 20_000_000, 50000, 0, 0, Set.of()));
        JsonNode timing = report(posts, null, true, null, true);
        assertThat(timing.path("topPosts")).hasSize(24);
        assertThat(timing.path("topPosts").get(0).path("id").asText()).isEqualTo("high-1");
        assertThat(ranked(timing, "ALL", "views")).hasSize(24).startsWith("views").doesNotContain("outside");
        assertThat(ranked(timing, "TEXT", "bookmarks")).startsWith("bookmarks");
        assertThat(ranked(timing, "TEXT", "replies")).startsWith("replies");
        assertThat(ranked(timing, "MEDIA", "composite")).containsExactly("media");
        assertThat(ranked(timing, "THREAD", "views")).containsExactly("zero", "unknown");
        assertThat(ranked(timing, "ALL", "composite")).containsExactlyElementsOf(
                timing.path("topPosts").findValuesAsText("id"));
        assertThat(timing.path("postRankings").path("additionalPosts").size()).isLessThan(20);
    }

    @Test void anObservedPostBeforeTheCommonCoverageWindowStillBelongsToTheSelectedPeriod() {
        var posts = List.of(post("earlier", 7200, true, false, 20, 1, 0, 0, Set.of()),
                post("future", -30, true, false, 999, 0, 0, 0, Set.of()));
        JsonNode timing = report(posts, NOW.minusSeconds(600), false, NOW.minusSeconds(300), false);
        assertThat(timing.path("topPosts").findValuesAsText("id")).containsExactly("earlier");
        assertThat(timing.path("timelineWindowComplete").isBoolean()).isTrue();
        assertThat(timing.path("timelineWindowComplete").booleanValue()).isFalse();
        assertThat(timing.path("formatStats").findValuesAsText("algoAdvice"))
                .noneMatch(advice -> advice.contains("本周期暂无"));
        assertThat(report(posts, null, true, NOW.minusSeconds(300), false)
                .path("timelineWindowComplete").booleanValue()).isTrue();
    }

    @Test void alternativeMetricsKeepTheirSecondarySortAndBreakFullTiesByCompositeOrder() {
        var posts = List.of(
                post("partial", 1, true, false, 1000, 0, 0, 0, Set.of("likes")),
                post("high-rate", 2, true, false, 200, 3, 10, 20, Set.of()),
                post("high-total", 3, true, false, 1000, 200, 10, 20, Set.of()),
                post("zero-engagement", 4, true, false, 1000, 0, 0, 0, Set.of()));
        JsonNode timing = report(posts, null, true, null, true);
        assertThat(ranked(timing, "MEDIA", "views")).containsExactly("high-total", "zero-engagement", "partial", "high-rate");
        assertThat(ranked(timing, "MEDIA", "bookmarks")).startsWith("high-rate", "high-total");
        assertThat(ranked(timing, "MEDIA", "replies")).startsWith("high-total", "high-rate");
        assertThat(ranked(timing, "MEDIA", "er")).startsWith("high-total").endsWith("partial");
        assertThat(ranked(timing, "MEDIA", "algoScore")).endsWith("partial");
    }

    @Test void oldContentTimingPayloadStillDeserializes() throws Exception {
        var old = mapper.readValue("{\"timezone\":\"UTC\",\"formatStats\":[],\"topPosts\":[]}", ContentTimingOverview.class);
        assertThat(old.topPosts()).isEmpty();
    }

    @Test void winningFormatFallsBackToComparableMeasuredViews() {
        var text = post("text", 100, false, false, 10, 2, 0, 0, Set.of());
        var media = post("media", 200, true, false, 10000, 0, 0, 0, Set.of("bookmarks"));
        assertThat(report(List.of(text, media), null, true, null, true).path("bestFormatCode").asText())
                .isEqualTo("MEDIA");
        var tie = post("media", 200, true, false, 10, 0, 0, 0, Set.of("bookmarks"));
        assertThat(report(List.of(text, tie), null, true, null, true).path("bestFormatCode").asText("")).isEmpty();
    }

    @Test void aFormatWithMoreViewsCannotClaimTheScoreWinnersBadge() {
        var text = post("scoring-text", 60, false, false, 100, 30, 10, 10, Set.of());
        var media = post("well-viewed-media", 90, true, false, 1000, 0, 0, 0, Set.of());
        JsonNode timing = report(List.of(text, media), null, true, null, true);
        assertThat(timing.path("bestFormatCode").asText()).isEqualTo("TEXT");
        assertThat(timing.path("formatStats").findValuesAsText("algoAdvice"))
                .anyMatch(note -> note.contains("均浏览比整体高")).noneMatch(note -> note.contains("最佳"));
        var equalViews = post("media", 90, true, false, 100, 0, 0, 0, Set.of());
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, OWNER,
                List.of(text, equalViews), List.of(), List.of(), false, false, 0, null, "", true, null, "", true);
        var dashboard = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                false, ZoneOffset.UTC, true, 0, NOW);
        assertThat(dashboard.algorithmHealth().opsRecommendations()).filteredOn(r -> r.code().equals("WINNING_FORMAT"))
                .singleElement().satisfies(r -> assertThat(r.detail()).contains("规则分").doesNotContain("均浏览"));
    }

    @Test void draftCadenceUsesPostsOutsideSelectedDay() {
        var posts = List.of(post("latest", 900, false, false, 100, 0, 0, 0, Set.of()),
                post("yesterday", 30 * 3600, false, false, 100, 0, 0, 0, Set.of()),
                post("old", 49 * 3600, false, false, 100, 0, 0, 0, Set.of()));
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, OWNER, posts,
                List.of(), List.of(), false, false, 2, null, "", true, null, "", true);
        for (var period : List.of(CommunityAnalyticsEngine.ReportPeriod.HOURS_24, CommunityAnalyticsEngine.ReportPeriod.WEEK)) {
            var report = new CommunityAnalyticsEngine().analyze(snapshot, period, false, ZoneOffset.UTC, true, 0, NOW);
            JsonNode times = mapper.valueToTree(report.algorithmHealth()).path("recentMainPostTimes");
            assertThat(times.size()).isEqualTo(2);
            assertThat(mapper.convertValue(times.get(0), Instant.class)).isEqualTo(NOW.minusSeconds(900));
            assertThat(mapper.convertValue(times.get(1), Instant.class)).isEqualTo(NOW.minusSeconds(30 * 3600));
        }
    }

    @Test void incompleteTimelineDoesNotClaimAnUninterruptedSequenceOfTextPosts() {
        var posts = List.of(post("a", 100, false, false, 10, 0, 0, 0, Set.of()),
                post("b", 200, false, false, 10, 0, 0, 0, Set.of()),
                post("c", 300, false, false, 10, 0, 0, 0, Set.of()));
        for (boolean complete : List.of(false, true)) {
            var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, OWNER, posts,
                    List.of(), List.of(), false, false, 0, NOW.minusSeconds(300), "", complete, null, "", true);
            var report = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.WEEK,
                    true, ZoneOffset.UTC, true, 0, NOW);
            String recommendations = report.algorithmHealth().opsRecommendations().toString();
            assertThat(recommendations.contains("已连续发布 3 条纯文字")).isEqualTo(complete);
        }
    }

    private List<String> ranked(JsonNode timing, String format, String metric) {
        var cards = new ArrayList<JsonNode>();
        timing.path("topPosts").forEach(cards::add);
        var rankings = timing.path("postRankings");
        rankings.path("additionalPosts").forEach(cards::add);
        var ids = new ArrayList<String>();
        rankings.path("byFormat").path(format).path(metric)
                .forEach(index -> ids.add(cards.get(index.asInt()).path("id").asText()));
        return ids;
    }

    private JsonNode report(List<RawTweet> posts, Instant timelineTail, boolean timelineDone, Instant mentionsTail, boolean mentionsDone) {
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", NOW, OWNER, posts, List.of(), List.of(),
                false, false, 0, timelineTail, "", timelineDone, mentionsTail, "", mentionsDone);
        var report = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                false, ZoneOffset.UTC, true, 0, NOW);
        return mapper.valueToTree(report.contentTiming());
    }

    private static RawTweet post(String id, int ageSeconds, boolean media, boolean thread, long views, int likes,
                                 int replies, int bookmarks, Set<String> missing) {
        return new RawTweet(id, id, NOW.minusSeconds(ageSeconds), TweetKind.POST, null, null, List.of(),
                media, thread, views, likes, replies, 0, bookmarks, false, id, "", missing);
    }
}
