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
}
