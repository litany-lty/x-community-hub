package com.xops.hub;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.*;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.model.AnalyticsReportModels.*;
import com.xops.hub.provider.*;
import com.xops.hub.service.*;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DetailedRecheckTest {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final Instant NOW = Instant.parse("2026-11-13T02:37:00Z");
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static XUserSummary user(String id,String handle) {return new XUserSummary(id,handle,handle,"","",false,100,0,42,"2020-01-01");}
    static RawTweet post(String id,Instant at) {return new RawTweet(id,id,at,TweetKind.POST,null,null,List.of(),false,false,150,2,0,0,0);}
    static RawAccountSnapshot snapshot(XUserSummary self,List<RawTweet> tweets) {
        return new RawAccountSnapshot(ProviderMode.X_OFFICIAL_V2,true,"",NOW,self,tweets,List.of(),List.of());
    }
    static XOpsProperties props() {
        var p=new XOpsProperties();p.setDefaultProvider("X_OFFICIAL_V2");p.setOfficialBearerToken("dummy");p.setDiskCacheDir("");return p;
    }
    static CommunityHubService hub(XOpsProperties p,RawAccountSnapshot snapshot,SharedApiExecutor pool) {
        var official=new OfficialXApiV2Provider(p,MAPPER,pool) {
            @Override public RawAccountSnapshot fetchSnapshot(String username, String token, String expectedAccountId) {
                return snapshot;
            }
        };
        return new CommunityHubService(p,new DemoXDataProvider(),null,official,MAPPER);
    }
    @Test void zeroCoverageMustSurviveBundleConstructor() {
        var bundle=new MultiPeriodDashboardBundle(null,null,null,0,true,NOW);
        assertThat(bundle.coveredWindowDays()).isZero();
    }
    @Test void partialMinutesMustNotBecomeTwoCoveredDaysInService() {
        var p=props();var pool=new SharedApiExecutor(p);
        try {
            var raw=new RawAccountSnapshot(ProviderMode.X_OFFICIAL_V2,true,"Partial",NOW,user("1","same_handle"),
                    List.of(post("p",NOW.minusSeconds(600))),List.of(),List.of(),false,true,0,
                    NOW.minusSeconds(600),"next",false,NOW,"",false,false);
            var result=hub(p,raw,pool).analyzeAccountBundle("same_handle",2,null,false,"127.0.0.1");
            assertThat(result.coveredWindowDays()).isZero();
        } finally {pool.shutdown();}
    }
    @Test void authenticatedUserIdMustMatchReportAfterHandleReuse() {
        var p=props();var pool=new SharedApiExecutor(p);
        try {
            var current=snapshot(user("new-owner-id","same_handle"),List.of(post("new-owner-post",NOW.minusSeconds(600))));
            var controller=new CommunityApiController(p,hub(p,current,pool),pool);
            var request=new MockHttpServletRequest();var session=new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,new AuthenticatedXUser("old-owner-id","same_handle","Old owner","",false,NOW));
            request.setSession(session);request.setRemoteAddr("127.0.0.1");
            assertThatThrownBy(()->controller.analyze(null,"WEEK",7,null,false,request,new MockHttpServletResponse()))
                    .as("same username no longer establishes the same X account")
                    .isInstanceOfSatisfying(XDataProviderException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(409));
        } finally {pool.shutdown();}
    }
    @Test void persistedHistoryMustNotMergeAcrossDifferentIdsSharingAHandle() {
        var old=AccountStore.merge(null,user("old-owner-id","same_handle"),NOW,List.of(post("old-owner-post",NOW.minusSeconds(600))),
                List.of(),List.of(),List.of(),null);
        assertThatThrownBy(()->AccountStore.merge(old,user("new-owner-id","same_handle"),NOW,List.of(post("new-owner-post",NOW.minusSeconds(100))),
                List.of(),List.of(),List.of(),null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rollingFortyEightHourActivityMustNotExceedDenominator() {
        var tweets=new ArrayList<RawTweet>();var cutoff=NOW.minus(Duration.ofHours(48));
        tweets.add(post("edge",cutoff));
        var hour=cutoff.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusSeconds(3600);
        while(!hour.isAfter(NOW)) {tweets.add(post("p"+tweets.size(),hour));hour=hour.plusSeconds(3600);}
        var result=new CommunityAnalyticsEngine().analyze(snapshot(user("1","self"),tweets),2,ZONE,false);
        assertThat(tweets).hasSize(49);
        assertThat(result.activity().activeUnits()).isLessThanOrEqualTo(result.activity().totalUnits());
    }
    @Test void replyToPeerInsideThirdPartyThreadIsNotVisitToPeersOwnPost() {
        var peer=user("2","peer");
        var nested=new RawTweet("my-nested-reply","reply in third author's thread",NOW.minusSeconds(600),TweetKind.REPLY,
                "peer",peer,List.of(),false,false,0,0,0,0,0,false,"third-author-root","peer-comment");
        var result=new CommunityAnalyticsEngine().analyze(snapshot(user("1","self"),List.of(nested)),7,ZONE,false);
        int counted=result.interactions().peerStats().stream().filter(s->s.peer().username().equals("peer")).mapToInt(PeerInteractionStat::myRepliesToPeer).sum();
        assertThat(counted).as("parent-comment author is not the root-post author").isZero();
    }
    @Test void viewCountDoesNotProveForYouTraffic() {
        var result=new CommunityAnalyticsEngine().analyze(snapshot(user("1","self"),List.of(post("p",NOW.minusSeconds(600)))),7,ZONE,false);
        assertThat(result.algorithmHealth().healthSummary()).doesNotContain("进入 For You 陌生人推荐流");
    }
    @Test void currentDocumentedOfficialFieldsMustRetainTypeAndCounts() throws Exception {
        var s=DataAuditRegressionTest.server(path->{
            if(path.contains("by/username")) return "{\"data\":{\"id\":\"1\",\"username\":\"self\",\"public_metrics\":{\"post_count\":42}}}";
            if(path.contains("/tweets?")) return """
                {"data":[{"id":"quote","text":"my quote","created_at":"2026-11-13T02:00:00Z",
                "referenced_posts":[{"type":"quoted","id":"original"}],"public_metrics":{"repost_count":7}}],
                "includes":{"posts":[{"id":"original","author_id":"peer-id"}],"users":[{"id":"peer-id","username":"peer"}]}}
                """;
            return "{\"data\":[]}";
        });
        var p=props();p.setOfficialBaseUrl("http://127.0.0.1:"+s.getAddress().getPort()+"/2");var pool=new SharedApiExecutor(p);
        try {
            var result=new OfficialXApiV2Provider(p,MAPPER,pool).fetchSnapshot("self",null);
            assertThat(List.of(result.timeline().get(0).kind().name(),String.valueOf(result.timeline().get(0).targetUsername()),
                    ""+result.timeline().get(0).retweetCount(),""+result.profile().totalPostsCount()))
                    .isEqualTo(List.of("QUOTE","peer","7","42"));
        } finally {pool.shutdown();s.stop(0);}
    }
    @AfterAll static void generateBrowserFixturesFromActualEngine() throws Exception {
        var demo=new DemoXDataProvider().generateSnapshot("audit_user","Synthetic browser audit fixture");
        var partial=new RawAccountSnapshot(demo.resolvedProvider(),false,"Synthetic partial follower fixture",demo.fetchedAt(),demo.profile(),
                demo.timeline(),demo.inboundInteractions(),demo.relationships(),true,false,false);
        var engine=new CommunityAnalyticsEngine();var out=Path.of(System.getProperty("java.io.tmpdir"));
        for(var entry:Map.of("normal",demo,"partial",partial).entrySet()) {
            var s=entry.getValue();var expiry=Instant.now().plusSeconds(300);
            var bundle=new MultiPeriodDashboardBundle(engine.analyze(s,30,ZONE,false,300,expiry),engine.analyze(s,7,ZONE,false,300,expiry),
                    engine.analyze(s,2,ZONE,false,300,expiry),30,false,s.fetchedAt().minus(Duration.ofDays(30)));
            JsonNode json=MAPPER.valueToTree(bundle);stripAvatars(json);
            MAPPER.writeValue(out.resolve("browser-"+entry.getKey()+".json").toFile(),json);
        }
    }
    static void stripAvatars(JsonNode n) {
        if(n.isObject()) {var o=(ObjectNode)n;if(o.has("avatarUrl")) o.put("avatarUrl","");o.elements().forEachRemaining(DetailedRecheckTest::stripAvatars);}
        if(n.isArray()) n.elements().forEachRemaining(DetailedRecheckTest::stripAvatars);
    }
}
