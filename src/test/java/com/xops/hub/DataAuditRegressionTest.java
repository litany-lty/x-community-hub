package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.*;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;

class DataAuditRegressionTest {
    static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    static XUserSummary user(String name, int followers, int following) {
        return new XUserSummary(name, name, name, "", "", false, followers, following, 20, "2020-01-01");
    }
    static RawTweet tweet(String id, Instant at, TweetKind kind, String peer, List<String> mentions) {
        return new RawTweet(id, id, at, kind, peer, peer == null ? null : user(peer, 100, 10), mentions,
                false, false, 100, 1, 0, 0, 0);
    }
    @Test void quotedThirdPartyWithMentionIsNotQuoteOfMe() throws Exception {
        var props = new XOpsProperties(); var pool = new SharedApiExecutor(props);
        try {
            var provider = new TwitterApiIoProvider(props,mapper,pool);
            var method = TwitterApiIoProvider.class.getDeclaredMethod("parseInbound", JsonNode.class,String.class);
            method.setAccessible(true);
            var node = mapper.readTree("""
                    {"id":"q","text":"@self look at this","createdAt":"2026-11-13T07:00:00Z",
                     "author":{"id":"peer","userName":"peer"},
                     "quoted_tweet":{"id":"otherpost","author":{"id":"third","userName":"third"}}}
                    """);
            var inbound = (InboundInteraction) method.invoke(provider,node,"self");
            assertThat(inbound.kind()).isEqualTo(InboundKind.MENTION_ME);
        } finally { pool.shutdown(); }
    }
    @Test void officialQuoteUsesQuotedAuthorWithoutTextMention() throws Exception {
        var s=server(path -> {
            if(path.contains("/by/username/")) return "{\"data\":{\"id\":\"self\",\"username\":\"self\"}}";
            if(path.contains("/tweets?")) return """
                    {"data":[{"id":"q","text":"My comment","created_at":"2026-11-13T07:00:00Z",
                     "referenced_tweets":[{"type":"quoted","id":"original"}]}],
                     "includes":{"tweets":[{"id":"original","author_id":"peer","text":"Original"}],
                                 "users":[{"id":"peer","username":"peer","name":"Peer"}]}}
                    """;
            if(path.contains("/mentions?")) return """
                    {"data":[{"id":"mention-q","author_id":"peer","text":"@self look","created_at":"2026-11-13T07:00:00Z",
                      "referenced_tweets":[{"type":"quoted","id":"third-post"}]}],
                     "includes":{"tweets":[{"id":"third-post","author_id":"third"}],
                       "users":[{"id":"peer","username":"peer"},{"id":"third","username":"third"}]}}
                    """;
            return "{\"data\":[]}";
        });
        var props = new XOpsProperties();
        props.setOfficialBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()+"/2");props.setOfficialBearerToken("dummy");
        var pool = new SharedApiExecutor(props);
        try {
            var provider = new OfficialXApiV2Provider(props,mapper,pool);
            var snapshot = provider.fetchSnapshot("self",null);
            assertThat(snapshot.timeline().get(0).targetUsername()).isEqualTo("peer");
            assertThat(snapshot.inboundInteractions().get(0).kind()).isEqualTo(InboundKind.MENTION_ME);
        } finally { pool.shutdown(); s.stop(0); }
    }
    static HttpServer server(Function<String,String> response) throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        s.createContext("/", ex -> {
            var body = response.apply(ex.getRequestURI().toString());
            int code = body.equals("ERROR503") ? 503 : 200;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json");
            ex.sendResponseHeaders(code,bytes.length);
            try(var out=ex.getResponseBody()) {out.write(bytes);}
        });
        s.start();return s;
    }
    @Test void officialNextPageMustMarkSnapshotIncomplete() throws Exception {
        var s=server(path -> {
            if(path.contains("/by/username/")) return "{\"data\":{\"id\":\"1\",\"username\":\"self\",\"public_metrics\":{\"followers_count\":300,\"following_count\":1}}}";
            if(path.contains("/following?")) return "{\"data\":[{\"id\":\"2\",\"username\":\"oldfan\"}]}";
            if(path.contains("/followers?")) return "{\"data\":[{\"id\":\"3\",\"username\":\"newfan\"}],\"meta\":{\"next_token\":\"next-page\"}}";
            return "{\"data\":[],\"meta\":{\"next_token\":\"next-page\"}}";
        });
        var p=new XOpsProperties();p.setOfficialBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()+"/2");p.setOfficialBearerToken("dummy");
        var pool=new SharedApiExecutor(p);
        try {
            var snapshot=new OfficialXApiV2Provider(p,mapper,pool).fetchSnapshot("self",null);
            assertThat(snapshot.followersTruncated()).as("next_token is present").isTrue();
            assertThat(snapshot.timelineExhausted()).isFalse();
            assertThat(snapshot.mentionsExhausted()).isFalse();
            assertThat(snapshot.coveredWindowDays()).isZero();
            assertThat(snapshot.degradedPartial()).isTrue();
        } finally {pool.shutdown();s.stop(0);}
    }
    @Test void failedGraphRefreshMustNotEraseKnownFollowers() throws Exception {
        var self=user("self",2,1); var peer=user("peer",100,10); var old=Instant.now().minusSeconds(400);
        var coverage=List.of(new long[]{old.minus(Duration.ofDays(30)).getEpochSecond(),old.getEpochSecond()});
        var stored=AccountStore.merge(null,user("self",1,1),old,List.of(),List.of(),coverage,coverage,
                new AccountStore.GraphUpdate(List.of(peer),List.of(peer),Set.of(),false));
        var followerRequests = new java.util.concurrent.atomic.AtomicInteger();
        var s=server(path -> {
            if(path.contains("/info?")) return "{\"data\":{\"id\":\"self\",\"userName\":\"self\",\"followers\":2,\"following\":1}}";
            if(path.contains("/followings?")) return "{\"followings\":[{\"id\":\"peer\",\"userName\":\"peer\"}],\"has_next_page\":false}";
            if(path.contains("/followers?")) {followerRequests.incrementAndGet(); return "ERROR503";}
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p=new XOpsProperties();p.setTwitterapiIoBaseUrl("http://127.0.0.1:"+s.getAddress().getPort());p.setTwitterapiIoKey("dummy");
        var pool=new SharedApiExecutor(p);
        try {
            var result=new TwitterApiIoProvider(p,mapper,pool).syncStore("self",stored,2,true,null);
            assertThat(result.degraded()).isTrue();
            assertThat(result.store().followers()).contains("peer");
            assertThat(result.store().graphFetchedAt()).isEqualTo(stored.graphFetchedAt());
            assertThat(result.store().followersTruncated()).isTrue();
            assertThat(result.store().graphRefreshFailed()).isTrue();
            new TwitterApiIoProvider(p,mapper,pool).syncStore("self",result.store(),2,true,null);
            assertThat(followerRequests.get()).isEqualTo(2);
        } finally {pool.shutdown();s.stop(0);}
    }
    @Test void incrementalSyncMustRefreshOldEngagementMetrics() throws Exception {
        var old=Instant.now().minusSeconds(400);var oldTweet=tweet("oldpost",old.minus(Duration.ofDays(10)),TweetKind.POST,null,List.of());
        var coverage=List.of(new long[]{old.minus(Duration.ofDays(30)).getEpochSecond(),old.getEpochSecond()});
        var stored=AccountStore.merge(null,user("self",0,0),old,List.of(oldTweet),List.of(),coverage,coverage,new AccountStore.GraphUpdate(List.of(),List.of(),Set.of(),false));
        var s=server(path -> {
            if(path.contains("/info?")) return "{\"data\":{\"id\":\"self\",\"userName\":\"self\",\"followers\":0,\"following\":0}}";
            if(path.contains("/last_tweets?")) return "{\"tweets\":[{\"id\":\"recent\",\"text\":\"recent\",\"createdAt\":\""+old.minusSeconds(60)+"\",\"likeCount\":2}],\"has_next_page\":false}";
            if(path.contains("/tweets?")) return "{\"tweets\":[{\"id\":\"oldpost\",\"text\":\"oldpost\",\"createdAt\":\""+oldTweet.createdAt()+"\",\"likeCount\":99}]}";
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p=new XOpsProperties();p.setTwitterapiIoBaseUrl("http://127.0.0.1:"+s.getAddress().getPort());p.setTwitterapiIoKey("dummy");
        var pool=new SharedApiExecutor(p);
        try {
            var result=new TwitterApiIoProvider(p,mapper,pool).syncStore("self",stored,30,true,null);
            var snapshot=TwitterApiIoProvider.snapshotOf(result.store(),"",false);
            assertThat(snapshot.timeline().stream().filter(t->t.id().equals("oldpost")).findFirst().orElseThrow().likeCount()).isEqualTo(99);
        } finally {pool.shutdown();s.stop(0);}
    }

    @Test void followingPaginationIsIndependentOfCompleteFollowers() throws Exception {
        var s = server(path -> {
            if(path.contains("/by/username/")) return "{\"data\":{\"id\":\"self\",\"username\":\"self\"}}";
            if(path.contains("/following?")) return "{\"data\":[],\"meta\":{\"next_token\":\"more\"}}";
            return "{\"data\":[]}";
        });
        var p = new XOpsProperties(); p.setOfficialBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()+"/2"); p.setOfficialBearerToken("dummy");
        var pool = new SharedApiExecutor(p);
        try {
            var snapshot = new OfficialXApiV2Provider(p,mapper,pool).fetchSnapshot("self",null);
            assertThat(snapshot.followingsTruncated()).isTrue();
            assertThat(snapshot.followersTruncated()).isFalse();
        } finally { pool.shutdown(); s.stop(0); }
    }

    @Test void failedMentionsMustNotClaimThirtyDayCoverage() throws Exception {
        var s = server(path -> {
            if(path.contains("/by/username/")) return "{\"data\":{\"id\":\"self\",\"username\":\"self\"}}";
            if(path.contains("/mentions?")) return "ERROR503";
            return "{\"data\":[]}";
        });
        var p = new XOpsProperties(); p.setOfficialBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()+"/2"); p.setOfficialBearerToken("dummy");
        var pool = new SharedApiExecutor(p);
        try {
            var snapshot = new OfficialXApiV2Provider(p,mapper,pool).fetchSnapshot("self",null);
            assertThat(snapshot.degradedPartial()).isTrue();
            assertThat(snapshot.mentionsExhausted()).isFalse();
            assertThat(snapshot.coveredWindowDays()).isZero();
        } finally { pool.shutdown(); s.stop(0); }
    }

    @Test void explicitProviderMustWinOverOtherCredentialAndMissingCredentialMustFail() {
        var p = new XOpsProperties(); p.setDefaultProvider("X_OFFICIAL_V2");
        p.setTwitterapiIoKey("dummy-twitter"); p.setOfficialBearerToken("dummy-official");
        var hub = new com.xops.hub.service.CommunityHubService(p,null,null,null,mapper);
        assertThat(hub.getActiveProviderName()).isEqualTo("X_OFFICIAL_V2");
        p.setOfficialBearerToken("");
        org.assertj.core.api.Assertions.assertThatThrownBy(hub::getActiveProviderName)
                .isInstanceOf(XDataProviderException.class);
        p.setDefaultProvider("AUTO");
        assertThat(hub.getActiveProviderName()).isEqualTo("TWITTERAPI_IO");
    }

    @Test void legacyStoreRetainsDataAndInfersMissingFollowingCompleteness() throws Exception {
        var stored = AccountStore.merge(null,user("self",1,2),NOW,List.of(),List.of(),List.of(),List.of(),
                new AccountStore.GraphUpdate(List.of(user("peer",0,0)),List.of(user("fan",0,0)),Set.of(),false));
        var json = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(stored);
        json.remove("gy"); json.remove("gf");
        var restored = mapper.treeToValue(json,AccountStore.StoredAccount.class);
        assertThat(restored.followings()).containsExactly("peer");
        assertThat(restored.followers()).containsExactly("fan");
        assertThat(restored.followingsTruncated()).isTrue();
        assertThat(AccountStore.toSnapshot(restored,"",false,30).followingsTruncated()).isTrue();
    }

    @Test void metricsRefreshCapsAtFiftyAndMissingIdsDoNotBlockNextBatch() throws Exception {
        var old = Instant.now().minusSeconds(400);
        var tweets = new ArrayList<RawTweet>();
        for(int i=0;i<51;i++) tweets.add(tweet("old"+i,old.minus(Duration.ofDays(10)).minusSeconds(i),TweetKind.POST,null,List.of()));
        var coverage=List.of(new long[]{old.minus(Duration.ofDays(30)).getEpochSecond(),old.getEpochSecond()});
        var stored=AccountStore.merge(null,user("self",0,0),old,tweets,List.of(),coverage,coverage,
                new AccountStore.GraphUpdate(List.of(),List.of(),Set.of(),false));
        var batches = new java.util.concurrent.CopyOnWriteArrayList<List<String>>();
        var s=server(path -> {
            if(path.contains("/info?")) return "{\"data\":{\"id\":\"self\",\"userName\":\"self\",\"followers\":0,\"following\":0}}";
            if(path.contains("/twitter/tweets?")) {
                String ids=java.net.URLDecoder.decode(path.substring(path.indexOf("tweet_ids=")+10),StandardCharsets.UTF_8);
                batches.add(List.of(ids.split(",")));
            }
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p=new XOpsProperties(); p.setTwitterapiIoBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()); p.setTwitterapiIoKey("dummy");
        var pool=new SharedApiExecutor(p);
        try {
            var provider=new TwitterApiIoProvider(p,mapper,pool);
            var first=provider.syncStore("self",stored,30,true,null);
            var second=provider.syncStore("self",first.store(),30,true,null);
            assertThat(batches).hasSize(2);
            assertThat(batches.get(0)).hasSize(50);
            String previouslySkipped=tweets.stream().map(RawTweet::id).filter(id -> !batches.get(0).contains(id)).findFirst().orElseThrow();
            assertThat(batches.get(1)).hasSize(50).contains(previouslySkipped);
            assertThat(second.store().tweets()).hasSize(51);
            assertThat(second.store().tweets().get(0).metricsFetchedAt()).isEqualTo(old.getEpochSecond());
        } finally { pool.shutdown(); s.stop(0); }
    }
    @Test void incompleteOfficialSnapshotStillUsesTtlAcrossPeriodSwitches() throws Exception {
        var profiles = new java.util.concurrent.atomic.AtomicInteger();
        var s=server(path -> {
            if(path.contains("/by/username/")) {
                profiles.incrementAndGet();
                return "{\"data\":{\"id\":\"self\",\"username\":\"self\"}}";
            }
            return "{\"data\":[],\"meta\":{\"next_token\":\"more\"}}";
        });
        var p = new XOpsProperties(); p.setDefaultProvider("X_OFFICIAL_V2");
        p.setOfficialBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()+"/2"); p.setOfficialBearerToken("dummy");
        var pool = new SharedApiExecutor(p);
        try {
            var official = new OfficialXApiV2Provider(p,mapper,pool);
            var hub = new com.xops.hub.service.CommunityHubService(p,null,null,official,mapper);
            hub.analyzeAccountBundle("self",2,null,false,"test");
            hub.analyzeAccountBundle("self",30,null,false,"test");
            assertThat(profiles.get()).isEqualTo(1);
        } finally {pool.shutdown();s.stop(0);}
    }

    @Test void twitterFollowingPageCapPropagatesIndependently() throws Exception {
        var s=server(path -> {
            if(path.contains("/info?")) return "{\"data\":{\"id\":\"self\",\"userName\":\"self\",\"followers\":0,\"following\":2}}";
            if(path.contains("/followings?")) return "{\"followings\":[{\"id\":\"peer\",\"userName\":\"peer\"}],\"has_next_page\":true,\"next_cursor\":\"more\"}";
            return "{\"tweets\":[],\"has_next_page\":false}";
        });
        var p = new XOpsProperties(); p.setTwitterapiIoBaseUrl("http://127.0.0.1:"+s.getAddress().getPort());
        p.setTwitterapiIoKey("dummy"); p.setMaxFollowingPages(1);
        var pool = new SharedApiExecutor(p);
        try {
            var result = new TwitterApiIoProvider(p,mapper,pool).syncStore("self",null,2,true,null);
            var snapshot = TwitterApiIoProvider.snapshotOf(result.store(),"",result.degraded());
            assertThat(snapshot.followingsTruncated()).isTrue();
            assertThat(snapshot.followersTruncated()).isFalse();
            assertThat(snapshot.relationships()).hasSize(1);
        } finally {pool.shutdown();s.stop(0);}
    }

}
