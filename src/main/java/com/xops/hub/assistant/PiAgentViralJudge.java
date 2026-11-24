package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * High-heat / breaking-news viral judge powered strictly by ChatGPT OAuth third-party login
 * (never OpenAI API key billing), supporting both PI Agent ({@code pi --provider openai-codex})
 * and official OpenAI Codex CLI ({@code github.com/openai/codex}, {@code Logged in using ChatGPT}).
 */
@Service
public class PiAgentViralJudge {
    public static final String PI_CLI_PATH_ENV = "X_CONTENT_ASSISTANT_PI_CLI_PATH";
    public static final String CODEX_CLI_PATH_ENV = "X_CONTENT_ASSISTANT_CODEX_CLI_PATH";
    public static final String CODEX_HOME_ENV = "X_CONTENT_ASSISTANT_CODEX_HOME";
    public static final String BACKEND_ENV = "X_CONTENT_ASSISTANT_VIRAL_JUDGE_BACKEND";
    public static final String DEFAULT_PI_PROVIDER = "openai-codex";
    public static final String DEFAULT_PI_MODEL = "gpt-6.1-sol";
    private static final long AUTH_CACHE_TTL_MS = 30_000L;

    private static final List<String> PI_CANDIDATE_PATHS = List.of(
            "/opt/homebrew/bin/pi",
            "/usr/local/bin/pi"
    );
    private static final List<String> CODEX_CANDIDATE_PATHS = List.of(
            "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex",
            "/opt/homebrew/bin/codex",
            "/usr/local/bin/codex"
    );

    private static final String OUTPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": [
                "verdict",
                "viralScore",
                "eventMagnitude",
                "publicResonance",
                "credibility",
                "category",
                "summary",
                "reason",
                "commentText"
              ],
              "properties": {
                "verdict": { "type": "string", "enum": ["VIRAL_REPOST", "SKIP"] },
                "viralScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "eventMagnitude": { "type": "integer", "minimum": 0, "maximum": 100 },
                "publicResonance": { "type": "integer", "minimum": 0, "maximum": 100 },
                "credibility": { "type": "integer", "minimum": 0, "maximum": 100 },
                "category": {
                  "type": "string",
                  "enum": ["BREAKING_NEWS", "KEY_PERSONNEL", "MAJOR_LAUNCH", "INDUSTRY_SHIFT", "ROUTINE"]
                },
                "summary": { "type": "string" },
                "reason": { "type": "string" },
                "commentText": { "type": "string" }
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            你是资深科技、商业与公共事件突发主编，负责判断一条刚发布的 X 推文是否具备「大流量、高热度、全网破圈讨论」潜质，并在判定值得转发时撰写简短引用转发评述。
            严禁调用任何本地工具或命令，仅根据输入内容直接给出 JSON 判断。

            【必须判定为 VIRAL_REPOST（viralScore >= 82）的典型场景】
            1. 重大人事变动或核心领袖表态：如头部企业创始人/CEO复出、卸任、跨界履新（例如：黄仁勋宣布恢复总裁职务）。
            2. 重磅产品/模型/底层突破首发：头部AI实验室、科技巨头或知名开源项目的一手重磅发布、颠覆性基准或重大并购融资。
            3. 重大政策突变、行业震动事件或极具争议与传播张力的一手突发消息。

            【必须判定为 SKIP 的场景】
            - 日常寒暄、鸡汤感想、普通小版本修补、例行活动预告、营销抽奖、带货广告、无根据传闻或缺乏信息量的短句。

            【评述写作硬约束】
            - 使用自然克制的中文，直击事件核心看点或影响，长度 28–120 字，去除 AI 套话与感叹号堆砌。
            - 严禁编造原帖未出现的数字、时间或未证实事实。

            只输出一个合法 JSON 对象，不要使用 Markdown 代码块，字段严格如下：
            {"verdict":"VIRAL_REPOST或SKIP","viralScore":0到100整数,"eventMagnitude":0到100整数,"publicResonance":0到100整数,"credibility":0到100整数,"category":"BREAKING_NEWS|KEY_PERSONNEL|MAJOR_LAUNCH|INDUSTRY_SHIFT|ROUTINE","summary":"22字内事件概括","reason":"40字内中文判定理由","commentText":"引用转发附带中文短评"}
            """;

    @FunctionalInterface
    public interface CliRunner {
        CliResult run(List<String> command, String stdin, Duration timeout) throws IOException, InterruptedException;
    }

    public record CliResult(int exitCode, String stdout, String stderr) { }

    public record EvaluationInput(
            String authorUsername,
            String authorDisplayName,
            boolean authorVerified,
            String postText,
            String quotedText,
            String articleText,
            long ageMinutes,
            Long views,
            Long likes,
            Long reposts,
            Long replies,
            int minScore,
            String ruleHint,
            String ownerStyleProfile,
            String ownerPreferences,
            boolean generateComment
    ) { }

    public record ViralJudgment(
            boolean shouldRepost,
            int viralScore,
            int eventMagnitude,
            int publicResonance,
            int credibility,
            String category,
            String summary,
            String reason,
            String commentText
    ) { }

    private final ObjectMapper mapper;
    private final Environment environment;
    private final CliRunner runner;
    private volatile ObjectNode cachedAuthStatus;
    private volatile long cachedAuthAtMs;

    @Autowired
    public PiAgentViralJudge(ObjectMapper mapper, @Autowired(required = false) Environment environment) {
        this(mapper, environment, (cmd, in, timeout) -> defaultRunProcess(cmd, in, timeout, resolveCodexHome(environment)));
    }

    public PiAgentViralJudge(ObjectMapper mapper, Environment environment, CliRunner runner) {
        this.mapper = mapper;
        this.environment = environment;
        this.runner = runner;
    }

    public String resolvePiBinary() {
        return resolveBinary(PI_CLI_PATH_ENV, "pi", PI_CANDIDATE_PATHS);
    }

    public String resolveCodexBinary() {
        return resolveBinary(CODEX_CLI_PATH_ENV, "codex", CODEX_CANDIDATE_PATHS);
    }

    static String resolveCodexHome(Environment environment) {
        String explicit = environment == null ? "" : environment.getProperty(CODEX_HOME_ENV, "").strip();
        if (!explicit.isBlank()) return explicit;
        String envHome = System.getenv("CODEX_HOME");
        if (envHome != null && !envHome.isBlank()) return envHome.strip();
        Path workdirCodex = Path.of(".codex").toAbsolutePath().normalize();
        if (Files.isDirectory(workdirCodex)) return workdirCodex.toString();
        return "";
    }

    private String resolveBinary(String envKey, String name, List<String> fixedCandidates) {
        String explicit = environment == null ? "" : environment.getProperty(envKey, "").strip();
        if (!explicit.isBlank()) return explicit;
        Path workdirBin = Path.of(".local", "bin", name).toAbsolutePath().normalize();
        if (Files.isRegularFile(workdirBin) && Files.isExecutable(workdirBin)) return workdirBin.toString();
        for (String candidate : fixedCandidates) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        }
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            Path localBin = Path.of(home, ".local", "bin", name);
            if (Files.isRegularFile(localBin) && Files.isExecutable(localBin)) return localBin.toString();
            Path nvmBin = Path.of(home, ".nvm", "versions", "node", "v24.20.0", "bin", name);
            if (Files.isRegularFile(nvmBin) && Files.isExecutable(nvmBin)) return nvmBin.toString();
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) continue;
                Path bin = Path.of(dir, name);
                if (Files.isRegularFile(bin) && Files.isExecutable(bin)) return bin.toString();
            }
        }
        return "";
    }

    public ObjectNode checkAuth(boolean allowRefresh) {
        long now = System.currentTimeMillis();
        if (!allowRefresh && cachedAuthStatus != null && now - cachedAuthAtMs < AUTH_CACHE_TTL_MS) {
            return cachedAuthStatus.deepCopy();
        }
        String preferred = preferredBackend();
        String piBin = resolvePiBinary();
        String codexBin = resolveCodexBinary();

        boolean piReady = false;
        String piDetail = "";
        if (!piBin.isBlank() && !"codex".equals(preferred)) {
            List<String> cmd = new ArrayList<>(List.of(piBin, "auth", "check", "--provider", DEFAULT_PI_PROVIDER, "--json"));
            if (!allowRefresh) cmd.add("--no-refresh");
            try {
                CliResult res = runner.run(cmd, "", Duration.ofSeconds(allowRefresh ? 12 : 5));
                JsonNode parsed = parseFirstJsonObject(res.stdout());
                piReady = parsed != null && "ready".equalsIgnoreCase(parsed.path("status").asText(""));
                piDetail = parsed == null ? "" : parsed.path("reason").asText("");
            } catch (Exception ex) {
                piDetail = safeMessage(ex);
            }
        }

        boolean codexOAuthReady = false;
        boolean codexApiKeyOnly = false;
        String codexDetail = "";
        if (!codexBin.isBlank() && !"pi".equals(preferred)) {
            try {
                CliResult res = runner.run(List.of(codexBin, "login", "status"), "", Duration.ofSeconds(5));
                String combined = (res.stdout() + "\n" + res.stderr()).strip();
                String lower = combined.toLowerCase(Locale.ROOT);
                codexOAuthReady = res.exitCode() == 0 && lower.contains("logged in using chatgpt");
                codexApiKeyOnly = res.exitCode() == 0 && lower.contains("api key") && !codexOAuthReady;
                codexDetail = truncate(combined, 120);
            } catch (Exception ex) {
                codexDetail = safeMessage(ex);
            }
        }

        String activeBackend = "";
        if ("pi".equals(preferred)) {
            activeBackend = piReady ? "PI_AGENT_OAUTH" : "";
        } else if ("codex".equals(preferred)) {
            activeBackend = codexOAuthReady ? "CODEX_CLI_OAUTH" : "";
        } else {
            // Auto: prefer whichever ChatGPT OAuth login is ready right now (PI Agent first if ready, else official Codex CLI).
            activeBackend = piReady ? "PI_AGENT_OAUTH" : (codexOAuthReady ? "CODEX_CLI_OAUTH" : "");
        }

        boolean installed = !piBin.isBlank() || !codexBin.isBlank();
        boolean ready = !activeBackend.isBlank();
        ObjectNode out = mapper.createObjectNode()
                .put("installed", installed)
                .put("ready", ready)
                .put("authMode", "CHATGPT_OAUTH_ONLY")
                .put("apiBillingDisabled", true)
                .put("activeBackend", activeBackend)
                .put("piInstalled", !piBin.isBlank())
                .put("piReady", piReady)
                .put("codexInstalled", !codexBin.isBlank())
                .put("codexReady", codexOAuthReady)
                .put("loginCommand", "codex login --device-auth  或  pi（输入 /login 选 ChatGPT）");

        if (ready) {
            String label = "PI_AGENT_OAUTH".equals(activeBackend)
                    ? "PI Agent · ChatGPT OAuth 已登录（非 API 计费）"
                    : "Codex CLI · ChatGPT OAuth 已登录（非 API 计费）";
            out.put("status", "READY").put("message", label);
        } else if (!installed) {
            out.put("status", "NOT_INSTALLED")
                    .put("message", "未检测到 codex 或 pi 命令");
        } else if (codexApiKeyOnly) {
            out.put("status", "OAUTH_REQUIRED")
                    .put("message", "检测到 API Key 模式已拦截：请执行 codex login 切换为 ChatGPT 第三方登录（非 API 计费）");
        } else {
            out.put("status", "LOGIN_REQUIRED")
                    .put("reason", !piDetail.isBlank() ? piDetail : codexDetail)
                    .put("message", "尚未完成 ChatGPT OAuth 登录：请执行 codex login 或 pi（/login 选 ChatGPT）");
        }

        cacheAuth(out, now);
        return out.deepCopy();
    }

    public ViralJudgment evaluate(EvaluationInput input) {
        ObjectNode auth = checkAuth(true);
        if (!auth.path("ready").asBoolean(false)) {
            throw new IllegalStateException(auth.path("message").asText("尚未完成 ChatGPT OAuth 第三方登录"));
        }
        String activeBackend = auth.path("activeBackend").asText("");
        String rawOutput = "PI_AGENT_OAUTH".equals(activeBackend)
                ? runPiEvaluation(input)
                : runCodexEvaluation(input);

        JsonNode json = parseFirstJsonObject(rawOutput);
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("ChatGPT 热度判定未返回有效 JSON：" + truncate(rawOutput, 160));
        }

        String verdict = json.path("verdict").asText("SKIP").strip().toUpperCase(Locale.ROOT);
        int viralScore = clampScore(json.path("viralScore").asInt(0));
        int magnitude = clampScore(json.path("eventMagnitude").asInt(0));
        int resonance = clampScore(json.path("publicResonance").asInt(0));
        int credibility = clampScore(json.path("credibility").asInt(0));
        String category = truncate(json.path("category").asText("ROUTINE").strip(), 40);
        String summary = truncate(json.path("summary").asText("").strip(), 60);
        String reason = truncate(json.path("reason").asText("").strip(), 120);
        String comment = truncateCodePoints(json.path("commentText").asText("").strip(), 200);

        int effectiveMin = Math.max(60, Math.min(99, input.minScore()));
        boolean pass = "VIRAL_REPOST".equals(verdict)
                && viralScore >= effectiveMin
                && credibility >= 70;
        return new ViralJudgment(pass, viralScore, magnitude, resonance, credibility, category, summary, reason, comment);
    }

    private String runPiEvaluation(EvaluationInput input) {
        String binary = resolvePiBinary();
        String model = environment == null ? "" : environment.getProperty("X_CONTENT_ASSISTANT_PI_MODEL", "").strip();
        List<String> cmd = new ArrayList<>(List.of(
                binary, "-p",
                "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                "--provider", DEFAULT_PI_PROVIDER,
                "--thinking", "low",
                "--system-prompt", SYSTEM_PROMPT
        ));
        if (!model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        cmd.add("--");
        cmd.add(buildUserPrompt(input));
        CliResult result = executeCli(cmd, "", Duration.ofSeconds(45));
        if (result.exitCode() != 0) {
            String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 判定异常：" + truncate(err, 200));
        }
        return result.stdout();
    }

    private String runCodexEvaluation(EvaluationInput input) {
        String binary = resolveCodexBinary();
        String rawModel = environment == null ? "" : environment.getProperty("X_CONTENT_ASSISTANT_CODEX_MODEL", "").strip();
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-viral-judge-");
            Path schemaFile = workDir.resolve("schema.json");
            Path outFile = workDir.resolve("last-message.json");
            Files.writeString(schemaFile, OUTPUT_SCHEMA_JSON, StandardCharsets.UTF_8);

            List<String> cmd = new ArrayList<>(List.of(
                    binary, "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "-s", "read-only",
                    "-c", "model_reasoning_effort=\"" + spec.reasoningEffort() + "\"",
                    "-C", workDir.toString(),
                    "--output-schema", schemaFile.toString(),
                    "-o", outFile.toString()
            ));
            if (!spec.modelId().isBlank()) {
                cmd.add("-m");
                cmd.add(spec.modelId());
            }
            cmd.add("-");

            String fullPrompt = SYSTEM_PROMPT + "\n\n" + buildUserPrompt(input);
            CliResult result = executeCli(cmd, fullPrompt, Duration.ofSeconds(45));
            if (Files.isRegularFile(outFile)) {
                String fileContent = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!fileContent.isBlank()) return fileContent;
            }
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("Codex CLI (ChatGPT OAuth) 判定异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ioe) {
            throw new IllegalStateException("准备 Codex 判定沙箱失败：" + safeMessage(ioe), ioe);
        } finally {
            deleteQuietly(workDir);
        }
    }

    PiAgentViralJudge.CliResult runCli(List<String> cmd, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        return runner.run(cmd, stdin, timeout);
    }

    private CliResult executeCli(List<String> cmd, String stdin, Duration timeout) {
        try {
            return runner.run(cmd, stdin, timeout);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ChatGPT OAuth 热度判定被中断", ie);
        } catch (IOException ioe) {
            throw new IllegalStateException("调用 ChatGPT OAuth 判定进程失败：" + safeMessage(ioe), ioe);
        }
    }

    private String buildUserPrompt(EvaluationInput input) {
        var payload = mapper.createObjectNode();
        payload.put("authorUsername", "@" + input.authorUsername())
                .put("authorDisplayName", input.authorDisplayName())
                .put("authorVerified", input.authorVerified())
                .put("ageMinutes", Math.max(0, input.ageMinutes()))
                .put("minViralScoreRequired", input.minScore())
                .put("generateComment", input.generateComment());
        if (!input.postText().isBlank()) payload.put("postText", truncate(input.postText(), 3000));
        if (!input.quotedText().isBlank()) payload.put("quotedText", truncate(input.quotedText(), 1500));
        if (!input.articleText().isBlank()) payload.put("articleText", truncate(input.articleText(), 3500));
        var metrics = payload.putObject("earlyMetrics");
        if (input.views() != null) metrics.put("views", input.views());
        if (input.likes() != null) metrics.put("likes", input.likes());
        if (input.reposts() != null) metrics.put("reposts", input.reposts());
        if (input.replies() != null) metrics.put("replies", input.replies());
        if (!input.ruleHint().isBlank()) payload.put("customFocusHint", input.ruleHint());
        if (input.generateComment()) {
            if (!input.ownerStyleProfile().isBlank()) payload.put("ownerWritingStyle", truncate(input.ownerStyleProfile(), 800));
            if (!input.ownerPreferences().isBlank()) payload.put("ownerPreferences", truncate(input.ownerPreferences(), 400));
        }
        return "请评估以下刚采集到的 X 推文并按 JSON 格式返回判定结果：\n" + payload;
    }

    private String preferredBackend() {
        String raw = environment == null ? "" : environment.getProperty(BACKEND_ENV, "").strip().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "pi", "pi-agent" -> "pi";
            case "codex", "codex-cli" -> "codex";
            default -> "auto";
        };
    }

    private void cacheAuth(ObjectNode node, long now) {
        this.cachedAuthStatus = node;
        this.cachedAuthAtMs = now;
    }

    JsonNode parseFirstJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();
        try {
            JsonNode direct = mapper.readTree(trimmed);
            if (direct != null && direct.isObject()) return direct;
        } catch (Exception ignored) { }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            try {
                JsonNode sliced = mapper.readTree(trimmed.substring(firstBrace, lastBrace + 1));
                if (sliced != null && sliced.isObject()) return sliced;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static CliResult defaultRunProcess(List<String> command, String stdin, Duration timeout, String codexHome)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        // Strictly strip API-key env vars so neither codex nor pi can ever fall back to paid OpenAI API billing.
        pb.environment().remove("OPENAI_API_KEY");
        pb.environment().remove("CODEX_API_KEY");
        pb.environment().remove("AZURE_OPENAI_API_KEY");
        pb.environment().put("NO_COLOR", "1");
        if (codexHome != null && !codexHome.isBlank()) {
            pb.environment().put("CODEX_HOME", codexHome);
        }

        List<String> extraDirs = new ArrayList<>();
        Path binaryPath = Path.of(command.get(0));
        if (binaryPath.getParent() != null) extraDirs.add(binaryPath.getParent().toString());
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            Path nvmNodeBin = Path.of(userHome, ".nvm", "versions", "node", "v24.20.0", "bin");
            if (Files.isDirectory(nvmNodeBin)) {
                extraDirs.add(nvmNodeBin.toString());
            }
        }
        String existingPath = pb.environment().getOrDefault("PATH", "");
        if (!extraDirs.isEmpty()) {
            String prefix = String.join(File.pathSeparator, extraDirs);
            pb.environment().put("PATH", existingPath.isBlank() ? prefix : prefix + File.pathSeparator + existingPath);
        }

        Process proc = pb.start();
        try (var out = proc.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        var stdoutFuture = CompletableFuture.supplyAsync(() -> readBounded(proc.getInputStream()));
        var stderrFuture = CompletableFuture.supplyAsync(() -> readBounded(proc.getErrorStream()));
        final boolean finished;
        try {
            finished = proc.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            killProcessTree(proc);
            Thread.currentThread().interrupt();
            throw ie;
        }
        if (!finished) {
            killProcessTree(proc);
            throw new IOException("ChatGPT OAuth 判定超时（>" + timeout.toSeconds() + "s）");
        }
        return new CliResult(proc.exitValue(), awaitStream(stdoutFuture), awaitStream(stderrFuture));
    }

    static void killProcessTree(Process proc) {
        if (proc == null) return;
        try {
            proc.toHandle().descendants().forEach(desc -> {
                try { desc.destroyForcibly(); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
        try {
            proc.destroyForcibly();
        } catch (Exception ignored) { }
    }

    private static String awaitStream(CompletableFuture<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final int MAX_STREAM_CAPTURE_BYTES = 512 * 1024;

    private static String readBounded(java.io.InputStream stream) {
        try (stream) {
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) {
                int remaining = MAX_STREAM_CAPTURE_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buf, 0, Math.min(n, remaining));
                }
            }
            return captured.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static int clampScore(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    static String truncateCodePoints(String s, int maxCodePoints) {
        if (s == null || s.isBlank()) return "";
        int count = s.codePointCount(0, s.length());
        if (count <= maxCodePoints) return s;
        int end = s.offsetByCodePoints(0, maxCodePoints);
        return s.substring(0, end).strip();
    }

    private static String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        return msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : truncate(msg, 200);
    }
}
