package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.xops.hub.assistant.AssistantIntelligence.Stage;
import com.xops.hub.provider.XDataProviderException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.xops.hub.assistant.AssistantStore.object;
import static com.xops.hub.assistant.AssistantTopics.required;

public final class AssistantWorkflow {
    private static final int FINGERPRINT_RULES = 2;

    private AssistantWorkflow() {}

    static String digest(JsonNode input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(input).toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            var sorted=object();
            var keys=new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            for(var key:keys) sorted.set(key,canonical(node.get(key)));
            return sorted;
        }
        if(node.isArray()) {
            var a=JsonNodeFactory.instance.arrayNode();
            node.forEach(child->a.add(canonical(child)));
            return a;
        }
        return node;
    }

    static ObjectNode settings(AssistantStore.Tx tx) {
        var saved=tx.get("meta","settings");
        if(saved!=null) return saved;
        return object().put("version",0).put("enabled",true).put("collectionEnabled",true).put("modelEnabled",true)
                .put("intervalMinutes",5).put("valueWeight",50).put("threshold",80)
                .put("model","").put("protocol","RESPONSES").put("contextTokens",0)
                .put("researchModel","").put("researchProtocol","RESPONSES").put("researchContextTokens",0);
    }

    static ObjectNode preferences(AssistantStore.Tx tx) {
        var value=tx.get("meta","preferences");
        return value==null?object().put("version",0).put("text",""):value;
    }

    static int ver(JsonNode v) { return v==null?0:v.path("version").asInt(); }
    static String id() { return UUID.randomUUID().toString(); }

    static void expect(JsonNode value,JsonNode body) {
        if(!body.path("version").isIntegralNumber() || ver(value)!=body.path("version").asInt())
            throw new XDataProviderException(409,"另一个页面已保存新版本，请保留当前文字并重新载入");
    }

    static String field(JsonNode body,String key) {
        String value=body.path(key).asText("").strip();
        if(value.isBlank()) throw new IllegalArgumentException("缺少 "+key);
        return value;
    }

    public static void ingest(AssistantStore.Tx tx,ObjectNode source) {
        String materialId=field(source,"id");
        var previous=tx.get("material",materialId);
        var content=object();
        for(String key:List.of("authorId","username","text","quotedText","articleText","sourceUrl","textStatus","authorTextStatus","quotedTextStatus","articleTextStatus","articleRole","publishedAt","quotedId","quotedAuthorId","quotedUsername","articleId","articleUrl"))
            if(source.has(key)) content.set(key,source.get(key));
        String fingerprint=digest(content);
        if(previous!=null && fingerprint.equals(previous.path("contentFingerprint").asText())) return;
        long now = System.currentTimeMillis();
        long firstCollectedAt = previous != null && previous.path("collectedAt").asLong(0) > 0
                ? previous.path("collectedAt").asLong() : source.path("collectedAt").asLong(now);
        var saved=tx.put("material",materialId,source.deepCopy()
                .put("contentFingerprint",fingerprint)
                .put("collectedAt",firstCollectedAt)
                .put("lastCollectedAt",now));
        String own=AssistantTopics.topicOf(tx,materialId);
        boolean analyzable = !AssistantTopics.text(saved).isBlank()
                && !"NO_ANALYZABLE_TEXT".equals(saved.path("textStatus").asText());
        if(!analyzable && own.isBlank()) return;
        if(own.isBlank()) {
            String exactDuplicate = AssistantTopics.findExactDuplicateTopic(tx, saved, "");
            if (!exactDuplicate.isEmpty()) {
                var target = required(tx, "topic", exactDuplicate);
                var members = target.withArray("materialIds");
                boolean present = false;
                for (JsonNode item : members) if (materialId.equals(item.asText())) present = true;
                if (!present) members.add(materialId);
                tx.put("membership", materialId, object().put("topicId", exactDuplicate).put("actor", "DETERMINISTIC_TEXT"));
                var decision = object().put("kind", "DUPLICATE").put("confidence", "HIGH")
                        .put("candidateId", exactDuplicate).put("reason", "规范化正文与既有选题素材一致，已直接归并来源");
                target.set("decision", decision);
                AssistantTopics.persist(tx, exactDuplicate, target);
                tx.put("merge_decision", "deterministic-" + materialId + "-v" + ver(saved),
                        decision.deepCopy().put("materialId", materialId).put("materialVersion", ver(saved))
                                .put("topicId", exactDuplicate).put("previousTopicId", ""));
                if (!target.has("score")) score(tx, exactDuplicate);
                return;
            }
            own=id();
            tx.put("membership",materialId,object().put("topicId",own));
            AssistantTopics.persist(tx,own,AssistantTopics.fresh(own,saved));
        } else {
            var topic=required(tx,"topic",own);
            AssistantTopics.persist(tx,own,topic.put("status","PENDING"));
        }
        if(analyzable) {
            var candidates = AssistantTopics.candidates(tx,saved,own);
            if (candidates.isEmpty()) {
                var topic = required(tx,"topic",own);
                var decision = object().put("kind","NEW_TOPIC").put("confidence","HIGH")
                        .put("reason","当前无其他历史候选选题，直接建立独立选题");
                topic.set("decision", decision);
                AssistantTopics.persist(tx, own, topic);
                score(tx, own);
                return;
            }
            var input=object().put("topicId",own).put("materialId",materialId);
            input.put("membershipVersion",ver(tx.get("membership",materialId)))
                    .put("topicVersion",ver(required(tx,"topic",own)));
            input.set("material",saved);
            input.set("candidates",candidates);
            enqueue(tx,"CLASSIFY",input,"",null);
        }
    }

    static ObjectNode enqueue(AssistantStore.Tx tx,String kind,ObjectNode input,String requestKey,ObjectNode requestBody) {
        var settings=settings(tx);
        String alias=kind+"/"+requestKey;
        String reqHash=requestBody==null?"":digest(requestBody);
        if(!requestKey.isEmpty()) {
            var old=tx.get("accept",alias);
            if(old!=null) {
                if(!reqHash.equals(old.path("digest").asText())) throw new XDataProviderException(409,"同一请求标识已用于不同内容");
                return required(tx,"task",old.path("taskId").asText());
            }
        }
        String fp = taskFingerprint(kind,input,settings);
        ObjectNode task = fingerprintTask(tx,kind,input,fp,0);
        int styleVersion = ver(input.path("style"));
        if (task == null && kind.equals("RESEARCH") && styleVersion > 0) {
            String awaiting = taskFingerprint(kind,input.deepCopy().without("style"),settings);
            task = fingerprintTask(tx,kind,input,awaiting,styleVersion);
        }
        if (task == null) {
            String key=id();
            task=object().put("kind",kind).put("stage",kind).put("status","QUEUED").put("createdAt",System.currentTimeMillis())
                    .put("fingerprintRules",FINGERPRINT_RULES);
            task.put("topicId",input.path("topicId").asText("")).put("materialId",input.path("materialId").asText(input.path("selectedMaterialId").asText("")))
                    .put("draftId",input.path("draftId").asText(""));
            task.set("input",input);
            task.set("settings",settings);
            task=tx.put("task",key,task);
            tx.put("fingerprint",fp,object().put("taskId",key));
        }
        if(!requestKey.isEmpty()) tx.put("accept",alias,object().put("digest",reqHash).put("taskId",task.path("id").asText()));
        return task;
    }

    private static String taskFingerprint(String kind, JsonNode input, JsonNode settings) {
        var identity = object().put("rules",FINGERPRINT_RULES).put("kind",kind);
        if (usesConstraintRepair(kind,input)) identity.put("constraintRepair",1);
        var stages = switch (kind) {
            case "COLLECT_STYLE", "VERIFY" -> List.<Stage>of();
            case "RESEARCH" -> List.of(Stage.RESEARCH,Stage.DRAFT);
            case "OPTIMIZE" -> List.of(Stage.OPTIMIZE,Stage.RESEARCH,Stage.DRAFT);
            default -> List.of(Stage.valueOf(kind));
        };
        if (!stages.isEmpty()) identity.setAll(effectiveBindings(settings, stages));
        if (kind.equals("SCORE")) {
            identity.put("valueWeight",settings.path("valueWeight").asInt(50));
            var weights = ContentIntelligenceEngine.scoreWeights(settings.path("scoreWeights"));
            var scoreWeights = identity.putObject("scoreWeights");
            var topicWeights = scoreWeights.putObject("topic");
            var reachWeights = scoreWeights.putObject("reach");
            weights.topic().forEach(topicWeights::put);
            weights.reach().forEach(reachWeights::put);
        }
        if (kind.equals("RESEARCH")) {
            ObjectNode content = ((ObjectNode)input).deepCopy().without("topicVersion");
            var topic = content.putObject("topic");
            for (String key : List.of("id", "title", "summary")) topic.put(key,input.path("topic").path(key).asText());
            identity.set("input",content);
        } else identity.set("input",input);
        return digest(identity);
    }

    private static ObjectNode effectiveBindings(JsonNode settings, List<Stage> stages) {
        String provider = settings.path("provider").asText("commandcode");
        var effective = object().put("provider",provider).put("connectionVersion",settings.path("connectionVersion").asLong());
        var bindings = effective.putObject("bindings");
        for (Stage role : stages) bindings.set(role.name(), modelConfig(settings,role,provider));
        return effective;
    }

    private static ObjectNode modelConfig(JsonNode settings, Stage role, String provider) {
        var binding = AssistantIntelligence.roleBinding(settings,role,provider);
        return object().put("model",binding.model()).put("protocol",binding.protocol())
                .put("context",binding.context()).put("timeout",binding.timeout()).put("maxOutput",binding.maxOutput());
    }

    static ObjectNode capabilities(AssistantStore.Tx tx, ObjectNode current) {
        var capability = tx.get("meta", "capabilities");
        if (capability != null) {
            int verifiedVersion = capability.path("settingsVersion").asInt(-1);
            if (verifiedVersion == ver(current)) return capability;
            var verified = tx.version("meta", "settings", verifiedVersion);
            if (verified != null && capabilityBindings(verified).equals(capabilityBindings(current))) return capability;
        }
        return object().put("ready", false);
    }

    private static ObjectNode capabilityBindings(ObjectNode settings) {
        var effective = effectiveBindings(settings,AssistantIntelligence.VERIFY_ROLES);
        effective.set("main",modelConfig(AssistantIntelligence.mainBindingSettings(settings),Stage.CLASSIFY,
                effective.path("provider").asText()));
        return effective;
    }

    private static ObjectNode fingerprintTask(AssistantStore.Tx tx, String kind, ObjectNode input, String fingerprint, int styleVersion) {
        var index = tx.get("fingerprint",fingerprint);
        if (index != null) {
            var task = required(tx,"task",index.path("taskId").asText());
            return matchesStyle(task,styleVersion) ? task : null;
        }
        if (input.has("newAttempt") || input.has("refreshId") || usesConstraintRepair(kind,input)) return null;

        // The first immutable revision retains accepted inputs even after a stage adds research or style.
        String scopeField = kind.equals("OPTIMIZE") ? "draftId" : "materialId";
        String scope = input.path(scopeField).asText(input.path("selectedMaterialId").asText());
        for (var legacy : tx.legacyTaskInputs(kind,scopeField,scope)) {
            if (!fingerprint.equals(taskFingerprint(kind,legacy.path("input"),legacy.path("settings")))) continue;
            String taskId = legacy.path("id").asText();
            var task = required(tx,"task",taskId);
            if (!matchesStyle(task,styleVersion)) continue;
            if (styleVersion == 0) tx.put("fingerprint",fingerprint,object().put("taskId",taskId));
            return task;
        }
        return null;
    }

    private static boolean usesConstraintRepair(String kind, JsonNode input) {
        return "OPTIMIZE".equals(kind) && DraftRevisionConstraints.from(input.path("instruction").asText()).requested();
    }

    private static boolean matchesStyle(ObjectNode task, int styleVersion) {
        int boundStyle = ver(task.path("input").path("style"));
        return styleVersion == 0 || boundStyle == 0 || boundStyle == styleVersion;
    }

    // Check request identity before resolving mutable versions (including a freshly saved draft).
    static ObjectNode replay(AssistantStore.Tx tx,String kind,ObjectNode body) {
        String key=field(body,"clientRequestId");
        if(key.length()>128) throw new IllegalArgumentException("请求标识过长");
        var row=tx.get("accept",kind+"/"+key);
        if(row==null) return null;
        if(!digest(body).equals(row.path("digest").asText())) throw new XDataProviderException(409,"同一请求标识已用于不同内容");
        return required(tx,"task",row.path("taskId").asText());
    }

    static ObjectNode research(AssistantStore.Tx tx,ObjectNode body) {
        var replay=replay(tx,"RESEARCH",body);
        if(replay!=null) return replay;
        String mid=field(body,"materialId");
        if(AssistantTopics.text(required(tx,"material",mid)).isBlank()) throw new IllegalArgumentException("该素材没有可分析文字");
        String topic=AssistantTopics.topicOf(tx,mid);
        var input=AssistantTopics.snapshot(tx,topic,mid);
        input.put("instruction",body.path("instruction").asText(""));
        if (body.path("regenerate").asBoolean()) {
            input.put("newAttempt",field(body,"clientRequestId"));
            var previous = previousResearch(tx, input);
            if (previous != null) input.set("previousResearch", previous);
        }
        var style=tx.get("meta","style");
        if(style!=null) input.set("style",style);
        input.set("preferences",preferences(tx));
        return enqueue(tx,"RESEARCH",input,field(body,"clientRequestId"),body);
    }

    private static ObjectNode previousResearch(AssistantStore.Tx tx, ObjectNode input) {
        String id = input.path("selectedMaterialId").asText();
        List<ObjectNode> attempts = tx.matching("task", "materialId", id, -1);
        for (int i = attempts.size() - 1; i >= 0; i--) {
            var prior = tx.get("research", attempts.get(i).path("id").asText());
            if (prior == null || prior.path("sourceReads").isEmpty()) continue;
            JsonNode snapshot = prior.path("snapshot");
            if (input.path("topicId").equals(snapshot.path("topicId"))
                    && memberIds(input).containsAll(memberIds(snapshot))) {
                return object().set("sourceReads", prior.path("sourceReads"));
            }
        }
        return null;
    }

    private static Set<String> memberIds(JsonNode snapshot) {
        var ids = new HashSet<String>();
        snapshot.path("materials").forEach(material -> ids.add(material.path("id").asText()));
        return ids;
    }

    static ObjectNode saveDraft(AssistantStore.Tx tx,String id,ObjectNode body) {
        var draft=required(tx,"draft",id);
        expect(draft,body);
        if(!body.path("text").isTextual()) throw new IllegalArgumentException("稿件正文无效");
        String text=body.get("text").asText();
        if(text.equals(draft.path("text").asText())) return DraftNumberChanges.withReport(tx,draft);
        draft.put("text",text).put("editor","USER");
        if (draft.path("hanLength") instanceof ObjectNode report) DraftHanLength.updateReport(report, text);
        draft.remove("numberChanges");
        DraftNumberChanges.recalculate(tx,draft);
        return tx.put("draft",id,draft);
    }

    static ObjectNode optimize(AssistantStore.Tx tx,String id,ObjectNode body) {
        var request=body.deepCopy().put("draftId",id);
        var replay=replay(tx,"OPTIMIZE",request);
        if(replay!=null) return replay;
        var draft=required(tx,"draft",id);
        expect(draft,body);
        var input=AssistantTopics.snapshot(tx,AssistantTopics.topicOf(tx,draft.path("materialId").asText()),draft.path("materialId").asText());
        input.put("draftId",id).put("text",draft.path("text").asText()).put("draftVersion",ver(draft)).put("instruction",field(body,"instruction"));
        input.set("research",draft.path("research"));
        if (memberIds(input).containsAll(memberIds(draft.path("research").path("snapshot")))) {
            var prior = previousResearch(tx, input);
            if (prior != null) input.set("previousResearch", prior);
        }
        input.set("preferences",preferences(tx));
        input.set("style",tx.get("meta","style"));
        return enqueue(tx,"OPTIMIZE",input,field(request,"clientRequestId"),request);
    }

    static ObjectNode requestTask(AssistantStore.Tx tx,String kind,ObjectNode body,ObjectNode input) {
        var existing=replay(tx,kind,body);
        if(existing!=null) return existing;
        // A new verification or refresh is an explicit request to check the current upstream state.
        input.put("refreshId",field(body,"clientRequestId"));
        return enqueue(tx,kind,input,field(body,"clientRequestId"),body);
    }

    static ObjectNode transition(AssistantStore.Tx tx,String id,String action) {
        var task=required(tx,"task",id);
        String status=task.path("status").asText();
        if("cancel".equals(action)) {
            if(Set.of("READY","CONSTRAINTS_UNMET").contains(status)) throw new XDataProviderException(409,"任务已完成");
            task.put("status","CANCELLED");
        } else if("retry".equals(action)) {
            if(task.path("constraintRepair").path("attempted").asBoolean())
                throw new XDataProviderException(409,"该任务已启动过一次自动修订");
            if(!Set.of("FAILED","INTERRUPTED","BLOCKED_CONFIG","RESEARCH_PARTIAL","CANCELLED","WAITING_STYLE","WAITING_SAMPLES").contains(status))
                throw new XDataProviderException(409,"当前任务不能重试");
            task.set("settings",settings(tx));
            var input = (ObjectNode)task.path("input");
            if ("RESEARCH_SYNTHESIS".equals(task.path("stage").asText())) {
                input.remove("synthesisDeadline");
            } else if ("RESEARCH_PARTIAL".equals(status)) {
                var prior = previousResearch(tx, input);
                if (prior != null) input.set("previousResearch", prior);
            }
            if ("DRAFT".equals(task.path("stage").asText()) && tx.get("meta", "style") == null) {
                var refresh = object().put("refreshId", "retry-" + id + "-" + System.currentTimeMillis());
                enqueue(tx, "COLLECT_STYLE", refresh, "", null);
            }
            task.put("status","QUEUED").remove("error");
        } else throw new IllegalArgumentException("任务操作无效");
        task.remove("attempt");
        return tx.put("task",id,task);
    }

    static ObjectNode reviseStyle(AssistantStore.Tx tx, ObjectNode body) {
        if (!body.path("profile").isTextual() || body.path("profile").asText().isBlank()) {
            throw new IllegalArgumentException("风格概括不能为空");
        }
        var current = tx.get("meta", "style");
        if (current != null && body.has("version")) expect(current, body);
        var next = current == null ? object() : current.deepCopy();
        next.put("profile", body.path("profile").asText().strip())
                .put("editor", "OWNER")
                .put("revisedAt", System.currentTimeMillis());
        var saved = tx.put("meta", "style", next);
        for (var waiting : tx.matching("task", "status", "WAITING_STYLE", -1)) {
            waiting.put("status", "QUEUED");
            waiting.remove("error");
            tx.put("task", waiting.path("id").asText(), waiting);
        }
        return saved;
    }

    static ObjectNode resetPreferences(AssistantStore.Tx tx, ObjectNode body) {
        var current = preferences(tx);
        if (body != null && body.has("version")) expect(current, body);
        return tx.put("meta", "preferences", object().put("text", "").put("resetAt", System.currentTimeMillis()));
    }

    static ObjectNode confirmTopic(AssistantStore.Tx tx, String topicId, ObjectNode body) {
        var topic = required(tx, "topic", topicId);
        if (body != null && body.has("version")) expect(topic, body);
        topic.put("needsConfirmation", false)
                .put("confirmedBy", "OWNER")
                .put("confirmedAt", System.currentTimeMillis());
        if ("NEEDS_CONFIRMATION".equals(topic.path("status").asText())) {
            topic.put("status", topic.has("score") ? "SCORED" : "PENDING");
        }
        return AssistantTopics.persist(tx, topicId, topic);
    }

    static void score(AssistantStore.Tx tx,String topicId) {
        var t=required(tx,"topic",topicId);
        if(t.path("materialIds").isEmpty()) return;
        enqueue(tx,"SCORE",AssistantTopics.snapshot(tx,topicId,t.path("materialIds").get(0).asText()),"",null);
    }

    static ObjectNode publicTask(ObjectNode task) {
        var safe=task.deepCopy();
        safe.remove(List.of("input","settings","attempt","fingerprintRules"));
        return safe;
    }
}
