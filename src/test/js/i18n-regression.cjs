"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const staticDir = path.resolve(__dirname, "../../main/resources/static");
const runtimeSource = fs.readFileSync(path.join(staticDir, "i18n.js"), "utf8");
const messageSource = fs.readFileSync(path.join(staticDir, "i18n-messages.js"), "utf8");

// A deliberately small DOM models only the production runtime's dependencies.
// Mutation records are delivered explicitly, including disconnect() discarding records.
function createPage(options = {}) {
    const observers = [];
    const listeners = new Map();
    const storage = options.storage || new Map();
    let mutationDeliveries = 0;
    const document = { nodeType: 9, childNodes: [], activeElement: null };
    function within(node, root) {
        for (let current = node; current; current = current.parentNode) {
            if (current === root) return true;
        }
        return false;
    }
    function recordMutation(record) {
        for (const observer of observers) {
            if (!observer.root || !within(record.target, observer.root)) continue;
            if (!observer.options[record.type === "childList" ? "childList"
                : record.type === "characterData" ? "characterData" : "attributes"]) continue;
            if (record.type === "attributes" && observer.options.attributeFilter
                && !observer.options.attributeFilter.includes(record.attributeName)) continue;
            observer.records.push(record);
        }
    }
    class Text {
        constructor(value) {
            this.nodeType = 3;
            this.parentNode = null;
            this.childNodes = [];
            this._value = String(value);
        }
        get parentElement() { return this.parentNode?.nodeType === 1 ? this.parentNode : null; }
        get nodeValue() { return this._value; }
        set nodeValue(value) {
            const oldValue = this._value;
            this._value = String(value);
            recordMutation({ type: "characterData", target: this, oldValue });
        }
        get textContent() { return this.nodeValue; }
    }
    class Element {
        constructor(tag, attrs = {}) {
            this.nodeType = 1;
            this.tagName = tag.toUpperCase();
            this.parentNode = null;
            this.childNodes = [];
            this.attrs = new Map(Object.entries(attrs));
            this.value = "";
        }
        get parentElement() { return this.parentNode?.nodeType === 1 ? this.parentNode : null; }
        get id() { return this.getAttribute("id") || ""; }
        get lang() { return this.getAttribute("lang") || ""; }
        set lang(value) { this.setAttribute("lang", value); }
        get textContent() { return this.childNodes.map(node => node.textContent).join(""); }
        get innerHTML() { throw new Error("The locale runtime must not read or rebuild HTML"); }
        set innerHTML(_) { throw new Error("The locale runtime must preserve live elements"); }
        hasAttribute(name) { return this.attrs.has(name); }
        getAttribute(name) { return this.attrs.get(name) ?? null; }
        setAttribute(name, value) {
            const oldValue = this.getAttribute(name);
            this.attrs.set(name, String(value));
            recordMutation({ type: "attributes", target: this, attributeName: name, oldValue });
        }
        closest(selector) {
            const selectors = selector.split(",").map(item => item.trim());
            for (let node = this; node?.nodeType === 1; node = node.parentElement) {
                if (selectors.some(item => item.startsWith("[")
                    ? node.hasAttribute(item.slice(1, -1))
                    : node.tagName.toLowerCase() === item.toLowerCase())) return node;
            }
            return null;
        }
        appendChild(node) {
            assert.equal(node.parentNode, null);
            node.parentNode = this;
            this.childNodes.push(node);
            recordMutation({ type: "childList", target: this, addedNodes: [node] });
            return node;
        }
    }
    class MutationObserver {
        constructor(callback) {
            this.callback = callback;
            this.root = null;
            this.records = [];
            observers.push(this);
        }
        observe(root, observerOptions) { this.root = root; this.options = observerOptions; }
        disconnect() { this.root = null; this.records = []; }
    }
    function element(tag, attrs = {}, value, parent = document.body) {
        const node = new Element(tag, attrs);
        if (value !== undefined) node.appendChild(new Text(value));
        if (parent) parent.appendChild(node);
        return node;
    }
    document.documentElement = element("html", { lang: "zh-CN" }, undefined, null);
    document.documentElement.parentNode = document;
    document.childNodes.push(document.documentElement);
    document.body = element("body", {}, undefined, document.documentElement);
    document.getElementById = (id) => {
        function find(node) {
            if (node.id === id) return node;
            for (const child of node.childNodes || []) {
                const match = find(child);
                if (match) return match;
            }
            return null;
        }
        return find(document.documentElement);
    };
    document.addEventListener = (type, callback) => {
        if (!listeners.has(type)) listeners.set(type, []);
        listeners.get(type).push(callback);
    };
    document.dispatchEvent = (event) => {
        for (const callback of listeners.get(event.type) || []) callback(event);
        return true;
    };

    const heading = element("h2", {}, "新建定时推文");
    const action = element("button", {
        title: "近 24 小时", "aria-label": "排序方式", "data-label": "24小时"
    }, "24小时");
    const language = element("select", { id: "languageSelect", "aria-label": "选择语言" });
    const gateLanguage = element("select", { id: "gateLanguageSelect", "aria-label": "选择语言" });
    for (const select of [language, gateLanguage]) {
        for (const [value, label] of [["zh-CN", "中文"], ["en", "English"], ["ja", "日本語"], ["ko", "한국어"]]) {
            element("option", { value, "data-i18n-ignore": "" }, label, select);
        }
    }
    const input = element("input", { placeholder: "搜索昵称 / @用户名" });
    input.value = "新建定时推文 <img src=x onerror=alert(1)> 日本語 한국어";
    input.selectionStart = 2;
    input.selectionEnd = 7;
    const textarea = element("textarea", { placeholder: "搜索昵称 / @用户名" }, "新建定时推文");
    textarea.value = "未保存的草稿：全部，<script>alert(1)</script>";
    const editable = element("div", { contenteditable: "true" }, "新建定时推文");
    const userName = element("span", { "data-i18n-ignore": "", title: "新建定时推文" }, "新建定时推文");
    const rawPost = element("div", { "data-i18n-ignore": "" }, "同步中（7s）… <script>alert(1)</script>");
    const fileName = element("span", { "data-i18n-ignore": "" }, "全部");
    const code = element("code", {}, "新建定时推文");
    const pre = element("pre", {}, "新建定时推文");
    const script = element("script", {}, "新建定时推文");
    const style = element("style", {}, "新建定时推文");
    const dynamic = element("div", {}, "同步中（7s）…");
    const fragment = element("span", {}, "在");
    document.activeElement = input;
    const languageEvents = [];
    document.addEventListener("xops:languagechange", event => languageEvents.push(event.detail));
    const context = {
        document, MutationObserver,
        CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init.detail; } },
        localStorage: {
            getItem(key) {
                if (options.readThrows) throw new Error("Storage unavailable");
                return storage.get(key) ?? null;
            },
            setItem(key, value) {
                if (options.writeThrows) throw new Error("Quota exceeded");
                storage.set(key, String(value));
            }
        },
        fetch() { throw new Error("Changing language must not fetch data"); },
        location: { reload() { throw new Error("Changing language must not reload"); } }
    };
    vm.createContext(context);
    vm.runInContext(messageSource, context, { filename: "i18n-messages.js" });
    vm.runInContext(runtimeSource, context, { filename: "i18n.js" });
    function flushMutations() {
        let delivered = 0;
        while (observers.some(observer => observer.records.length)) {
            assert.ok(delivered < 8, "Translation must not create an observer feedback loop");
            for (const observer of observers) {
                if (!observer.records.length) continue;
                const records = observer.records.splice(0);
                delivered++;
                mutationDeliveries++;
                observer.callback(records);
            }
        }
        return delivered;
    }
    return {
        api: context.XOpsI18n, messages: context.XOpsMessages, document, storage,
        heading, action, language, gateLanguage, input, textarea, editable, userName,
        rawPost, fileName, code, pre, script, style, dynamic, fragment,
        languageEvents, element, flushMutations,
        get mutationDeliveries() { return mutationDeliveries; }
    };
}

const page = createPage();
assert.equal(page.api.locale, "zh-CN");
assert.equal(page.document.documentElement.lang, "zh-CN");
assert.equal(page.heading.textContent, "新建定时推文");
assert.equal(page.language.value, "zh-CN");
assert.equal(page.gateLanguage.value, "zh-CN");
const headingText = page.heading.childNodes[0];
const originalInputs = [page.input.value, page.input.selectionStart, page.input.selectionEnd, page.textarea.value];
const originalRaw = [page.userName, page.rawPost, page.fileName, page.editable, page.code, page.pre, page.script, page.style]
    .map(node => node.textContent);

for (const [locale, expectedHeading, expectedDynamic] of [
    ["en", "New scheduled post", "Syncing (7s)…"],
    ["ja", "予約投稿を作成", "同期中（7秒）…"],
    ["ko", "새 예약 게시물", "동기화 중(7초)…"],
    ["zh-CN", "新建定时推文", "同步中（7s）…"]
]) {
    assert.equal(page.api.setLocale(locale), true);
    assert.equal(page.api.locale, locale);
    assert.equal(page.document.documentElement.lang, locale);
    assert.equal(page.language.value, locale);
    assert.equal(page.gateLanguage.value, locale);
    assert.equal(page.storage.get("xops_language"), locale);
    assert.equal(page.heading.textContent, expectedHeading);
    assert.equal(page.dynamic.textContent, expectedDynamic);
    assert.equal(page.heading.childNodes[0], headingText, "Language changes must preserve text-node identity");
    assert.equal(page.document.activeElement, page.input, "Language changes must preserve focus");
    assert.deepEqual([page.input.value, page.input.selectionStart, page.input.selectionEnd, page.textarea.value], originalInputs);
    assert.deepEqual([page.userName, page.rawPost, page.fileName, page.editable, page.code, page.pre, page.script, page.style]
        .map(node => node.textContent), originalRaw, "Original user and code content must remain byte-for-byte unchanged");
    assert.equal(page.userName.getAttribute("title"), "新建定时推文");
    assert.equal(page.textarea.textContent, "新建定时推文");
    assert.equal(page.action.getAttribute("data-label"), "24小时", "Business data attributes remain canonical");
    assert.equal(page.api.sourceText(page.heading), "新建定时推文");
    assert.equal(page.api.sourceText(page.dynamic), "同步中（7s）…");
    assert.equal(page.api.sourceAttribute(page.action, "title"), "近 24 小时");
    assert.equal(page.flushMutations(), 0, "Locale refresh disconnects its observer while writing");
    if (locale !== "zh-CN") {
        assert.notEqual(page.input.getAttribute("placeholder"), "搜索昵称 / @用户名");
        assert.notEqual(page.textarea.getAttribute("placeholder"), "搜索昵称 / @用户名");
        assert.notEqual(page.action.getAttribute("title"), "近 24 小时");
        assert.notEqual(page.language.getAttribute("aria-label"), "选择语言");
    }
    if (locale === "ja" || locale === "ko") assert.equal(page.fragment.textContent, "", "An intentional empty translation is valid");
}
assert.equal(page.input.getAttribute("placeholder"), "搜索昵称 / @用户名");
assert.equal(page.fragment.textContent, "在");
assert.equal(page.languageEvents.length, 4);

page.api.setLocale("en");
const newNode = page.element("p", {}, "同步中（9s）…");
assert.equal(page.flushMutations(), 1, "One inserted UI node needs one observer delivery");
assert.equal(newNode.textContent, "Syncing (9s)…");
assert.equal(page.flushMutations(), 0);
newNode.childNodes[0].nodeValue = "同步中（12s）…";
assert.equal(page.flushMutations(), 1);
assert.equal(newNode.textContent, "Syncing (12s)…");
assert.equal(page.api.sourceText(newNode), "同步中（12s）…");
page.input.setAttribute("placeholder", "新建定时推文");
assert.equal(page.flushMutations(), 1);
assert.equal(page.input.getAttribute("placeholder"), "New scheduled post");
assert.equal(page.api.sourceAttribute(page.input, "placeholder"), "新建定时推文");
assert.equal(page.input.value, originalInputs[0]);

page.language.value = "ko";
page.document.dispatchEvent({ type: "change", target: page.language });
assert.equal(page.api.locale, "ko");
assert.equal(page.gateLanguage.value, "ko");
const reloaded = createPage({ storage: page.storage });
assert.equal(reloaded.api.locale, "ko");
assert.equal(reloaded.heading.textContent, "새 예약 게시물");
assert.equal(page.api.setLocale("fr"), false);
assert.equal(page.api.locale, "ko");
assert.equal(page.storage.get("xops_language"), "ko");
const invalidSaved = createPage({ storage: new Map([["xops_language", "not-supported"]]) });
assert.equal(invalidSaved.api.locale, "zh-CN");
const inaccessibleStorage = createPage({ readThrows: true, writeThrows: true });
assert.equal(inaccessibleStorage.api.locale, "zh-CN");
assert.equal(inaccessibleStorage.api.setLocale("en"), true);
assert.equal(inaccessibleStorage.heading.textContent, "New scheduled post");

for (const [locale, expected] of [["zh-CN", "1.0万"], ["en", "10.0K"], ["ja", "1.0万"], ["ko", "1.0만"]]) {
    page.api.setLocale(locale);
    assert.equal(page.api.formatNumber(10000), expected);
}
page.api.setLocale("en");
assert.equal(page.api.translate("1.2万"), "12.0K");
assert.equal(page.api.translate("前缀同步中（7s）…后缀"), "前缀同步中（7s）…后缀",
    "Template matching must be anchored, not a substring rewrite");
assert.equal(page.api.translate("未登记原文 <img src=x onerror=alert(1)>"), "未登记原文 <img src=x onerror=alert(1)>");
assert.equal(page.api.formatNumber(Infinity), "--");
page.api.setLocale("zh-CN");
assert.equal(newNode.textContent, "同步中（12s）…");
assert.equal(page.input.getAttribute("placeholder"), "新建定时推文");

page.api.setLocale("en");
assert.equal(page.api.translate("🔒 7天"), "🔒 7d");
assert.equal(page.api.translate("11月16日 18:30"), "11/16 18:30");
assert.equal(page.api.translate("日均 2.0"), "Daily avg. 2.0");
assert.equal(page.api.translate("日均 2.0 · 引用 3"), "Daily avg. 2.0 · Quotes 3");
const originalSnippet = '新建定时推文 <script>alert(1)</script> · 日均 2.0';
const reviewSource = '11月16日 18:30「' + originalSnippet + '」；已采集浏览 100，历史单帖中位数 1000。';
const reviewTranslated = page.api.translate(reviewSource);
assert.ok(reviewTranslated.includes(originalSnippet), "Embedded original posts must not be translated recursively");
assert.ok(reviewTranslated.startsWith("11/16 18:30"));
assert.ok(!reviewTranslated.includes("已采集浏览"));

const sources = new Set();
for (const row of page.messages) {
    assert.ok(Array.isArray(row) && row.length >= 4, "Every message must provide source and all three translations");
    assert.equal(typeof row[0], "string");
    assert.ok(row[0].trim());
    assert.ok(!sources.has(row[0]), "Catalog sources must be unique: " + row[0]);
    sources.add(row[0]);
    const parameters = value => [...new Set([...value.matchAll(/\{(\d+)\}/g)].map(match => match[1]))].sort();
    for (const translated of row.slice(1, 4)) {
        assert.equal(typeof translated, "string");
        assert.deepEqual(parameters(translated), parameters(row[0]), "Translation must preserve data placeholders: " + row[0]);
    }
}
console.log("i18n-regression: passed (four languages, persistence, templates, raw data, inputs, observers, catalog)");
