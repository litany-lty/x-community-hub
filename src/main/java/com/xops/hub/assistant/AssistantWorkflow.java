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
    private static final int MAX_WEB_LINK_CODE_POINTS = 40_000;
    private static final java.util.regex.Pattern EMBEDDED_HTTP_URL = java.util.regex.Pattern.compile("https?://[^\\s<>\"'“”‘’]+");
    private static final java.util.regex.Pattern X_STATUS_URL = java.util.regex.Pattern.compile(
            "(?i)^https?://(?:(?:www|mobile)\\.)?(?:x|twitter|fxtwitter|vxtwitter)\\.com/(?:[A-Za-z0-9_]{1,15}|i(?:/web)?)/status/([0-9]{2,20})(?:[/?#].*)?$");

    record ParsedLinkInput(String normalizedUrl, java.net.URI uri, String xTweetId) { }

    static ParsedLinkInput parseLinkInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("请粘贴需要调研的链接");
        }
        var matcher = EMBEDDED_HTTP_URL.matcher(rawInput.strip());
        if (!matcher.find()) {
            throw new IllegalArgumentException("请输入以 http:// 或 https:// 开头的有效链接");
        }
        String candidate = matcher.group();
        while (!candidate.isEmpty()) {
            char tail = candidate.charAt(candidate.length() - 1);
            if (".,;!?，。！？；：、】》」』".indexOf(tail) >= 0
                    || (tail == ')' && countChar(candidate, '(') < countChar(candidate, ')'))
                    || (tail == '）' && countChar(candidate, '（') < countChar(candidate, '）'))) {
                candidate = candidate.substring(0, candidate.length() - 1);
            } else {
                break;
            }
        }
        String normalized = AssistantEvidence.normalizedUrl(candidate);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("链接格式无效，请提供公开可访问的 http/https 链接");
        }
        var xMatcher = X_STATUS_URL.matcher(normalized);
        String tweetId = xMatcher.matches() ? xMatcher.group(1) : null;
        return new ParsedLinkInput(normalized, java.net.URI.create(normalized), tweetId);
    }

    private static int countChar(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    static ObjectNode buildWebLinkMaterial(String normalizedUrl, TextSourceReader.ReadResult read, long nowMs) {
        String finalUrl = read.finalUri() != null
                ? AssistantEvidence.normalizedUrl(read.finalUri().toString())
                : normalizedUrl;
        if (finalUrl.isBlank()) finalUrl = normalizedUrl;
        String canonicalIdKey = AssistantEvidence.normalizedUrl(normalizedUrl);
        String materialId = "link-" + digest(TextNode.valueOf(canonicalIdKey)).substring(0, 20);
        String host = "";
        try {
            String h = java.net.URI.create(finalUrl).getHost();
            if (h != null) host = h.replaceFirst("(?i)^www\\.", "");
        } catch (IllegalArgumentException ignored) { }
        if (host.isBlank()) host = "web";

        String rawTitle = read.title() == null ? "" : read.title().replaceAll("\\s+", " ").strip();
        String rawBody = read.text() == null ? "" : read.text().strip();
        String cappedBody = limitCodePoints(rawBody, MAX_WEB_LINK_CODE_POINTS);
        String headline = !rawTitle.isBlank()
                ? limitCodePoints(rawTitle, 180)
                : firstParagraphPreview(cappedBody, 120);
        if (headline.isBlank()) headline = finalUrl;

        return object()
                .put("id", materialId)
                .put("authorId", "web:" + host.toLowerCase(Locale.ROOT))
                .put("username", host)
                .put("authorName", !rawTitle.isBlank() ? limitCodePoints(rawTitle, 80) : host)
                .put("authorAvatarUrl", "")
                .put("authorVerified", false)
                .put("text", headline)
                .put("quotedText", "")
                .put("articleText", cappedBody)
                .put("articleRole", "AUTHOR")
                .put("articleUrl", finalUrl)
                .put("sourceUrl", finalUrl)
                .put("textStatus", "ARTICLE_FULL")
                .put("authorTextStatus", "FULL")
                .put("quotedTextStatus", "NOT_PRESENT")
                .put("articleTextStatus", "FULL")
                .put("sourceIdentity", "USER_LINK")
                .put("kind", "POST")
                .put("publishedAt", nowMs)
                .put("collectedAt", nowMs);
    }

    static String ingestDirectLinkMaterial(AssistantStore.Tx tx, ObjectNode source) {
        String materialId = field(source, "id");
        ObjectNode previous = tx.get("material", materialId);
        ObjectNode content = object();
        for (String key : List.of("authorId", "username", "text", "quotedText", "articleText", "sourceUrl", "textStatus",
                "authorTextStatus", "quotedTextStatus", "articleTextStatus", "articleRole", "publishedAt",
                "quotedId", "quotedAuthorId", "quotedUsername", "articleId", "articleUrl")) {
            if (source.has(key)) content.set(key, source.get(key));
        }
        String fingerprint = digest(content);
        long now = System.currentTimeMillis();
        long firstCollectedAt = previous != null && previous.path("collectedAt").asLong(0) > 0
                ? previous.path("collectedAt").asLong()
                : source.path("collectedAt").asLong(now);
        ObjectNode saved = (previous != null && fingerprint.equals(previous.path("contentFingerprint").asText("")))
                ? previous
                : tx.put("material", materialId, source.deepCopy()
                        .put("contentFingerprint", fingerprint)
                        .put("collectedAt", firstCollectedAt)
                        .put("lastCollectedAt", now));
        if (AssistantTopics.text(saved).isBlank() || "NO_ANALYZABLE_TEXT".equals(saved.path("textStatus").asText(""))) {
            throw new IllegalArgumentException("该链接不含可分析的正文内容");
        }

        String topicId = AssistantTopics.topicOf(tx, materialId);
        if (topicId.isBlank()) {
            topicId = id();
            tx.put("membership", materialId, object().put("topicId", topicId).put("actor", "OWNER_LINK"));
            ObjectNode topic = AssistantTopics.fresh(topicId, saved);
            String titleCandidate = !saved.path("authorName").asText("").isBlank()
                    && "USER_LINK".equals(saved.path("sourceIdentity").asText(""))
                    ? saved.path("authorName").asText("")
                    : saved.path("text").asText("");
            if (!titleCandidate.isBlank()) {
                topic.put("title", limitCodePoints(titleCandidate.replaceAll("\\s+", " ").strip(), 100));
            }
            String summaryCandidate = !saved.path("articleText").asText("").isBlank()
                    ? firstParagraphPreview(saved.path("articleText").asText(""), 140)
                    : firstParagraphPreview(saved.path("text").asText(""), 140);
            topic.put("summary", summaryCandidate)
                    .put("status", "SCORED")
                    .put("score", 85)
                    .put("valueScore", 85.0)
                    .put("reachScore", 85.0)
                    .put("reason", "用户指定链接调研选题");
            topic.set("decision", object()
                    .put("kind", "NEW_TOPIC")
                    .put("confidence", "HIGH")
                    .put("reason", "来自用户复制提交的链接素材"));
            AssistantTopics.persist(tx, topicId, topic);
        } else {
            ObjectNode existingTopic = required(tx, "topic", topicId);
            if ("USER_LINK".equals(saved.path("sourceIdentity").asText(""))) {
                String summaryCandidate = firstParagraphPreview(saved.path("articleText").asText(""), 140);
                if (!summaryCandidate.isBlank() && existingTopic.path("summary").asText("").isBlank()) {
                    existingTopic.put("summary", summaryCandidate);
                    AssistantTopics.persist(tx, topicId, existingTopic);
                }
            }
        }
        return topicId;
    }

    private static String limitCodePoints(String text, int maxCodePoints) {
        if (text == null || text.isEmpty()) return "";
        int count = text.codePointCount(0, text.length());
        if (count <= maxCodePoints) return text;
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }

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

    public static boolean isPostPolishEnabled(JsonNode settings) {
        return settings == null || !settings.has("postPolishEnabled")
                || settings.path("postPolishEnabled").asBoolean(true);
    }

    public static String resolvePostPolishPrompt(JsonNode settings) {
        if (settings != null && settings.has("postPolishPrompt")) {
            return OwnerQuoteStyleDistiller.normalizePolishPrompt(settings.path("postPolishPrompt").asText(""));
        }
        return OwnerQuoteStyleDistiller.DEFAULT_POST_POLISH_PROMPT;
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
        if(previous!=null && fingerprint.equals(previous.path("contentFingerprint").asText())) {
            boolean needAuthorBackfill = (!source.path("authorAvatarUrl").asText("").isBlank()
                    && previous.path("authorAvatarUrl").asText("").isBlank())
                    || (!source.path("authorName").asText("").isBlank()
                    && previous.path("authorName").asText("").isBlank())
                    || (!source.path("authorBio").asText("").isBlank()
                    && previous.path("authorBio").asText("").isBlank());
            if (needAuthorBackfill) {
                tx.put("material", materialId, previous.deepCopy()
                        .put("authorName", source.path("authorName").asText(previous.path("authorName").asText("")))
                        .put("authorAvatarUrl", source.path("authorAvatarUrl").asText(previous.path("authorAvatarUrl").asText("")))
                        .put("authorBio", source.path("authorBio").asText(previous.path("authorBio").asText("")))
                        .put("authorVerified", source.path("authorVerified").asBoolean(previous.path("authorVerified").asBoolean(false))));
            }
            return;
        }
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
        if (kind.equals("RESEARCH") || kind.equals("DRAFT")) {
            ObjectNode content = ((ObjectNode)input).deepCopy().without(List.of(
                    "topicVersion", "research", "winningHint", "baseSkillId", "baseAngleInstruction"));
            if (content.path("skillId").asText("").isBlank()) {
                List<JsonNode> matList = new ArrayList<>();
                input.path("materials").forEach(matList::add);
                String rec = ViralWritingSkills.recommendSkill(input.path("topic"), matList);
                if (!rec.isEmpty()) content.put("skillId", rec);
            }
            var topic = content.putObject("topic");
            for (String key : List.of("id", "title", "summary")) topic.put(key,input.path("topic").path(key).asText());
            identity.set("input",content);
        } else if (input instanceof ObjectNode obj
                && (obj.has("winningHint") || obj.has("baseSkillId") || obj.has("baseAngleInstruction"))) {
            identity.set("input", obj.deepCopy().without(List.of("winningHint", "baseSkillId", "baseAngleInstruction")));
        } else identity.set("input",input);
        return digest(identity);
    }

    private static ObjectNode effectiveBindings(JsonNode settings, List<Stage> stages) {
        String provider = settings.path("provider").asText("commandcode");
        var effective = object().put("provider",provider).put("connectionVersion",settings.path("connectionVersion").asLong());
        if (settings.has("moduleConfigs")) {
            effective.set("moduleConfigs", settings.path("moduleConfigs").deepCopy());
        }
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
        String mode = body.path("mode").asText("RESEARCH").strip().toUpperCase(Locale.ROOT);
        boolean fastMode = "FAST".equals(mode);
        var replay=replay(tx, fastMode ? "DRAFT" : "RESEARCH", body);
        if(replay!=null) return replay;
        String mid=field(body,"materialId");
        var primaryMaterial = required(tx,"material",mid);
        if(AssistantTopics.text(primaryMaterial).isBlank()) throw new IllegalArgumentException("该素材没有可分析文字");
        String topic=AssistantTopics.topicOf(tx,mid);
        var input=AssistantTopics.snapshot(tx,topic,mid);
        String instruction = body.path("instruction").asText("").strip();
        if (instruction.codePointCount(0, instruction.length()) > 500) {
            throw new IllegalArgumentException("切入角度过长（最多 500 字）");
        }
        input.put("instruction", instruction);
        String rawSkill = body.path("skillId").asText("").strip();
        String skillId;
        if (rawSkill.isEmpty() || "AUTO".equalsIgnoreCase(rawSkill)) {
            List<JsonNode> matList = new ArrayList<>();
            input.path("materials").forEach(matList::add);
            skillId = ViralWritingSkills.recommendSkill(input.path("topic"), matList);
        } else {
            skillId = ViralWritingSkills.normalizeId(rawSkill);
        }
        if (!skillId.isEmpty()) input.put("skillId", skillId);
        var style=tx.get("meta","style");
        if(style!=null) input.set("style",style);
        input.set("preferences",preferences(tx));
        String winningHint = buildWinningObservationHint(tx);
        if (!winningHint.isEmpty()) input.put("winningHint", winningHint);
        applyRuntimeGenerationTarget(body, input);
        if (fastMode) {
            input.put("fastMode", true);
            if (body.path("regenerate").asBoolean()) {
                input.put("newAttempt", field(body, "clientRequestId"));
            }
            ObjectNode directResearch = buildDirectMaterialResearch(tx, input);
            ObjectNode priorRich = findLatestRichResearch(tx, topic, mid);
            if (priorRich != null) {
                mergeRichResearchInto(directResearch, priorRich);
                String rid = directResearch.path("id").asText("");
                if (!rid.isBlank()) {
                    directResearch = tx.put("research", rid, directResearch);
                }
            }
            input.set("research", directResearch);
            return enqueue(tx, "DRAFT", input, field(body, "clientRequestId"), body);
        }
        if (body.path("regenerate").asBoolean()) {
            input.put("newAttempt",field(body,"clientRequestId"));
            var previous = previousResearch(tx, input);
            if (previous != null) input.set("previousResearch", previous);
        }
        return enqueue(tx,"RESEARCH",input,field(body,"clientRequestId"),body);
    }

    private static ObjectNode buildDirectMaterialResearch(AssistantStore.Tx tx, ObjectNode snapshotInput) {
        String selectedMaterialId = snapshotInput.path("selectedMaterialId").asText("");
        JsonNode topicNode = snapshotInput.path("topic");
        String summary = !topicNode.path("summary").asText("").isBlank()
                ? topicNode.path("summary").asText()
                : topicNode.path("title").asText("已采集 X 一手素材");
        var research = object()
                .put("quality", "MATERIAL_DIRECT")
                .put("draftable", true)
                .put("fastMode", true)
                .put("summary", summary);
        var claims = research.putArray("claims");
        var matEv = research.putArray("materialEvidence");
        List<JsonNode> orderedMaterials = new ArrayList<>();
        List<JsonNode> secondaryMaterials = new ArrayList<>();
        for (JsonNode mat : snapshotInput.path("materials")) {
            if (!selectedMaterialId.isBlank() && selectedMaterialId.equals(mat.path("id").asText(""))) {
                orderedMaterials.add(mat);
            } else {
                secondaryMaterials.add(mat);
            }
        }
        orderedMaterials.addAll(secondaryMaterials);
        for (JsonNode mat : orderedMaterials) {
            String matId = mat.path("id").asText("");
            if (matId.isBlank() || AssistantTopics.text(mat).isBlank()) continue;
            String rawUname = mat.path("username").asText("").strip();
            String uname = rawUname.startsWith("@") ? rawUname.substring(1) : rawUname;
            if (uname.isBlank()) uname = "source";
            String authorName = mat.path("authorName").asText("").strip();
            String publisher = authorName.isBlank() ? ("@" + uname) : (authorName + " (@" + uname + ")");
            String text = formatDirectMaterialText(mat, uname);
            if (text.isBlank()) continue;
            String srcId = "material-" + matId + "-author";
            String excId = srcId + "#p1";
            String postUrl = !mat.path("sourceUrl").asText("").isBlank()
                    ? mat.path("sourceUrl").asText()
                    : ("https://x.com/" + uname + "/status/" + matId);
            var ev = matEv.addObject()
                    .put("id", srcId)
                    .put("url", postUrl)
                    .put("publisher", publisher)
                    .put("status", "MATERIAL_FULL");
            ev.putArray("excerpts").addObject().put("id", excId).put("text", text);
            var claim = claims.addObject()
                    .put("claim", text)
                    .put("relation", "SUPPORTS");
            claim.putArray("sourceIds").add(srcId);
            claim.putArray("excerptIds").add(excId);
        }
        research.putArray("angles");
        research.putArray("unverified");
        research.putArray("sourceReads");
        research.set("snapshot", snapshotInput.deepCopy().without(List.of("style", "preferences", "research")));
        return tx.put("research", "fast-" + id(), research);
    }

    private static String formatDirectMaterialText(JsonNode mat, String uname) {
        List<String> parts = new ArrayList<>();
        String authorText = mat.path("text").asText("").strip();
        if (!authorText.isBlank()) {
            parts.add("@" + uname + "：" + authorText);
        }
        String quotedText = mat.path("quotedText").asText("").strip();
        if (!quotedText.isBlank()) {
            String rawQuotedUser = mat.path("quotedUsername").asText("").strip();
            String quotedUser = rawQuotedUser.startsWith("@") ? rawQuotedUser.substring(1) : rawQuotedUser;
            String quotePrefix = quotedUser.isBlank() ? "引用：" : ("引用 @" + quotedUser + "：");
            parts.add(quotePrefix + quotedText);
        }
        String articleText = mat.path("articleText").asText("").strip();
        if (!articleText.isBlank()) {
            parts.add("长文全文：" + articleText);
        }
        return String.join("\n", parts);
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
        String skillId = ViralWritingSkills.normalizeId(body.path("skillId").asText(""));
        String instruction = body.path("instruction").asText("").strip();
        if (instruction.isBlank() && !skillId.isEmpty()) {
            instruction = "按所选预置爆款体裁重写新版本：摒弃平铺直叙的复述与总结腔，结合深度研究与一手参考资料换出更有流量钩子与评论讨论度的话题切口，严格保留已核实事实与数据";
        }
        if (instruction.isBlank()) {
            throw new IllegalArgumentException("缺少 instruction");
        }
        String matId = draft.path("materialId").asText("");
        String topicId = AssistantTopics.topicOf(tx, matId);
        if (topicId.isBlank()) topicId = draft.path("topicId").asText("");
        var input=AssistantTopics.snapshot(tx, topicId, matId);
        input.put("draftId",id).put("text",draft.path("text").asText()).put("draftVersion",ver(draft)).put("instruction",instruction);
        if (!skillId.isEmpty()) input.put("skillId", skillId);
        String baseSkillId = ViralWritingSkills.normalizeId(draft.path("skillId").asText(""));
        if (!baseSkillId.isEmpty()) input.put("baseSkillId", baseSkillId);
        String baseAngle = draft.path("angleInstruction").asText("").strip();
        if (!baseAngle.isBlank()) input.put("baseAngleInstruction", baseAngle);
        ObjectNode resolvedResearch = resolveResearchForOptimize(tx, draft, input, topicId, matId);
        input.set("research", resolvedResearch);
        if (memberIds(input).containsAll(memberIds(resolvedResearch.path("snapshot")))) {
            var prior = previousResearch(tx, input);
            if (prior != null) input.set("previousResearch", prior);
        }
        input.set("preferences",preferences(tx));
        input.set("style",tx.get("meta","style"));
        String winningHint = buildWinningObservationHint(tx);
        if (!winningHint.isEmpty()) input.put("winningHint", winningHint);
        applyRuntimeGenerationTarget(body, input);
        return enqueue(tx,"OPTIMIZE",input,field(request,"clientRequestId"),request);
    }

    private static ObjectNode resolveResearchForOptimize(
            AssistantStore.Tx tx, ObjectNode draft, ObjectNode snapshotInput, String topicId, String materialId) {
        ObjectNode base = null;
        if (draft.path("research").isObject() && !draft.path("research").isEmpty()) {
            base = ((ObjectNode) draft.path("research")).deepCopy();
        } else {
            String researchId = draft.path("researchId").asText("").strip();
            if (!researchId.isBlank()) {
                ObjectNode stored = tx.get("research", researchId);
                if (stored != null) base = stored.deepCopy();
            }
        }
        ObjectNode latestRich = findLatestRichResearch(tx, topicId, materialId);
        if (base == null) {
            if (latestRich != null) return latestRich;
            return buildEphemeralMaterialResearch(snapshotInput);
        }
        if (latestRich != null) {
            mergeRichResearchInto(base, latestRich);
        }
        return base;
    }

    static ObjectNode findLatestRichResearch(AssistantStore.Tx tx, String topicId, String selectedMaterialId) {
        List<ObjectNode> candidateTasks = new ArrayList<>();
        if (topicId != null && !topicId.isBlank()) {
            candidateTasks.addAll(tx.matching("task", "topicId", topicId, -1));
        }
        if (candidateTasks.isEmpty() && selectedMaterialId != null && !selectedMaterialId.isBlank()) {
            candidateTasks.addAll(tx.matching("task", "materialId", selectedMaterialId, -1));
        }
        for (int i = candidateTasks.size() - 1; i >= 0; i--) {
            ObjectNode task = candidateTasks.get(i);
            ObjectNode stored = tx.get("research", task.path("id").asText(""));
            if (stored == null && task.path("research").isObject()) {
                stored = (ObjectNode) task.path("research");
            }
            if (isRichSynthesizedResearch(stored)) {
                return stored.deepCopy();
            }
        }
        if (topicId != null && !topicId.isBlank()) {
            List<ObjectNode> topicDrafts = tx.matching("draft", "topicId", topicId, -1);
            topicDrafts.sort(Comparator.comparingLong((ObjectNode d) -> d.path("updatedAt").asLong(0L)).reversed());
            for (ObjectNode d : topicDrafts) {
                JsonNode dr = d.path("research");
                if (isRichSynthesizedResearch(dr)) {
                    return ((ObjectNode) dr).deepCopy();
                }
                String rid = d.path("researchId").asText("");
                if (!rid.isBlank()) {
                    ObjectNode stored = tx.get("research", rid);
                    if (isRichSynthesizedResearch(stored)) {
                        return stored.deepCopy();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isRichSynthesizedResearch(JsonNode research) {
        if (research == null || !research.isObject()) return false;
        if ("MATERIAL_DIRECT".equals(research.path("quality").asText("")) || research.path("fastMode").asBoolean(false)) {
            return false;
        }
        if (research.path("pendingSynthesis").asBoolean(false)) return false;
        return !research.path("evidence").isEmpty()
                || !research.path("sourceReads").isEmpty()
                || (research.path("draftable").asBoolean(false) && !research.path("claims").isEmpty());
    }

    private static void mergeRichResearchInto(ObjectNode target, ObjectNode richSource) {
        if (target == null || richSource == null) return;
        String richSummary = richSource.path("summary").asText("").strip();
        if (!richSummary.isBlank()
                && (target.path("summary").asText("").isBlank()
                || "MATERIAL_DIRECT".equals(target.path("quality").asText("")))) {
            target.put("summary", richSummary);
        }
        if (target.path("evidence").isEmpty() && richSource.path("evidence").isArray() && !richSource.path("evidence").isEmpty()) {
            target.set("evidence", richSource.path("evidence").deepCopy());
        } else if (richSource.path("evidence").isArray() && !richSource.path("evidence").isEmpty()) {
            ArrayNode targetEv = target.withArray("evidence");
            Set<String> existingIds = new LinkedHashSet<>();
            targetEv.forEach(item -> existingIds.add(item.path("id").asText("")));
            for (JsonNode item : richSource.path("evidence")) {
                String id = item.path("id").asText("");
                if (!id.isBlank() && existingIds.add(id)) targetEv.add(item.deepCopy());
            }
        }
        if (target.path("materialEvidence").isEmpty()
                && richSource.path("materialEvidence").isArray()
                && !richSource.path("materialEvidence").isEmpty()) {
            target.set("materialEvidence", richSource.path("materialEvidence").deepCopy());
        }
        if (target.path("sourceReads").isEmpty() && richSource.path("sourceReads").isArray() && !richSource.path("sourceReads").isEmpty()) {
            target.set("sourceReads", richSource.path("sourceReads").deepCopy());
        }
        if (richSource.path("angles").isArray() && !richSource.path("angles").isEmpty()) {
            ArrayNode targetAngles = target.withArray("angles");
            Set<String> seenAngles = new LinkedHashSet<>();
            targetAngles.forEach(a -> seenAngles.add(a.asText("")));
            for (JsonNode angle : richSource.path("angles")) {
                String text = angle.asText("").strip();
                if (!text.isBlank() && seenAngles.add(text)) targetAngles.add(text);
            }
        }
        if (richSource.path("unverified").isArray() && !richSource.path("unverified").isEmpty() && target.path("unverified").isEmpty()) {
            target.set("unverified", richSource.path("unverified").deepCopy());
        }
        if (richSource.path("claims").isArray() && !richSource.path("claims").isEmpty()) {
            ArrayNode targetClaims = target.withArray("claims");
            Set<String> seenClaims = new LinkedHashSet<>();
            targetClaims.forEach(c -> seenClaims.add(c.path("claim").asText("").strip()));
            for (JsonNode claim : richSource.path("claims")) {
                String text = claim.path("claim").asText("").strip();
                if (!text.isBlank() && seenClaims.add(text)) {
                    targetClaims.add(claim.deepCopy());
                }
            }
        }
    }

    private static ObjectNode buildEphemeralMaterialResearch(ObjectNode snapshotInput) {
        JsonNode topicNode = snapshotInput.path("topic");
        String summary = !topicNode.path("summary").asText("").isBlank()
                ? topicNode.path("summary").asText()
                : topicNode.path("title").asText("已采集一手素材");
        var research = object()
                .put("quality", "MATERIAL_DIRECT")
                .put("draftable", true)
                .put("summary", summary);
        research.putArray("claims");
        research.putArray("angles");
        research.putArray("unverified");
        research.putArray("sourceReads");
        research.putArray("evidence");
        research.set("snapshot", snapshotInput.deepCopy().without(List.of("style", "preferences", "research")));
        return research;
    }

    private static void applyRuntimeGenerationTarget(JsonNode body, ObjectNode input) {
        if (body == null || !body.isObject()) return;
        String rawProv = body.path("generationProvider").asText("").strip();
        String rawModel = body.path("generationModel").asText("").strip();
        if (!rawProv.isEmpty()) {
            String normalized = AssistantConnection.normalizeProvider(rawProv);
            if (!Set.of("chatgpt", "openai", "google", "minimax", "deepseek").contains(normalized)) {
                throw new IllegalArgumentException("不支持的内容生成渠道: " + rawProv);
            }
            input.put("generationProvider", normalized);
        }
        if (!rawModel.isEmpty()) {
            if (rawModel.length() > 160) throw new IllegalArgumentException("生成模型名称过长");
            input.put("generationModel", rawModel);
        }
    }

    static String buildWinningObservationHint(AssistantStore.Tx tx) {
        Map<String, ObjectNode> latestByPost = new LinkedHashMap<>();
        for (ObjectNode row : tx.page("observation", 100, 0)) {
            if (!"OBSERVED".equals(row.path("status").asText(""))) continue;
            if (row.path("draftId").asText("").isBlank() && row.path("publicationId").asText("").isBlank()) continue;
            String postId = row.path("postId").asText("").strip();
            if (postId.isBlank()) continue;
            ObjectNode prev = latestByPost.get(postId);
            if (prev == null
                    || row.path("windowHours").asInt(0) > prev.path("windowHours").asInt(0)
                    || (row.path("windowHours").asInt(0) == prev.path("windowHours").asInt(0)
                    && row.path("observedAt").asLong(0) > prev.path("observedAt").asLong(0))) {
                latestByPost.put(postId, row);
            }
        }
        if (latestByPost.isEmpty()) return "";

        record Winner(ObjectNode row, double pctChange, long weightedScore, double rankScore) { }
        List<Winner> winners = new ArrayList<>();
        for (ObjectNode row : latestByPost.values()) {
            double pctChange = row.at("/baseline/views/percentChange").asDouble(0.0);
            long weightedScore = row.path("bookmarks").asLong(0) * 10L
                    + row.path("reposts").asLong(0) * 8L
                    + row.path("replies").asLong(0) * 10L
                    + row.path("likes").asLong(0);
            if (pctChange >= 25.0 || weightedScore >= 50L) {
                double rankScore = Math.max(0.0, pctChange) * 2.0 + weightedScore;
                winners.add(new Winner(row, pctChange, weightedScore, rankScore));
            }
        }
        if (winners.isEmpty()) return "";
        winners.sort(Comparator.comparingDouble(Winner::rankScore).reversed());

        List<String> lines = new ArrayList<>();
        lines.add("【本账号实测高互动历史参考（仅借鉴开篇节奏与体裁力度，严禁复用其中的任何事实、人物或数字）】");
        int idx = 1;
        for (Winner w : winners) {
            if (idx > 2) break;
            ObjectNode row = w.row();
            String draftId = row.path("draftId").asText("");
            String pubId = row.path("publicationId").asText("");
            ObjectNode pub = pubId.isBlank() ? null : tx.get("publication", pubId);
            ObjectNode draft = draftId.isBlank() ? null : tx.get("draft", draftId);
            String skillId = ViralWritingSkills.normalizeId(row.path("skillId").asText(""));
            if (skillId.isEmpty() && pub != null) skillId = ViralWritingSkills.normalizeId(pub.path("skillId").asText(""));
            if (skillId.isEmpty() && draft != null) skillId = ViralWritingSkills.normalizeId(draft.path("skillId").asText(""));
            String skillName = skillId.isEmpty() ? "默认体裁" : ViralWritingSkills.resolveOrDefault(skillId).name();
            String rawText = pub != null && !pub.path("text").asText("").isBlank()
                    ? pub.path("text").asText("")
                    : (draft != null ? draft.path("text").asText("") : "");
            String opening = firstParagraphPreview(rawText, 60);
            String perf = w.pctChange() >= 25.0
                    ? String.format(Locale.ROOT, "浏览超基线 +%.0f%%", w.pctChange())
                    : ("高权重互动分 " + w.weightedScore());
            if (!opening.isBlank()) {
                lines.add(idx + ". [体裁：" + skillName + "｜" + perf + "] 开篇节奏：" + opening);
            } else {
                lines.add(idx + ". [体裁：" + skillName + "｜" + perf + "]");
            }
            idx++;
        }
        return String.join("\n", lines);
    }

    private static String firstParagraphPreview(String rawText, int maxCodePoints) {
        if (rawText == null || rawText.isBlank()) return "";
        String first = "";
        for (String line : rawText.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                first = trimmed;
                break;
            }
        }
        if (first.isEmpty()) return "";
        int total = first.codePointCount(0, first.length());
        if (total <= maxCodePoints) return first;
        return first.substring(0, first.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    static ObjectNode requestTask(AssistantStore.Tx tx,String kind,ObjectNode body,ObjectNode input) {
        var existing=replay(tx,kind,body);
        if(existing!=null) return existing;
        // A new verification or refresh is an explicit request to check the current upstream state.
        input.put("refreshId",field(body,"clientRequestId"));
        applyRuntimeGenerationTarget(body, input);
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
