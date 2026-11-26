package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.TwitterApiIoProvider.TextPost;
import com.xops.hub.provider.TwitterApiIoProvider.TextPostPage;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AutoRepostAndTierTest {
    @TempDir Path tempDir;

    @Test
    void tierSchedulingGivesOneMinuteSourcesZeroJitterAndTopPriorityAndPropagatesAuthorAvatar() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var oneMinSource = mapper.createObjectNode()
                .put("id", "src-1m")
                .put("intervalMinutes", 1)
                .put("jitterMs", 42_000L)
                .put("lastAttemptAt", 1_000_000L);
        assertThat(SourceCollector.sourceDue(oneMinSource, 1_000_000L + 59_000L, 60_000L)).isFalse();
        assertThat(SourceCollector.sourceDue(oneMinSource, 1_000_000L + 60_000L, 60_000L)).isTrue();

        var thirtyMinSource = mapper.createObjectNode()
                .put("id", "src-30m")
                .put("intervalMinutes", 30)
                .put("jitterMs", 20_000L)
                .put("lastAttemptAt", 1_000_000L);
        assertThat(SourceCollector.sourceDue(thirtyMinSource, 1_000_000L + 30 * 60_000L + 10_000L, 30 * 60_000L)).isFalse();
        assertThat(SourceCollector.sourceDue(thirtyMinSource, 1_000_000L + 30 * 60_000L + 20_000L, 30 * 60_000L)).isTrue();
        assertThatThrownBy(() -> SourceCollector.validateTierMinutes(7)).isInstanceOf(IllegalArgumentException.class);

        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant fixedNow = ZonedDateTime.of(2026, 11, 23, 20, 0, 0, 0, shanghai).toInstant();
        Clock clock = Clock.fixed(fixedNow, shanghai);

        AssistantStore store = new AssistantStore(tempDir.resolve("tiers-avatar"), mapper);
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(community.longHistoryCandidates()).thenReturn(List.of());

        XUserSummary nvidiaAuthor = new XUserSummary(
                "u-nvidia", "nvidia", "NVIDIA Newsroom", "https://pbs.twimg.com/profile_images/nv_normal.jpg",
                "", true, 5_000_000, 100, 2000, "");
        when(provider.resolvePublicAuthor("nvidia")).thenReturn(nvidiaAuthor);
        when(provider.fetchTextPostsPage(eq("nvidia"), eq("u-nvidia"), eq(""), eq(100), any()))
                .thenReturn(new TextPostPage(nvidiaAuthor, List.of(
                        new TextPost("1890001", "u-nvidia", "nvidia", fixedNow.minusSeconds(120), TweetKind.POST,
                                "NVIDIA 发布全新一代架构白皮书", "", "", "", "", "", "", "", "", "NVIDIA 发布全新一代架构白皮书",
                                "FULL", "NOT_PRESENT", "NOT_PRESENT", "TEXT_ONLY", false,
                                "https://x.com/nvidia/status/1890001", "1890001", false, false, "ROW_ID_VERIFIED",
                                50000L, 1200L, 80L, 300L, 200L)
                ), "", true));

        SourceCollector collector = new SourceCollector(store, provider, community, mapper, clock);
        AssistantWorker worker = new AssistantWorker(store, mock(AssistantIntelligence.class), collector);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("collectionEnabled", true).put("modelEnabled", false));
                return null;
            });

            collector.add("@nvidia", 1);
            collector.tick();

            var mvc = MockMvcBuilders.standaloneSetup(
                    new AssistantController(store, collector, worker, mock(AssistantPublishing.class), mapper))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, fixedNow));

            mvc.perform(get("/api/content-assistant/dashboard").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[0].intervalMinutes").value(1))
                    .andExpect(jsonPath("$.sources[0].displayName").value("NVIDIA Newsroom"))
                    .andExpect(jsonPath("$.sources[0].avatarUrl").value("https://pbs.twimg.com/profile_images/nv_normal.jpg"))
                    .andExpect(jsonPath("$.sources[0].verified").value(true))
                    .andExpect(jsonPath("$.materials[0].authorName").value("NVIDIA Newsroom"))
                    .andExpect(jsonPath("$.materials[0].authorAvatarUrl").value("https://pbs.twimg.com/profile_images/nv_normal.jpg"))
                    .andExpect(jsonPath("$.topics[0].authors[0].displayName").value("NVIDIA Newsroom"))
                    .andExpect(jsonPath("$.topics[0].authors[0].avatarUrl").value("https://pbs.twimg.com/profile_images/nv_normal.jpg"));

            mvc.perform(post("/api/content-assistant/sources/u-nvidia/config").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"intervalMinutes\":30}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.intervalMinutes").value(30));
        } finally {
            worker.close();
            collector.stop();
        }
    }

    @Test
    void chatGptOAuthBlocksApiKeyModeAndAutoRepostsViralBreakingNewsWithQuoteTweetId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fakeCodex = Files.createFile(tempDir.resolve("fake-codex"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment apiKeyEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");

        // 1. Verify API-key login mode is strictly blocked (must be ChatGPT OAuth third-party login).
        PiAgentViralJudge blockedJudge = new PiAgentViralJudge(mapper, apiKeyEnv, (cmd, stdin, timeout) ->
                new PiAgentViralJudge.CliResult(0, "Logged in using an API key", ""));
        ObjectNode blockedStatus = blockedJudge.checkAuth(true);
        assertThat(blockedStatus.path("ready").asBoolean()).isFalse();
        assertThat(blockedStatus.path("status").asText()).isEqualTo("OAUTH_REQUIRED");
        assertThat(blockedStatus.path("apiBillingDisabled").asBoolean()).isTrue();

        // 2. Verify ChatGPT OAuth login passes and evaluates viral breaking news vs routine posts.
        AtomicInteger judgeCalls = new AtomicInteger();
        PiAgentViralJudge oauthJudge = new PiAgentViralJudge(mapper, apiKeyEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            judgeCalls.incrementAndGet();
            int outIdx = cmd.indexOf("-o");
            String responseJson;
            if (stdin.contains("\"postText\":\"突发重磅：黄仁勋今晚宣布恢复总裁职务")) {
                responseJson = """
                        {"verdict":"VIRAL_REPOST","viralScore":95,"eventMagnitude":96,"publicResonance":94,"credibility":92,"category":"KEY_PERSONNEL","summary":"黄仁勋宣布恢复总裁职务","reason":"科技巨头核心掌门人重大职务变动，具备极强破圈传播力","commentText":"黄仁勋今晚宣布恢复总裁职务，意味着核心管理层将直接收拢一线业务决策权，后续战略动作值得重点跟进。"}
                        """;
            } else {
                responseJson = """
                        {"verdict":"SKIP","viralScore":22,"eventMagnitude":15,"publicResonance":20,"credibility":85,"category":"ROUTINE","summary":"日常问候","reason":"普通日常动态，无突发新闻价值","commentText":""}
                        """;
            }
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                Files.writeString(Path.of(cmd.get(outIdx + 1)), responseJson);
            }
            return new PiAgentViralJudge.CliResult(0, responseJson, "");
        });

        ObjectNode readyStatus = oauthJudge.checkAuth(true);
        assertThat(readyStatus.path("ready").asBoolean()).isTrue();
        assertThat(readyStatus.path("activeBackend").asText()).isEqualTo("CODEX_CLI_OAUTH");

        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant now = ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, shanghai).toInstant();
        Clock clock = Clock.fixed(now, shanghai);

        AssistantStore store = new AssistantStore(tempDir.resolve("auto-repost"), mapper);
        SourceCollector collector = mock(SourceCollector.class);
        XOAuthService oauthService = mock(XOAuthService.class);
        XPublishClient publishClient = mock(XPublishClient.class);
        when(oauthService.getValidWriteAccessToken("owner-1", false)).thenReturn("oauth-write-token");
        when(publishClient.createTweet(eq("oauth-write-token"), anyString(), eq(List.of()), eq("1890100")))
                .thenReturn("1890999");

        AutoRepostService autoRepost = new AutoRepostService(store, collector, oauthJudge, oauthService, publishClient, clock);
        try {
            long enabledAt = now.minusSeconds(300).toEpochMilli();
            ObjectNode source = store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("autoRepostCooldownMinutes", 0));
                return tx.put("source", "u-breaking", mapper.createObjectNode()
                        .put("authorId", "u-breaking").put("username", "breaking_tech")
                        .put("displayName", "科技快讯").put("status", "ACTIVE")
                        .put("intervalMinutes", 1)
                        .put("autoRepostEnabled", true)
                        .put("autoRepostEnabledAt", enabledAt)
                        .put("autoRepostTextMode", "AI")
                        .put("autoRepostMinScore", 82));
            });

            // A. Pre-existing post published BEFORE autoRepostEnabledAt must NOT trigger evaluation.
            ObjectNode oldMat = store.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "1890050").put("authorId", "u-breaking")
                        .put("username", "breaking_tech").put("text", "黄仁勋今晚宣布恢复总裁职务（旧帖）")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST").put("publishedAt", enabledAt - 10_000L);
                AssistantWorkflow.ingest(tx, m);
                return tx.get("material", "1890050");
            });
            autoRepost.processCandidate(source, oldMat);
            assertThat(judgeCalls.get()).isZero();

            // B. Routine post is evaluated and skipped without publishing.
            ObjectNode routineMat = store.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "1890099").put("authorId", "u-breaking")
                        .put("username", "breaking_tech").put("text", "大家晚上好，今天喝杯咖啡休息一下。")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST").put("publishedAt", now.minusSeconds(60).toEpochMilli());
                AssistantWorkflow.ingest(tx, m);
                return tx.get("material", "1890099");
            });
            autoRepost.processCandidate(source, routineMat);
            assertThat(judgeCalls.get()).isEqualTo(1);
            assertThat(store.<ObjectNode>tx(tx -> tx.get("auto_repost", "1890099")).path("status").asText()).isEqualTo("SKIPPED");
            verifyNoInteractions(publishClient);

            // C. Viral breaking news triggers native X Quote Tweet (quote_tweet_id = "1890100") and records publication.
            ObjectNode viralMat = store.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "1890100").put("authorId", "u-breaking")
                        .put("username", "breaking_tech").put("text", "突发重磅：黄仁勋今晚宣布恢复总裁职务，亲自挂帅下一代核心业务。")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                        .put("sourceUrl", "https://x.com/breaking_tech/status/1890100")
                        .put("publishedAt", now.minusSeconds(30).toEpochMilli());
                AssistantWorkflow.ingest(tx, m);
                return tx.get("material", "1890100");
            });
            autoRepost.processCandidate(source, viralMat);

            verify(publishClient, times(1)).createTweet(
                    eq("oauth-write-token"),
                    argThat(comment -> comment.contains("黄仁勋今晚宣布恢复总裁职务")),
                    eq(List.of()),
                    eq("1890100")
            );
            ObjectNode savedAuto = store.tx(tx -> tx.get("auto_repost", "1890100"));
            assertThat(savedAuto.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(savedAuto.path("publishedTweetId").asText()).isEqualTo("1890999");
            assertThat(savedAuto.path("viralScore").asInt()).isEqualTo(95);
            assertThat(store.<ObjectNode>tx(tx -> tx.get("publication", "auto-repost-1890100"))).isNotNull();

            // D. Exact duplicate breaking news from a second source in the same merged topic is deduplicated automatically.
            ObjectNode dupMat = store.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "1890101").put("authorId", "u-other")
                        .put("username", "other_media").put("text", "突发重磅：黄仁勋今晚宣布恢复总裁职务，亲自挂帅下一代核心业务。")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                        .put("publishedAt", now.minusSeconds(10).toEpochMilli());
                AssistantWorkflow.ingest(tx, m);
                return tx.get("material", "1890101");
            });
            autoRepost.processCandidate(source, dupMat);
            assertThat(store.<ObjectNode>tx(tx -> tx.get("auto_repost", "1890101")).path("status").asText())
                    .isEqualTo("SKIPPED_DUPLICATE");
            verify(publishClient, times(1)).createTweet(anyString(), anyString(), anyList(), anyString());

            // D2. Different breaking news from the SAME author (@breaking_tech) within 16 hours MUST be blocked by 16h same-author cooldown.
            ObjectNode sameAuthorSecondMat = store.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "1890105").put("authorId", "u-breaking")
                        .put("username", "breaking_tech").put("text", "另一则全新消息：某头部云厂商发布新一代推理芯片集群，延迟下降 45%。")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                        .put("publishedAt", now.minusSeconds(5).toEpochMilli());
                AssistantWorkflow.ingest(tx, m);
                return tx.get("material", "1890105");
            });
            int callsBeforeCooldown = judgeCalls.get();
            autoRepost.processCandidate(source, sameAuthorSecondMat);
            assertThat(judgeCalls.get()).isEqualTo(callsBeforeCooldown);
            assertThat(store.<ObjectNode>tx(tx -> tx.get("auto_repost", "1890105")).path("status").asText())
                    .isEqualTo("SKIPPED_AUTHOR_COOLDOWN");
            verify(publishClient, times(1)).createTweet(anyString(), anyString(), anyList(), anyString());

            // E. Dashboard exposes piAgent OAuth status and autoReposts feed.
            AssistantController controller = new AssistantController(store, collector, mock(AssistantWorker.class), mock(AssistantPublishing.class), mapper);
            ReflectionTestUtils.setField(controller, "piJudge", oauthJudge);
            when(collector.list()).thenReturn(List.of());
            when(collector.styleSamples()).thenReturn(mapper.createObjectNode());
            when(collector.history()).thenReturn(mapper.createObjectNode());
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, now));
            mvc.perform(get("/api/content-assistant/dashboard").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.piAgent.ready").value(true))
                    .andExpect(jsonPath("$.piAgent.authMode").value("CHATGPT_OAUTH_ONLY"))
                    .andExpect(jsonPath("$.autoReposts.length()").value(4));
        } finally {
            autoRepost.shutdown();
        }
    }

    @Test
    void recoverStaleRecordsConvergesSafelyAndRetryReusesCommentWhileBlockingUncertain() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant now = ZonedDateTime.of(2026, 11, 23, 22, 0, 0, 0, shanghai).toInstant();
        Clock clock = Clock.fixed(now, shanghai);

        AssistantStore store = new AssistantStore(tempDir.resolve("auto-repost-recovery"), mapper);
        SourceCollector collector = mock(SourceCollector.class);
        PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
        XOAuthService oauthService = mock(XOAuthService.class);
        XPublishClient publishClient = mock(XPublishClient.class);

        when(oauthService.getValidWriteAccessToken("owner-1", false)).thenReturn("write-token-retry");
        when(publishClient.createTweet("write-token-retry", "既有高质量锐评，免重跑判定直接补发。", List.of(), "1890203"))
                .thenReturn("1890888");

        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true));
            tx.put("auto_repost", "1890201", mapper.createObjectNode()
                    .put("materialId", "1890201").put("topicId", "t-eval").put("username", "u1")
                    .put("status", "EVALUATING"));
            tx.put("auto_repost", "1890202", mapper.createObjectNode()
                    .put("materialId", "1890202").put("topicId", "t-send").put("username", "u2")
                    .put("status", "SENDING"));
            tx.put("auto_repost", "1890203", mapper.createObjectNode()
                    .put("materialId", "1890203").put("topicId", "t-auth").put("username", "u3")
                    .put("status", "AUTH_REQUIRED")
                    .put("commentText", "既有高质量锐评，免重跑判定直接补发。")
                    .put("error", "缺少发推写权限"));
            return null;
        });

        AutoRepostService autoRepost = new AutoRepostService(store, collector, judge, oauthService, publishClient, clock);
        try {
            // (a) Restart recovery: EVALUATING -> JUDGE_FAILED, SENDING -> UNCERTAIN
            autoRepost.recoverStaleRecords();
            assertThat(store.<ObjectNode>tx(tx -> tx.get("auto_repost", "1890201")).path("status").asText())
                    .isEqualTo("JUDGE_FAILED");
            assertThat(store.<ObjectNode>tx(tx -> tx.get("auto_repost", "1890202")).path("status").asText())
                    .isEqualTo("UNCERTAIN");

            AssistantController controller = new AssistantController(store, collector, mock(AssistantWorker.class), mock(AssistantPublishing.class), mapper);
            ReflectionTestUtils.setField(controller, "autoRepostService", autoRepost);
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, now));

            // (b) UNCERTAIN must be rejected with 409 (Fail-Closed)
            mvc.perform(post("/api/content-assistant/auto-reposts/1890202/retry").session(session)
                            .header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isConflict());

            // (c) AUTH_REQUIRED with commentText retries via endpoint without re-invoking judge
            mvc.perform(post("/api/content-assistant/auto-reposts/1890203/retry").session(session)
                            .header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("EVALUATING"));

            verify(publishClient, timeout(3000).times(1))
                    .createTweet("write-token-retry", "既有高质量锐评，免重跑判定直接补发。", List.of(), "1890203");
            verifyNoInteractions(judge);

            long deadline = System.currentTimeMillis() + 3000;
            ObjectNode done = null;
            while (System.currentTimeMillis() < deadline) {
                done = store.tx(tx -> tx.get("auto_repost", "1890203"));
                if ("PUBLISHED".equals(done.path("status").asText())) break;
                Thread.sleep(20);
            }
            assertThat(done).isNotNull();
            assertThat(done.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(done.path("publishedTweetId").asText()).isEqualTo("1890888");
            assertThat(store.<ObjectNode>tx(tx -> tx.get("publication", "auto-repost-1890203"))).isNotNull();
        } finally {
            autoRepost.shutdown();
        }
    }

    @Test
    void repostPreviewAndAuthorTimelineEnforceViralPlusAccountFitDualGateAndCacheResults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant now = ZonedDateTime.of(2026, 11, 24, 23, 0, 0, 0, shanghai).toInstant();
        Clock clock = Clock.fixed(now, shanghai);

        // Verify PiAgentViralJudge strictly blocks high-heat tweets that do NOT fit the owner account
        Path fakeCodex = Files.createFile(tempDir.resolve("fake-codex-fit"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment fitEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        PiAgentViralJudge strictDualJudge = new PiAgentViralJudge(mapper, fitEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            int outIdx = cmd.indexOf("-o");
            String json = """
                    {"verdict":"VIRAL_REPOST","viralScore":94,"accountFit":false,"fitScore":35,"eventMagnitude":90,"publicResonance":92,"credibility":90,"category":"BREAKING_NEWS","summary":"跨界球赛花絮热议","reason":"虽有高热度，但属体育娱乐话题，不契合当前AI与工程定位","commentText":"场外花絮看看就好。"}
                    """;
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) Files.writeString(Path.of(cmd.get(outIdx + 1)), json);
            return new PiAgentViralJudge.CliResult(0, json, "");
        });
        PiAgentViralJudge.ViralJudgment offTopicHot = strictDualJudge.evaluate(new PiAgentViralJudge.EvaluationInput(
                "fchollet", "François Chollet", true, "What a crazy football match tonight!", "", "",
                5L, 200_000L, 9000L, 1200L, 800L, 82, "", "硬核AI工程拆解", "只发AI与独立产品干货", true));
        assertThat(offTopicHot.viralScore()).isEqualTo(94);
        assertThat(offTopicHot.accountFit()).isFalse();
        assertThat(offTopicHot.fitScore()).isEqualTo(35);
        assertThat(offTopicHot.shouldRepost()).isFalse();

        AssistantStore store = new AssistantStore(tempDir.resolve("repost-preview"), mapper);
        SourceCollector collector = mock(SourceCollector.class);
        PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
        XOAuthService oauthService = mock(XOAuthService.class);
        XPublishClient publishClient = mock(XPublishClient.class);

        when(judge.evaluate(any())).thenAnswer(inv -> {
            PiAgentViralJudge.EvaluationInput in = inv.getArgument(0);
            assertThat(in.factualEvidence()).isNotNull();
            assertThat(in.currentHotContext()).isNotNull();
            assertThat(in.attentionEvidence()).isNotNull();
            if (in.postText().contains("fluid intelligence benchmark")) {
                assertThat(in.factualEvidence().path("hasQuantitativeData").asBoolean()).isTrue();
                assertThat(in.factualEvidence().path("sourceLinkCount").asInt()).isGreaterThanOrEqualTo(1);
                assertThat(in.attentionEvidence().path("authorFollowersCount").asLong()).isEqualTo(1_200_000L);
                // Moderate-to-good potential (76 >= default 70, even though < old 82!) + strong account fit -> passes!
                return new PiAgentViralJudge.ViralJudgment(
                        true, 76, 84, 75, 72, true, 88, 78, 76, 85, "MAJOR_LAUNCH",
                        "ARC-AGI 新基准开源", "有扎实实验数据与开源链接，踩中推理评估热点且关注增速超基线",
                        "推理评估真正的分水岭不在刷榜，而在泛化样本零泄漏——这套新基准把底牌掀开了。"
                );
            }
            if (in.postText().contains("program synthesis milestone")) {
                return new PiAgentViralJudge.ViralJudgment(
                        true, 81, 86, 80, 78, true, 90, 82, 80, 88, "INDUSTRY_SHIFT",
                        "程序合成突破", "一手数据详实，契合AI工程定位",
                        "测试期搜索结合程序合成，正在把小参数模型的泛化上限往上推。"
                );
            }
            if (in.postText().contains("football match")) {
                return new PiAgentViralJudge.ViralJudgment(
                        false, 91, 60, 88, 92, false, 38, 85, 90, 88, "ROUTINE",
                        "球赛闲聊动态", "讨论度虽高但属体育闲聊，不契合当前账号受众",
                        "周末看球放松一下。"
                );
            }
            return new PiAgentViralJudge.ViralJudgment(
                    false, 58, 62, 52, 55, true, 84, 50, 55, 86, "ROUTINE",
                    "常规文档拼写修补", "主题契合但属小修小补，走热潜力不足",
                    "文档细节补齐，工程体验更顺手。"
            );
        });

        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
            tx.put("meta", "style", mapper.createObjectNode().put("profile", "结论先行，冷峻直白，拒绝套话"));
            tx.put("source", "68746721", mapper.createObjectNode()
                    .put("id", "68746721")
                    .put("authorId", "68746721")
                    .put("username", "fchollet")
                    .put("displayName", "François Chollet")
                    .put("avatarUrl", "https://pbs.twimg.com/profile_images/fc.jpg")
                    .put("verified", true)
                    .put("followersCount", 1_200_000L)
                    .put("status", "ACTIVE")
                    .put("autoRepostEnabled", true)
                    .put("autoRepostTextMode", "AI")
                    .put("autoRepostMinScore", 82)
                    .put("autoRepostRuleHint", "重点关注推理基准与架构"));
            AssistantWorkflow.ingest(tx, mapper.createObjectNode()
                    .put("id", "212494203584")
                    .put("authorId", "68746721")
                    .put("username", "fchollet")
                    .put("authorName", "François Chollet")
                    .put("text", "We are open-sourcing the next generation fluid intelligence benchmark (+32% generalization): https://arcprize.org/v2")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", now.minusSeconds(120).toEpochMilli())
                    .put("views", 82000L)
                    .put("likes", 1900L));
            AssistantWorkflow.ingest(tx, mapper.createObjectNode()
                    .put("id", "212494203550")
                    .put("authorId", "68746721")
                    .put("username", "fchollet")
                    .put("authorName", "François Chollet")
                    .put("text", "Earlier today: new program synthesis milestone cutting search cost by 4x.")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", now.minusSeconds(3600 * 3).toEpochMilli())
                    .put("views", 64000L)
                    .put("likes", 1600L));
            AssistantWorkflow.ingest(tx, mapper.createObjectNode()
                    .put("id", "212494203500")
                    .put("authorId", "68746721")
                    .put("username", "fchollet")
                    .put("authorName", "François Chollet")
                    .put("text", "What a crazy football match tonight, unbelievable final minute!")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", now.minusSeconds(600).toEpochMilli())
                    .put("views", 150000L)
                    .put("likes", 6200L));
            AssistantWorkflow.ingest(tx, mapper.createObjectNode()
                    .put("id", "212494203400")
                    .put("authorId", "68746721")
                    .put("username", "fchollet")
                    .put("authorName", "François Chollet")
                    .put("text", "Fixed a minor typo in README installation section.")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", now.minusSeconds(1200).toEpochMilli())
                    .put("views", 3200L)
                    .put("likes", 45L));
            return null;
        });

        AutoRepostService autoRepost = new AutoRepostService(store, collector, judge, oauthService, publishClient, clock);
        try {
            AssistantController controller = new AssistantController(store, collector, mock(AssistantWorker.class), mock(AssistantPublishing.class), mapper);
            ReflectionTestUtils.setField(controller, "autoRepostService", autoRepost);
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
            var session = new MockHttpSession();
            session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, now));

            // 0. Opening author timeline returns all 4 tweets sorted newest-first, migrating untouched legacy minScore 82 -> 70
            mvc.perform(post("/api/content-assistant/sources/68746721/timeline").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source.username").value("fchollet"))
                    .andExpect(jsonPath("$.source.minScore").value(70))
                    .andExpect(jsonPath("$.source.sameAuthorCooldownHours").value(16))
                    .andExpect(jsonPath("$.items.length()").value(4))
                    .andExpect(jsonPath("$.summary.pendingCount").value(4));

            // 1. Tweet with solid viral potential (76 >= 70) + account fit (88) passes both gates & exposes 3-pillar scores
            mvc.perform(post("/api/content-assistant/sources/68746721/repost-preview").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"materialId\":\"212494203584\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.preview.wouldAutoRepost").value(true))
                    .andExpect(jsonPath("$.preview.viralScore").value(76))
                    .andExpect(jsonPath("$.preview.factScore").value(84))
                    .andExpect(jsonPath("$.preview.trendScore").value(75))
                    .andExpect(jsonPath("$.preview.attentionScore").value(72))
                    .andExpect(jsonPath("$.preview.fitScore").value(88))
                    .andExpect(jsonPath("$.preview.decisionCode").value("WOULD_REPOST"));

            // 1b. Earlier tweet (3 hours earlier, within 16h!) ALSO passes content check individually
            mvc.perform(post("/api/content-assistant/sources/68746721/repost-preview").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"materialId\":\"212494203550\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.preview.contentPass").value(true));

            // 2. Hot + Off-Topic tweet fails accountFit -> SKIP_NOT_FIT
            mvc.perform(post("/api/content-assistant/sources/68746721/repost-preview").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"materialId\":\"212494203500\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.preview.wouldAutoRepost").value(false))
                    .andExpect(jsonPath("$.preview.decisionCode").value("SKIP_NOT_FIT"));

            // 3. Account-Fit + Low-Heat tweet fails viralPass -> SKIP_LOW_HEAT
            mvc.perform(post("/api/content-assistant/sources/68746721/repost-preview").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{\"materialId\":\"212494203400\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.preview.wouldAutoRepost").value(false))
                    .andExpect(jsonPath("$.preview.decisionCode").value("SKIP_LOW_HEAT"));

            // 4. Author timeline enforces 16h non-consecutive rule chronologically:
            //    Earlier qualifying post (212494203550, -3h) -> WOULD_REPOST,
            //    Newer qualifying post within 16h (212494203584, -2m) -> SKIP_AUTHOR_COOLDOWN!
            mvc.perform(post("/api/content-assistant/sources/fchollet/timeline").session(session)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.evaluatedCount").value(4))
                    .andExpect(jsonPath("$.summary.wouldRepostCount").value(1))
                    .andExpect(jsonPath("$.summary.skippedCount").value(3))
                    .andExpect(jsonPath("$.items[0].material.id").value("212494203584"))
                    .andExpect(jsonPath("$.items[0].decision.decisionCode").value("SKIP_AUTHOR_COOLDOWN"))
                    .andExpect(jsonPath("$.items[0].decision.authorCooldownBlocked").value(true))
                    .andExpect(jsonPath("$.items[3].material.id").value("212494203550"))
                    .andExpect(jsonPath("$.items[3].decision.decisionCode").value("WOULD_REPOST"));
            verify(judge, times(4)).evaluate(any());
        } finally {
            autoRepost.shutdown();
        }
    }

    @Test
    void quoteVoiceDistillerMountsPiSkillsStripsAiSlopAndCapsAtFiveSentencesWithEmojis() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String wordyAiDraft = """
                值得注意的是，🎉最让我惊喜的是: 这段动画现在直接由云端 Blender 渲染出来了 !!

                效果很哇塞😺。第三句补充说明帧率提升到 60 帧。

                第四句讲镜头跟随也顺手调好了!😽 第五句说源码稍后发仓库! 第六句是多余凑字数的尾巴。第七句也是多余废话。
                """;
        String formatted = OwnerQuoteStyleDistiller.enforceConciseQuoteFormat(wordyAiDraft, 200);
        assertThat(formatted).doesNotContain("值得注意的是");
        assertThat(formatted).contains("🎉最让我惊喜的是:").contains("😺").contains("😽");
        assertThat(formatted).doesNotContain("第六句").doesNotContain("第七句");
        assertThat(OwnerQuoteStyleDistiller.countSentences(formatted))
                .isLessThanOrEqualTo(OwnerQuoteStyleDistiller.MAX_QUOTE_SENTENCES);
        assertThat(OwnerQuoteStyleDistiller.SIGNATURE_QUOTE_EXAMPLES).hasSize(4);
        for (int i = 0; i < OwnerQuoteStyleDistiller.SIGNATURE_QUOTE_EXAMPLES.size(); i++) {
            String canonicalExample = OwnerQuoteStyleDistiller.SIGNATURE_QUOTE_EXAMPLES.get(i);
            boolean brief = i < 2;
            String kept = OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(canonicalExample, brief, 280);
            assertThat(kept).doesNotContain("？").doesNotContain("?");
            if (brief) {
                assertThat(kept).doesNotContain("💡 省流重点：");
                assertThat(kept.split("\\n\\n+")).hasSize(2);
            } else {
                assertThat(kept).contains("💡 省流重点：");
                assertThat(kept.split("\\n\\n+")).hasSize(3);
            }
        }
        String fourParaWithQuestion = """
                🎉 主力编码模型更新啦!!😺

                💡 省流重点：长上下文缓存降价 75%。

                平时拿 Agent 啃大项目最怕烧额度，这波真是刚需～😽

                你们准备先拿哪个项目试水？😸
                """;
        String strippedQuestion = OwnerQuoteStyleDistiller.enforceConciseQuoteFormat(fourParaWithQuestion, 280);
        assertThat(strippedQuestion.split("\\n\\n+")).hasSize(3);
        assertThat(strippedQuestion).contains("这波真是刚需～😽").doesNotContain("试水").doesNotContain("？");

        Path fakePi = Files.createFile(tempDir.resolve("fake-pi"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment piEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, fakePi.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "pi");
        List<List<String>> capturedPiCommands = new java.util.concurrent.CopyOnWriteArrayList<>();
        PiAgentViralJudge piJudge = new PiAgentViralJudge(mapper, piEnv, (cmd, stdin, timeout) -> {
            capturedPiCommands.add(cmd);
            if (cmd.contains("auth") && cmd.contains("check")) {
                return new PiAgentViralJudge.CliResult(0, "{\"status\":\"ready\"}", "");
            }
            return new PiAgentViralJudge.CliResult(0, """
                    {"verdict":"VIRAL_REPOST","viralScore":83,"factScore":86,"trendScore":80,"attentionScore":81,"accountFit":true,"fitScore":90,"eventMagnitude":82,"publicResonance":80,"credibility":88,"category":"MAJOR_LAUNCH","summary":"云端完整环境渲染发布","reason":"一手实测帧率与渲染数据扎实，契合AI工具折腾偏好","commentText":"🎉最让我惊喜的是: 现在直接在对话里就能调完整云电脑跑渲染了 !!\\n\\n实测出片效果很哇塞😺"}
                    """, "");
        });
        ObjectNode auth = piJudge.checkAuth(true);
        assertThat(auth.path("ready").asBoolean()).isTrue();
        assertThat(auth.path("activeSkills").toString()).contains("humanizer-zh", "quote-voice-distiller");

        PiAgentViralJudge.ViralJudgment res = piJudge.evaluate(new PiAgentViralJudge.EvaluationInput(
                "openai", "OpenAI", true, "Full cloud Blender environment now inside chat.", "", "",
                8L, 90_000L, 3200L, 450L, 210L, 70, "", "", "", true));
        assertThat(res.shouldRepost()).isTrue();
        assertThat(res.commentText()).contains("🎉").contains("😺");
        assertThat(OwnerQuoteStyleDistiller.countSentences(res.commentText())).isLessThanOrEqualTo(5);

        List<String> evalCmd = capturedPiCommands.get(capturedPiCommands.size() - 1);
        assertThat(evalCmd).contains("--skill", "--append-system-prompt", "--thinking", "medium");
        assertThat(String.join(" ", evalCmd)).contains("humanizer-zh").contains("quote-voice-distiller");

        // Verify Codex 2-step split: Gate (gpt-6.2-astra low + schema) -> Pure Quote Writer (gpt-6.2-astra medium + plain text, N1n3 cat style)
        Path fakeCodex = Files.createFile(tempDir.resolve("fake-codex-split"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment codexEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        List<List<String>> codexCmds = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> codexPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        PiAgentViralJudge splitCodexJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            codexCmds.add(cmd);
            codexPrompts.add(stdin);
            int outIdx = cmd.indexOf("-o");
            String body;
            if (cmd.contains("--output-schema")) {
                body = """
                        {"verdict":"VIRAL_REPOST","viralScore":86,"factScore":88,"trendScore":84,"attentionScore":85,"accountFit":true,"fitScore":92,"eventMagnitude":85,"publicResonance":84,"credibility":90,"category":"MAJOR_LAUNCH","summary":"云端沙箱直连 Blender","reason":"一手实测数据完整，契合独立开发者工作流"}
                        """;
            } else {
                body = """
                        重点并不在渲染速度；

                        重点是：现在直接在对话里就能调完整云电脑跑 Blender 啦，这不神奇吗～😽
                        """;
            }
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                Files.writeString(Path.of(cmd.get(outIdx + 1)), body);
            }
            return new PiAgentViralJudge.CliResult(0, body, "");
        });

        PiAgentViralJudge.ViralJudgment codexRes = splitCodexJudge.evaluate(new PiAgentViralJudge.EvaluationInput(
                "N1n3VASthR33H", "d!l3+ttant3", true, "Rendered this animation via cloud Blender in chat!", "", "",
                12L, 17_200L, 620L, 95L, 48L, 70, "", "", "", true));
        assertThat(codexRes.shouldRepost()).isTrue();
        assertThat(codexRes.commentText()).contains("这不神奇吗～😽");
        assertThat(codexCmds).hasSize(2);
        assertThat(codexCmds.get(0)).contains("-m", "gpt-6.2-astra", "model_reasoning_effort=\"low\"", "--output-schema");
        assertThat(codexCmds.get(1)).contains("-m", "gpt-6.2-astra", "model_reasoning_effort=\"medium\"");
        assertThat(codexCmds.get(1)).doesNotContain("--output-schema");
        assertThat(codexPrompts.get(1)).contains("😺").contains("😽").contains("原帖就在你正下方");

        // Verify coexisting Owner Last Tweet [1h + N(1..20m)] gate across publication / scheduled / auto-repost sources
        assertThat(AutoRepostService.OWNER_TWEET_MIN_JITTER_MINUTES).isEqualTo(1);
        assertThat(AutoRepostService.OWNER_TWEET_MAX_JITTER_MINUTES).isEqualTo(20);
        for (long sampleTs : List.of(1_732_450_000_000L, 1_732_453_711_000L, 1_732_460_999_000L)) {
            int jitter = AutoRepostService.resolveOwnerTweetJitterMinutes(sampleTs);
            assertThat(jitter).isBetween(
                    AutoRepostService.OWNER_TWEET_MIN_JITTER_MINUTES,
                    AutoRepostService.OWNER_TWEET_MAX_JITTER_MINUTES);
            assertThat(AutoRepostService.resolveOwnerTweetRequiredGapMs(sampleTs))
                    .isEqualTo((60L + jitter) * 60_000L);
        }

        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant tNow = ZonedDateTime.of(2026, 11, 25, 1, 20, 0, 0, shanghai).toInstant();
        Clock fixedClock = Clock.fixed(tNow, shanghai);
        AssistantStore gapStore = new AssistantStore(tempDir.resolve("owner-gap-coexist"), mapper);
        SourceCollector gapCollector = mock(SourceCollector.class);
        XOAuthService gapOauth = mock(XOAuthService.class);
        XPublishClient gapPublish = mock(XPublishClient.class);
        when(gapOauth.getValidWriteAccessToken("owner-1", false)).thenReturn("gap-write-token");
        when(gapPublish.createTweet(eq("gap-write-token"), anyString(), eq(List.of()), eq("2990102")))
                .thenReturn("2990999");

        // Owner posted a regular tweet 35 minutes ago (< 1h + N[1..10m])
        long ownerLastTweetAt = tNow.minusSeconds(35 * 60L).toEpochMilli();
        int expectedJitter = AutoRepostService.resolveOwnerTweetJitterMinutes(ownerLastTweetAt);
        ObjectNode secondAuthorSource = gapStore.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true));
            tx.put("publication", "direct-own-tweet-1", mapper.createObjectNode()
                    .put("id", "direct-own-tweet-1")
                    .put("status", "PUBLISHED")
                    .put("publishedTweetId", "2990001")
                    .put("publishedAt", ownerLastTweetAt));
            return tx.put("source", "u-second", mapper.createObjectNode()
                    .put("id", "u-second").put("authorId", "u-second")
                    .put("username", "karpathy").put("displayName", "Andrej Karpathy")
                    .put("status", "ACTIVE").put("autoRepostEnabled", true)
                    .put("autoRepostEnabledAt", tNow.minusSeconds(3600).toEpochMilli())
                    .put("autoRepostTextMode", "AI").put("autoRepostMinScore", 70));
        });

        AutoRepostService gapServiceWithinWindow = new AutoRepostService(
                gapStore, gapCollector, splitCodexJudge, gapOauth, gapPublish, fixedClock);
        try {
            ObjectNode blockedMat = gapStore.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "2990101").put("authorId", "u-second")
                        .put("username", "karpathy")
                        .put("text", "New open-source LLM training kernel hits 92% H100 MFU: https://github.com/karpathy/llm.c")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                        .put("publishedAt", tNow.minusSeconds(60).toEpochMilli());
                AssistantWorkflow.ingest(tx, m);
                return tx.get("material", "2990101");
            });
            int cmdsBeforeBlocked = codexCmds.size();
            gapServiceWithinWindow.processCandidate(secondAuthorSource, blockedMat);
            assertThat(codexCmds).hasSize(cmdsBeforeBlocked);
            ObjectNode skippedByOwnerGap = gapStore.tx(tx -> tx.get("auto_repost", "2990101"));
            assertThat(skippedByOwnerGap.path("status").asText()).isEqualTo("SKIPPED_COOLDOWN");
            assertThat(skippedByOwnerGap.path("deferredPendingEval").asBoolean()).isTrue();
            assertThat(skippedByOwnerGap.path("reason").asText()).contains("1小时+" + expectedJitter + "分钟").contains("待 CD 解除后再做判断");

            // /evaluate-once also short-circuits with 0 LLM calls while Global CD is active
            ObjectNode evalOnceBlocked = gapServiceWithinWindow.evaluateAuthorOnce("karpathy", mapper.createObjectNode());
            assertThat(evalOnceBlocked.path("cdShortCircuited").asBoolean()).isTrue();
            assertThat(evalOnceBlocked.path("cooldown").path("globalCd").path("active").asBoolean()).isTrue();
            assertThat(evalOnceBlocked.path("summary").path("wouldRepostCount").asInt()).isZero();
            assertThat(codexCmds).hasSize(cmdsBeforeBlocked);

            // Manual single-item preview/draft during Global CD evaluates & generates quote while preserving CD block
            ObjectNode timelineBeforeManual = gapServiceWithinWindow.authorTimeline("karpathy", mapper.createObjectNode());
            assertThat(timelineBeforeManual.path("items").get(0).path("decision").path("evaluated").asBoolean()).isFalse();
            assertThat(timelineBeforeManual.path("items").get(0).path("decision").path("cdShortCircuited").asBoolean()).isTrue();

            ObjectNode manualPreviewDuringCd = gapServiceWithinWindow.previewRepost(
                    "karpathy", mapper.createObjectNode().put("materialId", "2990101").put("forceWriteComment", true));
            assertThat(manualPreviewDuringCd.path("preview").path("evaluated").asBoolean()).isTrue();
            assertThat(manualPreviewDuringCd.path("preview").path("wouldAutoRepost").asBoolean()).isFalse();
            assertThat(manualPreviewDuringCd.path("preview").path("decisionCode").asText()).isEqualTo("SKIP_COOLDOWN");
            assertThat(manualPreviewDuringCd.path("preview").path("commentText").asText()).isNotBlank();

            ObjectNode timelineAfterManual = gapServiceWithinWindow.authorTimeline("karpathy", mapper.createObjectNode());
            var manualRowDec = timelineAfterManual.path("items").get(0).path("decision");
            assertThat(manualRowDec.path("evaluated").asBoolean()).isTrue();
            assertThat(manualRowDec.path("cdShortCircuited").asBoolean()).isFalse();
            assertThat(manualRowDec.path("wouldAutoRepost").asBoolean()).isFalse();
            assertThat(manualRowDec.path("commentText").asText()).isNotBlank();
            assertThat(manualRowDec.path("reason").asText()).contains("1小时+");
        } finally {
            gapServiceWithinWindow.shutdown();
        }

        // Advance clock past Global CD [1h + N(1..10m)] (+71m):
        // Add a 2nd tweet for @karpathy, then trigger recheckDeferredCooldownCandidates() ->
        // BOTH deferred 2990101 and new 2990102 are evaluated in ONE single batch gate call for @karpathy, picking ONLY 2990102!
        AtomicInteger batchGateCalls = new AtomicInteger();
        PiAgentViralJudge batchCodexJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            int outIdx = cmd.indexOf("-o");
            String body;
            if (cmd.contains("--output-schema")) {
                batchGateCalls.incrementAndGet();
                body = """
                        {
                          "selectedMaterialId": "2990102",
                          "items": [
                            {"materialId":"2990102","verdict":"VIRAL_REPOST","viralScore":89,"factScore":90,"trendScore":88,"attentionScore":88,"accountFit":true,"fitScore":93,"eventMagnitude":88,"publicResonance":86,"credibility":92,"category":"MAJOR_LAUNCH","summary":"4-bit融合注意力省显存58%","reason":"一手量化基准极扎实且最契合工程受众"},
                            {"materialId":"2990101","verdict":"SKIP","viralScore":78,"factScore":82,"trendScore":76,"attentionScore":75,"accountFit":true,"fitScore":86,"eventMagnitude":76,"publicResonance":74,"credibility":88,"category":"MAJOR_LAUNCH","summary":"开源训练内核进展","reason":"同账号本轮已有更优重磅推文"}
                          ]
                        }
                        """;
            } else {
                body = "显存直接砍半还多，128k 长上下文这下终于不用省着塞啦😽";
            }
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                Files.writeString(Path.of(cmd.get(outIdx + 1)), body);
            }
            return new PiAgentViralJudge.CliResult(0, body, "");
        });

        Clock afterGapClock = Clock.fixed(Instant.ofEpochMilli(ownerLastTweetAt + 81L * 60_000L), shanghai);
        AutoRepostService gapServiceAfterWindow = new AutoRepostService(
                gapStore, gapCollector, batchCodexJudge, gapOauth, gapPublish, afterGapClock);
        try {
            gapStore.tx(tx -> {
                ObjectNode m = mapper.createObjectNode().put("id", "2990102").put("authorId", "u-second")
                        .put("username", "karpathy")
                        .put("text", "Follow-up benchmark: 4-bit fused attention cuts memory by 58% across 128k context.")
                        .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                        .put("publishedAt", afterGapClock.millis() - 30_000L);
                AssistantWorkflow.ingest(tx, m);
                return null;
            });
            gapServiceAfterWindow.recheckDeferredCooldownCandidates();
            assertThat(batchGateCalls.get()).isEqualTo(1);
            ObjectNode publishedAfterGap = gapStore.tx(tx -> tx.get("auto_repost", "2990102"));
            assertThat(publishedAfterGap.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(publishedAfterGap.path("publishedTweetId").asText()).isEqualTo("2990999");
            ObjectNode siblingSkipped = gapStore.tx(tx -> tx.get("auto_repost", "2990101"));
            assertThat(siblingSkipped.path("status").asText()).isEqualTo("SKIPPED");
            assertThat(siblingSkipped.path("deferredPendingEval").asBoolean()).isFalse();

            // Immediately after 2990102 published, BOTH Global CD and @karpathy's 16h Author CD are now active -> 0 LLM calls!
            ObjectNode timelineAfterPublish = gapServiceAfterWindow.evaluateAuthorOnce("karpathy", mapper.createObjectNode());
            assertThat(timelineAfterPublish.path("cdShortCircuited").asBoolean()).isTrue();
            assertThat(timelineAfterPublish.path("cooldown").path("globalCd").path("active").asBoolean()).isTrue();
            assertThat(timelineAfterPublish.path("cooldown").path("authorCd").path("active").asBoolean()).isTrue();
            assertThat(batchGateCalls.get()).isEqualTo(1);

            // Manual batch evaluation click (forceRegenerate=true) during Global CD runs batch judge for debugging
            ObjectNode forcedBatchDuringCd = gapServiceAfterWindow.evaluateAuthorOnce(
                    "karpathy", mapper.createObjectNode().put("forceRegenerate", true));
            assertThat(forcedBatchDuringCd.path("evaluatedAccountOnce").asBoolean()).isTrue();
            assertThat(batchGateCalls.get()).isEqualTo(2);

            // Verify configurable 2nd-pass polish prompt: Pass 1 stiff draft -> Pass 2 rewritten by user's custom prompt + /polish-prompt API
            List<String> twoPassInputs = new java.util.concurrent.CopyOnWriteArrayList<>();
            PiAgentViralJudge twoPassJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
                if (cmd.contains("login") && cmd.contains("status")) {
                    return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
                }
                twoPassInputs.add(stdin);
                int outIdx = cmd.indexOf("-o");
                String outBody;
                if (cmd.contains("--output-schema")) {
                    outBody = """
                            {"verdict":"VIRAL_REPOST","viralScore":84,"factScore":86,"trendScore":82,"attentionScore":83,"accountFit":true,"fitScore":90,"eventMagnitude":82,"publicResonance":81,"credibility":89,"category":"INDUSTRY_SHIFT","summary":"AI自动找漏洞与上游合并","reason":"工程痛点真实，契合独立开发者视角"}
                            """;
                } else if (stdin.contains("【我的二次改写提示词")) {
                    outBody = """
                            抓 bug 谁都会啦，关键是改出来的 PR 开源维护者敢不敢合进去呀😹
                            """;
                } else {
                    outBody = """
                            💡我更想听维护者怎么审这些补丁。

                            能把模型找到的问题变成上游愿意合的修复，这一步才见功夫😺
                            """;
                }
                if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                    Files.writeString(Path.of(cmd.get(outIdx + 1)), outBody);
                }
                return new PiAgentViralJudge.CliResult(0, outBody, "");
            });

            AssistantController polishController = new AssistantController(
                    gapStore, gapCollector, mock(AssistantWorker.class), mock(AssistantPublishing.class), mapper);
            ReflectionTestUtils.setField(polishController, "autoRepostService", gapServiceAfterWindow);
            var polishMvc = MockMvcBuilders.standaloneSetup(polishController).setControllerAdvice(new ApiExceptionHandler()).build();
            var ownerSession = new MockHttpSession();
            ownerSession.setAttribute(XOAuthController.SESSION_AUTH_USER,
                    new AuthenticatedXUser("owner-1", "elidlicom", "elidlicom", "", false, tNow));

            String myCustomPrompt = "去掉句首💡和「这一步才见功夫」这种老气评委腔，改成程序员一句大白话反问+😹";
            polishMvc.perform(post("/api/content-assistant/polish-prompt").session(ownerSession)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .contentType("application/json")
                            .content(mapper.createObjectNode()
                                    .put("postPolishEnabled", true)
                                    .put("postPolishPrompt", myCustomPrompt)
                                    .toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.postPolishEnabled").value(true))
                    .andExpect(jsonPath("$.postPolishPrompt").value(myCustomPrompt));

            PiAgentViralJudge.ViralJudgment polishedRes = twoPassJudge.evaluate(new PiAgentViralJudge.EvaluationInput(
                    "thsottiaux", "Thibault", true,
                    "Models are finding hundreds of real bugs in open-source repos, now working with maintainers on upstream patches.",
                    "", "", 10L, 45_000L, 1200L, 180L, 90L, 70, "", "", "", true,
                    null, null, null, true, myCustomPrompt));
            assertThat(twoPassInputs).hasSize(3);
            assertThat(twoPassInputs.get(2))
                    .contains("💡我更想听维护者怎么审这些补丁")
                    .contains("这一步才见功夫")
                    .contains(myCustomPrompt);
            assertThat(polishedRes.commentText())
                    .doesNotContain("这一步才见功夫")
                    .contains("敢不敢合进去呀😹");

            // Verify Direct-Pass Categories (MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET):
            // After Global CD lifts (+75m after 2990102) while @karpathy's 16h Author CD is STILL active:
            // 1) Non-exempt INDUSTRY_SHIFT (even viralScore=88) is blocked by 16h Author CD (0 writer calls);
            // 2) QUOTA_RESET (even low viralScore=52 < minScore 70) passes via direct-pass AND exempts 16h Author CD -> PUBLISHED!
            // 3) Immediately after QUOTA_RESET publishes, Global 1h+N min CD blocks consecutive FEATURE_UPDATE with 0 LLM calls!
            when(gapPublish.createTweet(eq("gap-write-token"), anyString(), eq(List.of()), eq("2990104")))
                    .thenReturn("2991004");
            AtomicInteger directGateCalls = new AtomicInteger();
            AtomicInteger directWriterCalls = new AtomicInteger();
            PiAgentViralJudge directPassJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
                if (cmd.contains("login") && cmd.contains("status")) {
                    return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
                }
                int outIdx = cmd.indexOf("-o");
                String outBody;
                if (cmd.contains("--output-schema")) {
                    directGateCalls.incrementAndGet();
                    if (stdin.contains("GPT-6") && stdin.contains("market share")) {
                        outBody = """
                                {"verdict":"VIRAL_REPOST","viralScore":88,"factScore":85,"trendScore":84,"attentionScore":86,"accountFit":true,"fitScore":86,"eventMagnitude":84,"publicResonance":85,"credibility":88,"category":"INDUSTRY_SHIFT","summary":"行业市场份额讨论","reason":"热度达标但属常规行业动态而非模型/功能/额度通知"}
                                """;
                    } else {
                        outBody = """
                                {"verdict":"SKIP","viralScore":52,"factScore":82,"trendScore":58,"attentionScore":24,"accountFit":true,"fitScore":94,"eventMagnitude":78,"publicResonance":55,"credibility":95,"category":"QUOTA_RESET","summary":"Pro与Plus调用额度全量重置","reason":"官方刚发浏览尚低，属额度重置通知"}
                                """;
                    }
                } else {
                    directWriterCalls.incrementAndGet();
                    outBody = """
                            🎉 官方终于把这周的调用额度提前重置清零啦!!😺

                            💡 省流重点：Plus 和 Pro 订阅账号的高阶模型额度已经全部恢复满格。

                            前两天跑仓库刚把额度刷见底，这波回血来得太及时啦～😽
                            """;
                }
                if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                    Files.writeString(Path.of(cmd.get(outIdx + 1)), outBody);
                }
                return new PiAgentViralJudge.CliResult(0, outBody, "");
            });

            Clock authorCdOnlyClock = Clock.fixed(Instant.ofEpochMilli(afterGapClock.millis() + 85L * 60_000L), shanghai);
            AutoRepostService authorCdOnlySvc = new AutoRepostService(
                    gapStore, gapCollector, directPassJudge, gapOauth, gapPublish, authorCdOnlyClock);
            try {
                ObjectNode routineMat = gapStore.tx(tx -> {
                    ObjectNode m = mapper.createObjectNode().put("id", "2990103").put("authorId", "u-second")
                            .put("username", "karpathy")
                            .put("text", "Analysts debate GPT-6 enterprise market share across Fortune 500 buyers this quarter.")
                            .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                            .put("publishedAt", authorCdOnlyClock.millis() - 40_000L);
                    AssistantWorkflow.ingest(tx, m);
                    return tx.get("material", "2990103");
                });
                authorCdOnlySvc.processCandidate(secondAuthorSource, routineMat);
                assertThat(directGateCalls.get()).isEqualTo(1);
                assertThat(directWriterCalls.get()).isZero();
                assertThat(gapStore.tx(tx -> tx.get("auto_repost", "2990103")).path("status").asText())
                        .isEqualTo("SKIPPED_AUTHOR_COOLDOWN");

                ObjectNode quotaMat = gapStore.tx(tx -> {
                    ObjectNode m = mapper.createObjectNode().put("id", "2990104").put("authorId", "u-second")
                            .put("username", "karpathy")
                            .put("text", "Heads up: weekly usage quotas have just been reset for all Plus and Pro accounts!")
                            .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                            .put("publishedAt", authorCdOnlyClock.millis() - 20_000L);
                    AssistantWorkflow.ingest(tx, m);
                    return tx.get("material", "2990104");
                });
                authorCdOnlySvc.processCandidate(secondAuthorSource, quotaMat);
                assertThat(directGateCalls.get()).isEqualTo(2);
                assertThat(directWriterCalls.get()).isGreaterThanOrEqualTo(1);
                ObjectNode quotaPublished = gapStore.tx(tx -> tx.get("auto_repost", "2990104"));
                assertThat(quotaPublished.path("status").asText()).isEqualTo("PUBLISHED");
                assertThat(quotaPublished.path("category").asText()).isEqualTo("QUOTA_RESET");
                assertThat(quotaPublished.path("publishedTweetId").asText()).isEqualTo("2991004");

                // 2 minutes later: Global 1h+N min CD is active again -> even FEATURE_UPDATE is deferred with 0 LLM calls!
                int gatesAfterQuota = directGateCalls.get();
                ObjectNode consecutiveFeatureMat = gapStore.tx(tx -> {
                    ObjectNode m = mapper.createObjectNode().put("id", "2990105").put("authorId", "u-second")
                            .put("username", "karpathy")
                            .put("text", "Also shipping a quick CLI feature update with background git worktree sync.")
                            .put("textStatus", "TEXT_ONLY").put("kind", "POST")
                            .put("publishedAt", authorCdOnlyClock.millis() - 5_000L);
                    AssistantWorkflow.ingest(tx, m);
                    return tx.get("material", "2990105");
                });
                authorCdOnlySvc.processCandidate(secondAuthorSource, consecutiveFeatureMat);
                assertThat(directGateCalls.get()).isEqualTo(gatesAfterQuota);
                ObjectNode deferredFeature = gapStore.tx(tx -> tx.get("auto_repost", "2990105"));
                assertThat(deferredFeature.path("status").asText()).isEqualTo("SKIPPED_COOLDOWN");
                assertThat(deferredFeature.path("deferredPendingEval").asBoolean()).isTrue();

                // Verify manual quote publish endpoint natively attaches quote_tweet_id
                when(gapPublish.createTweet(eq("gap-write-token"), anyString(), eq(List.of()), eq("3110001")))
                        .thenReturn("3110999");
                polishMvc.perform(post("/api/content-assistant/sources/karpathy/publish-quote").session(ownerSession)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .contentType("application/json")
                                .content(mapper.createObjectNode()
                                        .put("materialId", "3110001")
                                        .put("sourceUrl", "https://x.com/karpathy/status/3110001")
                                        .put("commentText", "🎉 新模型发布啦!!😺\n\n💡 省流重点：速度翻倍。\n\n真香～😽")
                                        .put("clientRequestId", "manual-quote-req-1")
                                        .toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PUBLISHED"))
                        .andExpect(jsonPath("$.quoteTweetId").value("3110001"))
                        .andExpect(jsonPath("$.publishedTweetId").value("3110999"));
                verify(gapPublish, times(1)).createTweet(eq("gap-write-token"), anyString(), eq(List.of()), eq("3110001"));
                ObjectNode manualPublished = gapStore.tx(tx -> tx.get("auto_repost", "3110001"));
                assertThat(manualPublished.path("status").asText()).isEqualTo("PUBLISHED");
                assertThat(manualPublished.path("quoteTweetId").asText()).isEqualTo("3110001");
            } finally {
                authorCdOnlySvc.shutdown();
            }
        } finally {
            gapServiceAfterWindow.shutdown();
        }
    }

    @Test
    void adaptiveShortQuoteSkipsTlDrInjectsAuthorBioHotContextAndOwnerHistoricalStances() throws Exception {
        assertThat(OwnerQuoteStyleDistiller.isBriefFlashPost("Banked reset is online", "", "")).isTrue();
        assertThat(OwnerQuoteStyleDistiller.isBriefFlashPost(
                "We're releasing a major model upgrade.\nPoint 1: 75% cache discount.\nPoint 2: Better repo editing.\nPoint 3: Lower latency.",
                "", "")).isFalse();
        assertThat(OwnerQuoteStyleDistiller.isBriefFlashPost("Short intro", "",
                "Long article body describing benchmark improvements across 120 different coding tasks.")).isFalse();

        String briefWithRedundantTldr = """
                🎉 Tibo 说，Banked reset 已经上线。

                💡 省流重点：这项重置功能现在可以用了，但细节原帖没说。

                看来OpenAI意识到危机了，但是重置额度后，面对的还是降智AI……我都懒得用重置卡了
                """;
        String strippedBrief = OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(briefWithRedundantTldr, true, 280);
        assertThat(strippedBrief).doesNotContain("💡 省流重点：");
        assertThat(strippedBrief.split("\\n\\n+")).hasSize(2);

        String normalBulbLine = OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(
                "💡我更想听维护者怎么审这些补丁。\n\n这波挺稳", true, 280);
        assertThat(normalBulbLine).contains("💡我更想听维护者怎么审这些补丁。");

        ObjectMapper mapper = new ObjectMapper();
        assertThat(AssistantWorkflow.resolvePostPolishPrompt(mapper.createObjectNode()
                .put("postPolishPrompt", OwnerQuoteStyleDistiller.LEGACY_V3_POLISH_PROMPT_PREFIX + "：旧版三段式")))
                .isEqualTo(OwnerQuoteStyleDistiller.DEFAULT_POST_POLISH_PROMPT);

        AssistantStore store = new AssistantStore(tempDir.resolve("smart-quote-store"), mapper);
        Instant nowInstant = Instant.now();
        Clock clock = Clock.fixed(nowInstant, ZoneId.of("Asia/Shanghai"));
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(community.longHistoryCandidates()).thenReturn(List.of());
        SourceCollector collector = new SourceCollector(store, provider, community, mapper, clock);

        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-smart").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                    .put("enabled", true)
                    .put("collectionEnabled", true)
                    .put("postPolishEnabled", true));
            tx.put("source", "1953337039510003712", mapper.createObjectNode()
                    .put("id", "1953337039510003712")
                    .put("authorId", "1953337039510003712")
                    .put("username", "thsottiaux")
                    .put("displayName", "Tibo")
                    .put("bio", "Codex & ChatGPT @OpenAI")
                    .put("status", "ACTIVE"));
            tx.put("style_sample", "stance-openai-1", mapper.createObjectNode()
                    .put("textRole", "AUTHOR")
                    .put("kind", "POST")
                    .put("isQuote", false)
                    .put("text", "最近 OpenAI 模型降智太明显，给再多重置卡我也懒得用")
                    .put("publishedAt", nowInstant.minusSeconds(3600).toEpochMilli()));
            tx.put("style_sample", "stance-unrelated-1", mapper.createObjectNode()
                    .put("textRole", "AUTHOR")
                    .put("kind", "POST")
                    .put("isQuote", false)
                    .put("text", "周末去公园喝茶晒太阳，心情不错")
                    .put("publishedAt", nowInstant.minusSeconds(1800).toEpochMilli()));
            ObjectNode m = mapper.createObjectNode()
                    .put("id", "2125853628250599730")
                    .put("authorId", "1953337039510003712")
                    .put("username", "thsottiaux")
                    .put("authorName", "Tibo")
                    .put("authorBio", "Codex & ChatGPT @OpenAI")
                    .put("text", "Banked reset is online")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", nowInstant.minusSeconds(180).toEpochMilli())
                    .put("sourceUrl", "https://x.com/thsottiaux/status/2125853628250599730")
                    .put("views", 120_000L)
                    .put("likes", 2_000L);
            AssistantWorkflow.ingest(tx, m);
            return null;
        });

        Path fakeCodex = Files.createFile(tempDir.resolve("fake-codex-smart-quote"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment codexEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        List<String> capturedWriterInputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        PiAgentViralJudge smartJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            capturedWriterInputs.add(stdin);
            int outIdx = cmd.indexOf("-o");
            String body;
            if (cmd.contains("--output-schema")) {
                body = """
                        {"verdict":"VIRAL_REPOST","viralScore":86,"factScore":85,"trendScore":84,"attentionScore":88,"accountFit":true,"fitScore":92,"eventMagnitude":82,"publicResonance":86,"credibility":95,"category":"QUOTA_RESET","summary":"Tibo宣布Banked reset上线","reason":"属OpenAI额度重置通知，热议度高"}
                        """;
            } else {
                body = """
                        🎉 Tibo 说，Banked reset 已经上线。

                        💡 省流重点：现在支持了重置功能。

                        看来OpenAI意识到危机了，但是重置额度后，面对的还是降智AI……我都懒得用重置卡了
                        """;
            }
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                Files.writeString(Path.of(cmd.get(outIdx + 1)), body);
            }
            return new PiAgentViralJudge.CliResult(0, body, "");
        });

        AutoRepostService service = new AutoRepostService(
                store, collector, smartJudge, mock(XOAuthService.class), mock(XPublishClient.class), clock);
        try {
            ObjectNode preview = service.previewRepost("thsottiaux", mapper.createObjectNode()
                    .put("materialId", "2125853628250599730")
                    .put("forceRegenerate", true));
            String comment = preview.path("preview").path("commentText").asText("");
            assertThat(comment).contains("🎉 Tibo 说，Banked reset 已经上线。")
                    .contains("看来OpenAI意识到危机了")
                    .doesNotContain("💡 省流重点：");
            assertThat(comment.split("\\n\\n+")).hasSize(2);

            assertThat(capturedWriterInputs).hasSize(3);
            assertThat(capturedWriterInputs.get(1))
                    .contains("\"briefFlashPost\":true")
                    .contains("OpenAI")
                    .contains("currentHotContext")
                    .contains("ownerRecentStances")
                    .contains("降智")
                    .doesNotContain("公园喝茶");
        } finally {
            service.shutdown();
            collector.stop();
        }
    }

    @Test
    void scheduledThirtyMinutePatrolChecksElidlicomTimelineAndPublishesSingleQuoteWhenIdleOverOneHourPlusJitter() throws Exception {
        var method = AutoRepostService.class.getDeclaredMethod("scheduledOwnerIdleRepostCheck");
        var scheduled = method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${xops.auto-repost-patrol-ms:1800000}");

        ObjectMapper mapper = new ObjectMapper();
        AssistantStore patrolStore = new AssistantStore(tempDir.resolve("patrol-30m-store"), mapper);
        Instant nowInstant = ZonedDateTime.of(2026, 11, 26, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        Clock clock = Clock.fixed(nowInstant, ZoneId.of("Asia/Shanghai"));

        SourceCollector mockSources = mock(SourceCollector.class);
        XOAuthService mockOauth = mock(XOAuthService.class);
        XPublishClient mockPublish = mock(XPublishClient.class);
        when(mockOauth.getValidWriteAccessToken("owner-elidli", false)).thenReturn("patrol-write-token");
        when(mockPublish.createTweet(eq("patrol-write-token"), anyString(), eq(List.of()), eq("4100001")))
                .thenReturn("4199001");
        when(mockPublish.createTweet(eq("patrol-write-token"), anyString(), eq(List.of()), eq("4200001")))
                .thenReturn("4299001");

        patrolStore.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode()
                    .put("accountId", "owner-elidli")
                    .put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true));
            tx.put("source", "src-openai", mapper.createObjectNode()
                    .put("id", "src-openai")
                    .put("authorId", "src-openai")
                    .put("username", "OpenAI")
                    .put("displayName", "OpenAI")
                    .put("intervalMinutes", 5)
                    .put("status", "ACTIVE")
                    .put("autoRepostEnabled", true)
                    .put("autoRepostTextMode", "AI")
                    .put("autoRepostMinScore", 70));
            tx.put("source", "src-anthropic", mapper.createObjectNode()
                    .put("id", "src-anthropic")
                    .put("authorId", "src-anthropic")
                    .put("username", "AnthropicAI")
                    .put("displayName", "Anthropic")
                    .put("intervalMinutes", 30)
                    .put("status", "ACTIVE")
                    .put("autoRepostEnabled", true)
                    .put("autoRepostTextMode", "AI")
                    .put("autoRepostMinScore", 70));
            AssistantWorkflow.ingest(tx, mapper.createObjectNode()
                    .put("id", "4100001")
                    .put("authorId", "src-openai")
                    .put("username", "OpenAI")
                    .put("authorName", "OpenAI")
                    .put("text", "Introducing new realtime reasoning API with 60% lower first-token latency across tool calls.")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", nowInstant.minusSeconds(3_000).toEpochMilli())
                    .put("sourceUrl", "https://x.com/OpenAI/status/4100001"));
            AssistantWorkflow.ingest(tx, mapper.createObjectNode()
                    .put("id", "4200001")
                    .put("authorId", "src-anthropic")
                    .put("username", "AnthropicAI")
                    .put("authorName", "Anthropic")
                    .put("text", "Shipping prompt caching improvements with 90% cost reduction on multi-turn agent loops.")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", nowInstant.minusSeconds(2_400).toEpochMilli())
                    .put("sourceUrl", "https://x.com/AnthropicAI/status/4200001"));
            return null;
        });

        Path fakeCodex = Files.createFile(tempDir.resolve("fake-codex-patrol"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment codexEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        AtomicInteger llmCalls = new AtomicInteger();
        PiAgentViralJudge patrolJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            llmCalls.incrementAndGet();
            int outIdx = cmd.indexOf("-o");
            String outBody = cmd.contains("--output-schema")
                    ? "{\"verdict\":\"VIRAL_REPOST\",\"viralScore\":88,\"factScore\":89,\"trendScore\":86,\"attentionScore\":84,\"accountFit\":true,\"fitScore\":91,\"eventMagnitude\":86,\"publicResonance\":85,\"credibility\":93,\"category\":\"FEATURE_UPDATE\",\"summary\":\"首Token延迟降60%\",\"reason\":\"官方一手性能提升数据明确\"}"
                    : "首 token 延迟直接压掉六成，跑多轮工具调用终于不用干等啦😺";
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                Files.writeString(Path.of(cmd.get(outIdx + 1)), outBody);
            }
            return new PiAgentViralJudge.CliResult(0, outBody, "");
        });

        // 1) @elidlicom posted 42 minutes ago (< 1h + N[1..20]m) -> patrol short-circuits, 0 LLM calls, 0 publishes
        long recentElidliTweetAt = nowInstant.minusSeconds(42 * 60L).toEpochMilli();
        when(mockSources.fetchLatestAccountPostEpochMs("elidlicom", "owner-elidli")).thenReturn(recentElidliTweetAt);

        AutoRepostService recentSvc = new AutoRepostService(patrolStore, mockSources, patrolJudge, mockOauth, mockPublish, clock);
        try {
            ObjectNode recentResult = recentSvc.runOwnerIdleRepostPatrol();
            assertThat(recentResult.path("status").asText()).isEqualTo("RECENTLY_TWEETED");
            assertThat(recentResult.path("jitterMinutes").asInt()).isBetween(1, 20);
            assertThat(llmCalls.get()).isZero();
            verify(mockPublish, never()).createTweet(anyString(), anyString(), anyList(), anyString());
        } finally {
            recentSvc.shutdown();
        }

        // 2) Advance clock 50 minutes (total 92 minutes since @elidlicom's last tweet > 1h + N[1..20]m):
        //    Patrol checks @elidlicom, finds configured quote-repost sources, publishes ONE quote-repost, and stops!
        Clock idleClock = Clock.fixed(nowInstant.plusSeconds(50 * 60L), ZoneId.of("Asia/Shanghai"));
        AutoRepostService idleSvc = new AutoRepostService(patrolStore, mockSources, patrolJudge, mockOauth, mockPublish, idleClock);
        try {
            ObjectNode idleResult = idleSvc.runOwnerIdleRepostPatrol();
            assertThat(idleResult.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(idleResult.path("materialId").asText()).isEqualTo("4100001");
            assertThat(idleResult.path("sourceUsername").asText()).isEqualTo("OpenAI");
            verify(mockPublish, times(1)).createTweet(eq("patrol-write-token"), anyString(), eq(List.of()), eq("4100001"));
            verify(mockPublish, never()).createTweet(eq("patrol-write-token"), anyString(), eq(List.of()), eq("4200001"));

            // 3) Immediate next patrol sees the just-published quote-repost as @elidlicom's latest tweet -> RECENTLY_TWEETED
            ObjectNode postPublishCheck = idleSvc.runOwnerIdleRepostPatrol();
            assertThat(postPublishCheck.path("status").asText()).isEqualTo("RECENTLY_TWEETED");
            verify(mockPublish, times(1)).createTweet(anyString(), anyString(), anyList(), anyString());
        } finally {
            idleSvc.shutdown();
        }
    }

    @Test
    void localStartupDisablesAutoRepostAndScheduledPublishingWhileProductionAllowsBoth() {
        com.xops.hub.config.XOpsProperties localProps = new com.xops.hub.config.XOpsProperties();
        MockEnvironment defaultEnv = new MockEnvironment();
        MockEnvironment localProfileEnv = new MockEnvironment();
        localProfileEnv.setActiveProfiles("local");
        MockEnvironment previewProfileEnv = new MockEnvironment();
        previewProfileEnv.setActiveProfiles("preview");

        // 1. Blank or localhost redirectUri => local/non-production => auto-repost & scheduled publishing disabled
        localProps.setSchedulerEnabled(true);
        localProps.setAutoRepostEnabled(null);
        localProps.setOauthRedirectUri("");
        assertThat(localProps.isAutoRepostActive(defaultEnv)).isFalse();
        assertThat(localProps.isScheduledPublishActive(defaultEnv)).isFalse();

        localProps.setOauthRedirectUri("http://localhost:8080/api/auth/x/callback");
        assertThat(localProps.isAutoRepostActive(defaultEnv)).isFalse();
        assertThat(localProps.isScheduledPublishActive(defaultEnv)).isFalse();

        // 2. Active local/preview profile overrides even an HTTPS redirectUri
        localProps.setOauthRedirectUri("https://x.life-benchmark.com/api/auth/x/callback");
        assertThat(localProps.isAutoRepostActive(localProfileEnv)).isFalse();
        assertThat(localProps.isScheduledPublishActive(localProfileEnv)).isFalse();
        assertThat(localProps.isAutoRepostActive(previewProfileEnv)).isFalse();
        assertThat(localProps.isScheduledPublishActive(previewProfileEnv)).isFalse();

        // 3. Production HTTPS origin + no non-production profile => both enabled
        assertThat(localProps.isAutoRepostActive(defaultEnv)).isTrue();
        assertThat(localProps.isScheduledPublishActive(defaultEnv)).isTrue();

        // 4. Verify AutoRepostService & ScheduledTweetService stay idle in local profile
        ObjectMapper mapper = new ObjectMapper();
        AssistantStore store = new AssistantStore(tempDir.resolve("local-guard-store"), mapper);
        SourceCollector collector = mock(SourceCollector.class);
        PiAgentViralJudge judge = mock(PiAgentViralJudge.class);
        XOAuthService oauth = mock(XOAuthService.class);
        XPublishClient publish = mock(XPublishClient.class);
        AutoRepostService autoRepost = new AutoRepostService(store, collector, judge, oauth, publish);
        com.xops.hub.config.XOpsProperties runtimeProps = new com.xops.hub.config.XOpsProperties();
        runtimeProps.setSchedulerEnabled(true);
        runtimeProps.setOauthRedirectUri("http://localhost:8080/api/auth/x/callback");
        ReflectionTestUtils.setField(autoRepost, "properties", runtimeProps);
        ReflectionTestUtils.setField(autoRepost, "environment", localProfileEnv);

        try {
            assertThat(autoRepost.isAutoRepostRuntimeEnabled()).isFalse();
            autoRepost.bindHook();
            verify(collector, never()).setIngestHook(any());
            verify(collector, never()).setBatchIngestHook(any());
            autoRepost.enqueueAuthorBatch(
                    mapper.createObjectNode().put("authorId", "u1").put("autoRepostEnabled", true),
                    List.of(mapper.createObjectNode().put("id", "9001").put("text", "test post")));
            verifyNoInteractions(judge, publish);
            assertThat(autoRepost.runOwnerIdleRepostPatrol().path("status").asText()).isEqualTo("LOCAL_DISABLED");
        } finally {
            autoRepost.shutdown();
        }

        runtimeProps.setDiskCacheDir(tempDir.resolve("local-sched-cache").toString());
        com.xops.hub.service.ScheduledTweetService scheduledService =
                new com.xops.hub.service.ScheduledTweetService(runtimeProps, mapper, oauth, publish, Clock.systemUTC());
        ReflectionTestUtils.setField(scheduledService, "environment", localProfileEnv);
        try {
            assertThat(scheduledService.isScheduledPublishRuntimeEnabled()).isFalse();
            scheduledService.onApplicationReady();
            scheduledService.runScheduledTick();
            verifyNoInteractions(oauth, publish);
        } finally {
            scheduledService.shutdown();
        }
    }

    @Test
    void piAgentUnifiedMultiProviderRoutingPassesProviderModelAndEnvWithoutLeakingKeyInCliArgs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fakePi = Files.createFile(tempDir.resolve("fake-pi-multi"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment env = new MockEnvironment()
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, fakePi.toString());

        List<List<String>> capturedCmds = new java.util.concurrent.CopyOnWriteArrayList<>();
        PiAgentViralJudge judge = new PiAgentViralJudge(mapper, env, (cmd, stdin, timeout) -> {
            capturedCmds.add(cmd);
            return new PiAgentViralJudge.CliResult(0, "ok-from-pi", "");
        });

        List<Path> skills = judge.resolveHumanizerSkillFiles();
        assertThat(skills).isNotEmpty();

        String oai = judge.runPiProviderCompletion(
                "openai", "gpt-6-luna xhigh", "sys", "user-msg", skills, java.time.Duration.ofSeconds(30), "sk-secret-openai-999");
        assertThat(oai).isEqualTo("ok-from-pi");
        assertThat(capturedCmds.get(0)).contains("--provider", "openai", "--model", "gpt-6-luna", "--thinking", "xhigh", "--skill");
        assertThat(String.join(" ", capturedCmds.get(0))).doesNotContain("sk-secret-openai-999");

        judge.runPiProviderCompletion(
                "google", "gemini-4-carbon high", "sys", "user-msg", List.of(), java.time.Duration.ofSeconds(30), "AIza-secret-google-777");
        assertThat(capturedCmds.get(1)).contains("--provider", "google", "--model", "gemini-4-carbon", "--thinking", "high", "--no-skills");
        assertThat(String.join(" ", capturedCmds.get(1))).doesNotContain("AIza-secret-google-777");

        judge.runPiProviderCompletion(
                "deepseek", "deepseek-chat", "sys", "user-msg", List.of(), java.time.Duration.ofSeconds(30), "ds-secret-555");
        assertThat(capturedCmds.get(2)).contains("--provider", "deepseek", "--model", "deepseek-chat", "--no-skills");
        assertThat(capturedCmds.get(2)).doesNotContain("--thinking");
        assertThat(String.join(" ", capturedCmds.get(2))).doesNotContain("ds-secret-555");

        judge.runPiProviderCompletion(
                "chatgpt", "gpt-6.2-astra medium", "sys", "user-msg", skills, java.time.Duration.ofSeconds(30), "");
        assertThat(capturedCmds.get(3)).contains("--provider", "openai-codex", "--model", "gpt-6.2-astra", "--thinking", "medium", "--skill");

        assertThat(PiAgentViralJudge.isDefaultOfficialEndpoint("openai", "https://api.openai.com/v1")).isTrue();
        assertThat(PiAgentViralJudge.isDefaultOfficialEndpoint("openai", "https://custom-proxy.example/v1")).isFalse();
        assertThat(PiAgentViralJudge.isDefaultOfficialEndpoint("minimax", "https://api.minimax.cn/v1")).isFalse();
        assertThat(PiAgentViralJudge.isDefaultOfficialEndpoint("minimax", "https://api.minimax.io/v1")).isTrue();
    }

    @Test
    void ownerDistillationMergesHistoryMaterialExcludesOtherAuthorsAndInjectsDistilledPersona() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AssistantStore store = new AssistantStore(tempDir.resolve("distill-merge-store"), mapper);
        Instant nowInstant = Instant.now();
        Clock clock = Clock.fixed(nowInstant, ZoneId.of("Asia/Shanghai"));
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(community.longHistoryCandidates()).thenReturn(List.of());
        SourceCollector collector = new SourceCollector(store, provider, community, mapper, clock);

        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-id-1").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx)
                    .put("enabled", true)
                    .put("collectionEnabled", true)
                    .put("postPolishEnabled", false));
            tx.put("meta", "style", mapper.createObjectNode()
                    .put("profile", "短句直给、厌恶套话；对 OpenAI 降智与额度套路保持讽刺，对真正提效的工程工具直白肯定。")
                    .put("sampleCount", 120));
            tx.put("style_sample", "ss-quote-1", mapper.createObjectNode()
                    .put("textRole", "AUTHOR")
                    .put("kind", "QUOTE")
                    .put("isQuote", true)
                    .put("text", "又把老模型换皮当新能力吹了一轮，实际写起大仓库还是老样子")
                    .put("publishedAt", nowInstant.minusSeconds(7200).toEpochMilli()));
            tx.put("history_material", "hm-owner-quote-2", mapper.createObjectNode()
                    .put("id", "hm-owner-quote-2")
                    .put("authorId", "owner-id-1")
                    .put("username", "elidlicom")
                    .put("kind", "QUOTE")
                    .put("text", "OpenAI 最近连续降智后，给再多重置卡我也懒得用了")
                    .put("publishedAt", nowInstant.minusSeconds(3600).toEpochMilli()));
            tx.put("history_material", "hm-other-author", mapper.createObjectNode()
                    .put("id", "hm-other-author")
                    .put("authorId", "other-author-9")
                    .put("username", "stranger_dev")
                    .put("kind", "QUOTE")
                    .put("text", "无关路人对 OpenAI 降智和重置卡的胡乱评价，绝不应该混入本人语料")
                    .put("publishedAt", nowInstant.minusSeconds(1200).toEpochMilli()));
            ObjectNode m = mapper.createObjectNode()
                    .put("id", "2125853628250599731")
                    .put("authorId", "1953337039510003712")
                    .put("username", "thsottiaux")
                    .put("authorName", "Tibo")
                    .put("authorBio", "Codex & ChatGPT @OpenAI")
                    .put("text", "Banked reset is online")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", nowInstant.minusSeconds(120).toEpochMilli())
                    .put("sourceUrl", "https://x.com/thsottiaux/status/2125853628250599731");
            AssistantWorkflow.ingest(tx, m);
            return null;
        });

        List<ObjectNode> merged = store.tx(tx -> OwnerQuoteStyleDistiller.collectOwnerAuthoredSamples(tx, 50));
        assertThat(merged).extracting(n -> n.path("text").asText())
                .anyMatch(t -> t.contains("换皮当新能力"))
                .anyMatch(t -> t.contains("重置卡我也懒得用了"))
                .noneMatch(t -> t.contains("无关路人"));

        ObjectNode quoteStyleMeta = store.tx(OwnerQuoteStyleDistiller::resolveOrDistillQuoteStyle);
        assertThat(quoteStyleMeta.path("historicalQuoteSampleCount").asInt()).isEqualTo(2);
        assertThat(quoteStyleMeta.path("distilledPersonaProfile").asText()).contains("对 OpenAI 降智与额度套路保持讽刺");

        Path fakeCodex = Files.createFile(tempDir.resolve("fake-codex-distill"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment codexEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.CODEX_CLI_PATH_ENV, fakeCodex.toString())
                .withProperty(PiAgentViralJudge.BACKEND_ENV, "codex");
        List<String> capturedWriterInputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        PiAgentViralJudge distillJudge = new PiAgentViralJudge(mapper, codexEnv, (cmd, stdin, timeout) -> {
            if (cmd.contains("login") && cmd.contains("status")) {
                return new PiAgentViralJudge.CliResult(0, "Logged in using ChatGPT", "");
            }
            capturedWriterInputs.add(stdin);
            int outIdx = cmd.indexOf("-o");
            String body = cmd.contains("--output-schema")
                    ? "{\"verdict\":\"VIRAL_REPOST\",\"viralScore\":88,\"factScore\":86,\"trendScore\":84,\"attentionScore\":86,\"accountFit\":true,\"fitScore\":92,\"eventMagnitude\":84,\"publicResonance\":85,\"credibility\":95,\"category\":\"QUOTA_RESET\",\"summary\":\"Banked reset上线\",\"reason\":\"额度重置通知\"}"
                    : "🎉 Tibo 说，Banked reset 已经上线。\n\n看来OpenAI意识到危机了，但是重置额度后，面对的还是降智AI……我都懒得用重置卡了";
            if (outIdx >= 0 && outIdx + 1 < cmd.size()) {
                Files.writeString(Path.of(cmd.get(outIdx + 1)), body);
            }
            return new PiAgentViralJudge.CliResult(0, body, "");
        });

        AutoRepostService service = new AutoRepostService(
                store, collector, distillJudge, mock(XOAuthService.class), mock(XPublishClient.class), clock);
        try {
            service.previewRepost("thsottiaux", mapper.createObjectNode()
                    .put("materialId", "2125853628250599731")
                    .put("forceRegenerate", true));
            assertThat(capturedWriterInputs).hasSize(2);
            assertThat(capturedWriterInputs.get(1))
                    .contains("ownerDistilledPersona")
                    .contains("对 OpenAI 降智与额度套路保持讽刺")
                    .contains("重置卡我也懒得用了")
                    .doesNotContain("无关路人");
        } finally {
            service.shutdown();
            collector.stop();
        }
    }

    @Test
    void autoRepostQuietWindowBlocksAllAutomaticPushesBetweenNoonAndMidnight() throws Exception {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        assertThat(com.xops.hub.config.XOpsProperties.isQuietHour(
                ZonedDateTime.of(2026, 11, 26, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli(), shanghai, 12, 24)).isFalse();
        assertThat(com.xops.hub.config.XOpsProperties.isQuietHour(
                ZonedDateTime.of(2026, 11, 26, 11, 59, 59, 0, shanghai).toInstant().toEpochMilli(), shanghai, 12, 24)).isFalse();
        assertThat(com.xops.hub.config.XOpsProperties.isQuietHour(
                ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, shanghai).toInstant().toEpochMilli(), shanghai, 12, 24)).isTrue();
        assertThat(com.xops.hub.config.XOpsProperties.isQuietHour(
                ZonedDateTime.of(2026, 11, 26, 17, 30, 0, 0, shanghai).toInstant().toEpochMilli(), shanghai, 12, 24)).isTrue();
        assertThat(com.xops.hub.config.XOpsProperties.isQuietHour(
                ZonedDateTime.of(2026, 11, 26, 23, 59, 59, 0, shanghai).toInstant().toEpochMilli(), shanghai, 12, 24)).isTrue();
        assertThat(com.xops.hub.config.XOpsProperties.isQuietHour(
                ZonedDateTime.of(2026, 11, 27, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli(), shanghai, 12, 24)).isFalse();

        long eveningMs = ZonedDateTime.of(2026, 11, 26, 21, 0, 0, 0, shanghai).toInstant().toEpochMilli();
        long expectedWakeMs = ZonedDateTime.of(2026, 11, 27, 0, 0, 5, 0, shanghai).toInstant().toEpochMilli();
        assertThat(com.xops.hub.config.XOpsProperties.nextQuietWindowEndEpochMs(eveningMs, shanghai, 12, 24))
                .isEqualTo(expectedWakeMs);

        ObjectMapper mapper = new ObjectMapper();
        AssistantStore quietStore = new AssistantStore(tempDir.resolve("quiet-hours-store"), mapper);
        Instant noonInstant = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, shanghai).toInstant();
        long enabledAt = noonInstant.minusSeconds(3600).toEpochMilli();

        ObjectNode srcAndMat = quietStore.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-q").put("username", "elidlicom"));
            tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("autoRepostCooldownMinutes", 0));
            ObjectNode src = tx.put("source", "u-quiet", mapper.createObjectNode()
                    .put("id", "u-quiet")
                    .put("authorId", "u-quiet")
                    .put("username", "thsottiaux")
                    .put("displayName", "Tibo")
                    .put("status", "ACTIVE")
                    .put("intervalMinutes", 5)
                    .put("autoRepostEnabled", true)
                    .put("autoRepostEnabledAt", enabledAt)
                    .put("autoRepostTextMode", "AI")
                    .put("autoRepostMinScore", 70));
            ObjectNode mat = mapper.createObjectNode()
                    .put("id", "5100001")
                    .put("authorId", "u-quiet")
                    .put("username", "thsottiaux")
                    .put("authorName", "Tibo")
                    .put("text", "Codex Banked reset is live now for all subscribers!")
                    .put("textStatus", "TEXT_ONLY")
                    .put("kind", "POST")
                    .put("publishedAt", noonInstant.minusSeconds(120).toEpochMilli())
                    .put("sourceUrl", "https://x.com/thsottiaux/status/5100001");
            AssistantWorkflow.ingest(tx, mat);
            tx.put("auto_repost", "5100000", mapper.createObjectNode()
                    .put("materialId", "5100000")
                    .put("authorId", "u-quiet")
                    .put("username", "thsottiaux")
                    .put("status", "SKIPPED_COOLDOWN")
                    .put("deferredPendingEval", true)
                    .put("deferredUntilMs", noonInstant.minusSeconds(60).toEpochMilli()));
            ObjectNode out = mapper.createObjectNode();
            out.set("source", src);
            out.set("material", tx.get("material", "5100001"));
            return out;
        });
        ObjectNode source = (ObjectNode) srcAndMat.get("source");
        ObjectNode mat = (ObjectNode) srcAndMat.get("material");

        SourceCollector mockSources = mock(SourceCollector.class);
        PiAgentViralJudge mockJudge = mock(PiAgentViralJudge.class);
        XOAuthService mockOauth = mock(XOAuthService.class);
        XPublishClient mockPublish = mock(XPublishClient.class);

        for (Instant quietInstant : List.of(noonInstant, Instant.ofEpochMilli(eveningMs))) {
            Clock quietClock = Clock.fixed(quietInstant, shanghai);
            AutoRepostService quietSvc = new AutoRepostService(
                    quietStore, mockSources, mockJudge, mockOauth, mockPublish, quietClock);
            try {
                assertThat(quietSvc.isInAutoRepostQuietWindow(quietInstant.toEpochMilli())).isTrue();
                assertThat(quietSvc.isInAutoRepostQuietWindow(
                        ZonedDateTime.of(2026, 11, 26, 11, 59, 0, 0, shanghai).toInstant().toEpochMilli())).isFalse();

                quietSvc.processCandidate(source, mat);
                quietSvc.enqueueAuthorBatch(source, List.of(mat));
                quietSvc.recheckDeferredCooldownCandidates();
                quietSvc.scheduledOwnerIdleRepostCheck();

                ObjectNode patrolResult = quietSvc.runOwnerIdleRepostPatrol();
                assertThat(patrolResult.path("status").asText()).isEqualTo("QUIET_HOURS");
                assertThat(patrolResult.path("quietStartHour").asInt()).isEqualTo(12);
                assertThat(patrolResult.path("quietEndHour").asInt()).isEqualTo(24);
                assertThat(quietStore.<ObjectNode>tx(tx -> tx.get("auto_repost", "5100001"))).isNull();
            } finally {
                quietSvc.shutdown();
            }
        }
        verifyNoInteractions(mockJudge);
        verifyNoInteractions(mockPublish);
    }

    @Test
    void piAgentPassesOversizedDistillationPromptViaStdinAndTempFileWithoutHttpFallback() throws Exception {
        Path fakePi = Files.createFile(tempDir.resolve("fake-pi-large"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        MockEnvironment piEnv = new MockEnvironment()
                .withProperty(PiAgentViralJudge.PI_CLI_PATH_ENV, fakePi.toString())
                .withProperty("OPENAI_API_KEY", "sk-test-no-http-fallback");

        String hugeUserPrompt = "样本内容：今天测试降智 AI 与重置卡，完全不值。\n".repeat(4000);
        String hugeSystemPrompt = "风格蒸馏规则：保持短句直给。\n".repeat(1200);
        assertThat(hugeUserPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(200_000);
        assertThat(hugeSystemPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(40_000);

        List<String> capturedStdin = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Path> capturedTempFiles = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failNextCall = new java.util.concurrent.atomic.AtomicBoolean(false);

        PiAgentViralJudge judge = new PiAgentViralJudge(new ObjectMapper(), piEnv, (cmd, stdin, timeout) -> {
            if (failNextCall.get()) {
                return new PiAgentViralJudge.CliResult(1, "", "simulated pi failure");
            }
            for (String arg : cmd) {
                assertThat(arg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
            }
            int sysIdx = cmd.indexOf("--system-prompt");
            assertThat(sysIdx).isGreaterThanOrEqualTo(0);
            Path sysFile = Path.of(cmd.get(sysIdx + 1));
            assertThat(Files.isRegularFile(sysFile)).isTrue();
            assertThat(Files.readString(sysFile)).isEqualTo(hugeSystemPrompt);
            capturedTempFiles.add(sysFile);
            capturedStdin.add(stdin);
            return new PiAgentViralJudge.CliResult(0, "{\"status\":\"ok\"}", "");
        });

        String out = judge.runPiProviderCompletion(
                "openai",
                "gpt-6-luna xhigh",
                hugeSystemPrompt,
                hugeUserPrompt,
                List.of(),
                java.time.Duration.ofSeconds(120),
                "sk-test-no-http-fallback");
        assertThat(out).isEqualTo("{\"status\":\"ok\"}");
        assertThat(capturedStdin).containsExactly(hugeUserPrompt);
        assertThat(capturedTempFiles).hasSize(1);
        assertThat(Files.exists(capturedTempFiles.get(0))).isFalse();

        // When PI Agent fails for a supported official provider, HTTP fallback must NOT run.
        failNextCall.set(true);
        assertThatThrownBy(() -> judge.runApiQuoteCall("openai", "gpt-6-luna xhigh", "sys", "usr", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PI Agent")
                .hasMessageContaining("simulated pi failure");
    }
}
