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
        HOURS_48: { code: "HOURS_48", days: 2, badgePrefix: "近 48 小时" }
    };

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
        period: "HOURS_48",
        storedMode: false,
        days: 2,
        themeIdx: 0,
        activeTab: "activity",
        serverMeta: null,
        authStatus: null,
        report: null,
        bundle: null,
        coveredWindowDays: 0,
        pendingExpandCheck: false,
        pendingRelationshipChecks: 0,
        isFetching: false,
        isLoggingOut: false,
        reportRequestGeneration: 0,
        reportAbortController: null,
        authStatusGeneration: 0,
        cacheExpiresEpochMs: 0,
        cooldownTimerId: null,
        // 模块 2：推友互动筛选与增量分页状态
        selectedPeerUsername: null,
        peerRelFilter: "ALL",
        peerSearchQuery: "",
        peerSortBy: "intimacy",
        peerRenderLimit: LIST_PAGE_SIZE,
        // 模块 3：未回关筛选、增量分页与本地白名单/已打开状态
        followStateTab: "NOT_FOLLOWING_BACK",
        unfollowCategory: "ALL",
        followSearchQuery: "",
        followRenderLimit: LIST_PAGE_SIZE,
        whitelistSet: new Set(),
        openedFollowSet: new Set(),
        // 模块 4：爆款代表推文多维排序与体裁过滤状态
        topPostSortBy: "composite",
        topPostFormat: "ALL",
        // 模块 5：战报卡隐私指标切换与 CORS 安全头像缓存
        shareHideFollowRate: false,
        avatarImgCache: {},
        // 使用次数与作者引导弹层状态
        usageCount: 0,
        authorFollowingMarked: false,
        boundUserId: ""
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

    function resetUserScopedMemoryState() {
        cancelReportRequest();
        state.authStatusGeneration++;
        clearInterval(state.cooldownTimerId);
        state.report = null;
        state.bundle = null;
        state.coveredWindowDays = 0;
        state.pendingRelationshipChecks = 0;
        state.pendingExpandCheck = false;
        state.cacheExpiresEpochMs = 0;
        state.isFetching = false;
        state.selectedPeerUsername = null;
        state.peerRelFilter = "ALL";
        state.peerFilterTouched = false;
        state.peerSearchQuery = "";
        state.followSearchQuery = "";
        state.peerRenderLimit = LIST_PAGE_SIZE;
        state.followRenderLimit = LIST_PAGE_SIZE;
        state.whitelistSet = new Set();
        state.openedFollowSet = new Set();
        state.avatarImgCache = {};
        state.usageCount = 0;
        state.authorFollowingMarked = false;
        state.boundUserId = "";
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

    function normalizePeriod(rawPeriod, rawDays) {
        const up = String(rawPeriod || "").trim().toUpperCase();
        const dNum = Number(rawDays);
        if (up === "MONTH" || up === "MONTHLY" || up === "30D" || up === "M" || dNum === 30) {
            return PERIOD_META.MONTH;
        }
        if (up === "WEEK" || up === "WEEKLY" || up === "7D" || up === "W" || dNum === 7) {
            return PERIOD_META.WEEK;
        }
        if (
            up === "HOURS_48" ||
            up === "48H" ||
            up === "DAY" ||
            up === "DAILY" ||
            up === "2D" ||
            up === "1D" ||
            up === "D" ||
            dNum === 2 ||
            dNum === 1
        ) {
            return PERIOD_META.HOURS_48;
        }
        // 默认维度为 48 小时动态窗口
        return PERIOD_META.HOURS_48;
    }

    function syncPeriodButtons() {
        document.querySelectorAll(".seg-btn[data-period]").forEach((btn) => {
            btn.classList.toggle("active", btn.dataset.period === state.period);
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
        const validAvatar = user && user.avatarUrl ? safeAvatarSrc(user.avatarUrl) : "";
        if (validAvatar) {
            return `<div class="avatar-circle ${sizeClass}" style="${bgStyle}">
                <img src="${escapeHtml(validAvatar)}" alt="${escapeHtml(uname)}" referrerpolicy="no-referrer"
                     data-avatar-fallback="${escapeHtml(initial)}" />
            </div>`;
        }
        return `<div class="avatar-circle ${sizeClass}" style="${bgStyle}">${escapeHtml(initial)}</div>`;
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

    function switchTab(tabId) {
        if (!isLoggedIn()) {
            showToast("请先使用 X 账号授权登录");
            return;
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
            btn.classList.toggle("active", btn.dataset.tab === tabId);
        });
        window.scrollTo({ top: 0 });
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
                labelEl.textContent = id === "cycleThemeBtn" ? `主题 · ${theme.label}` : theme.label;
            }
        });
        try {
            localStorage.setItem("xops_theme", theme.id);
        } catch (_) {}
        if (state.report) {
            drawShareCardCanvas(state.report);
        }
    }

    async function fetchServerAndAuthStatus() {
        const generation = ++state.authStatusGeneration;
        const [metaResp, authResp] = await Promise.all([
            fetch("/api/meta", { cache: "no-store" }),
            fetch("/api/auth/x/status", { cache: "no-store" })
        ]);
        if (!authResp.ok) throw new Error(`无法确认登录状态（HTTP ${authResp.status}）`);
        const authStatus = await safeParseJson(authResp);
        const serverMeta = metaResp.ok ? await safeParseJson(metaResp) : null;
        if (generation !== state.authStatusGeneration) return;
        if (typeof authStatus.loggedIn !== "boolean") throw new Error("登录状态响应格式异常");
        const oldUserId = stableUserId(state.authStatus?.user);
        const newUserId = authStatus.loggedIn ? stableUserId(authStatus.user) : "";
        if (authStatus.loggedIn && !newUserId) throw new Error("登录状态缺少稳定账号 ID，请重新登录");
        if (oldUserId !== newUserId) {
            resetUserScopedMemoryState();
        }
        if (serverMeta) state.serverMeta = serverMeta;
        state.authStatus = authStatus;
        syncAuthGateAndWorkspaceUI();
    }

    function syncAuthGateAndWorkspaceUI() {
        const loggedIn = isLoggedIn();
        const oauthConfigured = Boolean(
            (state.authStatus && state.authStatus.oauthConfigured) ||
            (state.serverMeta && state.serverMeta.oauthConfigured)
        );

        // 主界面与登录门禁切换
        $("authGateSection").hidden = loggedIn;
        $("authenticatedWorkspace").hidden = !loggedIn;
        $("authenticatedRightRail").hidden = !loggedIn;
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
            logoutBtn.hidden = false;
            $("railDisplayName").textContent = u.displayName || u.username;
            $("railHandle").textContent = "@" + u.username;
            $("railAvatar").outerHTML = avatarHtml(u, "sm").replace(
                'class="avatar-circle sm"',
                'class="avatar-circle sm" id="railAvatar"'
            );

            // 首次进入尚未拿到全量报告前，立即预填本人头像、昵称与用户名，消除空白「加载中…」卡顿感
            if (!state.report) {
                $("profileAvatar").outerHTML = avatarHtml(u, "xl").replace(
                    'class="avatar-circle xl"',
                    'class="avatar-circle xl" id="profileAvatar"'
                );
                $("profileDisplayName").textContent = u.displayName || u.username;
                $("profileVerifiedIcon").hidden = !u.verified;
                $("profileHandleText").textContent = "@" + u.username;
                $("profilePersonaTag").textContent = state.storedMode ? "读取已入库报表" : "同步数据中";
                $("visitXProfileLink").href = `https://x.com/${encodeURIComponent(u.username)}`;
                $("profileBio").textContent = state.storedMode ? "正在读取本人已入库数据，不拉取实时数据…" : "正在并发同步您的近期推文、互动推友与关注关系，请稍候数秒…";
                $("topbarWindowHint").textContent = state.storedMode ? "已入库报表加载中…" : "正在同步本人实时数据…";
            }
        } else {
            logoutBtn.hidden = true;
            $("navUnfollowBadge").hidden = true;
            $("railDisplayName").textContent = "未授权登录";
            $("railHandle").textContent = "仅限登录查本人账号";
            $("railAvatar").outerHTML = `<div class="avatar-circle sm" id="railAvatar">X</div>`;

            const setupHint = $("oauthAdminSetupHint");
            if (!oauthConfigured) {
                if (state.authStatus && state.authStatus.suggestedCallbackUrl) {
                    $("gateCallbackUriText").textContent = state.authStatus.suggestedCallbackUrl;
                }
                setupHint.hidden = false;
            } else {
                setupHint.hidden = true;
            }
        }
    }

    function triggerXOAuthLogin() {
        if (isLoggedIn()) {
            if (state.report && Date.now() < state.cacheExpiresEpochMs) {
                showToast("数据刚更新过，5 分钟内无需重复刷新");
                return;
            }
            loadSelfReport(false);
            return;
        }
        const oauthConfigured = Boolean(
            (state.authStatus && state.authStatus.oauthConfigured) ||
            (state.serverMeta && state.serverMeta.oauthConfigured)
        );
        if (!oauthConfigured) {
            showError("服务端尚未在 application.yml 中配置 xops.oauth-client-id，请按下方配置说明填写后重启服务");
            const setupHint = document.getElementById("oauthAdminSetupHint");
            if (setupHint && !setupHint.hidden) {
                setupHint.scrollIntoView({ behavior: "smooth", block: "nearest" });
                setupHint.classList.remove("is-pulse");
                void setupHint.offsetWidth;
                setupHint.classList.add("is-pulse");
                setTimeout(() => setupHint.classList.remove("is-pulse"), 1600);
            }
            return;
        }
        window.location.href = "/api/auth/x/login";
    }

    function startCacheCooldownTicker(remainingSeconds) {
        clearInterval(state.cooldownTimerId);
        state.cacheExpiresEpochMs = Date.now() + Math.max(0, Number(remainingSeconds || 0)) * 1000;
        updateRefreshButtonState();
        state.cooldownTimerId = setInterval(updateRefreshButtonState, 1000);
    }

    function getCacheTtlSeconds() {
        return (
            (state.report && state.report.cacheTtlSeconds) ||
            (state.serverMeta && state.serverMeta.cacheTtlSeconds) ||
            300
        );
    }

    function getCacheDurationLabel() {
        const sec = getCacheTtlSeconds();
        if (sec >= 3600 && sec % 3600 === 0) {
            return `${sec / 3600} 小时`;
        }
        return `${Math.max(1, Math.round(sec / 60))} 分钟`;
    }

    function updateRefreshButtonState() {
        const relationshipControls = $("relationshipContinueControls");
        relationshipControls.hidden = state.storedMode || !isLoggedIn() || state.pendingRelationshipChecks <= 0;
        const relationshipBtn = $("continueRelationshipsBtn");
        relationshipBtn.disabled = state.isFetching || state.isLoggingOut;
        relationshipBtn.textContent = `继续核验关系（${state.pendingRelationshipChecks}）`;
        const btn = $("forceRefreshBtn");
        if (!btn) return;
        const modeUrl = new URL(window.location.href);
        modeUrl.searchParams.set("period", state.period);
        if (state.storedMode) modeUrl.searchParams.delete("stored");
        else modeUrl.searchParams.set("stored", "true");
        $("storedReportLink").href = modeUrl.toString();
        $("storedReportLink").textContent = state.storedMode ? "返回实时报表" : "查看已入库报表";
        if (state.storedMode) {
            btn.disabled = state.isFetching || state.isLoggingOut;
            btn.textContent = state.isFetching ? "读取中…" : "刷新实时数据";
            btn.title = "退出已入库模式并请求最新数据";
            btn.classList.remove("can-refresh");
            return;
        }
        if (state.isFetching) {
            btn.disabled = true;
            btn.classList.remove("can-refresh");
            btn.textContent = "更新中…";
            btn.title = "正在同步你的账号数据";
            return;
        }
        const ttlLabel = getCacheDurationLabel();
        const remainSec = Math.max(0, Math.ceil((state.cacheExpiresEpochMs - Date.now()) / 1000));
        const fetchedClock = state.report ? fmtClockTime(state.report.fetchedAt) : "";
        if (remainSec > 0) {
            const remainMin = Math.max(1, Math.ceil(remainSec / 60));
            btn.disabled = true;
            btn.classList.remove("can-refresh");
            btn.textContent = fetchedClock ? `更新于 ${fetchedClock}` : "已是最新";
            btn.title = `数据 ${ttlLabel}内不重复拉取，约 ${remainMin} 分钟后可刷新`;
        } else {
            btn.disabled = false;
            btn.classList.add("can-refresh");
            btn.textContent = "刷新数据";
            btn.title = "点击拉取最新数据";
            clearInterval(state.cooldownTimerId);
        }
    }


    function applyLoadedPeriodReport(body) {
        state.report = body;
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

        // 有待办时默认先看「我没回谁」，若无则看「评论待回复」；用户手动选过筛选后尊重其选择
        if (!state.peerFilterTouched) {
            state.peerRelFilter =
                Number(body.interactions.inboundHeavyPeers || 0) > 0
                    ? "I_OWE"
                    : Number(body.interactions.unrepliedMyPostPeers || 0) > 0
                      ? "UNREPLIED_POST"
                      : "ALL";
        }

        renderAll(body);
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
        if (state.isLoggingOut) return;
        if (!isLoggedIn()) {
            syncAuthGateAndWorkspaceUI();
            return;
        }

        const withinCacheLock = state.cacheExpiresEpochMs > Date.now();
        const authUname = state.authStatus && state.authStatus.user ? state.authStatus.user.username : "";
        const authUserId = stableUserId(state.authStatus.user);
        if (!authUserId) {
            showError("无法确认稳定账号 ID，请重新登录");
            return;
        }
        const bundleOwnerId = stableUserId(state.bundle?.[state.period]?.profile);

        // 包含关系（48h ⊆ 7天 ⊆ 30天）+ 严格本人归属校验：仅当内存包属于当前登录账号且已覆盖目标窗口时才直接切换
        if (
            !continueRelations && state.bundle &&
            state.bundle[state.period] &&
            bundleOwnerId &&
            bundleOwnerId === authUserId &&
            (state.storedMode || (state.coveredWindowDays >= state.days && withinCacheLock))
        ) {
            applyLoadedPeriodReport(state.bundle[state.period]);
            return;
        }

        if (state.isFetching) {
            if (!continueRelations) state.pendingExpandCheck = true;
            return;
        }
        const requestGeneration = ++state.reportRequestGeneration;
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
        let coveredDateHint = "";
        const isExpanding = Boolean(state.bundle && state.coveredWindowDays > 0 && state.coveredWindowDays < state.days);

        const updateLoadingProgressText = () => {
            if (!slowHintEl || !requestIsCurrent()) return;
            const sec = Math.max(1, Math.floor((Date.now() - startedMs) / 1000));
            const pMeta = PERIOD_META[state.period] || PERIOD_META.HOURS_48;
            if (state.storedMode) {
                slowHintEl.textContent = `正在读取 ${selfHandle} 的已入库${pMeta.badgePrefix}报表…（${sec} 秒）`;
            } else if (isExpanding || coveredDateHint) {
                slowHintEl.textContent =
                    `正在增量补齐${pMeta.badgePrefix}未加载历史` +
                    (coveredDateHint ? `（已覆盖至 ${coveredDateHint}）` : "") +
                    `…（${sec} 秒，已有数据免重复拉取）`;
            } else {
                slowHintEl.textContent = `正在加载 ${selfHandle} 的${pMeta.badgePrefix}数据…（${sec} 秒）`;
            }
            slowHintEl.hidden = false;
        };

        const slowTimer = setTimeout(() => {
            updateLoadingProgressText();
            elapsedTimer = setInterval(updateLoadingProgressText, 1000);
        }, state.report ? 250 : 150);

        try {
            let safetyChunks = 0;
            while (safetyChunks < 40) {
                safetyChunks++;
                const reqDays = state.days || 2;
                const reqPeriod = state.period || "HOURS_48";
                if (!requestIsCurrent()) return;
                const abortCtrl = new AbortController();
                state.reportAbortController = abortCtrl;
                const fetchTimeout = setTimeout(() => abortCtrl.abort(), 55000);
                let resp;
                let bundleBody;
                try {
                    resp = await fetch(
                        `/api/analyze/bundle?period=${encodeURIComponent(reqPeriod)}&targetDays=${encodeURIComponent(reqDays)}${state.storedMode ? "&stored=true" : continueRelations ? "&continueRelations=true" : ""}`,
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
                if (!resp.ok || !bundleBody.month || !bundleBody.day) {
                    throw new Error(bundleBody.message || `查询失败（HTTP ${resp.status}）`);
                }

                const reports = [bundleBody.month, bundleBody.week, bundleBody.day];
                if (reports.some((report) => stableUserId(report?.profile) !== authUserId)) {
                    throw new Error("报表账号与当前登录账号不匹配，请重新加载");
                }
                state.bundle = {
                    MONTH: bundleBody.month,
                    WEEK: bundleBody.week,
                    HOURS_48: bundleBody.day
                };
                // 覆盖天数以服务端真实计算为准（两类数据都覆盖到才算），不再用请求天数兜底冒充
                state.coveredWindowDays = Number(bundleBody.coveredWindowDays ?? 0);
                state.pendingRelationshipChecks = state.storedMode ? 0 : Math.max(0, Number(bundleBody.pendingRelationshipChecks || 0));
                if (bundleBody.coveredUntil) {
                    coveredDateHint = fmtMonthDay(bundleBody.coveredUntil);
                    updateLoadingProgressText();
                }

                const targetReport = state.bundle[state.period] || state.bundle.HOURS_48;
                startCacheCooldownTicker(targetReport.cacheRemainingSeconds);
                applyLoadedPeriodReport(targetReport);

                // 若当前所选维度已覆盖完成（或后端游标已到底），立即结束；否则自动从断点游标无缝续拉下一批
                if (state.storedMode || continueRelations || !bundleBody.hasMoreHistoryForTarget || state.coveredWindowDays >= state.days) {
                    break;
                }
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
                $("profilePersonaTag").textContent = state.storedMode ? "已入库报表不可用" : "同步未完成";
                $("profileBio").textContent = `${state.storedMode ? "未能读取已入库报表" : "暂未拉取完成"}：${msg}`;
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
            if (!state.storedMode && state.pendingExpandCheck && state.coveredWindowDays < state.days) {
                state.pendingExpandCheck = false;
                loadSelfReport(false);
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
        if (state.usageCount > 0) {
            handleEl.textContent = `@${uname} · 第 ${state.usageCount} 次使用`;
            handleEl.title = `@${uname} · 累计第 ${state.usageCount} 次使用`;
        } else {
            handleEl.textContent = `@${uname}`;
            handleEl.title = `@${uname}`;
        }
        const logoBtn = document.getElementById("logoHomeBtn");
        if (logoBtn && state.usageCount > 0) {
            logoBtn.title = `返回工作台顶部 · 累计第 ${state.usageCount} 次使用`;
        }
    }

    function openAuthorPromptModal(data) {
        const modal = document.getElementById("authorPromptModal");
        if (!modal || !data) return;
        const rawAuthor = data.authorProfile || {};
        const author = {
            username: rawAuthor.username || "elidlicom",
            displayName: rawAuthor.displayName || "Litany（李丰一）",
            avatarUrl: rawAuthor.avatarUrl || "/author-avatar.webp",
            bio: rawAuthor.bio || "Java Dev | Studying Chinese Metaphysics(Dao·道) ䷀ · 易道心学创始人 · elidli.com",
            verified: rawAuthor.verified !== undefined ? Boolean(rawAuthor.verified) : true,
            followersCount: rawAuthor.followersCount || 0,
            tweetCount: rawAuthor.totalPostsCount || rawAuthor.tweetCount || 0
        };
        const handle = String(author.username || "elidlicom").replace(/^@+/, "");
        $("authorModalBadge").textContent =
            data.modalBadgeText || `第 ${data.usageCount || state.usageCount || 3} 次使用 · 作者说明`;
        $("authorModalTitle").textContent = data.modalTitle || `你好，我是作者 @${handle}`;
        $("authorModalMessage").textContent =
            data.modalMessage || `因为每次使用这个网站服务，我都是真实付费调用 API 的。结个善缘，求关注 @${handle}！`;
        $("authorModalAvatarSlot").innerHTML = avatarHtml(author, "xl");
        $("authorModalDisplayName").textContent = author.displayName || handle;
        const verEl = $("authorModalVerified");
        verEl.innerHTML = verifiedSvgHtml(Boolean(author.verified));
        verEl.hidden = !author.verified;
        $("authorModalHandle").textContent = `@${handle}`;
        $("authorModalBio").textContent =
            author.bio || "Java Dev | Studying Chinese Metaphysics(Dao·道) ䷀ · 易道心学创始人 · elidli.com";

        const followersCount = Number(author.followersCount || 0);
        const tweetCount = Number(author.tweetCount || 0);
        const statsRow = $("authorModalStatsRow");
        if (followersCount > 0 || tweetCount > 0) {
            $("authorModalFollowers").textContent = fmtNum(followersCount);
            $("authorModalPosts").textContent = fmtNum(tweetCount);
            statsRow.hidden = false;
        } else {
            statsRow.hidden = true;
        }

        const isFollowing = Boolean(data.followingAuthor);
        const isSelf = Boolean(data.selfIsAuthor);
        state.authorFollowingMarked = isFollowing || isSelf;
        $("authorModalFollowedTag").hidden = !isFollowing;

        const safeProfileUrl = safeHttpUrl(data.authorProfileUrl, `https://x.com/${encodeURIComponent(handle)}`);
        const safeFollowUrl = safeHttpUrl(
            data.authorFollowIntentUrl,
            `https://x.com/intent/follow?screen_name=${encodeURIComponent(handle)}`
        );
        const followBtn = $("authorModalFollowBtn");
        followBtn.href = isFollowing || isSelf ? safeProfileUrl : safeFollowUrl;
        $("authorModalFollowBtnText").textContent = isFollowing
            ? `已关注 · 访问 @${handle} 主页 ↗`
            : isSelf
              ? "查看我的 X 主页 ↗"
              : `关注 @${handle} ↗`;

        const profileLink = $("authorModalProfileLink");
        profileLink.href = safeProfileUrl;
        profileLink.textContent = `查看 @${handle} 主页 ↗`;
        profileLink.hidden = isFollowing || isSelf;
        $("authorModalLaterBtn").textContent = isFollowing || isSelf ? "我知道了" : "稍后再说";
        modal.hidden = false;
    }

    function closeAuthorPromptModal() {
        const modal = document.getElementById("authorPromptModal");
        if (modal) modal.hidden = true;
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

        // 吸顶栏：仅展示统计起止窗口（采集与缓存刷新时间已由右上角按钮统一展示，避免重复）
        const hintEl = $("topbarWindowHint");
        const coverageNote = (act.coverageNote || "").trim();
        const storedHint = state.storedMode ? `已入库 · 报表终点 ${new Date(report.fetchedAt).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false })}（北京时间）` : "";
        hintEl.textContent = [storedHint, act.windowRangeText, coverageNote, act.metricsNote].filter(Boolean).join(" · ");
        hintEl.classList.toggle("is-partial", Boolean(coverageNote));
        hintEl.title = coverageNote
            ? "所选时间段内的数据尚未全部拉取，以下所有统计只按「实际数据覆盖」这段时间计算"
            : "所选时间段内的数据已完整拉取";

        // 头像与主页基本资料
        $("profileAvatar").outerHTML = avatarHtml(p, "xl").replace(
            'class="avatar-circle xl"',
            'class="avatar-circle xl" id="profileAvatar"'
        );
        $("profileDisplayName").textContent = p.displayName;
        $("profileVerifiedIcon").hidden = !p.verified;
        $("profilePersonaTag").textContent = act.personaTag;
        $("profileHandleText").textContent = "@" + p.username;
        $("profileBio").textContent = p.bio || "该账号暂未填写个人简介";
        $("visitXProfileLink").href = `https://x.com/${encodeURIComponent(p.username)}`;
        const hs = Number(act.activityHealthScore || 0);
        const hsWord = hs >= 75 ? "很好" : hs >= 50 ? "良好" : hs >= 30 ? "一般" : "需加油";
        $("healthScoreChip").textContent = hs < 0 ? "运营健康 -- · 指标待补充" : `运营健康 ${hs} · ${hsWord}`;
        $("healthScoreChip").className = `health-score-chip ${hs < 0 ? "" : hs >= 50 ? "judge-good" : "judge-warn"}`;

        $("profileFollowingNum").textContent = fmtNum(p.followingCount);
        $("profileFollowersNum").textContent = fmtNum(p.followersCount);
        const followBackRateComplete = audit.followBackRateComplete ??
            (!audit.followersSampleTruncated && !audit.followingsSampleTruncated && Number(audit.unknownFollowBackCount || 0) === 0);
        $("profileMutualRateNum").textContent = `${followBackRateComplete ? "" : "样本 "}${audit.followBackRatePct}%`;
        $("profileMutualRateNum").title = audit.followBackRateLabel || `${followBackRateComplete ? "关注" : "样本"}回关率 ${audit.followBackRatePct}%`;
        $("profileTotalTweetsNum").textContent = fmtNum(p.totalPostsCount);

        const noticeStrip = $("bannerNoticeStrip");
        const cleanNotice = (report.bannerNotice || "").trim();
        noticeStrip.textContent = cleanNotice;
        noticeStrip.hidden = !cleanNotice;

        const unfollowBadge = $("navUnfollowBadge");
        if (audit.notFollowingBackCount > 0) {
            unfollowBadge.textContent = String(audit.notFollowingBackCount);
            unfollowBadge.hidden = false;
        } else {
            unfollowBadge.hidden = true;
        }
    }

    /* ==================== 模块 1：发帖与回复活跃度看板 ==================== */
    function renderActivityPanel(report) {
        const act = report.activity;
        const is48h = report.periodCode === "HOURS_48" || report.periodCode === "DAY";
        const shortScope = is48h ? "48h " : report.periodCode === "WEEK" ? "近7天" : "近30天";

        $("kpiPostsTitle").textContent = "原创发帖";
        $("kpiOriginalPosts").textContent = fmtNum(act.originalPosts);
        if (is48h) {
            $("kpiDailyPosts").textContent = `日均 ${act.dailyAvgPosts} 条 · 另含 ${act.quotes} 引用`;
        } else if (act.sampleCapped) {
            $("kpiDailyPosts").textContent = `覆盖近 ${act.effectiveCoveredDays} 天 · 日均 ${act.dailyAvgPosts} 条（含 ${act.quotes} 引用）`;
        } else {
            $("kpiDailyPosts").textContent = `日均 ${act.dailyAvgPosts} 条（另含 ${act.quotes} 条引用）`;
        }

        $("kpiRepliesTitle").textContent = "回复";
        $("kpiReplies").textContent = fmtNum(act.replies);
        if (is48h) {
            const activeHrs = Number(act.activeUnits || 0);
            $("kpiDailyReplies").textContent = activeHrs > 0
                ? `活跃 ${activeHrs} / 48 小时 · 日均 ${act.dailyAvgReplies} 条回复`
                : "近 48 小时暂无回复互动";
        } else {
            $("kpiDailyReplies").textContent = `日均 ${act.dailyAvgReplies} 条评论互动`;
        }

        $("kpiReplyRatio").textContent = `${act.replyToPostRatio}x`;
        const ratioJudge =
            act.replyToPostRatio >= 2 && act.replyToPostRatio <= 6
                ? "✓ 健康（建议 2–6x）"
                : act.replyToPostRatio > 6
                    ? "回复很多，可多发原创"
                    : "偏低，建议 2x 以上";
        $("kpiReplyShare").textContent = ratioJudge;
        $("kpiReplyShare").className = `kpi-sub ${act.replyToPostRatio >= 2 && act.replyToPostRatio <= 6 ? "judge-good" : "judge-warn"}`;

        const erKnown = hasMetricSample(act, "engagementRateSampleCount");
        const viewsKnown = hasMetricSample(act, "mainPostViewSampleCount");
        $("kpiAvgEr").textContent = erKnown ? `${act.avgEngagementRatePct}%` : "--";
        $("kpiAvgEr").title = `公开互动率${metricSampleLabel(act, "engagementRateSampleCount")}`;
        const erVal = Number(act.avgEngagementRatePct || 0);
        const erJudge = erVal >= 3 ? "本站参考：较高（区间 1–3%）" : erVal >= 1 ? "本站参考：区间内（1–3%）" : "本站参考：偏低（区间 1–3%）";
        $("kpiAvgViews").textContent = `${erKnown ? erJudge : "公开互动率未取得"} · 均浏览 ${viewsKnown ? fmtNum(act.avgViewsPerPost) : "--"}${metricSampleLabel(act, "mainPostViewSampleCount")}`;
        $("kpiAvgViews").className = `kpi-sub ${!erKnown ? "" : erVal >= 1 ? "judge-good" : "judge-warn"}`;

        $("diagnosisSectionTitle").textContent = "节奏诊断";
        $("activityAdviceText").textContent = act.summaryAdvice;
        $("activeDaysBadge").textContent = act.activeProgressLabel;

        $("trendChartTitle").textContent = act.chartTitle;
        $("trendChartSubtitle").textContent = act.chartSubtitle;
        $("trendChartSubtitle").classList.remove("is-readout");

        const total = Math.max(1, act.totalActions);
        const pPost = ((act.originalPosts / total) * 100).toFixed(1);
        const pReply = ((act.replies / total) * 100).toFixed(1);
        const pQuote = ((act.quotes / total) * 100).toFixed(1);
        const pRt = ((act.retweets / total) * 100).toFixed(1);

        $("actionMixBar").innerHTML = act.totalActions > 0
            ? `
                <div class="mix-seg" style="width:${pReply}%; background:var(--x-blue)" title="回复 ${act.replies}"></div>
                <div class="mix-seg" style="width:${pPost}%; background:var(--text-primary)" title="原创推文 ${act.originalPosts}"></div>
                <div class="mix-seg" style="width:${pQuote}%; background:var(--x-green)" title="引用点评 ${act.quotes}"></div>
                <div class="mix-seg" style="width:${pRt}%; background:var(--x-amber)" title="纯转推 ${act.retweets}"></div>
            `
            : `<div class="mix-seg" style="width:100%; background:var(--border-color)"></div>`;

        $("actionMixLegend").innerHTML = `
            <span><i class="dot blue"></i>回复 <strong>${act.replies}</strong> (${act.totalActions > 0 ? pReply : "0.0"}%)</span>
            <span><i class="dot white"></i>原创发帖 <strong>${act.originalPosts}</strong> (${act.totalActions > 0 ? pPost : "0.0"}%)</span>
            <span><i class="dot green"></i>引用点评 <strong>${act.quotes}</strong> (${act.totalActions > 0 ? pQuote : "0.0"}%)</span>
            <span><i class="dot amber"></i>纯转推 <strong>${act.retweets}</strong> (${act.totalActions > 0 ? pRt : "0.0"}%)</span>
        `;

        const series = act.trendSeries || [];
        // 接口分桶保留原始整数；先汇总再显示，不累加 fmtNum 的缩写值。
        const engagementSamples = series.reduce((sum, bucket) => sum + bucket.engagementSampleCount, 0);
        const totalEngagements = series.reduce((sum, bucket) => sum + bucket.engagements, 0);
        const exactNum = (value) => Number(value).toLocaleString("zh-CN");
        $("activityMetricTotals").textContent = `本周期非纯转推 · 浏览样本 ${exactNum(act.viewSampleCount)} 帖 · 总浏览 ${act.viewSampleCount > 0 ? exactNum(act.totalViews) : "--"} · 互动样本 ${exactNum(engagementSamples)} 帖 · 总互动 ${engagementSamples > 0 ? exactNum(totalEngagements) : "--"}`;
        const maxBucketTotal = Math.max(1, ...series.map((d) => d.posts + d.quotes + d.replies + (d.threadReplies ?? 0)));
        const labelStep = series.length >= 28 ? 4 : series.length >= 20 ? 3 : 1;

        $("dailyTrendChart").innerHTML = series
            .map((d, idx) => {
                const threadReplies = d.threadReplies ?? 0;
                const authored = d.posts + d.quotes + threadReplies;
                const tot = authored + d.replies;
                const showAxisLabel = idx % labelStep === 0 || idx === series.length - 1;
                const tip = `${d.fullLabel}｜总动态 ${tot} 条（回复 ${d.replies} · 原创 ${d.posts} · 引用 ${d.quotes} · 串推楼层 ${threadReplies}）｜浏览 ${hasMetricSample(d, "viewSampleCount") ? fmtNum(d.views) : "--"}${metricSampleLabel(d, "viewSampleCount")} · 获赞评转藏 ${hasMetricSample(d, "engagementSampleCount") ? fmtNum(d.engagements) : "--"}${metricSampleLabel(d, "engagementSampleCount")}`;

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

        $("pillIOweBtn").textContent = `我没回谁 ${iOwe}`;
        $("pillUnrepliedBtn").textContent = `评论待回复 ${unreplied}`;
        $("pillTheyOweBtn").textContent = `谁没回我 ${theyOwe}`;
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
        const dedupCnt = Number(inter.filteredSamePostReplies || 0);
        $("interactionSummaryDesc").textContent =
            `${inter.totalInteractedPeers} 位推友 · 我去评 ${inter.totalOutboundActions} 帖 · 他来评 ${inter.totalInboundActions} 帖` +
            (dedupCnt > 0 ? ` · 同一帖子多次回复已合并 ${dedupCnt} 条` : "");

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
                label: `${Math.max(1, unreplied)} 条评论待回复`,
                extraTag: item.reciprocityCode === "I_OWE" ? "没去评 TA" : "",
                tone: "amber",
                actionText: "去回复",
                actionUrl: replyCommentUrl,
                primary: true
            };
        }
        // 在「我没回谁」筛选下（TA 来我推文下评论过，但我没去 TA 发布过的推文下评论）：主按钮直达 TA 发布过的最新推文去回评
        if (item.reciprocityCode === "I_OWE") {
            return {
                label: "没去评 TA",
                extraTag: unreplied > 0 ? `${unreplied} 条评论待回复` : "",
                tone: "amber",
                actionText: "去回访",
                actionUrl: peerLatestPostsUrl(u.username),
                primary: true
            };
        }
        if (item.reciprocityCode === "THEY_OWE") {
            return { label: "TA 没回我", extraTag: "", tone: "pink", actionText: "主页", actionUrl: profileUrl, primary: false };
        }
        // 互相评论（我去过 TA 推文下评论，TA 也来过我的推文下评论）
        if (unreplied > 0) {
            return {
                label: "互相评论",
                extraTag: `${unreplied} 条评论待回复`,
                tone: "green",
                actionText: "去回复",
                actionUrl: replyCommentUrl,
                primary: true
            };
        }
        return { label: "互相评论", extraTag: "", tone: "green", actionText: "去互动", actionUrl: peerLatestPostsUrl(u.username), primary: false };
    }

    function followRelationText(item, report) {
        const audit = report?.followAudit;
        const legacyStateKnown = audit && !/待核实|未知/.test(item.relationshipPortrait || "") &&
            (item.iFollowThem || !audit.followingsSampleTruncated) &&
            (item.theyFollowMe || !audit.followersSampleTruncated);
        const followStateKnown = typeof item.followStateKnown === "boolean" ? item.followStateKnown : Boolean(legacyStateKnown);
        if (!followStateKnown) return "关注关系待核实";
        if (item.iFollowThem && item.theyFollowMe) return "互关";
        if (item.iFollowThem) return "TA 未回关你";
        if (item.theyFollowMe) return "TA 关注了你";
        return "未互关";
    }

    function buildPeerDetailHtml(report, item, statusMeta) {
        const me = report.profile;
        const peer = item.peer;
        const myRaw = Number(item.myRawCommentCount || 0);
        const peerRaw = Number(item.peerRawCommentCount || 0);
        const facts = [];
        facts.push(
            `我评了 TA 的 <strong>${item.myRepliesToPeer}</strong> 个帖子` +
                (myRaw > item.myRepliesToPeer ? `（共 ${myRaw} 条回复，同一帖子只算 1 次）` : "")
        );
        facts.push(
            `TA 评了我的 <strong>${item.peerRepliesToMe}</strong> 个帖子` +
                (peerRaw > item.peerRepliesToMe ? `（共 ${peerRaw} 条回复，同一帖子只算 1 次）` : "")
        );
        if (Number(item.unrepliedInMyPostCount || 0) > 0) {
            facts.push(`我的帖子下还有 <strong>${item.unrepliedInMyPostCount}</strong> 处 TA 的评论没回`);
        }
        if (Number(item.peerInThreadRepliesOnTheirPost || 0) > 0) {
            facts.push(`TA 在自己的帖子里回过你 ${item.peerInThreadRepliesOnTheirPost} 次（不算来评你）`);
        }
        if (Number(item.myRepliesInMyOwnPost || 0) > 0) {
            facts.push(`你在自己的帖子里回过 TA ${item.myRepliesInMyOwnPost} 次（不算去评 TA）`);
        }
        if (Number(item.myQuotesOfPeer || 0) + Number(item.peerQuotesOfMe || 0) > 0) {
            facts.push(`引用：我引用 TA ${item.myQuotesOfPeer} 次，TA 引用我 ${item.peerQuotesOfMe} 次`);
        }

        const replyBtn =
            Number(item.unrepliedInMyPostCount || 0) > 0 && item.latestUnrepliedTweetUrl
                ? `<a class="x-blue-pill sm" href="${escapeHtml(safeHttpUrl(item.latestUnrepliedTweetUrl, peerDialogueUrl(me.username, peer.username)))}" target="_blank" rel="noopener noreferrer">去回复 TA 的评论 ↗</a>`
                : "";

        return `<div class="peer-detail" data-peer-detail="${escapeHtml(peer.username)}">
            <div class="peer-detail-handle">@${escapeHtml(peer.username)} · ${escapeHtml(followRelationText(item, report))}</div>
            ${item.relationshipPortrait ? `<div class="peer-detail-summary">${escapeHtml(item.relationshipPortrait)}</div>` : ""}
            <ul class="peer-detail-facts">${facts.map((f) => `<li>${f}</li>`).join("")}</ul>
            ${item.recentSnippet ? `<div class="snippet-quote">${escapeHtml(item.recentSnippet)}</div>` : ""}
            <div class="peer-detail-actions">
                ${replyBtn}
                <a class="x-outline-pill sm" href="${escapeHtml(peerLatestPostsUrl(peer.username))}" target="_blank" rel="noopener noreferrer">去评 TA 最新推文 ↗</a>
                <a class="x-outline-pill sm" href="${escapeHtml(peerDialogueUrl(me.username, peer.username))}" target="_blank" rel="noopener noreferrer">查看双方对话 ↗</a>
                <a class="x-outline-pill sm" href="https://x.com/${encodeURIComponent(peer.username)}" target="_blank" rel="noopener noreferrer">TA 的主页 ↗</a>
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
        if (state.period !== "WEEK" && state.period !== "MONTH") acts.push({ text: "看近 7 天", action: "period:WEEK" });
        if (state.period !== "MONTH") acts.push({ text: "看近 30 天", action: "period:MONTH" });
        return acts;
    }

    function renderPeerList(report) {
        const listEl = $("peerInteractionList");
        let list = [...(report.interactions.peerStats || [])];

        if (state.peerRelFilter !== "ALL") {
            list = list.filter((p) => {
                if (state.peerRelFilter === "UNREPLIED_POST") {
                    return Number(p.unrepliedInMyPostCount || 0) > 0;
                }
                return p.reciprocityCode === state.peerRelFilter;
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

        const effectiveSort =
            state.peerSortBy !== "intimacy"
                ? state.peerSortBy
                : state.peerRelFilter === "I_OWE" || state.peerRelFilter === "UNREPLIED_POST"
                    ? "iOweFirst"
                    : state.peerRelFilter === "THEY_OWE"
                        ? "theyOweFirst"
                        : "intimacy";

        list.sort((a, b) => {
            switch (effectiveSort) {
                case "iOweFirst":
                    return (
                        (b.unrepliedInMyPostCount || 0) - (a.unrepliedInMyPostCount || 0) ||
                        (b.peerRepliesToMe - b.myRepliesToPeer) - (a.peerRepliesToMe - a.myRepliesToPeer) ||
                        b.peerRepliesToMe - a.peerRepliesToMe
                    );
                case "theyOweFirst":
                    return (
                        (b.myRepliesToPeer - b.peerRepliesToMe) - (a.myRepliesToPeer - a.peerRepliesToMe) ||
                        b.myRepliesToPeer - a.myRepliesToPeer
                    );
                case "outbound":
                    return b.myRepliesToPeer - a.myRepliesToPeer || b.totalInteractions - a.totalInteractions;
                case "inbound":
                    return b.peerRepliesToMe - a.peerRepliesToMe || b.totalInteractions - a.totalInteractions;
                default:
                    return b.intimacyScore - a.intimacyScore;
            }
        });

        if (list.length === 0) {
            const filterEmptyMsg = {
                I_OWE: "来你推文下评论过的人，你都去评论过 TA 的推文了",
                UNREPLIED_POST: "别人给你的评论都已回复",
                THEY_OWE: "你去评论过推文的人，都来评论过你的推文",
                MUTUAL: "当前时间段内还没有互相评论推文的人"
            };
            const msg = state.peerSearchQuery
                ? `没有找到「${state.peerSearchQuery}」`
                : filterEmptyMsg[state.peerRelFilter] || "这个时间段内还没有互动记录";
            const acts = state.peerSearchQuery
                ? [{ text: "清空搜索", action: "clearPeerSearch" }]
                : state.peerRelFilter !== "ALL"
                    ? [{ text: "查看全部", action: "peerFilter:ALL" }, ...widerPeriodActions()]
                    : widerPeriodActions();
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
                const extraTagHtml = meta.extraTag
                    ? `<span class="mini-tag amber">${escapeHtml(meta.extraTag)}</span>`
                    : "";
                return `<div class="peer-item ${isOpen ? "is-open" : ""}">
                    <div class="x-user-row clickable peer-row" data-peer-handle="${escapeHtml(u.username)}" role="button" tabindex="0" aria-expanded="${isOpen}">
                        ${avatarHtml(u, "md")}
                        <div class="user-row-main">
                            <div class="peer-row-line">
                                <span class="peer-name">${escapeHtml(u.displayName)}</span>
                                ${verifiedSvgHtml(u.verified)}
                                <span class="user-handle-muted">@${escapeHtml(u.username)}</span>
                                <span class="mini-tag ${meta.tone}">${escapeHtml(meta.label)}</span>
                                ${extraTagHtml}
                            </div>
                            <div class="peer-row-sub">
                                我去评 <strong>${item.myRepliesToPeer}</strong> 帖 · TA 来评 <strong>${item.peerRepliesToMe}</strong> 帖 · ${escapeHtml(followRelationText(item, report))}
                            </div>
                        </div>
                        <a class="${actionCls} peer-ext-link" href="${escapeHtml(meta.actionUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(meta.actionText)} ↗</a>
                        <svg class="peer-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="M3.543 8.96l1.414-1.42L12 14.59l7.043-7.05 1.414 1.42L12 17.41 3.543 8.96z"/></svg>
                    </div>
                    ${isOpen ? buildPeerDetailHtml(report, item, meta) : ""}
                </div>`;
            })
            .join("");

        const loadMoreHtml = remainPeers > 0
            ? `<div class="list-load-more-row">
                   <button type="button" class="x-outline-pill sm" id="loadMorePeersBtn">再显示 ${Math.min(remainPeers, LIST_LOAD_STEP)} 人（还有 ${remainPeers} 人）</button>
               </div>`
            : "";

        listEl.innerHTML = rowsMarkup + loadMoreHtml;

        const moreBtn = $("loadMorePeersBtn");
        if (moreBtn) {
            moreBtn.addEventListener("click", () => {
                state.peerRenderLimit += LIST_LOAD_STEP;
                renderPeerList(report);
            });
        }

        listEl.querySelectorAll(".peer-ext-link, .peer-detail a").forEach((link) => {
            link.addEventListener("click", (e) => e.stopPropagation());
        });

        const toggleRow = (row) => {
            const handle = row.dataset.peerHandle;
            state.expandedPeerUsername =
                (state.expandedPeerUsername || "").toLowerCase() === handle.toLowerCase() ? null : handle;
            renderPeerList(report);
        };
        listEl.querySelectorAll(".peer-row[data-peer-handle]").forEach((row) => {
            row.addEventListener("click", () => toggleRow(row));
            row.addEventListener("keydown", (e) => {
                if (e.target === row && (e.key === "Enter" || e.key === " ")) {
                    e.preventDefault();
                    toggleRow(row);
                }
            });
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

        const graphIncomplete = audit.followersSampleTruncated || audit.followingsSampleTruncated;
        const limitations = [];
        if (audit.followersSampleTruncated) limitations.push(`粉丝名单未完整（已采样 ${fmtNum(audit.followersSampleCount)} 位）`);
        if (audit.followingsSampleTruncated) limitations.push("关注名单未完整");
        const unknownFollowBackCount = Number(audit.unknownFollowBackCount || 0);
        const unknownFollowingCount = Number(audit.unknownFollowingCount || 0);
        const hasUnknownRelations = unknownFollowBackCount > 0 || unknownFollowingCount > 0;
        if (graphIncomplete || hasUnknownRelations) limitations.push(`回关状态尚待核实 ${fmtNum(unknownFollowBackCount)} 位、我方关注状态尚待核实 ${fmtNum(unknownFollowingCount)} 位；已核实未回关账号正常显示，未知关系不列入确定未回关或清理建议`);
        $("followScopeNote").textContent = limitations.join("；");
        $("followScopeNote").hidden = !graphIncomplete && !hasUnknownRelations;

        const tabBtns = $("followStateTabs").querySelectorAll(".seg-tab");
        tabBtns[0].textContent = `TA未回关 ${audit.notFollowingBackCount}`;
        tabBtns[1].textContent = `我未回关 ${audit.fansNotFollowedBackCount}`;
        tabBtns[2].textContent = `互关 ${audit.mutualCount}`;

        $("unfollowCategoryPills").hidden = state.followStateTab !== "NOT_FOLLOWING_BACK";

        const items = getFilteredFollowItems(report);
        const listEl = $("followAuditList");

        if (items.length === 0) {
            const emptyMsg = state.followSearchQuery
                ? `没有找到「${state.followSearchQuery}」`
                : state.followStateTab === "NOT_FOLLOWING_BACK"
                    ? "这个分类下没有账号"
                    : state.followStateTab === "FAN_ONLY"
                        ? (graphIncomplete ? "已确认样本中暂无待回关账号；未采全关系仍未知" : "关注你的人你都回关了")
                        : "暂无互关数据";
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
                const isOpened = state.openedFollowSet.has(uKey);
                const tagClass = isKept
                    ? "green"
                    : item.auditCategory === "ONE_WAY_EFFORT" || item.auditCategory === "ZERO_INTERACTION"
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

                return `<div class="x-user-row">
                    ${avatarHtml(u, "md")}
                    <div class="user-row-main">
                        <div class="peer-row-line">
                            <span class="peer-name">${escapeHtml(u.displayName)}</span>
                            ${verifiedSvgHtml(u.verified)}
                            <span class="user-handle-muted">@${escapeHtml(u.username)}</span>
                            <span class="mini-tag ${tagClass}" title="${escapeHtml(item.recommendation || "")}">${isKept ? "已保留" : isOpened ? "已打开·待刷新确认" : escapeHtml(item.auditLabel)}</span>
                        </div>
                        <div class="peer-row-sub">
                            粉丝 ${fmtNum(u.followersCount)} · 我去评 <strong>${item.myRepliesToThem}</strong> 帖 · TA 来评 <strong>${item.theirRepliesToMe}</strong> 帖
                        </div>
                        ${u.bio ? `<div class="bio-one-line">${escapeHtml(u.bio)}</div>` : ""}
                    </div>
                    <div class="peer-row-actions">
                        ${sideBtn}
                        <a class="${actionCls} follow-go-link" data-open-follow="${escapeHtml(u.username)}" href="${safeProfileHref}" target="_blank" rel="noopener noreferrer">${actionText} ↗</a>
                    </div>
                </div>`;
            })
            .join("");

        const loadMoreHtml = remainItems > 0
            ? `<div class="list-load-more-row">
                   <button type="button" class="x-outline-pill sm" id="loadMoreFollowBtn">再显示 ${Math.min(remainItems, LIST_LOAD_STEP)} 人（还有 ${remainItems} 人）</button>
               </div>`
            : "";

        listEl.innerHTML = rowsHtml + loadMoreHtml;

        listEl.querySelectorAll("[data-wl-toggle]").forEach((btn) => {
            btn.addEventListener("click", () => {
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
            });
        });

        // 外部页面打开不代表关系变更成功；待办保留，等待后续关系数据确认。
        listEl.querySelectorAll("[data-open-follow]").forEach((link) => {
            const isGoAction = /去取关|去回关/.test(link.textContent);
            if (!isGoAction) return;
            link.addEventListener("click", () => {
                state.openedFollowSet.add(link.dataset.openFollow.toLowerCase());
                showToast("已打开 X 页面；完成操作后刷新数据确认，待办暂时保留");
                setTimeout(() => renderFollowAuditPanel(report), 0);
            });
        });

        const moreFollowBtn = $("loadMoreFollowBtn");
        if (moreFollowBtn) {
            moreFollowBtn.addEventListener("click", () => {
                state.followRenderLimit += LIST_LOAD_STEP;
                renderFollowAuditPanel(report);
            });
        }
    }

    function csvSafeCell(val) {
        const clean = String(val === null || val === undefined ? "" : val)
            .replace(/[\r\n]+/g, " ")
            .trim();
        const guarded = /^[=+\-@\t]/.test(clean) ? `'${clean}` : clean;
        return `"${guarded.replace(/"/g, '""')}"`;
    }

    function exportFollowListCsv() {
        if (!state.report) return;
        const items = getFilteredFollowItems(state.report);
        if (items.length === 0) {
            showToast("当前列表为空，无需导出");
            return;
        }
        const header = [
            "username",
            "displayName",
            "followers",
            "following",
            "relationState",
            "auditLabel",
            "myOutboundInPeriod",
            "theirInboundInPeriod",
            "profileUrl"
        ];
        const lines = [header.join(",")];
        for (const it of items) {
            const row = [
                csvSafeCell(it.user.username),
                csvSafeCell(it.user.displayName || ""),
                Number(it.user.followersCount || 0),
                Number(it.user.followingCount || 0),
                csvSafeCell(it.relationState),
                csvSafeCell(it.auditLabel),
                Number(it.myRepliesToThem || 0),
                Number(it.theirRepliesToMe || 0),
                csvSafeCell(safeHttpUrl(it.profileUrl, `https://x.com/${encodeURIComponent(it.user.username)}`))
            ];
            lines.push(row.join(","));
        }
        const blob = new Blob(["\uFEFF" + lines.join("\n")], { type: "text/csv;charset=utf-8;" });
        const blobUrl = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = blobUrl;
        a.download = `x-follow-audit-${state.report.profile.username}-${state.period.toLowerCase()}-${state.followStateTab.toLowerCase()}.csv`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(blobUrl), 1500);
        showToast(`已导出 ${items.length} 条账号关系记录`);
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
                return `🟢 当前正处于黄金档【${activeNowLabel}】· 适合立即发主推`;
            }
            if (bestSlotLabel && Number.isFinite(bestWaitMin)) {
                const waitH = Math.floor(bestWaitMin / 60);
                const waitM = bestWaitMin % 60;
                return waitH >= 24
                    ? `⏰ 距下一黄金档【${bestSlotLabel}】约 ${Math.floor(waitH / 24)} 天 ${waitH % 24} 小时`
                    : `⏰ 距下一黄金档【${bestSlotLabel}】还有 ${waitH} 小时 ${waitM} 分`;
            }
        } catch (_) {}
        return "";
    }

    function timingPeriodDescription() {
        const timezone = state.report?.contentTiming?.timezone || "Asia/Shanghai";
        return `按已采集浏览与公开互动率计算（${timezone === "Asia/Shanghai" ? "北京时间" : timezone}） · 点按格子看详情`;
    }

    function renderTimingPanel(report) {
        const ct = report.contentTiming;
        const slots = ct.goldenSlots || [];
        const ranks = ["第 1 推荐时段", "第 2 推荐时段", "第 3 推荐时段"];

        $("timingPeriodDesc").textContent = timingPeriodDescription();
        $("timingPeriodDesc").classList.remove("is-readout");

        const nextBadge = $("nextGoldenWindowBadge");
        if (nextBadge) {
            const hint = computeNextGoldenWindowHint(slots);
            nextBadge.textContent = hint;
            nextBadge.hidden = !hint;
        }

        $("goldenSlotsGrid").innerHTML = slots.length
            ? slots
                  .map(
                      (s, i) => `<div class="golden-card">
                      <div class="golden-rank">${ranks[i] || "推荐时段"}</div>
                      <div class="golden-time">${escapeHtml(s.label)}</div>
                      <div class="golden-meta">平均公开互动率 <strong>${s.avgEngagementRatePct}%</strong> · 场均浏览 ${fmtNum(s.avgViews)}（样本 ${s.postCount} 帖）</div>
                  </div>`
                  )
                  .join("")
            : emptyStateHtml("暂无可比较的浏览与公开互动样本，暂时算不出黄金时段", widerPeriodActions());

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
                    const tip = `${c.dayLabel} ${String(c.hour).padStart(2, "0")}:00–${String(c.hour + 3).padStart(2, "0")}:00｜发帖 ${c.postCount} 条 · 平均公开互动率 ${cellEr}${metricSampleLabel(c, "engagementRateSampleCount")} · 场均浏览 ${hasMetricSample(c, "viewSampleCount") ? fmtNum(c.avgViews) : "--"}${metricSampleLabel(c, "viewSampleCount")}`;
                    return `<div class="hm-cell" data-tip="${escapeHtml(tip)}" role="button" tabindex="0" style="background:${bg}; color:${color}" title="${escapeHtml(tip)}">
                        ${c.postCount > 0 ? cellEr : "·"}
                    </div>`;
                })
                .join("");
            rowsHtml.push(`<div class="hm-row"><span class="hm-day-label">${escapeHtml(dayName)}</span>${cellSpans}</div>`);
        }
        $("heatmapGridRows").innerHTML = rowsHtml.join("");

        $("formatCardsGrid").innerHTML = (ct.formatStats || [])
            .map(
                (f) => `<div class="format-card">
                <div class="format-title">${escapeHtml(f.formatLabel)}</div>
                <div class="format-er">${hasMetricSample(f, "engagementRateSampleCount") ? `${f.avgEngagementRatePct}%` : "--"} <span style="font-size:12px; font-weight:500; color:var(--text-secondary)">平均公开互动率${metricSampleLabel(f, "engagementRateSampleCount")}</span></div>
                <div class="golden-meta">本周期 ${f.postCount} 帖 · 场均浏览 ${hasMetricSample(f, "viewSampleCount") ? fmtNum(f.avgViews) : "--"}${metricSampleLabel(f, "viewSampleCount")}</div>
                <div class="golden-meta">场均获赞 ${hasMetricSample(f, "likeSampleCount") ? f.avgLikes : "--"}${metricSampleLabel(f, "likeSampleCount")} · 场均评论 ${hasMetricSample(f, "replySampleCount") ? f.avgReplies : "--"}${metricSampleLabel(f, "replySampleCount")}</div>
            </div>`
            )
            .join("");

        renderAlgoPoolAndSimulator(report);
        renderTopTweetsList(report);
    }

    function renderAlgoPoolAndSimulator(report) {
        const ah = report.algorithmHealth;
        if (!ah) return;

        const evaluated = ah.viewSampleCount ?? ah.evaluatedPostCount;
        const viewsKnown = hasMetricSample(ah, "viewSampleCount");
        const evalTotal = Math.max(1, evaluated || 0);
        const oonPct = viewsKnown ? Math.round((ah.outOfNetworkPostCount * 100) / evalTotal) : "--";
        const inNetPct = viewsKnown ? Math.round((ah.inNetworkPostCount * 100) / evalTotal) : "--";
        const lowPct = viewsKnown ? Math.round((ah.lowReachPostCount * 100) / evalTotal) : "--";

        const poolsEl = $("algoReachPoolsGrid");
        if (poolsEl) {
            poolsEl.innerHTML = `
                <div class="format-card pool-card oon">
                    <div class="format-title">浏览超过粉丝数</div>
                    <div class="format-er" style="color:var(--x-green)">${viewsKnown ? ah.outOfNetworkPostCount : "--"} 帖 <span style="font-size:12px; font-weight:600; color:var(--text-secondary)">(${oonPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="golden-meta">单帖浏览大于粉丝数；不能确定观看者或推荐来源</div>
                </div>
                <div class="format-card pool-card innet">
                    <div class="format-title">浏览为粉丝数的 5%–100%</div>
                    <div class="format-er" style="color:var(--x-blue)">${viewsKnown ? ah.inNetworkPostCount : "--"} 帖 <span style="font-size:12px; font-weight:600; color:var(--text-secondary)">(${inNetPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="golden-meta">按浏览/粉丝数分组，不代表观看者都是粉丝</div>
                </div>
                <div class="format-card pool-card low">
                    <div class="format-title">浏览偏低（&lt;5% 粉丝）</div>
                    <div class="format-er" style="color:${ah.lowReachPostCount > 0 ? "var(--x-amber)" : "var(--text-primary)"}">${viewsKnown ? ah.lowReachPostCount : "--"} 帖 <span style="font-size:12px; font-weight:600; color:var(--text-secondary)">(${lowPct}${viewsKnown ? "%" : ""})</span></div>
                    <div class="golden-meta">仅表示浏览相对粉丝数较低，原因尚不能确定</div>
                </div>
            `;
        }

        const tipsBox = $("algoPlainTipsBox");
        if (tipsBox) {
            tipsBox.innerHTML = `
                <div class="algo-tip-line">浏览分组${metricSampleLabel(ah, "viewSampleCount")} ${escapeHtml(ah.metricsNote || "")}</div>
                <div class="algo-tip-line">${escapeHtml(ah.diversityAdvice)}</div>
                <div class="algo-tip-line">${escapeHtml(ah.contentRuleAdvice)}</div>
            `;
        }

        const timingHintEl = $("draftLiveTimingHint");
        if (timingHintEl) {
            if (ah.latestMainPostAt) {
                const gapHrs = Math.max(0, (Date.now() - new Date(ah.latestMainPostAt).getTime()) / 3600000);
                if (gapHrs < 3) {
                    timingHintEl.textContent = `⏱️ 注意：距你上一条主推仅过去 ${gapHrs.toFixed(1)} 小时；本站按 3 小时作间隔参考，不代表平台折叠或降权`;
                } else {
                    timingHintEl.textContent = `✅ 距你上一条主推已间隔 ${gapHrs >= 24 ? Math.floor(gapHrs / 24) + " 天" : gapHrs.toFixed(1) + " 小时"}· 粘贴草稿可查看本站规则评分，不能据此判断平台分发`;
                }
            } else {
                timingHintEl.textContent = "粘贴草稿，按本站规则检查间隔、外链、篇幅与提问；评分不预测推荐流量";
            }
        }

        evaluatePrePostDraft();
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
        const ah = state.report ? state.report.algorithmHealth : null;
        const slots = state.report && state.report.contentTiming ? state.report.contentTiming.goldenSlots : [];

        let score = 56;
        const checks = [];

        // 1. 同作者连发间隔检测（AuthorDiversityScorer）
        if (ah && ah.latestMainPostAt) {
            const gapMin = Math.max(0, Math.round((Date.now() - new Date(ah.latestMainPostAt).getTime()) / 60000));
            if (gapMin < 60) {
                score -= 18;
                checks.push(`⚠️ <strong>连发过密（-18分）</strong>：距上一条主推仅 ${gapMin} 分钟，本站按间隔扣分，可考虑稍后发布；无法据此确定算法折叠或曝光变化`);
            } else if (gapMin < 180) {
                score -= 8;
                checks.push(`💡 <strong>间隔偏近（-8分）</strong>：距上一条主推 ${(gapMin / 60).toFixed(1)} 小时，本站以 3 小时作发布节奏参考，并非平台权重阈值`);
            } else {
                score += 12;
                checks.push(`✅ <strong>发帖间隔健康（+12分）</strong>：距上一条主推已超 3 小时，符合本站间隔参考，不保证平台分发效果`);
            }
        } else {
            score += 10;
            checks.push(`✅ <strong>发帖间隔未知（本站基础分 +10）</strong>：当前没有上一条主推时间可供比较，不能确认是否存在密集连发`);
        }

        // 2. 外部链接跳出检测（严格匹配 ASCII URL 字符集，避免吞掉紧随其后的中文标点或正文）
        const urls = text.match(URL_TOKEN_RE) || [];
        const hasExtLink = urls.some((u) => !/^https?:\/\/((www|mobile)\.)?(x\.com|twitter\.com)(\/|$)/i.test(u));
        if (hasExtLink) {
            score -= 16;
            checks.push(`⚠️ <strong>检测到网页外链（-16分）</strong>：本站规则对外链扣分；可比较正文或评论区放链接的效果，当前不能确认外链导致降权`);
        } else {
            score += 10;
            checks.push(`✅ <strong>未检测到站外链接（+10分）</strong>：符合本站外链检查规则，不代表获得平台推荐加权`);
        }

        // 3. 阅读停留时长评估（Dwell Time，按 Unicode 码点计数兼容 Emoji）
        const cleanLen = Array.from(text.replace(URL_TOKEN_RE, "").trim()).length;
        const lineCount = text.split(/\n+/).filter(Boolean).length;
        if (cleanLen >= 220) {
            score += 14 + (hasMedia ? 4 : 0);
            checks.push(`✅ <strong>长文篇幅参考（+${14 + (hasMedia ? 4 : 0)}分）</strong>：约 ${cleanLen} 字长推；本站未测量真实阅读停留；记得把最抓人的结论放前 2 行吸引点开「显示更多」`);
        } else if (cleanLen >= 45 || (cleanLen >= 20 && hasMedia)) {
            const pts = 10 + (lineCount >= 2 ? 3 : 0) + (hasMedia ? 4 : 0);
            score += pts;
            checks.push(`✅ <strong>篇幅与排版参考（+${pts}分）</strong>：${cleanLen} 字${lineCount >= 2 ? "分段清晰" : ""}${hasMedia ? "且带图/视频" : ""}；本站未测量完读或停留表现`);
        } else {
            score -= 8;
            checks.push(`💡 <strong>内容篇幅偏短（-8分）</strong>：仅 ${cleanLen} 字且未勾选配图，无法据此推断停留时间或推荐结果，可考虑补充 1–2 句具体细节或配图`);
        }

        // 4. 评论与收藏钩子检测（高权重动作）
        const hasQuestionOrHook = /[?？]|怎么看|如何看|你觉得|大家觉得|聊聊|哪一个|收藏|建议先马|清单|复盘|总结/.test(text);
        if (hasQuestionOrHook) {
            score += 10;
            checks.push(`✅ <strong>自带评论/收藏钩子（+10分）</strong>：检测到提问或收藏相关表达；互动效果仍需发布后观察`);
        } else {
            score += 2;
            checks.push(`💡 <strong>可加一句互动提问</strong>：可在末尾邀请推友表达看法，是否增加互动需观察`);
        }

        // 5. 黄金发帖时段匹配
        const slotHint = computeNextGoldenWindowHint(slots || []);
        if (slotHint.startsWith("🟢")) {
            score += 6;
            checks.push(`✅ <strong>命中你的黄金时段（+6分）</strong>：${escapeHtml(slotHint.replace(/^🟢\s*/, ""))}`);
        } else if (slotHint) {
            checks.push(`⏰ <strong>发布时间参考</strong>：${escapeHtml(slotHint.replace(/^⏰\s*/, ""))}`);
        }

        const finalScore = Math.max(18, Math.min(98, Math.round(score)));
        const gradeLabel = finalScore >= 82 ? "🔥 本站规则评分较高"
            : finalScore >= 65 ? "⚡ 本站规则评分中等"
            : "🛠️ 建议按下方提示微调后再发";
        const scoreColor = finalScore >= 82 ? "var(--x-green)" : finalScore >= 65 ? "var(--x-blue)" : "var(--x-amber)";

        outEl.hidden = false;
        outEl.innerHTML = `
            <div class="draft-score-head">
                <div class="draft-score-title-group">
                    <span class="draft-score-num" style="color:${scoreColor}">草稿规则分 ${finalScore} / 100（不预测平台推荐）</span>
                    <span class="mini-tag">${gradeLabel}</span>
                </div>
                <div class="draft-score-actions">
                    <button type="button" class="x-outline-pill sm" id="clearDraftSimBtn">清空</button>
                    <a class="x-blue-pill sm" href="https://x.com/intent/tweet?text=${encodeURIComponent(text)}" target="_blank" rel="noopener noreferrer">
                        带草稿发推 ↗
                    </a>
                </div>
            </div>
            <ul class="draft-check-list">
                ${checks.map((c) => `<li>${c}</li>`).join("")}
            </ul>
        `;

        const clrBtn = $("clearDraftSimBtn");
        if (clrBtn) {
            clrBtn.addEventListener("click", () => {
                inputEl.value = "";
                outEl.hidden = true;
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
                                  : `<span class="mini-tag">短推</span>`;
                      const bmBadge =
                          t.bookmarkCount > 0 && postMetricsKnown(t, ["bookmarks", "views"]) && t.viewCount > 0
                              ? `<span class="mini-tag amber" title="书签收藏占公开浏览比例，不能据此确定阅读停留或内容质量">收藏率 ${t.bookmarkRatePct || 0}%</span>`
                              : "";
                      const aScore = erKnown && typeof t.algoScore === "number" && t.algoScore >= 0 ? t.algoScore : null;
                      const algoColor = aScore === null ? "blue" : aScore >= 78 ? "green" : aScore >= 58 ? "blue" : "amber";
                      const algoTip = `${t.algoTierLabel || "本站规则分"}：根据公开互动、浏览/粉丝比与本站间隔/外链规则评分，不代表平台推荐或真实停留`;
                      const signalsHtml =
                          t.algoSignals && t.algoSignals.length
                              ? `<div class="tweet-algo-signals">
                                    <span class="algo-explain-prefix">样本观察：</span>
                                    ${t.algoSignals.map((sig) => `<span class="algo-sig-chip">${escapeHtml(sig)}</span>`).join("")}
                                 </div>`
                              : "";
                      return `<article class="x-tweet-card">
                          <div class="tweet-top-meta">
                              <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                                  ${avatarHtml(p, "sm")}
                                  <strong style="color:var(--text-primary)">${escapeHtml(p.displayName)}</strong>
                                  <span>@${escapeHtml(p.username)} · ${escapeHtml(t.createdLabel)}</span>
                                  ${tagBadge}
                              </div>
                              <div style="display:flex; gap:6px; align-items:center; flex-wrap:wrap;">
                                  <span class="mini-tag ${algoColor}" title="${escapeHtml(algoTip)}">本站规则分 ${aScore ?? "--"}</span>
                                  ${bmBadge}
                                  <span class="mini-tag blue">总互动 ${engagementKnown ? fmtNum(t.totalEngagements) : "--"}</span>
                                  <span class="mini-tag pink">公开互动率 ${rateKnown ? `${t.engagementRatePct}%` : "--"}</span>
                              </div>
                          </div>
                          <div class="tweet-text">${escapeHtml(t.text)}</div>
                          ${signalsHtml}
                          <div class="tweet-metrics-bar">
                              <span class="tweet-metric-item" title="评论数">${iconSvg("reply")} ${metric("replies", t.replyCount)}</span>
                              <span class="tweet-metric-item" title="转推与引用">${iconSvg("retweet")} ${metric("reposts", t.retweetCount)}</span>
                              <span class="tweet-metric-item" title="点赞数">${iconSvg("like")} ${metric("likes", t.likeCount)}</span>
                              <span class="tweet-metric-item" title="书签收藏">${iconSvg("bookmark")} ${metric("bookmarks", t.bookmarkCount)}</span>
                              <span class="tweet-metric-item" title="公开浏览计数">${iconSvg("views")} ${metric("views", t.viewCount)}</span>
                              <a href="${escapeHtml(safeHttpUrl(t.tweetUrl, `https://x.com/${encodeURIComponent(p.username)}`))}" target="_blank" rel="noopener noreferrer">查看原推 ↗</a>
                          </div>
                      </article>`;
                  })
                  .join("")
            : emptyStateHtml("这个类型下暂无推文", widerPeriodActions());
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
            return raw;
        }
        return raw.replace(
            /(?:🤝\s*)?(?:关注|样本)回关率\s*[\d.]+%(?:[ \t]*[（(][^）)\r\n]*[）)])?/g,
            `📈 周期总浏览 ${hasMetricSample(report.activity, "viewSampleCount") ? fmtNum(report.activity.totalViews) : "--"}`
        );
    }

    function ensureCanvasAvatarLoaded(avatarUrl) {
        if (!avatarUrl) return null;
        const cached = state.avatarImgCache[avatarUrl];
        if (cached === "ERROR") return null;
        if (cached && cached.complete && cached.naturalWidth > 0) {
            return cached;
        }
        if (!cached) {
            const img = new Image();
            img.crossOrigin = "anonymous";
            img.referrerPolicy = "no-referrer";
            state.avatarImgCache[avatarUrl] = img;
            img.onload = () => {
                if (state.report) {
                    drawShareCardCanvas(state.report);
                }
            };
            img.onerror = () => {
                state.avatarImgCache[avatarUrl] = "ERROR";
            };
            // 附加轻量参数避免复用无 CORS 响应头的浏览器图片缓存导致 Canvas 污染
            const sep = avatarUrl.includes("?") ? "&" : "?";
            img.src = `${avatarUrl}${sep}xops_cors=1`;
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

        renderCircleGrid("coreFansGrid", ic.coreFans, "green", "这段时间还没有常来评论的铁粉");
        renderCircleGrid("activeMutualsGrid", ic.activeMutuals, "blue", "这段时间暂无双向互评伙伴");
        renderCircleGrid("needWarmupPeersGrid", ic.needWarmupPeers, "amber", "这段时间暂无待回访推友");

        drawShareCardCanvas(report);
    }

    function renderCircleGrid(containerId, members, badgeColor, emptyMsg) {
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
                        <a href="https://x.com/${encodeURIComponent(m.user.username)}" target="_blank" rel="noopener noreferrer" style="color:var(--text-primary)">
                            ${escapeHtml(m.user.displayName)}
                        </a>
                        ${verifiedSvgHtml(m.user.verified)}
                        <span class="mini-tag ${badgeColor}">${escapeHtml(m.badge)}</span>
                    </div>
                    <div class="user-handle-muted">@${escapeHtml(m.user.username)} ${m.mutualFollow ? "· 互相关注" : ""}</div>
                    <div class="circle-reason">${escapeHtml(m.highlightReason)}</div>
                    <div class="circle-card-actions">
                        <a class="x-outline-pill sm" href="${escapeHtml(peerLatestPostsUrl(m.user.username))}" target="_blank" rel="noopener noreferrer" title="直达 TA 最新原创主推去评论">去评最新推文 ↗</a>
                        ${myUsername ? `<a class="x-outline-pill sm" href="${escapeHtml(peerDialogueUrl(myUsername, m.user.username))}" target="_blank" rel="noopener noreferrer" title="查看你们双方的历史互动对话">双方对话 ↗</a>` : ""}
                    </div>
                </div>
            </div>`
            )
            .join("");
    }

    function drawShareCardCanvas(report, forceFallbackAvatar = false) {
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

        const rightTitleText = `𝕏  ${sc.reportTitle || "社区运营战报"}`;
        ctx.fillStyle = pal.textPrimary;
        ctx.textAlign = "right";
        ctx.font = "800 26px -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif";
        const rightTitleW = ctx.measureText(rightTitleText).width;
        ctx.fillText(rightTitleText, W - 60, 86);
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
        ctx.fillText(`@${p.username}  ·  ${sc.periodLabel}（${report.activity.windowRangeText}）`, 158, 126, W - 218);

        const personaText = `🏆 ${sc.personaTag} · 健康分 ${sc.activityHealthScore < 0 ? "--" : `${sc.activityHealthScore}/100`}`;
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
            ctx.fillText(c.label, x + 22, y + 40, textMaxW);

            ctx.fillStyle = c.color;
            ctx.font = "800 40px -apple-system, BlinkMacSystemFont, sans-serif";
            ctx.fillText(c.val, x + 22, y + 104, textMaxW);

            ctx.fillStyle = pal.textSub;
            ctx.font = "500 15px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(c.sub, x + 22, y + 140, textMaxW);
        });

        if (report.activity.coverageNote) {
            ctx.fillStyle = pal.textMuted;
            ctx.font = "500 13px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(report.activity.coverageNote, 60, 412, W - 120);
        }
        if (sc.metricsNote) {
            ctx.fillStyle = pal.textMuted;
            ctx.font = "500 12px -apple-system, 'PingFang SC', sans-serif";
            ctx.fillText(sc.metricsNote, 60, 426, W - 120);
        }

        ctx.fillStyle = pal.footerBg;
        ctx.strokeStyle = pal.border;
        roundRect(ctx, 60, 430, W - 120, 110, 20, true, true);

        ctx.fillStyle = pal.amber;
        ctx.font = "700 20px -apple-system, 'PingFang SC', sans-serif";
        ctx.fillText(`💎 ${sc.periodLabel}最佳双向奔赴推友伙伴`, 86, 470, W - 172);

        const buddies =
            sc.topBuddyHandles && sc.topBuddyHandles.length
                ? sc.topBuddyHandles.join("    ")
                : "期待与更多同频推友建立深度互动连接";
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
            const bannerEl = $("sideAlgoStatusBanner");
            if (bannerEl) {
                bannerEl.textContent = ah.healthTitle;
                bannerEl.className = `algo-status-banner level-${(ah.healthLevel || "GOOD").toLowerCase()}`;
            }
            if ($("sideAlgoSummaryText")) {
                $("sideAlgoSummaryText").textContent = ah.healthSummary;
            }
            if ($("sideOonRateVal")) {
                const sampleCount = ah.viewSampleCount ?? ah.evaluatedPostCount;
                $("sideOonRateVal").textContent = sampleCount > 0
                    ? `${ah.outOfNetworkRatePct}% (${ah.outOfNetworkPostCount}/${sampleCount}帖)`
                    : ah.evaluatedPostCount > 0 ? "浏览未取得" : "暂无主推";
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
                    ? `浏览低 ${ah.linkViewLossPct}%`
                    : ah.externalLinkPostCount > 0
                        ? `${ah.externalLinkPostCount} 帖含外链${canCompare ? "" : " · 浏览对比未取得"}`
                        : "无外链样本";
            }
            if ($("underTheHoodBtn") && ah.underTheHoodUrl) {
                $("underTheHoodBtn").href = safeHttpUrl(ah.underTheHoodUrl, "https://x.com/i/jf/under_the_hood");
            }
            if ($("mobileAlgoStatusText")) {
                $("mobileAlgoStatusText").textContent = `${ah.healthTitle} · ${ah.healthSummary}`;
            }
        }

        $("opsChecklistTitle").textContent = "本期行动";

        // 每条行动都可点击直达对应页面/筛选（go）或外部链接（href）
        const todo = [];
        const unrepliedPeers = Number(inter.unrepliedMyPostPeers || 0);
        const iOwePeers = Number(inter.inboundHeavyPeers || 0);
        if (unrepliedPeers > 0) {
            todo.push({ text: `${unrepliedPeers} 人评论了你还没回复`, go: "peerFilter:UNREPLIED_POST", tone: "amber" });
        }
        if (iOwePeers > 0) {
            todo.push({ text: `${iOwePeers} 人来评过你，你还没去评 TA 推文`, go: "peerFilter:I_OWE", tone: "amber" });
        }
        if (ah && ah.exposureCliffDrop) {
            todo.push({ text: "近期浏览明显下滑，可查看官方分发信息；原因待确认", href: safeHttpUrl(ah.underTheHoodUrl, "https://x.com/i/jf/under_the_hood") });
        }
        if (ah && ah.linkViewLossPct >= 20) {
            todo.push({ text: `外链样本浏览低 ${ah.linkViewLossPct}%，可对照内容与发布时间，原因待确认`, go: "tab:timing" });
        } else if (ah && ah.rapidFirePostCount > 0) {
            todo.push({ text: `${ah.rapidFirePostCount} 条推文发得太密（间隔不足 3 小时）`, go: "tab:timing" });
        }
        const effectiveUnfollow = computeEffectiveSuggestUnfollowCount(report);
        if (effectiveUnfollow > 0) {
            todo.push({ text: `${effectiveUnfollow} 个账号不回关也不互动，可考虑取关`, go: "followTab:NOT_FOLLOWING_BACK|followCat:SUGGEST_CLEAN" });
        }
        if (audit.fansNotFollowedBackCount > 0) {
            todo.push({ text: `${audit.fansNotFollowedBackCount} 位粉丝你还没回关`, go: "followTab:FAN_ONLY" });
        }
        if (ct.goldenSlots && ct.goldenSlots.length > 0) {
            todo.push({ text: `下一条推文建议在 ${ct.goldenSlots[0].label} 发`, go: "tab:timing" });
        }
        if (act.originalPosts === 0) {
            todo.push({ text: "这段时间还没发原创推文，发一条吧", href: "https://x.com/compose/post" });
        } else if (act.replyToPostRatio < 1.5) {
            todo.push({ text: "回复偏少，每天多评论 10 条同领域推文", go: "tab:activity" });
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
                              <div class="mini-peer-name">${escapeHtml(p.peer.displayName)}</div>
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

    /* ==================== 事件绑定与初始化 ==================== */
    function copyTextWithFallback(text) {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
            return navigator.clipboard.writeText(text).catch(() => legacyExecCopy(text));
        }
        return legacyExecCopy(text);
    }

    function legacyExecCopy(text) {
        return new Promise((resolve, reject) => {
            try {
                const ta = document.createElement("textarea");
                ta.value = text;
                ta.setAttribute("readonly", "readonly");
                ta.style.position = "fixed";
                ta.style.opacity = "0";
                ta.style.left = "-9999px";
                document.body.appendChild(ta);
                ta.select();
                const ok = document.execCommand("copy");
                document.body.removeChild(ta);
                ok ? resolve() : reject(new Error("copy failed"));
            } catch (err) {
                reject(err);
            }
        });
    }

    function bindEvents() {
        // 全局捕获头像图片加载失败，回退显示首字（彻底替代内联 onerror 字符串拼接）
        document.addEventListener(
            "error",
            (e) => {
                const t = e.target;
                if (t && t.tagName === "IMG" && t.hasAttribute("data-avatar-fallback")) {
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

        // 手机端与桌面端点按柱状图/热力图格子即可查看对应时段明细（再点取消）
        const bindTapReadout = (containerId, cellSelector, readoutId, getDefaultText) => {
            const box = document.getElementById(containerId);
            if (!box) return;
            const handleActivate = (e) => {
                const cell = e.target && e.target.closest ? e.target.closest(cellSelector) : null;
                if (!cell || !cell.dataset.tip) return;
                const wasSelected = cell.classList.contains("is-selected");
                box.querySelectorAll(cellSelector).forEach((el) => el.classList.remove("is-selected"));
                const out = $(readoutId);
                if (wasSelected) {
                    out.textContent = getDefaultText();
                    out.classList.remove("is-readout");
                } else {
                    cell.classList.add("is-selected");
                    out.textContent = cell.dataset.tip;
                    out.classList.add("is-readout");
                }
            };
            box.addEventListener("click", handleActivate);
            box.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    handleActivate(e);
                }
            });
        };
        bindTapReadout(
            "dailyTrendChart",
            ".day-col[data-tip]",
            "trendChartSubtitle",
            () => (state.report && state.report.activity ? state.report.activity.chartSubtitle : "点按或悬停柱体查看明细")
        );
        bindTapReadout(
            "heatmapGridRows",
            ".hm-cell[data-tip]",
            "timingPeriodDesc",
            timingPeriodDescription
        );

        $("gateLoginBtn").addEventListener("click", triggerXOAuthLogin);
        const errCloseBtn = document.getElementById("errorToastCloseBtn");
        if (errCloseBtn) {
            errCloseBtn.addEventListener("click", hideError);
        }

        const railHandleEl = document.getElementById("railHandle");
        if (railHandleEl) {
            railHandleEl.style.cursor = "pointer";
            railHandleEl.addEventListener("click", async () => {
                if (state.storedMode) return;
                if (!isLoggedIn()) return;
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
                        }
                    }
                } catch (_) {}
            });
        }

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
            state.isLoggingOut = true;
            cancelReportRequest();
            state.authStatusGeneration++;
            try {
                const resp = await fetch("/api/auth/x/logout", { method: "POST" });
                if (!resp.ok) throw new Error(`退出失败（HTTP ${resp.status}），请重试`);
                await fetchServerAndAuthStatus();
                if (isLoggedIn()) throw new Error("退出未生效，当前账号仍保持登录，请重试");
                resetUserScopedMemoryState();
                state.boundUserId = "";
                closeAuthorPromptModal();
                if ($("peerSearchInput")) $("peerSearchInput").value = "";
                if ($("followFilterInput")) $("followFilterInput").value = "";
                if ($("draftSimulatorInput")) $("draftSimulatorInput").value = "";
                if ($("draftSimulatorResult")) $("draftSimulatorResult").hidden = true;
                hideError();
                showToast("已退出 X 账号授权登录");
            } catch (err) {
                showError(err.message || "退出失败，请检查网络后重试");
            } finally {
                state.isLoggingOut = false;
                updateRefreshButtonState();
            }
        };
        $("railLogoutBtn").addEventListener("click", doLogout);
        if ($("topbarLogoutBtn")) {
            $("topbarLogoutBtn").addEventListener("click", doLogout);
        }

        // 全局无侵入键盘快捷键（Esc 关闭作者弹层/错误提示/清空搜索框；1-5 切模块，M/W/D 秒切 30天/7天/48h）
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                const authorModal = document.getElementById("authorPromptModal");
                if (authorModal && !authorModal.hidden) {
                    closeAuthorPromptModal();
                    return;
                }
                const errEl = document.getElementById("errorToast");
                if (errEl && !errEl.hidden) {
                    hideError();
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
                "5": "circle"
            };
            if (tabKeyMap[e.key]) {
                switchTab(tabKeyMap[e.key]);
                return;
            }
            const up = String(e.key || "").toUpperCase();
            if (up === "M" || up === "W" || up === "D") {
                const pCode = up === "M" ? "MONTH" : up === "W" ? "WEEK" : "HOURS_48";
                const pMeta = PERIOD_META[pCode];
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
                window.location.href = $("storedReportLink").href;
                return;
            }
            // 有效缓存期内不重新拉取，完全到期后交由服务端判断更新
            if (Date.now() < state.cacheExpiresEpochMs) {
                return;
            }
            loadSelfReport(true);
        });

        // 月 / 周 / 日 三维度一键切换
        document.querySelectorAll(".seg-btn[data-period]").forEach((btn) => {
            btn.addEventListener("click", () => {
                const pMeta = normalizePeriod(btn.dataset.period, btn.dataset.days);
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
            state.peerRelFilter = filterCode || "ALL";
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
            for (const part of String(el.dataset.go || "").split("|")) {
                const [kind, val] = part.split(":");
                if (kind === "tab" && val) {
                    switchTab(val);
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
                } else if (kind === "clearPeerSearch") {
                    $("peerSearchInput").value = "";
                    state.peerSearchQuery = "";
                    if (state.report) renderPeerList(state.report);
                } else if (kind === "clearFollowSearch") {
                    $("followFilterInput").value = "";
                    state.followSearchQuery = "";
                    if (state.report) renderFollowAuditPanel(state.report);
                }
            }
        });

        // 模块 3：未回关筛选与导出（150ms 输入防抖 + 切换分类时重置分页）
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

        $("exportFollowCsvBtn").addEventListener("click", exportFollowListCsv);
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
        const authResult = urlParams.get("auth");
        const authErr = urlParams.get("auth_error");

        if (urlPeriod || urlDays) {
            const pMeta = normalizePeriod(urlPeriod, urlDays);
            state.period = pMeta.code;
            state.days = pMeta.days;
        }
        syncPeriodButtons();
        bindEvents();

        try {
            await fetchServerAndAuthStatus();
        } catch (_) {
            showError("无法连接后端服务，请稍后刷新重试");
            return;
        }

        if (authResult === "ok") {
            showToast("X 授权登录成功！已进入您本人的专属运营工作台");
            cleanAuthQueryParams();
        } else if (authErr) {
            showError(authErr);
            cleanAuthQueryParams();
        }

        if (isLoggedIn()) {
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
        u.searchParams.delete("user");
        window.history.replaceState({}, "", u.toString());
    }

    document.addEventListener("DOMContentLoaded", init);
})();
