"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const app = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/app.js"), "utf8");

function render(timing, format, sort) {
    const feed = { innerHTML: "", querySelectorAll: () => [] };
    const context = {
        state: { topPostFormat: format, topPostSortBy: sort },
        $: () => feed, fmtNum: String, escapeHtml: String, safeHttpUrl: String, iconSvg: () => "",
        widerPeriodActions: () => [], emptyStateHtml: message => message, tr: String,
        postMetricsKnown: (p, fields) => fields.every(f => !(p.unavailableMetrics || []).includes(f))
    };
    vm.runInNewContext(app.slice(app.indexOf("    function renderTopTweetsList("),
            app.indexOf("    function getEffectiveShareTweetText(")) +
            "\nrenderTopTweetsList(report);", { ...context, report: { profile: {}, contentTiming: timing } });
    return feed.innerHTML;
}

function post(id, more = {}) {
    return { id, text: "body-" + id, createdLabel: "", viewCount: 0, bookmarkCount: 0,
        retweetCount: 0, likeCount: 0, replyCount: 0, unavailableMetrics: [], ...more };
}

test("all 24 members of a server-ranked format are rendered, including cards outside the default list", () => {
    const defaults = Array.from({ length: 24 }, (_, i) => post("default-" + i));
    const more = Array.from({ length: 24 }, (_, i) => post("media-" + i, { hasMedia: true, viewCount: i }));
    const indices = more.map((_, i) => 47 - i);
    const html = render({ topPosts: defaults, formatStats: [{ formatCode: "MEDIA", postCount: 55, viewSampleCount: 55 }],
        postRankings: { byFormat: { MEDIA: { views: indices } }, additionalPosts: more } }, "MEDIA", "views");
    assert.equal((html.match(/class="x-tweet-card"/g) || []).length, 24);
    assert.ok(html.indexOf("body-media-23") < html.indexOf('body-media-0<'));
    assert.doesNotMatch(html, /body-default-/);
});

test("observed posts with no views disclose missing readings while a measured zero stays visible", () => {
    for (const known of [true, false]) {
        const html = render({ topPosts: [post("one", { hasMedia: true, unavailableMetrics: known ? [] : ["views"] })],
            formatStats: [{ formatCode: "MEDIA", postCount: 1, viewSampleCount: known ? 1 : 0 }],
            postRankings: { additionalPosts: [], byFormat: { MEDIA: { views: [0] } } } }, "MEDIA", "views");
        assert.match(html, /body-one/);
        if (known) {
            assert.doesNotMatch(html, /浏览量尚未取得/);
            assert.match(html, /title="浏览量">\s*0</);
        } else {
            assert.match(html, /浏览量尚未取得/);
            assert.match(html, /title="浏览量">\s*--</);
        }
    }
});

test("partial coverage and a truncated legacy report do not claim the format is absent", () => {
    const legacy = { topPosts: [post("text")], formatStats: [{ formatCode: "MEDIA", postCount: 2, viewSampleCount: 1 }] };
    assert.match(render(legacy, "MEDIA", "views"), /当前缓存仅含部分代表推文/);
    assert.match(render(legacy, "ALL", "composite"), /body-text/);
    const timing = { topPosts: [], formatStats: [{ formatCode: "MEDIA", postCount: 0, viewSampleCount: 0 }],
        postRankings: { additionalPosts: [], byFormat: { MEDIA: { views: [] } } } };
    assert.match(render({ ...timing, timelineWindowComplete: false }, "MEDIA", "views"), /已采集样本中暂无该体裁的主帖/);
    assert.match(render({ ...timing, timelineWindowComplete: true }, "MEDIA", "views"), /当前类型暂无推文/);
});

test("format summaries distinguish a measured zero, missing views, and an unobserved format", () => {
    const grid = {};
    const context = { grid, $: () => grid, fmtNum: String, escapeHtml: String, tr: String,
        hasMetricSample: (f, key) => f[key] === undefined || f[key] > 0, renderAlgoPoolAndSimulator() {}, renderTopTweetsList() {} };
    const code = app.slice(app.indexOf("    function renderTimingPanel("), app.indexOf("    function opsActionButtonLabel("));
    function formatted(n, known) {
        const row = { formatLabel: "MEDIA", formatCode: "MEDIA", postCount: n, viewSampleCount: known, avgViews: 0 };
        vm.runInNewContext(code + "\nrenderTimingPanel(report);", { ...context, report: { contentTiming: { formatStats: [row], timelineWindowComplete: false } } });
        return grid.innerHTML;
    }
    assert.match(formatted(1, 0), /浏览量尚未取得/);
    assert.match(formatted(1, 1), /均浏览 0/);
    assert.match(formatted(0, 0), /已采集样本中暂无该体裁的主帖/);
});

test("a non-default winning card can be loaded into the draft simulator", () => {
    let click, loaded;
    const list = { addEventListener: (_, handler) => { click = handler; } };
    const helperStart = app.indexOf("    function representativePostCards(");
    const helper = helperStart === -1 ? "" : app.slice(helperStart, app.indexOf("    function legacyRepresentativePosts(", helperStart));
    const start = app.indexOf('        const topTweetsListEl = $("topTweetsList");');
    const end = app.indexOf("        const toggleAlgoBtn", start);
    const state = { report: { contentTiming: { topPosts: [post("default")], postRankings: { additionalPosts: [post("winner", {hasMedia: true})] } } } };
    vm.runInNewContext(helper + app.slice(start, end), { state, $: () => list, loadDraftIntoSimulator: (text, options) => { loaded = [text, options.hasMedia]; } });
    click({target: {closest: () => ({dataset: {topPostId: "winner"}})}});
    assert.deepEqual(loaded, ["body-winner", true]);
});

test("the format badge compares measured values and never ranks missing scores as zero", () => {
    const grid = {};
    const rows = [{ formatCode: "TEXT", formatLabel: "Text", postCount: 1, viewSampleCount: 1, avgViews: 10, algoScoreSampleCount: 1, avgAlgoScore: 40 },
        { formatCode: "MEDIA", formatLabel: "Media", postCount: 1, viewSampleCount: 1, avgViews: 10000, algoScoreSampleCount: 0, avgAlgoScore: 0 }];
    const code = app.slice(app.indexOf("    function renderTimingPanel("), app.indexOf("    function opsActionButtonLabel("));
    const ctx = { $: () => grid, fmtNum: String, escapeHtml: String, tr: String, hasMetricSample: (f, key) => f[key] > 0,
        report: {contentTiming: {formatStats: rows, bestFormatCode: "MEDIA"}}, renderAlgoPoolAndSimulator() {}, renderTopTweetsList() {} };
    vm.runInNewContext(code + "\nrenderTimingPanel(report);", ctx);
    const cards = grid.innerHTML.split('class="format-card');
    assert.match(cards.find(html => html.includes("Media")), /表现最佳/);
    assert.doesNotMatch(cards.find(html => html.includes("Text")), /表现最佳/);
    delete ctx.report.contentTiming.bestFormatCode;
    vm.runInNewContext(code + "\nrenderTimingPanel(report);", ctx);
    assert.doesNotMatch(grid.innerHTML, /表现最佳/);
});
