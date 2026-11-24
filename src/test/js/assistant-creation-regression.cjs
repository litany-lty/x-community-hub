const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const appSource = fs.readFileSync(
    path.resolve(__dirname, "../../main/resources/static/app.js"),
    "utf8"
);

function section(startMarker, endMarker) {
    const start = appSource.indexOf(startMarker);
    assert.notEqual(start, -1, `missing start marker: ${startMarker}`);
    const end = appSource.indexOf(endMarker, start);
    assert.notEqual(end, -1, `missing end marker: ${endMarker}`);
    return appSource.slice(start, end);
}

function createContext(overrides = {}) {
    const ctx = {
        state: {
            assistantSelectedSkill: "AUTO",
            assistantSelectedTopicId: "t1"
        },
        escapeHtml: (v) => String(v ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;"),
        $: () => null,
        URL_TOKEN_RE: /https?:\/\/\S+/gi,
        ...overrides
    };
    return vm.createContext(ctx);
}

// 1. Test computeAssistantDraftMetrics & renderAssistantDraftMetricsHtml
const metricsCtx = createContext();
vm.runInContext(
    section("    function computeWeightedTweetLength(", "    function validateAndMergeScheduleFiles(")
    + "\n"
    + section("    function assistantDraftLengthText(", "    function assistantMaterialStatusBadge("),
    metricsCtx
);

const shortStandard = metricsCtx.computeAssistantDraftMetrics(
    "推理延迟直降 40% 才是这次架构更新真正的分水岭。\n\n具体看三组实测数据。"
);
assert.equal(shortStandard.tier, "STANDARD");
assert.ok(shortStandard.weighted <= 280, `expected <= 280, got ${shortStandard.weighted}`);
assert.match(shortStandard.tierLabel, /标准帖/);
assert.equal(shortStandard.hook.warn, false);
assert.equal(shortStandard.hook.label, "前两行钩子紧凑");

const premiumLong = metricsCtx.computeAssistantDraftMetrics("测".repeat(160));
assert.equal(premiumLong.tier, "PREMIUM");
assert.equal(premiumLong.weighted, 320);
assert.match(premiumLong.tierLabel, /Premium 长帖/);
assert.equal(premiumLong.hook.warn, true);
assert.equal(premiumLong.hook.label, "首段偏密，前两行宜断句留白");

const weakOpening = metricsCtx.computeAssistantDraftMetrics(
    "值得注意的是，今天我们来聊聊这个新模型的发布细节。"
);
assert.equal(weakOpening.tier, "STANDARD");
assert.equal(weakOpening.hook.warn, true);
assert.equal(weakOpening.hook.label, "首句偏铺垫，宜直接抛判断");

const denseFirstPara = metricsCtx.computeAssistantDraftMetrics("深".repeat(95) + "\n\n第二段");
assert.equal(denseFirstPara.hook.warn, true);
assert.equal(denseFirstPara.hook.label, "首段偏密，前两行宜断句留白");

const metricsHtml = metricsCtx.renderAssistantDraftMetricsHtml("值得注意的是，测试文本");
assert.match(metricsHtml, /is-warn/);
assert.match(metricsHtml, /首句偏铺垫，宜直接抛判断/);

// External link radar: external URL triggers warning, x.com/twitter.com URL does not
const externalLinkMetrics = metricsCtx.computeAssistantDraftMetrics("官方技术博客详解：https://openai.com/index/update");
assert.equal(externalLinkMetrics.hasExternalLink, true);
assert.match(
    metricsCtx.renderAssistantDraftMetricsHtml("官方技术博客详解：https://openai.com/index/update"),
    /正文含外链，宜移至首评规避降权/
);
const xLinkMetrics = metricsCtx.computeAssistantDraftMetrics("原帖直达：https://x.com/sama/status/190001");
assert.equal(xLinkMetrics.hasExternalLink, false);
assert.doesNotMatch(
    metricsCtx.renderAssistantDraftMetricsHtml("原帖直达：https://x.com/sama/status/190001"),
    /正文含外链/
);
const twitterLinkMetrics = metricsCtx.computeAssistantDraftMetrics("旧域名：https://twitter.com/sama/status/190001");
assert.equal(twitterLinkMetrics.hasExternalLink, false);

// Source post resolver for quote intent & direct quote publish
const resolvedPrimary = metricsCtx.resolveAssistantDraftSourcePost(
    { id: "d1", materialId: "190002" },
    [
        { id: "190001", username: "firstuser" },
        { id: "190002", username: "sama" }
    ]
);
assert.deepEqual({ ...resolvedPrimary }, { tweetId: "190002", url: "https://x.com/sama/status/190002" });

const resolvedFallback = metricsCtx.resolveAssistantDraftSourcePost(
    { id: "d2", materialId: "missing" },
    [{ id: "190001", username: "@firstuser" }]
);
assert.deepEqual({ ...resolvedFallback }, { tweetId: "190001", url: "https://x.com/firstuser/status/190001" });

const resolvedNonNumeric = metricsCtx.resolveAssistantDraftSourcePost(
    { id: "d3", materialId: "note-1" },
    [{ id: "note-1", username: "firstuser" }]
);
assert.equal(resolvedNonNumeric, null);

// 2. Test renderAssistantSkillBar AUTO chip & selection switching
const barEl = { innerHTML: "" };
const skillCtx = createContext({
    $: (id) => (id === "assistantSkillBar" ? barEl : null)
});
vm.runInContext(
    section("    function assistantWritingSkills(", "    function renderAssistantMaterialCard("),
    skillCtx
);

const sampleDashboard = {
    writingSkills: [
        { id: "BREAKING_FLASH", name: "突发快评", badge: "快讯爆款", summary: "快评" },
        { id: "DEEP_BREAKDOWN", name: "深度拆解", badge: "高藏高转", summary: "拆解" },
        { id: "SHARP_OPINION", name: "犀利观点", badge: "高评论互动", summary: "观点" },
        { id: "ACTIONABLE_LIST", name: "干货清单", badge: "高收藏转化", summary: "清单" }
    ],
    topics: [
        { id: "t1", recommendedSkillId: "DEEP_BREAKDOWN", recommendedSkillName: "深度拆解" },
        { id: "t2", recommendedSkillId: "ACTIONABLE_LIST", recommendedSkillName: "干货清单" }
    ]
};

skillCtx.renderAssistantSkillBar(sampleDashboard);
assert.equal(skillCtx.state.assistantSelectedSkill, "AUTO");
assert.match(barEl.innerHTML, /class="pill-chip sm assistant-skill-chip active" data-assistant-skill="AUTO" aria-pressed="true"/);
assert.match(barEl.innerHTML, /智能匹配<em>深度拆解<\/em>/);

// Switching selected topic updates AUTO chip hint
skillCtx.state.assistantSelectedTopicId = "t2";
skillCtx.renderAssistantSkillBar(sampleDashboard);
assert.match(barEl.innerHTML, /智能匹配<em>干货清单<\/em>/);

// Switching to manual skill updates active state
skillCtx.state.assistantSelectedSkill = "SHARP_OPINION";
skillCtx.renderAssistantSkillBar(sampleDashboard);
assert.match(barEl.innerHTML, /data-assistant-skill="AUTO" aria-pressed="false"/);
assert.match(barEl.innerHTML, /class="pill-chip sm assistant-skill-chip active" data-assistant-skill="SHARP_OPINION" aria-pressed="true"/);

// 3. Test renderAssistantMaterialCard engagement metrics rendering
const cardCtx = createContext({
    fmtNum: (n) => (n >= 1000 ? `${(n / 1000).toFixed(n % 1000 === 0 ? 0 : 1)}K` : String(n)),
    materialText: (m) => m.text || "",
    renderAssistantMaterialBody: (m) => `<p>${m.text || ""}</p>`
});
vm.runInContext(
    section("    function assistantMaterialStatusBadge(", "    function renderAssistantTopicDetail("),
    cardCtx
);
const hotCardHtml = cardCtx.renderAssistantMaterialCard(
    {
        id: "1890001",
        username: "nvidia",
        text: "NVIDIA 架构白皮书",
        textStatus: "TEXT_ONLY",
        views: 50000,
        reposts: 300,
        replies: 80,
        likes: 1200
    },
    new Set()
);
assert.match(hotCardHtml, /浏览 50K · 转 300 · 评 80 · 赞 1\.2K/);
const plainCardHtml = cardCtx.renderAssistantMaterialCard(
    { id: "m-plain", username: "user1", text: "无指标素材", textStatus: "TEXT_ONLY" },
    new Set()
);
assert.doesNotMatch(plainCardHtml, /assistant-material-engagement/);

console.log("assistant-creation-regression: passed");
