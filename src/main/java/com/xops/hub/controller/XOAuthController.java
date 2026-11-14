package com.xops.hub.controller;

import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthStatusResponse;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.service.XOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth/x")
public class XOAuthController {

    private static final Logger log = LoggerFactory.getLogger(XOAuthController.class);
    public static final String SESSION_AUTH_USER = "XOPS_AUTH_USER";
    public static final String SESSION_OAUTH_STATES = "XOPS_OAUTH_STATES";
    private static final Pattern SAFE_HOST_PATTERN =
            Pattern.compile("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

    private final XOpsProperties properties;
    private final XOAuthService oauthService;

    public XOAuthController(XOpsProperties properties, XOAuthService oauthService) {
        this.properties = properties;
        this.oauthService = oauthService;
    }

    @GetMapping("/status")
    public AuthStatusResponse getStatus(HttpServletRequest request, HttpServletResponse response) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        HttpSession session = request.getSession(false);
        AuthenticatedXUser user = session != null
                ? (AuthenticatedXUser) session.getAttribute(SESSION_AUTH_USER)
                : null;
        String baseOrigin = resolveBaseOrigin(request);
        return new AuthStatusResponse(
                properties.hasOAuthClient(),
                oauthService.resolveCallbackUrl(baseOrigin),
                user != null,
                user
        );
    }

    @GetMapping("/login")
    public void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!properties.hasOAuthClient()) {
            String msg = URLEncoder.encode("服务端尚未在 application.yml 中配置 xops.oauth-client-id", StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
            return;
        }
        try {
            XOAuthService.AuthorizationRequestInfo reqInfo =
                    oauthService.createAuthorizationRequest(resolveBaseOrigin(request));
            HttpSession session = request.getSession(true);
            rememberOAuthStateInSession(session, reqInfo.state());
            response.sendRedirect(reqInfo.authorizationUrl());
        } catch (Exception ex) {
            String err = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "发起 X 授权登录异常";
            String msg = URLEncoder.encode(err, StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
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
        if (error != null && !error.isBlank()) {
            String msg = URLEncoder.encode("已取消或拒绝 X 授权登录（" + error + "）", StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
            return;
        }
        try {
            HttpSession existingSession = request.getSession(false);
            if (!consumeOAuthStateFromSession(existingSession, state)) {
                throw new IllegalArgumentException("登录会话状态不匹配或跨浏览器发起，请从本页重新点击「使用 X 账号授权登录」");
            }
            AuthenticatedXUser user = oauthService.handleCallback(code, state);
            HttpSession session = request.getSession(true);
            session.removeAttribute(SESSION_AUTH_USER);
            try {
                request.changeSessionId();
            } catch (IllegalStateException ignored) {
            }
            session.setAttribute(SESSION_AUTH_USER, user);
            log.info("X OAuth 2.0 login succeeded for @{}", user.username());
            response.sendRedirect("/?auth=ok");
        } catch (Exception ex) {
            String err = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "X 授权回调校验失败";
            log.warn("X OAuth 2.0 callback failed: {}", err);
            String msg = URLEncoder.encode(err, StandardCharsets.UTF_8);
            response.sendRedirect("/?auth_error=" + msg);
        }
    }

    @SuppressWarnings("unchecked")
    private void rememberOAuthStateInSession(HttpSession session, String state) {
        synchronized (session) {
            List<String> states = (List<String>) session.getAttribute(SESSION_OAUTH_STATES);
            List<String> updated = states != null ? new ArrayList<>(states) : new ArrayList<>();
            updated.add(state);
            while (updated.size() > 5) {
                updated.remove(0);
            }
            session.setAttribute(SESSION_OAUTH_STATES, updated);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean consumeOAuthStateFromSession(HttpSession session, String state) {
        if (session == null || state == null || state.isBlank()) {
            return false;
        }
        synchronized (session) {
            List<String> states = (List<String>) session.getAttribute(SESSION_OAUTH_STATES);
            if (states == null || !states.contains(state)) {
                return false;
            }
            List<String> updated = new ArrayList<>(states);
            updated.remove(state);
            session.setAttribute(SESSION_OAUTH_STATES, updated);
            return true;
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Map.of("ok", true);
    }

    static String resolveBaseOrigin(HttpServletRequest req) {
        boolean trustedProxy = CommunityApiController.isTrustedProxyAddress(req.getRemoteAddr());
        String proto = req.getScheme();
        if (trustedProxy) {
            String xfp = req.getHeader("X-Forwarded-Proto");
            if (xfp != null && !xfp.isBlank()) {
                String firstProto = xfp.split(",", 2)[0].trim().toLowerCase();
                if ("https".equals(firstProto) || "http".equals(firstProto)) {
                    proto = firstProto;
                }
            }
        }

        String host = null;
        if (trustedProxy) {
            String xfh = req.getHeader("X-Forwarded-Host");
            if (xfh != null && !xfh.isBlank()) {
                String candidate = xfh.split(",", 2)[0].trim();
                if (SAFE_HOST_PATTERN.matcher(candidate).matches()) {
                    host = candidate;
                }
            }
        }
        if (host == null) {
            host = req.getServerName();
            int port = req.getServerPort();
            boolean standardPort = ("http".equalsIgnoreCase(proto) && port == 80)
                    || ("https".equalsIgnoreCase(proto) && port == 443);
            if (!standardPort && port > 0) {
                host = host + ":" + port;
            }
        }
        return proto + "://" + host;
    }
}
