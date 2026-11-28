package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.xops.hub.assistant.AssistantIntelligence.Stage;
import com.xops.hub.model.XPostLink;
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
        String materialId = "link-" + digest(object().put("landingUrl", finalUrl)).substring(0, 20);
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
                .put("collectedAt", nowMs);
    }

    static boolean isWebLinkMaterial(JsonNode material) {
        return "USER_LINK".equals(material.path("sourceIdentity").asText())
                && material.path("id").asText().startsWith("link-");
    }

    static ObjectNode retainWebImportKey(AssistantStore.Tx tx, ObjectNode source, String submittedUrl) {
        if (!isWebLinkMaterial(source)) return source;
        String sourceId = source.path("id").asText();
        String legacyId = "link-" + digest(TextNode.valueOf(submittedUrl)).substring(0, 20);
        String retainedId = null;
        for (ObjectNode row : tx.matching("material", "sourceUrl", source.path("sourceUrl").asText(), -1)) {
            if (!isWebLinkMaterial(row)) continue;
            String id = row.path("id").asText();
            if (legacyId.equals(id)) return source.deepCopy().put("id", id);
            if (retainedId == null || id.equals(sourceId)) retainedId = id;
        }
        return retainedId == null || retainedId.equals(sourceId) ? source : source.deepCopy().put("id", retainedId);
    }

    static String materialContentFingerprint(JsonNode material) {
        ObjectNode content = object();
        for (String key : List.of("authorId", "username", "text", "quotedText", "articleText", "sourceUrl", "textStatus",
                "authorTextStatus", "quotedTextStatus", "articleTextStatus", "articleRole", "publishedAt",
                "quotedId", "quotedAuthorId", "quotedUsername", "articleId", "articleUrl",
                "kind", "hasMedia", "media", "links", "selfThreadReply", "archiveOnly")) {
            if (material.has(key)) content.set(key, material.get(key));
        }
        // Earlier web imports stored the fetch time as publishedAt. It isn't evidence of a page edit.
        if (isWebLinkMaterial(material)) content.remove("publishedAt");
        return digest(content);
    }

    static String ingestDirectLinkMaterial(AssistantStore.Tx tx, ObjectNode source) {
        String materialId = field(source, "id");
        ObjectNode previous = tx.get("material", materialId);
        ObjectNode membership = tx.get("membership", materialId);
        String fingerprint = materialContentFingerprint(source);
        boolean sameWebContent = previous != null && isWebLinkMaterial(source)
                && fingerprint.equals(materialContentFingerprint(previous));
        long now = System.currentTimeMillis();
        long firstCollectedAt = previous != null && previous.path("collectedAt").asLong(0) > 0
                ? previous.path("collectedAt").asLong()
                : source.path("collectedAt").asLong(now);
        // Retain an explicitly imported item from the day of the owner's request, including historical X posts.
        long today = java.time.Instant.ofEpochMilli(now).atZone(AssistantRetention.ZONE).toLocalDate()
                .atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
        boolean freshlyImported = previous != null && previous.path("ownerImportedAt").asLong() >= today;
        ObjectNode saved = (sameWebContent || (freshlyImported && fingerprint.equals(previous.path("contentFingerprint").asText(""))))
                ? previous
                : tx.put("material", materialId, source.deepCopy()
                        .put("contentFingerprint", fingerprint).put("ownerImportedAt", now)
                        .put("collectedAt", firstCollectedAt).put("lastCollectedAt", now));
        if (AssistantTopics.text(saved).isBlank() || "NO_ANALYZABLE_TEXT".equals(saved.path("textStatus").asText(""))) {
            throw new IllegalArgumentException("该链接不含可分析的正文内容");
        }

        String topicId = membership == null ? "" : membership.path("topicId").asText("");
        if (topicId.isBlank()) {
            topicId = id();
            tx.put("membership", materialId, object().put("topicId", topicId).put("actor", "OWNER_LINK").put("ownerImportedAt", now));
            ObjectNode topic = AssistantTopics.fresh(topicId, saved);
            setImportedTopicText(topic, saved);
            topic.put("status", "SCORED")
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
            if (sameWebContent && !freshlyImported && membership.path("ownerImportedAt").asLong() < today) {
                tx.put("membership", materialId, membership.put("ownerImportedAt", now));
            }
            ObjectNode existingTopic = required(tx, "topic", topicId);
            if ("USER_LINK".equals(saved.path("sourceIdentity").asText("")) && existingTopic.path("materialIds").size() == 1
                    && (!sameWebContent || existingTopic.path("summary").asText("").isBlank())) {
                var refreshed = existingTopic.deepCopy();
                setImportedTopicText(refreshed, saved);
                if (!refreshed.equals(existingTopic)) AssistantTopics.persist(tx, topicId, refreshed);
            }
        }
        return topicId;
    }

    private static void setImportedTopicText(ObjectNode topic, JsonNode material) {
        String title = "USER_LINK".equals(material.path("sourceIdentity").asText()) && !material.path("authorName").asText().isBlank()
                ? material.path("authorName").asText() : material.path("text").asText("");
        if (!title.isBlank()) topic.put("title", limitCodePoints(title.replaceAll("\\s+", " ").strip(), 100));
        String article = material.path("articleText").asText("");
        topic.put("summary", firstParagraphPreview(article.isBlank() ? material.path("text").asText("") : article, 140));
    }

    private static ObjectNode writingSnapshot(AssistantStore.Tx tx, String topicId, String selectedId, ObjectNode draft) {
        var input = AssistantTopics.snapshot(tx, topicId, selectedId);
        if (selectedId.isEmpty()) return input;
        for (var material : input.path("materials")) {
            if (!selectedId.equals(material.path("id").asText())) continue;
            if (draft != null && !selectedId.matches("[0-9]{2,20}")) {
                XPostLink original = AssistantPublishing.draftSourcePost(tx, draft);
                XPostLink current = XPostLink.parse(material.path("sourceUrl").asText(""));
                if (!Objects.equals(original == null ? null : original.tweetId(), current == null ? null : current.tweetId()))
                    throw new XDataProviderException(409, "该稿件的素材标识已指向另一原帖，请重新选择原文");
                if (current == null && !sameWebSource(tx, selectedId, draft, material))
                    throw new XDataProviderException(409, "该稿件的网页链接已指向另一篇文章，请重新选择原文");
            }
            if ("USER_LINK".equals(material.path("sourceIdentity").asText())) {
                // Previously imported pages can still have an obsolete summary stored on their topic.
                setImportedTopicText((ObjectNode) input.get("topic"), material);
            }
            return input;
        }
        throw new XDataProviderException(409, "当前选题已不含该稿件的原始素材，请重新选择原文");
    }

    private static boolean sameWebSource(AssistantStore.Tx tx, String id, ObjectNode draft, JsonNode live) {
        String current = AssistantEvidence.normalizedUrl(live.path("sourceUrl").asText(""));
        var frozen = AssistantPublishing.frozenAuthorSource(id, draftResearch(tx, draft));
        if (frozen.present()) return !current.isEmpty() && current.equals(frozen.webUrl());
        return tx.versions("material", id).stream().allMatch(revision ->
                current.equals(AssistantEvidence.normalizedUrl(revision.path("sourceUrl").asText(""))));
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
        String fingerprint=materialContentFingerprint(source);
        if(previous!=null && fingerprint.equals(previous.path("contentFingerprint").asText())) {
            var refreshed = previous.deepCopy();
            for (String field : List.of("authorName", "authorAvatarUrl", "authorBio")) {
                if (!source.path(field).asText("").isBlank()) refreshed.set(field, source.get(field));
            }
            for (String field : List.of("views", "likes", "reposts", "replies", "bookmarks", "authorVerified",
                    "authorFollowersCount", "kind", "hasMedia", "media", "links", "selfThreadReply", "pinned")) {
                if (source.hasNonNull(field)) refreshed.set(field, source.get(field));
            }
            if (!refreshed.equals(previous)) tx.put("material", materialId, refreshed);
            return;
        }
        long now = System.currentTimeMillis();
        long firstCollectedAt = previous != null && previous.path("collectedAt").asLong(0) > 0
                ? previous.path("collectedAt").asLong() : source.path("collectedAt").asLong(now);
        ObjectNode collected = source.deepCopy();
        if (previous != null && previous.has("ownerImportedAt")) collected.set("ownerImportedAt", previous.get("ownerImportedAt"));
        var saved=tx.put("material",materialId,collected
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
            if (kind.equals("DRAFT") && input.path("fastMode").asBoolean()
                    && input.path("research") instanceof ObjectNode research && !research.has("id")) {
                input.set("research",tx.put("research","fast-" + id(),research));
            }
            task=object().put("kind",kind).put("stage",kind).put("status","QUEUED").put("createdAt",System.currentTimeMillis())
                    .put("fingerprintRules",FINGERPRINT_RULES).put("inputFingerprint", fp);
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
                List<JsonNode> matList = selectedMaterialsOnly(input);
                String rec = ViralWritingSkills.recommendSkill(selectedTopicNode(input, matList), matList);
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
            String id = index.path("taskId").asText();
            var task = tx.get("task", id);
            if (task != null && matchesStyle(task,styleVersion)) {
                String current = task.path("inputFingerprint").asText("");
                if (current.isEmpty()) {
                    var accepted = tx.version("task", id, 1);
                    if (accepted != null) current = taskFingerprint(kind, accepted.path("input"), task.path("settings"));
                }
                if (fingerprint.equals(current)) return task;
            }
        }
        if (input.has("newAttempt") || input.has("refreshId") || usesConstraintRepair(kind,input)) return null;

        // The first immutable revision retains accepted inputs even after a stage adds research or style.
        String scopeField = kind.equals("OPTIMIZE") ? "draftId" : "materialId";
        String scope = input.path(scopeField).asText(input.path("selectedMaterialId").asText());
        for (var legacy : tx.legacyTaskInputs(kind,scopeField,scope)) {
            String taskId = legacy.path("id").asText();
            var task = required(tx,"task",taskId);
            if (!fingerprint.equals(taskFingerprint(kind,legacy.path("input"),task.path("settings")))) continue;
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
        String targetDraftId = body.path("draftId").asText("").strip();
        ObjectNode baseDraft = targetDraftId.isBlank() ? null : required(tx, "draft", targetDraftId);
        if (baseDraft != null && body.has("version")) {
            expect(baseDraft, body);
        }
        String savedMaterial = baseDraft == null ? "" : draftMaterialId(tx, baseDraft);
        String requestedMaterial = body.path("materialId").asText("").strip();
        if (!savedMaterial.isEmpty() && !requestedMaterial.isEmpty() && !savedMaterial.equals(requestedMaterial))
            throw new XDataProviderException(409, "该稿件的研究来源与所选素材不一致，请刷新稿件");
        String mid = !savedMaterial.isEmpty() ? savedMaterial : field(body, "materialId");
        var primaryMaterial = required(tx,"material",mid);
        if(AssistantTopics.text(primaryMaterial).isBlank()) throw new IllegalArgumentException("该素材没有可分析文字");
        String topic=AssistantTopics.topicOf(tx,mid);
        if (topic.isBlank() && baseDraft != null) topic = baseDraft.path("topicId").asText("");
        var input=writingSnapshot(tx,topic,mid,savedMaterial.isEmpty() ? null : baseDraft);
        String instruction = body.path("instruction").asText("").strip();
        if (instruction.codePointCount(0, instruction.length()) > 500) {
            throw new IllegalArgumentException("切入角度过长（最多 500 字）");
        }
        input.put("instruction", instruction);
        String rawSkill = body.path("skillId").asText("").strip();
        String skillId;
        if (rawSkill.isEmpty() || "AUTO".equalsIgnoreCase(rawSkill)) {
            String inherited = baseDraft == null ? "" : ViralWritingSkills.normalizeId(baseDraft.path("skillId").asText(""));
            if (!inherited.isEmpty()) {
                skillId = inherited;
            } else {
                List<JsonNode> matList = selectedMaterialsOnly(input);
                skillId = ViralWritingSkills.recommendSkill(selectedTopicNode(input, matList), matList);
            }
        } else {
            skillId = ViralWritingSkills.normalizeId(rawSkill);
        }
        if (!skillId.isEmpty()) input.put("skillId", skillId);
        if (baseDraft != null) {
            input.put("draftId", targetDraftId)
                    .put("draftVersion", ver(baseDraft))
                    .put("text", baseDraft.path("text").asText(""))
                    .put("researchUpgrade", true);
            String baseAngle = baseDraft.path("angleInstruction").asText("").strip();
            if (!baseAngle.isBlank()) input.put("baseAngleInstruction", baseAngle);
        }
        var style=tx.get("meta","style");
        if(style!=null) input.set("style",style);
        input.set("preferences",preferences(tx));
        String winningHint = buildWinningObservationHint(tx);
        if (!winningHint.isEmpty()) input.put("winningHint", winningHint);
        applyRuntimeGenerationTarget(body, input);
        if (fastMode) {
            input.put("fastMode", true);
            if (body.path("regenerate").asBoolean() || baseDraft != null) {
                input.put("newAttempt", field(body, "clientRequestId"));
            }
            ObjectNode directResearch = buildDirectMaterialResearch(input);
            ObjectNode priorRich = findLatestRichResearch(tx, input);
            if (priorRich != null) {
                mergeRichResearchInto(directResearch, sanitizeResearchForMaterial(priorRich, mid));
            }
            input.set("research", directResearch);
            return enqueue(tx, "DRAFT", input, field(body, "clientRequestId"), body);
        }
        if (body.path("regenerate").asBoolean() || baseDraft != null) {
            input.put("newAttempt",field(body,"clientRequestId"));
            var previous = previousResearch(tx, input);
            if (previous != null) input.set("previousResearch", previous);
        }
        return enqueue(tx,"RESEARCH",input,field(body,"clientRequestId"),body);
    }

    private static ObjectNode buildDirectMaterialResearch(ObjectNode snapshotInput) {
        String selectedMaterialId = snapshotInput.path("selectedMaterialId").asText("").strip();
        List<JsonNode> targetMaterials = selectedMaterialsOnly(snapshotInput);
        String summary = resolveIsolatedMaterialSummary(snapshotInput, targetMaterials, "已采集 X 一手素材");
        var research = object()
                .put("quality", "MATERIAL_DIRECT")
                .put("draftable", true)
                .put("fastMode", true)
                .put("summary", summary);
        if (!selectedMaterialId.isBlank()) {
            research.put("materialId", selectedMaterialId);
        }
        var claims = research.putArray("claims");
        var matEv = research.putArray("materialEvidence");
        for (JsonNode mat : targetMaterials) {
            String matId = mat.path("id").asText("");
            if (matId.isBlank() || AssistantTopics.text(mat).isBlank()) continue;
            String author = mat.path("username").asText("").strip().replaceFirst("^@+", "");
            String name = mat.path("authorName").asText("").strip();
            String publisher = name.isEmpty() ? author.isEmpty() ? "" : "@" + author : name + (author.isEmpty() ? "" : " (@" + author + ")");
            String prefix = "material-" + matId + "-";
            String postUrl = mat.path("sourceUrl").asText("");
            if ("USER_LINK".equals(mat.path("sourceIdentity").asText())) {
                String article = mat.path("articleText").asText("");
                addDirectPassage(matEv, claims, prefix + "link", postUrl, name, "FULL", "",
                        article.isBlank() ? mat.path("text").asText("") : article);
                continue;
            }
            addDirectPassage(matEv, claims, prefix + "author", postUrl, publisher, mat.path("authorTextStatus").asText(""),
                    author.isEmpty() ? "" : "@" + author + "：", mat.path("text").asText(""));
            String quoted = mat.path("quotedUsername").asText("").strip().replaceFirst("^@+", "");
            String quotedPublisher = quoted.isEmpty() ? "" : "@" + quoted;
            XPostLink quotedPost = XPostLink.forMaterial(mat.path("quotedId").asText(""), quoted, null);
            String quotedUrl = quotedPost == null ? "" : quotedPost.url();
            addDirectPassage(matEv, claims, prefix + "quoted", quotedUrl, quotedPublisher, mat.path("quotedTextStatus").asText(""),
                    quoted.isEmpty() ? "被引帖：" : "引用 @" + quoted + "：", mat.path("quotedText").asText(""));
            String role = mat.path("articleRole").asText("");
            String status = mat.path("articleTextStatus").asText("");
            boolean quotedArticle = "QUOTED".equals(role);
            boolean authoredArticle = "AUTHOR".equals(role);
            String articleLabel = quotedArticle ? "被引长文" : authoredArticle ? "作者长文" : "归属待核实的长文";
            String articleUrl = quotedArticle ? quotedUrl : authoredArticle ? postUrl : "";
            if (articleUrl.isEmpty()) articleUrl = mat.path("articleUrl").asText("");
            String articleId = prefix + (quotedArticle ? "quoted-article" : authoredArticle ? "author-article" : "article");
            addDirectPassage(matEv, claims, articleId, articleUrl, quotedArticle ? quotedPublisher : authoredArticle ? publisher : "",
                    status, articleLabel + ("FULL".equals(status) ? "全文：" : "节选："), mat.path("articleText").asText(""));
        }
        research.putArray("angles");
        research.putArray("unverified");
        research.putArray("sourceReads");
        research.set("snapshot", snapshotInput.deepCopy().without(List.of("style", "preferences", "research")));
        return research;
    }

    private static void addDirectPassage(ArrayNode evidence, ArrayNode claims, String id, String url, String publisher,
                                         String completeness, String label, String body) {
        if (body.isBlank()) return;
        String status = switch (completeness) {
            case "FULL" -> "MATERIAL_FULL";
            case "PREVIEW" -> "MATERIAL_PREVIEW";
            default -> "MATERIAL_EXCERPT";
        };
        String excerptId = id + "#p1";
        String text = label + body.strip();
        var passage = evidence.addObject().put("id", id).put("url", url).put("publisher", publisher).put("status", status);
        passage.putArray("excerpts").addObject().put("id", excerptId).put("text", text);
        var claim = claims.addObject().put("claim", text).put("relation", "SUPPORTS");
        claim.putArray("sourceIds").add(id);
        claim.putArray("excerptIds").add(excerptId);
    }

    private static ObjectNode previousResearch(AssistantStore.Tx tx, ObjectNode input) {
        String id = input.path("selectedMaterialId").asText("").strip();
        if (id.isBlank()) return null;
        List<ObjectNode> attempts = tx.matching("task", "materialId", id, -1);
        for (int i = attempts.size() - 1; i >= 0; i--) {
            var prior = tx.get("research", attempts.get(i).path("id").asText());
            if (prior == null || prior.path("sourceReads").isEmpty()) continue;
            if (!belongsToMaterial(prior, id)) continue;
            JsonNode snapshot = prior.path("snapshot");
            if (input.path("topicId").equals(snapshot.path("topicId"))
                    && memberIds(input).containsAll(memberIds(snapshot)) && sameResearchSourceContent(input, snapshot)) {
                return object().put("materialId", id).set("sourceReads", prior.path("sourceReads"));
            }
        }
        return null;
    }

    private static Set<String> memberIds(JsonNode snapshot) {
        var ids = new HashSet<String>();
        snapshot.path("materials").forEach(material -> ids.add(material.path("id").asText()));
        snapshot.path("topic").path("materialIds").forEach(id -> ids.add(id.asText()));
        ids.remove("");
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

    static ObjectNode adoptDraft(AssistantStore.Tx tx, String id, ObjectNode body) {
        var current = required(tx, "draft", id);
        int expected = body.path("version").asInt(-1);
        int source = body.path("sourceVersion").asInt(-1);
        if (source < 1 || expected < 1) throw new IllegalArgumentException("请选择已保存的稿件版本");
        if (ver(current) == expected + 1 && current.path("adoptedOverVersion").asInt(-1) == expected
                && current.path("adoptedFromVersion").asInt(-1) == source) return current;
        expect(current, body);
        if (source == expected) return current;
        var selected = tx.version("draft", id, source);
        if (selected == null) throw new XDataProviderException(404, "历史稿件版本不存在");
        selected.put("editor", "USER").put("adoptedFromVersion", source).put("adoptedOverVersion", expected);
        return tx.put("draft", id, selected);
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
        String matId = draftMaterialId(tx, draft);
        String topicId = AssistantTopics.topicOf(tx, matId);
        if (topicId.isBlank()) topicId = draft.path("topicId").asText("");
        var input=writingSnapshot(tx, topicId, matId, draft);
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

    static ObjectNode draftResearch(AssistantStore.Tx tx, ObjectNode draft) {
        ObjectNode embedded = draft.get("research") instanceof ObjectNode research ? research : null;
        if (embedded != null && embedded.path("snapshot").isObject()) return embedded;
        String id = draft.path("researchId").asText("").strip();
        ObjectNode stored = id.isEmpty() ? null : tx.get("research", id);
        if (stored == null) return embedded == null ? object() : embedded;
        return embedded == null ? stored : stored.deepCopy().setAll(embedded);
    }

    static String draftMaterialId(AssistantStore.Tx tx, ObjectNode draft) {
        String id = draft.path("materialId").asText("").strip();
        if (!id.isEmpty()) return id;
        JsonNode embedded = draft.path("research");
        String explicit = explicitResearchMaterialId(embedded);
        if (!explicit.isEmpty()) return explicit;
        String researchId = draft.path("researchId").asText("").strip();
        JsonNode stored = researchId.isEmpty() ? null : tx.get("research", researchId);
        explicit = explicitResearchMaterialId(stored);
        if (!explicit.isEmpty()) return explicit;
        String sole = soleResearchMaterialId(embedded);
        if (sole.isEmpty()) sole = soleResearchMaterialId(stored);
        if (!sole.isEmpty() || embedded.path("snapshot").path("materials").size() > 1
                || stored != null && stored.path("snapshot").path("materials").size() > 1) return sole;
        return tx.frozenTopicMaterial(draft.path("originTopicId").asText(draft.path("topicId").asText("")),
                draft.path("topicVersion").asInt());
    }

    static String researchMaterialId(JsonNode research) {
        String explicit = explicitResearchMaterialId(research);
        return explicit.isEmpty() ? soleResearchMaterialId(research) : explicit;
    }

    private static String explicitResearchMaterialId(JsonNode research) {
        if (research == null || !research.isObject()) return "";
        String explicit = research.path("materialId").asText("").strip();
        if (!explicit.isBlank()) return explicit;
        return research.path("snapshot").path("selectedMaterialId").asText("").strip();
    }

    private static String soleResearchMaterialId(JsonNode research) {
        if (research == null) return "";
        JsonNode materials = research.path("snapshot").path("materials");
        return materials.isArray() && materials.size() == 1 ? materials.get(0).path("id").asText("").strip() : "";
    }

    private static boolean belongsToMaterial(JsonNode research, String materialId) {
        if (research == null || !research.isObject()) return false;
        String target = materialId == null ? "" : materialId.strip();
        if (target.isEmpty()) return true;
        String owner = researchMaterialId(research);
        if (!owner.isEmpty()) return target.equals(owner);
        if (research.path("snapshot").isMissingNode()) return true;
        Set<String> prev = memberIds(research.path("snapshot"));
        return prev.isEmpty() || (prev.size() == 1 && prev.contains(target));
    }

    private static List<JsonNode> selectedMaterialsOnly(JsonNode input) {
        List<JsonNode> selected = new ArrayList<>();
        if (input == null) return selected;
        String selectedId = input.path("selectedMaterialId").asText(input.path("materialId").asText("")).strip();
        JsonNode mats = input.path("materials");
        if (mats.isArray()) {
            for (JsonNode m : mats) {
                if (m == null || !m.isObject()) continue;
                if (!selectedId.isBlank() && selectedId.equals(m.path("id").asText(""))) {
                    selected.add(m);
                }
            }
            if (selected.isEmpty() && selectedId.isBlank()) {
                mats.forEach(selected::add);
            }
        }
        return selected;
    }

    private static JsonNode selectedTopicNode(JsonNode input, List<JsonNode> targetMaterials) {
        JsonNode topicNode = input == null ? object() : input.path("topic");
        boolean multiMaterialTopic = input != null && (input.path("materials").size() > 1
                || topicNode.path("materialIds").size() > 1);
        if (!multiMaterialTopic || targetMaterials.isEmpty()) return topicNode;
        JsonNode primary = targetMaterials.get(0);
        String headline = !primary.path("authorName").asText("").isBlank()
                && "USER_LINK".equals(primary.path("sourceIdentity").asText(""))
                ? primary.path("authorName").asText("")
                : firstParagraphPreview(AssistantTopics.text(primary), 100);
        String summary = firstParagraphPreview(
                !primary.path("articleText").asText("").isBlank()
                        ? primary.path("articleText").asText("")
                        : AssistantTopics.text(primary),
                140);
        return object()
                .put("id", topicNode.path("id").asText(""))
                .put("title", headline)
                .put("summary", summary);
    }

    private static String resolveIsolatedMaterialSummary(ObjectNode snapshotInput, List<JsonNode> targetMaterials, String fallback) {
        JsonNode topicNode = snapshotInput.path("topic");
        boolean multiMaterialTopic = snapshotInput.path("materials").size() > 1
                || topicNode.path("materialIds").size() > 1;
        if (multiMaterialTopic && !targetMaterials.isEmpty()) {
            JsonNode primary = targetMaterials.get(0);
            String candidate = firstParagraphPreview(
                    !primary.path("articleText").asText("").isBlank()
                            ? primary.path("articleText").asText("")
                            : AssistantTopics.text(primary),
                    140);
            if (!candidate.isBlank()) return candidate;
        }
        return !topicNode.path("summary").asText("").isBlank()
                ? topicNode.path("summary").asText()
                : topicNode.path("title").asText(fallback);
    }

    private static ObjectNode sanitizeResearchForMaterial(ObjectNode research, String materialId) {
        if (research == null) return null;
        String target = materialId == null ? "" : materialId.strip();
        ObjectNode copy = research.deepCopy();
        if (target.isEmpty()) return copy;
        copy.put("materialId", target);
        String allowedPrefix = "material-" + target + "-";
        if (copy.path("materialEvidence").isArray()) {
            ArrayNode filteredMatEv = JsonNodeFactory.instance.arrayNode();
            for (JsonNode ev : copy.path("materialEvidence")) {
                String sid = ev.path("id").asText("").strip();
                if (!sid.startsWith("material-") || sid.startsWith(allowedPrefix)) {
                    filteredMatEv.add(ev);
                }
            }
            copy.set("materialEvidence", filteredMatEv);
        }
        if (copy.path("claims").isArray()) {
            ArrayNode filteredClaims = JsonNodeFactory.instance.arrayNode();
            for (JsonNode claim : copy.path("claims")) {
                boolean referencesForeignMaterial = false;
                for (JsonNode sidNode : claim.path("sourceIds")) {
                    String sid = sidNode.asText("").strip();
                    if (sid.startsWith("material-") && !sid.startsWith(allowedPrefix)) {
                        referencesForeignMaterial = true;
                        break;
                    }
                }
                if (!referencesForeignMaterial) {
                    filteredClaims.add(claim);
                }
            }
            copy.set("claims", filteredClaims);
        }
        return copy;
    }

    private static ObjectNode resolveResearchForOptimize(
            AssistantStore.Tx tx, ObjectNode draft, ObjectNode snapshotInput, String topicId, String materialId) {
        ObjectNode base = draftResearch(tx, draft);
        if (base.isEmpty() || !belongsToMaterial(base, materialId)
                || !sameResearchSourceContent(snapshotInput, base.path("snapshot"))) base = null;
        if (base != null && !base.path("snapshot").isMissingNode()
                && !memberIds(snapshotInput).containsAll(memberIds(base.path("snapshot")))) {
            base = null;
        }
        if (base != null) {
            base = sanitizeResearchForMaterial(base, materialId);
            if (isRichSynthesizedResearch(base)) {
                return base;
            }
        }
        ObjectNode latestRich = findLatestRichResearch(tx, snapshotInput);
        if (base == null) {
            if (latestRich != null) return sanitizeResearchForMaterial(latestRich, materialId);
            return buildEphemeralMaterialResearch(snapshotInput);
        }
        if (latestRich != null) {
            mergeRichResearchInto(base, sanitizeResearchForMaterial(latestRich, materialId));
        }
        return base;
    }

    private static ObjectNode findLatestRichResearch(AssistantStore.Tx tx, ObjectNode input) {
        String topicId = input.path("topicId").asText("");
        String selectedMaterialId = input.path("selectedMaterialId").asText("").strip();
        if (selectedMaterialId.isBlank()) return null;
        var members = memberIds(input);
        var merged = tx.mergedTopics();
        Map<String, ObjectNode> tasksById = new LinkedHashMap<>();
        if (topicId != null && !topicId.isBlank()) {
            for (ObjectNode task : tx.topicRecords("task", topicId, -1, 0)) {
                String taskMatId = task.path("materialId").asText("").strip();
                if (taskMatId.isEmpty() || selectedMaterialId.equals(taskMatId)) {
                    tasksById.put(task.path("id").asText(""), task);
                }
            }
        }
        for (ObjectNode task : tx.matching("task", "materialId", selectedMaterialId, -1)) {
            tasksById.putIfAbsent(task.path("id").asText(""), task);
        }
        List<ObjectNode> candidateTasks = new ArrayList<>(tasksById.values());
        candidateTasks.sort(Comparator.comparingLong((ObjectNode t) -> t.path("updatedAt").asLong()));
        for (int i = candidateTasks.size() - 1; i >= 0; i--) {
            ObjectNode task = candidateTasks.get(i);
            ObjectNode stored = tx.get("research", task.path("id").asText(""));
            if (stored == null && task.path("research").isObject()) {
                stored = (ObjectNode) task.path("research");
            }
            if (canReuseRichResearch(stored, input, selectedMaterialId, members, merged)) {
                return stored.deepCopy();
            }
        }
        if (topicId != null && !topicId.isBlank()) {
            List<ObjectNode> topicDrafts = tx.topicRecords("draft",topicId,-1,0);
            topicDrafts.sort(Comparator.comparingLong((ObjectNode d) -> d.path("updatedAt").asLong(0L)).reversed());
            for (ObjectNode d : topicDrafts) {
                String draftMatId = d.path("materialId").asText("").strip();
                if (!draftMatId.isEmpty() && !selectedMaterialId.equals(draftMatId)) continue;
                JsonNode dr = d.path("research");
                if (canReuseRichResearch(dr, input, selectedMaterialId, members, merged)) {
                    return ((ObjectNode) dr).deepCopy();
                }
                String rid = d.path("researchId").asText("");
                if (!rid.isBlank()) {
                    ObjectNode stored = tx.get("research", rid);
                    if (canReuseRichResearch(stored, input, selectedMaterialId, members, merged)) {
                        return stored.deepCopy();
                    }
                }
            }
        }
        return null;
    }

    private static boolean canReuseRichResearch(
            JsonNode research, JsonNode input, String selectedMaterialId, Set<String> members, Map<String,String> merged) {
        if (!isRichSynthesizedResearch(research) || selectedMaterialId == null || selectedMaterialId.isBlank()) return false;
        var snapshot = research.path("snapshot");
        String origin = snapshot.path("topicId").asText(snapshot.path("topic").path("id").asText(""));
        var previous = memberIds(snapshot);
        String ownerMatId = researchMaterialId(research);
        boolean sameArticle = !ownerMatId.isBlank()
                ? selectedMaterialId.equals(ownerMatId)
                : (previous.size() == 1 && previous.contains(selectedMaterialId));
        if (!sameArticle) return false;
        return input.path("topicId").asText("").equals(merged.getOrDefault(origin,origin))
                && !previous.isEmpty() && members.containsAll(previous) && sameResearchSourceContent(input, snapshot);
    }

    private static boolean sameResearchSourceContent(JsonNode input, JsonNode previousSnapshot) {
        for (JsonNode current : selectedMaterialsOnly(input)) {
            JsonNode original = null;
            for (JsonNode material : previousSnapshot.path("materials")) {
                if (current.path("id").equals(material.path("id"))) {
                    original = material;
                    break;
                }
            }
            if (original == null) {
                if (isWebLinkMaterial(current)) return false;
            } else if (!materialContentFingerprint(current).equals(materialContentFingerprint(original))) return false;
            if (isWebLinkMaterial(current) && previousSnapshot.path("materials").size() == 1
                    && !previousSnapshot.path("topic").path("summary").asText("").isBlank()
                    && !previousSnapshot.path("topic").path("summary").equals(input.path("topic").path("summary"))) return false;
        }
        return true;
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
        String selectedMaterialId = snapshotInput.path("selectedMaterialId").asText("").strip();
        List<JsonNode> targetMaterials = selectedMaterialsOnly(snapshotInput);
        String summary = resolveIsolatedMaterialSummary(snapshotInput, targetMaterials, "已采集一手素材");
        var research = object()
                .put("quality", "MATERIAL_DIRECT")
                .put("draftable", true)
                .put("summary", summary);
        if (!selectedMaterialId.isBlank()) {
            research.put("materialId", selectedMaterialId);
        }
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

    static void blockChangedProviderTasks(AssistantStore.Tx tx, ObjectNode before, ObjectNode after, Map<String,String> running) {
        boolean mainChanged = !before.path("provider").asText("commandcode").equals(after.path("provider").asText("commandcode"))
                || before.path("connectionVersion").asInt() != after.path("connectionVersion").asInt();
        Set<String> changedModules = new HashSet<>();
        for (String module : List.of("collection", "search", "generation")) {
            if (!before.path("moduleConfigs").path(module).path("provider").equals(after.path("moduleConfigs").path(module).path("provider")))
                changedModules.add(module);
        }
        if (!mainChanged && changedModules.isEmpty()) return;
        for (String state : List.of("QUEUED","RUNNING","WAITING_STYLE","WAITING_SAMPLES")) {
            for (ObjectNode task : tx.matching("task", "status", state, -1)) {
                String kind = task.path("kind").asText();
                if (kind.equals("COLLECT_STYLE")) continue;
                boolean affected = mainChanged || switch (kind) {
                    case "RESEARCH", "OPTIMIZE" -> changedModules.contains("search") || changedModules.contains("generation");
                    case "VERIFY" -> true;
                    case "SCORE", "CLASSIFY" -> changedModules.contains("collection");
                    default -> changedModules.contains("generation");
                };
                if (!affected) continue;
                String id = task.path("id").asText();
                if (task.hasNonNull("attempt")) running.put(id, task.path("attempt").asText());
                task.remove("attempt");
                tx.put("task", id, task.put("status", "BLOCKED_CONFIG").put("error", "模型服务已更换，请明确重试以采用新配置"));
            }
        }
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
            ObjectNode currentSettings = settings(tx);
            ObjectNode accepted = tx.version("task", id, 1);
            ObjectNode originalInput = (ObjectNode) accepted.path("input");
            String kind = task.path("kind").asText();
            String fingerprint = taskFingerprint(kind, originalInput, currentSettings);
            ObjectNode indexed = fingerprintTask(tx, kind, originalInput, fingerprint, 0);
            if (indexed != null && !id.equals(indexed.path("id").asText())) {
                if (Set.of("QUEUED", "RUNNING", "WAITING_STYLE", "WAITING_SAMPLES", "READY").contains(indexed.path("status").asText()))
                    return indexed;
            }
            task.put("inputFingerprint", fingerprint);
            tx.put("fingerprint", fingerprint, object().put("taskId", id));
            task.set("settings",currentSettings);
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
        safe.remove(List.of("input","settings","attempt","fingerprintRules","inputFingerprint"));
        return safe;
    }
}
