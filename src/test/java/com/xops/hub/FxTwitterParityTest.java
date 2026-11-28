package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.store.AccountStore;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Compares two real sync/merge/report runs, with each upstream's native wire schema. */
@Timeout(20)
class FxTwitterParityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String JOINED = "Wed Mar 22 21:50:14 +0000 2006";
    private static final XUserSummary SELF = new XUserSummary("100", "self", "self", "", "", true, 0, 0, 50, JOINED);
    private static final XUserSummary PEER = new XUserSummary("200", "peer", "peer", "", "", true, 0, 0, 50, JOINED);
    @TempDir Path temp;

    @Test void freeContentPreservesStoreSemanticsAndAllFourReportsIncludingTextThresholds() throws Exception {
        Instant anchor = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        List<WirePair> own = new ArrayList<>();

        // Native media shortlinks count toward the existing text heuristic. Fx's readable text removes them.
        String mediaBody = "中".repeat(205);
        WirePair media = post("101", anchor.minusSeconds(3600), mediaBody + " https://t.co/media12345");
        media.free().put("text", mediaBody);
        media.paid().putObject("extendedEntities").putArray("media").addObject().put("type", "photo");
        media.free().putObject("media").putArray("all").addObject().put("type", "photo");
        media.free().withObject("/raw_text").withArray("facets").addObject()
                .put("type", "media").put("original", "https://t.co/media12345");
        own.add(media);

        // A short external t.co link can expand beyond the long-text threshold without changing the authored text.
        WirePair link = post("102", anchor.minusSeconds(5400), "资料 https://t.co/article123");
        String expanded = "https://example.org/articles/" + "a".repeat(230);
        link.free().put("text", "资料 " + expanded);
        link.free().withObject("/raw_text").withArray("facets").addObject().put("type", "url")
                .put("original", "https://t.co/article123").put("replacement", expanded);
        own.add(link);

        WirePair longQuote = post("103", anchor.minusSeconds(7200), "长文：" + "理".repeat(240) + " @peer");
        quote(longQuote, "900", "200", "peer");
        mention(longQuote, "200", "peer");
        longQuote.free().put("is_note_tweet", true);
        own.add(longQuote);
        WirePair zero = post("105", anchor.minusSeconds(18000), "Observed zero");
        metrics(zero, 0L, 0, 0, 0, 0, 0);
        own.add(zero);
        WirePair unknown = post("106", anchor.minusSeconds(40 * 3600), "Unavailable metrics");
        metrics(unknown, null, null, null, null, null, null);
        own.add(unknown);
        own.add(post("108", anchor.minusSeconds(5 * 86400), "Week historical post"));
        WirePair monthQuote = post("109", anchor.minusSeconds(20 * 86400), "Month quote");
        quote(monthQuote, "901", "200", "peer");
        own.add(monthQuote);

        WirePair inboundMention = post("203", anchor.minusSeconds(2 * 86400), "问候 @self");
        author(inboundMention, "200", "peer");
        mention(inboundMention, "100", "self");
        WirePair inboundQuote = post("202", anchor.minusSeconds(2700), "引用你的内容");
        author(inboundQuote, "200", "peer");
        quote(inboundQuote, "101", "100", "self");

        Scenario scenario = new Scenario(anchor, own, List.of(inboundMention, inboundQuote), base(anchor, List.of(), List.of()));
        Result result = run(scenario);
        assertThat(result.freeCalls()).isEqualTo(3); // Profile + own search + inbound search genuinely use Fx.
        assertThat(result.freeRunPaidRequests())
                .as("These equivalent individual-blue fixtures need no paid search/profile fallback or user enrichment")
                .isEmpty();
        SoftAssertions soft = new SoftAssertions();
        verifyParity(result, scenario, soft);
        var zeroStored = result.free().store().tweets().stream().filter(t -> t.id().equals("105")).findFirst().orElseThrow();
        var unknownStored = result.free().store().tweets().stream().filter(t -> t.id().equals("106")).findFirst().orElseThrow();
        soft.assertThat(zeroStored.unavailableMetrics()).as("zero remains an observed value").isEmpty();
        soft.assertThat(unknownStored.unavailableMetrics()).as("unavailable is never observed zero").containsExactlyInAnyOrderElementsOf(RawTweet.ALL_METRICS);
        soft.assertThat(unknownStored.metricsFetchedAt()).isZero();
        soft.assertAll();
    }

    @Test void repliesWithVerifiedStoredRootsMatchPaidReportsWithoutFallback() throws Exception {
        Instant anchor = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        WirePair root = post("101", anchor.minusSeconds(18000), "My thread root");
        WirePair selfReply = post("104", anchor.minusSeconds(14400), "A later floor of my thread");
        reply(selfReply, "self", "103", "101");
        WirePair regularReply = post("105", anchor.minusSeconds(10800), "回复 @peer");
        reply(regularReply, "peer", "901", "900");
        mention(regularReply, "200", "peer");
        WirePair incomingReply = post("201", anchor.minusSeconds(7200), "Replying to a later floor");
        author(incomingReply, "200", "peer");
        reply(incomingReply, "self", "104", "101");

        List<RawTweet> cached = List.of(
                new RawTweet("104", "old floor", anchor.minusSeconds(14400), TweetKind.POST, null, null, List.of(),
                        false, true, 50, 3, 1, 2, 1, true, "101", "103"),
                new RawTweet("105", "old reply", anchor.minusSeconds(10800), TweetKind.REPLY, "peer", PEER, List.of("peer"),
                        false, false, 50, 3, 1, 2, 1, false, "900", "901"));
        List<InboundInteraction> cachedInbound = List.of(new InboundInteraction("201", PEER, anchor.minusSeconds(7200),
                InboundKind.REPLY_TO_ME, "old incoming", "101", "104", "self"));
        Scenario scenario = new Scenario(anchor, List.of(root, selfReply, regularReply), List.of(incomingReply), base(anchor, cached, cachedInbound));
        Result result = run(scenario);
        assertThat(result.freeCalls()).isEqualTo(3);
        assertThat(result.freeRunPaidRequests()).as("Verified snapshot evidence resolves both pages")
                .isEmpty();
        SoftAssertions soft = new SoftAssertions();
        verifyParity(result, scenario, soft);
        soft.assertThat(result.free().store().tweets()).filteredOn(t -> t.id().equals("104")).singleElement()
                .satisfies(t -> soft.assertThat(t.conversationId()).isEqualTo("101"));
        soft.assertThat(result.free().store().tweets()).filteredOn(t -> t.id().equals("105")).singleElement()
                .satisfies(t -> soft.assertThat(t.conversationId()).isEqualTo("900"));
        soft.assertThat(result.free().store().inbound()).singleElement().satisfies(i -> soft.assertThat(i.conversationId()).isEqualTo("101"));
        soft.assertAll();
    }

    @Test void changedReplyParentForTheSamePostRequiresPaidSearch() throws Exception {
        Instant anchor = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        Instant at = anchor.minusSeconds(600);
        RawTweet old = new RawTweet("105", "previous", at, TweetKind.REPLY, "peer", PEER, List.of(),
                false, false, 10, 1, 0, 0, 0, false, "900", "901");
        WirePair current = post("105", at, "corrected reply");
        reply(current, "peer", "912", "910");
        Scenario scenario = new Scenario(anchor, List.of(current), List.of(), base(anchor, List.of(old), List.of()));
        Result result = run(scenario);
        assertThat(result.freeRunPaidRequests()).singleElement()
                .satisfies(uri -> assertThat(uri.getPath()).isEqualTo("/twitter/tweet/advanced_search"));
        SoftAssertions soft = new SoftAssertions();
        verifyParity(result, scenario, soft);
        soft.assertThat(result.free().store().tweets().get(0).conversationId()).isEqualTo("910");
        soft.assertAll();
    }

    @Test void overlappingRecordsWithoutRootEvidenceCannotErasePreviouslyKnownConversationRoots() {
        Instant anchor = Instant.parse("2026-11-16T12:00:00Z");
        Instant created = anchor.minusSeconds(60);
        RawTweet known = new RawTweet("105", "previous", created, TweetKind.REPLY, "peer", PEER, List.of(),
                false, false, 10, 1, 0, 0, 0, false, "900", "901");
        InboundInteraction knownInbound = new InboundInteraction("201", PEER, created, InboundKind.REPLY_TO_ME,
                "previous inbound", "101", "104", "self");
        var existing = base(anchor, List.of(known), List.of(knownInbound));
        RawTweet refreshed = new RawTweet("105", "updated", created, TweetKind.REPLY, "peer", PEER, List.of(),
                false, false, 20, 2, 0, 0, 0, false, "", "901");
        InboundInteraction refreshedInbound = new InboundInteraction("201", PEER, created, InboundKind.REPLY_TO_ME,
                "updated inbound", "", "104", "self");
        var merged = AccountStore.merge(existing, SELF, anchor, List.of(refreshed), List.of(refreshedInbound), List.of(), List.of(), null);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(merged.tweets()).singleElement().satisfies(t -> {
            soft.assertThat(t.conversationId()).isEqualTo("900");
            soft.assertThat(t.stats()[0]).isEqualTo(20);
            soft.assertThat(t.text()).isEqualTo("updated");
        });
        soft.assertThat(merged.inbound()).singleElement().satisfies(i -> {
            soft.assertThat(i.conversationId()).isEqualTo("101");
            soft.assertThat(i.snippet()).isEqualTo("updated inbound");
        });
        soft.assertAll();
    }

    @Test void changedReplyIdentityOrParentDoesNotInheritOldRootAndExplicitNewRootWins() {
        Instant anchor = Instant.parse("2026-11-16T12:00:00Z");
        Instant created = anchor.minusSeconds(60);
        RawTweet known = new RawTweet("105", "previous", created, TweetKind.REPLY, "peer", PEER, List.of(),
                false, false, 10, 1, 0, 0, 0, false, "900", "901");
        InboundInteraction knownInbound = new InboundInteraction("201", PEER, created, InboundKind.REPLY_TO_ME,
                "previous inbound", "101", "104", "self");
        var existing = base(anchor, List.of(known), List.of(knownInbound));
        SoftAssertions soft = new SoftAssertions();
        for (String change : List.of("parent", "peer", "time", "author-id", "explicit-root")) {
            Instant changedTime = change.equals("time") ? created.plusSeconds(1) : created;
            XUserSummary peer = change.equals("peer")
                    ? new XUserSummary("300", "other", "Other", "", "", true, 0, 0, 50, JOINED)
                    : change.equals("author-id")
                            ? new XUserSummary("300", "peer", "peer", "", "", true, 0, 0, 50, JOINED) : PEER;
            String root = change.equals("explicit-root") ? "777" : "";
            RawTweet updated = new RawTweet("105", "updated", changedTime, TweetKind.REPLY, peer.username(), peer, List.of(),
                    false, false, 20, 2, 0, 0, 0, false, root, change.equals("parent") ? "999" : "901");
            InboundInteraction updatedInbound = new InboundInteraction("201", peer, changedTime, InboundKind.REPLY_TO_ME,
                    "updated inbound", root, change.equals("parent") ? "999" : "104", "self");
            var merged = AccountStore.merge(existing, SELF, anchor, List.of(updated), List.of(updatedInbound), List.of(), List.of(), null);
            String expectedOwnRoot = change.equals("explicit-root") ? "777" : change.equals("author-id") ? "900" : "-";
            soft.assertThat(merged.tweets().get(0).conversationId()).as("Own reply: %s", change).isEqualTo(expectedOwnRoot);
            soft.assertThat(merged.inbound().get(0).conversationId()).as("Inbound reply: %s", change)
                    .isEqualTo(change.equals("explicit-root") ? "777" : null);
        }
        soft.assertAll();
    }

    private Result run(Scenario scenario) throws Exception {
        AtomicInteger paidCalls = new AtomicInteger();
        AtomicInteger freeCalls = new AtomicInteger();
        List<URI> paidRequests = Collections.synchronizedList(new ArrayList<>());
        try (MockServer paid = new MockServer(uri -> {
            paidCalls.incrementAndGet();
            paidRequests.add(uri);
            if (uri.getPath().equals("/twitter/user/info")) return JSON.createObjectNode().set("data", paidUser("100", "self"));
            if (uri.getPath().equals("/twitter/tweet/advanced_search")) {
                List<WirePair> rows = query(uri).contains("query=from:self ") ? scenario.own() : scenario.inbound();
                ObjectNode body = JSON.createObjectNode().put("has_next_page", false);
                ArrayNode tweets = body.putArray("tweets");
                sorted(rows).forEach(pair -> tweets.add(pair.paid()));
                return body;
            }
            throw new IllegalStateException("Unexpected paid endpoint " + uri.getPath());
        }); MockServer free = new MockServer(uri -> {
            freeCalls.incrementAndGet();
            if (uri.getPath().equals("/2/profile/self")) return JSON.createObjectNode().put("code", 200).set("user", freeUser("100", "self"));
            if (uri.getPath().equals("/2/search")) {
                List<WirePair> rows = query(uri).contains("q=from:self ") ? scenario.own() : scenario.inbound();
                ObjectNode body = JSON.createObjectNode().put("code", 200);
                ArrayNode results = body.putArray("results");
                sorted(rows).forEach(pair -> results.add(pair.free()));
                body.putObject("cursor").put("bottom", "").put("top", "");
                return body;
            }
            throw new IllegalStateException("Unexpected free endpoint " + uri.getPath());
        })) {
            long observationStart = Instant.now().getEpochSecond();
            TwitterApiIoProvider.SyncOutcome original;
            TwitterApiIoProvider.SyncOutcome replacement;
            try (ProviderRun run = new ProviderRun(paid, free, false)) {
                original = run.provider.syncStoreByHours("self", scenario.base(), 720, false, false, null, "100");
            }
            long paidEnd = Instant.now().getEpochSecond();
            int beforeFree = paidCalls.get();
            long freeStart = Instant.now().getEpochSecond();
            try (ProviderRun run = new ProviderRun(paid, free, true)) {
                replacement = run.provider.syncStoreByHours("self", scenario.base(), 720, false, false, null, "100");
            }
            return new Result(original, replacement, observationStart, paidEnd, freeStart, Instant.now().getEpochSecond(), freeCalls.get(),
                    List.copyOf(paidRequests.subList(beforeFree, paidRequests.size())));
        }
    }

    private void verifyParity(Result result, Scenario scenario, SoftAssertions soft) {
        soft.assertThat(result.paid().degraded()).as("paid control is complete").isFalse();
        soft.assertThat(result.free().degraded()).as("free-first result is complete").isFalse();
        soft.assertThat(differences(normalizeStore(result.paid().store(), result.paidStart(), result.paidEnd()),
                normalizeStore(result.free().store(), result.freeStart(), result.freeEnd())))
                .as("Stored business values (only actual observation instants are normalized)").isEmpty();
        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();
        var paidSnapshot = TwitterApiIoProvider.snapshotOf(result.paid().store(), "", result.paid().degraded());
        var freeSnapshot = TwitterApiIoProvider.snapshotOf(result.free().store(), "", result.free().degraded());
        for (ReportPeriod period : ReportPeriod.values()) {
            JsonNode expected = JSON.valueToTree(engine.analyze(paidSnapshot, period, false, ZoneId.of("Asia/Shanghai"), false, 0, scenario.anchor()));
            JsonNode actual = JSON.valueToTree(engine.analyze(freeSnapshot, period, false, ZoneId.of("Asia/Shanghai"), false, 0, scenario.anchor()));
            // Fixed snapshot anchor and expired cache make every report field deterministic: no business field is ignored.
            soft.assertThat(differences(expected, actual)).as("Full %s report parity", period.code).isEmpty();
        }
    }

    private JsonNode normalizeStore(AccountStore.StoredAccount store, long start, long end) {
        JsonNode node = JSON.valueToTree(store);
        // Only times observed by this request may differ across providers. Keep historic evidence exact.
        for (JsonNode tweet : node.path("t")) {
            for (String field : List.of("mt", "ma")) {
                if (observedDuring(tweet.path(field), start, end)) ((ObjectNode) tweet).put(field, -1L);
            }
            if (tweet.path("ms") instanceof ArrayNode evidence) {
                for (int i = 0; i < evidence.size(); i++) {
                    if (observedDuring(evidence.get(i), start, end)) evidence.set(i, evidence.numberNode(-1L));
                }
            }
        }
        node.path("u").elements().forEachRemaining(user -> {
            if (observedDuring(user.path("va"), start, end)) ((ObjectNode) user).put("va", -1L);
        });
        return node;
    }

    private static boolean observedDuring(JsonNode value, long start, long end) {
        return value.isIntegralNumber() && value.asLong() >= start && value.asLong() <= end;
    }

    private List<String> differences(JsonNode expected, JsonNode actual) {
        List<String> differences = new ArrayList<>();
        compare("$", expected, actual, differences);
        return differences;
    }

    private void compare(String path, JsonNode expected, JsonNode actual, List<String> differences) {
        if (expected.equals(actual)) return;
        if (expected.isObject() && actual.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(keys::add); actual.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) compare(path + "." + key, expected.path(key), actual.path(key), differences);
        } else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) compare(path + "[" + i + "]", expected.get(i), actual.get(i), differences);
        } else differences.add(path + ": paid=" + abbreviated(expected) + ", free=" + abbreviated(actual));
    }

    private String abbreviated(JsonNode node) { String text = node.toString(); return text.length() <= 160 ? text : text.substring(0, 160) + "…"; }
    private static String query(URI uri) { return URLDecoder.decode(String.valueOf(uri.getRawQuery()), StandardCharsets.UTF_8); }
    private static List<WirePair> sorted(List<WirePair> pairs) { return pairs.stream().sorted(Comparator.comparingLong((WirePair pair) -> pair.free().path("created_timestamp").asLong()).reversed()).toList(); }

    private AccountStore.StoredAccount base(Instant anchor, List<RawTweet> tweets, List<InboundInteraction> inbound) {
        return AccountStore.merge(null, SELF, anchor, tweets, inbound, List.of(), List.of(),
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
    }

    private static ObjectNode paidUser(String id, String username) {
        return JSON.createObjectNode().put("id", id).put("userName", username).put("name", username).put("profilePicture", "")
                .put("coverPicture", "").put("description", "").put("isBlueVerified", true).put("followers", 0)
                .put("following", 0).put("statusesCount", 50).put("createdAt", JOINED);
    }

    private static ObjectNode freeUser(String id, String username) {
        ObjectNode user = JSON.createObjectNode().put("type", "profile").put("id", id).put("screen_name", username)
                .put("name", username).put("avatar_url", "").put("banner_url", "").put("description", "")
                .put("followers", 0).put("following", 0).put("statuses", 50).put("joined", JOINED);
        // paidUser.isBlueVerified=true means an individual blue badge, not an unspecified verification type.
        user.putObject("verification").put("verified", true).put("type", "individual");
        return user;
    }

    private static WirePair post(String id, Instant at, String originalText) {
        ObjectNode paid = JSON.createObjectNode().put("id", id).put("text", originalText).put("createdAt", at.toString()).put("conversationId", id);
        ObjectNode free = JSON.createObjectNode().put("type", "status").put("id", id).put("text", originalText)
                .put("created_timestamp", at.getEpochSecond()).put("created_at", at.toString()).putNull("reposted_by").putNull("replying_to");
        free.putObject("raw_text").put("text", originalText).putArray("facets");
        WirePair pair = new WirePair(paid, free);
        author(pair, "100", "self");
        metrics(pair, 100L, 7, 2, 3, 1, 5);
        return pair;
    }

    private static void author(WirePair pair, String id, String username) {
        pair.paid().set("author", paidUser(id, username)); pair.free().set("author", freeUser(id, username));
    }

    private static void mention(WirePair pair, String id, String username) {
        pair.paid().withObject("/entities").withArray("user_mentions").addObject().put("id", id).put("screen_name", username);
        pair.free().withObject("/raw_text").withArray("facets").addObject().put("type", "mention").put("id", id).put("original", username);
    }

    private static void quote(WirePair pair, String id, String authorId, String author) {
        pair.paid().putObject("quoted_tweet").put("id", id).put("text", "Quoted post").set("author", paidUser(authorId, author));
        pair.free().putObject("quote").put("type", "status").put("id", id).put("text", "Quoted post").set("author", freeUser(authorId, author));
    }

    private static void reply(WirePair pair, String parentAuthor, String parentId, String conversationId) {
        pair.paid().put("isReply", true).put("inReplyToUsername", parentAuthor).put("inReplyToId", parentId).put("conversationId", conversationId);
        pair.free().putObject("replying_to").put("screen_name", parentAuthor).put("status", parentId);
        // Fx's actual public schema does not provide conversationId: the parent ID is not the root ID.
    }

    private static void metrics(WirePair pair, Long views, Integer likes, Integer replies, Integer reposts, Integer quotes, Integer bookmarks) {
        String[] paidFields = {"viewCount", "likeCount", "replyCount", "retweetCount", "quoteCount", "bookmarkCount"};
        String[] freeFields = {"views", "likes", "replies", "reposts", "quotes", "bookmarks"};
        Number[] values = {views, likes, replies, reposts, quotes, bookmarks};
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) { pair.paid().remove(paidFields[i]); pair.free().putNull(freeFields[i]); }
            else { pair.paid().put(paidFields[i], values[i].longValue()); pair.free().put(freeFields[i], values[i].longValue()); }
        }
    }

    private record WirePair(ObjectNode paid, ObjectNode free) { }
    private record Scenario(Instant anchor, List<WirePair> own, List<WirePair> inbound, AccountStore.StoredAccount base) { }
    private record Result(TwitterApiIoProvider.SyncOutcome paid, TwitterApiIoProvider.SyncOutcome free,
                          long paidStart, long paidEnd, long freeStart, long freeEnd,
                          int freeCalls, List<URI> freeRunPaidRequests) { }

    private static final class MockServer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newCachedThreadPool();
        MockServer(Function<URI, JsonNode> response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                int status = 200;
                byte[] bytes;
                try { bytes = JSON.writeValueAsBytes(response.apply(exchange.getRequestURI())); }
                catch (Exception ex) { status = 500; bytes = "{\"message\":\"unexpected fixture request\"}".getBytes(StandardCharsets.UTF_8); }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); handlers.shutdownNow(); }
    }

    private final class ProviderRun implements AutoCloseable {
        final SharedApiExecutor pool;
        final TwitterApiIoProvider provider;
        ProviderRun(MockServer paid, MockServer free, boolean useFree) {
            XOpsProperties p = new XOpsProperties();
            p.setTwitterapiIoBaseUrl(paid.baseUrl()); p.setTwitterapiIoKey("local-parity-fixture");
            p.setFxtwitterEnabled(useFree); p.setFxtwitterBaseUrl(free.baseUrl() + "/2");
            p.setApiPoolCoreSize(4); p.setApiPoolMaxSize(4);
            p.setDiskCacheDir(temp.resolve(useFree ? "free" : "paid").toString());
            pool = new SharedApiExecutor(p);
            provider = new TwitterApiIoProvider(p, JSON, pool);
        }
        @Override public void close() { pool.shutdown(); }
    }
}
