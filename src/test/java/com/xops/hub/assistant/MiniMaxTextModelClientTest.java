package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiniMaxTextModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void routesTextAndSearchToTheirOfficialEndpoints() throws Exception {
        var text = mapper.readTree("""
                {"id":"text-1","choices":[{"message":{"content":"{}","reasoning_content":"private"}}],
                 "usage":{"prompt_tokens":8,"completion_tokens":4}}
                """);
        var search = mapper.readTree("""
                {"base_resp":{"status_code":0},
                 "organic":[{"link":"https://example.org/paper","title":"Paper"},
                            {"link":"https://example.org/paper"},{"link":"file:///private"}]}
                """);
        var calls = new AtomicInteger();
        var client = new MiniMaxTextModelClient(mapper, (uri, headers, body, timeout) -> {
            assertThat(headers).containsEntry("Authorization", "Bearer test-key");
            if (calls.incrementAndGet() == 1) {
                assertThat(uri).isEqualTo(URI.create("https://api.minimax.cn/v1/chat/completions"));
                assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("business text");
                return new CommandCodeTextModelClient.TransportResponse(200, text, Map.of());
            }
            assertThat(uri).isEqualTo(URI.create("https://api.minimaxi.com/v1/coding_plan/search"));
            assertThat(body).isEqualTo(mapper.createObjectNode().put("q", "short search"));
            return new CommandCodeTextModelClient.TransportResponse(200, search, Map.of("x-request-id", "search-1"));
        }, ref -> {
            assertThat(ref).isEqualTo("MINIMAX_API_KEY");
            return "test-key";
        });

        var binding = binding("https://api.minimax.cn/v1", "MINIMAX_API_KEY");
        var answer = client.complete(binding, new TextModelClient.TextRequest("system", "business text", true, false));
        assertThat(answer.text()).isEqualTo("{}");
        assertThat(answer.requestId()).isEqualTo("text-1");
        assertThat(answer.modelSnapshot().toString()).doesNotContain("test-key", "private");
        var results = client.complete(binding, new TextModelClient.TextRequest("system", "business text", true, true,
                "short search"));
        assertThat(results.requestId()).isEqualTo("search-1");
        assertThat(results.searchResults()).singleElement().satisfies(result ->
                assertThat(result.url()).isEqualTo(URI.create("https://example.org/paper")));
        assertThat(results.toolEvents()).containsKey("search-0");
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsNonMiniMaxEndpointsBeforeResolvingCredentials() {
        var client = new MiniMaxTextModelClient(mapper, (uri, headers, body, timeout) -> {
            throw new AssertionError("request must not be sent");
        }, ref -> {
            throw new AssertionError("credential must not be resolved");
        });
        var request = new TextModelClient.TextRequest("", "text", false, false);
        assertThatThrownBy(() -> client.complete(binding("https://api.commandcode.ai/v1", "MINIMAX_API_KEY"), request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.complete(binding("https://api.minimax.cn/v1", "COMMANDCODE_API_KEY"), request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendsMessagesWithTheMatchingDialectAndReturnsOnlyVisibleText() throws Exception {
        var reply = mapper.readTree("""
                {"id":"msg-1","content":[{"type":"thinking","thinking":"private"},
                   {"type":"text","text":"{}"}],"usage":{"input_tokens":6,"output_tokens":4}}
                """);
        var client = new MiniMaxTextModelClient(mapper, (uri, headers, body, timeout) -> {
            assertThat(uri.getPath()).isEqualTo("/anthropic/v1/messages");
            assertThat(headers).containsEntry("anthropic-version","2023-06-01");
            assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
            assertThat(body.path("system").asText()).isEqualTo("system");
            return new CommandCodeTextModelClient.TransportResponse(200,reply,Map.of());
        }, ref -> "test-key");
        var chat = binding("https://api.minimax.cn/v1", "MINIMAX_API_KEY");
        var messages = new TextModelClient.ModelBinding(chat.connection(),chat.modelId(),TextModelClient.Protocol.MESSAGES,
                chat.contextTokens(),chat.timeout(),chat.maxOutputTokens(),chat.version());
        var result = client.complete(messages,new TextModelClient.TextRequest("system","text",true,false));
        assertThat(result.text()).isEqualTo("{}");
        assertThat(result.usage().outputTokens()).isEqualTo(4L);
    }

    @Test
    void switchingProvidersRestoresTheirOwnBindingsAndKeepsCollectionSettings(@TempDir Path db) {
        var store = new AssistantStore(db,mapper);
        store.tx(tx->tx.put("meta","settings",AssistantWorkflow.settings(tx).put("model","command-model")
                .put("contextTokens",16_000).put("collectionEnabled",false)));
        var mini = new MockEnvironment().withProperty(AssistantConnection.PROVIDER_ENV,"minimax")
                .withProperty(AssistantConnection.MINIMAX_KEY_ENV,"fake-minimax");
        var command = new MockEnvironment().withProperty(AssistantIntelligence.API_KEY_ENV,"fake-command");
        new AssistantConnection(store,mini).synchronize();
        var selected=store.tx(AssistantWorkflow::settings);
        assertThat(selected.path("model").asText()).isEqualTo("MiniMax-M3.1-Flash");
        assertThat(selected.path("contextTokens").asInt()).isEqualTo(32_000);
        assertThat(selected.path("collectionEnabled").asBoolean()).isFalse();
        store.tx(tx->tx.put("meta","settings",AssistantWorkflow.settings(tx).put("researchProtocol","MESSAGES")));
        new AssistantConnection(store,command).synchronize();
        assertThat(store.tx(AssistantWorkflow::settings).path("model").asText()).isEqualTo("command-model");
        new AssistantConnection(store,mini).synchronize();
        assertThat(store.tx(AssistantWorkflow::settings).path("researchProtocol").asText()).isEqualTo("MESSAGES");
    }

    private static TextModelClient.ModelBinding binding(String uri, String credential) {
        var connection = new TextModelClient.ServiceConnection("minimax", URI.create(uri), credential, 1);
        return new TextModelClient.ModelBinding(connection, "MiniMax-M3.1-Flash",
                TextModelClient.Protocol.CHAT_COMPLETIONS, 32_000, Duration.ofSeconds(30), 1_000, "test");
    }
}
