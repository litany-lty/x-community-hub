package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthAuditRegressionTest {
    // Coordinate a stale read if updates are unlocked; a correct session-local lock serializes them.
    private static class RacingSession extends MockHttpSession {
        private final CountDownLatch reads = new CountDownLatch(2);
        @Override
        public Object getAttribute(String name) {
            Object value = super.getAttribute(name);
            if (XOAuthController.SESSION_OAUTH_STATES.equals(name)) {
                reads.countDown();
                try {
                    reads.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            return value;
        }
    }

    @Test
    void sameSessionConcurrentRegistrationAndConsumptionKeepBothStates() throws Exception {
        XOAuthController controller = new XOAuthController(new XOpsProperties(), null);
        RacingSession registration = new RacingSession();
        runPair(
                () -> { ReflectionTestUtils.invokeMethod(controller, "rememberOAuthStateInSession", registration, "one"); return true; },
                () -> { ReflectionTestUtils.invokeMethod(controller, "rememberOAuthStateInSession", registration, "two"); return true; }
        );
        assertThat((List<String>) registration.getAttribute(XOAuthController.SESSION_OAUTH_STATES))
                .containsExactlyInAnyOrder("one", "two");

        RacingSession consumption = new RacingSession();
        consumption.setAttribute(XOAuthController.SESSION_OAUTH_STATES, List.of("one", "two"));
        assertThat(runPair(
                () -> ReflectionTestUtils.<Boolean>invokeMethod(controller, "consumeOAuthStateFromSession", consumption, "one"),
                () -> ReflectionTestUtils.<Boolean>invokeMethod(controller, "consumeOAuthStateFromSession", consumption, "two")
        )).containsExactly(true, true);
        assertThat((List<String>) consumption.getAttribute(XOAuthController.SESSION_OAUTH_STATES)).isEmpty();

        RacingSession duplicate = new RacingSession();
        duplicate.setAttribute(XOAuthController.SESSION_OAUTH_STATES, List.of("single"));
        assertThat(runPair(
                () -> ReflectionTestUtils.<Boolean>invokeMethod(controller, "consumeOAuthStateFromSession", duplicate, "single"),
                () -> ReflectionTestUtils.<Boolean>invokeMethod(controller, "consumeOAuthStateFromSession", duplicate, "single")
        )).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void fullGlobalCapacityRejectsNewLoginWithoutEvictingValidStatesAndReclaimsExpiry() throws Exception {
        XOpsProperties properties = new XOpsProperties();
        properties.setOauthClientId("local-test-client");
        XOAuthService service = new XOAuthService(properties, new ObjectMapper());
        String original = service.createAuthorizationRequest("http://localhost").state();
        Map<String, Object> states = pendingStates(service);
        Object saved = states.get(original);
        for (int i = 1; i < 2000; i++) states.put("existing-" + i, saved);
        assertThatThrownBy(() -> service.createAuthorizationRequest("http://localhost"))
                .isInstanceOf(XDataProviderException.class).hasMessageContaining("稍后重试");
        assertThat(states).hasSize(2000).containsEntry(original, saved);

        Constructor<?> constructor = saved.getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        states.put("existing-1", constructor.newInstance("verifier", "http://localhost", Instant.now().minusSeconds(1)));
        String replacement = service.createAuthorizationRequest("http://localhost").state();
        assertThat(states).hasSize(2000).containsKey(replacement).containsEntry(original, saved);

        states.remove(replacement);
        List<Boolean> accepted = runPair(
                () -> tryAuthorize(service), () -> tryAuthorize(service));
        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(states).hasSize(2000).containsEntry(original, saved);
    }

    private boolean tryAuthorize(XOAuthService service) {
        try {
            service.createAuthorizationRequest("http://localhost");
            return true;
        } catch (XDataProviderException ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pendingStates(XOAuthService service) {
        return (Map<String, Object>) ReflectionTestUtils.getField(service, "pendingStates");
    }

    private <T> List<T> runPair(Supplier<T> first, Supplier<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> a = executor.submit(() -> { start.await(); return first.get(); });
            Future<T> b = executor.submit(() -> { start.await(); return second.get(); });
            start.countDown();
            return List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
