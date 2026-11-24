package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.ScheduledTweetModels.*;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.provider.XPublishClient.XPublishException;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.ScheduledTweetService.MediaUploadInput;
import com.xops.hub.service.XOAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.xops.hub.assistant.AssistantStore.object;

@Service
public final class AssistantPublishing {
    private static final Pattern STATUS_ID_URL = Pattern.compile("/status/([0-9]+)");
    private final AssistantStore store;
    private final ScheduledTweetService queue;
    private final XOAuthService oauthService;
    private final XPublishClient publishClient;

    public AssistantPublishing(AssistantStore store, ScheduledTweetService queue) {
        this(store, queue, null, null);
    }

    @Autowired
    public AssistantPublishing(
            AssistantStore store,
            ScheduledTweetService queue,
            XOAuthService oauthService,
            XPublishClient publishClient
    ) {
        this.store = store;
        this.queue = queue;
        this.oauthService = oauthService;
        this.publishClient = publishClient;
    }

    public AssistantSource prepare(String accountId,String draftId,Integer version,String intentId,String text,long time,List<MediaUploadInput> files) {
        if(draftId==null && version==null) return null;
        if(!store.isOwner(accountId)) throw new XDataProviderException(403,"作者身份校验失败");
        if(draftId==null || version==null || version<1 || intentId==null || intentId.isBlank() || intentId.length()>128)
            throw new IllegalArgumentException("排期需要稿件、已保存版本和请求标识");
        var request=object().put("draftId",draftId).put("draftVersion",version).put("text",text==null?"":text).put("scheduledAtEpochMs",time);
        var hashes=request.putArray("mediaDigests");
        try { for(var file:files) hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes()))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        String fingerprint=AssistantWorkflow.digest(request);
        var prior=store.tx(t->t.get("publication",intentId));
        if(prior!=null && prior.hasNonNull("jobId") && queue.assistantJobs(accountId).stream().noneMatch(e->e.job().id().equals(prior.path("jobId").asText())))
            throw new XDataProviderException(409,"原发布任务暂无法对账，已保留意图及回执");
        return store.tx(t->{
            var saved=t.version("draft",draftId,version);
            if(saved==null) throw new XDataProviderException(404,"稿件版本不存在");
            var old=t.get("publication",intentId);
            if(old==null) {
                var intent=request.deepCopy().put("status","PENDING").put("requestDigest",fingerprint)
                        .put("topicId",saved.path("topicId").asText()).put("topicVersion",saved.path("topicVersion").asInt())
                        .put("researchId",saved.path("researchId").asText());
                String skillId = ViralWritingSkills.normalizeId(saved.path("skillId").asText(""));
                if (!skillId.isEmpty()) intent.put("skillId", skillId);
                t.put("publication",intentId,intent);
            } else if(!fingerprint.equals(old.path("requestDigest").asText())) throw new XDataProviderException(409,"同一发布意图已绑定其他内容");
            return source(saved,intentId,fingerprint,null);
        });
    }

    private AssistantSource source(ObjectNode draft,String intent,String digest,String op) {
        return new AssistantSource(intent,draft.path("id").asText(),draft.path("version").asInt(),draft.path("topicId").asText(),
                draft.path("topicVersion").asInt(),draft.path("researchId").asText(),digest,op);
    }

    public void record(ScheduledTweetJob job) { record(job,false); }

    private void record(ScheduledTweetJob job,boolean deleted) {
        if(job==null || job.assistantSource()==null) return;
        var src=job.assistantSource();
        store.tx(t->{
            var p=t.get("publication",src.intentId());
            if(p!=null && (p.path("deleted").asBoolean() || p.path("queueUpdatedAt").asLong(-1)>job.updatedAtEpochMs())) return null;
            if(p!=null && !deleted && p.path("queueUpdatedAt").asLong(-1)==job.updatedAtEpochMs()
                    && p.path("status").asText().equals(job.status().name()) && p.path("draftVersion").asInt()==src.draftVersion()) return null;
            if(p==null) p=object();
            p.put("deleted",deleted).put("requestDigest",src.requestDigest()).put("draftId",src.draftId()).put("draftVersion",src.draftVersion())
                    .put("topicId",src.topicId()).put("topicVersion",src.topicVersion()).put("researchId",src.researchId())
                    .put("jobId",job.id()).put("text",job.text()).put("status",job.status().name())
                    .put("scheduledAtEpochMs",job.scheduledAtEpochMs()).put("queueUpdatedAt",job.updatedAtEpochMs())
                    .put("publishedTweetId",job.publishedTweetId()).put("publishedAt",job.publishedAtEpochMs());
            if (p.path("skillId").asText("").isBlank() && src.draftId() != null && !src.draftId().isBlank()) {
                ObjectNode d = t.version("draft", src.draftId(), src.draftVersion());
                if (d == null) d = t.get("draft", src.draftId());
                if (d != null) {
                    String skillId = ViralWritingSkills.normalizeId(d.path("skillId").asText(""));
                    if (!skillId.isEmpty()) p.put("skillId", skillId);
                }
            }
            var media=p.putArray("mediaDigests");
            job.media().forEach(f->media.add(f.sha256()));
            t.put("publication",src.intentId(),p);
            return null;
        });
    }

    public void reconcile(String owner) { queue.assistantJobs(owner).forEach(entry->record(entry.job(),entry.deleted())); }

    public ObjectNode update(String account,String username,String draftId,ObjectNode body) {
        String intent=AssistantWorkflow.field(body,"intentId");
        String operation=AssistantWorkflow.field(body,"operationId");
        var draft=store.tx(t->AssistantTopics.required(t,"draft",draftId));
        AssistantWorkflow.expect(draft,body);
        var pub=store.tx(t->AssistantTopics.required(t,"publication",intent));
        if(!draftId.equals(pub.path("draftId").asText())) throw new XDataProviderException(409,"稿件与排期来源不一致");
        var updated=queue.updateAssistantJob(account,username,pub.path("jobId").asText(),body.path("expectedUpdatedAt").asLong(-1),
                draft.path("text").asText(),source(draft,intent,pub.path("requestDigest").asText(),operation));
        record(updated);
        return store.tx(t->t.get("publication",intent));
    }

    public ObjectNode publishNow(String accountId, String username, String draftId, ObjectNode body) {
        if (!store.isOwner(accountId)) throw new XDataProviderException(403, "作者身份校验失败");
        if (oauthService == null || publishClient == null) {
            throw new XDataProviderException(503, "直发发布服务未启用");
        }
        String clientRequestId = AssistantWorkflow.field(body, "clientRequestId");
        if (clientRequestId.length() > 128) throw new IllegalArgumentException("请求标识过长");
        boolean quoteSource = body.path("quoteSource").asBoolean(false);
        String pubKey = "direct-" + clientRequestId;

        record PreparedDirectPublish(
                ObjectNode existingPublished,
                String requestDigest,
                int draftVersion,
                String topicId,
                int topicVersion,
                String researchId,
                String skillId,
                String text,
                String quoteTweetId
        ) { }

        PreparedDirectPublish prepared = store.tx(tx -> {
            ObjectNode draft = AssistantTopics.required(tx, "draft", draftId);
            String quoteTweetId = quoteSource ? resolveQuoteTweetId(tx, draft) : null;
            if (quoteSource && quoteTweetId == null) {
                throw new IllegalArgumentException("该素材不含有效推文 ID，不支持引用原帖发布");
            }
            int requestedVersion = body.path("version").asInt(-1);
            ObjectNode digestPayload = object()
                    .put("draftId", draftId)
                    .put("draftVersion", requestedVersion)
                    .put("quoteSource", quoteSource)
                    .put("quoteTweetId", quoteTweetId == null ? "" : quoteTweetId);
            String requestDigest = AssistantWorkflow.digest(digestPayload);
            ObjectNode existing = tx.get("publication", pubKey);
            if (existing != null) {
                if (!requestDigest.equals(existing.path("requestDigest").asText(""))) {
                    throw new XDataProviderException(409, "同一请求标识已用于其他发布内容");
                }
                String existingStatus = existing.path("status").asText("");
                if ("PUBLISHED".equals(existingStatus)) {
                    return new PreparedDirectPublish(existing, requestDigest, requestedVersion, "", 0, "", "", "", quoteTweetId);
                }
                if ("SENDING".equals(existingStatus) || "UNCERTAIN".equals(existingStatus)) {
                    throw new XDataProviderException(409, "该发布请求正在处理或回执待确认，请勿重复提交");
                }
            }
            AssistantWorkflow.expect(draft, body);
            String text = draft.path("text").asText("").strip();
            if (text.isBlank()) throw new IllegalArgumentException("稿件正文为空，无法发布");
            return new PreparedDirectPublish(
                    null,
                    requestDigest,
                    draft.path("version").asInt(),
                    draft.path("topicId").asText(""),
                    draft.path("topicVersion").asInt(0),
                    draft.path("researchId").asText(""),
                    ViralWritingSkills.normalizeId(draft.path("skillId").asText("")),
                    text,
                    quoteTweetId
            );
        });

        if (prepared.existingPublished() != null) {
            return prepared.existingPublished();
        }

        String accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, accountId,
                () -> oauthService.getValidWriteAccessToken(accountId, false));

        store.tx(tx -> {
            ObjectNode sending = object()
                    .put("requestDigest", prepared.requestDigest())
                    .put("draftId", draftId)
                    .put("draftVersion", prepared.draftVersion())
                    .put("topicId", prepared.topicId())
                    .put("topicVersion", prepared.topicVersion())
                    .put("researchId", prepared.researchId())
                    .put("text", prepared.text())
                    .put("status", "SENDING")
                    .put("source", prepared.quoteTweetId() != null ? "DIRECT_QUOTE_PUBLISH" : "DIRECT_PUBLISH");
            if (!prepared.skillId().isEmpty()) sending.put("skillId", prepared.skillId());
            if (prepared.quoteTweetId() != null) sending.put("quoteTweetId", prepared.quoteTweetId());
            sending.putArray("mediaDigests");
            tx.put("publication", pubKey, sending);
            return null;
        });

        final String publishedTweetId;
        try {
            publishedTweetId = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, accountId,
                    () -> publishClient.createTweet(accessToken, prepared.text(), List.of(), prepared.quoteTweetId()));
        } catch (XPublishException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("publication", pubKey);
                if (rec != null) {
                    tx.put("publication", pubKey, rec.deepCopy()
                            .put("status", ex.isDeliveryUncertain() ? "UNCERTAIN" : "FAILED")
                            .put("error", ex.getMessage() == null ? "发布失败" : ex.getMessage()));
                }
                return null;
            });
            throw ex;
        }

        long publishedAt = System.currentTimeMillis();
        String uname = (username == null || username.isBlank()) ? "i" : username;
        String publishedUrl = "https://x.com/" + uname + "/status/" + publishedTweetId;
        return store.tx(tx -> {
            ObjectNode pub = object()
                    .put("requestDigest", prepared.requestDigest())
                    .put("draftId", draftId)
                    .put("draftVersion", prepared.draftVersion())
                    .put("topicId", prepared.topicId())
                    .put("topicVersion", prepared.topicVersion())
                    .put("researchId", prepared.researchId())
                    .put("text", prepared.text())
                    .put("status", "PUBLISHED")
                    .put("source", prepared.quoteTweetId() != null ? "DIRECT_QUOTE_PUBLISH" : "DIRECT_PUBLISH")
                    .put("publishedTweetId", publishedTweetId)
                    .put("publishedTweetUrl", publishedUrl)
                    .put("publishedAt", publishedAt);
            if (!prepared.skillId().isEmpty()) {
                pub.put("skillId", prepared.skillId());
            }
            if (prepared.quoteTweetId() != null) {
                pub.put("quoteTweetId", prepared.quoteTweetId());
            }
            pub.putArray("mediaDigests");
            return tx.put("publication", pubKey, pub);
        });
    }

    private static String resolveQuoteTweetId(AssistantStore.Tx tx, ObjectNode draft) {
        String materialId = draft.path("materialId").asText("").strip();
        if (materialId.matches("[0-9]+")) return materialId;
        if (!materialId.isBlank()) {
            ObjectNode mat = tx.get("material", materialId);
            if (mat != null) {
                String matId = mat.path("id").asText("").strip();
                if (matId.matches("[0-9]+")) return matId;
                Matcher m = STATUS_ID_URL.matcher(mat.path("sourceUrl").asText(""));
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }
}
