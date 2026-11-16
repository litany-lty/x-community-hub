package com.xops.hub.config;

import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 50 并发用户全速保障 + 超 50 人平滑接口限速排队网关（不直接拒绝正常超员用户）：
 * 1. 前 maxConcurrentUsers（默认 50）名活跃用户占用常驻高速席位（resident=true），正常并发零人为延迟；
 * 2. 第 51 名及以上超员用户不直接拒绝，自动进入平滑比例限速（Proportional Leaky-Bucket Pacing）+ 公平 FIFO 信号量排队；
 * 3. 常驻用户与超员用户使用独立处理许可，每用户最多四个执行中请求，总等待数受 maxThrottleQueueSize 限制。
 * 等待超时或队列已满时返回可重试的 503；正常超过 50 人仅增加等待。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UserConcurrencyThrottleFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_USERS = 4096;
    private static final int MAX_PARALLEL_PER_RESIDENT_USER = 4;
    private static final long MIN_STAGGER_STEP_MS = 12L;

    private static final class UserSlot {
        final AtomicLong lastSeenMs;
        final AtomicInteger inFlight = new AtomicInteger(0);
        final Semaphore executionPermits = new Semaphore(MAX_PARALLEL_PER_RESIDENT_USER, true);
        volatile boolean resident;

        UserSlot(long nowMs, boolean resident) {
            this.lastSeenMs = new AtomicLong(nowMs);
            this.resident = resident;
        }
    }

    private final int maxUsers;
    private final int maxQueueSize;
    private final long maxWaitMs;
    private final long activeWindowMs;
    private final long baseDelayMs;
    private final long maxDelayMs;

    private final Object slotMonitor = new Object();
    private final Map<String, UserSlot> activeUsers = new LinkedHashMap<>();
    private final Semaphore residentSemaphore;
    private final Semaphore overflowSemaphore;
    private final AtomicInteger residentSlotCount = new AtomicInteger(0);
    private final AtomicInteger queuedWaiters = new AtomicInteger(0);
    private final AtomicLong nextStaggerReadyNanos = new AtomicLong(Long.MIN_VALUE);
    private volatile long lastPruneMs = 0L;

    private final AtomicLong normalHandledCount = new AtomicLong(0L);
    private final AtomicLong throttledHandledCount = new AtomicLong(0L);
    private final AtomicLong queueOverflowRejectedCount = new AtomicLong(0L);

    public UserConcurrencyThrottleFilter(XOpsProperties properties) {
        XOpsProperties props = properties != null ? properties : new XOpsProperties();
        this.maxUsers = Math.max(1, props.getMaxConcurrentUsers());
        this.maxQueueSize = Math.max(8, props.getMaxThrottleQueueSize());
        this.maxWaitMs = Math.max(200L, props.getMaxThrottleWaitMs());
        this.activeWindowMs = Math.max(2000L, props.getActiveUserWindowSeconds() * 1000L);
        this.baseDelayMs = Math.max(0L, props.getOverCapacityBaseDelayMs());
        this.maxDelayMs = Math.max(this.baseDelayMs, props.getOverCapacityMaxDelayMs());
        int residentPermits = Math.max(this.maxUsers, Math.min(100, this.maxUsers * 2));
        int overflowPermits = Math.max(4, Math.min(25, (this.maxUsers + 1) / 2));
        this.residentSemaphore = new Semaphore(residentPermits, true);
        this.overflowSemaphore = new Semaphore(overflowPermits, true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = RequestSecuritySupport.requestPath(request);
        if (uri == null || !uri.startsWith("/api/")) {
            return true;
        }
        return "/api/health".equals(uri) || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long nowMs = System.currentTimeMillis();
        String userKey = resolveUserIdentityKey(request);
        UserSlot slot = resolveOrAllocateSlot(userKey, nowMs);
        boolean eligibleFastResident = slot != null && slot.resident;
        boolean userPermit = false;
        boolean residentPermit = false;
        try {
            // 有排队者时禁止新到请求抢占公平信号量。
            if (eligibleFastResident) {
                userPermit = slot.executionPermits.tryAcquire(0L, TimeUnit.NANOSECONDS);
                if (userPermit) {
                    residentPermit = residentSemaphore.tryAcquire(0L, TimeUnit.NANOSECONDS);
                    if (residentPermit) {
                        normalHandledCount.incrementAndGet();
                        response.setHeader("X-RateLimit-Mode", "normal");
                        filterChain.doFilter(request, response);
                        return;
                    }
                    slot.executionPermits.release();
                    userPermit = false;
                }
            }
            executeSmoothThrottled(request, response, filterChain, slot, eligibleFastResident);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            rejectBusy(request, response, "REQUEST_INTERRUPTED", "请求排队已中止，请稍候重试");
        } finally {
            if (residentPermit) residentSemaphore.release();
            if (userPermit) slot.executionPermits.release();
            if (slot != null) {
                synchronized (slotMonitor) {
                    slot.lastSeenMs.set(System.currentTimeMillis());
                    slot.inFlight.decrementAndGet();
                }
            }
        }
    }

    private void executeSmoothThrottled(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            UserSlot slot,
            boolean residentBurst
    ) throws ServletException, IOException, InterruptedException {
        int currentWaiters = queuedWaiters.incrementAndGet();
        if (currentWaiters > maxQueueSize) {
            queuedWaiters.decrementAndGet();
            rejectBusy(request, response, "SERVER_BUSY", "当前访问高峰排队队列已满，请稍候再试");
            return;
        }

        long enterWaitNanos = System.nanoTime();
        long deadlineNanos = enterWaitNanos + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        Semaphore targetSemaphore = residentBurst ? residentSemaphore : overflowSemaphore;
        boolean acquired = false;
        boolean userAcquired = false;
        boolean waiterReleased = false;
        try {
            long targetPaceNanos = residentBurst
                    ? TimeUnit.MILLISECONDS.toNanos(Math.min(80L, baseDelayMs))
                    : computeOverCapacityDelayNanos(enterWaitNanos);
            long remaining = deadlineNanos - System.nanoTime();
            if (targetPaceNanos > 0L && remaining > 0L) {
                TimeUnit.NANOSECONDS.sleep(Math.min(targetPaceNanos, remaining));
            }
            remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0L && slot != null) {
                userAcquired = slot.executionPermits.tryAcquire(remaining, TimeUnit.NANOSECONDS);
            }
            remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0L && (slot == null || userAcquired)) {
                acquired = targetSemaphore.tryAcquire(remaining, TimeUnit.NANOSECONDS);
            }
            queuedWaiters.decrementAndGet();
            waiterReleased = true;
            if (!acquired) {
                rejectBusy(request, response, "SERVER_BUSY", "当前访问高峰排队等待超时，请稍候再试");
                return;
            }

            throttledHandledCount.incrementAndGet();
            response.setHeader("X-RateLimit-Mode", "throttled");
            response.setHeader("X-Throttle-Delay-Ms",
                    Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enterWaitNanos)));
            filterChain.doFilter(request, response);
        } finally {
            if (!waiterReleased) queuedWaiters.decrementAndGet();
            if (acquired) targetSemaphore.release();
            if (userAcquired) slot.executionPermits.release();
        }
    }

    private void rejectBusy(HttpServletRequest request, HttpServletResponse response,
                            String code, String message) throws IOException {
        queueOverflowRejectedCount.incrementAndGet();
        response.setHeader("Retry-After", "3");
        MaliciousRequestShieldFilter.rejectJsonOrEmpty(request, response,
                HttpServletResponse.SC_SERVICE_UNAVAILABLE, code, message);
    }

    private long computeOverCapacityDelayNanos(long nowNanos) {
        if (baseDelayMs <= 0L && maxDelayMs <= 0L) return 0L;
        int totalActive;
        synchronized (slotMonitor) {
            totalActive = Math.max(maxUsers + 1, activeUsers.size());
        }
        int excessUsers = Math.max(1, totalActive - maxUsers);
        double ratio = Math.min(1.0, (double) excessUsers / (double) maxUsers);
        long proportionalMs = baseDelayMs + Math.round(ratio * Math.max(0L, maxDelayMs - baseDelayMs));
        long staggerBase = nowNanos + TimeUnit.MILLISECONDS.toNanos(proportionalMs);
        long latestNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        long scheduledNanos = nextStaggerReadyNanos.updateAndGet(previous -> Math.min(latestNanos,
                previous == Long.MIN_VALUE ? staggerBase
                        : Math.max(staggerBase, previous + TimeUnit.MILLISECONDS.toNanos(MIN_STAGGER_STEP_MS))));
        return Math.max(0L, scheduledNanos - nowNanos);
    }

    private UserSlot resolveOrAllocateSlot(String userKey, long nowMs) {
        synchronized (slotMonitor) {
            if (nowMs - lastPruneMs >= 1000L) pruneExpiredLocked(nowMs);
            UserSlot existing = activeUsers.get(userKey);
            if (existing != null) {
                existing.lastSeenMs.set(nowMs);
                existing.inFlight.incrementAndGet();
                return existing;
            }
            if (activeUsers.size() >= MAX_TRACKED_USERS) {
                evictOneNonResidentLocked();
                if (activeUsers.size() >= MAX_TRACKED_USERS) return null;
            }
            boolean grantResident = residentSlotCount.get() < maxUsers;
            UserSlot created = new UserSlot(nowMs, grantResident);
            created.inFlight.incrementAndGet();
            activeUsers.put(userKey, created);
            if (grantResident) residentSlotCount.incrementAndGet();
            return created;
        }
    }

    private void pruneExpiredLocked(long nowMs) {
        lastPruneMs = nowMs;
        int residentsAfterPrune = 0;
        Iterator<Map.Entry<String, UserSlot>> it = activeUsers.entrySet().iterator();
        while (it.hasNext()) {
            UserSlot slot = it.next().getValue();
            if (slot.inFlight.get() <= 0 && (nowMs - slot.lastSeenMs.get()) > activeWindowMs) {
                it.remove();
            } else if (slot.resident) {
                residentsAfterPrune++;
            }
        }
        if (residentsAfterPrune < maxUsers && activeUsers.size() > residentsAfterPrune) {
            for (UserSlot slot : activeUsers.values()) {
                if (residentsAfterPrune >= maxUsers) {
                    break;
                }
                if (!slot.resident) {
                    slot.resident = true;
                    residentsAfterPrune++;
                }
            }
        }
        residentSlotCount.set(residentsAfterPrune);
    }

    private void evictOneNonResidentLocked() {
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, UserSlot> entry : activeUsers.entrySet()) {
            UserSlot s = entry.getValue();
            if (!s.resident && s.inFlight.get() <= 0) {
                long ts = s.lastSeenMs.get();
                if (ts < oldestTs) {
                    oldestTs = ts;
                    oldestKey = entry.getKey();
                }
            }
        }
        if (oldestKey != null) {
            activeUsers.remove(oldestKey);
        }
    }

    private static String resolveUserIdentityKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object rawUser = session.getAttribute(XOAuthController.SESSION_AUTH_USER);
            if (rawUser instanceof AuthenticatedXUser user) {
                if (user.id() != null && !user.id().isBlank()) {
                    return "u:" + user.id().trim();
                }
                if (user.username() != null && !user.username().isBlank()) {
                    return "uh:" + user.username().trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "ip:" + RequestSecuritySupport.extractClientIp(request);
    }

    public Map<String, Object> snapshotMetrics() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastPruneMs >= 5000L) {
            synchronized (slotMonitor) {
                if (nowMs - lastPruneMs >= 5000L) {
                    pruneExpiredLocked(nowMs);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxConcurrentUsers", maxUsers);
        synchronized (slotMonitor) {
            m.put("activeUsers", activeUsers.size());
        }
        m.put("residentNormalUsers", residentSlotCount.get());
        m.put("queuedWaiters", queuedWaiters.get());
        m.put("maxThrottleQueueSize", maxQueueSize);
        m.put("normalHandledRequests", normalHandledCount.get());
        m.put("throttledHandledRequests", throttledHandledCount.get());
        m.put("queueOverflowRejectedRequests", queueOverflowRejectedCount.get());
        return m;
    }
}
