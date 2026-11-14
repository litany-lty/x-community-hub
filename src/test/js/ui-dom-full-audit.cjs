const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const rootDir = path.join(__dirname, "../../..");
const htmlSrc = fs.readFileSync(path.join(rootDir, "src/main/resources/static/index.html"), "utf8");
const jsSrc = fs.readFileSync(path.join(rootDir, "src/main/resources/static/app.js"), "utf8");

function slice(startMarker, endMarker) {
    const start = jsSrc.indexOf(startMarker);
    const end = jsSrc.indexOf(endMarker, start);
    assert.ok(start >= 0 && end > start, `missing slice markers: ${startMarker} -> ${endMarker}`);
    return jsSrc.slice(start, end);
}

// 1. 静态 HTML + JS DOM ID 全量一致性与自解释布局校验
(function testStaticDomAndLayoutIntegrity() {
    const htmlIds = new Set([...htmlSrc.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));
    assert.ok(htmlIds.size >= 180, `expected >= 180 static IDs in index.html, got ${htmlIds.size}`);

    // 动态注入后按 ID 绑定事件的合法元素白名单
    const dynamicIds = new Set([
        "loadMorePeersBtn",
        "loadMoreFollowBtn",
        "clearDraftSimBtn",
        "sendDraftToScheduleBtn"
    ]);

    const referencedIds = new Set([
        ...[...jsSrc.matchAll(/\$\("([^"]+)"\)/g)].map((m) => m[1]),
        ...[...jsSrc.matchAll(/getElementById\("([^"]+)"\)/g)].map((m) => m[1])
    ]);
    const missingStaticIds = [...referencedIds].filter((id) => !htmlIds.has(id) && !dynamicIds.has(id));
    assert.deepEqual(
        missingStaticIds,
        [],
        `app.js references missing DOM IDs not present in index.html: ${missingStaticIds.join(", ")}`
    );

    // 核心回归：移除 8小时，仅保留 24小时 / 48小时 / 7天 / 30天 四个时间维度按钮，且默认选中 24小时
    const segButtons = [...htmlSrc.matchAll(/<button[^>]*class="seg-btn[^"]*"[^>]*>/g)].map((m) => m[0]);
    assert.equal(segButtons.length, 4, "topbar must render exactly 4 period segment buttons");
    assert.equal(
        segButtons.some((tag) => tag.includes('data-period="HOURS_8"')),
        false,
        "HOURS_8 button must be completely removed from index.html"
    );
    assert.ok(
        segButtons[0].includes('data-period="HOURS_24"') && /\bactive\b/.test(segButtons[0]),
        "HOURS_24 button must be first and active by default"
    );

    // 样式自解释原则校验：所有静态无 ID 的 .section-desc 冗余说明段落、.sidebar-desc、#topbarPeriodBadge 均已移除
    const sectionDescTags = [...htmlSrc.matchAll(/<p[^>]*class="section-desc"[^>]*>/g)].map((m) => m[0]);
    const staticUnboundDescs = sectionDescTags.filter((tag) => !/\bid="/.test(tag));
    assert.deepEqual(staticUnboundDescs, [], "index.html should not contain static unbound .section-desc explanation blocks");
    assert.equal(/class="sidebar-desc"/.test(htmlSrc), false, "index.html should not contain redundant .sidebar-desc blocks");
    assert.equal(htmlIds.has("topbarPeriodBadge"), false, "redundant topbarPeriodBadge should be removed");
    assert.equal(htmlIds.has("scheduleQueueSummaryDesc"), false, "redundant scheduleQueueSummaryDesc should be removed");
})();

// 2. normalizePeriod / isPeriodUnlocked / syncPeriodButtons / 按钮点击与快捷键完整状态机校验
(function testPeriodSwitchingAndLockStateMachine() {
    function makeClassList(initial = []) {
        const set = new Set(initial);
        return {
            add: (...names) => names.forEach((n) => set.add(n)),
            remove: (...names) => names.forEach((n) => set.delete(n)),
            toggle: (name, force) => {
                const next = force === undefined ? !set.has(name) : Boolean(force);
                if (next) set.add(name);
                else set.delete(name);
                return next;
            },
            contains: (name) => set.has(name)
        };
    }

    function makeSegBtn(period, label, days) {
        const attrs = {};
        const listeners = {};
        const dataset = { period, label };
        if (days !== undefined) dataset.days = String(days);
        return {
            dataset,
            textContent: label,
            title: label,
            hidden: false,
            classList: makeClassList(period === "HOURS_24" ? ["seg-btn", "active"] : ["seg-btn"]),
            setAttribute(k, v) { attrs[k] = String(v); },
            getAttribute(k) { return attrs[k]; },
            addEventListener(type, fn) { (listeners[type] ||= []).push(fn); },
            click() { (listeners.click || []).forEach((fn) => fn()); }
        };
    }

    const segBtns = [
        makeSegBtn("HOURS_24", "24小时", 1),
        makeSegBtn("HOURS_48", "48小时", 2),
        makeSegBtn("WEEK", "7天", 7),
        makeSegBtn("MONTH", "30天", 30)
    ];

    const elementsById = {
        periodUnlockHelpBtn: { hidden: false },
        authorPromptModal: { hidden: true },
        unlockGuideModal: { hidden: true },
        confirmResendModal: { hidden: true }
    };

    const bodyClassList = makeClassList();
    const toasts = [];
    const unlockedModalCalls = [];
    const loadReportCalls = [];
    const docListeners = {};

    const wrappedCode = `
${slice("    const PERIOD_META = {", "    const AUTHOR_REPO_URL =")}
${slice("    const LIST_PAGE_SIZE = 50;", "    function cancelReportRequest()")}
${slice("    function normalizePeriod(rawPeriod, rawDays) {", "    function escapeHtml(str) {")}
${slice("    function syncBodyModalLock() {", "    function openAuthorPromptModal(data) {")}
function handlePeriodShortcut(e) {
${slice("            const periodOrder = [\"HOURS_24\", \"HOURS_48\", \"WEEK\", \"MONTH\"];", "        });\n\n        $(\"forceRefreshBtn\")")}
}
${slice("        // 24h / 48h / 7天 / 30天 四维度切换与锁定引导", "        // 模块 2：推友筛选与排序")}
this.__exports = { state, PERIOD_META, normalizePeriod, isPeriodUnlocked, syncPeriodButtons, syncBodyModalLock, handlePeriodShortcut };
`;
    const ctx = {
        state: null,
        document: {
            body: { classList: bodyClassList },
            querySelectorAll(sel) {
                if (sel === ".seg-btn[data-period]") return segBtns;
                return [];
            },
            getElementById(id) {
                return elementsById[id] || null;
            },
            addEventListener(type, fn) {
                (docListeners[type] ||= []).push(fn);
            }
        },
        showToast(msg) { toasts.push(msg); },
        openUnlockGuideModal(codeArg) { unlockedModalCalls.push(codeArg); },
        loadSelfReport(force) {
            const st = ctx.__exports.state;
            loadReportCalls.push({ period: st.period, days: st.days, force });
        }
    };
    vm.runInNewContext(wrappedCode, ctx);
    const { state, PERIOD_META, normalizePeriod, syncPeriodButtons, syncBodyModalLock, handlePeriodShortcut } = ctx.__exports;

    // 2a. 默认选中 HOURS_24，旧版 HOURS_8 参数平滑收敛至 HOURS_24
    assert.equal(state.period, "HOURS_24");
    assert.equal("HOURS_8" in PERIOD_META, false);
    assert.equal(normalizePeriod("", undefined).code, "HOURS_24");
    assert.equal(normalizePeriod("HOURS_8", 1).code, "HOURS_24");
    assert.equal(normalizePeriod("8h", undefined).code, "HOURS_24");
    assert.equal(normalizePeriod("HOURS_24", 1).code, "HOURS_24");
    assert.equal(normalizePeriod("HOURS_48", 2).code, "HOURS_48");
    assert.equal(normalizePeriod("WEEK", 7).code, "WEEK");
    assert.equal(normalizePeriod("MONTH", 30).code, "MONTH");

    // 2b. 首屏尚未拿到 accessPolicy 且正在请求时：不提前给 48小时 贴假锁，点击给温和同步提示
    state.isFetching = true;
    state.accessPolicy = null;
    syncPeriodButtons();
    assert.equal(segBtns[0].textContent, "24小时");
    assert.equal(segBtns[1].textContent, "48小时");
    assert.equal(segBtns[1].classList.contains("is-locked"), false);

    segBtns[1].click();
    assert.equal(unlockedModalCalls.length, 0);
    assert.ok(toasts.at(-1).includes("正在同步账号权限"));

    // 2c. 首日默认解锁 24小时，48h/7d/30d 未累计满时锁定；累计满 2 天后可在 24小时 与 48小时 间自由切换
    state.isFetching = false;
    state.accessPolicy = {
        unlockedPeriods: ["HOURS_24", "HOURS_48"],
        lockedReasons: {
            WEEK: "累计满 7 天物理存储数据后解锁",
            MONTH: "累计满 30 天物理存储数据后解锁"
        }
    };
    syncPeriodButtons();
    assert.equal(elementsById.periodUnlockHelpBtn.hidden, false);
    assert.equal(segBtns[0].textContent, "24小时");
    assert.equal(segBtns[1].textContent, "48小时");
    assert.equal(segBtns[2].textContent, "🔒 7天");
    assert.equal(segBtns[2].classList.contains("is-locked"), true);

    segBtns[1].click();
    assert.equal(state.period, "HOURS_48");
    assert.equal(segBtns[1].classList.contains("active"), true);
    assert.equal(segBtns[0].classList.contains("active"), false);

    segBtns[0].click();
    assert.equal(state.period, "HOURS_24");
    assert.equal(segBtns[0].classList.contains("active"), true);
    assert.equal(segBtns[1].classList.contains("active"), false);
    assert.equal(loadReportCalls.at(-1).period, "HOURS_24");

    // 点击未解锁的 7天：弹出解锁说明弹窗
    segBtns[2].click();
    assert.equal(unlockedModalCalls.at(-1), "WEEK");
    assert.equal(state.period, "HOURS_24");

    // 快捷键 D / T 切换测试
    handlePeriodShortcut({ key: "d" });
    assert.equal(state.period, "HOURS_48");
    handlePeriodShortcut({ key: "t" });
    assert.equal(state.period, "HOURS_24");

    // 2d. 全部 4 个时间窗已解锁时，顶栏「解锁」按钮自动隐藏（避免无意义辅助按钮）
    state.accessPolicy = {
        unlockedPeriods: ["HOURS_24", "HOURS_48", "WEEK", "MONTH"],
        lockedReasons: {}
    };
    syncPeriodButtons();
    assert.equal(elementsById.periodUnlockHelpBtn.hidden, true);
    assert.ok(segBtns.every((b) => !b.classList.contains("is-locked") && !b.textContent.startsWith("🔒")));

    // 2e. 弹窗滚动锁同步测试
    assert.equal(bodyClassList.contains("modal-open"), false);
    elementsById.unlockGuideModal.hidden = false;
    syncBodyModalLock();
    assert.equal(bodyClassList.contains("modal-open"), true);
    elementsById.unlockGuideModal.hidden = true;
    syncBodyModalLock();
    assert.equal(bodyClassList.contains("modal-open"), false);
})();

console.log("ui-dom-full-audit: passed");
