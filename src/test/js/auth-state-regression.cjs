const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
    const a = source.indexOf(start);
    const b = source.indexOf(end, a);
    assert.ok(a >= 0 && b > a, `Cannot locate production section: ${start}`);
    return source.slice(a, b);
}
const requestCode = section('    async function requestJsonWithin(', '    function syncAuthConnectionUI(');
const resetCode = section('    function cancelReportRequest()', '    // 已精简移除的元素');
const identityCode = section('    function stableUserId(', '    function getSelfStorageKey(');
const profileCacheCode = section('    function getProfileHeaderCacheKey(', '    function saveProfileHeaderCache(')
    + section('    function clearProfileHeaderCache(', '    function canPersistMediaToCacheStorage(');
const loadCode = section('    async function loadSelfReport(', '    // 首次加载骨架屏');
const logoutCode = section('        const doLogout = async () => {', '        $("railLogoutBtn").addEventListener')
    .replace('const doLogout =', 'globalThis.doLogout =');
const dailyStatusCode = section('    function renderDailyCacheStatus(', '    function renderProfileHeader(');
const noop = () => {};
function context() {
    const state = {authStatus: {loggedIn: true, user: {id: '1001', username: 'alice'}}, period: 'HOURS_48', days: 2,
        report: null, bundle: null, userScopeGeneration: 0, reportRequestGeneration: 0, authStatusGeneration: 0};
    const element = {classList: {remove: noop}, dataset: {}, hidden: false};
    const removedStorageKeys = [];
    const ctx = {state, LIST_PAGE_SIZE: 50, PERIOD_META: {HOURS_48: {badgePrefix: '48h'}}, Date, Number,
        Boolean, Set, AbortController, Intl, URLSearchParams, URL,
        removedStorageKeys, localStorage: {removeItem: key => removedStorageKeys.push(key)},
        document: {querySelectorAll: () => [], getElementById: () => null},
        $: () => element, setTimeout: () => 1, clearTimeout: noop, setInterval: () => 1, clearInterval: noop,
        isLoggedIn: () => Boolean(state.authStatus?.loggedIn), syncAuthGateAndWorkspaceUI: noop,
        updateRefreshButtonState: noop, hideError: noop, showLoadingSkeleton: noop,
        showError: msg => {ctx.error = msg;}, closeAuthorPromptModal: noop,
        cancelScheduleDraftPersistence: noop, clearPersistedScheduleDraft: async () => {},
        closeUnlockGuideModal: noop, closeConfirmResendModal: noop,
        safeParseJson: async r => r.body, fmtMonthDay: () => '', startCacheCooldownTicker: noop,
        applyLoadedPeriodReport: r => {state.report = r;}, fetchServerAndAuthStatus: async () => {},
        showToast: msg => {ctx.toast = msg;}};
    vm.createContext(ctx);
    vm.runInContext(identityCode + profileCacheCode + resetCode + loadCode + requestCode + logoutCode + dailyStatusCode, ctx);
    return ctx;
}
function response(username = 'alice', id = '1001') {
    const report = {profile: {id, username}, cacheRemainingSeconds: 100};
    return {status: 200, ok: true, body: {month: report, week: report, day: report,
        coveredWindowDays: 30, hasMoreHistoryForTarget: false}};
}
(async () => {
    const late = context();
    let release, signal;
    late.fetch = (_, options) => {signal = options.signal; return new Promise(r => {release = r;});};
    const pending = late.loadSelfReport();
    late.resetUserScopedMemoryState();
    late.state.authStatus = {loggedIn: false};
    assert.equal(signal.aborted, true, 'reset must cancel the actual fetch');
    release(response());
    await pending;
    assert.equal(late.state.report, null, 'late response must not repopulate logged-out state');
    assert.equal(late.state.bundle, null);
    assert.equal(late.error, undefined, 'cancelled requests must not show timeout failures');

    const changed = context();
    changed.fetch = () => new Promise(r => {release = r;});
    const previous = changed.loadSelfReport();
    changed.resetUserScopedMemoryState();
    changed.state.authStatus = {loggedIn: true, user: {id: '1002', username: 'bob'}};
    changed.state.isFetching = true; // a new identity has begun loading
    release(response());
    await previous;
    assert.equal(changed.state.report, null);
    assert.equal(changed.state.isFetching, true, 'old finally must not clear newer loading state');

    const wrongOwner = context();
    wrongOwner.fetch = async () => response('alice', '1002');
    await wrongOwner.loadSelfReport();
    assert.equal(wrongOwner.state.report, null);
    assert.match(wrongOwner.error, /账号不匹配/);

    const ownReport = context();
    ownReport.fetch = async () => response();
    await ownReport.loadSelfReport();
    assert.equal(ownReport.state.report.profile.id, '1001');
    assert.equal(ownReport.error, undefined);

    const missingIdentity = context();
    delete missingIdentity.state.authStatus.user.id;
    missingIdentity.fetch = async () => {assert.fail('missing identity must not issue a request');};
    await missingIdentity.loadSelfReport();
    assert.equal(missingIdentity.state.report, null);
    assert.match(missingIdentity.error, /稳定账号 ID/);

    const failed = context();
    failed.fetch = async () => ({status: 500, ok: false});
    await failed.doLogout();
    assert.equal(failed.error, undefined, 'logout failures stay visible in the current viewport');
    assert.equal(failed.state.authStatus.loggedIn, true);
    assert.match(failed.toast, /HTTP 500/);
    assert.deepEqual(failed.removedStorageKeys, [], 'failed logout must not remove the profile cache');

    const stillLoggedIn = context();
    stillLoggedIn.fetch = async () => ({status: 200, ok: true});
    await stillLoggedIn.doLogout();
    assert.equal(stillLoggedIn.error, undefined);
    assert.match(stillLoggedIn.toast, /退出尚未生效/);
    assert.equal(stillLoggedIn.state.authStatus.loggedIn, true);

    const network = context();
    network.fetch = async () => {throw new Error('offline');};
    await network.doLogout();
    assert.equal(network.error, undefined);
    assert.match(network.toast, /offline/);
    assert.equal(network.state.authStatus.loggedIn, true);

    const success = context();
    success.fetch = async () => ({status: 200, ok: true});
    success.fetchServerAndAuthStatus = async () => {success.state.authStatus = {loggedIn: false};};
    await success.doLogout();
    assert.match(success.toast, /已退出/);
    assert.equal(success.state.report, null);
    assert.deepEqual(success.removedStorageKeys, ['xops_profile_meta_user_1001', 'xops_last_uid'],
        'logout must clear only the stable-ID profile cache and last-user marker');

    const daily = context();
    daily.state.dailyCacheStatus = {snapshotAt: '2026-11-14T18:53:05Z',
        latestMetricFetchedAt: '2026-11-14T19:03:05Z', recentPosts: 1, recentInbound: 0, pendingMetricPosts: 0};
    daily.renderDailyCacheStatus({});
    assert.match(daily.$('dailyCacheStatusHint').textContent, /指标最近采集.*近24h.*分批读数/);
    assert.match(daily.$('dailyCacheStatusHint').textContent, /03:03:05/);
    delete daily.state.dailyCacheStatus.latestMetricFetchedAt;
    daily.renderDailyCacheStatus({});
    assert.match(daily.$('dailyCacheStatusHint').textContent, /指标最近采集时间未确认/);
    daily.state.dailyCacheStatus.lastCompletedAt = '2026-11-14T15:30:00Z';
    daily.state.dailyCacheStatus.nextCheckAt = '2026-11-14T16:30:00Z';
    daily.renderDailyCacheStatus({nextRefreshAt: '2026-11-15T08:00:00Z'});
    assert.match(daily.$('dailyCacheStatusHint').textContent, /上次完整同步[^·]*23:30:00/);
    assert.match(daily.$('dailyCacheStatusHint').textContent, /下次可检查更新[^·]*00:30:00/);
    daily.state.backgroundRefreshStatus = {state: 'PARTIAL', completedAt: '2026-11-14T16:01:00Z'};
    daily.renderDailyCacheStatus({});
    assert.match(daily.$('dailyCacheStatusHint').textContent, /部分.*更新|部分.*补齐/);
    assert.doesNotMatch(daily.$('dailyCacheStatusHint').textContent, /后台刷新完成/);
    const assistant = context();
    assistant.renderContentAssistant = noop;
    assistant.setContentAssistantError = noop;
    assistant.state.assistantRequestGeneration = 0;
    assistant.state.assistantAccess = {allowed: true};
    let deliverDashboard;
    assistant.contentAssistantRequest = () => new Promise(resolve => {deliverDashboard = resolve;});
    vm.runInContext(section('    async function loadContentAssistant(', '    async function runAssistantMutation('), assistant);
    const priorAccount = assistant.loadContentAssistant(true);
    assistant.resetUserScopedMemoryState();
    assistant.state.authStatus = {loggedIn: true, user: {id: 'other'}};
    deliverDashboard({drafts: [{id: 'private-draft', text: 'private text'}]});
    await priorAccount;
    assert.equal(assistant.state.assistantDashboard, null, 'late private dashboard response cannot populate another account');

    const filtered = context();
    filtered.renderContentAssistant = noop;
    filtered.setContentAssistantError = noop;
    Object.assign(filtered.state, {
        assistantAccess: {allowed: true}, assistantRequestGeneration: 0,
        assistantTopicSearch: '@OpenAI', assistantTopicSort: 'reach', assistantTopicOffset: 100,
        assistantSelectedTopicId: 'selected'
    });
    const assistantPaths = [];
    filtered.contentAssistantRequest = async path => {
        assistantPaths.push(path);
        return {topics: [{id: 'first'}, {id: 'selected'}], topicHasNext: false};
    };
    filtered.loadAssistantTopicDetail = async id => {assistantPaths.push(id);};
    vm.runInContext(section('    async function loadContentAssistant(', '    async function runAssistantMutation('), filtered);
    await filtered.loadContentAssistant(true);
    assert.equal(assistantPaths[0], '/dashboard?limit=100&topicOffset=100&q=%40OpenAI&sort=reach');
    assert.equal(filtered.state.assistantTopicOffset, 100, 'sync retains current page');
    assert.equal(assistantPaths[1], 'selected', 'sync reloads the selected topic instead of selecting the first match');

    const monitor = context();
    const controls = new Map();
    monitor.$ = id => {
        if (!controls.has(id)) controls.set(id, {dataset: {}, contains: () => false, replaceChildren: noop});
        return controls.get(id);
    };
    monitor.document.createTextNode = text => text;
    monitor.escapeHtml = s => String(s);
    monitor.assistantKeyValueRows = () => '';
    monitor.renderAssistantHistory = () => '';
    vm.runInContext(
        section('    const PENDING_QUOTE_STORAGE_PREFIX', '    function syncPolishPromptControls(')
        + '\n'
        + section('    function renderAssistantMonitor(', '    function renderContentAssistant('),
        monitor
    );
    const priorSettings = {settings: {model: 'stored', version: 1}};
    monitor.renderAssistantMonitor(priorSettings);
    monitor.$('assistantModel').value = 'edited before blur';
    monitor.$('assistantSettingsForm').dataset.editRevision = '1';
    monitor.renderAssistantMonitor(priorSettings);
    assert.equal(monitor.$('assistantModel').value, 'edited before blur', 'sync after blur preserves unsaved settings');
    delete monitor.$('assistantSettingsForm').dataset.editRevision;
    monitor.renderAssistantMonitor({settings: {version: 2, moduleConfigs: {generation: {provider: 'openai', model: 'gpt-6.2-sol high'}}}});
    assert.equal(monitor.$('assistantGenerationProvider').value, 'openai');
    assert.equal(monitor.$('assistantGenerationModelSelect').value, 'gpt-6.2-sol');
    assert.equal(monitor.$('assistantGenTargetSelect').value, 'openai|gpt-6.2-sol');
    assert.equal(monitor.state.assistantGenerationEffort, 'high');
    monitor.state.assistantGenerationTargetPinned = true;
    monitor.renderAssistantMonitor({settings: {version: 3, moduleConfigs: {generation: {provider: 'google', model: 'gemini-4-neon'}}}});
    assert.equal(monitor.$('assistantGenTargetSelect').value, 'openai|gpt-6.2-sol', 'pinned creation model survives background sync');
    monitor.state.assistantGenerationTargetPinned = false;

    const progress = context();
    const liveEditor = {value: 'text mid-edit', closest: () => ({dataset: {draftId: 'draft', draftVersion: '1'}})};
    const taskList = {};
    progress.$ = id => ({assistantDraftEditor: liveEditor, assistantTopicTasks: taskList})[id];
    progress.document.activeElement = liveEditor;
    progress.state.assistantSelectedTopicId = 'topic';
    progress.state.assistantSelectedDraftByTopic = {};
    progress.renderAssistantTask = task => task.stage;
    progress.escapeHtml = String;
    vm.runInContext(
        section('    const PENDING_QUOTE_STORAGE_PREFIX', '    function syncPolishPromptControls(')
        + '\n'
        + section('    function resolveRepostMaterialUrl(', '    function getRepostItemCommentText(')
        + section('    function classifyDraftLinks(', '    function buildCalibrationShingleArray(')
        + section('    function assistantDraftLengthText(', '    function assistantKeyValueRows('),
        progress
    );
    progress.renderAssistantTopicDetail({
        topics: [{id: 'topic'}], drafts: [{id: 'draft', topicId: 'topic', version: 1}],
        tasks: [{id: 'job', topicId: 'topic', stage: 'DRAFT'}]
    });
    assert.match(taskList.innerHTML, /DRAFT/, 'topic task status remains live during editing');
    assert.equal(liveEditor.value, 'text mid-edit');

    const topicDetail = {};
    progress.$ = id => id === 'assistantTopicDetail' ? topicDetail : null;
    Object.assign(progress, {
        assistantScoreText: String, escapeHtml: String,
        materialText: material => material.text,
        renderAssistantEvidenceChanges: () => '',
        renderAssistantObservationReview: () => ''
    });
    const material = {id: 'source', topicId: 'topic', text: 'A post'};
    progress.renderAssistantTopicDetail({topics: [{id: 'topic'}], materials: [material]});
    assert.doesNotMatch(topicDetail.innerHTML, /data-regenerate="true"/, 'first research uses the ordinary entry');
    progress.renderAssistantTopicDetail({
        topics: [{id: 'topic'}], materials: [material],
        drafts: [{id: 'draft', topicId: 'topic', materialId: 'source', version: 1}]
    });
    assert.match(topicDetail.innerHTML, /data-assistant-research="source">研究并生成/);
    assert.match(topicDetail.innerHTML, /data-assistant-research="source" data-regenerate="true">重新研究/);
    assert.match(topicDetail.innerHTML, /id="assistantDraftLength" class="inline-feedback" hidden/);
    assert.match(topicDetail.innerHTML, /id="assistantDraftNumberChanges"[^>]+ hidden/);
    progress.renderAssistantTopicDetail({
        topics: [{id: 'topic'}], materials: [material], drafts: [{
            id: 'draft', topicId: 'topic', materialId: 'source', version: 6,
            numberChanges: {added: ['0.5', '2.0'], met: false}
        }]
    });
    assert.match(topicDetail.innerHTML, /较基线新增数字 0\.5、2\.0，请审核。/);

    const constraintRepair = {
        attempted: true,
        initial: {hanLength: {actual: 147, min: 180, max: 240, met: false}, numberChanges: {added: ['2.0', '0.5'], met: false}},
        final: {hanLength: {actual: 205, min: 180, max: 240, met: true}, numberChanges: {added: [], met: true}}
    };
    progress.renderAssistantTopicDetail({
        topics: [{id: 'topic'}], materials: [material], drafts: [{
            id: 'draft', topicId: 'topic', materialId: 'source', version: 8, constraintRepair
        }]
    });
    assert.match(topicDetail.innerHTML, /已自动修订一次/);
    assert.match(topicDetail.innerHTML, /首次校验：147 个汉字.*未达标.*新增数字 2\.0、0\.5/);
    assert.match(topicDetail.innerHTML, /最终校验：205 个汉字.*已达标.*无新增数字/);

    const repairTask = context();
    repairTask.escapeHtml = text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    repairTask.renderAssistantResearch = () => '';
    vm.runInContext(section('    function assistantStatusLabel(', '    function assistantScoreText(')
        + section('    function renderAssistantTask(', '    function renderAssistantTopicDetail('), repairTask);
    const review = repairTask.renderAssistantTask({
        kind: 'OPTIMIZE', stage: 'OPTIMIZE_REVISION', status: 'CONSTRAINTS_UNMET',
        constraintRepair: {...constraintRepair, final: {numberChanges: {added: ['2.0'], met: false}}},
        result: {text: '<b>候选</b>'}
    });
    assert.match(review, /约束未达标/);
    assert.match(review, /最终校验：新增数字 2\.0/);
    assert.match(review, /查看未采用的候选稿/);
    assert.match(review, /&lt;b&gt;候选&lt;\/b&gt;/);
    const interrupted = repairTask.renderAssistantTask({
        kind: 'OPTIMIZE', stage: 'OPTIMIZE_REVISION', status: 'INTERRUPTED',
        constraintRepair: {attempted: true, initial: constraintRepair.initial}
    });
    assert.doesNotMatch(interrupted, /data-assistant-task-action="retry"/);

    const editorCase = context();
    const draftBox = {dataset: {draftId: 'd1', draftVersion: '1'}};
    const editor = {value: 'first edit', closest: () => draftBox};
    const saveState = {};
    const draftTitle = {textContent: '初稿 v1'};
    const draftLength = {hidden: true};
    const numberChanges = {hidden: false, textContent: '较基线新增数字 0.5、2.0，请审核。'};
    const draftSelect = {options: [
        {value: 'd1', textContent: '稿件 1 · v1'},
        {value: 'd2', textContent: '稿件 2 · v4'}
    ]};
    editorCase.$ = id => ({
        assistantDraftEditor: editor, assistantDraftSaveState: saveState,
        assistantDraftTitle: draftTitle, assistantDraftSelect: draftSelect,
        assistantDraftLength: draftLength, assistantDraftNumberChanges: numberChanges
    })[id];
    editorCase.syncScheduleDraftUnloadProtection = noop;
    editorCase.state.assistantDashboard = {drafts: [{id: 'd1', version: 1}]};
    editorCase.state.assistantDraftDirty = true;
    const writes = [];
    let resolveSave;
    editorCase.contentAssistantRequest = (_, input) => {
        writes.push([input.version, input.text]);
        if (input.version === 1) return new Promise(resolve => {resolveSave = resolve;});
        return Promise.resolve({
            id: 'd1', version: 3, text: input.text,
            hanLength: {min: 180, max: 240, actual: 123, met: false}, numberChanges: {added: [], met: true}
        });
    };
    vm.runInContext(section('    function assistantDraftLengthText(', '    function renderAssistantTopicDetail(')
        + section('    async function saveAssistantDraftEditor(', '    function queueAssistantDraftSave('), editorCase);
    const saved = editorCase.saveAssistantDraftEditor();
    editor.value = 'final edit';
    resolveSave({id: 'd1', version: 2, text: 'first edit'});
    assert.equal((await saved).version, 3);
    assert.deepEqual(writes, [[1, 'first edit'], [2, 'final edit']]);
    assert.equal(editorCase.state.assistantDraftDirty, false);
    assert.equal(draftBox.dataset.draftVersion, '3');
    assert.equal(draftTitle.textContent, '初稿 v3');
    assert.deepEqual(draftSelect.options.map(option => option.textContent), ['稿件 1 · v3', '稿件 2 · v4']);
    assert.equal(editor.value, 'final edit');
    assert.equal(draftLength.hidden, false);
    assert.equal(draftLength.textContent, '当前 123 个汉字 · 目标 180–240 · 未达标');
    assert.equal(numberChanges.hidden, true, 'saving a compliant edit clears the number warning');

    const historyCase = context();
    const draftRef = {dataset: {draftId: 'd1', draftVersion: '3'}};
    const activeDraftEditor = {value: 'current unsaved edit', closest: () => draftRef};
    const preview = {hidden: true, dataset: {}};
    const previewText = {};
    const previewTitle = {};
    const versionList = {hidden: false};
    historyCase.$ = id => ({
        assistantDraftEditor: activeDraftEditor, assistantDraftVersionPreview: preview,
        assistantDraftVersionText: previewText, assistantDraftVersionTitle: previewTitle,
        assistantDraftVersions: versionList
    })[id];
    const savedTexts = [];
    let queued = 0;
    historyCase.queueAssistantDraftSave = () => {queued++;};
    const historyClick = section('            const history = event.target.closest(', '            const optimize = event.target.closest(');
    vm.runInContext(`async function historyClick(event) { ${historyClick} }`, historyCase);
    const selectVersion = {dataset: {assistantVersion: '1', assistantVersionText: '<b>older text</b>'}};
    const click = (selector, target) => historyCase.historyClick({target: {closest: query => query === selector ? target : null}});
    await click('[data-assistant-version-text]', selectVersion);
    assert.equal(activeDraftEditor.value, 'current unsaved edit', 'viewing history leaves the editor alone');
    assert.equal(preview.hidden, false);
    assert.equal(previewTitle.textContent, '预览 v1');
    assert.equal(previewText.value, '<b>older text</b>');
    assert.deepEqual(savedTexts, []);
    assert.equal(queued, 0);
    historyCase.saveAssistantDraftEditor = async () => {savedTexts.push(activeDraftEditor.value); return {id: 'd1', version: 4};};
    const adoptions = [];
    historyCase.runAssistantMutation = async (_, path, request) => {
        adoptions.push([path,request.version,request.sourceVersion]);
        return {id:'d1',version:5,text:'<b>older text</b>'};
    };
    historyCase.renderAssistantTopicDetail = () => {};
    await click('#assistantAdoptDraftVersion', {isConnected: true});
    assert.deepEqual(savedTexts, ['current unsaved edit'], 'adopting preserves concurrent edits first');
    assert.deepEqual(adoptions, [['/drafts/d1/adopt',4,1]], 'restores the selected complete revision');
    assert.equal(queued, 0);
    assert.equal(preview.hidden, true);
    activeDraftEditor.value = 'current owner text';
    await click('[data-assistant-version-text]', selectVersion);
    let completePriorSave;
    historyCase.saveAssistantDraftEditor = () => new Promise(resolve => {completePriorSave = resolve;});
    const lateAdopt = click('#assistantAdoptDraftVersion', {isConnected: true});
    historyCase.state.authStatus = {loggedIn: true, user: {id: 'other'}};
    completePriorSave(true);
    await lateAdopt;
    assert.equal(activeDraftEditor.value, 'current owner text', 'a pending adoption cannot apply after account changes');
    assert.equal(queued, 0);
    assert.equal(adoptions.length, 1);

    const nonOwnerUi = context();
    const shellEl = {hidden: false};
    const noticeEl = {hidden: false};
    nonOwnerUi.$ = id => ({contentAssistantShell: shellEl, contentAssistantAccessNotice: noticeEl})[id] || null;
    nonOwnerUi.state.assistantAccess = {allowed: false};
    vm.runInContext(section('    function renderContentAssistant(', '    async function loadContentAssistant('), nonOwnerUi);
    nonOwnerUi.renderContentAssistant();
    assert.equal(shellEl.hidden, true, 'non-owner never sees assistant shell');
    assert.equal(noticeEl.hidden, true, 'non-owner never sees private assistant notice on public tab');

    monitor.renderAssistantMonitor({
        sources: [{
            id: 'src-1', username: 'OpenAI', status: 'ACTIVE',
            lastSuccessAt: 1700000000000, lastAttemptAt: 1700000300000,
            lagging: true, error: 'Invalid cursor token',
            gaps: [{fromAt: 1, toAt: 2, reason: 'expired'}]
        }],
        upstreamBudget: {ledgerState: 'SHARED_FILE_ACTIVE', hourlyUsed: 12, hourlyBudget: 120, dailyUsed: 40, dailyBudget: 800, dailyUsdReserved: 0.25, dailyUsdBudget: 5, budgetRejectedCalls: 0}
    });
    assert.match(monitor.$('assistantSourcesList').innerHTML, /采集滞后/);
    assert.match(monitor.$('assistantSourcesList').innerHTML, /异常：Invalid cursor token/);
    assert.match(monitor.$('assistantSourcesList').innerHTML, /历史缺口 1 处/);

    const waitingStyleHtml = repairTask.renderAssistantTask({
        id: 't-style', kind: 'RESEARCH', stage: 'DRAFT', status: 'WAITING_STYLE'
    });
    assert.match(waitingStyleHtml, /data-assistant-task-action="cancel"/);
    assert.match(waitingStyleHtml, /data-assistant-task-action="retry"/);
    const partialResearchHtml = repairTask.renderAssistantTask({
        id: 't-partial', kind: 'RESEARCH', stage: 'DRAFT', status: 'RESEARCH_PARTIAL'
    });
    assert.match(partialResearchHtml, /继续深挖/);
    assert.match(partialResearchHtml, /深度研究与成稿/);
    assert.match(partialResearchHtml, /撰写初稿/);
    assert.doesNotMatch(partialResearchHtml, />RESEARCH<|>DRAFT</);

    const synthesisTaskHtml = repairTask.renderAssistantTask({
        id: 't-syn', kind: 'RESEARCH', stage: 'RESEARCH_SYNTHESIS', status: 'RUNNING'
    });
    assert.match(synthesisTaskHtml, /证据核实与归纳/);
    assert.doesNotMatch(synthesisTaskHtml, /RESEARCH_SYNTHESIS/);
    assert.equal(repairTask.assistantStatusLabel('SUPPORTS'), '证据支持');
    assert.equal(repairTask.assistantStatusLabel('SCORED'), '已评分');
    assert.equal(repairTask.assistantSourceRefLabel('source-2#p3'), '网页来源 #2（第 3 段）');
    assert.equal(repairTask.assistantSourceRefLabel('material-99-quoted-article'), '被引用文章全文');
    const previewId = 'material-historical-key-quoted-article';
    const clipped = {id:previewId, status:'MATERIAL_PREVIEW', url:'https://x.com/glacier/status/20202'};
    vm.runInContext(section('    function renderAssistantResearch(', '    function renderAssistantEvidenceChanges('), repairTask);
    const clippedHtml = repairTask.renderAssistantResearch({materialEvidence:[clipped],
        claims:[{claim:'公开的节选',sourceIds:[previewId]}]});
    assert.match(clippedHtml,/被引用文章节选/);
    assert.doesNotMatch(clippedHtml,/文章全文/);

    // Period switch in normal mode reuses in-memory bundle for same user with 0 network requests
    const periodReuse = context();
    const periodUrls = [];
    const cooldownTicks = [];
    const deadline = new Date(Date.now() + 110000).toISOString();
    periodReuse.startCacheCooldownTicker = value => {cooldownTicks.push(value);};
    periodReuse.state.period = 'HOURS_24';
    periodReuse.state.days = 1;
    periodReuse.fetch = async url => {
        periodUrls.push(url);
        return {
            status: 200,
            ok: true,
            body: {
                hours24: {profile: {id: '1001', username: 'alice'}, periodCode: 'HOURS_24', nextRefreshAt: deadline},
                day: {profile: {id: '1001', username: 'alice'}, periodCode: 'HOURS_48', nextRefreshAt: deadline},
                week: {profile: {id: '1001', username: 'alice'}, periodCode: 'WEEK', nextRefreshAt: deadline},
                month: {profile: {id: '1001', username: 'alice'}, periodCode: 'MONTH', nextRefreshAt: deadline},
                coveredWindowDays: 30,
                hasMoreHistoryForTarget: false
            }
        };
    };
    await periodReuse.loadSelfReport(false);
    assert.equal(periodUrls.length, 1, 'initial load fetches bundle once');
    assert.match(periodUrls[0], /preferStored=true/, 'initial load requests preferStored=true for SWR fast open');
    assert.equal(periodReuse.state.report.periodCode, 'HOURS_24');
    periodReuse.state.period = 'WEEK';
    periodReuse.state.days = 7;
    await periodReuse.loadSelfReport(false);
    assert.equal(periodUrls.length, 1, 'switching period in normal mode reuses in-memory bundle without fetch');
    assert.equal(periodReuse.state.report.periodCode, 'WEEK');
    assert.equal(cooldownTicks[cooldownTicks.length - 1], deadline, 'viewing a cached period preserves its server deadline');
    await periodReuse.loadSelfReport({reloadFromServer: true});
    assert.equal(periodUrls.length, 2, 'reloadFromServer bypasses in-memory bundle');
    assert.doesNotMatch(periodUrls[1], /refresh=true/, 'reloadFromServer must not re-trigger force refresh');
    assert.doesNotMatch(periodUrls[1], /preferStored=true/, 'reloadFromServer must not send preferStored=true');

    periodReuse.state.bundle.WEEK.nextRefreshAt = new Date(Date.now() - 1000).toISOString();
    await periodReuse.loadSelfReport(false);
    assert.equal(periodUrls.length, 3, 'an expired in-memory period checks the server again on demand');

    // Background refresh RUNNING -> poll SUCCEEDED -> reloads latest server bundle without ReferenceError
    const bgPoll = context();
    const bgFetchUrls = [];
    const scheduledPolls = [];
    bgPoll.setTimeout = (fn, ms) => {
        if (ms === 2000) scheduledPolls.push(fn);
        return scheduledPolls.length;
    };
    bgPoll.state.period = 'HOURS_24';
    bgPoll.state.days = 1;
    let bundleLoads = 0;
    bgPoll.fetch = async url => {
        bgFetchUrls.push(url);
        if (url === '/api/analyze/bundle/refresh/status') {
            return {status: 200, ok: true, body: {state: 'SUCCEEDED', refreshId: 'ref-1'}};
        }
        bundleLoads++;
        const rev = bundleLoads === 1 ? 'stale' : 'fresh';
        const report = {profile: {id: '1001', username: 'alice'}, periodCode: 'HOURS_24', cacheRemainingSeconds: 90, rev};
        return {
            status: 200,
            ok: true,
            body: {
                hours24: report,
                day: report,
                week: report,
                month: report,
                backgroundRefreshStatus: bundleLoads === 1
                    ? {state: 'RUNNING', refreshId: 'ref-1'}
                    : {state: 'SUCCEEDED', refreshId: 'ref-1'},
                dailyCacheStatus: {
                    canContinue: true,
                    lastAttemptDegraded: false,
                    cycleStartedAt: '2026-11-22T10:00:00Z',
                    unattemptedMetricPosts: 10
                },
                coveredWindowDays: 30,
                hasMoreHistoryForTarget: false
            }
        };
    };
    await bgPoll.loadSelfReport(true);
    assert.equal(bgPoll.error, undefined, 'RUNNING background refresh must not throw ReferenceError');
    assert.equal(bgPoll.state.report.rev, 'stale');
    assert.equal(scheduledPolls.length, 1, 'RUNNING status schedules workspace refresh poll');
    await scheduledPolls[0]();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(bgPoll.error, undefined, 'SUCCEEDED poll must reload without ReferenceError');
    assert.equal(bgPoll.state.report.rev, 'fresh', 'SUCCEEDED poll reloads latest server bundle');
    assert.equal(bgFetchUrls.filter(u => u.startsWith('/api/analyze/bundle?')).length, 2);
    assert.doesNotMatch(bgFetchUrls[0], /preferStored=true/, 'forceRefresh must not send preferStored=true');
    assert.doesNotMatch(bgFetchUrls[bgFetchUrls.length - 1], /refresh=true/, 'post-SUCCEEDED reload must not send refresh=true');
    assert.doesNotMatch(bgFetchUrls[bgFetchUrls.length - 1], /preferStored=true/, 'post-SUCCEEDED reload must not send preferStored=true');
    assert.match(bgFetchUrls.at(-1), /[?&]stored=true(?:&|$)/, 'displaying a completed refresh uses the read-only endpoint');

    const fast = context();
    let fastPoll;
    const fastUrls = [];
    fast.setTimeout = (fn, ms) => { if (ms === 2000) fastPoll = fn; return 1; };
    fast.fetch = async url => {
        fastUrls.push(url);
        const status = {state: 'SUCCEEDED', refreshId: 'just-finished', cycleStartedAt: '2026-11-22T10:00:00Z'};
        if (url.endsWith('/refresh/status')) return {ok: true, status: 200, body: status};
        const result = response();
        const fresh = url.includes('stored=true');
        result.body.day.rev = fresh ? 'new' : 'old';
        result.body.hours24 = result.body.day;
        result.body.dailyCacheStatus = {cycleStartedAt: fresh ? status.cycleStartedAt : '2026-11-20T10:00:00Z', canContinue: false};
        if (!fresh) result.body.backgroundRefreshStatus = {...status, newerResultAvailable: true};
        return result;
    };
    await fast.loadSelfReport(false);
    assert.equal(fast.state.report.rev, 'old');
    assert.equal(typeof fastPoll, 'function');
    await fastPoll();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(fast.state.report.rev, 'new');
    assert.equal(fastUrls.filter(url => url.includes('stored=true')).length, 1);

    const manual = context();
    const manualPosts = [];
    manual.state.backgroundRefreshStatus = {state: 'SUCCEEDED', refreshId: 'previous'};
    manual.fetch = async (url, options = {}) => {
        if (options.method === 'POST') {
            manualPosts.push(url);
            return {status: 200, ok: true, body: {state: 'RUNNING', refreshId: 'new'}};
        }
        const res = response();
        res.body.dailyCacheStatus = {cycleStartedAt: '2026-11-14T12:00:00Z', canContinue: true, lastAttemptDegraded: false};
        res.body.backgroundRefreshStatus = {state: 'SUCCEEDED', refreshId: 'previous', cycleStartedAt: '2026-11-13T10:00:00Z'};
        return res;
    };
    await manual.loadSelfReport(true);
    assert.equal(manualPosts.length, 1, 'a successful job from yesterday cannot stop the current manual continuation');

    const olderPoll = context();
    let poll, finishPoll;
    olderPoll.setTimeout = (fn, ms) => { if (ms === 2000) poll = fn; return 1; };
    olderPoll.fetch = () => new Promise(resolve => { finishPoll = resolve; });
    olderPoll.state.backgroundRefreshStatus = {state: 'RUNNING', refreshId: 'old'};
    olderPoll.scheduleWorkspaceRefreshPoll('alice', 'HOURS_24', 1);
    const pendingPoll = poll();
    olderPoll.state.reportRequestGeneration++;
    olderPoll.state.backgroundRefreshStatus = {state: 'RUNNING', refreshId: 'current'};
    finishPoll({status: 200, ok: true, body: {state: 'PARTIAL', refreshId: 'old'}});
    await pendingPoll;
    assert.equal(olderPoll.state.backgroundRefreshStatus.refreshId, 'current');

    // Verified @elidlicom ownerAllPermissions unlocks all periods, schedule access, and 0ms interval immediately
    const ownerPerms = context();
    vm.runInContext(
        section('    function isOwnerAllPermissions(', '    function syncPeriodButtons(')
        + section('    const MIN_SCHEDULE_INTERVAL_MS =', '    function computeNextAvailableScheduleMs(')
        + section('    function hasScheduleAccessDecision(', '    function getScheduleLockedReason('),
        ownerPerms
    );
    ownerPerms.state.authStatus = {loggedIn: true, ownerAllPermissions: true, user: {id: '9000', username: 'elidlicom'}};
    ownerPerms.state.accessPolicy = null;
    ownerPerms.state.assistantAccess = null;
    ownerPerms.state.scheduleAccess = null;
    assert.equal(ownerPerms.isPeriodUnlocked('MONTH'), true, '@elidlicom unlocks 30d immediately before bundle finishes');
    assert.equal(ownerPerms.isScheduleEligible(), true, '@elidlicom unlocks scheduled tweets immediately');
    assert.equal(ownerPerms.getRequiredScheduleIntervalMs(), 0, '@elidlicom has 0ms schedule interval without opening Content tab');

    ownerPerms.state.authStatus = {loggedIn: true, ownerAllPermissions: false, user: {id: '9999', username: 'elidlicom'}};
    assert.equal(ownerPerms.isPeriodUnlocked('MONTH'), false, 'unverified handle spoof does not unlock periods');
    assert.equal(ownerPerms.getRequiredScheduleIntervalMs(), 3_600_000, 'unverified handle spoof keeps 1h schedule interval');
    ownerPerms.state.scheduleAccess = {allowed: true, authorInteractionRank: 1};
    ownerPerms.state.accessPolicy = {top5FullAccess: true, scheduledPublishAllowed: true};
    ownerPerms.state.assistantAccess = {allowed: true};
    assert.equal(ownerPerms.isScheduleEligible(), false, 'cached rank and assistant permissions cannot override the signed-in account');
    ownerPerms.state.authStatus = {loggedIn: true, user: {id: '9999', username: 'elidlicom'}};
    assert.equal(ownerPerms.isScheduleEligible(), false, 'a legacy response without verified ownership cannot enable scheduling');
    ownerPerms.state.authStatus = {loggedIn: false};
    assert.equal(ownerPerms.isScheduleEligible(), false, 'signed-out accounts have no scheduling entitlement');

    // SWR acceptance notice does not trigger caution state, and auto-opened syncStatusDetails closes once resolved
    const headerCtx = context();
    const headerNodes = new Map();
    const getHeaderNode = id => {
        if (!headerNodes.has(id)) {
            const classes = new Set();
            const listeners = new Map();
            headerNodes.set(id, {
                id, textContent: '', innerHTML: '', outerHTML: '', title: '', href: '', hidden: false, open: false,
                dataset: {},
                classList: {
                    add: c => classes.add(c),
                    remove: c => classes.delete(c),
                    toggle: (c, on) => on ? classes.add(c) : classes.delete(c),
                    contains: c => classes.has(c)
                },
                addEventListener: (ev, fn) => listeners.set(ev, fn),
                dispatchToggle: () => listeners.get('toggle')?.()
            });
        }
        return headerNodes.get(id);
    };
    headerCtx.$ = getHeaderNode;
    headerCtx.document.getElementById = getHeaderNode;
    headerCtx.window = {innerWidth: 390};
    headerCtx.AUTHOR_AVATAR_URL = '';
    headerCtx.AUTHOR_BANNER_URL = '';
    headerCtx.normalizePeriod = () => ({badgePrefix: '24h'});
    headerCtx.syncUsageHandleDisplay = noop;
    headerCtx.avatarHtml = () => '<div class="avatar-circle sm"></div><div class="avatar-circle xl"></div>';
    headerCtx.syncSettingsAccountMenu = noop;
    headerCtx.fmtClockTime = () => '15:07';
    headerCtx.applyCoverBanner = noop;
    headerCtx.fmtNum = v => String(v ?? 0);
    headerCtx.saveProfileHeaderCache = noop;
    headerCtx.scheduleMediaBatchWarm = noop;
    vm.runInContext(section('    function renderProfileHeader(', '    /* ==================== 模块 1：发帖与回复活跃度看板'), headerCtx);

    const baseReport = (bannerNotice) => ({
        provider: 'TWITTERAPI_IO',
        periodCode: 'HOURS_24',
        windowDays: 1,
        fetchedAt: '2026-11-23T07:07:00Z',
        bannerNotice,
        profile: {id: '1001', username: 'alice', displayName: 'Alice', verified: true, bio: 'dev', followingCount: 10, followersCount: 20, totalPostsCount: 30},
        activity: {coverageNote: '', windowRangeText: '11月22日 15:07 - 11月23日 15:07', personaTag: '高频互动深耕达人', activityHealthScore: 87, sampleCapped: false},
        followAudit: {followersSampleTruncated: false, followingsSampleTruncated: false, unknownFollowBackCount: 0, unknownFollowingCount: 0, followBackRatePct: 87.3, followBackRateComplete: true, notFollowingBackCount: 0},
        interactions: {peerStats: []}
    });

    headerCtx.state.pendingRelationshipChecks = 0;
    headerCtx.state.dailyCacheStatus = {timelineCovered: true, inboundCovered: true, pendingMetricPosts: 0, unattemptedMetricPosts: 0, canContinue: false, lastAttemptDegraded: false};
    headerCtx.renderProfileHeader(baseReport('已返回最近一次完整入库快照；后台刷新已受理。'));
    assert.equal(getHeaderNode('syncStatusSummary').textContent, '最近 24 小时已同步', 'SWR acceptance notice must not trigger 数据状态需留意');
    assert.equal(getHeaderNode('syncStatusDetails').open, false, 'SWR acceptance notice must not auto-expand details');

    // Degraded state auto-opens details; subsequent clean sync auto-closes it
    headerCtx.state.dailyCacheStatus = {timelineCovered: true, inboundCovered: true, pendingMetricPosts: 1, unattemptedMetricPosts: 1, canContinue: true, lastAttemptDegraded: true};
    headerCtx.renderProfileHeader(baseReport('本轮最近 24 小时数据或指标尚未补齐；继续补充可获取数据，未计为完整新鲜缓存。已入库 100 条动态、50 条互动；历史数据仍按原覆盖范围累计。'));
    assert.equal(getHeaderNode('syncStatusSummary').textContent, '数据状态需留意');
    assert.equal(getHeaderNode('syncStatusDetails').open, true);

    headerCtx.state.dailyCacheStatus = {timelineCovered: true, inboundCovered: true, pendingMetricPosts: 0, unattemptedMetricPosts: 0, canContinue: false, lastAttemptDegraded: false};
    headerCtx.renderProfileHeader(baseReport('最近 24 小时数据与指标已完成本轮同步；满 1 小时后再次检查更新。已入库 100 条动态、50 条互动；历史数据仍按原覆盖范围累计。'));
    assert.equal(getHeaderNode('syncStatusSummary').textContent, '最近 24 小时已同步');
    assert.equal(getHeaderNode('syncStatusDetails').open, false, 'auto-opened details must close when anomaly clears');

    // Settled partial cycle (e.g. search circuit 503 + paid budget cap reached) stays calm & collapsed instead of yellow 数据状态需留意
    headerCtx.state.dailyCacheStatus = {timelineCovered: true, inboundCovered: false, pendingMetricPosts: 0, unattemptedMetricPosts: 0, canContinue: false, lastAttemptDegraded: false};
    const settledPartialReport = baseReport('本轮可请求的数据已尝试完毕，仍有未确认项；保留原值和缺失状态，下一小时再检查，不重复请求本轮失败项。已入库 5889 条动态、2537 条互动；历史数据仍按原覆盖范围累计。');
    settledPartialReport.activity.sampleCapped = true;
    settledPartialReport.activity.coverageNote = '实际数据覆盖约 18 小时';
    headerCtx.renderProfileHeader(settledPartialReport);
    assert.equal(getHeaderNode('syncStatusSummary').textContent, '部分数据待补齐');
    assert.equal(getHeaderNode('syncStatusDetails').open, false);
    assert.equal(getHeaderNode('syncStatusDetails').classList.contains('is-attention'), false);

    console.log('auth-state-regression: passed');
})().catch(err => {console.error(err); process.exitCode = 1;});
