package com.xops.hub.config;

import com.xops.hub.provider.XDataProviderException;
import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一的请求边界安全校验：可信反代客户端 IP 提取、基准同源 Origin 解析与写接口防跨站（CSRF）校验。
 */
public final class RequestSecuritySupport {

    private static final Pattern SAFE_HOST_PATTERN =
            Pattern.compile("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$");
    private static final Pattern SAFE_IP_LITERAL_PATTERN =
            Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private RequestSecuritySupport() {
    }

    public static boolean isLoopbackAddress(String ip) {
        return "127.0.0.1".equals(ip)
                || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip);
    }

    public static boolean isTrustedProxyAddress(String ip) {
        // 本项目的反代连接来自本机；私网地址不等同于受信代理。
        return isLoopbackAddress(normalizeIpLiteral(ip));
    }

    public static String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = normalizeIpLiteral(request.getRemoteAddr());
        // Tomcat native 转发处理开启时 remoteAddr 已是客户端地址，此处不再信任外来头。
        if (isTrustedProxyAddress(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank() && xff.length() <= 2048) {
                String[] chain = xff.split(",", -1);
                for (int i = chain.length - 1; i >= 0; i--) {
                    String candidate = normalizeIpLiteral(chain[i].trim());
                    if (candidate == null) {
                        break;
                    }
                    if (!isTrustedProxyAddress(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return remote != null ? remote : "unknown";
    }

    private static String normalizeIpLiteral(String raw) {
        if (raw == null || !SAFE_IP_LITERAL_PATTERN.matcher(raw).matches()) {
            return null;
        }
        if (raw.indexOf(':') >= 0) {
            try {
                // 包含冒号且仅包含 IP 字符，绝不将主机名交给 DNS。
                return InetAddress.getByName(raw).getHostAddress();
            } catch (UnknownHostException ex) {
                return null;
            }
        }
        String[] octets = raw.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int value = Integer.parseInt(octet);
            if (value > 255) {
                return null;
            }
            if (!normalized.isEmpty()) normalized.append('.');
            normalized.append(value);
        }
        return normalized.toString();
    }

    public static String requestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return servletPath != null && !servletPath.isEmpty() ? servletPath : request.getRequestURI();
    }

    public static String resolveBaseOrigin(HttpServletRequest req) {
        if (req == null) {
            return "http://localhost";
        }
        boolean trustedProxy = isTrustedProxyAddress(req.getRemoteAddr());
        String proto = req.getScheme();
        if (trustedProxy) {
            String xfp = req.getHeader("X-Forwarded-Proto");
            if (xfp != null && !xfp.isBlank()) {
                int comma = xfp.lastIndexOf(',');
                String firstProto = xfp.substring(comma + 1).trim().toLowerCase(Locale.ROOT);
                if ("https".equals(firstProto) || "http".equals(firstProto)) {
                    proto = firstProto;
                }
            }
        }

        String host = null;
        if (trustedProxy) {
            String xfh = req.getHeader("X-Forwarded-Host");
            if (xfh != null && !xfh.isBlank()) {
                int comma = xfh.lastIndexOf(',');
                String candidate = xfh.substring(comma + 1).trim();
                if (SAFE_HOST_PATTERN.matcher(candidate).matches()) {
                    host = candidate;
                }
            }
        }
        if (host == null) {
            host = req.getServerName();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            int port = req.getServerPort();
            boolean standardPort = ("http".equalsIgnoreCase(proto) && port == 80)
                    || ("https".equalsIgnoreCase(proto) && port == 443);
            if (!standardPort && port > 0) {
                host = host + ":" + port;
            }
        }
        return proto + "://" + host;
    }

    /**
     * 对状态变更类请求校验浏览器同源特征（Sec-Fetch-Site / Origin / Referer），阻断跨站伪造请求。
     * 无浏览器特征头时保持兼容（供服务端内部或单元/MockMvc 测试直接调用）。
     */
    public static void verifySameOriginForMutation(HttpServletRequest request) {
        if (request == null) {
            return;
        }
        String secFetchSite = request.getHeader("Sec-Fetch-Site");
        if (secFetchSite != null && "cross-site".equalsIgnoreCase(secFetchSite.trim())) {
            throw new XDataProviderException(403, "跨站请求来源校验未通过");
        }
        String originHeader = request.getHeader("Origin");
        if (originHeader != null && !originHeader.isBlank()) {
            String trimmed = originHeader.trim();
            if ("null".equalsIgnoreCase(trimmed)) {
                throw new XDataProviderException(403, "跨站请求来源校验未通过");
            }
            verifyUriMatchesExpectedOrigin(trimmed, request);
            return;
        }
        String refererHeader = request.getHeader("Referer");
        if (refererHeader != null && !refererHeader.isBlank()) {
            verifyUriMatchesExpectedOrigin(refererHeader.trim(), request);
            return;
        }
        // 同站子域仍可能被另一方控制，缺少 Origin/Referer 时不能按同源放行。
        if (secFetchSite != null && !"same-origin".equalsIgnoreCase(secFetchSite.trim())) {
            throw new XDataProviderException(403, "跨站请求来源校验未通过");
        }
    }

    private static void verifyUriMatchesExpectedOrigin(String rawUri, HttpServletRequest request) {
        try {
            URI actualUri = URI.create(rawUri);
            URI expectedUri = URI.create(resolveBaseOrigin(request));
            if (!sameOrigin(actualUri, expectedUri)) {
                throw new XDataProviderException(403, "跨站请求来源校验未通过");
            }
        } catch (IllegalArgumentException ex) {
            throw new XDataProviderException(403, "跨站请求来源校验未通过");
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        if (a.getScheme() == null || b.getScheme() == null || a.getHost() == null || b.getHost() == null
                || a.getUserInfo() != null || b.getUserInfo() != null) {
            return false;
        }
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
