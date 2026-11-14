package com.xops.hub;

import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionalDataGapTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private ExactFollowProviderTest.Reply ok(String json) { return ExactFollowProviderTest.Reply.ok(json); }

    @Test void shortUncollectedIntervalRemainsAGap() {
        var coverage = List.of(new long[]{100, 200}, new long[]{250, 300});
        assertThat(AccountStore.mergeIntervals(coverage)).hasSize(2);
        assertThat(AccountStore.missingRanges(coverage, 100, 300)).singleElement()
                .satisfies(gap -> assertThat(gap).containsExactly(200, 250));
        assertThat(AccountStore.contiguousFrom(coverage, 300)).isEqualTo(250);
        assertThat(AccountStore.missingRanges(List.of(new long[]{100, 200}), 100, 230)).singleElement()
                .satisfies(gap -> assertThat(gap).containsExactly(200, 230));
    }

    @Test void repeatedSearchCursorDoesNotDeclareUnseenHistoryComplete() throws Exception {
        Instant now = Instant.now();
        AtomicInteger searches = new AtomicInteger();
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            if (uri.getPath().endsWith("advanced_search")) {
                searches.incrementAndGet();
                return ok("{\"tweets\":[{\"id\":\"one\",\"createdAt\":\"" + now.minusSeconds(60) + "\"}],\"has_next_page\":true,\"next_cursor\":\"stuck\"}");
            }
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(searches.get()).isGreaterThan(1);
            assertThat(TwitterApiIoProvider.snapshotOf(result.store(), "", result.degraded()).coveredWindowDays()).isLessThan(2);
            assertThat(result.degraded()).isTrue();
            var continued = f.provider.syncStore("self", result.store(), 2, false, null);
            assertThat(continued.progressed()).isFalse();
        }
    }

    @Test void malformedSuccessfulStreamDoesNotTurnMissingArrayIntoCoveredHistory() throws Exception {
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> uri.getPath().endsWith("/info")
                ? ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}")
                : ok("{\"status\":\"success\"}"))) {
            var result = f.provider.syncStore("self", null, 30, true, null);
            assertThat(result.store().tweetCoverage()).isEmpty();
            assertThat(result.store().inboundCoverage()).isEmpty();
            assertThat(result.degraded()).isTrue();
        }
    }

    @Test void officialMissingStreamPayloadDoesNotDeclareThirtyDaysCovered() throws Exception {
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().contains("/by/username/")) return ok("{\"data\":{\"id\":\"self-id\",\"username\":\"self\",\"public_metrics\":{\"followers_count\":0,\"following_count\":0}}}");
            return ok("{}");
        })) {
            f.properties.setOfficialBaseUrl(f.properties.getTwitterapiIoBaseUrl());
            f.properties.setOfficialBearerToken("local-fixture");
            var provider = new com.xops.hub.provider.OfficialXApiV2Provider(f.properties,
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), f.pool);
            var result = provider.fetchSnapshot("self", null);
            assertThat(result.coveredWindowDays()).isZero();
            assertThat(result.degradedPartial()).isTrue();
        }
    }

    @Test void officialSameHandleWithDifferentPeerIdsDoesNotCreateMutualFollow() throws Exception {
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().contains("/by/username/")) return ok("{\"data\":{\"id\":\"self-id\",\"username\":\"self\",\"public_metrics\":{\"followers_count\":1,\"following_count\":1}}}");
            if (uri.getPath().endsWith("/following")) return ok("{\"data\":[{\"id\":\"new-id\",\"username\":\"peer\"}]}");
            if (uri.getPath().endsWith("/followers")) return ok("{\"data\":[{\"id\":\"old-id\",\"username\":\"peer\"}]}");
            return ok("{\"meta\":{\"result_count\":0}}");
        })) {
            f.properties.setOfficialBaseUrl(f.properties.getTwitterapiIoBaseUrl());
            f.properties.setOfficialBearerToken("local-fixture");
            var provider = new com.xops.hub.provider.OfficialXApiV2Provider(f.properties,
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), f.pool);
            var result = provider.fetchSnapshot("self", null);
            assertThat(result.relationships()).singleElement().satisfies(r -> {
                assertThat(r.user().id()).isEqualTo("new-id");
                assertThat(r.iFollowThem()).isTrue();
                assertThat(r.theyFollowMe()).isFalse();
            });
            assertThat(result.followersTruncated()).isTrue();
        }
    }

    @Test void malformedDateDoesNotCreateActivityAtCurrentTimeOrCompleteItsPage() throws Exception {
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            return ok("{\"tweets\":[{\"id\":\"bad-date\",\"createdAt\":\"not-a-date\"}],\"has_next_page\":false}");
        })) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(result.store().tweets()).isEmpty();
            assertThat(result.store().tweetCoverage()).isEmpty();
            assertThat(result.degraded()).isTrue();
        }
    }

    @Test void stablePeerIdentityAndProfileFieldsSurviveStoreRoundTrip() throws Exception {
        var self = new XUserSummary("self-id", "self", "Self", "", "", false, 1, 1, 0, "");
        var peer = new XUserSummary("peer-id", "peer", "Peer", "", "bio", false, 12, 3, 55, "2020-01-01");
        var graph = new AccountStore.GraphUpdate(List.of(peer), List.of(peer), Set.of(), false, false, false, false);
        var merged = AccountStore.merge(null, self, Instant.now(), List.of(), List.of(), List.of(), List.of(), graph);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var reread = mapper.readValue(mapper.writeValueAsBytes(merged), AccountStore.StoredAccount.class);
        assertThat(AccountStore.followingUsers(reread).get(0)).isEqualTo(peer);
    }

    @Test void incompleteMetricRefreshKeepsPreviousMeasurementsAndTheirTimestamp() throws Exception {
        Instant old = Instant.now().minusSeconds(600);
        var self = new XUserSummary("self-id", "self", "self", "", "", false, 0, 0, 0, "");
        var post = new RawTweet("kept", "existing post", old.minusSeconds(60), TweetKind.POST,
                null, null, List.of(), false, false, 1000, 10, 2, 3, 4);
        var stored = AccountStore.merge(null, self, old, List.of(post), List.of(),
                List.of(new long[]{old.minusSeconds(180000).getEpochSecond(), old.getEpochSecond()}),
                List.of(new long[]{old.minusSeconds(180000).getEpochSecond(), old.getEpochSecond()}),
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            if (uri.getPath().equals("/twitter/tweets")) return ok("{\"tweets\":[{\"id\":\"kept\",\"createdAt\":\"" + post.createdAt() + "\",\"likeCount\":99}]}");
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            var result = f.provider.syncStore("self", stored, 2, true, null);
            assertThat(result.store().tweets()).singleElement().satisfies(tweet -> {
                assertThat(tweet.stats()).containsExactly(1000, 99, 2, 3, 4);
                assertThat(tweet.metricsFetchedAt()).isEqualTo(old.getEpochSecond());
                assertThat(tweet.metricsAttemptedAt()).isGreaterThan(old.getEpochSecond());
                assertThat(tweet.unavailableMetrics()).isEmpty();
            });
        }
    }

    @Test void boundedRelationshipChecksDoNotStarveCandidatesAfterTheFirstHundred() throws Exception {
        var checked = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var owner = new java.util.concurrent.atomic.AtomicReference<>("self-id");
        var requests = new AtomicInteger();
        var histories = new AtomicInteger();
        var failFirstHundred = new java.util.concurrent.atomic.AtomicBoolean();
        String users = java.util.stream.IntStream.range(0, 105)
                .mapToObj(i -> "{\"id\":\"id-" + i + "\",\"userName\":\"peer" + i + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            requests.incrementAndGet();
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"" + owner.get() + "\",\"userName\":\"self\",\"following\":105,\"followers\":1}}");
            if (uri.getPath().endsWith("/followings")) return ok("{\"followings\":[" + users + "],\"has_next_page\":false}");
            if (uri.getPath().endsWith("/followers")) return ok("{\"followers\":[],\"has_next_page\":false}");
            if (uri.getPath().endsWith("/check_follow_relationship")) {
                String peer = java.net.URLDecoder.decode(uri.getRawQuery().split("target_user_name=")[1], java.nio.charset.StandardCharsets.UTF_8);
                checked.add(peer);
                if (failFirstHundred.get() && Integer.parseInt(peer.substring(4)) < 100) return new ExactFollowProviderTest.Reply(500, "{}");
                return ok("{\"status\":\"success\",\"data\":{\"following\":true,\"followed_by\":false}}");
            }
            histories.incrementAndGet();
            return ok("{\"tweets\":[],\"has_next_page\":false}");
        })) {
            var first = f.provider.syncStore("self", null, 2, true, null);
            assertThat(checked).hasSize(100);
            assertThat(TwitterApiIoProvider.pendingRelationshipChecks(first.store())).isEqualTo(5);
            checked.clear();
            owner.set("other-owner");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.provider.continueRelationships("self", first.store(), "self-id"))
                    .isInstanceOf(com.xops.hub.provider.XDataProviderException.class).hasMessageContaining("ID");
            assertThat(checked).isEmpty();
            owner.set("self-id");
            int historyBefore = histories.get();
            var next = f.provider.continueRelationships("self", first.store(), "self-id");
            assertThat(checked).hasSize(5).contains("peer100", "peer101", "peer102", "peer103", "peer104");
            assertThat(next.store().relationshipEvidence()).hasSize(105);
            assertThat(TwitterApiIoProvider.pendingRelationshipChecks(next.store())).isZero();
            assertThat(histories.get()).isEqualTo(historyBefore);
            checked.clear();
            int before = requests.get();
            f.provider.continueRelationships("self", next.store(), "self-id");
            assertThat(checked).isEmpty();
            assertThat(requests.get()).isEqualTo(before);
            f.properties.setDiskCacheDir(temp.resolve("hub").toString());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var hub = new com.xops.hub.service.CommunityHubService(f.properties,
                    new com.xops.hub.provider.DemoXDataProvider(), f.provider, null, mapper);
            AccountStore.write(hub.resolveUserStoreFile("self"), next.store(), mapper);
            var completed = hub.analyzeAccountBundle("self", 2, null, false, "local", "self-id", true);
            assertThat(completed.pendingRelationshipChecks()).isZero();
            assertThat(completed.hasMoreHistoryForTarget()).isFalse();
            assertThat(requests.get()).isEqualTo(before);
            failFirstHundred.set(true);
            checked.clear();
            var failed = f.provider.syncStore("self", null, 2, true, null);
            assertThat(checked).hasSize(100);
            assertThat(failed.store().relationshipEvidence()).isEmpty();
            assertThat(failed.store().relationshipAttemptedAt()).hasSize(100);
            assertThat(TwitterApiIoProvider.pendingRelationshipChecks(failed.store())).isEqualTo(105);
            var persisted = mapper.readValue(mapper.writeValueAsBytes(failed.store()), AccountStore.StoredAccount.class);
            checked.clear();
            var afterFailures = f.provider.continueRelationships("self", persisted, "self-id");
            assertThat(checked).hasSize(100).contains("peer100", "peer101", "peer102", "peer103", "peer104");
            assertThat(afterFailures.store().relationshipEvidence()).hasSize(5);
            assertThat(TwitterApiIoProvider.pendingRelationshipChecks(afterFailures.store())).isEqualTo(100);
        }
    }

    @Test void missingMetricFieldsStayUnknownThroughStorageAndLegacyJson() throws Exception {
        Instant at = Instant.now().minusSeconds(60);
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            return ok("{\"tweets\":[{\"id\":\"partial\",\"createdAt\":\"" + at + "\",\"viewCount\":0,\"likeCount\":0}],\"has_next_page\":false}");
        })) {
            var first = f.provider.syncStore("self", null, 2, true, null);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var stored = mapper.readValue(mapper.writeValueAsBytes(first.store()), AccountStore.StoredAccount.class);
            var raw = AccountStore.toSnapshot(stored, "", false, 2).timeline().get(0);
            assertThat(raw.viewCount()).isZero();
            assertThat(raw.unavailableMetrics()).containsExactlyInAnyOrder("replies", "reposts", "bookmarks");
            var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(raw);
            legacy.remove("unavailableMetrics");
            assertThat(mapper.treeToValue(legacy, RawTweet.class).unavailableMetrics()).isEqualTo(RawTweet.ALL_METRICS);
            var legacyStored = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(stored.tweets().get(0));
            legacyStored.remove("um");
            assertThat(mapper.treeToValue(legacyStored, AccountStore.StoredTweet.class).unavailableMetrics()).isEqualTo(RawTweet.ALL_METRICS);
        }
    }
}
