package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.RequestSecuritySupport;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.*;
import java.util.regex.Pattern;
import static com.xops.hub.assistant.AssistantStore.object;
import static com.xops.hub.assistant.AssistantWorkflow.*;

@RestController
@RequestMapping("/api/content-assistant")
public final class AssistantController {
    private static final Pattern SOURCE_BATCH_DELIMITERS = Pattern.compile("[\\s,，、;；]+");
    private final AssistantStore store;
    private final SourceCollector sources;
    private final AssistantWorker worker;
    private final AssistantPublishing publishing;
    private final ObjectMapper mapper;
    private final UpstreamBudgetGuard budgetGuard;
    private final Environment environment;
    @Autowired(required = false)
    private PiAgentViralJudge piJudge;
    @Autowired(required = false)
    private AutoRepostService autoRepostService;

    public AssistantController(AssistantStore store,SourceCollector sources,AssistantWorker worker,AssistantPublishing publishing,ObjectMapper mapper) {
        this(store, sources, worker, publishing, mapper, null, null);
    }

    @Autowired
    public AssistantController(AssistantStore store,SourceCollector sources,AssistantWorker worker,
                               AssistantPublishing publishing,ObjectMapper mapper,
                               @Autowired(required = false) UpstreamBudgetGuard budgetGuard,
                               @Autowired(required = false) Environment environment) {
        this.store=store; this.sources=sources; this.worker=worker; this.publishing=publishing; this.mapper=mapper;
        this.budgetGuard=budgetGuard; this.environment=environment;
    }

    @ModelAttribute
    void guard(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        var session=request.getSession(false);
        var user=session==null?null:(AuthenticatedXUser)session.getAttribute(XOAuthController.SESSION_AUTH_USER);
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            RequestSecuritySupport.verifySameOriginForMutation(request);
            if(!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) throw new XDataProviderException(403,"缺少同源请求标识");
        }
        boolean allowed=store.authorize(user);
        request.setAttribute("assistantAllowed",allowed);
        var handler=request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if(handler instanceof HandlerMethod h && h.getMethod().getName().equals("access")) return;
        if(!allowed) throw new XDataProviderException(user==null?401:403,user==null?"请登录 X 账号":"仅限内容助手所有者使用");
        request.setAttribute("assistantOwner",user);
    }

    @GetMapping("/access")
    public ObjectNode access(HttpServletRequest r) { return object().put("allowed",Boolean.TRUE.equals(r.getAttribute("assistantAllowed"))); }

    private void purgeExpired() {
        List<String> deletedTasks = sources.purgeExpired();
        if (deletedTasks != null) deletedTasks.forEach(worker::interrupt);
    }

    @GetMapping("/dashboard")
    public ObjectNode dashboard(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,
                                @RequestParam(required=false) Integer topicOffset, @RequestParam(defaultValue="") String q,
                                @RequestParam(defaultValue="") String username, @RequestParam(defaultValue="") String status,
                                @RequestParam(defaultValue="") String tag, @RequestParam(defaultValue="score") String sort,
                                HttpServletRequest request) {
        var owner=(AuthenticatedXUser)request.getAttribute("assistantOwner");
        purgeExpired();
        publishing.reconcile(owner.id());
        int size=limit(limit), start=Math.max(0,offset);
        int topicStart=topicOffset==null?start:Math.max(0,topicOffset);
        var out=store.tx(tx->{
            var d=object();
            var config=settings(tx);
            d.set("settings",config);
            d.set("capabilities",capabilities(tx,config));
            int threshold = config.path("threshold").asInt(80);
            var topics=topicPage(tx,size+1,topicStart,q,username,status,tag,threshold,sort);
            var counts=tx.topicCounts(threshold);
            d.put("topicCount",counts.total())
             .put("recommendedTopicCount",counts.recommended())
             .put("needsConfirmationCount",counts.needsConfirmation())
             .put("draftedTopicCount",counts.drafted());
            String provider = AssistantConnection.selectedProvider(config, environment);
            if (environment != null) {
                config = config.deepCopy()
                        .put("provider", provider)
                        .put("credentialConfigured", AssistantConnection.isProviderConfigured(environment, provider));
                d.set("settings", config);
                d.set("capabilities", capabilities(tx, config));
            }
            var configuredProviders = object()
                    .put("chatgpt", environment != null && AssistantConnection.isProviderConfigured(environment, "chatgpt"))
                    .put("minimax", environment != null && AssistantConnection.isProviderConfigured(environment, "minimax"))
                    .put("commandcode", environment != null && AssistantConnection.isProviderConfigured(environment, "commandcode"));
            String recModel = "chatgpt".equals(provider)
                    ? ChatGptOAuthTextModelClient.DEFAULT_MODEL_SPEC
                    : ("minimax".equals(provider) ? "MiniMax-M2.7" : "gpt-5.4");
            String recProto = "minimax".equals(provider) ? "MESSAGES" : "RESPONSES";
            int recCtx = ("chatgpt".equals(provider) || "minimax".equals(provider)) ? 200_000 : 128_000;
            d.set("providerDefaults", object()
                    .put("provider", provider)
                    .put("defaultProtocol", AssistantConnection.defaultProtocol(provider))
                    .put("recommendedModel", recModel)
                    .put("recommendedProtocol", recProto)
                    .put("recommendedContextTokens", recCtx)
                    .set("configuredProviders", configuredProviders));
            d.put("materialCount",tx.count("material"));
            d.put("topicHasNext",topics.size()>size);
            d.set("topics",mapper.valueToTree(topics.subList(0,Math.min(size,topics.size()))));
            d.set("materials",mapper.valueToTree(materialPage(tx,size,start,"","","","updated")));
            var tasks=d.putArray("tasks"); tx.page("task",size,start).forEach(task->tasks.add(publicTask(task)));
            var drafts=d.putArray("drafts");
            for(var draft:tx.page("draft",size,start)) {
                var changes=AssistantEvidence.changes(tx,draft);
                draft.put("sourcesChanged",changes.path("sourcesChanged").asBoolean());
                draft.set("evidenceChanges",changes.path("evidenceChanges"));
                drafts.add(DraftNumberChanges.withReport(tx,draft));
            }
            d.set("writingSkills", mapper.valueToTree(ViralWritingSkills.presets()));
            d.set("style",Optional.ofNullable(tx.get("meta","style")).orElse(object()));
            d.set("preferences",preferences(tx));
            d.set("publications",mapper.valueToTree(tx.page("publication",size,start)));
            d.set("autoReposts",mapper.valueToTree(tx.page("auto_repost",20,0)));
            d.set("observations",mapper.valueToTree(tx.page("observation",size,start)));
            d.set("followers",mapper.valueToTree(tx.page("followers",size,start)));
            return d;
        });
        out.set("sources",mapper.valueToTree(sources.list()));
        var sampleCoverage=sources.styleSamples().path("coverage");
        var style=(ObjectNode)out.path("style");
        style.put("sampleCount",sampleCoverage.path("collected").asInt()).set("coverage",sampleCoverage);
        out.set("history",sources.history());
        out.set("storage",store.storage());
        if (piJudge != null) out.set("piAgent", piJudge.checkAuth(false));
        if (budgetGuard != null) out.set("upstreamBudget", mapper.valueToTree(budgetGuard.snapshotMetrics()));
        return out;
    }

    @PostMapping("/settings")
    public ObjectNode updateSettings(@RequestBody ObjectNode body) {
        return store.tx(tx->{
            var config=settings(tx);
            if(body.has("version")) expect(config,body);
            if(body.has("provider")) {
                AssistantConnection.switchProvider(tx,config,body.path("provider").asText(""),environment);
                config.put("providerManuallySelected", true);
            }
            for(String key:List.of("enabled","collectionEnabled","modelEnabled")) if(body.has(key)) {
                if(!body.path(key).isBoolean()) throw new IllegalArgumentException(key+" 必须是布尔值");
                config.put(key,body.get(key).asBoolean());
            }
            for(String key:List.of("model","researchModel")) if(body.has(key)) {
                String value=body.path(key).asText("").strip();
                if(value.length()>200) throw new IllegalArgumentException("模型名称过长");
                config.put(key,value);
            }
            for(String key:List.of("protocol","researchProtocol")) if(body.has(key)) {
                var p=body.path(key).asText("").toUpperCase(Locale.ROOT);
                if(!Set.of("CHAT_COMPLETIONS","RESPONSES","MESSAGES").contains(p)) throw new IllegalArgumentException("无效的模型协议");
                config.put(key,p);
            }
            bounded(config,body,"intervalMinutes",1,1440); bounded(config,body,"valueWeight",0,100); bounded(config,body,"threshold",0,100);
            bounded(config,body,"contextTokens",0,10_000_000); bounded(config,body,"researchContextTokens",0,10_000_000);
            bounded(config,body,"timeoutSeconds",1,600); bounded(config,body,"maxOutputTokens",1,100_000);
            bounded(config,body,"researchTimeoutSeconds",1,600); bounded(config,body,"researchMaxOutputTokens",1,100_000);
            if(body.has("bindings")) config.set("bindings",validatedBindings(body.path("bindings")));
            if (body.has("scoreWeights")) {
                var weights = ContentIntelligenceEngine.scoreWeights(body.path("scoreWeights"));
                config.set("scoreWeights", mapper.valueToTree(weights));
            }
            return tx.put("meta","settings",config);
        });
    }
    private static void bounded(ObjectNode config,ObjectNode body,String key,int min,int max) {
        if(!body.has(key)) return;
        var n=body.get(key);
        if(!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt()<min || n.asInt()>max) throw new IllegalArgumentException(key+" 范围为 "+min+"–"+max);
        config.put(key,n.asInt());
    }

    private static ObjectNode validatedBindings(JsonNode value) {
        if(!value.isObject()) throw new IllegalArgumentException("bindings 必须是对象");
        Set<String> roles=Set.of("CLASSIFY","SCORE","STYLE","RESEARCH","DRAFT","OPTIMIZE");
        Set<String> fields=Set.of("model","protocol","contextTokens","timeoutSeconds","maxOutputTokens");
        var result=object();
        value.fields().forEachRemaining(entry->{
            String role=entry.getKey().toUpperCase(Locale.ROOT);
            if(!roles.contains(role) || !entry.getValue().isObject()) throw new IllegalArgumentException("无效的模型角色绑定: "+entry.getKey());
            var clean=object();
            entry.getValue().fields().forEachRemaining(field->{
                if(!fields.contains(field.getKey())) throw new IllegalArgumentException(role+" 包含未知字段: "+field.getKey());
                switch(field.getKey()) {
                    case "model" -> {
                        String model=field.getValue().asText("").strip();
                        if(model.isBlank() || model.length()>200) throw new IllegalArgumentException(role+" model 无效");
                        clean.put("model",model);
                    }
                    case "protocol" -> {
                        String protocol=field.getValue().asText("").toUpperCase(Locale.ROOT);
                        if(!Set.of("CHAT_COMPLETIONS","RESPONSES","MESSAGES").contains(protocol)) throw new IllegalArgumentException(role+" protocol 无效");
                        clean.put("protocol",protocol);
                    }
                    case "contextTokens" -> putBounded(clean,field.getKey(),field.getValue(),1024,10_000_000,role);
                    case "timeoutSeconds" -> putBounded(clean,field.getKey(),field.getValue(),1,600,role);
                    case "maxOutputTokens" -> putBounded(clean,field.getKey(),field.getValue(),1,100_000,role);
                }
            });
            result.set(role,clean);
        });
        return result;
    }

    private static void putBounded(ObjectNode target,String key,JsonNode value,int min,int max,String role) {
        if(!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<min || value.asInt()>max)
            throw new IllegalArgumentException(role+" "+key+" 范围为 "+min+"–"+max);
        target.put(key,value.asInt());
    }

    @PostMapping("/preferences")
    public ObjectNode updatePreferences(@RequestBody ObjectNode body) {
        return store.tx(tx->{ var prefs=preferences(tx); expect(prefs,body);
            if(!body.path("text").isTextual()) throw new IllegalArgumentException("偏好必须是文字");
            return tx.put("meta","preferences",object().put("text",body.path("text").asText()));
        });
    }
    @PostMapping("/preferences/reset")
    public ObjectNode resetPrefs(@RequestBody(required=false) ObjectNode body) {
        return store.tx(tx->resetPreferences(tx, body == null ? object() : body));
    }
    @PostMapping("/style")
    public ObjectNode updateStyle(@RequestBody ObjectNode body) {
        return store.tx(tx->reviseStyle(tx, body));
    }
    @PostMapping("/sources") public Object add(@RequestBody ObjectNode body) {
        String raw = field(body, "input");
        Integer explicitTier = null;
        if (body.has("intervalMinutes")) {
            if (!body.path("intervalMinutes").isIntegralNumber()) {
                throw new IllegalArgumentException("intervalMinutes 必须是整数");
            }
            explicitTier = SourceCollector.validateTierMinutes(body.path("intervalMinutes").asInt());
        }
        List<String> tokens = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : SOURCE_BATCH_DELIMITERS.split(raw)) {
            String trimmed = part == null ? "" : part.strip();
            if (trimmed.isEmpty()) continue;
            String dedupKey = normalizeSourceDedupKey(trimmed);
            if (seen.add(dedupKey)) tokens.add(trimmed);
        }
        if (tokens.isEmpty()) throw new IllegalArgumentException("缺少 input");
        if (tokens.size() == 1) {
            return explicitTier == null ? sources.add(tokens.get(0)) : sources.add(tokens.get(0), explicitTier);
        }
        var added = mapper.createArrayNode();
        var errors = mapper.createArrayNode();
        for (String token : tokens) {
            try {
                added.add(explicitTier == null ? sources.add(token) : sources.add(token, explicitTier));
            } catch (RuntimeException e) {
                errors.add(object().put("input", token).put("error", e.getMessage() == null ? "添加失败" : e.getMessage()));
            }
        }
        var summary = object().put("addedCount", added.size());
        summary.set("added", added);
        summary.set("errors", errors);
        return summary;
    }

    private static String normalizeSourceDedupKey(String raw) {
        String s = raw.strip();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                String path = java.net.URI.create(s).getPath();
                if (path != null) {
                    String[] segs = path.split("/");
                    if (segs.length > 1 && !segs[1].isBlank()) s = segs[1];
                }
            } catch (IllegalArgumentException ignored) {}
        }
        if (s.startsWith("@")) s = s.substring(1);
        return s.toLowerCase(Locale.ROOT);
    }
    @PostMapping("/sources/{id}/config")
    public Object updateSourceConfig(@PathVariable String id, @RequestBody ObjectNode body) {
        return sources.updateConfig(id, body);
    }
    @PostMapping("/sources/{id}/{action}") public Object change(@PathVariable String id,@PathVariable String action) { return sources.change(id,action); }

    @PostMapping("/research") @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode researchTask(@RequestBody ObjectNode b) { return publicTask(store.tx(tx->research(tx,b))); }

    @PostMapping("/pi-agent/verify")
    public ObjectNode verifyPiAgent() {
        return piJudge == null
                ? object().put("ready", false).put("status", "UNAVAILABLE").put("message", "PI Agent 判定组件未启用")
                : piJudge.checkAuth(true);
    }

    @PostMapping("/auto-reposts/{materialId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode retryAutoRepost(@PathVariable String materialId) {
        if (autoRepostService == null) {
            throw new XDataProviderException(503, "自动引用转发服务未启用");
        }
        return autoRepostService.retry(materialId);
    }

    @PostMapping("/capabilities/verify") @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode verify(@RequestBody ObjectNode b) { return publicTask(store.tx(tx->requestTask(tx,"VERIFY",b,object()))); }

    @PostMapping("/style/refresh") @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode refreshStyle(@RequestBody ObjectNode b) { return publicTask(store.tx(tx->requestTask(tx,"COLLECT_STYLE",b,object()))); }

    @GetMapping("/tasks/{id}") public ObjectNode task(@PathVariable String id) {return publicTask(store.tx(t->AssistantTopics.required(t,"task",id)));}
    @PostMapping("/tasks/{id}/{action}") public ObjectNode transitionTask(@PathVariable String id,@PathVariable String action) {
        var task=store.tx(t->transition(t,id,action));
        if(action.equals("cancel")) worker.interrupt(id);
        return publicTask(task);
    }
    @PostMapping("/drafts/{id}/save") public ObjectNode save(@PathVariable String id,@RequestBody ObjectNode b) {
        return store.tx(tx->saveDraft(tx,id,b));
    }
    @PostMapping("/drafts/{id}/optimize") @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode improve(@PathVariable String id,@RequestBody ObjectNode b) {return publicTask(store.tx(tx->optimize(tx,id,b)));}
    @GetMapping("/drafts/{id}/versions") public List<ObjectNode> versions(@PathVariable String id) {
        return store.tx(tx->tx.versions("draft",id).stream().map(draft->DraftNumberChanges.withReport(tx,draft)).toList());
    }
    @PostMapping("/drafts/{id}/update-scheduled")
    public Object update(@PathVariable String id,@RequestBody ObjectNode b,HttpServletRequest r) {
        var owner=(AuthenticatedXUser)r.getAttribute("assistantOwner");
        return publishing.update(owner.id(),owner.username(),id,b);
    }
    @PostMapping("/drafts/{id}/publish-now")
    public ObjectNode publishNow(@PathVariable String id, @RequestBody ObjectNode b, HttpServletRequest r) {
        var owner = (AuthenticatedXUser) r.getAttribute("assistantOwner");
        return publishing.publishNow(owner.id(), owner.username(), id, b);
    }
    @PostMapping("/topics/{id}/confirm")
    public ObjectNode confirm(@PathVariable String id, @RequestBody(required=false) ObjectNode body) {
        return store.tx(tx->confirmTopic(tx, id, body == null ? object() : body));
    }
    @GetMapping("/topics/{id}/detail")
    public ObjectNode topicDetail(@PathVariable String id) {
        return store.tx(tx->{
            var sourceIndex = buildSourceIndex(tx);
            var topic=AssistantTopics.required(tx,"topic",id);
            var result=object();
            var materials=result.putArray("materials");
            for (var mid : topic.path("materialIds")) {
                var mat = enrichMaterialAuthor(AssistantTopics.required(tx,"material",mid.asText()).put("topicId",id), sourceIndex);
                materials.add(mat);
            }
            enrichTopicAuthors(tx, topic, sourceIndex);
            result.set("topic",topic);
            var tasks=result.putArray("tasks");
            tx.matching("task","topicId",id,-1).forEach(t->tasks.add(publicTask(t)));
            var drafts=tx.matching("draft","topicId",id,-1);
            drafts.sort(Comparator.comparingLong((ObjectNode d)->d.path("updatedAt").asLong()).reversed());
            for(var draft:drafts) {
                var changes=AssistantEvidence.changes(tx,draft);
                draft.put("sourcesChanged",changes.path("sourcesChanged").asBoolean());
                draft.set("evidenceChanges",changes.path("evidenceChanges"));
                DraftNumberChanges.withReport(tx,draft);
            }
            result.set("drafts",mapper.valueToTree(drafts));
            return result;
        });
    }

    @PostMapping("/topics/move")
    public ObjectNode move(@RequestBody ObjectNode b) {
        List<String> ids=new ArrayList<>(); b.path("materialIds").forEach(v->{if(!ids.contains(v.asText()))ids.add(v.asText());});
        String target=b.path("targetTopicId").asText("").strip();
        return store.tx(tx->{
            if(!target.isEmpty()) AssistantTopics.required(tx,"topic",target);
            String dest=target.isEmpty()?id():target;
            AssistantTopics.move(tx,ids,dest,b.path("versions"),"OWNER").forEach(t->score(tx,t));
            return AssistantTopics.required(tx,"topic",dest);
        });
    }
    private static int limit(int n) {return Math.max(1,Math.min(n,200));}
    @GetMapping("/topics")
    public List<ObjectNode> topics(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,
                                   @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String username,
                                   @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String tag,
                                   @RequestParam(defaultValue="score") String sort) {
        purgeExpired();
        return store.tx(t->topicPage(t,limit(limit),Math.max(0,offset),q,username,status,tag,settings(t).path("threshold").asInt(80),sort));
    }
    @GetMapping("/materials")
    public List<ObjectNode> materials(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,
                                      @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String username,
                                      @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="published") String sort) {
        purgeExpired();
        return store.tx(t->materialPage(t,limit(limit),Math.max(0,offset),q,username,status,sort));
    }
    private static List<ObjectNode> materialPage(AssistantStore.Tx tx,int size,int start,String q,String user,String status,String sort) {
        var sourceIndex=buildSourceIndex(tx);
        var rows=tx.materials(size,start,q,user,status,sort);
        for(var m:rows) {
            m.put("topicId",AssistantTopics.topicOf(tx,m.path("id").asText()));
            enrichMaterialAuthor(m,sourceIndex);
        }
        return rows;
    }

    private static List<ObjectNode> topicPage(AssistantStore.Tx tx,int size,int start,String q,String user,String status,String tag,int threshold,String sort) {
        var sourceIndex=buildSourceIndex(tx);
        var rows=tx.topics(size,start,q,user,status,tag,threshold,sort);
        for(var t:rows) enrichTopicAuthors(tx,t,sourceIndex);
        return rows;
    }

    private static Map<String, ObjectNode> buildSourceIndex(AssistantStore.Tx tx) {
        Map<String, ObjectNode> index = new HashMap<>();
        for (ObjectNode src : tx.all("source")) {
            String aid = src.path("authorId").asText(src.path("id").asText(""));
            String uname = src.path("username").asText("").toLowerCase(Locale.ROOT);
            if (!aid.isBlank()) index.put("id:" + aid, src);
            if (!uname.isBlank()) index.put("u:" + uname, src);
        }
        return index;
    }

    private static ObjectNode enrichMaterialAuthor(ObjectNode material, Map<String, ObjectNode> sourceIndex) {
        String authorId = material.path("authorId").asText("");
        String username = material.path("username").asText("");
        ObjectNode src = !authorId.isBlank() ? sourceIndex.get("id:" + authorId) : null;
        if (src == null && !username.isBlank()) src = sourceIndex.get("u:" + username.toLowerCase(Locale.ROOT));
        String displayName = material.path("authorName").asText("");
        if (displayName.isBlank() && src != null) displayName = src.path("displayName").asText("");
        if (displayName.isBlank()) displayName = username;
        String avatarUrl = material.path("authorAvatarUrl").asText("");
        if (avatarUrl.isBlank() && src != null) avatarUrl = src.path("avatarUrl").asText("");
        boolean verified = material.path("authorVerified").asBoolean(false)
                || (src != null && src.path("verified").asBoolean(false));
        material.put("authorName", displayName)
                .put("authorAvatarUrl", avatarUrl)
                .put("authorVerified", verified);
        return material;
    }

    private static void enrichTopicAuthors(AssistantStore.Tx tx, ObjectNode topic, Map<String, ObjectNode> sourceIndex) {
        var authors = topic.putArray("authors");
        Set<String> seen = new LinkedHashSet<>();
        List<ObjectNode> mats = new ArrayList<>();
        for (JsonNode mid : topic.path("materialIds")) {
            ObjectNode mat = tx.get("material", mid.asText(""));
            if (mat == null) continue;
            mats.add(mat);
            enrichMaterialAuthor(mat, sourceIndex);
            String uname = mat.path("username").asText("");
            String aid = mat.path("authorId").asText("");
            String key = !aid.isBlank() ? aid : uname.toLowerCase(Locale.ROOT);
            if (key.isBlank() || !seen.add(key)) continue;
            authors.add(object()
                    .put("authorId", aid)
                    .put("username", uname)
                    .put("displayName", mat.path("authorName").asText(uname))
                    .put("avatarUrl", mat.path("authorAvatarUrl").asText(""))
                    .put("verified", mat.path("authorVerified").asBoolean(false)));
        }
        String recId = ViralWritingSkills.recommendSkill(topic, mats);
        topic.put("recommendedSkillId", recId);
        topic.put("recommendedSkillName", ViralWritingSkills.resolveOrDefault(recId).name());
    }
}
