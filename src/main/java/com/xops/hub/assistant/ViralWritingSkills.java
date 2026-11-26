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
            【底层流量传播、话题设计及去 AI 味硬准则（必须严格执行）】
            1. 严禁当“课代表总结”或“通稿复述机”：
               - 读者刷 X 不是看新闻摘要或说明书缩写。严禁按“谁发布了什么 → 包含哪几点更新 → 意味着什么”的通报式套路平铺直叙复述！
               - 原帖与深度研究参考资料（`referenceContext` 中的外部网页摘录、一手素材、作者身份背景与热度信号）只是你的论据弹药库，用来打信息差、撑起判断与制造冲突，绝不是让你把原帖缩写一遍。
            2. 黄金前两行钩子（对齐 X 算法 Dwell 停留与展开率）：
               - 第一句禁止报菜名式官宣铺垫、寒暄或“接下来让我们看看”式预告；直接切入跟读者切身相关的利害痛点、最反直觉的事实反差、行业暗战潜台词或扎心真实处境。
            3. 深度研究信息差 + 话题张力（X vm_ranker 权重：深度评论互评 5–75x、复制链接分享 20x、引用/私信转发与收藏 5–10x 远高于纯点赞 0.5x）：
               - 信息差增量：优先调取 `referenceContext`（深度研究外部报道/基准摘录、跨信源对比、作者角色背景）挖出“光看原帖意识不到的背景、真实成本账、横向反差或隐含门槛”，提供可被读者直接转发给同行的高密度增量洞察；
               - 可站队的话题切口：亮出鲜明、有棱角的内行判断（看好哪点、泼什么冷水、谁最受益、谁最尴尬），收尾落在一个具体、有现实分歧的路线取舍或判断分水岭上，自然激发同行在评论区接话辩论或引用转发开麦，严禁使用“你怎么看？欢迎评论区留言”等低质讨赞套话。
            4. 严苛去 AI 套话与活人呼吸感（humanizer-zh 准则）：
               - 像真正在一线写代码、重度用产品、算过细账的内行发推聊话题，短句利落、敢下判断；
               - 严禁“这不仅是 X，更是 Y”假对比抬高语气；
               - 严禁“值得注意的是、总而言之、毫无疑问、颠覆性、赋能、抓手、闭环、开启新纪元、拭目以待”等空泛套话；
               - 严禁强凑空洞排比三段式或无信息量的戏剧性单句碎片；段落保持 1–3 句的自然呼吸感。
            5. 事实铁律：观点可以鲜明犀利，但所有具体事实、数字、日期、人物身份与技术细节必须严格取自提供的原帖与已核实研究材料（`research` 与 `referenceContext`），不编造任何未出现的数字、日期、内幕或作者亲测经历。
            """;

    private static final List<SkillPreset> PRESETS = List.of(
            new SkillPreset(
                    "BREAKING_FLASH",
                    "突发快评",
                    "快讯爆款",
                    "切身利害爆点切入 + 深挖信息差支点 + 牌桌洗牌话题，拒绝快讯复述，抢占引用与高权评论",
                    "140–240 字",
                    """
                    【当前采用预置 Skill：突发重磅快评（建议篇幅约 140–240 汉字）】
                    - 第 1 段（1 句）：不念官宣标题！直接挑明这次动作最狠的潜台词、牌桌冲击或对一线用户的直接利害（把谁做了什么自然嵌在判断里带出，一眼钩住停留）。
                    - 第 2 段（2–3 句）：从深度研究资料与一手原帖中抓出 1–2 个最反直觉或最有杀伤力的硬核细节/数据反差做支撑，补上光看原帖看不到的信息差，删掉一切修饰性废话。
                    - 第 3 段（1–2 句）：落在一个锋利但克制的现实预判或取舍话题上（谁的工作流立刻变天 / 哪类竞品最难受），制造天然的引用转发与评论讨论欲。
                    """
            ),
            new SkillPreset(
                    "DEEP_BREAKDOWN",
                    "深度拆解",
                    "高藏高转",
                    "刺破表面共识 + 深挖外部资料与底层账本 + 隐性边界话题，适合技术发布与商业复盘长帖（拉满收藏与复制链接 20x 权重）",
                    "350–680 字",
                    """
                    【当前采用预置 Skill：深度拆解长文（建议篇幅约 350–680 汉字）】
                    - 开篇立论：首句直接刺破表象共识（多数人只盯着表面热闹或官宣参数，真正决定成败的隐藏变量其实在另一处），绝不做长文摘要式开场。
                    - 信息差拆解：结合深度研究外部网页摘录与一手素材，按逻辑递进拆透 2–3 个底层机制、真实成本账或竞品横向对比，每一点必须有具体事实与细节支撑，不凑空泛小标题、不按原帖顺序复述。
                    - 边界与代价：点破一个官方不会主动强调的前提条件、适用边界或潜在迁移代价，立住一线专业可信度。
                    - 话题收束：用一句有锋芒、可复用的判断准则收尾，让读者既想收藏备查，又想直接复制链接发给同事讨论。
                    """
            ),
            new SkillPreset(
                    "SHARP_OPINION",
                    "犀利观点",
                    "高评论互动",
                    "鲜明亮出非共识判断 + 深挖证据反杀 + 可站队分歧切口，精准撬动 X 算法高权重深度回复（5–75x）",
                    "160–280 字",
                    """
                    【当前采用预置 Skill：犀利观点促评（建议篇幅约 160–280 汉字）】
                    - 首句亮剑：直接给出明确、有棱角但不偏激的非共识判断（泼冷水、挑破伪命题或指出被低估的真杀招），绝不四平八稳和稀泥。
                    - 证据支点：调取深度研究资料与原帖中的核心事实或数据作为论据，一针见血指出惯常看法漏掉了哪个关键变量。
                    - 站队切口收尾：最后一句落在行业里真实存在的路线分歧、成本取舍或判断分界线上，让不同立场的同行都想在评论区摆观点辩论。
                    """
            ),
            new SkillPreset(
                    "ACTIONABLE_LIST",
                    "干货清单",
                    "高收藏转化",
                    "痛点反差切入 + 融合深挖细节的去水分实操清单 + 避坑话题锚点，高收藏高转发转化",
                    "200–420 字",
                    """
                    【当前采用预置 Skill：结构化干货清单（建议篇幅约 200–420 汉字）】
                    - 首句痛点/反差：禁止写“今天总结几个要点”；直接点明这组实操能帮哪类人避开什么常见大坑、省掉什么真金白银或弯路。
                    - 硬核提纯主体：融合深度研究补充细节与原帖干货，用清晰短条目（1. 2. 3. 或 ·）只保留具体可执行动作、关键参数或排坑结论；只有几条真干货就写几条，绝不把原文废话拆条注水凑数。
                    - 讨论锚点收尾：一句话点明最适合立刻上手的场景或最容易翻车的边界，激发同路人收藏并补充实战体感。
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
