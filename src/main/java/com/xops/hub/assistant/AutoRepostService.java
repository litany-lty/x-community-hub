package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.XPostLink;
import com.xops.hub.provider.PaidCapabilityGate;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.provider.XPublishClient.XPublishException;
import com.xops.hub.provider.XPublishClient.PublicationReceipt;
import com.xops.hub.service.XOAuthService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static com.xops.hub.assistant.AssistantStore.object;

@Service
public class AutoRepostService {
    private static final Logger log = LoggerFactory.getLogger(AutoRepostService.class);
    static final long MAX_FRESHNESS_AGE_MS = 4L * 60L * 60_000L;
    static final long PATROL_MAX_FRESHNESS_AGE_MS = 48L * 60L * 60_000L;
    private static final long OWNER_TIMELINE_MAX_AGE_MS = 5L * 60_000L;
    static final long RECENT_REPOST_FALLBACK_MAX_AGE_MS = 30L * 24L * 60L * 60_000L;
    static final long REWRITE_MAX_AGE_MS = 30L * 24L * 60L * 60_000L;
    public static final long SAME_AUTHOR_COOLDOWN_MS = 16L * 60L * 60_000L;
    public static final long OWNER_TWEET_BASE_INTERVAL_MS = 60L * 60_000L;
    public static final int OWNER_TWEET_MIN_JITTER_MINUTES = 1;
    public static final int OWNER_TWEET_MAX_JITTER_MINUTES = 20;
    private static final long HOT_CONTEXT_WINDOW_MS = 48L * 60L * 60_000L;
    private static final java.util.regex.Pattern EMBEDDED_URL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:https?://|\\bwww\\.|\\b(?:t\\.co|x\\.com|twitter\\.com)/)\\S+");
    private static final java.util.regex.Pattern THREAD_OR_VISUAL_DEPENDENCY_PATTERN = java.util.regex.Pattern.compile(
            "(?is)(?:^\\s*(?:1\\s*/\\s*\\d*|🧵)|[👇⬇]|(?:如下图|见下图|配图|看图|视频里|评论区见|link\\s+in\\s+bio|see\\s+(?:below|thread)))");
    private static final java.util.regex.Pattern QUANT_FACT_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:\\b\\d+(?:\\.\\d+)?\\s*(?:%|x|倍|ms|s|gb|tb|mb|k|m|b|fps|tps|qps|tokens?|亿|万|美元|usd)|\\$\\d+|\\bv\\d+\\.\\d+)");
    private static final java.util.regex.Pattern DIRECT_PASS_HINT_PATTERN = java.util.regex.Pattern.compile(
            "(?is)(?:"
                    + "\\b(?:gpt|claude|gemini|codex|sora|llama|qwen|deepseek|gemma|mistral|grok|sonnet|opus|haiku|o[134])\\b"
                    + "|\\bmodels?\\b.{0,40}\\b(?:releas|launch|roll\\s*out|rolling\\s+out|introduc|announc|open[- ]?sourc|weights?|preview)"
                    + "|\\b(?:releas|launch|roll\\s*out|rolling\\s+out|introduc|shipp?ing)\\b.{0,40}\\b(?:models?|features?|updates?|upgrades?|cli|sdk|api|agents?)"
                    + "|\\b(?:features?|cli|sdk|api|agents?|sandbox|app|chat)\\b.{0,36}\\b(?:updates?|upgrades?|releas|improv|shipp?ed|iterat)"
                    + "|\\b(?:quotas?|usage|limits?|caps?|credits?|allowance)\\b.{0,40}\\b(?:reset|refresh|clear|doubl|increas|rais|restor|free|bonus)"
                    + "|\\b(?:reset|refresh|doubl|increas|rais)\\w*\\b.{0,40}\\b(?:quotas?|usage|limits?|caps?|credits?)"
                    + "|(?:新模型|大模型|模型发布|发布.{0,8}模型|开源模型|开源权重|上线.{0,8}模型)"
                    + "|(?:功能更新|功能迭代|新功能|版本更新|迭代更新|重磅更新|推了.{0,6}更新)"
                    + "|(?:额度|用量|调用次数|速率上限|上限|限免|赠金).{0,12}(?:重置|清零|刷新|恢复|翻倍|上调|放开)"
                    + "|(?:重置|清零|刷新|翻倍|上调).{0,12}(?:额度|用量|次数|上限)"
                    + ")");

    static boolean mightBeDirectPassCandidate(ObjectNode material) {
        if (material == null) return false;
        String text = AssistantTopics.text(material);
        return text != null && !text.isBlank() && DIRECT_PASS_HINT_PATTERN.matcher(text).find();
    }
    private static final int DEFAULT_DAILY_LIMIT = 10;
    private static final Set<String> RETRYABLE_STATUSES = Set.of(
            "AUTH_REQUIRED", "JUDGE_FAILED", "FAILED", "SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS"
    );
    private static final Set<String> WAITING_FOR_WINDOW = Set.of("SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS");
    private static final Set<String> QUOTE_DELIVERY_STATUSES = Set.of("SENDING", "UNCERTAIN", "PUBLISHED");

    private final AssistantStore store;
    private final SourceCollector sources;
    private final PiAgentViralJudge judge;
    private final XOAuthService oauthService;
    private final XPublishClient publishClient;
    private final Clock clock;
    private final Map<String,ObjectNode> pendingAcceptedReceipts = new java.util.concurrent.ConcurrentHashMap<>();
    @Autowired(required = false)
    private com.xops.hub.provider.PaidCapabilityGate paidGate;
    @Autowired(required = false)
    private com.xops.hub.service.ScheduledTweetService scheduledTweetService;
    @Autowired(required = false)
    private com.xops.hub.service.CommunityHubService communityHubService;
    @Autowired(required = false)
    private com.xops.hub.config.XOpsProperties properties;
    @Autowired(required = false)
    private org.springframework.core.env.Environment environment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-auto-repost");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ScheduledExecutorService deferredScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "content-auto-repost-cd-wakeup");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, Long> deferredSourceReadyAtMs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextPatrolWakeupAtMs = new java.util.concurrent.atomic.AtomicLong(0L);
    private volatile java.util.function.IntSupplier randomDrawSupplier =
            () -> java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 101);

    public enum AutoPublishMode {
        REPOST,
        REWRITE
    }

    public record AutoPublishOrderDecision(
            AutoPublishMode primaryMode,
            boolean forcedByTwoStreak,
            int randomNumber,
            String reasonCode
    ) { }

    void setRandomDrawSupplier(java.util.function.IntSupplier supplier) {
        this.randomDrawSupplier = supplier != null
                ? supplier
                : () -> java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 101);
    }

    /**
     * Resolves the next automatic publish mode:
     * - If the last 2 automatic publishes were the same mode, does NOT randomize and strictly switches to the other mode;
     * - Otherwise draws a random integer: odd (单数) => REPOST (引用), even (偶数) => REWRITE (洗稿).
     */
    static AutoPublishOrderDecision resolveNextAutoPublishOrder(
            List<AutoPublishMode> recentAutoModesNewestFirst,
            java.util.function.IntSupplier drawSupplier
    ) {
        if (recentAutoModesNewestFirst != null && recentAutoModesNewestFirst.size() >= 2) {
            AutoPublishMode last1 = recentAutoModesNewestFirst.get(0);
            AutoPublishMode last2 = recentAutoModesNewestFirst.get(1);
            if (last1 != null && last1 == last2) {
                AutoPublishMode opposite = (last1 == AutoPublishMode.REPOST)
                        ? AutoPublishMode.REWRITE
                        : AutoPublishMode.REPOST;
                return new AutoPublishOrderDecision(
                        opposite,
                        true,
                        0,
                        opposite == AutoPublishMode.REWRITE ? "STREAK_2_FORCE_REWRITE" : "STREAK_2_FORCE_REPOST"
                );
            }
        }
        int draw = drawSupplier != null ? drawSupplier.getAsInt() : 1;
        boolean isOdd = (draw & 1) != 0;
        AutoPublishMode chosen = isOdd ? AutoPublishMode.REPOST : AutoPublishMode.REWRITE;
        return new AutoPublishOrderDecision(
                chosen,
                false,
                draw,
                isOdd ? "RANDOM_ODD_REPOST" : "RANDOM_EVEN_REWRITE"
        );
    }

    static List<AutoPublishMode> collectRecentAutoPublishModesNewestFirst(AssistantStore.Tx tx, int limit) {
        record TimedMode(long publishedAtMs, AutoPublishMode mode) { }
        List<TimedMode> history = new ArrayList<>();
        for (ObjectNode rec : tx.all("auto_repost")) {
            if (rec.path("manual").asBoolean(false)) continue;
            if (!Set.of("PUBLISHED", "UNCERTAIN").contains(rec.path("status").asText(""))) continue;
            long ts = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L));
            if (ts > 0L) history.add(new TimedMode(ts, AutoPublishMode.REPOST));
        }
        for (ObjectNode rec : tx.all("auto_rewrite")) {
            if (!Set.of("PUBLISHED", "UNCERTAIN").contains(rec.path("status").asText(""))) continue;
            long ts = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L));
            if (ts > 0L) history.add(new TimedMode(ts, AutoPublishMode.REWRITE));
        }
        history.sort(Comparator.comparingLong(TimedMode::publishedAtMs).reversed());
        return history.stream().limit(Math.max(1, limit)).map(TimedMode::mode).toList();
    }
    private record QuotePreview(long requestOrder, ObjectNode result) { }
    private final java.util.concurrent.atomic.AtomicLong quotePreviewOrder = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, QuotePreview> previewCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, QuotePreview> eldest) {
                    return size() > 128;
                }
            }
    );
    private final Map<String, ObjectNode> rewritePreviewCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
                    return size() > 128;
                }
            }
    );

    @Autowired
    public AutoRepostService(
            AssistantStore store,
            SourceCollector sources,
            PiAgentViralJudge judge,
            XOAuthService oauthService,
            XPublishClient publishClient
    ) {
        this(store, sources, judge, oauthService, publishClient, Clock.systemUTC());
    }

    AutoRepostService(
            AssistantStore store,
            SourceCollector sources,
            PiAgentViralJudge judge,
            XOAuthService oauthService,
            XPublishClient publishClient,
            Clock clock
    ) {
        this.store = store;
        this.sources = sources;
        this.judge = judge;
        this.oauthService = oauthService;
        this.publishClient = publishClient;
        this.clock = clock;
    }

    static final int DEFAULT_QUIET_START_HOUR = 12;
    static final int DEFAULT_QUIET_END_HOUR = 24;

    int quietStartHour() {
        return properties != null ? properties.getAutoRepostQuietStartHour() : DEFAULT_QUIET_START_HOUR;
    }

    int quietEndHour() {
        return properties != null ? properties.getAutoRepostQuietEndHour() : DEFAULT_QUIET_END_HOUR;
    }

    boolean isInAutoRepostQuietWindow(long epochMs) {
        return com.xops.hub.config.XOpsProperties.isQuietHour(
                epochMs, AssistantRetention.ZONE, quietStartHour(), quietEndHour());
    }

    private String quietWindowReason() {
        return String.format(Locale.ROOT, "%02d:00-%02d:00（北京时间）静默时段不做自动引用转发",
                quietStartHour(), quietEndHour());
    }

    boolean isAutoRepostRuntimeEnabled() {
        if (properties == null) {
            return true;
        }
        return properties.isAutoRepostActive(environment);
    }

    @PostConstruct
    void bindHook() {
        if (!isAutoRepostRuntimeEnabled()) {
            reconcileAcceptedReceipts();
            log.info("Local/non-production runtime detected; auto-repost background ingest hooks and startup retries are disabled (preview remains available).");
            return;
        }
        recoverStaleRecords();
        sources.setIngestHook(this::enqueue);
        sources.setBatchIngestHook(this::enqueueAuthorBatch);
        long bootNowMs = clock.millis();
        if (isInAutoRepostQuietWindow(bootNowMs)) {
            scheduleQuietWindowEndWakeup(bootNowMs);
        }
    }

    List<String> recoverStaleRecords() {
        reconcileAcceptedReceipts();
        List<String> startupRetryIds = new ArrayList<>();
        store.tx(tx -> {
            for (String table : List.of("auto_repost", "auto_rewrite")) {
                for (ObjectNode rec : tx.all(table)) {
                    String id = rec.path("materialId").asText(rec.path("id").asText("")).strip();
                    if (id.isBlank()) continue;
                    String status = rec.path("status").asText("");
                    boolean pendingRetry = "auto_repost".equals(table) && rec.path("autoRetryAt").asLong() > 0L;
                    boolean oldBudgetRejection = "auto_repost".equals(table) && Set.of("AUTH_REQUIRED", "FAILED").contains(status)
                            && UpstreamBudgetGuard.isBudgetBlockMessage(rec.path("error").asText())
                            && !rec.path("budgetDeferred").asBoolean();
                    if ("RETRY_QUEUED".equals(status) || oldBudgetRejection || pendingRetry
                            && (Set.of("EVALUATING", "FAILED").contains(status) || WAITING_FOR_WINDOW.contains(status))) {
                        ObjectNode pending = rec.deepCopy().put("status", WAITING_FOR_WINDOW.contains(status) ? status : "FAILED");
                        if (oldBudgetRejection) {
                            pending.put("budgetDeferred", true).put("autoRetryAttempts", nonBudgetRetryAttempts(tx, id, rec));
                        }
                        if ("auto_repost".equals(table) && !pendingRetry) pending.put("autoRetryAt", clock.millis() + 3_000L);
                        if (!pending.equals(rec)) tx.put(table, id, pending);
                        if ("auto_repost".equals(table)) startupRetryIds.add(id);
                    } else if ("EVALUATING".equals(status)) {
                        tx.put(table, id, rec.deepCopy()
                                .put("status", interruptedEvaluationStatus(tx, table, id, rec))
                                .put("error", "服务重启中断判定，可手动重试"));
                    } else if ("SENDING".equals(status)) {
                        tx.put(table, id, rec.deepCopy()
                                .put("status", "UNCERTAIN")
                                .put("error", "发送途中服务重启，请先核实线上时间线避免重复发送"));
                    }
                }
            }
            return null;
        });
        String owner = store.boundOwnerId();
        if (paidGate != null && !owner.isBlank()) {
            for (String id : startupRetryIds) {
                ObjectNode row = store.tx(tx -> {
                    var pending = tx.get("auto_repost", id);
                    if (repostAuthorized(tx, pending, owner)) return pending;
                    if (pending != null && pending.has("autoRetryAt")) {
                        pending.remove("autoRetryAt");
                        tx.put("auto_repost", id, pending);
                    }
                    return null;
                });
                if (row == null) continue;
                paidGate.restorePendingRetry("auto-repost:" + id, "x_tweet_create", PaidCapabilityGate.PRIVILEGED_USERNAME,
                        owner, row.path("autoRetryAttempts").asInt(), row.path("autoRetryAt").asLong(), row.path("error").asText(),
                        () -> runAutomaticRepostRetry(id), true);
            }
        }
        return startupRetryIds;
    }

    private static int nonBudgetRetryAttempts(AssistantStore.Tx tx, String id, ObjectNode row) {
        int attempts = row.path("autoRetryAttempts").asInt();
        if (attempts == 0) return 0;
        var revisions = tx.versions("auto_repost", id);
        for (int i = 0; i + 1 < revisions.size(); i++) {
            var after = revisions.get(i);
            var before = revisions.get(i + 1);
            if ("EVALUATING".equals(after.path("status").asText()) && !after.path("automaticRetryRunning").asBoolean()) break;
            if ("FAILED".equals(after.path("status").asText()) && "SENDING".equals(before.path("status").asText())
                    && before.path("automaticRetryRunning").asBoolean() && UpstreamBudgetGuard.isBudgetBlockMessage(after.path("error").asText())
                    && after.path("autoRetryAttempts").asInt() > before.path("autoRetryAttempts").asInt()) attempts--;
        }
        // A legacy export may contain just the last revision of an exhausted local rejection.
        return Math.max(0, Math.min(attempts, PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES - 1));
    }

    public ObjectNode retry(String materialId) {
        return retry(materialId, null);
    }

    public ObjectNode verifyAutomaticPublication(String ownerId, String materialId, AutoPublishMode mode, ObjectNode decision) {
        if (mode == null) throw new IllegalArgumentException("请选择需要核实的自动发布记录");
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        String table = rewrite ? "auto_rewrite" : "auto_repost";
        String receiptId = (rewrite ? "auto-rewrite-" : "auto-repost-") + materialId;
        requireReceiptReady(receiptId);
        ObjectNode verified = store.tx(tx -> {
            ObjectNode row = AssistantTopics.required(tx, table, materialId);
            if (ownerId == null || ownerId.isBlank() || !boundPublisherMatches(tx, row, ownerId))
                throw new XDataProviderException(403, "该记录不属于当前发布账号");
            if (row.path("manual").asBoolean()) throw new XDataProviderException(409, "请从手动引用回执核实该记录");
            if (!AssistantPublishing.applyVerification(tx, table, row, decision, clock.millis())) return row;
            ObjectNode existing = tx.get("publication", receiptId);
            if (existing != null && "PUBLISHED".equals(existing.path("status").asText())
                    && !("PUBLISHED".equals(row.path("status").asText())
                        && existing.path("publishedTweetId").equals(row.path("publishedTweetId"))))
                throw new XDataProviderException(409, "已有不同的成功回执，请刷新后核对");
            row.remove(List.of("autoRetryAt", "autoRetryAttempts", "automaticRetryRunning", "deferredPendingEval"));
            if ("PUBLISHED".equals(row.path("status").asText())) {
                if (!rewrite && !row.hasNonNull("requestedQuoteTweetId") && row.hasNonNull("quoteTweetId"))
                    row.put("requestedQuoteTweetId", row.path("quoteTweetId").asText());
                if (!row.hasNonNull("publicationMode")) row.remove(List.of("quoteTweetId", "quotedTweetId"));
                if (existing == null || !"PUBLISHED".equals(existing.path("status").asText())) {
                    ObjectNode receipt = object().put("source", rewrite ? "AUTO_REWRITE_POST" : row.path("source").asText("AUTO_REPOST_VERIFIED"))
                            .put("ownerAccountId", ownerId).put("materialId", materialId)
                            .put("requestedText", row.path(rewrite ? "rewrittenText" : "commentText").asText());
                    for (String field : List.of("topicId", "authorId", "username", "status", "requestedQuoteTweetId",
                            "text", "publicationMode", "quoteTweetId", "quotedTweetId", "publishedTweetId", "publishedTweetUrl", "publishedAt", "verifiedAt")) {
                        if (row.hasNonNull(field)) receipt.set(field, row.get(field));
                    }
                    tx.put("publication", receiptId, receipt);
                }
            }
            return tx.put(table, materialId, row);
        });
        clearPreviewCache();
        return verified;
    }

    private void runAutomaticRepostRetry(String id) {
        // The ledger counts the outcome of the actual send on the serial publisher.
        CompletableFuture.runAsync(() -> {
            if (!isAutoRepostRuntimeEnabled()) return;
            reconcileAcceptedReceipts();
            ObjectNode eligible = store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", id);
                if (rec == null || rec.path("autoRetryAt").asLong() <= 0L) return null;
                if (("FAILED".equals(rec.path("status").asText()) || WAITING_FOR_WINDOW.contains(rec.path("status").asText()))
                        && repostAuthorized(tx, rec, null)) return rec;
                rec.remove("autoRetryAt");
                tx.put("auto_repost", id, rec);
                return null;
            });
            if (eligible == null) return;
            if (deferQuietRetry(id)) return;
            var owner = store.tx(AssistantStore.Tx::ownerBinding);
            if (owner == null) return;
            String ownerId = owner.path("accountId").asText();
            if (refreshOwnerTimeline(ownerId, owner.path("username").asText("").replaceFirst("^@+", "")) < 0L) {
                deferRepostRetry(id, eligible.path("status").asText(), clock.millis() + PaidCapabilityGate.RETRY_INTERVAL_MS,
                        "暂时无法核实发布账号的最新发帖时间，稍后重查");
                return;
            }
            try {
                retryRepost(id, ownerId);
            } catch (RepostCooldown cooldown) {
                deferRepostRetry(id, cooldown.status, cooldown.readyAt, cooldown.getMessage());
                return;
            } catch (RuntimeException interrupted) {
                store.tx(tx -> {
                    var row = tx.get("auto_repost", id);
                    if (row != null && "FAILED".equals(row.path("status").asText())) {
                        scheduleNextAttempt(row, row.path("autoRetryAttempts").asInt() + 1);
                        tx.put("auto_repost", id, row);
                    }
                    return null;
                });
                throw interrupted;
            }
            ObjectNode result = store.tx(tx -> tx.get("auto_repost", id));
            if (result != null && WAITING_FOR_WINDOW.contains(result.path("status").asText()))
                deferRepostRetry(id, result.path("status").asText(), result.path("autoRetryAt").asLong(), result.path("reason").asText());
        }, executor).join();
    }

    private boolean deferQuietRetry(String id) {
        long now = clock.millis();
        if (!isInAutoRepostQuietWindow(now)) return false;
        long next = com.xops.hub.config.XOpsProperties.nextQuietWindowEndEpochMs(
                now, AssistantRetention.ZONE, quietStartHour(), quietEndHour());
        deferRepostRetry(id, "SKIPPED_QUIET_HOURS", next, quietWindowReason());
        return true;
    }

    private void deferRepostRetry(String id, String status, long readyAt, String reason) {
        long now = clock.millis();
        long next = Math.max(now + 1L, readyAt);
        boolean pending = store.tx(tx -> {
            var row = tx.get("auto_repost", id);
            if (row == null || !("FAILED".equals(row.path("status").asText()) || WAITING_FOR_WINDOW.contains(row.path("status").asText()))) return false;
            row.remove("automaticRetryRunning");
            tx.put("auto_repost", id, row.put("status", status).put("autoRetryAt", next).put("reason", reason));
            return true;
        });
        if (pending && (paidGate == null || !paidGate.deferRunningRetry("auto-repost:" + id, next))) {
            deferredScheduler.schedule(() -> runAutomaticRepostRetry(id), Math.max(1L, next - now),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static final class RepostCooldown extends XDataProviderException {
        final String status;
        final long readyAt;
        RepostCooldown(String status, long readyAt, String reason) {
            super(409, reason);
            this.status = status;
            this.readyAt = readyAt;
        }
    }

    private static boolean strictRepostAuthorLock(ObjectNode row) {
        return row != null && row.path("strictSixteenHourAuthorLock")
                .asBoolean(row.path("candidateWindowMs").asLong() >= PATROL_MAX_FRESHNESS_AGE_MS);
    }

    private RepostCooldown repostCooldown(AssistantStore.Tx tx, ObjectNode rec, String ownerId, String ownerUsername,
                                         String materialId, boolean exemptAuthor, long now) {
        String username = rec.path("username").asText("").replaceFirst("^@+", "");
        DualCooldown cd = computeLiveDualCooldown(tx, AssistantWorkflow.settings(tx), ownerId, ownerUsername,
                rec.path("authorId").asText(), username, materialId, now);
        if ((!exemptAuthor || strictRepostAuthorLock(rec)) && cd.authorCdActive())
            return new RepostCooldown("SKIPPED_AUTHOR_COOLDOWN", Math.max(cd.authorReadyAtMs(), cd.globalReadyAtMs()),
                    "16 小时内已转发过 @" + username + "，避免连续转发同一人");
        if (cd.globalCdActive())
            return new RepostCooldown("SKIPPED_COOLDOWN", cd.globalReadyAtMs(), "距我账号上一条推文未满 "
                    + cd.globalRequiredMinutes() + " 分钟（还差约 " + cd.globalRemainingMinutes() + " 分钟），暂不引用转发");
        return null;
    }

    public ObjectNode retry(String materialId, AutoPublishMode requestedMode) {
        return retry(materialId, requestedMode, false);
    }

    public ObjectNode retry(String materialId, AutoPublishMode requestedMode, boolean reassess) {
        reconcileAcceptedReceipts();
        String id = materialId == null ? "" : materialId.strip();
        if (id.isBlank()) throw new IllegalArgumentException("缺少 materialId");
        AutoPublishMode mode = requestedMode != null ? requestedMode : store.tx(tx -> {
            boolean quote = tx.get("auto_repost", id) != null;
            boolean rewrite = tx.get("auto_rewrite", id) != null;
            if (quote && rewrite) throw new XDataProviderException(409, "此素材有两种发布记录，请选择要重试的记录");
            return rewrite ? AutoPublishMode.REWRITE : AutoPublishMode.REPOST;
        });
        if (reassess && mode != AutoPublishMode.REWRITE) throw new IllegalArgumentException("请指定要重新评估的洗稿");
        return mode == AutoPublishMode.REWRITE ? retryRewrite(id, reassess) : retryRepost(id, null);
    }

    private ObjectNode retryRepost(String id, String checkedOwnerId) {
        boolean automatic = checkedOwnerId != null;
        ObjectNode checkedOwner = store.tx(tx -> {
            requireRetryableRepost(tx, id, checkedOwnerId);
            return tx.ownerBinding();
        });
        String checkedId = checkedOwner.path("accountId").asText();
        if (!automatic && refreshOwnerTimeline(checkedId, checkedOwner.path("username").asText().replaceFirst("^@+", "")) < 0L)
            throw new XDataProviderException(503, "暂时无法核实发布账号的最新发帖时间，请稍后重试");
        record RetryPlan(
                ObjectNode updated,
                boolean reuseComment,
                String existingComment,
                String ownerId,
                String ownerUsername,
                String topicId,
                ObjectNode source,
                ObjectNode material,
                String styleProfile,
                String preferencesText
        ) { }

        RetryPlan plan = store.tx(tx -> {
            ObjectNode existing = requireRetryableRepost(tx, id, checkedId);
            String status = existing.path("status").asText("");
            long nowMs = clock.millis();
            String topicId = tx.publicationTopic(existing, id);
            ObjectNode owner = tx.ownerBinding();
            String ownerAccountId = owner.path("accountId").asText("");
            ObjectNode conflict = repostConflict(tx, id, topicId);
            if (conflict != null) {
                if (!automatic) throw new XDataProviderException(409, "该素材或选题已发送或回执待确认");
                if (!"PUBLISHED".equals(conflict.path("status").asText()))
                    throw new RepostCooldown("SKIPPED_COOLDOWN", nowMs + PaidCapabilityGate.RETRY_INTERVAL_MS, "同一原帖或选题的发布回执待核实，稍后重查");
                existing.remove(List.of("autoRetryAt", "automaticRetryRunning"));
                tx.put("auto_repost", id, existing.put("status", "SKIPPED_DUPLICATE").put("reason", "该素材或选题已有发布回执"));
                return null;
            }
            String ownerUsername = owner.path("username").asText("i");
            boolean exemptAuthorCd = PiAgentViralJudge.isDirectPassCategory(existing.path("category").asText(""));
            RepostCooldown cooldown = repostCooldown(tx, existing, ownerAccountId, ownerUsername, id, exemptAuthorCd, nowMs);
            if (cooldown != null) throw cooldown;
            String existingComment = existing.path("commentText").asText("").strip();
            ObjectNode liveMaterial = tx.rewriteMaterial(id, nowMs);
            boolean reuseComment = (Set.of("AUTH_REQUIRED", "FAILED").contains(status) || WAITING_FOR_WINDOW.contains(status))
                    && !existingComment.isBlank() && matchesEvaluatedQuote(tx, existing, liveMaterial);
            ObjectNode liveSource = tx.get("source", existing.path("authorId").asText());
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            ObjectNode evaluating = existing.deepCopy().put("topicId", topicId).put("ownerAccountId", ownerAccountId).put("automaticRetryRunning", automatic);
            if (liveMaterial != null) evaluating.put("quoteSourceFingerprint", quoteSourceFingerprint(liveMaterial));
            if (!reuseComment) {
                evaluating.remove(List.of("commentText", "quoteSourceNeedsReview"));
                if (liveMaterial != null) evaluating.put("quoteAuthorFollowers", quoteAuthorFollowers(liveSource, liveMaterial))
                        .put("previewMaterialFingerprint", previewMaterialFingerprint(liveMaterial));
            }
            evaluating.remove("budgetDeferred");
            if (!automatic) evaluating.remove(List.of("autoRetryAttempts", "autoRetryAt"));
            ObjectNode updated = tx.put("auto_repost", id, evaluating.put("status", "EVALUATING").put("error", ""));
            return new RetryPlan(
                    updated,
                    reuseComment,
                    existingComment,
                    ownerAccountId,
                    ownerUsername,
                    topicId,
                    liveSource.deepCopy(),
                    liveMaterial == null ? null : liveMaterial.deepCopy(),
                    composeOwnerQuoteVoiceProfile(tx),
                    prefs.path("text").asText("")
            );
        });

        if (plan == null) return store.tx(tx -> tx.get("auto_repost", id));
        Runnable attempt = () -> {
            try {
                if (plan.reuseComment()) {
                    dispatchCommentAndPublish(
                            id,
                            plan.ownerId(),
                            plan.ownerUsername(),
                            plan.topicId(),
                            plan.existingComment(),
                            null,
                            0L,
                            automatic
                    );
                } else if (plan.material() == null) {
                    store.tx(tx -> {
                        ObjectNode rec = tx.get("auto_repost", id);
                        if (rec != null) {
                            tx.put("auto_repost", id, rec.deepCopy()
                                    .put("status", "FAILED")
                                    .put("error", "关联原始素材不存在，无法重新判定"));
                        }
                        return null;
                    });
                } else {
                    evaluateAndPublish(
                            id,
                            plan.ownerId(),
                            plan.ownerUsername(),
                            plan.topicId(),
                            plan.source(),
                            plan.material(),
                            plan.styleProfile(),
                            plan.preferencesText(),
                            clock.millis(),
                            false,
                            automatic
                    );
                }
            } catch (RuntimeException ex) {
                markEvaluationFailed("auto_repost", id, safeError(ex));
                if (automatic) throw ex;
                log.warn("Auto-repost retry failed for material {}: {}", id, ex.getMessage());
            }
        };
        if (automatic) attempt.run();
        else executor.submit(attempt);
        return plan.updated();
    }

    private static boolean correctedQuoteVerdict(ObjectNode row) {
        return row.path("quoteSourceNeedsReview").asBoolean()
                && Set.of("SKIPPED", "JUDGE_FAILED").contains(row.path("status").asText());
    }

    private ObjectNode requireRetryableRepost(AssistantStore.Tx tx, String id, String checkedOwnerId) {
        ObjectNode existing = tx.get("auto_repost", id);
        if (existing == null) throw new XDataProviderException(404, "自动转发记录不存在");
        if (!RETRYABLE_STATUSES.contains(existing.path("status").asText()) && !correctedQuoteVerdict(existing))
            throw new XDataProviderException(409, "当前状态不允许重发，请先核实线上回执");
        if (tx.ownerBinding() == null) throw new XDataProviderException(400, "缺少内容助手所有者信息");
        if (!repostAuthorized(tx, existing, checkedOwnerId))
            throw new XDataProviderException(409, "原始素材与当前获准自动引用的来源或作者不一致");
        if (existing.path("quoteSourceNeedsReview").asBoolean() && tx.rewriteMaterial(id, clock.millis()) == null)
            throw new XDataProviderException(409, "原帖已更正且素材已过期，重新获取原帖后才能评估附文");
        return existing;
    }

    private void markEvaluationFailed(String table, String materialId, String reason) {
        store.tx(tx -> {
            ObjectNode rec = tx.get(table, materialId);
            if (rec != null && "EVALUATING".equals(rec.path("status").asText())) {
                tx.put(table, materialId, rec.put("status", interruptedEvaluationStatus(tx, table, materialId, rec)).put("error", reason));
            }
            return null;
        });
    }

    private static String interruptedEvaluationStatus(AssistantStore.Tx tx, String table, String id, ObjectNode row) {
        if ("auto_rewrite".equals(table) && !row.path("rewrittenText").asText().isBlank()) {
            for (ObjectNode previous : tx.versions(table, id)) {
                String status = previous.path("status").asText();
                if ("EVALUATING".equals(status)) continue;
                if (rewriteRetryable(status) && previous.path("rewrittenText").equals(row.path("rewrittenText"))
                        && tx.publicationOwnerMatches(previous, row.path("ownerAccountId").asText())) return status;
                break;
            }
        }
        return "JUDGE_FAILED";
    }

    public void clearPreviewCache() {
        previewCache.clear();
        rewritePreviewCache.clear();
    }

    record DualCooldown(
            boolean globalCdActive,
            int globalJitterMinutes,
            long globalRequiredMinutes,
            long globalRemainingMinutes,
            long globalReadyAtMs,
            boolean authorCdActive,
            long authorRemainingMinutes,
            long authorReadyAtMs
    ) {
        boolean anyActive() {
            return globalCdActive || authorCdActive;
        }

        long nextReadyAtMs() {
            if (globalCdActive && authorCdActive) return Math.max(globalReadyAtMs, authorReadyAtMs);
            return globalCdActive ? globalReadyAtMs : (authorCdActive ? authorReadyAtMs : 0L);
        }

        String blockingType() {
            if (globalCdActive && authorCdActive) return "BOTH_CD";
            if (globalCdActive) return "GLOBAL_CD";
            if (authorCdActive) return "AUTHOR_CD";
            return "NONE";
        }

        String primaryStatusForStore() {
            return globalCdActive ? "SKIPPED_COOLDOWN" : "SKIPPED_AUTHOR_COOLDOWN";
        }

        String primaryDecisionCode() {
            return globalCdActive ? "SKIP_COOLDOWN" : "SKIP_AUTHOR_COOLDOWN";
        }

        String blockingReasonText() {
            if (globalCdActive && authorCdActive) {
                long remHrs = Math.max(1L, (authorRemainingMinutes + 59L) / 60L);
                return "全局 CD（1小时+" + globalJitterMinutes + "分钟，余约 " + globalRemainingMinutes
                        + " 分钟）与账号 16h CD（余约 " + remHrs + " 小时）均未解除，待 CD 解除后再做判断";
            }
            if (globalCdActive) {
                return "全局 CD 冷却中（距我账号上一条推文不足 1小时+" + globalJitterMinutes
                        + "分钟，还差约 " + globalRemainingMinutes + " 分钟），待 CD 解除后再做判断";
            }
            if (authorCdActive) {
                long remHrs = Math.max(1L, (authorRemainingMinutes + 59L) / 60L);
                return "账号 CD 冷却中（16 小时内已转载过该作者，还差约 " + remHrs + " 小时），待 CD 解除后再做判断";
            }
            return "";
        }

        ObjectNode toJsonNode() {
            ObjectNode root = object()
                    .put("anyCdActive", anyActive())
                    .put("canEvaluateNow", !anyActive())
                    .put("blockingCd", blockingType())
                    .put("nextReadyAtMs", nextReadyAtMs())
                    .put("reason", blockingReasonText());
            root.putObject("globalCd")
                    .put("label", "全局 CD")
                    .put("active", globalCdActive)
                    .put("baseHours", 1)
                    .put("jitterMinutes", globalJitterMinutes)
                    .put("requiredMinutes", globalRequiredMinutes)
                    .put("remainingMinutes", globalRemainingMinutes)
                    .put("readyAtMs", globalReadyAtMs);
            root.putObject("authorCd")
                    .put("label", "账号 CD")
                    .put("active", authorCdActive)
                    .put("requiredHours", 16)
                    .put("remainingMinutes", authorRemainingMinutes)
                    .put("readyAtMs", authorReadyAtMs);
            return root;
        }
    }

    private DualCooldown computeDualCooldownFromContext(AuthorPreviewContext ctx, long nowMs) {
        long latestOwnerAt = ctx == null ? 0L : ctx.latestOwnerTweetAtMs();
        long ownerRequiredGapMs = ctx == null ? resolveOwnerTweetRequiredGapMs(0L) : ctx.ownerRequiredGapMs();
        int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerAt);
        boolean globalActive = ownerRequiredGapMs > 0L && latestOwnerAt > 0L
                && nowMs - latestOwnerAt >= 0L && nowMs - latestOwnerAt < ownerRequiredGapMs;
        long globalRemMin = globalActive
                ? Math.max(1L, (ownerRequiredGapMs - (nowMs - latestOwnerAt) + 59_999L) / 60_000L)
                : 0L;
        long globalReadyAt = globalActive ? (latestOwnerAt + ownerRequiredGapMs) : 0L;

        long latestAuthorAt = ctx == null ? 0L : ctx.sameAuthorActiveRepostTimesByMaterialId().values().stream()
                .mapToLong(Long::longValue).max().orElse(0L);
        boolean authorActive = latestAuthorAt > 0L && nowMs - latestAuthorAt >= 0L
                && nowMs - latestAuthorAt < SAME_AUTHOR_COOLDOWN_MS;
        long authorRemMin = authorActive
                ? Math.max(1L, (SAME_AUTHOR_COOLDOWN_MS - (nowMs - latestAuthorAt) + 59_999L) / 60_000L)
                : 0L;
        long authorReadyAt = authorActive ? (latestAuthorAt + SAME_AUTHOR_COOLDOWN_MS) : 0L;

        return new DualCooldown(
                globalActive,
                jitterMin,
                ownerRequiredGapMs > 0L ? Math.round(ownerRequiredGapMs / 60_000.0) : 0L,
                globalRemMin,
                globalReadyAt,
                authorActive,
                authorRemMin,
                authorReadyAt
        );
    }

    private DualCooldown computeLiveDualCooldown(
            AssistantStore.Tx tx,
            ObjectNode settings,
            String ownerAccountId,
            String ownerUsername,
            String candidateAuthorId,
            String candidateUsername,
            String excludeMaterialId,
            long nowMs
    ) {
        long latestOwnerAt = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUsername, excludeMaterialId, nowMs);
        long requiredOwnerGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerTweetAtMsForGap(latestOwnerAt));
        int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerAt);
        boolean globalActive = requiredOwnerGapMs > 0L && latestOwnerAt > 0L
                && nowMs - latestOwnerAt >= 0L && nowMs - latestOwnerAt < requiredOwnerGapMs;
        long globalRemMin = globalActive
                ? Math.max(1L, (requiredOwnerGapMs - (nowMs - latestOwnerAt) + 59_999L) / 60_000L)
                : 0L;
        long globalReadyAt = globalActive ? (latestOwnerAt + requiredOwnerGapMs) : 0L;

        ObjectNode recentSameAuthor = findRecentSameAuthorRepost(
                tx.repostActivityForCooldown(nowMs - SAME_AUTHOR_COOLDOWN_MS), candidateAuthorId, candidateUsername, excludeMaterialId, nowMs);
        long latestAuthorAt = recentSameAuthor == null ? 0L : recentSameAuthor.path("publishedAt").asLong(
                recentSameAuthor.path("dispatchedAt").asLong(recentSameAuthor.path("createdAt").asLong(0)));
        boolean authorActive = recentSameAuthor != null && latestAuthorAt > 0L
                && nowMs - latestAuthorAt >= 0L && nowMs - latestAuthorAt < SAME_AUTHOR_COOLDOWN_MS;
        long authorRemMin = authorActive
                ? Math.max(1L, (SAME_AUTHOR_COOLDOWN_MS - (nowMs - latestAuthorAt) + 59_999L) / 60_000L)
                : 0L;
        long authorReadyAt = authorActive ? (latestAuthorAt + SAME_AUTHOR_COOLDOWN_MS) : 0L;

        return new DualCooldown(
                globalActive,
                jitterMin,
                requiredOwnerGapMs > 0L ? Math.round(requiredOwnerGapMs / 60_000.0) : 0L,
                globalRemMin,
                globalReadyAt,
                authorActive,
                authorRemMin,
                authorReadyAt
        );
    }

    private static long latestOwnerTweetAtMsForGap(long ts) {
        return ts;
    }

    private record AuthorPreviewContext(
            String ownerAccountId,
            String ownerUsername,
            ObjectNode storedSource,
            ObjectNode effectiveSource,
            List<ObjectNode> materials,
            java.util.Map<String, ObjectNode> autoRepostsByMaterialId,
            java.util.Map<String, Long> sameAuthorActiveRepostTimesByMaterialId,
            long latestOwnerTweetAtMs,
            long ownerRequiredGapMs,
            boolean postPolishEnabled,
            String postPolishPrompt,
            String styleProfile,
            String preferencesText,
            String generationProvider,
            String generationModel
    ) { }

    static int resolveOwnerTweetJitterMinutes(long latestOwnerTweetAtMs) {
        if (latestOwnerTweetAtMs <= 0L) return 5;
        long z = latestOwnerTweetAtMs ^ (latestOwnerTweetAtMs >>> 33);
        z *= 0xff51afd7ed558ccdL;
        z ^= (z >>> 33);
        z *= 0xc4ceb9fe1a85ec53L;
        z ^= (z >>> 33);
        return OWNER_TWEET_MIN_JITTER_MINUTES + Math.floorMod(z, OWNER_TWEET_MAX_JITTER_MINUTES);
    }

    static long resolveOwnerTweetRequiredGapMs(long latestOwnerTweetAtMs) {
        return OWNER_TWEET_BASE_INTERVAL_MS + resolveOwnerTweetJitterMinutes(latestOwnerTweetAtMs) * 60_000L;
    }

    static long resolveEffectiveOwnerGapMs(ObjectNode settings, long latestOwnerTweetAtMs) {
        if (settings != null && settings.has("autoRepostCooldownMinutes")) {
            long customMin = settings.path("autoRepostCooldownMinutes").asLong(-1L);
            if (customMin == 0L) return 0L;
            if (customMin >= 15L) return customMin * 60_000L;
        }
        return resolveOwnerTweetRequiredGapMs(latestOwnerTweetAtMs);
    }

    long resolveLatestOwnerTweetEpochMs(
            AssistantStore.Tx tx,
            String ownerAccountId,
            String ownerUsername,
            String excludeMaterialId,
            long nowMs
    ) {
        long latest = 0L;
        String cleanExclude = excludeMaterialId == null ? "" : excludeMaterialId.strip();
        String excludePubId = cleanExclude.isBlank() ? "" : ("auto-repost-" + cleanExclude);
        String excludeRewritePubId = cleanExclude.isBlank() ? "" : ("auto-rewrite-" + cleanExclude);
        for (ObjectNode rec : tx.ownerActivity("auto_repost", ownerAccountId, -1)) {
            String recId = rec.path("materialId").asText(rec.path("id").asText("")).strip();
            if (!cleanExclude.isBlank() && cleanExclude.equals(recId)) continue;
            if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(rec.path("status").asText(""))) continue;
            long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0)));
            if (recAt > 0L && recAt <= nowMs) latest = Math.max(latest, recAt);
        }
        for (ObjectNode rec : tx.ownerActivity("auto_rewrite", ownerAccountId, -1)) {
            String recId = rec.path("materialId").asText(rec.path("id").asText("")).strip();
            if (!cleanExclude.isBlank() && cleanExclude.equals(recId)) continue;
            if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(rec.path("status").asText(""))) continue;
            long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0)));
            if (recAt > 0L && recAt <= nowMs) latest = Math.max(latest, recAt);
        }
        for (ObjectNode pub : tx.ownerActivity("publication", ownerAccountId, -1)) {
            String pubMatId = pub.path("materialId").asText("").strip();
            String pubId = pub.path("id").asText("");
            if (!cleanExclude.isBlank() && (cleanExclude.equals(pubMatId)
                    || excludePubId.equals(pubId)
                    || excludeRewritePubId.equals(pubId))) {
                continue;
            }
            if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(pub.path("status").asText(""))) continue;
            long pubAt = pub.path("publishedAt").asLong(pub.path("queueUpdatedAt").asLong(pub.path("updatedAt").asLong(0)));
            if (pubAt > 0L && pubAt <= nowMs) latest = Math.max(latest, pubAt);
        }
        latest = Math.max(latest, tx.lastConfirmedSchedule(ownerAccountId, nowMs));
        if (scheduledTweetService != null && ownerAccountId != null && !ownerAccountId.isBlank()) {
            long schedAt = scheduledTweetService.latestAccountPublishEpochMs(ownerAccountId, ownerUsername);
            if (schedAt > 0L && schedAt <= nowMs) latest = Math.max(latest, schedAt);
        }
        if (communityHubService != null) {
            long selfPostAt = communityHubService.latestCachedOwnerPostEpochMs(ownerAccountId, ownerUsername);
            if (selfPostAt > 0L && selfPostAt <= nowMs) latest = Math.max(latest, selfPostAt);
        }
        ObjectNode liveObserved = latestOwnerObservation(tx, ownerAccountId);
        if (liveObserved != null) {
            long observedAt = liveObserved.path("latestTweetAt").asLong(0L);
            if (observedAt > 0L && observedAt <= nowMs) latest = Math.max(latest, observedAt);
        }
        for (ObjectNode hm : tx.all("history_material")) {
            if (hm.path("selfThreadReply").asBoolean(false)) continue;
            String kind = hm.path("kind").asText("POST");
            if (!Set.of("POST", "QUOTE").contains(kind)) continue;
            if (ownerAccountId == null || ownerAccountId.isBlank() || !ownerAccountId.equals(hm.path("authorId").asText(""))) continue;
            long hmAt = hm.path("publishedAt").asLong(0L);
            if (hmAt > 0L && hmAt <= nowMs) latest = Math.max(latest, hmAt);
        }
        return latest;
    }

    private static boolean sameSourceIdentity(String firstId, String firstHandle, String secondId, String secondHandle) {
        if (!firstId.isBlank() && !secondId.isBlank()) return firstId.equals(secondId);
        return !firstHandle.isBlank() && firstHandle.equalsIgnoreCase(secondHandle);
    }

    static ObjectNode findRecentSameAuthorRepost(
            Iterable<ObjectNode> records,
            String authorId,
            String username,
            String excludeMaterialId,
            long nowMs
    ) {
        String cleanAuthorId = authorId == null ? "" : authorId.strip();
        String cleanUname = username == null ? "" : username.replaceFirst("^@+", "").strip();
        ObjectNode latestMatch = null;
        long latestAt = 0L;
        for (ObjectNode rec : records) {
            String recId = rec.path("materialId").asText(rec.path("id").asText("")).strip();
            if (!excludeMaterialId.isBlank() && excludeMaterialId.equals(recId)) continue;
            String recStatus = rec.path("status").asText("");
            if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) continue;
            String recAuthorId = rec.path("authorId").asText("").strip();
            String recUname = rec.path("username").asText("").replaceFirst("^@+", "").strip();
            boolean samePerson = sameSourceIdentity(cleanAuthorId, cleanUname, recAuthorId, recUname);
            if (!samePerson) continue;
            long recAt = rec.path("publishedAt").asLong(
                    rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0)));
            if (recAt > 0 && nowMs - recAt >= 0 && nowMs - recAt < SAME_AUTHOR_COOLDOWN_MS && recAt > latestAt) {
                latestAt = recAt;
                latestMatch = rec;
            }
        }
        return latestMatch;
    }

    private record ThreePillarEvidence(
            ObjectNode factualEvidence,
            ObjectNode currentHotContext,
            ObjectNode attentionEvidence
    ) { }

    private ThreePillarEvidence buildThreePillarEvidence(
            String materialId,
            ObjectNode source,
            ObjectNode material,
            long nowMs
    ) {
        return store.tx(tx -> {
            String authorId = material.path("authorId").asText(source.path("authorId").asText(source.path("id").asText(""))).strip();
            String username = material.path("username").asText(source.path("username").asText("")).replaceFirst("^@+", "").strip();
            String fullText = AssistantTopics.text(material);
            List<String> links = AssistantTopics.links(material);
            boolean hasQuant = QUANT_FACT_PATTERN.matcher(fullText).find();
            String topicId = materialId.isBlank() ? "" : AssistantTopics.topicOf(tx, materialId);
            ObjectNode ownTopic = topicId.isBlank() ? null : tx.get("topic", topicId);

            java.util.Set<String> topicAuthors = new java.util.LinkedHashSet<>();
            if (!username.isBlank()) topicAuthors.add(username.toLowerCase(Locale.ROOT));
            if (ownTopic != null) {
                for (var member : ownTopic.path("materialIds")) {
                    ObjectNode rel = tx.get("material", member.asText(""));
                    if (rel != null) {
                        String u = rel.path("username").asText("").replaceFirst("^@+", "").strip().toLowerCase(Locale.ROOT);
                        if (!u.isBlank()) topicAuthors.add(u);
                    }
                }
            }

            ObjectNode factual = object()
                    .put("sourceLinkCount", links.size())
                    .put("hasQuantitativeData", hasQuant)
                    .put("hasQuotedPrimarySource", !material.path("quotedText").asText("").isBlank())
                    .put("hasLongArticle", !material.path("articleText").asText("").isBlank())
                    .put("corroboratingAuthorCount", Math.max(1, topicAuthors.size()));
            var linkArr = factual.putArray("links");
            links.stream().limit(3).forEach(linkArr::add);

            // Hot trends over recent 48h across all monitored sources
            long hotCutoff = nowMs - HOT_CONTEXT_WINDOW_MS;
            record HotTopicEntry(String title, int authorCount, double score) { }
            List<HotTopicEntry> hotEntries = new ArrayList<>();
            Map<String, Integer> tagFreq = new java.util.HashMap<>();
            for (ObjectNode t : tx.all("topic")) {
                if ("MERGED".equals(t.path("status").asText("")) || t.path("materialIds").isEmpty()) continue;
                long pubAt = t.path("publishedAt").asLong(0);
                if (pubAt > 0 && pubAt < hotCutoff) continue;
                java.util.Set<String> authors = new java.util.HashSet<>();
                for (var midNode : t.path("materialIds")) {
                    ObjectNode m = tx.get("material", midNode.asText(""));
                    if (m != null) {
                        String u = m.path("username").asText("").replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
                        if (!u.isBlank()) authors.add(u);
                    }
                }
                double tScore = t.path("score").asDouble(50.0);
                String title = truncatePreview(t.path("title").asText(""), 42);
                if (!title.isBlank()) {
                    hotEntries.add(new HotTopicEntry(title, Math.max(1, authors.size()), tScore));
                }
                for (var tagNode : t.path("tags")) {
                    String tag = tagNode.asText("").strip();
                    if (!tag.isBlank()) tagFreq.merge(tag, 1, Integer::sum);
                }
            }
            hotEntries.sort(Comparator.comparingDouble((HotTopicEntry e) -> e.authorCount() * 18.0 + e.score()).reversed());
            ObjectNode hotCtx = object();
            if (ownTopic != null) {
                hotCtx.put("matchedTopicTitle", truncatePreview(ownTopic.path("title").asText(""), 50))
                        .put("matchedTopicAuthorCount", Math.max(1, topicAuthors.size()));
                if (ownTopic.path("score").isNumber()) {
                    hotCtx.put("matchedTopicScore", Math.round(ownTopic.path("score").asDouble()));
                }
            }
            var hotArr = hotCtx.putArray("recentHotTopics48h");
            for (HotTopicEntry e : hotEntries.stream().limit(6).toList()) {
                hotArr.addObject()
                        .put("title", e.title())
                        .put("authorCount", e.authorCount())
                        .put("score", Math.round(e.score()));
            }
            var tagArr = hotCtx.putArray("hotTags48h");
            tagFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(Map.Entry::getKey)
                    .forEach(tagArr::add);

            // Attention & engagement baseline for this author
            List<Long> histViews = new ArrayList<>();
            List<Long> histLikes = new ArrayList<>();
            for (ObjectNode m : tx.all("material")) {
                if (materialId.equals(m.path("id").asText(""))) continue;
                boolean same = sameSourceIdentity(authorId, username, m.path("authorId").asText(""),
                        m.path("username").asText("").replaceFirst("^@+", ""));
                if (!same) continue;
                if (m.path("views").isIntegralNumber() && m.path("views").asLong() > 0) {
                    histViews.add(m.path("views").asLong());
                }
                if (m.path("likes").isIntegralNumber() && m.path("likes").asLong() > 0) {
                    histLikes.add(m.path("likes").asLong());
                }
            }
            long medianViews = medianOf(histViews);
            long medianLikes = medianOf(histLikes);
            long followers = quoteAuthorFollowers(source, material);
            long pubAt = material.path("publishedAt").asLong(0);
            long ageMin = pubAt > 0 ? Math.max(0L, (nowMs - pubAt) / 60_000L) : 0L;
            long curViews = material.path("views").asLong(0);
            long curLikes = material.path("likes").asLong(0);
            long curReposts = material.path("reposts").asLong(0);
            long curReplies = material.path("replies").asLong(0);
            long curBookmarks = material.path("bookmarks").asLong(0);
            boolean freshlyPublished = ageMin <= 10 && curViews <= 0 && curLikes <= 0;

            ObjectNode attention = object()
                    .put("authorFollowersCount", followers)
                    .put("authorVerified", material.path("authorVerified").asBoolean(source.path("verified").asBoolean(false)))
                    .put("ageMinutes", ageMin)
                    .put("freshlyPublished", freshlyPublished)
                    .put("authorMedianViews", medianViews)
                    .put("authorMedianLikes", medianLikes);
            if (curViews > 0) {
                double er = ((curLikes + curReposts * 2.0 + curReplies * 2.0 + curBookmarks * 2.0) * 100.0) / curViews;
                attention.put("weightedEngagementRatePct", Math.round(er * 100.0) / 100.0);
                if (medianViews > 0) {
                    attention.put("viewsVsAuthorBaselineRatio", Math.round((curViews * 100.0) / medianViews) / 100.0);
                }
            }
            if (curLikes > 0 && medianLikes > 0) {
                attention.put("likesVsAuthorBaselineRatio", Math.round((curLikes * 100.0) / medianLikes) / 100.0);
            }
            if (ageMin >= 5 && curLikes > 0) {
                attention.put("likesPerHour", Math.round((curLikes * 60.0) / ageMin));
            }
            return new ThreePillarEvidence(factual, hotCtx, attention);
        });
    }

    private static long medianOf(List<Long> values) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static long quoteAuthorFollowers(ObjectNode source, ObjectNode material) {
        return source.path("followersCount").asLong(material.path("authorFollowersCount").asLong(0L));
    }

    private static String composeOwnerQuoteVoiceProfile(AssistantStore.Tx tx) {
        OwnerQuoteStyleDistiller.resolveOrDistillQuoteStyle(tx);
        return OwnerQuoteStyleDistiller.composeOwnerDistilledPersona(tx);
    }

    private AuthorPreviewContext loadAuthorPreviewContext(String rawSourceKey, ObjectNode req, int limit, boolean rewriteToday) {
        reconcileAcceptedReceipts();
        String key = rawSourceKey == null ? "" : rawSourceKey.replaceFirst("^@+", "").strip();
        if (key.isBlank()) throw new IllegalArgumentException("缺少监听对象标识");

        AuthorPreviewContext initial = store.tx(tx -> {
            ObjectNode owner = tx.ownerBinding();
            String ownerUname = owner != null && !owner.path("username").asText("").isBlank()
                    ? owner.path("username").asText() : "elidlicom";

            ObjectNode foundSource = tx.get("source", key);
            if (foundSource == null || "REMOVED".equals(foundSource.path("status").asText())) {
                foundSource = null;
                for (ObjectNode s : tx.all("source")) {
                    if ("REMOVED".equals(s.path("status").asText())) continue;
                    if (key.equalsIgnoreCase(s.path("username").asText("").replaceFirst("^@+", ""))
                            || key.equals(s.path("authorId").asText(""))) {
                        foundSource = s;
                        break;
                    }
                }
            }
            if (foundSource != null && foundSource.path("authorId").asText("").isBlank())
                foundSource.put("authorId", foundSource.path("id").asText(""));

            String requestedId = req.path("materialId").asText("").strip();
            long now = clock.millis();
            ObjectNode requested = requestedId.isBlank() ? null : tx.rewriteMaterial(requestedId, now);
            boolean matchesRequestedAuthor = requested != null && (key.equals(requested.path("authorId").asText())
                    || key.equalsIgnoreCase(requested.path("username").asText().replaceFirst("^@+", "")));
            String targetAuthorId = foundSource != null
                    ? foundSource.path("authorId").asText(foundSource.path("id").asText(""))
                    : matchesRequestedAuthor ? requested.path("authorId").asText() : "";
            String targetUsername = foundSource != null
                    ? foundSource.path("username").asText("").replaceFirst("^@+", "")
                    : key;

            Map<String,ObjectNode> authorMaterials = new LinkedHashMap<>();
            for (ObjectNode m : tx.all("material")) {
                String mAuthorId = m.path("authorId").asText("");
                String mUname = m.path("username").asText("").replaceFirst("^@+", "");
                boolean match = targetAuthorId.isBlank() ? key.equals(mAuthorId) || targetUsername.equalsIgnoreCase(mUname) : targetAuthorId.equals(mAuthorId);
                if (!match) continue;
                authorMaterials.put(m.path("id").asText(), m);
            }
            for (ObjectNode m : tx.rewriteWindow(targetAuthorId, targetUsername, now - REWRITE_MAX_AGE_MS, now)) {
                String id = m.path("id").asText();
                if (rewriteToday && withinRewriteWindow(m, now)) authorMaterials.put(id, m);
                else authorMaterials.replace(id, m);
            }
            if (matchesRequestedAuthor && (targetAuthorId.isBlank() || SourceCollector.belongsToSource(requested, targetAuthorId)))
                authorMaterials.put(requestedId, requested);
            List<ObjectNode> matchingMaterials = new ArrayList<>(authorMaterials.values());
            matchingMaterials.removeIf(m -> AssistantTopics.text(m).isBlank());
            matchingMaterials.sort(Comparator.comparingLong((ObjectNode m) -> m.path("publishedAt").asLong(0)).reversed());

            if (foundSource == null) {
                if (!matchingMaterials.isEmpty()) {
                    ObjectNode m0 = matchingMaterials.get(0);
                    foundSource = object()
                            .put("id", m0.path("authorId").asText(targetUsername))
                            .put("authorId", m0.path("authorId").asText(""))
                            .put("username", m0.path("username").asText(targetUsername))
                            .put("displayName", m0.path("authorName").asText(targetUsername))
                            .put("avatarUrl", m0.path("authorAvatarUrl").asText(""))
                            .put("verified", m0.path("authorVerified").asBoolean(false))
                            .put("followersCount", m0.path("authorFollowersCount").asLong(0))
                            .put("status", "PREVIEW")
                            .put("autoRepostEnabled", false)
                            .put("autoRepostTextMode", "AI")
                            .put("autoRepostMinScore", SourceCollector.DEFAULT_AUTO_REPOST_MIN_SCORE);
                } else {
                    throw new XDataProviderException(404, "未找到该监听作者：" + key);
                }
            }

            String previewAuthorId = foundSource.path("authorId").asText("");
            if (!previewAuthorId.isBlank()) matchingMaterials.removeIf(m -> !SourceCollector.belongsToSource(m, previewAuthorId));
            List<ObjectNode> topMaterials = new ArrayList<>(matchingMaterials.stream().limit(limit).toList());
            if (!requestedId.isBlank() && topMaterials.stream().noneMatch(m -> requestedId.equals(m.path("id").asText()))) {
                ObjectNode exact = tx.rewriteMaterial(requestedId, clock.millis());
                if (matchesPreviewSource(foundSource, exact) && !AssistantTopics.text(exact).isBlank()) topMaterials.add(exact);
            }
            String ownerAccountId = owner != null ? owner.path("accountId").asText("") : "";
            Map<String, ObjectNode> byMat = quoteTimelineRecords(tx, topMaterials, ownerAccountId);
            java.util.Map<String, Long> activeTimes = new java.util.HashMap<>();
            for (ObjectNode rec : tx.repostActivityForCooldown(clock.millis() - SAME_AUTHOR_COOLDOWN_MS)) {
                String recStatus = rec.path("status").asText("");
                if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) continue;
                String recAid = rec.path("authorId").asText("");
                String recUname = rec.path("username").asText("").replaceFirst("^@+", "");
                boolean same = sameSourceIdentity(targetAuthorId, targetUsername, recAid, recUname);
                if (!same) continue;
                String recMid = rec.path("materialId").asText(rec.path("id").asText(""));
                long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("sourcePublishedAt").asLong(0)));
                if (!recMid.isBlank() && recAt > 0) activeTimes.merge(recMid, recAt, Math::max);
            }

            ObjectNode normalizedSource = foundSource.deepCopy();
            normalizedSource.put("autoRepostMinScore", SourceCollector.resolveAutoRepostMinScore(normalizedSource));
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            ObjectNode settings = AssistantWorkflow.settings(tx);
            long previewNowMs = clock.millis();
            long latestOwnerTweetAtMs = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUname, "", previewNowMs);
            long ownerRequiredGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerTweetAtMs);
            boolean polishEnabled = AssistantWorkflow.isPostPolishEnabled(settings);
            String polishPrompt = AssistantWorkflow.resolvePostPolishPrompt(settings);
            var repostMod = settings.path("moduleConfigs").path("repost");
            String cfgRepostProv = repostMod.path("provider").asText("").strip();
            String defaultRepostProv = AssistantConnection.isSupportedProvider(cfgRepostProv)
                    ? AssistantConnection.normalizeProvider(cfgRepostProv)
                    : AssistantConnection.DEFAULT_REPOST_PROVIDER;
            String defaultRepostModel = repostMod.path("model").asText("").strip();
            if (defaultRepostModel.isBlank()) {
                defaultRepostModel = AssistantConnection.defaultModelForProvider(defaultRepostProv);
            }
            return new AuthorPreviewContext(
                    ownerAccountId,
                    ownerUname,
                    normalizedSource.deepCopy(),
                    normalizedSource,
                    topMaterials,
                    byMat,
                    activeTimes,
                    latestOwnerTweetAtMs,
                    ownerRequiredGapMs,
                    polishEnabled,
                    polishPrompt,
                    composeOwnerQuoteVoiceProfile(tx),
                    prefs.path("text").asText(""),
                    defaultRepostProv,
                    defaultRepostModel
            );
        });

        ObjectNode effectiveSource = initial.effectiveSource();
        String rawGenProv = req.path("generationProvider").asText("").strip();
        String effectiveGenProv = initial.generationProvider();
        if (!rawGenProv.isEmpty()) {
            String norm = AssistantConnection.normalizeProvider(rawGenProv);
            if (!Set.of("chatgpt", "openai", "google", "minimax", "deepseek").contains(norm)) {
                throw new IllegalArgumentException("不支持的转发评述生成渠道: " + rawGenProv);
            }
            effectiveGenProv = norm;
        }
        String rawGenModel = req.path("generationModel").asText("").strip();
        if (rawGenModel.length() > 160) {
            throw new IllegalArgumentException("生成模型名称过长");
        }
        String effectiveGenModel = !rawGenModel.isEmpty()
                ? rawGenModel
                : (!rawGenProv.isEmpty() ? AssistantConnection.defaultModelForProvider(effectiveGenProv) : initial.generationModel());
        boolean effectivePolishEnabled = req.has("postPolishEnabled") && req.path("postPolishEnabled").isBoolean()
                ? req.path("postPolishEnabled").asBoolean()
                : initial.postPolishEnabled();
        String effectivePolishPrompt = req.has("postPolishPrompt")
                ? OwnerQuoteStyleDistiller.normalizePolishPrompt(req.path("postPolishPrompt").asText(""))
                : initial.postPolishPrompt();
        if (req.has("autoRepostEnabled") && req.path("autoRepostEnabled").isBoolean()) {
            effectiveSource.put("autoRepostEnabled", req.path("autoRepostEnabled").asBoolean());
        }
        if (req.has("autoRepostTextMode")) {
            String m = req.path("autoRepostTextMode").asText("").strip().toUpperCase(Locale.ROOT);
            if (Set.of("AI", "TEMPLATE").contains(m)) effectiveSource.put("autoRepostTextMode", m);
        }
        if (req.has("autoRepostTemplate")) {
            effectiveSource.put("autoRepostTemplate", req.path("autoRepostTemplate").asText("").strip());
        }
        if (req.has("autoRepostRuleHint")) {
            effectiveSource.put("autoRepostRuleHint", req.path("autoRepostRuleHint").asText("").strip());
        }
        if (req.has("autoRepostMinScore") && req.path("autoRepostMinScore").isIntegralNumber()) {
            int rawReqScore = req.path("autoRepostMinScore").asInt(SourceCollector.DEFAULT_AUTO_REPOST_MIN_SCORE);
            int sc = (!initial.storedSource().path("autoRepostMinScoreCustomized").asBoolean(false) && rawReqScore == 82)
                    ? SourceCollector.DEFAULT_AUTO_REPOST_MIN_SCORE
                    : Math.max(50, Math.min(99, rawReqScore));
            effectiveSource.put("autoRepostMinScore", sc);
        }

        List<ObjectNode> candidates = new ArrayList<>(initial.materials());
        long now = clock.millis();
        boolean fetchPreview = candidates.isEmpty();
        if (rewriteToday) {
            long checkedAt = Math.max(initial.storedSource().path("rewritePreviewCheckedAt").asLong(),
                    initial.storedSource().path("lastSuccessAt").asLong());
            boolean browsingToday = req.path("rewriteToday").asBoolean() || req.path("materialId").asText().isBlank();
            fetchPreview = (fetchPreview || browsingToday)
                    && (checkedAt < rewriteDayStart(now) || now - checkedAt >= 5L * 60_000L)
                    && candidates.stream().noneMatch(m -> withinRewriteWindow(m, now));
        }
        if (sources != null && fetchPreview) {
            List<ObjectNode> fetched = sources.fetchOnDemandPreviewMaterials(effectiveSource);
            if (rewriteToday) fetched = retainTodayPreviewMaterials(initial, fetched, now);
            var previewMaterials = fetched.stream().filter(m -> matchesPreviewSource(effectiveSource, m)).limit(limit).toList();
            candidates.addAll(0, previewMaterials);
            if (candidates.size() > limit) candidates.subList(limit, candidates.size()).clear();
            initial.autoRepostsByMaterialId().putAll(store.tx(tx -> quoteTimelineRecords(tx, candidates, initial.ownerAccountId())));
        }
        return new AuthorPreviewContext(
                initial.ownerAccountId(),
                initial.ownerUsername(),
                initial.storedSource(),
                effectiveSource,
                candidates,
                initial.autoRepostsByMaterialId(),
                initial.sameAuthorActiveRepostTimesByMaterialId(),
                initial.latestOwnerTweetAtMs(),
                initial.ownerRequiredGapMs(),
                effectivePolishEnabled,
                effectivePolishPrompt,
                initial.styleProfile(),
                initial.preferencesText(),
                effectiveGenProv,
                effectiveGenModel
        );
    }

    private List<ObjectNode> retainTodayPreviewMaterials(AuthorPreviewContext ctx, List<ObjectNode> fetched, long startedAt) {
        return store.tx(tx -> {
            if (!boundPublisherMatches(tx, null, ctx.ownerAccountId())) return List.of();
            ObjectNode source = ctx.storedSource();
            String authorId = source.path("authorId").asText(source.path("id").asText());
            ObjectNode live = tx.get("source", authorId);
            if (live == null && !"PREVIEW".equals(source.path("status").asText())
                    || live != null && ("REMOVED".equals(live.path("status").asText())
                    || !live.path("instanceId").equals(source.path("instanceId")))) return List.of();
            long finishedAt = clock.millis();
            if (rewriteDayStart(startedAt) != rewriteDayStart(finishedAt)) return List.of();
            List<ObjectNode> observed = new ArrayList<>();
            for (ObjectNode m : fetched) {
                String id = m.path("id").asText();
                if (id.isBlank() || !matchesPreviewSource(source, m) || !withinRewriteWindow(m, finishedAt)) continue;
                // A listener may have collected a correction while the preview request was in flight.
                var canonical = tx.lastObservedRewriteMaterial(id);
                if (canonical == null) {
                    tx.cacheRewriteMaterial(m, "");
                    canonical = m;
                }
                if (matchesPreviewSource(source, canonical) && withinRewriteWindow(canonical, finishedAt)) observed.add(canonical);
            }
            if (live != null) tx.put("source", authorId, live.put("rewritePreviewCheckedAt", finishedAt));
            return observed;
        });
    }

    private static boolean matchesPreviewSource(ObjectNode source, ObjectNode material) {
        if (source == null || material == null) return false;
        String authorId = source.path("authorId").asText("");
        if (!authorId.isBlank()) return SourceCollector.belongsToSource(material, authorId);
        String handle = source.path("username").asText("").replaceFirst("^@+", "");
        return !handle.isBlank() && handle.equalsIgnoreCase(material.path("username").asText("").replaceFirst("^@+", ""));
    }

    private Map<String,ObjectNode> quoteTimelineRecords(AssistantStore.Tx tx, List<ObjectNode> materials, String ownerId) {
        Map<String,ObjectNode> records = new java.util.HashMap<>();
        List<ObjectNode> rows = quoteRows(tx, materials, ownerId);
        for (ObjectNode material : materials) {
            ObjectNode record = quoteTimelineRecord(material, rows);
            if (record != null) records.put(material.path("id").asText(), record);
        }
        return records;
    }

    private List<ObjectNode> quoteRows(AssistantStore.Tx tx, List<ObjectNode> materials, String ownerId) {
        Set<String> keys = new java.util.HashSet<>();
        for (ObjectNode material : materials) {
            keys.add(material.path("id").asText());
            String original = originalQuoteId(material);
            if (original != null) keys.add(original);
        }
        List<ObjectNode> rows = new ArrayList<>(tx.sourceQuoteRows(keys, ownerId));
        for (ObjectNode accepted : pendingAcceptedReceipts.values()) {
            if ((keys.contains(accepted.path("materialId").asText()) || keys.contains(accepted.path("requestedQuoteTweetId").asText()))
                    && !accepted.path("source").asText("").startsWith("AUTO_REWRITE") && tx.publicationOwnerMatches(accepted, ownerId))
                rows.add(accepted);
        }
        return rows;
    }

    private static String originalQuoteId(ObjectNode material) {
        String id = material.path("id").asText();
        return id.matches("[0-9]{2,20}") ? id : extractStatusIdFromUrl(material.path("sourceUrl").asText(""));
    }

    private static boolean quoteMatchesMaterial(ObjectNode row, ObjectNode material, String originalId) {
        String author = material.path("authorId").asText("");
        String recordedAuthor = row.path("authorId").asText("");
        if (!author.isBlank() && !recordedAuthor.isBlank() && !author.equals(recordedAuthor)) return false;
        String requested = AssistantStore.Tx.firstField(row, "requestedQuoteTweetId", "quoteTweetId", "quotedTweetId");
        if (originalId != null && !requested.isBlank()) return originalId.equals(requested);
        String id = row.path("id").asText();
        String recordedMaterial = row.path("materialId").asText("");
        if (recordedMaterial.isBlank() && row.path("quoteProjection").asBoolean()) recordedMaterial = id;
        if (recordedMaterial.isBlank() && id.startsWith("auto-repost-")) recordedMaterial = id.substring("auto-repost-".length());
        return material.path("id").asText().equals(recordedMaterial) || recordedMaterial.equals(originalId);
    }

    private static long quoteAttemptTime(ObjectNode row) {
        long at = Math.max(row.path("dispatchedAt").asLong(), row.path("publishedAt").asLong());
        return at > 0 ? at : row.path("createdAt").asLong(row.path("updatedAt").asLong());
    }

    private static ObjectNode quoteTimelineRecord(ObjectNode material, List<ObjectNode> rows) {
        String original = originalQuoteId(material);
        ObjectNode evaluation = null, delivery = null, released = null;
        long revision = 0;
        // Heads arrive in database write order. A failed attempt only releases that attempt's lock.
        for (ObjectNode row : rows) {
            if (!quoteMatchesMaterial(row, material, original)) continue;
            boolean projection = row.path("quoteProjection").asBoolean();
            if (projection && material.path("id").equals(row.path("id"))) evaluation = row;
            String status = row.path("status").asText("");
            if (row.path("uncertainDelivery").asBoolean() && !"PUBLISHED".equals(status)) status = "UNCERTAIN";
            if (QUOTE_DELIVERY_STATUSES.contains(status)) {
                if (delivery == null || quoteAttemptTime(row) >= quoteAttemptTime(delivery))
                    delivery = row.deepCopy().put("status", status);
            } else if (!projection || row.hasNonNull("verifiedAt") || row.hasNonNull("dispatchedAt")) {
                released = row;
            } else continue;
            revision = Math.max(revision, row.path("receiptRevision").asLong());
        }
        if (evaluation == null && delivery == null && released == null) return null;
        ObjectNode record = evaluation == null ? object() : evaluation.deepCopy();
        record.put("receiptRevision", revision);
        if (delivery != null) {
            record.put("status", delivery.path("status").asText())
                    .put("publishedTweetUrl", delivery.path("publishedTweetUrl").asText(""))
                    .put("publishedAt", delivery.path("publishedAt").asLong())
                    .put("publicationMode", delivery.path("publicationMode").asText(""))
                    .put("text", AssistantStore.Tx.firstField(delivery, "text", "requestedText", "commentText"))
                    .put("receiptComment", AssistantStore.Tx.firstField(delivery, "requestedText", "commentText", "text"));
        } else if (evaluation == null) {
            record.put("status", released.path("status").asText());
        }
        return record;
    }

    static String previewMaterialFingerprint(ObjectNode material) {
        ObjectNode inputs = object();
        for (String field : List.of("id", "authorId", "username", "authorName", "authorBio", "authorAvatarUrl", "authorVerified",
                "kind", "text", "quotedText", "articleText", "textStatus", "authorTextStatus", "quotedTextStatus", "articleTextStatus",
                "quotedId", "quotedAuthorId", "quotedUsername", "articleId", "articleUrl", "articleRole", "sourceUrl",
                "hasMedia", "media", "links", "publishedAt", "views", "likes", "reposts", "replies", "bookmarks", "authorFollowersCount")) {
            if (material.has(field)) inputs.set(field, material.get(field));
        }
        return AssistantWorkflow.digest(inputs);
    }

    static boolean previewMatchesMaterial(ObjectNode decision, ObjectNode material) {
        String fingerprint = decision.path("previewMaterialFingerprint").asText("");
        return fingerprint.isEmpty() ? material.path("version").asInt(1) <= 1
                : fingerprint.equals(previewMaterialFingerprint(material));
    }

    static boolean legacyPreviewMaterialFingerprintMatches(String fingerprint, ObjectNode material) {
        var previousInputs = material.deepCopy();
        previousInputs.remove(List.of("bookmarks", "authorFollowersCount"));
        return previewMaterialFingerprint(previousInputs).equals(fingerprint);
    }

    static String quoteSourceFingerprint(ObjectNode material) {
        ObjectNode inputs = object();
        for (String field : List.of("id", "authorId", "kind", "text", "quotedId", "quotedText", "quotedAuthorId",
                "articleId", "articleText", "articleRole", "selfThreadReply", "links", "hasMedia", "media")) {
            if (material.has(field)) inputs.set(field, material.get(field));
        }
        return AssistantWorkflow.digest(inputs);
    }

    static boolean matchesEvaluatedQuote(AssistantStore.Tx tx, ObjectNode record, ObjectNode material) {
        if (record.path("quoteSourceNeedsReview").asBoolean()) return false;
        if (material == null) return true;
        String current = quoteSourceFingerprint(material);
        String saved = record.path("quoteSourceFingerprint").asText();
        if (!saved.isBlank()) return saved.equals(current);
        String legacy = record.path("previewMaterialFingerprint").asText();
        return legacy.isBlank() || sameSavedPreviewInput(legacy, material)
                || tx.versions("material", material.path("id").asText()).stream()
                        .anyMatch(old -> sameSavedPreviewInput(legacy, old) && current.equals(quoteSourceFingerprint(old)));
    }

    static String rewriteSourceFingerprint(ObjectNode material) {
        return AssistantWorkflow.digest(object().put("id", material.path("id").asText())
                .put("authorId", material.path("authorId").asText()).put("text", extractPureRewriteSourceText(material)));
    }

    static boolean matchesFinalRewriteSource(AssistantStore.Tx tx, ObjectNode record, ObjectNode material) {
        if (!isPureTextRewriteCandidate(material)) return false;
        String current = rewriteSourceFingerprint(material);
        String saved = record.path("rewriteSourceFingerprint").asText();
        if (!saved.isBlank()) return saved.equals(current);
        String previous = record.path("previewMaterialFingerprint").asText();
        if (sameSavedPreviewInput(previous, material)) return true;
        List<ObjectNode> versions = tx.versions("material", material.path("id").asText());
        if (previous.isBlank()) {
            if (versions.isEmpty()) return false;
            List<ObjectNode> drafts = tx.versions("auto_rewrite", material.path("id").asText());
            if (drafts.isEmpty()) return false;
            ObjectNode firstDraft = drafts.get(drafts.size() - 1);
            long draftedAt = firstDraft.path("createdAt").asLong(firstDraft.path("updatedAt").asLong());
            return versions.get(versions.size() - 1).path("updatedAt").asLong(Long.MAX_VALUE) <= draftedAt
                    && versions.stream().allMatch(v -> current.equals(rewriteSourceFingerprint(v)));
        }
        return versions.stream().anyMatch(v -> sameSavedPreviewInput(previous, v) && current.equals(rewriteSourceFingerprint(v)));
    }

    static boolean sameSavedPreviewInput(String saved, ObjectNode material) {
        return !saved.isBlank() && (saved.equals(previewMaterialFingerprint(material)) || legacyPreviewMaterialFingerprintMatches(saved, material));
    }

    private static String buildPreviewCacheKey(ObjectNode material, AuthorPreviewContext ctx) {
        ObjectNode source = ctx.effectiveSource();
        String textMode = source.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        int minScore = SourceCollector.resolveAutoRepostMinScore(source);
        String tpl = source.path("autoRepostTemplate").asText("").strip();
        String ruleHint = source.path("autoRepostRuleHint").asText("").strip();
        String activePolish = ctx.postPolishEnabled() ? ctx.postPolishPrompt().strip() : "";
        return ctx.ownerAccountId() + "|" + previewMaterialFingerprint(material) + "|" + quoteAuthorFollowers(source, material)
                + "|" + textMode + "|" + minScore + "|" + tpl + "|" + ruleHint + "|"
                + ctx.generationProvider() + ":" + ctx.generationModel() + "|" + ctx.styleProfile().hashCode()
                + "|" + ctx.preferencesText().hashCode() + "|" + activePolish.hashCode();
    }

    private ObjectNode cachedQuotePreview(String key) {
        QuotePreview cached = previewCache.get(key);
        return cached == null ? null : cached.result();
    }

    private void beginQuotePreview(String key, long requestOrder) {
        previewCache.compute(key, (ignored, previous) -> previous != null && previous.requestOrder() > requestOrder
                ? previous : new QuotePreview(requestOrder, previous == null ? null : previous.result()));
    }

    private boolean saveQuotePreview(String key, long requestOrder, ObjectNode result) {
        var replacement = new QuotePreview(requestOrder, result);
        return previewCache.computeIfPresent(key, (ignored, reserved) ->
                reserved.requestOrder() == requestOrder ? replacement : reserved) == replacement;
    }

    private String buildRewritePreviewCacheKey(
            ObjectNode material,
            String styleProfile,
            String preferencesText,
            boolean postPolishEnabled,
            String postPolishPrompt,
            String generationProvider,
            String generationModel
    ) {
        String activePolish = postPolishEnabled ? (postPolishPrompt == null ? "" : postPolishPrompt.strip()) : "";
        String genKey = (generationProvider == null || generationProvider.isBlank() ? "chatgpt" : generationProvider)
                + ":" + (generationModel == null ? "" : generationModel.strip());
        return "rw-v2|" + rewriteSourceFingerprint(material) + "|" + previewMaterialFingerprint(material) + "|" + genKey + "|"
                + styleProfile.hashCode() + "|" + preferencesText.hashCode() + "|" + activePolish.hashCode();
    }

    static String classifyDecisionCode(
            String persistedStatus,
            boolean authorCooldownBlocked,
            boolean wouldRepost,
            boolean viralPass,
            boolean accountFit
    ) {
        if (persistedStatus != null && QUOTE_DELIVERY_STATUSES.contains(persistedStatus)) return persistedStatus;
        if ("SKIPPED_AUTHOR_COOLDOWN".equals(persistedStatus) || authorCooldownBlocked) return "SKIP_AUTHOR_COOLDOWN";
        if ("SKIPPED_DUPLICATE".equals(persistedStatus)) return "SKIP_DUPLICATE";
        if ("SKIPPED_LIMIT".equals(persistedStatus)) return "SKIP_LIMIT";
        if ("SKIPPED_COOLDOWN".equals(persistedStatus)) return "SKIP_COOLDOWN";
        if (wouldRepost) return "WOULD_REPOST";
        if (viralPass && !accountFit) return "SKIP_NOT_FIT";
        if (!viralPass && accountFit) return "SKIP_LOW_HEAT";
        return "SKIP_BOTH";
    }

    private ObjectNode buildSourceSummaryNode(ObjectNode effectiveSource, AuthorPreviewContext ctx) {
        String textMode = effectiveSource.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        int minScore = SourceCollector.resolveAutoRepostMinScore(effectiveSource);
        long nowMs = clock.millis();
        long latestSameAuthorAt = ctx == null ? 0L : ctx.sameAuthorActiveRepostTimesByMaterialId().values().stream()
                .mapToLong(Long::longValue).max().orElse(0L);
        boolean authorIn16hCooldown = latestSameAuthorAt > 0 && nowMs - latestSameAuthorAt >= 0
                && nowMs - latestSameAuthorAt < SAME_AUTHOR_COOLDOWN_MS;
        long cooldownRemainingMinutes = authorIn16hCooldown
                ? Math.max(1L, (SAME_AUTHOR_COOLDOWN_MS - (nowMs - latestSameAuthorAt) + 59_999L) / 60_000L)
                : 0L;
        long latestOwnerAt = ctx == null ? 0L : ctx.latestOwnerTweetAtMs();
        long ownerRequiredGapMs = ctx == null ? resolveOwnerTweetRequiredGapMs(0L) : ctx.ownerRequiredGapMs();
        int ownerJitterMin = resolveOwnerTweetJitterMinutes(latestOwnerAt);
        boolean ownerInTweetCooldown = ownerRequiredGapMs > 0L && latestOwnerAt > 0L
                && nowMs - latestOwnerAt >= 0L && nowMs - latestOwnerAt < ownerRequiredGapMs;
        long ownerCooldownRemainingMinutes = ownerInTweetCooldown
                ? Math.max(1L, (ownerRequiredGapMs - (nowMs - latestOwnerAt) + 59_999L) / 60_000L)
                : 0L;
        return object()
                .put("id", effectiveSource.path("id").asText(effectiveSource.path("authorId").asText("")))
                .put("username", effectiveSource.path("username").asText(""))
                .put("displayName", effectiveSource.path("displayName").asText(effectiveSource.path("username").asText("")))
                .put("avatarUrl", effectiveSource.path("avatarUrl").asText(""))
                .put("verified", effectiveSource.path("verified").asBoolean(false))
                .put("followersCount", effectiveSource.path("followersCount").asLong(0))
                .put("status", effectiveSource.path("status").asText("ACTIVE"))
                .put("intervalMinutes", effectiveSource.path("intervalMinutes").asInt(5))
                .put("autoRepostEnabled", effectiveSource.path("autoRepostEnabled").asBoolean(false))
                .put("autoRewriteEnabled", effectiveSource.path("autoRewriteEnabled").asBoolean(false))
                .put("textMode", textMode)
                .put("minScore", minScore)
                .put("ruleHint", effectiveSource.path("autoRepostRuleHint").asText("").strip())
                .put("template", effectiveSource.path("autoRepostTemplate").asText("").strip())
                .put("sameAuthorCooldownHours", 16)
                .put("authorInCooldown", authorIn16hCooldown)
                .put("authorCooldownRemainingMinutes", cooldownRemainingMinutes)
                .put("ownerTweetJitterMinutes", ownerJitterMin)
                .put("ownerTweetRequiredMinutes", ownerRequiredGapMs > 0L ? Math.round(ownerRequiredGapMs / 60_000.0) : 0L)
                .put("ownerInTweetCooldown", ownerInTweetCooldown)
                .put("ownerCooldownRemainingMinutes", ownerCooldownRemainingMinutes)
                .put("postPolishEnabled", ctx == null || ctx.postPolishEnabled())
                .put("postPolishPrompt", ctx == null ? OwnerQuoteStyleDistiller.DEFAULT_POST_POLISH_PROMPT : ctx.postPolishPrompt())
                .put("repostProvider", ctx == null ? AssistantConnection.DEFAULT_REPOST_PROVIDER : ctx.generationProvider())
                .put("repostModel", ctx == null ? AssistantConnection.DEFAULT_REPOST_MODEL : ctx.generationModel());
    }

    private static ObjectNode applyQuoteReceipt(ObjectNode out, ObjectNode record) {
        String status = record == null ? "" : record.path("status").asText("");
        out.put("persistedStatus", status).put("receiptRevision", record == null ? 0 : record.path("receiptRevision").asLong());
        if (!QUOTE_DELIVERY_STATUSES.contains(status)) return out;
        boolean published = "PUBLISHED".equals(status);
        String requestedText = record.path("receiptComment").asText("");
        if (!published || out.path("commentText").asText("").isBlank()) out.put("commentText", requestedText);
        return out.put("decisionCode", status).put("wouldAutoRepost", published).put("evaluated", true)
                .put("cdShortCircuited", false).put("authorCooldownBlocked", false).put("ownerCooldownBlocked", false)
                .put("publishedTweetUrl", published ? record.path("publishedTweetUrl").asText("") : "")
                .put("publicationMode", record.path("publicationMode").asText(""))
                .put("publishedText", record.path("text").asText(""))
                .put("reason", switch (status) {
                    case "SENDING" -> "引用转发正在发送";
                    case "UNCERTAIN" -> "引用转发回执待核实，请在活动记录核对后登记结果";
                    default -> "";
                });
    }

    private ObjectNode applySingleItemAuthorCooldownOverlay(
            ObjectNode decision,
            String materialId,
            long materialPublishedAt,
            AuthorPreviewContext ctx
    ) {
        ObjectNode publication = ctx.autoRepostsByMaterialId().get(materialId);
        ObjectNode out = applyQuoteReceipt(decision.deepCopy(), publication);
        String persisted = out.path("persistedStatus").asText("");
        if (QUOTE_DELIVERY_STATUSES.contains(persisted)) return out;
        boolean strictAuthorLock = strictRepostAuthorLock(publication);
        out.put("strictSixteenHourAuthorLock", strictAuthorLock);
        boolean contentPass = out.path("contentPass").asBoolean(
                out.path("viralPass").asBoolean(false) && out.path("accountFit").asBoolean(false));
        boolean directPass = PiAgentViralJudge.isDirectPassCategory(out.path("category").asText(""));
        boolean exemptAuthorCd = directPass && !strictAuthorLock;
        out.put("contentPass", contentPass).put("directPass", directPass && contentPass)
                .put("authorCooldownExempt", false);
        boolean genuinelyEvaluated = out.path("evaluated").asBoolean(false) && out.path("viralScore").asInt(0) > 0;
        if (!contentPass && (genuinelyEvaluated || (!"SKIPPED_AUTHOR_COOLDOWN".equals(persisted) && !"SKIPPED_COOLDOWN".equals(persisted)))) {
            return out;
        }

        long nowMs = clock.millis();
        long refTime = materialPublishedAt > 0 ? materialPublishedAt : nowMs;
        boolean conflictWithPublished = false;
        for (var entry : ctx.sameAuthorActiveRepostTimesByMaterialId().entrySet()) {
            if (materialId.equals(entry.getKey())) continue;
            long activeAt = entry.getValue();
            if (Math.abs(refTime - activeAt) < SAME_AUTHOR_COOLDOWN_MS
                    || (nowMs - activeAt >= 0 && nowMs - activeAt < SAME_AUTHOR_COOLDOWN_MS)) {
                conflictWithPublished = true;
                break;
            }
        }
        if (!exemptAuthorCd && (conflictWithPublished || "SKIPPED_AUTHOR_COOLDOWN".equals(persisted))) {
            String baseReason = out.path("baseReason").asText(out.path("reason").asText(""));
            out.put("baseReason", baseReason)
                    .put("wouldAutoRepost", false)
                    .put("authorCooldownBlocked", true)
                    .put("decisionCode", "SKIP_AUTHOR_COOLDOWN")
                    .put("reason", baseReason.isBlank() || baseReason.contains("16 小时")
                            ? "16 小时内已转发过该作者，避免连续转发同一人"
                            : "内容达标，但 16 小时内已转发过该作者（" + baseReason + "）");
            return out;
        }
        if (exemptAuthorCd && contentPass && conflictWithPublished) {
            out.put("authorCooldownExempt", true);
        }

        long latestOwnerAt = ctx.latestOwnerTweetAtMs();
        long requiredOwnerGapMs = ctx.ownerRequiredGapMs();
        boolean ownerGapBlocked = requiredOwnerGapMs > 0L && latestOwnerAt > 0L
                && nowMs - latestOwnerAt >= 0L && nowMs - latestOwnerAt < requiredOwnerGapMs;
        if (ownerGapBlocked || "SKIPPED_COOLDOWN".equals(persisted)) {
            int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerAt);
            long remMin = ownerGapBlocked
                    ? Math.max(1L, (requiredOwnerGapMs - (nowMs - latestOwnerAt) + 59_999L) / 60_000L)
                    : 0L;
            String baseReason = out.path("baseReason").asText(out.path("reason").asText(""));
            String gapHint = ownerGapBlocked
                    ? "距我账号上一条推文不足 1小时+" + jitterMin + "分钟（还差约 " + remMin + " 分钟）"
                    : "触发我账号 1小时+N分钟 发推间隔保护";
            out.put("baseReason", baseReason)
                    .put("wouldAutoRepost", false)
                    .put("ownerCooldownBlocked", true)
                    .put("decisionCode", "SKIP_COOLDOWN")
                    .put("reason", baseReason.isBlank() || baseReason.contains("上一条推文") || baseReason.contains("频控")
                            ? gapHint
                            : "内容达标，但" + gapHint + "（" + baseReason + "）");
        }
        return out;
    }

    private ObjectNode buildMaterialSummaryNode(ObjectNode m, ObjectNode effectiveSource) {
        ObjectNode matNode = object()
                .put("id", m.path("id").asText(""))
                .put("authorId", m.path("authorId").asText(effectiveSource.path("authorId").asText("")))
                .put("sourceFingerprint", quoteSourceFingerprint(m))
                .put("username", m.path("username").asText(effectiveSource.path("username").asText("")))
                .put("authorName", m.path("authorName").asText(effectiveSource.path("displayName").asText("")))
                .put("authorAvatarUrl", m.path("authorAvatarUrl").asText(effectiveSource.path("avatarUrl").asText("")))
                .put("authorVerified", m.path("authorVerified").asBoolean(effectiveSource.path("verified").asBoolean(false)))
                .put("kind", m.path("kind").asText("POST"))
                .put("hasMedia", m.path("hasMedia").asBoolean(false))
                .put("text", m.path("text").asText(""))
                .put("quotedText", m.path("quotedText").asText(""))
                .put("articleText", truncatePreview(m.path("articleText").asText(""), 280))
                .put("publishedAt", m.path("publishedAt").asLong(0))
                .put("sourceUrl", m.path("sourceUrl").asText(""));
        for (String metricKey : List.of("views", "likes", "reposts", "replies")) {
            if (m.path(metricKey).isIntegralNumber()) {
                matNode.put(metricKey, m.path(metricKey).asLong());
            }
        }
        return matNode;
    }

    private ObjectNode resolveStoredDecisionIfAvailable(
            ObjectNode m,
            AuthorPreviewContext ctx
    ) {
        String materialId = m.path("id").asText("");
        ObjectNode effectiveSource = ctx.effectiveSource();
        String textMode = effectiveSource.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = SourceCollector.resolveAutoRepostMinScore(effectiveSource);
        String ruleHint = effectiveSource.path("autoRepostRuleHint").asText("").strip();
        String cacheKey = buildPreviewCacheKey(m, ctx);

        ObjectNode cached = cachedQuotePreview(cacheKey);
        if (cached != null && cached.has("preview")) {
            ObjectNode decision = cached.path("preview").deepCopy();
            decision.put("evaluated", true).put("cached", true);
            return applySingleItemAuthorCooldownOverlay(decision, materialId, m.path("publishedAt").asLong(0), ctx);
        }

        boolean sameRuleHint = ruleHint.equals(ctx.storedSource().path("autoRepostRuleHint").asText("").strip());
        boolean customGenRequested = !ctx.generationProvider().isBlank() && !"chatgpt".equalsIgnoreCase(ctx.generationProvider());
        ObjectNode rec = ctx.autoRepostsByMaterialId().get(materialId);
        if (rec != null && sameRuleHint && !customGenRequested && previewMatchesMaterial(rec, m)
                && rec.path("quoteAuthorFollowers").asLong(m.path("authorFollowersCount").asLong()) == quoteAuthorFollowers(effectiveSource, m)) {
            String recStatus = rec.path("status").asText("");
            boolean hasScores = rec.has("viralScore") && rec.path("viralScore").asInt(0) > 0;
            boolean deferredWithoutEval = rec.path("deferredPendingEval").asBoolean(false)
                    || (("SKIPPED_COOLDOWN".equals(recStatus) || "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus)) && !hasScores);
            boolean isGuardSkip = !deferredWithoutEval && Set.of(
                    "SKIPPED_DUPLICATE", "SKIPPED_LIMIT", "SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN"
            ).contains(recStatus);
            if ("PUBLISHED".equals(recStatus) || hasScores || isGuardSkip) {
                int viralScore = rec.path("viralScore").asInt(0);
                int factScore = rec.path("factScore").asInt(rec.path("credibility").asInt(75));
                int trendScore = rec.path("trendScore").asInt(rec.path("eventMagnitude").asInt(0));
                int attentionScore = rec.path("attentionScore").asInt(rec.path("publicResonance").asInt(0));
                int credibility = rec.path("credibility").asInt(75);
                int fitScore = rec.has("fitScore")
                        ? rec.path("fitScore").asInt(0)
                        : ("PUBLISHED".equals(recStatus) ? 85 : 60);
                boolean accountFit = rec.has("accountFit")
                        ? rec.path("accountFit").asBoolean(false)
                        : (fitScore >= 68);
                String category = rec.path("category").asText("ROUTINE");
                boolean directPass = PiAgentViralJudge.isDirectPassCategory(category)
                        && accountFit && fitScore >= 65 && credibility >= 55 && factScore >= 50;
                boolean viralPass = directPass || (viralScore >= minScore && factScore >= 55 && credibility >= 60);
                boolean fitPass = accountFit && (directPass ? fitScore >= 65 : fitScore >= 68);
                boolean contentPass = viralPass && fitPass;
                boolean exemptAuthor = directPass && !strictRepostAuthorLock(rec);
                boolean authorCooldown = !exemptAuthor && "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus);
                boolean effectiveGuardSkip = isGuardSkip && !(exemptAuthor && "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus));
                boolean wouldRepost = "PUBLISHED".equals(recStatus) || (contentPass && !effectiveGuardSkip);
                String summary = rec.path("summary").asText("");
                boolean voiceV7 = rec.path("quoteVoiceVersion").asInt(0) >= 7 || "PUBLISHED".equals(recStatus);
                boolean briefFlash = OwnerQuoteStyleDistiller.isBriefFlashPost(
                        m.path("text").asText(""), m.path("quotedText").asText(""), m.path("articleText").asText(""));
                String commentText = voiceV7
                        ? OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(rec.path("commentText").asText(""), briefFlash, 280)
                        : "";
                if (useTemplate) {
                    String tpl = effectiveSource.path("autoRepostTemplate").asText("").strip();
                    if (!tpl.isBlank()) {
                        commentText = PiAgentViralJudge.truncateCodePoints(
                                tpl.replace("{summary}", summary.isBlank() ? truncatePreview(AssistantTopics.text(m), 22) : summary)
                                        .replace("{author}", "@" + effectiveSource.path("username").asText(""))
                                        .strip(), 240);
                    }
                }
                String decisionCode = classifyDecisionCode(
                        exemptAuthor && "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus) ? "" : recStatus,
                        authorCooldown, wouldRepost, viralPass, fitPass);
                ObjectNode built = object()
                        .put("evaluated", true)
                        .put("wouldAutoRepost", wouldRepost)
                        .put("contentPass", contentPass)
                        .put("directPass", directPass)
                        .put("authorCooldownBlocked", authorCooldown)
                        .put("viralPass", viralPass)
                        .put("accountFit", fitPass)
                        .put("viralScore", viralScore)
                        .put("factScore", factScore)
                        .put("trendScore", trendScore)
                        .put("attentionScore", attentionScore)
                        .put("minScore", minScore)
                        .put("fitScore", fitScore)
                        .put("decisionCode", decisionCode)
                        .put("persistedStatus", recStatus)
                        .put("category", category)
                        .put("summary", summary)
                        .put("baseReason", rec.path("reason").asText(""))
                        .put("reason", rec.path("reason").asText(""))
                        .put("commentText", commentText)
                        .put("publishedTweetUrl", rec.path("publishedTweetUrl").asText(""))
                        .put("textMode", textMode)
                        .put("cached", true)
                        .put("fallbackUsed", false);
                return applySingleItemAuthorCooldownOverlay(built, materialId, m.path("publishedAt").asLong(0), ctx);
            }
        }

        String recordedStatus = rec == null ? "" : rec.path("status").asText("");
        ObjectNode pending = object()
                .put("persistedStatus", recordedStatus)
                .put("publishedTweetUrl", rec == null ? "" : rec.path("publishedTweetUrl").asText(""))
                .put("evaluated", false)
                .put("wouldAutoRepost", false)
                .put("contentPass", false)
                .put("authorCooldownBlocked", false)
                .put("viralPass", false)
                .put("accountFit", false)
                .put("viralScore", 0)
                .put("factScore", 0)
                .put("trendScore", 0)
                .put("attentionScore", 0)
                .put("minScore", minScore)
                .put("fitScore", 0)
                .put("decisionCode", "PUBLISHED".equals(recordedStatus) ? "PUBLISHED" : "PENDING_EVAL")
                .put("reason", "")
                .put("commentText", "")
                .put("textMode", textMode);
        return applySingleItemAuthorCooldownOverlay(pending, materialId, m.path("publishedAt").asLong(), ctx);
    }

    public ObjectNode authorTimeline(String rawSourceKey, ObjectNode body) {
        ObjectNode req = body == null ? object() : body;
        AuthorPreviewContext ctx = loadAuthorPreviewContext(rawSourceKey, req, 20, req.path("rewriteToday").asBoolean());
        ObjectNode effectiveSource = ctx.effectiveSource();

        ObjectNode out = object();
        out.putObject("owner")
                .put("accountId", ctx.ownerAccountId())
                .put("username", ctx.ownerUsername())
                .put("displayName", ctx.ownerUsername())
                .put("avatarUrl", "/author-avatar.webp");
        ObjectNode sourceNode = buildSourceSummaryNode(effectiveSource, ctx);
        long nowMs = clock.millis();
        DualCooldown dualCd = computeDualCooldownFromContext(ctx, nowMs);
        sourceNode.set("cooldown", dualCd.toJsonNode());
        out.set("source", sourceNode);
        out.set("cooldown", dualCd.toJsonNode());

        AuthorRewriteBaseline rwBaseline = loadAuthorRewriteBaseline(
                ctx.ownerAccountId(),
                effectiveSource.path("authorId").asText(effectiveSource.path("id").asText("")),
                effectiveSource.path("username").asText(""),
                nowMs);
        Map<String, ObjectNode> latestMaterials = new java.util.HashMap<>();
        for (ObjectNode material : rwBaseline.windowMaterials()) latestMaterials.put(material.path("id").asText(), material);
        List<ObjectNode> timelineMaterials = new ArrayList<>();
        for (ObjectNode material : ctx.materials()) timelineMaterials.add(latestMaterials.getOrDefault(material.path("id").asText(), material));
        Set<String> existingMatIds = new java.util.HashSet<>();
        for (ObjectNode m : timelineMaterials) {
            String mid = m.path("id").asText("").strip();
            if (!mid.isBlank()) existingMatIds.add(mid);
        }
        var ranking = rankAuthorRewrites(authorRewritePool(ctx, rwBaseline), ctx, rwBaseline, nowMs);
        List<ObjectNode> extraRewriteMats = new ArrayList<>(ranking.candidates());
        rwBaseline.windowMaterials().stream()
                .filter(m -> rwBaseline.rewriteRecordsByMaterialId().containsKey(m.path("id").asText())
                        || withinRewriteWindow(m, nowMs) && isPureTextRewriteCandidate(m) && rwBaseline.traffic().qualifies(m))
                .sorted(rewritePriority(effectiveSource, rwBaseline.traffic().views(), rwBaseline.traffic().engagement()))
                .forEach(extraRewriteMats::add);
        for (ObjectNode extra : extraRewriteMats) {
            String id = extra.path("id").asText();
            ObjectNode status = ranking.statusById().get(id);
            boolean manualReview = status != null && status.path("manualReviewPreview").asBoolean();
            if ((timelineMaterials.size() < ctx.materials().size() + 6 || manualReview) && existingMatIds.add(id))
                timelineMaterials.add(extra);
        }
        if (timelineMaterials.size() > ctx.materials().size()) {
            var extra = timelineMaterials.subList(ctx.materials().size(), timelineMaterials.size());
            ctx.autoRepostsByMaterialId().putAll(store.tx(tx -> quoteTimelineRecords(tx, extra, ctx.ownerAccountId())));
        }

        record RawTimelineRow(ObjectNode rawMaterial, ObjectNode materialNode, ObjectNode decisionNode, ObjectNode rewriteNode, long publishedAt, String id) { }
        List<RawTimelineRow> rows = new ArrayList<>();
        for (ObjectNode m : timelineMaterials) {
            ObjectNode decision = resolveStoredDecisionIfAvailable(m, ctx);
            if (!QUOTE_DELIVERY_STATUSES.contains(decision.path("persistedStatus").asText(""))) {
                if (dualCd.globalCdActive()) {
                    boolean alreadyEvaluated = decision.path("evaluated").asBoolean(false);
                    String baseReason = decision.path("baseReason").asText(decision.path("reason").asText("")).strip();
                    boolean itemContentPass = decision.path("contentPass").asBoolean(false);
                    decision.put("wouldAutoRepost", false)
                            .put("cdShortCircuited", !alreadyEvaluated)
                            .put("authorCooldownBlocked", dualCd.authorCdActive())
                            .put("ownerCooldownBlocked", true);
                    if (!alreadyEvaluated) {
                        decision.put("decisionCode", "SKIP_COOLDOWN")
                                .put("reason", dualCd.blockingReasonText());
                    } else if (itemContentPass) {
                        decision.put("decisionCode", "SKIP_COOLDOWN")
                                .put("reason", baseReason.isBlank() || baseReason.contains("冷却中") || baseReason.contains("上一条推文")
                                        ? dualCd.blockingReasonText()
                                        : "内容达标（" + baseReason + "），但" + dualCd.blockingReasonText());
                    } else if (baseReason.isBlank()) {
                        decision.put("reason", dualCd.blockingReasonText());
                    }
                } else if (dualCd.authorCdActive() && decision.path("evaluated").asBoolean(false)) {
                    boolean exemptAuthorCd = !strictRepostAuthorLock(decision) && PiAgentViralJudge.isDirectPassCategory(decision.path("category").asText(""));
                    if (exemptAuthorCd && decision.path("contentPass").asBoolean(false)) {
                        decision.put("authorCooldownExempt", true);
                    } else if (!exemptAuthorCd) {
                        decision.put("wouldAutoRepost", false)
                                .put("authorCooldownBlocked", true)
                                .put("decisionCode", "SKIP_AUTHOR_COOLDOWN")
                                .put("reason", dualCd.blockingReasonText());
                    }
                }
            }
            String materialId = m.path("id").asText();
            ObjectNode rewriteNode = ranking.statusById().get(materialId);
            if (rewriteNode == null) rewriteNode = buildRewriteStatusNode(m, ctx, rwBaseline, nowMs, true);
            rows.add(new RawTimelineRow(
                    m,
                    buildMaterialSummaryNode(m, effectiveSource),
                    decision,
                    rewriteNode,
                    m.path("publishedAt").asLong(0),
                    m.path("id").asText("")
            ));
        }

        // Enforce 16-hour non-consecutive same-author window chronologically (oldest -> newest) across preview items
        // Direct-pass categories (MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET) are exempt from same-author 16h CD!
        if (!dualCd.globalCdActive()) {
            List<Long> occupiedSlots = new ArrayList<>(ctx.sameAuthorActiveRepostTimesByMaterialId().values());
            for (int i = rows.size() - 1; i >= 0; i--) {
                RawTimelineRow row = rows.get(i);
                ObjectNode dec = row.decisionNode();
                if (!dec.path("evaluated").asBoolean(false)) continue;
                if (QUOTE_DELIVERY_STATUSES.contains(dec.path("persistedStatus").asText(""))) continue;
                if (!dec.path("wouldAutoRepost").asBoolean(false)) continue;
                long ts = row.publishedAt() > 0 ? row.publishedAt() : clock.millis();
                boolean exemptAuthorCd = !strictRepostAuthorLock(dec) && PiAgentViralJudge.isDirectPassCategory(dec.path("category").asText(""));
                boolean within16h = occupiedSlots.stream().anyMatch(prev -> Math.abs(ts - prev) < SAME_AUTHOR_COOLDOWN_MS);
                if (within16h && !exemptAuthorCd) {
                    String baseReason = dec.path("baseReason").asText(dec.path("reason").asText(""));
                    dec.put("baseReason", baseReason)
                            .put("wouldAutoRepost", false)
                            .put("authorCooldownBlocked", true)
                            .put("decisionCode", "SKIP_AUTHOR_COOLDOWN")
                            .put("reason", baseReason.isBlank()
                                    ? "距上一条拟转发同作者推文不足 16 小时，自动防连转"
                                    : "内容达标，但距同作者上一条转发不足 16 小时（" + baseReason + "）");
                } else {
                    if (within16h && exemptAuthorCd) {
                        dec.put("authorCooldownExempt", true);
                    }
                    occupiedSlots.add(ts);
                }
            }
        }

        int evaluatedCount = 0;
        int wouldRepostCount = 0;
        int skippedCount = 0;
        int pendingCount = 0;
        int wouldRewriteCount = 0;
        var itemsArr = out.putArray("items");
        for (RawTimelineRow row : rows) {
            ObjectNode dec = row.decisionNode();
            if (dec.path("evaluated").asBoolean(false) || dec.path("cdShortCircuited").asBoolean(false)) {
                if (dec.path("evaluated").asBoolean(false)) {
                    evaluatedCount++;
                }
                if (dec.path("wouldAutoRepost").asBoolean(false)) {
                    wouldRepostCount++;
                } else {
                    skippedCount++;
                }
            } else {
                pendingCount++;
            }
            if (row.rewriteNode().path("candidate").asBoolean(false)) {
                wouldRewriteCount++;
            }
            ObjectNode itemObj = itemsArr.addObject();
            itemObj.set("material", row.materialNode());
            itemObj.set("decision", dec);
            itemObj.set("rewrite", row.rewriteNode());
        }

        out.putObject("summary")
                .put("totalCount", rows.size())
                .put("evaluatedCount", evaluatedCount)
                .put("wouldRepostCount", wouldRepostCount)
                .put("wouldRewriteCount", wouldRewriteCount)
                .put("skippedCount", skippedCount)
                .put("pendingCount", dualCd.globalCdActive() ? 0 : pendingCount)
                .put("cdShortCircuited", dualCd.globalCdActive());
        return out;
    }

    public ObjectNode evaluateAuthorOnce(String rawSourceKey, ObjectNode body) {
        long requestOrder = quotePreviewOrder.incrementAndGet();
        ObjectNode req = body == null ? object() : body;
        boolean forceRegenerate = req.path("forceRegenerate").asBoolean(false);
        AuthorPreviewContext base = loadAuthorPreviewContext(rawSourceKey, req, 20, false);
        ObjectNode effectiveSource = base.effectiveSource();
        long nowMs = clock.millis();
        DualCooldown dualCd = computeDualCooldownFromContext(base, nowMs);

        // Priority 0: Automatic Global CD (1h+N min) strictly short-circuits with 0 LLM calls;
        // manual forceRegenerate clicks bypass CD short-circuit for preview/debugging without auto-reposting.
        // If ONLY Author 16h CD is active, we still allow single-pass check so MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET can exempt Author CD.
        if (dualCd.globalCdActive() && !forceRegenerate) {
            return authorTimeline(rawSourceKey, req)
                    .put("cdShortCircuited", true)
                    .put("evaluatedAccountOnce", false)
                    .put("llmCallsSaved", true);
        }

        List<ObjectNode> windowCandidates = base.materials().stream().limit(8).toList();
        if (windowCandidates.isEmpty()) {
            return authorTimeline(rawSourceKey, req).put("evaluatedAccountOnce", true);
        }

        List<ObjectNode> toEvaluate = new ArrayList<>();
        for (ObjectNode m : windowCandidates) {
            ObjectNode recorded = base.autoRepostsByMaterialId().get(m.path("id").asText());
            if (recorded != null && QUOTE_DELIVERY_STATUSES.contains(recorded.path("status").asText(""))) continue;
            if (forceRegenerate) {
                toEvaluate.add(m);
            } else {
                ObjectNode existingDec = resolveStoredDecisionIfAvailable(m, base);
                boolean missingPassComment = existingDec.path("wouldAutoRepost").asBoolean(false)
                        && existingDec.path("commentText").asText("").isBlank();
                if (!existingDec.path("evaluated").asBoolean(false) || missingPassComment) {
                    toEvaluate.add(m);
                }
            }
        }
        if (toEvaluate.isEmpty()) {
            return authorTimeline(rawSourceKey, req)
                    .put("evaluatedAccountOnce", true)
                    .put("cachedAccountPass", true);
        }

        String textMode = effectiveSource.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = SourceCollector.resolveAutoRepostMinScore(effectiveSource);
        String tpl = effectiveSource.path("autoRepostTemplate").asText("").strip();
        String ruleHint = effectiveSource.path("autoRepostRuleHint").asText("").strip();
        String activePolishPrompt = base.postPolishEnabled() ? base.postPolishPrompt() : "";

        List<PiAgentViralJudge.BatchCandidateItem> batchItems = new ArrayList<>();
        ObjectNode sharedHotContext = null;
        for (ObjectNode m : toEvaluate) {
            beginQuotePreview(buildPreviewCacheKey(m, base), requestOrder);
            String mid = m.path("id").asText("");
            long pubAt = m.path("publishedAt").asLong(0);
            long ageMin = pubAt > 0 ? Math.max(0L, (nowMs - pubAt) / 60_000L) : 0L;
            ThreePillarEvidence ev = buildThreePillarEvidence(mid, effectiveSource, m, nowMs);
            if (sharedHotContext == null) sharedHotContext = ev.currentHotContext();
            batchItems.add(new PiAgentViralJudge.BatchCandidateItem(
                    mid,
                    m.path("text").asText(""),
                    m.path("quotedText").asText(""),
                    m.path("articleText").asText(""),
                    ageMin,
                    optionalLong(m, "views"),
                    optionalLong(m, "likes"),
                    optionalLong(m, "reposts"),
                    optionalLong(m, "replies"),
                    ev.factualEvidence(),
                    ev.attentionEvidence()
            ));
        }

        String authorBio = resolveAuthorBio(effectiveSource, toEvaluate);
        String batchContextText = toEvaluate.stream()
                .map(m -> m.path("text").asText(""))
                .filter(t -> !t.isBlank())
                .limit(3)
                .collect(java.util.stream.Collectors.joining("\n"));
        List<String> recentStances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                tx,
                effectiveSource.path("username").asText(""),
                authorBio,
                batchContextText,
                "",
                4
        ));

        PiAgentViralJudge.AccountBatchJudgment batchJudgment;
        try {
            batchJudgment = judge.evaluateAccountBatch(new PiAgentViralJudge.AccountBatchInput(
                    effectiveSource.path("username").asText(""),
                    effectiveSource.path("displayName").asText(""),
                    effectiveSource.path("verified").asBoolean(false),
                    minScore,
                    ruleHint,
                    base.styleProfile(),
                    base.preferencesText(),
                    !useTemplate || tpl.isBlank() || tpl.contains("{summary}"),
                    sharedHotContext,
                    activePolishPrompt,
                    batchItems,
                    dualCd.authorCdActive(),
                    base.generationProvider(),
                    base.generationModel(),
                    authorBio,
                    recentStances
            ));
        } catch (RuntimeException ex) {
            throw new XDataProviderException(502, "账号单次合并判定未完成：" + safeError(ex));
        }

        Map<String, String> regeneratedComments = new LinkedHashMap<>();
        for (ObjectNode m : toEvaluate) {
            String mid = m.path("id").asText("");
            PiAgentViralJudge.ViralJudgment j = batchJudgment.byMaterialId().get(mid);
            if (j == null) continue;
            boolean directPass = PiAgentViralJudge.isDirectPassCategory(j.category())
                    && j.accountFit() && j.fitScore() >= 65 && j.credibility() >= 55 && j.factScore() >= 50;
            boolean viralPass = directPass || (j.viralScore() >= minScore && j.factScore() >= 55 && j.credibility() >= 60);
            boolean fitPass = j.accountFit() && (directPass ? j.fitScore() >= 65 : j.fitScore() >= 68);
            boolean contentPass = j.shouldRepost();
            String commentText = contentPass ? buildCommentText(useTemplate, effectiveSource, m, j) : "";
            ObjectNode existingRec = base.autoRepostsByMaterialId().get(mid);
            String persistedStatus = existingRec == null ? "" : existingRec.path("status").asText("");
            String rawDecisionCode = classifyDecisionCode(directPass && "SKIPPED_AUTHOR_COOLDOWN".equals(persistedStatus)
                    ? "" : persistedStatus, false, contentPass, viralPass, fitPass);

            String cacheKey = buildPreviewCacheKey(m, base);
            ObjectNode cachedEntry = object();
            cachedEntry.putObject("owner")
                    .put("accountId", base.ownerAccountId())
                    .put("username", base.ownerUsername())
                    .put("displayName", base.ownerUsername())
                    .put("avatarUrl", "/author-avatar.webp");
            cachedEntry.set("source", buildSourceSummaryNode(effectiveSource, base));
            cachedEntry.set("material", buildMaterialSummaryNode(m, effectiveSource));
            cachedEntry.putObject("preview")
                    .put("evaluated", true)
                    .put("commentText", commentText)
                    .put("wouldAutoRepost", contentPass)
                    .put("contentPass", contentPass)
                    .put("directPass", directPass)
                    .put("authorCooldownBlocked", false)
                    .put("viralPass", viralPass)
                    .put("accountFit", fitPass)
                    .put("viralScore", j.viralScore())
                    .put("factScore", j.factScore())
                    .put("trendScore", j.trendScore())
                    .put("attentionScore", j.attentionScore())
                    .put("minScore", minScore)
                    .put("fitScore", j.fitScore())
                    .put("decisionCode", rawDecisionCode)
                    .put("persistedStatus", persistedStatus)
                    .put("publishedTweetUrl", existingRec == null ? "" : existingRec.path("publishedTweetUrl").asText(""))
                    .put("category", j.category())
                    .put("summary", j.summary())
                    .put("baseReason", j.reason())
                    .put("reason", j.reason())
                    .put("textMode", textMode)
                    .put("cached", false)
                    .put("fallbackUsed", false)
                    .put("fallbackReason", "");
            if (saveQuotePreview(cacheKey, requestOrder, cachedEntry) && !commentText.isBlank()) regeneratedComments.put(mid, commentText);
        }

        ObjectNode timeline = authorTimeline(rawSourceKey, req)
                .put("evaluatedAccountOnce", true)
                .put("selectedMaterialId", batchJudgment.selectedMaterialId());
        var regenerated = timeline.putArray("regeneratedCommentIds");
        for (var item : timeline.path("items")) {
            String id = item.path("material").path("id").asText();
            var decision = item.path("decision");
            if (!QUOTE_DELIVERY_STATUSES.contains(decision.path("persistedStatus").asText(""))
                    && decision.path("commentText").asText("").equals(regeneratedComments.get(id))) regenerated.add(id);
        }
        return timeline;
    }

    private ObjectNode quoteReceiptPreview(String sourceKey, String materialId, String clientRequestId) {
        String key = sourceKey == null ? "" : sourceKey.replaceFirst("^@+", "").strip();
        if (key.isBlank() || materialId.isBlank()) throw new IllegalArgumentException("缺少来源或素材标识");
        if (clientRequestId.length() > 128) throw new IllegalArgumentException("请求标识过长");
        reconcileAcceptedReceipts();
        return store.tx(tx -> {
            ObjectNode owner = tx.ownerBinding();
            if (owner == null) throw new XDataProviderException(403, "当前发布账号未绑定");
            String ownerId = owner.path("accountId").asText();
            ObjectNode out = object();
            out.putObject("owner").put("accountId", ownerId).put("username", owner.path("username").asText(""));
            ObjectNode material = tx.rewriteMaterial(materialId, clock.millis());
            var rows = quoteRows(tx, List.of(material == null ? object().put("id", materialId) : material), ownerId);
            if (material == null) {
                // On-demand source cards need not have a collected material. The send reservation retains its author.
                ObjectNode source = tx.get("source", key);
                for (ObjectNode receipt : rows) {
                    boolean sameAuthor = source != null ? matchesPreviewSource(source, receipt)
                            : key.equals(receipt.path("authorId").asText()) || key.equalsIgnoreCase(receipt.path("username").asText());
                    if (!sameAuthor) continue;
                    material = object().put("id", materialId).put("authorId", receipt.path("authorId").asText(""))
                            .put("username", receipt.path("username").asText("")).put("sourceUrl", receipt.path("sourceUrl").asText(""));
                    break;
                }
                if (material == null && !rows.isEmpty()) throw new XDataProviderException(404, "该来源没有指定素材的回执");
                if (material != null && originalQuoteId(material) != null && !materialId.equals(originalQuoteId(material)))
                    rows = quoteRows(tx, List.of(material), ownerId);
                out.putObject("material").put("id", materialId);
            } else {
                verifyManualQuoteAuthor(tx, key, material, null);
                out.set("material", buildMaterialSummaryNode(material, material));
            }
            ObjectNode row = material == null ? null : quoteTimelineRecord(material, rows);
            var preview = applyQuoteReceipt(object().put("decisionCode", "PENDING_EVAL").put("reason", "")
                    .put("publishedText", "").put("publishedTweetUrl", "").put("publicationMode", ""), row);
            if (!clientRequestId.isBlank()) {
                long matched = 0;
                for (ObjectNode attempt : rows) {
                    if (material != null && ("manual-quote-" + clientRequestId).equals(attempt.path("id").asText())
                            && quoteMatchesMaterial(attempt, material, originalQuoteId(material)))
                        matched = Math.max(matched, attempt.path("receiptRevision").asLong());
                }
                preview.put("requestReceiptRevision", matched);
            }
            out.set("preview", preview);
            return out;
        });
    }

    private ObjectNode quotePreviewEnvelope(AuthorPreviewContext ctx, ObjectNode material) {
        ObjectNode out = object();
        out.putObject("owner").put("accountId", ctx.ownerAccountId()).put("username", ctx.ownerUsername())
                .put("displayName", ctx.ownerUsername()).put("avatarUrl", "/author-avatar.webp");
        out.set("source", buildSourceSummaryNode(ctx.effectiveSource(), ctx));
        out.set("material", buildMaterialSummaryNode(material, ctx.effectiveSource()));
        var recent = out.putArray("recentMaterials");
        for (ObjectNode candidate : ctx.materials().stream().limit(8).toList()) {
            recent.addObject().put("id", candidate.path("id").asText(""))
                    .put("publishedAt", candidate.path("publishedAt").asLong())
                    .put("snippet", truncatePreview(AssistantTopics.text(candidate), 44));
        }
        return out;
    }

    public ObjectNode previewRepost(String rawSourceKey, ObjectNode body) {
        long requestOrder = quotePreviewOrder.incrementAndGet();
        ObjectNode req = body == null ? object() : body;
        String requestedMaterialId = req.path("materialId").asText("").strip();
        if (req.path("receiptOnly").asBoolean()) return quoteReceiptPreview(rawSourceKey, requestedMaterialId, req.path("clientRequestId").asText(""));
        boolean forceRegenerate = req.path("forceRegenerate").asBoolean(false);
        boolean forceWriteComment = req.path("forceWriteComment").asBoolean(false) || forceRegenerate;
        AuthorPreviewContext base = loadAuthorPreviewContext(rawSourceKey, req, 20, false);
        ObjectNode effectiveSource = base.effectiveSource();
        List<ObjectNode> candidates = base.materials();
        if (candidates.isEmpty()) {
            String key = rawSourceKey == null ? "" : rawSourceKey.replaceFirst("^@+", "").strip();
            throw new XDataProviderException(404, "@" + effectiveSource.path("username").asText(key) + " 暂无可预览的近期推文");
        }

        ObjectNode selectedMaterial = requestedMaterialId.isBlank() ? candidates.get(0) : candidates.stream()
                .filter(m -> requestedMaterialId.equals(m.path("id").asText()))
                .findFirst().orElseThrow(() -> new XDataProviderException(404, "该作者没有指定的推文素材：" + requestedMaterialId));

        String materialId = selectedMaterial.path("id").asText("");
        long publishedAt = selectedMaterial.path("publishedAt").asLong(0);
        String textMode = effectiveSource.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = SourceCollector.resolveAutoRepostMinScore(effectiveSource);
        String tpl = effectiveSource.path("autoRepostTemplate").asText("").strip();
        String ruleHint = effectiveSource.path("autoRepostRuleHint").asText("").strip();
        String cacheKey = buildPreviewCacheKey(selectedMaterial, base);
        ObjectNode existingRec = base.autoRepostsByMaterialId().get(materialId);
        String persistedStatus = existingRec == null ? "" : existingRec.path("status").asText("");
        if (QUOTE_DELIVERY_STATUSES.contains(persistedStatus) && (!forceRegenerate || !"PUBLISHED".equals(persistedStatus))) {
            return quotePreviewEnvelope(base, selectedMaterial).set("preview", resolveStoredDecisionIfAvailable(selectedMaterial, base));
        }

        if (!forceRegenerate) {
            ObjectNode cached = cachedQuotePreview(cacheKey);
            if (cached != null && (!forceWriteComment || !cached.path("preview").path("commentText").asText("").isBlank())) {
                ObjectNode copy = cached.deepCopy();
                ObjectNode overlaid = applySingleItemAuthorCooldownOverlay(
                        copy.withObject("/preview").put("cached", true).put("evaluated", true),
                        materialId, publishedAt, base);
                copy.set("source", buildSourceSummaryNode(effectiveSource, base));
                copy.set("preview", overlaid);
                return copy;
            }
            ObjectNode storedDecision = resolveStoredDecisionIfAvailable(selectedMaterial, base);
            boolean storedHasRequiredComment = (!storedDecision.path("wouldAutoRepost").asBoolean(false) && !forceWriteComment)
                    || !storedDecision.path("commentText").asText("").isBlank();
            if (storedDecision.path("evaluated").asBoolean(false) && storedHasRequiredComment) {
                return quotePreviewEnvelope(base, selectedMaterial).set("preview", storedDecision);
            }
        }

        beginQuotePreview(cacheKey, requestOrder);
        String commentText = "";
        boolean contentPass = false;
        boolean viralPass = false;
        boolean accountFit = false;
        int viralScore = 0;
        int factScore = 0;
        int trendScore = 0;
        int attentionScore = 0;
        int fitScore = 0;
        String category = "ROUTINE";
        String summary = "";
        String baseReason = "";

        long now = clock.millis();
        long ageMinutes = publishedAt > 0 ? Math.max(0L, (now - publishedAt) / 60_000L) : 0L;
        ThreePillarEvidence evidence = buildThreePillarEvidence(materialId, effectiveSource, selectedMaterial, now);
        String activePolishPrompt = base.postPolishEnabled() ? base.postPolishPrompt() : "";
        final ObjectNode targetMaterial = selectedMaterial;
        String selectedUsername = targetMaterial.path("username").asText(effectiveSource.path("username").asText(""));
        String selectedAuthorBio = resolveAuthorBio(effectiveSource, List.of(targetMaterial));
        List<String> recentStances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                tx,
                selectedUsername,
                selectedAuthorBio,
                targetMaterial.path("text").asText(""),
                targetMaterial.path("quotedText").asText(""),
                4
        ));
        try {
            PiAgentViralJudge.ViralJudgment j = judge.evaluate(new PiAgentViralJudge.EvaluationInput(
                    selectedUsername,
                    selectedMaterial.path("authorName").asText(effectiveSource.path("displayName").asText("")),
                    selectedMaterial.path("authorVerified").asBoolean(effectiveSource.path("verified").asBoolean(false)),
                    selectedMaterial.path("text").asText(""),
                    selectedMaterial.path("quotedText").asText(""),
                    selectedMaterial.path("articleText").asText(""),
                    ageMinutes,
                    optionalLong(selectedMaterial, "views"),
                    optionalLong(selectedMaterial, "likes"),
                    optionalLong(selectedMaterial, "reposts"),
                    optionalLong(selectedMaterial, "replies"),
                    minScore,
                    ruleHint,
                    base.styleProfile(),
                    base.preferencesText(),
                    !useTemplate || tpl.isBlank() || tpl.contains("{summary}"),
                    evidence.factualEvidence(),
                    evidence.currentHotContext(),
                    evidence.attentionEvidence(),
                    forceWriteComment,
                    activePolishPrompt,
                    false,
                    base.generationProvider(),
                    base.generationModel(),
                    selectedAuthorBio,
                    recentStances
            ));
            viralScore = j.viralScore();
            factScore = j.factScore();
            trendScore = j.trendScore();
            attentionScore = j.attentionScore();
            fitScore = j.fitScore();
            category = j.category();
            boolean directPass = PiAgentViralJudge.isDirectPassCategory(category)
                    && j.accountFit() && fitScore >= 65 && j.credibility() >= 55 && factScore >= 50;
            viralPass = directPass || (viralScore >= minScore && factScore >= 55 && j.credibility() >= 60);
            accountFit = j.accountFit() && (directPass ? fitScore >= 65 : fitScore >= 68);
            contentPass = j.shouldRepost();
            summary = j.summary();
            baseReason = j.reason();
            commentText = buildCommentText(useTemplate, effectiveSource, selectedMaterial, j);
        } catch (RuntimeException ex) {
            if (useTemplate && !tpl.isBlank() && !tpl.contains("{summary}")) {
                commentText = PiAgentViralJudge.truncateCodePoints(
                        tpl.replace("{author}", "@" + effectiveSource.path("username").asText("")).strip(), 240);
                baseReason = "已应用固定转发文案";
            } else {
                throw new XDataProviderException(502, "GPT 判定或转发语生成未完成：" + safeError(ex));
            }
        }

        boolean directPassPreview = PiAgentViralJudge.isDirectPassCategory(category) && contentPass;
        String evaluationStatus = QUOTE_DELIVERY_STATUSES.contains(persistedStatus) ? "" : persistedStatus;
        String rawDecisionCode = classifyDecisionCode(
                directPassPreview && "SKIPPED_AUTHOR_COOLDOWN".equals(evaluationStatus) ? "" : evaluationStatus,
                false, contentPass, viralPass, accountFit);

        ObjectNode baseOut = quotePreviewEnvelope(base, selectedMaterial);

        ObjectNode rawPreviewNode = baseOut.putObject("preview")
                .put("evaluated", true)
                .put("commentText", commentText)
                .put("wouldAutoRepost", contentPass)
                .put("contentPass", contentPass)
                .put("directPass", directPassPreview)
                .put("authorCooldownBlocked", false)
                .put("viralPass", viralPass)
                .put("accountFit", accountFit)
                .put("viralScore", viralScore)
                .put("factScore", factScore)
                .put("trendScore", trendScore)
                .put("attentionScore", attentionScore)
                .put("minScore", minScore)
                .put("fitScore", fitScore)
                .put("decisionCode", rawDecisionCode)
                .put("persistedStatus", evaluationStatus)
                .put("publishedTweetUrl", "")
                .put("category", category)
                .put("summary", summary)
                .put("baseReason", baseReason)
                .put("reason", baseReason)
                .put("textMode", textMode)
                .put("cached", false)
                .put("fallbackUsed", false)
                .put("fallbackReason", "");

        if (!saveQuotePreview(cacheKey, requestOrder, baseOut.deepCopy())) {
            ObjectNode newer = cachedQuotePreview(cacheKey);
            if (newer != null) {
                baseOut = newer.deepCopy();
                rawPreviewNode = baseOut.withObject("/preview");
            }
        }
        ObjectNode out = baseOut.deepCopy();
        // A write or a manual verification can complete during a model call.
        var current = store.tx(tx -> quoteTimelineRecords(tx, List.of(selectedMaterial), base.ownerAccountId()));
        base.autoRepostsByMaterialId().put(materialId, current.get(materialId));
        out.set("preview", applySingleItemAuthorCooldownOverlay(rawPreviewNode, materialId, publishedAt, base));
        return out;
    }

    public ObjectNode previewRewrite(String rawSourceKey, ObjectNode body) {
        ObjectNode req = body == null ? object() : body;
        String requestedMaterialId = req.path("materialId").asText("").strip();
        boolean forceRegenerate = req.path("forceRegenerate").asBoolean(false);
        AuthorPreviewContext base = loadAuthorPreviewContext(rawSourceKey, req, 20, true);
        ObjectNode effectiveSource = base.effectiveSource();
        long nowMs = clock.millis();
        String aid = effectiveSource.path("authorId").asText(effectiveSource.path("id").asText("")).strip();
        String uname = effectiveSource.path("username").asText("").replaceFirst("^@+", "").strip();
        AuthorRewriteBaseline rwBaseline = loadAuthorRewriteBaseline(base.ownerAccountId(), aid, uname, nowMs);

        List<ObjectNode> pool = authorRewritePool(base, rwBaseline);
        var ranking = rankAuthorRewrites(pool, base, rwBaseline, nowMs);

        ObjectNode selectedMaterial = null;
        if (!requestedMaterialId.isBlank()) {
            for (ObjectNode c : pool) {
                if (requestedMaterialId.equals(c.path("id").asText(""))) {
                    selectedMaterial = c;
                    break;
                }
            }
            if (!matchesPreviewSource(effectiveSource, selectedMaterial)) {
                throw new XDataProviderException(404, "未找到指定推文素材：" + requestedMaterialId);
            }
        } else {
            if (ranking.candidates().isEmpty()) throw new XDataProviderException(404, "@" + uname + " 今天暂无可洗稿的高热纯文本原帖");
            selectedMaterial = ranking.candidates().get(0);
        }

        String materialId = selectedMaterial.path("id").asText("");
        String cacheKey = base.ownerAccountId() + "|" + buildRewritePreviewCacheKey(
                selectedMaterial, base.styleProfile(), base.preferencesText(),
                base.postPolishEnabled(), base.postPolishPrompt(),
                base.generationProvider(), base.generationModel());
        ObjectNode existingRewriteRec = rwBaseline.rewriteRecordsByMaterialId().get(materialId);
        String persistedStatus = existingRewriteRec == null ? "" : existingRewriteRec.path("status").asText("");
        boolean published = "PUBLISHED".equals(persistedStatus);
        long previewAt = clock.millis();
        boolean currentDay = withinRewriteWindow(selectedMaterial, previewAt);
        if (!currentDay && requestedMaterialId.isBlank()) throw new XDataProviderException(404, "@" + uname + " 今天暂无可洗稿的高热纯文本原帖");
        ObjectNode status = currentDay ? ranking.statusById().get(materialId) : null;
        if (status == null) status = buildRewriteStatusNode(selectedMaterial, base, rwBaseline, previewAt, true);
        ObjectNode out = object();
        out.putObject("owner").put("accountId", base.ownerAccountId()).put("username", base.ownerUsername())
                .put("displayName", base.ownerUsername()).put("avatarUrl", "/author-avatar.webp");
        out.set("source", buildSourceSummaryNode(effectiveSource, base));
        out.set("material", buildMaterialSummaryNode(selectedMaterial, effectiveSource));
        out.set("rewrite", status);
        if (!status.path("withinWindow").asBoolean() || needsManualRewriteAction(persistedStatus)
                || Set.of("EVALUATING", "SENDING", "UNCERTAIN").contains(persistedStatus)
                || !forceRegenerate && (published || "SKIPPED".equals(status.path("status").asText())
                || !status.path("rewrittenText").asText().isBlank())) return out;
        String pureText = extractPureRewriteSourceText(selectedMaterial);
        if (pureText.isBlank()) {
            throw new XDataProviderException(400, "该推文不满足纯文本洗稿条件（" + rewriteIneligibleReason(selectedMaterial) + "）");
        }
        String authorUname = selectedMaterial.path("username").asText(uname);
        String authorBio = resolveAuthorBio(effectiveSource, List.of(selectedMaterial));
        List<String> stances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                tx, authorUname, authorBio, pureText, "", 4));
        String activePolishPrompt = base.postPolishEnabled() ? base.postPolishPrompt() : "";
        String selectedFingerprint = rewriteSourceFingerprint(selectedMaterial);
        int sourceVersion = store.tx(tx -> {
            long checkedAt = clock.millis();
            var current = tx.rewriteMaterial(materialId, checkedAt);
            if (!withinRewriteWindow(current, checkedAt))
                throw new XDataProviderException(409, "北京时间已跨日或原素材已不可用，请重新打开来源时间线");
            var state = tx.rewriteSourceState(materialId);
            if (state == null || !selectedFingerprint.equals(state.path("fingerprint").asText()))
                throw new XDataProviderException(409, "原素材已变更，请重新生成洗稿预览");
            return state.path("version").asInt();
        });

        final PiAgentViralJudge.RewriteJudgment rwJudgment;
        try {
            rwJudgment = judge.evaluateAndWriteRewrite(new PiAgentViralJudge.RewriteInput(
                    authorUname,
                    selectedMaterial.path("authorName").asText(effectiveSource.path("displayName").asText("")),
                    authorBio,
                    pureText,
                    optionalLong(selectedMaterial, "views"),
                    optionalLong(selectedMaterial, "likes"),
                    optionalLong(selectedMaterial, "reposts"),
                    optionalLong(selectedMaterial, "replies"),
                    rwBaseline.traffic().views(),
                    rwBaseline.traffic().likes(),
                    base.styleProfile(),
                    base.preferencesText(),
                    stances,
                    activePolishPrompt,
                    base.generationProvider(),
                    base.generationModel()
            ), true);
        } catch (RuntimeException ex) {
            throw new XDataProviderException(502, "洗稿评估或生成未完成：" + safeError(ex));
        }

        if (rwJudgment.rewrittenText().isBlank()) {
            throw new XDataProviderException(502, "洗稿生成返回内容为空，请重试");
        }

        ObjectNode evaluatedMaterial = selectedMaterial;
        return store.tx(tx -> {
            long finishedAt = clock.millis();
            var latest = authorRewriteBaseline(tx, base.ownerAccountId(), aid, uname, finishedAt);
            var state = tx.rewriteSourceState(materialId);
            if (state == null || sourceVersion != state.path("version").asInt())
                throw new XDataProviderException(409, "原素材已变更，请重新生成洗稿预览");
            var current = tx.rewriteMaterial(materialId, finishedAt);
            if (current == null) {
                if (withinRewriteWindow(evaluatedMaterial, finishedAt))
                    throw new XDataProviderException(409, "原素材已不可用，请重新打开来源时间线");
                current = evaluatedMaterial;
            }
            var result = buildRewriteStatusNode(current, base, latest, finishedAt, false);
            out.set("material", buildMaterialSummaryNode(current, effectiveSource));
            out.set("rewrite", result);
            if (!result.path("withinWindow").asBoolean()
                    || !Objects.equals(existingRewriteRec, latest.rewriteRecordsByMaterialId().get(materialId))) return out;

            String previewId = tx.rememberRewritePreview(base.ownerAccountId(), materialId, rwJudgment.rewrittenText(), sourceVersion);
            boolean locallyEligible = result.path("candidate").asBoolean();
            result.put("candidate", locallyEligible && rwJudgment.suitable())
                    .put("manualReviewPreview", locallyEligible && !rwJudgment.suitable())
                    .put("evaluated", true).put("suitable", rwJudgment.suitable())
                    .put("suitabilityScore", rwJudgment.suitabilityScore()).put("hookPattern", rwJudgment.hookPattern())
                    .put("coreInsight", rwJudgment.coreInsight()).put("reason", rwJudgment.reason())
                    .put("rewrittenText", rwJudgment.rewrittenText()).put("publishedTweetUrl", "").put("unsentPreview", published)
                    .put("savedDraftVersion", 0).put("previewId", previewId)
                    .put("status", "SKIPPED".equals(result.path("status").asText()) ? "SKIPPED" : "EVALUATED").put("cached", false);
            rewritePreviewCache.put(cacheKey, out.deepCopy());
            if (result.path("candidate").asBoolean()) {
                var currentRanking = rankAuthorRewrites(authorRewritePool(base, latest), base, latest, finishedAt);
                result.put("rank", currentRanking.statusById().get(materialId).path("rank").asInt());
            }
            return out;
        });
    }

    private record AuthorRewriteBaseline(
            List<ObjectNode> windowMaterials,
            Map<String, ObjectNode> rewriteRecordsByMaterialId,
            Map<String, ObjectNode> quoteRecordsByMaterialId,
            RewriteTraffic traffic
    ) { }

    private static List<ObjectNode> authorRewritePool(AuthorPreviewContext ctx, AuthorRewriteBaseline baseline) {
        Map<String, ObjectNode> materials = new LinkedHashMap<>();
        for (ObjectNode row : ctx.materials()) materials.put(row.path("id").asText(), row);
        for (ObjectNode row : baseline.windowMaterials()) materials.put(row.path("id").asText(), row);
        return List.copyOf(materials.values());
    }

    private record AuthorRewriteRanking(List<ObjectNode> candidates, Map<String, ObjectNode> statusById) { }

    private AuthorRewriteRanking rankAuthorRewrites(List<ObjectNode> pool, AuthorPreviewContext ctx,
                                                   AuthorRewriteBaseline baseline, long now) {
        List<ObjectNode> candidates = new ArrayList<>();
        Map<String, ObjectNode> statusById = new java.util.HashMap<>();
        for (ObjectNode material : pool) {
            if (!withinRewriteWindow(material, now)) continue;
            ObjectNode status = buildRewriteStatusNode(material, ctx, baseline, now, true);
            statusById.put(material.path("id").asText(), status);
            if (status.path("candidate").asBoolean()) candidates.add(material);
        }
        candidates.sort(rewritePriority(m -> statusById.get(m.path("id").asText()).path("rankScore").asDouble(), Function.identity()));
        for (int i = 0; i < candidates.size(); i++) statusById.get(candidates.get(i).path("id").asText()).put("rank", i + 1);
        return new AuthorRewriteRanking(candidates, statusById);
    }

    private record RewriteTraffic(long views, long likes, long engagement, int sampleCount) {
        static RewriteTraffic of(List<ObjectNode> materials) {
            List<Long> views = new ArrayList<>();
            List<Long> likes = new ArrayList<>();
            List<Long> engagement = new ArrayList<>();
            for (ObjectNode material : materials) {
                long v = material.path("views").asLong();
                long l = material.path("likes").asLong();
                long e = computeWeightedEngagement(material);
                if (v > 0) views.add(v);
                if (l > 0) likes.add(l);
                if (e > 0) engagement.add(e);
            }
            return new RewriteTraffic(medianOf(views), medianOf(likes), medianOf(engagement), Math.max(views.size(), engagement.size()));
        }

        boolean qualifies(ObjectNode material) {
            return isHighTrafficForAuthor(material, views, likes, engagement, sampleCount);
        }
    }

    private AuthorRewriteBaseline loadAuthorRewriteBaseline(String ownerId, String authorId, String username, long nowMs) {
        return store.tx(tx -> authorRewriteBaseline(tx, ownerId, authorId, username, nowMs));
    }

    private AuthorRewriteBaseline authorRewriteBaseline(AssistantStore.Tx tx, String ownerId, String authorId, String username, long nowMs) {
        String aid = authorId == null ? "" : authorId.strip();
        String uname = username == null ? "" : username.replaceFirst("^@+", "").strip();
        var owner = tx.ownerBinding();
        if (!ownerId.equals(owner == null ? "" : owner.path("accountId").asText()))
            throw new XDataProviderException(409, "发布账号已变更，请重新打开来源时间线");
        List<ObjectNode> windowMats = tx.rewriteWindow(aid, uname, nowMs - REWRITE_MAX_AGE_MS, nowMs);
        RewriteTraffic traffic = RewriteTraffic.of(windowMats);
        java.util.Map<String, ObjectNode> rwByMat = new java.util.HashMap<>();
        for (ObjectNode rec : tx.ownerActivity("auto_rewrite", ownerId, -1)) {
            String recAid = rec.path("authorId").asText("");
            String recUname = rec.path("username").asText("").replaceFirst("^@+", "");
            boolean same = sameSourceIdentity(aid, uname, recAid, recUname);
            if (!same) continue;
            String mid = rec.path("materialId").asText(rec.path("id").asText("")).strip();
            if (!mid.isBlank()) rwByMat.put(mid, rec.deepCopy());
        }
        var today = windowMats.stream().filter(m -> withinRewriteWindow(m, nowMs)).toList();
        return new AuthorRewriteBaseline(windowMats, rwByMat, quoteTimelineRecords(tx, today, ownerId), traffic);
    }

    private static boolean needsManualRewriteAction(String status) {
        return Set.of("AUTH_REQUIRED", "FAILED", "JUDGE_FAILED", "SKIPPED_DUPLICATE", "OWNER_REVIEW_REQUIRED").contains(status);
    }

    private static boolean dispatchRecordHasFinal(String status) {
        return Set.of("SENDING", "UNCERTAIN", "SKIPPED_LIMIT", "SKIPPED_QUIET_HOURS", "SKIPPED_COOLDOWN").contains(status);
    }

    private ObjectNode buildRewriteStatusNode(
            ObjectNode m,
            AuthorPreviewContext ctx,
            AuthorRewriteBaseline baseline,
            long nowMs,
            boolean usePreviewCache
    ) {
        String materialId = m.path("id").asText("").strip();
        boolean withinWindow = withinRewriteWindow(m, nowMs);
        boolean pureText = isPureTextRewriteCandidate(m);
        boolean highTraffic = baseline.traffic().qualifies(m);
        long eng = computeWeightedEngagement(m);
        double rankScore = computeRewriteRankScore(m, ctx.effectiveSource(), baseline.traffic().views(), baseline.traffic().engagement());
        Double exposure = rewriteExposureRate(m, ctx.effectiveSource());
        ObjectNode quote = baseline.quoteRecordsByMaterialId().getOrDefault(materialId, ctx.autoRepostsByMaterialId().get(materialId));
        String repostStatus = quote == null ? "" : quote.path("status").asText();
        boolean alreadyHandledAsRepost = Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(repostStatus);

        ObjectNode persisted = baseline.rewriteRecordsByMaterialId().get(materialId);
        String persistedStatus = persisted == null ? "" : persisted.path("status").asText("");
        String publishedUrl = persisted == null ? "" : persisted.path("publishedTweetUrl").asText("");
        boolean isPublishedRewrite = "PUBLISHED".equals(persistedStatus);
        boolean currentEvaluation = persisted != null && previewMatchesMaterial(persisted, m);
        boolean retainedFinal = persisted != null && !persisted.path("rewrittenText").asText().isBlank()
                && (currentEvaluation || dispatchRecordHasFinal(persistedStatus));
        boolean rejected = currentEvaluation && "SKIPPED".equals(persistedStatus);
        boolean dispatchPending = Set.of("EVALUATING", "SENDING", "UNCERTAIN").contains(persistedStatus);
        boolean corrected = persisted != null && persisted.path("rewriteSourceNeedsReview").asBoolean();
        boolean isCandidate = withinWindow && pureText && highTraffic && !isPublishedRewrite && !alreadyHandledAsRepost
                && !rejected && !corrected && !dispatchPending && !needsManualRewriteAction(persistedStatus);

        String ineligibleReason = "";
        if (!isCandidate) {
            if (!withinWindow) ineligibleReason = "洗稿候选只收录北京时间今天发布的原帖";
            else if (isPublishedRewrite) ineligibleReason = "该素材已有洗稿发布回执";
            else if (corrected) ineligibleReason = "原素材已更正，请重新评估定稿";
            else if (rejected) ineligibleReason = persisted.path("reason").asText("未通过洗稿适格判定");
            else if (needsManualRewriteAction(persistedStatus)) ineligibleReason = persisted.path("error").asText(persisted.path("reason").asText("需在活动记录中处理"));
            else if (dispatchPending) ineligibleReason = "该素材已有处理中或待核实的洗稿记录";
            else if (!pureText) ineligibleReason = rewriteIneligibleReason(m);
            else if (!highTraffic) ineligibleReason = "互动热度低于高流量洗稿阈值";
            else if (alreadyHandledAsRepost) ineligibleReason = "该帖已触发引用转发";
        }

        String cacheKey = ctx.ownerAccountId() + "|" + buildRewritePreviewCacheKey(
                m, ctx.styleProfile(), ctx.preferencesText(),
                ctx.postPolishEnabled(), ctx.postPolishPrompt(),
                ctx.generationProvider(), ctx.generationModel());
        ObjectNode cachedEntry = rewritePreviewCache.get(cacheKey);
        if (withinWindow && usePreviewCache && !retainedFinal && !isPublishedRewrite && !dispatchPending && !needsManualRewriteAction(persistedStatus) && !rejected && cachedEntry != null && cachedEntry.has("rewrite")) {
            ObjectNode cachedRw = cachedEntry.path("rewrite").deepCopy();
            cachedRw.put("candidate", isCandidate && cachedRw.path("suitable").asBoolean())
                    .put("manualReviewPreview", isCandidate && cachedRw.path("manualReviewPreview").asBoolean())
                    .put("pureText", pureText)
                    .put("highTraffic", highTraffic)
                    .put("withinWindow", withinWindow)
                    .put("engagement", eng)
                    .put("exposureRate", exposure)
                    .put("rankScore", rankScore)
                    .put("rank", 0)
                    .put("persistedStatus", persistedStatus)
                    .put("recordVersion", persisted == null ? 0 : persisted.path("version").asInt())
                    .put("ineligibleReason", ineligibleReason)
                    .put("cached", true);
            return cachedRw;
        }

        if (isPublishedRewrite || rejected || retainedFinal) {
            boolean suitable = !rejected && (isPublishedRewrite || persisted.path("suitabilityScore").asInt(0) >= 68);
            return object()
                    .put("candidate", isCandidate)
                    .put("pureText", pureText)
                    .put("highTraffic", highTraffic)
                    .put("withinWindow", withinWindow)
                    .put("engagement", eng)
                    .put("exposureRate", exposure)
                    .put("rankScore", rankScore)
                    .put("rank", 0)
                    .put("ineligibleReason", ineligibleReason)
                    .put("evaluated", !Set.of("EVALUATING", "JUDGE_FAILED").contains(persistedStatus))
                    .put("suitable", suitable)
                    .put("suitabilityScore", persisted.path("suitabilityScore").asInt(0))
                    .put("hookPattern", persisted.path("hookPattern").asText(""))
                    .put("coreInsight", persisted.path("summary").asText(""))
                    .put("reason", persisted.path("reason").asText(""))
                    .put("rewrittenText", isPublishedRewrite ? persisted.path("text").asText(persisted.path("rewrittenText").asText()) : persisted.path("rewrittenText").asText())
                    .put("savedDraftVersion", persisted.path("version").asInt())
                    .put("publishedTweetUrl", publishedUrl)
                    .put("persistedStatus", persistedStatus)
                    .put("recordVersion", persisted == null ? 0 : persisted.path("version").asInt())
                    .put("status", isPublishedRewrite || dispatchPending || needsManualRewriteAction(persistedStatus) || rejected ? persistedStatus : "EVALUATED")
                    .put("unsentPreview", false)
                    .put("cached", false);
        }

        String status = isPublishedRewrite || dispatchPending || needsManualRewriteAction(persistedStatus) ? persistedStatus : (isCandidate ? "READY" : "INELIGIBLE");
        return object()
                .put("candidate", isCandidate)
                .put("pureText", pureText)
                .put("highTraffic", highTraffic)
                .put("withinWindow", withinWindow)
                .put("engagement", eng)
                .put("exposureRate", exposure)
                .put("rankScore", rankScore)
                .put("rank", 0)
                .put("ineligibleReason", ineligibleReason)
                .put("evaluated", false)
                .put("suitable", false)
                .put("suitabilityScore", 0)
                .put("hookPattern", "")
                .put("coreInsight", "")
                .put("reason", "")
                .put("rewrittenText", "")
                .put("publishedTweetUrl", publishedUrl)
                .put("persistedStatus", persistedStatus)
                .put("recordVersion", persisted == null ? 0 : persisted.path("version").asInt())
                .put("status", status)
                .put("cached", false);
    }

    private static String truncatePreview(String raw, int maxCodePoints) {
        if (raw == null || raw.isBlank()) return "";
        String singleLine = raw.replaceAll("\\s+", " ").strip();
        if (singleLine.codePointCount(0, singleLine.length()) <= maxCodePoints) return singleLine;
        int end = singleLine.offsetByCodePoints(0, maxCodePoints);
        return singleLine.substring(0, end) + "…";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        deferredScheduler.shutdownNow();
    }

    public void enqueueAuthorBatch(ObjectNode source, List<ObjectNode> materials) {
        if (!isAutoRepostRuntimeEnabled()) return;
        long nowMs = clock.millis();
        if (isInAutoRepostQuietWindow(nowMs)) {
            scheduleQuietWindowEndWakeup(nowMs);
            return;
        }
        if (source == null || materials == null || materials.isEmpty()) return;
        ObjectNode sourceCopy = source.deepCopy();
        List<ObjectNode> copies = materials.stream().map(ObjectNode::deepCopy).toList();
        executor.submit(() -> {
            try {
                processAuthorBatch(sourceCopy, copies);
            } catch (Exception ex) {
                log.warn("Auto-repost batch evaluation failed for source {}: {}",
                        sourceCopy.path("username").asText(""), ex.getMessage());
            }
        });
    }

    public void enqueue(ObjectNode source, ObjectNode material) {
        if (!isAutoRepostRuntimeEnabled()) return;
        long nowMs = clock.millis();
        if (isInAutoRepostQuietWindow(nowMs)) {
            scheduleQuietWindowEndWakeup(nowMs);
            return;
        }
        if (source == null || material == null) return;
        enqueueAuthorBatch(source, List.of(material));
    }

    private void scheduleQuietWindowEndWakeup(long nowMs) {
        if (!isAutoRepostRuntimeEnabled()) return;
        long wakeMs = com.xops.hub.config.XOpsProperties.nextQuietWindowEndEpochMs(
                nowMs, AssistantRetention.ZONE, quietStartHour(), quietEndHour());
        schedulePatrolWakeupAt(wakeMs, nowMs);
    }

    private void schedulePatrolWakeupAt(long targetEpochMs, long nowMs) {
        if (!isAutoRepostRuntimeEnabled() || targetEpochMs <= 0L) return;
        long effectiveTarget = targetEpochMs;
        if (isInAutoRepostQuietWindow(effectiveTarget)) {
            effectiveTarget = com.xops.hub.config.XOpsProperties.nextQuietWindowEndEpochMs(
                    effectiveTarget, AssistantRetention.ZONE, quietStartHour(), quietEndHour());
        }
        long delayMs = Math.max(1_000L, effectiveTarget - nowMs);
        long fireAt = nowMs + delayMs;
        while (true) {
            long prev = nextPatrolWakeupAtMs.get();
            if (prev > nowMs && prev <= fireAt + 1_000L) {
                return;
            }
            if (nextPatrolWakeupAtMs.compareAndSet(prev, fireAt)) {
                break;
            }
        }
        final long scheduledFor = fireAt;
        try {
            deferredScheduler.schedule(() -> {
                nextPatrolWakeupAtMs.compareAndSet(scheduledFor, 0L);
                executor.submit(() -> {
                    try {
                        recheckDeferredCooldownCandidates();
                        runOwnerIdleRepostPatrol();
                    } catch (Exception ex) {
                        log.warn("Scheduled interval auto-send wakeup failed: {}", ex.getMessage());
                    }
                });
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            nextPatrolWakeupAtMs.compareAndSet(scheduledFor, 0L);
        }
    }

    private static ObjectNode latestOwnerObservation(AssistantStore.Tx tx, String ownerId) {
        var observation = tx.get("meta", "owner_latest_tweet");
        return observation != null && ownerId != null && ownerId.equals(observation.path("accountId").asText())
                ? observation : null;
    }

    private static boolean recentOwnerTimelineCheck(AssistantStore.Tx tx, String ownerId, String username, long now) {
        var observed = latestOwnerObservation(tx, ownerId);
        long at = observed == null ? 0L : observed.path("checkedAt").asLong();
        return at > 0L && now >= at && now - at < OWNER_TIMELINE_MAX_AGE_MS
                && username.replaceFirst("^@+", "").equalsIgnoreCase(observed.path("username").asText());
    }

    private long refreshOwnerTimeline(String ownerId, String username) {
        if (sources == null || ownerId.isBlank() || username.isBlank()) return -1L;
        try {
            long observed = sources.fetchLatestAccountPostEpochMs(username, ownerId);
            if (observed < 0L) return -1L;
            return store.tx(tx -> {
                ObjectNode owner = tx.ownerBinding();
                if (owner == null || !ownerId.equals(owner.path("accountId").asText())
                        || !username.equalsIgnoreCase(owner.path("username").asText("").replaceFirst("^@+", ""))) return -1L;
                ObjectNode prev = latestOwnerObservation(tx, ownerId);
                long latest = prev == null ? observed : Math.max(observed, prev.path("latestTweetAt").asLong());
                tx.put("meta", "owner_latest_tweet", object().put("accountId", ownerId).put("username", username)
                        .put("latestTweetAt", latest).put("checkedAt", clock.millis()));
                return latest;
            });
        } catch (RuntimeException ex) {
            log.warn("Could not refresh the owner's latest post: {}", ex.getMessage());
            return -1L;
        }
    }

    private void recordOwnerPublishAndScheduleNextSlot(String ownerId, String username, long publishedAtMs) {
        long nextGapMs = store.tx(tx -> {
            ObjectNode owner = tx.ownerBinding();
            if (owner == null || !ownerId.equals(owner.path("accountId").asText())) return 0L;
            ObjectNode prev = latestOwnerObservation(tx, ownerId);
            long merged = prev == null ? publishedAtMs : Math.max(publishedAtMs, prev.path("latestTweetAt").asLong(0L));
            tx.put("meta", "owner_latest_tweet", object()
                    .put("accountId", ownerId).put("username", username)
                    .put("latestTweetAt", merged)
                    .put("checkedAt", publishedAtMs));
            ObjectNode settings = AssistantWorkflow.settings(tx);
            return resolveEffectiveOwnerGapMs(settings, merged);
        });
        if (nextGapMs > 0L) {
            schedulePatrolWakeupAt(publishedAtMs + nextGapMs + 1_500L, publishedAtMs);
        }
    }

    private void scheduleDeferredCdWakeup(String sourceId, long readyAtMs, long nowMs) {
        if (sourceId == null || sourceId.isBlank() || readyAtMs <= 0L) return;
        deferredSourceReadyAtMs.merge(sourceId, readyAtMs, Math::max);
        long delayMs = Math.max(1_000L, readyAtMs - nowMs + 1_000L);
        try {
            deferredScheduler.schedule(() -> executor.submit(this::recheckDeferredCooldownCandidates),
                    delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    void recheckDeferredCooldownCandidates() {
        if (!isAutoRepostRuntimeEnabled()) return;
        long now = clock.millis();
        if (isInAutoRepostQuietWindow(now)) {
            if (!deferredSourceReadyAtMs.isEmpty()) {
                long nextWakeMs = com.xops.hub.config.XOpsProperties.nextQuietWindowEndEpochMs(
                        now, AssistantRetention.ZONE, quietStartHour(), quietEndHour());
                long delayMs = Math.max(1_000L, nextWakeMs - now);
                try {
                    deferredScheduler.schedule(() -> executor.submit(this::recheckDeferredCooldownCandidates),
                            delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }
            return;
        }
        List<String> readySourceIds = new ArrayList<>();
        for (var entry : deferredSourceReadyAtMs.entrySet()) {
            if (now >= entry.getValue()) {
                readySourceIds.add(entry.getKey());
            }
        }
        // Also scan persisted deferred records in auto_repost so deferred items survive restarts
        List<ObjectNode> sourcesToRecheck = store.tx(tx -> {
            java.util.Set<String> targetIds = new java.util.LinkedHashSet<>(readySourceIds);
            for (ObjectNode rec : tx.all("auto_repost")) {
                if (!rec.path("deferredPendingEval").asBoolean(false)) continue;
                long untilMs = rec.path("deferredUntilMs").asLong(0L);
                if (untilMs > 0L && now >= untilMs) {
                    String aid = rec.path("authorId").asText("").strip();
                    if (!aid.isBlank() && now >= deferredSourceReadyAtMs.getOrDefault(aid, 0L)) targetIds.add(aid);
                }
            }
            List<ObjectNode> found = new ArrayList<>();
            for (String sid : targetIds) {
                ObjectNode s = tx.get("source", sid);
                if (s != null && "ACTIVE".equals(s.path("status").asText(""))
                        && s.path("autoRepostEnabled").asBoolean(false)) {
                    found.add(s.deepCopy());
                } else {
                    deferredSourceReadyAtMs.remove(sid);
                }
            }
            return found;
        });

        for (ObjectNode src : sourcesToRecheck) {
            String sid = src.path("authorId").asText(src.path("id").asText(""));
            deferredSourceReadyAtMs.remove(sid);
            List<ObjectNode> eligibleMaterials = store.tx(tx -> {
                List<ObjectNode> list = new ArrayList<>();
                for (ObjectNode m : tx.rewriteWindow(sid, "", now - MAX_FRESHNESS_AGE_MS, now)) {
                    ObjectNode rec = tx.get("auto_repost", m.path("id").asText(""));
                    if (rec == null || (rec.path("deferredPendingEval").asBoolean(false)
                            && now >= rec.path("deferredUntilMs").asLong(0L))) {
                        list.add(m.deepCopy());
                    }
                }
                list.sort(Comparator.comparingLong((ObjectNode m) -> m.path("publishedAt").asLong(0L)).reversed());
                return list.stream().limit(8).toList();
            });
            if (!eligibleMaterials.isEmpty()) {
                processAuthorBatchInternal(src, eligibleMaterials, false, false, true, null);
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${xops.auto-repost-patrol-ms:1800000}",
            initialDelayString = "${xops.auto-repost-patrol-initial-delay-ms:60000}"
    )
    public void scheduledOwnerIdleRepostCheck() {
        if (!isAutoRepostRuntimeEnabled()) return;
        long nowMs = clock.millis();
        if (isInAutoRepostQuietWindow(nowMs)) {
            scheduleQuietWindowEndWakeup(nowMs);
            return;
        }
        executor.submit(() -> {
            try {
                runOwnerIdleRepostPatrol();
            } catch (Exception ex) {
                log.warn("Scheduled @elidlicom idle auto-repost patrol failed: {}", ex.getMessage());
            }
        });
    }

    /**
     * Shared interval dispatcher outside quiet hours (00:00:00–11:59:59 CST by default):
     * enforces the single global @elidlicom time lock (1h + 1~20m jitter or configured minutes),
     * coordinates both Auto Quote-Repost and High-Traffic Pure-Text Wash-Rewrite, and schedules
     * the exact next wakeup so multiple interval sends happen reliably across the active window.
     */
    ObjectNode runOwnerIdleRepostPatrol() {
        if (!isAutoRepostRuntimeEnabled()) {
            return object().put("status", "LOCAL_DISABLED");
        }
        long nowMs = clock.millis();
        if (isInAutoRepostQuietWindow(nowMs)) {
            scheduleQuietWindowEndWakeup(nowMs);
            return object()
                    .put("status", "QUIET_HOURS")
                    .put("quietStartHour", quietStartHour())
                    .put("quietEndHour", quietEndHour())
                    .put("reason", quietWindowReason());
        }
        record SourceOrderEntry(ObjectNode source, boolean authorCdActive, int tierMinutes, long lastActionAtMs) { }
        record PatrolSnapshot(
                String status,
                String ownerAccountId,
                String ownerUsername,
                ObjectNode settings,
                long localLatestOwnerTweetAtMs,
                long cachedLiveTweetAtMs,
                long cachedLiveCheckedAtMs,
                List<ObjectNode> unlockedRepostSources,
                List<ObjectNode> orderedRewriteSources,
                boolean anyRepostSourceConfigured,
                List<AutoPublishMode> recentAutoModesNewestFirst
        ) { }

        PatrolSnapshot snapshot = store.tx(tx -> {
            ObjectNode owner = tx.ownerBinding();
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (owner == null || owner.path("accountId").asText("").isBlank()) {
                return new PatrolSnapshot("NO_OWNER", "", "elidlicom", settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }
            if (!settings.path("enabled").asBoolean(true)) {
                return new PatrolSnapshot("DISABLED", "", "elidlicom", settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }
            String ownerAccountId = owner.path("accountId").asText("");
            String ownerUsername = owner.path("username").asText("").replaceFirst("^@+", "").strip();
            if (ownerUsername.isBlank()) {
                return new PatrolSnapshot("OWNER_CHECK_FAILED", ownerAccountId, "", settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }

            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            int publishedToday = autoPublicationsToday(tx, nowMs);
            Map<String, Long> latestRepostByAuthorKey = new java.util.HashMap<>();
            Map<String, Long> latestRewriteByAuthorKey = new java.util.HashMap<>();
            for (ObjectNode rec : tx.repostActivityForCooldown(nowMs - SAME_AUTHOR_COOLDOWN_MS)) {
                String recStatus = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0L)));
                    String aid = rec.path("authorId").asText("").strip();
                    String uname = rec.path("username").asText("").replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
                    if (!aid.isBlank() && recAt > 0L) latestRepostByAuthorKey.merge("id:" + aid, recAt, Math::max);
                    else if (!uname.isBlank() && recAt > 0L) latestRepostByAuthorKey.merge("u:" + uname, recAt, Math::max);
                }
            }
            for (ObjectNode rec : tx.all("auto_rewrite")) {
                String recStatus = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0L)));
                    String aid = rec.path("authorId").asText("").strip();
                    String uname = rec.path("username").asText("").replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
                    if (!aid.isBlank() && recAt > 0L) latestRewriteByAuthorKey.merge("id:" + aid, recAt, Math::max);
                    else if (!uname.isBlank() && recAt > 0L) latestRewriteByAuthorKey.merge("u:" + uname, recAt, Math::max);
                }
            }
            if (publishedToday >= dailyLimit) {
                return new PatrolSnapshot("DAILY_LIMIT_REACHED", ownerAccountId, ownerUsername, settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }

            int defaultTier = settings.path("intervalMinutes").asInt(5);
            List<SourceOrderEntry> unlockedRepostEntries = new ArrayList<>();
            boolean anyRepostConfigured = false;
            List<SourceOrderEntry> rewriteEntries = new ArrayList<>();
            for (ObjectNode s : tx.all("source")) {
                if (!"ACTIVE".equals(s.path("status").asText(""))) continue;
                String aid = s.path("authorId").asText(s.path("id").asText("")).strip();
                String uname = s.path("username").asText("").replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
                int tier = SourceCollector.resolveTierMinutes(s, defaultTier);
                if (s.path("autoRepostEnabled").asBoolean(false)) {
                    anyRepostConfigured = true;
                    long lastAuthorAt = Math.max(
                            aid.isBlank() ? 0L : latestRepostByAuthorKey.getOrDefault("id:" + aid, 0L),
                            uname.isBlank() ? 0L : latestRepostByAuthorKey.getOrDefault("u:" + uname, 0L)
                    );
                    boolean authorCd = lastAuthorAt > 0L && nowMs - lastAuthorAt >= 0L && nowMs - lastAuthorAt < SAME_AUTHOR_COOLDOWN_MS;
                    // Strictly keep 16-hour per-author lock after quote-reposting an account
                    if (!authorCd) {
                        unlockedRepostEntries.add(new SourceOrderEntry(s.deepCopy(), false, tier, lastAuthorAt));
                    }
                }
                if (s.path("autoRewriteEnabled").asBoolean(false)) {
                    long lastRwAt = Math.max(
                            aid.isBlank() ? 0L : latestRewriteByAuthorKey.getOrDefault("id:" + aid, 0L),
                            uname.isBlank() ? 0L : latestRewriteByAuthorKey.getOrDefault("u:" + uname, 0L)
                    );
                    rewriteEntries.add(new SourceOrderEntry(s.deepCopy(), false, tier, lastRwAt));
                }
            }
            if (!anyRepostConfigured && rewriteEntries.isEmpty()) {
                return new PatrolSnapshot("NO_CONFIGURED_SOURCES", ownerAccountId, ownerUsername, settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }
            unlockedRepostEntries.sort(Comparator.comparingInt(SourceOrderEntry::tierMinutes)
                    .thenComparingLong(SourceOrderEntry::lastActionAtMs));
            rewriteEntries.sort(Comparator.comparingLong(SourceOrderEntry::lastActionAtMs)
                    .thenComparingInt(SourceOrderEntry::tierMinutes));

            long localLatest = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUsername, "", nowMs);
            ObjectNode observedNode = latestOwnerObservation(tx, ownerAccountId);
            long cachedLiveTs = observedNode == null ? 0L : observedNode.path("latestTweetAt").asLong(0L);
            long cachedCheckedAt = observedNode != null && ownerUsername.equalsIgnoreCase(observedNode.path("username").asText())
                    ? observedNode.path("checkedAt").asLong(0L) : 0L;
            List<AutoPublishMode> recentAutoModes = collectRecentAutoPublishModesNewestFirst(tx, 2);
            return new PatrolSnapshot(
                    "READY",
                    ownerAccountId,
                    ownerUsername,
                    settings.deepCopy(),
                    localLatest,
                    cachedLiveTs,
                    cachedCheckedAt,
                    unlockedRepostEntries.stream().map(SourceOrderEntry::source).toList(),
                    rewriteEntries.stream().map(SourceOrderEntry::source).toList(),
                    anyRepostConfigured,
                    recentAutoModes
            );
        });

        if (!"READY".equals(snapshot.status())) {
            return object().put("status", snapshot.status());
        }

        long liveLatest = snapshot.cachedLiveTweetAtMs();
        boolean recentlyCheckedLive = snapshot.cachedLiveCheckedAtMs() > 0L
                && nowMs - snapshot.cachedLiveCheckedAtMs() >= 0L
                && nowMs - snapshot.cachedLiveCheckedAtMs() < OWNER_TIMELINE_MAX_AGE_MS;
        if (!recentlyCheckedLive) {
            liveLatest = refreshOwnerTimeline(snapshot.ownerAccountId(), snapshot.ownerUsername());
            if (liveLatest < 0L) return object().put("status", "OWNER_CHECK_FAILED");
        }

        long latestOwnerAt = Math.max(snapshot.localLatestOwnerTweetAtMs(), Math.max(0L, liveLatest));

        long checkNowMs = clock.millis();
        long requiredGapMs = resolveEffectiveOwnerGapMs(snapshot.settings(), latestOwnerAt);
        int jitterMinutes = resolveOwnerTweetJitterMinutes(latestOwnerAt);
        if (requiredGapMs > 0L && latestOwnerAt > 0L
                && checkNowMs - latestOwnerAt >= 0L
                && checkNowMs - latestOwnerAt <= requiredGapMs) {
            long readyAtMs = latestOwnerAt + requiredGapMs + 1_500L;
            schedulePatrolWakeupAt(readyAtMs, checkNowMs);
            long remainingMin = Math.max(1L, (requiredGapMs - (checkNowMs - latestOwnerAt) + 59_999L) / 60_000L);
            return object()
                    .put("status", "RECENTLY_TWEETED")
                    .put("latestOwnerTweetAt", latestOwnerAt)
                    .put("jitterMinutes", jitterMinutes)
                    .put("requiredMinutes", Math.round(requiredGapMs / 60_000.0))
                    .put("remainingMinutes", remainingMin);
        }

        boolean hasRepostPool = !snapshot.unlockedRepostSources().isEmpty();
        boolean hasRewritePool = !snapshot.orderedRewriteSources().isEmpty();
        if (!hasRepostPool && !hasRewritePool) {
            return object()
                    .put("status", "NO_ELIGIBLE_CANDIDATE")
                    .put("reason", snapshot.anyRepostSourceConfigured() ? "ALL_REPOST_SOURCES_IN_16H_LOCK" : "NO_ACTIVE_SOURCES")
                    .put("latestOwnerTweetAt", latestOwnerAt)
                    .put("jitterMinutes", jitterMinutes);
        }

        // Only one mode has eligible sources configured -> run that mode directly without consuming a random draw
        if (hasRepostPool && !hasRewritePool) {
            ObjectNode rpOnly = tryPatrolRepostSources(snapshot.unlockedRepostSources(), snapshot.ownerAccountId(), latestOwnerAt, jitterMinutes);
            if (rpOnly != null) {
                return rpOnly.put("dispatchOrder", "ONLY_REPOST_CONFIGURED");
            }
            return object().put("status", "NO_ELIGIBLE_CANDIDATE").put("latestOwnerTweetAt", latestOwnerAt).put("jitterMinutes", jitterMinutes);
        }
        if (!hasRepostPool && hasRewritePool) {
            ObjectNode rwOnly = tryPatrolRewriteSources(snapshot.orderedRewriteSources(), snapshot.ownerAccountId(), snapshot.ownerUsername(), latestOwnerAt, jitterMinutes);
            if (rwOnly != null) {
                return rwOnly.put("dispatchOrder", "ONLY_REWRITE_AVAILABLE");
            }
            return object().put("status", "NO_ELIGIBLE_CANDIDATE").put("latestOwnerTweetAt", latestOwnerAt).put("jitterMinutes", jitterMinutes);
        }

        // Both REPOST and REWRITE are available:
        // Odd = REPOST, Even = REWRITE; if 2 consecutive auto-publishes were identical, skip random and force the other!
        AutoPublishOrderDecision orderDecision = resolveNextAutoPublishOrder(
                snapshot.recentAutoModesNewestFirst(), this.randomDrawSupplier);

        if (orderDecision.primaryMode() == AutoPublishMode.REPOST) {
            ObjectNode rpResult = tryPatrolRepostSources(snapshot.unlockedRepostSources(), snapshot.ownerAccountId(), latestOwnerAt, jitterMinutes);
            if (rpResult != null) {
                return rpResult
                        .put("dispatchOrder", orderDecision.reasonCode())
                        .put("forcedByTwoStreak", orderDecision.forcedByTwoStreak())
                        .put("randomDraw", orderDecision.randomNumber());
            }
            ObjectNode rwFallback = tryPatrolRewriteSources(snapshot.orderedRewriteSources(), snapshot.ownerAccountId(), snapshot.ownerUsername(), latestOwnerAt, jitterMinutes);
            if (rwFallback != null) {
                return rwFallback
                        .put("dispatchOrder", orderDecision.reasonCode() + "_FALLBACK_REWRITE")
                        .put("forcedByTwoStreak", orderDecision.forcedByTwoStreak())
                        .put("randomDraw", orderDecision.randomNumber());
            }
        } else {
            ObjectNode rwResult = tryPatrolRewriteSources(snapshot.orderedRewriteSources(), snapshot.ownerAccountId(), snapshot.ownerUsername(), latestOwnerAt, jitterMinutes);
            if (rwResult != null) {
                return rwResult
                        .put("dispatchOrder", orderDecision.reasonCode())
                        .put("forcedByTwoStreak", orderDecision.forcedByTwoStreak())
                        .put("randomDraw", orderDecision.randomNumber());
            }
            ObjectNode rpFallback = tryPatrolRepostSources(snapshot.unlockedRepostSources(), snapshot.ownerAccountId(), latestOwnerAt, jitterMinutes);
            if (rpFallback != null) {
                return rpFallback
                        .put("dispatchOrder", orderDecision.reasonCode() + "_FALLBACK_REPOST")
                        .put("forcedByTwoStreak", orderDecision.forcedByTwoStreak())
                        .put("randomDraw", orderDecision.randomNumber());
            }
        }

        return object()
                .put("status", "NO_ELIGIBLE_CANDIDATE")
                .put("dispatchOrder", orderDecision.reasonCode())
                .put("latestOwnerTweetAt", latestOwnerAt)
                .put("jitterMinutes", jitterMinutes);
    }

    private ObjectNode tryPatrolRepostSources(List<ObjectNode> unlockedSources, String checkedOwnerId, long latestOwnerAt, int jitterMinutes) {
        if (unlockedSources == null || unlockedSources.isEmpty()) return null;
        // Pass 1: Prefer newly published content within the 48h freshness window
        for (ObjectNode source : unlockedSources) {
            List<ObjectNode> freshCandidates = loadRepostCandidatesForWindow(source, PATROL_MAX_FRESHNESS_AGE_MS, false);
            if (freshCandidates.isEmpty()) continue;
            String publishedMaterialId = processAuthorBatchInternal(source, freshCandidates, true, false, false, checkedOwnerId);
            if (publishedMaterialId != null && !publishedMaterialId.isBlank()) {
                return object()
                        .put("status", recordedDispatchStatus("auto_repost", publishedMaterialId))
                        .put("mode", "REPOST")
                        .put("repostSourceTier", "FRESH")
                        .put("materialId", publishedMaterialId)
                        .put("sourceUsername", source.path("username").asText(""))
                        .put("latestOwnerTweetAt", latestOwnerAt)
                        .put("jitterMinutes", jitterMinutes);
            }
        }
        // Pass 2: No new qualifying content across unlocked sources -> fall back to recent posts (strictly keeping 16h author lock)
        for (ObjectNode source : unlockedSources) {
            List<ObjectNode> recentCandidates = loadRepostCandidatesForWindow(source, RECENT_REPOST_FALLBACK_MAX_AGE_MS, true);
            if (recentCandidates.isEmpty()) continue;
            String publishedMaterialId = processAuthorBatchInternal(source, recentCandidates, true, true, false, checkedOwnerId);
            if (publishedMaterialId != null && !publishedMaterialId.isBlank()) {
                return object()
                        .put("status", recordedDispatchStatus("auto_repost", publishedMaterialId))
                        .put("mode", "REPOST")
                        .put("repostSourceTier", "RECENT_FALLBACK")
                        .put("materialId", publishedMaterialId)
                        .put("sourceUsername", source.path("username").asText(""))
                        .put("latestOwnerTweetAt", latestOwnerAt)
                        .put("jitterMinutes", jitterMinutes);
            }
        }
        return null;
    }

    private List<ObjectNode> loadRepostCandidatesForWindow(ObjectNode source, long windowMs, boolean recentArchiveFallback) {
        List<ObjectNode> synced = null;
        if (sources != null) {
            synced = recentArchiveFallback
                    ? sources.syncSourceMaterialsForRecentRepost(source, windowMs)
                    : sources.syncSourceMaterialsForPatrol(source, windowMs);
        }
        if (synced != null && !synced.isEmpty()) {
            return synced;
        }
        String aid = source.path("authorId").asText(source.path("id").asText("")).strip();
        long scanNow = clock.millis();
        return store.tx(tx -> SourceCollector.storedRepostCandidates(tx, aid, scanNow, windowMs));
    }

    void processCandidate(ObjectNode sourceSnapshot, ObjectNode materialSnapshot) {
        if (sourceSnapshot == null || materialSnapshot == null) return;
        processAuthorBatch(sourceSnapshot, List.of(materialSnapshot));
    }

    void processAuthorBatch(ObjectNode sourceSnapshot, List<ObjectNode> materialSnapshots) {
        processAuthorBatchInternal(sourceSnapshot, materialSnapshots, false, false, false, null);
    }

    private String processAuthorBatchInternal(ObjectNode sourceSnapshot, List<ObjectNode> materialSnapshots,
            boolean patrolMode, boolean recentArchiveFallback, boolean cooldownWakeup, String patrolOwnerId) {
        if (sourceSnapshot == null || materialSnapshots == null || materialSnapshots.isEmpty()) return "";
        long now = clock.millis();
        if (isInAutoRepostQuietWindow(now)) {
            return "";
        }
        long maxAgeMs = recentArchiveFallback
                ? RECENT_REPOST_FALLBACK_MAX_AGE_MS
                : (patrolMode ? PATROL_MAX_FRESHNESS_AGE_MS : MAX_FRESHNESS_AGE_MS);
        List<ObjectNode> filtered = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
        for (ObjectNode m : materialSnapshots) {
            if (m == null) continue;
            String mid = m.path("id").asText("").strip();
            if (!mid.matches("[0-9]+") || !seenIds.add(mid)) continue;
            if (m.path("selfThreadReply").asBoolean(false)) continue;
            if (!Set.of("POST", "QUOTE").contains(m.path("kind").asText("POST"))) continue;
            String combinedText = AssistantTopics.text(m);
            if (combinedText.isBlank() || combinedText.codePointCount(0, combinedText.length()) < 10) continue;
            long pubAt = m.path("publishedAt").asLong(0L);
            if (pubAt <= 0L || now - pubAt > maxAgeMs) continue;
            filtered.add(m);
        }
        if (filtered.isEmpty()) return "";

        boolean checkTimeline = !patrolMode || cooldownWakeup || store.tx(tx -> filtered.stream()
                .map(m -> tx.get("auto_repost", m.path("id").asText()))
                .anyMatch(r -> r != null && r.path("deferredPendingEval").asBoolean()
                        && (patrolMode || clock.millis() >= r.path("deferredUntilMs").asLong())));
        String targetSourceId = sourceSnapshot.path("authorId").asText(sourceSnapshot.path("id").asText(""));
        ObjectNode checkedOwner = checkTimeline ? store.tx(tx -> {
            ObjectNode source = tx.get("source", targetSourceId);
            if (source == null || !"ACTIVE".equals(source.path("status").asText()) || !source.path("autoRepostEnabled").asBoolean()
                    || !AssistantWorkflow.settings(tx).path("enabled").asBoolean(true)
                    || filtered.stream().noneMatch(m -> SourceCollector.belongsToSource(m, targetSourceId))) return null;
            return tx.ownerBinding();
        }) : null;
        if (checkTimeline) {
            if (checkedOwner == null) return "";
            String ownerId = checkedOwner.path("accountId").asText();
            String username = checkedOwner.path("username").asText("").replaceFirst("^@+", "");
            if (refreshOwnerTimeline(ownerId, username) < 0L) {
                long checkAt = clock.millis();
                scheduleDeferredCdWakeup(targetSourceId, checkAt + PaidCapabilityGate.RETRY_INTERVAL_MS, checkAt);
                return "";
            }
        }

        record ClaimedItem(String materialId, String topicId, ObjectNode material) { }
        record BatchClaimContext(
                String ownerId,
                String ownerUsername,
                ObjectNode source,
                List<ClaimedItem> claimedItems,
                String styleProfile,
                String preferencesText,
                String postPolishPrompt,
                DualCooldown deferredCd,
                String deferredSourceId,
                boolean onlyDirectPassAllowed,
                boolean deferToScheduledRewriteTurn
        ) { }

        BatchClaimContext ctx = store.tx(tx -> {
            ObjectNode owner = tx.ownerBinding();
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (owner == null || owner.path("accountId").asText("").isBlank()
                    || (patrolOwnerId != null && !patrolOwnerId.equals(owner.path("accountId").asText()))
                    || (checkedOwner != null && !checkedOwner.path("accountId").equals(owner.path("accountId")))
                    || !settings.path("enabled").asBoolean(true)) {
                return null;
            }
            String sourceId = sourceSnapshot.path("authorId").asText(sourceSnapshot.path("id").asText(""));
            ObjectNode liveSource = tx.get("source", sourceId);
            if (liveSource == null || !"ACTIVE".equals(liveSource.path("status").asText())
                    || !liveSource.path("autoRepostEnabled").asBoolean(false)) {
                return null;
            }
            long enabledAt = liveSource.path("autoRepostEnabledAt").asLong(0L);
            String ownerAccountId = owner.path("accountId").asText("");
            String ownerUsername = owner.path("username").asText("i");
            String candidateAuthorId = liveSource.path("authorId").asText(sourceId);
            String candidateUsername = liveSource.path("username").asText("");

            List<ClaimedItem> eligible = new ArrayList<>();
            for (ObjectNode snapMat : filtered) {
                String mid = snapMat.path("id").asText("");
                long pubAt = snapMat.path("publishedAt").asLong(0L);
                if (!patrolMode && !recentArchiveFallback && enabledAt > 0L && pubAt < enabledAt) continue;
                ObjectNode existingRec = tx.get("auto_repost", mid);
                if (existingRec != null) {
                    if (!repostAuthorized(tx, existingRec, ownerAccountId)) continue;
                    if (existingRec.path("autoRetryAt").asLong() > 0L) continue;
                    if (patrolMode || recentArchiveFallback) {
                        String recStatus = existingRec.path("status").asText("");
                        boolean patrolRetryable = Set.of(
                                "SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS", "JUDGE_FAILED"
                        ).contains(recStatus);
                        if (!patrolRetryable && !correctedQuoteVerdict(existingRec)) continue;
                    } else {
                        boolean canUnlockDeferred = existingRec.path("deferredPendingEval").asBoolean(false)
                                && now >= existingRec.path("deferredUntilMs").asLong(0L);
                        if (!canUnlockDeferred && !correctedQuoteVerdict(existingRec)) continue;
                    }
                }
                ObjectNode liveMat = tx.rewriteMaterial(mid, clock.millis());
                if (!SourceCollector.belongsToSource(snapMat, sourceId) || !SourceCollector.belongsToSource(liveMat, sourceId)
                        || !tx.retainedMaterialAuthorMatches(mid, sourceId)) continue;
                long livePublishedAt = liveMat.path("publishedAt").asLong();
                if (livePublishedAt <= 0 || livePublishedAt > clock.millis() || clock.millis() - livePublishedAt > maxAgeMs) continue;
                String topicId = tx.publicationTopic(liveMat, mid);
                ObjectNode prior = repostConflict(tx, mid, topicId);
                if (prior != null) {
                    if ("PUBLISHED".equals(prior.path("status").asText())) {
                        tx.put("auto_repost", mid, baseRecord(liveSource, liveMat, topicId, now, ownerAccountId)
                                .put("status","SKIPPED_DUPLICATE").put("reason","该素材或选题已有发布回执"));
                    }
                    continue;
                }
                eligible.add(new ClaimedItem(mid, topicId, liveMat));
                if (patrolMode && eligible.size() >= (recentArchiveFallback ? 3 : 8)) {
                    break;
                }
            }
            if (eligible.isEmpty()) return null;

            // Check duplicate topics & daily limit first for non-CD permanent guards, THEN check Dual CD BEFORE any LLM call!
            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            int publishedToday = autoPublicationsToday(tx, now);

            DualCooldown dualCd = computeLiveDualCooldown(
                    tx, settings, ownerAccountId, ownerUsername, candidateAuthorId, candidateUsername, "", now);
            if (dualCd.globalCdActive()) {
                long wakeAtMs = dualCd.globalReadyAtMs();
                for (ClaimedItem item : eligible) {
                    tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now, ownerAccountId)
                            .put("status", "SKIPPED_COOLDOWN")
                            .put("deferredPendingEval", true)
                            .put("deferredUntilMs", wakeAtMs)
                            .put("reason", dualCd.blockingReasonText()));
                }
                return new BatchClaimContext(ownerAccountId, ownerUsername, liveSource, List.of(),
                        "", "", "", dualCd, sourceId, false, false);
            }

            // When NOT in patrolMode (i.e. real-time ingest), if rewrite sources are also active, consult the shared
            // odd=REPOST / even=REWRITE + 2-streak anti-repeat rule so real-time ingests cannot starve or break order!
            if (!patrolMode) {
                boolean anyRewriteEnabled = tx.all("source").stream()
                        .anyMatch(s -> "ACTIVE".equals(s.path("status").asText("")) && s.path("autoRewriteEnabled").asBoolean(false));
                if (anyRewriteEnabled) {
                    List<AutoPublishMode> lastModes = collectRecentAutoPublishModesNewestFirst(tx, 2);
                    AutoPublishOrderDecision liveOrder = resolveNextAutoPublishOrder(lastModes, this.randomDrawSupplier);
                    if (liveOrder.primaryMode() == AutoPublishMode.REWRITE) {
                        long nextGap = resolveOwnerTweetRequiredGapMs(now);
                        String deferReason = liveOrder.forcedByTwoStreak()
                                ? "已连续 2 次自动引用转发，本轮直接切换执行洗稿发送"
                                : "本轮随机抽中双数（洗稿发送），优先执行洗稿发送";
                        for (ClaimedItem item : eligible) {
                            tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now, ownerAccountId)
                                    .put("status", "SKIPPED_COOLDOWN")
                                    .put("deferredPendingEval", true)
                                    .put("deferredUntilMs", now + nextGap)
                                    .put("reason", deferReason));
                        }
                        return new BatchClaimContext(ownerAccountId, ownerUsername, liveSource, List.of(),
                                "", "", "", null, sourceId, false, true);
                    }
                }
            }

            if (publishedToday >= dailyLimit) {
                for (ClaimedItem item : eligible) {
                    tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now, ownerAccountId)
                            .put("status", "SKIPPED_LIMIT")
                            .put("deferredPendingEval", false)
                            .put("reason", "已达单日自动引用转发保护上限（" + dailyLimit + " 条）"));
                }
                return null;
            }

            List<ClaimedItem> evalItems = eligible;
            if (dualCd.authorCdActive()) {
                if (patrolMode || recentArchiveFallback) {
                    // Strictly keep 16h same-author lock during interval patrol and recent-content fallback
                    return null;
                }
                evalItems = new ArrayList<>();
                for (ClaimedItem item : eligible) {
                    if (mightBeDirectPassCandidate(item.material())) {
                        evalItems.add(item);
                    } else {
                        tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now, ownerAccountId)
                                .put("status", "SKIPPED_AUTHOR_COOLDOWN")
                                .put("deferredPendingEval", false)
                                .put("reason", dualCd.blockingReasonText()));
                    }
                }
                if (evalItems.isEmpty()) {
                    return null;
                }
            }

            for (ClaimedItem item : evalItems) {
                tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now, ownerAccountId)
                        .put("status", "EVALUATING")
                        .put("quoteSourceFingerprint", quoteSourceFingerprint(item.material()))
                        .put("quoteAuthorFollowers", quoteAuthorFollowers(liveSource, item.material()))
                        .put("strictSixteenHourAuthorLock", patrolMode || recentArchiveFallback)
                        .put("candidateWindowMs", maxAgeMs)
                        .put("deferredPendingEval", false));
            }
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            String polishPrompt = AssistantWorkflow.isPostPolishEnabled(settings)
                    ? AssistantWorkflow.resolvePostPolishPrompt(settings)
                    : "";
            return new BatchClaimContext(
                    ownerAccountId,
                    ownerUsername,
                    liveSource,
                    evalItems,
                    composeOwnerQuoteVoiceProfile(tx),
                    prefs.path("text").asText(""),
                    polishPrompt,
                    null,
                    sourceId,
                    dualCd.authorCdActive(),
                    false
            );
        });

        if (ctx == null) return "";
        if (ctx.deferToScheduledRewriteTurn()) {
            scheduleDeferredCdWakeup(ctx.deferredSourceId(), now + resolveOwnerTweetRequiredGapMs(now), now);
            // Execute a rewrite turn immediately while the global time lock is free; if no rewrite candidate is eligible,
            // fall back to patrol repost so the open slot is never wasted.
            List<ObjectNode> rewriteSources = store.tx(tx -> tx.all("source").stream()
                    .filter(s -> "ACTIVE".equals(s.path("status").asText("")) && s.path("autoRewriteEnabled").asBoolean(false))
                    .map(ObjectNode::deepCopy)
                    .toList());
            ObjectNode rwDone = tryPatrolRewriteSources(rewriteSources, ctx.ownerId(), ctx.ownerUsername(), 0L, 0);
            if (rwDone == null) {
                processAuthorBatchInternal(sourceSnapshot, materialSnapshots, true, false, false, ctx.ownerId());
            }
            return "";
        }
        if (ctx.deferredCd() != null && ctx.deferredCd().globalCdActive()) {
            scheduleDeferredCdWakeup(ctx.deferredSourceId(), ctx.deferredCd().globalReadyAtMs(), now);
            return "";
        }
        if (ctx.claimedItems().isEmpty()) return "";

        if (ctx.claimedItems().size() == 1) {
            ClaimedItem only = ctx.claimedItems().get(0);
            evaluateAndPublish(
                    only.materialId(),
                    ctx.ownerId(),
                    ctx.ownerUsername(),
                    only.topicId(),
                    ctx.source(),
                    only.material(),
                    ctx.styleProfile(),
                    ctx.preferencesText(),
                    now,
                    ctx.onlyDirectPassAllowed(),
                    true,
                    patrolMode || recentArchiveFallback
            );
            return wasDispatchedOrPublished(only.materialId()) ? only.materialId() : "";
        }

        // Same account judged ONCE across all fresh candidates in the batch!
        String textMode = ctx.source().path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = SourceCollector.resolveAutoRepostMinScore(ctx.source());
        List<PiAgentViralJudge.BatchCandidateItem> batchInputs = new ArrayList<>();
        ObjectNode sharedHotCtx = null;
        for (ClaimedItem item : ctx.claimedItems()) {
            long pubAt = item.material().path("publishedAt").asLong(0L);
            long ageMin = pubAt > 0L ? Math.max(0L, (now - pubAt) / 60_000L) : 0L;
            ThreePillarEvidence ev = buildThreePillarEvidence(item.materialId(), ctx.source(), item.material(), now);
            if (sharedHotCtx == null) sharedHotCtx = ev.currentHotContext();
            batchInputs.add(new PiAgentViralJudge.BatchCandidateItem(
                    item.materialId(),
                    item.material().path("text").asText(""),
                    item.material().path("quotedText").asText(""),
                    item.material().path("articleText").asText(""),
                    ageMin,
                    optionalLong(item.material(), "views"),
                    optionalLong(item.material(), "likes"),
                    optionalLong(item.material(), "reposts"),
                    optionalLong(item.material(), "replies"),
                    ev.factualEvidence(),
                    ev.attentionEvidence()
            ));
        }

        String batchAuthorBio = resolveAuthorBio(ctx.source(), ctx.claimedItems().stream().map(ClaimedItem::material).toList());
        String batchText = ctx.claimedItems().stream()
                .map(i -> i.material().path("text").asText(""))
                .filter(t -> !t.isBlank())
                .limit(3)
                .collect(java.util.stream.Collectors.joining("\n"));
        List<String> recentStances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                tx,
                ctx.source().path("username").asText(""),
                batchAuthorBio,
                batchText,
                "",
                4
        ));
        final PiAgentViralJudge.AccountBatchJudgment batchRes;
        try {
            batchRes = judge.evaluateAccountBatch(new PiAgentViralJudge.AccountBatchInput(
                    ctx.source().path("username").asText(""),
                    ctx.source().path("displayName").asText(""),
                    ctx.source().path("verified").asBoolean(false),
                    minScore,
                    ctx.source().path("autoRepostRuleHint").asText(""),
                    ctx.styleProfile(),
                    ctx.preferencesText(),
                    !useTemplate,
                    sharedHotCtx,
                    ctx.postPolishPrompt(),
                    batchInputs,
                    ctx.onlyDirectPassAllowed(),
                    "",
                    "",
                    batchAuthorBio,
                    recentStances
            ));
        } catch (RuntimeException ex) {
            long failAt = clock.millis();
            store.tx(tx -> {
                for (ClaimedItem item : ctx.claimedItems()) {
                    ObjectNode rec = tx.get("auto_repost", item.materialId());
                    if (rec != null && "EVALUATING".equals(rec.path("status").asText(""))) {
                        tx.put("auto_repost", item.materialId(), rec.deepCopy()
                                .put("status", "JUDGE_FAILED")
                                .put("evaluatedAt", failAt)
                                .put("error", safeError(ex)));
                    }
                }
                return null;
            });
            return "";
        }

        long evaluatedAt = clock.millis();
        String winnerId = batchRes.selectedMaterialId();
        ClaimedItem winnerItem = null;
        PiAgentViralJudge.ViralJudgment winnerJudgment = null;
        long nextGlobalGapUntilMs = evaluatedAt + resolveOwnerTweetRequiredGapMs(evaluatedAt);
        boolean deferredExtraDirectPass = false;
        for (ClaimedItem item : ctx.claimedItems()) {
            PiAgentViralJudge.ViralJudgment j = batchRes.byMaterialId().get(item.materialId());
            if (!winnerId.isBlank() && winnerId.equals(item.materialId()) && j != null && j.shouldRepost()) {
                winnerItem = item;
                winnerJudgment = j;
            } else {
                boolean itemItselfQualifies = j != null && (j.shouldRepost()
                        || (recentArchiveFallback
                        && j.viralScore() >= minScore && j.accountFit() && j.credibility() >= 60 && j.factScore() >= 55));
                boolean extraQualifying = !winnerId.isBlank() && itemItselfQualifies
                        && (!ctx.onlyDirectPassAllowed() || (j != null && PiAgentViralJudge.isDirectPassCategory(j.category())));
                if (extraQualifying) {
                    deferredExtraDirectPass = true;
                }
                store.tx(tx -> {
                    ObjectNode rec = authorizedRepostAtStage(tx, item.materialId(), ctx.ownerId(), "EVALUATING");
                    if (rec != null) {
                        ObjectNode next = j != null ? applyJudgment(rec.deepCopy(), j, evaluatedAt) : rec.deepCopy();
                        if (extraQualifying) {
                            tx.put("auto_repost", item.materialId(), next
                                    .put("status", "SKIPPED_COOLDOWN")
                                    .put("deferredPendingEval", true)
                                    .put("deferredUntilMs", nextGlobalGapUntilMs)
                                    .put("reason", "同批已有优选推文先行发送，待该账号 16h 锁及全局时间锁解除后继续发送"));
                        } else if (ctx.onlyDirectPassAllowed()
                                && (j == null || !PiAgentViralJudge.isDirectPassCategory(j.category()))) {
                            tx.put("auto_repost", item.materialId(), next
                                    .put("status", "SKIPPED_AUTHOR_COOLDOWN")
                                    .put("deferredPendingEval", false)
                                    .put("reason", "16 小时内已转发过该作者（仅模型发布/功能更新/额度重置直通豁免）"));
                        } else {
                            tx.put("auto_repost", item.materialId(), next
                                    .put("status", "SKIPPED")
                                    .put("deferredPendingEval", false));
                        }
                    }
                    return null;
                });
            }
        }
        if (deferredExtraDirectPass) {
            scheduleDeferredCdWakeup(ctx.deferredSourceId(), nextGlobalGapUntilMs, evaluatedAt);
        }

        if (winnerItem != null && winnerJudgment != null) {
            String commentText = buildCommentText(useTemplate, ctx.source(), winnerItem.material(), winnerJudgment);
            if (commentText.isBlank()) {
                String wid = winnerItem.materialId();
                PiAgentViralJudge.ViralJudgment wj = winnerJudgment;
                store.tx(tx -> {
                    ObjectNode rec = tx.get("auto_repost", wid);
                    if (rec != null) {
                        tx.put("auto_repost", wid, applyJudgment(rec.deepCopy(), wj, evaluatedAt)
                                .put("status", "FAILED")
                                .put("error", "生成的附带评述为空，已中止自动转发"));
                    }
                    return null;
                });
                return "";
            }
            dispatchCommentAndPublish(
                    winnerItem.materialId(),
                    ctx.ownerId(),
                    ctx.ownerUsername(),
                    winnerItem.topicId(),
                    commentText,
                    winnerJudgment,
                    evaluatedAt,
                    true,
                    patrolMode || recentArchiveFallback
            );
            return wasDispatchedOrPublished(winnerItem.materialId()) ? winnerItem.materialId() : "";
        }
        return "";
    }

    private String recordedDispatchStatus(String table, String materialId) {
        return store.tx(tx -> {
            ObjectNode record = tx.get(table, materialId);
            return record == null ? "" : record.path("status").asText("");
        });
    }

    private boolean wasDispatchedOrPublished(String materialId) {
        return materialId != null && !materialId.isBlank()
                && Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recordedDispatchStatus("auto_repost", materialId));
    }

    private void evaluateAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            ObjectNode source,
            ObjectNode material,
            String styleProfile,
            String preferencesText,
            long now
    ) {
        evaluateAndPublish(materialId, ownerId, ownerUsername, topicId, source, material,
                styleProfile, preferencesText, now, false);
    }

    private void evaluateAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            ObjectNode source,
            ObjectNode material,
            String styleProfile,
            String preferencesText,
            long now,
            boolean onlyDirectPassAllowed
    ) {
        evaluateAndPublish(materialId, ownerId, ownerUsername, topicId, source, material,
                styleProfile, preferencesText, now, onlyDirectPassAllowed, true, false);
    }

    private void evaluateAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            ObjectNode source,
            ObjectNode material,
            String styleProfile,
            String preferencesText,
            long now,
            boolean onlyDirectPassAllowed,
            boolean enforceQuietWindow
    ) {
        evaluateAndPublish(materialId, ownerId, ownerUsername, topicId, source, material,
                styleProfile, preferencesText, now, onlyDirectPassAllowed, enforceQuietWindow, false);
    }

    private void evaluateAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            ObjectNode source,
            ObjectNode material,
            String styleProfile,
            String preferencesText,
            long now,
            boolean onlyDirectPassAllowed,
            boolean enforceQuietWindow,
            boolean strictSixteenHourAuthorLock
    ) {
        String textMode = source.path("autoRepostTextMode").asText("AI").toUpperCase();
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = SourceCollector.resolveAutoRepostMinScore(source);
        long publishedAt = material.path("publishedAt").asLong(0);
        long ageMinutes = publishedAt > 0 ? Math.max(0L, (now - publishedAt) / 60_000L) : 0L;
        ThreePillarEvidence evidence = buildThreePillarEvidence(materialId, source, material, now);

        String livePolishPrompt = store.tx(tx -> {
            ObjectNode s = AssistantWorkflow.settings(tx);
            return AssistantWorkflow.isPostPolishEnabled(s) ? AssistantWorkflow.resolvePostPolishPrompt(s) : "";
        });

        String singleUsername = material.path("username").asText("");
        String singleBio = resolveAuthorBio(source, List.of(material));
        List<String> singleStances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                tx, singleUsername, singleBio, material.path("text").asText(""), material.path("quotedText").asText(""), 4));

        final PiAgentViralJudge.ViralJudgment judgment;
        try {
            judgment = judge.evaluate(new PiAgentViralJudge.EvaluationInput(
                    singleUsername,
                    material.path("authorName").asText(source.path("displayName").asText("")),
                    material.path("authorVerified").asBoolean(source.path("verified").asBoolean(false)),
                    material.path("text").asText(""),
                    material.path("quotedText").asText(""),
                    material.path("articleText").asText(""),
                    ageMinutes,
                    optionalLong(material, "views"),
                    optionalLong(material, "likes"),
                    optionalLong(material, "reposts"),
                    optionalLong(material, "replies"),
                    minScore,
                    source.path("autoRepostRuleHint").asText(""),
                    styleProfile,
                    preferencesText,
                    !useTemplate,
                    evidence.factualEvidence(),
                    evidence.currentHotContext(),
                    evidence.attentionEvidence(),
                    false,
                    livePolishPrompt,
                    onlyDirectPassAllowed,
                    "",
                    "",
                    singleBio,
                    singleStances
            ));
        } catch (RuntimeException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec != null && "EVALUATING".equals(rec.path("status").asText())) {
                    tx.put("auto_repost", materialId, rec.deepCopy()
                            .put("status", "JUDGE_FAILED")
                            .put("evaluatedAt", clock.millis())
                            .put("error", safeError(ex)));
                }
                return null;
            });
            return;
        }

        long evaluatedAt = clock.millis();
        if (!judgment.shouldRepost()) {
            store.tx(tx -> {
                ObjectNode rec = authorizedRepostAtStage(tx, materialId, ownerId, "EVALUATING");
                if (rec != null) {
                    ObjectNode next = applyJudgment(rec.deepCopy(), judgment, evaluatedAt);
                    if (onlyDirectPassAllowed && !PiAgentViralJudge.isDirectPassCategory(judgment.category())) {
                        tx.put("auto_repost", materialId, next
                                .put("status", "SKIPPED_AUTHOR_COOLDOWN")
                                .put("deferredPendingEval", false)
                                .put("reason", "16 小时内已转发过该作者（仅模型发布/功能更新/额度重置直通豁免）"));
                    } else {
                        tx.put("auto_repost", materialId, next
                                .put("status", "SKIPPED")
                                .put("deferredPendingEval", false));
                    }
                }
                return null;
            });
            return;
        }

        String commentText = buildCommentText(useTemplate, source, material, judgment);
        if (commentText.isBlank()) {
            store.tx(tx -> {
                ObjectNode rec = authorizedRepostAtStage(tx, materialId, ownerId, "EVALUATING");
                if (rec != null) {
                    tx.put("auto_repost", materialId, applyJudgment(rec.deepCopy(), judgment, evaluatedAt)
                            .put("status", "FAILED")
                            .put("error", "生成的附带评述为空，已中止自动转发"));
                }
                return null;
            });
            return;
        }

        dispatchCommentAndPublish(materialId, ownerId, ownerUsername, topicId, commentText, judgment, evaluatedAt, enforceQuietWindow, strictSixteenHourAuthorLock);
    }

    private static String extractStatusIdFromUrl(String url) {
        XPostLink link = XPostLink.parse(url);
        return link == null ? null : link.tweetId();
    }

    private static boolean hasProtectedPublication(AssistantStore.Tx tx, String kind, String materialId, String topicId) {
        String original = resolveMaterialQuoteTweetId(tx, materialId, null);
        return tx.matchingQuoteOrRewrite(kind, materialId, original == null ? "" : original, topicId, true) != null;
    }

    private static ObjectNode repostConflict(AssistantStore.Tx tx, String materialId, String topicId) {
        String original = resolveMaterialQuoteTweetId(tx, materialId, null);
        for (String kind : List.of("publication", "auto_rewrite", "auto_repost")) {
            var conflict = tx.matchingQuoteOrRewrite(kind, materialId, original, topicId, true,
                    "auto_repost".equals(kind) ? materialId : "");
            if (conflict != null) return conflict;
        }
        return null;
    }

    private static String resolveMaterialQuoteTweetId(AssistantStore.Tx tx, String materialId, ObjectNode rec) {
        if (materialId != null && materialId.matches("[0-9]{2,20}")) {
            return materialId;
        }
        if (materialId != null && !materialId.isBlank()) {
            ObjectNode mat = tx.get("material", materialId);
            if (mat != null) {
                String matId = mat.path("id").asText("").strip();
                if (matId.matches("[0-9]{2,20}")) return matId;
                String fromMat = extractStatusIdFromUrl(mat.path("sourceUrl").asText(""));
                if (fromMat != null) return fromMat;
            }
        }
        if (rec != null) {
            String fromRec = extractStatusIdFromUrl(rec.path("sourceUrl").asText(""));
            if (fromRec != null) return fromRec;
        }
        return null;
    }

    private static void verifyManualQuoteAuthor(AssistantStore.Tx tx, String sourceKey, ObjectNode material, XPostLink requested) {
        String authorId = material.path("authorId").asText("");
        String handle = material.path("username").asText("").replaceFirst("^@+", "");
        ObjectNode source = tx.get("source", sourceKey);
        if (source == null) {
            for (var entry : tx.all("source")) {
                if ("REMOVED".equals(entry.path("status").asText())
                        || !sourceKey.equalsIgnoreCase(entry.path("username").asText("").replaceFirst("^@+", ""))) continue;
                if (source != null && !source.path("authorId").equals(entry.path("authorId")))
                    throw new XDataProviderException(409, "来源用户名对应多个账号，请按作者重新选择原帖");
                source = entry;
            }
        }
        boolean matches = source == null
                ? !sourceKey.isBlank() && (sourceKey.equals(authorId) || (!sourceKey.matches("[0-9]+") && sourceKey.equalsIgnoreCase(handle)))
                : SourceCollector.belongsToSource(material, source.path("authorId").asText(source.path("id").asText()));
        if (!matches) throw new XDataProviderException(409, "所选原帖与当前来源作者不一致");
        String linkHandle = requested == null ? null : requested.username();
        XPostLink storedLink = XPostLink.parse(material.path("sourceUrl").asText(""));
        if (linkHandle != null && !"i".equalsIgnoreCase(linkHandle) && !linkHandle.equalsIgnoreCase(handle)
                && (storedLink == null || !linkHandle.equalsIgnoreCase(storedLink.username()))
                && (source == null || !linkHandle.equalsIgnoreCase(source.path("username").asText(""))))
            throw new XDataProviderException(409, "原帖链接的作者与所选素材不一致");
    }

    public ObjectNode publishManualQuote(String accountId, String username, String rawSourceKey, ObjectNode body) {
        if (!store.isOwner(accountId)) {
            throw new XDataProviderException(403, "仅限内容助手所有者使用");
        }
        if (oauthService == null || publishClient == null) {
            throw new XDataProviderException(503, "引用发布服务未启用");
        }
        reconcileAcceptedReceipts();
        ObjectNode req = body == null ? object() : body;
        String commentText = OwnerQuoteStyleDistiller.stripWrappingQuotesOrFences(req.path("commentText").asText("")).strip();
        if (commentText.isBlank()) {
            throw new IllegalArgumentException("转发评述不能为空");
        }
        String clientRequestId = AssistantWorkflow.field(req, "clientRequestId");
        if (clientRequestId.length() > 128) {
            throw new IllegalArgumentException("请求标识过长");
        }
        String materialId = req.path("materialId").asText("").strip();
        String requestedSourceUrl = req.path("sourceUrl").asText("").strip();
        XPostLink requested = XPostLink.parse(requestedSourceUrl);
        if (!requestedSourceUrl.isBlank() && requested == null)
            throw new IllegalArgumentException("原帖链接无效");
        String selectedId = materialId.isBlank() && requested != null ? requested.tweetId() : materialId;
        if (selectedId.isBlank()) throw new IllegalArgumentException("缺少有效原帖推文 ID，无法引用转发");
        if (selectedId.matches("[0-9]{2,20}") && requested != null && !selectedId.equals(requested.tweetId()))
            throw new XDataProviderException(409, "所选素材与原帖链接的推文 ID 不一致");
        String pubKey = "manual-quote-" + (clientRequestId.isBlank()
                ? AssistantWorkflow.digest(object().put("accountId", accountId).put("material", materialId)
                        .put("sourceUrl", requestedSourceUrl).put("commentText", commentText))
                : clientRequestId);
        String sourceKey = rawSourceKey == null ? "" : rawSourceKey.replaceFirst("^@+", "").strip();

        ObjectNode snapshot = store.tx(tx -> {
            ObjectNode material = tx.rewriteMaterial(selectedId, clock.millis());
            if (material != null) return material;
            ObjectNode historical = tx.get("publication", pubKey);
            if (historical == null && selectedId.matches("[0-9]{2,20}"))
                historical = tx.protectedPublicationByDigest(AssistantWorkflow.digest(object().put("materialId", selectedId)
                        .put("quoteTweetId", selectedId).put("commentText", commentText)), accountId);
            if (historical == null || !selectedId.equals(historical.path("materialId").asText()))
                historical = tx.get("auto_repost", selectedId);
            return historical == null || !tx.publicationOwnerMatches(historical, accountId) || historical.path("authorId").asText("").isBlank()
                    ? null : historical.deepCopy().put("id", selectedId);
        });
        if (snapshot == null) {
            if (!selectedId.matches("[0-9]{2,20}") || sources == null)
                throw new XDataProviderException(409, "未能核实所选原帖及作者，请刷新来源");
            snapshot = sources.fetchTweetMaterialById(selectedId);
            if (snapshot == null || !selectedId.equals(snapshot.path("id").asText()))
                throw new XDataProviderException(409, "未能核实所选原帖及作者，请刷新来源");
        }
        ObjectNode selectedMaterial = snapshot;
        List<ObjectNode> pendingManual = pendingManualReceipts(accountId);
        record PreparedManualQuote(ObjectNode row, String quoteTweetId, String sourceUrl) { }

        PreparedManualQuote prepared = store.tx(tx -> {
            var owner = tx.ownerBinding();
            if (owner == null || !accountId.equals(owner.path("accountId").asText()))
                throw new XDataProviderException(403, "当前账号绑定已变更");
            ObjectNode mat = tx.rewriteMaterial(selectedId, clock.millis());
            if (mat == null) mat = selectedMaterial;
            verifyManualQuoteAuthor(tx, sourceKey, mat, requested);
            if (!tx.retainedMaterialAuthorMatches(selectedId, mat.path("authorId").asText()))
                throw new XDataProviderException(409, "所选原帖与已保存的来源作者不一致");
            XPostLink storedLink = XPostLink.parse(mat.path("sourceUrl").asText(""));
            String quoteTweetId = selectedId.matches("[0-9]{2,20}") ? selectedId : storedLink != null ? storedLink.tweetId() : null;
            if (quoteTweetId == null) {
                throw new IllegalArgumentException("缺少有效原帖推文 ID，无法引用转发");
            }
            if ((requested != null && !quoteTweetId.equals(requested.tweetId()))
                    || (storedLink != null && !quoteTweetId.equals(storedLink.tweetId())))
                throw new XDataProviderException(409, "所选素材与原帖链接的推文 ID 不一致");
            String resolvedMatId = selectedId;
            String topicId = tx.publicationTopic(mat, selectedId);
            String resolvedUrl = new XPostLink(quoteTweetId, mat.path("username").asText("")).url();

            String requestDigest = AssistantWorkflow.digest(object()
                    .put("materialId", resolvedMatId)
                    .put("quoteTweetId", quoteTweetId)
                    .put("commentText", commentText));
            for (ObjectNode pending : pendingManual) {
                if (requestDigest.equals(pending.path("requestDigest").asText()))
                    return new PreparedManualQuote(pending.deepCopy().put("receiptPending", true), quoteTweetId, resolvedUrl);
                if (pubKey.equals(pending.path("id").asText()))
                    throw new XDataProviderException(409, "同一请求标识已用于其他引用内容");
            }
            ObjectNode existing = tx.get("publication", pubKey);
            if (existing != null) {
                if (!tx.publicationOwnerMatches(existing, accountId))
                    throw new XDataProviderException(403, "该回执不属于当前发布账号");
                if (!requestDigest.equals(existing.path("requestDigest").asText(""))) {
                    throw new XDataProviderException(409, "同一请求标识已用于其他引用内容");
                }
                String existingStatus = existing.path("status").asText("");
                if ("PUBLISHED".equals(existingStatus)) {
                    return new PreparedManualQuote(existing, quoteTweetId, resolvedUrl);
                }
                if ("SENDING".equals(existingStatus) || "UNCERTAIN".equals(existingStatus)) {
                    throw new XDataProviderException(409, "该引用发布正在处理或回执待确认，请勿重复提交");
                }
            }

            // Older clients can generate a new key after losing a response.
            ObjectNode prior = tx.protectedPublicationByDigest(requestDigest, accountId);
            if (prior != null) {
                if (!tx.publicationOwnerMatches(prior, accountId))
                    throw new XDataProviderException(403, "该回执不属于当前发布账号");
                if ("PUBLISHED".equals(prior.path("status").asText())) {
                    return new PreparedManualQuote(prior, quoteTweetId, resolvedUrl);
                }
                throw new XDataProviderException(409, "同一条引用发布的回执待确认，请勿重复提交");
            }

            tx.requireNoPendingQuoteOrRewrite(resolvedMatId, quoteTweetId, topicId);
            ScheduledPublicationGuard.requireAvailableCopy(tx, "", quoteTweetId, commentText, List.of(), "", "", 0);
            ObjectNode reservation = tx.put("publication", pubKey, object()
                    .put("requestDigest", requestDigest)
                    .put("sendAttempt", java.util.UUID.randomUUID().toString())
                    .put("requestedText", commentText).put("requestedQuoteTweetId", quoteTweetId)
                    .put("ownerAccountId", accountId)
                    .put("status", "SENDING").put("postRequestDispatched", false)
                    .put("source", "MANUAL_QUOTE_REPOST")
                    .put("topicId", topicId)
                    .put("materialId", resolvedMatId)
                    .put("authorId", mat.path("authorId").asText(""))
                    .put("username", mat.path("username").asText("")).put("authorName", mat.path("authorName").asText(""))
                    .put("sourceUrl", resolvedUrl).put("createdAt", clock.millis())
                    .put("quoteTweetId", quoteTweetId)
                    .put("quotedTweetId", quoteTweetId)
                    .put("text", commentText));
            return new PreparedManualQuote(reservation, quoteTweetId, resolvedUrl);
        });

        if ("PUBLISHED".equals(prepared.row().path("status").asText())) {
            return prepared.row();
        }

        final String accessToken;
        try {
            accessToken = PaidCapabilityGate.withCaller(PaidCapabilityGate.PRIVILEGED_USERNAME, accountId, store.isOwner(accountId),
                    () -> oauthService.getValidWriteAccessToken(accountId, false));
        } catch (RuntimeException ex) {
            store.recordPublicationFailure(pubKey, prepared.row(), safeError(ex), false);
            throw ex;
        }

        PublicationReceipt receipt;
        try {
            receipt = PaidCapabilityGate.withCaller(PaidCapabilityGate.PRIVILEGED_USERNAME, accountId, store.isOwner(accountId),
                    () -> XPublishClient.withDispatchedPayload(commentText, prepared.quoteTweetId(),
                            payload -> AssistantPublishing.markRequestDispatched(store, pubKey, accountId, clock.millis(), payload),
                            () -> XPublishClient.withQuoteTweetUrl(prepared.sourceUrl(),
                                    () -> publishClient.createTweet(accessToken, commentText, List.of(), prepared.quoteTweetId()))));
        } catch (RuntimeException ex) {
            boolean uncertain = !(ex instanceof XPublishException failure) || failure.isDeliveryUncertain();
            store.recordPublicationFailure(pubKey, prepared.row(), safeError(ex), uncertain);
            throw ex;
        }

        ObjectNode accepted = applyReceipt(prepared.row().deepCopy().put("source", quoteSource("MANUAL", receipt.mode()))
                .put("postRequestDispatched", true).put("commentText", commentText),
                receipt, prepared.quoteTweetId(), clock.millis(), username);
        return persistAcceptedReceipt(pubKey, accepted);
    }

    private static boolean boundPublisherMatches(AssistantStore.Tx tx, ObjectNode rec, String expectedOwnerId) {
        ObjectNode owner = tx.ownerBinding();
        if (owner == null) return false;
        String ownerId = owner.path("accountId").asText();
        return (expectedOwnerId == null || expectedOwnerId.equals(ownerId))
                && (rec == null || tx.publicationOwnerMatches(rec, ownerId));
    }

    private boolean repostAuthorized(AssistantStore.Tx tx, ObjectNode rec, String expectedOwnerId) {
        return sourcePermitsPublication(tx, rec, expectedOwnerId, AutoPublishMode.REPOST);
    }

    private boolean sourcePermitsPublication(AssistantStore.Tx tx, ObjectNode rec, String expectedOwnerId, AutoPublishMode mode) {
        if (rec == null || !boundPublisherMatches(tx, rec, expectedOwnerId)
                || !AssistantWorkflow.settings(tx).path("enabled").asBoolean(true)) return false;
        // Old rows may only have the original author's stable ID after the material expires.
        String authorId = rec.path("authorId").asText("");
        if (authorId.isBlank() || !authorId.equals(rec.path("sourceId").asText(authorId))) return false;
        ObjectNode source = tx.get("source", authorId);
        if (source == null || !authorId.equals(source.path("authorId").asText(source.path("id").asText()))
                || !"ACTIVE".equals(source.path("status").asText())
                || !source.path(mode == AutoPublishMode.REWRITE ? "autoRewriteEnabled" : "autoRepostEnabled").asBoolean(false)) return false;
        String materialId = rec.path("materialId").asText(rec.path("id").asText());
        return tx.retainedMaterialAuthorMatches(materialId, authorId);
    }

    private ObjectNode authorizedRepostAtStage(AssistantStore.Tx tx, String materialId, String ownerId, String stage) {
        ObjectNode rec = tx.get("auto_repost", materialId);
        if (rec == null || !stage.equals(rec.path("status").asText())) return null;
        if (repostAuthorized(tx, rec, ownerId)) {
            if (matchesEvaluatedQuote(tx, rec, tx.rewriteMaterial(materialId, clock.millis()))) return rec;
            rec.remove(List.of("automaticRetryRunning", "autoRetryAt", "commentText"));
            tx.put("auto_repost", materialId, rec.put("status", "JUDGE_FAILED").put("quoteSourceNeedsReview", true)
                    .put("error", "评估后原帖已更正，请重新评估附文"));
            return null;
        }
        rec.remove(List.of("automaticRetryRunning", "autoRetryAt"));
        tx.put("auto_repost", materialId, rec.put("status", "FAILED").put("error", "原始素材与当前获准自动引用的来源或作者不一致"));
        return null;
    }

    private static final class RepostDispatchBlocked extends XPublishException {
        RepostDispatchBlocked() { super(409, "自动引用的发送条件已变更", false); }
    }

    private boolean hasDispatchConflict(AssistantStore.Tx tx, String materialId, ObjectNode row) {
        String topic = tx.publicationTopic(row, materialId);
        row.put("topicId", topic);
        ObjectNode conflict = repostConflict(tx, materialId, topic);
        if (conflict == null) return false;
        if ("PUBLISHED".equals(conflict.path("status").asText())) {
            row.remove("autoRetryAt");
            row.put("status", "SKIPPED_DUPLICATE").put("reason", "该素材或选题已有发布回执");
        } else {
            long checkAt = clock.millis() + PaidCapabilityGate.RETRY_INTERVAL_MS;
            if (row.path("automaticRetryRunning").asBoolean()) row.put("autoRetryAt", checkAt);
            else row.put("deferredPendingEval", true).put("deferredUntilMs", checkAt);
            row.put("status", "SKIPPED_COOLDOWN").put("reason", "同一原帖或选题的发布回执待核实，稍后重查");
        }
        row.remove("automaticRetryRunning");
        tx.put("auto_repost", materialId, row);
        return true;
    }

    private boolean maySendRepost(AssistantStore.Tx tx, String materialId, ObjectNode rec, String ownerId,
                                  String ownerUsername, boolean automatic, boolean exemptAuthor, long now) {
        if (automatic && rec.path("candidateWindowMs").asLong() > 0L) {
            ObjectNode material = tx.rewriteMaterial(materialId, now);
            long published = material == null ? rec.path("sourcePublishedAt").asLong() : material.path("publishedAt").asLong();
            if (published <= 0L || published > now || now - published > rec.path("candidateWindowMs").asLong()) {
                rec.remove(List.of("autoRetryAt", "automaticRetryRunning"));
                tx.put("auto_repost", materialId, rec.put("status", "SKIPPED").put("reason", "原帖已超出本轮引用的时间范围"));
                return false;
            }
        }
        RepostCooldown pause = repostCooldown(tx, rec, ownerId, ownerUsername, materialId, exemptAuthor, now);
        if (!recentOwnerTimelineCheck(tx, ownerId, ownerUsername, now))
            pause = new RepostCooldown("SKIPPED_COOLDOWN", now + PaidCapabilityGate.RETRY_INTERVAL_MS, "暂时无法核实发布账号的最新发帖时间，稍后重查");
        long windowCheckAt = clock.millis();
        if (automatic && isInAutoRepostQuietWindow(windowCheckAt)) {
            long end = com.xops.hub.config.XOpsProperties.nextQuietWindowEndEpochMs(windowCheckAt, AssistantRetention.ZONE, quietStartHour(), quietEndHour());
            pause = new RepostCooldown("SKIPPED_QUIET_HOURS", end, quietWindowReason());
        }
        if (pause == null) return true;
        if (rec.path("automaticRetryRunning").asBoolean()) rec.put("autoRetryAt", pause.readyAt);
        rec.remove("automaticRetryRunning");
        tx.put("auto_repost", materialId, rec.put("status", pause.status).put("reason", pause.getMessage()));
        return false;
    }

    private void verifySendingRepost(String materialId, String ownerId, String username, boolean automatic,
                                     boolean exemptAuthor, XPublishClient.PublicationPayload payload) {
        boolean ok = store.tx(tx -> {
            ObjectNode rec = authorizedRepostAtStage(tx, materialId, ownerId, "SENDING");
            if (rec == null || hasDispatchConflict(tx, materialId, rec)
                    || !maySendRepost(tx, materialId, rec, ownerId, username, automatic, exemptAuthor, clock.millis())) return false;
            if (payload != null) {
                rec.put("text", payload.text()).put("publicationMode", payload.mode().name())
                        .put("source", quoteSource("AUTO", payload.mode())).put("dispatchedAt", clock.millis());
                if (payload.quoteTweetId() == null) rec.remove(List.of("quoteTweetId", "quotedTweetId"));
                else rec.put("quoteTweetId", payload.quoteTweetId()).put("quotedTweetId", payload.quoteTweetId());
                tx.put("auto_repost", materialId, rec);
            }
            return true;
        });
        if (!ok) throw new RepostDispatchBlocked();
    }

    private void dispatchCommentAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            String commentText,
            PiAgentViralJudge.ViralJudgment judgment,
            long evaluatedAt
    ) {
        dispatchCommentAndPublish(materialId, ownerId, ownerUsername, topicId, commentText, judgment, evaluatedAt, true);
    }

    private void dispatchCommentAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            String commentText,
            PiAgentViralJudge.ViralJudgment judgment,
            long evaluatedAt,
            boolean enforceQuietWindow
    ) {
        dispatchCommentAndPublish(materialId, ownerId, ownerUsername, topicId, commentText, judgment, evaluatedAt, enforceQuietWindow, false);
    }

    private void dispatchCommentAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            String commentText,
            PiAgentViralJudge.ViralJudgment judgment,
            long evaluatedAt,
            boolean enforceQuietWindow,
            boolean strictSixteenHourAuthorLock
    ) {
        if (!store.tx(tx -> authorizedRepostAtStage(tx, materialId, ownerId, "EVALUATING") != null)) return;
        final String accessToken;
        try {
            accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId, store.isOwner(ownerId),
                    () -> oauthService.getValidWriteAccessToken(ownerId, false));
        } catch (RuntimeException ex) {
            boolean budgetBlocked = UpstreamBudgetGuard.isBudgetReached(ex);
            long retryAt = clock.millis() + PaidCapabilityGate.RETRY_INTERVAL_MS;
            boolean saved = store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec == null || !"EVALUATING".equals(rec.path("status").asText())) return false;
                ObjectNode next = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
                if (budgetBlocked) next.put("autoRetryAt", retryAt).put("budgetDeferred", true);
                else next.remove("autoRetryAt");
                next.remove("automaticRetryRunning");
                tx.put("auto_repost", materialId, next.put("status", budgetBlocked ? "FAILED" : "AUTH_REQUIRED")
                        .put("commentText", commentText).put("error", safeError(ex)));
                return true;
            });
            if (saved && budgetBlocked) scheduleBudgetRetry(materialId, ownerId, retryAt, ex);
            return;
        }

        // Fail-closed checkpoint before POST /2/tweets so uncertain responses are never double-sent.
        if (!store.tx(tx -> recentOwnerTimelineCheck(tx, ownerId, ownerUsername, clock.millis())))
            refreshOwnerTimeline(ownerId, ownerUsername.replaceFirst("^@+", ""));
        record QuoteDispatchTarget(String quoteTweetId, String quoteTweetUrl, boolean exemptAuthor) { }
        QuoteDispatchTarget dispatchTarget = store.tx(tx -> {
            ObjectNode rec = authorizedRepostAtStage(tx, materialId, ownerId, "EVALUATING");
            if (rec == null) return null;
            if (hasDispatchConflict(tx, materialId, rec)) return null;
            if (dailyLimitPreventsSend(tx, rec, "auto_repost", materialId)) return null;
            String resolvedQuoteId = resolveMaterialQuoteTweetId(tx, materialId, rec);
            if (resolvedQuoteId == null) {
                ObjectNode failed = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
                tx.put("auto_repost", materialId, failed
                        .put("status", "FAILED")
                        .put("commentText", commentText)
                        .put("error", "关联原帖缺少有效推文 ID，无法引用转发"));
                return null;
            }
            String resolvedQuoteUrl = rec.path("sourceUrl").asText("").strip();
            if (resolvedQuoteUrl.isBlank()) {
                ObjectNode mat = tx.get("material", materialId);
                if (mat != null) {
                    resolvedQuoteUrl = mat.path("sourceUrl").asText("").strip();
                }
            }
            if (resolvedQuoteUrl.isBlank()) {
                String recUser = rec.path("username").asText("").replaceFirst("^@+", "").strip();
                resolvedQuoteUrl = !recUser.isBlank()
                        ? ("https://x.com/" + recUser + "/status/" + resolvedQuoteId)
                        : ("https://x.com/i/status/" + resolvedQuoteId);
            }
            long dispatchMs = clock.millis();
            boolean exemptAuthorCd = !strictSixteenHourAuthorLock && (judgment != null
                    ? PiAgentViralJudge.isDirectPassCategory(judgment.category())
                    : PiAgentViralJudge.isDirectPassCategory(rec.path("category").asText("")));
            ObjectNode next = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
            next.put("commentText", commentText);
            if (!maySendRepost(tx, materialId, next, ownerId, ownerUsername, enforceQuietWindow, exemptAuthorCd, dispatchMs)) return null;
            next.remove(List.of("publicationMode", "text", "source", "quotedTweetId"));
            tx.put("auto_repost", materialId, next
                    .put("status", "SENDING")
                    .put("requestedQuoteTweetId", resolvedQuoteId).put("quoteTweetId", resolvedQuoteId)
                    .put("dispatchedAt", dispatchMs));
            return new QuoteDispatchTarget(resolvedQuoteId, resolvedQuoteUrl, exemptAuthorCd);
        });
        if (dispatchTarget == null) return;
        String quoteTweetId = dispatchTarget.quoteTweetId();

        PublicationReceipt receipt;
        try {
            receipt = PaidCapabilityGate.withCaller(PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId, store.isOwner(ownerId),
                    () -> XPublishClient.withPublicationPreflight(commentText, quoteTweetId,
                            () -> verifySendingRepost(materialId, ownerId, ownerUsername, enforceQuietWindow, dispatchTarget.exemptAuthor(), null),
                            payload -> verifySendingRepost(materialId, ownerId, ownerUsername, enforceQuietWindow, dispatchTarget.exemptAuthor(), payload),
                            () -> XPublishClient.withQuoteTweetUrl(dispatchTarget.quoteTweetUrl(),
                                    () -> publishClient.createTweet(accessToken, commentText, List.of(), quoteTweetId))));
        } catch (RepostDispatchBlocked blocked) {
            if (enforceQuietWindow && isInAutoRepostQuietWindow(clock.millis())) scheduleQuietWindowEndWakeup(clock.millis());
            return;
        } catch (RuntimeException ex) {
            if (ex instanceof XPublishException blocked && !blocked.isDeliveryUncertain()
                    && UpstreamBudgetGuard.isBudgetReached(blocked.getCause())) {
                long retryAt = clock.millis() + PaidCapabilityGate.RETRY_INTERVAL_MS;
                boolean saved = store.tx(tx -> {
                    var row = tx.get("auto_repost", materialId);
                    if (row == null || !"SENDING".equals(row.path("status").asText())) return false;
                    row.remove("automaticRetryRunning");
                    tx.put("auto_repost", materialId, row.put("status", "FAILED").put("budgetDeferred", true)
                            .put("autoRetryAt", retryAt).put("error", safeError(blocked)));
                    return true;
                });
                if (saved) scheduleBudgetRetry(materialId, ownerId, retryAt, blocked);
                return;
            }
            boolean retryable = markSendFailure("auto_repost", materialId, ex);
            if (retryable && paidGate != null) {
                paidGate.scheduleIdempotentRetry("auto-repost:" + materialId, "x_tweet_create",
                        PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId, safeError(ex),
                        () -> runAutomaticRepostRetry(materialId), true, store.isOwner(ownerId));
            }
            return;
        }
        completeAutoPublish(AutoPublishMode.REPOST, materialId, ownerId, ownerUsername, topicId, receipt, quoteTweetId, commentText);
    }

    private void scheduleBudgetRetry(String materialId, String ownerId, long retryAt, RuntimeException failure) {
        String key = "auto-repost:" + materialId;
        if (paidGate != null && !paidGate.deferRunningRetry(key, retryAt))
            paidGate.scheduleIdempotentRetry(key, "x_tweet_create", PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId,
                    safeError(failure), () -> runAutomaticRepostRetry(materialId), true, store.isOwner(ownerId));
    }

    // Only a definite rejection proves that replaying the remote operation is safe.
    private boolean markSendFailure(String table, String key, RuntimeException ex) {
        boolean rejected = ex instanceof XPublishException publish && !publish.isDeliveryUncertain();
        markSendingRecordFailed(table, key, ex, rejected);
        return rejected;
    }

    private void markSendingRecordFailed(String table, String key, RuntimeException ex, boolean safeToRetry) {
        store.tx(tx -> {
            ObjectNode rec = tx.get(table, key);
            if (rec != null && "SENDING".equals(rec.path("status").asText())) {
                if ("auto_repost".equals(table)) {
                    rec.remove("autoRetryAt");
                    if (safeToRetry && paidGate != null) {
                        boolean automatic = rec.path("automaticRetryRunning").asBoolean();
                        var existing = paidGate.getRetrySnapshot("auto-repost:" + key);
                        if (!automatic && existing != null && existing.state() == PaidCapabilityGate.RetryState.PENDING) {
                            rec.put("autoRetryAttempts", existing.retryCount()).put("autoRetryAt", existing.nextRetryAtEpochMs());
                        } else {
                            scheduleNextAttempt(rec, rec.path("autoRetryAttempts").asInt() + (automatic ? 1 : 0));
                        }
                    }
                    rec.remove("automaticRetryRunning");
                }
                tx.put(table, key, rec.put("status", safeToRetry ? "FAILED" : "UNCERTAIN")
                        .put("error", safeError(ex)));
            }
            return null;
        });
    }

    private void scheduleNextAttempt(ObjectNode row, int attempts) {
        row.put("autoRetryAttempts", attempts);
        if (attempts < PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES) row.put("autoRetryAt", clock.millis() + PaidCapabilityGate.RETRY_INTERVAL_MS);
        else row.remove("autoRetryAt");
    }

    private static ObjectNode applyReceipt(ObjectNode record, PublicationReceipt receipt, String requestedQuoteId,
                                           long doneMs, String username) {
        record.put("status", "PUBLISHED").put("publicationMode", receipt.mode().name())
                .put("text", receipt.text()).put("publishedTweetId", receipt.tweetId())
                .put("publishedTweetUrl", "https://x.com/" + (username == null || username.isBlank() ? "i" : username)
                        + "/status/" + receipt.tweetId())
                .put("publishedAt", doneMs).put("error", "");
        record.remove(List.of("quoteTweetId", "quotedTweetId"));
        if (requestedQuoteId != null) record.put("requestedQuoteTweetId", requestedQuoteId);
        if (receipt.quoteTweetId() != null) {
            record.put("quoteTweetId", receipt.quoteTweetId()).put("quotedTweetId", receipt.quoteTweetId());
        }
        return record;
    }

    private boolean dailyLimitPreventsSend(AssistantStore.Tx tx, ObjectNode rec, String table, String materialId) {
        int limit = Math.max(1, AssistantWorkflow.settings(tx).path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
        if (autoPublicationsToday(tx, clock.millis()) < limit) return false;
        tx.put(table, materialId, rec.put("status", "SKIPPED_LIMIT").put("reason", "已达当日自动发布上限"));
        return true;
    }

    static int autoPublicationsToday(AssistantStore.Tx tx, long now) {
        ObjectNode owner = tx.ownerBinding();
        if (owner == null) return 0;
        String ownerId = owner.path("accountId").asText();
        long start = Instant.ofEpochMilli(now).atZone(AssistantRetention.ZONE).toLocalDate()
                .atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
        Set<String> posts = new java.util.HashSet<>();
        Set<String> receiptedMaterials = new java.util.HashSet<>();
        for (ObjectNode receipt : tx.automaticReceiptsSince(start)) {
            if (!tx.publicationOwnerMatches(receipt, ownerId)) continue;
            String source = receipt.path("source").asText("");
            String id = receipt.path("id").asText();
            boolean rewrite = source.startsWith("AUTO_REWRITE") || id.startsWith("auto-rewrite-");
            String prefix = rewrite ? "auto-rewrite-" : "auto-repost-";
            String material = receipt.path("materialId").asText(id.startsWith(prefix) ? id.substring(prefix.length()) : id);
            String key = prefix + material;
            receiptedMaterials.add(key);
            posts.add(receipt.path("publishedTweetId").asText(key));
        }
        for (String table : List.of("auto_repost", "auto_rewrite")) {
            for (ObjectNode row : tx.all(table)) {
                if (row.path("manual").asBoolean() || !Set.of("PUBLISHED","SENDING","UNCERTAIN").contains(row.path("status").asText())) continue;
                if (!tx.publicationOwnerMatches(row, ownerId)) continue;
                long at = row.path("publishedAt").asLong(row.path("dispatchedAt").asLong(0));
                if (at < start) continue;
                String key = ("auto_rewrite".equals(table) ? "auto-rewrite-" : "auto-repost-") + row.path("materialId").asText(row.path("id").asText());
                if (!receiptedMaterials.contains(key) || row.hasNonNull("publishedTweetId")) {
                    posts.add(row.path("publishedTweetId").asText(key));
                }
            }
        }
        return posts.size();
    }

    static String quoteSource(String prefix, XPublishClient.PublicationMode mode) {
        return prefix + switch (mode) {
            case QUOTE -> "_QUOTE_REPOST";
            case QUOTE_LINK -> "_LINK_REPOST";
            case STANDALONE_FALLBACK -> "_STANDALONE_FALLBACK";
            case ORIGINAL -> "_ORIGINAL_POST";
        };
    }

    private synchronized ObjectNode persistAcceptedReceipt(String publicationKey, ObjectNode accepted) {
        accepted.put("id", publicationKey);
        String key = accepted.path("publishedTweetId").asText();
        pendingAcceptedReceipts.put(key, accepted.deepCopy());
        try {
            store.savePendingReceipt("auto-and-manual", key, accepted);
        } catch (RuntimeException failure) {
            log.warn("Could not journal accepted publication {}", key, failure);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try { return commitAcceptedReceipt(accepted); }
            catch (RuntimeException failure) {
                accepted.put("receiptRecovered", true);
                log.warn("Could not record accepted publication {}", key, failure);
            }
        }
        return accepted.deepCopy().put("receiptPending", true);
    }

    synchronized void reconcileAcceptedReceipts() {
        store.reconcilePublicationFailures();
        for (ObjectNode receipt : store.pendingReceipts("auto-and-manual"))
            pendingAcceptedReceipts.putIfAbsent(receipt.path("publishedTweetId").asText(), receipt);
        for (ObjectNode receipt : List.copyOf(pendingAcceptedReceipts.values())) {
            try { commitAcceptedReceipt(receipt.deepCopy().put("receiptRecovered", true)); }
            catch (RuntimeException failure) { log.warn("Could not reconcile accepted publication {}", receipt.path("publishedTweetId"), failure); }
        }
    }

    private synchronized List<ObjectNode> pendingManualReceipts(String ownerId) {
        return pendingAcceptedReceipts.values().stream().filter(r -> ownerId.equals(r.path("ownerAccountId").asText())
                && r.path("source").asText().startsWith("MANUAL_")).toList();
    }

    synchronized void requireReceiptReady(String publicationKey) {
        reconcileAcceptedReceipts();
        if (pendingAcceptedReceipts.values().stream().anyMatch(r -> publicationKey.equals(r.path("id").asText())))
            throw new XDataProviderException(409, "已收到发布成功回执，正在补写本地记录，请刷新");
    }

    private ObjectNode commitAcceptedReceipt(ObjectNode receipt) {
        String tweetId = receipt.path("publishedTweetId").asText();
        ObjectNode saved = store.tx(tx -> {
            String ownerId = receipt.path("ownerAccountId").asText();
            String materialId = receipt.path("materialId").asText();
            String source = receipt.path("source").asText();
            boolean manual = source.startsWith("MANUAL_");
            String table = "AUTO_REWRITE_POST".equals(source) ? "auto_rewrite" : "auto_repost";
            ObjectNode projection = tx.get(table, materialId);
            String key = receipt.path("id").asText();
            ObjectNode earlier = tx.get("publication", key);
            if (earlier != null && (!receiptOwnerMatches(tx, earlier, ownerId)
                    || ("PUBLISHED".equals(earlier.path("status").asText()) && !tweetId.equals(earlier.path("publishedTweetId").asText())))) {
                key += "-" + tweetId;
                earlier = tx.get("publication", key);
            }
            ObjectNode pub = receipt.deepCopy();
            if (projection != null && receiptOwnerMatches(tx, projection, ownerId) && !manual) {
                pub.put("authorId", projection.path("authorId").asText("")).put("username", projection.path("username").asText(""));
            }
            pub.put("topicId", tx.publicationTopic(pub, materialId));
            if (earlier != null && tweetId.equals(earlier.path("publishedTweetId").asText())
                    && "PUBLISHED".equals(earlier.path("status").asText())) pub = earlier;
            else {
                if (earlier != null && earlier.has("dispatchedAt")) pub.set("dispatchedAt", earlier.get("dispatchedAt"));
                pub = tx.put("publication", key, pub);
            }
            if (projection != null && (!receiptOwnerMatches(tx, projection, ownerId)
                    || ("PUBLISHED".equals(projection.path("status").asText())
                        && (tweetId.equals(projection.path("publishedTweetId").asText()) || (manual && !projection.path("manual").asBoolean())
                            || projection.path("publishedAt").asLong() > receipt.path("publishedAt").asLong())))) return pub;
            ObjectNode row = projection == null ? object() : projection;
            row.remove(List.of("autoRetryAt", "autoRetryAttempts", "automaticRetryRunning", "quoteTweetId", "quotedTweetId"));
            for (String field : List.of("ownerAccountId", "materialId", "topicId", "authorId", "username", "authorName", "sourceUrl",
                    "status", "publicationMode", "text", "publishedTweetId", "publishedTweetUrl", "publishedAt", "error",
                    "requestedQuoteTweetId", "quoteTweetId", "quotedTweetId")) {
                if (pub.has(field)) row.set(field, pub.get(field));
            }
            if (manual) row.put("manual", true).put("commentText", receipt.path("commentText").asText()).put("quoteVoiceVersion", 7);
            tx.put(table, materialId, row);
            return pub;
        });
        store.removePendingReceipt("auto-and-manual", tweetId);
        pendingAcceptedReceipts.remove(tweetId);
        clearPreviewCache();
        return saved;
    }

    private static boolean receiptOwnerMatches(AssistantStore.Tx tx, ObjectNode row, String ownerId) {
        return !row.path("ownerReviewRequired").asBoolean() && (ownerId.equals(row.path("ownerAccountId").asText())
                || (row.path("ownerAccountId").asText("").isBlank() && tx.publicationOwnerMatches(row, ownerId)));
    }

    private void completeAutoPublish(AutoPublishMode mode, String materialId, String ownerId, String username, String topicId,
                                     PublicationReceipt receipt, String requestedQuoteId, String requestedText) {
        boolean rewrite = mode == AutoPublishMode.REWRITE;
        String prefix = rewrite ? "auto-rewrite" : "auto-repost";
        long doneMs = clock.millis();
        ObjectNode pub = object().put("source", rewrite ? "AUTO_REWRITE_POST" : quoteSource("AUTO", receipt.mode()))
                .put("ownerAccountId", ownerId).put("topicId", topicId).put("materialId", materialId).put("requestedText", requestedText);
        persistAcceptedReceipt(prefix + "-" + materialId, applyReceipt(pub, receipt, requestedQuoteId, doneMs, username));
        if (paidGate != null) paidGate.markSucceeded(prefix + ":" + materialId);
        try {
            recordOwnerPublishAndScheduleNextSlot(ownerId, username, doneMs);
        } catch (RuntimeException schedulingFailure) {
            log.warn("Could not schedule the next slot after tweet {}", receipt.tweetId(), schedulingFailure);
        }
    }

    private static String resolveAuthorBio(ObjectNode source, List<ObjectNode> materials) {
        if (materials != null) {
            for (ObjectNode m : materials) {
                if (m != null) {
                    String b = m.path("authorBio").asText("").strip();
                    if (!b.isBlank()) return b;
                }
            }
        }
        return source == null ? "" : source.path("bio").asText("").strip();
    }

    private static String buildCommentText(boolean useTemplate, ObjectNode source, PiAgentViralJudge.ViralJudgment judgment) {
        return buildCommentText(useTemplate, source, null, judgment);
    }

    private static String buildCommentText(boolean useTemplate, ObjectNode source, ObjectNode material, PiAgentViralJudge.ViralJudgment judgment) {
        if (useTemplate) {
            String tpl = source.path("autoRepostTemplate").asText("").strip();
            if (tpl.isBlank()) return "";
            String authorHandle = "@" + source.path("username").asText("");
            String rendered = tpl
                    .replace("{summary}", judgment.summary())
                    .replace("{author}", authorHandle)
                    .strip();
            return PiAgentViralJudge.truncateCodePoints(rendered, 240);
        }
        String aiText = judgment.commentText();
        if (aiText == null || aiText.isBlank()) {
            return "";
        }
        boolean briefFlash = material != null && OwnerQuoteStyleDistiller.isBriefFlashPost(
                material.path("text").asText(""),
                material.path("quotedText").asText(""),
                material.path("articleText").asText(""));
        return OwnerQuoteStyleDistiller.enforceAdaptiveQuoteFormat(aiText, briefFlash, 280);
    }

    private static ObjectNode baseRecord(ObjectNode source, ObjectNode material, String topicId, long now, String ownerId) {
        return object().put("ownerAccountId", ownerId).put("sourceId", source.path("authorId").asText(source.path("id").asText("")))
                .put("previewMaterialFingerprint", previewMaterialFingerprint(material))
                .put("materialId", material.path("id").asText(""))
                .put("topicId", topicId)
                .put("authorId", material.path("authorId").asText(""))
                .put("username", material.path("username").asText(""))
                .put("authorName", material.path("authorName").asText(source.path("displayName").asText("")))
                .put("authorAvatarUrl", material.path("authorAvatarUrl").asText(source.path("avatarUrl").asText("")))
                .put("sourceUrl", material.path("sourceUrl").asText(""))
                .put("sourcePublishedAt", material.path("publishedAt").asLong(0))
                .put("createdAt", now);
    }

    private static ObjectNode applyJudgment(ObjectNode rec, PiAgentViralJudge.ViralJudgment j, long evaluatedAt) {
        ObjectNode next = rec.put("viralScore", j.viralScore())
                .put("factScore", j.factScore())
                .put("trendScore", j.trendScore())
                .put("attentionScore", j.attentionScore())
                .put("accountFit", j.accountFit())
                .put("fitScore", j.fitScore())
                .put("eventMagnitude", j.eventMagnitude())
                .put("publicResonance", j.publicResonance())
                .put("credibility", j.credibility())
                .put("category", j.category())
                .put("summary", j.summary())
                .put("reason", j.reason())
                .put("quoteVoiceVersion", 7)
                .put("evaluatedAt", evaluatedAt);
        if (j.commentText() != null && !j.commentText().isBlank() && rec.path("commentText").asText("").isBlank()) {
            next.put("commentText", j.commentText());
        }
        return next;
    }

    private static Long optionalLong(ObjectNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).asLong() : null;
    }

    private static String safeError(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank()) m = ex.getClass().getSimpleName();
        return m.length() > 260 ? m.substring(0, 260) : m;
    }

    // ==================== High-Traffic Pure-Text Wash-Rewrite Engine ====================

    static String extractPureRewriteSourceText(ObjectNode m) {
        if (m == null) return "";
        if (!"POST".equalsIgnoreCase(m.path("kind").asText("POST"))) return "";
        if (m.path("selfThreadReply").asBoolean(false) || m.path("archiveOnly").asBoolean(false)) return "";
        if (m.path("hasMedia").asBoolean(false) || !m.path("media").isEmpty()) return "";
        if (hasRewriteQuote(m)) return "";
        if (hasRewriteArticle(m) || hasOtherRewriteSourceUrl(m) || !m.path("links").isEmpty()) return "";
        String textStatus = m.path("textStatus").asText("");
        if (Set.of("MEDIA_ONLY", "MEDIA_URL_ONLY", "NO_ANALYZABLE_TEXT", "QUOTE_TEXT_MISSING", "QUOTE_SOURCE_MISSING").contains(textStatus)) {
            return "";
        }
        String raw = m.path("text").asText("").strip();
        if (raw.isBlank()) return "";
        if (EMBEDDED_URL_PATTERN.matcher(raw).find()) return "";
        if (raw.startsWith("@")) return "";
        if (THREAD_OR_VISUAL_DEPENDENCY_PATTERN.matcher(raw).find()) return "";
        int cp = raw.codePointCount(0, raw.length());
        if (cp < 35 || cp > 4_000) return "";
        return raw;
    }

    static boolean isPureTextRewriteCandidate(ObjectNode m) {
        if (m == null) return false;
        String id = m.path("id").asText("").strip();
        if (!id.matches("[0-9]+")) return false;
        return !extractPureRewriteSourceText(m).isBlank();
    }

    private static boolean hasRewriteArticle(ObjectNode material) {
        return !material.path("articleUrl").asText("").isBlank() || !material.path("articleId").asText("").isBlank()
                || !material.path("articleText").asText("").isBlank();
    }

    private static boolean hasRewriteQuote(ObjectNode material) {
        return !material.path("quotedId").asText("").isBlank() || !material.path("quotedText").asText("").isBlank()
                || !material.path("quotedAuthorId").asText("").isBlank() || !material.path("quotedUsername").asText("").isBlank();
    }

    private static boolean hasOtherRewriteSourceUrl(ObjectNode material) {
        String sourceUrl = material.path("sourceUrl").asText("").strip();
        if (sourceUrl.isEmpty()) return false;
        XPostLink link = XPostLink.parsePermalink(sourceUrl);
        return link == null || !link.tweetId().equals(material.path("id").asText(""));
    }

    static String rewriteIneligibleReason(ObjectNode m) {
        if (m == null) return "素材为空";
        if (m.path("hasMedia").asBoolean(false) || !m.path("media").isEmpty()) return "含图片或视频媒体";
        String kind = m.path("kind").asText("POST");
        if (!"POST".equalsIgnoreCase(kind) || hasRewriteQuote(m)) {
            return "含引用或非原创主帖";
        }
        String textStatus = m.path("textStatus").asText("");
        if (Set.of("MEDIA_ONLY", "MEDIA_URL_ONLY").contains(textStatus)) return "仅媒体无纯文本";
        if (hasRewriteArticle(m) || hasOtherRewriteSourceUrl(m) || !m.path("links").isEmpty()) return "含外部链接或文章";
        String raw = m.path("text").asText("").strip();
        if (EMBEDDED_URL_PATTERN.matcher(raw).find()) return "正文含链接";
        if (THREAD_OR_VISUAL_DEPENDENCY_PATTERN.matcher(raw).find()) return "依赖看图或串帖上下文";
        return "纯文本过短或结构不足以独立成篇";
    }

    static Double rewriteExposureRate(ObjectNode material, ObjectNode source) {
        if (!material.path("views").isIntegralNumber() || material.path("views").asLong() < 0) return null;
        long followers = material.path("authorFollowersCount").asLong();
        if (followers <= 0 && source != null) followers = source.path("followersCount").asLong();
        return followers > 0 ? (double) material.path("views").asLong() / followers : null;
    }

    static double computeRewriteRankScore(ObjectNode m, ObjectNode source, long medViews, long medEng) {
        long eng = computeWeightedEngagement(m);
        long views = Math.max(0L, m.path("views").asLong(0L));
        double ratioBoost = medEng > 0L ? Math.min(6.0, (double) eng / medEng) : 0.0;
        double viewBoost = medViews > 0L ? Math.min(6.0, (double) views / medViews) : 0.0;
        Double exposure = rewriteExposureRate(m, source);
        return Math.log1p(eng) * 10.0 + Math.log1p(views) * 3.5 + (ratioBoost + viewBoost) * 6.0
                + (exposure == null ? 0.0 : Math.log1p(exposure) * 24.0);
    }

    private static <T> Comparator<T> rewritePriority(ToDoubleFunction<T> score, Function<T, ObjectNode> material) {
        return Comparator.comparingDouble(score).reversed()
                .thenComparing(Comparator.comparingLong((T entry) -> material.apply(entry).path("publishedAt").asLong()).reversed())
                .thenComparing(entry -> material.apply(entry).path("id").asText());
    }

    static Comparator<ObjectNode> rewritePriority(ObjectNode source, long medViews, long medEng) {
        return rewritePriority(m -> computeRewriteRankScore(m, source, medViews, medEng), Function.identity());
    }

    static long computeWeightedEngagement(ObjectNode m) {
        if (m == null) return 0L;
        long views = Math.max(0L, m.path("views").asLong(0L));
        long likes = Math.max(0L, m.path("likes").asLong(0L));
        long reposts = Math.max(0L, m.path("reposts").asLong(0L));
        long replies = Math.max(0L, m.path("replies").asLong(0L));
        long bookmarks = Math.max(0L, m.path("bookmarks").asLong(0L));
        return likes + reposts * 3L + replies * 2L + bookmarks * 2L + (views / 100L);
    }

    static boolean isHighTrafficForAuthor(
            ObjectNode m,
            long medianViews,
            long medianLikes,
            long medianEngagement,
            int baselineSampleCount
    ) {
        long views = Math.max(0L, m.path("views").asLong(0L));
        long likes = Math.max(0L, m.path("likes").asLong(0L));
        long reposts = Math.max(0L, m.path("reposts").asLong(0L));
        long engagement = computeWeightedEngagement(m);
        boolean meetsAbsoluteFloor = views >= 800L || likes >= 12L || engagement >= 24L;
        if (!meetsAbsoluteFloor) return false;
        if (views >= 8_000L || likes >= 80L || reposts >= 12L) {
            return true;
        }
        if (baselineSampleCount >= 3 && (medianViews > 0L || medianLikes > 0L || medianEngagement > 0L)) {
            boolean beatsViews = medianViews > 0L && views >= Math.round(medianViews * 1.10);
            boolean beatsLikes = medianLikes > 0L && likes >= Math.round(medianLikes * 1.10);
            boolean beatsEng = medianEngagement > 0L && engagement >= Math.round(medianEngagement * 1.15);
            return beatsViews || beatsLikes || beatsEng;
        }
        return true;
    }

    private record RankedRewriteCandidate(
            ObjectNode source,
            String materialId,
            String topicId,
            ObjectNode material,
            String pureText,
            double rankScore,
            long medianViews,
            long medianLikes
    ) { }

    private static final Comparator<RankedRewriteCandidate> REWRITE_CANDIDATE_ORDER =
            rewritePriority(RankedRewriteCandidate::rankScore, RankedRewriteCandidate::material);

    private static boolean canStartPatrolRewrite(AssistantStore.Tx tx, ObjectNode existing, ObjectNode material, String ownerId) {
        if (existing == null) return true;
        if (!boundPublisherMatches(tx, existing, ownerId)) return false;
        String status = existing.path("status").asText("");
        return "SKIPPED_COOLDOWN".equals(status) || "SKIPPED_QUIET_HOURS".equals(status)
                || "SKIPPED_LIMIT".equals(status)
                || ("SKIPPED".equals(status) && !previewMatchesMaterial(existing, material));
    }

    private ObjectNode authorizedRewriteAtStage(AssistantStore.Tx tx, String materialId, String ownerId, String stage) {
        ObjectNode record = tx.get("auto_rewrite", materialId);
        if (record == null || !stage.equals(record.path("status").asText())) return null;
        if (sourcePermitsPublication(tx, record, ownerId, AutoPublishMode.REWRITE)) return record;
        String status = boundPublisherMatches(tx, record, ownerId) ? "FAILED" : "OWNER_REVIEW_REQUIRED";
        tx.put("auto_rewrite", materialId, record.put("status", status).put("error", "自动洗稿的来源、助手开关或作者授权已变更"));
        return null;
    }

    private static boolean hasConflictingRewritePublication(AssistantStore.Tx tx, String materialId, String topicId) {
        return hasConflictingRewritePublication(tx, materialId, topicId, false);
    }

    private static boolean hasConflictingRewritePublication(AssistantStore.Tx tx, String materialId, String topicId, boolean sending) {
        var rewrite = tx.get("auto_rewrite", materialId);
        String body = rewrite == null ? "" : rewrite.path("rewrittenText").asText("");
        return (!body.isBlank() && tx.conflictingDelivery("", "", body, List.of(), "auto_rewrite", materialId, 0) != null)
                || tx.matchingQuoteOrRewrite("auto_rewrite", materialId, materialId, topicId, true, sending ? materialId : "") != null
                || hasProtectedPublication(tx, "auto_repost", materialId, topicId)
                || hasProtectedPublication(tx, "publication", materialId, topicId);
    }

    private ObjectNode tryPatrolRewriteSources(
            List<ObjectNode> orderedRewriteSources,
            String ownerId,
            String ownerUsername,
            long latestOwnerAt,
            int jitterMinutes
    ) {
        var published = processRewriteSources(orderedRewriteSources, ownerId, ownerUsername, null);
        if (published == null) return null;
        return object()
                .put("status", recordedDispatchStatus("auto_rewrite", published.materialId()))
                .put("mode", "REWRITE")
                .put("materialId", published.materialId())
                .put("sourceUsername", published.source().path("username").asText(""))
                .put("latestOwnerTweetAt", latestOwnerAt)
                .put("jitterMinutes", jitterMinutes);
    }

    String processAuthorRewriteOnce(ObjectNode sourceSnapshot, String ownerId, String ownerUsername) {
        return processAuthorRewriteOnce(sourceSnapshot, ownerId, ownerUsername, null);
    }

    private String processAuthorRewriteOnce(ObjectNode sourceSnapshot, String ownerId, String ownerUsername,
                                            String retryMaterialId) {
        if (sourceSnapshot == null) return "";
        var published = processRewriteSources(List.of(sourceSnapshot), ownerId, ownerUsername, retryMaterialId);
        return published == null ? "" : published.materialId();
    }

    private RankedRewriteCandidate processRewriteSources(List<ObjectNode> sourceSnapshots, String ownerId,
                                                         String ownerUsername, String retryMaterialId) {
        if (sourceSnapshots == null || sourceSnapshots.isEmpty()) return null;
        boolean automatic = retryMaterialId == null;
        if (automatic) {
            for (ObjectNode source : sourceSnapshots) {
                long checkedAt = clock.millis();
                if (isInAutoRepostQuietWindow(checkedAt)) {
                    scheduleQuietWindowEndWakeup(checkedAt);
                    return null;
                }
                if (sources != null) {
                    if (!store.tx(tx -> boundPublisherMatches(tx, null, ownerId))) return null;
                    sources.syncSourceMaterialsForRewrite(source, 0L);
                }
            }
        }

        record RewriteBatchContext(
                List<RankedRewriteCandidate> topCandidates,
                String styleProfile,
                String preferencesText,
                String postPolishPrompt,
                String generationProvider,
                String generationModel
        ) { }

        RewriteBatchContext ctx = store.tx(tx -> {
            long now = clock.millis();
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (!settings.path("enabled").asBoolean(true) || !boundPublisherMatches(tx, null, ownerId)) return null;

            long latestOwner = resolveLatestOwnerTweetEpochMs(tx, ownerId, ownerUsername, "", now);
            long requiredGap = resolveEffectiveOwnerGapMs(settings, latestOwner);
            if (requiredGap > 0L && latestOwner > 0L && now - latestOwner >= 0L && now - latestOwner < requiredGap) {
                return null;
            }

            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            int publishedToday = autoPublicationsToday(tx, now);
            Set<String> usedTopics = new java.util.HashSet<>();
            Set<String> usedMaterials = new java.util.HashSet<>();
            for (ObjectNode rec : tx.all("auto_repost")) {
                String st = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(st)) {
                    usedMaterials.add(rec.path("materialId").asText(rec.path("id").asText("")));
                    String tid = tx.publicationTopic(rec, rec.path("materialId").asText(rec.path("id").asText()));
                    if (!tid.isBlank()) usedTopics.add(tid);
                }
            }
            Map<String, ObjectNode> rewriteRecords = new java.util.HashMap<>();
            for (ObjectNode rec : tx.all("auto_rewrite")) {
                String st = rec.path("status").asText("");
                String mid = rec.path("materialId").asText(rec.path("id").asText(""));
                rewriteRecords.put(mid, rec);
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(st)) {
                    usedMaterials.add(mid);
                    String tid = tx.publicationTopic(rec, mid);
                    if (!tid.isBlank()) usedTopics.add(tid);
                }
            }
            if (publishedToday >= dailyLimit) return null;

            List<RankedRewriteCandidate> ranked = new ArrayList<>();
            for (ObjectNode snapshot : sourceSnapshots) {
                String sourceId = snapshot.path("authorId").asText(snapshot.path("id").asText("")).strip();
                ObjectNode liveSource = tx.get("source", sourceId);
                if (liveSource == null || !"ACTIVE".equals(liveSource.path("status").asText())
                        || !liveSource.path("autoRewriteEnabled").asBoolean()) continue;
                List<ObjectNode> authorMats = tx.rewriteWindow(sourceId, liveSource.path("username").asText(), now - REWRITE_MAX_AGE_MS, now);
                RewriteTraffic traffic = RewriteTraffic.of(authorMats);
                List<RankedRewriteCandidate> authorCandidates = new ArrayList<>();
                for (ObjectNode m : authorMats) {
                    if (!withinRewriteWindow(m, now)) continue;
                    String mid = m.path("id").asText("").strip();
                    if (!automatic && !mid.equals(retryMaterialId)) continue;
                    if (mid.isBlank() || usedMaterials.contains(mid)) continue;
                    if (automatic && !canStartPatrolRewrite(tx, rewriteRecords.get(mid), m, ownerId)) continue;
                    if (!isPureTextRewriteCandidate(m) || !traffic.qualifies(m)) continue;
                    String tid = AssistantTopics.topicOf(tx, mid);
                    if (tid.isBlank()) tid = m.path("rewriteTopicId").asText("");
                    if (!tid.isBlank() && usedTopics.contains(tid)) continue;
                    if (hasProtectedPublication(tx, "publication", mid, tid)) continue;
                    double score = computeRewriteRankScore(m, liveSource, traffic.views(), traffic.engagement());
                    authorCandidates.add(new RankedRewriteCandidate(liveSource, mid, tid, m, extractPureRewriteSourceText(m),
                            score, traffic.views(), traffic.likes()));
                }
                authorCandidates.sort(REWRITE_CANDIDATE_ORDER);
                ranked.addAll(authorCandidates.stream().limit(3).toList());
            }
            if (ranked.isEmpty()) return null;
            ranked.sort(REWRITE_CANDIDATE_ORDER);

            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            String polishPrompt = AssistantWorkflow.isPostPolishEnabled(settings)
                    ? AssistantWorkflow.resolvePostPolishPrompt(settings)
                    : "";
            var repostMod = settings.path("moduleConfigs").path("repost");
            String prov = repostMod.path("provider").asText("").strip();
            String model = repostMod.path("model").asText("").strip();
            return new RewriteBatchContext(
                    ranked,
                    composeOwnerQuoteVoiceProfile(tx),
                    prefs.path("text").asText(""),
                    polishPrompt,
                    prov,
                    model
            );
        });

        if (ctx == null || ctx.topCandidates().isEmpty()) return null;

        for (RankedRewriteCandidate cand : ctx.topCandidates()) {
            long claimAt = clock.millis();
            if (automatic && isInAutoRepostQuietWindow(claimAt)) {
                scheduleQuietWindowEndWakeup(claimAt);
                return null;
            }
            ObjectNode claimed = store.tx(tx -> {
                long checkedAt = clock.millis();
                ObjectNode current = tx.rewriteMaterial(cand.materialId(), checkedAt);
                if (!withinRewriteWindow(cand.material(), checkedAt) || !canReassessRewrite(tx, current, checkedAt)
                        || !rewriteSourceFingerprint(cand.material()).equals(rewriteSourceFingerprint(current))) return null;
                ObjectNode existing = tx.get("auto_rewrite", cand.materialId());
                if (!automatic) {
                    ObjectNode retry = authorizedRewriteAtStage(tx, cand.materialId(), ownerId, "EVALUATING");
                    if (retry == null) return null;
                    if (!retry.has("reassessedFinalVersion")) retry.remove(List.of("rewrittenText", "commentText"));
                    retry.remove("rewriteSourceNeedsReview");
                    return tx.put("auto_rewrite", cand.materialId(), retry.put("previewMaterialFingerprint", previewMaterialFingerprint(cand.material()))
                            .put("rewriteSourceFingerprint", rewriteSourceFingerprint(cand.material())));
                }
                if (!canStartPatrolRewrite(tx, existing, cand.material(), ownerId)
                        || hasConflictingRewritePublication(tx, cand.materialId(), cand.topicId())) return null;
                if (existing != null && !existing.path("rewrittenText").asText().isBlank()) {
                    if (existing.path("rewriteSourceNeedsReview").asBoolean() || !matchesFinalRewriteSource(tx, existing, cand.material())) {
                        tx.put("auto_rewrite", cand.materialId(), existing.put("rewriteSourceNeedsReview", true)
                                .put("status", "FAILED").put("error", "原素材已变更，请重新评估定稿"));
                        return null;
                    }
                    return tx.put("auto_rewrite", cand.materialId(), existing.put("ownerAccountId", ownerId).put("status", "EVALUATING")
                            .put("topicId", cand.topicId()).put("rewriteSourceFingerprint", rewriteSourceFingerprint(cand.material())));
                }
                ObjectNode claim = baseRecord(cand.source(), cand.material(), cand.topicId(), claimAt, ownerId);
                if (!sourcePermitsPublication(tx, claim, ownerId, AutoPublishMode.REWRITE)) return null;
                return tx.put("auto_rewrite", cand.materialId(), claim.put("autoKind", "REWRITE").put("mode", "REWRITE").put("status", "EVALUATING")
                        .put("rewriteSourceFingerprint", rewriteSourceFingerprint(cand.material())));
            });
            if (claimed == null) continue;
            if (automatic && !claimed.path("rewrittenText").asText().isBlank()) {
                if (dispatchRewriteAndPublish(cand.materialId(), ownerId, ownerUsername, cand.topicId(), claimed.path("rewrittenText").asText(), null, claimAt, automatic)) return cand;
                continue;
            }

            String authorUname = cand.material().path("username").asText(cand.source().path("username").asText(""));
            String authorBio = resolveAuthorBio(cand.source(), List.of(cand.material()));
            List<String> stances = store.tx(tx -> {
                var relevant = OwnerQuoteStyleDistiller.findRelevantOwnerStances(tx, authorUname, authorBio, cand.pureText(), "", 4);
                ObjectNode row = authorizedRewriteAtStage(tx, cand.materialId(), ownerId, "EVALUATING");
                return row != null && rewriteSourceStillValid(tx, row, cand.materialId(), false, automatic) ? relevant : null;
            });
            if (stances == null) continue;

            final PiAgentViralJudge.RewriteJudgment rwJudgment;
            try {
                rwJudgment = judge.evaluateAndWriteRewrite(new PiAgentViralJudge.RewriteInput(
                        authorUname,
                        cand.material().path("authorName").asText(cand.source().path("displayName").asText("")),
                        authorBio,
                        cand.pureText(),
                        optionalLong(cand.material(), "views"),
                        optionalLong(cand.material(), "likes"),
                        optionalLong(cand.material(), "reposts"),
                        optionalLong(cand.material(), "replies"),
                        cand.medianViews(),
                        cand.medianLikes(),
                        ctx.styleProfile(),
                        ctx.preferencesText(),
                        stances,
                        ctx.postPolishPrompt(),
                        ctx.generationProvider(),
                        ctx.generationModel()
                ));
            } catch (RuntimeException ex) {
                long failAt = clock.millis();
                store.tx(tx -> {
                    ObjectNode rec = authorizedRewriteAtStage(tx, cand.materialId(), ownerId, "EVALUATING");
                    if (rec != null && rewriteSourceStillValid(tx, rec, cand.materialId(), false, automatic)) {
                        rec.remove(List.of("rewrittenText", "commentText", "reassessedFinalVersion"));
                        tx.put("auto_rewrite", cand.materialId(), rec.deepCopy()
                                .put("status", "JUDGE_FAILED")
                                .put("evaluatedAt", failAt)
                                .put("error", safeError(ex)));
                    }
                    return null;
                });
                continue;
            }

            long evalAt = clock.millis();
            if (!rwJudgment.suitable() || rwJudgment.rewrittenText().isBlank()) {
                store.tx(tx -> {
                    ObjectNode rec = authorizedRewriteAtStage(tx, cand.materialId(), ownerId, "EVALUATING");
                    if (rec != null && rewriteSourceStillValid(tx, rec, cand.materialId(), false, automatic)) {
                        rec.remove(List.of("rewrittenText", "commentText", "reassessedFinalVersion"));
                        tx.put("auto_rewrite", cand.materialId(), rec.deepCopy()
                                .put("status", "SKIPPED")
                                .put("suitabilityScore", rwJudgment.suitabilityScore())
                                .put("hookPattern", rwJudgment.hookPattern())
                                .put("summary", rwJudgment.coreInsight())
                                .put("reason", rwJudgment.reason())
                                .put("evaluatedAt", evalAt));
                    }
                    return null;
                });
                continue;
            }

            boolean published = dispatchRewriteAndPublish(
                    cand.materialId(),
                    ownerId,
                    ownerUsername,
                    cand.topicId(),
                    rwJudgment.rewrittenText(),
                    rwJudgment,
                    evalAt,
                    automatic
            );
            if (published) {
                return cand;
            }
        }
        return null;
    }

    private ObjectNode retryRewrite(String materialId, boolean reassess) {
        record RewriteRetryPlan(
                ObjectNode updated,
                boolean reuseText,
                String existingText,
                String ownerId,
                String ownerUsername,
                String topicId,
                ObjectNode source
        ) { }

        RewriteRetryPlan plan = store.tx(tx -> {
            ObjectNode existing = tx.get("auto_rewrite", materialId);
            if (existing == null) throw new XDataProviderException(404, "洗稿记录不存在");
            String status = existing.path("status").asText("");
            if (!rewriteRetryable(status)) {
                throw new XDataProviderException(409, "当前洗稿状态不允许重发，请先核实线上回执");
            }
            String topicId = tx.publicationTopic(existing, materialId);
            if (hasConflictingRewritePublication(tx, materialId, topicId)) {
                throw new XDataProviderException(409, "该素材或选题已有发布或待核实记录");
            }
            ObjectNode owner = tx.ownerBinding();
            if (owner == null || owner.path("accountId").asText("").isBlank()) {
                throw new XDataProviderException(400, "缺少内容助手所有者信息");
            }
            String ownerAccountId = owner.path("accountId").asText("");
            if (!sourcePermitsPublication(tx, existing, ownerAccountId, AutoPublishMode.REWRITE))
                throw new XDataProviderException(409, "自动洗稿未获当前作者授权，或助手、来源、洗稿开关已停用");
            String ownerUsername = owner.path("username").asText("i");
            long nowMs = clock.millis();
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (autoPublicationsToday(tx, nowMs) >= Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT)))
                throw new XDataProviderException(409, "已达当日自动发布上限，可在次日重试");
            long latestOwnerAt = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUsername, materialId, nowMs);
            long requiredGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerAt);
            if (requiredGapMs > 0L && latestOwnerAt > 0L && nowMs - latestOwnerAt >= 0L && nowMs - latestOwnerAt < requiredGapMs) {
                int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerAt);
                long remMin = Math.max(1L, (requiredGapMs - (nowMs - latestOwnerAt) + 59_999L) / 60_000L);
                throw new XDataProviderException(409, "全局时间锁冷却中（距上一条推文未满 1小时+" + jitterMin + "分钟，还差约 " + remMin + " 分钟）");
            }
            String existingText = existing.path("rewrittenText").asText("").strip();
            ObjectNode liveMat = tx.rewriteMaterial(materialId, nowMs);
            if (!reassess && "JUDGE_FAILED".equals(status) && !existingText.isBlank())
                throw new XDataProviderException(409, "旧判定未完成，请核对原素材后重新评估并发送");
            boolean reuse = !reassess && !existingText.isBlank();
            if (reuse && (existing.path("rewriteSourceNeedsReview").asBoolean()
                    || liveMat != null && !matchesFinalRewriteSource(tx, existing, liveMat))) {
                if (!existing.path("rewriteSourceNeedsReview").asBoolean())
                    tx.put("auto_rewrite", materialId, existing.put("rewriteSourceNeedsReview", true));
                return null;
            }
            if (!reuse && !canReassessRewrite(tx, liveMat, nowMs))
                throw new XDataProviderException(409, "重新评估只适用于北京时间今天发布的高热纯文本原帖");
            ObjectNode liveSrc = tx.get("source", existing.path("authorId").asText());
            ObjectNode next = existing.deepCopy().put("ownerAccountId", ownerAccountId).put("topicId", topicId)
                    .put("status", "EVALUATING").put("error", "");
            next.remove("reassessedFinalVersion");
            if (reassess && !existingText.isBlank() && !"JUDGE_FAILED".equals(status)
                    && !existing.path("rewriteSourceNeedsReview").asBoolean() && matchesFinalRewriteSource(tx, existing, liveMat))
                next.put("reassessedFinalVersion", existing.path("version").asInt());
            if (reuse && liveMat != null) next.put("rewriteSourceFingerprint", rewriteSourceFingerprint(liveMat));
            ObjectNode updated = tx.put("auto_rewrite", materialId, next);
            return new RewriteRetryPlan(
                    updated, reuse, existingText, ownerAccountId, ownerUsername,
                    topicId, liveSrc.deepCopy()
            );
        });

        if (plan == null) throw new XDataProviderException(409, "原素材已变更或缺少旧版依据，请核对后选择重新评估并发送");
        executor.submit(() -> {
            try {
                if (plan.reuseText()) {
                    dispatchRewriteAndPublish(materialId, plan.ownerId(), plan.ownerUsername(),
                            plan.topicId(), plan.existingText(), null, clock.millis(), false);
                } else {
                    String sent = processAuthorRewriteOnce(plan.source(), plan.ownerId(), plan.ownerUsername(), materialId);
                    if (sent.isEmpty()) markEvaluationFailed("auto_rewrite", materialId, "原素材当前不满足洗稿条件");
                }
            } catch (RuntimeException ex) {
                markEvaluationFailed("auto_rewrite", materialId, safeError(ex));
                log.warn("Auto-rewrite retry failed for {}: {}", materialId, ex.getMessage());
            }
        });
        return plan.updated();
    }

    static boolean rewriteRetryable(String status) {
        return RETRYABLE_STATUSES.contains(status) || "SKIPPED_LIMIT".equals(status);
    }

    private boolean dispatchRewriteAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            String proposedText,
            PiAgentViralJudge.RewriteJudgment judgment,
            long evaluatedAt,
            boolean enforceQuietWindow
    ) {
        boolean savedRetry = judgment == null;
        String rewrittenText = store.tx(tx -> {
            var rec = authorizedRewriteAtStage(tx, materialId, ownerId, "EVALUATING");
            if (rec == null || !rewriteSourceStillValid(tx, rec, materialId, savedRetry, enforceQuietWindow)) return null;
            var material = tx.rewriteMaterial(materialId, clock.millis());
            try {
                return PiAgentViralJudge.validatedRewriteText(proposedText, material == null ? "" : material.path("text").asText(""));
            } catch (IllegalStateException invalid) {
                rec.remove(List.of("rewrittenText", "commentText"));
                tx.put("auto_rewrite", materialId, rec.put("status", "JUDGE_FAILED").put("error", safeError(invalid)));
                return null;
            }
        });
        if (rewrittenText == null) return false;
        final String accessToken;
        try {
            accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId, store.isOwner(ownerId),
                    () -> oauthService.getValidWriteAccessToken(ownerId, false));
        } catch (RuntimeException ex) {
            store.tx(tx -> {
                ObjectNode rec = authorizedRewriteAtStage(tx, materialId, ownerId, "EVALUATING");
                if (rec != null) {
                    long checkedAt = clock.millis();
                    if ((rec.has("reassessedFinalVersion") || !withinRewriteWindow(tx.rewriteMaterial(materialId, checkedAt), checkedAt))
                            && !rewriteSourceStillValid(tx, rec, materialId, savedRetry, enforceQuietWindow))
                        return null;
                    ObjectNode next = applyRewriteJudgment(rec.deepCopy(), judgment, evaluatedAt, rewrittenText);
                    tx.put("auto_rewrite", materialId, next
                            .put("status", UpstreamBudgetGuard.isBudgetReached(ex) ? "FAILED" : "AUTH_REQUIRED")
                            .put("error", safeError(ex)));
                }
                return null;
            });
            return false;
        }

        boolean timelineVerified = enforceQuietWindow && store.tx(tx -> recentOwnerTimelineCheck(tx, ownerId, ownerUsername, clock.millis()))
                || refreshOwnerTimeline(ownerId, ownerUsername.replaceFirst("^@+", "")) >= 0L;
        boolean readyToSend = store.tx(tx -> {
            ObjectNode rec = authorizedRewriteAtStage(tx, materialId, ownerId, "EVALUATING");
            if (rec == null) return false;
            applyRewriteJudgment(rec, judgment, evaluatedAt, rewrittenText);
            if (!timelineVerified) {
                if (!rewriteSourceStillValid(tx, rec, materialId, savedRetry, enforceQuietWindow)) return false;
                tx.put("auto_rewrite", materialId, rec.put("status", "SKIPPED_COOLDOWN").put("reason", "暂时无法核实发布账号的最新发帖时间，请稍后重试"));
                return false;
            }
            if (!rewriteMaySend(tx, rec, materialId, ownerId, ownerUsername, enforceQuietWindow, savedRetry)) return false;
            tx.put("auto_rewrite", materialId, rec.put("status", "SENDING").put("dispatchedAt", clock.millis()));
            return true;
        });
        if (!readyToSend) return false;

        PublicationReceipt receipt;
        try {
            receipt = PaidCapabilityGate.withCaller(PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId, store.isOwner(ownerId),
                    () -> XPublishClient.withPublicationPreflight(rewrittenText, null,
                            () -> verifySendingRewrite(materialId, ownerId, ownerUsername, enforceQuietWindow, savedRetry),
                            () -> publishClient.createTweet(accessToken, rewrittenText, List.of(), null)));
        } catch (RuntimeException ex) {
            return !markSendFailure("auto_rewrite", materialId, ex);
        }
        completeAutoPublish(AutoPublishMode.REWRITE, materialId, ownerId, ownerUsername, topicId, receipt, null, rewrittenText);
        return true;
    }

    static long rewriteDayStart(long now) {
        return Instant.ofEpochMilli(now).atZone(AssistantRetention.ZONE).toLocalDate()
                .atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
    }

    static boolean withinRewriteWindow(ObjectNode source, long now) {
        long published = source == null ? 0L : source.path("publishedAt").asLong();
        return published > 0 && published >= rewriteDayStart(now) && published <= now;
    }

    static boolean canReassessRewrite(AssistantStore.Tx tx, ObjectNode material, long now) {
        if (material == null || !withinRewriteWindow(material, now) || !isPureTextRewriteCandidate(material)) return false;
        var pool = tx.rewriteWindow(material.path("authorId").asText(), material.path("username").asText(), now - REWRITE_MAX_AGE_MS, now);
        return RewriteTraffic.of(pool).qualifies(material);
    }

    private boolean rewriteSourceStillValid(AssistantStore.Tx tx, ObjectNode rec, String materialId, boolean savedRetry, boolean automatic) {
        long now = clock.millis();
        ObjectNode material = tx.rewriteMaterial(materialId, now);
        boolean changed = rec.path("rewriteSourceNeedsReview").asBoolean()
                || material != null && !matchesFinalRewriteSource(tx, rec, material);
        if (!changed && (savedRetry && !automatic || canReassessRewrite(tx, material, now))) return true;
        if (savedRetry) {
            if (changed) rec.put("rewriteSourceNeedsReview", true);
            rec.put("status", "FAILED").put("error", changed ? "原素材已变更，请重新评估定稿" : "原素材已过期或热度下降，已保留定稿供人工重试");
        } else if (!changed && !automatic && rec.has("reassessedFinalVersion")) {
            ObjectNode approved = tx.version("auto_rewrite", materialId, rec.path("reassessedFinalVersion").asInt());
            if (tx.publicationOwnerMatches(approved, rec.path("ownerAccountId").asText())) {
                tx.put("auto_rewrite", materialId, approved.put("rewriteSourceFingerprint", rec.path("rewriteSourceFingerprint").asText())
                        .put("error", "原素材已过期或热度下降，已保留原定稿供人工重试"));
                return false;
            }
            rec.remove(List.of("rewrittenText", "commentText", "reassessedFinalVersion"));
            rec.put("status", "JUDGE_FAILED").put("error", "无法核对原定稿的发布账号");
        } else {
            rec.remove(List.of("rewrittenText", "commentText"));
            rec.put("status", "JUDGE_FAILED").put("error", "原素材已变更、过期或不再符合高热纯文本洗稿条件");
        }
        tx.put("auto_rewrite", materialId, rec);
        return false;
    }

    private boolean rewriteMaySend(AssistantStore.Tx tx, ObjectNode rec, String materialId, String ownerId, String username,
                                   boolean checkQuiet, boolean savedRetry) {
        if (!rewriteSourceStillValid(tx, rec, materialId, savedRetry, checkQuiet)) return false;
        String topic = tx.publicationTopic(rec, materialId);
        boolean reserved = "SENDING".equals(rec.path("status").asText());
        if (hasConflictingRewritePublication(tx, materialId, topic, reserved)) {
            tx.put("auto_rewrite", materialId, rec.put("status", "SKIPPED_DUPLICATE").put("reason", "该素材或选题已有发布或待核实记录"));
            return false;
        }
        long now = clock.millis();
        ObjectNode settings = AssistantWorkflow.settings(tx);
        int limit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
        long dayStart = rewriteDayStart(now);
        int ownReservation = reserved && rec.path("dispatchedAt").asLong() >= dayStart ? 1 : 0;
        if (autoPublicationsToday(tx, now) - ownReservation >= limit) {
            tx.put("auto_rewrite", materialId, rec.put("status", "SKIPPED_LIMIT").put("reason", "已达当日自动发布上限"));
            return false;
        }
        if (checkQuiet && isInAutoRepostQuietWindow(now)) {
            tx.put("auto_rewrite", materialId, rec.put("status", "SKIPPED_QUIET_HOURS").put("reason", quietWindowReason()));
            return false;
        }
        if (!recentOwnerTimelineCheck(tx, ownerId, username, now)) {
            tx.put("auto_rewrite", materialId, rec.put("status", "SKIPPED_COOLDOWN").put("reason", "发布账号的发帖时间需要重新核实，请稍后重试"));
            return false;
        }
        long latest = resolveLatestOwnerTweetEpochMs(tx, ownerId, username, materialId, now);
        long gap = resolveEffectiveOwnerGapMs(settings, latest);
        if (latest > 0 && latest <= now && now - latest < gap) {
            long minutes = Math.max(1L, (gap - (now - latest) + 59_999L) / 60_000L);
            tx.put("auto_rewrite", materialId, rec.put("status", "SKIPPED_COOLDOWN").put("reason", "发布账号冷却中，还需约 " + minutes + " 分钟"));
            return false;
        }
        if (!topic.equals(rec.path("topicId").asText())) {
            rec.put("topicId", topic);
            if (reserved) tx.put("auto_rewrite", materialId, rec);
        }
        return true;
    }

    private void verifySendingRewrite(String materialId, String ownerId, String username, boolean enforceQuietWindow, boolean savedRetry) {
        boolean allowed = store.tx(tx -> {
            ObjectNode row = authorizedRewriteAtStage(tx, materialId, ownerId, "SENDING");
            return row != null && rewriteMaySend(tx, row, materialId, ownerId, username, enforceQuietWindow, savedRetry);
        });
        if (!allowed) throw new XPublishException(409, "洗稿发送条件已变更", false);
    }

    private static ObjectNode applyRewriteJudgment(
            ObjectNode rec,
            PiAgentViralJudge.RewriteJudgment j,
            long evaluatedAt,
            String rewrittenText
    ) {
        rec.put("autoKind", "REWRITE")
                .put("mode", "REWRITE")
                .put("evaluatedAt", evaluatedAt);
        if (j != null) {
            rec.put("suitabilityScore", j.suitabilityScore())
                    .put("viralScore", j.suitabilityScore())
                    .put("hookPattern", j.hookPattern())
                    .put("summary", j.coreInsight())
                    .put("reason", j.reason());
        }
        if (rewrittenText != null && !rewrittenText.isBlank()) {
            rec.put("rewrittenText", rewrittenText)
                    .put("commentText", rewrittenText);
        }
        return rec;
    }
}
