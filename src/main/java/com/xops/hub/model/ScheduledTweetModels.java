package com.xops.hub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xops.hub.provider.XPublishClient.PublicationReceipt;
import com.xops.hub.provider.XPublishClient.PublicationPayload;

import java.util.List;

/**
 * 图文定时推文领域模型与 API 请求/响应结构。
 */
public final class ScheduledTweetModels {

    private ScheduledTweetModels() {
    }

    public enum ScheduledTweetStatus {
        SCHEDULED,
        OVERDUE_CONFIRM_REQUIRED,
        RESEND_QUEUED,
        SENDING,
        PUBLISHED,
        FAILED,
        CANCELLED
    }

    public enum OverdueReason {
        NONE,
        MISSED_WINDOW,
        INTERRUPTED_SENDING,
        UNCERTAIN_DELIVERY,
        AUTHOR_VERIFIED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduledMediaAsset(
            String assetId,
            String fileName,
            String mimeType,
            long sizeBytes,
            String sha256,
            String remoteMediaId,
            long remoteUploadedAtEpochMs
    ) {
        public ScheduledMediaAsset withRemoteMedia(String mediaId, long uploadedAtEpochMs) {
            return new ScheduledMediaAsset(assetId, fileName, mimeType, sizeBytes, sha256, mediaId, uploadedAtEpochMs);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RewriteSource(String ownerAccountId, String materialId, int recordVersion, String previewId) {
        public RewriteSource(String ownerAccountId, String materialId, int recordVersion) {
            this(ownerAccountId, materialId, recordVersion, null);
        }
    }

    public record AssistantSource(String intentId, String draftId, int draftVersion, String topicId,
                                  int topicVersion, String researchId, String requestDigest, String updateOperationId,
                                  String quoteTweetId, RewriteSource rewriteSource) {
        public AssistantSource(String intentId, String draftId, int draftVersion, String topicId,
                               int topicVersion, String researchId, String requestDigest, String updateOperationId, String quoteTweetId) {
            this(intentId, draftId, draftVersion, topicId, topicVersion, researchId, requestDigest, updateOperationId, quoteTweetId, null);
        }
        public AssistantSource(String intentId, String draftId, int draftVersion, String topicId,
                               int topicVersion, String researchId, String requestDigest, String updateOperationId) {
            this(intentId, draftId, draftVersion, topicId, topicVersion, researchId, requestDigest, updateOperationId, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduledTweetJob(
            String id,
            String accountId,
            String username,
            String text,
            List<ScheduledMediaAsset> media,
            long scheduledAtEpochMs,
            ScheduledTweetStatus status,
            OverdueReason overdueReason,
            boolean uncertainDelivery,
            boolean postRequestDispatched,
            String confirmToken,
            String clientRequestId,
            String contentFingerprint,
            int attemptCount,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            long confirmedAtEpochMs,
            long sendingStartedAtEpochMs,
            long publishedAtEpochMs,
            String publishedTweetId,
            String publishedTweetUrl,
            String lastError,
            AssistantSource assistantSource,
            String quoteTweetId,
            String quoteTweetUrl,
            PublicationReceipt publicationReceipt,
            long postRequestDispatchedAtEpochMs,
            PublicationPayload dispatchedPayload
    ) {
        public ScheduledTweetJob {
            text = text == null ? "" : text;
            media = media == null ? List.of() : List.copyOf(media);
            status = status == null ? ScheduledTweetStatus.SCHEDULED : status;
            overdueReason = overdueReason == null ? OverdueReason.NONE : overdueReason;
            if (postRequestDispatchedAtEpochMs == 0 && (postRequestDispatched || uncertainDelivery)) {
                postRequestDispatchedAtEpochMs = status == ScheduledTweetStatus.SENDING || sendingStartedAtEpochMs <= 0
                        ? updatedAtEpochMs : sendingStartedAtEpochMs;
            }
        }

        public ScheduledTweetJob(
                String id,
                String accountId,
                String username,
                String text,
                List<ScheduledMediaAsset> media,
                long scheduledAtEpochMs,
                ScheduledTweetStatus status,
                OverdueReason overdueReason,
                boolean uncertainDelivery,
                boolean postRequestDispatched,
                String confirmToken,
                String clientRequestId,
                String contentFingerprint,
                int attemptCount,
                long createdAtEpochMs,
                long updatedAtEpochMs,
                long confirmedAtEpochMs,
                long sendingStartedAtEpochMs,
                long publishedAtEpochMs,
                String publishedTweetId,
                String publishedTweetUrl,
                String lastError,
                AssistantSource assistantSource
        ) {
            this(id, accountId, username, text, media, scheduledAtEpochMs, status, overdueReason,
                    uncertainDelivery, postRequestDispatched, confirmToken, clientRequestId, contentFingerprint,
                    attemptCount, createdAtEpochMs, updatedAtEpochMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, lastError, assistantSource, null, null);
        }

        public ScheduledTweetJob(String id, String accountId, String username, String text, List<ScheduledMediaAsset> media,
                                 long scheduledAtEpochMs, ScheduledTweetStatus status, OverdueReason overdueReason,
                                 boolean uncertainDelivery, boolean postRequestDispatched, String confirmToken, String clientRequestId,
                                 String contentFingerprint, int attemptCount, long createdAtEpochMs, long updatedAtEpochMs,
                                 long confirmedAtEpochMs, long sendingStartedAtEpochMs, long publishedAtEpochMs, String publishedTweetId,
                                 String publishedTweetUrl, String lastError, AssistantSource assistantSource, String quoteTweetId, String quoteTweetUrl) {
            this(id, accountId, username, text, media, scheduledAtEpochMs, status, overdueReason, uncertainDelivery,
                    postRequestDispatched, confirmToken, clientRequestId, contentFingerprint, attemptCount, createdAtEpochMs,
                    updatedAtEpochMs, confirmedAtEpochMs, sendingStartedAtEpochMs, publishedAtEpochMs, publishedTweetId,
                    publishedTweetUrl, lastError, assistantSource, quoteTweetId, quoteTweetUrl, null, 0L, null);
        }

        public ScheduledTweetJob withPublicationSource(AssistantSource source, String quoteId, String quoteUrl, String fingerprint) {
            return new ScheduledTweetJob(id,accountId,username,text,media,scheduledAtEpochMs,status,overdueReason,
                    uncertainDelivery,postRequestDispatched,confirmToken,clientRequestId,fingerprint,
                    attemptCount,createdAtEpochMs,updatedAtEpochMs,confirmedAtEpochMs,sendingStartedAtEpochMs,
                    publishedAtEpochMs,publishedTweetId,publishedTweetUrl,lastError,source,quoteId,quoteUrl,publicationReceipt,postRequestDispatchedAtEpochMs,dispatchedPayload);
        }

        public ScheduledTweetJob withMedia(List<ScheduledMediaAsset> nextMedia, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, nextMedia, scheduledAtEpochMs,
                    status, overdueReason, uncertainDelivery, postRequestDispatched,
                    confirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, lastError, assistantSource,
                    quoteTweetId, quoteTweetUrl, publicationReceipt, postRequestDispatchedAtEpochMs, dispatchedPayload
            );
        }

        public ScheduledTweetJob withPostRequestDispatched(PublicationPayload payload, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    status, overdueReason, uncertainDelivery, true,
                    confirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, lastError, assistantSource,
                    quoteTweetId, quoteTweetUrl, publicationReceipt, nowMs, payload
            );
        }

        public ScheduledTweetJob markSending(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.SENDING, overdueReason, uncertainDelivery, false,
                    null, clientRequestId, contentFingerprint, attemptCount + 1,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, nowMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource,
                    quoteTweetId, quoteTweetUrl, null, 0L, null
            );
        }

        public ScheduledTweetJob markOverdueConfirmRequired(
                OverdueReason reason,
                boolean uncertain,
                String newConfirmToken,
                String errorMessage,
                long nowMs
        ) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, reason, uncertain, postRequestDispatched,
                    newConfirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, errorMessage, assistantSource,
                    quoteTweetId, quoteTweetUrl, publicationReceipt, postRequestDispatchedAtEpochMs, dispatchedPayload
            );
        }

        public ScheduledTweetJob markResendQueued(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.RESEND_QUEUED, overdueReason, uncertainDelivery, postRequestDispatched,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, nowMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource,
                    quoteTweetId, quoteTweetUrl, publicationReceipt, postRequestDispatchedAtEpochMs, dispatchedPayload
            );
        }

        public ScheduledTweetJob markPublished(PublicationReceipt receipt, String tweetUrl, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.PUBLISHED, OverdueReason.NONE, false, true,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    nowMs, receipt.tweetId(), tweetUrl, null, assistantSource,
                    quoteTweetId, quoteTweetUrl, receipt, postRequestDispatchedAtEpochMs, dispatchedPayload
            );
        }

        public ScheduledTweetJob restorePublished(String tweetId, String url, long receivedAt, long verifiedAt, PublicationReceipt receipt) {
            return new ScheduledTweetJob(id, accountId, username, text, media, scheduledAtEpochMs, ScheduledTweetStatus.PUBLISHED,
                    verifiedAt > 0 ? OverdueReason.AUTHOR_VERIFIED : OverdueReason.NONE, false, true, null, clientRequestId,
                    contentFingerprint, attemptCount, createdAtEpochMs, Math.max(updatedAtEpochMs, receivedAt),
                    verifiedAt > 0 ? verifiedAt : confirmedAtEpochMs, sendingStartedAtEpochMs, receivedAt, tweetId, url,
                    null, assistantSource, quoteTweetId, quoteTweetUrl, receipt, postRequestDispatchedAtEpochMs, dispatchedPayload);
        }

        public ScheduledTweetJob registerDelivery(String tweetId, String retryToken, long nowMs) {
            boolean published = tweetId != null;
            ScheduledTweetStatus next = published ? ScheduledTweetStatus.PUBLISHED
                    : status == ScheduledTweetStatus.CANCELLED ? status : ScheduledTweetStatus.FAILED;
            long sentAt = postRequestDispatchedAtEpochMs > 0 ? postRequestDispatchedAtEpochMs : sendingStartedAtEpochMs;
            if (sentAt <= 0) sentAt = nowMs;
            PublicationReceipt confirmed = published && dispatchedPayload != null
                    ? new PublicationReceipt(tweetId, dispatchedPayload.text(), dispatchedPayload.mode(), dispatchedPayload.quoteTweetId()) : null;
            return new ScheduledTweetJob(id, accountId, username, text, media, scheduledAtEpochMs,
                    next, OverdueReason.AUTHOR_VERIFIED, false, published,
                    next == ScheduledTweetStatus.FAILED ? retryToken : null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, nowMs, sendingStartedAtEpochMs, published ? sentAt : 0,
                    tweetId, published ? "https://x.com/i/status/" + tweetId : null,
                    published ? null : "作者已核实未发出", assistantSource, quoteTweetId, quoteTweetUrl, confirmed, postRequestDispatchedAtEpochMs, dispatchedPayload);
        }

        public ScheduledTweetJob markFailed(String newConfirmToken, String errorMessage, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.FAILED, OverdueReason.NONE, false, false,
                    newConfirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, errorMessage, assistantSource,
                    quoteTweetId, quoteTweetUrl, null, 0L, null
            );
        }

        public ScheduledTweetJob markCancelled(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.CANCELLED, overdueReason, uncertainDelivery, postRequestDispatched,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource,
                    quoteTweetId, quoteTweetUrl, publicationReceipt, postRequestDispatchedAtEpochMs, dispatchedPayload
            );
        }

        public ScheduledTweetJob reschedule(long nextScheduledAtEpochMs, String nextText, String nextFingerprint, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, nextText, media, nextScheduledAtEpochMs,
                    ScheduledTweetStatus.SCHEDULED, OverdueReason.NONE, false, false,
                    null, clientRequestId, nextFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, 0L, 0L,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource,
                    quoteTweetId, quoteTweetUrl, null, 0L, null
            );
        }

        public ScheduledTweetJob scheduleIdempotentRetry(long nextScheduledAtEpochMs, String errorMessage, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, nextScheduledAtEpochMs,
                    ScheduledTweetStatus.SCHEDULED, OverdueReason.NONE, false, false,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, 0L, 0L,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, errorMessage, assistantSource,
                    quoteTweetId, quoteTweetUrl, null, 0L, null
            );
        }
    }

    public record ScheduledTweetListResponse(
            List<ScheduledTweetJob> items,
            int pendingCount,
            int overdueConfirmCount,
            boolean writeAuthorized,
            boolean mediaWriteAuthorized,
            long serverTimeEpochMs,
            String timezone,
            boolean scheduleAccessAllowed,
            boolean followsAuthor,
            Integer authorInteractionRank,
            String scheduleLockedReason,
            boolean offlineRefreshable
    ) {
        public ScheduledTweetListResponse(
                List<ScheduledTweetJob> items,
                int pendingCount,
                int overdueConfirmCount,
                boolean writeAuthorized,
                boolean mediaWriteAuthorized,
                long serverTimeEpochMs,
                String timezone,
                boolean scheduleAccessAllowed,
                boolean followsAuthor,
                Integer authorInteractionRank,
                String scheduleLockedReason
        ) {
            this(items, pendingCount, overdueConfirmCount, writeAuthorized, mediaWriteAuthorized,
                    serverTimeEpochMs, timezone, scheduleAccessAllowed, followsAuthor,
                    authorInteractionRank, scheduleLockedReason, false);
        }

        public ScheduledTweetListResponse(
                List<ScheduledTweetJob> items,
                int pendingCount,
                int overdueConfirmCount,
                boolean writeAuthorized,
                boolean mediaWriteAuthorized,
                long serverTimeEpochMs,
                String timezone
        ) {
            this(items, pendingCount, overdueConfirmCount, writeAuthorized, mediaWriteAuthorized,
                    serverTimeEpochMs, timezone, true, true, null, null, false);
        }
    }

    public record ConfirmResendRequest(String confirmToken) {
    }

    public record BatchConfirmItem(String id, String confirmToken) {
    }

    public record BatchConfirmRequest(List<BatchConfirmItem> items) {
    }

    public record RescheduleRequest(long scheduledAtEpochMs, String text) {
    }

    public record BatchCreateItemMeta(
            String text,
            long scheduledAtEpochMs,
            String clientRequestId, String assistantDraftId, Integer assistantDraftVersion,
            String quoteTweetId, String quoteTweetUrl, RewriteSource rewriteSource
    ) {
        public BatchCreateItemMeta(String text, long time, String key, String draftId, Integer version, String quoteId, String quoteUrl) {
            this(text, time, key, draftId, version, quoteId, quoteUrl, null);
        }
        public BatchCreateItemMeta(String text, long time, String key, String assistantDraftId, Integer assistantDraftVersion) {
            this(text, time, key, assistantDraftId, assistantDraftVersion, null, null);
        }

        public BatchCreateItemMeta(String text,long time,String key) { this(text,time,key,null,null,null,null); }
    }

    public record CreateScheduledTweetJsonRequest(
            String text,
            Long scheduledAtEpochMs,
            String clientRequestId,
            String assistantDraftId,
            Integer assistantDraftVersion,
            String quoteTweetId,
            String quoteTweetUrl, RewriteSource rewriteSource
    ) {
        public CreateScheduledTweetJsonRequest(String text, Long time, String key, String draftId, Integer version, String quoteId, String quoteUrl) {
            this(text, time, key, draftId, version, quoteId, quoteUrl, null);
        }
        public CreateScheduledTweetJsonRequest(
                String text,
                Long scheduledAtEpochMs,
                String clientRequestId,
                String assistantDraftId,
                Integer assistantDraftVersion
        ) {
            this(text, scheduledAtEpochMs, clientRequestId, assistantDraftId, assistantDraftVersion, null, null);
        }
    }

    public record BatchCreateJsonRequest(
            List<BatchCreateItemMeta> items
    ) {
    }

    public record BatchCreateResponse(
            List<ScheduledTweetJob> created,
            int createdCount
    ) {
    }
}
