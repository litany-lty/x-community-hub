package com.xops.hub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 为静态页面与前端资源补充 HTTP 缓存头：
 * HTML 入口保持 no-cache 以便及时拉取最新带版本号资源，静态图片与脚本/样式启用 7 天本地缓存。
 */
@Component
public class StaticAssetCacheHeaderFilter extends OncePerRequestFilter {

    private static final Pattern CACHEABLE_ASSET_PATTERN = Pattern.compile(
            "(?i)^/[a-zA-Z0-9._/-]+\\.(webp|png|jpe?g|gif|svg|ico|woff2?|css|js)$"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri == null || uri.startsWith("/api/") || uri.startsWith("/oauth2/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            response.setHeader("Cache-Control", "no-cache");
        } else if (CACHEABLE_ASSET_PATTERN.matcher(uri).matches()) {
            response.setHeader("Cache-Control", "public, max-age=604800");
        }
        filterChain.doFilter(request, response);
    }
}
