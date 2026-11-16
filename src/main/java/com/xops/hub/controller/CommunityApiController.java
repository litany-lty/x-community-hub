package com.xops.hub.controller;

import com.xops.hub.config.RequestSecuritySupport;
import com.xops.hub.config.UserConcurrencyThrottleFilter;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.AnalyticsReportModels.CommunityDashboardReport;
import com.xops.hub.model.AnalyticsReportModels.MultiPeriodDashboardBundle;
import com.xops.hub.model.AnalyticsReportModels.ServerCapabilities;
import com.xops.hub.model.AnalyticsReportModels.UsageCheckinResponse;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.UpstreamBudgetGuard;
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

    public CommunityApiController(XOpsProperties properties, CommunityHubService hubService) {
        this(properties, hubService, null, null, null);
    }

    public CommunityApiController(XOpsProperties properties, CommunityHubService hubService, SharedApiExecutor sharedApiExecutor) {
        this(properties, hubService, sharedApiExecutor, null, null);
    }

    @Autowired
    public CommunityApiController(
            XOpsProperties properties,
            CommunityHubService hubService,
            SharedApiExecutor sharedApiExecutor,
            @Autowired(required = false) UserConcurrencyThrottleFilter concurrencyThrottleFilter,
            @Autowired(required = false) UpstreamBudgetGuard budgetGuard
    ) {
        this.properties = properties;
        this.hubService = hubService;
    }

    @GetMapping("/meta")
    public ServerCapabilities getMeta() {
        return hubService.getCapabilities();
    }

    @GetMapping("/health")
    public java.util.Map<String, Object> getHealth() {
        java.util.Map<String, Object> health = new java.util.LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", java.time.Instant.now().toString());
        return health;
    }

    @GetMapping("/analyze")
    public CommunityDashboardReport analyze(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request);
        CommunityAnalyticsEngine.ReportPeriod resolvedPeriod = parseReportPeriod(
                period, days, CommunityAnalyticsEngine.ReportPeriod.HOURS_24);
        String clientIp = extractClientIp(request);
        CommunityDashboardReport report = hubService.analyzeWorkspaceReport(
                authorizedUsername, resolvedPeriod, provider, refresh, clientIp,
                resolveAuthorizedAccountId(authorizedUsername, request));
        verifyReportAccountIsolation(authorizedUsername, report);
        return report;
    }

    @GetMapping("/analyze/bundle")
    public MultiPeriodDashboardBundle analyzeBundle(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "targetDays", required = false) Integer targetDays,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
            @RequestParam(value = "continueRelations", defaultValue = "false") boolean continueRelations,
            @RequestParam(value = "stored", defaultValue = "false") boolean stored,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        String authorizedUsername = resolveAuthorizedTargetUsername(username, request, stored);
        CommunityAnalyticsEngine.ReportPeriod resolvedPeriod = parseReportPeriod(
                period, targetDays, CommunityAnalyticsEngine.ReportPeriod.HOURS_24);
        String clientIp = extractClientIp(request);
        String accountId = resolveAuthorizedAccountId(authorizedUsername, request);
        MultiPeriodDashboardBundle bundle = stored
                ? hubService.analyzeStoredAccountBundle(authorizedUsername, accountId)
                : hubService.analyzeWorkspaceBundle(authorizedUsername, resolvedPeriod, provider, refresh,
                        clientIp, accountId, continueRelations);
        if (bundle.hours24() != null) verifyReportAccountIsolation(authorizedUsername, bundle.hours24());
        if (bundle.day() != null) verifyReportAccountIsolation(authorizedUsername, bundle.day());
        if (bundle.week() != null) verifyReportAccountIsolation(authorizedUsername, bundle.week());
        if (bundle.month() != null) verifyReportAccountIsolation(authorizedUsername, bundle.month());
        return bundle;
    }

    @PostMapping("/usage/checkin")
    public UsageCheckinResponse checkinUsage(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        applyPrivateNoStoreHeaders(response);
        RequestSecuritySupport.verifySameOriginForMutation(request);
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
        RequestSecuritySupport.verifySameOriginForMutation(request);
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

    private CommunityAnalyticsEngine.ReportPeriod parseReportPeriod(
            String period,
            Integer fallbackDays,
            CommunityAnalyticsEngine.ReportPeriod defaultPeriod
    ) {
        if (period != null && !period.isBlank()) {
            return switch (period.trim().toUpperCase()) {
                case "HOURS_24", "24H", "24HOURS", "HOURS_8", "8H", "8HOURS", "1" -> CommunityAnalyticsEngine.ReportPeriod.HOURS_24;
                case "HOURS_48", "48H", "DAY", "D", "2" -> CommunityAnalyticsEngine.ReportPeriod.HOURS_48;
                case "WEEK", "W", "7D", "7" -> CommunityAnalyticsEngine.ReportPeriod.WEEK;
                case "MONTH", "M", "30D", "30" -> CommunityAnalyticsEngine.ReportPeriod.MONTH;
                default -> defaultPeriod;
            };
        }
        if (fallbackDays != null) {
            if (fallbackDays <= 1) {
                return CommunityAnalyticsEngine.ReportPeriod.HOURS_24;
            }
            return CommunityAnalyticsEngine.ReportPeriod.fromDays(fallbackDays);
        }
        return defaultPeriod;
    }

    private String extractClientIp(HttpServletRequest request) {
        return RequestSecuritySupport.extractClientIp(request);
    }

    public static boolean isTrustedProxyAddress(String ip) {
        return RequestSecuritySupport.isTrustedProxyAddress(ip);
    }
}
