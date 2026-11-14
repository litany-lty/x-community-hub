package com.xops.hub;

import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MainPostMetricRefreshTest {
    @Test void metricBatchAdmittedBeforeSoftDeadlineCanFinishAfterItButExpiredBatchesAreSkipped() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new ExactFollowProviderTest().new Fixture(uri -> {
            calls.incrementAndGet();
            try { Thread.sleep(800); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ExactFollowProviderTest.Reply.ok("{\"tweets\":[]}");
        })) {
            var pacerMethod = com.xops.hub.provider.TwitterApiIoProvider.class.getDeclaredMethod("pacerFor", String.class);
            pacerMethod.setAccessible(true);
            Object pacer = pacerMethod.invoke(fixture.provider, "local-fixture");
            var requestMethod = Arrays.stream(com.xops.hub.provider.TwitterApiIoProvider.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("getJsonOnceBeforeAsync") && m.getParameterCount() == 7)
                    .findFirst().orElseThrow();
            requestMethod.setAccessible(true);
            Instant admissionDeadline = Instant.now().plusMillis(500);
            var response = invokeMetricBatch(requestMethod, fixture, pacer, admissionDeadline);
            assertThat(response.join()).isNotNull();
            assertThat(Instant.now()).isAfter(admissionDeadline);
            var expired = invokeMetricBatch(requestMethod, fixture, pacer, Instant.now().minusSeconds(1));
            assertThat(expired.join()).isNull();
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    private CompletableFuture<?> invokeMetricBatch(java.lang.reflect.Method method,
            ExactFollowProviderTest.Fixture fixture, Object pacer, Instant deadline) throws Exception {
        var args = new ArrayList<Object>(List.of(fixture.properties.getTwitterapiIoBaseUrl() + "/metrics",
                "local-fixture", pacer, deadline, new java.util.concurrent.atomic.AtomicBoolean(), 15_000L, false));
        return (CompletableFuture<?>) method.invoke(fixture.provider, args.toArray());
    }

    @Test void capturedTwelveSecondMetricBatchDoesNotLoseRefreshedMetrics() throws Exception {
        Instant oldSync = Instant.now().minusSeconds(600);
        Instant created = oldSync.minusSeconds(3600);
        var profile = new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, "");
        var coverage = List.of(new long[]{oldSync.minusSeconds(30 * 86400L).getEpochSecond(), oldSync.getEpochSecond()});
        var stored = AccountStore.merge(null, profile, oldSync,
                List.of(tweet("slow-main", created, TweetKind.POST, false, RawTweet.ALL_METRICS)),
                List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
        try (var fixture = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ExactFollowProviderTest.Reply.ok(
                    "{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            if (uri.getPath().equals("/twitter/tweets")) {
                try { Thread.sleep(12_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ExactFollowProviderTest.Reply.ok("{\"tweets\":[{\"id\":\"slow-main\",\"createdAt\":\"" + created
                        + "\",\"viewCount\":100,\"likeCount\":3,\"replyCount\":2,\"retweetCount\":1,\"quoteCount\":0,\"bookmarkCount\":1}]}");
            }
            return ExactFollowProviderTest.Reply.ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            long started = System.nanoTime();
            var result = fixture.provider.syncStore("self", stored, 30, true, null);
            assertThat(result.store().tweets()).singleElement().satisfies(t -> assertThat(t.unavailableMetrics()).isEmpty());
            assertThat(result.degraded()).isFalse();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(15_500);
        }
    }

    @Test void boundedRefreshRepairsUnknownMainPostsBeforeOldRepliesAndThreadFloors() throws Exception {
        Instant oldSync = Instant.now().minusSeconds(600);
        Instant created = oldSync.minusSeconds(3600);
        var tweets = new ArrayList<RawTweet>();
        for (int i = 0; i < 55; i++) tweets.add(tweet("reply-" + i, created, TweetKind.REPLY, false, RawTweet.ALL_METRICS));
        tweets.add(tweet("floor", created, TweetKind.POST, true, RawTweet.ALL_METRICS));
        tweets.add(tweet("known-main", created, TweetKind.POST, false, Set.of()));
        Set<String> missingMain = new HashSet<>();
        for (int i = 0; i < 49; i++) {
            String id = "main-" + i;
            missingMain.add(id);
            tweets.add(tweet(id, created, i == 48 ? TweetKind.QUOTE : TweetKind.POST, false, RawTweet.ALL_METRICS));
        }
        var profile = new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, "");
        var coverage = List.of(new long[]{oldSync.minusSeconds(30 * 86400L).getEpochSecond(), oldSync.getEpochSecond()});
        var stored = AccountStore.merge(null, profile, oldSync, tweets, List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
        var requested = new AtomicReference<List<String>>();
        try (var fixture = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ExactFollowProviderTest.Reply.ok(
                    "{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            if (uri.getPath().equals("/twitter/tweets")) {
                List<String> ids = Arrays.asList(URLDecoder.decode(uri.getRawQuery().split("=", 2)[1], StandardCharsets.UTF_8).split(","));
                assertThat(requested.compareAndSet(null, ids)).isTrue();
                String nodes = ids.stream().map(id -> "{\"id\":\"" + id + "\",\"createdAt\":\"" + created
                        + "\",\"viewCount\":100,\"likeCount\":3,\"replyCount\":2,\"retweetCount\":1,\"quoteCount\":0,\"bookmarkCount\":1"
                        + (id.startsWith("reply-") ? ",\"isReply\":true,\"inReplyToUsername\":\"peer\"" : "")
                        + (id.equals("main-48") ? ",\"quoted_tweet\":{\"author\":{\"id\":\"peer-id\",\"userName\":\"peer\"}}" : "")
                        + "}").collect(Collectors.joining(","));
                return ExactFollowProviderTest.Reply.ok("{\"tweets\":[" + nodes + "]}");
            }
            return ExactFollowProviderTest.Reply.ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            var result = fixture.provider.syncStore("self", stored, 30, true, null);
            assertThat(requested.get()).hasSize(50).containsAll(missingMain);
            assertThat(result.store().tweets().stream().filter(t -> missingMain.contains(t.id())))
                    .hasSize(49).allSatisfy(t -> assertThat(t.unavailableMetrics()).isEmpty());
            assertThat(result.store().tweets().stream().filter(t -> t.id().equals("floor")))
                    .singleElement().satisfies(t -> assertThat(t.unavailableMetrics()).containsExactlyInAnyOrderElementsOf(RawTweet.ALL_METRICS));
            assertThat(result.store().tweets().stream().filter(t -> t.id().equals("main-48")))
                    .singleElement().satisfies(t -> assertThat(t.kind()).isEqualTo('Q'));
            requested.set(null);
            fixture.provider.syncStore("self", result.store(), 30, true, null);
            assertThat(requested.get()).hasSize(50).allSatisfy(id -> assertThat(id).startsWith("reply-"));
        }
    }

    private RawTweet tweet(String id, Instant created, TweetKind kind, boolean floor, Set<String> unknown) {
        return new RawTweet(id, id, created, kind, kind == TweetKind.REPLY ? "peer" : null, null, List.of(),
                false, floor, 0, 0, 0, 0, 0, floor, floor ? "root" : id, floor ? "root" : "", unknown);
    }
}
