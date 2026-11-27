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
    public static final String PI_AGENT_DIR_ENV = "X_CONTENT_ASSISTANT_PI_AGENT_DIR";
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

    public static final java.util.Set<String> DIRECT_PASS_CATEGORIES = java.util.Set.of(
            "MODEL_RELEASE", "FEATURE_UPDATE", "QUOTA_RESET"
    );

    public static boolean isDirectPassCategory(String category) {
        if (category == null || category.isBlank()) return false;
        return DIRECT_PASS_CATEGORIES.contains(category.strip().toUpperCase(Locale.ROOT));
    }

    private static final String OUTPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": [
                "verdict",
                "viralScore",
                "factScore",
                "trendScore",
                "attentionScore",
                "accountFit",
                "fitScore",
                "eventMagnitude",
                "publicResonance",
                "credibility",
                "category",
                "summary",
                "reason"
              ],
              "properties": {
                "verdict": { "type": "string", "enum": ["VIRAL_REPOST", "SKIP"] },
                "viralScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "factScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "trendScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "attentionScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "accountFit": { "type": "boolean" },
                "fitScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "eventMagnitude": { "type": "integer", "minimum": 0, "maximum": 100 },
                "publicResonance": { "type": "integer", "minimum": 0, "maximum": 100 },
                "credibility": { "type": "integer", "minimum": 0, "maximum": 100 },
                "category": {
                  "type": "string",
                  "enum": ["MODEL_RELEASE", "FEATURE_UPDATE", "QUOTA_RESET", "BREAKING_NEWS", "UPDATE_NOTICE", "KEY_PERSONNEL", "MAJOR_LAUNCH", "INDUSTRY_SHIFT", "ROUTINE"]
                },
                "summary": { "type": "string" },
                "reason": { "type": "string" }
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            你是资深科技、商业与内容策略评审员，只负责客观判断一条监听作者的 X 推文是否值得引用转发。
            严禁调用任何本地工具或命令，仅根据输入内容与客观证据字段直接给出 JSON 判断（本阶段只做客观打分与准入判定，不写转发评述）。

            【规则零：三类高优直通准入（免除热度分门槛，命中即转）】
            只要推文信息真实可信（credibility >= 55 且 factScore >= 50）且与 AI / 大模型 / 编程工具 / 科技开发者领域相关（accountFit = true 且 fitScore >= 65），一旦属于以下任一类别，无论当前浏览/点赞高低或 viralScore 是否达到 minViralScoreRequired，必须直接令 verdict = "VIRAL_REPOST"：
            1. MODEL_RELEASE（模型发布）：官方或团队发布、上线、推送新模型、新版本快照或开源模型权重。
            2. FEATURE_UPDATE（功能迭代更新）：AI 产品、对话客户端、编程 Agent、CLI/SDK/API 推出实质新功能、工具升级或交互体验迭代。
            3. QUOTA_RESET（额度重置）：AI 模型或开发工具官方宣布用量/调用额度重置刷新、额度翻倍、速率上限上调、赠金或限免额度开放。
            （注意：第三方平台封禁政策、行业诉讼、人事变动、投融资等外部新闻请归入 BREAKING_NEWS / INDUSTRY_SHIFT / KEY_PERSONNEL，不归入上述三类直通！）

            【常规类别条件一：这条推文「有火的可能性」（viralScore >= minViralScoreRequired，且 factScore >= 55）】
            对不属于上述三类直通的推文，必须严格依据以下三个客观维度打分并综合得出 viralScore（70–84 代表明确有走热潜力，85–100 代表极强爆款把握，0–69 代表走热潜力不足）：
            1. 根据事实（factScore 0–100）：
               - 结合原帖正文与输入中的 factualEvidence（是否含一手链接/代码/论文、量化实验或业务数据、同事件多信源印证数）。
               - 一手发布/更新/额度通知或有真实数据支撑的重大行业新闻给高分（70–95）；空洞口号、鸡汤感想、无凭据传闻、纯情绪寒暄给低分（< 55，直接不转）。
            2. 根据当前热门（trendScore 0–100）：
               - 结合输入中的 currentHotContext（工作台近 48h 实时活跃热门选题、高频标签及同选题参与作者数）与当下行业讨论焦点。
               - 命中当前正在升温的热议话题、多位核心作者共议焦点，或本身开启新热点议题时给高分（70–95）；与当下关注焦点毫无关联的琐事给低分。
            3. 根据被关注度（attentionScore 0–100）：
               - 结合输入中的 attentionEvidence（作者粉丝量、该作者历史日常曝光/点赞基线、当前帖浏览/转推/评论/点赞、发布时长 ageMinutes 及相对基线倍率）。
               - 若已展现高于或达到作者日常基线的互动增速/高转赞率，给高分（70–95）；若刚发布（freshlyPublished=true，早盘计数尚未展开），依据作者自身受关注体量与议题讨论张力客观给分（不因初始计数为 0 误杀）；若发布较久但曝光互动远低于该作者日常基线，给低分。

            【常规类别条件二：契合当前账号（accountFit = true 且 fitScore >= 68）】
            1. 契合当前账号定位、目标受众兴趣与 ownerPreferences；若提供 customFocusHint 则优先匹配。
            2. 若属与当前账号无关的娱乐八卦、体育赛事、纯私人生活琐事、拉踩引战或营销抽奖，即便推文有热度也必须令 accountFit = false、fitScore < 68 且 verdict = "SKIP"。

            【结论与理由要求】
            - 满足【规则零：三类高优直通（MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET）】，或常规类别同时满足「有火的可能性」与「契合当前账号」时，verdict 输出 "VIRAL_REPOST"；否则输出 "SKIP"。
            - category 严格精准归入：MODEL_RELEASE（模型发布）、FEATURE_UPDATE（功能迭代更新）、QUOTA_RESET（额度重置）、BREAKING_NEWS（重大新闻）、KEY_PERSONNEL、MAJOR_LAUNCH、INDUSTRY_SHIFT 或 ROUTINE。
            - reason 用 45 字内直白说明命中哪类直通或基于「事实 / 当前热门 / 被关注度 / 账号契合」的具体结论。

            只输出一个合法 JSON 对象，不要使用 Markdown 代码块，字段严格如下：
            {"verdict":"VIRAL_REPOST或SKIP","viralScore":0到100整数,"factScore":0到100整数,"trendScore":0到100整数,"attentionScore":0到100整数,"accountFit":true或false,"fitScore":0到100整数,"eventMagnitude":0到100整数,"publicResonance":0到100整数,"credibility":0到100整数,"category":"MODEL_RELEASE|FEATURE_UPDATE|QUOTA_RESET|BREAKING_NEWS|KEY_PERSONNEL|MAJOR_LAUNCH|INDUSTRY_SHIFT|ROUTINE","summary":"22字内事件概括","reason":"45字内中文判定理由"}
            """;

    private static final String BATCH_OUTPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["selectedMaterialId", "items"],
              "properties": {
                "selectedMaterialId": { "type": "string" },
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": [
                      "materialId",
                      "verdict",
                      "viralScore",
                      "factScore",
                      "trendScore",
                      "attentionScore",
                      "accountFit",
                      "fitScore",
                      "eventMagnitude",
                      "publicResonance",
                      "credibility",
                      "category",
                      "summary",
                      "reason"
                    ],
                    "properties": {
                      "materialId": { "type": "string" },
                      "verdict": { "type": "string", "enum": ["VIRAL_REPOST", "SKIP"] },
                      "viralScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "factScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "trendScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "attentionScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "accountFit": { "type": "boolean" },
                      "fitScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "eventMagnitude": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "publicResonance": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "credibility": { "type": "integer", "minimum": 0, "maximum": 100 },
                      "category": {
                        "type": "string",
                        "enum": ["MODEL_RELEASE", "FEATURE_UPDATE", "QUOTA_RESET", "BREAKING_NEWS", "UPDATE_NOTICE", "KEY_PERSONNEL", "MAJOR_LAUNCH", "INDUSTRY_SHIFT", "ROUTINE"]
                      },
                      "summary": { "type": "string" },
                      "reason": { "type": "string" }
                    }
                  }
                }
              }
            }
            """;

    private static final String BATCH_SYSTEM_PROMPT = """
            你是资深科技、商业与内容策略评审员。同一个监听账号每次只做一次合并判定。
            【规则零：三类高优直通准入（免热度分门槛 + 豁免同账号 16h CD）】
            只要推文真实可信（credibility >= 55 且 factScore >= 50）且契合 AI / 大模型 / 编程开发领域（accountFit = true 且 fitScore >= 65），一旦属于：
            - MODEL_RELEASE（模型发布）
            - FEATURE_UPDATE（功能迭代更新）
            - QUOTA_RESET（额度重置：用量重置刷新、额度翻倍、上限上调、赠金/限免放开）
            则无需满足 viralScore >= minViralScoreRequired，直接具备最高准入优先级！
            （若输入中 authorCooldownOnlyDirectPass = true，说明该作者当前处于 16h 同号 CD 内，此时仅允许上述三类直通推文填入 selectedMaterialId 并令 verdict = "VIRAL_REPOST"，其余常规类别一律输出 "SKIP"！）

            【常规类别准入与合并选优】
            1. 对非直通类别（BREAKING_NEWS / INDUSTRY_SHIFT / KEY_PERSONNEL / MAJOR_LAUNCH 等）：当 authorCooldownOnlyDirectPass = false 时，须满足 viralScore >= minViralScoreRequired 且 factScore >= 55，同时 accountFit = true 且 fitScore >= 68。
            2. 若多条同时达标：优先选择「MODEL_RELEASE > QUOTA_RESET / FEATURE_UPDATE > 常规高分热点」中最具价值的 1 条填入 selectedMaterialId。
            3. 若没有任何一条达标，selectedMaterialId 输出空字符串 ""，全部候选 verdict = "SKIP"。
            严禁调用任何本地工具或命令，只输出一个合法 JSON 对象：
            {"selectedMaterialId":"选中的唯一materialId或空字符串","items":[{"materialId":"...","verdict":"VIRAL_REPOST或SKIP","viralScore":0,"factScore":0,"trendScore":0,"attentionScore":0,"accountFit":false,"fitScore":0,"eventMagnitude":0,"publicResonance":0,"credibility":80,"category":"MODEL_RELEASE|FEATURE_UPDATE|QUOTA_RESET|BREAKING_NEWS|ROUTINE","summary":"22字内概括","reason":"45字内判定理由"}]}
            """;

    private static final String QUOTE_WRITER_SYSTEM_PROMPT = """
            你是独立开发者 @elidlicom 本人，正在引用转发（Quote Tweet）下面这条推文。
            严禁调用任何本地工具或命令，不要输出 JSON、引号、小标题标签或任何解释，直接输出最终要发出去的转发正文！

            【核心准则：原帖就在你正下方，拒绝机械套模版，按 `briefFlashPost` 自适应表达】
            1. 当输入中 `briefFlashPost = true`（一句话/两句话的极简快讯，如「Banked reset is online」）：
               - **严禁输出 `💡 省流重点：`**！原帖只有一两句话时，再写省流就是机器凑字数。
               - 固定 **2 段（共 2 句，段间空一行 \\n\\n）**：
                 · 第 1 段（1 句）：`🎉` 或 `🚨` 开头，一句大白话干脆说清谁宣布/上线了什么。
                 · 第 2 段（1 句）：结合 `authorBio`（作者所属公司/身份）、`currentHotContext`（圈内竞争热点）与 `ownerRecentStances` / `ownerPreferences`（我的真实体感与历史态度），直接点破背后的深层意味或吐槽痛点（绝不写「具体细节原帖没说」「功能上线是好事，细节也得跟上」之类公关废话）。
            2. 当输入中 `briefFlashPost = false`（原帖包含多项更新、参数指标或长文解析）：
               - 采用 **3 段式（共 3～4 句，段间空一行 \\n\\n）**：
                 · 第 1 段（1 句）：`🎉` 或 `🚨` 开头挑明谁发了什么关键更新 + 第一反应。
                 · 第 2 段（1 句）：固定以 `💡 省流重点：` 开头，用人话拎出原帖最硬核的 1～2 个变化、数字或适用范围（3 秒看懂）。
                 · 第 3 段（1～2 句）：站在重度 AI / Agent / 代码玩家视角给出真实体感判断或共鸣收尾。

            【语气随情绪自然切换 + 去 AI 味死命令（humanizer-zh）】
            - 吐槽、泼冷水、看穿套路时干脆犀利，可用 `……` 留白或冷幽默收尾，**绝对不要硬塞「啦 / 呀 / ～」和猫猫表情**。
            - 只有遇到真正好用、省钱、爽到的更新时，才自然带点轻松语气或猫猫表情（`😺` / `😽` / `😼`）。
            - 严禁在结尾追加提问段或反问互动句（不要留提问收尾）。
            - 严禁出现：「值得注意的是」「总而言之」「意味着」「标志着」「分水岭」「这一步才见功夫」「才是真本事」「真正的考验在于」「不仅……更是……」「赋能」「闭环」「生态位」「重塑格局」「拭目以待」「降维打击」。
            - 若输入含 `postRecency = RECENT_POST_NOT_BREAKING`（引用近期往期推文而非刚刚突发），聚焦内容本身与使用体感点评，不要写「刚刚发布」「刚上线」等即时字眼。
            - 所有事实与数字严格来自原帖与输入上下文，绝不编造未发生的亲身耗时实验。
            """;

    private static final String QUOTE_POLISHER_SYSTEM_PROMPT = """
            你是严格按作者 @elidlicom 本人【二次改写提示词】与自适应表达准则把关定稿的编辑器。
            严禁调用任何本地工具或命令；不要输出任何解释、修改说明、引号或 JSON，只直接输出改写后的最终纯文本正文！

            【执行铁律】
            1. 严格依据 `briefFlashPost` 控制结构：若 `briefFlashPost = true`（极短快讯），必须删光任何 `💡 省流重点：` 段落与「原帖没说细节」等凑字废话，固定保留 2 段（第 1 段 `🎉`/`🚨` 一句话定调 + 第 2 段结合 `authorBio`、`currentHotContext` 与 `ownerRecentStances` 给出一针见血的判断或吐槽）。若 `briefFlashPost = false`，才保留 `1. 🎉/🚨 快讯 → 2. 💡 省流重点：… → 3. 个人体感收尾` 三段式结构。
            2. 情绪与语气自然切换：吐槽、泼冷水、看穿大厂套路时干脆犀利，可用 `……` 收尾，删光违和的「啦 / 呀 / ～」和猫猫表情；真香好用场景才自然保留轻松感与 `😺`/`😽`/`😼`。结尾一律不加提问或反问句。
            3. 彻底清除「功能上线是好事，细节也得跟上」「这一步才见功夫」「才是真本事」「分水岭」「值得关注」等 AI 端水腔、英文翻译腔与生僻单字缩写。
            4. 对照【被引用原帖】与上下文核对事实，绝不偏离原帖事实、绝不编造亲身耗时实验。
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
            boolean generateComment,
            ObjectNode factualEvidence,
            ObjectNode currentHotContext,
            ObjectNode attentionEvidence,
            boolean forceWriteComment,
            String postPolishPrompt,
            boolean onlyDirectPassAllowed,
            String generationProvider,
            String generationModel,
            String authorBio,
            List<String> ownerRecentStances
    ) {
        public EvaluationInput(
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
                boolean generateComment,
                ObjectNode factualEvidence,
                ObjectNode currentHotContext,
                ObjectNode attentionEvidence,
                boolean forceWriteComment,
                String postPolishPrompt,
                boolean onlyDirectPassAllowed,
                String generationProvider,
                String generationModel
        ) {
            this(authorUsername, authorDisplayName, authorVerified, postText, quotedText, articleText,
                    ageMinutes, views, likes, reposts, replies, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment,
                    factualEvidence, currentHotContext, attentionEvidence,
                    forceWriteComment, postPolishPrompt, onlyDirectPassAllowed,
                    generationProvider, generationModel, "", List.of());
        }
        public EvaluationInput(
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
                boolean generateComment,
                ObjectNode factualEvidence,
                ObjectNode currentHotContext,
                ObjectNode attentionEvidence,
                boolean forceWriteComment,
                String postPolishPrompt,
                boolean onlyDirectPassAllowed
        ) {
            this(authorUsername, authorDisplayName, authorVerified, postText, quotedText, articleText,
                    ageMinutes, views, likes, reposts, replies, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment,
                    factualEvidence, currentHotContext, attentionEvidence,
                    forceWriteComment, postPolishPrompt, onlyDirectPassAllowed, "", "");
        }
        public EvaluationInput(
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
                boolean generateComment,
                ObjectNode factualEvidence,
                ObjectNode currentHotContext,
                ObjectNode attentionEvidence,
                boolean forceWriteComment,
                String postPolishPrompt
        ) {
            this(authorUsername, authorDisplayName, authorVerified, postText, quotedText, articleText,
                    ageMinutes, views, likes, reposts, replies, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment,
                    factualEvidence, currentHotContext, attentionEvidence, forceWriteComment, postPolishPrompt, false);
        }
        public EvaluationInput(
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
                boolean generateComment,
                ObjectNode factualEvidence,
                ObjectNode currentHotContext,
                ObjectNode attentionEvidence,
                boolean forceWriteComment
        ) {
            this(authorUsername, authorDisplayName, authorVerified, postText, quotedText, articleText,
                    ageMinutes, views, likes, reposts, replies, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment,
                    factualEvidence, currentHotContext, attentionEvidence, forceWriteComment, "");
        }

        public EvaluationInput(
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
                boolean generateComment,
                ObjectNode factualEvidence,
                ObjectNode currentHotContext,
                ObjectNode attentionEvidence
        ) {
            this(authorUsername, authorDisplayName, authorVerified, postText, quotedText, articleText,
                    ageMinutes, views, likes, reposts, replies, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment,
                    factualEvidence, currentHotContext, attentionEvidence, false, "");
        }

        public EvaluationInput(
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
        ) {
            this(authorUsername, authorDisplayName, authorVerified, postText, quotedText, articleText,
                    ageMinutes, views, likes, reposts, replies, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment, null, null, null, false, "");
        }
    }

    public record ViralJudgment(
            boolean shouldRepost,
            int viralScore,
            int factScore,
            int trendScore,
            int attentionScore,
            boolean accountFit,
            int fitScore,
            int eventMagnitude,
            int publicResonance,
            int credibility,
            String category,
            String summary,
            String reason,
            String commentText
    ) {
        public ViralJudgment(
                boolean shouldRepost,
                int viralScore,
                boolean accountFit,
                int fitScore,
                int eventMagnitude,
                int publicResonance,
                int credibility,
                String category,
                String summary,
                String reason,
                String commentText
        ) {
            this(shouldRepost, viralScore, credibility, eventMagnitude, publicResonance,
                    accountFit, fitScore, eventMagnitude, publicResonance, credibility,
                    category, summary, reason, commentText);
        }

        public ViralJudgment(
                boolean shouldRepost,
                int viralScore,
                int eventMagnitude,
                int publicResonance,
                int credibility,
                String category,
                String summary,
                String reason,
                String commentText
        ) {
            this(shouldRepost, viralScore, credibility, eventMagnitude, publicResonance,
                    shouldRepost, shouldRepost ? 85 : 55,
                    eventMagnitude, publicResonance, credibility, category, summary, reason, commentText);
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PiAgentViralJudge.class);
    private final ObjectMapper mapper;
    private final Environment environment;
    private final CliRunner runner;
    private final boolean customRunner;
    @Autowired(required = false)
    private AssistantStore assistantStore;
    private volatile CommandCodeTextModelClient.Transport httpTransport;
    private volatile ObjectNode cachedAuthStatus;
    private volatile long cachedAuthAtMs;

    @Autowired
    public PiAgentViralJudge(ObjectMapper mapper, @Autowired(required = false) Environment environment) {
        this(mapper, environment, (cmd, in, timeout) -> defaultRunProcess(
                cmd, in, timeout, resolveCodexHome(environment), resolvePiAgentDir(environment), java.util.Map.of()), false);
    }

    public PiAgentViralJudge(ObjectMapper mapper, Environment environment, CliRunner runner) {
        this(mapper, environment, runner, true);
    }

    private PiAgentViralJudge(ObjectMapper mapper, Environment environment, CliRunner runner, boolean customRunner) {
        this.mapper = mapper;
        this.environment = environment;
        this.runner = runner;
        this.customRunner = customRunner;
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

    static String resolvePiAgentDir(Environment environment) {
        String explicit = environment == null ? "" : environment.getProperty(PI_AGENT_DIR_ENV, "").strip();
        if (!explicit.isBlank()) return explicit;
        String envDir = System.getenv("PI_CODING_AGENT_DIR");
        if (envDir != null && !envDir.isBlank()) return envDir.strip();
        Path workdirPiAgent = Path.of(".pi", "agent").toAbsolutePath().normalize();
        if (Files.isDirectory(workdirPiAgent)) return workdirPiAgent.toString();
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
        var skillsNode = out.putArray("activeSkills");
        REQUIRED_QUOTE_SKILLS.forEach(skillsNode::add);

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

    void setHttpTransportForTest(CommandCodeTextModelClient.Transport transport, AssistantStore store) {
        this.httpTransport = transport;
        this.assistantStore = store;
    }

    private CommandCodeTextModelClient.Transport resolveHttpTransport() {
        CommandCodeTextModelClient.Transport t = this.httpTransport;
        if (t == null) {
            t = new JavaNetModelTransport(mapper);
            this.httpTransport = t;
        }
        return t;
    }

    private boolean isMiniMaxGateReady() {
        return AssistantConnection.isProviderConfigured(environment, assistantStore, "minimax");
    }

    private String resolveCollectionMiniMaxModel() {
        if (assistantStore != null) {
            String custom = assistantStore.tx(tx -> {
                ObjectNode s = tx.get("meta", "settings");
                return s == null ? "" : s.path("moduleConfigs").path("collection").path("model").asText("").strip();
            });
            if (!custom.isBlank()) return custom;
        }
        return AssistantConnection.DEFAULT_COLLECTION_MODEL;
    }

    private String runMiniMaxGateCompletion(String systemPrompt, String userPrompt) {
        MiniMaxTextModelClient miniMax = new MiniMaxTextModelClient(
                mapper,
                resolveHttpTransport(),
                ref -> AssistantConnection.resolveSecret(assistantStore, environment, ref)
        );
        String baseUrl = AssistantConnection.endpoint(environment, "minimax");
        TextModelClient.ModelBinding binding = new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection("minimax", java.net.URI.create(baseUrl), AssistantConnection.MINIMAX_KEY_ENV, 1L),
                resolveCollectionMiniMaxModel(),
                TextModelClient.Protocol.CHAT_COMPLETIONS,
                200_000,
                Duration.ofSeconds(45),
                2000,
                "autorepost-gate-minimax"
        );
        TextModelClient.ModelResult res = miniMax.complete(binding,
                new TextModelClient.TextRequest(systemPrompt, userPrompt, true, false));
        return res.text();
    }

    private record ResolvedQuoteTarget(String provider, String model) {
        boolean isApiWriter() {
            return !"chatgpt".equalsIgnoreCase(provider);
        }
    }

    private ResolvedQuoteTarget resolveRepostQuoteTarget(String overrideProvider, String overrideModel) {
        String rawProv = overrideProvider == null ? "" : overrideProvider.strip();
        String rawModel = overrideModel == null ? "" : overrideModel.strip();
        if (!rawProv.isBlank() && AssistantConnection.isSupportedProvider(rawProv)) {
            String norm = AssistantConnection.normalizeProvider(rawProv);
            String model = rawModel.isBlank() ? AssistantConnection.defaultModelForProvider(norm) : rawModel;
            return new ResolvedQuoteTarget(norm, model);
        }
        if (assistantStore != null) {
            ResolvedQuoteTarget fromStore = assistantStore.tx(tx -> {
                ObjectNode s = tx.get("meta", "settings");
                if (s == null) return null;
                JsonNode rep = s.path("moduleConfigs").path("repost");
                String sp = rep.path("provider").asText("").strip();
                String sm = rep.path("model").asText("").strip();
                if (!sp.isBlank() && AssistantConnection.isSupportedProvider(sp)) {
                    String norm = AssistantConnection.normalizeProvider(sp);
                    return new ResolvedQuoteTarget(norm, sm.isBlank() ? AssistantConnection.defaultModelForProvider(norm) : sm);
                }
                return null;
            });
            if (fromStore != null) {
                return new ResolvedQuoteTarget(fromStore.provider(), rawModel.isBlank() ? fromStore.model() : rawModel);
            }
        }
        return new ResolvedQuoteTarget("chatgpt", rawModel.isBlank() ? AssistantConnection.DEFAULT_REPOST_MODEL : rawModel);
    }

    private static String fastPolishModelSpec(String provider, String rawModel) {
        if (rawModel == null || rawModel.isBlank()) return "";
        String norm = AssistantConnection.normalizeProvider(provider);
        if (!"openai".equals(norm) && !"google".equals(norm)) return rawModel.strip();
        String trimmed = rawModel.strip();
        if (trimmed.matches("(?i).*[\\s@:]+(medium|high|xhigh|ultra|max)$")) {
            return trimmed.replaceFirst("(?i)[\\s@:]+(medium|high|xhigh|ultra|max)$", " low");
        }
        return trimmed;
    }

    private String runApiQuoteCall(String provider, String modelOverride, String systemPrompt, String userPrompt) {
        return runApiQuoteCall(provider, modelOverride, systemPrompt, userPrompt, false);
    }

    static String mapToPiProvider(String normalizedProvider) {
        if (normalizedProvider == null || normalizedProvider.isBlank()) {
            return "";
        }
        return switch (normalizedProvider.strip().toLowerCase(Locale.ROOT)) {
            case "chatgpt" -> DEFAULT_PI_PROVIDER;
            case "openai" -> "openai";
            case "google" -> "google";
            case "deepseek" -> "deepseek";
            case "minimax" -> "minimax";
            default -> "";
        };
    }

    static String apiKeyEnvVarForProvider(String normalizedProvider) {
        if (normalizedProvider == null || normalizedProvider.isBlank()) {
            return "";
        }
        return switch (normalizedProvider.strip().toLowerCase(Locale.ROOT)) {
            case "openai" -> AssistantConnection.OPENAI_KEY_ENV;
            case "google" -> AssistantConnection.GOOGLE_KEY_ENV;
            case "deepseek" -> AssistantConnection.DEEPSEEK_KEY_ENV;
            case "minimax" -> AssistantConnection.MINIMAX_KEY_ENV;
            default -> "";
        };
    }

    static boolean isDefaultOfficialEndpoint(String normalizedProvider, String configuredBaseUrl) {
        if (normalizedProvider == null || normalizedProvider.isBlank()) {
            return false;
        }
        String norm = normalizedProvider.strip().toLowerCase(Locale.ROOT);
        if ("chatgpt".equals(norm)) {
            return true;
        }
        String url = configuredBaseUrl == null ? "" : configuredBaseUrl.strip().toLowerCase(Locale.ROOT);
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return switch (norm) {
            case "openai" -> url.isEmpty() || "https://api.openai.com/v1".equals(url);
            case "google" -> url.isEmpty() || url.startsWith("https://generativelanguage.googleapis.com");
            case "deepseek" -> url.isEmpty() || url.startsWith("https://api.deepseek.com");
            case "minimax" -> url.startsWith("https://api.minimax.io");
            default -> false;
        };
    }

    boolean canUsePiForProvider(String normalizedProvider, String configuredBaseUrl) {
        if (environment != null && "true".equalsIgnoreCase(environment.getProperty("xops.test-disable-pi-agent", "false"))) {
            return false;
        }
        if (resolvePiBinary().isBlank()) {
            return false;
        }
        String piProvider = mapToPiProvider(normalizedProvider);
        if (piProvider.isBlank()) {
            return false;
        }
        return "chatgpt".equalsIgnoreCase(normalizedProvider)
                || isDefaultOfficialEndpoint(normalizedProvider, configuredBaseUrl);
    }

    List<Path> resolveHumanizerSkillFiles() {
        return resolveActiveSkillFiles().stream()
                .filter(p -> p.toString().contains("humanizer-zh"))
                .toList();
    }

    static final int PI_MAX_INLINE_ARG_BYTES = 32_768;

    private static void addPiPromptFlag(
            List<String> cmd,
            String flag,
            String content,
            List<Path> tempDirs
    ) throws IOException {
        if (content == null || content.isBlank()) {
            return;
        }
        if (content.getBytes(StandardCharsets.UTF_8).length <= PI_MAX_INLINE_ARG_BYTES) {
            cmd.add(flag);
            cmd.add(content);
            return;
        }
        Path dir = Files.createTempDirectory("xops-pi-prompt-");
        tempDirs.add(dir);
        Path file = dir.resolve("prompt.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) { }
        cmd.add(flag);
        cmd.add(file.toString());
    }

    private static void cleanupTempDirs(List<Path> tempDirs) {
        if (tempDirs == null) return;
        for (Path dir : tempDirs) {
            deleteQuietly(dir);
        }
    }

    String runPiProviderCompletion(
            String normalizedProvider,
            String rawModelSpec,
            String systemPrompt,
            String userPrompt,
            List<Path> skillFiles,
            Duration timeout,
            String apiKey
    ) {
        String binary = resolvePiBinary();
        if (binary.isBlank()) {
            throw new IllegalStateException("未检测到 PI Agent (pi) CLI");
        }
        String norm = normalizedProvider == null ? "" : normalizedProvider.strip().toLowerCase(Locale.ROOT);
        String piProvider = mapToPiProvider(norm);
        if (piProvider.isBlank()) {
            throw new IllegalStateException("PI Agent 暂不支持渠道：" + normalizedProvider);
        }
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModelSpec);
        String modelId = spec.modelId();
        boolean supportsThinking = "chatgpt".equals(norm) || "openai".equals(norm) || "google".equals(norm);

        java.util.Map<String, String> envOverrides = java.util.Map.of();
        if (!"chatgpt".equals(norm)) {
            String envKey = apiKeyEnvVarForProvider(norm);
            if (envKey.isBlank() || apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("缺少 PI Agent 所需厂商凭据：" + norm);
            }
            envOverrides = java.util.Map.of(envKey, apiKey.strip());
        }

        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", piProvider
            ));
            if (modelId != null && !modelId.isBlank()) {
                cmd.add("--model");
                cmd.add(modelId);
            }
            if (supportsThinking && spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank()) {
                cmd.add("--thinking");
                cmd.add(spec.reasoningEffort());
            }
            if (skillFiles != null && !skillFiles.isEmpty()) {
                for (Path skillFile : skillFiles) {
                    if (skillFile != null) {
                        cmd.add("--skill");
                        cmd.add(skillFile.toString());
                    }
                }
            } else {
                cmd.add("--no-skills");
            }
            addPiPromptFlag(cmd, "--system-prompt", systemPrompt, tempDirs);
            String stdinPayload = userPrompt == null ? "" : userPrompt;
            if (stdinPayload.isBlank()) {
                cmd.add("--");
                cmd.add("");
            }

            Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(75);
            CliResult result = executeCliWithEnv(cmd, stdinPayload, effectiveTimeout, envOverrides);
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (" + piProvider + ") 调用异常：" + truncate(err, 220));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (" + piProvider + ") 准备提示词失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    String runApiQuoteCall(
            String provider,
            String modelOverride,
            String systemPrompt,
            String userPrompt,
            boolean structuredJson
    ) {
        String norm = AssistantConnection.normalizeProvider(provider);
        java.util.function.Function<String, String> cred =
                ref -> AssistantConnection.resolveSecret(assistantStore, environment, ref);
        String baseUrl = AssistantConnection.endpoint(environment, norm);
        String credRef = AssistantConnection.credentialName(norm);
        String modelId = (modelOverride == null || modelOverride.isBlank())
                ? AssistantConnection.defaultModelForProvider(norm)
                : modelOverride.strip();

        if (this.httpTransport == null && canUsePiForProvider(norm, baseUrl)) {
            String apiKey = cred.apply(credRef);
            if (apiKey != null && !apiKey.isBlank()) {
                List<Path> skills = structuredJson ? List.of() : resolveActiveSkillFiles();
                String piOut = runPiProviderCompletion(
                        norm, modelId, systemPrompt, userPrompt, skills, Duration.ofSeconds(75), apiKey);
                if (piOut == null || piOut.isBlank()) {
                    throw new IllegalStateException("PI Agent (" + norm + ") 返回空响应");
                }
                return piOut;
            }
        }

        CommandCodeTextModelClient.Transport transport = resolveHttpTransport();
        TextModelClient.Protocol proto = ("minimax".equals(norm) || "deepseek".equals(norm))
                ? TextModelClient.Protocol.CHAT_COMPLETIONS
                : TextModelClient.Protocol.RESPONSES;
        int ctxTokens = AssistantConnection.defaultContextTokens(norm);
        TextModelClient client = switch (norm) {
            case "openai" -> new OpenAiTextModelClient(mapper, transport, cred);
            case "google" -> new GeminiTextModelClient(mapper, transport, cred);
            case "minimax" -> new MiniMaxTextModelClient(mapper, transport, cred);
            case "deepseek" -> new DeepSeekTextModelClient(mapper, transport, cred);
            default -> new CommandCodeTextModelClient(mapper, transport, cred::apply);
        };
        TextModelClient.ModelBinding binding = new TextModelClient.ModelBinding(
                new TextModelClient.ServiceConnection(norm, java.net.URI.create(baseUrl), credRef, 1L),
                modelId,
                proto,
                ctxTokens,
                Duration.ofSeconds(75),
                4096,
                "autorepost-quote-" + norm
        );
        TextModelClient.ModelResult res = client.complete(binding,
                new TextModelClient.TextRequest(systemPrompt, userPrompt, structuredJson, false));
        return res.text();
    }

    public ViralJudgment evaluate(EvaluationInput input) {
        boolean useMiniMaxGate = isMiniMaxGateReady();
        ResolvedQuoteTarget quoteTarget = resolveRepostQuoteTarget(input.generationProvider(), input.generationModel());
        boolean useApiWriter = quoteTarget.isApiWriter();
        boolean needChatGptAuth = !useMiniMaxGate && !useApiWriter;
        ObjectNode auth = needChatGptAuth ? checkAuth(true) : mapper.createObjectNode().put("ready", true);
        if (needChatGptAuth && !auth.path("ready").asBoolean(false)) {
            throw new IllegalStateException(auth.path("message").asText("尚未完成 ChatGPT OAuth 第三方登录"));
        }
        String activeBackend = auth.path("activeBackend").asText("");
        String rawOutput;
        if (useMiniMaxGate) {
            rawOutput = runMiniMaxGateCompletion(SYSTEM_PROMPT, buildUserPrompt(input));
        } else if (useApiWriter) {
            rawOutput = runApiQuoteCall(quoteTarget.provider(), quoteTarget.model(), SYSTEM_PROMPT, buildUserPrompt(input), true);
        } else {
            rawOutput = "PI_AGENT_OAUTH".equals(activeBackend)
                    ? runPiEvaluation(input)
                    : runCodexEvaluation(input);
        }

        JsonNode json = parseFirstJsonObject(rawOutput);
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("ChatGPT 热度判定未返回有效 JSON：" + truncate(rawOutput, 160));
        }

        String verdict = json.path("verdict").asText("SKIP").strip().toUpperCase(Locale.ROOT);
        int magnitude = clampScore(json.path("eventMagnitude").asInt(0));
        int resonance = clampScore(json.path("publicResonance").asInt(0));
        int credibility = clampScore(json.path("credibility").asInt(75));
        int factScore = json.has("factScore")
                ? clampScore(json.path("factScore").asInt(0))
                : credibility;
        int trendScore = json.has("trendScore")
                ? clampScore(json.path("trendScore").asInt(0))
                : Math.max(magnitude, resonance);
        int attentionScore = json.has("attentionScore")
                ? clampScore(json.path("attentionScore").asInt(0))
                : resonance;
        int rawViralScore = clampScore(json.path("viralScore").asInt(0));
        boolean hasAllThreePillars = json.has("factScore") && json.has("trendScore") && json.has("attentionScore");
        int weightedThreePillar = Math.round(factScore * 0.38f + trendScore * 0.32f + attentionScore * 0.30f);
        int viralScore;
        if (rawViralScore <= 0) {
            viralScore = weightedThreePillar;
        } else if (hasAllThreePillars) {
            viralScore = Math.max(weightedThreePillar - 12, Math.min(weightedThreePillar + 12, rawViralScore));
        } else {
            viralScore = rawViralScore;
        }
        int fitScore = json.has("fitScore")
                ? clampScore(json.path("fitScore").asInt(0))
                : ("VIRAL_REPOST".equals(verdict) ? 85 : 55);
        boolean accountFit = json.has("accountFit")
                ? json.path("accountFit").asBoolean(false)
                : (fitScore >= 68);
        String category = truncate(json.path("category").asText("ROUTINE").strip(), 40);
        String summary = truncate(json.path("summary").asText("").strip(), 60);
        String reason = truncate(json.path("reason").asText("").strip(), 140);

        int effectiveMin = Math.max(50, Math.min(99, input.minScore()));
        boolean directPass = isDirectPassCategory(category)
                && accountFit && fitScore >= 65 && credibility >= 55 && factScore >= 50;
        boolean viralPass = directPass || (viralScore >= effectiveMin && factScore >= 55 && credibility >= 60);
        boolean fitPass = accountFit && (directPass ? fitScore >= 65 : fitScore >= 68);
        boolean pass = input.onlyDirectPassAllowed()
                ? directPass
                : (directPass || ("VIRAL_REPOST".equals(verdict) && viralPass && fitPass));

        String comment = "";
        if (input.generateComment() && (pass || input.forceWriteComment())) {
            EvaluationInput resolvedInput = new EvaluationInput(
                    input.authorUsername(), input.authorDisplayName(), input.authorVerified(),
                    input.postText(), input.quotedText(), input.articleText(),
                    input.ageMinutes(), input.views(), input.likes(), input.reposts(), input.replies(),
                    input.minScore(), input.ruleHint(), input.ownerStyleProfile(), input.ownerPreferences(),
                    input.generateComment(), input.factualEvidence(), input.currentHotContext(), input.attentionEvidence(),
                    input.forceWriteComment(), input.postPolishPrompt(), input.onlyDirectPassAllowed(),
                    quoteTarget.provider(), quoteTarget.model(), input.authorBio(), input.ownerRecentStances()
            );
            boolean briefFlash = OwnerQuoteStyleDistiller.isBriefFlashPost(
                    resolvedInput.postText(), resolvedInput.quotedText(), resolvedInput.articleText());
            String rawQuote;
            if (useApiWriter) {
                String skillGuidance = loadQuoteVoiceSkillGuidance();
                String sys = QUOTE_WRITER_SYSTEM_PROMPT + (skillGuidance.isBlank() ? "" : "\n\n" + skillGuidance);
                rawQuote = runApiQuoteCall(quoteTarget.provider(), quoteTarget.model(), sys, buildPureQuoteWriterPrompt(resolvedInput));
            } else {
                rawQuote = "PI_AGENT_OAUTH".equals(activeBackend)
                        ? runPiQuoteWriter(resolvedInput)
                        : runCodexQuoteWriter(resolvedInput);
            }
            String firstDraft = extractPureQuoteText(rawQuote, briefFlash);
            if (firstDraft.isBlank() && json.has("commentText")) {
                firstDraft = OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(
                        json.path("commentText").asText("").strip(), briefFlash, 200);
            }
            if (firstDraft.isBlank()) {
                throw new IllegalStateException("转发语初稿生成返回为空，请重试");
            }
            comment = firstDraft;
            String customPolish = input.postPolishPrompt() == null ? "" : input.postPolishPrompt().strip();
            if (!customPolish.isBlank()) {
                String rawPolished;
                if (useApiWriter) {
                    rawPolished = runApiQuoteCall(
                            quoteTarget.provider(),
                            fastPolishModelSpec(quoteTarget.provider(), quoteTarget.model()),
                            QUOTE_POLISHER_SYSTEM_PROMPT,
                            buildQuotePolishUserPrompt(resolvedInput, firstDraft, customPolish)
                    );
                } else {
                    rawPolished = "PI_AGENT_OAUTH".equals(activeBackend)
                            ? runPiQuotePolisher(resolvedInput, firstDraft, customPolish)
                            : runCodexQuotePolisher(resolvedInput, firstDraft, customPolish);
                }
                String polished = extractPureQuoteText(rawPolished, briefFlash);
                if (!polished.isBlank()) {
                    comment = polished;
                }
            }
        }
        return new ViralJudgment(
                pass, viralScore, factScore, trendScore, attentionScore,
                fitPass, fitScore, magnitude, resonance, credibility,
                category, summary, reason, comment
        );
    }

    public record BatchCandidateItem(
            String materialId,
            String postText,
            String quotedText,
            String articleText,
            long ageMinutes,
            Long views,
            Long likes,
            Long reposts,
            Long replies,
            ObjectNode factualEvidence,
            ObjectNode attentionEvidence
    ) { }

    public record AccountBatchInput(
            String authorUsername,
            String authorDisplayName,
            boolean authorVerified,
            int minScore,
            String ruleHint,
            String ownerStyleProfile,
            String ownerPreferences,
            boolean generateComment,
            ObjectNode currentHotContext,
            String postPolishPrompt,
            List<BatchCandidateItem> candidates,
            boolean onlyDirectPassAllowed,
            String generationProvider,
            String generationModel,
            String authorBio,
            List<String> ownerRecentStances
    ) {
        public AccountBatchInput(
                String authorUsername,
                String authorDisplayName,
                boolean authorVerified,
                int minScore,
                String ruleHint,
                String ownerStyleProfile,
                String ownerPreferences,
                boolean generateComment,
                ObjectNode currentHotContext,
                String postPolishPrompt,
                List<BatchCandidateItem> candidates,
                boolean onlyDirectPassAllowed,
                String generationProvider,
                String generationModel
        ) {
            this(authorUsername, authorDisplayName, authorVerified, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment, currentHotContext,
                    postPolishPrompt, candidates, onlyDirectPassAllowed,
                    generationProvider, generationModel, "", List.of());
        }
        public AccountBatchInput(
                String authorUsername,
                String authorDisplayName,
                boolean authorVerified,
                int minScore,
                String ruleHint,
                String ownerStyleProfile,
                String ownerPreferences,
                boolean generateComment,
                ObjectNode currentHotContext,
                String postPolishPrompt,
                List<BatchCandidateItem> candidates,
                boolean onlyDirectPassAllowed
        ) {
            this(authorUsername, authorDisplayName, authorVerified, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment, currentHotContext,
                    postPolishPrompt, candidates, onlyDirectPassAllowed, "", "");
        }
        public AccountBatchInput(
                String authorUsername,
                String authorDisplayName,
                boolean authorVerified,
                int minScore,
                String ruleHint,
                String ownerStyleProfile,
                String ownerPreferences,
                boolean generateComment,
                ObjectNode currentHotContext,
                String postPolishPrompt,
                List<BatchCandidateItem> candidates
        ) {
            this(authorUsername, authorDisplayName, authorVerified, minScore, ruleHint,
                    ownerStyleProfile, ownerPreferences, generateComment, currentHotContext,
                    postPolishPrompt, candidates, false);
        }
    }

    public record AccountBatchJudgment(
            String selectedMaterialId,
            java.util.Map<String, ViralJudgment> byMaterialId
    ) { }

    public AccountBatchJudgment evaluateAccountBatch(AccountBatchInput batchInput) {
        if (batchInput == null || batchInput.candidates() == null || batchInput.candidates().isEmpty()) {
            return new AccountBatchJudgment("", java.util.Map.of());
        }
        if (batchInput.candidates().size() == 1) {
            BatchCandidateItem c = batchInput.candidates().get(0);
            ViralJudgment single = evaluate(new EvaluationInput(
                    batchInput.authorUsername(),
                    batchInput.authorDisplayName(),
                    batchInput.authorVerified(),
                    c.postText(),
                    c.quotedText(),
                    c.articleText(),
                    c.ageMinutes(),
                    c.views(),
                    c.likes(),
                    c.reposts(),
                    c.replies(),
                    batchInput.minScore(),
                    batchInput.ruleHint(),
                    batchInput.ownerStyleProfile(),
                    batchInput.ownerPreferences(),
                    batchInput.generateComment(),
                    c.factualEvidence(),
                    batchInput.currentHotContext(),
                    c.attentionEvidence(),
                    false,
                    batchInput.postPolishPrompt(),
                    batchInput.onlyDirectPassAllowed(),
                    batchInput.generationProvider(),
                    batchInput.generationModel(),
                    batchInput.authorBio(),
                    batchInput.ownerRecentStances()
            ));
            String selected = single.shouldRepost() ? c.materialId() : "";
            return new AccountBatchJudgment(selected, java.util.Map.of(c.materialId(), single));
        }

        boolean useMiniMaxGate = isMiniMaxGateReady();
        ResolvedQuoteTarget quoteTarget = resolveRepostQuoteTarget(batchInput.generationProvider(), batchInput.generationModel());
        boolean useApiWriter = quoteTarget.isApiWriter();
        boolean needChatGptAuth = !useMiniMaxGate && !useApiWriter;
        ObjectNode auth = needChatGptAuth ? checkAuth(true) : mapper.createObjectNode().put("ready", true);
        if (needChatGptAuth && !auth.path("ready").asBoolean(false)) {
            throw new IllegalStateException(auth.path("message").asText("尚未完成 ChatGPT OAuth 第三方登录"));
        }
        String activeBackend = auth.path("activeBackend").asText("");
        String rawOutput;
        if (useMiniMaxGate) {
            rawOutput = runMiniMaxGateCompletion(BATCH_SYSTEM_PROMPT, buildBatchUserPrompt(batchInput));
        } else if (useApiWriter) {
            rawOutput = runApiQuoteCall(quoteTarget.provider(), quoteTarget.model(), BATCH_SYSTEM_PROMPT, buildBatchUserPrompt(batchInput), true);
        } else {
            rawOutput = "PI_AGENT_OAUTH".equals(activeBackend)
                    ? runPiBatchEvaluation(batchInput)
                    : runCodexBatchEvaluation(batchInput);
        }
        JsonNode root = parseFirstJsonObject(rawOutput);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("ChatGPT 账号合并判定未返回有效 JSON：" + truncate(rawOutput, 160));
        }

        int effectiveMin = Math.max(50, Math.min(99, batchInput.minScore()));
        String hintedSelectedId = root.path("selectedMaterialId").asText("").strip();
        java.util.Map<String, JsonNode> rawItemsById = new java.util.LinkedHashMap<>();
        if (root.path("items").isArray()) {
            for (JsonNode itemNode : root.path("items")) {
                String mid = itemNode.path("materialId").asText("").strip();
                if (!mid.isBlank()) rawItemsById.put(mid, itemNode);
            }
        }

        record ParsedRow(
                BatchCandidateItem candidate,
                boolean directPass,
                boolean rawVerdictPass,
                boolean contentQualifies,
                int viralScore,
                int factScore,
                int trendScore,
                int attentionScore,
                boolean fitPass,
                int fitScore,
                int magnitude,
                int resonance,
                int credibility,
                String category,
                String summary,
                String reason,
                String inlineComment
        ) { }

        List<ParsedRow> parsedRows = new ArrayList<>();
        for (BatchCandidateItem cand : batchInput.candidates()) {
            JsonNode itemJson = rawItemsById.getOrDefault(cand.materialId(), root);
            String verdict = itemJson.path("verdict").asText("SKIP").strip().toUpperCase(Locale.ROOT);
            String category = truncate(itemJson.path("category").asText("ROUTINE").strip(), 40);
            int magnitude = clampScore(itemJson.path("eventMagnitude").asInt(0));
            int resonance = clampScore(itemJson.path("publicResonance").asInt(0));
            int credibility = clampScore(itemJson.path("credibility").asInt(75));
            int factScore = itemJson.has("factScore") ? clampScore(itemJson.path("factScore").asInt(0)) : credibility;
            int trendScore = itemJson.has("trendScore") ? clampScore(itemJson.path("trendScore").asInt(0)) : Math.max(magnitude, resonance);
            int attentionScore = itemJson.has("attentionScore") ? clampScore(itemJson.path("attentionScore").asInt(0)) : resonance;
            int rawViral = clampScore(itemJson.path("viralScore").asInt(0));
            boolean hasAll3 = itemJson.has("factScore") && itemJson.has("trendScore") && itemJson.has("attentionScore");
            int weighted = Math.round(factScore * 0.38f + trendScore * 0.32f + attentionScore * 0.30f);
            int viralScore = rawViral <= 0
                    ? weighted
                    : (hasAll3 ? Math.max(weighted - 12, Math.min(weighted + 12, rawViral)) : rawViral);
            int fitScore = itemJson.has("fitScore")
                    ? clampScore(itemJson.path("fitScore").asInt(0))
                    : ("VIRAL_REPOST".equals(verdict) ? 85 : 55);
            boolean accountFit = itemJson.has("accountFit")
                    ? itemJson.path("accountFit").asBoolean(false)
                    : (fitScore >= 68);
            boolean directPass = isDirectPassCategory(category)
                    && accountFit && fitScore >= 65 && credibility >= 55 && factScore >= 50;
            boolean viralPass = directPass || (viralScore >= effectiveMin && factScore >= 55 && credibility >= 60);
            boolean fitPass = accountFit && (directPass ? fitScore >= 65 : fitScore >= 68);
            boolean qualifies = batchInput.onlyDirectPassAllowed()
                    ? directPass
                    : (directPass || (viralPass && fitPass));
            boolean rawVerdictPass = batchInput.onlyDirectPassAllowed()
                    ? directPass
                    : (directPass || ("VIRAL_REPOST".equals(verdict) && qualifies));
            parsedRows.add(new ParsedRow(
                    cand,
                    directPass,
                    rawVerdictPass,
                    qualifies,
                    viralScore,
                    factScore,
                    trendScore,
                    attentionScore,
                    fitPass,
                    fitScore,
                    magnitude,
                    resonance,
                    credibility,
                    category,
                    truncate(itemJson.path("summary").asText("").strip(), 60),
                    truncate(itemJson.path("reason").asText("").strip(), 140),
                    itemJson.path("commentText").asText("").strip()
            ));
        }

        ParsedRow winner = null;
        // Direct-pass categories (MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET) always outrank routine hot news
        boolean anyDirectPass = parsedRows.stream().anyMatch(ParsedRow::directPass);
        if (!hintedSelectedId.isBlank()) {
            for (ParsedRow r : parsedRows) {
                if (hintedSelectedId.equals(r.candidate().materialId())
                        && r.contentQualifies()
                        && (!anyDirectPass || r.directPass())) {
                    winner = r;
                    break;
                }
            }
        }
        if (winner == null) {
            for (ParsedRow r : parsedRows) {
                if (!r.rawVerdictPass()) continue;
                if (anyDirectPass && !r.directPass()) continue;
                if (winner == null || r.viralScore() > winner.viralScore()) {
                    winner = r;
                }
            }
        }

        String winnerId = winner == null ? "" : winner.candidate().materialId();
        String winnerComment = "";
        if (winner != null && batchInput.generateComment()) {
            BatchCandidateItem wc = winner.candidate();
            EvaluationInput writerInput = new EvaluationInput(
                    batchInput.authorUsername(),
                    batchInput.authorDisplayName(),
                    batchInput.authorVerified(),
                    wc.postText(),
                    wc.quotedText(),
                    wc.articleText(),
                    wc.ageMinutes(),
                    wc.views(),
                    wc.likes(),
                    wc.reposts(),
                    wc.replies(),
                    batchInput.minScore(),
                    batchInput.ruleHint(),
                    batchInput.ownerStyleProfile(),
                    batchInput.ownerPreferences(),
                    true,
                    wc.factualEvidence(),
                    batchInput.currentHotContext(),
                    wc.attentionEvidence(),
                    false,
                    batchInput.postPolishPrompt(),
                    batchInput.onlyDirectPassAllowed(),
                    quoteTarget.provider(),
                    quoteTarget.model(),
                    batchInput.authorBio(),
                    batchInput.ownerRecentStances()
            );
            boolean briefFlash = OwnerQuoteStyleDistiller.isBriefFlashPost(
                    writerInput.postText(), writerInput.quotedText(), writerInput.articleText());
            String rawQuote;
            if (useApiWriter) {
                String skillGuidance = loadQuoteVoiceSkillGuidance();
                String sys = QUOTE_WRITER_SYSTEM_PROMPT + (skillGuidance.isBlank() ? "" : "\n\n" + skillGuidance);
                rawQuote = runApiQuoteCall(quoteTarget.provider(), quoteTarget.model(), sys, buildPureQuoteWriterPrompt(writerInput));
            } else {
                rawQuote = "PI_AGENT_OAUTH".equals(activeBackend)
                        ? runPiQuoteWriter(writerInput)
                        : runCodexQuoteWriter(writerInput);
            }
            String firstDraft = extractPureQuoteText(rawQuote, briefFlash);
            if (firstDraft.isBlank() && !winner.inlineComment().isBlank()) {
                firstDraft = OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(winner.inlineComment(), briefFlash, 200);
            }
            if (firstDraft.isBlank()) {
                throw new IllegalStateException("转发语初稿生成返回为空，请重试");
            }
            winnerComment = firstDraft;
            String customPolish = batchInput.postPolishPrompt() == null ? "" : batchInput.postPolishPrompt().strip();
            if (!customPolish.isBlank()) {
                String rawPolished;
                if (useApiWriter) {
                    rawPolished = runApiQuoteCall(
                            quoteTarget.provider(),
                            fastPolishModelSpec(quoteTarget.provider(), quoteTarget.model()),
                            QUOTE_POLISHER_SYSTEM_PROMPT,
                            buildQuotePolishUserPrompt(writerInput, firstDraft, customPolish)
                    );
                } else {
                    rawPolished = "PI_AGENT_OAUTH".equals(activeBackend)
                            ? runPiQuotePolisher(writerInput, firstDraft, customPolish)
                            : runCodexQuotePolisher(writerInput, firstDraft, customPolish);
                }
                String polished = extractPureQuoteText(rawPolished, briefFlash);
                if (!polished.isBlank()) {
                    winnerComment = polished;
                }
            }
        }

        java.util.Map<String, ViralJudgment> results = new java.util.LinkedHashMap<>();
        for (ParsedRow r : parsedRows) {
            boolean isWinner = !winnerId.isBlank() && winnerId.equals(r.candidate().materialId());
            boolean itemShouldRepost = isWinner || r.directPass();
            String reason = r.reason();
            if (!isWinner && !r.directPass() && r.contentQualifies() && !winnerId.isBlank()) {
                reason = reason.isBlank()
                        ? "同账号单次判定已优选另一条更强推文（16h账号CD仅转1条）"
                        : "同批已优选更强推文（" + reason + "）";
            }
            results.put(r.candidate().materialId(), new ViralJudgment(
                    itemShouldRepost,
                    r.viralScore(),
                    r.factScore(),
                    r.trendScore(),
                    r.attentionScore(),
                    r.fitPass(),
                    r.fitScore(),
                    r.magnitude(),
                    r.resonance(),
                    r.credibility(),
                    r.category(),
                    r.summary(),
                    reason,
                    isWinner ? winnerComment : ""
            ));
        }
        return new AccountBatchJudgment(winnerId, results);
    }

    private String runPiBatchEvaluation(AccountBatchInput batchInput) {
        String binary = resolvePiBinary();
        String model = environment == null ? "" : environment.getProperty("X_CONTENT_ASSISTANT_PI_MODEL", "").strip();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", DEFAULT_PI_PROVIDER,
                    "--thinking", "low"
            ));
            addPiPromptFlag(cmd, "--system-prompt", BATCH_SYSTEM_PROMPT, tempDirs);
            if (!model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            String batchPrompt = buildBatchUserPrompt(batchInput);
            CliResult result = executeCli(cmd, batchPrompt, Duration.ofSeconds(60));
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (ChatGPT OAuth) 账号合并判定异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 账号合并提示词准备失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    private String runCodexBatchEvaluation(AccountBatchInput batchInput) {
        String binary = resolveCodexBinary();
        String rawModel = environment == null ? "" : environment.getProperty("X_CONTENT_ASSISTANT_CODEX_MODEL", "").strip();
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        String modelId = ChatGptOAuthTextModelClient.effectiveCodexModelId(spec);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-viral-batch-");
            Path schemaFile = workDir.resolve("batch-schema.json");
            Path outFile = workDir.resolve("batch-result.json");
            Files.writeString(schemaFile, BATCH_OUTPUT_SCHEMA_JSON, StandardCharsets.UTF_8);

            List<String> cmd = List.of(
                    binary, "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "-s", "read-only",
                    "-m", modelId,
                    "-c", "model_reasoning_effort=\"" + spec.reasoningEffort() + "\"",
                    "-C", workDir.toString(),
                    "--output-schema", schemaFile.toString(),
                    "-o", outFile.toString(),
                    "-"
            );

            String fullPrompt = BATCH_SYSTEM_PROMPT + "\n\n" + buildBatchUserPrompt(batchInput);
            CliResult result = executeCli(cmd, fullPrompt, Duration.ofSeconds(60));
            if (Files.isRegularFile(outFile)) {
                String fileContent = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!fileContent.isBlank()) return fileContent;
            }
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("Codex CLI (ChatGPT OAuth) 账号合并判定异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ioe) {
            throw new IllegalStateException("准备 Codex 批量判定沙箱失败：" + safeMessage(ioe), ioe);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String buildBatchUserPrompt(AccountBatchInput batchInput) {
        var payload = mapper.createObjectNode();
        payload.put("authorUsername", "@" + batchInput.authorUsername())
                .put("authorDisplayName", batchInput.authorDisplayName())
                .put("authorVerified", batchInput.authorVerified())
                .put("minViralScoreRequired", batchInput.minScore())
                .put("authorCooldownOnlyDirectPass", batchInput.onlyDirectPassAllowed());
        if (batchInput.currentHotContext() != null) payload.set("currentHotContext", batchInput.currentHotContext());
        if (!batchInput.ruleHint().isBlank()) payload.put("customFocusHint", batchInput.ruleHint());
        if (!batchInput.ownerPreferences().isBlank()) {
            payload.put("ownerPreferences", truncate(batchInput.ownerPreferences(), 400));
        } else {
            payload.put("defaultAccountPositioning", "聚焦 AI 模型与工程落地、Agent 工作流、科技商业拐点、独立产品与高密度技术洞察；排斥娱乐八卦、无信息量鸡汤寒暄与低质营销。");
        }
        var arr = payload.putArray("candidates");
        for (BatchCandidateItem c : batchInput.candidates()) {
            var item = arr.addObject();
            item.put("materialId", c.materialId())
                    .put("ageMinutes", Math.max(0L, c.ageMinutes()));
            if (!c.postText().isBlank()) item.put("postText", truncate(c.postText(), 1800));
            if (!c.quotedText().isBlank()) item.put("quotedText", truncate(c.quotedText(), 900));
            if (!c.articleText().isBlank()) item.put("articleText", truncate(c.articleText(), 1800));
            var metrics = item.putObject("earlyMetrics");
            if (c.views() != null) metrics.put("views", c.views());
            if (c.likes() != null) metrics.put("likes", c.likes());
            if (c.reposts() != null) metrics.put("reposts", c.reposts());
            if (c.replies() != null) metrics.put("replies", c.replies());
            if (c.factualEvidence() != null) item.set("factualEvidence", c.factualEvidence());
            if (c.attentionEvidence() != null) item.set("attentionEvidence", c.attentionEvidence());
        }
        return "请对该监听作者的近期候选推文做单次合并判定（挑出至多 1 条满足走热潜力且契合当前账号的最优推文填入 selectedMaterialId），只返回客观判定 JSON：\n" + payload;
    }

    String extractPureQuoteText(String rawOutput) {
        return extractPureQuoteText(rawOutput, false);
    }

    String extractPureQuoteText(String rawOutput, boolean briefFlashPost) {
        if (rawOutput == null || rawOutput.isBlank()) return "";
        String trimmed = rawOutput.strip();
        if (trimmed.startsWith("{")) {
            JsonNode maybeJson = parseFirstJsonObject(trimmed);
            if (maybeJson != null && maybeJson.has("commentText")) {
                trimmed = maybeJson.path("commentText").asText("").strip();
            }
        }
        return OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(trimmed, briefFlashPost, 280);
    }

    private static final List<String> REQUIRED_QUOTE_SKILLS = List.of("humanizer-zh", "quote-voice-distiller");
    private volatile List<Path> cachedMaterializedSkillFiles;
    private volatile String cachedQuoteSkillInstructions;

    List<Path> resolveActiveSkillFiles() {
        List<Path> existing = cachedMaterializedSkillFiles;
        if (existing != null && !existing.isEmpty() && existing.stream().allMatch(Files::isRegularFile)) {
            return existing;
        }
        String home = System.getProperty("user.home", "");
        List<Path> resolved = new ArrayList<>();
        Path fallbackDir = null;
        for (String skillName : REQUIRED_QUOTE_SKILLS) {
            Path found = null;
            try (var in = PiAgentViralJudge.class.getResourceAsStream("/assistant-skills/" + skillName + "/SKILL.md")) {
                if (in != null) {
                    if (fallbackDir == null) {
                        fallbackDir = Files.createTempDirectory("xops-pi-skills-");
                    }
                    Path dir = Files.createDirectories(fallbackDir.resolve(skillName));
                    Path target = dir.resolve("SKILL.md");
                    Files.write(target, in.readAllBytes());
                    found = target;
                }
            } catch (IOException ignored) { }
            if (found == null && !home.isBlank()) {
                Path piSkill = Path.of(home, ".pi", "agent", "skills", skillName, "SKILL.md");
                Path agentSkill = Path.of(home, ".agents", "skills", skillName, "SKILL.md");
                if (Files.isRegularFile(piSkill)) found = piSkill;
                else if (Files.isRegularFile(agentSkill)) found = agentSkill;
            }
            if (found != null) resolved.add(found);
        }
        cachedMaterializedSkillFiles = List.copyOf(resolved);
        return cachedMaterializedSkillFiles;
    }

    private String loadQuoteVoiceSkillGuidance() {
        String cached = cachedQuoteSkillInstructions;
        if (cached != null) return cached;
        for (Path skillFile : resolveActiveSkillFiles()) {
            if (skillFile.toString().contains("quote-voice-distiller")) {
                try {
                    String content = Files.readString(skillFile, StandardCharsets.UTF_8).strip();
                    cachedQuoteSkillInstructions = content;
                    return content;
                } catch (IOException ignored) { }
            }
        }
        try (var in = PiAgentViralJudge.class.getResourceAsStream("/assistant-skills/quote-voice-distiller/SKILL.md")) {
            if (in != null) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
                cachedQuoteSkillInstructions = content;
                return content;
            }
        } catch (IOException ignored) { }
        cachedQuoteSkillInstructions = OwnerQuoteStyleDistiller.DEFAULT_DISTILLED_QUOTE_PROFILE;
        return cachedQuoteSkillInstructions;
    }

    private String runPiEvaluation(EvaluationInput input) {
        String binary = resolvePiBinary();
        String rawModel = resolveRequestedChatGptWriterModel(input);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        boolean explicitEffort = rawModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "low";
        String model = rawModel.isBlank() ? "" : spec.modelId();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", DEFAULT_PI_PROVIDER,
                    "--thinking", effort
            ));
            addPiPromptFlag(cmd, "--system-prompt", SYSTEM_PROMPT, tempDirs);
            if (!model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            String userPrompt = buildUserPrompt(input);
            CliResult result = executeCli(cmd, userPrompt, Duration.ofSeconds(45));
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (ChatGPT OAuth) 判定异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 判定提示词准备失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    private String resolveRequestedChatGptWriterModel(EvaluationInput input) {
        if (input != null && input.generationModel() != null && !input.generationModel().isBlank()) {
            return input.generationModel().strip();
        }
        return environment == null ? "" : environment.getProperty(
                "X_CONTENT_ASSISTANT_QUOTE_WRITER_MODEL",
                environment.getProperty("X_CONTENT_ASSISTANT_CODEX_MODEL",
                        environment.getProperty("X_CONTENT_ASSISTANT_PI_MODEL", ""))).strip();
    }

    private String runPiQuoteWriter(EvaluationInput input) {
        String binary = resolvePiBinary();
        String rawModel = resolveRequestedChatGptWriterModel(input);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        boolean explicitEffort = rawModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "medium";
        String model = rawModel.isBlank() ? "" : spec.modelId();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", DEFAULT_PI_PROVIDER,
                    "--thinking", effort
            ));
            addPiPromptFlag(cmd, "--system-prompt", QUOTE_WRITER_SYSTEM_PROMPT, tempDirs);
            for (Path skillFile : resolveActiveSkillFiles()) {
                cmd.add("--skill");
                cmd.add(skillFile.toString());
            }
            String skillGuidance = loadQuoteVoiceSkillGuidance();
            if (!skillGuidance.isBlank()) {
                addPiPromptFlag(cmd, "--append-system-prompt", skillGuidance, tempDirs);
            }
            if (!model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            String writerPrompt = buildPureQuoteWriterPrompt(input);
            CliResult result = executeCli(cmd, writerPrompt, Duration.ofSeconds(60));
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (ChatGPT OAuth) 转发语生成异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 转发语提示词准备失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    private String runCodexEvaluation(EvaluationInput input) {
        String binary = resolveCodexBinary();
        String rawModel = resolveRequestedChatGptWriterModel(input);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        String modelId = ChatGptOAuthTextModelClient.effectiveCodexModelId(spec);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-viral-judge-");
            Path schemaFile = workDir.resolve("schema.json");
            Path outFile = workDir.resolve("last-message.json");
            Files.writeString(schemaFile, OUTPUT_SCHEMA_JSON, StandardCharsets.UTF_8);

            List<String> cmd = List.of(
                    binary, "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "-s", "read-only",
                    "-m", modelId,
                    "-c", "model_reasoning_effort=\"" + spec.reasoningEffort() + "\"",
                    "-C", workDir.toString(),
                    "--output-schema", schemaFile.toString(),
                    "-o", outFile.toString(),
                    "-"
            );

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

    private String runCodexQuoteWriter(EvaluationInput input) {
        String binary = resolveCodexBinary();
        String rawWriterModel = resolveRequestedChatGptWriterModel(input);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawWriterModel);
        String modelId = ChatGptOAuthTextModelClient.effectiveCodexModelId(spec);
        boolean explicitEffort = rawWriterModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "medium";
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-quote-writer-");
            Path outFile = workDir.resolve("quote-comment.txt");

            List<String> cmd = List.of(
                    binary, "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "-s", "read-only",
                    "-m", modelId,
                    "-c", "model_reasoning_effort=\"" + effort + "\"",
                    "-C", workDir.toString(),
                    "-o", outFile.toString(),
                    "-"
            );

            String skillGuidance = loadQuoteVoiceSkillGuidance();
            String fullPrompt = QUOTE_WRITER_SYSTEM_PROMPT
                    + (skillGuidance.isBlank() ? "" : "\n\n" + skillGuidance)
                    + "\n\n" + buildPureQuoteWriterPrompt(input);
            CliResult result = executeCli(cmd, fullPrompt, Duration.ofSeconds(60));
            if (Files.isRegularFile(outFile)) {
                String fileContent = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!fileContent.isBlank()) return fileContent;
            }
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("Codex CLI (ChatGPT OAuth) 转发语生成异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ioe) {
            throw new IllegalStateException("准备 Codex 转发语沙箱失败：" + safeMessage(ioe), ioe);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String runPiQuotePolisher(EvaluationInput input, String firstDraft, String polishPrompt) {
        String binary = resolvePiBinary();
        String rawModel = resolveRequestedChatGptWriterModel(input);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        boolean explicitEffort = rawModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "medium";
        String model = rawModel.isBlank() ? "" : spec.modelId();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", DEFAULT_PI_PROVIDER,
                    "--thinking", effort
            ));
            addPiPromptFlag(cmd, "--system-prompt", QUOTE_POLISHER_SYSTEM_PROMPT, tempDirs);
            for (Path skillFile : resolveActiveSkillFiles()) {
                cmd.add("--skill");
                cmd.add(skillFile.toString());
            }
            if (!model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            String polishUserPrompt = buildQuotePolishUserPrompt(input, firstDraft, polishPrompt);
            CliResult result = executeCli(cmd, polishUserPrompt, Duration.ofSeconds(60));
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (ChatGPT OAuth) 二次改写异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 二次改写提示词准备失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    private String runCodexQuotePolisher(EvaluationInput input, String firstDraft, String polishPrompt) {
        String binary = resolveCodexBinary();
        String rawWriterModel = resolveRequestedChatGptWriterModel(input);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawWriterModel);
        String modelId = ChatGptOAuthTextModelClient.effectiveCodexModelId(spec);
        boolean explicitEffort = rawWriterModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "medium";
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-quote-polish-");
            Path outFile = workDir.resolve("quote-polished.txt");

            List<String> cmd = List.of(
                    binary, "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--color", "never",
                    "-s", "read-only",
                    "-m", modelId,
                    "-c", "model_reasoning_effort=\"" + effort + "\"",
                    "-C", workDir.toString(),
                    "-o", outFile.toString(),
                    "-"
            );

            String fullPrompt = QUOTE_POLISHER_SYSTEM_PROMPT
                    + "\n\n" + buildQuotePolishUserPrompt(input, firstDraft, polishPrompt);
            CliResult result = executeCli(cmd, fullPrompt, Duration.ofSeconds(60));
            if (Files.isRegularFile(outFile)) {
                String fileContent = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!fileContent.isBlank()) return fileContent;
            }
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("Codex CLI (ChatGPT OAuth) 二次改写异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ioe) {
            throw new IllegalStateException("准备 Codex 二次改写沙箱失败：" + safeMessage(ioe), ioe);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private ObjectNode buildQuoteWriterContextNode(EvaluationInput input, boolean briefFlashPost) {
        var postNode = mapper.createObjectNode();
        postNode.put("authorUsername", "@" + input.authorUsername());
        if (!input.authorDisplayName().isBlank()) {
            postNode.put("authorDisplayName", input.authorDisplayName());
        }
        String resolvedBio = OwnerQuoteStyleDistiller.resolveAuthorContext(input.authorUsername(), input.authorBio());
        if (!resolvedBio.isBlank()) {
            postNode.put("authorBio", resolvedBio);
        }
        postNode.put("briefFlashPost", briefFlashPost);
        if (input.ageMinutes() >= 24L * 60L) {
            postNode.put("postRecency", "RECENT_POST_NOT_BREAKING（近期往期高价值推文回看引用，请围绕核心信息与真实体感点评，不要写‘刚刚上线/刚发’等即时字眼）");
        }
        if (!input.postText().isBlank()) {
            postNode.put("postText", truncate(input.postText(), 3000));
        }
        if (!input.quotedText().isBlank()) {
            postNode.put("quotedText", truncate(input.quotedText(), 1500));
        }
        if (!input.articleText().isBlank()) {
            postNode.put("articleText", truncate(input.articleText(), 3500));
        }
        if (input.currentHotContext() != null && !input.currentHotContext().isEmpty()) {
            postNode.set("currentHotContext", input.currentHotContext());
        }
        if (input.ruleHint() != null && !input.ruleHint().isBlank()) {
            postNode.put("focusHint", truncate(input.ruleHint(), 300));
        }
        if (input.ownerPreferences() != null && !input.ownerPreferences().isBlank()) {
            postNode.put("ownerPreferences", truncate(input.ownerPreferences(), 300));
        }
        String distilledPersona = input.ownerStyleProfile() == null ? "" : input.ownerStyleProfile().strip();
        if (!distilledPersona.isBlank()
                && !distilledPersona.startsWith("【引用转发自适应表达规则")
                && !distilledPersona.startsWith("【模型发布")) {
            postNode.put("ownerDistilledPersona", truncateCodePoints(distilledPersona, 900));
        }
        if (input.ownerRecentStances() != null && !input.ownerRecentStances().isEmpty()) {
            var stancesArr = postNode.putArray("ownerRecentStances");
            input.ownerRecentStances().stream().limit(4).forEach(stancesArr::add);
        }
        return postNode;
    }

    private String buildQuotePolishUserPrompt(EvaluationInput input, String firstDraft, String polishPrompt) {
        boolean briefFlash = OwnerQuoteStyleDistiller.isBriefFlashPost(
                input.postText(), input.quotedText(), input.articleText());
        ObjectNode postNode = buildQuoteWriterContextNode(input, briefFlash);
        String lengthHint = briefFlash
                ? "本条属于一句话短快讯（briefFlashPost=true），严禁出现「💡 省流重点：」，直接输出 2 段（共 2 句）纯文本正文："
                : "请直接输出改写后的最终纯文本正文：";
        return "【被引用原帖与创作上下文（仅供参考语境，原帖就在你的文字正下方展示，绝不要复述原帖）】\n"
                + postNode
                + "\n\n【第一次生成的初稿】\n"
                + firstDraft
                + "\n\n【我的二次改写提示词（最高优先级，把上面的初稿按我的要求彻底改写一遍）】\n"
                + polishPrompt
                + "\n\n" + lengthHint;
    }

    PiAgentViralJudge.CliResult runCli(List<String> cmd, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        return runner.run(cmd, stdin, timeout);
    }

    private CliResult executeCli(List<String> cmd, String stdin, Duration timeout) {
        return executeCliWithEnv(cmd, stdin, timeout, java.util.Map.of());
    }

    private CliResult executeCliWithEnv(
            List<String> cmd,
            String stdin,
            Duration timeout,
            java.util.Map<String, String> providerApiEnv
    ) {
        try {
            if (customRunner || providerApiEnv == null || providerApiEnv.isEmpty()) {
                return runner.run(cmd, stdin, timeout);
            }
            return defaultRunProcess(
                    cmd, stdin, timeout, resolveCodexHome(environment), resolvePiAgentDir(environment), providerApiEnv);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PI Agent / ChatGPT OAuth 调用被中断", ie);
        } catch (IOException ioe) {
            throw new IllegalStateException("调用 PI Agent / ChatGPT OAuth 进程失败：" + safeMessage(ioe), ioe);
        }
    }

    private String buildUserPrompt(EvaluationInput input) {
        var payload = mapper.createObjectNode();
        payload.put("authorUsername", "@" + input.authorUsername())
                .put("authorDisplayName", input.authorDisplayName())
                .put("authorVerified", input.authorVerified())
                .put("ageMinutes", Math.max(0, input.ageMinutes()))
                .put("minViralScoreRequired", input.minScore());
        if (!input.postText().isBlank()) payload.put("postText", truncate(input.postText(), 3000));
        if (!input.quotedText().isBlank()) payload.put("quotedText", truncate(input.quotedText(), 1500));
        if (!input.articleText().isBlank()) payload.put("articleText", truncate(input.articleText(), 3500));
        var metrics = payload.putObject("earlyMetrics");
        if (input.views() != null) metrics.put("views", input.views());
        if (input.likes() != null) metrics.put("likes", input.likes());
        if (input.reposts() != null) metrics.put("reposts", input.reposts());
        if (input.replies() != null) metrics.put("replies", input.replies());
        if (input.factualEvidence() != null) payload.set("factualEvidence", input.factualEvidence());
        if (input.currentHotContext() != null) payload.set("currentHotContext", input.currentHotContext());
        if (input.attentionEvidence() != null) payload.set("attentionEvidence", input.attentionEvidence());
        if (!input.ruleHint().isBlank()) payload.put("customFocusHint", input.ruleHint());
        if (!input.ownerPreferences().isBlank()) payload.put("ownerPreferences", truncate(input.ownerPreferences(), 400));
        if (input.ownerPreferences().isBlank()) {
            payload.put("defaultAccountPositioning", "聚焦 AI 模型与工程落地、Agent 工作流、科技商业拐点、独立产品与高密度技术洞察；排斥娱乐八卦、无信息量鸡汤寒暄与低质营销。");
        }
        return "请根据「事实依据、当前热门、被关注度」判断该推文是否有火的可能性，并评估是否契合当前账号（两者同时满足即输出 VIRAL_REPOST），只返回客观判定 JSON：\n" + payload;
    }

    private String buildPureQuoteWriterPrompt(EvaluationInput input) {
        boolean briefFlash = OwnerQuoteStyleDistiller.isBriefFlashPost(
                input.postText(), input.quotedText(), input.articleText());
        ObjectNode postNode = buildQuoteWriterContextNode(input, briefFlash);
        if (briefFlash) {
            return "下面是你要引用转发的极简短快讯（briefFlashPost=true，原帖就在你正下方）：请严禁写「💡 省流重点：」，直接输出 2 段（第 1 段 🎉/🚨 一句话点明谁发了什么 → 第 2 段结合 authorBio、currentHotContext 与我的 ownerRecentStances/ownerPreferences 直接给出判断或吐槽，情绪自然不要硬塞猫猫表情与末尾提问）：\n"
                    + postNode;
        }
        return "下面是你要引用转发的长帖/多要点更新（briefFlashPost=false，原帖就在你正下方）：请输出 3 段（1. 🚨/🎉 快讯定调 → 2. 💡 省流重点：… → 3. 个人真实体感收尾，不加文末提问句）：\n"
                + postNode;
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
        return defaultRunProcess(command, stdin, timeout, codexHome, "");
    }

    private static CliResult defaultRunProcess(
            List<String> command,
            String stdin,
            Duration timeout,
            String codexHome,
            String piAgentDir
    ) throws IOException, InterruptedException {
        return defaultRunProcess(command, stdin, timeout, codexHome, piAgentDir, java.util.Map.of());
    }

    private static CliResult defaultRunProcess(
            List<String> command,
            String stdin,
            Duration timeout,
            String codexHome,
            String piAgentDir,
            java.util.Map<String, String> providerApiEnv
    ) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        // Strip all provider API-key env vars first so chatgpt OAuth can never fall back to API billing,
        // then inject ONLY the explicitly selected provider's credential when running via API key.
        pb.environment().remove("OPENAI_API_KEY");
        pb.environment().remove("CODEX_API_KEY");
        pb.environment().remove("AZURE_OPENAI_API_KEY");
        pb.environment().remove("GEMINI_API_KEY");
        pb.environment().remove("DEEPSEEK_API_KEY");
        pb.environment().remove("MINIMAX_API_KEY");
        if (providerApiEnv != null) {
            for (var entry : providerApiEnv.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    pb.environment().put(entry.getKey(), entry.getValue());
                }
            }
        }
        pb.environment().put("NO_COLOR", "1");
        if (codexHome != null && !codexHome.isBlank()) {
            pb.environment().put("CODEX_HOME", codexHome);
        }
        if (piAgentDir != null && !piAgentDir.isBlank()) {
            pb.environment().put("PI_CODING_AGENT_DIR", piAgentDir);
        }

        List<String> extraDirs = new ArrayList<>();
        Path binaryPath = Path.of(command.get(0));
        if (binaryPath.getParent() != null) extraDirs.add(binaryPath.getParent().toString());
        Path usrLocalBin = Path.of("/usr/local/bin");
        if (Files.isDirectory(usrLocalBin)) {
            extraDirs.add(usrLocalBin.toString());
        }
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
        var stdoutFuture = CompletableFuture.supplyAsync(() -> readBounded(proc.getInputStream()));
        var stderrFuture = CompletableFuture.supplyAsync(() -> readBounded(proc.getErrorStream()));
        try (var out = proc.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
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
            String timeoutLabel = "ChatGPT OAuth 判定";
            if (command != null) {
                int provIdx = command.indexOf("--provider");
                if (provIdx >= 0 && provIdx + 1 < command.size()) {
                    String prov = command.get(provIdx + 1);
                    if (prov != null && !prov.isBlank() && !"openai-codex".equalsIgnoreCase(prov)) {
                        timeoutLabel = "PI Agent (" + prov + ") 调用";
                    }
                }
            }
            throw new IOException(timeoutLabel + "超时（>" + timeout.toSeconds() + "s）");
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

    // ==================== High-Traffic Pure-Text Wash-Rewrite ====================

    private static final String REWRITE_JUDGE_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["suitableForRewrite", "suitabilityScore", "hookPattern", "coreInsight", "reason"],
              "properties": {
                "suitableForRewrite": { "type": "boolean" },
                "suitabilityScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                "hookPattern": { "type": "string" },
                "coreInsight": { "type": "string" },
                "reason": { "type": "string" }
              }
            }
            """;

    private static final String REWRITE_JUDGE_SYSTEM_PROMPT = """
            你是资深 X（Twitter）爆款纯文本内容拆解与选题评审员。
            当前任务不是做引用转发，而是判断一条该账号的高流量纯文本推文是否适合「学习其走红结构与核心洞察，改写为 @elidlicom 本人独立发布的原创纯文本推文」。
            严禁调用任何本地工具或命令，只输出一个合法 JSON 对象。

            【准入标准（suitableForRewrite = true 且 suitabilityScore >= 68）】
            1. 原文具备可迁移的认知密度、行业观察、AI / 模型 / 编程 / Agent / 产品商业洞察、反直觉观点或实操方法论，值得拆解学习并转化为独立原创观点。
            2. 契合 @elidlicom 的账号定位（AI 模型与工具真实体验、独立开发、科技与商业思考；若有 ownerPreferences 则优先对齐）。

            【一票否决（必须令 suitableForRewrite = false，suitabilityScore < 60）】
            1. 招聘启事、抽奖互动（RT/Follow 领奖）、纯商务软广、求关注/私信领资料。
            2. 高度依赖原作者私人身份且无法转化为行业公共观察的私人琐事（如家庭纪念日、个人入职离职官宣、线下聚会寒暄）。
            3. 残缺长推串首条（如「1/ 下面展开讲讲…」但正文并未给出实质论据）或纯情绪宣泄无观点。
            4. 与科技 / AI / 开发 / 商业产品完全无关的娱乐八卦、体育赛事或引战骂街。
            （注：若原文是某团队第一人称谈行业趋势或产品工程取舍，只要能自然转换为外部重度用户/开发者的独立观察视角且不冒充官方，可判为 suitableForRewrite = true，并在 coreInsight 中按外部观察者视角提炼。）

            只输出一个 JSON 对象，不要使用 Markdown 代码块：
            {"suitableForRewrite":true或false,"suitabilityScore":0到100整数,"hookPattern":"35字内拆解原文抓人开头、反差节奏或情绪钩子","coreInsight":"80字内提炼可转化为独立原创帖的核心观点与论据骨架","reason":"40字内中文判定理由"}
            """;

    private static final String REWRITE_WRITER_SYSTEM_PROMPT = """
            你是独立开发者与重度 AI 订阅用户 @elidlicom 本人，正在发布一条【完全独立原创的纯文本 X 推文】（不是转发引用，正下方没有任何原帖卡片！）。
            你的任务是：学习参考高流量纯文本样本的【抓人钩子、反差张力与节奏结构】，消化其核心观点后，用你自己的语言习惯和视角【彻底重构改写】成一条全新的中文原创推文。
            严禁调用任何本地工具或命令，不要输出 JSON、标题、引号或任何解释，直接输出最终要发布的推文正文！

            【洗稿重构与原创化硬约束】
            1. 只学「流量结构 + 核心认知」，绝不逐句顺译或同义词替换：
               - 重新组织切入角度、句式长短与段落顺序；开头第一句直接亮出反差判断、真实痛点或犀利观察，3 秒抓住注意力。
               - 把抽象概念落回具体日常开发/使用场景，像在朋友圈跟同行朋友直白聊真实体感。
            2. 严禁出现任何「转发/引用/搬运」痕迹：
               - 绝对禁止出现 `@原作者`、「刚刷到一条推」「看到有人说」「某大佬提到」「原帖说」「💡 省流重点：」或任何链接 URL。
               - 读者看到的必须是你本人自然发出的独立观点帖。
            3. 严守事实与身份边界：
               - 若原样本包含原作者专属的第一人称经历（如「我们公司刚融资」「我花了十万美元测卡」），必须转化为行业观察或普通重度付费用户视角，绝不伪造未发生的亲身实验或虚假身份。
               - 原文有明确公开数字/事实时可自然融入，绝不捏造新数字。
            4. 去 AI 味与排版节奏（humanizer-zh）：
               - 全文控制在 2～4 个短自然段（段间空一行 `\\n\\n`，总字数通常 70～220 字，精炼有嚼头，不写冗长裹脚布）。
               - 吐槽、看穿大厂套路时干脆犀利（可用 `……` 留白），真香实用话题可自然带轻松感；严禁在结尾硬加「你怎么看？」「大家觉得呢？」之类的廉价互动提问。
               - 严禁出现：「值得注意的是」「总而言之」「意味着」「标志着」「分水岭」「不仅……更是……」「赋能」「闭环」「生态位」「重塑格局」「降维打击」。
            """;

    private static final String REWRITE_POLISHER_SYSTEM_PROMPT = """
            你是 @elidlicom 本人的原创推文定稿编辑器。当前稿件是【独立发布的原创纯文本推文（非转发引用，下方无原帖）】。
            请严格按用户的【二次改写要求】与真人日常口吻把初稿打磨定稿；严禁调用任何本地工具或命令，不要输出解释、引号或 JSON，只直接输出最终纯文本推文正文！

            【定稿铁律】
            1. 本条是独立原创推文（不是转发引用！）：即便二次改写要求里提到「如果是转发引用需要先简述引用内容」，本条也【不适用】该条，严禁加入「原帖说」「💡 省流重点：」或 `@原作者` 等引用痕迹！
            2. 彻底消除英文翻译腔、AI 公关腔与生僻单字缩写（能用自然双音节词就不用拗口单字），确保每句话都通俗直白、符合微信朋友圈/日常交流语感。
            3. 保持 2～4 个短段落（段间空一行），保留抓人的开头反差与真实态度，不编造虚假亲身实验，不在末尾硬塞愚蠢提问。
            """;

    public record RewriteInput(
            String authorUsername,
            String authorDisplayName,
            String authorBio,
            String sourceText,
            Long views,
            Long likes,
            Long reposts,
            Long replies,
            long authorMedianViews,
            long authorMedianLikes,
            String ownerStyleProfile,
            String ownerPreferences,
            List<String> ownerRecentStances,
            String postPolishPrompt,
            String generationProvider,
            String generationModel
    ) { }

    public record RewriteJudgment(
            boolean suitable,
            int suitabilityScore,
            String hookPattern,
            String coreInsight,
            String reason,
            String rewrittenText
    ) { }

    public RewriteJudgment evaluateAndWriteRewrite(RewriteInput input) {
        return evaluateAndWriteRewrite(input, false);
    }

    public RewriteJudgment evaluateAndWriteRewrite(RewriteInput input, boolean forceWritePreview) {
        if (input == null || input.sourceText() == null || input.sourceText().isBlank()) {
            throw new IllegalArgumentException("洗稿源文本不能为空");
        }
        boolean useMiniMaxGate = isMiniMaxGateReady();
        ResolvedQuoteTarget writerTarget = resolveRepostQuoteTarget(input.generationProvider(), input.generationModel());
        boolean useApiWriter = writerTarget.isApiWriter();
        boolean needChatGptAuth = !useMiniMaxGate && !useApiWriter;
        ObjectNode auth = needChatGptAuth ? checkAuth(true) : mapper.createObjectNode().put("ready", true);
        if (needChatGptAuth && !auth.path("ready").asBoolean(false)) {
            throw new IllegalStateException(auth.path("message").asText("尚未完成 ChatGPT OAuth 第三方登录"));
        }
        String activeBackend = auth.path("activeBackend").asText("");
        String judgeUserPrompt = buildRewriteJudgeUserPrompt(input);
        String rawJudgeOutput;
        if (useMiniMaxGate) {
            rawJudgeOutput = runMiniMaxGateCompletion(REWRITE_JUDGE_SYSTEM_PROMPT, judgeUserPrompt);
        } else if (useApiWriter) {
            rawJudgeOutput = runApiQuoteCall(writerTarget.provider(), writerTarget.model(), REWRITE_JUDGE_SYSTEM_PROMPT, judgeUserPrompt, true);
        } else {
            rawJudgeOutput = "PI_AGENT_OAUTH".equals(activeBackend)
                    ? runPiGenericStructured(REWRITE_JUDGE_SYSTEM_PROMPT, judgeUserPrompt, writerTarget.model())
                    : runCodexGenericStructured(REWRITE_JUDGE_SCHEMA_JSON, REWRITE_JUDGE_SYSTEM_PROMPT, judgeUserPrompt, writerTarget.model());
        }

        JsonNode judgeJson = parseFirstJsonObject(rawJudgeOutput);
        if (judgeJson == null || !judgeJson.isObject()) {
            throw new IllegalStateException("洗稿适格性判定未返回有效 JSON：" + truncate(rawJudgeOutput, 160));
        }
        int suitabilityScore = clampScore(judgeJson.path("suitabilityScore").asInt(0));
        boolean rawSuitable = judgeJson.path("suitableForRewrite").asBoolean(false);
        String hookPattern = truncate(judgeJson.path("hookPattern").asText("").strip(), 120);
        String coreInsight = truncate(judgeJson.path("coreInsight").asText("").strip(), 240);
        String reason = truncate(judgeJson.path("reason").asText("").strip(), 140);
        boolean pass = rawSuitable && suitabilityScore >= 68;
        if (!pass && !forceWritePreview) {
            return new RewriteJudgment(false, suitabilityScore, hookPattern, coreInsight,
                    reason.isBlank() ? "该纯文本推文不适合转化为独立原创帖" : reason, "");
        }

        String skillGuidance = loadQuoteVoiceSkillGuidance();
        String writerPrompt = buildRewriteWriterUserPrompt(input, hookPattern, coreInsight);
        String rawDraft;
        if (useApiWriter) {
            rawDraft = runApiQuoteCall(writerTarget.provider(), writerTarget.model(), REWRITE_WRITER_SYSTEM_PROMPT, writerPrompt);
        } else {
            if (!needChatGptAuth) {
                ObjectNode writerAuth = checkAuth(true);
                if (!writerAuth.path("ready").asBoolean(false)) {
                    throw new IllegalStateException(writerAuth.path("message").asText("尚未完成 ChatGPT OAuth 第三方登录"));
                }
                activeBackend = writerAuth.path("activeBackend").asText("");
            }
            rawDraft = "PI_AGENT_OAUTH".equals(activeBackend)
                    ? runPiGenericWriter(REWRITE_WRITER_SYSTEM_PROMPT, writerPrompt, writerTarget.model(), resolveHumanizerSkillFiles())
                    : runCodexGenericWriter(REWRITE_WRITER_SYSTEM_PROMPT, writerPrompt, writerTarget.model());
        }
        String cleanedDraft = cleanStandaloneRewriteText(rawDraft);
        if (cleanedDraft.isBlank()) {
            throw new IllegalStateException("洗稿初稿生成返回为空，请重试");
        }
        String finalRewrite = cleanedDraft;
        String customPolish = input.postPolishPrompt() == null ? "" : input.postPolishPrompt().strip();
        if (!customPolish.isBlank()) {
            String polishUserPrompt = buildRewritePolishUserPrompt(input, cleanedDraft, customPolish, hookPattern, coreInsight);
            String rawPolished;
            if (useApiWriter) {
                rawPolished = runApiQuoteCall(
                        writerTarget.provider(),
                        fastPolishModelSpec(writerTarget.provider(), writerTarget.model()),
                        REWRITE_POLISHER_SYSTEM_PROMPT,
                        polishUserPrompt
                );
            } else {
                rawPolished = "PI_AGENT_OAUTH".equals(activeBackend)
                        ? runPiGenericWriter(REWRITE_POLISHER_SYSTEM_PROMPT, polishUserPrompt, writerTarget.model(), resolveHumanizerSkillFiles())
                        : runCodexGenericWriter(REWRITE_POLISHER_SYSTEM_PROMPT, polishUserPrompt, writerTarget.model());
            }
            String cleanedPolished = cleanStandaloneRewriteText(rawPolished);
            if (!cleanedPolished.isBlank()) {
                finalRewrite = cleanedPolished;
            }
        }
        return new RewriteJudgment(pass, suitabilityScore, hookPattern, coreInsight,
                reason.isBlank()
                        ? (pass ? "高流量纯文本结构清晰，已完成学习与原创改写" : "该纯文本推文洗稿适格分偏低，已生成预览改写供参考")
                        : reason,
                finalRewrite);
    }

    static String cleanStandaloneRewriteText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = OwnerQuoteStyleDistiller.stripWrappingQuotesOrFences(raw);
        if (trimmed.startsWith("{")) {
            // Guard against accidental JSON wrapper
            int idx = trimmed.indexOf("\"text\"");
            if (idx < 0) idx = trimmed.indexOf("\"rewrittenText\"");
            if (idx >= 0) {
                try {
                    JsonNode node = new ObjectMapper().readTree(trimmed);
                    String extracted = node.path("rewrittenText").asText(node.path("text").asText("")).strip();
                    if (!extracted.isBlank()) trimmed = extracted;
                } catch (Exception ignored) { }
            }
        }
        String withoutUrls = trimmed.replaceAll("https?://\\S+", "").strip();
        String[] rawParas = withoutUrls.split("\\n\\s*\\n+");
        List<String> cleanParas = new ArrayList<>();
        for (String p : rawParas) {
            String line = p.replaceAll("[ \\t]+", " ").strip();
            line = line.replaceFirst("^(?:💡\\s*)?省流重点[：:]\\s*", "").strip();
            if (!line.isEmpty()) {
                cleanParas.add(line);
            }
        }
        String joined = String.join("\n\n", cleanParas).strip();
        return truncateCodePoints(joined, 500);
    }

    private String buildRewriteJudgeUserPrompt(RewriteInput input) {
        var payload = mapper.createObjectNode();
        payload.put("authorUsername", "@" + input.authorUsername().replaceFirst("^@+", ""));
        if (!input.authorDisplayName().isBlank()) payload.put("authorDisplayName", input.authorDisplayName());
        String bio = OwnerQuoteStyleDistiller.resolveAuthorContext(input.authorUsername(), input.authorBio());
        if (!bio.isBlank()) payload.put("authorBio", bio);
        payload.put("sourcePureText", truncate(input.sourceText(), 3500));
        var traffic = payload.putObject("trafficMetrics");
        if (input.views() != null) traffic.put("views", input.views());
        if (input.likes() != null) traffic.put("likes", input.likes());
        if (input.reposts() != null) traffic.put("reposts", input.reposts());
        if (input.replies() != null) traffic.put("replies", input.replies());
        if (input.authorMedianViews() > 0) traffic.put("authorMedianViews", input.authorMedianViews());
        if (input.authorMedianLikes() > 0) traffic.put("authorMedianLikes", input.authorMedianLikes());
        if (input.ownerPreferences() != null && !input.ownerPreferences().isBlank()) {
            payload.put("ownerPreferences", truncate(input.ownerPreferences(), 400));
        }
        return "请评估这条高流量纯文本推文是否适合学习其爆款结构并改写为独立原创推文，只返回 JSON：\n" + payload;
    }

    private String buildRewriteWriterUserPrompt(RewriteInput input, String hookPattern, String coreInsight) {
        var ctx = mapper.createObjectNode();
        ctx.put("mode", "ORIGINAL_STANDALONE_POST");
        ctx.put("sourceAuthorContext", OwnerQuoteStyleDistiller.resolveAuthorContext(input.authorUsername(), input.authorBio()));
        ctx.put("highTrafficSourceTextForStudyOnly", truncate(input.sourceText(), 3500));
        if (!hookPattern.isBlank()) ctx.put("viralHookPatternToLearn", hookPattern);
        if (!coreInsight.isBlank()) ctx.put("coreInsightToTransform", coreInsight);
        if (input.ownerPreferences() != null && !input.ownerPreferences().isBlank()) {
            ctx.put("ownerPreferences", truncate(input.ownerPreferences(), 350));
        }
        if (input.ownerStyleProfile() != null && !input.ownerStyleProfile().isBlank()) {
            ctx.put("ownerDistilledPersona", truncateCodePoints(input.ownerStyleProfile(), 900));
        }
        if (input.ownerRecentStances() != null && !input.ownerRecentStances().isEmpty()) {
            var arr = ctx.putArray("ownerRecentStances");
            input.ownerRecentStances().stream().limit(4).forEach(arr::add);
        }
        return "请学习下方高流量纯文本的走红钩子与节奏结构，彻底改写重构为 @elidlicom 本人独立发布的全新中文原创纯文本推文（严禁做引用转发、严禁提原作者或加省流标签、直接输出正文）：\n"
                + ctx;
    }

    private String buildRewritePolishUserPrompt(
            RewriteInput input,
            String firstDraft,
            String polishPrompt,
            String hookPattern,
            String coreInsight
    ) {
        var ctx = mapper.createObjectNode();
        ctx.put("mode", "ORIGINAL_STANDALONE_POST（独立原创推文，非转发引用）");
        ctx.put("referenceSourceFacts", truncate(input.sourceText(), 2500));
        if (!hookPattern.isBlank()) ctx.put("viralHookPattern", hookPattern);
        if (!coreInsight.isBlank()) ctx.put("coreInsight", coreInsight);
        return "【参考背景与事实核对基线（本条为独立原创推文，非转发引用，绝不提及原帖或原作者）】\n"
                + ctx
                + "\n\n【洗稿初稿】\n"
                + firstDraft
                + "\n\n【我的二次改写提示词（注意：忽略其中仅针对“如果是转发引用”的子句，其余通俗化、去翻译腔、生活场景化与大厂态度要求全部严格执行）】\n"
                + polishPrompt
                + "\n\n请直接输出打磨后的最终独立原创纯文本推文：";
    }

    private String runPiGenericStructured(String systemPrompt, String userPrompt, String rawModelOverride) {
        String binary = resolvePiBinary();
        String rawModel = (rawModelOverride != null && !rawModelOverride.isBlank())
                ? rawModelOverride.strip()
                : resolveRequestedChatGptWriterModel(null);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        boolean explicitEffort = rawModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "low";
        String model = rawModel.isBlank() ? "" : spec.modelId();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", DEFAULT_PI_PROVIDER,
                    "--thinking", effort
            ));
            addPiPromptFlag(cmd, "--system-prompt", systemPrompt, tempDirs);
            if (!model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            CliResult result = executeCli(cmd, userPrompt, Duration.ofSeconds(45));
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (ChatGPT OAuth) 洗稿判定异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 洗稿判定提示词准备失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    private String runCodexGenericStructured(String schemaJson, String systemPrompt, String userPrompt, String rawModelOverride) {
        String binary = resolveCodexBinary();
        String rawModel = (rawModelOverride != null && !rawModelOverride.isBlank())
                ? rawModelOverride.strip()
                : resolveRequestedChatGptWriterModel(null);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        String modelId = ChatGptOAuthTextModelClient.effectiveCodexModelId(spec);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-rewrite-judge-");
            Path schemaFile = workDir.resolve("schema.json");
            Path outFile = workDir.resolve("last-message.json");
            Files.writeString(schemaFile, schemaJson, StandardCharsets.UTF_8);
            List<String> cmd = List.of(
                    binary, "exec",
                    "--ephemeral", "--skip-git-repo-check", "--ignore-user-config", "--ignore-rules",
                    "--color", "never", "-s", "read-only",
                    "-m", modelId,
                    "-c", "model_reasoning_effort=\"" + spec.reasoningEffort() + "\"",
                    "-C", workDir.toString(),
                    "--output-schema", schemaFile.toString(),
                    "-o", outFile.toString(),
                    "-"
            );
            CliResult result = executeCli(cmd, systemPrompt + "\n\n" + userPrompt, Duration.ofSeconds(45));
            if (Files.isRegularFile(outFile)) {
                String fileContent = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!fileContent.isBlank()) return fileContent;
            }
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("Codex CLI (ChatGPT OAuth) 洗稿判定异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ioe) {
            throw new IllegalStateException("准备 Codex 洗稿判定沙箱失败：" + safeMessage(ioe), ioe);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String runPiGenericWriter(String systemPrompt, String userPrompt, String rawModelOverride, List<Path> skills) {
        String binary = resolvePiBinary();
        String rawModel = (rawModelOverride != null && !rawModelOverride.isBlank())
                ? rawModelOverride.strip()
                : resolveRequestedChatGptWriterModel(null);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        boolean explicitEffort = rawModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "medium";
        String model = rawModel.isBlank() ? "" : spec.modelId();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    binary, "-p",
                    "--no-session", "--no-tools", "--no-extensions", "--no-mcp",
                    "--no-prompt-templates", "--no-context-files", "--no-approve",
                    "--provider", DEFAULT_PI_PROVIDER,
                    "--thinking", effort
            ));
            if (skills != null && !skills.isEmpty()) {
                for (Path sf : skills) {
                    cmd.add("--skill");
                    cmd.add(sf.toString());
                }
            } else {
                cmd.add("--no-skills");
            }
            addPiPromptFlag(cmd, "--system-prompt", systemPrompt, tempDirs);
            if (!model.isBlank()) {
                cmd.add("--model");
                cmd.add(model);
            }
            CliResult result = executeCli(cmd, userPrompt, Duration.ofSeconds(60));
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("PI Agent (ChatGPT OAuth) 洗稿生成异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ex) {
            throw new IllegalStateException("PI Agent (ChatGPT OAuth) 洗稿提示词准备失败：" + safeMessage(ex), ex);
        } finally {
            cleanupTempDirs(tempDirs);
        }
    }

    private String runCodexGenericWriter(String systemPrompt, String userPrompt, String rawModelOverride) {
        String binary = resolveCodexBinary();
        String rawModel = (rawModelOverride != null && !rawModelOverride.isBlank())
                ? rawModelOverride.strip()
                : resolveRequestedChatGptWriterModel(null);
        ChatGptOAuthTextModelClient.ModelSpec spec = ChatGptOAuthTextModelClient.parseModelSpec(rawModel);
        String modelId = ChatGptOAuthTextModelClient.effectiveCodexModelId(spec);
        boolean explicitEffort = rawModel.matches("(?i).*[\\s@:]+(minimal|low|medium|high|xhigh|ultra|max)$");
        String effort = explicitEffort ? spec.reasoningEffort() : "medium";
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("xops-rewrite-writer-");
            Path outFile = workDir.resolve("rewrite-out.txt");
            List<String> cmd = List.of(
                    binary, "exec",
                    "--ephemeral", "--skip-git-repo-check", "--ignore-user-config", "--ignore-rules",
                    "--color", "never", "-s", "read-only",
                    "-m", modelId,
                    "-c", "model_reasoning_effort=\"" + effort + "\"",
                    "-C", workDir.toString(),
                    "-o", outFile.toString(),
                    "-"
            );
            CliResult result = executeCli(cmd, systemPrompt + "\n\n" + userPrompt, Duration.ofSeconds(60));
            if (Files.isRegularFile(outFile)) {
                String fileContent = Files.readString(outFile, StandardCharsets.UTF_8).strip();
                if (!fileContent.isBlank()) return fileContent;
            }
            if (result.exitCode() != 0) {
                String err = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                throw new IllegalStateException("Codex CLI (ChatGPT OAuth) 洗稿生成异常：" + truncate(err, 200));
            }
            return result.stdout();
        } catch (IOException ioe) {
            throw new IllegalStateException("准备 Codex 洗稿生成沙箱失败：" + safeMessage(ioe), ioe);
        } finally {
            deleteQuietly(workDir);
        }
    }
}
