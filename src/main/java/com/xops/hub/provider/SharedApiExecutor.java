package com.xops.hub.provider;

import com.xops.hub.config.XOpsProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局共用的外部 API 调用有界线程池：
 * 1. 所有外部 HTTP API 请求以「单次请求」粒度投递，绝不在池内阻塞等待子任务（彻底消除嵌套死锁，高峰期多用户请求自动按 FIFO 公平交错执行）；
 * 2. 采用扩容优先有界队列（Scale-First Bounded Queue）：并发未满 maxPoolSize 时立即扩容零排队，满载后最多缓冲 queueCapacity 个请求；
 * 3. 排队超时守卫：若任务在队列中等待超过 apiQueueTimeoutMs 仍未拿到执行线程，立即中止并快速降级/返回 503，绝不堆积长队或白白消耗付费 API 额度；
 * 4. 自适应并发度建议：低峰空闲时允许单流按时间分片多路并发加速，高峰繁忙时自动收缩为单路串行。
 */
@Component
public class SharedApiExecutor {

    private static final Logger log = LoggerFactory.getLogger(SharedApiExecutor.class);
    private static final AtomicInteger POOL_SEQ = new AtomicInteger(1);

    /** Admission failed before the supplied callable could start. */
    public static final class NotStartedException extends XDataProviderException {
        public NotStartedException(String message) {
            super(503, message);
        }
    }

    private static final class ScaleFirstBoundedQueue extends ArrayBlockingQueue<Runnable> {
        private volatile ThreadPoolExecutor executor;

        ScaleFirstBoundedQueue(int capacity) {
            super(Math.max(1, capacity));
        }

        void bind(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public boolean offer(Runnable r) {
            ThreadPoolExecutor tp = this.executor;
            if (tp != null && tp.getPoolSize() < tp.getMaximumPoolSize() && tp.getActiveCount() >= tp.getPoolSize()) {
                return false;
            }
            return super.offer(r);
        }

        boolean offerBounded(Runnable r) {
            return super.offer(r);
        }
    }

    private final int coreSize;
    private final int maxSize;
    private final int queueCapacity;
    private final long queueTimeoutMs;
    private final int maxParallelSlices;
    private final ScaleFirstBoundedQueue workQueue;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService queueWatchdog;

    private final AtomicLong submittedCount = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong queueTimedOutCount = new AtomicLong(0);

    public SharedApiExecutor(XOpsProperties properties) {
        this.coreSize = Math.max(1, properties.getApiPoolCoreSize());
        this.maxSize = Math.max(this.coreSize, properties.getApiPoolMaxSize());
        this.queueCapacity = Math.max(1, properties.getApiPoolQueueCapacity());
        this.queueTimeoutMs = Math.max(0L, properties.getApiQueueTimeoutMs());
        this.maxParallelSlices = Math.max(1, Math.min(16, properties.getApiMaxParallelSlices()));

        int poolId = POOL_SEQ.getAndIncrement();
        AtomicInteger workerSeq = new AtomicInteger(1);
        this.workQueue = new ScaleFirstBoundedQueue(this.queueCapacity);
        this.executor = new ThreadPoolExecutor(
                this.coreSize,
                this.maxSize,
                60L,
                TimeUnit.SECONDS,
                this.workQueue,
                r -> {
                    Thread t = new Thread(r, "xops-api-pool-" + poolId + "-" + workerSeq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                (r, tp) -> {
                    if (tp.isShutdown() || !this.workQueue.offerBounded(r)) {
                        rejectedCount.incrementAndGet();
                        throw new RejectedExecutionException("Shared API queue saturated");
                    }
                    if (tp.isShutdown() && tp.remove(r)) {
                        rejectedCount.incrementAndGet();
                        throw new RejectedExecutionException("Shared API executor shut down during admission");
                    }
                }
        );
        this.workQueue.bind(this.executor);

        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "xops-api-qwatch-" + poolId);
            t.setDaemon(true);
            return t;
        });
        watchdog.setRemoveOnCancelPolicy(true);
        this.queueWatchdog = watchdog;
    }

    public <T> CompletableFuture<T> submitAsync(Callable<T> apiCall) {
        submittedCount.incrementAndGet();
        long enqueuedNanos = System.nanoTime();
        AtomicBoolean claimed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
        CompletableFuture<T> future = new CompletableFuture<>();
        PaidCapabilityGate.CallerScope capturedScope = PaidCapabilityGate.currentScope();
        FutureTask<T> task = new FutureTask<>(apiCall) {
            @Override
            public void run() {
                if (!claimed.compareAndSet(false, true)) return;
                ScheduledFuture<?> timer = timeout.get();
                if (timer != null) timer.cancel(false);
                if (future.isDone()) {
                    cancel(false);
                    return;
                }
                long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueuedNanos);
                if (queueTimeoutMs > 0 && waitedMs > queueTimeoutMs) {
                    queueTimedOutCount.incrementAndGet();
                    future.completeExceptionally(new NotStartedException("当前请求排队等待超时，请稍后再试"));
                    cancel(false);
                    return;
                }
                PaidCapabilityGate.CallerScope prevScope = PaidCapabilityGate.currentScope();
                PaidCapabilityGate.bindScope(capturedScope);
                try {
                    super.run();
                } finally {
                    PaidCapabilityGate.bindScope(prevScope);
                }
            }
            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        future.cancel(false);
                    } else {
                        T value = get();
                        completedCount.incrementAndGet();
                        future.complete(value);
                    }
                } catch (ExecutionException ex) {
                    future.completeExceptionally(ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(ex);
                }
            }
        };
        future.whenComplete((value, failure) -> {
            ScheduledFuture<?> timer = timeout.get();
            if (timer != null) timer.cancel(false);
            // A cancelled/timed-out caller must release queue space and cannot start paid work later.
            if (!task.isDone()) task.cancel(true);
            executor.remove(task);
        });
        try {
            if (queueTimeoutMs > 0) {
                ScheduledFuture<?> timer = queueWatchdog.schedule(() -> {
                    if (claimed.compareAndSet(false, true)) {
                        queueTimedOutCount.incrementAndGet();
                        future.completeExceptionally(new NotStartedException("当前请求排队等待超时，请稍后再试"));
                    }
                }, queueTimeoutMs, TimeUnit.MILLISECONDS);
                timeout.set(timer);
                if (future.isDone()) timer.cancel(false);
            }
            if (!future.isDone()) {
                executor.execute(task);
                // Completion may race between the precheck and queue admission.
                if (future.isDone()) executor.remove(task);
            }
        } catch (RejectedExecutionException ex) {
            claimed.set(true);
            future.completeExceptionally(new NotStartedException("当前外部请求队列繁忙，请稍后再试"));
        }
        return future;
    }

    public <T> T execute(Callable<T> apiCall) {
        CompletableFuture<T> future = submitAsync(apiCall);
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new XDataProviderException(503, "外部调用等待已中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error error) throw error;
            throw new XDataProviderException(502, "执行外部 API 调用失败", cause);
        } catch (CancellationException ex) {
            throw new XDataProviderException(503, "外部调用已取消", ex);
        }
    }

    /**
     * 根据剩余时间跨度、预估页数与线程池实时水位，动态决定单个数据流的分片并发数：
     * - 低峰空闲：最多并发 maxParallelSlices 路分片拉取（首次冷启动拉取翻倍为 2 * maxParallelSlices）；
     * - 中等负载：常规收缩至最多 2 路（首次冷启动收缩至最多 4 路）；
     * - 高峰排队：严格降为 1 路串行，避免单用户放大请求数挤压他人。
     */
    public int suggestSliceConcurrency(long remainingSpanSeconds, int estimatedPages, int remainingBudget, boolean slowMode) {
        return suggestSliceConcurrency(remainingSpanSeconds, estimatedPages, remainingBudget, slowMode, false);
    }

    public int suggestSliceConcurrency(
            long remainingSpanSeconds,
            int estimatedPages,
            int remainingBudget,
            boolean slowMode,
            boolean initialFetch
    ) {
        int sliceCap = initialFetch ? Math.min(16, maxParallelSlices * 2) : maxParallelSlices;
        if (slowMode || sliceCap <= 1 || estimatedPages <= 1 || remainingBudget <= 1 || remainingSpanSeconds < 1800L) {
            return 1;
        }
        int queued = workQueue.size();
        int active = executor.getActiveCount();
        if (queued >= Math.max(2, queueCapacity / 16) || active >= Math.max(1, (maxSize * 3) / 4)) {
            return 1;
        }
        int spanCap;
        if (initialFetch) {
            spanCap = remainingSpanSeconds >= 12 * 3600L ? sliceCap
                    : (remainingSpanSeconds >= 6 * 3600L
                            ? Math.min(sliceCap, Math.max(6, (sliceCap * 3) / 4))
                            : Math.min(sliceCap, Math.max(4, sliceCap / 2)));
        } else {
            spanCap = remainingSpanSeconds >= 24 * 3600L ? sliceCap
                    : (remainingSpanSeconds >= 6 * 3600L
                            ? Math.min(sliceCap, Math.max(3, (sliceCap * 3) / 4))
                            : Math.min(sliceCap, Math.max(2, sliceCap / 2)));
        }
        int desired = Math.min(spanCap, Math.min(estimatedPages, Math.max(1, remainingBudget / 2)));
        if (queued > 0 || active > maxSize / 3) {
            return Math.min(initialFetch ? 3 : 2, desired);
        }
        return Math.max(1, desired);
    }

    public Map<String, Object> snapshotMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("corePoolSize", coreSize);
        m.put("maxPoolSize", maxSize);
        m.put("currentPoolSize", executor.getPoolSize());
        m.put("activeThreads", executor.getActiveCount());
        m.put("queuedTasks", workQueue.size());
        m.put("queueCapacity", queueCapacity);
        m.put("queueTimeoutMs", queueTimeoutMs);
        m.put("maxParallelSlices", maxParallelSlices);
        m.put("initialMaxParallelSlices", Math.min(16, maxParallelSlices * 2));
        m.put("submittedTasks", submittedCount.get());
        m.put("completedTasks", completedCount.get());
        m.put("rejectedTasks", rejectedCount.get());
        m.put("queueTimedOutTasks", queueTimedOutCount.get());
        return m;
    }

    @PreDestroy
    public void shutdown() {
        queueWatchdog.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow().forEach(task -> {
                    if (task instanceof FutureTask<?> pending) pending.cancel(false);
                });
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow().forEach(task -> {
                if (task instanceof FutureTask<?> pending) pending.cancel(false);
            });
        }
        log.debug("SharedApiExecutor shut down cleanly");
    }
}
