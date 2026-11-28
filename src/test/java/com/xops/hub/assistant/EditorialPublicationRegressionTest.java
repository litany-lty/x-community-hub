package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.XOAuthService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditorialPublicationRegressionTest {
    @TempDir Path dir;
    private AssistantStore store;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-27T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final XOAuthService oauth = mock(XOAuthService.class);
    private final XPublishClient publisher = mock(XPublishClient.class);
    private final PiAgentViralJudge judge = mock(PiAgentViralJudge.class);

    @BeforeEach void bindOwner() {
        store = new AssistantStore(dir, new ObjectMapper());
        store.tx(tx -> tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT")
                .put("accountId", "101").put("username", "elidlicom")));
        when(oauth.getValidWriteAccessToken("101", false)).thenReturn("test-token");
    }

    private AutoRepostService auto() {
        return new AutoRepostService(store, mock(SourceCollector.class), judge, oauth, publisher, clock);
    }

    private AssistantPublishing publishing() {
        return new AssistantPublishing(store, mock(ScheduledTweetService.class), oauth, publisher);
    }

    @Test void schedulingAndDirectPublicationUseTheSamePostAfterPartialTopicSplit() {
        store.tx(tx -> {
            String first = AssistantWorkflow.ingestDirectLinkMaterial(tx, object().put("id", "101").put("text", "first"));
            String second = AssistantWorkflow.ingestDirectLinkMaterial(tx, object().put("id", "202").put("text", "second"));
            AssistantTopics.move(tx, List.of("202"), first, object().put(first, 1).put(second, 1), "OWNER");
            tx.put("draft", "from-b", object().put("topicId", first).put("materialId", "202").put("text", "draft about B"));
            return AssistantTopics.move(tx, List.of("202"), "split",
                    object().put(first, tx.get("topic", first).path("version").intValue()), "OWNER");
        });
        var service = publishing();
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.prepare("101", "from-b", 1,
                "wrong", "draft about B", 123, List.of(), true, "101")).getStatusCode());
        assertEquals("202", service.prepare("101", "from-b", 1, "right", "draft about B", 123,
                List.of(), true, null).quoteTweetId());
        when(publisher.createTweet("test-token", "draft about B", List.of(), "202")).thenReturn("99001");
        assertEquals("202", service.publishNow("101", "elidlicom", "from-b", object().put("version", 1)
                .put("clientRequestId", "direct").put("quoteSource", true)).path("quoteTweetId").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aMissingReceiptBlocksTheOtherQuoteEntry(boolean directFirst) {
        store.tx(tx -> tx.put("material", "202", object().put("authorId", "7").put("username", "writer")));
        store.tx(tx -> tx.put("draft", "d", object().put("materialId", "202").put("text", "comment")));
        var service = publishing();
        var auto = auto();
        try {
            when(publisher.createTweet("test-token", "comment", List.of(), "202"))
                    .thenThrow(new XPublishClient.XPublishException(502, "Lost response", true));
            Runnable direct = () -> service.publishNow("101", "elidlicom", "d",
                    object().put("version", 1).put("clientRequestId", "direct").put("quoteSource", true));
            Runnable manual = () -> auto.publishManualQuote("101", "elidlicom", "writer",
                    object().put("materialId", "202").put("clientRequestId", "manual").put("commentText", "comment"));
            Runnable first = directFirst ? direct : manual;
            Runnable second = directFirst ? manual : direct;
            assertThrows(XPublishClient.XPublishException.class, first::run);
            assertEquals(409, assertThrows(XDataProviderException.class, second::run).getStatusCode());
            verify(publisher, times(1)).createTweet("test-token", "comment", List.of(), "202");
        } finally {
            auto.shutdown();
        }
    }

    @Test void replayOfAnExistingSchedulePreservesItsAcceptedQuoteTarget() {
        store.tx(tx -> {
            tx.put("material", "legacy-link", object().put("sourceUrl", "https://x.com/writer/status/101"));
            return tx.put("draft", "d", object().put("materialId", "legacy-link").put("text", "comment"));
        });
        var queue = mock(ScheduledTweetService.class);
        var service = new AssistantPublishing(store, queue);
        var intent = service.prepare("101", "d", 1, "scheduled", "comment", 123, List.of(), true, null);
        var job = mock(ScheduledTweetJob.class);
        when(job.id()).thenReturn("job");
        when(queue.assistantJobs("101")).thenReturn(List.of(new ScheduledTweetService.AssistantQueueEntry(job, false)));
        store.tx(tx -> {
            tx.put("publication", "scheduled", object().put("draftId", "d").put("draftVersion", 1)
                    .put("jobId", "job").put("requestedQuoteTweetId", intent.quoteTweetId()).put("requestDigest", intent.requestDigest()));
            return tx.put("material", "legacy-link", object().put("sourceUrl", "https://x.com/writer/status/202"));
        });
        assertEquals("101", service.prepare("101", "d", 1, "scheduled", "comment", 123, List.of(), true, null).quoteTweetId());
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.prepare("101", "d", 1,
                "scheduled", "comment", 123, List.of(), true, "202")).getStatusCode());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aConfirmedReceiptIsRecoveredWithoutSendingAgain(boolean restart) throws Exception {
        store.tx(tx -> tx.put("draft", "d", object().put("text", "body")));
        when(publisher.createTweet("test-token", "body", List.of(), null)).thenReturn("991199");
        var service = publishing();
        var request = object().put("version", 1).put("clientRequestId", "disk-fault");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("assistant.sqlite"));
             var sql = connection.createStatement()) {
            sql.execute("""
                CREATE TRIGGER receipt_failure BEFORE INSERT ON revisions
                WHEN new.kind='publication' AND json_extract(new.data,'$.status')='PUBLISHED'
                BEGIN SELECT RAISE(ABORT,'test receipt error'); END
                """);
            assertEquals(503, assertThrows(XDataProviderException.class,
                    () -> service.publishNow("101", "elidlicom", "d", request)).getStatusCode());
            var journal = store.tx(tx -> tx.get("publication", "direct-disk-fault"));
            assertEquals("991199", journal.path("acceptedReceipt").path("publishedTweetId").asText());
            sql.execute("DROP TRIGGER receipt_failure");
        }
        var reader = restart ? publishing() : service;
        if (restart) reader.recoverInterruptedPublishes();
        reader.reconcile("101");
        var recovered = reader.publishNow("101", "elidlicom", "d", request);
        assertEquals("PUBLISHED", recovered.path("status").asText());
        assertEquals("991199", recovered.path("publishedTweetId").asText());
        assertFalse(recovered.has("acceptedReceipt"));
        verify(publisher, times(1)).createTweet("test-token", "body", List.of(), null);
    }

    @Test void openingPreviewsUsesTheCurrentMaterialForBothModels() {
        String previous = "Earlier reports indicated one hundred items were affected across several regions in this investigation.";
        String correction = "The corrected engineering report confirms ten affected items. The earlier estimate has been withdrawn.";
        ObjectNode material = object().put("id", "790").put("authorId", "7").put("username", "writer")
                .put("kind", "POST").put("textStatus", "TEXT_ONLY").put("text", previous)
                .put("publishedAt", clock.millis() - 3600000).put("views", 40000).put("likes", 340);
        store.tx(tx -> {
            tx.put("source", "7", object().put("authorId", "7").put("username", "writer").put("status", "ACTIVE")
                    .put("autoRewriteEnabled", true).put("autoRepostEnabled", true));
            AssistantWorkflow.ingest(tx, material);
            return null;
        });
        when(judge.evaluate(any())).thenReturn(new PiAgentViralJudge.ViralJudgment(true, 99, true,
                90, 90, 90, 90, "ROUTINE", "summary", "ok", "comment"));
        var first = new PiAgentViralJudge.RewriteJudgment(true, 93, "", "", "", "Initial assessment of the figures.");
        var second = new PiAgentViralJudge.RewriteJudgment(true, 93, "", "", "", "Updated account drawing on the revised total.");
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(first, second);
        var service = auto();
        try {
            var request = object().put("materialId", "790");
            assertEquals(previous, service.previewRepost("7", request).at("/material/text").asText());
            assertEquals(previous, service.previewRewrite("7", request).at("/material/text").asText());
            store.tx(tx -> { AssistantWorkflow.ingest(tx, material.deepCopy().put("text", correction)); return null; });
            assertEquals(correction, service.previewRepost("7", request).at("/material/text").asText());
            var rewritten = service.previewRewrite("7", request);
            assertEquals(correction, rewritten.at("/material/text").asText());
            assertEquals(second.rewrittenText(), rewritten.at("/rewrite/rewrittenText").asText());
            verify(judge, times(2)).evaluate(any());
            verify(judge, times(2)).evaluateAndWriteRewrite(any(), eq(true));
        } finally {
            service.shutdown();
        }
    }
}
