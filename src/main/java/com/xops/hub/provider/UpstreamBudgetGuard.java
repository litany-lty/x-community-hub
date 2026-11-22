package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reserves request counts and conservative USD estimates BEFORE sending upstream.
 * Reservations survive restart, are shared by processes using the same cache directory,
 * and are never refunded on ambiguous failures. This is not a provider billing receipt.
 */
@Component
public class UpstreamBudgetGuard {
    private static final Logger log = LoggerFactory.getLogger(UpstreamBudgetGuard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object[] FILE_LOCKS = new Object[64];
    private static final long MAX_LEDGER_BYTES = 8192;
    static {
        for (int i = 0; i < FILE_LOCKS.length; i++) FILE_LOCKS[i] = new Object();
    }

    private record Usage(long hour, long day, long month, long hourCalls, long dayCalls,
                         long hourMicros, long dayMicros, long monthMicros, long totalCalls) {
        Usage roll(Instant now, ZoneId zone) {
            LocalDate date = LocalDate.ofInstant(now, zone);
            long h = now.getEpochSecond() / 3600;
            long d = date.toEpochDay();
            long m = date.getYear() * 12L + date.getMonthValue();
            // Never reset budgets when the wall clock moves backwards.
            return new Usage(Math.max(hour, h), Math.max(day, d), Math.max(month, m),
                    h > hour ? 0 : hourCalls, d > day ? 0 : dayCalls,
                    h > hour ? 0 : hourMicros, d > day ? 0 : dayMicros,
                    m > month ? 0 : monthMicros, totalCalls);
        }
        Usage reserve(long micros) {
            return new Usage(hour, day, month, Math.addExact(hourCalls, 1), Math.addExact(dayCalls, 1),
                    Math.addExact(hourMicros, micros), Math.addExact(dayMicros, micros),
                    Math.addExact(monthMicros, micros), Math.addExact(totalCalls, 1));
        }
    }

    private final long hourlyBudget;
    private final long dailyBudget;
    private final long hourlyMicros;
    private final long dailyMicros;
    private final long monthlyMicros;
    private final long unpricedRequestMicros;
    private final ZoneId zoneId;
    private final Path ledger;
    private final Path lockFile;
    private final Clock clock;
    private final boolean enforceOnLocalMock;
    private final ConcurrentHashMap<String, AtomicLong> callsByCategory = new ConcurrentHashMap<>();
    private final AtomicLong localMockCalls = new AtomicLong();
    private final AtomicLong rejectedCalls = new AtomicLong();
    private final AtomicLong lastWarnMinute = new AtomicLong(Long.MIN_VALUE);
    private volatile Usage current;
    private volatile boolean ledgerHealthy = true;
    private volatile boolean ledgerLoaded;

    @Autowired
    public UpstreamBudgetGuard(XOpsProperties properties) {
        this(properties, false);
    }

    public UpstreamBudgetGuard(XOpsProperties properties, boolean enforceOnLocalMock) {
        this(properties, enforceOnLocalMock, Clock.systemUTC());
    }

    UpstreamBudgetGuard(XOpsProperties properties, boolean enforceOnLocalMock, Clock clock) {
        if (properties == null) throw new IllegalArgumentException("Budget properties are required");
        if (properties.getHourlyUpstreamApiBudget() < 0 || properties.getDailyUpstreamApiBudget() < 0) {
            throw new IllegalArgumentException("Upstream request budgets must be non-negative; zero blocks calls");
        }
        this.hourlyBudget = properties.getHourlyUpstreamApiBudget();
        this.dailyBudget = properties.getDailyUpstreamApiBudget();
        this.hourlyMicros = usdMicros(properties.getHourlyUpstreamUsdBudget());
        this.dailyMicros = usdMicros(properties.getDailyUpstreamUsdBudget());
        this.monthlyMicros = usdMicros(properties.getMonthlyUpstreamUsdBudget());
        this.unpricedRequestMicros = usdMicros(properties.getUnpricedUpstreamRequestReserveUsd());
        if (unpricedRequestMicros == 0) throw new IllegalArgumentException("Unpriced request reserve must be positive");
        this.zoneId = ZoneId.of(properties.getTimezone());
        this.clock = clock;
        this.enforceOnLocalMock = enforceOnLocalMock;
        String directory = properties.getDiskCacheDir();
        // DEMO/local mocks may explicitly disable snapshot storage. Paid requests still fail closed.
        this.ledger = directory == null || directory.isBlank() ? null
                : Path.of(directory).toAbsolutePath().normalize().resolve("upstream-budget.json");
        this.lockFile = ledger == null ? null : ledger.resolveSibling("upstream-budget.lock");
        this.current = empty(clock.instant());
    }

    static long usdMicros(String value) {
        try {
            long micros = new BigDecimal(value).movePointRight(6).longValueExact();
            if (micros < 0) throw new ArithmeticException("negative");
            return micros;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("USD budgets require non-negative amounts with at most six decimals", ex);
        }
    }

    public void acquirePermit(String rawUrl, String category) {
        final URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (RuntimeException ex) {
            throw new XDataProviderException(503, "外部调用地址无效，未发起付费请求", ex);
        }
        acquirePermit(uri, category);
    }

    public void acquirePermit(URI uri, String category) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || (!"https".equalsIgnoreCase(uri.getScheme())
                    && !("http".equalsIgnoreCase(uri.getScheme()) && isLocalLoopbackUri(uri)))) {
            throw new XDataProviderException(503, "外部调用地址未通过校验，未发起付费请求");
        }
        if (!knownCategory(category)) {
            throw new XDataProviderException(503, "外部调用未登记预算类别，未发起付费请求");
        }
        if (!enforceOnLocalMock && isLocalLoopbackUri(uri)) {
            localMockCalls.incrementAndGet();
            return;
        }
        if (ledger == null) {
            ledgerHealthy = false;
            ledgerLoaded = true;
            rejectedCalls.incrementAndGet();
            throw new XDataProviderException(503, "未配置持久化预算目录，已停止外部调用以保护费用");
        }
        long cost = estimateMicros(uri, category, unpricedRequestMicros);
        synchronized (FILE_LOCKS[Math.floorMod(ledger.hashCode(), FILE_LOCKS.length)]) {
            try {
                Files.createDirectories(ledger.getParent());
                if (Files.isSymbolicLink(lockFile) || Files.isSymbolicLink(ledger)) {
                    throw new IOException("Budget ledger must be a regular file");
                }
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = channel.lock()) {
                    Usage usage = readUsage(channel.size() > 0).roll(clock.instant(), zoneId);
                    current = usage;
                    ledgerLoaded = true;
                    ledgerHealthy = true;
                    if (usage.hourCalls >= hourlyBudget || usage.dayCalls >= dailyBudget
                            || cost > hourlyMicros - usage.hourMicros
                            || cost > dailyMicros - usage.dayMicros
                            || cost > monthlyMicros - usage.monthMicros) {
                        rejectedCalls.incrementAndGet();
                        warn("Upstream budget reached; external request was not sent");
                        throw new XDataProviderException(429, "外部调用预算已达保护上限，已停止本次同步；仍可查看已入库报表");
                    }
                    Usage reserved = usage.reserve(cost);
                    persist(reserved);
                    if (channel.size() == 0) {
                        channel.write(ByteBuffer.wrap(new byte[]{1}), 0);
                        channel.force(true);
                    }
                    current = reserved;
                    ledgerHealthy = true;
                }
            } catch (IOException | ArithmeticException ex) {
                ledgerHealthy = false;
                ledgerLoaded = true;
                rejectedCalls.incrementAndGet();
                warn("Upstream budget ledger unavailable; external requests blocked");
                throw new XDataProviderException(503, "预算记录暂不可用，已停止外部调用以保护费用", ex);
            }
        }
        callsByCategory.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
    }

    private Usage empty(Instant now) {
        LocalDate date = LocalDate.ofInstant(now, zoneId);
        return new Usage(now.getEpochSecond() / 3600, date.toEpochDay(),
                date.getYear() * 12L + date.getMonthValue(), 0, 0, 0, 0, 0, 0);
    }

    private Usage readUsage(boolean initialized) throws IOException {
        if (!Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) {
            if (initialized) throw new IOException("Initialized budget ledger is missing");
            return empty(clock.instant());
        }
        if (!Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)
                || Files.size(ledger) == 0 || Files.size(ledger) > MAX_LEDGER_BYTES) {
            throw new IOException("Invalid budget ledger");
        }
        JsonNode root = MAPPER.readTree(Files.readAllBytes(ledger));
        if (root == null || root.path("version").asInt(-1) != 1
                || !zoneId.getId().equals(root.path("timezone").asText())) {
            throw new IOException("Budget ledger version/timezone mismatch");
        }
        return new Usage(number(root, "hour"), number(root, "day"), number(root, "month"),
                number(root, "hourCalls"), number(root, "dayCalls"), number(root, "hourMicros"),
                number(root, "dayMicros"), number(root, "monthMicros"), number(root, "totalCalls"));
    }

    private static long number(JsonNode root, String name) throws IOException {
        JsonNode node = root.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new IOException("Invalid budget ledger field: " + name);
        }
        return node.longValue();
    }

    private void persist(Usage usage) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("timezone", zoneId.getId());
        data.put("hour", usage.hour);
        data.put("day", usage.day);
        data.put("month", usage.month);
        data.put("hourCalls", usage.hourCalls);
        data.put("dayCalls", usage.dayCalls);
        data.put("hourMicros", usage.hourMicros);
        data.put("dayMicros", usage.dayMicros);
        data.put("monthMicros", usage.monthMicros);
        data.put("totalCalls", usage.totalCalls);
        Path temporary = Files.createTempFile(ledger.getParent(), ".budget-", ".tmp");
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(data);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // Unsupported atomic replacement blocks spending rather than falling back to truncation.
            Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel parent = FileChannel.open(ledger.getParent(), StandardOpenOption.READ)) {
                parent.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean knownCategory(String category) {
        return category != null && switch (category) {
            case "twitterapi_io_read", "twitterapi_io_relation", "x_official_read",
                    "x_tweet_create", "x_media_upload", "x_oauth_token", "x_oauth_user_me" -> true;
            default -> false;
        };
    }

    /**
     * Conservative reservations based on the public rate cards checked 2026-11-15.
     * Discounts/deduplication are not assumed. OAuth/media prices not published in those
     * cards use an explicit policy reserve, not a claimed price.
     */
    static long estimateMicros(URI uri, String category, long unpricedReserve) {
        String path = uri.getPath();
        if ("x_tweet_create".equals(category)) return 200_000; // URL-priced post; includes lower-cost posts.
        if ("x_oauth_user_me".equals(category)) return 10_000;
        if ("x_oauth_token".equals(category) || "x_media_upload".equals(category)) return unpricedReserve;
        if ("twitterapi_io_relation".equals(category)) return 1_000;
        Map<String, String> query = query(uri);
        if ("twitterapi_io_read".equals(category)) {
            // TwitterAPI.io Get Article: 100 credits per article, 100,000 credits per USD.
            if (path.endsWith("/twitter/article")) return 1_000;
            if (path.endsWith("/twitter/user/info")) return 180;
            if (path.endsWith("/twitter/user/batch_info_by_ids")) {
                return Math.max(150, countIds(query.get("userIds"), 100) * 180L);
            }
            if (path.endsWith("/twitter/tweets")) {
                return Math.max(150, countIds(query.get("tweet_ids"), 100) * 150L);
            }
            if (path.endsWith("/twitter/user/followers") || path.endsWith("/twitter/user/followings")) {
                return 200 * 30L; // 200 users at the highest per-user rate.
            }
            if (path.endsWith("/twitter/user/followers_ids")) return 5000 * 20L;
            if (path.endsWith("/twitter/user/last_tweets") || path.endsWith("/twitter/user/mentions")
                    || path.endsWith("/twitter/tweet/advanced_search")) {
                return 100 * 150L; // Reserve up to 100 results; normal pages are smaller.
            }
        }
        if ("x_official_read".equals(category)) {
            if (path.contains("/users/by/username/") || path.endsWith("/users/me")) return 10_000;
            int page = boundedInt(query.get("max_results"), 100, 1000);
            if (path.endsWith("/followers") || path.endsWith("/following")) return page * 10_000L;
            if (path.endsWith("/tweets") || path.endsWith("/mentions")) {
                // Include referenced posts and expanded user resources, with no dedup discount.
                return page * (5_000L * 4 + 10_000L * 5);
            }
        }
        throw new XDataProviderException(503, "外部接口尚未登记费用预留规则，已停止调用");
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) return values;
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static int countIds(String value, int max) {
        if (value == null || value.isBlank()) throw new XDataProviderException(503, "外部批量请求缺少ID");
        int count = value.split(",", -1).length;
        if (count > max) throw new XDataProviderException(503, "外部批量请求超出预算估算范围");
        return count;
    }

    private static int boundedInt(String value, int fallback, int max) {
        try {
            int count = value == null ? fallback : Integer.parseInt(value);
            if (count < 1 || count > max) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException ex) {
            throw new XDataProviderException(503, "外部请求分页超出预算估算范围");
        }
    }

    static boolean isLocalLoopbackUri(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        return switch (uri.getHost().toLowerCase(java.util.Locale.ROOT)) {
            case "localhost", "127.0.0.1", "::1", "[::1]" -> true;
            default -> false;
        };
    }

    private void warn(String message) {
        long minute = clock.instant().getEpochSecond() / 60;
        long previous = lastWarnMinute.get();
        if (minute != previous && lastWarnMinute.compareAndSet(previous, minute)) log.warn(message);
    }

    public Map<String, Object> snapshotMetrics() {
        Usage usage = current.roll(clock.instant(), zoneId);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("hourlyBudget", hourlyBudget);
        metrics.put("hourlyUsed", ledgerLoaded ? Long.valueOf(usage.hourCalls) : null);
        metrics.put("dailyBudget", dailyBudget);
        metrics.put("dailyUsed", ledgerLoaded ? Long.valueOf(usage.dayCalls) : null);
        metrics.put("hourlyUsdBudget", BigDecimal.valueOf(hourlyMicros, 6));
        metrics.put("dailyUsdBudget", BigDecimal.valueOf(dailyMicros, 6));
        metrics.put("monthlyUsdBudget", BigDecimal.valueOf(monthlyMicros, 6));
        metrics.put("hourlyUsdReserved", ledgerLoaded ? BigDecimal.valueOf(usage.hourMicros, 6) : null);
        metrics.put("dailyUsdReserved", ledgerLoaded ? BigDecimal.valueOf(usage.dayMicros, 6) : null);
        metrics.put("monthlyUsdReserved", ledgerLoaded ? BigDecimal.valueOf(usage.monthMicros, 6) : null);
        metrics.put("totalBillableCalls", ledgerLoaded ? Long.valueOf(usage.totalCalls) : null);
        metrics.put("budgetRejectedCalls", rejectedCalls.get());
        metrics.put("localMockCalls", localMockCalls.get());
        metrics.put("ledgerHealthy", ledgerLoaded ? Boolean.valueOf(ledgerHealthy) : null);
        metrics.put("ledgerState", !ledgerLoaded ? "not_loaded" : ledgerHealthy ? "ready" : "unavailable");
        metrics.put("usageScope", "last ledger read by this process");
        metrics.put("accounting", "conservative reservations, not provider invoice");
        Map<String, Long> categories = new LinkedHashMap<>();
        callsByCategory.forEach((key, value) -> categories.put(key, value.get()));
        metrics.put("callsByCategory", categories);
        return metrics;
    }
}
