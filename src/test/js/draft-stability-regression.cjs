const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { File, Blob } = require('node:buffer');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
    const a = source.indexOf(start), b = source.indexOf(end, a);
    assert.ok(a >= 0 && b > a, 'Missing production section: ' + start);
    return source.slice(a, b);
}
const noop = () => {};
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
async function settle() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
const decode = s => String(s).replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');

// Only browser boundaries are faked; all mutations, serialization, recovery and DOM updates below execute production functions.
function fixture(shared = {}) {
    const session = shared.session || new Map(), database = shared.database || new Map();
    const clock = shared.clock || { now: Date.parse('2026-11-16T10:00:00Z') };
    const timers = new Map(), windowListeners = new Map();
    let timerId = 0, requestId = 0, previewId = 0;
    const ctx = { errors: [], publishErrors: [], confirms: [], confirmAnswer: true, revoked: [],
        writes: 0, deletes: 0, reads: 0, storeAttempts: 0, failSession: false, failMedia: false, storeGate: null,
        session, database, clock, timers, windowListeners, console, File, Blob, structuredClone,
        Date: class extends Date { static now() { return clock.now; } },
        MAX_BATCH_SCHEDULE_ITEMS: 20, MIN_SCHEDULE_INTERVAL_MS: 3600000,
        setTimeout: (fn, delay) => { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
        clearTimeout: id => timers.delete(id),
        indexedDB: {},
        sessionStorage: {
            getItem: key => session.get(key) || null,
            setItem: (key, value) => { if (ctx.failSession) throw new Error('quota'); session.set(key, String(value)); },
            removeItem: key => session.delete(key)
        },
        URL: { createObjectURL: () => 'blob:test-' + (++previewId), revokeObjectURL: value => ctx.revoked.push(value) },
        window: {
            location: { href: 'http://localhost/' },
            addEventListener: (name, fn) => windowListeners.set(name, fn),
            removeEventListener: (name, fn) => { if (windowListeners.get(name) === fn) windowListeners.delete(name); },
            confirm: text => { ctx.confirms.push(text); return ctx.confirmAnswer; }
        },
        newClientRequestId: () => 'request-' + (++requestId),
        isLoggedIn: () => Boolean(ctx.state.authStatus?.loggedIn),
        getRecommendedScheduleEpochMs: preferred => preferred,
        getRecommendedScheduleGapMs: () => 3600000,
        formatEpochToDatetimeLocalValue: ms => new Date(ms).toISOString().slice(0, 16),
        parseDraftItemEpochMs: item => new Date(item?.scheduledAtValue || '').getTime(),
        refreshAutomaticScheduleTimes: noop,
        formatScheduleLocalTime: String,
        syncScheduleComposerStats: noop, syncSingleDraftItemStats: noop,
        syncScheduleDraftConflictUI: noop, syncScheduleAuthBanner: noop, renderScheduleQuickSlots: noop,
        setScheduleComposerError: message => ctx.errors.push(message),
        clearScheduleComposeErrorForDraft: noop,
        setPublishAuthError: message => ctx.publishErrors.push(message),
        showToast: message => ctx.errors.push(message),
        switchTab: tab => { ctx.state.activeTab = tab; },
        fetchServerAndAuthStatus: async () => { ctx.state.authConnectionState = 'ready'; },
        triggerXOAuthLogin: noop,
        escapeHtml: value => String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
    };
    const document = ctx.document = { activeElement: null };
    function node(tag, attrs = {}) {
        const n = { tag, attrs: { ...attrs }, dataset: {}, value: '', innerHTML: '', hidden: false, nodes: [],
            selectionStart: 0, selectionEnd: 0, classList: { toggle: noop, add: noop, remove: noop },
            setAttribute(k, v) {
                n.attrs[k] = String(v);
                if (k.startsWith('data-')) n.dataset[k.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = String(v);
            },
            getAttribute: k => n.attrs[k] ?? null,
            focus: () => { document.activeElement = n; }, scrollIntoView: noop,
            contains: element => element === n || n.nodes.includes(element),
            querySelector: selector => n.nodes.find(el => matches(el, selector)) || null,
            querySelectorAll: selector => n.nodes.filter(el => matches(el, selector))
        };
        Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v));
        return n;
    }
    function matches(n, selector) {
        const last = selector.split(' ').at(-1);
        const tag = /^[a-z]+/i.exec(last)?.[0];
        if (tag && tag !== n.tag) return false;
        const cls = /\.([\w-]+)/.exec(last)?.[1];
        if (cls && !String(n.attrs.class || '').split(' ').includes(cls)) return false;
        for (const match of last.matchAll(/\[([\w-]+)(?:="([^"]*)")?\]/g)) {
            if (!(match[1] in n.attrs)) return false;
            if (match[2] !== undefined && n.attrs[match[1]] !== match[2]) return false;
        }
        return true;
    }
    const list = ctx.list = node('div', { id: 'scheduleDraftItemsList' });
    list.children = [];
    Object.defineProperty(list, 'innerHTML', { get: () => '', set: () => { throw new Error('Whole composer replacement destroys focused controls'); } });
    list.insertBefore = (card, ref) => {
        const old = list.children.indexOf(card);
        if (old >= 0) list.children.splice(old, 1);
        const position = ref ? list.children.indexOf(ref) : list.children.length;
        list.children.splice(position < 0 ? list.children.length : position, 0, card);
        card.remove = () => { const index = list.children.indexOf(card); if (index >= 0) list.children.splice(index, 1); };
    };
    const fileInput = node('input');
    document.getElementById = id => id === 'scheduleDraftItemsList' ? list : id === 'scheduleMediaFileInput' ? fileInput : null;
    document.querySelector = selector => list.children.flatMap(card => card.nodes).find(n => matches(n, selector)) || null;
    document.querySelectorAll = selector => list.children.flatMap(card => card.nodes).filter(n => matches(n, selector));
    document.createElement = tag => {
        assert.equal(tag, 'template');
        const template = { content: {} };
        Object.defineProperty(template, 'innerHTML', { set(html) {
            const tags = [...html.matchAll(/<(div|span|button|textarea|input)\b([^>]*)>/g)];
            const parsed = tags.map(match => {
                const attrs = Object.fromEntries([...match[2].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], decode(a[2])]));
                const el = node(match[1], attrs);
                if (match[1] === 'textarea') el.value = decode(html.slice(match.index + match[0].length).split('</textarea>')[0]);
                if (match[1] === 'input') el.value = attrs.value || '';
                return el;
            });
            const card = parsed[0];
            card.nodes = parsed.slice(1);
            template.content.firstElementChild = card;
        } });
        return template;
    };
    ctx.$ = document.getElementById;
    vm.createContext(ctx);
    vm.runInContext(
        section('    const LIST_PAGE_SIZE =', '    function cancelReportRequest()')
        + section('    function stableUserId(', '    function getSelfStorageKey(')
        + section('    function isOwnerAllPermissions()', '    function hasPeriodAccessDecision()')
        + section('    function validateAndMergeScheduleFiles(', '    function getTzDateParts(')
        + section('    function rotateComposerRequestId()', '    function getQueuedScheduledEpochsMs(')
        + section('    function randomScheduleJitterMinutes()', '    function getRecommendedScheduleEpochMs(')
        + section('    function createScheduleDraftItem(', '    const STEPUP_SCHEDULE_DRAFT_KEY =')
        + section('    const STEPUP_SCHEDULE_DRAFT_KEY =', '    function syncScheduleAuthBanner()')
        + section('    function scheduleDraftMediaHtml(', '    function renderScheduleQuickSlots()')
        + section('    function sendTextDraftToSchedule(', '    function canvasToPngFile(')
        + '\nthis.state = state; this.ttl = SCHEDULE_DRAFT_TTL_MS; this.getStorageQueue = () => scheduleDraftStorageQueue;', ctx);
    ctx.accessScheduleDraftStore = async (mode, action) => {
        ctx.storeAttempts++;
        if (ctx.storeGate) await ctx.storeGate.promise;
        if (ctx.failMedia) throw new Error('media storage unavailable');
        const store = {
            put: record => { ctx.writes++; database.set(record.key, structuredClone(record)); return { result: record.key }; },
            get: key => { ctx.reads++; return { result: database.has(key) ? structuredClone(database.get(key)) : undefined }; },
            delete: key => { ctx.deletes++; database.delete(key); return { result: undefined }; }
        };
        return action(store)?.result;
    };
    ctx.state.authStatus = { loggedIn: true, user: { id: '101' }, oauthConfigured: true, writeAuthorized: true, mediaWriteAuthorized: true };
    ctx.state.authConnectionState = 'ready';
    ctx.state.scheduleDraftPersistenceReady = true;
    ctx.state.composerClientRequestId = 'initial-request';
    ctx.draft = (text = '', files = [], touched = true) => ctx.createScheduleDraftItem(text, clock.now + 7200000, files, touched);
    ctx.media = (name = 'image.png', contents = 'png-content') => ctx.validateAndMergeScheduleFiles([], [new File([contents], name, { type: 'image/png' })]).files;
    ctx.shared = { session, database, clock };
    return ctx;
}

async function testNonDestructiveTransfer() {
    const c = fixture(), old = c.draft('already written', c.media()), oldFiles = old.files;
    c.state.scheduleDraftItems = [old]; c.renderScheduleDraftItems();
    const oldCard = c.list.children[0];
    assert.equal(c.sendTextDraftToSchedule('incoming'), true);
    assert.equal(c.state.scheduleDraftItems.length, 2);
    assert.equal(c.state.scheduleDraftItems[0], old);
    assert.equal(old.files, oldFiles); assert.equal(old.text, 'already written');
    assert.equal(c.list.children[0], oldCard);
    assert.equal(c.state.scheduleDraftItems[1].text, 'incoming');

    const empty = c.draft('', [], true), chosenTime = empty.scheduledAtValue;
    c.state.scheduleDraftItems = [old, empty]; c.state.activeScheduleDraftIdx = 1;
    assert.equal(c.sendTextDraftToSchedule('fills empty'), true);
    assert.equal(c.state.scheduleDraftItems[1], empty);
    assert.equal(empty.scheduledAtValue, chosenTime, 'manually chosen time survives a text import');
    c.state.scheduleDraftItems = Array.from({ length: 20 }, (_, i) => c.draft('draft-' + i));
    c.state.activeScheduleDraftIdx = 0;
    const originals = [...c.state.scheduleDraftItems], before = JSON.stringify(c.state.scheduleDraftItems);
    assert.equal(c.sendTextDraftToSchedule('cannot fit', [new File(['x'], 'x.png', { type: 'image/png' })]), false);
    assert.equal(JSON.stringify(c.state.scheduleDraftItems), before);
    assert.ok(originals.every((draft, i) => draft === c.state.scheduleDraftItems[i]));
    assert.match(c.errors.at(-1), /草稿已满.*保留/);
}

async function testStableNodesAndDestructiveConfirmation() {
    const c = fixture(), first = c.draft('first');
    c.state.scheduleDraftItems = [first]; c.renderScheduleDraftItems();
    const card = c.list.children[0], text = card.querySelector('textarea'), time = card.querySelector('input[type="datetime-local"]');
    text.focus(); text.selectionStart = 2; text.selectionEnd = 4;
    c.appendFilesToScheduleDraft([new File(['image'], 'test.png', { type: 'image/png' })], 0);
    assert.equal(c.list.children[0], card);
    assert.equal(card.querySelector('textarea'), text); assert.equal(card.querySelector('input[type="datetime-local"]'), time);
    assert.equal(c.document.activeElement, text); assert.equal(text.selectionStart, 2); assert.equal(text.selectionEnd, 4);
    time.focus(); time.value = '2026-11-18T13:4';
    first.files = []; c.renderScheduleDraftMedia(0);
    assert.equal(c.document.activeElement, time); assert.equal(time.value, '2026-11-18T13:4');
    const second = c.draft('second');
    c.state.scheduleDraftItems.push(second); c.renderScheduleDraftItems();
    const secondCard = c.list.children[1], secondText = secondCard.querySelector('textarea');
    assert.equal(c.list.children[0], card); assert.equal(time.value, '2026-11-18T13:4');
    c.confirmAnswer = false; c.removeScheduleDraftItem(0);
    assert.equal(c.state.scheduleDraftItems.length, 2); assert.equal(c.list.children[0], card);
    c.confirmAnswer = true; c.removeScheduleDraftItem(0);
    assert.equal(c.list.children[0], secondCard); assert.equal(secondCard.querySelector('textarea'), secondText);
    assert.equal(secondText.getAttribute('data-draft-textarea'), '0');
    assert.equal(secondText.getAttribute('aria-label'), '第 1 条推文正文');
    assert.equal(secondCard.querySelector('input[type="datetime-local"]').getAttribute('aria-label'), '第 1 条定时发布时间');
    assert.equal(c.confirms.length, 2);
}

async function testRecoveryIsolationAndRetention() {
    const c = fixture(), start = c.clock.now;
    c.state.scheduleDraftItems = [c.draft(' exact text\n ', c.media('original.png', 'original-bytes'))];
    assert.equal(await c.persistScheduleDraftNow(), true);
    const key = c.getScheduleDraftStorageKey('101');
    assert.equal(c.database.get(key).expiresAt, start + c.ttl + 5 * 60_000);
    c.clock.now += 4 * 60_000;
    c.state.scheduleDraftItems[0].text = ' latest text\n ';
    assert.equal(await c.persistScheduleDraftNow(), true);
    assert.equal(c.writes, 1, 'typing does not rewrite the same image Blob');
    c.clock.now = start + c.ttl + 4 * 60_000 - 1;
    const refreshed = fixture(c.shared); refreshed.state.scheduleDraftPersistenceReady = false;
    await refreshed.restoreScheduleDraftAfterStepUpAuth();
    const recovered = refreshed.state.scheduleDraftItems[0];
    assert.equal(recovered.text, ' latest text\n ');
    assert.equal(recovered.scheduledAtValue, c.state.scheduleDraftItems[0].scheduledAtValue);
    assert.equal(recovered.files[0].file.name, 'original.png');
    assert.equal(await recovered.files[0].file.text(), 'original-bytes');
    assert.equal(refreshed.state.scheduleDraftRestoring, false);
    assert.equal(refreshed.state.scheduleDraftPersistenceReady, true);
    const other = fixture(c.shared); other.state.authStatus.user.id = '202';
    await other.restoreScheduleDraftAfterStepUpAuth(); assert.equal(other.state.scheduleDraftItems.length, 0);
    const anotherTab = fixture({ database: c.database, clock: c.clock });
    await anotherTab.restoreScheduleDraftAfterStepUpAuth(); assert.equal(anotherTab.state.scheduleDraftItems.length, 0);
    c.clock.now += 2;
    const expired = fixture(c.shared);
    await expired.restoreScheduleDraftAfterStepUpAuth(); assert.equal(expired.state.scheduleDraftItems.length, 0);
}

async function testPendingImagesAndLeaveProtection() {
    const c = fixture(); c.storeGate = deferred();
    c.state.scheduleDraftItems = [c.draft('synchronously saved before pagehide', c.media())];
    const first = c.persistScheduleDraftNow();
    assert.match(c.session.get('xops_stepup_schedule_draft:101'), /synchronously saved/);
    assert.equal(c.windowListeners.has('beforeunload'), true);
    let authSaved = false;
    const second = c.saveScheduleDraftForStepUpAuth().then(result => { authSaved = result; return result; });
    await settle(); assert.equal(authSaved, false); assert.equal(c.storeAttempts, 1);
    c.storeGate.resolve();
    assert.equal(await first, true); assert.equal(await second, true);
    assert.equal(c.writes, 1); assert.equal(c.windowListeners.has('beforeunload'), false);
    c.state.scheduleDraftItems[0].text = 'latest keystroke';
    c.rotateComposerRequestId();
    assert.ok([...c.timers.values()].some(t => t.delay === 250));
    const flushed = c.persistScheduleDraftNow();
    assert.match(c.session.get('xops_stepup_schedule_draft:101'), /latest keystroke/);
    await flushed; assert.equal(c.timers.size, 0);
}

async function testStorageFailuresPreserveInput() {
    const textOnly = fixture(); const textDraft = textOnly.draft('keep this');
    textOnly.state.scheduleDraftItems = [textDraft]; textOnly.failSession = true;
    assert.equal(await textOnly.persistScheduleDraftNow(), false);
    assert.equal(textOnly.state.scheduleDraftItems[0], textDraft);
    assert.match(textOnly.errors.at(-1), /草稿暂存失败/);
    assert.equal(textOnly.windowListeners.has('beforeunload'), true);
    const image = fixture(); const imageDraft = image.draft('recoverable text', image.media());
    image.state.scheduleDraftItems = [imageDraft]; image.failMedia = true;
    assert.equal(await image.persistScheduleDraftNow(), false);
    assert.equal(image.state.scheduleDraftItems[0], imageDraft); assert.equal(imageDraft.files.length, 1);
    assert.match(image.errors.at(-1), /正文与时间已暂存，图片暂存失败/);
    const recovered = fixture(image.shared); recovered.state.scheduleDraftPersistenceReady = false;
    await recovered.restoreScheduleDraftAfterStepUpAuth();
    assert.equal(recovered.state.scheduleDraftItems[0].text, 'recoverable text');
    assert.equal(recovered.state.scheduleDraftItems[0].files.length, 0);
    assert.match(recovered.errors.at(-1), /正文与时间已恢复，图片未能恢复/);
    assert.equal(recovered.state.scheduleDraftRestoring, false);
    assert.equal(recovered.state.scheduleDraftPersistenceReady, true);
    const saved = fixture(); saved.state.scheduleDraftItems = [saved.draft('delete persisted draft', saved.media())];
    await saved.persistScheduleDraftNow(); const key = saved.getScheduleDraftStorageKey('101');
    saved.clearScheduleComposerDraft();
    assert.equal(saved.session.has('xops_stepup_schedule_draft:101'), false);
    await saved.getStorageQueue();
    assert.equal(saved.database.has(key), false);
    assert.equal(saved.state.scheduleDraftItems[0].text, '');
    assert.equal(saved.windowListeners.has('beforeunload'), false);
}

async function testStaleRecoveryAndAuthorizationEdits() {
    const saved = fixture(); saved.state.scheduleDraftItems = [saved.draft('saved', saved.media())];
    await saved.persistScheduleDraftNow();
    const late = fixture(saved.shared); late.state.scheduleDraftPersistenceReady = false; late.storeGate = deferred();
    const restore = late.restoreScheduleDraftAfterStepUpAuth();
    await settle(); assert.equal(late.state.scheduleDraftRestoring, true);
    const newer = late.draft('new account content');
    late.state.userScopeGeneration++; late.state.authStatus.user.id = '202';
    late.state.scheduleDraftItems = [newer]; late.state.scheduleDraftRestoring = false;
    late.storeGate.resolve(); await restore;
    assert.equal(late.state.scheduleDraftItems[0], newer); assert.equal(newer.files.length, 0);

    const loggingOut = fixture(saved.shared);
    loggingOut.state.scheduleDraftPersistenceReady = false; loggingOut.storeGate = deferred();
    const logoutRestore = loggingOut.restoreScheduleDraftAfterStepUpAuth();
    await settle(); assert.equal(loggingOut.state.scheduleDraftRestoring, true);
    loggingOut.state.isLoggingOut = true;
    loggingOut.storeGate.resolve(); await logoutRestore;
    assert.equal(loggingOut.state.scheduleDraftRestoring, false, 'a pending logout must not leave the same account recovery locked');
    assert.equal(loggingOut.state.scheduleDraftPersistenceReady, true);
    assert.equal(loggingOut.state.scheduleDraftItems[0].text, 'saved');
    assert.equal(await loggingOut.state.scheduleDraftItems[0].files[0].file.text(), 'png-content');
    loggingOut.state.isLoggingOut = false; // Logout failed; the same account continues editing.
    assert.equal(loggingOut.state.scheduleDraftRestoring, false);
    assert.equal(loggingOut.state.scheduleDraftItems[0].files.length, 1);

    const edited = fixture(saved.shared); edited.state.scheduleDraftPersistenceReady = false; edited.storeGate = deferred();
    const pendingRestore = edited.restoreScheduleDraftAfterStepUpAuth();
    await settle();
    edited.state.scheduleDraftItems[0].text = 'new input while restoring';
    edited.rotateComposerRequestId(); edited.storeGate.resolve(); await pendingRestore;
    assert.equal(edited.state.scheduleDraftItems[0].text, 'new input while restoring');
    assert.equal(edited.state.scheduleDraftItems[0].files.length, 0);
    assert.equal(edited.state.scheduleDraftRestoring, false);

    const auth = fixture(); auth.state.scheduleDraftItems = [auth.draft('before auth', auth.media())];
    auth.storeGate = deferred();
    const pendingAuth = auth.triggerXPublishAuth();
    await settle(); auth.state.scheduleDraftItems[0].text = 'typed while saving'; auth.rotateComposerRequestId();
    auth.storeGate.resolve(); await pendingAuth;
    assert.equal(auth.window.location.href, 'http://localhost/');
    assert.equal(auth.state.scheduleDraftItems[0].text, 'typed while saving');
    assert.match(auth.publishErrors.at(-1), /草稿有新修改/);
    await auth.triggerXPublishAuth();
    assert.equal(auth.window.location.href, '/api/auth/x/authorize-publish');
    assert.match(auth.session.get('xops_stepup_schedule_draft:101'), /typed while saving/);
}

(async () => {
    const tests = [testNonDestructiveTransfer, testStableNodesAndDestructiveConfirmation,
        testRecoveryIsolationAndRetention, testPendingImagesAndLeaveProtection,
        testStorageFailuresPreserveInput, testStaleRecoveryAndAuthorizationEdits];
    for (const test of tests) await test();
    console.log('draft stability regression: ' + tests.length + ' production behavior groups passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
