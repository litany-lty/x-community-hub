package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.*;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import com.xops.hub.store.BlueVerifiedCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccumulationAndInteractionRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void blueVerifiedCacheAndStoreShouldKeepFirstObservationFor15DaysAndRefreshAfterExpiry(@TempDir Path dir) {
        BlueVerifiedCache cache = new BlueVerifiedCache(dir.toString(), objectMapper);
        long t0 = Instant.now().getEpochSecond() - 86_400L;

        assertThat(cache.observeAuthoritative("u1", "peer_blue", true, t0)).isTrue();
        // Within 15 days, conflicting false must not overwrite the first observation
        assertThat(cache.observeAuthoritative("u1", "peer_blue", false, t0 + 14L * 86_400L)).isTrue();
        cache.flushIfDirty();

        // Reload from disk and verify TTL persistence
        BlueVerifiedCache reloaded = new BlueVerifiedCache(dir.toString(), objectMapper);
        assertThat(reloaded.resolveValid("u1", "peer_blue", t0 + 14L * 86_400L)).isTrue();

        // Past 15 days (TTL expired), authoritative false updates the cache
        assertThat(reloaded.resolveValid("u1", "peer_blue", t0 + 15L * 86_400L + 1L)).isNull();
        assertThat(reloaded.observeAuthoritative("u1", "peer_blue", false, t0 + 16L * 86_400L)).isFalse();

        // Store-level first-observation preservation
        XUserSummary me = new XUserSummary("901", "me_user", "Me", "", "", false, 10, 10, 10, "");
        XUserSummary firstBlue = new XUserSummary("u2", "blue_friend", "Blue Friend", "", "", true, 50, 40, 20, "");
        InboundInteraction ev1 = new InboundInteraction("ib1", firstBlue, Instant.ofEpochSecond(t0), InboundKind.REPLY_TO_ME, "hi", "conv1", "p1", "me_user");
        AccountStore.StoredAccount st = AccountStore.merge(null, me, Instant.ofEpochSecond(t0),
                List.of(), List.of(ev1), List.of(), List.of(), null);
        assertThat(st.users().get("blue_friend").verified()).isTrue();
        assertThat(st.users().get("blue_friend").verifiedAt()).isEqualTo(t0);

        XUserSummary conflictDay14 = new XUserSummary("u2", "blue_friend", "Blue Friend", "", "", false, 51, 40, 20, "");
        InboundInteraction ev2 = new InboundInteraction("ib2", conflictDay14, Instant.ofEpochSecond(t0 + 14L * 86_400L), InboundKind.REPLY_TO_ME, "hi again", "conv2", "p2", "me_user");
        AccountStore.StoredAccount stDay14 = AccountStore.merge(st, me, Instant.ofEpochSecond(t0 + 14L * 86_400L),
                List.of(), List.of(ev2), List.of(), List.of(), null);
        assertThat(stDay14.users().get("blue_friend").verified()).isTrue();
    }

    @Test
    void twitterApiIoProviderShouldEnrichUncachedBlueBadgeViaBatchInfoByIds(@TempDir Path dir) throws Exception {
        String recentIso = Instant.now().minus(Duration.ofHours(2)).toString();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requestedBatchIds = new CopyOnWriteArrayList<>();
        try {
            upstream.createContext("/twitter/user/info", ex -> writeJson(ex, """
                    {"status":"success","data":{"id":"100","userName":"me_acc","name":"Me Acc","followers_count":50,"following_count":20,"statusesCount":10,"isBlueVerified":false}}
                    """));
            upstream.createContext("/twitter/user/followings", ex -> writeJson(ex, """
                    {"status":"success","followings":[{"id":"3002","userName":"peer_batch","name":"Peer Batch"}],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/user/followers", ex -> writeJson(ex, """
                    {"status":"success","followers":[],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/user/last_tweets", ex -> writeJson(ex, String.format("""
                    {"status":"success","data":{"tweets":[{"id":"tw1","text":"@peer_batch nice post","conversationId":"c_out","inReplyToUsername":"peer_batch","inReplyToId":"c_out","isReply":true,"createdAt":"%s","viewCount":10,"likeCount":1,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0}]},"has_next_page":false,"next_cursor":""}
                    """, recentIso)));
            upstream.createContext("/twitter/user/mentions", ex -> writeJson(ex, String.format("""
                    {"status":"success","tweets":[{"id":"in1","text":"@me_acc thanks","conversationId":"c_in","inReplyToUsername":"me_acc","inReplyToId":"root_me","isReply":true,"createdAt":"%s","author":{"id":"3002","userName":"peer_batch","name":"Peer Batch"}}],"has_next_page":false}
                    """, recentIso)));
            upstream.createContext("/twitter/tweet/advanced_search", ex -> {
                String q = queryParam(ex.getRequestURI().getRawQuery(), "query");
                if (q.startsWith("from:me_acc")) {
                    writeJson(ex, String.format("""
                            {"tweets":[{"id":"tw1","text":"@peer_batch nice post","conversationId":"c_out","inReplyToUsername":"peer_batch","inReplyToId":"c_out","isReply":true,"createdAt":"%s","viewCount":10,"likeCount":1,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0,"author":{"id":"100","userName":"me_acc","name":"Me Acc"}}],"has_next_page":false,"next_cursor":""}
                            """, recentIso));
                } else {
                    writeJson(ex, String.format("""
                            {"tweets":[{"id":"in1","text":"@me_acc thanks","conversationId":"c_in","inReplyToUsername":"me_acc","inReplyToId":"root_me","isReply":true,"createdAt":"%s","author":{"id":"3002","userName":"peer_batch","name":"Peer Batch"}}],"has_next_page":false,"next_cursor":""}
                            """, recentIso));
                }
            });
            upstream.createContext("/twitter/user/batch_info_by_ids", ex -> {
                requestedBatchIds.add(queryParam(ex.getRequestURI().getRawQuery(), "userIds"));
                writeJson(ex, """
                        {"status":"success","users":[{"id":"3002","userName":"peer_batch","name":"Peer Batch","isBlueVerified":true}]}
                        """);
            });
            upstream.start();

            XOpsProperties props = baseTwitterProps(dir, upstream.getAddress().getPort());
            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, objectMapper);
            TwitterApiIoProvider.SyncOutcome outcome = provider.syncStoreByHours("me_acc", null, 8, true, true, null, "100");

            assertThat(outcome.degraded()).isFalse();
            assertThat(requestedBatchIds).singleElement().satisfies(ids -> assertThat(ids).contains("3002"));
            assertThat(outcome.store().users().get("peer_batch")).isNotNull();
            assertThat(outcome.store().users().get("peer_batch").verified()).isTrue();
            assertThat(provider.getBlueVerifiedCache().resolveValid("3002", "peer_batch", Instant.now().getEpochSecond())).isTrue();
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void analyticsEngineShouldCountOncePerPostSplitAtFiveDiffExcludePureQuoteAndSortBlueTotal() {
        Instant now = Instant.now();
        Instant twoHoursAgo = now.minus(Duration.ofHours(2));
        XUserSummary me = new XUserSummary("10", "me_acc", "Me", "", "", false, 100, 100, 50, "");

        XUserSummary blueLow = new XUserSummary("21", "blue_low", "Blue Low", "", "", true, 10, 10, 5, "");
        XUserSummary blueHigh = new XUserSummary("22", "blue_i_owe", "Blue IOwe", "", "", true, 10, 10, 5, "");
        XUserSummary nonBlueHigh = new XUserSummary("23", "nonblue_mutual", "NonBlue Mutual", "", "", false, 10, 10, 5, "");
        XUserSummary nonBlueTheyOwe = new XUserSummary("24", "nonblue_theyowe", "NonBlue TheyOwe", "", "", false, 10, 10, 5, "");
        XUserSummary quoteOnly = new XUserSummary("25", "quote_only", "Quote Only", "", "", true, 10, 10, 5, "");

        List<RawTweet> timeline = new ArrayList<>();
        // blue_low: 2 replies in SAME conversation c_bl_out -> must count as 1 post
        timeline.add(replyTweet("t_bl_1", "blue_low", blueLow, "c_bl_out", "c_bl_out", twoHoursAgo));
        timeline.add(replyTweet("t_bl_2", "blue_low", blueLow, "c_bl_out", "t_bl_1", twoHoursAgo.plusSeconds(10)));
        // blue_i_owe: 1 post visit
        timeline.add(replyTweet("t_bh_1", "blue_i_owe", blueHigh, "c_bh_out_1", "c_bh_out_1", twoHoursAgo));
        // nonblue_mutual: 5 distinct posts vs 1 inbound -> diff 4 <= 5 => MUTUAL
        for (int i = 1; i <= 5; i++) {
            timeline.add(replyTweet("t_nm_" + i, "nonblue_mutual", nonBlueHigh, "c_nm_out_" + i, "c_nm_out_" + i, twoHoursAgo.plusSeconds(i)));
        }
        // nonblue_theyowe: 7 distinct posts vs 1 inbound -> diff 6 > 5 => THEY_OWE
        for (int i = 1; i <= 7; i++) {
            timeline.add(replyTweet("t_nt_" + i, "nonblue_theyowe", nonBlueTheyOwe, "c_nt_out_" + i, "c_nt_out_" + i, twoHoursAgo.plusSeconds(i)));
        }
        // my own root posts so inbound replies stay consistent
        for (int i = 1; i <= 10; i++) {
            timeline.add(new RawTweet("my_root_" + i, "hello " + i, twoHoursAgo.minusSeconds(600),
                    TweetKind.POST, "", null, List.of(), false, false, 100, 1, 1, 0, 0, false, "my_root_" + i, ""));
        }

        List<InboundInteraction> inbound = new ArrayList<>();
        // blue_low: 3 inbound replies on SAME conversation my_root_1 -> must count as 1 post (1:1 => MUTUAL)
        inbound.add(new InboundInteraction("i_bl_1", blueLow, twoHoursAgo.plusSeconds(20), InboundKind.REPLY_TO_ME, "r1", "my_root_1", "my_root_1", "me_acc"));
        inbound.add(new InboundInteraction("i_bl_2", blueLow, twoHoursAgo.plusSeconds(30), InboundKind.REPLY_TO_ME, "r2", "my_root_1", "i_bl_1", "blue_low"));
        inbound.add(new InboundInteraction("i_bl_3", blueLow, twoHoursAgo.plusSeconds(40), InboundKind.REPLY_TO_ME, "r3", "my_root_1", "my_root_1", "me_acc"));
        // blue_i_owe: 8 distinct inbound posts vs 1 outbound -> diff 7 > 5 => I_OWE
        for (int i = 1; i <= 8; i++) {
            inbound.add(new InboundInteraction("i_bh_" + i, blueHigh, twoHoursAgo.plusSeconds(50 + i),
                    InboundKind.REPLY_TO_ME, "rbh", "my_root_" + i, "my_root_" + i, "me_acc"));
        }
        // nonblue_mutual: 1 inbound vs 5 outbound -> MUTUAL
        inbound.add(new InboundInteraction("i_nm_1", nonBlueHigh, twoHoursAgo.plusSeconds(70), InboundKind.REPLY_TO_ME, "rnm", "my_root_9", "my_root_9", "me_acc"));
        // nonblue_theyowe: 1 inbound vs 7 outbound -> THEY_OWE
        inbound.add(new InboundInteraction("i_nt_1", nonBlueTheyOwe, twoHoursAgo.plusSeconds(80), InboundKind.REPLY_TO_ME, "rnt", "my_root_10", "my_root_10", "me_acc"));
        // quote_only: pure quote without reply -> must be excluded from peerStats
        inbound.add(new InboundInteraction("i_qo_1", quoteOnly, twoHoursAgo.plusSeconds(90), InboundKind.QUOTE_ME, "quote", "qconv1", "", ""));

        RawAccountSnapshot snapshot = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", now, me, timeline, inbound, List.of());

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();
        CommunityDashboardReport report = engine.analyze(
                snapshot, ReportPeriod.HOURS_24, false, ZoneId.of("Asia/Shanghai"), false, 300L, now.plusSeconds(300));

        List<PeerInteractionStat> stats = report.interactions().peerStats();
        // Pure quote peer must be excluded
        assertThat(stats).extracting(p -> p.peer().username())
                .containsExactly("blue_i_owe", "blue_low", "nonblue_theyowe", "nonblue_mutual");

        PeerInteractionStat blueLowStat = stats.stream().filter(p -> p.peer().username().equals("blue_low")).findFirst().orElseThrow();
        assertThat(blueLowStat.myRepliesToPeer()).isEqualTo(1);
        assertThat(blueLowStat.peerRepliesToMe()).isEqualTo(1);
        assertThat(blueLowStat.reciprocityCode()).isEqualTo("MUTUAL");

        PeerInteractionStat nonBlueMutualStat = stats.stream().filter(p -> p.peer().username().equals("nonblue_mutual")).findFirst().orElseThrow();
        assertThat(nonBlueMutualStat.myRepliesToPeer()).isEqualTo(5);
        assertThat(nonBlueMutualStat.peerRepliesToMe()).isEqualTo(1);
        assertThat(nonBlueMutualStat.reciprocityCode()).isEqualTo("MUTUAL");

        PeerInteractionStat theyOweStat = stats.stream().filter(p -> p.peer().username().equals("nonblue_theyowe")).findFirst().orElseThrow();
        assertThat(theyOweStat.myRepliesToPeer()).isEqualTo(7);
        assertThat(theyOweStat.peerRepliesToMe()).isEqualTo(1);
        assertThat(theyOweStat.reciprocityCode()).isEqualTo("THEY_OWE");

        PeerInteractionStat iOweStat = stats.stream().filter(p -> p.peer().username().equals("blue_i_owe")).findFirst().orElseThrow();
        assertThat(iOweStat.myRepliesToPeer()).isEqualTo(1);
        assertThat(iOweStat.peerRepliesToMe()).isEqualTo(8);
        assertThat(iOweStat.reciprocityCode()).isEqualTo("I_OWE");

        // Blue V peers (blue_i_owe total 9, blue_low total 2) both precede non-blue peers (total 8, total 6)
        assertThat(report.interactions().filteredSamePostReplies()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void workspaceAccessLadderDailyQuotaAndAuthorStoryShouldBeEnforced(@TempDir Path dir) throws Exception {
        String recentIso = Instant.now().minus(Duration.ofHours(1)).toString();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            upstream.createContext("/twitter/user/info", ex -> {
                String uname = queryParam(ex.getRequestURI().getRawQuery(), "userName");
                String uid = "bob".equalsIgnoreCase(uname) ? "5002" : "5001";
                writeJson(ex, String.format("""
                        {"status":"success","data":{"id":"%s","userName":"%s","name":"%s","followers_count":100,"following_count":50,"statusesCount":12,"isBlueVerified":false}}
                        """, uid, uname, uname));
            });
            upstream.createContext("/twitter/user/followings", ex -> {
                String uname = queryParam(ex.getRequestURI().getRawQuery(), "userName");
                if ("bob".equalsIgnoreCase(uname)) {
                    writeJson(ex, """
                            {"status":"success","followings":[{"id":"9000","userName":"elidlicom","name":"Litany","isBlueVerified":true}],"has_next_page":false}
                            """);
                } else {
                    writeJson(ex, """
                            {"status":"success","followings":[{"id":"888","userName":"someone_else","name":"Someone"}],"has_next_page":false}
                            """);
                }
            });
            upstream.createContext("/twitter/user/followers", ex -> writeJson(ex, """
                    {"status":"success","followers":[],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/user/last_tweets", ex -> writeJson(ex, String.format("""
                    {"status":"success","data":{"tweets":[{"id":"tw_p1","text":"hi","conversationId":"tw_p1","isReply":false,"createdAt":"%s","viewCount":10,"likeCount":1,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0}]},"has_next_page":false,"next_cursor":""}
                    """, recentIso)));
            upstream.createContext("/twitter/user/mentions", ex -> writeJson(ex, """
                    {"status":"success","tweets":[],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/tweet/advanced_search", ex -> {
                String q = queryParam(ex.getRequestURI().getRawQuery(), "query");
                if (q.startsWith("from:")) {
                    writeJson(ex, String.format("""
                            {"tweets":[{"id":"tw_p1","text":"hi","conversationId":"tw_p1","isReply":false,"createdAt":"%s","viewCount":10,"likeCount":1,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0}],"has_next_page":false,"next_cursor":""}
                            """, recentIso));
                } else {
                    writeJson(ex, """
                            {"tweets":[],"has_next_page":false,"next_cursor":""}
                            """);
                }
            });
            upstream.createContext("/twitter/user/batch_info_by_ids", ex -> writeJson(ex, """
                    {"status":"success","users":[]}
                    """));
            upstream.start();

            XOpsProperties props = baseTwitterProps(dir, upstream.getAddress().getPort());
            TwitterApiIoProvider twitterProvider = new TwitterApiIoProvider(props, objectMapper);
            CommunityHubService service = new CommunityHubService(
                    props, new DemoXDataProvider(), twitterProvider, new OfficialXApiV2Provider(props, objectMapper), objectMapper);

            // Day 1 default: HOURS_24 is selected & unlocked by default, physical store is written immediately, 48h/7d/30d locked
            MultiPeriodDashboardBundle aliceBundle = service.analyzeWorkspaceBundle(
                    "alice", null, null, false, "127.0.0.1", "5001", false);
            assertThat(aliceBundle.hours24()).isNotNull();
            assertThat(aliceBundle.day()).isNull();
            assertThat(aliceBundle.week()).isNull();
            assertThat(aliceBundle.month()).isNull();
            assertThat(aliceBundle.hasMoreHistoryForTarget()).isFalse();
            assertThat(aliceBundle.accessPolicy().accumulatedDays()).isEqualTo(1);
            assertThat(aliceBundle.accessPolicy().followsAuthor()).isFalse();
            assertThat(aliceBundle.accessPolicy().unlockedPeriods()).containsExactly("HOURS_24");
            assertThat(aliceBundle.accessPolicy().dailyRefreshLimit()).isEqualTo(1);
            assertThat(aliceBundle.accessPolicy().dailyRefreshRemaining()).isEqualTo(1);
            assertThat(aliceBundle.accessPolicy().accumulatedSummaryMode()).isTrue();
            assertThat(aliceBundle.accessPolicy().authorStatement()).isEqualTo(CommunityHubService.AUTHOR_COST_STORY);
            assertThat(aliceBundle.accessPolicy().repoUrl()).isEqualTo(CommunityHubService.AUTHOR_REPO_URL);

            Path aliceStorePath = dir.resolve("users/by-id/5001/store_v" + AccountStore.VERSION + ".json.gz");
            assertThat(aliceStorePath).isRegularFile();

            assertThat(service.analyzeWorkspaceReport("alice", null, null, false, "127.0.0.1", "5001").periodCode())
                    .isEqualTo("HOURS_24");
            assertThatThrownBy(() -> service.analyzeWorkspaceReport("alice", ReportPeriod.HOURS_48, null, false, "127.0.0.1", "5001"))
                    .isInstanceOf(XDataProviderException.class)
                    .extracting(e -> ((XDataProviderException) e).getStatusCode()).isEqualTo(403);

            // Explicit checks within the same complete hour reuse the snapshot and leave the daily allowance intact.
            MultiPeriodDashboardBundle checked = service.analyzeWorkspaceBundle(
                    "alice", ReportPeriod.HOURS_24, null, true, "127.0.0.1", "5001", false);
            assertThat(checked.hours24().fromCache()).isTrue();
            assertThat(checked.hours24().fetchedAt()).isEqualTo(aliceBundle.hours24().fetchedAt());
            assertThat(checked.accessPolicy().dailyRefreshRemaining()).isEqualTo(1);
            assertThat(service.analyzeWorkspaceReport("alice", ReportPeriod.HOURS_24, null, true, "127.0.0.1", "5001").fromCache()).isTrue();

            // Bob on day 1 also gets HOURS_24 by default, while 48h/7d/30d require physical daily accumulation
            MultiPeriodDashboardBundle bobBundle = service.analyzeWorkspaceBundle(
                    "bob", ReportPeriod.HOURS_24, null, false, "127.0.0.1", "5002", false);
            assertThat(bobBundle.accessPolicy().followsAuthor()).isTrue();
            assertThat(bobBundle.accessPolicy().unlockedPeriods()).containsExactly("HOURS_24");
            assertThat(bobBundle.hours24()).isNotNull();
            assertThat(bobBundle.day()).isNull();

            // Simulate 2, 7, 30 accumulated dates for alice -> unlocks 48h, 7d, 30d; dates older than 30d rolling window are pruned
            Path aliceUsagePath = dir.resolve("users/by-id/5001/usage_v1.json");
            writeSyncedDates(aliceUsagePath, 2);
            MultiPeriodDashboardBundle aliceDay2 = service.analyzeWorkspaceBundle(
                    "alice", ReportPeriod.HOURS_48, null, false, "127.0.0.1", "5001", false);
            assertThat(aliceDay2.accessPolicy().unlockedPeriods()).containsExactly("HOURS_24", "HOURS_48");
            assertThat(aliceDay2.day()).isNotNull();
            assertThat(aliceDay2.week()).isNull();

            writeSyncedDates(aliceUsagePath, 7);
            MultiPeriodDashboardBundle aliceDay7 = service.analyzeWorkspaceBundle(
                    "alice", ReportPeriod.WEEK, null, false, "127.0.0.1", "5001", false);
            assertThat(aliceDay7.accessPolicy().unlockedPeriods()).containsExactly("HOURS_24", "HOURS_48", "WEEK");
            assertThat(aliceDay7.week()).isNotNull();
            assertThat(aliceDay7.month()).isNull();

            writeSyncedDates(aliceUsagePath, 35);
            MultiPeriodDashboardBundle aliceDay30 = service.analyzeWorkspaceBundle(
                    "alice", ReportPeriod.MONTH, null, false, "127.0.0.1", "5001", false);
            assertThat(aliceDay30.accessPolicy().accumulatedDays()).isEqualTo(30);
            assertThat(aliceDay30.accessPolicy().unlockedPeriods()).containsExactly("HOURS_24", "HOURS_48", "WEEK", "MONTH");
            assertThat(aliceDay30.month()).isNotNull();

            // Usage checkin 3rd modal + author preview include verbatim story & repo URL while preserving rolling dates
            service.recordUsageCheckin("alice", "5001");
            service.recordUsageCheckin("alice", "5001");
            UsageCheckinResponse thirdUse = service.recordUsageCheckin("alice", "5001");
            assertThat(thirdUse.shouldShowAuthorModal()).isTrue();
            assertThat(thirdUse.modalMessage())
                    .contains("真实付费调用 API")
                    .contains("结个善缘")
                    .contains("@elidlicom")
                    .contains(CommunityHubService.AUTHOR_COST_STORY)
                    .contains(CommunityHubService.AUTHOR_REPO_URL);
            CommunityHubService.UserUsageEntry afterCheckin = objectMapper.readValue(aliceUsagePath.toFile(), CommunityHubService.UserUsageEntry.class);
            assertThat(afterCheckin.syncedDates()).hasSize(35);

            UsageCheckinResponse preview = service.getAuthorCardPreview("alice", "5001");
            assertThat(preview.modalMessage())
                    .contains(CommunityHubService.AUTHOR_COST_STORY)
                    .contains(CommunityHubService.AUTHOR_REPO_URL);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void top5FullAccessAndTop50ScheduledAutoSyncShouldWorkOnlyForRegisteredPersistedPeers(@TempDir Path dir) throws Exception {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(Duration.ofHours(1));

        // Seed @elidlicom store with 55 peers so rankings 1..5, 6..50, and 51..55 are deterministic
        XUserSummary author = new XUserSummary("9000", "elidlicom", "Litany", "", "", true, 500, 100, 50, "");
        List<RawTweet> authorTweets = new ArrayList<>();
        List<InboundInteraction> authorInbound = new ArrayList<>();
        for (int rank = 1; rank <= 55; rank++) {
            String peerUid = String.valueOf(6000 + rank);
            String peerName = "peer_rank_" + rank;
            XUserSummary peer = new XUserSummary(peerUid, peerName, "Peer " + rank, "", "", false, 10, 10, 10, "");
            int replyPairs = 60 - rank; // strictly descending total replies
            for (int r = 1; r <= replyPairs; r++) {
                String cId = "c_ar_" + rank + "_" + r;
                authorTweets.add(replyTweet("a_tw_" + rank + "_" + r, peerName, peer, cId, cId, oneHourAgo));
            }
        }
        List<long[]> monthCoverage = List.of(new long[]{now.minus(Duration.ofDays(31)).getEpochSecond(), now.getEpochSecond()});
        AccountStore.StoredAccount authorStore = AccountStore.merge(
                null, author, now, authorTweets, authorInbound, monthCoverage, monthCoverage, null);
        AccountStore.write(dir.resolve("users/by-id/9000/store_v" + AccountStore.VERSION + ".json.gz"), authorStore, objectMapper);

        // Pre-persist ONLY rank 6 (within Top 50) and rank 52 (outside Top 50). Rank 1 is NOT persisted beforehand.
        XUserSummary rank6 = new XUserSummary("6006", "peer_rank_6", "Rank 6", "", "", false, 10, 10, 10, "");
        AccountStore.StoredAccount rank6Store = AccountStore.merge(
                null, rank6, now.minus(Duration.ofDays(1)), List.of(), List.of(), List.of(), List.of(), null);
        AccountStore.write(dir.resolve("users/by-id/6006/store_v" + AccountStore.VERSION + ".json.gz"), rank6Store, objectMapper);

        XUserSummary rank52 = new XUserSummary("6052", "peer_rank_52", "Rank 52", "", "", false, 10, 10, 10, "");
        AccountStore.StoredAccount rank52Store = AccountStore.merge(
                null, rank52, now.minus(Duration.ofDays(1)), List.of(), List.of(), List.of(), List.of(), null);
        AccountStore.write(dir.resolve("users/by-id/6052/store_v" + AccountStore.VERSION + ".json.gz"), rank52Store, objectMapper);

        List<String> syncedUsernames = new CopyOnWriteArrayList<>();
        String recentIso = oneHourAgo.toString();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            upstream.createContext("/twitter/user/info", ex -> {
                String uname = queryParam(ex.getRequestURI().getRawQuery(), "userName");
                syncedUsernames.add(uname.toLowerCase(Locale.ROOT));
                String uid = "peer_rank_6".equalsIgnoreCase(uname) ? "6006"
                        : "peer_rank_52".equalsIgnoreCase(uname) ? "6052" : "6001";
                writeJson(ex, String.format("""
                        {"status":"success","data":{"id":"%s","userName":"%s","name":"%s","followers_count":10,"following_count":10,"statusesCount":5,"isBlueVerified":false}}
                        """, uid, uname, uname));
            });
            upstream.createContext("/twitter/user/followings", ex -> writeJson(ex, """
                    {"status":"success","followings":[],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/user/followers", ex -> writeJson(ex, """
                    {"status":"success","followers":[],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/user/last_tweets", ex -> {
                String cursor = queryParam(ex.getRequestURI().getRawQuery(), "cursor");
                if ("page2_should_not_be_fetched".equals(cursor)) {
                    ex.sendResponseHeaders(500, -1);
                    return;
                }
                String old26hIso = now.minus(Duration.ofHours(26)).toString();
                writeJson(ex, String.format("""
                        {"status":"success","data":{"tweets":[
                            {"id":"tw_t","text":"post","conversationId":"tw_t","isReply":false,"createdAt":"%s","viewCount":10,"likeCount":1,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0},
                            {"id":"tw_26h","text":"older_than_24h","conversationId":"tw_26h","isReply":false,"createdAt":"%s","viewCount":5,"likeCount":0,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0}
                        ]},"has_next_page":true,"next_cursor":"page2_should_not_be_fetched"}
                        """, recentIso, old26hIso));
            });
            upstream.createContext("/twitter/user/mentions", ex -> writeJson(ex, """
                    {"status":"success","tweets":[],"has_next_page":false}
                    """));
            upstream.createContext("/twitter/tweet/advanced_search", ex -> {
                String cursor = queryParam(ex.getRequestURI().getRawQuery(), "cursor");
                if ("page2_should_not_be_fetched".equals(cursor)) {
                    ex.sendResponseHeaders(500, -1);
                    return;
                }
                String q = queryParam(ex.getRequestURI().getRawQuery(), "query");
                if (q.startsWith("from:")) {
                    String old26hIso = now.minus(Duration.ofHours(26)).toString();
                    writeJson(ex, String.format("""
                            {"tweets":[
                                {"id":"tw_t","text":"post","conversationId":"tw_t","isReply":false,"createdAt":"%s","viewCount":10,"likeCount":1,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0},
                                {"id":"tw_26h","text":"older_than_24h","conversationId":"tw_26h","isReply":false,"createdAt":"%s","viewCount":5,"likeCount":0,"replyCount":0,"retweetCount":0,"quoteCount":0,"bookmarkCount":0}
                            ],"has_next_page":true,"next_cursor":"page2_should_not_be_fetched"}
                            """, recentIso, old26hIso));
                } else {
                    writeJson(ex, """
                            {"tweets":[],"has_next_page":false,"next_cursor":""}
                            """);
                }
            });
            upstream.createContext("/twitter/user/batch_info_by_ids", ex -> writeJson(ex, """
                    {"status":"success","users":[]}
                    """));
            upstream.start();

            XOpsProperties props = baseTwitterProps(dir, upstream.getAddress().getPort());
            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, objectMapper);
            CommunityHubService service = new CommunityHubService(
                    props, new DemoXDataProvider(), provider, new OfficialXApiV2Provider(props, objectMapper), objectMapper);

            // Pre-seed rank 6 with a stale 35-day-old tweet and a 10-day-old tweet to verify dynamic 30-day rolling retention on daily physical sync
            RawTweet stale35d = new RawTweet("tw_stale_35d", "old", now.minus(Duration.ofDays(35)), TweetKind.POST,
                    "", null, List.of(), false, false, 1, 0, 0, 0, 0, false, "tw_stale_35d", "");
            RawTweet within10d = new RawTweet("tw_keep_10d", "keep", now.minus(Duration.ofDays(10)), TweetKind.POST,
                    "", null, List.of(), false, false, 5, 1, 0, 0, 0, false, "tw_keep_10d", "");
            AccountStore.StoredAccount seededRank6 = new AccountStore.StoredAccount(
                    AccountStore.VERSION, rank6, now.minus(Duration.ofDays(1)).getEpochSecond(), Map.of(),
                    List.of(
                            new AccountStore.StoredTweet("tw_keep_10d", within10d.createdAt().getEpochSecond(), 'P', "keep", null, List.of(), 0, new long[]{5, 1, 0, 0, 0}, "tw_keep_10d", null),
                            new AccountStore.StoredTweet("tw_stale_35d", stale35d.createdAt().getEpochSecond(), 'P', "old", null, List.of(), 0, new long[]{1, 0, 0, 0, 0}, "tw_stale_35d", null)
                    ),
                    List.of(),
                    List.of(new long[]{now.minus(Duration.ofDays(36)).getEpochSecond(), now.minus(Duration.ofDays(1)).getEpochSecond()}),
                    List.of(), List.of(), List.of(), List.of(), 0L, false
            );
            Path rank6StoreFile = dir.resolve("users/by-id/6006/store_v" + AccountStore.VERSION + ".json.gz");
            // Write raw pre-35d tweet directly via mapper to simulate an aged disk file from last month
            try (java.io.OutputStream gz = new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(rank6StoreFile))) {
                objectMapper.writeValue(gz, seededRank6);
            }

            // Top-50 scheduled auto-sync runs ONLY for registered persisted peers in Top 50 (peer_rank_6), loads 24h & prunes >30d tweets on disk
            int count = service.runTop50AutoSyncNow();
            assertThat(count).isEqualTo(1);
            assertThat(syncedUsernames).containsExactly("peer_rank_6");
            AccountStore.StoredAccount syncedRank6 = AccountStore.read(rank6StoreFile, objectMapper);
            assertThat(syncedRank6.tweets()).extracting(AccountStore.StoredTweet::id)
                    .contains("tw_t", "tw_keep_10d")
                    .doesNotContain("tw_stale_35d");

            // Rank 1 (Top 5) logs in and loads MONTH: all 4 periods unlocked, single load fetches ONLY 24h (never requests page2!), and writes physical store
            MultiPeriodDashboardBundle top1Bundle = service.analyzeWorkspaceBundle(
                    "peer_rank_1", ReportPeriod.MONTH, null, false, "127.0.0.1", "6001", false);
            assertThat(top1Bundle.accessPolicy().authorInteractionRank()).isEqualTo(1);
            assertThat(top1Bundle.accessPolicy().top5FullAccess()).isTrue();
            assertThat(top1Bundle.accessPolicy().top50AutoSync()).isTrue();
            assertThat(top1Bundle.accessPolicy().accumulatedSummaryMode()).isFalse();
            assertThat(top1Bundle.hasMoreHistoryForTarget()).isFalse();
            assertThat(top1Bundle.accessPolicy().unlockedPeriods())
                    .containsExactly("HOURS_24", "HOURS_48", "WEEK", "MONTH");
            assertThat(top1Bundle.hours24()).isNotNull();
            assertThat(top1Bundle.day()).isNotNull();
            assertThat(top1Bundle.week()).isNotNull();
            assertThat(top1Bundle.month()).isNotNull();
            assertThat(dir.resolve("users/by-id/6001/store_v" + AccountStore.VERSION + ".json.gz")).isRegularFile();
        } finally {
            upstream.stop(0);
        }
    }

    private void writeSyncedDates(Path usageFile, int days) throws IOException {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<String> dates = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            dates.add(today.minusDays(i).toString());
        }
        CommunityHubService.UserUsageEntry entry = new CommunityHubService.UserUsageEntry(
                0, 0, null, Instant.now(), today.toString(), true, 1, dates);
        objectMapper.writeValue(usageFile.toFile(), entry);
    }

    private static RawTweet replyTweet(String id, String targetUsername, XUserSummary targetUser,
                                      String convId, String inReplyToId, Instant createdAt) {
        return new RawTweet(id, "@" + targetUsername + " reply", createdAt, TweetKind.REPLY,
                targetUsername, targetUser, List.of(targetUsername), false, false,
                10, 1, 0, 0, 0, false, convId, inReplyToId);
    }

    private static XOpsProperties baseTwitterProps(Path dir, int port) {
        XOpsProperties props = new XOpsProperties();
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setTwitterapiIoKey("test-api-key");
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
        props.setDiskCacheDir(dir.toString());
        props.setCacheTtlSeconds(300);
        props.setTimezone("Asia/Shanghai");
        return props;
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).equals(key)) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
