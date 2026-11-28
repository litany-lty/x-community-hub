(() => {
    "use strict";

    const THEMES = [
        { id: "lights-out", label: "纯黑" },
        { id: "dim", label: "暗蓝" },
        { id: "light", label: "白天" }
    ];

    const PERIOD_META = {
        MONTH: { code: "MONTH", days: 30, badgePrefix: "近 30 天" },
        WEEK: { code: "WEEK", days: 7, badgePrefix: "近 7 天" },
        HOURS_48: { code: "HOURS_48", days: 2, badgePrefix: "近 48 小时" },
        HOURS_24: { code: "HOURS_24", days: 1, badgePrefix: "近 24 小时" }
    };

    const AUTHOR_REPO_URL = "https://github.com/litany-lty/x-community-hub";
    const AUTHOR_ARCHIVE_ACCOUNT_ID = "2095392916734976000";
    const AUTHOR_BANNER_URL = "/author-banner.webp";
    const AUTHOR_AVATAR_URL = "/author-avatar.webp";
    const MEDIA_CACHE_NAME = "xops-media-v1";
    const MEDIA_CACHE_MAX_ENTRIES = 120;
    const PROFILE_HEADER_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
    const AUTHOR_DEFAULT_BIO = "Java Dev & AI | Studying Chinese Metaphysics(Dao·道) ䷊";
    const AUTHOR_COST_STORY =
        "我目前负债十几个、失业、每个月靠贷款交房租。确实承担不起每个月过多的API费用。现在希望通过X平台解决人生危机。也希望朋友们可以谅解。如果限制让你感到不爽，可以到git仓库看开源代码，本地部署与线上效果一致。";

    // X 风格线性图标（统一替代互动指标里的 emoji）
    const ICON_PATHS = {
        reply: "M1.75 10c0-4.42 3.58-8 8-8h4.37c4.48 0 7.88 3.58 7.88 8 0 4.42-3.58 8-8 8h-1.67L6.5 21.5v-3.84C3.68 16.38 1.75 13.4 1.75 10zm8-6c-3.31 0-6 2.69-6 6 0 2.35 1.36 4.39 3.33 5.37l.67.33v2.06L11.66 16H14c3.31 0 6-2.69 6-6s-2.6-6-5.88-6H9.75z",
        retweet: "M4.5 3.88l4.43 4.14-1.36 1.46L5.5 7.55V16c0 1.1.9 2 2 2H13v2H7.5c-2.21 0-4-1.79-4-4V7.55L1.43 9.48.07 8.02 4.5 3.88zM16.5 6H11V4h5.5c2.21 0 4 1.79 4 4v8.45l2.07-1.93 1.36 1.46-4.43 4.14-4.43-4.14 1.36-1.46 2.07 1.93V8c0-1.1-.9-2-2-2z",
        like: "M16.7 5.5c-1.29-.05-2.66.62-3.89 2.36l-.81 1.14-.82-1.13C9.94 6.13 8.56 5.45 7.28 5.5c-1.3.06-2.48.79-3.12 2.09-.64 1.31-.68 3.11.25 5.18.91 2.02 2.78 4.13 5.59 6.04L12 20.3l1.99-1.49c2.81-1.91 4.69-4.02 5.6-6.04.93-2.07.89-3.87.25-5.18-.64-1.3-1.82-2.03-3.14-2.09zM12 5.37c1.42-1.47 3.07-2.18 4.79-1.87 1.88.33 3.4 1.5 4.21 3.15.83 1.69.8 3.88-.27 6.23-1.04 2.31-3.12 4.6-6.11 6.62L12 21.9l-2.62-1.96c-2.99-2.02-5.07-4.31-6.11-6.62-1.07-2.35-1.1-4.54-.27-6.23.81-1.65 2.33-2.82 4.21-3.15 1.72-.31 3.37.4 4.79 1.43z",
        bookmark: "M4 4.5C4 3.12 5.12 2 6.5 2h11C18.88 2 20 3.12 20 4.5v18.44l-8-5.71-8 5.71V4.5zM6.5 4c-.28 0-.5.22-.5.5v14.56l6-4.29 6 4.29V4.5c0-.28-.22-.5-.5-.5h-11z",
        views: "M8.75 21V3h2v18h-2zM18 21V8.5h2V21h-2zM4 21l.004-10h2L6 21H4zm9.248 0v-7h2v7h-2z"
    };
    const iconSvg = (name) => `<svg class="metric-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON_PATHS[name]}"/></svg>`;

    const LIST_PAGE_SIZE = 50;
    const LIST_LOAD_STEP = 80;
    const URL_TOKEN_RE = /https?:\/\/[A-Za-z0-9._~:/?#[\]@!$&'()*+,;=%-]+/gi;

    const state = {
        period: "HOURS_24",
        storedMode: false,
        days: 1,
        themeIdx: 0,
        activeTab: "activity",
        serverMeta: null,
        authStatus: null,
        authConnectionState: "idle",
        authConnectionError: "",
        authStatusRequest: null,
        report: null,
        bundle: null,
        dailyCacheStatus: null,
        backgroundRefreshStatus: null,
        accessPolicy: null,
        coveredWindowDays: 0,
        pendingExpandCheck: false,
        pendingRelationshipChecks: 0,
        isFetching: false,
        isSilentRefreshing: false,
        isLoggingOut: false,
        reportRequestGeneration: 0,
        reportAbortController: null,
        silentReportAbortController: null,
        authStatusGeneration: 0,
        userScopeGeneration: 0,
        cacheExpiresEpochMs: 0,
        cooldownTimerId: null,
        // 模块 2：推友互动筛选与增量分页状态
        selectedPeerUsername: null,
        peerRelFilter: "MUTUAL",
        peerSearchQuery: "",
        peerSortBy: "intimacy",
        peerRenderLimit: LIST_PAGE_SIZE,
        // 模块 3：未回关筛选、增量分页与本地白名单/已打开状态
        followStateTab: "NOT_FOLLOWING_BACK",
        unfollowCategory: "ALL",
        followSearchQuery: "",
        followSortBy: "recent",
        followRenderLimit: LIST_PAGE_SIZE,
        whitelistSet: new Set(),
        tabScrollPositions: {},
        // 模块 4：爆款代表推文多维排序与体裁过滤状态
        topPostSortBy: "composite",
        topPostFormat: "ALL",
        assistantAccess: null,
        assistantDashboard: null,
        assistantView: "assistant",
        assistantGenerationTargetPinned: false,
        assistantSelectedTopicId: "",
        assistantExternalTopicId: "",
        assistantTopicSearch: "",
        assistantTopicStatus: "",
        assistantTopicAuthor: "",
        assistantTopicTag: "",
        assistantTopicSort: "score",
        assistantTopicOffset: 0,
        assistantRequestedTopicOffset: 0,
        assistantTopicHasNext: false,
        assistantMaterialsFeed: null,
        assistantMaterialSearch: "",
        assistantMaterialAuthor: "",
        assistantMaterialStatus: "",
        assistantMaterialSort: "published",
        assistantMaterialOffset: 0,
        assistantMaterialHasNext: false,
        assistantReviewTag: "",
        assistantReviewScoreBand: "",
        assistantReviewDraftVersion: "",
        assistantReviewSkill: "",
        assistantSelectedDraftByTopic: {},
        assistantSelectedSkill: "AUTO",
        assistantCreationAngle: "",
        assistantOrganizeMode: false,
        assistantLoading: false,
        assistantMutation: null,
        assistantDraftDirty: false,
        assistantDraftSaveTimerId: null,
        assistantDraftSave: null,
        assistantDraftConflict: null,
        assistantTaskTimerId: null,
        assistantRequestGeneration: 0,
        backgroundRefreshTimerId: null,
        // 模块 5：战报卡隐私指标切换与 CORS 安全头像缓存
        shareHideFollowRate: false,
        avatarImgCache: {},
        // 使用次数与作者引导弹层状态
        usageCount: 0,
        authorFollowingMarked: false,
        boundUserId: "",
        // 模块 6：图文定时发布与逾期二次确认状态
        scheduleJobs: [],
        schedulePendingCount: 0,
        scheduleOverdueCount: 0,
        scheduleFilter: "ALL",
        scheduleDraftFiles: [],
        scheduleDraftItems: [],
        activeScheduleDraftIdx: 0,
        mediaPickerTargetIdx: 0,
        scheduleDraftRestoring: false,
        scheduleDraftRestore: null,
        scheduleDraftPersistenceReady: false,
        composerClientRequestId: "",
        schedulePollTimerId: null,
        scheduleRequestGeneration: 0,
        scheduleRetryNotBeforeEpochMs: 0,
        scheduleAbortController: null,
        scheduleMutations: new Set(),
        watchedResendIds: new Set(),
        pendingConfirmJob: null,
        rescheduleEditingJobId: null,
        rescheduleDraftValue: null,
        rescheduleError: "",
        rescheduleErrorKind: "",
        rescheduleInteractionVersion: 0,
        scheduleRenderedSignature: "",
        scheduleServerTz: "Asia/Shanghai",
        scheduleAccess: null,
        scheduleHasLoaded: false,
        scheduleListError: "",
        scheduleActionError: "",
        scheduleManualRefresh: false,
        scheduleFeedbackTimerId: null,
        scheduleComposerErrorDraftIdx: null,
        scheduleComposerErrorField: "",
        schedulePublishAuthError: "",
        algoProfileStatus: null,
        algoConfigOpen: false
    };

    function cancelReportRequest() {
        state.reportRequestGeneration++;
        if (state.reportAbortController) state.reportAbortController.abort();
        state.reportAbortController = null;
        state.isFetching = false;
        state.pendingExpandCheck = false;
        $("loadingBar").hidden = true;
        $("loadingSlowHint").hidden = true;
        $("authenticatedWorkspace").classList.remove("is-loading");
    }

    function syncReportVisibility() {
        document.querySelectorAll(".tab-panel").forEach((panel) => {
            panel.hidden = !state.report && panel.id !== "panel-schedule" && panel.id !== "panel-timing";
        });
        document.querySelectorAll("[data-content-report]").forEach((section) => {
            section.hidden = !state.report;
        });
        $("authenticatedRightRail").hidden = !isLoggedIn() || !state.report;
    }

    function resetUserScopedMemoryState() {
        cancelReportRequest();
        state.authStatusGeneration++;
        state.userScopeGeneration++;
        state.scheduleRequestGeneration++;
        state.scheduleRetryNotBeforeEpochMs = 0;
        clearTimeout(state.scheduleFeedbackTimerId);
        state.scheduleFeedbackTimerId = null;
        state.scheduleHasLoaded = false;
        state.scheduleListError = "";
        state.scheduleActionError = "";
        state.scheduleManualRefresh = false;
        state.scheduleComposerErrorDraftIdx = null;
        state.scheduleComposerErrorField = "";
        state.schedulePublishAuthError = "";
        for (const id of ["scheduleComposeError", "schedulePublishAuthError", "scheduleQueueFeedback"]) {
            const el = document.getElementById(id);
            if (el) { el.textContent = ""; el.hidden = true; }
        }
        if (state.scheduleAbortController) state.scheduleAbortController.abort();
        state.scheduleAbortController = null;
        state.scheduleMutations = new Set();
        clearInterval(state.cooldownTimerId);
        clearTimeout(state.backgroundRefreshTimerId);
        state.backgroundRefreshTimerId = null;
        state.report = null;
        state.bundle = null;
        state.dailyCacheStatus = null;
        state.backgroundRefreshStatus = null;
        state.accessPolicy = null;
        state.coveredWindowDays = 0;
        state.pendingRelationshipChecks = 0;
        state.pendingExpandCheck = false;
        state.cacheExpiresEpochMs = 0;
        state.isFetching = false;
        if (state.silentReportAbortController) state.silentReportAbortController.abort();
        state.silentReportAbortController = null;
        state.isSilentRefreshing = false;
        state.selectedPeerUsername = null;
        state.peerRelFilter = "MUTUAL";
        state.peerFilterTouched = false;
        state.peerSearchQuery = "";
        state.followSearchQuery = "";
        state.peerRenderLimit = LIST_PAGE_SIZE;
        state.followRenderLimit = LIST_PAGE_SIZE;
        state.whitelistSet = new Set();
        state.tabScrollPositions = {};
        clearTimeout(state.assistantTaskTimerId);
        state.assistantTaskTimerId = null;
        state.assistantAccess = null;
        state.assistantDashboard = null;
        if (typeof repostPreviewState !== "undefined") {
            if (document.getElementById("repostPreviewModal")?.hidden === false) closeRepostPreviewModal(true);
            else repostPreviewState.requestToken++;
            repostPreviewState.owner = null;
            repostPreviewState.source = null;
            repostPreviewState.items = [];
            repostPreviewState.publishingIds = new Set();
            repostPreviewState.editedComments.clear();
            repostPreviewState.editedRewrites.clear();
            document.getElementById("repostPreviewTimelineList")?.replaceChildren();
        }
        state.assistantMaterialsFeed = null;
        state.assistantSelectedTopicId = "";
        state.assistantExternalTopicId = "";
        state.assistantTopicSearch = "";
        state.assistantTopicStatus = "";
        state.assistantTopicAuthor = "";
        state.assistantTopicTag = "";
        state.assistantTopicSort = "score";
        state.assistantMaterialSearch = "";
        state.assistantMaterialAuthor = "";
        state.assistantMaterialStatus = "";
        state.assistantMaterialSort = "published";
        state.assistantMaterialOffset = 0;
        state.assistantMaterialHasNext = false;
        state.assistantReviewTag = "";
        state.assistantReviewScoreBand = "";
        state.assistantReviewDraftVersion = "";
        state.assistantReviewSkill = "";
        if ($("assistantTopicSearch")) $("assistantTopicSearch").value = "";
        if ($("assistantTopicStatus")) $("assistantTopicStatus").value = "";
        if ($("assistantTopicAuthor")) $("assistantTopicAuthor").value = "";
        if ($("assistantTopicTag")) $("assistantTopicTag").value = "";
        if ($("assistantTopicSort")) $("assistantTopicSort").value = "score";
        if ($("assistantMaterialSearch")) $("assistantMaterialSearch").value = "";
        if ($("assistantMaterialAuthor")) $("assistantMaterialAuthor").value = "";
        if ($("assistantMaterialStatus")) $("assistantMaterialStatus").value = "";
        if ($("assistantMaterialSort")) $("assistantMaterialSort").value = "published";
        if ($("assistantReviewTag")) $("assistantReviewTag").value = "";
        if ($("assistantReviewScoreBand")) $("assistantReviewScoreBand").value = "";
        if ($("assistantReviewDraftVersion")) $("assistantReviewDraftVersion").value = "";
        if ($("assistantReviewSkill")) $("assistantReviewSkill").value = "";
        state.assistantTopicOffset = 0;
        state.assistantRequestedTopicOffset = 0;
        state.assistantTopicHasNext = false;
        state.assistantSelectedDraftByTopic = {};
        state.assistantCreationAngle = "";
        if ($("assistantCreationAngleInput")) $("assistantCreationAngleInput").value = "";
        if ($("assistantLinkResearchInput")) $("assistantLinkResearchInput").value = "";
        state.assistantOrganizeMode = false;
        state.assistantLoading = false;
        state.assistantMutation = null;
        state.assistantGenerationTargetPinned = false;
        clearTimeout(state.assistantDraftSaveTimerId);
        state.assistantDraftSaveTimerId = null;
        state.assistantDraftSave = null;
        state.assistantDraftConflict = null;
        state.assistantDraftDirty = false;
        state.assistantRequestGeneration++;
        if ($("assistantSettingsForm")?.dataset) {
            delete $("assistantSettingsForm").dataset.version;
            delete $("assistantSettingsForm").dataset.editRevision;
        }
        if ($("assistantAdvancedSettings")?.dataset) {
            delete $("assistantAdvancedSettings").dataset.userToggled;
        }
        for (const id of ["assistantTopicList", "assistantTopicDetail", "assistantMaterialsList", "assistantReviewList",
            "assistantTopicTagChips", "assistantReviewTagChips", "assistantSuggestedPeers",
            "assistantSourcesList", "assistantCapabilityStatus", "assistantStyleMeta", "assistantStyleProfile",
            "assistantStyleRevisionInput", "assistantPreferencesInput", "assistantStorageStatus",
            "assistantBudgetStatus", "assistantHistoryStatus"]) {
            const element = document.getElementById(id);
            if (element) {
                element.replaceChildren();
                if (id === "assistantPreferencesInput" || id === "assistantStyleRevisionInput") {
                    element.value = "";
                    delete element.dataset.version;
                    delete element.dataset.editRevision;
                }
            }
        }
        for (const id of ["scheduleDraftItemsList", "scheduledTweetsList", "scheduleTimingContext", "scheduleQuickSlotChips"]) {
            document.getElementById(id)?.replaceChildren();
        }
        const scheduleFileInput = document.getElementById("scheduleMediaFileInput");
        if (scheduleFileInput) scheduleFileInput.value = "";
        for (const image of Object.values(state.avatarImgCache || {})) {
            if (image && typeof image === "object") image.onload = image.onerror = null;
        }
        state.avatarImgCache = {};
        state.usageCount = 0;
        state.authorFollowingMarked = false;
        state.boundUserId = "";
        clearInterval(state.schedulePollTimerId);
        state.schedulePollTimerId = null;
        for (const draft of state.scheduleDraftItems || []) {
            for (const media of (draft && draft.files) || []) {
                if (media && media.previewUrl && typeof URL !== "undefined" && URL.revokeObjectURL) {
                    try { URL.revokeObjectURL(media.previewUrl); } catch (_) {}
                }
            }
        }
        state.scheduleJobs = [];
        state.schedulePendingCount = 0;
        state.scheduleOverdueCount = 0;
        state.scheduleFilter = "ALL";
        state.scheduleDraftFiles = [];
        state.scheduleDraftItems = [];
        state.activeScheduleDraftIdx = 0;
        state.mediaPickerTargetIdx = 0;
        state.scheduleDraftRestoring = false;
        state.scheduleDraftRestore = null;
        state.scheduleDraftPersistenceReady = false;
        cancelScheduleDraftPersistence();
        state.composerClientRequestId = "";
        state.watchedResendIds = new Set();
        state.pendingConfirmJob = null;
        state.rescheduleEditingJobId = null;
        state.rescheduleDraftValue = null;
        state.rescheduleError = "";
        state.rescheduleErrorKind = "";
        state.rescheduleInteractionVersion = 0;
        state.scheduleRenderedSignature = "";
        state.scheduleAccess = null;
        for (const id of ["scheduleTimingContext", "scheduleQuickSlotChips"]) {
            const element = document.getElementById(id);
            if (element) element.innerHTML = "";
        }
        const canvas = document.getElementById("shareCardCanvas");
        if (canvas) canvas.width = canvas.width;
        syncReportVisibility();
    }

    // 已精简移除的元素返回游离占位节点，避免旧渲染逻辑写入时报错
    const detachedStubs = {};
    const $ = (id) => document.getElementById(id) || (detachedStubs[id] ||= document.createElement("div"));

    function debounce(fn, waitMs = 150) {
        let timer = null;
        return (...args) => {
            clearTimeout(timer);
            timer = setTimeout(() => fn(...args), waitMs);
        };
    }

    function stableUserId(user) {
        return String(user?.id || "").trim();
    }

    function captureUserScope() {
        const generation = state.userScopeGeneration;
        const userId = stableUserId(state.authStatus?.user);
        return () => Boolean(userId) && generation === state.userScopeGeneration &&
            !state.isLoggingOut && isLoggedIn() && stableUserId(state.authStatus?.user) === userId;
    }

    function beginScheduleMutation(key) {
        if (!isLoggedIn() || state.isLoggingOut || state.scheduleMutations.has(key)) return null;
        if (key.startsWith("job:") && state.scheduleMutations.has("batch-resend")) return null;
        if (key === "batch-resend" && [...state.scheduleMutations].some((item) => item.startsWith("job:"))) return null;
        const mutations = state.scheduleMutations;
        mutations.add(key);
        return { isCurrent: captureUserScope(), finish: () => mutations.delete(key) };
    }

    function getSelfStorageKey(prefix) {
        const id = stableUserId(state.authStatus?.user);
        if (!id) throw new Error("无法确认本地偏好的账号归属");
        return `${prefix}_user_${id}`;
    }

    function loadFollowLocalMarks() {
        try {
            const rawWl = localStorage.getItem(getSelfStorageKey("xops_wl"));
            const parsedWl = rawWl ? JSON.parse(rawWl) : [];
            state.whitelistSet = new Set(Array.isArray(parsedWl) ? parsedWl.map((s) => String(s).toLowerCase()) : []);
        } catch (_) {
            state.whitelistSet = new Set();
        }
    }

    function saveWhitelistSet() {
        try {
            localStorage.setItem(getSelfStorageKey("xops_wl"), JSON.stringify([...state.whitelistSet]));
        } catch (_) {}
    }

    // 报表数据只缓存在服务端（5 分钟），浏览器不再持久保存；清理旧版本遗留在浏览器里的报表缓存
    function purgeLegacyClientBundles() {
        [localStorage, sessionStorage].forEach((store) => {
            try {
                Object.keys(store)
                    .filter((k) => k.startsWith("xops_bundle_"))
                    .forEach((k) => store.removeItem(k));
            } catch (_) {}
        });
    }

    const mediaObjectUrlMap = new Map();
    const mediaWarmInFlight = new Map();

    function getProfileHeaderCacheKey(userId) {
        const cleanId = String(userId || "").trim();
        return cleanId ? `xops_profile_meta_user_${cleanId}` : "";
    }

    function saveProfileHeaderCache(userId, profile, personaTag) {
        const key = getProfileHeaderCacheKey(userId);
        if (!key || !profile) return;
        try {
            const payload = {
                id: String(userId).trim(),
                username: String(profile.username || "").trim(),
                displayName: String(profile.displayName || profile.username || "").trim(),
                avatarUrl: safeAvatarSrc(profile.avatarUrl),
                bannerUrl: safeAvatarSrc(profile.bannerUrl),
                bio: String(profile.bio || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim(),
                verified: Boolean(profile.verified),
                followingCount: Number(profile.followingCount || 0),
                followersCount: Number(profile.followersCount || 0),
                totalPostsCount: Number(profile.totalPostsCount || 0),
                personaTag: String(personaTag || "").trim(),
                savedAt: Date.now()
            };
            localStorage.setItem(key, JSON.stringify(payload));
            localStorage.setItem("xops_last_uid", payload.id);
        } catch (_) {}
    }

    function loadProfileHeaderCache(userId) {
        const key = getProfileHeaderCacheKey(userId);
        if (!key) return null;
        try {
            const raw = localStorage.getItem(key);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (!parsed || parsed.id !== String(userId).trim()) return null;
            if (Date.now() - Number(parsed.savedAt || 0) > PROFILE_HEADER_CACHE_TTL_MS) {
                localStorage.removeItem(key);
                return null;
            }
            return parsed;
        } catch (_) {
            return null;
        }
    }

    function clearProfileHeaderCache(userId) {
        const key = getProfileHeaderCacheKey(userId);
        try {
            if (key) localStorage.removeItem(key);
            localStorage.removeItem("xops_last_uid");
        } catch (_) {}
    }

    function canPersistMediaToCacheStorage(canonical) {
        if (!canonical) return false;
        if (canonical.startsWith("/")) return true;
        return /^https:\/\/(?:pbs|abs)\.twimg\.com\//i.test(canonical);
    }

    async function trimMediaCacheOverflow(cache) {
        try {
            const keys = await cache.keys();
            if (keys.length > MEDIA_CACHE_MAX_ENTRIES) {
                const excess = keys.slice(0, keys.length - MEDIA_CACHE_MAX_ENTRIES);
                await Promise.allSettled(excess.map((req) => cache.delete(req)));
            }
        } catch (_) {}
    }

    function rememberMediaObjectUrl(canonical, blob) {
        if (mediaObjectUrlMap.has(canonical)) return mediaObjectUrlMap.get(canonical);
        const objectUrl = URL.createObjectURL(blob);
        mediaObjectUrlMap.set(canonical, objectUrl);
        while (mediaObjectUrlMap.size > MEDIA_CACHE_MAX_ENTRIES) {
            const oldest = mediaObjectUrlMap.keys().next().value;
            URL.revokeObjectURL(mediaObjectUrlMap.get(oldest));
            mediaObjectUrlMap.delete(oldest);
        }
        return objectUrl;
    }

    async function readCacheStorageMedia(rawUrl) {
        const canonical = safeAvatarSrc(rawUrl);
        if (!canonical) return "";
        if (mediaObjectUrlMap.has(canonical)) {
            return mediaObjectUrlMap.get(canonical);
        }
        if (typeof window === "undefined" || !("caches" in window) || typeof URL === "undefined" || !URL.createObjectURL) {
            return "";
        }
        try {
            const cache = await window.caches.open(MEDIA_CACHE_NAME);
            const resp = await cache.match(canonical);
            if (!resp) return "";
            const blob = await resp.blob();
            if (!blob || blob.size === 0) return "";
            return rememberMediaObjectUrl(canonical, blob);
        } catch (_) {
            return "";
        }
    }

    function warmMediaAsset(rawUrl) {
        const canonical = safeAvatarSrc(rawUrl);
        if (!canonical || !canPersistMediaToCacheStorage(canonical)) return Promise.resolve(null);
        if (mediaObjectUrlMap.has(canonical)) {
            return Promise.resolve(mediaObjectUrlMap.get(canonical));
        }
        if (
            typeof window === "undefined" ||
            !("caches" in window) ||
            typeof fetch !== "function" ||
            typeof URL === "undefined" ||
            !URL.createObjectURL
        ) {
            return Promise.resolve(null);
        }
        if (mediaWarmInFlight.has(canonical)) {
            return mediaWarmInFlight.get(canonical);
        }
        const task = (async () => {
            try {
                const cache = await window.caches.open(MEDIA_CACHE_NAME);
                let resp = await cache.match(canonical);
                if (!resp) {
                    const isLocal = canonical.startsWith("/");
                    const netResp = await fetch(canonical, {
                        mode: isLocal ? "same-origin" : "cors",
                        credentials: isLocal ? "same-origin" : "omit",
                        cache: isLocal ? "default" : "reload",
                        referrerPolicy: "no-referrer"
                    });
                    const ct = netResp.headers.get("content-type") || "";
                    if (!netResp.ok || (ct && !/^image\//i.test(ct))) {
                        return null;
                    }
                    await cache.put(canonical, netResp.clone());
                    void trimMediaCacheOverflow(cache);
                    resp = netResp;
                }
                const blob = await resp.blob();
                if (!blob || blob.size === 0) return null;
                return rememberMediaObjectUrl(canonical, blob);
            } catch (_) {
                return null;
            } finally {
                mediaWarmInFlight.delete(canonical);
            }
        })();
        mediaWarmInFlight.set(canonical, task);
        return task;
    }

    function resolveCachedMediaSrc(rawUrl, options = {}) {
        const canonical = safeAvatarSrc(rawUrl);
        if (!canonical) return "";
        const hit = mediaObjectUrlMap.get(canonical);
        if (hit) return hit;
        if (options.warm === true) {
            void warmMediaAsset(canonical);
        }
        return canonical;
    }

    async function primeInitialMediaCache() {
        try {
            const lastUid = localStorage.getItem("xops_last_uid");
            const cached = lastUid ? loadProfileHeaderCache(lastUid) : null;
            const urls = [cached?.avatarUrl, cached?.bannerUrl, AUTHOR_AVATAR_URL, AUTHOR_BANNER_URL].filter(Boolean);
            await Promise.allSettled(urls.map((u) => readCacheStorageMedia(u)));
        } catch (_) {}
    }

    function scheduleMediaBatchWarm(urls) {
        const queue = [...new Set((urls || []).map((u) => safeAvatarSrc(u)).filter(Boolean))]
            .filter((u) => canPersistMediaToCacheStorage(u) && !mediaObjectUrlMap.has(u));
        if (queue.length === 0) return;
        const run = async () => {
            const batchSize = 4;
            for (let i = 0; i < queue.length; i += batchSize) {
                await Promise.allSettled(queue.slice(i, i + batchSize).map((u) => warmMediaAsset(u)));
            }
        };
        if (typeof window !== "undefined" && typeof window.requestIdleCallback === "function") {
            window.requestIdleCallback(() => { void run(); }, { timeout: 1200 });
        } else {
            setTimeout(() => { void run(); }, 150);
        }
    }

    function normalizePeriod(rawPeriod, rawDays) {
        const up = String(rawPeriod || "").trim().toUpperCase();
        if (up === "MONTH" || up === "MONTHLY" || up === "30D" || up === "M") {
            return PERIOD_META.MONTH;
        }
        if (up === "WEEK" || up === "WEEKLY" || up === "7D" || up === "W") {
            return PERIOD_META.WEEK;
        }
        if (up === "HOURS_48" || up === "48H" || up === "2D") {
            return PERIOD_META.HOURS_48;
        }
        if (
            up === "HOURS_24" ||
            up === "24H" ||
            up === "HOURS_8" ||
            up === "8H" ||
            up === "DAY" ||
            up === "DAILY" ||
            up === "1D" ||
            up === "D"
        ) {
            return PERIOD_META.HOURS_24;
        }
        const dNum = Number(rawDays);
        if (dNum === 30) return PERIOD_META.MONTH;
        if (dNum === 7) return PERIOD_META.WEEK;
        if (dNum === 2) return PERIOD_META.HOURS_48;
        // 默认维度为 24 小时动态窗口
        return PERIOD_META.HOURS_24;
    }

    function isOwnerAllPermissions() {
        const auth = state.authStatus;
        return auth?.loggedIn === true && auth.ownerAllPermissions === true
            && Boolean(String(auth.user?.id || "").trim());
    }

    function isExplicitNonOwnerSession() {
        const auth = state.authStatus;
        if (!auth || !auth.loggedIn) return false;
        if (auth.ownerAllPermissions === false) return true;
        if (auth.ownerAllPermissions === true) return false;
        const uname = String(auth.user?.username || "").replace(/^@+/, "").trim().toLowerCase();
        if (uname) return uname !== "elidlicom";
        return auth.ownerAllPermissions === false;
    }

    function syncPaidUiVisibility() {
        if (typeof document === "undefined") return;
        const ownerAllowed = isOwnerAllPermissions();
        if (typeof document.querySelectorAll === "function") {
            document.querySelectorAll("[data-paid-entry]").forEach((el) => {
                el.hidden = !ownerAllowed;
            });
        }
        if (!ownerAllowed) {
            [
                "navUnfollowBadge",
                "navScheduleBadge",
                "mobileScheduleBadge",
                "overdueGlobalBanner",
                "relationshipContinueControls",
                "contentAssistantShell",
                "timingQuickNav",
                "scheduleShareCardBtn",
                "sendDraftToScheduleBtn"
            ].forEach((id) => {
                const el = typeof document.getElementById === "function" ? document.getElementById(id) : null;
                if (el) el.hidden = true;
            });
            if (state.activeTab === "follow-audit" || state.activeTab === "schedule") {
                switchTab("activity", { historyMode: "replace" });
            }
        }
    }

    function hasPeriodAccessDecision() {
        if (isOwnerAllPermissions()) return true;
        return Boolean(
            (state.accessPolicy && Array.isArray(state.accessPolicy.unlockedPeriods))
            || (state.storedMode && state.bundle)
        );
    }

    function isPeriodUnlocked(periodCode) {
        if (!periodCode || periodCode === "HOURS_24" || isOwnerAllPermissions()) return true;
        if (state.accessPolicy && Array.isArray(state.accessPolicy.unlockedPeriods)) {
            return state.accessPolicy.unlockedPeriods.includes(periodCode);
        }
        if (state.storedMode && state.bundle) {
            return Boolean(state.bundle[periodCode]);
        }
        return false;
    }

    function syncPeriodButtons() {
        const lockedReasons = (state.accessPolicy && state.accessPolicy.lockedReasons) || {};
        const hasPolicyOrBundle = hasPeriodAccessDecision();
        document.querySelectorAll(".seg-btn[data-period]").forEach((btn) => {
            if (!btn.dataset.defaultTitle && btn.title) {
                btn.dataset.defaultTitle = globalThis.XOpsI18n?.sourceAttribute(btn, "title") || btn.title;
            }
            const pCode = btn.dataset.period;
            const unlocked = isPeriodUnlocked(pCode);
            const knownLocked = hasPolicyOrBundle && !unlocked;
            const baseLabel = btn.dataset.label || btn.textContent.replace(/^🔒\s*/, "");
            btn.classList.toggle("active", pCode === state.period);
            btn.classList.toggle("is-locked", knownLocked);
            btn.setAttribute("aria-disabled", knownLocked ? "true" : "false");
            btn.textContent = baseLabel;
            btn.setAttribute("aria-label", knownLocked ? `${baseLabel}，未解锁` : baseLabel);
            btn.title = knownLocked && lockedReasons[pCode]
                ? lockedReasons[pCode]
                : (btn.dataset.defaultTitle || baseLabel);
        });
    }

    function escapeHtml(str) {
        if (str === null || str === undefined) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    const DEFAULT_ALGO_PROFILE = {
        version: "2026.11-phoenix-hm",
        name: "Phoenix + Home Mixer",
        updatedAt: "2026-11-15",
        sourceUrl: "https://github.com/xai-org/x-algorithm/tree/590f3926e5bc",
        changelog: [
            "对齐 xai-org/x-algorithm (commit 590f3926e5bc, param.rs 2026-11-12) 真实 vm_ranker / ValueModelWeights 参数（作用于个性化预测概率 P(action)，非原始互动计数乘数）",
            "接入 BidirectionalFollowReplyWeightBoost (+15.0)：互关账号的原创/引用帖 reply 预测权重由 5.0 提升至 20.0；评论排序同步纳入 focalAuthorReplySeq / favByRootAuthorSeq",
            "接入 OONRetweetReplyFilter 与 PostFusionMultiplier 精确公式：纯转推与回复在公域过滤丢弃、关注内按 0.75x 折算；同作者单批多帖按 (1-0.25)*0.5^k+0.25 衰减",
            "接入 Scarecrow 反垃圾规则证据（BBQDuplicateTextProd 重复文本 COPYPASTA_SPAM、bot/6754 低质外链+@非粉丝提及）与 vm-ranker DPP(theta=0.65) 语义去重；剔除主观文案词表猜测"
        ],
        upstreamEvidence: {
            upstreamCommit: "590f3926e5bc",
            paramSyncedAt: "2026-11-12T16:00:36Z",
            activeRankerService: "vm_ranker",
            shareViaCopyLinkWeight: 20.0,
            replyWeight: 5.0,
            bidirectionalFollowReplyWeightBoost: 15.0,
            bidirectionalFollowDwellWeightBoost: 0.0,
            quoteWeight: 5.0,
            shareViaDmWeight: 5.0,
            followAuthorWeight: 4.0,
            shareWeight: 2.0,
            retweetWeight: 1.0,
            favoriteWeight: 0.5,
            contClickDwellTimeWeight: 0.4,
            clickWeight: 0.3,
            openLinkWeight: 0.2,
            videoOpenWeight: 0.07,
            photoExpandWeight: 0.05,
            quotedClickWeight: 0.05,
            dwellWeight: 0.05,
            postUnexploredWeight: 0.02,
            contDwellTimeWeight: 0.004,
            vqvWeight: 0.0,
            profileClickWeight: 0.0,
            minVideoDurationMs: 10000,
            reportWeight: -234.0,
            muteAuthorWeight: -58.8,
            notInterestedWeight: -47.52,
            blockAuthorWeight: -31.2,
            notDwelledWeight: -0.02,
            maxCandidateAgeHours: 48,
            enableAuthorDiversity: true,
            authorDiversityDecay: 0.5,
            authorDiversityFloor: 0.25,
            oonWeightFactor: 0.75,
            topicOonWeightFactor: 0.5,
            oonRescoreInNetworkRepliesRetweets: true,
            enablePhoenixOonReplies: false,
            dppEnabled: true,
            dppTheta: 0.65,
            dppMaxSelectedRank: 150,
            coldStartEnabled: true,
            coldStartFollowerCap: 50000,
            coldStartImpressionThreshold: 200,
            coldStartMaxPostAgeSecs: 7200,
            coldStartSlotMin: 15,
            coldStartSlotMax: 16,
            underTheHoodMinAccountAgeDays: 365,
            underTheHoodMinPriorMonthPosts: 10,
            underTheHoodPublishLagDays: 10
        },
        cadenceAndReach: {
            minRecommendedGapMinutes: 180,
            severeRapidFireGapMinutes: 60,
            linkLossNoticePct: 15,
            oonFollowerMultiple: 1.0,
            lowReachFollowerRatio: 0.05
        },
        draftChecker: {
            baseScore: 100,
            minScore: 15,
            maxScore: 100,
            excellentThreshold: 88,
            goodThreshold: 70,
            leadingMentionOonFilterPenalty: 28,
            authorDiversityMaxPenalty: 26,
            queueCollisionPenalty: 12,
            exactDuplicateTextPenalty: 35,
            nearDuplicateTextPenalty: 18,
            duplicateSimilarityThreshold: 0.65,
            urlWithNonFollowerMentionPenalty: 18
        }
    };

    function getActiveAlgoProfile() {
        const remote = state.algoProfileStatus && state.algoProfileStatus.profile
            ? state.algoProfileStatus.profile
            : null;
        if (!remote) return DEFAULT_ALGO_PROFILE;
        return {
            ...DEFAULT_ALGO_PROFILE,
            ...remote,
            upstreamEvidence: {
                ...DEFAULT_ALGO_PROFILE.upstreamEvidence,
                ...(remote.upstreamEvidence || {})
            },
            cadenceAndReach: {
                ...DEFAULT_ALGO_PROFILE.cadenceAndReach,
                ...(remote.cadenceAndReach || {})
            },
            draftChecker: {
                ...DEFAULT_ALGO_PROFILE.draftChecker,
                ...(remote.draftChecker || {})
            }
        };
    }

    function escapeRegexChars(str) {
        return String(str || "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    }

    async function fetchAlgoProfileStatus() {
        try {
            const { resp, body } = await requestJsonWithin("/api/algo/profile", {
                credentials: "same-origin", cache: "no-store"
            }, 10000);
            if (!resp.ok) return null;
            if (body && body.profile) {
                state.algoProfileStatus = body;
                renderAlgoConfigDrawer();
                evaluatePrePostDraft();
            }
            return body;
        } catch (_) {
            return null;
        }
    }

    function openAlgoConfigDrawer(open) {
        state.algoConfigOpen = Boolean(open);
        const drawer = $("algoConfigDrawer");
        const btn = $("toggleAlgoConfigBtn");
        if (drawer) drawer.hidden = !state.algoConfigOpen;
        if (btn) {
            btn.setAttribute("aria-expanded", String(state.algoConfigOpen));
            btn.textContent = state.algoConfigOpen ? "收起配置 ▴" : "参数配置 ▾";
        }
        if (state.algoConfigOpen) {
            renderAlgoConfigDrawer();
        }
    }

    function renderAlgoConfigDrawer(forceOverwriteEditor = false) {
        const status = state.algoProfileStatus;
        const profile = getActiveAlgoProfile();
        const ah = state.report && state.report.algorithmHealth ? state.report.algorithmHealth : null;
        const verText = (ah && ah.algoVersion) ? ah.algoVersion : (profile.version || "Phoenix");
        const nameText = (ah && ah.algoName) ? ah.algoName : (profile.name || "Phoenix + Home Mixer");
        const isOverride = Boolean(status && status.source === "LOCAL_OVERRIDE");

        const badgeEl = $("algoProfileVersionBadge");
        if (badgeEl) {
            badgeEl.textContent = "自定义参数";
            badgeEl.hidden = !isOverride;
        }
        const sideBtn = $("sideAlgoVerBtn");
        if (sideBtn) {
            sideBtn.textContent = isOverride ? "自定义" : "参数";
            sideBtn.title = `当前参数：${nameText} (${verText})`;
        }

        const canManage = Boolean(status && status.canManage);
        const metaEl = $("algoConfigMetaLine");
        if (metaEl) {
            const srcLabel = status && status.source === "LOCAL_OVERRIDE" ? "本地热覆盖" : "内置基线";
            const fp = status && status.fingerprint ? status.fingerprint : "-";
            const updatedAt = profile.updatedAt || "-";
            const ev = profile.upstreamEvidence || DEFAULT_ALGO_PROFILE.upstreamEvidence;
            const rankerRaw = ev && ev.activeRankerService ? ev.activeRankerService : "vm_ranker";
            const rankerLabel = { vm_ranker: "主排序模型", heavy_ranker: "精排模型", light_ranker: "粗排模型" }[rankerRaw] || rankerRaw;
            const commitLabel = ev && ev.upstreamCommit ? ` · 上游基线 ${ev.upstreamCommit}（${rankerLabel}）` : "";
            const overridePath = status && status.overridePath ? status.overridePath : "";
            metaEl.textContent = `生效规则：${nameText} (${verText})${commitLabel} · ${updatedAt} · ${srcLabel} · 指纹 ${fp}${overridePath ? ` · ${overridePath}` : ""}${canManage ? "" : " · 只读"}`;
        }

        const logsEl = $("algoChangelogList");
        if (logsEl) {
            const logs = Array.isArray(profile.changelog) ? profile.changelog : [];
            logsEl.innerHTML = logs
                .map((line) => `<span class="algo-changelog-chip">${escapeHtml(line)}</span>`)
                .join("");
        }

        const actionRow = $("algoConfigActionRow");
        if (actionRow) actionRow.hidden = !canManage;

        const editorEl = $("algoProfileJsonInput");
        if (editorEl) {
            editorEl.readOnly = !canManage;
            if (forceOverwriteEditor || document.activeElement !== editorEl) {
                const rawObj = (status && status.profile) ? status.profile : profile;
                editorEl.value = JSON.stringify(rawObj, null, 2);
            }
        }
    }

    async function submitAlgoProfileAction(mode) {
        if (!state.algoProfileStatus || !state.algoProfileStatus.canManage) {
            showError("仅管理员或本地调试模式可修改全局算法规则");
            return;
        }
        const saveBtn = $("saveAlgoProfileBtn");
        const reloadBtn = $("reloadAlgoProfileBtn");
        const resetBtn = $("resetAlgoProfileBtn");
        const editorEl = $("algoProfileJsonInput");

        let url = "/api/algo/profile";
        let fetchOpts = { method: "POST", credentials: "same-origin", headers: {} };
        if (mode === "reload") {
            url = "/api/algo/profile/reload";
        } else if (mode === "reset") {
            url = "/api/algo/profile/reset";
        } else {
            let parsed;
            try {
                parsed = JSON.parse(editorEl ? editorEl.value : "");
            } catch (err) {
                showError(`JSON 语法有误：${err.message}`);
                return;
            }
            if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
                showError("算法配置必须是合法的 JSON 对象");
                return;
            }
            fetchOpts.headers["Content-Type"] = "application/json";
            fetchOpts.body = JSON.stringify(parsed);
        }

        [saveBtn, reloadBtn, resetBtn].forEach((b) => {
            if (b) b.disabled = true;
        });
        try {
            const resp = await fetch(url, fetchOpts);
            const body = await safeParseJson(resp);
            if (!resp.ok) {
                showError((body && body.message) || `操作失败（HTTP ${resp.status}）`);
                return;
            }
            if (body && body.profile) {
                state.algoProfileStatus = body;
                renderAlgoConfigDrawer(true);
                evaluatePrePostDraft();
            }
            const okMsg = mode === "reload"
                ? "已从磁盘重载最新算法规则"
                : mode === "reset"
                    ? "已还原内置基线算法规则"
                    : "算法规则已热生效，正在重算分析指标";
            showToast(okMsg);
            if (isLoggedIn()) {
                await loadSelfReport({ reloadFromServer: true });
            }
        } catch (err) {
            showError((err && err.message) || "请求算法配置接口失败，请稍后重试");
        } finally {
            [saveBtn, reloadBtn, resetBtn].forEach((b) => {
                if (b) b.disabled = false;
            });
        }
    }

    function fmtNum(n) {
        const val = Number(n || 0);
        if (val >= 1000000) return (val / 1000000).toFixed(1) + "M";
        if (val >= 10000) return (val / 10000).toFixed(1) + "万";
        return val.toLocaleString("zh-CN");
    }

    function hasMetricSample(item, field) {
        return item[field] === undefined || Number(item[field]) > 0;
    }

    function metricSampleLabel(item, field) {
        return item[field] === undefined ? "" : `（已知 ${item[field]} 帖）`;
    }

    function postMetricsKnown(post, fields) {
        return !fields.some((field) => (post.unavailableMetrics || []).includes(field));
    }

    function safeHttpUrl(rawUrl, fallback = "https://x.com") {
        const s = String(rawUrl || "").trim();
        return /^https?:\/\//i.test(s) ? s : fallback;
    }

    function safeAvatarSrc(rawUrl) {
        const s = String(rawUrl || "").trim();
        if (/^https?:\/\//i.test(s)) return s;
        if (/^\/[a-zA-Z0-9._/-]+$/.test(s)) return s;
        return "";
    }

    function applyCoverBanner(coverEl, rawBannerUrl) {
        if (!coverEl) return;
        const canonical = safeAvatarSrc(rawBannerUrl);
        const renderSrc = canonical ? resolveCachedMediaSrc(canonical, { warm: true }) : "";
        if (renderSrc && !/["'()\\\r\n]/.test(renderSrc)) {
            coverEl.style.backgroundImage =
                `linear-gradient(180deg, rgba(0, 0, 0, 0.36) 0%, rgba(0, 0, 0, 0.04) 45%, rgba(0, 0, 0, 0.22) 100%), url("${renderSrc}")`;
            coverEl.classList.add("has-banner");
        } else {
            coverEl.style.backgroundImage = "";
            coverEl.classList.remove("has-banner");
        }
    }

    function firstCodePointUpper(str) {
        const chars = Array.from(String(str || "").trim());
        return chars.length > 0 ? chars[0].toUpperCase() : "X";
    }

    async function safeParseJson(resp) {
        const raw = await resp.text();
        if (!raw) return {};
        try {
            return JSON.parse(raw);
        } catch (_) {
            return {
                message: resp.ok ? "服务返回数据格式异常，请稍后重试" : `服务响应异常（HTTP ${resp.status}）`
            };
        }
    }

    function fmtClockTime(isoStr, withSeconds = false) {
        if (!isoStr) return "";
        try {
            const d = new Date(isoStr);
            if (Number.isNaN(d.getTime())) return "";
            const tz =
                (state.report && state.report.contentTiming && state.report.contentTiming.timezone) ||
                "Asia/Shanghai";
            return d.toLocaleTimeString("zh-CN", {
                timeZone: tz,
                hour12: false,
                hour: "2-digit",
                minute: "2-digit",
                ...(withSeconds ? { second: "2-digit" } : {})
            });
        } catch (_) {
            return "";
        }
    }

    function fmtCountdown(sec) {
        const s = Math.max(0, Math.ceil(sec));
        const m = Math.floor(s / 60);
        const r = s % 60;
        return `${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}`;
    }

    function peerLatestPostsUrl(peerUsername) {
        const q = `from:${peerUsername} -filter:replies -filter:retweets`;
        return `https://x.com/search?q=${encodeURIComponent(q)}&f=live`;
    }

    function peerDialogueUrl(myUsername, peerUsername) {
        const q = `(from:${myUsername} to:${peerUsername}) OR (from:${peerUsername} to:${myUsername})`;
        return `https://x.com/search?q=${encodeURIComponent(q)}&f=live`;
    }

    function avatarHtml(user, sizeClass = "md") {
        const uname = user && user.username ? user.username : "X";
        const initial = firstCodePointUpper(user && user.displayName ? user.displayName : uname);
        const hue = Math.abs(hashStr(uname)) % 360;
        const bgStyle = `background: linear-gradient(135deg, hsl(${hue}, 76%, 48%), hsl(${(hue + 48) % 360}, 74%, 38%));`;
        const canonical = user && user.avatarUrl ? safeAvatarSrc(user.avatarUrl) : "";
        if (canonical) {
            const shouldWarm = sizeClass === "xl" || canonical.startsWith("/");
            const renderSrc = resolveCachedMediaSrc(canonical, { warm: shouldWarm });
            const loadAttr = sizeClass === "xl" ? 'fetchpriority="high"' : 'loading="lazy"';
            return `<div class="avatar-circle ${sizeClass}" data-i18n-ignore style="${bgStyle}">
                <img src="${escapeHtml(renderSrc)}" data-canonical-src="${escapeHtml(canonical)}" alt="${escapeHtml(uname)}" referrerpolicy="no-referrer" decoding="async" ${loadAttr}
                     data-avatar-fallback="${escapeHtml(initial)}" />
            </div>`;
        }
        return `<div class="avatar-circle ${sizeClass}" data-i18n-ignore style="${bgStyle}">${escapeHtml(initial)}</div>`;
    }

    function verifiedSvgHtml(verified) {
        if (!verified) return "";
        return `<span class="verified-badge" title="X 蓝标认证账号">
            <svg viewBox="0 0 22 22"><path d="M20.396 11c-.018-.646-.215-1.275-.57-1.816-.354-.54-.852-.972-1.438-1.246.223-.607.27-1.264.14-1.897-.131-.634-.437-1.218-.882-1.687-.47-.445-1.053-.75-1.687-.882-.633-.13-1.29-.083-1.897.14-.273-.587-.704-1.086-1.245-1.44S11.647 1.62 11 1.604c-.646.017-1.273.213-1.813.568s-.969.854-1.24 1.44c-.608-.223-1.267-.272-1.902-.14-.635.13-1.22.436-1.69.882-.445.47-.749 1.055-.878 1.688-.13.633-.08 1.29.144 1.896-.587.274-1.087.705-1.443 1.245-.356.54-.555 1.17-.574 1.817.02.647.218 1.276.574 1.817.356.54.856.972 1.443 1.245-.224.606-.274 1.263-.144 1.896.13.634.433 1.218.877 1.688.47.443 1.054.747 1.687.878.633.132 1.29.084 1.897-.136.274.586.705 1.084 1.246 1.439.54.354 1.17.551 1.816.569.647-.016 1.276-.213 1.817-.567s.972-.854 1.245-1.44c.604.239 1.266.296 1.903.164.636-.132 1.22-.447 1.68-.907.46-.46.776-1.044.908-1.681s.075-1.299-.165-1.903c.586-.274 1.084-.705 1.439-1.246.354-.54.551-1.17.569-1.816zM9.662 14.85l-3.429-3.428 1.293-1.302 2.072 2.072 4.4-4.794 1.347 1.246z"/></svg>
        </span>`;
    }

    function hashStr(s) {
        let h = 0;
        for (let i = 0; i < s.length; i++) {
            h = (h << 5) - h + s.charCodeAt(i);
            h |= 0;
        }
        return h;
    }

    function showToast(msg) {
        const el = $("copyToast");
        el.textContent = msg;
        el.hidden = false;
        clearTimeout(showToast._timer);
        showToast._timer = setTimeout(() => {
            el.hidden = true;
        }, 2400);
    }

    function isLoggedIn() {
        return Boolean(state.authStatus && state.authStatus.loggedIn && state.authStatus.user);
    }

    function switchTab(tabId, options = {}) {
        if (!["activity", "interactions", "follow-audit", "timing", "circle", "schedule"].includes(tabId)) return;
        const historyMode = options.historyMode || "push";
        if (!isLoggedIn()) {
            showToast("请先使用 X 账号授权登录");
            return;
        }
        if ((tabId === "follow-audit" || tabId === "schedule") && state.authStatus?.ownerAllPermissions !== true) {
            tabId = "activity";
        }
        if (state.activeTab === tabId && historyMode === "push") {
            window.scrollTo({ top: 0, behavior: "smooth" });
            return;
        }
        const previousTab = state.activeTab;
        if (previousTab !== tabId) state.tabScrollPositions[previousTab] = window.scrollY || 0;
        const ownerId = stableUserId(state.authStatus?.user);
        const top = Number.isFinite(options.scrollTop) ? options.scrollTop : state.tabScrollPositions[tabId] || 0;
        if (historyMode !== "none") {
            const url = new URL(window.location.href);
            if (historyMode === "push" && previousTab !== tabId) {
                window.history.replaceState({ ...window.history.state, xopsTab: previousTab,
                    xopsScrollY: window.scrollY || 0, xopsOwnerId: ownerId }, "", url.toString());
            }
            url.searchParams.set("tab", tabId);
            const entry = { ...window.history.state, xopsTab: tabId, xopsScrollY: top, xopsOwnerId: ownerId };
            if (historyMode === "push" && previousTab !== tabId) window.history.pushState(entry, "", url.toString());
            else window.history.replaceState(entry, "", url.toString());
        }
        state.activeTab = tabId;
        $("authenticatedWorkspace").dataset.activeTab = tabId;
        document.querySelectorAll(".nav-item[data-tab]").forEach((btn) => {
            const active = btn.dataset.tab === tabId;
            btn.classList.toggle("active", active);
            if (active) {
                btn.setAttribute("aria-current", "page");
            } else {
                btn.removeAttribute("aria-current");
            }
        });
        document.querySelectorAll(".bottom-tab[data-tab]").forEach((btn) => {
            const active = btn.dataset.tab === tabId;
            btn.classList.toggle("active", active);
            if (active) {
                btn.setAttribute("aria-current", "page");
            } else {
                btn.removeAttribute("aria-current");
            }
        });
        document.querySelectorAll(".tab-panel").forEach((panel) => {
            panel.classList.toggle("active", panel.id === `panel-${tabId}`);
        });
        if (tabId === "timing") {
            if (state.report) renderAlgoPoolAndSimulator(state.report);
            void loadContentAssistant(true);
        }
        if (tabId === "circle" && state.report) {
            drawShareCardCanvas(state.report);
        }
        if (tabId === "schedule") {
            ensureScheduleDraftInitialized();
            renderScheduleQuickSlots();
            syncScheduleComposerStats();
            loadScheduledTweets(true);
        } else {
            refreshSchedulePollingState();
        }
        window.scrollTo({ top, behavior: "auto" });
    }

    function applyTheme(idx) {
        state.themeIdx = ((idx % THEMES.length) + THEMES.length) % THEMES.length;
        const theme = THEMES[state.themeIdx];
        document.documentElement.setAttribute("data-theme", theme.id);
        const metaTheme = document.getElementById("metaThemeColor");
        if (metaTheme) {
            metaTheme.setAttribute(
                "content",
                theme.id === "light" ? "#ffffff" : theme.id === "dim" ? "#15202b" : "#000000"
            );
        }
        const nextTheme = THEMES[(state.themeIdx + 1) % THEMES.length];
        const tip = `切换主题（当前：${theme.label}，点按切到${nextTheme.label}）`;
        ["cycleThemeBtn", "topbarThemeBtn", "gateThemeBtn"].forEach((id) => {
            const btn = document.getElementById(id);
            if (!btn) return;
            btn.title = tip;
            btn.setAttribute("aria-label", tip);
            const labelEl = btn.querySelector(".theme-btn-text");
            if (labelEl) {
                labelEl.textContent = theme.label;
            }
        });
        try {
            localStorage.setItem("xops_theme", theme.id);
        } catch (_) {}
        if (state.report) {
            drawShareCardCanvas(state.report);
        }
    }

    async function requestJsonWithin(url, options = {}, timeoutMs = 15000) {
        const controller = new AbortController();
        let timeoutId;
        const deadline = new Promise((_, reject) => {
            timeoutId = setTimeout(() => {
                controller.abort();
                const error = new Error("请求等待超时");
                error.name = "RequestTimeoutError";
                reject(error);
            }, timeoutMs);
        });
        try {
            return await Promise.race([
                (async () => {
                    const resp = await fetch(url, { ...options, signal: controller.signal });
                    const body = await safeParseJson(resp);
                    return { resp, body };
                })(),
                deadline
            ]);
        } catch (err) {
            const raw = String(err?.message || "").trim();
            if (
                /^(?:Failed to fetch|Load failed|NetworkError when attempting to fetch resource\.?|fetch failed)$/i.test(raw) ||
                (err?.name === "TypeError" && /fetch|network/i.test(raw))
            ) {
                const netErr = new Error("当前网络连接异常或处于离线状态，请检查网络后重试。");
                netErr.name = "NetworkOfflineError";
                throw netErr;
            }
            throw err;
        } finally {
            clearTimeout(timeoutId);
        }
    }

    function isPreExecutionGatewayRejection(err) {
        return err?.httpStatus === 503
            && (err.errorCode === "SERVER_BUSY" || err.errorCode === "REQUEST_INTERRUPTED");
    }

    async function startColdWorkspaceRefresh(period, targetDays, isCurrent) {
        const query = `?period=${encodeURIComponent(period)}&targetDays=${encodeURIComponent(targetDays)}`;
        const headers = { "X-Requested-With": "XMLHttpRequest" };
        let result = await requestJsonWithin("/api/analyze/bundle/refresh" + query, {
            method: "POST", cache: "no-store", headers
        }, 15000);
        if (!result.resp.ok) throw new Error(result.body.message || result.body.error || "后台刷新受理失败");
        if (isCurrent && !isCurrent()) return null;
        state.backgroundRefreshStatus = result.body || null;
        return state.backgroundRefreshStatus;
    }

    function scheduleWorkspaceRefreshPoll(username, period, targetDays) {
        clearTimeout(state.backgroundRefreshTimerId);
        state.backgroundRefreshTimerId = null;
        const expectedUserId = stableUserId(state.authStatus?.user);
        if (!expectedUserId) return;
        const generation = state.reportRequestGeneration;
        const isCurrentPoll = () => isLoggedIn() && !state.isLoggingOut &&
            stableUserId(state.authStatus?.user) === expectedUserId && generation === state.reportRequestGeneration;
        const pollOnce = async () => {
            if (!isCurrentPoll()) return;
            try {
                const { resp, body } = await requestJsonWithin("/api/analyze/bundle/refresh/status", { cache: "no-store" }, 15000);
                if (!isCurrentPoll()) return;
                if (resp.ok && body) {
                    state.backgroundRefreshStatus = body;
                    if (state.report) renderDailyCacheStatus(state.report);
                    if (body.state === "RUNNING") {
                        state.backgroundRefreshTimerId = setTimeout(pollOnce, 2000);
                        return;
                    }
                    if (["SUCCEEDED", "PARTIAL", "CACHED"].includes(body.state)) {
                        if (state.isFetching || state.isSilentRefreshing) {
                            state.backgroundRefreshTimerId = setTimeout(pollOnce, 2000);
                        } else {
                            void loadSelfReport({ reloadFromServer: true, silent: true, completedRefresh: body });
                        }
                    }
                }
            } catch (_) {
                if (isCurrentPoll()) {
                    state.backgroundRefreshTimerId = setTimeout(pollOnce, 4000);
                }
            }
        };
        state.backgroundRefreshTimerId = setTimeout(pollOnce, 2000);
    }

    function syncAuthConnectionUI() {
        const button = document.getElementById("gateLoginBtn");
        const loading = state.authConnectionState === "idle" || state.authConnectionState === "loading";
        if (button) {
            button.disabled = loading;
            button.setAttribute("aria-busy", String(loading));
            const label = button.querySelector("span");
            if (label) label.textContent = loading ? "正在连接…"
                : state.authConnectionState === "error" ? "重试连接" : "使用 X 账号授权登录";
        }
        const feedback = document.getElementById("authGateFeedback");
        if (feedback) {
            feedback.textContent = state.authConnectionError || "";
            feedback.hidden = !state.authConnectionError;
        }
    }

    async function fetchServerAndAuthStatus() {
        if (state.authStatusRequest?.generation === state.authStatusGeneration) {
            return state.authStatusRequest.promise;
        }
        const request = { generation: ++state.authStatusGeneration, promise: null };
        state.authStatusRequest = request;
        state.authConnectionState = "loading";
        state.authConnectionError = "";
        syncAuthConnectionUI();
        void primeInitialMediaCache().catch(() => {});
        request.promise = (async () => {
            try {
                const [metaResult, authResult] = await Promise.all([
                    requestJsonWithin("/api/meta", { cache: "no-store" }).catch(() => null),
                    requestJsonWithin("/api/auth/x/status", { cache: "no-store" })
                ]);
                if (request.generation !== state.authStatusGeneration) return;
                const { resp: authResp, body: authStatus } = authResult;
                if (!authResp.ok) throw new Error(`无法确认登录状态（HTTP ${authResp.status}）`);
                if (typeof authStatus.loggedIn !== "boolean") throw new Error("登录状态响应异常，请重试。");
                const oldUserId = stableUserId(state.authStatus?.user);
                const newUserId = authStatus.loggedIn ? stableUserId(authStatus.user) : "";
                if (authStatus.loggedIn && !newUserId) throw new Error("无法确认账号，请重新登录。");
                if (oldUserId !== newUserId) {
                    resetUserScopedMemoryState();
                    request.generation = state.authStatusGeneration;
                }
                if (metaResult?.resp.ok) state.serverMeta = metaResult.body;
                state.authStatus = authStatus;
                state.authConnectionState = "ready";
                state.authConnectionError = "";
                syncAuthGateAndWorkspaceUI();
                return authStatus;
            } catch (err) {
                if (request.generation !== state.authStatusGeneration) return;
                state.authConnectionState = "error";
                state.authConnectionError = err.name === "RequestTimeoutError"
                    ? "连接超时，请重试。" : "暂时无法确认登录状态，请重试。";
                throw err;
            } finally {
                if (state.authStatusRequest === request) {
                    state.authStatusRequest = null;
                    syncAuthConnectionUI();
                }
            }
        })();
        return request.promise;
    }

    function syncSettingsAccountMenu(user) {
        const head = document.getElementById("settingsAccountHead");
        if (!head) return;
        if (!user || !user.username) {
            head.hidden = true;
            return;
        }
        const nameEl = document.getElementById("settingsAccountName");
        const handleEl = document.getElementById("settingsAccountHandle");
        const avatarEl = document.getElementById("settingsAccountAvatar");
        if (nameEl) nameEl.textContent = user.displayName || user.username;
        if (handleEl) handleEl.textContent = "@" + String(user.username).replace(/^@+/, "");
        if (avatarEl) {
            avatarEl.outerHTML = avatarHtml(user, "sm").replace(
                'class="avatar-circle sm"',
                'class="avatar-circle sm" id="settingsAccountAvatar"'
            );
        }
        head.hidden = false;
    }

    function syncAuthGateAndWorkspaceUI() {
        const loggedIn = isLoggedIn();

        // 主界面与登录门禁切换
        $("authGateSection").hidden = loggedIn;
        $("authenticatedWorkspace").hidden = !loggedIn;
        $("authenticatedRightRail").hidden = !loggedIn || !state.report;
        $("leftNavMenu").classList.toggle("is-locked", !loggedIn);
        // 未登录时只保留中间的登录卡片：隐藏导航、账号胶囊与右栏
        $("leftNavMenu").hidden = !loggedIn;
        $("railAccountPill").hidden = !loggedIn;
        document.body.classList.toggle("is-guest", !loggedIn);

        const logoutBtn = $("railLogoutBtn");

        if (loggedIn) {
            const u = state.authStatus.user;
            const currentKey = stableUserId(u);
            if (state.boundUserId && state.boundUserId !== currentKey) {
                resetUserScopedMemoryState();
            }
            state.boundUserId = currentKey;
            const cachedHeader = loadProfileHeaderCache(currentKey);
            const effectiveUser = cachedHeader
                ? {
                    ...cachedHeader,
                    ...u,
                    avatarUrl: u.avatarUrl || cachedHeader.avatarUrl
                }
                : u;
            logoutBtn.hidden = false;
            $("railDisplayName").textContent = effectiveUser.displayName || effectiveUser.username;
            $("railHandle").textContent = "@" + effectiveUser.username;
            $("railAvatar").outerHTML = avatarHtml(effectiveUser, "sm").replace(
                'class="avatar-circle sm"',
                'class="avatar-circle sm" id="railAvatar"'
            );
            syncSettingsAccountMenu(effectiveUser);
            if (effectiveUser.avatarUrl) {
                void warmMediaAsset(effectiveUser.avatarUrl);
            }

            // 首次进入尚未拿到全量报告前，立即用本地快照预填横幅、头像、简介与计数，消除首屏布局跳变
            if (!state.report) {
                const isAuthorSelf = state.authStatus.ownerAllPermissions === true;
                const earlyBanner = (cachedHeader && cachedHeader.bannerUrl) || (isAuthorSelf ? AUTHOR_BANNER_URL : "");
                applyCoverBanner($("profileCover"), earlyBanner);
                const heroEl = $("profileHero");
                if (heroEl) heroEl.classList.toggle("has-banner", Boolean(earlyBanner));

                $("profileAvatar").outerHTML = avatarHtml(effectiveUser, "xl").replace(
                    'class="avatar-circle xl"',
                    'class="avatar-circle xl" id="profileAvatar"'
                );
                $("profileDisplayName").textContent = effectiveUser.displayName || effectiveUser.username;
                $("profileVerifiedIcon").hidden = !effectiveUser.verified;
                $("profileHandleText").textContent = "@" + effectiveUser.username;
                $("profilePersonaTag").textContent =
                    (cachedHeader && cachedHeader.personaTag) || (state.storedMode ? "已入库" : "同步中");
                $("visitXProfileLink").href = effectiveUser.id ? `https://x.com/i/user/${encodeURIComponent(effectiveUser.id)}`
                    : `https://x.com/${encodeURIComponent(effectiveUser.username)}`;
                if (cachedHeader && cachedHeader.bio) {
                    $("profileBio").textContent = cachedHeader.bio;
                    $("profileBio").hidden = false;
                } else {
                    $("profileBio").textContent = "";
                    $("profileBio").hidden = true;
                }
                if (cachedHeader) {
                    $("profileFollowingNum").textContent = fmtNum(cachedHeader.followingCount);
                    $("profileFollowersNum").textContent = fmtNum(cachedHeader.followersCount);
                    $("profileTotalTweetsNum").textContent = fmtNum(cachedHeader.totalPostsCount);
                } else {
                    ["profileFollowingNum", "profileFollowersNum", "profileTotalTweetsNum"].forEach((id) => {
                        $(id).textContent = "--";
                    });
                }
                $("topbarWindowHint").textContent = state.storedMode ? "读取已入库报表…" : "同步数据中…";
            }
            if (state.authStatus?.ownerAllPermissions === true && !state.assistantAccess) {
                state.assistantAccess = { allowed: true };
            }
            syncPaidUiVisibility();
            syncPeriodButtons();
            syncScheduleAuthBanner();
        } else {
            logoutBtn.hidden = true;
            $("navUnfollowBadge").hidden = true;
            $("navScheduleBadge").hidden = true;
            $("mobileScheduleBadge").hidden = true;
            $("overdueGlobalBanner").hidden = true;
            $("scheduledTweetsList").innerHTML = "";
            closeConfirmResendModal();
            clearScheduleComposerDraft();
            $("railDisplayName").textContent = "未授权登录";
            $("railHandle").textContent = "仅限登录查本人账号";
            $("railAvatar").outerHTML = `<div class="avatar-circle sm" id="railAvatar">X</div>`;
            syncSettingsAccountMenu(null);

            $("oauthAdminSetupHint").hidden = true;
            syncPaidUiVisibility();
        }
        syncAuthConnectionUI();
    }

    async function triggerXOAuthLogin() {
        if (state.authConnectionState === "idle" || state.authConnectionState === "loading") return;
        if (state.authConnectionState === "error") {
            try {
                await fetchServerAndAuthStatus();
                if (isLoggedIn()) {
                    const params = new URLSearchParams(window.location.search);
                    const publishError = params.get("publish_auth_error");
                    if (publishError) setPublishAuthError(publishError);
                    const urlTab = params.get("tab");
                    const validTabs = new Set(["activity", "interactions", "follow-audit", "timing", "circle", "schedule"]);
                    const initialTab = urlTab === "schedule" || params.get("auth") === "publish_ok" || publishError
                        ? "schedule" : urlTab && validTabs.has(urlTab) ? urlTab : state.activeTab;
                    switchTab(initialTab, { historyMode: "replace" });
                    await restoreScheduleDraftAfterStepUpAuth();
                    void loadScheduledTweets(true);
                    cleanAuthQueryParams();
                }
            } catch (_) { return; }
        }
        if (isLoggedIn()) {
            void loadSelfReport(false);
            return;
        }
        const oauthConfigured = Boolean(state.authStatus?.oauthConfigured || state.serverMeta?.oauthConfigured);
        if (!oauthConfigured) {
            state.authConnectionError = "登录暂不可用，请稍后再试。";
            syncAuthConnectionUI();
            return;
        }
        state.authConnectionState = "loading";
        const btn = typeof document !== "undefined" ? document.getElementById("gateLoginBtn") : null;
        if (btn) {
            btn.disabled = true;
            btn.setAttribute("aria-busy", "true");
            const label = btn.querySelector("span");
            if (label) label.textContent = "正在跳转 X 授权…";
        }
        window.location.href = "/api/auth/x/login";
    }

    function startCacheCooldownTicker(nextRefreshAt) {
        clearInterval(state.cooldownTimerId);
        state.cacheExpiresEpochMs = Date.parse(nextRefreshAt) || 0;
        updateRefreshButtonState();
    }

    function updateRefreshButtonState() {
        const relationshipControls = $("relationshipContinueControls");
        relationshipControls.hidden = !isOwnerAllPermissions() || state.storedMode || !isLoggedIn() || state.pendingRelationshipChecks <= 0;
        const relationshipBtn = $("continueRelationshipsBtn");
        relationshipBtn.disabled = state.isFetching || state.isLoggingOut;
        relationshipBtn.textContent = `继续核验（${state.pendingRelationshipChecks}）`;
        const btn = $("forceRefreshBtn");
        if (!btn) return;
        if (state.storedMode) {
            btn.disabled = state.isFetching || state.isLoggingOut;
            btn.textContent = state.isFetching ? "读取中…" : "切回实时";
            btn.title = "返回实时报表";
            btn.classList.remove("can-refresh", "is-quota-exhausted", "is-cooled");
            return;
        }
        if (state.isFetching) {
            btn.disabled = true;
            btn.classList.remove("can-refresh", "is-quota-exhausted", "is-cooled");
            btn.textContent = "同步中…";
            btn.title = "正在同步账号数据";
            return;
        }
        const status = state.dailyCacheStatus;
        const pending = status && (status.canContinue ?? (!status.timelineCovered || !status.inboundCovered || status.pendingMetricPosts > 0));
        btn.disabled = state.isLoggingOut;
        btn.classList.remove("is-quota-exhausted", "is-cooled");
        btn.classList.add("can-refresh");
        btn.textContent = pending ? "继续补齐" : "检查更新";
        btn.title = pending
            ? "继续补齐最近24小时的推文、收到的互动与可补指标，有进展时自动分批继续"
            : "检查服务端缓存与最近24小时覆盖，满一小时按需更新";
    }


    function applyLoadedPeriodReport(body) {
        state.report = body;
        syncReportVisibility();
        loadFollowLocalMarks();
        const resolvedMeta = normalizePeriod(body.periodCode, body.windowDays);
        state.period = resolvedMeta.code;
        state.days = resolvedMeta.days;
        syncPeriodButtons();

        // 地址栏仅保留维度参数，不暴露他人 user 参数
        const nextUrl = new URL(window.location.href);
        nextUrl.searchParams.set("period", state.period);
        if (state.storedMode) nextUrl.searchParams.set("stored", "true");
        nextUrl.searchParams.delete("user");
        nextUrl.searchParams.delete("days");
        window.history.replaceState({}, "", nextUrl.toString());

        if (body.interactions.peerStats.length > 0) {
            const exists = body.interactions.peerStats.some(
                (p) => p.peer.username.toLowerCase() === (state.selectedPeerUsername || "").toLowerCase()
            );
            if (!exists) {
                state.selectedPeerUsername = body.interactions.peerStats[0].peer.username;
            }
        } else {
            state.selectedPeerUsername = null;
        }

        // 有待办时默认先看「我没回谁」，若无则依次看「评论待回复 / 互相评论 / 谁没回我」；用户手动选过筛选后尊重其选择
        if (!state.peerFilterTouched) {
            state.peerRelFilter =
                Number(body.interactions.inboundHeavyPeers || 0) > 0
                    ? "I_OWE"
                    : Number(body.interactions.unrepliedMyPostPeers || 0) > 0
                      ? "UNREPLIED_POST"
                      : Number(body.interactions.balancedMutualPeers || 0) > 0
                        ? "MUTUAL"
                        : "THEY_OWE";
        }

        renderAll(body);
        if (state.accessPolicy && typeof state.accessPolicy.scheduledPublishAllowed === "boolean") {
            state.scheduleAccess = {
                allowed: Boolean(state.accessPolicy.scheduledPublishAllowed),
                followsAuthor: Boolean(state.accessPolicy.followsAuthor),
                authorInteractionRank: state.accessPolicy.authorInteractionRank ?? null,
                reason: state.accessPolicy.scheduledPublishLockedReason || ""
            };
            syncScheduleAuthBanner();
        }
        if (state.activeTab === "schedule") {
            renderScheduleQuickSlots();
        }
    }

    function fmtMonthDay(isoStr) {
        if (!isoStr) return "";
        try {
            const d = new Date(isoStr);
            if (Number.isNaN(d.getTime())) return "";
            return d.toLocaleDateString("zh-CN", {
                timeZone: state.report?.contentTiming?.timezone || "Asia/Shanghai",
                month: "2-digit",
                day: "2-digit"
            });
        } catch (_) {
            return "";
        }
    }

    async function loadSelfReport(options = {}) {
        const continueRelations = !state.storedMode && options?.continueRelations === true;
        const reloadFromServer = !state.storedMode && options?.reloadFromServer === true;
        const completedRefresh = reloadFromServer ? options?.completedRefresh : null;
        const forceRefresh = !state.storedMode && !continueRelations && (options === true || options?.forceRefresh === true);
        const silent = !state.storedMode && !forceRefresh && !continueRelations &&
            (options?.silent === true || (reloadFromServer && Boolean(state.report)));
        if (state.isLoggingOut) return;
        if (!isLoggedIn()) {
            syncAuthGateAndWorkspaceUI();
            return;
        }

        const authUname = state.authStatus && state.authStatus.user ? state.authStatus.user.username : "";
        const authUserId = stableUserId(state.authStatus.user);
        if (!authUserId) {
            showError("无法确认稳定账号 ID，请重新登录");
            return;
        }
        const bundleOwnerId = stableUserId(state.bundle?.[state.period]?.profile);

        // /api/analyze/bundle 已返回当前账号全部已解锁周期；切维度时优先复用本人内存包实现 0 网络请求秒切。
        if (
            !forceRefresh &&
            !continueRelations &&
            !reloadFromServer &&
            state.bundle &&
            state.bundle[state.period] &&
            bundleOwnerId &&
            bundleOwnerId === authUserId &&
            (state.storedMode || Date.parse(state.bundle[state.period].nextRefreshAt) > Date.now())
        ) {
            const cachedPeriodReport = state.bundle[state.period];
            startCacheCooldownTicker(cachedPeriodReport.nextRefreshAt);
            applyLoadedPeriodReport(cachedPeriodReport);
            return;
        }

        if (state.isFetching) {
            if (!continueRelations && !silent) state.pendingExpandCheck = true;
            return;
        }
        if (silent && state.isSilentRefreshing) {
            return;
        }
        if (!silent && state.isSilentRefreshing && state.silentReportAbortController) {
            state.silentReportAbortController.abort();
            state.silentReportAbortController = null;
            state.isSilentRefreshing = false;
        }
        const requestGeneration = ++state.reportRequestGeneration;
        const selectedPeriod = state.period;
        const selectedDays = state.days;
        const requestIsCurrent = () => requestGeneration === state.reportRequestGeneration &&
            !state.isLoggingOut && isLoggedIn() &&
            stableUserId(state.authStatus.user) === authUserId;
        if (silent) {
            state.isSilentRefreshing = true;
        } else {
            state.isFetching = true;
            state.pendingExpandCheck = false;
            updateRefreshButtonState();

            hideError();
            const retryCardStart = document.getElementById("workspaceRetryCard");
            if (retryCardStart) retryCardStart.hidden = true;
            $("loadingBar").hidden = false;
            if (!state.report) showLoadingSkeleton(true);
        }
        const slowHintEl = $("loadingSlowHint");
        const startedMs = Date.now();
        let elapsedTimer = null;

        const updateLoadingProgressText = () => {
            if (silent || !slowHintEl || !requestIsCurrent()) return;
            const sec = Math.max(1, Math.floor((Date.now() - startedMs) / 1000));
            slowHintEl.textContent = state.storedMode ? `读取已入库报表（${sec}s）…` : `同步中（${sec}s）…`;
            slowHintEl.hidden = false;
        };

        const slowTimer = silent ? null : setTimeout(() => {
            updateLoadingProgressText();
            elapsedTimer = setInterval(updateLoadingProgressText, 1000);
        }, 2500);

        try {
            // 首屏只加载最近 24 小时（或当前请求单批）让界面立即可流畅使用；剩余指标与历史窗口统一交由后台静默补齐。
            const reqDays = selectedDays || 1;
            const reqPeriod = selectedPeriod || "HOURS_24";
            const sendRefresh = forceRefresh;
            const sendPreferStored = !forceRefresh && !continueRelations && !reloadFromServer && !state.storedMode;
            if (!requestIsCurrent()) return;
            const abortCtrl = new AbortController();
            if (silent) {
                state.silentReportAbortController = abortCtrl;
            } else {
                state.reportAbortController = abortCtrl;
            }
            const fetchTimeout = setTimeout(() => abortCtrl.abort(), 55000);
            let resp;
            let bundleBody;
            try {
                resp = await fetch(
                    `/api/analyze/bundle?period=${encodeURIComponent(reqPeriod)}&targetDays=${encodeURIComponent(reqDays)}${state.storedMode || completedRefresh ? "&stored=true" : continueRelations ? "&continueRelations=true" : ""}${sendRefresh ? "&refresh=true" : ""}${sendPreferStored ? "&preferStored=true" : ""}`,
                    {
                        cache: "no-store",
                        signal: abortCtrl.signal
                    }
                );
                bundleBody = await safeParseJson(resp);
            } finally {
                clearTimeout(fetchTimeout);
            }

            if (!requestIsCurrent()) return;
            if (resp.status === 401) {
                if (silent) {
                    state.isSilentRefreshing = false;
                    return;
                }
                state.isFetching = false;
                await fetchServerAndAuthStatus();
                if (requestGeneration !== state.reportRequestGeneration && isLoggedIn()) return;
                showError(bundleBody.message || "登录会话已失效，请重新使用 X 账号授权登录");
                return;
            }
            const nonNullReports = [
                bundleBody.hours24,
                bundleBody.day,
                bundleBody.week,
                bundleBody.month
            ].filter(Boolean);
            if (!resp.ok || nonNullReports.length === 0) {
                throw new Error(bundleBody.message || `查询失败（HTTP ${resp.status}）`);
            }

            if (nonNullReports.some((report) => stableUserId(report?.profile) !== authUserId)) {
                throw new Error("报表账号与当前登录账号不匹配，请重新加载");
            }
            const background = completedRefresh || bundleBody.backgroundRefreshStatus;
            const cycle = bundleBody.dailyCacheStatus?.cycleStartedAt;
            state.backgroundRefreshStatus = !background?.newerResultAvailable && background?.cycleStartedAt && cycle &&
                Date.parse(background.cycleStartedAt) !== Date.parse(cycle) ? null : background || null;
            state.bundle = {
                HOURS_24: bundleBody.hours24 || null,
                HOURS_48: bundleBody.day || null,
                WEEK: bundleBody.week || null,
                MONTH: bundleBody.month || null
            };
            state.accessPolicy = bundleBody.accessPolicy || null;
            state.dailyCacheStatus = bundleBody.dailyCacheStatus || null;
            state.coveredWindowDays = Number(bundleBody.coveredWindowDays ?? 0);
            state.pendingRelationshipChecks = state.storedMode ? 0 : Math.max(0, Number(bundleBody.pendingRelationshipChecks || 0));

            const targetReport = state.bundle[state.period] || state.bundle.HOURS_24 || nonNullReports[0];
            startCacheCooldownTicker(targetReport.nextRefreshAt);
            applyLoadedPeriodReport(targetReport);
            if (!state.storedMode && (state.backgroundRefreshStatus?.state === "RUNNING" ||
                    (!completedRefresh && state.backgroundRefreshStatus?.newerResultAvailable))) {
                scheduleWorkspaceRefreshPoll(authUname, selectedPeriod || "HOURS_24", selectedDays || 1);
            } else if (
                !silent &&
                !reloadFromServer &&
                !state.storedMode &&
                !continueRelations &&
                state.dailyCacheStatus?.canContinue &&
                !state.dailyCacheStatus.lastAttemptDegraded &&
                (forceRefresh || !state.backgroundRefreshStatus || state.backgroundRefreshStatus.state === "IDLE")
            ) {
                void startColdWorkspaceRefresh(selectedPeriod || "HOURS_24", 1, requestIsCurrent)
                    .then(status => { if (status && requestIsCurrent()) scheduleWorkspaceRefreshPoll(authUname, selectedPeriod || "HOURS_24", 1); })
                    .catch(() => {});
            }
        } catch (err) {
            if (!requestIsCurrent()) return;
            if (silent && state.report) return;
            const rawErrMsg = String(err?.message || "");
            const isBrowserFetchFailure =
                /^(?:Failed to fetch|Load failed|NetworkError when attempting to fetch resource\.?|fetch failed)$/i.test(rawErrMsg.trim()) ||
                (err?.name === "TypeError" && /fetch|network/i.test(rawErrMsg));
            const msg = state.storedMode && err && err.name === "AbortError"
                ? "已入库报表读取超时，请重试当前模式"
                : err && err.name === "AbortError"
                ? "上游数据响应超时，请再点一次对应时间段继续从断点补齐"
                : isBrowserFetchFailure
                ? "当前网络连接异常或处于离线状态，请检查网络后重试。"
                : (rawErrMsg || "网络或数据服务异常，请稍后重试");
            showError(msg);
            if (!state.report) {
                showLoadingSkeleton(false);
                $("profilePersonaTag").textContent = state.storedMode ? "已入库不可用" : "同步未完成";
                $("profileBio").textContent = "";
                $("profileBio").hidden = true;
                $("topbarWindowHint").textContent = state.storedMode ? "已入库模式 · 请稍后重试" : "请稍后重试";
                const retryCard = document.getElementById("workspaceRetryCard");
                const retryMsgEl = document.getElementById("workspaceRetryMessage");
                if (retryMsgEl) retryMsgEl.textContent = msg;
                if (retryCard) retryCard.hidden = false;
            }
        } finally {
            if (slowTimer !== null) clearTimeout(slowTimer);
            clearInterval(elapsedTimer);
            if (!requestIsCurrent()) {
                if (silent) state.isSilentRefreshing = false;
                return;
            }
            if (silent) {
                state.silentReportAbortController = null;
                state.isSilentRefreshing = false;
            } else {
                state.reportAbortController = null;
                state.isFetching = false;
                updateRefreshButtonState();
                if (slowHintEl) slowHintEl.hidden = true;
                $("loadingBar").hidden = true;
                $("authenticatedWorkspace").classList.remove("is-loading");
                if (state.pendingExpandCheck && state.bundle?.[state.period]) {
                    state.pendingExpandCheck = false;
                    applyLoadedPeriodReport(state.bundle[state.period]);
                }
            }
        }
    }

    // 首次加载骨架屏：用灰色占位块代替空白，让用户知道内容马上出现
    function showLoadingSkeleton(on) {
        const ws = $("authenticatedWorkspace");
        ws.classList.toggle("is-loading", on);
        const rows = (n) =>
            Array.from({ length: n })
                .map(
                    () => `<div class="skeleton-row"><div class="skeleton-circle"></div><div class="skeleton-lines"><i></i><i></i></div></div>`
                )
                .join("");
        const targets = ["peerInteractionList", "followAuditList", "topTweetsList"];
        targets.forEach((id) => {
            $(id).innerHTML = on ? rows(5) : emptyStateHtml("数据同步未完成，请稍后刷新页面", []);
        });
        $("dailyTrendChart").innerHTML = on ? `<div class="skeleton-block"></div>` : "";
    }

    function showError(msg) {
        const el = $("errorToast");
        const textEl = document.getElementById("errorToastText");
        if (textEl) {
            textEl.textContent = msg;
        } else {
            el.textContent = msg;
        }
        el.hidden = false;
    }

    function hideError() {
        $("errorToast").hidden = true;
    }

    function syncUsageHandleDisplay(rawUsername) {
        const uname = String(rawUsername || "").replace(/^@+/, "");
        if (!uname) return;
        const handleEl = $("railHandle");
        handleEl.textContent = `@${uname}`;
        handleEl.title = `@${uname}`;
        const logoBtn = document.getElementById("logoHomeBtn");
        if (logoBtn) logoBtn.title = "返回工作台顶部";
    }

    function syncBodyModalLock() {
        const ids = ["authorPromptModal", "unlockGuideModal", "confirmResendModal", "repostPreviewModal"];
        const anyOpen = ids.some((id) => {
            const el = document.getElementById(id);
            return Boolean(el && !el.hidden);
        });
        document.body.classList.toggle("modal-open", anyOpen);
    }

    function buildFallbackAuthorCardData() {
        const selfIsAuthor = isOwnerAllPermissions();
        const authorId = String((selfIsAuthor && state.authStatus.user.id) || state.accessPolicy?.authorAccountId || AUTHOR_ARCHIVE_ACCOUNT_ID);
        const cachedAuthor = String(state.report?.profile?.id || "") === authorId ? state.report.profile : null;
        const handle = (selfIsAuthor && state.authStatus.user.username) || state.accessPolicy?.authorHandle || cachedAuthor?.username || "";
        const authorLabel = handle ? `@${handle}` : "作者";
        return {
            usageCount: state.usageCount || 0,
            modalReasonCode: "MANUAL_PREVIEW",
            modalBadgeText: state.usageCount > 0 ? `作者主页 · 第 ${state.usageCount} 次使用` : "作者主页 · 开源说明",
            modalTitle: handle ? `你好，我是作者 @${handle}` : "你好，我是作者",
            modalMessage:
                `每次使用本工作台，后端都会真实付费调用 X / 数据接口。项目免费开放并全量开源，结个善缘，欢迎关注 ${authorLabel} 交流进展！\n\n` +
                AUTHOR_COST_STORY,
            followingAuthor: Boolean(state.accessPolicy?.followsAuthor),
            selfIsAuthor,
            authorProfile: {
                id: authorId,
                username: handle,
                displayName: cachedAuthor?.displayName || "Litany（李丰一）",
                avatarUrl: "/author-avatar.webp",
                bannerUrl: AUTHOR_BANNER_URL,
                bio: cachedAuthor?.bio || AUTHOR_DEFAULT_BIO,
                verified: cachedAuthor?.verified !== undefined ? Boolean(cachedAuthor.verified) : true,
                followingCount: cachedAuthor?.followingCount || 0,
                followersCount: cachedAuthor?.followersCount || 0,
                totalPostsCount: cachedAuthor?.totalPostsCount || 0
            },
            authorProfileUrl: `https://x.com/i/user/${encodeURIComponent(authorId)}`,
            authorFollowIntentUrl: `https://x.com/intent/follow?user_id=${encodeURIComponent(authorId)}`
        };
    }

    const modalFocusHistoryStack = [];
    let ignoreNextModalBackPopstate = false;

    function formatLocalizedFetchError(err, fallback = "网络或数据服务异常，请稍后重试") {
        const raw = String(err?.message || "").trim();
        if (
            /^(?:Failed to fetch|Load failed|NetworkError when attempting to fetch resource\.?|fetch failed)$/i.test(raw) ||
            (err?.name === "TypeError" && /fetch|network/i.test(raw))
        ) {
            return "当前网络连接异常或处于离线状态，请检查网络后重试。";
        }
        return raw || fallback;
    }

    function onModalOpened(modalId, modalEl) {
        const alreadyIdx = modalFocusHistoryStack.findIndex((item) => item.id === modalId);
        if (alreadyIdx === -1) {
            const opener = document.activeElement && document.activeElement !== document.body
                ? document.activeElement
                : null;
            let pushedHistory = false;
            try {
                if (typeof window !== "undefined" && window.history && typeof window.history.pushState === "function") {
                    const nextState = { ...(window.history.state || {}), xopsModal: modalId };
                    window.history.pushState(nextState, "", window.location.href);
                    pushedHistory = true;
                }
            } catch (_) {}
            modalFocusHistoryStack.push({ id: modalId, opener, pushedHistory });
        }
        const focusTarget = modalEl?.querySelector?.(".author-modal-close, button:not([disabled]), a[href]");
        if (focusTarget && typeof focusTarget.focus === "function") {
            try { focusTarget.focus({ preventScroll: true }); } catch (_) {}
        }
    }

    function onModalClosed(modalId, fromPopstate = false) {
        const idx = modalFocusHistoryStack.findIndex((item) => item.id === modalId);
        if (idx === -1) return;
        const [entry] = modalFocusHistoryStack.splice(idx, 1);
        if (entry?.opener && entry.opener.isConnected && typeof entry.opener.focus === "function") {
            try { entry.opener.focus({ preventScroll: true }); } catch (_) {}
        }
        if (!fromPopstate && entry?.pushedHistory) {
            try {
                if (typeof window !== "undefined" && window.history?.state?.xopsModal === modalId && typeof window.history.back === "function") {
                    ignoreNextModalBackPopstate = true;
                    window.history.back();
                }
            } catch (_) {
                ignoreNextModalBackPopstate = false;
            }
        }
    }

    function handleModalPopstate(event) {
        if (ignoreNextModalBackPopstate) {
            ignoreNextModalBackPopstate = false;
            return true;
        }
        const top = modalFocusHistoryStack[modalFocusHistoryStack.length - 1];
        if (!top) return false;
        if (event?.state?.xopsModal === top.id) return false;
        if (top.id === "authorPromptModal") closeAuthorPromptModal(true);
        else if (top.id === "unlockGuideModal") closeUnlockGuideModal(true);
        else if (top.id === "confirmResendModal") closeConfirmResendModal(true);
        else if (top.id === "repostPreviewModal" && typeof closeRepostPreviewModal === "function") closeRepostPreviewModal(true);
        return true;
    }

    function openAuthorPromptModal(data) {
        const modal = document.getElementById("authorPromptModal");
        if (!modal || !data) return;
        const rawAuthor = data.authorProfile || {};
        const cleanAuthorBio = String(rawAuthor.bio || AUTHOR_DEFAULT_BIO)
            .replace(/[\u200B-\u200D\uFEFF]/g, "")
            .replace(/\s{2,}/g, " ")
            .trim() || AUTHOR_DEFAULT_BIO;
        const author = {
            username: rawAuthor.username || state.accessPolicy?.authorHandle || "",
            displayName: rawAuthor.displayName || "Litany（李丰一）",
            avatarUrl: rawAuthor.avatarUrl || "/author-avatar.webp",
            bannerUrl: rawAuthor.bannerUrl || AUTHOR_BANNER_URL,
            bio: cleanAuthorBio,
            verified: rawAuthor.verified !== undefined ? Boolean(rawAuthor.verified) : true,
            followingCount: rawAuthor.followingCount || 0,
            followersCount: rawAuthor.followersCount || 0,
            tweetCount: rawAuthor.totalPostsCount || rawAuthor.tweetCount || 0
        };
        const handle = String(author.username).replace(/^@+/, "");
        const authorLabel = handle ? `@${handle}` : "作者";
        const badgeEl = document.getElementById("authorModalBadge");
        if (badgeEl) {
            const countVal = Number(data.usageCount || state.usageCount || 0);
            const badgeText = data.modalReasonCode === "MANUAL_PREVIEW"
                ? (countVal > 0 ? `第 ${countVal} 次使用` : "")
                : (data.modalBadgeText || `第 ${countVal || 3} 次使用`);
            badgeEl.textContent = badgeText;
            badgeEl.hidden = !badgeText;
        }
        $("authorModalTitle").textContent = data.modalTitle || (handle ? `你好，我是作者 @${handle}` : "你好，我是作者");

        const rawMessage = String(
            data.modalMessage || `因为每次使用这个网站服务，我都是真实付费调用 API 的。结个善缘，求关注 ${authorLabel}！`
        ).replace(/\n+开源仓库（可本地部署，与线上一致）：https?:\/\/\S+\s*$/m, "").trim();
        const msgEl = $("authorModalMessage");
        msgEl.innerHTML = "";
        rawMessage.split(/\n{2,}/).map((seg) => seg.trim()).filter(Boolean).forEach((seg) => {
            const p = document.createElement("p");
            p.className = "author-note-para";
            p.textContent = seg;
            msgEl.appendChild(p);
        });

        applyCoverBanner($("authorModalCover"), author.bannerUrl);
        $("authorModalAvatarSlot").innerHTML = avatarHtml(author, "xl");
        $("authorModalDisplayName").textContent = author.displayName || handle;
        const verEl = $("authorModalVerified");
        verEl.innerHTML = verifiedSvgHtml(Boolean(author.verified));
        verEl.hidden = !author.verified;
        $("authorModalHandle").textContent = handle ? authorLabel : "";
        $("authorModalBio").textContent = author.bio;

        const followingCount = Number(author.followingCount || 0);
        const followersCount = Number(author.followersCount || 0);
        const tweetCount = Number(author.tweetCount || 0);
        const statsRow = $("authorModalStatsRow");
        if (followingCount > 0 || followersCount > 0 || tweetCount > 0) {
            $("authorModalFollowing").textContent = fmtNum(followingCount);
            $("authorModalFollowingWrap").hidden = !(followingCount > 0);
            $("authorModalFollowers").textContent = fmtNum(followersCount);
            $("authorModalPosts").textContent = fmtNum(tweetCount);
            statsRow.hidden = false;
        } else {
            statsRow.hidden = true;
        }

        const isFollowing = Boolean(data.followingAuthor);
        const isSelf = Boolean(data.selfIsAuthor);
        state.authorFollowingMarked = isFollowing || isSelf;
        $("authorModalSelfTag").hidden = !isSelf;
        $("authorModalFollowedTag").hidden = isSelf || !isFollowing;

        const authorId = encodeURIComponent(rawAuthor.id || state.accessPolicy?.authorAccountId || AUTHOR_ARCHIVE_ACCOUNT_ID);
        const safeProfileUrl = safeHttpUrl(data.authorProfileUrl, `https://x.com/i/user/${authorId}`);
        const safeFollowUrl = safeHttpUrl(
            data.authorFollowIntentUrl,
            `https://x.com/intent/follow?user_id=${authorId}`
        );
        const headerLink = $("authorModalHeaderLink");
        headerLink.href = safeProfileUrl;
        headerLink.textContent = `${authorLabel} ↗`;

        const followBtn = $("authorModalFollowBtn");
        followBtn.href = isFollowing || isSelf ? safeProfileUrl : safeFollowUrl;
        $("authorModalFollowBtnText").textContent = isSelf
            ? "查看我的 X 主页 ↗"
            : isFollowing
              ? `已关注 · 访问 ${authorLabel} 主页 ↗`
              : `关注 ${authorLabel} ↗`;

        const profileLink = $("authorModalProfileLink");
        profileLink.href = safeProfileUrl;
        profileLink.textContent = "X 主页 ↗";
        profileLink.hidden = isFollowing || isSelf;
        const repoLink = document.getElementById("authorModalRepoLink");
        if (repoLink) {
            repoLink.href = safeHttpUrl(state.accessPolicy?.repoUrl, AUTHOR_REPO_URL);
        }
        $("authorModalLaterBtn").textContent = isFollowing || isSelf ? "我知道了" : "稍后再说";
        modal.hidden = false;
        syncBodyModalLock();
        onModalOpened("authorPromptModal", modal);
    }

    function closeAuthorPromptModal(fromPopstate = false) {
        const modal = document.getElementById("authorPromptModal");
        if (modal) modal.hidden = true;
        syncBodyModalLock();
        onModalClosed("authorPromptModal", fromPopstate);
    }

    function openUnlockGuideModal(focusPeriodCode = null) {
        const modal = document.getElementById("unlockGuideModal");
        if (!modal) return;
        const p = state.accessPolicy;
        const statusBox = document.getElementById("unlockGuideStatusBox");
        if (statusBox) {
            if (p) {
                const rawRank = p.authorInteractionRank;
                const validRank = rawRank !== null && rawRank !== undefined && Number.isInteger(Number(rawRank)) && Number(rawRank) > 0
                    ? Number(rawRank)
                    : null;
                const allPeriodsUnlocked = Array.isArray(p.unlockedPeriods) && p.unlockedPeriods.length >= 4;
                const rankBadge = p.top5FullAccess && (validRank !== null || allPeriodsUnlocked)
                    ? (validRank !== null ? `互动第 ${validRank} 名（前 5 免等待全开）` : "全时间维度已解锁")
                    : p.top50AutoSync && validRank !== null
                      ? `互动第 ${validRank} 名（前 50 每日自动同步）`
                      : `每次同步 24 小时 · 保留近 30 天`;
                const followText = p.followsAuthor ? "已关注作者" : "未关注作者";
                statusBox.innerHTML =
                    `<div>已累计 <strong>${Number(p.accumulatedDays ?? 0)}/30</strong> 天 · ${followText}</div>` +
                    `<div>${rankBadge}</div><div>${reportSyncTimes(state.dailyCacheStatus).join(" · ")}</div>`;
            } else {
                statusBox.textContent = "每次同步 24 小时 · 保留近 30 天";
            }
        }
        const focusEl = document.getElementById("unlockGuideFocusMsg");
        if (focusEl) {
            if (focusPeriodCode === "SCHEDULE") {
                focusEl.textContent = getScheduleLockedReason();
                focusEl.hidden = false;
            } else if (focusPeriodCode && isPeriodUnlocked(focusPeriodCode)) {
                focusEl.hidden = true;
            } else {
                const reason = focusPeriodCode && p?.lockedReasons?.[focusPeriodCode];
                if (reason) {
                    focusEl.textContent = `未解锁原因：${reason}`;
                    focusEl.hidden = false;
                } else if (focusPeriodCode && focusPeriodCode !== "HOURS_24") {
                    const label = PERIOD_META[focusPeriodCode]?.badgePrefix || focusPeriodCode;
                    focusEl.textContent = `${label}暂未解锁。完成同步的天数达到要求后即可查看。`;
                    focusEl.hidden = false;
                } else {
                    focusEl.hidden = true;
                }
            }
        }
        const storyEl = document.getElementById("unlockGuideAuthorStory");
        if (storyEl) {
            storyEl.textContent = (p && p.authorStatement) || AUTHOR_COST_STORY;
        }
        const repoEl = document.getElementById("unlockGuideRepoLink");
        if (repoEl) {
            repoEl.href = safeHttpUrl(p?.repoUrl, AUTHOR_REPO_URL);
        }
        const followEl = document.getElementById("unlockGuideFollowLink");
        const followTxtEl = document.getElementById("unlockGuideFollowText");
        if (followEl && followTxtEl) {
            const followed = Boolean(p?.followsAuthor);
            const authorId = encodeURIComponent(p?.authorAccountId || AUTHOR_ARCHIVE_ACCOUNT_ID);
            followEl.href = followed
                ? `https://x.com/i/user/${authorId}`
                : `https://x.com/intent/follow?user_id=${authorId}`;
            const authorLabel = p?.authorHandle ? `@${p.authorHandle}` : "作者";
            followTxtEl.textContent = followed
                ? `已关注 · 访问 ${authorLabel} 主页 ↗`
                : `关注 ${authorLabel} ↗`;
        }
        modal.hidden = false;
        syncBodyModalLock();
        onModalOpened("unlockGuideModal", modal);
    }

    function closeUnlockGuideModal(fromPopstate = false) {
        const modal = document.getElementById("unlockGuideModal");
        if (modal) modal.hidden = true;
        syncBodyModalLock();
        onModalClosed("unlockGuideModal", fromPopstate);
    }

    async function checkinWorkspaceUsage() {
        if (state.storedMode || !isLoggedIn()) return;
        const userId = stableUserId(state.authStatus.user);
        const generation = state.authStatusGeneration;
        try {
            const resp = await fetch("/api/usage/checkin", { method: "POST", cache: "no-store" });
            if (!resp.ok) return;
            const data = await safeParseJson(resp);
            if (generation !== state.authStatusGeneration || state.isLoggingOut || !isLoggedIn() ||
                stableUserId(state.authStatus.user) !== userId) return;
            if (!data || typeof data.usageCount !== "number") return;
            state.usageCount = data.usageCount;
            const uname =
                (state.report && state.report.profile && state.report.profile.username) ||
                (state.authStatus && state.authStatus.user && state.authStatus.user.username) ||
                "";
            if (uname) {
                syncUsageHandleDisplay(uname);
            }
            if (data.shouldShowAuthorModal) {
                openAuthorPromptModal(data);
            }
        } catch (_) {}
    }

    /* ==================== 全量视图联动渲染 ==================== */
    function renderAll(report) {
        renderProfileHeader(report);
        renderActivityPanel(report);
        renderInteractionsPanel(report);
        renderFollowAuditPanel(report);
        renderTimingPanel(report);
        renderCirclePanel(report);
        renderRightSidebar(report);
    }

    function renderDailyCacheStatus(report) {
        const el = $("dailyCacheStatusHint");
        const status = state.dailyCacheStatus;
        const fmtTime = (value) => new Date(value).toLocaleString("zh-CN", {
            timeZone: "Asia/Shanghai", hour12: false
        });
        const parts = status ? [
            status.snapshotAt ? `资料快照 ${fmtTime(status.snapshotAt)}` : "",
            status.latestMetricFetchedAt ? `指标最近采集 ${fmtTime(status.latestMetricFetchedAt)}（近24h，分批读数）`
                : "指标最近采集时间未确认（近24h）",
            ...reportSyncTimes(status),
            `近24h推文${status.timelineCovered ? "已覆盖" : "待补齐"} ${status.recentPosts} 条`,
            `收到的互动${status.inboundCovered ? "已覆盖" : "待补齐"} ${status.recentInbound} 条`,
            status.unattemptedMetricPosts === undefined ? `待补指标 ${status.pendingMetricPosts} 帖`
                : `可补指标 ${status.unattemptedMetricPosts} 帖 · 本轮暂不可得 ${Math.max(0, status.pendingMetricPosts - status.unattemptedMetricPosts)} 帖`
        ] : [report.fetchedAt ? `原报表终点 ${fmtTime(report.fetchedAt)}` : ""];
        const background = state.backgroundRefreshStatus;
        if (background?.state === "RUNNING") {
            parts.unshift(`后台刷新中${background.startedAt ? ` · 开始 ${fmtTime(background.startedAt)}` : ""}`);
        } else if (background?.state === "FAILED") {
            parts.unshift(`后台刷新失败${background.error ? ` · ${background.error}` : ""}`);
        } else if (background?.state === "SUCCEEDED") {
            parts.unshift(`后台刷新完成${background.completedAt ? ` · ${fmtTime(background.completedAt)}` : ""}`);
        } else if (background?.state === "PARTIAL") {
            parts.unshift("后台已更新部分数据，仍待补齐");
        } else if (background?.state === "CACHED") {
            parts.unshift("本次复用已保存的报表");
        }
        el.textContent = parts.filter(Boolean).join(" · ");
        el.title = [el.textContent, status?.cycleStartedAt ? `本轮采集起点 ${fmtTime(status.cycleStartedAt)}` : ""].filter(Boolean).join(" · ");
        el.hidden = !el.textContent;
    }

    function followRateDisplay(report) {
        const audit = report.followAudit || {};
        const card = report.innerCircle?.shareCard || {};
        const value = audit.followBackRatePct ?? card.followBackRatePct;
        const sampled = Boolean(audit.followingsSampleTruncated);
        const count = Number(audit.followingSampleCount || 0);
        const unknown = Number(audit.unknownFollowBackCount || 0);
        const label = sampled ? "样本回关率" : "关注回关率";
        const scope = sampled ? `仅已采集的 ${count} 个关注对象` : "全部关注对象";
        let rate = value == null || (sampled && count === 0) ? "--" : `${value}%`;
        if (unknown > 0 && count > 0) {
            const mutual = Number(audit.mutualCount || 0);
            rate = `${(100 * mutual / count).toFixed(1)}%–${(100 * (mutual + unknown) / count).toFixed(1)}%`;
        }
        const detail = sampled || unknown > 0
            ? `${label} ${rate}（${scope}${unknown > 0 ? `，${unknown} 个未知关系` : ""}）`
            : audit.followBackRateLabel || card.followBackRateLabel || `${label} ${rate}`;
        return { label, rate, scope, detail };
    }

    function reportSyncTimes(status) {
        const completed = status?.lastCompletedAt ? Date.parse(status.lastCompletedAt) : NaN;
        const next = status?.nextCheckAt ? Date.parse(status.nextCheckAt)
            : status?.nextCheckAt === undefined && Number.isFinite(completed) ? completed + 3_600_000 : NaN;
        const format = time => new Date(time).toLocaleString("zh-CN", {timeZone: "Asia/Shanghai", hour12: false});
        return [
            Number.isFinite(completed) ? `上次完整同步 ${format(completed)}` : "尚无完整同步记录",
            Number.isFinite(next) ? `下次可检查更新 ${format(next)}` : "现在可检查更新",
            ...(status?.canContinue ? ["本轮可继续补齐"] : [])
        ];
    }

    function renderProfileHeader(report) {
        const p = report.profile;
        const act = report.activity;
        const audit = report.followAudit;
        const pMeta = normalizePeriod(report.periodCode, report.windowDays);

        // 左下角本人身份
        const retryCardEl = document.getElementById("workspaceRetryCard");
        if (retryCardEl) retryCardEl.hidden = true;
        $("railDisplayName").textContent = p.displayName;
        syncUsageHandleDisplay(p.username);
        $("railAvatar").outerHTML = avatarHtml(p, "sm").replace(
            'class="avatar-circle sm"',
            'class="avatar-circle sm" id="railAvatar"'
        );
        syncSettingsAccountMenu(p);

        // 吸顶栏：仅展示统计起止窗口与极简模式标记（窄屏精简日期防截断）
        const hintEl = $("topbarWindowHint");
        const coverageNote = (act.coverageNote || "").trim();
        const shortCoverage = /每日累计/.test(coverageNote) ? "" : coverageNote;
        const storedHint = state.storedMode
            ? `已入库 ${fmtClockTime(report.fetchedAt)}`
            : "";
        const fullHintText = [storedHint, act.windowRangeText, shortCoverage].filter(Boolean).join(" · ");
        const isNarrowMobile = typeof window !== "undefined" && window.innerWidth <= 720;
        if (isNarrowMobile && act.windowRangeText) {
            const compactRange = String(act.windowRangeText).replace(
                /(\d{1,2})月(\d{1,2})日\s*(\d{2}:\d{2})\s*[-–~]\s*(\d{1,2})月(\d{1,2})日\s*(\d{2}:\d{2})/,
                "$1/$2 $3 – $4/$5 $6"
            );
            hintEl.textContent = [storedHint, compactRange].filter(Boolean).join(" · ");
        } else {
            hintEl.textContent = fullHintText;
        }
        hintEl.classList.toggle("is-partial", Boolean(coverageNote) && !/每日累计/.test(coverageNote));
        hintEl.title = [fullHintText, coverageNote].filter(Boolean).join(" · ") || act.windowRangeText || "";
        renderDailyCacheStatus(report);

        // 头像与主页基本资料
        const isAuthorSelf = state.authStatus?.ownerAllPermissions === true && String(state.authStatus.user?.id) === String(p.id);
        const heroBannerUrl = p.bannerUrl || (isAuthorSelf ? AUTHOR_BANNER_URL : "");
        applyCoverBanner($("profileCover"), heroBannerUrl);
        const heroEl = $("profileHero");
        if (heroEl) heroEl.classList.toggle("has-banner", Boolean(heroBannerUrl));

        $("profileAvatar").outerHTML = avatarHtml(p, "xl").replace(
            'class="avatar-circle xl"',
            'class="avatar-circle xl" id="profileAvatar"'
        );
        $("profileDisplayName").textContent = p.displayName;
        $("profileVerifiedIcon").hidden = !p.verified;
        $("profilePersonaTag").textContent = act.personaTag;
        $("profileHandleText").textContent = "@" + p.username;
        const cleanBio = (p.bio || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim();
        $("profileBio").textContent = cleanBio;
        $("profileBio").hidden = !cleanBio;
        $("visitXProfileLink").href = p.id ? `https://x.com/i/user/${encodeURIComponent(p.id)}`
            : `https://x.com/${encodeURIComponent(p.username)}`;
        const hs = Number(act.activityHealthScore || 0);
        $("healthScoreChip").textContent = hs < 0 ? "健康 --" : `健康 ${hs}`;
        $("healthScoreChip").className = `health-score-chip ${hs < 0 ? "" : hs >= 50 ? "judge-good" : "judge-warn"}`;

        $("profileFollowingNum").textContent = fmtNum(p.followingCount);
        $("profileFollowersNum").textContent = fmtNum(p.followersCount);
        const followRate = followRateDisplay(report);
        $("profileMutualRateNum").textContent = `${audit.followingsSampleTruncated ? "样本 " : ""}${followRate.rate}`;
        $("profileMutualRateNum").title = followRate.detail;
        $("profileTotalTweetsNum").textContent = fmtNum(p.totalPostsCount);

        const selfId = stableUserId(state.authStatus?.user);
        if (selfId) {
            saveProfileHeaderCache(selfId, { ...p, bannerUrl: heroBannerUrl }, act.personaTag);
        }
        const topPeerAvatars = ((report.interactions && report.interactions.peerStats) || [])
            .slice(0, 24)
            .map((it) => it && it.peer && it.peer.avatarUrl)
            .filter(Boolean);
        scheduleMediaBatchWarm([AUTHOR_AVATAR_URL, AUTHOR_BANNER_URL, p.avatarUrl, heroBannerUrl, ...topPeerAvatars]);

        const noticeStrip = $("bannerNoticeStrip");
        const rawNotice = (report.bannerNotice || "").trim();
        const isSilentSwrNotice = rawNotice === "已返回最近一次完整入库快照；后台刷新已受理。";
        noticeStrip.textContent = isSilentSwrNotice ? "" : rawNotice;
        noticeStrip.hidden = !rawNotice || isSilentSwrNotice;

        const syncSummary = document.getElementById("syncStatusSummary");
        if (syncSummary) {
            const status = state.dailyCacheStatus;
            const pendingMetrics = Number(status?.pendingMetricPosts ?? NaN);
            const unattemptedMetrics = Number(status?.unattemptedMetricPosts ?? NaN);
            const isDemo = report.provider === "DEMO" || /DEMO|仿真|演示/i.test(rawNotice);
            // Only recognize complete, built-in notices; unfamiliar text stays visible as a caution.
            const knownDailyNotice = /^(?:最近 24 小时数据与指标已完成本轮同步；满 1 小时后再次检查更新。|本轮最近 24 小时数据或指标尚未补齐；继续补充可获取数据，未计为完整新鲜缓存。|本轮可请求的数据已尝试完毕，仍有未确认项；保留原值和缺失状态，下一小时再检查，不重复请求本轮失败项。)已入库 \d+ 条动态、\d+ 条互动；历史数据仍按原覆盖范围累计。$/.test(rawNotice);
            const knownSwrNotice = rawNotice === "已返回最近一次完整入库快照；后台刷新已受理。";
            const knownStorageNotice =
                /^单次加载 24 小时并已完成物理落盘（当前滚动保留近 30 天共 \d+ 条动态、\d+ 条互动）(?:（@\w+ 互动第 \d+ 名·已解锁全时间窗口）)?$/.test(rawNotice) ||
                /^每日物理存储模式：\d+ 条动态、\d+ 条互动已落盘。(?:你位列 @\w+ 互动前 \d+（第 \d+ 名），每日自动同步最近 24 小时并落盘；)?单次仅加载 24 小时，动态保留近 30 天窗口数据。$/.test(rawNotice);
            const canContinueSync = Boolean(status?.canContinue);
            const dataIncomplete = status?.timelineCovered === false || status?.inboundCovered === false ||
                (canContinueSync && pendingMetrics > 0 && (!Number.isFinite(unattemptedMetrics) || unattemptedMetrics > 0));
            const metricsUnavailable = !canContinueSync && pendingMetrics > 0 && unattemptedMetrics === 0;
            const followRateComplete = audit.followBackRateComplete ??
                (!audit.followersSampleTruncated && !audit.followingsSampleTruncated && Number(audit.unknownFollowBackCount || 0) === 0);
            const relationIncomplete = state.pendingRelationshipChecks > 0 ||
                !followRateComplete || Number(audit.unknownFollowBackCount || 0) > 0;
            const unexpectedNotice = Boolean(rawNotice && !isDemo && !knownDailyNotice && !knownSwrNotice && !knownStorageNotice);
            const needsAttention = Boolean(status?.lastAttemptDegraded || relationIncomplete || unexpectedNotice ||
                (!status && (act.sampleCapped || shortCoverage)) ||
                (canContinueSync && !dataIncomplete && !metricsUnavailable));
            let summaryText = "数据状态";
            if (state.storedMode) {
                summaryText = needsAttention ? "查看已保存数据 · 数据状态需留意" : "查看已保存数据";
            } else if (isDemo) {
                summaryText = "演示数据";
            } else if (needsAttention) {
                summaryText = "数据状态需留意";
            } else if (dataIncomplete) {
                summaryText = "部分数据待补齐";
            } else if (metricsUnavailable) {
                summaryText = "部分指标暂不可得";
            } else if (status?.timelineCovered === true && status?.inboundCovered === true && pendingMetrics === 0) {
                summaryText = "最近 24 小时已同步";
            }
            syncSummary.textContent = summaryText;
            const syncDetails = document.getElementById("syncStatusDetails");
            if (syncDetails) {
                if (!syncDetails._toggleBound && typeof syncDetails.addEventListener === "function") {
                    syncDetails._toggleBound = true;
                    syncDetails.addEventListener("toggle", () => {
                        if (!syncDetails._programmaticToggle) {
                            syncDetails._autoOpened = false;
                        }
                    });
                }
                syncDetails.classList?.toggle?.("is-attention", isDemo || needsAttention || (canContinueSync && dataIncomplete));
                const attentionKey = needsAttention && !isDemo
                    ? [rawNotice, shortCoverage, summaryText, Boolean(status?.lastAttemptDegraded),
                        audit.followersSampleTruncated, audit.followingsSampleTruncated,
                        audit.unknownFollowBackCount, audit.unknownFollowingCount, state.pendingRelationshipChecks].join("|")
                    : "";
                if (attentionKey && attentionKey !== syncDetails._lastAttentionKey) {
                    syncDetails._programmaticToggle = true;
                    syncDetails.open = true;
                    syncDetails._autoOpened = true;
                    syncDetails._programmaticToggle = false;
                } else if (!attentionKey && syncDetails._autoOpened) {
                    syncDetails._programmaticToggle = true;
                    syncDetails.open = false;
                    syncDetails._autoOpened = false;
                    syncDetails._programmaticToggle = false;
                }
                syncDetails._lastAttentionKey = attentionKey;
            }
        }

        const unfollowBadge = $("navUnfollowBadge");
        if (audit.notFollowingBackCount > 0) {
            unfollowBadge.textContent = String(audit.notFollowingBackCount);
            unfollowBadge.hidden = false;
        } else {
            unfollowBadge.hidden = true;
        }
    }

    /* ==================== 模块 1：发帖与回复活跃度看板 ==================== */
    function formatActivityTrendSummary(report) {
        if (!report || !report.activity) return "--";
        const act = report.activity;
        const series = act.trendSeries || [];
        const engagementSamples = series.reduce((sum, bucket) => sum + bucket.engagementSampleCount, 0);
        const totalEngagements = series.reduce((sum, bucket) => sum + bucket.engagements, 0);
        const exactNum = (value) => Number(value).toLocaleString("zh-CN");
        return `总浏览 ${act.viewSampleCount > 0 ? exactNum(act.totalViews) : "--"} · 总互动 ${engagementSamples > 0 ? exactNum(totalEngagements) : "--"}`;
    }

    function renderActivityPanel(report) {
        const act = report.activity;
        const pCode = report.periodCode || "";
        const isHourlyWindow = pCode === "HOURS_24" || pCode === "HOURS_48" || pCode === "DAY";
        const windowHours = pCode === "HOURS_24" ? 24 : 48;

        $("kpiPostsTitle").textContent = "原创发帖";
        $("kpiOriginalPosts").textContent = fmtNum(act.originalPosts);
        const quotePart = act.quotes > 0 ? `引用 ${act.quotes}` : "";
        if (pCode === "HOURS_24") {
            $("kpiDailyPosts").textContent = quotePart || (act.originalPosts > 0 ? "纯原创" : "暂无原创");
        } else if (!isHourlyWindow && act.sampleCapped) {
            $("kpiDailyPosts").textContent = `近 ${act.effectiveCoveredDays}d · 日均 ${act.dailyAvgPosts}${quotePart ? " · " + quotePart : ""}`;
        } else {
            $("kpiDailyPosts").textContent = `日均 ${act.dailyAvgPosts}${quotePart ? " · " + quotePart : ""}`;
        }

        $("kpiRepliesTitle").textContent = "回复";
        $("kpiReplies").textContent = fmtNum(act.replies);
        if (isHourlyWindow) {
            const activeHrs = Number(act.activeUnits || 0);
            $("kpiDailyReplies").textContent = pCode === "HOURS_24"
                ? (activeHrs > 0 ? `活跃 ${activeHrs}/24h` : "暂无回复")
                : (activeHrs > 0 ? `活跃 ${activeHrs}/${windowHours}h · 日均 ${act.dailyAvgReplies}` : `日均 ${act.dailyAvgReplies}`);
        } else {
            $("kpiDailyReplies").textContent = `日均 ${act.dailyAvgReplies}`;
        }

        $("kpiReplyRatio").textContent = `${act.replyToPostRatio}x`;
        const ratioJudge =
            act.replyToPostRatio >= 2 && act.replyToPostRatio <= 6
                ? "均衡 2–6x"
                : act.replyToPostRatio > 6
                    ? "偏高 · 增原创"
                    : "偏低 · 增互动";
        $("kpiReplyShare").textContent = ratioJudge;
        $("kpiReplyShare").className = `kpi-sub ${act.replyToPostRatio >= 2 && act.replyToPostRatio <= 6 ? "judge-good" : "judge-warn"}`;

        const erKnown = hasMetricSample(act, "engagementRateSampleCount");
        const viewsKnown = hasMetricSample(act, "mainPostViewSampleCount");
        $("kpiAvgEr").textContent = erKnown ? `${act.avgEngagementRatePct}%` : "--";
        $("kpiAvgEr").title = `公开互动率${metricSampleLabel(act, "engagementRateSampleCount")}`;
        const erVal = Number(act.avgEngagementRatePct || 0);
        $("kpiAvgViews").textContent = `均浏览 ${viewsKnown ? fmtNum(act.avgViewsPerPost) : "--"}`;
        $("kpiAvgViews").className = `kpi-sub ${!erKnown ? "" : erVal >= 1 ? "judge-good" : "judge-warn"}`;

        $("diagnosisSectionTitle").textContent = "节奏诊断";
        const rawAdvice = String(act.summaryAdvice || "").trim();
        const strippedAdvice = rawAdvice.includes("——")
            ? rawAdvice.split("——").slice(1).join("——").trim()
            : rawAdvice.replace(/^本周期产出[^。；]*[。；]\s*/, "").trim();
        $("activityAdviceText").textContent = strippedAdvice || rawAdvice;
        $("activeDaysBadge").textContent = String(act.activeProgressLabel || "")
            .replace(/\s*\/\s*/g, "/")
            .replace(/\s*小时$/, "h")
            .replace(/\s*天$/, "天");

        $("trendChartTitle").textContent = isHourlyWindow ? "逐时分布" : "每日分布";
        $("trendChartSubtitle").textContent = formatActivityTrendSummary(report);
        $("trendChartSubtitle").classList.remove("is-readout");

        const total = Math.max(1, act.totalActions);
        const pPost = ((act.originalPosts / total) * 100).toFixed(1);
        const pReply = ((act.replies / total) * 100).toFixed(1);
        const pQuote = ((act.quotes / total) * 100).toFixed(1);
        const pRt = ((act.retweets / total) * 100).toFixed(1);

        $("actionMixBar").innerHTML = act.totalActions > 0
            ? `
                <div class="mix-seg" style="width:${pReply}%; background:var(--x-blue)" title="回复 ${act.replies}"></div>
                <div class="mix-seg" style="width:${pPost}%; background:var(--text-primary)" title="原创 ${act.originalPosts}"></div>
                <div class="mix-seg" style="width:${pQuote}%; background:var(--x-green)" title="引用 ${act.quotes}"></div>
                <div class="mix-seg" style="width:${pRt}%; background:var(--x-amber)" title="转推 ${act.retweets}"></div>
            `
            : `<div class="mix-seg" style="width:100%; background:var(--border-color)"></div>`;

        const mixItems = [
            `<span><i class="dot blue"></i>回复 <strong>${act.replies}</strong> (${act.totalActions > 0 ? pReply : "0.0"}%)</span>`,
            `<span><i class="dot white"></i>原创 <strong>${act.originalPosts}</strong> (${act.totalActions > 0 ? pPost : "0.0"}%)</span>`
        ];
        if (act.quotes > 0 || act.totalActions === 0) {
            mixItems.push(`<span><i class="dot green"></i>引用 <strong>${act.quotes}</strong> (${act.totalActions > 0 ? pQuote : "0.0"}%)</span>`);
        }
        if (act.retweets > 0) {
            mixItems.push(`<span><i class="dot amber"></i>转推 <strong>${act.retweets}</strong> (${pRt}%)</span>`);
        }
        $("actionMixLegend").innerHTML = mixItems.join("");

        const series = act.trendSeries || [];
        const maxBucketTotal = Math.max(1, ...series.map((d) => d.posts + d.quotes + d.replies + (d.threadReplies ?? 0)));
        const labelStep = series.length >= 40 ? 8 : series.length >= 28 ? 6 : series.length >= 20 ? 4 : 1;

        const chartEl = $("dailyTrendChart");
        chartEl.classList.toggle("is-scrollable", series.length > 12);
        chartEl.innerHTML = series
            .map((d, idx) => {
                const threadReplies = d.threadReplies ?? 0;
                const authored = d.posts + d.quotes + threadReplies;
                const tot = authored + d.replies;
                const isLast = idx === series.length - 1;
                const distToLast = series.length - 1 - idx;
                const showAxisLabel = isLast || (idx % labelStep === 0 && distToLast >= Math.ceil(labelStep / 2));
                const breakdownParts = [];
                if (d.replies > 0) breakdownParts.push(`回复 ${d.replies}`);
                if (d.posts > 0) breakdownParts.push(`原创 ${d.posts}`);
                if (d.quotes > 0) breakdownParts.push(`引用 ${d.quotes}`);
                if (threadReplies > 0) breakdownParts.push(`串推 ${threadReplies}`);
                const viewSeg = hasMetricSample(d, "viewSampleCount") ? ` · 浏览 ${fmtNum(d.views)}` : "";
                const engSeg = hasMetricSample(d, "engagementSampleCount") ? ` · 互动 ${fmtNum(d.engagements)}` : "";
                const tip = tot === 0
                    ? `${d.fullLabel} · 暂无动态`
                    : `${d.fullLabel} · ${breakdownParts.join(" / ")}${viewSeg}${engSeg}`;

                if (tot === 0) {
                    return `<div class="day-col" data-tip="${escapeHtml(tip)}" role="button" tabindex="0" title="${escapeHtml(tip)}">
                        <div class="day-bar-stack is-empty" style="height:3px"></div>
                        ${showAxisLabel ? `<span class="day-label">${escapeHtml(d.label)}</span>` : ""}
                    </div>`;
                }

                const totalHeightPct = Math.max(8, Math.round((tot / maxBucketTotal) * 100));
                const replyPartPct = Math.round((d.replies / tot) * 100);
                const postPartPct = 100 - replyPartPct;

                return `<div class="day-col" data-tip="${escapeHtml(tip)}" role="button" tabindex="0" title="${escapeHtml(tip)}">
                    <div class="day-bar-stack" style="height:${totalHeightPct}%">
                        <div class="bar-reply" style="height:${replyPartPct}%"></div>
                        <div class="bar-post" style="height:${postPartPct}%"></div>
                    </div>
                    ${showAxisLabel ? `<span class="day-label">${escapeHtml(d.label)}</span>` : ""}
                </div>`;
            })
            .join("");
    }

    /* ==================== 模块 2：推友双向互动率深度拆解（1帖=1次精准账本） ==================== */
    function syncInteractionFilterUI(inter) {
        const allPeers = Array.isArray(inter.peerStats) ? inter.peerStats.length : Number(inter.totalInteractedPeers || 0);
        const iOwe = Number(inter.inboundHeavyPeers || 0);
        const unreplied = Number(inter.unrepliedMyPostPeers || 0);
        const theyOwe = Number(inter.outboundHeavyPeers || 0);
        const mutual = Number(inter.balancedMutualPeers || 0);

        const allBtn = document.getElementById("pillAllPeersBtn");
        if (allBtn) allBtn.textContent = `全部 ${allPeers}`;
        $("pillIOweBtn").textContent = `待回访 ${iOwe}`;
        $("pillUnrepliedBtn").textContent = `待回复 ${unreplied}`;
        $("pillTheyOweBtn").textContent = `未回访我 ${theyOwe}`;
        $("pillMutualBtn").textContent = `互相评论 ${mutual}`;

        document.querySelectorAll("#interactionTypePills .pill-chip").forEach((chip) => {
            chip.classList.toggle("active", chip.dataset.relFilter === state.peerRelFilter);
        });

        // 左侧与底部「互动」导航红点：我没回谁 + 评论待回复（去重前优先提示有待办）
        const pendingBadgeCnt = iOwe > 0 ? iOwe : unreplied;
        const badge = $("navInteractBadge");
        badge.textContent = String(pendingBadgeCnt);
        badge.hidden = pendingBadgeCnt <= 0;
        const mBadge = $("mobileInteractBadge");
        mBadge.textContent = String(pendingBadgeCnt);
        mBadge.hidden = pendingBadgeCnt <= 0;
    }

    function renderInteractionsPanel(report) {
        const inter = report.interactions;
        $("interactionSummaryDesc").textContent =
            `${inter.totalInteractedPeers} 人 · 去评 ${inter.totalOutboundActions} · 来评 ${inter.totalInboundActions}`;

        syncInteractionFilterUI(inter);
        renderPeerList(report);
    }

    /* 互动状态 → 白话标签 + 颜色 + 主按钮（严格对齐四维定义：我没回谁 / 评论待回复 / 谁没回我 / 互相评论） */
    function getPeerStatusMeta(item, meUsername, activeFilter = "ALL") {
        const u = item.peer;
        const unreplied = Number(item.unrepliedInMyPostCount || 0);
        const hasPostComments = Number(item.myRepliesToPeer || 0) + Number(item.peerRepliesToMe || 0) > 0;
        const profileUrl = `https://x.com/${encodeURIComponent(u.username)}`;
        const replyCommentUrl = safeHttpUrl(item.latestUnrepliedTweetUrl, peerDialogueUrl(meUsername, u.username));

        if (!hasPostComments) {
            return { label: "仅引用", extraTag: "", tone: "blue", actionText: "主页", actionUrl: profileUrl, primary: false };
        }
        // 在「评论待回复」筛选下：聚焦别人评论了我但我还没回复的评论，主按钮直达该条评论
        if (activeFilter === "UNREPLIED_POST") {
            return {
                label: `待回复 ${Math.max(1, unreplied)}`,
                extraTag: item.reciprocityCode === "I_OWE" ? "待回访" : "",
                tone: "amber",
                actionText: "去回复",
                actionUrl: replyCommentUrl,
                primary: true
            };
        }
        // 在「我没回谁」筛选下（TA 来我推文下评论过，但我没去 TA 发布过的推文下评论）：主按钮直达 TA 发布过的最新推文去回评
        if (item.reciprocityCode === "I_OWE") {
            return {
                label: "待回访",
                extraTag: unreplied > 0 ? `待回 ${unreplied}` : "",
                tone: "amber",
                actionText: "去回访",
                actionUrl: peerLatestPostsUrl(u.username),
                primary: true
            };
        }
        if (item.reciprocityCode === "THEY_OWE") {
            return { label: "未回访我", extraTag: "", tone: "pink", actionText: "主页", actionUrl: profileUrl, primary: false };
        }
        // 互相评论（我去过 TA 推文下评论，TA 也来过我的推文下评论）：列表主按钮统一为去互动
        return {
            label: "互相评论",
            extraTag: unreplied > 0 ? `待回 ${unreplied}` : "",
            tone: "green",
            actionText: "去互动",
            actionUrl: peerLatestPostsUrl(u.username),
            primary: false
        };
    }

    function followRelationText(item, report) {
        const audit = report?.followAudit;
        const legacyStateKnown = audit && !/待核实|未知/.test(item.relationshipPortrait || "") &&
            (item.iFollowThem || !audit.followingsSampleTruncated) &&
            (item.theyFollowMe || !audit.followersSampleTruncated);
        const followStateKnown = typeof item.followStateKnown === "boolean" ? item.followStateKnown : Boolean(legacyStateKnown);
        if (!followStateKnown) return "关系待核";
        if (item.iFollowThem && item.theyFollowMe) return "互关";
        if (item.iFollowThem) return "TA未回关";
        if (item.theyFollowMe) return "关注了我";
        return "未互关";
    }

    function buildPeerDetailHtml(report, item, ambiguousHandle = false) {
        const me = report.profile;
        const peer = item.peer;
        const myRaw = Number(item.myRawCommentCount || 0);
        const peerRaw = Number(item.peerRawCommentCount || 0);
        const chips = [`@${escapeHtml(peer.username)}`];
        if (myRaw > item.myRepliesToPeer) {
            chips.push(`共回 TA <strong>${myRaw}</strong> 条`);
        }
        if (peerRaw > item.peerRepliesToMe) {
            chips.push(`共回我 <strong>${peerRaw}</strong> 条`);
        }
        if (Number(item.unrepliedInMyPostCount || 0) > 0) {
            chips.push(`待回复 <strong>${item.unrepliedInMyPostCount}</strong>`);
        }
        if (Number(item.peerInThreadRepliesOnTheirPost || 0) > 0) {
            chips.push(`帖内回你 ${item.peerInThreadRepliesOnTheirPost}`);
        }
        if (Number(item.myRepliesInMyOwnPost || 0) > 0) {
            chips.push(`帖内回 TA ${item.myRepliesInMyOwnPost}`);
        }
        if (Number(item.myQuotesOfPeer || 0) + Number(item.peerQuotesOfMe || 0) > 0) {
            chips.push(`引用 我${item.myQuotesOfPeer}/TA${item.peerQuotesOfMe}`);
        }

        const replyBtn =
            Number(item.unrepliedInMyPostCount || 0) > 0 && item.latestUnrepliedTweetUrl
                ? `<a class="x-blue-pill sm" href="${escapeHtml(safeHttpUrl(item.latestUnrepliedTweetUrl, peerDialogueUrl(me.username, peer.username)))}" target="_blank" rel="noopener noreferrer">回复评论 ↗</a>`
                : "";

        return `<div class="peer-detail" data-peer-detail="${escapeHtml(peer.username)}">
            <div class="peer-detail-chips">${chips.map((c) => `<span class="peer-detail-chip">${c}</span>`).join("")}</div>
            ${item.recentSnippet ? `<div class="snippet-quote">${escapeHtml(item.recentSnippet)}</div>` : ""}
            <div class="peer-detail-actions">
                ${replyBtn}
                ${ambiguousHandle ? "" : `<a class="x-outline-pill sm" href="${escapeHtml(peerLatestPostsUrl(peer.username))}" target="_blank" rel="noopener noreferrer">最新推文 ↗</a>
                <a class="x-outline-pill sm" href="${escapeHtml(peerDialogueUrl(me.username, peer.username))}" target="_blank" rel="noopener noreferrer">对话 ↗</a>`}
                ${peer.id || !ambiguousHandle ? `<a class="x-outline-pill sm" href="https://x.com/${peer.id ? "i/user/" + encodeURIComponent(peer.id) : encodeURIComponent(peer.username)}" target="_blank" rel="noopener noreferrer">主页 ↗</a>` : ""}
            </div>
        </div>`;
    }

    function emptyStateHtml(message, actions) {
        const btns = (actions || [])
            .map((a) => `<button type="button" class="x-outline-pill sm" data-go="${escapeHtml(a.action)}">${escapeHtml(a.text)}</button>`)
            .join("");
        return `<div class="empty-placeholder">
            <div>${escapeHtml(message)}</div>
            ${btns ? `<div class="empty-actions">${btns}</div>` : ""}
        </div>`;
    }

    function widerPeriodActions() {
        const acts = [];
        const order = ["HOURS_24", "HOURS_48", "WEEK", "MONTH"];
        const idx = order.indexOf(state.period);
        for (let i = Math.max(0, idx + 1); i < order.length; i++) {
            const pCode = order[i];
            if (isPeriodUnlocked(pCode) && PERIOD_META[pCode]) {
                acts.push({ text: `看${PERIOD_META[pCode].badgePrefix}`, action: `period:${pCode}` });
            }
        }
        if (acts.length === 0 && state.period !== "MONTH") {
            acts.push({ text: "解锁说明", action: "unlockGuide:open" });
        }
        return acts;
    }

    function updateUserListRows(listEl, markup) {
        const active = document.activeElement;
        const hadFocus = Boolean(active && listEl.contains(active));
        const activeRow = hadFocus ? active.closest("[data-list-key]") : null;
        const focusedKey = activeRow?.getAttribute("data-list-key");
        const controls = "a, button, [tabindex]";
        const focusIndex = activeRow ? Array.from(activeRow.querySelectorAll(controls)).indexOf(active) : -1;
        const oldIndex = activeRow ? Array.from(listEl.children).indexOf(activeRow) : 0;
        const oldRows = new Map(Array.from(listEl.children).map((row) => [row.getAttribute("data-list-key"), row]));
        const template = document.createElement("template");
        template.innerHTML = markup;
        const kept = new Set();
        let cursor = listEl.firstElementChild;
        for (const next of Array.from(template.content.children)) {
            const key = next.getAttribute("data-list-key");
            const previous = key ? oldRows.get(key) : null;
            const signature = next.outerHTML;
            let node = previous && previous._listMarkup === signature ? previous : next;
            if (previous && key === "more") {
                const button = previous.querySelector("button");
                const nextButton = next.querySelector("button");
                if (button && nextButton) {
                    button.textContent = nextButton.textContent;
                    node = previous;
                }
            }
            node._listMarkup = signature;
            if (node !== cursor) listEl.insertBefore(node, cursor);
            kept.add(node);
            cursor = node.nextElementSibling;
        }
        for (const row of Array.from(listEl.children)) {
            if (!kept.has(row)) row.remove();
        }
        if (hadFocus && document.activeElement !== active) {
            const row = Array.from(listEl.children).find((item) => item.getAttribute("data-list-key") === focusedKey);
            const fallback = listEl.children[Math.min(oldIndex, listEl.children.length - 1)];
            const target = row && (focusIndex >= 0 ? row.querySelectorAll(controls)[focusIndex] : row)
                || fallback?.querySelector(controls);
            target?.focus({ preventScroll: true });
        }
    }

    function renderPeerList(report) {
        const listEl = $("peerInteractionList");
        let list = [...(report.interactions.peerStats || [])];

        const activeFilter = ["ALL", "I_OWE", "UNREPLIED_POST", "THEY_OWE", "MUTUAL"].includes(state.peerRelFilter)
            ? state.peerRelFilter
            : "MUTUAL";
        if (activeFilter !== "ALL") {
            list = list.filter((p) => {
                if (activeFilter === "UNREPLIED_POST") {
                    return Number(p.unrepliedInMyPostCount || 0) > 0;
                }
                return p.reciprocityCode === activeFilter;
            });
        }
        if (state.peerSearchQuery) {
            const q = state.peerSearchQuery.replace(/^@/, "").toLowerCase();
            list = list.filter(
                (p) =>
                    p.peer.username.toLowerCase().includes(q) ||
                    (p.peer.displayName || "").toLowerCase().includes(q)
            );
        }

        const effectiveSort = state.peerSortBy || "intimacy";
        const peerLatestMs = (item) => {
            if (!item?.latestInteractionAt) return -1;
            const ms = Date.parse(item.latestInteractionAt);
            return Number.isFinite(ms) ? ms : -1;
        };
        list.sort((a, b) => {
            const blueFirst = Number(b.peer?.verified === true) - Number(a.peer?.verified === true);
            if (blueFirst !== 0) return blueFirst;
            const inboundLeadDiff =
                Number(Number(b.peerRepliesToMe || 0) > Number(b.myRepliesToPeer || 0)) -
                Number(Number(a.peerRepliesToMe || 0) > Number(a.myRepliesToPeer || 0));
            if (inboundLeadDiff !== 0) return inboundLeadDiff;
            const totalA = (a.myRepliesToPeer || 0) + (a.peerRepliesToMe || 0);
            const totalB = (b.myRepliesToPeer || 0) + (b.peerRepliesToMe || 0);
            const recencyDiff = peerLatestMs(b) - peerLatestMs(a);
            switch (effectiveSort) {
                case "recent":
                    return (
                        recencyDiff ||
                        (b.intimacyScore || 0) - (a.intimacyScore || 0) ||
                        totalB - totalA ||
                        (b.peerRepliesToMe || 0) - (a.peerRepliesToMe || 0)
                    );
                case "iOweFirst":
                    return (
                        (b.unrepliedInMyPostCount || 0) - (a.unrepliedInMyPostCount || 0) ||
                        (b.peerRepliesToMe - b.myRepliesToPeer) - (a.peerRepliesToMe - a.myRepliesToPeer) ||
                        b.peerRepliesToMe - a.peerRepliesToMe ||
                        recencyDiff
                    );
                case "theyOweFirst":
                    return (
                        (b.myRepliesToPeer - b.peerRepliesToMe) - (a.myRepliesToPeer - a.peerRepliesToMe) ||
                        b.myRepliesToPeer - a.myRepliesToPeer ||
                        recencyDiff
                    );
                case "outbound":
                    return b.myRepliesToPeer - a.myRepliesToPeer || totalB - totalA || recencyDiff;
                case "inbound":
                    return b.peerRepliesToMe - a.peerRepliesToMe || totalB - totalA || recencyDiff;
                default:
                    return (
                        (b.intimacyScore || 0) - (a.intimacyScore || 0) ||
                        totalB - totalA ||
                        (b.peerRepliesToMe || 0) - (a.peerRepliesToMe || 0) ||
                        recencyDiff
                    );
            }
        });

        if (list.length === 0) {
            const filterEmptyMsg = {
                ALL: "这个时间段内还没有互动记录",
                I_OWE: "暂无待回访推友",
                UNREPLIED_POST: "主推评论均已回复",
                THEY_OWE: "暂无未回访我的推友",
                MUTUAL: "当前窗口暂无双向互评推友，可切换「全部」查看单向互动"
            };
            const msg = state.peerSearchQuery
                ? `没有找到「${state.peerSearchQuery}」`
                : filterEmptyMsg[activeFilter] || "这个时间段内还没有互动记录";
            const inter = report.interactions || {};
            const totalPeersCount = (inter.peerStats || []).length;
            const altActions = [];
            if (activeFilter !== "ALL" && totalPeersCount > 0) {
                altActions.push({ text: `查看全部 ${totalPeersCount} 位互动推友`, action: "peerFilter:ALL" });
            } else if (activeFilter !== "I_OWE" && Number(inter.inboundHeavyPeers || 0) > 0) {
                altActions.push({ text: "查看待回访", action: "peerFilter:I_OWE" });
            } else if (activeFilter !== "THEY_OWE" && Number(inter.outboundHeavyPeers || 0) > 0) {
                altActions.push({ text: "查看未回访我的推友", action: "peerFilter:THEY_OWE" });
            } else if (activeFilter !== "MUTUAL" && Number(inter.balancedMutualPeers || 0) > 0) {
                altActions.push({ text: "看互相评论", action: "peerFilter:MUTUAL" });
            }
            const acts = state.peerSearchQuery
                ? [{ text: "清空搜索", action: "clearPeerSearch" }]
                : altActions.length > 0
                    ? altActions
                    : widerPeriodActions();
            listEl.innerHTML = emptyStateHtml(msg, acts);
            return;
        }

        const visiblePeers = list.slice(0, state.peerRenderLimit);
        const remainPeers = Math.max(0, list.length - visiblePeers.length);
        const meUsername = report.profile.username;
        const expandedKey = (state.expandedPeerUsername || "").toLowerCase();
        const countsByHandle = new Map();
        for (const { peer } of report.interactions.peerStats || []) {
            const handle = peer.username.toLowerCase();
            countsByHandle.set(handle, (countsByHandle.get(handle) || 0) + 1);
        }

        const rowsMarkup = visiblePeers
            .map((item) => {
                const u = item.peer;
                const handle = u.username.toLowerCase();
                const key = u.id ? `id:${u.id}` : handle;
                const isOpen = expandedKey === key;
                const ambiguousHandle = countsByHandle.get(handle) > 1;
                const meta = getPeerStatusMeta(item, meUsername, state.peerRelFilter);
                if (ambiguousHandle && activeFilter !== "UNREPLIED_POST") {
                    meta.actionText = "主页";
                    meta.actionUrl = u.id ? `https://x.com/i/user/${encodeURIComponent(u.id)}` : "";
                }
                const actionCls = meta.primary ? "x-blue-pill sm" : "x-outline-pill sm";
                const redundantPrimaryTag =
                    (activeFilter === "MUTUAL" && meta.label === "互相评论") ||
                    (activeFilter === "THEY_OWE" && meta.label === "未回访我") ||
                    (activeFilter === "I_OWE" && meta.label === "待回访");
                const mainTagHtml = redundantPrimaryTag
                    ? ""
                    : `<span class="mini-tag ${meta.tone}">${escapeHtml(meta.label)}</span>`;
                const extraTagHtml = meta.extraTag
                    ? `<span class="mini-tag amber">${escapeHtml(meta.extraTag)}</span>`
                    : "";
                return `<div class="peer-item ${isOpen ? "is-open" : ""}" data-list-key="${escapeHtml(key)}">
                    <div class="x-user-row clickable peer-row" data-peer-handle="${escapeHtml(u.username)}" data-peer-key="${escapeHtml(key)}" role="button" tabindex="0" aria-expanded="${isOpen}">
                        ${avatarHtml(u, "md")}
                        <div class="user-row-main">
                            <div class="peer-row-line">
                                <span class="peer-name" data-i18n-ignore>${escapeHtml(u.displayName)}</span>
                                ${verifiedSvgHtml(u.verified)}
                                <span class="user-handle-muted" data-i18n-ignore>@${escapeHtml(u.username)}${ambiguousHandle ? ` · ID ${escapeHtml(u.id || "—")}` : ""}</span>
                                ${mainTagHtml}
                                ${extraTagHtml}
                            </div>
                            <div class="peer-row-sub">
                                去评 <strong>${item.myRepliesToPeer}</strong> · 来评 <strong>${item.peerRepliesToMe}</strong> · ${escapeHtml(followRelationText(item, report))}
                            </div>
                        </div>
                        ${meta.actionUrl ? `<a class="${actionCls} peer-ext-link" href="${escapeHtml(meta.actionUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(meta.actionText)} ↗</a>` : ""}
                        <svg class="peer-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="M3.543 8.96l1.414-1.42L12 14.59l7.043-7.05 1.414 1.42L12 17.41 3.543 8.96z"/></svg>
                    </div>
                    ${isOpen ? buildPeerDetailHtml(report, item, ambiguousHandle) : ""}
                </div>`;
            })
            .join("");

        const loadMoreHtml = remainPeers > 0
            ? `<div class="list-load-more-row" data-list-key="more">
                   <button type="button" class="x-outline-pill sm" id="loadMorePeersBtn">加载更多（余 ${remainPeers}）</button>
               </div>`
            : "";

        updateUserListRows(listEl, rowsMarkup + loadMoreHtml);

        const moreBtn = $("loadMorePeersBtn");
        if (moreBtn) {
            moreBtn.onclick = () => {
                state.peerRenderLimit += LIST_LOAD_STEP;
                renderPeerList(state.report || report);
            };
        }

        listEl.querySelectorAll(".peer-ext-link, .peer-detail a").forEach((link) => {
            link.onclick = (e) => e.stopPropagation();
        });

        const toggleRow = (row) => {
            const handle = row.dataset.peerKey || row.dataset.peerHandle;
            state.expandedPeerUsername =
                (state.expandedPeerUsername || "").toLowerCase() === handle.toLowerCase() ? null : handle;
            renderPeerList(report);
        };
        listEl.querySelectorAll(".peer-row[data-peer-handle]").forEach((row) => {
            row.onclick = () => toggleRow(row);
            row.onkeydown = (e) => {
                if (e.target === row && (e.key === "Enter" || e.key === " ")) {
                    e.preventDefault();
                    toggleRow(row);
                }
            };
        });
    }

    /* ==================== 模块 3：未回关关系体检 ==================== */
    function getFilteredFollowItems(report) {
        const audit = report.followAudit;
        let source =
            state.followStateTab === "FAN_ONLY"
                ? audit.fansNotFollowedBackList
                : state.followStateTab === "MUTUAL"
                    ? audit.mutualList
                    : audit.notFollowingBackList;

        let items = [...(source || [])];
        if (state.followStateTab === "NOT_FOLLOWING_BACK" && state.unfollowCategory !== "ALL") {
            if (state.unfollowCategory === "WHITELIST") {
                items = items.filter((i) => state.whitelistSet.has(i.user.username.toLowerCase()));
            } else if (state.unfollowCategory === "SUGGEST_CLEAN") {
                items = items.filter(
                    (i) =>
                        !state.whitelistSet.has(i.user.username.toLowerCase()) &&
                        (i.auditCategory === "ZERO_INTERACTION" || i.auditCategory === "ONE_WAY_EFFORT")
                );
            } else {
                items = items.filter(
                    (i) =>
                        !state.whitelistSet.has(i.user.username.toLowerCase()) &&
                        i.auditCategory === state.unfollowCategory
                );
            }
        }

        if (state.followSearchQuery) {
            const q = state.followSearchQuery.replace(/^@/, "").toLowerCase();
            items = items.filter(
                (i) =>
                    i.user.username.toLowerCase().includes(q) ||
                    (i.user.displayName || "").toLowerCase().includes(q) ||
                    (i.user.bio || "").toLowerCase().includes(q)
            );
        }

        // 名单次序来自上游的最近关注顺序；旧缓存或补齐资料没有可靠次序时放在最后。
        const recentOrder = (item) =>
            Number.isInteger(item.relationshipOrder) && item.relationshipOrder >= 0
                ? item.relationshipOrder
                : Number.MAX_SAFE_INTEGER;
        items.sort((a, b) => {
            const blueFirst = Number(b.user.verified === true) - Number(a.user.verified === true);
            if (blueFirst !== 0) return blueFirst;
            const inboundLeadDiff =
                Number(Number(b.theirRepliesToMe || 0) > Number(b.myRepliesToThem || 0)) -
                Number(Number(a.theirRepliesToMe || 0) > Number(a.myRepliesToThem || 0));
            if (inboundLeadDiff !== 0) return inboundLeadDiff;
            const difference = state.followSortBy === "intimacy"
                ? Number(b.intimacyScore || 0) - Number(a.intimacyScore || 0)
                : state.followSortBy === "followers"
                    ? Number(b.user.followersCount || 0) - Number(a.user.followersCount || 0)
                    : 0;
            return difference || recentOrder(a) - recentOrder(b);
        });
        return items;
    }

    function computeEffectiveSuggestUnfollowCount(report) {
        const audit = report && report.followAudit ? report.followAudit : null;
        if (!audit) return 0;
        const notBackAll = audit.notFollowingBackList || [];
        return Math.max(
            0,
            notBackAll.filter(
                (i) =>
                    !state.whitelistSet.has(i.user.username.toLowerCase()) &&
                    (i.auditCategory === "ONE_WAY_EFFORT" || i.auditCategory === "ZERO_INTERACTION")
            ).length
        );
    }

    function renderFollowAuditPanel(report) {
        const audit = report.followAudit;
        const scopeLabel = audit.interactionScopeLabel || report.periodLabel;
        const notBackAll = audit.notFollowingBackList || [];
        const whitelistedInNotBack = notBackAll.filter((i) => state.whitelistSet.has(i.user.username.toLowerCase())).length;
        const effectiveSuggestCount = computeEffectiveSuggestUnfollowCount(report);

        const wlPill = $("whitelistPillBtn");
        if (wlPill) {
            wlPill.textContent = `已保留 ${whitelistedInNotBack}`;
            wlPill.hidden = whitelistedInNotBack === 0 && state.unfollowCategory !== "WHITELIST";
        }
        $("suggestCleanPillBtn").textContent = `建议取关 ${effectiveSuggestCount}`;

        const limitations = [];
        if (audit.followersSampleTruncated) limitations.push(`粉丝采样 ${fmtNum(audit.followersSampleCount)}`);
        if (audit.followingsSampleTruncated) limitations.push("关注未全量");
        const unknownFollowBackCount = Number(audit.unknownFollowBackCount || 0);
        const unknownFollowingCount = Number(audit.unknownFollowingCount || 0);
        const totalUnknown = unknownFollowBackCount + unknownFollowingCount;
        if (totalUnknown > 0) {
            limitations.push(`待核验 ${totalUnknown.toLocaleString("zh-CN")} 人`);
            if (state.storedMode) limitations.push("切回实时后可检查更新");
            else if (state.pendingRelationshipChecks === 0) limitations.push("请在名单更新后再核验");
        }
        $("followScopeNote").textContent = limitations.join(" · ");
        $("followScopeNote").hidden = limitations.length === 0;

        // An incomplete source list may omit members. An incomplete opposite list leaves sampled peers unresolved.
        const pendingBack = unknownFollowBackCount > 0 || (audit.followersSampleTruncated && audit.unknownFollowBackCount == null);
        const pendingFollowing = unknownFollowingCount > 0 || (audit.followingsSampleTruncated && audit.unknownFollowingCount == null);
        const partialBack = audit.followingsSampleTruncated || pendingBack;
        const partialFan = audit.followersSampleTruncated || pendingFollowing;
        const partialMutual = (audit.followersSampleTruncated && audit.followingsSampleTruncated) || pendingBack || pendingFollowing;
        const countLabel = (count, partial, pending) => {
            if (!Number.isInteger(count)) return "未确定";
            const exactCount = count.toLocaleString("zh-CN");
            if (!partial) return exactCount;
            if (count > 0) return `≥${exactCount}`;
            return pending ? "待核验" : "未确定";
        };
        const tabBtns = $("followStateTabs").querySelectorAll(".seg-tab");
        tabBtns[0].textContent = `TA未回关 ${countLabel(audit.notFollowingBackCount, partialBack, unknownFollowBackCount)}`;
        tabBtns[1].textContent = `我未回关 ${countLabel(audit.fansNotFollowedBackCount, partialFan, unknownFollowingCount)}`;
        tabBtns[2].textContent = `互关 ${countLabel(audit.mutualCount, partialMutual, totalUnknown)}`;

        $("unfollowCategoryPills").hidden = state.followStateTab !== "NOT_FOLLOWING_BACK";

        const items = getFilteredFollowItems(report);
        const listEl = $("followAuditList");

        if (items.length === 0) {
            const isOutgoing = state.followStateTab === "NOT_FOLLOWING_BACK";
            const isFan = state.followStateTab === "FAN_ONLY";
            const pending = isOutgoing ? unknownFollowBackCount : isFan ? unknownFollowingCount : totalUnknown;
            const partial = isOutgoing ? partialBack : isFan ? partialFan : partialMutual;
            let emptyMsg;
            if (state.followSearchQuery) emptyMsg = `没有找到「${state.followSearchQuery}」`;
            else if (isOutgoing && state.unfollowCategory !== "ALL") emptyMsg = "当前筛选暂无已确认账号";
            else if (pending > 0) emptyMsg = `此分类暂无已确认账号，另有 ${pending.toLocaleString("zh-CN")} 人的关系待核验`;
            else if (partial) emptyMsg = "名单尚未收齐，此分类暂无已确认账号";
            else emptyMsg = isOutgoing ? "当前分类暂无账号" : isFan ? "关注你的人均已回关" : "暂无互关账号";
            const emptyActs = state.followSearchQuery
                ? [{ text: "清空搜索", action: "clearFollowSearch" }]
                : state.followStateTab === "NOT_FOLLOWING_BACK" && state.unfollowCategory !== "ALL"
                    ? [{ text: "查看全部", action: "followCat:ALL" }]
                    : [];
            listEl.innerHTML = emptyStateHtml(emptyMsg, emptyActs);
            return;
        }

        const visibleItems = items.slice(0, state.followRenderLimit);
        const remainItems = Math.max(0, items.length - visibleItems.length);

        const rowsHtml = visibleItems
            .map((item) => {
                const u = item.user;
                const uKey = u.username.toLowerCase();
                const isKept = state.whitelistSet.has(uKey);
                const tagClass = item.auditCategory === "ONE_WAY_EFFORT" || item.auditCategory === "ZERO_INTERACTION"
                    ? "pink"
                    : item.auditCategory === "LOYAL_FAN" || item.auditCategory === "CORE_MUTUAL"
                        ? "green"
                        : item.auditCategory === "BIG_ACCOUNT"
                            ? "amber"
                            : "blue";
                const safeProfileHref = escapeHtml(
                    safeHttpUrl(item.profileUrl, `https://x.com/${encodeURIComponent(u.username)}`)
                );
                const isNotBack = item.relationState === "NOT_FOLLOWING_BACK";
                const actionText = isNotBack ? "去取关" : item.relationState === "FAN_ONLY" ? "去回关" : "主页";
                const actionCls = isNotBack || item.relationState === "FAN_ONLY" ? "x-blue-pill sm" : "x-outline-pill sm";

                let sideBtn = "";
                if (isNotBack) {
                    sideBtn = `<button type="button" class="mini-mark-btn ${isKept ? "is-active" : ""}" data-wl-toggle="${escapeHtml(u.username)}" title="${isKept ? "取消保留" : "保留后不再出现在「建议取关」里"}">${isKept ? "已保留" : "保留"}</button>`;
                }

                const hasPeriodInteract = Number(item.myRepliesToThem || 0) + Number(item.theirRepliesToMe || 0) > 0;
                const interactSeg = hasPeriodInteract
                    ? `去评 <strong>${item.myRepliesToThem}</strong> · 来评 <strong>${item.theirRepliesToMe}</strong>`
                    : "无互动";

                return `<div class="x-user-row" data-list-key="${escapeHtml(u.username.toLowerCase())}">
                    ${avatarHtml(u, "md")}
                    <div class="user-row-main">
                        <div class="peer-row-line">
                            <span class="peer-name" data-i18n-ignore>${escapeHtml(u.displayName)}</span>
                            ${verifiedSvgHtml(u.verified)}
                            <span class="user-handle-muted" data-i18n-ignore>@${escapeHtml(u.username)}</span>
                            ${isKept ? "" : `<span class="mini-tag ${tagClass}" title="${escapeHtml(item.recommendation || "")}">${escapeHtml(item.auditLabel)}</span>`}
                        </div>
                        <div class="peer-row-sub">
                            粉丝 ${fmtNum(u.followersCount)} · ${interactSeg}
                        </div>
                        ${u.bio ? `<div class="bio-one-line" data-i18n-ignore>${escapeHtml(u.bio)}</div>` : ""}
                    </div>
                    <div class="peer-row-actions">
                        ${sideBtn}
                        <a class="${actionCls} follow-go-link" data-open-follow="${escapeHtml(u.username)}" data-follow-action="${isNotBack || item.relationState === "FAN_ONLY" ? "manage" : "profile"}" href="${safeProfileHref}" target="_blank" rel="noopener noreferrer">${actionText} ↗</a>
                    </div>
                </div>`;
            })
            .join("");

        const loadMoreHtml = remainItems > 0
            ? `<div class="list-load-more-row" data-list-key="more">
                   <button type="button" class="x-outline-pill sm" id="loadMoreFollowBtn">加载更多（余 ${remainItems}）</button>
               </div>`
            : "";

        updateUserListRows(listEl, rowsHtml + loadMoreHtml);

        listEl.querySelectorAll("[data-wl-toggle]").forEach((btn) => {
            btn.onclick = () => {
                const key = btn.dataset.wlToggle.toLowerCase();
                if (state.whitelistSet.has(key)) {
                    state.whitelistSet.delete(key);
                    showToast(`已取消保留 @${btn.dataset.wlToggle}`);
                } else {
                    state.whitelistSet.add(key);
                    showToast(`已保留 @${btn.dataset.wlToggle}，不再建议取关`);
                }
                saveWhitelistSet();
                renderFollowAuditPanel(report);
                renderRightSidebar(report);
            };
        });

        // 外部页面打开不代表关系变更成功；待办保留，等待后续关系数据确认。
        listEl.querySelectorAll("[data-open-follow]").forEach((link) => {
            const isGoAction = link.dataset.followAction === "manage";
            if (!isGoAction) return;
            link.onclick = () => {
                showToast("已打开 X，请在那里完成操作");
            };
        });

        const moreFollowBtn = $("loadMoreFollowBtn");
        if (moreFollowBtn) {
            moreFollowBtn.onclick = () => {
                state.followRenderLimit += LIST_LOAD_STEP;
                renderFollowAuditPanel(state.report || report);
            };
        }
    }

    /* ==================== 模块 4：内容助手与内容表现 ==================== */

    function contentAssistantHeaders() {
        return { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" };
    }

    async function contentAssistantRequest(path, payload, timeoutMs = 30000) {
        const options = payload === undefined
            ? { cache: "no-store" }
            : { method: "POST", cache: "no-store", headers: contentAssistantHeaders(), body: JSON.stringify(payload) };
        const { resp, body } = await requestJsonWithin("/api/content-assistant" + path, options, timeoutMs);
        if (resp.status === 401) {
            await fetchServerAndAuthStatus();
        }
        if (!resp.ok) {
            const error = new Error(body.message || body.error || `操作未完成（HTTP ${resp.status}）`);
            error.httpStatus = resp.status;
            error.body = body;
            throw error;
        }
        return body;
    }

    async function assistantPublishRequestId(ownerId, operation, ...content) {
        const identity = new TextEncoder().encode(JSON.stringify([ownerId, operation, ...content]));
        const digest = await crypto.subtle.digest("SHA-256", identity);
        return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
    }

    function assistantPublishNotice(receipt) {
        switch (receipt?.publicationMode) {
            case "QUOTE": return "已引用原帖发布";
            case "QUOTE_LINK": return "已发布，并附上原帖链接";
            case "STANDALONE_FALLBACK": return "原帖引用受限，已作为独立推文发布";
            default: return "发布已确认";
        }
    }

    async function publishAssistantDraftNow(draftId, quoteSource) {
        const isCurrent = captureUserScope();
        if (!isCurrent()) return;
        const draft = await saveAssistantDraftEditor();
        if (!isCurrent() || draft?.id !== draftId) return;
        try {
            const key = await assistantPublishRequestId(stableUserId(state.authStatus.user), "draft", draftId, draft.version, quoteSource);
            if (!isCurrent()) return;
            const receipt = await runAssistantMutation("publish-now", `/drafts/${encodeURIComponent(draftId)}/publish-now`,
                { version: draft.version, quoteSource, clientRequestId: key }, 60000);
            if (receipt && isCurrent()) showToast(assistantPublishNotice(receipt));
        } catch (error) {
            if (isCurrent()) setContentAssistantError(formatLocalizedFetchError(error, "发布请求未完成"));
        }
    }

    async function publishRepostPreviewItem(id) {
        const isCurrentUser = captureUserScope();
        if (!isCurrentUser() || !id || repostPreviewState.publishingIds.has(id)) return;
        const found = repostPreviewState.items.find((item) => String(item.material?.id || "") === id);
        if (!found || quoteDeliveryStatus(found.decision)) return;
        const comment = getRepostItemCommentText(id).trim();
        if (!comment) { showToast("转发附文不能为空"); return; }
        const quoteId = resolveRepostQuoteTweetId(found?.material || { id });
        if (!quoteId) { showToast("该素材缺少有效原帖推文 ID，无法引用转发"); return; }
        const attemptKey = quoteAttemptKey(found.material);
        const pending = repostPreviewState.pendingQuotes.get(attemptKey);
        if (pending) {
            markQuoteAwaitingReceipt(found, pending);
            renderRepostPreviewTimeline(id);
            return;
        }
        const sourceUrl = found ? resolveRepostMaterialUrl(found.material) : "";
        const beforeSend = found.decision;
        const sourceKey = repostPreviewState.sourceKey;
        const token = repostPreviewState.requestToken;
        const isCurrent = () => isCurrentUser() && token === repostPreviewState.requestToken;
        const currentCard = () => isCurrentUser() && sourceKey === repostPreviewState.sourceKey
            && repostPreviewState.items.find(item => String(item.material?.id || "") === id);
        const submitting = repostPreviewState.publishingIds;
        submitting.add(id);
        let attempt;
        renderRepostPreviewTimeline(id);
        try {
            const key = await assistantPublishRequestId(stableUserId(state.authStatus.user), "manual-quote", id, quoteId, comment);
            if (!isCurrent()) return;
            attempt = { key, comment, afterRevision: Number(beforeSend?.receiptRevision || 0) };
            repostPreviewState.pendingQuotes.set(attemptKey, attempt);
            const receipt = await contentAssistantRequest(`/sources/${encodeURIComponent(sourceKey)}/publish-quote`,
                { materialId: id, sourceUrl, commentText: comment, clientRequestId: key }, 60000);
            if (!receipt?.publishedTweetId && !receipt?.publishedTweetUrl) throw new Error("发布结果待核实");
            forgetQuoteAttempt(attemptKey, attempt);
            const currentItem = currentCard();
            if (currentItem) {
                currentItem.decision = {
                    ...currentItem.decision, evaluated: true, wouldAutoRepost: true,
                    persistedStatus: "PUBLISHED", decisionCode: "PUBLISHED", receiptRevision: Number(receipt.receiptRevision || 0),
                    publishedTweetUrl: receipt?.publishedTweetUrl || currentItem.decision?.publishedTweetUrl || "",
                    publicationMode: receipt?.publicationMode, publishedText: receipt?.text, commentText: comment
                };
            }
            if (isCurrent()) showToast(assistantPublishNotice(receipt));
        } catch (error) {
            const currentItem = currentCard();
            const ambiguous = !error.httpStatus || error.httpStatus >= 500;
            if (attempt && !ambiguous) {
                forgetQuoteAttempt(attemptKey, attempt);
                if (currentItem?.decision?.pendingRequest?.key === attempt.key) currentItem.decision = beforeSend;
            }
            if (attempt && currentItem && (ambiguous || error.httpStatus === 409)) {
                if (ambiguous) {
                    markQuoteAwaitingReceipt(currentItem, attempt);
                    renderRepostPreviewTimeline(id);
                }
                try { await refreshQuoteReceipt(id); } catch (_) { /* Keep the displayed receipt until a read succeeds. */ }
            }
            if (isCurrent() && !quoteDeliveryStatus(currentItem?.decision)) showToast(formatLocalizedFetchError(error, "引用发布请求未完成"));
        } finally {
            submitting.delete(id);
            if (currentCard()) renderRepostPreviewTimeline(id);
        }
    }

    function quoteAttemptKey(material) {
        return JSON.stringify([stableUserId(state.authStatus?.user), resolveRepostQuoteTweetId(material) || material.id]);
    }

    function forgetQuoteAttempt(key, attempt) {
        const active = repostPreviewState.pendingQuotes.get(key);
        if (active?.key === attempt.key && active.afterRevision === attempt.afterRevision) repostPreviewState.pendingQuotes.set(key, null);
    }

    function markQuoteAwaitingReceipt(row, attempt) {
        if (quoteDeliveryStatus(row.decision) && Number(row.decision?.receiptRevision || 0) > attempt.afterRevision) {
            row.decision = { ...row.decision, pendingRequest: attempt };
            return;
        }
        row.decision = { ...row.decision, persistedStatus: "UNCERTAIN", decisionCode: "UNCERTAIN",
            receiptRevision: attempt.afterRevision, pendingRequest: attempt, publishedText: "", commentText: attempt.comment };
    }

    async function refreshQuoteReceipt(id) {
        const token = repostPreviewState.requestToken;
        const account = captureUserScope();
        const entry = () => repostPreviewState.items.find(item => String(item.material?.id || "") === id);
        const original = entry();
        if (!original) return;
        const before = original.decision;
        const key = quoteAttemptKey(original.material);
        const attempt = repostPreviewState.pendingQuotes.get(key);
        const result = await contentAssistantRequest(`/sources/${encodeURIComponent(repostPreviewState.sourceKey)}/repost-preview`,
            { materialId: id, receiptOnly: true, ...(attempt ? { clientRequestId: attempt.key } : {}) });
        if (!account() || token !== repostPreviewState.requestToken || String(result?.material?.id) !== id || !result.preview) return;
        if (String(result.owner?.accountId || "") !== stableUserId(state.authStatus?.user)) return;
        const row = entry();
        if (!row) return;
        const next = result.preview;
        const active = repostPreviewState.pendingQuotes.get(key);
        if (active && (active.key !== attempt?.key || active.afterRevision !== attempt?.afterRevision)) return;
        let pendingRequest = null;
        if (attempt) {
            const matched = Number(next.requestReceiptRevision || 0) > attempt.afterRevision;
            if (!matched && !(quoteDeliveryStatus(next) && Number(next.receiptRevision || 0) > attempt.afterRevision)) return;
            if (matched) forgetQuoteAttempt(key, attempt);
            else pendingRequest = active;
        }
        const previous = row.decision?.pendingRequest && !active ? null : row.decision;
        row.decision = preservePublishedQuote(previous, { ...row.decision, ...next, pendingRequest,
            persistedStatus: next.persistedStatus || "", decisionCode: next.decisionCode || "PENDING_EVAL" }, row.decision !== before);
        renderRepostPreviewTimeline(id);
    }

    function setContentAssistantError(message) {
        const el = $("contentAssistantError");
        if (!el) return;
        el.textContent = String(message || "");
        el.hidden = !message;
    }

    const PENDING_QUOTE_STORAGE_PREFIX = "xops_pending_quote:";
    const repostPreviewState = {
        requestToken: 0,
        sourceKey: "",
        focusedMaterialId: "",
        filterMode: "ALL",
        timelineDayStart: 0,
        rewriteTimelineLoaded: false,
        rewriteDayTimer: null,
        polishDrawerOpen: false,
        evaluatingAccountOnce: false,
        cooldown: null,
        owner: null,
        source: null,
        items: [],
        overrides: {},
        evaluatingIds: new Set(),
        rewritingIds: new Set(),
        publishingIds: new Set(),
        pendingQuotes: browserRecords(PENDING_QUOTE_STORAGE_PREFIX),
        expandedIds: new Set(),
        editedComments: sourcePreviewDrafts("quote"),
        editedRewrites: sourcePreviewDrafts("rewrite")
    };

    function sourcePreviewDrafts(kind) {
        const records = browserRecords("xops_source_draft:");
        function context(id) {
            const owner = String(repostPreviewState.owner?.accountId || "");
            if (!owner || owner !== stableUserId(state.authStatus?.user)) return null;
            const row = repostPreviewState.items.find(item => String(item.material?.id || "") === id);
            if (!row) return null;
            const m = row.material;
            const post = resolveRepostQuoteTweetId(m) || id;
            const author = m.authorId || repostPreviewState.source?.authorId || repostPreviewState.sourceKey;
            const key = JSON.stringify([owner, author, post, kind]);
            const original = m.sourceFingerprint || JSON.stringify([m.text, m.quotedText, m.articleText]);
            return { key, original, row, owner };
        }
        function record(ctx) {
            if (!ctx) return null;
            const stored = records.get(ctx.key);
            return stored?.original === ctx.original && typeof stored.text === "string" ? stored : null;
        }
        return {
            get: id => record(context(id))?.text,
            has: id => record(context(id)) !== null,
            reference: id => record(context(id))?.reference,
            set(id, text) {
                const ctx = context(id);
                if (!ctx) return;
                const entry = { original: ctx.original, text };
                if (kind === "rewrite") {
                    const rw = ctx.row.rewrite;
                    entry.reference = record(ctx)?.reference || {
                        ownerAccountId: ctx.owner, materialId: id, recordVersion: Number(rw?.savedDraftVersion || 0),
                        ...(rw?.previewId ? { previewId: rw.previewId } : {})
                    };
                }
                records.set(ctx.key, entry);
            },
            delete(id) {
                const ctx = context(id);
                if (ctx && record(ctx)) records.set(ctx.key, null);
            },
            clear() { records.clearMemory(); }
        };
    }

    function browserRecords(prefix) {
        const unsaved = new Map();
        return {
            get(key) {
                if (unsaved.has(key)) return unsaved.get(key);
                try { return JSON.parse(localStorage.getItem(prefix + key)); } catch (_) { return null; }
            },
            set(key, entry) {
                try {
                    if (entry === null) localStorage.removeItem(prefix + key);
                    else localStorage.setItem(prefix + key, JSON.stringify(entry));
                    unsaved.delete(key);
                } catch (_) {
                    unsaved.set(key, entry);
                }
            },
            clearMemory() { unsaved.clear(); }
        };
    }

    const DEFAULT_GENERATION_TARGET_SPEC = "chatgpt|gpt-6.2-astra";
    const DEFAULT_GENERATION_EFFORT = "medium";
    const KNOWN_REASONING_EFFORTS = new Set(["none", "minimal", "low", "medium", "high", "xhigh", "ultra", "max"]);
    const REASONING_EFFORT_OPTIONS = [
        { value: "", label: "默认强度" },
        { value: "minimal", label: "极速 minimal" },
        { value: "low", label: "低强度 low" },
        { value: "medium", label: "中强度 medium" },
        { value: "high", label: "高强度 high" },
        { value: "xhigh", label: "极高强度 xhigh" },
        { value: "none", label: "关闭推理 none" }
    ];
    const PROVIDER_MODEL_CATALOG = {
        chatgpt: ["gpt-6.2-astra", "gpt-6.2-sol", "gpt-6.1-terra", "gpt-6-luna", "gpt-5.4"],
        openai: ["gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra", "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.5", "gpt-5.4", "gpt-5.4-mini"],
        google: ["gemini-4-neon", "gemini-4-carbon", "gemini-4-argon", "gemini-4-hydrogen"],
        minimax: ["MiniMax-M2.7"],
        deepseek: ["deepseek-chat", "deepseek-reasoner"]
    };
    const DEFAULT_MODEL_BY_PROVIDER = {
        chatgpt: "gpt-6.2-astra",
        openai: "gpt-6.2-sol",
        google: "gemini-4-neon",
        minimax: "MiniMax-M2.7",
        deepseek: "deepseek-chat"
    };
    const DEFAULT_EFFORT_BY_PROVIDER = {
        chatgpt: "medium",
        openai: "",
        google: "",
        minimax: "",
        deepseek: ""
    };
    const GENERATION_TARGET_OPTIONS = [
        { group: "ChatGPT 订阅额度（PI Agent）", value: "chatgpt|gpt-6.2-astra", label: "ChatGPT 订阅 · gpt-6.2-astra" },
        { group: "ChatGPT 订阅额度（PI Agent）", value: "chatgpt|gpt-6.2-sol", label: "ChatGPT 订阅 · gpt-6.2-sol" },
        { group: "ChatGPT 订阅额度（PI Agent）", value: "chatgpt|gpt-6.1-terra", label: "ChatGPT 订阅 · gpt-6.1-terra" },
        { group: "ChatGPT 订阅额度（PI Agent）", value: "chatgpt|gpt-6-luna", label: "ChatGPT 订阅 · gpt-6-luna" },
        { group: "OpenAI API（官方）", value: "openai|gpt-6.2-sol", label: "OpenAI API · gpt-6.2-sol" },
        { group: "OpenAI API（官方）", value: "openai|gpt-6.2-astra", label: "OpenAI API · gpt-6.2-astra" },
        { group: "OpenAI API（官方）", value: "openai|gpt-6.1-terra", label: "OpenAI API · gpt-6.1-terra" },
        { group: "OpenAI API（官方）", value: "openai|gpt-6.1-sol", label: "OpenAI API · gpt-6.1-sol" },
        { group: "OpenAI API（官方）", value: "openai|gpt-6-luna", label: "OpenAI API · gpt-6-luna" },
        { group: "OpenAI API（官方）", value: "openai|gpt-5.4", label: "OpenAI API · gpt-5.4" },
        { group: "Google Gemini API", value: "google|gemini-4-neon", label: "Gemini API · gemini-4-neon" },
        { group: "Google Gemini API", value: "google|gemini-4-carbon", label: "Gemini API · gemini-4-carbon" },
        { group: "Google Gemini API", value: "google|gemini-4-argon", label: "Gemini API · gemini-4-argon" },
        { group: "Google Gemini API", value: "google|gemini-4-hydrogen", label: "Gemini API · gemini-4-hydrogen" },
        { group: "MiniMax / DeepSeek API", value: "minimax|MiniMax-M2.7", label: "MiniMax API · MiniMax-M2.7" },
        { group: "MiniMax / DeepSeek API", value: "deepseek|deepseek-chat", label: "DeepSeek API · deepseek-chat" },
        { group: "MiniMax / DeepSeek API", value: "deepseek|deepseek-reasoner", label: "DeepSeek API · deepseek-reasoner" }
    ];

    function providerSupportsEffort(provider) {
        return ["chatgpt", "openai", "google"].includes(String(provider || "").toLowerCase());
    }

    function splitModelAndEffort(rawModel) {
        const trimmed = String(rawModel || "").trim();
        if (!trimmed) return { modelId: "", effort: "" };
        const parts = trimmed.split(/[\s@:]+/);
        if (parts.length >= 2) {
            const tail = parts[parts.length - 1].toLowerCase();
            if (KNOWN_REASONING_EFFORTS.has(tail)) {
                return { modelId: parts.slice(0, -1).join("-").trim(), effort: tail };
            }
        }
        return { modelId: trimmed, effort: "" };
    }

    function composeModelWithEffort(provider, baseModel, effort) {
        const prov = String(provider || "chatgpt").toLowerCase();
        const split = splitModelAndEffort(baseModel);
        const cleanModel = split.modelId || DEFAULT_MODEL_BY_PROVIDER[prov] || "gpt-6.2-astra";
        const cleanEffort = effort !== undefined
            ? String(effort || "").trim().toLowerCase()
            : split.effort;
        if (providerSupportsEffort(prov) && cleanEffort && KNOWN_REASONING_EFFORTS.has(cleanEffort)) {
            return `${cleanModel} ${cleanEffort}`;
        }
        return cleanModel;
    }

    function parseGenerationTargetSpec(rawSpec, explicitEffort) {
        const spec = String(rawSpec || state.assistantGenerationTargetSpec || DEFAULT_GENERATION_TARGET_SPEC).trim();
        const sep = spec.indexOf("|");
        if (sep < 0) {
            const eff = explicitEffort !== undefined ? explicitEffort : (state.assistantGenerationEffort ?? DEFAULT_GENERATION_EFFORT);
            return {
                generationProvider: "chatgpt",
                generationModel: composeModelWithEffort("chatgpt", "gpt-6.2-astra", eff)
            };
        }
        const rawProv = spec.slice(0, sep).trim().toLowerCase();
        const provider = ["chatgpt", "openai", "google", "minimax", "deepseek"].includes(rawProv) ? rawProv : "chatgpt";
        const rawModelPart = spec.slice(sep + 1).trim();
        const split = splitModelAndEffort(rawModelPart);
        const baseModel = split.modelId || DEFAULT_MODEL_BY_PROVIDER[provider] || "gpt-6.2-astra";
        const resolvedEffort = explicitEffort !== undefined ? String(explicitEffort || "").trim().toLowerCase() : split.effort;
        return {
            generationProvider: provider,
            generationModel: composeModelWithEffort(provider, baseModel, resolvedEffort)
        };
    }

    function renderGenerationTargetOptionsHtml(selectedSpec) {
        const rawActive = String(selectedSpec || state.assistantGenerationTargetSpec || DEFAULT_GENERATION_TARGET_SPEC).trim();
        const sep = rawActive.indexOf("|");
        const activeProv = sep >= 0 ? rawActive.slice(0, sep).trim().toLowerCase() : "chatgpt";
        const activeBaseModel = sep >= 0 ? (splitModelAndEffort(rawActive.slice(sep + 1)).modelId || DEFAULT_MODEL_BY_PROVIDER[activeProv]) : "gpt-6.2-astra";
        const active = `${activeProv}|${activeBaseModel}`;
        const groups = new Map();
        for (const opt of GENERATION_TARGET_OPTIONS) {
            if (!groups.has(opt.group)) groups.set(opt.group, []);
            groups.get(opt.group).push(opt);
        }
        let html = "";
        for (const [grpLabel, items] of groups.entries()) {
            html += `<optgroup label="${escapeHtml(grpLabel)}">${items.map((it) =>
                `<option value="${escapeHtml(it.value)}"${it.value === active ? " selected" : ""}>${escapeHtml(it.label)}</option>`
            ).join("")}</optgroup>`;
        }
        return html;
    }

    function renderReasoningEffortOptionsHtml(selectedEffort, provider = "chatgpt") {
        const active = providerSupportsEffort(provider) ? String(selectedEffort ?? state.assistantGenerationEffort ?? "").trim().toLowerCase() : "";
        return REASONING_EFFORT_OPTIONS.map((opt) =>
            `<option value="${escapeHtml(opt.value)}"${opt.value === active ? " selected" : ""}>${escapeHtml(opt.label)}</option>`
        ).join("");
    }

    function syncEffortSelectAvailability(effortEl, provider, nextEffort) {
        if (!effortEl) return;
        const supported = providerSupportsEffort(provider);
        effortEl.disabled = !supported;
        const targetVal = supported ? String(nextEffort ?? "").trim().toLowerCase() : "";
        if (!effortEl.options || [...effortEl.options].some((o) => o.value === targetVal)) {
            effortEl.value = targetVal;
        } else {
            effortEl.value = "";
        }
    }

    function syncGenerationTargetSelects(nextSpec, nextEffort) {
        const raw = String(nextSpec || state.assistantGenerationTargetSpec || DEFAULT_GENERATION_TARGET_SPEC).trim();
        const sep = raw.indexOf("|");
        const prov = sep >= 0 ? raw.slice(0, sep).trim().toLowerCase() : "chatgpt";
        const split = sep >= 0 ? splitModelAndEffort(raw.slice(sep + 1)) : { modelId: "gpt-6.2-astra", effort: "" };
        const baseSpec = `${prov}|${split.modelId || DEFAULT_MODEL_BY_PROVIDER[prov] || "gpt-6.2-astra"}`;
        const prevProv = (state.assistantGenerationTargetSpec || "").split("|")[0] || "chatgpt";
        let effort = nextEffort !== undefined
            ? String(nextEffort || "").trim().toLowerCase()
            : (split.effort || (prov !== prevProv ? (DEFAULT_EFFORT_BY_PROVIDER[prov] ?? "") : (state.assistantGenerationEffort ?? "")));
        if (!providerSupportsEffort(prov)) effort = "";
        state.assistantGenerationTargetSpec = baseSpec;
        state.assistantGenerationEffort = effort;
        for (const id of ["assistantGenTargetSelect", "assistantOptimizeGenTargetSelect"]) {
            const el = $(id);
            if (el && (!el.options || [...el.options].some((o) => o.value === baseSpec)) && el.value !== baseSpec) {
                el.value = baseSpec;
            }
        }
        for (const id of ["assistantGenEffortSelect", "assistantOptimizeEffortSelect"]) {
            syncEffortSelectAvailability($(id), prov, effort);
        }
    }

    const STAGE_MODEL_CONTROL_IDS = {
        repost: {
            provider: "assistantRepostProvider",
            model: "assistantRepostModelSelect",
            effort: "assistantRepostEffortSelect",
            custom: "assistantRepostModel"
        },
        generation: {
            provider: "assistantGenerationProvider",
            model: "assistantGenerationModelSelect",
            effort: "assistantGenerationEffortSelect",
            custom: "assistantGenerationModel"
        }
    };

    function syncStageModelControls(stage, provider, rawModelSpec) {
        const ids = STAGE_MODEL_CONTROL_IDS[stage];
        if (!ids) return;
        const safeProv = ["chatgpt", "openai", "google", "minimax", "deepseek"].includes(String(provider || "").toLowerCase())
            ? String(provider).toLowerCase() : "chatgpt";
        const split = splitModelAndEffort(rawModelSpec);
        const baseModel = split.modelId || DEFAULT_MODEL_BY_PROVIDER[safeProv] || "gpt-6.2-astra";
        const effort = providerSupportsEffort(safeProv)
            ? (split.effort || (rawModelSpec ? "" : (DEFAULT_EFFORT_BY_PROVIDER[safeProv] ?? "")))
            : "";
        if ($(ids.provider)) {
            $(ids.provider).value = safeProv;
        }
        const modelSel = $(ids.model);
        const customInput = $(ids.custom);
        const catalog = PROVIDER_MODEL_CATALOG[safeProv] || ["gpt-6.2-astra"];
        const inCatalog = catalog.includes(baseModel);
        if (modelSel) {
            modelSel.innerHTML = catalog.map((m) =>
                `<option value="${escapeHtml(m)}"${m === baseModel ? " selected" : ""}>${escapeHtml(m)}</option>`
            ).join("") + `<option value="__custom__"${!inCatalog ? " selected" : ""}>自定义模型…</option>`;
            modelSel.value = inCatalog ? baseModel : "__custom__";
        }
        syncEffortSelectAvailability($(ids.effort), safeProv, effort);
        const composed = composeModelWithEffort(safeProv, baseModel, effort);
        if (customInput) {
            customInput.hidden = inCatalog;
            customInput.value = inCatalog ? composed : baseModel;
        }
    }

    function readStageModelControls(stage) {
        const ids = STAGE_MODEL_CONTROL_IDS[stage];
        if (!ids) return { provider: "chatgpt", model: "gpt-6.2-astra medium" };
        const provRaw = ($(ids.provider)?.value || "chatgpt").trim().toLowerCase();
        const prov = ["chatgpt", "openai", "google", "minimax", "deepseek"].includes(provRaw) ? provRaw : "chatgpt";
        const selVal = $(ids.model)?.value || "";
        const rawBase = selVal === "__custom__"
            ? ($(ids.custom)?.value || "").trim()
            : (selVal || ($(ids.custom)?.value || "").trim());
        const effort = $(ids.effort)?.value ?? "";
        const composed = composeModelWithEffort(prov, rawBase || DEFAULT_MODEL_BY_PROVIDER[prov], effort);
        if ($(ids.custom) && selVal !== "__custom__") {
            $(ids.custom).value = composed;
        }
        return { provider: prov, model: composed };
    }

    function syncRepostSettingsModelControls(provider, rawModelSpec) {
        return syncStageModelControls("repost", provider, rawModelSpec);
    }

    function readRepostSettingsComposedModel() {
        return readStageModelControls("repost");
    }

    function applySavedGenerationDefault(dashboard) {
        if (state.assistantGenerationTargetPinned) return;
        const mod = dashboard?.settings?.moduleConfigs?.generation || {};
        const routing = dashboard?.providerDefaults?.stageRouting?.generation || {};
        const prov = String(mod.provider || routing.defaultProvider || "").toLowerCase();
        const model = String(mod.model || routing.defaultModel || "").trim();
        if (!prov || !model) return;
        const split = splitModelAndEffort(model);
        syncGenerationTargetSelects(`${prov}|${split.modelId}`, split.effort);
    }

    function syncPolishPromptControls(sourceOrSettings = {}) {
        const enabled = sourceOrSettings.postPolishEnabled !== false;
        const prompt = typeof sourceOrSettings.postPolishPrompt === "string"
            ? sourceOrSettings.postPolishPrompt
            : "";
        const modalChk = $("repostPolishEnabledCheck");
        const modalInput = $("repostPolishPromptInput");
        if (modalChk && (typeof document === "undefined" || document.activeElement !== modalChk)) {
            modalChk.checked = enabled;
        }
        if (modalInput && (typeof document === "undefined" || document.activeElement !== modalInput)) {
            modalInput.value = prompt;
        }
        const toggleBtn = $("repostPolishPromptToggleBtn");
        if (toggleBtn) {
            toggleBtn.textContent = "二次改写";
            toggleBtn.classList?.toggle?.("is-on", enabled);
            if (typeof toggleBtn.setAttribute === "function") {
                toggleBtn.setAttribute("aria-expanded", repostPreviewState.polishDrawerOpen ? "true" : "false");
            }
        }
        const drawer = $("repostPolishPromptDrawer");
        if (drawer) {
            drawer.hidden = !repostPreviewState.polishDrawerOpen;
        }
        const settingsChk = $("assistantPostPolishEnabled");
        const settingsInput = $("assistantPostPolishPrompt");
        if (settingsChk && (typeof document === "undefined" || document.activeElement !== settingsChk)) {
            settingsChk.checked = enabled;
        }
        if (settingsInput && !settingsInput.dataset?.editRevision
            && (typeof document === "undefined" || document.activeElement !== settingsInput)) {
            settingsInput.value = prompt;
        }
    }

    async function persistOwnerPolishPrompt(enabled, promptText, resetDefault = false) {
        const body = resetDefault
            ? { resetDefault: true, postPolishEnabled: true }
            : { postPolishEnabled: Boolean(enabled), postPolishPrompt: String(promptText || "").trim() };
        const saved = await contentAssistantRequest("/polish-prompt", body, 20000);
        if (!saved) return null;
        if (state.assistantDashboard?.settings) {
            state.assistantDashboard.settings.postPolishEnabled = saved.postPolishEnabled;
            state.assistantDashboard.settings.postPolishPrompt = saved.postPolishPrompt;
            if (Number.isInteger(saved.settingsVersion)) {
                state.assistantDashboard.settings.version = saved.settingsVersion;
                const form = $("assistantSettingsForm");
                if (form) form.dataset.version = String(saved.settingsVersion);
            }
        }
        if (repostPreviewState.source) {
            repostPreviewState.source.postPolishEnabled = saved.postPolishEnabled;
            repostPreviewState.source.postPolishPrompt = saved.postPolishPrompt;
        }
        const settingsInput = $("assistantPostPolishPrompt");
        if (settingsInput?.dataset) delete settingsInput.dataset.editRevision;
        syncPolishPromptControls(saved);
        return saved;
    }

    function collectSourceAutoRepostOverrides(row, sourceId) {
        if (!row || !sourceId) return {};
        const escSel = typeof CSS !== "undefined" && typeof CSS.escape === "function"
            ? CSS.escape(sourceId)
            : String(sourceId).replace(/(["\\])/g, "\\$1");
        const toggle = row.querySelector(`[data-source-autorepost-toggle="${escSel}"]`);
        const rewriteToggle = row.querySelector(`[data-source-autorewrite-toggle="${escSel}"]`);
        const modeEl = row.querySelector(`[data-source-autorepost-mode="${escSel}"]`);
        const scoreEl = row.querySelector(`[data-source-autorepost-score="${escSel}"]`);
        const tplEl = row.querySelector(`[data-source-autorepost-template="${escSel}"]`);
        const hintEl = row.querySelector(`[data-source-autorepost-hint="${escSel}"]`);
        const payload = {};
        if (toggle) payload.autoRepostEnabled = Boolean(toggle.checked);
        if (rewriteToggle) payload.autoRewriteEnabled = Boolean(rewriteToggle.checked);
        if (modeEl) payload.autoRepostTextMode = modeEl.value;
        if (scoreEl && scoreEl.value !== "") payload.autoRepostMinScore = Number(scoreEl.value);
        if (tplEl) payload.autoRepostTemplate = tplEl.value.trim();
        if (hintEl) payload.autoRepostRuleHint = hintEl.value.trim();
        return payload;
    }

    function resolveRepostMaterialUrl(material = {}, fallbackUsername = repostPreviewState.source?.username) {
        const handle = String(material.username || fallbackUsername || "").replace(/^@+/, "");
        const id = String(material.id || "").trim();
        const raw = String(material.sourceUrl || "").trim();
        if (/^[0-9]{2,20}$/.test(id) && resolveRepostQuoteTweetId({ sourceUrl: raw }) !== id) {
            return `https://x.com/${/^[A-Za-z0-9_]{1,15}$/.test(handle) ? handle : "i"}/status/${id}`;
        }
        return raw && typeof safeHttpUrl === "function" ? safeHttpUrl(raw, "") : raw;
    }

    function resolveRepostQuoteTweetId(material = {}) {
        const id = String(material?.id || "").trim();
        if (/^[0-9]{2,20}$/.test(id)) return id;
        const match = String(material?.sourceUrl || "").trim().match(/^https?:\/\/(?:(?:www|mobile)\.)?(?:x|twitter)\.com\/(?:[A-Za-z0-9_]{1,15}\/status|i\/web\/status)\/([0-9]{2,20})(?:\/(?:photo|video)\/[0-9]+)?\/?(?:[?#]\S*)?$/i);
        return match ? match[1] : "";
    }

    function getRepostItemCommentText(materialId) {
        const id = String(materialId || "");
        const found = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
        if (quoteDeliveryStatus(found?.decision)) return String(found.decision.publishedText || found.decision.commentText || "");
        if (repostPreviewState.editedComments.has(id)) {
            return String(repostPreviewState.editedComments.get(id) || "");
        }
        return String(found?.decision?.commentText || "");
    }

    function getRewriteItemText(materialId) {
        const ownerId = repostPreviewState.owner?.accountId;
        if (ownerId && String(ownerId) !== String(state.authStatus?.user?.id || "")) return "";
        const id = String(materialId || "");
        const found = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
        if (["PUBLISHED", "SENDING", "UNCERTAIN"].includes(found?.rewrite?.status)) return String(found.rewrite.rewrittenText || "");
        if (repostPreviewState.editedRewrites.has(id)) {
            return String(repostPreviewState.editedRewrites.get(id) || "");
        }
        return String(found?.rewrite?.rewrittenText || "");
    }

    function beijingRewriteDayStart(now) {
        const beijingOffsetMs = 8 * 3_600_000;
        return now - (now + beijingOffsetMs) % 86_400_000;
    }

    function isManualRewritePreview(rewrite) {
        return rewrite?.manualReviewPreview === true && !rewrite.candidate && rewrite.status === "EVALUATED"
            && !rewrite.unsentPreview && !["PUBLISHED", "SENDING", "UNCERTAIN", "EVALUATING"].includes(rewrite.persistedStatus);
    }

    function applyRankedRewritePreview(target, rewrite) {
        const previousRank = target.rewrite?.candidate ? Number(target.rewrite.rank || 0) : 0;
        const rank = rewrite.candidate ? Number(rewrite.rank || previousRank) : 0;
        if (previousRank !== rank) {
            for (const other of repostPreviewState.items) {
                if (other === target || !other.rewrite?.candidate || !other.rewrite.rank) continue;
                let position = other.rewrite.rank;
                if (previousRank && position > previousRank) position--;
                if (rank && position >= rank) position++;
                if (position !== other.rewrite.rank) other.rewrite = {...other.rewrite, rank: position};
            }
        }
        target.rewrite = {...rewrite, rank};
    }

    function isTodayRewriteItem(item, now = Date.now()) {
        const publishedAt = Number(item?.material?.publishedAt);
        return publishedAt >= beijingRewriteDayStart(now) && publishedAt <= now
            && (item?.rewrite?.candidate === true || isManualRewritePreview(item?.rewrite));
    }

    function requireTodayRewrite(item) {
        if (isTodayRewriteItem(item)) return true;
        renderRepostPreviewTimeline();
        showToast("这条原帖已不在今日洗稿候选中");
        return false;
    }

    function previewGenerationOptions() {
        const model = $("repostGenTargetSelect");
        const effort = $("repostGenEffortSelect");
        const options = parseGenerationTargetSpec(model?.value || state.assistantGenerationTargetSpec,
            effort ? effort.value : state.assistantGenerationEffort);
        const polish = $("repostPolishEnabledCheck");
        const prompt = $("repostPolishPromptInput")?.value?.trim();
        if (typeof polish?.checked === "boolean") options.postPolishEnabled = polish.checked;
        if (prompt) options.postPolishPrompt = prompt;
        return options;
    }

    function buildRepostItemComposedText(materialId) {
        const id = String(materialId || "");
        const found = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
        const comment = getRepostItemCommentText(id).trim();
        const url = found ? resolveRepostMaterialUrl(found.material) : "";
        return [comment, url].filter(Boolean).join("\n");
    }

    function buildRepostIntentHref(composedText) {
        const text = String(composedText || "").trim();
        return text
            ? `https://x.com/intent/tweet?text=${encodeURIComponent(text)}`
            : "https://x.com/intent/tweet";
    }

    function resolveRepostOwnerIdentity(owner = {}) {
        const authUser = state.authStatus?.user || {};
        const prof = state.report?.profile || {};
        const username = String(authUser.username || prof.username || owner?.username || "elidlicom").replace(/^@+/, "");
        const displayName = String(prof.displayName || authUser.displayName || authUser.name || owner?.displayName || username).trim();
        const avatarUrl = String(prof.avatarUrl || authUser.avatarUrl || owner?.avatarUrl || "/author-avatar.webp").trim();
        return { username, displayName, avatarUrl };
    }

    const REPOST_SAME_AUTHOR_COOLDOWN_MS = 16 * 60 * 60 * 1000;
    const REPOST_DIRECT_PASS_CATEGORIES = new Set(["MODEL_RELEASE", "FEATURE_UPDATE", "QUOTA_RESET"]);

    function isDirectPassRepostCategory(category) {
        return REPOST_DIRECT_PASS_CATEGORIES.has(String(category || "").trim().toUpperCase());
    }

    function repostCategoryLabel(category) {
        const raw = String(category || "").trim().toUpperCase();
        switch (raw) {
            case "MODEL_RELEASE":
                return "模型发布 · 直通";
            case "FEATURE_UPDATE":
                return "功能更新 · 直通";
            case "QUOTA_RESET":
                return "额度重置 · 直通";
            case "MAJOR_LAUNCH":
                return "模型发布";
            case "BREAKING_NEWS":
            case "KEY_PERSONNEL":
            case "INDUSTRY_SHIFT":
                return "重大新闻";
            case "UPDATE_NOTICE":
                return "更新通知";
            default:
                return "";
        }
    }

    function repostDecisionBadgeMeta(decision, isEvaluating = false) {
        switch (quoteDeliveryStatus(decision)) {
            case "UNCERTAIN": return { cls: "error", label: "待核实" };
            case "SENDING": return { cls: "running", label: "发送中" };
            case "PUBLISHED": return { cls: "ok", label: assistantPublishNotice(decision) };
        }
        if (isEvaluating) return { cls: "running", label: "判定中…" };
        const code = String(decision?.decisionCode || "PENDING_EVAL").toUpperCase();
        switch (code) {
            case "PUBLISHED": return { cls: "ok", label: assistantPublishNotice(decision) };
            case "WOULD_REPOST": return { cls: "ok", label: "会转发" };
            case "SKIP_AUTHOR_COOLDOWN": return { cls: "running", label: "账号 CD" };
            case "SKIP_NOT_FIT": return { cls: "running", label: "不契合" };
            case "SKIP_LOW_HEAT": return { cls: "muted", label: "热度不足" };
            case "SKIP_DUPLICATE": return { cls: "muted", label: "同事件已转" };
            case "SKIP_LIMIT": return { cls: "muted", label: "达日上限" };
            case "SKIP_COOLDOWN": return { cls: "running", label: "全局 CD" };
            case "EVAL_FALLBACK": return { cls: "muted", label: "未完成" };
            case "PENDING_EVAL": return { cls: "muted", label: "待判定" };
            default: return { cls: "muted", label: "不转发" };
        }
    }

    function repostDualScoreText(decision, source) {
        if (!decision || !decision.evaluated) return "";
        const minScore = Number(decision.minScore || source?.minScore || 70);
        const vScore = Number(decision.viralScore || 0);
        const fScore = Number(decision.fitScore || 0);
        if (vScore <= 0 && fScore <= 0) return "";
        const isDirect = Boolean(decision.directPass || (isDirectPassRepostCategory(decision.category) && decision.contentPass));
        const vPart = (decision.viralPass || isDirect) ? `潜力 ${vScore}` : `潜力 ${vScore}/${minScore}`;
        const fPart = fScore > 0 ? `契合 ${fScore}` : "";
        return [vPart, fPart].filter(Boolean).join(" · ");
    }

    function repostScoreBreakdownText(decision) {
        if (!decision || !decision.evaluated) return "";
        const fact = Number(decision.factScore || 0);
        const trend = Number(decision.trendScore || 0);
        const attn = Number(decision.attentionScore || 0);
        return (fact > 0 || trend > 0 || attn > 0) ? `事实 ${fact} · 热门 ${trend} · 关注 ${attn}` : "";
    }

    function resolveDualCdView(sourceInfo = {}) {
        const cd = sourceInfo?.cooldown || repostPreviewState.cooldown || {};
        const gNode = cd.globalCd || {};
        const aNode = cd.authorCd || {};
        const globalActive = gNode.active !== undefined
            ? Boolean(gNode.active)
            : Boolean(sourceInfo?.ownerInTweetCooldown);
        const globalJitter = Number(gNode.jitterMinutes || sourceInfo?.ownerTweetJitterMinutes || 5);
        const globalRemMin = globalActive
            ? Math.max(1, Number(gNode.remainingMinutes || sourceInfo?.ownerCooldownRemainingMinutes || 1))
            : 0;
        const authorActive = aNode.active !== undefined
            ? Boolean(aNode.active)
            : Boolean(sourceInfo?.authorInCooldown);
        const authorRemMin = authorActive
            ? Math.max(1, Number(aNode.remainingMinutes || sourceInfo?.authorCooldownRemainingMinutes || 60))
            : 0;
        const authorRemHrs = authorActive ? Math.max(1, Math.ceil(authorRemMin / 60)) : 0;
        const anyActive = globalActive || authorActive;
        let reasonText = "";
        if (globalActive && authorActive) {
            reasonText = `全局 CD（1小时+${globalJitter}分钟，余约 ${globalRemMin} 分钟）与账号 16h CD（余约 ${authorRemHrs} 小时）均未解除，待 CD 解除后再做判断`;
        } else if (globalActive) {
            reasonText = `全局 CD 冷却中（距上一条推文不足 1小时+${globalJitter}分钟，还差约 ${globalRemMin} 分钟），待 CD 解除后再做判断`;
        } else if (authorActive) {
            reasonText = `账号 CD 冷却中（16 小时内已转载该作者，还差约 ${authorRemHrs} 小时），待 CD 解除后再做判断`;
        }
        return { globalActive, globalJitter, globalRemMin, authorActive, authorRemMin, authorRemHrs, anyActive, reasonText };
    }

    function renderRepostDualCdBar(sourceInfo = {}) {
        const barEl = $("repostDualCdBar");
        if (!barEl) return;
        if (repostPreviewState.filterMode === "REWRITE") {
            barEl.hidden = true;
            return;
        }
        const cd = resolveDualCdView(sourceInfo);
        const globalText = cd.globalActive ? `全局 CD · 余 ${cd.globalRemMin}m` : "全局 CD · 就绪";
        const globalTitle = `距上一条推文需间隔 1h+${cd.globalJitter}m`;
        const globalPill = `<span class="assistant-state ${cd.globalActive ? "running" : "ok"}" title="${escapeHtml(globalTitle)}">${escapeHtml(globalText)}</span>`;
        const authorText = cd.authorActive ? `账号 CD · 余 ${cd.authorRemHrs}h` : "账号 CD · 就绪";
        const authorPill = `<span class="assistant-state ${cd.authorActive ? "running" : "ok"}" title="${escapeHtml("同一作者 16h 内只转 1 条")}">${escapeHtml(authorText)}</span>`;
        barEl.innerHTML = `<div class="repost-dual-cd-pills">${globalPill}${authorPill}</div>`;
        barEl.hidden = false;
    }

    function recomputeTimeline16hWindow() {
        const items = repostPreviewState.items || [];
        const dualCd = resolveDualCdView(repostPreviewState.source || {});
        if (dualCd.globalActive) {
            for (const it of items) {
                if (!it) continue;
                const dec = (it.decision ||= {});
                if (quoteDeliveryStatus(dec)) continue;
                const alreadyEvaluated = Boolean(dec.evaluated);
                const exemptAuthorCd = !dec.strictSixteenHourAuthorLock && isDirectPassRepostCategory(dec.category);
                const contentPass = dec.contentPass !== undefined
                    ? Boolean(dec.contentPass)
                    : Boolean((dec.viralPass || exemptAuthorCd) && dec.accountFit);
                const rawBase = String(dec.baseReason !== undefined ? dec.baseReason : (dec.reason || "")).trim();
                if (rawBase && !rawBase.includes("全局 CD") && !rawBase.includes("上一条推文")) {
                    dec.baseReason = rawBase;
                }
                const savedBase = String(dec.baseReason || "").trim();
                dec.wouldAutoRepost = false;
                dec.cdShortCircuited = !alreadyEvaluated;
                dec.authorCooldownBlocked = dualCd.authorActive;
                dec.ownerCooldownBlocked = true;
                if (!alreadyEvaluated) {
                    dec.decisionCode = "SKIP_COOLDOWN";
                    dec.reason = dualCd.reasonText;
                } else if (contentPass) {
                    dec.decisionCode = "SKIP_COOLDOWN";
                    dec.reason = savedBase
                        ? `内容达标（${savedBase}），但${dualCd.reasonText}`
                        : dualCd.reasonText;
                } else {
                    if (!dec.decisionCode || dec.decisionCode === "WOULD_REPOST" || dec.decisionCode === "PENDING_EVAL") {
                        dec.decisionCode = "SKIP_COOLDOWN";
                    }
                    dec.reason = savedBase || dualCd.reasonText;
                }
            }
            return;
        }
        const occupied = [];
        for (const it of items) {
            const dec = it?.decision;
            if (dec?.persistedStatus === "PUBLISHED") {
                const ts = Number(it.material?.publishedAt || 0);
                if (ts > 0) occupied.push(ts);
            }
        }
        for (let i = items.length - 1; i >= 0; i--) {
            const it = items[i];
            const dec = it?.decision;
            if (!dec || !dec.evaluated || quoteDeliveryStatus(dec)) continue;
            const exemptAuthorCd = !dec.strictSixteenHourAuthorLock && isDirectPassRepostCategory(dec.category);
            const persisted = String(dec.persistedStatus || "");
            const guardPersisted = ["SKIPPED_DUPLICATE", "SKIPPED_LIMIT", "SKIPPED_COOLDOWN"]
                .includes(persisted) || (!exemptAuthorCd && persisted === "SKIPPED_AUTHOR_COOLDOWN");
            if (guardPersisted) continue;
            const contentPass = dec.contentPass !== undefined
                ? Boolean(dec.contentPass)
                : Boolean((dec.viralPass || exemptAuthorCd) && dec.accountFit);
            if (!contentPass) {
                if (dualCd.authorActive && !exemptAuthorCd) {
                    dec.wouldAutoRepost = false;
                    dec.authorCooldownBlocked = true;
                    dec.decisionCode = "SKIP_AUTHOR_COOLDOWN";
                    dec.reason = dualCd.reasonText;
                }
                continue;
            }
            const baseReason = String(dec.baseReason !== undefined ? dec.baseReason : (dec.reason || "")).trim();
            dec.baseReason = baseReason;
            const ts = Number(it.material?.publishedAt || 0);
            const blockedBySlot = dualCd.authorActive
                || (ts > 0 && occupied.some((prev) => Math.abs(ts - prev) < REPOST_SAME_AUTHOR_COOLDOWN_MS));
            if (blockedBySlot && !exemptAuthorCd) {
                dec.wouldAutoRepost = false;
                dec.authorCooldownBlocked = true;
                dec.ownerCooldownBlocked = false;
                dec.decisionCode = "SKIP_AUTHOR_COOLDOWN";
                dec.reason = baseReason && !baseReason.includes("16 小时")
                    ? `内容达标，但同账号 16 小时 CD 内仅转 1 条（${baseReason}）`
                    : (baseReason || "16 小时内不连续转发同一作者");
            } else {
                dec.wouldAutoRepost = true;
                dec.authorCooldownBlocked = false;
                dec.ownerCooldownBlocked = false;
                if (blockedBySlot && exemptAuthorCd) dec.authorCooldownExempt = true;
                dec.decisionCode = "WOULD_REPOST";
                dec.reason = baseReason;
                if (ts > 0) occupied.push(ts);
            }
        }
    }

    function renderRepostPreviewHeader(sourceInfo = {}) {
        const srcHandle = String(sourceInfo.username || sourceInfo.handle || repostPreviewState.sourceKey || "").replace(/^@+/, "");
        const srcName = String(sourceInfo.displayName || srcHandle || "监听作者").trim();
        const srcVerified = Boolean(sourceInfo.verified);
        const avatarEl = $("repostPreviewSourceAvatar");
        if (avatarEl && typeof avatarHtml === "function") {
            avatarEl.innerHTML = avatarHtml({
                username: srcHandle || srcName,
                displayName: srcName,
                avatarUrl: sourceInfo.avatarUrl || ""
            }, "md");
        }
        const titleEl = $("repostPreviewTitle");
        if (titleEl) titleEl.textContent = srcName;
        const verEl = $("repostPreviewSourceVerified");
        if (verEl && typeof verifiedSvgHtml === "function") {
            verEl.innerHTML = verifiedSvgHtml(srcVerified);
        }
        const handleEl = $("repostPreviewSourceHandle");
        if (handleEl) handleEl.textContent = srcHandle ? `@${srcHandle}` : "";
        renderRepostDualCdBar(sourceInfo);
        const ruleEl = $("repostPreviewRuleBadge");
        if (ruleEl) {
            const minScore = Number(sourceInfo.minScore || sourceInfo.autoRepostMinScore || 70);
            const isTpl = String(sourceInfo.textMode || sourceInfo.autoRepostTextMode || "AI").toUpperCase() === "TEMPLATE";
            const autoOn = Boolean(sourceInfo.autoRepostEnabled);
            const rewriteOn = Boolean(sourceInfo.autoRewriteEnabled);
            const modeText = autoOn && rewriteOn
                ? "自动转推+洗稿"
                : (autoOn ? "自动转推" : (rewriteOn ? "洗稿发送" : "仅预览"));
            if (repostPreviewState.filterMode === "REWRITE") {
                const active = String(sourceInfo.status || "ACTIVE").toUpperCase() === "ACTIVE" && sourceInfo.enabled !== false;
                ruleEl.className = `assistant-state ${active && rewriteOn ? "ok" : "muted"}`;
                ruleEl.textContent = active && rewriteOn ? "洗稿发送已开启" : "仅手动洗稿";
            } else {
                ruleEl.className = `assistant-state ${(autoOn || rewriteOn) ? "ok" : "muted"}`;
                ruleEl.textContent = `${modeText} · 潜力≥${minScore}${isTpl ? " · 固定文案" : ""}`;
            }
            ruleEl.hidden = false;
        }
        const profLink = $("repostPreviewProfileLink");
        if (profLink) {
            if (srcHandle) {
                const rawUrl = `https://x.com/${encodeURIComponent(srcHandle)}`;
                profLink.href = typeof safeHttpUrl === "function" ? safeHttpUrl(rawUrl, rawUrl) : rawUrl;
                profLink.hidden = false;
            } else {
                profLink.hidden = true;
            }
        }
        if (sourceInfo && (sourceInfo.postPolishPrompt !== undefined || state.assistantDashboard?.settings)) {
            syncPolishPromptControls({
                ...(state.assistantDashboard?.settings || {}),
                ...sourceInfo
            });
        }
    }

    function renderRepostPreviewSubbar(now = Date.now()) {
        const items = repostPreviewState.items || [];
        let repostCount = 0;
        let skipCount = 0;
        let rewriteCount = 0;
        let reviewCount = 0;
        let pendingCount = 0;
        for (const it of items) {
            const dec = it.decision || {};
            if (dec.evaluated || dec.cdShortCircuited) {
                if (dec.wouldAutoRepost) repostCount++;
                else skipCount++;
            } else {
                pendingCount++;
            }
            if (isTodayRewriteItem(it, now)) {
                if (it.rewrite.candidate) rewriteCount++;
                else reviewCount++;
            }
        }
        const filterEl = $("repostPreviewFilterBar");
        if (filterEl) {
            const filters = [
                { id: "ALL", label: `全部推文 ${items.length}` },
                { id: "REPOST", label: `会转发 ${repostCount}` },
                { id: "SKIP", label: `不转发 ${skipCount}` },
                { id: "REWRITE", label: reviewCount ? `今日洗稿 ${rewriteCount} · 待调优 ${reviewCount}` : `今日洗稿 ${rewriteCount}` }
            ];
            filterEl.innerHTML = filters.map((f) => {
                const active = repostPreviewState.filterMode === f.id;
                return `<button type="button" class="pill-chip sm${active ? " active" : ""}" data-repost-filter="${f.id}" aria-pressed="${active ? "true" : "false"}">${escapeHtml(f.label)}</button>`;
            }).join("");
        }
        const rewriteMode = repostPreviewState.filterMode === "REWRITE";
        const scopeEl = $("repostRewriteScope");
        if (scopeEl) scopeEl.hidden = !rewriteMode;
        const evalRestBtn = $("repostPreviewEvalRestBtn");
        if (evalRestBtn) {
            if (rewriteMode) {
                evalRestBtn.hidden = true;
                return;
            }
            const unevaluated = items.filter((it) => !it.decision?.evaluated).length;
            const inFlight = repostPreviewState.evaluatingIds.size;
            if (repostPreviewState.evaluatingAccountOnce || inFlight > 0) {
                evalRestBtn.hidden = false;
                evalRestBtn.disabled = true;
                evalRestBtn.textContent = repostPreviewState.evaluatingAccountOnce
                    ? "判定中…"
                    : `生成中 ${inFlight}`;
            } else if (items.length > 0) {
                evalRestBtn.hidden = false;
                evalRestBtn.disabled = false;
                evalRestBtn.textContent = unevaluated > 0 ? "合并判定" : "重新判定";
            } else {
                evalRestBtn.hidden = true;
            }
        }
    }

    function renderRewriteTimelineItemHtml(item) {
        const mat = item?.material || {};
        const rw = item?.rewrite || {};
        const id = String(mat.id || "");
        const isRewriting = Boolean(repostPreviewState.rewritingIds?.has(id));
        const isFocused = Boolean(id && id === repostPreviewState.focusedMaterialId);

        const srcHandle = String(mat.username || repostPreviewState.source?.username || "").replace(/^@+/, "");
        const srcName = String(mat.authorName || repostPreviewState.source?.displayName || srcHandle || "--").trim();
        const srcAvatar = typeof avatarHtml === "function"
            ? avatarHtml({ username: srcHandle || srcName, displayName: srcName, avatarUrl: mat.authorAvatarUrl || repostPreviewState.source?.avatarUrl || "" }, "sm")
            : "";
        const srcVer = typeof verifiedSvgHtml === "function"
            ? verifiedSvgHtml(Boolean(mat.authorVerified ?? repostPreviewState.source?.verified))
            : "";
        const pubTime = Number(mat.publishedAt) > 0 && typeof formatAssistantDateTime === "function"
            ? formatAssistantDateTime(Number(mat.publishedAt), "Asia/Shanghai")
            : "";
        const sourceUrl = resolveRepostMaterialUrl(mat);
        const pubTweetUrl = rw.publishedTweetUrl && typeof safeHttpUrl === "function"
            ? safeHttpUrl(rw.publishedTweetUrl, "")
            : String(rw.publishedTweetUrl || "");

        const formatMetric = (n) => typeof fmtNum === "function" ? fmtNum(n) : String(n);
        const metricParts = [];
        if (Number.isFinite(rw.engagement)) metricParts.push(`热度 ${formatMetric(rw.engagement)}`);
        if (Number.isFinite(rw.exposureRate)) metricParts.push(`曝光率 ${Number((rw.exposureRate * 100).toFixed(2))}%`);
        if (Number(mat.views) > 0) metricParts.push(`浏览 ${formatMetric(Number(mat.views))}`);
        if (Number(mat.reposts) > 0) metricParts.push(`转 ${formatMetric(Number(mat.reposts))}`);
        if (Number(mat.replies) > 0) metricParts.push(`评 ${formatMetric(Number(mat.replies))}`);
        if (Number(mat.likes) > 0) metricParts.push(`赞 ${formatMetric(Number(mat.likes))}`);

        const mainText = String(mat.articleText || mat.text || "");
        const rankNum = Number(rw.rank || 0);
        const isPublished = !rw.unsentPreview && (rw.persistedStatus === "PUBLISHED" || rw.status === "PUBLISHED");
        const badgeMeta = isRewriting
            ? { cls: "running", label: "生成中…" }
            : rw.unsentPreview ? { cls: "muted", label: "未发送预览" } : (isPublished
                ? { cls: "ok", label: "已洗稿" }
                : (rw.evaluated ? { cls: rw.suitable ? "ok" : "running", label: rw.suitable ? "可洗稿" : "待调优" } : { cls: "muted", label: "洗稿候选" }));
        const scoreText = rw.evaluated && Number(rw.suitabilityScore) > 0 ? `适格 ${Number(rw.suitabilityScore)}` : "";
        const reasonParts = [rw.hookPattern ? `切口：${rw.hookPattern}` : "", rw.reason || ""].filter(Boolean);
        const reasonHtml = reasonParts.length
            ? `<div class="author-timeline-reason is-pass" data-i18n-ignore>${escapeHtml(reasonParts.join(" · "))}</div>`
            : "";

        const owner = resolveRepostOwnerIdentity(repostPreviewState.owner);
        const ownerAvatar = typeof avatarHtml === "function"
            ? avatarHtml({ username: owner.username, displayName: owner.displayName, avatarUrl: owner.avatarUrl }, "sm")
            : "";
        const rewriteVal = getRewriteItemText(id);
        const intentHref = buildRepostIntentHref(rewriteVal.trim());

        let previewSectionHtml = "";
        if (isRewriting && !rewriteVal) {
            previewSectionHtml = `<div class="timeline-owner-quote-box">
                <div class="repost-owner-row">
                    <div class="repost-owner-identity" data-i18n-ignore>${ownerAvatar}<strong>${escapeHtml(owner.displayName)}</strong><span>@${escapeHtml(owner.username)}</span></div>
                </div>
                <textarea class="repost-preview-comment is-loading" rows="2" readonly placeholder="生成洗稿中…"></textarea>
            </div>`;
        } else if (rewriteVal || repostPreviewState.editedRewrites.has(id)) {
            previewSectionHtml = `<div class="timeline-owner-quote-box">
                <div class="repost-owner-row">
                    <div class="repost-owner-identity" data-i18n-ignore>${ownerAvatar}<strong>${escapeHtml(owner.displayName)}</strong><span>@${escapeHtml(owner.username)}</span></div>
                    ${pubTweetUrl ? `<a class="link-inline-btn" href="${pubTweetUrl}" target="_blank" rel="noopener noreferrer">查看已发推文 ↗</a>` : ""}
                </div>
                <textarea class="repost-preview-comment${isRewriting ? " is-loading" : ""}" rows="6" maxlength="600" data-rewrite-text-input="${escapeHtml(id)}" aria-label="洗稿内容预览"${isRewriting || isPublished ? " readonly" : ""}>${escapeHtml(rewriteVal)}</textarea>
                <div class="repost-preview-actions">
                    <button type="button" class="x-outline-pill sm" data-rewrite-regen-item="${escapeHtml(id)}"${isRewriting ? " disabled" : ""}>${isRewriting ? "生成中…" : "换一版"}</button>
                    <div class="repost-preview-actions-right">
                        <button type="button" class="x-outline-pill sm" data-rewrite-copy-item="${escapeHtml(id)}"${isRewriting ? " disabled" : ""}>复制正文</button>
                        <a class="x-outline-pill sm" data-rewrite-intent-link="${escapeHtml(id)}" href="${intentHref}" target="_blank" rel="noopener noreferrer">去 X 发帖 ↗</a>
                        <button type="button" class="x-blue-pill sm" data-rewrite-schedule-item="${escapeHtml(id)}"${isRewriting ? " disabled" : ""}>转入排期草稿</button>
                    </div>
                </div>
            </div>`;
        } else {
            previewSectionHtml = `<button type="button" class="timeline-skip-quote-toggle" data-rewrite-preview-item="${escapeHtml(id)}"${isRewriting ? " disabled" : ""}>
                <span class="timeline-skip-quote-snippet">${isRewriting ? "生成中…" : "生成洗稿内容"}</span>
                <span>${isRewriting ? "" : "▸"}</span>
            </button>`;
        }

        return `<article class="author-timeline-item is-will-repost${isFocused ? " is-focused" : ""}" data-timeline-material="${escapeHtml(id)}">
            <div class="author-timeline-head">
                <div class="author-timeline-who" data-i18n-ignore>
                    ${srcAvatar}
                    <strong>${escapeHtml(srcName)}</strong>
                    ${srcVer}
                    <span>@${escapeHtml(srcHandle)}</span>
                    ${pubTime ? `<span>· ${escapeHtml(pubTime)}</span>` : ""}
                </div>
                <div class="author-timeline-decision">
                    ${rankNum > 0 ? `<span class="mini-tag blue" data-rewrite-rank>#${rankNum}</span>` : ""}
                    <span class="assistant-state ${badgeMeta.cls}">${escapeHtml(badgeMeta.label)}</span>
                    ${scoreText ? `<span class="timeline-dual-score">${escapeHtml(scoreText)}</span>` : ""}
                    ${sourceUrl ? `<a class="link-inline-btn" href="${sourceUrl}" target="_blank" rel="noopener noreferrer">原帖 ↗</a>` : ""}
                </div>
            </div>
            <div class="author-timeline-text" data-i18n-ignore>${escapeHtml(mainText)}</div>
            ${metricParts.length ? `<div class="author-timeline-meta-row"><span>${metricParts.map(part => `<span>${escapeHtml(part)}</span>`).join(" · ")}</span></div>` : ""}
            ${reasonHtml}
            ${previewSectionHtml}
        </article>`;
    }

    function renderRepostTimelineItemHtml(item) {
        if (repostPreviewState.filterMode === "REWRITE") {
            return renderRewriteTimelineItemHtml(item);
        }
        const mat = item?.material || {};
        const dec = item?.decision || {};
        const id = String(mat.id || "");
        const isEvaluating = repostPreviewState.evaluatingIds.has(id);
        const isPublishing = Boolean(repostPreviewState.publishingIds?.has(id));
        const willRepost = Boolean(dec.evaluated && dec.wouldAutoRepost);
        const isFocused = Boolean(id && id === repostPreviewState.focusedMaterialId);
        const isExpanded = willRepost || isFocused || repostPreviewState.expandedIds.has(id) || repostPreviewState.editedComments.has(id);
        const badge = repostDecisionBadgeMeta(dec, isEvaluating);
        const dualScore = repostDualScoreText(dec, repostPreviewState.source);
        const scoreBreakdown = repostScoreBreakdownText(dec);

        const srcHandle = String(mat.username || repostPreviewState.source?.username || "").replace(/^@+/, "");
        const srcName = String(mat.authorName || repostPreviewState.source?.displayName || srcHandle || "--").trim();
        const srcAvatar = typeof avatarHtml === "function"
            ? avatarHtml({ username: srcHandle || srcName, displayName: srcName, avatarUrl: mat.authorAvatarUrl || repostPreviewState.source?.avatarUrl || "" }, "sm")
            : "";
        const srcVer = typeof verifiedSvgHtml === "function"
            ? verifiedSvgHtml(Boolean(mat.authorVerified ?? repostPreviewState.source?.verified))
            : "";
        const pubTime = Number(mat.publishedAt) > 0 && typeof formatAssistantDateTime === "function"
            ? formatAssistantDateTime(Number(mat.publishedAt))
            : "";
        const sourceUrl = resolveRepostMaterialUrl(mat);
        const pubTweetUrl = dec.publishedTweetUrl && typeof safeHttpUrl === "function"
            ? safeHttpUrl(dec.publishedTweetUrl, "")
            : String(dec.publishedTweetUrl || "");

        const formatMetric = (n) => typeof fmtNum === "function" ? fmtNum(n) : String(n);
        const metricParts = [];
        if (Number(mat.views) > 0) metricParts.push(`浏览 ${formatMetric(Number(mat.views))}`);
        if (Number(mat.reposts) > 0) metricParts.push(`转 ${formatMetric(Number(mat.reposts))}`);
        if (Number(mat.replies) > 0) metricParts.push(`评 ${formatMetric(Number(mat.replies))}`);
        if (Number(mat.likes) > 0) metricParts.push(`赞 ${formatMetric(Number(mat.likes))}`);

        const mainText = String(mat.text || (!mat.quotedText && mat.articleText ? mat.articleText : "") || "（纯媒体或链接推文）");
        const quotedSubHtml = mat.quotedText
            ? `<div class="author-timeline-quoted-sub" data-i18n-ignore>${escapeHtml(mat.quotedText)}</div>`
            : "";

        const code = String(dec.decisionCode || "");
        const isCdCode = code === "SKIP_COOLDOWN" || code === "SKIP_AUTHOR_COOLDOWN";
        const cdBase = String(dec.baseReason || "").trim();
        const cdBaseIsCdText = /CD|上一条推文|16 小时/.test(cdBase);
        const reasonText = (isCdCode ? (dec.evaluated && !cdBaseIsCdText ? cdBase : "") : String(dec.reason || "")).trim();
        const reasonCls = willRepost ? " is-pass" : ((code === "SKIP_NOT_FIT" || code === "SKIP_LOW_HEAT") ? " is-mismatch" : "");
        const reasonHtml = reasonText
            ? `<div class="author-timeline-reason${reasonCls}" data-i18n-ignore>${escapeHtml(reasonText)}</div>`
            : "";

        const rewrite = item.rewrite;
        const rejectedRewrite = repostPreviewState.filterMode === "ALL" && rewrite?.evaluated && !rewrite.candidate && !rewrite.manualReviewPreview
                && !rewrite.suitable && ["SKIPPED", "EVALUATED"].includes(rewrite.status);
        const receiptLabel = !rewrite?.unsentPreview && repostPreviewState.filterMode === "ALL"
            && {PUBLISHED: "已洗稿发送", SENDING: "洗稿发送中", UNCERTAIN: "自动洗稿待核实"}[rewrite?.persistedStatus || rewrite?.status];
        const receiptUrl = receiptLabel && rewrite.publishedTweetUrl ? safeHttpUrl(rewrite.publishedTweetUrl, "") : "";
        const owner = resolveRepostOwnerIdentity(repostPreviewState.owner);
        const ownerAvatar = typeof avatarHtml === "function"
            ? avatarHtml({ username: owner.username, displayName: owner.displayName, avatarUrl: owner.avatarUrl }, "sm")
            : "";
        const commentVal = getRepostItemCommentText(id);
        const composedHref = buildRepostIntentHref([commentVal.trim(), sourceUrl].filter(Boolean).join("\n"));

        let quoteSectionHtml = "";
        const delivery = quoteDeliveryStatus(dec) || (isPublishing ? "SENDING" : "");
        if (delivery) {
            const uncertain = delivery === "UNCERTAIN";
            const final = delivery === "PUBLISHED";
            quoteSectionHtml = `<div class="timeline-owner-quote-box">
                ${commentVal ? `<p class="author-timeline-text" data-i18n-ignore>${escapeHtml(commentVal)}</p>` : ""}
                ${uncertain ? '<p class="author-timeline-reason" role="status">发布结果待核实，请先查看发布记录。</p>' : ""}
                <div class="repost-preview-actions">
                    <button type="button" class="x-blue-pill sm" data-repost-publish-item="${escapeHtml(id)}" disabled>${uncertain ? "待核实" : final ? "已发布" : "发送中…"}</button>
                    ${!final && !isPublishing ? `<button type="button" class="x-outline-pill sm" data-repost-refresh-receipt="${escapeHtml(id)}">刷新回执</button>` : ""}
                    ${uncertain ? `<button type="button" class="x-outline-pill sm" data-repost-review-item="${escapeHtml(id)}">查看待核实记录</button>` : ""}
                    ${commentVal ? `<button type="button" class="x-outline-pill sm" data-repost-copy-item="${escapeHtml(id)}">复制附文</button>` : ""}
                </div></div>`;
        } else if (isEvaluating && !commentVal) {
            quoteSectionHtml = `<div class="timeline-owner-quote-box">
                <div class="repost-owner-row">
                    <div class="repost-owner-identity" data-i18n-ignore>${ownerAvatar}<strong>${escapeHtml(owner.displayName)}</strong><span>@${escapeHtml(owner.username)}</span></div>
                </div>
                <textarea class="repost-preview-comment is-loading" rows="2" readonly>生成中…</textarea>
            </div>`;
        } else if (dec.fallbackUsed && !dec.evaluated && !commentVal) {
            quoteSectionHtml = `<button type="button" class="timeline-skip-quote-toggle" data-repost-write-item="${escapeHtml(id)}">
                <span class="timeline-skip-quote-snippet">重新生成</span>
                <span>↻</span>
            </button>`;
        } else if (commentVal || repostPreviewState.editedComments.has(id)) {
            if (isExpanded) {
                quoteSectionHtml = `<div class="timeline-owner-quote-box">
                    <div class="repost-owner-row">
                        <div class="repost-owner-identity" data-i18n-ignore>${ownerAvatar}<strong>${escapeHtml(owner.displayName)}</strong><span>@${escapeHtml(owner.username)}</span></div>
                    </div>
                    <textarea class="repost-preview-comment${isEvaluating ? " is-loading" : ""}" rows="6" maxlength="400" data-repost-comment-input="${escapeHtml(id)}" aria-label="转发附文预览"${isEvaluating ? " readonly" : ""}>${escapeHtml(commentVal)}</textarea>
                    <div class="repost-preview-actions">
                        <button type="button" class="x-outline-pill sm" data-repost-regen-item="${escapeHtml(id)}"${isEvaluating || isPublishing ? " disabled" : ""}>${isEvaluating ? "生成中…" : "换一句"}</button>
                        <div class="repost-preview-actions-right">
                            <button type="button" class="x-outline-pill sm" data-repost-copy-item="${escapeHtml(id)}"${isEvaluating || isPublishing ? " disabled" : ""}>复制附文</button>
                            <a class="x-outline-pill sm" data-repost-intent-link="${escapeHtml(id)}" href="${composedHref}" target="_blank" rel="noopener noreferrer">去 X 转发 ↗</a>
                            <button type="button" class="x-outline-pill sm" data-repost-schedule-item="${escapeHtml(id)}"${isEvaluating || isPublishing ? " disabled" : ""}>转入排期草稿</button>
                            <button type="button" class="x-blue-pill sm" data-repost-publish-item="${escapeHtml(id)}"${isEvaluating || isPublishing ? " disabled" : ""}>${isPublishing ? "发布中…" : "立即引用"}</button>
                        </div>
                    </div>
                </div>`;
            } else {
                quoteSectionHtml = `<button type="button" class="timeline-skip-quote-toggle" data-repost-expand-item="${escapeHtml(id)}">
                    <span class="timeline-skip-quote-snippet" data-i18n-ignore>${escapeHtml(commentVal)}</span>
                    <span>▾</span>
                </button>`;
            }
        } else {
            quoteSectionHtml = `<button type="button" class="timeline-skip-quote-toggle" data-repost-write-item="${escapeHtml(id)}"${isEvaluating ? " disabled" : ""}>
                <span class="timeline-skip-quote-snippet">${isEvaluating ? "试写中…" : "试写转发语"}</span>
                <span>${isEvaluating ? "" : "▸"}</span>
            </button>`;
        }

        const categoryTag = dec.evaluated ? repostCategoryLabel(dec.category) : "";
        const evalTriggerBtn = !dec.evaluated && !isEvaluating
            ? `<button type="button" class="link-inline-btn" data-repost-eval-item="${escapeHtml(id)}">判定</button>`
            : "";

        return `<article class="author-timeline-item${willRepost ? " is-will-repost" : ""}${isFocused ? " is-focused" : ""}" data-timeline-material="${escapeHtml(id)}">
            <div class="author-timeline-head">
                <div class="author-timeline-who" data-i18n-ignore>
                    ${srcAvatar}
                    <strong>${escapeHtml(srcName)}</strong>
                    ${srcVer}
                    <span>@${escapeHtml(srcHandle)}</span>
                    ${pubTime ? `<span>· ${escapeHtml(pubTime)}</span>` : ""}
                </div>
                <div class="author-timeline-decision">
                    ${categoryTag ? `<span class="mini-tag blue">${escapeHtml(categoryTag)}</span>` : ""}
                    <span class="assistant-state ${badge.cls}">${escapeHtml(badge.label)}</span>
                    ${dualScore ? `<span class="timeline-dual-score"${scoreBreakdown ? ` title="${escapeHtml(scoreBreakdown)}"` : ""}>${escapeHtml(dualScore)}</span>` : ""}
                    ${evalTriggerBtn}
                    ${pubTweetUrl ? `<a class="link-inline-btn" href="${escapeHtml(pubTweetUrl)}" target="_blank" rel="noopener noreferrer">查看已发推文 ↗</a>` : ""}
                    ${sourceUrl ? `<a class="link-inline-btn" href="${sourceUrl}" target="_blank" rel="noopener noreferrer">原帖 ↗</a>` : ""}
                </div>
            </div>
            <div class="author-timeline-text" data-i18n-ignore>${escapeHtml(mainText)}</div>
            ${quotedSubHtml}
            ${metricParts.length ? `<div class="author-timeline-meta-row"><span>${escapeHtml(metricParts.join(" · "))}</span></div>` : ""}
            ${reasonHtml}
            ${rejectedRewrite ? `<div class="author-timeline-reason is-mismatch" data-i18n-ignore>洗稿未通过 · ${escapeHtml(rewrite.reason || rewrite.ineligibleReason || "不符合洗稿条件")}</div>` : ""}
            ${receiptLabel ? `<details class="timeline-owner-quote-box"><summary>${escapeHtml(receiptLabel)}</summary>
                ${rewrite.rewrittenText ? `<p class="author-timeline-text" data-i18n-ignore>${escapeHtml(rewrite.rewrittenText)}</p>` : ""}
                ${receiptUrl ? `<a class="link-inline-btn" href="${escapeHtml(receiptUrl)}" target="_blank" rel="noopener noreferrer">查看已发推文 ↗</a>` : ""}
            </details>` : ""}
            ${!delivery && dec.publishedText && dec.publicationMode && dec.publishedText !== commentVal
                ? `<div class="author-timeline-reason"><span>已发布正文</span> <span data-i18n-ignore>${escapeHtml(dec.publishedText)}</span></div>` : ""}
            ${quoteSectionHtml}
        </article>`;
    }

    function itemMatchesRepostFilter(item, now = Date.now()) {
        const mode = repostPreviewState.filterMode;
        const settled = Boolean(item.decision?.evaluated || item.decision?.cdShortCircuited);
        if (mode === "REPOST") return Boolean(settled && item.decision?.wouldAutoRepost);
        if (mode === "SKIP") return Boolean(settled && !item.decision?.wouldAutoRepost);
        if (mode === "REWRITE") return isTodayRewriteItem(item, now);
        return true;
    }

    function renderRepostPreviewTimeline(singleMaterialId = "") {
        recomputeTimeline16hWindow();
        const active = singleMaterialId && repostPreviewState.items.find(item => String(item.material?.id || "") === singleMaterialId);
        if (active && quoteDeliveryStatus(active.decision) === "UNCERTAIN"
                && ["REPOST", "SKIP"].includes(repostPreviewState.filterMode) && !itemMatchesRepostFilter(active)) {
            repostPreviewState.filterMode = "ALL";
            repostPreviewState.focusedMaterialId = singleMaterialId;
            singleMaterialId = "";
        }
        if (repostPreviewState.source) {
            renderRepostPreviewHeader(repostPreviewState.source);
        }
        const now = Date.now();
        renderRepostPreviewSubbar(now);
        const listEl = $("repostPreviewTimelineList");
        if (!listEl) return;

        const visibleItems = (repostPreviewState.items || []).filter(item => itemMatchesRepostFilter(item, now));
        if (repostPreviewState.filterMode === "REWRITE") {
            visibleItems.sort((a, b) => (a.rewrite.rank || Infinity) - (b.rewrite.rank || Infinity));
        }

        if (singleMaterialId && (repostPreviewState.filterMode === "ALL" || repostPreviewState.filterMode === "REWRITE") && typeof listEl.querySelector === "function") {
            const escSel = typeof CSS !== "undefined" && typeof CSS.escape === "function"
                ? CSS.escape(singleMaterialId)
                : String(singleMaterialId).replace(/(["\\])/g, "\\$1");
            const existingRow = listEl.querySelector(`[data-timeline-material="${escSel}"]`);
            const activeInput = typeof document !== "undefined" ? document.activeElement : null;
            const activeEditingOther = activeInput && (activeInput.dataset?.repostCommentInput || activeInput.dataset?.rewriteTextInput)
                && (activeInput.dataset.repostCommentInput || activeInput.dataset.rewriteTextInput) !== singleMaterialId;
            if (existingRow && activeEditingOther && visibleItems.length) {
                const idx = visibleItems.findIndex((it) => String(it.material?.id || "") === singleMaterialId);
                if (repostPreviewState.filterMode === "REWRITE") {
                    const rows = new Map(Array.from(listEl.children, node => [node.dataset.timelineMaterial, node]));
                    for (const item of visibleItems) {
                        const badge = rows.get(String(item.material.id))?.querySelector("[data-rewrite-rank]");
                        if (badge && item.rewrite.rank) badge.textContent = `#${item.rewrite.rank}`;
                    }
                    if (idx < 0) {
                        existingRow.remove();
                        return;
                    }
                    const nextRow = rows.get(String(visibleItems[idx + 1]?.material.id)) || null;
                    if (existingRow.nextElementSibling !== nextRow) listEl.insertBefore(existingRow, nextRow);
                }
                if (idx >= 0) {
                    existingRow.outerHTML = renderRepostTimelineItemHtml(visibleItems[idx]);
                    return;
                }
            }
        }

        if (!visibleItems.length) {
            let emptyMsg = "当前筛选下暂无推文。";
            if (repostPreviewState.filterMode === "REWRITE") emptyMsg = "这位作者今天暂无符合条件的洗稿原帖。";
            else if (!repostPreviewState.items.length) emptyMsg = "该监听作者暂无可预览的近期推文。";
            else if (repostPreviewState.filterMode === "REPOST") emptyMsg = "当前已评估推文中暂无满足「会火且适合当前账号」的条目。";
            listEl.innerHTML = `<div class="assistant-empty compact" style="margin:16px;">${escapeHtml(emptyMsg)}</div>`;
            return;
        }
        listEl.innerHTML = visibleItems.map(renderRepostTimelineItemHtml).join("");
    }

    async function evaluateSingleTimelineItem(materialId, forceRegenerate, token, expandOnFinish = false, forceWriteComment = false) {
        const id = String(materialId || "").trim();
        if (!id || token !== repostPreviewState.requestToken) return;
        if (repostPreviewState.evaluatingIds.has(id)) return;
        repostPreviewState.evaluatingIds.add(id);
        const requestOrder = nextQuotePreviewOrder();
        const previousEdit = repostPreviewState.editedComments.get(id);
        const originalItem = repostPreviewState.items.find(item => String(item.material?.id || "") === id);
        const originalDecision = originalItem?.decision;
        const originalFingerprint = originalItem?.material?.sourceFingerprint;
        if (expandOnFinish) repostPreviewState.expandedIds.add(id);
        renderRepostPreviewTimeline(id);
        try {
            const reqBody = { ...repostPreviewState.overrides, ...previewGenerationOptions(), materialId: id };
            if (forceRegenerate) reqBody.forceRegenerate = true;
            if (forceWriteComment || forceRegenerate || id === repostPreviewState.focusedMaterialId) {
                reqBody.forceWriteComment = true;
            }
            const res = await contentAssistantRequest(
                `/sources/${encodeURIComponent(repostPreviewState.sourceKey)}/repost-preview`,
                reqBody,
                95000
            );
            if (token !== repostPreviewState.requestToken) return;
            const target = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
            if ((target?.quotePreviewOrder || 0) > requestOrder) return;
            if (String(res?.material?.id || "") !== id || !res?.preview) throw new Error("返回的素材与所选原帖不一致，请刷新重试");
            if (target) {
                target.quotePreviewOrder = requestOrder;
                target.decision = preservePublishedQuote(target.decision, res.preview, target.decision !== originalDecision);
                target.material = res.material;
                if ((forceRegenerate || forceWriteComment) && !quoteDeliveryStatus(target.decision)
                        && originalFingerprint === res.material.sourceFingerprint && !res.preview.fallbackUsed && res.preview.commentText?.trim()
                        && repostPreviewState.editedComments.get(id) === previousEdit) {
                    repostPreviewState.editedComments.delete(id);
                }
            }
        } catch (err) {
            if (token !== repostPreviewState.requestToken) return;
            const target = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
            if (target && (target.quotePreviewOrder || 0) <= requestOrder && !quoteDeliveryStatus(target.decision)) {
                target.decision = {
                    ...(target.decision || {}),
                    evaluated: false,
                    fallbackUsed: true,
                    decisionCode: "EVAL_FALLBACK",
                    reason: formatLocalizedFetchError(err, "判定未完成，请稍后重试")
                };
            }
        } finally {
            if (token === repostPreviewState.requestToken) {
                repostPreviewState.evaluatingIds.delete(id);
                renderRepostPreviewTimeline(id);
            }
        }
    }

    async function previewSingleRewriteItem(materialId, forceRegenerate, token) {
        const id = String(materialId || "").trim();
        if (!id || token !== repostPreviewState.requestToken) return;
        if (repostPreviewState.rewritingIds.has(id)) return;
        const row = repostPreviewState.items.find(item => String(item.material?.id || "") === id);
        if (!requireTodayRewrite(row)) return;
        repostPreviewState.rewritingIds.add(id);
        const initial = row.rewrite;
        const initialFingerprint = row.material.sourceFingerprint;
        const initialStatus = initial?.status;
        const initialVersion = Number(initial?.recordVersion || 0);
        const previousEdit = repostPreviewState.editedRewrites.get(id);
        renderRepostPreviewTimeline(id);
        try {
            const reqBody = { ...repostPreviewState.overrides, ...previewGenerationOptions(), materialId: id };
            if (forceRegenerate) reqBody.forceRegenerate = true;
            const res = await contentAssistantRequest(
                `/sources/${encodeURIComponent(repostPreviewState.sourceKey)}/rewrite-preview`,
                reqBody,
                95000
            );
            if (token !== repostPreviewState.requestToken) return;
            const target = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
            if (String(res?.material?.id || "") !== id || !res?.rewrite) throw new Error("返回的素材与所选原帖不一致，请刷新重试");
            if (target) {
                if (target.material?.sourceFingerprint !== initialFingerprint
                        && res.material.sourceFingerprint !== target.material?.sourceFingerprint) return;
                const current = target.rewrite;
                const status = current?.status;
                const currentVersion = Number(current?.recordVersion || 0);
                const newerReceipt = (status !== initialStatus || currentVersion !== initialVersion)
                        && ["PUBLISHED", "SENDING", "UNCERTAIN", "EVALUATING", "JUDGE_FAILED"].includes(status);
                if (currentVersion > Number(res.rewrite.recordVersion || 0)
                        || newerReceipt && status !== res.rewrite.status
                        && (status === "PUBLISHED" || !["PUBLISHED", "UNCERTAIN"].includes(res.rewrite.status))) return;
                applyRankedRewritePreview(target, res.rewrite);
                target.material = res.material;
                if (!isTodayRewriteItem(target)) {
                    showToast(res.rewrite.ineligibleReason || res.rewrite.reason || "这条原帖已不在今日洗稿候选中");
                } else if (forceRegenerate && res.rewrite.rewrittenText?.trim() && repostPreviewState.editedRewrites.get(id) === previousEdit) {
                    repostPreviewState.editedRewrites.delete(id);
                }
            }
        } catch (err) {
            if (token === repostPreviewState.requestToken && typeof showToast === "function") {
                showToast(formatLocalizedFetchError(err, "生成洗稿内容未完成，请稍后重试"));
            }
        } finally {
            if (token === repostPreviewState.requestToken) {
                repostPreviewState.rewritingIds.delete(id);
                renderRepostPreviewTimeline(id);
            }
        }
    }

    function nextQuotePreviewOrder() {
        repostPreviewState.quoteRequestOrder = (repostPreviewState.quoteRequestOrder || 0) + 1;
        return repostPreviewState.quoteRequestOrder;
    }

    function quoteDeliveryStatus(decision) {
        const status = decision?.persistedStatus || decision?.decisionCode;
        return ["SENDING", "UNCERTAIN", "PUBLISHED"].includes(status) ? status : "";
    }

    function preservePublishedQuote(previous, decision, newerReceipt = false) {
        const before = Number(previous?.receiptRevision || 0);
        const after = Number(decision?.receiptRevision || 0);
        const status = quoteDeliveryStatus(previous);
        const next = quoteDeliveryStatus(decision);
        if (previous?.pendingRequest && !next && Number(decision?.requestReceiptRevision || 0) <= previous.pendingRequest.afterRevision) return previous;
        if (before && after && before !== after) return before > after ? previous : decision;
        if (newerReceipt && status && (status === "PUBLISHED" || next !== "PUBLISHED")) return previous;
        if (before > after || (status === "PUBLISHED" && next !== "PUBLISHED" && !after)) return previous;
        return decision;
    }

    async function evaluateAuthorAccountOnceInModal(token, forceRegenerate = false) {
        if (!repostPreviewState.sourceKey || token !== repostPreviewState.requestToken) return;
        const dualCd = resolveDualCdView(repostPreviewState.source || {});
        if (dualCd.globalActive && !forceRegenerate) {
            renderRepostPreviewTimeline();
            return;
        }
        if (repostPreviewState.evaluatingAccountOnce) return;
        repostPreviewState.evaluatingAccountOnce = true;
        const requestOrder = nextQuotePreviewOrder();
        const initialItems = new Map(repostPreviewState.items.map(item => [String(item.material?.id || ""), { ...item }]));
        const previousEdits = forceRegenerate ? new Map([...initialItems.keys()]
            .filter(id => repostPreviewState.editedComments.has(id)).map(id => [id, repostPreviewState.editedComments.get(id)])) : null;
        renderRepostPreviewSubbar();
        try {
            const reqBody = { ...repostPreviewState.overrides, ...previewGenerationOptions() };
            if (repostPreviewState.focusedMaterialId) reqBody.materialId = repostPreviewState.focusedMaterialId;
            if (forceRegenerate) reqBody.forceRegenerate = true;
            const data = await contentAssistantRequest(
                `/sources/${encodeURIComponent(repostPreviewState.sourceKey)}/evaluate-once`,
                reqBody,
                110000
            );
            if (token !== repostPreviewState.requestToken) return;
            if (data?.owner) repostPreviewState.owner = data.owner;
            if (data?.source) repostPreviewState.source = data.source;
            if (data?.cooldown) repostPreviewState.cooldown = data.cooldown;
            if (Array.isArray(data?.items)) {
                const currentItems = new Map(repostPreviewState.items.map(item => [String(item.material?.id || ""), item]));
                const regenerated = new Set(data.regeneratedCommentIds || []);
                for (const item of data.items) {
                    const id = String(item.material?.id || "");
                    const initial = initialItems.get(id);
                    const current = currentItems.get(id);
                    currentItems.delete(id);
                    if (current && initial && current.rewrite !== initial.rewrite) item.rewrite = current.rewrite;
                    if ((current?.quotePreviewOrder || 0) > requestOrder) {
                        item.quotePreviewOrder = current.quotePreviewOrder;
                        item.material = current.material;
                        item.decision = preservePublishedQuote(item.decision, current.decision);
                        continue;
                    }
                    if (current && initial && current.material !== initial.material && !current.quotePreviewOrder) item.material = current.material;
                    item.quotePreviewOrder = requestOrder;
                    item.decision = preservePublishedQuote(current?.decision, item.decision, current?.decision !== initial?.decision);
                    if (regenerated.has(id) && previousEdits?.has(id) && !quoteDeliveryStatus(item.decision)
                            && initial?.material?.sourceFingerprint === item.material?.sourceFingerprint
                            && item.decision?.commentText?.trim() && !item.decision.fallbackUsed
                            && previousEdits.get(id) === repostPreviewState.editedComments.get(id)) {
                        repostPreviewState.editedComments.delete(id);
                    }
                }
                for (const [id, current] of currentItems) {
                    const initial = initialItems.get(id);
                    if (!initial || current.decision !== initial.decision || current.rewrite !== initial.rewrite) data.items.push(current);
                }
                repostPreviewState.items = data.items;
            }
        } catch (err) {
            if (token === repostPreviewState.requestToken && typeof showToast === "function") {
                showToast(formatLocalizedFetchError(err, "本账号合并判定未完成，请稍后重试"));
            }
        } finally {
            if (token === repostPreviewState.requestToken) {
                repostPreviewState.evaluatingAccountOnce = false;
                renderRepostPreviewTimeline();
            }
        }
    }

    async function loadAuthorTimelinePreview(sourceKey, options = {}) {
        const key = String(sourceKey || "").trim();
        if (!key) return;
        const token = ++repostPreviewState.requestToken;
        repostPreviewState.sourceKey = key;
        repostPreviewState.timelineDayStart = beijingRewriteDayStart(Date.now());
        repostPreviewState.evaluatingAccountOnce = false;
        repostPreviewState.cooldown = null;
        repostPreviewState.evaluatingIds.clear();
        repostPreviewState.rewritingIds.clear();
        repostPreviewState.expandedIds.clear();
        repostPreviewState.items = [];
        const rewriteMode = options.filterMode === "REWRITE";
        repostPreviewState.filterMode = rewriteMode ? "REWRITE" : "ALL";
        repostPreviewState.rewriteTimelineLoaded = rewriteMode;
        if (repostPreviewState.source) renderRepostPreviewHeader(repostPreviewState.source);
        if (repostPreviewState.focusedMaterialId) {
            repostPreviewState.expandedIds.add(repostPreviewState.focusedMaterialId);
        }

        const listEl = $("repostPreviewTimelineList");
        if (listEl) {
            listEl.innerHTML = `<div class="assistant-empty compact" style="margin:18px;">加载中…</div>`;
        }
        renderRepostPreviewSubbar();

        try {
            const reqBody = { ...repostPreviewState.overrides };
            if (repostPreviewState.focusedMaterialId) reqBody.materialId = repostPreviewState.focusedMaterialId;
            if (rewriteMode) Object.assign(reqBody, previewGenerationOptions(), {rewriteToday: true});
            const data = await contentAssistantRequest(
                `/sources/${encodeURIComponent(key)}/timeline`,
                reqBody,
                35000
            );
            if (token !== repostPreviewState.requestToken) return;
            repostPreviewState.owner = data?.owner || null;
            repostPreviewState.source = data?.source || repostPreviewState.source;
            repostPreviewState.cooldown = data?.cooldown || data?.source?.cooldown || null;
            repostPreviewState.items = Array.isArray(data?.items) ? data.items : [];
            for (const row of repostPreviewState.items) {
                if (!row.material?.id) continue;
                const attempt = repostPreviewState.pendingQuotes.get(quoteAttemptKey(row.material));
                if (attempt) {
                    markQuoteAwaitingReceipt(row, attempt);
                    void refreshQuoteReceipt(String(row.material.id)).catch(() => {});
                }
            }
            renderRepostPreviewTimeline();

            if (repostPreviewState.focusedMaterialId && listEl?.querySelector) {
                const escSel = typeof CSS !== "undefined" && typeof CSS.escape === "function"
                    ? CSS.escape(repostPreviewState.focusedMaterialId)
                    : repostPreviewState.focusedMaterialId;
                const focusedRow = listEl.querySelector(`[data-timeline-material="${escSel}"]`);
                if (focusedRow && typeof focusedRow.scrollIntoView === "function") {
                    try { focusedRow.scrollIntoView({ block: "nearest" }); } catch (_) {}
                }
            }

            // Priority 0: If Global CD (1h+N min) is active, short-circuit immediately (0 LLM calls).
            const dualCd = resolveDualCdView(repostPreviewState.source || {});
            if (dualCd.globalActive) {
                return;
            }

            // Same account evaluated ONLY ONCE across its top candidate window!
            const hasPendingTopWindow = repostPreviewState.items
                .slice(0, 8)
                .some((it) => !it.decision?.evaluated);
            if (hasPendingTopWindow && repostPreviewState.filterMode !== "REWRITE") {
                void evaluateAuthorAccountOnceInModal(token, false);
            }
        } catch (err) {
            if (token !== repostPreviewState.requestToken) return;
            repostPreviewState.rewriteTimelineLoaded = false;
            if (listEl) {
                listEl.innerHTML = `<div class="assistant-empty compact" style="margin:18px;">${escapeHtml(formatLocalizedFetchError(err, "加载监听作者推文失败，请稍后重试"))}</div>`;
            }
        }
    }

    function refreshRewriteDay() {
        const modal = $("repostPreviewModal");
        if (!modal || modal.hidden) return false;
        const now = Date.now();
        const today = beijingRewriteDayStart(now);
        clearTimeout(repostPreviewState.rewriteDayTimer);
        repostPreviewState.rewriteDayTimer = setTimeout(refreshRewriteDay, today + 86_400_000 - now);
        if (repostPreviewState.timelineDayStart === today && (repostPreviewState.filterMode !== "REWRITE" || repostPreviewState.rewriteTimelineLoaded)) return false;
        if (repostPreviewState.filterMode === "REWRITE") {
            void loadAuthorTimelinePreview(repostPreviewState.sourceKey, { filterMode: "REWRITE" });
            return true;
        }
        renderRepostPreviewSubbar(now);
        return false;
    }

    function openRepostPreviewModal(sourceKey, options = {}) {
        const key = String(sourceKey || "").trim();
        if (!key) return;
        const modal = document.getElementById("repostPreviewModal");
        if (!modal) return;
        const normHandle = key.replace(/^@+/, "").toLowerCase();
        const knownSource = (state.assistantDashboard?.sources || []).find((s) =>
            s.id === key
            || s.authorId === key
            || String(s.username || "").replace(/^@+/, "").toLowerCase() === normHandle
        ) || {};
        repostPreviewState.overrides = options.overrides && typeof options.overrides === "object"
            ? { ...options.overrides }
            : {};
        repostPreviewState.focusedMaterialId = options.materialId ? String(options.materialId) : "";
        repostPreviewState.source = { ...knownSource, ...repostPreviewState.overrides };
        modal.hidden = false;
        syncBodyModalLock();
        onModalOpened("repostPreviewModal", modal);
        void loadAuthorTimelinePreview(key, options);
        refreshRewriteDay();
    }

    function closeRepostPreviewModal(fromPopstate = false) {
        clearTimeout(repostPreviewState.rewriteDayTimer);
        repostPreviewState.requestToken++;
        repostPreviewState.evaluatingIds.clear();
        repostPreviewState.rewritingIds.clear();
        const modal = document.getElementById("repostPreviewModal");
        if (modal) modal.hidden = true;
        syncBodyModalLock();
        onModalClosed("repostPreviewModal", fromPopstate);
    }

    function assistantStatusLabel(status) {
        const raw = String(status || "").trim();
        if (!raw) return "待定";
        const labels = {
            QUEUED: "排队中", RUNNING: "处理中", PARTIAL: "部分完成", SUCCEEDED: "已完成",
            COMPLETED: "已完成", FAILED: "失败", CANCELLED: "已取消", INTERRUPTED: "已中断",
            CONSTRAINTS_UNMET: "约束未达标",
            READY: "就绪", BLOCKED_CONFIG: "等待配置", RESEARCH_PARTIAL: "部分研究",
            WAITING_STYLE: "等待风格", WAITING_SAMPLES: "等待样本",
            SCORED: "已评分", PENDING: "待处理", NEEDS_CONFIRMATION: "待确认独立",
            DRAFTED: "已有初稿", MERGED: "已归并",
            DUPLICATE: "重复内容", SAME_EVENT_NEW_INFORMATION: "同事件新进展",
            FOLLOW_UP: "后续进展", NEW_TOPIC: "独立新选题",
            VERIFIED: "已核实", SUPPORTS: "证据支持", PARTIALLY_SUPPORTS: "部分印证",
            REFUTES: "证据否定", UNRELATED: "不构成依据", UNVERIFIED: "待核实", CONFLICT: "有冲突",
            SUPPORTED: "证据充分", FULL: "完整内容", PREVIEW: "预览待补全", NO_USABLE_EVIDENCE: "暂无可用证据",
            READ: "已读取", MATERIAL_FULL: "原文全文", MATERIAL_PREVIEW: "原文节选", MATERIAL_EXCERPT: "已采集片段", UNREADABLE: "网页不可读",
            BLOCKED: "访问受阻", TIMED_OUT: "读取超时", TOO_LARGE: "体积超限", SKIPPED: "已跳过",
            OBSERVED: "已测量", RECORDED: "已记录", MISSING_WINDOW: "已错过窗口", OUTSIDE_WINDOW: "超出窗口",
            RETRYABLE_ERROR: "待重试", INSUFFICIENT: "样本不足",
            ACTIVE: "监听中", PAUSED: "已暂停", REMOVED: "已移除", DRAFT: "草稿",
            SCHEDULED: "已排期", PUBLISHED: "已发布", SENDING: "发送中", RESEND_QUEUED: "排队补发",
            OVERDUE_CONFIRM_REQUIRED: "逾期待确认", UNCERTAIN: "待核对",
            COMPLETE: "采集完成", EXHAUSTED: "已读尽上游", LIMITED: "采集受限", COLLECTING: "采集中"
        };
        return labels[raw.toUpperCase()] || raw;
    }

    function assistantStatusClass(status) {
        const normalized = String(status || "").toUpperCase();
        if (["SUCCEEDED", "COMPLETED", "ACTIVE", "READY", "SCORED", "DRAFTED", "VERIFIED",
            "SUPPORTS", "SUPPORTED", "OBSERVED", "RECORDED", "PUBLISHED", "READ", "MATERIAL_FULL", "COMPLETE", "FULL"].includes(normalized)) return "ok";
        if (["FAILED", "INTERRUPTED", "CONFLICT", "REFUTES", "CONSTRAINTS_UNMET",
            "BLOCKED", "TIMED_OUT", "OVERDUE_CONFIRM_REQUIRED", "UNCERTAIN", "LIMITED"].includes(normalized)) return "error";
        if (["RUNNING", "QUEUED", "WAITING_STYLE", "WAITING_SAMPLES", "SCHEDULED", "SENDING",
            "RESEND_QUEUED", "COLLECTING", "PARTIALLY_SUPPORTS", "PREVIEW", "NEEDS_CONFIRMATION"].includes(normalized)) return "running";
        return "muted";
    }

    function assistantTaskKindLabel(kind) {
        const map = {
            DRAFT: "极速成稿",
            RESEARCH: "深度研究与成稿",
            OPTIMIZE: "定向改稿润色",
            CLASSIFY: "选题智能归并",
            SCORE: "选题价值评估",
            STYLE: "个人风格提炼",
            COLLECT_STYLE: "原创样本采集",
            VERIFY: "模型与联网自检"
        };
        return map[String(kind || "").toUpperCase()] || kind || "助手任务";
    }

    function assistantTaskStageLabel(stage) {
        const map = {
            RESEARCH: "联网检索与读取网页",
            RESEARCH_SYNTHESIS: "证据核实与归纳",
            DRAFT: "撰写初稿",
            OPTIMIZE: "生成改稿版本",
            OPTIMIZE_REVISION: "自动修订约束",
            CLASSIFY: "判别事件关联",
            SCORE: "计算价值与传播分",
            STYLE: "提炼写作风格",
            COLLECT_STYLE: "拉取历史原创样本",
            VERIFY: "探测结构化输出与联网"
        };
        return map[String(stage || "").toUpperCase()] || stage || "";
    }

    function assistantSourceRefLabel(rawRef, materialEvidence = []) {
        const ref = String(rawRef || "").trim();
        if (!ref) return "";
        const webMatch = /^source-(\d+)(?:#p(\d+))?$/i.exec(ref);
        if (webMatch) return `网页来源 #${webMatch[1]}${webMatch[2] ? `（第 ${webMatch[2]} 段）` : ""}`;
        const matMatch = /^material-.+?-(author-article|quoted-article|article|author|quoted|link)(?:#p(\d+))?$/i.exec(ref);
        if (matMatch) {
            const status = materialEvidence.find(entry => entry.id === ref.split("#")[0])?.status;
            const article = ["MATERIAL_PREVIEW","MATERIAL_EXCERPT"].includes(status) ? "文章节选" : "文章全文";
            const roleName = {
                author: "作者原帖正文",
                quoted: "被引用原帖正文",
                "author-article": "作者" + article,
                "quoted-article": "被引用" + article,
                article: "归属待核实的" + article,
                link: "链接原文"
            }[matMatch[1].toLowerCase()] || "原帖素材";
            return `${roleName}${matMatch[2] ? `（第 ${matMatch[2]} 段）` : ""}`;
        }
        return ref;
    }

    function assistantScoreText(score) {
        return Number.isFinite(score) ? String(Math.round(score)) : "待评";
    }

    function assistantScoreCoverage(coverage) {
        return Number.isFinite(coverage) ? `（覆盖 ${Math.round(coverage * 100)}%）` : "";
    }

    function renderAssistantSummary(dashboard) {
        const container = $("contentAssistantSummary");
        if (!container) return;
        const sources = dashboard.sources || [];
        const topics = Array.isArray(dashboard.topics) ? dashboard.topics : [];
        const activeSources = sources.filter((source) => String(source.status || "ACTIVE") === "ACTIVE").length;
        const topicCount = Math.max(Number(dashboard.topicCount || 0), topics.length);
        const recommendedCount = Math.max(
            Number(dashboard.recommendedTopicCount || 0),
            topics.filter((t) => String(t.status || "").toUpperCase() === "RECOMMENDED").length
        );
        const needsConfirmationCount = Math.max(
            Number(dashboard.needsConfirmationCount || 0),
            topics.filter((t) => String(t.status || "").toUpperCase() === "NEEDS_CONFIRMATION").length
        );
        const draftedCount = Math.max(
            Number(dashboard.draftedTopicCount || 0),
            topics.filter((t) => String(t.status || "").toUpperCase() === "DRAFTED" || Number(t.draftCount || 0) > 0).length
        );
        const activeStatus = state.assistantTopicStatus || "";
        const inTopics = state.assistantView === "assistant";
        const cards = [
            { value: topicCount, label: "全部选题", attr: `data-assistant-summary-status=""`, active: inTopics && activeStatus === "" },
            { value: recommendedCount, label: "高分推荐", attr: `data-assistant-summary-status="RECOMMENDED"`, active: inTopics && activeStatus === "RECOMMENDED" },
            { value: needsConfirmationCount, label: "待确认", attr: `data-assistant-summary-status="NEEDS_CONFIRMATION"`, active: inTopics && activeStatus === "NEEDS_CONFIRMATION" },
            { value: draftedCount, label: "已有初稿", attr: `data-assistant-summary-status="DRAFTED"`, active: inTopics && activeStatus === "DRAFTED" },
            { value: activeSources, label: "监听作者", attr: `data-assistant-summary-view="monitor"`, active: state.assistantView === "monitor" }
        ];
        container.innerHTML = cards.map((card) =>
            `<button type="button" class="assistant-summary-card${card.active ? " active" : ""}" ${card.attr} aria-pressed="${card.active ? "true" : "false"}"><strong>${fmtNum(card.value)}</strong><span>${card.label}</span></button>`
        ).join("");
    }

    function renderAssistantHotTagChips(container, dashboard, activeTag, dataAttr) {
        if (!container) return;
        const counts = new Map();
        for (const topic of (dashboard?.topics || [])) {
            for (const raw of (Array.isArray(topic.tags) ? topic.tags : [])) {
                const tag = String(raw || "").trim();
                if (!tag) continue;
                counts.set(tag, (counts.get(tag) || 0) + 1);
            }
        }
        const sorted = Array.from(counts.entries())
            .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
            .slice(0, 12)
            .map(([tag]) => tag);
        const trimmedActive = String(activeTag || "").trim();
        if (trimmedActive && !sorted.some((tag) => tag.toLowerCase() === trimmedActive.toLowerCase())) {
            sorted.unshift(trimmedActive);
        }
        if (!sorted.length) {
            container.hidden = true;
            container.innerHTML = "";
            return;
        }
        container.hidden = false;
        container.innerHTML = sorted.map((tag) => {
            const isActive = trimmedActive && tag.toLowerCase() === trimmedActive.toLowerCase();
            return `<button type="button" class="pill-chip sm${isActive ? " active" : ""}" ${dataAttr}="${escapeHtml(tag)}" aria-pressed="${isActive ? "true" : "false"}">#${escapeHtml(tag)}</button>`;
        }).join("");
    }

    function materialText(material) {
        return [material.text, material.quotedText, material.articleText].filter(Boolean).join("\n\n");
    }

    function formatAssistantDateTime(value, timeZone) {
        const date = new Date(value);
        return Number.isFinite(date.getTime()) ? date.toLocaleString("zh-CN", {
            timeZone, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
        }) : "";
    }

    function assistantAuthorChipHtml(author, options = {}) {
        if (!author) return "";
        const username = String(author.username || author.handle || "").replace(/^@+/, "");
        const displayName = String(author.displayName || author.authorName || username || "未知作者").trim();
        const avatarUrl = String(author.avatarUrl || author.authorAvatarUrl || "").trim();
        const verified = Boolean(author.verified || author.authorVerified);
        const avatar = avatarHtml({ username: username || displayName, displayName, avatarUrl }, "sm");
        const previewKey = String(author.sourceId || author.authorId || author.id || username || "").trim();
        const previewMatAttr = options.materialId && /^[0-9]+$/.test(String(options.materialId))
            ? ` data-repost-preview-material-id="${escapeHtml(String(options.materialId))}"`
            : "";
        const isWebLinkAuthor = String(author.authorId || "").startsWith("web:") || username.includes(".");
        const avatarSlot = previewKey && username && username.toLowerCase() !== "unknown" && !isWebLinkAuthor
            ? `<button type="button" class="assistant-avatar-preview-btn" data-source-repost-preview="${escapeHtml(previewKey)}"${previewMatAttr} aria-label="预览转发 @${escapeHtml(username)} 时说什么" title="点击预览转发时我会说什么">${avatar}</button>`
            : avatar;
        const extraCount = Number(options.extraCount || 0);
        const handleLabel = username ? (isWebLinkAuthor ? username : `@${username}`) : "";
        return `<span class="assistant-author-chip${options.topic ? " is-topic" : ""}" data-i18n-ignore>
            ${avatarSlot}
            <span class="assistant-author-name">${escapeHtml(displayName)}</span>
            ${verifiedSvgHtml(verified)}
            ${handleLabel ? `<span class="assistant-author-handle">${escapeHtml(handleLabel)}</span>` : ""}
            ${extraCount > 0 ? `<span class="assistant-author-extra">+${extraCount}</span>` : ""}
        </span>`;
    }

    function resolveTopicAuthors(topic, topicMaterials, sourcesByKey) {
        if (Array.isArray(topic?.authors) && topic.authors.length) return topic.authors;
        const result = [];
        const seen = new Set();
        for (const mat of (topicMaterials || [])) {
            const uname = String(mat.username || "").replace(/^@+/, "");
            const key = (mat.authorId || uname.toLowerCase()).trim();
            if (!key || seen.has(key)) continue;
            seen.add(key);
            const fallbackSrc = sourcesByKey?.get(mat.authorId) || sourcesByKey?.get(uname.toLowerCase());
            result.push({
                authorId: mat.authorId || "",
                username: uname,
                displayName: mat.authorName || fallbackSrc?.displayName || uname,
                avatarUrl: mat.authorAvatarUrl || fallbackSrc?.avatarUrl || "",
                verified: Boolean(mat.authorVerified || fallbackSrc?.verified)
            });
        }
        return result;
    }

    function renderAssistantTopics(dashboard) {
        const topics = dashboard.topics || [];
        const list = $("assistantTopicList");
        $("assistantTopicPageLabel").textContent = `第 ${Math.floor(state.assistantTopicOffset / 100) + 1} 页`;
        $("assistantTopicPrevBtn").disabled = state.assistantTopicOffset === 0;
        $("assistantTopicNextBtn").disabled = !state.assistantTopicHasNext;
        renderAssistantSkillBar(dashboard);
        renderAssistantHotTagChips($("assistantTopicTagChips"), dashboard, state.assistantTopicTag || "", "data-assistant-topic-tag-chip");
        const drafts = dashboard.drafts || [];
        const materials = dashboard.materials || [];
        const sourcesByKey = new Map();
        for (const src of (dashboard.sources || [])) {
            if (src.authorId) sourcesByKey.set(src.authorId, src);
            if (src.username) sourcesByKey.set(String(src.username).toLowerCase(), src);
        }
        const draftedTopicIds = new Set(drafts.map((item) => item.topicId).filter(Boolean));
        const isAnalyzable = (material) => Boolean(materialText(material))
            && !["NO_ANALYZABLE_TEXT", "MEDIA_ONLY", "MEDIA_URL_ONLY"].includes(String(material?.textStatus || "").toUpperCase());
        list.innerHTML = topics.length ? topics.map((topic) => {
            const selected = topic.id === state.assistantSelectedTopicId;
            const assessment = topic.assessment || {};
            const scoreStage = Number.isFinite(topic.score) && assessment.evidenceStatus === "RESEARCH_VERIFIED"
                ? " · 已核验" : "";
            const hasDraft = draftedTopicIds.has(topic.id);
            const topicMaterials = materials.filter((material) =>
                material.topicId === topic.id || (topic.materialIds || []).includes(material.id));
            const topicAuthors = resolveTopicAuthors(topic, topicMaterials, sourcesByKey);
            const authorHtml = topicAuthors.length
                ? assistantAuthorChipHtml(topicAuthors[0], { topic: true, extraCount: topicAuthors.length - 1 })
                : "";
            const primaryMaterialId = topicMaterials.length
                ? (topicMaterials.find(isAnalyzable)?.id || "")
                : (Array.isArray(topic.materialIds) && topic.materialIds.length ? topic.materialIds[0] : "");
            const quickActionHtml = hasDraft
                ? `<button type="button" class="x-outline-pill sm assistant-quick-btn" data-assistant-focus-draft="${escapeHtml(topic.id)}">改稿</button>`
                : (primaryMaterialId
                    ? `<button type="button" class="x-blue-pill sm assistant-quick-btn" data-assistant-research="${escapeHtml(primaryMaterialId)}" data-research-mode="FAST" data-assistant-topic-id="${escapeHtml(topic.id)}">极速成稿</button><button type="button" class="x-outline-pill sm assistant-quick-btn" data-assistant-research="${escapeHtml(primaryMaterialId)}" data-research-mode="RESEARCH" data-assistant-topic-id="${escapeHtml(topic.id)}">联网深挖</button>`
                    : "");
            const recSkillText = topic.recommendedSkillName ? ` · ${escapeHtml(topic.recommendedSkillName)}` : "";
            return `<div role="button" tabindex="0" class="assistant-topic-card${selected ? " active" : ""}" data-assistant-topic="${escapeHtml(topic.id)}">
                <span class="assistant-topic-score">${assistantScoreText(topic.score)}</span>
                <span class="assistant-topic-copy">
                    ${authorHtml}
                    <strong>${escapeHtml(topic.title || "未命名选题")}</strong>
                    <small>${escapeHtml(topic.summary || topic.reason || "等待补充摘要")}</small>
                    <span class="assistant-score-line">价值 ${assistantScoreText(topic.valueScore)} · 传播 ${assistantScoreText(topic.reachScore)} · ${(topic.materialIds || []).length} 源${scoreStage}${recSkillText}</span>
                    ${quickActionHtml ? `<span class="assistant-topic-quick-actions">${quickActionHtml}</span>` : ""}
                </span>
            </div>`;
        }).join("") : `<div class="assistant-empty">暂无符合条件的选题。</div>`;
        renderAssistantTopicDetail(dashboard);
    }

    function renderAssistantResearch(value) {
        if (!value || typeof value !== "object") return "";
        const research = value.research && typeof value.research === "object" ? value.research : value;
        const summary = research.summary || research.researchSummary || "";
        const sourceReads = research.sourceReads || research.sources || [];
        const claims = research.claims || research.usedClaims || [];
        const unresolved = research.unverified || research.unverifiedItems || research.unresolved || [];
        const conflicts = research.conflicts || [];
        const sourcesHtml = Array.isArray(sourceReads) && sourceReads.length ? `<div class="assistant-research-block"><strong>实际读取</strong><ol>${sourceReads.map((source) => {
            const title = source.title || source.name || source.url || "未命名来源";
            const url = source.url || source.sourceUrl || "";
            return `<li>${url ? `<a href="${safeHttpUrl(url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(title)} ↗</a>` : escapeHtml(title)}</li>`;
        }).join("")}</ol></div>` : "";
        const materialEvidence = Array.isArray(research.materialEvidence) ? research.materialEvidence : [];
        const materialHtml = materialEvidence.length
            ? `<div class="assistant-research-block"><strong>已采集一手原文</strong><ol>${materialEvidence.map((source) => {
                const roleLabel = assistantSourceRefLabel(source.id, materialEvidence);
                const url = String(source.url || "");
                const linkedPost = /^https:\/\/x\.com\/([A-Za-z0-9_]{1,15})\/status\/\d+$/.exec(url);
                const safeExternalUrl = !linkedPost && url ? safeHttpUrl(url) : "";
                const author = linkedPost ? `@${linkedPost[1]}` : (source.title || source.publisher || "原帖作者");
                const excerpts = Array.isArray(source.excerpts) ? source.excerpts : [];
                return `<li><span>${escapeHtml(roleLabel)} · </span>${linkedPost
                    ? `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(author)} 原帖 ↗</a>`
                    : (safeExternalUrl
                        ? `<a href="${safeExternalUrl}" target="_blank" rel="noopener noreferrer">${escapeHtml(author)} ↗</a>`
                        : escapeHtml(author))}
                    ${excerpts.filter((excerpt) => excerpt?.text).map((excerpt) => `<blockquote>${escapeHtml(excerpt.text)}</blockquote>`).join("")}</li>`;
            }).join("")}</ol></div>` : "";
        const formatSourceRef = (rawRef) => typeof assistantSourceRefLabel === "function" ? assistantSourceRefLabel(rawRef, materialEvidence) : String(rawRef || "");
        const relationLabels = { LINKED_IN_BODY: "正文链接指向", SAME_FINAL_URL: "指向同一最终网址" };
        const sourceRelations = Array.isArray(research.sourceRelations) ? research.sourceRelations.filter((entry) =>
            entry?.fromSourceId && entry.toSourceId && Object.hasOwn(relationLabels, entry.relation || entry.type)) : [];
        const relationsHtml = sourceRelations.length
            ? `<div class="assistant-research-block"><strong>来源关系</strong><ul>${sourceRelations.map((entry) =>
                `<li>${escapeHtml(formatSourceRef(entry.fromSourceId))} · ${relationLabels[entry.relation || entry.type]} · ${escapeHtml(formatSourceRef(entry.toSourceId))}</li>`
            ).join("")}</ul></div>` : "";
        const claimsHtml = Array.isArray(claims) && claims.length ? `<div class="assistant-research-block"><strong>说法核验</strong><ul>${claims.map((claim) => {
            const text = typeof claim === "string" ? claim : claim.text || claim.claim || claim.statement || "";
            const status = typeof claim === "object" ? claim.status || claim.verdict || claim.relation || "VERIFIED" : "VERIFIED";
            const refs = typeof claim === "object" ? claim.sourceIndexes || claim.sourceNumbers || claim.sourceIds || claim.supportIds || [] : [];
            return `<li><span class="assistant-state ${assistantStatusClass(status)}">${escapeHtml(assistantStatusLabel(status))}</span> ${escapeHtml(text)}
                ${Array.isArray(refs) && refs.length ? `<small>依据：${refs.map((r) => escapeHtml(formatSourceRef(r))).join("、")}</small>` : ""}</li>`;
        }).join("")}</ul></div>` : "";
        const issueBlock = (title, items, cssClass) => Array.isArray(items) && items.length
            ? `<div class="assistant-research-block ${cssClass}"><strong>${title}</strong><ul>${items.map((item) =>
                `<li>${escapeHtml(typeof item === "string" ? item : item.text || item.claim || item.statement || "")}</li>`).join("")}</ul></div>` : "";
        return `<div class="assistant-research-result">
            ${summary ? `<p class="assistant-research-summary">${escapeHtml(summary)}</p>` : ""}
            ${sourcesHtml}${materialHtml}${relationsHtml}${claimsHtml}
            ${issueBlock("待进一步核实", unresolved, "is-warning")}
            ${issueBlock("存在冲突的说法", conflicts, "is-conflict")}
        </div>`;
    }

    function renderAssistantEvidenceChanges(draft, fallbackMaterialId) {
        const changes = Array.isArray(draft?.evidenceChanges) ? draft.evidenceChanges : [];
        const factChanges = Array.isArray(draft?.factChanges) ? draft.factChanges : [];
        if (!draft?.sourcesChanged && !changes.length && !factChanges.length) return "";
        const targetMaterialId = String(draft?.materialId || fallbackMaterialId || "").trim();
        const labels = {
            SOURCE_TEXT_UPDATED: "来源正文已更新", SOURCE_NOW_UNREADABLE: "来源当前不可读",
            SOURCE_READABLE_AGAIN: "来源恢复可读", TOPIC_MEMBERS_CHANGED: "选题成员已变化",
            TOPIC_REMOVED: "原选题已移除", MATERIAL_TEXT_CHANGED: "素材正文已变化"
        };
        return `<div class="assistant-evidence-alert">
            <strong>研究依据已变化</strong>
            ${changes.length ? `<ul>${changes.map((change) => `<li>${escapeHtml(labels[change.type] || change.type || "依据变化")}
                ${change.url ? ` · <a href="${safeHttpUrl(change.url)}" target="_blank" rel="noopener noreferrer">查看来源 ↗</a>` : ""}</li>`).join("")}</ul>` : ""}
            ${factChanges.length ? `<div class="assistant-fact-changes">${factChanges.map((change) => `<p><del>${escapeHtml(change.before || "")}</del><ins>${escapeHtml(change.after || "")}</ins>
                ${Array.isArray(change.sourceIds) && change.sourceIds.length ? `<small>依据：${change.sourceIds.map((r) => escapeHtml(typeof assistantSourceRefLabel === "function" ? assistantSourceRefLabel(r, draft?.research?.materialEvidence || []) : r)).join("、")}</small>` : ""}</p>`).join("")}</div>` : ""}
            ${targetMaterialId
                ? (draft?.id
                    ? `<button type="button" class="x-outline-pill sm" data-assistant-research-upgrade="${escapeHtml(draft.id)}" data-material-id="${escapeHtml(targetMaterialId)}">按最新依据深挖出新版</button>`
                    : `<button type="button" class="x-outline-pill sm" data-assistant-research="${escapeHtml(targetMaterialId)}" data-regenerate="true">按最新依据重新研究</button>`)
                : ""}
        </div>`;
    }

    function renderAssistantObservationReview(dashboard, observations) {
        const metrics = [
            ["views", "浏览"], ["likes", "赞"], ["replies", "评论"],
            ["reposts", "转发"], ["bookmarks", "收藏"]
        ];
        const observationsHtml = observations.length ? observations.map((row) => {
            const status = String(row.status || "").toUpperCase();
            const windowLabel = Number(row.windowHours) === 168 ? "7d" : `${Number(row.windowHours || 0)}h`;
            const missing = new Set(Array.isArray(row.missingMetrics) ? row.missingMetrics : []);
            const measured = metrics.map(([key, label]) =>
                `<span><small>${label}</small><strong>${missing.has(key) || !Number.isFinite(Number(row[key])) ? "--" : fmtNum(row[key])}</strong></span>`).join("");
            const baseline = row.baseline || {};
            const viewsBaseline = baseline.views || {};
            const sampleCount = Number(viewsBaseline.sampleCount ?? baseline.candidateCount ?? 0);
            const minimum = Number(baseline.minimumRequired || 5);
            const baselineText = viewsBaseline.ready
                ? `同龄前 ${Number(baseline.maximumCompared || 20)} 条中位数 ${fmtNum(viewsBaseline.median)}${Number.isFinite(Number(viewsBaseline.percentChange)) ? ` · ${Number(viewsBaseline.percentChange) >= 0 ? "+" : ""}${Number(viewsBaseline.percentChange).toFixed(1)}%` : ""}`
                : `同龄样本 ${sampleCount}/${minimum}${Number.isFinite(Number(viewsBaseline.median)) ? ` · 当前中位数 ${fmtNum(viewsBaseline.median)}` : " · 暂无中位数"}`;
            return `<article class="assistant-observation-card">
                <div class="assistant-pane-head"><strong>${windowLabel} 观察</strong>
                    <span class="assistant-state ${assistantStatusClass(status)}">${escapeHtml(assistantStatusLabel(status))}</span></div>
                <p class="assistant-observation-time">${row.observedAt ? `实际测量 ${escapeHtml(formatAssistantDateTime(row.observedAt))}` :
                    row.checkedAt ? `检查于 ${escapeHtml(formatAssistantDateTime(row.checkedAt))}` : "尚未测量"}
                    ${Number.isFinite(Number(row.offsetMinutes)) ? ` · 偏移 ${Number(row.offsetMinutes).toFixed(0)} 分钟` : ""}</p>
                ${status === "OBSERVED" ? `<div class="assistant-observation-metrics">${measured}</div>` : ""}
                <p class="assistant-baseline-line">${escapeHtml(row.reason || baselineText)}</p>
                ${missing.size ? `<p class="assistant-missing-line">暂缺指标：${[...missing].map((k) => escapeHtml(({ views: "浏览量", likes: "赞", replies: "评论", reposts: "转发", bookmarks: "收藏" })[k] || k)).join("、")}</p>` : ""}
            </article>`;
        }).join("") : `<div class="assistant-empty compact">尚无 24h / 72h / 7d 同龄观察。</div>`;

        const followerRows = Array.isArray(dashboard.followers) ? dashboard.followers : dashboard.followers?.items || [];
        const followersHtml = followerRows.length ? followerRows.slice().sort((a, b) =>
            Number(b.observedAt || b.attemptedAt || 0) - Number(a.observedAt || a.attemptedAt || 0)).slice(0, 7).map((row) =>
            `<div class="assistant-follower-row"><span>${escapeHtml(row.localDate || formatAssistantDateTime(row.observedAt || row.attemptedAt))}</span>
                <span class="assistant-state ${assistantStatusClass(row.status)}">${escapeHtml(assistantStatusLabel(row.status))}</span>
                <strong>${row.followers !== null && row.followers !== undefined && Number.isFinite(Number(row.followers)) ? fmtNum(row.followers) : "--"}</strong>
                <em>${row.netChange !== null && row.netChange !== undefined && Number.isFinite(Number(row.netChange))
                    ? `${Number(row.netChange) >= 0 ? "+" : ""}${fmtNum(row.netChange)}` : escapeHtml(row.error || "")}</em></div>`
        ).join("") : `<div class="assistant-empty compact">尚无每日粉丝快照。</div>`;
        return `<div class="assistant-observations-grid"><div><h5>同龄表现</h5>${observationsHtml}</div>
            <div><h5>粉丝日变化</h5>${followersHtml}</div></div>`;
    }

    function renderAssistantHistory(history) {
        if (!history || typeof history !== "object") return `<div class="assistant-empty compact">暂无历史归档状态。</div>`;
        const received = Array.isArray(history.sources) ? history.sources : [];
        const ownerId = String(history.ownerAccountId || state.authStatus?.user?.id || "");
        const sources = received.filter(source => source && source.owner === true && ownerId
            && String(source.authorId || source.id || "") === ownerId);
        if (!sources.length) return `<div class="assistant-empty compact">尚无作者的历史归档。</div>`;
        const materials = sources.length === received.length ? history.materials : sources[0].timelineSavedCount;
        const interactions = sources.length === received.length ? history.interactions : sources[0].inboundSavedCount;
        const summary = `<div class="assistant-history-summary"><span>长期素材 <strong>${fmtNum(materials || 0)}</strong></span>
            <span>入站互动 <strong>${fmtNum(interactions || 0)}</strong></span>
            <span class="assistant-state ${history.complete ? "ok" : "running"}">${history.complete ? "动态与入站历史已完成" : "动态或入站仍待补齐"}</span></div>`;
        const rows = sources.map((source) => {
            const name = source.username ? `@${source.username}` : "匿名来源";
            const dimension = (prefix, label) => {
                const exhausted = source[prefix + "Exhausted"] === true;
                const complete = source[prefix + "BackfillComplete"] === true;
                const oldest = source[prefix + "OldestAt"];
                const newest = source[prefix + "NewestAt"];
                const failure = source[prefix + "Error"] || "";
                const rawReason = failure || source[prefix + "TerminalReason"] || (!complete ? "待续采" : "");
                const reason = { UPSTREAM_EXHAUSTED: "已拉至上游最早页", COVERAGE_LIMIT: "上游覆盖受限" }[rawReason] || rawReason;
                return `<div><strong>${label}</strong><span>${oldest ? formatAssistantDateTime(oldest) : "起点未知"} → ${newest ? formatAssistantDateTime(newest) : "终点未知"}</span>
                    <small>${failure ? escapeHtml(failure) : exhausted ? escapeHtml(reason || "已拉至最早页") : complete ? "本轮回填完成" : escapeHtml(reason)}</small></div>`;
            };
            const sourceComplete = source.timelineBackfillComplete === true && source.inboundBackfillComplete === true;
            const retryExhausted = source.timelineRetryExhausted === true || source.inboundRetryExhausted === true;
            const sourceStateClass = source.eligible === false ? "muted" : retryExhausted ? "warn" : sourceComplete ? "ok" : "running";
            const sourceStateText = source.eligible === false ? "已退出范围" : retryExhausted ? "等待手动重试" : sourceComplete ? "已归档 · 增量中" : "归档中";
            const retryBtnHtml = retryExhausted
                ? `<button type="button" class="link-inline-btn" data-assistant-history-retry="1">手动重试</button>`
                : "";
            return `<article class="assistant-history-source"><div class="assistant-pane-head"><span><strong>${escapeHtml(name)}</strong><small>本人</small></span>
                <span class="assistant-head-actions"><span class="assistant-state ${sourceStateClass}">${sourceStateText}</span>${retryBtnHtml}</span></div>
                <div class="assistant-history-dimensions">${dimension("timeline", "动态")}${dimension("inbound", "入站")}</div></article>`;
        }).join("");
        return summary + rows;
    }

    function renderAssistantTask(task) {
        const status = String(task.status || "").toUpperCase();
        const canCancel = ["QUEUED", "RUNNING", "WAITING_STYLE", "WAITING_SAMPLES"].includes(status);
        const repair = task.constraintRepair;
        const canRetry = !repair?.attempted && ["FAILED", "INTERRUPTED", "BLOCKED_CONFIG", "RESEARCH_PARTIAL", "CANCELLED", "WAITING_STYLE", "WAITING_SAMPLES"].includes(status);
        const retryLabel = status === "RESEARCH_PARTIAL" ? "继续深挖" : "重试";
        const kindText = typeof assistantTaskKindLabel === "function" ? assistantTaskKindLabel(task.kind) : (task.kind || "助手任务");
        const stageText = typeof assistantTaskStageLabel === "function" ? assistantTaskStageLabel(task.stage) : (task.stage === "OPTIMIZE_REVISION" ? "自动修订" : task.stage || "");
        const candidate = repair && !canCancel && !["READY", "SUCCEEDED"].includes(status) ? task.result?.text : "";
        return `<div class="assistant-task-row">
            <span class="assistant-state ${assistantStatusClass(status)}">${escapeHtml(assistantStatusLabel(status))}</span>
            <span><strong>${escapeHtml(kindText)}</strong><small>${escapeHtml(stageText)}${task.error ? (stageText ? " · " : "") + escapeHtml(task.error) : ""}</small></span>
            ${canCancel ? `<button type="button" class="link-inline-btn" data-assistant-task-action="cancel" data-task-id="${escapeHtml(task.id)}">取消</button>` : ""}
            ${canRetry ? `<button type="button" class="link-inline-btn" data-assistant-task-action="retry" data-task-id="${escapeHtml(task.id)}">${retryLabel}</button>` : ""}
            ${repair ? `<div class="assistant-task-result">${renderAssistantConstraintRepair(repair, status)}
                ${candidate ? `<p>当前稿件已保留。</p><details><summary>查看未采用的候选稿</summary><pre>${escapeHtml(candidate)}</pre></details>` : ""}
            </div>` : ""}
            ${task.research || task.result ? `<div class="assistant-task-result">${renderAssistantResearch(task.research || task.result)}</div>` : ""}
        </div>`;
    }

    function assistantDraftLengthText(length) {
        if (!length) return "";
        return `当前 ${length.actual} 个汉字 · 目标 ${length.min}–${length.max} · ${length.met ? "已达标" : "未达标"}`;
    }

    function computeAssistantDraftMetrics(rawText) {
        const text = String(rawText || "");
        const trimmed = text.trim();
        const hanMatches = trimmed.match(/\p{Script=Han}/gu);
        const hanCount = hanMatches ? hanMatches.length : 0;
        const fallbackWeighted = (src) => {
            if (!src) return 0;
            const normalized = src.replace(/https?:\/\/\S+/gi, "x".repeat(23));
            let len = 0;
            for (const ch of normalized) {
                len += ch.codePointAt(0) <= 0x7f ? 1 : 2;
            }
            return len;
        };
        const weighted = typeof computeWeightedTweetLength === "function"
            ? computeWeightedTweetLength(text)
            : fallbackWeighted(text);
        const tier = weighted <= 280 ? "STANDARD" : "PREMIUM";
        const tierLabel = weighted <= 280
            ? `X 权重 ${weighted}/280（标准帖）`
            : `X 权重 ${weighted}/280（Premium 长帖）`;
        let hook = { warn: false, label: "" };
        if (trimmed) {
            const firstSegment = trimmed.split(/\r?\n/)[0] || "";
            const firstHan = (firstSegment.match(/\p{Script=Han}/gu) || []).length;
            if (/^\s*(值得注意的是|总而言之|今天我们来|让我们看看|接下来|在当今|随着.{1,15}发展|众所周知|大家好)/.test(firstSegment)) {
                hook = { warn: true, label: "首句偏铺垫，宜直接抛判断" };
            } else if (firstHan > 90) {
                hook = { warn: true, label: "首段偏密，前两行宜断句留白" };
            } else {
                hook = { warn: false, label: "前两行钩子紧凑" };
            }
        }
        const urlMatches = text.match(/https?:\/\/[^\s<>"')\]）】，。；！？]+/gi) || [];
        const { hasExternalLink } = classifyDraftLinks(urlMatches);
        return { hanCount, weighted, tier, tierLabel, hook, hasExternalLink };
    }

    function renderAssistantDraftMetricsHtml(rawText) {
        const m = computeAssistantDraftMetrics(rawText);
        const esc = typeof escapeHtml === "function" ? escapeHtml : (v) => String(v ?? "");
        const parts = [
            `<span>${m.hanCount} 汉字</span>`,
            `<span class="${m.weighted > 280 ? "is-premium" : "is-standard"}">${esc(m.tierLabel)}</span>`
        ];
        if (m.hook.label) {
            parts.push(`<span class="${m.hook.warn ? "is-warn" : "is-ok"}">${esc(m.hook.label)}</span>`);
        }
        if (m.hasExternalLink) {
            parts.push(`<span class="is-warn">正文含外链，宜移至首评规避降权</span>`);
        }
        return parts.join("");
    }

    function assistantDraftResearchStatus(draft) {
        const res = draft?.research && typeof draft.research === "object" ? draft.research : null;
        if (!res) {
            return { hasDeepResearch: false, webSourceCount: 0, badgeCls: "muted", badgeLabel: "仅原帖依据" };
        }
        const webIds = new Set();
        if (Array.isArray(res.evidence)) {
            for (const item of res.evidence) {
                const sid = String(item?.id || "").trim();
                if (sid && !sid.startsWith("material-")) webIds.add(sid);
            }
        }
        if (Array.isArray(res.sourceReads)) {
            for (const item of res.sourceReads) {
                if (String(item?.status || "").toUpperCase() === "READ") {
                    const key = String(item?.id || item?.url || "").trim();
                    if (key) webIds.add(key);
                }
            }
        }
        const webSourceCount = webIds.size;
        const quality = String(res.quality || "").toUpperCase();
        const isDirectOnly = quality === "MATERIAL_DIRECT" || res.fastMode === true;
        const hasDeepResearch = webSourceCount > 0
            || (!isDirectOnly && Boolean(res.draftable || (Array.isArray(res.claims) && res.claims.length)));
        const badgeLabel = hasDeepResearch
            ? (webSourceCount > 0 ? `已融合深挖 · ${webSourceCount} 源` : "已融合深挖")
            : "仅原帖依据";
        return {
            hasDeepResearch,
            webSourceCount,
            badgeCls: hasDeepResearch ? "ok" : "muted",
            badgeLabel
        };
    }

    function resStatusSummaryLabel(draft) {
        const st = assistantDraftResearchStatus(draft);
        return st.hasDeepResearch
            ? (st.webSourceCount > 0 ? `研究依据（已融合 ${st.webSourceCount} 个外部网页与一手原文）` : "研究依据（已融合深度研究）")
            : "研究依据（当前仅含原帖原文，可点「联网深挖出新版」补充外部资料）";
    }

    function resolveAssistantDraftSourcePost(draft, materials) {
        if (!draft) return null;
        if (Object.hasOwn(draft, "quoteSourcePost")) return draft.quoteSourcePost;
        const snapshot = draft.research?.snapshot;
        const frozen = Array.isArray(snapshot?.materials) ? snapshot.materials : [];
        const id = String(draft.materialId || draft.research?.materialId || snapshot?.selectedMaterialId
            || (frozen.length === 1 ? frozen[0]?.id : "") || "").trim();
        const stable = /^[0-9]{2,20}$/.test(id);
        const matched = frozen.find((item) => id && String(item?.id || "") === id);
        const postLink = /^https?:\/\/(?:(?:www|mobile)\.)?(?:x|twitter)\.com\/(?:([A-Za-z0-9_]{1,15})\/status|i\/web\/status)\/([0-9]{2,20})(?:\/(?:photo|video)\/[0-9]+)?\/?(?:[?#]\S*)?$/i;
        let link = String(matched?.sourceUrl || "").trim().match(postLink);
        if (!matched?.sourceUrl) {
            for (const evidence of draft.research?.materialEvidence || []) {
                if (id && !["author", "author-article", "link"].some((role) => evidence.id === `material-${id}-${role}`)) continue;
                if (!id && evidence.id && !/^material-.+-(?:author|author-article|link)$/.test(evidence.id)) continue;
                const cited = String(evidence.url || "").trim().match(postLink);
                if (!cited) {
                    if (id && !stable) return null;
                    continue;
                }
                if (stable && cited[2] !== id) continue;
                if (link && link[2] !== cited[2]) return null;
                link = cited;
            }
        }
        const live = stable && Array.isArray(materials) ? materials.find((item) => String(item?.id || "") === id) : null;
        if (!matched && !link && live) link = String(live.sourceUrl || "").trim().match(postLink);
        const tweetId = stable ? id : link?.[2];
        if (!tweetId) return null;
        const linkedHandle = link?.[2] === tweetId && String(link[1] || "").toLowerCase() !== "i" ? link[1] : "";
        const username = String(linkedHandle || matched?.username || live?.username || "").replace(/^@/, "");
        const handle = /^[A-Za-z0-9_]{1,15}$/.test(username) ? username : "i";
        return { tweetId, url: `https://x.com/${handle}/status/${tweetId}` };
    }

    function assistantDraftNumberChangesText(changes) {
        return changes?.met === false && changes.added?.length
            ? `较基线新增数字 ${changes.added.join("、")}，请审核。` : "";
    }

    function renderAssistantConstraintRepair(repair, status) {
        if (!repair) return "";
        const checkText = ({ hanLength, numberChanges }) => {
            const checks = [];
            if (hanLength) {
                checks.push(`${hanLength.actual} 个汉字（目标 ${hanLength.min}–${hanLength.max}，${hanLength.met ? "已达标" : "未达标"}）`);
            }
            if (numberChanges) {
                checks.push(numberChanges.met ? "无新增数字" : `新增数字 ${numberChanges.added.join("、")}`);
            }
            return checks.join(" · ");
        };
        const label = repair.final ? "已自动修订一次" : {
            QUEUED: "等待自动修订", RUNNING: "正在自动修订"
        }[status] || (repair.attempted ? "自动修订未完成" : "自动修订未开始");
        return `<p class="inline-feedback"><strong>${label}</strong><br>首次校验：${escapeHtml(checkText(repair.initial))}
            ${repair.final ? `<br>最终校验：${escapeHtml(checkText(repair.final))}` : ""}</p>`;
    }

    function assistantMaterialStatusBadge(material) {
        const raw = String(material?.textStatus || "").toUpperCase();
        const articlePreview = raw === "ARTICLE_PREVIEW" || String(material?.articleTextStatus || "").toUpperCase() === "PREVIEW";
        if (articlePreview) return { cls: "running", label: "文章预览·待补全文" };
        const map = {
            TEXT_ONLY: { cls: "ok", label: "原创/引用正文" },
            ARTICLE_FULL: { cls: "ok", label: "文章全文" },
            COMPLETE: { cls: "ok", label: "完整正文" },
            QUOTE_TEXT_MISSING: { cls: "muted", label: "引用正文不可读" },
            QUOTE_SOURCE_MISSING: { cls: "muted", label: "引用来源待核" },
            MEDIA_ONLY: { cls: "muted", label: "仅媒体无文字" },
            MEDIA_URL_ONLY: { cls: "muted", label: "仅媒体链接" },
            NO_ANALYZABLE_TEXT: { cls: "muted", label: "无可分析文字" }
        };
        return map[raw] || { cls: "muted", label: material?.textStatus || "文字状态未知" };
    }

    function renderAssistantMaterialBody(material) {
        const hasQuote = Boolean(material?.quotedText || String(material?.quotedTextStatus || "").toUpperCase() === "MISSING");
        const isArticlePreview = String(material?.textStatus || "").toUpperCase() === "ARTICLE_PREVIEW"
            || String(material?.articleTextStatus || "").toUpperCase() === "PREVIEW";
        const hasArticle = Boolean(material?.articleText || isArticlePreview);
        if (!hasQuote && !hasArticle) {
            const combined = typeof materialText === "function" ? materialText(material) : (material?.text || "");
            return `<p>${escapeHtml(combined || "无可分析文字")}</p>`;
        }
        const parts = [];
        if (material?.text) {
            parts.push(`<p>${escapeHtml(material.text)}</p>`);
        }
        if (hasQuote) {
            const qUser = material.quotedUsername ? `@${material.quotedUsername}` : "原帖作者";
            const qUrl = material.quotedUsername && material.quotedId && typeof safeHttpUrl === "function"
                ? safeHttpUrl(`https://x.com/${material.quotedUsername}/status/${material.quotedId}`) : "";
            const qBody = material.quotedText || "原帖正文不可读";
            parts.push(`<div class="assistant-material-quote"><strong>引用 ${escapeHtml(qUser)}</strong>${qUrl ? ` · <a href="${qUrl}" target="_blank" rel="noopener noreferrer">被引用原帖 ↗</a>` : ""}<p>${escapeHtml(qBody)}</p></div>`);
        }
        if (hasArticle) {
            const roleNote = material.articleRole === "QUOTED" ? "被引用文章" : "作者文章";
            const title = isArticlePreview ? `${roleNote}预览 · 待补全文` : `${roleNote}全文`;
            const body = material.articleText || "仅预览";
            parts.push(`<div class="assistant-material-article${isArticlePreview ? " is-preview" : ""}"><strong>${escapeHtml(title)}</strong><p>${escapeHtml(body)}</p></div>`);
        }
        return parts.join("");
    }

    const DEFAULT_ASSISTANT_SKILLS = [
        { id: "BREAKING_FLASH", name: "突发快评", badge: "快讯爆款", summary: "首句爆点定性 + 核心增量事实 + 二阶影响判断" },
        { id: "DEEP_BREAKDOWN", name: "深度拆解", badge: "高藏高转", summary: "反常识立论 + 底层机制拆解 + 隐含边界" },
        { id: "SHARP_OPINION", name: "犀利观点", badge: "高评论互动", summary: "非共识判断 + 事实支点 + 具象分歧点" },
        { id: "ACTIONABLE_LIST", name: "干货清单", badge: "高收藏转化", summary: "直击问题 + 高信噪比要点清单 + 适用场景" }
    ];

    function assistantWritingSkills(dashboard) {
        const list = dashboard?.writingSkills;
        return Array.isArray(list) && list.length ? list : DEFAULT_ASSISTANT_SKILLS;
    }

    function assistantSkillLabel(skillId, dashboard) {
        if (!skillId) return "";
        const found = assistantWritingSkills(dashboard || state.assistantDashboard).find((item) => item.id === skillId);
        return found ? found.name : skillId;
    }

    function selectedAssistantTopic(dashboard) {
        const id = state.assistantSelectedTopicId;
        return dashboard?.detailTopic && dashboard.detailTopic.id === id ? dashboard.detailTopic
            : (dashboard?.topics || []).find((topic) => topic.id === id);
    }

    function renderAssistantSkillBar(dashboard) {
        const bar = $("assistantSkillBar");
        if (!bar) return;
        const skills = assistantWritingSkills(dashboard);
        const validIds = new Set(["AUTO", ...skills.map((item) => item.id)]);
        const activeId = validIds.has(state.assistantSelectedSkill)
            ? state.assistantSelectedSkill : "AUTO";
        state.assistantSelectedSkill = activeId;
        const selectedTopic = selectedAssistantTopic(dashboard);
        const autoHint = selectedTopic?.recommendedSkillName || "自动";
        const autoActive = activeId === "AUTO";
        const autoChip = `<button type="button" class="pill-chip sm assistant-skill-chip${autoActive ? " active" : ""}" data-assistant-skill="AUTO" aria-pressed="${autoActive ? "true" : "false"}" title="按当前选题内容特征自动匹配最合适的爆款体裁">智能匹配<em>${escapeHtml(autoHint)}</em></button>`;
        bar.innerHTML = autoChip + skills.map((skill) => {
            const active = skill.id === activeId;
            return `<button type="button" class="pill-chip sm assistant-skill-chip${active ? " active" : ""}" data-assistant-skill="${escapeHtml(skill.id)}" aria-pressed="${active ? "true" : "false"}" title="${escapeHtml(skill.summary || "")}">${escapeHtml(skill.name)}</button>`;
        }).join("");
    }

    function renderAssistantMaterialCard(material, draftedMaterialIds, options = {}) {
        const badge = assistantMaterialStatusBadge(material);
        const fmt = typeof formatAssistantDateTime === "function" ? formatAssistantDateTime : () => "";
        const publishedText = material.publishedAt ? `发布 ${fmt(material.publishedAt)}` : "";
        const collectedStamp = material.collectedAt || material.lastCollectedAt || (material.publishedAt ? material.updatedAt : 0);
        const collectedText = collectedStamp ? `采集 ${fmt(collectedStamp)}` : "";
        const analyzable = Boolean(typeof materialText === "function" ? materialText(material)
            : (material.text || material.quotedText || material.articleText))
            && !["NO_ANALYZABLE_TEXT", "MEDIA_ONLY", "MEDIA_URL_ONLY"].includes(String(material.textStatus || "").toUpperCase());
        const url = resolveRepostMaterialUrl(material, "");
        const fallbackSrc = (state.assistantDashboard?.sources || []).find((s) =>
            (material.authorId && s.authorId === material.authorId)
            || (material.username && String(s.username || "").toLowerCase() === String(material.username).toLowerCase()));
        const fallbackName = material.authorName || fallbackSrc?.displayName || material.username || fallbackSrc?.username || "unknown";
        const authorHtml = typeof assistantAuthorChipHtml === "function"
            ? assistantAuthorChipHtml({
                sourceId: fallbackSrc?.id || "",
                authorId: material.authorId || fallbackSrc?.authorId || "",
                username: material.username || fallbackSrc?.username || "unknown",
                displayName: fallbackName,
                avatarUrl: material.authorAvatarUrl || fallbackSrc?.avatarUrl || "",
                verified: Boolean(material.authorVerified || fallbackSrc?.verified)
            }, { materialId: material.id })
            : `<strong>@${escapeHtml(material.username || "unknown")}</strong>`;
        const formatMetric = (n) => typeof fmtNum === "function" ? fmtNum(n) : String(n);
        const engagementParts = [];
        if (Number(material.views) > 0) engagementParts.push(`浏览 ${formatMetric(Number(material.views))}`);
        if (Number(material.reposts) > 0) engagementParts.push(`转 ${formatMetric(Number(material.reposts))}`);
        if (Number(material.replies) > 0) engagementParts.push(`评 ${formatMetric(Number(material.replies))}`);
        if (Number(material.likes) > 0) engagementParts.push(`赞 ${formatMetric(Number(material.likes))}`);
        const engagementHtml = engagementParts.length
            ? `<span class="assistant-material-engagement">${escapeHtml(engagementParts.join(" · "))}</span>`
            : "";
        return `<article class="assistant-material">
            ${options.selectable ? `<label class="assistant-material-select"><input type="checkbox" data-assistant-material-id="${escapeHtml(material.id)}" />选择</label>` : ""}
            <div class="assistant-material-meta">${authorHtml}
                ${publishedText ? `<span>${escapeHtml(publishedText)}</span>` : ""}
                ${collectedText ? `<span>${escapeHtml(collectedText)}</span>` : ""}
                ${engagementHtml}
                <span class="assistant-state ${badge.cls}">${escapeHtml(badge.label)}</span>
            </div>
            ${renderAssistantMaterialBody(material)}
            <div class="assistant-material-actions">
                ${url ? `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">查看原帖 ↗</a>` : ""}
                ${options.showTopicJump && material.topicId ? `<button type="button" class="link-inline-btn" data-assistant-open-topic="${escapeHtml(material.topicId)}">查看所属选题</button>` : ""}
                ${analyzable ? `<button type="button" class="link-inline-btn" data-assistant-research="${escapeHtml(material.id)}" data-research-mode="FAST">极速成稿</button>` : ""}
                ${analyzable ? `<button type="button" class="link-inline-btn" data-assistant-research="${escapeHtml(material.id)}">研究并生成</button>` : ""}
                ${analyzable && draftedMaterialIds.has(material.id) ? `<button type="button" class="link-inline-btn" data-assistant-research="${escapeHtml(material.id)}" data-research-mode="FAST" data-regenerate="true">极速重成稿</button>` : ""}
                ${analyzable && draftedMaterialIds.has(material.id) ? `<button type="button" class="link-inline-btn" data-assistant-research="${escapeHtml(material.id)}" data-regenerate="true">重新研究</button>` : ""}
            </div>
        </article>`;
    }

    function renderAssistantTopicDetail(dashboard) {
        const container = $("assistantTopicDetail");
        const topic = selectedAssistantTopic(dashboard);
        if (!topic) {
            if (state.assistantDraftDirty && $("assistantDraftEditor")) return;
            container.innerHTML = `<div class="assistant-empty">选择一个选题查看来源、研究和稿件。</div>`;
            return;
        }
        const materials = (dashboard.materials || []).filter((material) =>
            material.topicId === topic.id || (topic.materialIds || []).includes(material.id));
        const tasks = (dashboard.tasks || []).filter((task) => task.topicId === topic.id);
        const taskHtml = tasks.length ? tasks.sort((a, b) => String(b.updatedAt || "").localeCompare(String(a.updatedAt || "")))
            .map(renderAssistantTask).join("") : `<div class="assistant-empty compact">尚未启动研究。</div>`;
        const topicDrafts = (dashboard.drafts || []).filter((item) => item.topicId === topic.id)
            .sort((a, b) => String(b.updatedAt || b.createdAt || "").localeCompare(String(a.updatedAt || a.createdAt || "")));
        const requestedDraftId = state.assistantSelectedDraftByTopic[topic.id];
        const draft = topicDrafts.find((item) => item.id === requestedDraftId) || topicDrafts[0];
        if (draft) state.assistantSelectedDraftByTopic[topic.id] = draft.id;
        const currentEditor = $("assistantDraftEditor");
        const currentDraftBox = currentEditor?.closest("[data-draft-id]");
        if (state.assistantDraftDirty && currentDraftBox && currentDraftBox.dataset.draftId !== draft?.id) return;
        const focused = currentEditor && document.activeElement === currentEditor;
        if (draft && currentDraftBox?.dataset.draftId === draft.id
            && (state.assistantDraftDirty || (currentDraftBox.dataset.draftVersion === String(draft.version)
                && (focused || $("assistantDraftVersions")?.hidden === false)))) {
            $("assistantTopicTasks").innerHTML = taskHtml;
            return;
        }
        const otherTopics = (dashboard.topics || []).filter((item) => item.id !== topic.id);
        const draftedMaterialIds = new Set(topicDrafts.map((item) => item.materialId));
        const organizeMode = Boolean(state.assistantOrganizeMode);
        const isAnalyzable = (material) => Boolean(typeof materialText === "function" ? materialText(material)
            : (material?.text || material?.quotedText || material?.articleText))
            && !["NO_ANALYZABLE_TEXT", "MEDIA_ONLY", "MEDIA_URL_ONLY"].includes(String(material?.textStatus || "").toUpperCase());
        const primaryMaterial = materials.find(isAnalyzable) || null;
        const sourceHtml = materials.length
            ? materials.map((material) => renderAssistantMaterialCard(material, draftedMaterialIds, { selectable: organizeMode })).join("")
            : `<div class="assistant-empty compact">该选题暂无文字素材。</div>`;
        const needsConfirm = topic.needsConfirmation === true || String(topic.status || "").toUpperCase() === "NEEDS_CONFIRMATION";
        const numberChangesText = assistantDraftNumberChangesText(draft?.numberChanges);
        const researchBlockHtml = draft?.research && typeof renderAssistantResearch === "function"
            ? `<details class="assistant-collapsible assistant-evidence-collapsible"><summary>${resStatusSummaryLabel(draft)}</summary>${renderAssistantResearch(draft.research)}</details>`
            : "";
        const sourcePost = draft ? resolveAssistantDraftSourcePost(draft, materials) : null;
        const draftResStatus = draft ? assistantDraftResearchStatus(draft) : null;
        const upgradeMatId = draft?.materialId || draft?.research?.materialId || draft?.research?.snapshot?.selectedMaterialId || "";
        const draftHtml = draft ? `<div class="assistant-draft" data-draft-id="${escapeHtml(draft.id)}" data-draft-version="${Number(draft.version || 0)}">
            <div class="assistant-pane-head"><h3 id="assistantDraftTitle">初稿 v${Number(draft.version || 0)}</h3>
                <div class="assistant-draft-head-actions">
                    <span class="assistant-state ${draftResStatus.badgeCls}">${escapeHtml(draftResStatus.badgeLabel)}</span>
                    ${draft.skillId ? `<span class="assistant-state ok">${escapeHtml(assistantSkillLabel(draft.skillId, dashboard))}</span>` : ""}
                    ${topicDrafts.length > 1 ? `<select id="assistantDraftSelect" class="x-select" aria-label="选择独立稿件">${topicDrafts.map((item, index) =>
                        `<option value="${escapeHtml(item.id)}"${item.id === draft.id ? " selected" : ""}>稿件 ${index + 1} · v${Number(item.version || 0)}</option>`).join("")}</select>` : ""}
                    <button type="button" class="x-outline-pill sm" data-assistant-history="${escapeHtml(draft.id)}">版本记录</button>
                </div></div>
            ${renderAssistantEvidenceChanges(draft, upgradeMatId)}
            ${draft.angleInstruction ? `<small class="assistant-draft-angle">${escapeHtml(draft.angleInstruction)}</small>` : ""}
            <textarea id="assistantDraftEditor" rows="9" data-i18n-ignore>${escapeHtml(draft.text || "")}</textarea>
            <div id="assistantDraftLiveMetrics" class="assistant-draft-metrics" data-i18n-ignore>${renderAssistantDraftMetricsHtml(draft.text || "")}</div>
            <p id="assistantDraftLength" class="inline-feedback"${draft.hanLength ? "" : " hidden"}>${escapeHtml(assistantDraftLengthText(draft.hanLength))}</p>
            <p id="assistantDraftNumberChanges" class="inline-feedback is-error"${numberChangesText ? "" : " hidden"}>${escapeHtml(numberChangesText)}</p>
            ${renderAssistantConstraintRepair(draft.constraintRepair, "SUCCEEDED")}
            <p class="inline-feedback" id="assistantDraftSaveState" role="status"></p>
            <div id="assistantDraftConflict" hidden>
                <details open><summary>另一页面保存的正文</summary><pre id="assistantDraftRemoteText"></pre></details>
                <button type="button" class="x-outline-pill sm" data-assistant-resolve-conflict="local">将本页正文保存为新版本</button>
                <button type="button" class="link-inline-btn" data-assistant-resolve-conflict="remote">采用另一页面的版本</button>
            </div>
            <div class="assistant-revision-panel">
                <div class="assistant-optimize-presets" role="group" aria-label="切换体裁与改稿快捷项">
                    ${assistantWritingSkills(dashboard).map((skill) =>
                        `<button type="button" class="pill-chip sm${draft.skillId === skill.id ? " active" : ""}" data-assistant-skill-rewrite="${escapeHtml(skill.id)}" data-draft-id="${escapeHtml(draft.id)}">${escapeHtml(skill.name)}</button>`
                    ).join("")}
                    <span class="assistant-preset-sep" aria-hidden="true"></span>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="拒绝复述与总结腔，结合深度研究与一手素材换一个更有冲突张力与评论讨论度的话题切口重写新版本，首句直击切身利害或反差" data-draft-id="${escapeHtml(draft.id)}">换话题切口</button>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="充分调取深度研究外部资料与原帖细节补充信息差（横向对比、真实成本账或隐含边界），写出让人愿意收藏转发的增量洞察" data-draft-id="${escapeHtml(draft.id)}">补深挖增量</button>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="精炼为单条标准推文篇幅（控制在115个汉字以内，总长不超过280权重），第一句直接给出最硬结论，保留核心事实且不要新增任何数字" data-draft-id="${escapeHtml(draft.id)}">≤280短帖</button>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="将正文控制在180–220个汉字以内，保留核心事实与原始数据，不要新增任何数字" data-draft-id="${escapeHtml(draft.id)}">180–220字</button>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="结论前置：第一句直接给出核心判断，再展开关键论据，不要新增任何数字" data-draft-id="${escapeHtml(draft.id)}">结论前置</button>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="改为更自然口语化的表达，去除AI腔、套话与生硬过渡词，保持原意且不新增数字" data-draft-id="${escapeHtml(draft.id)}">去AI味</button>
                    <button type="button" class="pill-chip sm" data-assistant-optimize-preset="仅做文字润色与节奏优化，保持原有结构，严禁新增任何数字、百分比或未核实信息" data-draft-id="${escapeHtml(draft.id)}">仅润色</button>
                </div>
                <div class="assistant-draft-actions">
                    <select id="assistantOptimizeGenTargetSelect" class="x-select assistant-gen-select sm" aria-label="本次改稿生成模型" data-i18n-ignore>${renderGenerationTargetOptionsHtml(state.assistantGenerationTargetSpec)}</select>
                    <select id="assistantOptimizeEffortSelect" class="x-select assistant-effort-select sm" aria-label="本次改稿推理强度" data-i18n-ignore${!providerSupportsEffort((state.assistantGenerationTargetSpec || "chatgpt").split("|")[0]) ? " disabled" : ""}>${renderReasoningEffortOptionsHtml(state.assistantGenerationEffort, (state.assistantGenerationTargetSpec || "chatgpt").split("|")[0])}</select>
                    <input id="assistantOptimizeInstruction" type="text" placeholder="改稿要求（留空直接换高流量话题切口）…" aria-label="改稿要求" />
                    ${upgradeMatId && !draftResStatus.hasDeepResearch ? `<button type="button" class="x-blue-pill sm" data-assistant-research-upgrade="${escapeHtml(draft.id)}" data-material-id="${escapeHtml(upgradeMatId)}">联网深挖出新版</button>` : ""}
                    <button type="button" class="${draftResStatus.hasDeepResearch ? "x-blue-pill" : "x-outline-pill"} sm" data-assistant-optimize="${escapeHtml(draft.id)}">改写新版本</button>
                    ${upgradeMatId && draftResStatus.hasDeepResearch ? `<button type="button" class="x-outline-pill sm" data-assistant-research-upgrade="${escapeHtml(draft.id)}" data-material-id="${escapeHtml(upgradeMatId)}">重跑深挖</button>` : ""}
                </div>
            </div>
            <div class="assistant-draft-export-actions">
                <div class="assistant-export-secondary">
                    <button type="button" class="x-outline-pill sm" data-assistant-copy-draft="${escapeHtml(draft.id)}">复制文案</button>
                    <button type="button" class="x-outline-pill sm" data-assistant-simulate-draft="${escapeHtml(draft.id)}">草稿体检</button>
                </div>
                <div class="assistant-export-primary">
                    <button type="button" class="x-outline-pill sm" data-assistant-tweet-intent="${escapeHtml(draft.id)}">发推 ↗</button>
                    ${sourcePost ? `<button type="button" class="x-outline-pill sm" data-assistant-quote-intent="${escapeHtml(draft.id)}" data-quote-url="${escapeHtml(sourcePost.url)}">引用原帖 ↗</button><button type="button" class="x-outline-pill sm" data-assistant-publish-now="${escapeHtml(draft.id)}" data-quote-source="true">引用直发</button><button type="button" class="x-blue-pill sm" data-assistant-schedule="${escapeHtml(draft.id)}" data-quote-source="true">引用定时</button>` : ""}
                    <button type="button" class="${sourcePost ? "x-outline-pill" : "x-blue-pill"} sm" data-assistant-schedule="${escapeHtml(draft.id)}">转入定时</button>
                </div>
            </div>
            ${researchBlockHtml}
            <div id="assistantDraftVersions" class="assistant-version-list" hidden></div>
            <section id="assistantDraftVersionPreview" class="assistant-detail-section" hidden>
                <h4 id="assistantDraftVersionTitle"></h4>
                <textarea id="assistantDraftVersionText" aria-labelledby="assistantDraftVersionTitle" rows="7" readonly data-i18n-ignore></textarea>
                <button type="button" id="assistantAdoptDraftVersion" class="x-outline-pill sm">采用此版本</button>
            </section>
        </div>` : "";
        const publications = draft ? (dashboard.publications || []).filter((item) => item.draftId === draft.id
            && (item.topicId === topic.id || !item.topicId || (item.status === "SCHEDULED" && !item.deleted))) : [];
        const publicationHtml = publications.length ? publications.map((publication) => {
            const pubFmt = typeof formatAssistantDateTime === "function" ? formatAssistantDateTime : () => "";
            const scheduledAt = publication.scheduledAtEpochMs || publication.scheduledAt;
            const status = String(publication.status || "").toUpperCase();
            let pubTime = "";
            if (publication.publishedAt) pubTime = `发布于 ${pubFmt(publication.publishedAt)}`;
            else if (status === "FAILED") pubTime = publication.error || "未完成发布";
            else if (status === "UNCERTAIN") pubTime = "回执待核实";
            else if (scheduledAt) pubTime = `排期于 ${pubFmt(scheduledAt)}`;
            else if (publication.jobId) pubTime = "已同步至定时队列";
            else if (status === "PENDING") pubTime = "已登记排期";
            return `<div class="assistant-publication-row">
            <span class="assistant-state ${assistantStatusClass(publication.status)}">${escapeHtml(assistantStatusLabel(publication.status))}</span>
            ${assistantVerificationButtons(publication)}
            <span><strong>稿件版本 v${Number(publication.draftVersion || 0)}</strong><small>${escapeHtml(pubTime)}</small></span>
            ${status === "SCHEDULED" && !publication.deleted
                ? `<button type="button" class="link-inline-btn" data-assistant-update-scheduled="${escapeHtml(publication.id || publication.intentId)}" data-publication-draft-id="${escapeHtml(draft.id)}" data-publication-intent="${escapeHtml(publication.intentId || publication.id)}"${!state.assistantDraftDirty && Number(draft.version) === Number(publication.draftVersion) ? " hidden" : ""}>更新为当前版本</button>` : ""}
        </div>`;
        }).join("") : "";
        const observationRows = Array.isArray(dashboard.observations) ? dashboard.observations : dashboard.observations?.items || [];
        const topicObservations = observationRows.filter((row) => row.topicId === topic.id);
        const reviewHtml = topicObservations.length ? `<details class="assistant-collapsible assistant-detail-section">
                <summary>发布复盘</summary>
                <div class="assistant-collapsible-body">${renderAssistantObservationReview(dashboard, topicObservations)}</div>
            </details>` : "";
        const headPrimaryAction = draft
            ? ""
            : (primaryMaterial
                ? `<button type="button" class="x-blue-pill sm" data-assistant-research="${escapeHtml(primaryMaterial.id)}" data-research-mode="FAST">极速成稿</button><button type="button" class="x-outline-pill sm" data-assistant-research="${escapeHtml(primaryMaterial.id)}" data-research-mode="RESEARCH">联网深挖</button>`
                : "");
        const organizeToggleHtml = materials.length
            ? `<button type="button" class="link-inline-btn" data-assistant-toggle-organize>${organizeMode ? "完成整理" : "整理归并"}</button>`
            : "";
        const organizeToolsHtml = organizeMode ? `<div class="assistant-topic-tools">
                <select id="assistantMoveTarget" class="x-select"><option value="">拆分为新选题</option>${otherTopics.map((item) => `<option value="${escapeHtml(item.id)}">合并到：${escapeHtml(item.title || item.id)}</option>`).join("")}</select>
                <button type="button" class="x-outline-pill sm" id="assistantMoveMaterialsBtn">移动所选</button>
            </div>` : "";
        const tasksSectionHtml = `<section class="assistant-detail-section"${tasks.length ? "" : " hidden"}><div id="assistantTopicTasks">${taskHtml}</div></section>`;
        const detailSectionsHtml = draft
            ? `<section class="assistant-detail-section">${draftHtml}</section>
            ${tasksSectionHtml}
            <details class="assistant-collapsible assistant-detail-section"${organizeMode ? " open" : ""}>
                <summary>文字来源（${materials.length}）</summary>
                <div class="assistant-collapsible-body">
                    ${organizeToggleHtml ? `<div class="assistant-section-head assistant-section-head-end">${organizeToggleHtml}</div>` : ""}
                    ${sourceHtml}${organizeToolsHtml}
                </div>
            </details>
            ${publications.length ? `<details class="assistant-collapsible assistant-detail-section">
                <summary>发布关联（${publications.length}）</summary>
                <div class="assistant-collapsible-body">${publicationHtml}</div>
            </details>` : ""}`
            : `${tasksSectionHtml}
            <section class="assistant-detail-section">
                <div class="assistant-section-head"><h4>文字来源（${materials.length}）</h4>${organizeToggleHtml}</div>
                ${sourceHtml}${organizeToolsHtml}
            </section>`;
        container.innerHTML = `<div class="assistant-detail-head">
                <div><h3>${escapeHtml(topic.title || "未命名选题")}</h3><p>${escapeHtml(topic.reason || topic.summary || "")}</p>
                    ${Array.isArray(topic.tags) && topic.tags.length ? `<small>${topic.tags.map((tag) => `#${escapeHtml(tag)}`).join(" ")}</small>` : ""}</div>
                <div class="assistant-head-actions">
                    ${headPrimaryAction}
                    ${needsConfirm ? `<button type="button" class="x-outline-pill sm" data-assistant-confirm-topic="${escapeHtml(topic.id)}">确认独立保留</button>` : ""}
                    <span class="assistant-total-score">${assistantScoreText(topic.score)}</span>
                </div>
            </div>
            ${detailSectionsHtml}${reviewHtml}`;
        if (focused && draft && currentDraftBox?.dataset.draftId === draft.id) {
            const editor = $("assistantDraftEditor");
            editor?.focus?.({ preventScroll: true });
            editor?.setSelectionRange?.(currentEditor.selectionStart, currentEditor.selectionEnd);
        }
    }

    function assistantKeyValueRows(value) {
        if (!value || typeof value !== "object") return `<div class="assistant-empty compact">暂无记录。</div>`;
        const keyMap = {
            ready: "整体就绪状态", rolesReady: "分阶段角色状态", uniqueCombinations: "模型配置组数",
            sameCombination: "统一模型配置", settingsVersion: "关联配置版本",
            textGeneration: "基础文本生成", structuredOutput: "结构化输出校验",
            searchEvent: "联网搜索调用", sourceReturned: "搜索来源提取",
            modelId: "当前模型", protocol: "接口协议", contextTokens: "上下文容量",
            databaseBytes: "本地数据库体积", dbBytes: "本地数据库体积",
            usableBytes: "磁盘可用空间", usableSpaceBytes: "磁盘可用空间",
            lastBackup: "最近自动备份", collectionSpaceAvailable: "采集空间状态"
        };
        const valueMap = {
            RESPONSES: "Responses", CHAT_COMPLETIONS: "Chat Completions", MESSAGES: "Messages",
            ready: "正常", not_loaded: "待初始化", unavailable: "暂不可用", SHARED_FILE_ACTIVE: "正常"
        };
        const formatBytes = (bytes) => {
            const n = Number(bytes);
            if (!Number.isFinite(n) || n < 0) return String(bytes);
            if (n < 1024) return `${n} B`;
            if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
            if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
            return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
        };
        const entries = Object.entries(value).filter(([, item]) =>
            item !== null && item !== undefined && item !== "" && !Array.isArray(item) && typeof item !== "object");
        return entries.length ? entries.map(([key, item]) => {
            const label = keyMap[key] || key;
            let text;
            if (typeof item === "boolean") {
                text = key === "ready" || key === "rolesReady" ? (item ? "已就绪" : "待验证")
                    : key === "collectionSpaceAvailable" ? (item ? "空间充足" : "空间不足（已保护暂停）")
                    : (item ? "已通过" : "未通过");
            } else if (/Bytes$/i.test(key) && Number.isFinite(Number(item))) {
                text = formatBytes(item);
            } else if (key === "lastBackup") {
                text = String(item).replace(/\.sqlite$/i, "");
            } else {
                text = valueMap[String(item)] || String(item);
            }
            return `<div class="assistant-kv"><span>${escapeHtml(label)}</span><strong>${escapeHtml(text)}</strong></div>`;
        }).join("") : `<div class="assistant-empty compact">暂无记录。</div>`;
    }

    function renderAssistantMonitor(dashboard) {
        const esc = typeof escapeHtml === "function" ? escapeHtml : String;
        const fmt = typeof formatAssistantDateTime === "function" ? formatAssistantDateTime : () => "";
        const settings = dashboard.settings || {};
        const settingsForm = $("assistantSettingsForm");
        if (!settingsForm.dataset.editRevision || settingsForm.dataset.version === undefined) {
            const activeProvider = String(settings.provider || dashboard.providerDefaults?.provider || "chatgpt").toLowerCase();
            if ($("assistantProvider")) {
                $("assistantProvider").value = ["chatgpt", "openai", "google", "deepseek", "minimax", "commandcode"].includes(activeProvider)
                    ? activeProvider : "commandcode";
            }
            const modCfg = settings.moduleConfigs || {};
            const stageRouting = dashboard.providerDefaults?.stageRouting || {};
            if ($("assistantCollectionModel")) {
                $("assistantCollectionModel").value = modCfg.collection?.model || stageRouting.collection?.model || "MiniMax-M2.7";
            }
            if ($("assistantSearchModel")) {
                $("assistantSearchModel").value = modCfg.search?.model || stageRouting.search?.model || "deepseek-chat";
            }
            const repostProv = String(modCfg.repost?.provider || stageRouting.repost?.provider || "chatgpt").toLowerCase();
            const safeRepostProv = ["chatgpt", "openai", "google", "minimax", "deepseek"].includes(repostProv) ? repostProv : "chatgpt";
            const repostMod = modCfg.repost?.model || stageRouting.repost?.model || composeModelWithEffort(safeRepostProv, DEFAULT_MODEL_BY_PROVIDER[safeRepostProv], DEFAULT_EFFORT_BY_PROVIDER[safeRepostProv]);
            syncRepostSettingsModelControls(safeRepostProv, repostMod);
            const repostSplit = splitModelAndEffort(repostMod);
            const repostEl = $("repostGenTargetSelect");
            if (repostEl) {
                const candidateSpec = `${safeRepostProv}|${repostSplit.modelId || DEFAULT_MODEL_BY_PROVIDER[safeRepostProv]}`;
                if ([...(repostEl.options || [])].some((opt) => opt.value === candidateSpec)) {
                    repostEl.value = candidateSpec;
                }
            }
            syncEffortSelectAvailability($("repostGenEffortSelect"), safeRepostProv, repostSplit.effort);
            const genProvRaw = String(modCfg.generation?.provider || stageRouting.generation?.defaultProvider || "chatgpt").toLowerCase();
            const safeGenProv = ["chatgpt", "openai", "google", "minimax", "deepseek"].includes(genProvRaw) ? genProvRaw : "chatgpt";
            const genMod = modCfg.generation?.model || stageRouting.generation?.defaultModel || composeModelWithEffort(safeGenProv, DEFAULT_MODEL_BY_PROVIDER[safeGenProv], DEFAULT_EFFORT_BY_PROVIDER[safeGenProv]);
            syncStageModelControls("generation", safeGenProv, genMod);
            applySavedGenerationDefault(dashboard);
            $("assistantEnabled").checked = settings.enabled !== false;
            $("assistantInterval").value = settings.intervalMinutes ?? 5;
            $("assistantThreshold").value = settings.threshold ?? 80;
            $("assistantValueWeight").value = settings.valueWeight ?? 50;
            $("assistantCollectionEnabled").checked = settings.collectionEnabled !== false;
            $("assistantModelEnabled").checked = settings.modelEnabled !== false;
            $("assistantModel").value = settings.model || "";
            $("assistantProtocol").value = settings.protocol || "RESPONSES";
            $("assistantContextTokens").value = settings.contextTokens ?? 0;
            $("assistantResearchModel").value = settings.researchModel || "";
            $("assistantResearchProtocol").value = settings.researchProtocol || "RESPONSES";
            $("assistantResearchContextTokens").value = settings.researchContextTokens ?? 0;
            settingsForm.dataset.version = String(settings.version ?? 0);
        }
        if (typeof syncPolishPromptControls === "function") {
            syncPolishPromptControls(settings);
        }
        const sourceHandles = (dashboard.sources || [])
            .map((source) => source.username || source.handle || "")
            .filter(Boolean);
        for (const [selectId, currentVal] of [
            ["assistantTopicAuthor", state.assistantTopicAuthor || ""],
            ["assistantMaterialAuthor", state.assistantMaterialAuthor || ""]
        ]) {
            const selectEl = $(selectId);
            if (selectEl) {
                selectEl.innerHTML = `<option value="">全部作者</option>` + sourceHandles.map((handle) =>
                    `<option value="${esc(handle)}"${handle === currentVal ? " selected" : ""}>@${esc(handle)}</option>`).join("");
                selectEl.value = currentVal;
            }
        }
        const rewriteStatusEl = $("assistantRewriteStatus");
        if (rewriteStatusEl) {
            const sources = dashboard.sources || [];
            const activeCount = sources.filter(source => source.autoRewriteEnabled === true && source.enabled !== false
                && String(source.status || "ACTIVE").toUpperCase() === "ACTIVE").length;
            rewriteStatusEl.hidden = !sources.length;
            rewriteStatusEl.textContent = activeCount
                ? `${activeCount} 位监听作者已开启自动洗稿。`
                : "尚无监听作者开启自动洗稿。点作者旁的「今日洗稿」可手动生成，勾选「洗稿发送」可开启自动发送。";
        }
        const sourcesListEl = $("assistantSourcesList");
        if (sourcesListEl && !sourcesListEl.contains(document.activeElement)) {
            sourcesListEl.innerHTML = (dashboard.sources || []).length
                ? dashboard.sources.map((source) => {
                    const paused = String(source.status || "").toUpperCase() === "PAUSED" || source.enabled === false;
                    const lagging = !paused && source.lagging === true;
                    const gapCount = Array.isArray(source.gaps) ? source.gaps.length : 0;
                    const tier = [1, 5, 30].includes(Number(source.intervalMinutes)) ? Number(source.intervalMinutes) : 5;
                    const autoOn = source.autoRepostEnabled === true;
                    const rewriteOn = source.autoRewriteEnabled === true;
                    const textMode = String(source.autoRepostTextMode || "AI").toUpperCase() === "TEMPLATE" ? "TEMPLATE" : "AI";
                    const minScore = Number.isFinite(Number(source.autoRepostMinScore)) ? Number(source.autoRepostMinScore) : 70;
                    const uname = String(source.username || source.handle || source.id || "").replace(/^@+/, "");
                    const displayName = String(source.displayName || uname || source.id).trim();
                    const avatar = typeof avatarHtml === "function"
                        ? avatarHtml({ username: uname || displayName, displayName, avatarUrl: source.avatarUrl || "" }, "sm")
                        : "";
                    const avatarBtn = avatar
                        ? `<button type="button" class="assistant-avatar-preview-btn" data-source-repost-preview="${esc(source.id)}" aria-label="预览转发 @${esc(uname)} 时说什么" title="点击预览转发时我会说什么">${avatar}</button>`
                        : "";
                    const verifiedHtml = typeof verifiedSvgHtml === "function" ? verifiedSvgHtml(Boolean(source.verified)) : "";
                    const metaParts = [];
                    const lastOkText = source.lastSuccessAt ? fmt(source.lastSuccessAt) : "";
                    const lastTryText = source.lastAttemptAt && source.lastAttemptAt !== source.lastSuccessAt
                        ? fmt(source.lastAttemptAt) : "";
                    if (lastOkText) metaParts.push(lastOkText);
                    if (lastTryText && lastTryText !== lastOkText) metaParts.push("检查 " + lastTryText);
                    if (!metaParts.length && !source.lastSuccessAt && !source.error) metaParts.push("待同步");
                    if (lagging && source.error) metaParts.push("采集滞后");
                    if (gapCount > 0) metaParts.push(`历史缺口 ${gapCount} 处`);
                    const isBudgetPaused = !paused && Boolean(source.error && String(source.error).includes("外部调用预算已达保护上限"));
                    const stateClass = paused ? "muted" : (isBudgetPaused ? "running" : (source.error ? "error" : (lagging ? "running" : "ok")));
                    const stateText = paused ? "已暂停" : (isBudgetPaused ? "预算保护" : (source.error ? "采集异常" : (lagging ? "采集滞后" : "监听中")));
                    return `<div class="assistant-source-card" data-source-row="${esc(source.id)}">
                        <div class="assistant-source-head">
                            <div class="assistant-source-identity" data-i18n-ignore>
                                ${avatarBtn}
                                <div class="assistant-source-identity-copy">
                                    <span class="assistant-source-title-line">
                                        <strong>${esc(displayName)}</strong>
                                        ${verifiedHtml}
                                        <span class="assistant-author-handle">@${esc(uname)}</span>
                                    </span>
                                    <small class="assistant-source-meta-line">${esc(metaParts.join(" · "))}</small>
                                    ${source.error ? `<small class="assistant-source-error-line">${isBudgetPaused ? "保护暂停：" : "异常："}${esc(source.error)}</small>` : ""}
                                </div>
                            </div>
                            <div class="assistant-source-controls">
                                <button type="button" class="x-outline-pill sm" data-source-repost-preview="${esc(source.id)}" data-source-preview-mode="REWRITE">今日洗稿</button>
                                <label class="assistant-setting-toggle is-inline"><input type="checkbox" data-source-autorepost-toggle="${esc(source.id)}"${autoOn ? " checked" : ""} /><span>自动转推</span></label>
                                <label class="assistant-setting-toggle is-inline" title="找出该账号高流量纯文本内容进行学习与改写，共用全局时间锁间隔发送（不引用原帖）"><input type="checkbox" data-source-autorewrite-toggle="${esc(source.id)}"${rewriteOn ? " checked" : ""} /><span>洗稿发送</span></label>
                                <select class="x-select assistant-tier-select" data-assistant-source-tier="${esc(source.id)}" aria-label="采集档位">
                                    <option value="1"${tier === 1 ? " selected" : ""}>1 分钟</option>
                                    <option value="5"${tier === 5 ? " selected" : ""}>5 分钟</option>
                                    <option value="30"${tier === 30 ? " selected" : ""}>30 分钟</option>
                                </select>
                                <span class="assistant-state ${stateClass}">${stateText}</span>
                                <button type="button" class="link-inline-btn" data-assistant-source-action="${paused ? "resume" : "pause"}" data-source-id="${esc(source.id)}">${paused ? "恢复" : "暂停"}</button>
                                <button type="button" class="link-inline-btn danger" data-assistant-source-action="remove" data-source-id="${esc(source.id)}">移除</button>
                            </div>
                        </div>
                        ${autoOn ? `<div class="assistant-source-autorepost">
                            <select class="x-select assistant-tier-select" data-source-autorepost-mode="${esc(source.id)}" aria-label="附带文字生成方式">
                                <option value="AI"${textMode === "AI" ? " selected" : ""}>AI 评述</option>
                                <option value="TEMPLATE"${textMode === "TEMPLATE" ? " selected" : ""}>固定文案</option>
                            </select>
                            <input type="number" class="autorepost-score-input" min="50" max="99" value="${minScore}" data-source-autorepost-score="${esc(source.id)}" aria-label="走热潜力阈值" title="走热潜力阈值（50-99）" />
                            ${textMode === "TEMPLATE"
                                ? `<input type="text" class="autorepost-tpl-input" maxlength="240" placeholder="固定附文（支持 {summary}）" value="${esc(source.autoRepostTemplate || "")}" data-source-autorepost-template="${esc(source.id)}" />`
                                : `<input type="text" class="autorepost-tpl-input" maxlength="200" placeholder="侧重方向（如：重大人事、新品发布）" value="${esc(source.autoRepostRuleHint || "")}" data-source-autorepost-hint="${esc(source.id)}" />`}
                            <button type="button" class="x-outline-pill sm" data-source-autorepost-save="${esc(source.id)}">保存</button>
                        </div>` : ""}
                    </div>`;
                }).join("") : `<div class="assistant-empty compact">尚未添加监听作者。</div>`;
        }
        const piBarEl = $("assistantPiAgentBar");
        if (piBarEl) {
            const piInfo = dashboard.piAgent;
            if (piInfo && piInfo.ready !== true) {
                piBarEl.hidden = false;
                const badgeCls = piInfo.installed === false ? "error" : "running";
                piBarEl.innerHTML = `<div class="assistant-pi-bar-copy">
                        <span><span class="assistant-state ${badgeCls}">待登录 ChatGPT</span></span>
                        <small>${esc(piInfo.message || "")}</small>
                        ${piInfo.loginCommand ? `<code>${esc(piInfo.loginCommand)}</code>` : ""}
                    </div>
                    <button type="button" class="x-outline-pill sm" id="verifyPiAgentBtn">检测授权</button>`;
            } else {
                piBarEl.hidden = true;
                piBarEl.innerHTML = "";
            }
        }
        const autoFeedEl = $("assistantAutoRepostFeed");
        if (autoFeedEl) {
            const unconfirmed = dashboard.unconfirmedPublications || [];
            const awaitingVerification = new Set(unconfirmed.filter(row => row.autoKind)
                .map(row => row.autoKind + ":" + (row.materialId || row.id)));
            const activityKey = row => (row.autoKind || row.mode || "REPOST") + ":" + (row.materialId || row.id);
            const hasRetryList = Array.isArray(dashboard.retryableAutoPublications);
            const retryable = new Map((dashboard.retryableAutoPublications || []).map(row => [activityKey(row), row]));
            const items = (dashboard.autoReposts || []).filter(row => !awaitingVerification.has(activityKey(row))).slice(0, 8);
            const shown = new Set(items.map(activityKey));
            for (const [key, row] of retryable) if (!shown.has(key) && !awaitingVerification.has(key)) items.push(row);
            if (items.length) {
                autoFeedEl.hidden = false;
                const statusLabels = {
                    PUBLISHED: ["ok", "已发布"],
                    EVALUATING: ["running", "判定中"],
                    SENDING: ["running", "发送中"],
                    SKIPPED: ["muted", "未达转发条件"],
                    SKIPPED_AUTHOR_COOLDOWN: ["muted", "16h同作者限频"],
                    SKIPPED_DUPLICATE: ["muted", "同事件去重"],
                    SKIPPED_LIMIT: ["muted", "达日上限"],
                    SKIPPED_COOLDOWN: ["muted", "冷却跳过"],
                    SKIPPED_QUIET_HOURS: ["muted", "静默期跳过"],
                    OWNER_REVIEW_REQUIRED: ["error", "待核实作者"],
                    AUTH_REQUIRED: ["error", "需发推授权"],
                    JUDGE_FAILED: ["error", "判定异常"],
                    UNCERTAIN: ["error", "回执待核"],
                    FAILED: ["error", "发送失败"]
                };
                const rewriteStatusLabels = {
                    PUBLISHED: ["ok", "已洗稿发送"],
                    EVALUATING: ["running", "洗稿判定中"],
                    SENDING: ["running", "洗稿发送中"],
                    SKIPPED: ["muted", "不适合洗稿"],
                    SKIPPED_DUPLICATE: ["muted", "同选题去重"],
                    SKIPPED_LIMIT: ["muted", "达日上限"],
                    SKIPPED_COOLDOWN: ["muted", "冷却跳过"],
                    SKIPPED_QUIET_HOURS: ["muted", "静默期跳过"],
                    OWNER_REVIEW_REQUIRED: ["error", "待核实作者"],
                    AUTH_REQUIRED: ["error", "需发推授权"],
                    JUDGE_FAILED: ["error", "洗稿异常"],
                    UNCERTAIN: ["error", "回执待核"],
                    FAILED: ["error", "发送失败"]
                };
                autoFeedEl.innerHTML = items.map((item) => {
                    const st = String(item.status || "").toUpperCase();
                    const isRewrite = String(item.autoKind || item.mode || "").toUpperCase() === "REWRITE";
                    const [cls, statusLabel] = (isRewrite ? rewriteStatusLabels[st] : statusLabels[st]) || statusLabels[st] || ["muted", st || "记录"];
                    const label = st === "PUBLISHED" && !isRewrite && item.publicationMode
                        ? assistantPublishNotice(item) : statusLabel;
                    const rawScore = isRewrite ? Number(item.suitabilityScore || item.viralScore) : Number(item.viralScore);
                    const scorePart = Number.isFinite(rawScore) && rawScore > 0
                        ? (isRewrite ? ` · 适格 ${rawScore}` : ` · 热度 ${rawScore}`) : "";
                    const pubUrl = item.publishedTweetUrl && typeof safeHttpUrl === "function" ? safeHttpUrl(item.publishedTweetUrl) : "";
                    const srcUrl = item.sourceUrl && typeof safeHttpUrl === "function" ? safeHttpUrl(item.sourceUrl) : "";
                    const detailText = (st === "PUBLISHED" ? item.text : "") || item.rewrittenText || item.commentText || item.reason || item.error || item.summary || "";
                    const action = retryable.get(activityKey(item));
                    const canRetry = hasRetryList ? Boolean(action) : ["AUTH_REQUIRED", "JUDGE_FAILED", "FAILED", "SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS"].includes(st);
                    const itemId = item.materialId || item.id || "";
                    const retryBtnHtml = canRetry && itemId
                        ? `<button type="button" class="link-inline-btn" data-autorepost-retry="${esc(itemId)}" data-publish-mode="${isRewrite ? "REWRITE" : "REPOST"}">重试</button>${isRewrite && action?.canReassess ? `<button type="button" class="link-inline-btn" data-autorepost-retry="${esc(itemId)}" data-publish-mode="REWRITE" data-reassess="true">重新评估并发送</button>` : ""}`
                        : "";
                    return `<div class="assistant-autorepost-item">
                        <div>
                            <span class="assistant-state ${cls}">${esc(label)}</span>
                            <strong>@${esc(item.username || "source")}${esc(scorePart)}</strong>
                            ${detailText ? `<small${st === "PUBLISHED" ? " data-i18n-ignore" : ""}>${esc(detailText)}</small>` : ""}
                        </div>
                        <div class="assistant-draft-head-actions">
                            ${retryBtnHtml}
                            ${pubUrl ? `<a href="${pubUrl}" target="_blank" rel="noopener noreferrer">已发推文 ↗</a>`
                                : (srcUrl ? `<a href="${srcUrl}" target="_blank" rel="noopener noreferrer">原帖 ↗</a>` : "")}
                        </div>
                    </div>`;
                }).join("");
            } else {
                autoFeedEl.hidden = true;
                autoFeedEl.innerHTML = "";
            }
            if (unconfirmed.length) {
                autoFeedEl.hidden = false;
                autoFeedEl.insertAdjacentHTML("afterbegin", unconfirmed.map(row => `<div class="assistant-autorepost-item" data-verification-material="${esc(row.materialId || row.quoteTweetId || row.id)}"${row.autoKind === "REPOST" || row.source === "MANUAL_QUOTE_REPOST" ? ` data-quote-verification="${esc(row.requestedQuoteTweetId || row.quoteTweetId || row.materialId || row.id)}"` : ""}>
                    <div><strong>${row.scheduledDelivery ? "定时发布待核实" : row.autoKind === "REWRITE" ? "自动洗稿待核实" : row.autoKind === "REPOST" ? "自动引用待核实"
                        : String(row.source || "").startsWith("MANUAL_") ? "手动引用待核实" : "草稿直发待核实"}</strong>
                    <small data-i18n-ignore>${escapeHtml((row.publicationMode ? row.text : "") || row.requestedText || row.text || row.rewrittenText || row.commentText || "")}</small></div>
                    <div class="assistant-draft-head-actions">${assistantVerificationButtons(row)}</div></div>`).join(""));
            }
        }
        const capabilities = dashboard.capabilities || {};
        const providerDefaults = dashboard.providerDefaults || {};
        const maskedKeys = providerDefaults.maskedApiKeys || {};
        const cfgProvs = providerDefaults.configuredProviders || {};
        const syncKeyInputHint = (id, keyName, envLabel) => {
            const el = $(id);
            if (!el) return;
            const masked = maskedKeys[keyName] || "";
            const isReady = Boolean(cfgProvs[keyName]);
            el.placeholder = masked ? `已配置 ${masked}` : (isReady ? `已就绪（${envLabel}）` : `填入 ${envLabel}`);
        };
        syncKeyInputHint("assistantMinimaxApiKey", "minimax", "MINIMAX_API_KEY");
        syncKeyInputHint("assistantDeepseekApiKey", "deepseek", "DEEPSEEK_API_KEY");
        syncKeyInputHint("assistantOpenaiApiKey", "openai", "OPENAI_API_KEY");
        syncKeyInputHint("assistantGoogleApiKey", "google", "GEMINI_API_KEY");
        const providerId = String(settings.provider || providerDefaults.provider || "chatgpt").toLowerCase();
        const providerHintEl = $("assistantProviderHint");
        if (providerHintEl) {
            providerHintEl.textContent = providerId === "chatgpt"
                ? "gpt-6.2-astra low · 200K"
                : (providerId === "openai"
                    ? "gpt-6.2-sol · 200K"
                    : (providerId === "google"
                        ? "gemini-4-neon · 1M"
                        : (providerId === "deepseek"
                            ? "deepseek-chat · 128K"
                            : (providerId === "minimax" ? "MiniMax-M2.7 · 200K" : "gpt-6.2-sol · 128K"))));
        }
        const advancedDetails = $("assistantAdvancedSettings");
        const chatGptOAuthReady = providerId === "chatgpt" && dashboard.piAgent?.ready === true;
        const capFailed = capabilities.ready !== true
            && Boolean((capabilities.main && typeof capabilities.main === "object")
                || (capabilities.research && typeof capabilities.research === "object")
                || capabilities.checkedAt);
        if (advancedDetails && !advancedDetails.dataset?.userToggled) {
            if (chatGptOAuthReady && !capFailed) {
                advancedDetails.open = false;
            } else if (!settings.model || capabilities.ready !== true) {
                advancedDetails.open = true;
            }
        }
        const suggestedEl = $("assistantSuggestedPeers");
        if (suggestedEl) {
            const existingHandles = new Set((dashboard.sources || [])
                .map((source) => String(source.username || source.handle || "").replace(/^@/, "").toLowerCase())
                .filter(Boolean));
            const selfHandle = String(state.authStatus?.user?.username || state.report?.profile?.username || "").replace(/^@/, "").toLowerCase();
            const rawPeers = Array.isArray(state.report?.interactions?.peers) && state.report.interactions.peers.length
                ? state.report.interactions.peers
                : (Array.isArray(state.report?.interactions?.peerStats) ? state.report.interactions.peerStats : []);
            const candidates = [];
            const seen = new Set();
            for (const item of rawPeers) {
                const rawHandle = typeof item === "string"
                    ? item
                    : (item?.peer?.username || item?.username || item?.handle || "");
                const handle = String(rawHandle).replace(/^@/, "").trim();
                const lower = handle.toLowerCase();
                if (!handle || lower === selfHandle || existingHandles.has(lower) || seen.has(lower)) continue;
                seen.add(lower);
                const score = Number(item?.intimacyScore ?? item?.totalInteractions ?? ((item?.myRepliesToPeer || 0) + (item?.peerRepliesToMe || 0)) ?? 0);
                candidates.push({ handle, score });
            }
            candidates.sort((a, b) => b.score - a.score);
            const top10 = candidates.slice(0, 10);
            if (top10.length) {
                const top5Input = top10.slice(0, 5).map((p) => `@${p.handle}`).join(" ");
                suggestedEl.hidden = false;
                suggestedEl.innerHTML = `<div class="assistant-suggested-head">
                        <span>高频互动推荐</span>
                        <button type="button" class="link-inline-btn" data-assistant-add-suggested="${esc(top5Input)}">一键添加前 ${Math.min(5, top10.length)}</button>
                    </div>
                    <div class="assistant-suggested-chips">${top10.map((p) =>
                        `<button type="button" class="pill-chip sm" data-assistant-add-suggested="@${esc(p.handle)}">+ @${esc(p.handle)}</button>`
                    ).join("")}</div>`;
            } else {
                suggestedEl.hidden = true;
                suggestedEl.innerHTML = "";
            }
        }
        const protoText = (p) => ({
            RESPONSES: "Responses 接口",
            MESSAGES: "Messages 接口",
            CHAT_COMPLETIONS: "Chat Completions 接口"
        }[String(p || "").toUpperCase()] || p || "");
        const mainCap = capabilities.main && typeof capabilities.main === "object" ? capabilities.main : null;
        const resCap = capabilities.research && typeof capabilities.research === "object" ? capabilities.research : null;
        const capabilityDisplay = {};
        if (providerId === "chatgpt") {
            capabilityDisplay["订阅授权"] = dashboard.piAgent?.ready === true ? "已授权" : "待登录";
        }
        if (capabilities.ready === true || chatGptOAuthReady) {
            capabilityDisplay["能力验证"] = "已就绪";
        } else if (mainCap || resCap || capabilities.checkedAt) {
            capabilityDisplay["能力验证"] = "未通过";
        } else {
            capabilityDisplay["能力验证"] = settings.model ? "待验证" : "待配置";
        }
        if (mainCap) {
            const modelNote = mainCap.modelId ? ` · ${mainCap.modelId}` : "";
            capabilityDisplay["结构化输出"] = (mainCap.structuredOutput ? "已通过" : "未通过") + modelNote;
        } else if (typeof capabilities.structuredOutput === "boolean") {
            capabilityDisplay["结构化输出"] = capabilities.structuredOutput ? "已通过" : "未通过";
        }
        if (resCap) {
            const ok = Boolean(resCap.searchEvent && resCap.sourceReturned);
            capabilityDisplay["联网检索"] = ok ? "已通过" : "未通过";
        } else if (typeof capabilities.searchEvent === "boolean") {
            capabilityDisplay["联网检索"] = (capabilities.searchEvent && capabilities.sourceReturned) ? "已通过" : "未通过";
        }
        if (Number(capabilities.uniqueCombinations) > 1) {
            capabilityDisplay["模型组数"] = `${Number(capabilities.uniqueCombinations)} 组`;
        }
        const capErr = String(mainCap?.error || resCap?.error || capabilities.error || "").replace(/^[A-Za-z0-9_.$]+Exception:\s*/, "");
        if (capErr) capabilityDisplay["异常"] = capErr;
        const checkedStamp = mainCap?.checkedAt || resCap?.checkedAt || capabilities.checkedAt;
        if (checkedStamp) capabilityDisplay["最近验证"] = fmt(checkedStamp) || String(checkedStamp);
        $("assistantCapabilityStatus").innerHTML = assistantKeyValueRows(capabilityDisplay);
        const style = dashboard.style || {};
        const coverage = style.coverage || {};
        const styleStatus = { COMPLETE: "已完成", EXHAUSTED: "样本已尽", PENDING: "待续采",
            LIMITED: "受限", COLLECTING: "采集中" }[coverage.state];
        $("assistantStyleMeta").textContent = [
            `已学 ${Number(style.learnedCount || 0)} · 样本 ${Number(coverage.collected ?? style.sampleCount ?? 0)}/${Number(coverage.target || 100)}`,
            style.editor === "OWNER" ? "已修订" : "",
            styleStatus, coverage.error
        ].filter(Boolean).join(" · ");
        const styleProfileEl = $("assistantStyleProfile");
        const exampleItems = Array.isArray(style.examples) ? style.examples.filter((item) => item?.text) : [];
        if (styleProfileEl) {
            if (exampleItems.length && typeof document?.createElement === "function") {
                const examples = document.createElement("ul");
                for (const example of exampleItems) {
                    const row = document.createElement("li");
                    row.textContent = example.text;
                    examples.appendChild(row);
                }
                styleProfileEl.replaceChildren(examples);
                styleProfileEl.hidden = false;
            } else {
                styleProfileEl.replaceChildren();
                styleProfileEl.hidden = true;
            }
        }
        const styleRevisionInput = $("assistantStyleRevisionInput");
        if (styleRevisionInput && (!styleRevisionInput.dataset.editRevision || styleRevisionInput.dataset.version === undefined)) {
            styleRevisionInput.value = style.profile || "";
            styleRevisionInput.dataset.version = String(style.version ?? 0);
        }
        const preferencesInput = $("assistantPreferencesInput");
        if (!preferencesInput.dataset.editRevision || preferencesInput.dataset.version === undefined) {
            preferencesInput.value = dashboard.preferences?.text || "";
            preferencesInput.dataset.version = String(dashboard.preferences?.version ?? 0);
        }
        const rawStorage = dashboard.storage;
        const fmtBytesLocal = (b) => {
            const n = Number(b);
            if (!Number.isFinite(n) || n < 0) return "--";
            if (n < 1024) return `${n} B`;
            if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
            if (n < 1073741824) return `${(n / 1048576).toFixed(1)} MB`;
            return `${(n / 1073741824).toFixed(1)} GB`;
        };
        const storageDisplay = rawStorage && typeof rawStorage === "object" ? {
            数据库: fmtBytesLocal(rawStorage.databaseBytes ?? rawStorage.dbBytes),
            可用空间: fmtBytesLocal(rawStorage.usableBytes ?? rawStorage.usableSpaceBytes),
            采集空间: rawStorage.collectionSpaceAvailable === false ? "不足 16 MB" : "充足",
            最近备份: rawStorage.lastBackup ? String(rawStorage.lastBackup).replace(/\.sqlite$/i, "") : "暂无"
        } : null;
        $("assistantStorageStatus").innerHTML = assistantKeyValueRows(storageDisplay);
        const budgetEl = $("assistantBudgetStatus");
        if (budgetEl) {
            const ledgerRaw = String(dashboard.upstreamBudget?.ledgerState || "").trim();
            const ledgerLabel = {
                ready: "正常",
                SHARED_FILE_ACTIVE: "正常",
                not_loaded: "待初始化",
                unavailable: "受限",
                DEGRADED: "降级"
            }[ledgerRaw] || ledgerRaw || "--";
            const fmtUsd = (v) => v != null && Number.isFinite(Number(v)) ? `$${Number(v).toFixed(2)}` : "--";
            budgetEl.innerHTML = dashboard.upstreamBudget ? assistantKeyValueRows({
                共享账本: ledgerLabel,
                本小时请求: `${dashboard.upstreamBudget.hourlyUsed ?? "--"} / ${dashboard.upstreamBudget.hourlyBudget ?? "--"}`,
                今日请求: `${dashboard.upstreamBudget.dailyUsed ?? "--"} / ${dashboard.upstreamBudget.dailyBudget ?? "--"}`,
                今日预留: `${fmtUsd(dashboard.upstreamBudget.dailyUsdReserved)} / ${fmtUsd(dashboard.upstreamBudget.dailyUsdBudget)}`,
                超限拦截: `${dashboard.upstreamBudget.budgetRejectedCalls ?? "--"}`
            }) : "";
        }
        $("assistantHistoryStatus").innerHTML = renderAssistantHistory(dashboard.history);
    }

    function renderContentAssistant() {
        const ownerAllowed = typeof isOwnerAllPermissions === "function" ? isOwnerAllPermissions() : true;
        const allowed = ownerAllowed && state.assistantAccess?.allowed === true;
        $("contentAssistantShell").hidden = !allowed;
        const quickNav = document.getElementById("timingQuickNav");
        if (quickNav) quickNav.hidden = !allowed;
        if ($("contentAssistantAccessNotice")) $("contentAssistantAccessNotice").hidden = true;
        if (!allowed || !state.assistantDashboard) return;
        const dashboard = state.assistantDashboard;
        const running = (dashboard.tasks || []).some((task) =>
            ["QUEUED", "RUNNING", "WAITING_STYLE", "WAITING_SAMPLES"].includes(String(task.status || "").toUpperCase()));
        const status = $("contentAssistantStatus");
        status.textContent = state.assistantLoading ? "正在读取…" : running ? "任务处理中" : "已同步";
        status.className = "assistant-status-dot " + (running ? "running" : "ok");
        document.querySelectorAll("[data-assistant-view]").forEach((button) => {
            const active = button.dataset.assistantView === state.assistantView;
            button.classList.toggle("active", active);
            button.setAttribute("aria-selected", String(active));
        });
        $("contentAssistantView").hidden = state.assistantView !== "assistant";
        if ($("contentMaterialsView")) $("contentMaterialsView").hidden = state.assistantView !== "materials";
        if ($("contentReviewView")) $("contentReviewView").hidden = state.assistantView !== "review";
        $("contentMonitorView").hidden = state.assistantView !== "monitor";
        if ($("contentSettingsView")) $("contentSettingsView").hidden = state.assistantView !== "settings";
        if ($("assistantCreationToolbar")) {
            $("assistantCreationToolbar").hidden = !(state.assistantView === "assistant" || state.assistantView === "materials");
        }
        renderAssistantSummary(dashboard);
        renderAssistantTopics(dashboard);
        renderAssistantMaterialsView(dashboard);
        renderAssistantReviewPanel(dashboard);
        renderAssistantMonitor(dashboard);
        clearTimeout(state.assistantTaskTimerId);
        state.assistantTaskTimerId = null;
        if (running && state.activeTab === "timing" && !document.hidden) {
            state.assistantTaskTimerId = setTimeout(() => loadContentAssistant(true), 4000);
        }
    }

    async function loadContentAssistant(silent = false, topicOffset = state.assistantRequestedTopicOffset ?? state.assistantTopicOffset ?? 0, force = false) {
        if (!isLoggedIn() || state.isLoggingOut || (state.assistantLoading && !force)) return;
        const generation = ++state.assistantRequestGeneration;
        const isCurrentUser = captureUserScope();
        const search = state.assistantTopicSearch || "";
        const statusFilter = state.assistantTopicStatus || "";
        const authorFilter = state.assistantTopicAuthor || "";
        const tagFilter = state.assistantTopicTag || "";
        const sort = state.assistantTopicSort || "score";
        const isCurrent = () => generation === state.assistantRequestGeneration && isCurrentUser()
            && search === (state.assistantTopicSearch || "")
            && statusFilter === (state.assistantTopicStatus || "")
            && authorFilter === (state.assistantTopicAuthor || "")
            && tagFilter === (state.assistantTopicTag || "")
            && sort === (state.assistantTopicSort || "score");
        state.assistantRequestedTopicOffset = topicOffset;
        state.assistantLoading = true;
        if (!silent) setContentAssistantError("");
        try {
            const access = state.assistantAccess || await contentAssistantRequest("/access");
            if (!isCurrent()) return;
            state.assistantAccess = access;
            if (state.assistantAccess?.allowed !== true) return;
            const query = new URLSearchParams({ limit: "100", topicOffset: String(topicOffset), q: search.trim(), sort });
            if (statusFilter) query.set("status", statusFilter);
            if (authorFilter) query.set("username", authorFilter);
            if (tagFilter.trim()) query.set("tag", tagFilter.trim());
            const dashboard = await contentAssistantRequest("/dashboard?" + query.toString());
            if (!isCurrent()) return;
            const topics = dashboard.topics || [];
            const current = state.assistantSelectedTopicId;
            const selected = (current && current === state.assistantExternalTopicId) || topics.some((item) => item.id === current)
                ? current : topics[0]?.id || "";
            if (state.assistantDraftDirty) {
                const editing = $("assistantDraftEditor")?.closest("[data-draft-id]")?.dataset.draftId;
                if (current !== selected || !(dashboard.drafts || []).some((draft) => draft.id === editing && draft.topicId === selected)) {
                    const saved = await saveAssistantDraftEditor();
                    if (!saved) return;
                    const incoming = (dashboard.drafts || []).find((draft) => draft.id === saved.id);
                    if (incoming && saved.version >= incoming.version) Object.assign(incoming, saved);
                }
            }
            if (!isCurrent()) return;
            state.assistantDashboard = dashboard;
            state.assistantTopicOffset = topicOffset;
            state.assistantTopicHasNext = dashboard.topicHasNext === true;
            state.assistantSelectedTopicId = selected;
            if (selected) await loadAssistantTopicDetail(selected, false);
        } catch (error) {
            if (isCurrent()) setContentAssistantError(error.message || "内容助手暂不可用。");
        } finally {
            if (generation === state.assistantRequestGeneration) {
                state.assistantLoading = false;
                if (isCurrentUser()) renderContentAssistant();
            }
        }
    }

    function assistantVerificationButtons(row) {
        if (row.acceptedReceipt) return '<span class="assistant-state running">X 已确认发布，正在补记回执</span>';
        const auto = ["REPOST", "REWRITE"].includes(row.autoKind);
        const direct = !row.jobId && /^(DIRECT_|MANUAL_)/.test(row.source || "");
        if (row.status !== "UNCERTAIN" || !(row.scheduledDelivery || auto || direct)) return "";
        const id = auto ? row.materialId || row.id : row.id;
        const mode = row.scheduledDelivery ? ' data-publish-mode="SCHEDULED"' : auto ? ` data-publish-mode="${row.autoKind}"` : "";
        const attrs = `data-assistant-verify="${escapeHtml(id)}" data-version="${Number(row.version)}"${mode}`;
        return `<a href="https://x.com/${encodeURIComponent(state.authStatus?.user?.username || "i")}" target="_blank" rel="noopener noreferrer">查看我的时间线 ↗</a>
            <button type="button" class="link-inline-btn" ${attrs} data-published="true">登记已发布</button>
            <button type="button" class="link-inline-btn" ${attrs} data-published="false">已核实未发布</button>`;
    }

    async function verifyAssistantPublication(button) {
        const id = button.dataset.assistantVerify;
        const published = button.dataset.published === "true";
        const decision = { version: Number(button.dataset.version), published };
        if (published) {
            const entered = window.prompt("请输入你已在 X 时间线上核实的帖子 ID 或链接");
            if (entered === null) return;
            const match = entered.trim().match(/^(?:https?:\/\/(?:www\.)?(?:x\.com|twitter\.com)\/(?:[\w]+|i\/web)\/status\/)?(\d{2,20})(?:[/?#].*)?$/);
            if (!match) { showToast("请输入有效的 X 帖子 ID 或链接"); return; }
            decision.tweetId = match[1];
        } else if (!window.confirm("已检查 X 时间线，确认这条帖子没有发布？确认后可重新提交。")) return;
        const mode = button.dataset.publishMode;
        const path = ["REPOST", "REWRITE"].includes(mode)
            ? `/auto-reposts/${encodeURIComponent(id)}/verify?mode=${mode}`
            : mode === "SCHEDULED" ? `/scheduled-publications/${encodeURIComponent(id)}/verify` : `/publications/${encodeURIComponent(id)}/verify`;
        const result = await runAssistantMutation("verify-" + id, path, decision);
        if (result) showToast(published ? "已登记发布回执" : "已核实未发布，可以重新提交");
    }

    async function runAssistantMutation(key, path, payload, timeoutMs = 30000) {
        const isCurrentUser = captureUserScope();
        if (state.assistantMutation || !isCurrentUser()) return null;
        let complete;
        const operation = { key, done: new Promise((resolve) => { complete = resolve; }) };
        state.assistantMutation = operation;
        setContentAssistantError("");
        try {
            const result = await contentAssistantRequest(path, payload, timeoutMs);
            if (!isCurrentUser()) return null;
            await loadContentAssistant(true, undefined, true);
            return isCurrentUser() ? result : null;
        } catch (error) {
            if (isCurrentUser()) setContentAssistantError(error.message || "操作未完成。");
            return null;
        } finally {
            if (state.assistantMutation === operation) state.assistantMutation = null;
            complete();
        }
    }

    async function loadAssistantTopicDetail(topicId, render = true) {
        if (!topicId || !state.assistantDashboard) return;
        const isCurrentUser = captureUserScope();
        const dashboard = state.assistantDashboard;
        const isCurrent = () => isCurrentUser() && state.assistantDashboard === dashboard
            && state.assistantSelectedTopicId === topicId;
        try {
            const detail = await contentAssistantRequest(`/topics/${encodeURIComponent(topicId)}/detail`);
            if (!isCurrent()) return;
            if (state.assistantDraftDirty && Array.isArray(detail.drafts)) {
                const editing = $("assistantDraftEditor")?.closest("[data-draft-id]")?.dataset.draftId;
                if (editing && !detail.drafts.some((draft) => draft.id === editing)
                    && (!await saveAssistantDraftEditor() || !isCurrent())) return;
            }
            if (detail.topic?.id === topicId) {
                dashboard.detailTopic = detail.topic;
                dashboard.topics = (dashboard.topics || []).map((item) => item.id === topicId ? detail.topic : item);
            }
            for (const key of ["materials", "tasks", "drafts", "publications", "observations"]) {
                if (!Array.isArray(detail[key])) continue;
                const incomingIds = new Set(detail[key].map((item) => item.id).filter(Boolean));
                const draftIds = key === "publications" ? new Set((detail.drafts || []).map((draft) => draft.id)) : null;
                dashboard[key] = [
                    ...(dashboard[key] || []).filter((item) => item.topicId !== topicId && !incomingIds.has(item.id)
                        && !(draftIds?.has(item.draftId) && item.status === "SCHEDULED")),
                    ...detail[key]
                ];
            }
            if (render) renderAssistantTopics(dashboard);
        } catch (error) {
            if (isCurrent()) setContentAssistantError(error.message || "无法读取选题详情。");
        }
    }

    async function loadAssistantTopicPage(offset) {
        const isCurrentUser = captureUserScope();
        if (!await saveAssistantDraftEditor() || !isCurrentUser()) return;
        state.assistantExternalTopicId = "";
        await loadContentAssistant(false, Math.max(0, Number(offset) || 0), true);
    }

    async function openAssistantCreationTopic(topicId) {
        const isCurrentUser = captureUserScope();
        if (!topicId || !await saveAssistantDraftEditor() || !isCurrentUser()) return;
        state.assistantSelectedTopicId = topicId;
        state.assistantExternalTopicId = topicId;
        state.assistantOrganizeMode = false;
        state.assistantView = "assistant";
        renderContentAssistant();
        await loadAssistantTopicDetail(topicId);
    }

    function assistantCreationRequest(fields) {
        const instruction = ($("assistantCreationAngleInput")?.value ?? state.assistantCreationAngle ?? "").trim();
        const target = parseGenerationTargetSpec(
            $("assistantGenTargetSelect")?.value || state.assistantGenerationTargetSpec,
            $("assistantGenEffortSelect")?.value ?? state.assistantGenerationEffort
        );
        const payload = { ...fields, ...target, skillId: state.assistantSelectedSkill || "AUTO", clientRequestId: newClientRequestId() };
        if (instruction) payload.instruction = instruction;
        return payload;
    }

    async function startAssistantMaterialResearch(button) {
        const isCurrentUser = captureUserScope();
        const request = assistantCreationRequest({
            materialId: button.dataset.assistantResearch,
            mode: button.dataset.researchMode || "RESEARCH",
            regenerate: button.dataset.regenerate === "true"
        });
        if (!await saveAssistantDraftEditor() || !isCurrentUser()) return;
        const task = await runAssistantMutation("research", "/research", request, 180000);
        if (task?.topicId && (state.assistantView !== "assistant" || state.assistantSelectedTopicId !== task.topicId)) {
            await openAssistantCreationTopic(task.topicId);
        }
    }

    async function loadAssistantMaterialsFeed(offset = state.assistantMaterialOffset || 0) {
        if (!isLoggedIn() || state.isLoggingOut || state.assistantAccess?.allowed !== true) return;
        const isCurrentUser = captureUserScope();
        const nextOffset = Math.max(0, Number(offset) || 0);
        const query = new URLSearchParams({
            limit: "101",
            offset: String(nextOffset),
            q: (state.assistantMaterialSearch || "").trim(),
            username: state.assistantMaterialAuthor || "",
            status: state.assistantMaterialStatus || "",
            sort: state.assistantMaterialSort || "published"
        });
        try {
            const rows = await contentAssistantRequest("/materials?" + query.toString());
            if (!isCurrentUser()) return;
            const list = Array.isArray(rows) ? rows : [];
            state.assistantMaterialOffset = nextOffset;
            state.assistantMaterialHasNext = list.length > 100;
            state.assistantMaterialsFeed = list.slice(0, 100);
            if (state.assistantDashboard) renderAssistantMaterialsView(state.assistantDashboard);
        } catch (error) {
            if (isCurrentUser()) setContentAssistantError(error.message || "无法读取原始素材列表。");
        }
    }

    function renderAssistantMaterialsView(dashboard) {
        const container = $("assistantMaterialsList");
        if (!container) return;
        const pageLabel = $("assistantMaterialPageLabel");
        if (pageLabel) pageLabel.textContent = `第 ${Math.floor((state.assistantMaterialOffset || 0) / 100) + 1} 页`;
        if ($("assistantMaterialPrevBtn")) $("assistantMaterialPrevBtn").disabled = (state.assistantMaterialOffset || 0) === 0;
        if ($("assistantMaterialNextBtn")) $("assistantMaterialNextBtn").disabled = !state.assistantMaterialHasNext;
        const items = Array.isArray(state.assistantMaterialsFeed) ? state.assistantMaterialsFeed : (dashboard.materials || []);
        const draftedIds = new Set((dashboard.drafts || []).map((draft) => draft.materialId));
        container.innerHTML = items.length
            ? items.map((material) => renderAssistantMaterialCard(material, draftedIds, { showTopicJump: true })).join("")
            : `<div class="assistant-empty">暂无符合筛选条件的原始素材。</div>`;
    }

    function renderAssistantReviewPanel(dashboard) {
        const container = $("assistantReviewList");
        if (!container) return;
        if (typeof renderAssistantHotTagChips === "function") {
            renderAssistantHotTagChips($("assistantReviewTagChips"), dashboard, state.assistantReviewTag || "", "data-assistant-review-tag-chip");
        }
        const topicsById = new Map((dashboard.topics || []).map((t) => [t.id, t]));
        const draftsById = new Map((dashboard.drafts || []).map((d) => [d.id, d]));
        const resolveRowSkillId = (row) => row.skillId || draftsById.get(row.draftId)?.skillId || "";
        const tagFilter = (state.assistantReviewTag || "").trim().toLowerCase();
        const bandFilter = state.assistantReviewScoreBand || "";
        const verFilter = state.assistantReviewDraftVersion || "";
        const skillFilter = state.assistantReviewSkill || "";
        const observations = (Array.isArray(dashboard.observations) ? dashboard.observations : []).filter((row) => {
            const topic = topicsById.get(row.topicId);
            if (tagFilter) {
                const tags = Array.isArray(topic?.tags) ? topic.tags.map((item) => String(item).toLowerCase()) : [];
                if (!tags.some((item) => item.includes(tagFilter))) return false;
            }
            if (bandFilter) {
                const score = Number(topic?.score);
                if (!Number.isFinite(score)) return false;
                if (bandFilter === "80-100" && score < 80) return false;
                if (bandFilter === "60-79" && (score < 60 || score >= 80)) return false;
                if (bandFilter === "0-59" && score >= 60) return false;
            }
            if (verFilter === "initial" && Number(row.draftVersion || 0) !== 1) return false;
            if (verFilter === "revised" && Number(row.draftVersion || 0) < 2) return false;
            if (skillFilter && resolveRowSkillId(row) !== skillFilter) return false;
            return true;
        });
        const metrics = [["views", "浏览"], ["likes", "赞"], ["replies", "评论"], ["reposts", "转发"], ["bookmarks", "收藏"]];
        const cards = observations.length ? observations.map((row) => {
            const topic = topicsById.get(row.topicId);
            const effectiveSkillId = resolveRowSkillId(row);
            const skillBadgeHtml = effectiveSkillId
                ? `<span class="assistant-state ok">${escapeHtml(assistantSkillLabel(effectiveSkillId, dashboard))}</span>`
                : "";
            const status = String(row.status || "").toUpperCase();
            const windowLabel = Number(row.windowHours) === 168 ? "7d" : `${Number(row.windowHours || 0)}h`;
            const missing = new Set(Array.isArray(row.missingMetrics) ? row.missingMetrics : []);
            const measured = metrics.map(([key, label]) =>
                `<span><small>${label}</small><strong>${missing.has(key) || !Number.isFinite(Number(row[key])) ? "--" : fmtNum(row[key])}</strong></span>`).join("");
            const viewsBaseline = row.baseline?.views || {};
            const baselineText = viewsBaseline.ready
                ? `前 ${Number(row.baseline?.maximumCompared || 20)} 条同龄中位数 ${fmtNum(viewsBaseline.median)}${Number.isFinite(Number(viewsBaseline.percentChange)) ? ` · ${Number(viewsBaseline.percentChange) >= 0 ? "+" : ""}${Number(viewsBaseline.percentChange).toFixed(1)}%` : ""}`
                : `同龄样本 ${Number(viewsBaseline.sampleCount ?? 0)}/${Number(row.baseline?.minimumRequired || 5)} · 基线不足仅展示原值`;
            const tagsText = Array.isArray(topic?.tags) && topic.tags.length ? topic.tags.map((t) => `#${escapeHtml(t)}`).join(" ") : "";
            return `<article class="assistant-observation-card">
                <div class="assistant-pane-head">
                    <strong>${escapeHtml(topic?.title || row.postId || "已发布内容")} · ${windowLabel}</strong>
                    <span class="assistant-draft-head-actions">${skillBadgeHtml}<span class="assistant-state ${assistantStatusClass(status)}">${escapeHtml(assistantStatusLabel(status))}</span></span>
                </div>
                <p class="assistant-observation-time">
                    ${Number.isFinite(Number(topic?.score)) ? `综合分 ${Math.round(topic.score)} · ` : ""}
                    ${Number(row.draftVersion) > 0 ? `采用稿件 v${Number(row.draftVersion)} · ` : ""}
                    ${row.observedAt ? `实测 ${escapeHtml(formatAssistantDateTime(row.observedAt))}` : "待测量"}
                    ${tagsText ? ` · ${tagsText}` : ""}
                </p>
                ${status === "OBSERVED" ? `<div class="assistant-observation-metrics">${measured}</div>` : ""}
                <p class="assistant-baseline-line">${escapeHtml(row.reason || baselineText)}</p>
            </article>`;
        }).join("") : `<div class="assistant-empty compact">暂无符合筛选条件的发布同龄观察记录。</div>`;
        const followerRows = Array.isArray(dashboard.followers) ? dashboard.followers : [];
        const followersHtml = followerRows.length ? followerRows.slice().sort((a, b) =>
            Number(b.observedAt || b.attemptedAt || 0) - Number(a.observedAt || a.attemptedAt || 0)).slice(0, 7).map((row) =>
            `<div class="assistant-follower-row"><span>${escapeHtml(row.localDate || formatAssistantDateTime(row.observedAt || row.attemptedAt))}</span>
                <span class="assistant-state ${assistantStatusClass(row.status)}">${escapeHtml(assistantStatusLabel(row.status))}</span>
                <strong>${row.followers !== null && row.followers !== undefined && Number.isFinite(Number(row.followers)) ? fmtNum(row.followers) : "--"}</strong>
                <em>${row.netChange !== null && row.netChange !== undefined && Number.isFinite(Number(row.netChange))
                    ? `${Number(row.netChange) >= 0 ? "+" : ""}${fmtNum(row.netChange)}` : escapeHtml(row.error || "")}</em></div>`
        ).join("") : `<div class="assistant-empty compact">尚无每日粉丝快照。</div>`;
        container.innerHTML = `<div class="assistant-observations-grid">
            <div><h5>同龄表现</h5>${cards}</div>
            <div><h5>粉丝日变化</h5>${followersHtml}</div>
        </div>`;
    }

    async function saveAssistantDraftEditor() {
        clearTimeout(state.assistantDraftSaveTimerId);
        const editor = $("assistantDraftEditor");
        const draftBox = editor?.closest("[data-draft-id]");
        if (!editor || !draftBox) return true;
        const draftId = draftBox.dataset.draftId;
        const isCurrentUser = captureUserScope();
        if (!isCurrentUser()) return false;
        if (state.assistantDraftSave) {
            const saved = await state.assistantDraftSave;
            if (!saved || !isCurrentUser() || $("assistantDraftEditor") !== editor) return false;
            return saveAssistantDraftEditor();
        }
        if (state.assistantDraftConflict?.id === draftId) return false;
        const queue = state.assistantDashboard?.drafts || [];
        const draft = queue.find((item) => item.id === draftId);
        if (!state.assistantDraftDirty) {
            if (draft && Number(draft.version) === Number(draftBox.dataset.draftVersion)) return draft;
            if (draft) renderAssistantTopicDetail(state.assistantDashboard);
            setContentAssistantError("稿件已更新，请确认当前显示的版本后再操作。");
            return false;
        }
        const text = editor.value;
        const version = Number(draftBox.dataset.draftVersion || 0);
        $("assistantDraftSaveState").textContent = "正在保存…";
        const pending = contentAssistantRequest(`/drafts/${encodeURIComponent(draftId)}/save`, { version, text })
            .then((saved) => {
                if (!isCurrentUser() || $("assistantDraftEditor") !== editor) return false;
                const drafts = state.assistantDashboard?.drafts || [];
                const index = drafts.findIndex((item) => item.id === draftId);
                if (index >= 0) drafts[index] = { ...drafts[index], ...saved };
                draftBox.dataset.draftVersion = String(saved.version);
                $("assistantDraftTitle").textContent = `初稿 v${saved.version}`;
                const options = Array.from($("assistantDraftSelect")?.options || []);
                const optionIndex = options.findIndex((option) => option.value === draftId);
                if (optionIndex >= 0) options[optionIndex].textContent = `稿件 ${optionIndex + 1} · v${saved.version}`;
                const length = $("assistantDraftLength");
                length.hidden = !saved.hanLength;
                length.textContent = assistantDraftLengthText(saved.hanLength);
                const numberChanges = $("assistantDraftNumberChanges");
                numberChanges.textContent = assistantDraftNumberChangesText(saved.numberChanges);
                numberChanges.hidden = !numberChanges.textContent;
                const updatedWhileSaving = editor.value !== text;
                state.assistantDraftDirty = updatedWhileSaving;
                syncScheduleDraftUnloadProtection();
                $("assistantDraftSaveState").textContent = updatedWhileSaving ? "等待保存…" : "已保存";
                return saved;
            }, async (error) => {
                if (isCurrentUser() && $("assistantDraftEditor") === editor) {
                    $("assistantDraftSaveState").textContent = error.message || "保存失败，编辑仍在此页。";
                    if (error.httpStatus === 409) {
                        try {
                            const versions = await contentAssistantRequest(`/drafts/${encodeURIComponent(draftId)}/versions`);
                            const latest = versions[0];
                            if (!isCurrentUser() || $("assistantDraftEditor") !== editor || latest?.id !== draftId || latest.version <= version) return false;
                            state.assistantDraftConflict = latest;
                            $("assistantDraftRemoteText").textContent = latest.text;
                            $("assistantDraftConflict").hidden = false;
                            $("assistantDraftSaveState").textContent = `另一页面已保存 v${latest.version}。本页输入已保留，请选择要使用的正文。`;
                        } catch {
                            if (isCurrentUser() && $("assistantDraftEditor") === editor)
                                $("assistantDraftSaveState").textContent = "暂时无法读取新版本，本页输入已保留，可继续编辑后重试。";
                        }
                    }
                }
                return false;
            })
            .finally(() => {
                if (state.assistantDraftSave === pending) state.assistantDraftSave = null;
            });
        state.assistantDraftSave = pending;
        const saved = await pending;
        if (!saved || !isCurrentUser()) return false;
        return state.assistantDraftDirty ? saveAssistantDraftEditor() : saved;
    }

    async function resolveAssistantDraftConflict(choice) {
        const latest = state.assistantDraftConflict;
        const editor = $("assistantDraftEditor");
        const box = editor?.closest("[data-draft-id]");
        if (!latest || latest.id !== box?.dataset.draftId || !["local", "remote"].includes(choice)) return;
        const isCurrentUser = captureUserScope();
        state.assistantDraftConflict = null;
        $("assistantDraftConflict").hidden = true;
        box.dataset.draftVersion = String(latest.version);
        if (choice === "local") {
            if (!await saveAssistantDraftEditor() || !isCurrentUser()) return;
        } else {
            editor.value = latest.text;
            state.assistantDraftDirty = false;
            const cached = state.assistantDashboard?.drafts?.find(draft => draft.id === latest.id);
            if (cached) Object.assign(cached, latest, {topicId: cached.topicId});
            $("assistantDraftTitle").textContent = `初稿 v${latest.version}`;
            $("assistantDraftSaveState").textContent = "已采用另一页面的版本";
            syncScheduleDraftUnloadProtection();
        }
        await loadContentAssistant(true, undefined, true);
    }

    function queueAssistantDraftSave() {
        clearTimeout(state.assistantDraftSaveTimerId);
        const editor = $("assistantDraftEditor");
        const draftBox = editor?.closest("[data-draft-id]");
        if (!draftBox) return;
        state.assistantDraftDirty = true;
        document.querySelectorAll("[data-assistant-update-scheduled][hidden]").forEach((button) => {
            if (button.dataset.publicationDraftId === draftBox.dataset.draftId) button.hidden = false;
        });
        syncScheduleDraftUnloadProtection();
        if (state.assistantDraftConflict?.id === draftBox.dataset.draftId) return;
        $("assistantDraftSaveState").textContent = "等待保存…";
        state.assistantDraftSaveTimerId = setTimeout(() => void saveAssistantDraftEditor(), 1000);
    }

    async function sendAssistantDraftToSchedule(draftId, quoteSource = false) {
        const resolved = await saveAssistantDraftEditor();
        if (!resolved || resolved.id !== draftId) return;
        if (state.scheduleDraftRestore || state.scheduleDraftRestoring || state.scheduleDraftPersistenceReady === false) {
            const isCurrentUser = captureUserScope();
            await (state.scheduleDraftRestore?.promise || restoreScheduleDraftAfterStepUpAuth());
            if (!isCurrentUser() || state.scheduleDraftRestoring || state.scheduleDraftPersistenceReady === false) return;
        }
        const topicId = resolved.topicId;
        const topicMaterials = (state.assistantDashboard?.materials || []).filter((item) =>
            item.topicId === topicId);
        const sourcePost = quoteSource ? resolveAssistantDraftSourcePost(resolved, topicMaterials) : null;
        if (quoteSource && !sourcePost) {
            setContentAssistantError("该素材不含有效推文 ID，不支持引用原帖定时发布。");
            return;
        }
        ensureScheduleDraftInitialized();
        const target = state.scheduleDraftItems.find((item) => !hasScheduleDraftContent(item));
        const sourceRef = {
            assistantDraftId: resolved.id,
            assistantDraftVersion: Number(resolved.version),
            topicId,
            quoteSource: Boolean(quoteSource && sourcePost)
        };
        const quoteMeta = sourcePost ? { quoteTweetId: sourcePost.tweetId, quoteTweetUrl: sourcePost.url } : null;
        if (target) {
            target.text = resolved.text;
            target.sourceRef = sourceRef;
            target.quoteTweetId = quoteMeta ? quoteMeta.quoteTweetId : "";
            target.quoteTweetUrl = quoteMeta ? quoteMeta.quoteTweetUrl : "";
        } else if (state.scheduleDraftItems.length < MAX_BATCH_SCHEDULE_ITEMS) {
            state.scheduleDraftItems.push(createScheduleDraftItem(resolved.text, null, [], false, sourceRef, null, quoteMeta));
            state.activeScheduleDraftIdx = state.scheduleDraftItems.length - 1;
        } else {
            setContentAssistantError("排期草稿已满，请先提交或移除一条。");
            return;
        }
        rotateComposerRequestId();
        renderScheduleDraftItems();
        queueScheduleDraftPersistence();
        switchTab("schedule");
        if (resolved.text.length > MAX_SCHEDULE_TEXT_LENGTH) {
            const targetIndex = target ? state.scheduleDraftItems.indexOf(target) : state.activeScheduleDraftIdx;
            setScheduleComposerError(scheduleTextLengthError(targetIndex, state.scheduleDraftItems.length),
                true, { draftIndex: targetIndex, field: "text" });
        } else {
            showToast(sourcePost ? "已带原帖引用转入定时编辑区" : "已将助手稿件带入定时编辑区");
        }
    }

    function renderTimingPanel(report) {
        const ct = report.contentTiming;
        const formatStats = ct.formatStats || [];
        const bestFormatCode = ct.bestFormatCode;

        $("formatCardsGrid").innerHTML = formatStats
            .map((f) => {
                const isWinner = Boolean(bestFormatCode && f.formatCode === bestFormatCode && f.postCount > 0);
                const bmKnown = hasMetricSample(f, "bookmarkSampleCount");
                const hasPosts = f.postCount > 0;
                const viewSummary = hasPosts && f.viewSampleCount === 0 ? tr("浏览量尚未取得")
                    : `均浏览 ${hasPosts && hasMetricSample(f, "viewSampleCount") ? fmtNum(f.avgViews) : "--"}`;
                const formatSummary = !hasPosts && ct.timelineWindowComplete !== true ? tr("已采集样本中暂无该体裁的主帖")
                    : `${f.postCount} 帖 · ${viewSummary}`;
                return `<div class="format-card${isWinner ? " is-winning-format" : ""}">
                    <div class="format-head-row">
                        <div class="format-title">${escapeHtml(f.formatLabel)}</div>
                        ${isWinner ? `<span class="mini-tag green format-win-badge">表现最佳</span>` : ""}
                    </div>
                    <div class="format-er">${hasMetricSample(f, "engagementRateSampleCount") ? `${f.avgEngagementRatePct}%` : "--"} <span style="font-size:11.5px; font-weight:500; color:var(--text-secondary)">互动率</span></div>
                    <div class="format-meta">${escapeHtml(formatSummary)}</div>
                    <div class="format-meta">赞 ${hasMetricSample(f, "likeSampleCount") ? f.avgLikes : "--"} · 评 ${hasMetricSample(f, "replySampleCount") ? f.avgReplies : "--"} · 藏 ${bmKnown ? f.avgBookmarks : "--"}</div>
                </div>`;
            })
            .join("");

        renderAlgoPoolAndSimulator(report);
        renderTopTweetsList(report);
    }

    function opsActionButtonLabel(actionTarget) {
        const target = String(actionTarget || "");
        if (target.includes("UNREPLIED_POST")) return "去回复 ›";
        if (target.includes("I_OWE")) return "去回访 ›";
        if (target.includes("tab:schedule")) return "去排期 ›";
        if (target.includes("focusDraft:1")) return "去体检 ›";
        if (target.includes("tab:interactions")) return "去互动 ›";
        if (target.includes("scrollId:formatCardsGrid")) return "看对比 ›";
        if (target.includes("scrollId:topTweetsList")) return "看推文 ›";
        if (target.includes("scrollId:algoCalibrationPanel")) return "看明细 ›";
        return "查看 ›";
    }

    function renderAlgoPoolAndSimulator(report) {
        const ah = report.algorithmHealth;
        if (!ah) return;

        const profile = getActiveAlgoProfile();
        const cr = profile.cadenceAndReach || DEFAULT_ALGO_PROFILE.cadenceAndReach;
        const minGapMin = Math.max(15, Number(cr.minRecommendedGapMinutes || 180));
        const minGapHrsLabel = Math.round((minGapMin / 60) * 10) / 10;

        const evaluated = ah.viewSampleCount ?? ah.evaluatedPostCount;
        const viewsKnown = hasMetricSample(ah, "viewSampleCount");
        const evalTotal = Math.max(1, evaluated || 0);
        const oonPct = viewsKnown ? Math.round((ah.outOfNetworkPostCount * 100) / evalTotal) : "--";
        const inNetPct = viewsKnown ? Math.round((ah.inNetworkPostCount * 100) / evalTotal) : "--";
        const lowPct = viewsKnown ? Math.round((ah.lowReachPostCount * 100) / evalTotal) : "--";

        const poolsEl = $("algoReachPoolsGrid");
        if (poolsEl) {
            const oonMult = Number(cr.oonFollowerMultiple || 1.0);
            const oonMultPct = Math.round(oonMult * 100);
            const lowRatioPct = Math.round(Number(cr.lowReachFollowerRatio || 0.05) * 100);
            const oonSubLabel = oonMult === 1 ? "浏览 ≥ 粉丝数" : `浏览 ≥ ${oonMult}倍粉丝`;
            poolsEl.innerHTML = `
                <div class="format-card pool-card oon">
                    <div class="format-title">破圈推文</div>
                    <div class="format-er" style="color:var(--x-green)">${viewsKnown ? ah.outOfNetworkPostCount : "--"}<span class="pool-pct">(${oonPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="format-meta">${escapeHtml(oonSubLabel)}</div>
                </div>
                <div class="format-card pool-card innet">
                    <div class="format-title">常规触达</div>
                    <div class="format-er" style="color:var(--x-blue)">${viewsKnown ? ah.inNetworkPostCount : "--"}<span class="pool-pct">(${inNetPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="format-meta">粉丝数 ${lowRatioPct}%–${oonMultPct}%</div>
                </div>
                <div class="format-card pool-card low">
                    <div class="format-title">低曝光</div>
                    <div class="format-er" style="color:${ah.lowReachPostCount > 0 ? "var(--x-amber)" : "var(--text-primary)"}">${viewsKnown ? ah.lowReachPostCount : "--"}<span class="pool-pct">(${lowPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="format-meta">低于粉丝数 ${lowRatioPct}%</div>
                </div>
            `;
        }

        const calEl = $("algoCalibrationPanel");
        if (calEl) {
            const cal = ah.calibration || null;
            if (cal && (cal.samplePostCount > 0 || cal.unknownRelationCommentCount > 0 || cal.knownRelationCommentCount > 0
                    || (cal.replyTemplateRisk && cal.replyTemplateRisk.evaluatedReplyCount > 0))) {
                const replyBoostMult = (Number(cal.inferredEffectiveReplyWeight || 5) / 5).toFixed(1);
                const mutualVal = cal.mutualShareKnown ? `${cal.mutualCommenterSharePct}%` : "--";
                const mutualSub = cal.mutualShareKnown
                    ? `评论加成 ×${replyBoostMult}`
                    : "待积累评论";
                const baseSub = cal.samplePostCount > 0
                    ? `约 ${cal.medianFollowerReachMultiple} 倍粉丝数`
                    : "待积累主推";
                const coldVal = cal.coldStartEligibleAccount
                    ? (cal.samplePostCount > 0 ? `${cal.coldStartPassRatePct}%` : "--")
                    : "已豁免";
                const coldSub = cal.coldStartEligibleAccount
                    ? `${cal.coldStartPassCount}/${cal.samplePostCount} 帖点赞 ≥1.5%`
                    : "粉丝超 5 万";
                const rtr = cal.replyTemplateRisk || {};
                const rtrVal = rtr.copyPastaRiskTriggered
                    ? `重复 ${rtr.maxClusterSize} 条`
                    : "良好";
                const rtrSub = rtr.copyPastaRiskTriggered
                    ? "减少重复话术"
                    : "无刷屏重复";

                const extraLines = [];
                if (Array.isArray(cal.domainStats) && cal.domainStats.length > 0) {
                    const domBadges = cal.domainStats.slice(0, 4).map((d) => {
                        const sign = d.vsNativePct > 0 ? `+${d.vsNativePct}%` : `${d.vsNativePct}%`;
                        const warn = d.shortenerDomain ? "⚠️短链 " : "";
                        return `<span>${warn}${escapeHtml(d.domain)} · 均 ${fmtNum(d.avgViews)} (${sign})</span>`;
                    });
                    extraLines.push(`<div class="algo-cal-extra-line"><strong>常用外链</strong>：${domBadges.join(" ｜ ")}</div>`);
                }

                calEl.innerHTML = `
                    <div class="algo-cal-grid">
                        <div class="algo-cal-card">
                            <span class="algo-cal-label">日常单帖浏览</span>
                            <span class="algo-cal-val">${cal.samplePostCount > 0 ? fmtNum(cal.medianViews) : "--"}</span>
                            <span class="algo-cal-sub">${escapeHtml(baseSub)}</span>
                        </div>
                        <div class="algo-cal-card">
                            <span class="algo-cal-label">互关评论占比</span>
                            <span class="algo-cal-val" style="color:var(--x-blue)">${escapeHtml(mutualVal)}</span>
                            <span class="algo-cal-sub">${escapeHtml(mutualSub)}</span>
                            ${cal.unknownRelationCommentCount > 0 ? `<span class="algo-cal-sub">${escapeHtml(`关系已核实 ${cal.knownRelationCommentCount ?? 0} 条，未知 ${cal.unknownRelationCommentCount} 条`)}</span>` : ""}
                        </div>
                        <div class="algo-cal-card">
                            <span class="algo-cal-label">新帖冷启动达标</span>
                            <span class="algo-cal-val" style="color:var(--x-green)">${escapeHtml(coldVal)}</span>
                            <span class="algo-cal-sub">${escapeHtml(coldSub)}</span>
                        </div>
                        <div class="algo-cal-card">
                            <span class="algo-cal-label">回复原创度</span>
                            <span class="algo-cal-val" style="color:${rtr.copyPastaRiskTriggered ? "var(--x-amber)" : "var(--x-green)"}">${escapeHtml(rtrVal)}</span>
                            <span class="algo-cal-sub">${escapeHtml(rtrSub)}</span>
                        </div>
                    </div>
                    ${extraLines.join("")}
                `;
                calEl.hidden = false;
            } else {
                calEl.innerHTML = "";
                calEl.hidden = true;
            }
        }

        const playbookEl = $("algoOpsPlaybookList");
        if (playbookEl) {
            const rawOps = Array.isArray(ah.opsRecommendations) ? ah.opsRecommendations : [];
            const cards = [];
            for (const item of rawOps) {
                if (!item) continue;
                if (item.code === "RESIDUAL_ANOMALY_REVIEW") continue;
                const isReview = item.code === "REVIEW_LOW_VIEW_POST";
                const reviewUrl = isReview ? safeHttpUrl(item.externalUrl, "") : "";
                if (isReview && !reviewUrl) continue;
                if (item.code === "CLEAN_LOW_SIGNAL_FOLLOWS" || item.code === "LOYAL_FAN_FOLLOWBACK") {
                    continue; // 关系清理与回关类建议保留在总览待办清单，内容页聚焦内容/时段/曝光策略
                }
                const title = item.title || "";
                const detail = item.detail || "";
                const prio = String(item.priority || "MEDIUM").toUpperCase();
                const prioClass = prio === "HIGH" ? "prio-high" : prio === "LOW" ? "prio-low" : "prio-medium";
                const prioLabel = prio === "HIGH" ? "优先" : "建议";
                const actionHtml = item.externalUrl
                    ? `<a class="algo-ops-go" href="${escapeHtml(isReview ? reviewUrl : safeHttpUrl(item.externalUrl, "https://x.com"))}" target="_blank" rel="noopener noreferrer">${isReview ? "查看推文 ↗" : "前往 ↗"}</a>`
                    : `<button type="button" class="algo-ops-go" data-go="${escapeHtml(item.actionTarget || "tab:timing")}">${escapeHtml(opsActionButtonLabel(item.actionTarget))}</button>`;
                cards.push(`
                    <div class="algo-ops-item">
                        <div class="algo-ops-main">
                            <div class="algo-ops-title-row">
                                <span class="algo-ops-badge ${prioClass}">${prioLabel}</span>
                                <span class="algo-ops-title">${escapeHtml(title)}</span>
                            </div>
                            ${detail ? `<div class="algo-ops-detail">${escapeHtml(detail)}</div>` : ""}
                        </div>
                        ${actionHtml}
                    </div>
                `);
            }
            playbookEl.innerHTML = cards.join("");
            playbookEl.hidden = cards.length === 0;
        }

        const tipsBox = $("algoPlainTipsBox");
        if (tipsBox) {
            tipsBox.innerHTML = "";
            tipsBox.hidden = true;
        }

        const timingHintEl = $("draftLiveTimingHint");
        if (timingHintEl) {
            if (ah.latestMainPostAt) {
                const gapHrs = Math.max(0, (Date.now() - new Date(ah.latestMainPostAt).getTime()) / 3600000);
                if (gapHrs < minGapHrsLabel) {
                    timingHintEl.textContent = `距上条仅 ${gapHrs.toFixed(1)}h，建议间隔 ≥${minGapHrsLabel}h`;
                    timingHintEl.hidden = false;
                } else {
                    timingHintEl.textContent = "";
                    timingHintEl.hidden = true;
                }
            } else {
                timingHintEl.textContent = "";
                timingHintEl.hidden = true;
            }
        }

        renderAlgoConfigDrawer();
        evaluatePrePostDraft();
    }

    const KNOWN_SHORTENER_DOMAINS = new Set([
        "bit.ly", "tinyurl.com", "t.cn", "is.gd", "cutt.ly", "buff.ly",
        "ow.ly", "rebrand.ly", "shorturl.at", "rb.gy", "soo.gd"
    ]);

    function classifyDraftLinks(urls, nativeAttachment = false) {
        let shortLinks = 0;
        let unknown = false;
        for (const raw of urls) {
            try {
                const host = new URL(raw).hostname.toLowerCase().replace(/\.$/, "");
                if (host === "t.co") shortLinks++;
                else if (host && !/(^|\.)(x\.com|twitter\.com)$/.test(host)) {
                    return { hasExternalLink: true, domain: host.replace(/^www\./, "") };
                }
            } catch (_) {
                unknown = true;
            }
        }
        return { hasExternalLink: unknown || shortLinks > Number(nativeAttachment) ? null : false, domain: "" };
    }

    function buildCalibrationShingleArray(cleanText) {
        if (!cleanText) return [];
        const norm = String(cleanText).toLowerCase();
        const set = new Set();
        const words = norm.match(/[a-z0-9_]{2,}/g) || [];
        for (const w of words) {
            if (set.size >= 64) break;
            set.add(`w:${w}`);
        }
        const compact = norm.replace(/[^a-z0-9\u4e00-\u9fff]/g, "");
        const chars = Array.from(compact);
        if (chars.length === 1 && set.size < 64) {
            set.add(`c:${chars[0]}`);
        } else {
            for (let i = 0; i < chars.length - 1 && set.size < 64; i++) {
                if (/[\u4e00-\u9fff]/.test(chars[i]) || /[\u4e00-\u9fff]/.test(chars[i + 1])) {
                    set.add(`g:${chars[i]}${chars[i + 1]}`);
                }
            }
        }
        return Array.from(set);
    }

    function interpolateCorpusViewsAtPercentile(sortedViews, pct) {
        if (!sortedViews.length) return 0;
        const clamped = Math.max(0, Math.min(100, Number(pct) || 0)) / 100;
        const pos = clamped * (sortedViews.length - 1);
        const lo = Math.floor(pos);
        const hi = Math.ceil(pos);
        if (lo === hi) return sortedViews[lo];
        return Math.round(sortedViews[lo] * (hi - pos) + sortedViews[hi] * (pos - lo));
    }

    function predictDraftFromCorpus(cal, draftFeat, pipelineMult, followersCount) {
        if (!cal || !cal.predictionUnlocked || !Array.isArray(cal.corpusSamples) || cal.corpusSamples.length < (cal.minRequiredSamples || 6)) {
            return {
                unlocked: false,
                sampleCount: cal ? Number(cal.samplePostCount || 0) : 0,
                minRequired: cal ? Number(cal.minRequiredSamples || 6) : 6
            };
        }
        const qSet = new Set(draftFeat.shingles || []);
        const priorWeight = 1.6;
        let wSum = priorWeight;
        let wySum = priorWeight * 50.0;

        const scoredNeighbors = [];
        for (const s of cal.corpusSamples) {
            if (!s) continue;
            const sShingles = Array.isArray(s.shingles) ? s.shingles : [];
            let inter = 0;
            for (const tok of sShingles) {
                if (qSet.has(tok)) inter++;
            }
            const union = qSet.size + sShingles.length - inter;
            const jaccard = union > 0 ? inter / union : 0.0;
            const fmtMatch = draftFeat.formatCode === s.formatCode ? 1.0 : 0.0;
            const extMatch = typeof draftFeat.hasExternalLink === "boolean" && draftFeat.hasExternalLink === s.hasExternalLink ? 1.0 : 0.0;
            const domainMatch = (draftFeat.hasExternalLink && s.hasExternalLink && draftFeat.externalDomain && draftFeat.externalDomain === s.externalDomain) ? 1.0 : 0.0;
            const listMatch = Boolean(draftFeat.structuredList) === Boolean(s.structuredList) ? 1.0 : 0.0;
            const qMatch = Boolean(draftFeat.hasQuestion) === Boolean(s.hasQuestion) ? 1.0 : 0.0;
            const lenDiff = Math.abs(Math.log1p(Math.max(0, draftFeat.cleanCharLen)) - Math.log1p(Math.max(0, Number(s.cleanCharLen || 0))));

            const z = 2.6 * jaccard
                + 0.85 * fmtMatch
                + 0.35 * extMatch
                + 0.55 * domainMatch
                + 0.30 * listMatch
                + 0.25 * qMatch
                - 0.30 * Math.min(2.5, lenDiff);
            const w = Math.max(0.05, Math.exp(z) - 0.85);
            wSum += w;
            wySum += w * Number(s.rankPercentile || 50);
            scoredNeighbors.push({ sample: s, weight: w, jaccard });
        }

        const rawMeanPct = wySum / wSum;
        let varNum = priorWeight * Math.pow(50.0 - rawMeanPct, 2);
        for (const sn of scoredNeighbors) {
            varNum += sn.weight * Math.pow(Number(sn.sample.rankPercentile || 50) - rawMeanPct, 2);
        }
        const std = Math.sqrt(varNum / wSum);
        const halfBand = Math.max(10, Math.min(22, Math.round(std * 0.9)));

        const rawLowPct = Math.max(5, Math.min(92, Math.round(rawMeanPct - halfBand)));
        const rawHighPct = Math.max(rawLowPct + 6, Math.min(99, Math.round(rawMeanPct + halfBand)));

        const safePipeMult = Math.max(0.15, Math.min(1.0, Number(pipelineMult) || 1.0));
        const effCenterPct = rawMeanPct * safePipeMult;
        const effLowPct = Math.max(3, Math.min(90, Math.round(effCenterPct - halfBand * safePipeMult)));
        const effHighPct = Math.max(effLowPct + 5, Math.min(99, Math.round(effCenterPct + halfBand * safePipeMult)));

        const sortedViews = scoredNeighbors
            .map((sn) => Math.max(0, Number(sn.sample.viewCount || 0)))
            .sort((a, b) => a - b);
        const rawLowViews = interpolateCorpusViewsAtPercentile(sortedViews, rawLowPct);
        const rawHighViews = interpolateCorpusViewsAtPercentile(sortedViews, rawHighPct);
        const expViewsLow = Math.max(1, Math.round(rawLowViews * safePipeMult));
        const expViewsHigh = Math.max(expViewsLow, Math.round(rawHighViews * safePipeMult));

        const fCnt = Number(followersCount);
        const multLow = Number.isFinite(fCnt) && fCnt > 0 ? (expViewsLow / fCnt).toFixed(2) : null;
        const multHigh = Number.isFinite(fCnt) && fCnt > 0 ? (expViewsHigh / fCnt).toFixed(2) : null;

        scoredNeighbors.sort((a, b) => b.weight - a.weight);
        const topNeighbors = scoredNeighbors.slice(0, 3).map((sn) => ({
            id: sn.sample.id,
            createdLabel: sn.sample.createdLabel,
            formatCode: sn.sample.formatCode,
            textPreview: sn.sample.textPreview,
            viewCount: sn.sample.viewCount,
            rankPercentile: sn.sample.rankPercentile,
            realizedVmUtilityPer1k: sn.sample.realizedVmUtilityPer1k,
            tweetUrl: sn.sample.tweetUrl,
            jaccardPct: Math.round(sn.jaccard * 100)
        }));

        return {
            unlocked: true,
            sampleCount: Number(cal.samplePostCount || scoredNeighbors.length),
            loocvBandHitRatePct: cal.loocvBandHitRatePct,
            loocvMaePercentile: cal.loocvMaePercentile,
            rawLowPct,
            rawHighPct,
            effLowPct,
            effHighPct,
            pipelineDiscounted: safePipeMult < 0.98,
            pipelineMult: safePipeMult,
            expViewsLow,
            expViewsHigh,
            multLow,
            multHigh,
            topNeighbors
        };
    }

    function computeUpstreamDiversityMult(ev, sameAuthorBatchIdx) {
        const enabled = ev && ev.enableAuthorDiversity !== false;
        const k = Math.max(0, Math.floor(Number(sameAuthorBatchIdx) || 0));
        if (!enabled || k <= 0) return 1.0;
        const floor = Number.isFinite(Number(ev.authorDiversityFloor)) ? Number(ev.authorDiversityFloor) : 0.25;
        const decay = Number.isFinite(Number(ev.authorDiversityDecay)) ? Number(ev.authorDiversityDecay) : 0.5;
        return (1.0 - floor) * Math.pow(decay, k) + floor;
    }

    function normalizeForDuplicateCheck(raw) {
        return String(raw || "")
            .replace(URL_TOKEN_RE, " ")
            .toLowerCase()
            .replace(/[\s\p{P}\p{S}]+/gu, " ")
            .trim();
    }

    function buildDuplicateShingleSet(normText) {
        const set = new Set();
        if (!normText) return set;
        const wordTokens = normText.match(/[a-z0-9_]{2,}/g) || [];
        for (const w of wordTokens) set.add(`w:${w}`);
        const cjkCompact = normText.replace(/[^a-z0-9\u4e00-\u9fff]/g, "");
        const chars = Array.from(cjkCompact);
        if (chars.length === 1) {
            set.add(`c:${chars[0]}`);
        } else {
            for (let i = 0; i < chars.length - 1; i++) {
                const a = chars[i];
                const b = chars[i + 1];
                if (/[\u4e00-\u9fff]/.test(a) || /[\u4e00-\u9fff]/.test(b)) {
                    set.add(`g:${a}${b}`);
                }
            }
        }
        return set;
    }

    function evaluateCorpusSimilarity(text, skipDraftIdx) {
        const draft = String(text || "").trim();
        if (!draft) {
            return { maxSim: 0, exactMatch: false, sourceLabel: "" };
        }
        const normDraft = normalizeForDuplicateCheck(draft);
        const draftSet = Array.from(normDraft).length >= 8 ? buildDuplicateShingleSet(normDraft) : null;
        const candidates = [];
        for (const p of representativePostCards(state.report?.contentTiming)) {
            if (p && p.text) candidates.push({ text: p.text, label: "近期已发样本推文" });
        }
        for (const j of (state.scheduleJobs || [])) {
            if (j && j.status === "SCHEDULED" && j.text) {
                candidates.push({ text: j.text, label: "待发定时队列推文" });
            }
        }
        (state.scheduleDraftItems || []).forEach((it, idx) => {
            if (idx !== skipDraftIdx && it && it.text && it.text.trim()) {
                candidates.push({ text: it.text, label: `排期草稿 #${idx + 1}` });
            }
        });

        let maxSim = 0;
        let bestLabel = "";
        let exactMatch = false;
        for (const cand of candidates) {
            const normCand = normalizeForDuplicateCheck(cand.text);
            if (cand.text.trim() === draft || (normDraft && normCand === normDraft)) {
                return { maxSim: 1.0, exactMatch: true, sourceLabel: cand.label };
            }
            if (!draftSet?.size || !normCand) continue;
            const candSet = buildDuplicateShingleSet(normCand);
            if (!candSet.size) continue;
            let inter = 0;
            for (const tok of draftSet) {
                if (candSet.has(tok)) inter++;
            }
            const union = draftSet.size + candSet.size - inter;
            const sim = union > 0 ? inter / union : 0;
            if (sim > maxSim) {
                maxSim = sim;
                bestLabel = cand.label;
                if (sim >= 0.96) exactMatch = true;
            }
        }
        return { maxSim, exactMatch, sourceLabel: bestLabel };
    }

    function inspectMentionFollowerRelation(handles) {
        if (!handles.length) return { nonConfirmed: [], confirmedNonFollower: [] };
        const confirmedFollowers = new Set();
        const confirmedNonFollowers = new Set();
        const selfHandle = state.report && state.report.profile && state.report.profile.username
            ? String(state.report.profile.username).toLowerCase()
            : "";
        if (selfHandle) confirmedFollowers.add(selfHandle);

        const fa = state.report && state.report.followAudit ? state.report.followAudit : null;
        if (fa) {
            for (const item of (fa.fansNotFollowedBackList || [])) {
                if (item && item.user && item.user.username) confirmedFollowers.add(String(item.user.username).toLowerCase());
            }
            for (const item of (fa.mutualList || [])) {
                if (item && item.user && item.user.username) confirmedFollowers.add(String(item.user.username).toLowerCase());
            }
            for (const item of (fa.notFollowingBackList || [])) {
                if (item && item.user && item.user.username && item.theyFollowMe === false && item.relationState !== "UNKNOWN") {
                    confirmedNonFollowers.add(String(item.user.username).toLowerCase());
                }
            }
        }
        const inter = state.report && state.report.interactions ? state.report.interactions : null;
        if (inter && Array.isArray(inter.peerStats)) {
            for (const p of inter.peerStats) {
                const h = p && p.peer && p.peer.username ? String(p.peer.username).toLowerCase() : "";
                if (!h) continue;
                if (p.theyFollowMe) {
                    confirmedFollowers.add(h);
                } else if (p.theyFollowMe === false && !confirmedFollowers.has(h)) {
                    confirmedNonFollowers.add(h);
                }
            }
        }

        const nonConfirmed = [];
        const confirmedNonFollower = [];
        for (const rawH of handles) {
            const h = rawH.toLowerCase();
            if (!confirmedFollowers.has(h)) {
                nonConfirmed.push(rawH);
                if (confirmedNonFollowers.has(h)) {
                    confirmedNonFollower.push(rawH);
                }
            }
        }
        return { nonConfirmed, confirmedNonFollower };
    }

    function analyzeDraftByXAlgo(rawText, options = {}) {
        const text = String(rawText || "").trim();
        const hasMedia = Boolean(options.hasMedia);
        const isThreadOpt = Boolean(options.isThread);
        const plannedEpochMs = Number(options.plannedEpochMs);
        const hasPlannedTime = Number.isFinite(plannedEpochMs);
        const skipDraftIdx = Number.isInteger(options.skipDraftIdx) ? options.skipDraftIdx : -1;

        const ah = state.report ? state.report.algorithmHealth : null;
        const ct = state.report ? state.report.contentTiming : null;
        const profile = getActiveAlgoProfile();
        const ev = profile.upstreamEvidence || DEFAULT_ALGO_PROFILE.upstreamEvidence;
        const cr = profile.cadenceAndReach || DEFAULT_ALGO_PROFILE.cadenceAndReach;
        const dc = profile.draftChecker || DEFAULT_ALGO_PROFILE.draftChecker;

        const minGapMin = Math.max(15, Number(cr.minRecommendedGapMinutes || 180));
        const severeGapMin = Math.max(5, Math.min(minGapMin, Number(cr.severeRapidFireGapMinutes || 60)));
        const minGapHrsLabel = Math.round((minGapMin / 60) * 10) / 10;
        const ageWindowHrs = Math.max(1, Number(ev.maxCandidateAgeHours || 48));

        let score = Number(dc.baseScore ?? 100);
        const checks = [];
        const sampleNotes = [];
        const rewrites = [];

        // 1. 候选类型与陌生人推荐资格
        const leadingAt = /^@[A-Za-z0-9_]{1,15}\b/.test(text);
        const oonFactor = Number(ev.oonWeightFactor ?? 0.75);
        const replyBaseW = Number(ev.replyWeight ?? 5.0);
        const replyMutualW = replyBaseW + Number(ev.bidirectionalFollowReplyWeightBoost ?? 15.0);
        if (leadingAt) {
            const leadPen = Number(dc.leadingMentionOonFilterPenalty ?? 28);
            score -= leadPen;
            checks.push(
                `开头带 @用户名 会被当作对话回复，无法进入陌生人推荐流`
            );
            rewrites.push(`把开头的 @用户名 移到句中，否则会被当作回复，陌生人刷不到。`);
        } else {
            checks.push(
                `陌生人推荐正常：原创主推可正常进入推荐流，互关好友评论权重提升至 ${(replyMutualW / Math.max(1, replyBaseW)).toFixed(0)} 倍`
            );
        }

        // 2. 48h 候选窗口与连续发帖间隔
        const refEpochMs = hasPlannedTime ? plannedEpochMs : Date.now();
        const ageCutoffMs = refEpochMs - ageWindowHrs * 3600_000;
        const observedTimes = ah?.recentMainPostTimes;
        const activePostCount = Array.isArray(observedTimes)
            ? observedTimes.map(at => Date.parse(at)).filter(ms => ms >= ageCutoffMs && ms <= refEpochMs).length
            : null;
        const multSecond = computeUpstreamDiversityMult(ev, 1);
        const multThird = computeUpstreamDiversityMult(ev, 2);

        let gapMin = Infinity;
        if (ah && ah.latestMainPostAt) {
            const latestMs = new Date(ah.latestMainPostAt).getTime();
            if (Number.isFinite(latestMs)) {
                gapMin = Math.max(0, Math.round(Math.abs(refEpochMs - latestMs) / 60_000));
            }
        }
        const severeGap = gapMin < severeGapMin;
        const mildGap = !severeGap && gapMin < minGapMin;
        const maxDivPen = Number(dc.authorDiversityMaxPenalty ?? 26);
        let effectiveDivMult = 1.0;

        if (severeGap) {
            effectiveDivMult = activePostCount >= 2 ? multThird : multSecond;
            score -= maxDivPen;
            const weightPct = Math.round(effectiveDivMult * 100);
            checks.push(activePostCount === null
                ? `距上一条推文仅 ${gapMin} 分钟，旧报表缺少连续发帖记录，推荐权重估计至多 ${weightPct}%`
                : `距上一条推文仅 ${gapMin} 分钟，按已采集的连续发帖估算推荐权重为 ${weightPct}%`);
            rewrites.push(`距上一条推文仅 ${gapMin} 分钟，建议延后至满 ${minGapHrsLabel} 小时再发，避免连发曝光折损。`);
        } else if (mildGap) {
            const mildPen = Math.max(6, Math.round(maxDivPen * 0.5));
            effectiveDivMult = multSecond;
            score -= mildPen;
            checks.push(
                `距上一条推文 ${(gapMin / 60).toFixed(1)} 小时，略低于建议间隔 ${minGapHrsLabel} 小时`
            );
            rewrites.push(`若非实时热点，建议距上一条推文满 ${minGapHrsLabel} 小时后再发。`);
        } else {
            checks.push(
                `发布间隔充足（距上一条已满 ${minGapHrsLabel} 小时）`
            );
        }

        // 检查与本地定时队列/多条草稿的时间碰撞
        const queuedEpochs = getQueuedScheduledEpochsMs();
        const peerEpochs = hasPlannedTime
            ? [
                  ...queuedEpochs,
                  ...(state.scheduleDraftItems || [])
                      .map((it, i) => (i === skipDraftIdx ? NaN : parseDraftItemEpochMs(it)))
                      .filter((v) => Number.isFinite(v))
              ]
            : queuedEpochs;
        const nearestQueueDiffMin = peerEpochs.reduce((best, qMs) => {
            const diffMin = Math.abs(qMs - refEpochMs) / 60_000;
            return diffMin < best ? diffMin : best;
        }, Infinity);
        const hasQueueConflict = Number.isFinite(nearestQueueDiffMin) && nearestQueueDiffMin < minGapMin;
        if (hasQueueConflict) {
            const qPen = Number(dc.queueCollisionPenalty ?? 12);
            effectiveDivMult = Math.min(effectiveDivMult, multSecond);
            score -= qPen;
            const diffStr = nearestQueueDiffMin < 60
                ? `${Math.round(nearestQueueDiffMin)} 分钟`
                : `${(nearestQueueDiffMin / 60).toFixed(1)} 小时`;
            checks.push(
                `前后 ${diffStr} 内已有定时推文，间隔偏近`
            );
            rewrites.push(`与定时队列中的推文仅隔 ${diffStr}，建议拉开至少 ${minGapHrsLabel} 小时。`);
        }

        // 3. 重复文本与相似度检查
        const cleanText = text.replace(URL_TOKEN_RE, "").trim();
        const simTh = Number(dc.duplicateSimilarityThreshold ?? ev.dppTheta ?? 0.65);
        const dupEval = evaluateCorpusSimilarity(text, skipDraftIdx);
        const simPct = Math.round(dupEval.maxSim * 100);
        if (dupEval.exactMatch) {
            const exactPen = Number(dc.exactDuplicateTextPenalty ?? 35);
            score -= exactPen;
            checks.push(
                `正文与「${escapeHtml(dupEval.sourceLabel)}」完全重复（${simPct}%），容易触发重复刷屏限流`
            );
            rewrites.push(`正文与「${dupEval.sourceLabel}」重复，请换一套表述再发，切勿原样重发。`);
        } else if (dupEval.maxSim >= simTh && Array.from(cleanText).length >= 16) {
            const nearPen = Number(dc.nearDuplicateTextPenalty ?? 18);
            score -= nearPen;
            checks.push(
                `正文与「${escapeHtml(dupEval.sourceLabel)}」相似度较高（${simPct}%）`
            );
            rewrites.push(`与「${dupEval.sourceLabel}」相似度 ${simPct}%，建议拉开切入角度与措辞。`);
        } else {
            checks.push(
                `内容原创度良好（与近期推文最高重合 ${simPct}%）`
            );
        }

        // 4. 外链、短链信誉与 @非粉丝提及检查
        const urls = text.match(URL_TOKEN_RE) || [];
        const { hasExternalLink: hasExtLink, domain: draftDomain } = classifyDraftLinks(urls, hasMedia || Boolean(options.quoteTweetId));
        const isShortenerUrl = Boolean(draftDomain && KNOWN_SHORTENER_DOMAINS.has(draftDomain));
        const rawMentions = text.match(/(?:^|[^\w])@([A-Za-z0-9_]{1,15})/g) || [];
        const mentionHandles = Array.from(
            new Set(rawMentions.map((m) => m.replace(/^[^@]*@/, "")).filter(Boolean))
        );
        const mentionRel = inspectMentionFollowerRelation(mentionHandles);
        const riskyUrlMention = hasExtLink && mentionRel.nonConfirmed.length > 0;

        if (riskyUrlMention) {
            const urlMentionPen = Number(dc.urlWithNonFollowerMentionPenalty ?? 18);
            score -= urlMentionPen;
            const handleTags = mentionRel.nonConfirmed.slice(0, 3).map((h) => `@${escapeHtml(h)}`).join("、");
            checks.push(
                `同时包含外部链接并提及未关注你的人（${handleTags}），易触发垃圾外链风控`
            );
            rewrites.push(`避免在同一条推文里同时放外链并 @未关注你的账号（${mentionRel.nonConfirmed.slice(0, 3).map((h) => "@" + h).join("、")}），可去掉 @ 或把链接放首评。`);
        } else if (isShortenerUrl) {
            const shortPen = 12;
            score -= shortPen;
            checks.push(
                `包含第三方跳转短链 ${escapeHtml(draftDomain)}，容易触发低信誉链接风控`
            );
            rewrites.push(`请将短链 ${draftDomain} 替换为原始完整链接（X 会自动缩短且保留站点信誉）。`);
        } else if (hasExtLink) {
            checks.push(
                `包含普通外链（${escapeHtml(draftDomain || "外链")}），链接本身不扣分，曝光主要取决于点击后的停留阅读`
            );
        } else if (hasExtLink === null) {
            checks.push("链接目标待确认");
        } else {
            checks.push(
                `无外链或异常提及风险`
            );
        }

        // 5. 串推首条完整性检查
        const autoThread = isThreadOpt || /(^|\n)\s*\d+\/\d*\b|🧵|\bThread\b/i.test(text);
        const nonEmptyLines = cleanText.split(/\n+/).map((l) => l.trim()).filter(Boolean);
        const leadLineChars = Array.from(nonEmptyLines[0] || "").length;
        if (autoThread && leadLineChars > 0 && leadLineChars < 16) {
            checks.push(
                `串推首行仅 ${leadLineChars} 字，单次刷新只展示串推里的 1 条，首条过短不利于吸引展开`
            );
            rewrites.push(`串推首条仅 ${leadLineChars} 字，建议在第一条直接亮出核心结论，吸引点击展开。`);
        }

        const cal = ah && ah.calibration ? ah.calibration : null;
        const replyBoostRatio = cal && Number.isFinite(Number(cal.inferredEffectiveReplyWeight))
            ? (Number(cal.inferredEffectiveReplyWeight) / Math.max(1, replyBaseW)).toFixed(1)
            : (replyMutualW / Math.max(1, replyBaseW)).toFixed(1);
        checks.push(
            `本账号互动参考：评论权重加成约 ×${replyBoostRatio}${hasMedia ? ` · 配图或满 ${Math.round(Number(ev.minVideoDurationMs || 10000) / 1000)} 秒视频可增加停留` : ""}`
        );

        // 6. 本账号同域名与外链历史表现参考（仅在确实相关时给出简短提示）
        if (hasExtLink && draftDomain && cal && Array.isArray(cal.domainStats)) {
            const matchedDom = cal.domainStats.find((d) => d && d.domain === draftDomain);
            if (matchedDom) {
                const sign = matchedDom.vsNativePct > 0 ? `+${matchedDom.vsNativePct}%` : `${matchedDom.vsNativePct}%`;
                sampleNotes.push(
                    `过往发过 ${matchedDom.postCount} 条 ${escapeHtml(matchedDom.domain)} 链接，平均 ${fmtNum(matchedDom.avgViews)} 浏览（较无外链推文 ${sign}）`
                );
            }
        }
        const linkLossNoticePct = Number(cr.linkLossNoticePct ?? 15);
        const effLinkLossPct = ah ? Number(ah.linkViewLossPct ?? ah.linkLossPct) : NaN;
        if (hasExtLink && ah && ah.linkComparisonAvailable && effLinkLossPct >= linkLossNoticePct) {
            const extAvg = ah.externalLinkAvgViews ?? ah.extLinkAvgViews;
            const natAvg = ah.nativePostAvgViews ?? ah.nativeAvgViews;
            sampleNotes.push(
                `近期带外链推文均浏览偏低 ${effLinkLossPct}%（${fmtNum(extAvg)} vs ${fmtNum(natAvg)}），可尝试把链接放在首条自评`
            );
        }
        // 7. 基于本账号历史校准样本的核回归内容分位与触达区间预判
        const structuredList = nonEmptyLines.length >= 3 || /(^|\n)\s*(?:[-•*✅💡⚠️🔹▶️]|\d+[\.、\)）])\s+/m.test(text);
        const hasQuestion = /[?？]/.test(text);
        const draftFormatCode = autoThread ? "THREAD" : (hasMedia ? "MEDIA" : "TEXT");
        const pipelineReachMult = leadingAt ? 0.25 : effectiveDivMult;
        const followersCount = state.report && state.report.profile ? Number(state.report.profile.followersCount) : NaN;
        const empiricalPrediction = predictDraftFromCorpus(
            cal,
            {
                formatCode: draftFormatCode,
                cleanCharLen: Array.from(cleanText).length,
                structuredList,
                hasQuestion,
                hasExternalLink: hasExtLink,
                externalDomain: draftDomain,
                shingles: buildCalibrationShingleArray(cleanText)
            },
            pipelineReachMult,
            followersCount
        );

        const oonDimScore = leadingAt ? 20 : 100;
        const oonDimVal = leadingAt ? "开头@受限" : "正常";
        const divDimScore = Math.max(20, Math.round(effectiveDivMult * 100) - (hasQueueConflict ? 12 : 0));
        const divDimVal = hasQueueConflict
            ? "排期冲突"
            : severeGap
                ? "间隔过近"
                : mildGap
                    ? "间隔偏近"
                    : "充足";
        const dedupDimScore = dupEval.exactMatch
            ? 15
            : (dupEval.maxSim >= simTh && Array.from(cleanText).length >= 16 ? 55 : 100);
        const dedupDimVal = dupEval.exactMatch
            ? "内容重复"
            : (dupEval.maxSim >= simTh && Array.from(cleanText).length >= 16 ? `相似 ${simPct}%` : "原创");
        const safetyDimScore = riskyUrlMention ? 56 : (isShortenerUrl ? 68 : 100);
        const safetyDimVal = riskyUrlMention ? "外链+@非粉" : (isShortenerUrl ? "短链风险"
            : (hasExtLink === null ? "链接目标待确认" : hasExtLink ? "普通外链" : "无风险"));

        const dimList = [
            { key: "oon", label: "陌生人推荐", score: oonDimScore, valLabel: oonDimVal },
            { key: "diversity", label: "发布间隔", score: divDimScore, valLabel: divDimVal },
            { key: "dedup", label: "内容原创度", score: dedupDimScore, valLabel: dedupDimVal },
            { key: "safety", label: "外链与提及", score: safetyDimScore, valLabel: safetyDimVal }
        ];

        const minScore = Number(dc.minScore ?? 15);
        const maxScore = Number(dc.maxScore ?? 100);
        const finalScore = Math.max(minScore, Math.min(maxScore, Math.round(score)));
        const excTh = Number(dc.excellentThreshold ?? 88);
        const goodTh = Number(dc.goodThreshold ?? 70);
        const gradeLabel = finalScore >= excTh ? "可直接发" : finalScore >= goodTh ? "建议微调" : "存在限流风险";
        const scoreColor = finalScore >= excTh ? "var(--x-green)" : finalScore >= goodTh ? "var(--x-blue)" : "var(--x-amber)";
        const tone = finalScore >= excTh ? "green" : finalScore >= goodTh ? "blue" : "amber";

        const summaryRiskTag = leadingAt
            ? "开头@受限"
            : dupEval.exactMatch
                ? "内容重复"
                : (dupEval.maxSim >= simTh && Array.from(cleanText).length >= 16)
                    ? "与旧帖相似"
                    : riskyUrlMention
                        ? "外链+@非粉"
                        : isShortenerUrl
                            ? "短链风险"
                            : hasQueueConflict
                                ? "排期过近"
                                : severeGap
                                    ? "连发过近"
                                    : "";

        return {
            text,
            finalScore,
            gradeLabel,
            scoreColor,
            tone,
            algoVersion: `${profile.version || DEFAULT_ALGO_PROFILE.version} · ${ev.upstreamCommit || "590f3926e5bc"}`,
            checks,
            sampleNotes,
            empiricalPrediction,
            rewrites: rewrites.slice(0, 4),
            dimensions: dimList,
            summaryRiskTag,
            hasExtLink
        };
    }

    function loadDraftIntoSimulator(text, opts = {}) {
        const inputEl = $("draftSimulatorInput");
        if (!inputEl) return;
        const prevRaw = String(inputEl.value || "");
        const nextRaw = String(text || "");
        if (prevRaw.trim() && prevRaw.trim() !== nextRaw.trim()) {
            state.draftUndoSnapshot = {
                text: prevRaw,
                hasMedia: Boolean($("draftHasMediaCheck")?.checked),
                isThread: Boolean($("draftIsThreadCheck")?.checked)
            };
            const undoBar = document.getElementById("draftUndoBar");
            if (undoBar) undoBar.hidden = false;
        }
        switchTab("timing");
        inputEl.value = nextRaw;
        const mediaChk = $("draftHasMediaCheck");
        if (mediaChk && typeof opts.hasMedia === "boolean") {
            mediaChk.checked = opts.hasMedia;
        }
        const threadChk = $("draftIsThreadCheck");
        if (threadChk && typeof opts.isThread === "boolean") {
            threadChk.checked = opts.isThread;
        }
        evaluatePrePostDraft();
        const card = $("prePostSimulatorCard");
        if (card) {
            card.hidden = false;
            card.scrollIntoView({ behavior: "smooth", block: "center" });
        }
        inputEl.focus();
    }

    function evaluatePrePostDraft() {
        const inputEl = $("draftSimulatorInput");
        const outEl = $("draftSimulatorResult");
        const livePill = document.getElementById("draftLiveScorePill");
        if (!inputEl || !outEl) return;

        const text = (inputEl.value || "").trim();
        if (!text) {
            outEl.hidden = true;
            if (livePill) {
                livePill.hidden = true;
                livePill.textContent = "";
            }
            return;
        }

        const hasMedia = Boolean($("draftHasMediaCheck") && $("draftHasMediaCheck").checked);
        const isThread = Boolean($("draftIsThreadCheck") && $("draftIsThreadCheck").checked);
        const res = analyzeDraftByXAlgo(text, { hasMedia, isThread });
        if (livePill) {
            livePill.textContent = `${res.finalScore} 分 · ${res.gradeLabel}`;
            livePill.className = `draft-live-score-pill tone-${res.tone || "blue"}`;
            livePill.hidden = false;
        }

        const dimsHtml = res.dimensions
            .map((d) => {
                const chipCls = d.score >= 80 ? "is-good" : d.score >= 55 ? "is-mid" : "is-warn";
                return `<div class="draft-dim-chip ${chipCls}">
                    <span class="draft-dim-name">${escapeHtml(d.label)}</span>
                    <span class="draft-dim-val">${escapeHtml(d.valLabel || String(d.score))}</span>
                </div>`;
            })
            .join("");

        const rewritesHtml = res.rewrites.length
            ? `<div class="draft-ops-advice-box">
                    <ul class="draft-ops-rewrite-list">
                        ${res.rewrites.map((tip) => `<li>${escapeHtml(tip)}</li>`).join("")}
                    </ul>
                </div>`
            : "";

        const sampleRefHtml = Array.isArray(res.sampleNotes) && res.sampleNotes.length
            ? `<div class="draft-sample-ref-inline">
                    ${res.sampleNotes.slice(0, 2).map((n) => `<div class="draft-context-note">${n}</div>`).join("")}
                </div>`
            : "";

        let inlineForecastHtml = "";
        let nnBlockHtml = "";
        const ep = res.empiricalPrediction;
        if (ep && ep.unlocked) {
            const discountHint = ep.pipelineDiscounted ? ` <span class="draft-forecast-discount">（改完问题后可更高）</span>` : "";
            inlineForecastHtml = `<span class="draft-inline-forecast">预计 <strong>${fmtNum(ep.expViewsLow)}–${fmtNum(ep.expViewsHigh)}</strong> 浏览${discountHint}</span>`;
            if (Array.isArray(ep.topNeighbors) && ep.topNeighbors.length) {
                nnBlockHtml = `
                    <div class="draft-nn-block">
                        <div class="draft-nn-head">相似旧帖</div>
                        <div class="draft-nn-rows">
                            ${ep.topNeighbors.map((nn) => `
                                <a class="draft-nn-row" href="${escapeHtml(safeHttpUrl(nn.tweetUrl, "https://x.com"))}" target="_blank" rel="noopener noreferrer">
                                    <span class="draft-nn-date">${escapeHtml(nn.createdLabel)}</span>
                                    <span class="draft-nn-title" data-i18n-ignore>${escapeHtml(nn.textPreview)} ↗</span>
                                    <strong class="draft-nn-views">${fmtNum(nn.viewCount)} 浏览</strong>
                                </a>
                            `).join("")}
                        </div>
                    </div>
                `;
            }
        } else if (ep) {
            inlineForecastHtml = `<span class="draft-inline-forecast is-muted">满 ${ep.minRequired} 条主推解锁浏览预估（${ep.sampleCount}/${ep.minRequired}）</span>`;
        }

        outEl.hidden = false;
        outEl.innerHTML = `
            <div class="draft-score-head">
                <div class="draft-score-title-group">
                    <span class="draft-score-num" style="color:${res.scoreColor}">${res.finalScore} 分</span>
                    <span class="mini-tag ${res.tone}">${res.gradeLabel}</span>
                    ${inlineForecastHtml}
                </div>
                <div class="draft-score-actions">
                    <button type="button" class="x-outline-pill sm" id="clearDraftSimBtn">清空</button>
                    <button type="button" class="x-outline-pill sm" id="sendDraftToScheduleBtn"${isOwnerAllPermissions() ? "" : " hidden"}>转入定时</button>
                    <a class="x-blue-pill sm" href="https://x.com/intent/tweet?text=${encodeURIComponent(res.text)}" target="_blank" rel="noopener noreferrer">
                        发推 ↗
                    </a>
                </div>
            </div>
            <div class="draft-algo-dims">${dimsHtml}</div>
            ${rewritesHtml}
            ${sampleRefHtml}
            ${nnBlockHtml}
        `;

        const clrBtn = $("clearDraftSimBtn");
        if (clrBtn) {
            clrBtn.addEventListener("click", () => {
                inputEl.value = "";
                outEl.hidden = true;
                if (livePill) {
                    livePill.hidden = true;
                    livePill.textContent = "";
                }
                const undoBar = document.getElementById("draftUndoBar");
                if (undoBar) undoBar.hidden = true;
                state.draftUndoSnapshot = null;
            });
        }
        const toSchedBtn = $("sendDraftToScheduleBtn");
        if (toSchedBtn) {
            toSchedBtn.addEventListener("click", () => {
                if (sendTextDraftToSchedule(res.text) !== false) {
                    showToast("已将草稿填入定时发布");
                }
            });
        }
    }

    function renderTopTweetsList(report) {
        const p = report.profile;
        const timing = report.contentTiming;
        const format = state.topPostFormat;
        const sort = state.topPostSortBy;
        const limit = 24;
        const candidates = representativePostCards(timing);
        const indices = timing.postRankings?.byFormat?.[format]?.[sort];
        const stats = (timing.formatStats || []).filter(f => format === "ALL" || f.formatCode === format);
        const observedCount = stats.reduce((sum, f) => sum + Number(f.postCount || 0), 0);
        const hasViewReadings = !stats.length || stats.some(f => f.viewSampleCount !== 0);
        let topPosts;
        let limitedCache = false;
        if (Array.isArray(indices)) {
            topPosts = indices.slice(0, limit).map(i => candidates[i]).filter(Boolean);
        } else {
            const available = candidates.filter(t => format === "ALL" ||
                format === (t.isThread ? "THREAD" : t.hasMedia ? "MEDIA" : "TEXT"));
            topPosts = legacyRepresentativePosts(available, sort).slice(0, limit);
            const needed = sort === "composite" ? Math.min(limit, observedCount) : observedCount;
            limitedCache = stats.length ? available.length < needed : candidates.length >= limit && (format !== "ALL" || sort !== "composite");
        }
        const notice = [];
        if (observedCount > 0 && !hasViewReadings) notice.push("浏览量尚未取得");
        if (limitedCache) notice.push("当前缓存仅含部分代表推文");

        const cardsHtml = topPosts.length
            ? topPosts
                  .map((t) => {
                      const engagementKnown = postMetricsKnown(t, ["likes", "replies", "reposts", "bookmarks"]);
                      const erKnown = engagementKnown && postMetricsKnown(t, ["views"]);
                      const rateKnown = erKnown && Number(t.viewCount) > 0;
                      const metric = (field, value) => postMetricsKnown(t, [field]) ? fmtNum(value) : "--";
                      const tagBadge = t.isThread
                          ? `<span class="mini-tag blue">长推</span>`
                          : t.hasMedia
                              ? `<span class="mini-tag green">图文/视频</span>`
                              : t.kind === "QUOTE"
                                  ? `<span class="mini-tag amber">引用</span>`
                                  : "";
                      const bmBadge =
                          t.bookmarkCount >= 2 && postMetricsKnown(t, ["bookmarks", "views"]) && t.viewCount > 0 && Number(t.bookmarkRatePct || 0) >= 0.4
                              ? `<span class="mini-tag amber" title="收藏占浏览比例">收藏 ${t.bookmarkRatePct || 0}%</span>`
                              : "";
                      const cleanSignals = (t.algoSignals || []).filter(
                          (sig) => sig && !/其他浏览量样本|缺失指标/.test(sig)
                      );
                      const signalsHtml = cleanSignals.length
                          ? `<div class="tweet-algo-signals">
                                ${cleanSignals.map((sig) => `<span class="algo-sig-chip">${escapeHtml(sig)}</span>`).join("")}
                             </div>`
                          : "";
                      return `<article class="x-tweet-card">
                          <div class="tweet-top-meta">
                              <div class="tweet-meta-left">
                                  <span>${escapeHtml(t.createdLabel)}</span>
                                  ${tagBadge}
                              </div>
                              <div class="tweet-meta-right">
                                  ${bmBadge}
                                  <span class="mini-tag pink">${rateKnown ? `互动率 ${t.engagementRatePct}%` : "--"}</span>
                              </div>
                          </div>
                          <div class="tweet-text" data-i18n-ignore>${escapeHtml(t.text)}</div>
                          ${signalsHtml}
                          <div class="tweet-metrics-bar">
                              <span class="tweet-metric-item" title="评论数">${iconSvg("reply")} ${metric("replies", t.replyCount)}</span>
                              <span class="tweet-metric-item" title="转推与引用">${iconSvg("retweet")} ${metric("reposts", t.retweetCount)}</span>
                              <span class="tweet-metric-item" title="点赞数">${iconSvg("like")} ${metric("likes", t.likeCount)}</span>
                              <span class="tweet-metric-item" title="书签收藏">${iconSvg("bookmark")} ${metric("bookmarks", t.bookmarkCount)}</span>
                              <span class="tweet-metric-item" title="浏览量">${iconSvg("views")} ${metric("views", t.viewCount)}</span>
                              <button type="button" class="tweet-draft-check-btn" data-top-post-id="${escapeHtml(t.id)}" title="放入草稿体检">体检</button>
                              <a class="tweet-orig-link" href="${escapeHtml(safeHttpUrl(t.tweetUrl, `https://x.com/${encodeURIComponent(p.username)}`))}" target="_blank" rel="noopener noreferrer">原推 ↗</a>
                          </div>
                      </article>`;
                  })
                  .join("")
            : "";
        const messages = notice.map(message => `<div class="format-meta">${escapeHtml(tr(message))}</div>`).join("");
        const emptyMessage = timing.timelineWindowComplete === true ? "当前类型暂无推文" : "已采集样本中暂无该体裁的主帖";
        $("topTweetsList").innerHTML = messages + (cardsHtml || (notice.length ? "" : emptyStateHtml(emptyMessage, widerPeriodActions())));
        $("topTweetsList").querySelectorAll(".tweet-text").forEach((el) => {
            el.addEventListener("click", () => el.classList.toggle("is-expanded"));
        });
    }

    function representativePostCards(timing) {
        return [...(timing?.topPosts || []), ...(timing?.postRankings?.additionalPosts || [])];
    }

    function legacyRepresentativePosts(posts, sort) {
        const engagementFields = ["likes", "replies", "reposts", "bookmarks"];
        const metrics = {
            views: ["viewCount", ["views"]], replies: ["replyCount", ["replies"]],
            bookmarks: ["bookmarkCount", ["bookmarks"]], engagements: ["totalEngagements", engagementFields],
            er: ["engagementRatePct", ["views", ...engagementFields]],
            algoScore: ["algoScore", ["views", ...engagementFields]], bookmarkRate: ["bookmarkRatePct", ["views", "bookmarks"]]
        };
        if (!metrics[sort]) return posts;
        function score(post, name) {
            const [field, required] = metrics[name];
            if (!postMetricsKnown(post, required) || post[field] == null || !Number.isFinite(Number(post[field]))) return -Infinity;
            if ((name === "er" || name === "bookmarkRate") && !(Number(post.viewCount) > 0)) return -Infinity;
            if (name === "algoScore" && Number(post[field]) < 0) return -Infinity;
            return Number(post[field]);
        }
        const secondary = { views: "engagements", replies: "engagements", bookmarks: "bookmarkRate" }[sort] || "views";
        return posts.sort((a, b) => {
            const left = score(a, sort), right = score(b, sort);
            if (left !== right) return right - left;
            if (left === -Infinity) return 0;
            const leftTie = score(a, secondary), rightTie = score(b, secondary);
            return leftTie === rightTie ? 0 : rightTie - leftTie;
        });
    }

    /* ==================== 模块 5：核心铁粉圈层 & Canvas 高清晒单战报卡 ==================== */
    function getEffectiveShareTweetText(report) {
        const sc = report.innerCircle.shareCard;
        const uname = String(report.profile?.username || "").replace(/^@+/, "");
        const authorId = String(state.accessPolicy?.authorAccountId || AUTHOR_ARCHIVE_ACCOUNT_ID);
        const viaSuffix = uname && String(report.profile?.id || "") !== authorId
            ? ` via https://x.com/i/user/${encodeURIComponent(authorId)}` : "";
        let raw = (sc.shareTweetText || "").replace(/平均互动率/g, "平均公开互动率");
        if (!hasMetricSample(sc, "engagementRateSampleCount")) {
            raw = raw.replace(/平均公开互动率\s*[\d.]+%/g, "平均公开互动率 --");
        }
        if (sc.metricsNote && !raw.includes(sc.metricsNote)) raw += `\n${sc.metricsNote}`;
        const followRate = followRateDisplay(report);
        if (state.shareHideFollowRate || report.followAudit) {
            raw = raw.replace(
                /(?:🤝\s*)?(?:关注|样本)回关率\s*(?:--|[\d.]+%(?:–[\d.]+%)?)(?:[ \t]*[（(][^）)\r\n]*[）)])?/g,
                state.shareHideFollowRate
                    ? `📈 周期总浏览 ${hasMetricSample(report.activity, "viewSampleCount") ? fmtNum(report.activity.totalViews) : "--"}`
                    : `🤝 ${followRate.detail}`
            );
        }
        if (uname && /我的\s*X\s*社区运营/.test(raw)) {
            raw = raw.replace(
                /^📊\s*我的\s*X\s*社区运营([^\n（(]+)[（(]@?([^｜|\n）)]+)[｜|]([^）)\n]+)[）)]/m,
                `📊 @${uname} $1（$3）${viaSuffix}`
            );
        }
        let translated = globalThis.XOpsI18n?.translate(raw) ?? raw;
        if (typeof computeWeightedTweetLength === "function" && computeWeightedTweetLength(translated) > 255) {
            const act = report.activity || {};
            const periodLabel = sc.periodLabel || PERIOD_META[report.periodCode]?.badgePrefix || "24h";
            const erText = hasMetricSample(sc, "engagementRateSampleCount")
                ? `${Number(sc.avgEngagementRatePct || 0).toFixed(2)}%`
                : "--";
            const metricRight = state.shareHideFollowRate
                ? `📈 浏览 ${hasMetricSample(act, "viewSampleCount") ? fmtNum(act.totalViews) : "--"}`
                : `🤝 ${followRate.label} ${followRate.rate}`;
            const scoreStr = Number(sc.activityHealthScore) >= 0 ? `${sc.activityHealthScore}/100` : "--";
            const posts = sc.originalPosts ?? (Number.isFinite(act.originalPosts) && Number.isFinite(act.quotes)
                ? act.originalPosts + act.quotes : "--");
            const compactZh = [
                `📊 @${uname || "me"} ${periodLabel}${viaSuffix}`,
                `✍️ 发帖 ${posts} ｜ 💬 回复 ${sc.replies ?? act.replies ?? "--"}（${Number(sc.replyToPostRatio || 0).toFixed(1)}x）`,
                `⚡ 互动率 ${erText} ｜ ${metricRight}`,
                `🏆 ${sc.personaTag || "活跃运营"}（${scoreStr}）`,
                "#BuildInPublic #X运营"
            ].join("\n");
            translated = globalThis.XOpsI18n?.translate(compactZh) ?? compactZh;
        }
        return translated;
    }

    function ensureCanvasAvatarLoaded(avatarUrl) {
        const canonical = safeAvatarSrc(avatarUrl);
        if (!canonical) return null;
        const cached = state.avatarImgCache[canonical];
        if (cached === "ERROR") return null;
        if (cached && cached.complete && cached.naturalWidth > 0) {
            return cached;
        }
        if (!cached) {
            const img = new Image();
            img.crossOrigin = "anonymous";
            img.referrerPolicy = "no-referrer";
            state.avatarImgCache[canonical] = img;
            img.onload = () => {
                if (state.report) {
                    drawShareCardCanvas(state.report);
                }
            };
            img.onerror = () => {
                state.avatarImgCache[canonical] = "ERROR";
            };
            const blobHit = mediaObjectUrlMap.get(canonical);
            if (blobHit) {
                img.src = blobHit;
            } else {
                warmMediaAsset(canonical)
                    .then((warmedBlob) => {
                        img.src = warmedBlob || canonical;
                    })
                    .catch(() => {
                        img.src = canonical;
                    });
            }
        }
        return null;
    }

    function syncSharePrivacyBtnUI(report) {
        const btn = $("toggleSharePrivacyBtn");
        if (btn) {
            btn.classList.toggle("active", state.shareHideFollowRate);
            btn.textContent = state.shareHideFollowRate ? "显示回关率" : "隐藏回关率";
        }
        if (report) {
            $("postToXIntentLink").href = `https://x.com/intent/tweet?text=${encodeURIComponent(getEffectiveShareTweetText(report))}`;
        }
    }

    function renderCirclePanel(report) {
        const ic = report.innerCircle;
        const sc = ic.shareCard;
        $("shareCardSectionTitle").textContent = sc.reportTitle || "运营战报卡";
        syncSharePrivacyBtnUI(report);

        renderCircleGrid("coreFansGrid", ic.coreFans, "暂无常评铁粉");
        renderCircleGrid("activeMutualsGrid", ic.activeMutuals, "暂无双向互评伙伴");
        renderCircleGrid("needWarmupPeersGrid", ic.needWarmupPeers, "暂无待回访推友");

        drawShareCardCanvas(report);
    }

    function renderCircleGrid(containerId, members, emptyMsg) {
        const el = $(containerId);
        if (!members || members.length === 0) {
            el.innerHTML = emptyStateHtml(emptyMsg, widerPeriodActions());
            return;
        }
        const myUsername = state.report && state.report.profile ? state.report.profile.username : "";
        el.innerHTML = members
            .map(
                (m) => `<div class="circle-card">
                ${avatarHtml(m.user, "md")}
                <div class="circle-card-body">
                    <div class="user-name-wrap">
                        <a data-i18n-ignore href="https://x.com/${encodeURIComponent(m.user.username)}" target="_blank" rel="noopener noreferrer" style="color:var(--text-primary)">
                            ${escapeHtml(m.user.displayName)}
                        </a>
                        ${verifiedSvgHtml(m.user.verified)}
                    </div>
                    <div class="user-handle-muted"><span data-i18n-ignore>@${escapeHtml(m.user.username)}</span>${m.mutualFollow ? " · 互关" : ""}</div>
                    <div class="circle-reason">${escapeHtml(String(m.highlightReason || "").replace(/（回礼率\s*TA来评\s*\d+\s*帖）/g, "（待回礼）"))}</div>
                    <div class="circle-card-actions">
                        <a class="x-outline-pill sm" href="${escapeHtml(peerLatestPostsUrl(m.user.username))}" target="_blank" rel="noopener noreferrer" title="直达 TA 最新原创主推去评论">去回评 ↗</a>
                        ${myUsername ? `<a class="x-outline-pill sm" href="${escapeHtml(peerDialogueUrl(myUsername, m.user.username))}" target="_blank" rel="noopener noreferrer" title="查看双方历史互动对话">对话 ↗</a>` : ""}
                    </div>
                </div>
            </div>`
            )
            .join("");
    }

    function drawShareCardCanvas(report, forceFallbackAvatar = false) {
        const tr = (text) => globalThis.XOpsI18n?.translate(text) ?? text;
        const canvas = $("shareCardCanvas");
        if (!canvas || !canvas.getContext) return;
        const scale = 2;
        const W = 1080;
        const H = 600;
        if (canvas.width !== W * scale || canvas.height !== H * scale) {
            canvas.width = W * scale;
            canvas.height = H * scale;
        }
        const themeId = (THEMES[state.themeIdx] && THEMES[state.themeIdx].id) || "lights-out";
        const pal =
            themeId === "light"
                ? {
                      bg0: "#ffffff",
                      bgMid: "#f2f8fd",
                      bg1: "#f7f9f9",
                      glow: "rgba(29, 155, 240, 0.14)",
                      border: "#dbe3ea",
                      cardBg: "#ffffff",
                      footerBg: "#f7f9f9",
                      textPrimary: "#0f1419",
                      textMuted: "#536471",
                      textSub: "#536471",
                      pillBg: "rgba(29, 155, 240, 0.12)",
                      blue: "#1a8cd8",
                      green: "#009965",
                      pink: "#e01573",
                      amber: "#b26a00"
                  }
                : themeId === "dim"
                  ? {
                        bg0: "#15202b",
                        bgMid: "#1c2b3a",
                        bg1: "#121b24",
                        glow: "rgba(29, 155, 240, 0.24)",
                        border: "#38444d",
                        cardBg: "#1e2732",
                        footerBg: "#19232d",
                        textPrimary: "#f7f9f9",
                        textMuted: "#8b98a5",
                        textSub: "#9aa8b6",
                        pillBg: "rgba(29, 155, 240, 0.2)",
                        blue: "#1d9bf0",
                        green: "#00ba7c",
                        pink: "#f91880",
                        amber: "#ffd400"
                    }
                  : {
                        bg0: "#05070a",
                        bgMid: "#0d1520",
                        bg1: "#06080c",
                        glow: "rgba(29, 155, 240, 0.26)",
                        border: "#2f3336",
                        cardBg: "#16181c",
                        footerBg: "#111418",
                        textPrimary: "#e7e9ea",
                        textMuted: "#71767b",
                        textSub: "#8b98a5",
                        pillBg: "rgba(29, 155, 240, 0.18)",
                        blue: "#1d9bf0",
                        green: "#00ba7c",
                        pink: "#f91880",
                        amber: "#ffd400"
                    };

        const ctx = canvas.getContext("2d");
        ctx.save();
        ctx.scale(scale, scale);

        const bgGrad = ctx.createLinearGradient(0, 0, W, H);
        bgGrad.addColorStop(0, pal.bg0);
        bgGrad.addColorStop(0.55, pal.bgMid);
        bgGrad.addColorStop(1, pal.bg1);
        ctx.fillStyle = bgGrad;
        ctx.fillRect(0, 0, W, H);

        const glow = ctx.createRadialGradient(W * 0.82, 90, 20, W * 0.82, 90, 340);
        glow.addColorStop(0, pal.glow);
        glow.addColorStop(1, "rgba(29, 155, 240, 0)");
        ctx.fillStyle = glow;
        ctx.fillRect(0, 0, W, H);

        ctx.strokeStyle = pal.border;
        ctx.lineWidth = 2;
        roundRect(ctx, 24, 24, W - 48, H - 48, 28, false, true);

        const p = report.profile;
        const sc = report.innerCircle.shareCard;

        const rightTitleText = `𝕏  ${tr(sc.reportTitle || "社区运营战报")}`;
        ctx.fillStyle = pal.textPrimary;
        ctx.textAlign = "right";
        ctx.font = "800 26px -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif";
        const rightTitleW = Math.min(ctx.measureText(rightTitleText).width, W - 420);
        ctx.fillText(rightTitleText, W - 60, 86, W - 420);
        ctx.textAlign = "left";

        const loadedAvatar = !forceFallbackAvatar ? ensureCanvasAvatarLoaded(p.avatarUrl) : null;
        if (loadedAvatar) {
            ctx.save();
            ctx.beginPath();
            ctx.arc(96, 98, 42, 0, Math.PI * 2);
            ctx.closePath();
            ctx.clip();
            ctx.drawImage(loadedAvatar, 54, 56, 84, 84);
            ctx.restore();
            ctx.strokeStyle = "rgba(29, 155, 240, 0.65)";
            ctx.lineWidth = 2.5;
            ctx.beginPath();
            ctx.arc(96, 98, 43, 0, Math.PI * 2);
            ctx.stroke();
        } else {
            const hue = Math.abs(hashStr(p.username || "X")) % 360;
            const avGrad = ctx.createLinearGradient(54, 56, 138, 140);
            avGrad.addColorStop(0, `hsl(${hue}, 76%, 48%)`);
            avGrad.addColorStop(1, `hsl(${(hue + 48) % 360}, 74%, 38%)`);
            ctx.fillStyle = avGrad;
            ctx.beginPath();
            ctx.arc(96, 98, 42, 0, Math.PI * 2);
            ctx.fill();
            ctx.fillStyle = "#ffffff";
            ctx.font = "800 34px -apple-system, BlinkMacSystemFont, sans-serif";
            ctx.textAlign = "center";
            ctx.fillText(firstCodePointUpper(p.displayName || p.username), 96, 110);
            ctx.textAlign = "left";
        }

        const maxNameW = Math.max(220, W - 60 - rightTitleW - 182);
        ctx.fillStyle = pal.textPrimary;
        ctx.font = "800 32px -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif";
        ctx.fillText(truncateText(p.displayName || p.username, 18), 158, 92, maxNameW);

        ctx.fillStyle = pal.textMuted;
        ctx.font = "600 20px -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif";
        ctx.fillText(`@${p.username}  ·  ${tr(sc.periodLabel)} (${tr(report.activity.windowRangeText)})`, 158, 126, W - 218);

        const personaText = tr(`🏆 ${sc.personaTag} · 健康分 ${sc.activityHealthScore < 0 ? "--" : `${sc.activityHealthScore}/100`}`);
        ctx.font = "700 20px -apple-system, 'PingFang SC', sans-serif";
        const pillW = Math.min(W - 120, Math.max(360, Math.ceil(ctx.measureText(personaText).width + 46)));
        ctx.fillStyle = pal.pillBg;
        roundRect(ctx, 60, 162, pillW, 44, 22, true, false);
        ctx.fillStyle = pal.blue;
        ctx.fillText(personaText, 82, 191, pillW - 44);

        const followRate = followRateDisplay(report);
        const fourthCard = state.shareHideFollowRate
            ? {
                  label: "周期总浏览",
                  val: hasMetricSample(report.activity, "viewSampleCount") ? fmtNum(report.activity.totalViews) : "--",
                  sub: `原创与互动累计浏览${metricSampleLabel(report.activity, "viewSampleCount")}`,
                  color: pal.green
              }
            : {
                  label: followRate.label,
                  val: followRate.rate,
                  sub: followRate.scope,
                  color: pal.green
              };

        const origPosts = report.activity ? report.activity.originalPosts : sc.originalPosts;
        const quotePosts = report.activity ? Number(report.activity.quotes || 0) : 0;
        const cards = [
            {
                label: "原创发帖",
                val: `${origPosts}`,
                sub: quotePosts > 0 ? `另含 ${quotePosts} 条引用` : `${sc.periodLabel}内容输出`,
                color: pal.textPrimary
            },
            { label: "回复", val: `${sc.replies}`, sub: `回复/发帖比 ${sc.replyToPostRatio}x`, color: pal.blue },
            { label: "平均公开互动率", val: hasMetricSample(sc, "engagementRateSampleCount") ? `${sc.avgEngagementRatePct}%` : "--", sub: `公开赞评转藏 ÷ 浏览${metricSampleLabel(sc, "engagementRateSampleCount")}`, color: pal.pink },
            fourthCard
        ];

        const boxW = 224;
        const boxH = 165;
        const startX = 60;
        const gap = 21;
        const textMaxW = boxW - 40;
        cards.forEach((c, idx) => {
            const x = startX + idx * (boxW + gap);
            const y = 232;
            ctx.fillStyle = pal.cardBg;
            ctx.strokeStyle = pal.border;
            ctx.lineWidth = 1.5;
            roundRect(ctx, x, y, boxW, boxH, 20, true, true);

            ctx.fillStyle = pal.textMuted;
            ctx.font = "600 18px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(tr(c.label), x + 22, y + 40, textMaxW);

            ctx.fillStyle = c.color;
            ctx.font = "800 40px -apple-system, BlinkMacSystemFont, sans-serif";
            ctx.fillText(tr(c.val), x + 22, y + 104, textMaxW);

            ctx.fillStyle = pal.textSub;
            ctx.font = "500 15px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(tr(c.sub), x + 22, y + 140, textMaxW);
        });

        if (report.activity.coverageNote) {
            ctx.fillStyle = pal.textMuted;
            ctx.font = "500 13px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(tr(report.activity.coverageNote), 60, 412, W - 120);
        }
        if (sc.metricsNote) {
            ctx.fillStyle = pal.textMuted;
            ctx.font = "500 12px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(tr(sc.metricsNote), 60, 426, W - 120);
        }

        ctx.fillStyle = pal.footerBg;
        ctx.strokeStyle = pal.border;
        roundRect(ctx, 60, 430, W - 120, 110, 20, true, true);

        ctx.fillStyle = pal.amber;
        ctx.font = "700 20px -apple-system, 'PingFang SC', sans-serif";
        ctx.fillText(tr(`💎 ${sc.periodLabel}最佳双向奔赴推友伙伴`), 86, 470, W - 172);

        const buddies =
            sc.topBuddyHandles && sc.topBuddyHandles.length
                ? sc.topBuddyHandles.join("    ")
                : tr("期待与更多同频推友建立深度互动连接");
        ctx.fillStyle = pal.textPrimary;
        ctx.font = "700 23px -apple-system, BlinkMacSystemFont, sans-serif";
        ctx.fillText(truncateText(buddies, 40), 86, 512, W - 290);

        ctx.fillStyle = pal.textMuted;
        ctx.font = "500 15px -apple-system, sans-serif";
        ctx.textAlign = "right";
        ctx.fillText("X Community Ops Hub", W - 86, 512);
        ctx.textAlign = "left";
        ctx.restore();
    }

    function roundRect(ctx, x, y, w, h, r, fill, stroke) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.arcTo(x + w, y, x + w, y + h, r);
        ctx.arcTo(x + w, y + h, x, y + h, r);
        ctx.arcTo(x, y + h, x, y, r);
        ctx.arcTo(x, y, x + w, y, r);
        ctx.closePath();
        if (fill) ctx.fill();
        if (stroke) ctx.stroke();
    }

    function truncateText(str, maxLen) {
        if (!str) return "";
        const chars = Array.from(String(str));
        return chars.length > maxLen ? chars.slice(0, maxLen - 1).join("") + "…" : chars.join("");
    }

    /* ==================== 右侧运营行动清单与周期漏斗 ==================== */
    function renderRightSidebar(report) {
        const act = report.activity;
        const inter = report.interactions;
        const audit = report.followAudit;
        const ic = report.innerCircle;
        const ct = report.contentTiming;
        const ah = report.algorithmHealth;

        if (ah) {
            const levelKey = (ah.healthLevel || "GOOD").toLowerCase();
            const shortStatusTitle =
                levelKey === "alert"
                    ? "近期浏览偏低"
                    : levelKey === "warn"
                        ? (ah.rapidFirePostCount > 0 ? `${ah.rapidFirePostCount} 条推文连发过密` : `带外链浏览偏低 ${ah.linkViewLossPct}%`)
                        : levelKey === "unknown"
                            ? "暂无主推数据"
                            : "曝光表现正常";
            const bannerEl = $("sideAlgoStatusBanner");
            if (bannerEl) {
                bannerEl.textContent = shortStatusTitle;
                bannerEl.className = `algo-status-banner level-${levelKey}`;
            }
            const summaryEl = $("sideAlgoSummaryText");
            if (summaryEl) {
                if (levelKey === "alert") {
                    summaryEl.textContent = "近 7 天平均浏览回落，可检查题材或账号状态";
                    summaryEl.hidden = false;
                } else {
                    summaryEl.textContent = "";
                    summaryEl.hidden = true;
                }
            }
            if ($("sideOonRateVal")) {
                const sampleCount = ah.viewSampleCount ?? ah.evaluatedPostCount;
                $("sideOonRateVal").textContent = sampleCount > 0
                    ? `${ah.outOfNetworkRatePct}%`
                    : "--";
                $("sideOonRateVal").title = sampleCount > 0
                    ? `${ah.outOfNetworkPostCount}/${sampleCount} 条推文浏览达到粉丝数`
                    : "";
            }
            if ($("sideRapidFireVal")) {
                $("sideRapidFireVal").textContent = ah.rapidFirePostCount > 0
                    ? `${ah.rapidFirePostCount} 条过密`
                    : "正常";
            }
            if ($("sideExtLinkVal")) {
                const canCompare = ah.linkComparisonAvailable ??
                    (hasMetricSample(ah, "externalLinkViewSampleCount") && hasMetricSample(ah, "nativeViewSampleCount"));
                $("sideExtLinkVal").textContent = canCompare && ah.linkViewLossPct >= 15
                    ? `偏低 ${ah.linkViewLossPct}%`
                    : ah.externalLinkPostCount > 0
                        ? (canCompare ? "正常" : `${ah.externalLinkPostCount} 帖`)
                        : "无外链";
            }
            if ($("underTheHoodBtn") && ah.underTheHoodUrl) {
                $("underTheHoodBtn").href = safeHttpUrl(ah.underTheHoodUrl, "https://x.com/i/jf/under_the_hood");
            }
            const stripEl = document.getElementById("mobileAlgoHealthStrip");
            if (stripEl) {
                stripEl.className = `mobile-algo-strip level-${levelKey}`;
            }
            const mobileRefLink = document.getElementById("mobileAlgoRefLink");
            if (mobileRefLink) {
                if (ah.underTheHoodUrl) {
                    mobileRefLink.href = safeHttpUrl(ah.underTheHoodUrl, "https://x.com/i/jf/under_the_hood");
                }
                mobileRefLink.hidden = levelKey === "good";
            }
            if ($("mobileAlgoStatusText")) {
                $("mobileAlgoStatusText").textContent = shortStatusTitle;
                $("mobileAlgoStatusText").title = ah.healthSummary || "";
            }
        }

        $("opsChecklistTitle").textContent = "本期行动";

        // 每条行动都可点击直达对应页面/筛选（go）或外部链接（href）
        const todo = [];
        const ownerPaidUi = isOwnerAllPermissions();
        const effectiveUnfollow = computeEffectiveSuggestUnfollowCount(report);
        if (ah && Array.isArray(ah.opsRecommendations) && ah.opsRecommendations.length > 0) {
            for (const item of ah.opsRecommendations) {
                if (!item) continue;
                let title = item.title || item.detail || "";
                if (item.code === "CLEAN_LOW_SIGNAL_FOLLOWS") {
                    if (!ownerPaidUi || effectiveUnfollow <= 0) continue;
                    title = `${effectiveUnfollow} 个低互动未回关可清理`;
                }
                if (!ownerPaidUi && String(item.actionTarget || "").includes("follow")) continue;
                const prio = String(item.priority || "MEDIUM").toUpperCase();
                if (item.externalUrl) {
                    todo.push({
                        text: title,
                        href: safeHttpUrl(item.externalUrl, "https://x.com")
                    });
                } else {
                    let targetGo = item.actionTarget || "tab:timing";
                    if (
                        (item.code === "CADENCE_SPACING" || targetGo === "tab:schedule") &&
                        (!ownerPaidUi || (hasScheduleAccessDecision() && !isScheduleEligible()))
                    ) {
                        targetGo = "tab:timing|scrollId:topTweetsList";
                    }
                    todo.push({
                        text: title,
                        go: targetGo,
                        tone: prio === "HIGH" ? "amber" : ""
                    });
                }
            }
        } else {
            const unrepliedPeers = Number(inter.unrepliedMyPostPeers || 0);
            const iOwePeers = Number(inter.inboundHeavyPeers || 0);
            if (unrepliedPeers > 0) {
                todo.push({ text: `${unrepliedPeers} 条主推评论待回`, go: "peerFilter:UNREPLIED_POST", tone: "amber" });
            }
            if (iOwePeers > 0) {
                todo.push({ text: `${iOwePeers} 位互动推友待回访`, go: "peerFilter:I_OWE", tone: "amber" });
            }
            if (ah && ah.exposureCliffDrop) {
                todo.push({ text: "近期曝光下滑 · 分发自检", href: safeHttpUrl(ah.underTheHoodUrl, "https://x.com/i/jf/under_the_hood") });
            }
            if (ah && ah.linkViewLossPct >= 20) {
                todo.push({ text: `外链帖浏览低 ${ah.linkViewLossPct}%`, go: "tab:timing|focusDraft:1" });
            } else if (ah && ah.rapidFirePostCount > 0) {
                todo.push({
                    text: `${ah.rapidFirePostCount} 条推文间隔 <3h`,
                    go: ownerPaidUi ? "tab:schedule" : "tab:timing|focusDraft:1"
                });
            }
            if (ownerPaidUi && effectiveUnfollow > 0) {
                todo.push({ text: `${effectiveUnfollow} 个低互动未回关可清理`, go: "followTab:NOT_FOLLOWING_BACK|followCat:SUGGEST_CLEAN" });
            }
            if (ownerPaidUi && audit.fansNotFollowedBackCount > 0) {
                todo.push({ text: `${audit.fansNotFollowedBackCount} 位粉丝待回关`, go: "followTab:FAN_ONLY" });
            }
            if (act.originalPosts === 0) {
                todo.push({ text: "本期尚无原创 · 去发推", href: "https://x.com/compose/post" });
            } else if (act.replyToPostRatio < 1.5) {
                todo.push({ text: "回复比偏低 · 补充评论互动", go: "tab:interactions" });
            }
        }
        const renderTodoItem = (t) =>
            t.href
                ? `<li><a class="action-item" href="${escapeHtml(t.href)}" target="_blank" rel="noopener noreferrer"><span>${escapeHtml(t.text)}</span><span class="action-arrow">↗</span></a></li>`
                : `<li><button type="button" class="action-item ${t.tone ? "tone-" + t.tone : ""}" data-go="${escapeHtml(t.go)}"><span>${escapeHtml(t.text)}</span><span class="action-arrow">›</span></button></li>`;

        $("opsChecklist").innerHTML = todo.length
            ? todo.slice(0, 6).map(renderTodoItem).join("")
            : `<li class="user-handle-muted">暂无待办，状态很好</li>`;

        // 窄屏/手机端隐藏右侧栏时，在「活跃」页同步展示前 4 条高优先级待办，点按同样直达
        const mobileOpsCard = document.getElementById("mobileOpsCard");
        const mobileOpsList = document.getElementById("mobileOpsChecklist");
        if (mobileOpsCard && mobileOpsList) {
            mobileOpsCard.hidden = todo.length === 0;
            mobileOpsList.innerHTML = todo.slice(0, 4).map(renderTodoItem).join("");
        }

        // 待回访：与互动页「我没回谁」同一数据源，按紧急度取前 5
        const meUsername = report.profile.username;
        const owePeers = (inter.peerStats || [])
            .filter((p) => p.reciprocityCode === "I_OWE")
            .sort(
                (a, b) =>
                    (b.unrepliedInMyPostCount || 0) - (a.unrepliedInMyPostCount || 0) ||
                    b.peerRepliesToMe - a.peerRepliesToMe
            );
        $("sidebarPriorityPeers").innerHTML = owePeers.length
            ? owePeers
                  .slice(0, 5)
                  .map((p) => {
                      const meta = getPeerStatusMeta(p, meUsername, "I_OWE");
                      return `<div class="mini-peer-item">
                      <div class="mini-peer-left">
                          ${avatarHtml(p.peer, "sm")}
                          <div style="min-width:0">
                              <div class="mini-peer-name"><span data-i18n-ignore>${escapeHtml(p.peer.displayName)}</span>${verifiedSvgHtml(p.peer.verified)}</div>
                              <div class="user-handle-muted" style="font-size:12px">${escapeHtml(meta.label)}</div>
                          </div>
                      </div>
                      <a class="x-blue-pill sm" href="${escapeHtml(meta.actionUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(meta.actionText)}</a>
                  </div>`;
                  })
                  .join("") +
              (owePeers.length > 5
                  ? `<button type="button" class="side-more-link" data-go="peerFilter:I_OWE">查看全部 ${owePeers.length} 人</button>`
                  : "")
            : `<div class="user-handle-muted">没有需要回访的人</div>`;
    }

    /* ==================== 模块 6：图文定时发布与逾期二次确认 ==================== */
    function newClientRequestId() {
        if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
            return crypto.randomUUID();
        }
        return "req-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
    }

    function computeWeightedTweetLength(text) {
        const raw = String(text || "");
        if (!raw) return 0;
        const normalized = raw.replace(URL_TOKEN_RE, "x".repeat(23));
        let len = 0;
        for (const ch of normalized) {
            const cp = ch.codePointAt(0);
            const isNarrow =
                cp <= 0x10ff &&
                !(cp >= 0x2000 && cp <= 0x200d) &&
                !(cp >= 0x2010 && cp <= 0x201f) &&
                !(cp >= 0x2032 && cp <= 0x2037);
            len += isNarrow ? 1 : 2;
        }
        return len;
    }

    function validateAndMergeScheduleFiles(existingFiles, incomingFiles) {
        const baseCount = (existingFiles || []).length;
        const list = [...(existingFiles || [])];
        const incoming = Array.from(incomingFiles || []).filter(Boolean);
        const allowedMimes = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
        const extMime = (name) => {
            const lower = String(name || "").toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            return "";
        };

        let truncatedCount = 0;
        for (let idx = 0; idx < incoming.length; idx++) {
            const f = incoming[idx];
            const mime = allowedMimes.has(f.type) ? f.type : extMime(f.name);
            if (!allowedMimes.has(mime)) {
                return { ok: false, files: list, error: "仅支持 PNG、JPG、WEBP 或 GIF 格式图片", truncatedCount: 0 };
            }
            const isGif = mime === "image/gif";
            const maxBytes = isGif ? 15 * 1024 * 1024 : 5 * 1024 * 1024;
            if (Number(f.size || 0) <= 0) {
                return { ok: false, files: list, error: "图片文件为空，请重新选择", truncatedCount: 0 };
            }
            if (Number(f.size || 0) > maxBytes) {
                return { ok: false, files: list, error: `图片体积超出限制（${isGif ? "GIF 最大 15MB" : "单张静态图最大 5MB"}）`, truncatedCount: 0 };
            }
            const existingHasGif = list.some((it) => it.mime === "image/gif");
            if (isGif && list.length > 0) {
                return { ok: false, files: list, error: "GIF 动图每次仅支持 1 张，且不可与其他图片混发", truncatedCount: 0 };
            }
            if (existingHasGif) {
                return { ok: false, files: list, error: "已添加 GIF 动图，不能再添加其他图片", truncatedCount: 0 };
            }
            if (list.length >= 4) {
                if (baseCount >= 4) {
                    return { ok: false, files: list, error: "单条定时推文最多附带 4 张静态图片", truncatedCount: 0 };
                }
                truncatedCount = incoming.length - idx;
                break;
            }
            list.push({
                file: f,
                name: f.name || (isGif ? "animation.gif" : "image.png"),
                size: Number(f.size || 0),
                mime,
                previewUrl: ""
            });
        }
        // 全部文件通过校验后才创建预览，避免后一个文件无效时泄漏前面的 Blob。
        for (let i = baseCount; i < list.length; i++) {
            if (typeof URL !== "undefined" && URL.createObjectURL) {
                list[i].previewUrl = URL.createObjectURL(list[i].file);
            }
        }
        return { ok: true, files: list, error: "", truncatedCount };
    }

    function getTzDateParts(epochMs, timezone) {
        const parts = new Intl.DateTimeFormat("en-US", {
            timeZone: timezone || "Asia/Shanghai",
            weekday: "short",
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: false
        }).formatToParts(new Date(epochMs));
        const val = (t) => (parts.find((p) => p.type === t) || {}).value || "";
        const wkMap = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };
        return {
            year: Number(val("year")),
            month: Number(val("month")),
            day: Number(val("day")),
            weekday: wkMap[val("weekday")] || 1,
            hour: Number(val("hour")) % 24,
            minute: Number(val("minute")),
            second: Number(val("second"))
        };
    }

    function zonedWallClockToEpochMs(year, month, day, hour, minute, timezone) {
        const nominalUtc = Date.UTC(year, month - 1, day, hour, minute, 0, 0);
        let guess = nominalUtc;
        for (let i = 0; i < 2; i++) {
            const p = getTzDateParts(guess, timezone);
            const wallUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, 0, 0);
            guess += nominalUtc - wallUtc;
        }
        return guess;
    }

    function formatEpochToDatetimeLocalValue(epochMs) {
        const d = new Date(epochMs);
        if (Number.isNaN(d.getTime())) return "";
        const pad = (n) => String(n).padStart(2, "0");
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    const MIN_SCHEDULE_INTERVAL_MS = 3_600_000;
    const MAX_BATCH_SCHEDULE_ITEMS = 20;
    function getRequiredScheduleIntervalMs() {
        return ((typeof isOwnerAllPermissions === "function" && isOwnerAllPermissions())
                || state.assistantAccess?.allowed === true) ? 0 : MIN_SCHEDULE_INTERVAL_MS;
    }

    function computeNextAvailableScheduleMs(preferredMs, occupiedEpochsMs = [], minIntervalMs = MIN_SCHEDULE_INTERVAL_MS) {
        let candidate = Math.ceil(Number(preferredMs) / 60_000) * 60_000;
        if (!Number.isFinite(candidate)) {
            candidate = Math.ceil((Date.now() + 30 * 60_000) / 60_000) * 60_000;
        }
        const sortedOccupied = (occupiedEpochsMs || [])
            .map((v) => Number(v))
            .filter((v) => Number.isFinite(v))
            .sort((a, b) => a - b);
        for (const occupied of sortedOccupied) {
            if (Math.abs(candidate - occupied) < minIntervalMs) {
                candidate = Math.ceil((occupied + minIntervalMs) / 60_000) * 60_000;
            }
        }
        return candidate;
    }

    function validateBatchScheduleEpochs(draftEpochsMs, queuedEpochsMs = [], nowMs = Date.now(), minIntervalMs = MIN_SCHEDULE_INTERVAL_MS) {
        const epochs = (draftEpochsMs || []).map((v) => Number(v));
        const conflictSet = new Set();
        let firstError = "";
        const recordErr = (idx, msg) => {
            conflictSet.add(idx);
            if (!firstError) firstError = msg;
        };

        for (let i = 0; i < epochs.length; i++) {
            if (!Number.isFinite(epochs[i]) || epochs[i] < nowMs + 15_000) {
                recordErr(
                    i,
                    epochs.length > 1
                        ? `第 ${i + 1} 条推文定时时间需晚于当前时间至少 15 秒`
                        : "定时发布时间需晚于当前时间至少 15 秒"
                );
            }
        }

        for (let i = 0; i < epochs.length; i++) {
            if (!Number.isFinite(epochs[i])) continue;
            for (let j = i + 1; j < epochs.length; j++) {
                if (!Number.isFinite(epochs[j])) continue;
                if (Math.abs(epochs[i] - epochs[j]) < minIntervalMs) {
                    conflictSet.add(i);
                    recordErr(
                        j,
                        `第 ${i + 1} 条与第 ${j + 1} 条定时推文间隔不足 1 小时（每条推文之间必须至少间隔 1 小时）`
                    );
                }
            }
        }

        const validQueued = (queuedEpochsMs || []).map((v) => Number(v)).filter((v) => Number.isFinite(v));
        for (let i = 0; i < epochs.length; i++) {
            if (!Number.isFinite(epochs[i])) continue;
            for (const qMs of validQueued) {
                if (Math.abs(epochs[i] - qMs) < minIntervalMs) {
                    recordErr(
                        i,
                        epochs.length > 1
                            ? `第 ${i + 1} 条与队列中待发布推文间隔不足 1 小时（每条推文之间必须至少间隔 1 小时）`
                            : "与队列中待发布推文时间间隔不足 1 小时（每条推文之间必须至少间隔 1 小时）"
                    );
                    break;
                }
            }
        }

        const conflictIndices = [...conflictSet].sort((a, b) => a - b);
        return {
            ok: conflictIndices.length === 0,
            conflictIndices,
            error: firstError
        };
    }

    function alignBatchScheduleEpochs(draftEpochsMs, queuedEpochsMs = [], nowMs = Date.now(), minIntervalMs = MIN_SCHEDULE_INTERVAL_MS) {
        const minLeadMs = Math.ceil((nowMs + 5 * 60_000) / 60_000) * 60_000;
        const occupied = (queuedEpochsMs || []).map((v) => Number(v)).filter((v) => Number.isFinite(v));
        const aligned = [];
        for (let i = 0; i < (draftEpochsMs || []).length; i++) {
            const stepGapMs = typeof minIntervalMs === "function" ? Number(minIntervalMs(i)) : Number(minIntervalMs);
            const effectiveGapMs = Number.isFinite(stepGapMs) && stepGapMs >= 0 ? stepGapMs : MIN_SCHEDULE_INTERVAL_MS;
            const raw = Number(draftEpochsMs[i]);
            let target = Number.isFinite(raw) && raw >= nowMs + 60_000 ? raw : minLeadMs;
            if (i > 0 && target < aligned[i - 1] + effectiveGapMs) {
                target = aligned[i - 1] + effectiveGapMs;
            }
            const resolved = computeNextAvailableScheduleMs(target, [...occupied, ...aligned], effectiveGapMs);
            aligned.push(resolved);
        }
        return aligned;
    }

    function formatScheduleEpochLabel(epochMs) {
        if (!epochMs) return "--";
        try {
            const tz = state.scheduleServerTz || state.report?.contentTiming?.timezone || "Asia/Shanghai";
            return new Date(epochMs).toLocaleString("zh-CN", {
                timeZone: tz,
                month: "2-digit",
                day: "2-digit",
                hour: "2-digit",
                minute: "2-digit",
                hour12: false
            });
        } catch (_) {
            return new Date(epochMs).toLocaleString();
        }
    }

    function rotateComposerRequestId() {
        state.composerClientRequestId = newClientRequestId();
        if (state.scheduleDraftPersistenceReady) queueScheduleDraftPersistence();
    }

    function getQueuedScheduledEpochsMs(excludeJobId = null) {
        const nowMs = Date.now();
        return (state.scheduleJobs || [])
            .filter((j) => j && j.status === "SCHEDULED" && j.id !== excludeJobId && Number(j.scheduledAtEpochMs) > nowMs)
            .map((j) => Number(j.scheduledAtEpochMs))
            .filter((v) => Number.isFinite(v));
    }

    function parseDraftItemEpochMs(item) {
        const raw = item?.scheduledAtValue || "";
        return raw ? new Date(raw).getTime() : NaN;
    }

    function getCurrentUserScheduleJobs() {
        const userId = isLoggedIn() ? stableUserId(state.authStatus?.user) : "";
        return userId
            ? (state.scheduleJobs || []).filter((job) => job && String(job.accountId || "") === userId)
            : [];
    }

    function getLatestPublishedEpochMs(nowMs = Date.now()) {
        const userId = isLoggedIn() ? stableUserId(state.authStatus?.user) : "";
        const report = state.report;
        let latest = 0;
        if (userId && report?.liveData === true && stableUserId(report.profile) === userId) {
            const time = Date.parse(report.algorithmHealth?.latestMainPostAt || "");
            if (Number.isFinite(time) && time > 0 && time <= nowMs) latest = time;
        }
        for (const job of getCurrentUserScheduleJobs()) {
            const time = Number(job.publishedAtEpochMs);
            if (job.status === "PUBLISHED" && Number.isFinite(time) && time > latest && time <= nowMs) {
                latest = time;
            }
        }
        return latest;
    }

    function randomScheduleJitterMinutes() {
        return 1 + Math.floor(Math.random() * 10);
    }

    function getRecommendedScheduleJitterMin(draftItem = null) {
        if (draftItem && typeof draftItem === "object") {
            if (!Number.isInteger(draftItem.recommendedJitterMin)
                    || draftItem.recommendedJitterMin < 1
                    || draftItem.recommendedJitterMin > 10) {
                draftItem.recommendedJitterMin = randomScheduleJitterMinutes();
            }
            return draftItem.recommendedJitterMin;
        }
        if (!Number.isInteger(state.defaultScheduleJitterMin)
                || state.defaultScheduleJitterMin < 1
                || state.defaultScheduleJitterMin > 10) {
            state.defaultScheduleJitterMin = randomScheduleJitterMinutes();
        }
        return state.defaultScheduleJitterMin;
    }

    function getRecommendedScheduleGapMs(draftItem = null) {
        return MIN_SCHEDULE_INTERVAL_MS + getRecommendedScheduleJitterMin(draftItem) * 60_000;
    }

    const MIN_RECOMMENDED_SCHEDULE_LEAD_MS = 15 * 60_000;

    function getRecommendedBaselineEpochMs(nowMs = Date.now()) {
        const stepMs = 5 * 60_000;
        return Math.ceil((nowMs + MIN_RECOMMENDED_SCHEDULE_LEAD_MS) / stepMs) * stepMs;
    }

    function getRecommendedScheduleEpochMs(preferredMs, {
        draftIndex = state.activeScheduleDraftIdx,
        nowMs = Date.now(),
        includeLaterAutoDrafts = true,
        gapMs = null
    } = {}) {
        const activeDraft = Number.isInteger(draftIndex) && draftIndex >= 0
            ? (state.scheduleDraftItems || [])[draftIndex] || null
            : null;
        const gap = Number.isFinite(gapMs) && gapMs >= MIN_SCHEDULE_INTERVAL_MS
            ? gapMs
            : getRecommendedScheduleGapMs(activeDraft);
        const latest = getLatestPublishedEpochMs(nowMs);
        const occupied = [];
        for (const job of getCurrentUserScheduleJobs()) {
            if (job.status === "SENDING" || job.status === "RESEND_QUEUED") {
                occupied.push(nowMs);
            } else if (job.status === "SCHEDULED") {
                const time = Number(job.scheduledAtEpochMs);
                if (Number.isFinite(time) && time > 0) occupied.push(Math.max(nowMs, time));
            }
        }
        (state.scheduleDraftItems || []).forEach((item, index) => {
            if (index === draftIndex || (!includeLaterAutoDrafts && index > draftIndex && !item.timeTouched)) return;
            const time = parseDraftItemEpochMs(item);
            if (Number.isFinite(time) && time > nowMs) occupied.push(time);
        });
        const preferred = Number.isFinite(preferredMs) ? preferredMs : getRecommendedBaselineEpochMs(nowMs);
        const earliestByLastPost = latest > 0 ? latest + gap : 0;
        return computeNextAvailableScheduleMs(Math.max(nowMs + 60_000, earliestByLastPost, preferred), occupied, gap);
    }

    function formatScheduleLocalTime(epochMs, nowMs = Date.now()) {
        const date = new Date(epochMs);
        if (!Number.isFinite(date.getTime())) return "--";
        const now = new Date(nowMs);
        const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
        const sameDay = (a, b) => a.getFullYear() === b.getFullYear()
            && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
        const pad = (value) => String(value).padStart(2, "0");
        const day = sameDay(date, now) ? "今天" : sameDay(date, tomorrow) ? "明天"
            : (date.getFullYear() !== now.getFullYear() ? date.getFullYear() + "/" : "")
                + pad(date.getMonth() + 1) + "/" + pad(date.getDate());
        return day + " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    function buildScheduleQuickPresets(nowMs = Date.now()) {
        const tz = state.scheduleServerTz || state.report?.contentTiming?.timezone || "Asia/Shanghai";
        const now = getTzDateParts(nowMs, tz);
        const items = state.scheduleDraftItems || [];
        const idx = items.length > 0
            ? Math.max(0, Math.min(state.activeScheduleDraftIdx || 0, items.length - 1))
            : -1;
        const activeDraft = idx >= 0 ? items[idx] : null;
        const jitterMin = getRecommendedScheduleJitterMin(activeDraft);
        const gapMs = MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
        const untouchedDraftMs = activeDraft && !activeDraft.timeTouched ? parseDraftItemEpochMs(activeDraft) : NaN;
        const previousDraftMs = idx > 0 ? parseDraftItemEpochMs(items[idx - 1]) : NaN;
        const recommendedBaseMs = Math.max(
            Number.isFinite(untouchedDraftMs) && untouchedDraftMs >= nowMs + 60_000
                ? untouchedDraftMs
                : getRecommendedBaselineEpochMs(nowMs),
            Number.isFinite(previousDraftMs) ? previousDraftMs + gapMs : 0
        );
        const candidates = [{ epochMs: recommendedBaseMs, recommended: true }];
        const tonight = zonedWallClockToEpochMs(now.year, now.month, now.day, 20, 0, tz);
        if (tonight > nowMs + 60_000) candidates.push({ epochMs: tonight });
        candidates.push({ epochMs: zonedWallClockToEpochMs(now.year, now.month, now.day + 1, 9, 0, tz) });
        const seen = new Set();
        const presets = [];
        for (const candidate of candidates) {
            const epochMs = candidate.recommended
                ? getRecommendedScheduleEpochMs(candidate.epochMs, {
                    draftIndex: idx,
                    nowMs,
                    includeLaterAutoDrafts: false,
                    gapMs
                })
                : getRecommendedScheduleEpochMs(candidate.epochMs, { nowMs });
            if (seen.has(epochMs)) continue;
            seen.add(epochMs);
            presets.push({
                epochMs,
                label: (candidate.recommended ? "推荐 " : "") + formatScheduleLocalTime(epochMs, nowMs)
            });
        }
        return presets;
    }

    function refreshAutomaticScheduleTimes(startIdx = 0, nowMs = Date.now()) {
        if (state.scheduleMutations?.has("create") || state.scheduleDraftRestoring) return false;
        const items = state.scheduleDraftItems || [];
        let changed = false;
        for (let index = startIdx; index < items.length; index++) {
            const item = items[index];
            const input = document.querySelector('#scheduleDraftItemsList [data-draft-time="' + index + '"]');
            if (item.timeTouched || (input && document.activeElement === input)) continue;
            const gap = getRecommendedScheduleGapMs(item);
            const current = parseDraftItemEpochMs(item);
            const previous = index > 0 ? parseDraftItemEpochMs(items[index - 1]) : NaN;
            const preferred = Math.max(
                Number.isFinite(current) && current >= nowMs + 60_000 ? current : getRecommendedBaselineEpochMs(nowMs),
                Number.isFinite(previous) ? previous + gap : 0
            );
            const next = getRecommendedScheduleEpochMs(preferred, {
                draftIndex: index,
                nowMs,
                includeLaterAutoDrafts: false,
                gapMs: gap
            });
            const value = formatEpochToDatetimeLocalValue(next);
            if (value === item.scheduledAtValue) continue;
            item.scheduledAtValue = value;
            if (input) input.value = value;
            changed = true;
        }
        if (changed) rotateComposerRequestId();
        return changed;
    }

    function createScheduleDraftItem(text = "", epochMs = null, files = [], timeTouched = false, sourceRef = null, jitterMin = null, quoteMeta = null) {
        const existingItems = state.scheduleDraftItems || [];
        const resolvedJitter = Number.isInteger(jitterMin) && jitterMin >= 1 && jitterMin <= 10
            ? jitterMin
            : (existingItems.length === 0 ? getRecommendedScheduleJitterMin(null) : randomScheduleJitterMinutes());
        const gapMs = MIN_SCHEDULE_INTERVAL_MS + resolvedJitter * 60_000;
        const lastDraftEpoch = !Number.isFinite(epochMs) && existingItems.length > 0
            ? parseDraftItemEpochMs(existingItems[existingItems.length - 1])
            : NaN;
        const defaultPreferred = Math.max(
            getRecommendedBaselineEpochMs(Date.now()),
            Number.isFinite(lastDraftEpoch) ? lastDraftEpoch + gapMs : 0
        );
        const resolvedMs = Number.isFinite(epochMs)
            ? epochMs
            : getRecommendedScheduleEpochMs(defaultPreferred, { draftIndex: -1, gapMs });
        return {
            draftId: newClientRequestId(),
            text: String(text || ""),
            scheduledAtValue: formatEpochToDatetimeLocalValue(resolvedMs),
            files: Array.isArray(files) ? files : [],
            timeTouched: Boolean(timeTouched),
            recommendedJitterMin: resolvedJitter,
            quoteTweetId: String(quoteMeta?.quoteTweetId || "").trim(),
            quoteTweetUrl: String(quoteMeta?.quoteTweetUrl || "").trim(),
            sourceRef: sourceRef && sourceRef.assistantDraftId ? {
                assistantDraftId: String(sourceRef.assistantDraftId),
                assistantDraftVersion: Number(sourceRef.assistantDraftVersion || 0),
                topicId: String(sourceRef.topicId || ""),
                quoteSource: Boolean(sourceRef.quoteSource)
            } : sourceRef?.rewriteSource ? { rewriteSource: sourceRef.rewriteSource } : null
        };
    }

    function ensureScheduleDraftInitialized() {
        if (!Array.isArray(state.scheduleDraftItems) || state.scheduleDraftItems.length === 0) {
            state.scheduleDraftItems = [createScheduleDraftItem("", null, [], false)];
            state.activeScheduleDraftIdx = 0;
            rotateComposerRequestId();
            renderScheduleDraftItems();
        } else {
            const listEl = document.getElementById("scheduleDraftItemsList");
            if (listEl && listEl.children.length !== state.scheduleDraftItems.length) {
                renderScheduleDraftItems();
            } else {
                syncScheduleDraftConflictUI();
            }
        }
    }

    function addNextScheduleDraftItem() {
        if (state.scheduleDraftRestoring) return;
        ensureScheduleDraftInitialized();
        if (state.scheduleDraftItems.length >= MAX_BATCH_SCHEDULE_ITEMS) {
            showToast(`单次最多同时预设 ${MAX_BATCH_SCHEDULE_ITEMS} 条定时推文`);
            return;
        }
        const existingEpochs = state.scheduleDraftItems
            .map((it) => parseDraftItemEpochMs(it))
            .filter((v) => Number.isFinite(v));
        const jitterMin = randomScheduleJitterMinutes();
        const gapMs = MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
        const jitterMs = jitterMin * 60_000;
        const basePreferred = existingEpochs.length
            ? Math.max(existingEpochs[existingEpochs.length - 1] + gapMs, Date.now() + jitterMs)
            : Date.now() + jitterMs;
        const nextMs = getRecommendedScheduleEpochMs(basePreferred, { draftIndex: state.scheduleDraftItems.length, gapMs });
        state.scheduleDraftItems.push(createScheduleDraftItem("", nextMs, [], false, null, jitterMin));
        state.activeScheduleDraftIdx = state.scheduleDraftItems.length - 1;
        rotateComposerRequestId();
        renderScheduleDraftItems();
        syncScheduleAuthBanner();
        const newTa = document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${state.activeScheduleDraftIdx}"]`);
        newTa?.focus();
    }

    function removeScheduleDraftItem(idx) {
        if (state.scheduleDraftRestoring) return;
        const item = (state.scheduleDraftItems || [])[idx];
        if (!item) return;
        if (hasScheduleDraftContent(item) && !window.confirm(globalThis.XOpsI18n?.translate("移除这条草稿及配图？") ?? "移除这条草稿及配图？")) return;
        if (!Array.isArray(state.scheduleDraftItems) || state.scheduleDraftItems.length <= 1) {
            clearScheduleComposerDraft();
            return;
        }
        if (idx < 0 || idx >= state.scheduleDraftItems.length) return;
        const [removed] = state.scheduleDraftItems.splice(idx, 1);
        for (const media of removed?.files || []) {
            if (media?.previewUrl && typeof URL !== "undefined" && URL.revokeObjectURL) {
                try { URL.revokeObjectURL(media.previewUrl); } catch (_) {}
            }
        }
        state.activeScheduleDraftIdx = Math.max(0, Math.min(state.activeScheduleDraftIdx, state.scheduleDraftItems.length - 1));
        rotateComposerRequestId();
        renderScheduleDraftItems();
        syncScheduleAuthBanner();
    }

    function cascadeSubsequentDraftEpochs(fromIdx) {
        const items = state.scheduleDraftItems || [];
        if (fromIdx < 0 || fromIdx >= items.length - 1) return;
        refreshAutomaticScheduleTimes(fromIdx + 1);
    }

    function applyEpochToActiveScheduleDraft(epochMs) {
        if (!Number.isFinite(epochMs)) return;
        ensureScheduleDraftInitialized();
        const idx = Math.max(0, Math.min(state.activeScheduleDraftIdx || 0, state.scheduleDraftItems.length - 1));
        const item = state.scheduleDraftItems[idx];
        const resolvedMs = getRecommendedScheduleEpochMs(epochMs, { draftIndex: idx });
        item.scheduledAtValue = formatEpochToDatetimeLocalValue(resolvedMs);
        item.timeTouched = true;
        const inputEl = document.querySelector(`#scheduleDraftItemsList [data-draft-time="${idx}"]`);
        if (inputEl) inputEl.value = item.scheduledAtValue;
        cascadeSubsequentDraftEpochs(idx);
        rotateComposerRequestId();
        syncScheduleDraftConflictUI();
        renderScheduleQuickSlots();
        if (resolvedMs !== epochMs) showToast("已顺延至 " + formatScheduleLocalTime(resolvedMs));
    }

    function hasScheduleAccessDecision() {
        return state.authStatus?.loggedIn === true;
    }

    function isScheduleEligible() {
        return isOwnerAllPermissions();
    }

    function getScheduleLockedReason() {
        return "定时发布仅向已验证的作者账号开放。";
    }

    const STEPUP_SCHEDULE_DRAFT_KEY = "xops_stepup_schedule_draft";
    const SCHEDULE_DRAFT_TTL_MS = 24 * 60 * 60_000;
    let scheduleDraftPersistTimer = null;
    let scheduleDraftDbPromise = null;
    let scheduleDraftStorageQueue = Promise.resolve();
    let scheduleDraftStorageWarning = "";
    let scheduleDraftTabId = "";
    let schedulePublishAuthPending = false;
    let scheduleDraftUnloadProtected = false;
    const scheduleDraftMediaSaved = new Map();

    function hasScheduleDraftContent(item) {
        return Boolean(item && (String(item.text || "").length || (item.files || []).length || item.timeTouched || item.quoteTweetId));
    }

    function getScheduleDraftStorageKey(userId) {
        if (!scheduleDraftTabId) {
            try {
                scheduleDraftTabId = sessionStorage.getItem("xops_schedule_draft_tab") || newClientRequestId();
                sessionStorage.setItem("xops_schedule_draft_tab", scheduleDraftTabId);
            } catch (_) {
                scheduleDraftTabId = newClientRequestId();
            }
        }
        return String(userId) + ":" + scheduleDraftTabId;
    }

    function cancelScheduleDraftPersistence() {
        clearTimeout(scheduleDraftPersistTimer);
        scheduleDraftPersistTimer = null;
    }

    function syncScheduleDraftUnloadProtection() {
        const items = state.scheduleDraftItems || [];
        const userId = stableUserId(state.authStatus?.user);
        const hasContent = items.some(hasScheduleDraftContent);
        const hasMedia = items.some((item) => (item.files || []).length);
        const saved = userId ? scheduleDraftMediaSaved.get(getScheduleDraftStorageKey(userId)) : null;
        const needed = Boolean(isLoggedIn() && !state.isLoggingOut && (state.assistantDraftDirty
            || (hasContent && (scheduleDraftStorageWarning || (hasMedia && (!saved || saved.pending
                || saved.signature !== scheduleDraftMediaSignature(items)
                || Date.now() - saved.savedAt >= 5 * 60_000))))));
        if (needed === scheduleDraftUnloadProtected) return;
        scheduleDraftUnloadProtected = needed;
        window[needed ? "addEventListener" : "removeEventListener"]("beforeunload", warnBeforeLeavingScheduleDraft);
    }

    function warnBeforeLeavingScheduleDraft(event) {
        if (state.assistantDraftDirty) void saveAssistantDraftEditor();
        void persistScheduleDraftNow();
        syncScheduleDraftUnloadProtection();
        if (!scheduleDraftUnloadProtected) return;
        event.preventDefault();
        event.returnValue = "";
    }

    function showScheduleDraftStorageWarning(message, isCurrent) {
        if (!isCurrent() || scheduleDraftStorageWarning === message) return;
        scheduleDraftStorageWarning = message;
        setScheduleComposerError(message);
        syncScheduleDraftUnloadProtection();
    }

    function openScheduleDraftDatabase() {
        if (scheduleDraftDbPromise) return scheduleDraftDbPromise;
        scheduleDraftDbPromise = new Promise((resolve, reject) => {
            if (typeof indexedDB === "undefined") {
                reject(new Error("draft storage unavailable"));
                return;
            }
            const request = indexedDB.open("xops-schedule-drafts", 1);
            let done = false;
            const finish = (db, error) => {
                if (done) {
                    if (db) db.close();
                    return;
                }
                done = true;
                clearTimeout(timer);
                error ? reject(error) : resolve(db);
            };
            const timer = setTimeout(() => finish(null, new Error("draft storage timed out")), 2000);
            request.onupgradeneeded = () => {
                const store = request.result.createObjectStore("drafts", { keyPath: "key" });
                store.createIndex("expiresAt", "expiresAt");
            };
            request.onerror = () => finish(null, request.error || new Error("draft storage failed"));
            request.onblocked = () => finish(null, new Error("draft storage busy"));
            request.onsuccess = () => {
                const db = request.result;
                db.onversionchange = () => { db.close(); scheduleDraftDbPromise = null; };
                finish(db);
            };
        }).catch((error) => {
            scheduleDraftDbPromise = null;
            throw error;
        });
        return scheduleDraftDbPromise;
    }

    async function accessScheduleDraftStore(mode, action) {
        const db = await openScheduleDraftDatabase();
        return new Promise((resolve, reject) => {
            const transaction = db.transaction("drafts", mode);
            let done = false;
            const timer = setTimeout(() => {
                if (done) return;
                try { transaction.abort(); } catch (_) {}
                done = true;
                reject(new Error("draft storage timed out"));
            }, 3000);
            const finish = (value, error) => {
                if (done) return;
                done = true;
                clearTimeout(timer);
                error ? reject(error) : resolve(value);
            };
            let request;
            try {
                const store = transaction.objectStore("drafts");
                if (mode === "readwrite") {
                    const expired = store.index("expiresAt").openKeyCursor(IDBKeyRange.upperBound(Date.now()));
                    expired.onsuccess = () => {
                        const cursor = expired.result;
                        if (cursor) { store.delete(cursor.primaryKey); cursor.continue(); }
                    };
                }
                request = action(store);
            } catch (error) {
                try { transaction.abort(); } catch (_) {}
                finish(null, error);
                return;
            }
            transaction.oncomplete = () => finish(request?.result);
            transaction.onerror = transaction.onabort = () =>
                finish(null, transaction.error || new Error("draft storage failed"));
        });
    }

    function queueScheduleDraftStorage(action) {
        const next = scheduleDraftStorageQueue.catch(() => {}).then(action);
        scheduleDraftStorageQueue = next;
        return next;
    }

    function scheduleDraftMediaSignature(items) {
        return JSON.stringify(items.map((item) => [
            item.draftId,
            (item.files || []).map((media) => [media.name, media.size, media.mime, media.storageId])
        ]));
    }

    function queueScheduleDraftPersistence() {
        if (!state.scheduleDraftPersistenceReady || state.scheduleDraftRestoring || !isLoggedIn() || state.isLoggingOut) return;
        cancelScheduleDraftPersistence();
        scheduleDraftPersistTimer = setTimeout(() => { void persistScheduleDraftNow(); }, 250);
        syncScheduleDraftUnloadProtection();
    }

    async function persistScheduleDraftNow() {
        cancelScheduleDraftPersistence();
        if (!state.scheduleDraftPersistenceReady || state.scheduleDraftRestoring || !isLoggedIn() || state.isLoggingOut) return false;
        const userId = stableUserId(state.authStatus?.user);
        if (!userId) return false;
        const isCurrent = captureUserScope();
        const key = getScheduleDraftStorageKey(userId);
        const items = (state.scheduleDraftItems || []).slice(0, MAX_BATCH_SCHEDULE_ITEMS);
        if (!items.some(hasScheduleDraftContent)) {
            await clearPersistedScheduleDraft(userId);
            return true;
        }
        const mediaItems = items.map((item) => ({
            draftId: item.draftId,
            files: (item.files || []).map((media) => {
                if (!media.storageId) media.storageId = newClientRequestId();
                return { file: media.file, name: media.name, size: media.size, mime: media.mime, storageId: media.storageId };
            })
        }));
        const hasMedia = mediaItems.some((item) => item.files.length);
        const mediaSignature = scheduleDraftMediaSignature(mediaItems);
        const savedAt = Date.now();
        const record = {
            userId, key, savedAt, activeIndex: state.activeScheduleDraftIdx,
            items: items.map((item) => ({
                draftId: item.draftId, text: String(item.text || ""), scheduledAtValue: String(item.scheduledAtValue || ""),
                timeTouched: Boolean(item.timeTouched), mediaCount: (item.files || []).length,
                quoteTweetId: String(item.quoteTweetId || ""),
                quoteTweetUrl: String(item.quoteTweetUrl || ""),
                sourceRef: item.sourceRef || null
            })),
            mediaSignature
        };
        try {
            sessionStorage.setItem(STEPUP_SCHEDULE_DRAFT_KEY + ":" + userId, JSON.stringify(record));
        } catch (_) {
            showScheduleDraftStorageWarning("草稿暂存失败，内容仍在当前页面；刷新或离开前请保留正文和图片。", isCurrent);
            return false;
        }
        const previous = scheduleDraftMediaSaved.get(key);
        if (!hasMedia && !previous) {
            scheduleDraftStorageWarning = "";
            syncScheduleDraftUnloadProtection();
            return true;
        }
        if (hasMedia && previous?.signature === mediaSignature && savedAt - previous.savedAt < 5 * 60_000) {
            return previous.pending || true;
        }
        const entry = { signature: mediaSignature, savedAt, pending: null };
        const pending = queueScheduleDraftStorage(async () => {
            if (!isCurrent()) return false;
            await accessScheduleDraftStore("readwrite", (store) => hasMedia
                ? store.put({ key, userId, expiresAt: savedAt + SCHEDULE_DRAFT_TTL_MS + 5 * 60_000, mediaSignature, items: mediaItems })
                : store.delete(key));
            return true;
        }).then((stored) => {
            if (scheduleDraftMediaSaved.get(key) === entry) {
                if (stored && hasMedia) entry.pending = null;
                else scheduleDraftMediaSaved.delete(key);
            }
            if (stored) scheduleDraftStorageWarning = "";
            syncScheduleDraftUnloadProtection();
            return stored;
        }).catch(() => {
            if (scheduleDraftMediaSaved.get(key) === entry) scheduleDraftMediaSaved.delete(key);
            showScheduleDraftStorageWarning("正文与时间已暂存，图片暂存失败；离开页面后可能需要重新添加图片。", isCurrent);
            return false;
        });
        entry.pending = pending;
        scheduleDraftMediaSaved.set(key, entry);
        syncScheduleDraftUnloadProtection();
        return pending;
    }

    async function clearPersistedScheduleDraft(userId = stableUserId(state.authStatus?.user)) {
        cancelScheduleDraftPersistence();
        if (!userId) return;
        const key = getScheduleDraftStorageKey(userId);
        try {
            sessionStorage.removeItem(STEPUP_SCHEDULE_DRAFT_KEY + ":" + userId);
            const legacy = JSON.parse(sessionStorage.getItem(STEPUP_SCHEDULE_DRAFT_KEY) || "null");
            if (legacy?.userId === userId) sessionStorage.removeItem(STEPUP_SCHEDULE_DRAFT_KEY);
        } catch (_) {}
        scheduleDraftMediaSaved.delete(key);
        scheduleDraftStorageWarning = "";
        syncScheduleDraftUnloadProtection();
        if (typeof indexedDB === "undefined") return;
        await queueScheduleDraftStorage(() => accessScheduleDraftStore("readwrite", (store) => store.delete(key))).catch(() => {});
    }

    async function saveScheduleDraftForStepUpAuth() {
        state.scheduleDraftPersistenceReady = true;
        return persistScheduleDraftNow();
    }

    async function restoreScheduleDraftAfterStepUpAuth() {
        const generation = state.userScopeGeneration;
        const userId = stableUserId(state.authStatus?.user);
        const pending = state.scheduleDraftRestore;
        if (pending && pending.generation === generation && pending.userId === userId) return pending.promise;
        const restore = {generation, userId, promise: restoreScheduleDraftContents()};
        state.scheduleDraftRestore = restore;
        try { await restore.promise; }
        finally { if (state.scheduleDraftRestore === restore) state.scheduleDraftRestore = null; }
    }

    async function restoreScheduleDraftContents() {
        cancelScheduleDraftPersistence();
        const userId = stableUserId(state.authStatus?.user);
        if (!isLoggedIn() || !userId) return;
        const generation = state.userScopeGeneration;
        const isCurrent = () => generation === state.userScopeGeneration && isLoggedIn()
            && stableUserId(state.authStatus?.user) === userId;
        const initialRequestId = state.composerClientRequestId;
        let parsed = null;
        try {
            parsed = JSON.parse(sessionStorage.getItem(STEPUP_SCHEDULE_DRAFT_KEY + ":" + userId)
                || sessionStorage.getItem(STEPUP_SCHEDULE_DRAFT_KEY) || "null");
        } catch (_) {}
        if (parsed && !Array.isArray(parsed.items) && typeof parsed.text === "string") {
            parsed.items = [{ text: parsed.text, scheduledAtValue: parsed.timeValue || "", timeTouched: true, mediaCount: 0 }];
        }
        if (!parsed || parsed.userId !== userId || !parsed.savedAt ||
            Date.now() - Number(parsed.savedAt) > SCHEDULE_DRAFT_TTL_MS ||
            !Array.isArray(parsed.items) || (state.scheduleDraftItems || []).some(hasScheduleDraftContent)) {
            state.scheduleDraftPersistenceReady = true;
            return;
        }
        const items = parsed.items.slice(0, MAX_BATCH_SCHEDULE_ITEMS).map((item) => {
            const ms = item?.scheduledAtValue ? new Date(item.scheduledAtValue).getTime() : NaN;
            const quoteMeta = item?.quoteTweetId ? { quoteTweetId: item.quoteTweetId, quoteTweetUrl: item.quoteTweetUrl || "" } : null;
            const draft = createScheduleDraftItem(String(item?.text || ""),
                Number.isFinite(ms) ? ms : null, [], item?.timeTouched !== false, item?.sourceRef, null, quoteMeta);
            draft.draftId = String(item?.draftId || draft.draftId);
            if (typeof item?.scheduledAtValue === "string") draft.scheduledAtValue = item.scheduledAtValue;
            return draft;
        });
        if (!isCurrent() || state.composerClientRequestId !== initialRequestId || !items.length) {
            state.scheduleDraftPersistenceReady = true;
            return;
        }
        const needsMedia = parsed.items.some((item) => Number(item?.mediaCount) > 0);
        state.scheduleDraftRestoring = needsMedia;
        state.scheduleDraftItems = items;
        state.activeScheduleDraftIdx = Math.max(0, Math.min(Number(parsed.activeIndex) || 0, items.length - 1));
        rotateComposerRequestId();
        const restoredRequestId = state.composerClientRequestId;
        renderScheduleDraftItems();
        try {
            if (needsMedia) {
                const key = getScheduleDraftStorageKey(userId);
                const stored = await queueScheduleDraftStorage(() => accessScheduleDraftStore("readonly", (store) => store.get(key)));
                if (!isCurrent() || state.composerClientRequestId !== restoredRequestId) return;
                if (!stored || stored.userId !== userId || stored.mediaSignature !== parsed.mediaSignature || stored.expiresAt < Date.now()) {
                    throw new Error("draft images unavailable");
                }
                for (const item of stored.items || []) {
                    const draft = (state.scheduleDraftItems || []).find((candidate) => candidate.draftId === item.draftId);
                    const original = items.find((candidate) => candidate.draftId === item.draftId);
                    if (!draft || draft !== original || draft.files.length) continue;
                    const recoveredFiles = (item.files || []).map((media) => {
                        if (!(media.file instanceof Blob)) throw new Error("draft image unavailable");
                        return new File([media.file], media.name || "image.png", { type: media.mime || media.file.type });
                    });
                    const recovered = validateAndMergeScheduleFiles([], recoveredFiles);
                    if (!recovered.ok || recovered.files.length !== (item.files || []).length) throw new Error("draft images invalid");
                    recovered.files.forEach((media, index) => { media.storageId = item.files[index].storageId; });
                    draft.files = recovered.files;
                    renderScheduleDraftMedia(state.scheduleDraftItems.indexOf(draft));
                }
                scheduleDraftMediaSaved.set(key, { signature: stored.mediaSignature, savedAt: stored.expiresAt - SCHEDULE_DRAFT_TTL_MS - 5 * 60_000 });
            }
        } catch (_) {
            showScheduleDraftStorageWarning("正文与时间已恢复，图片未能恢复；请重新添加图片后再提交。", isCurrent);
        } finally {
            if (isCurrent()) {
                state.scheduleDraftRestoring = false;
                state.scheduleDraftPersistenceReady = true;
                syncScheduleComposerStats();
                syncScheduleAuthBanner();
                syncScheduleDraftUnloadProtection();
            }
        }
    }

    async function triggerXPublishAuth() {
        if (!isLoggedIn()) { triggerXOAuthLogin(); return; }
        if (schedulePublishAuthPending) return;
        schedulePublishAuthPending = true;
        const isCurrent = captureUserScope();
        try {
            setPublishAuthError("");
            if (state.authConnectionState !== "ready") await fetchServerAndAuthStatus();
            if (!isCurrent()) return;
            if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                syncScheduleAuthBanner();
                setPublishAuthError(getScheduleLockedReason(), true);
                return;
            }
            const configured = Boolean(state.authStatus?.oauthConfigured || state.serverMeta?.oauthConfigured);
            if (!configured) {
                setPublishAuthError("当前暂时无法开启发布授权，请稍后重试。", true);
                return;
            }
            const savedRequestId = state.composerClientRequestId;
            if (!await saveScheduleDraftForStepUpAuth()) {
                setPublishAuthError("草稿未能完整暂存，暂未离开当前页面；请重试，或先保留并移除配图后授权。", true);
                return;
            }
            if (!isCurrent()) return;
            if (state.composerClientRequestId !== savedRequestId) {
                setPublishAuthError("草稿有新修改，请再次点击授权以保存后继续。", true);
                return;
            }
            window.location.href = "/api/auth/x/authorize-publish";
        } catch (_) {
            if (isCurrent()) setPublishAuthError("无法确认授权状态，请重试。", true);
        } finally {
            schedulePublishAuthPending = false;
        }
    }

    function syncScheduleAuthBanner() {
        const loggedIn = isLoggedIn();
        const decided = hasScheduleAccessDecision();
        const eligible = isScheduleEligible();
        const locked = loggedIn && decided && !eligible;

        const eligCard = document.getElementById("scheduleEligibilityCard");
        if (eligCard) eligCard.hidden = !locked;

        const draftItems = state.scheduleDraftItems || [];
        const draftCount = draftItems.length;
        const anyHasFiles = draftItems.some((it) => (it.files || []).length > 0);
        const writeAuth = Boolean(state.authStatus?.writeAuthorized);
        const mediaAuth = Boolean(state.authStatus?.mediaWriteAuthorized);
        const writeOk = anyHasFiles ? (writeAuth && mediaAuth) : writeAuth;
        const offlineOk = state.authStatus?.offlineRefreshable !== false;
        const hasAuthExpiredJob = (state.scheduleJobs || []).some((j) =>
            (j.status === "FAILED" || j.status === "OVERDUE_CONFIRM_REQUIRED")
            && /二次授权|授权已过期|授权已失效|访问令牌已过期|授权范围已变更|HTTP 401|Unauthorized/i.test(j.lastError || "")
        );
        const cardVisible = loggedIn && !locked && (!writeOk || !offlineOk || Boolean(state.schedulePublishAuthError));
        const card = document.getElementById("scheduleWriteAuthCard");
        if (card) {
            card.hidden = !cardVisible;
            const titleEl = document.getElementById("scheduleWriteAuthTitle");
            const descEl = document.getElementById("scheduleWriteAuthDesc");
            const btnEl = document.getElementById("triggerPublishAuthBtn");
            if (!writeAuth) {
                if (titleEl) titleEl.textContent = hasAuthExpiredJob ? "发推授权已过期" : "需开启发推授权";
                if (descEl) {
                    descEl.textContent = hasAuthExpiredJob
                        ? "重新授予发推、传图与自动续期权限后，即可恢复排期与补发。"
                        : "单独授予发推、传图与自动续期权限。";
                }
                if (btnEl) btnEl.textContent = hasAuthExpiredJob ? "重新授权" : "去授权";
            } else if (anyHasFiles && !mediaAuth) {
                if (titleEl) titleEl.textContent = "需开启传图授权";
                if (descEl) {
                    descEl.textContent = "已有纯文本发推权限；发送图片需补充媒体上传授权。";
                }
                if (btnEl) btnEl.textContent = "去授权";
            } else if (!offlineOk) {
                if (titleEl) titleEl.textContent = "升级长效离线授权";
                if (descEl) {
                    descEl.textContent = "当前令牌约 2 小时过期，升级后自动续期。";
                }
                if (btnEl) btnEl.textContent = "去升级";
            } else if (state.schedulePublishAuthError) {
                if (titleEl) titleEl.textContent = "发推授权异常";
                if (descEl) {
                    descEl.textContent = "单独授予发推、传图与自动续期权限。";
                }
                if (btnEl) btnEl.textContent = "重新授权";
            }
        }
        const reauthBtn = document.getElementById("reauthorizePublishBtn");
        if (reauthBtn) {
            reauthBtn.hidden = !loggedIn || locked || cardVisible;
            reauthBtn.disabled = locked || state.scheduleDraftRestoring;
        }

        const shareSchedBtn = document.getElementById("scheduleShareCardBtn");
        if (shareSchedBtn) {
            const ownerAllowed = typeof isOwnerAllPermissions === "function" ? isOwnerAllPermissions() : !locked;
            shareSchedBtn.hidden = !ownerAllowed;
            shareSchedBtn.classList.toggle("is-locked-action", locked);
            shareSchedBtn.textContent = locked ? "🔒 定时发布" : "定时发布";
            shareSchedBtn.title = locked ? getScheduleLockedReason() : "将战报图与文案加入定时发布队列";
        }

        const publishError = document.getElementById("schedulePublishAuthError");
        if (publishError) {
            publishError.textContent = state.schedulePublishAuthError || "";
            publishError.hidden = !state.schedulePublishAuthError;
        }
        const composeCard = document.getElementById("scheduleComposeCard");
        if (composeCard) {
            composeCard.classList.toggle("is-locked-card", locked);
        }
        ["alignScheduleGapBtn", "clearScheduleDraftBtn", "createScheduleBtn"].forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.disabled = locked || state.scheduleDraftRestoring
                || (id === "createScheduleBtn" && state.scheduleMutations.has("create"));
        });
        const addItemBtn = document.getElementById("addScheduleDraftItemBtn");
        if (addItemBtn) {
            addItemBtn.disabled = locked || state.scheduleDraftRestoring || draftCount >= MAX_BATCH_SCHEDULE_ITEMS;
        }
        const listEl = document.getElementById("scheduleDraftItemsList");
        if (listEl) {
            listEl.querySelectorAll("textarea, input, button").forEach((ctrl) => {
                ctrl.disabled = locked || state.scheduleDraftRestoring;
            });
        }
        const createBtn = document.getElementById("createScheduleBtn");
        if (createBtn) {
            if (state.scheduleDraftRestoring) {
                createBtn.textContent = "正在恢复图片…";
            } else if (state.scheduleMutations.has("create")) {
                createBtn.textContent = "提交中…";
            } else if (!locked && !writeOk) {
                createBtn.textContent = anyHasFiles && writeAuth ? "授权传图并去定时" : "二次授权并去定时";
            } else if (draftCount > 1) {
                createBtn.textContent = `批量加入队列（${draftCount} 条）`;
            } else {
                createBtn.textContent = "加入定时队列";
            }
        }
    }

    function formatGapDurationShort(diffMs) {
        const absMin = Math.round(Math.abs(diffMs) / 60_000);
        if (absMin < 60) return `${absMin}m`;
        const h = Math.floor(absMin / 60);
        const m = absMin % 60;
        return m === 0 ? `${h}h` : `${h}h ${m}m`;
    }

    function syncSingleDraftItemStats(idx) {
        const item = (state.scheduleDraftItems || [])[idx];
        if (!item) return;
        const text = item.text || "";
        const weighted = computeWeightedTweetLength(text);
        const counter = document.querySelector(`#scheduleDraftItemsList [data-draft-counter="${idx}"]`);
        if (counter) {
            counter.textContent = text.length > MAX_SCHEDULE_TEXT_LENGTH
                ? `${text.length}/${MAX_SCHEDULE_TEXT_LENGTH} · 超出本站排期上限`
                : weighted > 280 ? `${weighted}/280 · X 账号长文权限未验证` : `${weighted}/280`;
        }
        const linkHint = document.querySelector(`#scheduleDraftItemsList [data-draft-link-hint="${idx}"]`);
        if (linkHint) {
            const hasUrl = URL_TOKEN_RE.test(text);
            URL_TOKEN_RE.lastIndex = 0;
            if (hasUrl) {
                linkHint.hidden = false;
                linkHint.textContent = "含外链";
            } else {
                linkHint.hidden = true;
            }
        }
        const algoChip = document.querySelector(`#scheduleDraftItemsList [data-draft-algo-chip="${idx}"]`);
        const algoBtn = document.querySelector(`#scheduleDraftItemsList [data-draft-action="check-algo"][data-draft-idx="${idx}"]`);
        if (text.trim()) {
            const res = analyzeDraftByXAlgo(text, {
                hasMedia: (item.files || []).length > 0,
                isThread: weighted > 280,
                plannedEpochMs: parseDraftItemEpochMs(item),
                skipDraftIdx: idx
            });
            if (algoChip) {
                if (res.summaryRiskTag) {
                    algoChip.className = `mini-tag ${res.tone}`;
                    algoChip.textContent = `⚠️ ${res.summaryRiskTag}`;
                    algoChip.title = res.rewrites.length ? res.rewrites[0] : "";
                    algoChip.hidden = false;
                } else {
                    algoChip.hidden = true;
                }
            }
            if (algoBtn) algoBtn.hidden = false;
        } else {
            if (algoChip) algoChip.hidden = true;
            if (algoBtn) algoBtn.hidden = true;
        }
    }

    function syncScheduleComposerStats() {
        const items = state.scheduleDraftItems || [];
        for (let i = 0; i < items.length; i++) {
            syncSingleDraftItemStats(i);
        }
        syncScheduleDraftConflictUI();
    }

    function syncScheduleDraftConflictUI() {
        const items = state.scheduleDraftItems || [];
        const listEl = document.getElementById("scheduleDraftItemsList");
        if (!listEl || !items.length) return;

        const isSingle = items.length === 1;
        listEl.classList.toggle("is-single", isSingle);

        const epochs = items.map((it) => parseDraftItemEpochMs(it));
        const queued = getQueuedScheduledEpochsMs();
        const nowMs = Date.now();
        const requiredInterval = getRequiredScheduleIntervalMs();
        const validation = validateBatchScheduleEpochs(epochs, queued, nowMs, requiredInterval);
        const conflictSet = new Set(validation.conflictIndices);

        items.forEach((_, idx) => {
            const cardEl = listEl.querySelector(`[data-draft-idx="${idx}"].schedule-draft-item`);
            const headEl = listEl.querySelector(`[data-draft-head="${idx}"]`);
            const gapTag = listEl.querySelector(`[data-draft-gap-tag="${idx}"]`);
            const timeInput = listEl.querySelector(`[data-draft-time="${idx}"]`);
            const hasConflict = conflictSet.has(idx);

            if (cardEl) {
                cardEl.classList.toggle("is-active", !isSingle && idx === state.activeScheduleDraftIdx);
                cardEl.classList.toggle("has-conflict", hasConflict);
            }
            if (timeInput) {
                timeInput.classList.toggle("is-conflict", hasConflict);
            }

            let tagText = "";
            let tagClass = "schedule-gap-tag";
            const curMs = epochs[idx];
            if (!Number.isFinite(curMs) || curMs < nowMs + 15_000) {
                tagText = "需设为未来时间";
                tagClass += " is-conflict";
            } else {
                const nearQueue = queued.find((qMs) => Math.abs(curMs - qMs) < requiredInterval);
                if (nearQueue !== undefined) {
                    tagText = `距队列 ${formatGapDurationShort(curMs - nearQueue)}（需 ≥1h）`;
                    tagClass += " is-conflict";
                } else if (idx > 0 && Number.isFinite(epochs[idx - 1])) {
                    const diffPrev = curMs - epochs[idx - 1];
                    if (Math.abs(diffPrev) < requiredInterval) {
                        tagText = `距 #${idx} ${formatGapDurationShort(diffPrev)}（需 ≥1h）`;
                        tagClass += " is-conflict";
                    } else if (diffPrev >= requiredInterval) {
                        tagText = `距 #${idx} +${formatGapDurationShort(diffPrev)}`;
                        tagClass += " is-ok";
                    } else {
                        tagText = `早于 #${idx} ${formatGapDurationShort(diffPrev)}`;
                        tagClass += " is-ok";
                    }
                } else if (hasConflict) {
                    tagText = "间隔需 ≥1h";
                    tagClass += " is-conflict";
                }
            }

            if (gapTag) {
                gapTag.className = tagClass;
                gapTag.textContent = tagText;
                gapTag.hidden = !tagText;
            }
            if (headEl) {
                headEl.hidden = isSingle && !tagText;
            }
        });

        const alignBtn = document.getElementById("alignScheduleGapBtn");
        if (alignBtn) {
            alignBtn.hidden = validation.ok;
        }
    }

    function scheduleDraftMediaHtml(item, idx) {
        return (item.files || []).map((media, mediaIdx) => {
            const sizeKb = Math.max(1, Math.round(media.size / 1024));
            return `<div class="schedule-media-thumb">
                <img data-i18n-ignore src="${escapeHtml(media.previewUrl)}" alt="${escapeHtml(media.name)}" />
                <button type="button" class="schedule-media-remove" data-draft-action="remove-media" data-draft-idx="${idx}" data-remove-media-idx="${mediaIdx}" title="移除图片" aria-label="移除图片">×</button>
                <span class="schedule-media-caption">${sizeKb >= 1024 ? (sizeKb / 1024).toFixed(1) + "MB" : sizeKb + "KB"}</span>
            </div>`;
        }).join("");
    }

    function renderScheduleDraftMedia(idx, syncStats = true) {
        const item = (state.scheduleDraftItems || [])[idx];
        const grid = document.querySelector(`#scheduleDraftItemsList [data-draft-media-grid="${idx}"]`);
        if (!item || !grid) return;
        const html = scheduleDraftMediaHtml(item, idx);
        if (grid._sourceMarkup !== html) {
            grid._sourceMarkup = html;
            const focusedMedia = grid.contains(document.activeElement);
            grid.innerHTML = html;
            if (focusedMedia) {
                const next = grid.querySelector("button")
                    || document.querySelector(`#scheduleDraftItemsList [data-draft-action="add-media"][data-draft-idx="${idx}"]`);
                next?.focus({ preventScroll: true });
            }
        }
        grid.hidden = !(item.files || []).length;
        if (syncStats) {
            syncSingleDraftItemStats(idx);
            syncScheduleAuthBanner();
        }
    }

    function getLocalTimezoneBadge() {
        const offsetMin = -new Date().getTimezoneOffset();
        const sign = offsetMin >= 0 ? "+" : "-";
        const absMin = Math.abs(offsetMin);
        const hrs = Math.floor(absMin / 60);
        const mins = absMin % 60;
        return mins > 0 ? `UTC${sign}${hrs}:${String(mins).padStart(2, "0")}` : `UTC${sign}${hrs}`;
    }

    function scheduleDraftQuoteHtml(item, idx) {
        const qid = String(item?.quoteTweetId || "").trim();
        if (!qid) return "";
        const rawUrl = String(item?.quoteTweetUrl || `https://x.com/i/status/${qid}`).trim();
        const qUrl = typeof safeHttpUrl === "function" ? safeHttpUrl(rawUrl, `https://x.com/i/status/${qid}`) : rawUrl;
        const handleMatch = qUrl.match(/https?:\/\/(?:(?:www|mobile)\.)?(?:x|twitter)\.com\/([A-Za-z0-9_]{1,15})\/status\//i);
        const label = handleMatch && handleMatch[1].toLowerCase() !== "i" ? `@${handleMatch[1]}` : `#${qid}`;
        return `<div class="schedule-quote-attachment" data-draft-quote-bar="${idx}">
            <span>引用原帖 <a href="${escapeHtml(qUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(label)} ↗</a></span>
            <button type="button" class="link-inline-btn" data-draft-action="clear-quote" data-draft-idx="${idx}" aria-label="移除引用原帖">×</button>
        </div>`;
    }

    function scheduleDraftCardHtml(item, idx, isSingle) {
        const placeholder = isSingle ? "输入定时推文内容…" : `第 ${idx + 1} 条推文内容…`;
        const minAttrVal = formatEpochToDatetimeLocalValue(Date.now() + 60_000);
        const tzBadge = getLocalTimezoneBadge();
        const quoteHtml = scheduleDraftQuoteHtml(item, idx);
        return `<div class="schedule-draft-item" data-draft-idx="${idx}" data-draft-key="${escapeHtml(item.draftId)}">
            <div class="schedule-draft-item-head" data-draft-head="${idx}" ${isSingle ? "hidden" : ""}>
                <div class="schedule-draft-head-left">
                    <span class="schedule-seq-pill">#${idx + 1}</span>
                    <span class="schedule-gap-tag" data-draft-gap-tag="${idx}" hidden></span>
                </div>
                <div class="schedule-draft-head-right">
                    <button type="button" class="schedule-draft-remove-btn" data-draft-action="remove-item" data-draft-idx="${idx}" title="移除第 ${idx + 1} 条" aria-label="移除第 ${idx + 1} 条" ${isSingle ? "hidden" : ""}>×</button>
                </div>
            </div>
            <textarea class="sim-textarea schedule-textarea" data-draft-textarea="${idx}" rows="${isSingle ? 4 : 3}"
                aria-label="第 ${idx + 1} 条推文正文" placeholder="${placeholder}">${escapeHtml(item.text || "")}</textarea>
            <div data-draft-quote-slot="${idx}" ${quoteHtml ? "" : "hidden"}>${quoteHtml}</div>
            <div class="schedule-media-grid" data-draft-media-grid="${idx}" ${(item.files || []).length ? "" : "hidden"}>${scheduleDraftMediaHtml(item, idx)}</div>
            <div class="schedule-draft-item-foot">
                <div class="schedule-compose-left">
                    <button type="button" class="x-outline-pill sm" data-draft-action="add-media" data-draft-idx="${idx}">添加图片</button>
                    <span class="muted-meta" data-draft-counter="${idx}">0 / 280</span>
                    <span class="mini-tag amber" data-draft-link-hint="${idx}" hidden></span>
                    <span class="mini-tag" data-draft-algo-chip="${idx}" hidden></span>
                    <button type="button" class="x-outline-pill sm" data-draft-action="check-algo" data-draft-idx="${idx}" hidden>体检</button>
                </div>
                <div class="schedule-compose-right">
                    <span class="schedule-tz-badge" data-i18n-ignore title="本地时区">${escapeHtml(tzBadge)}</span>
                    <input type="datetime-local" class="schedule-time-input" data-draft-time="${idx}" min="${escapeHtml(minAttrVal)}" aria-label="第 ${idx + 1} 条定时发布时间" value="${escapeHtml(item.scheduledAtValue || "")}" />
                </div>
            </div>
        </div>`;
    }

    function renderScheduleDraftItems() {
        const listEl = document.getElementById("scheduleDraftItemsList");
        if (!listEl) return;
        const items = state.scheduleDraftItems || [];
        const isSingle = items.length === 1;
        listEl.classList.toggle("is-single", isSingle);
        const minAttrVal = formatEpochToDatetimeLocalValue(Date.now() + 60_000);
        const existing = new Map(Array.from(listEl.children).map((card) => [card.dataset.draftKey, card]));
        const keep = new Set();
        items.forEach((item, idx) => {
            if (!item.draftId) item.draftId = newClientRequestId();
            let card = existing.get(item.draftId);
            if (!card) {
                const template = document.createElement("template");
                template.innerHTML = scheduleDraftCardHtml(item, idx, isSingle);
                card = template.content.firstElementChild;
            }
            keep.add(card);
            card.dataset.draftKey = item.draftId;
            card.dataset.draftIdx = String(idx);
            for (const attr of ["data-draft-idx", "data-draft-head", "data-draft-gap-tag", "data-draft-textarea",
                "data-draft-quote-slot", "data-draft-media-grid", "data-draft-counter", "data-draft-link-hint", "data-draft-algo-chip", "data-draft-time"]) {
                card.querySelectorAll("[" + attr + "]").forEach((node) => node.setAttribute(attr, String(idx)));
            }
            const quoteSlot = card.querySelector(`[data-draft-quote-slot="${idx}"]`);
            if (quoteSlot) {
                const quoteHtml = scheduleDraftQuoteHtml(item, idx);
                if (quoteSlot._sourceMarkup !== quoteHtml) {
                    quoteSlot._sourceMarkup = quoteHtml;
                    quoteSlot.innerHTML = quoteHtml;
                }
                quoteSlot.hidden = !quoteHtml;
            }
            const sequence = card.querySelector(".schedule-seq-pill");
            if (sequence) sequence.textContent = "#" + (idx + 1);
            const remove = card.querySelector('[data-draft-action="remove-item"]');
            if (remove) {
                remove.hidden = isSingle;
                remove.title = "移除第 " + (idx + 1) + " 条";
                remove.setAttribute("aria-label", remove.title);
            }
            const textarea = card.querySelector("textarea");
            if (textarea) {
                textarea.rows = isSingle ? 4 : 3;
                textarea.placeholder = isSingle ? "输入定时推文内容…" : "第 " + (idx + 1) + " 条推文内容…";
                textarea.setAttribute("aria-label", "第 " + (idx + 1) + " 条推文正文");
                if (document.activeElement !== textarea && textarea.value !== item.text) textarea.value = item.text;
            }
            const time = card.querySelector('input[type="datetime-local"]');
            if (time) {
                time.setAttribute("aria-label", "第 " + (idx + 1) + " 条定时发布时间");
                if (minAttrVal) time.min = minAttrVal;
                if (document.activeElement !== time && time.value !== item.scheduledAtValue) time.value = item.scheduledAtValue;
            }
            if (listEl.children[idx] !== card) listEl.insertBefore(card, listEl.children[idx] || null);
            renderScheduleDraftMedia(idx, false);
        });
        Array.from(listEl.children).forEach((card) => { if (!keep.has(card)) card.remove(); });
        syncScheduleComposerStats();
        syncScheduleAuthBanner();
        renderScheduleQuickSlots();
    }

    function clearScheduleComposerDraft() {
        void clearPersistedScheduleDraft();
        for (const draft of state.scheduleDraftItems || []) {
            for (const item of draft.files || []) {
                if (item.previewUrl && typeof URL !== "undefined" && URL.revokeObjectURL) {
                    try { URL.revokeObjectURL(item.previewUrl); } catch (_) {}
                }
            }
        }
        state.scheduleDraftFiles = [];
        state.scheduleDraftItems = [];
        state.scheduleDraftItems = [createScheduleDraftItem("", null, [], false)];
        state.activeScheduleDraftIdx = 0;
        state.mediaPickerTargetIdx = 0;
        const fi = document.getElementById("scheduleMediaFileInput");
        if (fi) fi.value = "";
        rotateComposerRequestId();
        renderScheduleDraftItems();
        syncScheduleAuthBanner();
    }

    function appendFilesToScheduleDraft(fileList, targetIdx = state.activeScheduleDraftIdx) {
        ensureScheduleDraftInitialized();
        const idx = Math.max(0, Math.min(Number(targetIdx) || 0, state.scheduleDraftItems.length - 1));
        const draft = state.scheduleDraftItems[idx];
        const res = validateAndMergeScheduleFiles(draft.files || [], fileList);
        if (!res.ok) {
            showToast(res.error);
            return;
        }
        if (res.files.length !== (draft.files || []).length) {
            draft.files = res.files;
            state.activeScheduleDraftIdx = idx;
            rotateComposerRequestId();
            renderScheduleDraftMedia(idx);
            clearScheduleComposeErrorForDraft(idx);
            syncScheduleAuthBanner();
        }
        if (res.truncatedCount > 0) {
            showToast("单条推文最多 4 张图片，已自动保留前 4 张");
        }
    }

    function renderScheduleQuickSlots() {
        const wrap = document.getElementById("scheduleQuickSlotChips");
        if (!wrap) return;
        if (refreshAutomaticScheduleTimes()) syncScheduleDraftConflictUI();
        const nowMs = Date.now();
        const context = document.getElementById("scheduleTimingContext");
        if (context) {
            const latest = getLatestPublishedEpochMs(nowMs);
            const jobs = getCurrentUserScheduleJobs();
            const sending = jobs.some((job) => job.status === "SENDING" || job.status === "RESEND_QUEUED");
            const next = jobs.filter((job) => job.status === "SCHEDULED" && Number(job.scheduledAtEpochMs) > nowMs)
                .map((job) => Number(job.scheduledAtEpochMs)).sort((a, b) => a - b)[0];
            const timeHtml = (ms) => '<time datetime="' + new Date(ms).toISOString() + '">'
                + escapeHtml(formatScheduleLocalTime(ms, nowMs)) + '</time>';
            const html = '<span>' + (latest ? '最近发文记录 ' + timeHtml(latest) : state.isFetching ? '正在读取发文记录…' : '暂无已同步发文记录') + '</span>'
                + (sending ? '<span>正在发布</span>' : next ? '<span>下条定时 ' + timeHtml(next) + '</span>' : '');
            if (context._sourceMarkup !== html) {
                context.innerHTML = html;
                context._sourceMarkup = html;
            }
            context.title = "按浏览器本地时间显示；发文记录来自已同步的原创、引用推文及本站发布成功记录。";
        }
        const html = buildScheduleQuickPresets(nowMs).map((preset) =>
            '<button type="button" class="pill-chip" data-quick-epoch="' + preset.epochMs + '">'
            + escapeHtml(preset.label) + '</button>'
        ).join("");
        if (wrap._sourceMarkup !== html) {
            wrap.innerHTML = html;
            wrap._sourceMarkup = html;
        }
        syncScheduleQuickSlotSelection();
    }

    function syncScheduleQuickSlotSelection() {
        const wrap = document.getElementById("scheduleQuickSlotChips");
        if (!wrap) return;
        const idx = Math.max(0, Math.min(state.activeScheduleDraftIdx || 0, (state.scheduleDraftItems || []).length - 1));
        const activeVal = state.scheduleDraftItems && state.scheduleDraftItems[idx]
            ? String(state.scheduleDraftItems[idx].scheduledAtValue || "")
            : "";
        wrap.querySelectorAll("[data-quick-epoch]").forEach((chip) => {
            const ms = Number(chip.dataset.quickEpoch);
            const match = Boolean(activeVal) && Number.isFinite(ms) && formatEpochToDatetimeLocalValue(ms) === activeVal;
            chip.classList.toggle("active", match);
        });
    }

    function sendTextDraftToSchedule(text, attachedFiles = [], quoteMeta = null, rewriteSource = null) {
        switchTab("schedule");
        if (state.scheduleDraftRestoring) {
            setScheduleComposerError("图片正在恢复，请稍候再加入草稿。", true);
            return false;
        }
        if (hasScheduleAccessDecision() && !isScheduleEligible()) {
            syncScheduleAuthBanner();
            setScheduleComposerError(getScheduleLockedReason(), true);
            return false;
        }
        ensureScheduleDraftInitialized();
        const items = state.scheduleDraftItems;
        const isEmpty = (item) => !String(item.text || "").trim() && !(item.files || []).length && !String(item.quoteTweetId || "").trim();
        let idx = isEmpty(items[state.activeScheduleDraftIdx]) ? state.activeScheduleDraftIdx : items.findIndex(isEmpty);
        if (idx < 0 && items.length >= MAX_BATCH_SCHEDULE_ITEMS) {
            setScheduleComposerError("草稿已满，请先提交或移除一条后再添加；现有内容已保留。", true);
            return false;
        }
        const media = validateAndMergeScheduleFiles([], attachedFiles);
        if (!media.ok) { setScheduleComposerError(media.error, true); return false; }
        let item;
        if (idx < 0) {
            idx = items.length;
            const jitterMin = randomScheduleJitterMinutes();
            const gapMs = MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
            const last = parseDraftItemEpochMs(items[items.length - 1]);
            const preferred = Math.max(getRecommendedBaselineEpochMs(Date.now()), Number.isFinite(last) ? last + gapMs : 0);
            item = createScheduleDraftItem("", getRecommendedScheduleEpochMs(preferred, { draftIndex: idx, gapMs }), [], false, null, jitterMin, quoteMeta);
            items.push(item);
        } else {
            item = items[idx];
        }
        item.text = String(text || "");
        item.files = media.files;
        item.quoteTweetId = String(quoteMeta?.quoteTweetId || "").trim();
        item.quoteTweetUrl = String(quoteMeta?.quoteTweetUrl || "").trim();
        item.sourceRef = rewriteSource ? { rewriteSource } : null;
        if (!item.timeTouched) {
            const jitterMin = getRecommendedScheduleJitterMin(item);
            const gapMs = MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
            const preferred = Math.max(parseDraftItemEpochMs(item) || 0, getRecommendedBaselineEpochMs(Date.now()));
            item.scheduledAtValue = formatEpochToDatetimeLocalValue(getRecommendedScheduleEpochMs(preferred, { draftIndex: idx, gapMs }));
        }
        state.activeScheduleDraftIdx = idx;
        setScheduleComposerError("");
        rotateComposerRequestId();
        renderScheduleDraftItems();
        const input = document.querySelector('#scheduleDraftItemsList [data-draft-textarea="' + idx + '"]');
        input?.focus({ preventScroll: true });
        input?.scrollIntoView({ block: "nearest" });
        return true;
    }

    function canvasToPngFile(canvas, fileName) {
        let dataUrl;
        try {
            dataUrl = canvas.toDataURL("image/png");
        } catch (_) {
            if (state.report) {
                drawShareCardCanvas(state.report, true);
            }
            dataUrl = canvas.toDataURL("image/png");
        }
        const commaIdx = dataUrl.indexOf(",");
        const binary = atob(dataUrl.slice(commaIdx + 1));
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return new File([bytes], fileName, { type: "image/png" });
    }

    function statusMetaForScheduledJob(status) {
        switch (status) {
            case "SCHEDULED":
                return { label: "待发布", cls: "blue" };
            case "OVERDUE_CONFIRM_REQUIRED":
                return { label: "逾期待确认", cls: "amber" };
            case "RESEND_QUEUED":
                return { label: "已确认·排队补发", cls: "blue" };
            case "SENDING":
                return { label: "正在发送…", cls: "blue" };
            case "PUBLISHED":
                return { label: "已发布", cls: "green" };
            case "FAILED":
                return { label: "发送失败", cls: "danger" };
            case "CANCELLED":
                return { label: "已取消", cls: "" };
            default:
                return { label: status || "--", cls: "" };
        }
    }

    function updateScheduleBadgesAndBanner() {
        if (typeof isOwnerAllPermissions === "function" && !isOwnerAllPermissions()) {
            ["navScheduleBadge", "mobileScheduleBadge", "overdueGlobalBanner"].forEach((id) => {
                const el = $(id);
                if (el) el.hidden = true;
            });
            return;
        }
        const overdue = Number(state.scheduleOverdueCount || 0);
        const pending = Number(state.schedulePendingCount || 0);
        const badgeCount = overdue > 0 ? overdue : pending;

        const navBadge = $("navScheduleBadge");
        const mobBadge = $("mobileScheduleBadge");
        [navBadge, mobBadge].forEach((b) => {
            if (!b) return;
            if (badgeCount > 0) {
                b.textContent = badgeCount > 99 ? "99+" : String(badgeCount);
                b.hidden = false;
                b.style.background = overdue > 0 ? "var(--x-amber)" : "var(--x-blue)";
                b.style.color = overdue > 0 ? "#0f1419" : "#ffffff";
            } else {
                b.hidden = true;
            }
        });

        const banner = $("overdueGlobalBanner");
        const bannerText = $("overdueGlobalText");
        if (banner && bannerText) {
            if (isLoggedIn() && overdue > 0) {
                bannerText.textContent = `${overdue} 条定时逾期待确认`;
                banner.hidden = false;
            } else {
                banner.hidden = true;
            }
        }
    }

    function setRescheduleError(jobId, message, reveal = false, kind = "validation") {
        if (state.rescheduleEditingJobId !== jobId) return false;
        state.rescheduleError = String(message || "");
        state.rescheduleErrorKind = state.rescheduleError ? kind : "";
        const key = CSS.escape(jobId);
        const error = document.querySelector('[data-resched-error="' + key + '"]');
        const input = document.querySelector('[data-resched-input="' + key + '"]');
        if (input) input.setAttribute("aria-invalid", state.rescheduleError ? "true" : "false");
        if (!error) return false;
        const displayedSource = globalThis.XOpsI18n?.sourceText(error) ?? error.textContent;
        if (displayedSource !== state.rescheduleError) error.textContent = state.rescheduleError;
        error.hidden = !state.rescheduleError;
        if (reveal && state.rescheduleError) error.scrollIntoView({ block: "nearest" });
        return true;
    }

    function canRescheduleJob(job) {
        return Boolean(job && !job.uncertainDelivery &&
            ["SCHEDULED", "OVERDUE_CONFIRM_REQUIRED", "FAILED", "CANCELLED"].includes(job.status));
    }

    function preserveRescheduleEditor(listEl) {
        if (!listEl) return false;
        const jobId = state.rescheduleEditingJobId;
        if (!jobId) return false;
        const key = CSS.escape(jobId);
        if (!listEl.querySelector('[data-resched-input="' + key + '"]')) return false;
        const job = (state.scheduleJobs || []).find((item) => item.id === jobId);
        const editable = canRescheduleJob(job);
        const save = listEl.querySelector('[data-job-action="save-reschedule"][data-job-id="' + key + '"]');
        if (save) save.disabled = !editable || state.scheduleMutations.has("job:" + jobId)
            || state.scheduleMutations.has("batch-resend");
        if (!editable) {
            const message = !job ? "这条任务已不存在，请关闭编辑。"
                : job.status === "PUBLISHED" ? "这条任务已发布，无法改期。"
                : job.uncertainDelivery ? "请先核实原帖是否已发布，再决定是否补发。"
                : "这条任务正在发送或已进入补发队列，暂时无法改期。";
            setRescheduleError(jobId, message, false, "status");
        } else if (state.rescheduleErrorKind === "status") {
            setRescheduleError(jobId, "");
        }
        // Keep the original input node: replacing it dismisses mobile date/time pickers.
        return true;
    }

    function scheduledPublicationDisplay(job) {
        const published = job.status === "PUBLISHED";
        const receipt = published ? job.publicationReceipt : null;
        const payload = receipt || (job.uncertainDelivery ? job.dispatchedPayload : null);
        const quoteId = payload ? payload.quoteTweetId : job.quoteTweetId;
        return {
            text: payload?.text ?? job.text ?? "",
            quoteUrl: quoteId ? (job.quoteTweetUrl || `https://x.com/i/status/${quoteId}`) : "",
            quoteLabel: published && !receipt ? "原计划引用的推文 ↗" : "引用原帖 ↗",
            modeLabel: receipt?.mode === "QUOTE_LINK" ? "已发布，并附上原帖链接"
                : receipt?.mode === "STANDALONE_FALLBACK" ? "原帖引用受限，已作为独立推文发布" : ""
        };
    }

    function renderScheduledTweetsList() {
        const listEl = $("scheduledTweetsList");
        if (!listEl) return;
        const all = state.scheduleJobs || [];
        const writeAuth = Boolean(state.authStatus?.writeAuthorized);
        const mediaAuth = Boolean(state.authStatus?.mediaWriteAuthorized);
        const signature = JSON.stringify([all, state.scheduleFilter, state.rescheduleEditingJobId, writeAuth, mediaAuth]);
        if (signature === state.scheduleRenderedSignature) return;
        const focusedJobId = document.activeElement?.getAttribute?.("data-resched-input");
        const overdueConfirmable = all.filter((j) => j.status === "OVERDUE_CONFIRM_REQUIRED" && !j.uncertainDelivery);
        const batchBtn = $("batchConfirmOverdueBtn");
        if (batchBtn) {
            batchBtn.hidden = overdueConfirmable.length < 2;
            batchBtn.textContent = `批量补发 (${overdueConfirmable.length})`;
        }

        const actionNeededCount = all.filter((j) => j.status === "OVERDUE_CONFIRM_REQUIRED" || j.status === "FAILED").length;
        const scheduledCount = all.filter((j) => j.status === "SCHEDULED" || j.status === "RESEND_QUEUED" || j.status === "SENDING").length;
        const publishedCount = all.filter((j) => j.status === "PUBLISHED").length;
        const pillWrap = document.getElementById("scheduleStatusFilterPills");
        if (pillWrap) {
            const labelMap = {
                ALL: all.length > 0 ? `全部 ${all.length}` : "全部",
                ACTION_NEEDED: actionNeededCount > 0 ? `待处理 ${actionNeededCount}` : "待处理",
                SCHEDULED: scheduledCount > 0 ? `待发布 ${scheduledCount}` : "待发布",
                PUBLISHED: publishedCount > 0 ? `已发布 ${publishedCount}` : "已发布"
            };
            pillWrap.querySelectorAll(".pill-chip[data-sfilter]").forEach((btn) => {
                const key = btn.dataset.sfilter;
                if (labelMap[key]) btn.textContent = labelMap[key];
            });
        }

        const filtered = all.filter((j) => {
            if (state.scheduleFilter === "ACTION_NEEDED") {
                return j.status === "OVERDUE_CONFIRM_REQUIRED" || j.status === "FAILED";
            }
            if (state.scheduleFilter === "SCHEDULED") {
                return j.status === "SCHEDULED" || j.status === "RESEND_QUEUED" || j.status === "SENDING";
            }
            if (state.scheduleFilter === "PUBLISHED") {
                return j.status === "PUBLISHED";
            }
            return true;
        });

        if (preserveRescheduleEditor(listEl)) return;
        state.scheduleRenderedSignature = signature;
        if (!filtered.length) {
            listEl.innerHTML = emptyStateHtml("暂无定时记录", []);
            return;
        }

        listEl.innerHTML = filtered
            .map((job) => {
                const st = statusMetaForScheduledJob(job.status);
                const mediaHtml = (job.media || []).length
                    ? `<div class="schedule-job-media">${job.media
                          .map(
                              (a) =>
                                  `<img data-i18n-ignore src="/api/scheduled-tweets/${encodeURIComponent(job.id)}/media/${encodeURIComponent(a.assetId)}" alt="${escapeHtml(a.fileName || "image")}" loading="lazy" />`
                          )
                          .join("")}</div>`
                    : "";

                let alertHtml = "";
                if (job.status === "OVERDUE_CONFIRM_REQUIRED") {
                    const cls = job.uncertainDelivery ? "is-danger" : "is-overdue";
                    alertHtml = `<div class="schedule-job-alert ${cls}">${escapeHtml(job.lastError || "已超过预定时间，确认后自动补发")}</div>`;
                } else if (job.status === "FAILED" && job.lastError) {
                    alertHtml = `<div class="schedule-job-alert is-danger">${escapeHtml(job.lastError)}</div>`;
                }

                const canEditTime = canRescheduleJob(job);
                const showRescheduleBox = state.rescheduleEditingJobId === job.id && canEditTime;
                const rescheduleMinVal = formatEpochToDatetimeLocalValue(Date.now() + 60_000);
                const rescheduleBoxHtml = showRescheduleBox
                    ? `<div class="schedule-reschedule-box">
                        <input type="datetime-local" class="x-mini-input" data-resched-input="${escapeHtml(job.id)}" min="${escapeHtml(rescheduleMinVal)}" aria-label="新的发布时间" aria-describedby="reschedule-error-${escapeHtml(job.id)}" aria-invalid="${state.rescheduleError ? "true" : "false"}" value="${escapeHtml(state.rescheduleDraftValue ?? formatEpochToDatetimeLocalValue(Math.max(job.scheduledAtEpochMs || 0, Date.now() + 15 * 60_000)))}" />
                        <button type="button" class="x-blue-pill sm" data-job-action="save-reschedule" data-job-id="${escapeHtml(job.id)}">保存</button>
                        <button type="button" class="x-outline-pill sm" data-job-action="close-reschedule" data-job-id="${escapeHtml(job.id)}">放弃修改</button>
                        <p class="schedule-reschedule-error" id="reschedule-error-${escapeHtml(job.id)}" data-resched-error="${escapeHtml(job.id)}" role="alert" ${state.rescheduleError ? "" : "hidden"}>${escapeHtml(state.rescheduleError || "")}</p>
                    </div>`
                    : "";

                const actions = [];
                if (job.text && job.text.trim()) {
                    actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="copy-text" data-job-id="${escapeHtml(job.id)}" aria-label="复制原文">复制</button>`);
                }
                const jobMissingAuth = !writeAuth || ((job.media || []).length > 0 && !mediaAuth);
                const jobHasAuthErr = /二次授权|授权已过期|授权已失效|访问令牌已过期|授权范围已变更|HTTP 401|Unauthorized/i.test(job.lastError || "");
                if (job.status === "OVERDUE_CONFIRM_REQUIRED" || job.status === "FAILED") {
                    const resendLabel = job.status === "OVERDUE_CONFIRM_REQUIRED" ? "确认补发" : "确认重发";
                    if (jobMissingAuth) {
                        actions.push(`<button type="button" class="x-blue-pill sm" data-job-action="reauth" data-job-id="${escapeHtml(job.id)}">重新授权</button>`);
                        actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="open-confirm" data-job-id="${escapeHtml(job.id)}">${resendLabel}</button>`);
                    } else {
                        actions.push(`<button type="button" class="x-blue-pill sm" data-job-action="open-confirm" data-job-id="${escapeHtml(job.id)}">${resendLabel}</button>`);
                        if (jobHasAuthErr) {
                            actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="reauth" data-job-id="${escapeHtml(job.id)}">重新授权</button>`);
                        }
                    }
                }
                if (canEditTime) {
                    actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="toggle-reschedule" data-job-id="${escapeHtml(job.id)}">改期</button>`);
                }
                if (job.status === "SCHEDULED" || job.status === "OVERDUE_CONFIRM_REQUIRED") {
                    actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="cancel" data-job-id="${escapeHtml(job.id)}">取消定时</button>`);
                }
                if (job.status === "PUBLISHED" && job.publishedTweetUrl) {
                    actions.push(`<a class="x-outline-pill sm" href="${escapeHtml(safeHttpUrl(job.publishedTweetUrl))}" target="_blank" rel="noopener noreferrer">原推 ↗</a>`);
                }
                if (job.status !== "SENDING" && job.status !== "RESEND_QUEUED") {
                    actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="delete" data-job-id="${escapeHtml(job.id)}">删除记录</button>`);
                }

                const timePrefix = job.status === "PUBLISHED" && job.publishedAtEpochMs
                    ? formatScheduleEpochLabel(job.publishedAtEpochMs)
                    : formatScheduleEpochLabel(job.scheduledAtEpochMs);
                const timeTitle = job.status === "PUBLISHED" && job.publishedAtEpochMs
                    ? `原定 ${formatScheduleEpochLabel(job.scheduledAtEpochMs)}`
                    : "";

                const display = scheduledPublicationDisplay(job);
                const jobQuoteHtml = display.quoteUrl
                    ? `<div class="schedule-quote-attachment is-queued"><a href="${escapeHtml(safeHttpUrl(display.quoteUrl))}" target="_blank" rel="noopener noreferrer">${escapeHtml(display.quoteLabel)}</a></div>`
                    : "";

                return `<article class="schedule-job-card${showRescheduleBox ? " is-rescheduling" : ""}">
                    <div class="schedule-job-head">
                        <span class="mini-tag ${st.cls}">${escapeHtml(st.label)}</span>
                        <span class="schedule-job-time" ${timeTitle ? `title="${escapeHtml(timeTitle)}"` : ""}>${escapeHtml(timePrefix)}</span>
                    </div>
                    <div class="schedule-job-text" ${display.text ? "data-i18n-ignore" : ""}>${display.text ? escapeHtml(display.text) : "<span class=\"user-handle-muted\">图片推文</span>"}</div>
                    ${display.modeLabel ? `<div class="schedule-job-alert">${escapeHtml(display.modeLabel)}</div>` : ""}
                    ${jobQuoteHtml}
                    ${mediaHtml}
                    ${alertHtml}
                    ${rescheduleBoxHtml}
                    <div class="schedule-job-actions">${actions.join("")}</div>
                </article>`;
            })
            .join("");
        if (focusedJobId && focusedJobId === state.rescheduleEditingJobId) {
            listEl.querySelector(`[data-resched-input="${CSS.escape(focusedJobId)}"]`)?.focus();
        }
    }

    function openConfirmResendModal(job) {
        if (!job) return;
        state.pendingConfirmJob = job;
        $("confirmResendReason").textContent = job.lastError || "该推文已超过预定发布窗口，需要你二次确认后再发送。";
        $("confirmResendReason").classList.remove("is-error");
        $("confirmResendMeta").textContent = `原定发布时间：${formatScheduleEpochLabel(job.scheduledAtEpochMs)} · 附件 ${(job.media || []).length} 张`;
        const textPreview = $("confirmResendTextPreview");
        const hasOriginalText = Boolean(job.text && job.text.trim());
        textPreview.toggleAttribute("data-i18n-ignore", hasOriginalText);
        textPreview.textContent = hasOriginalText ? job.text : "（无正文，仅图片推文）";

        const uncertainBox = $("confirmResendUncertainBox");
        uncertainBox.hidden = !job.uncertainDelivery;
        const profLink = $("confirmResendProfileLink");
        if (profLink && state.authStatus?.user?.username) {
            profLink.href = `https://x.com/${encodeURIComponent(state.authStatus.user.username)}`;
        }

        const mediaBox = $("confirmResendMediaPreview");
        if (job.media && job.media.length) {
            mediaBox.hidden = false;
            mediaBox.innerHTML = job.media
                .map(
                    (a) => `<img data-i18n-ignore src="/api/scheduled-tweets/${encodeURIComponent(job.id)}/media/${encodeURIComponent(a.assetId)}" alt="${escapeHtml(a.fileName || "image")}" />`
                )
                .join("");
        } else {
            mediaBox.hidden = true;
            mediaBox.innerHTML = "";
        }
        syncConfirmResendSubmitState();
        const resendModal = $("confirmResendModal");
        resendModal.hidden = false;
        syncBodyModalLock();
        if (typeof onModalOpened === "function") onModalOpened("confirmResendModal", resendModal);
    }

    function closeConfirmResendModal(fromPopstate = false) {
        const modal = document.getElementById("confirmResendModal");
        if (modal) modal.hidden = true;
        state.pendingConfirmJob = null;
        syncBodyModalLock();
        if (typeof onModalClosed === "function") onModalClosed("confirmResendModal", fromPopstate);
    }

    function refreshSchedulePollingState() {
        clearInterval(state.schedulePollTimerId);
        state.schedulePollTimerId = null;
        if (!isLoggedIn() || document.hidden) return;
        const hasFastWatch =
            state.watchedResendIds.size > 0 ||
            (state.scheduleJobs || []).some((j) => j.status === "RESEND_QUEUED" || j.status === "SENDING");
        if (!hasFastWatch && state.activeTab !== "schedule") return;
        const intervalMs = hasFastWatch ? 2500 : 15000;
        state.schedulePollTimerId = setInterval(() => {
            if (!isLoggedIn() || document.hidden) {
                clearInterval(state.schedulePollTimerId);
                state.schedulePollTimerId = null;
                return;
            }
            loadScheduledTweets(true);
        }, intervalMs);
    }

    function setPublishAuthError(message, reveal = false) {
        state.schedulePublishAuthError = String(message || "");
        syncScheduleAuthBanner();
        const error = document.getElementById("schedulePublishAuthError");
        if (reveal && state.schedulePublishAuthError && error) error.scrollIntoView({ block: "nearest" });
    }

    function setScheduleComposerError(message, reveal = false, options = {}) {
        const error = document.getElementById("scheduleComposeError");
        const list = document.getElementById("scheduleDraftItemsList");
        list?.querySelectorAll("[data-composer-invalid]").forEach((input) => {
            input.removeAttribute("data-composer-invalid");
            input.removeAttribute("aria-invalid");
            if (input.getAttribute("aria-describedby") === "scheduleComposeError") input.removeAttribute("aria-describedby");
        });
        state.scheduleComposerErrorDraftIdx = Number.isInteger(options.draftIndex) ? options.draftIndex : null;
        state.scheduleComposerErrorField = options.field || "";
        const text = String(message || "");
        if (error) { error.textContent = text; error.hidden = !text; }
        let input = null;
        if (text && state.scheduleComposerErrorDraftIdx !== null) {
            const attribute = options.field === "time" ? "data-draft-time" : "data-draft-textarea";
            input = list?.querySelector(`[${attribute}="${state.scheduleComposerErrorDraftIdx}"]`);
            if (input) {
                input.setAttribute("aria-invalid", "true");
                input.setAttribute("aria-describedby", "scheduleComposeError");
                input.setAttribute("data-composer-invalid", "true");
            }
        }
        if (reveal && text) (input || error)?.scrollIntoView({ block: "nearest" });
    }

    function clearScheduleComposeErrorForDraft(idx) {
        if (state.scheduleComposerErrorDraftIdx === null || state.scheduleComposerErrorDraftIdx === idx) {
            setScheduleComposerError("");
        }
    }

    function setScheduleQueueFeedback(message, isError = false, reveal = false) {
        const el = document.getElementById("scheduleQueueFeedback");
        if (!el) return;
        el.textContent = String(message || "");
        el.hidden = !message;
        el.classList.toggle("is-error", Boolean(isError));
        el.setAttribute("role", isError ? "alert" : "status");
        if (reveal && message) el.scrollIntoView({ block: "nearest" });
    }

    function setScheduleActionError(message, reveal = false) {
        state.scheduleActionError = String(message || "");
        setScheduleQueueFeedback(state.scheduleActionError, Boolean(message), reveal);
    }

    function syncScheduleQueueState() {
        clearTimeout(state.scheduleFeedbackTimerId);
        state.scheduleFeedbackTimerId = null;
        const button = document.getElementById("refreshScheduleListBtn");
        const loading = Boolean(state.scheduleAbortController);
        const remaining = Math.max(0, Math.ceil((state.scheduleRetryNotBeforeEpochMs - Date.now()) / 1000));
        if (button) {
            button.disabled = loading || remaining > 0;
            button.textContent = loading ? "刷新中…" : remaining > 0 ? `等待 ${remaining} 秒`
                : state.scheduleListError ? "重试" : "刷新";
            button.setAttribute("aria-busy", String(loading));
        }
        if (state.scheduleActionError) {
            setScheduleQueueFeedback(state.scheduleActionError, true);
        } else if (loading) {
            setScheduleQueueFeedback(!state.scheduleHasLoaded ? "正在读取定时任务…" : "");
        } else if (remaining > 0) {
            setScheduleQueueFeedback(`${state.scheduleListError || "服务繁忙"}，${remaining} 秒后可刷新。`, true);
        } else if (state.scheduleListError) {
            setScheduleQueueFeedback(state.scheduleListError, true);
        } else {
            setScheduleQueueFeedback("");
        }
        if (remaining > 0 && !document.hidden && state.activeTab === "schedule") {
            const isCurrent = captureUserScope();
            state.scheduleFeedbackTimerId = setTimeout(() => {
                if (isCurrent()) syncScheduleQueueState();
            }, 1000);
        }
    }

    async function loadScheduledTweets(silent = false, forceRefresh = false) {
        if (!isLoggedIn() || state.isLoggingOut) return false;
        if (!silent) {
            state.scheduleManualRefresh = true;
            state.scheduleActionError = "";
        }
        if (Date.now() < state.scheduleRetryNotBeforeEpochMs || (state.scheduleAbortController && !forceRefresh)) {
            syncScheduleQueueState();
            return false;
        }
        if (state.scheduleAbortController) state.scheduleAbortController.abort();
        const generation = ++state.scheduleRequestGeneration;
        const isCurrentUser = captureUserScope();
        const isCurrent = () => generation === state.scheduleRequestGeneration && isCurrentUser();
        const controller = new AbortController();
        state.scheduleAbortController = controller;
        syncScheduleQueueState();
        const timeoutId = setTimeout(() => controller.abort(), 30000);
        try {
            const resp = await fetch("/api/scheduled-tweets", { cache: "no-store", signal: controller.signal });
            const body = await safeParseJson(resp);
            if (!isCurrent()) return;
            if (resp.status === 401) {
                await fetchServerAndAuthStatus();
                return;
            }
            if (!resp.ok) {
                if (resp.status === 429 || resp.status === 503) {
                    const retryAfter = resp.headers?.get?.("Retry-After") || "";
                    const seconds = Number(retryAfter);
                    const delayMs = retryAfter && Number.isFinite(seconds)
                        ? seconds * 1000 : Date.parse(retryAfter) - Date.now();
                    state.scheduleRetryNotBeforeEpochMs = Date.now() +
                        (Number.isFinite(delayMs) ? Math.max(1000, Math.min(delayMs, 86400000)) : 15000);
                }
                if (!silent || state.scheduleManualRefresh || !state.scheduleHasLoaded
                    || resp.status === 429 || resp.status === 503) {
                    state.scheduleListError = body.message || `读取失败（HTTP ${resp.status}），请重试。`;
                }
                return false;
            }
            if (!Array.isArray(body.items)) throw new Error("任务列表响应异常，请重试。");
            state.scheduleJobs = body.items;
            state.scheduleHasLoaded = true;
            state.scheduleListError = "";
            state.scheduleRetryNotBeforeEpochMs = 0;
            state.schedulePendingCount = Number(body.pendingCount || 0);
            state.scheduleOverdueCount = Number(body.overdueConfirmCount || 0);
            if (body.timezone) state.scheduleServerTz = body.timezone;
            if (state.authStatus) {
                state.authStatus.writeAuthorized = Boolean(body.writeAuthorized);
                state.authStatus.mediaWriteAuthorized = Boolean(body.mediaWriteAuthorized);
                if (typeof body.offlineRefreshable === "boolean") {
                    state.authStatus.offlineRefreshable = body.offlineRefreshable;
                }
            }
            state.scheduleAccess = {
                allowed: body.scheduleAccessAllowed !== false,
                followsAuthor: Boolean(body.followsAuthor),
                authorInteractionRank: body.authorInteractionRank ?? null,
                reason: body.scheduleLockedReason || ""
            };

            for (const watchedId of Array.from(state.watchedResendIds)) {
                const match = state.scheduleJobs.find((j) => j.id === watchedId);
                if (!match) {
                    state.watchedResendIds.delete(watchedId);
                } else if (match.status === "PUBLISHED") {
                    state.watchedResendIds.delete(watchedId);
                    showToast("定时推文已自动补发成功！");
                } else if (match.status === "FAILED" || match.status === "OVERDUE_CONFIRM_REQUIRED") {
                    state.watchedResendIds.delete(watchedId);
                    showError(match.lastError || "定时推文补发未完成，请核对后重试");
                }
            }

            renderScheduleQuickSlots();
            syncScheduleAuthBanner();
            syncScheduleDraftConflictUI();
            updateScheduleBadgesAndBanner();
            renderScheduledTweetsList();
            refreshSchedulePollingState();
            return true;
        } catch (err) {
            if (isCurrent() && (!silent || state.scheduleManualRefresh || !state.scheduleHasLoaded)) {
                state.scheduleListError = err.name === "AbortError" ? "读取超时，请重试。" : err.message || "暂时无法读取任务，请重试。";
            }
            return false;
        } finally {
            clearTimeout(timeoutId);
            if (generation === state.scheduleRequestGeneration) {
                state.scheduleAbortController = null;
                state.scheduleManualRefresh = false;
                syncScheduleQueueState();
            }
        }
    }

    const MAX_SCHEDULE_TEXT_LENGTH = 25000;

    function scheduleTextLengthError(index, count) {
        return `${count > 1 ? `第 ${index + 1} 条` : "当前"}正文超过本站排期上限 ${MAX_SCHEDULE_TEXT_LENGTH} 个字符，请缩短后提交。当前 X 账号的长文权限未验证。`;
    }

    async function submitCreateScheduledTweet() {
        if (!isLoggedIn() || state.isLoggingOut || state.scheduleMutations.has("create")) return;
        if (state.scheduleDraftRestoring) {
            setScheduleComposerError("图片正在恢复，请稍候再提交。", true);
            return;
        }
        setScheduleComposerError("");
        if (hasScheduleAccessDecision() && !isScheduleEligible()) {
            syncScheduleAuthBanner();
            setScheduleComposerError(getScheduleLockedReason(), true);
            return;
        }
        ensureScheduleDraftInitialized();
        const items = state.scheduleDraftItems || [];
        items.forEach((it, idx) => {
            const taEl = document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${idx}"]`);
            if (taEl) it.text = taEl.value || "";
            const tiEl = document.querySelector(`#scheduleDraftItemsList [data-draft-time="${idx}"]`);
            if (tiEl) it.scheduledAtValue = tiEl.value || "";
        });

        for (let i = 0; i < items.length; i++) {
            const text = items[i].text || "";
            const files = items[i].files || [];
            if (text.length > MAX_SCHEDULE_TEXT_LENGTH) {
                setScheduleComposerError(scheduleTextLengthError(i, items.length),
                    true, { draftIndex: i, field: "text" });
                return;
            }
            if (!text.trim() && files.length === 0) {
                setScheduleComposerError(
                    items.length > 1 ? `第 ${i + 1} 条还没有内容，请输入正文或添加图片。`
                        : "请输入正文或添加图片。",
                    true, { draftIndex: i, field: "text" }
                );
                document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${i}"]`)?.focus();
                return;
            }
        }

        const draftEpochs = items.map((it) => parseDraftItemEpochMs(it));
        const validation = validateBatchScheduleEpochs(
            draftEpochs,
            getQueuedScheduledEpochsMs(),
            Date.now(),
            getRequiredScheduleIntervalMs()
        );
        syncScheduleDraftConflictUI();
        if (!validation.ok) {
            setScheduleComposerError(validation.error || "请检查发布时间。", true,
                { draftIndex: validation.conflictIndices?.[0], field: "time" });
            return;
        }

        const anyHasFiles = items.some((it) => (it.files || []).length > 0);
        const needsWrite = !state.authStatus?.writeAuthorized;
        const needsMedia = anyHasFiles && !state.authStatus?.mediaWriteAuthorized;
        if (needsWrite || needsMedia) {
            await triggerXPublishAuth();
            return;
        }
        if (!state.composerClientRequestId) {
            rotateComposerRequestId();
        }

        const mutation = beginScheduleMutation("create");
        if (!mutation) return;
        const submittedRequestId = state.composerClientRequestId;
        const submittedIds = items.length === 1 ? [submittedRequestId]
            : items.map((_, idx) => `${submittedRequestId}_${idx}`);
        const btn = $("createScheduleBtn");
        btn.disabled = true;
        btn.textContent = "提交中…";
        try {
            let resp, body;
            if (!anyHasFiles && items.length === 1) {
                const payload = {
                    text: items[0].text || "",
                    scheduledAtEpochMs: draftEpochs[0],
                    clientRequestId: state.composerClientRequestId
                };
                if (items[0].sourceRef?.assistantDraftId) {
                    payload.assistantDraftId = items[0].sourceRef.assistantDraftId;
                    payload.assistantDraftVersion = Number(items[0].sourceRef.assistantDraftVersion || 0);
                }
                if (items[0].sourceRef?.rewriteSource) payload.rewriteSource = items[0].sourceRef.rewriteSource;
                if (items[0].quoteTweetId) {
                    payload.quoteTweetId = items[0].quoteTweetId;
                    if (items[0].quoteTweetUrl) payload.quoteTweetUrl = items[0].quoteTweetUrl;
                }
                ({ resp, body } = await requestJsonWithin("/api/scheduled-tweets", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                }, 60000));
            } else if (!anyHasFiles) {
                const metas = items.map((it, idx) => ({
                    text: it.text || "",
                    scheduledAtEpochMs: draftEpochs[idx],
                    clientRequestId: `${state.composerClientRequestId}_${idx}`,
                    assistantDraftId: it.sourceRef?.assistantDraftId || null,
                    assistantDraftVersion: it.sourceRef?.assistantDraftVersion || null,
                    rewriteSource: it.sourceRef?.rewriteSource || null,
                    quoteTweetId: it.quoteTweetId || null,
                    quoteTweetUrl: it.quoteTweetUrl || null
                }));
                ({ resp, body } = await requestJsonWithin("/api/scheduled-tweets/batch", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ items: metas })
                }, 60000));
            } else if (items.length === 1) {
                const fd = new FormData();
                fd.append("text", items[0].text || "");
                fd.append("scheduledAtEpochMs", String(draftEpochs[0]));
                fd.append("clientRequestId", state.composerClientRequestId);
                if (items[0].sourceRef?.assistantDraftId) {
                    fd.append("assistantDraftId", items[0].sourceRef.assistantDraftId);
                    fd.append("assistantDraftVersion", String(items[0].sourceRef.assistantDraftVersion || 0));
                }
                if (items[0].sourceRef?.rewriteSource) fd.append("rewriteSource", JSON.stringify(items[0].sourceRef.rewriteSource));
                if (items[0].quoteTweetId) {
                    fd.append("quoteTweetId", items[0].quoteTweetId);
                    if (items[0].quoteTweetUrl) fd.append("quoteTweetUrl", items[0].quoteTweetUrl);
                }
                for (const media of items[0].files || []) {
                    fd.append("files", media.file, media.name);
                }
                ({ resp, body } = await requestJsonWithin("/api/scheduled-tweets", {
                    method: "POST", body: fd
                }, 60000));
            } else {
                const fd = new FormData();
                const metas = items.map((it, idx) => ({
                    text: it.text || "",
                    scheduledAtEpochMs: draftEpochs[idx],
                    clientRequestId: `${state.composerClientRequestId}_${idx}`,
                    assistantDraftId: it.sourceRef?.assistantDraftId || null,
                    assistantDraftVersion: it.sourceRef?.assistantDraftVersion || null,
                    rewriteSource: it.sourceRef?.rewriteSource || null,
                    quoteTweetId: it.quoteTweetId || null,
                    quoteTweetUrl: it.quoteTweetUrl || null
                }));
                fd.append("itemsJson", JSON.stringify(metas));
                items.forEach((it, idx) => {
                    for (const media of it.files || []) {
                        fd.append(`files_${idx}`, media.file, media.name);
                    }
                });
                ({ resp, body } = await requestJsonWithin("/api/scheduled-tweets/batch", {
                    method: "POST", body: fd
                }, 60000));
            }
            if (!resp.ok) {
                const error = new Error(body.message || `提交未完成（HTTP ${resp.status}）`);
                error.httpStatus = resp.status;
                error.errorCode = body.error || "";
                throw error;
            }
            if (!mutation.isCurrent()) return;
            const submittedCount = items.length;
            await loadScheduledTweets(true, true);
            if (!mutation.isCurrent()) return;
            if (state.composerClientRequestId === submittedRequestId) clearScheduleComposerDraft();
            showToast(submittedCount > 1 ? `已加入队列（${submittedCount} 条）` : "已加入队列");
        } catch (err) {
            if (!mutation.isCurrent()) return;
            const uncertain = !isPreExecutionGatewayRejection(err) && (!err.httpStatus || err.httpStatus >= 500);
            if (uncertain) {
                setScheduleComposerError("暂未收到提交结果，正在核对队列；草稿已保留。", true);
                const checked = await loadScheduledTweets(true, true);
                if (!mutation.isCurrent()) return;
                const confirmed = checked && submittedIds.every((id) =>
                    state.scheduleJobs.some((job) => job.clientRequestId === id));
                if (confirmed) {
                    if (state.composerClientRequestId === submittedRequestId) clearScheduleComposerDraft();
                    setScheduleComposerError("");
                    showToast("已加入队列");
                } else {
                    setScheduleComposerError("尚未确认提交结果，请先刷新队列核对，避免重复安排；草稿已保留。", true);
                }
            } else {
                const message = err.message || "提交未完成，请重试。";
                const match = /第\s*(\d+)\s*条/.exec(message);
                const field = /时间|间隔|排期/.test(message) ? "time" : "text";
                const options = state.composerClientRequestId === submittedRequestId
                    ? { draftIndex: match ? Number(match[1]) - 1 : items.length === 1 ? 0 : undefined, field }
                    : {};
                setScheduleComposerError(message, true, options);
                if (err.httpStatus === 401 || err.httpStatus === 403) setPublishAuthError(message);
            }
        } finally {
            mutation.finish();
            if (mutation.isCurrent()) syncScheduleAuthBanner();
        }
    }

    function syncConfirmResendSubmitState() {
        const button = $("confirmResendSubmitBtn");
        if (!button) return;
        const jobId = state.pendingConfirmJob?.id;
        const pending = Boolean(jobId && (state.scheduleMutations.has("job:" + jobId)
            || state.scheduleMutations.has("batch-resend")));
        button.disabled = pending;
        button.textContent = pending ? "确认中…" : "确认补发";
        button.setAttribute("aria-busy", String(pending));
    }

    async function submitConfirmResend(job) {
        if (!job || !isLoggedIn() || state.isLoggingOut) return;
        if (hasScheduleAccessDecision() && !isScheduleEligible()) {
            $("confirmResendReason").textContent = getScheduleLockedReason();
            return;
        }
        const needsWrite = !state.authStatus?.writeAuthorized;
        const needsMedia = (job.media || []).length > 0 && !state.authStatus?.mediaWriteAuthorized;
        if (needsWrite || needsMedia) {
            closeConfirmResendModal();
            switchTab("schedule");
            document.getElementById("scheduleWriteAuthCard")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
            setPublishAuthError("发布授权尚未开通或已过期，请重新授权后再补发。", true);
            return;
        }
        const mutation = beginScheduleMutation(`job:${job.id}`);
        if (!mutation) return;
        syncConfirmResendSubmitState();
        try {
            const { resp, body } = await requestJsonWithin(`/api/scheduled-tweets/${encodeURIComponent(job.id)}/confirm-resend`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ confirmToken: job.confirmToken })
            }, 45000);
            if (!resp.ok) {
                const error = new Error(body.message || `确认未完成（HTTP ${resp.status}）`);
                error.httpStatus = resp.status;
                error.errorCode = body.error || "";
                throw error;
            }
            if (!mutation.isCurrent()) return;
            state.watchedResendIds.add(job.id);
            if (state.pendingConfirmJob?.id === job.id) closeConfirmResendModal();
            showToast("已二次确认，正在自动补发…");
            await loadScheduledTweets(true, true);
        } catch (err) {
            if (!mutation.isCurrent()) return;
            const uncertain = !isPreExecutionGatewayRejection(err) && (!err.httpStatus || err.httpStatus >= 500);
            const message = uncertain
                ? "暂未确认补发结果，请先刷新队列核对。" : err.message || "确认未完成，请重试。";
            if (state.pendingConfirmJob?.id === job.id) {
                $("confirmResendReason").textContent = message;
                $("confirmResendReason").classList.add("is-error");
                $("confirmResendReason").setAttribute("role", "alert");
            } else {
                showToast(message);
            }
            await loadScheduledTweets(true, true);
        } finally {
            mutation.finish();
            if (mutation.isCurrent()) syncConfirmResendSubmitState();
        }
    }

    async function submitBatchConfirmOverdue() {
        if (!isLoggedIn() || state.isLoggingOut || state.scheduleMutations.has("batch-resend")) return;
        if (hasScheduleAccessDecision() && !isScheduleEligible()) {
            setScheduleQueueFeedback(getScheduleLockedReason(), true, true);
            return;
        }
        const eligible = (state.scheduleJobs || []).filter(
            (j) => j.status === "OVERDUE_CONFIRM_REQUIRED" && !j.uncertainDelivery && j.confirmToken
        );
        if (!eligible.length) {
            showToast("没有可批量确认的逾期推文");
            return;
        }
        const hasAnyMedia = eligible.some((j) => (j.media || []).length > 0);
        if (!state.authStatus?.writeAuthorized || (hasAnyMedia && !state.authStatus?.mediaWriteAuthorized)) {
            switchTab("schedule");
            document.getElementById("scheduleWriteAuthCard")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
            setPublishAuthError("发布授权尚未开通或已过期，请重新授权后再补发。", true);
            return;
        }
        const hasUncertain = (state.scheduleJobs || []).some(
            (j) => j.status === "OVERDUE_CONFIRM_REQUIRED" && j.uncertainDelivery
        );
        const mutation = beginScheduleMutation("batch-resend");
        if (!mutation) return;
        const btn = $("batchConfirmOverdueBtn");
        setScheduleActionError("");
        btn.disabled = true;
        btn.textContent = "确认中…";
        try {
            const { resp, body } = await requestJsonWithin("/api/scheduled-tweets/batch-confirm-resend", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    items: eligible.map((j) => ({ id: j.id, confirmToken: j.confirmToken }))
                })
            }, 45000);
            if (!resp.ok) {
                const error = new Error(body.message || `批量确认未完成（HTTP ${resp.status}）`);
                error.httpStatus = resp.status;
                error.errorCode = body.error || "";
                throw error;
            }
            if (!mutation.isCurrent()) return;
            eligible.forEach((j) => state.watchedResendIds.add(j.id));
            showToast(
                hasUncertain
                    ? `已确认 ${eligible.length} 条逾期推文补发（状态存疑条目需单独核对后确认）`
                    : `已确认 ${eligible.length} 条逾期推文，正在自动补发…`
            );
            await loadScheduledTweets(true, true);
        } catch (err) {
            if (mutation.isCurrent()) {
                const uncertain = !isPreExecutionGatewayRejection(err) && (!err.httpStatus || err.httpStatus >= 500);
                const message = uncertain
                    ? "暂未确认批量补发结果，请刷新队列核对。" : err.message || "批量确认未完成，请重试。";
                setScheduleActionError(message, true);
            }
        } finally {
            mutation.finish();
            if (mutation.isCurrent()) {
                btn.disabled = false;
                const count = (state.scheduleJobs || []).filter((job) => job.status === "OVERDUE_CONFIRM_REQUIRED" && !job.uncertainDelivery).length;
                btn.textContent = `批量补发 (${count})`;
            }
        }
    }

    async function submitScheduledJobAction(jobId, action, button, nextMs) {
        const mutation = beginScheduleMutation(`job:${jobId}`);
        if (!mutation) return;
        const label = { reschedule: "改期", cancel: "取消", delete: "删除" }[action];
        const submittedDraftValue = state.rescheduleDraftValue;
        const submittedInteractionVersion = state.rescheduleInteractionVersion;
        setScheduleActionError("");
        button.disabled = true;
        if (action === "reschedule") setRescheduleError(jobId, "");
        try {
            const options = { method: action === "delete" ? "DELETE" : "POST" };
            if (action === "reschedule") {
                options.headers = { "Content-Type": "application/json" };
                options.body = JSON.stringify({ scheduledAtEpochMs: nextMs });
            }
            const suffix = action === "delete" ? "" : `/${action}`;
            const { resp, body } = await requestJsonWithin(`/api/scheduled-tweets/${encodeURIComponent(jobId)}${suffix}`, options, 45000);
            if (!mutation.isCurrent()) return;
            if (!resp.ok) {
                const error = new Error(body.message || `${label}未完成（HTTP ${resp.status}）`);
                error.httpStatus = resp.status;
                error.errorCode = body.error || "";
                throw error;
            }
            const editedWhileSaving = action === "reschedule" && state.rescheduleEditingJobId === jobId
                && (state.rescheduleDraftValue !== submittedDraftValue
                    || state.rescheduleInteractionVersion !== submittedInteractionVersion);
            if (state.rescheduleEditingJobId === jobId
                && (action === "delete" || (action === "reschedule" && !editedWhileSaving))) {
                state.rescheduleEditingJobId = null;
                state.rescheduleDraftValue = null;
                state.rescheduleError = "";
                state.rescheduleErrorKind = "";
                renderScheduledTweetsList();
            }
            showToast(editedWhileSaving ? "已保存上次提交的时间，当前修改尚未保存"
                : action === "reschedule" ? "已重新排期" : action === "cancel" ? "已取消该定时任务" : "已删除定时记录");
            await loadScheduledTweets(true, true);
        } catch (err) {
            if (mutation.isCurrent()) {
                const uncertain = !isPreExecutionGatewayRejection(err) && (!err.httpStatus || err.httpStatus >= 500);
                const message = uncertain ? `尚未确认${label}结果，请刷新队列核对。`
                    : err.message || label + "未完成，请重试。";
                if (action === "reschedule") {
                    if (state.rescheduleDraftValue !== submittedDraftValue
                        || state.rescheduleInteractionVersion !== submittedInteractionVersion
                        || !setRescheduleError(jobId, message, true)) {
                        showToast(message);
                    }
                } else {
                    setScheduleActionError(message, true);
                }
                if (uncertain) await loadScheduledTweets(true, true);
            }
        } finally {
            mutation.finish();
            if (mutation.isCurrent()) {
                button.disabled = false;
                if (action === "reschedule") preserveRescheduleEditor($("scheduledTweetsList"));
            }
        }
    }

    /* ==================== 事件绑定与初始化 ==================== */
    function copyTextWithFallback(text) {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
            return navigator.clipboard.writeText(text).catch(() => legacyExecCopy(text));
        }
        return legacyExecCopy(text);
    }

    function legacyExecCopy(text) {
        return new Promise((resolve, reject) => {
            const active = document.activeElement;
            const inputSelection = active && typeof active.selectionStart === "number"
                ? [active.selectionStart, active.selectionEnd, active.selectionDirection] : null;
            const selection = document.getSelection?.();
            const ranges = selection && !inputSelection
                ? Array.from({ length: selection.rangeCount }, (_, idx) => selection.getRangeAt(idx).cloneRange()) : [];
            let ta = null;
            try {
                ta = document.createElement("textarea");
                ta.value = text;
                ta.setAttribute("readonly", "readonly");
                ta.style.position = "fixed";
                ta.style.opacity = "0";
                ta.style.left = "-9999px";
                ta.style.fontSize = "16px";
                document.body.appendChild(ta);
                ta.focus({ preventScroll: true });
                ta.select();
                const ok = document.execCommand("copy");
                ok ? resolve() : reject(new Error("copy failed"));
            } catch (err) {
                reject(err);
            } finally {
                if (ta?.parentNode) ta.parentNode.removeChild(ta);
                try {
                    if (active?.isConnected) {
                        active.focus({ preventScroll: true });
                        if (inputSelection) active.setSelectionRange(...inputSelection);
                    }
                    if (selection && !inputSelection) {
                        selection.removeAllRanges();
                        ranges.forEach((range) => selection.addRange(range));
                    }
                } catch (_) {}
            }
        });
    }

    function bindEvents() {
        if ("scrollRestoration" in window.history) window.history.scrollRestoration = "manual";
        window.addEventListener("popstate", (event) => {
            if (typeof handleModalPopstate === "function" && handleModalPopstate(event)) return;
            if (!isLoggedIn()) return;
            const tabId = new URL(window.location.href).searchParams.get("tab") || "activity";
            const sameOwner = event.state?.xopsOwnerId === stableUserId(state.authStatus?.user);
            const remembered = state.tabScrollPositions[tabId];
            const scrollTop = Number.isFinite(remembered) ? remembered
                : sameOwner && Number.isFinite(event.state?.xopsScrollY) ? event.state.xopsScrollY : 0;
            switchTab(tabId, { historyMode: "none", scrollTop });
        });
        window.addEventListener("pagehide", () => { void persistScheduleDraftNow(); });
        document.addEventListener("visibilitychange", () => {
            if (document.hidden) void persistScheduleDraftNow();
            else refreshRewriteDay();
            refreshSchedulePollingState();
            if (!document.hidden && isLoggedIn() && (state.activeTab === "schedule" || state.watchedResendIds.size > 0)) {
                void loadScheduledTweets(true);
            }
        });

        // 全局捕获头像图片加载失败，回退显示首字（彻底替代内联 onerror 字符串拼接）
        document.addEventListener(
            "error",
            (e) => {
                const t = e.target;
                if (t && t.tagName === "IMG" && t.hasAttribute("data-avatar-fallback")) {
                    const canonical = t.dataset ? t.dataset.canonicalSrc : "";
                    if (canonical && String(t.src || "").startsWith("blob:") && t.dataset.retriedCanonical !== "1") {
                        t.dataset.retriedCanonical = "1";
                        const objectUrl = mediaObjectUrlMap.get(canonical);
                        if (objectUrl) URL.revokeObjectURL(objectUrl);
                        mediaObjectUrlMap.delete(canonical);
                        t.src = canonical;
                        return;
                    }
                    const fb = t.getAttribute("data-avatar-fallback") || "X";
                    const parent = t.parentElement;
                    t.remove();
                    if (parent) parent.textContent = fb;
                }
            },
            true
        );

        document.querySelectorAll("[data-tab]").forEach((btn) => {
            btn.addEventListener("click", () => switchTab(btn.dataset.tab));
        });

        $("logoHomeBtn").addEventListener("click", () => {
            if (isLoggedIn()) {
                switchTab("activity");
            }
            window.scrollTo({ top: 0, behavior: "smooth" });
        });

        ["cycleThemeBtn", "topbarThemeBtn", "gateThemeBtn"].forEach((id) => {
            const btn = document.getElementById(id);
            if (btn) {
                btn.addEventListener("click", () => applyTheme(state.themeIdx + 1));
            }
        });

        const workspaceSettings = document.getElementById("workspaceSettings");
        if (workspaceSettings) {
            document.addEventListener("click", (e) => {
                if (workspaceSettings.open && !workspaceSettings.contains(e.target)) {
                    workspaceSettings.open = false;
                }
            });
        }

        // 手机端支持点按与横向滑动扫读柱状图明细（再点取消）
        const bindTapReadout = (containerId, cellSelector, readoutId, getDefaultText) => {
            const box = document.getElementById(containerId);
            if (!box) return;
            let scrubbedInGesture = false;
            const applyCellSelection = (cell, toggleIfSame) => {
                if (!cell || !cell.dataset.tip) return;
                const wasSelected = cell.classList.contains("is-selected");
                if (wasSelected && !toggleIfSame) return;
                box.querySelectorAll(cellSelector).forEach((el) => el.classList.remove("is-selected"));
                const out = $(readoutId);
                if (wasSelected && toggleIfSame) {
                    const defTxt = getDefaultText();
                    out.textContent = defTxt;
                    out.classList.remove("is-readout");
                    out.hidden = !defTxt;
                } else {
                    cell.classList.add("is-selected");
                    out.hidden = false;
                    out.textContent = globalThis.XOpsI18n?.sourceAttribute(cell, "data-tip") ?? cell.dataset.tip;
                    out.classList.add("is-readout");
                }
            };
            box.addEventListener("touchstart", () => {
                scrubbedInGesture = false;
            }, { passive: true });
            box.addEventListener("touchmove", (e) => {
                if (!e.touches || e.touches.length !== 1) return;
                const t = e.touches[0];
                const hit = document.elementFromPoint(t.clientX, t.clientY);
                const cell = hit && hit.closest ? hit.closest(cellSelector) : null;
                if (cell && box.contains(cell)) {
                    scrubbedInGesture = true;
                    applyCellSelection(cell, false);
                }
            }, { passive: true });
            box.addEventListener("click", (e) => {
                if (scrubbedInGesture) {
                    scrubbedInGesture = false;
                    return;
                }
                const cell = e.target && e.target.closest ? e.target.closest(cellSelector) : null;
                applyCellSelection(cell, true);
            });
            box.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    const cell = e.target && e.target.closest ? e.target.closest(cellSelector) : null;
                    applyCellSelection(cell, true);
                }
            });
        };
        const mobileAlgoTextEl = document.getElementById("mobileAlgoStatusText");
        if (mobileAlgoTextEl) {
            const openTimingDiag = () => switchTab("timing");
            mobileAlgoTextEl.addEventListener("click", openTimingDiag);
            mobileAlgoTextEl.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    openTimingDiag();
                }
            });
        }
        bindTapReadout(
            "dailyTrendChart",
            ".day-col[data-tip]",
            "trendChartSubtitle",
            () => formatActivityTrendSummary(state.report)
        );
        $("refreshContentAssistantBtn")?.addEventListener("click", () => {
            void loadContentAssistant(false);
            if (state.assistantView === "materials") void loadAssistantMaterialsFeed();
        });
        document.querySelectorAll("[data-assistant-view]").forEach((button) => {
            button.addEventListener("click", () => {
                state.assistantView = button.dataset.assistantView || "assistant";
                renderContentAssistant();
                if (state.assistantView === "materials") void loadAssistantMaterialsFeed();
            });
        });
        const loadFilteredAssistantTopics = debounce(() => {
            void loadAssistantTopicPage(0);
        }, 250);
        $("assistantTopicSearch")?.addEventListener("input", (event) => {
            state.assistantTopicSearch = event.target.value || "";
            loadFilteredAssistantTopics();
        });
        $("assistantTopicStatus")?.addEventListener("change", (event) => {
            state.assistantTopicStatus = event.target.value || "";
            void loadAssistantTopicPage(0);
        });
        $("assistantTopicAuthor")?.addEventListener("change", (event) => {
            state.assistantTopicAuthor = event.target.value || "";
            void loadAssistantTopicPage(0);
        });
        $("assistantTopicTag")?.addEventListener("input", (event) => {
            state.assistantTopicTag = event.target.value || "";
            loadFilteredAssistantTopics();
        });
        $("assistantTopicSort")?.addEventListener("change", (event) => {
            state.assistantTopicSort = event.target.value || "score";
            void loadAssistantTopicPage(0);
        });
        $("assistantTopicPrevBtn")?.addEventListener("click", () =>
            loadAssistantTopicPage(state.assistantTopicOffset - 100));
        $("assistantTopicNextBtn")?.addEventListener("click", () =>
            loadAssistantTopicPage(state.assistantTopicOffset + 100));
        const loadFilteredAssistantMaterials = debounce(() => {
            void loadAssistantMaterialsFeed(0);
        }, 250);
        $("assistantMaterialSearch")?.addEventListener("input", (event) => {
            state.assistantMaterialSearch = event.target.value || "";
            loadFilteredAssistantMaterials();
        });
        $("assistantMaterialAuthor")?.addEventListener("change", (event) => {
            state.assistantMaterialAuthor = event.target.value || "";
            void loadAssistantMaterialsFeed(0);
        });
        $("assistantMaterialStatus")?.addEventListener("change", (event) => {
            state.assistantMaterialStatus = event.target.value || "";
            void loadAssistantMaterialsFeed(0);
        });
        $("assistantMaterialSort")?.addEventListener("change", (event) => {
            state.assistantMaterialSort = event.target.value || "published";
            void loadAssistantMaterialsFeed(0);
        });
        $("assistantMaterialPrevBtn")?.addEventListener("click", () =>
            void loadAssistantMaterialsFeed((state.assistantMaterialOffset || 0) - 100));
        $("assistantMaterialNextBtn")?.addEventListener("click", () =>
            void loadAssistantMaterialsFeed((state.assistantMaterialOffset || 0) + 100));
        $("assistantMaterialsList")?.addEventListener("click", async (event) => {
            const openTopic = event.target.closest("[data-assistant-open-topic]");
            if (openTopic) {
                await openAssistantCreationTopic(openTopic.dataset.assistantOpenTopic);
                return;
            }
            const research = event.target.closest("[data-assistant-research]");
            if (research) await startAssistantMaterialResearch(research);
        });
        const refreshReviewFilter = () => {
            if (state.assistantDashboard) renderAssistantReviewPanel(state.assistantDashboard);
        };
        $("assistantReviewTag")?.addEventListener("input", (event) => {
            state.assistantReviewTag = event.target.value || "";
            refreshReviewFilter();
        });
        $("assistantReviewScoreBand")?.addEventListener("change", (event) => {
            state.assistantReviewScoreBand = event.target.value || "";
            refreshReviewFilter();
        });
        $("assistantReviewDraftVersion")?.addEventListener("change", (event) => {
            state.assistantReviewDraftVersion = event.target.value || "";
            refreshReviewFilter();
        });
        $("assistantReviewSkill")?.addEventListener("change", (event) => {
            state.assistantReviewSkill = event.target.value || "";
            refreshReviewFilter();
        });
        $("contentAssistantSummary")?.addEventListener("click", (event) => {
            const viewBtn = event.target.closest("[data-assistant-summary-view]");
            if (viewBtn) {
                state.assistantView = viewBtn.dataset.assistantSummaryView || "monitor";
                renderContentAssistant();
                return;
            }
            const statusBtn = event.target.closest("[data-assistant-summary-status]");
            if (!statusBtn) return;
            const rawStatus = statusBtn.dataset.assistantSummaryStatus || "";
            const nextStatus = rawStatus && state.assistantView === "assistant" && state.assistantTopicStatus === rawStatus
                ? "" : rawStatus;
            state.assistantView = "assistant";
            state.assistantTopicStatus = nextStatus;
            if ($("assistantTopicStatus")) $("assistantTopicStatus").value = nextStatus;
            void loadAssistantTopicPage(0);
        });
        $("assistantSkillBar")?.addEventListener("click", (event) => {
            const chip = event.target.closest("[data-assistant-skill]");
            if (!chip) return;
            state.assistantSelectedSkill = chip.dataset.assistantSkill || "AUTO";
            renderAssistantSkillBar(state.assistantDashboard);
        });
        $("assistantCreationAngleInput")?.addEventListener("input", (event) => {
            state.assistantCreationAngle = event.target.value || "";
        });
        const submitAssistantLinkResearch = async (mode = "RESEARCH") => {
            const linkInput = $("assistantLinkResearchInput");
            const rawUrl = (linkInput?.value || "").trim();
            if (!rawUrl) {
                setContentAssistantError("请先粘贴需要调研的链接。");
                linkInput?.focus?.();
                return;
            }
            if (!await saveAssistantDraftEditor()) return;
            const payload = assistantCreationRequest({ url: rawUrl, mode });
            const researchBtn = $("assistantLinkResearchBtn");
            const fastBtn = $("assistantLinkFastBtn");
            if (researchBtn) researchBtn.disabled = true;
            if (fastBtn) fastBtn.disabled = true;
            try {
                const res = await runAssistantMutation("research-link", "/research-link", payload, 180000);
                if (res && res.topicId) {
                    if (linkInput) linkInput.value = "";
                    await openAssistantCreationTopic(res.topicId);
                    showToast(mode === "FAST" ? "已读取链接正文，正在生成推文…" : "已提交链接调研，正在生成推文…");
                }
            } finally {
                if (researchBtn) researchBtn.disabled = false;
                if (fastBtn) fastBtn.disabled = false;
            }
        };
        $("assistantLinkResearchForm")?.addEventListener("submit", (event) => {
            event.preventDefault();
            void submitAssistantLinkResearch("RESEARCH");
        });
        $("assistantLinkFastBtn")?.addEventListener("click", () => {
            void submitAssistantLinkResearch("FAST");
        });
        $("assistantTopicTagChips")?.addEventListener("click", (event) => {
            const chip = event.target.closest("[data-assistant-topic-tag-chip]");
            if (!chip) return;
            const clicked = chip.dataset.assistantTopicTagChip || "";
            const current = (state.assistantTopicTag || "").trim();
            const nextTag = current.toLowerCase() === clicked.toLowerCase() ? "" : clicked;
            state.assistantTopicTag = nextTag;
            if ($("assistantTopicTag")) $("assistantTopicTag").value = nextTag;
            void loadAssistantTopicPage(0);
        });
        $("assistantReviewTagChips")?.addEventListener("click", (event) => {
            const chip = event.target.closest("[data-assistant-review-tag-chip]");
            if (!chip) return;
            const clicked = chip.dataset.assistantReviewTagChip || "";
            const current = (state.assistantReviewTag || "").trim();
            const nextTag = current.toLowerCase() === clicked.toLowerCase() ? "" : clicked;
            state.assistantReviewTag = nextTag;
            if ($("assistantReviewTag")) $("assistantReviewTag").value = nextTag;
            refreshReviewFilter();
        });
        const focusAssistantDraftEditor = () => {
            const editor = $("assistantDraftEditor");
            if (!editor) return;
            editor.scrollIntoView?.({ behavior: "smooth", block: "center" });
            editor.focus?.();
        };
        $("assistantTopicList")?.addEventListener("click", async (event) => {
            const researchBtn = event.target.closest("[data-assistant-research]");
            if (researchBtn) {
                await startAssistantMaterialResearch(researchBtn);
                return;
            }
            const focusDraftBtn = event.target.closest("[data-assistant-focus-draft]");
            if (focusDraftBtn) {
                const topicId = focusDraftBtn.dataset.assistantFocusDraft || "";
                if (topicId && topicId !== state.assistantSelectedTopicId) {
                    if (!await saveAssistantDraftEditor()) return;
                    state.assistantSelectedTopicId = topicId;
                    state.assistantOrganizeMode = false;
                    renderAssistantTopics(state.assistantDashboard);
                    await loadAssistantTopicDetail(topicId);
                }
                focusAssistantDraftEditor();
                return;
            }
            if (event.target.closest("[data-source-repost-preview]")) return;
            const card = event.target.closest("[data-assistant-topic]");
            if (!card || !await saveAssistantDraftEditor()) return;
            if (state.assistantSelectedTopicId !== card.dataset.assistantTopic) {
                state.assistantOrganizeMode = false;
            }
            state.assistantSelectedTopicId = card.dataset.assistantTopic;
            renderAssistantTopics(state.assistantDashboard);
            await loadAssistantTopicDetail(state.assistantSelectedTopicId);
        });
        $("assistantTopicList")?.addEventListener("keydown", (event) => {
            if (event.key !== "Enter" && event.key !== " ") return;
            const card = event.target?.closest?.("[data-assistant-topic]");
            if (!card || event.target !== card) return;
            event.preventDefault();
            card.click();
        });
        const submitAssistantSources = async (rawInput, explicitTier) => {
            const value = String(rawInput || "").trim();
            if (!value) return null;
            const tierVal = Number(explicitTier ?? $("assistantSourceTier")?.value ?? 5);
            const payload = [1, 5, 30].includes(tierVal)
                ? { input: value, intervalMinutes: tierVal }
                : { input: value };
            const result = await runAssistantMutation("add-source", "/sources", payload);
            if (!result) return null;
            if (typeof result.addedCount === "number") {
                const errList = Array.isArray(result.errors) ? result.errors : [];
                if (errList.length) {
                    showToast(`已添加 ${result.addedCount} 个，${errList.length} 个失败`);
                    setContentAssistantError(errList.map((e) => `${e.input}: ${e.error}`).join("；"));
                } else {
                    showToast(`已添加 ${result.addedCount} 个监听作者（${tierVal} 分钟档）`);
                }
            } else {
                showToast(`已添加监听 @${result.username || value.replace(/^@/, "")}（${result.intervalMinutes || tierVal} 分钟档）`);
            }
            return result;
        };
        $("assistantAddSourceForm")?.addEventListener("submit", async (event) => {
            event.preventDefault();
            const input = $("assistantSourceInput");
            const value = input.value.trim();
            if (!value) return;
            if (await submitAssistantSources(value, $("assistantSourceTier")?.value)) input.value = "";
        });
        $("assistantSuggestedPeers")?.addEventListener("click", async (event) => {
            const btn = event.target.closest("[data-assistant-add-suggested]");
            if (!btn) return;
            await submitAssistantSources(btn.dataset.assistantAddSuggested, $("assistantSourceTier")?.value);
        });
        const collectSourceAutoRepostPayload = (row, sourceId) => collectSourceAutoRepostOverrides(row, sourceId);
        document.addEventListener("click", (event) => {
            const previewBtn = event.target?.closest?.("[data-source-repost-preview]");
            if (!previewBtn) return;
            event.preventDefault();
            event.stopPropagation();
            const sourceKey = previewBtn.dataset.sourceRepostPreview || "";
            const materialId = previewBtn.dataset.repostPreviewMaterialId || "";
            const row = previewBtn.closest("[data-source-row]");
            const overrides = row ? collectSourceAutoRepostOverrides(row, row.dataset.sourceRow || sourceKey) : {};
            openRepostPreviewModal(sourceKey, { materialId, overrides, filterMode: previewBtn.dataset.sourcePreviewMode });
        });
        $("assistantSourcesList")?.addEventListener("change", async (event) => {
            const tierSelect = event.target.closest("[data-assistant-source-tier]");
            if (tierSelect) {
                const sourceId = tierSelect.dataset.assistantSourceTier;
                const intervalMinutes = Number(tierSelect.value);
                const updated = await runAssistantMutation("source-tier",
                    `/sources/${encodeURIComponent(sourceId)}/config`, { intervalMinutes });
                if (updated) showToast(`已切换为 ${intervalMinutes} 分钟采集档`);
                return;
            }
            const autoToggle = event.target.closest("[data-source-autorepost-toggle]");
            if (autoToggle) {
                const sourceId = autoToggle.dataset.sourceAutorepostToggle;
                const enabled = Boolean(autoToggle.checked);
                const existing = (state.assistantDashboard?.sources || []).find((s) => s.id === sourceId) || {};
                const nextMode = existing.autoRepostTextMode === "TEMPLATE" && !existing.autoRepostTemplate
                    ? "AI" : (existing.autoRepostTextMode || "AI");
                if (document.activeElement && $("assistantSourcesList")?.contains(document.activeElement)) {
                    document.activeElement.blur();
                }
                const updated = await runAssistantMutation("source-autorepost",
                    `/sources/${encodeURIComponent(sourceId)}/config`,
                    { autoRepostEnabled: enabled, autoRepostTextMode: nextMode });
                if (updated) showToast(enabled ? "已开启高热度自动引用转推" : "已关闭自动引用转推");
                return;
            }
            const rewriteToggle = event.target.closest("[data-source-autorewrite-toggle]");
            if (rewriteToggle) {
                const sourceId = rewriteToggle.dataset.sourceAutorewriteToggle;
                const enabled = Boolean(rewriteToggle.checked);
                rewriteToggle.disabled = true;
                if (document.activeElement && $("assistantSourcesList")?.contains(document.activeElement)) {
                    document.activeElement.blur();
                }
                const updated = await runAssistantMutation("source-autorewrite",
                    `/sources/${encodeURIComponent(sourceId)}/config`,
                    { autoRewriteEnabled: enabled });
                rewriteToggle.disabled = false;
                if (updated) showToast(enabled ? "已开启高流量纯文本洗稿发送" : "已关闭洗稿发送");
                else rewriteToggle.checked = (state.assistantDashboard?.sources || []).find(s => s.id === sourceId)?.autoRewriteEnabled === true;
                return;
            }
            const modeSelect = event.target.closest("[data-source-autorepost-mode]");
            if (modeSelect) {
                const sourceId = modeSelect.dataset.sourceAutorepostMode;
                const mode = modeSelect.value;
                if (mode === "TEMPLATE") {
                    const src = (state.assistantDashboard?.sources || []).find((s) => s.id === sourceId);
                    if (src) {
                        src.autoRepostTextMode = "TEMPLATE";
                        if (document.activeElement) document.activeElement.blur();
                        renderAssistantMonitor(state.assistantDashboard);
                    }
                    return;
                }
                if (document.activeElement) document.activeElement.blur();
                await runAssistantMutation("source-autorepost-mode",
                    `/sources/${encodeURIComponent(sourceId)}/config`, { autoRepostTextMode: "AI" });
            }
        });
        $("assistantSourcesList")?.addEventListener("click", async (event) => {
            const saveBtn = event.target.closest("[data-source-autorepost-save]");
            if (saveBtn) {
                const sourceId = saveBtn.dataset.sourceAutorepostSave;
                const row = saveBtn.closest("[data-source-row]");
                const payload = collectSourceAutoRepostPayload(row, sourceId);
                if (document.activeElement && $("assistantSourcesList")?.contains(document.activeElement)) {
                    document.activeElement.blur();
                }
                const updated = await runAssistantMutation("source-autorepost-save",
                    `/sources/${encodeURIComponent(sourceId)}/config`, payload);
                if (updated) showToast("已保存自动转推规则");
                return;
            }
            const button = event.target.closest("[data-assistant-source-action]");
            if (!button) return;
            const action = button.dataset.assistantSourceAction;
            void runAssistantMutation("source-" + action,
                `/sources/${encodeURIComponent(button.dataset.sourceId)}/${action}`, {});
        });
        $("assistantPiAgentBar")?.addEventListener("click", async (event) => {
            const btn = event.target.closest("#verifyPiAgentBtn");
            if (!btn) return;
            const res = await runAssistantMutation("verify-pi-agent", "/pi-agent/verify", {});
            if (res) {
                showToast(res.ready ? "ChatGPT OAuth 授权已就绪" : (res.message || "尚未完成 ChatGPT 登录"));
            }
        });
        $("assistantAutoRepostFeed")?.addEventListener("click", async (event) => {
            const verify = event.target.closest("[data-assistant-verify]");
            if (verify) { await verifyAssistantPublication(verify); return; }
            const retryBtn = event.target.closest("[data-autorepost-retry]");
            if (!retryBtn) return;
            const id = (retryBtn.dataset.autorepostRetry || "").trim();
            if (!id) return;
            const mode = retryBtn.dataset.publishMode;
            if (mode !== "REPOST" && mode !== "REWRITE") return;
            const reassess = mode === "REWRITE" && retryBtn.dataset.reassess === "true";
            const res = await runAssistantMutation("autorepost-retry", `/auto-reposts/${encodeURIComponent(id)}/retry?mode=${mode}${reassess ? "&reassess=true" : ""}`, {});
            if (res) showToast(mode === "REWRITE" ? "已重新提交改写稿" : "已重新提交自动引用转推");
        });
        $("assistantHistoryStatus")?.addEventListener("click", async (event) => {
            const retryBtn = event.target.closest("[data-assistant-history-retry]");
            if (!retryBtn) return;
            const res = await runAssistantMutation("history-retry", "/history/retry", {});
            if (res && state.assistantDashboard) {
                state.assistantDashboard.history = res;
                if ($("assistantHistoryStatus")) {
                    $("assistantHistoryStatus").innerHTML = renderAssistantHistory(res);
                }
                showToast("已触发历史归档手动重试");
            }
        });
        const markAssistantEdit = (event) => {
            const field = event.currentTarget;
            field.dataset.editRevision = String(Number(field.dataset.editRevision || 0) + 1);
        };
        $("assistantAdvancedSettings")?.addEventListener("toggle", (event) => {
            event.currentTarget.dataset.userToggled = "1";
        });
        $("applyAssistantPresetBtn")?.addEventListener("click", () => {
            const dashboard = state.assistantDashboard || {};
            const selectedProvider = $("assistantProvider")?.value;
            const provider = String(selectedProvider || dashboard.settings?.provider || dashboard.providerDefaults?.provider || "chatgpt").toLowerCase();
            const preset = provider === "chatgpt"
                ? { model: "gpt-6.2-astra low", protocol: "RESPONSES", contextTokens: 200000, label: "ChatGPT OAuth" }
                : (provider === "openai"
                    ? { model: "gpt-6.2-sol", protocol: "RESPONSES", contextTokens: 200000, label: "OpenAI API" }
                    : (provider === "google"
                        ? { model: "gemini-4-neon", protocol: "RESPONSES", contextTokens: 1000000, label: "Google Gemini API" }
                        : (provider === "deepseek"
                            ? { model: "deepseek-chat", protocol: "CHAT_COMPLETIONS", contextTokens: 128000, label: "DeepSeek" }
                            : (provider === "minimax"
                                ? { model: "MiniMax-M2.7", protocol: "MESSAGES", contextTokens: 200000, label: "MiniMax" }
                                : { model: "gpt-6.2-sol", protocol: "RESPONSES", contextTokens: 128000, label: "CommandCode" }))));
            if ($("assistantModel")) $("assistantModel").value = preset.model;
            if ($("assistantProtocol")) $("assistantProtocol").value = preset.protocol;
            if ($("assistantContextTokens")) $("assistantContextTokens").value = String(preset.contextTokens);
            const form = $("assistantSettingsForm");
            if (form) form.dataset.editRevision = String(Number(form.dataset.editRevision || 0) + 1);
            showToast(`已填入 ${preset.label} 推荐预设`);
        });
        $("assistantSettingsForm")?.addEventListener("input", markAssistantEdit);
        $("assistantSettingsForm")?.addEventListener("change", markAssistantEdit);
        $("assistantStyleRevisionInput")?.addEventListener("input", markAssistantEdit);
        $("assistantPreferencesInput")?.addEventListener("input", markAssistantEdit);
        $("assistantPostPolishPrompt")?.addEventListener("input", markAssistantEdit);
        $("assistantSettingsForm")?.addEventListener("submit", async (event) => {
            event.preventDefault();
            const form = event.currentTarget;
            const version = form.dataset.version;
            const editRevision = form.dataset.editRevision;
            if (version === undefined) return;
            const prevSettings = state.assistantDashboard?.settings || {};
            const prevReady = state.assistantDashboard?.capabilities?.ready === true;
            const payload = {
                version: Number(version),
                provider: $("assistantProvider") ? $("assistantProvider").value : String(prevSettings.provider || "commandcode"),
                enabled: $("assistantEnabled").checked,
                collectionEnabled: $("assistantCollectionEnabled").checked,
                modelEnabled: $("assistantModelEnabled").checked,
                intervalMinutes: Number($("assistantInterval").value),
                valueWeight: Number($("assistantValueWeight").value),
                threshold: Number($("assistantThreshold").value),
                model: $("assistantModel").value.trim(),
                protocol: $("assistantProtocol").value,
                contextTokens: Number($("assistantContextTokens").value),
                researchModel: $("assistantResearchModel").value.trim(),
                researchProtocol: $("assistantResearchProtocol").value,
                researchContextTokens: Number($("assistantResearchContextTokens").value)
            };
            const collModel = ($("assistantCollectionModel")?.value || "").trim() || "MiniMax-M2.7";
            const srchModel = ($("assistantSearchModel")?.value || "").trim() || "deepseek-chat";
            const genCfg = readStageModelControls("generation");
            const repostCfg = readRepostSettingsComposedModel();
            payload.moduleConfigs = {
                collection: { provider: "minimax", model: collModel },
                search: { provider: "deepseek", model: srchModel },
                generation: { provider: genCfg.provider, model: genCfg.model },
                repost: { provider: repostCfg.provider, model: repostCfg.model }
            };
            const apiKeys = {};
            const mmKey = ($("assistantMinimaxApiKey")?.value || "").trim();
            const dsKey = ($("assistantDeepseekApiKey")?.value || "").trim();
            const oaKey = ($("assistantOpenaiApiKey")?.value || "").trim();
            const ggKey = ($("assistantGoogleApiKey")?.value || "").trim();
            if (mmKey) apiKeys.minimax = mmKey;
            if (dsKey) apiKeys.deepseek = dsKey;
            if (oaKey) apiKeys.openai = oaKey;
            if (ggKey) apiKeys.google = ggKey;
            if (Object.keys(apiKeys).length) payload.apiKeys = apiKeys;
            const modelConfigChanged =
                payload.provider !== String(prevSettings.provider || "") ||
                payload.model !== String(prevSettings.model || "") ||
                payload.protocol !== String(prevSettings.protocol || "RESPONSES") ||
                payload.contextTokens !== Number(prevSettings.contextTokens ?? 0) ||
                payload.researchModel !== String(prevSettings.researchModel || "") ||
                payload.researchProtocol !== String(prevSettings.researchProtocol || "RESPONSES") ||
                payload.researchContextTokens !== Number(prevSettings.researchContextTokens ?? 0) ||
                payload.modelEnabled !== (prevSettings.modelEnabled !== false);
            const saved = await runAssistantMutation("settings", "/settings", payload);
            if (!saved) return;
            if ($("assistantMinimaxApiKey")) $("assistantMinimaxApiKey").value = "";
            if ($("assistantDeepseekApiKey")) $("assistantDeepseekApiKey").value = "";
            if ($("assistantOpenaiApiKey")) $("assistantOpenaiApiKey").value = "";
            if ($("assistantGoogleApiKey")) $("assistantGoogleApiKey").value = "";
            showToast("设置已保存");
            state.assistantGenerationTargetPinned = false;
            const savedGenSplit = splitModelAndEffort(genCfg.model);
            syncGenerationTargetSelects(`${genCfg.provider}|${savedGenSplit.modelId}`, savedGenSplit.effort);
            const dashboard = state.assistantDashboard;
            if (saved.version >= (dashboard.settings?.version ?? 0)) dashboard.settings = saved;
            if (form.dataset.version === version) form.dataset.version = String(saved.version);
            if (form.dataset.editRevision === editRevision) {
                delete form.dataset.editRevision;
                renderAssistantMonitor(dashboard);
            }
            const shouldVerify = payload.modelEnabled && Boolean(payload.model) && Number(payload.contextTokens) > 0
                && (modelConfigChanged || !prevReady);
            if (shouldVerify) {
                await runAssistantMutation("verify", "/capabilities/verify", { clientRequestId: newClientRequestId() }, 180000);
            }
        });
        $("verifyAssistantCapabilityBtn")?.addEventListener("click", () => {
            void runAssistantMutation("verify", "/capabilities/verify", { clientRequestId: newClientRequestId() }, 180000);
        });
        $("refreshAssistantStyleBtn")?.addEventListener("click", () => {
            void runAssistantMutation("style", "/style/refresh", { clientRequestId: newClientRequestId() }, 180000);
        });
        $("saveAssistantStyleBtn")?.addEventListener("click", async () => {
            const input = $("assistantStyleRevisionInput");
            if (!input) return;
            const profile = input.value.trim();
            if (!profile) {
                setContentAssistantError("风格概括不能为空。");
                return;
            }
            const version = input.dataset.version;
            const editRevision = input.dataset.editRevision;
            const payload = { profile };
            if (version !== undefined && Number(version) > 0) payload.version = Number(version);
            const saved = await runAssistantMutation("style-revise", "/style", payload);
            if (!saved) return;
            const dashboard = state.assistantDashboard;
            if (dashboard && saved.version >= (dashboard.style?.version ?? 0)) dashboard.style = { ...dashboard.style, ...saved };
            input.dataset.version = String(saved.version ?? 0);
            if (input.dataset.editRevision === editRevision) {
                delete input.dataset.editRevision;
                if (dashboard) renderAssistantMonitor(dashboard);
            }
        });
        $("saveAssistantPreferencesBtn")?.addEventListener("click", async () => {
            const input = $("assistantPreferencesInput");
            const version = input.dataset.version;
            const editRevision = input.dataset.editRevision;
            if (version === undefined) return;
            const saved = await runAssistantMutation("preferences", "/preferences", {
                text: input.value,
                version: Number(version)
            });
            if (!saved) return;
            const dashboard = state.assistantDashboard;
            if (saved.version >= (dashboard.preferences?.version ?? 0)) dashboard.preferences = saved;
            if (input.dataset.version === version) input.dataset.version = String(saved.version);
            if (input.dataset.editRevision === editRevision) {
                delete input.dataset.editRevision;
                renderAssistantMonitor(dashboard);
            }
        });
        $("resetAssistantPreferencesBtn")?.addEventListener("click", async () => {
            const input = $("assistantPreferencesInput");
            const version = input?.dataset.version;
            const payload = version !== undefined ? { version: Number(version) } : {};
            const saved = await runAssistantMutation("preferences-reset", "/preferences/reset", payload);
            if (!saved) return;
            const dashboard = state.assistantDashboard;
            if (dashboard) dashboard.preferences = saved;
            if (input) {
                input.value = "";
                input.dataset.version = String(saved.version ?? 0);
                delete input.dataset.editRevision;
            }
            if (dashboard) renderAssistantMonitor(dashboard);
        });
        $("saveAssistantPolishPromptBtn")?.addEventListener("click", async () => {
            const chk = $("assistantPostPolishEnabled");
            const input = $("assistantPostPolishPrompt");
            try {
                const saved = await persistOwnerPolishPrompt(
                    chk ? chk.checked : true,
                    input ? input.value : "",
                    false
                );
                if (saved) showToast("二次改写提示词已保存");
            } catch (err) {
                setContentAssistantError(formatLocalizedFetchError(err, "保存二次改写提示词失败"));
            }
        });
        $("resetAssistantPolishPromptBtn")?.addEventListener("click", async () => {
            try {
                const saved = await persistOwnerPolishPrompt(true, "", true);
                if (saved) showToast("已恢复默认二次改写提示词");
            } catch (err) {
                setContentAssistantError(formatLocalizedFetchError(err, "恢复默认提示词失败"));
            }
        });
        $("assistantTopicDetail")?.addEventListener("input", (event) => {
            if (event.target.id === "assistantDraftEditor") {
                const metrics = $("assistantDraftLiveMetrics");
                if (metrics) metrics.innerHTML = renderAssistantDraftMetricsHtml(event.target.value);
                queueAssistantDraftSave();
            }
        });
        $("assistantTopicDetail")?.addEventListener("change", async (event) => {
            if (event.target.id !== "assistantDraftSelect") return;
            const currentDraftId = $("assistantDraftEditor")?.closest("[data-draft-id]")?.dataset.draftId || "";
            const saved = await saveAssistantDraftEditor();
            if (!saved) {
                event.target.value = currentDraftId;
                return;
            }
            const topicId = state.assistantSelectedTopicId;
            state.assistantSelectedDraftByTopic[topicId] = event.target.value;
            state.assistantDraftDirty = false;
            renderAssistantTopicDetail(state.assistantDashboard);
        });
        $("assistantTopicDetail")?.addEventListener("click", async (event) => {
            const conflict = event.target.closest("[data-assistant-resolve-conflict]");
            if (conflict) {
                await resolveAssistantDraftConflict(conflict.dataset.assistantResolveConflict);
                return;
            }
            const research = event.target.closest("[data-assistant-research]");
            if (research) {
                await startAssistantMaterialResearch(research);
                return;
            }
            const researchUpgrade = event.target.closest("[data-assistant-research-upgrade]");
            if (researchUpgrade) {
                const draftId = (researchUpgrade.dataset.assistantResearchUpgrade || "").trim();
                const materialId = (researchUpgrade.dataset.materialId || "").trim();
                const instruction = ($("assistantOptimizeInstruction")?.value || $("assistantCreationAngleInput")?.value || "").trim();
                const genTarget = parseGenerationTargetSpec(
                    $("assistantOptimizeGenTargetSelect")?.value || $("assistantGenTargetSelect")?.value || state.assistantGenerationTargetSpec,
                    $("assistantOptimizeEffortSelect") ? $("assistantOptimizeEffortSelect").value : state.assistantGenerationEffort
                );
                if (!await saveAssistantDraftEditor()) return;
                const draft = (state.assistantDashboard?.drafts || []).find((item) => item.id === draftId);
                if (!draft) return;
                const payload = {
                    materialId: materialId || draft.materialId,
                    draftId: draft.id,
                    version: draft.version,
                    mode: "RESEARCH",
                    skillId: draft.skillId || state.assistantSelectedSkill || "AUTO",
                    generationProvider: genTarget.generationProvider,
                    generationModel: genTarget.generationModel,
                    regenerate: true,
                    clientRequestId: newClientRequestId()
                };
                if (instruction) payload.instruction = instruction;
                state.assistantSelectedDraftByTopic[draft.topicId] = draft.id;
                const started = await runAssistantMutation("research-upgrade", "/research", payload, 180000);
                if (started) showToast("已启动联网深挖，完成后将直接生成当前稿件新版本…");
                return;
            }
            const taskAction = event.target.closest("[data-assistant-task-action]");
            if (taskAction) {
                await runAssistantMutation("task-" + taskAction.dataset.assistantTaskAction,
                    `/tasks/${encodeURIComponent(taskAction.dataset.taskId)}/${taskAction.dataset.assistantTaskAction}`, {});
                return;
            }
            if (event.target.closest("#assistantMoveMaterialsBtn")) {
                const materialIds = Array.from(document.querySelectorAll("[data-assistant-material-id]:checked"))
                    .map((input) => input.dataset.assistantMaterialId);
                if (!materialIds.length) {
                    setContentAssistantError("请先选择要移动的文字来源。");
                    return;
                }
                const topic = selectedAssistantTopic(state.assistantDashboard);
                const targetTopicId = $("assistantMoveTarget").value;
                const versions = { [topic.id]: topic.version };
                const target = (state.assistantDashboard?.topics || []).find((item) => item.id === targetTopicId);
                if (target) versions[target.id] = target.version;
                const isCurrentUser = captureUserScope();
                const editor = $("assistantDraftEditor");
                if (editor?.readOnly) return;
                if (editor) editor.readOnly = true;
                try {
                    if (!await saveAssistantDraftEditor() || !isCurrentUser() || state.assistantSelectedTopicId !== topic.id) return;
                    await runAssistantMutation("move-materials", "/topics/move", { materialIds, targetTopicId, versions });
                } finally {
                    if (editor) editor.readOnly = false;
                }
                return;
            }
            const confirmTopic = event.target.closest("[data-assistant-confirm-topic]");
            if (confirmTopic) {
                const topicId = confirmTopic.dataset.assistantConfirmTopic;
                const topic = selectedAssistantTopic(state.assistantDashboard);
                const payload = topic?.version !== undefined ? { version: Number(topic.version) } : {};
                await runAssistantMutation("confirm-topic", `/topics/${encodeURIComponent(topicId)}/confirm`, payload);
                return;
            }
            const history = event.target.closest("[data-assistant-history]");
            if (history) {
                const list = $("assistantDraftVersions");
                const preview = $("assistantDraftVersionPreview");
                if (!list.hidden) {
                    list.hidden = true;
                    preview.hidden = true;
                    return;
                }
                list.textContent = "正在读取版本…";
                list.hidden = false;
                const isCurrentUser = captureUserScope();
                try {
                    const versions = await contentAssistantRequest(`/drafts/${encodeURIComponent(history.dataset.assistantHistory)}/versions`);
                    if (!isCurrentUser() || !history.isConnected || list.hidden) return;
                    const buttons = (Array.isArray(versions) ? versions : versions.items || []).map((version) => {
                        const button = document.createElement("button");
                        button.type = "button";
                        button.dataset.assistantVersion = String(Number(version.version || 0));
                        button.dataset.assistantVersionText = version.text || "";
                        button.textContent = `v${button.dataset.assistantVersion} · ${formatAssistantDateTime(version.savedAt || version.updatedAt)}`;
                        return button;
                    });
                    list.replaceChildren(...(buttons.length ? buttons : ["暂无历史版本。"]));
                } catch (error) {
                    if (isCurrentUser() && history.isConnected && !list.hidden) {
                        list.hidden = true;
                        setContentAssistantError(error.message);
                    }
                }
                return;
            }
            const version = event.target.closest("[data-assistant-version-text]");
            if (version) {
                const preview = $("assistantDraftVersionPreview");
                preview.dataset.draftId = $("assistantDraftEditor").closest("[data-draft-id]").dataset.draftId;
                preview.dataset.version = version.dataset.assistantVersion;
                $("assistantDraftVersionText").value = version.dataset.assistantVersionText || "";
                $("assistantDraftVersionTitle").textContent = `预览 v${version.dataset.assistantVersion}`;
                preview.hidden = false;
                return;
            }
            const adopt = event.target.closest("#assistantAdoptDraftVersion");
            if (adopt) {
                const preview = $("assistantDraftVersionPreview");
                const editor = $("assistantDraftEditor");
                const draftId = editor?.closest("[data-draft-id]")?.dataset.draftId;
                if (preview.hidden || !draftId || preview.dataset.draftId !== draftId) return;
                const isCurrentUser = captureUserScope();
                const isCurrentDraft = () => isCurrentUser() && adopt.isConnected && $("assistantDraftEditor") === editor;
                const sourceVersion = Number(preview.dataset.version);
                const wasReadOnly = editor.readOnly;
                adopt.disabled = true;
                editor.readOnly = true;
                try {
                    const current = await saveAssistantDraftEditor();
                    if (!current || current.id !== draftId || !isCurrentDraft()) return;
                    const restored = await runAssistantMutation("adopt-version", `/drafts/${encodeURIComponent(draftId)}/adopt`,
                        { version: current.version, sourceVersion });
                    if (!restored || !isCurrentDraft()) return;
                    editor.value = restored.text;
                    preview.hidden = true;
                    $("assistantDraftVersions").hidden = true;
                    renderAssistantTopicDetail(state.assistantDashboard);
                } finally {
                    editor.readOnly = wasReadOnly;
                    adopt.disabled = false;
                }
                return;
            }
            const optimize = event.target.closest("[data-assistant-optimize]");
            if (optimize) {
                const draftId = optimize.dataset.assistantOptimize;
                const rawInstruction = $("assistantOptimizeInstruction").value.trim();
                const instruction = rawInstruction
                    || "拒绝复述与总结腔，结合深度研究资料与一手素材换一个更有流量钩子与评论讨论度的话题切口重写新版本";
                const genTarget = parseGenerationTargetSpec(
                    $("assistantOptimizeGenTargetSelect")?.value || state.assistantGenerationTargetSpec,
                    $("assistantOptimizeEffortSelect") ? $("assistantOptimizeEffortSelect").value : state.assistantGenerationEffort
                );
                if (!await saveAssistantDraftEditor()) return;
                const draft = (state.assistantDashboard?.drafts || []).find((item) => item.id === draftId);
                if (!draft) return;
                const payload = {
                    version: draft.version,
                    instruction,
                    generationProvider: genTarget.generationProvider,
                    generationModel: genTarget.generationModel,
                    clientRequestId: newClientRequestId()
                };
                if (draft.skillId) payload.skillId = draft.skillId;
                await runAssistantMutation("optimize", `/drafts/${encodeURIComponent(draft.id)}/optimize`, payload, 180000);
                return;
            }
            const skillRewrite = event.target.closest("[data-assistant-skill-rewrite]");
            if (skillRewrite) {
                const draftId = skillRewrite.dataset.draftId;
                const skillId = (skillRewrite.dataset.assistantSkillRewrite || "").trim();
                const instruction = ($("assistantOptimizeInstruction")?.value || "").trim();
                const genTarget = parseGenerationTargetSpec(
                    $("assistantOptimizeGenTargetSelect")?.value || state.assistantGenerationTargetSpec,
                    $("assistantOptimizeEffortSelect") ? $("assistantOptimizeEffortSelect").value : state.assistantGenerationEffort
                );
                if (!await saveAssistantDraftEditor()) return;
                const draft = (state.assistantDashboard?.drafts || []).find((item) => item.id === draftId);
                if (!draft || !skillId) return;
                state.assistantSelectedSkill = skillId;
                renderAssistantSkillBar(state.assistantDashboard);
                await runAssistantMutation("optimize", `/drafts/${encodeURIComponent(draft.id)}/optimize`, {
                    version: draft.version,
                    skillId,
                    instruction,
                    generationProvider: genTarget.generationProvider,
                    generationModel: genTarget.generationModel,
                    clientRequestId: newClientRequestId()
                }, 180000);
                return;
            }
            const optimizePreset = event.target.closest("[data-assistant-optimize-preset]");
            if (optimizePreset) {
                const draftId = optimizePreset.dataset.draftId;
                const instruction = (optimizePreset.dataset.assistantOptimizePreset || "").trim();
                const genTarget = parseGenerationTargetSpec(
                    $("assistantOptimizeGenTargetSelect")?.value || state.assistantGenerationTargetSpec,
                    $("assistantOptimizeEffortSelect") ? $("assistantOptimizeEffortSelect").value : state.assistantGenerationEffort
                );
                const input = $("assistantOptimizeInstruction");
                if (input) input.value = instruction;
                if (!await saveAssistantDraftEditor()) return;
                const draft = (state.assistantDashboard?.drafts || []).find((item) => item.id === draftId);
                if (!draft) return;
                await runAssistantMutation("optimize", `/drafts/${encodeURIComponent(draft.id)}/optimize`, {
                    version: draft.version,
                    instruction,
                    generationProvider: genTarget.generationProvider,
                    generationModel: genTarget.generationModel,
                    clientRequestId: newClientRequestId()
                }, 180000);
                return;
            }
            const focusDraft = event.target.closest("[data-assistant-focus-draft]");
            if (focusDraft) {
                focusAssistantDraftEditor();
                return;
            }
            const toggleOrganize = event.target.closest("[data-assistant-toggle-organize]");
            if (toggleOrganize) {
                if (!await saveAssistantDraftEditor()) return;
                $("assistantDraftEditor")?.blur?.();
                if ($("assistantDraftVersions")) $("assistantDraftVersions").hidden = true;
                if ($("assistantDraftVersionPreview")) $("assistantDraftVersionPreview").hidden = true;
                state.assistantOrganizeMode = !state.assistantOrganizeMode;
                if (state.assistantDashboard) renderAssistantTopicDetail(state.assistantDashboard);
                return;
            }
            const copyDraft = event.target.closest("[data-assistant-copy-draft]");
            if (copyDraft) {
                const text = ($("assistantDraftEditor")?.value || "").trim();
                if (!text) {
                    showToast("当前稿件为空");
                    return;
                }
                try {
                    await copyTextWithFallback(text);
                    showToast("已复制当前稿件文案");
                } catch (_) {
                    showToast("复制失败，请手动复制");
                }
                return;
            }
            const simulateDraft = event.target.closest("[data-assistant-simulate-draft]");
            if (simulateDraft) {
                const text = ($("assistantDraftEditor")?.value || "").trim();
                if (!text) {
                    showToast("当前稿件为空");
                    return;
                }
                loadDraftIntoSimulator(text, {
                    isThread: computeWeightedTweetLength(text) > 280
                });
                showToast("已送入草稿体检");
                return;
            }
            const tweetIntent = event.target.closest("[data-assistant-tweet-intent]");
            if (tweetIntent) {
                const text = ($("assistantDraftEditor")?.value || "").trim();
                if (!text) {
                    showToast("当前稿件为空");
                    return;
                }
                window.open("https://x.com/intent/tweet?text=" + encodeURIComponent(text), "_blank", "noopener,noreferrer");
                return;
            }
            const quoteIntent = event.target.closest("[data-assistant-quote-intent]");
            if (quoteIntent) {
                const text = ($("assistantDraftEditor")?.value || "").trim();
                const quoteUrl = quoteIntent.dataset.quoteUrl || "";
                if (!text) {
                    showToast("当前稿件为空");
                    return;
                }
                if (!quoteUrl) return;
                window.open(
                    "https://x.com/intent/tweet?text=" + encodeURIComponent(text) + "&url=" + encodeURIComponent(quoteUrl),
                    "_blank",
                    "noopener,noreferrer"
                );
                return;
            }
            const publishNow = event.target.closest("[data-assistant-publish-now]");
            const verify = event.target.closest("[data-assistant-verify]");
            if (verify) { await verifyAssistantPublication(verify); return; }
            if (publishNow) {
                await publishAssistantDraftNow(publishNow.dataset.assistantPublishNow, publishNow.dataset.quoteSource === "true");
                return;
            }
            const schedule = event.target.closest("[data-assistant-schedule]");
            if (schedule) {
                await sendAssistantDraftToSchedule(
                    schedule.dataset.assistantSchedule,
                    schedule.dataset.quoteSource === "true"
                );
                return;
            }
            const updateScheduled = event.target.closest("[data-assistant-update-scheduled]");
            if (updateScheduled) {
                const publication = (state.assistantDashboard?.publications || []).find((item) =>
                    (item.id || item.intentId) === updateScheduled.dataset.assistantUpdateScheduled);
                const isCurrentUser = captureUserScope();
                if (!publication || !isCurrentUser()) return;
                while (state.assistantMutation && isCurrentUser()) await state.assistantMutation.done;
                if (!isCurrentUser()) return;
                const draft = await saveAssistantDraftEditor();
                if (isCurrentUser() && draft?.id === publication.draftId) {
                    await runAssistantMutation("update-scheduled",
                        `/drafts/${encodeURIComponent(draft.id)}/update-scheduled`, {
                            version: draft.version,
                            intentId: publication.intentId || publication.id,
                            expectedUpdatedAt: publication.queueUpdatedAt,
                            operationId: newClientRequestId()
                        });
                }
            }
        });

        $("gateLoginBtn").addEventListener("click", triggerXOAuthLogin);
        const profileBioEl = document.getElementById("profileBio");
        if (profileBioEl) {
            profileBioEl.addEventListener("click", () => {
                profileBioEl.classList.toggle("is-expanded");
            });
        }
        const errCloseBtn = document.getElementById("errorToastCloseBtn");
        if (errCloseBtn) {
            errCloseBtn.addEventListener("click", hideError);
        }

        const triggerOpenAuthorCard = async () => {
            const settings = document.getElementById("workspaceSettings");
            if (settings?.open) {
                settings.open = false;
                document.getElementById("workspaceSettingsToggle")?.focus?.({ preventScroll: true });
            }
            if (!isLoggedIn() || state.storedMode) {
                openAuthorPromptModal(buildFallbackAuthorCardData());
                return;
            }
            const userId = stableUserId(state.authStatus.user);
            const generation = state.authStatusGeneration;
            try {
                const resp = await fetch("/api/usage/author-card", { cache: "no-store" });
                if (resp.ok) {
                    const data = await safeParseJson(resp);
                    if (generation !== state.authStatusGeneration || state.isLoggingOut || !isLoggedIn() ||
                        stableUserId(state.authStatus.user) !== userId) return;
                    if (data && data.authorProfile) {
                        openAuthorPromptModal(data);
                        return;
                    }
                }
            } catch (_) {}
            openAuthorPromptModal(buildFallbackAuthorCardData());
        };

        ["openAuthorStoryBtn", "heroAuthorInfoBtn", "railAuthorBtn", "gateAuthorBtn"].forEach((btnId) => {
            const el = document.getElementById(btnId);
            if (el) {
                el.addEventListener("click", triggerOpenAuthorCard);
            }
        });

        const unlockGuideModalEl = document.getElementById("unlockGuideModal");
        if (unlockGuideModalEl) {
            unlockGuideModalEl.addEventListener("click", (e) => {
                if (e.target === unlockGuideModalEl) {
                    closeUnlockGuideModal();
                }
            });
        }
        document.getElementById("unlockGuideCloseBtn")?.addEventListener("click", closeUnlockGuideModal);
        document.getElementById("unlockGuideLaterBtn")?.addEventListener("click", closeUnlockGuideModal);

        const repostPreviewModalEl = document.getElementById("repostPreviewModal");
        if (repostPreviewModalEl) {
            repostPreviewModalEl.addEventListener("click", (e) => {
                if (e.target === repostPreviewModalEl) {
                    closeRepostPreviewModal();
                }
            });
        }
        document.getElementById("repostPreviewCloseBtn")?.addEventListener("click", () => closeRepostPreviewModal());
        for (const [stage, ids] of Object.entries(STAGE_MODEL_CONTROL_IDS)) {
            document.getElementById(ids.provider)?.addEventListener("change", (e) => {
                const prov = String(e.target.value || "chatgpt").toLowerCase();
                syncStageModelControls(stage, prov, composeModelWithEffort(prov, DEFAULT_MODEL_BY_PROVIDER[prov], DEFAULT_EFFORT_BY_PROVIDER[prov]));
            });
            document.getElementById(ids.model)?.addEventListener("change", (e) => {
                const customInput = $(ids.custom);
                const isCustom = e.target.value === "__custom__";
                if (customInput) {
                    customInput.hidden = !isCustom;
                    if (isCustom && (PROVIDER_MODEL_CATALOG[$(ids.provider)?.value || "chatgpt"] || []).includes(splitModelAndEffort(customInput.value).modelId)) {
                        customInput.value = "";
                        customInput.focus();
                    } else {
                        readStageModelControls(stage);
                    }
                }
            });
            document.getElementById(ids.effort)?.addEventListener("change", () => {
                readStageModelControls(stage);
            });
        }
        document.getElementById("assistantGenTargetSelect")?.addEventListener("change", (e) => {
            state.assistantGenerationTargetPinned = true;
            syncGenerationTargetSelects(e.target.value);
        });
        document.getElementById("assistantGenEffortSelect")?.addEventListener("change", (e) => {
            state.assistantGenerationTargetPinned = true;
            syncGenerationTargetSelects(state.assistantGenerationTargetSpec, e.target.value);
        });
        document.getElementById("repostGenTargetSelect")?.addEventListener("change", (e) => {
            const prov = String(e.target.value || "chatgpt").split("|")[0];
            syncEffortSelectAvailability($("repostGenEffortSelect"), prov, DEFAULT_EFFORT_BY_PROVIDER[prov] ?? "");
        });
        document.getElementById("repostSaveDefaultModelBtn")?.addEventListener("click", async () => {
            const sel = $("repostGenTargetSelect");
            const effSel = $("repostGenEffortSelect");
            const parsed = parseGenerationTargetSpec(sel ? sel.value : "", effSel ? effSel.value : "");
            const curSettings = state.assistantDashboard?.settings || {};
            const curMods = curSettings.moduleConfigs || {};
            const nextMods = {
                collection: curMods.collection || { provider: "minimax", model: "MiniMax-M2.7" },
                search: curMods.search || { provider: "deepseek", model: "deepseek-chat" },
                repost: { provider: parsed.generationProvider, model: parsed.generationModel }
            };
            if (curMods.generation) nextMods.generation = curMods.generation;
            const btn = $("repostSaveDefaultModelBtn");
            if (btn) btn.disabled = true;
            try {
                const saved = await runAssistantMutation("settings", "/settings", { moduleConfigs: nextMods });
                if (saved) {
                    if (state.assistantDashboard) state.assistantDashboard.settings = saved;
                    const form = $("assistantSettingsForm");
                    if (form && saved.version !== undefined) form.dataset.version = String(saved.version);
                    syncRepostSettingsModelControls(parsed.generationProvider, parsed.generationModel);
                    if (btn) {
                        const prevTxt = btn.textContent;
                        btn.textContent = "已设为默认";
                        setTimeout(() => { btn.textContent = prevTxt; }, 1600);
                    }
                }
            } finally {
                if (btn) btn.disabled = false;
            }
        });
        document.getElementById("assistantTopicDetail")?.addEventListener("change", (e) => {
            if (e.target?.id === "assistantOptimizeGenTargetSelect") {
                state.assistantGenerationTargetPinned = true;
                syncGenerationTargetSelects(e.target.value);
            } else if (e.target?.id === "assistantOptimizeEffortSelect") {
                state.assistantGenerationTargetPinned = true;
                syncGenerationTargetSelects(state.assistantGenerationTargetSpec, e.target.value);
            }
        });
        document.getElementById("repostPolishPromptToggleBtn")?.addEventListener("click", () => {
            repostPreviewState.polishDrawerOpen = !repostPreviewState.polishDrawerOpen;
            syncPolishPromptControls(repostPreviewState.source || state.assistantDashboard?.settings || {});
        });
        document.getElementById("repostSavePolishPromptBtn")?.addEventListener("click", async () => {
            const chk = $("repostPolishEnabledCheck");
            const input = $("repostPolishPromptInput");
            try {
                const saved = await persistOwnerPolishPrompt(
                    chk ? chk.checked : true,
                    input ? input.value : "",
                    false
                );
                if (saved) showToast("二次改写提示词已保存，点「换一句」即可按新要求重写");
            } catch (err) {
                showToast(formatLocalizedFetchError(err, "保存二次改写提示词失败"));
            }
        });
        document.getElementById("repostResetPolishPromptBtn")?.addEventListener("click", async () => {
            try {
                const saved = await persistOwnerPolishPrompt(true, "", true);
                if (saved) showToast("已恢复默认二次改写提示词");
            } catch (err) {
                showToast(formatLocalizedFetchError(err, "恢复默认提示词失败"));
            }
        });
        document.getElementById("repostPreviewFilterBar")?.addEventListener("click", (event) => {
            const btn = event.target?.closest?.("[data-repost-filter]");
            if (!btn) return;
            const nextMode = btn.dataset.repostFilter || "ALL";
            if (repostPreviewState.filterMode === nextMode) return;
            repostPreviewState.filterMode = nextMode;
            if (nextMode === "REWRITE" && refreshRewriteDay()) return;
            renderRepostPreviewTimeline();
        });
        document.getElementById("repostPreviewEvalRestBtn")?.addEventListener("click", () => {
            void evaluateAuthorAccountOnceInModal(repostPreviewState.requestToken, true);
        });
        document.getElementById("repostPreviewTimelineList")?.addEventListener("input", (event) => {
            const rwInput = event.target?.closest?.("[data-rewrite-text-input]");
            if (rwInput) {
                const id = rwInput.dataset.rewriteTextInput || "";
                if (!id) return;
                repostPreviewState.editedRewrites.set(id, rwInput.value);
                const row = rwInput.closest("[data-timeline-material]");
                const link = row?.querySelector?.("[data-rewrite-intent-link]");
                if (link) {
                    link.href = buildRepostIntentHref(rwInput.value.trim());
                }
                return;
            }
            const input = event.target?.closest?.("[data-repost-comment-input]");
            if (!input) return;
            const id = input.dataset.repostCommentInput || "";
            if (!id) return;
            repostPreviewState.editedComments.set(id, input.value);
            const row = input.closest("[data-timeline-material]");
            const link = row?.querySelector?.("[data-repost-intent-link]");
            if (link) {
                link.href = buildRepostIntentHref(buildRepostItemComposedText(id));
            }
        });
        window.addEventListener("storage", event => {
            if (!event.key?.startsWith(PENDING_QUOTE_STORAGE_PREFIX) || $("repostPreviewModal")?.hidden !== false) return;
            const row = repostPreviewState.items.find(item => item.material?.id && PENDING_QUOTE_STORAGE_PREFIX + quoteAttemptKey(item.material) === event.key);
            if (!row) return;
            const attempt = repostPreviewState.pendingQuotes.get(quoteAttemptKey(row.material));
            if (attempt) {
                markQuoteAwaitingReceipt(row, attempt);
                renderRepostPreviewTimeline(String(row.material.id));
            } else {
                void refreshQuoteReceipt(String(row.material.id)).catch(() => {});
            }
        });
        document.getElementById("repostPreviewTimelineList")?.addEventListener("click", async (event) => {
            const checkReceipt = event.target?.closest?.("[data-repost-refresh-receipt]");
            if (checkReceipt) {
                checkReceipt.disabled = true;
                const token = repostPreviewState.requestToken;
                try { await refreshQuoteReceipt(checkReceipt.dataset.repostRefreshReceipt); }
                catch (error) {
                    if (token === repostPreviewState.requestToken) showToast(formatLocalizedFetchError(error, "未能刷新回执"));
                } finally { checkReceipt.disabled = false; }
                return;
            }
            const review = event.target?.closest?.("[data-repost-review-item]");
            if (review) {
                const id = review.dataset.repostReviewItem;
                const row = repostPreviewState.items.find(item => String(item.material?.id || "") === id);
                const quoteId = row ? resolveRepostQuoteTweetId(row.material) || id : id;
                const account = captureUserScope();
                closeRepostPreviewModal();
                state.assistantView = "monitor";
                switchTab("timing");
                renderContentAssistant();
                await loadContentAssistant(true);
                if (account() && state.assistantView === "monitor") {
                    const feed = $("assistantAutoRepostFeed");
                    const target = feed?.querySelector(`[data-quote-verification][data-verification-material="${CSS.escape(id)}"], [data-quote-verification="${CSS.escape(quoteId)}"]`);
                    if (!target) {
                        showToast("暂未找到这条引用的记录，请稍后刷新回执");
                        return;
                    }
                    target?.scrollIntoView({block: "center"});
                    target?.querySelector("[data-assistant-verify]")?.focus({preventScroll: true});
                }
                return;
            }
            const rwPreviewBtn = event.target?.closest?.("[data-rewrite-preview-item]");
            if (rwPreviewBtn) {
                const id = rwPreviewBtn.dataset.rewritePreviewItem || "";
                if (id) {
                    void previewSingleRewriteItem(id, false, repostPreviewState.requestToken);
                }
                return;
            }
            const rwRegenBtn = event.target?.closest?.("[data-rewrite-regen-item]");
            if (rwRegenBtn) {
                const id = rwRegenBtn.dataset.rewriteRegenItem || "";
                if (id) {
                    void previewSingleRewriteItem(id, true, repostPreviewState.requestToken);
                }
                return;
            }
            const rwIntent = event.target?.closest?.("[data-rewrite-intent-link]");
            if (rwIntent) {
                const row = repostPreviewState.items.find(item => String(item.material?.id || "") === rwIntent.dataset.rewriteIntentLink);
                if (!requireTodayRewrite(row)) event.preventDefault();
                return;
            }
            const rwCopyBtn = event.target?.closest?.("[data-rewrite-copy-item]");
            if (rwCopyBtn) {
                const id = rwCopyBtn.dataset.rewriteCopyItem || "";
                const text = getRewriteItemText(id).trim();
                if (!text) {
                    showToast("暂无可复制的洗稿正文");
                    return;
                }
                copyTextWithFallback(text)
                    .then(() => showToast("已复制洗稿正文"))
                    .catch(() => showToast("复制未成功，请手动选中复制"));
                return;
            }
            const rwSchedBtn = event.target?.closest?.("[data-rewrite-schedule-item]");
            if (rwSchedBtn) {
                const id = rwSchedBtn.dataset.rewriteScheduleItem || "";
                const row = repostPreviewState.items.find((item) => String(item.material?.id || "") === id);
                const ownerId = String(repostPreviewState.owner?.accountId || "");
                if (!row || !ownerId || ownerId !== stableUserId(state.authStatus?.user)) {
                    showToast("发布账号已变更，请重新打开来源时间线");
                    return;
                }
                if (!requireTodayRewrite(row)) return;
                const text = getRewriteItemText(id).trim();
                if (!text) {
                    showToast("暂无可转入定时的洗稿内容");
                    return;
                }
                const locked = ["PUBLISHED", "UNCERTAIN", "SENDING"].includes(row.rewrite?.status);
                const storedRef = !locked && repostPreviewState.editedRewrites.reference?.(id);
                const rewriteSource = storedRef || { ownerAccountId: ownerId, materialId: id, recordVersion: Number(row.rewrite?.savedDraftVersion || 0) };
                if (!storedRef && row.rewrite?.previewId) rewriteSource.previewId = row.rewrite.previewId;
                if (isManualRewritePreview(row.rewrite) && !rewriteSource.previewId) {
                    showToast("请先重新生成洗稿预览再转入排期");
                    return;
                }
                if (sendTextDraftToSchedule(text, [], null, rewriteSource) !== false) {
                    closeRepostPreviewModal();
                    showToast("已转入排期草稿，请确认时间后加入队列");
                }
                return;
            }
            const expandBtn = event.target?.closest?.("[data-repost-expand-item]");
            if (expandBtn) {
                const id = expandBtn.dataset.repostExpandItem || "";
                if (id) {
                    repostPreviewState.expandedIds.add(id);
                    renderRepostPreviewTimeline(id);
                }
                return;
            }
            const writeBtn = event.target?.closest?.("[data-repost-write-item]");
            if (writeBtn) {
                const id = writeBtn.dataset.repostWriteItem || "";
                if (id) {
                    void evaluateSingleTimelineItem(id, true, repostPreviewState.requestToken, true, true);
                }
                return;
            }
            const evalBtn = event.target?.closest?.("[data-repost-eval-item]");
            if (evalBtn) {
                const id = evalBtn.dataset.repostEvalItem || "";
                if (id) {
                    void evaluateSingleTimelineItem(id, false, repostPreviewState.requestToken, true, true);
                }
                return;
            }
            const regenBtn = event.target?.closest?.("[data-repost-regen-item]");
            if (regenBtn) {
                const id = regenBtn.dataset.repostRegenItem || "";
                if (id) {
                    void evaluateSingleTimelineItem(id, true, repostPreviewState.requestToken, true, true);
                }
                return;
            }
            const copyBtn = event.target?.closest?.("[data-repost-copy-item]");
            if (copyBtn) {
                const id = copyBtn.dataset.repostCopyItem || "";
                const text = getRepostItemCommentText(id).trim();
                if (!text) {
                    showToast("暂无可复制的转发附文");
                    return;
                }
                copyTextWithFallback(text)
                    .then(() => showToast("已复制转发附文"))
                    .catch(() => showToast("复制未成功，请手动选中复制"));
                return;
            }
            const schedBtn = event.target?.closest?.("[data-repost-schedule-item]");
            if (schedBtn) {
                const id = schedBtn.dataset.repostScheduleItem || "";
                const found = (repostPreviewState.items || []).find((it) => String(it.material?.id || "") === id);
                if (!found || quoteDeliveryStatus(found.decision) || repostPreviewState.publishingIds.has(id)) return;
                const comment = getRepostItemCommentText(id).trim();
                if (!comment) {
                    showToast("暂无可转入的转发内容");
                    return;
                }
                const quoteTweetId = resolveRepostQuoteTweetId(found?.material || { id });
                const quoteTweetUrl = found ? resolveRepostMaterialUrl(found.material) : "";
                if (!quoteTweetId) {
                    showToast("该素材缺少有效原帖推文 ID，无法引用转发");
                    return;
                }
                if (sendTextDraftToSchedule(comment, [], { quoteTweetId, quoteTweetUrl }) !== false) {
                    closeRepostPreviewModal();
                    showToast("已转入排期草稿，请确认时间后加入队列");
                }
                return;
            }
            const pubBtn = event.target?.closest?.("[data-repost-publish-item]");
            if (pubBtn) {
                void publishRepostPreviewItem(pubBtn.dataset.repostPublishItem || "");
            }
        });

        const authorModalEl = document.getElementById("authorPromptModal");
        if (authorModalEl) {
            authorModalEl.addEventListener("click", (e) => {
                if (e.target === authorModalEl) {
                    closeAuthorPromptModal();
                }
            });
        }
        const authorCloseBtn = document.getElementById("authorModalCloseBtn");
        if (authorCloseBtn) {
            authorCloseBtn.addEventListener("click", closeAuthorPromptModal);
        }
        const authorLaterBtn = document.getElementById("authorModalLaterBtn");
        if (authorLaterBtn) {
            authorLaterBtn.addEventListener("click", closeAuthorPromptModal);
        }
        const authorFollowBtn = document.getElementById("authorModalFollowBtn");
        if (authorFollowBtn) {
            authorFollowBtn.addEventListener("click", () => {
                if (!state.authorFollowingMarked) {
                    showToast("已打开作者关注页，请在 X 完成操作；当前尚未确认关注");
                }
                closeAuthorPromptModal();
            });
        }

        const doLogout = async () => {
            if (state.isLoggingOut) return;
            if (state.assistantDraftDirty && !await saveAssistantDraftEditor()) return;
            const settings = document.getElementById("workspaceSettings");
            if (settings?.open) {
                settings.open = false;
                document.getElementById("workspaceSettingsToggle")?.focus?.({ preventScroll: true });
            }
            state.isLoggingOut = true;
            const prevUserId = stableUserId(state.authStatus?.user) || state.boundUserId;
            const previousGeneration = state.userScopeGeneration;
            const buttons = [$("railLogoutBtn"), $("topbarLogoutBtn")];
            buttons.forEach((button) => { button.disabled = true; button.textContent = "正在退出…"; });
            cancelReportRequest();
            state.authStatusGeneration++;
            try {
                let requestError = null;
                try {
                    const { resp } = await requestJsonWithin("/api/auth/x/logout", { method: "POST" });
                    if (!resp.ok) throw new Error(`退出未完成（HTTP ${resp.status}），请重试。`);
                } catch (err) {
                    requestError = err;
                }
                if (previousGeneration !== state.userScopeGeneration || stableUserId(state.authStatus?.user) !== prevUserId) return;
                await fetchServerAndAuthStatus();
                if (isLoggedIn()) throw requestError || new Error("退出尚未生效，请重试。");
                clearProfileHeaderCache(prevUserId);
                const confirmedGeneration = state.userScopeGeneration;
                await clearPersistedScheduleDraft(prevUserId);
                if (confirmedGeneration !== state.userScopeGeneration || isLoggedIn()) return;
                resetUserScopedMemoryState();
                state.boundUserId = "";
                state.period = "HOURS_24";
                state.days = 1;
                if (typeof syncPeriodButtons === "function") syncPeriodButtons();
                closeAuthorPromptModal();
                closeUnlockGuideModal();
                closeConfirmResendModal();
                if (typeof closeRepostPreviewModal === "function") closeRepostPreviewModal();
                $("peerSearchInput").value = "";
                $("followFilterInput").value = "";
                $("draftSimulatorInput").value = "";
                $("draftSimulatorResult").hidden = true;
                hideError();
                showToast("已退出 X 账号授权登录");
            } catch (err) {
                if (!isLoggedIn() || stableUserId(state.authStatus?.user) === prevUserId) {
                    showToast(err.name === "RequestTimeoutError" ? "暂未确认退出结果，请稍后重试。" : err.message || "退出未完成，请重试。");
                }
            } finally {
                state.isLoggingOut = false;
                buttons.forEach((button) => { button.disabled = false; button.textContent = "退出"; });
                updateRefreshButtonState();
            }
        };
        $("railLogoutBtn").addEventListener("click", doLogout);
        if ($("topbarLogoutBtn")) {
            $("topbarLogoutBtn").addEventListener("click", doLogout);
        }

        // 全局无侵入键盘快捷键（Esc 关闭弹窗/错误提示/清空搜索框；1-6 切模块，M/W/D/T 秒切 30天/7天/48h/24h）
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                const repostModal = document.getElementById("repostPreviewModal");
                if (repostModal && !repostModal.hidden) {
                    closeRepostPreviewModal();
                    return;
                }
                const confirmModal = document.getElementById("confirmResendModal");
                if (confirmModal && !confirmModal.hidden) {
                    closeConfirmResendModal();
                    return;
                }
                const unlockModal = document.getElementById("unlockGuideModal");
                if (unlockModal && !unlockModal.hidden) {
                    closeUnlockGuideModal();
                    return;
                }
                const authorModal = document.getElementById("authorPromptModal");
                if (authorModal && !authorModal.hidden) {
                    closeAuthorPromptModal();
                    return;
                }
                const settings = document.getElementById("workspaceSettings");
                if (settings?.open) {
                    e.preventDefault();
                    settings.open = false;
                    document.getElementById("workspaceSettingsToggle")?.focus?.({ preventScroll: true });
                    return;
                }
                const errEl = document.getElementById("errorToast");
                if (errEl && !errEl.hidden) {
                    hideError();
                    return;
                }
                if (state.algoConfigOpen) {
                    openAlgoConfigDrawer(false);
                    return;
                }
                const activeEl = e.target;
                if (activeEl && activeEl.id === "peerSearchInput" && activeEl.value) {
                    e.preventDefault();
                    activeEl.value = "";
                    state.peerSearchQuery = "";
                    state.peerRenderLimit = LIST_PAGE_SIZE;
                    if (state.report) renderPeerList(state.report);
                    return;
                }
                if (activeEl && activeEl.id === "followFilterInput" && activeEl.value) {
                    e.preventDefault();
                    activeEl.value = "";
                    state.followSearchQuery = "";
                    state.followRenderLimit = LIST_PAGE_SIZE;
                    if (state.report) renderFollowAuditPanel(state.report);
                    return;
                }
            }
            if (!isLoggedIn() || e.metaKey || e.ctrlKey || e.altKey || document.body.classList.contains("modal-open")) return;
            const tag = (e.target && e.target.tagName ? e.target.tagName : "").toUpperCase();
            if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || (e.target && e.target.isContentEditable)) {
                return;
            }
            const tabKeyMap = {
                "1": "activity",
                "2": "interactions",
                "3": "follow-audit",
                "4": "timing",
                "5": "circle",
                "6": "schedule"
            };
            if (tabKeyMap[e.key]) {
                switchTab(tabKeyMap[e.key]);
                return;
            }
            const periodOrder = ["HOURS_24", "HOURS_48", "WEEK", "MONTH"];
            if (e.key === "[" || e.key === "]") {
                const unlockedOrder = periodOrder.filter((c) => isPeriodUnlocked(c));
                if (unlockedOrder.length > 1) {
                    const curIdx = Math.max(0, unlockedOrder.indexOf(state.period));
                    const nextIdx = e.key === "]"
                        ? (curIdx + 1) % unlockedOrder.length
                        : (curIdx - 1 + unlockedOrder.length) % unlockedOrder.length;
                    const pMeta = PERIOD_META[unlockedOrder[nextIdx]];
                    if (pMeta && state.period !== pMeta.code) {
                        state.period = pMeta.code;
                        state.days = pMeta.days;
                        syncPeriodButtons();
                        loadSelfReport(false);
                        showToast(`已切换至${pMeta.badgePrefix}`);
                    }
                }
                return;
            }
            const up = String(e.key || "").toUpperCase();
            if (up === "T" || up === "D" || up === "W" || up === "M") {
                const pCode = up === "M" ? "MONTH" : up === "W" ? "WEEK" : up === "D" ? "HOURS_48" : "HOURS_24";
                const pMeta = PERIOD_META[pCode];
                if (pMeta && !isPeriodUnlocked(pMeta.code)) {
                    if (!hasPeriodAccessDecision()) {
                        showToast(state.isFetching
                            ? "正在同步账号权限与报表数据，请稍候…"
                            : "暂时无法确认可用时间范围，请检查更新后重试。");
                        return;
                    }
                    openUnlockGuideModal(pMeta.code);
                    return;
                }
                if (pMeta && state.period !== pMeta.code) {
                    state.period = pMeta.code;
                    state.days = pMeta.days;
                    syncPeriodButtons();
                    loadSelfReport(false);
                    showToast(`已切换至${pMeta.badgePrefix}`);
                }
            }
        });

        $("forceRefreshBtn").addEventListener("click", () => {
            if (state.storedMode) {
                const liveUrl = new URL(window.location.href);
                liveUrl.searchParams.set("period", state.period);
                liveUrl.searchParams.delete("stored");
                window.location.href = liveUrl.toString();
                return;
            }
            loadSelfReport(true);
        });

        // 24h / 48h / 7天 / 30天 四维度切换与锁定引导
        document.querySelectorAll(".seg-btn[data-period]").forEach((btn) => {
            btn.addEventListener("click", () => {
                const pMeta = normalizePeriod(btn.dataset.period, btn.dataset.days);
                if (!isPeriodUnlocked(pMeta.code)) {
                    if (!hasPeriodAccessDecision()) {
                        showToast(state.isFetching
                            ? "正在同步账号权限与报表数据，请稍候…"
                            : "暂时无法确认可用时间范围，请检查更新后重试。");
                        return;
                    }
                    openUnlockGuideModal(pMeta.code);
                    return;
                }
                state.period = pMeta.code;
                state.days = pMeta.days;
                syncPeriodButtons();
                loadSelfReport(false);
            });
        });

        // 模块 2：推友筛选与排序（150ms 输入防抖 + 切换筛选时重置分页）
        const syncPeerClearBtn = () => {
            const btn = document.getElementById("clearPeerSearchBtn");
            if (btn) btn.hidden = !$("peerSearchInput").value;
        };
        const onPeerSearchDebounced = debounce(() => {
            state.peerSearchQuery = $("peerSearchInput").value.trim();
            state.peerRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderPeerList(state.report);
        }, 150);

        $("peerSearchInput").addEventListener("input", (e) => {
            syncPeerClearBtn();
            onPeerSearchDebounced(e.target.value);
        });
        document.getElementById("clearPeerSearchBtn")?.addEventListener("click", () => {
            $("peerSearchInput").value = "";
            state.peerSearchQuery = "";
            state.peerRenderLimit = LIST_PAGE_SIZE;
            syncPeerClearBtn();
            if (state.report) renderPeerList(state.report);
            $("peerSearchInput").focus();
        });

        $("peerSortSelect").addEventListener("change", (e) => {
            state.peerSortBy = e.target.value;
            state.peerRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderPeerList(state.report);
        });

        const applyPeerRelFilter = (filterCode) => {
            state.peerRelFilter = ["ALL", "I_OWE", "UNREPLIED_POST", "THEY_OWE", "MUTUAL"].includes(filterCode)
                ? filterCode
                : "ALL";
            state.peerFilterTouched = true;
            state.peerRenderLimit = LIST_PAGE_SIZE;
            if (state.report) {
                syncInteractionFilterUI(state.report.interactions);
                renderPeerList(state.report);
            }
        };

        $("interactionTypePills").querySelectorAll(".pill-chip").forEach((chip) => {
            chip.addEventListener("click", () => applyPeerRelFilter(chip.dataset.relFilter));
        });

        // 统一「一键直达」：空状态按钮、右栏行动项等带 data-go 的元素（tab:xxx / peerFilter:xxx / period:xxx / clearPeerSearch）
        document.addEventListener("click", (e) => {
            const el = e.target && e.target.closest ? e.target.closest("[data-go]") : null;
            if (!el || !isLoggedIn()) return;
            e.preventDefault();
            const rawGo = String(el.dataset.go || "");
            const hasInPageTarget = rawGo.includes("scrollId:") || rawGo.includes("focusDraft:") || rawGo.includes("openAlgoConfig:");
            const timingTargetIds = new Set(["formatCardsGrid", "topTweetsList", "algoCalibrationPanel", "algoPoolSection", "contentAssistantShell", "prePostSimulatorCard"]);
            const parts = rawGo.split("|");
            const explicitTab = parts.some((p) => p.startsWith("tab:"));
            if (!explicitTab && (rawGo.includes("focusDraft:") || rawGo.includes("openAlgoConfig:") || parts.some((p) => p.startsWith("scrollId:") && timingTargetIds.has(p.slice(9))))) {
                if (state.activeTab !== "timing") switchTab("timing");
            }
            for (const part of parts) {
                const [kind, val] = part.split(":");
                if (kind === "tab" && val) {
                    const normalizedTab = val === "content" ? "timing" : val;
                    if (!(hasInPageTarget && state.activeTab === normalizedTab)) {
                        switchTab(normalizedTab);
                    }
                } else if (kind === "peerFilter") {
                    switchTab("interactions");
                    applyPeerRelFilter(val);
                } else if (kind === "followTab" && val) {
                    switchTab("follow-audit");
                    const tabBtn = document.querySelector(`#followStateTabs .seg-tab[data-fstate="${val}"]`);
                    if (tabBtn) tabBtn.click();
                } else if (kind === "followCat" && val) {
                    const catBtn = document.querySelector(`#unfollowCategoryPills .pill-chip[data-ucall="${val}"]`);
                    if (catBtn) catBtn.click();
                } else if (kind === "period" && val) {
                    const segBtn = document.querySelector(`.seg-btn[data-period="${val}"]`);
                    if (segBtn) segBtn.click();
                } else if (kind === "unlockGuide") {
                    openUnlockGuideModal(val && val !== "open" ? val : null);
                } else if (kind === "clearPeerSearch") {
                    $("peerSearchInput").value = "";
                    state.peerSearchQuery = "";
                    syncPeerClearBtn();
                    if (state.report) renderPeerList(state.report);
                } else if (kind === "clearFollowSearch") {
                    $("followFilterInput").value = "";
                    state.followSearchQuery = "";
                    const fClear = document.getElementById("clearFollowSearchBtn");
                    if (fClear) fClear.hidden = true;
                    if (state.report) renderFollowAuditPanel(state.report);
                } else if (kind === "focusDraft") {
                    const runFocus = () => {
                        const inp = $("draftSimulatorInput");
                        if (inp) {
                            inp.scrollIntoView({ behavior: "smooth", block: "center" });
                            inp.focus();
                        }
                    };
                    if (typeof requestAnimationFrame === "function") requestAnimationFrame(runFocus);
                    else runFocus();
                } else if (kind === "scrollId" && val) {
                    const runScroll = () => {
                        const targetEl = document.getElementById(val);
                        if (!targetEl) return;
                        const cardEl = val === "formatCardsGrid" ? (targetEl.closest(".x-section-card") || targetEl) : targetEl;
                        cardEl.scrollIntoView({ behavior: "smooth", block: "center" });
                        cardEl.classList.remove("is-target-flash");
                        void cardEl.offsetWidth;
                        cardEl.classList.add("is-target-flash");
                        setTimeout(() => cardEl.classList.remove("is-target-flash"), 1400);
                    };
                    if (typeof requestAnimationFrame === "function") requestAnimationFrame(runScroll);
                    else runScroll();
                } else if (kind === "openAlgoConfig") {
                    openAlgoConfigDrawer(true);
                    const sec = $("algoPoolSection");
                    if (sec) sec.scrollIntoView({ behavior: "smooth", block: "start" });
                }
            }
        });

        // 模块 3：关注列表筛选与排序（150ms 输入防抖 + 切换时重置分页）
        $("followStateTabs").querySelectorAll(".seg-tab").forEach((tab) => {
            tab.addEventListener("click", () => {
                state.followStateTab = tab.dataset.fstate;
                state.followRenderLimit = LIST_PAGE_SIZE;
                $("followStateTabs").querySelectorAll(".seg-tab").forEach((t) => {
                    t.classList.toggle("active", t === tab);
                });
                if (state.report) renderFollowAuditPanel(state.report);
            });
        });

        $("unfollowCategoryPills").querySelectorAll(".pill-chip").forEach((chip) => {
            chip.addEventListener("click", () => {
                state.unfollowCategory = chip.dataset.ucall;
                state.followRenderLimit = LIST_PAGE_SIZE;
                $("unfollowCategoryPills").querySelectorAll(".pill-chip").forEach((c) => {
                    c.classList.toggle("active", c === chip);
                });
                if (state.report) renderFollowAuditPanel(state.report);
            });
        });

        const syncFollowClearBtn = () => {
            const btn = document.getElementById("clearFollowSearchBtn");
            if (btn) btn.hidden = !$("followFilterInput").value;
        };
        const onFollowSearchDebounced = debounce(() => {
            state.followSearchQuery = $("followFilterInput").value.trim();
            state.followRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderFollowAuditPanel(state.report);
        }, 150);

        $("followFilterInput").addEventListener("input", (e) => {
            syncFollowClearBtn();
            onFollowSearchDebounced(e.target.value);
        });
        document.getElementById("clearFollowSearchBtn")?.addEventListener("click", () => {
            $("followFilterInput").value = "";
            state.followSearchQuery = "";
            state.followRenderLimit = LIST_PAGE_SIZE;
            syncFollowClearBtn();
            if (state.report) renderFollowAuditPanel(state.report);
            $("followFilterInput").focus();
        });

        $("followSortSelect").addEventListener("change", (e) => {
            state.followSortBy = e.target.value;
            state.followRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderFollowAuditPanel(state.report);
        });
        $("continueRelationshipsBtn").addEventListener("click", () => {
            if (!state.isFetching && state.pendingRelationshipChecks > 0) {
                return loadSelfReport({ continueRelations: true });
            }
        });

        // 模块 4：爆款代表推文排序与体裁筛选
        const topSortEl = $("topPostSortSelect");
        if (topSortEl) {
            topSortEl.addEventListener("change", (e) => {
                state.topPostSortBy = e.target.value;
                if (state.report) renderTopTweetsList(state.report);
            });
        }

        const topFmtEl = $("topPostFormatPills");
        if (topFmtEl) {
            topFmtEl.querySelectorAll(".pill-chip").forEach((chip) => {
                chip.addEventListener("click", () => {
                    state.topPostFormat = chip.dataset.tformat;
                    topFmtEl.querySelectorAll(".pill-chip").forEach((c) => {
                        c.classList.toggle("active", c === chip);
                    });
                    if (state.report) renderTopTweetsList(state.report);
                });
            });
        }

        const draftSimInput = $("draftSimulatorInput");
        if (draftSimInput) {
            const onDraftInput = debounce(() => evaluatePrePostDraft(), 80);
            draftSimInput.addEventListener("input", onDraftInput);
        }
        document.getElementById("restoreDraftUndoBtn")?.addEventListener("click", () => {
            const snap = state.draftUndoSnapshot;
            if (!snap) return;
            const inputEl = $("draftSimulatorInput");
            if (inputEl) inputEl.value = snap.text;
            const mediaChk = $("draftHasMediaCheck");
            if (mediaChk) mediaChk.checked = Boolean(snap.hasMedia);
            const threadChk = $("draftIsThreadCheck");
            if (threadChk) threadChk.checked = Boolean(snap.isThread);
            state.draftUndoSnapshot = null;
            const undoBar = document.getElementById("draftUndoBar");
            if (undoBar) undoBar.hidden = true;
            evaluatePrePostDraft();
            showToast("已恢复刚才手写草稿");
            inputEl?.focus();
        });
        document.getElementById("workspaceRetryBtn")?.addEventListener("click", () => {
            const retryCard = document.getElementById("workspaceRetryCard");
            if (retryCard) retryCard.hidden = true;
            void loadSelfReport(false);
        });
        const draftMediaChk = $("draftHasMediaCheck");
        if (draftMediaChk) {
            draftMediaChk.addEventListener("change", () => evaluatePrePostDraft());
        }
        const draftThreadChk = $("draftIsThreadCheck");
        if (draftThreadChk) {
            draftThreadChk.addEventListener("change", () => evaluatePrePostDraft());
        }
        const topTweetsListEl = $("topTweetsList");
        if (topTweetsListEl) {
            topTweetsListEl.addEventListener("click", (e) => {
                const btn = e.target && e.target.closest ? e.target.closest("[data-top-post-id]") : null;
                if (!btn) return;
                const postId = btn.dataset.topPostId;
                const posts = representativePostCards(state.report?.contentTiming);
                const post = posts.find((item) => String(item.id) === String(postId));
                if (!post) return;
                loadDraftIntoSimulator(post.text || "", {
                    hasMedia: Boolean(post.hasMedia),
                    isThread: Boolean(post.isThread)
                });
            });
        }
        const toggleAlgoBtn = $("toggleAlgoConfigBtn");
        if (toggleAlgoBtn) {
            toggleAlgoBtn.addEventListener("click", () => {
                openAlgoConfigDrawer(!state.algoConfigOpen);
            });
        }
        const saveAlgoBtn = $("saveAlgoProfileBtn");
        if (saveAlgoBtn) {
            saveAlgoBtn.addEventListener("click", () => submitAlgoProfileAction("save"));
        }
        const reloadAlgoBtn = $("reloadAlgoProfileBtn");
        if (reloadAlgoBtn) {
            reloadAlgoBtn.addEventListener("click", () => submitAlgoProfileAction("reload"));
        }
        const resetAlgoBtn = $("resetAlgoProfileBtn");
        if (resetAlgoBtn) {
            resetAlgoBtn.addEventListener("click", () => submitAlgoProfileAction("reset"));
        }

        // 模块 5：战报图隐私指标切换、2x PNG 下载与文案复制
        const privacyToggleBtn = $("toggleSharePrivacyBtn");
        if (privacyToggleBtn) {
            privacyToggleBtn.addEventListener("click", () => {
                state.shareHideFollowRate = !state.shareHideFollowRate;
                try {
                    localStorage.setItem("xops_hide_follow_rate", state.shareHideFollowRate ? "1" : "0");
                } catch (_) {}
                syncSharePrivacyBtnUI(state.report);
                if (state.report) {
                    drawShareCardCanvas(state.report);
                }
            });
        }

        $("downloadCardPngBtn").addEventListener("click", () => {
            if (!state.report) return;
            drawShareCardCanvas(state.report);
            const canvas = $("shareCardCanvas");
            let dataUrl;
            try {
                dataUrl = canvas.toDataURL("image/png");
            } catch (_) {
                // 若极端情况下跨域头像引发 Canvas 污染，自动回退纯矢量头像导出，确保下载 100% 成功
                drawShareCardCanvas(state.report, true);
                dataUrl = canvas.toDataURL("image/png");
            }
            const link = document.createElement("a");
            link.download = `x-ops-${state.period.toLowerCase()}-${state.report.profile.username}.png`;
            link.href = dataUrl;
            link.click();
            showToast("战报 2x 高清 PNG 已开始下载");
        });

        $("copyShareTextBtn").addEventListener("click", async () => {
            if (!state.report) return;
            const text = getEffectiveShareTweetText(state.report);
            try {
                await copyTextWithFallback(text);
                showToast("晒单推文文案已复制，可直接去 X 粘贴发布！");
            } catch (_) {
                showToast("复制失败，请手动选中复制");
            }
        });

        // 模块 6：战报图一键定时 & 图文定时工作台交互事件
        $("scheduleShareCardBtn")?.addEventListener("click", () => {
            if (!state.report) return;
            if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                openUnlockGuideModal("SCHEDULE");
                return;
            }
            drawShareCardCanvas(state.report);
            const canvas = $("shareCardCanvas");
            const fileName = `x-ops-${state.period.toLowerCase()}-${state.report.profile.username}.png`;
            const file = canvasToPngFile(canvas, fileName);
            if (sendTextDraftToSchedule(getEffectiveShareTweetText(state.report), [file]) !== false) {
                showToast("已将战报图与文案填入定时发布草稿");
            }
        });

        $("goOverdueScheduleBtn")?.addEventListener("click", () => {
            state.scheduleFilter = "ACTION_NEEDED";
            document.querySelectorAll("#scheduleStatusFilterPills .pill-chip").forEach((c) => {
                c.classList.toggle("active", c.dataset.sfilter === "ACTION_NEEDED");
            });
            switchTab("schedule");
        });

        $("addScheduleDraftItemBtn")?.addEventListener("click", () => {
            if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                showError(getScheduleLockedReason());
                return;
            }
            addNextScheduleDraftItem();
        });

        $("alignScheduleGapBtn")?.addEventListener("click", () => {
            ensureScheduleDraftInitialized();
            const rawEpochs = state.scheduleDraftItems.map((it) => parseDraftItemEpochMs(it));
            const aligned = alignBatchScheduleEpochs(
                rawEpochs,
                getQueuedScheduledEpochsMs(),
                Date.now(),
                (idx) => {
                    const jitterMin = randomScheduleJitterMinutes();
                    if (state.scheduleDraftItems[idx]) {
                        state.scheduleDraftItems[idx].recommendedJitterMin = jitterMin;
                    }
                    return MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
                }
            );
            state.scheduleDraftItems.forEach((it, idx) => {
                it.scheduledAtValue = formatEpochToDatetimeLocalValue(aligned[idx]);
                it.timeTouched = true;
                const domInput = document.querySelector(`#scheduleDraftItemsList [data-draft-time="${idx}"]`);
                if (domInput) domInput.value = it.scheduledAtValue;
            });
            rotateComposerRequestId();
            syncScheduleDraftConflictUI();
            renderScheduleQuickSlots();
            showToast("已按 1 小时间隔顺延对齐");
        });

        $("scheduleMediaFileInput")?.addEventListener("change", (e) => {
            if (e.target.files && e.target.files.length) {
                appendFilesToScheduleDraft(e.target.files, state.mediaPickerTargetIdx);
                e.target.value = "";
            }
        });

        const draftListEl = document.getElementById("scheduleDraftItemsList");
        if (draftListEl) {
            draftListEl.addEventListener("focusin", (e) => {
                const card = e.target && e.target.closest ? e.target.closest("[data-draft-idx]") : null;
                if (!card) return;
                const idx = Number(card.dataset.draftIdx);
                if (!Number.isInteger(idx) || idx < 0 || idx >= (state.scheduleDraftItems || []).length) return;
                const changedDraft = state.activeScheduleDraftIdx !== idx;
                state.activeScheduleDraftIdx = idx;
                if (changedDraft) renderScheduleQuickSlots();
                const isSingle = state.scheduleDraftItems.length === 1;
                draftListEl.querySelectorAll(".schedule-draft-item").forEach((el) => {
                    el.classList.toggle("is-active", !isSingle && Number(el.dataset.draftIdx) === idx);
                });
            });

            draftListEl.addEventListener("input", (e) => {
                const timeInput = e.target && e.target.matches && e.target.matches("[data-draft-time]") ? e.target : null;
                if (timeInput) {
                    const item = (state.scheduleDraftItems || [])[Number(timeInput.dataset.draftTime)];
                    if (item) {
                        item.scheduledAtValue = timeInput.value || "";
                        item.timeTouched = true;
                        rotateComposerRequestId();
                        clearScheduleComposeErrorForDraft(Number(timeInput.dataset.draftTime));
                        syncScheduleDraftConflictUI();
                    }
                    return;
                }
                const ta = e.target && e.target.matches && e.target.matches("[data-draft-textarea]") ? e.target : null;
                if (!ta) return;
                const idx = Number(ta.dataset.draftTextarea);
                const item = (state.scheduleDraftItems || [])[idx];
                if (!item) return;
                item.text = ta.value || "";
                rotateComposerRequestId();
                clearScheduleComposeErrorForDraft(idx);
                syncSingleDraftItemStats(idx);
            });

            draftListEl.addEventListener("change", (e) => {
                const ti = e.target && e.target.matches && e.target.matches("[data-draft-time]") ? e.target : null;
                if (!ti) return;
                const idx = Number(ti.dataset.draftTime);
                const item = (state.scheduleDraftItems || [])[idx];
                if (!item) return;
                item.scheduledAtValue = ti.value || "";
                item.timeTouched = true;
                cascadeSubsequentDraftEpochs(idx);
                rotateComposerRequestId();
                clearScheduleComposeErrorForDraft(idx);
                syncScheduleDraftConflictUI();
                renderScheduleQuickSlots();
            });

            draftListEl.addEventListener("keydown", (e) => {
                const ta = e.target && e.target.matches && e.target.matches("[data-draft-textarea]") ? e.target : null;
                if (ta && (e.metaKey || e.ctrlKey) && e.key === "Enter") {
                    e.preventDefault();
                    submitCreateScheduledTweet();
                }
            });

            draftListEl.addEventListener("paste", (e) => {
                const ta = e.target && e.target.matches && e.target.matches("[data-draft-textarea]") ? e.target : null;
                if (!ta) return;
                const idx = Number(ta.dataset.draftTextarea);
                const items = e.clipboardData && e.clipboardData.items ? Array.from(e.clipboardData.items) : [];
                const pastedImages = items
                    .filter((it) => it.kind === "file" && it.type && it.type.startsWith("image/"))
                    .map((it) => it.getAsFile())
                    .filter(Boolean);
                if (pastedImages.length) {
                    e.preventDefault();
                    appendFilesToScheduleDraft(pastedImages, idx);
                }
            });

            draftListEl.addEventListener("click", (e) => {
                const btn = e.target && e.target.closest ? e.target.closest("[data-draft-action]") : null;
                if (!btn) return;
                const action = btn.dataset.draftAction;
                const idx = Number(btn.dataset.draftIdx);
                if (!Number.isInteger(idx) || idx < 0 || idx >= (state.scheduleDraftItems || []).length) return;

                if (action === "add-media") {
                    state.activeScheduleDraftIdx = idx;
                    state.mediaPickerTargetIdx = idx;
                    $("scheduleMediaFileInput").click();
                } else if (action === "remove-media") {
                    const mIdx = Number(btn.dataset.removeMediaIdx);
                    const files = state.scheduleDraftItems[idx].files || [];
                    if (!Number.isInteger(mIdx) || mIdx < 0 || mIdx >= files.length) return;
                    const [removed] = files.splice(mIdx, 1);
                    if (removed && removed.previewUrl && typeof URL !== "undefined" && URL.revokeObjectURL) {
                        try { URL.revokeObjectURL(removed.previewUrl); } catch (_) {}
                    }
                    rotateComposerRequestId();
                    renderScheduleDraftMedia(idx);
                    clearScheduleComposeErrorForDraft(idx);
                    syncScheduleAuthBanner();
                } else if (action === "clear-quote") {
                    const draftItem = (state.scheduleDraftItems || [])[idx];
                    if (!draftItem) return;
                    draftItem.quoteTweetId = "";
                    draftItem.quoteTweetUrl = "";
                    if (draftItem.sourceRef) draftItem.sourceRef.quoteSource = false;
                    rotateComposerRequestId();
                    renderScheduleDraftItems();
                    queueScheduleDraftPersistence();
                } else if (action === "remove-item") {
                    removeScheduleDraftItem(idx);
                } else if (action === "check-algo") {
                    const draftItem = (state.scheduleDraftItems || [])[idx];
                    if (!draftItem) return;
                    loadDraftIntoSimulator(draftItem.text || "", {
                        hasMedia: (draftItem.files || []).length > 0,
                        isThread: computeWeightedTweetLength(draftItem.text || "") > 280
                    });
                }
            });
        }

        $("clearScheduleDraftBtn")?.addEventListener("click", () => {
            if (state.scheduleDraftRestoring) return;
            if ((state.scheduleDraftItems || []).some(hasScheduleDraftContent)
                && !window.confirm(globalThis.XOpsI18n?.translate("清空所有草稿、配图和已选时间？") ?? "清空所有草稿、配图和已选时间？")) return;
            clearScheduleComposerDraft();
            setScheduleComposerError("");
        });

        const schedComposeCard = document.getElementById("scheduleComposeCard");
        if (schedComposeCard) {
            let dragDepth = 0;
            schedComposeCard.addEventListener("dragenter", (e) => {
                if (!e.dataTransfer || !Array.from(e.dataTransfer.types || []).includes("Files")) return;
                e.preventDefault();
                dragDepth++;
                schedComposeCard.classList.add("is-dragover");
            });
            schedComposeCard.addEventListener("dragover", (e) => {
                if (!e.dataTransfer || !Array.from(e.dataTransfer.types || []).includes("Files")) return;
                e.preventDefault();
                e.dataTransfer.dropEffect = "copy";
                schedComposeCard.classList.add("is-dragover");
            });
            schedComposeCard.addEventListener("dragleave", () => {
                dragDepth = Math.max(0, dragDepth - 1);
                if (dragDepth === 0) schedComposeCard.classList.remove("is-dragover");
            });
            schedComposeCard.addEventListener("drop", (e) => {
                dragDepth = 0;
                schedComposeCard.classList.remove("is-dragover");
                if (!e.dataTransfer || !e.dataTransfer.files || !e.dataTransfer.files.length) return;
                e.preventDefault();
                if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                    showError(getScheduleLockedReason());
                    return;
                }
                const dropCard = e.target && e.target.closest ? e.target.closest("[data-draft-idx]") : null;
                const dropIdx = dropCard ? Number(dropCard.dataset.draftIdx) : state.activeScheduleDraftIdx;
                appendFilesToScheduleDraft(e.dataTransfer.files, dropIdx);
            });
        }

        $("scheduleQuickSlotChips")?.addEventListener("click", (e) => {
            const chip = e.target && e.target.closest ? e.target.closest("[data-quick-epoch]") : null;
            if (!chip) return;
            const ms = Number(chip.dataset.quickEpoch);
            if (Number.isFinite(ms)) {
                applyEpochToActiveScheduleDraft(ms);
            }
        });

        $("createScheduleBtn")?.addEventListener("click", submitCreateScheduledTweet);
        $("triggerPublishAuthBtn")?.addEventListener("click", triggerXPublishAuth);
        $("reauthorizePublishBtn")?.addEventListener("click", triggerXPublishAuth);
        $("refreshScheduleListBtn")?.addEventListener("click", () => loadScheduledTweets(false));
        $("batchConfirmOverdueBtn")?.addEventListener("click", submitBatchConfirmOverdue);

        $("scheduleStatusFilterPills")?.querySelectorAll(".pill-chip").forEach((chip) => {
            chip.addEventListener("click", () => {
                const nextFilter = chip.dataset.sfilter || "ALL";
                if (state.scheduleFilter === nextFilter) return;
                state.scheduleFilter = nextFilter;
                state.rescheduleEditingJobId = null;
                state.rescheduleDraftValue = null;
                state.rescheduleError = "";
                state.rescheduleErrorKind = "";
                $("scheduleStatusFilterPills").querySelectorAll(".pill-chip").forEach((c) => {
                    c.classList.toggle("active", c === chip);
                });
                renderScheduledTweetsList();
            });
        });

        const trackRescheduleInteraction = (e) => {
            if (e.target?.dataset?.reschedInput === state.rescheduleEditingJobId) {
                state.rescheduleInteractionVersion += 1;
            }
        };
        $("scheduledTweetsList")?.addEventListener("focusin", trackRescheduleInteraction);
        $("scheduledTweetsList")?.addEventListener("pointerdown", trackRescheduleInteraction);
        const updateRescheduleDraft = (e) => {
            if (e.target?.dataset?.reschedInput === state.rescheduleEditingJobId) {
                trackRescheduleInteraction(e);
                state.rescheduleDraftValue = e.target.value;
                setRescheduleError(state.rescheduleEditingJobId, "");
                preserveRescheduleEditor($("scheduledTweetsList"));
            }
        };
        $("scheduledTweetsList")?.addEventListener("input", updateRescheduleDraft);
        $("scheduledTweetsList")?.addEventListener("change", updateRescheduleDraft);
        $("scheduledTweetsList")?.addEventListener("click", async (e) => {
            const btn = e.target && e.target.closest ? e.target.closest("[data-job-action]") : null;
            if (!btn) return;
            const action = btn.dataset.jobAction;
            const jobId = btn.dataset.jobId;
            if (action === "close-reschedule") {
                if (state.rescheduleEditingJobId === jobId) {
                    state.rescheduleEditingJobId = null;
                    state.rescheduleDraftValue = null;
                    state.rescheduleError = "";
                    state.rescheduleErrorKind = "";
                    renderScheduledTweetsList();
                }
                return;
            }
            const job = (state.scheduleJobs || []).find((j) => j.id === jobId);
            if (!job) return;
            if (action === "copy-text") {
                if (!job.text || !job.text.trim()) {
                    showToast("这条任务没有文本");
                    return;
                }
                try {
                    await copyTextWithFallback(job.text);
                    showToast("已复制原文");
                } catch (_) {
                    showError("复制失败，请手动选择文本复制");
                }
                return;
            }
            if (action === "reauth") {
                await triggerXPublishAuth();
                return;
            }
            if (state.scheduleMutations.has(`job:${jobId}`) || state.scheduleMutations.has("batch-resend")) return;

            if (action === "open-confirm") {
                if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                    showError(getScheduleLockedReason());
                    return;
                }
                openConfirmResendModal(job);
            } else if (action === "toggle-reschedule") {
                if (!canRescheduleJob(job)) return;
                state.rescheduleEditingJobId = state.rescheduleEditingJobId === jobId ? null : jobId;
                state.rescheduleDraftValue = null;
                state.rescheduleError = "";
                state.rescheduleErrorKind = "";
                renderScheduledTweetsList();
            } else if (action === "save-reschedule") {
                if (!canRescheduleJob(job)) return;
                if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                    setRescheduleError(jobId, getScheduleLockedReason(), true);
                    return;
                }
                const input = document.querySelector(`[data-resched-input="${CSS.escape(jobId)}"]`);
                if (input) state.rescheduleDraftValue = input.value;
                const nextMs = input && input.value ? new Date(input.value).getTime() : NaN;
                if (!Number.isFinite(nextMs) || nextMs < Date.now() + 15_000) {
                    setRescheduleError(jobId, "请选择晚于当前时间至少 15 秒的发布时间。", true);
                    return;
                }
                const otherQueued = getQueuedScheduledEpochsMs(jobId);
                const conflict = otherQueued.find((qMs) => Math.abs(nextMs - qMs) < getRequiredScheduleIntervalMs());
                if (conflict !== undefined) {
                    setRescheduleError(jobId, "与 " + formatScheduleLocalTime(conflict) + " 的任务间隔不足 1 小时，请调整时间。", true);
                    return;
                }
                await submitScheduledJobAction(jobId, "reschedule", btn, nextMs);
            } else if (action === "cancel" || action === "delete") {
                const question = action === "cancel" ? "取消这条定时发布？"
                    : job.status === "PUBLISHED" ? "删除这条本站记录？已发布的 X 推文不受影响。"
                    : "删除这条任务记录及配图？删除后无法恢复。";
                if (!window.confirm(globalThis.XOpsI18n?.translate(question) ?? question)) return;
                await submitScheduledJobAction(jobId, action, btn);
            }
        });

        const confirmModal = document.getElementById("confirmResendModal");
        if (confirmModal) {
            confirmModal.addEventListener("click", (e) => {
                if (e.target === confirmModal) closeConfirmResendModal();
            });
        }
        $("confirmResendCloseBtn")?.addEventListener("click", closeConfirmResendModal);
        $("confirmResendCancelBtn")?.addEventListener("click", closeConfirmResendModal);
        $("confirmResendSubmitBtn")?.addEventListener("click", () => {
            if (state.pendingConfirmJob) {
                submitConfirmResend(state.pendingConfirmJob);
            }
        });

        // 移动端触控点按说明气泡（替代桌面 hover title）
        const tapPopover = document.getElementById("tapInfoPopover");
        if (tapPopover) {
            let tapPopoverTimer = null;
            let tapPopoverAnchor = null;
            const hideTapPopover = () => {
                clearTimeout(tapPopoverTimer);
                tapPopoverTimer = null;
                tapPopoverAnchor = null;
                tapPopover.hidden = true;
            };
            const showTapPopover = (anchorEl, text) => {
                if (!text) return;
                clearTimeout(tapPopoverTimer);
                tapPopoverAnchor = anchorEl;
                tapPopover.textContent = text;
                tapPopover.hidden = false;
                const rect = anchorEl.getBoundingClientRect();
                const popRect = tapPopover.getBoundingClientRect();
                const vw = window.innerWidth || 393;
                const vh = window.innerHeight || 852;
                const left = Math.max(12, Math.min(vw - popRect.width - 12, rect.left + rect.width / 2 - popRect.width / 2));
                const showAbove = rect.top > popRect.height + 16;
                const top = showAbove
                    ? Math.max(12, rect.top - popRect.height - 8)
                    : Math.min(vh - popRect.height - 12, rect.bottom + 8);
                tapPopover.style.left = `${Math.round(left)}px`;
                tapPopover.style.top = `${Math.round(top)}px`;
                tapPopoverTimer = setTimeout(hideTapPopover, 4000);
            };
            document.addEventListener("click", (e) => {
                const isTouchOrNarrow =
                    (typeof window !== "undefined" && window.matchMedia?.("(pointer: coarse)")?.matches) ||
                    (typeof window !== "undefined" && window.innerWidth <= 767);
                const trigger = e.target?.closest?.("#healthScoreChip, #profileMutualRateNum, .kpi-card[title], .stat-inline[title], #mobileAlgoStatusText, #dailyCacheStatusHint, .day-col[title]");
                if (!trigger || !isTouchOrNarrow) {
                    if (!tapPopover.hidden && !tapPopover.contains(e.target)) hideTapPopover();
                    return;
                }
                const tipHost = trigger.id === "profileMutualRateNum" ? (trigger.closest("[title]") || trigger) : trigger;
                const tipText = String(tipHost.getAttribute("title") || tipHost.dataset?.tip || "").trim();
                if (!tipText) return;
                if (!tapPopover.hidden && tapPopoverAnchor === tipHost) {
                    hideTapPopover();
                    return;
                }
                showTapPopover(tipHost, tipText);
            });
            window.addEventListener("scroll", hideTapPopover, { passive: true });
        }
    }

    async function init() {
        let savedTheme = null;
        try {
            localStorage.removeItem("xops_recent_handles");
            purgeLegacyClientBundles();
            savedTheme = localStorage.getItem("xops_theme");
            state.shareHideFollowRate = localStorage.getItem("xops_hide_follow_rate") === "1";
        } catch (_) {}

        const foundIdx = THEMES.findIndex((t) => t.id === savedTheme);
        applyTheme(foundIdx >= 0 ? foundIdx : 0);

        const urlParams = new URLSearchParams(window.location.search);
        state.storedMode = urlParams.get("stored") === "true";
        const urlPeriod = urlParams.get("period");
        const urlDays = urlParams.get("days");
        const urlTab = urlParams.get("tab");
        const authResult = urlParams.get("auth");
        const authErr = urlParams.get("auth_error");
        const publishAuthErr = urlParams.get("publish_auth_error");

        if (urlPeriod || urlDays) {
            const pMeta = normalizePeriod(urlPeriod, urlDays);
            state.period = pMeta.code;
            state.days = pMeta.days;
        }
        syncPeriodButtons();
        bindEvents();
        syncAuthConnectionUI();
        void fetchAlgoProfileStatus();
        try {
            await fetchServerAndAuthStatus();
        } catch (_) {
            return;
        }

        if (authResult === "publish_ok") {
            setPublishAuthError("");
            const cap = state.authStatus || {};
            if (!cap.writeAuthorized) showToast("发布权限尚未确认，请在定时发布页重新授权。");
            else if (cap.mediaWriteAuthorized && cap.offlineRefreshable) showToast("定时发布二次授权已开通！长效离线刷新已启用");
            else if (cap.offlineRefreshable) showToast("文本发布已授权，长效离线刷新已启用。");
            else if (cap.mediaWriteAuthorized) showToast("文本和图片发布已授权，尚未确认长效离线刷新。");
            else showToast("文本发布已授权，尚未确认长效离线刷新。");
            cleanAuthQueryParams();
        } else if (authResult === "ok") {
            showToast("X 授权登录成功！已进入您本人的专属运营工作台");
            cleanAuthQueryParams();
        } else if (publishAuthErr) {
            setPublishAuthError(publishAuthErr);
            cleanAuthQueryParams();
        } else if (authErr) {
            const cleanAuthErr = /access_denied/i.test(authErr)
                ? "你已取消 X 账号授权，如需使用请重新点击登录。"
                : authErr;
            state.authConnectionError = cleanAuthErr;
            syncAuthConnectionUI();
            cleanAuthQueryParams();
        }

        if (isLoggedIn()) {
            const nonOwner = state.authStatus?.ownerAllPermissions !== true;
            const validTabs = new Set(["activity", "interactions", "follow-audit", "timing", "circle", "schedule"]);
            const initialTab = !nonOwner && (urlTab === "schedule" || authResult === "publish_ok" || publishAuthErr)
                ? "schedule" : urlTab && validTabs.has(urlTab) ? urlTab : state.activeTab;
            switchTab(initialTab, { historyMode: "replace" });
            if (!nonOwner) {
                await restoreScheduleDraftAfterStepUpAuth();
                if (state.activeTab !== "schedule") {
                    void loadScheduledTweets(true);
                }
            }
            await loadSelfReport(false);
            if (!state.storedMode && isLoggedIn()) {
                await checkinWorkspaceUsage();
            }
        }
    }

    function cleanAuthQueryParams() {
        const u = new URL(window.location.href);
        u.searchParams.delete("auth");
        u.searchParams.delete("auth_error");
        u.searchParams.delete("publish_auth_error");
        u.searchParams.delete("user");
        window.history.replaceState({}, "", u.toString());
    }

    document.addEventListener("xops:languagechange", () => {
        if (state.report) {
            syncSharePrivacyBtnUI(state.report);
            drawShareCardCanvas(state.report);
        }
    });
    document.addEventListener("DOMContentLoaded", init);
})();
