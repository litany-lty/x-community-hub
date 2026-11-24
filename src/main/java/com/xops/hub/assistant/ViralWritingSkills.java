package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Built-in viral writing skills for X content creation, combining verified x-algorithm
 * (Phoenix + Home Mixer vm_ranker) engagement mechanics with humanizer-zh anti-AI-slop rules.
 */
public final class ViralWritingSkills {
    public static final String DEFAULT_SKILL_ID = "BREAKING_FLASH";
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("(?m)^\\s*([1-9][.)、]|[•·\\-*])\\s+");
    private static final Pattern ACTIONABLE_KEYWORDS = Pattern.compile(
            "清单|步骤|教程|指南|工具集|checklist|workflow|how to|tips", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPINION_KEYWORDS = Pattern.compile(
            "争议|反驳|真相|高估|低估|误区|分歧|伪命题|对比|为什么说|其实| vs ", Pattern.CASE_INSENSITIVE);

    public record SkillPreset(
            String id,
            String name,
            String badge,
            String summary,
            String targetLength,
            String structurePrompt
    ) { }

    private static final String SHARED_VIRAL_AND_HUMANIZER_KERNEL = """
            【底层传播与去 AI 味硬准则（必须严格执行）】
            1. 黄金前两行钩子（对齐 X 算法 Dwell 与展开率）：第一句直接抛出最反直觉的事实、核心冲突或定性判断，严禁任何背景铺垫、寒暄或“接下来让我们看看”式预告。
            2. 瞄准高权重互动信号（X vm_ranker 权重：复制链接分享 20x、深度评论互动 5–20x、引用/私信转发 5x 远高于纯点赞 0.5x）：
               - 提供可被读者直接转发给同行/朋友的「高密度增量信息或清晰判断框架」；
               - 收尾留一个具体、有边界的真实判断分水岭，严禁使用“你怎么看？欢迎评论区留言”等低质讨赞套话。
            3. 严苛去 AI 套话（humanizer-zh 准则）：
               - 严禁“这不仅是 X，更是 Y”假对比抬高语气；
               - 严禁“值得注意的是、总而言之、毫无疑问、颠覆性、赋能、抓手、闭环、开启新纪元”等空泛套话；
               - 严禁强凑空洞排比三段式或无信息量的戏剧性单句碎片；段落保持 1–3 句的自然呼吸感。
            4. 事实铁律：严格使用提供的原帖与已核实研究材料，不编造任何未出现的数字、日期、内幕或作者亲测经历。
            """;

    private static final List<SkillPreset> PRESETS = List.of(
            new SkillPreset(
                    "BREAKING_FLASH",
                    "突发快评",
                    "快讯爆款",
                    "首句爆点定性 + 核心增量事实 + 二阶影响判断，适合一手动态快速抢占引用与转发",
                    "140–240 字",
                    """
                    【当前采用预置 Skill：突发重磅快评（建议篇幅约 140–240 汉字）】
                    - 第 1 段（1 句）：直接点明核心人物/机构做了什么及其最关键的分量，一眼看懂为什么值得停下来读。
                    - 第 2 段（2–3 句）：提炼原帖最硬的 1–2 个具体事实增量，删掉一切修饰性废话。
                    - 第 3 段（1–2 句）：给出克制但一针见血的行业二阶影响判断（接下来真正会改变什么）。
                    """
            ),
            new SkillPreset(
                    "DEEP_BREAKDOWN",
                    "深度拆解",
                    "高藏高转",
                    "反常识立论 + 底层机制拆解 + 隐含边界，适合技术发布与商业复盘长帖（拉满收藏与复制链接 20x 权重）",
                    "350–680 字",
                    """
                    【当前采用预置 Skill：深度拆解长文（建议篇幅约 350–680 汉字）】
                    - 开篇立论：首句直接给出穿透表象的核心判断（多数人只看到了表面热闹，真正关键的变化在哪）。
                    - 主体拆解：按逻辑递进拆解 2–3 个核心机制、技术/商业变量或前后对比，每一点必须有具体事实支撑，不凑空泛小标题。
                    - 边界与代价：点出一个容易被忽视的前提条件、适用边界或潜在代价，体现专业可信度。
                    - 收束结论：用一句可复用的判断准则收尾，让读者愿意收藏或直接复制链接分享给同事。
                    """
            ),
            new SkillPreset(
                    "SHARP_OPINION",
                    "犀利观点",
                    "高评论互动",
                    "鲜明亮出非共识判断 + 事实支点 + 具象分歧点，精准撬动 X 算法高权重深度回复（5–20x）",
                    "160–280 字",
                    """
                    【当前采用预置 Skill：犀利观点促评（建议篇幅约 160–280 汉字）】
                    - 首句亮剑：直接给出明确、有棱角但不偏激的非共识判断，不和稀泥。
                    - 事实支点：用原帖中的核心事实或数据作为论据，指出惯常看法漏掉了哪个关键变量。
                    - 分水岭收尾：最后一句落在一个具体、有现实争议的选择题或判断分界线上，自然激发同行在评论区摆观点辩论。
                    """
            ),
            new SkillPreset(
                    "ACTIONABLE_LIST",
                    "干货清单",
                    "高收藏转化",
                    "首句直击具体问题 + 高信噪比要点清单 + 适用场景，适合方法论、工具与多要点提纯",
                    "200–420 字",
                    """
                    【当前采用预置 Skill：结构化干货清单（建议篇幅约 200–420 汉字）】
                    - 首句痛点/收益：直接说明这组信息解决什么具体问题或省掉什么弯路。
                    - 清单主体：用清晰的短条目（1. 2. 3. 或 ·）提纯原帖最核心的硬核要点；每条只保留具体动作、参数或结论，只有几条真干货就写几条，绝不注水凑数。
                    - 结尾落点：一句话点明最适合在什么场景下立刻用起来。
                    """
            )
    );

    private static final Map<String, SkillPreset> BY_ID = PRESETS.stream()
            .collect(Collectors.toUnmodifiableMap(SkillPreset::id, Function.identity()));

    private ViralWritingSkills() {
    }

    public static List<SkillPreset> presets() {
        return PRESETS;
    }

    public static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        return BY_ID.containsKey(upper) ? upper : "";
    }

    public static SkillPreset resolveOrDefault(String raw) {
        String id = normalizeId(raw);
        return id.isEmpty() ? BY_ID.get(DEFAULT_SKILL_ID) : BY_ID.get(id);
    }

    public static String directiveFor(String rawSkillId) {
        String id = normalizeId(rawSkillId);
        if (id.isEmpty()) {
            return SHARED_VIRAL_AND_HUMANIZER_KERNEL;
        }
        SkillPreset skill = BY_ID.get(id);
        return SHARED_VIRAL_AND_HUMANIZER_KERNEL + "\n" + skill.structurePrompt();
    }

    /**
     * Zero-token deterministic recommendation of the best built-in viral writing skill
     * for a topic and its source materials. Always returns one of the 4 built-in IDs.
     */
    public static String recommendSkill(JsonNode topic, List<? extends JsonNode> materials) {
        List<String> topicParts = new ArrayList<>();
        if (topic != null) {
            appendIfNotBlank(topicParts, topic.path("title").asText(""));
            appendIfNotBlank(topicParts, topic.path("summary").asText(""));
            JsonNode tags = topic.path("tags");
            if (tags.isArray()) {
                for (JsonNode tag : tags) {
                    appendIfNotBlank(topicParts, tag.asText(""));
                }
            }
        }

        List<String> materialParts = new ArrayList<>();
        boolean hasArticle = false;
        boolean hasQuoteWithComment = false;
        int maxSingleMaterialLength = 0;
        if (materials != null) {
            for (JsonNode mat : materials) {
                if (mat == null || mat.isMissingNode() || mat.isNull()) continue;
                String text = mat.path("text").asText("").strip();
                String quotedText = mat.path("quotedText").asText("").strip();
                String articleText = mat.path("articleText").asText("").strip();
                String textStatus = mat.path("textStatus").asText("").toUpperCase(Locale.ROOT);
                String articleStatus = mat.path("articleTextStatus").asText("").toUpperCase(Locale.ROOT);
                if (!articleText.isBlank()
                        || "ARTICLE_FULL".equals(textStatus)
                        || "ARTICLE_PREVIEW".equals(textStatus)
                        || "FULL".equals(articleStatus)
                        || "PREVIEW".equals(articleStatus)) {
                    hasArticle = true;
                }
                if (!text.isBlank() && ("QUOTE".equalsIgnoreCase(mat.path("kind").asText(""))
                        || !quotedText.isBlank()
                        || !mat.path("quotedId").asText("").isBlank())) {
                    hasQuoteWithComment = true;
                }
                List<String> singleParts = new ArrayList<>();
                appendIfNotBlank(singleParts, text);
                appendIfNotBlank(singleParts, quotedText);
                appendIfNotBlank(singleParts, articleText);
                if (!singleParts.isEmpty()) {
                    String singleJoined = String.join("\n", singleParts);
                    maxSingleMaterialLength = Math.max(maxSingleMaterialLength, singleJoined.codePointCount(0, singleJoined.length()));
                    materialParts.add(singleJoined);
                }
            }
        }

        String topicBody = String.join("\n", topicParts);
        String materialsBody = String.join("\n", materialParts);
        String primaryBody = materialsBody.isBlank() ? topicBody : materialsBody;
        String allText = topicBody.isBlank() ? materialsBody
                : (materialsBody.isBlank() ? topicBody : topicBody + "\n" + materialsBody);

        // (a) Actionable list: >= 3 numbered/bulleted items or methodology keywords
        if (countMatches(LIST_ITEM_PATTERN, primaryBody) >= 3 || ACTIONABLE_KEYWORDS.matcher(allText).find()) {
            return "ACTIONABLE_LIST";
        }

        // (b) Deep breakdown: article text/status, long body (>= 420 chars), or high valueScore over reachScore
        int primaryLength = primaryBody.codePointCount(0, primaryBody.length());
        int valueScore = topic != null ? topic.path("valueScore").asInt(0) : 0;
        int reachScore = topic != null ? topic.path("reachScore").asInt(0) : 0;
        if (hasArticle
                || maxSingleMaterialLength >= 420
                || primaryLength >= 420
                || (valueScore >= 85 && (valueScore - reachScore) >= 12)) {
            return "DEEP_BREAKDOWN";
        }

        // (c) Sharp opinion: debate/contrast signals or quote post with author commentary
        if (OPINION_KEYWORDS.matcher(allText).find() || hasQuoteWithComment) {
            return "SHARP_OPINION";
        }

        // (d) Default: breaking flash
        return DEFAULT_SKILL_ID;
    }

    private static void appendIfNotBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.strip());
        }
    }

    private static int countMatches(Pattern pattern, String text) {
        if (text == null || text.isBlank()) return 0;
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
            if (count >= 3) return count;
        }
        return count;
    }
}
