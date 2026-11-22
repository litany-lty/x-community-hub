package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/** Stores capability evidence for one immutable connection/model/protocol combination. */
public final class ModelCapabilityVerifier {
    private final ObjectMapper mapper;
    private final TextModelClient client;

    public ModelCapabilityVerifier(ObjectMapper mapper, TextModelClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    public CapabilityStatus verify(TextModelClient.ModelBinding binding, boolean requireWebSearch) {
        boolean textGeneration = false;
        boolean structuredOutput = false;
        boolean searchEvent = false;
        boolean sourceReturned = false;
        String error = "";
        try {
            TextModelClient.ModelResult result = client.complete(binding,
                    new TextModelClient.TextRequest("Return JSON only.", "Return {\"status\":\"ok\"}.", true, false));
            textGeneration = !result.text().isBlank();
            JsonNode parsed = mapper.readTree(result.text());
            structuredOutput = parsed != null && "ok".equals(parsed.path("status").asText());
            if (!structuredOutput) error = "模型未返回约定的结构化文字";
            if (structuredOutput && requireWebSearch) {
                var search = client.complete(binding, new TextModelClient.TextRequest("Return JSON only.",
                        "Use web search to find the SQLite official documentation and cite its URL.",
                        true, true, "SQLite official documentation"));
                searchEvent = !search.toolEvents().isEmpty();
                sourceReturned = !search.searchResults().isEmpty();
                if (!searchEvent || !sourceReturned) error = "联网能力未就绪，缺少搜索事件或来源";
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return new CapabilityStatus(binding.connection().version(), binding.modelId(), binding.protocol(),
                binding.contextTokens(), binding.timeout().toSeconds(), binding.maxOutputTokens(), binding.version(),
                textGeneration, structuredOutput, searchEvent, sourceReturned,
                structuredOutput && (!requireWebSearch || searchEvent && sourceReturned), Instant.now(), error);
    }

    public record CapabilityStatus(long connectionVersion, String modelId, TextModelClient.Protocol protocol,
                                   int contextTokens, long timeoutSeconds, int maxOutputTokens, String bindingVersion,
                                   boolean textGeneration, boolean structuredOutput, boolean searchEvent,
                                   boolean sourceReturned, boolean ready, Instant checkedAt, String error) { }
}
