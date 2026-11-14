package com.xops.hub.controller;

import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.AnalyticsReportModels.ServerCapabilities;
import com.xops.hub.model.AnalyticsReportModels.UsageCheckinResponse;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityAnalyticsEngine;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.util.HandleNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CommunityApiController {

    private final XOpsProperties properties;
    private final CommunityHubService hubService;
    private final SharedApiExecutor sharedApiExecutor;

    public CommunityApiController(XOpsProperties properties, CommunityHubService hubService) {
        this(properties, hubService, new SharedApiExecutor(properties));
    }

    @Autowired
    public CommunityApiController(XOpsProperties properties, CommunityHubService hubService, SharedApiExecutor sharedApiExecutor) {
        this.properties = properties;
        this.hubService = hubService;
        this.sharedApiExecutor = sharedApiExecutor;
    }

    @GetMapping("/meta")
    public ServerCapabilities getMeta() {
        return hubService.getCapabilities();
    }

    @GetMapping("/health")
    public java.util.Map<String, Object> getHealth() {
        java.util.Map<String, Object> health = new java.util.LinkedHashMap<>();
        health.put("status", "UP");
        health.put("provider", hubService.getActiveProviderName());
        health.put("oauthConfigured", properties.hasOAuthClient());
        health.put("requireLoginSelfOnly", properties.isRequireLoginSelfOnly());
        health.put("cacheTtlSeconds", Math.max(0L, properties.getCacheTtlSeconds()));
        health.put("activeCachedAccounts", hubService.getActiveCacheSize());
        health.put("apiPool", sharedApiExecutor.snapshotMetrics());
        health.put("timestamp", java.time.Instant.now().toString());
        return health;
    }

    @GetMapping("/analyze")
    public CommunityDashboardReport analyze(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "days", defaultValue = "2") int days,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request);
        int resolvedDays = parsePeriodOrDays(period, days);
        String clientIp = extractClientIp(request);
        CommunityDashboardReport report = hubService.analyzeAccount(authorizedUsername, resolvedDays, provider, refresh, clientIp, resolveAuthorizedAccountId(authorizedUsername, request));
        verifyReportAccountIsolation(authorizedUsername, report);
        return report;
    }

    @GetMapping("/analyze/bundle")
    public MultiPeriodDashboardBundle analyzeBundle(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "targetDays", defaultValue = "2") int targetDays,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
            @RequestParam(value = "continueRelations", defaultValue = "false") boolean continueRelations,
            @RequestParam(value = "stored", defaultValue = "false") boolean stored,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request, stored);
        int resolvedTargetDays = parsePeriodOrDays(period, targetDays);
        String clientIp = extractClientIp(request);
        String accountId = resolveAuthorizedAccountId(authorizedUsername, request);
        MultiPeriodDashboardBundle bundle = stored
                ? hubService.analyzeStoredAccountBundle(authorizedUsername, accountId)
                : hubService.analyzeAccountBundle(authorizedUsername, resolvedTargetDays, provider, refresh,
                        clientIp, accountId, continueRelations);
        verifyReportAccountIsolation(authorizedUsername, bundle.day());
        verifyReportAccountIsolation(authorizedUsername, bundle.week());
        verifyReportAccountIsolation(authorizedUsername, bundle.month());
        return bundle;
    }

    @PostMapping("/usage/checkin")
    public UsageCheckinResponse checkinUsage(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request);
        return hubService.recordUsageCheckin(authorizedUsername, resolveAuthorizedAccountId(authorizedUsername, request));
    }

    @GetMapping("/usage/author-card")
    public UsageCheckinResponse getAuthorCard(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request);
        return hubService.getAuthorCardPreview(authorizedUsername, resolveAuthorizedAccountId(authorizedUsername, request));
    }

    @PostMapping("/usage/follow-author")
    public java.util.Map<String, Object> markFollowedAuthor(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request);
        return hubService.markAuthorFollowedOptimistically(authorizedUsername, resolveAuthorizedAccountId(authorizedUsername, request));
    }

    static void applyPrivateNoStoreHeaders(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Vary", "Cookie");
    }

    private static void verifyReportAccountIsolation(String expectedUsername, CommunityDashboardReport report) {
        if (report == null || report.profile() == null || report.profile().username() == null
                || !report.profile().username().equalsIgnoreCase(expectedUsername)) {
            throw new XDataProviderException(500, "账号数据隔离校验未通过，已阻断响应");
        }
    }

    private String resolveAuthorizedAccountId(String username, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        AuthenticatedXUser auth = session != null
                ? (AuthenticatedXUser) session.getAttribute(XOAuthController.SESSION_AUTH_USER) : null;
        if (auth != null && username.equalsIgnoreCase(auth.username())) {
            if (auth.id() == null || auth.id().isBlank()) {
                throw new XDataProviderException(401, "登录身份缺少账号 ID，请重新使用 X 授权登录");
            }
            return auth.id();
        }
        return null;
    }

    private String resolveAuthorizedTargetUsername(String rawUsername, HttpServletRequest request) {
        return resolveAuthorizedTargetUsername(rawUsername, request, false);
    }

    private String resolveAuthorizedTargetUsername(String rawUsername, HttpServletRequest request, boolean requireLoggedInSelf) {
        HttpSession session = request.getSession(false);
        AuthenticatedXUser authUser = session != null
                ? (AuthenticatedXUser) session.getAttribute(XOAuthController.SESSION_AUTH_USER)
                : null;

        if (requireLoggedInSelf || properties.isRequireLoginSelfOnly()) {
            if (authUser == null || authUser.username() == null || authUser.username().isBlank()) {
                throw new XDataProviderException(401, "请先使用 X 账号授权登录，登录后仅可查询您本人的账号数据");
            }
            String selfHandle = HandleNormalizer.normalize(authUser.username());
            if (rawUsername != null && !rawUsername.isBlank()) {
                String requestedHandle = HandleNormalizer.normalize(rawUsername);
                if (!requestedHandle.equalsIgnoreCase(selfHandle)) {
                    throw new XDataProviderException(
                            403,
                            "权限受限：每位用户仅可查看当前登录授权的本人账号（@" + selfHandle + "）"
                    );
                }
            }
            return selfHandle;
        }

        if (rawUsername != null && !rawUsername.isBlank()) {
            return HandleNormalizer.normalize(rawUsername);
        }
        if (authUser != null && authUser.username() != null && !authUser.username().isBlank()) {
            return HandleNormalizer.normalize(authUser.username());
        }
        throw new IllegalArgumentException("请输入要查询的 X 账号");
    }

    private int parsePeriodOrDays(String period, int fallbackDays) {
        if (period != null && !period.isBlank()) {
            return switch (period.trim().toUpperCase()) {
                case "HOURS_48", "48H", "DAY", "D", "2", "1" -> 2;
                case "WEEK", "W", "7D", "7" -> 7;
                case "MONTH", "M", "30D", "30" -> 30;
                default -> CommunityAnalyticsEngine.resolvePeriodDays(fallbackDays);
            };
        }
        return CommunityAnalyticsEngine.resolvePeriodDays(fallbackDays);
    }

    private String extractClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        // 仅当直连来源为本机或内网反向代理时才信任 X-Forwarded-For，防止公网伪造请求头绕过限流与撑爆内存
        if (isTrustedProxyAddress(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int commaIdx = xff.indexOf(',');
                String candidate = (commaIdx > 0 ? xff.substring(0, commaIdx) : xff).trim();
                if (!candidate.isEmpty() && candidate.length() <= 64) {
                    return candidate;
                }
            }
        }
        return remote;
    }

    public static boolean isTrustedProxyAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        return "127.0.0.1".equals(ip)
                || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    }
}
