package com.xops.hub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.CommunityApiController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetJob;
import com.xops.hub.model.ScheduledTweetModels.ScheduledTweetStatus;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.store.XOAuthSessionStore;
import com.xops.hub.store.XOAuthTokenStore;
import com.xops.hub.store.XOAuthTokenStore.StoredOAuthToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackendStorageSafetyTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    private XOpsProperties properties() {
        XOpsProperties properties = new XOpsProperties();
        properties.setDiskCacheDir(tempDir.toString());
        properties.setTokenEncryptionSecret("synthetic-test-secret");
        properties.setTimezone("Asia/Shanghai");
        properties.setSchedulerEnabled(false);
        return properties;
    }

    private AuthenticatedXUser user() {
        return new AuthenticatedXUser("1001", "alice", "Alice", "", false, Instant.ofEpochSecond(1000));
    }

    @Test
    void logoutAfterRestartRevokesEveryRestoredSiblingButKeepsAnotherLogin() {
        XOpsProperties props = properties();
        XOAuthSessionStore store = new XOAuthSessionStore(props, mapper);
        store.saveSession("original", user(), 1000);
        store.saveSession("independent", user(), 1000);
        store.rotateOnRestore("original", "sibling-a", user(), 1001);
        store.rotateOnRestore("original", "sibling-b", user(), 1002);
        assertNotNull(store.loadValid("sibling-a", 1003));
        assertNotNull(store.loadValid("sibling-b", 1003));
        new XOAuthSessionStore(props, mapper).revokeSession("sibling-a");
        assertNull(store.loadValid("original", 1003));
        assertNull(store.loadValid("sibling-a", 1003));
        assertNull(store.loadValid("sibling-b", 1003));
        assertNotNull(store.loadValid("independent", 1003));
    }

    @Test
    void cachedSessionObservesExternalRotationAndCannotExtendAliasLifetime() {
        XOpsProperties props = properties();
        XOAuthSessionStore reader = new XOAuthSessionStore(props, mapper);
        reader.saveSession("original", user(), 1000);
        assertNotNull(reader.loadValid("original", 1000));
        XOAuthSessionStore writer = new XOAuthSessionStore(props, mapper);
        writer.rotateOnRestore("original", "restored", user(), 1001);
        assertFalse(reader.touchIfStale("original", user(), 1002));
        assertNull(reader.loadValid("original", 1062));
        assertNotNull(reader.loadValid("restored", 1062));
    }

    @Test
    void revokedOriginalCannotCreateAnUntrackedRestoredSession() {
        XOAuthSessionStore store = new XOAuthSessionStore(properties(), mapper);
        store.saveSession("original", user(), 1000);
        store.revokeSession("original");
        assertThrows(IllegalStateException.class, () -> store.rotateOnRestore("original", "late-child", user(), 1001));
        assertNull(store.loadValid("late-child", 1002));
    }

    @Test
    void authenticatedRequestResumingAfterLogoutCannotRecreateRevokedCredentials() throws Exception {
        XOpsProperties props = properties();
        XOAuthSessionStore store = new XOAuthSessionStore(props, mapper);
        store.saveSession("pending-status", user(), 1000);
        CountDownLatch authenticated = new CountDownLatch(1);
        CountDownLatch logoutCompleted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var delayedStatus = executor.submit(() -> {
                AuthenticatedXUser capturedUser = store.loadValid("pending-status", 1001).toAuthenticatedXUser();
                authenticated.countDown();
                assertTrue(logoutCompleted.await(5, TimeUnit.SECONDS));
                return store.touchIfStale("pending-status", capturedUser, 1002);
            });
            assertTrue(authenticated.await(5, TimeUnit.SECONDS));
            store.revokeSession("pending-status");
            logoutCompleted.countDown();
            assertFalse(delayedStatus.get(5, TimeUnit.SECONDS));
            assertNull(store.loadValid("pending-status", 1003));
            assertNull(new XOAuthSessionStore(props, mapper).loadValid("pending-status", 1003));
        } finally {
            logoutCompleted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void onlyExplicitLoginOrRestoreCreatesCredentialsAndExistingSessionsStillRenew() {
        XOAuthSessionStore store = new XOAuthSessionStore(properties(), mapper);
        assertFalse(store.touchIfStale("login", user(), 1000));
        assertNull(store.loadValid("login", 1000));
        store.saveSession("login", user(), 1000);
        assertNotNull(store.loadValid("login", 1000));
        assertFalse(store.touchIfStale("login", user(), 1001));
        long renewalTime = 1000 + XOAuthSessionStore.SLIDE_THRESHOLD_SECONDS + 1;
        assertTrue(store.touchIfStale("login", user(), renewalTime));
        assertEquals(renewalTime + XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS,
                store.loadValid("login", renewalTime).expiresAtEpochSec());
        store.rotateOnRestore("login", "restored", user(), renewalTime + 1);
        assertEquals(user(), store.loadValid("restored", renewalTime + 2).toAuthenticatedXUser());
        assertFalse(store.touchIfStale("login", user(), renewalTime + 2));
        long restoredRenewal = renewalTime + XOAuthSessionStore.SLIDE_THRESHOLD_SECONDS + 2;
        assertTrue(store.touchIfStale("restored", user(), restoredRenewal));
        assertEquals(restoredRenewal + XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS,
                store.loadValid("restored", restoredRenewal).expiresAtEpochSec());
    }

    @Test
    void tokenCacheDetectsAtomicReplacementWithEqualTimestampAndSize() throws Exception {
        XOpsProperties props = properties();
        XOAuthTokenStore reader = new XOAuthTokenStore(props, mapper);
        reader.save(new StoredOAuthToken("1001", "alice", "token-aa", "refresh-aa", 5000, "tweet.write", 1000));
        assertEquals("token-aa", reader.load("1001").accessToken());
        Path tokenFile = tempDir.resolve("users/by-id/1001/oauth_token_v1.enc");
        var oldTime = Files.getLastModifiedTime(tokenFile);
        long oldSize = Files.size(tokenFile);
        new XOAuthTokenStore(props, mapper).save(
                new StoredOAuthToken("1001", "alice", "token-bb", "refresh-bb", 5000, "tweet.write", 1000));
        assertEquals(oldSize, Files.size(tokenFile));
        Files.setLastModifiedTime(tokenFile, oldTime);
        assertEquals("token-bb", reader.load("1001").accessToken());
    }

    @Test
    void copiedTokenNeverAuthenticatesAnotherAccount() throws Exception {
        XOAuthTokenStore store = new XOAuthTokenStore(properties(), mapper);
        store.save(new StoredOAuthToken("1001", "alice", "token-aa", "", 5000, "tweet.write", 1000));
        Path other = tempDir.resolve("users/by-id/1002/oauth_token_v1.enc");
        Files.createDirectories(other.getParent());
        Files.copy(tempDir.resolve("users/by-id/1001/oauth_token_v1.enc"), other);
        assertNull(store.load("1002"));
    }

    private XOAuthService oauth() {
        XOAuthService oauth = mock(XOAuthService.class);
        when(oauth.getTokenCapability(anyString())).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(oauth.getValidWriteAccessToken(anyString(), anyBoolean())).thenReturn("synthetic-token");
        return oauth;
    }

    private XPublishClient publisher() {
        XPublishClient client = mock(XPublishClient.class);
        when(client.createTweet(anyString(), anyString(), anyList())).thenReturn("tweet-1");
        return client;
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-15T01:00:00Z").toEpochMilli());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
        void advance(long millis) { now.addAndGet(millis); }
    }

    private ScheduledTweetJob create(ScheduledTweetService service, MutableClock clock, String text, long delay) {
        return service.createScheduledTweet("1001", "alice", text, clock.millis() + delay, text, List.of());
    }

    @Test
    void dailyPublishAllowanceSurvivesHistoryDeletionAndRestart() {
        XOpsProperties props = properties();
        props.setDailyTweetPublishLimitPerUser(1);
        MutableClock clock = new MutableClock();
        XOAuthService oauth = oauth();
        XPublishClient client = publisher();
        ScheduledTweetService service = new ScheduledTweetService(props, mapper, oauth, client, clock);
        try {
            ScheduledTweetJob job = create(service, clock, "first", 20_000);
            clock.advance(21_000);
            service.processDueJobsForAccount("1001");
            assertEquals(ScheduledTweetStatus.PUBLISHED, service.listJobs("1001", "alice").items().get(0).status());
            service.deleteJob("1001", job.id());
        } finally { service.shutdown(); }
        ScheduledTweetService restarted = new ScheduledTweetService(props, mapper, oauth, client, clock);
        try {
            XDataProviderException error = assertThrows(XDataProviderException.class,
                    () -> create(restarted, clock, "second", 20_000));
            assertEquals(429, error.getStatusCode());
            verify(client, times(1)).createTweet(anyString(), anyString(), anyList());
        } finally { restarted.shutdown(); }
    }

    @Test
    void concurrentDispatchCannotSpendTheSameLastDailyAllowanceTwice() throws Exception {
        XOpsProperties props = properties();
        props.setDailyTweetPublishLimitPerUser(1);
        props.setScheduleGraceWindowSeconds(7200);
        MutableClock clock = new MutableClock();
        XPublishClient client = publisher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.createTweet(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return "tweet-1";
        });
        ScheduledTweetService service = new ScheduledTweetService(props, mapper, oauth(), client, clock);
        var executor = Executors.newFixedThreadPool(2);
        try {
            create(service, clock, "first", 20_000);
            create(service, clock, "second", 3_620_000);
            clock.advance(3_621_000);
            var first = executor.submit(() -> service.processDueJobsForAccount("1001"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var concurrent = executor.submit(() -> service.processDueJobsForAccount("1001"));
            concurrent.get(3, TimeUnit.SECONDS);
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            verify(client, times(1)).createTweet(anyString(), anyString(), anyList());
            assertEquals(1L, service.listJobs("1001", "alice").items().stream()
                    .filter(job -> job.status() == ScheduledTweetStatus.PUBLISHED).count());
        } finally {
            release.countDown(); executor.shutdownNow(); service.shutdown();
        }
    }

    @Test
    void corruptOrForeignQueueCannotBeReplacedWithAnEmptyQueue() throws Exception {
        XOpsProperties props = properties();
        MutableClock clock = new MutableClock();
        ScheduledTweetService service = new ScheduledTweetService(props, mapper, oauth(), publisher(), clock);
        Path queue = tempDir.resolve("users/by-id/1001/scheduled_tweets_v1.json");
        try {
            create(service, clock, "first", 20_000);
            String original = Files.readString(queue);
            Files.writeString(queue, "{broken");
            assertDoesNotThrow(service::recoverAllAccountsOnStartup);
            assertThrows(IllegalStateException.class, () -> service.listJobs("1001", "alice"));
            assertThrows(IllegalStateException.class, () -> create(service, clock, "second", 3_620_000));
            assertEquals("{broken", Files.readString(queue));
            Files.writeString(queue, original.replace("\"accountId\":\"1001\"", "\"accountId\":\"1002\""));
            assertThrows(IllegalStateException.class, () -> service.listJobs("1001", "alice"));
        } finally { service.shutdown(); }
    }

    @Test
    void successfulRemotePostWithFailedLocalCheckpointRequiresUncertainDeliveryConfirmation() {
        XOpsProperties props = properties();
        MutableClock clock = new MutableClock();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                byte[] bytes = super.writeValueAsBytes(value);
                if (new String(bytes, StandardCharsets.UTF_8).contains("\"status\":\"PUBLISHED\"")
                        && failOnce.compareAndSet(true, false)) {
                    throw com.fasterxml.jackson.databind.JsonMappingException.fromUnexpectedIOE(
                            new java.io.IOException("synthetic disk failure"));
                }
                return bytes;
            }
        };
        XPublishClient client = publisher();
        ScheduledTweetService service = new ScheduledTweetService(props, failingMapper, oauth(), client, clock);
        try {
            create(service, clock, "first", 20_000);
            clock.advance(21_000);
            service.processDueJobsForAccount("1001");
            var job = service.listJobs("1001", "alice").items().get(0);
            assertEquals(ScheduledTweetStatus.OVERDUE_CONFIRM_REQUIRED, job.status());
            assertTrue(job.uncertainDelivery());
            service.processDueJobsForAccount("1001");
            verify(client, times(1)).createTweet(anyString(), anyString(), anyList());
        } finally { service.shutdown(); }
    }

    @Test
    void oversizedIdempotencyKeyIsRejectedBeforeAnyQueueIsWritten() {
        MutableClock clock = new MutableClock();
        ScheduledTweetService service = new ScheduledTweetService(properties(), mapper, oauth(), publisher(), clock);
        try {
            assertThrows(IllegalArgumentException.class, () -> service.createScheduledTweet(
                    "1001", "alice", "text", clock.millis() + 20_000, "x".repeat(129), List.of()));
            assertFalse(Files.exists(tempDir.resolve("users/by-id/1001/scheduled_tweets_v1.json")));
        } finally { service.shutdown(); }
    }

    @Test
    void fiftyUsersCanContinueTwentyBatchesBehindOneNatWhileOneUserStillStopsAtThirty() throws Exception {
        XOpsProperties props = properties();
        assertEquals(50, props.getMaxConcurrentUsers());
        CommunityHubService service = new CommunityHubService(props, null, null, null, mapper);
        var admission = CommunityHubService.class.getDeclaredMethod("checkLiveSyncRateLimit", String.class, String.class);
        admission.setAccessible(true);
        assertDoesNotThrow(() -> {
            for (int batch = 0; batch < 20; batch++) {
                for (int user = 0; user < 50; user++) {
                    admission.invoke(service, "nat_user_" + user, "192.0.2.10");
                }
            }
        }, "50 users with 20 incremental batches must fit the shared NAT allowance");
        for (int attempt = 0; attempt < 30; attempt++) {
            admission.invoke(service, "single_user", "192.0.2.11");
        }
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> admission.invoke(service, "single_user", "192.0.2.11"));
        XDataProviderException cause = assertInstanceOf(XDataProviderException.class, error.getCause());
        assertEquals(429, cause.getStatusCode());
        assertTrue(cause.getMessage().contains("当前账号"), "The per-account limit must remain independent of shared IP capacity");
    }

    @Test
    void publicHealthDoesNotExposeOperationalTrafficOrSpend() {
        CommunityApiController controller = new CommunityApiController(properties(), mock(CommunityHubService.class));
        assertEquals(Set.of("status", "timestamp"), controller.getHealth().keySet());
    }
}
