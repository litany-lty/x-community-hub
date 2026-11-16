package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.FxTwitterClient;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class FxTwitterAvatarParityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String ORIGINAL = "https://pbs.twimg.com/profile_images/123/avatar_normal.jpg";
    private static final String FREE = "https://pbs.twimg.com/profile_images/123/avatar_200x200.jpg";
    private static final String EXPECTED = "https://pbs.twimg.com/profile_images/123/avatar_400x400.jpg";

    @Test
    void nativeFreeProfileAndPaidQuotePreserveTheSameStoredPeerAvatar() throws Exception {
        ObjectNode nativeUser = JSON.createObjectNode().put("type", "profile").put("id", "200")
                .put("screen_name", "peer").put("name", "Peer").put("avatar_url", FREE)
                .put("description", "").put("followers", 10).put("following", 20).put("statuses", 30);
        nativeUser.putObject("verification").put("verified", true).put("type", "individual");
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("code", 200).set("user", nativeUser));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2/profile/peer", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        XOpsProperties properties = properties();
        properties.setFxtwitterEnabled(true);
        properties.setFxtwitterBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/2");
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        try {
            TwitterApiIoProvider parser = new TwitterApiIoProvider(properties, JSON, pool);
            JsonNode freeProfile = new FxTwitterClient(properties, JSON, pool)
                    .fetchAsync("https://route.invalid/twitter/user/info?userName=peer", Instant.now().plusSeconds(3), new AtomicBoolean())
                    .get(4, TimeUnit.SECONDS).path("data");
            ObjectNode paidProfile = JSON.createObjectNode().put("id", "200").put("userName", "peer")
                    .put("name", "Peer").put("profilePicture", ORIGINAL).put("description", "")
                    .put("followers", 10).put("following", 20).put("statusesCount", 30).put("isBlueVerified", true);
            Instant anchor = Instant.parse("2026-11-16T12:00:00Z");
            RawTweet paid = ReflectionTestUtils.invokeMethod(parser, "parseRawTweet", quote(paidProfile, anchor), "self");
            RawTweet free = ReflectionTestUtils.invokeMethod(parser, "parseRawTweet", quote(freeProfile, anchor), "self");
            assertThat(paid.targetUser().avatarUrl()).isEqualTo(EXPECTED);
            assertThat(free.targetUser()).isEqualTo(paid.targetUser());

            XUserSummary self = new XUserSummary("100", "self", "Self", "", "", false, 0, 0, 1, "");
            var oldStore = AccountStore.merge(null, self, anchor, List.of(paid), List.of(), List.of(), List.of(), null);
            var newStore = AccountStore.merge(null, self, anchor, List.of(free), List.of(), List.of(), List.of(), null);
            assertThat(newStore.users().get("peer").avatarUrl()).isEqualTo(EXPECTED);
            assertThat(JSON.<JsonNode>valueToTree(newStore)).isEqualTo(JSON.<JsonNode>valueToTree(oldStore));
            assertThat(AccountStore.toSnapshot(newStore, "", false, 1).timeline()).singleElement()
                    .satisfies(tweet -> assertThat(tweet.targetUser().avatarUrl()).isEqualTo(EXPECTED));
        } finally {
            pool.shutdown();
            server.stop(0);
        }
    }

    @Test
    void sizeNormalizationLeavesOtherHostsAndNonProfileImagesUnchanged() {
        XOpsProperties properties = properties();
        SharedApiExecutor pool = new SharedApiExecutor(properties);
        try {
            TwitterApiIoProvider parser = new TwitterApiIoProvider(properties, JSON, pool);
            for (String avatar : List.of("https://images.example.org/profile_images/123/avatar_200x200.jpg",
                    "https://pbs.twimg.com/media/avatar_200x200.jpg")) {
                ObjectNode user = JSON.createObjectNode().put("id", "200").put("userName", "peer")
                        .put("profilePicture", avatar);
                XUserSummary parsed = ReflectionTestUtils.invokeMethod(parser, "parseUserNode", user, "");
                assertThat(parsed.avatarUrl()).as(avatar).isEqualTo(avatar);
            }
        } finally {
            pool.shutdown();
        }
    }

    private static XOpsProperties properties() {
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir("");
        properties.setTwitterapiIoKey("");
        properties.setApiPoolCoreSize(1);
        properties.setApiPoolMaxSize(1);
        return properties;
    }

    private static ObjectNode quote(JsonNode author, Instant anchor) {
        ObjectNode post = JSON.createObjectNode().put("id", "101").put("text", "引用")
                .put("createdAt", anchor.minusSeconds(3600).toString()).put("conversationId", "101")
                .put("viewCount", 10).put("likeCount", 1).put("replyCount", 0)
                .put("retweetCount", 0).put("quoteCount", 0).put("bookmarkCount", 0);
        post.putObject("quoted_tweet").put("id", "900").set("author", author);
        return post;
    }
}
