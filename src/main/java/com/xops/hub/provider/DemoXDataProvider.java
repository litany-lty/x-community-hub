package com.xops.hub.provider;

import com.xops.hub.model.AnalyticsReportModels.PresetAccount;
import com.xops.hub.model.RawSnapshotModels.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 开箱即用的确定性仿真数据源：
 * 相同 @username 始终生成一致且符合真实 X 社区生态分布的 30 天发帖、双向回复与关注关系快照，
 * 便于未配置外部 API Key 时完整体验全部 5 大运营功能。
 */
@Component
public class DemoXDataProvider {

    private record PeerTemplate(
            String username,
            String displayName,
            String bio,
            boolean verified,
            int followers,
            int following
    ) {
    }

    private static final List<PeerTemplate> PEER_POOL = List.of(
            new PeerTemplate("lin_indie", "Lin · 独立开发", "Building SaaS in public | 日拱一卒 #BuildInPublic", true, 14800, 920),
            new PeerTemplate("zoe_growth", "Zoe 增长笔记", "海外社区增长 & X 算法实操拆解 | 前大厂增长负责人", true, 32600, 610),
            new PeerTemplate("kai_rust", "Kai 写代码", "Rust / Java 后端架构 | 开源爱好者", false, 4820, 780),
            new PeerTemplate("momo_design", "Momo 视觉设计", "UI/UX Designer · 专注极简深色系产品设计", true, 19400, 540),
            new PeerTemplate("ethan_ai", "Ethan · AI Agent", "每天实测一个 AI 工作流 | Prompt & Agent 探索", true, 22900, 1120),
            new PeerTemplate("nina_yap", "Nina 社区主理人", "Web3 & AI 双栖社区运营 | 欢迎互暖交流 🤝", false, 6350, 1450),
            new PeerTemplate("leo_quant", "Leo 量化日志", "链上数据分析 / 美股宏观笔记", false, 8910, 430),
            new PeerTemplate("sara_pm", "Sara 产品思考", "0-1 出海产品经理 | 记录真实踩坑与复盘", false, 5190, 690),
            new PeerTemplate("ryan_founder", "Ryan | Founder", "YC Alum · AI Infra 创业中", true, 88500, 310),
            new PeerTemplate("openai_news_cn", "AI 前沿速递", "每日精选全球 AI 行业动态与论文解读", true, 142000, 120),
            new PeerTemplate("paul_vc", "Paul 创投观察", "Early-stage VC · 关注 AI 应用与出海工具", true, 64200, 490),
            new PeerTemplate("yuki_maker", "Yuki 造物记", "全栈独立创作者 | 正在做第 4 个小产品", false, 3420, 820),
            new PeerTemplate("derek_ops", "Derek 运营手册", "社群冷启动 / 内容裂变 / 创作者经济", false, 4150, 980),
            new PeerTemplate("ada_research", "Ada 投研笔记", "深度长文爱好者 · 每周两篇行业拆解 Thread", true, 15700, 460),
            new PeerTemplate("ghost_acc_01", "CryptoMoon2024", "Web3 enthusiast | DM for promo", false, 890, 3900),
            new PeerTemplate("dormant_dev", "OldCoder99", "Java & Linux notes", false, 410, 620),
            new PeerTemplate("silent_reader", "安静看推的小张", "主要看 AI 和出海独立开发", false, 260, 540),
            new PeerTemplate("hype_hunter", "AlphaHunter_X", "Next 100x gems | NFA", false, 2150, 4200),
            new PeerTemplate("ivy_creator", "Ivy 内容工坊", "一人公司实践者 · 分享写作与播客制作", false, 7280, 660),
            new PeerTemplate("owen_cloud", "Owen 云原生", "Kubernetes / Go / 分布式系统调优", false, 3890, 510),
            new PeerTemplate("fan_amber", "Amber 学出海", "刚入坑 X 运营的新人，向各位前辈学习！", false, 520, 380),
            new PeerTemplate("fan_bruce", "Bruce 独立站", "跨境 DTC & SEO 实战派", false, 1680, 740),
            new PeerTemplate("fan_claire", "Claire 效率控", "Notion 模版创作者 / 数字游民在清迈", false, 2940, 610),
            new PeerTemplate("cold_kol_88", "TechWhale 科技鲸", "硅谷一线科技评论", true, 196000, 185),
            new PeerTemplate("unrecip_jason", "Jason 聊增长", "分享流量玩法（偶更）", false, 3100, 290),
            new PeerTemplate("unrecip_kevin", "Kevin 创业日记", "连续创业者 | ToB SaaS", false, 4600, 340)
    );

    private static final String[] POST_TEMPLATES = {
            "复盘了过去两周在 X 做社区冷启动的数据：与其每天硬憋 5 条自嗨推文，不如发 1 条有信息密度的实战复盘 + 认真去 15 位同频推友评论区写有观点的回复，主页回访率直接翻了 2.6 倍。",
            "整理了一份「独立开发者 X 账号从 0 到 5000 铁粉」的每日 45 分钟运营清单：\n1. 早间黄金档发 1 条踩坑/数据对比贴\n2. 围绕 10 个核心互暖推友做深度补充评论\n3. 每周定期清理零互动无效关注，保持健康关注比\n长推拆解如下 🧵👇",
            "刚把新产品的暗黑模式交互细节打磨完，顺手贴一张前后对比图。大家觉得纯黑 #000000 还是暗夜蓝 #15202B 更耐看？欢迎评论区聊聊你的日常偏好 👇",
            "很多人忽略了 X 算法里的一个高权重信号：「双向对话深度」。当推友来你评论区留言，你在 30 分钟内接住话题形成二次来回回复，这条推文的推荐池明显会被推高一档。",
            "本周 #BuildInPublic 进度汇报：\n✅ 完成核心分析引擎重构，接口延迟压到 15ms 内\n✅ 新增关注关系智能分类（自动区分行业大V与零互动沉睡号，防误删）\n✅ 支持一键生成战报长图\n感谢这几天帮忙内测提建议的推友们！",
            "做社区运营最怕「单向消耗」——你天天去别人底下认真暖场，对方既不回关也从不回访。定期看一眼双向回复比例，把精力留给真正同频双向奔赴的朋友，体验会好太多。",
            "实测发现图文贴和纯文字短推在 X 上的分工完全不同：\n• 纯文字短推：适合抛尖锐观点引发评论讨论\n• 结构化配图 + Thread：书签收藏率最高，长尾吸粉最强\n不要只用一种体裁发帖。",
            "今天和几位做海外工具站的朋友深聊了 2 小时，最大的共识是：2026 年工具产品最好的免费流量池，依然是在 X 上真实记录解决具体痛点的全过程。"
    };

    private static final String[] OUTBOUND_REPLY_TEMPLATES = {
            "这个切入点太准了！我上周测下来也是类似结论，尤其是前 30 分钟的互动密度很关键。",
            "非常赞同第二点，把复杂度留在后端、把确定性交付给用户，留存率确实不一样。",
            "收藏了！顺便补充一个实战细节：配合固定时段输出，老粉的打开习惯会更稳定。",
            "哈哈太真实了，之前我也踩过一模一样的坑，后来加了一层缓存才彻底稳住。",
            "这组视觉排版看着很舒服，信息层级一眼就能抓到重点，学习了 👍",
            "支持日更！你最近这个系列的拆解干货密度拉满了，持续蹲后续更新。"
    };

    private static final String[] INBOUND_REPLY_TEMPLATES = {
            "说得太到位了，刚开始运营 X 最容易陷入自嗨式发帖，双向互动才是破局关键！",
            "刚试了一下你分享的思路，这周主页曝光确实涨了不少，感谢无私拆解 🙌",
            "纯黑 #000000 党投一票！在 OLED 屏幕上对比度真的绝佳。",
            "已加入书签！这条 Thread 对我们正在做冷启动的小团队太有参考价值了。",
            "每次看你的周报都很有动力，一起保持节奏 #BuildInPublic 🚀",
            "深有同感！把精力聚焦在 20 个同频推友身上，社区氛围和正反馈立竿见影。"
    };

    public List<PresetAccount> presetAccounts() {
        return List.of(
                new PresetAccount("karpathy", "Andrej Karpathy", "AI 深度创作者"),
                new PresetAccount("levelsio", "Pieter Levels", "独立开发 BuildInPublic"),
                new PresetAccount("sama", "Sam Altman", "科技高频互动")
        );
    }

    public RawAccountSnapshot generateSnapshot(String username, String reasonNotice) {
        String cleanHandle = username.trim();
        String lower = cleanHandle.toLowerCase(Locale.ROOT);
        long seed = stableSeed(lower);
        Random rng = new Random(seed);

        XUserSummary profile = buildDemoProfile(cleanHandle, lower, rng);
        Instant now = Instant.now();
        ZoneId zone = ZoneId.of("Asia/Shanghai");

        List<XUserSummary> peers = PEER_POOL.stream()
                .map(p -> new XUserSummary(
                        "u_" + p.username(),
                        p.username(),
                        p.displayName(),
                        "",
                        p.bio(),
                        p.verified(),
                        p.followers(),
                        p.following(),
                        180 + Math.abs(p.username().hashCode() % 1200),
                        "2022-05"
                ))
                .toList();

        // 构建关注关系：前 8 个互关，8..19 我关注但对方未回关（含大V、单向暖场、零互动），20..25 粉丝关注我但我未回关
        List<RelationshipEntry> relationships = new ArrayList<>();
        for (int i = 0; i < peers.size(); i++) {
            XUserSummary p = peers.get(i);
            if (i < 9) {
                relationships.add(new RelationshipEntry(p, true, true));
            } else if (i < 20) {
                relationships.add(new RelationshipEntry(p, true, false));
            } else if (i < 24) {
                relationships.add(new RelationshipEntry(p, false, true));
            } else {
                relationships.add(new RelationshipEntry(p, true, false));
            }
        }

        List<RawTweet> timeline = new ArrayList<>();
        List<InboundInteraction> inbound = new ArrayList<>();

        int postCount = 16 + rng.nextInt(12); // 16..27 条原创推文
        int quoteCount = 4 + rng.nextInt(5);

        List<String> myPostIds = new ArrayList<>();
        // 生成原创推文（偏好周二/周四晚 21:00 与上午 09:30 的黄金窗口，让热力图与诊断有真实规律）
        int[] preferredHours = {21, 21, 20, 9, 10, 22, 14, 19, 12, 23};
        for (int i = 0; i < postCount; i++) {
            int daysAgo = rng.nextInt(29);
            int hour = preferredHours[rng.nextInt(preferredHours.length)];
            int minute = rng.nextInt(55);
            ZonedDateTime targetTime = now.atZone(zone)
                    .minusDays(daysAgo)
                    .withHour(hour)
                    .withMinute(minute)
                    .withSecond(0)
                    .withNano(0);
            if (targetTime.toInstant().isAfter(now)) {
                targetTime = targetTime.minusDays(1);
            }

            String tpl = POST_TEMPLATES[i % POST_TEMPLATES.length];
            boolean isThread = tpl.contains("🧵") || (i % 5 == 0);
            boolean hasMedia = !isThread && (tpl.contains("对比图") || i % 2 == 0);

            boolean isGoldenHour = (hour >= 20 && hour <= 22) || (hour == 9);
            double mult = (isGoldenHour ? 1.45 : 0.9) * (isThread ? 1.35 : (hasMedia ? 1.2 : 0.95));
            long views = Math.round((1800 + rng.nextInt(5200)) * mult);
            int likes = (int) Math.round(views * (0.022 + rng.nextDouble() * 0.026));
            int replies = (int) Math.round(views * (0.005 + rng.nextDouble() * 0.010));
            int retweets = (int) Math.round(views * (0.003 + rng.nextDouble() * 0.007));
            int bookmarks = (int) Math.round(views * (isThread ? 0.012 : 0.004));

            List<String> mentions = (i % 4 == 0) ? List.of(peers.get(i % 5).username()) : List.of();
            String myPostId = String.valueOf(1889000000000000000L + seed % 100000 + i);
            myPostIds.add(myPostId);
            timeline.add(new RawTweet(
                    myPostId,
                    tpl,
                    targetTime.toInstant(),
                    TweetKind.POST,
                    null,
                    null,
                    mentions,
                    hasMedia,
                    isThread,
                    views,
                    likes,
                    replies,
                    retweets,
                    bookmarks,
                    false,
                    myPostId,
                    ""
            ));
        }

        // 生成引用推文
        for (int i = 0; i < quoteCount; i++) {
            XUserSummary target = peers.get(i % 7);
            Instant ts = now.minus(Duration.ofHours(10L + i * 95L + rng.nextInt(24)));
            long views = 1400 + rng.nextInt(2800);
            String quoteId = String.valueOf(1889100000000000000L + i);
            myPostIds.add(quoteId);
            timeline.add(new RawTweet(
                    quoteId,
                    "非常认可 @" + target.username() + " 的一线实战观察，补充一点我们在社区冷启动阶段的实测数据：",
                    ts,
                    TweetKind.QUOTE,
                    target.username(),
                    target,
                    List.of(target.username()),
                    false,
                    false,
                    views,
                    38 + rng.nextInt(55),
                    9 + rng.nextInt(15),
                    6 + rng.nextInt(12),
                    14 + rng.nextInt(25),
                    false,
                    quoteId,
                    ""
            ));
        }

        // 精细化生成双向「1个帖子算1次」真实关系矩阵（含同帖多次追评去重、本帖内礼貌回复不虚增、以及帖内漏回检测）
        int[][] interactionMatrix = {
                // {peerIdx, myVisitedPosts, peerVisitedMyPosts, peerQuotesOfMe}
                {0, 8, 9, 2},   // lin_indie: 双向互评核心铁粉
                {1, 7, 7, 1},   // zoe_growth: 双向互评核心铁粉
                {2, 2, 7, 1},   // kai_rust: 我欠回访（他来评7帖，我仅去2帖）
                {3, 6, 6, 1},   // momo_design: 双向互评
                {4, 9, 1, 0},   // ethan_ai: 我去多·他回少 (9:1)
                {5, 6, 7, 1},   // nina_yap: 双向互评
                {6, 4, 4, 0},   // leo_quant: 均衡互评
                {7, 0, 5, 0},   // sara_pm: 我没回她（来评我5帖，我去0帖，含1帖未在评论区回复！）
                {9, 5, 0, 0},   // 未回关推友：我去评5帖未回访（仅在他自己帖内礼貌接话1次）
                {11, 5, 5, 0},  // yuki_maker: 均衡互评
                {12, 1, 5, 0},  // derek_ops: 他评我多·待回访
                {18, 4, 3, 0},  // ivy_creator: 双向互评
                {20, 0, 4, 1},  // fan_amber: 单向关注我的热情粉丝（来评4帖我去0帖·提醒回关+回访！）
                {21, 0, 3, 0},  // fan_bruce: 单向关注我的粉丝（来评3帖我去0帖，且帖内漏回1帖！）
                {24, 5, 0, 0},  // unrecip_jason: 普通号，我去评5帖仍0回访且未回关（典型谁没回我·单向消耗！）
                {25, 3, 0, 0}   // unrecip_kevin: 普通号，我去评3帖仍0回访且未回关
        };

        long seq = 1;
        for (int[] row : interactionMatrix) {
            XUserSummary peer = peers.get(row[0]);
            int outPosts = row[1];
            int inPosts = row[2];
            int inQuotes = row[3];

            for (int r = 0; r < outPosts; r++) {
                Instant ts = now.minus(Duration.ofHours(3L + (seq * 9L) % (26 * 24)));
                String peerRootPostId = "peer_post_" + peer.username() + "_" + r;
                String myCommentId = String.valueOf(1889200000000000000L + seq++);
                String replyText = OUTBOUND_REPLY_TEMPLATES[(int) (seq % OUTBOUND_REPLY_TEMPLATES.length)];
                timeline.add(new RawTweet(
                        myCommentId,
                        replyText,
                        ts,
                        TweetKind.REPLY,
                        peer.username(),
                        peer,
                        List.of(peer.username()),
                        false,
                        false,
                        180 + rng.nextInt(650),
                        4 + rng.nextInt(14),
                        1 + rng.nextInt(3),
                        0,
                        rng.nextInt(3),
                        false,
                        peerRootPostId,
                        peerRootPostId
                ));
                // 仿真「同一个帖子我追评第 2 条」：引擎必须按 1 个帖子算 1 次，合并同帖追评
                if (r == 0 && outPosts >= 4) {
                    timeline.add(new RawTweet(
                            String.valueOf(1889250000000000000L + seq++),
                            "再补充一个细节观察，同帖二楼追评交流！",
                            ts.plus(Duration.ofMinutes(12)),
                            TweetKind.REPLY,
                            peer.username(),
                            peer,
                            List.of(peer.username()),
                            false,
                            false,
                            95,
                            3,
                            0,
                            0,
                            0,
                            false,
                            peerRootPostId,
                            myCommentId
                    ));
                }
                // 仿真「对方在他自己帖子下礼貌接话」：不计入他来我推文评论次数
                if (row[0] == 9 && r == 0) {
                    inbound.add(new InboundInteraction(
                            String.valueOf(1889350000000000000L + seq++),
                            peer,
                            ts.plus(Duration.ofMinutes(20)),
                            InboundKind.REPLY_TO_ME,
                            "Thanks for sharing your perspective!",
                            peerRootPostId,
                            myCommentId,
                            cleanHandle
                    ));
                }
            }

            for (int r = 0; r < inPosts; r++) {
                Instant ts = now.minus(Duration.ofHours(2L + (seq * 11L) % (26 * 24)));
                // 使用唯一槽位避免同一推友随机撞上同一个 myPostId 导致预期演示帖数不同
                String targetMyPostId = myPostIds.get((row[0] * 3 + r) % myPostIds.size());
                String peerCommentId = String.valueOf(1889300000000000000L + seq++);
                String snippet = INBOUND_REPLY_TEMPLATES[(int) (seq % INBOUND_REPLY_TEMPLATES.length)];
                inbound.add(new InboundInteraction(
                        peerCommentId,
                        peer,
                        ts,
                        InboundKind.REPLY_TO_ME,
                        snippet,
                        targetMyPostId,
                        targetMyPostId,
                        cleanHandle
                ));
                // 我在自己帖子下礼貌回复对方评论（不计入「我去评他帖」次数）；保留少量未回复样本验证漏回提醒
                boolean leaveUnrepliedForDemo = (row[0] == 7 || row[0] == 21) && r == 0;
                if (!leaveUnrepliedForDemo) {
                    timeline.add(new RawTweet(
                            String.valueOf(1889280000000000000L + seq++),
                            "感谢 @" + peer.username() + " 捧场交流！",
                            ts.plus(Duration.ofMinutes(18)),
                            TweetKind.REPLY,
                            peer.username(),
                            peer,
                            List.of(peer.username()),
                            false,
                            false,
                            70,
                            2,
                            0,
                            0,
                            0,
                            false,
                            targetMyPostId,
                            peerCommentId
                    ));
                }
            }

            for (int q = 0; q < inQuotes; q++) {
                Instant ts = now.minus(Duration.ofHours(16L + (seq * 19L) % (26 * 24)));
                String quoteTweetId = String.valueOf(1889400000000000000L + seq++);
                inbound.add(new InboundInteraction(
                        quoteTweetId,
                        peer,
                        ts,
                        InboundKind.QUOTE_ME,
                        "强烈推荐关注 @" + cleanHandle + " 的这篇实战复盘，干货满满！",
                        quoteTweetId,
                        "",
                        ""
                ));
            }
        }

        // 补充少量转推
        for (int i = 0; i < 3; i++) {
            XUserSummary target = peers.get(i);
            timeline.add(new RawTweet(
                    String.valueOf(1889500000000000000L + i),
                    "RT @" + target.username(),
                    now.minus(Duration.ofDays(2L + i * 6L)),
                    TweetKind.RETWEET,
                    target.username(),
                    target,
                    List.of(),
                    false,
                    false,
                    0, 0, 0, 0, 0
            ));
        }

        String banner = (reasonNotice != null && !reasonNotice.isBlank())
                ? reasonNotice
                : "当前展示仿真演示数据。服务端配置 API 密钥后将自动同步你的实时账号数据。";

        return new RawAccountSnapshot(
                ProviderMode.DEMO,
                false,
                banner,
                now,
                profile,
                timeline,
                inbound,
                relationships
        );
    }

    private XUserSummary buildDemoProfile(String handle, String lower, Random rng) {
        return switch (lower) {
            case "alex_builds" -> new XUserSummary(
                    "10001", "alex_builds", "Alex · 独立开发与出海", "",
                    "全栈独立开发者 | 正在公开构建第 3 款 AI SaaS | 关注出海增长与 X 社区运营 #BuildInPublic",
                    true, 12840, 486, 1940, "2021-03"
            );
            case "maya_web3" -> new XUserSummary(
                    "10002", "maya_web3", "Maya · 社区增长主理人", "",
                    "前头部交易所社区增长 | 日均 30+ 深度互动 Yap 实践者 | 助推创作者冷启动",
                    true, 26400, 812, 4320, "2020-08"
            );
            case "chen_ai_lab" -> new XUserSummary(
                    "10003", "chen_ai_lab", "Chen · AI 产品实验室", "",
                    "AI Agent 架构与产品落地 | 每周二/四更新深度实测长推 Thread",
                    true, 18950, 365, 1180, "2022-01"
            );
            default -> new XUserSummary(
                    "u_" + Math.abs(lower.hashCode()),
                    handle,
                    formatDisplayName(handle) + " · X 创作者",
                    "",
                    "专注内容创作与 X 社区高质量同频交流",
                    rng.nextBoolean(),
                    1800 + rng.nextInt(16000),
                    280 + rng.nextInt(520),
                    420 + rng.nextInt(2200),
                    "2022-09"
            );
        };
    }

    private String formatDisplayName(String handle) {
        if (handle.isEmpty()) return "Creator";
        return Character.toUpperCase(handle.charAt(0)) + handle.substring(1);
    }

    private long stableSeed(String text) {
        long h = 1125899906842597L;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h = 31 * h + (b & 0xff);
        }
        return h;
    }
}
