package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supplier-neutral contract used by content-assistant business stages. */
public interface TextModelClient {
    ModelResult complete(ModelBinding binding, TextRequest request);

    default boolean requiresExplicitSearchQuery(ModelBinding binding) {
        return false;
    }

    default Set<Protocol> supportedProtocols() {
        return EnumSet.allOf(Protocol.class);
    }

    default Protocol defaultProtocol() {
        return Protocol.RESPONSES;
    }

    enum Protocol { CHAT_COMPLETIONS, RESPONSES, MESSAGES }

    record ServiceConnection(String serviceName, URI baseUri, String credentialRef, long version) {
        public ServiceConnection {
            if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("serviceName is required");
            if (baseUri == null || !"https".equalsIgnoreCase(baseUri.getScheme())) {
                throw new IllegalArgumentException("model baseUri must use https");
            }
            if (baseUri.getRawUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null) {
                throw new IllegalArgumentException("model baseUri cannot contain credentials, query, or fragment");
            }
            if (credentialRef == null || credentialRef.isBlank()) throw new IllegalArgumentException("credentialRef is required");
        }
    }

    record ModelBinding(ServiceConnection connection, String modelId, Protocol protocol, int contextTokens,
                        Duration timeout, int maxOutputTokens, String version) {
        public ModelBinding {
            if (connection == null || modelId == null || modelId.isBlank() || protocol == null) {
                throw new IllegalArgumentException("connection, modelId, and protocol are required");
            }
            if (contextTokens <= 0 || timeout == null || timeout.isNegative() || timeout.isZero() || maxOutputTokens <= 0) {
                throw new IllegalArgumentException("model budgets must be positive");
            }
            if (version == null || version.isBlank()) throw new IllegalArgumentException("binding version is required");
        }
    }

    record TextRequest(String systemText, String userText, boolean structuredJson, boolean webSearch,
                       String searchQuery) {
        public TextRequest(String systemText, String userText, boolean structuredJson, boolean webSearch) {
            this(systemText, userText, structuredJson, webSearch, "");
        }

        public TextRequest {
            systemText = systemText == null ? "" : systemText;
            if (userText == null || userText.isBlank()) throw new IllegalArgumentException("userText is required");
            searchQuery = searchQuery == null ? "" : searchQuery.strip();
        }
    }

    record SearchResult(String query, URI url, String title, String snippet, String publishedAt) { }

    record Usage(Long inputTokens, Long outputTokens) { }

    record ModelResult(String text, List<SearchResult> searchResults, Usage usage, String requestId,
                       JsonNode modelSnapshot, Map<String, JsonNode> toolEvents) {
        public ModelResult {
            text = text == null ? "" : text;
            searchResults = searchResults == null ? List.of() : List.copyOf(searchResults);
            toolEvents = toolEvents == null ? Map.of() : Map.copyOf(toolEvents);
        }
    }
}
