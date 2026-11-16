const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const tests = [];
const noop = () => {};
const test = (name, run) => tests.push({ name, run });

function section(start, end) {
    const a = source.indexOf(start), b = source.indexOf(end, a);
    assert.ok(a >= 0 && b > a, 'Missing production section: ' + start);
    return source.slice(a, b);
}

const switchCode = section('    function switchTab(', '    function applyTheme(');
const initCode = section('    async function init()', '    function cleanAuthQueryParams()');
const popstateCode = section(
    '        if ("scrollRestoration" in window.history)',
    '        document.addEventListener("visibilitychange"'
);
const rowsCode = section('    function updateUserListRows(', '    function renderPeerList(');
const peerCode = section('    function renderPeerList(', '    /* ==================== 模块 3：');
const followCode = section('    function getFilteredFollowItems(', '    /* ==================== 模块 4：');

function navigationFixture(initialUrl = 'https://app.example.test/') {
    const entries = [{ url: initialUrl, state: null }];
    const writes = [];
    const listeners = new Map();
    const workspace = { dataset: {} };
    let index = 0;
    const location = {
        get href() { return entries[index].url; },
        get search() { return new URL(this.href).search; }
    };
    const history = {
        scrollRestoration: 'auto',
        get state() { return entries[index].state; },
        get length() { return entries.length; },
        replaceState(state, title, url) {
            entries[index] = { state, url };
            writes.push('replace');
        },
        pushState(state, title, url) {
            entries.splice(index + 1);
            entries.push({ state, url });
            index += 1;
            writes.push('push');
        }
    };
    const window = {
        history, location, scrollY: 0,
        scrollTo({ top }) { this.scrollY = top; },
        addEventListener(type, handler) { listeners.set(type, handler); }
    };
    const state = {
        activeTab: 'activity', tabScrollPositions: {}, report: null,
        authStatus: { loggedIn: true, user: { id: 'owner-1' } }
    };
    const ctx = {
        state, window, URL, URLSearchParams,
        document: { querySelectorAll: () => [] },
        $: () => workspace,
        isLoggedIn: () => Boolean(state.authStatus.loggedIn),
        stableUserId: (user) => user.id,
        showToast: noop, showError: (message) => assert.fail(message),
        refreshSchedulePollingState: noop,
        ensureScheduleDraftInitialized: noop, renderScheduleQuickSlots: noop,
        syncScheduleComposerStats: noop, loadScheduledTweets: async () => {},
        restoreScheduleDraftAfterStepUpAuth: async () => {},
        localStorage: { getItem: () => null, removeItem: noop },
        purgeLegacyClientBundles: noop, THEMES: [{ id: 'dark' }],
        applyTheme: noop, syncPeriodButtons: noop, syncAuthConnectionUI: noop,
        fetchServerAndAuthStatus: async () => {},
        fetchAlgoProfileStatus: async () => {},
        loadSelfReport: async () => {},
        checkinWorkspaceUsage: async () => {}
    };
    vm.createContext(ctx);
    vm.runInContext(switchCode + '\nfunction bindEvents() {\n' + popstateCode + '\n}\n' + initCode, ctx);
    return {
        ctx, state, window, writes, entries, workspace,
        back() {
            assert.ok(index > 0);
            index -= 1;
            assert.ok(listeners.has('popstate'), 'Production popstate listener must be bound');
            listeners.get('popstate')({ state: history.state });
        }
    };
}

test('module A → B → A restores each module scroll position', () => {
    const f = navigationFixture();
    f.ctx.switchTab('activity', { historyMode: 'replace' });
    f.window.scrollY = 640;
    f.ctx.switchTab('interactions');
    assert.equal(f.window.scrollY, 0);
    f.window.scrollY = 210;
    f.ctx.switchTab('activity');
    assert.equal(f.window.scrollY, 640);
    assert.equal(f.state.tabScrollPositions.interactions, 210);
    assert.equal(f.workspace.dataset.activeTab, 'activity');
    assert.equal(f.window.history.length, 3);
    assert.equal(f.entries[1].state.xopsScrollY, 210);
});

test('history none restores a tab without adding or rewriting an entry', () => {
    const f = navigationFixture();
    f.ctx.switchTab('activity', { historyMode: 'replace' });
    f.ctx.switchTab('interactions');
    const writes = f.writes.length;
    f.ctx.switchTab('follow-audit', { historyMode: 'none', scrollTop: 320 });
    assert.equal(f.window.scrollY, 320);
    assert.equal(f.state.activeTab, 'follow-audit');
    assert.equal(f.window.history.length, 2);
    assert.equal(f.writes.length, writes);
});

test('browser back uses the production popstate listener and restores scroll without pushing', () => {
    const f = navigationFixture();
    f.ctx.bindEvents();
    assert.equal(f.window.history.scrollRestoration, 'manual');
    f.ctx.switchTab('activity', { historyMode: 'replace' });
    f.window.scrollY = 510;
    f.ctx.switchTab('interactions');
    f.window.scrollY = 230;
    f.ctx.switchTab('follow-audit');
    f.window.scrollY = 350;
    const writes = f.writes.length;
    f.back();
    assert.equal(f.state.activeTab, 'interactions');
    assert.equal(f.window.scrollY, 230);
    assert.equal(f.state.tabScrollPositions['follow-audit'], 350);
    f.back();
    assert.equal(f.state.activeTab, 'activity');
    assert.equal(f.window.scrollY, 510);
    assert.equal(f.writes.length, writes);
    assert.equal(f.window.history.length, 3);
});

test('init replaces the first history entry for default and deep linked tabs', async () => {
    for (const [query, expected] of [
        ['', 'activity'],
        ['?tab=activity', 'activity'],
        ['?tab=interactions', 'interactions'],
        ['?tab=schedule', 'schedule']
    ]) {
        const f = navigationFixture('https://app.example.test/' + query);
        await f.ctx.init();
        assert.equal(f.window.history.length, 1, 'No extra initial entry for ' + (query || 'default'));
        assert.equal(f.state.activeTab, expected);
        assert.equal(f.window.history.state?.xopsTab, expected, 'Initial tab must be recorded');
        assert.equal(f.window.history.state?.xopsOwnerId, 'owner-1');
        assert.equal(f.writes.includes('push'), false);
        assert.equal(f.writes.filter((kind) => kind === 'replace').length, 1);
    }
});

// Only the direct div rows and a/button controls used below are modeled.
// Renderer markup is observed separately; this is not an HTML or DOM implementation.
function rowFixture() {
    const document = { activeElement: null };
    const list = {
        children: [],
        get firstElementChild() { return this.children[0] || null; },
        contains(node) { return this.children.includes(node) || this.children.includes(node?.row); },
        insertBefore(node, cursor) {
            if (node.parent) node.remove();
            const at = cursor ? this.children.indexOf(cursor) : this.children.length;
            assert.ok(at >= 0, 'Insertion cursor must be a current row');
            this.children.splice(at, 0, node);
            node.parent = this;
        }
    };
    function makeRow(key, body, markup) {
        const row = {
            key, outerHTML: markup, parent: null, controls: [],
            getAttribute: (name) => name === 'data-list-key' ? key : null,
            closest: () => row,
            querySelectorAll: () => row.controls,
            querySelector: (selector) => selector === 'button'
                ? row.controls.find((control) => control.tag === 'button') || null
                : row.controls[0] || null,
            get nextElementSibling() {
                return this.parent?.children[this.parent.children.indexOf(this) + 1] || null;
            },
            remove() {
                if (document.activeElement === this || document.activeElement?.row === this) {
                    document.activeElement = null;
                }
                this.parent.children.splice(this.parent.children.indexOf(this), 1);
                this.parent = null;
            },
            focus(options) { document.activeElement = this; this.focusOptions = options; }
        };
        for (const match of body.matchAll(/<(a|button)(?:\s[^>]*)?>([\s\S]*?)<\/\1>/g)) {
            row.controls.push({
                row, tag: match[1], textContent: match[2],
                closest: () => row,
                focus(options) { document.activeElement = this; this.focusOptions = options; }
            });
        }
        return row;
    }
    document.createElement = (tag) => {
        assert.equal(tag, 'template');
        return {
            content: { children: [] },
            set innerHTML(markup) {
                this.content.children = Array.from(
                    markup.matchAll(/<div data-list-key="([^"]+)">([\s\S]*?)<\/div>/g),
                    (match) => makeRow(match[1], match[2], match[0])
                );
                assert.equal(this.content.children.map((row) => row.outerHTML).join(''), markup,
                    'Keep fixture markup inside its explicit direct-row contract');
            }
        };
    };
    const ctx = vm.createContext({ document });
    vm.runInContext(rowsCode, ctx);
    return { document, list, update: (markup) => ctx.updateUserListRows(list, markup) };
}

const aliceRow = '<div data-list-key="alice"><a>Alice</a><button>Edit Alice</button></div>';
const bobRow = '<div data-list-key="bob"><a>Bob</a><button>Edit Bob</button></div>';
const moreRow = (remaining) => '<div data-list-key="more"><button>加载更多（余 ' + remaining + '）</button></div>';

test('keyed append preserves existing rows, the load-more button, and its focus', () => {
    const f = rowFixture();
    f.update(aliceRow + moreRow(2));
    const alice = f.list.children[0];
    const more = f.list.children[1];
    const button = more.controls[0];
    button.focus();
    f.update(aliceRow + bobRow + moreRow(1));
    assert.equal(f.list.children[0], alice);
    assert.equal(f.list.children[2], more);
    assert.equal(f.list.children[2].controls[0], button);
    assert.equal(button.textContent, '加载更多（余 1）');
    assert.equal(f.document.activeElement, button);
    assert.equal(f.list.children.length, 3);
});

test('a changed keyed row restores focus to the corresponding control without scrolling', () => {
    const f = rowFixture();
    f.update(aliceRow + bobRow);
    const before = f.list.children[0];
    before.controls[1].focus();
    f.update(aliceRow.replace('Edit Alice', 'Save Alice') + bobRow);
    const after = f.list.children[0];
    assert.notEqual(after, before);
    assert.equal(f.document.activeElement, after.controls[1]);
    assert.equal(f.document.activeElement.textContent, 'Save Alice');
    assert.equal(f.document.activeElement.focusOptions.preventScroll, true);
    assert.equal(f.list.children.length, 2);
});

function clickControl(dataset = {}, textContent = '') {
    const handlers = new Map();
    return {
        dataset, textContent, onclick: null, onkeydown: null,
        querySelectorAll: () => [],
        addEventListener(type, handler) {
            if (!handlers.has(type)) handlers.set(type, []);
            handlers.get(type).push(handler);
        },
        click() {
            const event = {
                target: this, stops: 0, preventDefault: noop,
                stopPropagation() { this.stops += 1; }
            };
            const registered = [...(handlers.get('click') || [])];
            this.onclick?.(event);
            registered.forEach((handler) => handler(event));
            return event;
        }
    };
}

function renderingFixture() {
    const markup = {}, updates = {}, toasts = [], saved = [];
    const peerRow = clickControl({ peerHandle: 'alice' });
    const peerLink = clickControl();
    const followMark = clickControl({ wlToggle: 'alice' });
    const followLink = clickControl({ openFollow: 'alice', followAction: 'manage' }, 'Unfollow ↗');
    const peerList = {
        querySelectorAll: (selector) => selector === '.peer-row[data-peer-handle]' ? [peerRow] : [peerLink]
    };
    const followList = {
        querySelectorAll: (selector) => selector === '[data-wl-toggle]' ? [followMark] : [followLink]
    };
    const elements = new Map([
        ['peerInteractionList', peerList], ['followAuditList', followList],
        ['loadMorePeersBtn', clickControl()], ['loadMoreFollowBtn', clickControl()],
        ['followStateTabs', { querySelectorAll: () => [clickControl(), clickControl(), clickControl()] }]
    ]);
    const state = {
        peerRelFilter: 'MUTUAL', peerSortBy: 'intimacy', peerSearchQuery: '',
        peerRenderLimit: 1, expandedPeerUsername: null, report: null,
        followStateTab: 'NOT_FOLLOWING_BACK', unfollowCategory: 'ALL',
        followSortBy: 'intimacy', followSearchQuery: '', followRenderLimit: 1,
        whitelistSet: new Set(), openedFollowSet: new Set(['alice'])
    };
    const ctx = {
        state, LIST_LOAD_STEP: 1,
        $: (id) => {
            if (id === 'loadMorePeersBtn' && !markup.peer?.includes('id="loadMorePeersBtn"')) return null;
            if (id === 'loadMoreFollowBtn' && !markup.follow?.includes('id="loadMoreFollowBtn"')) return null;
            if (!elements.has(id)) elements.set(id, clickControl());
            return elements.get(id);
        },
        updateUserListRows: (list, html) => {
            const kind = list === peerList ? 'peer' : 'follow';
            markup[kind] = html;
            updates[kind] = (updates[kind] || 0) + 1;
        },
        escapeHtml: (value) => String(value ?? ''),
        avatarHtml: () => '', verifiedSvgHtml: () => '',
        followRelationText: () => '互关', buildPeerDetailHtml: () => '',
        getPeerStatusMeta: () => ({
            primary: false, label: '互相评论', tone: 'green',
            actionUrl: 'https://x.com/alice', actionText: '主页'
        }),
        fmtNum: (value) => String(value || 0),
        safeHttpUrl: (url, fallback) => url || fallback,
        showToast: (message) => toasts.push(message),
        saveWhitelistSet: () => saved.push('whitelist'),
        saveOpenedFollowSet: () => saved.push('opened'),
        renderRightSidebar: noop
    };
    vm.createContext(ctx);
    vm.runInContext(peerCode + '\n' + followCode, ctx);
    const peers = ['alice', 'bob', 'carol', 'dave'].map((username, index) => ({
        peer: { username, displayName: username, verified: index === 1 },
        reciprocityCode: 'MUTUAL', intimacyScore: 90 - index * 20,
        myRepliesToPeer: index === 1 ? 100 : 1, peerRepliesToMe: 1
    }));
    const follows = peers.map((item, index) => ({
        user: { ...item.peer, followersCount: 100 + index },
        intimacyScore: item.intimacyScore, relationshipOrder: index,
        relationState: 'NOT_FOLLOWING_BACK', auditCategory: 'ONE_WAY_EFFORT',
        auditLabel: '仅单向互动', myRepliesToThem: 1, theirRepliesToMe: 0
    }));
    const report = {
        profile: { username: 'owner' }, periodLabel: '最近 7 天',
        interactions: { peerStats: peers },
        followAudit: {
            notFollowingBackList: follows, notFollowingBackCount: follows.length,
            fansNotFollowedBackList: [], fansNotFollowedBackCount: 0,
            mutualList: [], mutualCount: 0
        }
    };
    state.report = report;
    return { ctx, state, report, markup, updates, toasts, saved, elements, peerRow, peerLink, followMark, followLink };
}

test('intimacy is primary even when the lower-score account is verified and more active', () => {
    const f = renderingFixture();
    f.state.peerRenderLimit = 20;
    f.ctx.renderPeerList(f.report);
    assert.ok(f.markup.peer.indexOf('data-list-key="alice"') < f.markup.peer.indexOf('data-list-key="bob"'));
    assert.ok(f.markup.peer.includes('data-list-key="bob"'));
});

test('follow tabs distinguish verified counts from unfinished lists in both directions', () => {
    const f = renderingFixture();
    const buttons = [clickControl(), clickControl(), clickControl()];
    f.elements.set('followStateTabs', { querySelectorAll: () => buttons });
    f.ctx.emptyStateHtml = message => message;
    Object.assign(f.report.followAudit, {
        notFollowingBackList: [], notFollowingBackCount: 0, mutualCount: 4,
        followersSampleTruncated: true, followingsSampleTruncated: true,
        unknownFollowBackCount: 2, unknownFollowingCount: 3
    });
    f.ctx.renderFollowAuditPanel(f.report);
    assert.deepEqual(buttons.map(b => b.textContent), ['TA未回关 待核验', '我未回关 待核验', '互关 ≥4']);
    assert.match(f.elements.get('followAuditList').innerHTML, /2 人/);
    f.state.followStateTab = 'FAN_ONLY';
    f.ctx.renderFollowAuditPanel(f.report);
    assert.match(f.elements.get('followAuditList').innerHTML, /3 人/);
    Object.assign(f.report.followAudit, { followersSampleTruncated: true, followingsSampleTruncated: false,
        unknownFollowBackCount: 0, unknownFollowingCount: 0 });
    f.ctx.renderFollowAuditPanel(f.report);
    assert.deepEqual(buttons.map(b => b.textContent), ['TA未回关 0', '我未回关 未确定', '互关 4']);
    Object.assign(f.report.followAudit, { followersSampleTruncated: false, followingsSampleTruncated: false,
        unknownFollowBackCount: 0, unknownFollowingCount: 0 });
    f.ctx.renderFollowAuditPanel(f.report);
    assert.deepEqual(buttons.map(b => b.textContent), ['TA未回关 0', '我未回关 0', '互关 4']);
});

test('real follow audit labels survive a stale opened-account state', () => {
    const f = renderingFixture();
    f.ctx.renderFollowAuditPanel(f.report);
    assert.ok(f.markup.follow.includes('>仅单向互动</span>'));
    assert.ok(f.markup.follow.includes('data-follow-action="manage"'));
    assert.equal(f.markup.follow.includes('待确认'), false);
    f.followLink.click();
    assert.deepEqual(f.saved, []);
    assert.ok(f.markup.follow.includes('>仅单向互动</span>'));
});

test('re-rendering a preserved peer row or external link does not duplicate its click handler', () => {
    const f = renderingFixture();
    for (let i = 0; i < 3; i += 1) f.ctx.renderPeerList(f.report);
    const renders = f.updates.peer;
    f.peerRow.click();
    assert.equal(f.updates.peer, renders + 1);
    assert.equal(f.state.expandedPeerUsername, 'alice');
    assert.equal(f.peerLink.click().stops, 1);
});

test('re-rendering the peer load-more control advances the limit only once per click', () => {
    const f = renderingFixture();
    for (let i = 0; i < 3; i += 1) f.ctx.renderPeerList(f.report);
    const renders = f.updates.peer;
    f.elements.get('loadMorePeersBtn').click();
    assert.equal(f.state.peerRenderLimit, 2);
    assert.equal(f.updates.peer, renders + 1);
});

test('re-rendering preserved follow controls performs each user action once', () => {
    const f = renderingFixture();
    for (let i = 0; i < 3; i += 1) f.ctx.renderFollowAuditPanel(f.report);
    f.followLink.click();
    assert.equal(f.toasts.length, 1);
    const renders = f.updates.follow;
    f.followMark.click();
    assert.equal(f.state.whitelistSet.has('alice'), true);
    assert.deepEqual(f.saved, ['whitelist']);
    assert.equal(f.updates.follow, renders + 1);
    assert.equal(f.toasts.length, 2);
});

test('re-rendering the follow load-more control advances the limit only once per click', () => {
    const f = renderingFixture();
    for (let i = 0; i < 3; i += 1) f.ctx.renderFollowAuditPanel(f.report);
    const renders = f.updates.follow;
    f.elements.get('loadMoreFollowBtn').click();
    assert.equal(f.state.followRenderLimit, 2);
    assert.equal(f.updates.follow, renders + 1);
});

(async () => {
    for (const { name, run } of tests) {
        await run();
        console.log('PASS ' + name);
    }
    console.log('Navigation/list stability: ' + tests.length + ' passed');
})().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
