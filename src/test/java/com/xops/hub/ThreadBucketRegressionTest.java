package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadBucketRegressionTest {
    @Test
    void realObservedBucketsCountThreadFloorsWithoutInflatingMainPostsOrReplies() {
        var now = Instant.parse("2026-11-14T00:01:09Z");
        var tweets = new ArrayList<RawTweet>();
        // Observed 04:01–06:01: 4 roots + 24 replies + 8 quotes + 2 thread floors.
        addGroup(tweets, "first", now.minusSeconds(3 * 3600), 4, 24, 8, 2, 1287);
        // Observed 06:01–08:01: 3 roots + 31 replies + 1 quote + 4 thread floors.
        addGroup(tweets, "second", now.minusSeconds(3600), 3, 31, 1, 4, 686);
        var self = new XUserSummary("self-id", "self", "Self", "", "", false, 365, 248, 4878, "2020-01-01");
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", now,
                self, tweets, List.of(), List.of());
        var activity = new CommunityAnalyticsEngine().analyze(snapshot, 2, ZoneId.of("Asia/Shanghai"), false).activity();
        assertThat(activity.originalPosts()).isEqualTo(7);
        assertThat(activity.replies()).isEqualTo(55);
        var mapper = new ObjectMapper();
        var first = mapper.valueToTree(activity.trendSeries().get(22));
        var second = mapper.valueToTree(activity.trendSeries().get(23));
        assertThat(first.path("threadReplies").asInt(-1)).isEqualTo(2);
        assertThat(second.path("threadReplies").asInt(-1)).isEqualTo(4);
        for (var bucket : List.of(first, second)) {
            int total = bucket.path("posts").asInt() + bucket.path("replies").asInt()
                    + bucket.path("quotes").asInt() + bucket.path("threadReplies").asInt();
            assertThat(total).isEqualTo(bucket.path("viewSampleCount").asInt());
        }
        assertThat(first.path("views").asLong()).isEqualTo(1287);
        assertThat(first.path("viewSampleCount").asInt()).isEqualTo(38);
        assertThat(second.path("views").asLong()).isEqualTo(686);
        assertThat(second.path("viewSampleCount").asInt()).isEqualTo(39);
    }

    private static void addGroup(List<RawTweet> tweets, String prefix, Instant at,
            int roots, int replies, int quotes, int floors, long views) {
        for (int i = 0; i < roots + replies + quotes + floors; i++) {
            var kind = i < roots ? TweetKind.POST : i < roots + replies ? TweetKind.REPLY
                    : i < roots + replies + quotes ? TweetKind.QUOTE : TweetKind.POST;
            boolean floor = i >= roots + replies + quotes;
            String id = prefix + "-" + i;
            tweets.add(new RawTweet(id, id, at, kind, null, null, List.of(), false, floor,
                    i == 0 ? views : 0, 0, 0, 0, 0, floor, floor ? prefix + "-0" : id, floor ? prefix + "-0" : ""));
        }
    }
}
