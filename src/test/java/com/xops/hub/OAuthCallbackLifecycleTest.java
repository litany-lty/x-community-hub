package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOAuthSessionRestoreFilter;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.store.XOAuthSessionStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OAuthCallbackLifecycleTest {
    private static final AuthenticatedXUser USER = new AuthenticatedXUser(
            "callback-user", "callback_user", "Callback User", "", false, Instant.ofEpochSecond(1000));
    @TempDir Path directory;

    @Test
    void initialCallbackPersistsBeforeIdentityAndRevocationInvalidatesItsMemorySession() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        var store = new XOAuthSessionStore(properties, new ObjectMapper()) {
            @Override public void saveSession(String id, AuthenticatedXUser user, long now) {
                assertNull(pending.getAttribute(XOAuthController.SESSION_AUTH_USER));
                super.saveSession(id, user, now);
            }
        };
        String anonymousId = pending.getId();
        var controller = new XOAuthController(properties, oauth(XOAuthService.AuthPurpose.LOGIN), store);
        var result = callback(controller, pending, false);
        assertEquals("/?auth=ok", result.response().getRedirectedUrl());
        assertNotEquals(anonymousId, pending.getId());
        assertEquals(USER, pending.getAttribute(XOAuthController.SESSION_AUTH_USER));
        assertNotNull(store.loadValid(pending.getId(), Instant.now().getEpochSecond()));
        assertEquals(3600, pending.getMaxInactiveInterval());
        assertTrue(result.response().getHeader("Set-Cookie").contains("Max-Age=34560000"));

        store.revokeSession(pending.getId());
        assertNull(throughFilter(new XOAuthSessionRestoreFilter(store), pending, pending.getId()));
    }

    @Test
    void siblingLogoutRevokesStepUpCallbackSessionAlongWithRestoredSessions() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        var store = new XOAuthSessionStore(properties, new ObjectMapper());
        var oauth = oauth(XOAuthService.AuthPurpose.LOGIN);
        var controller = new XOAuthController(properties, oauth, store);
        callback(controller, pending, false);
        String priorId = pending.getId();
        when(oauth.handleCallbackResult(anyString(), anyString())).thenReturn(result(XOAuthService.AuthPurpose.PUBLISH));
        var stepUp = callback(controller, pending, true);
        assertEquals("/?auth=publish_ok&tab=schedule", stepUp.response().getRedirectedUrl());
        String stepUpId = pending.getId();
        assertNotEquals(priorId, stepUpId);

        // Restore the still-valid old cookie through the actual filter before it expires.
        var filter = new XOAuthSessionRestoreFilter(store);
        HttpSession sibling = throughFilter(filter, null, priorId);
        assertNotNull(sibling);
        assertNotEquals(stepUpId, sibling.getId());
        var logout = new MockHttpServletRequest("POST", "/api/auth/x/logout");
        logout.setSession(sibling);
        logout.setRequestedSessionId(sibling.getId());
        controller.logout(logout, new MockHttpServletResponse());

        assertNull(store.loadValid(priorId, Instant.now().getEpochSecond()));
        assertNull(store.loadValid(stepUpId, Instant.now().getEpochSecond()));
        assertNull(throughFilter(filter, pending, stepUpId));
    }

    @Test
    void failedInitialPersistenceNeverLeavesAnAuthenticatedSessionOrLiveCookie() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        AtomicReference<String> attemptedId = new AtomicReference<>();
        var store = new XOAuthSessionStore(properties, new ObjectMapper()) {
            @Override public void saveSession(String id, AuthenticatedXUser user, long now) {
                assertNull(pending.getAttribute(XOAuthController.SESSION_AUTH_USER));
                attemptedId.set(id);
                throw new IllegalStateException("synthetic persistence failure");
            }
        };
        var controller = new XOAuthController(properties, oauth(XOAuthService.AuthPurpose.LOGIN), store);
        var exchange = callback(controller, pending, false);
        assertTrue(exchange.response().getRedirectedUrl().startsWith("/?auth_error="));
        assertNull(exchange.request().getSession(false));
        assertTrue(pending.isInvalid());
        assertTrue(exchange.response().getHeader("Set-Cookie").contains("Max-Age=0"));
        assertNull(store.loadValid(attemptedId.get(), Instant.now().getEpochSecond()));
    }

    @Test
    void failedRotationAfterPartialPersistenceRevokesItsNewCredential() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        AtomicReference<String> attemptedId = new AtomicReference<>();
        var store = new XOAuthSessionStore(properties, new ObjectMapper()) {
            @Override public void rotateOnRestore(String oldId, String newId, AuthenticatedXUser user, long now) {
                assertEquals(USER, pending.getAttribute(XOAuthController.SESSION_AUTH_USER));
                attemptedId.set(newId);
                super.rotateOnRestore(oldId, newId, user, now);
                throw new IllegalStateException("synthetic failure after credential creation");
            }
        };
        pending.setAttribute(XOAuthController.SESSION_AUTH_USER, USER);
        String oldId = pending.getId();
        store.saveSession(oldId, USER, Instant.now().getEpochSecond());
        var controller = new XOAuthController(properties, oauth(XOAuthService.AuthPurpose.PUBLISH), store);
        var exchange = callback(controller, pending, true);

        assertTrue(exchange.response().getRedirectedUrl().startsWith("/?tab=schedule&publish_auth_error="));
        assertNull(exchange.request().getSession(false));
        assertTrue(pending.isInvalid());
        assertTrue(exchange.response().getHeader("Set-Cookie").contains("Max-Age=0"));
        assertNull(store.loadValid(attemptedId.get(), Instant.now().getEpochSecond()));
        assertNull(store.loadValid(oldId, Instant.now().getEpochSecond()));
    }

    @Test
    void logoutDuringUpstreamCallbackCannotCreateANewAuthenticatedSession() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        var store = new XOAuthSessionStore(properties, new ObjectMapper());
        var oauth = oauth(XOAuthService.AuthPurpose.LOGIN);
        when(oauth.handleCallbackResult(anyString(), anyString())).thenAnswer(invocation -> {
            pending.invalidate();
            return result(XOAuthService.AuthPurpose.LOGIN);
        });
        var controller = new XOAuthController(properties, oauth, store);
        var exchange = callback(controller, pending, false);

        assertTrue(exchange.response().getRedirectedUrl().startsWith("/?auth_error="));
        assertNull(exchange.request().getSession(false));
        assertTrue(pending.isInvalid());
        assertNull(exchange.response().getHeader("Set-Cookie"));
    }

    @Test
    void logoutCapturingAnonymousIdPreventsCallbackFromLeavingNewCredentials() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        String anonymousId = pending.getId();
        CountDownLatch upstreamEntered = new CountDownLatch(1);
        CountDownLatch returnFromUpstream = new CountDownLatch(1);
        CountDownLatch logoutCapturedId = new CountDownLatch(1);
        CountDownLatch finishLogout = new CountDownLatch(1);
        CountDownLatch callbackCapturedSession = new CountDownLatch(1);
        AtomicBoolean upstreamReturned = new AtomicBoolean();
        AtomicBoolean blockFirstRevocation = new AtomicBoolean(true);
        AtomicInteger saves = new AtomicInteger();
        var store = new XOAuthSessionStore(properties, new ObjectMapper()) {
            @Override public void saveSession(String id, AuthenticatedXUser user, long now) {
                saves.incrementAndGet();
                super.saveSession(id, user, now);
            }
            @Override public void revokeSession(String... ids) {
                if (blockFirstRevocation.compareAndSet(true, false)) {
                    logoutCapturedId.countDown();
                    await(finishLogout);
                }
                super.revokeSession(ids);
            }
        };
        var oauth = oauth(XOAuthService.AuthPurpose.LOGIN);
        when(oauth.handleCallbackResult(anyString(), anyString())).thenAnswer(invocation -> {
            upstreamEntered.countDown();
            await(returnFromUpstream);
            upstreamReturned.set(true);
            return result(XOAuthService.AuthPurpose.LOGIN);
        });
        var controller = new XOAuthController(properties, oauth, store);
        pending.setAttribute(XOAuthController.SESSION_OAUTH_STATES, List.of("interleaved-state"));
        var callbackRequest = new MockHttpServletRequest("GET", "/api/auth/x/callback") {
            @Override public HttpSession getSession(boolean create) {
                HttpSession current = super.getSession(create);
                if (!create && upstreamReturned.get()) callbackCapturedSession.countDown();
                return current;
            }
        };
        callbackRequest.setSession(pending);
        var logoutRequest = new MockHttpServletRequest("POST", "/api/auth/x/logout");
        logoutRequest.setSession(pending);
        logoutRequest.setRequestedSessionId(anonymousId);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var callbackRun = pool.submit(() -> invokeCallback(controller, callbackRequest, "code", "interleaved-state"));
            await(upstreamEntered);
            var logoutRun = pool.submit(() -> controller.logout(logoutRequest, new MockHttpServletResponse()));
            await(logoutCapturedId);
            returnFromUpstream.countDown();
            await(callbackCapturedSession);
            assertThrows(TimeoutException.class, () -> callbackRun.get(200, TimeUnit.MILLISECONDS));
            finishLogout.countDown();
            logoutRun.get(5, TimeUnit.SECONDS);
            var response = callbackRun.get(5, TimeUnit.SECONDS).response();
            assertTrue(response.getRedirectedUrl().startsWith("/?auth_error="));
            assertEquals(0, saves.get());
            assertTrue(pending.isInvalid());
            assertNull(store.loadValid(anonymousId, Instant.now().getEpochSecond()));
        } finally {
            returnFromUpstream.countDown();
            finishLogout.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentCallbacksKeepBothRotationsInTheOriginalCredentialFamily() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        CountDownLatch bothUpstream = new CountDownLatch(2);
        CountDownLatch returnFirst = new CountDownLatch(1);
        CountDownLatch returnSecond = new CountDownLatch(1);
        CountDownLatch firstRotationEntered = new CountDownLatch(1);
        CountDownLatch finishFirstRotation = new CountDownLatch(1);
        AtomicInteger saves = new AtomicInteger();
        AtomicInteger rotations = new AtomicInteger();
        var store = new XOAuthSessionStore(properties, new ObjectMapper()) {
            @Override public void saveSession(String id, AuthenticatedXUser user, long now) {
                saves.incrementAndGet();
                super.saveSession(id, user, now);
            }
            @Override public void rotateOnRestore(String oldId, String newId, AuthenticatedXUser user, long now) {
                if (rotations.incrementAndGet() == 1) {
                    firstRotationEntered.countDown();
                    await(finishFirstRotation);
                }
                super.rotateOnRestore(oldId, newId, user, now);
            }
        };
        pending.setAttribute(XOAuthController.SESSION_AUTH_USER, USER);
        pending.setAttribute(XOAuthController.SESSION_OAUTH_STATES, List.of("first-state", "second-state"));
        String originalId = pending.getId();
        store.saveSession(originalId, USER, Instant.now().getEpochSecond());
        var oauth = oauth(XOAuthService.AuthPurpose.LOGIN);
        when(oauth.handleCallbackResult(anyString(), anyString())).thenAnswer(invocation -> {
            bothUpstream.countDown();
            await("first-code".equals(invocation.getArgument(0)) ? returnFirst : returnSecond);
            return result(XOAuthService.AuthPurpose.LOGIN);
        });
        var controller = new XOAuthController(properties, oauth, store);
        var firstRequest = new MockHttpServletRequest("GET", "/api/auth/x/callback");
        firstRequest.setSession(pending);
        var secondRequest = new MockHttpServletRequest("GET", "/api/auth/x/callback");
        secondRequest.setSession(pending);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> invokeCallback(controller, firstRequest, "first-code", "first-state"));
            var second = pool.submit(() -> invokeCallback(controller, secondRequest, "second-code", "second-state"));
            await(bothUpstream);
            returnFirst.countDown();
            await(firstRotationEntered);
            returnSecond.countDown();
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            finishFirstRotation.countDown();
            assertEquals("/?auth=ok", first.get(5, TimeUnit.SECONDS).response().getRedirectedUrl());
            assertEquals("/?auth=ok", second.get(5, TimeUnit.SECONDS).response().getRedirectedUrl());
            assertEquals(1, saves.get(), "only the explicitly seeded original login may create a new family");
            assertEquals(2, rotations.get());
            long now = Instant.now().getEpochSecond();
            assertEquals(store.loadValid(originalId, now).familyHash(), store.loadValid(pending.getId(), now).familyHash());
        } finally {
            returnFirst.countDown();
            returnSecond.countDown();
            finishFirstRotation.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void apiValidationWaitsForStepUpCommitWithoutInvalidatingTheSession() throws Exception {
        var properties = properties();
        var pending = new MockHttpSession();
        CountDownLatch rotationEntered = new CountDownLatch(1);
        CountDownLatch finishRotation = new CountDownLatch(1);
        CountDownLatch apiCapturedSession = new CountDownLatch(1);
        var store = new XOAuthSessionStore(properties, new ObjectMapper()) {
            @Override public void rotateOnRestore(String oldId, String newId, AuthenticatedXUser user, long now) {
                rotationEntered.countDown();
                await(finishRotation);
                super.rotateOnRestore(oldId, newId, user, now);
            }
        };
        pending.setAttribute(XOAuthController.SESSION_AUTH_USER, USER);
        String originalId = pending.getId();
        store.saveSession(originalId, USER, Instant.now().getEpochSecond());
        var controller = new XOAuthController(properties, oauth(XOAuthService.AuthPurpose.PUBLISH), store);
        var filter = new XOAuthSessionRestoreFilter(store);
        var apiRequest = new MockHttpServletRequest("GET", "/api/auth/x/status") {
            @Override public HttpSession getSession(boolean create) {
                HttpSession session = super.getSession(create);
                if (!create) apiCapturedSession.countDown();
                return session;
            }
        };
        apiRequest.setSession(pending);
        apiRequest.setCookies(new Cookie("JSESSIONID", originalId));
        apiRequest.setRequestedSessionId(originalId);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var callbackRun = pool.submit(() -> callback(controller, pending, true));
            await(rotationEntered);
            assertNotEquals(originalId, pending.getId());
            assertNull(store.loadValid(pending.getId(), Instant.now().getEpochSecond()));
            assertEquals(USER, pending.getAttribute(XOAuthController.SESSION_AUTH_USER),
                    "requests already admitted must retain the prior identity during a step-up commit");
            var apiRun = pool.submit(() -> {
                AtomicReference<HttpSession> observed = new AtomicReference<>();
                filter.doFilter(apiRequest, new MockHttpServletResponse(), (req, res) -> {
                    assertFalse(Thread.holdsLock(pending), "business dispatch must run outside the session monitor");
                    observed.set(((HttpServletRequest) req).getSession(false));
                });
                return observed.get();
            });
            await(apiCapturedSession);
            assertThrows(TimeoutException.class, () -> apiRun.get(200, TimeUnit.MILLISECONDS));
            finishRotation.countDown();
            assertEquals("/?auth=publish_ok&tab=schedule", callbackRun.get(5, TimeUnit.SECONDS).response().getRedirectedUrl());
            assertSame(pending, apiRun.get(5, TimeUnit.SECONDS));
            assertFalse(pending.isInvalid());
            assertEquals(USER, pending.getAttribute(XOAuthController.SESSION_AUTH_USER));
            assertNotNull(store.loadValid(pending.getId(), Instant.now().getEpochSecond()));
        } finally {
            finishRotation.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled interleaving did not reach its checkpoint");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for controlled interleaving", ex);
        }
    }

    private static CallbackExchange invokeCallback(XOAuthController controller, MockHttpServletRequest request,
                                                    String code, String state) throws Exception {
        var response = new MockHttpServletResponse();
        controller.callback(code, state, null, request, response);
        return new CallbackExchange(request, response);
    }

    private XOpsProperties properties() {
        var properties = new XOpsProperties();
        properties.setDiskCacheDir(directory.toString());
        properties.setTokenEncryptionSecret("synthetic-callback-lifecycle-test-secret");
        properties.setOauthClientId("synthetic-client");
        return properties;
    }

    private static XOAuthService oauth(XOAuthService.AuthPurpose purpose) {
        var oauth = mock(XOAuthService.class);
        when(oauth.handleCallbackResult(anyString(), anyString())).thenReturn(result(purpose));
        return oauth;
    }

    private static XOAuthService.CallbackResult result(XOAuthService.AuthPurpose purpose) {
        return new XOAuthService.CallbackResult(USER, purpose, purpose == XOAuthService.AuthPurpose.PUBLISH, false, false);
    }

    private record CallbackExchange(MockHttpServletRequest request, MockHttpServletResponse response) {}

    private static CallbackExchange callback(XOAuthController controller, MockHttpSession session, boolean publish) throws Exception {
        session.setAttribute(XOAuthController.SESSION_OAUTH_STATES, List.of("synthetic-state"));
        if (publish) session.setAttribute(XOAuthController.SESSION_PUBLISH_OAUTH_STATES, List.of("synthetic-state"));
        var request = new MockHttpServletRequest("GET", "/api/auth/x/callback");
        request.setSession(session);
        var response = new MockHttpServletResponse();
        controller.callback("synthetic-code", "synthetic-state", null, request, response);
        return new CallbackExchange(request, response);
    }

    private static HttpSession throughFilter(XOAuthSessionRestoreFilter filter, HttpSession session, String cookie) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/auth/x/status");
        if (session != null) request.setSession(session);
        request.setCookies(new Cookie("JSESSIONID", cookie));
        request.setRequestedSessionId(cookie);
        AtomicReference<HttpSession> observed = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> observed.set(((HttpServletRequest) req).getSession(false)));
        return observed.get();
    }
}
