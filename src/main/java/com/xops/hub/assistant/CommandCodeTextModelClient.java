package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CommandCodeTextModelClient implements TextModelClient {
    private final ObjectMapper mapper;
    private final Transport transport;
    private final CredentialResolver credentials;

    public CommandCodeTextModelClient(ObjectMapper mapper, Transport transport, CredentialResolver credentials) {
        this.mapper = mapper;
        this.transport = transport;
        this.credentials = credentials;
    }

    @Override
    public ModelResult complete(ModelBinding binding, TextRequest request) {
        String base=binding.connection().baseUri().toString().replaceFirst("/+$","");
        String path=endpoint(binding.protocol());
        URI endpoint=URI.create(base+(base.endsWith("/v1")?path.substring(3):path));
        ObjectNode body = switch (binding.protocol()) {
            case CHAT_COMPLETIONS -> chatBody(binding, request);
            case RESPONSES -> responsesBody(binding, request);
            case MESSAGES -> messagesBody(binding, request);
        };
        String credential = credentials.resolve(binding.connection().credentialRef());
        if (credential == null || credential.isBlank()) throw new ModelCallException("model credential is unavailable", false, false, null);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + credential);
        if (binding.protocol() == Protocol.MESSAGES) headers.put("anthropic-version", "2023-06-01");
        TransportResponse response = transport.post(endpoint, Map.copyOf(headers), body, binding.timeout());
        if (response.status() < 200 || response.status() >= 300) {
            throw new ModelCallException("model returned HTTP " + response.status(), response.status() == 408 || response.status() == 429 || response.status() >= 500,
                    response.status()==408 || response.status()>=500,
                    response.headers().get("x-request-id"));
        }
        return parse(binding, response);
    }

    private ObjectNode chatBody(ModelBinding binding, TextRequest request) {
        ObjectNode body = baseBody(binding);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemText());
        messages.addObject().put("role", "user").put("content", request.userText());
        body.put("max_tokens", binding.maxOutputTokens());
        if (request.structuredJson()) body.putObject("response_format").put("type", "json_object");
        if (request.webSearch()) throw new IllegalArgumentException("web search requires a Responses binding");
        return body;
    }

    private ObjectNode responsesBody(ModelBinding binding, TextRequest request) {
        ObjectNode body = baseBody(binding);
        ArrayNode input = body.putArray("input");
        input.addObject().put("role", "system").putArray("content").addObject().put("type", "input_text").put("text", request.systemText());
        input.addObject().put("role", "user").putArray("content").addObject().put("type", "input_text").put("text", request.userText());
        body.put("max_output_tokens", binding.maxOutputTokens());
        if (request.structuredJson()) body.putObject("text").putObject("format").put("type", "json_object");
        if (request.webSearch()) {
            body.putArray("tools").addObject().put("type","web_search");
            body.put("tool_choice","required");
            body.putArray("include").add("web_search_call.action.sources");
        }
        return body;
    }

    private ObjectNode messagesBody(ModelBinding binding, TextRequest request) {
        if (request.webSearch()) throw new IllegalArgumentException("web search requires a Responses binding");
        ObjectNode body = baseBody(binding);
        body.put("system", request.systemText());
        body.put("max_tokens", binding.maxOutputTokens());
        body.putArray("messages").addObject().put("role", "user").put("content", request.userText());
        return body;
    }

    private ObjectNode baseBody(ModelBinding binding) {
        return mapper.createObjectNode().put("model", binding.modelId()).put("stream", false);
    }

    private ModelResult parse(ModelBinding binding, TransportResponse response) {
        JsonNode root = response.body();
        String text = switch (binding.protocol()) {
            case CHAT_COMPLETIONS -> root.path("choices").path(0).path("message").path("content").asText("");
            case RESPONSES -> responseText(root);
            case MESSAGES -> contentText(root.path("content"));
        };
        JsonNode usageNode = root.path("usage");
        Long input = nullableLong(usageNode, binding.protocol() == Protocol.CHAT_COMPLETIONS ? "prompt_tokens" : "input_tokens");
        Long output = nullableLong(usageNode, binding.protocol() == Protocol.CHAT_COMPLETIONS ? "completion_tokens" : "output_tokens");
        List<SearchResult> searches = new ArrayList<>();
        Map<String, JsonNode> events = new LinkedHashMap<>();
        collectToolEvents(root.path("output"), searches, events);
        ObjectNode snapshot = mapper.createObjectNode()
                .put("service", binding.connection().serviceName())
                .put("connectionVersion", binding.connection().version())
                .put("bindingVersion", binding.version())
                .put("model", binding.modelId())
                .put("protocol", binding.protocol().name());
        return new ModelResult(text, searches, new Usage(input, output), response.headers().get("x-request-id"), snapshot, events);
    }

    private String responseText(JsonNode root) {
        String direct = root.path("output_text").asText("");
        if (!direct.isBlank()) return direct;
        for (JsonNode item : root.path("output")) {
            String text = contentText(item.path("content"));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String contentText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String value = part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    private void collectToolEvents(JsonNode output, List<SearchResult> searches, Map<String, JsonNode> events) {
        int index = 0;
        String query = "";
        for (JsonNode item : output) {
            String type = item.path("type").asText("").toLowerCase(Locale.ROOT);
            if (type.equals("web_search_call") && !item.path("status").asText().equals("failed")) {
                events.put("search-" + index++, item.deepCopy());
                query = item.path("query").asText(item.path("action").path("query").asText(query));
                JsonNode results = item.has("results") ? item.path("results") : item.path("action").has("sources") ? item.path("action").path("sources") : item.path("action").path("results");
                for (JsonNode result : results) {
                    String rawUrl = result.path("url").asText("");
                    try {
                        searches.add(new SearchResult(item.path("query").asText(""), URI.create(rawUrl),
                                result.path("title").asText(""), result.path("snippet").asText(""), result.path("published_at").asText("")));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid supplier URLs are retained in the raw event and excluded from actionable results.
                    }
                }
            }
        }
        for (JsonNode item : output) {
            for (JsonNode content : item.path("content")) {
                for (JsonNode annotation : content.path("annotations")) {
                    if (!annotation.path("type").asText("").contains("url_citation")) continue;
                    String rawUrl = annotation.path("url").asText("");
                    try {
                        URI url = URI.create(rawUrl);
                        boolean alreadyPresent = searches.stream().anyMatch(existing -> existing.url().equals(url));
                        if (!alreadyPresent) searches.add(new SearchResult(query, url, annotation.path("title").asText(""), "", ""));
                    } catch (IllegalArgumentException ignored) {
                        // The raw response stays available in the task attempt for diagnosis.
                    }
                }
            }
        }
    }

    private static Long nullableLong(JsonNode node, String field) {
        return node.has(field) && node.path(field).canConvertToLong() ? node.path(field).longValue() : null;
    }

    private static String endpoint(Protocol protocol) {
        return switch (protocol) {
            case CHAT_COMPLETIONS -> "/v1/chat/completions";
            case RESPONSES -> "/v1/responses";
            case MESSAGES -> "/v1/messages";
        };
    }

    public interface CredentialResolver { String resolve(String credentialRef); }
    public interface Transport { TransportResponse post(URI uri, Map<String, String> headers, JsonNode body, Duration timeout); }
    public record TransportResponse(int status, JsonNode body, Map<String, String> headers) {
        public TransportResponse { headers = headers == null ? Map.of() : Map.copyOf(headers); }
    }
    public static final class ModelCallException extends RuntimeException {
        private final boolean retryable;
        private final boolean resultUnknown;
        private final String requestId;
        public ModelCallException(String message, boolean retryable, boolean resultUnknown, String requestId) {
            super(message); this.retryable = retryable; this.resultUnknown = resultUnknown; this.requestId = requestId;
        }
        public boolean retryable() { return retryable; }
        public boolean resultUnknown() { return resultUnknown; }
        public String requestId() { return requestId; }
    }
}
