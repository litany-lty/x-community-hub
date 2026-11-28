package com.xops.hub.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.provider.TwitterApiIoProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.xops.hub.assistant.AssistantStore.object;
class EditorialResearchSourceTest {
    @TempDir Path runtime;
    final ObjectMapper mapper=new ObjectMapper();
    AssistantStore store;
    String mid="10101";
    private ObjectNode config() {
        return object().put("version",1).put("provider","commandcode").put("providerManuallySelected",true).put("model","fixture")
            .put("protocol","MESSAGES").put("researchModel","research").put("researchProtocol","RESPONSES").put("contextTokens",32000);
    }
    private void setup() {
        store=new AssistantStore(runtime,mapper);
        store.tx(tx->{tx.put("meta","owner",object().put("accountId","501").put("username","elidlicom").put("bindingSource","EXPLICIT"));
            return tx.put("meta","settings",config());});
    }
    private ObjectNode material(String quoted,String author,String article,String excerpt) {
        return object().put("id",mid).put("username","alice").put("authorId","37").put("sourceIdentity","ROW_ID_VERIFIED")
             .put("kind","QUOTE").put("text","读到这篇长文").put("authorTextStatus","FULL")
             .put("quotedId",quoted).put("quotedUsername",author).put("quotedAuthorId",quoted)
             .put("quotedText","分享观察").put("quotedTextStatus","FULL").put("articleRole","QUOTED").put("articleId",article)
             .put("sourceUrl","https://x.com/alice/status/"+mid).put("articleUrl","https://x.com/i/article/"+article)
             .put("articleText",excerpt).put("articleTextStatus","PREVIEW").put("textStatus","ARTICLE_PREVIEW");
    }
    private String start(ObjectNode row) {
        store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,row));
        var task=store.tx(tx->AssistantWorkflow.research(tx,object().put("materialId",mid).put("clientRequestId","research")));
        return task.path("id").asText();
    }
    private ObjectNode reread() { return store.tx(tx->tx.get("material",mid)); }
    private ObjectNode claim(String task) {
        return store.tx(tx->tx.put("task",task,tx.get("task",task).put("attempt","fixture").put("status","RUNNING")));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void backfillOnlyUpdatesTheMaterialRevisionItRead(boolean replace) {
        setup();
        var old=material("20202","glacier","50202","冰川旧预览");
        var replacement=material("30303","garden","50303","新版温室长文预览");
        String task=start(old);
        List<String> resolverRequests=new ArrayList<>();
        var env=new MockEnvironment().withProperty(AssistantIntelligence.API_KEY_ENV,"local-fixture");
        TextModelClient model=(binding,req)->new TextModelClient.ModelResult("{\"queries\":[],\"candidateSources\":[],\"questionsToVerify\":[]}",List.of(),null,"fixture",object(),Map.of("search",object()));
        TextSourceReader reader = uri -> new TextSourceReader.ReadResult(uri,uri,"text/html","引用","已留存原文","body",Instant.now(),TextSourceReader.Status.READ,"");
        AssistantIntelligence.ArticleTextResolver resolver = (id,deadline)->{
            resolverRequests.add(id);
            if(replace) store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,replacement));
            return new TwitterApiIoProvider.ArticleBackfill("旧冰川实测的完整全文已到达",true);
        };
        var models=new AssistantIntelligence(mapper,env,model,reader,resolver);
        var worker=new AssistantWorker(store,models,mock(SourceCollector.class));
        try {worker.execute(claim(task));} finally {worker.close(); models.closeReadPool();}
        assertEquals(List.of("20202"),resolverRequests);
        var latest=reread();
        assertEquals(replace?"30303":"20202",latest.path("quotedId").asText());
        assertEquals(replace?"PREVIEW":"FULL",latest.path("articleTextStatus").asText(),store.tx(tx->tx.get("task",task)).toString());
        assertEquals(replace?"新版温室长文预览":"旧冰川实测的完整全文已到达",latest.path("articleText").asText());
        var fast=store.tx(tx->AssistantWorkflow.research(tx,object().put("mode","FAST").put("materialId",mid).put("clientRequestId","later")));
        assertEquals(!replace,fast.at("/input/research/claims").toString().contains("旧冰川"));
    }
    @Test void fastEvidenceOriginalAndPreviewEffectiveWriterInput() {
        setup();
        var mat=material("20202","glacier","50202","旧冰川预览仅节选六行").put("sourceUrl","https://x.com/alice/status/40404");
        String topic=store.tx(tx->AssistantWorkflow.ingestDirectLinkMaterial(tx,mat));
        var fast=store.tx(tx->AssistantWorkflow.research(tx,object().put("mode","FAST").put("materialId",mid).put("clientRequestId","current")));
        var input=(ObjectNode)fast.path("input");
        var evidence=input.at("/research/materialEvidence/0");
        assertEquals("https://x.com/alice/status/10101",evidence.path("url").asText());
        assertFalse(evidence.toString().contains("旧冰川预览"));
        var article=input.at("/research/materialEvidence/2");
        assertEquals("material-10101-quoted-article",article.path("id").asText());
        assertEquals("MATERIAL_PREVIEW",article.path("status").asText());
        assertEquals("https://x.com/glacier/status/20202",article.path("url").asText());
        assertEquals("@glacier",article.path("publisher").asText());
        assertEquals("MATERIAL_FULL",evidence.path("status").asText());
        String frozen=store.tx(tx->{
            var d=object().put("materialId",mid).put("topicId",topic).set("research",input.path("research"));
            return AssistantPublishing.draftSourcePost(tx,(ObjectNode)d).tweetId();
        });
        assertEquals(mid,frozen);
        var prompts=new ArrayList<String>();
        var env=new MockEnvironment().withProperty(AssistantIntelligence.API_KEY_ENV,"local-fixture");
        TextModelClient model=(binding,request)->{
            prompts.add(request.userText());
            var response=object().put("draft","需要等作者公开后核实");
            response.putArray("usedClaimIds").add("claim-1");response.putArray("caveats");response.putArray("factChanges");
            return new TextModelClient.ModelResult(response.toString(),List.of(),null,"fixture",object(),Map.of());
        };
        var models=new AssistantIntelligence(mapper,env,model,mock(TextSourceReader.class));
        try {models.run("DRAFT",config(),input);} finally {models.closeReadPool();}
        assertFalse(prompts.isEmpty());
        assertTrue(prompts.stream().allMatch(p->p.contains("旧冰川预览仅节选六行") && p.contains("被引长文节选") && !p.contains("长文全文：旧冰川预览仅节选六行") && p.contains("https://x.com/alice/status/10101") && !p.contains("https://x.com/alice/status/40404")));
    }
}
