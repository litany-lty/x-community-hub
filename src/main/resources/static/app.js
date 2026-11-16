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
        accessPolicy: null,
        coveredWindowDays: 0,
        pendingExpandCheck: false,
        pendingRelationshipChecks: 0,
        isFetching: false,
        isLoggingOut: false,
        reportRequestGeneration: 0,
        reportAbortController: null,
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
            panel.hidden = !state.report && panel.id !== "panel-schedule";
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
        state.report = null;
        state.bundle = null;
        state.dailyCacheStatus = null;
        state.accessPolicy = null;
        state.coveredWindowDays = 0;
        state.pendingRelationshipChecks = 0;
        state.pendingExpandCheck = false;
        state.cacheExpiresEpochMs = 0;
        state.isFetching = false;
        state.selectedPeerUsername = null;
        state.peerRelFilter = "MUTUAL";
        state.peerFilterTouched = false;
        state.peerSearchQuery = "";
        state.followSearchQuery = "";
        state.peerRenderLimit = LIST_PAGE_SIZE;
        state.followRenderLimit = LIST_PAGE_SIZE;
        state.whitelistSet = new Set();
        state.tabScrollPositions = {};
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

    function hasPeriodAccessDecision() {
        return Boolean(
            (state.accessPolicy && Array.isArray(state.accessPolicy.unlockedPeriods))
            || (state.storedMode && state.bundle)
        );
    }

    function isPeriodUnlocked(periodCode) {
        if (!periodCode || periodCode === "HOURS_24") return true;
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

        const metaEl = $("algoConfigMetaLine");
        if (metaEl) {
            const srcLabel = status && status.source === "LOCAL_OVERRIDE" ? "本地热覆盖" : "内置基线";
            const fp = status && status.fingerprint ? status.fingerprint : "-";
            const updatedAt = profile.updatedAt || "-";
            const ev = profile.upstreamEvidence || DEFAULT_ALGO_PROFILE.upstreamEvidence;
            const commitLabel = ev && ev.upstreamCommit ? ` · 上游源码 ${ev.upstreamCommit} (${ev.activeRankerService || "vm_ranker"})` : "";
            const overridePath = status && status.overridePath ? status.overridePath : "";
            metaEl.textContent = `生效规则：${nameText} (${verText})${commitLabel} · 更新日期 ${updatedAt} · 来源：${srcLabel} · 指纹 ${fp}${overridePath ? ` · 覆盖文件 ${overridePath}` : ""}`;
        }

        const logsEl = $("algoChangelogList");
        if (logsEl) {
            const logs = Array.isArray(profile.changelog) ? profile.changelog : [];
            logsEl.innerHTML = logs
                .map((line) => `<span class="algo-changelog-chip">${escapeHtml(line)}</span>`)
                .join("");
        }

        const canManage = Boolean(status && status.canManage);
        const actionRow = $("algoConfigActionRow");
        if (actionRow) actionRow.hidden = !canManage;

        const hintEl = $("algoConfigPermHint");
        if (hintEl) {
            hintEl.textContent = canManage
                ? "当前会话具备算法热更新权限：修改下方 JSON 后点击「保存并热生效」，系统将立即无外部 API 开销重算推荐与评分（也可直接修改磁盘覆盖文件自动热加载）。"
                : "只读视图：仅授权管理员或本地调试模式可修改全局规则；磁盘覆盖文件变更后后端会自动零停机重载。";
        }

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
                await loadSelfReport(false);
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
        if (tabId === "timing" && state.report) {
            const slots = (state.report.contentTiming && state.report.contentTiming.goldenSlots) || [];
            const nextBadge = $("nextGoldenWindowBadge");
            if (nextBadge) {
                const hint = computeNextGoldenWindowHint(slots);
                nextBadge.textContent = hint;
                nextBadge.hidden = !hint;
            }
            renderAlgoPoolAndSimulator(state.report);
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
        } finally {
            clearTimeout(timeoutId);
        }
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
            if (effectiveUser.avatarUrl) {
                void warmMediaAsset(effectiveUser.avatarUrl);
            }

            // 首次进入尚未拿到全量报告前，立即用本地快照预填横幅、头像、简介与计数，消除首屏布局跳变
            if (!state.report) {
                const isAuthorSelf = String(effectiveUser.username || "").replace(/^@+/, "").toLowerCase() === "elidlicom";
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
                $("visitXProfileLink").href = `https://x.com/${encodeURIComponent(effectiveUser.username)}`;
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

            $("oauthAdminSetupHint").hidden = true;
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
        window.location.href = "/api/auth/x/login";
    }

    function startCacheCooldownTicker(remainingSeconds) {
        clearInterval(state.cooldownTimerId);
        state.cacheExpiresEpochMs = Date.now() + Math.max(0, Number(remainingSeconds || 0)) * 1000;
        updateRefreshButtonState();
    }

    function updateRefreshButtonState() {
        const relationshipControls = $("relationshipContinueControls");
        relationshipControls.hidden = state.storedMode || !isLoggedIn() || state.pendingRelationshipChecks <= 0;
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
        const forceRefresh = !state.storedMode && !continueRelations && (options === true || options?.forceRefresh === true);
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

        // 仅已入库只读模式复用本人内存包；实时页始终交给服务端检查持久化缓存与覆盖。
        if (
            state.storedMode && state.bundle &&
            state.bundle[state.period] &&
            bundleOwnerId &&
            bundleOwnerId === authUserId
        ) {
            applyLoadedPeriodReport(state.bundle[state.period]);
            return;
        }

        if (state.isFetching) {
            if (!continueRelations) state.pendingExpandCheck = true;
            return;
        }
        const requestGeneration = ++state.reportRequestGeneration;
        const selectedPeriod = state.period;
        const selectedDays = state.days;
        const requestIsCurrent = () => requestGeneration === state.reportRequestGeneration &&
            !state.isLoggingOut && isLoggedIn() &&
            stableUserId(state.authStatus.user) === authUserId;
        state.isFetching = true;
        state.pendingExpandCheck = false;
        updateRefreshButtonState();

        hideError();
        $("loadingBar").hidden = false;
        if (!state.report) showLoadingSkeleton(true);
        const slowHintEl = $("loadingSlowHint");
        const selfHandle = authUname ? `@${authUname}` : "本人账号";
        const startedMs = Date.now();
        let elapsedTimer = null;

        const updateLoadingProgressText = () => {
            if (!slowHintEl || !requestIsCurrent()) return;
            const sec = Math.max(1, Math.floor((Date.now() - startedMs) / 1000));
            slowHintEl.textContent = state.storedMode ? `读取已入库报表（${sec}s）…` : `同步中（${sec}s）…`;
            slowHintEl.hidden = false;
        };

        const slowTimer = setTimeout(() => {
            updateLoadingProgressText();
            elapsedTimer = setInterval(updateLoadingProgressText, 1000);
        }, 2500);

        try {
            let batchLimit = 1;
            let previousStatus = null;
            let cycleStartedAt = null;
            for (let batch = 0; batch < batchLimit; batch++) {
                // 续批只采最近24小时，显示维度保持用户选择，不付费扩展历史。
                const reqDays = batch === 0 ? selectedDays || 1 : 1;
                const reqPeriod = batch === 0 ? selectedPeriod || "HOURS_24" : "HOURS_24";
                const sendRefresh = forceRefresh && batch === 0;
                if (!requestIsCurrent()) return;
                const abortCtrl = new AbortController();
                state.reportAbortController = abortCtrl;
                const fetchTimeout = setTimeout(() => abortCtrl.abort(), 55000);
                let resp;
                let bundleBody;
                try {
                    resp = await fetch(
                        `/api/analyze/bundle?period=${encodeURIComponent(reqPeriod)}&targetDays=${encodeURIComponent(reqDays)}${state.storedMode ? "&stored=true" : continueRelations ? "&continueRelations=true" : ""}${sendRefresh ? "&refresh=true" : ""}`,
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
                startCacheCooldownTicker(targetReport.cacheRemainingSeconds);
                applyLoadedPeriodReport(targetReport);
                const status = state.dailyCacheStatus;
                if (state.storedMode || continueRelations || !status?.canContinue || status.lastAttemptDegraded ||
                    state.pendingExpandCheck || state.period !== selectedPeriod || state.days !== selectedDays ||
                    !status.cycleStartedAt) break;
                if (batch === 0) cycleStartedAt = status.cycleStartedAt;
                if (status.cycleStartedAt !== cycleStartedAt) break;

                // 首响应给出明确未试队列后允许一批确认；之后只在实存数据/覆盖/可尝试数推进时继续。
                const movedEarlier = (next, prev) => Boolean(next && (!prev || Date.parse(next) < Date.parse(prev)));
                const progressed = !previousStatus ||
                    status.unattemptedMetricPosts < previousStatus.unattemptedMetricPosts ||
                    (!previousStatus.timelineCovered && status.timelineCovered) ||
                    (!previousStatus.inboundCovered && status.inboundCovered) ||
                    status.recentPosts > previousStatus.recentPosts || status.recentInbound > previousStatus.recentInbound ||
                    movedEarlier(status.timelineCoveredFrom, previousStatus.timelineCoveredFrom) ||
                    movedEarlier(status.inboundCoveredFrom, previousStatus.inboundCoveredFrom);
                if (!progressed) break;
                previousStatus = status;
                // 指标接口每批至多50帖；每个未覆盖流预留3批，已知队列变长可扩展，但一次动作最多20批。
                const streamBatches = (Number(!status.timelineCovered) + Number(!status.inboundCovered)) * 3;
                batchLimit = Math.min(20, Math.max(batchLimit, batch + 1 + Math.ceil(status.unattemptedMetricPosts / 50) + streamBatches));
            }
        } catch (err) {
            if (!requestIsCurrent()) return;
            const msg = state.storedMode && err && err.name === "AbortError"
                ? "已入库报表读取超时，请重试当前模式"
                : err && err.name === "AbortError"
                ? "上游数据响应超时，请再点一次对应时间段继续从断点补齐"
                : (err.message || "网络或数据服务异常，请稍后重试");
            showError(msg);
            if (!state.report) {
                showLoadingSkeleton(false);
                $("profilePersonaTag").textContent = state.storedMode ? "已入库不可用" : "同步未完成";
                $("profileBio").textContent = "";
                $("profileBio").hidden = true;
                $("topbarWindowHint").textContent = state.storedMode ? "已入库模式 · 请稍后重试" : "请稍后重试";
            }
        } finally {
            clearTimeout(slowTimer);
            clearInterval(elapsedTimer);
            if (!requestIsCurrent()) return;
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
        const ids = ["authorPromptModal", "unlockGuideModal", "confirmResendModal"];
        const anyOpen = ids.some((id) => {
            const el = document.getElementById(id);
            return Boolean(el && !el.hidden);
        });
        document.body.classList.toggle("modal-open", anyOpen);
    }

    function buildFallbackAuthorCardData() {
        const selfHandle = String(state.authStatus?.user?.username || "").replace(/^@+/, "");
        const selfIsAuthor = selfHandle.toLowerCase() === "elidlicom";
        const cachedAuthor = (state.report && state.report.profile && selfIsAuthor)
            ? state.report.profile
            : null;
        return {
            usageCount: state.usageCount || 0,
            modalReasonCode: "MANUAL_PREVIEW",
            modalBadgeText: state.usageCount > 0 ? `作者主页 · 第 ${state.usageCount} 次使用` : "作者主页 · 开源说明",
            modalTitle: "你好，我是作者 @elidlicom",
            modalMessage:
                "每次使用本工作台，后端都会真实付费调用 X / 数据接口。项目免费开放并全量开源，结个善缘，欢迎关注 @elidlicom 交流进展！\n\n" +
                AUTHOR_COST_STORY,
            followingAuthor: Boolean(state.accessPolicy?.followsAuthor),
            selfIsAuthor,
            authorProfile: {
                username: "elidlicom",
                displayName: cachedAuthor?.displayName || "Litany（李丰一）",
                avatarUrl: "/author-avatar.webp",
                bannerUrl: AUTHOR_BANNER_URL,
                bio: cachedAuthor?.bio || AUTHOR_DEFAULT_BIO,
                verified: cachedAuthor?.verified !== undefined ? Boolean(cachedAuthor.verified) : true,
                followingCount: cachedAuthor?.followingCount || 0,
                followersCount: cachedAuthor?.followersCount || 0,
                totalPostsCount: cachedAuthor?.totalPostsCount || 0
            },
            authorProfileUrl: "https://x.com/elidlicom",
            authorFollowIntentUrl: "https://x.com/intent/follow?screen_name=elidlicom"
        };
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
            username: rawAuthor.username || "elidlicom",
            displayName: rawAuthor.displayName || "Litany（李丰一）",
            avatarUrl: rawAuthor.avatarUrl || "/author-avatar.webp",
            bannerUrl: rawAuthor.bannerUrl || AUTHOR_BANNER_URL,
            bio: cleanAuthorBio,
            verified: rawAuthor.verified !== undefined ? Boolean(rawAuthor.verified) : true,
            followingCount: rawAuthor.followingCount || 0,
            followersCount: rawAuthor.followersCount || 0,
            tweetCount: rawAuthor.totalPostsCount || rawAuthor.tweetCount || 0
        };
        const handle = String(author.username || "elidlicom").replace(/^@+/, "");
        const badgeEl = document.getElementById("authorModalBadge");
        if (badgeEl) {
            if (data.modalReasonCode === "MANUAL_PREVIEW") {
                const countVal = Number(data.usageCount || state.usageCount || 0);
                badgeEl.textContent = countVal > 0 ? `作者主页 · 第 ${countVal} 次使用` : "作者主页 · 开源说明";
            } else {
                badgeEl.textContent = data.modalBadgeText || `第 ${data.usageCount || state.usageCount || 3} 次使用`;
            }
        }
        $("authorModalTitle").textContent = data.modalTitle || `你好，我是作者 @${handle}`;

        const rawMessage = String(
            data.modalMessage || `因为每次使用这个网站服务，我都是真实付费调用 API 的。结个善缘，求关注 @${handle}！`
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
        $("authorModalHandle").textContent = `@${handle}`;
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

        const safeProfileUrl = safeHttpUrl(data.authorProfileUrl, `https://x.com/${encodeURIComponent(handle)}`);
        const safeFollowUrl = safeHttpUrl(
            data.authorFollowIntentUrl,
            `https://x.com/intent/follow?screen_name=${encodeURIComponent(handle)}`
        );
        const headerLink = $("authorModalHeaderLink");
        headerLink.href = safeProfileUrl;
        headerLink.textContent = `@${handle} ↗`;

        const followBtn = $("authorModalFollowBtn");
        followBtn.href = isFollowing || isSelf ? safeProfileUrl : safeFollowUrl;
        $("authorModalFollowBtnText").textContent = isSelf
            ? "查看我的 X 主页 ↗"
            : isFollowing
              ? `已关注 · 访问 @${handle} 主页 ↗`
              : `关注 @${handle} ↗`;

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
    }

    function closeAuthorPromptModal() {
        const modal = document.getElementById("authorPromptModal");
        if (modal) modal.hidden = true;
        syncBodyModalLock();
    }

    function openUnlockGuideModal(focusPeriodCode = null) {
        const modal = document.getElementById("unlockGuideModal");
        if (!modal) return;
        const p = state.accessPolicy;
        const statusBox = document.getElementById("unlockGuideStatusBox");
        if (statusBox) {
            if (p) {
                const rankBadge = p.top5FullAccess
                    ? `互动第 ${Number(p.authorInteractionRank || 1)} 名（前 5 免等待全开）`
                    : p.top50AutoSync
                      ? `互动第 ${Number(p.authorInteractionRank || 1)} 名（前 50 每日自动同步）`
                      : `24h 物理落盘 · 30 天滚动窗口`;
                const followText = p.followsAuthor ? "已关注作者" : "未关注作者";
                const quotaText = Number(p.dailyRefreshLimit || 1) > 10
                    ? "不限刷新"
                    : `今日剩余刷新 ${Number(p.dailyRefreshRemaining ?? 0)}/${Number(p.dailyRefreshLimit || 1)}`;
                statusBox.innerHTML =
                    `<div>已累计 <strong>${Number(p.accumulatedDays || 1)}/30</strong> 天 · ${followText} · ${quotaText}</div>` +
                    `<div>${rankBadge}</div>`;
            } else {
                statusBox.textContent = "默认 24 小时 · 单次加载 24h · 每日物理落盘 · 滚动保留 30 天。";
            }
        }
        const focusEl = document.getElementById("unlockGuideFocusMsg");
        if (focusEl) {
            const reason = focusPeriodCode && p?.lockedReasons?.[focusPeriodCode];
            if (reason) {
                focusEl.textContent = `未解锁原因：${reason}`;
                focusEl.hidden = false;
            } else if (focusPeriodCode && focusPeriodCode !== "HOURS_24") {
                const label = PERIOD_META[focusPeriodCode]?.badgePrefix || focusPeriodCode;
                focusEl.textContent = `${label}暂未解锁：每日物理存储 24 小时数据，累计满对应天数后自动开放。`;
                focusEl.hidden = false;
            } else {
                focusEl.hidden = true;
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
            followEl.href = followed
                ? "https://x.com/elidlicom"
                : "https://x.com/intent/follow?screen_name=elidlicom";
            followTxtEl.textContent = followed
                ? "已关注 · 访问 @elidlicom 主页 ↗"
                : "关注 @elidlicom ↗";
        }
        modal.hidden = false;
        syncBodyModalLock();
    }

    function closeUnlockGuideModal() {
        const modal = document.getElementById("unlockGuideModal");
        if (modal) modal.hidden = true;
        syncBodyModalLock();
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
            status.lastCompletedAt ? `最近完整更新 ${fmtTime(status.lastCompletedAt)}` : "尚无完整更新记录",
            `近24h推文${status.timelineCovered ? "已覆盖" : "待补齐"} ${status.recentPosts} 条`,
            `收到的互动${status.inboundCovered ? "已覆盖" : "待补齐"} ${status.recentInbound} 条`,
            status.unattemptedMetricPosts === undefined ? `待补指标 ${status.pendingMetricPosts} 帖`
                : `可补指标 ${status.unattemptedMetricPosts} 帖 · 本轮暂不可得 ${Math.max(0, status.pendingMetricPosts - status.unattemptedMetricPosts)} 帖`
        ] : [report.fetchedAt ? `原报表终点 ${fmtTime(report.fetchedAt)}` : ""];
        el.textContent = parts.filter(Boolean).join(" · ");
        el.title = [el.textContent, status?.cycleStartedAt ? `本轮采集起点 ${fmtTime(status.cycleStartedAt)}` : ""].filter(Boolean).join(" · ");
        el.hidden = !el.textContent;
    }

    function renderProfileHeader(report) {
        const p = report.profile;
        const act = report.activity;
        const audit = report.followAudit;
        const pMeta = normalizePeriod(report.periodCode, report.windowDays);

        // 左下角本人身份
        $("railDisplayName").textContent = p.displayName;
        syncUsageHandleDisplay(p.username);
        $("railAvatar").outerHTML = avatarHtml(p, "sm").replace(
            'class="avatar-circle sm"',
            'class="avatar-circle sm" id="railAvatar"'
        );

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
        const isAuthorSelf = String(p.username || "").replace(/^@+/, "").toLowerCase() === "elidlicom";
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
        $("visitXProfileLink").href = `https://x.com/${encodeURIComponent(p.username)}`;
        const hs = Number(act.activityHealthScore || 0);
        $("healthScoreChip").textContent = hs < 0 ? "健康 --" : `健康 ${hs}`;
        $("healthScoreChip").className = `health-score-chip ${hs < 0 ? "" : hs >= 50 ? "judge-good" : "judge-warn"}`;

        $("profileFollowingNum").textContent = fmtNum(p.followingCount);
        $("profileFollowersNum").textContent = fmtNum(p.followersCount);
        const followBackRateComplete = audit.followBackRateComplete ??
            (!audit.followersSampleTruncated && !audit.followingsSampleTruncated && Number(audit.unknownFollowBackCount || 0) === 0);
        $("profileMutualRateNum").textContent = `${followBackRateComplete ? "" : "~"}${audit.followBackRatePct}%`;
        $("profileMutualRateNum").title = audit.followBackRateLabel || `${followBackRateComplete ? "关注" : "样本"}回关率 ${audit.followBackRatePct}%`;
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
        noticeStrip.textContent = rawNotice;
        noticeStrip.hidden = !rawNotice;

        const syncSummary = document.getElementById("syncStatusSummary");
        if (syncSummary) {
            const status = state.dailyCacheStatus;
            const pendingMetrics = Number(status?.pendingMetricPosts ?? NaN);
            const unattemptedMetrics = Number(status?.unattemptedMetricPosts ?? NaN);
            const isDemo = report.provider === "DEMO" || /DEMO|仿真|演示/i.test(rawNotice);
            // Only recognize complete, built-in notices; unfamiliar text stays visible as a caution.
            const knownDailyNotice = /^(?:最近 24 小时数据与指标已完成本轮同步；满 1 小时后再次检查更新。|本轮最近 24 小时数据或指标尚未补齐；继续补充可获取数据，未计为完整新鲜缓存。|本轮可请求的数据已尝试完毕，仍有未确认项；保留原值和缺失状态，下一小时再检查，不重复请求本轮失败项。)已入库 \d+ 条动态、\d+ 条互动；历史数据仍按原覆盖范围累计。$/.test(rawNotice);
            const knownStorageNotice =
                /^单次加载 24 小时并已完成物理落盘（当前滚动保留近 30 天共 \d+ 条动态、\d+ 条互动）(?:（@\w+ 互动第 \d+ 名·已解锁全时间窗口）)?$/.test(rawNotice) ||
                /^每日物理存储模式：\d+ 条动态、\d+ 条互动已落盘。(?:你位列 @\w+ 互动前 \d+（第 \d+ 名），每日自动同步最近 24 小时并落盘；)?单次仅加载 24 小时，动态保留近 30 天窗口数据。$/.test(rawNotice);
            const dataIncomplete = status?.timelineCovered === false || status?.inboundCovered === false ||
                (pendingMetrics > 0 && (!Number.isFinite(unattemptedMetrics) || unattemptedMetrics > 0));
            const metricsUnavailable = pendingMetrics > 0 && unattemptedMetrics === 0;
            const relationIncomplete = audit.followersSampleTruncated || audit.followingsSampleTruncated ||
                Number(audit.unknownFollowBackCount || 0) > 0 || Number(audit.unknownFollowingCount || 0) > 0 ||
                state.pendingRelationshipChecks > 0;
            const needsAttention = Boolean(status?.lastAttemptDegraded || relationIncomplete || act.sampleCapped ||
                shortCoverage || (rawNotice && !isDemo && !knownDailyNotice && !knownStorageNotice) ||
                (status?.canContinue && !dataIncomplete && !metricsUnavailable));
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
                syncDetails.classList?.toggle?.("is-attention", isDemo || needsAttention || dataIncomplete || metricsUnavailable);
                const attentionKey = needsAttention && !isDemo
                    ? [rawNotice, shortCoverage, summaryText, Boolean(status?.lastAttemptDegraded),
                        audit.followersSampleTruncated, audit.followingsSampleTruncated,
                        audit.unknownFollowBackCount, audit.unknownFollowingCount, state.pendingRelationshipChecks].join("|")
                    : "";
                if (attentionKey && attentionKey !== syncDetails._lastAttentionKey) syncDetails.open = true;
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

        $("dailyTrendChart").innerHTML = series
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
        const iOwe = Number(inter.inboundHeavyPeers || 0);
        const unreplied = Number(inter.unrepliedMyPostPeers || 0);
        const theyOwe = Number(inter.outboundHeavyPeers || 0);
        const mutual = Number(inter.balancedMutualPeers || 0);

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

    function buildPeerDetailHtml(report, item) {
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
                <a class="x-outline-pill sm" href="${escapeHtml(peerLatestPostsUrl(peer.username))}" target="_blank" rel="noopener noreferrer">最新推文 ↗</a>
                <a class="x-outline-pill sm" href="${escapeHtml(peerDialogueUrl(me.username, peer.username))}" target="_blank" rel="noopener noreferrer">对话 ↗</a>
                <a class="x-outline-pill sm" href="https://x.com/${encodeURIComponent(peer.username)}" target="_blank" rel="noopener noreferrer">主页 ↗</a>
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

        const activeFilter = ["I_OWE", "UNREPLIED_POST", "THEY_OWE", "MUTUAL"].includes(state.peerRelFilter)
            ? state.peerRelFilter
            : "MUTUAL";
        list = list.filter((p) => {
            if (activeFilter === "UNREPLIED_POST") {
                return Number(p.unrepliedInMyPostCount || 0) > 0;
            }
            return p.reciprocityCode === activeFilter;
        });
        if (state.peerSearchQuery) {
            const q = state.peerSearchQuery.replace(/^@/, "").toLowerCase();
            list = list.filter(
                (p) =>
                    p.peer.username.toLowerCase().includes(q) ||
                    (p.peer.displayName || "").toLowerCase().includes(q)
            );
        }

        const effectiveSort = state.peerSortBy || "intimacy";
        list.sort((a, b) => {
            const totalA = (a.myRepliesToPeer || 0) + (a.peerRepliesToMe || 0);
            const totalB = (b.myRepliesToPeer || 0) + (b.peerRepliesToMe || 0);
            const blueDiff = (b.peer?.verified ? 1 : 0) - (a.peer?.verified ? 1 : 0);
            switch (effectiveSort) {
                case "iOweFirst":
                    return (
                        (b.unrepliedInMyPostCount || 0) - (a.unrepliedInMyPostCount || 0) ||
                        (b.peerRepliesToMe - b.myRepliesToPeer) - (a.peerRepliesToMe - a.myRepliesToPeer) ||
                        b.peerRepliesToMe - a.peerRepliesToMe ||
                        blueDiff
                    );
                case "theyOweFirst":
                    return (
                        (b.myRepliesToPeer - b.peerRepliesToMe) - (a.myRepliesToPeer - a.peerRepliesToMe) ||
                        b.myRepliesToPeer - a.myRepliesToPeer ||
                        blueDiff
                    );
                case "outbound":
                    return b.myRepliesToPeer - a.myRepliesToPeer || totalB - totalA || blueDiff;
                case "inbound":
                    return b.peerRepliesToMe - a.peerRepliesToMe || totalB - totalA || blueDiff;
                default:
                    return (
                        (b.intimacyScore || 0) - (a.intimacyScore || 0) ||
                        totalB - totalA ||
                        (b.peerRepliesToMe || 0) - (a.peerRepliesToMe || 0) ||
                        blueDiff
                    );
            }
        });

        if (list.length === 0) {
            const filterEmptyMsg = {
                I_OWE: "暂无待回访推友",
                UNREPLIED_POST: "主推评论均已回复",
                THEY_OWE: "暂无未回访我的推友",
                MUTUAL: "当前窗口暂无双向互评推友"
            };
            const msg = state.peerSearchQuery
                ? `没有找到「${state.peerSearchQuery}」`
                : filterEmptyMsg[activeFilter] || "这个时间段内还没有互动记录";
            const inter = report.interactions || {};
            const altActions = [];
            if (activeFilter !== "I_OWE" && Number(inter.inboundHeavyPeers || 0) > 0) {
                altActions.push({ text: "查看待回访", action: "peerFilter:I_OWE" });
            } else if (activeFilter !== "THEY_OWE" && Number(inter.outboundHeavyPeers || 0) > 0) {
                altActions.push({ text: "查看未回访我的推友", action: "peerFilter:THEY_OWE" });
            } else if (activeFilter !== "MUTUAL" && Number(inter.balancedMutualPeers || 0) > 0) {
                altActions.push({ text: "看互相评论", action: "peerFilter:MUTUAL" });
            }
            const acts = state.peerSearchQuery
                ? [{ text: "清空搜索", action: "clearPeerSearch" }]
                : [...altActions, ...widerPeriodActions()];
            listEl.innerHTML = emptyStateHtml(msg, acts);
            return;
        }

        const visiblePeers = list.slice(0, state.peerRenderLimit);
        const remainPeers = Math.max(0, list.length - visiblePeers.length);
        const meUsername = report.profile.username;
        const expandedKey = (state.expandedPeerUsername || "").toLowerCase();

        const rowsMarkup = visiblePeers
            .map((item) => {
                const u = item.peer;
                const isOpen = expandedKey === u.username.toLowerCase();
                const meta = getPeerStatusMeta(item, meUsername, state.peerRelFilter);
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
                return `<div class="peer-item ${isOpen ? "is-open" : ""}" data-list-key="${escapeHtml(u.username.toLowerCase())}">
                    <div class="x-user-row clickable peer-row" data-peer-handle="${escapeHtml(u.username)}" role="button" tabindex="0" aria-expanded="${isOpen}">
                        ${avatarHtml(u, "md")}
                        <div class="user-row-main">
                            <div class="peer-row-line">
                                <span class="peer-name" data-i18n-ignore>${escapeHtml(u.displayName)}</span>
                                ${verifiedSvgHtml(u.verified)}
                                <span class="user-handle-muted" data-i18n-ignore>@${escapeHtml(u.username)}</span>
                                ${mainTagHtml}
                                ${extraTagHtml}
                            </div>
                            <div class="peer-row-sub">
                                去评 <strong>${item.myRepliesToPeer}</strong> · 来评 <strong>${item.peerRepliesToMe}</strong> · ${escapeHtml(followRelationText(item, report))}
                            </div>
                        </div>
                        <a class="${actionCls} peer-ext-link" href="${escapeHtml(meta.actionUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(meta.actionText)} ↗</a>
                        <svg class="peer-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="M3.543 8.96l1.414-1.42L12 14.59l7.043-7.05 1.414 1.42L12 17.41 3.543 8.96z"/></svg>
                    </div>
                    ${isOpen ? buildPeerDetailHtml(report, item) : ""}
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
            const handle = row.dataset.peerHandle;
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

    /* ==================== 模块 4：黄金时段热力图与爆款内容 ==================== */
    function computeNextGoldenWindowHint(slots) {
        if (!slots || slots.length === 0) return "";
        const dayMap = { "周一": 1, "周二": 2, "周三": 3, "周四": 4, "周五": 5, "周六": 6, "周日": 7 };
        try {
            const parts = new Intl.DateTimeFormat("en-US", {
                timeZone: state.report?.contentTiming?.timezone || "Asia/Shanghai",
                weekday: "short",
                hour: "2-digit",
                minute: "2-digit",
                hour12: false
            }).formatToParts(new Date());
            const wkMap = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };
            const wk = wkMap[(parts.find((p) => p.type === "weekday") || {}).value] || 1;
            const hr = Number((parts.find((p) => p.type === "hour") || {}).value || 0) % 24;
            const min = Number((parts.find((p) => p.type === "minute") || {}).value || 0);
            const nowWeekMin = (wk - 1) * 1440 + hr * 60 + min;

            let bestWaitMin = Infinity;
            let bestSlotLabel = "";
            let activeNowLabel = "";

            for (const s of slots) {
                const m = String(s.label || "").match(/^(周[一二三四五六日])\s+(\d{2}):00[–-](\d{2}):00$/);
                if (!m) continue;
                const d = dayMap[m[1]];
                const startH = Number(m[2]);
                const endH = Number(m[3]);
                const slotStartMin = (d - 1) * 1440 + startH * 60;
                const slotEndMin = (d - 1) * 1440 + endH * 60;
                if (nowWeekMin >= slotStartMin && nowWeekMin < slotEndMin) {
                    activeNowLabel = s.label;
                    break;
                }
                let diff = slotStartMin - nowWeekMin;
                if (diff < 0) diff += 7 * 1440;
                if (diff < bestWaitMin) {
                    bestWaitMin = diff;
                    bestSlotLabel = s.label;
                }
            }

            if (activeNowLabel) {
                return `🟢 当前正处于黄金档 ${activeNowLabel}`;
            }
            if (bestSlotLabel && Number.isFinite(bestWaitMin)) {
                const waitH = Math.floor(bestWaitMin / 60);
                const waitM = bestWaitMin % 60;
                return waitH >= 24
                    ? `⏰ 下档 ${bestSlotLabel} · ${Math.floor(waitH / 24)}天${waitH % 24}h`
                    : `⏰ 下档 ${bestSlotLabel} · ${waitH}h${waitM}m`;
            }
        } catch (_) {}
        return "";
    }

    function timingPeriodDescription() {
        const timezone = state.report?.contentTiming?.timezone || "Asia/Shanghai";
        return timezone === "Asia/Shanghai" ? "北京时间" : timezone;
    }

    function renderTimingPanel(report) {
        const ct = report.contentTiming;
        const slots = ct.goldenSlots || [];
        const ranks = ["#1", "#2", "#3"];

        const periodDescEl = $("timingPeriodDesc");
        const tzText = timingPeriodDescription();
        periodDescEl.textContent = tzText;
        periodDescEl.classList.remove("is-readout");
        periodDescEl.hidden = !tzText;

        const nextBadge = $("nextGoldenWindowBadge");
        if (nextBadge) {
            const hint = computeNextGoldenWindowHint(slots);
            nextBadge.textContent = hint;
            nextBadge.hidden = !hint;
        }

        $("goldenSlotsGrid").innerHTML = slots.length
            ? slots
                  .map(
                      (s, i) => `<div class="golden-card" data-golden-slot="${escapeHtml(s.label)}" role="button" tabindex="0" title="点击将该黄金时段填入定时发布">
                      <div class="golden-rank">${ranks[i] || `#${i + 1}`} <span class="golden-sched-hint">定时 ›</span></div>
                      <div class="golden-time">${escapeHtml(s.label)}</div>
                      <div class="golden-meta">互动率 <strong>${s.avgEngagementRatePct}%</strong> · 均浏览 ${fmtNum(s.avgViews)} · ${s.postCount} 帖</div>
                  </div>`
                  )
                  .join("")
            : emptyStateHtml("样本不足，暂无黄金时段", widerPeriodActions());

        const rowsHtml = [];
        const cells = ct.heatmapCells || [];
        for (let d = 1; d <= 7; d++) {
            const rowCells = cells.filter((c) => c.dayOfWeek === d);
            if (!rowCells.length) continue;
            const dayName = rowCells[0].dayLabel;
            const cellSpans = rowCells
                .map((c) => {
                    const alpha = c.postCount > 0 ? Math.max(0.2, c.intensity * 0.9) : 0.06;
                    const bg = c.postCount > 0 ? `rgba(29, 155, 240, ${alpha.toFixed(2)})` : "rgba(113,118,123,0.10)";
                    const color = c.intensity > 0.52 ? "#ffffff" : "var(--text-secondary)";
                    const cellEr = hasMetricSample(c, "engagementRateSampleCount") ? `${c.avgEngagementRatePct}%` : "--";
                    const slotRange = `${String(c.hour).padStart(2, "0")}:00–${String(c.hour + 3).padStart(2, "0")}:00`;
                    const tip = c.postCount > 0
                        ? `${c.dayLabel} ${slotRange} · ${c.postCount} 帖 · 互动率 ${cellEr}${hasMetricSample(c, "viewSampleCount") ? ` · 均浏览 ${fmtNum(c.avgViews)}` : ""}`
                        : `${c.dayLabel} ${slotRange} · 暂无发帖`;
                    return `<div class="hm-cell" data-tip="${escapeHtml(tip)}" role="button" tabindex="0" style="background:${bg}; color:${color}" title="${escapeHtml(tip)}">
                        ${c.postCount > 0 ? cellEr : "·"}
                    </div>`;
                })
                .join("");
            rowsHtml.push(`<div class="hm-row"><span class="hm-day-label">${escapeHtml(dayName)}</span>${cellSpans}</div>`);
        }
        $("heatmapGridRows").innerHTML = rowsHtml.join("");

        const formatStats = ct.formatStats || [];
        const knownFormatStats = formatStats.filter(
            (f) => (Number(f.algoScoreSampleCount) > 0 && Number(f.avgAlgoScore) > 0)
                || (Number(f.viewSampleCount) > 0 && Number(f.avgViews) > 0)
        );
        const bestFormat = knownFormatStats.length >= 2
            ? [...knownFormatStats].sort((a, b) =>
                (Number(b.avgAlgoScore || 0) - Number(a.avgAlgoScore || 0))
                || (Number(b.avgViews || 0) - Number(a.avgViews || 0))
            )[0]
            : null;
        const bestFormatCode = bestFormat ? bestFormat.formatCode : "";

        $("formatCardsGrid").innerHTML = formatStats
            .map((f) => {
                const isWinner = Boolean(bestFormatCode && f.formatCode === bestFormatCode && f.postCount > 0);
                const bmKnown = hasMetricSample(f, "bookmarkSampleCount");
                return `<div class="format-card${isWinner ? " is-winning-format" : ""}">
                    <div class="format-head-row">
                        <div class="format-title">${escapeHtml(f.formatLabel)}</div>
                        ${isWinner ? `<span class="mini-tag green format-win-badge">表现最佳</span>` : ""}
                    </div>
                    <div class="format-er">${hasMetricSample(f, "engagementRateSampleCount") ? `${f.avgEngagementRatePct}%` : "--"} <span style="font-size:11.5px; font-weight:500; color:var(--text-secondary)">互动率</span></div>
                    <div class="golden-meta">${f.postCount} 帖 · 均浏览 ${hasMetricSample(f, "viewSampleCount") ? fmtNum(f.avgViews) : "--"}</div>
                    <div class="golden-meta">赞 ${hasMetricSample(f, "likeSampleCount") ? f.avgLikes : "--"} · 评 ${hasMetricSample(f, "replySampleCount") ? f.avgReplies : "--"} · 藏 ${bmKnown ? f.avgBookmarks : "--"}</div>
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
        if (target.includes("tab:schedule") || target.includes("goldenSlot:")) return "去排期 ›";
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
                    <div class="golden-meta">${escapeHtml(oonSubLabel)}</div>
                </div>
                <div class="format-card pool-card innet">
                    <div class="format-title">常规触达</div>
                    <div class="format-er" style="color:var(--x-blue)">${viewsKnown ? ah.inNetworkPostCount : "--"}<span class="pool-pct">(${inNetPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="golden-meta">粉丝数 ${lowRatioPct}%–${oonMultPct}%</div>
                </div>
                <div class="format-card pool-card low">
                    <div class="format-title">低曝光</div>
                    <div class="format-er" style="color:${ah.lowReachPostCount > 0 ? "var(--x-amber)" : "var(--text-primary)"}">${viewsKnown ? ah.lowReachPostCount : "--"}<span class="pool-pct">(${lowPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="golden-meta">低于粉丝数 ${lowRatioPct}%</div>
                </div>
            `;
        }

        const calEl = $("algoCalibrationPanel");
        if (calEl) {
            const cal = ah.calibration || null;
            if (cal && (cal.samplePostCount > 0 || (cal.replyTemplateRisk && cal.replyTemplateRisk.evaluatedReplyCount > 0))) {
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

    function extractPrimaryDraftDomain(urls) {
        let sawTco = false;
        for (const u of (urls || [])) {
            const m = /^https?:\/\/([^/\s:?#]+)/i.exec(String(u || ""));
            if (!m) continue;
            let host = m[1].toLowerCase();
            if (host.startsWith("www.")) host = host.slice(4);
            if (host.startsWith("mobile.")) host = host.slice(7);
            if (host === "x.com" || host === "twitter.com") continue;
            if (host === "t.co") {
                sawTco = true;
                continue;
            }
            return host;
        }
        return sawTco ? "t.co" : "";
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
            const extMatch = Boolean(draftFeat.hasExternalLink) === Boolean(s.hasExternalLink) ? 1.0 : 0.0;
            const domainMatch = (draftFeat.hasExternalLink && draftFeat.externalDomain && draftFeat.externalDomain === s.externalDomain) ? 1.0 : 0.0;
            const listMatch = Boolean(draftFeat.structuredList) === Boolean(s.structuredList) ? 1.0 : 0.0;
            const qMatch = Boolean(draftFeat.hasQuestion) === Boolean(s.hasQuestion) ? 1.0 : 0.0;
            const slotMatch = Boolean(draftFeat.activeSlotHit) === Boolean(s.activeSlotHit) ? 1.0 : 0.0;
            const lenDiff = Math.abs(Math.log1p(Math.max(0, draftFeat.cleanCharLen)) - Math.log1p(Math.max(0, Number(s.cleanCharLen || 0))));

            const z = 2.6 * jaccard
                + 0.85 * fmtMatch
                + 0.35 * extMatch
                + 0.55 * domainMatch
                + 0.30 * listMatch
                + 0.25 * qMatch
                + 0.20 * slotMatch
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

    function evaluateCorpusSimilarity(cleanText, skipDraftIdx) {
        const normDraft = normalizeForDuplicateCheck(cleanText);
        if (!normDraft || Array.from(normDraft).length < 8) {
            return { maxSim: 0, exactMatch: false, sourceLabel: "" };
        }
        const draftSet = buildDuplicateShingleSet(normDraft);
        const candidates = [];
        const topPosts = state.report && state.report.contentTiming && Array.isArray(state.report.contentTiming.topPosts)
            ? state.report.contentTiming.topPosts
            : [];
        for (const p of topPosts) {
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
            if (!normCand) continue;
            if (normCand === normDraft) {
                return { maxSim: 1.0, exactMatch: true, sourceLabel: cand.label };
            }
            const candSet = buildDuplicateShingleSet(normCand);
            if (!draftSet.size || !candSet.size) continue;
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
        const slots = ct && Array.isArray(ct.goldenSlots) ? ct.goldenSlots : [];

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
        const recentEpochs = [];
        if (ct && Array.isArray(ct.topPosts)) {
            for (const p of ct.topPosts) {
                const ms = p && p.createdAt ? new Date(p.createdAt).getTime() : NaN;
                if (Number.isFinite(ms) && ms >= ageCutoffMs && ms <= refEpochMs) {
                    recentEpochs.push(ms);
                }
            }
        }
        if (ah && ah.latestMainPostAt) {
            const latestMs = new Date(ah.latestMainPostAt).getTime();
            if (Number.isFinite(latestMs) && latestMs >= ageCutoffMs && latestMs <= refEpochMs) {
                if (!recentEpochs.some((e) => Math.abs(e - latestMs) < 60_000)) {
                    recentEpochs.push(latestMs);
                }
            }
        }
        const active48hCount = recentEpochs.length;
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
            effectiveDivMult = active48hCount >= 2 ? multThird : multSecond;
            score -= maxDivPen;
            checks.push(
                `距上一条推文仅 ${gapMin} 分钟，连续发帖会使第 2 条推荐权重折损至 ${Math.round(multSecond * 100)}%`
            );
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
        const dupEval = evaluateCorpusSimilarity(cleanText, skipDraftIdx);
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
        const hasExtLink = urls.some((u) => !/^https?:\/\/((www|mobile)\.)?(x\.com|twitter\.com)(\/|$)/i.test(u));
        const draftDomain = hasExtLink ? extractPrimaryDraftDomain(urls) : "";
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
        let slotHitNow = false;
        if (!hasPlannedTime && slots.length > 0) {
            const slotHint = computeNextGoldenWindowHint(slots);
            slotHitNow = Boolean(slotHint && slotHint.startsWith("🟢"));
            if (slotHitNow) {
                sampleNotes.push(`当前正处于你的高活跃黄金时段`);
            }
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
                activeSlotHit: slotHitNow,
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
        const safetyDimVal = riskyUrlMention ? "外链+@非粉" : (isShortenerUrl ? "短链风险" : (hasExtLink ? "普通外链" : "无风险"));

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
        switchTab("timing");
        inputEl.value = String(text || "");
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
            card.scrollIntoView({ behavior: "smooth", block: "center" });
        }
        inputEl.focus();
    }

    function evaluatePrePostDraft() {
        const inputEl = $("draftSimulatorInput");
        const outEl = $("draftSimulatorResult");
        if (!inputEl || !outEl) return;

        const text = (inputEl.value || "").trim();
        if (!text) {
            outEl.hidden = true;
            return;
        }

        const hasMedia = Boolean($("draftHasMediaCheck") && $("draftHasMediaCheck").checked);
        const isThread = Boolean($("draftIsThreadCheck") && $("draftIsThreadCheck").checked);
        const res = analyzeDraftByXAlgo(text, { hasMedia, isThread });

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
                    <button type="button" class="x-outline-pill sm" id="sendDraftToScheduleBtn">转入定时</button>
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
            });
        }
        const toSchedBtn = $("sendDraftToScheduleBtn");
        if (toSchedBtn) {
            toSchedBtn.addEventListener("click", () => {
                sendTextDraftToSchedule(res.text);
            });
        }
    }

    function renderTopTweetsList(report) {
        const p = report.profile;
        let topPosts = [...(report.contentTiming.topPosts || [])];

        if (state.topPostFormat !== "ALL") {
            topPosts = topPosts.filter((t) => {
                if (state.topPostFormat === "THREAD") return Boolean(t.isThread);
                if (state.topPostFormat === "MEDIA") return !t.isThread && Boolean(t.hasMedia);
                return !t.isThread && !t.hasMedia;
            });
        }

        topPosts.sort((a, b) => {
            const required = {
                algoScore: ["views", "likes", "replies", "reposts", "bookmarks"],
                er: ["views", "likes", "replies", "reposts", "bookmarks"],
                views: ["views"], bookmarks: ["bookmarks"], replies: ["replies"]
            }[state.topPostSortBy];
            if (required) {
                const sortable = (post) => postMetricsKnown(post, required) &&
                    (state.topPostSortBy !== "er" || Number(post.viewCount) > 0) &&
                    (state.topPostSortBy !== "algoScore" || Number(post.algoScore) >= 0);
                const availabilityOrder = Number(sortable(b)) - Number(sortable(a));
                if (availabilityOrder) return availabilityOrder;
                if (!sortable(a)) return 0;
            }
            switch (state.topPostSortBy) {
                case "algoScore":
                    return (b.algoScore || 0) - (a.algoScore || 0) || b.viewCount - a.viewCount;
                case "views":
                    return b.viewCount - a.viewCount || b.totalEngagements - a.totalEngagements;
                case "er":
                    return b.engagementRatePct - a.engagementRatePct || b.viewCount - a.viewCount;
                case "bookmarks":
                    return b.bookmarkCount - a.bookmarkCount || (b.bookmarkRatePct || 0) - (a.bookmarkRatePct || 0);
                case "replies":
                    return b.replyCount - a.replyCount || b.totalEngagements - a.totalEngagements;
                default:
                    return 0; // 保持后端综合影响力默认排序
            }
        });

        $("topTweetsList").innerHTML = topPosts.length
            ? topPosts
                  .slice(0, 12)
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
            : emptyStateHtml("当前类型暂无推文", widerPeriodActions());
        $("topTweetsList").querySelectorAll(".tweet-text").forEach((el) => {
            el.addEventListener("click", () => el.classList.toggle("is-expanded"));
        });
    }

    /* ==================== 模块 5：核心铁粉圈层 & Canvas 高清晒单战报卡 ==================== */
    function getEffectiveShareTweetText(report) {
        const sc = report.innerCircle.shareCard;
        let raw = (sc.shareTweetText || "").replace(/平均互动率/g, "平均公开互动率");
        if (!hasMetricSample(sc, "engagementRateSampleCount")) {
            raw = raw.replace(/平均公开互动率\s*[\d.]+%/g, "平均公开互动率 --");
        }
        if (sc.metricsNote && !raw.includes(sc.metricsNote)) raw += `\n${sc.metricsNote}`;
        if (!state.shareHideFollowRate) {
            return globalThis.XOpsI18n?.translate(raw) ?? raw;
        }
        const redacted = raw.replace(
            /(?:🤝\s*)?(?:关注|样本)回关率\s*[\d.]+%(?:[ \t]*[（(][^）)\r\n]*[）)])?/g,
            `📈 周期总浏览 ${hasMetricSample(report.activity, "viewSampleCount") ? fmtNum(report.activity.totalViews) : "--"}`
        );
        return globalThis.XOpsI18n?.translate(redacted) ?? redacted;
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
                    <div class="circle-reason">${escapeHtml(m.highlightReason)}</div>
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

        const fourthCard = state.shareHideFollowRate
            ? {
                  label: "周期总浏览",
                  val: hasMetricSample(report.activity, "viewSampleCount") ? fmtNum(report.activity.totalViews) : "--",
                  sub: `原创与互动累计浏览${metricSampleLabel(report.activity, "viewSampleCount")}`,
                  color: pal.green
              }
            : {
                  label: (sc.followBackRateLabel || "").startsWith("样本") ? "样本回关率" : "关注回关率",
                  val: `${sc.followBackRatePct}%`,
                  sub: (sc.followBackRateLabel || "").startsWith("样本") ? "名单未完整，未知关系待确认" : "关注列表互关健康度",
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
        const effectiveUnfollow = computeEffectiveSuggestUnfollowCount(report);
        if (ah && Array.isArray(ah.opsRecommendations) && ah.opsRecommendations.length > 0) {
            for (const item of ah.opsRecommendations) {
                if (!item) continue;
                let title = item.title || item.detail || "";
                if (item.code === "CLEAN_LOW_SIGNAL_FOLLOWS") {
                    if (effectiveUnfollow <= 0) continue;
                    title = `${effectiveUnfollow} 个低互动未回关可清理`;
                }
                const prio = String(item.priority || "MEDIUM").toUpperCase();
                if (item.externalUrl) {
                    todo.push({
                        text: title,
                        href: safeHttpUrl(item.externalUrl, "https://x.com")
                    });
                } else {
                    todo.push({
                        text: title,
                        go: item.actionTarget || "tab:timing",
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
                todo.push({ text: `${ah.rapidFirePostCount} 条推文间隔 <3h`, go: "tab:schedule" });
            }
            if (effectiveUnfollow > 0) {
                todo.push({ text: `${effectiveUnfollow} 个低互动未回关可清理`, go: "followTab:NOT_FOLLOWING_BACK|followCat:SUGGEST_CLEAN" });
            }
            if (audit.fansNotFollowedBackCount > 0) {
                todo.push({ text: `${audit.fansNotFollowedBackCount} 位粉丝待回关`, go: "followTab:FAN_ONLY" });
            }
            if (ct.goldenSlots && ct.goldenSlots.length > 0) {
                todo.push({ text: `黄金档 · ${ct.goldenSlots[0].label}`, go: "tab:schedule|goldenSlot:0" });
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

        for (const f of incoming) {
            const mime = allowedMimes.has(f.type) ? f.type : extMime(f.name);
            if (!allowedMimes.has(mime)) {
                return { ok: false, files: list, error: "仅支持 PNG、JPG、WEBP 或 GIF 格式图片" };
            }
            const isGif = mime === "image/gif";
            const maxBytes = isGif ? 15 * 1024 * 1024 : 5 * 1024 * 1024;
            if (Number(f.size || 0) <= 0) {
                return { ok: false, files: list, error: "图片文件为空，请重新选择" };
            }
            if (Number(f.size || 0) > maxBytes) {
                return { ok: false, files: list, error: `图片体积超出限制（${isGif ? "GIF 最大 15MB" : "单张静态图最大 5MB"}）` };
            }
            const existingHasGif = list.some((it) => it.mime === "image/gif");
            if (isGif && list.length > 0) {
                return { ok: false, files: list, error: "GIF 动图每次仅支持 1 张，且不可与其他图片混发" };
            }
            if (existingHasGif) {
                return { ok: false, files: list, error: "已添加 GIF 动图，不能再添加其他图片" };
            }
            if (list.length >= 4) {
                return { ok: false, files: list, error: "单条定时推文最多附带 4 张静态图片" };
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
        for (let i = (existingFiles || []).length; i < list.length; i++) {
            if (typeof URL !== "undefined" && URL.createObjectURL) {
                list[i].previewUrl = URL.createObjectURL(list[i].file);
            }
        }
        return { ok: true, files: list, error: "" };
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

    function computeNextGoldenSlotTimestampMs(slotLabel, timezone, nowMs = Date.now()) {
        const m = String(slotLabel || "").match(/^(周[一二三四五六日])\s+(\d{2}):00[–-](\d{2}):00$/);
        if (!m) return null;
        const dayMap = { "周一": 1, "周二": 2, "周三": 3, "周四": 4, "周五": 5, "周六": 6, "周日": 7 };
        const targetWk = dayMap[m[1]];
        const startHour = Number(m[2]);
        const tz = timezone || "Asia/Shanghai";
        const nowParts = getTzDateParts(nowMs, tz);
        let daysAhead = (targetWk - nowParts.weekday + 7) % 7;
        if (daysAhead === 0 && startHour * 60 <= nowParts.hour * 60 + nowParts.minute + 5) {
            daysAhead = 7;
        }
        let targetMs = zonedWallClockToEpochMs(
            nowParts.year,
            nowParts.month,
            nowParts.day + daysAhead,
            startHour,
            0,
            tz
        );
        if (targetMs <= nowMs + 60_000) {
            targetMs = zonedWallClockToEpochMs(
                nowParts.year,
                nowParts.month,
                nowParts.day + daysAhead + 7,
                startHour,
                0,
                tz
            );
        }
        return targetMs;
    }

    function formatEpochToDatetimeLocalValue(epochMs) {
        const d = new Date(epochMs);
        if (Number.isNaN(d.getTime())) return "";
        const pad = (n) => String(n).padStart(2, "0");
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    const MIN_SCHEDULE_INTERVAL_MS = 3_600_000;
    const MAX_BATCH_SCHEDULE_ITEMS = 20;

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
            const raw = Number(draftEpochsMs[i]);
            let target = Number.isFinite(raw) && raw >= nowMs + 60_000 ? raw : minLeadMs;
            if (i > 0 && target < aligned[i - 1] + minIntervalMs) {
                target = aligned[i - 1] + minIntervalMs;
            }
            const resolved = computeNextAvailableScheduleMs(target, [...occupied, ...aligned], minIntervalMs);
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

    function getRecommendedScheduleGapMs() {
        const profile = getActiveAlgoProfile();
        const minutes = Number(profile?.cadenceAndReach?.minRecommendedGapMinutes);
        return Math.max(MIN_SCHEDULE_INTERVAL_MS, (Number.isFinite(minutes) && minutes > 0 ? minutes : 180) * 60_000);
    }

    function getRecommendedScheduleEpochMs(preferredMs, {
        draftIndex = state.activeScheduleDraftIdx,
        nowMs = Date.now(),
        includeLaterAutoDrafts = true
    } = {}) {
        const gap = getRecommendedScheduleGapMs();
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
        const preferred = Number.isFinite(preferredMs) ? preferredMs : nowMs + 30 * 60_000;
        return computeNextAvailableScheduleMs(Math.max(nowMs + 5 * 60_000, latest + gap, preferred), occupied, gap);
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
        const candidates = [{ epochMs: nowMs + 30 * 60_000, recommended: true }];
        const tonight = zonedWallClockToEpochMs(now.year, now.month, now.day, 20, 0, tz);
        if (tonight > nowMs + 60_000) candidates.push({ epochMs: tonight });
        candidates.push({ epochMs: zonedWallClockToEpochMs(now.year, now.month, now.day + 1, 9, 0, tz) });
        for (const slot of (state.report?.contentTiming?.goldenSlots || []).slice(0, 3)) {
            const start = computeNextGoldenSlotTimestampMs(slot.label, tz, nowMs);
            const endMatch = String(slot.label || "").match(/[–-](\d{1,2}):(\d{2})$/);
            if (!start || !endMatch) continue;
            const day = getTzDateParts(start, tz);
            let end = zonedWallClockToEpochMs(day.year, day.month, day.day, Number(endMatch[1]), Number(endMatch[2]), tz);
            if (end <= start) end = zonedWallClockToEpochMs(day.year, day.month, day.day + 1, Number(endMatch[1]), Number(endMatch[2]), tz);
            candidates.push({ epochMs: start, goldenEnd: end });
        }
        const seen = new Set();
        const presets = [];
        for (const candidate of candidates) {
            const epochMs = getRecommendedScheduleEpochMs(candidate.epochMs, { nowMs });
            if (candidate.goldenEnd && epochMs >= candidate.goldenEnd) continue;
            if (seen.has(epochMs)) continue;
            seen.add(epochMs);
            const isGolden = Boolean(candidate.goldenEnd);
            presets.push({
                epochMs,
                label: (candidate.recommended ? "推荐 " : isGolden ? "★ " : "") + formatScheduleLocalTime(epochMs, nowMs),
                isGolden
            });
        }
        return presets;
    }

    function refreshAutomaticScheduleTimes(startIdx = 0, nowMs = Date.now()) {
        if (state.scheduleMutations?.has("create") || state.scheduleDraftRestoring) return false;
        const items = state.scheduleDraftItems || [];
        const gap = getRecommendedScheduleGapMs();
        let changed = false;
        for (let index = startIdx; index < items.length; index++) {
            const item = items[index];
            const input = document.querySelector('#scheduleDraftItemsList [data-draft-time="' + index + '"]');
            if (item.timeTouched || (input && document.activeElement === input)) continue;
            const current = parseDraftItemEpochMs(item);
            const previous = index > 0 ? parseDraftItemEpochMs(items[index - 1]) : NaN;
            const preferred = Math.max(
                Number.isFinite(current) && current > nowMs + 5 * 60_000 ? current : nowMs + 30 * 60_000,
                Number.isFinite(previous) ? previous + gap : 0
            );
            const next = getRecommendedScheduleEpochMs(preferred, { draftIndex: index, nowMs, includeLaterAutoDrafts: false });
            const value = formatEpochToDatetimeLocalValue(next);
            if (value === item.scheduledAtValue) continue;
            item.scheduledAtValue = value;
            if (input) input.value = value;
            changed = true;
        }
        if (changed) rotateComposerRequestId();
        return changed;
    }

    function createScheduleDraftItem(text = "", epochMs = null, files = [], timeTouched = false) {
        const resolvedMs = Number.isFinite(epochMs)
            ? epochMs
            : getRecommendedScheduleEpochMs(Date.now() + 30 * 60_000, { draftIndex: -1 });
        return {
            draftId: newClientRequestId(),
            text: String(text || ""),
            scheduledAtValue: formatEpochToDatetimeLocalValue(resolvedMs),
            files: Array.isArray(files) ? files : [],
            timeTouched: Boolean(timeTouched)
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
        const lastEpoch = existingEpochs.length
            ? existingEpochs[existingEpochs.length - 1]
            : Date.now() + 30 * 60_000;
        const basePreferred = Math.max(lastEpoch + getRecommendedScheduleGapMs(), Date.now() + 5 * 60_000);
        const nextMs = getRecommendedScheduleEpochMs(basePreferred, { draftIndex: state.scheduleDraftItems.length });
        state.scheduleDraftItems.push(createScheduleDraftItem("", nextMs, [], false));
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
        return Boolean(
            state.scheduleAccess
            || (state.accessPolicy && typeof state.accessPolicy.scheduledPublishAllowed === "boolean")
        );
    }

    function isScheduleEligible() {
        if (state.scheduleAccess) {
            return Boolean(state.scheduleAccess.allowed);
        }
        if (state.accessPolicy && typeof state.accessPolicy.scheduledPublishAllowed === "boolean") {
            return Boolean(state.accessPolicy.scheduledPublishAllowed);
        }
        return true;
    }

    function getScheduleLockedReason() {
        return (
            state.scheduleAccess?.reason
            || state.accessPolicy?.scheduledPublishLockedReason
            || "定时发布仅限与 @elidlicom 互动频率前 100 的推友可用。"
        );
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
        return Boolean(item && (String(item.text || "").length || (item.files || []).length || item.timeTouched));
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
        const needed = Boolean(isLoggedIn() && !state.isLoggingOut && hasContent && (
            scheduleDraftStorageWarning || (hasMedia && (!saved || saved.pending
                || saved.signature !== scheduleDraftMediaSignature(items)
                || Date.now() - saved.savedAt >= 5 * 60_000))
        ));
        if (needed === scheduleDraftUnloadProtected) return;
        scheduleDraftUnloadProtected = needed;
        window[needed ? "addEventListener" : "removeEventListener"]("beforeunload", warnBeforeLeavingScheduleDraft);
    }

    function warnBeforeLeavingScheduleDraft(event) {
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
                timeTouched: Boolean(item.timeTouched), mediaCount: (item.files || []).length
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
            const draft = createScheduleDraftItem(String(item?.text || "").slice(0, 25000),
                Number.isFinite(ms) ? ms : null, [], item?.timeTouched !== false);
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
        if (eligCard) {
            eligCard.hidden = !locked;
            if (locked) {
                const descEl = document.getElementById("scheduleEligibilityDesc");
                const rawReason = getScheduleLockedReason();
                const isGenericTop100Msg = /^定时发布仅限与\s*@?elidlicom\s*互动频率前\s*100/.test(rawReason.trim());
                if (descEl) {
                    descEl.textContent = isGenericTop100Msg ? "" : rawReason;
                    descEl.hidden = isGenericTop100Msg;
                }
                const rank = state.scheduleAccess?.authorInteractionRank ?? state.accessPolicy?.authorInteractionRank ?? null;
                const rankText = rank === 0
                    ? "✓ 作者本人"
                    : rank != null && rank >= 1 && rank <= 100
                        ? `✓ 互动第 ${rank} 名`
                        : rank != null && rank > 100
                            ? `当前第 ${rank} 名（需前 100）`
                            : "未进入互动前 100";
                const metaEl = document.getElementById("scheduleEligibilityMeta");
                if (metaEl) {
                    metaEl.innerHTML =
                        `<span class="mini-tag ${rank !== null && rank <= 100 ? "green" : "amber"}">${escapeHtml(rankText)}</span>`;
                }
                const actionLink = document.getElementById("scheduleEligibilityActionLink");
                if (actionLink) {
                    actionLink.href = "https://x.com/elidlicom";
                    actionLink.textContent = "去互动 ↗";
                }
            }
        }

        const writeOk = Boolean(state.authStatus?.writeAuthorized && state.authStatus?.mediaWriteAuthorized);
        const offlineOk = state.authStatus?.offlineRefreshable !== false;
        const card = document.getElementById("scheduleWriteAuthCard");
        if (card) {
            card.hidden = !loggedIn || locked || (writeOk && offlineOk && !state.schedulePublishAuthError);
            const titleEl = document.getElementById("scheduleWriteAuthTitle");
            const descEl = document.getElementById("scheduleWriteAuthDesc");
            const btnEl = document.getElementById("triggerPublishAuthBtn");
            if (!writeOk) {
                if (titleEl) titleEl.textContent = "需开启发推授权";
                if (descEl) {
                    descEl.textContent = "单独授予发推、传图与自动续期权限。";
                }
                if (btnEl) btnEl.textContent = "去授权";
            } else if (!offlineOk) {
                if (titleEl) titleEl.textContent = "升级长效离线授权";
                if (descEl) {
                    descEl.textContent = "当前令牌约 2 小时过期，升级后自动续期。";
                }
                if (btnEl) btnEl.textContent = "去升级";
            }
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
        const draftItems = state.scheduleDraftItems || [];
        const draftCount = draftItems.length;
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
            const hasFiles = draftItems.some((it) => (it.files || []).length > 0);
            if (state.scheduleDraftRestoring) {
                createBtn.textContent = "正在恢复图片…";
            } else if (state.scheduleMutations.has("create")) {
                createBtn.textContent = "提交中…";
            } else if (!locked && !writeOk && !hasFiles) {
                createBtn.textContent = "二次授权并去定时";
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
            counter.textContent = weighted > 280
                ? `${weighted}/280 · 长推`
                : `${weighted}/280`;
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
        const validation = validateBatchScheduleEpochs(epochs, queued, nowMs, MIN_SCHEDULE_INTERVAL_MS);
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
                const nearQueue = queued.find((qMs) => Math.abs(curMs - qMs) < MIN_SCHEDULE_INTERVAL_MS);
                if (nearQueue !== undefined) {
                    tagText = `距队列 ${formatGapDurationShort(curMs - nearQueue)}（需 ≥1h）`;
                    tagClass += " is-conflict";
                } else if (idx > 0 && Number.isFinite(epochs[idx - 1])) {
                    const diffPrev = curMs - epochs[idx - 1];
                    if (Math.abs(diffPrev) < MIN_SCHEDULE_INTERVAL_MS) {
                        tagText = `距 #${idx} ${formatGapDurationShort(diffPrev)}（需 ≥1h）`;
                        tagClass += " is-conflict";
                    } else if (diffPrev >= MIN_SCHEDULE_INTERVAL_MS) {
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

    function scheduleDraftCardHtml(item, idx, isSingle) {
        const placeholder = isSingle ? "输入定时推文内容…" : `第 ${idx + 1} 条推文内容…`;
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
                maxlength="25000" aria-label="第 ${idx + 1} 条推文正文" placeholder="${placeholder}">${escapeHtml(item.text || "")}</textarea>
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
                    <input type="datetime-local" class="schedule-time-input" data-draft-time="${idx}" aria-label="第 ${idx + 1} 条定时发布时间" value="${escapeHtml(item.scheduledAtValue || "")}" />
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
                "data-draft-media-grid", "data-draft-counter", "data-draft-link-hint", "data-draft-algo-chip", "data-draft-time"]) {
                card.querySelectorAll("[" + attr + "]").forEach((node) => node.setAttribute(attr, String(idx)));
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
            '<button type="button" class="pill-chip' + (preset.isGolden ? ' golden-preset' : '')
            + '" data-quick-epoch="' + preset.epochMs + '">' + escapeHtml(preset.label) + '</button>'
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

    function sendTextDraftToSchedule(text, attachedFiles = []) {
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
        const isEmpty = (item) => !String(item.text || "").trim() && !(item.files || []).length;
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
            const last = parseDraftItemEpochMs(items[items.length - 1]);
            const preferred = Math.max(Date.now() + 30 * 60_000, Number.isFinite(last) ? last + getRecommendedScheduleGapMs() : 0);
            item = createScheduleDraftItem("", getRecommendedScheduleEpochMs(preferred, { draftIndex: idx }), [], false);
            items.push(item);
        } else {
            item = items[idx];
        }
        item.text = String(text || "");
        item.files = media.files;
        if (!item.timeTouched) {
            const slots = state.report?.contentTiming?.goldenSlots || [];
            const tz = state.scheduleServerTz || state.report?.contentTiming?.timezone || "Asia/Shanghai";
            const bestMs = slots.length ? computeNextGoldenSlotTimestampMs(slots[0].label, tz, Date.now()) : null;
            const preferred = Math.max(bestMs || 0, parseDraftItemEpochMs(item) || Date.now() + 30 * 60_000);
            item.scheduledAtValue = formatEpochToDatetimeLocalValue(getRecommendedScheduleEpochMs(preferred, { draftIndex: idx }));
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

    function preserveRescheduleEditor(listEl) {
        if (!listEl) return false;
        const jobId = state.rescheduleEditingJobId;
        if (!jobId) return false;
        const key = CSS.escape(jobId);
        if (!listEl.querySelector('[data-resched-input="' + key + '"]')) return false;
        const job = (state.scheduleJobs || []).find((item) => item.id === jobId);
        const editable = job && ["SCHEDULED", "OVERDUE_CONFIRM_REQUIRED", "FAILED", "CANCELLED"].includes(job.status);
        const save = listEl.querySelector('[data-job-action="save-reschedule"][data-job-id="' + key + '"]');
        if (save) save.disabled = !editable || state.scheduleMutations.has("job:" + jobId)
            || state.scheduleMutations.has("batch-resend");
        if (!editable) {
            const message = !job ? "这条任务已不存在，请关闭编辑。"
                : job.status === "PUBLISHED" ? "这条任务已发布，无法改期。"
                : "这条任务正在发送或已进入补发队列，暂时无法改期。";
            setRescheduleError(jobId, message, false, "status");
        } else if (state.rescheduleErrorKind === "status") {
            setRescheduleError(jobId, "");
        }
        // Keep the original input node: replacing it dismisses mobile date/time pickers.
        return true;
    }

    function renderScheduledTweetsList() {
        const listEl = $("scheduledTweetsList");
        if (!listEl) return;
        const all = state.scheduleJobs || [];
        const signature = JSON.stringify([all, state.scheduleFilter, state.rescheduleEditingJobId]);
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

                const canEditTime =
                    job.status === "SCHEDULED" ||
                    job.status === "OVERDUE_CONFIRM_REQUIRED" ||
                    job.status === "FAILED" ||
                    job.status === "CANCELLED";
                const showRescheduleBox = state.rescheduleEditingJobId === job.id && canEditTime;
                const rescheduleBoxHtml = showRescheduleBox
                    ? `<div class="schedule-reschedule-box">
                        <input type="datetime-local" class="x-mini-input" data-resched-input="${escapeHtml(job.id)}" aria-label="新的发布时间" aria-describedby="reschedule-error-${escapeHtml(job.id)}" aria-invalid="${state.rescheduleError ? "true" : "false"}" value="${escapeHtml(state.rescheduleDraftValue ?? formatEpochToDatetimeLocalValue(Math.max(job.scheduledAtEpochMs || 0, Date.now() + 15 * 60_000)))}" />
                        <button type="button" class="x-blue-pill sm" data-job-action="save-reschedule" data-job-id="${escapeHtml(job.id)}">保存</button>
                        <button type="button" class="x-outline-pill sm" data-job-action="close-reschedule" data-job-id="${escapeHtml(job.id)}">放弃修改</button>
                        <p class="schedule-reschedule-error" id="reschedule-error-${escapeHtml(job.id)}" data-resched-error="${escapeHtml(job.id)}" role="alert" ${state.rescheduleError ? "" : "hidden"}>${escapeHtml(state.rescheduleError || "")}</p>
                    </div>`
                    : "";

                const actions = [];
                if (job.text && job.text.trim()) {
                    actions.push(`<button type="button" class="x-outline-pill sm" data-job-action="copy-text" data-job-id="${escapeHtml(job.id)}" aria-label="复制原文">复制</button>`);
                }
                if (job.status === "OVERDUE_CONFIRM_REQUIRED") {
                    actions.push(`<button type="button" class="x-blue-pill sm" data-job-action="open-confirm" data-job-id="${escapeHtml(job.id)}">确认补发</button>`);
                } else if (job.status === "FAILED") {
                    actions.push(`<button type="button" class="x-blue-pill sm" data-job-action="open-confirm" data-job-id="${escapeHtml(job.id)}">确认重发</button>`);
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

                return `<article class="schedule-job-card">
                    <div class="schedule-job-head">
                        <span class="mini-tag ${st.cls}">${escapeHtml(st.label)}</span>
                        <span class="schedule-job-time" ${timeTitle ? `title="${escapeHtml(timeTitle)}"` : ""}>${escapeHtml(timePrefix)}</span>
                    </div>
                    <div class="schedule-job-text" ${job.text ? "data-i18n-ignore" : ""}>${job.text ? escapeHtml(job.text) : "<span class=\"user-handle-muted\">图片推文</span>"}</div>
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
        $("confirmResendModal").hidden = false;
        syncBodyModalLock();
    }

    function closeConfirmResendModal() {
        const modal = document.getElementById("confirmResendModal");
        if (modal) modal.hidden = true;
        state.pendingConfirmJob = null;
        syncBodyModalLock();
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
            MIN_SCHEDULE_INTERVAL_MS
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
            if (items.length === 1) {
                const fd = new FormData();
                fd.append("text", items[0].text || "");
                fd.append("scheduledAtEpochMs", String(draftEpochs[0]));
                fd.append("clientRequestId", state.composerClientRequestId);
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
                    clientRequestId: `${state.composerClientRequestId}_${idx}`
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
                throw error;
            }
            if (!mutation.isCurrent()) return;
            const submittedCount = items.length;
            await loadScheduledTweets(true, true);
            if (!mutation.isCurrent()) return;
            if (state.composerClientRequestId === submittedRequestId) clearScheduleComposerDraft();
            showToast(submittedCount > 1 ? `已批量加入 ${submittedCount} 条定时推文` : "已加入定时发布队列");
        } catch (err) {
            if (!mutation.isCurrent()) return;
            const uncertain = !err.httpStatus || err.httpStatus >= 500;
            if (uncertain) {
                setScheduleComposerError("暂未收到提交结果，正在核对队列；草稿已保留。", true);
                const checked = await loadScheduledTweets(true, true);
                if (!mutation.isCurrent()) return;
                const confirmed = checked && submittedIds.every((id) =>
                    state.scheduleJobs.some((job) => job.clientRequestId === id));
                if (confirmed) {
                    if (state.composerClientRequestId === submittedRequestId) clearScheduleComposerDraft();
                    setScheduleComposerError("");
                    showToast("已确认加入定时队列");
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
                throw error;
            }
            if (!mutation.isCurrent()) return;
            state.watchedResendIds.add(job.id);
            if (state.pendingConfirmJob?.id === job.id) closeConfirmResendModal();
            showToast("已二次确认，正在自动补发…");
            await loadScheduledTweets(true, true);
        } catch (err) {
            if (!mutation.isCurrent()) return;
            const message = !err.httpStatus || err.httpStatus >= 500
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
                const message = !err.httpStatus || err.httpStatus >= 500
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
                const uncertain = !err.httpStatus || err.httpStatus >= 500;
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

        // 手机端支持点按与横向滑动扫读柱状图/热力图时段明细（再点取消）
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
        bindTapReadout(
            "heatmapGridRows",
            ".hm-cell[data-tip]",
            "timingPeriodDesc",
            timingPeriodDescription
        );

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
            if (!isLoggedIn() || e.metaKey || e.ctrlKey || e.altKey) return;
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
        const onPeerSearchDebounced = debounce(() => {
            state.peerSearchQuery = $("peerSearchInput").value.trim();
            state.peerRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderPeerList(state.report);
        }, 150);

        $("peerSearchInput").addEventListener("input", (e) => {
            onPeerSearchDebounced(e.target.value);
        });

        $("peerSortSelect").addEventListener("change", (e) => {
            state.peerSortBy = e.target.value;
            state.peerRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderPeerList(state.report);
        });

        const applyPeerRelFilter = (filterCode) => {
            state.peerRelFilter = filterCode && filterCode !== "ALL" ? filterCode : "MUTUAL";
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
            for (const part of rawGo.split("|")) {
                const [kind, val] = part.split(":");
                if (kind === "tab" && val) {
                    if (!(hasInPageTarget && state.activeTab === val)) {
                        switchTab(val);
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
                    if (state.report) renderPeerList(state.report);
                } else if (kind === "clearFollowSearch") {
                    $("followFilterInput").value = "";
                    state.followSearchQuery = "";
                    if (state.report) renderFollowAuditPanel(state.report);
                } else if (kind === "focusDraft") {
                    const inp = $("draftSimulatorInput");
                    if (inp) {
                        inp.scrollIntoView({ behavior: "smooth", block: "center" });
                        inp.focus();
                    }
                } else if (kind === "scrollId" && val) {
                    const targetEl = document.getElementById(val);
                    if (targetEl) {
                        targetEl.scrollIntoView({ behavior: "smooth", block: "center" });
                        targetEl.classList.remove("is-target-flash");
                        void targetEl.offsetWidth;
                        targetEl.classList.add("is-target-flash");
                        setTimeout(() => targetEl.classList.remove("is-target-flash"), 1400);
                    }
                } else if (kind === "goldenSlot") {
                    const slots = state.report?.contentTiming?.goldenSlots || [];
                    const slotIdx = Math.max(0, Number(val) || 0);
                    const picked = slots[slotIdx] || slots[0];
                    if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                        switchTab("schedule");
                        syncScheduleAuthBanner();
                        showError(getScheduleLockedReason());
                    } else if (picked && picked.label) {
                        const tz = state.scheduleServerTz || state.report?.contentTiming?.timezone || "Asia/Shanghai";
                        const targetMs = computeNextGoldenSlotTimestampMs(picked.label, tz, Date.now());
                        if (state.activeTab !== "schedule") switchTab("schedule");
                        if (targetMs) {
                            const adjustedMs = getRecommendedScheduleEpochMs(targetMs);
                            applyEpochToActiveScheduleDraft(adjustedMs);
                        }
                        const activeIdx = state.activeScheduleDraftIdx || 0;
                        document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${activeIdx}"]`)?.focus();
                        showToast("已填入 " + formatScheduleLocalTime(parseDraftItemEpochMs(state.scheduleDraftItems[activeIdx])));
                    }
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

        const onFollowSearchDebounced = debounce(() => {
            state.followSearchQuery = $("followFilterInput").value.trim();
            state.followRenderLimit = LIST_PAGE_SIZE;
            if (state.report) renderFollowAuditPanel(state.report);
        }, 150);

        $("followFilterInput").addEventListener("input", (e) => {
            onFollowSearchDebounced(e.target.value);
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
                const posts = state.report?.contentTiming?.topPosts || [];
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
                switchTab("schedule");
                syncScheduleAuthBanner();
                showError(getScheduleLockedReason());
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
                MIN_SCHEDULE_INTERVAL_MS
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

        const goldenSlotsGridEl = document.getElementById("goldenSlotsGrid");
        if (goldenSlotsGridEl) {
            const activateGoldenSlot = (e) => {
                const card = e.target && e.target.closest ? e.target.closest(".golden-card[data-golden-slot]") : null;
                if (!card || !isLoggedIn()) return;
                const slotLabel = card.dataset.goldenSlot;
                if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                    switchTab("schedule");
                    syncScheduleAuthBanner();
                    showError(getScheduleLockedReason());
                    return;
                }
                const tz = state.scheduleServerTz || state.report?.contentTiming?.timezone || "Asia/Shanghai";
                const targetMs = computeNextGoldenSlotTimestampMs(slotLabel, tz, Date.now());
                switchTab("schedule");
                if (targetMs) {
                    const adjustedMs = getRecommendedScheduleEpochMs(targetMs);
                    applyEpochToActiveScheduleDraft(adjustedMs);
                }
                const activeIdx = state.activeScheduleDraftIdx || 0;
                document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${activeIdx}"]`)?.focus();
                showToast("已填入 " + formatScheduleLocalTime(parseDraftItemEpochMs(state.scheduleDraftItems[activeIdx])));
            };
            goldenSlotsGridEl.addEventListener("click", activateGoldenSlot);
            goldenSlotsGridEl.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    activateGoldenSlot(e);
                }
            });
        }

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
            if (state.scheduleMutations.has(`job:${jobId}`) || state.scheduleMutations.has("batch-resend")) return;

            if (action === "open-confirm") {
                if (hasScheduleAccessDecision() && !isScheduleEligible()) {
                    showError(getScheduleLockedReason());
                    return;
                }
                openConfirmResendModal(job);
            } else if (action === "toggle-reschedule") {
                state.rescheduleEditingJobId = state.rescheduleEditingJobId === jobId ? null : jobId;
                state.rescheduleDraftValue = null;
                state.rescheduleError = "";
                state.rescheduleErrorKind = "";
                renderScheduledTweetsList();
            } else if (action === "save-reschedule") {
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
                const conflict = otherQueued.find((qMs) => Math.abs(nextMs - qMs) < MIN_SCHEDULE_INTERVAL_MS);
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
            showToast("定时发布二次授权已开通！长效离线刷新已启用");
            cleanAuthQueryParams();
        } else if (authResult === "ok") {
            showToast("X 授权登录成功！已进入您本人的专属运营工作台");
            cleanAuthQueryParams();
        } else if (publishAuthErr) {
            setPublishAuthError(publishAuthErr);
            cleanAuthQueryParams();
        } else if (authErr) {
            state.authConnectionError = authErr;
            syncAuthConnectionUI();
            cleanAuthQueryParams();
        }

        if (isLoggedIn()) {
            const validTabs = new Set(["activity", "interactions", "follow-audit", "timing", "circle", "schedule"]);
            const initialTab = urlTab === "schedule" || authResult === "publish_ok" || publishAuthErr
                ? "schedule" : urlTab && validTabs.has(urlTab) ? urlTab : state.activeTab;
            switchTab(initialTab, { historyMode: "replace" });
            await restoreScheduleDraftAfterStepUpAuth();
            if (state.activeTab !== "schedule") {
                void loadScheduledTweets(true);
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
