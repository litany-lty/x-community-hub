package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.*;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingIdentityRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.now();
    private final XUserSummary owner = user(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom");
    private final XUserSummary oldPeer = user("201", "freya");
    private final XUserSummary newPeer = user("999", "freya");
    private final List<long[]> coverage = List.of(new long[] {now.minusSeconds(31 * 86400L).getEpochSecond(), now.getEpochSecond()});

    @Test void oldDirectRepliesKeepTheirRootAndRankAcrossAnOwnerRename(@TempDir Path dir) throws Exception {
        var visit = new InboundInteraction("reply", oldPeer, now.minusSeconds(90), InboundKind.REPLY_TO_ME,
                "a visit", "uncollected-root", "uncollected-root", owner.username());
        var unknown = new InboundInteraction("unknown", newPeer, now.minusSeconds(60), InboundKind.REPLY_TO_ME,
                "unproven", "different-root", "different-root", "");
        var before = AccountStore.merge(null, owner, now.minusSeconds(30), List.of(), List.of(visit, unknown),
                coverage, coverage, null);
        // Before stable reply recipients existed, the old profile and ru were the stored identity evidence.
        var file = dir.resolve("renamed.gz");
        AccountStore.write(file, before, mapper, true);
        var after = AccountStore.merge(AccountStore.read(file, mapper), user(owner.id(), "renamed_author"), now,
                List.of(), List.of(), List.of(), List.of(), null);
        AccountStore.write(file, after, mapper, true);
        var snapshot = TwitterApiIoProvider.snapshotOf(AccountStore.read(file, mapper), "", false);
        var report = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.MONTH,
                true, ZoneOffset.UTC, true, 0, now);
        assertThat(report.interactions().peerStats()).singleElement().satisfies(stat -> {
            assertThat(stat.peer().id()).isEqualTo(oldPeer.id());
            assertThat(stat.peerRepliesToMe()).isOne();
        });
    }

    @Test void authorCardUsesTheLoggedInCallersBudgetAndOnlyTheBoundPersonsProfile(@TempDir Path dir) throws Exception {
        var properties = new XOpsProperties();
        properties.setDiskCacheDir(dir.toString());
        var impostor = user("999", "elidlicom");
        var viewer = user("300", "reader");
        var provider = org.mockito.Mockito.mock(TwitterApiIoProvider.class);
        var scopes = new java.util.ArrayList<Boolean>();
        org.mockito.Mockito.when(provider.fetchPublicUserSummaryQuietly(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    scopes.add(PaidCapabilityGate.isCurrentCallerForbidden());
                    return impostor;
                });
        var hub = new CommunityHubService(properties, new DemoXDataProvider(), provider,
                org.mockito.Mockito.mock(OfficialXApiV2Provider.class), mapper);
        try {
            var unrelated = hub.getAuthorCardPreview("elidlicom", impostor.id());
            assertThat(scopes).containsExactly(true);
            assertThat(unrelated.authorProfile().id()).isEqualTo(owner.id());
            assertThat(unrelated.authorProfileUrl()).endsWith("/" + owner.id());
            assertThat(unrelated.authorFollowIntentUrl()).endsWith("user_id=" + owner.id());
            var file = dir.resolve("users/by-id/300/store_v" + AccountStore.VERSION + ".json.gz");
            var wrong = AccountStore.merge(null, viewer, now, List.of(), List.of(), coverage, coverage,
                    new AccountStore.GraphUpdate(List.of(impostor), List.of(), Set.of(), false));
            AccountStore.write(file, wrong, mapper);
            assertThat(hub.recordUsageCheckin(viewer.username(), viewer.id()).followingAuthor()).isFalse();
            var renamed = user(owner.id(), "new_author_name");
            var right = AccountStore.merge(null, viewer, now, List.of(), List.of(), coverage, coverage,
                    new AccountStore.GraphUpdate(List.of(renamed), List.of(), Set.of(), false));
            AccountStore.write(file, right, mapper);
            var card = hub.recordUsageCheckin(viewer.username(), viewer.id());
            assertThat(card.followingAuthor()).isTrue();
            assertThat(card.authorProfile().username()).isEqualTo(renamed.username());
            var third = hub.recordUsageCheckin(viewer.username(), viewer.id());
            var preview = hub.getAuthorCardPreview(viewer.username(), viewer.id());
            for (var prompt : List.of(third, preview)) {
                assertThat(prompt.modalTitle()).contains("@new_author_name");
                assertThat(prompt.modalMessage()).contains("@new_author_name").doesNotContain("@elidlicom");
                assertThat(prompt.authorProfileUrl()).endsWith("/" + owner.id());
            }
        } finally { hub.stopBackgroundRefreshes(); }
    }

    @Test void onlyAnObservedReceiverOrAnIndependentlyOwnedRootCanEstablishAVisit() throws Exception {
        var props = new XOpsProperties();
        var pool = new SharedApiExecutor(props);
        try {
            var provider = new TwitterApiIoProvider(props, mapper, pool);
            var parse = TwitterApiIoProvider.class.getDeclaredMethod("parseInbound", JsonNode.class, String.class, String.class);
            parse.setAccessible(true);
            var node = mapper.createObjectNode().put("id", "mention").put("isReply", true)
                    .put("text", "@elidlicom look").put("createdAt", now.minusSeconds(30).toString())
                    .put("conversationId", "outside").put("inReplyToId", "outside");
            node.putObject("author").put("id", oldPeer.id()).put("userName", oldPeer.username());
            var unknown = (InboundInteraction) parse.invoke(provider, node, owner.username(), owner.id());
            node.put("id", "confirmed").put("conversationId", "mine").put("inReplyToId", "mine")
                    .put("inReplyToUserId", owner.id());
            var direct = (InboundInteraction) parse.invoke(provider, node, owner.username(), owner.id());
            assertThat(unknown.kind()).isEqualTo(InboundKind.MENTION_ME);
            assertThat(direct.inReplyToUsername()).isEqualTo(owner.username());
            var stored = AccountStore.merge(null, owner, now, List.of(), List.of(unknown, direct), coverage, coverage, null);
            var legacy = mapper.valueToTree(stored);
            for (var item : legacy.path("i")) {
                if (!item.path("i").asText().equals("mention")) continue;
                ((ObjectNode) item).put("k", "R").remove("ru");
            }
            var recovered = AccountStore.toSnapshot(mapper.treeToValue(legacy, AccountStore.StoredAccount.class), "", false, 30);
            var visits = new CommunityAnalyticsEngine().analyze(recovered, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                    true, ZoneOffset.UTC, true, 0, now).interactions();
            assertThat(visits.totalInboundActions()).isEqualTo(1);
            assertThat(visits.peerStats()).singleElement().satisfies(peer -> {
                assertThat(peer.unrepliedInMyPostCount()).isEqualTo(1);
                assertThat(peer.latestUnrepliedTweetUrl()).endsWith("/confirmed");
            });
        } finally { pool.shutdown(); }
    }

    @Test void sameHandleKeepsBothHistoricalIdentitiesAndLegacyRowsStayUnbound() throws Exception {
        var first = AccountStore.merge(null, owner, now, List.of(visit("out-old", oldPeer)), List.of(visitMe("old", oldPeer)),
                coverage, coverage, null);
        var updated = AccountStore.merge(first, owner, now, List.of(visit("out-new", newPeer)), List.of(visitMe("new", newPeer)),
                coverage, coverage, new AccountStore.GraphUpdate(List.of(newPeer), List.of(user("888", "fan")), Set.of(), false));
        var json = mapper.valueToTree(updated);
        var legacy = ((ObjectNode) json.path("i").get(0)).deepCopy().put("i", "legacy");
        legacy.remove("pi");
        ((ObjectNode) json).withArray("i").add(legacy);
        var restored = AccountStore.toSnapshot(mapper.treeToValue(json, AccountStore.StoredAccount.class), "", false, 30);
        var report = new CommunityAnalyticsEngine().analyze(restored, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                true, ZoneOffset.UTC, true, 0, now);
        assertThat(report.interactions().peerStats()).hasSize(3).allSatisfy(peer -> {
            assertThat(peer.peerRepliesToMe()).isEqualTo(1);
            assertThat(peer.myRepliesToPeer()).isEqualTo(peer.peer().id().isEmpty() ? 0 : 1);
            assertThat(peer.iFollowThem()).isEqualTo("999".equals(peer.peer().id()));
        });
        assertThat(report.followAudit().notFollowingBackList()).singleElement()
                .satisfies(item -> assertThat(item.theirRepliesToMe()).isEqualTo(1));
    }

    @Test void rankAndFollowerPrivilegesFollowTheIdAcrossRenames(@TempDir Path dir) throws Exception {
        var graph = new AccountStore.GraphUpdate(List.of(), List.of(oldPeer), Set.of(), false);
        var stored = AccountStore.merge(null, owner, now, List.of(visit("ranked", oldPeer)), List.of(), coverage, coverage, graph);
        AccountStore.write(dir.resolve("users/by-id/" + com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID + "/store_v1.json.gz"), stored, mapper);
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        var pool = new SharedApiExecutor(props);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), new TwitterApiIoProvider(props, mapper, pool),
                new OfficialXApiV2Provider(props, mapper, pool), mapper);
        try {
            assertThat(hub.resolveAuthorInteractionRank("freya", "999")).isNull();
            assertThat(hub.evaluateScheduledPublishAccess("999", "freya").followsAuthor()).isFalse();
            assertThat(hub.resolveAuthorInteractionRank("renamed", "201")).isEqualTo(1);
            assertThat(hub.evaluateScheduledPublishAccess("201", "renamed").followsAuthor()).isTrue();
        } finally { hub.stopBackgroundRefreshes(); pool.shutdown(); }
    }

    @Test void backgroundSlicePreservesConcurrentDeletionAndBothNewPosts() {
        var before = AccountStore.merge(null, owner, now, List.of(visit("deleted", oldPeer)), List.of(), coverage, coverage, null);
        var latest = AccountStore.merge(AccountStore.withoutTweets(before, List.of("deleted")), owner, now,
                List.of(visit("foreground", oldPeer)), List.of(), coverage, coverage, null);
        var fetched = AccountStore.merge(before, owner, now, List.of(visit("backfilled", oldPeer)), List.of(), coverage, coverage, null);
        assertThat(AccountStore.mergeStoredSlice(before, latest, fetched).tweets()).extracting(AccountStore.StoredTweet::id)
                .containsExactlyInAnyOrder("foreground", "backfilled");
    }

    @Test void ranksFollowNewAuthorStoreContentsEvenWhenBackfillKeepsTheWindowAnchor(@TempDir Path dir) throws Exception {
        var file = dir.resolve("users/by-id/" + com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID + "/store_v1.json.gz");
        var before = AccountStore.merge(null, owner, now, List.of(visit("old", oldPeer)), List.of(), coverage, coverage, null);
        AccountStore.write(file, before, mapper);
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        var pool = new SharedApiExecutor(props);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), new TwitterApiIoProvider(props, mapper, pool),
                new OfficialXApiV2Provider(props, mapper, pool), mapper);
        try {
            assertThat(hub.resolveAuthorInteractionRank("freya", oldPeer.id())).isEqualTo(1);
            var filled = AccountStore.merge(before, owner, now, List.of(visit("new1", newPeer), visit("new2", newPeer)), List.of(), coverage, coverage, null);
            assertThat(filled.syncedAt()).isEqualTo(before.syncedAt());
            AccountStore.write(file, filled, mapper);
            assertThat(hub.resolveAuthorInteractionRank("freya", newPeer.id())).isEqualTo(1);
            assertThat(hub.resolveAuthorInteractionRank("freya", oldPeer.id())).isEqualTo(2);
        } finally { hub.stopBackgroundRefreshes(); pool.shutdown(); }
    }

    @Test void unknownRelationshipsNeverStandForNonMutualVisitorsInCalibration() {
        var unknown = user("400", "unknown");
        var positive = user("401", "positive");
        var negative = user("402", "negative");
        var visits = List.of(visitMe("1", positive), visitMe("2", positive), visitMe("3", negative), visitMe("4", unknown));
        for (boolean haveEvidence : List.of(true, false)) {
            var relations = haveEvidence ? List.of(new RelationshipEntry(positive, true, true, true),
                    new RelationshipEntry(negative, true, false, true)) : List.<RelationshipEntry>of();
            var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", now, owner, List.of(), visits,
                    relations, true, false, true);
            var cal = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                    true, ZoneOffset.UTC, true, 0, now).algorithmHealth().calibration();
            assertThat(cal.mutualShareKnown()).isEqualTo(haveEvidence);
            if (haveEvidence) assertThat(cal.mutualCommenterSharePct()).isEqualTo(66.7);
            JsonNode json = mapper.valueToTree(cal);
            assertThat(json.path("knownRelationCommentCount").asInt(-1)).isEqualTo(haveEvidence ? 3 : 0);
            assertThat(json.path("unknownRelationCommentCount").asInt(-1)).isEqualTo(haveEvidence ? 1 : 4);
        }
    }

    @Test void onlyKnownExternalDestinationsEnterReachComparisonsAndPenalties() {
        var texts = List.of("https://x.com", "https://twitter.com/name/status/1", "https://mobile.x.com/name/status/2",
                "https://t.co/unknown", "https://outside.example/post", "https://x.com.attacker.example/post");
        var posts = texts.stream().map(url -> new RawTweet(url, url, now.minusSeconds(10), TweetKind.POST, null, null, List.of(),
                false, false, 100, 2, 0, 1, 0)).toList();
        var snapshot = new RawAccountSnapshot(ProviderMode.TWITTERAPI_IO, true, "", now, owner, posts, List.of(), List.of());
        var report = new CommunityAnalyticsEngine().analyze(snapshot, CommunityAnalyticsEngine.ReportPeriod.HOURS_24,
                true, ZoneOffset.UTC, true, 0, now);
        assertThat(report.algorithmHealth().externalLinkPostCount()).isEqualTo(2);
        assertThat(report.algorithmHealth().nativeViewSampleCount()).isEqualTo(3);
        assertThat(report.algorithmHealth().calibration().domainStats()).extracting(d -> d.domain())
                .containsExactlyInAnyOrder("outside.example", "x.com.attacker.example");
        var samples = report.algorithmHealth().calibration().corpusSamples();
        assertThat(samples).filteredOn(s -> s.id().equals("https://t.co/unknown")).singleElement()
                .satisfies(s -> assertThat(s.hasExternalLink()).isNull());
        assertThat(samples).filteredOn(s -> s.id().equals("https://x.com")).singleElement()
                .satisfies(s -> assertThat(s.hasExternalLink()).isFalse());
    }

    private static XUserSummary user(String id, String handle) {
        return new XUserSummary(id, handle, handle, "", "", false, 1, 1, 10, "");
    }

    private InboundInteraction visitMe(String id, XUserSummary peer) {
        return new InboundInteraction(id, peer, now.minusSeconds(60), InboundKind.REPLY_TO_ME, "hello",
                "mine-" + id, "mine-" + id, owner.username());
    }

    private RawTweet visit(String id, XUserSummary peer) {
        return new RawTweet(id, "reply", now.minusSeconds(45), TweetKind.REPLY, peer.username(), peer, List.of(),
                false, false, 1, 1, 1, 1, 1, false, "root-" + id, "root-" + id);
    }
}
