package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.InboundKind;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.SharedApiExecutor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class HealthOfficialContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestsCurrentContractAndPreservesCurrentAndLegacyResponseSemantics(boolean legacy) throws Exception {
        var requests = new ConcurrentLinkedQueue<String>();
        String count = legacy ? "\"tweet_count\":42" : "\"post_count\":42,\"tweet_count\":99";
        String reposts = legacy ? "\"retweet_count\":7" : "\"repost_count\":7,\"retweet_count\":99";
        String timeline = """
                {"data":[
                  {"id":"quote","text":"quote","created_at":"2026-11-13T02:00:00Z",
                   "referenced_posts":[{"type":"quoted","id":"peer-root"}],
                   "public_metrics":{%s,"quote_count":2}},
                  {"id":"reply","text":"reply","created_at":"2026-11-13T02:00:00Z",
                   "conversation_id":"peer-root","referenced_posts":[{"type":"replied_to","id":"peer-root"}]},
                  {"id":"repost","text":"repost","created_at":"2026-11-13T02:00:00Z",
                   "referenced_posts":[{"type":"retweeted","id":"peer-root"}]}],
                 "includes":{"posts":[{"id":"peer-root","author_id":"peer-id"}],
                             "users":[{"id":"peer-id","username":"peer"}]}}
                """.formatted(reposts);
        String mentions = """
                {"data":[
                  {"id":"incoming-quote","author_id":"peer-id","text":"quote self","created_at":"2026-11-13T02:00:00Z",
                   "referenced_posts":[{"type":"quoted","id":"self-root"}]},
                  {"id":"incoming-reply","author_id":"peer-id","text":"reply self","created_at":"2026-11-13T02:00:00Z",
                   "conversation_id":"self-root","referenced_posts":[{"type":"replied_to","id":"self-root"}]},
                  {"id":"third-party-quote","author_id":"peer-id","text":"@self look","created_at":"2026-11-13T02:00:00Z",
                   "referenced_posts":[{"type":"quoted","id":"third-root"}]}],
                 "includes":{"posts":[{"id":"self-root","author_id":"1"},{"id":"third-root","author_id":"third"}],
                             "users":[{"id":"peer-id","username":"peer"}]}}
                """;
        if (legacy) {
            timeline = legacyResponse(timeline);
            mentions = legacyResponse(mentions);
        }
        String timelineResponse = timeline;
        String mentionsResponse = mentions;
        var server = DataAuditRegressionTest.server(path -> {
            requests.add(path);
            if (path.contains("/by/username/")) {
                return "{\"data\":{\"id\":\"1\",\"username\":\"self\",\"public_metrics\":{" + count + "}}}";
            }
            if (path.contains("/tweets?")) return timelineResponse;
            if (path.contains("/mentions?")) return mentionsResponse;
            return "{\"data\":[]}";
        });
        var properties = new XOpsProperties();
        properties.setOfficialBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        properties.setOfficialBearerToken("synthetic-test-token");
        var pool = new SharedApiExecutor(properties);
        try {
            var result = new OfficialXApiV2Provider(properties, new ObjectMapper(), pool).fetchSnapshot("self", null);
            assertThat(result.profile().totalPostsCount()).isEqualTo(42);
            assertThat(result.timeline()).extracting(t -> t.kind()).containsExactly(TweetKind.QUOTE, TweetKind.REPLY, TweetKind.RETWEET);
            assertThat(result.timeline().get(0).targetUsername()).isEqualTo("peer");
            assertThat(result.timeline().get(0).retweetCount()).isEqualTo(9);
            assertThat(result.timeline().get(1).targetUsername()).isEqualTo("peer");
            assertThat(result.timeline().get(1).inReplyToId()).isEqualTo("peer-root");
            assertThat(result.inboundInteractions()).extracting(i -> i.kind())
                    .containsExactly(InboundKind.QUOTE_ME, InboundKind.REPLY_TO_ME, InboundKind.MENTION_ME);
            assertThat(result.inboundInteractions().get(1).inReplyToUsername()).isEqualTo("self");
            assertThat(result.inboundInteractions().get(1).inReplyToId()).isEqualTo("self-root");
            var postRequests = requests.stream().filter(p -> p.contains("/tweets?") || p.contains("/mentions?")).toList();
            assertThat(postRequests).hasSize(2);
            for (String request : postRequests) {
                var query = query(request);
                assertThat(query).doesNotContainKey("tweet.fields");
                assertThat(query.get("post.fields").split(",")).containsExactlyInAnyOrder(
                        "created_at", "conversation_id", "public_metrics", "entities", "attachments");
                assertThat(query.get("expansions").split(",")).containsExactlyInAnyOrder(
                        "author_id", "in_reply_to_user_id", "referenced_posts");
                assertThat(request).doesNotContain("referenced_tweets", "referenced_posts.id.author_id");
            }
        } finally {
            pool.shutdown();
            server.stop(0);
        }
    }

    private static String legacyResponse(String json) {
        return json.replace("referenced_posts", "referenced_tweets").replace("\"posts\"", "\"tweets\"");
    }

    private static Map<String, String> query(String request) {
        return Arrays.stream(URI.create(request).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
