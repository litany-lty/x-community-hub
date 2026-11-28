const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const vm = require("node:vm");
const { test } = require("node:test");
const path = require("node:path");

const app = readFileSync(path.join(__dirname, "../../main/resources/static/app.js"), "utf8");
function fragment(start, end) {
    const a = app.indexOf(start);
    const b = app.indexOf(end, a + 1);
    assert.ok(a >= 0 && b > a);
    return app.slice(a, b);
}
const readBody = fragment("    function getRewriteItemText(", "    function buildRepostItemComposedText(");
const preview = fragment("    async function previewSingleRewriteItem(", "    function preservePublishedQuote(");
const handoff = fragment("            const rwSchedBtn = event.target?.closest?.(", "            const expandBtn = ");

function fixture() {
    let now = Date.parse("2026-11-28T15:59:59.999Z");
    const material = { id: "101", publishedAt: now };
    const row = { material, rewrite: { status: "READY", candidate: true } };
    const repostPreviewState = {
        requestToken: 5, sourceKey: "source", owner: { accountId: "A" }, items: [row],
        editedRewrites: new Map(), rewritingIds: new Set(), filterMode: "REWRITE"
    };
    let resolvePreview;
    const transfers = [], requests = [];
    const ctx = vm.createContext({
        Date: class extends Date { static now() { return now; } },
        repostPreviewState, state: { authStatus: { user: { id: "A" } } },
        $: () => null, renderRepostPreviewTimeline() {}, showToast() {}, closeRepostPreviewModal() {},
        parseGenerationTargetSpec: () => ({}), stableUserId: (user) => String(user?.id || ""),
        sendTextDraftToSchedule: (...args) => { transfers.push(args); return true; },
        contentAssistantRequest: (path, body) => new Promise(resolve => { requests.push({path, body}); resolvePreview = resolve; })
    });
    vm.runInContext(readBody + preview + "\nfunction transfer(event) {" + handoff + "}", ctx);
    const click = () => ctx.transfer({ target: { closest: () => ({ dataset: { rewriteScheduleItem: "101" } }) } });
    return { row, material, state: ctx.state, preview: repostPreviewState, ctx, transfers, click, requests,
        advance: ms => { now += ms; },
        respond: (result) => resolvePreview({ material, rewrite: result }) };
}

for (const status of ["PUBLISHED", "UNCERTAIN", "SENDING"]) {
    test("a late rewrite cannot replace " + status + " body or receipt", async () => {
        const f = fixture();
        const pending = f.ctx.previewSingleRewriteItem("101", false, 5);
        const final = { status, persistedStatus: status, recordVersion: 6, savedDraftVersion: 6, rewrittenText: "Actual final B", candidate: status === "PUBLISHED",
            publishedTweetUrl: status === "PUBLISHED" ? "https://x.com/i/status/90123" : "" };
        f.row.rewrite = final;
        f.preview.editedRewrites.set("101", "obsolete edit");
        f.respond({ status: "EVALUATED", candidate: true, rewrittenText: "Old preview A", publishedTweetUrl: "", persistedStatus: "" });
        await pending;
        assert.equal(f.row.rewrite, final);
        assert.equal(f.ctx.getRewriteItemText("101"), "Actual final B");
        if (status === "PUBLISHED") {
            f.click();
            assert.equal(f.transfers[0][0], "Actual final B");
            assert.equal(f.transfers[0][3].recordVersion, 6);
        }
    });
}

test("a stale source card cannot transfer its former owner's draft", () => {
    const f = fixture();
    f.row.rewrite = { candidate: true, rewrittenText: "A retained final", recordVersion: 3, savedDraftVersion: 3 };
    f.state.authStatus.user = { id: "B" };
    assert.equal(f.ctx.getRewriteItemText("101"), "");
    f.click();
    assert.equal(f.transfers.length, 0);
    f.preview.owner = { accountId: "B" };
    f.row.rewrite = { candidate: true, rewrittenText: "B's independent draft", recordVersion: 2, savedDraftVersion: 2 };
    f.click();
    assert.equal(f.transfers[0][0], "B's independent draft");
    assert.deepEqual({ ...f.transfers[0][3] }, { ownerAccountId: "B", materialId: "101", recordVersion: 2 });
});

test("an edited unsent preview keeps its server-issued source reference", () => {
    const f = fixture();
    f.row.rewrite = { candidate: true, status: "EVALUATED", savedDraftVersion: 0, previewId: "A:101:2:example", rewrittenText: "New preview" };
    f.preview.editedRewrites.set("101", "Edited preview");
    f.click();
    assert.equal(f.transfers[0][0], "Edited preview");
    assert.deepEqual({ ...f.transfers[0][3] }, { ownerAccountId: "A", materialId: "101", recordVersion: 0, previewId: "A:101:2:example" });
});

test("a per-post preview keeps its rank in the source timeline", async () => {
    const f = fixture();
    f.row.rewrite.rank = 2;
    const pending = f.ctx.previewSingleRewriteItem("101", false, 5);
    assert.equal(f.requests[0].body.materialId, "101");
    f.respond({ status: "EVALUATED", candidate: true, rank: 0, rewrittenText: "Preview" });
    await pending;
    assert.equal(f.row.rewrite.rank, 2);
});

test("removing a candidate and promoting it back shifts the neighboring server ranks, including gaps", async () => {
    const f = fixture();
    f.row.rewrite.rank = 2;
    const neighbors = [1, 3, 6].map(rank => ({material: {id: "p" + rank, publishedAt: f.material.publishedAt},
        rewrite: {candidate: true, rank}}));
    f.preview.items.push(...neighbors);
    f.preview.editedRewrites.set("p3", "正在编辑");
    const first = f.ctx.previewSingleRewriteItem("101", false, 5);
    f.respond({status: "EVALUATED", candidate: false, evaluated: true, suitable: false, manualReviewPreview: true,
        rewrittenText: "Review", rank: 0, previewId: "proof"});
    await first;
    assert.deepEqual(neighbors.map(item => item.rewrite.rank), [1, 2, 5]);
    const next = f.ctx.previewSingleRewriteItem("101", true, 5);
    f.respond({status: "EVALUATED", candidate: true, evaluated: true, suitable: true, rewrittenText: "Ready", rank: 1});
    await next;
    assert.deepEqual(neighbors.map(item => item.rewrite.rank), [2, 3, 6]);
    assert.equal(f.row.rewrite.rank, 1);
    assert.equal(f.preview.editedRewrites.get("p3"), "正在编辑");
});

test("a low-scoring manual draft remains editable on reopen and transfers with its own proof", async () => {
    const f = fixture();
    f.row.rewrite.rank = 2;
    const pending = f.ctx.previewSingleRewriteItem("101", false, 5);
    f.respond({status: "EVALUATED", candidate: false, evaluated: true, suitable: false, manualReviewPreview: true,
        rewrittenText: "Draft for manual review", rank: 0, previewId: "review-proof"});
    await pending;
    assert.equal(f.row.rewrite.rank, 0);
    const reopened = fixture();
    reopened.row.rewrite = structuredClone(f.row.rewrite);
    reopened.preview.editedRewrites.set("101", "Edited after reopening");
    reopened.click();
    assert.equal(reopened.transfers[0][0], "Edited after reopening");
    assert.equal(reopened.transfers[0][3].previewId, "review-proof");
    delete reopened.row.rewrite.previewId;
    reopened.click();
    assert.equal(reopened.transfers.length, 1);
    const regen = reopened.ctx.previewSingleRewriteItem("101", true, 5);
    assert.equal(reopened.requests[0].body.forceRegenerate, true);
    reopened.respond({status: "EVALUATED", candidate: true, rewrittenText: "Improved draft", previewId: "fresh-proof"});
    await regen;
    assert.equal(reopened.ctx.getRewriteItemText("101"), "Improved draft");
});

test("a late manual draft cannot replace an original corrected while generation was in flight", async () => {
    const f = fixture();
    f.row.material.sourceFingerprint = "first";
    const pending = f.ctx.previewSingleRewriteItem("101", false, 5);
    f.row.material = {...f.material, sourceFingerprint: "corrected"};
    f.row.rewrite = {candidate: false, status: "INELIGIBLE"};
    f.respond({status: "EVALUATED", candidate: false, manualReviewPreview: true, previewId: "stale", rewrittenText: "old"});
    await pending;
    assert.equal(f.row.material.sourceFingerprint, "corrected");
    assert.equal(f.row.rewrite.status, "INELIGIBLE");
});

test("a preview left open across Beijing midnight cannot regenerate, open X, or transfer yesterday's draft", async () => {
    const f = fixture();
    f.row.rewrite.rewrittenText = "Draft from yesterday";
    f.preview.editedRewrites.set("101", "My edit");
    f.advance(1);
    // A displayed card may still receive a click before a background tab's timer fires.
    for (const force of [false, true]) {
        void f.ctx.previewSingleRewriteItem("101", force, 5);
        assert.equal(f.requests.length, 0);
    }
    f.click();
    assert.equal(f.transfers.length, 0);
    let cancelled = false;
    const controls = fragment('document.getElementById("repostPreviewTimelineList")?.addEventListener("click",', "            const expandBtn = ");
    const bind = controls + "});";
    let handleClick;
    f.ctx.document = {getElementById: () => ({addEventListener: (_event, fn) => {handleClick = fn;}})};
    vm.runInContext(bind, f.ctx);
    await handleClick({ target: {closest: s => s.includes("data-rewrite-intent-link") ? {dataset: {rewriteIntentLink: "101"}} : null},
        preventDefault: () => {cancelled = true;} });
    assert.equal(cancelled, true);
});

test("an open rewrite timeline refreshes at Beijing midnight without evaluating quotes and cancels its timer on close", async () => {
    const f = fixture();
    const modal = {hidden: true};
    const timers = new Map();
    const paths = [];
    Object.assign(f.preview, {pendingQuotes: new Map(), expandedIds: new Set(), evaluatingIds: new Set()});
    Object.assign(f.ctx, {
        document: {getElementById: id => id === "repostPreviewModal" ? modal : null},
        $: id => ({repostPreviewModal: modal, repostGenTargetSelect: {value: "selected-model"}, repostGenEffortSelect: {value: "high"},
            repostPolishEnabledCheck: {checked: true}, repostPolishPromptInput: {value: "editor preferences"}})[id] || null,
        parseGenerationTargetSpec: (model, effort) => ({generationProvider: "test", generationModel: model + " " + effort}),
        syncBodyModalLock() {}, onModalOpened() {}, onModalClosed() {}, renderRepostPreviewHeader() {}, renderRepostPreviewSubbar() {},
        resolveDualCdView: () => ({}), quoteAttemptKey: m => m.id,
        clearTimeout: id => timers.delete(id),
        setTimeout(fn, delay) { const id = Symbol(); timers.set(id, {fn, delay}); return id; },
        contentAssistantRequest: async (path, body) => {
            paths.push([path, body.materialId, body.rewriteToday, body.generationModel, body.postPolishPrompt]);
            return {items: [{material: f.material, decision: {}, rewrite: {candidate: true}}]};
        }
    });
    vm.runInContext(fragment("    async function loadAuthorTimelinePreview(", "    function assistantStatusLabel("), f.ctx);
    f.ctx.openRepostPreviewModal("source", {materialId: "101", filterMode: "REWRITE"});
    await Promise.resolve();
    const [id, timer] = [...timers.entries()][0] || [];
    assert.equal(timer?.delay, 1);
    timers.delete(id);
    f.advance(1);
    timer.fn();
    await Promise.resolve();
    const expected = ["/sources/source/timeline", "101", true, "selected-model high", "editor preferences"];
    assert.deepEqual(paths, [expected, expected]);
    f.ctx.closeRepostPreviewModal();
    assert.equal(timers.size, 0);
    f.ctx.openRepostPreviewModal("source", {materialId: "101"});
    await Promise.resolve();
    assert.equal(paths[2][2], undefined, "opening the ordinary author timeline leaves rewrite collection alone");
    let clickFilter;
    f.ctx.document.getElementById = id => id === "repostPreviewFilterBar" ? {addEventListener: (_type, fn) => {clickFilter=fn;}} : modal;
    vm.runInContext(fragment('        document.getElementById("repostPreviewFilterBar")?.addEventListener("click",', '        document.getElementById("repostPreviewEvalRestBtn")'), f.ctx);
    clickFilter({target: {closest: () => ({dataset: {repostFilter: "REWRITE"}})}});
    await Promise.resolve();
    assert.deepEqual(paths[3], expected, "first entering today’s rewrites from the ordinary timeline collects today's originals");
    f.ctx.closeRepostPreviewModal();
});

test("a failed change to automatic publishing restores the saved switch state", async () => {
    const checkbox = {checked: true, disabled: false, dataset: {sourceAutorewriteToggle: "source"}};
    let handleChange, finish;
    const list = {addEventListener: (_event, fn) => { handleChange = fn; }};
    const ctx = vm.createContext({$: () => list, document: {activeElement: null},
        state: {assistantDashboard: {sources: [{id: "source", autoRepostEnabled: true, autoRewriteEnabled: false}]}},
        runAssistantMutation: () => new Promise(resolve => {finish = resolve;}), showToast() {}});
    vm.runInContext(fragment('        $("assistantSourcesList")?.addEventListener("change"', '        $("assistantSourcesList")?.addEventListener("click"'), ctx);
    const pending = handleChange({target: {closest: selector => selector.includes("autorewrite-toggle") ? checkbox : null}});
    assert.equal(checkbox.disabled, true);
    finish(null);
    await pending;
    assert.equal(checkbox.checked, false);
    assert.equal(checkbox.disabled, false);
});
