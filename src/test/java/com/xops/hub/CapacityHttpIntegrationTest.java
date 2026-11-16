package com.xops.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.UserConcurrencyThrottleFilter;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.store.XOAuthSessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP/Tomcat/filter-chain acceptance with synthetic logged-in accounts and the existing DEMO APIs.
 * This measures local demo/report/session work, never production paid-provider cold-start throughput.
 * The separately labelled hold endpoint measures gateway admission only, not application performance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "server.address=127.0.0.1",
        "server.servlet.session.persistent=false",
        "xops.default-provider=DEMO",
        "xops.require-login-self-only=true",
        "xops.twitterapi-io-key=",
        "xops.official-bearer-token=",
        "xops.oauth-client-id=capacity_synthetic_client",
        "xops.oauth-client-secret=capacity_synthetic_secret",
        "xops.token-encryption-secret=capacity_synthetic_encryption_secret",
        "xops.twitterapi-io-base-url=http://127.0.0.1:9",
        "xops.official-base-url=http://127.0.0.1:9/2",
        "xops.scheduler-enabled=false",
        "xops.top50-auto-sync-enabled=false",
        "xops.max-concurrent-users=50"
})
@Import(CapacityHttpIntegrationTest.FixtureConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CapacityHttpIntegrationTest {
    private static Path tempRoot;

    @DynamicPropertySource
    static void isolatedStorage(DynamicPropertyRegistry registry) throws IOException {
        tempRoot = Files.createTempDirectory("xops-capacity-http-");
        registry.add("xops.disk-cache-dir", tempRoot::toString);
        registry.add("xops.algo-profile-path", () -> tempRoot.resolve("algorithm.json").toString());
        registry.add("server.tomcat.basedir", () -> tempRoot.resolve("tomcat").toString());
    }

    @AfterAll
    static void removeSyntheticData() throws IOException {
        if (tempRoot != null && Files.exists(tempRoot)) {
            try (var paths = Files.walk(tempRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired AdmissionMeter meter;
    @Autowired UserConcurrencyThrottleFilter throttle;
    @Autowired UpstreamBudgetGuard budget;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1).build();

    private record Identity(String id, String username, String cookie) { }
    private record Sample(String path, int status, long elapsedNanos, String rateMode, String failure) {
        boolean successful() { return status == 200 && failure == null; }
    }

    @Test
    @Timeout(55)
    void fiftyRealSessionWorkflowsAndSeventyFiveOverflowUsersRemainIsolatedAndComplete() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(75);
        try {
            List<Identity> users = new ArrayList<>();
            for (int i = 0; i < 75; i++) users.add(loginFixture(i));
            assertEquals(75, users.stream().map(Identity::cookie).distinct().count(), "Each user needs a distinct real HttpSession");
            assertEquals(50, ((Number) throttle.snapshotMetrics().get("maxConcurrentUsers")).intValue());

            // Only the first 50 accounts have entered /api/* when this phase starts.
            runWave("real-demo-first-read-50", users.subList(0, 50), workers, false);
            runWave("real-demo-cache-and-session-50", users.subList(0, 50), workers, false);

            Identity alice = users.get(0);
            Identity bob = users.get(1);
            HttpResponse<String> denied = get(alice, "/api/analyze?provider=DEMO&period=HOURS_24&username=" + bob.username());
            assertEquals(403, denied.statusCode(), "Cross-account access must be rejected by the real controller");
            assertFalse(mapper.readTree(denied.body()).has("profile"));
            assertEquals(401, get(null, "/api/analyze?provider=DEMO&period=HOURS_24").statusCode());

            List<Sample> gateSamples = runWave("gateway-hold-fixture-only-75", users, workers, true);
            assertTrue(gateSamples.stream().anyMatch(s -> "throttled".equals(s.rateMode())),
                    "The 75-user phase must actually exercise overflow pacing");
            runWave("real-demo-mixed-and-session-75", users, workers, false);

            Map<String, Object> metrics = throttle.snapshotMetrics();
            assertEquals(0L, ((Number) metrics.get("queueOverflowRejectedRequests")).longValue());
            assertEquals(0, ((Number) metrics.get("queuedWaiters")).intValue());
            assertTrue(((Number) metrics.get("throttledHandledRequests")).longValue() > 0);
            assertEquals(Map.of(), budget.snapshotMetrics().get("callsByCategory"),
                    "The workload must not dispatch even a local mock provider call");
            System.out.println("CAPACITY_HTTP_SCOPE local Tomcat + real session/report/queue controllers + DEMO only; "
                    + "hold-fixture latency includes a deliberate 150 ms sleep; no production or paid upstream claim");
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private Identity loginFixture(int index) throws Exception {
        URI target = localUri("/__capacity_fixture/login?index=" + index);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5)).header("Origin", "http://127.0.0.1:" + port)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow()
                .split(";", 2)[0];
        JsonNode user = mapper.readTree(response.body());
        return new Identity(user.path("id").asText(), user.path("username").asText(), cookie);
    }

    private List<Sample> runWave(String label, List<Identity> users, ExecutorService workers, boolean fixtureOnly)
            throws Exception {
        meter.resetPeak();
        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Sample>>> futures = new ArrayList<>();
        long waveStart = System.nanoTime();
        for (Identity user : users) {
            futures.add(workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("client barrier timeout");
                List<Sample> samples = new ArrayList<>();
                if (fixtureOnly) {
                    samples.add(measure(user, "/api/__capacity_fixture/hold", "fixture"));
                } else {
                    samples.add(measure(user, "/api/auth/x/status", "status"));
                    samples.add(measure(user, "/api/analyze?provider=DEMO&period=HOURS_24", "report"));
                    samples.add(measure(user, "/api/scheduled-tweets", "queue"));
                }
                return samples;
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "All simulated users must be ready before the wave");
        start.countDown();
        List<Sample> samples = new ArrayList<>();
        for (Future<List<Sample>> future : futures) samples.addAll(future.get(15, TimeUnit.SECONDS));
        long successes = samples.stream().filter(Sample::successful).count();
        List<Long> latencies = samples.stream().map(Sample::elapsedNanos).sorted().toList();
        int percentileIndex = Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", label);
        result.put("concurrentUserWorkflows", users.size());
        result.put("requests", samples.size());
        result.put("successful", successes);
        result.put("successRatePct", Math.round(successes * 10000.0 / samples.size()) / 100.0);
        result.put("p95Ms", Math.round(latencies.get(percentileIndex) / 10000.0) / 100.0);
        result.put("maxMs", Math.round(latencies.get(latencies.size() - 1) / 10000.0) / 100.0);
        result.put("peakAdmittedServletRequests", meter.peak.get());
        result.put("throttledRequests", samples.stream().filter(s -> "throttled".equals(s.rateMode())).count());
        result.put("waveMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waveStart));
        System.out.println("CAPACITY_HTTP " + mapper.writeValueAsString(result));
        assertEquals(samples.size(), successes,
                () -> label + " failures=" + samples.stream().filter(s -> !s.successful()).toList());
        assertTrue(meter.peak.get() > 1, "The real servlet handlers must have overlapped");
        return samples;
    }

    private Sample measure(Identity user, String path, String kind) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = get(user, path);
            String failure = null;
            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                if ("status".equals(kind)) {
                    if (!json.path("loggedIn").asBoolean()
                            || !user.id().equals(json.path("user").path("id").asText())) failure = "session identity mismatch";
                } else if ("report".equals(kind)) {
                    if (!user.id().equals(json.path("profile").path("id").asText())
                            || !user.username().equals(json.path("profile").path("username").asText())) failure = "report identity mismatch";
                } else if ("queue".equals(kind)) {
                    if (!json.path("items").isArray() || !json.path("items").isEmpty()) failure = "foreign scheduled queue";
                } else if (!user.id().equals(json.path("id").asText())) {
                    failure = "fixture identity mismatch";
                }
            } else {
                failure = "unexpected HTTP status";
            }
            return new Sample(path, response.statusCode(), System.nanoTime() - start,
                    response.headers().firstValue("X-RateLimit-Mode").orElse(""), failure);
        } catch (Exception ex) {
            return new Sample(path, -1, System.nanoTime() - start, "", ex.getClass().getSimpleName());
        }
    }

    private HttpResponse<String> get(Identity user, String path) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(localUri(path)).timeout(Duration.ofSeconds(12)).GET();
        if (user != null) request.header("Cookie", user.cookie());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI localUri(String path) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != port) throw new IllegalArgumentException("Nonlocal test request");
        return uri;
    }

    static final class AdmissionMeter {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        void resetPeak() { peak.set(active.get()); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean AdmissionMeter admissionMeter() { return new AdmissionMeter(); }
        @Bean FixtureController capacityFixtureController(XOAuthSessionStore store) { return new FixtureController(store); }
        @Bean FilterRegistrationBean<OncePerRequestFilter> capacityAdmissionMeter(AdmissionMeter meter) {
            OncePerRequestFilter filter = new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    int active = meter.active.incrementAndGet();
                    meter.peak.accumulateAndGet(active, Math::max);
                    try { chain.doFilter(request, response); }
                    finally { meter.active.decrementAndGet(); }
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setName("capacityTestAdmissionMeter");
            registration.addUrlPatterns("/api/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 25);
            return registration;
        }
    }

    @TestComponent
    @RestController
    static class FixtureController {
        private final XOAuthSessionStore store;
        FixtureController(XOAuthSessionStore store) { this.store = store; }

        @PostMapping("/__capacity_fixture/login")
        Map<String, String> login(@RequestParam int index, HttpServletRequest request) {
            if (index < 0 || index >= 75) throw new IllegalArgumentException("synthetic user out of range");
            String id = "cap_id_" + index;
            String username = "cap_user_" + index;
            AuthenticatedXUser user = new AuthenticatedXUser(id, username, username, "", false, Instant.now());
            var session = request.getSession(true);
            session.setMaxInactiveInterval(3600);
            store.saveSession(session.getId(), user, Instant.now().getEpochSecond());
            session.setAttribute(XOAuthController.SESSION_AUTH_USER, user);
            session.setAttribute(XOAuthController.SESSION_COOKIE_TOUCHED_AT, Instant.now().getEpochSecond());
            return Map.of("id", id, "username", username);
        }

        @GetMapping("/api/__capacity_fixture/hold")
        Map<String, String> hold(HttpServletRequest request) throws InterruptedException {
            var session = request.getSession(false);
            Object raw = session != null ? session.getAttribute(XOAuthController.SESSION_AUTH_USER) : null;
            if (!(raw instanceof AuthenticatedXUser user)) throw new IllegalStateException("missing synthetic login");
            Thread.sleep(150L);
            return Map.of("id", user.id());
        }
    }
}
