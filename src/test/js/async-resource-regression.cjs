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
const stateCode = section('    const LIST_PAGE_SIZE =', '    function cancelReportRequest()');
const scopeCode = section('    function stableUserId(', '    function getSelfStorageKey(');
const resetCode = section('    function cancelReportRequest()', '    // 已精简移除的元素');
const requestCode = section('    async function requestJsonWithin(', '    function syncAuthConnectionUI(');
const scheduleCode = section('    function setPublishAuthError(', '    /* ==================== 事件绑定与初始化');
const renderCode = section('    function setRescheduleError(', '    function openConfirmResendModal(');
const fileCode = section('    function validateAndMergeScheduleFiles(', '    function getTzDateParts(');
const blobCode = section('    function rememberMediaObjectUrl(', '    async function readCacheStorageMedia(');
const noop = () => {};
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function response(body = {}, status = 200) { return { status, ok: status >= 200 && status < 300, body }; }
function fixture() {
    const elements = new Map(), timers = new Map(), created = [], revoked = [];
    let timerId = 0;
    function element(id) {
        if (!elements.has(id)) elements.set(id, { hidden: false, disabled: false, textContent: '', innerHTML: '',
            classList: { add: noop, remove: noop, toggle: noop }, querySelectorAll: () => [], querySelector: () => null,
            setAttribute: noop, removeAttribute: noop, getAttribute: () => null, scrollIntoView: noop });
        return elements.get(id);
    }
    const ctx = { AbortController, Date, Intl, URL: {
        createObjectURL: value => { created.push(value); return `blob:${created.length}`; },
        revokeObjectURL: value => revoked.push(value)
    }, FormData: class { constructor() { this.values = []; } append(...args) { this.values.push(args); } },
        document: { hidden: false, getElementById: element, querySelectorAll: () => [], querySelector: () => null, activeElement: null },
        $: element, setTimeout: fn => { const id = ++timerId; timers.set(id, fn); return id; },
        clearTimeout: id => timers.delete(id), clearInterval: noop,
        cancelScheduleDraftPersistence: noop, clearPersistedScheduleDraft: async () => {},
        safeParseJson: async r => r.body, isLoggedIn: () => Boolean(ctx.state.authStatus?.loggedIn),
        syncScheduleAuthBanner: noop, syncScheduleDraftConflictUI: noop, updateScheduleBadgesAndBanner: noop,
        refreshSchedulePollingState: noop, renderScheduledTweetsList: () => { ctx.renders++; },
        renderScheduleQuickSlots: () => { ctx.recommendationRenders++; },
        fetchServerAndAuthStatus: async () => { ctx.authRefreshes++; },
        ensureScheduleDraftInitialized: noop, hasScheduleAccessDecision: () => false,
        isScheduleEligible: () => true, getScheduleLockedReason: () => 'locked',
        parseDraftItemEpochMs: it => Number(it.scheduledAtValue), getQueuedScheduledEpochsMs: () => [],
        validateBatchScheduleEpochs: () => ({ ok: true }), MIN_SCHEDULE_INTERVAL_MS: 3600000, MAX_BATCH_SCHEDULE_ITEMS: 20,
        clearScheduleComposerDraft: () => { ctx.clears++; ctx.state.scheduleDraftItems = []; },
        closeConfirmResendModal: () => { ctx.state.pendingConfirmJob = null; },
        rotateComposerRequestId: () => { ctx.state.composerClientRequestId = 'generated'; },
        showError: text => ctx.errors.push(text), showToast: text => ctx.toasts.push(text),
        renders: 0, recommendationRenders: 0, clears: 0, authRefreshes: 0, errors: [], toasts: [], timers, created, revoked, elements,
        MEDIA_CACHE_MAX_ENTRIES: 120, mediaObjectUrlMap: new Map() };
    vm.createContext(ctx);
    vm.runInContext(stateCode + scopeCode + resetCode + requestCode + scheduleCode + fileCode + blobCode + '\nthis.state = state;', ctx);
    ctx.state.authStatus = { loggedIn: true, user: { id: '101', username: 'alice' }, writeAuthorized: true, mediaWriteAuthorized: true };
    ctx.state.scheduleDraftItems = [{ text: 'scheduled content', scheduledAtValue: String(Date.now() + 3600000), files: [], timeTouched: true }];
    ctx.state.composerClientRequestId = 'draft-a';
    ctx.state.scheduleDraftPersistenceReady = true;
    return ctx;
}
async function testListSingleFlightAndLatestResponse() {
    const ctx = fixture(), calls = [];
    ctx.fetch = (url, options) => { const d = deferred(); calls.push({ url, options, ...d }); return d.promise; };
    const old = ctx.loadScheduledTweets(true);
    await ctx.loadScheduledTweets(true);
    assert.equal(calls.length, 1, 'slow poll must not stack another HTTP request');
    const latest = ctx.loadScheduledTweets(true, true);
    assert.equal(calls.length, 2, 'a mutation refresh replaces an outdated read');
    assert.equal(calls[0].options.signal.aborted, true);
    calls[1].resolve(response({ items: [{ id: 'new', status: 'SCHEDULED' }] }));
    await latest;
    calls[0].resolve(response({ items: [{ id: 'old', status: 'SCHEDULED' }] }));
    await old;
    assert.equal(ctx.state.scheduleJobs[0].id, 'new');
    assert.equal(ctx.renders, 1);
    assert.equal(ctx.recommendationRenders, 1, "only the latest queue response refreshes scheduling recommendations");
    assert.equal(ctx.state.scheduleAbortController, null);
    assert.equal(ctx.timers.size, 0, 'completed requests clear their deadline timers');
}
async function testListSessionResetAnd401() {
    const ctx = fixture(), d = deferred();
    let signal;
    ctx.fetch = (url, options) => { signal = options.signal; return d.promise; };
    const request = ctx.loadScheduledTweets(true);
    ctx.resetUserScopedMemoryState();
    // A fresh session of the same account must not accept its previous session's response.
    ctx.state.authStatus = { loggedIn: true, user: { id: '101', username: 'alice' } };
    d.resolve(response({ items: [{ id: 'previous-session' }] }));
    await request;
    assert.equal(signal.aborted, true);
    assert.equal(ctx.state.scheduleJobs.length, 0);
    const unauthorized = fixture();
    unauthorized.fetch = async () => response({ message: 'expired' }, 401);
    await unauthorized.loadScheduledTweets(true);
    assert.equal(unauthorized.authRefreshes, 1, 'silent poll reconciles expired authentication');
    assert.equal(unauthorized.renders, 0);
}
async function testPollRespectsRetryAfter() {
    const ctx = fixture();
    let calls = 0;
    ctx.fetch = async () => {
        calls++;
        return { ...response({ message: 'slow down' }, 429), headers: { get: () => '30' } };
    };
    const started = Date.now();
    await ctx.loadScheduledTweets(true);
    await ctx.loadScheduledTweets(true);
    await ctx.loadScheduledTweets(false, true);
    assert.equal(calls, 1, 'polls and manual refresh do not bypass the server backoff');
    assert.ok(ctx.state.scheduleRetryNotBeforeEpochMs >= started + 30000);
    ctx.state.scheduleRetryNotBeforeEpochMs = 0;
    ctx.fetch = async () => { calls++; return response({ items: [] }); };
    await ctx.loadScheduledTweets(true);
    assert.equal(calls, 2, 'reading resumes when the backoff expires');
}

async function testCreateDedupAndEditedDraftPreservation() {
    const ctx = fixture(), d = deferred();
    let posts = 0;
    ctx.fetch = (url, options) => {
        if (options.method === 'POST') { posts++; return d.promise; }
        return Promise.resolve(response({ items: [] }));
    };
    const pending = ctx.submitCreateScheduledTweet();
    await ctx.submitCreateScheduledTweet();
    assert.equal(posts, 1, 'repeated submit cannot create another outgoing request');
    ctx.state.composerClientRequestId = 'draft-b';
    ctx.state.scheduleDraftItems[0].text = 'new unsent edit';
    d.resolve(response({ id: 'created' }));
    await pending;
    assert.equal(ctx.clears, 0, 'an edited draft survives the old submission success');
    assert.equal(ctx.state.scheduleDraftItems[0].text, 'new unsent edit');
    assert.equal(ctx.state.scheduleMutations.size, 0);
    assert.equal(ctx.toasts.length, 1);
}
async function testCreateCannotClearNewSession() {
    const ctx = fixture(), d = deferred();
    ctx.fetch = () => d.promise;
    const pending = ctx.submitCreateScheduledTweet();
    ctx.resetUserScopedMemoryState();
    ctx.state.authStatus = { loggedIn: true, user: { id: '101' }, writeAuthorized: true };
    ctx.state.scheduleDraftItems = [{ text: 'fresh session draft', files: [] }];
    ctx.state.scheduleMutations.add('create');
    d.resolve(response({ id: 'created' }));
    await pending;
    assert.equal(ctx.clears, 0);
    assert.equal(ctx.toasts.length, 0);
    assert.equal(ctx.state.scheduleMutations.has('create'), true, 'old finally does not release a new session action');
}
async function testJobMutationDedupAndLateErrors() {
    const ctx = fixture(), d = deferred(), button = { disabled: false };
    let writes = 0;
    ctx.fetch = () => { writes++; return d.promise; };
    const pending = ctx.submitScheduledJobAction('job-1', 'cancel', button);
    await ctx.submitScheduledJobAction('job-1', 'delete', button);
    assert.equal(writes, 1, 'conflicting same-job mutations cannot overlap');
    ctx.resetUserScopedMemoryState();
    ctx.state.authStatus = { loggedIn: true, user: { id: '202' } };
    d.resolve(response({ message: 'old error' }, 500));
    await pending;
    assert.equal(ctx.errors.length, 0, 'late errors from another account do not reach current UI');
}
function testPollingCannotEnablePendingSubmit() {
    const ctx = fixture();
    vm.runInContext(section('    function syncScheduleAuthBanner()', '    function formatGapDurationShort('), ctx);
    ctx.state.scheduleMutations.add('create');
    ctx.syncScheduleAuthBanner();
    assert.equal(ctx.$('createScheduleBtn').disabled, true, 'auth/list synchronization preserves the in-flight submit lock');
    assert.equal(ctx.$('createScheduleBtn').textContent, '提交中…');
    ctx.state.scheduleMutations.delete('create');
    ctx.syncScheduleAuthBanner();
    assert.equal(ctx.$('createScheduleBtn').disabled, false);
}

function testMediaValidationAndBoundedCache() {
    const ctx = fixture();
    const result = ctx.validateAndMergeScheduleFiles([], [
        { name: 'ok.png', type: 'image/png', size: 10 },
        { name: 'invalid.svg', type: 'image/svg+xml', size: 10 }
    ]);
    assert.equal(result.ok, false);
    assert.equal(ctx.created.length, 0, 'invalid batch must not allocate unreleasable previews');
    const blob = {};
    for (let i = 0; i < 125; i++) ctx.rememberMediaObjectUrl(`https://pbs.twimg.com/${i}`, blob);
    assert.equal(ctx.mediaObjectUrlMap.size, 120);
    assert.equal(ctx.revoked.length, 5);
    ctx.rememberMediaObjectUrl('https://pbs.twimg.com/124', blob);
    assert.equal(ctx.created.length, 125, 'concurrent cache completion reuses the existing object URL');
}
function testAccountResetHidesPreviousReport() {
    const ctx = fixture();
    const panels = [{ id: 'panel-circle', hidden: false }, { id: 'panel-activity', hidden: false }, { id: 'panel-schedule', hidden: false }];
    ctx.document.querySelectorAll = () => panels;
    const canvas = ctx.$('shareCardCanvas');
    let resets = 0;
    Object.defineProperty(canvas, 'width', { get: () => 1080, set: () => { resets++; } });
    ctx.state.report = { profile: { id: '101' } };
    ctx.resetUserScopedMemoryState();
    assert.equal(resets, 1, 'reset clears the previously rendered personal share image');
    assert.equal(panels[0].hidden, true);
    assert.equal(panels[1].hidden, true);
    assert.equal(panels[2].hidden, false, 'scheduler remains usable without an analytics report');
    assert.equal(ctx.$('authenticatedRightRail').hidden, true);
    ctx.state.report = { profile: { id: '202' } };
    ctx.syncReportVisibility();
    assert.equal(panels[0].hidden, false);
    assert.equal(ctx.$('authenticatedRightRail').hidden, false);
}

function rescheduleFixture() {
    const ctx = fixture();
    Object.assign(ctx, { statusMetaForScheduledJob: () => ({ label: 'scheduled', cls: '' }),
        formatScheduleEpochLabel: String, formatEpochToDatetimeLocalValue: () => 'default-time',
        escapeHtml: value => String(value), safeHttpUrl: String, emptyStateHtml: () => '<empty>',
        CSS: { escape: String } });
    vm.runInContext(renderCode, ctx);
    ctx.state.scheduleJobs = [{ id: 'job-1', status: 'SCHEDULED', text: 'post', scheduledAtEpochMs: 12345 }];
    ctx.state.rescheduleEditingJobId = 'job-1';
    ctx.state.rescheduleDraftValue = '2026-12-01T12:30';
    const list = ctx.$('scheduledTweetsList');
    let writes = 0, html = '', input = null, error = null, save = null;
    const select = selector => {
        if (selector === '[data-resched-input="job-1"]') return input;
        if (selector === '[data-resched-error="job-1"]') return error;
        if (selector === '[data-job-action="save-reschedule"][data-job-id="job-1"]') return save;
        return null;
    };
    list.querySelector = select;
    ctx.document.querySelector = select;
    Object.defineProperty(list, 'innerHTML', { get: () => html, set: value => {
        html = value;
        writes++;
        input = error = save = null;
        if (!value.includes('data-resched-input="job-1"')) return;
        const attributes = { 'data-resched-input': 'job-1', 'aria-invalid': 'false' };
        input = { value: value.match(/data-resched-input="job-1"[^>]*value="([^"]*)"/)[1],
            dataset: { reschedInput: 'job-1' },
            setAttribute: (name, text) => { attributes[name] = text; },
            getAttribute: name => attributes[name], focus: noop };
        error = { textContent: '', hidden: true, scrolls: [],
            scrollIntoView(options) { this.scrolls.push(options.block); } };
        save = { disabled: false };
    } });
    ctx.editor = { list, get input() { return input; }, get error() { return error; },
        get save() { return save; }, get writes() { return writes; }, get html() { return html; } };
    ctx.renderScheduledTweetsList();
    return ctx;
}
async function testScheduleRenderKeepsEdits() {
    const ctx = rescheduleFixture(), dom = ctx.editor, input = dom.input;
    const renderedSignature = ctx.state.scheduleRenderedSignature;
    assert.ok(dom.html.includes('value="2026-12-01T12:30"'));
    ctx.renderScheduledTweetsList();
    ctx.state.scheduleJobs.push({ id: 'job-2', status: 'PUBLISHED', text: 'other post' });
    ctx.renderScheduledTweetsList();
    assert.equal(dom.writes, 1, 'another job changing must not dismiss the native time picker');
    assert.equal(dom.input, input, 'the input node survives even when it is not document.activeElement');
    assert.equal(ctx.state.scheduleRenderedSignature, renderedSignature, 'deferred content is not marked rendered');

    ctx.state.scheduleJobs[0].status = 'SENDING';
    ctx.renderScheduledTweetsList();
    assert.equal(dom.writes, 1);
    assert.equal(dom.input, input);
    assert.equal(ctx.state.rescheduleEditingJobId, 'job-1');
    assert.equal(dom.save.disabled, true, 'a sending job cannot be rescheduled');
    assert.equal(dom.error.hidden, false);
    assert.match(dom.error.textContent, /正在发送/);
    assert.equal(input.getAttribute('aria-invalid'), 'true');
    assert.equal(dom.error.scrolls.length, 0, 'background status changes do not move the viewport');

    let click;
    dom.list.addEventListener = (event, handler) => { click = handler; };
    vm.runInContext(section('        $("scheduledTweetsList")?.addEventListener("click",',
        '        const confirmModal ='), ctx);
    await click({ target: { closest: () => ({ dataset: { jobAction: 'close-reschedule', jobId: 'job-1' } }) } });
    assert.equal(ctx.state.rescheduleEditingJobId, null);
    assert.equal(dom.writes, 2, 'explicitly closing the editor renders the latest jobs');
    assert.equal(dom.input, null);
    assert.ok(dom.html.includes('other post'));
}
async function testRescheduleErrorsAndNewEdits() {
    const ctx = rescheduleFixture(), dom = ctx.editor, input = dom.input;
    const submittedValue = input.value;
    for (const status of [400, 409]) {
        const d = deferred(), message = status === 400 ? '时间已过期' : '与队列时间冲突';
        ctx.fetch = () => d.promise;
        const pending = ctx.submitScheduledJobAction('job-1', 'reschedule', dom.save, Date.now() + 3600000);
        assert.equal(dom.save.disabled, true);
        d.resolve(response({ message }, status));
        await pending;
        assert.equal(ctx.state.rescheduleEditingJobId, 'job-1');
        assert.equal(ctx.state.rescheduleDraftValue, submittedValue);
        assert.equal(dom.input, input);
        assert.equal(input.value, submittedValue);
        assert.equal(dom.writes, 1, 'an API error must not replace the editor');
        assert.equal(dom.save.disabled, false, 'failed saving restores the same button');
        assert.equal(dom.error.hidden, false);
        assert.equal(dom.error.textContent, message);
        assert.equal(input.getAttribute('aria-invalid'), 'true');
        assert.equal(dom.error.scrolls.at(-1), 'nearest', 'the error is revealed next to the input');
    }
    assert.deepEqual(ctx.errors, [], 'reschedule failures must not go to the page-top error');
    assert.deepEqual(ctx.toasts, []);
    assert.equal(ctx.state.scheduleMutations.size, 0);

    const d = deferred();
    ctx.fetch = (url, options) => options.method === 'POST' ? d.promise
        : Promise.resolve(response({ items: [{ ...ctx.state.scheduleJobs[0], scheduledAtEpochMs: 23456 }] }));
    const pending = ctx.submitScheduledJobAction('job-1', 'reschedule', dom.save, Date.now() + 3600000);
    assert.equal(dom.error.hidden, true, 'retry clears the previous inline error');
    input.value = '2026-12-01T14:30';
    ctx.state.rescheduleDraftValue = input.value;
    d.resolve(response({ id: 'job-1' }));
    await pending;
    assert.equal(ctx.state.rescheduleEditingJobId, 'job-1');
    assert.equal(ctx.state.rescheduleDraftValue, '2026-12-01T14:30');
    assert.equal(dom.input, input, 'success for an older submission preserves the current editor');
    assert.equal(input.value, '2026-12-01T14:30');
    assert.equal(dom.writes, 1);
    assert.equal(dom.save.disabled, false);
    assert.match(ctx.toasts.at(-1), /当前修改尚未保存/);

    const listeners = {};
    dom.list.addEventListener = (event, handler) => { listeners[event] = handler; };
    vm.runInContext(section('        const trackRescheduleInteraction =',
        '        const updateRescheduleDraft ='), ctx);
    const focusResponse = deferred(), beforeFocusValue = input.value;
    ctx.fetch = (url, options) => options.method === 'POST' ? focusResponse.promise
        : Promise.resolve(response({ items: ctx.state.scheduleJobs }));
    const focusPending = ctx.submitScheduledJobAction('job-1', 'reschedule', dom.save, Date.now() + 3600000);
    const beforeFocusVersion = ctx.state.rescheduleInteractionVersion;
    ctx.document.activeElement = input;
    listeners.focusin({ target: input });
    assert.equal(input.value, beforeFocusValue, 'opening the picker need not change its committed value');
    assert.ok(ctx.state.rescheduleInteractionVersion > beforeFocusVersion);
    focusResponse.resolve(response({ id: 'job-1' }));
    await focusPending;
    assert.equal(ctx.state.rescheduleEditingJobId, 'job-1');
    assert.equal(ctx.state.rescheduleDraftValue, beforeFocusValue);
    assert.equal(dom.input, input, 'focus during saving preserves the reopened native picker');
    assert.equal(dom.writes, 1);
    assert.equal(dom.save.disabled, false);

    ctx.fetch = async (url, options) => options.method === 'POST'
        ? response({ id: 'job-1' }) : response({ items: ctx.state.scheduleJobs });
    await ctx.submitScheduledJobAction('job-1', 'reschedule', dom.save, Date.now() + 3600000);
    assert.equal(ctx.state.rescheduleEditingJobId, null, 'ordinary success still closes an untouched editor');
    assert.equal(ctx.state.rescheduleDraftValue, null);
    assert.equal(dom.input, null);
    assert.equal(dom.writes, 2);
    assert.equal(ctx.toasts.at(-1), '已重新排期');
    assert.equal(ctx.state.scheduleMutations.size, 0);
}
async function testScheduledTaskCopiesOnlyOriginalText() {
    const ctx = fixture(), copied = [];
    let click, failCopy = false;
    ctx.$('scheduledTweetsList').addEventListener = (event, handler) => {
        assert.equal(event, 'click');
        click = handler;
    };
    ctx.navigator = { clipboard: { writeText: async text => {
        copied.push(text);
        if (failCopy) throw new Error('clipboard unavailable');
    } } };
    ctx.legacyExecCopy = async () => { throw new Error('fallback unavailable'); };
    ctx.fetch = async () => { throw new Error('copy must not send a request'); };
    ctx.hasScheduleAccessDecision = () => true;
    ctx.isScheduleEligible = () => false;
    ctx.state.authStatus.writeAuthorized = false;
    vm.runInContext(
        section('    function copyTextWithFallback(', '    function legacyExecCopy(')
        + section('        $("scheduledTweetsList")?.addEventListener("click",',
            '        const confirmModal ='), ctx);
    const original = '  第一行 <tag> & "引号" 😀\n第二行\nhttps://example.test/original?a=1&b=2  ';
    const job = { id: 'copy-1', text: original, status: 'SCHEDULED',
        scheduledAtEpochMs: 1893456000000, publishedTweetUrl: 'https://x.com/account/status/123',
        media: [{ name: 'private-attachment.png', url: 'https://example.test/attachment' }] };
    ctx.state.scheduleJobs = [job];
    ctx.state.scheduleMutations.add('job:copy-1');
    ctx.state.scheduleMutations.add('batch-resend');
    const before = JSON.stringify(job);
    const event = { target: { closest: () => ({ dataset: { jobAction: 'copy-text', jobId: job.id } }) } };
    await click(event);
    assert.deepEqual(copied, [original], 'clipboard gets exact body text, with no task metadata');
    assert.deepEqual(ctx.toasts, ['已复制原文']);
    assert.equal(JSON.stringify(job), before, 'copy leaves the task unchanged during a mutation');

    job.text = '';
    ctx.toasts.length = 0;
    await click(event);
    assert.equal(copied.length, 1, 'empty text leaves the clipboard untouched');
    assert.deepEqual(ctx.toasts, ['这条任务没有文本']);

    job.text = original;
    failCopy = true;
    ctx.toasts.length = 0;
    await click(event);
    assert.deepEqual(ctx.toasts, [], 'failed copying must not report success');
    assert.deepEqual(ctx.errors, ['复制失败，请手动选择文本复制']);
}

async function testResendConfirmationFollowsDisplayedTask() {
    for (const firstStatus of [200, 409]) {
        const ctx = fixture(), first = deferred(), second = deferred();
        ctx.formatScheduleEpochLabel = String;
        ctx.syncBodyModalLock = noop;
        ctx.$('confirmResendTextPreview').toggleAttribute = noop;
        vm.runInContext(section('    function openConfirmResendModal(', '    function refreshSchedulePollingState('), ctx);
        ctx.loadScheduledTweets = async () => true;
        let posts = 0;
        ctx.fetch = url => { posts++; return url.includes('/job-a/') ? first.promise : second.promise; };
        const a = { id: 'job-a', text: 'original A', confirmToken: 'token-a', media: [] };
        const b = { id: 'job-b', text: 'original B', confirmToken: 'token-b', media: [] };
        ctx.openConfirmResendModal(a);
        const pendingA = ctx.submitConfirmResend(a);
        const button = ctx.$('confirmResendSubmitBtn');
        assert.equal(button.disabled, true);
        assert.equal(button.textContent, '确认中…');
        ctx.closeConfirmResendModal();
        ctx.openConfirmResendModal(b);
        assert.equal(button.disabled, false, 'opening another task resets its own button state');
        assert.equal(button.textContent, '确认补发');
        const pendingB = ctx.submitConfirmResend(b);
        assert.equal(button.disabled, true);
        ctx.closeConfirmResendModal();
        ctx.openConfirmResendModal(b);
        assert.equal(button.disabled, true, 'reopening an in-flight task keeps its submit disabled');
        await ctx.submitConfirmResend(b);
        assert.equal(posts, 2, 'reopening does not allow another outgoing request');
        first.resolve(response({ message: 'A response' }, firstStatus));
        await pendingA;
        assert.equal(ctx.state.pendingConfirmJob, b);
        assert.equal(ctx.$('confirmResendModal').hidden, false, 'A response cannot close B');
        assert.equal(button.disabled, true, 'A finally cannot unlock B while B is in flight');
        assert.equal(button.textContent, '确认中…');
        assert.equal(ctx.$('confirmResendReason').textContent.includes('A response'), false);
        second.resolve(response({ id: 'job-b' }));
        await pendingB;
        assert.equal(ctx.state.pendingConfirmJob, null);
        assert.equal(ctx.$('confirmResendModal').hidden, true);
        assert.equal(button.disabled, false);
        assert.equal(button.textContent, '确认补发');
        assert.equal(ctx.state.scheduleMutations.size, 0);
    }
}

function testRepeatedScheduleFilterPreservesEditor() {
    const ctx = rescheduleFixture(), input = ctx.editor.input;
    const chips = ['ALL', 'PUBLISHED'].map(sfilter => ({
        dataset: { sfilter }, classList: { toggle: noop },
        addEventListener(event, listener) { assert.equal(event, 'click'); this.click = listener; }
    }));
    ctx.$('scheduleStatusFilterPills').querySelectorAll = () => chips;
    vm.runInContext(section('        $("scheduleStatusFilterPills")?.querySelectorAll(',
        '        const trackRescheduleInteraction ='), ctx);
    input.value = '2026-12-02T17:30';
    ctx.state.rescheduleDraftValue = input.value;
    ctx.setRescheduleError('job-1', '当前时间待调整');
    const writes = ctx.editor.writes;
    chips[0].click();
    assert.equal(ctx.state.rescheduleEditingJobId, 'job-1');
    assert.equal(ctx.state.rescheduleDraftValue, '2026-12-02T17:30');
    assert.equal(ctx.state.rescheduleError, '当前时间待调整');
    assert.equal(ctx.editor.input, input);
    assert.equal(ctx.editor.writes, writes, 'same-filter click cannot rebuild the date picker');
    chips[1].click();
    assert.equal(ctx.state.scheduleFilter, 'PUBLISHED');
    assert.equal(ctx.state.rescheduleEditingJobId, null, 'an intentional filter change still closes the editor');
    assert.equal(ctx.state.rescheduleDraftValue, null);
    assert.equal(ctx.editor.input, null);
}

async function testLegacyClipboardPreservesEditingStateAndCleansUp() {
    const original = '  原文 <tag> 😀\nhttps://example.test/a?b=1  ';
    for (const outcome of ['success', 'false', 'throw']) {
        for (const kind of ['input', 'range']) {
            const inserted = [], removed = [], focusCalls = [];
            const originalRange = { marker: 'original range', cloneRange() { return { marker: this.marker }; } };
            let selected = [originalRange];
            const selection = {
                get rangeCount() { return selected.length; },
                getRangeAt: idx => selected[idx],
                removeAllRanges() { selected = []; },
                addRange(range) { selected.push(range); }
            };
            const active = { isConnected: true,
                focus(options) { focusCalls.push(options); document.activeElement = this; },
                setSelectionRange(start, end, direction) { this.restoredSelection = [start, end, direction]; }
            };
            if (kind === 'input') Object.assign(active, { selectionStart: 2, selectionEnd: 8, selectionDirection: 'backward' });
            const document = {
                activeElement: active, getSelection: () => selection,
                body: {
                    appendChild(node) { inserted.push(node); node.parentNode = this; },
                    removeChild(node) { removed.push(node); node.parentNode = null; document.activeElement = null; }
                },
                createElement(tag) {
                    assert.equal(tag, 'textarea');
                    return { style: {}, value: '', setAttribute: noop,
                        focus() { document.activeElement = this; },
                        select() { selected = [{ marker: 'temporary clipboard selection' }]; }
                    };
                },
                execCommand(command) {
                    assert.equal(command, 'copy');
                    assert.equal(inserted[0].value, original, 'clipboard helper must copy the exact original text');
                    if (outcome === 'throw') throw new Error('browser denied copy');
                    return outcome === 'success';
                }
            };
            const ctx = vm.createContext({ document });
            vm.runInContext(section('    function legacyExecCopy(', '    function bindEvents()'), ctx);
            const copy = ctx.legacyExecCopy(original);
            if (outcome === 'success') await copy;
            else await assert.rejects(copy);
            assert.equal(inserted.length, 1);
            assert.equal(removed.length, 1, 'temporary textarea is removed even when copy throws');
            assert.equal(removed[0], inserted[0]);
            assert.equal(document.activeElement, active, 'copy never leaves focus on body or a detached node');
            assert.equal(focusCalls.at(-1).preventScroll, true);
            if (kind === 'input') assert.deepEqual(active.restoredSelection, [2, 8, 'backward']);
            else assert.deepEqual(selected.map(range => range.marker), ['original range']);
        }
    }
}

(async () => {
    await testListSingleFlightAndLatestResponse();
    await testListSessionResetAnd401();
    await testCreateDedupAndEditedDraftPreservation();
    await testPollRespectsRetryAfter();
    await testCreateCannotClearNewSession();
    await testJobMutationDedupAndLateErrors();
    testMediaValidationAndBoundedCache();
    testPollingCannotEnablePendingSubmit();
    await testScheduleRenderKeepsEdits();
    await testRescheduleErrorsAndNewEdits();
    testAccountResetHidesPreviousReport();
    await testScheduledTaskCopiesOnlyOriginalText();
    await testResendConfirmationFollowsDisplayedTask();
    testRepeatedScheduleFilterPreservesEditor();
    await testLegacyClipboardPreservesEditingStateAndCleansUp();
    console.log('async-resource-regression: passed (15 behavior groups)');
})().catch(error => { console.error(error); process.exitCode = 1; });
