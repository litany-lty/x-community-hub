package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import static com.xops.hub.assistant.AssistantStore.object;

/** Bind queued work to the service configuration actually available to this process. */
@Component
final class AssistantConnection {
    static final String PROVIDER_ENV = "X_CONTENT_ASSISTANT_MODEL_PROVIDER";
    static final String MINIMAX_KEY_ENV = "MINIMAX_API_KEY";
    static final String MINIMAX_BASE_ENV = "MINIMAX_BASE_URL";
    private static final List<String> BINDING_FIELDS = List.of("model", "protocol", "contextTokens",
            "timeoutSeconds", "maxOutputTokens", "researchModel", "researchProtocol", "researchContextTokens",
            "researchTimeoutSeconds", "researchMaxOutputTokens", "bindings");
    private final AssistantStore store;
    private final Environment environment;

    AssistantConnection(AssistantStore store, Environment environment) {
        this.store=store;
        this.environment=environment;
    }

    @PostConstruct void synchronize() {
        String provider=provider(environment);
        String endpoint=endpoint(environment);
        String credential=environment.getProperty(credentialName(environment),"");
        // Only the one-way comparison value is retained in the private settings index.
        var connection=object().put("endpoint",endpoint).put("credential",credential);
        if (!provider.equals("commandcode")) connection.put("provider",provider);
        String fingerprint=AssistantWorkflow.digest(connection);
        store.tx(tx->{
            var previous=tx.get("server","connection");
            if(previous!=null && fingerprint.equals(previous.path("fingerprint").asText())) return null;
            var next=tx.put("server","connection",object().put("fingerprint",fingerprint));
            var settings=AssistantWorkflow.settings(tx);
            String priorProvider=settings.path("provider").asText("commandcode");
            if (!priorProvider.equals(provider)) {
                tx.put("provider_binding",priorProvider,bindingOf(settings));
                var restored=tx.get("provider_binding",provider);
                settings.remove(BINDING_FIELDS);
                settings.setAll(restored==null?defaultBinding(provider):bindingOf(restored));
            }
            settings.put("connectionVersion",next.path("version").asInt()).put("credentialConfigured",!credential.isBlank())
                    .put("provider",provider);
            tx.put("meta","settings",settings);
            return null;
        });
    }

    private static ObjectNode bindingOf(ObjectNode settings) {
        var binding=object();
        for (String field:BINDING_FIELDS) {
            if (settings.has(field)) binding.set(field,settings.get(field));
        }
        return binding;
    }

    private static ObjectNode defaultBinding(String provider) {
        if (provider.equals("minimax")) {
            return object().put("model","MiniMax-M3.1-Flash").put("protocol","CHAT_COMPLETIONS")
                    .put("contextTokens",32_000).put("researchModel","").put("researchProtocol","CHAT_COMPLETIONS")
                    .put("researchContextTokens",0);
        }
        return object().put("model","").put("protocol","RESPONSES").put("contextTokens",0)
                .put("researchModel","").put("researchProtocol","RESPONSES").put("researchContextTokens",0);
    }

    static String provider(Environment environment) {
        String configured=environment.getProperty(PROVIDER_ENV,"commandcode").strip().toLowerCase(Locale.ROOT);
        return switch(configured) {
            case "commandcode", "minimax" -> configured;
            default -> throw new IllegalArgumentException("Unsupported content assistant model provider");
        };
    }

    static String credentialName(Environment environment) {
        return provider(environment).equals("minimax") ? MINIMAX_KEY_ENV : AssistantIntelligence.API_KEY_ENV;
    }

    static String endpoint(Environment environment) {
        if (provider(environment).equals("minimax"))
            return environment.getProperty(MINIMAX_BASE_ENV,"https://api.minimax.cn/v1");
        return environment.getProperty(AssistantIntelligence.BASE_URL_ENV,"https://api.commandcode.ai/provider/v1");
    }

    static boolean supportsProtocol(String provider, String protocol) {
        if ("minimax".equalsIgnoreCase(provider)) {
            return "CHAT_COMPLETIONS".equalsIgnoreCase(protocol) || "MESSAGES".equalsIgnoreCase(protocol);
        }
        return true;
    }

    static String defaultProtocol(String provider) {
        return "minimax".equalsIgnoreCase(provider) ? "CHAT_COMPLETIONS" : "RESPONSES";
    }
}
