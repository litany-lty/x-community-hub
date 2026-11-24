package com.xops.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.RequestSecuritySupport;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthStatusResponse;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.store.XOAuthSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/x")
public class XOAuthController {

    private static final Logger log = LoggerFactory.getLogger(XOAuthController.class);
    public static final String SESSION_AUTH_USER = "XOPS_AUTH_USER";
    public static final String SESSION_OAUTH_STATES = "XOPS_OAUTH_STATES";
    public static final String SESSION_PUBLISH_OAUTH_STATES = "XOPS_PUBLISH_OAUTH_STATES";
    public static final String SESSION_COOKIE_TOUCHED_AT = "XOPS_COOKIE_TOUCHED_AT";
    private static final int SESSION_MAX_AGE_SECONDS = (int) XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS;
    private static final int PRE_AUTH_SESSION_MAX_AGE_SECONDS = 600;

    private final XOpsProperties properties;
    private final XOAuthService oauthService;
    private final XOAuthSessionStore sessionStore;
    private final CommunityHubService communityHubService;

    public XOAuthController(XOpsProperties properties, XOAuthService oauthService) {
        this(properties, oauthService, new XOAuthSessionStore(properties, new ObjectMapper()), null);
    }

    public XOAuthController(XOpsProperties properties, XOAuthService oauthService, XOAuthSessionStore sessionStore) {
        this(properties, oauthService, sessionStore, null);
    }

    @Autowired
    public XOAuthController(
            XOpsProperties properties,
            XOAuthService oauthService,
            XOAuthSessionStore sessionStore,
            CommunityHubService communityHubService
    ) {
        this.properties = properties;
        this.oauthService = oauthService;
        this.sessionStore = sessionStore;
        this.communityHubService = communityHubService;
    }

    private com.xops.hub.assistant.AssistantStore assistantStore;

    @Autowired
    void bindAssistantStore(com.xops.hub.assistant.AssistantStore store) { this.assistantStore=store; }

    @GetMapping("/status")
    public AuthStatusResponse getStatus(HttpServletRequest request, HttpServletResponse response) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        HttpSession session = request.getSession(false);
        AuthenticatedXUser user = session != null
                ? (AuthenticatedXUser) session.getAttribute(SESSION_AUTH_USER)
                : null;
        boolean ownerAllPermissions = false;
        if (user != null) {
            if (assistantStore != null && CommunityHubService.AUTHOR_USERNAME.equalsIgnoreCase(user.username())) {
                ownerAllPermissions = assistantStore.authorize(user);
            } else if (communityHubService != null) {
                ownerAllPermissions = communityHubService.isAuthorAccount(user.id(), user.username());
            } else if (assistantStore != null && user.id() != null && !user.id().isBlank()) {
                ownerAllPermissions = assistantStore.isOwner(user.id());
            }
        }
        String baseOrigin = resolveBaseOrigin(request);
        boolean writeAuthorized = false;
        boolean mediaWriteAuthorized = false;
        boolean offlineRefreshable = false;
        if (user != null && oauthService != null && user.id() != null && !user.id().isBlank()) {
            XOAuthService.TokenCapability capability = oauthService.getTokenCapability(user.id());
            writeAuthorized = capability.writeAuthorized();
            mediaWriteAuthorized = capability.mediaWriteAuthorized();
            offlineRefreshable = capability.offlineRefreshable();
        }
        if (user != null && session != null && user.id() != null && !user.id().isBlank()) {
            long nowSec = Instant.now().getEpochSecond();
            Object touchedObj = session.getAttribute(SESSION_COOKIE_TOUCHED_AT);
            long lastTouchedSec = touchedObj instanceof Number n ? n.longValue() : 0L;
            boolean storeTouched = sessionStore != null && sessionStore.touchIfStale(session.getId(), user, nowSec);
            boolean cookieStale = lastTouchedSec <= 0L
                    || (nowSec - lastTouchedSec) >= XOAuthSessionStore.SLIDE_THRESHOLD_SECONDS;
            if (storeTouched || cookieStale) {
                session.setMaxInactiveInterval(XOAuthSessionStore.MAX_IN_MEMORY_IDLE_SECONDS);
                session.setAttribute(SESSION_COOKIE_TOUCHED_AT, nowSec);
                writeSessionCookie(request, response, session.getId(), SESSION_MAX_AGE_SECONDS);
            }
        }
        return new AuthStatusResponse(
                properties.hasOAuthClient(),
                oauthService != null ? oauthService.resolveCallbackUrl(baseOrigin) : resolveFallbackCallbackUrl(baseOrigin),
                user != null,
                user,
                writeAuthorized,
                mediaWriteAuthorized,
                offlineRefreshable,
                ownerAllPermissions
        );
    }

    private static String resolveFallbackCallbackUrl(String baseOrigin) {
        String cleanOrigin = baseOrigin.endsWith("/") ? baseOrigin.substring(0, baseOrigin.length() - 1) : baseOrigin;
        return cleanOrigin + "/api/auth/x/callback";
    }

    private String loopbackLocalhostRedirectIfNeeded(HttpServletRequest request) {
        if (!"127.0.0.1".equals(request.getServerName())) {
            return null;
        }
        String configuredRedirect = properties.getOauthRedirectUri();
        if (configuredRedirect == null || !configuredRedirect.startsWith("http://localhost:")) {
            return null;
        }
        String query = request.getQueryString();
        return "http://localhost:" + request.getServerPort() + request.getRequestURI()
                + (query != null && !query.isBlank() ? "?" + query : "");
    }

    @GetMapping("/login")
    public void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String canonicalLocalhost = loopbackLocalhostRedirectIfNeeded(request);
        if (canonicalLocalhost != null) {
            response.sendRedirect(canonicalLocalhost);
            return;
        }
        if (!properties.hasOAuthClient()) {
            String msg = URLEncoder.encode("服务端尚未在 application.yml 中配置 xops.oauth-client-id", StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
            return;
        }
        try {
            XOAuthService.AuthorizationRequestInfo reqInfo =
                    oauthService.createAuthorizationRequest(resolveBaseOrigin(request));
            HttpSession session = request.getSession(true);
            if (session.getAttribute(SESSION_AUTH_USER) == null) {
                session.setMaxInactiveInterval(PRE_AUTH_SESSION_MAX_AGE_SECONDS);
            }
            rememberOAuthStateInSession(session, reqInfo.state());
            response.sendRedirect(reqInfo.authorizationUrl());
        } catch (Exception ex) {
            String err = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "发起 X 授权登录异常";
            String msg = URLEncoder.encode(err, StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
        }
    }

    @GetMapping("/authorize-publish")
    public void authorizePublish(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String canonicalLocalhost = loopbackLocalhostRedirectIfNeeded(request);
        if (canonicalLocalhost != null) {
            response.sendRedirect(canonicalLocalhost);
            return;
        }
        if (!properties.hasOAuthClient()) {
            String msg = URLEncoder.encode("服务端尚未在 application.yml 中配置 xops.oauth-client-id", StandardCharsets.UTF_8);
            response.sendRedirect("/?tab=schedule&publish_auth_error=" + msg);
            return;
        }
        HttpSession session = request.getSession(false);
        AuthenticatedXUser user = session != null
                ? (AuthenticatedXUser) session.getAttribute(SESSION_AUTH_USER)
                : null;
        if (user == null || user.id() == null || user.id().isBlank()) {
            String msg = URLEncoder.encode("请先使用 X 账号授权登录，再开启定时发布二次授权", StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
            return;
        }
        if (communityHubService != null) {
            CommunityHubService.ScheduledPublishAccess access =
                    communityHubService.evaluateScheduledPublishAccess(user.id(), user.username());
            if (!access.allowed()) {
                String err = (access.reason() != null && !access.reason().isBlank())
                        ? access.reason()
                        : "定时发布仅限与 @" + CommunityHubService.AUTHOR_USERNAME + " 互动频率前 " + CommunityHubService.TOP_SCHEDULED_PUBLISH_RANK + " 的推友可用";
                String msg = URLEncoder.encode(err, StandardCharsets.UTF_8);
                response.sendRedirect("/?tab=schedule&publish_auth_error=" + msg);
                return;
            }
        }
        try {
            XOAuthService.AuthorizationRequestInfo reqInfo =
                    oauthService.createPublishAuthorizationRequest(resolveBaseOrigin(request), user.id());
            rememberOAuthStateInSession(session, reqInfo.state());
            rememberStateInList(session, SESSION_PUBLISH_OAUTH_STATES, reqInfo.state());
            response.sendRedirect(reqInfo.authorizationUrl());
        } catch (Exception ex) {
            String err = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "发起定时发布二次授权异常";
            String msg = URLEncoder.encode(err, StandardCharsets.UTF_8);
            response.sendRedirect("/?tab=schedule&publish_auth_error=" + msg);
        }
    }

    @GetMapping("/callback")
    public void callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        HttpSession existingSession = request.getSession(false);
        boolean publishFlow = consumeStateFromList(existingSession, SESSION_PUBLISH_OAUTH_STATES, state);
        boolean keepLoggedInOnError = publishFlow
                && existingSession != null
                && existingSession.getAttribute(SESSION_AUTH_USER) != null;

        if (error != null && !error.isBlank()) {
            consumeOAuthStateFromSession(existingSession, state);
            if (oauthService != null) {
                oauthService.discardPendingState(state);
            }
            if (keepLoggedInOnError) {
                String msg = URLEncoder.encode("已取消定时发布二次授权（" + error + "），不影响查看运营报表", StandardCharsets.UTF_8);
                response.sendRedirect("/?tab=schedule&publish_auth_error=" + msg);
            } else {
                String msg = URLEncoder.encode("已取消或拒绝 X 授权登录（" + error + "）", StandardCharsets.UTF_8);
                response.sendRedirect("/?auth_error=" + msg);
            }
            return;
        }
        try {
            if (!consumeOAuthStateFromSession(existingSession, state)) {
                throw new IllegalArgumentException("登录会话状态不匹配或跨浏览器发起，请从本页重新点击「使用 X 账号授权登录」");
            }
            XOAuthService.CallbackResult cb = oauthService.handleCallbackResult(code, state);
            AuthenticatedXUser user = cb.user();
            // Logout may have invalidated the pending OAuth session while the upstream callback ran.
            HttpSession session = request.getSession(false);
            if (session == null) {
                throw new IllegalStateException("登录会话已失效，请重新登录");
            }
            promoteAuthenticatedSession(request, response, session, user);
            if (user != null && assistantStore != null && CommunityHubService.AUTHOR_USERNAME.equalsIgnoreCase(user.username())) {
                assistantStore.authorize(user);
            }
            if (cb.purpose() == XOAuthService.AuthPurpose.PUBLISH || publishFlow) {
                log.info("X OAuth 2.0 publish step-up authorization succeeded for @{}", user.username());
                response.sendRedirect("/?auth=publish_ok&tab=schedule");
            } else {
                log.info("X OAuth 2.0 login succeeded for @{}", user.username());
                response.sendRedirect("/?auth=ok");
            }
        } catch (Exception ex) {
            String err = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "X 授权回调校验失败";
            log.warn("X OAuth 2.0 callback failed: {}", err);
            String msg = URLEncoder.encode(err, StandardCharsets.UTF_8);
            if (keepLoggedInOnError) {
                response.sendRedirect("/?tab=schedule&publish_auth_error=" + msg);
            } else {
                response.sendRedirect("/?auth_error=" + msg);
            }
        }
    }

    private void promoteAuthenticatedSession(HttpServletRequest request, HttpServletResponse response,
                                             HttpSession session, AuthenticatedXUser user) {
        // The same monitor protects logout and serializes concurrent credential rotations.
        // Upstream OAuth requests run before this lock; only the local identity commit is serialized.
        synchronized (session) {
            String promotingSessionId = null;
            try {
                if (request.getSession(false) == null) {
                    throw new IllegalStateException("登录会话已失效，请重新登录");
                }
                boolean hadLoggedInUser = session.getAttribute(SESSION_AUTH_USER) != null;
                String oldSessionId = session.getId();
                // Keep the prior identity readable to requests already admitted by the filter.
                // Success replaces it after persistence; failure invalidates the whole session below.
                request.changeSessionId();
                promotingSessionId = session.getId();
                long nowSec = Instant.now().getEpochSecond();
                if (sessionStore != null) {
                    if (hadLoggedInUser && !oldSessionId.equals(promotingSessionId)) {
                        sessionStore.rotateOnRestore(oldSessionId, promotingSessionId, user, nowSec);
                    } else {
                        sessionStore.saveSession(promotingSessionId, user, nowSec);
                    }
                }
                // Expose the identity only after its persistent credential is committed.
                session.setMaxInactiveInterval(XOAuthSessionStore.MAX_IN_MEMORY_IDLE_SECONDS);
                session.setAttribute(SESSION_COOKIE_TOUCHED_AT, nowSec);
                session.setAttribute(SESSION_AUTH_USER, user);
                writeSessionCookie(request, response, promotingSessionId, SESSION_MAX_AGE_SECONDS);
            } catch (RuntimeException ex) {
                // Keep partial-write cleanup under this monitor so it cannot revoke a later callback.
                if (sessionStore != null && promotingSessionId != null) {
                    try {
                        sessionStore.revokeSession(promotingSessionId);
                    } catch (RuntimeException cleanupFailure) {
                        ex.addSuppressed(cleanupFailure);
                        log.warn("Failed to revoke incomplete OAuth session: {}", cleanupFailure.getMessage());
                    }
                }
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // A concurrent logout already invalidated it before this callback acquired the monitor.
                }
                writeSessionCookie(request, response, "", 0L);
                throw ex;
            }
        }
    }

    private void rememberOAuthStateInSession(HttpSession session, String state) {
        rememberStateInList(session, SESSION_OAUTH_STATES, state);
    }

    private boolean consumeOAuthStateFromSession(HttpSession session, String state) {
        return consumeStateFromList(session, SESSION_OAUTH_STATES, state);
    }

    @SuppressWarnings("unchecked")
    private void rememberStateInList(HttpSession session, String attrName, String state) {
        synchronized (session) {
            List<String> states = (List<String>) session.getAttribute(attrName);
            List<String> updated = states != null ? new ArrayList<>(states) : new ArrayList<>();
            updated.add(state);
            while (updated.size() > 5) {
                updated.remove(0);
            }
            session.setAttribute(attrName, updated);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean consumeStateFromList(HttpSession session, String attrName, String state) {
        if (session == null || state == null || state.isBlank()) {
            return false;
        }
        synchronized (session) {
            List<String> states = (List<String>) session.getAttribute(attrName);
            if (states == null || !states.contains(state)) {
                return false;
            }
            List<String> updated = new ArrayList<>(states);
            updated.remove(state);
            session.setAttribute(attrName, updated);
            return true;
        }
    }

    public Map<String, Object> logout(HttpServletRequest request) {
        return logout(request, null);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        RequestSecuritySupport.verifySameOriginForMutation(request);
        HttpSession session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                // Capture the current ID after any in-flight callback finishes rotating this session.
                if (sessionStore != null) {
                    sessionStore.revokeSession(request.getRequestedSessionId(), session.getId());
                }
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // Another logout already invalidated the same session.
                }
            }
        } else if (sessionStore != null) {
            sessionStore.revokeSession(request.getRequestedSessionId());
        }
        if (response != null) {
            writeSessionCookie(request, response, "", 0L);
        }
        return Map.of("ok", true);
    }

    public static void writeSessionCookie(
            HttpServletRequest request,
            HttpServletResponse response,
            String sessionId,
            long maxAgeSeconds
    ) {
        if (response == null) {
            return;
        }
        String baseOrigin = request != null ? resolveBaseOrigin(request) : "http://localhost";
        ResponseCookie cookie = ResponseCookie.from("JSESSIONID", sessionId != null ? sessionId : "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(baseOrigin.startsWith("https://"))
                .maxAge(Duration.ofSeconds(Math.max(0L, maxAgeSeconds)))
                .build();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    static String resolveBaseOrigin(HttpServletRequest req) {
        return RequestSecuritySupport.resolveBaseOrigin(req);
    }
}
