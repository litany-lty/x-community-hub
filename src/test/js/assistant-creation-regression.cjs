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

assert.equal(
    metricsCtx.assistantDraftResearchStatus({ research: { quality: "MATERIAL_DIRECT", fastMode: true } }).badgeLabel,
    "仅原帖依据"
);
assert.equal(
    metricsCtx.assistantDraftResearchStatus({
        research: { quality: "SUPPORTED", draftable: true, evidence: [{ id: "source-1" }, { id: "source-2" }] }
    }).badgeLabel,
    "已融合深挖 · 2 源"
);

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

// 4. Test monitored avatar click preview trigger & repost preview modal rendering
const previewEls = {};
function makePreviewEl(id) {
    return {
        id,
        hidden: true,
        disabled: false,
        readOnly: false,
        checked: false,
        value: "",
        href: "",
        innerHTML: "",
        textContent: "",
        className: "",
        dataset: {},
        replaceChildren() {},
        contains() { return false; },
        classList: {
            set: new Set(),
            add(...names) { names.forEach((n) => this.set.add(n)); },
            remove(...names) { names.forEach((n) => this.set.delete(n)); },
            toggle(name, force) {
                const on = force === undefined ? !this.set.has(name) : Boolean(force);
                if (on) this.set.add(name);
                else this.set.delete(name);
                return on;
            },
            contains(name) { return this.set.has(name); }
        }
    };
}
[
    "repostPreviewTitle", "repostPreviewSourceAvatar", "repostPreviewSourceVerified",
    "repostPreviewSourceHandle", "repostPreviewRuleBadge", "repostPreviewProfileLink",
    "repostPreviewSubbar", "repostPreviewFilterBar", "repostPreviewEvalRestBtn",
    "repostPreviewTimelineList", "assistantSourcesList"
].forEach((id) => { previewEls[id] = makePreviewEl(id); });

const previewCtx = createContext({
    state: {
        authStatus: { user: { username: "elidlicom", displayName: "Litany" } },
        report: { profile: { username: "elidlicom", displayName: "Litany", avatarUrl: "/author-avatar.webp" } }
    },
    $: (id) => (previewEls[id] ||= makePreviewEl(id)),
    assistantKeyValueRows: () => "",
    renderAssistantHistory: () => "",
    avatarHtml: (u) => `<span class="mock-avatar">${u.username}</span>`,
    verifiedSvgHtml: (v) => (v ? "<svg class='v'/>" : ""),
    safeHttpUrl: (u) => u,
    fmtNum: (n) => String(n),
    document: { activeElement: null }
});
vm.runInContext(
    section("    const repostPreviewState = {", "    function assistantStatusLabel(")
    + "\nthis.__repostPreviewState = repostPreviewState;\n"
    + section("    function formatAssistantDateTime(", "    function resolveTopicAuthors(")
    + "\n"
    + section("    function renderAssistantMonitor(", "    function renderContentAssistant("),
    previewCtx
);

const chipHtml = previewCtx.assistantAuthorChipHtml(
    { sourceId: "src-openai", username: "sama", displayName: "Sam Altman", verified: true },
    { materialId: "1920001" }
);
assert.match(chipHtml, /class="assistant-avatar-preview-btn"/);
assert.match(chipHtml, /data-source-repost-preview="src-openai"/);
assert.match(chipHtml, /data-repost-preview-material-id="1920001"/);

previewCtx.renderAssistantMonitor({
    sources: [{ id: "src-openai", username: "sama", displayName: "Sam Altman", status: "ACTIVE", intervalMinutes: 5 }],
    settings: { provider: "chatgpt", model: "gpt-6.2-astra low", version: 1 }
});
assert.match(previewEls.assistantSourcesList.innerHTML, /class="assistant-avatar-preview-btn" data-source-repost-preview="src-openai"/);

previewCtx.__repostPreviewState.owner = { username: "elidlicom", displayName: "Litany", avatarUrl: "/author-avatar.webp" };
previewCtx.__repostPreviewState.source = {
    id: "src-openai", username: "sama", displayName: "Sam Altman", verified: true,
    autoRepostEnabled: true, textMode: "AI", minScore: 70
};
previewCtx.__repostPreviewState.items = [
    {
        material: {
            id: "1920005", username: "sama", authorName: "Sam Altman", authorVerified: true,
            publishedAt: 1732453600000,
            text: "Follow-up benchmark chart 1 hour later.",
            sourceUrl: "https://x.com/sama/status/1920005", views: 80000, likes: 2200
        },
        decision: {
            evaluated: true, wouldAutoRepost: true, contentPass: true, viralPass: true, accountFit: true,
            viralScore: 78, factScore: 85, trendScore: 76, attentionScore: 74,
            minScore: 70, fitScore: 86, decisionCode: "WOULD_REPOST",
            reason: "补充实测图表，契合AI工程定位",
            commentText: "第二组吞吐曲线印证了长上下文缓存的收益。"
        }
    },
    {
        material: {
            id: "1920001", username: "sama", authorName: "Sam Altman", authorVerified: true,
            publishedAt: 1732450000000,
            text: "Reasoning cost dropped 10x in three months.",
            sourceUrl: "https://x.com/sama/status/1920001", views: 120000, likes: 3400
        },
        decision: {
            evaluated: true, wouldAutoRepost: true, contentPass: true, viralPass: true, accountFit: true,
            viralScore: 82, factScore: 88, trendScore: 80, attentionScore: 79,
            minScore: 70, fitScore: 89, category: "MODEL_RELEASE", decisionCode: "WOULD_REPOST",
            reason: "一手成本拐点数据，契合AI工程定位",
            commentText: "推理成本三个月降十倍，真正改变的是 Agent 落地的盈亏平衡线。"
        }
    },
    {
        material: {
            id: "1919990", username: "sama", authorName: "Sam Altman", authorVerified: true,
            publishedAt: 1732440000000,
            text: "Great basketball game tonight!",
            sourceUrl: "https://x.com/sama/status/1919990", views: 95000, likes: 4100
        },
        decision: {
            evaluated: true, wouldAutoRepost: false, contentPass: false, viralPass: true, accountFit: false,
            viralScore: 88, factScore: 60, trendScore: 85, attentionScore: 90,
            minScore: 70, fitScore: 36, decisionCode: "SKIP_NOT_FIT",
            reason: "虽有讨论度但属球赛闲聊，不契合当前账号受众",
            commentText: ""
        }
    }
];
previewCtx.renderRepostPreviewTimeline();
assert.equal(previewEls.repostPreviewTitle.textContent, "Sam Altman");
assert.match(previewEls.repostPreviewRuleBadge.textContent, /自动转推 · 潜力≥70/);
assert.match(previewEls.repostDualCdBar.innerHTML, /全局 CD · 就绪/);
assert.match(previewEls.repostDualCdBar.innerHTML, /账号 CD · 就绪/);
assert.doesNotMatch(previewEls.repostDualCdBar.innerHTML, /repost-dual-cd-note/);
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /全部推文 3/);
// 1920001 (-1h earlier) wins WOULD_REPOST; 1920005 (+1h later, <16h!) becomes SKIP_AUTHOR_COOLDOWN
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /会转发 1/);
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /不转发 2/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /潜力 82 · 契合 89/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /title="事实 88 · 热门 80 · 关注 79"/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /模型发布/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, />账号 CD</);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, />不契合</);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /推理成本三个月降十倍/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-repost-publish-item="1920001"/);
assert.equal(previewCtx.resolveRepostQuoteTweetId({ id: "1920001" }), "1920001");
assert.equal(previewCtx.resolveRepostQuoteTweetId({ id: "note-1", sourceUrl: "https://x.com/sama/status/1920099" }), "1920099");
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-repost-write-item="1919990"/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /核心看点：|值得关注的最新动向：/);

previewCtx.__repostPreviewState.editedComments.set("1920001", "手动微调后的转发附文");
assert.equal(
    previewCtx.buildRepostItemComposedText("1920001"),
    "手动微调后的转发附文\nhttps://x.com/sama/status/1920001"
);

previewCtx.__repostPreviewState.filterMode = "REPOST";
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /1920001/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /1920005/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /1919990/);

// Priority Dual-CD short-circuit: when Global CD [1h + N(1..10m)] is active, ALL items immediately resolve to Not Satisfied
previewCtx.__repostPreviewState.filterMode = "ALL";
previewCtx.__repostPreviewState.source.ownerInTweetCooldown = true;
previewCtx.__repostPreviewState.source.ownerTweetJitterMinutes = 7;
previewCtx.__repostPreviewState.source.ownerCooldownRemainingMinutes = 24;
previewCtx.__repostPreviewState.source.postPolishEnabled = true;
previewCtx.__repostPreviewState.source.postPolishPrompt = "去掉老气评委腔，改成朋友随口吐槽";
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostPreviewRuleBadge.textContent, /潜力≥70/);
assert.doesNotMatch(previewEls.repostPreviewRuleBadge.textContent, /冷却中/);
assert.match(previewEls.repostDualCdBar.innerHTML, /全局 CD · 余 24m/);
assert.match(previewEls.repostDualCdBar.innerHTML, /1h\+7m/);
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /会转发 0/);
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /不转发 3/);
assert.equal(previewEls.repostPreviewEvalRestBtn.disabled, false);
assert.equal(previewEls.repostPreviewEvalRestBtn.textContent, "重新判定");
assert.match(previewEls.repostPreviewTimelineList.innerHTML, />全局 CD</);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /1小时\+7分钟/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /潜力 82 · 契合 89/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /一手成本拐点数据/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /手动微调后的转发附文/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /第二组吞吐曲线印证了长上下文缓存的收益/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, />不契合</);
assert.equal(previewEls.repostPolishPromptInput.value, "去掉老气评委腔，改成朋友随口吐槽");
assert.equal(previewEls.repostPolishPromptToggleBtn.textContent, "二次改写");
assert.equal(previewEls.repostPolishPromptToggleBtn.classList.contains("is-on"), true);

// Even unevaluated / cdShortCircuited cards keep manual preview & quote-draft buttons during CD
previewCtx.__repostPreviewState.items.push({
    material: {
        id: "1919980", username: "sama", authorName: "Sam Altman", authorVerified: true,
        publishedAt: 1732435000000,
        text: "Unevaluated tweet during global cooldown.",
        sourceUrl: "https://x.com/sama/status/1919980"
    },
    decision: { evaluated: false, cdShortCircuited: true, decisionCode: "SKIP_COOLDOWN" }
});
previewCtx.renderRepostPreviewTimeline();
assert.equal(previewEls.repostPreviewEvalRestBtn.textContent, "合并判定");
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-repost-eval-item="1919980"/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-repost-write-item="1919980"/);
previewCtx.__repostPreviewState.items = previewCtx.__repostPreviewState.items.filter((it) => it.material.id !== "1919980");

// Direct-pass categories (MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET) bypass score threshold AND 16h Author CD when Global CD is ready
previewCtx.__repostPreviewState.source.ownerInTweetCooldown = false;
previewCtx.__repostPreviewState.source.authorInCooldown = true;
previewCtx.__repostPreviewState.source.authorCooldownRemainingMinutes = 480;
previewCtx.__repostPreviewState.items.unshift({
    material: {
        id: "1920010", username: "sama", authorName: "Sam Altman",
        text: "All Plus & Pro weekly usage limits have been reset!",
        publishedAt: Date.now() - 120 * 1000,
        sourceUrl: "https://x.com/sama/status/1920010"
    },
    decision: {
        evaluated: true, wouldAutoRepost: true, contentPass: true, directPass: true,
        viralPass: true, accountFit: true, viralScore: 54, factScore: 86, trendScore: 60, attentionScore: 30,
        minScore: 70, fitScore: 95, category: "QUOTA_RESET", decisionCode: "WOULD_REPOST",
        reason: "官方额度重置通知，直通转发",
        commentText: "🎉 官方把调用额度全量重置啦!!😺\n\n💡 省流重点：Plus 和 Pro 额度恢复满格。"
    }
});
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostDualCdBar.innerHTML, /账号 CD · 余 8h/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /额度重置 · 直通/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /潜力 54 · 契合 95/);

// On-demand generation target selection (ChatGPT subscription vs Google Gemini API)
assert.deepEqual(
    { ...previewCtx.parseGenerationTargetSpec("chatgpt|gpt-6.2-astra high") },
    { generationProvider: "chatgpt", generationModel: "gpt-6.2-astra high" }
);
assert.deepEqual(
    { ...previewCtx.parseGenerationTargetSpec("google|gemini-4-carbon") },
    { generationProvider: "google", generationModel: "gemini-4-carbon" }
);
assert.deepEqual(
    { ...previewCtx.parseGenerationTargetSpec("openai|gpt-6.2-astra high") },
    { generationProvider: "openai", generationModel: "gpt-6.2-astra high" }
);
assert.deepEqual(
    { ...previewCtx.parseGenerationTargetSpec("openai|") },
    { generationProvider: "openai", generationModel: "gpt-6.2-sol" }
);
const genOptionsHtml = previewCtx.renderGenerationTargetOptionsHtml("google|gemini-4-carbon");
assert.match(genOptionsHtml, /ChatGPT 订阅额度（PI Agent）/);
assert.match(genOptionsHtml, /OpenAI API（官方）/);
assert.match(genOptionsHtml, /OpenAI API · gpt-6\.2-sol/);
assert.match(genOptionsHtml, /Google Gemini API/);
assert.match(genOptionsHtml, /value="google\|gemini-4-carbon" selected/);
previewEls.assistantGenTargetSelect = { value: "" };
previewEls.assistantOptimizeGenTargetSelect = { value: "" };
previewEls.repostGenTargetSelect = { value: "" };
previewEls.assistantGenEffortSelect = { value: "" };
previewEls.repostGenEffortSelect = { value: "" };
previewCtx.syncGenerationTargetSelects("google|gemini-4-neon", "high");
assert.equal(previewEls.assistantGenTargetSelect.value, "google|gemini-4-neon");
assert.equal(previewEls.assistantOptimizeGenTargetSelect.value, "google|gemini-4-neon");
assert.equal(previewEls.repostGenTargetSelect.value, "");
assert.equal(previewEls.assistantGenEffortSelect.value, "high");
assert.deepEqual(
    { ...previewCtx.parseGenerationTargetSpec("openai|gpt-6.2-sol", "xhigh") },
    { generationProvider: "openai", generationModel: "gpt-6.2-sol xhigh" }
);
assert.deepEqual(
    { ...previewCtx.parseGenerationTargetSpec("minimax|MiniMax-M2.7", "high") },
    { generationProvider: "minimax", generationModel: "MiniMax-M2.7" }
);

// History archive status copy (completed backfill vs in-progress backfill)
const historyCtx = createContext({
    fmtNum: (n) => String(n),
    formatAssistantDateTime: (ts) => String(ts)
});
vm.runInContext(
    section("    function renderAssistantHistory(", "    function renderAssistantTask("),
    historyCtx
);
const completedHistoryHtml = historyCtx.renderAssistantHistory({
    materials: 20000,
    interactions: 6374,
    complete: true,
    sources: [{
        username: "elidlicom",
        owner: true,
        eligible: true,
        timelineExhausted: true,
        inboundExhausted: true,
        timelineBackfillComplete: true,
        inboundBackfillComplete: true,
        timelineOldestAt: "11/20 15:40",
        timelineNewestAt: "11/25 15:25",
        inboundOldestAt: "11/18 14:13",
        inboundNewestAt: "11/25 15:23",
        timelineTerminalReason: "UPSTREAM_EXHAUSTED",
        inboundTerminalReason: "UPSTREAM_EXHAUSTED"
    }]
});
assert.match(completedHistoryHtml, /已归档 · 增量中/);
assert.match(completedHistoryHtml, /已拉至上游最早页/);
assert.doesNotMatch(completedHistoryHtml, /上游已明确耗尽/);
assert.doesNotMatch(completedHistoryHtml, />归档中</);
assert.doesNotMatch(completedHistoryHtml, /data-assistant-history-retry/);

const exhaustedHistoryHtml = historyCtx.renderAssistantHistory({
    materials: 120,
    interactions: 45,
    complete: false,
    sources: [{
        username: "elidlicom",
        owner: true,
        eligible: true,
        timelineExhausted: false,
        inboundExhausted: true,
        timelineBackfillComplete: false,
        inboundBackfillComplete: true,
        timelineRetryExhausted: true,
        timelineError: "免费接口连续重试 3 次未成功，等待手动触发重试"
    }]
});
assert.match(exhaustedHistoryHtml, /等待手动重试/);
assert.match(exhaustedHistoryHtml, /data-assistant-history-retry="1"/);
assert.match(exhaustedHistoryHtml, /手动重试/);

// Verify link-to-research UI bar & cache-busting asset version sync in index.html
const indexHtml = fs.readFileSync(
    path.resolve(__dirname, "../../main/resources/static/index.html"),
    "utf8"
);
assert.match(indexHtml, /id="assistantLinkResearchForm"/);
assert.match(indexHtml, /id="assistantLinkResearchInput"/);
assert.match(indexHtml, /id="assistantLinkResearchBtn"/);
assert.match(indexHtml, /id="assistantLinkFastBtn"/);
const cssVer = (indexHtml.match(/\/styles\.css\?v=([^"']+)/) || [])[1];
const jsVer = (indexHtml.match(/\/app\.js\?v=([^"']+)/) || [])[1];
assert.ok(cssVer && cssVer === jsVer, `Expected styles.css (${cssVer}) and app.js (${jsVer}) cache versions to match`);
assert.match(appSource, /\/research-link/);
assert.match(appSource, /换话题切口/);
assert.match(appSource, /补深挖增量/);
assert.match(appSource, /联网深挖出新版/);
assert.match(appSource, /data-assistant-research-upgrade/);
assert.match(previewEls.assistantSourcesList.innerHTML, /data-source-autorewrite-toggle="src-openai"/);
assert.match(previewEls.assistantSourcesList.innerHTML, /洗稿发送/);
assert.match(appSource, /已洗稿发送/);
assert.match(appSource, /已开启高流量纯文本洗稿发送/);

console.log("assistant-creation-regression: passed");
