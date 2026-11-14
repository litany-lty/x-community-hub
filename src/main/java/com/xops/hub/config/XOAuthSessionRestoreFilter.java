package com.xops.hub.config;

import com.xops.hub.controller.XOAuthController;
import com.xops.hub.store.XOAuthSessionStore;
import com.xops.hub.store.XOAuthSessionStore.StoredAuthSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当服务端重启或内存 HttpSession 失效时，自动从加密持久化存储恢复已登录身份并轮换会话。
 */
@Component
public class XOAuthSessionRestoreFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/api/auth/x/login",
            "/api/auth/x/callback",
            "/api/auth/x/logout",
            "/api/health",
            "/api/meta"
    );

    private final XOAuthSessionStore sessionStore;
    private final ConcurrentHashMap<String, Object> restoreLocks = new ConcurrentHashMap<>();

    public XOAuthSessionRestoreFilter(XOAuthSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return true;
        }
        return EXCLUDED_PATHS.contains(uri);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession existing = request.getSession(false);
        if (existing == null || existing.getAttribute(XOAuthController.SESSION_AUTH_USER) == null) {
            String requestedSessionId = extractSessionId(request);
            if (requestedSessionId != null && !requestedSessionId.isBlank()) {
                synchronized (lockForSession(requestedSessionId)) {
                    HttpSession rechecked = request.getSession(false);
                    if (rechecked == null || rechecked.getAttribute(XOAuthController.SESSION_AUTH_USER) == null) {
                        long nowSec = Instant.now().getEpochSecond();
                        StoredAuthSession stored = sessionStore.loadValid(requestedSessionId, nowSec);
                        var restoredUser = stored != null ? stored.toAuthenticatedXUser() : null;
                        if (restoredUser != null) {
                            HttpSession restored = request.getSession(true);
                            restored.setMaxInactiveInterval((int) XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS);
                            restored.setAttribute(XOAuthController.SESSION_AUTH_USER, restoredUser);
                            restored.setAttribute(XOAuthController.SESSION_COOKIE_TOUCHED_AT, nowSec);
                            sessionStore.rotateOnRestore(requestedSessionId, restored.getId(), restoredUser, nowSec);
                            XOAuthController.writeSessionCookie(
                                    request,
                                    response,
                                    restored.getId(),
                                    XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS
                            );
                        }
                    }
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private Object lockForSession(String sessionId) {
        if (restoreLocks.size() > 2048) {
            restoreLocks.clear();
        }
        return restoreLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    private static String extractSessionId(HttpServletRequest request) {
        String requested = request.getRequestedSessionId();
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if ("JSESSIONID".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }
}
