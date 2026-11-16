package com.xops.hub.controller;

import com.xops.hub.config.RequestSecuritySupport;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.XAlgorithmProfile;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.service.XAlgorithmProfileRegistry;
import com.xops.hub.util.HandleNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/algo")
public class XAlgorithmController {

    public record AlgorithmProfileResponse(
            String source,
            String fingerprint,
            Instant loadedAt,
            boolean canManage,
            String overridePath,
            XAlgorithmProfile profile
    ) {
    }

    private final XOpsProperties properties;
    private final XAlgorithmProfileRegistry registry;

    public XAlgorithmController(XOpsProperties properties, XAlgorithmProfileRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @GetMapping("/profile")
    public AlgorithmProfileResponse getProfile(HttpServletRequest request, HttpServletResponse response) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        return toResponse(registry.currentState(), canManageAlgoProfile(request));
    }

    @PostMapping("/profile")
    public AlgorithmProfileResponse updateProfile(
            @RequestBody XAlgorithmProfile body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        ScheduledTweetController.verifySameOriginIfPresent(request);
        requireManagePermission(request);
        return toResponse(registry.saveOverride(body), true);
    }

    @PostMapping("/profile/reload")
    public AlgorithmProfileResponse reloadProfile(HttpServletRequest request, HttpServletResponse response) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        ScheduledTweetController.verifySameOriginIfPresent(request);
        requireManagePermission(request);
        return toResponse(registry.reloadFromDisk(), true);
    }

    @PostMapping("/profile/reset")
    public AlgorithmProfileResponse resetProfile(HttpServletRequest request, HttpServletResponse response) {
        CommunityApiController.applyPrivateNoStoreHeaders(response);
        ScheduledTweetController.verifySameOriginIfPresent(request);
        requireManagePermission(request);
        return toResponse(registry.resetToBuiltin(), true);
    }

    private void requireManagePermission(HttpServletRequest request) {
        if (!canManageAlgoProfile(request)) {
            throw new XDataProviderException(403, "仅管理员或本地调试模式可修改全局算法规则");
        }
    }

    private boolean canManageAlgoProfile(HttpServletRequest request) {
        HttpSession session = request != null ? request.getSession(false) : null;
        AuthenticatedXUser authUser = session != null
                ? (AuthenticatedXUser) session.getAttribute(XOAuthController.SESSION_AUTH_USER)
                : null;
        if (authUser != null
                && authUser.id() != null && !authUser.id().isBlank()
                && authUser.username() != null && !authUser.username().isBlank()) {
            String normalized = HandleNormalizer.normalize(authUser.username());
            if (CommunityHubService.AUTHOR_USERNAME.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        if (!properties.isRequireLoginSelfOnly() && request != null) {
            return RequestSecuritySupport.isLoopbackAddress(request.getRemoteAddr());
        }
        return false;
    }

    private static AlgorithmProfileResponse toResponse(XAlgorithmProfileRegistry.ProfileState state, boolean canManage) {
        return new AlgorithmProfileResponse(
                state.source(),
                state.fingerprint(),
                state.loadedAt(),
                canManage,
                canManage ? state.overridePath() : "",
                state.profile()
        );
    }
}
