package com.xops.hub.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Unified gate and 10-minute idempotent retry coordinator for all paid capabilities:
 * 1. Only {@code @elidlicom} is permitted to invoke paid upstream capabilities; all other callers fail closed (HTTP 403).
 * 2. Privileged {@code @elidlicom} calls bypass {@link UpstreamBudgetGuard} accounting/budget caps completely.
 * 3. Failed paid operations are scheduled for idempotent retry every 10 minutes, up to 3 retries maximum.
 */
@Component
public class PaidCapabilityGate {
    private static final Logger log = LoggerFactory.getLogger(PaidCapabilityGate.class);

    public static final String PRIVILEGED_USERNAME = "elidlicom";
    public static final long RETRY_INTERVAL_MS = 10 * 60_000L;
    public static final int MAX_IDEMPOTENT_RETRIES = 3;
    public static final String PAID_FORBIDDEN_MESSAGE = "付费能力仅对 @elidlicom 开放使用";

    private static final Set<String> PAID_GATED_CATEGORIES = Set.of(
            "twitterapi_io_read",
            "twitterapi_io_relation",
            "x_official_read",
            "x_tweet_create",
            "x_media_upload"
    );

    public record CallerScope(String username, String accountId, boolean enforced) {
        public CallerScope {
            username = normalizeUsername(username);
            accountId = accountId == null ? "" : accountId.trim();
        }

        public boolean isPrivileged() {
            return isPrivilegedUsername(username);
        }
    }

    public enum RetryState {
        PENDING,
        SUCCEEDED,
        EXHAUSTED
    }

    public record RetrySnapshot(
            String idempotencyKey,
            String category,
            String username,
            int retryCount,
            int maxRetries,
            long nextRetryAtEpochMs,
            RetryState state,
            String lastError
    ) {
    }

    private static final class RetryTaskEntry {
        final String idempotencyKey;
        final String category;
        final String username;
        final String accountId;
        volatile Runnable action;
        int retryCount;
        long nextRetryAtEpochMs;
        RetryState state;
        String lastError;
        boolean running;
        boolean reportedFailureDuringRun;

        RetryTaskEntry(
                String idempotencyKey,
                String category,
                String username,
                String accountId,
                Runnable action,
                long nextRetryAtEpochMs,
                String initialError
        ) {
            this.idempotencyKey = idempotencyKey;
            this.category = category == null ? "paid_op" : category;
            this.username = normalizeUsername(username);
            this.accountId = accountId == null ? "" : accountId.trim();
            this.action = action;
            this.retryCount = 0;
            this.nextRetryAtEpochMs = nextRetryAtEpochMs;
            this.state = RetryState.PENDING;
            this.lastError = initialError == null ? "" : initialError;
        }

        synchronized RetrySnapshot snapshot() {
            return new RetrySnapshot(
                    idempotencyKey,
                    category,
                    username,
                    retryCount,
                    MAX_IDEMPOTENT_RETRIES,
                    nextRetryAtEpochMs,
                    state,
                    lastError
            );
        }
    }

    private static final ThreadLocal<CallerScope> CURRENT_SCOPE = new ThreadLocal<>();

    private final Clock clock;
    private final ConcurrentHashMap<String, RetryTaskEntry> retryLedger = new ConcurrentHashMap<>();

    @Autowired
    public PaidCapabilityGate() {
        this(Clock.systemUTC());
    }

    public PaidCapabilityGate(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static String normalizeUsername(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        while (cleaned.startsWith("@")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    public static boolean isPrivilegedUsername(String rawUsername) {
        return PRIVILEGED_USERNAME.equalsIgnoreCase(normalizeUsername(rawUsername));
    }

    public static boolean isPaidCategory(String category) {
        return category != null && PAID_GATED_CATEGORIES.contains(category);
    }

    public static CallerScope currentScope() {
        return CURRENT_SCOPE.get();
    }

    public static boolean isCurrentCallerPrivileged() {
        CallerScope scope = CURRENT_SCOPE.get();
        return scope != null && scope.isPrivileged();
    }

    public static boolean isCurrentCallerForbidden() {
        CallerScope scope = CURRENT_SCOPE.get();
        return scope != null && scope.enforced() && !scope.isPrivileged();
    }

    public static <T> T withCaller(String username, String accountId, Supplier<T> action) {
        CallerScope previous = CURRENT_SCOPE.get();
        CURRENT_SCOPE.set(new CallerScope(username, accountId, true));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_SCOPE.remove();
            } else {
                CURRENT_SCOPE.set(previous);
            }
        }
    }

    public static void runWithCaller(String username, String accountId, Runnable action) {
        withCaller(username, accountId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> Callable<T> wrapCallable(Callable<T> delegate) {
        CallerScope captured = CURRENT_SCOPE.get();
        if (captured == null) {
            return delegate;
        }
        return () -> {
            CallerScope previous = CURRENT_SCOPE.get();
            CURRENT_SCOPE.set(captured);
            try {
                return delegate.call();
            } finally {
                if (previous == null) {
                    CURRENT_SCOPE.remove();
                } else {
                    CURRENT_SCOPE.set(previous);
                }
            }
        };
    }

    public static void bindScope(CallerScope scope) {
        if (scope == null) {
            CURRENT_SCOPE.remove();
        } else {
            CURRENT_SCOPE.set(scope);
        }
    }

    /**
     * Enforces that paid upstream categories are only executed for {@code @elidlicom}.
     * Unscoped loopback URIs remain allowed exclusively for offline unit-test HTTP fixtures.
     */
    public static void requirePaidAllowed(URI uri, String category) {
        if (category == null || !PAID_GATED_CATEGORIES.contains(category)) {
            return;
        }
        CallerScope scope = CURRENT_SCOPE.get();
        if (scope != null && scope.enforced()) {
            if (!scope.isPrivileged()) {
                throw new XDataProviderException(403, PAID_FORBIDDEN_MESSAGE);
            }
            return;
        }
        if (uri != null && !UpstreamBudgetGuard.isLocalLoopbackUri(uri)) {
            throw new XDataProviderException(403, PAID_FORBIDDEN_MESSAGE);
        }
    }

    /**
     * Registers a failed paid operation for idempotent scheduled retry in 10-minute intervals, up to 3 retries.
     * Calling this multiple times with the same {@code idempotencyKey} while an entry is already pending or exhausted
     * will NOT reset retry counts or create duplicate schedules.
     */
    public RetrySnapshot scheduleIdempotentRetry(
            String idempotencyKey,
            String category,
            String username,
            String accountId,
            String errorReason,
            Runnable retryAction
    ) {
        return scheduleIdempotentRetry(idempotencyKey, category, username, accountId, errorReason, retryAction, false);
    }

    /**
     * Registers a failed operation for idempotent scheduled retry in 10-minute intervals, up to 3 retries.
     * Passive duplicate registrations preserve existing schedule/exhausted state; an explicit new active sync
     * ({@code resetIfExhausted = true}) revives a previously exhausted key for a fresh 3-retry cycle instead of permanent failure.
     */
    public RetrySnapshot scheduleIdempotentRetry(
            String idempotencyKey,
            String category,
            String username,
            String accountId,
            String errorReason,
            Runnable retryAction,
            boolean resetIfExhausted
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || retryAction == null) {
            throw new IllegalArgumentException("idempotencyKey and retryAction are required");
        }
        String key = idempotencyKey.trim();
        long nowMs = clock.millis();
        RetryTaskEntry entry = retryLedger.compute(key, (k, existing) -> {
            if (existing == null) {
                return new RetryTaskEntry(
                        k,
                        category,
                        username,
                        accountId,
                        retryAction,
                        nowMs + RETRY_INTERVAL_MS,
                        errorReason
                );
            }
            synchronized (existing) {
                existing.action = retryAction;
                if (existing.running) {
                    existing.reportedFailureDuringRun = true;
                    existing.lastError = errorReason == null ? "" : errorReason;
                    return existing;
                }
                if (existing.state == RetryState.SUCCEEDED
                        || (resetIfExhausted && existing.state == RetryState.EXHAUSTED)) {
                    existing.retryCount = 0;
                    existing.state = RetryState.PENDING;
                    existing.nextRetryAtEpochMs = nowMs + RETRY_INTERVAL_MS;
                    existing.lastError = errorReason == null ? "" : errorReason;
                }
                return existing;
            }
        });
        return entry.snapshot();
    }

    public void markSucceeded(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        RetryTaskEntry entry = retryLedger.get(idempotencyKey.trim());
        if (entry != null) {
            synchronized (entry) {
                entry.state = RetryState.SUCCEEDED;
                entry.lastError = "";
            }
        }
    }

    public boolean isExhausted(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return false;
        RetryTaskEntry entry = retryLedger.get(idempotencyKey.trim());
        if (entry == null) return false;
        synchronized (entry) {
            return entry.state == RetryState.EXHAUSTED;
        }
    }

    public int resetByPrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) return 0;
        String prefix = keyPrefix.trim();
        int cleared = 0;
        for (String key : new ArrayList<>(retryLedger.keySet())) {
            if (!key.startsWith(prefix)) continue;
            RetryTaskEntry entry = retryLedger.get(key);
            if (entry == null) continue;
            synchronized (entry) {
                if (entry.running) continue;
            }
            if (retryLedger.remove(key, entry)) {
                cleared++;
            }
        }
        return cleared;
    }

    public RetrySnapshot getRetrySnapshot(String idempotencyKey) {
        if (idempotencyKey == null) return null;
        RetryTaskEntry entry = retryLedger.get(idempotencyKey.trim());
        return entry == null ? null : entry.snapshot();
    }

    public Map<String, RetrySnapshot> allRetrySnapshots() {
        Map<String, RetrySnapshot> out = new java.util.LinkedHashMap<>();
        retryLedger.forEach((k, v) -> out.put(k, v.snapshot()));
        return out;
    }

    @Scheduled(fixedDelayString = "${xops.paid-retry-poll-ms:30000}")
    public void runDueRetries() {
        long nowMs = clock.millis();
        List<RetryTaskEntry> dueEntries = new ArrayList<>();
        for (RetryTaskEntry entry : retryLedger.values()) {
            synchronized (entry) {
                if (entry.state == RetryState.PENDING
                        && !entry.running
                        && entry.retryCount < MAX_IDEMPOTENT_RETRIES
                        && nowMs >= entry.nextRetryAtEpochMs) {
                    entry.running = true;
                    entry.reportedFailureDuringRun = false;
                    entry.retryCount++;
                    dueEntries.add(entry);
                }
            }
        }

        for (RetryTaskEntry entry : dueEntries) {
            try {
                runWithCaller(entry.username, entry.accountId, () -> {
                    if (isPaidCategory(entry.category) && !isPrivilegedUsername(entry.username)) {
                        throw new XDataProviderException(403, PAID_FORBIDDEN_MESSAGE);
                    }
                    entry.action.run();
                });
                boolean reportedFailure;
                synchronized (entry) {
                    entry.running = false;
                    reportedFailure = entry.reportedFailureDuringRun;
                    if (reportedFailure) {
                        if (entry.retryCount >= MAX_IDEMPOTENT_RETRIES) {
                            entry.state = RetryState.EXHAUSTED;
                            entry.nextRetryAtEpochMs = 0L;
                        } else {
                            entry.state = RetryState.PENDING;
                            entry.nextRetryAtEpochMs = clock.millis() + RETRY_INTERVAL_MS;
                        }
                    } else {
                        entry.state = RetryState.SUCCEEDED;
                        entry.lastError = "";
                    }
                }
                if (reportedFailure) {
                    log.warn("Idempotent retry degraded/fallback: key={} attempt={}/{} state={} reason={}",
                            entry.idempotencyKey, entry.retryCount, MAX_IDEMPOTENT_RETRIES, entry.state, entry.lastError);
                } else {
                    log.info("Idempotent paid retry succeeded: key={} attempt={}/{}",
                            entry.idempotencyKey, entry.retryCount, MAX_IDEMPOTENT_RETRIES);
                }
            } catch (Exception ex) {
                long finishedMs = clock.millis();
                String err = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                synchronized (entry) {
                    entry.running = false;
                    entry.lastError = err;
                    if (entry.retryCount >= MAX_IDEMPOTENT_RETRIES) {
                        entry.state = RetryState.EXHAUSTED;
                        entry.nextRetryAtEpochMs = 0L;
                    } else {
                        entry.state = RetryState.PENDING;
                        entry.nextRetryAtEpochMs = finishedMs + RETRY_INTERVAL_MS;
                    }
                }
                log.warn("Idempotent paid retry failed: key={} attempt={}/{} state={} reason={}",
                        entry.idempotencyKey, entry.retryCount, MAX_IDEMPOTENT_RETRIES, entry.state, err);
            }
        }
    }
}
