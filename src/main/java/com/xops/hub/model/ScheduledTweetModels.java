package com.xops.hub.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        UNCERTAIN_DELIVERY
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

    public record AssistantSource(String intentId, String draftId, int draftVersion, String topicId,
                                  int topicVersion, String researchId, String requestDigest, String updateOperationId) {}

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
            AssistantSource assistantSource
    ) {
        public ScheduledTweetJob {
            text = text == null ? "" : text;
            media = media == null ? List.of() : List.copyOf(media);
            status = status == null ? ScheduledTweetStatus.SCHEDULED : status;
            overdueReason = overdueReason == null ? OverdueReason.NONE : overdueReason;
        }

        public ScheduledTweetJob withAssistantSource(AssistantSource source) {
            return new ScheduledTweetJob(id,accountId,username,text,media,scheduledAtEpochMs,status,overdueReason,
                    uncertainDelivery,postRequestDispatched,confirmToken,clientRequestId,contentFingerprint,
                    attemptCount,createdAtEpochMs,updatedAtEpochMs,confirmedAtEpochMs,sendingStartedAtEpochMs,
                    publishedAtEpochMs,publishedTweetId,publishedTweetUrl,lastError,source);
        }

        public ScheduledTweetJob withMedia(List<ScheduledMediaAsset> nextMedia, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, nextMedia, scheduledAtEpochMs,
                    status, overdueReason, uncertainDelivery, postRequestDispatched,
                    confirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, lastError, assistantSource
            );
        }

        public ScheduledTweetJob withPostRequestDispatched(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    status, overdueReason, uncertainDelivery, true,
                    confirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, lastError, assistantSource
            );
        }

        public ScheduledTweetJob markSending(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.SENDING, overdueReason, uncertainDelivery, false,
                    null, clientRequestId, contentFingerprint, attemptCount + 1,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, nowMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource
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
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, errorMessage, assistantSource
            );
        }

        public ScheduledTweetJob markResendQueued(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.RESEND_QUEUED, overdueReason, uncertainDelivery, postRequestDispatched,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, nowMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource
            );
        }

        public ScheduledTweetJob markPublished(String tweetId, String tweetUrl, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.PUBLISHED, OverdueReason.NONE, false, true,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    nowMs, tweetId, tweetUrl, null, assistantSource
            );
        }

        public ScheduledTweetJob markFailed(String newConfirmToken, String errorMessage, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.FAILED, OverdueReason.NONE, false, false,
                    newConfirmToken, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, errorMessage, assistantSource
            );
        }

        public ScheduledTweetJob markCancelled(long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, scheduledAtEpochMs,
                    ScheduledTweetStatus.CANCELLED, OverdueReason.NONE, false, false,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, confirmedAtEpochMs, sendingStartedAtEpochMs,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource
            );
        }

        public ScheduledTweetJob reschedule(long nextScheduledAtEpochMs, String nextText, String nextFingerprint, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, nextText, media, nextScheduledAtEpochMs,
                    ScheduledTweetStatus.SCHEDULED, OverdueReason.NONE, false, false,
                    null, clientRequestId, nextFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, 0L, 0L,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, null, assistantSource
            );
        }

        public ScheduledTweetJob scheduleIdempotentRetry(long nextScheduledAtEpochMs, String errorMessage, long nowMs) {
            return new ScheduledTweetJob(
                    id, accountId, username, text, media, nextScheduledAtEpochMs,
                    ScheduledTweetStatus.SCHEDULED, OverdueReason.NONE, false, false,
                    null, clientRequestId, contentFingerprint, attemptCount,
                    createdAtEpochMs, nowMs, 0L, 0L,
                    publishedAtEpochMs, publishedTweetId, publishedTweetUrl, errorMessage, assistantSource
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
            String clientRequestId, String assistantDraftId, Integer assistantDraftVersion
    ) {
        public BatchCreateItemMeta(String text,long time,String key) { this(text,time,key,null,null); }
    }

    public record CreateScheduledTweetJsonRequest(
            String text,
            Long scheduledAtEpochMs,
            String clientRequestId,
            String assistantDraftId,
            Integer assistantDraftVersion
    ) {
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
