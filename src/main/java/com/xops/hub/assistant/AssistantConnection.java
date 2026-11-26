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
    static final String DEEPSEEK_KEY_ENV = "DEEPSEEK_API_KEY";
    static final String DEEPSEEK_BASE_ENV = "DEEPSEEK_BASE_URL";
    static final String GOOGLE_KEY_ENV = "GEMINI_API_KEY";
    static final String GOOGLE_ALT_KEY_ENV = "GOOGLE_API_KEY";
    static final String GOOGLE_BASE_ENV = "GEMINI_BASE_URL";
    static final String OPENAI_KEY_ENV = "OPENAI_API_KEY";
    static final String OPENAI_BASE_ENV = "OPENAI_BASE_URL";

    static final String DEFAULT_COLLECTION_PROVIDER = "minimax";
    static final String DEFAULT_COLLECTION_MODEL = "MiniMax-M2.7";
    static final String DEFAULT_SEARCH_PROVIDER = "deepseek";
    static final String DEFAULT_SEARCH_MODEL = DeepSeekTextModelClient.DEFAULT_MODEL;
    static final String DEFAULT_GENERATION_PROVIDER = "chatgpt";
    static final String DEFAULT_GENERATION_MODEL = ChatGptOAuthTextModelClient.ASTRA_6_2_MODEL + " medium";
    static final String DEFAULT_REPOST_PROVIDER = "chatgpt";
    static final String DEFAULT_REPOST_MODEL = ChatGptOAuthTextModelClient.ASTRA_6_2_MODEL + " medium";

    private static final List<String> BINDING_FIELDS = List.of("model", "protocol", "contextTokens",
            "timeoutSeconds", "maxOutputTokens", "researchModel", "researchProtocol", "researchContextTokens",
            "researchTimeoutSeconds", "researchMaxOutputTokens", "bindings");
    private final AssistantStore store;
    private final Environment environment;

    AssistantConnection(AssistantStore store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    @PostConstruct
    void synchronize() {
        String explicit = environment.getProperty(PROVIDER_ENV, "").strip().toLowerCase(Locale.ROOT);
        String defaultProvider = provider(environment, store);
        boolean chatgptReady = isProviderConfigured(environment, store, "chatgpt");
        var connection = object()
                .put("chatgptOAuthReady", chatgptReady)
                .put("openaiEndpoint", endpoint(environment, "openai"))
                .put("openaiCredential", resolveSecret(store, environment, OPENAI_KEY_ENV))
                .put("googleEndpoint", endpoint(environment, "google"))
                .put("googleCredential", resolveSecret(store, environment, GOOGLE_KEY_ENV))
                .put("deepseekEndpoint", endpoint(environment, "deepseek"))
                .put("deepseekCredential", resolveSecret(store, environment, DEEPSEEK_KEY_ENV))
                .put("minimaxEndpoint", endpoint(environment, "minimax"))
                .put("minimaxCredential", resolveSecret(store, environment, MINIMAX_KEY_ENV))
                .put("commandEndpoint", endpoint(environment, "commandcode"))
                .put("commandCredential", resolveSecret(store, environment, AssistantIntelligence.API_KEY_ENV));
        if (!explicit.isEmpty()) connection.put("provider", defaultProvider);
        String fingerprint = AssistantWorkflow.digest(connection);
        store.tx(tx -> {
            var previous = tx.get("server", "connection");
            var settings = AssistantWorkflow.settings(tx);
            boolean shouldUpgradeToChatGpt = explicit.isEmpty() && chatgptReady && "chatgpt".equals(defaultProvider)
                    && !"chatgpt".equals(settings.path("provider").asText(""))
                    && !settings.path("providerManuallySelected").asBoolean(false);
            if (previous != null && fingerprint.equals(previous.path("fingerprint").asText()) && !shouldUpgradeToChatGpt) {
                if (settings.path("connectionVersion").asInt() != previous.path("version").asInt()) {
                    settings.put("connectionVersion", previous.path("version").asInt());
                    tx.put("meta", "settings", settings);
                }
                return null;
            }
            var next = tx.put("server", "connection", object().put("fingerprint", fingerprint));
            String priorProvider = settings.path("provider").asText("");
            String targetProvider;
            if (!explicit.isEmpty()) {
                targetProvider = defaultProvider;
            } else if (chatgptReady && "chatgpt".equals(defaultProvider) && !settings.path("providerManuallySelected").asBoolean(false)) {
                targetProvider = "chatgpt";
            } else if (isSupportedProvider(priorProvider) && isProviderConfigured(environment, tx, priorProvider)) {
                targetProvider = normalizeProvider(priorProvider);
            } else {
                targetProvider = defaultProvider;
            }
            switchProvider(tx, settings, targetProvider, environment);
            settings.put("connectionVersion", next.path("version").asInt());
            tx.put("meta", "settings", settings);
            return null;
        });
    }

    static int recordServerConnectionChange(AssistantStore.Tx tx, Environment environment) {
        String explicit = environment == null ? "" : environment.getProperty(PROVIDER_ENV, "").strip().toLowerCase(Locale.ROOT);
        boolean chatgptReady = isProviderConfigured(environment, tx, "chatgpt");
        var connection = object()
                .put("chatgptOAuthReady", chatgptReady)
                .put("openaiEndpoint", endpoint(environment, "openai"))
                .put("openaiCredential", resolveSecretFromTx(tx, environment, OPENAI_KEY_ENV))
                .put("googleEndpoint", endpoint(environment, "google"))
                .put("googleCredential", resolveSecretFromTx(tx, environment, GOOGLE_KEY_ENV))
                .put("deepseekEndpoint", endpoint(environment, "deepseek"))
                .put("deepseekCredential", resolveSecretFromTx(tx, environment, DEEPSEEK_KEY_ENV))
                .put("minimaxEndpoint", endpoint(environment, "minimax"))
                .put("minimaxCredential", resolveSecretFromTx(tx, environment, MINIMAX_KEY_ENV))
                .put("commandEndpoint", endpoint(environment, "commandcode"))
                .put("commandCredential", resolveSecretFromTx(tx, environment, AssistantIntelligence.API_KEY_ENV));
        if (!explicit.isEmpty()) connection.put("provider", normalizeProvider(explicit));
        String fingerprint = AssistantWorkflow.digest(connection);
        var next = tx.put("server", "connection", object().put("fingerprint", fingerprint));
        return next.path("version").asInt();
    }

    static void switchProvider(AssistantStore.Tx tx, ObjectNode settings, String targetProvider, Environment environment) {
        String target = normalizeProvider(targetProvider);
        String priorProvider = settings.path("provider").asText("commandcode");
        if (!priorProvider.equals(target)) {
            if (isSupportedProvider(priorProvider)) {
                tx.put("provider_binding", normalizeProvider(priorProvider), bindingOf(settings));
            }
            var restored = tx.get("provider_binding", target);
            settings.remove(BINDING_FIELDS);
            settings.setAll(restored == null ? defaultBinding(target) : bindingOf(restored));
        }
        settings.put("provider", target);
        if (environment != null) {
            settings.put("credentialConfigured", isProviderConfigured(environment, tx, target));
        }
    }

    private static ObjectNode bindingOf(ObjectNode settings) {
        var binding = object();
        for (String field : BINDING_FIELDS) {
            if (settings.has(field)) binding.set(field, settings.get(field));
        }
        return binding;
    }

    static ObjectNode defaultBinding(String provider) {
        return switch (normalizeProvider(provider)) {
            case "chatgpt" -> object()
                    .put("model", ChatGptOAuthTextModelClient.DEFAULT_MODEL_SPEC)
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 200_000)
                    .put("researchModel", ChatGptOAuthTextModelClient.DEFAULT_MODEL_SPEC)
                    .put("researchProtocol", "RESPONSES")
                    .put("researchContextTokens", 200_000);
            case "openai" -> object()
                    .put("model", OpenAiTextModelClient.DEFAULT_MODEL)
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 200_000)
                    .put("researchModel", OpenAiTextModelClient.DEFAULT_MODEL)
                    .put("researchProtocol", "RESPONSES")
                    .put("researchContextTokens", 200_000);
            case "google" -> object()
                    .put("model", GeminiTextModelClient.DEFAULT_MODEL)
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 1_000_000)
                    .put("researchModel", GeminiTextModelClient.FAST_MODEL)
                    .put("researchProtocol", "RESPONSES")
                    .put("researchContextTokens", 1_000_000);
            case "deepseek" -> object()
                    .put("model", DeepSeekTextModelClient.DEFAULT_MODEL)
                    .put("protocol", "CHAT_COMPLETIONS")
                    .put("contextTokens", 128_000)
                    .put("researchModel", DeepSeekTextModelClient.DEFAULT_MODEL)
                    .put("researchProtocol", "CHAT_COMPLETIONS")
                    .put("researchContextTokens", 128_000);
            case "minimax" -> object()
                    .put("model", "MiniMax-M3.1-Flash")
                    .put("protocol", "CHAT_COMPLETIONS")
                    .put("contextTokens", 32_000)
                    .put("researchModel", "")
                    .put("researchProtocol", "CHAT_COMPLETIONS")
                    .put("researchContextTokens", 0);
            default -> object()
                    .put("model", "")
                    .put("protocol", "RESPONSES")
                    .put("contextTokens", 0)
                    .put("researchModel", "")
                    .put("researchProtocol", "RESPONSES")
                    .put("researchContextTokens", 0);
        };
    }

    static boolean isSupportedProvider(String raw) {
        if (raw == null) return false;
        String v = raw.strip().toLowerCase(Locale.ROOT);
        return SetOfProviders.SUPPORTED.contains(v);
    }

    private static final class SetOfProviders {
        static final java.util.Set<String> SUPPORTED = java.util.Set.of(
                "chatgpt", "chatgpt-oauth", "codex", "pi",
                "openai", "openai-api",
                "google", "gemini", "google-api", "gemini-api",
                "deepseek", "deepseek-api",
                "commandcode", "minimax"
        );
    }

    static String normalizeProvider(String raw) {
        String configured = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (configured) {
            case "chatgpt", "chatgpt-oauth", "codex", "pi" -> "chatgpt";
            case "openai", "openai-api" -> "openai";
            case "google", "gemini", "google-api", "gemini-api" -> "google";
            case "deepseek", "deepseek-api" -> "deepseek";
            case "commandcode", "minimax" -> configured;
            default -> throw new IllegalArgumentException("Unsupported content assistant model provider: " + raw);
        };
    }

    static String selectedProvider(ObjectNode settings, Environment environment) {
        String saved = settings == null ? "" : settings.path("provider").asText("");
        if (isSupportedProvider(saved)) return normalizeProvider(saved);
        return provider(environment, (AssistantStore) null);
    }

    static String provider(Environment environment) {
        return provider(environment, (AssistantStore) null);
    }

    static String provider(Environment environment, AssistantStore store) {
        String configured = environment == null ? "" : environment.getProperty(PROVIDER_ENV, "").strip().toLowerCase(Locale.ROOT);
        if (!configured.isEmpty()) return normalizeProvider(configured);
        if (isProviderConfigured(environment, store, "commandcode")) {
            return "commandcode";
        }
        if (isProviderConfigured(environment, store, "chatgpt")) {
            return "chatgpt";
        }
        if (isProviderConfigured(environment, store, "openai")) {
            return "openai";
        }
        if (isProviderConfigured(environment, store, "google")) {
            return "google";
        }
        if (isProviderConfigured(environment, store, "minimax")) {
            return "minimax";
        }
        if (isProviderConfigured(environment, store, "deepseek")) {
            return "deepseek";
        }
        return "commandcode";
    }

    static String credentialName(Environment environment) {
        return credentialName(provider(environment));
    }

    static String credentialName(String provider) {
        return switch (normalizeProvider(provider)) {
            case "chatgpt" -> ChatGptOAuthTextModelClient.CREDENTIAL_REF;
            case "openai" -> OPENAI_KEY_ENV;
            case "google" -> GOOGLE_KEY_ENV;
            case "deepseek" -> DEEPSEEK_KEY_ENV;
            case "minimax" -> MINIMAX_KEY_ENV;
            default -> AssistantIntelligence.API_KEY_ENV;
        };
    }

    static String endpoint(Environment environment) {
        return endpoint(environment, provider(environment));
    }

    static String endpoint(Environment environment, String provider) {
        return switch (normalizeProvider(provider)) {
            case "chatgpt" -> ChatGptOAuthTextModelClient.BASE_URL;
            case "openai" -> environment == null
                    ? OpenAiTextModelClient.DEFAULT_BASE_URL
                    : nonBlank(environment.getProperty(OPENAI_BASE_ENV), OpenAiTextModelClient.DEFAULT_BASE_URL);
            case "google" -> environment == null
                    ? GeminiTextModelClient.DEFAULT_BASE_URL
                    : nonBlank(environment.getProperty(GOOGLE_BASE_ENV), GeminiTextModelClient.DEFAULT_BASE_URL);
            case "deepseek" -> environment == null
                    ? DeepSeekTextModelClient.DEFAULT_BASE_URL
                    : nonBlank(environment.getProperty(DEEPSEEK_BASE_ENV), DeepSeekTextModelClient.DEFAULT_BASE_URL);
            case "minimax" -> environment == null
                    ? "https://api.minimax.cn/v1"
                    : nonBlank(environment.getProperty(MINIMAX_BASE_ENV), "https://api.minimax.cn/v1");
            default -> environment == null
                    ? "https://api.commandcode.ai/provider/v1"
                    : nonBlank(environment.getProperty(AssistantIntelligence.BASE_URL_ENV), "https://api.commandcode.ai/provider/v1");
        };
    }

    static boolean isProviderConfigured(Environment environment, String provider) {
        return isProviderConfigured(environment, (AssistantStore) null, provider);
    }

    static boolean isProviderConfigured(Environment environment, AssistantStore store, String provider) {
        if (!isSupportedProvider(provider)) return false;
        String normalized = normalizeProvider(provider);
        if ("chatgpt".equals(normalized)) {
            if (environment != null && "true".equalsIgnoreCase(environment.getProperty("xops.test-disable-chatgpt-oauth", "false"))) {
                return false;
            }
            return ChatGptOAuthTextModelClient.isOAuthReady(environment);
        }
        String base = endpoint(environment, normalized);
        String key = resolveSecret(store, environment, credentialName(normalized));
        return base != null && !base.isBlank() && !key.isBlank();
    }

    static boolean isProviderConfigured(Environment environment, AssistantStore.Tx tx, String provider) {
        if (!isSupportedProvider(provider)) return false;
        String normalized = normalizeProvider(provider);
        if ("chatgpt".equals(normalized)) {
            if (environment != null && "true".equalsIgnoreCase(environment.getProperty("xops.test-disable-chatgpt-oauth", "false"))) {
                return false;
            }
            return ChatGptOAuthTextModelClient.isOAuthReady(environment);
        }
        String base = endpoint(environment, normalized);
        String key = resolveSecretFromTx(tx, environment, credentialName(normalized));
        return base != null && !base.isBlank() && !key.isBlank();
    }

    static String resolveSecret(AssistantStore store, Environment environment, String credentialRef) {
        if (store != null) {
            String fromStore = store.tx(tx -> resolveSecretFromTx(tx, null, credentialRef));
            if (fromStore != null && !fromStore.isBlank()) return fromStore;
        }
        return resolveSecretFromEnv(environment, credentialRef);
    }

    static String resolveSecretFromTx(AssistantStore.Tx tx, Environment environment, String credentialRef) {
        if (tx != null && credentialRef != null) {
            ObjectNode secrets = tx.get("secret", "api_keys");
            if (secrets != null) {
                String slot = secretFieldForRef(credentialRef);
                if (!slot.isEmpty()) {
                    String saved = secrets.path(slot).asText("").strip();
                    if (!saved.isBlank()) return saved;
                }
            }
        }
        return resolveSecretFromEnv(environment, credentialRef);
    }

    static String secretFieldForRef(String refOrProvider) {
        if (refOrProvider == null) return "";
        return switch (refOrProvider.strip()) {
            case OPENAI_KEY_ENV, "openai", "openai-api", "openaiApiKey" -> "openaiApiKey";
            case GOOGLE_KEY_ENV, GOOGLE_ALT_KEY_ENV, "google", "gemini", "googleApiKey" -> "googleApiKey";
            case DEEPSEEK_KEY_ENV, "deepseek", "deepseekApiKey" -> "deepseekApiKey";
            case MINIMAX_KEY_ENV, "minimax", "minimaxApiKey" -> "minimaxApiKey";
            case AssistantIntelligence.API_KEY_ENV, "commandcode", "commandcodeApiKey" -> "commandcodeApiKey";
            default -> "";
        };
    }

    private static String resolveSecretFromEnv(Environment environment, String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) return "";
        if (environment != null) {
            String direct = environment.getProperty(credentialRef, "").strip();
            if (!direct.isBlank()) return direct;
            if (OPENAI_KEY_ENV.equals(credentialRef)) {
                for (String alt : List.of("X_CONTENT_ASSISTANT_OPENAI_API_KEY", "xops.openai-api-key")) {
                    String val = environment.getProperty(alt, "").strip();
                    if (!val.isBlank()) return val;
                }
            } else if (GOOGLE_KEY_ENV.equals(credentialRef)) {
                for (String alt : List.of(GOOGLE_ALT_KEY_ENV, "X_CONTENT_ASSISTANT_GOOGLE_API_KEY", "xops.gemini-api-key")) {
                    String val = environment.getProperty(alt, "").strip();
                    if (!val.isBlank()) return val;
                }
            } else if (DEEPSEEK_KEY_ENV.equals(credentialRef)) {
                for (String alt : List.of("X_CONTENT_ASSISTANT_DEEPSEEK_API_KEY", "xops.deepseek-api-key")) {
                    String val = environment.getProperty(alt, "").strip();
                    if (!val.isBlank()) return val;
                }
            } else if (MINIMAX_KEY_ENV.equals(credentialRef)) {
                for (String alt : List.of("X_CONTENT_ASSISTANT_MINIMAX_API_KEY", "xops.minimax-api-key")) {
                    String val = environment.getProperty(alt, "").strip();
                    if (!val.isBlank()) return val;
                }
            }
        }
        String sysVal = System.getenv(credentialRef);
        if (sysVal != null && !sysVal.isBlank()) return sysVal.strip();
        if (GOOGLE_KEY_ENV.equals(credentialRef)) {
            String altSys = System.getenv(GOOGLE_ALT_KEY_ENV);
            if (altSys != null && !altSys.isBlank()) return altSys.strip();
        }
        return "";
    }

    static String maskSecret(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.strip();
        if (s.length() <= 8) return "••••";
        return s.substring(0, 4) + "••••" + s.substring(s.length() - 3);
    }

    static boolean supportsProtocol(String provider, String protocol) {
        String norm = isSupportedProvider(provider) ? normalizeProvider(provider) : provider;
        if ("minimax".equalsIgnoreCase(norm)) {
            return "CHAT_COMPLETIONS".equalsIgnoreCase(protocol) || "MESSAGES".equalsIgnoreCase(protocol);
        }
        if ("deepseek".equalsIgnoreCase(norm)) {
            return "CHAT_COMPLETIONS".equalsIgnoreCase(protocol) || "RESPONSES".equalsIgnoreCase(protocol);
        }
        if ("chatgpt".equalsIgnoreCase(norm) || "openai".equalsIgnoreCase(norm) || "google".equalsIgnoreCase(norm)) {
            return "RESPONSES".equalsIgnoreCase(protocol) || "CHAT_COMPLETIONS".equalsIgnoreCase(protocol);
        }
        return true;
    }

    static String defaultProtocol(String provider) {
        String norm = isSupportedProvider(provider) ? normalizeProvider(provider) : provider;
        if ("minimax".equalsIgnoreCase(norm) || "deepseek".equalsIgnoreCase(norm)) {
            return "CHAT_COMPLETIONS";
        }
        return "RESPONSES";
    }

    static int defaultContextTokens(String provider) {
        String norm = isSupportedProvider(provider) ? normalizeProvider(provider) : provider;
        return switch (norm.toLowerCase(Locale.ROOT)) {
            case "google" -> 1_000_000;
            case "chatgpt", "openai", "minimax" -> 200_000;
            default -> 128_000;
        };
    }

    static String defaultModelForProvider(String provider) {
        String norm = isSupportedProvider(provider) ? normalizeProvider(provider) : provider;
        return switch (norm.toLowerCase(Locale.ROOT)) {
            case "chatgpt" -> ChatGptOAuthTextModelClient.DEFAULT_MODEL_SPEC;
            case "openai" -> OpenAiTextModelClient.DEFAULT_MODEL;
            case "google" -> GeminiTextModelClient.DEFAULT_MODEL;
            case "deepseek" -> DeepSeekTextModelClient.DEFAULT_MODEL;
            case "minimax" -> DEFAULT_COLLECTION_MODEL;
            default -> "gpt-5.4";
        };
    }

    private static String nonBlank(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary.strip() : fallback;
    }
}
