package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xops.hub.assistant.AssistantStore;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.controller.CommunityApiController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.*;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerIdentityRoutingTest {
    private static final String ORIGINAL_AUTHOR_ID = "2095392916734976000";
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void restoringTheOldHandleOnlyBindingCannotTransferAuthorAccess() {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        Path assistant = dir.resolve("assistant");
        var before = new AssistantStore(assistant, mapper);
        before.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode()
                .put("accountId", "202").put("username", "elidlicom")));
        var restored = new AssistantStore(assistant, mapper);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), mock(TwitterApiIoProvider.class),
                mock(OfficialXApiV2Provider.class), mapper, null, restored);
        try {
            assertThat(hub.isAuthorAccount("202", "elidlicom")).isFalse();
            assertThat(hub.evaluateScheduledPublishAccess("202", "elidlicom").allowed()).isFalse();
            assertThat(hub.isAuthorAccount(ORIGINAL_AUTHOR_ID, "renamed")).isTrue();
            assertThat(AccountStore.retainsFullHistory("renamed", ORIGINAL_AUTHOR_ID, restored.boundOwnerId())).isTrue();
            assertThat(restored.tx(tx -> tx.get("meta", "owner")).path("accountId").asText()).isEqualTo("202");
            restored.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode()
                    .put("accountId", "404").put("username", "explicit").put("bindingSource", "EXPLICIT")));
            assertThat(hub.isAuthorAccount("404", "explicit")).isTrue();
            assertThat(hub.isAuthorAccount(ORIGINAL_AUTHOR_ID, "elidlicom")).isFalse();
        } finally {
            hub.stopBackgroundRefreshes();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void officialRefreshSpendsOnlyOneLookupWhenTheProfileConflictsWithTheSession() throws Exception {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setOfficialBearerToken("fixture");
        props.setDefaultProvider("X_OFFICIAL_V2");
        props.setOfficialBaseUrl("http://127.0.0.1:1/2");
        var pool = new SharedApiExecutor(props);
        var budget = new UpstreamBudgetGuard(props, true);
        var official = new OfficialXApiV2Provider(props, mapper, pool, budget);
        var http = mock(HttpClient.class);
        var wrongProfile = mock(HttpResponse.class);
        when(wrongProfile.statusCode()).thenReturn(200);
        when(wrongProfile.body()).thenReturn("{\"data\":{\"id\":\"202\",\"username\":\"elidlicom\"}}");
        when(http.send(any(HttpRequest.class), any())).thenReturn(wrongProfile);
        ReflectionTestUtils.setField(official, "httpClient", http);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), mock(TwitterApiIoProvider.class), official, mapper);
        try {
            assertThatThrownBy(() -> hub.analyzeWorkspaceBundle("elidlicom", ReportPeriod.HOURS_24, "X_OFFICIAL_V2",
                    true, "127.0.0.1", ORIGINAL_AUTHOR_ID, false)).isInstanceOf(XDataProviderException.class)
                    .hasMessageContaining("ID");
            verify(http).send(argThat(request -> request.uri().getPath().equals("/2/users/" + ORIGINAL_AUTHOR_ID)), any());
            verifyNoMoreInteractions(http);
            assertThat(budget.snapshotMetrics().get("totalBillableCalls")).isEqualTo(1L);
        } finally {
            hub.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    @Test
    void restoredAuthorSnapshotWinsOverFirstLoginWithReassignedHandle() throws Exception {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        var owners = new AssistantStore(dir.resolve("assistant"), mapper);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), mock(TwitterApiIoProvider.class),
                mock(OfficialXApiV2Provider.class), mapper, null, owners);
        try {
            var original = new XUserSummary(ORIGINAL_AUTHOR_ID, "elidlicom", "Author", "", "", false, 0, 0, 1, "");
            var stored = AccountStore.merge(null, original, Instant.now(), List.of(), List.of(), List.of(),
                    List.of(), null, Map.of(), true);
            AccountStore.write(dir.resolve("users/by-id/" + ORIGINAL_AUTHOR_ID + "/store_v" + AccountStore.VERSION + ".json.gz"), stored, mapper);
            var auth = new XOAuthController(props, null, null, hub);
            ReflectionTestUtils.invokeMethod(auth, "bindAssistantStore", owners);
            assertThat(status(auth, "202", "elidlicom")).isFalse();
            assertThat(hub.evaluateScheduledPublishAccess("202", "elidlicom").allowed()).isFalse();
            assertThat(status(auth, ORIGINAL_AUTHOR_ID, "renamed")).isTrue();
            assertThat(owners.boundOwnerId()).isEmpty();
            assertThat(hub.evaluateScheduledPublishAccess(ORIGINAL_AUTHOR_ID, "renamed").allowed()).isTrue();
        } finally {
            hub.stopBackgroundRefreshes();
        }
    }

    private static boolean status(XOAuthController auth, String id, String handle) {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser(id, handle, handle, "", true, Instant.now()));
        return auth.getStatus(request, new MockHttpServletResponse()).ownerAllPermissions();
    }

    @Test void oldSessionsCannotUndoTheHandleFromANewerOAuthCallbackOrReplayFormerOwnerJobs() throws Exception {
        var owners = new AssistantStore(dir, mapper);
        owners.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "202").put("username", "elidlicom"));
            tx.put("auto_repost", "123", mapper.createObjectNode().put("status", "FAILED").put("autoRetryAt", 100));
            tx.put("history_material", "456", mapper.createObjectNode().put("authorId", ORIGINAL_AUTHOR_ID).put("text", "preserved"));
            tx.put("style_sample", "unknown", mapper.createObjectNode().put("textRole", "AUTHOR").put("text", "older unverified sample"));
            tx.put("meta", "style", mapper.createObjectNode().put("profile", "saved explicit preference").put("editor", "OWNER"));
            return null;
        });
        var properties = new XOpsProperties();
        var oauth = mock(com.xops.hub.service.XOAuthService.class);
        var auth = new XOAuthController(properties, oauth, null, null);
        ReflectionTestUtils.invokeMethod(auth, "bindAssistantStore", owners);
        var now = Instant.now();
        var current = new AuthenticatedXUser(ORIGINAL_AUTHOR_ID, "current", "", "", false, now);
        when(oauth.handleCallbackResult("code", "state")).thenReturn(new com.xops.hub.service.XOAuthService.CallbackResult(
                current, com.xops.hub.service.XOAuthService.AuthPurpose.LOGIN, true, true, true));
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(XOAuthController.SESSION_OAUTH_STATES, List.of("state"));
        var response = new MockHttpServletResponse();
        auth.callback("code", "state", null, request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/?auth=ok");
        assertThat(owners.tx(tx -> tx.get("auto_repost", "123")).path("status").asText()).isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThat(owners.tx(tx -> tx.get("history_material", "456")).path("text").asText()).isEqualTo("preserved");
        assertThat(owners.tx(tx -> tx.get("style_sample", "unknown")).path("textRole").asText()).isEqualTo("LEGACY_UNVERIFIED");
        assertThat(owners.tx(tx -> tx.get("meta", "style")).path("profile").asText()).isEqualTo("saved explicit preference");
        when(oauth.getTokenCapability(ORIGINAL_AUTHOR_ID)).thenReturn(new com.xops.hub.service.XOAuthService.TokenCapability(true, true, true));
        assertThat(status(auth, ORIGINAL_AUTHOR_ID, "old")).isTrue();
        owners.observeOAuthOwner(new AuthenticatedXUser(ORIGINAL_AUTHOR_ID, "older", "", "", false, now.minusSeconds(5)));
        var owner = owners.tx(AssistantStore.Tx::ownerBinding);
        assertThat(owner.path("username").asText()).isEqualTo("current");
        assertThat(owner.path("usernameObservedAt").asLong()).isEqualTo(now.toEpochMilli());
        assertThat(owners.tx(tx -> tx.version("meta", "owner", 1)).path("accountId").asText()).isEqualTo("202");
    }

    @Test void anOrdinaryEligibleUserCannotDispatchARestoredAssistantQueue() {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        var owners = new AssistantStore(dir.resolve("assistant"), mapper);
        var oauth = mock(com.xops.hub.service.XOAuthService.class);
        when(oauth.getTokenCapability("202")).thenReturn(new com.xops.hub.service.XOAuthService.TokenCapability(true, true, true));
        var publish = mock(XPublishClient.class);
        var now = Instant.now();
        var oldQueue = new com.xops.hub.service.ScheduledTweetService(props, mapper, oauth, publish,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        owners.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT")
                .put("accountId", "202").put("username", "elidlicom")));
        oldQueue.setAssistantStore(owners);
        try {
            var source = new com.xops.hub.model.ScheduledTweetModels.AssistantSource("intent", "draft", 1, "topic", 1, "", "digest", "");
            oldQueue.createScheduledTweet("202", "elidlicom", "prepared by the former owner", now.toEpochMilli() + 30_000,
                    "intent", List.of(), source);
        } finally {
            oldQueue.shutdown();
        }
        // The old database stored only the ID and handle. Ordinary scheduling eligibility still holds.
        owners.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "202").put("username", "elidlicom")));
        var restored = new com.xops.hub.service.ScheduledTweetService(props, mapper, oauth, publish,
                java.time.Clock.fixed(now.plusSeconds(32), java.time.ZoneOffset.UTC));
        restored.setAssistantStore(new AssistantStore(dir.resolve("assistant"), mapper));
        try {
            restored.processDueJobsForAccount("202");
            assertThat(restored.assistantJobs("202")).singleElement().satisfies(entry ->
                    assertThat(entry.job().lastError()).contains("作者身份"));
            verify(oauth, never()).getValidWriteAccessToken(anyString(), anyBoolean());
            verifyNoInteractions(publish);
        } finally {
            restored.shutdown();
        }
    }

    @Test
    void theInMemoryLastPostUsesStableOwnerIdAcrossReassignedHandles() {
        var props = new XOpsProperties();
        var hub = new CommunityHubService(props, new DemoXDataProvider(), mock(TwitterApiIoProvider.class),
                mock(OfficialXApiV2Provider.class), mapper);
        var now = Instant.now();
        try {
            for (String id : List.of("101", "202")) {
                Instant published = now.minusSeconds(id.equals("101") ? 3600 : 60);
                var user = new XUserSummary(id, id.equals("101") ? "old_handle" : "current", "Author", "", "", false, 0, 0, 1, "");
                var post = new com.xops.hub.model.RawSnapshotModels.RawTweet("post-" + id, "post", published,
                        com.xops.hub.model.RawSnapshotModels.TweetKind.POST, "", null, List.of(), false, false,
                        0, 0, 0, 0, 0, false, "post", "");
                var account = AccountStore.merge(null, user, now, List.of(post), List.of(), List.of(), List.of(), null);
                ReflectionTestUtils.invokeMethod(hub, "putSnapshotCache", "TWITTERAPI_IO:id:" + id,
                        TwitterApiIoProvider.snapshotOf(account, "", false), null, 60L, now.plusSeconds(60), "");
            }
            assertThat(hub.latestCachedOwnerPostEpochMs("101", "current")).isEqualTo(now.minusSeconds(3600).getEpochSecond() * 1000L);
        } finally {
            hub.stopBackgroundRefreshes();
        }
    }

    @Test
    void aPrivilegedLookingHandleWithoutAStableIdDecisionCannotRunPaidWork() {
        assertThat(PaidCapabilityGate.withCaller("elidlicom", "202", PaidCapabilityGate::isCurrentCallerPrivileged)).isFalse();
        var clock = mock(java.time.Clock.class);
        when(clock.millis()).thenReturn(0L, PaidCapabilityGate.RETRY_INTERVAL_MS + 100L);
        var gate = new PaidCapabilityGate(clock);
        var action = mock(Runnable.class);
        gate.scheduleIdempotentRetry("former-handle", "x_tweet_create", "elidlicom", "202", "failed", action);
        gate.runDueRetries();
        verifyNoInteractions(action);
    }

    @Test
    void emptyOrConflictingHandleArchivesCannotChooseTheOwner() throws Exception {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        var owners = new AssistantStore(dir.resolve("assistant"), mapper);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), mock(TwitterApiIoProvider.class),
                mock(OfficialXApiV2Provider.class), mapper, null, owners);
        try {
            assertThat(hub.isAuthorAccount("202", "elidlicom")).isFalse();
            assertThat(owners.authorize(new AuthenticatedXUser("202", "elidlicom", "", "", true, Instant.now()))).isFalse();
            var reusedHandle = new XUserSummary("303", "elidlicom", "New user", "", "", false, 0, 0, 1, "");
            Files.writeString(dir.resolve("author_profile_elidlicom.json"), mapper.writeValueAsString(
                    new CommunityHubService.CachedAuthorProfile(reusedHandle, Instant.now().plusSeconds(3600))));
            assertThat(hub.isAuthorAccount("303", "elidlicom")).isFalse();
            assertThat(hub.isAuthorAccount(ORIGINAL_AUTHOR_ID, "renamed")).isTrue();
            for (String id : List.of(ORIGINAL_AUTHOR_ID, "202", "303")) {
                String handle = id.equals(ORIGINAL_AUTHOR_ID) ? "renamed" : "elidlicom";
                var user = new XUserSummary(id, handle, handle, "", "", false, 0, 0, 1, "");
                var stored = AccountStore.merge(null, user, Instant.now(), List.of(), List.of(), List.of(),
                        List.of(), null, Map.of(), true);
                AccountStore.write(dir.resolve("users/by-id/" + id + "/store_v" + AccountStore.VERSION + ".json.gz"), stored, mapper);
            }
            assertThat(hub.isAuthorAccount("303", "elidlicom")).isFalse();
            assertThat(hub.isAuthorAccount(ORIGINAL_AUTHOR_ID, "renamed")).isTrue();
            assertThat(hub.longHistoryCandidates()).extracting(candidate -> candidate.user().id())
                    .containsExactly(ORIGINAL_AUTHOR_ID);
            assertThat(AccountStore.retainsFullHistory("renamed", ORIGINAL_AUTHOR_ID, null)).isTrue();
            assertThat(AccountStore.retainsFullHistory("elidlicom", "202", null)).isFalse();
        } finally {
            hub.stopBackgroundRefreshes();
        }
    }

    @Test
    void renamedAccountCanReadItsOfflineReportThenRefreshUnderItsCurrentHandle() throws Exception {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setTwitterapiIoKey("test-key");
        var provider = mock(TwitterApiIoProvider.class);
        var owners = new AssistantStore(dir.resolve("assistant"), mapper);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), provider,
                mock(OfficialXApiV2Provider.class), mapper, null, owners);
        var old = new XUserSummary("101", "before_name", "Before", "", "", false, 0, 0, 1, "");
        var renamed = new XUserSummary("101", "after_name", "After", "", "", false, 0, 0, 1, "");
        var now = Instant.now();
        var snapshot = AccountStore.merge(null, old, now.minusSeconds(9000), List.of(), List.of(), List.of(),
                List.of(), null, Map.of(), true);
        AccountStore.write(dir.resolve("users/by-id/101/store_v" + AccountStore.VERSION + ".json.gz"), snapshot, mapper);
        var updated = AccountStore.merge(snapshot, renamed, now, List.of(), List.of(), List.of(), List.of(), null, Map.of(), true);
        when(provider.syncStoreByHours(eq("after_name"), any(), eq(24), anyBoolean(), eq(true), isNull(), eq("101")))
                .thenReturn(new TwitterApiIoProvider.SyncOutcome(updated, true, false, false, 1));
        var api = new CommunityApiController(props, hub);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser("101", "after_name", "After", "", true, now));
        try {
            var cached = api.analyzeBundle("after_name", "24H", null, "TWITTERAPI_IO", false, false,
                    true, false, request, new MockHttpServletResponse());
            assertThat(cached.hours24().profile().id()).isEqualTo("101");
            assertThat(cached.hours24().profile().username()).isEqualTo("before_name");
            verify(provider, never()).syncStoreByHours(anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), any(), any());
            var live = api.analyzeBundle("after_name", "24H", null, "TWITTERAPI_IO", true, false,
                    false, false, request, new MockHttpServletResponse());
            assertThat(live.hours24().profile().username()).isEqualTo("after_name");
        } finally {
            hub.stopBackgroundRefreshes();
        }
    }

    @Test
    void failedFreeProfileOnlyFallsBackForTheBoundAuthorId() throws Exception {
        var props = new XOpsProperties();
        props.setDefaultProvider("TWITTERAPI_IO");
        props.setTwitterapiIoKey("test-key");
        props.setTwitterapiIoBaseUrl("http://127.0.0.1:1");
        props.setFxtwitterBaseUrl("http://127.0.0.1:2/2");
        props.setFxtwitterEnabled(true);
        props.setDiskCacheDir(dir.toString());
        var owners = new AssistantStore(dir.resolve("assistant"), mapper);
        owners.tx(tx -> tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "101").put("username", "renamed")));
        var pool = new SharedApiExecutor(props);
        var guard = new UpstreamBudgetGuard(props, true);
        var provider = new TwitterApiIoProvider(props, mapper, pool, guard);
        var free = rejectingTransport(404);
        var paid = rejectingTransport(401);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(provider, "fxTwitterClient"), "httpClient", free);
        ReflectionTestUtils.setField(provider, "httpClient", paid);
        var hub = new CommunityHubService(props, new DemoXDataProvider(), provider, mock(OfficialXApiV2Provider.class),
                mapper, null, owners);
        ReflectionTestUtils.setField(hub, "paidCapabilityGate", new PaidCapabilityGate());
        try {
            assertThatThrownBy(() -> hub.analyzeWorkspaceBundle("elidlicom", ReportPeriod.HOURS_24, "TWITTERAPI_IO",
                    false, "127.0.0.1", "202", false)).isInstanceOf(XDataProviderException.class);
            verify(free, atLeastOnce()).send(any(HttpRequest.class), any());
            verify(paid, never()).send(any(HttpRequest.class), any());
            assertThat(dir.resolve("upstream-budget.json")).doesNotExist();
            assertThatThrownBy(() -> hub.analyzeWorkspaceBundle("renamed", ReportPeriod.HOURS_24, "TWITTERAPI_IO",
                    false, "127.0.0.1", "101", false)).isInstanceOf(XDataProviderException.class);
            verify(paid).send(any(HttpRequest.class), any());
            assertThat(guard.snapshotMetrics().get("totalBillableCalls")).isEqualTo(1L);
        } finally {
            hub.stopBackgroundRefreshes();
            pool.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpClient rejectingTransport(int statusCode) throws Exception {
        var client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn("{}");
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        return client;
    }
}
