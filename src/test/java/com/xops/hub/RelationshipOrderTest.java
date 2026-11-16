package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.FollowAuditItem;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipOrderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.now();
    @TempDir Path directory;

    private XUserSummary user(String id, int followers) {
        return new XUserSummary(id, id, id, "", "", false, followers, 0, 0, "2020-01");
    }

    private AccountStore.StoredAccount stored() {
        var profile = new XUserSummary("self", "self", "Self", "", "", false, 4, 3, 1, "2020-01");
        var following = List.of(user("new_following", 50_000), user("mutual", 20), user("old_following", 10));
        var followers = List.of(user("new_fan", 100), user("mutual", 20), user("old_fan", 200), user("supplemented", 300));
        var graph = new AccountStore.GraphUpdate(following, followers, Set.of(), false, false, false, false,
                List.of(), Map.of(),
                Map.of("new_following", 0, "mutual", 1, "old_following", 2),
                Map.of("new_fan", 0, "mutual", 1, "old_fan", 2));
        var post = new RawTweet("post", "A cached post", now.minusSeconds(3600), TweetKind.POST,
                null, null, List.of(), false, false, 10L, 1, 0, 0, 0, false, "post", "");
        List<long[]> coverage = List.of(new long[]{now.minusSeconds(721L * 3600).getEpochSecond(), now.getEpochSecond()});
        return AccountStore.merge(null, profile, now, List.of(post), List.of(), coverage, coverage, graph);
    }

    private CommunityDashboardReport report(AccountStore.StoredAccount stored) {
        return new CommunityAnalyticsEngine().analyze(AccountStore.toSnapshot(stored, "", false, 30), 30, ZoneOffset.UTC, true);
    }

    private List<FollowAuditItem> items(CommunityDashboardReport report) {
        var audit = report.followAudit();
        List<FollowAuditItem> items = new ArrayList<>(audit.notFollowingBackList());
        items.addAll(audit.fansNotFollowedBackList());
        items.addAll(audit.mutualList());
        return items;
    }

    private Map<String, FollowAuditItem> itemsById(CommunityDashboardReport report) {
        return items(report).stream().collect(Collectors.toMap(item -> item.user().id(), item -> item));
    }

    @Test
    void persistentSourceOrderSurvivesCategorySortingWhileSupplementedUsersRemainUnknown() throws Exception {
        var original = stored();
        Path file = directory.resolve("store.json.gz");
        AccountStore.write(file, original, mapper);
        var restored = AccountStore.read(file, mapper);
        assertThat(restored).isNotNull();
        assertThat(restored.followingOrderById()).isEqualTo(original.followingOrderById());
        assertThat(restored.followerOrderById()).isEqualTo(original.followerOrderById());

        var result = report(restored);
        assertThat(result.followAudit().notFollowingBackList()).extracting(item -> item.user().id())
                .containsExactly("old_following", "new_following");
        assertThat(result.followAudit().fansNotFollowedBackList()).extracting(item -> item.user().id())
                .containsExactly("supplemented", "old_fan", "new_fan");
        var byId = itemsById(result);
        assertThat(byId.get("new_following").relationshipOrder()).isEqualTo(0);
        assertThat(byId.get("old_following").relationshipOrder()).isEqualTo(2);
        assertThat(byId.get("mutual").relationshipOrder()).isEqualTo(1);
        assertThat(byId.get("new_fan").relationshipOrder()).isEqualTo(0);
        assertThat(byId.get("old_fan").relationshipOrder()).isEqualTo(2);
        assertThat(byId.get("supplemented").relationshipOrder()).isNull();
    }

    @Test
    void legacyJsonAndConstructorsDoNotInventOrderFromArrayPositions() throws Exception {
        ObjectNode oldJson = mapper.valueToTree(stored());
        oldJson.remove(List.of("fo", "fro"));
        var old = mapper.treeToValue(oldJson, AccountStore.StoredAccount.class);
        assertThat(old.followingOrderById()).isEmpty();
        assertThat(old.followerOrderById()).isEmpty();
        assertThat(items(report(old))).allSatisfy(item -> assertThat(item.relationshipOrder()).isNull());

        var legacyGraph = new AccountStore.GraphUpdate(List.of(user("legacy", 10)), List.of(), Set.of(), false);
        assertThat(legacyGraph.followingOrderById()).isEmpty();
        assertThat(legacyGraph.followerOrderById()).isEmpty();
        var item = new FollowAuditItem(user("legacy", 10), "NOT_FOLLOWING_BACK", true, false,
                0, 0, 0, "ZERO_INTERACTION", "", "", "https://x.com/legacy");
        assertThat(item.relationshipOrder()).isNull();
        assertThat(item.intimacyScore()).isZero();
    }

    @Test
    void relationshipVerificationChangesCategoryWithoutInventingTheOtherDirectionsOrder() {
        var base = stored();
        var verified = AccountStore.withRelationshipEvidence(base, List.of(
                new AccountStore.RelationshipEvidence("old_fan", "old_fan", true, true, now.getEpochSecond()),
                new AccountStore.RelationshipEvidence("new_following", "new_following", false, true, now.getEpochSecond())
        ), Map.of());
        var byId = itemsById(report(verified));
        assertThat(byId.get("old_fan").relationState()).isEqualTo("MUTUAL");
        assertThat(byId.get("old_fan").relationshipOrder()).isNull();
        assertThat(byId.get("new_following").relationState()).isEqualTo("FAN_ONLY");
        assertThat(byId.get("new_following").relationshipOrder()).isNull();
        assertThat(byId.get("old_following").relationshipOrder()).isEqualTo(2);
    }

    @Test
    void normalStoreTransformationsKeepOrderMetadataUntilTheCorrespondingGraphRefresh() {
        var base = stored();
        List<UnaryOperator<AccountStore.StoredAccount>> transformations = List.of(
                value -> AccountStore.withMetricAttempts(value, List.of("post"), now),
                value -> AccountStore.withDailyRefreshState(value, 10, 20),
                value -> AccountStore.withCoverage(value, value.tweetCoverage(), value.inboundCoverage()),
                value -> AccountStore.applyBlueVerifiedCache(value, Map.of(), null, now.getEpochSecond()),
                AccountStore::withoutRelationshipEvidence,
                value -> AccountStore.withRelationshipEvidence(value, List.of(), Map.of()),
                value -> AccountStore.withoutTweets(value, List.of("post")),
                value -> AccountStore.pruneToRollingWindow(value, now.plusSeconds(32L * 86400)),
                value -> AccountStore.merge(value, value.profile(), now.plusSeconds(1),
                        List.of(), List.of(), List.of(), List.of(), null)
        );
        for (var transform : transformations) {
            var updated = transform.apply(base);
            assertThat(updated.followingOrderById()).isEqualTo(base.followingOrderById());
            assertThat(updated.followerOrderById()).isEqualTo(base.followerOrderById());
        }
        var reordered = new AccountStore.GraphUpdate(AccountStore.followingUsers(base), AccountStore.followerUsers(base),
                Set.of(), false, false, false, false, List.of(), Map.of(),
                Map.of("old_following", 0, "mutual", 1, "new_following", 2), base.followerOrderById());
        var refreshed = AccountStore.merge(base, base.profile(), now.plusSeconds(1),
                List.of(), List.of(), List.of(), List.of(), reordered);
        assertThat(itemsById(report(refreshed)).get("old_following").relationshipOrder()).isEqualTo(0);
    }

    @Test
    void demoProjectsExistingIntimacyScoresAndSimulatedOrdersWithoutAnotherFormula() {
        var snapshot = new DemoXDataProvider().generateSnapshot("sort_preview", "");
        var result = new CommunityAnalyticsEngine().analyze(snapshot, 30, ZoneOffset.UTC, false);
        var scores = result.interactions().peerStats().stream()
                .collect(Collectors.toMap(stat -> stat.peer().id(), stat -> stat.intimacyScore()));
        assertThat(scores.values()).anySatisfy(score -> assertThat(score).isPositive());
        var auditItems = items(result);
        assertThat(auditItems).isNotEmpty();
        for (var item : auditItems) {
            assertThat(item.relationshipOrder()).isNotNull();
            assertThat(item.intimacyScore()).isEqualTo(scores.getOrDefault(item.user().id(), 0.0));
        }
    }
}
