package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeStageModelRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void geminiClientUsesInteractionsV1WithThinkingSearchAndLegacyModelUpgrade() throws Exception {
        var interactionReply = mapper.readTree("""
                {
                  "id": "inter-101",
                  "steps": [
                    {
                      "type": "google_search",
                      "id": "gs-1",
                      "results": [
                        {"url": "https://ai.google.dev/gemini-api/docs", "title": "Gemini API Docs", "rendered_content": "Official docs"}
                      ]
                    },
                    {
                      "type": "model_output",
                      "content": [
                        {"type": "thought", "text": "internal thought"},
                        {
                          "type": "text",
                          "text": "{\\"ok\\":true}",
                          "citations": [{"url": "https://blog.google/technology/ai/", "title": "Google AI Blog"}]
                        }
                      ]
                    }
                  ],
                  "usage": {"total_input_tokens": 120, "total_output_tokens": 45, "total_thought_tokens": 30}
                }
                """);

        var calls = new AtomicInteger();
        var client = new GeminiTextModelClient(mapper, (uri, headers, body, timeout) -> {
            calls.incrementAndGet();
            assertThat(uri).isEqualTo(URI.create("https://generativelanguage.googleapis.com/v1/interactions"));
            assertThat(headers)
                    .containsEntry("x-goog-api-key", "gem-secret-key")
                    .containsEntry("Api-Revision", GeminiTextModelClient.API_REVISION);
            assertThat(body.path("model").asText()).isEqualTo("gemini-4-carbon");
            assertThat(body.path("system_instruction").asText()).startsWith("sys prompt").contains("JSON object");
            assertThat(body.path("generation_config").path("thinking_level").asText()).isEqualTo("high");
            assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("google_search");
            return new CommandCodeTextModelClient.TransportResponse(200, interactionReply, Map.of());
        }, ref -> {
            assertThat(ref).isEqualTo(AssistantConnection.GOOGLE_KEY_ENV);
            return "gem-secret-key";
        });

        var binding = new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection("google", URI.create(GeminiTextModelClient.DEFAULT_BASE_URL), AssistantConnection.GOOGLE_KEY_ENV, 1L),
                "gemini-4-carbon high", TextModelClient.Protocol.RESPONSES, 1_000_000, Duration.ofSeconds(60), 2048, "test"
        );
        var res = client.complete(binding, new TextModelClient.TextRequest("sys prompt", "hello", true, true));
        assertThat(res.text()).isEqualTo("{\"ok\":true}");
        assertThat(res.requestId()).isEqualTo("inter-101");
        assertThat(res.searchResults()).extracting(TextModelClient.SearchResult::url)
                .containsExactly(
                        URI.create("https://ai.google.dev/gemini-api/docs"),
                        URI.create("https://blog.google/technology/ai/")
                );
        assertThat(calls).hasValue(1);

        // Legacy Gemini 1.5/2.0/2.5 identifiers automatically upgrade to gemini-4-hydrogen
        assertThat(GeminiTextModelClient.parseModelSpec("gemini-2.5-pro").modelId())
                .isEqualTo(GeminiTextModelClient.FAST_MODEL);
    }

    @Test
    void deepSeekClientRoutesSearchFetchAndChatCompletionsWith128CharLimit() throws Exception {
        String longSnippet = "官方网页搜索正文摘要，包含完整参数约束（query 不超过 128 字符，need_content 返回页面正文），长度超过 80 字符可直接命中缓存无需二次抓取。".repeat(2);
        var searchReply = mapper.createObjectNode();
        searchReply.putArray("results").addObject()
                .put("url", "https://api-docs.deepseek.com/zh-cn/guides/search/")
                .put("title", "DeepSeek 网页搜索")
                .put("content", longSnippet)
                .put("summary", "简短摘要")
                .put("site_name", "DeepSeek Docs");
        var fetchReply = mapper.readTree("""
                {"url": "https://example.org/deep-dive", "title": "Deep Dive", "content": "Fetched markdown body"}
                """);
        var chatReply = mapper.readTree("""
                {"id": "ds-chat-1", "choices": [{"message": {"content": "{\\"synthesized\\":true}"}}],
                 "usage": {"prompt_tokens": 50, "completion_tokens": 20}}
                """);

        var calls = new AtomicInteger();
        var client = new DeepSeekTextModelClient(mapper, (uri, headers, body, timeout) -> {
            assertThat(headers).containsEntry("Authorization", "Bearer ds-secret");
            int step = calls.incrementAndGet();
            if (step == 1) {
                assertThat(uri).isEqualTo(URI.create("https://api.deepseek.com/v1/tools/search"));
                assertThat(body.path("need_content").asBoolean()).isTrue();
                String q = body.path("query").asText();
                assertThat(q.codePointCount(0, q.length())).isLessThanOrEqualTo(128);
                return new CommandCodeTextModelClient.TransportResponse(200, searchReply, Map.of("x-request-id", "ds-s1"));
            }
            if (step == 2) {
                assertThat(uri).isEqualTo(URI.create("https://api.deepseek.com/v1/tools/fetch"));
                assertThat(body.path("url").asText()).isEqualTo("https://example.org/deep-dive");
                return new CommandCodeTextModelClient.TransportResponse(200, fetchReply, Map.of());
            }
            assertThat(uri).isEqualTo(URI.create("https://api.deepseek.com/v1/chat/completions"));
            assertThat(body.path("model").asText()).isEqualTo("deepseek-chat");
            return new CommandCodeTextModelClient.TransportResponse(200, chatReply, Map.of());
        }, ref -> "ds-secret");

        var binding = new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection("deepseek", URI.create(DeepSeekTextModelClient.DEFAULT_BASE_URL), AssistantConnection.DEEPSEEK_KEY_ENV, 1L),
                "deepseek-chat", TextModelClient.Protocol.CHAT_COMPLETIONS, 128_000, Duration.ofSeconds(45), 2048, "test"
        );

        String longQuery = "搜".repeat(160);
        var searchRes = client.complete(binding, new TextModelClient.TextRequest("sys", "user question", false, true, longQuery));
        assertThat(searchRes.searchResults()).singleElement().satisfies(item ->
                assertThat(item.url()).isEqualTo(URI.create("https://api-docs.deepseek.com/zh-cn/guides/search/")));

        // Cached content (>= 80 chars) from search hit does NOT require an extra /tools/fetch HTTP call
        var cachedHit = client.readCachedOrFetch(URI.create("https://api-docs.deepseek.com/zh-cn/guides/search/"), Duration.ofSeconds(10));
        assertThat(cachedHit).isNotNull();
        assertThat(cachedHit.content()).isEqualTo(longSnippet);
        assertThat(calls).hasValue(1);

        // Uncached URL falls back to POST /v1/tools/fetch
        var fetched = client.readCachedOrFetch(URI.create("https://example.org/deep-dive"), Duration.ofSeconds(10));
        assertThat(fetched).isNotNull();
        assertThat(fetched.content()).isEqualTo("Fetched markdown body");
        assertThat(calls).hasValue(2);

        var chatRes = client.complete(binding, new TextModelClient.TextRequest("sys", "synthesize", true, false));
        assertThat(chatRes.text()).isEqualTo("{\"synthesized\":true}");
        assertThat(calls).hasValue(3);
    }

    @Test
    void routesThreeStagesIndependentlyAndSwitchesGenerationTargetPerRequest(@TempDir Path db) {
        var store = new AssistantStore(db, mapper);
        var env = new MockEnvironment();
        new AssistantConnection(store, env).synchronize();

        var sources = org.mockito.Mockito.mock(SourceCollector.class);
        org.mockito.Mockito.when(sources.styleSamples()).thenReturn(mapper.createObjectNode());
        var controller = new AssistantController(store, sources,
                org.mockito.Mockito.mock(AssistantWorker.class), org.mockito.Mockito.mock(AssistantPublishing.class),
                mapper, null, env);

        ObjectNode saveBody = mapper.createObjectNode();
        saveBody.putObject("apiKeys")
                .put("minimax", "mm-key-123456")
                .put("deepseek", "ds-key-654321")
                .put("openai", "sk-proj-OpenAiTestKey888")
                .put("google", "AIzaSyTestGoogleKey999");
        saveBody.putObject("moduleConfigs")
                .putObject("repost")
                .put("provider", "openai")
                .put("model", "gpt-5.4 high");
        ObjectNode savedSettings = controller.updateSettings(saveBody);
        // Secret keys must never be stored in plaintext inside meta/settings
        assertThat(savedSettings.toString()).doesNotContain("mm-key-123456", "ds-key-654321", "sk-proj-OpenAiTestKey888", "AIzaSyTestGoogleKey999");

        var owner = org.mockito.Mockito.mock(AuthenticatedXUser.class);
        org.mockito.Mockito.when(owner.id()).thenReturn("u-1");
        var req = new MockHttpServletRequest();
        req.setAttribute("assistantOwner", owner);
        ObjectNode dashboard = controller.dashboard(10, 0, null, "", "", "", "", "score", req);
        var providerDefaults = dashboard.path("providerDefaults");
        assertThat(providerDefaults.path("maskedApiKeys").path("openai").asText()).contains("••••").endsWith("888");
        assertThat(providerDefaults.path("maskedApiKeys").path("google").asText()).contains("••••").endsWith("999");
        assertThat(providerDefaults.path("configuredProviders").path("minimax").asBoolean()).isTrue();
        assertThat(providerDefaults.path("configuredProviders").path("deepseek").asBoolean()).isTrue();
        assertThat(providerDefaults.path("configuredProviders").path("openai").asBoolean()).isTrue();
        assertThat(providerDefaults.path("configuredProviders").path("google").asBoolean()).isTrue();
        assertThat(providerDefaults.path("stageRouting").path("repost").path("provider").asText()).isEqualTo("openai");
        assertThat(providerDefaults.path("stageRouting").path("repost").path("model").asText()).isEqualTo("gpt-5.4 high");

        var intelligence = new AssistantIntelligence(mapper, env, null, store);
        ObjectNode settings = store.tx(AssistantWorkflow::settings);

        // 1) Collection (CLASSIFY & SCORE) -> MiniMax (MiniMax-M2.7)
        var classifyBinding = intelligence.binding(settings, AssistantIntelligence.Stage.CLASSIFY, null);
        var scoreBinding = intelligence.binding(settings, AssistantIntelligence.Stage.SCORE, null);
        assertThat(classifyBinding.connection().serviceName()).isEqualTo("minimax");
        assertThat(classifyBinding.modelId()).isEqualTo("MiniMax-M2.7");
        assertThat(scoreBinding.connection().serviceName()).isEqualTo("minimax");

        // 2) Search (RESEARCH & RESEARCH_SYNTHESIS) -> DeepSeek (deepseek-chat)
        var researchBinding = intelligence.binding(settings, AssistantIntelligence.Stage.RESEARCH, null);
        var synthesisBinding = intelligence.binding(settings, AssistantIntelligence.Stage.RESEARCH_SYNTHESIS, null);
        assertThat(researchBinding.connection().serviceName()).isEqualTo("deepseek");
        assertThat(researchBinding.modelId()).isEqualTo("deepseek-chat");
        assertThat(synthesisBinding.connection().serviceName()).isEqualTo("deepseek");

        // 3) Generation (DRAFT & OPTIMIZE) -> per-call override right at generation time (Gemini & OpenAI API)!
        ObjectNode geminiCallInput = mapper.createObjectNode()
                .put("generationProvider", "google")
                .put("generationModel", "gemini-4-carbon");
        var geminiDraftBinding = intelligence.binding(settings, AssistantIntelligence.Stage.DRAFT, geminiCallInput);
        var geminiOptBinding = intelligence.binding(settings, AssistantIntelligence.Stage.OPTIMIZE, geminiCallInput);
        assertThat(geminiDraftBinding.connection().serviceName()).isEqualTo("google");
        assertThat(geminiDraftBinding.modelId()).isEqualTo("gemini-4-carbon");
        assertThat(geminiOptBinding.connection().serviceName()).isEqualTo("google");
        assertThat(geminiOptBinding.modelId()).isEqualTo("gemini-4-carbon");

        ObjectNode openAiCallInput = mapper.createObjectNode()
                .put("generationProvider", "openai")
                .put("generationModel", "gpt-5.4 high");
        var openAiDraftBinding = intelligence.binding(settings, AssistantIntelligence.Stage.DRAFT, openAiCallInput);
        assertThat(openAiDraftBinding.connection().serviceName()).isEqualTo("openai");
        assertThat(openAiDraftBinding.modelId()).isEqualTo("gpt-5.4 high");
    }

    @Test
    void openAiClientUsesResponsesV1WithReasoningEffortAndWebSearch() throws Exception {
        var responseJson = mapper.readTree("""
                {
                  "id": "resp-oai-1",
                  "output": [
                    {
                      "type": "web_search_call",
                      "action": {"sources": [{"url": "https://platform.openai.com/docs", "title": "OpenAI Docs"}]}
                    },
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "{\\"draft\\":\\"ready\\"}",
                          "annotations": [{"url": "https://openai.com/index/", "title": "OpenAI Index"}]
                        }
                      ]
                    }
                  ],
                  "usage": {"input_tokens": 90, "output_tokens": 35}
                }
                """);
        var client = new OpenAiTextModelClient(mapper, (uri, headers, body, timeout) -> {
            assertThat(uri).isEqualTo(URI.create("https://api.openai.com/v1/responses"));
            assertThat(headers).containsEntry("Authorization", "Bearer sk-test-openai");
            assertThat(body.path("model").asText()).isEqualTo("gpt-6.2-astra");
            assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("high");
            assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("web_search_preview");
            return new CommandCodeTextModelClient.TransportResponse(200, responseJson, Map.of());
        }, ref -> "sk-test-openai");

        var binding = new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection("openai", URI.create(OpenAiTextModelClient.DEFAULT_BASE_URL), AssistantConnection.OPENAI_KEY_ENV, 1L),
                "gpt-6.2-astra high", TextModelClient.Protocol.RESPONSES, 200_000, Duration.ofSeconds(60), 2048, "test"
        );
        var res = client.complete(binding, new TextModelClient.TextRequest("sys", "write", true, true));
        assertThat(res.text()).isEqualTo("{\"draft\":\"ready\"}");
        assertThat(res.requestId()).isEqualTo("resp-oai-1");
        assertThat(res.searchResults()).extracting(TextModelClient.SearchResult::url)
                .containsExactly(
                        URI.create("https://platform.openai.com/docs"),
                        URI.create("https://openai.com/index/")
                );
    }

    @Test
    void savingApiKeyUpdatesServerConnectionVersionInLockstepWithSettings(@TempDir Path tempDir) {
        var env = new MockEnvironment();
        var store = new AssistantStore(tempDir, mapper);
        var conn = new AssistantConnection(store, env);
        conn.synchronize();

        store.tx(tx -> {
            var secrets = AssistantStore.object().put("openaiApiKey", "sk-live-test-key");
            tx.put("secret", "api_keys", secrets);
            int nextVer = AssistantConnection.recordServerConnectionChange(tx, env);
            var settings = AssistantWorkflow.settings(tx);
            settings.put("connectionVersion", nextVer);
            tx.put("meta", "settings", settings);
            return null;
        });

        store.tx(tx -> {
            var server = tx.get("server", "connection");
            var settings = AssistantWorkflow.settings(tx);
            assertThat(server).isNotNull();
            assertThat(settings.path("connectionVersion").asInt()).isEqualTo(server.path("version").asInt());
            return null;
        });
    }
}
