package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.*;
import com.xops.hub.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static com.xops.hub.assistant.AssistantStore.object;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantPublicationTest {
    @Test void queueRecoversAnUnlinkedIntentAndPreservesDeletedReceipt(@TempDir Path dir) {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var store=new AssistantStore(dir.resolve("assistant"),mapper);
        store.authorize(new AuthenticatedXUser("101","elidlicom","owner","",false,Instant.now()));
        store.tx(t->t.put("draft","d",object().put("text","原稿").put("topicId","t").put("topicVersion",1).put("researchId","r")));
        var props=new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setDailyTweetPublishLimitPerUser(0);
        var oauth=mock(XOAuthService.class);
        when(oauth.getTokenCapability("101")).thenReturn(new XOAuthService.TokenCapability(true,true,true));
        var publisher=mock(XPublishClient.class);
        var jobs=new ScheduledTweetService(props,mapper,oauth,publisher,Clock.systemUTC());
        jobs.setAssistantStore(store);
        try {
            var publishing=new AssistantPublishing(store,jobs);
            long at=System.currentTimeMillis()+180_000;
            assertThatThrownBy(()->publishing.prepare("other","d",1,"intent","发文",at,List.of())).isInstanceOf(XDataProviderException.class);
            var src=publishing.prepare("101","d",1,"intent","实际待发",at,List.of());
            var job=jobs.createScheduledTweet("101","elidlicom","实际待发",at,"intent",List.of(),src);
            // Queue write succeeded, app stopped before the assistant receipt was saved.
            publishing.reconcile("101");
            var receipt=store.tx(t->t.get("publication","intent"));
            assertThat(receipt.path("jobId").asText()).isEqualTo(job.id());
            assertThat(receipt.path("text").asText()).isEqualTo("实际待发");
            assertThatThrownBy(()->publishing.prepare("101","d",1,"intent","被更换的内容",at,List.of())).isInstanceOf(XDataProviderException.class);
            store.tx(t->AssistantWorkflow.saveDraft(t,"d",object().put("version",1).put("text","修改稿")));
            assertThat(jobs.assistantJobs("101").get(0).job().text()).isEqualTo("实际待发");
            var body=object().put("version",2).put("intentId","intent").put("expectedUpdatedAt",job.updatedAtEpochMs()).put("operationId","op");
            var updated=publishing.update("101","elidlicom","d",body);
            assertThat(updated.path("draftVersion").asInt()).isEqualTo(2);
            assertThat(publishing.update("101","elidlicom","d",body).path("text").asText()).isEqualTo("修改稿");
            jobs.deleteJob("101",job.id());
            publishing.reconcile("101");
            assertThat(store.tx(t->t.get("publication","intent")).path("deleted").asBoolean()).isTrue();
            assertThatThrownBy(()->jobs.createScheduledTweet("101","elidlicom","实际待发",at,"intent",List.of(),src)).isInstanceOf(XDataProviderException.class);
            verifyNoInteractions(publisher);
        } finally {jobs.shutdown();}
    }

    @Test void publishNowWithQuoteSourcePersistsPublicationAndIsIdempotentOnReplay(@TempDir Path dir) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new AssistantStore(dir.resolve("assistant-direct"), mapper);
        store.authorize(new AuthenticatedXUser("101", "elidlicom", "owner", "", false, Instant.now()));
        store.tx(t -> {
            t.put("material", "1900012345", object().put("username", "sama").put("text", "原帖事实"));
            t.put("draft", "d-quote", object()
                    .put("text", "推理延迟下降 40% 是关键分水岭。")
                    .put("topicId", "t1")
                    .put("topicVersion", 2)
                    .put("materialId", "1900012345")
                    .put("researchId", "r1"));
            t.put("draft", "d-non-numeric", object()
                    .put("text", "无数字 ID 素材")
                    .put("topicId", "t2")
                    .put("topicVersion", 1)
                    .put("materialId", "custom-local-note")
                    .put("researchId", "r2"));
            return null;
        });

        var oauth = mock(XOAuthService.class);
        when(oauth.getValidWriteAccessToken("101", false)).thenReturn("write-token-1");
        var publisher = mock(XPublishClient.class);
        when(publisher.createTweet("write-token-1", "推理延迟下降 40% 是关键分水岭。", List.of(), "1900012345"))
                .thenReturn("2000099999");

        var publishing = new AssistantPublishing(store, mock(ScheduledTweetService.class), oauth, publisher);
        var req = object()
                .put("version", 1)
                .put("quoteSource", true)
                .put("clientRequestId", "pub-now-1");

        var pub = publishing.publishNow("101", "elidlicom", "d-quote", req);
        assertThat(pub.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(pub.path("publishedTweetId").asText()).isEqualTo("2000099999");
        assertThat(pub.path("quoteTweetId").asText()).isEqualTo("1900012345");
        assertThat(pub.path("draftId").asText()).isEqualTo("d-quote");
        assertThat(pub.path("draftVersion").asInt()).isEqualTo(1);
        assertThat(pub.path("topicId").asText()).isEqualTo("t1");
        assertThat(pub.path("publishedAt").asLong()).isGreaterThan(0L);

        // Stored in publication table for AssistantObservations closed loop
        var stored = store.tx(t -> t.get("publication", "direct-pub-now-1"));
        assertThat(stored).isNotNull();
        assertThat(stored.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(stored.path("publishedTweetId").asText()).isEqualTo("2000099999");

        // Idempotent replay with same clientRequestId does not invoke createTweet a second time
        var replayed = publishing.publishNow("101", "elidlicom", "d-quote", req);
        assertThat(replayed.path("publishedTweetId").asText()).isEqualTo("2000099999");
        verify(publisher, times(1)).createTweet("write-token-1", "推理延迟下降 40% 是关键分水岭。", List.of(), "1900012345");

        // Non-numeric material rejects quoteSource=true with IllegalArgumentException (HTTP 400)
        assertThatThrownBy(() -> publishing.publishNow("101", "elidlicom", "d-non-numeric", object()
                .put("version", 1)
                .put("quoteSource", true)
                .put("clientRequestId", "pub-now-bad")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持引用原帖");
    }
}
