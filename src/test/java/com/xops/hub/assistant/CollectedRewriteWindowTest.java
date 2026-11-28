package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.TwitterApiIoProvider.TextPost;
import com.xops.hub.provider.TwitterApiIoProvider.TextPostPage;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectedRewriteWindowTest {
    @TempDir Path data;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String BODY = "Engineering teams independently measured delivery speed throughout a six week trial and compared the original cycle times.";
    private static final long WINDOW = AutoRepostService.REWRITE_MAX_AGE_MS;
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode registerSource(AssistantStore store) {
        return store.tx(tx -> {
            tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "owner"));
            return tx.put("source", "7", object().put("authorId", "7").put("username", "source")
                    .put("instanceId", "first").put("status", "ACTIVE").put("autoRewriteEnabled", true));
        });
    }

    @Test void realTimelineResponseKeepsItsPermalinkAndReachesPreviewAndPatrol() throws Exception {
        var store = new AssistantStore(data.resolve("assistant"), mapper);
        Instant posted = LocalDate.now(ZONE).minusDays(1).atTime(8, 0).atZone(ZONE).toInstant();
        var serviceTime = Clock.fixed(posted.plusSeconds(3600), ZONE);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            ObjectNode response = object();
            int status = 200;
            if (path.endsWith("/info")) {
                response.putObject("data").put("id", "7").put("userName", "source");
            } else if (path.endsWith("/last_tweets") || path.endsWith("/tweets")) {
                response.putArray("tweets").addObject().put("id", "970011").put("text", BODY).put("createdAt", posted.toString())
                        .put("viewCount", 35_000).put("likeCount", 250).putObject("author").put("id", "7").put("userName", "source");
                response.put("has_next_page", false);
            } else status = 404;
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        var properties = new XOpsProperties();
        properties.setDiskCacheDir(data.resolve("upstream").toString());
        properties.setFxtwitterEnabled(false);
        properties.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTwitterapiIoKey("local-fixture");
        var executor = new SharedApiExecutor(properties);
        var collector = new SourceCollector(store, new TwitterApiIoProvider(properties, mapper, executor), null, mapper, Clock.systemUTC());
        var judge = mock(PiAgentViralJudge.class);
        var publisher = mock(XPublishClient.class);
        var auth = mock(XOAuthService.class);
        var service = new AutoRepostService(store, collector, judge, auth, publisher, serviceTime);
        try {
            var source = registerSource(store);
            var posts = collector.syncSourceMaterialsForRewrite(source, WINDOW);
            assertEquals(1, posts.size());
            ObjectNode row = posts.get(0);
            String link = "https://x.com/source/status/970011";
            assertEquals(link, row.path("sourceUrl").asText());
            assertEquals(BODY, AutoRepostService.extractPureRewriteSourceText(row));
            assertTrue(AssistantTopics.links(row).contains(link));
            var preview = new PiAgentViralJudge.RewriteJudgment(true, 88, "", "", "适合独立改写", "上线速度在评审这一步拉开了差距。");
            when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(preview);
            when(judge.evaluateAndWriteRewrite(any())).thenReturn(new PiAgentViralJudge.RewriteJudgment(false, 40, "", "", "不符本周选题", ""));
            assertEquals(preview.rewrittenText(), service.previewRewrite("7", object().put("materialId", "970011"))
                    .path("rewrite").path("rewrittenText").asText());
            assertTrue(service.authorTimeline("7", object()).path("items").get(0).path("rewrite").path("candidate").asBoolean());
            service.processAuthorRewriteOnce(source, "42", "owner");
            verify(judge).evaluateAndWriteRewrite(any());
            verifyNoInteractions(auth, publisher);
        } finally {
            service.shutdown();
            collector.stop();
            executor.shutdown();
            server.stop(0);
        }
    }

    @Test void onlyTheOriginalPostMetadataIsExemptFromLinkChecks() {
        ObjectNode plain = object().put("id", "970011").put("kind", "POST").put("text", BODY);
        for (String url : List.of("https://x.com/source/status/970011", "https://twitter.com/i/web/status/970011?s=20"))
            assertTrue(AutoRepostService.isPureTextRewriteCandidate(plain.deepCopy().put("sourceUrl", url)), url);
        for (String other : List.of("https://news.example/status/970011", "https://x.com.evil.example/source/status/970011",
                "https://x.com/source/status/970012", "https://x.com/source/status/970011/photo/1", "https://x.com/i/article/970011"))
            assertFalse(AutoRepostService.isPureTextRewriteCandidate(plain.deepCopy().put("sourceUrl", other)), other);
        for (String bodyLink : List.of("https://x.com/source/status/970011", "https://t.co/abc", "t.co/abc", "www.example.org/a", "https://news.example/analysis"))
            assertFalse(AutoRepostService.isPureTextRewriteCandidate(plain.deepCopy().put("text", BODY + " " + bodyLink)));
        for (ObjectNode linked : List.of(plain.deepCopy().put("hasMedia", true), plain.deepCopy().put("quotedId", "123"),
                plain.deepCopy().put("articleId", "55"), plain.deepCopy().put("kind", "QUOTE")))
            assertFalse(AutoRepostService.isPureTextRewriteCandidate(linked));
    }

    private static class MutableClock extends Clock {
        Instant current = Instant.parse("2026-11-27T01:00:00Z");
        @Override public Instant instant() { return current; }
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        void advance(Duration duration) { current = current.plus(duration); }
    }

    private TextPost post(String id, Instant time) {
        var post = mock(TextPost.class);
        when(post.id()).thenReturn(id);
        when(post.authorId()).thenReturn("7");
        when(post.username()).thenReturn("source");
        when(post.publishedAt()).thenReturn(time);
        when(post.kind()).thenReturn(TweetKind.POST);
        when(post.text()).thenReturn(BODY + " " + id);
        when(post.sourceUrl()).thenReturn("https://x.com/source/status/" + id);
        when(post.views()).thenReturn(15_000L);
        return post;
    }

    @Test void listenerAndBackfillKeepThirtyDaysBeyondNormalRetentionAndDeleteExpiredSnapshots() throws Exception {
        var store = new AssistantStore(data, mapper);
        var clock = new MutableClock();
        var provider = mock(TwitterApiIoProvider.class);
        var collector = new SourceCollector(store, provider, null, mapper, clock);
        var judge = mock(PiAgentViralJudge.class);
        var publish = mock(XPublishClient.class);
        var service = new AutoRepostService(store, null, judge, mock(XOAuthService.class), publish, clock);
        try {
            var recent = post("101", clock.instant().minus(Duration.ofHours(1)));
            var old = post("202", clock.instant().minus(Duration.ofDays(9)));
            var older = post("303", clock.instant().minus(Duration.ofDays(21)));
            var expired = post("404", clock.instant().minus(Duration.ofDays(31)));
            var src = registerSource(store);
            when(provider.fetchTextPostsPage(eq("source"), eq("7"), eq(""), eq(100), any()))
                    .thenReturn(new TextPostPage(null, List.of(recent, old, expired), "", true));
            ReflectionTestUtils.invokeMethod(collector, "collectSourcePage", src);
            assertNull(store.tx(tx -> tx.get("material", "202")));
            assertEquals(BODY + " 202", store.tx(tx -> tx.rewriteMaterial("202", clock.millis())).path("text").asText());
            assertNull(store.tx(tx -> tx.rewriteMaterial("404", clock.millis())));
            String topic = store.tx(tx -> AssistantTopics.topicOf(tx, "101"));
            assertFalse(topic.isBlank());

            when(provider.fetchTextPostsPage(eq("source"), eq("7"), eq(""), eq(40), any()))
                    .thenReturn(new TextPostPage(null, List.of(recent), "next-page", false));
            when(provider.fetchTextPostsPage(eq("source"), eq("7"), eq("next-page"), eq(40), any()))
                    .thenReturn(new TextPostPage(null, List.of(old, older, expired), "", true));
            collector.syncSourceMaterialsForRewrite(src, WINDOW);
            clock.advance(Duration.ofMinutes(11));
            List<ObjectNode> rows = collector.syncSourceMaterialsForRewrite(src, WINDOW);
            assertEquals(3, rows.size());
            assertEquals(Set.of("101", "202", "303"), rows.stream().map(row -> row.path("id").asText()).collect(Collectors.toSet()));
            verify(provider).fetchTextPostsPage(eq("source"), eq("7"), eq("next-page"), eq(40), any());
            assertNull(store.tx(tx -> tx.get("material", "303")));

            clock.advance(Duration.ofDays(3));
            collector.purgeExpired();
            assertNull(store.tx(tx -> tx.get("material", "101")));
            assertNull(store.tx(tx -> tx.get("topic", topic)));
            assertEquals(topic, store.tx(tx -> tx.rewriteMaterial("101", clock.millis())).path("rewriteTopicId").asText());
            assertEquals(3, service.authorTimeline("7", object()).path("summary").path("wouldRewriteCount").asInt());
            when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(new PiAgentViralJudge.RewriteJudgment(true, 88, "", "", "", "独立稿件"));
            assertEquals("303", service.previewRewrite("7", object().put("materialId", "303")).path("material").path("id").asText());
            when(judge.evaluateAndWriteRewrite(any())).thenReturn(new PiAgentViralJudge.RewriteJudgment(false, 30, "", "", "不适合", ""));
            service.processAuthorRewriteOnce(src, "42", "owner");
            verify(judge, times(3)).evaluateAndWriteRewrite(any());
            verifyNoInteractions(publish);

            clock.advance(Duration.ofDays(30));
            collector.purgeExpired();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("assistant.sqlite"));
                 var cmd = db.createStatement(); var count = cmd.executeQuery("SELECT count(*) FROM rewrite_material_cache")) {
                assertEquals(0, count.getInt(1));
            }
        } finally { service.shutdown(); collector.stop(); }
    }
}
