package com.xops.hub.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.service.XOAuthService;
import com.xops.hub.store.XOAuthTokenStore;
import com.xops.hub.store.XOAuthTokenStore.StoredOAuthToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class OfficialProxySupportTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesProxyUrlSyntaxStrictly() {
        assertNotNull(OfficialProxySupport.parseStrictHttpProxySelector("http://127.0.0.1:18118"));

        assertThrows(IllegalArgumentException.class,
                () -> OfficialProxySupport.parseStrictHttpProxySelector("socks5://127.0.0.1:1080"));
        assertThrows(IllegalArgumentException.class,
                () -> OfficialProxySupport.parseStrictHttpProxySelector("https://127.0.0.1:18118"));
        assertThrows(IllegalArgumentException.class,
                () -> OfficialProxySupport.parseStrictHttpProxySelector("http://127.0.0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> OfficialProxySupport.parseStrictHttpProxySelector("http://user:pass@127.0.0.1:18118"));
        assertThrows(IllegalArgumentException.class,
                () -> OfficialProxySupport.parseStrictHttpProxySelector("http://127.0.0.1:18118/proxy"));
        assertThrows(IllegalArgumentException.class,
                () -> OfficialProxySupport.parseStrictHttpProxySelector("http://127.0.0.1:18118?foo=1"));
    }

    @Test
    void loopbackTargetsBypassProxyWhileExternalTargetsUseProxy() throws Exception {
        ProxySelector selector = OfficialProxySupport.parseStrictHttpProxySelector("http://127.0.0.1:18118");

        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://127.0.0.1:9000/2/tweets")));
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://localhost:9000/2/tweets")));
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("http://[::1]:9000/2/tweets")));

        List<Proxy> external = selector.select(URI.create("https://api.x.com/2/tweets"));
        assertEquals(1, external.size());
        assertEquals(Proxy.Type.HTTP, external.get(0).type());
        assertEquals(new InetSocketAddress("127.0.0.1", 18118), external.get(0).address());

        selector.connectFailed(
                URI.create("https://api.x.com/2/tweets"),
                external.get(0).address(),
                new IOException("tunnel down")
        );
        assertEquals(Proxy.Type.HTTP, selector.select(URI.create("https://api.x.com/2/tweets")).get(0).type());

        int closedProxyPort;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedProxyPort = ss.getLocalPort();
        }
        HttpServer localMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localMock.createContext("/2/tweets", ex -> {
            ex.getRequestBody().readAllBytes();
            writeJson(ex, 201, "{\"data\":{\"id\":\"local_mock_tweet_1\"}}");
        });
        localMock.start();
        try {
            XOpsProperties props = baseProperties();
            props.setOfficialBaseUrl("http://127.0.0.1:" + localMock.getAddress().getPort() + "/2");
            props.setOfficialProxyUrl("http://127.0.0.1:" + closedProxyPort);
            XPublishClient client = new XPublishClient(props, new ObjectMapper());
            assertEquals("local_mock_tweet_1", client.createTweet("tok_local", "hello local", List.of()));
        } finally {
            localMock.stop(0);
        }
    }

    @Test
    void httpsPublishMediaTweetAndOAuthFlowsTunnelThroughConfiguredHttpConnectProxy() throws Exception {
        List<String> connectLines = new CopyOnWriteArrayList<>();
        ExecutorService acceptPool = Executors.newCachedThreadPool();
        try (ServerSocket connectProxy = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            int proxyPort = connectProxy.getLocalPort();
            acceptPool.submit(() -> {
                while (!connectProxy.isClosed()) {
                    try {
                        Socket client = connectProxy.accept();
                        acceptPool.submit(() -> handleConnectAttempt(client, connectLines));
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });

            XOpsProperties props = baseProperties();
            props.setOfficialBaseUrl("https://api.x.com/2");
            props.setOfficialProxyUrl("http://127.0.0.1:" + proxyPort);

            ObjectMapper mapper = new ObjectMapper();
            XPublishClient publishClient = new XPublishClient(props, mapper);
            assertThrows(XPublishClient.XPublishException.class,
                    () -> publishClient.uploadMedia("acc_jp", new byte[]{1, 2, 3, 4}, "image/png", "cover.png"));
            assertThrows(XPublishClient.XPublishException.class,
                    () -> publishClient.createTweet("acc_jp", "Hello via JP proxy", List.of()));

            XOAuthService oauthService = new XOAuthService(props, mapper);
            XOAuthService.AuthorizationRequestInfo authReq =
                    oauthService.createPublishAuthorizationRequest("https://x.life-benchmark.com", "u_jp_1");
            assertThrows(XDataProviderException.class,
                    () -> oauthService.handleCallbackResult("code_jp_1", authReq.state()));

            XOAuthTokenStore tokenStore = new XOAuthTokenStore(props, mapper);
            tokenStore.save(new StoredOAuthToken(
                    "acc_expired_1",
                    "jp_author",
                    "expired_access",
                    "valid_refresh",
                    1000L,
                    XOAuthService.PUBLISH_OAUTH_SCOPE,
                    1000L
            ));
            assertThrows(XDataProviderException.class,
                    () -> oauthService.getValidWriteAccessToken("acc_expired_1", false));

            assertEquals(4, connectLines.size());
            for (String line : connectLines) {
                assertTrue(line.startsWith("CONNECT api.x.com:443 HTTP/1.1"),
                        "Expected HTTP CONNECT to api.x.com:443 via loopback proxy, got: " + line);
            }
        } finally {
            acceptPool.shutdownNow();
        }
    }

    @Test
    void failsClosedWhenProxyIsUnreachableWithoutDirectFallback() throws Exception {
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = ss.getLocalPort();
        }

        XOpsProperties props = baseProperties();
        props.setOfficialBaseUrl("https://api.x.com/2");
        props.setOfficialProxyUrl("http://127.0.0.1:" + closedPort);

        ObjectMapper mapper = new ObjectMapper();
        XPublishClient publishClient = new XPublishClient(props, mapper);
        XPublishClient.XPublishException publishEx = assertThrows(
                XPublishClient.XPublishException.class,
                () -> publishClient.createTweet("acc_jp", "Should fail closed", List.of())
        );
        assertEquals(502, publishEx.getStatusCode());

        XOAuthService oauthService = new XOAuthService(props, mapper);
        XOAuthService.AuthorizationRequestInfo authReq =
                oauthService.createAuthorizationRequest("https://x.life-benchmark.com");
        XDataProviderException oauthEx = assertThrows(
                XDataProviderException.class,
                () -> oauthService.handleCallbackResult("code_closed", authReq.state())
        );
        assertEquals(502, oauthEx.getStatusCode());
    }

    private static void handleConnectAttempt(Socket client, List<String> connectLines) {
        try (Socket sock = client) {
            sock.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine != null) {
                connectLines.add(requestLine);
            }
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // drain headers
            }
            byte[] resp = ("HTTP/1.1 502 Bad Gateway\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            OutputStream os = sock.getOutputStream();
            os.write(resp);
            os.flush();
        } catch (IOException ignored) {
        }
    }

    private XOpsProperties baseProperties() {
        XOpsProperties props = new XOpsProperties();
        props.setDiskCacheDir(tempDir.toString());
        props.setOauthClientId("test-client-id");
        props.setOauthClientSecret("test-client-secret");
        props.setTokenEncryptionSecret("unit-test-encryption-secret-32b");
        props.setHourlyUpstreamApiBudget(100);
        props.setDailyUpstreamApiBudget(500);
        props.setHourlyUpstreamUsdBudget("10.0");
        props.setDailyUpstreamUsdBudget("50.0");
        props.setMonthlyUpstreamUsdBudget("200.0");
        return props;
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
