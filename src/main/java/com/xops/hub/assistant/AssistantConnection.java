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
        String explicit=environment.getProperty(PROVIDER_ENV,"").strip().toLowerCase(Locale.ROOT);
        String defaultProvider=provider(environment);
        // Only the one-way comparison value is retained in the private settings index.
        var connection=object()
                .put("minimaxEndpoint",endpoint(environment,"minimax"))
                .put("minimaxCredential",environment.getProperty(MINIMAX_KEY_ENV,""))
                .put("commandEndpoint",endpoint(environment,"commandcode"))
                .put("commandCredential",environment.getProperty(AssistantIntelligence.API_KEY_ENV,""));
        if (!explicit.isEmpty()) connection.put("provider",defaultProvider);
        String fingerprint=AssistantWorkflow.digest(connection);
        store.tx(tx->{
            var previous=tx.get("server","connection");
            if(previous!=null && fingerprint.equals(previous.path("fingerprint").asText())) return null;
            var next=tx.put("server","connection",object().put("fingerprint",fingerprint));
            var settings=AssistantWorkflow.settings(tx);
            String priorProvider=settings.path("provider").asText("");
            String targetProvider;
            if (!explicit.isEmpty()) {
                targetProvider=defaultProvider;
            } else if (isSupportedProvider(priorProvider) && isProviderConfigured(environment,priorProvider)) {
                targetProvider=normalizeProvider(priorProvider);
            } else {
                targetProvider=defaultProvider;
            }
            switchProvider(tx,settings,targetProvider,environment);
            settings.put("connectionVersion",next.path("version").asInt());
            tx.put("meta","settings",settings);
            return null;
        });
    }

    static void switchProvider(AssistantStore.Tx tx, ObjectNode settings, String targetProvider, Environment environment) {
        String target=normalizeProvider(targetProvider);
        String priorProvider=settings.path("provider").asText("commandcode");
        if (!priorProvider.equals(target)) {
            tx.put("provider_binding",priorProvider,bindingOf(settings));
            var restored=tx.get("provider_binding",target);
            settings.remove(BINDING_FIELDS);
            settings.setAll(restored==null?defaultBinding(target):bindingOf(restored));
        }
        settings.put("provider",target);
        if (environment!=null) {
            settings.put("credentialConfigured",isProviderConfigured(environment,target));
        }
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

    static boolean isSupportedProvider(String raw) {
        if (raw == null) return false;
        String v = raw.strip().toLowerCase(Locale.ROOT);
        return "commandcode".equals(v) || "minimax".equals(v);
    }

    static String normalizeProvider(String raw) {
        String configured = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (configured) {
            case "commandcode", "minimax" -> configured;
            default -> throw new IllegalArgumentException("Unsupported content assistant model provider");
        };
    }

    static String selectedProvider(ObjectNode settings, Environment environment) {
        String saved = settings == null ? "" : settings.path("provider").asText("");
        if (isSupportedProvider(saved)) return normalizeProvider(saved);
        return provider(environment);
    }

    static String provider(Environment environment) {
        String configured=environment==null?"":environment.getProperty(PROVIDER_ENV,"").strip().toLowerCase(Locale.ROOT);
        if (!configured.isEmpty()) return normalizeProvider(configured);
        if (isProviderConfigured(environment,"minimax") && !isProviderConfigured(environment,"commandcode")) {
            return "minimax";
        }
        return "commandcode";
    }

    static String credentialName(Environment environment) {
        return credentialName(provider(environment));
    }

    static String credentialName(String provider) {
        return "minimax".equalsIgnoreCase(provider) ? MINIMAX_KEY_ENV : AssistantIntelligence.API_KEY_ENV;
    }

    static String endpoint(Environment environment) {
        return endpoint(environment, provider(environment));
    }

    static String endpoint(Environment environment, String provider) {
        if ("minimax".equalsIgnoreCase(provider))
            return environment == null ? "https://api.minimax.cn/v1"
                    : environment.getProperty(MINIMAX_BASE_ENV,"https://api.minimax.cn/v1");
        return environment == null ? "https://api.commandcode.ai/provider/v1"
                : environment.getProperty(AssistantIntelligence.BASE_URL_ENV,"https://api.commandcode.ai/provider/v1");
    }

    static boolean isProviderConfigured(Environment environment, String provider) {
        if (environment == null || !isSupportedProvider(provider)) return false;
        String base = endpoint(environment, provider);
        String key = environment.getProperty(credentialName(provider), "");
        return base != null && !base.isBlank() && !key.isBlank();
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
