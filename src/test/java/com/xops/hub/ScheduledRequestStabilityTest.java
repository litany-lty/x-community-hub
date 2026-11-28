package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.ScheduledTweetController;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.store.XOAuthTokenStore;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.ScheduledTweetService.BatchTweetDraftInput;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.assistant.AssistantStore;
import com.xops.hub.model.ScheduledTweetModels.AssistantSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScheduledRequestStabilityTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private MutableClock clock;
    private XOpsProperties properties;
    private XPublishClient publisher;
    private ScheduledTweetService service;

    @BeforeEach
    void setUp() {
        properties = new XOpsProperties();
        properties.setDiskCacheDir(tempDir.toString());
        properties.setTimezone("Asia/Shanghai");
        properties.setSchedulerEnabled(false);
        XOAuthService oauth = mock(XOAuthService.class);
        when(oauth.getTokenCapability(anyString())).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(oauth.getValidWriteAccessToken(anyString(), anyBoolean())).thenReturn("synthetic-access-token");
        clock = new MutableClock();
        publisher = mock(XPublishClient.class);
        when(publisher.createTweet(anyString(), anyString(), anyList())).thenReturn("synthetic-tweet-id");
        service = new ScheduledTweetService(properties, mapper, oauth, publisher, clock);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void malformedJsonAndWrongJsonFieldTypeReturn400WithoutLeakingPayload() throws Exception {
        ScheduledTweetService mockService = mock(ScheduledTweetService.class);
        MockMvc mvc = mvc(mockService);
        for (String body : List.of("{\"text\":\"private-body\",\"scheduledAtEpochMs\":",
                "{\"scheduledAtEpochMs\":\"private-invalid-time\"}")) {
            mvc.perform(post("/api/scheduled-tweets/job-1/reschedule")
                            .session(session()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("请求内容格式不正确，请检查发布时间等字段后重试"));
        }
        verifyNoInteractions(mockService);
    }

    @Test
    void invalidMultipartTimeReturns400BeforeCreatingAnyJob() throws Exception {
        ScheduledTweetService mockService = mock(ScheduledTweetService.class);
        mvc(mockService).perform(multipart("/api/scheduled-tweets")
                        .session(session()).param("text", "body").param("scheduledAtEpochMs", "private-invalid-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("发布时间格式不正确，请重新选择时间"));
        verifyNoInteractions(mockService);
    }

    @Test
    void textOnlySingleAndBatchJsonCreateScheduleWithoutMultipart() throws Exception {
        MockMvc mvc = mvc(service);
        long t1 = clock.millis() + 120_000L;
        long t2 = t1 + 3_600_000L;
        long t3 = t2 + 3_600_000L;

        mvc.perform(post("/api/scheduled-tweets")
                        .session(session())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "text", "json single schedule",
                                "scheduledAtEpochMs", t1,
                                "clientRequestId", "json-single-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("json single schedule"))
                .andExpect(jsonPath("$.clientRequestId").value("json-single-1"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        mvc.perform(post("/api/scheduled-tweets/batch")
                        .session(session())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("text", "batch-a", "scheduledAtEpochMs", t2, "clientRequestId", "json-batch-0"),
                                Map.of("text", "batch-b", "scheduledAtEpochMs", t3, "clientRequestId", "json-batch-1")
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(2));

        mvc.perform(post("/api/scheduled-tweets")
                        .session(session())
                        .contentType("application/json")
                        .content("{\"text\":\"missing-time\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));

        assertEquals(3, service.listJobs("1001", "alice").items().size());
    }

    @Test
    void createRejectsTimeThatExpiredWhileWaitingForAccountLock() throws Exception {
        long candidate = clock.millis() + 20_000;
        Throwable error = rejectAfterLockDelay(() -> service.createScheduledTweet(
                "1001", "alice", "single", candidate, "single-1", List.of()));
        assertInstanceOf(IllegalArgumentException.class, error);
        assertTrue(error.getMessage().contains("15 秒"));
        assertTrue(service.listJobs("1001", "alice").items().isEmpty());
    }

    @Test
    void batchRejectsExpiredFirstItemWithoutWritingAnyOtherItem() throws Exception {
        long candidate = clock.millis() + 20_000;
        List<BatchTweetDraftInput> drafts = List.of(
                new BatchTweetDraftInput("first", candidate, "batch-0", List.of()),
                new BatchTweetDraftInput("second", candidate + 3_600_000, "batch-1", List.of()));
        Throwable error = rejectAfterLockDelay(() -> service.createBatchScheduledTweets("1001", "alice", drafts));
        assertInstanceOf(IllegalArgumentException.class, error);
        assertTrue(error.getMessage().contains("第 1 条"));
        assertTrue(service.listJobs("1001", "alice").items().isEmpty());
    }

    @Test
    void rescheduleRejectsExpiredTimeAndPreservesOriginalJob() throws Exception {
        ScheduledTweetJob original = service.createScheduledTweet("1001", "alice", "original", clock.millis() + 3_600_000,
                "reschedule-1", List.of());
        long candidate = clock.millis() + 20_000;
        Throwable error = rejectAfterLockDelay(() -> service.rescheduleJob("1001", "alice", original.id(), candidate, "changed"));
        assertInstanceOf(IllegalArgumentException.class, error);
        ScheduledTweetJob unchanged = service.listJobs("1001", "alice").items().get(0);
        assertEquals(original.scheduledAtEpochMs(), unchanged.scheduledAtEpochMs());
        assertEquals(original.text(), unchanged.text());
        assertEquals(original.status(), unchanged.status());
    }

    @Test
    void exhaustedBudgetRejectsMovingIntoTodayWithoutChangingSavedJob() throws Exception {
        properties.setDailyTweetPublishLimitPerUser(1);
        ScheduledTweetJob original = service.createScheduledTweet("1001", "alice", "tomorrow",
                clock.millis() + 86_400_000, "move-into-today", List.of());
        exhaustDailyAllowance();

        XDataProviderException error = assertThrows(XDataProviderException.class, () -> service.rescheduleJob(
                "1001", "alice", original.id(), clock.millis() + 3_600_000, "changed"));
        assertEquals(429, error.getStatusCode());
        assertEquals(original, service.listJobs("1001", "alice").items().get(0));
        assertDoesNotThrow(() -> service.rescheduleJob("1001", "alice", original.id(),
                clock.millis() + 90_000_000, "later tomorrow"));
        verifyNoInteractions(publisher);
    }

    @Test
    void existingTodayTaskCanBeEditedWithoutDoubleCountingAllowance() throws Exception {
        properties.setDailyTweetPublishLimitPerUser(1);
        ScheduledTweetJob original = service.createScheduledTweet("1001", "alice", "today",
                clock.millis() + 3_600_000, "edit-today", List.of());
        Path budget = exhaustDailyAllowance();
        String savedBudget = Files.readString(budget);

        assertDoesNotThrow(() -> service.rescheduleJob("1001", "alice", original.id(),
                original.scheduledAtEpochMs(), original.text()));
        ScheduledTweetJob edited = service.rescheduleJob("1001", "alice", original.id(),
                original.scheduledAtEpochMs() + 3_600_000, "edited today");
        assertEquals("edited today", edited.text());
        assertEquals(savedBudget, Files.readString(budget));
        verifyNoInteractions(publisher);
    }

    @Test
    void exhaustedBudgetRejectsReactivatingCancelledTodayTask() throws Exception {
        properties.setDailyTweetPublishLimitPerUser(1);
        ScheduledTweetJob original = service.createScheduledTweet("1001", "alice", "today",
                clock.millis() + 3_600_000, "reactivate-today", List.of());
        ScheduledTweetJob cancelled = service.cancelJob("1001", original.id());
        exhaustDailyAllowance();

        XDataProviderException error = assertThrows(XDataProviderException.class, () -> service.rescheduleJob(
                "1001", "alice", original.id(), original.scheduledAtEpochMs(), original.text()));
        assertEquals(429, error.getStatusCode());
        assertEquals(cancelled, service.listJobs("1001", "alice").items().get(0));
        verifyNoInteractions(publisher);
    }

    @Test
    void dispatchRechecksGraceWindowAfterWaitingForAccountLock() throws Exception {
        properties.setScheduleGraceWindowSeconds(10);
        ScheduledTweetJob original = service.createScheduledTweet("1001", "alice", "queued",
                clock.millis() + 20_000, "late-dispatch", List.of());
        clock.advance(20_000);

        afterLockDelay(() -> { service.processDueJobsForAccount("1001"); return null; });

        ScheduledTweetJob current = service.listJobs("1001", "alice").items().get(0);
        assertEquals(original.id(), current.id());
        assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, current.status());
        assertEquals(0, current.attemptCount());
        assertFalse(Files.exists(tempDir.resolve("users/by-id/1001/scheduled_publish_budget_v1.json")));
        verifyNoInteractions(publisher);
    }

    @Test
    void dispatchStillPublishesAtExactGraceBoundary() {
        properties.setScheduleGraceWindowSeconds(10);
        service.createScheduledTweet("1001", "alice", "on boundary", clock.millis() + 20_000,
                "grace-boundary", List.of());
        clock.advance(30_000);

        service.processDueJobsForAccount("1001");

        ScheduledTweetJob current = service.listJobs("1001", "alice").items().get(0);
        assertEquals(ScheduledTweetStatus.PUBLISHED, current.status());
        assertEquals(1, current.attemptCount());
        verify(publisher, times(1)).createTweet(anyString(), anyString(), anyList());
    }

    @Test void initialTickWaitsForStartupRecoveryBeforeDispatch() throws Exception {
        var guarded = spy(service);
        doReturn(true).when(guarded).isScheduledPublishRuntimeEnabled();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(guarded).recoverAllAccountsOnStartup();
        guarded.createScheduledTweet("1001", "alice", "arrived", clock.millis() + 20_000, "boot-tick", List.of());
        clock.advance(20_000);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var startup = executor.submit(guarded::onApplicationReady);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var tickStarted = new CountDownLatch(1);
            var tick = executor.submit(() -> { tickStarted.countDown(); guarded.runScheduledTick(); });
            assertTrue(tickStarted.await(5, TimeUnit.SECONDS));
            verify(publisher, after(150).never()).createTweet(anyString(), anyString(), anyList());
            resume.countDown();
            startup.get(5, TimeUnit.SECONDS);
            tick.get(5, TimeUnit.SECONDS);
            verify(publisher, timeout(5000).times(1)).createTweet(anyString(), anyString(), anyList());
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test void restartedUnconfirmedSendKeepsOriginalDispatchTimeForCooldown() throws Exception {
        service.createScheduledTweet("1001", "alice", "sent", clock.millis() + 20_000, "restart-cooldown", List.of());
        long sentAt = clock.millis() + 20_100;
        Path file = tempDir.resolve("users/by-id/1001/scheduled_tweets_v1.json");
        var queue = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(file.toFile());
        var row = (com.fasterxml.jackson.databind.node.ObjectNode) queue.path("jobs").get(0);
        row.put("status", "SENDING").put("sendingStartedAtEpochMs", sentAt - 100).put("updatedAtEpochMs", sentAt)
                .put("postRequestDispatched", true).remove("postRequestDispatchedAtEpochMs");
        mapper.writeValue(file.toFile(), queue);
        clock.advance(75_000);
        service.recoverAllAccountsOnStartup();
        var recovered = service.listJobs("1001", "alice").items().get(0);
        assertTrue(recovered.uncertainDelivery());
        assertEquals(sentAt, service.latestAccountPublishEpochMs("1001", "alice"));
        service.cancelJob("1001", recovered.id());
        assertEquals(sentAt, service.latestAccountPublishEpochMs("1001", "alice"));
        verifyNoInteractions(publisher);
    }

    @Test
    void aLostReceiptRequiresConfirmationEvenAfterCancellingAndTryingToReschedule() {
        when(publisher.createTweet(anyString(), anyString(), anyList()))
                .thenThrow(new XPublishClient.XPublishException(504, "missing receipt", true));
        var job = service.createScheduledTweet("1001", "alice", "already sent?", clock.millis() + 60_000,
                "lost-receipt", List.of());
        clock.advance(60_000);
        service.processDueJobsForAccount("1001");
        var uncertain = service.listJobs("1001", "alice").items().get(0);
        assertTrue(uncertain.uncertainDelivery());
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.rescheduleJob(
                "1001", "alice", job.id(), clock.millis() + 60_000, null)).getStatusCode());
        assertEquals(uncertain, service.listJobs("1001", "alice").items().get(0));
        assertTrue(service.cancelJob("1001", job.id()).uncertainDelivery());
        assertEquals(409, assertThrows(XDataProviderException.class, () -> service.rescheduleJob(
                "1001", "alice", job.id(), clock.millis() + 60_000, null)).getStatusCode());
        clock.advance(60_000);
        service.processDueJobsForAccount("1001");
        verify(publisher, times(1)).createTweet(anyString(), anyString(), anyList());
    }

    @Test
    void queueMutationsResolveAssistantOwnershipBeforeLockingTheAccount() {
        var store = mock(AssistantStore.class);
        Object lock = ReflectionTestUtils.invokeMethod(service, "accountLock", "1001");
        when(store.isOwner("1001")).thenAnswer(call -> {
            assertFalse(Thread.holdsLock(lock), "The assistant store can already be holding its transaction while reading the queue");
            return true;
        });
        service.setAssistantStore(store);
        var source = new AssistantSource("intent", "draft", 1, "topic", 1, "research", "digest", null);
        var job = service.createScheduledTweet("1001", "elidlicom", "draft", clock.millis() + 60_000,
                "intent", List.of(), source);
        var next = new AssistantSource("intent", "draft", 2, "topic", 1, "research", "digest", "edit-v2");
        var updated = service.updateAssistantJob("1001", "elidlicom", job.id(), job.updatedAtEpochMs(), "edited", next);
        assertEquals("edited", updated.text());
        assertEquals(updated, service.updateAssistantJob("1001", "elidlicom", job.id(),
                job.updatedAtEpochMs(), "edited", next));
    }

    @Test
    void legacyCancellationOrManualReschedulingCannotEraseAnUnknownPriorAttempt() throws Exception {
        long due = clock.millis() + 60_000;
        var cancelled = service.createScheduledTweet(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "cancelled", due, "old-cancel", List.of());
        var moved = service.createScheduledTweet(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "moved", due, "old-move", List.of());
        var safeRetry = service.createScheduledTweet(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "retry", due, "old-retry", List.of());
        Path file = tempDir.resolve("users/by-id/" + AuthorIdentity.ARCHIVE_ACCOUNT_ID + "/scheduled_tweets_v1.json");
        var old = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(file.toFile());
        old.put("version", 1);
        for (var record : old.withArray("jobs")) {
            var row = (com.fasterxml.jackson.databind.node.ObjectNode) record;
            row.put("attemptCount", 1);
            if (cancelled.id().equals(row.path("id").asText())) row.put("status", "CANCELLED");
            if (safeRetry.id().equals(row.path("id").asText())) row.put("lastError", "明确拒绝，十分钟后重试");
        }
        mapper.writeValue(file.toFile(), old);
        var recovered = service.listJobs(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom").items();
        var oldCancelled = recovered.stream().filter(j -> j.id().equals(cancelled.id())).findFirst().orElseThrow();
        assertTrue(oldCancelled.uncertainDelivery());
        assertEquals(ScheduledTweetStatus.CANCELLED, oldCancelled.status());
        var oldMoved = recovered.stream().filter(j -> j.id().equals(moved.id())).findFirst().orElseThrow();
        assertTrue(oldMoved.uncertainDelivery());
        assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, oldMoved.status());
        assertEquals(ScheduledTweetStatus.SCHEDULED, recovered.stream()
                .filter(j -> j.id().equals(safeRetry.id())).findFirst().orElseThrow().status());
        assertEquals(recovered, service.listJobs(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom").items());
    }

    @Test
    void authorBatchRejectsIdenticalDraftsAtomicallyWhileAllowingDistinctSimultaneousPosts() throws Exception {
        MockMvc mvc = mvc(service);
        var author = session();
        author.setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", "Author", "", false, Instant.EPOCH));
        long due = clock.millis() + 60_000;
        List<Map<String, Object>> items = List.of(
                Map.of("text", "same", "scheduledAtEpochMs", due, "clientRequestId", "batch_0"),
                Map.of("text", " same ", "scheduledAtEpochMs", due, "clientRequestId", "batch_1"));
        mvc.perform(post("/api/scheduled-tweets/batch").session(author).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("items", items))))
                .andExpect(status().isConflict());
        assertTrue(service.listJobs(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom").items().isEmpty());
        var drafts = List.of(new BatchTweetDraftInput("first", due, "batch_0", List.of()),
                new BatchTweetDraftInput("second", due, "batch_1", List.of()));
        assertThrows(XDataProviderException.class, () -> service.createBatchScheduledTweets("1001", "elidlicom", drafts));
        var created = service.createBatchScheduledTweets(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", drafts);
        assertEquals(2, created.createdCount());
        assertEquals(created, service.createBatchScheduledTweets(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom", drafts));
    }

    @Test
    void readingFailedMediaJobPreservesCurrentTextAndRefreshAuthorization() {
        properties.setTokenEncryptionSecret("synthetic-scheduling-regression-only");
        var tokenStore = new XOAuthTokenStore(properties, mapper);
        long expiry = Instant.now().getEpochSecond() + 7200;
        long issued = clock.instant().getEpochSecond() - 300;
        var pool = new SharedApiExecutor(properties);
        var oauth = new XOAuthService(properties, mapper, pool);
        var actual = new ScheduledTweetService(properties, mapper, oauth, publisher, clock);
        try {
            tokenStore.save(new XOAuthTokenStore.StoredOAuthToken("1001", "elidlicom", "a", "r",
                    expiry, XOAuthService.PUBLISH_OAUTH_SCOPE, issued));
            var media = new ScheduledTweetService.MediaUploadInput("image.gif", "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            actual.createScheduledTweet("1001", "elidlicom", "image", clock.millis() + 60_000, "media", List.of(media));
            tokenStore.save(new XOAuthTokenStore.StoredOAuthToken("1001", "elidlicom", "b", "r2",
                    expiry, "tweet.write offline.access", issued + 1));
            clock.advance(60_000);
            actual.processDueJobsForAccount("1001");
            var result = actual.listJobs("1001", "elidlicom");
            assertEquals(ScheduledTweetStatus.FAILED, result.items().get(0).status());
            assertTrue(result.writeAuthorized());
            assertTrue(result.offlineRefreshable());
            assertFalse(result.mediaWriteAuthorized());
            assertEquals("b", oauth.getValidWriteAccessToken("1001", false));
        } finally {
            actual.shutdown();
            pool.shutdown();
        }
    }

    @Test
    void tweetWaitingInTheSharedPoolHasNoDispatchMarkerAndCanRetryAfterAdmissionTimeout() throws Exception {
        properties.setApiPoolCoreSize(1);
        properties.setApiPoolMaxSize(1);
        properties.setApiPoolQueueCapacity(1);
        properties.setApiQueueTimeoutMs(1500);
        properties.setOfficialBaseUrl("http://127.0.0.1:1/2");
        var pool = new SharedApiExecutor(properties);
        var oauth = mock(XOAuthService.class);
        when(oauth.getTokenCapability("1001")).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(oauth.getValidWriteAccessToken("1001", false)).thenReturn("synthetic");
        var actual = new ScheduledTweetService(properties, mapper, oauth, new XPublishClient(properties, mapper, pool), clock);
        var caller = Executors.newSingleThreadExecutor();
        var active = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            pool.submitAsync(() -> { active.countDown(); release.await(); return null; });
            assertTrue(active.await(3, TimeUnit.SECONDS));
            actual.createScheduledTweet("1001", "elidlicom", "queued", clock.millis() + 60_000, "wait", List.of());
            clock.advance(60_000);
            var dispatch = caller.submit(() -> actual.processDueJobsForAccount("1001"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((int) pool.snapshotMetrics().get("queuedTasks") == 0 && !dispatch.isDone() && System.nanoTime() < deadline) Thread.sleep(5);
            var waiting = actual.listJobs("1001", "elidlicom").items().get(0);
            assertEquals(ScheduledTweetStatus.SENDING, waiting.status());
            assertFalse(waiting.postRequestDispatched());
            dispatch.get(4, TimeUnit.SECONDS);
            var retry = actual.listJobs("1001", "elidlicom").items().get(0);
            assertEquals(ScheduledTweetStatus.SCHEDULED, retry.status());
            assertFalse(retry.uncertainDelivery());
            assertEquals(clock.millis() + 600_000, retry.scheduledAtEpochMs());
            assertEquals(1L, pool.snapshotMetrics().get("queueTimedOutTasks"));
        } finally {
            release.countDown();
            caller.shutdownNow();
            actual.shutdown();
            pool.shutdown();
        }
    }

    @Test
    void explicitAndLegacyTrailingUrlQuoteTweetsDispatchWithQuoteTweetId() throws Exception {
        when(publisher.createTweet(anyString(), anyString(), anyList(), anyString())).thenReturn("quoted-pub-id");
        MockMvc mvc = mvc(service);
        long t1 = clock.millis() + 60_000L;
        long t2 = t1 + 3_600_000L;

        mvc.perform(post("/api/scheduled-tweets")
                        .session(session())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "text", "我的引用点评",
                                "scheduledAtEpochMs", t1,
                                "clientRequestId", "quote-explicit-1",
                                "quoteTweetId", "1920001",
                                "quoteTweetUrl", "https://x.com/sama/status/1920001"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteTweetId").value("1920001"));

        service.createScheduledTweet("1001", "alice",
                "旧格式带链接附文\nhttps://x.com/sama/status/1920002", t2, "quote-legacy-2", List.of());
        Path oldQueue = tempDir.resolve("users/by-id/1001/scheduled_tweets_v1.json");
        var persisted = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(oldQueue.toFile());
        mapper.writeValue(oldQueue.toFile(), persisted.put("version", 2));

        clock.advance(65_000L);
        service.processDueJobsForAccount("1001");
        verify(publisher, times(1)).createTweet("synthetic-access-token", "我的引用点评", List.of(), "1920001");
        var receipt = service.listJobs("1001", "alice").items().stream()
                .filter(j -> j.status() == ScheduledTweetStatus.PUBLISHED).findFirst().orElseThrow().publicationReceipt();
        assertEquals(XPublishClient.PublicationMode.QUOTE, receipt.mode());
        assertEquals("1920001", receipt.quoteTweetId());
        assertEquals("我的引用点评", receipt.text());

        clock.advance(3_600_000L);
        service.processDueJobsForAccount("1001");
        verify(publisher, times(1)).createTweet("synthetic-access-token", "旧格式带链接附文", List.of(), "1920002");
    }

    @Test
    void scheduledFallbackUsesTheFullTargetIdAndExplicitSource() {
        String body = "旁证 https://x.com/other/status/10155";
        String source = "https://x.com/original/status/101";
        String actual = ReflectionTestUtils.invokeMethod(ScheduledTweetService.class, "resolveEffectiveQuoteUrl", body, "101", source);
        assertEquals(source, actual);
        String generic = ReflectionTestUtils.invokeMethod(ScheduledTweetService.class, "resolveEffectiveQuoteUrl", body, "101", null);
        assertEquals("https://x.com/i/status/101", generic);
    }

    private Path exhaustDailyAllowance() throws Exception {
        Path budget = tempDir.resolve("users/by-id/1001/scheduled_publish_budget_v1.json");
        mapper.writeValue(budget.toFile(), Map.of("accountId", "1001", "localDate", "2026-11-16", "count", 1));
        return budget;
    }

    private Throwable rejectAfterLockDelay(Supplier<?> operation) throws Exception {
        return assertThrows(ExecutionException.class, () -> afterLockDelay(operation)).getCause();
    }

    private Object afterLockDelay(Supplier<?> operation) throws Exception {
        Object lock = ReflectionTestUtils.invokeMethod(service, "accountLock", "1001");
        assertNotNull(lock);
        AtomicReference<Thread> worker = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> result;
            synchronized (lock) {
                result = executor.submit(() -> {
                    worker.set(Thread.currentThread());
                    return operation.get();
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((worker.get() == null || worker.get().getState() != Thread.State.BLOCKED)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertNotNull(worker.get());
                assertEquals(Thread.State.BLOCKED, worker.get().getState(), "request must actually be waiting on the account lock");
                clock.advance(40_000);
            }
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private MockMvc mvc(ScheduledTweetService mockService) {
        return MockMvcBuilders.standaloneSetup(new ScheduledTweetController(mockService, mapper))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser("1001", "alice", "Alice", "", false, Instant.ofEpochSecond(1000)));
        return session;
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-16T01:00:00Z").toEpochMilli());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
        void advance(long millis) { now.addAndGet(millis); }
    }
}
