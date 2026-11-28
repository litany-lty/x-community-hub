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
    const material = { id: "101" };
    const row = { material, rewrite: { status: "READY", candidate: true } };
    const repostPreviewState = {
        requestToken: 5, sourceKey: "source", owner: { accountId: "A" }, items: [row],
        editedRewrites: new Map(), rewritingIds: new Set(), filterMode: "REWRITE"
    };
    let resolvePreview;
    const transfers = [];
    const ctx = vm.createContext({
        repostPreviewState, state: { authStatus: { user: { id: "A" } } },
        $: () => null, renderRepostPreviewTimeline() {}, showToast() {}, closeRepostPreviewModal() {},
        parseGenerationTargetSpec: () => ({}), stableUserId: (user) => String(user?.id || ""),
        sendTextDraftToSchedule: (...args) => { transfers.push(args); return true; },
        contentAssistantRequest: () => new Promise(resolve => { resolvePreview = resolve; })
    });
    vm.runInContext(readBody + preview + "\nfunction transfer(event) {" + handoff + "}", ctx);
    const click = () => ctx.transfer({ target: { closest: () => ({ dataset: { rewriteScheduleItem: "101" } }) } });
    return { row, material, state: ctx.state, preview: repostPreviewState, ctx, transfers, click,
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
    f.row.rewrite = { rewrittenText: "A retained final", recordVersion: 3, savedDraftVersion: 3 };
    f.state.authStatus.user = { id: "B" };
    assert.equal(f.ctx.getRewriteItemText("101"), "");
    f.click();
    assert.equal(f.transfers.length, 0);
    f.preview.owner = { accountId: "B" };
    f.row.rewrite = { rewrittenText: "B's independent draft", recordVersion: 2, savedDraftVersion: 2 };
    f.click();
    assert.equal(f.transfers[0][0], "B's independent draft");
    assert.deepEqual({ ...f.transfers[0][3] }, { ownerAccountId: "B", materialId: "101", recordVersion: 2 });
});

test("an edited unsent preview keeps its server-issued source reference", () => {
    const f = fixture();
    f.row.rewrite = { status: "EVALUATED", savedDraftVersion: 0, previewId: "A:101:2:example", rewrittenText: "New preview" };
    f.preview.editedRewrites.set("101", "Edited preview");
    f.click();
    assert.equal(f.transfers[0][0], "Edited preview");
    assert.deepEqual({ ...f.transfers[0][3] }, { ownerAccountId: "A", materialId: "101", recordVersion: 0, previewId: "A:101:2:example" });
});
