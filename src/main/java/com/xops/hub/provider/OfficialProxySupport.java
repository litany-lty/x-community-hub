package com.xops.hub.provider;

import com.xops.hub.config.XOpsProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Shared outbound proxy builder for X official write & OAuth endpoints
 * ({@link XPublishClient} and {@code XOAuthService}).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>When {@code xops.official-proxy-url} is blank, connects directly.</li>
 *   <li>When configured (e.g. {@code http://127.0.0.1:18118}), routes all non-loopback
 *       official X requests through the proxy and fails closed (never falling back to
 *       {@code DIRECT}) if the tunnel is unreachable, preventing origin IP leaks.</li>
 *   <li>Loopback target hosts ({@code 127.0.0.1}, {@code localhost}, {@code [::1]}) bypass
 *       the proxy so local mock tests remain isolated.</li>
 * </ul>
 */
public final class OfficialProxySupport {

    private OfficialProxySupport() {
    }

    public static HttpClient buildHttpClient(XOpsProperties properties, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        String raw = properties != null ? properties.getOfficialProxyUrl() : null;
        if (raw != null && !raw.isBlank()) {
            builder.proxy(parseStrictHttpProxySelector(raw.trim()));
        }
        return builder.build();
    }

    public static ProxySelector parseStrictHttpProxySelector(String rawProxyUrl) {
        final URI uri;
        try {
            uri = URI.create(rawProxyUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid xops.official-proxy-url syntax", ex);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getPort() <= 0 || uri.getPort() > 65535
                || uri.getUserInfo() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "xops.official-proxy-url must be a clean http://host:port URI without credentials, path, or query");
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), uri.getPort()));
        List<Proxy> proxied = List.of(proxy);
        List<Proxy> direct = List.of(Proxy.NO_PROXY);

        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI target) {
                if (target == null || isLoopbackHost(target.getHost())) {
                    return direct;
                }
                return proxied;
            }

            @Override
            public void connectFailed(URI target, SocketAddress sa, IOException ioe) {
                // Intentionally fail-closed: never fall back to direct connection on proxy failure.
            }
        };
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }
}
