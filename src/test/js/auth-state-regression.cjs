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
const identityCode = section('    function stableUserId(', '    function getSelfStorageKey(');
const profileCacheCode = section('    function getProfileHeaderCacheKey(', '    function saveProfileHeaderCache(')
    + section('    function clearProfileHeaderCache(', '    function canPersistMediaToCacheStorage(');
const loadCode = section('    async function loadSelfReport(', '    // 首次加载骨架屏');
const logoutCode = section('        const doLogout = async () => {', '        $("railLogoutBtn").addEventListener')
    .replace('const doLogout =', 'globalThis.doLogout =');
const timingCode = section('    function computeNextGoldenWindowHint(', '    function timingPeriodDescription(')
    + section('    function timingPeriodDescription(', '    function renderTimingPanel(');
const dailyStatusCode = section('    function renderDailyCacheStatus(', '    function renderProfileHeader(');
const noop = () => {};
function context() {
    const state = {authStatus: {loggedIn: true, user: {id: '1001', username: 'alice'}}, period: 'HOURS_48', days: 2,
        report: null, bundle: null, reportRequestGeneration: 0, authStatusGeneration: 0};
    const element = {classList: {remove: noop}, hidden: false};
    const removedStorageKeys = [];
    const ctx = {state, LIST_PAGE_SIZE: 50, PERIOD_META: {HOURS_48: {badgePrefix: '48h'}}, Date, Number,
        Boolean, Set, AbortController, Intl,
        removedStorageKeys, localStorage: {removeItem: key => removedStorageKeys.push(key)},
        $: () => element, setTimeout: () => 1, clearTimeout: noop, setInterval: () => 1, clearInterval: noop,
        isLoggedIn: () => Boolean(state.authStatus?.loggedIn), syncAuthGateAndWorkspaceUI: noop,
        updateRefreshButtonState: noop, hideError: noop, showLoadingSkeleton: noop,
        showError: msg => {ctx.error = msg;}, closeAuthorPromptModal: noop,
        safeParseJson: async r => r.body, fmtMonthDay: () => '', startCacheCooldownTicker: noop,
        applyLoadedPeriodReport: r => {state.report = r;}, fetchServerAndAuthStatus: async () => {},
        showToast: msg => {ctx.toast = msg;}};
    vm.createContext(ctx);
    vm.runInContext(identityCode + profileCacheCode + resetCode + loadCode + logoutCode + timingCode + dailyStatusCode, ctx);
    return ctx;
}
function response(username = 'alice', id = '1001') {
    const report = {profile: {id, username}, cacheRemainingSeconds: 100};
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
    changed.state.authStatus = {loggedIn: true, user: {id: '1002', username: 'bob'}};
    changed.state.isFetching = true; // a new identity has begun loading
    release(response());
    await previous;
    assert.equal(changed.state.report, null);
    assert.equal(changed.state.isFetching, true, 'old finally must not clear newer loading state');

    const wrongOwner = context();
    wrongOwner.fetch = async () => response('alice', '1002');
    await wrongOwner.loadSelfReport();
    assert.equal(wrongOwner.state.report, null);
    assert.match(wrongOwner.error, /账号不匹配/);

    const ownReport = context();
    ownReport.fetch = async () => response();
    await ownReport.loadSelfReport();
    assert.equal(ownReport.state.report.profile.id, '1001');
    assert.equal(ownReport.error, undefined);

    const missingIdentity = context();
    delete missingIdentity.state.authStatus.user.id;
    missingIdentity.fetch = async () => {assert.fail('missing identity must not issue a request');};
    await missingIdentity.loadSelfReport();
    assert.equal(missingIdentity.state.report, null);
    assert.match(missingIdentity.error, /稳定账号 ID/);

    const failed = context();
    failed.fetch = async () => ({status: 500, ok: false});
    await failed.doLogout();
    assert.equal(failed.toast, undefined);
    assert.equal(failed.state.authStatus.loggedIn, true);
    assert.match(failed.error, /HTTP 500/);
    assert.deepEqual(failed.removedStorageKeys, [], 'failed logout must not remove the profile cache');

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
    assert.deepEqual(success.removedStorageKeys, ['xops_profile_meta_user_1001', 'xops_last_uid'],
        'logout must clear only the stable-ID profile cache and last-user marker');

    const timing = context();
    const RealDate = Date;
    timing.Date = class extends RealDate {constructor(...args) {super(...(args.length ? args : ['2026-11-09T01:00:00Z']));}};
    timing.state.report = {contentTiming: {timezone: 'America/New_York'}};
    assert.match(timing.computeNextGoldenWindowHint([{label: '周日 18:00–21:00'}]), /当前正处于黄金档/);
    assert.match(timing.timingPeriodDescription(), /America\/New_York/);
    timing.state.report = {contentTiming: {}};
    assert.match(timing.computeNextGoldenWindowHint([{label: '周一 09:00–12:00'}]), /当前正处于黄金档/);
    const daily = context();
    daily.state.dailyCacheStatus = {snapshotAt: '2026-11-14T18:53:05Z',
        latestMetricFetchedAt: '2026-11-14T19:03:05Z', recentPosts: 1, recentInbound: 0, pendingMetricPosts: 0};
    daily.renderDailyCacheStatus({});
    assert.match(daily.$('dailyCacheStatusHint').textContent, /指标最近采集.*近24h.*分批读数/);
    assert.match(daily.$('dailyCacheStatusHint').textContent, /03:03:05/);
    delete daily.state.dailyCacheStatus.latestMetricFetchedAt;
    daily.renderDailyCacheStatus({});
    assert.match(daily.$('dailyCacheStatusHint').textContent, /指标最近采集时间未确认/);
    console.log('auth-state-regression: passed');
})().catch(err => {console.error(err); process.exitCode = 1;});
