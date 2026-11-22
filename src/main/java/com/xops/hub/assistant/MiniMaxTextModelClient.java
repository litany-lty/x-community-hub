package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.xops.hub.assistant.CommandCodeTextModelClient.ModelCallException;
import static com.xops.hub.assistant.CommandCodeTextModelClient.Transport;
import static com.xops.hub.assistant.CommandCodeTextModelClient.TransportResponse;

/** MiniMax OpenAI-compatible text and Token Plan web search. */
public final class MiniMaxTextModelClient implements TextModelClient {
    private final ObjectMapper mapper;
    private final Transport transport;
    private final Function<String, String> credentials;

    public MiniMaxTextModelClient(ObjectMapper mapper, Transport transport,
                                  Function<String, String> credentials) {
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
        return EnumSet.of(Protocol.CHAT_COMPLETIONS, Protocol.MESSAGES);
    }

    @Override
    public Protocol defaultProtocol() {
        return Protocol.CHAT_COMPLETIONS;
    }

    @Override
    public ModelResult complete(ModelBinding binding, TextRequest request) {
        URI base = binding.connection().baseUri();
        String searchHost = searchHost(base);
        if (!"minimax".equals(binding.connection().serviceName())
                || !AssistantConnection.MINIMAX_KEY_ENV.equals(binding.connection().credentialRef())
                || binding.protocol() == Protocol.RESPONSES) {
            throw new IllegalArgumentException("MiniMax requires its own credentials and Chat Completions or Messages binding");
        }
        String key = credentials.apply(binding.connection().credentialRef());
        if (key == null || key.isBlank()) {
            throw new ModelCallException("MiniMax credential is unavailable", false, false, null);
        }
        var headers = Map.of("Content-Type", "application/json", "Authorization", "Bearer " + key.strip());
        var snapshot = mapper.createObjectNode().put("service", "minimax")
                .put("model", binding.modelId()).put("protocol", binding.protocol().name())
                .put("connectionVersion", binding.connection().version()).put("bindingVersion", binding.version());
        if (request.webSearch()) {
            String query = request.searchQuery();
            if (query.isBlank()) throw new IllegalArgumentException("MiniMax web search requires a search query");
            return search(binding, query, URI.create(searchHost + "/v1/coding_plan/search"), headers, snapshot);
        }
        boolean anthropic = binding.protocol() == Protocol.MESSAGES;
        var body = mapper.createObjectNode().put("model", binding.modelId()).put("stream", false)
                .put("max_tokens", binding.maxOutputTokens());
        var messages = body.putArray("messages");
        if (anthropic) {
            body.put("system", request.systemText());
            if ("MiniMax-M3.1-Flash".equals(binding.modelId())) body.putObject("output_config").put("effort", "low");
            headers = Map.of("Content-Type", "application/json", "Authorization", "Bearer " + key.strip(),
                    "anthropic-version", "2023-06-01");
        } else {
            body.put("reasoning_split", true);
            if ("MiniMax-M3.1-Flash".equals(binding.modelId())) body.put("reasoning_effort", "low");
            messages.addObject().put("role", "system").put("content", request.systemText());
        }
        messages.addObject().put("role", "user").put("content", request.userText());
        String path = anthropic ? "/anthropic/v1/messages" : "/v1/chat/completions";
        var response = transport.post(base.resolve(path), headers, body, binding.timeout());
        String id = check(response);
        var root = response.body();
        var usage = root.path("usage");
        String text = anthropic ? messagesText(root) : root.path("choices").path(0).path("message").path("content").asText("");
        boolean truncated = anthropic ? "max_tokens".equals(root.path("stop_reason").asText())
                : "length".equals(root.path("choices").path(0).path("finish_reason").asText());
        if (text.isBlank() || truncated) {
            throw new ModelCallException("MiniMax returned empty or truncated output", false, false, id);
        }
        return new ModelResult(text, List.of(), new Usage(count(usage, anthropic ? "input_tokens" : "prompt_tokens"),
                count(usage, anthropic ? "output_tokens" : "completion_tokens")), id, snapshot, Map.of());
    }

    private static String messagesText(JsonNode root) {
        var text = new StringBuilder();
        for (var part : root.path("content")) {
            if ("text".equals(part.path("type").asText())) text.append(part.path("text").asText());
        }
        return text.toString();
    }

    private ModelResult search(ModelBinding binding, String query, URI endpoint, Map<String, String> headers,
                               ObjectNode snapshot) {
        String terms = query.codePoints().limit(256)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var response = transport.post(endpoint, headers, mapper.createObjectNode().put("q", terms), binding.timeout());
        String id = check(response);
        var organic = response.body().path("organic");
        if (!organic.isArray()) throw new ModelCallException("MiniMax search returned no result list", false, false, id);
        List<SearchResult> matches = new ArrayList<>();
        var seen = new HashSet<URI>();
        for (var result : organic) {
            try {
                URI url = URI.create(result.path("link").asText());
                if (url.getHost() != null && url.getRawUserInfo() == null
                        && ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                        && seen.add(url)) {
                    matches.add(new SearchResult(terms, url, result.path("title").asText(""),
                            result.path("snippet").asText(""), result.path("date").asText("")));
                }
            } catch (IllegalArgumentException ignored) {
                // Only valid absolute URLs can proceed to the text source reader.
            }
        }
        var event = mapper.createObjectNode().put("type", "web_search_call").put("status", "completed")
                .put("query", terms).put("resultCount", matches.size());
        event.set("results", mapper.valueToTree(matches));
        return new ModelResult("", matches, new Usage(null, null), id, snapshot, Map.of("search-0", event));
    }

    private static String searchHost(URI base) {
        String url = base.toString().replaceFirst("/+$", "");
        return switch (url) {
            case "https://api.minimax.cn/v1", "https://api.minimaxi.com/v1" -> "https://api.minimaxi.com";
            case "https://api.minimax.io/v1" -> "https://api.minimax.io";
            default -> throw new IllegalArgumentException("MiniMax requires an official HTTPS API base URL");
        };
    }

    private static String check(TransportResponse response) {
        var root = response.body();
        String id = response.headers().getOrDefault("x-request-id",
                response.headers().getOrDefault("trace-id", root.path("id").asText(null)));
        int status = response.status();
        if (status < 200 || status >= 300) {
            boolean unknown = status == 408 || status >= 500;
            throw new ModelCallException("MiniMax returned HTTP " + status, status == 429, unknown, id);
        }
        var supplierStatus = root.path("base_resp").path("status_code");
        if (supplierStatus.isIntegralNumber() && supplierStatus.intValue() != 0) {
            int code = supplierStatus.intValue();
            throw new ModelCallException("MiniMax returned status " + code, code == 1002, code == 1001, id);
        }
        return id;
    }

    private static Long count(JsonNode usage, String field) {
        return usage.path(field).isIntegralNumber() ? usage.path(field).longValue() : null;
    }
}
