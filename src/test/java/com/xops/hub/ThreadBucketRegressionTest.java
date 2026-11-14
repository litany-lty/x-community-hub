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
    void bucketsCountThreadFloorsWithoutInflatingMainPostsOrReplies() {
        var now = Instant.parse("2024-04-05T12:00:00Z");
        var tweets = new ArrayList<RawTweet>();
        addGroup(tweets, "first", now.minusSeconds(3 * 3600), 1, 2, 1, 3, 91);
        addGroup(tweets, "second", now.minusSeconds(3600), 2, 1, 0, 1, 37);
        var self = new XUserSummary("self-id", "self", "Self", "", "", false, 10, 5, 20, "2020-01-01");
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", now,
                self, tweets, List.of(), List.of());
        var activity = new CommunityAnalyticsEngine().analyze(snapshot, 2, ZoneId.of("Asia/Shanghai"), false).activity();
        assertThat(activity.originalPosts()).isEqualTo(3);
        assertThat(activity.replies()).isEqualTo(3);
        var mapper = new ObjectMapper();
        var first = mapper.valueToTree(activity.trendSeries().get(22));
        var second = mapper.valueToTree(activity.trendSeries().get(23));
        assertThat(first.path("threadReplies").asInt(-1)).isEqualTo(3);
        assertThat(second.path("threadReplies").asInt(-1)).isEqualTo(1);
        for (var bucket : List.of(first, second)) {
            int total = bucket.path("posts").asInt() + bucket.path("replies").asInt()
                    + bucket.path("quotes").asInt() + bucket.path("threadReplies").asInt();
            assertThat(total).isEqualTo(bucket.path("viewSampleCount").asInt());
        }
        assertThat(first.path("views").asLong()).isEqualTo(91);
        assertThat(first.path("viewSampleCount").asInt()).isEqualTo(7);
        assertThat(second.path("views").asLong()).isEqualTo(37);
        assertThat(second.path("viewSampleCount").asInt()).isEqualTo(4);
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
