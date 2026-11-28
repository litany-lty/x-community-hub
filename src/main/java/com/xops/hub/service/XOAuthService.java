package com.xops.hub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.OfficialProxySupport;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.store.XOAuthTokenStore;
import com.xops.hub.store.XOAuthTokenStore.StoredOAuthToken;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X OAuth 2.0 Authorization Code + PKCE (S256) 登录与按需二次授权服务：
 * 1. 默认登录仅申请最小只读范围（users.read tweet.read），无需额外发推授权即可查看全部运营报表；
 * 2. 使用定时发推与逾期补发时，再通过独立二次授权补充 tweet.write media.write offline.access，
 *    并借助 refresh_token 自动轮换尽可能延长授权有效时间。
 */
@Service
public class XOAuthService {

    public static final String LOGIN_OAUTH_SCOPE = "users.read tweet.read";
    public static final String PUBLISH_OAUTH_SCOPE = "users.read tweet.read tweet.write media.write offline.access";
    public static final String DEFAULT_OAUTH_SCOPE = LOGIN_OAUTH_SCOPE;
    private static final String REJECTED_GRANT_MESSAGE = "X 发推授权已过期或失效，请前往「定时发布」页完成二次授权：";
    private static final String AUTHORIZE_ENDPOINT = "https://x.com/i/oauth2/authorize";
    private static final int MAX_PENDING_STATES = 2000;
    private static final Duration PKCE_STATE_TTL = Duration.ofMinutes(15);
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 90L;

    public enum AuthPurpose {
        LOGIN,
        PUBLISH
    }

    public record AuthorizationRequestInfo(String authorizationUrl, String state) {
    }

    public record TokenCapability(boolean writeAuthorized, boolean mediaWriteAuthorized, boolean offlineRefreshable) {
    }

    public record CallbackResult(
            AuthenticatedXUser user,
            AuthPurpose purpose,
            boolean writeAuthorized,
            boolean mediaWriteAuthorized,
            boolean offlineRefreshable
    ) {
    }

    private record TokenGrant(String accessToken, String refreshToken, long expiresInSec, String scope) {
    }

    private record TokenEndpointResponse(int statusCode, JsonNode body) {
    }

    private record PkceChallengeState(String codeVerifier, String redirectUri, Instant expiresAt) {
    }

    private record PendingAuthMeta(AuthPurpose purpose, String expectedAccountId, Instant expiresAt) {
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final SharedApiExecutor sharedExecutor;
    private final UpstreamBudgetGuard budgetGuard;
    private final XOAuthTokenStore tokenStore;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, PkceChallengeState> pendingStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingAuthMeta> pendingAuthMeta = new ConcurrentHashMap<>();

    private static final int LOCK_STRIPES = 64;
    private final Object[] accountLocks;

    public XOAuthService(XOpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new SharedApiExecutor(properties), new UpstreamBudgetGuard(properties));
    }

    public XOAuthService(XOpsProperties properties, ObjectMapper objectMapper, SharedApiExecutor sharedExecutor) {
        this(properties, objectMapper, sharedExecutor, new UpstreamBudgetGuard(properties));
    }

    @Autowired
    public XOAuthService(
            XOpsProperties properties,
            ObjectMapper objectMapper,
            SharedApiExecutor sharedExecutor,
            @Autowired(required = false) UpstreamBudgetGuard budgetGuard
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sharedExecutor = sharedExecutor;
        this.budgetGuard = budgetGuard != null ? budgetGuard : new UpstreamBudgetGuard(properties);
        this.tokenStore = new XOAuthTokenStore(properties, objectMapper);
        this.httpClient = OfficialProxySupport.buildHttpClient(this.properties, Duration.ofSeconds(10));
        this.accountLocks = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.accountLocks[i] = new Object();
        }
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
        return createAuthorizationRequest(baseOrigin, AuthPurpose.LOGIN, null, LOGIN_OAUTH_SCOPE);
    }

    public synchronized AuthorizationRequestInfo createPublishAuthorizationRequest(
            String baseOrigin,
            String expectedAccountId
    ) {
        if (expectedAccountId == null || expectedAccountId.isBlank()) {
            throw new XDataProviderException(401, "请先使用 X 账号登录后再发起定时发布二次授权");
        }
        return createAuthorizationRequest(baseOrigin, AuthPurpose.PUBLISH, expectedAccountId.trim(), PUBLISH_OAUTH_SCOPE);
    }

    private AuthorizationRequestInfo createAuthorizationRequest(
            String baseOrigin,
            AuthPurpose purpose,
            String expectedAccountId,
            String scope
    ) {
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
        Instant expiresAt = Instant.now().plus(PKCE_STATE_TTL);

        pendingStates.put(state, new PkceChallengeState(
                codeVerifier,
                redirectUri,
                expiresAt
        ));
        pendingAuthMeta.put(state, new PendingAuthMeta(
                purpose,
                expectedAccountId,
                expiresAt
        ));

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

    public void discardPendingState(String state) {
        if (state == null || state.isBlank()) {
            return;
        }
        pendingStates.remove(state);
        pendingAuthMeta.remove(state);
    }

    public AuthenticatedXUser handleCallback(String code, String state) {
        return handleCallbackResult(code, state).user();
    }

    public CallbackResult handleCallbackResult(String code, String state) {
        if (state == null || state.isBlank()) {
            throw new XDataProviderException(400, "缺少 OAuth state 参数，登录校验失败");
        }
        PkceChallengeState saved = pendingStates.remove(state);
        PendingAuthMeta meta = pendingAuthMeta.remove(state);
        if (saved == null || Instant.now().isAfter(saved.expiresAt())) {
            throw new XDataProviderException(400, "登录会话已过期或 state 不匹配，请重新发起 X 授权");
        }
        if (code == null || code.isBlank()) {
            throw new XDataProviderException(400, "未获取到 X 授权码 code");
        }

        AuthPurpose purpose = meta != null ? meta.purpose() : AuthPurpose.LOGIN;
        String expectedAccountId = meta != null ? meta.expectedAccountId() : null;

        TokenGrant grant = exchangeCodeForTokenGrant(code, saved.codeVerifier(), saved.redirectUri());
        AuthenticatedXUser user = fetchCurrentXUser(grant.accessToken());
        if (purpose == AuthPurpose.PUBLISH
                && expectedAccountId != null
                && !expectedAccountId.isBlank()
                && !expectedAccountId.equals(user.id())) {
            throw new XDataProviderException(
                    403,
                    "二次授权的 X 账号（@" + user.username() + "）与当前已登录账号不一致，请切换回原 X 账号后重试"
            );
        }

        persistTokenGrant(user, grant, purpose);
        TokenCapability capability = getTokenCapability(user.id());
        return new CallbackResult(
                user,
                purpose,
                capability.writeAuthorized(),
                capability.mediaWriteAuthorized(),
                capability.offlineRefreshable()
        );
    }

    public TokenCapability getTokenCapability(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return new TokenCapability(false, false, false);
        }
        synchronized (lockForAccount(accountId)) {
            StoredOAuthToken token = tokenStore.load(accountId);
            if (token == null) {
                return new TokenCapability(false, false, false);
            }
            long nowSec = Instant.now().getEpochSecond();
            boolean accessTokenUnexpired = token.accessToken() != null
                    && !token.accessToken().isBlank()
                    && nowSec + TOKEN_REFRESH_MARGIN_SECONDS < token.expiresAtEpochSec();
            boolean usable = token.hasRefreshToken() || accessTokenUnexpired;
            return new TokenCapability(
                    usable && token.hasWriteScope(),
                    usable && token.hasMediaWriteScope(),
                    token.hasRefreshToken()
            );
        }
    }

    public String getValidWriteAccessToken(String accountId, boolean requireMediaWrite) {
        if (accountId == null || accountId.isBlank()) {
            throw new XDataProviderException(401, "缺少账号 ID，请重新使用 X 授权登录");
        }
        synchronized (lockForAccount(accountId)) {
            StoredOAuthToken token = tokenStore.load(accountId);
            if (token == null) {
                throw new XDataProviderException(403, "当前账号尚未开通定时发布二次授权，请前往「定时发布」页点击「前往 X 二次授权」");
            }
            if (!token.hasWriteScope()) {
                throw new XDataProviderException(403, "当前账号尚未授予发推权限（tweet.write），请前往「定时发布」页完成二次授权");
            }
            if (requireMediaWrite && !token.hasMediaWriteScope()) {
                throw new XDataProviderException(403, "当前账号尚未授予图片上传权限（media.write），请前往「定时发布」页完成二次授权");
            }
            long nowSec = Instant.now().getEpochSecond();
            if (token.accessToken() != null && !token.accessToken().isBlank()
                    && nowSec + TOKEN_REFRESH_MARGIN_SECONDS < token.expiresAtEpochSec()) {
                return token.accessToken();
            }
            if (!token.hasRefreshToken()) {
                throw new XDataProviderException(401, "X 发推访问令牌已过期且未获得离线刷新权限（offline.access），请前往「定时发布」页完成二次授权");
            }
            if (!properties.hasOAuthClient()) {
                throw new XDataProviderException(400, "服务端尚未配置 xops.oauth-client-id，无法刷新令牌");
            }

            TokenGrant refreshed = exchangeRefreshToken(accountId, token.refreshToken());
            String nextRefresh = refreshed.refreshToken().isBlank() ? token.refreshToken() : refreshed.refreshToken();
            String nextScope = refreshed.scope().isBlank() ? token.scope() : refreshed.scope();
            long expiresIn = refreshed.expiresInSec() > 0 ? refreshed.expiresInSec() : 7200L;
            StoredOAuthToken updated = new StoredOAuthToken(
                    token.accountId(),
                    token.username(),
                    refreshed.accessToken(),
                    nextRefresh,
                    Instant.now().getEpochSecond() + expiresIn,
                    nextScope,
                    Instant.now().getEpochSecond()
            );
            tokenStore.save(updated);
            if (!updated.hasWriteScope() || (requireMediaWrite && !updated.hasMediaWriteScope())) {
                throw new XDataProviderException(403, "X 授权范围已变更，请重新完成定时发布二次授权");
            }
            return updated.accessToken();
        }
    }

    public String invalidateRejectedWriteToken(String accountId, String rejectedAccessToken) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        synchronized (lockForAccount(accountId)) {
            StoredOAuthToken token = tokenStore.load(accountId);
            if (token == null) {
                return "X 发推授权已过期或失效，请前往「定时发布」页完成二次授权：HTTP 401";
            }
            if (rejectedAccessToken != null && !rejectedAccessToken.isBlank()
                    && token.accessToken() != null && !rejectedAccessToken.equals(token.accessToken())) {
                return null;
            }
            if (!token.hasRefreshToken()) {
                tokenStore.delete(accountId);
                return "X 发推访问令牌已过期且未获得离线刷新权限（offline.access），请前往「定时发布」页完成二次授权";
            }
            if (!properties.hasOAuthClient()) {
                tokenStore.delete(accountId);
                return "X 发推授权已过期或失效，请前往「定时发布」页完成二次授权：HTTP 401";
            }
            try {
                TokenGrant refreshed = exchangeRefreshToken(accountId, token.refreshToken());
                String nextRefresh = refreshed.refreshToken().isBlank() ? token.refreshToken() : refreshed.refreshToken();
                String nextScope = refreshed.scope().isBlank() ? token.scope() : refreshed.scope();
                long expiresIn = refreshed.expiresInSec() > 0 ? refreshed.expiresInSec() : 7200L;
                long nowSec = Instant.now().getEpochSecond();
                tokenStore.save(new StoredOAuthToken(
                        token.accountId(),
                        token.username(),
                        refreshed.accessToken(),
                        nextRefresh,
                        nowSec + expiresIn,
                        nextScope,
                        nowSec
                ));
                return null;
            } catch (XDataProviderException ex) {
                return ex.getMessage();
            }
        }
    }

    static boolean isRejectedGrantMessage(String message) {
        return message != null && message.startsWith(REJECTED_GRANT_MESSAGE);
    }

    public void evictStaleTokenBefore(String accountId, long authFailureEpochMs) {
        if (accountId == null || accountId.isBlank() || authFailureEpochMs <= 0L) {
            return;
        }
        long failureEpochSec = authFailureEpochMs / 1000L;
        synchronized (lockForAccount(accountId)) {
            StoredOAuthToken token = tokenStore.load(accountId);
            if (token != null && token.updatedAtEpochSec() < failureEpochSec
                    && token.expiresAtEpochSec() <= Instant.now().getEpochSecond()) {
                tokenStore.delete(accountId);
            }
        }
    }

    private void persistTokenGrant(AuthenticatedXUser user, TokenGrant grant, AuthPurpose purpose) {
        if (user == null || user.id() == null || user.id().isBlank()) {
            return;
        }
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        String effectiveScope = !grant.scope().isBlank()
                ? grant.scope()
                : (purpose == AuthPurpose.PUBLISH ? PUBLISH_OAUTH_SCOPE : LOGIN_OAUTH_SCOPE);
        boolean grantHasWrite = hasScopeToken(effectiveScope, "tweet.write");

        synchronized (lockForAccount(user.id())) {
            StoredOAuthToken existing = tokenStore.load(user.id());
            if (grantHasWrite) {
                String refreshToken = !grant.refreshToken().isBlank()
                        ? grant.refreshToken()
                        : (existing != null && existing.refreshToken() != null ? existing.refreshToken() : "");
                long expiresIn = grant.expiresInSec() > 0 ? grant.expiresInSec() : 7200L;
                long nowSec = Instant.now().getEpochSecond();
                tokenStore.save(new StoredOAuthToken(
                        user.id(),
                        user.username(),
                        grant.accessToken(),
                        refreshToken,
                        nowSec + expiresIn,
                        effectiveScope,
                        nowSec
                ));
                return;
            }
            // 默认只读登录不得覆盖或降级已有的写权限与离线刷新令牌
            if (existing != null && existing.hasWriteScope() && !user.username().equals(existing.username())) {
                tokenStore.save(new StoredOAuthToken(
                        existing.accountId(),
                        user.username(),
                        existing.accessToken(),
                        existing.refreshToken(),
                        existing.expiresAtEpochSec(),
                        existing.scope(),
                        existing.updatedAtEpochSec()
                ));
            }
        }
    }

    private static boolean hasScopeToken(String scope, String target) {
        if (scope == null || scope.isBlank() || target == null || target.isBlank()) {
            return false;
        }
        for (String token : scope.trim().split("\\s+")) {
            if (target.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private Object lockForAccount(String accountId) {
        if (accountId == null) {
            return accountLocks[0];
        }
        return accountLocks[(accountId.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    private TokenGrant exchangeCodeForTokenGrant(String code, String codeVerifier, String redirectUri) {
        StringBuilder form = new StringBuilder();
        form.append("code=").append(urlEnc(code));
        form.append("&grant_type=authorization_code");
        form.append("&redirect_uri=").append(urlEnc(redirectUri));
        form.append("&code_verifier=").append(urlEnc(codeVerifier));

        TokenEndpointResponse resp = sendTokenFormRequest(form);
        JsonNode json = resp.body();
        if (resp.statusCode() >= 400 || json.path("access_token").asText("").isBlank()) {
            String errDesc = json.path("error_description").asText(json.path("error").asText("HTTP " + resp.statusCode()));
            throw new XDataProviderException(401, "换取 X OAuth Token 失败：" + errDesc);
        }
        return parseTokenGrant(json);
    }

    private TokenGrant exchangeRefreshToken(String accountId, String refreshToken) {
        StringBuilder form = new StringBuilder();
        form.append("grant_type=refresh_token");
        form.append("&refresh_token=").append(urlEnc(refreshToken));

        TokenEndpointResponse resp = sendTokenFormRequest(form);
        JsonNode json = resp.body();
        if (resp.statusCode() == 400 || resp.statusCode() == 401) {
            String errCode = json.path("error").asText("");
            String errDesc = json.path("error_description").asText(errCode.isBlank() ? "HTTP " + resp.statusCode() : errCode);
            if ("invalid_client".equalsIgnoreCase(errCode)) {
                throw new XDataProviderException(503, "X OAuth 客户端凭据被拒绝，请检查服务端配置");
            }
            tokenStore.delete(accountId);
            throw new XDataProviderException(401, REJECTED_GRANT_MESSAGE + errDesc);
        }
        if (resp.statusCode() >= 400 || json.path("access_token").asText("").isBlank()) {
            String errDesc = json.path("error_description").asText(json.path("error").asText("HTTP " + resp.statusCode()));
            throw new XDataProviderException(502, "刷新 X 访问令牌失败，请稍后重试（" + errDesc + "）");
        }
        return parseTokenGrant(json);
    }

    private static TokenGrant parseTokenGrant(JsonNode json) {
        long expiresIn = json.path("expires_in").asLong(7200L);
        if (expiresIn <= 0) {
            expiresIn = 7200L;
        }
        return new TokenGrant(
                json.path("access_token").asText(""),
                json.path("refresh_token").asText(""),
                expiresIn,
                json.path("scope").asText("")
        );
    }

    private TokenEndpointResponse sendTokenFormRequest(StringBuilder form) {
        String clientId = properties.getOauthClientId().trim();
        String clientSecret = properties.getOauthClientSecret() != null
                ? properties.getOauthClientSecret().trim()
                : "";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(resolveOfficialBaseUrl() + "/oauth2/token"))
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

        String formBody = form.toString();
        URI tokenUri = URI.create(resolveOfficialBaseUrl() + "/oauth2/token");
        boolean loopback = UpstreamBudgetGuard.isLocalLoopbackUri(tokenUri);
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(loopback ? 20L : 250L * attempt);
                }
                HttpRequest tokenReq = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
                HttpResponse<String> resp = sharedExecutor.execute(() -> {
                    budgetGuard.acquirePermit(tokenReq.uri(), "x_oauth_token");
                    return httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
                });
                int status = resp.statusCode();
                if ((status == 502 || status == 503 || status == 504) && attempt < 2) {
                    continue;
                }
                JsonNode json;
                try {
                    json = objectMapper.readTree(resp.body());
                } catch (Exception parseEx) {
                    if (status >= 500 && attempt < 2) {
                        continue;
                    }
                    throw new XDataProviderException(502, "X OAuth 网关返回非 JSON 响应（HTTP " + status + "），请稍后重试");
                }
                return new TokenEndpointResponse(status, json);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new XDataProviderException(503, "请求 X OAuth 令牌接口被中断", ie);
            } catch (XDataProviderException e) {
                throw e;
            } catch (Exception e) {
                if (e.getCause() instanceof java.net.ConnectException && attempt < 2) {
                    continue;
                }
                throw new XDataProviderException(502, "请求 X OAuth 令牌接口异常：" + e.getMessage(), e);
            }
        }
        throw new XDataProviderException(502, "请求 X OAuth 令牌接口暂不可用，请稍后重试");
    }

    private String resolveOfficialBaseUrl() {
        String baseUrl = properties.getOfficialBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.x.com/2";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private AuthenticatedXUser fetchCurrentXUser(String userAccessToken) {
        String url = resolveOfficialBaseUrl() + "/users/me?user.fields=profile_image_url,verified";
        URI meUri = URI.create(url);
        boolean loopback = UpstreamBudgetGuard.isLocalLoopbackUri(meUri);
        Exception lastErr = null;
        for (int attempt = 0; attempt <= com.xops.hub.provider.PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(loopback ? 20L : 300L * attempt);
                }
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(meUri)
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + userAccessToken)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> resp = sharedExecutor.execute(() -> {
                    budgetGuard.acquirePermit(req.uri(), "x_oauth_user_me");
                    return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                });
                int code = resp.statusCode();
                if ((code == 429 || code >= 500) && attempt < com.xops.hub.provider.PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode data = root.path("data");
                if (code >= 400 || data.isMissingNode() || data.path("id").asText("").isBlank()
                        || data.path("username").asText("").isBlank()) {
                    throw new XDataProviderException(401, "获取已授权 X 账号资料失败（HTTP " + code + "，请确认开发者后台账户状态正常）");
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
            Map.Entry<String, PkceChallengeState> entry = it.next();
            if (now.isAfter(entry.getValue().expiresAt())) {
                it.remove();
                pendingAuthMeta.remove(entry.getKey());
            }
        }
        Iterator<Map.Entry<String, PendingAuthMeta>> metaIt = pendingAuthMeta.entrySet().iterator();
        while (metaIt.hasNext()) {
            if (now.isAfter(metaIt.next().getValue().expiresAt())) {
                metaIt.remove();
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
