package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.xops.hub.assistant.AssistantStore.object;

/**
 * Distills the owner's quote-repost voice, decides short-flash vs. detailed-update structure,
 * supplements author role context, and retrieves relevant historical owner stances so quote
 * commentary reflects real domain intuition and natural emotional cadence.
 */
public final class OwnerQuoteStyleDistiller {
    public static final int MAX_QUOTE_SENTENCES = 5;
    private static final int BRIEF_POST_MAX_CODEPOINTS = 140;
    private static final int BRIEF_POST_MAX_LINES = 2;
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?]+)|\\n+");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern BRIEF_TLDR_LINE = Pattern.compile("^(?:💡\\s*)?省流重点[：:].*$");
    private static final Pattern TRAILING_QUESTION_PARAGRAPH = Pattern.compile("[？?][\\p{So}\\p{Mn}\\p{Sk}\\p{Cn}\\p{Zs}\\s\\u200D]*$");
    private static final Pattern AI_SLOP_OPENINGS = Pattern.compile(
            "^(?:值得注意的是[，,：:]?|总而言之[，,：:]?|综上所述[，,：:]?|不得不说[，,：:]?|不可否认[，,：:]?|众所周知[，,：:]?)\\s*");
    private static final Pattern SUBJECTIVE_STANCE_HINT = Pattern.compile(
            "(?:降智|懒得|真香|离谱|说实话|没用|舒服|抠|危机|急了|失望|卡|额度|重置|不如|还是|翻车|折腾|肉疼)");

    private static final Map<String, String> KNOWN_AUTHOR_ROLES = Map.ofEntries(
            Map.entry("thsottiaux", "OpenAI Codex & ChatGPT 负责人"),
            Map.entry("sama", "OpenAI CEO"),
            Map.entry("gdb", "OpenAI 联合创始人兼总裁"),
            Map.entry("openai", "OpenAI 官方"),
            Map.entry("openaidevs", "OpenAI 开发者官方"),
            Map.entry("chatgptapp", "OpenAI ChatGPT 官方"),
            Map.entry("anthropicai", "Anthropic / Claude 官方"),
            Map.entry("darioamodei", "Anthropic CEO"),
            Map.entry("alexalbert__", "Anthropic Claude 开发者关系负责人"),
            Map.entry("bcherny", "Anthropic Claude Code 负责人"),
            Map.entry("officiallogank", "Google AI Studio / Gemini 产品负责人"),
            Map.entry("geminiapp", "Google Gemini 官方"),
            Map.entry("demishassabis", "Google DeepMind CEO"),
            Map.entry("jeffdean", "Google 首席科学家"),
            Map.entry("sundarpichai", "Google / Alphabet CEO"),
            Map.entry("karpathy", "Andrej Karpathy（前 OpenAI 联创）"),
            Map.entry("_mohansolo", "Windsurf / Codeium 核心团队"),
            Map.entry("windsurf_ai", "Windsurf 官方"),
            Map.entry("cursor_ai", "Cursor 官方"),
            Map.entry("rauchg", "Vercel CEO"),
            Map.entry("elonmusk", "xAI / X 掌门人"),
            Map.entry("xai", "xAI / Grok 官方"),
            Map.entry("deepseek_ai", "DeepSeek 官方")
    );

    public static final String DEFAULT_DISTILLED_QUOTE_PROFILE = """
            【引用转发自适应表达规则（按原帖信息密度自动切换，禁止机械套模版）】
            一、结构按原帖信息量二选一：
            1. 【短快讯直评（briefFlashPost = true：原帖只有 1～2 句极简通知/一句话官宣，字数少、没有多项新参数）】
               - **严禁写「💡 省流重点：」**！原帖只有一句话时再写省流就是机器凑字数。
               - 固定 **2 段（共 2 句，段间空一行）**：
                 · 第 1 段（1 句）：`🎉` 或 `🚨` 开头，用大白话直接说清是谁宣布/上线了什么。
                 · 第 2 段（1 句）：结合 `authorBio`（作者所属公司/身份）、`currentHotContext`（圈内竞争热点）与 `ownerRecentStances` / `ownerPreferences`（我的真实体感态度），直接给出内行洞察、犀利判断或真实吐槽。
            2. 【长帖/多要点提炼（briefFlashPost = false：原帖包含多项更新、数据指标或长文解析）】
               - 采用 **3 段式（共 3～4 句，段间空一行）**：
                 · 第 1 段：`🎉` 或 `🚨` 开头挑明谁发了什么关键更新 + 第一反应。
                 · 第 2 段：以 `💡 省流重点：` 开头，用人话拎出最硬核的 1～2 个变化、数字或适用范围。
                 · 第 3 段：结合自身重度 AI/开发体验给出直白判断或共鸣收尾。
            二、语气随情绪自然切换 + 严禁机械废话：
            - 吐槽、泼冷水、看穿大厂危机公关时要干脆犀利，可用 `……` 留白或冷幽默收尾，**绝对不要硬塞「啦 / 呀 / ～」和猫猫表情**。
            - 只有遇到真正好用、省钱、爽到的更新时，才自然带点轻松语气或贴合情绪的猫猫表情（`😺`/`😽`/`😼`）。
            - **严禁空洞废话与提问收尾**：禁止写「具体细节原帖没说」「功能上线是好事，细节也得跟上」「这一步才见功夫」「值得关注」「分水岭」等公关腔，也严禁在结尾加提问句。
            """.strip();

    public static final List<String> SIGNATURE_QUOTE_EXAMPLES = List.of(
            "🎉 Tibo 说，Banked reset 已经上线。\n\n看来OpenAI意识到危机了，但是重置额度后，面对的还是降智AI……我都懒得用重置卡了",
            "🚨 Anthropic 把长任务后台执行权限又放宽了一档。\n\n最近各家都在抢重度 Agent 工作流，谁先把断线和限流忍过去，谁就能留住真付钱的用户。",
            "🎉 新一版主力编码模型终于放出来啦，这次连长上下文缓存价也一块大砍了一刀!!😺\n\n💡 省流重点：跨文件改代码的准头稳了一截，重度挂机跑仓库的缓存成本直接降了四分之三。\n\n平时拿 Agent 啃大项目最肉疼的就是反复读上下文烧额度，这波算是精准挠到天天写代码的人痒处啦～😽",
            "🚨 会话环境推了个超实在的更新，现在直接在对话框里就能调完整云电脑干重活啦!!🎉\n\n💡 省流重点：原生打通了子代理编排并预装好 Blender、FFmpeg 等全套命令行工具，不用再自己配本地环境。\n\n以前好多好玩的小脑洞全卡在装依赖劝退上，现在一句话就能把订阅里吃灰的算力榨干，这波真挺实用～😽"
    );

    public static final String LEGACY_V1_POLISH_PROMPT_PREFIX = "把上面的初稿按我平时发推跟熟人随口聊天的口吻彻底重写一遍";
    public static final String LEGACY_V2_POLISH_PROMPT_PREFIX = "把上面的初稿严格按我面对「模型发布 / 重大新闻 / 更新通知」的【统一四段式固定模版】";
    public static final String LEGACY_V3_POLISH_PROMPT_PREFIX = "把上面的初稿严格按我面对「模型发布 / 重大新闻 / 更新通知」的【统一三段式固定模版】";

    public static final String DEFAULT_POST_POLISH_PROMPT = """
            对照【被引用原帖】和上下文（`briefFlashPost`、`authorBio`、`currentHotContext`、`ownerRecentStances`），把初稿打磨成真人内行口吻：
            1. 结构自适应：若 `briefFlashPost = true`（一句话短快讯），彻底删掉「💡 省流重点：」及其废话（如「原帖没说细节」），只保留 2 段（第 1 段 `🎉/🚨` 挑明谁发了什么；第 2 段直接结合大厂处境和我的真实使用体感给出判断或吐槽）。只有长帖/多要点更新才保留 3 段（含 `💡 省流重点：`）。
            2. 情绪真实切换：吐槽、看穿套路或泼冷水时语气要干脆犀利（可用 `……` 收尾），删掉违和的「啦 / 呀 / ～」和猫猫表情；真香更新才自然保留轻松感与 `😺/😽/😼`。
            3. 去 AI 公关腔：删光「功能上线是好事，细节也得跟上」「这一步才见功夫」「才是真本事」「分水岭」「值得关注」等套话，严禁在末尾加反问或提问句。
            """.strip();

    private OwnerQuoteStyleDistiller() { }

    public static String normalizePolishPrompt(String rawPrompt) {
        String saved = rawPrompt == null ? "" : rawPrompt.strip();
        if (saved.isEmpty()
                || saved.startsWith(LEGACY_V1_POLISH_PROMPT_PREFIX)
                || saved.startsWith(LEGACY_V2_POLISH_PROMPT_PREFIX)
                || saved.startsWith(LEGACY_V3_POLISH_PROMPT_PREFIX)) {
            return DEFAULT_POST_POLISH_PROMPT;
        }
        return saved;
    }

    public static boolean isBuiltInQuotePolishPrompt(String rawPrompt) {
        String saved = rawPrompt == null ? "" : rawPrompt.strip();
        return saved.isEmpty()
                || saved.equals(DEFAULT_POST_POLISH_PROMPT)
                || saved.startsWith(LEGACY_V1_POLISH_PROMPT_PREFIX)
                || saved.startsWith(LEGACY_V2_POLISH_PROMPT_PREFIX)
                || saved.startsWith(LEGACY_V3_POLISH_PROMPT_PREFIX);
    }

    public static boolean isBriefFlashPost(String postText, String quotedText, String articleText) {
        if (articleText != null) {
            String cleanArticle = URL_PATTERN.matcher(articleText).replaceAll("").strip();
            if (!cleanArticle.isEmpty() && cleanArticle.codePointCount(0, cleanArticle.length()) > 40) {
                return false;
            }
        }
        StringBuilder combined = new StringBuilder();
        if (postText != null && !postText.isBlank()) {
            combined.append(postText.strip());
        }
        if (quotedText != null && !quotedText.isBlank()) {
            if (!combined.isEmpty()) combined.append('\n');
            combined.append(quotedText.strip());
        }
        String cleaned = URL_PATTERN.matcher(combined.toString()).replaceAll("").strip();
        if (cleaned.isEmpty()) {
            return true;
        }
        int nonEmptyLines = 0;
        for (String line : cleaned.split("\\R")) {
            if (!line.strip().isEmpty()) {
                nonEmptyLines++;
            }
        }
        String compact = cleaned.replaceAll("\\s+", " ").strip();
        int codePoints = compact.codePointCount(0, compact.length());
        return nonEmptyLines <= BRIEF_POST_MAX_LINES && codePoints <= BRIEF_POST_MAX_CODEPOINTS;
    }

    public static String resolveAuthorContext(String username, String authorBio) {
        String cleanUser = username == null ? "" : username.replaceFirst("^@+", "").strip().toLowerCase(Locale.ROOT);
        String knownRole = KNOWN_AUTHOR_ROLES.getOrDefault(cleanUser, "");
        String cleanBio = authorBio == null ? "" : authorBio.replaceAll("\\s+", " ").strip();
        if (cleanBio.length() > 180) {
            cleanBio = cleanBio.substring(0, 180).strip();
        }
        if (knownRole.isEmpty()) {
            return cleanBio;
        }
        if (cleanBio.isEmpty()) {
            return knownRole;
        }
        String lowerBio = cleanBio.toLowerCase(Locale.ROOT);
        if (lowerBio.contains("openai") && knownRole.contains("OpenAI")) {
            return cleanBio + "（" + knownRole + "）";
        }
        if (lowerBio.equalsIgnoreCase(knownRole)) {
            return cleanBio;
        }
        return knownRole + "｜简介：" + cleanBio;
    }

    public static List<ObjectNode> collectOwnerAuthoredSamples(AssistantStore.Tx tx, int maxSamples) {
        if (tx == null || maxSamples <= 0) {
            return List.of();
        }
        ObjectNode owner = tx.get("meta", "owner");
        String ownerId = owner == null ? "" : owner.path("accountId").asText("").strip();
        String ownerUsername = owner == null ? "" : owner.path("username").asText("").replaceFirst("^@+", "").strip();

        record RawCandidate(ObjectNode node, String dedupKey, boolean isQuote, long publishedAt) { }
        Map<String, RawCandidate> byId = new LinkedHashMap<>();
        Set<String> seenText = new LinkedHashSet<>();

        for (ObjectNode sample : tx.matching("style_sample", "textRole", "AUTHOR", 400)) {
            if (sample == null) continue;
            String raw = sample.path("text").asText("").strip();
            String cleaned = URL_PATTERN.matcher(raw).replaceAll("").replaceAll("\\s+", " ").strip();
            if (cleaned.codePointCount(0, cleaned.length()) < 4) continue;
            String sid = sample.path("id").asText("").strip();
            String idKey = sid.isEmpty() ? cleaned : sid;
            if (!seenText.add(cleaned)) continue;
            boolean isQuote = sample.path("isQuote").asBoolean(false)
                    || "QUOTE".equalsIgnoreCase(sample.path("kind").asText(""));
            byId.put(idKey, new RawCandidate(sample.deepCopy(), cleaned, isQuote, sample.path("publishedAt").asLong(0L)));
        }

        if (!ownerId.isBlank() || !ownerUsername.isBlank()) {
            for (ObjectNode hist : tx.all("history_material")) {
                if (hist == null) continue;
                String hAid = hist.path("authorId").asText("").strip();
                String hUname = hist.path("username").asText("").replaceFirst("^@+", "").strip();
                boolean isOwner = (!ownerId.isBlank() && ownerId.equals(hAid))
                        || (!ownerUsername.isBlank() && ownerUsername.equalsIgnoreCase(hUname));
                if (!isOwner) continue;
                String kind = hist.path("kind").asText("POST").strip().toUpperCase(Locale.ROOT);
                if (!"POST".equals(kind) && !"QUOTE".equals(kind)) continue;
                String raw = hist.path("text").asText("").strip();
                String cleaned = URL_PATTERN.matcher(raw).replaceAll("").replaceAll("\\s+", " ").strip();
                if (cleaned.codePointCount(0, cleaned.length()) < 12) continue;
                String hid = hist.path("id").asText("").strip();
                String idKey = hid.isEmpty() ? cleaned : hid;
                if (byId.containsKey(idKey) || !seenText.add(cleaned)) continue;
                boolean isQuote = "QUOTE".equals(kind);
                ObjectNode copy = hist.deepCopy()
                        .put("textRole", "AUTHOR")
                        .put("isQuote", isQuote);
                byId.put(idKey, new RawCandidate(copy, cleaned, isQuote, hist.path("publishedAt").asLong(0L)));
            }
        }

        List<RawCandidate> quotes = byId.values().stream()
                .filter(RawCandidate::isQuote)
                .sorted(Comparator.comparingLong(RawCandidate::publishedAt).reversed())
                .toList();
        List<RawCandidate> posts = byId.values().stream()
                .filter(c -> !c.isQuote())
                .sorted(Comparator.comparingLong(RawCandidate::publishedAt).reversed())
                .toList();

        int maxQuotes = Math.max(1, (int) Math.ceil(maxSamples * 0.4));
        List<ObjectNode> merged = new ArrayList<>(Math.min(maxSamples, byId.size()));
        Set<String> pickedKeys = new LinkedHashSet<>();
        for (RawCandidate q : quotes) {
            if (merged.size() >= maxQuotes || merged.size() >= maxSamples) break;
            merged.add(q.node());
            pickedKeys.add(q.dedupKey());
        }
        for (RawCandidate p : posts) {
            if (merged.size() >= maxSamples) break;
            if (pickedKeys.add(p.dedupKey())) {
                merged.add(p.node());
            }
        }
        for (RawCandidate q : quotes) {
            if (merged.size() >= maxSamples) break;
            if (pickedKeys.add(q.dedupKey())) {
                merged.add(q.node());
            }
        }
        return merged;
    }

    public static List<String> findRelevantOwnerStances(
            AssistantStore.Tx tx,
            String authorUsername,
            String authorBio,
            String postText,
            String quotedText,
            int limit
    ) {
        if (tx == null || limit <= 0) {
            return List.of();
        }
        String roleContext = resolveAuthorContext(authorUsername, authorBio);
        String combinedContext = ((authorUsername == null ? "" : authorUsername)
                + " " + roleContext
                + " " + (postText == null ? "" : postText)
                + " " + (quotedText == null ? "" : quotedText)).toLowerCase(Locale.ROOT);

        Set<String> searchTerms = new LinkedHashSet<>();
        if (containsAny(combinedContext, "openai", "chatgpt", "codex", "gpt", "o1", "o3", "o4", "sora", "thsottiaux", "sama", "gdb", "banked")) {
            searchTerms.addAll(List.of("openai", "chatgpt", "gpt", "codex", "降智", "重置", "plus", "pro"));
        }
        if (containsAny(combinedContext, "anthropic", "claude", "sonnet", "opus", "haiku", "mcp", "darioamodei", "bcherny")) {
            searchTerms.addAll(List.of("claude", "anthropic", "sonnet", "opus", "封号", "额度"));
        }
        if (containsAny(combinedContext, "google", "gemini", "deepmind", "aistudio", "logan", "officiallogank", "sundarpichai")) {
            searchTerms.addAll(List.of("gemini", "google", "deepmind", "aistudio"));
        }
        if (containsAny(combinedContext, "cursor", "windsurf", "agent", "cli", "ide", "coding", "code")) {
            searchTerms.addAll(List.of("agent", "cursor", "windsurf", "写代码", "上下文"));
        }
        if (containsAny(combinedContext, "reset", "quota", "limit", "usage", "credit", "banked", "额度", "重置")) {
            searchTerms.addAll(List.of("额度", "重置", "降智", "懒得", "订阅", "限流"));
        }
        if (searchTerms.isEmpty()) {
            return List.of();
        }

        List<ObjectNode> rawCandidates = collectOwnerAuthoredSamples(tx, 400);

        record ScoredStance(String text, int score, long publishedAt) { }
        Map<String, ScoredStance> bestByText = new LinkedHashMap<>();
        for (ObjectNode node : rawCandidates) {
            if (node == null) continue;
            String rawText = node.path("text").asText("").strip();
            if (rawText.isBlank()) continue;
            String cleaned = URL_PATTERN.matcher(rawText).replaceAll("").replaceAll("\\s+", " ").strip();
            int cp = cleaned.codePointCount(0, cleaned.length());
            if (cp < 10 || cp > 220) continue;

            String lower = cleaned.toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String term : searchTerms) {
                if (lower.contains(term)) {
                    hits++;
                }
            }
            if (hits == 0) continue;

            int score = hits * 10;
            if (SUBJECTIVE_STANCE_HINT.matcher(cleaned).find()) {
                score += 8;
            }
            if (node.path("isQuote").asBoolean(false) || "QUOTE".equalsIgnoreCase(node.path("kind").asText(""))) {
                score += 2;
            }
            long pubAt = node.path("publishedAt").asLong(0L);
            ScoredStance prev = bestByText.get(cleaned);
            if (prev == null || score > prev.score() || (score == prev.score() && pubAt > prev.publishedAt())) {
                bestByText.put(cleaned, new ScoredStance(cleaned, score, pubAt));
            }
        }

        return bestByText.values().stream()
                .sorted(Comparator.comparingInt(ScoredStance::score).reversed()
                        .thenComparing(Comparator.comparingLong(ScoredStance::publishedAt).reversed()))
                .limit(limit)
                .map(ScoredStance::text)
                .toList();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static String composeOwnerDistilledPersona(AssistantStore.Tx tx) {
        if (tx == null) {
            return "";
        }
        ObjectNode style = tx.get("meta", "style");
        String profile = style == null ? "" : style.path("profile").asText("").strip();
        List<ObjectNode> ownerSamples = collectOwnerAuthoredSamples(tx, 300);
        List<String> realQuotes = ownerSamples.stream()
                .filter(n -> n.path("isQuote").asBoolean(false) || "QUOTE".equalsIgnoreCase(n.path("kind").asText("")))
                .map(n -> URL_PATTERN.matcher(n.path("text").asText("")).replaceAll("").replaceAll("\\s+", " ").strip())
                .filter(t -> t.codePointCount(0, t.length()) >= 8)
                .distinct()
                .limit(4)
                .toList();
        if (profile.isBlank() && realQuotes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!profile.isBlank()) {
            sb.append("【大模型蒸馏的本人语言习惯与真实立场画像】").append(profile);
        }
        if (!realQuotes.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("【本人过往真实引用转发例句】");
            for (String q : realQuotes) {
                sb.append("\n- ").append(q);
            }
        }
        return PiAgentViralJudge.truncateCodePoints(sb.toString().strip(), 900);
    }

    public static ObjectNode resolveOrDistillQuoteStyle(AssistantStore.Tx tx) {
        ObjectNode existing = tx.get("meta", "quote_style");
        List<ObjectNode> ownerSamples = collectOwnerAuthoredSamples(tx, 300);
        List<ObjectNode> quoteSamples = ownerSamples.stream()
                .filter(sample -> sample.path("isQuote").asBoolean(false)
                        || "QUOTE".equalsIgnoreCase(sample.path("kind").asText("")))
                .sorted(Comparator.comparingLong((ObjectNode n) -> n.path("publishedAt").asLong(0)).reversed())
                .toList();

        ObjectNode styleMeta = tx.get("meta", "style");
        String distilledPersona = styleMeta == null ? "" : styleMeta.path("profile").asText("").strip();

        ObjectNode out = existing != null ? existing.deepCopy() : object();
        out.put("profile", DEFAULT_DISTILLED_QUOTE_PROFILE)
                .put("distilledPersonaProfile", distilledPersona)
                .put("voiceRevision", "2026-11-26-v6")
                .put("maxSentences", MAX_QUOTE_SENTENCES)
                .put("historicalOwnerSampleCount", ownerSamples.size())
                .put("historicalQuoteSampleCount", quoteSamples.size());

        ArrayNode realQuoteExamples = out.putArray("realQuoteExamples");
        quoteSamples.stream()
                .map(q -> q.path("text").asText("").strip())
                .filter(txt -> txt.length() >= 8 && txt.codePointCount(0, txt.length()) <= 160)
                .distinct()
                .limit(6)
                .forEach(realQuoteExamples::add);

        ArrayNode examples = out.putArray("examples");
        int added = 0;
        for (String sig : SIGNATURE_QUOTE_EXAMPLES) {
            examples.add(sig);
            added++;
        }
        for (ObjectNode q : quoteSamples) {
            if (added >= 10) break;
            String txt = q.path("text").asText("").strip();
            if (txt.length() >= 8 && txt.codePointCount(0, txt.length()) <= 120 && countSentences(txt) <= MAX_QUOTE_SENTENCES) {
                examples.add(txt);
                added++;
            }
        }
        if (existing == null
                || !"2026-11-26-v6".equals(existing.path("voiceRevision").asText(""))
                || !distilledPersona.equals(existing.path("distilledPersonaProfile").asText(""))
                || ownerSamples.size() != existing.path("historicalOwnerSampleCount").asInt(-1)) {
            out.put("voiceRevision", "2026-11-26-v6");
            tx.put("meta", "quote_style", out);
        }
        return out;
    }

    public static int countSentences(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        for (String seg : SENTENCE_SPLIT.split(text.strip())) {
            String trimmed = seg.strip();
            if (!trimmed.isEmpty() && trimmed.codePoints().anyMatch(Character::isLetterOrDigit)) {
                count++;
            }
        }
        return count;
    }

    public static String stripWrappingQuotesOrFences(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1).strip();
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3).strip();
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("“") && s.endsWith("”"))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * Backward-compatible wrapper applying standard concise quote formatting.
     */
    public static String enforceConciseQuoteFormat(String rawComment, int maxCodePoints) {
        return enforceAdaptiveQuoteFormat(rawComment, false, maxCodePoints);
    }

    /**
     * Strips code fences / AI-slop filler, drops redundant "💡 省流重点：" sections on brief flash posts,
     * removes trailing interactive question paragraphs, caps sentences at <= 5, and caps code points.
     */
    public static String enforceAdaptiveQuoteFormat(String rawComment, boolean briefFlashPost, int maxCodePoints) {
        String cleaned = stripWrappingQuotesOrFences(rawComment);
        if (cleaned.isBlank()) return "";
        cleaned = AI_SLOP_OPENINGS.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").strip();

        String[] paragraphs = cleaned.split("\\n\\n+");
        List<String> keptParagraphs = new ArrayList<>();
        int totalSentences = 0;
        for (String para : paragraphs) {
            if (totalSentences >= MAX_QUOTE_SENTENCES) break;
            String[] lines = para.split("\\n");
            List<String> keptLines = new ArrayList<>();
            for (String line : lines) {
                if (totalSentences >= MAX_QUOTE_SENTENCES) break;
                String trimmedLine = line.strip();
                if (trimmedLine.isEmpty()) continue;
                if (briefFlashPost && BRIEF_TLDR_LINE.matcher(trimmedLine).matches()) {
                    continue;
                }
                String[] sents = SENTENCE_SPLIT.split(trimmedLine);
                StringBuilder lineBuilder = new StringBuilder();
                for (String s : sents) {
                    if (s.isBlank()) continue;
                    boolean hasWord = s.codePoints().anyMatch(Character::isLetterOrDigit);
                    if (hasWord && totalSentences >= MAX_QUOTE_SENTENCES) break;
                    lineBuilder.append(s);
                    if (hasWord) {
                        totalSentences++;
                    }
                }
                if (!lineBuilder.isEmpty()) {
                    keptLines.add(lineBuilder.toString().strip());
                }
            }
            if (!keptLines.isEmpty()) {
                keptParagraphs.add(String.join("\n", keptLines));
            }
        }
        while (((briefFlashPost && keptParagraphs.size() >= 3) || keptParagraphs.size() >= 4)
                && TRAILING_QUESTION_PARAGRAPH.matcher(keptParagraphs.get(keptParagraphs.size() - 1)).find()) {
            keptParagraphs.remove(keptParagraphs.size() - 1);
        }
        String joined = String.join("\n\n", keptParagraphs).strip();
        if (joined.isBlank() && briefFlashPost) {
            return enforceAdaptiveQuoteFormat(rawComment, false, maxCodePoints);
        }
        return PiAgentViralJudge.truncateCodePoints(joined, maxCodePoints);
    }
}
