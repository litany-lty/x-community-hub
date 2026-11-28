package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.xops.hub.assistant.AssistantStore.object;
import static com.xops.hub.assistant.AutoRepostService.AutoPublishMode.REWRITE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewritePublicationRegressionTest {
    @TempDir Path dir;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-27T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final ObjectMapper mapper = new ObjectMapper();
    private final PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
    private final XPublishClient publisher = mock(XPublishClient.class);
    private final XOAuthService auth = mock(XOAuthService.class);
    private AssistantStore store;
    private AutoRepostService service;

    @BeforeEach void setup() {
        store = new AssistantStore(dir, mapper);
        put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "42").put("username", "owner"));
        put("source", "7", object().put("authorId", "7").put("username", "source").put("status", "ACTIVE").put("autoRewriteEnabled", true));
        when(auth.getValidWriteAccessToken("42", false)).thenReturn("local-token");
        service = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, publisher, clock);
    }

    @AfterEach void stop() { service.shutdown(); }

    private void put(String kind, String id, ObjectNode row) { store.tx(tx -> tx.put(kind, id, row)); }
    private ObjectNode get(String kind, String id) { return store.tx(tx -> tx.get(kind, id)); }
    private ObjectNode source() { return get("source", "7"); }
    private String patrol() { return service.processAuthorRewriteOnce(source(), "42", "owner"); }

    private void material(String id, String author, int views) {
        put("material", id, object().put("authorId", author).put("username", "source").put("kind", "POST")
                .put("text", "Four engineering teams timed the entire release process before and after changing their review policy. Case " + id)
                .put("views", views).put("publishedAt", clock.millis() - Duration.ofHours(1).toMillis()));
    }

    private void failedRewrite() {
        put("auto_rewrite", "101", object().put("authorId", "7").put("materialId", "101").put("topicId", "team")
                .put("ownerAccountId", "42").put("rewrittenText", "Independent draft").put("status", "FAILED"));
    }

    private void revoke(String change) {
        switch (change) {
            case "assistant" -> store.tx(tx -> tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", false)));
            case "source" -> put("source", "7", source().put("status", "PAUSED"));
            case "rewrite" -> put("source", "7", source().put("autoRewriteEnabled", false));
            default -> throw new AssertionError(change);
        }
    }

    private PiAgentViralJudge.RewriteJudgment approved(String body) {
        return new PiAgentViralJudge.RewriteJudgment(true, 91, "", "", "独立成稿", body);
    }

    @ParameterizedTest @ValueSource(strings = {"source", "assistant", "rewrite"})
    void changesWhileWritingBlockTheAutomaticSend(String change) {
        material("101", "7", 20_000);
        when(judge.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
            revoke(change);
            return approved("Independent draft");
        });
        assertEquals("", patrol());
        assertEquals("FAILED", get("auto_rewrite", "101").path("status").asText());
        verifyNoInteractions(auth, publisher);
    }

    @ParameterizedTest @ValueSource(strings = {"source", "assistant", "rewrite"})
    void savedTextStillRequiresCurrentAuthorization(String change) {
        failedRewrite();
        revoke(change);
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry("101", REWRITE)).getStatusCode());
        assertEquals("FAILED", get("auto_rewrite", "101").path("status").asText());
        verifyNoInteractions(auth, judge, publisher);
    }

    @Test void revocationInTheHttpQueueStopsTheRequest() throws Exception {
        failedRewrite();
        var properties = new XOpsProperties();
        properties.setOfficialBaseUrl("http://127.0.0.1:1/2");
        var pool = new SharedApiExecutor(properties) {
            @Override public <T> T execute(Callable<T> action) {
                revoke("rewrite");
                return super.execute(action);
            }
        };
        var budget = mock(UpstreamBudgetGuard.class);
        var client = new XPublishClient(properties, mapper, pool, budget);
        var http = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", http);
        var queued = new AutoRepostService(store, mock(SourceCollector.class), judge, auth, client, clock);
        try {
            queued.retry("101", REWRITE);
            ((ExecutorService) ReflectionTestUtils.getField(queued, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals("FAILED", get("auto_rewrite", "101").path("status").asText());
            verifyNoInteractions(budget, http);
        } finally { queued.shutdown(); pool.shutdown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER_REVIEW_REQUIRED", "FAILED", "SKIPPED_COOLDOWN"})
    void theNewOwnerCannotReclaimThePreviousOwnersRecord(String status) {
        material("101", "7", 20_000);
        put("auto_rewrite", "101", object().put("authorId", "7").put("ownerAccountId", "old").put("status", status));
        ObjectNode original = get("auto_rewrite", "101");
        assertEquals("", patrol());
        assertEquals(original, get("auto_rewrite", "101"));
        assertThrows(XDataProviderException.class, () -> service.retry("101", REWRITE));
        verifyNoInteractions(judge, publisher, auth);
    }

    private void otherRewrite(String status) {
        put("auto_rewrite", "202", object().put("materialId", "202").put("topicId", "team").put("status", status)
                .put("dispatchedAt", clock.millis() - Duration.ofHours(3).toMillis()));
    }

    @ParameterizedTest @ValueSource(strings = {"UNCERTAIN", "PUBLISHED", "SENDING"})
    void retryRespectsAProtectedRewriteOfTheSameTopic(String status) {
        failedRewrite();
        otherRewrite(status);
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.retry("101", REWRITE)).getStatusCode());
        verifyNoInteractions(judge, publisher, auth);
    }

    @Test void finalClaimDetectsAnotherReceiptCreatedDuringTheRetry() throws Exception {
        failedRewrite();
        when(auth.getValidWriteAccessToken("42", false)).thenAnswer(call -> { otherRewrite("UNCERTAIN"); return "local-token"; });
        service.retry("101", REWRITE);
        ((ExecutorService) ReflectionTestUtils.getField(service, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertEquals("SKIPPED_DUPLICATE", get("auto_rewrite", "101").path("status").asText());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest @ValueSource(strings = {"copy", "empty-wrapper"})
    void legacySavedTextGetsTheSameFinalValidation(String outcome) throws Exception {
        material("101", "7", 20_000);
        failedRewrite();
        String saved = outcome.equals("copy") ? get("material", "101").path("text").asText() : "{\"rewrittenText\":\"\"}";
        put("auto_rewrite", "101", get("auto_rewrite", "101").put("rewrittenText", saved));
        service.retry("101", REWRITE);
        ((ExecutorService) ReflectionTestUtils.getField(service, "executor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertEquals("JUDGE_FAILED", get("auto_rewrite", "101").path("status").asText());
        assertFalse(get("auto_rewrite", "101").has("rewrittenText"));
        verifyNoInteractions(auth, publisher);
    }

    @ParameterizedTest @ValueSource(strings = {"copy", "formatting", "minor-edit", "empty-json", "malformed", "polished-copy", "valid"})
    void realWritingPipelineChecksTheActualFinalText(String outcome) {
        material("101", "7", 20_000);
        String original = get("material", "101").path("text").asText();
        String independent = "上线速度卡在评审，而这笔等待时间经常没进工程团队的工时账。";
        boolean polish = outcome.equals("polished-copy");
        store.tx(tx -> {
            var settings = AssistantWorkflow.settings(tx);
            settings.withObject("/moduleConfigs/repost").put("provider", "openai").put("model", "fixture");
            return tx.put("meta", "settings", settings.put("postPolishEnabled", polish));
        });
        var pipeline = new PiAgentViralJudge(mapper, new MockEnvironment(), (cmd, in, timeout) -> { throw new AssertionError("unexpected CLI"); }) {
            int drafts;
            @Override String runApiQuoteCall(String provider, String model, String prompt, String request, boolean json) {
                if (json) return "{\"suitableForRewrite\":true,\"suitabilityScore\":91}";
                drafts++;
                return switch (outcome) {
                    case "copy" -> original;
                    case "formatting" -> original.toUpperCase().replace(" ", "\n");
                    case "minor-edit" -> original.replace("four", "some").replace("timed", "tested");
                    case "empty-json" -> "{\"rewrittenText\":\"\"}";
                    case "malformed" -> "{\"rewrittenText\":";
                    case "polished-copy" -> drafts == 1 ? independent : original;
                    case "valid" -> "{\"rewrittenText\":\"" + independent + "\"}";
                    default -> throw new AssertionError(outcome);
                };
            }
            @Override List<Path> resolveActiveSkillFiles() { return List.of(); }
        };
        pipeline.setHttpTransportForTest((a, b, c, d) -> { throw new AssertionError("unexpected HTTP"); }, null);
        when(publisher.createTweet("local-token", independent, List.of(), null)).thenReturn("9001");
        var real = new AutoRepostService(store, mock(SourceCollector.class), pipeline, auth, publisher, clock);
        try {
            real.processAuthorRewriteOnce(source(), "42", "owner");
            if (outcome.equals("valid")) {
                assertEquals("9001", get("publication", "auto-rewrite-101").path("publishedTweetId").asText());
                verify(publisher).createTweet("local-token", independent, List.of(), null);
            } else {
                assertEquals("JUDGE_FAILED", get("auto_rewrite", "101").path("status").asText());
                assertNull(get("publication", "auto-rewrite-101"));
                verifyNoInteractions(auth, publisher);
            }
        } finally { real.shutdown(); }
    }

    @Test void definitiveFailuresLeaveRoomForTheFourthCandidate() {
        for (int n = 1; n <= 4; n++) material("10" + n, "7", 90_000 - n * 10_000);
        when(judge.evaluateAndWriteRewrite(any())).thenAnswer(call -> {
            PiAgentViralJudge.RewriteInput input = call.getArgument(0);
            String source = input.sourceText();
            return approved("Independent draft " + source.substring(source.length() - 3));
        });
        when(publisher.createTweet(eq("local-token"), anyString(), eq(List.of()), isNull())).thenAnswer(call -> {
            if ("Independent draft 104".equals(call.getArgument(1))) return "9002";
            throw new XPublishClient.XPublishException(400, "Definitive refusal", false);
        });
        assertEquals("", patrol());
        assertEquals("104", patrol());
        assertEquals("", patrol());
        verify(judge, times(4)).evaluateAndWriteRewrite(any());
        for (String id : List.of("101", "102", "103")) {
            assertEquals("FAILED", get("auto_rewrite", id).path("status").asText());
            verify(publisher, times(1)).createTweet("local-token", "Independent draft " + id, List.of(), null);
        }
        assertEquals("PUBLISHED", get("auto_rewrite", "104").path("status").asText());
    }

    @Test void explicitMaterialMustBelongToTheSelectedSourceEvenWhenTheHandleMatches() {
        material("101", "7", 40_000);
        material("202", "8", 50_000);
        assertEquals(404, assertThrows(XDataProviderException.class,
                () -> service.previewRewrite("7", object().put("materialId", "202"))).getStatusCode());
        verifyNoInteractions(judge, publisher);
    }

    @Test void aCurrentModelRejectionTakesPrecedenceOverAManualPreviewAndChangesWithTheSource() {
        material("101", "7", 40_000);
        when(judge.evaluateAndWriteRewrite(any(), eq(true))).thenReturn(approved("Manual preview"));
        service.previewRewrite("7", object().put("materialId", "101"));
        when(judge.evaluateAndWriteRewrite(any())).thenReturn(new PiAgentViralJudge.RewriteJudgment(false, 28, "", "", "与账号主题不符", ""));
        patrol();
        var timeline = service.authorTimeline("7", object());
        var result = timeline.path("items").get(0).path("rewrite");
        assertEquals(0, timeline.path("summary").path("wouldRewriteCount").asInt());
        assertFalse(result.path("candidate").asBoolean());
        assertTrue(result.path("evaluated").asBoolean());
        assertEquals("SKIPPED", result.path("status").asText());
        assertEquals("与账号主题不符", result.path("reason").asText());
        var reopened = service.previewRewrite("7", object().put("materialId", "101")).path("rewrite");
        assertFalse(reopened.path("candidate").asBoolean());
        assertEquals("与账号主题不符", reopened.path("ineligibleReason").asText());
        patrol();
        verify(judge, times(1)).evaluateAndWriteRewrite(any());
        put("material", "101", get("material", "101").put("text", "The new source corrects the findings and explains a different engineering result."));
        assertEquals("READY", service.authorTimeline("7", object()).path("items").get(0).path("rewrite").path("status").asText());
        patrol();
        verify(judge, times(2)).evaluateAndWriteRewrite(any());
        verifyNoInteractions(publisher);
    }
}
