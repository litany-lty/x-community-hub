package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
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
    private static void bindTestOwner(AssistantStore store) {
        store.tx(tx -> tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "101").put("username", "elidlicom")));
    }

    @Test void retryAfterDefiniteFailureKeepsOriginalQuoteWhenTopicMembershipChanges(@TempDir Path dir) {
        var store = new AssistantStore(dir, new ObjectMapper());
        bindTestOwner(store);
        store.tx(tx -> {
            var topic = object();
            topic.putArray("materialIds").add("web").add("101").add("202");
            tx.put("topic", "t", topic);
            var research = object();
            research.putArray("materialEvidence").addObject().put("url", "https://x.com/writer/status/101");
            return tx.put("draft", "d", object().put("text", "comment").put("topicId", "t").set("research", research));
        });
        var auth = mock(XOAuthService.class);
        when(auth.getValidWriteAccessToken("101", false)).thenThrow(new XDataProviderException(403, "No grant")).thenReturn("token");
        var publisher = mock(XPublishClient.class);
        when(publisher.createTweet(eq("token"), eq("comment"), eq(List.of()), anyString())).thenReturn("12345");
        var svc = new AssistantPublishing(store, null, auth, publisher);
        var req = object().put("clientRequestId", "same").put("version", 1).put("quoteSource", true);
        assertThatThrownBy(() -> svc.publishNow("101", "elidlicom", "d", req)).hasMessageContaining("No grant");
        assertThat(store.tx(tx -> tx.get("publication","direct-same")).path("requestedQuoteTweetId").asText()).isEqualTo("101");
        store.tx(tx -> {
            var topic = object();
            topic.putArray("materialIds").add("web").add("202");
            return tx.put("topic", "t", topic);
        });
        assertThat(svc.publishNow("101", "elidlicom", "d", req).path("quoteTweetId").asText()).isEqualTo("101");
        verify(publisher).createTweet("token", "comment", List.of(), "101");
        verifyNoMoreInteractions(publisher);
    }
    @Test void restartBeforeTokenCanRetryButOlderAndDispatchedSendsNeedOwnerVerification(@TempDir Path dir) {
        var store = new AssistantStore(dir, new ObjectMapper());
        bindTestOwner(store);
        store.tx(tx -> tx.put("draft", "d", object().put("text", "body")));
        var auth = mock(XOAuthService.class);
        when(auth.getValidWriteAccessToken("101", false)).thenThrow(new AssertionError("process stopped")).thenReturn("token");
        var publisher = mock(XPublishClient.class);
        when(publisher.createTweet("token", "body", List.of(), null)).thenReturn("12345");
        var service = new AssistantPublishing(store, null, auth, publisher);
        var request = object().put("version", 1).put("clientRequestId", "restart");
        assertThatThrownBy(() -> service.publishNow("101", "elidlicom", "d", request)).isInstanceOf(AssertionError.class);
        store.tx(tx -> {
            tx.put("publication", "manual-legacy", object().put("source", "MANUAL_QUOTE_REPOST").put("status", "SENDING"));
            return tx.put("publication", "direct-in-flight", object().put("source", "DIRECT_PUBLISH").put("status", "SENDING")
                    .put("postRequestDispatched", true));
        });
        var restarted = new AssistantPublishing(store, null, auth, publisher);
        restarted.recoverInterruptedPublishes();
        assertThat(store.tx(tx -> tx.get("publication","direct-restart")).path("status").asText()).isEqualTo("FAILED");
        assertThat(restarted.publishNow("101", "elidlicom", "d", request).path("publishedTweetId").asText()).isEqualTo("12345");
        for (String id : List.of("manual-legacy", "direct-in-flight")) {
            var row = store.tx(tx -> tx.get("publication", id));
            assertThat(row.path("status").asText()).isEqualTo("UNCERTAIN");
            var decision = object().put("version", row.path("version").asInt())
                    .put("published", id.startsWith("manual")).put("tweetId", "12346");
            assertThatThrownBy(() -> restarted.verifyPublication("other", id, decision)).isInstanceOf(XDataProviderException.class);
            var verified = restarted.verifyPublication("101", id, decision);
            assertThat(verified.path("status").asText()).isEqualTo(id.startsWith("manual") ? "PUBLISHED" : "FAILED");
            if (id.startsWith("manual")) assertThat(verified.path("publishedTweetId").asText()).isEqualTo("12346");
        }
        verify(publisher).createTweet("token", "body", List.of(), null);
    }

    @Test void rejectedBatchAndLegacyOrphanNeverLookScheduled(@TempDir Path dir) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new AssistantStore(dir.resolve("assistant"), mapper);
        bindTestOwner(store);
        store.tx(tx -> tx.put("draft", "d", object().put("text", "same")));
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        var oauth = mock(XOAuthService.class);
        when(oauth.getTokenCapability("101")).thenReturn(new XOAuthService.TokenCapability(true,true,true));
        var queue = new ScheduledTweetService(props, mapper, oauth, mock(XPublishClient.class), Clock.systemUTC());
        queue.setAssistantStore(store);
        try {
            var p = new AssistantPublishing(store, queue);
            long due = System.currentTimeMillis() + 120000;
            var drafts = List.of("first", "second").stream().map(id -> new ScheduledTweetService.BatchTweetDraftInput(
                    "same", due, id, List.of(), p.prepare("101", "d", 1, id, "same", due, List.of()))).toList();
            assertThatThrownBy(() -> queue.createBatchScheduledTweets("101", "elidlicom", drafts))
                    .isInstanceOf(XDataProviderException.class);
            assertThat(queue.assistantJobs("101")).isEmpty();
            assertThat(store.tx(tx -> tx.count("publication")).longValue()).isZero();
            store.tx(tx -> tx.put("publication", "legacy", object().put("status", "PENDING").put("draftId", "d")));
            p.reconcile("101");
            assertThat(store.tx(tx -> tx.get("publication", "legacy")).path("status").asText()).isEqualTo("FAILED");
        } finally { queue.shutdown(); }
    }
    @Test void queueRecoversAnUnlinkedIntentAndPreservesDeletedReceipt(@TempDir Path dir) {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var store=new AssistantStore(dir.resolve("assistant"),mapper);
        bindTestOwner(store);
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

    @Test void queueReceiptUsesTheScheduledVersionSkillAndTheTextActuallyPublished(@TempDir Path dir) throws Exception {
        var mapper = new ObjectMapper();
        var store = new AssistantStore(dir, mapper);
        bindTestOwner(store);
        var v1 = object().put("text", "original").put("materialId", "123").put("skillId", "BREAKING_FLASH");
        store.tx(tx -> tx.put("draft", "d", v1));
        var service = new AssistantPublishing(store, null);
        var source1 = service.prepare("101", "d", 1, "intent", "original", 100, List.of(), true, null);
        var scheduled = object().put("id", "job").put("status", "SCHEDULED").put("text", "original").put("updatedAtEpochMs", 10);
        scheduled.set("assistantSource", mapper.valueToTree(source1));
        service.record(mapper.treeToValue(scheduled, com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob.class));
        store.tx(tx -> tx.put("draft", "d", v1.deepCopy().put("skillId", "ACTIONABLE_LIST").put("text", "changed")));
        scheduled.put("text", "changed").put("status", "PUBLISHED").put("updatedAtEpochMs", 20).put("quoteTweetId", "123");
        scheduled.withObject("/assistantSource").put("draftVersion", 2);
        scheduled.putObject("publicationReceipt").put("tweetId", "222").put("text", "changed with fallback")
                .put("mode", "STANDALONE_FALLBACK");
        service.record(mapper.treeToValue(scheduled, com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob.class));
        var record = store.tx(tx -> tx.get("publication", "intent"));
        assertThat(record.path("draftVersion").asInt()).isEqualTo(2);
        assertThat(record.path("skillId").asText()).isEqualTo("ACTIONABLE_LIST");
        assertThat(record.path("publicationMode").asText()).isEqualTo("STANDALONE_FALLBACK");
        assertThat(record.path("requestedQuoteTweetId").asText()).isEqualTo("123");
        assertThat(record.has("quoteTweetId")).isFalse();
        assertThat(record.path("text").asText()).isEqualTo("changed with fallback");
        assertThat(record.path("requestedText").asText()).isEqualTo("changed");
    }

    @Test void concurrentDirectRequestClaimsBeforeLoadingTokenAndCanReplayAfterDraftChanges(@TempDir Path dir) throws Exception {
        var store = new AssistantStore(dir, new ObjectMapper());
        bindTestOwner(store);
        store.tx(tx -> tx.put("draft", "draft", object().put("text", "first")));
        var tokenRequested = new java.util.concurrent.CountDownLatch(1);
        var tokenReady = new java.util.concurrent.CountDownLatch(1);
        var oauth = mock(XOAuthService.class);
        when(oauth.getValidWriteAccessToken("101", false)).thenAnswer(call -> {
            tokenRequested.countDown();
            if (!tokenReady.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("token latch timed out");
            return "token";
        });
        var publisher = mock(XPublishClient.class);
        when(publisher.createTweet("token", "first", List.of(), null)).thenReturn("123");
        var service = new AssistantPublishing(store, null, oauth, publisher);
        var body = object().put("version", 1).put("clientRequestId", "same");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.publishNow("101", "elidlicom", "draft", body));
            assertThat(tokenRequested.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.publishNow("101", "elidlicom", "draft", body))
                    .isInstanceOf(XDataProviderException.class).hasMessageContaining("回执待确认");
            var afterReload = body.deepCopy().put("clientRequestId", "new-browser-key");
            assertThatThrownBy(() -> service.publishNow("101", "elidlicom", "draft", afterReload))
                    .isInstanceOf(XDataProviderException.class).hasMessageContaining("回执待确认");
            tokenReady.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).path("publishedTweetId").asText()).isEqualTo("123");
            assertThat(service.publishNow("101", "elidlicom", "draft", afterReload).path("publishedTweetId").asText()).isEqualTo("123");
            store.tx(tx -> tx.put("draft", "draft", object().put("text", "second")));
            assertThat(service.publishNow("101", "elidlicom", "draft", body).path("publishedTweetId").asText()).isEqualTo("123");
            verify(publisher, times(1)).createTweet("token", "first", List.of(), null);
            verify(oauth, times(1)).getValidWriteAccessToken("101", false);
        } finally {
            tokenReady.countDown();
            executor.shutdownNow();
        }
    }

    @Test void directPublicationRetriesDefiniteAdmissionFailureAndHoldsUnknownDelivery(@TempDir Path dir) {
        var store = new AssistantStore(dir, new ObjectMapper());
        bindTestOwner(store);
        store.tx(tx -> tx.put("draft", "d", object().put("text", "text")));
        var oauth = mock(XOAuthService.class);
        when(oauth.getValidWriteAccessToken("101", false)).thenReturn("token");
        var publisher = mock(XPublishClient.class);
        when(publisher.createTweet("token", "text", List.of(), null))
                .thenThrow(new XPublishClient.XPublishException(503, "admission rejected", false))
                .thenThrow(new XPublishClient.XPublishException(504, "delivery unknown", true));
        var service = new AssistantPublishing(store, null, oauth, publisher);
        var body = object().put("version", 1).put("clientRequestId", "try");
        assertThatThrownBy(() -> service.publishNow("101", "owner", "d", body)).hasMessageContaining("admission");
        assertThat(store.tx(tx -> tx.get("publication", "direct-try")).path("status").asText()).isEqualTo("FAILED");
        assertThatThrownBy(() -> service.publishNow("101", "owner", "d", body)).hasMessageContaining("delivery");
        assertThat(store.tx(tx -> tx.get("publication", "direct-try")).path("status").asText()).isEqualTo("UNCERTAIN");
        assertThatThrownBy(() -> service.publishNow("101", "owner", "d", body)).hasMessageContaining("回执待确认");
        assertThatThrownBy(() -> service.publishNow("101", "owner", "d", body.deepCopy().put("clientRequestId", "new-key")))
                .isInstanceOf(XDataProviderException.class).hasMessageContaining("回执待确认");
        verify(publisher, times(2)).createTweet("token", "text", List.of(), null);
    }

    @Test void publishNowWithQuoteSourcePersistsPublicationAndIsIdempotentOnReplay(@TempDir Path dir) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new AssistantStore(dir.resolve("assistant-direct"), mapper);
        bindTestOwner(store);
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
