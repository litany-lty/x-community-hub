package com.xops.hub.assistant;

/** Supplier-neutral web search contract used by the research stage. */
public interface SearchProvider {
    default boolean requiresExplicitSearchQuery(TextModelClient.ModelBinding binding) {
        return false;
    }

    TextModelClient.ModelResult search(TextModelClient.ModelBinding binding, TextModelClient.TextRequest request);

    static SearchProvider fromModelClient(TextModelClient client) {
        return new SearchProvider() {
            @Override
            public boolean requiresExplicitSearchQuery(TextModelClient.ModelBinding binding) {
                return client.requiresExplicitSearchQuery(binding);
            }

            @Override
            public TextModelClient.ModelResult search(TextModelClient.ModelBinding binding, TextModelClient.TextRequest request) {
                return client.complete(binding, request);
            }
        };
    }
}
