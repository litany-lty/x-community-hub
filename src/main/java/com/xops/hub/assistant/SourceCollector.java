package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.PaidCapabilityGate;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.TwitterApiIoProvider.TextPost;
import com.xops.hub.provider.TwitterApiIoProvider.TextPostPage;
import com.xops.hub.provider.TwitterApiIoProvider.TextInteraction;
import com.xops.hub.provider.TwitterApiIoProvider.TextInteractionPage;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityHubService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class SourceCollector {
    public static final Set<Integer> SUPPORTED_TIERS = Set.of(1, 5, 30);
    private static final Pattern HANDLE = Pattern.compile("^[A-Za-z0-9_]{1,15}$");
    private static final int STYLE_SAMPLE_LIMIT = 100;
    private static final int STYLE_PAGE_BATCH = 20;
    private static final int MAX_SOURCES_PER_TICK = 5;
    private static final int MAX_GAP_ENTRIES = 20;
    private static final long MAX_JITTER_MS = 45_000L;
    static final String TIMELINE_PROVIDER = "TWITTERAPI_IO";
    static final String TIMELINE_ENDPOINT = "/twitter/user/last_tweets";
    static final String INBOUND_ENDPOINT = "/twitter/user/mentions";
    static final long SCAN_OVERLAP_MS = 30 * 60 * 1000L;
    private static final long HISTORY_INCREMENTAL_INTERVAL_MS = 5 * 60 * 1000L;
    static final long HISTORY_FAILURE_BACKOFF_MS = com.xops.hub.provider.PaidCapabilityGate.RETRY_INTERVAL_MS;
    static final long BUDGET_BACKOFF_MS = 10 * 60 * 1000L;

    private final AssistantStore store;
    private final TwitterApiIoProvider provider;
    private final CommunityHubService communityHubService;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "content-source-collector");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean tickRunning = new AtomicBoolean();
    private final AtomicBoolean styleRunning = new AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaidCapabilityGate paidCapabilityGate;

    @org.springframework.beans.factory.annotation.Autowired
    public SourceCollector(AssistantStore store, TwitterApiIoProvider provider,
            CommunityHubService communityHubService, ObjectMapper mapper) {
        this(store, provider, communityHubService, mapper, Clock.systemUTC());
    }

    SourceCollector(AssistantStore store, TwitterApiIoProvider provider,
            CommunityHubService communityHubService, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.provider = provider;
        this.communityHubService = communityHubService;
        this.mapper = mapper;
        this.clock = clock;
    }

    @FunctionalInterface
    interface IngestHook {
        void onMaterialIngested(ObjectNode source, ObjectNode material);
    }

    private volatile IngestHook ingestHook;

    void setIngestHook(IngestHook ingestHook) {
        this.ingestHook = ingestHook;
    }

    static int validateTierMinutes(int minutes) {
        if (!SUPPORTED_TIERS.contains(minutes)) {
            throw new IllegalArgumentException("采集档位仅支持 1、5、30 分钟");
        }
        return minutes;
    }

    static int resolveTierMinutes(ObjectNode source, int fallbackMinutes) {
        int raw = source == null ? 0 : source.path("intervalMinutes").asInt(0);
        if (SUPPORTED_TIERS.contains(raw)) return raw;
        if (SUPPORTED_TIERS.contains(fallbackMinutes)) return fallbackMinutes;
        return fallbackMinutes <= 1 ? 1 : (fallbackMinutes >= 30 ? 30 : 5);
    }

    public List<ObjectNode> list() {
        long now = clock.millis();
        return store.tx(tx -> {
            int defaultMinutes = AssistantWorkflow.settings(tx).path("intervalMinutes").asInt(5);
            return tx.all("source").stream()
                    .filter(source -> !"REMOVED".equals(source.path("status").asText()))
                    .sorted(Comparator.comparingInt((ObjectNode n) -> resolveTierMinutes(n, defaultMinutes))
                            .thenComparing(node -> node.path("username").asText("")))
                    .map(node -> {
                        ObjectNode copy = node.deepCopy();
                        int tierMinutes = resolveTierMinutes(copy, defaultMinutes);
                        long interval = tierMinutes * 60_000L;
                        copy.put("intervalMinutes", tierMinutes)
                                .put("displayName", copy.path("displayName").asText(copy.path("username").asText("")))
                                .put("avatarUrl", copy.path("avatarUrl").asText(""))
                                .put("verified", copy.path("verified").asBoolean(false))
                                .put("autoRepostEnabled", copy.path("autoRepostEnabled").asBoolean(false))
                                .put("autoRepostTextMode", copy.path("autoRepostTextMode").asText("AI"))
                                .put("autoRepostTemplate", copy.path("autoRepostTemplate").asText(""))
                                .put("autoRepostMinScore", copy.path("autoRepostMinScore").asInt(82))
                                .put("autoRepostRuleHint", copy.path("autoRepostRuleHint").asText(""));
                        if ("ACTIVE".equals(copy.path("status").asText())) {
                            long anchor = copy.path("lastCompleteAt").asLong(0) > 0
                                    ? copy.path("lastCompleteAt").asLong() : copy.path("enabledAt").asLong(now);
                            long lagMs = Math.max(0L, now - anchor);
                            long maxLag = tierMinutes <= 1 ? 150_000L : interval * 2 + MAX_JITTER_MS;
                            copy.put("lagMs", lagMs).put("lagging", lagMs > maxLag);
                        } else {
                            copy.put("lagMs", 0L).put("lagging", false);
                        }
                        return copy;
                    }).toList();
        });
    }

    public ObjectNode add(String input) {
        return add(input, 5);
    }

    public ObjectNode add(String input, int intervalMinutes) {
        int tierMinutes = validateTierMinutes(intervalMinutes);
        XUserSummary author = provider.resolvePublicAuthor(parseUsername(input));
        long now = clock.millis();
        long cutoff = AssistantRetention.cutoffMillis(now);
        return store.tx(tx -> {
            ObjectNode existing = tx.get("source", author.id());
            boolean readded = existing != null && "REMOVED".equals(existing.path("status").asText());
            if (existing != null && !readded) {
                if (existing.path("intervalMinutes").asInt(5) != tierMinutes
                        || existing.path("avatarUrl").asText("").isBlank()) {
                    ObjectNode updated = existing.deepCopy()
                            .put("displayName", value(author.displayName()))
                            .put("avatarUrl", value(author.avatarUrl()))
                            .put("verified", author.verified())
                            .put("intervalMinutes", tierMinutes);
                    return tx.put("source", author.id(), updated);
                }
                return existing;
            }
            ObjectNode source = existing != null ? existing.deepCopy() : mapper.createObjectNode();
            source.put("authorId", author.id()).put("username", author.username())
                    .put("displayName", value(author.displayName()))
                    .put("avatarUrl", value(author.avatarUrl()))
                    .put("verified", author.verified())
                    .put("intervalMinutes", tierMinutes)
                    .put("status", "ACTIVE")
                    .put("instanceId", UUID.randomUUID().toString()).put("enabledAt", now)
                    .put("cursor", "").put("cursorProvider", TIMELINE_PROVIDER).put("cursorEndpoint", TIMELINE_ENDPOINT)
                    .put("cursorFailures", 0).put("jitterMs", sourceJitterMs(author.id()))
                    .put("scanSince", cutoff)
                    .put("lastSuccessAt", 0).put("lastCompleteAt", 0)
                    .put("error", "");
            if (!source.has("autoRepostEnabled")) source.put("autoRepostEnabled", false);
            if (!source.has("autoRepostTextMode")) source.put("autoRepostTextMode", "AI");
            if (!source.has("autoRepostTemplate")) source.put("autoRepostTemplate", "");
            if (!source.has("autoRepostMinScore")) source.put("autoRepostMinScore", 82);
            if (!source.has("autoRepostRuleHint")) source.put("autoRepostRuleHint", "");
            source.remove("anchorSince");
            return tx.put("source", author.id(), source);
        });
    }

    public ObjectNode updateConfig(String id, ObjectNode body) {
        if (body == null) throw new IllegalArgumentException("配置不能为空");
        long now = clock.millis();
        return store.tx(tx -> {
            ObjectNode current = tx.get("source", id);
            if (current == null || "REMOVED".equals(current.path("status").asText())) {
                throw new IllegalArgumentException("监听作者不存在: " + id);
            }
            ObjectNode next = current.deepCopy();
            if (body.has("intervalMinutes")) {
                if (!body.path("intervalMinutes").isIntegralNumber()) {
                    throw new IllegalArgumentException("intervalMinutes 必须是整数");
                }
                next.put("intervalMinutes", validateTierMinutes(body.path("intervalMinutes").asInt()));
            }
            if (body.has("autoRepostEnabled")) {
                if (!body.path("autoRepostEnabled").isBoolean()) {
                    throw new IllegalArgumentException("autoRepostEnabled 必须是布尔值");
                }
                boolean enabled = body.path("autoRepostEnabled").asBoolean();
                boolean wasEnabled = current.path("autoRepostEnabled").asBoolean(false);
                next.put("autoRepostEnabled", enabled);
                if (enabled && !wasEnabled) {
                    next.put("autoRepostEnabledAt", now);
                }
            }
            if (body.has("autoRepostTextMode")) {
                String mode = body.path("autoRepostTextMode").asText("").strip().toUpperCase(Locale.ROOT);
                if (!Set.of("AI", "TEMPLATE").contains(mode)) {
                    throw new IllegalArgumentException("附带文字模式仅支持 AI 或 TEMPLATE");
                }
                next.put("autoRepostTextMode", mode);
            }
            if (body.has("autoRepostTemplate")) {
                String tpl = body.path("autoRepostTemplate").asText("").strip();
                if (tpl.length() > 240) {
                    throw new IllegalArgumentException("预设转发文案不能超过 240 字");
                }
                next.put("autoRepostTemplate", tpl);
            }
            if (body.has("autoRepostMinScore")) {
                int minScore = body.path("autoRepostMinScore").asInt(-1);
                if (!body.path("autoRepostMinScore").isIntegralNumber() || minScore < 60 || minScore > 99) {
                    throw new IllegalArgumentException("热度阈值范围为 60–99");
                }
                next.put("autoRepostMinScore", minScore);
            }
            if (body.has("autoRepostRuleHint")) {
                String hint = body.path("autoRepostRuleHint").asText("").strip();
                if (hint.length() > 200) {
                    throw new IllegalArgumentException("自定义判定补充条件不能超过 200 字");
                }
                next.put("autoRepostRuleHint", hint);
            }
            if (next.path("autoRepostEnabled").asBoolean(false)
                    && "TEMPLATE".equals(next.path("autoRepostTextMode").asText("AI"))
                    && next.path("autoRepostTemplate").asText("").isBlank()) {
                throw new IllegalArgumentException("选择预设固定文案模式时，请填写预设转发文字");
            }
            return tx.put("source", id, next);
        });
    }

    public ObjectNode change(String id, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("pause", "resume", "remove").contains(normalized))
            throw new IllegalArgumentException("Unsupported source action: " + action);
        return store.tx(tx -> {
            ObjectNode current = tx.get("source", id);
            if (current == null) throw new IllegalArgumentException("Unknown source: " + id);
            ObjectNode next = current.deepCopy();
            next.put("status", switch (normalized) {
                case "pause" -> "PAUSED";
                case "remove" -> "REMOVED";
                default -> "ACTIVE";
            });
            if ("resume".equals(normalized)) {
                long now = clock.millis();
                long cutoff = AssistantRetention.cutoffMillis(now);
                long resumeAnchor = next.path("lastCompleteAt").asLong(0) > 0
                        ? Math.max(cutoff, next.path("lastCompleteAt").asLong() - SCAN_OVERLAP_MS)
                        : cutoff;
                next.put("cursor", "").put("cursorProvider", TIMELINE_PROVIDER).put("cursorEndpoint", TIMELINE_ENDPOINT)
                        .put("cursorFailures", 0).put("scanSince", resumeAnchor).put("error", "");
                next.remove("anchorSince");
            }
            return tx.put("source", id, next);
        });
    }

    public ObjectNode styleSamples() {
        return store.tx(tx -> {
            List<ObjectNode> samples = tx.matching("style_sample", "textRole", "AUTHOR", -1).stream()
                    .sorted(Comparator.comparingLong((ObjectNode n) -> n.path("publishedAt").asLong()).reversed())
                    .limit(STYLE_SAMPLE_LIMIT).toList();
            ObjectNode status = tx.get("meta", "style_status");
            ObjectNode result = mapper.createObjectNode();
            ArrayNode rows = result.putArray("samples");
            samples.forEach(rows::add);
            ObjectNode coverage = result.putObject("coverage");
            boolean current = status != null && status.path("authorTextOnly").asBoolean();
            boolean exhausted = current && status.path("exhausted").asBoolean();
            boolean running = styleRunning.get();
            String error = current ? status.path("error").asText("") : "";
            String state = running ? "COLLECTING" : samples.size() >= STYLE_SAMPLE_LIMIT ? "COMPLETE"
                    : !error.isBlank() ? "LIMITED" : exhausted ? "EXHAUSTED" : "PENDING";
            coverage.put("collected", samples.size()).put("target", STYLE_SAMPLE_LIMIT)
                    .put("exhausted", exhausted).put("running", running).put("state", state)
                    .put("lastSuccessAt", current ? status.path("lastSuccessAt").asLong(0) : 0)
                    .put("error", error);
            return result;
        });
    }

    public ObjectNode refreshStyleSamples() {
        if (styleRunning.compareAndSet(false, true)) worker.submit(() -> {
            try { collectStyleSamples(); } finally { styleRunning.set(false); }
        });
        return styleSamples();
    }

    @Scheduled(cron = "${xops.content-assistant-style-cron:0 35 3 * * *}", zone = "${xops.timezone:Asia/Shanghai}")
    public void scheduledStyleRefresh() {
        ObjectNode settings = store.tx(AssistantWorkflow::settings);
        if (hasBoundOwner() && settings.path("enabled").asBoolean(false)
                && settings.path("collectionEnabled").asBoolean(false)) refreshStyleSamples();
    }

    public ObjectNode history() {
        return store.tx(tx -> {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode sources = result.putArray("sources");
            List<ObjectNode> ownerSources = tx.all("history_source").stream()
                    .filter(n -> n.path("owner").asBoolean(false)
                            || PaidCapabilityGate.isPrivilegedUsername(n.path("username").asText("")))
                    .sorted(Comparator.comparingInt(n -> n.path("rank").asInt(Integer.MAX_VALUE)))
                    .toList();
            ownerSources.forEach(sources::add);
            result.put("materials", tx.count("history_material"));
            result.put("interactions", tx.count("history_interaction"));
            List<ObjectNode> eligible = ownerSources.stream()
                    .filter(n -> n.path("eligible").asBoolean(true)).toList();
            result.put("complete", !eligible.isEmpty()
                    && eligible.stream().allMatch(n -> n.path("timelineExhausted").asBoolean(false)
                            && n.path("inboundExhausted").asBoolean(false)
                            && n.path("timelineError").asText("").isBlank()
                            && n.path("inboundError").asText("").isBlank()));
            return result;
        });
    }

    @Scheduled(fixedDelayString = "${xops.content-assistant-source-tick-ms:15000}")
    public void scheduledTick() {
        if (!tickRunning.compareAndSet(false, true)) return;
        worker.submit(() -> { try { tick(); } finally { tickRunning.set(false); } });
    }

    public List<String> purgeExpired() {
        return store.purgeExpiredContent(AssistantRetention.cutoffMillis(clock.millis()));
    }

    void tick() {
        ObjectNode ownerNode = store.tx(tx -> tx.get("meta", "owner"));
        String ownerAccountId = ownerNode == null ? "" : ownerNode.path("accountId").asText("");
        com.xops.hub.provider.PaidCapabilityGate.runWithCaller(
                com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerAccountId, this::tickInternal);
    }

    private void tickInternal() {
        purgeExpired();
        if (!store.hasCollectionSpace()) return;
        ObjectNode settings = store.tx(AssistantWorkflow::settings);
        boolean assistantCollectionActive = hasBoundOwner()
                && settings.path("enabled").asBoolean(false)
                && settings.path("collectionEnabled").asBoolean(false);
        boolean paidBudgetExhausted = provider.isPageReadBudgetExhausted();
        boolean freeTimelineAvailable = provider.canReadTimelineForFree("");
        if (paidBudgetExhausted && !freeTimelineAvailable) {
            reconcileMisclassifiedBudgetErrors(assistantCollectionActive);
            return;
        }
        if (assistantCollectionActive) {
            int defaultMinutes = settings.path("intervalMinutes").asInt(5);
            long now = clock.millis();
            List<ObjectNode> dueSources = store.tx(tx -> tx.all("source").stream()
                    .filter(s -> "ACTIVE".equals(s.path("status").asText()))
                    .filter(s -> sourceDue(s, now, resolveTierMinutes(s, defaultMinutes) * 60_000L, freeTimelineAvailable))
                    .sorted(Comparator.comparingInt((ObjectNode s) -> resolveTierMinutes(s, defaultMinutes))
                            .thenComparingLong(s -> s.path("lastAttemptAt").asLong(0)))
                    .limit(MAX_SOURCES_PER_TICK)
                    .map(ObjectNode::deepCopy).toList());
            for (ObjectNode due : dueSources) {
                if (!store.hasCollectionSpace()) break;
                if (!freeTimelineAvailable && provider.isPageReadBudgetExhausted()) break;
                collectSourcePage(due);
            }
            boolean publicationChanged = store.tx(tx -> {
                var status = tx.get("meta", "style_status");
                if (status == null || status.path("refreshRequestedAt").asLong() <= status.path("refreshAcknowledgedAt").asLong())
                    return false;
                tx.put("meta", "style_status", status.put("refreshAcknowledgedAt",status.path("refreshRequestedAt").asLong()));
                return true;
            });
            boolean styleHasNextBatch = store.tx(tx -> {
                var status = tx.get("meta", "style_status");
                if (status == null || !status.path("continueBatch").asBoolean()) return false;
                String err = status.path("error").asText("");
                return err.isBlank() || (freeTimelineAvailable && err.contains("外部调用预算已达保护上限"));
            });
            if ((publicationChanged || styleHasNextBatch) && (freeTimelineAvailable || !provider.isPageReadBudgetExhausted())) {
                refreshStyleSamples();
            }
        }
        if ((assistantCollectionActive || communityHubService.isSilentHistoryEnabled())
                && !communityHubService.hasActiveForegroundWork()
                && (freeTimelineAvailable || !provider.isBackgroundPaidBudgetExhausted())) {
            synchronizeHistoryCandidates();
            if (!communityHubService.hasActiveForegroundWork()
                    && (freeTimelineAvailable || !provider.isBackgroundPaidBudgetExhausted())) {
                collectOneHistoryPage();
            }
        }
    }

    private void reconcileMisclassifiedBudgetErrors(boolean assistantCollectionActive) {
        store.tx(tx -> {
            if (assistantCollectionActive) {
                for (ObjectNode src : tx.all("source")) {
                    if ("ACTIVE".equals(src.path("status").asText())
                            && "读取超时，已保留当前进度".equals(src.path("error").asText(""))) {
                        tx.put("source", src.path("id").asText(),
                                src.deepCopy().put("error", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE));
                    }
                }
                ObjectNode style = tx.get("meta", "style_status");
                if (style != null && "读取超时，已保留当前进度".equals(style.path("error").asText(""))) {
                    tx.put("meta", "style_status",
                            style.deepCopy().put("error", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE));
                }
            }
            for (ObjectNode hs : tx.all("history_source")) {
                boolean tlMisclassified = "读取超时，已保留当前进度".equals(hs.path("timelineError").asText(""));
                boolean inMisclassified = "读取超时，已保留当前进度".equals(hs.path("inboundError").asText(""));
                if (tlMisclassified || inMisclassified) {
                    ObjectNode next = hs.deepCopy();
                    if (tlMisclassified) next.put("timelineError", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
                    if (inMisclassified) next.put("inboundError", UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
                    tx.put("history_source", hs.path("id").asText(), next);
                }
            }
            return null;
        });
    }

    static boolean sourceDue(ObjectNode source, long now, long intervalMs) {
        return sourceDue(source, now, intervalMs, false);
    }

    static boolean sourceDue(ObjectNode source, long now, long intervalMs, boolean freeTimelineAvailable) {
        long lastAttempt = source.path("lastAttemptAt").asLong(0);
        if (lastAttempt <= 0) return true;
        String activeCursor = boundCursor(source, "cursor", TIMELINE_PROVIDER, TIMELINE_ENDPOINT);
        String error = source.path("error").asText("");
        if (!activeCursor.isBlank() && error.isBlank()) {
            return now - lastAttempt >= 5_000L;
        }
        long configuredJitter = source.path("jitterMs").asLong(sourceJitterMs(source.path("id").asText("")));
        long jitter = intervalMs <= 60_000L
                ? 0L
                : Math.min(Math.max(0L, configuredJitter), Math.min(MAX_JITTER_MS, intervalMs / 5));
        if (error.contains("外部调用预算已达保护上限") || error.contains("预算记录暂不可用")) {
            if (freeTimelineAvailable) return true;
            return now - lastAttempt >= Math.max(intervalMs, BUDGET_BACKOFF_MS) + jitter;
        }
        if (error.contains("超时")) {
            return now - lastAttempt >= Math.min(intervalMs, 90_000L) + Math.min(15_000L, jitter);
        }
        return now - lastAttempt >= intervalMs + jitter;
    }

    static long sourceJitterMs(String sourceId) {
        return sourceId == null || sourceId.isBlank() ? 0L : Math.floorMod(sourceId.hashCode(), 46) * 1000L;
    }

    static String boundCursor(ObjectNode record, String field, String expectedProvider, String expectedEndpoint) {
        if (record == null) return "";
        String raw = record.path(field).asText("");
        if (raw.isBlank()) return "";
        String provider = record.path(field + "Provider").asText(expectedProvider);
        String endpoint = record.path(field + "Endpoint").asText(expectedEndpoint);
        return expectedProvider.equals(provider) && expectedEndpoint.equals(endpoint) ? raw : "";
    }

    static boolean shouldResetExpiredCursor(Exception error, int consecutiveFailures) {
        if (UpstreamBudgetGuard.isBudgetReached(error)) {
            return false;
        }
        if (error instanceof XDataProviderException xpe) {
            int code = xpe.getStatusCode();
            String msg = xpe.getMessage() == null ? "" : xpe.getMessage();
            if (code == 400 || code == 404 || code == 410 || code == 422 || (code == 502 && msg.contains("游标"))) {
                return true;
            }
            if (code == 429 || code == 503 || code == 504) {
                return consecutiveFailures >= 3;
            }
        }
        return consecutiveFailures >= 2;
    }

    private static void appendCoverageGap(ObjectNode target, long fromAt, long toAt, String reason) {
        ArrayNode gaps = target.withArray("gaps");
        while (gaps.size() >= MAX_GAP_ENTRIES) gaps.remove(0);
        gaps.addObject().put("fromAt", fromAt).put("toAt", toAt)
                .put("recordedAt", System.currentTimeMillis()).put("reason", reason);
    }

    private void collectSourcePage(ObjectNode source) {
        String id = source.path("id").asText();
        String storedRawCursor = source.path("cursor").asText("");
        String cursor = boundCursor(source, "cursor", TIMELINE_PROVIDER, TIMELINE_ENDPOINT);
        if (!cursor.isBlank() && !cursor.startsWith("fx:")
                && provider.isPageReadBudgetExhausted() && provider.canReadTimelineForFree("")) {
            cursor = "";
        }
        String instanceId = source.path("instanceId").asText("");
        long attemptAt = clock.millis();
        long cutoff = AssistantRetention.cutoffMillis(attemptAt);
        long enabledAt = source.path("enabledAt").asLong(attemptAt);
        long scanStartedAt = cursor.isBlank()
                ? attemptAt : source.path("scanStartedAt").asLong(attemptAt);
        long lastCompleteAt = source.path("lastCompleteAt").asLong(0);
        long rawFallback = source.path("anchorSince").asLong(0) > 0
                ? source.path("anchorSince").asLong()
                : (lastCompleteAt > 0 ? listenerScanSince(Math.min(enabledAt, cutoff), lastCompleteAt) : cutoff);
        long scanSince = Math.max(cutoff, cursor.isBlank()
                ? rawFallback
                : source.path("scanSince").asLong(cutoff));
        if (!markSourceAttempt(id, instanceId, storedRawCursor, cursor, attemptAt, scanStartedAt, scanSince)) return;
        try {
            TextPostPage page = provider.fetchTextPostsPage(source.path("username").asText(), id, cursor, 100,
                    clock.instant().plusSeconds(45));
            List<TextPost> accepted = page.posts().stream()
                    .filter(post -> post.publishedAt().toEpochMilli() >= scanSince).toList();
            boolean crossedBoundary = crossedListenerBoundary(page.posts(), scanSince);
            commitPage("source", id, instanceId, cursor, page, accepted, scanSince, clock.millis(),
                    scanStartedAt, page.exhausted() || crossedBoundary);
            if (paidCapabilityGate != null) {
                paidCapabilityGate.markSucceeded("free-source:" + id + ":" + cursor);
            }
        } catch (Exception ex) {
            markSourceFailure(id, instanceId, cursor, scanSince, scanStartedAt, ex);
            if (paidCapabilityGate != null) {
                paidCapabilityGate.scheduleIdempotentRetry(
                        "free-source:" + id + ":" + cursor,
                        "fxtwitter_free_source",
                        PaidCapabilityGate.PRIVILEGED_USERNAME,
                        id,
                        safeError(ex),
                        this::tick
                );
            }
        }
    }

    private void synchronizeHistoryCandidates() {
        List<CommunityHubService.LongHistoryCandidate> candidates = new ArrayList<>(
                communityHubService.longHistoryCandidates().stream()
                        .filter(c -> c != null && c.owner() && c.user() != null
                                && PaidCapabilityGate.isPrivilegedUsername(c.user().username()))
                        .toList()
        );
        ObjectNode owner = store.tx(tx -> tx.get("meta", "owner"));
        if (owner != null && !owner.path("accountId").asText("").isBlank()
                && PaidCapabilityGate.isPrivilegedUsername(owner.path("username").asText(""))
                && candidates.stream().noneMatch(candidate -> owner.path("accountId").asText().equals(candidate.user().id()))) {
            XUserSummary ownerUser = new XUserSummary(owner.path("accountId").asText(),
                    owner.path("username").asText(), owner.path("username").asText(), "", "",
                    false, 0, 0, 0, "");
            candidates.add(0, new CommunityHubService.LongHistoryCandidate(ownerUser, 0, true, 0, true, 0));
        }
        store.tx(tx -> {
            if (tx.get("meta", "owner") == null && !candidates.isEmpty()) {
                candidates.stream().filter(CommunityHubService.LongHistoryCandidate::owner).findFirst()
                        .ifPresent(ownerCand -> tx.put("meta", "owner", mapper.createObjectNode()
                                .put("accountId", ownerCand.user().id())
                                .put("username", ownerCand.user().username())));
            }
            Set<String> eligible = new HashSet<>();
            for (CommunityHubService.LongHistoryCandidate candidate : candidates) {
                XUserSummary user = candidate.user();
                eligible.add(user.id());
                ObjectNode current = tx.get("history_source", user.id());
                ObjectNode next = current != null ? current.deepCopy() : mapper.createObjectNode();
                String prevTlErr = current != null ? current.path("timelineError").asText("") : "";
                String prevInErr = current != null ? current.path("inboundError").asText("") : "";
                boolean clearTlBudgetErr = prevTlErr.contains("外部调用预算已达保护上限") || prevTlErr.contains("预算记录暂不可用");
                boolean clearInBudgetErr = prevInErr.contains("外部调用预算已达保护上限") || prevInErr.contains("预算记录暂不可用");
                if (clearTlBudgetErr) next.put("timelineError", "");
                if (clearInBudgetErr) next.put("inboundError", "");
                next.put("authorId", user.id()).put("username", user.username()).put("rank", candidate.rank())
                        .put("owner", candidate.owner()).put("eligible", true)
                        .put("qualificationWindowDays", candidate.qualificationWindowDays())
                        .put("qualificationProvisional", candidate.provisional())
                        .put("qualificationAt", candidate.qualificationAt())
                        .put("timelineCursor", current != null ? current.path("timelineCursor").asText("") : "")
                        .put("inboundCursor", current != null ? current.path("inboundCursor").asText("") : "")
                        .put("timelineExhausted", current != null && current.path("timelineExhausted").asBoolean(false))
                        .put("inboundExhausted", current != null && current.path("inboundExhausted").asBoolean(false))
                        .put("timelineBackfillComplete", current != null && (current.path("timelineBackfillComplete").asBoolean(false)
                                || current.path("timelineExhausted").asBoolean(false)))
                        .put("inboundBackfillComplete", current != null && (current.path("inboundBackfillComplete").asBoolean(false)
                                || current.path("inboundExhausted").asBoolean(false)))
                        .put("nextDimension", current != null ? current.path("nextDimension").asText("TIMELINE") : "TIMELINE")
                        .put("error", current != null ? current.path("error").asText("") : "");
                boolean changed = current == null
                        || clearTlBudgetErr || clearInBudgetErr
                        || !current.has("timelineBackfillComplete") || !current.has("inboundBackfillComplete")
                        || !user.username().equals(current.path("username").asText())
                        || candidate.rank() != current.path("rank").asInt()
                        || candidate.owner() != current.path("owner").asBoolean()
                        || !current.path("eligible").asBoolean(false)
                        || candidate.qualificationWindowDays() != current.path("qualificationWindowDays").asInt()
                        || candidate.provisional() != current.path("qualificationProvisional").asBoolean()
                        || candidate.qualificationAt() != current.path("qualificationAt").asLong();
                if (changed) tx.put("history_source", user.id(), next);
            }
            for (ObjectNode source : tx.all("history_source")) {
                if (!eligible.contains(source.path("id").asText())
                        && (source.path("eligible").asBoolean(false)
                        || !source.path("timelineCursor").asText("").isBlank()
                        || !source.path("inboundCursor").asText("").isBlank())) {
                    ObjectNode next = source.deepCopy()
                            .put("eligible", false)
                            .put("timelineCursor", "")
                            .put("inboundCursor", "")
                            .put("timelineError", "")
                            .put("inboundError", "")
                            .put("error", "");
                    tx.put("history_source", source.path("id").asText(), next);
                }
            }
            return null;
        });
    }

    private void collectOneHistoryPage() {
        long now = System.currentTimeMillis();
        boolean allowFreeTimeline = provider.canReadTimelineForFree("");
        boolean allowPaidBackground = !provider.isBackgroundPaidBudgetExhausted();
        ObjectNode due = store.tx(tx -> tx.all("history_source").stream()
                .filter(s -> s.path("eligible").asBoolean(false)
                        && (((allowFreeTimeline || allowPaidBackground) && historyDimensionDue(s, "timeline", now, allowFreeTimeline))
                            || (allowPaidBackground && historyDimensionDue(s, "inbound", now, false))))
                .min(Comparator.comparingLong(s -> s.path("lastAttemptAt").asLong(0)))
                .map(ObjectNode::deepCopy).orElse(null));
        if (due == null) return;
        String id = due.path("id").asText();
        long attemptAt = now;
        boolean timelineDue = (allowFreeTimeline || allowPaidBackground) && historyDimensionDue(due, "timeline", now, allowFreeTimeline);
        boolean inboundDue = allowPaidBackground && historyDimensionDue(due, "inbound", now, false);
        boolean timeline = timelineDue && (!inboundDue || "TIMELINE".equals(due.path("nextDimension").asText("TIMELINE")));
        HistoryScan scan = null;
        try {
            if (timeline) {
                scan = prepareHistoryScan(id, "timeline", TIMELINE_ENDPOINT, due, attemptAt);
                long minPublished = scan.since();
                TextPostPage page = provider.fetchHistoryPostsPage(due.path("username").asText(), id,
                        scan.cursor(), 100, Instant.now().plusSeconds(30));
                List<TextPost> accepted = scan.backfill() ? page.posts() : page.posts().stream()
                        .filter(post -> post.publishedAt().toEpochMilli() >= minPublished).toList();
                boolean crossed = !scan.backfill() && crossedListenerBoundary(page.posts(), minPublished);
                commitHistoryTimeline(id, page, accepted, scan, attemptAt, page.exhausted() || crossed);
                if (paidCapabilityGate != null) {
                    paidCapabilityGate.markSucceeded("history-source:" + id + ":timeline:" + scan.cursor());
                }
            } else {
                scan = prepareHistoryScan(id, "inbound", INBOUND_ENDPOINT, due, attemptAt);
                long minPublished = scan.since();
                TextInteractionPage page = provider.fetchInboundTextPage(due.path("username").asText(), id,
                        scan.cursor(), 100, Instant.now().plusSeconds(30));
                List<TextInteraction> accepted = scan.backfill() ? page.interactions() : page.interactions().stream()
                        .filter(interaction -> interaction.publishedAt().toEpochMilli() >= minPublished).toList();
                boolean crossed = !scan.backfill() && page.interactions().stream()
                        .anyMatch(interaction -> interaction.publishedAt().toEpochMilli() < minPublished);
                commitHistoryInbound(id, page, accepted, scan, attemptAt, page.exhausted() || crossed);
                if (paidCapabilityGate != null) {
                    paidCapabilityGate.markSucceeded("history-source:" + id + ":inbound:" + scan.cursor());
                }
            }
        } catch (Exception ex) {
            String dimension = timeline ? "timeline" : "inbound";
            HistoryScan failedScan = scan;
            if (paidCapabilityGate != null) {
                String retryCursor = failedScan == null ? "" : failedScan.cursor();
                paidCapabilityGate.scheduleIdempotentRetry(
                        "history-source:" + id + ":" + dimension + ":" + retryCursor,
                        "twitterapi_io_read",
                        PaidCapabilityGate.PRIVILEGED_USERNAME,
                        id,
                        safeError(ex),
                        this::tick
                );
            }
            store.tx(tx -> {
                ObjectNode source = tx.get("history_source", id);
                if (source != null) {
                    ObjectNode next = source.deepCopy()
                            .put(dimension + "Error", safeError(ex))
                            .put("nextDimension", timeline ? "INBOUND" : "TIMELINE");
                    if (failedScan != null && !failedScan.cursor().isBlank() && !UpstreamBudgetGuard.isBudgetReached(ex)) {
                        int failures = next.path(dimension + "CursorFailures").asInt(0) + 1;
                        if (shouldResetExpiredCursor(ex, failures)) {
                            appendCoverageGap(next, failedScan.since(), failedScan.startedAt(), dimension + ":" + safeError(ex));
                            next.put(dimension + "Cursor", "").put(dimension + "CursorFailures", 0);
                        } else {
                            next.put(dimension + "CursorFailures", failures);
                        }
                    }
                    tx.put("history_source", id, next);
                }
                return null;
            });
        }
    }

    private record HistoryScan(String cursor, long startedAt, long since, boolean backfill) {}

    static boolean historyDimensionDue(ObjectNode source, String prefix, long now) {
        return historyDimensionDue(source, prefix, now, false);
    }

    static boolean historyDimensionDue(ObjectNode source, String prefix, long now, boolean freeAvailable) {
        String dimError = source.path(prefix + "Error").asText("");
        if (!dimError.isBlank()) {
            boolean budgetError = dimError.contains("外部调用预算已达保护上限") || dimError.contains("预算记录暂不可用");
            if (!(budgetError && freeAvailable)) {
                long lastAttempt = source.path(prefix + "LastAttemptAt")
                        .asLong(source.path("lastAttemptAt").asLong(0));
                long backoff = budgetError ? BUDGET_BACKOFF_MS : HISTORY_FAILURE_BACKOFF_MS;
                if (lastAttempt > 0 && now - lastAttempt < backoff) return false;
            }
        }
        if (!source.path(prefix + "BackfillComplete").asBoolean(false)) return true;
        if (!source.path(prefix + "Cursor").asText("").isBlank()) return true;
        return now - source.path("last" + Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1)
                + "CheckAt").asLong(0) >= HISTORY_INCREMENTAL_INTERVAL_MS;
    }

    private HistoryScan prepareHistoryScan(String sourceId, String prefix, String endpoint, ObjectNode source, long attemptAt) {
        String rawCursor = boundCursor(source, prefix + "Cursor", TIMELINE_PROVIDER, endpoint);
        String cursor = ("timeline".equals(prefix) && !rawCursor.isBlank() && !rawCursor.startsWith("fx:")
                && provider.isPageReadBudgetExhausted() && provider.canReadTimelineForFree(""))
                ? "" : rawCursor;
        boolean backfill = !source.path(prefix + "BackfillComplete").asBoolean(false);
        long startedAt = cursor.isBlank() ? attemptAt : source.path(prefix + "ScanStartedAt").asLong(attemptAt);
        long since = backfill ? 0 : (cursor.isBlank()
                ? Math.max(0, source.path(prefix + "WatermarkAt").asLong(0) - SCAN_OVERLAP_MS)
                : source.path(prefix + "ScanSince").asLong(0));
        store.tx(tx -> {
            ObjectNode current = tx.get("history_source", sourceId);
            if (current != null) tx.put("history_source", sourceId, current.deepCopy()
                    .put("lastAttemptAt", attemptAt)
                    .put(prefix + "LastAttemptAt", attemptAt)
                    .put(prefix + "Cursor", cursor)
                    .put(prefix + "CursorProvider", TIMELINE_PROVIDER)
                    .put(prefix + "CursorEndpoint", endpoint)
                    .put(prefix + "ScanStartedAt", startedAt)
                    .put(prefix + "ScanSince", since));
            return null;
        });
        return new HistoryScan(cursor, startedAt, since, backfill);
    }

    private void commitHistoryTimeline(String sourceId, TextPostPage page, List<TextPost> posts,
            HistoryScan scan, long completedAt, boolean scanComplete) {
        store.tx(tx -> {
            long added = 0;
            for (TextPost post : posts) {
                ObjectNode material = toMaterial(post, true);
                ObjectNode existing = tx.get("history_material", post.id());
                if (existing == null) added++;
                if (!sameRecord(existing, material)) tx.put("history_material", post.id(), material);
            }
            ObjectNode current = tx.get("history_source", sourceId);
            if (current != null) {
                ObjectNode next = current.deepCopy().put("lastSuccessAt", completedAt).put("timelineError", "")
                        .put("timelineCursor", scanComplete ? "" : page.nextCursor())
                        .put("timelineCursorProvider", TIMELINE_PROVIDER)
                        .put("timelineCursorEndpoint", TIMELINE_ENDPOINT)
                        .put("timelineCursorFailures", 0)
                        .put("nextDimension", "INBOUND")
                        .put("timelineSavedCount", current.path("timelineSavedCount").asLong(0) + added);
                applyCoverage(next, "timeline", posts.stream()
                        .map(post -> post.publishedAt().toEpochMilli()).toList());
                if (scanComplete) {
                    next.put("timelineBackfillComplete", true).put("timelineExhausted", true)
                            .put("timelineWatermarkAt", scan.startedAt()).put("lastTimelineCheckAt", completedAt);
                    if (scan.backfill() && page.exhausted()) next.put("timelineTerminalReason", "UPSTREAM_EXHAUSTED");
                }
                tx.put("history_source", sourceId, next);
            }
            return null;
        });
    }

    private void commitHistoryInbound(String sourceId, TextInteractionPage page, List<TextInteraction> interactions,
            HistoryScan scan, long completedAt, boolean scanComplete) {
        store.tx(tx -> {
            long added = 0;
            for (TextInteraction interaction : interactions) {
                ObjectNode material = mapper.createObjectNode()
                        .put("id", interaction.id()).put("targetAuthorId", interaction.targetAuthorId())
                        .put("targetUsername", interaction.targetUsername()).put("peerId", value(interaction.peerId()))
                        .put("peerUsername", value(interaction.peerUsername())).put("publishedAt", interaction.publishedAt().toEpochMilli())
                        .put("kind", interaction.kind().name()).put("text", value(interaction.text()))
                        .put("sourceUrl", value(interaction.sourceUrl()));
                String materialId = sourceId + ":" + interaction.id();
                ObjectNode existing = tx.get("history_interaction", materialId);
                if (existing == null) added++;
                if (!sameRecord(existing, material)) tx.put("history_interaction", materialId, material);
            }
            ObjectNode current = tx.get("history_source", sourceId);
            if (current != null) {
                ObjectNode next = current.deepCopy().put("lastSuccessAt", completedAt).put("inboundError", "")
                        .put("inboundCursor", scanComplete ? "" : page.nextCursor())
                        .put("inboundCursorProvider", TIMELINE_PROVIDER)
                        .put("inboundCursorEndpoint", INBOUND_ENDPOINT)
                        .put("inboundCursorFailures", 0)
                        .put("nextDimension", "TIMELINE")
                        .put("inboundSavedCount", current.path("inboundSavedCount").asLong(0) + added);
                applyCoverage(next, "inbound", interactions.stream()
                        .map(interaction -> interaction.publishedAt().toEpochMilli()).toList());
                if (scanComplete) {
                    next.put("inboundBackfillComplete", true).put("inboundExhausted", true)
                            .put("inboundWatermarkAt", scan.startedAt()).put("lastInboundCheckAt", completedAt);
                    if (scan.backfill() && page.exhausted()) next.put("inboundTerminalReason", "UPSTREAM_EXHAUSTED");
                }
                tx.put("history_source", sourceId, next);
            }
            return null;
        });
    }

    private static void applyCoverage(ObjectNode source, String prefix, List<Long> observedTimes) {
        if (observedTimes.isEmpty()) return;
        long oldest = observedTimes.stream().mapToLong(Long::longValue).min().orElse(0);
        long newest = observedTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long previousOldest = source.path(prefix + "OldestAt").asLong(0);
        source.put(prefix + "OldestAt", previousOldest == 0 ? oldest : Math.min(previousOldest, oldest));
        source.put(prefix + "NewestAt", Math.max(newest, source.path(prefix + "NewestAt").asLong(0)));
    }

    private static boolean sameRecord(ObjectNode existing, ObjectNode candidate) {
        if (existing == null) return false;
        ObjectNode normalized = existing.deepCopy();
        normalized.remove(List.of("id", "version", "updatedAt"));
        ObjectNode next = candidate.deepCopy();
        next.remove(List.of("id", "version", "updatedAt"));
        return normalized.equals(next);
    }

    private void commitPage(String sourceKind, String sourceId, String expectedInstanceId, String expectedCursor,
            TextPostPage page, List<TextPost> posts, long scanSince, long completedAt,
            long scanStartedAt, boolean scanComplete) {
        List<ObjectNode> newlyIngested = new ArrayList<>();
        ObjectNode latestSourceSnapshot = store.tx(tx -> {
            ObjectNode current = tx.get(sourceKind, sourceId);
            if (current == null || !"ACTIVE".equals(current.path("status").asText())
                    || !expectedInstanceId.equals(current.path("instanceId").asText())
                    || !expectedCursor.equals(current.path("cursor").asText(""))) return null;
            long commitCutoff = AssistantRetention.cutoffMillis(completedAt);
            XUserSummary pageAuthor = page.author();
            for (TextPost post : posts) {
                if (post.publishedAt().toEpochMilli() < commitCutoff) continue;
                boolean existedBefore = tx.get("material", post.id()) != null;
                ObjectNode material = toMaterial(post, pageAuthor, false);
                AssistantWorkflow.ingest(tx, material);
                if (!existedBefore) {
                    ObjectNode saved = tx.get("material", post.id());
                    if (saved != null) newlyIngested.add(saved);
                }
            }
            ObjectNode next = current.deepCopy().put("lastSuccessAt", completedAt).put("error", "")
                    .put("scanSince", scanSince)
                    .put("cursor", scanComplete ? "" : page.nextCursor())
                    .put("cursorProvider", TIMELINE_PROVIDER)
                    .put("cursorEndpoint", TIMELINE_ENDPOINT)
                    .put("cursorFailures", 0);
            if (pageAuthor != null) {
                if (!value(pageAuthor.username()).isBlank()) next.put("username", pageAuthor.username());
                if (!value(pageAuthor.displayName()).isBlank()) next.put("displayName", pageAuthor.displayName());
                if (!value(pageAuthor.avatarUrl()).isBlank()) next.put("avatarUrl", pageAuthor.avatarUrl());
                next.put("verified", pageAuthor.verified());
            }
            if (scanComplete) {
                next.put("lastCompleteAt", scanStartedAt);
                next.remove("anchorSince");
            }
            return tx.put(sourceKind, sourceId, next);
        });
        IngestHook hook = this.ingestHook;
        if (hook != null && latestSourceSnapshot != null
                && latestSourceSnapshot.path("autoRepostEnabled").asBoolean(false)
                && !newlyIngested.isEmpty()) {
            for (ObjectNode item : newlyIngested) {
                hook.onMaterialIngested(latestSourceSnapshot, item);
            }
        }
    }

    private ObjectNode toMaterial(TextPost post, boolean archiveOnly) {
        return toMaterial(post, null, archiveOnly);
    }

    private ObjectNode toMaterial(TextPost post, XUserSummary author, boolean archiveOnly) {
        ObjectNode material = mapper.createObjectNode()
                .put("id", post.id()).put("authorId", post.authorId()).put("username", post.username())
                .put("authorName", author != null && !value(author.displayName()).isBlank() ? author.displayName() : value(post.username()))
                .put("authorAvatarUrl", author != null ? value(author.avatarUrl()) : "")
                .put("authorVerified", author != null && author.verified())
                .put("text", value(post.text())).put("quotedText", value(post.quotedText()))
                .put("articleText", value(post.articleText())).put("articleRole", post.articleRole())
                .put("publishedAt", post.publishedAt().toEpochMilli())
                .put("sourceUrl", value(post.sourceUrl())).put("textStatus", value(post.textStatus()))
                .put("authorTextStatus", post.authorTextStatus()).put("quotedTextStatus", post.quotedTextStatus())
                .put("articleTextStatus", post.articleTextStatus())
                .put("quotedId", value(post.quotedId())).put("quotedAuthorId", value(post.quotedAuthorId()))
                .put("quotedUsername", value(post.quotedUsername())).put("articleId", value(post.articleId()))
                .put("articleUrl", value(post.articleUrl())).put("sourceIdentity", value(post.sourceIdentity()))
                .put("kind", post.kind().name()).put("hasMedia", post.hasMedia())
                .put("selfThreadReply", post.selfThreadReply()).put("pinned", post.pinned())
                .put("archiveOnly", archiveOnly);
        if (post.views() != null) material.put("views", post.views());
        if (post.likes() != null) material.put("likes", post.likes());
        if (post.replies() != null) material.put("replies", post.replies());
        if (post.reposts() != null) material.put("reposts", post.reposts());
        if (post.bookmarks() != null) material.put("bookmarks", post.bookmarks());
        return material;
    }

    private void collectStyleSamples() {
        ObjectNode owner = store.tx(tx -> tx.get("meta", "owner"));
        if (owner == null || owner.path("accountId").asText("").isBlank()
                || owner.path("username").asText("").isBlank()) return;
        ObjectNode status = store.tx(tx -> tx.get("meta", "style_status"));
        String cursor = status != null && status.path("authorTextOnly").asBoolean()
                ? boundCursor(status, "cursor", TIMELINE_PROVIDER, TIMELINE_ENDPOINT) : "";
        if (!cursor.isBlank() && !cursor.startsWith("fx:")
                && provider.isPageReadBudgetExhausted() && provider.canReadTimelineForFree("")) {
            cursor = "";
        }
        if (countStyleSamples() >= STYLE_SAMPLE_LIMIT) cursor = "";
        String attemptedCursor = cursor;
        try {
            for (int pageNumber = 0; pageNumber < STYLE_PAGE_BATCH; pageNumber++) {
                if (pageNumber > 0 && provider.canReadTimelineForFree("")) {
                    Thread.sleep(200L);
                }
                attemptedCursor = cursor;
                if (!store.hasCollectionSpace()) throw new IllegalStateException("本地空间达到采集保护下限");
                TextPostPage page = provider.fetchTextPostsPage(owner.path("username").asText(),
                        owner.path("accountId").asText(), cursor, 100, Instant.now().plusSeconds(45));
                if (!page.exhausted() && (page.nextCursor().isBlank() || cursor.equals(page.nextCursor()))) {
                    throw new IllegalStateException("上游分页游标无进展，已保留当前进度");
                }
                String nextCursor = page.exhausted() ? "" : page.nextCursor();
                boolean continueBatch = store.tx(tx -> {
                    for (TextPost post : page.posts()) {
                        if (post.styleText().isBlank()) continue;
                        tx.put("style_sample", post.id(), mapper.createObjectNode()
                                .put("textRole", "AUTHOR").put("text", post.styleText())
                                .put("publishedAt", post.publishedAt().toEpochMilli()));
                    }
                    boolean needsMore = !page.exhausted() && countStyleSamples(tx) < STYLE_SAMPLE_LIMIT;
                    ObjectNode current = tx.get("meta", "style_status");
                    ObjectNode next = current != null ? current.deepCopy() : mapper.createObjectNode();
                    tx.put("meta", "style_status", next.put("authorTextOnly", true)
                            .put("cursor", nextCursor)
                            .put("cursorProvider", TIMELINE_PROVIDER)
                            .put("cursorEndpoint", TIMELINE_ENDPOINT)
                            .put("cursorFailures", 0)
                            .put("exhausted", page.exhausted())
                            .put("continueBatch", needsMore).put("lastSuccessAt", System.currentTimeMillis())
                            .put("error", ""));
                    return needsMore;
                });
                if (!continueBatch) break;
                cursor = nextCursor;
            }
        } catch (Exception ex) {
            String failedCursor = attemptedCursor;
            store.tx(tx -> {
                ObjectNode current = tx.get("meta", "style_status");
                ObjectNode next = current != null ? current.deepCopy() : mapper.createObjectNode();
                if (!next.path("authorTextOnly").asBoolean()) next.put("cursor", "").put("exhausted", false);
                if (!failedCursor.isBlank() && !UpstreamBudgetGuard.isBudgetReached(ex)) {
                    int failures = next.path("cursorFailures").asInt(0) + 1;
                    if (shouldResetExpiredCursor(ex, failures)) {
                        next.put("cursor", "").put("cursorFailures", 0);
                    } else {
                        next.put("cursorFailures", failures);
                    }
                }
                tx.put("meta", "style_status", next.put("authorTextOnly", true).put("error", safeError(ex)));
                return null;
            });
        }
    }

    private int countStyleSamples() {
        return store.tx(this::countStyleSamples);
    }

    private int countStyleSamples(AssistantStore.Tx tx) {
        return tx.matching("style_sample", "textRole", "AUTHOR", STYLE_SAMPLE_LIMIT).size();
    }

    private void markAttempt(String kind, String id, long at) {
        store.tx(tx -> {
            ObjectNode current = tx.get(kind, id);
            if (current != null) tx.put(kind, id, current.deepCopy().put("lastAttemptAt", at));
            return null;
        });
    }

    private boolean markSourceAttempt(String id, String instanceId, String expectedRawCursor, String resolvedCursor,
            long at, long scanStartedAt, long scanSince) {
        return store.tx(tx -> {
            ObjectNode current = tx.get("source", id);
            if (current == null || !"ACTIVE".equals(current.path("status").asText())
                    || !instanceId.equals(current.path("instanceId").asText())
                    || !expectedRawCursor.equals(current.path("cursor").asText(""))) return false;
            tx.put("source", id, current.deepCopy().put("lastAttemptAt", at)
                    .put("cursor", resolvedCursor)
                    .put("cursorProvider", TIMELINE_PROVIDER)
                    .put("cursorEndpoint", TIMELINE_ENDPOINT)
                    .put("scanStartedAt", scanStartedAt)
                    .put("scanSince", scanSince));
            return true;
        });
    }

    private void markSourceFailure(String id, String instanceId, String cursor, long scanSince,
            long scanStartedAt, Exception error) {
        store.tx(tx -> {
            ObjectNode current = tx.get("source", id);
            if (current != null && "ACTIVE".equals(current.path("status").asText())
                    && instanceId.equals(current.path("instanceId").asText())
                    && cursor.equals(current.path("cursor").asText(""))) {
                ObjectNode next = current.deepCopy().put("error", safeError(error));
                if (!cursor.isBlank() && !UpstreamBudgetGuard.isBudgetReached(error)) {
                    int failures = next.path("cursorFailures").asInt(0) + 1;
                    if (shouldResetExpiredCursor(error, failures)) {
                        appendCoverageGap(next, scanSince, scanStartedAt, safeError(error));
                        next.put("cursor", "").put("cursorFailures", 0).put("anchorSince", scanSince);
                    } else {
                        next.put("cursorFailures", failures);
                    }
                }
                tx.put("source", id, next);
            }
            return null;
        });
    }

    private boolean hasBoundOwner() {
        ObjectNode owner = store.tx(tx -> tx.get("meta", "owner"));
        return owner != null && !owner.path("accountId").asText("").isBlank();
    }

    private static String parseUsername(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            URI uri = URI.create(value);
            String[] parts = uri.getPath().split("/");
            value = parts.length > 1 ? parts[1] : "";
        }
        if (value.startsWith("@")) value = value.substring(1);
        if (!HANDLE.matcher(value).matches()) throw new IllegalArgumentException("Invalid X username");
        return value;
    }

    private static String value(String text) { return text == null ? "" : text; }

    static long listenerScanSince(long enabledAt, long lastCompleteAt) {
        if (lastCompleteAt <= 0) return enabledAt;
        return Math.max(enabledAt, lastCompleteAt - SCAN_OVERLAP_MS);
    }

    static boolean crossedListenerBoundary(List<TextPost> posts, long scanSince) {
        return posts.stream().anyMatch(post -> !post.pinned()
                && post.publishedAt().toEpochMilli() < scanSince);
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    @PreDestroy
    void stop() { worker.shutdownNow(); }
}
