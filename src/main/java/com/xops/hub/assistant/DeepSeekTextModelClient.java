package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.xops.hub.assistant.CommandCodeTextModelClient.ModelCallException;
import static com.xops.hub.assistant.CommandCodeTextModelClient.Transport;
import static com.xops.hub.assistant.CommandCodeTextModelClient.TransportResponse;

/**
 * Official DeepSeek API client implementing:
 * 1) {@code POST https://api.deepseek.com/v1/tools/search} & {@code /v1/tools/fetch}
 *    (https://api-docs.deepseek.com/zh-cn/guides/search/) for web research & source reading;
 * 2) {@code POST https://api.deepseek.com/v1/chat/completions} ({@code deepseek-chat} /
 *    {@code deepseek-reasoner}) for search query planning & evidence synthesis.
 */
public final class DeepSeekTextModelClient implements TextModelClient {
    public static final String SERVICE_NAME = "deepseek";
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int MAX_QUERY_CODE_POINTS = 128;

    public record CachedWebContent(String title, String content, String summary, String publishedAt) { }

    private final ObjectMapper mapper;
    private final Transport transport;
    private final Function<String, String> credentials;
    private final Map<String, CachedWebContent> searchContentCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedWebContent> eldest) {
                    return size() > 160;
                }
            }
    );

    public DeepSeekTextModelClient(ObjectMapper mapper, Transport transport, Function<String, String> credentials) {
        this.mapper = mapper;
        this.transport = transport;
        this.credentials = credentials;
    }

    @Override
    public boolean requiresExplicitSearchQuery(ModelBinding binding) {
        return true;
    }

    @Override
    public Set<Protocol> supportedProtocols() {
        return EnumSet.of(Protocol.CHAT_COMPLETIONS, Protocol.RESPONSES);
    }

    @Override
    public Protocol defaultProtocol() {
        return Protocol.CHAT_COMPLETIONS;
    }

    @Override
    public ModelResult complete(ModelBinding binding, TextRequest request) {
        String key = resolveApiKey(binding.connection().credentialRef());
        URI base = normalizeBase(binding.connection().baseUri());
        String modelId = binding.modelId() == null || binding.modelId().isBlank()
                ? DEFAULT_MODEL
                : binding.modelId().strip();

        ObjectNode snapshot = mapper.createObjectNode()
                .put("service", SERVICE_NAME)
                .put("model", modelId)
                .put("protocol", binding.protocol().name())
                .put("connectionVersion", binding.connection().version())
                .put("bindingVersion", binding.version());

        if (request.webSearch()) {
            String rawQuery = request.searchQuery().isBlank() ? request.userText() : request.searchQuery();
            return executeWebSearch(base, key, rawQuery, request.userText(), binding.timeout(), snapshot);
        }

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key
        );
        boolean isReasoner = modelId.toLowerCase(Locale.ROOT).contains("reasoner");
        ObjectNode body = mapper.createObjectNode()
                .put("model", modelId)
                .put("stream", false)
                .put("max_tokens", Math.min(8192, Math.max(256, binding.maxOutputTokens())));
        if (request.structuredJson() && !isReasoner) {
            body.putObject("response_format").put("type", "json_object");
        }
        ArrayNode messages = body.putArray("messages");
        if (!request.systemText().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemText());
        }
        messages.addObject().put("role", "user").put("content", request.userText());

        URI chatEndpoint = URI.create(base + "/v1/chat/completions");
        TransportResponse response = transport.post(chatEndpoint, headers, body, binding.timeout());
        String reqId = checkStatus(response, "DeepSeek Chat");
        JsonNode root = response.body();
        JsonNode choice = root.path("choices").path(0);
        String text = choice.path("message").path("content").asText("");
        if (request.structuredJson()) {
            text = GeminiTextModelClient.stripJsonFence(text);
        }
        boolean truncated = "length".equals(choice.path("finish_reason").asText(""));
        if (text.isBlank() || truncated) {
            throw new ModelCallException("DeepSeek 返回为空或输出被截断", false, false, reqId);
        }
        JsonNode usage = root.path("usage");
        return new ModelResult(
                text,
                List.of(),
                new Usage(integralOrNull(usage, "prompt_tokens"), integralOrNull(usage, "completion_tokens")),
                reqId,
                snapshot,
                Map.of()
        );
    }

    /**
     * Calls {@code POST https://api.deepseek.com/v1/tools/search} directly. Can also be invoked by
     * {@link SearchProvider} even when another model handles query planning.
     */
    public ModelResult executeWebSearch(
            URI rawBase,
            String rawApiKey,
            String rawQuery,
            String oriQuestionHint,
            Duration timeout,
            ObjectNode snapshot
    ) {
        String key = (rawApiKey == null || rawApiKey.isBlank())
                ? resolveApiKey(AssistantConnection.DEEPSEEK_KEY_ENV)
                : rawApiKey.strip();
        String query = limitCodePoints(rawQuery == null ? "" : rawQuery.replaceAll("\\s+", " ").strip(), MAX_QUERY_CODE_POINTS);
        if (query.isBlank()) {
            throw new IllegalArgumentException("DeepSeek 网页搜索 query 不能为空");
        }
        URI base = normalizeBase(rawBase == null ? URI.create(DEFAULT_BASE_URL) : rawBase);
        URI searchUri = URI.create(base + "/v1/tools/search");

        ObjectNode reqBody = mapper.createObjectNode()
                .put("query", query)
                .put("count", 8)
                .put("need_content", true);
        if (oriQuestionHint != null && !oriQuestionHint.isBlank()) {
            String cleanOri = limitCodePoints(oriQuestionHint.replaceAll("\\s+", " ").strip(), MAX_QUERY_CODE_POINTS);
            if (!cleanOri.isBlank() && !cleanOri.equals(query)) {
                reqBody.put("ori_question", cleanOri);
            }
        }

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key
        );
        TransportResponse response = transport.post(searchUri, headers, reqBody, timeout != null ? timeout : Duration.ofSeconds(45));
        String reqId = checkStatus(response, "DeepSeek Search");
        JsonNode resultsNode = response.body().path("results");
        if (!resultsNode.isArray()) {
            throw new ModelCallException("DeepSeek /v1/tools/search 未返回 results 列表", false, false, reqId);
        }

        List<SearchResult> matches = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (JsonNode item : resultsNode) {
            String urlStr = item.path("url").asText("").strip();
            if (urlStr.isBlank()) continue;
            try {
                URI uri = URI.create(urlStr);
                String scheme = uri.getScheme();
                if (uri.getHost() == null || uri.getRawUserInfo() != null
                        || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                    continue;
                }
                String normKey = AssistantEvidence.normalizedUrl(urlStr);
                if (!seenUrls.add(normKey.isBlank() ? uri.toString() : normKey)) continue;

                String title = item.path("title").asText("").strip();
                String summary = item.path("summary").asText("").strip();
                String snippet = item.path("snippet").asText("").strip();
                String content = item.path("content").asText("").strip();
                String date = item.path("date").isNull() ? "" : item.path("date").asText("").strip();
                String effectiveSnippet = !summary.isBlank() ? summary : snippet;

                matches.add(new SearchResult(query, uri, title, effectiveSnippet, date));
                if (!content.isBlank() || !summary.isBlank()) {
                    String cacheBody = !content.isBlank() ? content : summary;
                    searchContentCache.put(uri.toString(), new CachedWebContent(title, cacheBody, summary, date));
                    if (!normKey.isBlank()) {
                        searchContentCache.put(normKey, new CachedWebContent(title, cacheBody, summary, date));
                    }
                }
            } catch (IllegalArgumentException ignored) { }
        }

        ObjectNode event = mapper.createObjectNode()
                .put("type", "deepseek_web_search")
                .put("endpoint", "/v1/tools/search")
                .put("status", "completed")
                .put("query", query)
                .put("resultCount", matches.size());
        event.set("results", mapper.valueToTree(matches));

        ObjectNode summaryJson = mapper.createObjectNode()
                .put("summary", "DeepSeek web search returned " + matches.size() + " results")
                .put("query", query);
        return new ModelResult(
                summaryJson.toString(),
                matches,
                new Usage(null, null),
                reqId,
                snapshot,
                Map.of("deepseek-search-0", event)
        );
    }

    /**
     * Returns cached full-text from {@code /v1/tools/search (need_content=true)} or fetches via
     * {@code POST https://api.deepseek.com/v1/tools/fetch} as fallback when direct scraping fails.
     */
    public CachedWebContent readCachedOrFetch(URI url, Duration timeout) {
        if (url == null) return null;
        String raw = url.toString();
        String norm = AssistantEvidence.normalizedUrl(raw);
        CachedWebContent hit = searchContentCache.get(raw);
        if (hit == null && !norm.isBlank()) {
            hit = searchContentCache.get(norm);
        }
        if (hit != null && hit.content() != null && hit.content().length() >= 80) {
            return hit;
        }
        String key = credentials.apply(AssistantConnection.DEEPSEEK_KEY_ENV);
        if (key == null || key.isBlank() || raw.length() > 8192 || url.getRawUserInfo() != null) {
            return hit;
        }
        try {
            URI fetchUri = URI.create(DEFAULT_BASE_URL + "/v1/tools/fetch");
            ObjectNode req = mapper.createObjectNode().put("url", raw);
            Map<String, String> headers = Map.of(
                    "Content-Type", "application/json",
                    "Authorization", "Bearer " + key.strip()
            );
            TransportResponse resp = transport.post(fetchUri, headers, req,
                    timeout != null ? timeout : Duration.ofSeconds(20));
            if (resp.status() >= 200 && resp.status() < 300) {
                String title = resp.body().path("title").asText("").strip();
                String content = resp.body().path("content").asText("").strip();
                if (!content.isBlank()) {
                    CachedWebContent fetched = new CachedWebContent(title, content, "", "");
                    searchContentCache.put(raw, fetched);
                    if (!norm.isBlank()) searchContentCache.put(norm, fetched);
                    return fetched;
                }
            }
        } catch (Exception ignored) { }
        return hit;
    }

    private String resolveApiKey(String credentialRef) {
        String ref = (credentialRef == null || credentialRef.isBlank())
                ? AssistantConnection.DEEPSEEK_KEY_ENV
                : credentialRef;
        String key = credentials.apply(ref);
        if (key == null || key.isBlank()) {
            throw new ModelCallException("未配置 DeepSeek API Key（可在运行设置填入或配置环境变量 DEEPSEEK_API_KEY）", false, false, null);
        }
        return key.strip();
    }

    private static URI normalizeBase(URI baseUri) {
        String s = baseUri.toString().replaceFirst("/+$", "");
        if (s.endsWith("/v1")) {
            s = s.substring(0, s.length() - 3);
        }
        return URI.create(s);
    }

    private static String checkStatus(TransportResponse response, String label) {
        int status = response.status();
        JsonNode root = response.body();
        String id = response.headers().getOrDefault("x-request-id", root.path("id").asText(null));
        if (status < 200 || status >= 300) {
            String errMsg = root.path("error").path("message").asText("");
            String detail = errMsg.isBlank() ? ("HTTP " + status) : ("HTTP " + status + ": " + errMsg);
            throw new ModelCallException(label + " 调用失败（" + detail + "）",
                    status == 408 || status == 429 || status >= 500,
                    status == 408 || status >= 500,
                    id);
        }
        return id;
    }

    private static String limitCodePoints(String text, int max) {
        if (text == null || text.isEmpty()) return "";
        int cp = text.codePointCount(0, text.length());
        if (cp <= max) return text;
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static Long integralOrNull(JsonNode usage, String field) {
        return usage.path(field).isIntegralNumber() ? usage.path(field).longValue() : null;
    }
}
