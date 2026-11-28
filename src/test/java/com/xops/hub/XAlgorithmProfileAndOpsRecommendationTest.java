package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.XAlgorithmController;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.*;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.model.XAlgorithmProfile;
import com.xops.hub.model.XAlgorithmProfile.ScoringWeights;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.XAlgorithmProfileRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XAlgorithmProfileAndOpsRecommendationTest {

    private static final Instant NOW = Instant.parse("2026-11-15T01:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static XUserSummary user(String id, String username) {
        return new XUserSummary(id, username, username, "", "", false, 200, 10, 50, "2020-01-01");
    }

    @Test
    void builtinProfileScoresAuthorEngagedPostsHigherAndRespectsSampleCappedGuard() {
        XUserSummary self = user("u-self", "self_ops");
        XUserSummary peer1 = user("u-p1", "peer_one");
        XUserSummary peer2 = user("u-p2", "peer_two");
        XUserSummary coldFollow = user("u-cold", "cold_follow");

        // p1（有作者帖内接话）与 p2（无作者接话、且有待回复评论）基础互动一致，间隔 > 4 小时排除连发干扰
        Instant t1 = NOW.minus(Duration.ofHours(10));
        Instant t2 = NOW.minus(Duration.ofHours(5));
        Instant tExt = NOW.minus(Duration.ofHours(20));

        RawTweet p1 = new RawTweet("p1", "主帖一：拆解 Phoenix 多动作权重设计，欢迎讨论？",
                t1, TweetKind.POST, null, null, List.of(), false, false,
                300, 10, 3, 2, 3, false, "conv-p1", "");
        RawTweet authorReplyToP1 = new RawTweet("r-self-p1", "@peer_one 是的，作者接话会放大对话信号",
                t1.plusSeconds(120), TweetKind.REPLY, "peer_one", peer1, List.of("peer_one"), false, false,
                50, 1, 0, 0, 0, false, "conv-p1", "in-p1");
        RawTweet p2 = new RawTweet("p2", "主帖二：拆解 Phoenix 多动作权重设计，欢迎讨论？",
                t2, TweetKind.POST, null, null, List.of(), false, false,
                300, 10, 3, 2, 3, false, "conv-p2", "");
        RawTweet extLinkPost = new RawTweet("p-ext", "外部文章链接参考 https://example.com/post",
                tExt, TweetKind.POST, null, null, List.of(), false, false,
                60, 1, 0, 0, 0, false, "conv-ext", "");

        List<InboundInteraction> inbound = List.of(
                new InboundInteraction("in-p1", peer1, t1.plusSeconds(60), InboundKind.REPLY_TO_ME,
                        "很实用", "conv-p1", "p1", "self_ops"),
                new InboundInteraction("in-p2", peer2, t2.plusSeconds(60), InboundKind.REPLY_TO_ME,
                        "请问能展开讲讲权重吗？", "conv-p2", "p2", "self_ops")
        );
        List<RelationshipEntry> relations = List.of(
                new RelationshipEntry(coldFollow, true, false, true)
        );

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();

        RawAccountSnapshot fullSnap = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", NOW, self,
                List.of(p1, authorReplyToP1, p2, extLinkPost), inbound, relations,
                false, false, 30, null, "", true, null, "", true, false);
        CommunityDashboardReport fullReport = engine.analyze(fullSnap, 7, ZONE, false);

        TopPostCard cardP1 = fullReport.contentTiming().topPosts().stream()
                .filter(c -> c.id().equals("p1")).findFirst().orElseThrow();
        TopPostCard cardP2 = fullReport.contentTiming().topPosts().stream()
                .filter(c -> c.id().equals("p2")).findFirst().orElseThrow();

        assertThat(cardP1.algoSignals()).anyMatch(s -> s.contains("帖内作者接话"));
        assertThat(cardP1.algoScore()).isGreaterThan(cardP2.algoScore());
        assertThat(fullReport.algorithmHealth().algoVersion()).isEqualTo("2026.11-phoenix-hm");
        assertThat(fullReport.algorithmHealth().algoName()).isEqualTo("Phoenix + Home Mixer");
        assertThat(fullReport.algorithmHealth().authorEngagedPostCount()).isEqualTo(1);
        assertThat(fullReport.algorithmHealth().opsRecommendations())
                .extracting(OpsRecommendationItem::code)
                .contains("AUTHOR_THREAD_REPLY", "EXT_LINK_SELF_REPLY", "CLEAN_LOW_SIGNAL_FOLLOWS");

        // 样本未完整覆盖时，严禁输出「可清理未回关」误导推荐
        Instant tail = NOW.minus(Duration.ofHours(1));
        RawAccountSnapshot partialSnap = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", NOW, self,
                List.of(p1, authorReplyToP1, p2, extLinkPost), inbound, relations,
                false, false, 0, tail, "", false, tail, "", false, false);
        CommunityDashboardReport partialReport = engine.analyze(partialSnap, 7, ZONE, false);

        assertThat(partialReport.activity().sampleCapped()).isTrue();
        assertThat(partialReport.algorithmHealth().opsRecommendations())
                .extracting(OpsRecommendationItem::code)
                .doesNotContain("CLEAN_LOW_SIGNAL_FOLLOWS");
    }

    @Test
    void hotReloadAndOverrideRecomputeCachedBundleWithoutUpstreamCalls(@TempDir Path tempDir) throws Exception {
        Path profilePath = tempDir.resolve("x-algorithm-profile.json");
        Path diskCacheDir = tempDir.resolve("cache");

        XOpsProperties props = new XOpsProperties();
        props.setDefaultProvider("DEMO");
        props.setDiskCacheDir(diskCacheDir.toString());
        props.setAlgoProfilePath(profilePath.toString());
        props.setCacheTtlSeconds(300L);
        props.setRequireLoginSelfOnly(false);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        XAlgorithmProfileRegistry registry = new XAlgorithmProfileRegistry(props, objectMapper);

        AtomicInteger upstreamCalls = new AtomicInteger(0);
        DemoXDataProvider countingDemo = new DemoXDataProvider() {
            @Override
            public RawAccountSnapshot generateSnapshot(String username, String reasonNotice) {
                upstreamCalls.incrementAndGet();
                return super.generateSnapshot(username, reasonNotice);
            }
        };

        CommunityHubService hubService = new CommunityHubService(
                props, countingDemo, null, null, objectMapper, registry);

        // 首次分析触发 1 次上游抓取并写入缓存
        MultiPeriodDashboardBundle b1 = hubService.analyzeAccountBundle("demo_ops_user", 30, "DEMO", false, "127.0.0.1");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(b1.month().algorithmHealth().algoVersion()).isEqualTo("2026.11-phoenix-hm");
        double initialTopScore = b1.month().contentTiming().topPosts().stream()
                .mapToDouble(TopPostCard::algoScore).findFirst().orElse(0.0);

        // 同指纹二次读取命中内存缓存
        MultiPeriodDashboardBundle b1Cached = hubService.analyzeAccountBundle("demo_ops_user", 30, "DEMO", false, "127.0.0.1");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(b1Cached.month().fromCache()).isTrue();

        // 通过 saveOverride 提高 baseScore 并更新版本号，缓存期内零上游 API 调用即重算分数
        XAlgorithmProfile base = registry.currentProfile();
        ScoringWeights boosted = new ScoringWeights(
                Math.min(50.0, base.scoring().baseScore() + 20.0),
                base.scoring().minScore(), base.scoring().maxScore(),
                base.scoring().replyMaxPoints(), base.scoring().replyRatePivotPct(),
                base.scoring().replyRateCoef(), base.scoring().replyCountCap(), base.scoring().replyCountCoef(),
                base.scoring().authorReplyEngagementBonus(),
                base.scoring().dwellBookmarkMaxPoints(), base.scoring().bookmarkRatePivotPct(),
                base.scoring().bookmarkRateCoef(), base.scoring().longTextMinChars(),
                base.scoring().longTextBonus(), base.scoring().mediaDwellBonus(),
                base.scoring().shareQuoteMaxPoints(), base.scoring().repostRatePivotPct(),
                base.scoring().repostRateCoef(), base.scoring().reachMultipleCap(), base.scoring().reachMultipleCoef(),
                base.scoring().quotePostBonus(),
                base.scoring().likeMaxPoints(), base.scoring().likeRatePivotPct(),
                base.scoring().likeRateCoef(), base.scoring().likeCountCap(), base.scoring().likeCountCoef(),
                base.scoring().compositeReplyWeight(), base.scoring().compositeRepostWeight(),
                base.scoring().compositeBookmarkWeight(), base.scoring().compositeLikeWeight(),
                base.scoring().compositeViewLogWeight(), base.scoring().compositeAuthorEngagedBonus()
        );
        XAlgorithmProfile customProfile = new XAlgorithmProfile(
                "2026.12-custom-boost",
                "Phoenix Custom Boost",
                "2026-11-16",
                base.sourceUrl(),
                List.of("测试热更新：提升基础分用于验证重算"),
                boosted,
                base.cadenceAndReach(),
                base.opsPlaybook(),
                base.draftChecker()
        );
        var overrideState = registry.saveOverride(customProfile);
        assertThat(overrideState.source()).isEqualTo("LOCAL_OVERRIDE");
        assertThat(Files.exists(profilePath)).isTrue();

        MultiPeriodDashboardBundle b2 = hubService.analyzeAccountBundle("demo_ops_user", 30, "DEMO", false, "127.0.0.1");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(b2.month().algorithmHealth().algoVersion()).isEqualTo("2026.12-custom-boost");
        double boostedTopScore = b2.month().contentTiming().topPosts().stream()
                .mapToDouble(TopPostCard::algoScore).findFirst().orElse(0.0);
        assertThat(boostedTopScore).isGreaterThan(initialTopScore);

        // 直接在磁盘上改写覆盖文件（模拟外部脚本拉取上游算法后写盘），无需重启即可自动热加载
        String diskJson = Files.readString(profilePath)
                .replace("2026.12-custom-boost", "2027.01-disk-hot-reload-ext");
        Files.writeString(profilePath, diskJson);

        MultiPeriodDashboardBundle b3 = hubService.analyzeAccountBundle("demo_ops_user", 30, "DEMO", false, "127.0.0.1");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(b3.month().algorithmHealth().algoVersion()).isEqualTo("2027.01-disk-hot-reload-ext");

        // 一键还原内置基线
        var resetState = registry.resetToBuiltin();
        assertThat(resetState.source()).isEqualTo("BUILTIN");
        MultiPeriodDashboardBundle b4 = hubService.analyzeAccountBundle("demo_ops_user", 30, "DEMO", false, "127.0.0.1");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(b4.month().algorithmHealth().algoVersion()).isEqualTo("2026.11-phoenix-hm");
    }

    @Test
    void controllerEnforcesAuthorOrLocalPermissionsAndValidatesProfiles(@TempDir Path tempDir) {
        XOpsProperties props = new XOpsProperties();
        props.setRequireLoginSelfOnly(true);
        props.setAlgoProfilePath(tempDir.resolve("algo-override.json").toString());

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        XAlgorithmProfileRegistry registry = new XAlgorithmProfileRegistry(props, objectMapper);
        XAlgorithmController controller = new XAlgorithmController(
                props,
                registry,
                new com.xops.hub.assistant.AssistantStore(tempDir, objectMapper));

        // 1. 普通登录用户仅可只读查询（canManage = false），写入必须拒绝 403
        MockHttpServletRequest regularReq = new MockHttpServletRequest();
        regularReq.setRemoteAddr("10.0.0.8");
        regularReq.getSession(true).setAttribute(
                XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser("9002", "other_user", "Other User", "", false, NOW)
        );
        var readRes = controller.getProfile(regularReq, new MockHttpServletResponse());
        assertThat(readRes.canManage()).isFalse();
        assertThat(readRes.overridePath()).isEmpty();
        assertThat(readRes.profile().version()).isEqualTo("2026.11-phoenix-hm");

        assertThatThrownBy(() -> controller.updateProfile(readRes.profile(), regularReq, new MockHttpServletResponse()))
                .isInstanceOf(XDataProviderException.class)
                .satisfies(ex -> assertThat(((XDataProviderException) ex).getStatusCode()).isEqualTo(403));

        // 2. 管理员 @elidlicom 登录具备完整管理权限
        MockHttpServletRequest adminReq = new MockHttpServletRequest();
        adminReq.setRemoteAddr("10.0.0.9");
        adminReq.getSession(true).setAttribute(
                XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "Author", "", true, NOW)
        );
        var adminRead = controller.getProfile(adminReq, new MockHttpServletResponse());
        assertThat(adminRead.canManage()).isTrue();

        // 非法空版本号或越界权重被严格校验拦截（对应 ApiExceptionHandler 400）
        XAlgorithmProfile invalidVersion = new XAlgorithmProfile(
                " ", "Valid Name", "2026-11-15", "", List.of("v"),
                null, null, null, null);
        assertThatThrownBy(() -> controller.updateProfile(invalidVersion, adminReq, new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);

        ScoringWeights outOfRangeWeight = new ScoringWeights(
                999.0, 1, 99,
                1, 1, 1, 1, 1, 1,
                1, 1, 1, 200, 1, 1,
                1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1
        );
        XAlgorithmProfile invalidWeight = new XAlgorithmProfile(
                "2026.11-invalid-weight", "Invalid", "2026-11-15", "", List.of("v"),
                outOfRangeWeight, null, null, null);
        assertThatThrownBy(() -> controller.updateProfile(invalidWeight, adminReq, new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);

        // 合法覆盖保存成功
        XAlgorithmProfile validOverride = new XAlgorithmProfile(
                "2026.11-admin-hotfix",
                "Phoenix Admin Hotfix",
                "2026-11-15",
                "https://github.com/xai-org/x-algorithm",
                List.of("管理员在线调优版本"),
                adminRead.profile().scoring(),
                adminRead.profile().cadenceAndReach(),
                adminRead.profile().opsPlaybook(),
                adminRead.profile().draftChecker()
        );
        var saved = controller.updateProfile(validOverride, adminReq, new MockHttpServletResponse());
        assertThat(saved.source()).isEqualTo("LOCAL_OVERRIDE");
        assertThat(saved.profile().version()).isEqualTo("2026.11-admin-hotfix");
    }

    @Test
    void upstreamEvidenceFormulasAndEvidenceBasedDraftCheckerAndOpsPlaybookWork() {
        var ev = XAlgorithmProfile.UpstreamEvidenceSpec.defaults();
        assertThat(ev.upstreamCommit()).isEqualTo("590f3926e5bc");
        assertThat(ev.activeRankerService()).isEqualTo("vm_ranker");
        assertThat(ev.shareViaCopyLinkWeight()).isEqualTo(20.0);
        assertThat(ev.replyWeight()).isEqualTo(5.0);
        assertThat(ev.bidirectionalFollowReplyWeightBoost()).isEqualTo(15.0);
        assertThat(ev.openLinkWeight()).isEqualTo(0.2);
        assertThat(ev.reportWeight()).isEqualTo(-234.0);
        assertThat(ev.maxCandidateAgeHours()).isEqualTo(48);
        assertThat(ev.minVideoDurationMs()).isEqualTo(10_000);
        assertThat(ev.dppTheta()).isEqualTo(0.65);

        // xai-value-model/scoring.rs: diversity_multiplier = (1 - 0.25) * 0.5^k + 0.25
        assertThat(ev.authorDiversityMultiplier(0)).isEqualTo(1.0);
        assertThat(ev.authorDiversityMultiplier(1)).isEqualTo(0.625);
        assertThat(ev.authorDiversityMultiplier(2)).isEqualTo(0.4375);
        assertThat(ev.authorDiversityMultiplier(3)).isEqualTo(0.34375);

        // home-mixer/filters/oon_retweet_reply_filter.rs + scoring.rs: oon_applies
        assertThat(ev.postFusionOonMultiplier(false, true, false)).isEqualTo(0.0);
        assertThat(ev.postFusionOonMultiplier(false, false, true)).isEqualTo(0.0);
        assertThat(ev.postFusionOonMultiplier(false, false, false)).isEqualTo(0.75);
        assertThat(ev.postFusionOonMultiplier(true, true, false)).isEqualTo(0.75);
        assertThat(ev.postFusionOonMultiplier(true, false, false)).isEqualTo(1.0);

        // xai-value-model/weights.rs: reply_weight_for(candidate)
        assertThat(ev.effectiveReplyWeight(false, false, true)).isEqualTo(20.0);
        assertThat(ev.effectiveReplyWeight(true, false, true)).isEqualTo(5.0);
        assertThat(ev.effectiveReplyWeight(false, true, true)).isEqualTo(5.0);
        assertThat(ev.effectiveReplyWeight(false, false, false)).isEqualTo(5.0);

        // 无证据的主观加减分默认归零
        assertThat(XAlgorithmProfile.ScoringWeights.defaults().longTextBonus()).isEqualTo(0.0);
        assertThat(XAlgorithmProfile.ScoringWeights.defaults().mediaDwellBonus()).isEqualTo(0.0);
        assertThat(XAlgorithmProfile.CadenceAndReachRules.defaults().externalLinkPenalty()).isEqualTo(0);

        var dc = XAlgorithmProfile.DraftCheckerRules.defaults();
        assertThat(dc.baseScore()).isEqualTo(100);
        assertThat(dc.leadingMentionOonFilterPenalty()).isEqualTo(28);
        assertThat(dc.exactDuplicateTextPenalty()).isEqualTo(35);
        assertThat(dc.duplicateSimilarityThreshold()).isEqualTo(0.65);
        assertThat(XAlgorithmProfile.OpsPlaybookRules.defaults().maxRecommendations()).isEqualTo(10);

        var negMention = new XAlgorithmProfile.DraftCheckerRules(
                dc.baseScore(), dc.minScore(), dc.maxScore(), dc.excellentThreshold(), dc.goodThreshold(),
                -5, dc.authorDiversityMaxPenalty(), dc.queueCollisionPenalty(),
                dc.exactDuplicateTextPenalty(), dc.nearDuplicateTextPenalty(),
                dc.duplicateSimilarityThreshold(), dc.urlWithNonFollowerMentionPenalty()
        );
        assertThatThrownBy(negMention::validateStrict).isInstanceOf(IllegalArgumentException.class);

        var badSimThreshold = new XAlgorithmProfile.DraftCheckerRules(
                dc.baseScore(), dc.minScore(), dc.maxScore(), dc.excellentThreshold(), dc.goodThreshold(),
                dc.leadingMentionOonFilterPenalty(), dc.authorDiversityMaxPenalty(), dc.queueCollisionPenalty(),
                dc.exactDuplicateTextPenalty(), dc.nearDuplicateTextPenalty(),
                1.5, dc.urlWithNonFollowerMentionPenalty()
        );
        assertThatThrownBy(badSimThreshold::validateStrict).isInstanceOf(IllegalArgumentException.class);

        XUserSummary self = user("u-content", "content_ops");
        RawTweet t1 = new RawTweet("m1", "第一条纯文本主推，分析近期产品迭代策略",
                NOW.minus(Duration.ofHours(16)), TweetKind.POST, null, null, List.of(), false, false,
                120, 4, 1, 0, 0, false, "c1", "");
        RawTweet t2 = new RawTweet("m2", "第二条纯文本主推，聊聊独立开发者定价思路",
                NOW.minus(Duration.ofHours(10)), TweetKind.POST, null, null, List.of(), false, false,
                120, 4, 1, 0, 0, false, "c2", "");
        RawTweet t3 = new RawTweet("m3", "第三条图文主推，附带实拍架构拆解图",
                NOW.minus(Duration.ofHours(4)), TweetKind.POST, null, null, List.of(), true, false,
                120, 4, 1, 0, 0, false, "c3", "");

        RawAccountSnapshot snap = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", NOW, self,
                List.of(t1, t2, t3), List.of(), List.of(),
                false, false, 30, null, "", true, null, "", true, false);

        CommunityDashboardReport report = new CommunityAnalyticsEngine().analyze(snap, 7, ZONE, false);
        ContentFormatStat textStat = report.contentTiming().formatStats().stream()
                .filter(f -> "TEXT".equals(f.formatCode())).findFirst().orElseThrow();
        ContentFormatStat mediaStat = report.contentTiming().formatStats().stream()
                .filter(f -> "MEDIA".equals(f.formatCode())).findFirst().orElseThrow();

        assertThat(textStat.bookmarkSampleCount()).isEqualTo(2);
        assertThat(textStat.algoScoreSampleCount()).isEqualTo(2);
        assertThat(textStat.avgAlgoScore()).isGreaterThan(0);
        assertThat(textStat.algoAdvice())
                .contains("提升留存与分享")
                .doesNotContain("PhoenixScores", "vm-ranker");

        assertThat(mediaStat.bookmarkSampleCount()).isEqualTo(1);
        assertThat(mediaStat.algoScoreSampleCount()).isEqualTo(1);
        assertThat(mediaStat.avgAlgoScore()).isGreaterThan(0);
        assertThat(mediaStat.algoAdvice())
                .contains("图片点击展开与视频观看")
                .doesNotContain("photo_expand", "vm-ranker");

        assertThat(report.algorithmHealth().opsRecommendations())
                .extracting(OpsRecommendationItem::code)
                .contains("BOOKMARK_DWELL_STRUCTURE", "OON_QUOTE_REPOST_HOOK", "AUTHOR_REPLY_DESIGN");
        assertThat(report.algorithmHealth().opsRecommendations())
                .allSatisfy(item -> {
                    assertThat(item.detail()).doesNotContain(
                            "reply_engaged_by_author",
                            "对停留(dwell)与书签收藏(bookmark)高加权",
                            "ExplicitEngagementSignalsQueryHydrator",
                            "BBQDuplicateTextRepliesProd",
                            "Scarecrow"
                    );
                });
        // 仅 3 条样本 (< 6) 时必须锁定预测，拒绝伪造分位预判
        assertThat(report.algorithmHealth().calibration().samplePostCount()).isEqualTo(3);
        assertThat(report.algorithmHealth().calibration().predictionUnlocked()).isFalse();
    }

    @Test
    void empiricalCalibrationPreservesBaselineAndRejectsSmallSampleReview() {
        XUserSummary self = user("u-cal", "cal_ops");
        XUserSummary mutualPeer = user("u-mp", "mutual_peer");
        XUserSummary fanOnlyPeer = user("u-fp", "fan_only_peer");

        // 构造 8 条间隔充足 (≥5h) 的历史独立主推：
        // - 6 条正常表现帖（曝光与互动正向匹配，含 github.com 与 bit.ly 外链）
        // - 1 条爆款图文帖
        // - 1 条负残差异常帖（互动率极高但曝光被异常压在 35）
        List<RawTweet> timeline = List.of(
                new RawTweet("c1", "架构复盘一：缓存分层与读写隔离实践 https://github.com/xai-org/x-algorithm",
                        NOW.minus(Duration.ofHours(60)), TweetKind.POST, null, null, List.of(), true, false,
                        900, 28, 8, 5, 10, false, "cv1", ""),
                new RawTweet("c2", "架构复盘二：异步线程池队列容量压测对比 https://github.com/xai-org/x-algorithm",
                        NOW.minus(Duration.ofHours(52)), TweetKind.POST, null, null, List.of(), true, false,
                        860, 25, 7, 4, 9, false, "cv2", ""),
                new RawTweet("c3", "随手记一句日常状态",
                        NOW.minus(Duration.ofHours(44)), TweetKind.POST, null, null, List.of(), false, false,
                        220, 2, 0, 0, 0, false, "cv3", ""),
                new RawTweet("c4", "短链测试帖 https://bit.ly/3xyzabc",
                        NOW.minus(Duration.ofHours(36)), TweetKind.POST, null, null, List.of(), false, false,
                        180, 2, 0, 0, 0, false, "cv4", ""),
                new RawTweet("c5", "架构复盘三：索引基数与慢查询排查清单？",
                        NOW.minus(Duration.ofHours(28)), TweetKind.POST, null, null, List.of(), true, false,
                        1100, 36, 11, 7, 15, false, "cv5", ""),
                new RawTweet("c6", "周末读书摘录整理",
                        NOW.minus(Duration.ofHours(20)), TweetKind.POST, null, null, List.of(), false, false,
                        260, 3, 1, 0, 1, false, "cv6", ""),
                new RawTweet("c7", "负残差异常样本：高争议敏感词测试但忠实读者拼命评论转发",
                        NOW.minus(Duration.ofHours(12)), TweetKind.POST, null, null, List.of(), false, false,
                        35, 10, 6, 4, 5, false, "cv7", ""),
                new RawTweet("c8", "架构复盘四：零停机热更新配置中心实现细节？",
                        NOW.minus(Duration.ofHours(5)), TweetKind.POST, null, null, List.of(), true, false,
                        980, 32, 9, 6, 12, false, "cv8", ""),
                // 3 条一模一样的暖场模板回评 -> 触发 BBQDuplicateTextRepliesProd 预警
                new RawTweet("r1", "@mutual_peer 写得太棒了完全赞同支持一下！",
                        NOW.minus(Duration.ofHours(4)), TweetKind.REPLY, "mutual_peer", mutualPeer, List.of("mutual_peer"), false, false,
                        20, 1, 0, 0, 0, false, "ext-1", "ext-1"),
                new RawTweet("r2", "@fan_only_peer 写得太棒了，完全赞同，支持一下",
                        NOW.minus(Duration.ofHours(3)), TweetKind.REPLY, "fan_only_peer", fanOnlyPeer, List.of("fan_only_peer"), false, false,
                        20, 1, 0, 0, 0, false, "ext-2", "ext-2"),
                new RawTweet("r3", "@mutual_peer 写得太棒了！完全赞同！支持一下！",
                        NOW.minus(Duration.ofHours(2)), TweetKind.REPLY, "mutual_peer", mutualPeer, List.of("mutual_peer"), false, false,
                        20, 1, 0, 0, 0, false, "ext-3", "ext-3")
        );

        // 4 条入站回复：3 条来自互关好友 mutual_peer，1 条来自单向粉丝 fan_only_peer -> 互关占比 75% -> 有效 reply 权重 = 5 + 15 * 0.75 = 16.25
        List<InboundInteraction> inbound = List.of(
                new InboundInteraction("ib1", mutualPeer, NOW.minus(Duration.ofHours(59)), InboundKind.REPLY_TO_ME, "赞", "cv1", "c1", "cal_ops"),
                new InboundInteraction("ib2", mutualPeer, NOW.minus(Duration.ofHours(51)), InboundKind.REPLY_TO_ME, "好文", "cv2", "c2", "cal_ops"),
                new InboundInteraction("ib3", mutualPeer, NOW.minus(Duration.ofHours(27)), InboundKind.REPLY_TO_ME, "收藏了", "cv5", "c5", "cal_ops"),
                new InboundInteraction("ib4", fanOnlyPeer, NOW.minus(Duration.ofHours(4)), InboundKind.REPLY_TO_ME, "学习", "cv8", "c8", "cal_ops")
        );
        List<RelationshipEntry> relations = List.of(
                new RelationshipEntry(mutualPeer, true, true, true),
                new RelationshipEntry(fanOnlyPeer, false, true, true)
        );

        RawAccountSnapshot snap = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", NOW, self,
                timeline, inbound, relations,
                false, false, 30, null, "", true, null, "", true, false);

        CommunityDashboardReport report = new CommunityAnalyticsEngine().analyze(snap, 7, ZONE, false);
        EmpiricalAlgoCalibration cal = report.algorithmHealth().calibration();

        assertThat(cal.samplePostCount()).isEqualTo(8);
        assertThat(cal.predictionUnlocked()).isTrue();
        assertThat(cal.mutualShareKnown()).isTrue();
        assertThat(cal.mutualCommenterSharePct()).isEqualTo(75.0);
        assertThat(cal.inferredEffectiveReplyWeight()).isEqualTo(16.25);
        assertThat(cal.coldStartEligibleAccount()).isTrue();
        assertThat(cal.coldStartPassCount()).isEqualTo(5);
        assertThat(cal.loocvBandHitRatePct()).isGreaterThan(0.0);

        assertThat(cal.domainStats())
                .extracting(DomainReachStat::domain)
                .contains("github.com", "bit.ly");
        DomainReachStat bitlyStat = cal.domainStats().stream()
                .filter(d -> "bit.ly".equals(d.domain())).findFirst().orElseThrow();
        assertThat(bitlyStat.shortenerDomain()).isTrue();

        assertThat(cal.replyTemplateRisk().copyPastaRiskTriggered()).isTrue();
        assertThat(cal.replyTemplateRisk().maxClusterSize()).isEqualTo(3);

        // 35 次浏览仍不足以形成可靠复盘候选；高平滑互动率不能抵消样本量不足。
        assertThat(cal.residualAnomalies())
                .extracting(ResidualAnomalyPost::id)
                .doesNotContain("c7");

        assertThat(report.algorithmHealth().opsRecommendations())
                .extracting(OpsRecommendationItem::code)
                .contains("REPLY_TEMPLATE_DEDUP")
                .doesNotContain("RESIDUAL_ANOMALY_REVIEW");
        assertThat(report.algorithmHealth().opsRecommendations())
                .noneMatch(item -> item.externalUrl() != null && item.externalUrl().endsWith("/c7"));

        assertThat(report.algorithmHealth().opsRecommendations())
                .extracting(OpsRecommendationItem::code)
                .doesNotContain("GOLDEN_SLOT_LAUNCH");

        // 短口头禅/笑声（如「哈哈哈哈」「谢谢支持」）即使多次出现也不应误报为刷屏模板
        List<RawTweet> casualReplies = List.of(
                new RawTweet("cr1", "@mutual_peer 哈哈哈哈！", NOW.minus(Duration.ofHours(4)), TweetKind.REPLY, "mutual_peer", mutualPeer, List.of("mutual_peer"), false, false, 10, 0, 0, 0, 0, false, "e1", "e1"),
                new RawTweet("cr2", "@fan_only_peer 哈哈哈哈", NOW.minus(Duration.ofHours(3)), TweetKind.REPLY, "fan_only_peer", fanOnlyPeer, List.of("fan_only_peer"), false, false, 10, 0, 0, 0, 0, false, "e2", "e2"),
                new RawTweet("cr3", "@mutual_peer 哈哈哈哈。", NOW.minus(Duration.ofHours(2)), TweetKind.REPLY, "mutual_peer", mutualPeer, List.of("mutual_peer"), false, false, 10, 0, 0, 0, 0, false, "e3", "e3"),
                new RawTweet("cr4", "@mutual_peer 哈哈哈哈哈", NOW.minus(Duration.ofHours(1)), TweetKind.REPLY, "mutual_peer", mutualPeer, List.of("mutual_peer"), false, false, 10, 0, 0, 0, 0, false, "e4", "e4")
        );
        RawAccountSnapshot casualSnap = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", NOW, self,
                casualReplies, List.of(), relations,
                false, false, 30, null, "", true, null, "", true, false);
        CommunityDashboardReport casualReport = new CommunityAnalyticsEngine().analyze(casualSnap, 7, ZONE, false);
        assertThat(casualReport.algorithmHealth().calibration().replyTemplateRisk().copyPastaRiskTriggered()).isFalse();
    }
}
