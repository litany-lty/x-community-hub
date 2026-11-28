package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialWorkspacePersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final XUserSummary self = user("100", "alice");
    @TempDir Path dir;

    @Test void failedSideRetainsItsDiskListWhileTheSuccessfulSideRefreshes() throws Exception {
        var oldGraph = new AccountStore.GraphUpdate(List.of(user("200", "old_following")), List.of(user("300", "old_fan")), Set.of(), false);
        var before = AccountStore.merge(null, self, Instant.now().minusSeconds(90000), List.of(), List.of(), List.of(), List.of(), oldGraph);
        var newFollowing = mapper.readTree("{\"data\":[{\"id\":\"201\",\"username\":\"new_following\"}]}");
        var newFollower = mapper.readTree("{\"data\":[{\"id\":\"301\",\"username\":\"new_fan\"}]}");
        var missedFollowers = refresh("followers-failed", before, newFollowing, null, 1, 1);
        assertThat(missedFollowers.followers()).containsExactly("old_fan");
        assertThat(missedFollowers.followings()).containsExactly("new_following");
        assertThat(missedFollowers.followersTruncated()).isTrue();
        assertThat(missedFollowers.graphRefreshFailed()).isTrue();
        var missedFollowings = refresh("followings-failed", before, null, newFollower, 1, 1);
        assertThat(missedFollowings.followings()).containsExactly("old_following");
        assertThat(missedFollowings.followers()).containsExactly("new_fan");
        var errorPayload = mapper.readTree("{\"errors\":[{\"detail\":\"unavailable\"}]}");
        assertThat(refresh("error-payload", before, newFollowing, errorPayload, 1, 1).followers()).containsExactly("old_fan");
        var malformed = mapper.readTree("{\"data\":{\"unexpected\":{\"id\":\"999\",\"username\":\"wrong_shape\"}}}");
        assertThat(refresh("malformed", before, newFollowing, malformed, 1, 1).followers()).containsExactly("old_fan");
        var emptyPageWithMore = mapper.readTree("{\"data\":[],\"meta\":{\"result_count\":0,\"next_token\":\"next\"}}");
        assertThat(refresh("empty-page-with-more", before, newFollowing, emptyPageWithMore, 1, 1).followers())
                .containsExactly("old_fan");
    }

    @Test void explicitEmptyAndBoundedSuccessHaveTheirOwnMembershipAndCompleteness() throws Exception {
        var old = new AccountStore.GraphUpdate(List.of(), List.of(user("300", "old_fan")), Set.of(), false);
        var before = AccountStore.merge(null, self, Instant.now().minusSeconds(90000), List.of(), List.of(), List.of(), List.of(), old);
        JsonNode confirmedEmpty = mapper.readTree("{\"meta\":{\"result_count\":0}}");
        var cleared = refresh("empty", before, confirmedEmpty, confirmedEmpty, 0, 0);
        assertThat(cleared.followers()).isEmpty();
        assertThat(cleared.followersTruncated()).isFalse();
        var firstPage = mapper.readTree("{\"data\":[{\"id\":\"301\",\"username\":\"new_fan\"}],\"meta\":{\"next_token\":\"next\"}}");
        var partial = refresh("partial", before, confirmedEmpty, firstPage, 0, 2);
        assertThat(partial.followers()).containsExactly("new_fan");
        assertThat(partial.followersTruncated()).isTrue();
        assertThat(partial.graphRefreshFailed()).isFalse();
    }

    @Test void failedGraphSideCannotTransferCachedMembershipToANewHandleOwner() throws Exception {
        var cached = new AccountStore.GraphUpdate(List.of(), List.of(user("300", "reused")), Set.of(), false);
        var before = AccountStore.merge(null, self, Instant.now().minusSeconds(90000), List.of(), List.of(), List.of(), List.of(), cached);
        var newFollowings = mapper.readTree("{\"data\":[{\"id\":\"999\",\"username\":\"reused\"},{\"id\":\"202\",\"username\":\"other\"}]}");
        var updated = refresh("changed-handle-owner", before, newFollowings, null, 2, 1);
        assertThat(updated.users().get("reused").id()).isEqualTo("300");
        assertThat(updated.followers()).containsExactly("reused");
        assertThat(updated.followings()).containsExactly("other");
        assertThat(updated.followingsTruncated()).isTrue();
    }

    @Test void completeFollowerSideCanSupersedeAnOldMutualEvenWhenFollowingsFail() throws Exception {
        var mutual = user("400", "prior_mutual");
        var oldMutual = new AccountStore.GraphUpdate(List.of(mutual), List.of(mutual), Set.of("prior_mutual"), false);
        var prior = AccountStore.merge(null, self, Instant.now().minusSeconds(90000), List.of(), List.of(), List.of(), List.of(), oldMutual);
        var confirmedEmpty = mapper.readTree("{\"meta\":{\"result_count\":0}}");
        var confirmedLoss = refresh("lost-follower", prior, null, confirmedEmpty, 1, 0);
        assertThat(confirmedLoss.explicitFollowedBy()).isEmpty();
        assertThat(TwitterApiIoProvider.snapshotOf(confirmedLoss, "", false).relationships()).singleElement()
                .satisfies(r -> {
                    assertThat(r.iFollowThem()).isTrue();
                    assertThat(r.theyFollowMe()).isFalse();
                });
    }

    @Test void officialRefreshRetainsProvenHistoryAndMigratesUnmarkedCoverageOnlyOnce() throws Exception {
        long at = Instant.now().minusSeconds(90000).getEpochSecond();
        var historic = List.of(new long[]{at - 86400, at});
        var proven = AccountStore.merge(null, self, Instant.ofEpochSecond(at), List.of(), List.of(), historic, historic, null);
        JsonNode empty = mapper.readTree("{\"meta\":{\"result_count\":0}}");
        var updated = refresh("proven", proven, empty, empty, 0, 0);
        assertThat(AccountStore.missingRanges(updated.tweetCoverage(), at - 86400, at)).isEmpty();
        assertThat(AccountStore.missingRanges(updated.inboundCoverage(), at - 86400, at)).isEmpty();
        var legacyJson = mapper.valueToTree(proven);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacyJson).remove("cp");
        var legacy = mapper.treeToValue(legacyJson, AccountStore.StoredAccount.class);
        var corrected = refresh("legacy", legacy, empty, empty, 0, 0);
        assertThat(AccountStore.missingRanges(corrected.tweetCoverage(), at - 86400, at)).isNotEmpty();
        assertThat(mapper.valueToTree(corrected).path("cp").asInt()).isEqualTo(1);

        var backfilled = List.of(new long[]{at - 2 * 86400, at - 86400});
        var inFlight = AccountStore.merge(legacy, self, Instant.ofEpochSecond(at), List.of(), List.of(), backfilled, backfilled, null);
        var rebased = AccountStore.mergeStoredSlice(legacy, corrected, inFlight);
        for (var coverage : List.of(rebased.tweetCoverage(), rebased.inboundCoverage())) {
            assertThat(AccountStore.missingRanges(coverage, at - 2 * 86400, at - 86400)).isEmpty();
            assertThat(AccountStore.missingRanges(coverage, at - 86400, at)).isNotEmpty();
        }
        assertThat(rebased.coverageProofVersion()).isEqualTo(AccountStore.VERIFIED_COVERAGE_VERSION);

        var unmarkedEmpty = AccountStore.withCoverage(legacy, List.of(), List.of());
        var firstProof = AccountStore.merge(unmarkedEmpty, self, Instant.ofEpochSecond(at), List.of(), List.of(), backfilled, backfilled, null);
        var firstBackgroundProof = AccountStore.mergeStoredSlice(unmarkedEmpty, unmarkedEmpty, firstProof);
        assertThat(firstBackgroundProof.coverageProofVersion()).isEqualTo(AccountStore.VERIFIED_COVERAGE_VERSION);
        assertThat(AccountStore.missingRanges(firstBackgroundProof.inboundCoverage(), at - 2 * 86400, at - 86400)).isEmpty();
    }

    @Test void incompletePreviousDayDoesNotGrantASecondSyncDate() throws Exception {
        Instant yesterday = Instant.now().minusSeconds(86400);
        var partial = AccountStore.merge(null, self, yesterday, List.of(), List.of(), List.of(), List.of(), null);
        partial = AccountStore.withDailyRefreshState(partial, yesterday.getEpochSecond(), 0);
        var empty = mapper.readTree("{\"meta\":{\"result_count\":0}}");
        refresh("unfinished", partial, empty, empty, 0, 0);
        var usage = mapper.readTree(dir.resolve("unfinished/users/by-id/100/usage_v1.json").toFile());
        assertThat(usage.path("syncedDates")).hasSize(1);
        var wholeDay = List.of(new long[]{yesterday.minusSeconds(86400).getEpochSecond(), yesterday.getEpochSecond()});
        var completed = AccountStore.merge(null, self, yesterday, List.of(), List.of(), wholeDay, wholeDay, null);
        completed = AccountStore.withDailyRefreshState(completed, yesterday.getEpochSecond(), yesterday.getEpochSecond());
        refresh("completed", completed, empty, empty, 0, 0);
        var successfulUsage = mapper.readTree(dir.resolve("completed/users/by-id/100/usage_v1.json").toFile());
        assertThat(successfulUsage.path("syncedDates")).hasSize(2);
    }

    private AccountStore.StoredAccount refresh(String name, AccountStore.StoredAccount before, JsonNode following, JsonNode followers,
                                                int followingCount, int followerCount) throws Exception {
        Path cache = dir.resolve(name);
        Path file = cache.resolve("users/by-id/100/store_v1.json.gz");
        AccountStore.write(file, before, mapper);
        var properties = new XOpsProperties();
        properties.setDefaultProvider("X_OFFICIAL_V2");
        properties.setOfficialBearerToken("fixture");
        properties.setDiskCacheDir(cache.toString());
        JsonNode profile = mapper.createObjectNode().set("data", mapper.createObjectNode().put("id", self.id()).put("username", self.username())
                .set("public_metrics", mapper.createObjectNode().put("following_count", followingCount).put("followers_count", followerCount)));
        JsonNode posts = mapper.createObjectNode().set("data", mapper.createArrayNode());
        var pool = new SharedApiExecutor(properties) {
            final Iterator<JsonNode> pages = Arrays.asList(posts, posts, following, followers).iterator();
            @Override @SuppressWarnings("unchecked") public <T> T execute(Callable<T> ignored) { return (T) profile; }
            @Override @SuppressWarnings("unchecked") public <T> CompletableFuture<T> submitAsync(Callable<T> ignored) {
                JsonNode page = pages.next();
                return page == null ? CompletableFuture.failedFuture(new IllegalStateException("simulated HTTP 503"))
                        : CompletableFuture.completedFuture((T) page);
            }
        };
        var hub = new CommunityHubService(properties, new DemoXDataProvider(), new TwitterApiIoProvider(properties, mapper, pool),
                new OfficialXApiV2Provider(properties, mapper, pool), mapper);
        try {
            hub.analyzeWorkspaceBundle(self.username(), ReportPeriod.HOURS_24, "X_OFFICIAL_V2", false, "127.0.0.1", self.id(), false);
            return AccountStore.read(file, mapper);
        } finally {
            hub.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    private static XUserSummary user(String id, String handle) {
        return new XUserSummary(id, handle, handle, "", "", false, 1, 1, 10, "");
    }
}
