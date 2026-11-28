package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.ScheduledTweetModels.AssistantSource;
import com.xops.hub.model.ScheduledTweetModels.OverdueReason;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient.PublicationPayload;
import com.xops.hub.provider.XPublishClient.PublicationMode;
import com.xops.hub.provider.XPublishClient.PublicationReceipt;

import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;

/** Queue files own scheduling. An unresolved delivery is claimed in the same transaction as immediate posts. */
public final class ScheduledPublicationGuard {
    private ScheduledPublicationGuard() { }

    public static String requireRewriteOwner(AssistantStore.Tx tx, AssistantSource source, String accountId, String text) {
        var binding = tx.ownerBinding();
        boolean currentOwner = binding != null && accountId.equals(binding.path("accountId").asText());
        var reference = source == null ? null : source.rewriteSource();
        String declaredMaterial = "";
        if (reference != null) {
            if (!currentOwner || !accountId.equals(reference.ownerAccountId()))
                throw new XDataProviderException(409, "洗稿草稿所属的发布账号已变更，请重新打开来源时间线");
            String material = reference.materialId();
            if (material == null || material.isBlank() || reference.recordVersion() < 0)
                throw new IllegalArgumentException("洗稿排期缺少有效素材记录");
            if (reference.recordVersion() > 0) {
                var original = tx.version("auto_rewrite", material, reference.recordVersion());
                if (!tx.publicationOwnerMatches(original, accountId))
                    throw new XDataProviderException(409, "该洗稿定稿不属于当前发布账号");
                if (!currentFinal(tx, material, original, accountId)) throw changedRewrite();
            } else {
                var proof = reference.previewId() == null ? tx.rewritePreviewWithText(accountId, material, text)
                        : tx.get("rewrite_preview", reference.previewId());
                var state = tx.rewriteSourceState(material);
                if (proof != null) {
                    if (!accountId.equals(proof.path("ownerAccountId").asText()) || !material.equals(proof.path("materialId").asText())
                            || !currentPreview(proof, state)) throw changedRewrite();
                } else {
                    // Old source cards carried no preview identifier, including after a user edit.
                    // Only an original with a single known source revision can identify those edits.
                    if (reference.previewId() != null || state == null || state.path("version").asInt() != 1
                            || !state.path("pureText").asBoolean() || tx.versions("auto_rewrite", material).stream()
                            .anyMatch(row -> row.path("rewriteSourceNeedsReview").asBoolean())) throw changedRewrite();
                }
            }
            declaredMaterial = material;
        }

        if (text == null || text.isBlank()) return declaredMaterial;
        var copies = tx.retainedRewritesWithText(text);
        if (copies.isEmpty()) return declaredMaterial;
        if (!currentOwner) {
            boolean formerOwner = copies.stream().anyMatch(row -> accountId.equals(row.path("ownerAccountId").asText())
                    || row.path("ownerAccountId").asText().isBlank() && tx.versions("meta", "owner").stream()
                        .anyMatch(previous -> accountId.equals(previous.path("accountId").asText())));
            if (formerOwner) throw new XDataProviderException(409, "洗稿草稿所属的发布账号已变更，请重新打开来源时间线");
            return "";
        }
        var owned = copies.stream().filter(row -> tx.publicationOwnerMatches(row, accountId)).toList();
        if (owned.isEmpty()) throw new XDataProviderException(409, "该洗稿定稿不属于当前发布账号");
        for (var row : owned) {
            String material = row.path("materialId").asText(row.path("id").asText());
            if (row.has("textDigest") ? currentPreview(row, tx.rewriteSourceState(material)) : currentFinal(tx, material, row, accountId))
                return declaredMaterial.isBlank() ? material : declaredMaterial;
        }
        throw changedRewrite();
    }

    private static boolean currentPreview(ObjectNode proof, ObjectNode state) {
        return state != null && proof.path("sourceVersion").asInt() == state.path("version").asInt();
    }

    private static boolean currentFinal(AssistantStore.Tx tx, String material, ObjectNode original, String accountId) {
        var latest = tx.get("auto_rewrite", material);
        if (!tx.publicationOwnerMatches(latest, accountId) || latest.path("rewriteSourceNeedsReview").asBoolean()) return false;
        String fingerprint = original.path("rewriteSourceFingerprint").asText();
        var state = tx.rewriteSourceState(material);
        if (!fingerprint.isBlank()) {
            if (!fingerprint.equals(latest.path("rewriteSourceFingerprint").asText())) return false;
            if (state != null && !fingerprint.equals(state.path("fingerprint").asText())) return false;
        } else if (state != null && state.path("version").asInt() > 1
                && !AutoRepostService.matchesFinalRewriteSource(tx, original, tx.lastObservedRewriteMaterial(material))) {
            return false;
        }
        int version = original.path("version").asInt();
        if (version == latest.path("version").asInt()) return true;
        return tx.versions("auto_rewrite", material).stream()
                .noneMatch(row -> row.path("version").asInt() >= version && row.path("rewriteSourceNeedsReview").asBoolean());
    }

    private static XDataProviderException changedRewrite() {
        return new XDataProviderException(409, "洗稿原素材已变更或无法核对原版本，请重新生成预览或评估定稿");
    }

    static void check(AssistantStore.Tx tx, ObjectNode draft, String quoteId, String text, List<String> media) {
        String draftId = draft == null ? "" : draft.path("id").asText("");
        requireAvailableCopy(tx, draftId, draft == null ? 0 : draft.path("version").asInt(), quoteId, text, media, "", "", 0);
        checkTopic(tx, draft, selectedMaterial(tx, draft, quoteId), quoteId, "", "");
    }

    static void requireAvailableCopy(AssistantStore.Tx tx, String draftId, String quoteId, String text, List<String> media,
                                     String ownKind, String ownId, long queuedAt) {
        requireAvailableCopy(tx, draftId, 0, quoteId, text, media, ownKind, ownId, queuedAt);
    }

    static void requireAvailableCopy(AssistantStore.Tx tx, String draftId, int draftVersion, String quoteId, String text,
                                     List<String> media, String ownKind, String ownId, long queuedAt) {
        var conflict = tx.conflictingDelivery(draftId, draftVersion, quoteId, text, media, ownKind, ownId, queuedAt);
        if (conflict != null) throw new XDataProviderException(409, "同一稿件已发布、正在发送或回执待核实，请检查发布记录");
    }

    static void checkTopic(AssistantStore.Tx tx, ObjectNode origin, String material, String quoteId,
                           String ownJob, String ownPublication) {
        if (quoteId == null || quoteId.isBlank()) return;
        tx.requireNoPendingQuoteOrRewrite(material, quoteId, tx.publicationTopic(origin, material), ownJob, ownPublication);
    }

    private static String selectedMaterial(AssistantStore.Tx tx, ObjectNode draft, String quoteId) {
        return AssistantPublishing.publicationMaterial(tx, draft, quoteId);
    }

    static boolean checkQueue(AssistantStore.Tx tx, String account, AssistantSource source, String quoteId,
                              String text, List<String> media, String jobId, long createdAt) {
        String rewriteMaterial = requireRewriteOwner(tx, source, account, text);
        ObjectNode binding = tx.ownerBinding();
        if (!AuthorIdentity.trustedId(binding == null ? null : binding.path("accountId").asText()).equals(account)) {
            if (source != null) throw new XDataProviderException(403, "内容助手排期的发布账号已变更");
            return false;
        }
        if (!rewriteMaterial.isBlank()) {
            String topic = tx.publicationTopic(tx.get("auto_rewrite", rewriteMaterial), rewriteMaterial);
            tx.requireNoPendingQuoteOrRewrite(rewriteMaterial, rewriteMaterial, topic, jobId, "");
        }
        ObjectNode draft = draft(tx, source);
        requireAvailableCopy(tx, source == null ? "" : source.draftId(), source == null ? 0 : source.draftVersion(),
                quoteId, text, media, "scheduled_delivery", jobId, createdAt);
        checkTopic(tx, draft, selectedMaterial(tx, draft, quoteId), quoteId, jobId, "");
        return true;
    }

    public static void checkEnqueue(AssistantStore store, String account, AssistantSource source, String quoteId,
                                    String text, List<String> media) {
        if (store != null) store.tx(tx -> checkQueue(tx, account, source, quoteId, text, media, "", 0));
    }

    public static void claim(AssistantStore store, ScheduledTweetJob job, String quoteId, String text) {
        if (store == null) return;
        store.tx(tx -> {
            if (checkJob(tx, job, quoteId, text))
                write(tx, job, draft(tx, job.assistantSource()), quoteId, text, "SENDING");
            return null;
        });
    }

    public static void beforeDispatch(AssistantStore store, ScheduledTweetJob job, String quoteId, String text, PublicationPayload payload) {
        if (store == null) return;
        store.tx(tx -> {
            if (!checkJob(tx, job, quoteId, text)) return null;
            var row = AssistantTopics.required(tx, "scheduled_delivery", job.id());
            if (!text.equals(payload.text())) {
                requireAvailableCopy(tx, row.path("draftId").asText(""), row.path("draftVersion").asInt(), quoteId, payload.text(),
                        job.media().stream().map(asset -> asset.sha256()).toList(), "scheduled_delivery", job.id(), job.createdAtEpochMs());
            }
            setPayload(row, payload);
            tx.put("scheduled_delivery", job.id(), row);
            return null;
        });
    }

    private static void setPayload(ObjectNode row, PublicationPayload payload) {
        row.put("text", payload.text()).put("publicationMode", payload.mode().name());
        if (payload.quoteTweetId() == null) row.remove("quoteTweetId");
        else row.put("quoteTweetId", payload.quoteTweetId());
    }

    private static void copyPayload(ScheduledTweetJob job, ObjectNode row) {
        var receipt = job.publicationReceipt();
        if (receipt != null) setPayload(row, new PublicationPayload(receipt.text(), receipt.mode(), receipt.quoteTweetId()));
        else if (job.dispatchedPayload() != null) setPayload(row, job.dispatchedPayload());
    }

    private static boolean checkJob(AssistantStore.Tx tx, ScheduledTweetJob job, String quoteId, String text) {
        var previous = tx.get("scheduled_delivery", job.id());
        if (previous != null && "PUBLISHED".equals(previous.path("status").asText()))
            throw new XDataProviderException(409, "该排期已登记为已发布");
        if (tx.get("scheduled_verification", job.id()) != null)
            throw new XDataProviderException(409, "该排期的人工核实结果正在保存");
        return checkQueue(tx, job.accountId(), job.assistantSource(), quoteId, text,
                job.media().stream().map(asset -> asset.sha256()).toList(), job.id(), job.createdAtEpochMs());
    }

    public static ScheduledTweetJob reconcile(AssistantStore store, ScheduledTweetJob job, String quoteId, String actualText) {
        if (store == null || job.attemptCount() == 0 || !store.isOwner(job.accountId())) return null;
        return store.tx(tx -> {
            ObjectNode saved = tx.get("scheduled_delivery", job.id());
            if (saved != null) {
                if ("PUBLISHED".equals(saved.path("status").asText()) && saved.hasNonNull("publishedTweetId")) {
                    String id = saved.path("publishedTweetId").asText();
                    if (job.status() == ScheduledTweetStatus.PUBLISHED && job.publishedTweetId() != null) {
                        if (id.equals(job.publishedTweetId())) return null;
                        throw new XDataProviderException(409, "排期与已保存的成功回执记录了不同的帖子 ID，请核实");
                    }
                    String url = saved.path("publishedTweetUrl").asText("https://x.com/i/status/" + id);
                    PublicationReceipt receipt = null;
                    for (var mode : PublicationMode.values()) {
                        if (mode.name().equals(saved.path("publicationMode").asText())) {
                            receipt = new PublicationReceipt(id, saved.path("text").asText(), mode, saved.path("quoteTweetId").asText(null));
                            break;
                        }
                    }
                    return job.restorePublished(id, url, saved.path("publishedAt").asLong(), saved.path("verifiedAt").asLong(), receipt);
                }
                int previous = saved.path("attemptCount").asInt();
                if (previous > job.attemptCount()) return null;
                // Dashboard snapshots may arrive after the terminal result of the same queue attempt.
                if (previous == job.attemptCount() && saved.path("settled").asBoolean()) {
                    if (job.status() == ScheduledTweetStatus.PUBLISHED && "PUBLISHED".equals(saved.path("status").asText())
                            && job.publishedTweetId() != null && !saved.hasNonNull("publishedTweetId"))
                        tx.put("scheduled_delivery", job.id(), saved.put("publishedTweetId", job.publishedTweetId()));
                    return null;
                }
                if (previous == job.attemptCount() && "UNCERTAIN".equals(saved.path("status").asText())
                        && job.status() == ScheduledTweetStatus.SENDING) return null;
            }
            if (job.uncertainDelivery() || job.status() == ScheduledTweetStatus.SENDING) {
                String status = job.status() == ScheduledTweetStatus.SENDING ? "SENDING" : "UNCERTAIN";
                if (saved == null || !status.equals(saved.path("status").asText()) || saved.path("attemptCount").asInt() != job.attemptCount())
                    write(tx, job, draft(tx, job.assistantSource()), quoteId, actualText, status);
            } else if (saved == null && job.status() == ScheduledTweetStatus.PUBLISHED && job.publishedTweetId() != null) {
                write(tx, job, draft(tx, job.assistantSource()), quoteId, actualText, "PUBLISHED");
            } else if (saved != null) {
                if (job.overdueReason() == OverdueReason.AUTHOR_VERIFIED && "UNCERTAIN".equals(saved.path("status").asText())) {
                    ObjectNode decision = object().put("published", job.publishedTweetId() != null)
                            .put("tweetId", job.publishedTweetId()).put("version", saved.path("version").asInt());
                    AssistantPublishing.applyVerification(tx, "scheduled_delivery", saved, decision, job.confirmedAtEpochMs());
                } else saved.put("status", job.status().name());
                saved.put("settled", true).put("attemptCount", job.attemptCount());
                if (job.status() == ScheduledTweetStatus.PUBLISHED) {
                    saved.put("publishedAt", job.publishedAtEpochMs()).put("publishedTweetId", job.publishedTweetId());
                }
                copyPayload(job, saved);
                tx.put("scheduled_delivery", job.id(), saved);
            }
            return null;
        });
    }

    private static ObjectNode draft(AssistantStore.Tx tx, AssistantSource source) {
        return source == null || source.draftId() == null ? null : tx.version("draft", source.draftId(), source.draftVersion());
    }

    private static void write(AssistantStore.Tx tx, ScheduledTweetJob job, ObjectNode draft, String quoteId, String text, String status) {
        String material = selectedMaterial(tx, draft, quoteId);
        ObjectNode claim = object().put("ownerAccountId", job.accountId()).put("jobId", job.id())
                .put("status", status).put("requestedText", text).put("attemptCount", job.attemptCount())
                .put("materialId", material).put("topicId", tx.publicationTopic(draft, material));
        var digests = claim.putArray("mediaDigests");
        job.media().forEach(asset -> digests.add(asset.sha256()));
        if (job.assistantSource() != null && job.assistantSource().rewriteSource() != null) {
            material = job.assistantSource().rewriteSource().materialId();
            claim.put("source", "AUTO_REWRITE_SCHEDULE").put("materialId", material).put("topicId", tx.publicationTopic(null, material));
        }
        if (job.postRequestDispatchedAtEpochMs() > 0) claim.put("dispatchedAt", job.postRequestDispatchedAtEpochMs());
        if (job.assistantSource() != null)
            claim.put("draftId", job.assistantSource().draftId()).put("draftVersion", job.assistantSource().draftVersion());
        if ("PUBLISHED".equals(status)) {
            claim.put("settled", true).put("publishedAt", job.publishedAtEpochMs()).put("publishedTweetId", job.publishedTweetId());
            if (job.overdueReason() == OverdueReason.AUTHOR_VERIFIED) claim.put("verifiedAt", job.confirmedAtEpochMs());
        }
        if (quoteId != null) claim.put("requestedQuoteTweetId", quoteId);
        copyPayload(job, claim);
        tx.put("scheduled_delivery", job.id(), claim);
    }
}
