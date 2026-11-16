"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const appPath = path.resolve(__dirname, "../../main/resources/static/app.js");
const source = fs.readFileSync(appPath, "utf8");

function sourceSection(startMarker, endMarker) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start + startMarker.length);
    assert.ok(start >= 0 && end > start, "Production renderer extraction markers must exist");
    return source.slice(start, end);
}

// Only the fields this renderer writes are needed; execute production code unchanged.
const nodes = Object.fromEntries([
    "algoReachPoolsGrid", "algoCalibrationPanel", "algoOpsPlaybookList",
    "algoPlainTipsBox", "draftLiveTimingHint"
].map(id => [id, { innerHTML: "", textContent: "", hidden: true }]));
const cadenceAndReach = {
    minRecommendedGapMinutes: 180, oonFollowerMultiple: 1, lowReachFollowerRatio: 0.05
};
const context = vm.createContext({
    $: id => nodes[id] || null,
    DEFAULT_ALGO_PROFILE: { cadenceAndReach },
    getActiveAlgoProfile: () => ({ cadenceAndReach }),
    renderAlgoConfigDrawer() {},
    evaluatePrePostDraft() {}
});
vm.runInContext([
    sourceSection("    function escapeHtml(", "    const DEFAULT_ALGO_PROFILE ="),
    sourceSection("    function fmtNum(", "    function safeAvatarSrc("),
    sourceSection("    function opsActionButtonLabel(", "    const KNOWN_SHORTENER_DOMAINS =")
].join("\n"), context, { filename: appPath });

const review = {
    code: "REVIEW_LOW_VIEW_POST",
    priority: "LOW",
    title: '浏览量低于历史常态 <候选正文> "引号" & 复盘',
    detail: '11/16 09:30 · <script>alert("x")</script> & \'正文\'',
    externalUrl: "https://x.com/example/status/2468?ref=first&source=report"
};
const legacy = {
    code: "RESIDUAL_ANOMALY_REVIEW",
    title: "互动高但曝光偏低 · 候选正文",
    detail: "LEGACY-UNRELIABLE-ASSERTION",
    externalUrl: "https://x.com/example/status/2468"
};
const health = {
    viewSampleCount: 6, evaluatedPostCount: 6,
    outOfNetworkPostCount: 1, inNetworkPostCount: 4, lowReachPostCount: 1,
    calibration: {
        samplePostCount: 6, medianViews: 800, medianFollowerReachMultiple: 2,
        mutualShareKnown: false, coldStartEligibleAccount: true,
        coldStartPassRatePct: 50, coldStartPassCount: 3,
        replyTemplateRisk: { evaluatedReplyCount: 8, copyPastaRiskTriggered: false },
        domainStats: [{ domain: "example.com", avgViews: 400, vsNativePct: -20 }],
        // A stored report may retain both the former inline record and its old ops card.
        residualAnomalies: [{
            createdLabel: "10月19日 10:27", textPreview: "HISTORICAL-ONLY · 候选正文",
            actualViews: 8, tweetUrl: "https://x.com/example/status/2468"
        }]
    }
};
function render(opsRecommendations) {
    context.renderAlgoPoolAndSimulator({
        algorithmHealth: { ...health, opsRecommendations }
    });
    return nodes.algoOpsPlaybookList.innerHTML;
}
function occurrences(text, fragment) {
    return text.split(fragment).length - 1;
}

const html = render([
    legacy, review,
    { code: "ORDINARY_EXTERNAL", title: "其他建议", detail: "普通建议详情",
      externalUrl: "https://example.com/help", priority: "MEDIUM" },
    { code: "REPLY_UNANSWERED", title: "待回复评论", detail: "回复现有评论",
      actionTarget: "tab:interactions;filter:UNREPLIED_POST", priority: "HIGH" }
]);
const combined = nodes.algoCalibrationPanel.innerHTML + html;
assert.doesNotMatch(combined, /互动高但曝光偏低|HISTORICAL-ONLY|LEGACY-UNRELIABLE-ASSERTION|RESIDUAL_ANOMALY_REVIEW/,
    "Stored residual records must not reintroduce the removed assertion or standalone row");
assert.equal(occurrences(combined, "候选正文"), 1,
    "A candidate must appear once, without the historical inline/legacy duplicates");
assert.equal(nodes.algoCalibrationPanel.hidden, false);
assert.match(nodes.algoCalibrationPanel.innerHTML, /日常单帖浏览/);
assert.match(nodes.algoCalibrationPanel.innerHTML, /常用外链/,
    "Removing residual rows must preserve unrelated calibration content");

const cards = html.split('<div class="algo-ops-item">').slice(1);
assert.equal(cards.length, 3);
for (const card of cards) {
    for (const className of ["algo-ops-main", "algo-ops-title-row", "algo-ops-title", "algo-ops-detail"]) {
        assert.ok(card.includes('class="' + className + '"'),
            "Review and ordinary recommendations must share the existing card structure");
    }
    assert.match(card, /class="algo-ops-badge prio-(?:low|medium|high)"/);
    assert.match(card, /class="algo-ops-go"/);
}
assert.equal(nodes.algoOpsPlaybookList.hidden, false);
assert.equal(occurrences(html, ">查看推文 ↗</a>"), 1);
assert.equal(occurrences(html, ">前往 ↗</a>"), 1);
assert.match(cards[0], /href="https:\/\/x\.com\/example\/status\/2468\?ref=first&amp;source=report" target="_blank" rel="noopener noreferrer">查看推文 ↗<\/a>/);
assert.match(cards[1], /href="https:\/\/example\.com\/help" target="_blank" rel="noopener noreferrer">前往 ↗<\/a>/);
assert.ok(cards[2].includes('<button type="button" class="algo-ops-go" data-go="tab:interactions;filter:UNREPLIED_POST">去回复 ›</button>'),
    "Internal recommendation actions must retain their destination and contextual label");
assert.ok(cards[0].includes('<span class="algo-ops-title">浏览量低于历史常态 &lt;候选正文&gt; &quot;引号&quot; &amp; 复盘</span>'));
assert.ok(cards[0].includes('<div class="algo-ops-detail">11/16 09:30 · &lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;正文&#39;</div>'));
assert.doesNotMatch(html, /<script>|<候选正文>/,
    "Untrusted recommendation text must render as text");

const emptyHtml = render([
    legacy,
    { code: "REVIEW_LOW_VIEW_POST", title: "MISSING-URL", detail: "No destination" },
    { code: "REVIEW_LOW_VIEW_POST", title: "EMPTY-URL", externalUrl: "" },
    { code: "REVIEW_LOW_VIEW_POST", title: "NULL-URL", externalUrl: null },
    { code: "REVIEW_LOW_VIEW_POST", title: "WHITESPACE-URL", externalUrl: "  \t\n " },
    { code: "REVIEW_LOW_VIEW_POST", title: "JAVASCRIPT-URL", externalUrl: "javascript:alert(1)" },
    { code: "REVIEW_LOW_VIEW_POST", title: "DATA-URL", externalUrl: "data:text/html,<script>alert(1)</script>" }
]);
assert.equal(emptyHtml, "",
    "Re-rendering stored/undeliverable reviews must clear previous cards");
assert.equal(nodes.algoOpsPlaybookList.hidden, true);

const unsafeHtml = render([
    { code: "ORDINARY_EXTERNAL", title: "安全链接", externalUrl: "javascript:alert(1)" }
]);
assert.match(unsafeHtml, /href="https:\/\/x\.com"/,
    "External actions must keep the production HTTP URL safeguard");
assert.doesNotMatch(unsafeHtml, /href="javascript:/);

console.log("insight-presentation-regression: passed (stored reports, cards, actions, escaping, refresh)");
