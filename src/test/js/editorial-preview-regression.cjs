const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/static/app.js"), "utf8");
function functionsBetween(start, end) {
    const first = source.indexOf("    " + start);
    const last = source.indexOf("    " + end, first + 1);
    assert.ok(first >= 0 && last > first);
    return source.slice(first, last);
}

test("reopened preview drafts keep their text and rewrite proof with the same account and original", () => {
    const disk = new Map();
    const storage = { getItem: key => disk.get(key) ?? null, setItem: (key, value) => disk.set(key, value), removeItem: key => disk.delete(key) };
    function open(owner, original, previewId, localStorage = storage) {
        const material = { id: "101", authorId: "7", sourceFingerprint: original };
        const row = { material, rewrite: { previewId, savedDraftVersion: 0 } };
        const ui = vm.createContext({ localStorage, repostPreviewState: { owner: { accountId: owner }, items: [row] },
            state: {authStatus: {user: {id: owner}}}, stableUserId: user => user.id, resolveRepostQuoteTweetId: m => m.id });
        vm.runInContext(functionsBetween("function sourcePreviewDrafts(", "const DEFAULT_GENERATION_TARGET_SPEC"), ui);
        return { quote: ui.sourcePreviewDrafts("quote"), rewrite: ui.sourcePreviewDrafts("rewrite"), row };
    }
    const first = open("42", "original", "first-preview");
    first.quote.set("101", "我的附文");
    first.rewrite.set("101", "我的正文");
    const again = open("42", "original", "newer-preview");
    assert.equal(again.quote.get("101"), "我的附文");
    assert.equal(again.rewrite.get("101"), "我的正文");
    assert.equal(again.rewrite.reference("101").previewId, "first-preview");
    again.row.material.views = 99000;
    assert.equal(again.rewrite.get("101"), "我的正文");
    again.rewrite.set("101", "");
    assert.equal(again.rewrite.has("101"), true);
    assert.equal(again.rewrite.get("101"), "");
    assert.equal(again.rewrite.reference("101").previewId, "first-preview");
    assert.equal(open("77", "original", "b").quote.has("101"), false);
    assert.equal(open("42", "corrected", "c").rewrite.has("101"), false);
    again.quote.delete("101");
    assert.equal(open("42", "original", "d").quote.has("101"), false);
    const blocked = open("42", "original", "x", { setItem() { throw Error("storage unavailable"); } });
    blocked.quote.set("101", "仅本页保存");
    assert.equal(blocked.quote.get("101"), "仅本页保存");
});

test("the activity panel registers a verified scheduled delivery without requesting a resend", async () => {
    const writes = [];
    const ui = vm.createContext({
        state: { authStatus: { user: { username: "tester" } } }, escapeHtml: String, showToast() {},
        window: { confirm: () => true, prompt: () => "https://x.com/tester/status/93100" },
        async runAssistantMutation(_key, endpoint, decision) { writes.push([endpoint, { ...decision }]); return {}; }
    });
    vm.runInContext(functionsBetween("function assistantVerificationButtons(", "async function runAssistantMutation("), ui);
    const row = { id: "scheduled-1", jobId: "scheduled-1", status: "UNCERTAIN", version: 4, scheduledDelivery: true };
    assert.match(ui.assistantVerificationButtons(row), /data-publish-mode="SCHEDULED"/);
    for (const published of [true, false]) {
        await ui.verifyAssistantPublication({ dataset: { assistantVerify: row.id, publishMode: "SCHEDULED", version: "4", published: String(published) } });
    }
    assert.deepEqual(writes, [
        ["/scheduled-publications/scheduled-1/verify", { version: 4, published: true, tweetId: "93100" }],
        ["/scheduled-publications/scheduled-1/verify", { version: 4, published: false }]
    ]);
    assert.equal(ui.assistantVerificationButtons({ ...row, status: "PUBLISHED" }), "");
});

function modal(request) {
    const item = { material: { id: "202", publishedAt: Date.now() }, decision: { commentText: "模型附文" }, rewrite: { candidate: true, rewrittenText: "模型改写" } };
    const preview = {
        requestToken: 3, sourceKey: "writer", items: [item],
        evaluatingIds: new Set(), rewritingIds: new Set(), expandedIds: new Set(),
        editedComments: new Map([["202", "手改附文"]]), editedRewrites: new Map([["202", "手改正文"]])
    };
    const ui = vm.createContext({
        repostPreviewState: preview, state: {}, $: () => null, renderRepostPreviewTimeline() {},
        parseGenerationTargetSpec: () => ({}), formatLocalizedFetchError: (_err, label) => label, showToast() {},
        contentAssistantRequest: request
    });
    vm.runInContext(functionsBetween("function getRewriteItemText(", "function buildRepostItemComposedText(")
        + functionsBetween("function resolveAssistantDraftSourcePost(", "function assistantDraftNumberChangesText(")
        + functionsBetween("async function evaluateSingleTimelineItem(", "async function evaluateAuthorAccountOnceInModal("), ui);
    return { ui, preview, item };
}

test("a split draft always links to its selected post, including when only the stable ID remains", () => {
    const { ui } = modal();
    const other = [{ id: "101", username: "alpha" }];
    const draft = { materialId: "202", research: { snapshot: { materials: [{ id: "202", username: "beta" }] } } };
    assert.equal(ui.resolveAssistantDraftSourcePost(draft, other).url, "https://x.com/beta/status/202");
    assert.equal(ui.resolveAssistantDraftSourcePost({ materialId: "202" }, other).url, "https://x.com/i/status/202");
    assert.equal(ui.resolveAssistantDraftSourcePost({ materialId: "missing" }, other), null);
    assert.equal(ui.resolveAssistantDraftSourcePost({ quoteSourcePost: null, materialId: "202" }, other), null);
});

for (const kind of ["quote", "rewrite"]) {
    const editKey = kind === "quote" ? "editedComments" : "editedRewrites";
    const original = kind === "quote" ? "手改附文" : "手改正文";
    const generate = ui => kind === "quote" ? ui.evaluateSingleTimelineItem("202", true, 3) : ui.previewSingleRewriteItem("202", true, 3);
    const response = id => ({ material: { id, publishedAt: Date.now() }, preview: { commentText: "新附文" }, rewrite: { candidate: true, rewrittenText: "新正文" } });

    test(kind + " preserves edits after timeouts and ignores a response for a different source", async () => {
        const fixture = modal(async () => { throw Error("timeout"); });
        await generate(fixture.ui);
        assert.equal(fixture.preview[editKey].get("202"), original);
        fixture.ui.contentAssistantRequest = async () => response("101");
        await generate(fixture.ui);
        assert.equal(fixture.item.material.id, "202");
        assert.equal(fixture.preview[editKey].get("202"), original);
    });

    test(kind + " replaces the previous edit only after success and protects newer typing", async () => {
        let resolveRequest;
        const fixture = modal(() => new Promise(resolve => { resolveRequest = resolve; }));
        const pending = generate(fixture.ui);
        assert.equal(fixture.preview[editKey].get("202"), original);
        fixture.preview[editKey].set("202", "等待期间修改");
        resolveRequest(response("202"));
        await pending;
        assert.equal(fixture.preview[editKey].get("202"), "等待期间修改");
        fixture.ui.contentAssistantRequest = async () => response("202");
        await generate(fixture.ui);
        assert.equal(fixture.preview[editKey].has("202"), false);
    });
}

test("a rejected rewrite is excluded from rewrite candidates and its reason stays visible in the timeline", () => {
    const row = {
        material: { id: "202", text: "原帖正文" }, decision: {},
        rewrite: { status: "SKIPPED", candidate: false, evaluated: true, suitable: false, reason: "缺少可核实的独立观点" }
    };
    const preview = { filterMode: "ALL", evaluatingIds: new Set(), expandedIds: new Set(), editedComments: new Map() };
    const ui = vm.createContext({
        repostPreviewState: preview, escapeHtml: String, repostDecisionBadgeMeta: () => ({ cls: "", label: "" }),
        repostDualScoreText: () => "", repostScoreBreakdownText: () => "", resolveRepostMaterialUrl: () => "",
        resolveRepostOwnerIdentity: () => ({}), getRepostItemCommentText: () => "", buildRepostIntentHref: () => ""
    });
    vm.runInContext(functionsBetween("function getRewriteItemText(", "function buildRepostItemComposedText(")
        + functionsBetween("function quoteDeliveryStatus(", "function preservePublishedQuote(")
        + functionsBetween("function renderRepostTimelineItemHtml(", "function renderRepostPreviewTimeline("), ui);
    assert.match(ui.renderRepostTimelineItemHtml(row), /洗稿未通过 · 缺少可核实的独立观点/);
    preview.filterMode = "REWRITE";
    assert.equal(ui.itemMatchesRepostFilter(row), false);
});

function batchModal(request) {
    const { ui, preview } = modal(request);
    Object.assign(preview, { focusedMaterialId: "202", publishingIds: new Set(), pendingQuotes: new Map() });
    Object.assign(ui, {
        state: { authStatus: { user: {} } }, resolveDualCdView: () => ({}), renderRepostPreviewSubbar() {},
        captureUserScope: () => () => true, stableUserId: () => "42", assistantPublishRequestId: async () => "request",
        resolveRepostQuoteTweetId: material => material.id, resolveRepostMaterialUrl: () => "",
        getRepostItemCommentText: () => "手改附文", assistantPublishNotice: () => ""
    });
    vm.runInContext(functionsBetween("async function evaluateAuthorAccountOnceInModal(", "function openRepostPreviewModal(")
        + functionsBetween("async function publishRepostPreviewItem(", "function setContentAssistantError("), ui);
    return { ui, preview };
}

test("the receipt link is visible on a published quote without a preview comment", () => {
    const row = { material: { id: "101", text: "Original" }, decision: {
        persistedStatus: "PUBLISHED", publishedTweetUrl: "https://x.com/i/status/90106",
        publishedText: "Manual reply and source URL", publicationMode: "QUOTE_LINK"
    } };
    const preview = { filterMode: "ALL", items: [row], editedComments: new Map(), evaluatingIds: new Set(), expandedIds: new Set() };
    const ui = vm.createContext({
        repostPreviewState: preview, escapeHtml: String, repostDecisionBadgeMeta: dec => ({cls: "ok", label: dec.persistedStatus}),
        repostDualScoreText: () => "", repostScoreBreakdownText: () => "", resolveRepostMaterialUrl: () => "",
        resolveRepostOwnerIdentity: () => ({}), buildRepostIntentHref: () => "", repostCategoryLabel: () => "", safeHttpUrl: String
    });
    vm.runInContext(functionsBetween("function quoteDeliveryStatus(", "function preservePublishedQuote(")
        + functionsBetween("function getRepostItemCommentText(", "function getRewriteItemText(")
        + functionsBetween("function renderRepostTimelineItemHtml(", "function renderRepostPreviewTimeline("), ui);
    const html = ui.renderRepostTimelineItemHtml(row);
    assert.match(html, /href="https:\/\/x\.com\/i\/status\/90106"[^>]*>查看已发推文/);
    assert.match(html, /Manual reply and source URL/);
});

test("an older batch preserves a newer completed single quote regeneration", async () => {
    let finishBatch;
    let finishSingle;
    const {ui, preview} = batchModal(path => new Promise(resolve => {
        if (path.endsWith("evaluate-once")) finishBatch = resolve;
        else finishSingle = resolve;
    }));
    preview.editedComments.clear();
    const batch = ui.evaluateAuthorAccountOnceInModal(preview.requestToken, true);
    const single = ui.evaluateSingleTimelineItem("202", true, preview.requestToken, true);
    finishSingle({ material: {id: "202", text: "Corrected"}, preview: {commentText: "New single conclusion", evaluated: true} });
    await single;
    finishBatch({ items: [{material: {id: "202", text: "Previous"}, decision: {evaluated: true, commentText: "Old batch conclusion"}}],
        regeneratedCommentIds: ["202"] });
    await batch;
    assert.equal(preview.items[0].decision.commentText, "New single conclusion");
    assert.equal(preview.items[0].material.text, "Corrected");
});

for (const olderFinishesFirst of [true, false]) {
    test("a newer account refresh keeps the corrected original and comment when the single request finishes "
            + (olderFinishesFirst ? "first" : "last"), async () => {
        let finishSingle, finishBatch;
        const {ui, preview} = batchModal(endpoint => new Promise(resolve => {
            if (endpoint.endsWith("evaluate-once")) finishBatch = resolve;
            else finishSingle = resolve;
        }));
        const single = ui.evaluateSingleTimelineItem("202", true, preview.requestToken);
        const batch = ui.evaluateAuthorAccountOnceInModal(preview.requestToken, true);
        const singleResult = {material: {id: "202", text: "Old original"}, preview: {evaluated: true, commentText: "Old comment"}};
        const batchResult = {items: [{material: {id: "202", text: "Corrected original"},
            decision: {evaluated: true, commentText: "New comment"}}], regeneratedCommentIds: ["202"]};
        if (olderFinishesFirst) {
            finishSingle(singleResult);
            await single;
            finishBatch(batchResult);
        } else {
            finishBatch(batchResult);
            await batch;
            finishSingle(singleResult);
        }
        await Promise.all([single, batch]);
        assert.equal(preview.items[0].decision.commentText, "New comment");
        assert.equal(preview.items[0].material.text, "Corrected original");
    });
}

test("timeline repaint keeps the strict same-author cooldown for patrol releases", () => {
    const decision = {evaluated: true, wouldAutoRepost: false, category: "MODEL_RELEASE", contentPass: true,
        persistedStatus: "SKIPPED_AUTHOR_COOLDOWN", strictSixteenHourAuthorLock: true, decisionCode: "SKIP_AUTHOR_COOLDOWN"};
    const ui = vm.createContext({repostPreviewState: {items: [{material: {id: "202", publishedAt: 1000}, decision}]},
        resolveDualCdView: () => ({authorActive: true}), isDirectPassRepostCategory: category => category === "MODEL_RELEASE",
        REPOST_SAME_AUTHOR_COOLDOWN_MS: 16 * 3600000});
    vm.runInContext(functionsBetween("function quoteDeliveryStatus(", "function preservePublishedQuote(")
        + functionsBetween("function recomputeTimeline16hWindow(", "function renderRepostPreviewHeader("), ui);
    ui.recomputeTimeline16hWindow();
    assert.equal(decision.wouldAutoRepost, false);
    assert.equal(decision.decisionCode, "SKIP_AUTHOR_COOLDOWN");
    assert.ok(!decision.authorCooldownExempt);
});

test("an older published batch receipt preserves a later successful manual quote", async () => {
    let finishBatch;
    let finishPost;
    const {ui, preview} = batchModal(path => new Promise(resolve => {
        if (path.endsWith("publish-quote")) finishPost = resolve;
        else finishBatch = resolve;
    }));
    const old = structuredClone(preview.items);
    old[0].decision = {persistedStatus: "PUBLISHED", publishedTweetUrl: "https://x.com/i/status/9001", publishedText: "Old"};
    const batch = ui.evaluateAuthorAccountOnceInModal(preview.requestToken, true);
    const publish = ui.publishRepostPreviewItem("202");
    await new Promise(resolve => setImmediate(resolve));
    finishPost({publishedTweetUrl: "https://x.com/i/status/9002", publicationMode: "QUOTE", text: "New"});
    await publish;
    finishBatch({items: old});
    await batch;
    assert.equal(preview.items[0].decision.publishedTweetUrl, "https://x.com/i/status/9002");
    assert.equal(preview.items[0].decision.publishedText, "New");
});

test("an unreadable success response locks the card until a newer negative verification allows another send", async () => {
    let posts = 0;
    let receipt = { persistedStatus: "UNCERTAIN", decisionCode: "UNCERTAIN", receiptRevision: 6, requestReceiptRevision: 6, publishedText: "实际请求附文" };
    const { ui, preview } = batchModal(async (_path, body) => {
        if (body.receiptOnly)
            return { owner: { accountId: "42" }, material: {id: "202"}, preview: receipt };
        posts++;
        return posts === 1 ? {} : { publishedTweetId: "91234", publicationMode: "QUOTE", text: "实际请求附文" };
    });
    await ui.publishRepostPreviewItem("202");
    assert.equal(preview.items[0].decision.persistedStatus, "UNCERTAIN");
    await ui.publishRepostPreviewItem("202");
    assert.equal(posts, 1);
    const pending = preview.items[0].decision;
    receipt = { persistedStatus: "VERIFIED_UNPUBLISHED", decisionCode: "PENDING_EVAL", receiptRevision: 8,
        publishedText: "", publishedTweetUrl: "", reason: "" };
    await ui.refreshQuoteReceipt("202");
    const cleared = preview.items[0].decision;
    assert.equal(cleared.persistedStatus, "VERIFIED_UNPUBLISHED");
    assert.equal(ui.preservePublishedQuote(cleared, pending), cleared, "late evaluation cannot restore a cleared lock");
    await ui.publishRepostPreviewItem("202");
    await ui.publishRepostPreviewItem("202");
    assert.equal(posts, 2);
    assert.equal(preview.items[0].decision.persistedStatus, "PUBLISHED");
});

test("a pending quote keeps its hand edit when an earlier batch regeneration returns", async () => {
    let finish;
    const { ui, preview } = batchModal(() => new Promise(resolve => { finish = resolve; }));
    const earlier = structuredClone(preview.items);
    const batch = ui.evaluateAuthorAccountOnceInModal(preview.requestToken, true);
    preview.items[0].decision = { persistedStatus: "UNCERTAIN", decisionCode: "UNCERTAIN", receiptRevision: 7 };
    finish({ items: earlier, regeneratedCommentIds: ["202"] });
    await batch;
    assert.equal(preview.items[0].decision.persistedStatus, "UNCERTAIN");
    assert.equal(preview.editedComments.get("202"), "手改附文");
    const later = { persistedStatus: "UNCERTAIN", receiptRevision: 12 };
    assert.equal(ui.preservePublishedQuote({persistedStatus: "PUBLISHED", receiptRevision: 10}, later), later);
});

test("a refreshed card cannot unlock a new timed-out send with a previous or unrelated negative receipt", async () => {
    let posts = 0;
    let matched = 4;
    let latest = 4;
    const disk = new Map();
    const storage = { getItem: k => disk.get(k) ?? null, setItem: (k, v) => disk.set(k, v), removeItem: k => disk.delete(k) };
    const negative = () => ({ evaluated: true, persistedStatus: "VERIFIED_UNPUBLISHED", decisionCode: "PENDING_EVAL", receiptRevision: latest });
    const request = async (path, body) => {
        if (body.receiptOnly) {
            assert.equal(body.clientRequestId, "request");
            return { owner: {accountId: "42"}, material: {id: "202"}, preview: { ...negative(), requestReceiptRevision: matched } };
        }
        if (path.endsWith("/timeline")) return { owner: {accountId: "42"}, items: [{material: {id: "202"}, decision: negative()}] };
        if (!path.endsWith("/publish-quote")) throw Error("Unexpected request " + path);
        posts++;
        throw Error("response lost");
    };
    function open() {
        const m = batchModal(request);
        m.ui.localStorage = storage;
        vm.runInContext(functionsBetween("function browserRecords(", "const DEFAULT_GENERATION_TARGET_SPEC"), m.ui);
        m.preview.pendingQuotes = m.ui.browserRecords("receipt:");
        return m;
    }
    const first = open();
    first.preview.items[0].decision = negative();
    await first.ui.publishRepostPreviewItem("202");
    assert.equal(first.preview.items[0].decision.persistedStatus, "UNCERTAIN");
    const reopened = open();
    latest = 7;
    await reopened.ui.loadAuthorTimelinePreview("writer");
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(reopened.preview.items[0].decision.persistedStatus, "UNCERTAIN");
    await reopened.ui.publishRepostPreviewItem("202");
    assert.equal(posts, 1);
    assert.equal(reopened.ui.preservePublishedQuote(reopened.preview.items[0].decision, negative()).persistedStatus, "UNCERTAIN");
    matched = latest = 8;
    await reopened.ui.refreshQuoteReceipt("202");
    assert.equal(reopened.preview.items[0].decision.persistedStatus, "VERIFIED_UNPUBLISHED");
    assert.equal(disk.size, 0);
});

test("an open quote card locks when another tab dispatches the same post", async () => {
    const {ui, preview} = batchModal(() => assert.fail("duplicate publish"));
    const key = ui.quoteAttemptKey(preview.items[0].material);
    preview.pendingQuotes.set(key, {key: "other-tab", afterRevision: 0, comment: "另一标签发送的正文"});
    let notify;
    Object.assign(ui, {$: () => ({hidden: false}), PENDING_QUOTE_STORAGE_PREFIX: "pending:",
        window: {addEventListener: (_event, f) => { notify = f; }}});
    vm.runInContext(functionsBetween('window.addEventListener("storage",', 'document.getElementById("repostPreviewTimelineList")?.addEventListener("click",'), ui);
    notify({key: "pending:another-account"});
    assert.equal(preview.items[0].decision.persistedStatus, undefined);
    notify({key: "pending:" + key});
    assert.equal(preview.items[0].decision.persistedStatus, "UNCERTAIN");
    assert.equal(preview.items[0].decision.commentText, "另一标签发送的正文");
    await ui.publishRepostPreviewItem("202");
});

test("opening verification without a matching quote cannot focus another post's verification button", async () => {
    let notice;
    const feed = { querySelector: s => s.includes("data-assistant-verify") ? {focus: () => assert.fail("unrelated verification")} : null };
    const {ui} = batchModal();
    Object.assign(ui, {event: {target: {closest: () => ({dataset: {repostReviewItem: "202"}})}},
        CSS: {escape: String}, $: () => feed, closeRepostPreviewModal() {}, switchTab() {}, renderContentAssistant() {},
        loadContentAssistant: async () => {}, showToast: text => { notice = text; }});
    await vm.runInContext("(async () => {" + functionsBetween('const review = event.target?.closest?.("[data-repost-review-item]");', 'const rwPreviewBtn = ') + "})()", ui);
    assert.match(notice, /暂未找到这条引用/);
});

test("the timeline and batch evaluation both request the material selected in the library", async () => {
    const paths = [];
    const { ui } = batchModal(async (path, body) => {
        paths.push(path);
        assert.equal(body.materialId, "202");
        return { items: [{ material: { id: "202" }, decision: { evaluated: true } }] };
    });
    await ui.loadAuthorTimelinePreview("writer", { materialId: "202" });
    await ui.evaluateAuthorAccountOnceInModal(ui.repostPreviewState.requestToken, true);
    assert.deepEqual(paths, ["/sources/writer/timeline", "/sources/writer/evaluate-once"]);
});

test("an account batch replaces only the comments actually regenerated and preserves newer typing", async () => {
    let finish;
    const { ui, preview } = batchModal(() => new Promise(resolve => { finish = resolve; }));
    const items = Array.from({ length: 9 }, (_, index) => ({
        material: { id: String(201 + index) }, decision: { commentText: "模型附文" }
    }));
    preview.items = items;
    for (const item of items) preview.editedComments.set(item.material.id, "手改附文");
    const pending = ui.evaluateAuthorAccountOnceInModal(preview.requestToken, true);
    preview.editedComments.set("201", "等待期间修改");
    finish({ items, regeneratedCommentIds: items.slice(0, 8).map(item => item.material.id) });
    await pending;
    assert.equal(preview.editedComments.get("209"), "手改附文");
    assert.equal(preview.editedComments.get("201"), "等待期间修改");
    assert.equal(preview.editedComments.has("202"), false);
});

for (const batchFirst of [true, false]) {
    test("a published receipt survives a concurrent batch refresh, batchFirst=" + batchFirst, async () => {
        let finishBatch;
        let finishSend;
        const { ui, preview } = batchModal(path => new Promise(resolve => {
            if (path.endsWith("publish-quote")) finishSend = resolve;
            else finishBatch = resolve;
        }));
        const stale = structuredClone(preview.items);
        const batch = ui.evaluateAuthorAccountOnceInModal(preview.requestToken, true);
        const send = ui.publishRepostPreviewItem("202");
        await new Promise(resolve => setImmediate(resolve));
        if (batchFirst) { finishBatch({ items: stale }); await batch; }
        finishSend({ publishedTweetUrl: "https://x.com/i/status/9220", publicationMode: "QUOTE", text: "手改附文" });
        await send;
        if (!batchFirst) { finishBatch({ items: stale }); await batch; }
        assert.equal(preview.items[0].decision.persistedStatus, "PUBLISHED");
        assert.equal(preview.items[0].decision.publishedTweetUrl, "https://x.com/i/status/9220");
    });
}

test("verification displays the last dispatched fallback text", () => {
    const el = { insertAdjacentHTML(_where, html) { this.innerHTML = html; } };
    const row = { scheduledDelivery: true, requestedText: "planned", text: "actual body with link", publicationMode: "QUOTE_LINK" };
    const ctx = vm.createContext({ $: () => el, esc: String, escapeHtml: String, assistantVerificationButtons: () => "",
        dashboard: { unconfirmedPublications: [row] } });
    vm.runInContext(functionsBetween('const autoFeedEl = $("assistantAutoRepostFeed");', 'const capabilities = dashboard.capabilities'), ctx);
    assert.match(el.innerHTML, /actual body with link/);
    assert.doesNotMatch(el.innerHTML, /planned/);
});

test("older actionable rewrites stay accessible alongside the eight recent activities", () => {
    const recent = Array.from({ length: 8 }, (_, i) => ({ id: String(i), autoKind: "REPOST", status: "SKIPPED" }));
    const frozen = { id: "101", autoKind: "REWRITE", status: "SKIPPED_QUIET_HOURS", rewrittenText: "旧定稿" };
    const failed = { id: "202", autoKind: "REWRITE", status: "FAILED", canReassess: true };
    const el = { hidden: false, innerHTML: "", insertAdjacentHTML(_where, markup) { this.innerHTML += markup; } };
    const ctx = vm.createContext({ $: () => el, esc: String, escapeHtml: String, dashboard: {
        autoReposts: [...recent, frozen, failed], retryableAutoPublications: [failed, frozen], unconfirmedPublications: []
    } });
    vm.runInContext(functionsBetween('const autoFeedEl = $("assistantAutoRepostFeed");', 'const capabilities = dashboard.capabilities'), ctx);
    assert.match(el.innerHTML, /data-autorepost-retry="101" data-publish-mode="REWRITE"/);
    assert.match(el.innerHTML, /data-autorepost-retry="202"[^>]*data-reassess="true"/);
    assert.equal((el.innerHTML.match(/旧定稿/g) || []).length, 1);
});

test("published rewrite cards always copy the delivered body", () => {
    const row = { material: { id: "101" }, rewrite: { status: "PUBLISHED", rewrittenText: "实际发送" } };
    const ctx = vm.createContext({ repostPreviewState: {items: [row], editedRewrites: new Map([["101", "之前未发送的编辑"]])} });
    vm.runInContext(functionsBetween("function getRewriteItemText(", "function buildRepostItemComposedText("), ctx);
    assert.equal(ctx.getRewriteItemText("101"), "实际发送");
});
