package com.xops.hub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRestoreHardeningTest {
    private static final AuthenticatedXUser USER =
            new AuthenticatedXUser("restore-user", "restore_user", "Restore User", "", false, Instant.parse("2026-11-15T01:00:00Z"));

    @Test
    void concurrentOldCookiesReuseSameSessionAndCookie(@TempDir Path dir) throws Exception {
        var store = store(dir);
        store.saveSession("old-session", USER, Instant.now().getEpochSecond());
        var filter = new XOAuthSessionRestoreFilter(store);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return restore(filter, request("old-session")); });
            var second = pool.submit(() -> { start.await(); return restore(filter, request("old-session")); });
            start.countDown();
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertThat(a.session()).isSameAs(b.session());
            assertThat(a.cookie()).isEqualTo(b.cookie());
            assertThat(a.session().getAttribute(XOAuthController.SESSION_AUTH_USER)).isEqualTo(USER);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anonymousSessionIdChangesBeforeRecoveredIdentityIsAssigned(@TempDir Path dir) throws Exception {
        var store = store(dir);
        store.saveSession("old-session", USER, Instant.now().getEpochSecond());
        var filter = new XOAuthSessionRestoreFilter(store);
        var request = request("old-session");
        request.setSession(new MockHttpSession(null, "old-session"));
        var restored = restore(filter, request);
        assertThat(restored.session().getId()).isNotEqualTo("old-session");
        assertThat(restored.session().getAttribute(XOAuthController.SESSION_AUTH_USER)).isEqualTo(USER);
        assertThat(store.loadValid(restored.session().getId(), Instant.now().getEpochSecond())).isNotNull();
    }

    @Test
    void urlSessionIdCannotRestorePersistentAuthentication(@TempDir Path dir) throws Exception {
        var store = store(dir);
        store.saveSession("old-session", USER, Instant.now().getEpochSecond());
        var request = new MockHttpServletRequest("GET", "/api/auth/x/status");
        request.setRequestedSessionId("old-session");
        request.setRequestedSessionIdFromURL(true);
        request.setRequestedSessionIdFromCookie(false);
        var result = restore(new XOAuthSessionRestoreFilter(store), request);
        assertThat(result.session()).isNull();
        assertThat(result.cookie()).isNull();
    }

    @Test
    void revokedRecoveredSessionLosesIdentityOnNextRequest(@TempDir Path dir) throws Exception {
        var store = store(dir);
        store.saveSession("old-session", USER, Instant.now().getEpochSecond());
        var filter = new XOAuthSessionRestoreFilter(store);
        var first = restore(filter, request("old-session"));
        String restoredId = first.session().getId();
        store.revokeSession("old-session");
        var followup = request(restoredId);
        followup.setSession(first.session());
        assertThat(restore(filter, followup).session()).isNull();
        assertThat(restore(filter, request("old-session")).session()).isNull();
    }

    @Test
    void logoutBetweenReadAndRotationDoesNotLeaveAuthenticatedOrphan(@TempDir Path dir) throws Exception {
        var props = properties(dir);
        var store = new XOAuthSessionStore(props, new ObjectMapper()) {
            @Override
            public void rotateOnRestore(String oldId, String newId, AuthenticatedXUser user, long now) {
                revokeSession(oldId);
                super.rotateOnRestore(oldId, newId, user, now);
            }
        };
        store.saveSession("old-session", USER, Instant.now().getEpochSecond());
        var result = restore(new XOAuthSessionRestoreFilter(store), request("old-session"));
        assertThat(result.session()).isNull();
        assertThat(result.cookie()).isNull();
    }

    @Test
    void persistentLoginKeeps400DaysWhileIdleMemorySessionsUseOneHour(@TempDir Path dir) throws Exception {
        var store = store(dir);
        long nowSec = Instant.now().getEpochSecond();
        store.saveSession("old-session", USER, nowSec);
        var filter = new XOAuthSessionRestoreFilter(store);
        var recovered = restore(filter, request("old-session"));
        assertThat(recovered.cookie()).contains("Max-Age=34560000");
        assertThat(recovered.session().getMaxInactiveInterval()).isEqualTo(3600);
        assertThat(store.loadValid(recovered.session().getId(), nowSec).expiresAtEpochSec() - nowSec)
                .isGreaterThanOrEqualTo(399L * 24 * 3600);

        var legacy = new MockHttpSession();
        legacy.setAttribute(XOAuthController.SESSION_AUTH_USER, USER);
        legacy.setMaxInactiveInterval((int) XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS);
        store.saveSession(legacy.getId(), USER, nowSec);
        var legacyRequest = request(legacy.getId());
        legacyRequest.setSession(legacy);
        var retained = restore(filter, legacyRequest);
        assertThat(retained.session().getAttribute(XOAuthController.SESSION_AUTH_USER)).isEqualTo(USER);
        assertThat(retained.session().getMaxInactiveInterval()).isEqualTo(3600);
    }

    @Test
    void unmarkedAuthenticatedSessionWithoutCredentialsCannotBypassRevocation(@TempDir Path dir) throws Exception {
        var store = store(dir);
        var legacy = new MockHttpSession();
        legacy.setAttribute(XOAuthController.SESSION_AUTH_USER, USER);
        var request = request(legacy.getId());
        request.setSession(legacy);
        assertThat(restore(new XOAuthSessionRestoreFilter(store), request).session()).isNull();
        assertThat(legacy.isInvalid()).isTrue();
    }

    @Test
    void authenticatedMemoryIdentityMustMatchItsPersistentAccount(@TempDir Path dir) throws Exception {
        var store = store(dir);
        var session = new MockHttpSession();
        store.saveSession(session.getId(), USER, Instant.now().getEpochSecond());
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, new AuthenticatedXUser(
                "another-account", "other", "Other", "", false, USER.loginAt()));
        var request = new MockHttpServletRequest("GET", "/api/auth/x/status");
        request.setSession(session);
        assertThat(restore(new XOAuthSessionRestoreFilter(store), request).session()).isNull();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void memoryOnlyConfigurationKeepsAuthenticatedSessionsWithoutDiskCredentials(@TempDir Path dir) throws Exception {
        var props = properties(dir);
        props.setDiskCacheDir("");
        var session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER, USER);
        var request = new MockHttpServletRequest("GET", "/api/auth/x/status");
        request.setSession(session);
        var result = restore(new XOAuthSessionRestoreFilter(new XOAuthSessionStore(props, new ObjectMapper())), request);
        assertThat(result.session()).isSameAs(session);
        assertThat(result.session().getAttribute(XOAuthController.SESSION_AUTH_USER)).isEqualTo(USER);
        assertThat(result.session().getMaxInactiveInterval()).isEqualTo(3600);
    }

    private record RestoreResult(HttpSession session, String cookie) {}

    private static RestoreResult restore(XOAuthSessionRestoreFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpSession> observed = new AtomicReference<>();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> observed.set(((HttpServletRequest) req).getSession(false)));
        return new RestoreResult(observed.get(), response.getHeader("Set-Cookie"));
    }

    private static MockHttpServletRequest request(String oldId) {
        var request = new MockHttpServletRequest("GET", "/api/auth/x/status");
        request.setCookies(new Cookie("JSESSIONID", oldId));
        request.setRequestedSessionId(oldId);
        return request;
    }

    private static XOAuthSessionStore store(Path dir) {
        return new XOAuthSessionStore(properties(dir), new ObjectMapper());
    }

    private static XOpsProperties properties(Path dir) {
        var props = new XOpsProperties();
        props.setDiskCacheDir(dir.toString());
        props.setTokenEncryptionSecret("local-request-guard-test-secret-2026");
        return props;
    }
}
