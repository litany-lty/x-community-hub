const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const appJsPath = path.resolve(__dirname, "../../main/resources/static/app.js");
const appJs = fs.readFileSync(appJsPath, "utf8");

function sliceBetween(source, startMarker, endMarker) {
    const start = source.indexOf(startMarker);
    assert.notEqual(start, -1, `Missing start marker: ${startMarker}`);
    const end = source.indexOf(endMarker, start);
    assert.notEqual(end, -1, `Missing end marker: ${endMarker}`);
    return source.slice(start, end);
}

const urlTokenReSlice = sliceBetween(
    appJs,
    "    const URL_TOKEN_RE =",
    "\n"
);

const helpersSlice = sliceBetween(
    appJs,
    "    function computeWeightedTweetLength(text) {",
    "    function formatEpochToDatetimeLocalValue("
);

const batchHelpersSlice = sliceBetween(
    appJs,
    "    function formatEpochToDatetimeLocalValue(",
    "    function formatScheduleEpochLabel("
);
const recommendationHelpersSlice = sliceBetween(
    appJs,
    "    function getQueuedScheduledEpochsMs(",
    "    function ensureScheduleDraftInitialized("
);

const harnessCode = `
${urlTokenReSlice};
${helpersSlice}
${batchHelpersSlice}
${recommendationHelpersSlice}
this.__exports = {
    computeWeightedTweetLength,
    validateAndMergeScheduleFiles,
    getTzDateParts,
    zonedWallClockToEpochMs,
    MIN_SCHEDULE_INTERVAL_MS,
    computeNextAvailableScheduleMs,
    validateBatchScheduleEpochs,
    alignBatchScheduleEpochs,
    formatEpochToDatetimeLocalValue,
    parseDraftItemEpochMs,
    getLatestPublishedEpochMs,
    randomScheduleJitterMinutes,
    getRecommendedScheduleGapMs,
    getRecommendedScheduleEpochMs,
    buildScheduleQuickPresets,
    formatScheduleLocalTime,
    refreshAutomaticScheduleTimes,
    createScheduleDraftItem
};
`;

const displayContext = vm.createContext({});
vm.runInContext(sliceBetween(appJs, "    function canRescheduleJob(", "    function preserveRescheduleEditor(") +
    sliceBetween(appJs, "    function scheduledPublicationDisplay(", "    function renderScheduledTweetsList("), displayContext);
for (const status of ["OVERDUE_CONFIRM_REQUIRED", "FAILED", "CANCELLED"]) {
    assert.equal(displayContext.canRescheduleJob({status, uncertainDelivery: true}), false);
    assert.equal(displayContext.canRescheduleJob({status, uncertainDelivery: false}), true);
}
const originalJob = {status: "PUBLISHED", text: "Planned", quoteTweetId: "101", quoteTweetUrl: "https://x.com/i/status/101"};
assert.equal(displayContext.scheduledPublicationDisplay(originalJob).quoteLabel, "原计划引用的推文 ↗");
for (const [mode, text] of [["QUOTE_LINK", "Planned https://x.com/i/status/101"], ["STANDALONE_FALLBACK", "Planned"]]) {
    const receipt = {mode, text, quoteTweetId: null};
    const display = displayContext.scheduledPublicationDisplay({...originalJob, publicationReceipt: receipt});
    assert.equal(display.text, text);
    assert.equal(display.quoteUrl, "");
    assert.ok(display.modeLabel);
    const pending = displayContext.scheduledPublicationDisplay({...originalJob, status: "OVERDUE_CONFIRM_REQUIRED",
        uncertainDelivery: true, dispatchedPayload: receipt});
    assert.equal(pending.text, text);
    assert.equal(pending.quoteUrl, "");
    assert.equal(pending.modeLabel, "");
}
assert.equal(displayContext.scheduledPublicationDisplay({...originalJob, publicationReceipt: {mode: "QUOTE", text: "Planned", quoteTweetId: "101"}}).quoteUrl,
    originalJob.quoteTweetUrl);
const MINUTE = 60_000;
let clockNowMs = Date.UTC(2026, 10, 14, 12, 0, 0);
class HarnessDate extends Date {
    constructor(...args) { super(...(args.length ? args : [clockNowMs])); }
    static now() { return clockNowMs; }
}
const state = {};
let activeProfile = {};
const timeInputs = new Map();
const documentStub = {
    activeElement: null,
    querySelector(selector) {
        const match = String(selector).match(/data-draft-time=["'](\d+)["']/);
        return match ? timeInputs.get(Number(match[1])) || null : null;
    },
    querySelectorAll() { return Array.from(timeInputs.values()); },
    getElementById() { return null; }
};
const context = vm.createContext({
    URL: { createObjectURL: () => "blob:mock" },
    Intl,
    Date: HarnessDate,
    Math,
    Number,
    String,
    Array,
    Set,
    state,
    document: documentStub,
    isLoggedIn: () => Boolean(state.authStatus?.loggedIn),
    stableUserId: (user) => String(user?.id || ""),
    getActiveAlgoProfile: () => activeProfile,
    newClientRequestId: () => "draft-id",
    rotateComposerRequestId: () => {
        state.composerRotationCount = (state.composerRotationCount || 0) + 1;
        state.composerClientRequestId = "rotated-" + state.composerRotationCount;
    }
});

vm.runInContext(harnessCode, context, { filename: "app-scheduled-tweet-helpers.js" });
const {
    computeWeightedTweetLength,
    validateAndMergeScheduleFiles,
    getTzDateParts,
    MIN_SCHEDULE_INTERVAL_MS,
    computeNextAvailableScheduleMs,
    validateBatchScheduleEpochs,
    alignBatchScheduleEpochs,
    formatEpochToDatetimeLocalValue,
    parseDraftItemEpochMs,
    getLatestPublishedEpochMs,
    randomScheduleJitterMinutes,
    getRecommendedScheduleGapMs,
    getRecommendedScheduleEpochMs,
    buildScheduleQuickPresets,
    formatScheduleLocalTime,
    refreshAutomaticScheduleTimes,
    createScheduleDraftItem
} = context.__exports;

function resetScheduleState(now = Date.UTC(2026, 10, 14, 12, 0, 0)) {
    clockNowMs = now;
    Object.assign(state, {
        authStatus: { loggedIn: true, user: { id: "self" } },
        report: null,
        scheduleJobs: [],
        scheduleDraftItems: [],
        scheduleMutations: new Set(),
        composerRotationCount: 0,
        composerClientRequestId: "initial",
        activeScheduleDraftIdx: 0,
        defaultScheduleJitterMin: null,
        scheduleServerTz: Intl.DateTimeFormat().resolvedOptions().timeZone
    });
    activeProfile = { cadenceAndReach: { minRecommendedGapMinutes: 180 } };
    timeInputs.clear();
    documentStub.activeElement = null;
}
function liveReport(lastMs, userId = "self") {
    return {
        liveData: true,
        profile: { id: userId },
        algorithmHealth: { latestMainPostAt: new Date(lastMs).toISOString() },
        contentTiming: {}
    };
}
function draftAt(epochMs, timeTouched = false, text = "") {
    return { scheduledAtValue: formatEpochToDatetimeLocalValue(epochMs), timeTouched, text, files: [] };
}

function makeFile(name, type, size) {
    return { name, type, size };
}

// 1. URL=23 + CJK=2 + ASCII=1 weighted length
assert.equal(computeWeightedTweetLength(""), 0);
assert.equal(computeWeightedTweetLength("Hello"), 5);
assert.equal(computeWeightedTweetLength("你好"), 4);
assert.equal(
    computeWeightedTweetLength("你好 https://example.com/a/b/c/d/e/f ABC"),
    4 + 1 + 23 + 1 + 3
);

// 2. validateAndMergeScheduleFiles limits & rules
const fourPngs = [
    makeFile("1.png", "image/png", 1024),
    makeFile("2.jpg", "image/jpeg", 2048),
    makeFile("3.webp", "image/webp", 4096),
    makeFile("4.png", "", 512)
];
const okFour = validateAndMergeScheduleFiles([], fourPngs);
assert.equal(okFour.ok, true);
assert.equal(okFour.files.length, 4);

const fifthBlocked = validateAndMergeScheduleFiles(okFour.files, [makeFile("5.png", "image/png", 100)]);
assert.equal(fifthBlocked.ok, false);
assert.match(fifthBlocked.error, /4/);

const fiveAtOnce = validateAndMergeScheduleFiles([], [
    ...fourPngs,
    makeFile("5.png", "image/png", 100)
]);
assert.equal(fiveAtOnce.ok, true);
assert.equal(fiveAtOnce.files.length, 4);
assert.equal(fiveAtOnce.truncatedCount, 1);

const oversizedStatic = validateAndMergeScheduleFiles([], [makeFile("big.png", "image/png", 5 * 1024 * 1024 + 1)]);
assert.equal(oversizedStatic.ok, false);
assert.match(oversizedStatic.error, /5MB/);

const okGif = validateAndMergeScheduleFiles([], [makeFile("anim.gif", "image/gif", 10 * 1024 * 1024)]);
assert.equal(okGif.ok, true);
assert.equal(okGif.files.length, 1);

const oversizedGif = validateAndMergeScheduleFiles([], [makeFile("huge.gif", "image/gif", 15 * 1024 * 1024 + 1)]);
assert.equal(oversizedGif.ok, false);
assert.match(oversizedGif.error, /15MB/);

const mixPngAfterGif = validateAndMergeScheduleFiles(okGif.files, [makeFile("a.png", "image/png", 100)]);
assert.equal(mixPngAfterGif.ok, false);

const mixGifAfterPng = validateAndMergeScheduleFiles([okFour.files[0]], [makeFile("anim.gif", "image/gif", 1024)]);
assert.equal(mixGifAfterPng.ok, false);

const badExt = validateAndMergeScheduleFiles([], [makeFile("doc.svg", "image/svg+xml", 1024)]);
assert.equal(badExt.ok, false);

// 3. Multi-tweet batch scheduling & mandatory 1-hour minimum interval
assert.equal(MIN_SCHEDULE_INTERVAL_MS, 3_600_000);

const baseMs = Date.UTC(2026, 10, 14, 12, 0, 0);
// Occupied slots at +20m and +80m -> next available from +30m must jump to +140m (80m + 60m)
const nextFree = computeNextAvailableScheduleMs(
    baseMs + 30 * 60_000,
    [baseMs + 20 * 60_000, baseMs + 80 * 60_000],
    MIN_SCHEDULE_INTERVAL_MS
);
assert.equal(nextFree, baseMs + 140 * 60_000);

// Exact 1h spacing passes
const exactOneHour = validateBatchScheduleEpochs(
    [baseMs + 10 * 60_000, baseMs + 70 * 60_000, baseMs + 130 * 60_000],
    [baseMs + 190 * 60_000],
    baseMs,
    MIN_SCHEDULE_INTERVAL_MS
);
assert.equal(exactOneHour.ok, true);
assert.deepEqual(Array.from(exactOneHour.conflictIndices), []);

// Intra-batch 59m gap fails and flags both conflicting items
const shortGapBatch = validateBatchScheduleEpochs(
    [baseMs + 10 * 60_000, baseMs + 69 * 60_000],
    [],
    baseMs,
    MIN_SCHEDULE_INTERVAL_MS
);
assert.equal(shortGapBatch.ok, false);
assert.deepEqual(Array.from(shortGapBatch.conflictIndices), [0, 1]);
assert.match(shortGapBatch.error, /1 小时/);

// Queue conflict (< 1h from queued SCHEDULED job) fails
const queueGapFail = validateBatchScheduleEpochs(
    [baseMs + 45 * 60_000],
    [baseMs + 90 * 60_000],
    baseMs,
    MIN_SCHEDULE_INTERVAL_MS
);
assert.equal(queueGapFail.ok, false);
assert.deepEqual(Array.from(queueGapFail.conflictIndices), [0]);
assert.match(queueGapFail.error, /1 小时/);

// Auto-align resolves both intra-batch conflicts and queued slot collisions
const aligned = alignBatchScheduleEpochs(
    [baseMs + 10 * 60_000, baseMs + 25 * 60_000, baseMs + 40 * 60_000],
    [baseMs + 90 * 60_000],
    baseMs,
    MIN_SCHEDULE_INTERVAL_MS
);
assert.equal(aligned.length, 3);
const alignedCheck = validateBatchScheduleEpochs(aligned, [baseMs + 90 * 60_000], baseMs, MIN_SCHEDULE_INTERVAL_MS);
assert.equal(alignedCheck.ok, true, `Expected aligned epochs to have zero conflicts, got: ${alignedCheck.error}`);

// 5. Last actual publication is scoped to the signed-in account and known successful data.
resetScheduleState(baseMs);
state.report = liveReport(baseMs - 90 * MINUTE);
state.scheduleJobs = [
    { accountId: "self", status: "PUBLISHED", publishedAtEpochMs: baseMs - 20 * MINUTE },
    { accountId: "other", status: "PUBLISHED", publishedAtEpochMs: baseMs - MINUTE },
    { accountId: "self", status: "SENDING", publishedAtEpochMs: baseMs - MINUTE },
    { accountId: "self", status: "SCHEDULED", scheduledAtEpochMs: baseMs - MINUTE },
    { accountId: "self", status: "PUBLISHED", publishedAtEpochMs: baseMs + MINUTE },
    { accountId: "self", status: "PUBLISHED", publishedAtEpochMs: "invalid" }
];
assert.equal(getLatestPublishedEpochMs(baseMs), baseMs - 20 * MINUTE);
state.scheduleJobs = [];
assert.equal(getLatestPublishedEpochMs(baseMs), baseMs - 90 * MINUTE);
state.report.liveData = false;
assert.equal(getLatestPublishedEpochMs(baseMs), 0, "Stored/demo reports must not become the current account's last post");
state.report = liveReport(baseMs - MINUTE, "other");
assert.equal(getLatestPublishedEpochMs(baseMs), 0);
state.report = liveReport(baseMs + MINUTE);
assert.equal(getLatestPublishedEpochMs(baseMs), 0);
state.report.algorithmHealth.latestMainPostAt = "invalid";
assert.equal(getLatestPublishedEpochMs(baseMs), 0);
state.scheduleJobs = [{ accountId: "self", status: "PUBLISHED", publishedAtEpochMs: baseMs - MINUTE }];
state.authStatus.loggedIn = false;
assert.equal(getLatestPublishedEpochMs(baseMs), 0);

// 6. Recommendations use 1h + random(1..10)m default interval while preserving 1-hour hard minimum.
resetScheduleState(baseMs);
const sampledJitters = new Set();
for (let i = 0; i < 200; i++) {
    const j = randomScheduleJitterMinutes();
    assert.equal(Number.isInteger(j), true);
    assert.ok(j >= 1 && j <= 10, `Expected jitter in [1, 10], got ${j}`);
    sampledJitters.add(j);
}
assert.ok(sampledJitters.size >= 5, "Expected multiple distinct random jitter values across 200 samples");

const defaultGapMs = getRecommendedScheduleGapMs();
assert.ok(defaultGapMs >= 61 * MINUTE && defaultGapMs <= 70 * MINUTE);
assert.equal(getRecommendedScheduleGapMs(), defaultGapMs, "Session default jitter stays stable once sampled");

state.defaultScheduleJitterMin = 7;
assert.equal(getRecommendedScheduleGapMs(), 67 * MINUTE);
state.report = liveReport(baseMs - 10 * MINUTE);
assert.equal(getRecommendedScheduleEpochMs(baseMs + 30 * MINUTE, { nowMs: baseMs }), baseMs + 57 * MINUTE);

state.defaultScheduleJitterMin = 5;
state.scheduleJobs = [
    { accountId: "self", status: "SCHEDULED", scheduledAtEpochMs: baseMs + 60 * MINUTE },
    { accountId: "other", status: "SCHEDULED", scheduledAtEpochMs: baseMs + 210 * MINUTE }
];
const draft0 = draftAt(baseMs + 210 * MINUTE);
draft0.recommendedJitterMin = 4; // 64m gap
const draft1 = draftAt(baseMs + 130 * MINUTE, true);
state.scheduleDraftItems = [draft0, draft1];
assert.equal(
    getRecommendedScheduleEpochMs(baseMs + 5 * MINUTE, { draftIndex: 0, nowMs: baseMs }),
    baseMs + 194 * MINUTE,
    "Resolve last-post, queue and peer-draft conflicts with 1h + jitter without reserving another account's queue"
);
state.report = null;
state.scheduleDraftItems = [];
state.scheduleJobs = [{ accountId: "self", status: "SCHEDULED", scheduledAtEpochMs: baseMs + 48 * 60 * MINUTE }];
assert.equal(getRecommendedScheduleEpochMs(baseMs + 30 * MINUTE, { nowMs: baseMs }), baseMs + 30 * MINUTE,
    "A distant queue entry must leave the earlier free slot usable");
state.scheduleJobs = [
    { accountId: "self", status: "SENDING", scheduledAtEpochMs: baseMs - 48 * 60 * MINUTE },
    { accountId: "self", status: "RESEND_QUEUED", scheduledAtEpochMs: baseMs - 24 * 60 * MINUTE }
];
state.defaultScheduleJitterMin = 6;
assert.equal(getLatestPublishedEpochMs(baseMs), 0);
assert.equal(getRecommendedScheduleEpochMs(baseMs + 5 * MINUTE, { nowMs: baseMs }), baseMs + 66 * MINUTE,
    "In-flight work reserves the present and advances by 1h + jitter");

// Per-item jitter alignment spaces batch drafts by 61..70m each and passes 1h validation
const jitterAligned = alignBatchScheduleEpochs(
    [baseMs + 10 * MINUTE, baseMs + 15 * MINUTE, baseMs + 20 * MINUTE],
    [],
    baseMs,
    () => MIN_SCHEDULE_INTERVAL_MS + randomScheduleJitterMinutes() * MINUTE
);
assert.ok(jitterAligned[1] - jitterAligned[0] >= 61 * MINUTE && jitterAligned[1] - jitterAligned[0] <= 70 * MINUTE);
assert.ok(jitterAligned[2] - jitterAligned[1] >= 61 * MINUTE && jitterAligned[2] - jitterAligned[1] <= 70 * MINUTE);
assert.equal(validateBatchScheduleEpochs(jitterAligned, [], baseMs, MIN_SCHEDULE_INTERVAL_MS).ok, true);

// 7. Automatic ordering ignores later automatic drafts, respects manual/focused inputs, and never drifts on repeat.
state.scheduleJobs = [];
const auto0 = draftAt(baseMs + 5 * MINUTE);
auto0.recommendedJitterMin = 3; // 63m
const auto1 = draftAt(baseMs + 125 * MINUTE);
const manual2 = draftAt(baseMs + 60 * MINUTE, true);
state.scheduleDraftItems = [auto0, auto1, manual2];
assert.equal(getRecommendedScheduleEpochMs(baseMs + 5 * MINUTE,
    { draftIndex: 0, nowMs: baseMs, includeLaterAutoDrafts: false }), baseMs + 123 * MINUTE);
assert.equal(getRecommendedScheduleEpochMs(baseMs + 5 * MINUTE,
    { draftIndex: 0, nowMs: baseMs }), baseMs + 188 * MINUTE);
state.scheduleDraftItems = [
    draftAt(baseMs + 5 * MINUTE, false, "Keep first body"),
    draftAt(baseMs + 120 * MINUTE, true, "Keep manual body"),
    draftAt(baseMs + 125 * MINUTE, false, "Keep following body"),
    draftAt(baseMs + 400 * MINUTE, false, "Keep focused body")
];
const preservedFiles = [makeFile("kept.png", "image/png", 100)];
state.scheduleDraftItems[2].files = preservedFiles;
state.scheduleDraftItems.forEach((item, index) => timeInputs.set(index, { value: item.scheduledAtValue }));
const manualValue = state.scheduleDraftItems[1].scheduledAtValue;
const focusedValue = state.scheduleDraftItems[3].scheduledAtValue;
documentStub.activeElement = timeInputs.get(3);
documentStub.activeElement.value = ""; // A date input temporarily cleared while its user is editing.
refreshAutomaticScheduleTimes(0, baseMs);
assert.equal(state.scheduleDraftItems[1].scheduledAtValue, manualValue);
assert.equal(timeInputs.get(1).value, manualValue);
assert.equal(state.scheduleDraftItems[3].scheduledAtValue, focusedValue);
assert.equal(timeInputs.get(3).value, "");
const prevAnchorMs = Math.max(
    parseDraftItemEpochMs(state.scheduleDraftItems[0]),
    parseDraftItemEpochMs(state.scheduleDraftItems[1])
);
const followingGapMs = parseDraftItemEpochMs(state.scheduleDraftItems[2]) - prevAnchorMs;
assert.ok(followingGapMs >= 61 * MINUTE && followingGapMs <= 70 * MINUTE, `Expected 61..70m gap, got ${followingGapMs / MINUTE}m`);
assert.equal(timeInputs.get(2).value, state.scheduleDraftItems[2].scheduledAtValue);
assert.equal(state.scheduleDraftItems[2].text, "Keep following body");
assert.equal(state.scheduleDraftItems[2].files, preservedFiles);
const settledTimes = state.scheduleDraftItems.map((item) => item.scheduledAtValue);
refreshAutomaticScheduleTimes(0, baseMs);
assert.deepEqual(state.scheduleDraftItems.map((item) => item.scheduledAtValue), settledTimes,
    "Repeated queue refreshes must not keep pushing automatic drafts forward");

// A successful create may appear in the refreshed queue before its draft is cleared.
resetScheduleState(baseMs);
const submittedDraft = draftAt(baseMs + 30 * MINUTE, false, "Submitted body");
submittedDraft.recommendedJitterMin = 8; // 68m
state.scheduleDraftItems = [submittedDraft];
state.scheduleJobs = [{ accountId: "self", status: "SCHEDULED", scheduledAtEpochMs: baseMs + 30 * MINUTE }];
const submittingValue = state.scheduleDraftItems[0].scheduledAtValue;
timeInputs.set(0, { value: submittingValue });
state.scheduleMutations.add("create");
const submittingId = state.composerClientRequestId;
const submittingRotations = state.composerRotationCount;
assert.equal(refreshAutomaticScheduleTimes(0, baseMs), false);
assert.equal(state.scheduleDraftItems[0].scheduledAtValue, submittingValue);
assert.equal(timeInputs.get(0).value, submittingValue);
assert.equal(state.composerClientRequestId, submittingId);
assert.equal(state.composerRotationCount, submittingRotations);
state.scheduleMutations.delete("create");
assert.equal(refreshAutomaticScheduleTimes(0, baseMs), true);
assert.equal(parseDraftItemEpochMs(state.scheduleDraftItems[0]), baseMs + 98 * MINUTE);
assert.equal(timeInputs.get(0).value, state.scheduleDraftItems[0].scheduledAtValue);
assert.equal(state.composerRotationCount, submittingRotations + 1);

// 8. Preset labels follow max(lastPost + 1h + 1..10m, now + 1..10m) and stay 100% synced with input draft.
const presetNowMs = new Date(2026, 10, 14, 19, 30).getTime();
resetScheduleState(presetNowMs);
state.report = liveReport(presetNowMs - 10 * MINUTE); // 19:20
const presetDraft = draftAt(presetNowMs + 5 * MINUTE);
presetDraft.recommendedJitterMin = 5; // 65m -> 19:20 + 65m = 20:25
state.scheduleDraftItems = [presetDraft];
const presets = Array.from(buildScheduleQuickPresets(presetNowMs));
assert.ok(presets.length > 0);
assert.equal(new Set(presets.map((preset) => preset.epochMs)).size, presets.length);
const recommended = presets.find((preset) => preset.epochMs === presetNowMs + 55 * MINUTE);
assert.ok(recommended, "Recent post 10m ago (19:20) shifts recommended preset by 1h + 5m to 20:25");
assert.match(recommended.label, /20:25/);

// Screenshot regression: at 20:14 with last post at 17:51 (18:51 already passed!),
// both input draft and recommended chip resolve to the EXACT same >= +15m 5-min rounded slot (20:30, never N hours later!)
const shotNowMs = new Date(2026, 10, 22, 20, 14).getTime();
resetScheduleState(shotNowMs);
state.report = liveReport(new Date(2026, 10, 22, 17, 51).getTime());
state.scheduleDraftItems = [];
// Even if buildScheduleQuickPresets runs BEFORE createScheduleDraftItem, both share the exact same recommended time!
const preInitPresets = Array.from(buildScheduleQuickPresets(shotNowMs));
state.scheduleDraftItems = [createScheduleDraftItem("", null, [], false)];
const postInitPresets = Array.from(buildScheduleQuickPresets(shotNowMs));
const draftEpochMs = parseDraftItemEpochMs(state.scheduleDraftItems[0]);
assert.ok(
    draftEpochMs >= shotNowMs + 15 * MINUTE && draftEpochMs <= shotNowMs + 20 * MINUTE,
    `Expected draft at 20:14 (with last post 17:51) to be 20:29..20:34, got ${state.scheduleDraftItems[0].scheduledAtValue}`
);
assert.equal(preInitPresets[0].epochMs, draftEpochMs, "Pre-init recommended chip matches initialized draft minute");
assert.equal(postInitPresets[0].epochMs, draftEpochMs, "Post-init recommended chip matches input draft minute");
for (const preset of presets) {
    assert.ok(preset.label.includes(formatScheduleLocalTime(preset.epochMs, presetNowMs)));
}
const yearEnd = new Date(2026, 11, 31, 23, 45).getTime();
assert.match(formatScheduleLocalTime(new Date(2026, 11, 31, 23, 50).getTime(), yearEnd), /今天.*23:50/);
assert.match(formatScheduleLocalTime(new Date(2027, 0, 1, 0, 15).getTime(), yearEnd), /明天.*00:15/);
assert.match(formatScheduleLocalTime(new Date(2027, 0, 2, 9, 0).getTime(), yearEnd), /01[/-]02.*09:00/);
const crossDayInput = formatEpochToDatetimeLocalValue(new Date(2027, 0, 1, 0, 15).getTime());
assert.equal(parseDraftItemEpochMs({ scheduledAtValue: crossDayInput }), new Date(2027, 0, 1, 0, 15).getTime());

// 9. A dense queue with more than 200 collisions must still return a free time.
const denseOccupied = Array.from({ length: 240 }, (_, i) => baseMs + i * 30 * MINUTE).reverse();
assert.equal(computeNextAvailableScheduleMs(baseMs, denseOccupied),
    baseMs + 239 * 30 * MINUTE + MIN_SCHEDULE_INTERVAL_MS);

const scheduleRestore = sliceBetween(appJs,
    "    async function restoreScheduleDraftAfterStepUpAuth(", "    async function triggerXPublishAuth(");
const fullLongText = "字".repeat(25050);
const ownerState = {authStatus: {user: {id: "bound-owner"}}, userScopeGeneration: 1, scheduleDraftItems: []};
const restoreContext = vm.createContext({
    state: ownerState, stableUserId: (user) => user.id, isLoggedIn: () => true,
    sessionStorage: {getItem: () => JSON.stringify({
        userId: "bound-owner", savedAt: Date.now(), items: [{text: fullLongText,
            sourceRef: {assistantDraftId: "saved-draft", assistantDraftVersion: 2}}]
    })},
    createScheduleDraftItem: (text, epoch, files, touched, sourceRef) => ({text, sourceRef}),
    cancelScheduleDraftPersistence() {}, rotateComposerRequestId() {}, renderScheduleDraftItems() {},
    syncScheduleComposerStats() {}, syncScheduleAuthBanner() {}, syncScheduleDraftUnloadProtection() {},
    hasScheduleDraftContent: (item) => Boolean(item.text), STEPUP_SCHEDULE_DRAFT_KEY: "draft",
    SCHEDULE_DRAFT_TTL_MS: 30000, MAX_BATCH_SCHEDULE_ITEMS: 20
});
vm.runInContext(scheduleRestore, restoreContext);
(async () => {
    await restoreContext.restoreScheduleDraftAfterStepUpAuth();
    assert.equal(ownerState.scheduleDraftItems[0].text, fullLongText);
    assert.equal(ownerState.scheduleDraftItems[0].sourceRef.assistantDraftId, "saved-draft");
    assert.equal(ownerState.scheduleDraftItems[0].sourceRef.assistantDraftVersion, 2);
    ownerState.authStatus.user.id = "other-account";
    ownerState.scheduleDraftItems = [];
    await restoreContext.restoreScheduleDraftAfterStepUpAuth();
    assert.equal(ownerState.scheduleDraftItems.length, 0, "different account cannot restore an owner draft");
    console.log("scheduled-tweet-regression: passed");
})().catch(error => { console.error(error); process.exitCode = 1; });
