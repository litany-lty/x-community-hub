package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.ScheduledTweetModels.*;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.ScheduledTweetService.MediaUploadInput;
import org.springframework.stereotype.Service;

import java.security.*;
import java.util.*;
import static com.xops.hub.assistant.AssistantStore.object;

@Service
public final class AssistantPublishing {
    private final AssistantStore store;
    private final ScheduledTweetService queue;

    public AssistantPublishing(AssistantStore store,ScheduledTweetService queue) { this.store=store; this.queue=queue; }

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
}
