(() => {
    "use strict";

    const LANGUAGES = ["zh-CN", "en", "ja", "ko"];
    const STORAGE_KEY = "xops_language";
    const ATTRIBUTE_NAMES = ["title", "aria-label", "placeholder", "alt", "data-tip"];
    const TEXT_BLOCKS = "script, style, code, pre, textarea, [contenteditable], [data-i18n-ignore]";
    const ATTRIBUTE_BLOCKS = "script, style, code, pre, [data-i18n-ignore]";
    const sourceRecords = new WeakMap();
    const attributeRecords = new WeakMap();
    const exact = new Map();
    const patterns = [];
    const cache = new Map();
    let locale = "zh-CN";
    let observer = null;
    let observing = false;

    const normalize = (value) => String(value).trim().replace(/\s+/g, " ");
    const escapeRegExp = (value) => value.replace(/[.*+?^\x24{}()|[\]\\]/g, "\\$&");

    for (const row of globalThis.XOpsMessages || []) {
        if (!Array.isArray(row) || row.length < 4) continue;
        const source = normalize(row[0]);
        if (!source) continue;
        const entry = { source, values: row.slice(1, 4), params: new Set(row[4] || []) };
        exact.set(source, entry);
        const matches = [...source.matchAll(/\{(\d+)\}/g)];
        if (!matches.length || matches.length > 8) continue;
        const parts = source.split(/\{\d+\}/);
        const captures = {
            number: "(-?[0-9,]{1,48}(?:\\.[0-9]{1,12})?(?:%|x|万|K|M)?|--)",
            fingerprint: "([A-Za-z0-9_-]{1,128})",
            shortener: "(⚠️短链\\s*|)"
        };
        const parameterTypes = row[5] || {};
        // Adjacent parameters require a known boundary such as a number or a fingerprint.
        if (parts.slice(1, -1).some((part, index) => !part && !captures[parameterTypes[Number(matches[index][1])]])) continue;
        const literalLength = parts.reduce((total, part) => total + part.length, 0);
        if (!literalLength) continue;
        const expression = parts.map((part, index) => {
            const literal = escapeRegExp(part).replace(/ +/g, "\\s+");
            if (index === matches.length) return literal;
            return literal + (captures[parameterTypes[Number(matches[index][1])]] || "([\\s\\S]*?)");
        }).join("");
        patterns.push({
            ...entry,
            regex: new RegExp("^" + expression + "$"),
            indices: matches.map((match) => Number(match[1])),
            literals: parts.filter((part) => part.trim().length > 1),
            first: parts[0],
            last: parts[parts.length - 1],
            specificity: literalLength,
            constraints: Object.keys(parameterTypes).length
        });
    }
    patterns.sort((left, right) => right.specificity - left.specificity
        || right.constraints - left.constraints
        || right.source.length - left.source.length);

    try {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (LANGUAGES.includes(stored)) locale = stored;
    } catch (_) {}

    function formatNumber(value) {
        const number = Number(value || 0);
        if (!Number.isFinite(number)) return "--";
        if (locale === "en") {
            if (Math.abs(number) >= 1000000) return (number / 1000000).toFixed(1) + "M";
            if (Math.abs(number) >= 1000) return (number / 1000).toFixed(1) + "K";
        } else if (locale === "ja" || locale === "ko") {
            if (Math.abs(number) >= 100000000) return (number / 100000000).toFixed(1) + (locale === "ja" ? "億" : "억");
            if (Math.abs(number) >= 10000) return (number / 10000).toFixed(1) + (locale === "ja" ? "万" : "만");
        } else {
            if (Math.abs(number) >= 1000000) return (number / 1000000).toFixed(1) + "M";
            if (Math.abs(number) >= 10000) return (number / 10000).toFixed(1) + "万";
        }
        return number.toLocaleString(locale);
    }

    function translate(value, depth = 0) {
        const text = String(value ?? "");
        if (!text || locale === "zh-CN" || depth > 4) return text;
        const cacheKey = locale + "\u0000" + depth + "\u0000" + text;
        if (cache.has(cacheKey)) return cache.get(cacheKey);
        const leading = text.match(/^\s*/)[0];
        const trailing = text.match(/\s*$/)[0];
        const trimmed = text.trim();
        let result = null;
        const direct = exact.get(normalize(trimmed));
        const column = LANGUAGES.indexOf(locale) - 1;
        if (direct) result = direct.values[column];

        if (result === null || result === undefined) {
            const compactNumber = trimmed.match(/^(-?\d+(?:\.\d+)?)(万|만|億|억|K|M)$/);
            if (compactNumber) {
                const multiplier = { "万": 10000, "만": 10000, "億": 100000000, "억": 100000000, K: 1000, M: 1000000 };
                result = formatNumber(Number(compactNumber[1]) * multiplier[compactNumber[2]]);
            }
        }
        if ((result === null || result === undefined) && trimmed.length <= 8192) {
            const normalized = normalize(trimmed);
            for (const pattern of patterns) {
                if ((pattern.first && !normalized.startsWith(pattern.first)) ||
                    (pattern.last && !normalized.endsWith(pattern.last)) ||
                    pattern.literals.some((part) => !normalized.includes(part))) continue;
                const match = pattern.regex.exec(trimmed);
                if (!match) continue;
                const values = {};
                let consistent = true;
                pattern.indices.forEach((index, position) => {
                    const captured = match[position + 1];
                    if (Object.hasOwn(values, index) && values[index] !== captured) consistent = false;
                    values[index] = captured;
                });
                if (!consistent) continue;
                result = pattern.values[column].replace(/\{(\d+)\}/g, (placeholder, indexText) => {
                    const index = Number(indexText);
                    if (!Object.hasOwn(values, index)) return placeholder;
                    return pattern.params.has(index) ? translate(values[index], depth + 1) : values[index];
                });
                break;
            }
        }
        if (result === null || result === undefined) {
            const separator = trimmed.match(/^([·｜]\s+)([\s\S]+)$/);
            if (separator) result = separator[1] + translate(separator[2], depth + 1);
        }
        if (result === null || result === undefined) {
            const weekday = trimmed.match(/^(周[一二三四五六日])(\s+\d{1,2}:\d{2}.*)$/);
            if (weekday) result = translate(weekday[1], depth + 1) + weekday[2];
        }
        if ((result === null || result === undefined) && / · | ｜ |\n/.test(trimmed)) {
            const pieces = trimmed.split(/( · | ｜ |\n)/);
            const translated = pieces.map((piece, index) => index % 2 ? piece : translate(piece, depth + 1)).join("");
            if (translated !== trimmed) result = translated;
        }
        const output = result === null || result === undefined ? text : leading + result + trailing;
        if (cache.size >= 1500) cache.delete(cache.keys().next().value);
        cache.set(cacheKey, output);
        return output;
    }

    function isBlocked(element, selector) {
        return Boolean(element?.closest?.(selector));
    }

    function refreshText(node) {
        if (!node.parentElement || isBlocked(node.parentElement, TEXT_BLOCKS)) return;
        const current = node.nodeValue || "";
        let record = sourceRecords.get(node);
        if (!record || current !== record.rendered) record = { source: current, rendered: current };
        const result = translate(record.source);
        record.rendered = result;
        sourceRecords.set(node, record);
        if (current !== result) node.nodeValue = result;
    }

    function refreshAttributes(element) {
        if (isBlocked(element, ATTRIBUTE_BLOCKS)) return;
        let records = attributeRecords.get(element);
        if (!records) { records = new Map(); attributeRecords.set(element, records); }
        for (const name of ATTRIBUTE_NAMES) {
            if (!element.hasAttribute(name)) { records.delete(name); continue; }
            const current = element.getAttribute(name);
            let record = records.get(name);
            if (!record || current !== record.rendered) record = { source: current, rendered: current };
            const result = translate(record.source);
            record.rendered = result;
            records.set(name, record);
            if (current !== result) element.setAttribute(name, result);
        }
    }

    function walk(root) {
        if (!root) return;
        if (root.nodeType === 3) { refreshText(root); return; }
        if (root.nodeType === 1) {
            refreshAttributes(root);
            if (isBlocked(root, TEXT_BLOCKS)) return;
        }
        for (const child of Array.from(root.childNodes || [])) walk(child);
    }

    function observe() {
        if (!observer || !globalThis.document?.documentElement) return;
        observer.observe(document.documentElement, {
            subtree: true, childList: true, characterData: true, attributes: true,
            attributeFilter: [...ATTRIBUTE_NAMES, "data-i18n-ignore"]
        });
        observing = true;
    }

    function refresh(root = globalThis.document?.documentElement) {
        const resume = observing;
        if (resume) { observer.disconnect(); observing = false; }
        try { walk(root); } finally { if (resume) observe(); }
    }

    function sourceText(node) {
        if (!node) return "";
        if (node.nodeType === 3) {
            const record = sourceRecords.get(node);
            return record && node.nodeValue === record.rendered ? record.source : (node.nodeValue || "");
        }
        return Array.from(node.childNodes || []).map(sourceText).join("");
    }

    function sourceAttribute(element, name) {
        const record = attributeRecords.get(element)?.get(name);
        const current = element?.getAttribute?.(name);
        return record && current === record.rendered ? record.source : (current || "");
    }

    function syncSelectors() {
        if (!globalThis.document) return;
        document.documentElement.lang = locale;
        for (const id of ["languageSelect", "gateLanguageSelect"]) {
            const select = document.getElementById(id);
            if (select) select.value = locale;
        }
    }

    function setLocale(nextLocale) {
        if (!LANGUAGES.includes(nextLocale)) return false;
        const previous = locale;
        locale = nextLocale;
        try { localStorage.setItem(STORAGE_KEY, locale); } catch (_) {}
        syncSelectors();
        refresh();
        if (previous !== locale && typeof globalThis.CustomEvent === "function" && globalThis.document) {
            document.dispatchEvent(new CustomEvent("xops:languagechange", { detail: { locale, previous } }));
        }
        return true;
    }

    globalThis.XOpsI18n = Object.freeze({
        get locale() { return locale; },
        translate, setLocale, sourceText, sourceAttribute, refresh, formatNumber
    });

    if (globalThis.document?.documentElement) {
        syncSelectors();
        refresh();
        document.addEventListener("change", (event) => {
            if (event.target?.id === "languageSelect" || event.target?.id === "gateLanguageSelect") {
                setLocale(event.target.value);
            }
        });
        if (typeof MutationObserver === "function") {
            observer = new MutationObserver((records) => {
                const roots = new Set();
                for (const record of records) {
                    if (record.type === "childList") {
                        for (const added of record.addedNodes || []) roots.add(added);
                    } else roots.add(record.target);
                }
                observer.disconnect();
                observing = false;
                try { for (const root of roots) walk(root); } finally { observe(); }
            });
            observe();
        }
    }
})();
