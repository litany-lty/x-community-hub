package com.xops.hub.config;

import com.xops.hub.provider.XDataProviderException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第一道边界防线：在会话恢复、Multipart 解析与业务线程池之前，低开销直接拒绝：
 * 1. 非法 HTTP 方法、超长 URI/Query、目录穿越/JNDI/脚本注入探测、常见漏洞扫描器路径；
 * 2. 非上传接口的超大请求体与非法 Multipart 载荷（防止打满磁盘或内存）；
 * 3. 单 IP 恶意扫描封禁盒、OAuth 授权握手防刷与极端单 IP 洪泛攻击拦截。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaliciousRequestShieldFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MaliciousRequestShieldFilter.class);

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD", "POST", "DELETE", "OPTIONS");
    private static final Set<String> MULTIPART_UPLOAD_PATHS = Set.of(
            "/api/scheduled-tweets",
            "/api/scheduled-tweets/batch"
    );
    private static final long MAX_NON_UPLOAD_BODY_BYTES = 256L * 1024L;
    private static final long MAX_JSON_SCHEDULE_BODY_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_UPLOAD_BODY_BYTES = 26L * 1024L * 1024L;
    private static final int MAX_URI_LENGTH = 512;
    private static final int MAX_QUERY_LENGTH = 2048;
    private static final int MAX_IP_MAP_SIZE = 4096;
    private static final int MALICE_STRIKE_BAN_THRESHOLD = 8;
    private static final long MALICE_WINDOW_SECONDS = 300L;
    private static final long MALICE_BAN_SECONDS = 300L;

    private record MinuteCounter(long minuteEpoch, int count) {
    }

    private record MaliceRecord(long windowStartSec, int strikes, long bannedUntilSec) {
    }

    private long lastMalicePruneMinute = -1L;
    private final XOpsProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, MinuteCounter> ipApiCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MinuteCounter> ipAuthCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MaliceRecord> ipMaliceBox = new ConcurrentHashMap<>();

    @Autowired
    public MaliciousRequestShieldFilter(XOpsProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MaliciousRequestShieldFilter(XOpsProperties properties, Clock clock) {
        this.properties = properties != null ? properties : new XOpsProperties();
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        appendBaselineSecurityHeaders(response);

        String method = request.getMethod();
        if (method == null || !ALLOWED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setHeader("Allow", "GET, HEAD, POST, DELETE, OPTIONS");
            return;
        }

        String clientIp = RequestSecuritySupport.extractClientIp(request);
        long nowSec = Instant.now(clock).getEpochSecond();

        if (isIpTemporarilyBanned(clientIp, nowSec)) {
            rejectJsonOrEmpty(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "检测到异常恶意探测，当前请求已被安全防线拦截");
            return;
        }

        String rawUri = request.getRequestURI();
        String path = RequestSecuritySupport.requestPath(request);
        String query = request.getQueryString();
        if (rawUri == null || rawUri.isEmpty() || rawUri.length() > MAX_URI_LENGTH
                || (query != null && query.length() > MAX_QUERY_LENGTH)) {
            recordMaliceStrike(clientIp, nowSec, 2);
            rejectJsonOrEmpty(request, response, HttpServletResponse.SC_REQUEST_URI_TOO_LONG, "URI_TOO_LONG", "请求地址长度超出安全限制");
            return;
        }

        if (containsPathTraversal(rawUri) || containsExploitPayload(rawUri) || containsExploitPayload(query)) {
            recordMaliceStrike(clientIp, nowSec, 3);
            log.debug("Blocked exploit payload from {} on {}", clientIp, rawUri);
            rejectJsonOrEmpty(request, response, HttpServletResponse.SC_BAD_REQUEST, "MALICIOUS_REQUEST", "检测到非法请求特征，已拒绝访问");
            return;
        }

        if (isScannerProbePath(rawUri)) {
            recordMaliceStrike(clientIp, nowSec, 2);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            return;
        }

        if ("POST".equalsIgnoreCase(method)
                && (path == null || (!path.startsWith("/api/") && !path.startsWith("/__capacity_fixture/")))) {
            recordMaliceStrike(clientIp, nowSec, 1);
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setHeader("Allow", "GET, HEAD, OPTIONS");
            response.setContentLength(0);
            return;
        }

        // 在进入 Multipart 解析器前拦截异常体积载荷，防止磁盘与内存被恶意请求打爆
        long contentLength = request.getContentLengthLong();
        String contentType = request.getContentType();
        boolean isMultipart = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
        boolean isUploadEndpoint = "POST".equalsIgnoreCase(method) && MULTIPART_UPLOAD_PATHS.contains(path);
        if (isMultipart && !isUploadEndpoint) {
            recordMaliceStrike(clientIp, nowSec, 2);
            rejectJsonOrEmpty(request, response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "当前接口不接受 Multipart 上传");
            return;
        }
        long maxAllowedBytes = isUploadEndpoint
                ? (isMultipart ? MAX_UPLOAD_BODY_BYTES : MAX_JSON_SCHEDULE_BODY_BYTES)
                : MAX_NON_UPLOAD_BODY_BYTES;
        if (contentLength > maxAllowedBytes) {
            rejectJsonOrEmpty(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE", "请求体超出安全大小上限");
            return;
        }

        if (path.startsWith("/api/")) {
            long minuteEpoch = nowSec / 60L;
            if (isOAuthHandshakePath(path)) {
                int authLimit = Math.max(5, properties.getIpAuthLimitPerMinute());
                if (incrementMinuteCounter(ipAuthCounters, clientIp, minuteEpoch) > authLimit) {
                    response.setHeader("Retry-After", "15");
                    rejectJsonOrEmpty(request, response, 429, "AUTH_RATE_LIMITED", "登录授权请求过于频繁，请稍候再试");
                    return;
                }
            }
            int floodLimit = Math.max(60, properties.getIpApiFloodLimitPerMinute());
            if (incrementMinuteCounter(ipApiCounters, clientIp, minuteEpoch) > floodLimit) {
                recordMaliceStrike(clientIp, nowSec, 1);
                response.setHeader("Retry-After", "5");
                rejectJsonOrEmpty(request, response, 429, "FLOOD_REJECTED", "检测到异常高频请求，已触发防洪保护");
                return;
            }
        }

        // 同源校验必须先于 Multipart 解析、会话恢复及付费读取，OAuth 回跳不受影响。
        // Spring 的 GET 映射同样处理 HEAD，因此两者都要保护收费入口。
        boolean paidRead = ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                && ("/api/analyze".equals(path) || "/api/analyze/bundle".equals(path));
        if (path.startsWith("/api/") && (paidRead || "POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
            try {
                RequestSecuritySupport.verifySameOriginForMutation(request);
            } catch (XDataProviderException ex) {
                rejectJsonOrEmpty(request, response, 403, "FORBIDDEN", ex.getMessage());
                return;
            }
        }

        if (isUploadEndpoint && isMultipart) {
            // Multipart 总大小另由容器的 25MB 解析上限约束，包括 chunked 上传。
            filterChain.doFilter(request, response);
        } else {
            LimitedBodyRequest limited = new LimitedBodyRequest(request, maxAllowedBytes);
            try {
                filterChain.doFilter(limited, response);
            } catch (IOException | ServletException ex) {
                if (!limited.exceeded || response.isCommitted()) throw ex;
            } finally {
                if (limited.exceeded && !response.isCommitted()) {
                    response.resetBuffer();
                    rejectJsonOrEmpty(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                            "PAYLOAD_TOO_LARGE", "请求体超出安全大小上限");
                }
            }
        }
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream input;
        private boolean exceeded;
        private long consumed;

        private LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (input == null) {
                ServletInputStream source = super.getInputStream();
                input = new ServletInputStream() {
                    @Override public int read() throws IOException {
                        int value = source.read();
                        if (value >= 0) account(1);
                        return value;
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int count = source.read(bytes, offset, (int) Math.min(length, Math.max(1L, limit - consumed + 1L)));
                        if (count > 0) account(count);
                        return count;
                    }
                    @Override public boolean isFinished() { return source.isFinished(); }
                    @Override public boolean isReady() { return source.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                    @Override public void close() throws IOException { source.close(); }
                    private void account(int count) throws IOException {
                        consumed += count;
                        if (consumed > limit) {
                            exceeded = true;
                            throw new IOException("Request body exceeds configured limit");
                        }
                    }
                };
            }
            return input;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding)));
        }
    }

    private boolean isIpTemporarilyBanned(String ip, long nowSec) {
        MaliceRecord rec = ipMaliceBox.get(ip);
        return rec != null && rec.bannedUntilSec() > nowSec;
    }

    private void recordMaliceStrike(String ip, long nowSec, int delta) {
        synchronized (ipMaliceBox) {
            if (!ipMaliceBox.containsKey(ip) && ipMaliceBox.size() >= MAX_IP_MAP_SIZE) {
                if (nowSec / 60L != lastMalicePruneMinute) {
                    lastMalicePruneMinute = nowSec / 60L;
                    ipMaliceBox.entrySet().removeIf(e -> e.getValue().bannedUntilSec() <= nowSec
                            && nowSec - e.getValue().windowStartSec() > MALICE_WINDOW_SECONDS);
                }
                // 当前请求已经被拒绝；不为新攻击身份无限分配记录，也不清空已有封禁。
                if (ipMaliceBox.size() >= MAX_IP_MAP_SIZE) return;
            }
            ipMaliceBox.compute(ip, (k, prev) -> {
                if (prev == null || (prev.bannedUntilSec() <= nowSec && nowSec - prev.windowStartSec() > MALICE_WINDOW_SECONDS)) {
                    int strikes = Math.min(MALICE_STRIKE_BAN_THRESHOLD, Math.max(1, delta));
                    return new MaliceRecord(nowSec, strikes,
                            strikes >= MALICE_STRIKE_BAN_THRESHOLD ? nowSec + MALICE_BAN_SECONDS : 0L);
                }
                int nextStrikes = Math.min(MALICE_STRIKE_BAN_THRESHOLD, prev.strikes() + Math.max(1, delta));
                long banUntil = nextStrikes >= MALICE_STRIKE_BAN_THRESHOLD
                        ? Math.max(prev.bannedUntilSec(), nowSec + MALICE_BAN_SECONDS)
                        : prev.bannedUntilSec();
                if (banUntil > nowSec && prev.bannedUntilSec() <= nowSec) {
                    log.debug("Temporarily blocked malicious IP {} for {}s", ip, MALICE_BAN_SECONDS);
                }
                return new MaliceRecord(prev.windowStartSec(), nextStrikes, banUntil);
            });
        }
    }

    private static int incrementMinuteCounter(ConcurrentHashMap<String, MinuteCounter> map, String key, long minuteEpoch) {
        synchronized (map) {
            if (!map.containsKey(key) && map.size() >= MAX_IP_MAP_SIZE - 1) {
                MinuteCounter overflow = map.get("overflow");
                if (overflow == null || overflow.minuteEpoch() != minuteEpoch) {
                    map.entrySet().removeIf(e -> e.getValue().minuteEpoch() < minuteEpoch);
                }
                if (map.size() >= MAX_IP_MAP_SIZE - 1) {
                    key = "overflow";
                }
            }
            MinuteCounter previous = map.get(key);
            int next = previous == null || previous.minuteEpoch() != minuteEpoch
                    ? 1 : (int) Math.min(Integer.MAX_VALUE, (long) previous.count() + 1L);
            map.put(key, new MinuteCounter(minuteEpoch, next));
            return next;
        }
    }

    private static boolean isOAuthHandshakePath(String uri) {
        return "/api/auth/x/login".equals(uri)
                || "/api/auth/x/callback".equals(uri)
                || "/api/auth/x/authorize-publish".equals(uri);
    }

    static boolean containsExploitPayload(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.indexOf('\0') >= 0
                || lower.contains("%00")
                || lower.indexOf('\r') >= 0
                || lower.indexOf('\n') >= 0
                || lower.contains("${jndi:")
                || lower.contains("%24%7bjndi")
                || lower.contains("<script")
                || lower.contains("%3cscript")
                || lower.contains("javascript:")
                || lower.contains("/etc/passwd");
    }

    static boolean containsPathTraversal(String uri) {
        String decoded = decodePathForInspection(uri);
        for (String segment : decoded.replace('\\', '/').split("/", -1)) {
            if ("..".equals(segment) || segment.startsWith("..;")) return true;
        }
        return false;
    }

    private static String decodePathForInspection(String input) {
        String decoded = input.toLowerCase(Locale.ROOT);
        for (int round = 0; round < 3 && decoded.indexOf('%') >= 0; round++) {
            StringBuilder next = new StringBuilder(decoded.length());
            for (int i = 0; i < decoded.length(); i++) {
                if (decoded.charAt(i) == '%' && i + 2 < decoded.length()) {
                    int hi = Character.digit(decoded.charAt(i + 1), 16);
                    int lo = Character.digit(decoded.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        next.append((char) (hi * 16 + lo));
                        i += 2;
                        continue;
                    }
                }
                next.append(decoded.charAt(i));
            }
            decoded = next.toString().toLowerCase(Locale.ROOT);
        }
        return decoded;
    }

    static boolean isScannerProbePath(String uri) {
        String lower = decodePathForInspection(uri).replace('\\', '/');
        if (lower.contains("/web-inf") || lower.contains("/meta-inf")) {
            return true;
        }
        if (lower.startsWith("/.env")
                || lower.startsWith("/.git")
                || lower.startsWith("/.svn")
                || lower.startsWith("/.aws")
                || lower.startsWith("/.ssh")
                || lower.startsWith("/wp-admin")
                || lower.startsWith("/wp-login")
                || lower.startsWith("/wp-includes")
                || lower.startsWith("/wp-content")
                || lower.startsWith("/phpmyadmin")
                || lower.startsWith("/pma")
                || lower.startsWith("/actuator")
                || lower.startsWith("/cgi-bin")
                || lower.startsWith("/vendor/phpunit")
                || lower.startsWith("/boaform")
                || lower.startsWith("/hnap1")
                || lower.equals("/xmlrpc.php")
                || lower.equals("/server-status")) {
            return true;
        }
        return lower.endsWith(".php")
                || lower.endsWith(".asp")
                || lower.endsWith(".aspx")
                || lower.endsWith(".jsp")
                || lower.endsWith(".env")
                || lower.endsWith(".sql")
                || lower.endsWith(".bak");
    }

    private static void appendBaselineSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    }

    static void rejectJsonOrEmpty(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        String uri = RequestSecuritySupport.requestPath(request);
        if (uri != null && uri.startsWith("/api/") && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            response.setContentType("application/json;charset=UTF-8");
            String body = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } else {
            response.setContentLength(0);
        }
    }
}
