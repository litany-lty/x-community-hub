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
        URL_TOKEN_RE: /https?:\/\/\S+/gi, URL,
        ...overrides
    };
    return vm.createContext(ctx);
}

// 1. Test computeAssistantDraftMetrics & renderAssistantDraftMetricsHtml
const metricsCtx = createContext();
vm.runInContext(
    section("    function computeWeightedTweetLength(", "    function validateAndMergeScheduleFiles(")
    + section("    function classifyDraftLinks(", "    function buildCalibrationShingleArray(")
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
assert.equal(resolvedFallback, null);

const resolvedNonNumeric = metricsCtx.resolveAssistantDraftSourcePost(
    { id: "d3", materialId: "note-1" },
    [{ id: "note-1", username: "firstuser" }]
);
assert.equal(resolvedNonNumeric, null);
const legacyAlias = {materialId: "note-1", research: {materialEvidence: [
    {id: "material-note-1-quoted", url: "https://x.com/other/status/303"},
    {id: "material-note-1-author", url: "https://x.com/first/status/101"}
]}};
const reassignedMaterial = [{id: "note-1", username: "second", sourceUrl: "https://x.com/second/status/202"}];
assert.deepEqual({...metricsCtx.resolveAssistantDraftSourcePost(legacyAlias, reassignedMaterial)},
    {tweetId: "101", url: "https://x.com/first/status/101"});
assert.equal(metricsCtx.resolveAssistantDraftSourcePost({materialId: "note-1"}, reassignedMaterial), null);

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
    section("    function assistantMaterialStatusBadge(", "    function renderAssistantTopicDetail(")
      + section("    function resolveRepostMaterialUrl(", "    function getRepostItemCommentText("),
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
for (const sourceUrl of ["https://x.com/other/status/40404","https://example.org/unrelated"]) {
    const material = {id:"10101", username:"glacier", text:"冰川素材", sourceUrl};
    for (const options of [{selectable:true},{showTopicJump:true}]) {
        const html = cardCtx.renderAssistantMaterialCard(material,new Set(),options);
        assert.match(html,/href="https:\/\/x\.com\/glacier\/status\/10101"/);
        assert.ok(!html.includes(sourceUrl));
    }
}
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
        authStatus: { user: { id: "42", username: "elidlicom", displayName: "Litany" } },
        report: { profile: { username: "elidlicom", displayName: "Litany", avatarUrl: "/author-avatar.webp" } }
    },
    stableUserId: user => String(user?.id || ""),
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
    section("    const PENDING_QUOTE_STORAGE_PREFIX", "    function assistantStatusLabel(")
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
assert.match(previewEls.assistantSourcesList.innerHTML, /data-source-preview-mode="REWRITE">今日洗稿</);
assert.match(previewEls.assistantRewriteStatus.textContent, /尚无监听作者开启自动洗稿/);
assert.match(previewEls.assistantRewriteStatus.textContent, /今日洗稿.*手动/);
previewCtx.renderAssistantMonitor({
    sources: [
        { id: "src-openai", status: "ACTIVE", autoRewriteEnabled: true },
        { id: "s2", status: "PAUSED", autoRewriteEnabled: true },
        { id: "s3", status: "ACTIVE", autoRepostEnabled: true }
    ]
});
assert.match(previewEls.assistantRewriteStatus.textContent, /1 位监听作者已开启自动洗稿/);

previewCtx.__repostPreviewState.owner = { accountId: "42", username: "elidlicom", displayName: "Litany", avatarUrl: "/author-avatar.webp" };
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

const mismatchedCard = previewCtx.__repostPreviewState.items.find(item => item.material.id === "1920001");
const originalLink = mismatchedCard.material.sourceUrl;
mismatchedCard.material.sourceUrl = "https://x.com/sama/status/1920099";
const correctedCard = previewCtx.renderRepostTimelineItemHtml(mismatchedCard);
assert.ok(correctedCard.includes('href="' + originalLink + '" target="_blank" rel="noopener noreferrer">原帖'));
const externalHref = correctedCard.match(/data-repost-intent-link="1920001" href="([^"]+)"/)[1];
assert.equal(new URL(externalHref).searchParams.get("text"), "手动微调后的转发附文\n" + originalLink);
assert.equal(previewCtx.buildRepostItemComposedText("1920001"), "手动微调后的转发附文\n" + originalLink);
mismatchedCard.material.sourceUrl = "https://mobile.twitter.com/sama/status/1920001/photo/1?s=20";
assert.equal(previewCtx.resolveRepostMaterialUrl(mismatchedCard.material), mismatchedCard.material.sourceUrl);
mismatchedCard.material.sourceUrl = originalLink;

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

// Beijing midnight is 16:00 UTC; the two neighboring posts share a UTC date.
const beijingMidnight = Date.parse("2026-11-27T16:00:00Z");
let rewriteClock = beijingMidnight + 3_600_000;
previewCtx.Date = class extends Date { static now() { return rewriteClock; } };
previewCtx.__repostPreviewState.items = [
    {
        material: {
            id: "rw-older", username: "sama", authorName: "Sam Altman", authorVerified: true,
            publishedAt: rewriteClock - 1000, kind: "POST", hasMedia: false,
            text: "Older high-heat pure text post on team speed.",
            sourceUrl: "https://x.com/sama/status/rw-older", views: 45000, likes: 920
        },
        decision: { evaluated: true, wouldAutoRepost: false, decisionCode: "SKIP_NOT_FIT" },
        rewrite: { candidate: true, pureText: true, highTraffic: true, rank: 2, rankScore: 98.2, exposureRate: null, evaluated: false, rewrittenText: "" }
    },
    {
        material: {
            id: "rw-media", username: "sama", authorName: "Sam Altman", authorVerified: true,
            publishedAt: rewriteClock, kind: "POST", hasMedia: true,
            text: "Image attachment tweet should not appear under 会洗稿.",
            sourceUrl: "https://x.com/sama/status/rw-media", views: 190000, likes: 4800
        },
        decision: { evaluated: true, wouldAutoRepost: true, decisionCode: "WOULD_REPOST", commentText: "转发附文" },
        rewrite: { candidate: false, pureText: false, highTraffic: true, rank: 0, rankScore: 0, ineligibleReason: "含图片或视频媒体" }
    },
    {
        material: {
            id: "rw-top", username: "sama", authorName: "Sam Altman", authorVerified: true,
            publishedAt: beijingMidnight, kind: "POST", hasMedia: false,
            text: "Recent pure-text high-traffic insight about inference economics.",
            sourceUrl: "https://x.com/sama/status/rw-top", views: 60000, likes: 1400
        },
        decision: { evaluated: true, wouldAutoRepost: false, decisionCode: "SKIP_NOT_FIT" },
        rewrite: { candidate: true, pureText: true, highTraffic: true, rank: 1, rankScore: 98.2, engagement: 525, exposureRate: 1.254, evaluated: false, rewrittenText: "" }
    },
    { material: {id: "rw-yesterday", text: "历史原帖", publishedAt: beijingMidnight - 1}, decision: {},
        rewrite: {candidate: true, rank: 3, status: "PUBLISHED", rewrittenText: "历史发送正文", publishedTweetUrl: "https://x.com/i/status/903"} },
    { material: {id: "rw-future", publishedAt: rewriteClock + 1000}, decision: {}, rewrite: {candidate: true, rank: 4} },
    { material: {id: "rw-undated"}, decision: {}, rewrite: {candidate: true, rank: 5} }
];
previewCtx.__repostPreviewState.filterMode = "ALL";
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /今日洗稿 2/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /历史发送正文/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /href="https:\/\/x\.com\/i\/status\/903"/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-schedule-item="rw-yesterday"/);

previewCtx.__repostPreviewState.filterMode = "REWRITE";
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /#1/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /#2/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /rw-media|rw-yesterday|rw-future|rw-undated/);
assert.equal(previewEls.repostPreviewEvalRestBtn.hidden, true, "the quote batch action does not evaluate rewrite candidates");
assert.equal(previewEls.repostDualCdBar.hidden, true, "the quote cooldown does not block manual rewrites");
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /热度 525/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /曝光率 125.4%/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /曝光率 0%/);
assert.ok(
    previewEls.repostPreviewTimelineList.innerHTML.indexOf("rw-top")
    < previewEls.repostPreviewTimelineList.innerHTML.indexOf("rw-older")
);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-preview-item="rw-top"/);

previewCtx.__repostPreviewState.items[2].rewrite = {
    ...previewCtx.__repostPreviewState.items[2].rewrite,
    evaluated: true,
    suitable: true,
    suitabilityScore: 91,
    hookPattern: "反直觉洞察",
    reason: "高流量纯文本结构清晰",
    rewrittenText: "算力成本一旦跌破临界点，过去不敢做的 Agent 全都成立了。\n\n真该重算一遍账了。"
};
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /适格 91/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-text-input="rw-top"/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-regen-item="rw-top"/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-copy-item="rw-top"/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-schedule-item="rw-top"/);
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /x\.com\/sama\/status\/rw-top.*data-rewrite-intent-link/);

// A server-confirmed manual draft can be edited after a low suitability judgment.
const manual = previewCtx.__repostPreviewState.items[2];
Object.assign(manual.rewrite, {candidate: false, rank: 0, status: "EVALUATED", suitable: false,
    manualReviewPreview: true, previewId: "issued-for-today"});
previewCtx.renderRepostPreviewTimeline();
assert.match(previewEls.repostPreviewFilterBar.innerHTML, /今日洗稿 1 · 待调优 1/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /待调优/);
assert.match(previewEls.repostPreviewTimelineList.innerHTML, /data-rewrite-text-input="rw-top"/);
assert.ok(previewEls.repostPreviewTimelineList.innerHTML.indexOf("rw-older") < previewEls.repostPreviewTimelineList.innerHTML.indexOf("rw-top"));
assert.doesNotMatch(previewCtx.renderRewriteTimelineItemHtml(manual, 3), /#3/);
for (const status of ["PUBLISHED", "SENDING", "EVALUATING", "UNCERTAIN"]) {
    manual.rewrite.persistedStatus = status;
    previewCtx.renderRepostPreviewTimeline();
    assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /rw-top/);
}
manual.rewrite.persistedStatus = "";
manual.material.publishedAt = beijingMidnight - 1;
previewCtx.renderRepostPreviewTimeline();
assert.doesNotMatch(previewEls.repostPreviewTimelineList.innerHTML, /rw-top/);

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
    ownerAccountId: "101",
    materials: 20000,
    interactions: 6374,
    complete: true,
    sources: [{
        authorId: "101",
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
const legacyRankedHistory = historyCtx.renderAssistantHistory({
    materials: 92, interactions: 17, complete: true,
    sources: [{username: "retired_peer", owner: false, rank: 1, eligible: true}]
});
assert.doesNotMatch(legacyRankedHistory, /retired_peer|前\s*10|>92<|>17</);
assert.match(legacyRankedHistory, /尚无作者的历史归档/);
assert.doesNotMatch(historyCtx.renderAssistantHistory({
    ownerAccountId: "101", materials: 900, complete: true,
    sources: [{authorId: "202", username: "elidlicom", owner: true}]
}), />900<|已归档 · 增量中/);

const exhaustedHistoryHtml = historyCtx.renderAssistantHistory({
    ownerAccountId: "101",
    materials: 120,
    interactions: 45,
    complete: false,
    sources: [{
        authorId: "101",
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
const evidenceAlertCtx = createContext({ safeHttpUrl: (u) => u });
vm.runInContext(
    section("    function renderAssistantEvidenceChanges(", "    function renderAssistantObservationReview("),
    evidenceAlertCtx
);
const alertHtmlForDraftB = evidenceAlertCtx.renderAssistantEvidenceChanges(
    { id: "draft-b", materialId: "mat-b", sourcesChanged: true, evidenceChanges: [{ type: "SOURCE_TEXT_UPDATED", url: "https://example.org/b" }] },
    "mat-a"
);
assert.match(alertHtmlForDraftB, /data-assistant-research-upgrade="draft-b" data-material-id="mat-b"/);
assert.doesNotMatch(alertHtmlForDraftB, /data-material-id="mat-a"/);
assert.match(previewEls.assistantSourcesList.innerHTML, /data-source-autorewrite-toggle="src-openai"/);
assert.match(previewEls.assistantSourcesList.innerHTML, /洗稿发送/);
assert.match(appSource, /已洗稿发送/);
assert.match(appSource, /已开启高流量纯文本洗稿发送/);

async function verifyDirectPublishRetries() {
    const attempts = [];
    const deliveries = new Set();
    function session(owner, version, comment) {
        const state = { authStatus: { user: { id: owner } }, assistantDashboard: { drafts: [{ id: "draft", version }] } };
        const preview = { requestToken: 1, sourceKey: "source", items: [{ material: { id: "101" } }], publishingIds: new Set(), pendingQuotes: new Map() };
        const send = async (path, body) => {
            if (body.receiptOnly) return { owner: { accountId: owner }, material: { id: "101" },
                preview: { persistedStatus: "UNCERTAIN", decisionCode: "UNCERTAIN", receiptRevision: 1 } };
            attempts.push({ path, key: body.clientRequestId });
            if (!deliveries.has(body.clientRequestId)) {
                deliveries.add(body.clientRequestId);
                throw new Error("response lost after dispatch");
            }
            return { publicationMode: "STANDALONE_FALLBACK", text: "published", publishedTweetUrl: "receipt" };
        };
        const notices = [];
        const context = createContext({ state, crypto: require("node:crypto").webcrypto, TextEncoder,
            captureUserScope: () => () => state.authStatus.user.id === owner, stableUserId: (user) => user.id,
            saveAssistantDraftEditor: async () => state.assistantDashboard.drafts[0], runAssistantMutation: (_key, path, body) => send(path, body),
            repostPreviewState: preview, getRepostItemCommentText: () => comment,
            resolveRepostQuoteTweetId: () => "101", resolveRepostMaterialUrl: () => "url", contentAssistantRequest: send,
            renderRepostPreviewTimeline() {}, setContentAssistantError() {}, formatLocalizedFetchError: (e) => e.message,
            showToast: (notice) => notices.push(notice) });
        vm.runInContext(section("    function quoteDeliveryStatus(", "    async function evaluateAuthorAccountOnceInModal(")
            + section("    async function assistantPublishRequestId(", "    function setContentAssistantError("), context);
        return { context, notices };
    }
    for (const operation of ["draft", "preview"]) {
        const click = (session) => operation === "draft"
            ? session.context.publishAssistantDraftNow("draft", true)
            : session.context.publishRepostPreviewItem("101");
        const start = attempts.length;
        await click(session("owner", 1, "original"));
        const restored = session("owner", 1, "original");
        await click(restored);
        assert.equal(attempts[start].key, attempts[start+1].key, operation + " retries the same publication after login");
        assert.ok(restored.notices.includes("原帖引用受限，已作为独立推文发布"));
        await click(session("owner", 2, "edited"));
        await click(session("other", 1, "original"));
        assert.equal(new Set(attempts.slice(start).map((call) => call.key)).size, 3, operation + " separates edits and owners");
    }
    assert.equal(deliveries.size, 6);
}
async function verifyScheduledUpdateWaitsForEditor() {
    const part = section('            const updateScheduled = event.target.closest(', '\n        });');
    for (const outcome of ["saved", "failed", "switched"]) {
        let finishSave;
        const versions = [];
        const draft = { id: "draft", version: 2 };
        const event = { target: { closest: () => ({ isConnected: true, dataset: { assistantUpdateScheduled: "intent" } }) } };
        const ctx = createContext({ event, captureUserScope: () => () => outcome !== "switched" || !finishSave,
            state: { assistantDashboard: { drafts: [draft], publications: [{ id: "intent", draftId: "draft", queueUpdatedAt: 1 }] } },
            saveAssistantDraftEditor: () => new Promise(resolve => { finishSave = resolve; }),
            newClientRequestId: () => "op", runAssistantMutation: async (_, path, payload) => versions.push(payload.version) });
        const submitted = vm.runInContext(`(async () => {${part}})()`, ctx);
        await Promise.resolve();
        assert.deepEqual(versions, [], "queue must wait for the editor save");
        finishSave(outcome === "failed" ? false : { id: "draft", version: 3 });
        await submitted;
        assert.deepEqual(versions, outcome === "saved" ? [3] : [], "queue uses the save receipt from the same owner");
    }
}

function verifyCancelledTaskCanRestart() {
    const ctx = createContext({assistantStatusClass: () => "muted", assistantStatusLabel: String,
        renderAssistantConstraintRepair: () => "", renderAssistantResearch: () => ""});
    vm.runInContext(section("    function renderAssistantTask(", "    function assistantDraftLengthText("),ctx);
    assert.match(ctx.renderAssistantTask({id:"first",kind:"RESEARCH",status:"CANCELLED"}), /data-assistant-task-action="retry"/);
    assert.doesNotMatch(ctx.renderAssistantTask({id:"done",kind:"RESEARCH",status:"READY"}), /data-assistant-task-action="retry"/);
    const repair = {kind:"OPTIMIZE",stage:"OPTIMIZE_REVISION",constraintRepair:{attempted:true},
        result:{text:"最终修订正文"},draftId:"draft",draftVersion:2};
    for (const status of ["READY","SUCCEEDED"]) {
        assert.doesNotMatch(ctx.renderAssistantTask({...repair,status}), /查看未采用的候选稿/);
    }
    assert.match(ctx.renderAssistantTask({...repair,status:"CONSTRAINTS_UNMET"}), /查看未采用的候选稿.*最终修订正文/);
}

async function verifyMaterialCreationNavigation() {
    const handlers = {};
    const elements = Object.fromEntries(["assistantTopicDetail","assistantMaterialsList","assistantDraftTitle",
        "assistantDraftSaveState","assistantDraftLength","assistantDraftNumberChanges"].map(id => [id, {
        addEventListener(name, handler) { if (name === "click") handlers[id] = handler; }
    }]));
    elements.assistantGenTargetSelect = {value:"google|gemini-4-neon"};
    elements.assistantGenEffortSelect = {value:"high"};
    elements.assistantCreationAngleInput = {value:"  核验数字  "};
    const box = {dataset:{draftId:"older",draftVersion:"1"}};
    const editor = {value:"以前的正文",closest:() => box};
    const drafts = [
        {id:"newer",topicId:"A",materialId:"101",text:"较新稿",version:1,updatedAt:200},
        {id:"older",topicId:"A",materialId:"101",text:"以前的正文",version:1,updatedAt:100}
    ];
    const dashboard = () => ({topics:[{id:"A",title:"已筛选选题"}],drafts:structuredClone(drafts),materials:[],tasks:[]});
    const state = {assistantDashboard:dashboard(),assistantSelectedTopicId:"A",assistantSelectedDraftByTopic:{A:"older"},
        assistantView:"assistant",assistantSelectedSkill:"DEEP_BREAKDOWN",assistantAccess:{allowed:true},assistantRequestGeneration:0};
    const calls = [];
    let saveFails = false;
    const ctx = createContext({state,URLSearchParams,isLoggedIn:()=>true,captureUserScope:()=>()=>true,
        $: id => id === "assistantDraftEditor" ? (state.assistantSelectedTopicId === "A" ? editor : null) : elements[id] || null,
        document:{activeElement:{},querySelectorAll:()=>[]}, clearTimeout(){}, setTimeout(){return 1;},
        setContentAssistantError: message => assert.ok(!message,message),syncScheduleDraftUnloadProtection(){},
        assistantDraftLengthText:()=>"",assistantDraftNumberChangesText:()=>"",assistantScoreText:()=>"",newClientRequestId:()=>"test",
        renderContentAssistant(){}, renderAssistantTopics: d => ctx.renderAssistantTopicDetail(d),
        runAssistantMutation: async (_,path,body) => {
            calls.push({path,body}); return {id:"task",topicId:body.materialId === "202" ? "B" : "A"};
        },
        contentAssistantRequest: async (path,body) => {
            calls.push({path,body});
            if (path === "/drafts/older/save") {
                if (saveFails) throw Error("保存冲突");
                Object.assign(drafts[1],{version:body.version+1,text:body.text});
                return structuredClone(drafts[1]);
            }
            if (path === "/topics/B/detail") return {topic:{id:"B",title:"列表之外的选题"},materials:[],tasks:[],drafts:[]};
            if (path === "/topics/A/detail") return {topic:{id:"A",title:"已筛选选题"},materials:[],tasks:[],drafts:structuredClone(drafts)};
            if (path.startsWith("/dashboard?")) return dashboard();
            assert.fail(path);
        }});
    vm.runInContext(
        section("    const DEFAULT_GENERATION_TARGET_SPEC =", "    function renderGenerationTargetOptionsHtml(")
        + section("    function selectedAssistantTopic(", "    function renderAssistantSkillBar(")
        + section("    function renderAssistantTopicDetail(", "    function assistantKeyValueRows(")
        + section("    async function saveAssistantDraftEditor()", "    async function sendAssistantDraftToSchedule(")
        + section("    async function loadContentAssistant(", "    function assistantVerificationButtons(")
        + section("    async function loadAssistantTopicDetail(", "    async function loadAssistantMaterialsFeed(")
        + section('        $("assistantMaterialsList")?.addEventListener("click"', "        const refreshReviewFilter =")
        + section('        $("assistantTopicDetail")?.addEventListener("click"', "            const researchUpgrade =")
        + "\n});",ctx);
    const click = (id, selector, dataset) => handlers[id]({target:{closest:s => s === selector ? {dataset} : null}});
    for (const mode of ["FAST","RESEARCH"]) {
        calls.length = 0;
        editor.value = "改在较旧的稿件 " + mode;
        ctx.queueAssistantDraftSave();
        await click("assistantTopicDetail","[data-assistant-research]",{assistantResearch:"101",researchMode:mode});
        assert.deepEqual(calls.map(c => c.path),["/drafts/older/save","/research"]);
        assert.equal(drafts[1].text,editor.value);
        assert.equal(drafts[0].text,"较新稿");
        assert.equal(state.assistantSelectedDraftByTopic.A,"older");
    }
    state.assistantView = "materials";
    saveFails = true;
    calls.length = 0;
    editor.value = "保存有冲突";
    ctx.queueAssistantDraftSave();
    await click("assistantMaterialsList","[data-assistant-research]",{assistantResearch:"202",researchMode:"FAST"});
    assert.deepEqual(calls.map(c => c.path),["/drafts/older/save"]);
    assert.equal(state.assistantView,"materials");
    assert.equal(state.assistantSelectedTopicId,"A");
    saveFails = false;
    calls.length = 0;
    await click("assistantMaterialsList","[data-assistant-research]",{assistantResearch:"202",researchMode:"FAST"});
    assert.deepEqual(calls.map(c => c.path),["/drafts/older/save","/research","/topics/B/detail"]);
    assert.deepEqual({...calls[1].body},{materialId:"202",mode:"FAST",regenerate:false,generationProvider:"google",
        generationModel:"gemini-4-neon high",skillId:"DEEP_BREAKDOWN",clientRequestId:"test",instruction:"核验数字"});
    assert.equal(state.assistantSelectedTopicId,"B");
    assert.match(elements.assistantTopicDetail.innerHTML,/列表之外的选题/);
    assert.deepEqual(Array.from(state.assistantDashboard.topics,t=>t.id),["A"]);

    state.assistantSelectedTopicId = "A";
    state.assistantView = "materials";
    calls.length = 0;
    await click("assistantMaterialsList","[data-assistant-open-topic]",{assistantOpenTopic:"B"});
    await ctx.loadContentAssistant(true);
    assert.equal(calls.filter(c => c.path === "/topics/B/detail").length,2);
    assert.equal(state.assistantSelectedTopicId,"B");
    assert.equal(state.assistantView,"assistant");
    assert.equal(ctx.selectedAssistantTopic(state.assistantDashboard).title,"列表之外的选题");
    assert.deepEqual(Array.from(state.assistantDashboard.topics,t=>t.id),["A"]);
    await ctx.loadAssistantTopicPage(0);
    assert.equal(state.assistantSelectedTopicId,"A","changing the page restores the selection to the filtered list");

    elements.assistantMoveTarget = { value: "" };
    ctx.document.querySelectorAll = () => [{ dataset: { assistantMaterialId: "101" } }];
    vm.runInContext('        $("assistantTopicDetail")?.addEventListener("click", async (event) => {\n'
        + section('            if (event.target.closest("#assistantMoveMaterialsBtn"))', "            const confirmTopic =")
        + "\n});", ctx);
    for (const fails of [true, false]) {
        saveFails = fails;
        editor.value = "拆分前要保存的手改";
        ctx.queueAssistantDraftSave();
        calls.length = 0;
        await click("assistantTopicDetail", "#assistantMoveMaterialsBtn", {});
        assert.deepEqual(calls.map(entry => entry.path), fails ? ["/drafts/older/save"] : ["/drafts/older/save", "/topics/move"]);
        assert.equal(editor.readOnly, false);
        if (!fails) assert.equal(drafts[1].text, "拆分前要保存的手改");
    }
}

async function verifyVisibleRevisionIsPublishedAndScheduled() {
    const blank = () => "";
    const latest = {id:"d", topicId:"topic", version:2, text:"后台成稿 v2",
        quoteSourcePost:{tweetId:"202",url:"https://x.com/b/status/202"},
        research:{snapshot:{selectedMaterialId:"202"}}};
    const dashboard = {topics:[{id:"topic"}], drafts:[latest], materials:[
        {id:"101",topicId:"topic",text:"A"}, {id:"202",topicId:"topic",text:"B"}
    ]};
    const state = {assistantDashboard:dashboard,assistantSelectedTopicId:"topic",assistantSelectedDraftByTopic:{topic:"d"},
        scheduleDraftItems:[{}],authStatus:{user:{id:"owner"}}};
    let version = "1", editor, rendered = "";
    const makeEditor = value => ({value,closest:() => ({dataset:{draftId:"d",draftVersion:version}}),focus(){},setSelectionRange(){}});
    editor = makeEditor("屏幕上的 v1");
    const container = { set innerHTML(html) {
        rendered = html;
        version = html.match(/data-draft-version="(\d+)"/)[1];
        editor = makeEditor(html.match(/id="assistantDraftEditor"[^>]*>([^<]*)</)[1]);
    } };
    const direct = [];
    const ctx = createContext({state,document:{activeElement:editor},clearTimeout(){},
        $:id => id === "assistantDraftEditor" ? editor : id === "assistantTopicDetail" ? container : null,
        captureUserScope:()=>()=>true, stableUserId:()=>"owner", assistantPublishRequestId:async()=>"req",
        assistantDraftResearchStatus:()=>({}),renderAssistantEvidenceChanges:blank,renderAssistantDraftMetricsHtml:blank,
        renderAssistantMaterialCard:blank,assistantDraftLengthText:blank,assistantDraftNumberChangesText:blank,
        renderAssistantConstraintRepair:blank,assistantWritingSkills:()=>[],renderGenerationTargetOptionsHtml:blank,
        providerSupportsEffort:()=>true,renderReasoningEffortOptionsHtml:blank,assistantScoreText:blank,
        runAssistantMutation:async(_,path,body)=>{direct.push(body);return {};},assistantPublishNotice:blank,
        setContentAssistantError:blank,ensureScheduleDraftInitialized(){},hasScheduleDraftContent:()=>false,
        rotateComposerRequestId(){},renderScheduleDraftItems(){},queueScheduleDraftPersistence(){},switchTab(){},showToast(){},
        MAX_SCHEDULE_TEXT_LENGTH:4000});
    vm.runInContext(section("    function selectedAssistantTopic(", "    function renderAssistantSkillBar(")
        + section("    function resolveAssistantDraftSourcePost(", "    function assistantDraftNumberChangesText(")
        + section("    function renderAssistantTopicDetail(", "    function assistantKeyValueRows(")
        + section("    async function saveAssistantDraftEditor(", "    function queueAssistantDraftSave(")
        + section("    async function publishAssistantDraftNow(", "    async function publishRepostPreviewItem(")
        + section("    async function sendAssistantDraftToSchedule(", "    function renderTimingPanel("), ctx);
    // The poll already installed v2 in the dashboard and the owner still sees v1. The first click refreshes the view.
    await ctx.publishAssistantDraftNow("d", true);
    assert.deepEqual(direct, []);
    assert.equal(version, "2"); assert.equal(editor.value, latest.text);
    assert.match(rendered, /data-quote-url="https:\/\/x.com\/b\/status\/202"/);
    assert.match(rendered, /data-assistant-research-upgrade="d" data-material-id="202"/);
    await ctx.publishAssistantDraftNow("d", true);
    await ctx.sendAssistantDraftToSchedule("d", true);
    assert.equal(direct[0].version, 2);
    assert.equal(state.scheduleDraftItems[0].text, latest.text);
    assert.equal(state.scheduleDraftItems[0].sourceRef.assistantDraftVersion, 2);
    assert.equal(state.scheduleDraftItems[0].quoteTweetId, "202");
}

async function verifyRetryMode() {
    let click;
    const paths = [], notices = [], decisions = [];
    const context = vm.createContext({
        $: () => ({addEventListener: (_, callback) => { click = callback; }}),
        runAssistantMutation: async (_, path, decision) => { paths.push(path); decisions.push(decision); return true; },
        state: { authStatus: { user: { username: "owner" } } },
        escapeHtml: String,
        window: { prompt: () => "https://x.com/owner/status/12345", confirm: () => true },
        showToast: message => notices.push(message)
    });
    vm.runInContext(section("    function assistantVerificationButtons(", "    async function runAssistantMutation("), context);
    vm.runInContext(section('        $("assistantAutoRepostFeed")?.addEventListener', '        $("assistantHistoryStatus")?.addEventListener'), context);
    for (const publishMode of ["REWRITE", "REPOST", ""]) {
        await click({target: {closest: selector => selector === "[data-autorepost-retry]" ? {dataset: {autorepostRetry: "101", publishMode}} : null}});
    }
    assert.deepEqual(paths, ["/auto-reposts/101/retry?mode=REWRITE", "/auto-reposts/101/retry?mode=REPOST"]);
    assert.deepEqual(notices, ["已重新提交改写稿", "已重新提交自动引用转推"]);
    for (const published of ["true", "false"]) {
        await click({target: {closest: selector => selector === "[data-assistant-verify]" ? {dataset: {
            assistantVerify: "manual-test", version: "2", published
        }} : null}});
    }
    assert.deepEqual(paths.slice(2), ["/publications/manual-test/verify", "/publications/manual-test/verify"]);
    assert.deepEqual(JSON.parse(JSON.stringify(decisions.slice(2))), [
        { version: 2, published: true, tweetId: "12345" }, { version: 2, published: false }
    ]);
    assert.match(context.assistantVerificationButtons({id: "old", source: "DIRECT_PUBLISH", status: "UNCERTAIN", version: 4}), /登记已发布/);
    assert.equal(context.assistantVerificationButtons({source: "DIRECT_PUBLISH", status: "SENDING"}), "");
    const accepted = context.assistantVerificationButtons({source: "DIRECT_PUBLISH", status: "UNCERTAIN", acceptedReceipt: {tweetId: "12345"}});
    assert.match(accepted, /正在补记回执/);
    assert.doesNotMatch(accepted, /data-assistant-verify/);
    for (const publishMode of ["REWRITE", "REPOST"]) {
        const buttons = context.assistantVerificationButtons({materialId: "101", status: "UNCERTAIN", autoKind: publishMode, version: 5});
        assert.match(buttons, /data-assistant-verify="101"/);
        assert.ok(buttons.includes(`data-publish-mode="${publishMode}"`));
        for (const published of ["true", "false"]) {
            await click({target: {closest: selector => selector === "[data-assistant-verify]" ? {dataset: {
                assistantVerify: "101", publishMode, version: "5", published
            }} : null}});
            assert.equal(paths.at(-1), `/auto-reposts/101/verify?mode=${publishMode}`);
            assert.equal(decisions.at(-1).version, 5);
            assert.equal(decisions.at(-1).published, published === "true");
        }
    }
}

async function verifySplitEditorAndPublicationViews() {
    for (const mode of ["offline","split","conflict-local","conflict-remote"]) {
        const splitDuringDetail = mode === "split" || mode === "conflict-remote";
        const conflict = mode.startsWith("conflict-");
        const topics = [{id: "A", title: "旧题"}, {id: "B", title: "新题"}];
        const stored = {id: "d", materialId: "202", topicId: "A", version: 1, text: "已保存旧稿"};
        const job = {id: "old-job", jobId: "j", topicId: "A", draftId: "d", draftVersion: 1, status: "SCHEDULED"};
        const observation = {id: "900:24", topicId: "A", draftId: "d", status: "OBSERVED", windowHours: 24, views: 357};
        const manual = {id: "901:24", topicId: "A", status: "OBSERVED", windowHours: 24, views: 91};
        // The old linked job is outside the dashboard's page, while the detail includes all editable jobs.
        const dashboard = () => ({topics, drafts: [{...stored}], publications: [], observations: [observation, manual]});
        const state = {assistantDashboard: dashboard(), assistantSelectedTopicId: "A", assistantSelectedDraftByTopic: {A: "d"},
            assistantAccess: {allowed: true}, assistantRequestGeneration: 0};
        const fields = Object.fromEntries(["assistantDraftTitle", "assistantDraftLength", "assistantDraftSaveState",
            "assistantDraftNumberChanges", "assistantTopicTasks", "assistantDraftConflict", "assistantDraftRemoteText"].map(id => [id, {}]));
        let editor, html, fail = mode === "offline";
        const pane = {set innerHTML(next) {
            html = next;
            const data = next.match(/data-draft-id="([^"]+)" data-draft-version="(\d+)"/);
            const box = data && {dataset: {draftId: data[1], draftVersion: data[2]}};
            editor = box ? {value: next.match(/id="assistantDraftEditor"[^>]*>([^<]*)</)[1], closest: () => box} : null;
        }};
        const saves = [], blank = () => "";
        const ctx = createContext({state, URLSearchParams, document: {activeElement: null, querySelectorAll: () => []},
            $: id => id === "assistantTopicDetail" ? pane : id === "assistantDraftEditor" ? editor : fields[id],
            captureUserScope: () => () => true, isLoggedIn: () => true, setTimeout: () => 1, clearTimeout() {},
            syncScheduleDraftUnloadProtection() {}, setContentAssistantError: message => assert.fail(message),
            renderContentAssistant: () => ctx.renderAssistantTopicDetail(state.assistantDashboard),
            renderAssistantTopics: data => ctx.renderAssistantTopicDetail(data),
            assistantDraftResearchStatus: () => ({}), renderAssistantEvidenceChanges: blank, renderAssistantDraftMetricsHtml: blank,
            assistantDraftLengthText: blank, assistantDraftNumberChangesText: blank, renderAssistantConstraintRepair: blank,
            assistantWritingSkills: () => [], renderGenerationTargetOptionsHtml: blank, providerSupportsEffort: () => true,
            renderReasoningEffortOptionsHtml: blank, assistantScoreText: blank, assistantVerificationButtons: blank,
            assistantStatusClass: blank, assistantStatusLabel: String, fmtNum: String, renderAssistantTask: blank,
            contentAssistantRequest: async (path, body) => {
                if (path.startsWith("/dashboard?")) return dashboard();
                if (path === "/drafts/d/save") {
                    saves.push({...body});
                    if (fail) throw Error("暂时无法保存");
                    if (body.version !== stored.version) throw Object.assign(Error("另一页面已保存"),{httpStatus:409});
                    assert.equal(body.version, stored.version);
                    Object.assign(stored, {text: body.text, version: body.version + 1});
                    return {...stored};
                }
                if (path === "/drafts/d/versions") return [{...stored}];
                if (path === "/topics/A/detail") {
                    stored.topicId = "B";
                    return {topic: topics[0], drafts: [], publications: [job], observations: [observation, manual]};
                }
                if (path === "/topics/B/detail") return {topic: topics[1], drafts: [{...stored}], publications: [job], observations: []};
                assert.fail(path);
            }});
        vm.runInContext(section("    function selectedAssistantTopic(", "    function renderAssistantSkillBar(")
            + section("    function renderAssistantObservationReview(", "    function renderAssistantHistory(")
            + section("    function resolveAssistantDraftSourcePost(", "    function assistantDraftNumberChangesText(")
            + section("    function renderAssistantTopicDetail(", "    function assistantKeyValueRows(")
            + section("    async function loadContentAssistant(", "    function assistantVerificationButtons(")
            + section("    async function loadAssistantTopicDetail(", "    async function loadAssistantTopicPage(")
            + section("    async function saveAssistantDraftEditor(", "    async function sendAssistantDraftToSchedule("), ctx);
        ctx.renderAssistantTopicDetail(state.assistantDashboard);
        const typed = "另一个标签页拆题之前刚键入的稿件";
        editor.value = typed;
        ctx.queueAssistantDraftSave();
        if (conflict) Object.assign(stored,{version:2,text:"另一页面提交的正文"});
        if (!splitDuringDetail) stored.topicId = "B";
        await ctx.loadContentAssistant(true);
        if (conflict) {
            assert.equal(editor.value,typed);
            assert.equal(state.assistantDraftDirty,true);
            assert.equal(stored.version,2);
            assert.equal(fields.assistantDraftRemoteText.textContent,stored.text);
            await ctx.loadContentAssistant(true);
            assert.equal(saves.length,1, "a background refresh must not keep submitting stale text");
            await ctx.resolveAssistantDraftConflict(mode === "conflict-local" ? "local" : "remote");
            assert.equal(stored.version,mode === "conflict-local" ? 3 : 2);
            assert.equal(stored.text,mode === "conflict-local" ? typed : "另一页面提交的正文");
            assert.equal(state.assistantDraftDirty,false);
            state.assistantSelectedTopicId="B";
            await ctx.loadAssistantTopicDetail("B");
            assert.equal(editor.value,stored.text);
            continue;
        }
        if (fail) {
            assert.equal(editor.value, typed);
            assert.equal(state.assistantDraftDirty, true);
            assert.equal(fields.assistantDraftSaveState.textContent, "暂时无法保存");
            fail = false;
            await ctx.loadContentAssistant(true);
        }
        assert.deepEqual(saves.at(-1), {version: 1, text: typed});
        assert.equal(stored.text, typed);
        assert.equal(editor, null);
        assert.match(html, /357<\/strong>/);
        assert.match(html, /91<\/strong>/);
        state.assistantSelectedTopicId = "B";
        await ctx.loadAssistantTopicDetail("B");
        await ctx.loadAssistantTopicDetail("B");
        assert.equal(editor.value, typed);
        assert.equal((html.match(/data-assistant-update-scheduled="old-job"/g) || []).length, 1);
        assert.doesNotMatch(html, /357<\/strong>|91<\/strong>|发布复盘/);
    }
}

Promise.all([verifyDirectPublishRetries(), verifyRetryMode(), verifyScheduledUpdateWaitsForEditor(), verifyCancelledTaskCanRestart(),
    verifyMaterialCreationNavigation(), verifyVisibleRevisionIsPublishedAndScheduled(), verifySplitEditorAndPublicationViews()]).then(() => console.log("assistant-creation-regression: passed"))
    .catch((error) => { console.error(error); process.exitCode = 1; });
