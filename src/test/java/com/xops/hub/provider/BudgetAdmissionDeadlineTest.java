package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAdmissionDeadlineTest {
    @Test
    void supplementalRequestExpiredDuringReservationNeverReachesHttpAndIsNotMarkedStarted(@TempDir Path dir) throws Exception {
        try (Fixture fixture = new Fixture(dir)) {
            AtomicBoolean started = new AtomicBoolean();
            AtomicBoolean degraded = new AtomicBoolean();
            Instant deadline = Instant.now().plusSeconds(1);
            CompletableFuture<JsonNode> pending = ReflectionTestUtils.invokeMethod(fixture.provider,
                    "getJsonOnceBeforeAsync", fixture.baseUrl + "/twitter/tweets?tweet_ids=one",
                    "local-test-key", fixture.pacer, deadline, degraded, 1000L, false, started);
            assertThat(fixture.guard.entered.await(2, TimeUnit.SECONDS)).isTrue();
            awaitDeadline(deadline);
            fixture.guard.release.countDown();
            assertThat(pending.get(3, TimeUnit.SECONDS)).isNull();
            fixture.awaitWorker();
            assertThat(fixture.received.get()).isZero();
            assertThat(started.get()).isFalse();
            assertThat(degraded.get()).isTrue();
            assertThat(fixture.guard.snapshotMetrics()).containsEntry("hourlyUsed", 1L);
        }
    }

    @Test
    void relationshipRequestExpiredDuringReservationDoesNotMarkAttemptOrReachHttp(@TempDir Path dir) throws Exception {
        try (Fixture fixture = new Fixture(dir)) {
            AtomicBoolean degraded = new AtomicBoolean();
            Map<String, Long> attemptedAt = new ConcurrentHashMap<>();
            var peer = new XUserSummary("peer-id", "peer_user", "Peer", "", "", false, 1, 1, 0, "");
            Instant deadline = Instant.now().plusSeconds(1);
            CompletableFuture<?> pending = ReflectionTestUtils.invokeMethod(fixture.provider,
                    "checkRelationshipOnce", fixture.baseUrl, "self_user", peer, "local-test-key",
                    fixture.pacer, deadline, degraded, attemptedAt);
            assertThat(fixture.guard.entered.await(2, TimeUnit.SECONDS)).isTrue();
            awaitDeadline(deadline);
            fixture.guard.release.countDown();
            assertThat(pending.get(3, TimeUnit.SECONDS)).isNull();
            fixture.awaitWorker();
            assertThat(fixture.received.get()).isZero();
            assertThat(attemptedAt).isEmpty();
            assertThat(fixture.guard.snapshotMetrics()).containsEntry("hourlyUsed", 1L);
        }
    }

    @Test
    void ordinaryReadInterruptedAfterReservationDoesNotSendOrRetry(@TempDir Path dir) throws Exception {
        try (Fixture fixture = new Fixture(dir)) {
            fixture.guard.interruptOnRelease = true;
            CompletableFuture<JsonNode> pending = ReflectionTestUtils.invokeMethod(fixture.provider,
                    "getJsonWithRetryAsync", fixture.baseUrl + "/twitter/user/info?userName=test",
                    "local-test-key", fixture.pacer);
            assertThat(fixture.guard.entered.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.guard.release.countDown();
            Throwable failure = pending.handle((value, error) -> error).get(3, TimeUnit.SECONDS);
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            assertThat(failure).isInstanceOf(XDataProviderException.class);
            assertThat(((XDataProviderException) failure).getStatusCode()).isEqualTo(503);
            fixture.awaitWorker();
            assertThat(fixture.received.get()).isZero();
            assertThat(fixture.guard.snapshotMetrics()).containsEntry("hourlyUsed", 1L);
        }
    }

    @Test
    void paidFallbackDeadlineReturnsDuringContendedBudgetAndNeverStartsHttp(@TempDir Path dir) throws Exception {
        try (Fixture fixture = new Fixture(dir)) {
            Instant deadline = Instant.now().plusSeconds(1);
            CompletableFuture<JsonNode> pending = ReflectionTestUtils.invokeMethod(fixture.provider,
                    "getJsonWithRetryAsync", fixture.baseUrl + "/twitter/tweet/advanced_search?queryType=Latest",
                    "local-test-key", fixture.pacer, deadline, SearchReplyRoots.empty());
            assertThat(fixture.guard.entered.await(2, TimeUnit.SECONDS)).isTrue();
            Throwable failure = pending.handle((value, error) -> error).get(2, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOfSatisfying(XDataProviderException.class,
                    providerError -> assertThat(providerError.getStatusCode()).isEqualTo(504));
            fixture.guard.release.countDown();
            fixture.awaitWorker();
            assertThat(fixture.received.get()).isZero();
        }
    }

    private static void awaitDeadline(Instant deadline) throws InterruptedException {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining >= 0) Thread.sleep(remaining + 20L);
    }

    private static final class BlockingBudgetGuard extends UpstreamBudgetGuard {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean interruptOnRelease;

        private BlockingBudgetGuard(XOpsProperties props) {
            super(props, true);
        }

        @Override
        public void acquirePermit(String url, String category) {
            // Exercise the real persistent reservation, then hold it like contended filesystem work.
            super.acquirePermit(url, category);
            entered.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reservation test gate timed out");
                        return;
                    } catch (InterruptedException ex) {
                        // Model a non-interruptible section, preserving the signal for the caller.
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted || interruptOnRelease) Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger received = new AtomicInteger();
        private final HttpServer server;
        private final SharedApiExecutor executor;
        private final BlockingBudgetGuard guard;
        private final TwitterApiIoProvider provider;
        private final String baseUrl;
        private final Object pacer;

        private Fixture(Path dir) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                received.incrementAndGet();
                byte[] body = "{\"status\":\"success\",\"tweets\":[],\"data\":{\"following\":true,\"followed_by\":true}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var props = new XOpsProperties();
            props.setDiskCacheDir(dir.toString());
            props.setTwitterapiIoBaseUrl(baseUrl);
            props.setTwitterapiIoKey("local-test-key");
            props.setApiPoolCoreSize(1);
            props.setApiPoolMaxSize(1);
            props.setApiPoolQueueCapacity(8);
            props.setApiQueueTimeoutMs(5000);
            executor = new SharedApiExecutor(props);
            guard = new BlockingBudgetGuard(props);
            provider = new TwitterApiIoProvider(props, new ObjectMapper(), executor, guard);
            pacer = ReflectionTestUtils.invokeMethod(provider, "pacerFor", "local-test-key");
        }

        private void awaitWorker() throws Exception {
            // With one worker, this completes only after the held request has fully left its callable.
            executor.submitAsync(() -> true).get(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            guard.release.countDown();
            executor.shutdown();
            server.stop(0);
        }
    }
}
