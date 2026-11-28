const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
    const a = source.indexOf(start), b = source.indexOf(end, a);
    assert.ok(a >= 0 && b > a, `Missing production section: ${start}`);
    return source.slice(a, b);
}
const noop = () => {};
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function response(body = {}, status = 200, headers = {}) {
    return { status, ok: status >= 200 && status < 300, body, headers: { get: key => headers[key] || null } };
}
function fixture() {
    const nodes = new Map(), timers = new Map(), fields = [];
    let timerId = 0, now = Date.now();
    function node(id) {
        if (nodes.has(id)) return nodes.get(id);
        const attrs = new Map(), classes = new Set();
        const el = { id, hidden: true, disabled: false, textContent: '', innerHTML: '', value: '', dataset: {}, scrolls: [],
            setAttribute: (k, v) => attrs.set(k, String(v)), getAttribute: k => attrs.get(k) ?? null,
            removeAttribute: k => attrs.delete(k), focus: () => { ctx.document.activeElement = el; },
            scrollIntoView: v => el.scrolls.push(v.block),
            classList: { add: k => classes.add(k), remove: k => classes.delete(k),
                toggle: (k, on) => on ? classes.add(k) : classes.delete(k) },
            querySelector: selector => selector === 'span' ? node(id + '-label') : find(selector),
            querySelectorAll: selector => selector === '[data-composer-invalid]'
                ? fields.filter(f => f.getAttribute('data-composer-invalid')) : fields };
        nodes.set(id, el); return el;
    }
    function find(selector) {
        if (selector.includes('data-resched-error')) return node('reschedule-error');
        if (selector.includes('data-resched-input')) return node('reschedule-input');
        if (selector.includes('save-reschedule')) return node('reschedule-save');
        const match = /\[data-draft-(time|textarea)="(\d+)"\]/.exec(selector);
        return match ? node(`draft-${match[1]}-${match[2]}`) : null;
    }
    const ctx = { AbortController, URL, URLSearchParams, Intl, console,
        Date: class extends Date { static now() { return now; } },
        setTimeout: (fn, delay) => { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
        clearTimeout: id => timers.delete(id), clearInterval: noop,
        document: { hidden: false, activeElement: null, getElementById: node, querySelector: find,
            querySelectorAll: () => [], body: { classList: { toggle: noop } } },
        window: { location: { href: 'http://localhost/', search: '' } }, CSS: { escape: String }, $: node,
        MIN_SCHEDULE_INTERVAL_MS: 3600000, MAX_BATCH_SCHEDULE_ITEMS: 20,
        FormData: class { constructor() { this.values = []; } append(...value) { this.values.push(value); } },
        safeParseJson: async resp => resp.body,
        primeInitialMediaCache: async () => {},
        syncAuthGateAndWorkspaceUI: () => { ctx.authSyncs++; ctx.syncAuthConnectionUI(); },
        resetUserScopedMemoryState: () => { ctx.state.userScopeGeneration++; ctx.state.authStatusGeneration++; },
        isLoggedIn: () => Boolean(ctx.state.authStatus?.loggedIn),
        hasScheduleAccessDecision: () => false, isScheduleEligible: () => true, getScheduleLockedReason: () => 'locked',
        syncScheduleDraftConflictUI: noop, updateScheduleBadgesAndBanner: noop, renderScheduleQuickSlots: noop,
        refreshSchedulePollingState: noop, renderScheduledTweetsList: () => ctx.renders++, ensureScheduleDraftInitialized: noop,
        parseDraftItemEpochMs: item => Number(item.scheduledAtValue), getQueuedScheduledEpochsMs: () => [],
        clearScheduleComposerDraft: () => { ctx.clears++; ctx.state.scheduleDraftItems = []; },
        rotateComposerRequestId: () => { ctx.state.composerClientRequestId = 'generated'; },
        showError: text => ctx.errors.push(text), showToast: text => ctx.toasts.push(text),
        hideError: () => { node('errorToast').hidden = true; }, loadSelfReport: async () => { ctx.reportLoads++; },
        restoreScheduleDraftAfterStepUpAuth: async () => { ctx.restores++; }, cleanAuthQueryParams: noop,
        switchTab: (tab, options) => { ctx.state.activeTab = tab; ctx.navigations.push({ tab, options }); },
        cancelReportRequest: noop, clearProfileHeaderCache: noop,
        clearPersistedScheduleDraft: async id => ctx.clearedOwners.push(id),
        syncPeriodButtons: noop, closeAuthorPromptModal: noop, closeUnlockGuideModal: noop, closeConfirmResendModal: noop,
        updateRefreshButtonState: noop, renderAlgoConfigDrawer: noop, evaluatePrePostDraft: noop,
        errors: [], toasts: [], authSyncs: 0, renders: 0, clears: 0, reportLoads: 0, restores: 0, navigations: [], clearedOwners: [],
        nodes, timers, fields, advance: ms => { now += ms; },
        expire: delay => { const matches = [...timers].filter(([, timer]) => timer.delay === delay);
            for (const [id, timer] of matches) { timers.delete(id); timer.fn(); } } };
    vm.createContext(ctx);
    vm.runInContext(section('    const LIST_PAGE_SIZE =', '    function cancelReportRequest()')
        + section('    function stableUserId(', '    function getSelfStorageKey(')
        + section('    function isOwnerAllPermissions()', '    function hasPeriodAccessDecision()')
        + section('    async function requestJsonWithin(', '    function syncAuthGateAndWorkspaceUI(')
        + section('    async function triggerXOAuthLogin(', '    function startCacheCooldownTicker(')
        + section('    function syncScheduleAuthBanner(', '    function formatGapDurationShort(')
        + section('    function setPublishAuthError(', '    async function submitConfirmResend(')
        + section('    const MIN_SCHEDULE_INTERVAL_MS =', '    function computeNextAvailableScheduleMs(')
        + section('    function validateBatchScheduleEpochs(', '    function alignBatchScheduleEpochs(')
        + section('    function setRescheduleError(', '    function renderScheduledTweetsList(')
        + section('    async function submitScheduledJobAction(', '    /* ==================== 事件绑定与初始化')
        + section('        const doLogout = async () => {', '        $("railLogoutBtn").addEventListener(')
        + '\nthis.state = state; this.doLogout = doLogout;', ctx);
    ctx.state.authStatus = { loggedIn: true, user: { id: '101', username: 'alice' },
        oauthConfigured: true, writeAuthorized: true, mediaWriteAuthorized: true, offlineRefreshable: true };
    ctx.state.authConnectionState = 'ready'; ctx.state.activeTab = 'schedule';
    ctx.state.scheduleDraftPersistenceReady = true; ctx.state.scheduleDraftRestoring = false;
    ctx.state.composerClientRequestId = 'draft-a';
    ctx.state.scheduleDraftItems = [{ text: 'original draft', scheduledAtValue: String(now + 7200000), files: [], timeTouched: true }];
    const textInput = node('draft-textarea-0'), timeInput = node('draft-time-0');
    textInput.value = 'original draft'; timeInput.value = String(now + 7200000);
    fields.push(textInput, timeInput);
    return ctx;
}
async function testAuthReadinessAndTimeout() {
    const ctx = fixture();
    ctx.state.authStatus = null; ctx.state.authConnectionState = 'idle';
    let calls = 0;
    ctx.fetch = async () => { calls++; throw new Error('should not fetch from a connecting login click'); };
    await ctx.triggerXOAuthLogin();
    assert.equal(calls, 0); assert.deepEqual(ctx.errors, []);
    const status = deferred();
    ctx.fetch = url => { calls++; return url === '/api/meta'
        ? Promise.resolve(response({ oauthConfigured: true })) : status.promise; };
    const first = ctx.fetchServerAndAuthStatus(), second = ctx.fetchServerAndAuthStatus();
    assert.equal(calls, 2, 'two callers share one meta/status pair');
    assert.equal(ctx.$('gateLoginBtn').disabled, true);
    assert.equal(ctx.$('gateLoginBtn-label').textContent, '正在连接…');
    status.resolve(response({ loggedIn: false, oauthConfigured: true }));
    await Promise.all([first, second]);
    assert.equal(ctx.state.authConnectionState, 'ready');
    assert.equal(ctx.$('gateLoginBtn').disabled, false);
    await ctx.triggerXOAuthLogin();
    assert.equal(ctx.window.location.href, '/api/auth/x/login');

    const slow = fixture(); slow.state.authStatus = null;
    slow.fetch = () => new Promise(() => {});
    const pending = slow.fetchServerAndAuthStatus();
    slow.expire(15000);
    await assert.rejects(pending, { name: 'RequestTimeoutError' });
    assert.equal(slow.state.authConnectionState, 'error');
    assert.equal(slow.$('gateLoginBtn-label').textContent, '重试连接');
    assert.equal(slow.$('authGateFeedback').hidden, false);
    assert.equal(slow.timers.size, 0);

    for (const [query, expectedTab] of [['?tab=interactions', 'interactions'], ['?tab=follow-audit', 'follow-audit'], ['', 'activity']]) {
        const retry = fixture();
        retry.state.authStatus = null; retry.state.authConnectionState = 'error'; retry.state.activeTab = 'activity';
        retry.window.location.search = query;
        retry.fetch = async url => response(url === '/api/meta' ? { oauthConfigured: true }
            : url === '/api/auth/x/status' ? { loggedIn: true, oauthConfigured: true, user: { id: '101' } }
            : { items: [] });
        await retry.triggerXOAuthLogin();
        assert.equal(retry.state.activeTab, expectedTab, 'login retry restores the requested tab');
        assert.equal(retry.navigations.length, 1, 'login retry initializes tab history exactly once');
        assert.equal(retry.navigations[0].options.historyMode, 'replace');
        assert.equal(retry.restores, 1, 'login retry restores saved drafts once');
        assert.equal(retry.reportLoads, 1);
    }
}
async function testQueueWaitingFailureAndCooldown() {
    const ctx = fixture(), pendingRead = deferred(); let reads = 0;
    ctx.$('scheduledTweetsList').innerHTML = 'unchanged editor';
    ctx.fetch = () => { reads++; return pendingRead.promise; };
    const pending = ctx.loadScheduledTweets(true);
    assert.equal(ctx.$('refreshScheduleListBtn').textContent, '刷新中…');
    assert.equal(ctx.$('scheduleQueueFeedback').hidden, false);
    await ctx.loadScheduledTweets(false);
    assert.equal(reads, 1, 'manual refresh does not duplicate an in-flight read');
    pendingRead.resolve(response({ message: '服务繁忙' }, 503, { 'Retry-After': '2' }));
    await pending;
    assert.equal(ctx.$('scheduledTweetsList').innerHTML, 'unchanged editor');
    assert.match(ctx.$('scheduleQueueFeedback').textContent, /服务繁忙/);
    assert.equal(ctx.$('refreshScheduleListBtn').disabled, true);
    await ctx.loadScheduledTweets(false, true); assert.equal(reads, 1);
    ctx.advance(3000); ctx.expire(1000);
    assert.equal(ctx.$('refreshScheduleListBtn').disabled, false);
    assert.equal(ctx.$('refreshScheduleListBtn').textContent, '重试');
    ctx.fetch = async () => { reads++; return response({ items: [] }); };
    await ctx.loadScheduledTweets(false);
    assert.equal(ctx.state.scheduleHasLoaded, true);
    assert.equal(ctx.$('scheduleQueueFeedback').hidden, true);
    assert.deepEqual(ctx.errors, []);
}
async function testComposerFailureStaysLocal() {
    const ctx = fixture();
    ctx.fetch = async () => response({ message: '第 1 条发布时间与其他任务冲突' }, 409);
    await ctx.submitCreateScheduledTweet();
    assert.equal(ctx.clears, 0);
    assert.equal(ctx.state.scheduleDraftItems[0].text, 'original draft');
    assert.match(ctx.$('scheduleComposeError').textContent, /时间/);
    assert.equal(ctx.$('scheduleComposeError').hidden, false);
    assert.equal(ctx.$('draft-time-0').getAttribute('aria-invalid'), 'true');
    assert.equal(ctx.$('createScheduleBtn').disabled, false);
    assert.deepEqual(ctx.errors, []);
    ctx.clearScheduleComposeErrorForDraft(0);
    assert.equal(ctx.$('scheduleComposeError').hidden, true);
    assert.equal(ctx.$('draft-time-0').getAttribute('aria-invalid'), null);
}
async function testUnknownCreateChecksWithoutResubmitting() {
    const ctx = fixture(); let posts = 0, reads = 0;
    ctx.fetch = (url, options) => {
        if (options.method === 'POST') { posts++; return new Promise(() => {}); }
        reads++; return Promise.resolve(response({ items: [{ id: 'accepted', clientRequestId: 'draft-a', status: 'SCHEDULED' }] }));
    };
    const pending = ctx.submitCreateScheduledTweet();
    ctx.state.composerClientRequestId = 'new-edit';
    ctx.state.scheduleDraftItems[0].text = 'new unsent text';
    ctx.expire(60000); await pending;
    assert.equal(posts, 1, 'a timeout never automatically resubmits the mutation');
    assert.equal(reads, 1, 'the existing request identity is reconciled using a read');
    assert.equal(ctx.clears, 0, 'queue confirmation cannot discard newer input');
    assert.equal(ctx.state.scheduleDraftItems[0].text, 'new unsent text');
    assert.equal(ctx.toasts.at(-1), '已加入队列');
    assert.equal(ctx.state.scheduleMutations.size, 0);
}
async function testJsonFastPathAndPreExecutionBusyRejection() {
    const ctx = fixture();
    let captured = null, reads = 0;
    ctx.fetch = async (url, options = {}) => {
        if (options.method === 'POST') {
            captured = { url, options };
            return response({ error: 'SERVER_BUSY', message: '当前访问高峰排队等待超时，请稍候再试' }, 503);
        }
        reads++;
        return response({ items: [] });
    };
    await ctx.submitCreateScheduledTweet();
    assert.equal(captured.url, '/api/scheduled-tweets');
    assert.equal(captured.options.headers?.['Content-Type'], 'application/json');
    assert.equal(JSON.parse(captured.options.body).text, 'original draft');
    assert.equal(reads, 0, 'pre-execution SERVER_BUSY must not trigger ambiguous queue reconciliation');
    assert.equal(ctx.$('scheduleComposeError').textContent, '当前访问高峰排队等待超时，请稍候再试');

    ctx.state.scheduleDraftItems = [{
        text: 'with media',
        scheduledAtValue: String(ctx.Date.now() + 3600000),
        files: [{ file: { size: 12 }, name: 'pic.png' }],
        timeTouched: true
    }];
    ctx.fetch = async (url, options = {}) => {
        if (options.method === 'POST') {
            captured = { url, options };
            return response({ id: 'created-media' }, 200);
        }
        return response({ items: [] });
    };
    await ctx.submitCreateScheduledTweet();
    assert.ok(captured.options.body instanceof ctx.FormData, 'media drafts keep using multipart FormData');
}
async function testRescheduleUnknownResultKeepsEditor() {
    const ctx = fixture(); let posts = 0;
    ctx.state.rescheduleEditingJobId = 'job-1'; ctx.state.rescheduleDraftValue = '2026-12-01T12:30';
    ctx.state.scheduleJobs = [{ id: 'job-1', status: 'SCHEDULED' }];
    ctx.fetch = (url, options) => options.method === 'POST'
        ? (posts++, new Promise(() => {})) : Promise.resolve(response({ items: ctx.state.scheduleJobs }));
    const input = ctx.$('reschedule-input'); input.value = ctx.state.rescheduleDraftValue;
    const pending = ctx.submitScheduledJobAction('job-1', 'reschedule', ctx.$('reschedule-save'), ctx.Date.now() + 7200000);
    ctx.expire(45000); await pending;
    assert.equal(posts, 1); assert.equal(ctx.state.rescheduleEditingJobId, 'job-1');
    assert.equal(ctx.$('reschedule-input'), input);
    assert.match(ctx.$('reschedule-error').textContent, /尚未确认/);
    assert.equal(ctx.$('reschedule-save').disabled, false);
    assert.deepEqual(ctx.errors, []);
}
function testAuthorizationErrorHasIndependentLifetime() {
    const ctx = fixture();
    ctx.state.authStatus.writeAuthorized = false;
    ctx.setPublishAuthError('用户未完成发布授权');
    ctx.hideError(); ctx.syncScheduleAuthBanner();
    assert.equal(ctx.$('schedulePublishAuthError').textContent, '用户未完成发布授权');
    assert.equal(ctx.$('schedulePublishAuthError').hidden, false);
    assert.equal(ctx.$('scheduleWriteAuthCard').hidden, false);
    ctx.setPublishAuthError('');
    assert.equal(ctx.$('schedulePublishAuthError').hidden, true);
}
async function testScheduleReauthorizeEntryPoints() {
    const ctx = fixture();
    ctx.syncScheduleAuthBanner();
    assert.equal(ctx.$('scheduleWriteAuthCard').hidden, true);
    assert.equal(ctx.$('reauthorizePublishBtn').hidden, false, 'authorized state keeps an explicit reauthorize button');

    ctx.state.authStatus.writeAuthorized = false;
    ctx.state.scheduleJobs = [{
        id: 'job-expired',
        status: 'FAILED',
        text: 'scheduled post',
        scheduledAtEpochMs: ctx.Date.now() - 60000,
        lastError: 'X 发推授权已过期或失效，请前往「定时发布」页完成二次授权：Value passed for the token was invalid.'
    }];
    ctx.syncScheduleAuthBanner();
    assert.equal(ctx.$('scheduleWriteAuthCard').hidden, false);
    assert.equal(ctx.$('reauthorizePublishBtn').hidden, true, 'toolbar button hides when top reauth card is already visible');
    assert.equal(ctx.$('scheduleWriteAuthTitle').textContent, '发推授权已过期');
    assert.equal(ctx.$('triggerPublishAuthBtn').textContent, '重新授权');

    ctx.escapeHtml = s => String(s ?? '');
    ctx.safeHttpUrl = s => String(s ?? '');
    ctx.emptyStateHtml = s => s;
    ctx.formatScheduleEpochLabel = () => '11-23 12:00';
    ctx.formatEpochToDatetimeLocalValue = () => '2026-11-23T12:00';
    ctx.statusMetaForScheduledJob = () => ({ cls: 'red', label: '发送失败' });
    vm.runInContext(section('    function renderScheduledTweetsList(', '    function openConfirmResendModal('), ctx);
    ctx.renderScheduledTweetsList();
    assert.match(ctx.$('scheduledTweetsList').innerHTML, /data-job-action="reauth"/);

    let reauthCalls = 0;
    ctx.triggerXPublishAuth = async () => { reauthCalls++; };
    let clickHandler;
    ctx.$('scheduledTweetsList').addEventListener = (event, fn) => { if (event === 'click') clickHandler = fn; };
    vm.runInContext(section('        $("scheduledTweetsList")?.addEventListener("click",', '        const confirmModal ='), ctx);
    await clickHandler({ target: { closest: () => ({ dataset: { jobAction: 'reauth', jobId: 'job-expired' } }) } });
    assert.equal(reauthCalls, 1, 'reauth action on failed job triggers X publish step-up authorization');
}
async function testLogoutShowsProgressAndVerifiesAmbiguousResult() {
    const ctx = fixture(); let posts = 0;
    ctx.$('railLogoutBtn').textContent = '退出'; ctx.$('topbarLogoutBtn').textContent = '退出';
    ctx.fetch = (url, options) => {
        if (options.method === 'POST') { posts++; return new Promise(() => {}); }
        return Promise.resolve(response(url === '/api/meta' ? { oauthConfigured: true } : { loggedIn: false, oauthConfigured: true }));
    };
    const pending = ctx.doLogout();
    assert.equal(ctx.$('railLogoutBtn').textContent, '正在退出…');
    assert.equal(ctx.$('railLogoutBtn').disabled, true);
    ctx.expire(15000); await pending;
    assert.equal(posts, 1); assert.equal(ctx.state.isLoggingOut, false);
    assert.equal(ctx.$('railLogoutBtn').disabled, false);
    assert.deepEqual(ctx.clearedOwners, ['101']);
    assert.equal(ctx.toasts.at(-1), '已退出 X 账号授权登录');
}
(async () => {
    await testAuthReadinessAndTimeout();
    await testQueueWaitingFailureAndCooldown();
    await testComposerFailureStaysLocal();
    await testUnknownCreateChecksWithoutResubmitting();
    await testJsonFastPathAndPreExecutionBusyRejection();
    await testRescheduleUnknownResultKeepsEditor();
    testAuthorizationErrorHasIndependentLifetime();
    await testScheduleReauthorizeEntryPoints();
    await testLogoutShowsProgressAndVerifiesAmbiguousResult();
    console.log('feedback-stability-regression: passed (8 behavior groups)');
})().catch(error => { console.error(error); process.exitCode = 1; });
