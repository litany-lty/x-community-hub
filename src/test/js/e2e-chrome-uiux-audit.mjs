import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const WORKSPACE = path.resolve(import.meta.dirname, "../../..");
const CHROME_BIN = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const JAR_PATH = path.join(WORKSPACE, "target/x-community-hub.jar");
const JAVA_BIN = "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java";

function getFreePort() {
    return new Promise((resolve, reject) => {
        const s = net.createServer();
        s.listen(0, "127.0.0.1", () => {
            const port = s.address().port;
            s.close(() => resolve(port));
        });
        s.on("error", reject);
    });
}

let BACKEND_PORT = 0;
let PROXY_PORT = 0;

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "xops-uiux-audit-"));

// Dynamic auth & schedule state controlled by the E2E test runner
const mockSim = {
    loggedIn: false,
    oauthConfigured: true,
    writeAuthorized: true,
    mediaWriteAuthorized: true,
    offlineRefreshable: true,
    scheduleAccessAllowed: true,
    authorInteractionRank: 12,
    unlockedPeriods: ["HOURS_24", "HOURS_48"],
    forceNextBundle500: false,
    jobs: [
        {
            id: "job-overdue-1",
            text: "这是一条逾期待确认的测试推文",
            scheduledAtEpochMs: Date.now() - 3600_000,
            status: "OVERDUE_CONFIRM_REQUIRED",
            uncertainDelivery: false,
            confirmToken: "tok-1",
            lastError: "服务重启错过预定时间，请确认后补发",
            media: []
        },
        {
            id: "job-overdue-2",
            text: "第二条逾期待确认推文（测试批量补发）",
            scheduledAtEpochMs: Date.now() - 7200_000,
            status: "OVERDUE_CONFIRM_REQUIRED",
            uncertainDelivery: false,
            confirmToken: "tok-2",
            lastError: "已超过预定窗口",
            media: []
        },
        {
            id: "job-published-1",
            text: "已成功发布的定时推文样本",
            scheduledAtEpochMs: Date.now() - 86400_000,
            publishedAtEpochMs: Date.now() - 86395_000,
            status: "PUBLISHED",
            publishedTweetUrl: "https://x.com/demo_ops_user/status/10001",
            media: []
        }
    ],
    dailyCacheSynced: true
};

function recomputeCounts() {
    const overdueConfirmCount = mockSim.jobs.filter((j) => j.status === "OVERDUE_CONFIRM_REQUIRED").length;
    const pendingCount = mockSim.jobs.filter((j) => j.status === "SCHEDULED" || j.status === "RESEND_QUEUED").length;
    return { overdueConfirmCount, pendingCount };
}

function readReqBody(req) {
    return new Promise((resolve) => {
        const chunks = [];
        req.on("data", (c) => chunks.push(c));
        req.on("end", () => resolve(Buffer.concat(chunks)));
    });
}

function startProxyServer() {
    return new Promise((resolve) => {
        const srv = http.createServer(async (req, res) => {
            const urlObj = new URL(req.url, `http://127.0.0.1:${PROXY_PORT}`);
            const p = urlObj.pathname;

            if (p === "/api/auth/x/status") {
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(
                    JSON.stringify({
                        oauthConfigured: mockSim.oauthConfigured,
                        suggestedCallbackUrl: `http://127.0.0.1:${PROXY_PORT}/api/auth/x/callback`,
                        loggedIn: mockSim.loggedIn,
                        user: mockSim.loggedIn
                            ? {
                                  id: "demo-uid-1001",
                                  username: "demo_ops_user",
                                  displayName: "Demo Creator",
                                  avatarUrl: "/author-avatar.webp",
                                  verified: true
                              }
                            : null,
                        writeAuthorized: mockSim.writeAuthorized,
                        mediaWriteAuthorized: mockSim.mediaWriteAuthorized,
                        offlineRefreshable: mockSim.offlineRefreshable
                    })
                );
                return;
            }

            if (p === "/api/auth/x/logout" && req.method === "POST") {
                mockSim.loggedIn = false;
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ ok: true }));
                return;
            }

            if (p === "/api/auth/x/authorize-publish" && req.method === "GET") {
                mockSim.writeAuthorized = true;
                mockSim.mediaWriteAuthorized = true;
                res.writeHead(302, { Location: `http://127.0.0.1:${PROXY_PORT}/?auth=publish_ok` });
                res.end();
                return;
            }

            if (p === "/api/usage/checkin" && req.method === "POST") {
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(
                    JSON.stringify({
                        usageCount: 5,
                        shouldShowAuthorModal: false
                    })
                );
                return;
            }

            if (p.startsWith("/api/scheduled-tweets")) {
                if (req.method === "GET" && p === "/api/scheduled-tweets") {
                    const { overdueConfirmCount, pendingCount } = recomputeCounts();
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(
                        JSON.stringify({
                            items: mockSim.jobs,
                            pendingCount,
                            overdueConfirmCount,
                            timezone: "Asia/Shanghai",
                            writeAuthorized: mockSim.writeAuthorized,
                            mediaWriteAuthorized: mockSim.mediaWriteAuthorized,
                            offlineRefreshable: mockSim.offlineRefreshable,
                            scheduleAccessAllowed: mockSim.scheduleAccessAllowed,
                            followsAuthor: true,
                            authorInteractionRank: mockSim.authorInteractionRank,
                            scheduleLockedReason: mockSim.scheduleAccessAllowed
                                ? ""
                                : "定时发布仅限与 @elidlicom 互动频率前 100 的推友可用。"
                        })
                    );
                    return;
                }
                if (req.method === "POST" && p === "/api/scheduled-tweets") {
                    const raw = (await readReqBody(req)).toString("utf8");
                    const textMatch = /name="text"\r\n\r\n([\s\S]*?)\r\n--/.exec(raw);
                    const epochMatch = /name="scheduledAtEpochMs"\r\n\r\n(\d+)/.exec(raw);
                    const created = {
                        id: `job-${Date.now()}`,
                        text: textMatch ? textMatch[1] : "单条新建定时推文",
                        scheduledAtEpochMs: epochMatch ? Number(epochMatch[1]) : Date.now() + 3600_000,
                        status: "SCHEDULED",
                        media: []
                    };
                    mockSim.jobs.unshift(created);
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify(created));
                    return;
                }
                if (req.method === "POST" && p === "/api/scheduled-tweets/batch") {
                    const raw = (await readReqBody(req)).toString("utf8");
                    const jsonMatch = /name="itemsJson"\r\n\r\n([\s\S]*?)\r\n--/.exec(raw);
                    const items = jsonMatch ? JSON.parse(jsonMatch[1]) : [];
                    items.forEach((it, idx) => {
                        mockSim.jobs.unshift({
                            id: `job-batch-${Date.now()}-${idx}`,
                            text: it.text,
                            scheduledAtEpochMs: Number(it.scheduledAtEpochMs),
                            status: "SCHEDULED",
                            media: []
                        });
                    });
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ createdCount: items.length }));
                    return;
                }
                if (req.method === "POST" && p === "/api/scheduled-tweets/batch-confirm-resend") {
                    const body = JSON.parse((await readReqBody(req)).toString("utf8") || "{}");
                    for (const it of body.items || []) {
                        const job = mockSim.jobs.find((j) => j.id === it.id);
                        if (job) {
                            job.status = "PUBLISHED";
                            job.publishedAtEpochMs = Date.now();
                        }
                    }
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ confirmedCount: (body.items || []).length }));
                    return;
                }
                const actionMatch = /^\/api\/scheduled-tweets\/([^/]+)\/(reschedule|cancel|confirm-resend)$/.exec(p);
                if (actionMatch && req.method === "POST") {
                    const [, id, act] = actionMatch;
                    const raw = (await readReqBody(req)).toString("utf8");
                    const job = mockSim.jobs.find((j) => j.id === id);
                    if (!job) {
                        res.writeHead(404, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ message: "Not found" }));
                        return;
                    }
                    if (act === "reschedule") {
                        const parsed = JSON.parse(raw || "{}");
                        job.scheduledAtEpochMs = Number(parsed.scheduledAtEpochMs);
                        job.status = "SCHEDULED";
                    } else if (act === "cancel") {
                        job.status = "CANCELLED";
                    } else if (act === "confirm-resend") {
                        job.status = "PUBLISHED";
                        job.publishedAtEpochMs = Date.now();
                    }
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify(job));
                    return;
                }
                const delMatch = /^\/api\/scheduled-tweets\/([^/]+)$/.exec(p);
                if (delMatch && req.method === "DELETE") {
                    const id = delMatch[1];
                    mockSim.jobs = mockSim.jobs.filter((j) => j.id !== id);
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ deleted: true }));
                    return;
                }
            }

            if (p === "/api/analyze/bundle") {
                if (mockSim.forceNextBundle500) {
                    mockSim.forceNextBundle500 = false;
                    res.writeHead(500, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ message: "模拟上游服务暂时繁忙（HTTP 500）" }));
                    return;
                }
                if (!mockSim.loggedIn) {
                    res.writeHead(401, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ message: "未登录" }));
                    return;
                }
                urlObj.searchParams.set("username", "elidlicom");
                urlObj.searchParams.set("provider", "DEMO");
                const upstreamResp = await fetch(`http://127.0.0.1:${BACKEND_PORT}${urlObj.pathname}${urlObj.search}`);
                const bundle = await upstreamResp.json();
                if (!mockSim.unlockedPeriods.includes("HOURS_48")) bundle.day = null;
                if (!mockSim.unlockedPeriods.includes("WEEK")) bundle.week = null;
                if (!mockSim.unlockedPeriods.includes("MONTH")) bundle.month = null;
                // Align profile ID with simulated logged-in user ID
                for (const k of ["hours24", "day", "week", "month"]) {
                    if (bundle[k] && bundle[k].profile) {
                        bundle[k].profile = {
                            ...bundle[k].profile,
                            id: "demo-uid-1001",
                            username: "demo_ops_user"
                        };
                    }
                }
                bundle.accessPolicy = {
                    ...(bundle.accessPolicy || {}),
                    unlockedPeriods: mockSim.unlockedPeriods,
                    lockedReasons: {
                        WEEK: "累计满 7 天物理存储数据后自动开放",
                        MONTH: "累计满 30 天物理存储数据后自动开放"
                    },
                    scheduledPublishAllowed: mockSim.scheduleAccessAllowed,
                    authorInteractionRank: mockSim.authorInteractionRank,
                    followsAuthor: true,
                    accumulatedDays: mockSim.unlockedPeriods.length >= 4 ? 30 : 2,
                    dailyRefreshLimit: 5,
                    dailyRefreshRemaining: 4
                };
                bundle.dailyCacheStatus = {
                    snapshotAt: new Date().toISOString(),
                    latestMetricFetchedAt: new Date().toISOString(),
                    lastCompletedAt: new Date().toISOString(),
                    timelineCovered: mockSim.dailyCacheSynced,
                    inboundCovered: mockSim.dailyCacheSynced,
                    recentPosts: 8,
                    recentInbound: 14,
                    pendingMetricPosts: mockSim.dailyCacheSynced ? 0 : 3,
                    unattemptedMetricPosts: mockSim.dailyCacheSynced ? 0 : 3,
                    canContinue: false
                };
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify(bundle));
                return;
            }

            // Forward everything else (static HTML/CSS/JS/webp, /api/meta, /api/algo/*) to real Spring Boot server
            const bodyBuf = req.method !== "GET" && req.method !== "HEAD" ? await readReqBody(req) : undefined;
            const upstream = await fetch(`http://127.0.0.1:${BACKEND_PORT}${urlObj.pathname}${urlObj.search}`, {
                method: req.method,
                headers: req.headers["content-type"] ? { "Content-Type": req.headers["content-type"] } : {},
                body: bodyBuf
            });
            const arrBuf = Buffer.from(await upstream.arrayBuffer());
            res.writeHead(upstream.status, {
                "Content-Type": upstream.headers.get("content-type") || "application/octet-stream",
                "Cache-Control": "no-store"
            });
            res.end(arrBuf);
        });
        srv.listen(PROXY_PORT, "127.0.0.1", () => resolve(srv));
    });
}

async function waitForHttp(url, timeoutMs = 15000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        try {
            const r = await fetch(url);
            if (r.ok) return;
        } catch {}
        await new Promise((r) => setTimeout(r, 250));
    }
    throw new Error(`Timed out waiting for ${url}`);
}

class CdpSession {
    constructor(wsUrl) {
        this.ws = new WebSocket(wsUrl);
        this.nextId = 1;
        this.pending = new Map();
        this.consoleErrors = [];
        this.pageExceptions = [];
        this.failedRequests = [];
    }
    async connect() {
        await new Promise((resolve, reject) => {
            this.ws.addEventListener("open", resolve, { once: true });
            this.ws.addEventListener("error", reject, { once: true });
        });
        this.ws.addEventListener("message", (ev) => {
            const msg = JSON.parse(String(ev.data));
            if (msg.id && this.pending.has(msg.id)) {
                const { resolve, reject } = this.pending.get(msg.id);
                this.pending.delete(msg.id);
                if (msg.error) reject(new Error(msg.error.message));
                else resolve(msg.result);
            } else if (msg.method === "Runtime.exceptionThrown") {
                this.pageExceptions.push(msg.params.exceptionDetails?.text || "exception");
            } else if (msg.method === "Runtime.consoleAPICalled" && msg.params.type === "error") {
                this.consoleErrors.push(msg.params.args.map((a) => a.value ?? a.description).join(" "));
            } else if (msg.method === "Network.responseReceived") {
                const { status, url } = msg.params.response;
                if (status >= 400 && !url.includes("/api/analyze/bundle") && !url.includes("favicon")) {
                    this.failedRequests.push(`${status} ${url}`);
                }
            }
        });
        await this.send("Page.enable");
        await this.send("Runtime.enable");
        await this.send("DOM.enable");
        await this.send("Network.enable");
    }
    send(method, params = {}) {
        const id = this.nextId++;
        this.ws.send(JSON.stringify({ id, method, params }));
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
        });
    }
    async eval(expr) {
        const res = await this.send("Runtime.evaluate", {
            expression: `(async () => { ${expr} })()`,
            awaitPromise: true,
            returnByValue: true
        });
        if (res.exceptionDetails) {
            throw new Error(
                `Eval error: ${res.exceptionDetails.exception?.description || res.exceptionDetails.text}`
            );
        }
        return res.result.value;
    }
    async setViewport(width, height, mobile = false) {
        await this.send("Emulation.setDeviceMetricsOverride", {
            width,
            height,
            deviceScaleFactor: 2,
            mobile
        });
    }
    close() {
        this.ws.close();
    }
}

async function launchChrome() {
    const userDataDir = path.join(tmpRoot, "chrome-profile");
    const proc = spawn(
        CHROME_BIN,
        [
            "--headless=new",
            "--disable-gpu",
            "--no-first-run",
            "--no-default-browser-check",
            `--user-data-dir=${userDataDir}`,
            "--remote-debugging-port=0",
            "about:blank"
        ],
        { stdio: ["ignore", "pipe", "pipe"] }
    );
    const wsUrl = await new Promise((resolve, reject) => {
        let stderr = "";
        const timer = setTimeout(() => reject(new Error(`Chrome launch timeout: ${stderr}`)), 10000);
        proc.stderr.on("data", (chunk) => {
            stderr += chunk.toString();
            const m = /DevTools listening on (ws:\/\/[^\s]+)/.exec(stderr);
            if (m) {
                clearTimeout(timer);
                resolve(m[1]);
            }
        });
    });
    const browserWs = new CdpSession(wsUrl);
    await new Promise((resolve, reject) => {
        browserWs.ws.addEventListener("open", resolve, { once: true });
        browserWs.ws.addEventListener("error", reject, { once: true });
    });
    // Discover first page target via HTTP /json/list
    const portMatch = /:(\d+)\//.exec(wsUrl);
    const targets = await (await fetch(`http://127.0.0.1:${portMatch[1]}/json/list`)).json();
    const pageTarget = targets.find((t) => t.type === "page");
    browserWs.close();
    const page = new CdpSession(pageTarget.webSocketDebuggerUrl);
    await page.connect();
    return { proc, page };
}

const findings = [];
function recordFinding(severity, area, detail) {
    findings.push({ severity, area, detail });
    console.log(`[FINDING][${severity}] ${area}: ${detail}`);
}

async function main() {
    BACKEND_PORT = await getFreePort();
    PROXY_PORT = await getFreePort();
    console.log("1. Starting isolated DEMO Spring Boot backend on port", BACKEND_PORT, "and proxy on", PROXY_PORT);
    const backend = spawn(
        JAVA_BIN,
        [
            "-jar",
            JAR_PATH,
            `--server.port=${BACKEND_PORT}`,
            "--xops.default-provider=DEMO",
            "--xops.require-login-self-only=false",
            `--xops.disk-cache-dir=${path.join(tmpRoot, "cache")}`,
            `--xops.scheduled-tweets-dir=${path.join(tmpRoot, "sched")}`,
            `--xops.algo-profile-path=${path.join(tmpRoot, "algo-override.json")}`
        ],
        { stdio: ["ignore", "pipe", "pipe"] }
    );

    let proxySrv = null;
    let chrome = null;
    try {
        await waitForHttp(`http://127.0.0.1:${BACKEND_PORT}/api/health`);
        proxySrv = await startProxyServer();
        chrome = await launchChrome();
        const { page } = chrome;

        // ==================== JOURNEY 1: Guest Auth Gate ====================
        console.log("2. Auditing Guest Auth Gate (1440x900)...");
        await page.setViewport(1440, 900, false);
        await page.send("Page.navigate", { url: `http://127.0.0.1:${PROXY_PORT}/` });
        await new Promise((r) => setTimeout(r, 800));

        const guestCheck = await page.eval(`
            const gateVisible = !$("authGateSection").hidden && getComputedStyle($("authGateSection")).display !== "none";
            const wsHidden = $("authenticatedWorkspace").hidden;
            const navHidden = $("leftNavMenu").hidden;
            const rightHidden = $("authenticatedRightRail").hidden;
            const beforeTheme = document.documentElement.getAttribute("data-theme");
            $("gateThemeBtn").click();
            const afterTheme = document.documentElement.getAttribute("data-theme");
            $("gateAuthorBtn").click();
            const modalOpen = !$("authorPromptModal").hidden && document.body.classList.contains("modal-open");
            document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
            const modalClosed = $("authorPromptModal").hidden && !document.body.classList.contains("modal-open");
            const overflowX = document.documentElement.scrollWidth - window.innerWidth;
            function $(id) { return document.getElementById(id); }
            return { gateVisible, wsHidden, navHidden, rightHidden, beforeTheme, afterTheme, modalOpen, modalClosed, overflowX };
        `);
        assert.equal(guestCheck.gateVisible, true);
        assert.equal(guestCheck.wsHidden, true);
        assert.equal(guestCheck.navHidden, true);
        assert.equal(guestCheck.rightHidden, true);
        assert.notEqual(guestCheck.beforeTheme, guestCheck.afterTheme);
        assert.equal(guestCheck.modalOpen, true);
        assert.equal(guestCheck.modalClosed, true);
        if (guestCheck.overflowX > 0) {
            recordFinding("WARN", "Guest Gate Desktop", `Horizontal overflow ${guestCheck.overflowX}px`);
        }

        // ==================== JOURNEY 2: Logged-in Workspace + Periods ====================
        console.log("3. Auditing Logged-in Workspace, Period Lock Modal, and 4 Period Switches...");
        mockSim.loggedIn = true;
        mockSim.unlockedPeriods = ["HOURS_24", "HOURS_48"];
        await page.send("Page.navigate", { url: `http://127.0.0.1:${PROXY_PORT}/?auth=ok` });
        await new Promise((r) => setTimeout(r, 1200));

        const periodCheck = await page.eval(`
            function $(id) { return document.getElementById(id); }
            const toastShown = !$("copyToast").hidden;
            const syncedStatusHidden = $("dailyCacheStatusHint").hidden;
            // Click locked 7d button
            const btn7d = document.querySelector('.seg-btn[data-period="WEEK"]');
            btn7d.click();
            const unlockOpen = !$("unlockGuideModal").hidden;
            const focusVisible = !$("unlockGuideFocusMsg").hidden && $("unlockGuideFocusMsg").textContent.includes("7 天");
            $("unlockGuideCloseBtn").click();
            const unlockClosed = $("unlockGuideModal").hidden;
            // Switch to 48h
            document.querySelector('.seg-btn[data-period="HOURS_48"]').click();
            await new Promise(r => setTimeout(r, 400));
            const active48 = document.querySelector('.seg-btn[data-period="HOURS_48"]').classList.contains("active");
            return { toastShown, syncedStatusHidden, unlockOpen, focusVisible, unlockClosed, active48 };
        `);
        assert.equal(periodCheck.syncedStatusHidden, true);
        assert.equal(periodCheck.unlockOpen, true);
        assert.equal(periodCheck.focusVisible, true);
        assert.equal(periodCheck.unlockClosed, true);
        assert.equal(periodCheck.active48, true);

        // Unlock all 4 periods & switch to WEEK so rich demo data is loaded
        mockSim.unlockedPeriods = ["HOURS_24", "HOURS_48", "WEEK", "MONTH"];
        await page.send("Page.reload");
        await new Promise((r) => setTimeout(r, 1000));
        await page.eval(`
            document.querySelector('.seg-btn[data-period="WEEK"]').click();
            await new Promise(r => setTimeout(r, 500));
        `);

        // ==================== JOURNEY 3: Tab 1 (Activity) ====================
        console.log("4. Auditing Tab 1 (Activity) & Trend Bar Tap Readout...");
        const actCheck = await page.eval(`
            function $(id) { return document.getElementById(id); }
            const unlockHelpHiddenWhenAllOpen = $("periodUnlockHelpBtn").hidden;
            const col = document.querySelector("#dailyTrendChart .day-col[data-tip]");
            const beforeSub = $("trendChartSubtitle").textContent;
            col.click();
            const afterSub = $("trendChartSubtitle").textContent;
            const subVisible = !$("trendChartSubtitle").hidden && getComputedStyle($("trendChartSubtitle")).display !== "none";
            col.click();
            const restoredSub = $("trendChartSubtitle").textContent;
            return { unlockHelpHiddenWhenAllOpen, beforeSub, afterSub, subVisible, restoredSub };
        `);
        assert.equal(actCheck.unlockHelpHiddenWhenAllOpen, true);
        assert.notEqual(actCheck.beforeSub, actCheck.afterSub);
        assert.equal(actCheck.restoredSub, actCheck.beforeSub);

        // ==================== JOURNEY 4: Tab 2 (Interactions) ====================
        console.log("5. Auditing Tab 2 (Interactions) Filters, Sort, Search, Row Expand...");
        const interCheck = await page.eval(`
            function $(id) { return document.getElementById(id); }
            document.querySelector('.nav-item[data-tab="interactions"]').click();
            await new Promise(r => setTimeout(r, 150));
            const pillResults = [];
            for (const chip of document.querySelectorAll("#interactionTypePills .pill-chip")) {
                chip.click();
                pillResults.push({ filter: chip.dataset.relFilter, active: chip.classList.contains("active") });
            }
            // Find a filter with at least 1 row
            for (const chip of document.querySelectorAll("#interactionTypePills .pill-chip")) {
                chip.click();
                if (document.querySelector("#peerInteractionList .peer-row")) break;
            }
            const firstRow = document.querySelector("#peerInteractionList .peer-row");
            let rowExpanded = false;
            let rowCollapsed = false;
            if (firstRow) {
                firstRow.click();
                rowExpanded = Boolean(document.querySelector("#peerInteractionList .peer-detail"));
                const openRow = document.querySelector("#peerInteractionList .peer-row");
                openRow.click();
                rowCollapsed = !document.querySelector("#peerInteractionList .peer-detail");
            }
            // Search non-existent handle & clear via empty-state button
            const searchInp = $("peerSearchInput");
            searchInp.value = "non_existent_xyz_999";
            searchInp.dispatchEvent(new Event("input", { bubbles: true }));
            await new Promise(r => setTimeout(r, 220));
            const clearBtn = document.querySelector('#peerInteractionList [data-go="clearPeerSearch"]');
            const hasEmptyClearBtn = Boolean(clearBtn);
            if (clearBtn) clearBtn.click();
            const searchCleared = searchInp.value === "";
            return { pillResults, rowExpanded, rowCollapsed, hasEmptyClearBtn, searchCleared };
        `);
        assert.equal(interCheck.rowExpanded, true);
        assert.equal(interCheck.rowCollapsed, true);
        assert.equal(interCheck.hasEmptyClearBtn, true);
        assert.equal(interCheck.searchCleared, true);

        // ==================== JOURNEY 5: Tab 3 (Follow Audit) ====================
        console.log("6. Auditing Tab 3 (Follow Audit) Tabs, Whitelist Toggle, CSV Export...");
        const followCheck = await page.eval(`
            function $(id) { return document.getElementById(id); }
            document.querySelector('.nav-item[data-tab="follow-audit"]').click();
            await new Promise(r => setTimeout(r, 150));
            const wlBtn = document.querySelector("#followAuditList [data-wl-toggle]");
            let wlToggled = false;
            if (wlBtn) {
                const beforeTxt = $("suggestCleanPillBtn").textContent;
                wlBtn.click();
                const afterTxt = $("suggestCleanPillBtn").textContent;
                wlToggled = beforeTxt !== afterTxt || !$("whitelistPillBtn").hidden;
                // toggle back
                const wlBtn2 = document.querySelector("#followAuditList [data-wl-toggle]");
                if (wlBtn2) wlBtn2.click();
            }
            $("exportFollowCsvBtn").click();
            const exportToast = $("copyToast").textContent;
            // Switch through FAN_ONLY and MUTUAL tabs
            const segTabs = document.querySelectorAll("#followStateTabs .seg-tab");
            segTabs[1].click();
            const pillsHiddenOnFan = $("unfollowCategoryPills").hidden;
            segTabs[2].click();
            segTabs[0].click();
            return { wlToggled, exportToast, pillsHiddenOnFan };
        `);
        assert.equal(followCheck.wlToggled, true);
        assert.equal(followCheck.pillsHiddenOnFan, true);
        assert.match(followCheck.exportToast, /导出/);

        // ==================== JOURNEY 6: Tab 4 (Timing, Heatmap, Ops Buttons, Simulator, Algo Config) ====================
        console.log("7. Auditing Tab 4 (Timing, Heatmap Readout, Ops Playbook Actions, Simulator, Algo Config)...");
        const timingAudit = await page.eval(`
            function $(id) { return document.getElementById(id); }
            document.querySelector('.nav-item[data-tab="timing"]').click();
            await new Promise(r => setTimeout(r, 200));

            // 6a. Heatmap cell click readout visibility test!
            const hmCell = document.querySelector("#heatmapGridRows .hm-cell[data-tip]");
            hmCell.click();
            const hmReadoutHiddenAfterClick = $("timingPeriodDesc").hidden;
            const hmReadoutDisplay = getComputedStyle($("timingPeriodDesc")).display;
            const hmReadoutText = $("timingPeriodDesc").textContent;
            hmCell.click();
            const hmReadoutHiddenAfterUntoggle = $("timingPeriodDesc").hidden;

            // 6b. Check Ops Playbook action buttons!
            const opsBtns = Array.from(document.querySelectorAll("#algoOpsPlaybookList button.algo-ops-go")).map(b => ({
                text: b.textContent.trim(),
                go: b.dataset.go,
                title: b.closest(".algo-ops-item")?.querySelector(".algo-ops-title")?.textContent?.trim() || ""
            }));

            // 6c. Top Post -> Draft Simulator button
            const topCheckBtn = document.querySelector("#topTweetsList [data-top-post-id]");
            topCheckBtn.click();
            const simVisible = !$("draftSimulatorResult").hidden;
            const simScoreText = document.querySelector("#draftSimulatorResult .draft-score-num")?.textContent || "";
            const dimsCount = document.querySelectorAll("#draftSimulatorResult .draft-dim-chip").length;

            // 6d. Test risky draft in simulator (@mention + shortener)
            $("draftSimulatorInput").value = "@someone 看看这个短链 https://bit.ly/3test";
            $("draftSimulatorInput").dispatchEvent(new Event("input", { bubbles: true }));
            await new Promise(r => setTimeout(r, 150));
            const hasAdviceBox = Boolean(document.querySelector("#draftSimulatorResult .draft-ops-advice-box"));
            const clearSimBtn = $("clearDraftSimBtn");
            clearSimBtn.click();
            const simCleared = $("draftSimulatorResult").hidden && $("draftSimulatorInput").value === "";

            // 6e. Algo Config Drawer open, save override, verify badge, reset to builtin, Escape to close
            $("toggleAlgoConfigBtn").click();
            const drawerOpen = !$("algoConfigDrawer").hidden;
            const rawJson = JSON.parse($("algoProfileJsonInput").value);
            rawJson.version = "2026.12-ui-test";
            $("algoProfileJsonInput").value = JSON.stringify(rawJson, null, 2);
            $("saveAlgoProfileBtn").click();
            await new Promise(r => setTimeout(r, 500));
            const overrideBadgeVisible = !$("algoProfileVersionBadge").hidden && $("algoProfileVersionBadge").textContent === "自定义参数";
            $("resetAlgoProfileBtn").click();
            await new Promise(r => setTimeout(r, 500));
            const resetBadgeHidden = $("algoProfileVersionBadge").hidden;
            document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
            const drawerClosedByEscape = $("algoConfigDrawer").hidden;

            // 6f. Test scrollId deep-link button (e.g., WINNING_FORMAT or CONTROVERSY_GUARD or RESIDUAL_ANOMALY_REVIEW)
            let scrollIdFlashed = false;
            const scrollBtn = document.querySelector('#algoOpsPlaybookList button.algo-ops-go[data-go*="scrollId:"]');
            if (scrollBtn) {
                const targetId = scrollBtn.dataset.go.split("scrollId:")[1].split("|")[0];
                scrollBtn.click();
                scrollIdFlashed = document.getElementById(targetId)?.classList.contains("is-target-flash") || false;
            }

            // 6g. Test GOLDEN_SLOT_LAUNCH ("去排期 ›") deep-link -> switches to schedule & fills golden slot & marks 1 active chip
            let goldenGoSwitched = false;
            let goldenActiveChipCount = 0;
            const goldenBtn = document.querySelector('#algoOpsPlaybookList button.algo-ops-go[data-go*="goldenSlot:"]');
            if (goldenBtn) {
                goldenBtn.click();
                await new Promise(r => setTimeout(r, 150));
                goldenGoSwitched = document.getElementById("panel-schedule").classList.contains("active");
                goldenActiveChipCount = document.querySelectorAll("#scheduleQuickSlotChips .pill-chip.active").length;
                document.querySelector('.nav-item[data-tab="timing"]').click();
                await new Promise(r => setTimeout(r, 100));
            }

            return {
                hmReadoutHiddenAfterClick,
                hmReadoutDisplay,
                hmReadoutText,
                hmReadoutHiddenAfterUntoggle,
                opsBtns,
                simVisible,
                simScoreText,
                dimsCount,
                hasAdviceBox,
                simCleared,
                drawerOpen,
                drawerClosedByEscape,
                overrideBadgeVisible,
                resetBadgeHidden,
                scrollIdFlashed,
                goldenGoSwitched,
                goldenActiveChipCount
            };
        `);
        if (timingAudit.hmReadoutHiddenAfterClick || timingAudit.hmReadoutDisplay === "none") {
            recordFinding(
                "BUG",
                "Heatmap Cell Tap Readout (#timingPeriodDesc)",
                `Clicking heatmap cell sets text "${timingAudit.hmReadoutText}" but #timingPeriodDesc remains hidden (display=${timingAudit.hmReadoutDisplay})!`
            );
        }
        assert.equal(timingAudit.hmReadoutHiddenAfterClick, false);
        assert.notEqual(timingAudit.hmReadoutDisplay, "none");
        assert.equal(timingAudit.hmReadoutHiddenAfterUntoggle, true);
        for (const ob of timingAudit.opsBtns) {
            if (ob.go === "tab:timing") {
                recordFinding(
                    "UX",
                    `Ops Playbook Button (${ob.title})`,
                    `Button "${ob.text}" has generic data-go="tab:timing" while already inside tab:timing!`
                );
            }
        }
        assert.equal(timingAudit.simVisible, true);
        assert.equal(timingAudit.dimsCount, 4);
        assert.equal(timingAudit.hasAdviceBox, true);
        assert.equal(timingAudit.simCleared, true);
        assert.equal(timingAudit.drawerOpen, true);
        assert.equal(timingAudit.drawerClosedByEscape, true);
        assert.equal(timingAudit.overrideBadgeVisible, true);
        assert.equal(timingAudit.resetBadgeHidden, true);
        assert.equal(timingAudit.scrollIdFlashed, true);
        assert.equal(timingAudit.goldenGoSwitched, true);
        assert.equal(timingAudit.goldenActiveChipCount, 1);

        // ==================== JOURNEY 7: Tab 5 (Circle & Share Card Poster -> Schedule) ====================
        console.log("8. Auditing Tab 5 (Circle & 2x Canvas Poster + Send to Schedule)...");
        const circleAudit = await page.eval(`
            function $(id) { return document.getElementById(id); }
            document.querySelector('.nav-item[data-tab="circle"]').click();
            await new Promise(r => setTimeout(r, 250));
            const canvas = $("shareCardCanvas");
            const dataUrlLen = canvas.toDataURL("image/png").length;
            const beforeHref = $("postToXIntentLink").href;
            $("toggleSharePrivacyBtn").click();
            const afterHref = $("postToXIntentLink").href;
            $("scheduleShareCardBtn").click();
            await new Promise(r => setTimeout(r, 200));
            const switchedToSchedule = document.getElementById("panel-schedule").classList.contains("active");
            const hasAttachedPoster = document.querySelectorAll("#scheduleDraftItemsList .schedule-media-thumb").length === 1;
            return { dataUrlLen, privacyChanged: beforeHref !== afterHref, switchedToSchedule, hasAttachedPoster };
        `);
        assert.ok(circleAudit.dataUrlLen > 5000, "Share card canvas must render rich PNG");
        assert.equal(circleAudit.privacyChanged, true);
        assert.equal(circleAudit.switchedToSchedule, true);
        assert.equal(circleAudit.hasAttachedPoster, true);

        // ==================== JOURNEY 8: Tab 6 (Schedule Composer, Conflict Align, Queue Actions, Overdue Modal) ====================
        console.log("9. Auditing Tab 6 (Schedule Batch, Conflict Align, Overdue Modal, Filters)...");
        const schedAudit = await page.eval(`
            function $(id) { return document.getElementById(id); }
            $("clearScheduleDraftBtn").click();
            const ta0 = document.querySelector('#scheduleDraftItemsList [data-draft-textarea="0"]');
            ta0.value = "@leading_user 测试开头@风险提示标签";
            ta0.dispatchEvent(new Event("input", { bubbles: true }));
            const chip0 = document.querySelector('#scheduleDraftItemsList [data-draft-algo-chip="0"]');
            const riskChipShown = !chip0.hidden && chip0.textContent.includes("开头@受限");

            ta0.value = "第一条正常定时推文内容，无风险标记";
            ta0.dispatchEvent(new Event("input", { bubbles: true }));
            const riskChipHiddenWhenClean = chip0.hidden;

            // Add 2nd item and force <1h conflict
            $("addScheduleDraftItemBtn").click();
            const ta1 = document.querySelector('#scheduleDraftItemsList [data-draft-textarea="1"]');
            ta1.value = "第二条批量定时推文内容";
            ta1.dispatchEvent(new Event("input", { bubbles: true }));

            const t0 = document.querySelector('#scheduleDraftItemsList [data-draft-time="0"]');
            const t1 = document.querySelector('#scheduleDraftItemsList [data-draft-time="1"]');
            t1.value = t0.value; // exact collision!
            t1.dispatchEvent(new Event("change", { bubbles: true }));
            const alignBtnVisibleOnConflict = !$("alignScheduleGapBtn").hidden;
            $("alignScheduleGapBtn").click();
            const alignBtnHiddenAfterFix = $("alignScheduleGapBtn").hidden;

            // Submit batch!
            $("createScheduleBtn").click();
            await new Promise(r => setTimeout(r, 400));
            const totalCardsAfterBatch = document.querySelectorAll("#scheduledTweetsList .schedule-job-card").length;

            // Open overdue confirm modal on job-overdue-1
            const confirmBtn = document.querySelector('#scheduledTweetsList [data-job-action="open-confirm"][data-job-id="job-overdue-1"]');
            confirmBtn.click();
            const modalOpen = !$("confirmResendModal").hidden;
            $("confirmResendSubmitBtn").click();
            await new Promise(r => setTimeout(r, 350));
            const modalClosedAfterSubmit = $("confirmResendModal").hidden;

            // Reschedule & Cancel & Delete on a SCHEDULED job
            const reschedToggle = document.querySelector('#scheduledTweetsList [data-job-action="toggle-reschedule"]');
            reschedToggle.click();
            const reschedBoxShown = Boolean(document.querySelector("#scheduledTweetsList .schedule-reschedule-box"));
            document.querySelector('#scheduledTweetsList [data-job-action="close-reschedule"]').click();

            return {
                riskChipShown,
                riskChipHiddenWhenClean,
                alignBtnVisibleOnConflict,
                alignBtnHiddenAfterFix,
                totalCardsAfterBatch,
                modalOpen,
                modalClosedAfterSubmit,
                reschedBoxShown
            };
        `);
        assert.equal(schedAudit.riskChipShown, true);
        assert.equal(schedAudit.riskChipHiddenWhenClean, true);
        assert.equal(schedAudit.alignBtnVisibleOnConflict, true);
        assert.equal(schedAudit.alignBtnHiddenAfterFix, true);
        assert.ok(schedAudit.totalCardsAfterBatch >= 5);
        assert.equal(schedAudit.modalOpen, true);
        assert.equal(schedAudit.modalClosedAfterSubmit, true);
        assert.equal(schedAudit.reschedBoxShown, true);

        // ==================== JOURNEY 8b: Step-Up Publish Auth & Top-100 Lock ====================
        console.log("9b. Auditing Step-Up Publish OAuth Draft Preservation & Top-100 Lock Gate...");
        mockSim.writeAuthorized = false;
        await page.eval(`
            document.getElementById("refreshScheduleListBtn").click();
            await new Promise(r => setTimeout(r, 350));
        `);
        const stepUpBefore = await page.eval(`
            function $(id) { return document.getElementById(id); }
            const authCardShown = !$("scheduleWriteAuthCard").hidden;
            const ta0 = document.querySelector('#scheduleDraftItemsList [data-draft-textarea="0"]');
            ta0.value = "二次授权前暂存的草稿文案，授权回来后应自动恢复";
            ta0.dispatchEvent(new Event("input", { bubbles: true }));
            $("triggerPublishAuthBtn").click();
            return { authCardShown };
        `);
        assert.equal(stepUpBefore.authCardShown, true);
        await new Promise((r) => setTimeout(r, 1200));
        const stepUpAfter = await page.eval(`
            function $(id) { return document.getElementById(id); }
            const schedulePanelActive = $("panel-schedule").classList.contains("active");
            const authCardHidden = $("scheduleWriteAuthCard").hidden;
            const ta0 = document.querySelector('#scheduleDraftItemsList [data-draft-textarea="0"]');
            return { schedulePanelActive, authCardHidden, restoredText: ta0 ? ta0.value : "" };
        `);
        assert.equal(stepUpAfter.schedulePanelActive, true);
        assert.equal(stepUpAfter.authCardHidden, true);
        assert.equal(stepUpAfter.restoredText, "二次授权前暂存的草稿文案，授权回来后应自动恢复");

        // Test Top-100 lock gate
        mockSim.scheduleAccessAllowed = false;
        mockSim.authorInteractionRank = 128;
        await page.eval(`
            document.getElementById("refreshScheduleListBtn").click();
            await new Promise(r => setTimeout(r, 350));
        `);
        const lockGateCheck = await page.eval(`
            function $(id) { return document.getElementById(id); }
            return {
                eligCardShown: !$("scheduleEligibilityCard").hidden,
                createDisabled: $("createScheduleBtn").disabled
            };
        `);
        assert.equal(lockGateCheck.eligCardShown, true);
        assert.equal(lockGateCheck.createDisabled, true);
        mockSim.scheduleAccessAllowed = true;
        mockSim.authorInteractionRank = 12;
        await page.eval(`
            document.getElementById("refreshScheduleListBtn").click();
            await new Promise(r => setTimeout(r, 350));
        `);

        // ==================== JOURNEY 9: Mobile Viewport (390x844) Layout & Horizontal Overflow Audit ====================
        console.log("10. Auditing Mobile Viewport (390x844) across ALL 6 Tabs for Overflow / Clipping...");
        await page.setViewport(390, 844, true);
        await new Promise((r) => setTimeout(r, 250));

        const mobileTabs = ["activity", "interactions", "follow-audit", "timing", "circle", "schedule"];
        for (const tab of mobileTabs) {
            const mobCheck = await page.eval(`
                const btn = document.querySelector('.mobile-bottom-nav .bottom-tab[data-tab="${tab}"]');
                btn.click();
                await new Promise(r => setTimeout(r, 150));
                if ("${tab}" === "timing") {
                    const inp = document.getElementById("draftSimulatorInput");
                    inp.value = "测试移动端草稿体检卡片在小屏下的排版宽度与换行表现 https://github.com/xai-org/x-algorithm";
                    inp.dispatchEvent(new Event("input", { bubbles: true }));
                    await new Promise(r => setTimeout(r, 150));
                }
                const docOverflow = document.documentElement.scrollWidth - window.innerWidth;
                const overflowingEls = [];
                const isInsideHorizontalScrollStrip = (node) => {
                    let cur = node.parentElement;
                    while (cur && cur !== document.body) {
                        const ox = getComputedStyle(cur).overflowX;
                        if ((ox === "auto" || ox === "scroll") && cur.clientWidth <= window.innerWidth) {
                            return true;
                        }
                        cur = cur.parentElement;
                    }
                    return false;
                };
                for (const el of document.querySelectorAll("#panel-${tab} *")) {
                    const st = getComputedStyle(el);
                    if (st.display === "none" || st.visibility === "hidden") continue;
                    if (isInsideHorizontalScrollStrip(el)) continue;
                    const rect = el.getBoundingClientRect();
                    if (rect.width > 0 && rect.right > window.innerWidth + 2) {
                        overflowingEls.push(\`\${el.tagName.toLowerCase()}\${el.id ? "#" + el.id : ""}\${el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\\s+/).join(".") : ""} (right=\${Math.round(rect.right)}px)\`);
                    }
                }
                const pillMutualRight = "${tab}" === "interactions"
                    ? Math.round(document.getElementById("pillMutualBtn")?.getBoundingClientRect()?.right || 0)
                    : 0;
                return { docOverflow, overflowingEls: overflowingEls.slice(0, 8), pillMutualRight };
            `);
            if (mobCheck.docOverflow > 1 || mobCheck.overflowingEls.length > 0) {
                recordFinding(
                    "LAYOUT",
                    `Mobile 390px Tab [${tab}]`,
                    `docOverflow=${mobCheck.docOverflow}px; elements exceeding viewport: ${mobCheck.overflowingEls.join(", ")}`
                );
            }
            if (tab === "interactions" && mobCheck.pillMutualRight > 390) {
                recordFinding(
                    "UX",
                    "Mobile 390px #interactionTypePills",
                    `#pillMutualBtn extends past 390px (right=${mobCheck.pillMutualRight}px)`
                );
            }
        }

        // ==================== JOURNEY 10: Error Toast & Logout Cleanup ====================
        console.log("11. Auditing Error Toast & Logout State Isolation...");
        await page.setViewport(1440, 900, false);
        mockSim.forceNextBundle500 = true;
        const errAndLogout = await page.eval(`
            function $(id) { return document.getElementById(id); }
            $("forceRefreshBtn").click();
            await new Promise(r => setTimeout(r, 400));
            const errVisible = !$("errorToast").hidden && $("errorToastText").textContent.includes("500");
            $("errorToastCloseBtn").click();
            const errClosed = $("errorToast").hidden;

            $("railLogoutBtn").click();
            await new Promise(r => setTimeout(r, 400));
            const backToGate = !$("authGateSection").hidden && $("authenticatedWorkspace").hidden;
            return { errVisible, errClosed, backToGate };
        `);
        assert.equal(errAndLogout.errVisible, true);
        assert.equal(errAndLogout.errClosed, true);
        assert.equal(errAndLogout.backToGate, true);

        if (page.consoleErrors.length > 0) {
            recordFinding("ERROR", "Console Errors", page.consoleErrors.join(" | "));
        }
        if (page.pageExceptions.length > 0) {
            recordFinding("ERROR", "Uncaught Page Exceptions", page.pageExceptions.join(" | "));
        }
        if (page.failedRequests.length > 0) {
            recordFinding("WARN", "Unexpected HTTP >=400", page.failedRequests.join(" | "));
        }

        console.log("\n=== E2E CHROME UI/UX AUDIT SUMMARY ===");
        console.log("Total findings:", findings.length);
        for (const f of findings) {
            console.log(` - [${f.severity}] ${f.area}: ${f.detail}`);
        }
        assert.equal(findings.length, 0, "Expected 0 UI/UX findings across all desktop & mobile journeys");
    } finally {
        if (chrome) {
            try { chrome.page.close(); } catch {}
            chrome.proc.kill("SIGKILL");
        }
        if (proxySrv) proxySrv.close();
        backend.kill("SIGTERM");
        fs.rmSync(tmpRoot, { recursive: true, force: true });
    }
}

main().catch((err) => {
    console.error("AUDIT FAILED:", err);
    process.exit(1);
});
