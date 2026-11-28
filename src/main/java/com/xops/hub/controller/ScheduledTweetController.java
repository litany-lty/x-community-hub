package com.xops.hub.controller;

import com.xops.hub.assistant.AssistantPublishing;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.RequestSecuritySupport;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.ScheduledTweetModels.BatchConfirmRequest;
import com.xops.hub.model.ScheduledTweetModels.BatchCreateItemMeta;
import com.xops.hub.model.ScheduledTweetModels.BatchCreateJsonRequest;
import com.xops.hub.model.ScheduledTweetModels.BatchCreateResponse;
import com.xops.hub.model.ScheduledTweetModels.ConfirmResendRequest;
import com.xops.hub.model.ScheduledTweetModels.CreateScheduledTweetJsonRequest;
import com.xops.hub.model.ScheduledTweetModels.RewriteSource;
import com.xops.hub.model.ScheduledTweetModels.RescheduleRequest;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetListResponse;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.ScheduledTweetService.BatchTweetDraftInput;
import com.xops.hub.service.ScheduledTweetService.MediaFileContent;
import com.xops.hub.service.ScheduledTweetService.MediaUploadInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduled-tweets")
public class ScheduledTweetController {

    private final ScheduledTweetService scheduledTweetService;
    private final ObjectMapper objectMapper;
    @Autowired
    void setAssistantPublishing(AssistantPublishing publishing) { this.publishing=publishing; }
    private AssistantPublishing publishing;

    public ScheduledTweetController(ScheduledTweetService scheduledTweetService) {
        this(scheduledTweetService, new ObjectMapper());
    }

    @Autowired
    public ScheduledTweetController(ScheduledTweetService scheduledTweetService, ObjectMapper objectMapper) {
        this.scheduledTweetService = scheduledTweetService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @GetMapping
    public ScheduledTweetListResponse list(HttpServletRequest request, HttpServletResponse response) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        return scheduledTweetService.listJobs(user.id(), user.username());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScheduledTweetJob create(
            @RequestParam(value = "text", required = false, defaultValue = "") String text,
            @RequestParam("scheduledAtEpochMs") long scheduledAtEpochMs,
            @RequestParam(value = "clientRequestId", required = false, defaultValue = "") String clientRequestId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);

        List<MediaUploadInput> uploads = new ArrayList<>();
        if (files != null) {
            for (MultipartFile mf : files) {
                if (mf != null && !mf.isEmpty()) {
                    uploads.add(new MediaUploadInput(mf.getOriginalFilename(), mf.getBytes()));
                }
            }
        }
        String draftId = request.getParameter("assistantDraftId");
        String rawVersion = request.getParameter("assistantDraftVersion");
        Integer version;
        try {
            version = rawVersion == null ? null : Integer.valueOf(rawVersion);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("稿件版本格式无效");
        }
        String quoteTweetId = request.getParameter("quoteTweetId");
        String quoteTweetUrl = request.getParameter("quoteTweetUrl");
        String rawRewrite = request.getParameter("rewriteSource");
        RewriteSource rewrite = rawRewrite == null ? null : objectMapper.readValue(rawRewrite, RewriteSource.class);
        boolean quoteSource = "true".equalsIgnoreCase(request.getParameter("quoteSource"));
        return createScheduledTweetInternal(user, text, scheduledAtEpochMs, clientRequestId, uploads,
                draftId, version, quoteTweetId, quoteTweetUrl, quoteSource, rewrite);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ScheduledTweetJob createJson(
            @RequestBody(required = false) CreateScheduledTweetJsonRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        if (body == null || body.scheduledAtEpochMs() == null) {
            throw new IllegalArgumentException("发布时间格式不正确，请重新选择时间");
        }
        return createScheduledTweetInternal(
                user,
                body.text() != null ? body.text() : "",
                body.scheduledAtEpochMs(),
                body.clientRequestId() != null ? body.clientRequestId() : "",
                List.of(),
                body.assistantDraftId(),
                body.assistantDraftVersion(),
                body.quoteTweetId(),
                body.quoteTweetUrl(),
                body.quoteTweetId() != null && !body.quoteTweetId().isBlank(), body.rewriteSource()
        );
    }

    private ScheduledTweetJob createScheduledTweetInternal(
            AuthenticatedXUser user,
            String text,
            long scheduledAtEpochMs,
            String clientRequestId,
            List<MediaUploadInput> uploads,
            String draftId,
            Integer version,
            String quoteTweetId,
            String quoteTweetUrl,
            boolean quoteSource, RewriteSource rewrite
    ) {
        var source = schedulingSource(user.id(), draftId, version, clientRequestId, text,
                scheduledAtEpochMs, uploads, quoteSource, quoteTweetId, quoteTweetUrl, rewrite);
        var job = scheduledTweetService.createScheduledTweet(
                user.id(), user.username(), text, scheduledAtEpochMs, clientRequestId, uploads,
                source.source(), source.quoteId(), source.quoteUrl());
        if (source.source() != null) publishing.record(job);
        return job;
    }

    private AssistantPublishing.ScheduleSource schedulingSource(String owner, String draftId, Integer version, String intent, String text,
                                              long time, List<MediaUploadInput> uploads, boolean quote, String quoteId, String url, RewriteSource rewrite) {
        if (rewrite != null) {
            if (draftId != null || version != null) throw new IllegalArgumentException("排期不能同时关联洗稿和创作稿件");
            return new AssistantPublishing.ScheduleSource(publishing.prepareRewrite(owner, rewrite, intent, text, time, uploads, quoteId), quoteId, url);
        }
        return draftId == null && version == null ? new AssistantPublishing.ScheduleSource(null, quoteId, url)
                : publishing.prepareDraftSchedule(owner, draftId, version, intent, text, time, uploads, quote, quoteId);
    }

    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchCreateResponse createBatch(
            @RequestParam("itemsJson") String itemsJson,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);

        List<BatchCreateItemMeta> metas;
        try {
            metas = objectMapper.readValue(itemsJson, new TypeReference<List<BatchCreateItemMeta>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("批量定时推文参数格式无效");
        }
        MultipartHttpServletRequest multiReq = request instanceof MultipartHttpServletRequest m ? m : null;
        return createBatchInternal(user, metas, multiReq);
    }

    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BatchCreateResponse createBatchJson(
            @RequestBody(required = false) BatchCreateJsonRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        return createBatchInternal(user, body != null ? body.items() : null, null);
    }

    private BatchCreateResponse createBatchInternal(
            AuthenticatedXUser user,
            List<BatchCreateItemMeta> metas,
            MultipartHttpServletRequest multiReq
    ) throws IOException {
        if (metas == null || metas.isEmpty()) {
            throw new IllegalArgumentException("请至少添加 1 条定时推文");
        }

        List<BatchTweetDraftInput> drafts = new ArrayList<>(metas.size());
        for (int i = 0; i < metas.size(); i++) {
            BatchCreateItemMeta meta = metas.get(i);
            if (meta == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条定时推文参数为空");
            }
            List<MediaUploadInput> uploads = new ArrayList<>();
            if (multiReq != null) {
                List<MultipartFile> itemFiles = multiReq.getFiles("files_" + i);
                if (itemFiles != null) {
                    for (MultipartFile mf : itemFiles) {
                        if (mf != null && !mf.isEmpty()) {
                            uploads.add(new MediaUploadInput(mf.getOriginalFilename(), mf.getBytes()));
                        }
                    }
                }
            }
            boolean metaQuoteSource = meta.quoteTweetId() != null && !meta.quoteTweetId().isBlank();
            var source = schedulingSource(user.id(), meta.assistantDraftId(), meta.assistantDraftVersion(),
                    meta.clientRequestId(), meta.text(), meta.scheduledAtEpochMs(), uploads,
                    metaQuoteSource, meta.quoteTweetId(), meta.quoteTweetUrl(), meta.rewriteSource());
            drafts.add(new BatchTweetDraftInput(
                    meta.text(),
                    meta.scheduledAtEpochMs(),
                    meta.clientRequestId(),
                    uploads, source.source(), source.quoteId(), source.quoteUrl()
            ));
        }

        var result = scheduledTweetService.createBatchScheduledTweets(user.id(), user.username(), drafts);
        if (result != null && publishing != null) result.created().forEach(publishing::record);
        return result;
    }

    @PostMapping("/{id}/confirm-resend")
    public ScheduledTweetJob confirmResend(
            @PathVariable("id") String id,
            @RequestBody ConfirmResendRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        String token = body != null ? body.confirmToken() : null;
        return scheduledTweetService.confirmAndResend(user.id(), user.username(), id, token);
    }

    @PostMapping("/batch-confirm-resend")
    public ScheduledTweetListResponse batchConfirmResend(
            @RequestBody BatchConfirmRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        return scheduledTweetService.batchConfirmOverdue(
                user.id(),
                user.username(),
                body != null ? body.items() : List.of()
        );
    }

    @PostMapping("/{id}/cancel")
    public ScheduledTweetJob cancel(
            @PathVariable("id") String id,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        return scheduledTweetService.cancelJob(user.id(), id);
    }

    @PostMapping("/{id}/reschedule")
    public ScheduledTweetJob reschedule(
            @PathVariable("id") String id,
            @RequestBody RescheduleRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        if (body == null) {
            throw new IllegalArgumentException("缺少改期请求参数");
        }
        return scheduledTweetService.rescheduleJob(user.id(), user.username(), id, body.scheduledAtEpochMs(), body.text());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable("id") String id,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        verifySameOriginIfPresent(request);
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        scheduledTweetService.deleteJob(user.id(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/media/{assetId}")
    public ResponseEntity<byte[]> getMedia(
            @PathVariable("id") String id,
            @PathVariable("assetId") String assetId,
            HttpServletRequest request
    ) {
        AuthenticatedXUser user = requireAuthenticatedUser(request);
        MediaFileContent media = scheduledTweetService.loadMediaAssetBytes(user.id(), id, assetId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(media.mimeType()))
                .body(media.bytes());
    }

    private static AuthenticatedXUser requireAuthenticatedUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        AuthenticatedXUser user = session != null
                ? (AuthenticatedXUser) session.getAttribute(XOAuthController.SESSION_AUTH_USER)
                : null;
        if (user == null || user.id() == null || user.id().isBlank()
                || user.username() == null || user.username().isBlank()) {
            throw new XDataProviderException(401, "请先使用 X 账号授权登录");
        }
        return user;
    }

    static void verifySameOriginIfPresent(HttpServletRequest request) {
        RequestSecuritySupport.verifySameOriginForMutation(request);
    }
}
