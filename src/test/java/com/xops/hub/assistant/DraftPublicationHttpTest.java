package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class DraftPublicationHttpTest extends LocalPublicationHttpFixture {
    enum Pathway { DIRECT, CREATE_SCHEDULE, UPDATE_SCHEDULE }

    private JsonNode http(String route, ObjectNode body) throws Exception {
        var request = body == null ? get(route) : post(route).contentType("application/json")
                .header("X-Requested-With", "XMLHttpRequest").header("Origin", "http://localhost").content(json.writeValueAsBytes(body));
        var result = mvc.perform(request.session(session)).andReturn().getResponse();
        String response = result.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, result.getStatus(), route + " " + response);
        return json.readTree(response);
    }

    private ObjectNode material(String id, String post, String handle, String text) {
        return object().put("id", id).put("authorId", "7").put("username", handle).put("text", text)
                .put("sourceUrl", "https://x.com/" + handle + "/status/" + post);
    }

    private JsonNode schedule(int version, String text, String quote, String url) throws Exception {
        return http(QUEUE, object().put("assistantDraftId", DRAFT).put("assistantDraftVersion", version).put("text", text)
                .put("quoteTweetId", quote).put("quoteTweetUrl", url).put("scheduledAtEpochMs", due).put("clientRequestId", "intent"));
    }

    private void publish(Pathway pathway, JsonNode saved, JsonNode priorJob) throws Exception {
        int version = saved.path("version").asInt();
        if (pathway == Pathway.DIRECT) {
            http(EDIT + "/publish-now", object().put("version", version).put("quoteSource", true).put("clientRequestId", "publish"));
            return;
        }
        if (pathway == Pathway.CREATE_SCHEDULE) {
            schedule(version, saved.path("text").asText(), saved.at("/quoteSourcePost/tweetId").asText(), saved.at("/quoteSourcePost/url").asText());
        } else {
            http(EDIT + "/update-scheduled", object().put("version", version).put("intentId", "intent").put("operationId", "update")
                    .put("expectedUpdatedAt", priorJob.path("updatedAtEpochMs").asLong()));
        }
        assertTrue(captured.isEmpty());
        clock.now = Instant.ofEpochMilli(due + 1000);
        scheduler.processDueJobsForAccount(ACCOUNT);
        assertEquals("PUBLISHED", http(QUEUE, null).at("/items/0/status").asText());
    }

    private String moveB(String from, String to) throws Exception {
        var request = object().put("targetTopicId", to);
        request.putArray("materialIds").add(B);
        var versions = request.putObject("versions");
        versions.put(from, http(BASE + "/topics/" + from + "/detail", null).at("/topic/version").asInt());
        if (!to.isEmpty()) versions.put(to, http(BASE + "/topics/" + to + "/detail", null).at("/topic/version").asInt());
        return http(BASE + "/topics/move", request).path("id").asText();
    }

    @ParameterizedTest @EnumSource(Pathway.class)
    void splitAdoptedDraftUsesItsCurrentTextAndAuthorAtEveryFallback(Pathway pathway) throws Exception {
        restrictQuotes = true;
        var greenhouse = material(A, A, "garden", "温室报告");
        var glacier = material(B, B, "glacier", "冰川考察");
        String topicA = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, greenhouse));
        String topicB = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, glacier));
        moveB(topicB, topicA);
        store.tx(tx -> {
            var frozen = object().set("snapshot", AssistantTopics.snapshot(tx, topicA, B));
            tx.put("draft", DRAFT, object().put("topicId", topicA).put("text", "冰川初稿").set("research", frozen));
            return tx.put("draft", DRAFT, object().put("topicId", topicA).put("materialId", A).put("text", "温室旧稿"));
        });
        var prior = pathway == Pathway.UPDATE_SCHEDULE ? schedule(2, "温室旧稿", A, greenhouse.path("sourceUrl").asText()) : null;
        String destination = moveB(topicA, "");
        var adopted = http(EDIT + "/adopt", object().put("version", 2).put("sourceVersion", 1));
        assertEquals(destination, adopted.path("topicId").asText());
        if (pathway == Pathway.UPDATE_SCHEDULE) {
            store.tx(tx -> {
                tx.put("observation", "900:24", object().put("topicId", topicA).put("materialId", A).put("draftId", DRAFT)
                        .put("draftVersion", 2).put("views", 357));
                for (int i = 0; i < 101; i++) tx.put("publication", "filler-" + i,
                        object().put("topicId", topicA).put("materialId", A).put("status", "PUBLISHED"));
                assertFalse(tx.topicRecords("publication", null, 100, 0).stream().anyMatch(row -> "intent".equals(row.path("id").asText())));
                return null;
            });
            var sourceDetail = http(BASE + "/topics/" + topicA + "/detail", null);
            var editingDetail = http(BASE + "/topics/" + destination + "/detail", null);
            assertEquals(357, sourceDetail.at("/observations/0/views").asInt());
            assertTrue(editingDetail.path("observations").isEmpty());
            assertEquals(1, editingDetail.path("publications").size());
            assertEquals("intent", editingDetail.at("/publications/0/id").asText());
            assertEquals(topicA, editingDetail.at("/publications/0/topicId").asText());
        }
        var saved = http(EDIT + "/save", object().put("version", 3).put("text", FINAL_TEXT));
        String url = glacier.path("sourceUrl").asText();
        assertEquals(url, saved.at("/quoteSourcePost/url").asText());
        publish(pathway, saved, prior);
        assertEquals(3, captured.size());
        assertEquals(B, captured.get(0).path("quote_tweet_id").asText());
        assertEquals(FINAL_TEXT, captured.get(0).path("text").asText());
        assertTrue(captured.get(1).path("text").asText().contains(url));
        assertEquals(FINAL_TEXT + "\n\n（消息源：glacier）", captured.get(2).path("text").asText());
    }

    @ParameterizedTest @EnumSource(Pathway.class)
    void frozenAuthorEvidenceSurvivesAReassignedLegacyKey(Pathway pathway) throws Exception {
        var first = material("legacy-link", A, "glacier", "冰川观测");
        String url = first.path("sourceUrl").asText();
        store.tx(tx -> {
            String topic = AssistantWorkflow.ingestDirectLinkMaterial(tx, first);
            var evidence = object();
            var primary = evidence.putArray("materialEvidence");
            primary.addObject().put("id", "material-legacy-link-quoted").put("url", "https://x.com/other/status/30303");
            primary.addObject().put("id", "material-legacy-link-author").put("url", url);
            return tx.put("draft", DRAFT, object().put("topicId", topic).put("materialId", "legacy-link").put("text", "原稿").set("research", evidence));
        });
        assertEquals(A, http(EDIT + "/versions", null).at("/0/quoteSourcePost/tweetId").asText());
        var prior = pathway == Pathway.UPDATE_SCHEDULE ? schedule(1, "原稿", A, url) : null;
        store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, material("legacy-link", B, "other", "温室公告")));
        assertEquals(url, http(EDIT + "/versions", null).at("/0/quoteSourcePost/url").asText());
        var saved = http(EDIT + "/save", object().put("version", 1).put("text", FINAL_TEXT));
        assertEquals(url, saved.at("/quoteSourcePost/url").asText());
        publish(pathway, saved, prior);
        assertEquals(1, captured.size());
        assertEquals(FINAL_TEXT, captured.get(0).path("text").asText());
        assertEquals(A, captured.get(0).path("quote_tweet_id").asText());
    }
    private ObjectNode legacy(String topic, String text) {
        var draft = object().put("topicId",topic).put("text",text);
        store.tx(tx -> draft.put("topicVersion", tx.get("topic",topic).path("version").asInt()));
        return draft;
    }

    @org.junit.jupiter.api.Test void oldTopicVersionAloneIdentifiesSplitSource() throws Exception {
        String origin = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx,material(B,B,"glacier","B 冰川原文")));
        String extra = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx,material(A,A,"garden","A 温室原文")));
        var base = legacy(origin,"写给 B 的旧稿");
        store.tx(tx -> tx.put("draft",DRAFT,base));
        store.tx(tx -> AssistantTopics.move(tx,List.of(A), origin,object().put(origin,tx.get("topic",origin).path("version").asInt()).put(extra,tx.get("topic",extra).path("version").asInt()),"OWNER"));
        String dest = moveB(origin,"");
        assertEquals(B,http(EDIT+"/versions",null).at("/0/quoteSourcePost/tweetId").asText());
        assertEquals(DRAFT,http(BASE+"/topics/"+dest+"/detail",null).at("/drafts/0/id").asText());
        assertTrue(http(BASE+"/topics/"+origin+"/detail",null).path("drafts").isEmpty());
        var req = object().put("version",1).put("instruction","优化").put("clientRequestId","opt");
        var resp = mvc.perform(post(EDIT+"/optimize").header("Origin","http://localhost").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsBytes(req)).session(session))
                .andReturn().getResponse();
        assertEquals(202,resp.getStatus());
        var t=store.tx(tx->tx.get("task",tx.page("task",1,0).get(0).path("id").asText()));
        var input=t.path("input");
        assertEquals(B,input.at("/materials/0/id").asText());
        assertEquals(B,input.path("selectedMaterialId").asText());

        http(EDIT+"/save",object().put("version",1).put("text","写给 B 的第二版"));
        store.tx(tx->{
           long tenDaysAgo=System.currentTimeMillis()-10*86400000L;
           tx.put("material",A,tx.get("material",A).put("ownerImportedAt",tenDaysAgo).put("collectedAt",tenDaysAgo));
           tx.put("membership",A,tx.get("membership",A).put("ownerImportedAt",tenDaysAgo));
           assertEquals(2,tx.versions("draft",DRAFT).size());
           long cutoff=System.currentTimeMillis()-7*86400000L;
           tx.purgeExpiredContent(cutoff, System.currentTimeMillis()-30*86400000L);
           assertNull(tx.get("material",A)); assertNotNull(tx.get("material",B)); assertNotNull(tx.get("topic",dest));
           assertNull(tx.get("topic",origin));
           assertEquals(2,tx.versions("draft",DRAFT).size());
           assertEquals(DRAFT,tx.topicRecords("draft",dest,-1,0).get(0).path("id").asText());
           for (var revision : tx.versions("draft",DRAFT)) {
               assertEquals(B, AssistantWorkflow.draftMaterialId(tx, revision));
               assertEquals(B, AssistantPublishing.draftSourcePost(tx, revision).tweetId());
           }
           tx.purgeExpiredContent(cutoff, System.currentTimeMillis()-30*86400000L);
           assertEquals(2,tx.versions("draft",DRAFT).size());
           tx.put("material",B,tx.get("material",B).put("ownerImportedAt",tenDaysAgo).put("collectedAt",tenDaysAgo));
           tx.put("membership",B,tx.get("membership",B).put("ownerImportedAt",tenDaysAgo));
           tx.purgeExpiredContent(cutoff, System.currentTimeMillis()-30*86400000L);
           assertNull(tx.get("material",B));
           assertTrue(tx.versions("draft",DRAFT).isEmpty());
           return null;
        });
    }

    @org.junit.jupiter.api.Test void comparingTheLatestVersionAfterASplitCanKeepBothEditorsWork() throws Exception {
        String origin = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx,material(A,A,"garden","温室")));
        store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx,material(B,B,"glacier","冰川")));
        moveOne(B,origin);
        store.tx(tx -> tx.put("draft",DRAFT,object().put("topicId",origin).put("materialId",B).put("text","原文")));
        http(EDIT+"/save",object().put("version",1).put("text","另一页面的新稿"));
        String destination = moveB(origin,"");
        var local = object().put("version",1).put("text","本页输入");
        assertEquals(409,postStatus(EDIT+"/save",local));
        var latest = http(EDIT+"/versions",null).get(0);
        assertEquals("另一页面的新稿",latest.path("text").asText());
        var saved = http(EDIT+"/save",local.put("version",latest.path("version").asInt()));
        assertEquals(3,saved.path("version").asInt());
        assertEquals(destination,saved.path("topicId").asText());
        var history = http(EDIT+"/versions",null);
        assertEquals("本页输入",history.at("/0/text").asText());
        assertEquals("另一页面的新稿",history.at("/1/text").asText());
    }

    @ParameterizedTest @EnumSource(Pathway.class)
    void quotedEvidenceNeverOverridesTheFrozenAuthor(Pathway pathway) throws Exception {
        var baseMat=material(A,A,"garden","本稿选中的作者转贴，正文为所引文章的预览");
        baseMat.put("quotedId",B).put("quotedUsername","glacier").put("quotedAuthorId","008");
        String origin=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,baseMat));
        var draft=legacy(origin,"围绕温室作者的转发点评");
        draft.putObject("research").putArray("materialEvidence").addObject()
            .put("id","material-"+A+"-quoted").put("url","https://x.com/glacier/status/"+B);
        store.tx(tx->tx.put("draft",DRAFT,draft));
        var source=http(EDIT+"/versions",null).at("/0/quoteSourcePost");
        assertEquals(A,source.path("tweetId").asText());
        var prior=pathway==Pathway.UPDATE_SCHEDULE?schedule(1,draft.path("text").asText(),A,source.path("url").asText()):null;
        var saved=http(EDIT+"/save",object().put("version",1).put("text",FINAL_TEXT));
        publish(pathway,saved,prior);
        assertEquals(A,captured.get(0).path("quote_tweet_id").asText());
    }

    void moveOne(String mid,String dest) {
        store.tx(tx->{
            String from=AssistantTopics.topicOf(tx,mid);
            return AssistantTopics.move(tx,List.of(mid),dest,object()
                    .put(from,tx.get("topic",from).path("version").asInt())
                    .put(dest,tx.get("topic",dest).path("version").asInt()),"OWNER");
        });
    }

    private int postStatus(String route,ObjectNode request) throws Exception {
        return mvc.perform(post(route).header("Origin","http://localhost").header("X-Requested-With","XMLHttpRequest").session(session)
               .contentType("application/json").content(json.writeValueAsBytes(request))).andReturn().getResponse().getStatus();
    }

    @ParameterizedTest @EnumSource(Pathway.class)
    void remappedAliasKeepsOriginalTopicUncertainty(Pathway pathway) throws Exception {
        String C="30303", alias="legacy-key";
        String tOld=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,material(A,A,"glacier","原帖 A 冰川")));
        String newTopic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,material(B,B,"garden","原帖 B 温室")));
        store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,material(C,C,"north","同题冰川北部")));
        store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,material(alias,A,"glacier","原帖 A 冰川")));
        moveOne(C,tOld); moveOne(alias,tOld);
        store.tx(tx->{
            var draft=object().put("topicId",tOld).put("materialId",alias).put("text",FINAL_TEXT);
            draft.putObject("research").set("snapshot",AssistantTopics.snapshot(tx,tOld,alias));
            tx.put("draft",DRAFT,draft);
            tx.put("draft","canonical-a",object().put("topicId",tOld).put("materialId",A).put("text","独立对照的另一条文案"));
            return tx.put("draft","pending-c",object().put("topicId",tOld).put("materialId",C).put("text","冰川北部的单独评论"));
        });
        var prior=pathway==Pathway.UPDATE_SCHEDULE?schedule(1,FINAL_TEXT,A,"https://x.com/glacier/status/"+A):null;
        store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,material(alias,B,"garden","新温室原文 B")));
        moveOne(alias,newTopic);
        failNextPost=true;
        var pending=object().put("version",1).put("quoteSource",true).put("clientRequestId","c-uncertain");
        assertTrue(postStatus(BASE+"/drafts/pending-c/publish-now",pending)>=500);
        assertEquals("UNCERTAIN",store.tx(tx->tx.get("publication","direct-c-uncertain")).path("status").asText());
        int canonical=postStatus(BASE+"/drafts/canonical-a/publish-now",pending.deepCopy().put("clientRequestId","control"));
        assertEquals(409,canonical);
        var saved=http(EDIT+"/save",object().put("version",1).put("text","原帖 A 更新正文"));
        assertEquals(A,saved.at("/quoteSourcePost/tweetId").asText());
        assertEquals(tOld,saved.path("topicId").asText());
        assertTrue(http(BASE+"/topics/"+newTopic+"/detail",null).path("drafts").isEmpty());
        var request = object();
        int status = switch (pathway) {
            case DIRECT -> postStatus(EDIT+"/publish-now",request.put("version",2).put("quoteSource",true).put("clientRequestId","alias"));
            case CREATE_SCHEDULE -> postStatus(QUEUE,request.put("assistantDraftId",DRAFT).put("assistantDraftVersion",2)
                    .put("text",saved.path("text").asText()).put("quoteTweetId",A).put("scheduledAtEpochMs",due).put("clientRequestId","second"));
            case UPDATE_SCHEDULE -> postStatus(EDIT+"/update-scheduled",request.put("version",2).put("operationId","upd")
                    .put("intentId","intent").put("expectedUpdatedAt",prior.path("updatedAtEpochMs").asLong()));
        };
        assertEquals(409,status);
        clock.now=Instant.ofEpochMilli(due+1000); scheduler.processDueJobsForAccount(ACCOUNT);
        assertEquals(1,captured.size());
        assertEquals(C,captured.get(0).path("quote_tweet_id").asText());
    }
    @ParameterizedTest @EnumSource(Pathway.class)
    void genericPermalinkRetainsTheKnownAuthor(Pathway pathway) throws Exception {
        restrictQuotes=true;
        var source=material(A,A,"glacier","冰川实测").put("sourceUrl","https://x.com/i/web/status/"+A);
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx, source));
        store.tx(tx->{
            var draft=object().put("topicId",topic).put("materialId",A).put("text","初稿");
            draft.putObject("research").set("snapshot",AssistantTopics.snapshot(tx,topic,A));
            return tx.put("draft",DRAFT,draft);
        });
        var prior=pathway==Pathway.UPDATE_SCHEDULE?schedule(1,"初稿",A,source.path("sourceUrl").asText()):null;
        var saved=http(EDIT+"/save",object().put("version",1).put("text",FINAL_TEXT));
        publish(pathway,saved,prior);
        assertEquals(3,captured.size());
        assertEquals(FINAL_TEXT+"\n\n（消息源：glacier）",captured.get(2).path("text").asText());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"omit","wrongHandle","batch"})
    void createLinkedScheduleUsesTheVerifiedAuthor(String mode) throws Exception {
        restrictQuotes=true;
        var source=material(A,A,"glacier","冰川实测");
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,source));
        store.tx(tx->{
            var draft=object().put("topicId",topic).put("materialId",A).put("text",FINAL_TEXT);
            draft.putObject("research").set("snapshot",AssistantTopics.snapshot(tx,topic,A));
            return tx.put("draft",DRAFT,draft);
        });
        var version=http(EDIT+"/versions",null).get(0);
        assertEquals(source.path("sourceUrl").asText(),version.at("/quoteSourcePost/url").asText());
        var request=object().put("assistantDraftId",DRAFT).put("assistantDraftVersion",1).put("quoteTweetId",A)
            .put("scheduledAtEpochMs",due).put("clientRequestId","draft-create").put("text",FINAL_TEXT);
        if(!mode.equals("omit")) request.put("quoteTweetUrl","https://x.com/garden/status/"+A);
        if (mode.equals("batch")) {
            var batch = object(); batch.putArray("items").add(request); http(QUEUE+"/batch",batch);
        } else http(QUEUE,request);
        clock.now=Instant.ofEpochMilli(due+1000); scheduler.processDueJobsForAccount(ACCOUNT);
        assertEquals(3,captured.size());
        assertEquals(FINAL_TEXT+"\n\n（消息源：glacier）",captured.get(2).path("text").asText());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"?utm_content=one,two&mode=full","?token=QQ==","?value=one-"})
    void standaloneRemovesTheEntireEmbeddedOriginalLink(String suffix) throws Exception {
        restrictQuotes=true;
        var source=material(A,A,"glacier","冰川");
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,source));
        String text=FINAL_TEXT+" "+source.path("sourceUrl").asText()+suffix;
        store.tx(tx->tx.put("draft",DRAFT,object().put("topicId",topic).put("materialId",A).put("text",text)));
        http(EDIT+"/publish-now",object().put("version",1).put("quoteSource",true).put("clientRequestId","wire"));
        assertEquals(FINAL_TEXT+"\n\n（消息源：glacier）",captured.get(2).path("text").asText());
    }

    @org.junit.jupiter.api.Test void sourceLinkRedirectReassignedToUnrelatedArticle() throws Exception {
        String entry="https://news.example/latest";
        java.util.function.BiFunction<String,String,ObjectNode> page=(url,text)->AssistantWorkflow.buildWebLinkMaterial(entry,
              new TextSourceReader.ReadResult(java.net.URI.create(entry),java.net.URI.create(url),"text/html",text,text,"body",Instant.now(),TextSourceReader.Status.READ,""),clock.millis());
        var first=page.apply("https://garden.example/report","温室土壤原文");
        var next=page.apply("https://glacier.example/survey","冰川消融新文章");
        assertNotEquals(first.path("id"),next.path("id"));
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,first));
        String mid=first.path("id").asText();
        store.tx(tx->{
            var fast=AssistantWorkflow.research(tx,object().put("mode","FAST").put("materialId",mid).put("clientRequestId","fixture-first"));
            var draft=object().put("topicId",topic).put("materialId",mid).put("text","温室土壤原稿");
            draft.set("research",fast.at("/input/research")); return tx.put("draft",DRAFT,draft);
        });
        store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,next));
        for(String operation:List.of("opt","research")){
            var task=store.tx(tx->{
                var req=object().put("draftId",DRAFT).put("version",1).put("instruction","核实旧稿").put("clientRequestId",operation);
                return operation.equals("opt")?AssistantWorkflow.optimize(tx,DRAFT,req):AssistantWorkflow.research(tx,req);
            });
            var input=task.path("input");
            assertEquals("https://garden.example/report",input.at("/materials/0/sourceUrl").asText());
            assertFalse(input.toString().contains("冰川消融新文章"));
        }
    }

    @org.junit.jupiter.api.Test void reusedHistoricalWebKeyCannotCombineTwoLandingPages() {
        String id = "link-historical";
        var page = object().put("id",id).put("sourceIdentity","USER_LINK").put("username","example")
                .put("sourceUrl","https://garden.example/report").put("text","温室报道");
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,page));
        store.tx(tx->{
            var draft = object().put("topicId",topic).put("materialId",id).put("text","温室报道初稿");
            draft.putObject("research").set("snapshot",AssistantTopics.snapshot(tx,topic,id));
            return tx.put("draft",DRAFT,draft);
        });
        store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,page.deepCopy().put("sourceUrl","https://glacier.example/survey").put("text","冰川数据")));
        store.tx(tx->{
            var request = object().put("draftId",DRAFT).put("version",1).put("instruction","核实旧稿").put("clientRequestId","res");
            assertEquals(409,assertThrows(com.xops.hub.provider.XDataProviderException.class,()->AssistantWorkflow.research(tx,request)).getStatusCode());
            assertEquals(409,assertThrows(com.xops.hub.provider.XDataProviderException.class,()->AssistantWorkflow.optimize(tx,DRAFT,request)).getStatusCode());
            return null;
        });
    }
    @ParameterizedTest @EnumSource(Pathway.class)
    void ambiguousFrozenAuthorNeverFallsBackToTheOnlyLiveAlias(Pathway pathway) throws Exception {
        String alias = "old-key";
        String origin = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, material(alias,A,"glacier","冰川")));
        String destination = store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, material(B,B,"garden","温室")));
        store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, material("30303","30303","north","北部")));
        moveOne("30303",origin);
        store.tx(tx -> tx.put("draft",DRAFT,object().put("topicId",origin).put("materialId",alias).put("text",FINAL_TEXT)));
        var old = pathway == Pathway.UPDATE_SCHEDULE ? schedule(1,FINAL_TEXT,A,"https://x.com/glacier/status/"+A) : null;
        moveOne(alias,destination);
        var draft = store.tx(tx -> {
            var row = tx.get("draft",DRAFT);
            var evidence = row.putObject("research").putArray("materialEvidence");
            evidence.addObject().put("id","material-old-key-author").put("url","https://x.com/glacier/status/"+A);
            evidence.addObject().put("id","material-old-key-author-article").put("url","https://x.com/garden/status/"+B);
            return tx.put("draft",DRAFT,row);
        });
        assertTrue(http(EDIT+"/versions",null).at("/0/quoteSourcePost").isNull());
        assertEquals(DRAFT,http(BASE+"/topics/"+origin+"/detail",null).at("/drafts/0/id").asText());
        var req = object().put("version",draft.path("version").asInt()).put("clientRequestId","ambiguous");
        int status = switch(pathway) {
            case DIRECT -> postStatus(EDIT+"/publish-now",req.put("quoteSource",true));
            case CREATE_SCHEDULE -> postStatus(QUEUE, req.put("assistantDraftId",DRAFT).put("assistantDraftVersion",2)
                    .put("quoteTweetId",A).put("scheduledAtEpochMs",due).put("text",FINAL_TEXT));
            case UPDATE_SCHEDULE -> postStatus(EDIT+"/update-scheduled",req.put("intentId","intent").put("operationId","ambiguous")
                    .put("expectedUpdatedAt",old.path("updatedAtEpochMs").asLong()));
        };
        assertEquals(pathway == Pathway.UPDATE_SCHEDULE ? 409 : 400,status);
        assertTrue(captured.isEmpty());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"Training costs fell across the three systems.","The check failed twice.","Reported by @ai."})
    void standaloneAttributionRecognizesCompleteHandles(String english) throws Exception {
        restrictQuotes=true;
        var real=material(A,A,"ai","对另一家模型的评估");
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,real));
        store.tx(tx->tx.put("draft",DRAFT,object().put("topicId",topic).put("materialId",A).put("text",english)));
        var version=http(EDIT+"/versions",null).get(0);
        assertEquals(real.path("sourceUrl").asText(),version.at("/quoteSourcePost/url").asText());
        http(EDIT+"/publish-now",object().put("version",1).put("quoteSource",true).put("clientRequestId","wire"));
        assertEquals(3,captured.size()); assertEquals(english.contains("@ai") ? english : english+"\n\n（消息源：ai）",captured.get(2).path("text").asText());
    }

}
