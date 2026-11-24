package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Searches must preserve every row even when native page sizes and data sources differ. */
@Timeout(15)
class FxTwitterSearchBoundaryParityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String JOINED = "Wed Mar 22 21:50:14 +0000 2006";
    private static final XUserSummary SELF = new XUserSummary("100", "self", "self", "", "", true, 0, 0, 50, JOINED);
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(ints = {0, 31})
    void sameSecondBoundaryMustDrainBothNativePageSizesBeforeClaimingCompleteCoverage(int newerRows) throws Exception {
        Instant anchor = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        Instant lower = anchor.minusSeconds(25 * 3600);
        Instant upper = lower.plusSeconds(100);
        var original = withInboundGap(anchor, upper);
        List<WireRow> rows = new ArrayList<>();
        // Zero newer rows reaches the boundary in the probe; 31 reaches it in a continuation page.
        for (int i = 0; i < newerRows; i++) rows.add(mention(Integer.toString(2000 + i), lower.plusSeconds(newerRows - i)));
        for (int i = 0; i < 35; i++) rows.add(mention(Integer.toString(1000 + i), lower));
        List<String> paidCursors = Collections.synchronizedList(new ArrayList<>());
        List<String> freeCursors = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            if (uri.getPath().equals("/twitter/user/info")) return paidProfile();
            if (uri.getPath().equals("/twitter/tweet/advanced_search")) {
                String cursor = query(uri).getOrDefault("cursor", "");
                paidCursors.add(cursor);
                int start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
                int end = Math.min(start + 20, rows.size());
                return paidPage(end < rows.size() ? Integer.toString(end) : "", rows.subList(start, end));
            }
            return unavailable();
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/search")) {
                String cursor = query(uri).getOrDefault("cursor", "");
                freeCursors.add(cursor);
                int start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
                int end = Math.min(start + 30, rows.size());
                return freePage(end < rows.size() ? Integer.toString(end) : "", rows.subList(start, end));
            }
            return unavailable();
        })) {
            TwitterApiIoProvider.SyncOutcome paidResult, freeResult;
            try (var f = new Fixture(paid, free, false)) { paidResult = sync(f, original); }
            int paidCountBeforeFree = paidCursors.size();
            try (var f = new Fixture(paid, free, true)) { freeResult = sync(f, original); }
            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(paidCursors).as("every original 20-row page is read without free-run fallback")
                    .containsExactlyElementsOf(cursors(rows.size(), 20));
            soft.assertThat(paidCursors.size()).isEqualTo(paidCountBeforeFree);
            soft.assertThat(freeCursors).as("every free 30-row page is read, including the rest of the boundary second")
                    .containsExactlyElementsOf(cursors(rows.size(), 30));
            soft.assertThat(paidResult.store().inbound()).hasSize(rows.size());
            soft.assertThat(freeResult.store().inbound()).hasSize(rows.size());
            soft.assertThat(freeResult.store().inbound()).containsExactlyElementsOf(paidResult.store().inbound());
            soft.assertThat(paidResult.degraded()).isFalse();
            soft.assertThat(freeResult.degraded()).isFalse();
            soft.assertThat(AccountStore.missingRanges(paidResult.store().inboundCoverage(), lower.getEpochSecond(), upper.getEpochSecond())).isEmpty();
            soft.assertThat(AccountStore.missingRanges(freeResult.store().inboundCoverage(), lower.getEpochSecond(), upper.getEpochSecond())).isEmpty();
            soft.assertAll();
        }
    }

    @Test void switchingSourceMidWindowContinuesPaidCursorAndProducesTheSameCompleteRows() throws Exception {
        Instant anchor = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        Instant lower = anchor.minusSeconds(25 * 3600);
        Instant upper = lower.plusSeconds(100);
        var original = withInboundGap(anchor, upper);
        List<WireRow> rows = List.of(mention("201", lower.plusSeconds(80)), mention("202", lower.plusSeconds(70)),
                mention("203", lower.plusSeconds(60)), mention("204", lower.plusSeconds(50)), mention("205", lower.plusSeconds(40)));
        List<URI> paidRequests = Collections.synchronizedList(new ArrayList<>());
        List<URI> freeRequests = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            if (uri.getPath().equals("/twitter/user/info")) return paidProfile();
            if (uri.getPath().equals("/twitter/tweet/advanced_search")) {
                paidRequests.add(uri);
                return query(uri).getOrDefault("cursor", "").isEmpty()
                        ? paidPage("paid-continue", rows.subList(0, 3)) : paidPage("", rows.subList(3, 5));
            }
            return unavailable();
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/search")) {
                freeRequests.add(uri);
                return query(uri).getOrDefault("cursor", "").isEmpty()
                        ? freePage("free-continue", rows.subList(0, 2)) : unavailable();
            }
            return unavailable();
        })) {
            TwitterApiIoProvider.SyncOutcome paidResult, freeResult;
            try (var f = new Fixture(paid, free, false)) { paidResult = sync(f, original); }
            int beforeFree = paidRequests.size();
            try (var f = new Fixture(paid, free, true)) { freeResult = sync(f, original); }
            assertThat(freeRequests).hasSize(4);
            List<URI> fallback = paidRequests.subList(beforeFree, paidRequests.size());
            assertThat(fallback).hasSize(2);
            assertThat(query(fallback.get(0))).doesNotContainKey("cursor");
            assertThat(query(fallback.get(1)).get("cursor")).isEqualTo("paid-continue");
            String requestedWindow = query(freeRequests.get(0)).get("q");
            assertThat(query(fallback.get(0)).get("query")).isEqualTo(requestedWindow);
            assertThat(query(fallback.get(1)).get("query")).isEqualTo(requestedWindow);
            assertThat(freeResult.store().inbound()).hasSize(5).containsExactlyElementsOf(paidResult.store().inbound());
            assertThat(freeResult.degraded()).isFalse();
            assertThat(AccountStore.missingRanges(freeResult.store().inboundCoverage(), lower.getEpochSecond(), upper.getEpochSecond())).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failingAfterSourceSwitchKeepsConfirmedRowsAndLeavesUnseenTimeUncovered(boolean failureAtLowerBound) throws Exception {
        Instant anchor = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        Instant lower = anchor.minusSeconds(25 * 3600);
        Instant upper = lower.plusSeconds(100);
        var original = withInboundGap(anchor, upper);
        List<WireRow> rows = List.of(mention("201", lower.plusSeconds(80)), mention("202", lower.plusSeconds(70)),
                mention("203", failureAtLowerBound ? lower : lower.plusSeconds(60)));
        List<URI> paidRequests = Collections.synchronizedList(new ArrayList<>());
        List<URI> freeRequests = Collections.synchronizedList(new ArrayList<>());
        try (var paid = new MockServer(uri -> {
            if (uri.getPath().equals("/twitter/tweet/advanced_search")) {
                paidRequests.add(uri);
                return query(uri).getOrDefault("cursor", "").isEmpty() ? paidPage("paid-continue", rows) : unavailable();
            }
            return unavailable();
        }); var free = new MockServer(uri -> {
            if (uri.getPath().equals("/2/profile/self")) return freeProfile();
            if (uri.getPath().equals("/2/search")) {
                freeRequests.add(uri);
                return query(uri).getOrDefault("cursor", "").isEmpty() ? freePage("free-continue", rows.subList(0, 2)) : unavailable();
            }
            return unavailable();
        }); var f = new Fixture(paid, free, true)) {
            var result = sync(f, original);
            assertThat(freeRequests).hasSize(4);
            assertThat(paidRequests).hasSize(2);
            assertThat(query(paidRequests.get(1)).get("cursor")).isEqualTo("paid-continue");
            assertThat(result.store().inbound()).extracting(AccountStore.StoredInbound::id)
                    .containsExactlyInAnyOrder("201", "202", "203");
            assertThat(result.degraded()).isTrue();
            var missing = AccountStore.missingRanges(result.store().inboundCoverage(), lower.getEpochSecond(), upper.getEpochSecond());
            if (failureAtLowerBound) {
                // The first paid page reached since_time but its cursor promised more rows at that second.
                // A failed next page must not turn the partial page into evidence of full coverage.
                assertThat(missing).isNotEmpty().anySatisfy(gap -> {
                    assertThat(gap[0]).isLessThanOrEqualTo(lower.getEpochSecond());
                    assertThat(gap[1]).isGreaterThanOrEqualTo(lower.getEpochSecond());
                });
            } else {
                assertThat(missing).singleElement().satisfies(gap ->
                        assertThat(gap).containsExactly(lower.getEpochSecond(), lower.plusSeconds(60).getEpochSecond()));
                assertThat(AccountStore.missingRanges(result.store().inboundCoverage(), lower.plusSeconds(60).getEpochSecond(), upper.getEpochSecond())).isEmpty();
            }
        }
    }

    private static TwitterApiIoProvider.SyncOutcome sync(Fixture f, AccountStore.StoredAccount original) {
        return f.provider.syncStoreByHours("self", original, 24, false, false, null, "100");
    }

    private static List<String> cursors(int count, int pageSize) {
        List<String> result = new ArrayList<>();
        result.add("");
        for (int at = pageSize; at < count; at += pageSize) result.add(Integer.toString(at));
        return result;
    }

    private static AccountStore.StoredAccount withInboundGap(Instant anchor, Instant upper) {
        List<long[]> own = List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()});
        List<long[]> inbound = List.of(new long[]{upper.getEpochSecond(), anchor.getEpochSecond()});
        return AccountStore.merge(null, SELF, anchor, List.of(), List.of(), own, inbound,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
    }

    private static WireRow mention(String id, Instant at) {
        String text = "Message @self " + id;
        ObjectNode paid = JSON.createObjectNode().put("id", id).put("text", text).put("createdAt", at.toString()).put("conversationId", id)
                .put("isReply", false).put("viewCount", 100).put("likeCount", 1).put("replyCount", 0).put("retweetCount", 0).put("quoteCount", 0).put("bookmarkCount", 0);
        paid.set("author", paidUser("200", "peer"));
        paid.putObject("entities").putArray("user_mentions").addObject().put("id", "100").put("screen_name", "self");
        ObjectNode free = JSON.createObjectNode().put("type", "status").put("id", id).put("text", text)
                .put("created_at", at.toString()).put("created_timestamp", at.getEpochSecond())
                .put("views", 100).put("likes", 1).put("replies", 0).put("reposts", 0).put("quotes", 0).put("bookmarks", 0)
                .putNull("replying_to").putNull("reposted_by").putNull("media");
        free.set("author", freeUser("200", "peer"));
        free.putObject("raw_text").put("text", text).putArray("facets").addObject()
                .put("type", "mention").put("id", "100").put("original", "self");
        return new WireRow(paid, free);
    }

    private static ObjectNode paidUser(String id, String username) {
        return JSON.createObjectNode().put("id", id).put("userName", username).put("name", username)
                .put("isBlueVerified", true).put("followers", 0).put("following", 0).put("statusesCount", 50).put("createdAt", JOINED);
    }

    private static ObjectNode freeUser(String id, String username) {
        ObjectNode user = JSON.createObjectNode().put("type", "profile").put("id", id).put("screen_name", username)
                .put("name", username).put("followers", 0).put("following", 0).put("statuses", 50).put("joined", JOINED);
        user.putObject("verification").put("verified", true).put("type", "individual");
        return user;
    }

    private static Reply paidProfile() { return ok(JSON.createObjectNode().set("data", paidUser("100", "self"))); }
    private static Reply freeProfile() { return ok(JSON.createObjectNode().put("code", 200).set("user", freeUser("100", "self"))); }
    private static Reply unavailable() { return new Reply(500, "{\"code\":500}"); }
    private static Reply ok(ObjectNode body) { return new Reply(200, body.toString()); }

    private static Reply paidPage(String next, List<WireRow> rows) {
        ObjectNode root = JSON.createObjectNode().put("has_next_page", !next.isEmpty()).put("next_cursor", next);
        ArrayNode tweets = root.putArray("tweets");
        rows.forEach(row -> tweets.add(row.paid()));
        return ok(root);
    }

    private static Reply freePage(String next, List<WireRow> rows) {
        ObjectNode root = JSON.createObjectNode().put("code", 200);
        ArrayNode tweets = root.putArray("results");
        rows.forEach(row -> tweets.add(row.free()));
        root.putObject("cursor").put("bottom", next).put("top", "");
        return ok(root);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&")) {
            String[] pair = parameter.split("=", 2);
            params.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }

    private record WireRow(ObjectNode paid, ObjectNode free) { }
    private record Reply(int status, String body) { }

    private static final class MockServer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newCachedThreadPool();
        MockServer(Function<URI, Reply> response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                Reply result = response.apply(exchange.getRequestURI());
                byte[] body = result.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.status(), body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); handlers.shutdownNow(); }
    }

    private final class Fixture implements AutoCloseable {
        final SharedApiExecutor pool;
        final TwitterApiIoProvider provider;
        Fixture(MockServer paid, MockServer free, boolean enabled) {
            XOpsProperties p = new XOpsProperties();
            p.setTwitterapiIoBaseUrl(paid.baseUrl());
            p.setTwitterapiIoKey("local-fixture");
            p.setFxtwitterEnabled(enabled);
            p.setFxtwitterBaseUrl(free.baseUrl() + "/2");
            p.setDiskCacheDir(temp.resolve(enabled ? "free" : "paid").toString());
            p.setApiPoolCoreSize(1);
            p.setApiPoolMaxSize(1);
            p.setApiMaxParallelSlices(1);
            pool = new SharedApiExecutor(p);
            provider = new TwitterApiIoProvider(p, JSON, pool);
        }
        @Override public void close() { pool.shutdown(); }
    }
}
