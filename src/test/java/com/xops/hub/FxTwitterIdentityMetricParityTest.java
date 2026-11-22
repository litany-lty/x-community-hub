package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Later cross-check: a metric refresh cannot splice a cached root onto a different reply identity. */
@Timeout(15)
class FxTwitterIdentityMetricParityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final XUserSummary SELF = new XUserSummary("100", "self", "Self", "", "", true, 0, 0, 20, "");
    @TempDir Path temp;

    @Test void dailyMetricRefreshRejectsDifferentParentInsteadOfSplicingItOntoKnownRoot() throws Exception {
        Instant synced = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = synced.minusSeconds(3600);
        var old = store(synced, List.of(thread("101", created)));
        ObjectNode conflicting = freeStatus("101", created);
        conflicting.putObject("replying_to").put("status", "902").put("screen_name", "self");
        try (Fixture f = new Fixture(uri -> paidRows(), id -> detail(conflicting))) {
            var result = f.sync(old, true);
            assertThat(f.freeDetailIds).containsExactly("101");
            assertThat(f.paidDetailQueries).containsExactly("tweet_ids=101");
            assertUnchanged(tweet(old, "101"), tweet(result.store(), "101"));
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    @Test void historicalMetricRefreshRejectsLostReplyRelationInsteadOfTurningThreadFloorIntoMainPost() throws Exception {
        Instant synced = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = synced.minusSeconds(7 * 86400L);
        var old = store(synced, List.of(thread("101", created)));
        // Fx has no parent evidence in this response, even though the same stored ID is a reply.
        ObjectNode conflicting = freeStatus("101", created);
        try (Fixture f = new Fixture(uri -> paidRows(), id -> detail(conflicting))) {
            var result = f.sync(old, false);
            assertThat(f.freeDetailIds).containsExactly("101");
            assertThat(f.paidDetailQueries).containsExactly("tweet_ids=101");
            assertUnchanged(tweet(old, "101"), tweet(result.store(), "101"));
            assertThat(result.degraded()).isTrue();
        }
    }

    @Test void dailyMetricRefreshCanFillPreviouslyUnknownParentWithoutDiscardingKnownRoot() throws Exception {
        Instant synced = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = synced.minusSeconds(3600);
        RawTweet incompleteRelation = new RawTweet("101", "cached thread floor", created, TweetKind.POST,
                null, null, List.of(), false, true, 80, 8, 2, 3, 9, true, "900", "", Set.of());
        var old = store(synced, List.of(incompleteRelation));
        ObjectNode supplemented = freeStatus("101", created);
        supplemented.putObject("replying_to").put("status", "901").put("screen_name", "self");
        try (Fixture f = new Fixture(uri -> paidRows(), id -> detail(supplemented))) {
            var result = f.sync(old, true);
            assertThat(f.freeDetailIds).containsExactly("101");
            assertThat(f.paidDetailQueries).isEmpty();
            var refreshed = tweet(result.store(), "101");
            assertThat(refreshed.inReplyToId()).isEqualTo("901");
            assertThat(refreshed.conversationId()).isEqualTo("900");
            assertThat(refreshed.kind()).isEqualTo('P');
            assertThat(refreshed.flags()).isEqualTo(tweet(old, "101").flags());
            assertThat(refreshed.stats()).containsExactly(100, 7, 2, 4, 5);
            assertThat(refreshed.metricsFetchedAt()).isGreaterThan(tweet(old, "101").metricsFetchedAt());
            assertThat(result.degraded()).isFalse();
            assertThat(result.dailyDataComplete()).isTrue();
        }
    }

    @Test void paidSupplementWithConflictingPublicationTimeCannotReplaceCachedTweetAlongsideValidFreeProgress() throws Exception {
        Instant synced = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = synced.minusSeconds(3600);
        var old = store(synced, List.of(post("101", created), post("102", created.minusSeconds(10))));
        try (Fixture f = new Fixture(uri -> paidRows(paidStatus("102", created.plusSeconds(60))),
                id -> id.equals("101") ? detail(freeStatus("101", created)) : unavailable())) {
            var result = f.sync(old, true);
            assertThat(f.freeDetailIds).containsExactly("101", "102");
            assertThat(f.paidDetailQueries).containsExactly("tweet_ids=102");
            assertThat(tweet(result.store(), "101").stats()).containsExactly(100, 7, 2, 4, 5);
            assertUnchanged(tweet(old, "102"), tweet(result.store(), "102"));
            assertThat(result.incompleteDailyMetricUpdates()).isEqualTo(1);
            assertThat(result.degraded()).isTrue();
            assertThat(result.dailyDataComplete()).isFalse();
        }
    }

    @Test void sameHandleWithDifferentAuthorIdCannotOverwriteCachedOwnMetrics() throws Exception {
        Instant synced = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(7200);
        Instant created = synced.minusSeconds(3600);
        var old = store(synced, List.of(post("101", created)));
        ObjectNode otherIdentity = freeStatus("101", created);
        otherIdentity.set("author", freeUser("200", "self"));
        try (Fixture f = new Fixture(uri -> paidRows(paidStatus("101", created)), id -> detail(otherIdentity))) {
            var result = f.sync(old, true);
            assertThat(f.freeDetailIds).containsExactly("101");
            assertThat(f.paidDetailQueries).containsExactly("tweet_ids=101");
            assertThat(tweet(result.store(), "101").text()).isEqualTo("paid update 101");
            assertThat(result.store().profile().id()).isEqualTo("100");
            assertThat(result.incompleteDailyMetricUpdates()).isZero();
            assertThat(result.degraded()).isFalse();
            assertThat(result.dailyDataComplete()).isTrue();
        }
    }

    private static void assertUnchanged(AccountStore.StoredTweet before, AccountStore.StoredTweet after) {
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.kind()).isEqualTo(before.kind());
        assertThat(after.target()).isEqualTo(before.target());
        assertThat(after.flags()).isEqualTo(before.flags());
        assertThat(after.inReplyToId()).isEqualTo(before.inReplyToId());
        assertThat(after.conversationId()).isEqualTo(before.conversationId());
        assertThat(after.text()).isEqualTo(before.text());
        assertThat(after.stats()).containsExactly(before.stats());
        assertThat(after.unavailableMetrics()).isEqualTo(before.unavailableMetrics());
        assertThat(after.metricsFetchedAt()).isEqualTo(before.metricsFetchedAt());
    }

    private static AccountStore.StoredTweet tweet(AccountStore.StoredAccount store, String id) {
        return store.tweets().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    private static RawTweet post(String id, Instant at) {
        return new RawTweet(id, "cached " + id, at, TweetKind.POST, null, null, List.of(),
                false, false, 80, 8, 2, 3, 9, false, id, "", Set.of());
    }

    private static RawTweet thread(String id, Instant at) {
        return new RawTweet(id, "cached thread floor", at, TweetKind.POST, null, null, List.of(),
                false, true, 80, 8, 2, 3, 9, true, "900", "901", Set.of());
    }

    private static AccountStore.StoredAccount store(Instant anchor, List<RawTweet> tweets) {
        List<long[]> coverage = List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()});
        return AccountStore.merge(null, SELF, anchor, tweets, List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
    }

    private static ObjectNode freeUser(String id, String handle) {
        ObjectNode user = JSON.createObjectNode().put("type", "profile").put("id", id).put("screen_name", handle)
                .put("name", "Self").put("description", "").put("avatar_url", "").put("banner_url", "")
                .put("followers", 0).put("following", 0).put("statuses", 20)
                .put("joined", "Wed Mar 22 21:50:14 +0000 2006");
        user.putObject("verification").put("verified", true).put("type", "individual");
        return user;
    }

    private static ObjectNode freeStatus(String id, Instant created) {
        ObjectNode row = JSON.createObjectNode().put("type", "status").put("id", id).put("text", "updated " + id)
                .put("created_timestamp", created.getEpochSecond()).put("created_at", created.toString())
                .put("views", 100).put("likes", 7).put("replies", 2).put("reposts", 3).put("quotes", 1).put("bookmarks", 5)
                .putNull("replying_to").putNull("reposted_by").putNull("media");
        row.set("author", freeUser("100", "self"));
        row.putObject("raw_text").put("text", "updated " + id).putArray("facets");
        return row;
    }

    private static ObjectNode paidStatus(String id, Instant created) {
        ObjectNode row = JSON.createObjectNode().put("id", id).put("text", "paid update " + id)
                .put("createdAt", created.toString()).put("conversationId", id)
                .put("viewCount", 100).put("likeCount", 7).put("replyCount", 2).put("retweetCount", 3).put("quoteCount", 1).put("bookmarkCount", 5);
        row.putObject("author").put("id", "100").put("userName", "self").put("isBlueVerified", true);
        return row;
    }

    private static Reply detail(ObjectNode row) { return json(JSON.createObjectNode().put("code", 200).set("status", row)); }
    private static Reply unavailable() { return new Reply(404, "{\"code\":404}"); }
    private static Reply json(ObjectNode row) { return new Reply(200, row.toString()); }

    private static Reply paidRows(ObjectNode... rows) {
        ObjectNode root = JSON.createObjectNode().put("has_next_page", false);
        ArrayNode tweets = root.putArray("tweets");
        for (ObjectNode row : rows) tweets.add(row);
        return json(root);
    }

    private record Reply(int status, String body) { }

    private final class Fixture implements AutoCloseable {
        final List<String> freeDetailIds = Collections.synchronizedList(new ArrayList<>());
        final List<String> paidDetailQueries = Collections.synchronizedList(new ArrayList<>());
        final MockServer paid;
        final MockServer free;
        final SharedApiExecutor pool;
        final TwitterApiIoProvider provider;

        Fixture(Function<URI, Reply> paidDetail, Function<String, Reply> freeDetail) throws Exception {
            paid = new MockServer(uri -> {
                if (uri.getPath().equals("/twitter/tweets")) {
                    paidDetailQueries.add(URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
                    return paidDetail.apply(uri);
                }
                // Historical refresh keeps the original native-head endpoints; their empty head is intentional.
                if (uri.getPath().equals("/twitter/user/last_tweets") || uri.getPath().equals("/twitter/user/mentions")) return paidRows();
                throw new AssertionError("Unexpected paid path: " + uri.getPath());
            });
            free = new MockServer(uri -> {
                if (uri.getPath().equals("/2/profile/self")) return json(JSON.createObjectNode().put("code", 200).set("user", freeUser("100", "self")));
                if (uri.getPath().equals("/2/profile/self/statuses") || uri.getPath().equals("/2/search")) {
                    ObjectNode root = JSON.createObjectNode().put("code", 200); root.putArray("results"); root.putObject("cursor").put("bottom", "");
                    return json(root);
                }
                if (uri.getPath().startsWith("/2/status/")) {
                    String id = uri.getPath().substring("/2/status/".length()); freeDetailIds.add(id); return freeDetail.apply(id);
                }
                throw new AssertionError("Unexpected free path: " + uri.getPath());
            });
            XOpsProperties p = new XOpsProperties();
            p.setTwitterapiIoBaseUrl(paid.baseUrl()); p.setTwitterapiIoKey("local-fixture");
            p.setFxtwitterEnabled(true); p.setFxtwitterBaseUrl(free.baseUrl() + "/2");
            p.setDiskCacheDir(temp.toString()); p.setApiPoolCoreSize(1); p.setApiPoolMaxSize(1);
            pool = new SharedApiExecutor(p); provider = new TwitterApiIoProvider(p, JSON, pool);
        }

        TwitterApiIoProvider.SyncOutcome sync(AccountStore.StoredAccount existing, boolean daily) {
            return provider.syncStoreByHours("self", existing, daily ? 24 : 720, true, daily, null, "100");
        }

        @Override public void close() { pool.shutdown(); free.close(); paid.close(); }
    }

    private static final class MockServer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newCachedThreadPool();
        MockServer(Function<URI, Reply> handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                Reply reply = handler.apply(exchange.getRequestURI()); byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            }); server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); handlers.shutdownNow(); }
    }
}
