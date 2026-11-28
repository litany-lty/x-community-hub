// Frozen browser code from b2ae8aa, through the first HTTP request on its single-text schedule path.
// The caller supplies a real saved source card and the account currently shown in the legacy composer.
const { readFileSync } = require("node:fs");
const assert = require("node:assert/strict");
const [cardPath, due, account] = process.argv.slice(2);
const card = JSON.parse(readFileSync(cardPath, "utf8"));
const repostPreviewState = { owner: card.owner, items: card.items, editedRewrites: new Map() };
const state = {
    authStatus: { user: { id: account }, writeAuthorized: true },
    activeScheduleDraftIdx: 0,
    scheduleDraftItems: [{ text: "", files: [], timeTouched: true }],
    scheduleMutations: new Set()
};
const noop = () => {};
const document = { querySelector: () => null };
const $ = () => ({});
const MAX_SCHEDULE_TEXT_LENGTH = 280, MAX_BATCH_SCHEDULE_ITEMS = 10;
const isLoggedIn = () => true, isScheduleEligible = () => true, hasScheduleAccessDecision = () => true;
const switchTab = noop, setScheduleComposerError = noop, ensureScheduleDraftInitialized = noop;
const renderScheduleDraftItems = noop, closeRepostPreviewModal = noop, showToast = noop, syncScheduleDraftConflictUI = noop;
const rotateComposerRequestId = () => { state.composerClientRequestId = "legacy-auto-handoff"; };
const validateAndMergeScheduleFiles = () => ({ ok: true, files: [] });
const getQueuedScheduledEpochsMs = () => [], getRequiredScheduleIntervalMs = () => 0;
const parseDraftItemEpochMs = () => Number(due), validateBatchScheduleEpochs = () => ({ ok: true });
const beginScheduleMutation = () => ({});
const requestJsonWithin = (url, request) => {
    assert.equal(url, "/api/scheduled-tweets");
    assert.deepEqual(Object.keys(JSON.parse(request.body)).sort(), ["clientRequestId", "scheduledAtEpochMs", "text"]);
    process.stdout.write(request.body);
    return new Promise(() => {});
};

function getRewriteItemText(materialId) {
    const id = String(materialId || "");
    if (repostPreviewState.editedRewrites.has(id)) {
        return String(repostPreviewState.editedRewrites.get(id) || "");
    }
    const found = repostPreviewState.items.find((it) => String(it.material?.id || "") === id);
    return String(found?.rewrite?.rewrittenText || "");
}

function sendTextDraftToSchedule(text, attachedFiles = [], quoteMeta = null) {
    switchTab("schedule");
    if (state.scheduleDraftRestoring) {
        setScheduleComposerError("图片正在恢复，请稍候再加入草稿。", true);
        return false;
    }
    if (hasScheduleAccessDecision() && !isScheduleEligible()) {
        syncScheduleAuthBanner();
        setScheduleComposerError(getScheduleLockedReason(), true);
        return false;
    }
    ensureScheduleDraftInitialized();
    const items = state.scheduleDraftItems;
    const isEmpty = (item) => !String(item.text || "").trim() && !(item.files || []).length && !String(item.quoteTweetId || "").trim();
    let idx = isEmpty(items[state.activeScheduleDraftIdx]) ? state.activeScheduleDraftIdx : items.findIndex(isEmpty);
    if (idx < 0 && items.length >= MAX_BATCH_SCHEDULE_ITEMS) {
        setScheduleComposerError("草稿已满，请先提交或移除一条后再添加；现有内容已保留。", true);
        return false;
    }
    const media = validateAndMergeScheduleFiles([], attachedFiles);
    if (!media.ok) { setScheduleComposerError(media.error, true); return false; }
    let item;
    if (idx < 0) {
        idx = items.length;
        const jitterMin = randomScheduleJitterMinutes();
        const gapMs = MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
        const last = parseDraftItemEpochMs(items[items.length - 1]);
        const preferred = Math.max(getRecommendedBaselineEpochMs(Date.now()), Number.isFinite(last) ? last + gapMs : 0);
        item = createScheduleDraftItem("", getRecommendedScheduleEpochMs(preferred, { draftIndex: idx, gapMs }), [], false, null, jitterMin, quoteMeta);
        items.push(item);
    } else {
        item = items[idx];
    }
    item.text = String(text || "");
    item.files = media.files;
    item.quoteTweetId = String(quoteMeta?.quoteTweetId || "").trim();
    item.quoteTweetUrl = String(quoteMeta?.quoteTweetUrl || "").trim();
    if (!quoteMeta?.quoteTweetId) item.sourceRef = null;
    if (!item.timeTouched) {
        const jitterMin = getRecommendedScheduleJitterMin(item);
        const gapMs = MIN_SCHEDULE_INTERVAL_MS + jitterMin * 60_000;
        const preferred = Math.max(parseDraftItemEpochMs(item) || 0, getRecommendedBaselineEpochMs(Date.now()));
        item.scheduledAtValue = formatEpochToDatetimeLocalValue(getRecommendedScheduleEpochMs(preferred, { draftIndex: idx, gapMs }));
    }
    state.activeScheduleDraftIdx = idx;
    setScheduleComposerError("");
    rotateComposerRequestId();
    renderScheduleDraftItems();
    const input = document.querySelector('#scheduleDraftItemsList [data-draft-textarea="' + idx + '"]');
    input?.focus({ preventScroll: true });
    input?.scrollIntoView({ block: "nearest" });
    return true;
}

function click(event) {
    const rwSchedBtn = event.target?.closest?.("[data-rewrite-schedule-item]");
    if (rwSchedBtn) {
        const id = rwSchedBtn.dataset.rewriteScheduleItem || "";
        const text = getRewriteItemText(id).trim();
        if (!text) {
            showToast("暂无可转入定时的洗稿内容");
            return;
        }
        if (sendTextDraftToSchedule(text) !== false) {
            closeRepostPreviewModal();
            showToast("已转入定时发布");
        }
        return;
    }
}

async function submitCreateScheduledTweet() {
    if (!isLoggedIn() || state.isLoggingOut || state.scheduleMutations.has("create")) return;
    if (state.scheduleDraftRestoring) {
        setScheduleComposerError("图片正在恢复，请稍候再提交。", true);
        return;
    }
    setScheduleComposerError("");
    if (hasScheduleAccessDecision() && !isScheduleEligible()) {
        syncScheduleAuthBanner();
        setScheduleComposerError(getScheduleLockedReason(), true);
        return;
    }
    ensureScheduleDraftInitialized();
    const items = state.scheduleDraftItems || [];
    items.forEach((it, idx) => {
        const taEl = document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${idx}"]`);
        if (taEl) it.text = taEl.value || "";
        const tiEl = document.querySelector(`#scheduleDraftItemsList [data-draft-time="${idx}"]`);
        if (tiEl) it.scheduledAtValue = tiEl.value || "";
    });

    for (let i = 0; i < items.length; i++) {
        const text = items[i].text || "";
        const files = items[i].files || [];
        if (text.length > MAX_SCHEDULE_TEXT_LENGTH) {
            setScheduleComposerError(scheduleTextLengthError(i, items.length),
                true, { draftIndex: i, field: "text" });
            return;
        }
        if (!text.trim() && files.length === 0) {
            setScheduleComposerError(
                items.length > 1 ? `第 ${i + 1} 条还没有内容，请输入正文或添加图片。`
                    : "请输入正文或添加图片。",
                true, { draftIndex: i, field: "text" }
            );
            document.querySelector(`#scheduleDraftItemsList [data-draft-textarea="${i}"]`)?.focus();
            return;
        }
    }

    const draftEpochs = items.map((it) => parseDraftItemEpochMs(it));
    const validation = validateBatchScheduleEpochs(
        draftEpochs,
        getQueuedScheduledEpochsMs(),
        Date.now(),
        getRequiredScheduleIntervalMs()
    );
    syncScheduleDraftConflictUI();
    if (!validation.ok) {
        setScheduleComposerError(validation.error || "请检查发布时间。", true,
            { draftIndex: validation.conflictIndices?.[0], field: "time" });
        return;
    }

    const anyHasFiles = items.some((it) => (it.files || []).length > 0);
    const needsWrite = !state.authStatus?.writeAuthorized;
    const needsMedia = anyHasFiles && !state.authStatus?.mediaWriteAuthorized;
    if (needsWrite || needsMedia) {
        await triggerXPublishAuth();
        return;
    }
    if (!state.composerClientRequestId) {
        rotateComposerRequestId();
    }

    const mutation = beginScheduleMutation("create");
    if (!mutation) return;
    const submittedRequestId = state.composerClientRequestId;
    const submittedIds = items.length === 1 ? [submittedRequestId]
        : items.map((_, idx) => `${submittedRequestId}_${idx}`);
    const btn = $("createScheduleBtn");
    btn.disabled = true;
    btn.textContent = "提交中…";
    try {
        let resp, body;
        if (!anyHasFiles && items.length === 1) {
            const payload = {
                text: items[0].text || "",
                scheduledAtEpochMs: draftEpochs[0],
                clientRequestId: state.composerClientRequestId
            };
            if (items[0].sourceRef?.assistantDraftId) {
                payload.assistantDraftId = items[0].sourceRef.assistantDraftId;
                payload.assistantDraftVersion = Number(items[0].sourceRef.assistantDraftVersion || 0);
            }
            if (items[0].quoteTweetId) {
                payload.quoteTweetId = items[0].quoteTweetId;
                if (items[0].quoteTweetUrl) payload.quoteTweetUrl = items[0].quoteTweetUrl;
            }
            ({ resp, body } = await requestJsonWithin("/api/scheduled-tweets", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            }, 60000));
        }
    } finally {}
}

assert.equal(card.items[0].rewrite.candidate, true);
click({ target: { closest: () => ({ dataset: { rewriteScheduleItem: card.items[0].material.id } }) } });
void submitCreateScheduledTweet();
