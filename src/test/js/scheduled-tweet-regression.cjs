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
    "    const MIN_SCHEDULE_INTERVAL_MS =",
    "    function formatScheduleEpochLabel("
);

const harnessCode = `
${urlTokenReSlice};
${helpersSlice}
${batchHelpersSlice}
this.__exports = {
    computeWeightedTweetLength,
    validateAndMergeScheduleFiles,
    getTzDateParts,
    zonedWallClockToEpochMs,
    computeNextGoldenSlotTimestampMs,
    MIN_SCHEDULE_INTERVAL_MS,
    computeNextAvailableScheduleMs,
    validateBatchScheduleEpochs,
    alignBatchScheduleEpochs
};
`;

const context = vm.createContext({
    URL: { createObjectURL: () => "blob:mock" },
    Intl,
    Date,
    Math,
    Number,
    String,
    Array,
    Set
});

vm.runInContext(harnessCode, context, { filename: "app-scheduled-tweet-helpers.js" });
const {
    computeWeightedTweetLength,
    validateAndMergeScheduleFiles,
    getTzDateParts,
    computeNextGoldenSlotTimestampMs,
    MIN_SCHEDULE_INTERVAL_MS,
    computeNextAvailableScheduleMs,
    validateBatchScheduleEpochs,
    alignBatchScheduleEpochs
} = context.__exports;

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

// 3. computeNextGoldenSlotTimestampMs timezone calculations
// 2026-11-14T08:00:00Z = Saturday 16:00:00 in Asia/Shanghai
const nowMs = Date.UTC(2026, 10, 14, 8, 0, 0);
const sameDayEvening = computeNextGoldenSlotTimestampMs("周六 20:00–22:00", "Asia/Shanghai", nowMs);
const eveningParts = getTzDateParts(sameDayEvening, "Asia/Shanghai");
assert.equal(eveningParts.year, 2026);
assert.equal(eveningParts.month, 11);
assert.equal(eveningParts.day, 14);
assert.equal(eveningParts.weekday, 6);
assert.equal(eveningParts.hour, 20);
assert.equal(eveningParts.minute, 0);

// Earlier Saturday slot rolls +7 days to 2026-11-21
const nextWeekAfternoon = computeNextGoldenSlotTimestampMs("周六 15:00–17:00", "Asia/Shanghai", nowMs);
const nextWeekParts = getTzDateParts(nextWeekAfternoon, "Asia/Shanghai");
assert.equal(nextWeekParts.year, 2026);
assert.equal(nextWeekParts.month, 11);
assert.equal(nextWeekParts.day, 21);
assert.equal(nextWeekParts.weekday, 6);
assert.equal(nextWeekParts.hour, 15);
assert.equal(nextWeekParts.minute, 0);

// America/New_York resolution (at 2026-11-14T08:00:00Z it is Saturday 03:00 EST)
const nySlot = computeNextGoldenSlotTimestampMs("周一 09:00–11:00", "America/New_York", nowMs);
const nyParts = getTzDateParts(nySlot, "America/New_York");
assert.equal(nyParts.year, 2026);
assert.equal(nyParts.month, 11);
assert.equal(nyParts.day, 16);
assert.equal(nyParts.weekday, 1);
assert.equal(nyParts.hour, 9);
assert.equal(nyParts.minute, 0);

// 4. Multi-tweet batch scheduling & mandatory 1-hour minimum interval
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

console.log("scheduled-tweet-regression: passed");
