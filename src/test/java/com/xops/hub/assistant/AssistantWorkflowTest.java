package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.XDataProviderException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import static com.xops.hub.assistant.AssistantStore.object;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssistantWorkflowTest {
    @TempDir Path dir;
    AssistantStore store;
    ObjectMapper mapper=new ObjectMapper();
    AssistantIntelligence models=mock(AssistantIntelligence.class);
    SourceCollector sources=mock(SourceCollector.class);
    AssistantWorker worker;

    @BeforeEach void start() {
        store=new AssistantStore(dir,mapper);
        worker=new AssistantWorker(store,models,sources);
    }
    @AfterEach void close(){worker.close();}

    private MockHttpSession session(String id,String username) {
        var s=new MockHttpSession();
        s.setAttribute(XOAuthController.SESSION_AUTH_USER,new AuthenticatedXUser(id,username,username,"",false,Instant.now()));
        return s;
    }

    @Test void privateRoutesUseVerifiedStableIdAndOrigin() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new AssistantController(store,sources,worker,mock(AssistantPublishing.class),mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/content-assistant/dashboard")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get("/api/content-assistant/access").session(session("A","elidlicom"))).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(get("/api/content-assistant/access").session(session("B","elidlicom"))).andExpect(jsonPath("$.allowed").value(false));
        mvc.perform(get("/api/content-assistant/access").session(session("A","renamed"))).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(post("/api/content-assistant/research").session(session("B","elidlicom")).header("X-Requested-With","XMLHttpRequest")
                .contentType("application/json").content("{\"ownerId\":\"A\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/content-assistant/preferences").session(session("A","renamed")).header("X-Requested-With","XMLHttpRequest")
                .header("Sec-Fetch-Site","cross-site").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(models,sources);
    }

    @Test void dashboardFiltersAndPaginatesTopicsIndependently() throws Exception {
        store.tx(tx -> {
            tx.put("material", "a", object().put("username", "Alice"));
            tx.put("material", "b", object().put("username", "AliceNews"));
            tx.put("material", "c", object().put("username", "Bob"));
            putTopic(tx, "alpha", "a", "首个专题", "深度分析", 90, 30, 80);
            putTopic(tx, "beta", "b", "第二个专题", "后续报道", 70, 95, 45);
            putTopic(tx, "gamma", "c", "无关专题", "市场动态", 99, 99, 99);
            tx.put("topic", "merged", object().put("score", 100).set("materialIds", object().arrayNode()));
            tx.put("task", "only-task", object().put("status", "READY"));
            return null;
        });
        when(sources.styleSamples()).thenReturn(object());
        var mvc = MockMvcBuilders.standaloneSetup(new AssistantController(store,sources,worker,mock(AssistantPublishing.class),mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var owner = session("A", "elidlicom");
        String dashboard = "/api/content-assistant/dashboard";
        mvc.perform(get(dashboard).session(owner).param("limit", "1").param("q", "@ALICE").param("sort", "value"))
                .andExpect(jsonPath("$.topics[0].id").value("beta")).andExpect(jsonPath("$.topicHasNext").value(true));
        mvc.perform(get(dashboard).session(owner).param("limit", "1").param("q", "@ALICE").param("sort", "value").param("topicOffset", "1"))
                .andExpect(jsonPath("$.topics[0].id").value("alpha")).andExpect(jsonPath("$.topicHasNext").value(false))
                .andExpect(jsonPath("$.topicCount").value(3)).andExpect(jsonPath("$.recommendedTopicCount").value(2))
                .andExpect(jsonPath("$.tasks[0].id").value("only-task")).andExpect(jsonPath("$.materials.length()").value(1));
        mvc.perform(get(dashboard).session(owner).param("limit", "1").param("username", "@alice").param("sort", "reach"))
                .andExpect(jsonPath("$.topics[0].id").value("alpha"));
        mvc.perform(get(dashboard).session(owner).param("q", "深度分析"))
                .andExpect(jsonPath("$.topics.length()").value(1)).andExpect(jsonPath("$.topics[0].id").value("alpha"));
        mvc.perform(get(dashboard).session(owner).param("limit", "1").param("offset", "1"))
                .andExpect(jsonPath("$.topics[0].id").value("alpha")).andExpect(jsonPath("$.tasks.length()").value(0));
        mvc.perform(post("/api/content-assistant/settings").session(owner).header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json").content("{\"version\":0,\"threshold\":100}")).andExpect(status().isOk());
        mvc.perform(get(dashboard).session(owner)).andExpect(jsonPath("$.recommendedTopicCount").value(0));
    }

    private static void putTopic(AssistantStore.Tx tx, String id, String materialId, String title,
                                 String summary, int score, int value, int reach) {
        var topic = object().put("title", title).put("summary", summary).put("score", score)
                .put("valueScore", value).put("reachScore", reach);
        topic.putArray("materialIds").add(materialId);
        tx.put("topic", id, topic);
    }

    @Test void dashboardKeepsLegacyCapabilityChecksUntilEffectiveBindingsChange() throws Exception {
        store.tx(tx -> {
            var verified = tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                    .put("model", "fixture").put("contextTokens", 16000));
            tx.put("meta", "capabilities", object().put("ready", true).put("uniqueCombinations", 4)
                    .put("settingsVersion", verified.path("version").asInt()));
            return null;
        });
        when(sources.styleSamples()).thenReturn(object());
        var mvc = MockMvcBuilders.standaloneSetup(new AssistantController(store,sources,worker,mock(AssistantPublishing.class),mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var owner = session("A", "elidlicom");
        String dashboard = "/api/content-assistant/dashboard";
        String settings = "/api/content-assistant/settings";
        mvc.perform(post(settings).session(owner).header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                .content("{\"version\":1,\"threshold\":99,\"intervalMinutes\":9,\"bindings\":{\"DRAFT\":{\"timeoutSeconds\":120}}}"))
                .andExpect(status().isOk());
        mvc.perform(get(dashboard).session(owner)).andExpect(jsonPath("$.capabilities.ready").value(true))
                .andExpect(jsonPath("$.capabilities.uniqueCombinations").value(4));
        mvc.perform(post(settings).session(owner).header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                .content("{\"version\":2,\"bindings\":{\"DRAFT\":{\"timeoutSeconds\":121}}}"))
                .andExpect(status().isOk());
        mvc.perform(get(dashboard).session(owner)).andExpect(jsonPath("$.capabilities.ready").value(false));
        verifyNoInteractions(models);
    }

    private ObjectNode ingest(String id,String text) {
        return store.tx(tx->{
            var m=object().put("id",id).put("text",text).put("username","source").put("textStatus",text.isBlank()?"NO_ANALYZABLE_TEXT":"TEXT_ONLY");
            AssistantWorkflow.ingest(tx,m);
            return tx.get("material",id);
        });
    }

    @Test void repeatedInputsReuseWorkAndResearchSurvivesDraftingAndBackup() throws Exception {
        ingest("p","有文字的原创"); ingest("p","有文字的原创"); ingest("video","");
        store.tx(tx->{
            assertThat(tx.all("task")).hasSize(1);
            assertThat(tx.all("topic")).hasSize(1);
            tx.put("meta","settings",AssistantWorkflow.settings(tx).put("model","offline").put("contextTokens",32000));
            tx.put("meta","style",object().put("profile","测试口吻"));
            return null;
        });
        var body=object().put("materialId","p").put("clientRequestId","one");
        var accepted=store.tx(t->AssistantWorkflow.research(t,body));
        var duplicate=store.tx(t->AssistantWorkflow.research(t,body.deepCopy().put("clientRequestId","two")));
        assertThat(accepted.path("id")).isEqualTo(duplicate.path("id"));
        assertThatThrownBy(()->store.tx(t->AssistantWorkflow.research(t,body.deepCopy().put("instruction","新数据")))).isInstanceOf(XDataProviderException.class);
        assertThatThrownBy(()->store.tx(t->AssistantWorkflow.research(t,body.deepCopy().put("clientRequestId","three").put("materialId","video")))).isInstanceOf(IllegalArgumentException.class);

        String taskId=accepted.path("id").asText();
        var research=object().put("draftable",true).put("quality","SUPPORTED").put("summary","已经读取的文字证据");
        when(models.run(eq("RESEARCH"),any(),any())).thenReturn(object().set("result",research));
        worker.execute(claim(taskId));
        var next=store.tx(t->t.get("task",taskId));
        assertThat(next.path("stage").asText()).isEqualTo("DRAFT");
        assertThat(next.path("research").path("snapshot").path("materials").get(0).path("text").asText()).isEqualTo("有文字的原创");
        when(models.run(eq("DRAFT"),any(),any())).thenReturn(object().set("result",object().put("text","初稿")));
        worker.execute(claim(taskId));
        var ready=store.tx(t->t.get("task",taskId));
        String draftId=ready.path("draftId").asText();
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        var edit=object().put("version",1).put("text","人工编辑");
        store.tx(t->AssistantWorkflow.saveDraft(t,draftId,edit));
        assertThatThrownBy(()->store.tx(t->AssistantWorkflow.saveDraft(t,draftId,edit.put("text","另一个标签页")))).isInstanceOf(XDataProviderException.class);
        store.backupDaily();
        Path restored=Files.createDirectory(dir.resolve("restore"));
        try(var files=Files.list(dir.resolve("backups"))) {Files.copy(files.findFirst().orElseThrow(),restored.resolve("assistant.sqlite"));}
        var restoredDraft=new AssistantStore(restored,mapper).tx(t->t.get("draft",draftId));
        assertThat(restoredDraft.path("text").asText()).isEqualTo("人工编辑");
        assertThat(restoredDraft.path("researchId").asText()).isEqualTo(taskId);
    }

    @Test void researchReusesLegacyWorkAcrossUnrelatedSettingsAndKeepsIntentionalReruns() {
        ingest("p", "原始素材");
        store.tx(tx -> {
            var settings = tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                    .put("model", "fixture").put("contextTokens", 16000));
            String topicId = AssistantTopics.topicOf(tx, "p");
            var input = AssistantTopics.snapshot(tx, topicId, "p").put("instruction", "");
            input.set("preferences", AssistantWorkflow.preferences(tx));
            var task = object().put("kind", "RESEARCH").put("stage", "RESEARCH").put("status", "QUEUED")
                    .put("topicId", topicId).put("materialId", "p");
            task.set("input", input);
            task.set("settings", settings);
            tx.put("task", "legacy-research", task);
            var identity = object().put("rules", 1).put("kind", "RESEARCH");
            identity.set("input", input);
            identity.set("settings", settings);
            tx.put("fingerprint", AssistantWorkflow.digest(identity), object().put("taskId", "legacy-research"));
            var changed = settings.deepCopy().put("threshold", 99).put("intervalMinutes", 9);
            changed.putObject("bindings").putObject("SCORE").put("model", "different-score-model");
            tx.put("meta", "settings", changed);
            tx.put("topic", topicId, tx.get("topic", topicId).put("score", 75));
            return null;
        });
        var body = object().put("materialId", "p").put("clientRequestId", "regular");
        var reused = store.tx(tx -> AssistantWorkflow.research(tx, body));
        assertThat(reused.path("id").asText()).isEqualTo("legacy-research");
        store.tx(tx -> {
            var settings = AssistantWorkflow.settings(tx);
            settings.withObject("/bindings").putObject("RESEARCH").put("model", "different-research-model");
            tx.put("meta", "settings", settings);
            return null;
        });
        var rebound = store.tx(tx -> AssistantWorkflow.research(tx, body.deepCopy().put("clientRequestId", "new-binding")));
        assertThat(rebound.path("id")).isNotEqualTo(reused.path("id"));
        var manual = body.deepCopy().put("clientRequestId", "intentional").put("regenerate", true);
        var rerun = store.tx(tx -> AssistantWorkflow.research(tx, manual));
        assertThat(rerun.path("id")).isNotEqualTo(rebound.path("id"));
        assertThat(store.tx(tx -> AssistantWorkflow.research(tx, manual)).path("id")).isEqualTo(rerun.path("id"));
    }

    @Test void optimizeCorrectsLengthOnceAndKeepsItsOriginalVersion() {
        String baseline = "汉".repeat(199) + "𠀀 and 2026!";
        String first = "短".repeat(122) + "𠀀" + "1! A".repeat(37);
        String revised = "修".repeat(208) + "𠀀，结果。";
        var prompts = new ArrayList<String>();
        useModel((binding, request) -> {
            prompts.add(request.userText());
            assertThat(request.webSearch()).isFalse();
            return modelDraft(prompts.size() == 1 ? first : revised);
        });
        String taskId = optimizeDraft(baseline, "压缩到约 180–240 字，保留证据。");
        worker.execute(claim(taskId));
        var checkpoint = store.tx(tx -> tx.get("task", taskId));
        assertThat(checkpoint.path("status").asText()).isEqualTo("QUEUED");
        assertThat(checkpoint.path("stage").asText()).isEqualTo("OPTIMIZE_REVISION");
        assertThat(checkpoint.path("constraintRepair").path("attempted").asBoolean()).isFalse();
        assertThat(checkpoint.at("/constraintRepair/initial/hanLength/actual").asInt()).isEqualTo(123);
        assertThat(store.tx(tx -> tx.get("draft", "draft")).path("text").asText()).isEqualTo(baseline);

        // Unsent correction survives restart and retains the same original draft and evidence.
        claim(taskId);
        worker.recover();
        assertThat(store.tx(tx -> tx.get("task",taskId)).path("status").asText()).isEqualTo("QUEUED");
        worker.execute(claim(taskId));
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("candidateDraft", baseline, "已有研究", "首稿实测 123 个汉字",
                "至少增加 57 个汉字", "建议目标 210 个");
        var ready = store.tx(tx -> tx.get("task", taskId));
        var draft = store.tx(tx -> tx.get("draft", "draft"));
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(draft.path("text").asText()).isEqualTo(revised);
        assertThat(draft.path("baseVersion").asInt()).isOne();
        assertThat(draft.at("/hanLength/actual").asInt()).isEqualTo(211);
        assertThat(draft.at("/hanLength/baseline").asInt()).isEqualTo(200);
        assertThat(draft.at("/constraintRepair/final/hanLength/met").asBoolean()).isTrue();
        assertThat(store.tx(tx -> tx.version("draft", "draft", 1)).path("text").asText()).isEqualTo(baseline);
    }

    @Test void optimizeStopsAfterOneFailedCorrectionAndKeepsTheCandidateForReview() {
        String baseline = "保留原稿的比例 10%。" + "原".repeat(198);
        String first = "改".repeat(190) + "新增 0.5 分，原比例 10％。";
        String second = "旧".repeat(49) + "仍有 2.0。";
        var prompts = new ArrayList<String>();
        useModel((binding, request) -> {
            prompts.add(request.userText());
            return modelDraft(prompts.size() == 1 ? first : second);
        });
        String taskId = optimizeDraft(baseline, "调整到 180–240 个汉字，不添加新数字。");
        worker.execute(claim(taskId));
        var before = store.tx(tx -> tx.get("task", taskId));
        assertThat(before.at("/constraintRepair/initial/hanLength/met").asBoolean()).isTrue();
        assertThat(before.at("/constraintRepair/initial/numberChanges/added/0").asText()).isEqualTo("0.5");
        worker.execute(claim(taskId));
        var failed = store.tx(tx -> tx.get("task", taskId));
        assertThat(failed.path("status").asText()).isEqualTo("CONSTRAINTS_UNMET");
        assertThat(failed.at("/constraintRepair/final/hanLength/met").asBoolean()).isFalse();
        assertThat(failed.at("/constraintRepair/final/numberChanges/added/0").asText()).isEqualTo("2.0");
        assertThat(failed.at("/result/text").asText()).isEqualTo(second);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("首稿新增了原稿没有的阿拉伯数字或百分比", "0.5");
        assertThat(store.tx(tx -> tx.get("draft", "draft")).path("version").asInt()).isOne();
        assertThatThrownBy(() -> store.tx(tx -> AssistantWorkflow.transition(tx,taskId,"retry")))
                .isInstanceOf(XDataProviderException.class).hasMessageContaining("一次自动修订");
    }

    @Test void optimizeSavesFirstPassWhenBothConstraintsAreMet() {
        String baseline = "原文百分比 10%。";
        String generated = "新".repeat(180) + "保留比例 10％。";
        var prompts = new ArrayList<String>();
        useModel((binding, request) -> {
            prompts.add(request.userText());
            return modelDraft(generated);
        });
        String taskId = optimizeDraft(baseline, "180–240字，不添加新数字。");
        worker.execute(claim(taskId));
        var ready = store.tx(tx -> tx.get("task", taskId));
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.has("constraintRepair")).isFalse();
        assertThat(ready.at("/result/hanLength/met").asBoolean()).isTrue();
        assertThat(ready.at("/result/numberChanges/met").asBoolean()).isTrue();
        assertThat(prompts).hasSize(1);
        assertThat(DraftHanLength.parse("目标 180–240 字符或字节")).isNull();
    }

    @Test void interruptedCorrectionDoesNotReplayAndKeepsTheFirstCandidate() {
        String baseline = "现有原稿 10%。";
        String generated = "新增 0.5 分，保留 10%。";
        var prompts = new ArrayList<String>();
        useModel((binding, request) -> {
            prompts.add(request.userText());
            if (prompts.size() == 2) worker.recover();
            return modelDraft(generated);
        });
        String taskId = optimizeDraft(baseline, "只润色，不添加新数字。");
        worker.execute(claim(taskId));
        var inFlight = claim(taskId);
        worker.execute(inFlight);
        worker.execute(inFlight);
        var interrupted = store.tx(tx -> tx.get("task",taskId));
        assertThat(interrupted.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.at("/constraintRepair/attempted").asBoolean()).isTrue();
        assertThat(interrupted.at("/result/text").asText()).isEqualTo(generated);
        assertThat(store.tx(tx -> tx.get("draft", "draft")).path("version").asInt()).isOne();
        assertThat(prompts).hasSize(2);
        assertThatThrownBy(() -> store.tx(tx -> AssistantWorkflow.transition(tx,taskId,"retry")))
                .isInstanceOf(XDataProviderException.class);
    }

    @Test void historicalNumberChangesUseTheAcceptedBaselineReadOnly() throws Exception {
        String baseline = "保留第1项，比例为 10%。";
        String generated = "FrontierCode 2.0 更新后增加 0.5 分，保留第1项和 10％。";
        String taskId = optimizeDraft(baseline, "只做表达润色，不添加新数字。");
        store.tx(tx -> tx.put("draft", "draft", object().put("text", generated).put("baseDraftId", "draft")
                .put("baseVersion", 1).put("originTaskId", taskId)));
        var mvc = MockMvcBuilders.standaloneSetup(new AssistantController(store,sources,worker,mock(AssistantPublishing.class),mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var owner = session("A", "elidlicom");
        mvc.perform(get("/api/content-assistant/drafts/draft/versions").session(owner))
                .andExpect(jsonPath("$[0].numberChanges.added[0]").value("2.0"))
                .andExpect(jsonPath("$[0].numberChanges.added[1]").value("0.5"))
                .andExpect(jsonPath("$[0].numberChanges.met").value(false))
                .andExpect(jsonPath("$[1].numberChanges").doesNotExist());
        assertThat(store.tx(tx -> tx.get("draft", "draft")).has("numberChanges")).isFalse();
        var edited = store.tx(tx -> AssistantWorkflow.saveDraft(tx, "draft", object().put("version", 2).put("text", baseline)));
        assertThat(edited.path("numberChanges").path("met").asBoolean()).isTrue();
        assertThat(edited.path("numberChanges").path("added").isEmpty()).isTrue();
    }

    private void useModel(TextModelClient model) {
        worker.close();
        var environment = new MockEnvironment().withProperty(AssistantIntelligence.API_KEY_ENV, "fixture");
        worker = new AssistantWorker(store, new AssistantIntelligence(mapper, environment, model, mock(TextSourceReader.class)), sources);
    }

    private TextModelClient.ModelResult modelDraft(String text) {
        var reply = object().put("needsAdditionalResearch", false).put("draft", text);
        reply.putArray("usedClaimIds");
        reply.putArray("caveats");
        return new TextModelClient.ModelResult(reply.toString(), List.of(), null, "offline", object(), Map.of());
    }

    private String optimizeDraft(String baseline, String instruction) {
        ingest("p", "素材");
        return store.tx(tx -> {
            tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("model", "fixture").put("contextTokens", 16000));
            var research = object().put("summary", "已有研究");
            for (String key : List.of("claims", "angles", "unverified")) research.putArray(key);
            var draft = object().put("materialId", "p").put("text", baseline);
            draft.set("research", research);
            tx.put("draft", "draft", draft);
            return AssistantWorkflow.optimize(tx, "draft", object().put("version", 1).put("clientRequestId", "optimize")
                    .put("instruction", instruction)).path("id").asText();
        });
    }

    @Test void crossAuthorMergeAndManualSplitRetainTheResearchSnapshot() {
        store.tx(t->t.put("meta","settings",AssistantWorkflow.settings(t).put("model","fixture").put("contextTokens",12000)));
        ingest("a","同日产品发布，首版有三项能力");
        String target=store.tx(t->AssistantTopics.topicOf(t,"a"));
        store.tx(t->{AssistantWorkflow.ingest(t,object().put("id","b").put("username","another-author").put("text","同日产品发布，新增第四项能力"));return null;});
        var queued=store.tx(t->t.matching("task","materialId","b",1).get(0));
        var decision=object().put("kind","SAME_EVENT_NEW_INFORMATION").put("confidence","HIGH").put("candidateId",target);
        when(models.run(eq("CLASSIFY"),any(),any())).thenReturn(object().set("result",decision));
        worker.execute(claim(queued.path("id").asText()));
        var merged=store.tx(t->t.get("topic",target));
        assertThat(merged.path("materialIds").size()).isEqualTo(2);
        var request=object().put("materialId","a").put("clientRequestId","old-snapshot");
        String task=store.tx(t->AssistantWorkflow.research(t,request)).path("id").asText();
        store.tx(t->t.put("meta","style",object().put("profile","第一版已准备好")));
        var doubleClick=store.tx(t->AssistantWorkflow.research(t,request.deepCopy().put("clientRequestId","other-tab")));
        assertThat(doubleClick.path("id").asText()).isEqualTo(task);

        store.tx(t->AssistantTopics.move(t,List.of("b"),"split",object().put(target,merged.path("version").asInt()),"OWNER"));
        var captured=store.tx(t->t.get("task",task)).path("input");
        assertThat(captured.path("materials").size()).isEqualTo(2);
        var next=store.tx(t->AssistantWorkflow.research(t,request.deepCopy().put("clientRequestId","new-snapshot"))).path("input");
        assertThat(next.path("materials").size()).isOne();
        assertThat(next.path("materials").path(0).path("id").asText()).isEqualTo("a");
    }

    @Test void synthesisTimeoutKeepsReadsAndRetriesWithoutAnotherSearch() {
        ingest("p", "事实素材");
        store.tx(t -> t.put("meta","settings",AssistantWorkflow.settings(t).put("model","fixture").put("contextTokens",12000)));
        var queued = store.tx(t -> AssistantWorkflow.research(t, object()
                .put("materialId","p").put("clientRequestId","checkpoint")));
        String taskId = queued.path("id").asText();
        var reads = object().put("pendingSynthesis",true).put("quality","PARTIAL");
        reads.putArray("sourceReads").addObject().put("id","source-1").put("url","https://example.org/primary").put("status","READ");
        reads.putArray("evidence").addObject().put("id","source-1").put("contentFingerprint","fingerprint");
        when(models.run(eq("RESEARCH"),any(),any())).thenReturn(object().set("result",reads));
        worker.execute(claim(taskId));
        var checkpoint = store.tx(t -> t.get("task",taskId));
        assertThat(checkpoint.path("stage").asText()).isEqualTo("RESEARCH_SYNTHESIS");
        assertThat(checkpoint.path("research").path("sourceReads")).isEqualTo(reads.path("sourceReads"));

        var unknown = new AssistantIntelligence.AssistantIntelligenceException(
                AssistantIntelligence.FailureKind.RESULT_UNKNOWN, false, "fixture", "归纳等待超时", null);
        when(models.run(eq("RESEARCH_SYNTHESIS"),any(),any())).thenThrow(unknown)
                .thenAnswer(invocation -> {
                    com.fasterxml.jackson.databind.node.ObjectNode input = invocation.getArgument(2);
                    ObjectNode checkpointData = input.withObject("/researchReads").deepCopy();
                    checkpointData.remove("pendingSynthesis");
                    return object().set("result", checkpointData.put("quality","PARTIAL").put("draftable",false));
                });
        worker.execute(claim(taskId));
        var interrupted = store.tx(t -> t.get("task",taskId));
        assertThat(interrupted.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.path("research").path("sourceReads")).isEqualTo(reads.path("sourceReads"));
        store.tx(t -> AssistantWorkflow.transition(t,taskId,"retry"));
        worker.execute(claim(taskId));
        verify(models, times(1)).run(eq("RESEARCH"),any(),any());
        verify(models, times(2)).run(eq("RESEARCH_SYNTHESIS"),any(),any());
        var kept = store.tx(t -> t.get("research",taskId));
        assertThat(kept.path("sourceReads")).isEqualTo(reads.path("sourceReads"));
        assertThat(kept.path("evidence")).isEqualTo(reads.path("evidence"));
    }

    @Test void expiredAttemptKeepsItsStageAndRequiresExplicitRetry() {
        ingest("p","original");
        var queued = store.tx(t -> AssistantWorkflow.research(t, object()
                .put("materialId","p").put("clientRequestId","expired")));
        String taskId = queued.path("id").asText();
        var attempt = claim(taskId);
        store.tx(t -> t.put("task", taskId, attempt.put("deadline", 1L)));
        worker.tick();
        var expired = store.tx(t -> t.get("task", taskId));
        assertThat(expired.path("stage").asText()).isEqualTo("RESEARCH");
        assertThat(expired.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(expired.path("error").asText()).contains("上游结果未知");
        worker.execute(attempt);
        com.fasterxml.jackson.databind.node.ObjectNode unexpectedResearch = store.tx(t -> t.get("research", taskId));
        assertThat(unexpectedResearch).isNull();
        var retried = store.tx(t -> AssistantWorkflow.transition(t, taskId, "retry"));
        assertThat(retried.path("status").asText()).isEqualTo("QUEUED");
    }

    @Test void cancelledOrInterruptedAttemptCannotCommitLateResults() {
        ingest("p","original");
        var queued=store.tx(t->{
            var in=object().put("materialId","p").put("clientRequestId","test");
            t.put("meta","settings",AssistantWorkflow.settings(t).put("model","local-test").put("contextTokens",10000));
            return AssistantWorkflow.research(t,in);
        });
        String id=queued.path("id").asText();
        var first=claim(id);
        when(models.run(eq("RESEARCH"),any(),any())).thenAnswer(call->{
            store.tx(t->AssistantWorkflow.transition(t,id,"cancel"));
            return object().set("result",object().put("draftable",true));
        });
        worker.execute(first);
        assertThat(store.tx(t->t.get("task",id)).path("status").asText()).isEqualTo("CANCELLED");
        store.tx(t->AssistantWorkflow.transition(t,id,"retry"));
        claim(id); worker.recover();
        assertThat(store.tx(t->t.get("task",id)).path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat((List<?>)store.tx(t->t.all("draft"))).isEmpty();
    }

    @Test void waitingStyleTaskUnblocksOnFailureZeroSamplesOrManualStyleRevision() throws Exception {
        ingest("p", "原创事件素材");
        store.tx(t -> {
            t.put("meta", "owner", object().put("accountId", "A").put("username", "elidlicom"));
            t.put("meta", "settings", AssistantWorkflow.settings(t).put("model", "fixture").put("contextTokens", 16000));
            return null;
        });
        String taskId = store.tx(t -> AssistantWorkflow.research(t, object()
                .put("materialId", "p").put("clientRequestId", "style-wait"))).path("id").asText();
        when(models.run(eq("RESEARCH"), any(), any())).thenReturn(object().set("result", object().put("draftable", true)));
        worker.execute(claim(taskId));
        assertThat(store.tx(t -> t.get("task", taskId)).path("stage").asText()).isEqualTo("DRAFT");

        var runningCoverage = object();
        runningCoverage.putArray("samples");
        runningCoverage.putObject("coverage").put("exhausted", false).put("running", true).put("error", "");
        when(sources.styleSamples()).thenReturn(runningCoverage);
        worker.tick();
        assertThat(store.tx(t -> t.get("task", taskId)).path("status").asText()).isEqualTo("WAITING_STYLE");

        var emptyCoverage = object();
        emptyCoverage.putArray("samples");
        emptyCoverage.putObject("coverage").put("exhausted", true).put("running", false).put("error", "");
        when(sources.styleSamples()).thenReturn(emptyCoverage);
        worker.collectedStyle();
        assertThat(store.tx(t -> t.get("task", taskId)).path("status").asText()).isEqualTo("FAILED");
        assertThat(store.tx(t -> t.get("task", taskId)).path("error").asText()).contains("无法准备个人风格档案");

        when(sources.styleSamples()).thenReturn(runningCoverage);
        var retried = store.tx(t -> AssistantWorkflow.transition(t, taskId, "retry"));
        assertThat(retried.path("status").asText()).isEqualTo("QUEUED");
        worker.tick();
        assertThat(store.tx(t -> t.get("task", taskId)).path("status").asText()).isEqualTo("WAITING_STYLE");

        var styleSamples = object();
        styleSamples.putArray("samples").addObject().put("id", "s1").put("text", "作者历史推文");
        styleSamples.putObject("coverage").put("exhausted", true).put("running", false).put("error", "");
        when(sources.styleSamples()).thenReturn(styleSamples);
        when(models.run(eq("STYLE"), any(), any())).thenThrow(new XDataProviderException(502, "风格提炼失败"));
        worker.collectedStyle();
        String styleId = store.tx(t -> t.matching("task", "stage", "STYLE", 5).stream()
                .filter(task -> "QUEUED".equals(task.path("status").asText())).findFirst().orElseThrow()).path("id").asText();
        worker.execute(claim(styleId));
        assertThat(store.tx(t -> t.get("task", taskId)).path("status").asText()).isEqualTo("FAILED");

        when(sources.styleSamples()).thenReturn(runningCoverage);
        store.tx(t -> AssistantWorkflow.transition(t, taskId, "retry"));
        worker.tick();
        assertThat(store.tx(t -> t.get("task", taskId)).path("status").asText()).isEqualTo("WAITING_STYLE");

        var mvc = MockMvcBuilders.standaloneSetup(new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var owner = session("A", "elidlicom");
        mvc.perform(post("/api/content-assistant/style").session(owner).header("X-Requested-With", "XMLHttpRequest")
                        .contentType("application/json").content("{\"version\":0,\"profile\":\"简洁克制，重事实证据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editor").value("OWNER"));
        assertThat(store.tx(t -> t.get("task", taskId)).path("status").asText()).isEqualTo("QUEUED");
    }

    @Test void deterministicDuplicateMergeTopicConfirmationPreferencesResetAndDashboardFiltersWork() throws Exception {
        store.tx(tx -> {
            AssistantWorkflow.ingest(tx, object().put("id", "m1").put("username", "author1")
                    .put("text", "OpenAI 发布 GPT-5.4！\n\n支持 1M 上下文").put("textStatus", "TEXT_ONLY").put("publishedAt", 2000L));
            AssistantWorkflow.ingest(tx, object().put("id", "m2").put("username", "author2")
                    .put("text", "  OpenAI 发布 GPT-5.4！ 支持 1M 上下文  ").put("textStatus", "TEXT_ONLY").put("publishedAt", 3000L));
            return null;
        });
        String mergedTopicId = store.tx(tx -> AssistantTopics.topicOf(tx, "m1"));
        String secondTopicId = store.tx(tx -> AssistantTopics.topicOf(tx, "m2"));
        assertThat(secondTopicId).isEqualTo(mergedTopicId);
        var mergedTopic = store.tx(tx -> tx.get("topic", mergedTopicId));
        assertThat(store.tx(tx -> tx.get("membership", "m2")).path("actor").asText()).isEqualTo("DETERMINISTIC_TEXT");
        assertThat(mergedTopic.at("/decision/kind").asText()).isEqualTo("DUPLICATE");
        assertThat(mergedTopic.path("publishedAt").asLong()).isEqualTo(3000L);
        assertThat((List<?>) store.tx(tx -> tx.matching("task", "stage", "CLASSIFY", 5))).isEmpty();

        store.tx(tx -> {
            var confirmTopic = object().put("title", "待确认选题").put("status", "NEEDS_CONFIRMATION")
                    .put("score", 85).put("publishedAt", 1500L);
            confirmTopic.putArray("materialIds").add("m3");
            confirmTopic.putArray("tags").add("AIAgent");
            tx.put("material", "m3", object().put("username", "author3").put("publishedAt", 1500L));
            tx.put("topic", "confirm-me", confirmTopic);
            var updatedMerged = tx.get("topic", mergedTopicId).put("score", 60).put("status", "READY");
            updatedMerged.putArray("tags").add("ModelRelease");
            tx.put("topic", mergedTopicId, updatedMerged);
            return null;
        });

        when(sources.styleSamples()).thenReturn(object());
        var mvc = MockMvcBuilders.standaloneSetup(new AssistantController(store, sources, worker, mock(AssistantPublishing.class), mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var owner = session("A", "elidlicom");
        mvc.perform(get("/api/content-assistant/dashboard").session(owner).param("status", "NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value("confirm-me"));
        mvc.perform(post("/api/content-assistant/topics/confirm-me/confirm").session(owner)
                        .header("X-Requested-With", "XMLHttpRequest").contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCORED"))
                .andExpect(jsonPath("$.needsConfirmation").value(false))
                .andExpect(jsonPath("$.confirmedBy").value("OWNER"));
        mvc.perform(get("/api/content-assistant/dashboard").session(owner).param("status", "RECOMMENDED"))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value("confirm-me"))
                .andExpect(jsonPath("$.needsConfirmationCount").value(0))
                .andExpect(jsonPath("$.draftedTopicCount").value(0))
                .andExpect(jsonPath("$.providerDefaults.provider").isNotEmpty());
        store.tx(tx -> tx.put("draft", "d-1", object().put("topicId", mergedTopicId).put("materialId", "m1").put("text", "初稿")));
        mvc.perform(get("/api/content-assistant/dashboard").session(owner).param("status", "DRAFTED"))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value(mergedTopicId))
                .andExpect(jsonPath("$.draftedTopicCount").value(1));
        when(sources.add(anyString())).thenAnswer(inv -> object().put("username", inv.getArgument(0, String.class)));
        mvc.perform(post("/api/content-assistant/sources").session(owner).header("X-Requested-With", "XMLHttpRequest")
                        .contentType("application/json")
                        .content("{\"input\":\"@OpenAI, https://x.com/OpenAI\\n@AnthropicAI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedCount").value(2));
        mvc.perform(get("/api/content-assistant/dashboard").session(owner).param("tag", "modelrelease"))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value(mergedTopicId));
        mvc.perform(get("/api/content-assistant/dashboard").session(owner).param("sort", "published"))
                .andExpect(jsonPath("$.topics[0].id").value(mergedTopicId))
                .andExpect(jsonPath("$.topics[1].id").value("confirm-me"));
        mvc.perform(get("/api/content-assistant/materials").session(owner).param("sort", "published"))
                .andExpect(jsonPath("$[0].id").value("m2"));

        mvc.perform(post("/api/content-assistant/preferences").session(owner).header("X-Requested-With", "XMLHttpRequest")
                        .contentType("application/json").content("{\"version\":0,\"text\":\"不要用感叹号\"}"))
                .andExpect(jsonPath("$.text").value("不要用感叹号"));
        mvc.perform(post("/api/content-assistant/preferences/reset").session(owner).header("X-Requested-With", "XMLHttpRequest")
                        .contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(""))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test void pastedWebLinkAndXTweetLinkRunResearchAndGenerateTweetDrafts() throws Exception {
        store.tx(tx -> {
            tx.put("meta", "owner", object().put("accountId", "A").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                    .put("enabled", true)
                    .put("modelEnabled", true)
                    .put("provider", "openai")
                    .put("model", "gpt-5.4")
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 128_000));
            tx.put("meta", "style", object().put("profile", "直接给结论与关键数字，不讲套话"));
            return null;
        });

        TextSourceReader linkReader = uri -> {
            if ("https://openai.com/index/gpt-6-release".equals(uri.toString())) {
                return new TextSourceReader.ReadResult(
                        uri,
                        uri,
                        "text/html",
                        "Introducing GPT-6 Developer Platform",
                        "GPT-6 reduces coding agent tool-call latency by 45% and supports 1M token context windows with native repo indexing.",
                        "网页正文段落",
                        Instant.parse("2026-11-26T08:00:00Z"),
                        TextSourceReader.Status.READ,
                        ""
                );
            }
            return new TextSourceReader.ReadResult(
                    uri,
                    uri,
                    "text/html",
                    "Independent Benchmark Report",
                    "Third-party evaluation confirms GPT-6 achieves 45% faster tool-call execution on repository tasks.",
                    "网页正文段落",
                    Instant.parse("2026-11-26T08:05:00Z"),
                    TextSourceReader.Status.READ,
                    ""
            );
        };
        TextModelClient linkModelClient = (binding, request) -> {
            if (request.webSearch()) {
                return new TextModelClient.ModelResult(
                        "{\"summary\":\"Found benchmark verification\"}",
                        List.of(new TextModelClient.SearchResult(
                                "GPT-6 latency benchmark",
                                java.net.URI.create("https://bench.example.org/gpt-6-eval"),
                                "Independent Benchmark Report",
                                "45% faster tool calls",
                                "2026-11-26"
                        )),
                        new TextModelClient.Usage(80L, 40L),
                        "req-search-1",
                        null,
                        Map.of("search", mapper.createObjectNode().put("count", 1))
                );
            }
            String userText = request.userText();
            if (userText.contains("Build a research result")) {
                JsonNode sourcesArr;
                try {
                    sourcesArr = mapper.readTree(userText.substring(userText.indexOf("{\"topic\":"))).path("sources");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                String linkSrcId = "";
                String linkExcId = "";
                String webSrcId = "";
                String webExcId = "";
                for (JsonNode s : sourcesArr) {
                    String sid = s.path("id").asText("");
                    String eid = s.path("excerpts").path(0).path("id").asText("");
                    if (sid.startsWith("material-")) {
                        linkSrcId = sid;
                        linkExcId = eid;
                    } else if (webSrcId.isEmpty()) {
                        webSrcId = sid;
                        webExcId = eid;
                    }
                }
                assertThat(linkSrcId).startsWith("material-link-").endsWith("-link");
                assertThat(webSrcId).isNotBlank();
                String body = """
                        {
                          "summary": "GPT-6 开发者平台将工具调用延迟降低 45%% 并支持 1M 上下文",
                          "claims": [
                            {
                              "claim": "GPT-6 工具调用延迟降低 45%%，并支持 1M 上下文窗口",
                              "relation": "SUPPORTS",
                              "sourceIds": ["%s", "%s"],
                              "excerptIds": ["%s", "%s"]
                            }
                          ],
                          "angles": ["从 Agent 仓库级编码效率切入"],
                          "unverified": []
                        }
                        """.formatted(linkSrcId, webSrcId, linkExcId, webExcId);
                return new TextModelClient.ModelResult(body, List.of(), new TextModelClient.Usage(100L, 60L), "req-synth-1", null, Map.of());
            }
            return new TextModelClient.ModelResult(
                    "{\"draft\":\"GPT-6 开发者平台上线：工具调用延迟降低 45%，原生支持 1M 上下文窗口，跑仓库级 Agent 终于轻快了。\",\"usedClaimIds\":[\"claim-1\"],\"caveats\":[],\"factChanges\":[]}",
                    List.of(),
                    new TextModelClient.Usage(120L, 80L),
                    "req-draft-1",
                    null,
                    Map.of()
            );
        };

        MockEnvironment env = new MockEnvironment()
                .withProperty(AssistantConnection.OPENAI_KEY_ENV, "sk-test-link-key");
        AssistantIntelligence realIntelligence = new AssistantIntelligence(mapper, env, linkModelClient, linkReader);
        AssistantWorker realWorker = new AssistantWorker(store, realIntelligence, sources);
        try {
            AssistantController controller = new AssistantController(store, sources, realWorker, mock(AssistantPublishing.class), mapper, null, env);
            controller.setIntelligence(realIntelligence);
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
            var owner = session("A", "elidlicom");

            // 1) Paste copied text containing an external article URL -> RESEARCH + synthesis + DRAFT
            String linkRespStr = mvc.perform(post("/api/content-assistant/research-link")
                            .session(owner)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("url", "来看看这篇官方发布：https://openai.com/index/gpt-6-release。")
                                    .put("mode", "RESEARCH")
                                    .put("instruction", "突出延迟下降对编码 Agent 的意义")
                                    .put("clientRequestId", "link-req-web-1")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.topicId").isNotEmpty())
                    .andExpect(jsonPath("$.materialId").isNotEmpty())
                    .andExpect(jsonPath("$.sourceUrl").value("https://openai.com/index/gpt-6-release"))
                    .andExpect(jsonPath("$.task.kind").value("RESEARCH"))
                    .andReturn().getResponse().getContentAsString();

            ObjectNode linkResp = (ObjectNode) mapper.readTree(linkRespStr);
            String webTaskId = linkResp.path("task").path("id").asText();
            String webTopicId = linkResp.path("topicId").asText();

            // Execute RESEARCH -> RESEARCH_SYNTHESIS -> DRAFT stages deterministically
            for (int step = 0; step < 3; step++) {
                ObjectNode running = claim(webTaskId);
                realWorker.execute(running);
            }

            ObjectNode finishedWebTask = store.tx(tx -> tx.get("task", webTaskId));
            assertThat(finishedWebTask.path("status").asText())
                    .as(() -> "task=" + finishedWebTask)
                    .isEqualTo("READY");
            String webDraftId = finishedWebTask.path("draftId").asText();
            assertThat(webDraftId).isNotBlank();
            ObjectNode webDraft = store.tx(tx -> tx.get("draft", webDraftId));
            assertThat(webDraft.path("topicId").asText()).isEqualTo(webTopicId);
            assertThat(webDraft.path("text").asText()).contains("45%").contains("1M");

            // 2) Paste an X status URL -> resolves single tweet via SourceCollector & generates draft in FAST mode
            when(sources.fetchTweetMaterialById("1987654321098765432")).thenReturn(object()
                    .put("id", "1987654321098765432")
                    .put("authorId", "9001")
                    .put("username", "karpathy")
                    .put("authorName", "Andrej Karpathy")
                    .put("text", "New minimal C CUDA training loop hits 45% faster step time on single GPU.")
                    .put("textStatus", "TEXT_ONLY")
                    .put("authorTextStatus", "FULL")
                    .put("sourceIdentity", "ROW_ID_VERIFIED")
                    .put("kind", "POST")
                    .put("publishedAt", 1_732_600_000_000L)
                    .put("sourceUrl", "https://x.com/karpathy/status/1987654321098765432"));

            String xLinkRespStr = mvc.perform(post("/api/content-assistant/research-link")
                            .session(owner)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(object()
                                    .put("url", "https://x.com/karpathy/status/1987654321098765432?s=20")
                                    .put("mode", "FAST")
                                    .put("clientRequestId", "link-req-x-1")
                                    .toString()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.materialId").value("1987654321098765432"))
                    .andExpect(jsonPath("$.task.kind").value("DRAFT"))
                    .andReturn().getResponse().getContentAsString();

            ObjectNode xLinkResp = (ObjectNode) mapper.readTree(xLinkRespStr);
            String xTaskId = xLinkResp.path("task").path("id").asText();
            realWorker.execute(claim(xTaskId));
            ObjectNode finishedXTask = store.tx(tx -> tx.get("task", xTaskId));
            assertThat(finishedXTask.path("status").asText()).isEqualTo("READY");
            assertThat(finishedXTask.path("draftId").asText()).isNotBlank();
        } finally {
            realWorker.close();
            realIntelligence.closeReadPool();
        }
    }

    private ObjectNode claim(String id) {
        return store.tx(t->t.put("task",id,t.get("task",id).put("attempt",AssistantWorkflow.id()).put("status","RUNNING")));
    }
}
