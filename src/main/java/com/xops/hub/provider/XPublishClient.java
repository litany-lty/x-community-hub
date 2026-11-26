package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X 官方 API v2 图文发布客户端：
 * 1. 媒体上传严格执行 initialize -> append（4MB 分片） -> finalize（+ 必要时 STATUS 轮询）；
 * 2. POST /2/tweets 绝不盲目自动重试，超时/5xx/连接异常一律标记为 deliveryUncertain=true 交由人工二次确认。
 */
@Component
public class XPublishClient {

    private static final int MEDIA_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final long STATUS_POLL_MAX_MS = 25_000L;
    private static final ThreadLocal<String> QUOTE_URL_HINT = new ThreadLocal<>();
    private static final Pattern X_MENTION_QUOTE_RESTRICTION = Pattern.compile(
            "(?is).*(only reply to or quote posts|where you are mentioned or are the author).*");
    private static final Pattern CANONICAL_STATUS_URL = Pattern.compile(
            "(?i)^https?://(?:(?:www|mobile)\\.)?(?:x|twitter)\\.com/([A-Za-z0-9_]{1,15})/status/([0-9]{2,20})(?:[/?#].*)?$");

    public static <T> T withQuoteTweetUrl(String quoteTweetUrl, Supplier<T> action) {
        String prev = QUOTE_URL_HINT.get();
        if (quoteTweetUrl == null || quoteTweetUrl.isBlank()) {
            QUOTE_URL_HINT.remove();
        } else {
            QUOTE_URL_HINT.set(quoteTweetUrl.strip());
        }
        try {
            return action.get();
        } finally {
            if (prev == null) {
                QUOTE_URL_HINT.remove();
            } else {
                QUOTE_URL_HINT.set(prev);
            }
        }
    }

    public static class XPublishException extends XDataProviderException {
        private final boolean deliveryUncertain;

        public XPublishException(int statusCode, String message, boolean deliveryUncertain) {
            super(statusCode, message);
            this.deliveryUncertain = deliveryUncertain;
        }

        public XPublishException(int statusCode, String message, Throwable cause, boolean deliveryUncertain) {
            super(statusCode, message, cause);
            this.deliveryUncertain = deliveryUncertain;
        }

        public boolean isDeliveryUncertain() {
            return deliveryUncertain;
        }
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final SharedApiExecutor sharedExecutor;
    private final UpstreamBudgetGuard budgetGuard;
    private final HttpClient httpClient;

    public XPublishClient(XOpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new SharedApiExecutor(properties), new UpstreamBudgetGuard(properties));
    }

    public XPublishClient(XOpsProperties properties, ObjectMapper objectMapper, SharedApiExecutor sharedExecutor) {
        this(properties, objectMapper, sharedExecutor, new UpstreamBudgetGuard(properties));
    }

    @Autowired
    public XPublishClient(
            XOpsProperties properties,
            ObjectMapper objectMapper,
            SharedApiExecutor sharedExecutor,
            @Autowired(required = false) UpstreamBudgetGuard budgetGuard
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sharedExecutor = sharedExecutor;
        this.budgetGuard = budgetGuard != null ? budgetGuard : new UpstreamBudgetGuard(properties);
        this.httpClient = OfficialProxySupport.buildHttpClient(this.properties, Duration.ofSeconds(10));
    }

    public String uploadMedia(String userAccessToken, byte[] bytes, String mimeType, String fileName) {
        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new XPublishException(401, "缺少有效访问令牌，无法上传图片", false);
        }
        if (bytes == null || bytes.length == 0) {
            throw new XPublishException(400, "媒体文件内容为空", false);
        }

        String baseUrl = resolveOfficialBaseUrl();
        String mediaCategory = "image/gif".equalsIgnoreCase(mimeType) ? "tweet_gif" : "tweet_image";
        String mediaId = initializeMediaUpload(baseUrl, userAccessToken, mimeType, bytes.length, mediaCategory);
        appendMediaChunks(baseUrl, userAccessToken, mediaId, bytes, fileName);
        JsonNode finalizeData = finalizeMediaUpload(baseUrl, userAccessToken, mediaId);
        awaitMediaProcessingIfNeeded(baseUrl, userAccessToken, mediaId, finalizeData);
        return mediaId;
    }

    public String createTweet(String userAccessToken, String text, List<String> mediaIds) {
        return createTweet(userAccessToken, text, mediaIds, null, null);
    }

    public String createTweet(String userAccessToken, String text, List<String> mediaIds, String quoteTweetId) {
        return createTweet(userAccessToken, text, mediaIds, quoteTweetId, QUOTE_URL_HINT.get());
    }

    public String createTweet(
            String userAccessToken,
            String text,
            List<String> mediaIds,
            String quoteTweetId,
            String quoteTweetUrl
    ) {
        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new XPublishException(401, "缺少有效访问令牌，无法发布推文", false);
        }
        String cleanQuoteId = (quoteTweetId == null || quoteTweetId.isBlank()) ? null : quoteTweetId.trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (text != null && !text.isBlank()) {
            payload.put("text", text);
        }
        if (mediaIds != null && !mediaIds.isEmpty()) {
            payload.put("media", Map.of("media_ids", mediaIds));
        }
        if (cleanQuoteId != null) {
            payload.put("quote_tweet_id", cleanQuoteId);
        }
        if (payload.isEmpty()) {
            throw new XPublishException(400, "推文正文与媒体不可同时为空", false);
        }

        HttpResponse<String> resp = sendCreateTweetPayload(userAccessToken, payload);
        int status = resp.statusCode();
        if (status >= 500) {
            throw new XPublishException(
                    502,
                    "推文发布请求响应异常（HTTP " + status + "），无法确认 X 是否已成功落库",
                    true
            );
        }
        JsonNode root = parseJsonOrNull(resp.body());
        if (status == 403 && cleanQuoteId != null) {
            String detail = extractXErrorDetail(root, status);
            String combinedError = detail + " " + (resp.body() == null ? "" : resp.body());
            if (X_MENTION_QUOTE_RESTRICTION.matcher(combinedError).find()) {
                String canonicalUrl = resolveCanonicalQuoteUrl(cleanQuoteId, quoteTweetUrl);
                String fallbackText = appendQuoteUrlIfMissing(text, cleanQuoteId, canonicalUrl);
                Map<String, Object> fallbackPayload = new LinkedHashMap<>();
                fallbackPayload.put("text", fallbackText);
                if (mediaIds != null && !mediaIds.isEmpty()) {
                    fallbackPayload.put("media", Map.of("media_ids", mediaIds));
                }
                resp = sendCreateTweetPayload(userAccessToken, fallbackPayload);
                status = resp.statusCode();
                if (status >= 500) {
                    throw new XPublishException(
                            502,
                            "推文发布请求响应异常（HTTP " + status + "），无法确认 X 是否已成功落库",
                            true
                    );
                }
                root = parseJsonOrNull(resp.body());
            }
        }
        if (status >= 400) {
            String detail = extractXErrorDetail(root, status);
            throw new XPublishException(status, "X 官方接口拒绝发布：" + detail, false);
        }
        String tweetId = root == null ? "" : root.path("data").path("id").asText("");
        if (tweetId.isBlank()) {
            throw new XPublishException(502, "推文发布成功响应缺少推文 ID，无法确认 X 是否已成功落库", true);
        }
        return tweetId;
    }

    private HttpResponse<String> sendCreateTweetPayload(String userAccessToken, Map<String, Object> payload) {
        final String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new XPublishException(400, "序列化推文请求体失败：" + ex.getMessage(), ex, false);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(resolveOfficialBaseUrl() + "/tweets"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + userAccessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        return sharedExecutor.execute(() -> {
            try {
                budgetGuard.acquirePermit(req.uri(), "x_tweet_create");
            } catch (XDataProviderException ex) {
                throw new XPublishException(ex.getStatusCode(), ex.getMessage(), ex, false);
            }
            try {
                return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException ex) {
                throw new XPublishException(504, "推文发布请求响应超时，无法确认 X 是否已成功落库", ex, true);
            } catch (IOException ex) {
                throw new XPublishException(502, "推文发布请求网络异常（" + ex.getMessage() + "），无法确认 X 是否已成功落库", ex, true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new XPublishException(502, "推文发布请求被中断，无法确认 X 是否已成功落库", ex, true);
            }
        });
    }

    private static String resolveCanonicalQuoteUrl(String cleanQuoteId, String quoteTweetUrl) {
        if (quoteTweetUrl != null && !quoteTweetUrl.isBlank()) {
            Matcher matcher = CANONICAL_STATUS_URL.matcher(quoteTweetUrl.strip());
            if (matcher.matches() && cleanQuoteId.equals(matcher.group(2))) {
                return "https://x.com/" + matcher.group(1) + "/status/" + cleanQuoteId;
            }
        }
        return "https://x.com/i/status/" + cleanQuoteId;
    }

    private static String appendQuoteUrlIfMissing(String text, String cleanQuoteId, String canonicalUrl) {
        if (text == null || text.isBlank()) {
            return canonicalUrl;
        }
        if (text.contains("/status/" + cleanQuoteId)) {
            return text;
        }
        return text.stripTrailing() + "\n\n" + canonicalUrl;
    }

    private String initializeMediaUpload(
            String baseUrl,
            String token,
            String mimeType,
            int totalBytes,
            String mediaCategory
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("media_type", mimeType);
        body.put("total_bytes", totalBytes);
        body.put("media_category", mediaCategory);
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new XPublishException(400, "构造媒体初始化请求失败：" + ex.getMessage(), ex, false);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/media/upload/initialize"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = sendMediaRequest(req, "媒体上传初始化");
        JsonNode root = parseJsonOrNull(resp.body());
        if (resp.statusCode() >= 400) {
            throw new XPublishException(resp.statusCode(), "媒体上传初始化失败：" + extractXErrorDetail(root, resp.statusCode()), false);
        }
        String mediaId = extractMediaId(root);
        if (mediaId.isBlank()) {
            throw new XPublishException(502, "媒体上传初始化未返回有效的 media_id", false);
        }
        return mediaId;
    }

    private void appendMediaChunks(String baseUrl, String token, String mediaId, byte[] bytes, String fileName) {
        String safeName = (fileName == null || fileName.isBlank()) ? "upload.bin" : fileName.replace("\"", "_");
        int segmentIndex = 0;
        for (int offset = 0; offset < bytes.length; offset += MEDIA_CHUNK_BYTES) {
            int len = Math.min(MEDIA_CHUNK_BYTES, bytes.length - offset);
            String boundary = "----XOpsMediaBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] multipart = buildMultipartAppendBody(boundary, segmentIndex, bytes, offset, len, safeName);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/media/upload/" + urlEnc(mediaId) + "/append"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                    .build();

            HttpResponse<String> resp = sendMediaRequest(req, "媒体分片上传");
            if (resp.statusCode() >= 400) {
                JsonNode root = parseJsonOrNull(resp.body());
                throw new XPublishException(resp.statusCode(), "媒体分片上传失败：" + extractXErrorDetail(root, resp.statusCode()), false);
            }
            segmentIndex++;
        }
    }

    private JsonNode finalizeMediaUpload(String baseUrl, String token, String mediaId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/media/upload/" + urlEnc(mediaId) + "/finalize"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = sendMediaRequest(req, "媒体完成确认");
        JsonNode root = parseJsonOrNull(resp.body());
        if (resp.statusCode() >= 400) {
            throw new XPublishException(resp.statusCode(), "媒体上传完成确认失败：" + extractXErrorDetail(root, resp.statusCode()), false);
        }
        return root;
    }

    private void awaitMediaProcessingIfNeeded(String baseUrl, String token, String mediaId, JsonNode root) {
        JsonNode proc = extractProcessingInfo(root);
        if (proc == null || proc.isMissingNode() || proc.isNull()) {
            return;
        }
        long deadlineMs = System.currentTimeMillis() + STATUS_POLL_MAX_MS;
        while (proc != null && !proc.isMissingNode() && !proc.isNull()) {
            String state = proc.path("state").asText("");
            if ("succeeded".equalsIgnoreCase(state) || state.isBlank()) {
                return;
            }
            if ("failed".equalsIgnoreCase(state)) {
                String msg = proc.path("error").path("message").asText(proc.path("error").path("name").asText("媒体处理失败"));
                throw new XPublishException(502, "X 媒体转码失败：" + msg, false);
            }
            if (!"pending".equalsIgnoreCase(state) && !"in_progress".equalsIgnoreCase(state)) {
                return;
            }
            long checkAfterSecs = Math.min(5L, Math.max(1L, proc.path("check_after_secs").asLong(1L)));
            long waitMs = checkAfterSecs * 1000L;
            if (System.currentTimeMillis() + waitMs > deadlineMs) {
                throw new XPublishException(504, "X 媒体处理等待超时，请稍后重试", false);
            }
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new XPublishException(502, "等待 X 媒体处理时被中断", ex, false);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/media/upload?command=STATUS&media_id=" + urlEnc(mediaId)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> statusResp = sendMediaRequest(req, "媒体处理状态查询");
            JsonNode statusRoot = parseJsonOrNull(statusResp.body());
            if (statusResp.statusCode() >= 400) {
                throw new XPublishException(
                        statusResp.statusCode(),
                        "查询 X 媒体处理状态失败：" + extractXErrorDetail(statusRoot, statusResp.statusCode()),
                        false
                );
            }
            proc = extractProcessingInfo(statusRoot);
        }
    }

    private HttpResponse<String> sendMediaRequest(HttpRequest req, String actionLabel) {
        return sharedExecutor.execute(() -> {
            try {
                budgetGuard.acquirePermit(req.uri(), "x_media_upload");
            } catch (XDataProviderException ex) {
                throw new XPublishException(ex.getStatusCode(), ex.getMessage(), ex, false);
            }
            try {
                return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new XPublishException(502, actionLabel + "网络异常：" + ex.getMessage(), ex, false);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new XPublishException(502, actionLabel + "被中断", ex, false);
            }
        });
    }

    private byte[] buildMultipartAppendBody(
            String boundary,
            int segmentIndex,
            byte[] fullBytes,
            int offset,
            int len,
            String safeName
    ) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(len + 512);
            StringBuilder part1 = new StringBuilder();
            part1.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"segment_index\"\r\n\r\n")
                    .append(segmentIndex).append("\r\n");
            out.write(part1.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder part2Header = new StringBuilder();
            part2Header.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"media\"; filename=\"").append(safeName).append("\"\r\n")
                    .append("Content-Type: application/octet-stream\r\n\r\n");
            out.write(part2Header.toString().getBytes(StandardCharsets.UTF_8));
            out.write(fullBytes, offset, len);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build multipart body", ex);
        }
    }

    private JsonNode parseJsonOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractMediaId(JsonNode root) {
        if (root == null) {
            return "";
        }
        String id = root.path("data").path("id").asText("");
        if (!id.isBlank()) {
            return id;
        }
        id = root.path("data").path("media_id_string").asText("");
        if (!id.isBlank()) {
            return id;
        }
        id = root.path("media_id_string").asText("");
        if (!id.isBlank()) {
            return id;
        }
        return root.path("id").asText("");
    }

    private static JsonNode extractProcessingInfo(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode dataProc = root.path("data").path("processing_info");
        if (!dataProc.isMissingNode() && !dataProc.isNull()) {
            return dataProc;
        }
        JsonNode topProc = root.path("processing_info");
        return (!topProc.isMissingNode() && !topProc.isNull()) ? topProc : null;
    }

    private static String extractXErrorDetail(JsonNode root, int statusCode) {
        if (root != null) {
            String detail = root.path("detail").asText("");
            if (!detail.isBlank()) {
                return detail;
            }
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String msg = errors.get(0).path("message").asText("");
                if (!msg.isBlank()) {
                    return msg;
                }
            }
            String title = root.path("title").asText("");
            if (!title.isBlank()) {
                return title;
            }
            String errDesc = root.path("error_description").asText(root.path("error").asText(""));
            if (!errDesc.isBlank()) {
                return errDesc;
            }
        }
        return "HTTP " + statusCode;
    }

    private String resolveOfficialBaseUrl() {
        String baseUrl = properties.getOfficialBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.x.com/2";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String urlEnc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
