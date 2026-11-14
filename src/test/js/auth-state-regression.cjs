const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
    const a = source.indexOf(start);
    const b = source.indexOf(end, a);
    assert.ok(a >= 0 && b > a, `Cannot locate production section: ${start}`);
    return source.slice(a, b);
}
const resetCode = section('    function cancelReportRequest()', '    // 已精简移除的元素');
const loadCode = section('    async function loadSelfReport(', '    // 首次加载骨架屏');
const logoutCode = section('        const doLogout = async () => {', '        $("railLogoutBtn").addEventListener')
    .replace('const doLogout =', 'globalThis.doLogout =');
const timingCode = section('    function computeNextGoldenWindowHint(', '    function timingPeriodDescription(')
    + section('    function timingPeriodDescription(', '    function renderTimingPanel(');
const noop = () => {};
function context() {
    const state = {authStatus: {loggedIn: true, user: {username: 'alice'}}, period: 'HOURS_48', days: 2,
        report: null, bundle: null, reportRequestGeneration: 0, authStatusGeneration: 0};
    const element = {classList: {remove: noop}, hidden: false};
    const ctx = {state, LIST_PAGE_SIZE: 50, PERIOD_META: {HOURS_48: {badgePrefix: '48h'}}, Date, Number,
        Boolean, Set, AbortController, Intl,
        $: () => element, setTimeout: () => 1, clearTimeout: noop, setInterval: () => 1, clearInterval: noop,
        isLoggedIn: () => Boolean(state.authStatus?.loggedIn), syncAuthGateAndWorkspaceUI: noop,
        updateRefreshButtonState: noop, hideError: noop, showLoadingSkeleton: noop,
        showError: msg => {ctx.error = msg;}, closeAuthorPromptModal: noop,
        safeParseJson: async r => r.body, fmtMonthDay: () => '', startCacheCooldownTicker: noop,
        applyLoadedPeriodReport: r => {state.report = r;}, fetchServerAndAuthStatus: async () => {},
        showToast: msg => {ctx.toast = msg;}};
    vm.createContext(ctx);
    vm.runInContext(resetCode + loadCode + logoutCode + timingCode, ctx);
    return ctx;
}
function response(username = 'alice') {
    const report = {profile: {username}, cacheRemainingSeconds: 100};
    return {status: 200, ok: true, body: {month: report, week: report, day: report,
        coveredWindowDays: 30, hasMoreHistoryForTarget: false}};
}
(async () => {
    const late = context();
    let release, signal;
    late.fetch = (_, options) => {signal = options.signal; return new Promise(r => {release = r;});};
    const pending = late.loadSelfReport();
    late.resetUserScopedMemoryState();
    late.state.authStatus = {loggedIn: false};
    assert.equal(signal.aborted, true, 'reset must cancel the actual fetch');
    release(response());
    await pending;
    assert.equal(late.state.report, null, 'late response must not repopulate logged-out state');
    assert.equal(late.state.bundle, null);
    assert.equal(late.error, undefined, 'cancelled requests must not show timeout failures');

    const changed = context();
    changed.fetch = () => new Promise(r => {release = r;});
    const previous = changed.loadSelfReport();
    changed.resetUserScopedMemoryState();
    changed.state.authStatus = {loggedIn: true, user: {username: 'bob'}};
    changed.state.isFetching = true; // a new identity has begun loading
    release(response());
    await previous;
    assert.equal(changed.state.report, null);
    assert.equal(changed.state.isFetching, true, 'old finally must not clear newer loading state');

    const wrongOwner = context();
    wrongOwner.fetch = async () => response('bob');
    await wrongOwner.loadSelfReport();
    assert.equal(wrongOwner.state.report, null);
    assert.match(wrongOwner.error, /账号不匹配/);

    const failed = context();
    failed.fetch = async () => ({status: 500, ok: false});
    await failed.doLogout();
    assert.equal(failed.toast, undefined);
    assert.equal(failed.state.authStatus.loggedIn, true);
    assert.match(failed.error, /HTTP 500/);

    const stillLoggedIn = context();
    stillLoggedIn.fetch = async () => ({status: 200, ok: true});
    await stillLoggedIn.doLogout();
    assert.equal(stillLoggedIn.toast, undefined);
    assert.match(stillLoggedIn.error, /仍保持登录/);

    const network = context();
    network.fetch = async () => {throw new Error('offline');};
    await network.doLogout();
    assert.equal(network.toast, undefined);
    assert.match(network.error, /offline/);

    const success = context();
    success.fetch = async () => ({status: 200, ok: true});
    success.fetchServerAndAuthStatus = async () => {success.state.authStatus = {loggedIn: false};};
    await success.doLogout();
    assert.match(success.toast, /已退出/);
    assert.equal(success.state.report, null);

    const timing = context();
    const RealDate = Date;
    timing.Date = class extends RealDate {constructor(...args) {super(...(args.length ? args : ['2026-11-09T01:00:00Z']));}};
    timing.state.report = {contentTiming: {timezone: 'America/New_York'}};
    assert.match(timing.computeNextGoldenWindowHint([{label: '周日 18:00–21:00'}]), /当前正处于黄金档/);
    assert.match(timing.timingPeriodDescription(), /America\/New_York/);
    timing.state.report = {contentTiming: {}};
    assert.match(timing.computeNextGoldenWindowHint([{label: '周一 09:00–12:00'}]), /当前正处于黄金档/);
    console.log('auth-state-regression: passed');
})().catch(err => {console.error(err); process.exitCode = 1;});
