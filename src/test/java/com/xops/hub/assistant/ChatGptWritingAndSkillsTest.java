package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatGptWritingAndSkillsTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chatGptOAuthClientExecutesCompletionAndSearchWithoutApiKeysAndBlocksWhenLoggedOut() throws Exception {
        Path fakeCodex = tempDir.resolve("fake-codex.sh");
        Files.writeString(fakeCodex, """
                #!/bin/sh
                if [ "$1" = "login" ] && [ "$2" = "status" ]; then
                  echo "Logged in using ChatGPT"
                  exit 0
                fi
                if [ -n "$OPENAI_API_KEY" ] || [ -n "$CODEX_API_KEY" ]; then
                  echo "API key leaked into OAuth subprocess" >&2
                  exit 9
                fi
                OUT=""
                IS_SEARCH="0"
                while [ $# -gt 0 ]; do
                  case "$1" in
                    --search) IS_SEARCH="1"; shift ;;
                    -o) OUT="$2"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                cat >/dev/null
                if [ "$IS_SEARCH" = "1" ]; then
                  echo '{"type":"item.completed","item":{"type":"web_search_call","query":"OpenAI release"}}'
                  printf '%s' '{"summary":"Verified official announcement","sources":[{"url":"https://openai.com/index/gpt-update","title":"Official Update","snippet":"Released today","publishedAt":"2026-11-23"}]}' > "$OUT"
                else
                  printf '%s' '```json
                {"ok":true,"draft":"首句直接抛出核心事实。"}
                ```' > "$OUT"
                fi
                exit 0
                """);
        Files.setPosixFilePermissions(fakeCodex, PosixFilePermissions.fromString("rwxr-xr-x"));

        MockEnvironment env = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, tempDir.resolve("missing-pi").toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        ChatGptOAuthTextModelClient client = new ChatGptOAuthTextModelClient(mapper, env);
        TextModelClient.ServiceConnection conn = new TextModelClient.ServiceConnection(
                "chatgpt", URI.create(ChatGptOAuthTextModelClient.BASE_URL), "CHATGPT_OAUTH", 1);
        TextModelClient.ModelBinding binding = new TextModelClient.ModelBinding(
                conn, "chatgpt-default", TextModelClient.Protocol.RESPONSES, 200_000, Duration.ofSeconds(15), 4_000, "v1");

        TextModelClient.ModelResult jsonResult = client.complete(binding,
                new TextModelClient.TextRequest("sys", "user", true, false, ""));
        assertThat(mapper.readTree(jsonResult.text()).path("draft").asText()).isEqualTo("首句直接抛出核心事实。");
        assertThat(jsonResult.modelSnapshot().path("authMode").asText()).isEqualTo("CHATGPT_OAUTH_ONLY");

        TextModelClient.ModelResult searchResult = client.complete(binding,
                new TextModelClient.TextRequest("sys", "find primary sources", true, true, "OpenAI release"));
        assertThat(searchResult.searchResults()).hasSize(1);
        assertThat(searchResult.searchResults().get(0).url().toString()).isEqualTo("https://openai.com/index/gpt-update");
        assertThat(searchResult.toolEvents()).containsKey("web_search_1");

        // Auto-upgrade default provider to chatgpt when OAuth is ready, while respecting manual switch
        AssistantStore store = new AssistantStore(tempDir.resolve("conn-store"), mapper);
        new AssistantConnection(store, env).synchronize();
        ObjectNode synced = store.tx(AssistantWorkflow::settings);
        assertThat(synced.path("provider").asText()).isEqualTo("chatgpt");
        assertThat(synced.path("model").asText()).isEqualTo("gpt-6.2-astra low");
        assertThat(synced.path("contextTokens").asInt()).isEqualTo(200_000);

        // Verify Astra6.2 low alias normalization & strict low reasoning effort even on long prompts (>1600 chars)
        assertThat(ChatGptOAuthTextModelClient.parseModelSpec("Astra6.2 low"))
                .isEqualTo(new ChatGptOAuthTextModelClient.ModelSpec("gpt-6.2-astra", "low"));
        assertThat(ChatGptOAuthTextModelClient.parseModelSpec("gpt-6.2-astra"))
                .isEqualTo(new ChatGptOAuthTextModelClient.ModelSpec("gpt-6.2-astra", "low"));
        Path argCapture = tempDir.resolve("captured-args.txt");
        Path argCheckingCodex = tempDir.resolve("arg-codex.sh");
        Files.writeString(argCheckingCodex, """
                #!/bin/sh
                if [ "$1" = "login" ] && [ "$2" = "status" ]; then
                  echo "Logged in using ChatGPT ($CODEX_HOME)"
                  exit 0
                fi
                printf '%s\\n' "$@" > "__ARG_CAPTURE__"
                OUT=""
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -o) OUT="$2"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                cat >/dev/null
                printf '%s' '{"ok":true}' > "$OUT"
                exit 0
                """.replace("__ARG_CAPTURE__", argCapture.toString()));
        Files.setPosixFilePermissions(argCheckingCodex, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path customCodexHome = tempDir.resolve("custom-codex-home");
        Files.createDirectories(customCodexHome);
        MockEnvironment astraEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, argCheckingCodex.toString())
                .withProperty(PiAgentViralJudge.CODEX_HOME_ENV, customCodexHome.toString())
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, tempDir.resolve("missing-pi").toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        ChatGptOAuthTextModelClient astraClient = new ChatGptOAuthTextModelClient(mapper, astraEnv);
        TextModelClient.ModelBinding astraBinding = new TextModelClient.ModelBinding(
                conn, "Astra6.2 low", TextModelClient.Protocol.RESPONSES, 200_000, Duration.ofSeconds(15), 4_000, "v1");
        TextModelClient.ModelResult astraResult = astraClient.complete(astraBinding,
                new TextModelClient.TextRequest("sys", "长文素材 ".repeat(500), true, false, ""));
        assertThat(astraResult.modelSnapshot().path("resolvedModel").asText()).isEqualTo("gpt-6.2-astra");
        assertThat(astraResult.modelSnapshot().path("reasoningEffort").asText()).isEqualTo("low");
        List<String> recordedArgs = Files.readAllLines(argCapture);
        assertThat(recordedArgs).contains("model_reasoning_effort=\"low\"", "-m", "gpt-6.2-astra");

        store.tx(tx -> {
            ObjectNode cfg = AssistantWorkflow.settings(tx);
            AssistantConnection.switchProvider(tx, cfg, "commandcode", env);
            cfg.put("providerManuallySelected", true);
            return tx.put("meta", "settings", cfg);
        });
        new AssistantConnection(store, env).synchronize();
        assertThat(store.tx(AssistantWorkflow::settings).path("provider").asText()).isEqualTo("commandcode");

        // Blocked when logged out
        Path loggedOutCodex = tempDir.resolve("logged-out-codex.sh");
        Files.writeString(loggedOutCodex, "#!/bin/sh\necho 'Not logged in'\nexit 1\n");
        Files.setPosixFilePermissions(loggedOutCodex, PosixFilePermissions.fromString("rwxr-xr-x"));
        MockEnvironment loggedOutEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, loggedOutCodex.toString())
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, tempDir.resolve("missing-pi").toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        ChatGptOAuthTextModelClient loggedOutClient = new ChatGptOAuthTextModelClient(mapper, loggedOutEnv);
        assertThatThrownBy(() -> loggedOutClient.complete(binding,
                new TextModelClient.TextRequest("sys", "user", false, false, "")))
                .isInstanceOf(CommandCodeTextModelClient.ModelCallException.class);
    }

    @Test
    void fastModeDraftingPresetSkillRewriteAndPrimaryMaterialSynthesisWorkEndToEnd() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("fast-skill-store"), mapper);
        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());

        List<String> capturedPrompts = new ArrayList<>();
        TextModelClient fakeChatGpt = new TextModelClient() {
            @Override
            public java.util.Set<Protocol> supportedProtocols() {
                return java.util.EnumSet.of(Protocol.RESPONSES);
            }

            @Override
            public Protocol defaultProtocol() {
                return Protocol.RESPONSES;
            }

            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                capturedPrompts.add(request.systemText() + "\n" + request.userText());
                assertThat(binding.connection().serviceName()).isEqualTo("chatgpt");
                if (request.userText().startsWith("Build a research result")) {
                    var synth = object()
                            .put("summary", "原帖已明确给出发布事实");
                    var claim = synth.putArray("claims").addObject()
                            .put("claim", "OpenAI 上线全新推理架构，延迟降低 40%")
                            .put("relation", "SUPPORTS");
                    claim.putArray("sourceIds").add("material-m1-author");
                    claim.putArray("excerptIds").add("material-m1-author#p1");
                    synth.putArray("angles").add("底层推理架构延迟下降带来的实时交互拐点");
                    synth.putArray("unverified");
                    return new ModelResult(synth.toString(), List.of(), null, "req-synth", object(), Map.of());
                }
                boolean isRewrite = request.userText().contains("犀利观点促评");
                var reply = object()
                        .put("needsAdditionalResearch", false)
                        .put("draft", isRewrite
                                ? "延迟砍掉 40% 才是这次推理架构更新真正的胜负手：实时 Agent 终于跨过了交互卡顿生死线。"
                                : "多数人只盯着跑分，这次推理延迟直降 40% 才真正改写了高频 Agent 的成本结构。");
                reply.putArray("usedClaimIds").add("claim-1");
                reply.putArray("caveats");
                reply.putArray("factChanges");
                return new ModelResult(reply.toString(), List.of(), null, "req-draft", object(), Map.of());
            }
        };

        MockEnvironment env = new MockEnvironment().withProperty("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "chatgpt");
        AssistantIntelligence intelligence = new AssistantIntelligence(mapper, env, fakeChatGpt, mock(TextSourceReader.class));
        AssistantWorker worker = new AssistantWorker(store, intelligence, sources);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
                ObjectNode cfg = AssistantWorkflow.settings(tx)
                        .put("provider", "chatgpt")
                        .put("model", "chatgpt-default")
                        .put("protocol", "RESPONSES")
                        .put("contextTokens", 200_000)
                        .put("enabled", true)
                        .put("modelEnabled", true);
                tx.put("meta", "settings", cfg);
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "m1")
                        .put("username", "sama")
                        .put("authorName", "Sam Altman")
                        .put("text", "OpenAI 上线全新推理架构，延迟降低 40%")
                        .put("textStatus", "TEXT_ONLY")
                        .put("sourceUrl", "https://x.com/sama/status/190001")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                return null;
            });

            var mvc = MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, Instant.now()));

            mvc.perform(get("/api/content-assistant/dashboard").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.writingSkills.length()").value(4))
                    .andExpect(jsonPath("$.writingSkills[0].id").value("BREAKING_FLASH"));

            // 1. Fast mode + DEEP_BREAKDOWN skips web scraping & WAITING_STYLE and drafts in 1 step
            String fastResp = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"materialId\":\"m1\",\"mode\":\"FAST\",\"skillId\":\"DEEP_BREAKDOWN\",\"clientRequestId\":\"req-fast-1\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.kind").value("DRAFT"))
                    .andExpect(jsonPath("$.stage").value("DRAFT"))
                    .andReturn().getResponse().getContentAsString();
            String fastTaskId = mapper.readTree(fastResp).path("id").asText();

            ObjectNode claimedFast = store.tx(t -> t.put("task", fastTaskId,
                    t.get("task", fastTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimedFast);

            ObjectNode readyTask = store.tx(t -> t.get("task", fastTaskId));
            assertThat(readyTask.path("status").asText()).isEqualTo("READY");
            String draftId = readyTask.path("draftId").asText();
            ObjectNode createdDraft = store.tx(t -> t.get("draft", draftId));
            assertThat(createdDraft.path("skillId").asText()).isEqualTo("DEEP_BREAKDOWN");
            assertThat(createdDraft.path("text").asText()).contains("延迟直降 40%");
            assertThat(capturedPrompts.get(capturedPrompts.size() - 1))
                    .contains("深度拆解长文")
                    .contains("vm_ranker")
                    .contains("这不仅是 X，更是 Y");

            // 2. One-click preset skill rewrite (SHARP_OPINION) with empty instruction
            String optResp = mvc.perform(post("/api/content-assistant/drafts/" + draftId + "/optimize").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"version\":1,\"skillId\":\"SHARP_OPINION\",\"instruction\":\"\",\"clientRequestId\":\"req-opt-1\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.kind").value("OPTIMIZE"))
                    .andReturn().getResponse().getContentAsString();
            String optTaskId = mapper.readTree(optResp).path("id").asText();
            ObjectNode claimedOpt = store.tx(t -> t.put("task", optTaskId,
                    t.get("task", optTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimedOpt);

            ObjectNode rewrittenDraft = store.tx(t -> t.get("draft", draftId));
            assertThat(rewrittenDraft.path("version").asInt()).isEqualTo(2);
            assertThat(rewrittenDraft.path("skillId").asText()).isEqualTo("SHARP_OPINION");
            assertThat(rewrittenDraft.path("text").asText()).contains("胜负手");

            // 3. Primary X post (MATERIAL_FULL) is draftable in synthesizeResearch even before external web pages echo it
            String topicId = store.tx(tx -> AssistantTopics.topicOf(tx, "m1"));
            ObjectNode synthInput = store.tx(tx -> {
                ObjectNode snap = AssistantTopics.snapshot(tx, topicId, "m1");
                ObjectNode reads = object();
                reads.putArray("evidence");
                reads.putArray("sourceReads");
                reads.putArray("searchEvents");
                reads.putArray("sourceRelations");
                String postBody = "OpenAI 上线全新推理架构，延迟降低 40%";
                int cp = postBody.codePointCount(0, postBody.length());
                var matSource = new ContentAssistantModelTasks.SourceEvidence(
                        "material-m1-author", "https://x.com/sama/status/190001", "X author @sama",
                        "@sama", "", "", "", "原帖 190001", "MATERIAL_FULL",
                        List.of(new ContentAssistantModelTasks.EvidenceExcerpt(
                                "material-m1-author#p1", "material-m1-author", "p1", 0, cp, postBody, 5)),
                        cp, cp, "FULL_TEXT_COVERED", "fp-m1");
                reads.putArray("materialEvidence").add(mapper.valueToTree(matSource));
                snap.set("researchReads", reads);
                return snap;
            });
            ObjectNode settings = store.tx(AssistantWorkflow::settings);
            ObjectNode synthOut = intelligence.run("RESEARCH_SYNTHESIS", settings, synthInput);
            assertThat(synthOut.at("/result/draftable").asBoolean()).isTrue();
            assertThat(synthOut.at("/result/quality").asText()).isEqualTo("PARTIAL");
        } finally {
            worker.close();
        }
    }

    @Test
    void fastModePrioritizesSelectedMaterialAsClaim1WithRoleAttribution() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("fast-multi-store"), mapper);
        store.tx(tx -> {
            AssistantWorkflow.ingest(tx, object()
                    .put("id", "m1")
                    .put("username", "firstuser")
                    .put("authorName", "First Author")
                    .put("text", "第一条同选题快讯")
                    .put("textStatus", "TEXT_ONLY")
                    .put("sourceUrl", "https://x.com/firstuser/status/1001")
                    .put("publishedAt", Instant.now().toEpochMilli()));
            AssistantWorkflow.ingest(tx, object()
                    .put("id", "m2")
                    .put("username", "selecteduser")
                    .put("authorName", "Selected Author")
                    .put("text", "核心转发点评")
                    .put("quotedUsername", "official_ai")
                    .put("quotedText", "官方公布推理成本下降 50%")
                    .put("articleText", "长文详细拆解 KV Cache 优化机制")
                    .put("textStatus", "TEXT_ONLY")
                    .put("sourceUrl", "https://x.com/selecteduser/status/1002")
                    .put("publishedAt", Instant.now().toEpochMilli()));
            // Merge m2 into m1's topic so m1 is first in topic.materialIds and m2 is second
            String topic1 = AssistantTopics.topicOf(tx, "m1");
            String topic2 = AssistantTopics.topicOf(tx, "m2");
            ObjectNode expected = object()
                    .put(topic1, tx.get("topic", topic1).path("version").asInt())
                    .put(topic2, tx.get("topic", topic2).path("version").asInt());
            AssistantTopics.move(tx, List.of("m2"), topic1, expected, "USER");
            return null;
        });

        ObjectNode task = store.tx(tx -> AssistantWorkflow.research(tx, object()
                .put("materialId", "m2")
                .put("mode", "FAST")
                .put("clientRequestId", "req-fast-multi-1")));

        ObjectNode research = (ObjectNode) task.path("input").path("research");
        assertThat(research.path("materialId").asText()).isEqualTo("m2");
        assertThat(research.path("claims")).hasSize(3);
        assertThat(research.at("/claims/0/sourceIds/0").asText()).isEqualTo("material-m2-author");
        assertThat(research.at("/claims/0/claim").asText()).isEqualTo("@selecteduser：核心转发点评");
        assertThat(research.at("/claims/1/claim").asText()).isEqualTo("引用 @official_ai：官方公布推理成本下降 50%");
        assertThat(research.at("/claims/2/claim").asText()).isEqualTo("归属待核实的长文节选：长文详细拆解 KV Cache 优化机制");
        assertThat(research.path("materialEvidence")).hasSize(3);
        assertThat(research.at("/materialEvidence/0/publisher").asText()).isEqualTo("Selected Author (@selecteduser)");
        assertThat(research.deepCopy().without("snapshot").toString())
                .doesNotContain("material-m1-author", "第一条同选题快讯", "First Author");
    }

    @Test
    void parseDraftAcceptsTolerantUsedClaimIdsAndRejectsOutOfBounds() throws Exception {
        ContentIntelligenceEngine engine = new ContentIntelligenceEngine(mapper);
        var research = new ContentIntelligenceEngine.Research(
                "摘要",
                List.of(
                        new ContentIntelligenceEngine.Claim("事实一", ContentIntelligenceEngine.EvidenceRelation.SUPPORTS,
                                List.of("s1"), List.of("s1#p1")),
                        new ContentIntelligenceEngine.Claim("事实二", ContentIntelligenceEngine.EvidenceRelation.SUPPORTS,
                                List.of("s2"), List.of("s2#p1"))
                ),
                List.of(),
                List.of()
        );

        assertThat(engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":["claim-1"],"caveats":[],"factChanges":[]}
                """), research).usedClaims()).containsExactly("事实一");
        assertThat(engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":[1],"caveats":[],"factChanges":[]}
                """), research).usedClaims()).containsExactly("事实一");
        assertThat(engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":["1"],"caveats":[],"factChanges":[]}
                """), research).usedClaims()).containsExactly("事实一");
        assertThat(engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":["c1","claim_2"],"caveats":[],"factChanges":[]}
                """), research).usedClaims()).containsExactly("事实一", "事实二");
        assertThat(engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":[0,1],"caveats":[],"factChanges":[]}
                """), research).usedClaims()).containsExactly("事实一", "事实二");
        assertThat(engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":null,"caveats":[],"factChanges":[]}
                """), research).usedClaims()).isEmpty();

        assertThatThrownBy(() -> engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":["claim-99"],"caveats":[],"factChanges":[]}
                """), research))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("usedClaimIds must come from the accepted research result");
        assertThatThrownBy(() -> engine.parseDraft(mapper.readTree("""
                {"draft":"正文","usedClaimIds":[0,2],"caveats":[],"factChanges":[]}
                """), research))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("usedClaimIds must come from the accepted research result");

        ChatGptOAuthTextModelClient client = new ChatGptOAuthTextModelClient(mapper, new MockEnvironment());
        String extracted = client.extractJsonObjectString("\uFEFF```json\n{\"ok\":true,\"draft\":\"bom-clean\"}\n```\uFEFF");
        assertThat(mapper.readTree(extracted).path("draft").asText()).isEqualTo("bom-clean");
    }

    @Test
    void recommendSkillAndAutoModeFastDraftingAndDashboardExposureWork() throws Exception {
        // 1. All 4 deterministic skill recommendations
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "Agent 提效实操"),
                List.of(object().put("text", "1. 拆解任务\n2. 隔离沙箱\n3. 自动化回归"))))
                .isEqualTo("ACTIONABLE_LIST");
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "AI Coding checklist"),
                List.of(object().put("text", "分享一套团队日常 workflow"))))
                .isEqualTo("ACTIONABLE_LIST");
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "架构长文解析"),
                List.of(object().put("text", "深度复盘").put("articleText", "完整长文内容"))))
                .isEqualTo("DEEP_BREAKDOWN");
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "高价值技术分析").put("valueScore", 90).put("reachScore", 72),
                List.of(object().put("text", "简短摘要"))))
                .isEqualTo("DEEP_BREAKDOWN");
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "关于开源模型效率的争议"),
                List.of(object().put("text", "很多人高估了参数量，其实推理延迟才是瓶颈"))))
                .isEqualTo("SHARP_OPINION");
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "转发点评"),
                List.of(object().put("kind", "QUOTE").put("text", "这点非常关键").put("quotedText", "官方公布新接口"))))
                .isEqualTo("SHARP_OPINION");
        assertThat(ViralWritingSkills.recommendSkill(
                object().put("title", "OpenAI 发布新模型"),
                List.of(object().put("text", "OpenAI 刚刚正式上线新版本 API"))))
                .isEqualTo("BREAKING_FLASH");

        // 2. End-to-end: /dashboard exposes recommendedSkillId/Name, and skillId="AUTO" persists resolved skill on draft
        AssistantStore store = new AssistantStore(tempDir.resolve("auto-skill-store"), mapper);
        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());

        TextModelClient fakeChatGpt = new TextModelClient() {
            @Override
            public java.util.Set<Protocol> supportedProtocols() {
                return java.util.EnumSet.of(Protocol.RESPONSES);
            }

            @Override
            public Protocol defaultProtocol() {
                return Protocol.RESPONSES;
            }

            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                var reply = object()
                        .put("needsAdditionalResearch", false)
                        .put("draft", "这套 3 步 workflow 清单直接省掉一半调试时间。");
                reply.putArray("usedClaimIds").add("claim-1");
                reply.putArray("caveats");
                reply.putArray("factChanges");
                return new ModelResult(reply.toString(), List.of(), null, "req-auto", object(), Map.of());
            }
        };

        MockEnvironment env = new MockEnvironment().withProperty("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "chatgpt");
        AssistantIntelligence intelligence = new AssistantIntelligence(mapper, env, fakeChatGpt, mock(TextSourceReader.class));
        AssistantWorker worker = new AssistantWorker(store, intelligence, sources);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                        .put("provider", "chatgpt")
                        .put("model", "chatgpt-default")
                        .put("protocol", "RESPONSES")
                        .put("contextTokens", 200_000)
                        .put("enabled", true)
                        .put("modelEnabled", true));
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "m-list")
                        .put("username", "builder")
                        .put("authorName", "Builder")
                        .put("text", "实战步骤清单：\n1. 先定指标\n2. 再跑基准\n3. 最后上线")
                        .put("textStatus", "TEXT_ONLY")
                        .put("sourceUrl", "https://x.com/builder/status/2001")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                return null;
            });

            var mvc = MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, Instant.now()));

            mvc.perform(get("/api/content-assistant/dashboard").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.topics[0].recommendedSkillId").value("ACTIONABLE_LIST"))
                    .andExpect(jsonPath("$.topics[0].recommendedSkillName").value("干货清单"));

            String fastResp = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"materialId\":\"m-list\",\"mode\":\"FAST\",\"skillId\":\"AUTO\",\"clientRequestId\":\"req-auto-1\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String taskId = mapper.readTree(fastResp).path("id").asText();
            ObjectNode claimed = store.tx(t -> t.put("task", taskId,
                    t.get("task", taskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimed);

            ObjectNode doneTask = store.tx(t -> t.get("task", taskId));
            assertThat(doneTask.path("status").asText()).isEqualTo("READY");
            ObjectNode draft = store.tx(t -> t.get("draft", doneTask.path("draftId").asText()));
            assertThat(draft.path("skillId").asText()).isEqualTo("ACTIONABLE_LIST");
        } finally {
            worker.close();
        }
    }

    @Test
    void customCreationAnglePassesThroughFastDraftPersistsOnDraftAndRejectsTooLongInput() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("angle-store"), mapper);
        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());

        List<String> capturedPrompts = new ArrayList<>();
        TextModelClient fakeChatGpt = new TextModelClient() {
            @Override
            public java.util.Set<Protocol> supportedProtocols() {
                return java.util.EnumSet.of(Protocol.RESPONSES);
            }

            @Override
            public Protocol defaultProtocol() {
                return Protocol.RESPONSES;
            }

            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                capturedPrompts.add(request.userText());
                var reply = object()
                        .put("needsAdditionalResearch", false)
                        .put("draft", "从独立开发者成本视角看，这次推理降价直接改写了小团队的产品边界。");
                reply.putArray("usedClaimIds").add("claim-1");
                reply.putArray("caveats");
                reply.putArray("factChanges");
                return new ModelResult(reply.toString(), List.of(), null, "req-angle", object(), Map.of());
            }
        };

        MockEnvironment env = new MockEnvironment().withProperty("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "chatgpt");
        AssistantIntelligence intelligence = new AssistantIntelligence(mapper, env, fakeChatGpt, mock(TextSourceReader.class));
        AssistantWorker worker = new AssistantWorker(store, intelligence, sources);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                        .put("provider", "chatgpt")
                        .put("model", "chatgpt-default")
                        .put("protocol", "RESPONSES")
                        .put("contextTokens", 200_000)
                        .put("enabled", true)
                        .put("modelEnabled", true));
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "m-angle")
                        .put("username", "sama")
                        .put("authorName", "Sam Altman")
                        .put("text", "推理成本大幅下降")
                        .put("textStatus", "TEXT_ONLY")
                        .put("sourceUrl", "https://x.com/sama/status/3001")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                return null;
            });

            var mvc = MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, Instant.now()));

            String angle = "从独立开发者成本结构切入，强调小团队机会";
            String angleResp = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("materialId", "m-angle")
                                    .put("mode", "FAST")
                                    .put("skillId", "AUTO")
                                    .put("instruction", "  " + angle + "  ")
                                    .put("clientRequestId", "req-angle-1")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String angleTaskId = mapper.readTree(angleResp).path("id").asText();

            String plainResp = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("materialId", "m-angle")
                                    .put("mode", "FAST")
                                    .put("skillId", "AUTO")
                                    .put("instruction", "")
                                    .put("clientRequestId", "req-angle-plain")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String plainTaskId = mapper.readTree(plainResp).path("id").asText();
            assertThat(angleTaskId).isNotEqualTo(plainTaskId);

            ObjectNode claimed = store.tx(t -> t.put("task", angleTaskId,
                    t.get("task", angleTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimed);

            assertThat(capturedPrompts).hasSize(1);
            assertThat(capturedPrompts.get(0)).contains("\"currentInstruction\":\"" + angle + "\"");

            ObjectNode doneTask = store.tx(t -> t.get("task", angleTaskId));
            String draftId = doneTask.path("draftId").asText();
            ObjectNode draft = store.tx(t -> t.get("draft", draftId));
            assertThat(draft.path("angleInstruction").asText()).isEqualTo(angle);

            // Optimize preserves angleInstruction from base draft
            String optResp = mvc.perform(post("/api/content-assistant/drafts/" + draftId + "/optimize").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"version\":1,\"skillId\":\"SHARP_OPINION\",\"instruction\":\"\",\"clientRequestId\":\"req-angle-opt\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String optTaskId = mapper.readTree(optResp).path("id").asText();
            ObjectNode claimedOpt = store.tx(t -> t.put("task", optTaskId,
                    t.get("task", optTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimedOpt);
            assertThat(store.tx(t -> t.get("draft", draftId)).path("angleInstruction").asText()).isEqualTo(angle);

            // > 500 chars rejected with 400
            mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("materialId", "m-angle")
                                    .put("mode", "FAST")
                                    .put("instruction", "角".repeat(501))
                                    .put("clientRequestId", "req-angle-too-long")
                                    .toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            worker.close();
        }
    }

    @Test
    void skillIdPropagatesFromPublicationToObservationAndWinningSamplesFeedIntoDraftPrompt() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("obs-loop-store"), mapper);
        long publishedAt = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli();
        long observedAt = publishedAt + 24L * 3_600_000L;

        store.tx(tx -> {
            tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                    .put("provider", "chatgpt")
                    .put("model", "chatgpt-default")
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 200_000)
                    .put("enabled", true)
                    .put("modelEnabled", true));
            tx.put("draft", "d-win", object()
                    .put("text", "多数人只看到了参数规模，真正改写成本曲线的是推理缓存复用。\n\n第二段拆解细节。")
                    .put("topicId", "t-win")
                    .put("topicVersion", 1)
                    .put("skillId", "DEEP_BREAKDOWN"));
            tx.put("publication", "direct-win-1", object()
                    .put("draftId", "d-win")
                    .put("draftVersion", 1)
                    .put("topicId", "t-win")
                    .put("topicVersion", 1)
                    .put("skillId", "DEEP_BREAKDOWN")
                    .put("status", "PUBLISHED")
                    .put("text", "多数人只看到了参数规模，真正改写成本曲线的是推理缓存复用。\n\n第二段拆解细节。")
                    .put("publishedTweetId", "990001")
                    .put("publishedAt", publishedAt));
            return null;
        });

        AssistantObservations observations = new AssistantObservations(
                store,
                () -> { },
                new AssistantObservations.ObservationClient() {
                    @Override
                    public ObjectNode post(String ownerId, String postId) {
                        return object()
                                .put("observedAt", observedAt)
                                .put("views", 12_000)
                                .put("likes", 180)
                                .put("replies", 22)
                                .put("reposts", 15)
                                .put("bookmarks", 30);
                    }

                    @Override
                    public ObjectNode owner(String ownerId, String username) {
                        return object().put("observedAt", observedAt).put("followers", 5000);
                    }
                },
                java.time.Clock.fixed(Instant.ofEpochMilli(observedAt), java.time.ZoneOffset.UTC)
        );
        try {
            observations.runTick();
        } finally {
            observations.stop();
        }

        // (a) publication -> observation_target -> observation preserves skillId
        ObjectNode obsRow = store.tx(tx -> tx.get("observation", "990001:24"));
        assertThat(obsRow).isNotNull();
        assertThat(obsRow.path("status").asText()).isEqualTo("OBSERVED");
        assertThat(obsRow.path("skillId").asText()).isEqualTo("DEEP_BREAKDOWN");

        // Also inject a high percentChange baseline on the observation and verify FAST draft prompt receives winning hint
        store.tx(tx -> {
            ObjectNode updated = obsRow.deepCopy();
            updated.withObject("/baseline").withObject("/views").put("percentChange", 65.0).put("ready", true);
            tx.put("observation", "990001:24", updated);
            AssistantWorkflow.ingest(tx, object()
                    .put("id", "m-next")
                    .put("username", "sama")
                    .put("authorName", "Sam Altman")
                    .put("text", "新一代模型上下文窗口翻倍")
                    .put("textStatus", "TEXT_ONLY")
                    .put("sourceUrl", "https://x.com/sama/status/990002")
                    .put("publishedAt", Instant.now().toEpochMilli()));
            return null;
        });

        List<String> capturedPrompts = new ArrayList<>();
        TextModelClient fakeChatGpt = new TextModelClient() {
            @Override
            public java.util.Set<Protocol> supportedProtocols() {
                return java.util.EnumSet.of(Protocol.RESPONSES);
            }

            @Override
            public Protocol defaultProtocol() {
                return Protocol.RESPONSES;
            }

            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                capturedPrompts.add(request.userText());
                var reply = object()
                        .put("needsAdditionalResearch", false)
                        .put("draft", "上下文窗口翻倍的真正价值在于一次装下整个代码库。");
                reply.putArray("usedClaimIds").add("claim-1");
                reply.putArray("caveats");
                reply.putArray("factChanges");
                return new ModelResult(reply.toString(), List.of(), null, "req-win", object(), Map.of());
            }
        };

        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());
        MockEnvironment env = new MockEnvironment().withProperty("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "chatgpt");
        AssistantIntelligence intelligence = new AssistantIntelligence(mapper, env, fakeChatGpt, mock(TextSourceReader.class));
        AssistantWorker worker = new AssistantWorker(store, intelligence, sources);
        try {
            ObjectNode task = store.tx(tx -> AssistantWorkflow.research(tx, object()
                    .put("materialId", "m-next")
                    .put("mode", "FAST")
                    .put("skillId", "AUTO")
                    .put("clientRequestId", "req-win-fast")));
            String taskId = task.path("id").asText();
            ObjectNode claimed = store.tx(t -> t.put("task", taskId,
                    t.get("task", taskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimed);

            ObjectNode done = store.tx(t -> t.get("task", taskId));
            assertThat(done.path("status").asText()).isEqualTo("READY");
            assertThat(capturedPrompts).hasSize(1);
            assertThat(capturedPrompts.get(0))
                    .contains("本账号实测高互动历史参考")
                    .contains("深度拆解")
                    .contains("浏览超基线 +65%")
                    .contains("多数人只看到了参数规模");
            // Stored meta/preferences must remain untouched
            assertThat(store.tx(AssistantWorkflow::preferences).path("text").asText("")).isEmpty();
        } finally {
            worker.close();
        }
    }

    @Test
    void codexSearchDrainsOver100KbJsonlWithoutBrokenPipeAndKillsProcessTreeOnTimeout() throws Exception {
        // (a) >100KB stdout JSONL stream must be drained without SIGPIPE so -o file is written cleanly
        Path bigStreamCodex = tempDir.resolve("big-stream-codex.sh");
        Files.writeString(bigStreamCodex, """
                #!/bin/sh
                if [ "$1" = "login" ] && [ "$2" = "status" ]; then
                  echo "Logged in using ChatGPT"
                  exit 0
                fi
                OUT=""
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -o) OUT="$2"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                cat >/dev/null
                i=0
                while [ $i -lt 900 ]; do
                  printf '{"type":"item.delta","idx":%d,"pad":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}\\n' "$i"
                  i=$((i + 1))
                done
                printf '{"type":"item.completed","item":{"type":"web_search_call","query":"big stream"}}\\n'
                printf '%s' '{"summary":"Drained over 100KB cleanly","sources":[{"url":"https://openai.com/index/big-stream","title":"Big Stream","snippet":"ok","publishedAt":"2026-11-23"}]}' > "$OUT"
                exit 0
                """);
        Files.setPosixFilePermissions(bigStreamCodex, PosixFilePermissions.fromString("rwxr-xr-x"));

        MockEnvironment bigEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, bigStreamCodex.toString())
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, tempDir.resolve("missing-pi").toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        ChatGptOAuthTextModelClient client = new ChatGptOAuthTextModelClient(mapper, bigEnv);
        TextModelClient.ServiceConnection conn = new TextModelClient.ServiceConnection(
                "chatgpt", URI.create(ChatGptOAuthTextModelClient.BASE_URL), "CHATGPT_OAUTH", 1);
        TextModelClient.ModelBinding binding = new TextModelClient.ModelBinding(
                conn, "chatgpt-default", TextModelClient.Protocol.RESPONSES, 200_000, Duration.ofSeconds(15), 4_000, "v1");

        TextModelClient.ModelResult res = client.complete(binding,
                new TextModelClient.TextRequest("sys", "search big stream", true, true, "big stream"));
        assertThat(mapper.readTree(res.text()).path("summary").asText()).isEqualTo("Drained over 100KB cleanly");
        assertThat(res.searchResults()).hasSize(1);
        assertThat(res.searchResults().get(0).url().toString()).isEqualTo("https://openai.com/index/big-stream");

        // (b) Timeout kills both child shell and its spawned background descendant (sleep 30) within 2s
        Path pidFile = tempDir.resolve("grandchild.pid");
        Path hangCodex = tempDir.resolve("hang-codex.sh");
        Files.writeString(hangCodex, "#!/bin/sh\n"
                + "if [ \"$1\" = \"login\" ] && [ \"$2\" = \"status\" ]; then echo \"Logged in using ChatGPT\"; exit 0; fi\n"
                + "cat >/dev/null\n"
                + "sleep 30 &\n"
                + "echo $! > \"" + pidFile + "\"\n"
                + "wait\n");
        Files.setPosixFilePermissions(hangCodex, PosixFilePermissions.fromString("rwxr-xr-x"));

        MockEnvironment hangEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, hangCodex.toString())
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, tempDir.resolve("missing-pi").toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        ChatGptOAuthTextModelClient hangClient = new ChatGptOAuthTextModelClient(mapper, hangEnv);
        TextModelClient.ModelBinding shortTimeoutBinding = new TextModelClient.ModelBinding(
                conn, "chatgpt-default", TextModelClient.Protocol.RESPONSES, 200_000, Duration.ofMillis(700), 4_000, "v1");

        assertThatThrownBy(() -> hangClient.complete(shortTimeoutBinding,
                new TextModelClient.TextRequest("sys", "hang test", false, false, "")))
                .isInstanceOf(CommandCodeTextModelClient.ModelCallException.class);

        assertThat(Files.isRegularFile(pidFile)).isTrue();
        long childPid = Long.parseLong(Files.readString(pidFile).strip());
        long deadline = System.currentTimeMillis() + 2000;
        boolean alive = true;
        while (System.currentTimeMillis() < deadline) {
            alive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) break;
            Thread.sleep(30);
        }
        assertThat(alive).as("spawned grandchild PID %d should be killed on timeout", childPid).isFalse();
    }

    @Test
    void materialsEndpointSupportsAuthorNameSearchHighSignalFilterAndEngagementSort() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("materials-query-store"), mapper);
        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());
        AssistantWorker worker = new AssistantWorker(store, mock(AssistantIntelligence.class), sources);
        try {
            long now = Instant.now().toEpochMilli();
            store.tx(tx -> {
                tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
                // Short low-engagement text (not HIGH_SIGNAL)
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "m-short")
                        .put("username", "casual_dev")
                        .put("authorName", "Casual Dev")
                        .put("text", "早安，今天天气不错")
                        .put("textStatus", "TEXT_ONLY")
                        .put("views", 200).put("likes", 5).put("reposts", 0).put("replies", 1)
                        .put("publishedAt", now));
                // Quoted post with highest engagement (HIGH_SIGNAL)
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "m-quote-hot")
                        .put("username", "nv_news")
                        .put("authorName", "NVIDIA Newsroom")
                        .put("text", "重磅转发评论")
                        .put("quotedText", "新一代架构带宽提升两倍")
                        .put("textStatus", "TEXT_ONLY")
                        .put("views", 50_000).put("likes", 1_200).put("reposts", 300).put("replies", 80)
                        .put("publishedAt", now - 60_000L));
                // Long article with medium engagement (HIGH_SIGNAL)
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "m-article")
                        .put("username", "sama")
                        .put("authorName", "Sam Altman")
                        .put("text", "长文发布")
                        .put("articleText", "完整架构复盘文章内容")
                        .put("textStatus", "ARTICLE_FULL")
                        .put("views", 10_000).put("likes", 200).put("reposts", 40).put("replies", 15)
                        .put("publishedAt", now - 120_000L));
                return null;
            });

            var mvc = MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, Instant.now()));

            // 1. Search by authorName ("NVIDIA Newsroom")
            mvc.perform(get("/api/content-assistant/materials").session(session).param("q", "NVIDIA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value("m-quote-hot"));

            // 2. Filter HIGH_SIGNAL excludes m-short
            mvc.perform(get("/api/content-assistant/materials").session(session)
                            .param("status", "HIGH_SIGNAL")
                            .param("sort", "engagement"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value("m-quote-hot"))
                    .andExpect(jsonPath("$[1].id").value("m-article"));

            // 3. Engagement sort orders hottest first across all materials
            mvc.perform(get("/api/content-assistant/materials").session(session).param("sort", "engagement"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].id").value("m-quote-hot"))
                    .andExpect(jsonPath("$[1].id").value("m-article"))
                    .andExpect(jsonPath("$[2].id").value("m-short"));
        } finally {
            worker.close();
        }
    }

    @Test
    void deepResearchContextAndMaterialsInjectIntoDraftFastRegenAndOptimizeWithTrafficFocus() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("deep-ctx-store"), mapper);
        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());

        URI externalDoc = URI.create("https://engineering.example.org/agent-cache-benchmark");
        TextSourceReader reader = uri -> new TextSourceReader.ReadResult(
                uri, uri, "text/html", "Agent Prompt Cache Benchmark 2026",
                "实测多轮仓库级重构时，跨轮次未命中前缀会导致尾延迟飙升 3.2 倍，而新架构通过增量索引把冷启动压到了 480ms。",
                "第1段", Instant.parse("2026-11-26T09:00:00Z"), TextSourceReader.Status.READ, "", List.of()
        );

        List<String> draftPrompts = new ArrayList<>();
        List<String> optimizePrompts = new ArrayList<>();
        TextModelClient fakeModel = new TextModelClient() {
            @Override
            public java.util.Set<Protocol> supportedProtocols() {
                return java.util.EnumSet.of(Protocol.RESPONSES);
            }

            @Override
            public Protocol defaultProtocol() {
                return Protocol.RESPONSES;
            }

            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                if (request.webSearch()) {
                    return new ModelResult(
                            "{\"summary\":\"Found engineering benchmark\"}",
                            List.of(new SearchResult("agent cache benchmark", externalDoc,
                                    "Agent Prompt Cache Benchmark 2026", "480ms cold start", "2026-11-26")),
                            null, "req-web-search", object(),
                            Map.of("web_search_1", object().put("query", "agent cache benchmark"))
                    );
                }
                String userText = request.userText();
                if (userText.startsWith("Build a research result")) {
                    assertThat(userText).contains("debate-worthy X angles");
                    var synth = object().put("summary", "新架构针对长会话 Agent 缓存做了底层改造");
                    var claim = synth.putArray("claims").addObject()
                            .put("claim", "增量索引将仓库级 Agent 冷启动压至 480ms")
                            .put("relation", "SUPPORTS");
                    claim.putArray("sourceIds").add("source-1");
                    claim.putArray("excerptIds").add("source-1#p1");
                    synth.putArray("angles").add("天天跑大仓库的人最痛的其实是跨轮次缓存失效后的尾延迟账单");
                    synth.putArray("unverified");
                    return new ModelResult(synth.toString(), List.of(), null, "req-synth", object(), Map.of());
                }
                if (userText.startsWith("Write one")) {
                    draftPrompts.add(userText);
                    ObjectNode clean = object()
                            .put("draft", "重度跑过仓库级 Agent 的人都懂，真正让人肉疼的从来不是首轮响应，而是跨轮次缓存失效时飙升的尾延迟。\n\n这次把冷启动直接压到 480ms，等于把高频自动改代码的隐形税砍掉了。");
                    clean.putArray("usedClaimIds").add("claim-1");
                    clean.putArray("caveats");
                    clean.putArray("factChanges");
                    return new ModelResult(clean.toString(), List.of(), null, "req-draft", object(), Map.of());
                }
                if (userText.startsWith("Improve structure")) {
                    optimizePrompts.add(userText);
                    ObjectNode clean = object()
                            .put("needsAdditionalResearch", false)
                            .put("draft", "别光看官宣口号，真正决定编程 Agent 能不能全天候挂机的生死线其实是跨轮次尾延迟：冷启动压进 480ms 后，小团队挂机跑测试和人工盯屏的成本账彻底反过来了。");
                    clean.putArray("usedClaimIds").add("claim-1");
                    clean.putArray("caveats");
                    return new ModelResult(clean.toString(), List.of(), null, "req-opt", object(), Map.of());
                }
                throw new IllegalStateException("Unexpected prompt: " + userText);
            }
        };

        MockEnvironment env = new MockEnvironment().withProperty("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "chatgpt");
        AssistantIntelligence intelligence = new AssistantIntelligence(mapper, env, fakeModel, reader);
        AssistantWorker worker = new AssistantWorker(store, intelligence, sources);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
                // Also set built-in quote polish prompt on settings to ensure standalone draft is NOT hijacked into quote TL;DR
                ObjectNode cfg = AssistantWorkflow.settings(tx)
                        .put("provider", "chatgpt")
                        .put("model", "chatgpt-default")
                        .put("protocol", "RESPONSES")
                        .put("contextTokens", 200_000)
                        .put("enabled", true)
                        .put("modelEnabled", true)
                        .put("postPolishEnabled", true)
                        .put("postPolishPrompt", OwnerQuoteStyleDistiller.DEFAULT_POST_POLISH_PROMPT);
                tx.put("meta", "settings", cfg);
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "195001")
                        .put("authorId", "8801")
                        .put("username", "bcherny")
                        .put("authorName", "Boris Cherny")
                        .put("authorBio", "Claude Code creator")
                        .put("text", "We reworked prompt cache indexing for repo-scale coding sessions.")
                        .put("quotedUsername", "anthropicai")
                        .put("quotedAuthorId", "8802")
                        .put("quotedId", "195000")
                        .put("quotedText", "Faster repo indexing is now live for all developers.")
                        .put("textStatus", "TEXT_ONLY")
                        .put("authorTextStatus", "FULL")
                        .put("quotedTextStatus", "FULL")
                        .put("sourceIdentity", "ROW_ID_VERIFIED")
                        .put("views", 85_000).put("likes", 1_900).put("replies", 140)
                        .put("sourceUrl", "https://x.com/bcherny/status/195001")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                return null;
            });

            var mvc = MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, Instant.now()));

            // 1. Run full Deep Research -> Synthesis -> Draft
            String resStr = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("materialId", "195001")
                                    .put("mode", "RESEARCH")
                                    .put("skillId", "SHARP_OPINION")
                                    .put("instruction", "从重度挂机开发者的真实痛点切入")
                                    .put("clientRequestId", "req-deep-1")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String taskId = mapper.readTree(resStr).path("id").asText();

            for (int step = 0; step < 3; step++) {
                ObjectNode running = store.tx(t -> t.put("task", taskId,
                        t.get("task", taskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
                worker.execute(running);
            }

            ObjectNode doneTask = store.tx(t -> t.get("task", taskId));
            assertThat(doneTask.path("status").asText()).isEqualTo("READY");
            // Built-in quote polish prompt must NOT trigger a second-pass optimize on standalone Content Assistant drafts
            assertThat(draftPrompts).hasSize(1);
            assertThat(optimizePrompts).isEmpty();

            String draftPrompt = draftPrompts.get(0);
            assertThat(draftPrompt)
                    .contains("referenceContext")
                    .contains("deepResearchSources")
                    .contains("尾延迟飙升 3.2 倍")
                    .contains("Agent Prompt Cache Benchmark 2026")
                    .contains("primaryMaterials")
                    .contains("Anthropic Claude Code 负责人")
                    .contains("Faster repo indexing is now live")
                    .contains("严禁当“课代表总结”");

            String draftId = doneTask.path("draftId").asText();
            ObjectNode v1Draft = store.tx(t -> t.get("draft", draftId));
            assertThat(v1Draft.path("skillId").asText()).isEqualTo("SHARP_OPINION");

            // 2. Rewrite new version (OPTIMIZE without explicit skillId) -> receives full deep research & material context + keeps skillId
            String optStr = mvc.perform(post("/api/content-assistant/drafts/" + draftId + "/optimize").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("version", 1)
                                    .put("instruction", "换一个更有争议与评论讨论度的话题切口重写新版本")
                                    .put("clientRequestId", "req-deep-opt-1")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String optTaskId = mapper.readTree(optStr).path("id").asText();
            ObjectNode claimedOpt = store.tx(t -> t.put("task", optTaskId,
                    t.get("task", optTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING")));
            worker.execute(claimedOpt);

            assertThat(optimizePrompts).hasSize(1);
            assertThat(optimizePrompts.get(0))
                    .contains("referenceContext")
                    .contains("deepResearchSources")
                    .contains("尾延迟飙升 3.2 倍")
                    .contains("primaryMaterials")
                    .contains("Anthropic Claude Code 负责人")
                    .contains("从重度挂机开发者的真实痛点切入")
                    .contains("犀利观点促评");

            ObjectNode v2Draft = store.tx(t -> t.get("draft", draftId));
            assertThat(v2Draft.path("version").asInt()).isEqualTo(2);
            assertThat(v2Draft.path("skillId").asText()).isEqualTo("SHARP_OPINION");

            // 3. Fast regenerate on a topic that ALREADY has completed Deep Research preserves external deep research evidence!
            ObjectNode fastRegenTask = store.tx(tx -> AssistantWorkflow.research(tx, object()
                    .put("materialId", "195001")
                    .put("mode", "FAST")
                    .put("regenerate", true)
                    .put("clientRequestId", "req-fast-after-deep")));
            JsonNode mergedFastResearch = fastRegenTask.path("input").path("research");
            assertThat(mergedFastResearch.path("evidence")).isNotEmpty();
            assertThat(mergedFastResearch.toString()).contains("尾延迟飙升 3.2 倍");

            // 4. First Fast-Draft v1 -> Then one-click Deep-Research Upgrade directly into SAME draft v2 (no orphan Draft #2!)
            store.tx(tx -> {
                AssistantWorkflow.ingest(tx, object()
                        .put("id", "196001")
                        .put("authorId", "8803")
                        .put("username", "sama")
                        .put("authorName", "Sam Altman")
                        .put("text", "New background coding agent sandbox launched today.")
                        .put("textStatus", "TEXT_ONLY")
                        .put("authorTextStatus", "FULL")
                        .put("sourceIdentity", "ROW_ID_VERIFIED")
                        .put("sourceUrl", "https://x.com/sama/status/196001")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                return null;
            });
            String fastFirstStr = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("materialId", "196001")
                                    .put("mode", "FAST")
                                    .put("skillId", "BREAKING_FLASH")
                                    .put("clientRequestId", "req-fast-first-196")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String fastFirstTaskId = mapper.readTree(fastFirstStr).path("id").asText();
            worker.execute(store.tx(t -> t.put("task", fastFirstTaskId,
                    t.get("task", fastFirstTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            String fastDraftId = store.tx(t -> t.get("task", fastFirstTaskId)).path("draftId").asText();
            String topic196 = store.tx(t -> AssistantTopics.topicOf(t, "196001"));
            assertThat(store.tx(t -> t.get("draft", fastDraftId)).path("version").asInt()).isEqualTo(1);
            assertThat(store.tx(t -> t.get("draft", fastDraftId)).at("/research/quality").asText()).isEqualTo("MATERIAL_DIRECT");

            // Now trigger Deep Research directly bound to fastDraftId -> updates fastDraftId in-place to v2!
            String upgradeStr = mvc.perform(post("/api/content-assistant/research").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("materialId", "196001")
                                    .put("draftId", fastDraftId)
                                    .put("version", 1)
                                    .put("mode", "RESEARCH")
                                    .put("regenerate", true)
                                    .put("clientRequestId", "req-deep-upgrade-196")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.draftId").value(fastDraftId))
                    .andReturn().getResponse().getContentAsString();
            String upgradeTaskId = mapper.readTree(upgradeStr).path("id").asText();
            for (int step = 0; step < 3; step++) {
                worker.execute(store.tx(t -> t.put("task", upgradeTaskId,
                        t.get("task", upgradeTaskId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            }
            ObjectNode upgradedDraft = store.tx(t -> t.get("draft", fastDraftId));
            assertThat(upgradedDraft.path("version").asInt()).isEqualTo(2);
            assertThat(upgradedDraft.path("skillId").asText()).isEqualTo("BREAKING_FLASH");
            assertThat(upgradedDraft.at("/research/evidence")).isNotEmpty();
            assertThat((List<?>) store.tx(t -> t.matching("draft", "topicId", topic196, -1))).hasSize(1);
            assertThat(draftPrompts.get(draftPrompts.size() - 1)).contains("基于最新深度研究重构升级");

            // 5. Verify production log fixes: fenced/think JSON recovery, style sample ID sanitization, and carbon/xhigh 240s timeout + lease
            ContentIntelligenceEngine ci = new ContentIntelligenceEngine(mapper);
            JsonNode recoveredScore = ci.parseJson(new TextModelClient.ModelResult(
                    "<think>分析选题价值</think>\n```json\n{\"title\":\"恢复成功\",\"ok\":true}\n```\n以上是评分结果。",
                    List.of(), null, "r1", object(), Map.of()));
            assertThat(recoveredScore.path("title").asText()).isEqualTo("恢复成功");

            assertThat(ContentAssistantModelTasks.sanitizeStyleExampleIds(
                    List.of("sample-s1:0-180", "#s2", "hallucinated-999"),
                    List.of("s1", "s2", "s3")))
                    .containsExactly("s1", "s2");

            ObjectNode saved120Settings = object()
                    .put("provider", "openai")
                    .put("model", "gpt-6.2-sol")
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 200_000)
                    .put("timeoutSeconds", 120);
            ObjectNode carbonInput = object()
                    .put("generationProvider", "google")
                    .put("generationModel", "gemini-4-carbon");
            assertThat(AssistantWorker.stageLeaseMillis(saved120Settings, carbonInput, "OPTIMIZE"))
                    .isEqualTo(255_000L);
        } finally {
            worker.close();
            intelligence.closeReadPool();
        }
    }

    @Test
    void articleAAndArticleBDeepResearchMaterialsStayStrictlyIsolatedDuringResearchDraftingFastRegenAndOptimize() throws Exception {
        AssistantStore store = new AssistantStore(tempDir.resolve("article-isolation-store"), mapper);
        SourceCollector sources = mock(SourceCollector.class);
        when(sources.styleSamples()).thenReturn(object());
        when(sources.list()).thenReturn(List.of());
        when(sources.history()).thenReturn(object());

        URI docA = URI.create("https://compiler.example.org/rust-linker-63pct");
        URI seedLinkA = URI.create("https://compiler.example.org/seed-only-a");
        URI docB = URI.create("https://storage.example.org/nvme-engine-910k");
        URI seedLinkB = URI.create("https://storage.example.org/seed-only-b");
        List<URI> openedUrls = new java.util.concurrent.CopyOnWriteArrayList<>();

        TextSourceReader reader = uri -> {
            openedUrls.add(uri);
            if (uri.equals(docA) || uri.equals(seedLinkA)) {
                return new TextSourceReader.ReadResult(
                        uri, uri, "text/html", "Compiler Linker Deep Dive A",
                        "文章A外部深挖：增量符号表裁剪将链接阶段峰值内存降低了 63%，仅适用于 ELF 静态产物。",
                        "第1段", Instant.parse("2026-11-27T08:00:00Z"), TextSourceReader.Status.READ, "", List.of()
                );
            }
            if (uri.equals(docB) || uri.equals(seedLinkB)) {
                return new TextSourceReader.ReadResult(
                        uri, uri, "text/html", "NVMe Engine Deep Dive B",
                        "文章B外部深挖：零拷贝轮询队列把单盘随机读拉到了 910k IOPS，但会独占一个物理核。",
                        "第1段", Instant.parse("2026-11-27T09:00:00Z"), TextSourceReader.Status.READ, "", List.of()
                );
            }
            throw new IllegalStateException("Unexpected URI read: " + uri);
        };

        List<String> searchInputs = new ArrayList<>();
        List<String> synthInputs = new ArrayList<>();
        List<String> draftPrompts = new ArrayList<>();
        List<String> optimizePrompts = new ArrayList<>();

        TextModelClient fakeModel = new TextModelClient() {
            @Override
            public java.util.Set<Protocol> supportedProtocols() {
                return java.util.EnumSet.of(Protocol.RESPONSES);
            }

            @Override
            public Protocol defaultProtocol() {
                return Protocol.RESPONSES;
            }

            @Override
            public ModelResult complete(ModelBinding binding, TextRequest request) {
                String userText = request.userText();
                if (request.webSearch()) {
                    searchInputs.add(userText);
                    boolean isA = userText.contains("art-a") || userText.contains("CompilerAlpha");
                    URI hit = isA ? docA : docB;
                    String title = isA ? "Compiler Linker Deep Dive A" : "NVMe Engine Deep Dive B";
                    return new ModelResult(
                            "{\"summary\":\"ok\"}",
                            List.of(new SearchResult(isA ? "compiler linker" : "nvme iops", hit, title, "", "2026-11-27")),
                            null, "req-search", object(),
                            Map.of("web_search_1", object().put("hit", hit.toString()))
                    );
                }
                if (userText.startsWith("Build a research result")) {
                    synthInputs.add(userText);
                    boolean isA = userText.contains("material-art-a-link");
                    var synth = object().put("summary", isA ? "文章A深挖摘要：链接内存降63%" : "文章B深挖摘要：随机读达910k IOPS");
                    var claim = synth.putArray("claims").addObject()
                            .put("claim", isA ? "增量符号表裁剪使峰值内存降低 63%" : "零拷贝轮询使单盘随机读达到 910k IOPS")
                            .put("relation", "SUPPORTS");
                    claim.putArray("sourceIds").add("source-1");
                    claim.putArray("excerptIds").add("source-1#p1");
                    synth.putArray("angles").add(isA ? "文章A切口：静态链接内存瓶颈" : "文章B切口：独占物理核换910k吞吐");
                    synth.putArray("unverified");
                    return new ModelResult(synth.toString(), List.of(), null, "req-synth", object(), Map.of());
                }
                if (userText.startsWith("Write one")) {
                    draftPrompts.add(userText);
                    boolean isA = userText.contains("63%");
                    ObjectNode out = object().put("draft", isA
                            ? "编译大型工程最怕链接器爆内存，增量符号表直接砍掉 63% 峰值占用。"
                            : "单盘飙到 910k IOPS 的代价是死绑一颗物理核，高并发存储得重新算核数账。");
                    out.putArray("usedClaimIds").add("claim-1");
                    out.putArray("caveats");
                    out.putArray("factChanges");
                    return new ModelResult(out.toString(), List.of(), null, "req-draft", object(), Map.of());
                }
                if (userText.startsWith("Improve structure")) {
                    optimizePrompts.add(userText);
                    ObjectNode out = object()
                            .put("needsAdditionalResearch", false)
                            .put("draft", "链接阶段省下 63% 内存后，CI 小规格机器终于不用频繁 OOM 重跑了。");
                    out.putArray("usedClaimIds").add("claim-1");
                    out.putArray("caveats");
                    return new ModelResult(out.toString(), List.of(), null, "req-opt", object(), Map.of());
                }
                throw new IllegalStateException("Unexpected prompt: " + userText);
            }
        };

        MockEnvironment env = new MockEnvironment().withProperty("X_CONTENT_ASSISTANT_MODEL_PROVIDER", "chatgpt");
        AssistantIntelligence intelligence = new AssistantIntelligence(mapper, env, fakeModel, reader);
        AssistantWorker worker = new AssistantWorker(store, intelligence, sources);
        try {
            String sharedTopicId = store.tx(tx -> {
                tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                        .put("provider", "chatgpt")
                        .put("model", "chatgpt-default")
                        .put("protocol", "RESPONSES")
                        .put("contextTokens", 200_000)
                        .put("enabled", true)
                        .put("modelEnabled", true)
                        .put("postPolishEnabled", false));
                String topicA = AssistantWorkflow.ingestDirectLinkMaterial(tx, object()
                        .put("id", "art-a")
                        .put("authorId", "web:compiler.example.org")
                        .put("username", "compiler.example.org")
                        .put("authorName", "CompilerAlpha Release Note")
                        .put("text", "CompilerAlpha 新版链接器架构解析 " + seedLinkA)
                        .put("articleText", "CompilerAlpha 详细介绍静态链接优化。参考延伸：" + seedLinkA)
                        .put("sourceUrl", "https://compiler.example.org/post-a")
                        .put("textStatus", "ARTICLE_FULL")
                        .put("articleTextStatus", "FULL")
                        .put("sourceIdentity", "USER_LINK")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                String topicB = AssistantWorkflow.ingestDirectLinkMaterial(tx, object()
                        .put("id", "art-b")
                        .put("authorId", "web:storage.example.org")
                        .put("username", "storage.example.org")
                        .put("authorName", "StorageBeta Benchmark")
                        .put("text", "StorageBeta 零拷贝引擎实测 " + seedLinkB)
                        .put("articleText", "StorageBeta 披露最新存储引擎测试。参考延伸：" + seedLinkB)
                        .put("sourceUrl", "https://storage.example.org/post-b")
                        .put("textStatus", "ARTICLE_FULL")
                        .put("articleTextStatus", "FULL")
                        .put("sourceIdentity", "USER_LINK")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                String topicC = AssistantWorkflow.ingestDirectLinkMaterial(tx, object()
                        .put("id", "art-c")
                        .put("authorId", "web:network.example.org")
                        .put("username", "network.example.org")
                        .put("authorName", "NetworkGamma QUIC Note")
                        .put("text", "NetworkGamma 调整 QUIC 拥塞窗口")
                        .put("articleText", "NetworkGamma 独立网络协议更新说明。")
                        .put("sourceUrl", "https://network.example.org/post-c")
                        .put("textStatus", "ARTICLE_FULL")
                        .put("articleTextStatus", "FULL")
                        .put("sourceIdentity", "USER_LINK")
                        .put("publishedAt", Instant.now().toEpochMilli()));
                ObjectNode versions = object()
                        .put(topicA, tx.get("topic", topicA).path("version").asInt())
                        .put(topicB, tx.get("topic", topicB).path("version").asInt())
                        .put(topicC, tx.get("topic", topicC).path("version").asInt());
                AssistantTopics.move(tx, List.of("art-b", "art-c"), topicA, versions, "MODEL");
                // Simulate shared topic summary polluted by Article A
                ObjectNode mergedTopic = tx.get("topic", topicA);
                mergedTopic.put("title", "CompilerAlpha 选题标题").put("summary", "CompilerAlpha 选题级旧摘要");
                AssistantTopics.persist(tx, topicA, mergedTopic);
                return topicA;
            });

            // 1. Deep research + draft on Article A
            String taskAId = store.tx(tx -> AssistantWorkflow.research(tx, object()
                    .put("materialId", "art-a")
                    .put("mode", "RESEARCH")
                    .put("clientRequestId", "req-iso-a"))).path("id").asText();
            for (int step = 0; step < 3; step++) {
                worker.execute(store.tx(t -> t.put("task", taskAId,
                        t.get("task", taskAId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            }
            String draftAId = store.tx(t -> t.get("task", taskAId)).path("draftId").asText();
            assertThat(openedUrls).containsExactlyInAnyOrder(seedLinkA, docA);
            assertThat(searchInputs.get(0)).contains("CompilerAlpha").doesNotContain("StorageBeta", "NetworkGamma", "art-b", "art-c");
            assertThat(synthInputs.get(0)).contains("material-art-a-link").doesNotContain("StorageBeta", "910k", "art-b");
            assertThat(draftPrompts.get(0))
                    .contains("63%", "Compiler Linker Deep Dive A", "CompilerAlpha")
                    .doesNotContain("StorageBeta", "910k", "NVMe", "NetworkGamma", "art-b", "art-c");

            openedUrls.clear();

            // 2. Deep research + draft on Article B (in the same topic!)
            String taskBId = store.tx(tx -> AssistantWorkflow.research(tx, object()
                    .put("materialId", "art-b")
                    .put("mode", "RESEARCH")
                    .put("clientRequestId", "req-iso-b"))).path("id").asText();
            for (int step = 0; step < 3; step++) {
                worker.execute(store.tx(t -> t.put("task", taskBId,
                        t.get("task", taskBId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            }
            assertThat(openedUrls).containsExactlyInAnyOrder(seedLinkB, docB);
            assertThat(searchInputs.get(1)).contains("StorageBeta").doesNotContain("CompilerAlpha", "选题级旧摘要", "art-a", "art-c");
            assertThat(synthInputs.get(1)).contains("material-art-b-link").doesNotContain("CompilerAlpha", "63%", "选题级旧摘要", "art-a");
            assertThat(draftPrompts.get(1))
                    .contains("910k", "NVMe Engine Deep Dive B", "StorageBeta")
                    .doesNotContain("CompilerAlpha", "63%", "Compiler Linker Deep Dive A", "选题级旧摘要", "art-a", "art-c");

            // 3. Optimize Draft A AFTER Article B produced newer deep research in the same topic -> MUST NOT mix B into A!
            String optAId = store.tx(tx -> AssistantWorkflow.optimize(tx, draftAId, object()
                    .put("version", 1)
                    .put("instruction", "补充深挖细节改写新版本")
                    .put("clientRequestId", "req-iso-opt-a"))).path("id").asText();
            worker.execute(store.tx(t -> t.put("task", optAId,
                    t.get("task", optAId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            assertThat(optimizePrompts).hasSize(1);
            assertThat(optimizePrompts.get(0))
                    .contains("63%", "Compiler Linker Deep Dive A", "CompilerAlpha")
                    .doesNotContain("910k", "NVMe Engine Deep Dive B", "StorageBeta", "独占物理核", "art-b", "art-c");

            // 4. Fast regenerate on Article A reuses ONLY Article A's deep research; Fast draft on unresearched Article C stays clean
            ObjectNode fastA = store.tx(tx -> AssistantWorkflow.research(tx, object()
                    .put("materialId", "art-a")
                    .put("mode", "FAST")
                    .put("regenerate", true)
                    .put("clientRequestId", "req-iso-fast-a")));
            String fastAResearch = ((ObjectNode) fastA.at("/input/research")).deepCopy().without("snapshot").toString();
            assertThat(fastAResearch).contains("63%", "Compiler Linker Deep Dive A")
                    .doesNotContain("910k", "NVMe Engine Deep Dive B", "StorageBeta", "art-b");
            String fastAId = fastA.path("id").asText();
            worker.execute(store.tx(t -> t.put("task", fastAId,
                    t.get("task", fastAId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            assertThat(draftPrompts.get(2))
                    .contains("63%", "Compiler Linker Deep Dive A", "CompilerAlpha")
                    .doesNotContain("910k", "NVMe Engine Deep Dive B", "StorageBeta", "NetworkGamma", "选题级旧摘要", "art-b", "art-c");

            ObjectNode fastC = store.tx(tx -> AssistantWorkflow.research(tx, object()
                    .put("materialId", "art-c")
                    .put("mode", "FAST")
                    .put("clientRequestId", "req-iso-fast-c")));
            ObjectNode fastCResearch = ((ObjectNode) fastC.at("/input/research")).deepCopy().without("snapshot");
            assertThat(fastCResearch.path("evidence")).isEmpty();
            assertThat(fastCResearch.path("claims")).hasSize(1);
            assertThat(fastCResearch.toString())
                    .contains("NetworkGamma")
                    .doesNotContain("63%", "910k", "CompilerAlpha", "StorageBeta", "选题级旧摘要");
            String fastCId = fastC.path("id").asText();
            worker.execute(store.tx(t -> t.put("task", fastCId,
                    t.get("task", fastCId).put("attempt", AssistantWorkflow.id()).put("status", "RUNNING"))));
            assertThat(draftPrompts.get(3))
                    .contains("NetworkGamma")
                    .doesNotContain("63%", "910k", "CompilerAlpha", "StorageBeta", "选题级旧摘要", "art-a", "art-b");
            assertThat(sharedTopicId).isNotBlank();
        } finally {
            worker.close();
            intelligence.closeReadPool();
        }
    }
}
