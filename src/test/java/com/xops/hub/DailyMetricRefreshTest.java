package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DailyMetricRefreshTest {
    @TempDir Path temp;

    @Test void incrementalDailySearchNeverRequeriesCoveredHistoryAndRejectsForeignRows() throws Exception {
        Instant oldSync = Instant.now().minusSeconds(7200);
        var self = new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, "");
        long coveredFrom = oldSync.minusSeconds(30 * 86400L).getEpochSecond();
        var coverage = List.of(new long[]{coveredFrom, oldSync.getEpochSecond()});
        var stored = AccountStore.merge(null, self, oldSync, List.of(), List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
        var coveredHistoryQueries = new java.util.concurrent.atomic.AtomicInteger();
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            if (uri.getPath().endsWith("/advanced_search")) {
                String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                var bounds = java.util.regex.Pattern.compile("(?:since_time:|until_time:)(\\d+)").matcher(query);
                var times = new ArrayList<Long>();
                while (bounds.find()) times.add(Long.parseLong(bounds.group(1)));
                if (times.size() == 2 && times.get(0) < oldSync.getEpochSecond()) {
                    coveredHistoryQueries.incrementAndGet();
                }
                if (query.contains("query=from:self") && times.size() == 2) {
                    String foreign = metric("foreign", Instant.ofEpochSecond(times.get(0) + 10));
                    foreign = foreign.substring(0, foreign.length() - 1) + ",\"author\":{\"id\":\"other-id\",\"userName\":\"other\"}}";
                    return ok("{\"tweets\":[" + foreign + "],\"has_next_page\":false}");
                }
            }
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            f.properties.setDiskCacheDir(temp.toString());
            var provider = new TwitterApiIoProvider(f.properties, new ObjectMapper().findAndRegisterModules(), f.pool);
            var result = provider.syncStoreByHours("self", stored, 24, true, true, null, "self-id");
            assertThat(coveredHistoryQueries.get()).isZero();
            assertThat(result.store().tweets()).isEmpty();
            assertThat(result.degraded()).isTrue();
            long retainedFrom = Math.max(coveredFrom,
                    result.store().syncedAtInstant().minus(AccountStore.RETENTION).getEpochSecond());
            assertThat(AccountStore.missingRanges(result.store().tweetCoverage(), retainedFrom, oldSync.getEpochSecond())).isEmpty();
            assertThat(AccountStore.missingRanges(result.store().tweetCoverage(), oldSync.getEpochSecond() + 1, result.store().syncedAt())).isNotEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "missing", "partial", "wrong-author-id", "wrong-author-handle", "missing-first", "batch-failure"})
    void dailyRefreshUpdatesCachedMetricsInBoundedResumableBatchesAndPrunesConfirmedDeletions(String responseMode) throws Exception {
        boolean omitLastMetric = responseMode.equals("missing");
        boolean rejectLastMetric = responseMode.startsWith("wrong-author");
        boolean missingFirstMetric = responseMode.equals("missing-first");
        Instant oldSync = Instant.now().minusSeconds(7200);
        Instant oldCreated = oldSync.minusSeconds(3600);
        Instant newCreated = Instant.now().minusSeconds(10);
        var self = new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, "");
        var oldTweets = new ArrayList<RawTweet>();
        // 101 metric targets (old-0: reply with unavailable metrics, old-1: quote, old-2..old-100: primary posts)
        oldTweets.add(new RawTweet("old-0", "cached", oldCreated, TweetKind.REPLY, "peer", null, List.of(),
                false, false, 10, 1, 0, 0, 9, false, "old-0", "", RawTweet.ALL_METRICS));
        oldTweets.add(new RawTweet("old-1", "cached", oldCreated, TweetKind.QUOTE, "peer", null, List.of(),
                false, false, 10, 1, 0, 0, 9));
        for (int i = 2; i < 101; i++) oldTweets.add(new RawTweet("old-" + i, "cached", oldCreated,
                TweetKind.POST, null, null, List.of(), false, false, 10, 1, 0, 0, 9));
        // Cached ordinary reply & pure retweet with complete metrics inside 24h must NOT be re-requested every hour
        oldTweets.add(new RawTweet("cached-reply", "@peer cached", oldCreated,
                TweetKind.REPLY, "peer", null, List.of(), false, false, 15, 2, 0, 0, 0));
        oldTweets.add(new RawTweet("cached-retweet", "RT @peer cached", oldCreated,
                TweetKind.RETWEET, null, null, List.of(), false, false, 25, 3, 0, 1, 0));
        oldTweets.add(new RawTweet("outside-day", "preserved", oldSync.minusSeconds(86400),
                TweetKind.POST, null, null, List.of(), false, false, 10, 1, 0, 0, 0));
        var coverage = List.of(new long[]{oldSync.minusSeconds(30 * 86400L).getEpochSecond(), oldSync.getEpochSecond()});
        var recentPeer = new XUserSummary("recent-id", "recent", "Recent", "", "", false, 0, 0, 0, "");
        var ancientPeer = new XUserSummary("ancient-id", "ancient", "Ancient", "", "", false, 0, 0, 0, "");
        var oldInbound = List.of(new InboundInteraction("recent-interaction", recentPeer, oldCreated, InboundKind.MENTION_ME, "recent"),
                new InboundInteraction("ancient-interaction", ancientPeer, oldSync.minusSeconds(172800), InboundKind.MENTION_ME, "older"));
        var stored = AccountStore.merge(null, self, oldSync, oldTweets, oldInbound, coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
        var batches = Collections.synchronizedList(new ArrayList<List<String>>());
        var blueBatches = Collections.synchronizedList(new ArrayList<List<String>>());
        var coveredSliceQueries = new java.util.concurrent.atomic.AtomicInteger();
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            if (uri.getPath().equals("/twitter/tweets")) {
                var ids = Arrays.asList(URLDecoder.decode(uri.getRawQuery().split("=", 2)[1], StandardCharsets.UTF_8).split(","));
                batches.add(List.copyOf(ids));
                if (responseMode.equals("batch-failure")) return new ExactFollowProviderTest.Reply(500, "{}");
                var rows = new ArrayList<String>();
                for (String id : ids) if (!(omitLastMetric && id.equals("old-100")) && !(missingFirstMetric && id.equals("old-0"))) {
                    String row = metric(id, oldCreated);
                    if (responseMode.equals("partial") && id.equals("old-100")) row = row.replace(",\"bookmarkCount\":3", "");
                    if (rejectLastMetric && id.equals("old-100")) row = row.substring(0, row.length() - 1)
                            + ",\"author\":{\"id\":\"" + (responseMode.equals("wrong-author-id") ? "other-id" : "self-id")
                            + "\",\"userName\":\"" + (responseMode.equals("wrong-author-handle") ? "other" : "self") + "\"}}";
                    rows.add(row);
                }
                return ok("{\"tweets\":[" + String.join(",", rows) + "]}");
            }
            if (uri.getPath().endsWith("/advanced_search")) {
                String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                var bounds = java.util.regex.Pattern.compile("(?:since_time:|until_time:)(\\d+)").matcher(query);
                var times = new ArrayList<Long>();
                while (bounds.find()) times.add(Long.parseLong(bounds.group(1)));
                if (times.size() == 2 && times.get(0) < oldSync.getEpochSecond()) {
                    coveredSliceQueries.incrementAndGet();
                }
                if (query.contains("query=from:self")) {
                    return ok("{\"tweets\":[" + metric("new-post", newCreated) + "],\"has_next_page\":false}");
                }
                return ok("{\"tweets\":[{\"id\":\"new-inbound\",\"text\":\"hello @self\",\"createdAt\":\""
                        + newCreated + "\",\"author\":{\"id\":\"peer-id\",\"userName\":\"peer\",\"isBlueVerified\":true}}],\"has_next_page\":false}");
            }
            if (uri.getPath().endsWith("/batch_info_by_ids")) {
                blueBatches.add(Arrays.asList(URLDecoder.decode(uri.getRawQuery().split("=", 2)[1], StandardCharsets.UTF_8).split(",")));
                return ok("{\"users\":[],\"status\":\"success\"}");
            }
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            XOpsProperties properties = f.properties;
            properties.setDiskCacheDir(temp.toString());
            var provider = new TwitterApiIoProvider(properties, new ObjectMapper().findAndRegisterModules(), f.pool);
            var first = provider.syncStoreByHours("self", stored, 24, true, true, null, "self-id");
            assertThat(coveredSliceQueries.get()).isZero();
            assertThat(batches).hasSize(1);
            assertThat(batches.get(0)).hasSize(50);
            assertThat(first.store().dailyRefreshedAt()).isZero();
            assertThat(first.pendingDailyMetricUpdates()).isEqualTo(51);
            assertThat(first.incompleteDailyMetricUpdates()).isEqualTo(responseMode.equals("batch-failure") ? 101 : 51);
            assertThat(first.dailyDataComplete()).isFalse();
            if (responseMode.equals("batch-failure")) {
                assertThat(first.dailyMetricBatchFailed()).isTrue();
                assertThat(first.progressed()).isTrue(); // New stream progress must not hide a failed metric batch.
                assertThat(first.degraded()).isTrue();
                assertThat(first.store().tweets()).extracting(AccountStore.StoredTweet::id).contains("old-0", "old-100");
                return;
            }
            assertThat(first.dailyMetricBatchFailed()).isFalse();
            if (missingFirstMetric) {
                assertThat(first.degraded()).isFalse();
                assertThat(first.store().tweets()).extracting(AccountStore.StoredTweet::id).doesNotContain("old-0");
            } else {
                assertThat(first.store().tweets()).anySatisfy(t -> {
                    assertThat(t.id()).isEqualTo("old-0");
                    assertThat(t.kind()).isEqualTo('R');
                    assertThat(t.stats()).containsExactly(new long[]{100, 7, 2, 1, 3});
                });
            }
            assertThat(first.store().tweets()).anySatisfy(t -> assertThat(t.id()).isEqualTo("new-post"));
            assertThat(first.store().inbound()).anySatisfy(t -> assertThat(t.id()).isEqualTo("new-inbound"));
            var second = provider.syncStoreByHours("self", first.store(), 24, false, true, null, "self-id");
            assertThat(second.apiPages()).isZero();
            assertThat(second.progressed()).isTrue();
            assertThat(second.pendingDailyMetricUpdates()).isEqualTo(1);
            var third = provider.syncStoreByHours("self", second.store(), 24, false, true, null, "self-id");
            assertThat(batches).hasSize(3);
            assertThat(batches.stream().map(List::size).toList()).containsExactly(50, 50, 1);
            assertThat(batches.stream().flatMap(Collection::stream).toList())
                    .hasSize(101)
                    .doesNotHaveDuplicates()
                    .doesNotContain("outside-day", "cached-reply", "cached-retweet");
            assertThat(third.store().dailyRefreshStartedAt()).isEqualTo(first.store().dailyRefreshStartedAt());
            assertThat(third.store().syncedAt()).isEqualTo(first.store().syncedAt());
            if (responseMode.equals("partial") || rejectLastMetric) {
                assertThat(third.store().dailyRefreshedAt()).isZero();
                assertThat(third.incompleteDailyMetricUpdates()).isEqualTo(1);
                assertThat(third.dailyDataComplete()).isFalse();
                assertThat(third.store().tweets()).anySatisfy(t -> {
                    assertThat(t.id()).isEqualTo("old-100");
                    assertThat(t.stats()).containsExactly(rejectLastMetric ? new long[]{10, 1, 0, 0, 9} : new long[]{100, 7, 2, 1, 9});
                });
            } else {
                // "complete", "missing" (old-100 deleted), and "missing-first" (old-0 deleted) all prune deleted rows and finish cleanly
                if (omitLastMetric) {
                    assertThat(third.store().tweets()).extracting(AccountStore.StoredTweet::id).doesNotContain("old-100");
                }
                assertThat(third.store().dailyRefreshedAt()).isEqualTo(third.store().dailyRefreshStartedAt()).isPositive();
                assertThat(third.incompleteDailyMetricUpdates()).isZero();
                assertThat(third.dailyDataComplete()).isTrue();
            }
            assertThat(third.pendingDailyMetricUpdates()).isZero();
            assertThat(third.dailyMetricBatchFailed()).isEqualTo(rejectLastMetric);
            provider.syncStoreByHours("self", third.store(), 24, false, true, null, "self-id");
            assertThat(batches).hasSize(3);
            assertThat(coveredSliceQueries.get()).isZero();
            assertThat(blueBatches).hasSize(1);
            assertThat(blueBatches.get(0)).contains("recent-id");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dailyGraphReuseChecksBothCountsWithoutDependingOnAFollowedAuthor(boolean followerCountChanged) throws Exception {
        Instant oldSync = Instant.now().minusSeconds(7200);
        var self = new XUserSummary("self-id", "self", "Self", "", "", false, 1, followerCountChanged ? 1 : 0, 0, "");
        var peer = new XUserSummary("peer-id", "peer", "Peer", "", "", false, 0, 0, 0, "");
        var followedAuthor = new XUserSummary("author-id", "elidlicom", "Author", "", "", false, 0, 0, 0, "");
        var coverage = List.of(new long[]{oldSync.minusSeconds(30 * 86400L).getEpochSecond(), oldSync.getEpochSecond()});
        var stored = AccountStore.merge(null, self, oldSync, List.of(), List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(followerCountChanged ? List.of(followedAuthor) : List.of(),
                        List.of(peer), Set.of(), false, false, false, false));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":"
                    + (followerCountChanged ? 1 : 0) + ",\"followers\":"
                    + (followerCountChanged ? 2 : 1) + "}}");
            if (uri.getPath().endsWith("/followings")) return ok("{\"followings\":[{\"id\":\"author-id\",\"userName\":\"elidlicom\"}],\"has_next_page\":false}");
            if (uri.getPath().endsWith("/followers")) {
                calls.incrementAndGet();
                return ok("{\"followers\":[{\"id\":\"peer-id\",\"userName\":\"peer\"}"
                        + (followerCountChanged ? ",{\"id\":\"new-peer-id\",\"userName\":\"newpeer\"}" : "") + "],\"has_next_page\":false}");
            }
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            f.properties.setDiskCacheDir(temp.toString());
            var provider = new TwitterApiIoProvider(f.properties, new ObjectMapper().findAndRegisterModules(), f.pool);
            var result = provider.syncStoreByHours("self", stored, 24, true, true, null, "self-id");
            assertThat(calls.get()).isEqualTo(followerCountChanged ? 1 : 0);
            assertThat(result.graphReused()).isEqualTo(!followerCountChanged);
            assertThat(result.store().followers()).hasSize(followerCountChanged ? 2 : 1);
        }
    }

    @Test
    void initialColdStartDoublesSliceConcurrencyComparedWithWarmSync() throws Exception {
        var defaultProps = new com.xops.hub.config.XOpsProperties();
        var defaultPool = new com.xops.hub.provider.SharedApiExecutor(defaultProps);
        try {
            var poolMetrics = defaultPool.snapshotMetrics();
            assertThat(poolMetrics.get("corePoolSize")).isEqualTo(20);
            assertThat(poolMetrics.get("maxPoolSize")).isEqualTo(36);
            assertThat(poolMetrics.get("maxParallelSlices")).isEqualTo(4);
            assertThat(poolMetrics.get("initialMaxParallelSlices")).isEqualTo(8);

            assertThat(defaultPool.suggestSliceConcurrency(25 * 3600L, 12, 24, false, false)).isEqualTo(4);
            assertThat(defaultPool.suggestSliceConcurrency(25 * 3600L, 12, 24, false, true)).isEqualTo(8);
            assertThat(defaultPool.suggestSliceConcurrency(8 * 3600L, 12, 24, false, false)).isEqualTo(3);
            assertThat(defaultPool.suggestSliceConcurrency(8 * 3600L, 12, 24, false, true)).isEqualTo(6);
            assertThat(defaultPool.suggestSliceConcurrency(3 * 3600L, 12, 24, false, false)).isEqualTo(2);
            assertThat(defaultPool.suggestSliceConcurrency(3 * 3600L, 12, 24, false, true)).isEqualTo(4);

            var tweetSubSlices = new java.util.concurrent.atomic.AtomicInteger();
            var inboundSubSlices = new java.util.concurrent.atomic.AtomicInteger();
            var inFlight = new java.util.concurrent.atomic.AtomicInteger();
            var peakInFlight = new java.util.concurrent.atomic.AtomicInteger();

            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var serverExec = java.util.concurrent.Executors.newCachedThreadPool();
            server.setExecutor(serverExec);
            server.createContext("/", exchange -> {
                int cur = inFlight.incrementAndGet();
                peakInFlight.accumulateAndGet(cur, Math::max);
                try {
                    var uri = exchange.getRequestURI();
                    String body;
                    if (uri.getPath().endsWith("/info")) {
                        body = "{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}";
                    } else if (uri.getPath().endsWith("/advanced_search")) {
                        String q = java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
                        long since = Long.parseLong(q.replaceAll(".*since_time:(\\d+).*", "$1"));
                        long until = Long.parseLong(q.replaceAll(".*until_time:(\\d+).*", "$1"));
                        boolean isTweets = q.startsWith("queryType=Latest&query=from:self") || !q.contains("-from:self");
                        if (until - since > 20 * 3600L) {
                            Instant tailTs = Instant.ofEpochSecond(until - 2 * 3600L);
                            body = isTweets
                                    ? "{\"tweets\":[{\"id\":\"p-top\",\"text\":\"hello\",\"createdAt\":\"" + tailTs
                                            + "\",\"author\":{\"id\":\"self-id\",\"userName\":\"self\"}}],\"has_next_page\":true,\"next_cursor\":\"c1\"}"
                                    : "{\"tweets\":[{\"id\":\"m-top\",\"text\":\"@self hi\",\"isReply\":true,\"inReplyToUsername\":\"self\",\"createdAt\":\""
                                            + tailTs + "\",\"author\":{\"id\":\"peer-1\",\"userName\":\"peer1\"}}],\"has_next_page\":true,\"next_cursor\":\"c1\"}";
                        } else {
                            if (isTweets) {
                                tweetSubSlices.incrementAndGet();
                            } else {
                                inboundSubSlices.incrementAndGet();
                            }
                            Thread.sleep(60L);
                            body = "{\"tweets\":[],\"has_next_page\":false}";
                        }
                    } else {
                        body = "{\"tweets\":[],\"users\":[],\"status\":\"success\",\"has_next_page\":false}";
                    }
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
            server.start();
            try {
                defaultProps.setDiskCacheDir(temp.toString());
                defaultProps.setTwitterapiIoKey("local-cold-2x");
                defaultProps.setTwitterapiIoBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
                var provider = new TwitterApiIoProvider(defaultProps, new ObjectMapper().findAndRegisterModules(), defaultPool);

                var cold = provider.syncStoreByHours("self", null, 24, true, true, null, "self-id");
                assertThat(cold.degraded()).isFalse();
                assertThat(tweetSubSlices.get()).isEqualTo(8);
                assertThat(inboundSubSlices.get()).isEqualTo(8);
                assertThat(peakInFlight.get()).isGreaterThanOrEqualTo(10);

                tweetSubSlices.set(0);
                inboundSubSlices.set(0);
                var selfSummary = new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, "");
                var warmEmptyCoverage = AccountStore.merge(
                        null, selfSummary, Instant.now().minusSeconds(7200), List.of(), List.of(), List.of(), List.of(),
                        new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
                var warm = provider.syncStoreByHours("self", warmEmptyCoverage, 24, true, true, null, "self-id");
                assertThat(warm.degraded()).isFalse();
                assertThat(tweetSubSlices.get()).isLessThanOrEqualTo(4);
                assertThat(inboundSubSlices.get()).isLessThanOrEqualTo(4);
            } finally {
                server.stop(0);
                serverExec.shutdownNow();
            }
        } finally {
            defaultPool.shutdown();
        }
    }

    @Test
    void higherParallelSliceConfigScalesIntermediateSpanConcurrencyProportionally() {
        var props8 = new XOpsProperties();
        props8.setApiMaxParallelSlices(8);
        var pool8 = new com.xops.hub.provider.SharedApiExecutor(props8);
        try {
            var metrics = pool8.snapshotMetrics();
            assertThat(metrics.get("maxParallelSlices")).isEqualTo(8);
            assertThat(metrics.get("initialMaxParallelSlices")).isEqualTo(16);

            assertThat(pool8.suggestSliceConcurrency(25 * 3600L, 20, 40, false, false)).isEqualTo(8);
            assertThat(pool8.suggestSliceConcurrency(25 * 3600L, 20, 40, false, true)).isEqualTo(16);
            assertThat(pool8.suggestSliceConcurrency(8 * 3600L, 20, 40, false, false)).isEqualTo(6);
            assertThat(pool8.suggestSliceConcurrency(8 * 3600L, 20, 40, false, true)).isEqualTo(12);
            assertThat(pool8.suggestSliceConcurrency(3 * 3600L, 20, 40, false, false)).isEqualTo(4);
            assertThat(pool8.suggestSliceConcurrency(3 * 3600L, 20, 40, false, true)).isEqualTo(8);
        } finally {
            pool8.shutdown();
        }
    }

    @Test
    void splitInboundSearchQueriesDeduplicateItemsAndIntersectCoverage() throws Exception {
        Instant now = Instant.now();
        Instant recentAt = now.minusSeconds(2 * 3600L);
        Instant midAt = now.minusSeconds(6 * 3600L);
        List<String> observedQueries = Collections.synchronizedList(new ArrayList<>());

        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) {
                return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            }
            if (uri.getPath().endsWith("/advanced_search")) {
                String rawQuery = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                observedQueries.add(rawQuery);
                if (rawQuery.contains("(to:self OR @self) -from:self")) {
                    String reply = "{\"id\":\"in-reply-1\",\"text\":\"@self hello\",\"isReply\":true,\"inReplyToUsername\":\"self\",\"createdAt\":\""
                            + recentAt + "\",\"author\":{\"id\":\"peer-1\",\"userName\":\"peer1\"}}";
                    String dupQuote = "{\"id\":\"in-quote-dup\",\"text\":\"@self quoting\",\"createdAt\":\""
                            + midAt + "\",\"quoted_tweet\":{\"author\":{\"id\":\"self-id\",\"userName\":\"self\"}},\"author\":{\"id\":\"peer-2\",\"userName\":\"peer2\"}}";
                    return ok("{\"tweets\":[" + reply + "," + dupQuote + "],\"has_next_page\":false}");
                }
                if (rawQuery.contains("quoted_user_id:self-id -from:self")) {
                    String dupQuote = "{\"id\":\"in-quote-dup\",\"text\":\"@self quoting\",\"createdAt\":\""
                            + midAt + "\",\"quoted_tweet\":{\"author\":{\"id\":\"self-id\",\"userName\":\"self\"}},\"author\":{\"id\":\"peer-2\",\"userName\":\"peer2\"}}";
                    String pureQuote = "{\"id\":\"in-quote-only\",\"text\":\"pure quote\",\"createdAt\":\""
                            + recentAt + "\",\"quoted_tweet\":{\"author\":{\"id\":\"self-id\",\"userName\":\"self\"}},\"author\":{\"id\":\"peer-3\",\"userName\":\"peer3\"}}";
                    return ok("{\"tweets\":[" + dupQuote + "," + pureQuote + "],\"has_next_page\":false}");
                }
            }
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            f.properties.setDiskCacheDir(temp.resolve("split-full").toString());
            f.properties.setSplitInboundSearchQueries(true);
            var provider = new TwitterApiIoProvider(f.properties, new ObjectMapper().findAndRegisterModules(), f.pool);
            var result = provider.syncStoreByHours("self", null, 24, true, true, null, "self-id");
            assertThat(result.degraded()).isFalse();
            assertThat(observedQueries).anyMatch(q -> q.contains("(to:self OR @self) -from:self"));
            assertThat(observedQueries).anyMatch(q -> q.contains("quoted_user_id:self-id -from:self"));
            assertThat(observedQueries).noneMatch(q -> q.contains("(to:self OR @self OR quoted_user_id:self-id)"));
            assertThat(result.store().inbound().stream().map(AccountStore.StoredInbound::id).toList())
                    .containsExactlyInAnyOrder("in-reply-1", "in-quote-dup", "in-quote-only");
            long to = result.store().syncedAt();
            long from = to - 24 * 3600L;
            assertThat(AccountStore.missingRanges(result.store().inboundCoverage(), from, to)).isEmpty();
        }

        // Partial coverage on quote stream must keep uncovered sub-range open while retaining fetched replies & quotes.
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) {
                return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            }
            if (uri.getPath().endsWith("/advanced_search")) {
                String rawQuery = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                if (rawQuery.contains("(to:self OR @self) -from:self")) {
                    String reply = "{\"id\":\"in-reply-ok\",\"text\":\"@self ok\",\"isReply\":true,\"inReplyToUsername\":\"self\",\"createdAt\":\""
                            + recentAt + "\",\"author\":{\"id\":\"peer-1\",\"userName\":\"peer1\"}}";
                    return ok("{\"tweets\":[" + reply + "],\"has_next_page\":false}");
                }
                if (rawQuery.contains("quoted_user_id:self-id -from:self")) {
                    long since = Long.parseLong(rawQuery.replaceAll(".*since_time:(\\d+).*", "$1"));
                    long until = Long.parseLong(rawQuery.replaceAll(".*until_time:(\\d+).*", "$1"));
                    if (until - since > 20 * 3600L && !rawQuery.contains("cursor=")) {
                        String quote = "{\"id\":\"in-quote-head\",\"text\":\"head quote\",\"createdAt\":\""
                                + midAt + "\",\"quoted_tweet\":{\"author\":{\"id\":\"self-id\",\"userName\":\"self\"}},\"author\":{\"id\":\"peer-3\",\"userName\":\"peer3\"}}";
                        return ok("{\"tweets\":[" + quote + "],\"has_next_page\":true,\"next_cursor\":\"q2\"}");
                    }
                    return new ExactFollowProviderTest.Reply(500, "{\"error\":\"upstream timeout\"}");
                }
            }
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            f.properties.setDiskCacheDir(temp.resolve("split-partial").toString());
            f.properties.setSplitInboundSearchQueries(true);
            var provider = new TwitterApiIoProvider(f.properties, new ObjectMapper().findAndRegisterModules(), f.pool);
            var result = provider.syncStoreByHours("self", null, 24, true, true, null, "self-id");
            assertThat(result.degraded()).isTrue();
            assertThat(result.store().inbound().stream().map(AccountStore.StoredInbound::id).toList())
                    .containsExactlyInAnyOrder("in-reply-ok", "in-quote-head");
            long to = result.store().syncedAt();
            long from = to - 24 * 3600L;
            assertThat(AccountStore.missingRanges(result.store().inboundCoverage(), midAt.getEpochSecond(), to)).isEmpty();
            assertThat(AccountStore.missingRanges(result.store().inboundCoverage(), from, midAt.getEpochSecond() - 1)).isNotEmpty();
        }
    }

    private ExactFollowProviderTest.Reply ok(String json) { return ExactFollowProviderTest.Reply.ok(json); }
    private String metric(String id, Instant at) {
        String classification = id.equals("old-0") ? ",\"isReply\":true,\"inReplyToUsername\":\"peer\""
                : id.equals("old-1") ? ",\"quoted_tweet\":{\"author\":{\"userName\":\"peer\"}}"
                : id.equals("old-2") ? ",\"text\":\"RT @peer mock\"" : "";
        return "{\"id\":\"" + id + "\",\"createdAt\":\"" + at
                + "\",\"viewCount\":100,\"likeCount\":7,\"replyCount\":2,\"retweetCount\":1,\"quoteCount\":0,\"bookmarkCount\":3" + classification + "}";
    }
}
