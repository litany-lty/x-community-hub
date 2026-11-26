package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Arrays;
import java.util.EnumSet;
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
 * Official Google Gemini Interactions API ({@code POST /v1/interactions}) client with native
 * {@code google_search} grounding and {@code steps} schema parsing.
 */
public final class GeminiTextModelClient implements TextModelClient {
    public static final String SERVICE_NAME = "google";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String DEFAULT_MODEL = "gemini-4-neon";
    public static final String FAST_MODEL = "gemini-4-hydrogen";
    public static final String PRO_MODEL = "gemini-4-carbon";
    public static final String API_REVISION = "2026-05-20";
    private static final Set<String> THINKING_LEVELS = Set.of("minimal", "low", "medium", "high");

    private final ObjectMapper mapper;
    private final Transport transport;
    private final Function<String, String> credentials;

    public GeminiTextModelClient(ObjectMapper mapper, Transport transport, Function<String, String> credentials) {
        this.mapper = mapper;
        this.transport = transport;
        this.credentials = credentials;
    }

    public record GeminiModelSpec(String modelId, String thinkingLevel) { }

    /**
     * Parses optional thinking level suffix (e.g. {@code "gemini-4-carbon high"}) and upgrades
     * deprecated legacy Gemini 1.5/2.0/2.5 identifiers to {@code gemini-4-hydrogen}.
     */
    public static GeminiModelSpec parseModelSpec(String rawModel) {
        String trimmed = rawModel == null ? "" : rawModel.strip();
        if (trimmed.isEmpty() || "google-default".equalsIgnoreCase(trimmed) || "gemini-default".equalsIgnoreCase(trimmed)) {
            return new GeminiModelSpec(DEFAULT_MODEL, "");
        }
        String modelPart = trimmed;
        String thinking = "";
        String[] parts = trimmed.split("[\\s@:]+");
        if (parts.length >= 2) {
            String tail = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            if (THINKING_LEVELS.contains(tail)) {
                thinking = tail;
                modelPart = String.join("-", Arrays.copyOf(parts, parts.length - 1)).strip();
            } else if ("xhigh".equals(tail) || "max".equals(tail) || "ultra".equals(tail)) {
                thinking = "high";
                modelPart = String.join("-", Arrays.copyOf(parts, parts.length - 1)).strip();
            } else if ("none".equals(tail)) {
                thinking = "minimal";
                modelPart = String.join("-", Arrays.copyOf(parts, parts.length - 1)).strip();
            }
        }
        if (modelPart.startsWith("models/")) {
            modelPart = modelPart.substring("models/".length());
        }
        String lower = modelPart.toLowerCase(Locale.ROOT);
        if (lower.startsWith("gemini-1.5") || lower.startsWith("gemini-2.0") || lower.startsWith("gemini-2.5")) {
            modelPart = FAST_MODEL;
        }
        return new GeminiModelSpec(modelPart, thinking);
    }

    @Override
    public Set<Protocol> supportedProtocols() {
        return EnumSet.of(Protocol.RESPONSES, Protocol.CHAT_COMPLETIONS);
    }

    @Override
    public Protocol defaultProtocol() {
        return Protocol.RESPONSES;
    }

    @Override
    public ModelResult complete(ModelBinding binding, TextRequest request) {
        if (!SERVICE_NAME.equalsIgnoreCase(binding.connection().serviceName())) {
            throw new IllegalArgumentException("GeminiTextModelClient requires google service connection");
        }
        String apiKey = credentials.apply(binding.connection().credentialRef());
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelCallException("未配置 Google Gemini API Key（可在运行设置填入或配置环境变量 GEMINI_API_KEY）", false, false, null);
        }

        GeminiModelSpec spec = parseModelSpec(binding.modelId());
        URI endpoint = resolveInteractionsEndpoint(binding.connection().baseUri());

        ObjectNode body = mapper.createObjectNode()
                .put("model", spec.modelId())
                .put("store", false);

        StringBuilder sysBuilder = new StringBuilder();
        if (!request.systemText().isBlank()) {
            sysBuilder.append(request.systemText().strip());
        }
        if (request.structuredJson()) {
            if (!sysBuilder.isEmpty()) sysBuilder.append("\n");
            sysBuilder.append("Output ONLY a single valid JSON object without Markdown code fences.");
        }
        if (!sysBuilder.isEmpty()) {
            body.put("system_instruction", sysBuilder.toString());
        }

        String userPrompt = request.userText();
        if (request.webSearch() && !request.searchQuery().isBlank()) {
            userPrompt = "Preferred Google Search query: " + request.searchQuery() + "\n\n" + userPrompt;
        }
        body.put("input", userPrompt);

        String effectiveThinking = !spec.thinkingLevel().isBlank()
                ? spec.thinkingLevel()
                : (spec.modelId().toLowerCase(Locale.ROOT).contains("carbon") ? "medium" : "low");
        ObjectNode genCfg = body.putObject("generation_config")
                .put("max_output_tokens", effectiveMaxOutputTokens(effectiveThinking, binding.maxOutputTokens()))
                .put("thinking_level", effectiveThinking);

        if (request.webSearch()) {
            body.putArray("tools").addObject().put("type", "google_search");
        }

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "x-goog-api-key", apiKey.strip(),
                "Api-Revision", API_REVISION
        );

        TransportResponse response = transport.post(endpoint, headers, body, binding.timeout());
        int status = response.status();
        JsonNode root = response.body();
        String reqId = root.path("id").asText(response.headers().getOrDefault("x-request-id", null));

        if (status < 200 || status >= 300) {
            String errMsg = root.path("error").path("message").asText("");
            String detail = errMsg.isBlank() ? ("HTTP " + status) : ("HTTP " + status + ": " + errMsg);
            throw new ModelCallException("Google Gemini API 调用失败（" + detail + "）",
                    status == 408 || status == 429 || status >= 500,
                    status == 408 || status >= 500,
                    reqId);
        }

        String interactionStatus = root.path("status").asText("completed").toLowerCase(Locale.ROOT);
        if ("failed".equals(interactionStatus) || "cancelled".equals(interactionStatus)) {
            throw new ModelCallException("Google Gemini Interaction 状态异常：" + interactionStatus, false, false, reqId);
        }

        StringBuilder outText = new StringBuilder();
        Map<String, SearchResult> discoveredUrls = new LinkedHashMap<>();
        Map<String, JsonNode> toolEvents = new LinkedHashMap<>();
        extractInteractionOutput(root, request.searchQuery(), outText, discoveredUrls, toolEvents);

        // If default/high thinking exhausted max_output_tokens (status=incomplete with 0 text), retry once at low thinking
        if (outText.toString().isBlank() && "incomplete".equals(interactionStatus) && !"minimal".equals(spec.thinkingLevel())) {
            ObjectNode retryBody = body.deepCopy();
            retryBody.putObject("generation_config")
                    .put("max_output_tokens", Math.max(16384, binding.maxOutputTokens()))
                    .put("thinking_level", "low");
            TransportResponse retryResp = transport.post(endpoint, headers, retryBody, binding.timeout());
            if (retryResp.status() >= 200 && retryResp.status() < 300) {
                root = retryResp.body();
                reqId = root.path("id").asText(reqId);
                extractInteractionOutput(root, request.searchQuery(), outText, discoveredUrls, toolEvents);
            }
        }

        String finalText = outText.toString().strip();
        if (request.structuredJson()) {
            finalText = stripJsonFence(finalText);
        }
        if (request.webSearch() && !discoveredUrls.isEmpty() && toolEvents.isEmpty()) {
            ArrayNode cites = mapper.createArrayNode();
            discoveredUrls.values().forEach(sr -> cites.add(sr.url().toString()));
            ObjectNode ev = mapper.createObjectNode()
                    .put("type", "google_search_grounding")
                    .put("status", "completed")
                    .put("resultCount", discoveredUrls.size());
            ev.set("urls", cites);
            toolEvents.put("google_search_0", ev);
        }

        if (finalText.isBlank() && !request.webSearch()) {
            throw new ModelCallException("Google Gemini 返回内容为空", false, false, reqId);
        }

        JsonNode usageNode = root.path("usage");
        Long inputTokens = integralOrNull(usageNode, "total_input_tokens", "input_tokens", "prompt_token_count");
        Long outputTokens = integralOrNull(usageNode, "total_output_tokens", "output_tokens", "candidates_token_count");

        ObjectNode snapshot = mapper.createObjectNode()
                .put("service", SERVICE_NAME)
                .put("model", spec.modelId())
                .put("protocol", binding.protocol().name())
                .put("connectionVersion", binding.connection().version())
                .put("bindingVersion", binding.version());
        if (!spec.thinkingLevel().isBlank()) {
            snapshot.put("thinkingLevel", spec.thinkingLevel());
        }

        return new ModelResult(
                finalText,
                List.copyOf(discoveredUrls.values()),
                new Usage(inputTokens, outputTokens),
                reqId,
                snapshot,
                toolEvents
        );
    }

    static URI resolveInteractionsEndpoint(URI baseUri) {
        String base = baseUri.toString().replaceFirst("/+$", "");
        if (base.endsWith("/v1") || base.endsWith("/v1beta")) {
            return URI.create(base + "/interactions");
        }
        return URI.create(base + "/v1/interactions");
    }

    private static void extractCitationUrls(
            JsonNode annotations,
            String chunkText,
            String query,
            Map<String, SearchResult> target
    ) {
        if (annotations == null || !annotations.isArray()) return;
        for (JsonNode ann : annotations) {
            String urlStr = ann.path("url").asText(ann.path("uri").asText("")).strip();
            if (urlStr.isBlank()) continue;
            String title = ann.path("title").asText("").strip();
            int start = ann.path("start_index").asInt(ann.path("startIndex").asInt(-1));
            int end = ann.path("end_index").asInt(ann.path("endIndex").asInt(-1));
            String snippet = (start >= 0 && end > start && end <= chunkText.length())
                    ? chunkText.substring(start, end).strip()
                    : "";
            addValidUrl(urlStr, title, snippet, "", query, target);
        }
    }

    private static void extractSearchStepResults(
            JsonNode step,
            String defaultQuery,
            Map<String, SearchResult> target
    ) {
        String q = defaultQuery;
        JsonNode queries = step.path("arguments").path("queries");
        if (queries.isArray() && !queries.isEmpty()) {
            q = queries.get(0).asText(defaultQuery);
        }
        for (String field : List.of("result", "results", "sources")) {
            JsonNode arr = step.path(field);
            if (!arr.isArray()) continue;
            for (JsonNode item : arr) {
                String urlStr = item.path("url").asText(item.path("uri").asText(item.path("link").asText(""))).strip();
                String title = item.path("title").asText("").strip();
                String snippet = item.path("snippet").asText(item.path("rendered_content").asText("")).strip();
                String pub = item.path("published_at").asText(item.path("date").asText("")).strip();
                addValidUrl(urlStr, title, snippet, pub, q, target);
            }
        }
    }

    private static void addValidUrl(
            String rawUrl,
            String title,
            String snippet,
            String publishedAt,
            String query,
            Map<String, SearchResult> target
    ) {
        if (rawUrl == null || rawUrl.isBlank()) return;
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && uri.getRawUserInfo() == null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                target.putIfAbsent(uri.toString(), new SearchResult(query == null ? "" : query, uri, title, snippet, publishedAt));
            }
        } catch (IllegalArgumentException ignored) { }
    }

    static String stripJsonFence(String text) {
        if (text == null) return "";
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1).strip();
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3).strip();
        }
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1);
        }
        return s;
    }

    static int effectiveMaxOutputTokens(String thinkingLevel, int requested) {
        int base = Math.max(256, requested);
        return switch (thinkingLevel == null ? "" : thinkingLevel.toLowerCase(Locale.ROOT)) {
            case "minimal", "low" -> Math.max(4096, base);
            default -> Math.max(16384, base);
        };
    }

    private static void extractInteractionOutput(
            JsonNode root,
            String searchQuery,
            StringBuilder outText,
            Map<String, SearchResult> discoveredUrls,
            Map<String, JsonNode> toolEvents
    ) {
        int eventSeq = toolEvents.size();
        JsonNode steps = root.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String stepType = step.path("type").asText("").toLowerCase(Locale.ROOT);
                if ("model_output".equals(stepType) || "message".equals(stepType)) {
                    for (JsonNode block : step.path("content")) {
                        if ("text".equalsIgnoreCase(block.path("type").asText("text"))
                                || "output_text".equalsIgnoreCase(block.path("type").asText(""))) {
                            String chunk = block.path("text").asText("");
                            if (!chunk.isBlank()) {
                                if (!outText.isEmpty()) outText.append("\n");
                                outText.append(chunk);
                            }
                            extractCitationUrls(block.path("annotations"), chunk, searchQuery, discoveredUrls);
                            extractCitationUrls(block.path("citations"), chunk, searchQuery, discoveredUrls);
                        }
                    }
                } else if (stepType.contains("google_search") || stepType.contains("web_search")) {
                    toolEvents.put("google_search_" + (eventSeq++), step.deepCopy());
                    extractSearchStepResults(step, searchQuery, discoveredUrls);
                }
            }
        }
        if (outText.isEmpty() && root.path("outputs").isArray()) {
            for (JsonNode out : root.path("outputs")) {
                if ("text".equalsIgnoreCase(out.path("type").asText(""))) {
                    String chunk = out.path("text").asText("");
                    if (!chunk.isBlank()) {
                        if (!outText.isEmpty()) outText.append("\n");
                        outText.append(chunk);
                    }
                }
            }
        }
    }

    private static Long integralOrNull(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode()) return null;
        for (String key : keys) {
            if (node.path(key).isIntegralNumber()) return node.path(key).longValue();
        }
        return null;
    }
}
