package com.xops.hub.config;

import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.provider.XDataProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestBoundaryHardeningTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void forwardedIdentityOnlyUsesLocalProxyAndCanonicalIp() {
        var publicRequest = request("GET", "/api/meta", "203.0.113.9");
        publicRequest.addHeader("CF-Connecting-IP", "127.0.0.1");
        publicRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(RequestSecuritySupport.extractClientIp(publicRequest)).isEqualTo("203.0.113.9");
        var privateClient = request("GET", "/api/meta", "10.1.2.3");
        privateClient.addHeader("X-Forwarded-For", "203.0.113.1");
        assertThat(RequestSecuritySupport.extractClientIp(privateClient)).isEqualTo("10.1.2.3");
        var proxy = request("GET", "/api/meta", "127.0.0.1");
        proxy.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.8");
        proxy.addHeader("CF-Connecting-IP", "127.0.0.1");
        assertThat(RequestSecuritySupport.extractClientIp(proxy)).isEqualTo("203.0.113.8");
        proxy.removeHeader("X-Forwarded-For");
        proxy.addHeader("X-Forwarded-For", "999.1.2.3");
        assertThat(RequestSecuritySupport.extractClientIp(proxy)).isEqualTo("127.0.0.1");
        var a = request("GET", "/api/meta", "2001:db8::1");
        var b = request("GET", "/api/meta", "2001:0db8:0:0:0:0:0:1");
        assertThat(RequestSecuritySupport.extractClientIp(a)).isEqualTo(RequestSecuritySupport.extractClientIp(b));
    }

    @Test
    void sameSiteWithoutSourceIsRejectedAndIpv6SameOriginWorks() {
        var siblingSite = request("POST", "/api/usage/checkin", "203.0.113.1");
        siblingSite.addHeader("Sec-Fetch-Site", "same-site");
        assertThatThrownBy(() -> RequestSecuritySupport.verifySameOriginForMutation(siblingSite))
                .isInstanceOf(XDataProviderException.class);
        var local = request("POST", "/api/usage/checkin", "::1");
        local.setServerName("::1");
        local.setServerPort(8080);
        local.addHeader("Origin", "http://[::1]:8080");
        assertThatCode(() -> RequestSecuritySupport.verifySameOriginForMutation(local)).doesNotThrowAnyException();
    }

    @Test
    void crossSiteMultipartStopsBeforeParsingAndOAuthCallbackStillWorks() throws Exception {
        var shield = shield(new XOpsProperties());
        var upload = request("POST", "/api/scheduled-tweets", "203.0.113.2");
        upload.setContentType("multipart/form-data; boundary=test");
        upload.addHeader("Origin", "https://untrusted.example");
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        shield.doFilter(upload, response, (req, res) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(reached).isFalse();
        var callback = request("GET", "/api/auth/x/callback", "203.0.113.3");
        callback.setQueryString("code=opaque..value&state=a");
        callback.addHeader("Sec-Fetch-Site", "cross-site");
        response = new MockHttpServletResponse();
        shield.doFilter(callback, response, (req, res) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reached).isTrue();
    }

    @Test
    void encodedTraversalAndScannerPathsAreBlockedWithoutTreatingQueryDotsAsTraversal() throws Exception {
        assertThat(MaliciousRequestShieldFilter.containsPathTraversal("/%252e%252e/private")).isTrue();
        assertThat(MaliciousRequestShieldFilter.containsPathTraversal("/safe..name")).isFalse();
        var shield = shield(new XOpsProperties());
        var scanner = request("GET", "/%2egit/config", "203.0.113.4");
        var response = new MockHttpServletResponse();
        shield.doFilter(scanner, response, (req, res) -> { throw new AssertionError("scanner reached application"); });
        assertThat(response.getStatus()).isEqualTo(404);
        var encodedApi = request("POST", "/%61pi/usage/checkin", "203.0.113.5");
        encodedApi.setServletPath("/api/usage/checkin");
        encodedApi.addHeader("Origin", "https://untrusted.example");
        response = new MockHttpServletResponse();
        shield.doFilter(encodedApi, response, (req, res) -> { throw new AssertionError("encoded API bypassed CSRF"); });
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void paidReadsRejectCrossSiteNavigationAndForeignRefererButAcceptSameOriginAndCli() throws Exception {
        var shield = shield(new XOpsProperties());
        for (String path : new String[] {"/api/analyze", "/api/analyze/bundle"}) {
            var navigation = request("GET", path, "203.0.113.7");
            navigation.addHeader("Sec-Fetch-Site", "cross-site");
            navigation.addHeader("Sec-Fetch-Mode", "navigate");
            var rejectedNavigation = new MockHttpServletResponse();
            shield.doFilter(navigation, rejectedNavigation,
                    (req, res) -> { throw new AssertionError("cross-site navigation reached paid read"); });
            assertThat(rejectedNavigation.getStatus()).isEqualTo(403);

            var foreignReferer = request("GET", path, "203.0.113.7");
            foreignReferer.addHeader("Referer", "https://untrusted.example/article");
            var rejectedReferer = new MockHttpServletResponse();
            shield.doFilter(foreignReferer, rejectedReferer,
                    (req, res) -> { throw new AssertionError("foreign referer reached paid read"); });
            assertThat(rejectedReferer.getStatus()).isEqualTo(403);

            var sameOrigin = request("GET", path, "203.0.113.7");
            sameOrigin.setScheme("https");
            sameOrigin.setServerName("x.life-benchmark.com");
            sameOrigin.setServerPort(443);
            sameOrigin.addHeader("Sec-Fetch-Site", "same-origin");
            sameOrigin.addHeader("Origin", "https://x.life-benchmark.com");
            AtomicBoolean called = new AtomicBoolean();
            var allowed = new MockHttpServletResponse();
            shield.doFilter(sameOrigin, allowed, (req, res) -> called.set(true));
            assertThat(allowed.getStatus()).isEqualTo(200);
            assertThat(called.get()).isTrue();
            assertAllowed(shield, path, "203.0.113.7");

            var head = request("HEAD", path, "203.0.113.7");
            head.addHeader("Sec-Fetch-Site", "cross-site");
            var rejectedHead = new MockHttpServletResponse();
            shield.doFilter(head, rejectedHead,
                    (req, res) -> { throw new AssertionError("HEAD bypassed paid-read protection"); });
            assertThat(rejectedHead.getStatus()).isEqualTo(403);
        }
    }

    @Test
    void streamedBodyCannotBypassSizeLimitWithUnknownLength() throws Exception {
        var shield = shield(new XOpsProperties());
        var request = new MockHttpServletRequest("POST", "/api/usage/checkin") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setRemoteAddr("203.0.113.6");
        request.setContentType("application/json");
        request.setContent(new byte[300 * 1024]);
        var response = new MockHttpServletResponse();
        shield.doFilter(request, response, (req, res) -> req.getInputStream().readAllBytes());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void fiftyUsersBehindOneNatCanLoginAndPollBeforeExactFloodBoundary() throws Exception {
        XOpsProperties props = new XOpsProperties();
        props.setIpAuthLimitPerMinute(150);
        props.setIpApiFloodLimitPerMinute(6000);
        var shield = shield(props);
        for (int i = 0; i < 50; i++) {
            assertAllowed(shield, "/api/auth/x/login", "203.0.113.20");
            assertAllowed(shield, "/api/auth/x/callback", "203.0.113.20");
        }
        for (int i = 0; i < 1300; i++) assertAllowed(shield, "/api/scheduled-tweets", "203.0.113.20");
        for (int i = 1400; i < 6000; i++) assertAllowed(shield, "/api/meta", "203.0.113.20");
        var blocked = new MockHttpServletResponse();
        shield.doFilter(request("GET", "/api/meta", "203.0.113.20"), blocked,
                (req, res) -> { throw new AssertionError("flood reached application"); });
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("FLOOD_REJECTED");
    }

    @Test
    void authRequestsAllowConfiguredBoundaryThenRejectExcess() throws Exception {
        XOpsProperties props = new XOpsProperties();
        props.setIpAuthLimitPerMinute(150);
        var shield = shield(props);
        for (int i = 0; i < 150; i++) assertAllowed(shield, "/api/auth/x/login", "203.0.113.21");
        var blocked = new MockHttpServletResponse();
        shield.doFilter(request("GET", "/api/auth/x/login", "203.0.113.21"), blocked, (req, res) -> {});
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("AUTH_RATE_LIMITED");
    }

    @Test
    void identityChurnCannotClearExistingCountersOrGrowMapsWithoutBound() throws Exception {
        XOpsProperties props = new XOpsProperties();
        props.setIpApiFloodLimitPerMinute(60);
        var shield = shield(props);
        for (int i = 0; i < 60; i++) assertAllowed(shield, "/api/meta", "203.0.113.30");
        for (int i = 0; i < 4200; i++) {
            String ip = "198.18." + (i / 256) + "." + (i % 256);
            shield.doFilter(request("GET", "/api/meta", ip), new MockHttpServletResponse(), (req, res) -> {});
            shield.doFilter(request("GET", "/.git/config", ip), new MockHttpServletResponse(), (req, res) -> {});
        }
        var blocked = new MockHttpServletResponse();
        shield.doFilter(request("GET", "/api/meta", "203.0.113.30"), blocked, (req, res) -> {});
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(shield, "ipApiCounters")).hasSizeLessThanOrEqualTo(4096);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(shield, "ipMaliceBox")).hasSizeLessThanOrEqualTo(4096);
    }

    @Test
    void metaInfWebInfAndNonApiPostsAreStoppedAtShieldBoundary() throws Exception {
        var shield = shield(new XOpsProperties());
        for (String probe : new String[] {
                "/s/6373e28/_/;/META-INF/maven/com.atlassian.jira/pom.xml",
                "/WEB-INF/web.xml",
                "/%2fmeta-inf/MANIFEST.MF"
        }) {
            var res = new MockHttpServletResponse();
            shield.doFilter(request("GET", probe, "203.0.113.40"), res,
                    (req, r) -> { throw new AssertionError("META-INF/WEB-INF probe bypassed shield: " + probe); });
            assertThat(res.getStatus()).isEqualTo(404);
        }

        var postRoot = new MockHttpServletResponse();
        var postReq = request("POST", "/", "203.0.113.41");
        postReq.setServletPath("/");
        shield.doFilter(postReq, postRoot,
                (req, r) -> { throw new AssertionError("POST / reached static resource handler"); });
        assertThat(postRoot.getStatus()).isEqualTo(405);
        assertThat(postRoot.getHeader("Allow")).isEqualTo("GET, HEAD, OPTIONS");
    }

    @Test
    void apiExceptionHandlerHandlesRestrictiveAcceptAndClientDisconnectCleanly() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExceptionProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/probe/not-found").accept(MediaType.valueOf("image/webp")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(get("/probe/boom").accept(MediaType.valueOf("text/css")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));

        mvc.perform(get("/probe/broken-pipe").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @RestController
    static class ExceptionProbeController {
        @GetMapping("/probe/not-found")
        String notFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing.webp");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("unexpected failure");
        }

        @GetMapping("/probe/broken-pipe")
        String brokenPipe() throws IOException {
            throw new IOException("ServletOutputStream failed to write: java.io.IOException: Broken pipe");
        }
    }

    private static MaliciousRequestShieldFilter shield(XOpsProperties props) {
        return new MaliciousRequestShieldFilter(props, FIXED_CLOCK);
    }

    private static void assertAllowed(MaliciousRequestShieldFilter filter, String path, String ip) throws Exception {
        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request("GET", path, ip), response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(called).isTrue();
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }
}
