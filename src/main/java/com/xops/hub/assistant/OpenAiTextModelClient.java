package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
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
 * Official OpenAI Platform API client ({@code https://api.openai.com/v1}) supporting both
 * {@code POST /v1/responses} (default, with optional reasoning effort & {@code web_search_preview})
 * and {@code POST /v1/chat/completions}.
 */
public final class OpenAiTextModelClient implements TextModelClient {
    public static final String SERVICE_NAME = "openai";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-6.2-sol";
    public static final String FLAGSHIP_MODEL = "gpt-6.2-astra";
    public static final String MINI_MODEL = "gpt-6-luna";
    private static final Set<String> REASONING_EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh");

    private final ObjectMapper mapper;
    private final Transport transport;
    private final Function<String, String> credentials;

    public OpenAiTextModelClient(ObjectMapper mapper, Transport transport, Function<String, String> credentials) {
        this.mapper = mapper;
        this.transport = transport;
        this.credentials = credentials;
    }

    public record OpenAiModelSpec(String modelId, String reasoningEffort) { }

    public static OpenAiModelSpec parseModelSpec(String rawModel) {
        String trimmed = rawModel == null ? "" : rawModel.strip();
        if (trimmed.isEmpty() || "openai-default".equalsIgnoreCase(trimmed)) {
            return new OpenAiModelSpec(DEFAULT_MODEL, "");
        }
        String[] parts = trimmed.split("[\\s@:]+");
        String modelId = parts[0].strip();
        String effort = "";
        if (parts.length >= 2) {
            String tail = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            if (REASONING_EFFORTS.contains(tail)) {
                effort = tail;
            }
        }
        return new OpenAiModelSpec(modelId.isEmpty() ? DEFAULT_MODEL : modelId, effort);
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
        String apiKey = credentials.apply(binding.connection().credentialRef());
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelCallException("未配置 OpenAI API Key（OPENAI_API_KEY）", false, false, null);
        }
        OpenAiModelSpec spec = parseModelSpec(binding.modelId());
        Protocol protocol = (binding.protocol() == Protocol.CHAT_COMPLETIONS && !request.webSearch())
                ? Protocol.CHAT_COMPLETIONS
                : Protocol.RESPONSES;
        URI endpoint = resolveEndpoint(binding.connection().baseUri(), protocol);
        ObjectNode body = protocol == Protocol.CHAT_COMPLETIONS
                ? buildChatBody(spec, binding, request)
                : buildResponsesBody(spec, binding, request);

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey.strip()
        );

        TransportResponse response = transport.post(endpoint, headers, body, binding.timeout());
        int status = response.status();
        JsonNode root = response.body();
        String reqId = root.path("id").asText(response.headers().getOrDefault("x-request-id", null));
        if (status < 200 || status >= 300) {
            String errMsg = root.path("error").path("message").asText("");
            String detail = errMsg.isBlank() ? ("HTTP " + status) : ("HTTP " + status + ": " + errMsg);
            throw new ModelCallException("OpenAI API 调用失败（" + detail + "）",
                    status == 408 || status == 429 || status >= 500,
                    status == 408 || status >= 500,
                    reqId);
        }
        return parseResult(spec, binding, protocol, request, root, reqId);
    }

    static URI resolveEndpoint(URI baseUri, Protocol protocol) {
        String base = baseUri.toString().replaceFirst("/+$", "");
        String suffix = protocol == Protocol.CHAT_COMPLETIONS ? "/chat/completions" : "/responses";
        if (base.endsWith("/v1")) {
            return URI.create(base + suffix);
        }
        return URI.create(base + "/v1" + suffix);
    }

    private ObjectNode buildResponsesBody(OpenAiModelSpec spec, ModelBinding binding, TextRequest request) {
        ObjectNode body = mapper.createObjectNode().put("model", spec.modelId());
        ArrayNode input = body.putArray("input");
        if (request.systemText() != null && !request.systemText().isBlank()) {
            input.addObject()
                    .put("role", "system")
                    .putArray("content")
                    .addObject()
                    .put("type", "input_text")
                    .put("text", request.systemText());
        }
        String userPrompt = request.userText();
        if (request.webSearch() && !request.searchQuery().isBlank()) {
            userPrompt = "Preferred web search query: " + request.searchQuery() + "\n\n" + userPrompt;
        }
        input.addObject()
                .put("role", "user")
                .putArray("content")
                .addObject()
                .put("type", "input_text")
                .put("text", userPrompt);
        body.put("max_output_tokens", effectiveMaxTokens(spec, binding.maxOutputTokens()));
        if (!spec.reasoningEffort().isBlank() && supportsReasoningEffort(spec.modelId())) {
            body.putObject("reasoning").put("effort", spec.reasoningEffort());
        }
        if (request.structuredJson() && !request.webSearch()) {
            body.putObject("text").putObject("format").put("type", "json_object");
        }
        if (request.webSearch()) {
            body.putArray("tools").addObject().put("type", "web_search_preview");
        }
        return body;
    }

    private ObjectNode buildChatBody(OpenAiModelSpec spec, ModelBinding binding, TextRequest request) {
        ObjectNode body = mapper.createObjectNode().put("model", spec.modelId());
        ArrayNode messages = body.putArray("messages");
        if (request.systemText() != null && !request.systemText().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemText());
        }
        messages.addObject().put("role", "user").put("content", request.userText());
        body.put("max_completion_tokens", effectiveMaxTokens(spec, binding.maxOutputTokens()));
        if (!spec.reasoningEffort().isBlank() && supportsReasoningEffort(spec.modelId())) {
            body.put("reasoning_effort", spec.reasoningEffort());
        }
        if (request.structuredJson()) {
            body.putObject("response_format").put("type", "json_object");
        }
        return body;
    }

    private static int effectiveMaxTokens(OpenAiModelSpec spec, int requested) {
        int base = Math.max(256, requested);
        if (!supportsReasoningEffort(spec.modelId())) {
            return base;
        }
        return switch (spec.reasoningEffort()) {
            case "high", "xhigh" -> Math.max(16384, base);
            case "medium", "" -> Math.max(8192, base);
            default -> Math.max(4096, base);
        };
    }

    private static boolean supportsReasoningEffort(String modelId) {
        String lower = modelId.toLowerCase(Locale.ROOT);
        return lower.startsWith("gpt-5") || lower.startsWith("gpt-6")
                || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4");
    }

    private ModelResult parseResult(
            OpenAiModelSpec spec,
            ModelBinding binding,
            Protocol protocol,
            TextRequest request,
            JsonNode root,
            String reqId
    ) {
        Map<String, SearchResult> discoveredUrls = new LinkedHashMap<>();
        Map<String, JsonNode> toolEvents = new LinkedHashMap<>();
        String text;
        if (protocol == Protocol.CHAT_COMPLETIONS) {
            text = root.path("choices").path(0).path("message").path("content").asText("");
        } else {
            text = extractResponsesTextAndCitations(root, request.searchQuery(), discoveredUrls, toolEvents);
        }
        text = text == null ? "" : text.strip();
        if (request.structuredJson()) {
            text = stripJsonFence(text);
        }
        if (text.isBlank() && !request.webSearch()) {
            throw new ModelCallException("OpenAI API 返回内容为空", false, false, reqId);
        }

        JsonNode usageNode = root.path("usage");
        Long inputTokens = integralOrNull(usageNode, protocol == Protocol.CHAT_COMPLETIONS ? "prompt_tokens" : "input_tokens");
        Long outputTokens = integralOrNull(usageNode, protocol == Protocol.CHAT_COMPLETIONS ? "completion_tokens" : "output_tokens");

        ObjectNode snapshot = mapper.createObjectNode()
                .put("service", SERVICE_NAME)
                .put("model", spec.modelId())
                .put("protocol", protocol.name())
                .put("connectionVersion", binding.connection().version())
                .put("bindingVersion", binding.version());
        if (!spec.reasoningEffort().isBlank()) {
            snapshot.put("reasoningEffort", spec.reasoningEffort());
        }

        return new ModelResult(
                text,
                new ArrayList<>(discoveredUrls.values()),
                new Usage(inputTokens, outputTokens),
                reqId,
                snapshot,
                toolEvents
        );
    }

    private String extractResponsesTextAndCitations(
            JsonNode root,
            String query,
            Map<String, SearchResult> urls,
            Map<String, JsonNode> events
    ) {
        StringBuilder sb = new StringBuilder();
        int seq = 0;
        for (JsonNode item : root.path("output")) {
            String type = item.path("type").asText("");
            if (type.contains("web_search")) {
                events.put("web_search_" + (seq++), item.deepCopy());
                for (JsonNode src : item.path("action").path("sources")) {
                    addValidUrl(src.path("url").asText(""), src.path("title").asText(""), "", query, urls);
                }
            }
            for (JsonNode content : item.path("content")) {
                String cType = content.path("type").asText("");
                if ("output_text".equals(cType) || "text".equals(cType)) {
                    String chunk = content.path("text").asText("");
                    if (!chunk.isBlank()) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(chunk);
                    }
                    for (JsonNode ann : content.path("annotations")) {
                        addValidUrl(ann.path("url").asText(""), ann.path("title").asText(""), "", query, urls);
                    }
                }
            }
        }
        if (sb.isEmpty() && !root.path("output_text").asText("").isBlank()) {
            sb.append(root.path("output_text").asText(""));
        }
        return sb.toString();
    }

    private static void addValidUrl(String rawUrl, String title, String snippet, String query, Map<String, SearchResult> target) {
        if (rawUrl == null || rawUrl.isBlank()) return;
        try {
            URI uri = URI.create(rawUrl.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return;
            if (uri.getHost() == null || uri.getRawUserInfo() != null) return;
            target.putIfAbsent(uri.toString(), new SearchResult(query == null ? "" : query, uri, title == null ? "" : title, snippet == null ? "" : snippet, ""));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static String stripJsonFence(String text) {
        if (!text.startsWith("```")) return text;
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).strip();
        }
        return text;
    }

    private static Long integralOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isIntegralNumber() ? v.asLong() : null;
    }
}
