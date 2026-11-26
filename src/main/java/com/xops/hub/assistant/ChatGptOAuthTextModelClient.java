package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.xops.hub.assistant.CommandCodeTextModelClient.ModelCallException;

/**
 * TextModelClient adapter that routes all content-assistant stages (CLASSIFY, SCORE, STYLE,
 * RESEARCH, DRAFT, OPTIMIZE, VERIFY) through ChatGPT third-party OAuth subscription login
 * (official {@code codex} CLI or {@code pi --provider openai-codex}), never paid API billing.
 */
public final class ChatGptOAuthTextModelClient implements TextModelClient {
    public static final String SERVICE_NAME = "chatgpt";
    public static final String CREDENTIAL_REF = "CHATGPT_OAUTH";
    public static final String BASE_URL = "https://chatgpt.com/backend-api";
    public static final String ASTRA_6_2_MODEL = "gpt-6.2-astra";
    public static final String DEFAULT_REASONING_EFFORT = "low";
    public static final String DEFAULT_MODEL_SPEC = ASTRA_6_2_MODEL + " " + DEFAULT_REASONING_EFFORT;
    public static final String DEFAULT_MODEL_ALIAS = "chatgpt-default";
    private static final Set<String> SUPPORTED_EFFORTS = Set.of(
            "minimal", "low", "medium", "high", "xhigh", "ultra", "max"
    );
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>\\])]+");

    public record ModelSpec(String modelId, String reasoningEffort) { }

    private final ObjectMapper mapper;
    private final PiAgentViralJudge oauthBridge;

    public ChatGptOAuthTextModelClient(ObjectMapper mapper, Environment environment) {
        this(mapper, new PiAgentViralJudge(mapper, environment));
    }

    public ChatGptOAuthTextModelClient(ObjectMapper mapper, PiAgentViralJudge oauthBridge) {
        this.mapper = mapper;
        this.oauthBridge = oauthBridge;
    }

    public static boolean isBinaryInstalled(Environment environment) {
        PiAgentViralJudge probe = new PiAgentViralJudge(new ObjectMapper(), environment);
        return !probe.resolveCodexBinary().isBlank() || !probe.resolvePiBinary().isBlank();
    }

    public static boolean isOAuthReady(Environment environment) {
        PiAgentViralJudge probe = new PiAgentViralJudge(new ObjectMapper(), environment);
        return probe.checkAuth(false).path("ready").asBoolean(false);
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
        if (!SERVICE_NAME.equalsIgnoreCase(binding.connection().serviceName())) {
            throw new IllegalArgumentException("ChatGptOAuthTextModelClient requires chatgpt service connection");
        }
        ObjectNode auth = oauthBridge.checkAuth(true);
        if (!auth.path("ready").asBoolean(false)) {
            throw new ModelCallException(
                    auth.path("message").asText("尚未完成 ChatGPT OAuth 第三方登录，请执行 codex login --device-auth"),
                    false, false, null);
        }
        String activeBackend = auth.path("activeBackend").asText("CODEX_CLI_OAUTH");
        String requestId = "chatgpt-oauth-" + UUID.randomUUID();
        ObjectNode snapshot = mapper.createObjectNode()
                .put("service", SERVICE_NAME)
                .put("authMode", "CHATGPT_OAUTH_ONLY")
                .put("backend", activeBackend)
                .put("model", binding.modelId())
                .put("protocol", binding.protocol().name())
                .put("connectionVersion", binding.connection().version())
                .put("bindingVersion", binding.version());

        Duration timeout = binding.timeout() != null ? binding.timeout() : Duration.ofSeconds(120);
        ModelSpec modelSpec = parseModelSpec(binding.modelId());
        snapshot.put("resolvedModel", effectiveCodexModelId(modelSpec))
                .put("reasoningEffort", modelSpec.reasoningEffort());
        long deadlineMs = System.currentTimeMillis() + timeout.toMillis();

        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                if (attempt > 0) {
                    long leftMs = deadlineMs - System.currentTimeMillis();
                    if (leftMs < 3_000L) {
                        break;
                    }
                    Thread.sleep(Math.min(500L * attempt, leftMs / 4L));
                }
                Duration attemptTimeout = Duration.ofMillis(Math.max(2_000L, deadlineMs - System.currentTimeMillis()));
                if (request.webSearch()) {
                    return executeWebSearch(activeBackend, modelSpec, request, attemptTimeout, requestId, snapshot);
                }
                String rawText = "PI_AGENT_OAUTH".equals(activeBackend)
                        ? runPiCompletion(modelSpec, request, attemptTimeout, requestId)
                        : runCodexCompletion(modelSpec, request, attemptTimeout, requestId);
                String finalText = request.structuredJson() ? extractJsonObjectString(rawText) : rawText.strip();
                return new ModelResult(finalText, List.of(), new Usage(null, null), requestId, snapshot, Map.of());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ModelCallException("ChatGPT OAuth 调用已中断", true, true, requestId);
            } catch (ModelCallException mce) {
                if (!mce.retryable() || attempt >= 2 || (deadlineMs - System.currentTimeMillis()) < 3_000L) {
                    throw mce;
                }
            }
        }
        throw new ModelCallException("ChatGPT OAuth 调用重试未成功", true, false, requestId);
    }

    private ModelResult executeWebSearch(
            String activeBackend,
            ModelSpec modelSpec,
            TextRequest request,
            Duration timeout,
            String requestId,
            ObjectNode snapshot
    ) {
        String codexBin = oauthBridge.resolveCodexBinary();
        if (codexBin.isBlank()) {
            throw new ModelCallException("联网检索需要安装官方 codex CLI 以启用 Responses web_search", false, false, requestId);
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-chatgpt-search-");
            Path outFile = workDir.resolve("search-result.json");
            List<String> cmd = new ArrayList<>(List.of(
                    codexBin, "--search", "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "--json",
                    "-s", "read-only",
                    "-c", "model_reasoning_effort=\"" + modelSpec.reasoningEffort() + "\"",
                    "-m", effectiveCodexModelId(modelSpec),
                    "-C", workDir.toString(),
                    "-o", outFile.toString(),
                    "-"
            ));

            String searchInstruction = request.systemText() + "\n"
                    + "You MUST invoke the native web_search tool to find authoritative public web pages, and do NOT execute any shell commands.\n"
                    + "Return ONLY one JSON object with shape: "
                    + "{\"summary\":\"concise summary\",\"sources\":[{\"url\":\"https://...\",\"title\":\"page title\",\"snippet\":\"key excerpt\",\"publishedAt\":\"\"}]}\n\n"
                    + (request.searchQuery().isBlank() ? "" : "Preferred search query: " + request.searchQuery() + "\n")
                    + request.userText();

            PiAgentViralJudge.CliResult res = oauthBridge.runCli(cmd, searchInstruction, timeout);
            String lastMessage = Files.isRegularFile(outFile)
                    ? Files.readString(outFile, StandardCharsets.UTF_8).strip()
                    : "";
            if (lastMessage.isBlank() && res.exitCode() != 0) {
                String err = !res.stderr().isBlank() ? res.stderr() : res.stdout();
                throw new ModelCallException("ChatGPT OAuth 联网搜索失败：" + truncate(err, 220), true, false, requestId);
            }

            Map<String, SearchResult> discovered = new LinkedHashMap<>();
            Map<String, JsonNode> toolEvents = new LinkedHashMap<>();
            parseSearchJsonlEvents(res.stdout(), request.searchQuery(), discovered, toolEvents);

            String cleanJson = extractJsonObjectString(lastMessage.isBlank() ? res.stdout() : lastMessage);
            parseSearchJsonPayload(cleanJson, request.searchQuery(), discovered, toolEvents);

            if (cleanJson.isBlank()) {
                ObjectNode fallback = mapper.createObjectNode().put("summary", "completed");
                cleanJson = fallback.toString();
            }
            return new ModelResult(cleanJson, List.copyOf(discovered.values()), new Usage(null, null),
                    requestId, snapshot, toolEvents);
        } catch (ModelCallException mce) {
            throw mce;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("ChatGPT OAuth 联网搜索被中断", true, true, requestId);
        } catch (IOException ioe) {
            throw new ModelCallException("ChatGPT OAuth 联网搜索进程异常：" + ioe.getMessage(), true, true, requestId);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private void parseSearchJsonlEvents(
            String stdout,
            String defaultQuery,
            Map<String, SearchResult> discovered,
            Map<String, JsonNode> toolEvents
    ) {
        if (stdout == null || stdout.isBlank()) return;
        int eventSeq = 1;
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{")) continue;
            try {
                JsonNode ev = mapper.readTree(trimmed);
                String rawLine = ev.toString();
                if (rawLine.contains("web_search")) {
                    toolEvents.put("web_search_" + (eventSeq++), ev);
                }
                collectUrlsFromNode(ev, defaultQuery, discovered);
            } catch (Exception ignored) { }
        }
    }

    private void parseSearchJsonPayload(
            String cleanJson,
            String defaultQuery,
            Map<String, SearchResult> discovered,
            Map<String, JsonNode> toolEvents
    ) {
        if (cleanJson == null || cleanJson.isBlank()) return;
        try {
            JsonNode root = mapper.readTree(cleanJson);
            if (root == null || !root.isObject()) return;
            for (JsonNode src : root.path("sources")) {
                addCandidateUrl(
                        src.path("url").asText(""),
                        src.path("title").asText(""),
                        src.path("snippet").asText(""),
                        src.path("publishedAt").asText(""),
                        defaultQuery,
                        discovered
                );
            }
            if (discovered.isEmpty()) {
                collectUrlsFromText(cleanJson, defaultQuery, discovered);
            }
            if (!discovered.isEmpty() && toolEvents.isEmpty()) {
                toolEvents.put("web_search_1", mapper.createObjectNode()
                        .put("type", "web_search_call")
                        .put("results", discovered.size()));
            }
        } catch (Exception ignored) { }
    }

    private void collectUrlsFromNode(JsonNode node, String query, Map<String, SearchResult> discovered) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has("url") && node.path("url").isTextual()) {
                addCandidateUrl(node.path("url").asText(""), node.path("title").asText(""),
                        node.path("snippet").asText(""), node.path("published_at").asText(""), query, discovered);
            }
            node.fields().forEachRemaining(entry -> collectUrlsFromNode(entry.getValue(), query, discovered));
        } else if (node.isArray()) {
            node.forEach(item -> collectUrlsFromNode(item, query, discovered));
        }
    }

    private void collectUrlsFromText(String text, String query, Map<String, SearchResult> discovered) {
        Matcher m = URL_PATTERN.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && seen.size() < 12) {
            String raw = m.group().replaceAll("[.,;!?，。！？）)}\\]\\\\]+$", "");
            if (seen.add(raw)) addCandidateUrl(raw, "", "", "", query, discovered);
        }
    }

    private static void addCandidateUrl(
            String rawUrl,
            String title,
            String snippet,
            String publishedAt,
            String query,
            Map<String, SearchResult> discovered
    ) {
        String normalized = AssistantEvidence.normalizedUrl(rawUrl);
        if (normalized.isBlank() || normalized.contains("chatgpt.com") || normalized.contains("openai.com/auth")) return;
        try {
            URI uri = URI.create(normalized);
            discovered.putIfAbsent(normalized, new SearchResult(
                    query == null ? "" : query,
                    uri,
                    title == null ? "" : title,
                    snippet == null ? "" : snippet,
                    publishedAt == null ? "" : publishedAt
            ));
        } catch (IllegalArgumentException ignored) { }
    }

    private String runCodexCompletion(ModelSpec modelSpec, TextRequest request, Duration timeout, String requestId) {
        String codexBin = oauthBridge.resolveCodexBinary();
        if (codexBin.isBlank()) {
            throw new ModelCallException("未检测到官方 codex CLI", false, false, requestId);
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-chatgpt-text-");
            Path outFile = workDir.resolve("response.txt");
            List<String> cmd = new ArrayList<>(List.of(
                    codexBin, "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "-s", "read-only",
                    "-c", "model_reasoning_effort=\"" + modelSpec.reasoningEffort() + "\"",
                    "-m", effectiveCodexModelId(modelSpec),
                    "-C", workDir.toString(),
                    "-o", outFile.toString(),
                    "-"
            ));

            String fullPrompt = (request.systemText().isBlank() ? "" : request.systemText() + "\n")
                    + "Do NOT run any shell commands or local tools. Respond directly with the requested output only.\n\n"
                    + request.userText();
            if (request.structuredJson()) {
                fullPrompt = fullPrompt
                        + "\n\nIMPORTANT: Output ONLY one raw JSON object starting with { and ending with }, with no markdown code fences or commentary.";
            }
            PiAgentViralJudge.CliResult res = oauthBridge.runCli(cmd, fullPrompt, timeout);
            if (Files.isRegularFile(outFile)) {
                String content = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!content.isBlank()) return content;
            }
            if (res.exitCode() != 0) {
                String err = !res.stderr().isBlank() ? res.stderr() : res.stdout();
                throw new ModelCallException("ChatGPT OAuth 调用异常：" + truncate(err, 220), true, false, requestId);
            }
            return res.stdout();
        } catch (ModelCallException mce) {
            throw mce;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("ChatGPT OAuth 调用被中断", true, true, requestId);
        } catch (IOException ioe) {
            throw new ModelCallException("ChatGPT OAuth 进程调用失败：" + ioe.getMessage(), true, true, requestId);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String runPiCompletion(ModelSpec modelSpec, TextRequest request, Duration timeout, String requestId) {
        String piBin = oauthBridge.resolvePiBinary();
        if (piBin.isBlank()) {
            throw new ModelCallException("未检测到 PI Agent (pi) CLI", false, false, requestId);
        }
        List<String> cmd = new ArrayList<>(List.of(
                piBin, "-p",
                "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                "--provider", PiAgentViralJudge.DEFAULT_PI_PROVIDER,
                "--thinking", modelSpec.reasoningEffort()
        ));
        if (!request.systemText().isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(request.systemText());
        }
        if (!modelSpec.modelId().isBlank()) {
            cmd.add("--model");
            cmd.add(modelSpec.modelId());
        }
        cmd.add("--");
        cmd.add(request.userText());
        try {
            PiAgentViralJudge.CliResult res = oauthBridge.runCli(cmd, "", timeout);
            if (res.exitCode() != 0) {
                String err = !res.stderr().isBlank() ? res.stderr() : res.stdout();
                throw new ModelCallException("PI Agent (ChatGPT OAuth) 调用异常：" + truncate(err, 220), true, false, requestId);
            }
            return res.stdout();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("PI Agent 调用被中断", true, true, requestId);
        } catch (IOException ioe) {
            throw new ModelCallException("PI Agent 进程异常：" + ioe.getMessage(), true, true, requestId);
        }
    }

    public static ModelSpec parseModelSpec(String rawModelId) {
        if (rawModelId == null) return new ModelSpec("", DEFAULT_REASONING_EFFORT);
        String trimmed = rawModelId.strip();
        if (trimmed.isEmpty()) return new ModelSpec("", DEFAULT_REASONING_EFFORT);

        String effort = DEFAULT_REASONING_EFFORT;
        String modelPart = trimmed;
        Matcher suffix = Pattern.compile("^(.*?)[\\s@:]+([A-Za-z]+)$").matcher(trimmed);
        if (suffix.matches()) {
            String candidateEffort = suffix.group(2).toLowerCase(Locale.ROOT);
            if (SUPPORTED_EFFORTS.contains(candidateEffort)) {
                modelPart = suffix.group(1).strip();
                effort = candidateEffort;
            }
        }

        if (modelPart.isEmpty()
                || DEFAULT_MODEL_ALIAS.equalsIgnoreCase(modelPart)
                || "auto".equalsIgnoreCase(modelPart)
                || "default".equalsIgnoreCase(modelPart)) {
            return new ModelSpec("", effort);
        }

        String normalizedKey = modelPart.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        String resolvedModel = switch (normalizedKey) {
            case "astra6.2", "astra-6.2", "gpt-6.2-astra", "gpt6.2-astra" -> ASTRA_6_2_MODEL;
            case "sol6.2", "sol-6.2", "gpt-6.2-sol", "gpt6.2-sol" -> "gpt-6.2-sol";
            default -> modelPart;
        };
        return new ModelSpec(resolvedModel, effort);
    }

    public static String effectiveCodexModelId(ModelSpec spec) {
        if (spec == null || spec.modelId() == null || spec.modelId().isBlank()) {
            return ASTRA_6_2_MODEL;
        }
        return spec.modelId();
    }

    static String resolveExplicitModel(String modelId) {
        return parseModelSpec(modelId).modelId();
    }

    String extractJsonObjectString(String raw) {
        return extractJsonObjectString(raw, oauthBridge);
    }

    static String extractJsonObjectString(String raw, PiAgentViralJudge bridge) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = stripBomAndCodeFence(raw);
        if (bridge != null) {
            JsonNode parsed = bridge.parseFirstJsonObject(cleaned);
            if (parsed != null && parsed.isObject()) {
                return parsed.toString();
            }
        }
        return cleaned;
    }

    private static String stripBomAndCodeFence(String raw) {
        String s = stripBom(raw.strip());
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                String body = s.substring(firstNewline + 1);
                int lastFence = body.lastIndexOf("```");
                if (lastFence >= 0) {
                    s = stripBom(body.substring(0, lastFence).strip());
                }
            }
        }
        return s;
    }

    private static String stripBom(String value) {
        String s = value;
        while (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1).strip();
        }
        while (!s.isEmpty() && s.charAt(s.length() - 1) == '\uFEFF') {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
