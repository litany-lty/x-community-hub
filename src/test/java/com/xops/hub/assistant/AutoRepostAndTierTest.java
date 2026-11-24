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
            if (stdin.contains("黄仁勋今晚宣布恢复总裁职务")) {
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
        Instant now = ZonedDateTime.of(2026, 11, 23, 21, 0, 0, 0, shanghai).toInstant();
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
                ObjectNode m = mapper.createObjectNode().put("id", "1890101").put("authorId", "u-breaking")
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
                    .andExpect(jsonPath("$.autoReposts.length()").value(3));
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
}
