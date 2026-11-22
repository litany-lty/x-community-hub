package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import static com.xops.hub.assistant.AssistantStore.object;
import static com.xops.hub.assistant.AssistantWorkflow.*;

@Component
public final class AssistantWorker {
    private final AssistantStore store;
    private final AssistantIntelligence models;
    private final SourceCollector sources;
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"assistant-model");t.setDaemon(true);return t;});
    private final Map<String,FutureTask<Void>> active=new ConcurrentHashMap<>();
    private static final Set<String> BACKGROUND=Set.of("CLASSIFY","SCORE");
    public AssistantWorker(AssistantStore store,AssistantIntelligence models,SourceCollector sources) {
        this.store=store; this.models=models; this.sources=sources;
    }

    @PostConstruct
    void recover() {
        store.tx(tx->{
            var pending=tx.matching("task","status","RUNNING",-1);
            pending.addAll(tx.matching("task","status","WAITING_SAMPLES",-1));
            for(var t:pending) {
                if ("OPTIMIZE_REVISION".equals(t.path("stage").asText())) {
                    if (t.path("constraintRepair").path("attempted").asBoolean()) {
                        t.put("status","INTERRUPTED").put("error","自动修订已开始，重启后结果未知。首次候选和原稿已保留。");
                    } else t.put("status","QUEUED");
                } else if("COLLECT_STYLE".equals(t.path("stage").asText())) t.put("status","QUEUED");
                else t.put("status","INTERRUPTED").put("error","服务已重启，上游结果未知。可从已保存阶段继续。");
                t.remove("attempt"); tx.put("task",t.path("id").asText(),t);
            }
            return null;
        });
    }

    @PreDestroy void close() { workers.shutdownNow(); }
    public void interrupt(String id) { var job=active.get(id); if(job!=null) job.cancel(true); }

    @Scheduled(fixedDelayString="${xops.content-assistant-worker-ms:2500}")
    public synchronized void tick() {
        try {
            if (!store.hasCollectionSpace()) return;
        } catch (IllegalStateException ex) {
            return;
        }
        var expired = store.tx(tx -> {
            long now = System.currentTimeMillis();
            var ids = new ArrayList<String>();
            for (var task : tx.matching("task", "status", "RUNNING", -1)) {
                long deadline = task.path("deadline").asLong();
                if (deadline <= 0 || deadline > now) continue;
                String id = task.path("id").asText();
                String error = task.path("constraintRepair").path("attempted").asBoolean()
                        ? "自动修订已超时，结果未知。首次候选和原稿已保留。"
                        : "执行已超时，上游结果未知。请明确重试以继续本阶段。";
                task.put("status", "INTERRUPTED").put("finishedAt", now)
                        .put("error", error).remove("attempt");
                tx.put("task", id, task);
                ids.add(id);
            }
            return ids;
        });
        expired.forEach(this::interrupt);
        if(active.size()>=2) return;
        var claimed=store.tx(tx->{
            if(tx.get("meta","owner")==null) return List.<ObjectNode>of();
            var config=settings(tx);
            if(!config.path("enabled").asBoolean() || !config.path("modelEnabled").asBoolean()) return List.<ObjectNode>of();
            var jobs=tx.matching("task","status","QUEUED",-1);
            jobs.sort(Comparator.comparing(t->BACKGROUND.contains(t.path("kind").asText())));
            boolean background=tx.matching("task","status","RUNNING",2).stream().anyMatch(t->BACKGROUND.contains(t.path("kind").asText()));
            List<ObjectNode> selected=new ArrayList<>();
            for(var task:jobs) {
                if(selected.size()+active.size()>=2) break;
                String stage=task.path("stage").asText();
                if(active.containsKey(task.path("id").asText())) continue;
                if(BACKGROUND.contains(stage)) { if(background) continue; background=true; }
                if(stage.equals("DRAFT") && !task.path("input").path("style").isObject()) {
                    var style=tx.get("meta","style");
                    if(style==null) {
                        task.put("status","WAITING_STYLE");
                        tx.put("task",task.path("id").asText(),task);
                        continue;
                    }
                    ((ObjectNode)task.path("input")).set("style",style);
                }
                long start=System.currentTimeMillis();
                var storedInput = (ObjectNode) task.path("input");
                long end = start + stageLeaseMillis(task.path("settings"), stage);
                if ("RESEARCH_SYNTHESIS".equals(stage)) {
                    end = Math.min(end, storedInput.path("synthesisDeadline").asLong(end));
                    if (end <= start) {
                        task.put("status","INTERRUPTED").put("finishedAt",start)
                                .put("error","研究期限已结束，已保存读取资料。请重试以继续归纳。");
                        tx.put("task", task.path("id").asText(), task);
                        continue;
                    }
                    storedInput.put("synthesisDeadline", end);
                }
                task.put("status","RUNNING").put("attempt",id()).put("startedAt",start).put("deadline",end);
                selected.add(tx.put("task",task.path("id").asText(),task));
            }
            return selected;
        });
        for(var task:claimed) {
            String id=task.path("id").asText();
            var f = new FutureTask<Void>(() -> { execute(task); return null; });
            active.put(id,f);
            workers.execute(() -> {
                try { f.run(); }
                finally { active.remove(id, f); }
            });
        }
    }

    static long stageLeaseMillis(JsonNode settings, String stage) {
        String provider = settings.path("provider").asText("commandcode");
        return switch (stage) {
            case "RESEARCH", "RESEARCH_SYNTHESIS" -> Math.max(30_000L,
                    AssistantIntelligence.roleBinding(settings, AssistantIntelligence.Stage.RESEARCH, provider).timeout() * 1000L);
            case "STYLE", "COLLECT_STYLE" -> Math.max(600_000L,
                    AssistantIntelligence.roleBinding(settings, AssistantIntelligence.Stage.STYLE, provider).timeout() * 1000L);
            case "VERIFY" -> Math.max(240_000L,
                    AssistantIntelligence.roleBinding(settings, AssistantIntelligence.Stage.CLASSIFY, provider).timeout() * 2000L);
            case "OPTIMIZE_REVISION" -> Math.max(30_000L,
                    AssistantIntelligence.roleBinding(settings, AssistantIntelligence.Stage.OPTIMIZE, provider).timeout() * 1000L + 15_000L);
            default -> {
                AssistantIntelligence.Stage role = AssistantIntelligence.Stage.valueOf(stage);
                yield Math.max(30_000L, AssistantIntelligence.roleBinding(settings, role, provider).timeout() * 1000L + 15_000L);
            }
        };
    }

    // Consumers of collection results only compare source text/IDs. Collection timestamps do not trigger model spend.
    @Scheduled(fixedDelayString="${xops.content-assistant-style-check-ms:30000}")
    public void collectedStyle() {
        var enabled=store.tx(t->t.get("meta","owner")!=null && settings(t).path("enabled").asBoolean() && settings(t).path("modelEnabled").asBoolean());
        if(!enabled) return;
        var samples=sources.styleSamples();
        if(samples.path("coverage").path("running").asBoolean()) return;
        var cleaned=object(); var rows=cleaned.putArray("samples");
        samples.path("samples").forEach(s->rows.add(object().put("id",s.path("id").asText()).put("text",s.path("text").asText())));
        String hash=digest(rows);
        store.tx(tx->{
            var waiting=tx.matching("task","status","WAITING_SAMPLES",-1);
            var waitingStyle=tx.matching("task","status","WAITING_STYLE",-1);
            var style=tx.get("meta","style");
            var marker=tx.get("meta","sampleHash");
            ObjectNode extraction=null;
            if(!rows.isEmpty() && (marker==null || !hash.equals(marker.path("hash").asText()) || !waiting.isEmpty())) {
                cleaned.put("sampleHash",hash);
                extraction=enqueue(tx,"STYLE",cleaned,"",null);
                if(marker==null || !marker.path("taskId").equals(extraction.path("id")))
                    tx.put("meta","sampleHash",object().put("hash",hash).put("taskId",extraction.path("id").asText()));
            } else if (!rows.isEmpty() && style == null && !waitingStyle.isEmpty()) {
                if (marker != null && !marker.path("taskId").asText("").isBlank()) {
                    extraction = tx.get("task", marker.path("taskId").asText());
                }
                if (extraction == null) {
                    cleaned.put("sampleHash", hash);
                    extraction = enqueue(tx, "STYLE", cleaned, "", null);
                    tx.put("meta", "sampleHash", object().put("hash", hash).put("taskId", extraction.path("id").asText()));
                }
            }
            for(var task:waiting) {
                if(rows.isEmpty()) {
                    task.put("status","FAILED").put("error",samples.path("coverage").path("error").asText("").isBlank()
                            ?"尚未取得本人的合格原创文字":samples.path("coverage").path("error").asText());
                } else if(style!=null && hash.equals(style.path("sampleHash").asText())) {
                    task.put("status","READY").set("result",style);
                } else if(extraction!=null && Set.of("FAILED","INTERRUPTED","BLOCKED_CONFIG","CANCELLED").contains(extraction.path("status").asText())) {
                    task.put("status",extraction.path("status").asText()).put("error",extraction.path("error").asText())
                            .put("styleTaskId",extraction.path("id").asText());
                } else if(extraction!=null && !task.path("styleTaskId").equals(extraction.path("id"))) {
                    task.put("styleTaskId",extraction.path("id").asText());
                } else continue;
                tx.put("task",task.path("id").asText(),task);
            }
            String coverageError = samples.path("coverage").path("error").asText("");
            boolean sampleTerminal = rows.isEmpty() && (samples.path("coverage").path("exhausted").asBoolean() || !coverageError.isBlank());
            for (var draftTask : waitingStyle) {
                if (style != null) {
                    draftTask.put("status", "QUEUED").remove("error");
                } else if (sampleTerminal) {
                    draftTask.put("status", "FAILED").put("error", "无法准备个人风格档案："
                            + (coverageError.isBlank() ? "尚未取得本人的合格原创文字" : coverageError));
                } else if (extraction != null && Set.of("FAILED", "INTERRUPTED", "BLOCKED_CONFIG", "CANCELLED").contains(extraction.path("status").asText())) {
                    draftTask.put("status", extraction.path("status").asText())
                            .put("error", "风格档案提炼未完成：" + extraction.path("error").asText("阶段执行失败"))
                            .put("styleTaskId", extraction.path("id").asText());
                } else continue;
                tx.put("task", draftTask.path("id").asText(), draftTask);
            }
            return null;
        });
    }

    void execute(ObjectNode task) {
        String stage=task.path("stage").asText();
        try {
            if("COLLECT_STYLE".equals(stage)) {
                sources.refreshStyleSamples();
                commit(task,(tx,now)->now.put("status","WAITING_SAMPLES"));
                return;
            }
            var current=store.tx(tx->tx.get("task",task.path("id").asText()));
            if(current==null || !current.path("attempt").equals(task.path("attempt")) || !"RUNNING".equals(current.path("status").asText())) return;
            var config=(ObjectNode)task.path("settings");
            var server=store.tx(tx->tx.get("server","connection"));
            if(server!=null && config.path("connectionVersion").asInt()!=server.path("version").asInt())
                throw new MissingConfiguration("服务端连接已更换，请明确重试以采用新配置");
            var input=(ObjectNode)task.path("input");
            if(config.path("model").asText("").isBlank() || config.path("contextTokens").asInt()<=0)
                throw new MissingConfiguration("请配置模型名称及可用上下文长度");
            if ("OPTIMIZE_REVISION".equals(stage) && !reserveCorrection(task)) return;
            var output=models.run(stage,config,input);
            commit(task,(tx,now)->complete(tx,now,stage,output));
        } catch(Exception ex) {
            String name=ex.getClass().getSimpleName();
            String status=ex instanceof MissingConfiguration || name.contains("Config") ? "BLOCKED_CONFIG"
                    : name.contains("Unknown") || name.contains("Interrupted") ? "INTERRUPTED":"FAILED";
            if(ex instanceof AssistantIntelligence.AssistantIntelligenceException failure) {
                status=switch(failure.kind()) {case MISSING_CONFIG -> "BLOCKED_CONFIG"; case RESULT_UNKNOWN -> "INTERRUPTED"; case DEFINITE_FAILURE -> "FAILED";};
            }
            String finalStatus=status;
            String message=ex.getMessage();
            commit(task,(tx,now)->{
                String reason = message == null ? "阶段执行失败" : message;
                now.put("status",finalStatus).put("error",reason);
                if ("STYLE".equals(stage) && tx.get("meta", "style") == null) {
                    for (var waiting : tx.matching("task", "status", "WAITING_STYLE", -1)) {
                        tx.put("task", waiting.path("id").asText(), waiting
                                .put("status", finalStatus)
                                .put("error", "风格档案提炼未完成：" + reason)
                                .put("styleTaskId", now.path("id").asText()));
                    }
                }
            });
        }
    }

    private boolean reserveCorrection(ObjectNode task) {
        return store.tx(tx -> {
            String id = task.path("id").asText();
            var now = tx.get("task", id);
            if (now == null || !"RUNNING".equals(now.path("status").asText())
                    || !now.path("attempt").equals(task.path("attempt"))
                    || now.path("constraintRepair").path("attempted").asBoolean()) return false;
            now.withObject("/constraintRepair").put("attempted", true);
            tx.put("task", id, now);
            return true;
        });
    }

    private interface Commit { void apply(AssistantStore.Tx tx,ObjectNode task); }
    private void commit(ObjectNode attempt,Commit action) {
        store.tx(tx->{
            String id=attempt.path("id").asText();
            var now=tx.get("task",id);
            if(now==null || !"RUNNING".equals(now.path("status").asText()) || !now.path("attempt").equals(attempt.path("attempt"))) return null;
            action.apply(tx,now);
            now.put("finishedAt",System.currentTimeMillis());
            now.remove("attempt");
            tx.put("task",id,now);
            return null;
        });
    }

    private void complete(AssistantStore.Tx tx,ObjectNode task,String stage,ObjectNode output) {
        var result=(ObjectNode)output.path("result");
        var input=(ObjectNode)task.path("input");
        task.set("result",result);
        task.withObject("/execution").set(stage,output.deepCopy().without("result"));
        task.put("status","READY");
        switch(stage) {
            case "VERIFY" -> {
                result.put("settingsVersion",ver(task.path("settings")));
                tx.put("meta","capabilities",result);
            }
            case "CLASSIFY" -> classify(tx,task,input,result,output);
            case "SCORE" -> scoreResult(tx,task,input,result);
            case "STYLE" -> {
                var marker=tx.get("meta","sampleHash");
                if(marker!=null && !marker.path("hash").equals(input.path("sampleHash"))) break;
                result.put("sampleHash",input.path("sampleHash").asText()).put("learnedCount",result.path("processedSampleIds").size())
                        .put("sampleCount",input.path("samples").size());
                tx.put("meta","style",result);
                for(var waiting:tx.matching("task","status","WAITING_STYLE",-1))
                    tx.put("task",waiting.path("id").asText(),waiting.put("status","QUEUED"));
            }
            case "RESEARCH", "RESEARCH_SYNTHESIS" -> {
                var research=result.deepCopy();
                if ("RESEARCH".equals(stage) && research.path("articleBackfills").isObject()) {
                    applyArticleBackfills(tx, input, research.path("articleBackfills"));
                    research.remove("articleBackfills");
                }
                research.set("snapshot", input.deepCopy().without(List.of(
                        "style", "preferences", "research", "researchReads", "synthesisDeadline")));
                if ("RESEARCH".equals(stage)) AssistantEvidence.record(tx,research);
                var saved=tx.put("research",task.path("id").asText(),research);
                task.set("research",saved);
                if (result.path("pendingSynthesis").asBoolean()) {
                    input.set("researchReads", saved.deepCopy().without("snapshot"));
                    if (task.has("deadline")) input.put("synthesisDeadline", task.path("deadline").asLong());
                    task.put("stage", "RESEARCH_SYNTHESIS").put("status", "QUEUED");
                    break;
                }
                var currentTopic = tx.get("topic", input.path("topicId").asText());
                if (ver(currentTopic) == input.path("topicVersion").asInt() && "SUPPORTED".equals(result.path("quality").asText())) {
                    var scoring = AssistantTopics.snapshot(tx, input.path("topicId").asText(), input.path("selectedMaterialId").asText())
                            .put("researchVerified", true);
                    scoring.set("research", object().set("claims", result.path("claims")));
                    enqueue(tx,"SCORE",scoring,"",null);
                }
                input.set("research",saved);
                if (result.path("draftable").asBoolean()) input.remove(List.of("researchReads", "synthesisDeadline"));
                if(!result.path("draftable").asBoolean(false)) {
                    String synthesisError = result.path("synthesisError").asText();
                    task.put("status","RESEARCH_PARTIAL").put("error", synthesisError.isBlank()
                            ? "当前证据不足以成稿，已保存取得的资料"
                            : "研究归纳失败，已保存取得的资料：" + synthesisError);
                } else {
                    task.put("stage","DRAFT").put("status","QUEUED");
                    if(input.path("style").isMissingNode() || input.path("style").isNull()) {
                        if(tx.get("meta","style")==null) enqueue(tx,"COLLECT_STYLE",object(),"",null);
                    }
                }
            }
            case "OPTIMIZE","DRAFT","OPTIMIZE_REVISION" -> {
                if(result.path("needsAdditionalResearch").asBoolean()) {
                    input.set("questions",result.path("researchQuestions"));
                    input.remove(List.of("researchReads", "synthesisDeadline"));
                    task.put("stage","RESEARCH").put("status","QUEUED");
                    break;
                }
                if ("OPTIMIZE".equals(task.path("kind").asText()) && !checkRevision(task,input,result,stage)) break;
                var draft=object().put("text",field(result,"text")).put("topicId",input.path("topicId").asText())
                        .put("topicVersion",input.path("topicVersion").asInt()).put("materialId",input.path("selectedMaterialId").asText())
                        .put("styleVersion",ver(input.path("style"))).put("preferenceVersion",ver(input.path("preferences")))
                        .put("researchId",input.path("research").path("id").asText()).put("originTaskId",task.path("id").asText()).put("editor","MODEL");
                draft.set("research",input.path("research"));
                draft.set("generation",output);
                draft.set("factChanges",result.path("factChanges"));
                draft.set("usedClaims",result.path("usedClaims"));
                if (result.path("hanLength").isObject()) draft.set("hanLength",result.get("hanLength"));
                if (result.path("numberChanges").isObject()) draft.set("numberChanges",result.get("numberChanges"));
                if (task.path("constraintRepair").isObject()) draft.set("constraintRepair",task.get("constraintRepair"));
                String target=input.path("draftId").asText("");
                var base=target.isEmpty()?null:tx.get("draft",target);
                if(base!=null) draft.put("baseDraftId",target).put("baseVersion",input.path("draftVersion").asInt());
                if(base==null || ver(base)!=input.path("draftVersion").asInt()) target=id();
                var saved=tx.put("draft",target,draft);
                task.put("draftId",target).put("draftVersion",ver(saved));
            }
            default -> throw new IllegalArgumentException("未知任务阶段");
        }
    }

    private boolean checkRevision(ObjectNode task, ObjectNode input, ObjectNode result, String stage) {
        var checks = DraftRevisionConstraints.checks(result);
        if ("OPTIMIZE_REVISION".equals(stage)) {
            task.withObject("/constraintRepair").set("final", checks);
            input.remove("correctionCandidate");
            if (DraftRevisionConstraints.unmet(checks)) {
                task.put("status", "CONSTRAINTS_UNMET").put("error", "自动修订后仍未满足要求，候选稿已保留，原稿未变更。");
                return false;
            }
            if (field(result,"text").strip().equals(input.path("text").asText().strip())) {
                task.put("status", "CONSTRAINTS_UNMET").put("error", "自动修订仅返回原稿，未产生可保存的新版本。");
                return false;
            }
        } else if (DraftRevisionConstraints.unmet(checks)) {
            task.putObject("constraintRepair").put("attempted", false).set("initial", checks);
            input.set("correctionCandidate", result);
            task.put("stage", "OPTIMIZE_REVISION").put("status", "QUEUED");
            return false;
        }
        return true;
    }

    private void classify(AssistantStore.Tx tx,ObjectNode task,ObjectNode input,ObjectNode decision,ObjectNode execution) {
        String mid=input.path("materialId").asText(), own=input.path("topicId").asText();
        var material=tx.get("material",mid);
        if(ver(material)!=ver(input.path("material")) || !own.equals(AssistantTopics.topicOf(tx,mid))
                || ver(tx.get("membership",mid))!=input.path("membershipVersion").asInt()) return;
        var topic=AssistantTopics.required(tx,"topic",own);
        if (input.has("topicVersion") && ver(topic) != input.path("topicVersion").asInt()) {
            input.put("topicVersion", ver(topic));
            input.set("candidates", AssistantTopics.candidates(tx,material,own));
            task.put("status","QUEUED");
            return;
        }
        var dest=tx.get("topic",decision.path("candidateId").asText(""));
        String kind=decision.path("kind").asText("");
        boolean merge=Set.of("CONFLICT","DUPLICATE","SAME_EVENT_NEW_INFORMATION").contains(kind)
                && !decision.path("confidence").asText().equals("LOW") && dest!=null && !own.equals(dest.path("id").asText());
        if(merge) {
            String target=dest.path("id").asText();
            boolean stillCurrent=false;
            for(var c:input.path("candidates")) if(target.equals(c.path("id").asText()) && ver(c)==ver(dest)) stillCurrent=true;
            if(!stillCurrent) {
                input.set("candidates",AssistantTopics.candidates(tx,material,own));
                task.put("status","QUEUED"); return;
            }
            recordDecision(tx, task, input, decision, execution, target);
            var expected=object().put(own,ver(topic)).put(target,ver(dest));
            var changed=AssistantTopics.move(tx,List.of(mid),target,expected,"MODEL");
            for(String id:changed) {
                var affected=AssistantTopics.required(tx,"topic",id);
                if(target.equals(id)) {
                    if("DUPLICATE".equals(kind) && dest.has("score")) {
                        dest.set("materialIds",affected.path("materialIds"));
                        dest.set("decision",decision); AssistantTopics.persist(tx,id,dest);
                        continue;
                    }
                    affected.set("decision",decision); AssistantTopics.persist(tx,id,affected);
                }
                score(tx,id);
            }
        } else {
            recordDecision(tx, task, input, decision, execution, own);
            topic.set("decision",decision);
            topic.put("needsConfirmation","UNCERTAIN".equals(kind) || "LOW".equals(decision.path("confidence").asText()));
            AssistantTopics.persist(tx,own,topic);
            score(tx,own);
        }
    }

    private void recordDecision(AssistantStore.Tx tx, ObjectNode task, ObjectNode input,
                                ObjectNode decision, ObjectNode execution, String resultingTopicId) {
        var record = decision.deepCopy().put("materialId", input.path("materialId").asText())
                .put("materialVersion", ver(input.path("material")))
                .put("topicId", resultingTopicId).put("previousTopicId", input.path("topicId").asText());
        record.set("candidates", input.path("candidates"));
        if (execution.has("model")) record.set("model", execution.path("model"));
        tx.put("merge_decision", task.path("id").asText(), record);
    }

    private void scoreResult(AssistantStore.Tx tx,ObjectNode task,ObjectNode input,ObjectNode result) {
        String id=input.path("topicId").asText();
        var t=AssistantTopics.required(tx,"topic",id);
        if(ver(t)!=input.path("topicVersion").asInt()) return;
        int weight=task.path("settings").path("valueWeight").asInt(50);
        double v=result.path("topicValue").asDouble(), r=result.path("reachPotential").asDouble();
        long composite = Math.round((v * weight + r * (100 - weight)) / 100);
        result.put("composite", composite);
        t.put("valueScore",v).put("reachScore",r).put("score",composite)
                .put("reason",String.join(" · ",firstReason(result,"topicReasons"),firstReason(result,"reachReasons")))
                .put("status",t.path("needsConfirmation").asBoolean()?"NEEDS_CONFIRMATION":"SCORED");
        if(result.hasNonNull("summary")) t.put("summary",result.path("summary").asText());
        if(result.hasNonNull("title")) t.put("title",result.path("title").asText());
        if(result.has("tags")) t.set("tags",result.path("tags"));
        t.set("assessment",result);
        AssistantTopics.persist(tx,id,t);
    }
    private static String firstReason(JsonNode n,String key) { return n.path(key).path(0).asText(""); }

    private static void applyArticleBackfills(AssistantStore.Tx tx, ObjectNode input, JsonNode backfills) {
        backfills.fields().forEachRemaining(entry -> {
            String materialId = entry.getKey();
            JsonNode update = entry.getValue();
            String fullText = update.path("articleText").asText("");
            if (fullText.isBlank()) return;
            ObjectNode stored = tx.get("material", materialId);
            if (stored != null && "PREVIEW".equals(stored.path("articleTextStatus").asText())) {
                ObjectNode upgraded = stored.deepCopy().put("articleText", fullText).put("articleTextStatus", "FULL");
                if ("ARTICLE_PREVIEW".equals(upgraded.path("textStatus").asText())) upgraded.put("textStatus", "ARTICLE_FULL");
                if ("AUTHOR".equals(upgraded.path("articleRole").asText()) && "PREVIEW".equals(upgraded.path("authorTextStatus").asText())) {
                    upgraded.put("authorTextStatus", "FULL");
                }
                ObjectNode content = object();
                for (String key : List.of("authorId", "username", "text", "quotedText", "articleText", "sourceUrl", "textStatus",
                        "authorTextStatus", "quotedTextStatus", "articleTextStatus", "articleRole", "publishedAt",
                        "quotedId", "quotedAuthorId", "quotedUsername", "articleId", "articleUrl")) {
                    if (upgraded.has(key)) content.set(key, upgraded.get(key));
                }
                upgraded.put("contentFingerprint", AssistantWorkflow.digest(content));
                ObjectNode saved = tx.put("material", materialId, upgraded);
                var members = input.path("materials");
                if (members instanceof ArrayNode arr) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (materialId.equals(arr.get(i).path("id").asText())) arr.set(i, saved);
                    }
                }
            }
        });
    }

    private static class MissingConfiguration extends RuntimeException { MissingConfiguration(String msg){ super(msg); } }
}
