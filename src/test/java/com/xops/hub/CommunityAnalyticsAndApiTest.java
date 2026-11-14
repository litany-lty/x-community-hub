package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.*;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import com.xops.hub.util.HandleNormalizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xops.default-provider=DEMO",
                "xops.require-login-self-only=true",
                "xops.oauth-client-id=test_oauth_client_id",
                "xops.oauth-client-secret=test_oauth_secret"
        }
)
class CommunityAnalyticsAndApiTest {

    private static HttpServer mockXOfficialOAuthServer;
    private static java.nio.file.Path testDiskCacheDir;

    @DynamicPropertySource
    static void registerMockOAuthProperties(DynamicPropertyRegistry registry) throws IOException {
        testDiskCacheDir = java.nio.file.Files.createTempDirectory("xops-springboot-test-cache-");
        mockXOfficialOAuthServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockXOfficialOAuthServer.createContext("/2/oauth2/token", exchange -> writeJson(exchange, """
                {"token_type":"bearer","access_token":"mock_user_access_token_xyz","scope":"users.read tweet.read"}
                """));
        mockXOfficialOAuthServer.createContext("/2/users/me", exchange -> writeJson(exchange, """
                {"data":{"id":"10001","username":"ops_member","name":"Ops Member","profile_image_url":"https://pbs.twimg.com/a_normal.jpg","verified":true}}
                """));
        mockXOfficialOAuthServer.start();
        int port = mockXOfficialOAuthServer.getAddress().getPort();
        registry.add("xops.official-base-url", () -> "http://127.0.0.1:" + port + "/2");
        registry.add("xops.disk-cache-dir", () -> testDiskCacheDir.toString());
    }

    @AfterAll
    static void stopMockOAuthServer() {
        if (mockXOfficialOAuthServer != null) {
            mockXOfficialOAuthServer.stop(0);
        }
        if (testDiskCacheDir != null) {
            try (var walk = java.nio.file.Files.walk(testDiskCacheDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void handleNormalizerShouldParseHandlesAndProfileUrlsAndRejectInvalidInputs() {
        assertThat(HandleNormalizer.normalize("@elidlicom")).isEqualTo("elidlicom");
        assertThat(HandleNormalizer.normalize("maya_web3")).isEqualTo("maya_web3");
        assertThat(HandleNormalizer.normalize("https://x.com/chen_ai_lab?s=20")).isEqualTo("chen_ai_lab");
        assertThat(HandleNormalizer.normalize("https://mobile.x.com/elidlicom")).isEqualTo("elidlicom");
        assertThat(HandleNormalizer.normalize("https://twitter.com/@lin_indie/status/123")).isEqualTo("lin_indie");

        assertThatThrownBy(() -> HandleNormalizer.normalize("https://x.com/home"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HandleNormalizer.normalize("https://x.com/compose/post"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HandleNormalizer.normalize("bad handle!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void analyticsEngineShouldSupportDayWeekMonthDimensionsAndAccurateMetrics() {
        Instant now = Instant.parse("2026-11-12T08:00:00Z");
        XUserSummary me = new XUserSummary("1", "my_ops", "My Ops", "", "bio", true, 5000, 300, 800, "2023");
        XUserSummary peerBalanced = new XUserSummary("2", "alice_dev", "Alice", "", "dev", true, 3200, 400, 500, "2023");
        XUserSummary peerOneWay = new XUserSummary("3", "bob_cold", "Bob", "", "cold", false, 1200, 200, 120, "2023");
        XUserSummary bigKol = new XUserSummary("4", "mega_kol", "Mega KOL", "", "kol", true, 95000, 100, 3000, "2020");
        XUserSummary loyalFan = new XUserSummary("5", "fan_lily", "Lily", "", "fan", false, 600, 500, 90, "2024");

        List<RawTweet> timeline = List.of(
                new RawTweet("t1", "Post 1 🧵", now.minus(Duration.ofHours(6)), TweetKind.POST,
                        null, null, List.of(), false, true, 2000, 80, 15, 10, 20),
                new RawTweet("t2", "Post 2", now.minus(Duration.ofDays(3)), TweetKind.POST,
                        null, null, List.of(), true, false, 1000, 30, 5, 5, 5),
                new RawTweet("t_month", "Month Post", now.minus(Duration.ofDays(20)), TweetKind.POST,
                        null, null, List.of(), false, false, 5000, 200, 20, 20, 20),
                // 动态滚动近30天边界内（29天20小时前，跨自然日落在第 -30 天傍晚）：应纳入月维度统计与首桶趋势图
                new RawTweet("t_rolling_edge_in", "Rolling 29d20h", now.minus(Duration.ofDays(29)).minus(Duration.ofHours(20)), TweetKind.POST,
                        null, null, List.of(), false, false, 1500, 40, 4, 3, 6),
                // 超过动态滚动30天（30天1小时前）：即使同属上月也应精准排除
                new RawTweet("t_rolling_edge_out", "Older than 30d", now.minus(Duration.ofDays(30)).minus(Duration.ofHours(1)), TweetKind.POST,
                        null, null, List.of(), false, false, 3000, 90, 8, 5, 10),
                new RawTweet("r1", "hi1", now.minus(Duration.ofHours(30)), TweetKind.REPLY, "alice_dev", peerBalanced, List.of("alice_dev"), false, false, 100, 2, 0, 0, 0, false, "peer-root-1", "peer-root-1"),
                new RawTweet("r2", "hi2", now.minus(Duration.ofHours(12)), TweetKind.REPLY, "alice_dev", peerBalanced, List.of("alice_dev"), false, false, 100, 2, 0, 0, 0, false, "peer-root-2", "peer-root-2"),
                new RawTweet("r3", "hi3", now.minus(Duration.ofHours(14)), TweetKind.REPLY, "alice_dev", peerBalanced, List.of("alice_dev"), false, false, 100, 2, 0, 0, 0, false, "peer-root-3", "peer-root-3"),
                new RawTweet("r4", "yo1", now.minus(Duration.ofDays(3)), TweetKind.REPLY, "bob_cold", peerOneWay, List.of("bob_cold"), false, false, 80, 1, 0, 0, 0, false, "peer-root-4", "peer-root-4"),
                new RawTweet("r5", "yo2", now.minus(Duration.ofDays(3)), TweetKind.REPLY, "bob_cold", peerOneWay, List.of("bob_cold"), false, false, 80, 1, 0, 0, 0, false, "peer-root-5", "peer-root-5"),
                new RawTweet("r6", "yo3", now.minus(Duration.ofDays(3)), TweetKind.REPLY, "bob_cold", peerOneWay, List.of("bob_cold"), false, false, 80, 1, 0, 0, 0, false, "peer-root-6", "peer-root-6")
        );

        List<InboundInteraction> inbound = List.of(
                new InboundInteraction("in1", peerBalanced, now.minus(Duration.ofHours(5)), InboundKind.REPLY_TO_ME, "great 1", "self-root-1", "self-root-1", "my_ops"),
                new InboundInteraction("in2", peerBalanced, now.minus(Duration.ofHours(6)), InboundKind.REPLY_TO_ME, "great 2", "self-root-2", "self-root-2", "my_ops"),
                new InboundInteraction("in3", peerBalanced, now.minus(Duration.ofHours(7)), InboundKind.REPLY_TO_ME, "great 3", "self-root-3", "self-root-3", "my_ops"),
                new InboundInteraction("in4", loyalFan, now.minus(Duration.ofHours(8)), InboundKind.REPLY_TO_ME, "love this", "self-root-4", "self-root-4", "my_ops"),
                new InboundInteraction("in5", loyalFan, now.minus(Duration.ofHours(9)), InboundKind.REPLY_TO_ME, "helpful", "self-root-5", "self-root-5", "my_ops")
        );

        List<RelationshipEntry> relationships = List.of(
                new RelationshipEntry(peerBalanced, true, true),
                new RelationshipEntry(peerOneWay, true, false),
                new RelationshipEntry(bigKol, true, false),
                new RelationshipEntry(loyalFan, false, true)
        );

        RawAccountSnapshot snapshot = new RawAccountSnapshot(
                ProviderMode.DEMO, false, "", now, me, timeline, inbound, relationships
        );

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();

        CommunityDashboardReport hours48Report = engine.analyze(snapshot, 2, ZoneId.of("Asia/Shanghai"), false);
        assertThat(hours48Report.periodCode()).isEqualTo("HOURS_48");
        assertThat(hours48Report.periodLabel()).isEqualTo("48小时动态窗口");
        assertThat(hours48Report.windowDays()).isEqualTo(2);
        assertThat(hours48Report.activity().trendSeries()).hasSize(24);
        assertThat(hours48Report.activity().originalPosts()).isEqualTo(1);
        // r1 在 30 小时前（>24h 且 <48h），必须被 48 小时动态窗口纳入；r4..r6 在 3 天前，排除在 48h 外
        assertThat(hours48Report.activity().replies()).isEqualTo(3);
        assertThat(hours48Report.innerCircle().shareCard().reportTitle()).contains("48h战报");

        CommunityDashboardReport weekReport = engine.analyze(snapshot, 7, ZoneId.of("Asia/Shanghai"), false);
        assertThat(weekReport.periodCode()).isEqualTo("WEEK");
        assertThat(weekReport.periodLabel()).isEqualTo("7天动态窗口");
        assertThat(weekReport.activity().trendSeries()).hasSize(7);
        assertThat(weekReport.activity().originalPosts()).isEqualTo(2);
        assertThat(weekReport.activity().replies()).isEqualTo(6);
        assertThat(weekReport.activity().replyToPostRatio()).isEqualTo(3.0);
        assertThat(weekReport.innerCircle().shareCard().reportTitle()).contains("7天战报");

        PeerInteractionStat bobStat = weekReport.interactions().peerStats().stream()
                .filter(p -> p.peer().username().equals("bob_cold"))
                .findFirst()
                .orElseThrow();
        assertThat(bobStat.replyRatioLabel()).isEqualTo("3 : 0");
        assertThat(bobStat.returnRateLabel()).isEqualTo("0%");
        assertThat(bobStat.reciprocityCode()).isEqualTo("THEY_OWE");
        assertThat(bobStat.relationshipType()).isEqualTo("👀 TA 没来评我帖");

        CommunityDashboardReport monthReport = engine.analyze(snapshot, 30, ZoneId.of("Asia/Shanghai"), false);
        assertThat(monthReport.periodCode()).isEqualTo("MONTH");
        assertThat(monthReport.periodLabel()).isEqualTo("30天动态窗口");
        assertThat(monthReport.activity().trendSeries()).hasSize(30);
        assertThat(monthReport.activity().originalPosts()).isEqualTo(4);
        int trendPostsSum = monthReport.activity().trendSeries().stream().mapToInt(TrendBucketPoint::posts).sum();
        assertThat(trendPostsSum).isEqualTo(4);
        assertThat(monthReport.activity().windowRangeText()).contains("10月13日 16:00 – 11月12日 16:00");
        assertThat(monthReport.innerCircle().shareCard().reportTitle()).contains("30天战报");
    }

    @Test
    void shouldSeparateSelfThreadRepliesProtectTruncatedFollowersAndCapAnomalousEr() {
        Instant now = Instant.parse("2026-11-12T08:00:00Z");
        XUserSummary me = new XUserSummary("1", "creator_x", "Creator", "", "", true, 8500, 400, 1200, "2022");
        XUserSummary earlyOldFan = new XUserSummary("2", "early_mutual", "Early Fan", "", "", false, 900, 300, 100, "2021");

        List<RawTweet> timeline = List.of(
                // 主推：1000 浏览，50 互动（ER = 5.0%）
                new RawTweet("main1", "1/3 Main hook 🧵", now.minus(Duration.ofHours(4)), TweetKind.POST,
                        null, null, List.of(), false, true, 1000, 30, 5, 5, 10, false),
                // 串推子楼层（2/3 自回复）：不应计入独立原创主推数，也不应稀释单帖均浏览
                new RawTweet("sub2", "2/3 Sub thread detail", now.minus(Duration.ofHours(4)), TweetKind.POST,
                        null, null, List.of(), false, true, 120, 4, 0, 0, 1, true),
                // 刚发布 0 浏览曝光但已有 8 赞的新推文：不应拉爆互动率至 Infinity 或污染均值
                new RawTweet("zero_view", "Brand new post", now.minus(Duration.ofMinutes(2)), TweetKind.POST,
                        null, null, List.of(), false, false, 0, 8, 2, 0, 1, false)
        );

        RawAccountSnapshot truncatedSnapshot = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO,
                true,
                "",
                now,
                me,
                timeline,
                List.of(),
                List.of(new RelationshipEntry(earlyOldFan, true, false)),
                true,
                false
        );

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();
        CommunityDashboardReport report = engine.analyze(truncatedSnapshot, 7, ZoneId.of("Asia/Shanghai"), false);

        // 仅 main1 和 zero_view 计为独立主推（2 条），sub2 不虚增发帖数；总浏览包含 sub2 共 1120
        assertThat(report.activity().originalPosts()).isEqualTo(2);
        assertThat(report.activity().totalViews()).isEqualTo(1120L);
        assertThat(report.activity().avgEngagementRatePct()).isEqualTo(5.0);

        // 粉丝列表截断时，未采到的老粉回关状态未知；单独计数，不进入确定未回关名单或建议取关数。
        assertThat(report.followAudit().followersSampleTruncated()).isTrue();
        assertThat(report.followAudit().unknownFollowBackCount()).isEqualTo(1);
        assertThat(report.followAudit().notFollowingBackCount()).isZero();
        assertThat(report.followAudit().notFollowingBackList()).isEmpty();
        assertThat(report.followAudit().suggestedUnfollowCount()).isZero();
    }

    @Test
    void shouldComputeXAlgoScoresReachPoolsRapidFireAndExternalLinkDiagnostics() {
        Instant now = Instant.parse("2026-11-12T12:00:00Z");
        XUserSummary me = new XUserSummary("1", "algo_tester", "Algo Tester", "", "builder", true, 1000, 200, 500, "2022");

        List<RawTweet> timeline = List.of(
                // 1. 破圈高收藏原生干货帖（2600 浏览 = 2.6x 粉丝，无外链，间隔充足）
                new RawTweet("viral_clean", "复盘我做独立开发的 5 个增长踩坑经验 🧵 建议收藏", now.minus(Duration.ofHours(10)),
                        TweetKind.POST, null, null, List.of(), false, true, 2600, 95, 18, 14, 32, false),
                // 2. 原生配图帖末尾带 1 个官方 t.co 媒体短链：不应误判为外部导流链接
                new RawTweet("native_media", "今日产品看板实拍更新 https://t.co/picAbc123", now.minus(Duration.ofHours(5)),
                        TweetKind.POST, null, null, List.of(), true, false, 700, 28, 6, 3, 5, false),
                // 3. 距上一条仅 25 分钟连发 + 带外部网页链接的低触达帖（40 浏览 < 5% 粉丝）
                new RawTweet("rapid_ext", "新文章发布了快看 https://myblog.example.com/post-1", now.minus(Duration.ofHours(5)).plus(Duration.ofMinutes(25)),
                        TweetKind.POST, null, null, List.of(), false, false, 40, 1, 0, 0, 0, false)
        );

        RawAccountSnapshot snapshot = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", now, me, timeline, List.of(), List.of()
        );

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();
        CommunityDashboardReport report = engine.analyze(snapshot, 7, ZoneId.of("Asia/Shanghai"), false);

        AlgorithmHealthSummary ah = report.algorithmHealth();
        assertThat(ah).isNotNull();
        assertThat(ah.evaluatedPostCount()).isEqualTo(3);
        assertThat(ah.outOfNetworkPostCount()).isEqualTo(1);
        assertThat(ah.inNetworkPostCount()).isEqualTo(1);
        assertThat(ah.lowReachPostCount()).isEqualTo(1);
        assertThat(ah.rapidFirePostCount()).isEqualTo(1);
        assertThat(ah.externalLinkPostCount()).isEqualTo(1);
        assertThat(ah.linkViewLossPct()).isGreaterThan(80);
        assertThat(ah.underTheHoodUrl()).isEqualTo("https://x.com/i/jf/under_the_hood");

        Map<String, TopPostCard> cardById = report.contentTiming().topPosts().stream()
                .collect(java.util.stream.Collectors.toMap(TopPostCard::id, c -> c));

        TopPostCard viralCard = cardById.get("viral_clean");
        TopPostCard mediaCard = cardById.get("native_media");
        TopPostCard rapidExtCard = cardById.get("rapid_ext");

        assertThat(viralCard.reachTierCode()).isEqualTo("OON_VIRAL");
        assertThat(viralCard.hasExternalLink()).isFalse();
        assertThat(viralCard.rapidFirePenalty()).isFalse();
        assertThat(mediaCard.hasExternalLink()).isFalse();

        assertThat(rapidExtCard.hasExternalLink()).isTrue();
        assertThat(rapidExtCard.rapidFirePenalty()).isTrue();
        assertThat(viralCard.algoScore()).isGreaterThan(rapidExtCard.algoScore() + 35);
        assertThat(rapidExtCard.algoSignals()).anyMatch(s -> s.contains("25分（本地分-14）"));
        assertThat(rapidExtCard.algoSignals()).contains("🔗 正文含外链（本地分-8）");
    }

    @Test
    void shouldEnforceXLoginSelfOnlyAccessAndFixedThreeMinuteCache() throws Exception {
        // 1. 校验首页、/api/meta 与 /api/health
        ResponseEntity<String> indexResp = restTemplate.getForEntity("/", String.class);
        assertThat(indexResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(indexResp.getBody())
                .contains("登录 X 账号授权，查看您的专属运营报表")
                .contains("5 分钟")
                .contains("48小时动态窗口")
                .doesNotContain("settingsModal");

        ResponseEntity<ServerCapabilities> metaResp = restTemplate.getForEntity("/api/meta", ServerCapabilities.class);
        assertThat(metaResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metaResp.getBody()).isNotNull();
        assertThat(metaResp.getBody().requireLoginSelfOnly()).isTrue();
        assertThat(metaResp.getBody().cacheTtlSeconds()).isEqualTo(300L);

        ResponseEntity<Map> healthResp = restTemplate.getForEntity("/api/health", Map.class);
        assertThat(healthResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResp.getBody()).containsEntry("status", "UP");

        // 2. 未登录直接访问 /api/analyze、/api/analyze/bundle 或 /api/usage/checkin 必须被 401 拦截
        ResponseEntity<Map> unauthResp = restTemplate.getForEntity("/api/analyze?username=@elidlicom", Map.class);
        assertThat(unauthResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> unauthBundleResp = restTemplate.getForEntity("/api/analyze/bundle", Map.class);
        assertThat(unauthBundleResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> unauthCheckinResp = restTemplate.postForEntity("/api/usage/checkin", null, Map.class);
        assertThat(unauthCheckinResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> unauthFollowAuthorResp = restTemplate.postForEntity("/api/usage/follow-author", null, Map.class);
        assertThat(unauthFollowAuthorResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 3a. 验证防登录 CSRF（攻击者用自己会话生成的 state 发给无此会话的受害者浏览器，必须被拒绝）
        URI loginUri = URI.create(restTemplate.getRootUri() + "/api/auth/x/login");
        HttpURLConnection csrfLoginConn = (HttpURLConnection) loginUri.toURL().openConnection();
        csrfLoginConn.setInstanceFollowRedirects(false);
        csrfLoginConn.setRequestProperty("X-Forwarded-Host", "evil.example.com/../../attack");
        assertThat(csrfLoginConn.getResponseCode()).isEqualTo(302);
        String csrfAuthLoc = csrfLoginConn.getHeaderField("Location");
        // 恶意 X-Forwarded-Host 必须被忽略，redirect_uri 保持本机合法地址
        assertThat(URLDecoder.decode(csrfAuthLoc, StandardCharsets.UTF_8)).doesNotContain("evil.example.com");
        String csrfState = extractQueryParam(URI.create(csrfAuthLoc).getRawQuery(), "state");

        URI csrfCbUri = URI.create(restTemplate.getRootUri() + "/api/auth/x/callback?code=valid_code_123&state=" + csrfState);
        HttpURLConnection csrfCbConn = (HttpURLConnection) csrfCbUri.toURL().openConnection();
        csrfCbConn.setInstanceFollowRedirects(false);
        assertThat(csrfCbConn.getResponseCode()).isEqualTo(302);
        assertThat(csrfCbConn.getHeaderField("Location")).contains("auth_error=");

        // 3b. 正常同浏览器会话完成 OAuth 2.0 PKCE 登录与回调（携带预登录 Cookie 并验证会话轮换）
        HttpURLConnection loginConn = (HttpURLConnection) loginUri.toURL().openConnection();
        loginConn.setInstanceFollowRedirects(false);
        assertThat(loginConn.getResponseCode()).isEqualTo(302);
        String preLoginSetCookie = loginConn.getHeaderField("Set-Cookie");
        assertThat(preLoginSetCookie).contains("JSESSIONID=");
        String preLoginCookie = preLoginSetCookie.split(";", 2)[0];

        String authorizeLocation = loginConn.getHeaderField("Location");
        assertThat(authorizeLocation).startsWith("https://x.com/i/oauth2/authorize?");
        String state = extractQueryParam(URI.create(authorizeLocation).getRawQuery(), "state");

        URI callbackUri = URI.create(restTemplate.getRootUri() + "/api/auth/x/callback?code=valid_code_123&state=" + state);
        HttpURLConnection cbConn = (HttpURLConnection) callbackUri.toURL().openConnection();
        cbConn.setInstanceFollowRedirects(false);
        cbConn.setRequestProperty("Cookie", preLoginCookie);
        assertThat(cbConn.getResponseCode()).isEqualTo(302);
        assertThat(cbConn.getHeaderField("Location")).endsWith("/?auth=ok");
        String postAuthSetCookie = cbConn.getHeaderField("Set-Cookie");
        String sessionCookie = (postAuthSetCookie != null && postAuthSetCookie.contains("JSESSIONID="))
                ? postAuthSetCookie.split(";", 2)[0]
                : preLoginCookie;

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.add(HttpHeaders.COOKIE, sessionCookie);

        // 4. 已登录用户通过 /api/analyze/bundle 一次性获取同源三周期包，验证 fetchedAt 完全一致且 300 秒（5 分钟）服务端缓存生效
        ResponseEntity<MultiPeriodDashboardBundle> bundleResp = restTemplate.exchange(
                "/api/analyze/bundle",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                MultiPeriodDashboardBundle.class
        );
        assertThat(bundleResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bundleResp.getHeaders().getCacheControl()).contains("no-store");
        MultiPeriodDashboardBundle bundleBody = bundleResp.getBody();
        assertThat(bundleBody).isNotNull();
        CommunityDashboardReport firstBody = bundleBody.month();
        assertThat(firstBody.profile().username()).isEqualTo("ops_member");
        assertThat(firstBody.fromCache()).isFalse();
        assertThat(firstBody.cacheTtlSeconds()).isEqualTo(300L);
        assertThat(firstBody.cacheRemainingSeconds()).isBetween(1L, 300L);
        assertThat(bundleBody.month().periodLabel()).isEqualTo("30天动态窗口");
        assertThat(bundleBody.week().periodCode()).isEqualTo("WEEK");
        assertThat(bundleBody.week().periodLabel()).isEqualTo("7天动态窗口");
        assertThat(bundleBody.day().periodCode()).isEqualTo("HOURS_48");
        assertThat(bundleBody.day().periodLabel()).isEqualTo("48小时动态窗口");
        assertThat(bundleBody.day().windowDays()).isEqualTo(2);
        assertThat(bundleBody.week().fetchedAt()).isEqualTo(firstBody.fetchedAt());
        assertThat(bundleBody.day().fetchedAt()).isEqualTo(firstBody.fetchedAt());

        // 4b. 默认不传 period/days 时，必须返回「48小时动态窗口」（HOURS_48）并直接命中 5 分钟缓存
        ResponseEntity<CommunityDashboardReport> defaultPeriodResp = restTemplate.exchange(
                "/api/analyze",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                CommunityDashboardReport.class
        );
        assertThat(defaultPeriodResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(defaultPeriodResp.getBody()).isNotNull();
        assertThat(defaultPeriodResp.getBody().periodCode()).isEqualTo("HOURS_48");
        assertThat(defaultPeriodResp.getBody().periodLabel()).isEqualTo("48小时动态窗口");
        assertThat(defaultPeriodResp.getBody().fromCache()).isTrue();

        // 5. 5 分钟缓存期内，即使前端传 refresh=true 也强制走缓存
        ResponseEntity<CommunityDashboardReport> cachedWeekResp = restTemplate.exchange(
                "/api/analyze?period=WEEK&refresh=true",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                CommunityDashboardReport.class
        );
        assertThat(cachedWeekResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cachedWeekResp.getBody()).isNotNull();
        assertThat(cachedWeekResp.getBody().fromCache()).isTrue();
        assertThat(cachedWeekResp.getBody().periodCode()).isEqualTo("WEEK");

        // 6. 已登录 @elidlicom 尝试越权传参查询他人账号（如 @other_user）必须返回 403 FORBIDDEN
        ResponseEntity<Map> crossUserResp = restTemplate.exchange(
                "/api/analyze?username=@other_user&period=MONTH",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class
        );
        assertThat(crossUserResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 6b. 验证使用次数打卡：第 1、2 次静默；第 3 次弹出作者 @elidli 说明；未关注时第 10 次弹出关注提示；仅点击关注后第 20 次仍需真实关系确认
        for (int expected = 1; expected <= 20; expected++) {
            ResponseEntity<UsageCheckinResponse> checkinResp = restTemplate.exchange(
                    "/api/usage/checkin",
                    HttpMethod.POST,
                    new HttpEntity<>(authHeaders),
                    UsageCheckinResponse.class
            );
            assertThat(checkinResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            UsageCheckinResponse cBody = checkinResp.getBody();
            assertThat(cBody).isNotNull();
            assertThat(cBody.usageCount()).isEqualTo(expected);
            assertThat(cBody.authorProfile()).isNotNull();
            assertThat(cBody.authorProfile().username()).isEqualTo("elidlicom");
            assertThat(cBody.authorProfile().displayName()).isEqualTo("Litany（李丰一）");
            assertThat(cBody.authorProfile().avatarUrl()).isEqualTo("/author-avatar.webp");
            assertThat(cBody.authorFollowIntentUrl()).isEqualTo("https://x.com/intent/follow?screen_name=elidlicom");

            if (expected == 3) {
                assertThat(cBody.shouldShowAuthorModal()).isTrue();
                assertThat(cBody.modalReasonCode()).isEqualTo("THIRD_USE_INTRO");
                assertThat(cBody.modalTitle()).contains("@elidlicom");
                assertThat(cBody.modalMessage()).contains("真实付费调用 API").contains("结个善缘").contains("@elidlicom");
            } else if (expected == 10) {
                assertThat(cBody.shouldShowAuthorModal()).isTrue();
                assertThat(cBody.modalReasonCode()).isEqualTo("EVERY_10_REMINDER");
                assertThat(cBody.followingAuthor()).isFalse();
                assertThat(cBody.modalMessage()).contains("真实付费调用 API").contains("结个善缘").contains("@elidlicom");
                // 模拟用户在第 10 次弹窗点击「关注 @elidli」
                ResponseEntity<Map> followResp = restTemplate.exchange(
                        "/api/usage/follow-author",
                        HttpMethod.POST,
                        new HttpEntity<>(authHeaders),
                        Map.class
                );
                assertThat(followResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            } else if (expected == 20) {
                assertThat(cBody.followingAuthor()).isFalse();
                assertThat(cBody.shouldShowAuthorModal()).isTrue();
            } else {
                assertThat(cBody.shouldShowAuthorModal()).isFalse();
            }
        }

        // 7. 退出登录后会话立即失效，再次请求返回 401
        ResponseEntity<Map> logoutResp = restTemplate.exchange(
                "/api/auth/x/logout",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders),
                Map.class
        );
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> afterLogoutResp = restTemplate.exchange(
                "/api/analyze?period=MONTH",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class
        );
        assertThat(afterLogoutResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void twitterApiIoProviderShouldParseSingleDigitDatesSkipPinnedCutoffAndRetry429() throws Exception {
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String nowIso = Instant.now().minus(Duration.ofHours(3)).toString();
        String pinnedOldDate = "Wed May  6 09:15:00 +0000 2026";

        java.util.concurrent.atomic.AtomicInteger infoCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        mockUpstream.createContext("/twitter/user/info", exchange -> {
            if (infoCalls.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, """
                    {"status":"success","data":{"id":"77","userName":"real_op","name":"Real Operator","biography":"Ops bio","followers_count":1520,"following_count":210,"statusesCount":320,"isBlueVerified":true}}
                    """);
        });
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> {
            String q = exchange.getRequestURI().getQuery();
            if (q != null && q.contains("cursor=page2")) {
                writeJson(exchange, String.format("""
                        {"status":"success","data":{"tweets":[{"id":"tw2","text":"@peer_one nice thread!","isReply":true,"inReplyToUsername":"peer_one","createdAt":"%s","viewCount":300,"likeCount":8,"replyCount":1}]},"has_next_page":false,"next_cursor":""}
                        """, nowIso));
            } else {
                writeJson(exchange, String.format("""
                        {"status":"success","data":{"tweets":[{"id":"pin0","text":"Pinned intro","isPinned":true,"createdAt":"%s","viewCount":9000,"likeCount":100},{"id":"tw1","text":"Launching our new community tool 🧵","isReply":false,"createdAt":"%s","viewCount":4200,"likeCount":95,"replyCount":18,"retweetCount":12,"quoteCount":4,"bookmarkCount":30}]},"has_next_page":true,"next_cursor":"page2"}
                        """, pinnedOldDate, nowIso));
            }
        });
        mockUpstream.createContext("/twitter/user/mentions", exchange -> {
            writeJson(exchange, String.format("""
                    {"status":"success","tweets":[{"id":"m1","text":"Congrats @real_op!","isReply":true,"inReplyToUsername":"real_op","createdAt":"%s","author":{"id":"88","userName":"peer_one","name":"Peer One","followers_count":900}}],"has_next_page":false}
                    """, nowIso));
        });
        mockUpstream.createContext("/twitter/user/followings", exchange -> writeJson(exchange, """
                {"followings":[{"id":"88","userName":"peer_one","name":"Peer One","biography":"Builder","followers_count":900,"following_count":300}],"has_next_page":false}
                """));
        mockUpstream.createContext("/twitter/user/followers", exchange -> writeJson(exchange, """
                {"followers":[{"id":"88","userName":"peer_one","name":"Peer One","followers_count":900,"following_count":300}],"has_next_page":false}
                """));
        if (!advancedSearchMocked(mockUpstream)) {
            mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> writeJson(exchange, """
                    {"status":"success","tweets":[],"has_next_page":false,"next_cursor":""}
                    """));
        }
        mockUpstream.start();

        try {
            int port = mockUpstream.getAddress().getPort();
            XOpsProperties props = new XOpsProperties();
            props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            props.setTwitterapiIoKey("test_live_key_123456");

            TwitterApiIoProvider provider = new TwitterApiIoProvider(props, new ObjectMapper());
            RawAccountSnapshot snapshot = provider.fetchSnapshot("real_op", null);
            assertThat(snapshot.liveData()).isTrue();
            // 置顶的半年前旧推文超出 30 天保留期，不入库
            assertThat(snapshot.timeline()).extracting(RawTweet::id).containsExactlyInAnyOrder("tw1", "tw2");
        } finally {
            mockUpstream.stop(0);
        }
    }

    @Test
    void shouldHandleAuditEdgeCasesUrlBoundariesDayFirstHourAndEmojiSurrogates() {
        // 设为半点时刻（北京时间 20:30），验证滚动近 24 小时首桶折叠：[昨天20:30, 昨天21:00) 不会被 24 小时整点桶漏掉
        Instant now = Instant.parse("2026-11-12T12:30:00Z");
        XUserSummary me = new XUserSummary("1", "edge_tester", "🚀 Edge", "", "", true, 2000, 180, 400, "2023");
        XUserSummary emojiPeer = new XUserSummary("2", "emoji_peer", "🌟 Peer", "", "", false, 900, 200, 150, "2024");

        // 构造刚好在码点第 64 位附近包含 UTF-16 代理对（🚀 占 2 个 char）的长回复文本，验证截断绝不产生半个孤立代理字符
        String emojiLongReply = "这是一段用来验证Unicode代理对安全截断的社区走心评论内容测试文本刚好卡在六十四字边界位置附近🚀🚀🚀加油冲刺破圈";

        List<RawTweet> timeline = List.of(
                // 47 小时 50 分钟前（落在滚动 48h 窗口起点附近，早于第 1 个 2h 桶整点起点，验证首桶折叠不漏算）
                new RawTweet("day_edge_in", "原生配图紧跟中文标点 https://t.co/mediaPic99，今天重构完成了！",
                        now.minus(Duration.ofHours(47)).minus(Duration.ofMinutes(50)),
                        TweetKind.POST, null, null, List.of(), true, false, 1600, 50, 8, 4, 12, false),
                // 外部链接紧跟中文逗号（无空格）：必须精准识别为外链，不被中文吞并破坏协议匹配
                new RawTweet("cn_ext_url", "详细技术复盘见 https://blog.example.org/x-algo，欢迎大家拍砖交流？",
                        now.minus(Duration.ofHours(4)),
                        TweetKind.POST, null, null, List.of(), false, false, 300, 10, 2, 1, 2, false),
                new RawTweet("emoji_reply", emojiLongReply,
                        now.minus(Duration.ofHours(2)),
                        TweetKind.REPLY, "emoji_peer", emojiPeer, List.of("emoji_peer"), false, false, 90, 2, 0, 0, 0, false,
                        "emoji-peer-root", "emoji-peer-root")
        );

        RawAccountSnapshot snapshot = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", now, me, timeline, List.of(), List.of()
        );

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();
        CommunityDashboardReport dayReport = engine.analyze(snapshot, 2, ZoneId.of("Asia/Shanghai"), false);

        assertThat(dayReport.periodCode()).isEqualTo("HOURS_48");
        assertThat(dayReport.activity().originalPosts()).isEqualTo(2);
        int dayTrendPostsSum = dayReport.activity().trendSeries().stream().mapToInt(TrendBucketPoint::posts).sum();
        assertThat(dayTrendPostsSum).isEqualTo(2);

        Map<String, TopPostCard> topById = dayReport.contentTiming().topPosts().stream()
                .collect(java.util.stream.Collectors.toMap(TopPostCard::id, c -> c));
        // 单一官方 t.co 媒体链接 + 中文标点：不误判为外链
        assertThat(topById.get("day_edge_in").hasExternalLink()).isFalse();
        // 外部域名链接 + 中文标点：准确识别为外链
        assertThat(topById.get("cn_ext_url").hasExternalLink()).isTrue();

        // 验证 Emoji 摘要不含孤立高低代理项（Unpaired UTF-16 Surrogate）
        String snippet = dayReport.interactions().peerStats().get(0).recentSnippet();
        assertThat(snippet).isNotEmpty();
        for (int i = 0; i < snippet.length(); i++) {
            char ch = snippet.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertThat(i + 1).isLessThan(snippet.length());
                assertThat(Character.isLowSurrogate(snippet.charAt(i + 1))).isTrue();
                i++;
            } else {
                assertThat(Character.isLowSurrogate(ch)).isFalse();
            }
        }
    }

    @Test
    void shouldPersistCompactStoreAndOnlyFetchNewDataAfterRestart(@TempDir java.nio.file.Path tempCacheDir) throws Exception {
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String nowIso = Instant.now().minus(Duration.ofHours(2)).toString();

        java.util.concurrent.atomic.AtomicInteger infoCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger tweetsCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger mentionsCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger followingsCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger followersCalls = new java.util.concurrent.atomic.AtomicInteger(0);

        mockUpstream.createContext("/twitter/user/info", exchange -> {
            infoCalls.incrementAndGet();
            writeJson(exchange, """
                    {"status":"success","data":{"id":"77","userName":"real_op","name":"Real Operator","biography":"Ops","followers_count":1,"following_count":1,"statusesCount":2,"isBlueVerified":true}}
                    """);
        });
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> {
            tweetsCalls.incrementAndGet();
            writeJson(exchange, String.format("""
                    {"status":"success","data":{"tweets":[{"id":"tw1","text":"Shipping 48h window optimization","isReply":false,"createdAt":"%s","viewCount":1800,"likeCount":45,"replyCount":6,"retweetCount":4,"bookmarkCount":12}]},"has_next_page":false,"next_cursor":""}
                    """, nowIso));
        });
        mockUpstream.createContext("/twitter/user/mentions", exchange -> {
            mentionsCalls.incrementAndGet();
            writeJson(exchange, String.format("""
                    {"status":"success","tweets":[{"id":"m1","text":"Awesome @real_op!","isReply":true,"inReplyToUsername":"real_op","createdAt":"%s","author":{"id":"88","userName":"peer_one","name":"Peer One","followers_count":900}}],"has_next_page":false}
                    """, nowIso));
        });
        mockUpstream.createContext("/twitter/user/followings", exchange -> {
            followingsCalls.incrementAndGet();
            writeJson(exchange, """
                    {"followings":[{"id":"88","userName":"peer_one","name":"Peer One","followers_count":900,"following_count":300}],"has_next_page":false}
                    """);
        });
        mockUpstream.createContext("/twitter/user/followers", exchange -> {
            followersCalls.incrementAndGet();
            writeJson(exchange, """
                    {"followers":[{"id":"88","userName":"peer_one","name":"Peer One","followers_count":900,"following_count":300}],"has_next_page":false}
                    """);
        });
        java.util.concurrent.atomic.AtomicInteger searchCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        ADVANCED_SEARCH_CUSTOM.add(mockUpstream);
        mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> {
            searchCalls.incrementAndGet();
            writeJson(exchange, """
                    {"status":"success","tweets":[],"has_next_page":false,"next_cursor":""}
                    """);
        });
        if (!advancedSearchMocked(mockUpstream)) {
            mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> writeJson(exchange, """
                    {"status":"success","tweets":[],"has_next_page":false,"next_cursor":""}
                    """));
        }
        mockUpstream.start();

        try {
            int port = mockUpstream.getAddress().getPort();
            XOpsProperties props = new XOpsProperties();
            props.setDefaultProvider("TWITTERAPI_IO");
            props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            props.setTwitterapiIoKey("test_cost_opt_key_999");
            props.setCacheTtlSeconds(3600L);
            props.setDiskCacheDir(tempCacheDir.toString());

            DemoXDataProvider demoProvider = new DemoXDataProvider();
            TwitterApiIoProvider twitterProvider = new TwitterApiIoProvider(props, objectMapper);
            OfficialXApiV2Provider officialProvider = new OfficialXApiV2Provider(props, objectMapper);

            // 1. 首次冷启动同步：5 个子接口各调用 1 次，并自动落盘持久化快照
            CommunityHubService service1 = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            MultiPeriodDashboardBundle coldBundle = service1.analyzeAccountBundle("real_op", "TWITTERAPI_IO", false, "127.0.0.1");
            assertThat(coldBundle.day().fromCache()).isFalse();
            assertThat(coldBundle.day().periodCode()).isEqualTo("HOURS_48");
            assertThat(coldBundle.day().followAudit().mutualCount()).isEqualTo(1);
            assertThat(infoCalls.get()).isEqualTo(1);
            assertThat(tweetsCalls.get()).isEqualTo(1);
            assertThat(mentionsCalls.get()).isEqualTo(1);
            assertThat(followingsCalls.get()).isEqualTo(1);
            assertThat(followersCalls.get()).isEqualTo(1);

            // 首次：原生接口只给到 2 小时前就到底，剩余 48 小时用 1 次时间段搜索确认补齐（两类数据各 1 次）
            assertThat(searchCalls.get()).isEqualTo(2);

            java.nio.file.Path storeFile = tempCacheDir.resolve("users").resolve("real_op").resolve("store_v1.json.gz");
            assertThat(java.nio.file.Files.isRegularFile(storeFile)).isTrue();
            String storedJson;
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(storeFile))) {
                storedJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // 压缩存储：短字段名、不存冗余字段
            assertThat(storedJson).contains("\"tc\":").contains("\"ic\":").doesNotContain("viewCount").doesNotContain("relationshipType");

            // 2. 5 分钟内同一服务重复请求：直接命中服务端缓存，0 次上游请求
            MultiPeriodDashboardBundle cached = service1.analyzeAccountBundle("real_op", "TWITTERAPI_IO", true, "127.0.0.1");
            assertThat(cached.day().fromCache()).isTrue();
            assertThat(infoCalls.get() + tweetsCalls.get() + mentionsCalls.get() + searchCalls.get()).isEqualTo(5);

            // 3. 服务重启（内存缓存清空）：从持久化存储恢复，只刷新资料（1 次）；距上次同步不足 1 分钟、没有未入库的时间段，
            //    推文 / 互动 0 次请求；关注数 / 粉丝数未变，关注关系沿用库里的（0 次关系分页请求）
            CommunityHubService serviceAfterRestart = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            MultiPeriodDashboardBundle warmBundle = serviceAfterRestart.analyzeAccountBundle("real_op", "TWITTERAPI_IO", false, "127.0.0.1");
            assertThat(warmBundle.day().fromCache()).isFalse();
            assertThat(infoCalls.get()).isEqualTo(2);
            assertThat(tweetsCalls.get()).isEqualTo(1);
            assertThat(mentionsCalls.get()).isEqualTo(1);
            assertThat(searchCalls.get()).isEqualTo(2);
            assertThat(followingsCalls.get()).isEqualTo(1);
            assertThat(followersCalls.get()).isEqualTo(1);
            assertThat(warmBundle.day().followAudit().mutualCount()).isEqualTo(1);
            assertThat(warmBundle.day().activity().originalPosts()).isEqualTo(1);
            assertThat(warmBundle.day().bannerNotice()).contains("沿用已入库的关注关系");
        } finally {
            mockUpstream.stop(0);
        }
    }

    @Test
    void shouldStrictlyCountOnePostOnceAndIdentifyWhoOwesWhoAndRelationshipPortraits() {
        Instant now = Instant.parse("2026-11-12T12:00:00Z");
        XUserSummary me = new XUserSummary("1", "my_ops", "My Ops", "", "bio", true, 3600, 220, 600, "2023");
        XUserSummary mutualAlly = new XUserSummary("2", "ally_chen", "Chen", "", "ai", true, 4100, 310, 520, "2023");
        XUserSummary politeGhost = new XUserSummary("3", "ghost_pm", "Ghost PM", "", "pm", false, 1800, 260, 300, "2023");
        XUserSummary warmFanUnreplied = new XUserSummary("4", "warm_fan", "Warm Fan", "", "indie", false, 850, 420, 140, "2024");
        XUserSummary thirdOwner = new XUserSummary("5", "third_host", "Third Host", "", "host", true, 9000, 150, 900, "2022");

        List<RawTweet> timeline = List.of(
                // 10 天前发布的我方老主帖（用于验证 48h 窗口内收到跨周期老帖评论时，依然 100% 识别为我方主帖）
                new RawTweet("my_old_post_10d", "10d ago deep dive 🧵", now.minus(Duration.ofDays(10)), TweetKind.POST,
                        null, null, List.of(), false, true, 3200, 90, 12, 8, 25, false, "my_old_post_10d", ""),
                // 12 小时前发布的我方新主帖
                new RawTweet("my_new_post_1", "Shipping precise post-level interaction ledger", now.minus(Duration.ofHours(12)), TweetKind.POST,
                        null, null, List.of(), false, false, 1900, 64, 9, 5, 16, false, "my_new_post_1", ""),

                // 规则1验证：我去 @ally_chen 的同一个主帖 (chen_root_1) 连续评论/追评 3 条 -> 只能算 1 次！再去 chen_root_2 评 1 条 -> 合计 2 次
                new RawTweet("out_chen_1a", "第一条评论观点", now.minus(Duration.ofHours(10)), TweetKind.REPLY,
                        "ally_chen", mutualAlly, List.of("ally_chen"), false, false, 120, 4, 1, 0, 0, false, "chen_root_1", "chen_root_1"),
                new RawTweet("out_chen_1b", "同帖补充第二条盖楼", now.minus(Duration.ofHours(9)), TweetKind.REPLY,
                        "ally_chen", mutualAlly, List.of("ally_chen"), false, false, 80, 2, 0, 0, 0, false, "chen_root_1", "chen_reply_back_1"),
                new RawTweet("out_chen_1c", "同帖第三次回复", now.minus(Duration.ofHours(8)), TweetKind.REPLY,
                        "ally_chen", mutualAlly, List.of("ally_chen"), false, false, 60, 1, 0, 0, 0, false, "chen_root_1", "chen_reply_back_2"),
                new RawTweet("out_chen_2", "去他第二个独立帖子暖场", now.minus(Duration.ofHours(6)), TweetKind.REPLY,
                        "ally_chen", mutualAlly, List.of("ally_chen"), false, false, 110, 3, 0, 0, 0, false, "chen_root_2", "chen_root_2"),

                // 规则1反例验证：我在「自己的帖子 my_new_post_1」底下礼貌回复 @ally_chen 的评论 -> 绝不能算「我去他那边评论」！但应消除漏回状态
                new RawTweet("my_polite_in_own_post", "谢谢 Chen 兄捧场支持！", now.minus(Duration.ofHours(5)), TweetKind.REPLY,
                        "ally_chen", mutualAlly, List.of("ally_chen"), false, false, 50, 2, 0, 0, 0, false, "my_new_post_1", "in_chen_on_my_new_1a"),

                // 规则2反例准备：我去 @ghost_pm 的 2 个独立主帖评论
                new RawTweet("out_ghost_1", "赞同这个产品拆解", now.minus(Duration.ofHours(20)), TweetKind.REPLY,
                        "ghost_pm", politeGhost, List.of("ghost_pm"), false, false, 95, 2, 1, 0, 0, false, "ghost_root_1", "ghost_root_1"),
                new RawTweet("out_ghost_2", "第二篇也很有启发", now.minus(Duration.ofHours(15)), TweetKind.REPLY,
                        "ghost_pm", politeGhost, List.of("ghost_pm"), false, false, 85, 1, 1, 0, 0, false, "ghost_root_2", "ghost_root_2"),

                // 第三方帖主防串味验证：先去 @third_host 主帖评论，再在该帖内顺带回复 @ghost_pm -> 不算去 @ghost_pm 帖子评论
                new RawTweet("out_third_root", "Host 好帖", now.minus(Duration.ofHours(14)), TweetKind.REPLY,
                        "third_host", thirdOwner, List.of("third_host"), false, false, 70, 1, 0, 0, 0, false, "third_root_99", "third_root_99"),
                new RawTweet("out_ghost_in_third", "在别人帖子里顺便接 @ghost_pm 一句", now.minus(Duration.ofHours(13)), TweetKind.REPLY,
                        "ghost_pm", politeGhost, List.of("ghost_pm"), false, false, 40, 0, 0, 0, 0, false, "third_root_99", "ghost_sub_in_third")
        );

        List<InboundInteraction> inbound = List.of(
                // 规则2验证：@ally_chen 在我的同一个主帖 (my_new_post_1) 下连评 2 条 -> 只能算 1 次！再去我 10 天前老帖评 1 条 -> 合计 2 次
                new InboundInteraction("in_chen_on_my_new_1a", mutualAlly, now.minus(Duration.ofHours(5)).minus(Duration.ofMinutes(30)),
                        InboundKind.REPLY_TO_ME, "第一条走心长评！", "my_new_post_1", "my_new_post_1", "my_ops"),
                new InboundInteraction("in_chen_on_my_new_1b", mutualAlly, now.minus(Duration.ofHours(4)),
                        InboundKind.REPLY_TO_ME, "同帖再追评一句细节", "my_new_post_1", "my_polite_in_own_post", "my_ops"),
                new InboundInteraction("in_chen_on_my_old", mutualAlly, now.minus(Duration.ofHours(3)),
                        InboundKind.REPLY_TO_ME, "考古你10天前这篇干货太顶了", "my_old_post_10d", "my_old_post_10d", "my_ops"),

                // 规则2关键防虚增验证：@ghost_pm 在「他自己的帖子 ghost_root_1 / ghost_root_2」下礼貌回我 "Thanks!" -> 绝不算他来评我帖（记为 0）！
                new InboundInteraction("in_ghost_own_thread_1", politeGhost, now.minus(Duration.ofHours(19)),
                        InboundKind.REPLY_TO_ME, "Thanks for reading @my_ops!", "ghost_root_1", "out_ghost_1", "my_ops"),
                new InboundInteraction("in_ghost_own_thread_2", politeGhost, now.minus(Duration.ofHours(14)),
                        InboundKind.REPLY_TO_ME, "Glad you liked it!", "ghost_root_2", "out_ghost_2", "my_ops"),

                // 「我没回谁」精准捕获验证：@warm_fan 2 小时前来评了我 10 天前的老帖 + 新帖，我既未在帖内回复，也未去他主页回访
                new InboundInteraction("in_fan_unreplied_new", warmFanUnreplied, now.minus(Duration.ofHours(2)),
                        InboundKind.REPLY_TO_ME, "请问这个指标是怎么算的呀？求翻牌", "my_new_post_1", "my_new_post_1", "my_ops"),
                new InboundInteraction("in_fan_unreplied_old", warmFanUnreplied, now.minus(Duration.ofHours(1)),
                        InboundKind.REPLY_TO_ME, "这篇复盘我也反复看了两遍！", "my_old_post_10d", "my_old_post_10d", "my_ops")
        );

        List<RelationshipEntry> relationships = List.of(
                new RelationshipEntry(mutualAlly, true, true),
                new RelationshipEntry(politeGhost, true, true),
                new RelationshipEntry(warmFanUnreplied, false, true),
                new RelationshipEntry(thirdOwner, true, false)
        );

        RawAccountSnapshot snapshot = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", now, me, timeline, inbound, relationships
        );

        CommunityAnalyticsEngine engine = new CommunityAnalyticsEngine();
        // 在默认 48 小时动态窗口下检验跨周期根帖识别与「1帖=1次」精准度
        CommunityDashboardReport report = engine.analyze(snapshot, 2, ZoneId.of("Asia/Shanghai"), false);
        InteractionOverview inter = report.interactions();

        Map<String, PeerInteractionStat> byHandle = inter.peerStats().stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.peer().username(), p -> p));

        // 1. @ally_chen：我去评 2 帖（原始 4 条跨帖+1条我帖内回评全剥离），他来评 2 帖（原始 3 条合并为 2 帖；my_old_post_10d 漏回 1 帖）
        PeerInteractionStat chen = byHandle.get("ally_chen");
        assertThat(chen.myRepliesToPeer()).isEqualTo(2);
        assertThat(chen.myRawCommentCount()).isEqualTo(4);
        assertThat(chen.myRepliesInMyOwnPost()).isEqualTo(1);
        assertThat(chen.peerRepliesToMe()).isEqualTo(2);
        assertThat(chen.peerRawCommentCount()).isEqualTo(3);
        assertThat(chen.replyRatioLabel()).isEqualTo("2 : 2");
        // my_new_post_1 我已帖内回过他（不算漏回），但 my_old_post_10d 他刚评了我还没回 -> 精准识别 1 帖评论待回复；同时因双方都去评过对方推文(2:2)，严格归入「互相评论 (MUTUAL)」而非「我没回谁」
        assertThat(chen.unrepliedInMyPostCount()).isEqualTo(1);
        assertThat(chen.latestUnrepliedTweetUrl()).isEqualTo("https://x.com/ally_chen/status/in_chen_on_my_old");
        assertThat(chen.reciprocityCode()).isEqualTo("MUTUAL");
        assertThat(chen.relationshipType()).isEqualTo("🤝 互相评论·评论待回");
        assertThat(chen.relationshipPortrait()).contains("互关好友").contains("互相评论").contains("尚未在评论区回复 TA").contains("已去评 TA 2 帖");

        // 2. @ghost_pm：我去评他 2 个独立帖（第三方帖内接话已剥离），他在自己帖内回我 2 条绝不算来评我帖（2 : 0 -> 谁没回我 THEY_OWE）
        PeerInteractionStat ghost = byHandle.get("ghost_pm");
        assertThat(ghost.myRepliesToPeer()).isEqualTo(2);
        assertThat(ghost.peerRepliesToMe()).isEqualTo(0);
        assertThat(ghost.peerInThreadRepliesOnTheirPost()).isEqualTo(2);
        assertThat(ghost.replyRatioLabel()).isEqualTo("2 : 0");
        assertThat(ghost.reciprocityCode()).isEqualTo("THEY_OWE");
        assertThat(ghost.relationshipType()).isEqualTo("👀 TA 没来评我帖");
        assertThat(ghost.relationshipPortrait()).contains("互关好友").contains("TA 没来评我帖").contains("仅在 TA 帖内礼貌接话 2 帖");

        // 3. @warm_fan：未回关粉丝来评我 2 个主帖（含 10 天前老帖），我去评 0 帖且帖内漏回 2 帖 -> 精准落入「我没回谁 (I_OWE)」与「评论待回复」
        PeerInteractionStat fan = byHandle.get("warm_fan");
        assertThat(fan.myRepliesToPeer()).isEqualTo(0);
        assertThat(fan.peerRepliesToMe()).isEqualTo(2);
        assertThat(fan.unrepliedInMyPostCount()).isEqualTo(2);
        assertThat(fan.latestUnrepliedTweetUrl()).isEqualTo("https://x.com/warm_fan/status/in_fan_unreplied_old");
        assertThat(fan.reciprocityCode()).isEqualTo("I_OWE");
        assertThat(fan.relationshipType()).isEqualTo("🙋‍♂️ 没去评TA·评论待回");
        assertThat(fan.relationshipPortrait()).contains("关注我的粉丝(未回关)").contains("TA 来评我 2 帖").contains("尚未去评 TA 主帖");

        // 4. 四维分类汇总核验：
        // - 我没回谁 (inboundHeavyPeers)：仅 warm_fan 1 人（peer>0 && my==0）
        // - 评论待回复 (unrepliedMyPostPeers)：warm_fan + ally_chen 共 2 人（unrepliedInMyPostCount > 0）
        // - 谁没回我 (outboundHeavyPeers)：ghost_pm + third_host 共 2 人（my>0 && peer==0）
        // - 互相评论 (balancedMutualPeers)：ally_chen 1 人（my>0 && peer>0）
        assertThat(inter.inboundHeavyPeers()).isEqualTo(1);
        assertThat(inter.unrepliedMyPostPeers()).isEqualTo(2);
        assertThat(inter.outboundHeavyPeers()).isEqualTo(2);
        assertThat(inter.balancedMutualPeers()).isEqualTo(1);
        // 只统计真正的同帖重复：我在 chen_root_1 追评 2 条 + chen 在 my_new_post_1 追评 1 条 = 3；自帖回评、第三方帖接话、对方帖内回我均不计入
        assertThat(inter.filteredSamePostReplies()).isEqualTo(3);
    }

    @Test
    void shouldPersistUsageStatsAndRespectRealFollowGraphForAuthorPrompts(@TempDir java.nio.file.Path tempDir) throws Exception {
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicBoolean followsElidliInGraph = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<Instant> snapshotClock = new java.util.concurrent.atomic.AtomicReference<>(Instant.now().minus(Duration.ofMinutes(30)));

        mockUpstream.createContext("/twitter/user/info", exchange -> writeJson(exchange, String.format("""
                {"status":"success","data":{"id":"501","userName":"fan_builder","name":"Fan Builder","biography":"Indie","followers_count":10,"following_count":%d,"statusesCount":5,"isBlueVerified":false}}
                """, followsElidliInGraph.get() ? 2 : 1)));
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> writeJson(exchange, String.format("""
                {"status":"success","data":{"tweets":[{"id":"t1","text":"Hello X","isReply":false,"createdAt":"%s","viewCount":200,"likeCount":5,"replyCount":1,"retweetCount":0,"bookmarkCount":0}]},"has_next_page":false}
                """, snapshotClock.get().toString())));
        mockUpstream.createContext("/twitter/user/mentions", exchange -> writeJson(exchange, """
                {"status":"success","tweets":[],"has_next_page":false}
                """));
        mockUpstream.createContext("/twitter/user/followings", exchange -> {
            if (followsElidliInGraph.get()) {
                writeJson(exchange, """
                        {"followings":[{"id":"999","userName":"elidlicom","name":"Litany（李丰一）","biography":"Java Dev | Studying Chinese Metaphysics(Dao·道)","followers_count":6800,"following_count":320,"statusesCount":1200,"isBlueVerified":true}],"has_next_page":false}
                        """);
            } else {
                writeJson(exchange, """
                        {"followings":[{"id":"888","userName":"other_peer","name":"Other Peer","followers_count":500,"following_count":100}],"has_next_page":false}
                        """);
            }
        });
        mockUpstream.createContext("/twitter/user/followers", exchange -> writeJson(exchange, """
                {"followers":[{"id":"888","userName":"other_peer","name":"Other Peer","followers_count":500,"following_count":100}],"has_next_page":false}
                """));
        if (!advancedSearchMocked(mockUpstream)) {
            mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> writeJson(exchange, """
                    {"status":"success","tweets":[],"has_next_page":false,"next_cursor":""}
                    """));
        }
        mockUpstream.start();

        try {
            int port = mockUpstream.getAddress().getPort();
            XOpsProperties props = new XOpsProperties();
            props.setDefaultProvider("TWITTERAPI_IO");
            props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            props.setTwitterapiIoKey("test_author_graph_key");
            props.setCacheTtlSeconds(3600L);
            props.setDiskCacheDir(tempDir.toString());

            DemoXDataProvider demoProvider = new DemoXDataProvider();
            TwitterApiIoProvider twitterProvider = new TwitterApiIoProvider(props, objectMapper);
            OfficialXApiV2Provider officialProvider = new OfficialXApiV2Provider(props, objectMapper);

            // 1. 用户已真实关注 @elidlicom：第 3 次仍展示 1 次作者说明（带已关注状态与作者真实资料及内置高清头像），第 10 次因已关注绝不打扰
            CommunityHubService service1 = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            service1.analyzeAccountBundle("fan_builder", "TWITTERAPI_IO", false, "127.0.0.1");

            assertThat(service1.recordUsageCheckin("fan_builder").shouldShowAuthorModal()).isFalse(); // 1
            assertThat(service1.recordUsageCheckin("fan_builder").shouldShowAuthorModal()).isFalse(); // 2
            UsageCheckinResponse thirdUse = service1.recordUsageCheckin("fan_builder"); // 3
            assertThat(thirdUse.shouldShowAuthorModal()).isTrue();
            assertThat(thirdUse.modalReasonCode()).isEqualTo("THIRD_USE_INTRO");
            assertThat(thirdUse.followingAuthor()).isTrue();
            assertThat(thirdUse.authorProfile().username()).isEqualTo("elidlicom");
            assertThat(thirdUse.authorProfile().displayName()).isEqualTo("Litany（李丰一）");
            assertThat(thirdUse.authorProfile().avatarUrl()).isEqualTo("/author-avatar.webp");

            // 2. 模拟服务重启：计数从磁盘 usage_stats_v1.json 无缝延续，第 10 次因真实关注 @elidlicom 保持静默
            CommunityHubService serviceAfterRestart = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            for (int i = 4; i <= 10; i++) {
                UsageCheckinResponse r = serviceAfterRestart.recordUsageCheckin("fan_builder");
                assertThat(r.usageCount()).isEqualTo(i);
                assertThat(r.shouldShowAuthorModal()).isFalse();
                assertThat(r.followingAuthor()).isTrue();
            }

            // 3. 若 1 小时缓存到期后拉取的新快照发现用户未关注 @elidlicom（例如取关了，following_count 2 -> 1），逢 20 次会再次精准弹出关注提示
            followsElidliInGraph.set(false);
            java.nio.file.Files.deleteIfExists(tempDir.resolve("users").resolve("fan_builder").resolve("store_v1.json.gz"));
            CommunityHubService serviceAfterExpiry = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            serviceAfterExpiry.analyzeAccountBundle("fan_builder", "TWITTERAPI_IO", false, "127.0.0.1");

            for (int i = 11; i <= 20; i++) {
                UsageCheckinResponse r = serviceAfterExpiry.recordUsageCheckin("fan_builder");
                assertThat(r.usageCount()).isEqualTo(i);
                if (i == 20) {
                    assertThat(r.followingAuthor()).isFalse();
                    assertThat(r.shouldShowAuthorModal()).isTrue();
                    assertThat(r.modalReasonCode()).isEqualTo("EVERY_10_REMINDER");
                } else {
                    assertThat(r.shouldShowAuthorModal()).isFalse();
                }
            }

            // 4. 作者本人账号 @elidlicom 登录使用：逢 10 次永不向自己弹求关注提示
            for (int i = 1; i <= 10; i++) {
                UsageCheckinResponse selfRes = serviceAfterExpiry.recordUsageCheckin("elidlicom");
                if (i == 10) {
                    assertThat(selfRes.selfIsAuthor()).isTrue();
                    assertThat(selfRes.shouldShowAuthorModal()).isFalse();
                }
            }
        } finally {
            mockUpstream.stop(0);
        }
    }

    @Test
    void shouldProgressivelyExpandFrom48hTo7dAnd30dWithoutDuplicatePageLoads(@TempDir java.nio.file.Path tempDir) throws Exception {
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ADVANCED_SEARCH_CUSTOM.add(mockUpstream);
        Instant baseNow = Instant.now();

        java.util.concurrent.atomic.AtomicInteger infoCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger followingsCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger tweetsNativeCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger mentionsNativeCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<String> searchRanges = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        mockUpstream.createContext("/twitter/user/info", exchange -> {
            infoCalls.incrementAndGet();
            writeJson(exchange, """
                    {"status":"success","data":{"id":"901","userName":"prog_user","name":"Progressive User","biography":"48h->7d->30d","followers_count":1,"following_count":1,"statusesCount":120,"isBlueVerified":true}}
                    """);
        });
        mockUpstream.createContext("/twitter/user/followings", exchange -> {
            followingsCalls.incrementAndGet();
            writeJson(exchange, """
                    {"followings":[{"id":"88","userName":"peer_48h","name":"Peer 48h","followers_count":900,"following_count":300}],"has_next_page":false}
                    """);
        });
        mockUpstream.createContext("/twitter/user/followers", exchange -> writeJson(exchange, """
                {"followers":[{"id":"88","userName":"peer_48h","name":"Peer 48h","followers_count":900,"following_count":300}],"has_next_page":false}
                """));
        // 原生接口首页：已越过 48 小时（-52h），第 2 页永远不应被请求
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> {
            tweetsNativeCalls.incrementAndGet();
            writeJson(exchange, String.format("""
                    {"status":"success","data":{"tweets":[
                      {"id":"tw_h6","conversationId":"tw_h6","text":"48h post","isReply":false,"createdAt":"%s","viewCount":1000,"likeCount":20},
                      {"id":"tw_h52","conversationId":"tw_h52","text":"52h edge post","isReply":false,"createdAt":"%s","viewCount":1500,"likeCount":30}
                    ]},"has_next_page":true,"next_cursor":"never_used"}
                    """, baseNow.minus(Duration.ofHours(6)), baseNow.minus(Duration.ofHours(52))));
        });
        mockUpstream.createContext("/twitter/user/mentions", exchange -> {
            mentionsNativeCalls.incrementAndGet();
            writeJson(exchange, String.format("""
                    {"status":"success","tweets":[
                      {"id":"m_h5","conversationId":"tw_h6","inReplyToId":"tw_h6","text":"Great 48h post","isReply":true,"inReplyToUsername":"prog_user","createdAt":"%s","author":{"id":"88","userName":"peer_48h","name":"Peer 48h","followers_count":900}},
                      {"id":"m_h54","conversationId":"tw_h52","inReplyToId":"tw_h52","text":"Great 52h post","isReply":true,"inReplyToUsername":"prog_user","createdAt":"%s","author":{"id":"202","userName":"peer_7d_a","name":"Peer 7d A","followers_count":500}}
                    ],"has_next_page":true,"next_cursor":"never_used"}
                    """, baseNow.minus(Duration.ofHours(5)), baseNow.minus(Duration.ofHours(54))));
        });
        // 更早的历史只能按时间段搜索：返回 [since, until) 内的数据
        String[][] olderTweets = {
                {"tw_d5", "5"}, {"tw_d9", "9"}, {"tw_d20", "20"}, {"tw_d33", "33"}
        };
        String[][] olderMentions = {
                {"m_d4", "4", "tw_d5", "peer_7d_b"}, {"m_d10", "10", "tw_d9", "peer_30d_a"}, {"m_d18", "18", "tw_d20", "peer_30d_b"}
        };
        mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> {
            String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            long since = Long.parseLong(q.replaceAll("(?s).*since_time:(\\d+).*", "$1"));
            long until = Long.parseLong(q.replaceAll("(?s).*until_time:(\\d+).*", "$1"));
            boolean fromMe = q.contains("query=from:prog_user");
            searchRanges.add((fromMe ? "T" : "M") + ":" + Math.round((baseNow.getEpochSecond() - until) / 3600.0) + "h");
            StringBuilder arr = new StringBuilder();
            if (fromMe) {
                for (String[] t : olderTweets) {
                    Instant ts = baseNow.minus(Duration.ofDays(Long.parseLong(t[1])));
                    if (ts.getEpochSecond() >= since && ts.getEpochSecond() < until) {
                        if (arr.length() > 0) arr.append(',');
                        arr.append(String.format("{\"id\":\"%s\",\"conversationId\":\"%s\",\"text\":\"old post\",\"isReply\":false,\"createdAt\":\"%s\",\"viewCount\":2000,\"likeCount\":40}", t[0], t[0], ts));
                    }
                }
            } else {
                for (String[] m : olderMentions) {
                    Instant ts = baseNow.minus(Duration.ofDays(Long.parseLong(m[1])));
                    if (ts.getEpochSecond() >= since && ts.getEpochSecond() < until) {
                        if (arr.length() > 0) arr.append(',');
                        arr.append(String.format("{\"id\":\"%s\",\"conversationId\":\"%s\",\"inReplyToId\":\"%s\",\"isReply\":true,\"inReplyToUsername\":\"prog_user\",\"text\":\"nice\",\"createdAt\":\"%s\",\"author\":{\"id\":\"9\",\"userName\":\"%s\",\"name\":\"%s\",\"followers_count\":600}}", m[0], m[2], m[2], ts, m[3], m[3]));
                    }
                }
            }
            writeJson(exchange, "{\"status\":\"success\",\"tweets\":[" + arr + "],\"has_next_page\":false,\"next_cursor\":\"\"}");
        });
        mockUpstream.start();

        try {
            int port = mockUpstream.getAddress().getPort();
            XOpsProperties props = new XOpsProperties();
            props.setDefaultProvider("TWITTERAPI_IO");
            props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            props.setTwitterapiIoKey("test_prog_window_key");
            props.setCacheTtlSeconds(300L);
            props.setDiskCacheDir(tempDir.toString());

            DemoXDataProvider demoProvider = new DemoXDataProvider();
            TwitterApiIoProvider twitterProvider = new TwitterApiIoProvider(props, objectMapper);
            OfficialXApiV2Provider officialProvider = new OfficialXApiV2Provider(props, objectMapper);

            // 1. 默认 48 小时：原生首页已越过 48 小时，不翻第 2 页、不做任何时间段搜索
            CommunityHubService service1 = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            MultiPeriodDashboardBundle b2 = service1.analyzeAccountBundle("prog_user", 2, "TWITTERAPI_IO", false, "127.0.0.1");
            assertThat(b2.coveredWindowDays()).isEqualTo(2);
            assertThat(b2.day().activity().originalPosts()).isEqualTo(1);
            assertThat(tweetsNativeCalls.get()).isEqualTo(1);
            assertThat(mentionsNativeCalls.get()).isEqualTo(1);
            assertThat(searchRanges).isEmpty();

            // 2. 切到 7 天：仅补未覆盖数据；扩窗新增一次资料核验，防止用户名换主人，关注图谱不重复请求。
            MultiPeriodDashboardBundle b7 = service1.analyzeAccountBundle("prog_user", 7, "TWITTERAPI_IO", false, "127.0.0.1");
            assertThat(b7.coveredWindowDays()).isEqualTo(7);
            assertThat(searchRanges).containsExactlyInAnyOrder("T:49h", "M:49h");
            assertThat(tweetsNativeCalls.get()).isEqualTo(1);
            assertThat(mentionsNativeCalls.get()).isEqualTo(1);
            assertThat(infoCalls.get()).isEqualTo(2);
            assertThat(followingsCalls.get()).isEqualTo(1);
            assertThat(b7.week().activity().originalPosts()).isEqualTo(3);
            assertThat(b7.week().interactions().peerStats()).hasSize(3);

            // 3. 切回 48 小时：已覆盖，0 次请求
            assertThat(service1.analyzeAccountBundle("prog_user", 2, "TWITTERAPI_IO", false, "127.0.0.1").day().fromCache()).isTrue();
            assertThat(searchRanges).hasSize(2);

            // 4. 服务重启后切 30 天：只搜索「7 天之前 → 30 天」；刚同步过的最新部分与关注关系都不重复请求
            CommunityHubService serviceAfterRestart = new CommunityHubService(props, demoProvider, twitterProvider, officialProvider, objectMapper);
            MultiPeriodDashboardBundle b30 = serviceAfterRestart.analyzeAccountBundle("prog_user", 30, "TWITTERAPI_IO", false, "127.0.0.1");
            assertThat(b30.coveredWindowDays()).isEqualTo(30);
            assertThat(searchRanges).containsExactlyInAnyOrder("T:49h", "M:49h", "T:169h", "M:169h");
            assertThat(tweetsNativeCalls.get()).isEqualTo(1);
            assertThat(mentionsNativeCalls.get()).isEqualTo(1);
            assertThat(infoCalls.get()).isEqualTo(3);
            assertThat(followingsCalls.get()).isEqualTo(1);

            // 三个时间窗包含关系，数据单调递增（33 天前的数据超出 30 天，不入库）
            assertThat(b30.day().activity().originalPosts()).isEqualTo(1);
            assertThat(b30.week().activity().originalPosts()).isEqualTo(3);
            assertThat(b30.month().activity().originalPosts()).isEqualTo(5);
            assertThat(b30.day().interactions().peerStats()).hasSize(1);
            assertThat(b30.week().interactions().peerStats()).hasSize(3);
            assertThat(b30.month().interactions().peerStats()).hasSize(5);

            // 5. 30 天覆盖后，任意来回切换 0 次请求
            serviceAfterRestart.analyzeAccountBundle("prog_user", 30, "TWITTERAPI_IO", false, "127.0.0.1");
            serviceAfterRestart.analyzeAccountBundle("prog_user", 7, "TWITTERAPI_IO", false, "127.0.0.1");
            serviceAfterRestart.analyzeAccountBundle("prog_user", 2, "TWITTERAPI_IO", false, "127.0.0.1");
            assertThat(searchRanges).hasSize(4);
            assertThat(tweetsNativeCalls.get() + mentionsNativeCalls.get()).isEqualTo(2);
        } finally {
            mockUpstream.stop(0);
        }
    }

    @Test
    void accountStoreShouldDropDataOlderThan30DaysAndTrackMissingRanges() {
        Instant now = Instant.parse("2026-11-13T06:00:00Z");
        XUserSummary me = new XUserSummary("1", "me_x", "Me", "", "", true, 100, 100, 1000, "2022");
        XUserSummary peer = new XUserSummary("2", "Peer_X", "Peer", "https://pbs.twimg.com/a.jpg", "bio", false, 500, 80, 0, "");
        List<RawTweet> tweets = List.of(
                new RawTweet("fresh", "post", now.minus(Duration.ofDays(2)), TweetKind.POST, null, null, List.of(),
                        true, false, 900, 10, 2, 1, 3, false, "fresh", ""),
                new RawTweet("old", "post", now.minus(Duration.ofDays(31)), TweetKind.POST, null, null, List.of(),
                        false, false, 100, 1, 0, 0, 0, false, "old", ""),
                new RawTweet("rep", "回复".repeat(200), now.minus(Duration.ofHours(3)), TweetKind.REPLY, "peer_x", peer,
                        List.of("peer_x"), false, false, 10, 0, 0, 0, 0, false, "root9", "root9")
        );
        AccountStore.StoredAccount st = AccountStore.merge(null, me, now, tweets, List.of(),
                List.of(new long[]{now.minus(Duration.ofDays(40)).getEpochSecond(), now.getEpochSecond()}), List.of(), null);
        // 31 天前的推文与区间被裁掉；回复正文只保留前 120 字
        assertThat(st.tweets()).extracting(AccountStore.StoredTweet::id).containsExactly("rep", "fresh");
        assertThat(st.tweetCoverage().get(0)[0]).isEqualTo(now.minus(AccountStore.RETENTION).getEpochSecond());
        assertThat(st.tweets().get(0).text().codePointCount(0, st.tweets().get(0).text().length())).isEqualTo(120);
        assertThat(st.users()).containsOnlyKeys("peer_x");

        // 还原后字段完整：指标、会话、推友资料
        RawAccountSnapshot snap = AccountStore.toSnapshot(st, "", false, 30);
        RawTweet fresh = snap.timeline().stream().filter(t -> t.id().equals("fresh")).findFirst().orElseThrow();
        assertThat(fresh.viewCount()).isEqualTo(900);
        assertThat(fresh.bookmarkCount()).isEqualTo(3);
        assertThat(fresh.hasMedia()).isTrue();
        assertThat(fresh.conversationId()).isEqualTo("fresh");
        RawTweet rep = snap.timeline().stream().filter(t -> t.id().equals("rep")).findFirst().orElseThrow();
        assertThat(rep.targetUser().followersCount()).isEqualTo(500);
        assertThat(rep.conversationId()).isEqualTo("root9");

        // 已入库 [now-48h, now]，求近 7 天缺口 -> 只缺 [now-7d, now-48h]
        List<long[]> gaps = AccountStore.missingRanges(
                List.of(new long[]{now.minus(Duration.ofHours(48)).getEpochSecond(), now.getEpochSecond()}),
                now.minus(Duration.ofDays(7)).getEpochSecond(), now.getEpochSecond());
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0)).containsExactly(now.minus(Duration.ofDays(7)).getEpochSecond(), now.minus(Duration.ofHours(48)).getEpochSecond());
    }

    private static final java.util.Set<HttpServer> ADVANCED_SEARCH_CUSTOM = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static boolean advancedSearchMocked(HttpServer server) {
        return ADVANCED_SEARCH_CUSTOM.contains(server);
    }

    @Test
    void shouldContinueByTimeSlicesWhenUpstreamStopsPagingBeforeWindowIsCovered(@TempDir java.nio.file.Path tempDir) throws Exception {
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ADVANCED_SEARCH_CUSTOM.add(mockUpstream);
        Instant baseNow = Instant.now();
        java.util.List<String> advancedQueries = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        mockUpstream.createContext("/twitter/user/info", exchange -> writeJson(exchange, """
                {"status":"success","data":{"id":"777","userName":"heavy_user","name":"Heavy","followers_count":1,"following_count":1,"statusesCount":99999,"isBlueVerified":false}}
                """));
        mockUpstream.createContext("/twitter/user/followings", exchange -> writeJson(exchange, """
                {"followings":[{"id":"88","userName":"peer_a","name":"Peer A","followers_count":900,"following_count":300}],"has_next_page":false}
                """));
        mockUpstream.createContext("/twitter/user/followers", exchange -> writeJson(exchange, """
                {"followers":[{"id":"88","userName":"peer_a","name":"Peer A","followers_count":900,"following_count":300}],"has_next_page":false}
                """));
        // 原生时间线 / 提及接口：只给 1 页（10 小时内）就不再提供下一页 —— 旧逻辑会误判为「30 天已取完」
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> writeJson(exchange, String.format("""
                {"status":"success","data":{"tweets":[
                  {"id":"t_h2","conversationId":"t_h2","text":"fresh post","isReply":false,"createdAt":"%s","viewCount":100,"likeCount":3},
                  {"id":"t_h10","conversationId":"peer_root_1","inReplyToId":"peer_root_1","inReplyToUsername":"peer_a","isReply":true,"text":"nice","createdAt":"%s","viewCount":10}
                ]},"has_next_page":false,"next_cursor":""}
                """, baseNow.minus(Duration.ofHours(2)), baseNow.minus(Duration.ofHours(10)))));
        mockUpstream.createContext("/twitter/user/mentions", exchange -> writeJson(exchange, String.format("""
                {"status":"success","tweets":[
                  {"id":"m_h3","conversationId":"t_h2","inReplyToId":"t_h2","inReplyToUsername":"heavy_user","isReply":true,"text":"great","createdAt":"%s","author":{"id":"88","userName":"peer_a","name":"Peer A","followers_count":900}}
                ],"has_next_page":false}
                """, baseNow.minus(Duration.ofHours(3)))));
        // 高级搜索按时间分片：from: 返回 30 小时前的我的动态；to:/@ 返回 40 小时前别人对我的评论；再往前查不到 -> 真实取完
        mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> {
            String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            advancedQueries.add(q);
            long until = Long.parseLong(q.replaceAll("(?s).*until_time:(\\d+).*", "$1"));
            boolean fromMe = q.contains("query=from:heavy_user");
            Instant slicePoint = baseNow.minus(Duration.ofHours(fromMe ? 30 : 40));
            if (until > slicePoint.getEpochSecond()) {
                if (fromMe) {
                    writeJson(exchange, String.format("""
                            {"status":"success","tweets":[{"id":"t_h30","conversationId":"t_h30","text":"older post","isReply":false,"createdAt":"%s","viewCount":50,"likeCount":1}],"has_next_page":false,"next_cursor":""}
                            """, slicePoint));
                } else {
                    writeJson(exchange, String.format("""
                            {"status":"success","tweets":[{"id":"m_h40","conversationId":"t_h30","inReplyToId":"t_h30","inReplyToUsername":"heavy_user","isReply":true,"text":"older comment","createdAt":"%s","author":{"id":"89","userName":"peer_b","name":"Peer B","followers_count":500}}],"has_next_page":false,"next_cursor":""}
                            """, slicePoint));
                }
            } else {
                writeJson(exchange, """
                        {"status":"success","tweets":[],"has_next_page":false,"next_cursor":""}
                        """);
            }
        });
        mockUpstream.start();

        try {
            int port = mockUpstream.getAddress().getPort();
            XOpsProperties props = new XOpsProperties();
            props.setDefaultProvider("TWITTERAPI_IO");
            props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            props.setTwitterapiIoKey("test_slice_key");
            props.setCacheTtlSeconds(3600L);
            props.setDiskCacheDir(tempDir.toString());
            CommunityHubService service = new CommunityHubService(props, new DemoXDataProvider(),
                    new TwitterApiIoProvider(props, objectMapper), new OfficialXApiV2Provider(props, objectMapper), objectMapper);

            MultiPeriodDashboardBundle b = service.analyzeAccountBundle("heavy_user", 2, "TWITTERAPI_IO", false, "127.0.0.1");
            // 不再把「上游不给下一页」当成取完：自动按时间段补齐，两类数据都补满 48 小时才算覆盖
            assertThat(advancedQueries).anyMatch(q -> q.contains("query=from:heavy_user") && q.contains("since_time:"));
            assertThat(advancedQueries).anyMatch(q -> q.contains("to:heavy_user") && q.contains("-from:heavy_user"));
            assertThat(b.coveredWindowDays()).isEqualTo(2);
            assertThat(b.day().activity().coverageNote()).isEmpty();
            assertThat(b.day().activity().originalPosts()).isEqualTo(2);
            assertThat(b.day().interactions().peerStats()).extracting(p -> p.peer().username()).contains("peer_a", "peer_b");
        } finally {
            mockUpstream.stop(0);
        }
    }

    @Test
    void shouldAlignWindowToCommonCoverageWhenStreamsCoverDifferentSpans() {
        Instant now = Instant.parse("2026-11-13T06:00:00Z");
        XUserSummary me = new XUserSummary("1", "me_x", "Me", "", "", true, 100, 100, 1000, "2022");
        XUserSummary peer = new XUserSummary("2", "peer_x", "Peer", "", "", false, 100, 100, 100, "2022");
        List<RawTweet> timeline = List.of(
                new RawTweet("p1", "post", now.minus(Duration.ofHours(5)), TweetKind.POST, null, null, List.of(),
                        false, false, 100, 1, 0, 0, 0, false, "p1", "")
        );
        // 别人的评论能看到 30 小时前，但我的动态只拉到 19 小时前：19~30 小时这段是盲区，不能判为「待回复」
        List<InboundInteraction> inbound = List.of(
                new InboundInteraction("c_recent", peer, now.minus(Duration.ofHours(4)), InboundKind.REPLY_TO_ME, "hi", "p1", "p1", "me_x"),
                new InboundInteraction("c_blind", peer, now.minus(Duration.ofHours(25)), InboundKind.REPLY_TO_ME, "old", "old_root", "old_root", "me_x")
        );
        RawAccountSnapshot snap = new RawAccountSnapshot(
                ProviderMode.TWITTERAPI_IO, true, "", now, me, timeline, inbound, List.of(), false, false,
                1,
                now.minus(Duration.ofHours(19)), "ts:123:", false,
                now.minus(Duration.ofHours(30)), "ts:456:", false
        );
        CommunityDashboardReport r = new CommunityAnalyticsEngine().analyze(snap, 2, ZoneId.of("Asia/Shanghai"), false);
        assertThat(r.activity().sampleCapped()).isTrue();
        assertThat(r.activity().coverageNote()).contains("实际数据覆盖").contains("19 小时");
        PeerInteractionStat p = r.interactions().peerStats().get(0);
        assertThat(p.peerRepliesToMe()).isEqualTo(1);
        assertThat(p.unrepliedInMyPostCount()).isEqualTo(1);
        // 7 天 / 30 天窗口同样只按共同覆盖范围计算，不会把盲区数据混进来
        CommunityDashboardReport m = new CommunityAnalyticsEngine().analyze(snap, 30, ZoneId.of("Asia/Shanghai"), false);
        assertThat(m.interactions().peerStats().get(0).peerRepliesToMe()).isEqualTo(1);
        assertThat(m.activity().effectiveCoveredDays()).isEqualTo(0.8);
    }

    @Test
    void shouldParallelizeHighDensitySlicesOffPeakAndProtectBoundedQueueAtPeak(@TempDir java.nio.file.Path tempDir) throws Exception {
        // Part 1：低峰期高密度缺口自动拆分为 4 路时间分片并发拉取，且完整合并连续覆盖区间
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        ADVANCED_SEARCH_CUSTOM.add(mockUpstream);
        Instant baseNow = Instant.now();

        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger peakConcurrent = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger sliceCalls = new java.util.concurrent.atomic.AtomicInteger(0);

        mockUpstream.createContext("/twitter/user/info", exchange -> writeJson(exchange, """
                {"status":"success","data":{"id":"601","userName":"fast_creator","name":"Fast Creator","followers_count":0,"following_count":0,"statusesCount":2000,"isBlueVerified":true}}
                """));
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> writeJson(exchange, String.format("""
                {"status":"success","data":{"tweets":[
                  {"id":"tw_head","conversationId":"tw_head","text":"head","isReply":false,"createdAt":"%s","viewCount":500,"likeCount":10},
                  {"id":"tw_50h","conversationId":"tw_50h","text":"50h","isReply":false,"createdAt":"%s","viewCount":500,"likeCount":10}
                ]},"has_next_page":false,"next_cursor":""}
                """, baseNow.minus(Duration.ofHours(2)), baseNow.minus(Duration.ofHours(50)))));
        mockUpstream.createContext("/twitter/user/mentions", exchange -> writeJson(exchange, String.format("""
                {"status":"success","tweets":[
                  {"id":"m_50h","conversationId":"tw_head","inReplyToId":"tw_head","inReplyToUsername":"fast_creator","isReply":true,"text":"ok","createdAt":"%s","author":{"id":"8","userName":"p1","name":"P1","followers_count":100}}
                ],"has_next_page":false}
                """, baseNow.minus(Duration.ofHours(50)))));

        mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> {
            String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            boolean fromMe = q.contains("query=from:fast_creator");
            if (!fromMe) {
                writeJson(exchange, "{\"status\":\"success\",\"tweets\":[],\"has_next_page\":false,\"next_cursor\":\"\"}");
                return;
            }
            long since = Long.parseLong(q.replaceAll("(?s).*since_time:(\\d+).*", "$1"));
            long until = Long.parseLong(q.replaceAll("(?s).*until_time:(\\d+).*", "$1"));
            long spanHours = (until - since) / 3600L;
            int cur = inFlight.incrementAndGet();
            peakConcurrent.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(130L);
                if (spanHours > 80L) {
                    // 首页探测（50h ~ 169h，跨度约 119h）：返回 10 小时跨度与下一页标记，触发 4 路并行分片
                    Instant tailTs = Instant.ofEpochSecond(until - 10 * 3600L);
                    writeJson(exchange, String.format("""
                            {"status":"success","tweets":[{"id":"tw_probe","conversationId":"tw_probe","text":"probe","isReply":false,"createdAt":"%s","viewCount":300,"likeCount":5}],"has_next_page":true,"next_cursor":"c_more"}
                            """, tailTs));
                } else {
                    int seq = sliceCalls.incrementAndGet();
                    Instant midTs = Instant.ofEpochSecond((since + until) / 2L);
                    writeJson(exchange, String.format("""
                            {"status":"success","tweets":[{"id":"tw_slice_%d","conversationId":"tw_slice_%d","text":"slice","isReply":false,"createdAt":"%s","viewCount":300,"likeCount":5}],"has_next_page":false,"next_cursor":""}
                            """, seq, seq, midTs));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        });
        mockUpstream.start();

        try {
            int port = mockUpstream.getAddress().getPort();
            XOpsProperties props = new XOpsProperties();
            props.setDefaultProvider("TWITTERAPI_IO");
            props.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            props.setTwitterapiIoKey("test_pool_parallel_key");
            props.setApiPoolCoreSize(8);
            props.setApiPoolMaxSize(12);
            props.setApiPoolQueueCapacity(32);
            props.setApiMaxParallelSlices(4);
            props.setCacheTtlSeconds(300L);
            props.setDiskCacheDir(tempDir.toString());

            com.xops.hub.provider.SharedApiExecutor sharedPool = new com.xops.hub.provider.SharedApiExecutor(props);
            try {
                TwitterApiIoProvider twitterProvider = new TwitterApiIoProvider(props, objectMapper, sharedPool);
                OfficialXApiV2Provider officialProvider = new OfficialXApiV2Provider(props, objectMapper, sharedPool);
                CommunityHubService service = new CommunityHubService(props, new DemoXDataProvider(), twitterProvider, officialProvider, objectMapper);

                // 先完成 48 小时覆盖
                MultiPeriodDashboardBundle b2 = service.analyzeAccountBundle("fast_creator", 2, "TWITTERAPI_IO", false, "127.0.0.1");
                assertThat(b2.coveredWindowDays()).isEqualTo(2);

                // 切到 7 天：1 次 130ms 探测 + 4 个 130ms 子区间并行（串行需 ~650ms+，并行约 ~280ms）
                long t0 = System.currentTimeMillis();
                MultiPeriodDashboardBundle b7 = service.analyzeAccountBundle("fast_creator", 7, "TWITTERAPI_IO", false, "127.0.0.1");
                long elapsedMs = System.currentTimeMillis() - t0;

                assertThat(b7.coveredWindowDays()).isEqualTo(7);
                assertThat(sliceCalls.get()).isEqualTo(4);
                assertThat(peakConcurrent.get()).isGreaterThanOrEqualTo(3);
                assertThat(elapsedMs).isLessThan(520L);
                // 首屏 2 帖 + 探测 1 帖 + 4 个并行分片各 1 帖 = 7 帖完整入库不丢片
                assertThat(b7.week().activity().originalPosts()).isEqualTo(7);
            } finally {
                sharedPool.shutdown();
            }

            // Part 2：高峰期有界队列满载拒绝 + 排队超时快速中止（不消耗上游 API）+ 已入库用户秒回本地数据兜底
            XOpsProperties peakProps = new XOpsProperties();
            peakProps.setDefaultProvider("TWITTERAPI_IO");
            peakProps.setTwitterapiIoBaseUrl("http://127.0.0.1:" + port);
            peakProps.setTwitterapiIoKey("test_pool_peak_key");
            peakProps.setApiPoolCoreSize(1);
            peakProps.setApiPoolMaxSize(1);
            peakProps.setApiPoolQueueCapacity(1);
            peakProps.setApiQueueTimeoutMs(110L);
            peakProps.setCacheTtlSeconds(300L);
            peakProps.setDiskCacheDir(tempDir.toString());

            com.xops.hub.provider.SharedApiExecutor tinyPool = new com.xops.hub.provider.SharedApiExecutor(peakProps);
            try {
                java.util.concurrent.CountDownLatch workerEntered = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean timedOutTaskExecutedUpstream = new java.util.concurrent.atomic.AtomicBoolean(false);

                // 占满唯一工作线程 360ms
                java.util.concurrent.CompletableFuture<String> blocker = tinyPool.submitAsync(() -> {
                    workerEntered.countDown();
                    Thread.sleep(360L);
                    return "ok";
                });
                assertThat(workerEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                // 高峰水位下分片并发度自动降为 1
                assertThat(tinyPool.suggestSliceConcurrency(48 * 3600L, 12, 20, false)).isEqualTo(1);

                // 占满唯一队列槽位：排队满 110ms 后应立即超时返回 503，绝不等满 360ms，也绝不执行上游调用
                long qStart = System.currentTimeMillis();
                java.util.concurrent.CompletableFuture<String> queuedTask = tinyPool.submitAsync(() -> {
                    timedOutTaskExecutedUpstream.set(true);
                    return "never";
                });

                // 第 3 个请求超出有界队列容量（queueCapacity=1）：0ms 立即拒绝，防止队列无限堆积
                assertThatThrownBy(() -> tinyPool.execute(() -> "overflow"))
                        .isInstanceOf(com.xops.hub.provider.XDataProviderException.class)
                        .satisfies(ex -> assertThat(((com.xops.hub.provider.XDataProviderException) ex).getStatusCode()).isEqualTo(503));

                // 已有持久化数据的老用户在高峰期触发 503 保护时，直接秒回已入库的完整 7 天数据，不报错
                CommunityHubService peakService = new CommunityHubService(
                        peakProps, new DemoXDataProvider(),
                        new TwitterApiIoProvider(peakProps, objectMapper, tinyPool),
                        new OfficialXApiV2Provider(peakProps, objectMapper, tinyPool),
                        objectMapper
                );
                MultiPeriodDashboardBundle degradedBundle = peakService.analyzeAccountBundle("fast_creator", 7, "TWITTERAPI_IO", false, "127.0.0.1");
                assertThat(degradedBundle.week().activity().originalPosts()).isEqualTo(7);
                assertThat(degradedBundle.week().bannerNotice()).contains("已入库数据");

                // 验证队列中的第 2 个任务在 ~110ms 准时超时中止，且工作线程释放后未触发其上游请求
                assertThatThrownBy(queuedTask::join).hasCauseInstanceOf(com.xops.hub.provider.XDataProviderException.class);
                assertThat(System.currentTimeMillis() - qStart).isLessThan(300L);
                assertThat(blocker.join()).isEqualTo("ok");
                Thread.sleep(30L);
                assertThat(timedOutTaskExecutedUpstream.get()).isFalse();
                Map<String, Object> metrics = tinyPool.snapshotMetrics();
                assertThat((Long) metrics.get("rejectedTasks")).isGreaterThanOrEqualTo(1L);
                assertThat((Long) metrics.get("queueTimedOutTasks")).isGreaterThanOrEqualTo(1L);
            } finally {
                tinyPool.shutdown();
            }
        } finally {
            mockUpstream.stop(0);
        }
    }

    @Test
    void shouldIsolate100ConcurrentUsersOnSameSharedIpWithoutFalseRateLimitOrDataLeak(@TempDir java.nio.file.Path tempDir) throws Exception {
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService upstreamExec = java.util.concurrent.Executors.newFixedThreadPool(16);
        mockUpstream.setExecutor(upstreamExec);

        Instant now = Instant.now();
        String recentTweetTime = now.minusSeconds(3600).toString();
        String anchorTweetTime = now.minusSeconds(54 * 3600L).toString();
        String recentMentionTime = now.minusSeconds(5400).toString();
        String anchorMentionTime = now.minusSeconds(55 * 3600L).toString();
        java.util.concurrent.atomic.AtomicInteger totalUpstreamCalls = new java.util.concurrent.atomic.AtomicInteger(0);

        mockUpstream.createContext("/twitter/user/info", exchange -> {
            totalUpstreamCalls.incrementAndGet();
            String u = extractQueryParam(exchange.getRequestURI().getRawQuery(), "userName");
            writeJson(exchange, """
                    {"status":"success","data":{"id":"id_%s","userName":"%s","name":"Name %s","biography":"Bio of %s","followers_count":1,"following_count":1,"statusesCount":2}}
                    """.formatted(u, u, u, u));
        });
        mockUpstream.createContext("/twitter/user/followings", exchange -> {
            totalUpstreamCalls.incrementAndGet();
            String u = extractQueryParam(exchange.getRequestURI().getRawQuery(), "userName");
            writeJson(exchange, """
                    {"followings":[{"id":"p_%s","userName":"peer_%s","name":"Peer of %s","followers_count":50,"following_count":20}],"has_next_page":false}
                    """.formatted(u, u, u));
        });
        mockUpstream.createContext("/twitter/user/followers", exchange -> {
            totalUpstreamCalls.incrementAndGet();
            String u = extractQueryParam(exchange.getRequestURI().getRawQuery(), "userName");
            writeJson(exchange, """
                    {"followers":[{"id":"p_%s","userName":"peer_%s","name":"Peer of %s","followers_count":50,"following_count":20}],"has_next_page":false}
                    """.formatted(u, u, u));
        });
        mockUpstream.createContext("/twitter/user/last_tweets", exchange -> {
            totalUpstreamCalls.incrementAndGet();
            String u = extractQueryParam(exchange.getRequestURI().getRawQuery(), "userName");
            writeJson(exchange, """
                    {"data":{"tweets":[
                      {"id":"tw1_%s","text":"post_of_%s unique content","createdAt":"%s","likeCount":10,"replyCount":1,"retweetCount":0,"quoteCount":0,"viewCount":100,"isReply":false},
                      {"id":"tw2_%s","text":"older_anchor_of_%s","createdAt":"%s","likeCount":3,"replyCount":0,"retweetCount":0,"quoteCount":0,"viewCount":40,"isReply":false}
                    ]},"has_next_page":false}
                    """.formatted(u, u, recentTweetTime, u, u, anchorTweetTime));
        });
        mockUpstream.createContext("/twitter/user/mentions", exchange -> {
            totalUpstreamCalls.incrementAndGet();
            String u = extractQueryParam(exchange.getRequestURI().getRawQuery(), "userName");
            writeJson(exchange, """
                    {"tweets":[
                      {"id":"ib1_%s","text":"@%s reply_to_%s","createdAt":"%s","isReply":true,"conversationId":"tw1_%1$s","inReplyToId":"tw1_%s","inReplyToUsername":"%s",
                       "author":{"id":"p_%s","userName":"peer_%s","name":"Peer of %s","followers_count":50,"following_count":20}},
                      {"id":"ib2_%s","text":"@%s older_reply_to_%s","createdAt":"%s","isReply":true,"conversationId":"tw2_%1$s","inReplyToId":"tw2_%s","inReplyToUsername":"%s",
                       "author":{"id":"p_%s","userName":"peer_%s","name":"Peer of %s","followers_count":50,"following_count":20}}
                    ],"has_next_page":false}
                    """.formatted(u, u, u, recentMentionTime, u, u, u, u, u, u, u, u, anchorMentionTime, u, u, u, u, u));
        });
        ADVANCED_SEARCH_CUSTOM.add(mockUpstream);
        mockUpstream.createContext("/twitter/tweet/advanced_search", exchange -> {
            totalUpstreamCalls.incrementAndGet();
            writeJson(exchange, """
                    {"tweets":[],"has_next_page":false}
                    """);
        });
        mockUpstream.start();

        String baseUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort();
        XOpsProperties props = new XOpsProperties();
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setDiskCacheDir(tempDir.toString());
        props.setCacheTtlSeconds(300L);
        props.setTwitterapiIoBaseUrl(baseUrl);
        props.setTwitterapiIoKey("test-100-users-key");
        props.setApiPoolCoreSize(16);
        props.setApiPoolMaxSize(24);
        props.setApiPoolQueueCapacity(256);
        props.setApiQueueTimeoutMs(10000L);

        com.xops.hub.provider.SharedApiExecutor sharedPool = new com.xops.hub.provider.SharedApiExecutor(props);
        java.util.concurrent.ExecutorService clientPool = java.util.concurrent.Executors.newFixedThreadPool(20);
        try {
            CommunityHubService service = new CommunityHubService(
                    props,
                    new DemoXDataProvider(),
                    new TwitterApiIoProvider(props, objectMapper, sharedPool),
                    new OfficialXApiV2Provider(props, objectMapper, sharedPool),
                    objectMapper
            );

            // 1. 同一出口 IP（模拟同一公司 Wi-Fi / 活动现场 NAT / 反代）下 100 个真实账号同时拉取并签到：0 误伤、0 串号
            String sharedNatIp = "203.0.113.88";
            List<String> users = new java.util.ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                users.add(String.format("u_%03d", i));
            }

            List<java.util.concurrent.CompletableFuture<Void>> tasks = new java.util.ArrayList<>();
            for (String uname : users) {
                tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    MultiPeriodDashboardBundle b = service.analyzeAccountBundle(uname, 2, "TWITTERAPI_IO", false, sharedNatIp);
                    UsageCheckinResponse checkin = service.recordUsageCheckin(uname);

                    assertThat(b.day().profile().username()).isEqualTo(uname);
                    assertThat(b.week().profile().username()).isEqualTo(uname);
                    assertThat(b.month().profile().username()).isEqualTo(uname);
                    assertThat(b.day().contentTiming().topPosts()).hasSize(1);
                    assertThat(b.day().contentTiming().topPosts().get(0).text()).isEqualTo("post_of_" + uname + " unique content");
                    assertThat(b.day().interactions().peerStats()).hasSize(1);
                    assertThat(b.day().interactions().peerStats().get(0).peer().username()).isEqualTo("peer_" + uname);
                    assertThat(b.day().followAudit().mutualCount()).isEqualTo(1);
                    assertThat(checkin.usageCount()).isEqualTo(1);
                }, clientPool));
            }
            java.util.concurrent.CompletableFuture.allOf(tasks.toArray(new java.util.concurrent.CompletableFuture[0])).join();

            // 验证 100 个用户拥有各自独立的目录与文件（store_v1.json.gz + usage_v1.json）
            for (String uname : users) {
                assertThat(java.nio.file.Files.isRegularFile(
                        tempDir.resolve("users").resolve(uname).resolve("store_v1.json.gz"))).isTrue();
                assertThat(java.nio.file.Files.isRegularFile(
                        tempDir.resolve("users").resolve(uname).resolve("usage_v1.json"))).isTrue();
            }

            // 2. 100 个用户在 5 分钟内高频切页命中缓存：0 次新增上游调用，绝不消耗限流额度
            int callsBeforeCacheHits = totalUpstreamCalls.get();
            for (String uname : users) {
                MultiPeriodDashboardBundle cached = service.analyzeAccountBundle(uname, 2, "TWITTERAPI_IO", true, sharedNatIp);
                assertThat(cached.day().fromCache()).isTrue();
                assertThat(cached.day().profile().username()).isEqualTo(uname);
            }
            assertThat(totalUpstreamCalls.get()).isEqualTo(callsBeforeCacheHits);

            // 3. 防误伤验证：在无缓存直连场景（cacheTtlSeconds=0）下，即便同 IP 下某个异常账号（abuser_bot）
            //    每分钟连续触发超 30 次实时同步被精准拦截（429），同 IP 下的正常用户（u_001）依然畅通无阻
            XOpsProperties noCacheProps = new XOpsProperties();
            noCacheProps.setDefaultProvider("TWITTERAPI_IO");
            noCacheProps.setDiskCacheDir(tempDir.toString());
            noCacheProps.setCacheTtlSeconds(0L);
            noCacheProps.setTwitterapiIoBaseUrl(baseUrl);
            noCacheProps.setTwitterapiIoKey("test-100-users-key");
            CommunityHubService noCacheService = new CommunityHubService(
                    noCacheProps,
                    new DemoXDataProvider(),
                    new TwitterApiIoProvider(noCacheProps, objectMapper, sharedPool),
                    new OfficialXApiV2Provider(noCacheProps, objectMapper, sharedPool),
                    objectMapper
            );
            assertThatThrownBy(() -> {
                for (int i = 0; i < 35; i++) {
                    noCacheService.analyzeAccountBundle("abuser_bot", 2, "TWITTERAPI_IO", false, sharedNatIp);
                }
            }).isInstanceOf(com.xops.hub.provider.XDataProviderException.class)
              .satisfies(ex -> assertThat(((com.xops.hub.provider.XDataProviderException) ex).getStatusCode()).isEqualTo(429));

            MultiPeriodDashboardBundle innocentOnSameIp = noCacheService.analyzeAccountBundle("u_001", 2, "TWITTERAPI_IO", false, sharedNatIp);
            assertThat(innocentOnSameIp.day().profile().username()).isEqualTo("u_001");

            // 4. 跨账号合并与错位磁盘文件强拦截验证
            AccountStore.StoredAccount store001 = AccountStore.read(
                    tempDir.resolve("users").resolve("u_001").resolve("store_v1.json.gz"), objectMapper);
            assertThat(store001).isNotNull();
            assertThatThrownBy(() -> AccountStore.merge(
                    store001,
                    new XUserSummary("id_2", "u_002", "Name 2", "", "", false, 1, 1, 1, ""),
                    Instant.now(), List.of(), List.of(), List.of(), List.of(), null
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("账号 ID 不一致").hasMessageContaining("禁止合并持久化数据");

            // 若磁盘上 u_002 的文件被错误覆盖成 u_001 的内容，重启后读取时自动识破归属不符并丢弃重拉，绝不把 u_001 数据返给 u_002
            java.nio.file.Files.copy(
                    tempDir.resolve("users").resolve("u_001").resolve("store_v1.json.gz"),
                    tempDir.resolve("users").resolve("u_002").resolve("store_v1.json.gz"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
            CommunityHubService restartedService = new CommunityHubService(
                    props,
                    new DemoXDataProvider(),
                    new TwitterApiIoProvider(props, objectMapper, sharedPool),
                    new OfficialXApiV2Provider(props, objectMapper, sharedPool),
                    objectMapper
            );
            MultiPeriodDashboardBundle recovered002 = restartedService.analyzeAccountBundle("u_002", 2, "TWITTERAPI_IO", false, sharedNatIp);
            assertThat(recovered002.day().profile().username()).isEqualTo("u_002");
            assertThat(recovered002.day().contentTiming().topPosts()).extracting(TopPostCard::text)
                    .containsExactly("post_of_u_002 unique content");

            // 5. 旧版平铺文件 store_v1_<user>.json.gz 无损自动迁移到 users/<user>/store_v1.json.gz
            java.nio.file.Path legacyFlat = tempDir.resolve("store_v1_legacy_acct.json.gz");
            AccountStore.StoredAccount legacyData = AccountStore.merge(
                    AccountStore.empty(),
                    new XUserSummary("id_legacy_acct", "legacy_acct", "Legacy Acct", "", "", false, 1, 1, 2, ""),
                    now, List.of(), List.of(),
                    List.of(new long[]{now.minusSeconds(7 * 86400L).getEpochSecond(), now.getEpochSecond()}),
                    List.of(new long[]{now.minusSeconds(7 * 86400L).getEpochSecond(), now.getEpochSecond()}),
                    null
            );
            AccountStore.write(legacyFlat, legacyData, objectMapper);
            assertThat(java.nio.file.Files.isRegularFile(legacyFlat)).isTrue();

            MultiPeriodDashboardBundle migrated = restartedService.analyzeAccountBundle("legacy_acct", 2, "TWITTERAPI_IO", false, sharedNatIp);
            assertThat(migrated.day().profile().username()).isEqualTo("legacy_acct");
            assertThat(java.nio.file.Files.exists(legacyFlat)).isFalse();
            assertThat(java.nio.file.Files.isRegularFile(
                    tempDir.resolve("users").resolve("legacy_acct").resolve("store_v1.json.gz"))).isTrue();
        } finally {
            clientPool.shutdownNow();
            sharedPool.shutdown();
            mockUpstream.stop(0);
            upstreamExec.shutdownNow();
        }
    }

    private static String extractQueryParam(String rawQuery, String key) {
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Missing query param: " + key);
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
