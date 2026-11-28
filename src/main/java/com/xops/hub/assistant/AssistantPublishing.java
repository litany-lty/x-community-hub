package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.XPostLink;
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
import java.util.concurrent.ConcurrentHashMap;
import static com.xops.hub.assistant.AssistantStore.object;

@Service
public final class AssistantPublishing {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssistantPublishing.class);
    private final AssistantStore store;
    private final ScheduledTweetService queue;
    private final XOAuthService oauthService;
    private final XPublishClient publishClient;
    private final Map<String, ObjectNode> pendingDirectReceipts = new ConcurrentHashMap<>();

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
        return prepare(accountId, draftId, version, intentId, text, time, files, false, null);
    }

    public AssistantSource prepareRewrite(String accountId, RewriteSource rewrite, String intent, String text, long time,
                                          List<MediaUploadInput> files, String quoteId) {
        if (intent == null || intent.isBlank() || intent.length() > 128)
            throw new IllegalArgumentException("洗稿排期缺少请求标识");
        var request = object().put("ownerAccountId", rewrite.ownerAccountId()).put("materialId", rewrite.materialId())
                .put("recordVersion", rewrite.recordVersion()).put("text", text).put("time", time).put("quoteId", quoteId);
        if (rewrite.previewId() != null) request.put("previewId", rewrite.previewId());
        var hashes = request.putArray("mediaDigests");
        try {
            for (var file : files) hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes())));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var source = new AssistantSource(intent, null, 0, null, 0, null, AssistantWorkflow.digest(request), null, quoteId, rewrite);
        store.tx(tx -> {
            ScheduledPublicationGuard.requireRewriteOwner(tx, source, accountId, text);
            return null;
        });
        return source;
    }

    public AssistantSource prepare(String accountId,String draftId,Integer version,String intentId,String text,long time,List<MediaUploadInput> files,boolean quoteSource,String explicitQuoteTweetId) {
        return prepareDraftSchedule(accountId,draftId,version,intentId,text,time,files,quoteSource,explicitQuoteTweetId).source();
    }

    public record ScheduleSource(AssistantSource source, String quoteId, String quoteUrl) {}

    public ScheduleSource prepareDraftSchedule(String accountId,String draftId,Integer version,String intentId,String text,long time,
                                              List<MediaUploadInput> files,boolean quoteSource,String explicitQuoteTweetId) {
        if(draftId==null && version==null) return new ScheduleSource(null,explicitQuoteTweetId,null);
        if(!store.isOwner(accountId)) throw new XDataProviderException(403,"作者身份校验失败");
        if(draftId==null || version==null || version<1 || intentId==null || intentId.isBlank() || intentId.length()>128)
            throw new IllegalArgumentException("排期需要稿件、已保存版本和请求标识");
        String cleanExplicitQuoteId = (explicitQuoteTweetId == null || explicitQuoteTweetId.isBlank())
                ? null : explicitQuoteTweetId.strip();
        if (cleanExplicitQuoteId != null && !cleanExplicitQuoteId.matches("[0-9]{2,20}")) {
            throw new IllegalArgumentException("被引用推文 ID 格式无效");
        }
        var request=object().put("draftId",draftId).put("draftVersion",version).put("text",text==null?"":text).put("scheduledAtEpochMs",time);
        var mediaDigests = new ArrayList<String>();
        try { for(var file:files) mediaDigests.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes()))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        mediaDigests.forEach(request.putArray("mediaDigests")::add);
        var prior=store.tx(t->t.get("publication",intentId));
        if(prior!=null && prior.hasNonNull("jobId") && queue.assistantJobs(accountId).stream().noneMatch(e->e.job().id().equals(prior.path("jobId").asText())))
            throw new XDataProviderException(409,"原发布任务暂无法对账，已保留意图及回执");
        return store.tx(t->{
            var saved=t.version("draft",draftId,version);
            if(saved==null) throw new XDataProviderException(404,"稿件版本不存在");
            var old=t.get("publication",intentId);
            String quoteTweetId = null;
            XPostLink sourcePost = null;
            if (quoteSource || cleanExplicitQuoteId != null) {
                sourcePost = draftSourcePost(t, saved);
                if (old != null && old.hasNonNull("jobId") && draftId.equals(old.path("draftId").asText())
                        && old.path("creationDraftVersion").asInt(old.path("draftVersion").asInt()) == version) {
                    quoteTweetId = old.path("creationQuoteTweetId").asText(old.path("requestedQuoteTweetId").asText(old.path("quoteTweetId").asText(null)));
                }
                if (quoteTweetId == null) {
                    if (sourcePost == null) throw new IllegalArgumentException("该素材不含有效推文 ID，不支持引用原帖发布");
                    quoteTweetId = sourcePost.tweetId();
                }
                if (cleanExplicitQuoteId != null && !quoteTweetId.equals(cleanExplicitQuoteId)) {
                    throw new XDataProviderException(409, "引用目标与稿件保存时的原帖不一致，请重新从稿件创建排期");
                }
            }
            ObjectNode digestNode = request.deepCopy();
            if (quoteTweetId != null) {
                digestNode.put("quoteTweetId", quoteTweetId);
            }
            String fingerprint=AssistantWorkflow.digest(digestNode);
            if(old!=null && !fingerprint.equals(old.path("requestDigest").asText())) throw new XDataProviderException(409,"同一发布意图已绑定其他内容");
            if (old == null) ScheduledPublicationGuard.check(t, saved, quoteTweetId, text, mediaDigests);
            String quoteUrl = quoteTweetId == null ? null : sourcePost != null && quoteTweetId.equals(sourcePost.tweetId())
                    ? sourcePost.url() : new XPostLink(quoteTweetId,null).url();
            return new ScheduleSource(source(saved,intentId,fingerprint,null,quoteTweetId), quoteTweetId, quoteUrl);
        });
    }

    private AssistantSource source(ObjectNode draft,String intent,String digest,String op) {
        return source(draft, intent, digest, op, null);
    }

    private AssistantSource source(ObjectNode draft,String intent,String digest,String op,String quoteTweetId) {
        return new AssistantSource(intent,draft.path("id").asText(),draft.path("version").asInt(),draft.path("topicId").asText(),
                draft.path("topicVersion").asInt(),draft.path("researchId").asText(),digest,op,quoteTweetId);
    }

    public void record(ScheduledTweetJob job) { record(job,false); }

    private void record(ScheduledTweetJob job,boolean deleted) {
        if(job==null || job.assistantSource()==null || job.assistantSource().rewriteSource()!=null) return;
        var src=job.assistantSource();
        var receipt = job.publicationReceipt();
        store.tx(t->{
            var p=t.get("publication",src.intentId());
            if (p != null && "PUBLISHED".equals(p.path("status").asText()) && p.hasNonNull("publishedTweetId")
                    && job.status() != ScheduledTweetStatus.PUBLISHED) return null;
            if(p!=null && ((p.path("deleted").asBoolean() && !deleted)
                    || p.path("queueUpdatedAt").asLong(-1)>job.updatedAtEpochMs())) return null;
            if(p!=null && p.path("deleted").asBoolean()==deleted && p.path("queueUpdatedAt").asLong(-1)==job.updatedAtEpochMs()
                    && p.path("status").asText().equals(job.status().name()) && p.path("draftVersion").asInt()==src.draftVersion()
                    && p.path("skillDraftVersion").asInt(-1)==src.draftVersion()
                    && (receipt == null || receipt.mode().toString().equals(p.path("publicationMode").asText()))) return null;
            if(p==null) p=object();
            if (!p.has("creationDraftVersion")) {
                ObjectNode initial = p;
                if (p.hasNonNull("jobId")) {
                    var history = t.versions("publication", src.intentId());
                    for (var version : history) if (version.hasNonNull("jobId")) initial = version;
                }
                p.put("creationDraftVersion", initial.path("draftVersion").asInt(src.draftVersion()));
                String initialQuote = initial.path("requestedQuoteTweetId").asText(initial.path("quoteTweetId").asText(job.quoteTweetId()));
                if (initialQuote != null) p.put("creationQuoteTweetId", initialQuote);
            }
            p.put("ownerAccountId", job.accountId()).put("uncertainDelivery", job.uncertainDelivery());
            if(p.path("draftVersion").asInt(-1) != src.draftVersion()) p.remove("skillId");
            p.put("deleted",deleted).put("requestDigest",src.requestDigest()).put("draftId",src.draftId()).put("draftVersion",src.draftVersion())
                    .put("topicId",src.topicId()).put("topicVersion",src.topicVersion()).put("researchId",src.researchId())
                    .put("jobId",job.id()).put("text",job.text()).put("status",job.status().name())
                    .put("scheduledAtEpochMs",job.scheduledAtEpochMs()).put("queueUpdatedAt",job.updatedAtEpochMs())
                    .put("publishedTweetId",job.publishedTweetId()).put("publishedAt",job.publishedAtEpochMs());
            String requestedQuoteId = (job.quoteTweetId() != null && !job.quoteTweetId().isBlank())
                    ? job.quoteTweetId() : src.quoteTweetId();
            p.put("requestedText", job.text());
            if (job.overdueReason() == OverdueReason.AUTHOR_VERIFIED)
                p.put("verifiedAt", job.confirmedAtEpochMs());
            if (requestedQuoteId != null && !requestedQuoteId.isBlank()) p.put("requestedQuoteTweetId", requestedQuoteId);
            else p.remove(List.of("requestedQuoteTweetId", "quoteTweetId"));
            if (receipt != null) {
                p.put("text", receipt.text()).put("publicationMode", receipt.mode().toString());
                p.remove("quoteTweetId");
                if (receipt.quoteTweetId() != null) p.put("quoteTweetId", receipt.quoteTweetId());
            } else if (job.status() == ScheduledTweetStatus.PUBLISHED) {
                p.remove("quoteTweetId");
            } else if (requestedQuoteId != null && !requestedQuoteId.isBlank()) {
                p.put("quoteTweetId", requestedQuoteId);
            }
            if (src.draftId() != null && !src.draftId().isBlank()) {
                ObjectNode draft = t.version("draft", src.draftId(), src.draftVersion());
                if (draft != null) {
                    p.put("materialId", publicationMaterial(t, draft, requestedQuoteId));
                    String skillId = ViralWritingSkills.normalizeId(draft.path("skillId").asText(""));
                    if (skillId.isEmpty()) p.remove("skillId"); else p.put("skillId", skillId);
                }
            }
            p.put("skillDraftVersion", src.draftVersion());
            if (src.updateOperationId() != null) p.put("updateOperationId", src.updateOperationId());
            var media=p.putArray("mediaDigests");
            job.media().forEach(f->media.add(f.sha256()));
            t.put("publication",src.intentId(),p);
            return null;
        });
    }

    public void reconcile(String owner) {
        store.reconcilePublicationFailures();
        if (queue != null) {
            reconcileQueueVerifications(owner);
            queue.reconcileOwnerDeliveryGuards(owner);
        }
        store.tx(tx -> tx.publicationsByStatus("UNCERTAIN")).forEach(row -> {
            if (row.get("acceptedReceipt") instanceof ObjectNode receipt) pendingDirectReceipts.putIfAbsent(row.path("id").asText(), receipt);
        });
        for (var saved : store.pendingReceipts("direct-accepted"))
            pendingDirectReceipts.putIfAbsent(saved.path("key").asText(), (ObjectNode) saved.path("receipt"));
        pendingDirectReceipts.forEach((id, receipt) -> {
            try {
                writeAcceptedReceipt(id, receipt.put("receiptRecovered", true));
            } catch (RuntimeException error) {
                log.warn("Could not reconcile confirmed publication {}", id, error);
            }
        });
        if (queue != null) queue.assistantJobs(owner).forEach(entry->record(entry.job(),entry.deleted()));
        store.tx(tx -> {
            for (ObjectNode old : tx.publicationsByStatus("PENDING")) {
                if (!old.hasNonNull("jobId") && old.hasNonNull("draftId")) {
                    tx.put("publication", old.path("id").asText(), old.put("status", "FAILED").put("error", "该发布请求未进入定时队列，可重新排期"));
                }
            }
            return null;
        });
    }

    private void reconcileQueueVerifications(String owner) {
        if (owner == null || owner.isBlank()) return;
        for (var pending : store.tx(tx -> tx.all("scheduled_verification"))) {
            if (!owner.equals(pending.path("ownerAccountId").asText())) continue;
            try { verifyScheduledPublication(owner, pending.path("id").asText(), (ObjectNode) pending.get("decision")); }
            catch (RuntimeException unavailable) { log.warn("Could not reconcile scheduled verification {}", pending.path("id"), unavailable); }
        }
    }

    @jakarta.annotation.PostConstruct
    void recoverInterruptedPublishes() {
        if (queue != null) reconcileQueueVerifications(store.boundOwnerId());
        store.reconcilePublicationFailures();
        for (var saved : store.pendingReceipts("direct-accepted")) {
            var receipt = (ObjectNode) saved.path("receipt");
            String key = saved.path("key").asText();
            pendingDirectReceipts.put(key, receipt);
            try { writeAcceptedReceipt(key, receipt.put("receiptRecovered", true)); }
            catch (RuntimeException unavailable) { log.warn("Could not recover confirmed publication {}", key, unavailable); }
        }
        store.tx(tx -> {
            for (ObjectNode sending : tx.publicationsByStatus("SENDING")) {
                if (!canVerify(sending)) continue;
                // Older releases did not persist the dispatch boundary.
                boolean uncertain = sending.path("postRequestDispatched").asBoolean(true);
                tx.put("publication", sending.path("id").asText(), sending.put("status", uncertain ? "UNCERTAIN" : "FAILED")
                        .put("error", uncertain ? "服务在收取回执前重启，请核实 X 时间线" : "发出请求前服务重启，可重新发布"));
            }
            return null;
        });
    }

    static boolean canVerify(ObjectNode publication) {
        String source = publication.path("source").asText("");
        return !publication.path("ownerReviewRequired").asBoolean() && !publication.hasNonNull("jobId") && (source.startsWith("DIRECT_") || source.startsWith("MANUAL_"));
    }

    static void markRequestDispatched(AssistantStore store, String id, String expectedOwner, long at) {
        markRequestDispatched(store, id, expectedOwner, at, null);
    }

    static void markRequestDispatched(AssistantStore store, String id, String expectedOwner, long at, XPublishClient.PublicationPayload payload) {
        store.tx(tx -> {
            ObjectNode row = AssistantTopics.required(tx, "publication", id);
            if (!tx.publicationOwnerMatches(row, expectedOwner))
                throw new XPublishException(403, "原发布账号已变更，已停止发送", false);
            if (!"SENDING".equals(row.path("status").asText())) throw new XPublishException(409, "发布请求状态已更新，请刷新", false);
            if (row.path("source").asText().startsWith("MANUAL_")
                    && !tx.retainedMaterialAuthorMatches(row.path("materialId").asText(), row.path("authorId").asText()))
                throw new XPublishException(409, "所选原帖的作者已变更，请刷新后核对", false);
            String quoteId = row.path("requestedQuoteTweetId").asText(row.path("quoteTweetId").asText(""));
            String text = row.path("requestedText").asText(row.path("text").asText(""));
            try {
                String draftId = row.path("draftId").asText("");
                int revision = row.path("draftVersion").asInt();
                ScheduledPublicationGuard.requireAvailableCopy(tx, draftId, revision, quoteId, text, List.of(), "publication", id, 0);
                if (payload != null && !text.equals(payload.text()))
                    ScheduledPublicationGuard.requireAvailableCopy(tx, draftId, revision, quoteId, payload.text(), List.of(), "publication", id, 0);
                ScheduledPublicationGuard.checkTopic(tx, row, row.path("materialId").asText(quoteId), quoteId, "", id);
            } catch (XDataProviderException conflict) {
                throw new XPublishException(conflict.getStatusCode(), conflict.getMessage(), conflict, false);
            }
            if (payload != null) {
                row.put("text", payload.text()).put("publicationMode", payload.mode().name());
                if (payload.quoteTweetId() == null) row.remove(List.of("quoteTweetId", "quotedTweetId"));
                else row.put("quoteTweetId", payload.quoteTweetId()).put("quotedTweetId", payload.quoteTweetId());
                if (row.path("source").asText().startsWith("MANUAL_"))
                    row.put("source", AutoRepostService.quoteSource("MANUAL", payload.mode()));
            }
            if (payload != null || !row.path("postRequestDispatched").asBoolean()) {
                tx.put("publication", id, row.put("postRequestDispatched", true).put("dispatchedAt", at));
            }
            return null;
        });
    }

    public ObjectNode verifyPublication(String owner, String id, ObjectNode decision) {
        if (!store.isOwner(owner)) throw new XDataProviderException(403, "作者身份校验失败");
        restoreDirectReceipt(id);
        return store.tx(tx -> {
            ObjectNode row = AssistantTopics.required(tx, "publication", id);
            if (!tx.publicationOwnerMatches(row, owner)) throw new XDataProviderException(409, "这条发布记录未绑定到当前作者");
            if (!canVerify(row)) throw new XDataProviderException(409, "该记录当前无需核实，请刷新");
            if (!applyVerification(tx, "publication", row, decision, System.currentTimeMillis())) return row;
            if (decision.path("published").asBoolean() && row.path("source").asText().startsWith("MANUAL_") && row.hasNonNull("materialId")) {
                String materialId = row.path("materialId").asText();
                var source = tx.get("material", materialId);
                if (source == null) source = tx.get("auto_repost", materialId);
                if (source != null) {
                    for (String field : List.of("authorId", "username")) {
                        if (row.path(field).asText("").isBlank()) row.put(field, source.path(field).asText(""));
                    }
                }
            }
            return tx.put("publication", id, row);
        });
    }

    public ObjectNode verifyScheduledPublication(String owner, String id, ObjectNode decision) {
        if (!store.isOwner(owner)) throw new XDataProviderException(403, "作者身份校验失败");
        ObjectNode guard = store.tx(tx -> {
            ObjectNode row = AssistantTopics.required(tx, "scheduled_delivery", id);
            if (!tx.publicationOwnerMatches(row, owner)) throw new XDataProviderException(409, "这条排期不属于当前作者");
            if (!applyVerification(tx, "scheduled_delivery", row.deepCopy(), decision, System.currentTimeMillis())) {
                tx.removeQueueVerification(id);
                return row;
            }
            var normalized = object().put("version", decision.path("version").asInt()).put("published", decision.path("published").asBoolean());
            if (normalized.path("published").asBoolean()) normalized.put("tweetId", decision.path("tweetId").asText().strip());
            var pending = tx.get("scheduled_verification", id);
            if (pending == null) {
                var intent = object().put("ownerAccountId", owner).put("attemptCount", row.path("attemptCount").asInt());
                intent.set("decision", normalized);
                tx.put("scheduled_verification", id, intent);
            } else if (!normalized.equals(pending.get("decision")) || pending.path("attemptCount").asInt() != row.path("attemptCount").asInt()) {
                throw new XDataProviderException(409, "该排期的核实结果正在保存，请刷新");
            }
            return row;
        });
        if (guard.hasNonNull("verifiedAt")) return guard;
        String tweetId = decision.path("published").asBoolean() ? decision.path("tweetId").asText().strip() : null;
        ScheduledTweetService.AssistantQueueEntry entry;
        try {
            entry = queue.registerDelivery(owner, id, guard.path("attemptCount").asInt(), tweetId);
        } catch (XDataProviderException rejected) {
            if (rejected.getStatusCode() == 409) store.tx(tx -> { tx.removeQueueVerification(id); return null; });
            throw rejected;
        }
        if (entry != null) record(entry.job(), entry.deleted());
        return store.tx(tx -> {
            ObjectNode row = AssistantTopics.required(tx, "scheduled_delivery", id);
            if (!tx.publicationOwnerMatches(row, owner) || row.path("attemptCount").asInt() != guard.path("attemptCount").asInt())
                throw new XDataProviderException(409, "排期发送记录已更新，请刷新后核实");
            if (applyVerification(tx, "scheduled_delivery", row, decision, System.currentTimeMillis())) {
                row.put("settled", true);
                if (entry != null && tweetId != null) row.put("publishedAt", entry.job().publishedAtEpochMs());
                row = tx.put("scheduled_delivery", id, row);
            }
            tx.removeQueueVerification(id);
            return row;
        });
    }

    static boolean applyVerification(AssistantStore.Tx tx, String kind, ObjectNode row, ObjectNode decision, long now) {
        if (decision == null || !decision.path("published").isBoolean()) throw new IllegalArgumentException("请选择核实结果");
        boolean published = decision.path("published").asBoolean();
        String tweetId = decision.path("tweetId").asText("").strip();
        if (published && !tweetId.matches("[0-9]{2,20}")) throw new IllegalArgumentException("请输入 X 上该条帖子的数字 ID");
        if (row.hasNonNull("verifiedAt") && decision.path("version").isIntegralNumber()
                && decision.path("version").asInt() == row.path("verifiedFromVersion").asInt(-1)
                && (published || row.path("version").asInt() == decision.path("version").asInt() + 1)
                && (published ? "PUBLISHED".equals(row.path("status").asText()) && tweetId.equals(row.path("publishedTweetId").asText())
                              : "FAILED".equals(row.path("status").asText()))) return false;
        if (!"UNCERTAIN".equals(row.path("status").asText()))
            throw new XDataProviderException(409, "该记录当前无需核实，请刷新");
        AssistantWorkflow.expect(row, decision);
        if (published) {
            tx.requireAvailablePostId(kind, row, tweetId);
            row.put("publishedTweetId", tweetId).put("publishedTweetUrl", "https://x.com/i/status/" + tweetId)
                    .put("publishedAt", row.path("dispatchedAt").asLong(row.path("createdAt").asLong(row.path("updatedAt").asLong(now))));
        }
        row.put("status", published ? "PUBLISHED" : "FAILED").put("verifiedAt", now)
                .put("verifiedFromVersion", decision.path("version").asInt()).put("error", published ? "" : "作者已核实未发出，可重新发布");
        return true;
    }

    public ObjectNode update(String account,String username,String draftId,ObjectNode body) {
        String intent=AssistantWorkflow.field(body,"intentId");
        String operation=AssistantWorkflow.field(body,"operationId");
        var draft=store.tx(t->AssistantTopics.required(t,"draft",draftId));
        AssistantWorkflow.expect(draft,body);
        var pub=store.tx(t->AssistantTopics.required(t,"publication",intent));
        if(!draftId.equals(pub.path("draftId").asText())) throw new XDataProviderException(409,"稿件与排期来源不一致");
        String requested = pub.path("requestedQuoteTweetId").asText(pub.path("quoteTweetId").asText("")).strip();
        XPostLink currentSource = requested.isEmpty() ? null : store.tx(tx -> {
            XPostLink post = draftSourcePost(tx, draft);
            if (post != null && !operation.equals(pub.path("updateOperationId").asText()))
                ScheduledPublicationGuard.checkTopic(tx, draft, publicationMaterial(tx, draft, post.tweetId()),
                        post.tweetId(), pub.path("jobId").asText(), intent);
            return post;
        });
        if (!requested.isEmpty() && currentSource == null)
            throw new XDataProviderException(409, "当前稿件已无可核实的引用原帖，请重新选择排期方式");
        var updated=queue.updateAssistantJob(account,username,pub.path("jobId").asText(),body.path("expectedUpdatedAt").asLong(-1),
                draft.path("text").asText(),source(draft,intent,pub.path("requestDigest").asText(),operation,currentSource == null ? null : currentSource.tweetId()),
                currentSource == null ? null : currentSource.url());
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
        restoreDirectReceipt(pubKey);

        int version = body.path("version").asInt(-1);
        ObjectNode reservation = store.tx(tx -> {
            ObjectNode prior = tx.get("publication", pubKey);
            if (prior != null) {
                if (!tx.publicationOwnerMatches(prior, accountId)) throw new XDataProviderException(409, "原发布请求不属于当前作者");
                String requestedQuote = prior.path("requestedQuoteTweetId").asText(prior.path("quoteTweetId").asText(""));
                if (!directDigest(draftId, version, quoteSource, requestedQuote).equals(prior.path("requestDigest").asText())) {
                    throw new XDataProviderException(409, "同一请求标识已用于其他发布内容");
                }
                switch (prior.path("status").asText()) {
                    case "PUBLISHED" -> { return prior; }
                    case "SENDING", "UNCERTAIN" -> throw new XDataProviderException(409, "该发布请求正在处理或回执待确认，请勿重复提交");
                }
            }
            ObjectNode draft = AssistantTopics.required(tx, "draft", draftId);
            AssistantWorkflow.expect(draft, body);
            String text = draft.path("text").asText("").strip();
            if (text.isEmpty()) throw new IllegalArgumentException("稿件正文为空，无法发布");
            XPostLink sourcePost = quoteSource && prior == null ? draftSourcePost(tx, draft) : null;
            String quoteId = prior != null ? prior.path("requestedQuoteTweetId").asText(prior.path("quoteTweetId").asText(null))
                    : sourcePost == null ? null : sourcePost.tweetId();
            if (quoteSource && quoteId == null) throw new IllegalArgumentException("该素材不含有效推文 ID，不支持引用原帖发布");
            String digest = directDigest(draftId, version, quoteSource, quoteId);
            ObjectNode matching = tx.protectedPublicationByDigest(digest, accountId);
            if (matching != null) {
                if (!tx.publicationOwnerMatches(matching, accountId)) throw new XDataProviderException(409, "原发布回执不属于当前作者");
                if ("PUBLISHED".equals(matching.path("status").asText())) return matching;
                throw new XDataProviderException(409, "该稿件版本的发布正在处理或回执待确认，请勿重复提交");
            }
            String materialId = publicationMaterial(tx, draft, quoteId);
            String topicId = tx.publicationTopic(draft, materialId.isBlank() ? quoteId : materialId);
            ScheduledPublicationGuard.check(tx, draft, quoteId, text, List.of());
            ObjectNode sending = object()
                    .put("requestDigest", digest).put("ownerAccountId", accountId).put("sendAttempt", UUID.randomUUID().toString())
                    .put("draftId", draftId).put("draftVersion", version)
                    .put("topicId", topicId).put("materialId", materialId)
                    .put("topicVersion", draft.path("topicVersion").asInt())
                    .put("researchId", draft.path("researchId").asText(""))
                    .put("requestedText", text).put("text", text).put("status", "SENDING").put("postRequestDispatched", false)
                    .put("source", quoteSource ? "DIRECT_QUOTE_PUBLISH" : "DIRECT_PUBLISH");
            String skill = ViralWritingSkills.normalizeId(draft.path("skillId").asText(""));
            if (!skill.isEmpty()) sending.put("skillId", skill);
            if (quoteId != null) {
                String url = sourcePost != null ? sourcePost.url() : prior.path("requestedQuoteTweetUrl").asText(new XPostLink(quoteId, null).url());
                sending.put("requestedQuoteTweetId", quoteId).put("requestedQuoteTweetUrl", url);
            }
            sending.putArray("mediaDigests");
            return tx.put("publication", pubKey, sending);
        });
        if ("PUBLISHED".equals(reservation.path("status").asText())) return reservation;

        String text = reservation.path("requestedText").asText();
        String quoteId = reservation.path("requestedQuoteTweetId").asText(null);
        String quoteUrl = reservation.path("requestedQuoteTweetUrl").asText(null);
        String account = com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME;
        String accessToken;
        try {
            accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(account, accountId, true,
                    () -> oauthService.getValidWriteAccessToken(accountId, false));
        } catch (RuntimeException error) {
            store.recordPublicationFailure(pubKey, reservation, Objects.toString(error.getMessage(), "发布失败"), false);
            throw error;
        }

        XPublishClient.PublicationReceipt receipt;
        try {
            receipt = com.xops.hub.provider.PaidCapabilityGate.withCaller(account, accountId, true,
                    () -> XPublishClient.withDispatchedPayload(text, quoteId, payload -> markRequestDispatched(store, pubKey, accountId, System.currentTimeMillis(), payload),
                            () -> XPublishClient.withQuoteTweetUrl(quoteUrl, () -> publishClient.createTweet(accessToken, text, List.of(), quoteId))));
        } catch (RuntimeException error) {
            boolean uncertain = !(error instanceof XPublishException publishError) || publishError.isDeliveryUncertain();
            store.recordPublicationFailure(pubKey, reservation, Objects.toString(error.getMessage(), "发布失败"), uncertain);
            throw error;
        }
        ObjectNode accepted = object().put("ownerAccountId", accountId).put("sendAttempt", reservation.path("sendAttempt").asText()).put("text", receipt.text()).put("publicationMode", receipt.mode().toString())
                .put("publishedTweetId", receipt.tweetId()).put("publishedAt", System.currentTimeMillis())
                .put("publishedTweetUrl", new XPostLink(receipt.tweetId(), username).url());
        if (receipt.quoteTweetId() != null) accepted.put("quoteTweetId", receipt.quoteTweetId());
        // Keep the returned ID reserved until either SQLite or its recovery journal contains the receipt.
        synchronized (store) {
            return persistDirectReceipt(pubKey, accepted);
        }
    }

    private ObjectNode persistDirectReceipt(String pubKey, ObjectNode accepted) {
        pendingDirectReceipts.put(pubKey, accepted);
        try {
            return writeAcceptedReceipt(pubKey, accepted);
        } catch (RuntimeException firstFailure) {
            accepted.put("receiptRecovered", true);
            try {
                return writeAcceptedReceipt(pubKey, accepted);
            } catch (RuntimeException storageFailure) {
                // Preserve the confirmed ID across a restart even if the success transition itself fails.
                try {
                    store.tx(tx -> {
                        ObjectNode row = AssistantTopics.required(tx, "publication", pubKey);
                        if (!"PUBLISHED".equals(row.path("status").asText())) {
                            row.set("acceptedReceipt", accepted);
                            tx.put("publication", pubKey, row.put("status", "UNCERTAIN"));
                        }
                        return null;
                    });
                } catch (RuntimeException journalFailure) {
                    storageFailure.addSuppressed(journalFailure);
                    var saved = object().put("key", pubKey);
                    saved.set("receipt", accepted);
                    store.savePendingReceipt("direct-accepted", pubKey, saved);
                }
                throw new XDataProviderException(503, "X 已确认发布，正在补记回执，请刷新查看", storageFailure);
            }
        }
    }

    private void restoreDirectReceipt(String key) {
        store.restorePublicationFailure(key);
        ObjectNode receipt = pendingDirectReceipts.get(key);
        if (receipt == null) {
            ObjectNode row = store.tx(tx -> tx.get("publication", key));
            if (row != null && row.get("acceptedReceipt") instanceof ObjectNode saved) receipt = saved;
        }
        if (receipt == null) {
            var journal = store.pendingReceipt("direct-accepted", key);
            if (journal != null && journal.get("receipt") instanceof ObjectNode saved) {
                pendingDirectReceipts.putIfAbsent(key, saved);
                receipt = pendingDirectReceipts.get(key);
            }
        }
        if (receipt != null) writeAcceptedReceipt(key, receipt.put("receiptRecovered", true));
    }

    private ObjectNode writeAcceptedReceipt(String key, ObjectNode receipt) {
        ObjectNode result = store.tx(tx -> {
            ObjectNode row = AssistantTopics.required(tx, "publication", key);
            String tweetId = receipt.path("publishedTweetId").asText();
            if ("PUBLISHED".equals(row.path("status").asText())) {
                if (tweetId.equals(row.path("publishedTweetId").asText())) return row;
                throw new XDataProviderException(409, "已登记的帖子 ID 与平台成功回执不同，请核实");
            }
            tx.requireAvailablePostId("publication", row, tweetId);
            if (receipt.hasNonNull("sendAttempt") && !receipt.path("sendAttempt").equals(row.path("sendAttempt")))
                throw new XDataProviderException(409, "已接收的回执与当前发送请求不一致");
            row.remove(List.of("error", "quoteTweetId", "acceptedReceipt"));
            row.setAll(receipt);
            return tx.put("publication", key, row.put("status", "PUBLISHED"));
        });
        if (pendingDirectReceipts.remove(key, receipt)) store.removePendingReceipt("direct-accepted", key);
        return result;
    }

    private static String directDigest(String id, int version, boolean quote, String quoteId) {
        return AssistantWorkflow.digest(object().put("draftId", id).put("draftVersion", version)
                .put("quoteSource", quote).put("quoteTweetId", quoteId == null ? "" : quoteId));
    }

    static ObjectNode withDraftSourcePost(AssistantStore.Tx tx, ObjectNode draft) {
        ObjectNode research = AssistantWorkflow.draftResearch(tx, draft);
        if (!research.isEmpty()) draft.set("research", research);
        String materialId = AssistantWorkflow.draftMaterialId(tx, draft);
        if (!materialId.isEmpty()) draft.put("materialId", materialId);
        XPostLink source = draftSourcePost(tx, draft);
        if (source == null) draft.putNull("quoteSourcePost");
        else draft.putObject("quoteSourcePost").put("tweetId", source.tweetId()).put("url", source.url());
        return draft;
    }

    static XPostLink draftSourcePost(AssistantStore.Tx tx, ObjectNode draft) {
        JsonNode research = AssistantWorkflow.draftResearch(tx, draft);
        String selected = AssistantWorkflow.draftMaterialId(tx, draft);
        JsonNode materials = research.path("snapshot").path("materials");
        if (selected.isEmpty() && materials.size() == 1 && !materials.get(0).path("sourceUrl").asText("").isBlank())
            return materialPost("", materials.get(0));
        if (selected.isEmpty() && materials.isEmpty()) {
            String topicId = draft.path("originTopicId").asText(draft.path("topicId").asText(""));
            selected = tx.frozenTopicMaterial(topicId, Math.max(1, draft.path("topicVersion").asInt()));
        }
        FrozenSource frozen = frozenAuthorSource(selected, research);
        if (frozen.present()) return frozen.post();
        return selected.isEmpty() ? null : historicalMaterialPost(tx, selected);
    }

    record FrozenSource(boolean present, XPostLink post, String webUrl) {
        static final FrozenSource ABSENT = new FrozenSource(false, null, "");
        static final FrozenSource UNRESOLVED = new FrozenSource(true, null, "");

        static FrozenSource from(XPostLink post, String url) {
            return new FrozenSource(true, post, post == null ? AssistantEvidence.normalizedUrl(url) : "");
        }

        String assignedMaterial(String selected, String liveUrl) {
            if (!present) return selected;
            if (post != null) return attributedMaterial(selected, post.tweetId(), liveUrl);
            return !webUrl.isEmpty() && webUrl.equals(AssistantEvidence.normalizedUrl(liveUrl)) ? selected : null;
        }
    }

    static FrozenSource frozenAuthorSource(String selected, JsonNode research) {
        boolean matchedSnapshot = false;
        for (JsonNode material : research.path("snapshot").path("materials")) {
            if (selected.isEmpty() || !selected.equals(material.path("id").asText())) continue;
            matchedSnapshot = true;
            XPostLink link = materialPost(selected, material);
            String url = material.path("sourceUrl").asText("");
            if (link != null || !url.isBlank()) return FrozenSource.from(link, url);
        }
        FrozenSource cited = FrozenSource.ABSENT;
        for (JsonNode evidence : research.path("materialEvidence")) {
            String id = evidence.path("id").asText("");
            if (!selected.isEmpty()) {
                String prefix = "material-" + selected + "-";
                if (!(id.equals(prefix + "author") || id.equals(prefix + "author-article") || id.equals(prefix + "link"))) continue;
            } else if (!id.isEmpty() && !id.matches("material-.+-(?:author|author-article|link)")) {
                continue;
            }
            String url = evidence.path("url").asText("");
            XPostLink link = XPostLink.parse(url);
            if (selected.matches("[0-9]{2,20}") && (link == null || !selected.equals(link.tweetId()))) continue;
            FrozenSource candidate = FrozenSource.from(link, url);
            if (candidate.post() == null && candidate.webUrl().isEmpty()) return FrozenSource.UNRESOLVED;
            if (cited.present() && (!cited.webUrl().equals(candidate.webUrl()) || cited.post() != null
                    && (link == null || !cited.post().tweetId().equals(link.tweetId())))) return FrozenSource.UNRESOLVED;
            cited = candidate;
        }
        return !cited.present() && matchedSnapshot ? FrozenSource.UNRESOLVED : cited;
    }

    static String publicationMaterial(AssistantStore.Tx tx, ObjectNode draft, String quoteId) {
        String selected = draft == null ? "" : AssistantWorkflow.draftMaterialId(tx, draft);
        if (selected.isEmpty()) return Objects.toString(quoteId, "");
        if (selected.matches("[0-9]{2,20}")) return selected;
        ObjectNode live = tx.get("material", selected);
        String liveUrl = live == null ? null : live.path("sourceUrl").asText();
        if (quoteId == null || quoteId.isBlank()) {
            var frozen = frozenAuthorSource(selected, AssistantWorkflow.draftResearch(tx, draft));
            return Objects.toString(frozen.assignedMaterial(selected, liveUrl), "");
        }
        return attributedMaterial(selected, quoteId, liveUrl);
    }

    static String attributedMaterial(String selected, String originalId, String liveUrl) {
        if (originalId == null || originalId.isBlank() || originalId.equals(selected)) return selected;
        XPostLink live = XPostLink.parse(liveUrl);
        return live != null && originalId.equals(live.tweetId()) ? selected : originalId;
    }

    private static XPostLink historicalMaterialPost(AssistantStore.Tx tx, String id) {
        if (id.matches("[0-9]{2,20}")) return materialPost(id, tx.get("material", id));
        XPostLink original = null;
        for (var revision : tx.versions("material", id)) {
            XPostLink link = materialPost(id, revision);
            if (link == null || original != null && !original.tweetId().equals(link.tweetId())) return null;
            if (original == null) original = link;
        }
        return original;
    }

    static XPostLink materialPost(String id, JsonNode material) {
        return XPostLink.forMaterial(id, material == null ? "" : material.path("username").asText(""),
                material == null ? "" : material.path("sourceUrl").asText(""));
    }
}
