package com.xops.hub.provider;
import com.xops.hub.config.XOpsProperties;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SharedApiExecutorCancellationTest {
    private SharedApiExecutor executor(long timeoutMs) {
        XOpsProperties p = new XOpsProperties();
        p.setApiPoolCoreSize(1); p.setApiPoolMaxSize(1); p.setApiPoolQueueCapacity(1);
        p.setApiQueueTimeoutMs(timeoutMs); return new SharedApiExecutor(p);
    }
    @Test void cancelledQueuedCallReleasesCapacityAndNeverRuns() throws Exception {
        SharedApiExecutor pool = executor(5000);
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean paidCallStarted = new AtomicBoolean();
        try {
            var first = pool.submitAsync(() -> { running.countDown(); release.await(); return 1; });
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            var cancelled = pool.submitAsync(() -> { paidCallStarted.set(true); return 2; });
            assertThat(cancelled.cancel(true)).isTrue();
            assertThat(pool.snapshotMetrics().get("queuedTasks")).isEqualTo(0);
            var replacement = pool.submitAsync(() -> 3); release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(replacement.get(2, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(paidCallStarted).isFalse();
        } finally { release.countDown(); pool.shutdown(); }
    }
    @Test void timedOutQueueEntryIsRemovedBeforeWorkerBecomesAvailable() throws Exception {
        SharedApiExecutor pool = executor(100);
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean paidCallStarted = new AtomicBoolean();
        try {
            pool.submitAsync(() -> { running.countDown(); release.await(); return 1; });
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            var expired = pool.submitAsync(() -> { paidCallStarted.set(true); return 2; });
            assertThatThrownBy(() -> expired.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(XDataProviderException.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((int) pool.snapshotMetrics().get("queuedTasks") != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(pool.snapshotMetrics().get("queuedTasks")).isEqualTo(0);
            release.countDown(); assertThat(paidCallStarted).isFalse();
        } finally { release.countDown(); pool.shutdown(); }
    }
    @Test void submissionsAfterShutdownFinishPromptlyInsteadOfHanging() {
        SharedApiExecutor pool = executor(100); pool.shutdown();
        assertThat(pool.submitAsync(() -> 1).isCompletedExceptionally()).isTrue();
    }
}
