package com.xops.hub.config;

import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserConcurrencyHardeningTest {
    @Test
    void fiftyDistinctUsersExecuteNormallyWhileFiftyFirstIsPaced() throws Exception {
        XOpsProperties props = properties();
        props.setOverCapacityBaseDelayMs(10);
        props.setOverCapacityMaxDelayMs(20);
        var filter = new UserConcurrencyThrottleFilter(props);
        var pool = Executors.newFixedThreadPool(50);
        CountDownLatch entered = new CountDownLatch(50);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> work = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                String user = "user-" + i;
                work.add(pool.submit(() -> invoke(filter, user, (req, res) -> {
                    entered.countDown();
                    await(release);
                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var extra = invoke(filter, "user-51", (req, res) -> {});
            assertThat(extra.getStatus()).isEqualTo(200);
            assertThat(extra.getHeader("X-RateLimit-Mode")).isEqualTo("throttled");
            assertThat(Long.parseLong(extra.getHeader("X-Throttle-Delay-Ms"))).isGreaterThanOrEqualTo(10);
            release.countDown();
            for (var future : work) {
                var response = future.get(5, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getHeader("X-RateLimit-Mode")).isEqualTo("normal");
            }
            assertThat(filter.snapshotMetrics()).containsEntry("residentNormalUsers", 50).containsEntry("queuedWaiters", 0);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void singleUserCannotBypassFourExecutingRequestsAndInterruptReleasesWaiter() throws Exception {
        var filter = new UserConcurrencyThrottleFilter(properties());
        var pool = Executors.newFixedThreadPool(4);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> running = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        var waitingResponse = new MockHttpServletResponse();
        Thread waiter = new Thread(() -> {
            try {
                filter.doFilter(request("one-user"), waitingResponse,
                        (req, res) -> { throw new AssertionError("fifth request bypassed per-user concurrency"); });
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        try {
            for (int i = 0; i < 4; i++) running.add(pool.submit(() -> invoke(filter, "one-user", (req, res) -> {
                entered.countDown();
                await(release);
            })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            waiter.start();
            awaitQueueSize(filter, 1);
            assertThat(waiter.isAlive()).isTrue();
            waiter.interrupt();
            waiter.join(3000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(interruptPreserved.get()).isTrue();
            assertThat(waitingResponse.getStatus()).isEqualTo(503);
            assertThat(waitingResponse.getContentAsString()).contains("REQUEST_INTERRUPTED");
            assertThat(filter.snapshotMetrics()).containsEntry("queuedWaiters", 0);
            release.countDown();
            for (var future : running) future.get(5, TimeUnit.SECONDS);
            assertThat(invoke(filter, "one-user", (req, res) -> {}).getHeader("X-RateLimit-Mode")).isEqualTo("normal");
        } finally {
            release.countDown();
            waiter.interrupt();
            pool.shutdownNow();
        }
    }

    @Test
    void queueCapacityIsBoundedAndDrainsAfterBusyUsersFinish() throws Exception {
        XOpsProperties props = properties();
        props.setMaxThrottleQueueSize(8);
        props.setMaxThrottleWaitMs(5000);
        var filter = new UserConcurrencyThrottleFilter(props);
        var pool = Executors.newFixedThreadPool(12);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> running = new ArrayList<>();
        List<Future<MockHttpServletResponse>> queued = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) running.add(pool.submit(() -> invoke(filter, "busy-user", (req, res) -> {
                entered.countDown();
                await(release);
            })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 8; i++) queued.add(pool.submit(() -> invoke(filter, "busy-user", (req, res) -> {})));
            awaitQueueSize(filter, 8);
            var overflow = invoke(filter, "busy-user", (req, res) -> { throw new AssertionError("full queue entered application"); });
            assertThat(overflow.getStatus()).isEqualTo(503);
            assertThat(overflow.getHeader("Retry-After")).isEqualTo("3");
            assertThat(filter.snapshotMetrics()).containsEntry("queuedWaiters", 8);
            release.countDown();
            for (var future : running) future.get(5, TimeUnit.SECONDS);
            for (var future : queued) assertThat(future.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(filter.snapshotMetrics()).containsEntry("queuedWaiters", 0);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void pacingCannotExceedTotalWaitDeadlineAndFailuresReturnPermits() throws Exception {
        XOpsProperties props = properties();
        props.setMaxConcurrentUsers(1);
        props.setMaxThrottleWaitMs(200);
        props.setOverCapacityBaseDelayMs(5000);
        props.setOverCapacityMaxDelayMs(5000);
        var filter = new UserConcurrencyThrottleFilter(props);
        invoke(filter, "resident", (req, res) -> {});
        long start = System.nanoTime();
        var timedOut = invoke(filter, "overflow", (req, res) -> { throw new AssertionError("expired request executed"); });
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(timedOut.getStatus()).isEqualTo(503);
        assertThat(elapsedMs).isLessThan(1500);
        assertThat(filter.snapshotMetrics()).containsEntry("queuedWaiters", 0);
        assertThat(invoke(filter, "resident", (req, res) -> {}).getStatus()).isEqualTo(200);

        props.setOverCapacityBaseDelayMs(0);
        props.setOverCapacityMaxDelayMs(0);
        var failureFilter = new UserConcurrencyThrottleFilter(props);
        invoke(failureFilter, "resident", (req, res) -> {});
        assertThatThrownBy(() -> invoke(failureFilter, "overflow", (req, res) -> { throw new IOException("client disconnected"); }))
                .isInstanceOf(IOException.class);
        assertThat(failureFilter.snapshotMetrics()).containsEntry("queuedWaiters", 0);
        Semaphore overflowPermits = (Semaphore) ReflectionTestUtils.getField(failureFilter, "overflowSemaphore");
        assertThat(overflowPermits.availablePermits()).isEqualTo(4);
        assertThat(invoke(failureFilter, "overflow", (req, res) -> {}).getStatus()).isEqualTo(200);
    }

    @Test
    void slowReadsAndMediaLoadsDoNotStarveScheduledTweetMutations() throws Exception {
        var filter = new UserConcurrencyThrottleFilter(properties());
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch readsAndMediaEntered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> running = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                running.add(pool.submit(() -> {
                    var res = new MockHttpServletResponse();
                    filter.doFilter(request("GET", "/api/analyze/bundle", "busy-author"), res, (rq, rs) -> {
                        readsAndMediaEntered.countDown();
                        await(release);
                    });
                    return res;
                }));
            }
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                running.add(pool.submit(() -> {
                    var res = new MockHttpServletResponse();
                    filter.doFilter(request("GET", "/api/scheduled-tweets/job-1/media/asset-" + idx, "busy-author"), res, (rq, rs) -> {
                        readsAndMediaEntered.countDown();
                        await(release);
                    });
                    return res;
                }));
            }
            assertThat(readsAndMediaEntered.await(5, TimeUnit.SECONDS)).isTrue();

            var saveResp = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/scheduled-tweets", "busy-author"), saveResp, (rq, rs) -> {});
            assertThat(saveResp.getStatus()).isEqualTo(200);
            assertThat(saveResp.getHeader("X-RateLimit-Mode")).isEqualTo("normal");

            var deleteResp = new MockHttpServletResponse();
            filter.doFilter(request("DELETE", "/api/scheduled-tweets/job-1", "busy-author"), deleteResp, (rq, rs) -> {});
            assertThat(deleteResp.getStatus()).isEqualTo(200);
            assertThat(deleteResp.getHeader("X-RateLimit-Mode")).isEqualTo("normal");
        } finally {
            release.countDown();
            for (var f : running) {
                try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
        }
    }

    private static XOpsProperties properties() {
        XOpsProperties props = new XOpsProperties();
        props.setMaxConcurrentUsers(50);
        props.setOverCapacityBaseDelayMs(0);
        props.setOverCapacityMaxDelayMs(0);
        return props;
    }

    private static MockHttpServletResponse invoke(UserConcurrencyThrottleFilter filter, String user, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(user), response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String user) {
        return request("GET", "/api/scheduled-tweets", user);
    }

    private static MockHttpServletRequest request(String method, String path, String user) {
        var request = new MockHttpServletRequest(method, path);
        var session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser(user, user, user, "", false, Instant.EPOCH));
        request.setSession(session);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private static void await(CountDownLatch latch) throws ServletException {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new ServletException("test gate timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServletException(ex);
        }
    }

    private static void awaitQueueSize(UserConcurrencyThrottleFilter filter, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((int) filter.snapshotMetrics().get("queuedWaiters") != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(filter.snapshotMetrics()).containsEntry("queuedWaiters", expected);
    }
}
