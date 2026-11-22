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

const escapeHtmlSlice = sliceBetween(
    appJs,
    "    function escapeHtml(str) {",
    "    async function fetchAlgoProfileStatus() {"
);

const draftAnalyzerSlice = sliceBetween(
    appJs,
    "    const KNOWN_SHORTENER_DOMAINS = new Set([",
    "    function loadDraftIntoSimulator(text, opts = {}) {"
);

const mockState = {
    algoProfileStatus: null,
    scheduleJobs: [],
    scheduleDraftItems: [],
    report: {
        profile: { username: "self_ops", followersCount: 1000 },
        algorithmHealth: {
            latestMainPostAt: new Date(Date.now() - 8 * 3600_000).toISOString(),
            linkComparisonAvailable: true,
            linkLossPct: 28,
            extLinkAvgViews: 180,
            nativeAvgViews: 250,
            calibration: {
                samplePostCount: 3,
                minRequiredSamples: 6,
                predictionUnlocked: false,
                inferredEffectiveReplyWeight: 14.5,
                domainStats: [
                    { domain: "github.com", postCount: 3, avgViews: 880, vsNativePct: 35, shortenerDomain: false },
                    { domain: "bit.ly", postCount: 1, avgViews: 150, vsNativePct: -40, shortenerDomain: true }
                ],
                corpusSamples: []
            }
        },
        contentTiming: {
            formatStats: [
                { formatCode: "MEDIA", formatLabel: "图文/视频", viewSampleCount: 4, avgViews: 620 },
                { formatCode: "TEXT", formatLabel: "纯文本", viewSampleCount: 5, avgViews: 310 }
            ],
            topPosts: [
                {
                    id: "t-prev",
                    text: "拆解 xai-org/x-algorithm 里的 vm-ranker 权重与 OONRetweetReplyFilter 过滤机制",
                    createdAt: new Date(Date.now() - 8 * 3600_000).toISOString()
                }
            ]
        },
        followAudit: {
            fansNotFollowedBackList: [{ user: { username: "loyal_fan" }, theyFollowMe: true }],
            mutualList: [{ user: { username: "mutual_friend" }, theyFollowMe: true }],
            notFollowingBackList: [{ user: { username: "cold_stranger" }, theyFollowMe: false, relationState: "CONFIRMED" }]
        },
        interactions: { peerStats: [] }
    }
};

const harnessCode = `
const URL_TOKEN_RE = /https?:\\/\\/[^\\s]+/gi;
const state = __state;
const fmtNum = (n) => String(n ?? 0);
const getQueuedScheduledEpochsMs = () => (state.scheduleJobs || []).map((j) => Number(j.scheduledAtEpochMs));
const parseDraftItemEpochMs = (it) => Number(it.epochMs || NaN);
${escapeHtmlSlice}
${draftAnalyzerSlice}
this.__exports = { analyzeDraftByXAlgo, computeUpstreamDiversityMult, buildCalibrationShingleArray, DEFAULT_ALGO_PROFILE };
`;

const sandbox = { __state: mockState };
vm.createContext(sandbox);
vm.runInContext(harnessCode, sandbox);
const { analyzeDraftByXAlgo, computeUpstreamDiversityMult, buildCalibrationShingleArray, DEFAULT_ALGO_PROFILE } = sandbox.__exports;

// 1. Exact upstream diversity formula: (1 - 0.25) * 0.5^k + 0.25
assert.equal(computeUpstreamDiversityMult(DEFAULT_ALGO_PROFILE.upstreamEvidence, 0), 1.0);
assert.equal(computeUpstreamDiversityMult(DEFAULT_ALGO_PROFILE.upstreamEvidence, 1), 0.625);
assert.equal(computeUpstreamDiversityMult(DEFAULT_ALGO_PROFILE.upstreamEvidence, 2), 0.4375);

// 2. Clean original post + canonical github.com URL: ZERO fake deduction + matches domain ledger + locked predictor (<6 samples)
const cleanWithGithub = analyzeDraftByXAlgo(
    "今天实测了一套全新的本地缓存增量聚合方案，延迟下降明显 https://github.com/xai-org/x-algorithm",
    { hasMedia: true }
);
assert.equal(cleanWithGithub.finalScore, 100);
assert.equal(cleanWithGithub.empiricalPrediction.unlocked, false);
assert.ok(cleanWithGithub.checks.some((c) => c.includes("链接本身不扣分") && c.includes("github.com")));
assert.ok(cleanWithGithub.checks.some((c) => c.includes("×2.9")));
assert.ok(cleanWithGithub.sampleNotes.some((n) => n.includes("github.com") && n.includes("+35%")));
assert.ok(cleanWithGithub.checks.every((c) => !/OONRetweetReplyFilter|BBQDuplicateTextProd|bot\/6754|PhoenixScores/.test(c)));

// 3. Third-party URL shortener (bit.ly) triggers URL reputation warning (-12)
const shortenerRes = analyzeDraftByXAlgo("这篇干货总结放在这里 https://bit.ly/3xyzabc");
assert.equal(shortenerRes.finalScore, 88);
assert.equal(shortenerRes.dimensions.find((d) => d.key === "safety").valLabel, "短链风险");

// 4. Unlock empirical predictor with 6 calibrated historical samples -> verify percentile range, nearest neighbors, and pipeline discount!
mockState.report.algorithmHealth.calibration.samplePostCount = 6;
mockState.report.algorithmHealth.calibration.predictionUnlocked = true;
mockState.report.algorithmHealth.calibration.loocvBandHitRatePct = 83.3;
mockState.report.algorithmHealth.calibration.loocvMaePercentile = 11.2;
mockState.report.algorithmHealth.calibration.corpusSamples = [
    {
        id: "s1", createdLabel: "11-10 21:00", formatCode: "MEDIA", cleanCharLen: 48,
        structuredList: true, hasQuestion: true, hasExternalLink: false, externalDomain: "",
        viewCount: 1200, rankPercentile: 92, realizedVmUtilityPer1k: 58.4,
        shingles: buildCalibrationShingleArray("系统架构缓存分层与压测性能对比清单"), textPreview: "系统架构缓存分层与压测性能对比清单", tweetUrl: "https://x.com/self_ops/status/s1"
    },
    {
        id: "s2", createdLabel: "11-11 21:00", formatCode: "MEDIA", cleanCharLen: 52,
        structuredList: true, hasQuestion: true, hasExternalLink: false, externalDomain: "",
        viewCount: 980, rankPercentile: 80, realizedVmUtilityPer1k: 49.1,
        shingles: buildCalibrationShingleArray("数据库索引优化与缓存穿透排查步骤"), textPreview: "数据库索引优化与缓存穿透排查步骤", tweetUrl: "https://x.com/self_ops/status/s2"
    },
    {
        id: "s3", createdLabel: "11-12 20:00", formatCode: "MEDIA", cleanCharLen: 44,
        structuredList: false, hasQuestion: true, hasExternalLink: true, externalDomain: "github.com",
        viewCount: 760, rankPercentile: 65, realizedVmUtilityPer1k: 41.0,
        shingles: buildCalibrationShingleArray("开源算法仓库阅读笔记与架构图"), textPreview: "开源算法仓库阅读笔记与架构图", tweetUrl: "https://x.com/self_ops/status/s3"
    },
    {
        id: "s4", createdLabel: "11-12 10:00", formatCode: "TEXT", cleanCharLen: 14,
        structuredList: false, hasQuestion: false, hasExternalLink: false, externalDomain: "",
        viewCount: 240, rankPercentile: 38, realizedVmUtilityPer1k: 14.2,
        shingles: buildCalibrationShingleArray("早上好喝杯咖啡开工"), textPreview: "早上好喝杯咖啡开工", tweetUrl: "https://x.com/self_ops/status/s4"
    },
    {
        id: "s5", createdLabel: "11-13 09:00", formatCode: "TEXT", cleanCharLen: 12,
        structuredList: false, hasQuestion: false, hasExternalLink: false, externalDomain: "",
        viewCount: 190, rankPercentile: 22, realizedVmUtilityPer1k: 10.5,
        shingles: buildCalibrationShingleArray("今天天气不错出去散步"), textPreview: "今天天气不错出去散步", tweetUrl: "https://x.com/self_ops/status/s5"
    },
    {
        id: "s6", createdLabel: "11-14 08:00", formatCode: "TEXT", cleanCharLen: 16,
        structuredList: false, hasQuestion: false, hasExternalLink: true, externalDomain: "bit.ly",
        viewCount: 140, rankPercentile: 10, realizedVmUtilityPer1k: 8.1,
        shingles: buildCalibrationShingleArray("随手转发一个链接"), textPreview: "随手转发一个链接", tweetUrl: "https://x.com/self_ops/status/s6"
    }
];

const highMatchDraft = analyzeDraftByXAlgo(
    "1. 系统架构缓存分层策略\n2. 压测延迟与索引排查步骤\n3. 你们线上怎么配？",
    { hasMedia: true }
);
assert.equal(highMatchDraft.empiricalPrediction.unlocked, true);
assert.equal(highMatchDraft.empiricalPrediction.topNeighbors.length, 3);
assert.equal(highMatchDraft.empiricalPrediction.topNeighbors[0].id, "s1");
assert.ok(highMatchDraft.empiricalPrediction.effHighPct >= 68);
assert.equal(highMatchDraft.empiricalPrediction.pipelineDiscounted, false);

const lowMatchCasualDraft = analyzeDraftByXAlgo("早上好喝杯咖啡准备出门散步", { hasMedia: false });
assert.ok(highMatchDraft.empiricalPrediction.effHighPct > lowMatchCasualDraft.empiricalPrediction.effHighPct);

// Leading @mention discounts both pipeline score AND empirical percentile forecast
const leadingAtDiscounted = analyzeDraftByXAlgo(
    "@mutual_friend 1. 系统架构缓存分层策略\n2. 压测延迟与索引排查步骤\n3. 怎么看？",
    { hasMedia: true }
);
assert.equal(leadingAtDiscounted.finalScore, 72);
assert.equal(leadingAtDiscounted.empiricalPrediction.pipelineDiscounted, true);
assert.ok(leadingAtDiscounted.empiricalPrediction.effHighPct < leadingAtDiscounted.empiricalPrediction.rawHighPct);

// Exact duplicate text check
const dupRes = analyzeDraftByXAlgo(
    "拆解 xai-org/x-algorithm 里的 vm-ranker 权重与 OONRetweetReplyFilter 过滤机制"
);
assert.equal(dupRes.finalScore, 65);
assert.ok(dupRes.checks.some((c) => c.includes("完全重复")));
assert.ok(dupRes.rewrites.every((r) => !/BBQDuplicateTextProd/.test(r)));

// External URL + @non-follower mention: spam link risk
const nonFollowerUrlRes = analyzeDraftByXAlgo(
    "分享这篇工程架构文章给 @cold_stranger 看看 https://example.com/arch"
);
assert.equal(nonFollowerUrlRes.finalScore, 82);
assert.equal(nonFollowerUrlRes.dimensions.find((d) => d.key === "safety").valLabel, "外链+@非粉");
assert.ok(nonFollowerUrlRes.checks.every((c) => !/bot\/6754|Scarecrow/.test(c)));

// Rapid-fire cadence (<60m): AuthorDiversity 63% (0.625x) warning
mockState.report.algorithmHealth.latestMainPostAt = new Date(Date.now() - 25 * 60_000).toISOString();
const rapidRes = analyzeDraftByXAlgo("刚发完上一条紧接着再发一条全新的独立思考");
assert.equal(rapidRes.finalScore, 74);
assert.ok(rapidRes.checks.some((c) => c.includes("63%")));

console.log("draft-algo-evidence-regression: passed");
