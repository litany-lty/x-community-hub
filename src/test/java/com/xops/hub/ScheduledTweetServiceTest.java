package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOAuthSessionRestoreFilter;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.ScheduledTweetController;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.model.ScheduledTweetModels.BatchConfirmItem;
import com.xops.hub.model.ScheduledTweetModels.OverdueReason;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetListResponse;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.ScheduledTweetService.MediaUploadInput;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.store.AccountStore;
import com.xops.hub.store.XOAuthSessionStore;
import com.xops.hub.store.XOAuthTokenStore;
import com.xops.hub.store.XOAuthTokenStore.StoredOAuthToken;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScheduledTweetServiceTest {

    private static final byte[] VALID_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04
    };

    private static final class MutableClock extends Clock {
        private final AtomicLong epochMs;
        private final ZoneId zone;

        MutableClock(long initialEpochMs) {
            this.epochMs = new AtomicLong(initialEpochMs);
            this.zone = ZoneOffset.UTC;
        }

        private MutableClock(AtomicLong epochMs, ZoneId zone) {
            this.epochMs = epochMs;
            this.zone = zone;
        }

        void setMillis(long millis) {
            this.epochMs.set(millis);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(epochMs, zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMs.get());
        }
    }

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer httpServer;
    private ExecutorService serverExecutor;
    private XOpsProperties properties;
    private SharedApiExecutor sharedExecutor;
    private XOAuthTokenStore tokenStore;
    private XOAuthService oauthService;
    private XPublishClient publishClient;
    private MutableClock clock;
    private ScheduledTweetService service;
    private MockMvc mockMvc;

    private final AtomicInteger tokenCalls = new AtomicInteger(0);
    private final AtomicInteger mediaInitCalls = new AtomicInteger(0);
    private final AtomicInteger mediaAppendCalls = new AtomicInteger(0);
    private final AtomicInteger mediaFinalizeCalls = new AtomicInteger(0);
    private final AtomicInteger tweetPostCalls = new AtomicInteger(0);
    private final List<String> tweetBodies = Collections.synchronizedList(new ArrayList<>());

    private volatile long tokenDelayMs = 0L;
    private volatile int tokenResponseStatus = 200;
    private volatile String tokenErrorCode = "";
    private volatile String tokenErrorDescription = "";
    private volatile String tokenResponseAccessToken = "tok-initial";
    private volatile String tokenResponseRefreshToken = "rt-initial";
    private volatile String tokenResponseScope = XOAuthService.PUBLISH_OAUTH_SCOPE;
    private volatile long tweetDelayMs = 0L;
    private volatile int tweetResponseStatus = 201;

    @BeforeEach
    void setUp() throws Exception {
        serverExecutor = Executors.newCachedThreadPool();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.setExecutor(serverExecutor);
        httpServer.createContext("/2", this::handleMockXApi);
        httpServer.start();

        int port = httpServer.getAddress().getPort();
        properties = new XOpsProperties();
        properties.setOfficialBaseUrl("http://127.0.0.1:" + port + "/2");
        properties.setOauthClientId("client-test");
        properties.setOauthClientSecret("secret-test");
        properties.setDiskCacheDir(tempDir.toString());
        properties.setTokenEncryptionSecret("unit-test-aes-gcm-secret-key-32bytes");
        properties.setSchedulerEnabled(false);
        properties.setScheduleGraceWindowSeconds(300L);
        properties.setTimezone("Asia/Shanghai");

        sharedExecutor = new SharedApiExecutor(properties);
        tokenStore = new XOAuthTokenStore(properties, objectMapper);
        oauthService = new XOAuthService(properties, objectMapper, sharedExecutor);
        publishClient = new XPublishClient(properties, objectMapper, sharedExecutor);
        clock = new MutableClock(1_760_000_000_000L);
        service = new ScheduledTweetService(properties, objectMapper, oauthService, publishClient, clock);
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduledTweetController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (sharedExecutor != null) {
            sharedExecutor.shutdown();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void oauthDefaultScopeEncryptionAndSingleFlightRefresh() throws Exception {
        // 1. 默认登录仅申请最小只读范围，不含写推文或离线刷新权限
        XOAuthService.AuthorizationRequestInfo loginReq = oauthService.createAuthorizationRequest("http://localhost:8080");
        String loginUrl = loginReq.authorizationUrl();
        assertTrue(loginUrl.contains("users.read"));
        assertTrue(loginUrl.contains("tweet.read"));
        assertFalse(loginUrl.contains("tweet.write"));
        assertFalse(loginUrl.contains("media.write"));
        assertFalse(loginUrl.contains("offline.access"));

        tokenResponseScope = XOAuthService.LOGIN_OAUTH_SCOPE;
        tokenResponseRefreshToken = "";
        AuthenticatedXUser loginUser = oauthService.handleCallback("auth-code-login", loginReq.state());
        assertEquals("1001", loginUser.id());
        assertEquals("alice", loginUser.username());
        XOAuthService.TokenCapability loginCap = oauthService.getTokenCapability("1001");
        assertFalse(loginCap.writeAuthorized());
        assertFalse(loginCap.mediaWriteAuthorized());
        assertFalse(loginCap.offlineRefreshable());

        // 2. 定时发布二次授权申请完整读写与 offline.access 范围并加密落盘
        XOAuthService.AuthorizationRequestInfo publishReq =
                oauthService.createPublishAuthorizationRequest("http://localhost:8080", "1001");
        String publishUrl = publishReq.authorizationUrl();
        assertTrue(publishUrl.contains("users.read"));
        assertTrue(publishUrl.contains("tweet.read"));
        assertTrue(publishUrl.contains("tweet.write"));
        assertTrue(publishUrl.contains("media.write"));
        assertTrue(publishUrl.contains("offline.access"));

        tokenResponseScope = XOAuthService.PUBLISH_OAUTH_SCOPE;
        tokenResponseRefreshToken = "rt-initial";
        XOAuthService.CallbackResult publishResult = oauthService.handleCallbackResult("auth-code-publish", publishReq.state());
        assertEquals(XOAuthService.AuthPurpose.PUBLISH, publishResult.purpose());
        assertEquals("1001", publishResult.user().id());
        assertTrue(publishResult.writeAuthorized());
        assertTrue(publishResult.mediaWriteAuthorized());
        assertTrue(publishResult.offlineRefreshable());

        Path encFile = tempDir.resolve("users").resolve("by-id").resolve("1001").resolve("oauth_token_v1.enc");
        assertTrue(Files.exists(encFile), "encrypted token file should exist on disk");
        String rawDisk = Files.readString(encFile, StandardCharsets.UTF_8);
        assertTrue(rawDisk.startsWith("v1:"), "stored token should use v1 AES-GCM envelope");
        assertFalse(rawDisk.contains("tok-initial"), "access token must not be stored in plaintext");
        assertFalse(rawDisk.contains("rt-initial"), "refresh token must not be stored in plaintext");

        // 3. 之后仅用默认只读范围再次登录，不得覆盖或降级既有的写权限与 refresh_token
        XOAuthService.AuthorizationRequestInfo reloginReq = oauthService.createAuthorizationRequest("http://localhost:8080");
        tokenResponseScope = XOAuthService.LOGIN_OAUTH_SCOPE;
        tokenResponseAccessToken = "tok-readonly";
        tokenResponseRefreshToken = "";
        oauthService.handleCallback("auth-code-relogin", reloginReq.state());
        XOAuthService.TokenCapability preservedCap = oauthService.getTokenCapability("1001");
        assertTrue(preservedCap.writeAuthorized());
        assertTrue(preservedCap.mediaWriteAuthorized());
        assertTrue(preservedCap.offlineRefreshable());
        assertEquals("rt-initial", tokenStore.load("1001").refreshToken());

        // 4. 二次授权跨账号校验：若回调返回不同 X ID 则拒绝保存
        XOAuthService.AuthorizationRequestInfo mismatchReq =
                oauthService.createPublishAuthorizationRequest("http://localhost:8080", "9999");
        tokenResponseScope = XOAuthService.PUBLISH_OAUTH_SCOPE;
        tokenResponseAccessToken = "tok-initial";
        tokenResponseRefreshToken = "rt-initial";
        XDataProviderException mismatchEx = assertThrows(
                XDataProviderException.class,
                () -> oauthService.handleCallbackResult("auth-code-mismatch", mismatchReq.state())
        );
        assertEquals(403, mismatchEx.getStatusCode());
        assertNull(tokenStore.load("9999"));

        // 写入即将过期的令牌并并发触发刷新，验证 single-flight + refresh_token 轮换
        long nowSec = Instant.now().getEpochSecond();
        tokenStore.save(new StoredOAuthToken(
                "1001",
                "alice",
                "tok-expiring",
                "rt-old",
                nowSec + 30L,
                XOAuthService.PUBLISH_OAUTH_SCOPE,
                nowSec
        ));
        tokenCalls.set(0);
        tokenDelayMs = 60L;
        tokenResponseAccessToken = "tok-refreshed";
        tokenResponseRefreshToken = "rt-rotated";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<String> task = () -> {
                ready.countDown();
                assertTrue(go.await(3, TimeUnit.SECONDS));
                return oauthService.getValidWriteAccessToken("1001", true);
            };
            Future<String> f1 = pool.submit(task);
            Future<String> f2 = pool.submit(task);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            go.countDown();

            assertEquals("tok-refreshed", f1.get(5, TimeUnit.SECONDS));
            assertEquals("tok-refreshed", f2.get(5, TimeUnit.SECONDS));
            assertEquals(1, tokenCalls.get(), "concurrent refresh should hit /oauth2/token only once");
            assertEquals("rt-rotated", tokenStore.load("1001").refreshToken());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredOrRevokedPublishTokenClearsWriteCapabilityAndAllowsReauthorization() throws Exception {
        long nowSec = Instant.now().getEpochSecond();
        // 1) X OAuth2 refresh_token 过期时常返回 HTTP 400 invalid_request（而非仅 invalid_grant）
        tokenStore.save(new StoredOAuthToken(
                "1001",
                "alice",
                "tok-expired",
                "rt-expired",
                nowSec - 60L,
                XOAuthService.PUBLISH_OAUTH_SCOPE,
                nowSec - 3600L
        ));
        assertTrue(oauthService.getTokenCapability("1001").writeAuthorized());
        tokenResponseStatus = 400;
        tokenErrorCode = "invalid_request";
        tokenErrorDescription = "Value passed for the token was invalid.";

        XDataProviderException refreshEx = assertThrows(
                XDataProviderException.class,
                () -> oauthService.getValidWriteAccessToken("1001", false)
        );
        assertEquals(401, refreshEx.getStatusCode());
        assertFalse(oauthService.getTokenCapability("1001").writeAuthorized(),
                "400 invalid_request on refresh_token must clear stale write capability so UI shows reauth entry");

        // 2) access_token 本地尚未到期但被 X 侧吊销（/2/tweets 返回 401）且刷新失败时，调度器应同步清除失效令牌
        long freshSec = Instant.now().getEpochSecond();
        tokenStore.save(new StoredOAuthToken(
                "1001",
                "alice",
                "tok-revoked-early",
                "rt-also-revoked",
                freshSec + 3600L,
                XOAuthService.PUBLISH_OAUTH_SCOPE,
                freshSec
        ));
        assertTrue(service.listJobs("1001", "alice").writeAuthorized());
        tweetResponseStatus = 401;
        long t0 = clock.millis();
        ScheduledTweetJob job = service.createScheduledTweet(
                "1001",
                "alice",
                "授权吊销测试推文",
                t0 + 30_000L,
                "req-revoked-token",
                List.of()
        );
        clock.setMillis(t0 + 31_000L);
        service.processDueJobsForAccount("1001");
        ScheduledTweetJob failed = findJobById("1001", job.id());
        assertEquals(ScheduledTweetStatus.FAILED, failed.status());
        assertTrue(failed.lastError().contains("二次授权"), "failed message should guide user to re-authorize");
        assertFalse(service.listJobs("1001", "alice").writeAuthorized(),
                "401 on publish + failed refresh must flip writeAuthorized to false");

        // 3) 兼容存量卡死状态：旧版失败任务已落盘但旧令牌文件未删，首次读取列表自愈，重新二次授权后恢复
        tokenStore.save(new StoredOAuthToken(
                "1001",
                "alice",
                "tok-legacy-stale",
                "rt-legacy-stale",
                freshSec - 120L,
                XOAuthService.PUBLISH_OAUTH_SCOPE,
                (t0 - 10_000L) / 1000L
        ));
        assertFalse(service.listJobs("1001", "alice").writeAuthorized(),
                "legacy stale token older than auth-failed job should be evicted on listJobs");

        tokenResponseStatus = 200;
        tokenErrorCode = "";
        tokenErrorDescription = "";
        tweetResponseStatus = 201;
        tokenResponseAccessToken = "tok-reauthorized";
        tokenResponseRefreshToken = "rt-reauthorized";
        XOAuthService.AuthorizationRequestInfo reauthReq =
                oauthService.createPublishAuthorizationRequest("http://localhost:8080", "1001");
        XOAuthService.CallbackResult reauthCb = oauthService.handleCallbackResult("code-reauth", reauthReq.state());
        assertTrue(reauthCb.writeAuthorized());
        assertTrue(service.listJobs("1001", "alice").writeAuthorized(),
                "newly re-authorized token newer than prior failed job must remain writeAuthorized");
    }

    @Test
    void scheduledTextWithPngAndImageOnlyTweetPublish() throws Exception {
        seedValidWriteToken("1001", "alice");

        long t0 = clock.millis();
        ScheduledTweetJob job1 = service.createScheduledTweet(
                "1001",
                "alice",
                "Hello X",
                t0 + 30_000L,
                "req-text-png",
                List.of(new MediaUploadInput("hero.png", VALID_PNG))
        );
        clock.setMillis(t0 + 35_000L);
        service.processDueJobsForAccount("1001");

        assertEquals(1, mediaInitCalls.get());
        assertEquals(1, mediaAppendCalls.get());
        assertEquals(1, mediaFinalizeCalls.get());
        assertEquals(1, tweetPostCalls.get());

        JsonNode tweet1 = objectMapper.readTree(tweetBodies.get(0));
        assertEquals("Hello X", tweet1.path("text").asText());
        assertEquals("media_9001", tweet1.path("media").path("media_ids").get(0).asText());

        ScheduledTweetJob fetched1 = findJobById("1001", job1.id());
        assertEquals(ScheduledTweetStatus.PUBLISHED, fetched1.status());
        assertEquals("tweet_1001", fetched1.publishedTweetId());

        // 纯图片（空白正文）不得发送空 text 字段
        long t1 = clock.millis();
        ScheduledTweetJob job2 = service.createScheduledTweet(
                "1001",
                "alice",
                "   ",
                t1 + 30_000L,
                "req-image-only",
                List.of(new MediaUploadInput("only.png", VALID_PNG))
        );
        clock.setMillis(t1 + 35_000L);
        service.processDueJobsForAccount("1001");

        assertEquals(2, tweetPostCalls.get());
        JsonNode tweet2 = objectMapper.readTree(tweetBodies.get(1));
        assertFalse(tweet2.has("text"), "blank tweet text must be omitted from POST /2/tweets payload");
        assertEquals(1, tweet2.path("media").path("media_ids").size());
        assertEquals(ScheduledTweetStatus.PUBLISHED, findJobById("1001", job2.id()).status());
    }

    @Test
    void overdueMissedWindowAndCrashRecoveryRequireConfirmationThenAutoResend() throws Exception {
        seedValidWriteToken("1001", "alice");
        long t0 = clock.millis();

        ScheduledTweetJob jobA = service.createScheduledTweet(
                "1001",
                "alice",
                "Missed window tweet",
                t0 + 60_000L,
                "req-overdue-a",
                List.of()
        );
        // 超过 300s 宽限期
        clock.setMillis(t0 + 400_000L);
        service.processDueJobsForAccount("1001");

        assertEquals(0, tweetPostCalls.get(), "overdue job must NOT auto-send without confirmation");
        ScheduledTweetJob promotedA = findJobById("1001", jobA.id());
        assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, promotedA.status());
        assertEquals(OverdueReason.MISSED_WINDOW, promotedA.overdueReason());
        assertFalse(promotedA.uncertainDelivery());
        assertNotNull(promotedA.confirmToken());
        assertFalse(promotedA.confirmToken().isBlank());

        ScheduledTweetJob queuedA = service.confirmAndResend("1001", promotedA.id(), promotedA.confirmToken());
        assertEquals(ScheduledTweetStatus.RESEND_QUEUED, queuedA.status());

        ScheduledTweetJob doneA = awaitJobStatus("1001", promotedA.id(), ScheduledTweetStatus.PUBLISHED, 2000L);
        assertEquals(1, tweetPostCalls.get());
        assertEquals("tweet_1001", doneA.publishedTweetId());

        // 模拟服务崩溃前卡在 SENDING 且已发出 POST 请求的恢复场景
        long t1 = clock.millis();
        ScheduledTweetJob jobB = service.createScheduledTweet(
                "1001",
                "alice",
                "Interrupted sending tweet",
                t1 + 60_000L,
                "req-crash-b",
                List.of()
        );
        Path queueFile = tempDir.resolve("users").resolve("by-id").resolve("1001").resolve("scheduled_tweets_v1.json");
        String json = Files.readString(queueFile, StandardCharsets.UTF_8);
        String mutated = json
                .replace("\"id\":\"" + jobB.id() + "\"", "\"id\":\"" + jobB.id() + "\"")
                .replace("\"status\":\"SCHEDULED\",\"overdueReason\":\"NONE\",\"uncertainDelivery\":false,\"postRequestDispatched\":false",
                        "\"status\":\"SENDING\",\"overdueReason\":\"NONE\",\"uncertainDelivery\":false,\"postRequestDispatched\":true");
        assertTrue(mutated.contains("\"postRequestDispatched\":true"));
        Files.writeString(queueFile, mutated, StandardCharsets.UTF_8);

        ScheduledTweetService recoveryService = new ScheduledTweetService(properties, objectMapper, oauthService, publishClient, clock);
        try {
            recoveryService.recoverAllAccountsOnStartup();
            ScheduledTweetListResponse listed = recoveryService.listJobs("1001", "alice");
            ScheduledTweetJob recoveredB = listed.items().stream()
                    .filter(j -> j.id().equals(jobB.id()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, recoveredB.status());
            assertEquals(OverdueReason.INTERRUPTED_SENDING, recoveredB.overdueReason());
            assertTrue(recoveredB.uncertainDelivery());
            assertEquals(1, tweetPostCalls.get(), "crash recovery must not auto-dispatch /2/tweets");
        } finally {
            recoveryService.shutdown();
        }
    }

    @Test
    void antiDuplicationIdempotencyConcurrencyAndUncertainDeliveryGuard() throws Exception {
        seedValidWriteToken("1001", "alice");
        long t0 = clock.millis();

        // 1. clientRequestId 幂等与近似时间内容去重
        ScheduledTweetJob first = service.createScheduledTweet(
                "1001",
                "alice",
                "Idempotent post",
                t0 + 40_000L,
                "req-dup-1",
                List.of(new MediaUploadInput("a.png", VALID_PNG))
        );
        ScheduledTweetJob idempotentRepeat = service.createScheduledTweet(
                "1001",
                "alice",
                "Idempotent post",
                t0 + 40_000L,
                "req-dup-1",
                List.of(new MediaUploadInput("a.png", VALID_PNG))
        );
        assertEquals(first.id(), idempotentRepeat.id());

        XDataProviderException dupEx = assertThrows(XDataProviderException.class, () -> service.createScheduledTweet(
                "1001",
                "alice",
                "Idempotent post",
                t0 + 50_000L,
                "req-dup-2",
                List.of(new MediaUploadInput("a.png", VALID_PNG))
        ));
        assertEquals(409, dupEx.getStatusCode());

        // 2. 并发 confirmAndResend + processDueJobsForAccount 只发送 1 次
        clock.setMillis(t0 + 400_000L);
        ScheduledTweetJob overdue = findJobById("1001", first.id());
        assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, overdue.status());

        tweetDelayMs = 60L;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(3, TimeUnit.SECONDS);
                    service.confirmAndResend("1001", overdue.id(), overdue.confirmToken());
                    return null;
                }));
            }
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(3, TimeUnit.SECONDS);
                    service.processDueJobsForAccount("1001");
                    return null;
                }));
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        tweetDelayMs = 0L;

        ScheduledTweetJob published = awaitJobStatus("1001", overdue.id(), ScheduledTweetStatus.PUBLISHED, 2000L);
        assertEquals(1, tweetPostCalls.get(), "concurrent confirmation + dispatch must execute POST /2/tweets once");

        XDataProviderException alreadyPublishedEx = assertThrows(XDataProviderException.class,
                () -> service.confirmAndResend("1001", published.id(), overdue.confirmToken()));
        assertEquals(409, alreadyPublishedEx.getStatusCode());

        // 3. 504 响应不盲目重试，保留 remoteMediaId 并禁止批量补发，单独确认补发复用已上传媒体
        mediaInitCalls.set(0);
        tweetResponseStatus = 504;
        long t1 = clock.millis();
        ScheduledTweetJob uncertainJob = service.createScheduledTweet(
                "1001",
                "alice",
                "Uncertain gateway timeout",
                t1 + 30_000L,
                "req-504",
                List.of(new MediaUploadInput("timeout.png", VALID_PNG))
        );
        clock.setMillis(t1 + 35_000L);
        service.processDueJobsForAccount("1001");

        assertEquals(2, tweetPostCalls.get(), "504 on POST /2/tweets must not blind-retry");
        assertEquals(1, mediaInitCalls.get());

        ScheduledTweetJob after504 = findJobById("1001", uncertainJob.id());
        assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, after504.status());
        assertEquals(OverdueReason.UNCERTAIN_DELIVERY, after504.overdueReason());
        assertTrue(after504.uncertainDelivery());
        assertFalse(after504.media().get(0).remoteMediaId().isBlank());

        XDataProviderException batchBlockedEx = assertThrows(XDataProviderException.class,
                () -> service.batchConfirmOverdue("1001", "alice", List.of(
                        new BatchConfirmItem(after504.id(), after504.confirmToken())
                )));
        assertEquals(400, batchBlockedEx.getStatusCode());

        tweetResponseStatus = 201;
        service.confirmAndResend("1001", after504.id(), after504.confirmToken());
        ScheduledTweetJob recoveredDone = awaitJobStatus("1001", after504.id(), ScheduledTweetStatus.PUBLISHED, 2000L);
        assertEquals(ScheduledTweetStatus.PUBLISHED, recoveredDone.status());
        assertEquals(1, mediaInitCalls.get(), "resend should reuse checkpointed remoteMediaId without re-uploading");
        assertEquals(3, tweetPostCalls.get());
    }

    @Test
    void controllerAuthCsrfAndMediaOwnershipIsolation() throws Exception {
        seedValidWriteToken("1001", "alice");
        mockMvc.perform(get("/api/scheduled-tweets"))
                .andExpect(status().isUnauthorized());

        MockHttpSession aliceSession = new MockHttpSession();
        aliceSession.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser(
                "1001", "alice", "Alice", "", true, Instant.now()
        ));

        long scheduledAt = clock.millis() + 60_000L;
        MockMultipartFile badOriginFile = new MockMultipartFile("files", "bad.png", "image/png", VALID_PNG);
        mockMvc.perform(multipart("/api/scheduled-tweets")
                        .file(badOriginFile)
                        .param("text", "Blocked CSRF")
                        .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                        .session(aliceSession)
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());

        MockMultipartFile validFile = new MockMultipartFile("files", "shot.png", "image/png", VALID_PNG);
        String createBody = mockMvc.perform(multipart("/api/scheduled-tweets")
                        .file(validFile)
                        .param("text", "Controller OK")
                        .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                        .param("clientRequestId", "req-mvc-1")
                        .session(aliceSession)
                        .header("Origin", "http://localhost"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode created = objectMapper.readTree(createBody);
        String jobId = created.path("id").asText();
        String assetId = created.path("media").get(0).path("assetId").asText();

        mockMvc.perform(get("/api/scheduled-tweets/{id}/media/{assetId}", jobId, assetId)
                        .session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(VALID_PNG));

        MockHttpSession bobSession = new MockHttpSession();
        bobSession.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser(
                "2002", "bob", "Bob", "", false, Instant.now()
        ));
        mockMvc.perform(get("/api/scheduled-tweets/{id}/media/{assetId}", jobId, assetId)
                        .session(bobSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void scheduledPublishRequiresBoundOwnerRegardlessOfRankOrFollowing() throws Exception {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        XUserSummary author = new XUserSummary(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "Litany", "", "", true, 500, 100, 50, "");

        List<RawTweet> authorTweets = new ArrayList<>();
        List<XUserSummary> followersList = new ArrayList<>();
        for (int rank = 1; rank <= 105; rank++) {
            String peerUid = String.valueOf(6000 + rank);
            String peerName = "peer_rank_" + rank;
            XUserSummary peer = new XUserSummary(peerUid, peerName, "Peer " + rank, "", "", false, 10, 10, 10, "");
            if (rank == 1 || rank == 100 || rank == 101) {
                followersList.add(peer);
            }
            int replyPairs = 110 - rank;
            for (int r = 1; r <= replyPairs; r++) {
                String cId = "c_sched_" + rank + "_" + r;
                authorTweets.add(new RawTweet(
                        "a_tw_" + rank + "_" + r,
                        "@" + peerName + " reply",
                        oneHourAgo,
                        TweetKind.REPLY,
                        peerName,
                        peer,
                        List.of(peerName),
                        false,
                        false,
                        10,
                        1,
                        0,
                        0,
                        0,
                        false,
                        cId,
                        cId
                ));
            }
        }
        List<long[]> monthCoverage = List.of(new long[]{now.minus(Duration.ofDays(31)).getEpochSecond(), now.getEpochSecond()});
        AccountStore.GraphUpdate authorGraph = new AccountStore.GraphUpdate(
                List.of(), followersList, Set.of(), false, false, false, false, List.of(), Map.of()
        );
        Path authorStorePath = tempDir.resolve("users/by-id/" + com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID + "/store_v" + AccountStore.VERSION + ".json.gz");
        AccountStore.StoredAccount authorStore = AccountStore.merge(
                null, author, now, authorTweets, List.of(), monthCoverage, monthCoverage, authorGraph
        );
        AccountStore.write(authorStorePath, authorStore, objectMapper);

        TwitterApiIoProvider twitterProvider = new TwitterApiIoProvider(properties, objectMapper);
        CommunityHubService hubService = new CommunityHubService(
                properties, new DemoXDataProvider(), twitterProvider, new OfficialXApiV2Provider(properties, objectMapper), objectMapper
        );
        ScheduledTweetService gatedService = new ScheduledTweetService(
                properties, objectMapper, oauthService, publishClient, hubService, clock
        );
        XOAuthSessionStore sessionStore = new XOAuthSessionStore(properties, objectMapper);
        XOAuthController gatedAuthController = new XOAuthController(properties, oauthService, sessionStore, hubService);
        try {
            MockMvc gatedMvc = MockMvcBuilders.standaloneSetup(
                            new ScheduledTweetController(gatedService, objectMapper),
                            gatedAuthController
                    )
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();

            seedValidWriteToken("2999", "stranger");
            seedValidWriteToken("6010", "peer_rank_10");
            seedValidWriteToken("6101", "peer_rank_101");
            seedValidWriteToken("6100", "peer_rank_100");
            seedValidWriteToken(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom");

            long scheduledAt = clock.millis() + 60_000L;

            var highestRank = hubService.evaluateScheduledPublishAccess("6001", "peer_rank_1");
            assertEquals(1, highestRank.authorInteractionRank());
            assertTrue(highestRank.followsAuthor());
            assertFalse(highestRank.allowed());
            assertFalse(hubService.evaluateScheduledPublishAccess("9999", "elidlicom").allowed());

            // 1. Unranked user -> blocked from single create, batch create, and publish step-up OAuth
            MockHttpSession strangerSession = authSession("2999", "stranger");
            var strangerRes = gatedMvc.perform(get("/api/scheduled-tweets").session(strangerSession))
                    .andReturn().getResponse();
            String strangerBody = strangerRes.getContentAsString(StandardCharsets.UTF_8);
            assertEquals(200, strangerRes.getStatus(), "GET /api/scheduled-tweets failed: " + strangerBody);
            JsonNode strangerList = objectMapper.readTree(strangerBody);
            assertFalse(strangerList.path("scheduleAccessAllowed").asBoolean(true));
            assertTrue(strangerList.path("authorInteractionRank").isNull());
            assertTrue(strangerList.path("scheduleLockedReason").asText().contains("已验证的作者账号"));
            gatedMvc.perform(multipart("/api/scheduled-tweets")
                            .param("text", "Blocked stranger")
                            .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                            .session(strangerSession))
                    .andExpect(status().isForbidden());
            gatedMvc.perform(multipart("/api/scheduled-tweets/batch")
                            .param("itemsJson", objectMapper.writeValueAsString(List.of(
                                    Map.of("text", "Blocked batch", "scheduledAtEpochMs", scheduledAt, "clientRequestId", "b_stranger")
                            )))
                            .session(strangerSession))
                    .andExpect(status().isForbidden());
            String strangerPublishAuthRedirect = gatedMvc.perform(get("/api/auth/x/authorize-publish").session(strangerSession))
                    .andExpect(status().isFound())
                    .andReturn().getResponse().getRedirectedUrl();
            assertNotNull(strangerPublishAuthRedirect);
            assertTrue(strangerPublishAuthRedirect.startsWith("/?tab=schedule&publish_auth_error="));

            // 2. Top 10 (#10) interaction frequency peer -> BLOCKED (paid capability restricted strictly to @elidlicom)
            MockHttpSession rank10Session = authSession("6010", "peer_rank_10");
            JsonNode rank10List = objectMapper.readTree(
                    gatedMvc.perform(get("/api/scheduled-tweets").session(rank10Session))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
            );
            assertFalse(rank10List.path("scheduleAccessAllowed").asBoolean(true));
            assertFalse(rank10List.path("followsAuthor").asBoolean(true));
            assertEquals(10, rank10List.path("authorInteractionRank").asInt());
            String rank10PublishAuthRedirect = gatedMvc.perform(get("/api/auth/x/authorize-publish").session(rank10Session))
                    .andExpect(status().isFound())
                    .andReturn().getResponse().getRedirectedUrl();
            assertNotNull(rank10PublishAuthRedirect);
            assertTrue(rank10PublishAuthRedirect.startsWith("/?tab=schedule&publish_auth_error="));
            gatedMvc.perform(multipart("/api/scheduled-tweets")
                            .param("text", "Blocked top10 peer")
                            .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                            .param("clientRequestId", "req-r10-blocked")
                            .session(rank10Session))
                    .andExpect(status().isForbidden());

            // 3. Ranked #101 -> BLOCKED
            MockHttpSession rank101Session = authSession("6101", "peer_rank_101");
            JsonNode rank101List = objectMapper.readTree(
                    gatedMvc.perform(get("/api/scheduled-tweets").session(rank101Session))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
            );
            assertFalse(rank101List.path("scheduleAccessAllowed").asBoolean(true));
            assertTrue(rank101List.path("followsAuthor").asBoolean(false));
            assertEquals(101, rank101List.path("authorInteractionRank").asInt());
            assertTrue(rank101List.path("scheduleLockedReason").asText().contains("已验证的作者账号"));
            gatedMvc.perform(multipart("/api/scheduled-tweets")
                            .param("text", "Blocked rank 101 follower")
                            .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                            .session(rank101Session))
                    .andExpect(status().isForbidden());
            gatedMvc.perform(multipart("/api/scheduled-tweets/batch")
                            .param("itemsJson", objectMapper.writeValueAsString(List.of(
                                    Map.of("text", "Blocked 101 batch", "scheduledAtEpochMs", scheduledAt, "clientRequestId", "b_101")
                            )))
                            .session(rank101Session))
                    .andExpect(status().isForbidden());
            String rank101PublishAuthRedirect = gatedMvc.perform(get("/api/auth/x/authorize-publish").session(rank101Session))
                    .andExpect(status().isFound())
                    .andReturn().getResponse().getRedirectedUrl();
            assertNotNull(rank101PublishAuthRedirect);
            assertTrue(rank101PublishAuthRedirect.startsWith("/?tab=schedule&publish_auth_error="));

            // 4. Ranked #100 -> also BLOCKED
            MockHttpSession rank100Session = authSession("6100", "peer_rank_100");
            JsonNode rank100List = objectMapper.readTree(
                    gatedMvc.perform(get("/api/scheduled-tweets").session(rank100Session))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
            );
            assertFalse(rank100List.path("scheduleAccessAllowed").asBoolean(true));
            assertEquals(100, rank100List.path("authorInteractionRank").asInt());
            gatedMvc.perform(multipart("/api/scheduled-tweets")
                            .param("text", "Blocked rank 100 follower")
                            .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                            .param("clientRequestId", "req-r100-1")
                            .session(rank100Session))
                    .andExpect(status().isForbidden());

            // 5. @elidlicom author himself is allowed and can create/reschedule/cancel
            MockHttpSession authorSession = authSession(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom");
            String authorCreated = gatedMvc.perform(multipart("/api/scheduled-tweets")
                            .param("text", "Author self scheduled post")
                            .param("scheduledAtEpochMs", String.valueOf(scheduledAt))
                            .param("clientRequestId", "req-author-1")
                            .session(authorSession))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String authorJobId = objectMapper.readTree(authorCreated).path("id").asText();
            gatedMvc.perform(post("/api/scheduled-tweets/{id}/reschedule", authorJobId)
                            .session(authorSession)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"scheduledAtEpochMs\":" + (scheduledAt + 30_000L) + "}"))
                    .andExpect(status().isOk());
            gatedMvc.perform(post("/api/scheduled-tweets/{id}/cancel", authorJobId)
                            .session(authorSession))
                    .andExpect(status().isOk());
        } finally {
            gatedService.shutdown();
        }
    }

    @Test
    void stepUpAuthorizePublishAndPersistentSessionAcrossRestartAndLogout() throws Exception {
        XOAuthSessionStore storeA = new XOAuthSessionStore(properties, objectMapper);
        XOAuthController controllerA = new XOAuthController(properties, oauthService, storeA);
        MockMvc mvcA = MockMvcBuilders.standaloneSetup(controllerA, new ScheduledTweetController(service))
                .addFilters(new XOAuthSessionRestoreFilter(storeA))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        // 1. 默认登录跳转只带只读 scope
        var loginRes = mvcA.perform(get("/api/auth/x/login"))
                .andExpect(status().isFound())
                .andReturn();
        String loginLoc = loginRes.getResponse().getRedirectedUrl();
        assertNotNull(loginLoc);
        assertTrue(loginLoc.contains("users.read"));
        assertTrue(loginLoc.contains("tweet.read"));
        assertFalse(loginLoc.contains("tweet.write"));
        assertFalse(loginLoc.contains("offline.access"));
        String loginState = extractQueryParam(java.net.URI.create(loginLoc).getRawQuery(), "state");
        MockHttpSession session = (MockHttpSession) loginRes.getRequest().getSession(false);
        assertNotNull(session);

        // 2. 登录回调成功后落地加密会话，/status 展示仅只读且下发 400 天 Max-Age Cookie
        tokenResponseScope = XOAuthService.LOGIN_OAUTH_SCOPE;
        tokenResponseRefreshToken = "";
        var cbRes = mvcA.perform(get("/api/auth/x/callback")
                        .param("code", "login-code")
                        .param("state", loginState)
                        .session(session))
                .andExpect(status().isFound())
                .andReturn();
        assertEquals("/?auth=ok", cbRes.getResponse().getRedirectedUrl());
        String persistedSessionId = session.getId();

        session.removeAttribute(XOAuthController.SESSION_COOKIE_TOUCHED_AT);
        var statusBeforeStepUp = mvcA.perform(get("/api/auth/x/status").session(session))
                .andExpect(status().isOk())
                .andReturn();
        String setCookieHeader = statusBeforeStepUp.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookieHeader);
        assertTrue(setCookieHeader.contains("Max-Age=34560000"));
        JsonNode statusJson1 = objectMapper.readTree(statusBeforeStepUp.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(statusJson1.path("loggedIn").asBoolean());
        assertFalse(statusJson1.path("writeAuthorized").asBoolean());
        assertFalse(statusJson1.path("offlineRefreshable").asBoolean());

        // 3. 模拟服务重启：重建全新 XOAuthSessionStore 与 Controller，仅携带原 JSESSIONID Cookie 自动恢复登录态
        XOAuthSessionStore storeB = new XOAuthSessionStore(properties, objectMapper);
        XOAuthController controllerB = new XOAuthController(properties, oauthService, storeB);
        MockMvc mvcB = MockMvcBuilders.standaloneSetup(controllerB, new ScheduledTweetController(service))
                .addFilters(new XOAuthSessionRestoreFilter(storeB))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        var restoredRes = mvcB.perform(get("/api/auth/x/status")
                        .cookie(new Cookie("JSESSIONID", persistedSessionId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode restoredJson = objectMapper.readTree(restoredRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(restoredJson.path("loggedIn").asBoolean());
        assertEquals("alice", restoredJson.path("user").path("username").asText());
        MockHttpSession restoredSession = (MockHttpSession) restoredRes.getRequest().getSession(false);
        assertNotNull(restoredSession);

        // 4. 在不退出登录的情况下发起定时发布二次授权，取消授权时保持登录并回跳定时页
        var cancelStepUpReq = mvcB.perform(get("/api/auth/x/authorize-publish").session(restoredSession))
                .andExpect(status().isFound())
                .andReturn();
        String cancelStepUpLoc = cancelStepUpReq.getResponse().getRedirectedUrl();
        assertNotNull(cancelStepUpLoc);
        assertTrue(cancelStepUpLoc.contains("tweet.write"));
        assertTrue(cancelStepUpLoc.contains("media.write"));
        assertTrue(cancelStepUpLoc.contains("offline.access"));
        String cancelState = extractQueryParam(java.net.URI.create(cancelStepUpLoc).getRawQuery(), "state");

        var cancelCb = mvcB.perform(get("/api/auth/x/callback")
                        .param("error", "access_denied")
                        .param("state", cancelState)
                        .session(restoredSession))
                .andExpect(status().isFound())
                .andReturn();
        assertTrue(cancelCb.getResponse().getRedirectedUrl().startsWith("/?tab=schedule&publish_auth_error="));
        JsonNode stillLoggedIn = objectMapper.readTree(
                mvcB.perform(get("/api/auth/x/status").session(restoredSession))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
        );
        assertTrue(stillLoggedIn.path("loggedIn").asBoolean());
        assertFalse(stillLoggedIn.path("writeAuthorized").asBoolean());

        // 5. 完成定时发布二次授权：保持同一用户登录，写推文与 offline.access 立即生效
        var stepUpReq = mvcB.perform(get("/api/auth/x/authorize-publish").session(restoredSession))
                .andExpect(status().isFound())
                .andReturn();
        String stepUpState = extractQueryParam(
                java.net.URI.create(stepUpReq.getResponse().getRedirectedUrl()).getRawQuery(), "state"
        );
        tokenResponseScope = XOAuthService.PUBLISH_OAUTH_SCOPE;
        tokenResponseRefreshToken = "rt-stepup";
        var stepUpCb = mvcB.perform(get("/api/auth/x/callback")
                        .param("code", "publish-code")
                        .param("state", stepUpState)
                        .session(restoredSession))
                .andExpect(status().isFound())
                .andReturn();
        assertEquals("/?auth=publish_ok&tab=schedule", stepUpCb.getResponse().getRedirectedUrl());

        JsonNode authorizedStatus = objectMapper.readTree(
                mvcB.perform(get("/api/auth/x/status").session(restoredSession))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
        );
        assertTrue(authorizedStatus.path("loggedIn").asBoolean());
        assertTrue(authorizedStatus.path("writeAuthorized").asBoolean());
        assertTrue(authorizedStatus.path("mediaWriteAuthorized").asBoolean());
        assertTrue(authorizedStatus.path("offlineRefreshable").asBoolean());

        // 6. 退出登录后，原 Cookie 与新轮换 Cookie 均立即失效，无法再自动恢复
        mvcB.perform(post("/api/auth/x/logout").session(restoredSession))
                .andExpect(status().isOk());
        JsonNode afterLogoutOldCookie = objectMapper.readTree(
                mvcB.perform(get("/api/auth/x/status").cookie(new Cookie("JSESSIONID", persistedSessionId)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
        );
        assertFalse(afterLogoutOldCookie.path("loggedIn").asBoolean());
        mvcB.perform(get("/api/scheduled-tweets").cookie(new Cookie("JSESSIONID", restoredSession.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void batchCreateAndOneHourMinimumSpacingEnforcement() throws Exception {
        seedValidWriteToken("1001", "alice");
        MockHttpSession session = authSession("1001", "alice");
        long nowMs = clock.millis();

        // 1. 批次内两条间隔 59 分钟（不足 1 小时）：整批原子拒绝 409，不残留任何任务或媒体目录
        long t10m = nowMs + 10 * 60_000L;
        long t69m = nowMs + 69 * 60_000L;
        String conflictBatchJson = objectMapper.writeValueAsString(List.of(
                Map.of("text", "Batch conflict #1", "scheduledAtEpochMs", t10m, "clientRequestId", "creq_0"),
                Map.of("text", "Batch conflict #2", "scheduledAtEpochMs", t69m, "clientRequestId", "creq_1")
        ));
        MockMultipartFile png0 = new MockMultipartFile("files_0", "one.png", "image/png", VALID_PNG);
        mockMvc.perform(multipart("/api/scheduled-tweets/batch")
                        .file(png0)
                        .param("itemsJson", conflictBatchJson)
                        .session(session))
                .andExpect(status().isConflict());
        assertEquals(0, service.listJobs("1001", "alice").items().size());
        Path mediaRoot = tempDir.resolve("scheduled-tweets").resolve("1001").resolve("media");
        if (Files.exists(mediaRoot)) {
            try (var dirs = Files.list(mediaRoot)) {
                assertEquals(0L, dirs.count(), "Rejected batch must not leave orphan media directories");
            }
        }

        // 2. 合法批量预设 3 条定时推文（间隔严格 >= 1 小时，含独立配图）+ 整批幂等重放
        long t70m = nowMs + 70 * 60_000L;
        long t130m = nowMs + 130 * 60_000L;
        String validBatchJson = objectMapper.writeValueAsString(List.of(
                Map.of("text", "Preset #1 with image", "scheduledAtEpochMs", t10m, "clientRequestId", "okbatch_0"),
                Map.of("text", "Preset #2 text only", "scheduledAtEpochMs", t70m, "clientRequestId", "okbatch_1"),
                Map.of("text", "Preset #3 with image", "scheduledAtEpochMs", t130m, "clientRequestId", "okbatch_2")
        ));
        MockMultipartFile png2 = new MockMultipartFile("files_2", "three.png", "image/png", VALID_PNG);
        String batchRespBody = mockMvc.perform(multipart("/api/scheduled-tweets/batch")
                        .file(png0)
                        .file(png2)
                        .param("itemsJson", validBatchJson)
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode batchNode = objectMapper.readTree(batchRespBody);
        assertEquals(3, batchNode.path("createdCount").asInt());
        assertEquals(3, batchNode.path("created").size());
        assertEquals(1, batchNode.path("created").get(0).path("media").size());
        assertEquals(0, batchNode.path("created").get(1).path("media").size());
        assertEquals(1, batchNode.path("created").get(2).path("media").size());

        // 整批相同 clientRequestId 重试幂等返回原 3 条任务，不重复入队
        String replayBody = mockMvc.perform(multipart("/api/scheduled-tweets/batch")
                        .file(png0)
                        .file(png2)
                        .param("itemsJson", validBatchJson)
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode replayNode = objectMapper.readTree(replayBody);
        assertEquals(
                batchNode.path("created").get(0).path("id").asText(),
                replayNode.path("created").get(0).path("id").asText()
        );
        assertEquals(3, service.listJobs("1001", "alice").items().size());

        // 3. 新增单条与已有 SCHEDULED 队列间隔不足 1 小时（+100m 距 +70m/+130m 仅 30m）-> 409；满 1 小时（+190m）-> 200
        mockMvc.perform(multipart("/api/scheduled-tweets")
                        .param("text", "Too close single")
                        .param("scheduledAtEpochMs", String.valueOf(nowMs + 100 * 60_000L))
                        .session(session))
                .andExpect(status().isConflict());

        long t190m = nowMs + 190 * 60_000L;
        String fourthResp = mockMvc.perform(multipart("/api/scheduled-tweets")
                        .param("text", "Spaced +1h single")
                        .param("scheduledAtEpochMs", String.valueOf(t190m))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String fourthId = objectMapper.readTree(fourthResp).path("id").asText();

        // 4. 改期（reschedule）与队列其他待发布推文间隔不足 1 小时（+160m 距 +130m 仅 30m）-> 409；满 1 小时（+200m）-> 200
        mockMvc.perform(post("/api/scheduled-tweets/" + fourthId + "/reschedule")
                        .contentType("application/json")
                        .content("{\"scheduledAtEpochMs\":" + (nowMs + 160 * 60_000L) + "}")
                        .session(session))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/scheduled-tweets/" + fourthId + "/reschedule")
                        .contentType("application/json")
                        .content("{\"scheduledAtEpochMs\":" + (nowMs + 200 * 60_000L) + "}")
                        .session(session))
                .andExpect(status().isOk());

        // 5. 已取消或已逾期转待确认的任务不再占用未来 1 小时时间槽
        clock.setMillis(nowMs + 220 * 60_000L);
        long afterOverdueNow = clock.millis();
        mockMvc.perform(multipart("/api/scheduled-tweets")
                        .param("text", "Fresh tweet after old ones became overdue")
                        .param("scheduledAtEpochMs", String.valueOf(afterOverdueNow + 15 * 60_000L))
                        .session(session))
                .andExpect(status().isOk());
    }

    private static String extractQueryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static MockHttpSession authSession(String accountId, String username) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser(
                accountId, username, username, "", false, Instant.now()
        ));
        return session;
    }

    private void seedValidWriteToken(String accountId, String username) {
        long nowSec = Instant.now().getEpochSecond();
        tokenStore.save(new StoredOAuthToken(
                accountId,
                username,
                "tok-valid",
                "rt-valid",
                nowSec + 7200L,
                XOAuthService.PUBLISH_OAUTH_SCOPE,
                nowSec
        ));
    }

    private ScheduledTweetJob findJobById(String accountId, String jobId) {
        return service.listJobs(accountId, "alice").items().stream()
                .filter(j -> j.id().equals(jobId))
                .findFirst()
                .orElseThrow();
    }

    private ScheduledTweetJob awaitJobStatus(
            String accountId,
            String jobId,
            ScheduledTweetStatus targetStatus,
            long timeoutMs
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ScheduledTweetJob latest = findJobById(accountId, jobId);
        while (latest.status() != targetStatus && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
            latest = findJobById(accountId, jobId);
        }
        assertEquals(targetStatus, latest.status(), "timed out waiting for job status " + targetStatus);
        return latest;
    }

    private void handleMockXApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] reqBytes = exchange.getRequestBody().readAllBytes();
        String reqBody = new String(reqBytes, StandardCharsets.UTF_8);

        if ("/2/oauth2/token".equals(path)) {
            int call = tokenCalls.incrementAndGet();
            sleepQuietly(tokenDelayMs);
            if (tokenResponseStatus >= 400) {
                String errJson = objectMapper.writeValueAsString(java.util.Map.of(
                        "error", tokenErrorCode,
                        "error_description", tokenErrorDescription
                ));
                sendJson(exchange, tokenResponseStatus, errJson);
                return;
            }
            String json = objectMapper.writeValueAsString(java.util.Map.of(
                    "token_type", "bearer",
                    "expires_in", 7200,
                    "access_token", tokenResponseAccessToken,
                    "refresh_token", tokenResponseRefreshToken,
                    "scope", tokenResponseScope
            ));
            sendJson(exchange, 200, json);
            return;
        }
        if ("/2/users/me".equals(path)) {
            sendJson(exchange, 200, "{\"data\":{\"id\":\"1001\",\"username\":\"alice\",\"name\":\"Alice\",\"verified\":true}}");
            return;
        }
        if ("/2/media/upload/initialize".equals(path)) {
            int seq = 9000 + mediaInitCalls.incrementAndGet();
            sendJson(exchange, 202, "{\"data\":{\"id\":\"media_" + seq + "\"}}");
            return;
        }
        if (path.startsWith("/2/media/upload/") && path.endsWith("/append")) {
            mediaAppendCalls.incrementAndGet();
            sendJson(exchange, 204, "");
            return;
        }
        if (path.startsWith("/2/media/upload/") && path.endsWith("/finalize")) {
            mediaFinalizeCalls.incrementAndGet();
            String[] parts = path.split("/");
            String mediaId = parts.length >= 5 ? parts[4] : "media_9001";
            sendJson(exchange, 200, "{\"data\":{\"id\":\"" + mediaId + "\"}}");
            return;
        }
        if ("/2/tweets".equals(path)) {
            int seq = tweetPostCalls.incrementAndGet();
            tweetBodies.add(reqBody);
            sleepQuietly(tweetDelayMs);
            int status = tweetResponseStatus;
            if (status >= 400) {
                sendJson(exchange, status, "{\"detail\":\"mock upstream error " + status + "\"}");
                return;
            }
            sendJson(exchange, 201, "{\"data\":{\"id\":\"tweet_" + (1000 + seq) + "\"}}");
            return;
        }
        sendJson(exchange, 404, "{\"detail\":\"unmatched mock path: " + path + "\"}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0L) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
