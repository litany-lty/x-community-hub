"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/app.js"), "utf8");

function section(start, end) {
    const a = source.indexOf(start);
    const b = source.indexOf(end, a + start.length);
    assert.ok(a >= 0 && b > a, start);
    return source.slice(a, b);
}

test("an offline author card follows the verified owner after a rename", () => {
    const state = {authStatus: {loggedIn: true, ownerAllPermissions: false, user: {id: "999", username: "elidlicom"}},
        report: {profile: {id: "999", username: "elidlicom", displayName: "Wrong person"}}};
    const card = vm.runInNewContext(section("    function isOwnerAllPermissions(", "    function isExplicitNonOwnerSession(")
        + section("    function buildFallbackAuthorCardData(", "    const modalFocusHistoryStack")
        + "\nbuildFallbackAuthorCardData", {state, AUTHOR_COST_STORY: "", AUTHOR_DEFAULT_BIO: "", AUTHOR_BANNER_URL: "",
            AUTHOR_ARCHIVE_ACCOUNT_ID: "2095392916734976000"});
    assert.equal(card().selfIsAuthor, false);
    assert.notEqual(card().authorProfile.displayName, "Wrong person");
    assert.match(card().authorFollowIntentUrl, /user_id=2095392916734976000$/);
    state.accessPolicy = {authorAccountId: "101", authorHandle: "new_author_name"};
    assert.match(card().modalMessage, /@new_author_name/);
    assert.equal(card().authorProfile.username, "new_author_name");
    state.authStatus = {loggedIn: true, ownerAllPermissions: true, user: {id: "101", username: "new_author_name"}};
    assert.equal(card().selfIsAuthor, true);
    assert.equal(card().authorProfile.username, "new_author_name");
    assert.match(card().authorProfileUrl, /\/101$/);
    assert.notEqual(card().authorProfile.displayName, "Wrong person");
});

test("compact share publishes the counts from the share card", () => {
    const share = vm.runInNewContext(section("    function getEffectiveShareTweetText(", "    function ensureCanvasAvatarLoaded(")
        + "\ngetEffectiveShareTweetText", {state: {}, AUTHOR_ARCHIVE_ACCOUNT_ID: "2095392916734976000", hasMetricSample: () => true,
        computeWeightedTweetLength: () => 300, followRateDisplay: () => ({label: "", rate: "--"})});
    const report = {profile: {username: "example"}, activity: {originalPosts: 1, quotes: 2, replies: 9},
        innerCircle: {shareCard: {periodLabel: "24h", shareTweetText: "周报".repeat(150), originalPosts: 3,
            replies: 9, replyToPostRatio: 3}}};
    assert.match(share(report), /发帖 3.*回复 9（3\.0x）/);
    Object.assign(report.innerCircle.shareCard, {originalPosts: 0, replies: 0});
    assert.match(share(report), /发帖 0.*回复 0/);
});

test("both share templates attribute the stable owner and omit self-attribution after a rename", () => {
    const state = {accessPolicy: {authorAccountId: "101", authorHandle: "renamed_author"}};
    let length;
    const share = vm.runInNewContext(section("    function getEffectiveShareTweetText(", "    function ensureCanvasAvatarLoaded(")
        + "\ngetEffectiveShareTweetText", {state, AUTHOR_ARCHIVE_ACCOUNT_ID: "2095392916734976000", hasMetricSample: () => true,
        computeWeightedTweetLength: () => length, followRateDisplay: () => ({label: "", rate: "--"})});
    for (length of [120, 350]) {
        const report = {profile: {id: "999", username: "elidlicom"}, activity: {},
            innerCircle: {shareCard: {periodLabel: "24h", shareTweetText: "📊 我的 X 社区运营战报（@elidlicom｜最近 24 小时）"}}};
        assert.match(share(report), /via https:\/\/x\.com\/i\/user\/101/);
        assert.doesNotMatch(share(report), /via @elidlicom/);
        report.profile = {id: "101", username: "renamed_author"};
        assert.doesNotMatch(share(report), /via /);
    }
});

test("unknown ledger values stay distinguishable from a confirmed zero", () => {
    const budget = vm.runInNewContext("(dashboard) => {" +
        section('        const budgetEl = $("assistantBudgetStatus");', '        $("assistantHistoryStatus").innerHTML') +
        "return budgetEl.innerHTML;}", {$: () => ({}), assistantKeyValueRows: data => data});
    const data = {ledgerState: "not_loaded", hourlyUsed: null, hourlyBudget: 120,
        dailyUsed: null, dailyBudget: 800, dailyUsdReserved: null, dailyUsdBudget: 5, budgetRejectedCalls: 0};
    const unknown = budget({upstreamBudget: data});
    assert.equal(unknown["本小时请求"], "-- / 120");
    assert.equal(unknown["今日请求"], "-- / 800");
    assert.equal(unknown["今日预留"], "-- / $5.00");
    const zero = budget({upstreamBudget: {...data, hourlyUsed: 0, dailyUsdReserved: 0}});
    assert.equal(zero["本小时请求"], "0 / 120");
    assert.equal(zero["今日预留"], "$0.00 / $5.00");
});

test("publish callback describes only capabilities returned by the status endpoint", () => {
    const callback = section('        if (authResult === "publish_ok") {', ' else if (authResult === "ok") {');
    function notice(authStatus) {
        let message;
        vm.runInNewContext(callback, {state: {authStatus}, authResult: "publish_ok",
            setPublishAuthError() {}, cleanAuthQueryParams() {}, showToast: value => { message = value; }});
        return message;
    }
    assert.match(notice({writeAuthorized: true, mediaWriteAuthorized: true, offlineRefreshable: true}), /刷新已启用/);
    assert.match(notice({writeAuthorized: true, mediaWriteAuthorized: false, offlineRefreshable: false}), /文本/);
    assert.doesNotMatch(notice({writeAuthorized: true}), /刷新已启用/);
    assert.doesNotMatch(notice({writeAuthorized: false, offlineRefreshable: true}), /已开通|刷新已启用/);
});

test("assistant receipt shows the actual scheduled time and prioritizes a definitive failure", () => {
    const label = vm.runInNewContext("(publication) => {" +
        section("            const pubFmt =", '            return `<div class="assistant-publication-row">') +
        "return pubTime;}", {formatAssistantDateTime: ms => String(ms)});
    assert.equal(label({status: "SCHEDULED", jobId: "j", scheduledAtEpochMs: 24680}), "排期于 24680");
    assert.equal(label({status: "SCHEDULED", scheduledAtEpochMs: 24680, scheduledAt: 12345}), "排期于 24680");
    assert.equal(label({status: "SCHEDULED", scheduledAt: 12345}), "排期于 12345");
    assert.equal(label({status: "PUBLISHED", publishedAt: 555, scheduledAtEpochMs: 222}), "发布于 555");
    assert.equal(label({status: "FAILED", error: "重复排期", scheduledAtEpochMs: 222}), "重复排期");
    assert.doesNotMatch(label({status: "SENDING", source: "DIRECT_PUBLISH"}), /排期/);
});

test("typing in an already scheduled draft can send the latest editor text after an in-flight save", async () => {
    const draft = {id: "draft", version: 2, text: "原正文"};
    const publication = {id: "intent", draftId: draft.id, draftVersion: 2, queueUpdatedAt: 10};
    const state = {assistantDashboard: {drafts: [draft], publications: [publication]}};
    const button = {hidden: true, dataset: {assistantUpdateScheduled: "intent", publicationDraftId: "draft"}};
    const box = {dataset: {draftId: draft.id, draftVersion: "2"}};
    const editor = {value: "编辑第一版", closest: () => box};
    const elements = {assistantDraftEditor: editor};
    let resolveFirst;
    const saved = new Map([[draft.version, draft.text]]);
    const ctx = vm.createContext({
        state, $: id => elements[id] || (elements[id] = {}), captureUserScope: () => () => true,
        setTimeout: () => 1, clearTimeout() {}, syncScheduleDraftUnloadProtection() {},
        assistantDraftLengthText: () => "", assistantDraftNumberChangesText: () => "", newClientRequestId: () => "op",
        document: {querySelectorAll: () => [button]}, event: {target: {closest: () => button}},
        contentAssistantRequest: async (_, body) => {
            if (body.version === 2) await new Promise(resolve => { resolveFirst = resolve; });
            assert.equal(body.version, Math.max(...saved.keys()));
            saved.set(body.version + 1, body.text);
            return {id: "draft", version: body.version + 1, text: body.text};
        },
        runAssistantMutation: (_, path, payload) => {
            assert.equal(path, "/drafts/draft/update-scheduled");
            ctx.queuedText = saved.get(payload.version);
        }
    });
    vm.runInContext(section("    async function saveAssistantDraftEditor(", "    async function sendAssistantDraftToSchedule("), ctx);
    ctx.queueAssistantDraftSave();
    assert.equal(button.hidden, false);
    const autosave = ctx.saveAssistantDraftEditor();
    const handler = section("            const updateScheduled = event.target.closest(", "\n        });");
    const updating = vm.runInContext("(async () => {" + handler + "})()", ctx);
    editor.value = "提交排期时的最新正文";
    ctx.queueAssistantDraftSave();
    assert.equal(ctx.queuedText, undefined);
    resolveFirst();
    await Promise.all([updating, autosave]);
    assert.equal(ctx.queuedText, "提交排期时的最新正文");
    assert.equal(state.assistantDashboard.drafts[0].version, 4);
});
