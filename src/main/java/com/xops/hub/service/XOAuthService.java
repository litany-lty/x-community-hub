package com.xops.hub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.XDataProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X OAuth 2.0 Authorization Code + PKCE (S256) 登录验身服务：
 * 访客点击「使用 X 账号登录」后完成官方身份验证（仅消耗 1 次 users/me 读取），
 * 自动绑定其真实 @username 并配合 TwitterAPI.io 拉取全量运营数据。
 */
@Service
public class XOAuthService {

    private static final String AUTHORIZE_ENDPOINT = "https://x.com/i/oauth2/authorize";
    private static final int MAX_PENDING_STATES = 2000;

    public record AuthorizationRequestInfo(String authorizationUrl, String state) {
    }

    private record PkceChallengeState(String codeVerifier, String redirectUri, Instant expiresAt) {
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final SharedApiExecutor sharedExecutor;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, PkceChallengeState> pendingStates = new ConcurrentHashMap<>();

    public XOAuthService(XOpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new SharedApiExecutor(properties));
    }

    @Autowired
    public XOAuthService(XOpsProperties properties, ObjectMapper objectMapper, SharedApiExecutor sharedExecutor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sharedExecutor = sharedExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String resolveCallbackUrl(String baseOrigin) {
        if (properties.getOauthRedirectUri() != null && !properties.getOauthRedirectUri().isBlank()) {
            return properties.getOauthRedirectUri().trim();
        }
        String cleanOrigin = baseOrigin.endsWith("/") ? baseOrigin.substring(0, baseOrigin.length() - 1) : baseOrigin;
        return cleanOrigin + "/api/auth/x/callback";
    }

    public String buildAuthorizationUrl(String baseOrigin) {
        return createAuthorizationRequest(baseOrigin).authorizationUrl();
    }

    public synchronized AuthorizationRequestInfo createAuthorizationRequest(String baseOrigin) {
        if (!properties.hasOAuthClient()) {
            throw new XDataProviderException(400, "服务端尚未在 application.yml 中配置 xops.oauth-client-id");
        }
        cleanupExpiredStates();
        if (pendingStates.size() >= MAX_PENDING_STATES) {
            throw new XDataProviderException(503, "当前授权登录请求较多，请稍后重试");
        }

        String state = randomUrlSafe(24);
        String codeVerifier = randomUrlSafe(48);
        String codeChallenge = sha256Base64Url(codeVerifier);
        String redirectUri = resolveCallbackUrl(baseOrigin);

        pendingStates.put(state, new PkceChallengeState(
                codeVerifier,
                redirectUri,
                Instant.now().plus(Duration.ofMinutes(10))
        ));

        String scope = "users.read tweet.read";
        String url = AUTHORIZE_ENDPOINT
                + "?response_type=code"
                + "&client_id=" + urlEnc(properties.getOauthClientId().trim())
                + "&redirect_uri=" + urlEnc(redirectUri)
                + "&scope=" + urlEnc(scope)
                + "&state=" + urlEnc(state)
                + "&code_challenge=" + urlEnc(codeChallenge)
                + "&code_challenge_method=S256";
        return new AuthorizationRequestInfo(url, state);
    }

    public AuthenticatedXUser handleCallback(String code, String state) {
        if (state == null || state.isBlank()) {
            throw new XDataProviderException(400, "缺少 OAuth state 参数，登录校验失败");
        }
        PkceChallengeState saved = pendingStates.remove(state);
        if (saved == null || Instant.now().isAfter(saved.expiresAt())) {
            throw new XDataProviderException(400, "登录会话已过期或 state 不匹配，请重新发起 X 授权登录");
        }
        if (code == null || code.isBlank()) {
            throw new XDataProviderException(400, "未获取到 X 授权码 code");
        }

        String accessToken = exchangeCodeForToken(code, saved.codeVerifier(), saved.redirectUri());
        return fetchCurrentXUser(accessToken);
    }

    private String exchangeCodeForToken(String code, String codeVerifier, String redirectUri) {
        String clientId = properties.getOauthClientId().trim();
        String clientSecret = properties.getOauthClientSecret() != null
                ? properties.getOauthClientSecret().trim()
                : "";

        StringBuilder form = new StringBuilder();
        form.append("code=").append(urlEnc(code));
        form.append("&grant_type=authorization_code");
        form.append("&redirect_uri=").append(urlEnc(redirectUri));
        form.append("&code_verifier=").append(urlEnc(codeVerifier));

        String baseUrl = properties.getOfficialBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.x.com/2";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/oauth2/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");

        if (!clientSecret.isEmpty()) {
            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)
            );
            reqBuilder.header("Authorization", "Basic " + basic);
        } else {
            form.append("&client_id=").append(urlEnc(clientId));
        }

        try {
            HttpRequest tokenReq = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
            HttpResponse<String> resp = sharedExecutor.execute(() ->
                    httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString())
            );
            JsonNode json;
            try {
                json = objectMapper.readTree(resp.body());
            } catch (Exception parseEx) {
                throw new XDataProviderException(502, "X OAuth 网关返回非 JSON 响应（HTTP " + resp.statusCode() + "），请稍后重试");
            }
            if (resp.statusCode() >= 400 || json.path("access_token").asText("").isBlank()) {
                String errDesc = json.path("error_description").asText(json.path("error").asText("HTTP " + resp.statusCode()));
                throw new XDataProviderException(401, "换取 X OAuth Token 失败：" + errDesc);
            }
            return json.path("access_token").asText();
        } catch (XDataProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new XDataProviderException(502, "请求 X OAuth 令牌接口异常：" + e.getMessage(), e);
        }
    }

    private AuthenticatedXUser fetchCurrentXUser(String userAccessToken) {
        String baseUrl = properties.getOfficialBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.x.com/2";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/users/me?user.fields=profile_image_url,verified";
        Exception lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + userAccessToken)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> resp = sharedExecutor.execute(() ->
                        httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                );
                if (resp.statusCode() >= 500 && attempt == 0) {
                    Thread.sleep(350L);
                    continue;
                }
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode data = root.path("data");
                if (resp.statusCode() >= 400 || data.isMissingNode() || data.path("username").asText("").isBlank()) {
                    throw new XDataProviderException(401, "获取已授权 X 账号资料失败（HTTP " + resp.statusCode() + "，请确认开发者后台账户状态正常）");
                }
                String avatar = data.path("profile_image_url").asText("").replace("_normal.", "_400x400.");
                return new AuthenticatedXUser(
                        data.path("id").asText(""),
                        data.path("username").asText(""),
                        data.path("name").asText(data.path("username").asText("")),
                        avatar,
                        data.path("verified").asBoolean(false),
                        Instant.now()
                );
            } catch (XDataProviderException e) {
                throw e;
            } catch (Exception e) {
                lastErr = e;
                if (attempt == 0) {
                    try {
                        Thread.sleep(350L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new XDataProviderException(502, "查询 X 当前登录用户失败：" + (lastErr != null ? lastErr.getMessage() : "未知网络异常"), lastErr);
    }

    private void cleanupExpiredStates() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, PkceChallengeState>> it = pendingStates.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isAfter(it.next().getValue().expiresAt())) {
                it.remove();
            }
        }
    }

    private String randomUrlSafe(int numBytes) {
        byte[] buf = new byte[numBytes];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Base64Url(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String urlEnc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
