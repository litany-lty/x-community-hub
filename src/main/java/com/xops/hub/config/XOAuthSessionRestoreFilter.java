package com.xops.hub.config;

import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.store.XOAuthSessionStore;
import com.xops.hub.store.XOAuthSessionStore.StoredAuthSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 当服务端重启或内存 HttpSession 失效时，自动从加密持久化存储恢复已登录身份并轮换会话。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class XOAuthSessionRestoreFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/api/auth/x/login",
            "/api/auth/x/callback",
            "/api/auth/x/logout",
            "/api/health",
            "/api/meta"
    );
    private static final Pattern PLAUSIBLE_SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final int LOCK_STRIPES = 64;
    private static final int MAX_RESTORE_CACHE = 4096;
    private static final long RESTORE_REUSE_SECONDS = 60L;
    private record RestoredSession(HttpSession session, long expiresAtEpochSec) {}
    private final Map<String, RestoredSession> restoredSessions = new LinkedHashMap<>();

    private final XOAuthSessionStore sessionStore;
    private final Object[] restoreLocks;

    public XOAuthSessionRestoreFilter(XOAuthSessionStore sessionStore) {
        this.sessionStore = sessionStore;
        this.restoreLocks = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.restoreLocks[i] = new Object();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = RequestSecuritySupport.requestPath(request);
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
        HttpServletRequest effectiveRequest = request;
        HttpSession existing = request.getSession(false);
        long nowSec = Instant.now().getEpochSecond();
        boolean needsRestore = true;
        if (existing != null) {
            synchronized (existing) {
                try {
                    Object identity = existing.getAttribute(XOAuthController.SESSION_AUTH_USER);
                    needsRestore = identity == null;
                    if (!needsRestore) {
                        if (existing.getMaxInactiveInterval() <= 0
                                || existing.getMaxInactiveInterval() > XOAuthSessionStore.MAX_IN_MEMORY_IDLE_SECONDS) {
                            existing.setMaxInactiveInterval(XOAuthSessionStore.MAX_IN_MEMORY_IDLE_SECONDS);
                        }
                        if (sessionStore.isPersistenceEnabled()) {
                            StoredAuthSession persisted = sessionStore.loadValid(existing.getId(), nowSec);
                            // Identity, current ID and credential must come from the same committed rotation.
                            if (!(identity instanceof AuthenticatedXUser user) || persisted == null || persisted.user() == null
                                    || !Objects.equals(user.id(), persisted.user().id())) {
                                existing.invalidate();
                                needsRestore = true;
                            }
                        }
                    }
                } catch (IllegalStateException ex) {
                    // Logout may have invalidated it before this request acquired the session monitor.
                    needsRestore = true;
                }
            }
        }
        // Release the existing-session monitor before acquiring the restore stripe.
        if (needsRestore) {
            String requestedSessionId = extractSessionId(request);
            if (requestedSessionId != null && PLAUSIBLE_SESSION_ID.matcher(requestedSessionId).matches()) {
                synchronized (lockForSession(requestedSessionId)) {
                    HttpSession rechecked = request.getSession(false);
                    if (rechecked != null) {
                        synchronized (rechecked) {
                            effectiveRequest = restoreIfUnauthenticated(request, response, rechecked, requestedSessionId, nowSec);
                        }
                    } else {
                        effectiveRequest = restoreIfUnauthenticated(request, response, null, requestedSessionId, nowSec);
                    }
                }
            }
        }

        // 在进入 DispatcherServlet Multipart 流式落盘前拒绝未授权的上传请求，防止匿名 25MB 载荷攻击
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            HttpSession afterCheck = effectiveRequest.getSession(false);
            if (afterCheck == null || afterCheck.getAttribute(XOAuthController.SESSION_AUTH_USER) == null) {
                MaliciousRequestShieldFilter.rejectJsonOrEmpty(
                        request,
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "PROVIDER_ERROR",
                        "请先使用 X 账号授权登录"
                );
                return;
            }
        }

        filterChain.doFilter(effectiveRequest, response);
    }

    private HttpServletRequest restoreIfUnauthenticated(HttpServletRequest request, HttpServletResponse response,
                                                        HttpSession rechecked, String requestedSessionId, long nowSec) {
        // An existing anonymous session shares the callback/logout monitor for its entire promotion.
        if (rechecked != null) {
            try {
                if (rechecked.getAttribute(XOAuthController.SESSION_AUTH_USER) != null) return request;
            } catch (IllegalStateException ex) {
                rechecked = null;
            }
        }
        StoredAuthSession stored = sessionStore.loadValid(requestedSessionId, nowSec);
        var restoredUser = stored != null ? stored.toAuthenticatedXUser() : null;
        if (restoredUser == null) return request;
        HttpServletRequest effectiveRequest = request;
        HttpSession restored = cachedSession(requestedSessionId, nowSec);
        if (restored != null) {
            effectiveRequest = withRestoredSession(request, restored);
        } else {
            // 已有匿名会话提升为登录身份时必须换 ID，避免会话固定。
            if (rechecked != null) request.changeSessionId();
            restored = request.getSession(true);
            try {
                sessionStore.rotateOnRestore(requestedSessionId, restored.getId(), restoredUser, nowSec);
                restored.setMaxInactiveInterval(XOAuthSessionStore.MAX_IN_MEMORY_IDLE_SECONDS);
                restored.setAttribute(XOAuthController.SESSION_AUTH_USER, restoredUser);
                restored.setAttribute(XOAuthController.SESSION_COOKIE_TOUCHED_AT, nowSec);
                cacheRestoredSession(requestedSessionId, restored, nowSec);
            } catch (IllegalStateException ex) {
                // load 与 rotate 之间可能刚好登出，不能遗留带身份的新会话。
                restored.invalidate();
                restored = null;
            }
        }
        if (restored != null) {
            XOAuthController.writeSessionCookie(request, response, restored.getId(),
                    XOAuthSessionStore.MAX_SESSION_LIFETIME_SECONDS);
        }
        return effectiveRequest;
    }

    private HttpSession cachedSession(String oldId, long nowSec) {
        RestoredSession cached;
        synchronized (restoredSessions) {
            cached = restoredSessions.get(oldId);
        }
        if (cached == null) return null;
        try {
            if (cached.expiresAtEpochSec() > nowSec
                    && cached.session().getAttribute(XOAuthController.SESSION_AUTH_USER) != null
                    && sessionStore.loadValid(cached.session().getId(), nowSec) != null) {
                return cached.session();
            }
        } catch (IllegalStateException ignored) {
            // 原会话已被登出失效。
        }
        synchronized (restoredSessions) {
            restoredSessions.remove(oldId, cached);
        }
        return null;
    }

    private void cacheRestoredSession(String oldId, HttpSession session, long nowSec) {
        synchronized (restoredSessions) {
            if (restoredSessions.size() >= MAX_RESTORE_CACHE) {
                restoredSessions.entrySet().removeIf(e -> e.getValue().expiresAtEpochSec() <= nowSec);
            }
            if (restoredSessions.size() < MAX_RESTORE_CACHE) {
                restoredSessions.put(oldId, new RestoredSession(session, nowSec + RESTORE_REUSE_SECONDS));
            }
        }
    }

    private static HttpServletRequest withRestoredSession(HttpServletRequest request, HttpSession restored) {
        return new HttpServletRequestWrapper(request) {
            @Override public HttpSession getSession() { return getSession(true); }
            @Override public HttpSession getSession(boolean create) {
                try {
                    restored.getCreationTime();
                    return restored;
                } catch (IllegalStateException ex) {
                    return super.getSession(create);
                }
            }
        };
    }

    private Object lockForSession(String sessionId) {
        return restoreLocks[(sessionId.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    private static String extractSessionId(HttpServletRequest request) {
        // 只恢复真实 Cookie；URL 重写参数不能作为持久登录凭据。
        if (request.isRequestedSessionIdFromURL()) return null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("JSESSIONID".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        if (request.isRequestedSessionIdFromCookie()) {
            String requested = request.getRequestedSessionId();
            return requested != null && !requested.isBlank() ? requested.trim() : null;
        }
        return null;
    }
}
