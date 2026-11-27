package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.provider.XPublishClient.XPublishException;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xops.hub.assistant.AssistantStore.object;

@Service
public class AutoRepostService {
    private static final Logger log = LoggerFactory.getLogger(AutoRepostService.class);
    static final long MAX_FRESHNESS_AGE_MS = 4L * 60L * 60_000L;
    static final long PATROL_MAX_FRESHNESS_AGE_MS = 48L * 60L * 60_000L;
    static final long RECENT_REPOST_FALLBACK_MAX_AGE_MS = 30L * 24L * 60L * 60_000L;
    static final long REWRITE_MAX_AGE_MS = 30L * 24L * 60L * 60_000L;
    public static final long SAME_AUTHOR_COOLDOWN_MS = 16L * 60L * 60_000L;
    public static final long OWNER_TWEET_BASE_INTERVAL_MS = 60L * 60_000L;
    public static final int OWNER_TWEET_MIN_JITTER_MINUTES = 1;
    public static final int OWNER_TWEET_MAX_JITTER_MINUTES = 20;
    private static final long HOT_CONTEXT_WINDOW_MS = 48L * 60L * 60_000L;
    private static final java.util.regex.Pattern EMBEDDED_URL_PATTERN = java.util.regex.Pattern.compile("(?i)https?://\\S+");
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

    private final AssistantStore store;
    private final SourceCollector sources;
    private final PiAgentViralJudge judge;
    private final XOAuthService oauthService;
    private final XPublishClient publishClient;
    private final Clock clock;
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
    private final Map<String, ObjectNode> previewCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
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
            log.info("Local/non-production runtime detected; auto-repost background ingest hooks and startup retries are disabled (preview remains available).");
            return;
        }
        List<String> startupRetryIds = recoverStaleRecords();
        sources.setIngestHook(this::enqueue);
        sources.setBatchIngestHook(this::enqueueAuthorBatch);
        long bootNowMs = clock.millis();
        if (isInAutoRepostQuietWindow(bootNowMs)) {
            scheduleQuietWindowEndWakeup(bootNowMs);
        }
        if (!startupRetryIds.isEmpty()) {
            deferredScheduler.schedule(() -> {
                long retryNow = clock.millis();
                if (isInAutoRepostQuietWindow(retryNow)) {
                    log.info("Skipping {} startup auto-repost retries during quiet window ({}).",
                            startupRetryIds.size(), quietWindowReason());
                    scheduleQuietWindowEndWakeup(retryNow);
                    return;
                }
                for (String id : startupRetryIds) {
                    try {
                        retry(id);
                    } catch (Exception e) {
                        log.warn("Startup queued retry failed for {}: {}", id, e.getMessage());
                    }
                }
            }, 3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    List<String> recoverStaleRecords() {
        List<String> startupRetryIds = new ArrayList<>();
        store.tx(tx -> {
            for (String table : List.of("auto_repost", "auto_rewrite")) {
                for (ObjectNode rec : tx.all(table)) {
                    String id = rec.path("materialId").asText(rec.path("id").asText("")).strip();
                    if (id.isBlank()) continue;
                    String status = rec.path("status").asText("");
                    if ("EVALUATING".equals(status)) {
                        tx.put(table, id, rec.deepCopy()
                                .put("status", "JUDGE_FAILED")
                                .put("error", "服务重启中断判定，可手动重试"));
                    } else if ("SENDING".equals(status)) {
                        tx.put(table, id, rec.deepCopy()
                                .put("status", "UNCERTAIN")
                                .put("error", "发送途中服务重启，请先核实线上时间线避免重复发送"));
                    } else if ("RETRY_QUEUED".equals(status)) {
                        tx.put(table, id, rec.deepCopy()
                                .put("status", "FAILED")
                                .put("error", "等待重启后重发"));
                        if ("auto_repost".equals(table)) {
                            startupRetryIds.add(id);
                        }
                    }
                }
            }
            return null;
        });
        return startupRetryIds;
    }

    public ObjectNode retry(String materialId) {
        String id = materialId == null ? "" : materialId.strip();
        if (id.isBlank()) throw new IllegalArgumentException("缺少 materialId");
        boolean isRewriteOnly = store.tx(tx -> tx.get("auto_repost", id) == null && tx.get("auto_rewrite", id) != null);
        if (isRewriteOnly) {
            return retryRewrite(id);
        }

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
            ObjectNode existing = tx.get("auto_repost", id);
            if (existing == null) {
                throw new XDataProviderException(404, "自动转发记录不存在");
            }
            String status = existing.path("status").asText("");
            if (!RETRYABLE_STATUSES.contains(status)) {
                throw new XDataProviderException(409, "当前状态不允许重发，请先核实线上回执");
            }
            String topicId = existing.path("topicId").asText("");
            if (topicId.isBlank()) topicId = AssistantTopics.topicOf(tx, id);
            String targetAuthorId = existing.path("authorId").asText("");
            String targetUsername = existing.path("username").asText("").replaceFirst("^@+", "");
            long nowMs = clock.millis();
            boolean exemptAuthorCd = PiAgentViralJudge.isDirectPassCategory(existing.path("category").asText(""));
            ObjectNode recentSameAuthor = exemptAuthorCd
                    ? null
                    : findRecentSameAuthorRepost(tx.all("auto_repost"), targetAuthorId, targetUsername, id, nowMs);
            if (recentSameAuthor != null) {
                throw new XDataProviderException(409, "16 小时内已转发过 @" + targetUsername + "，避免连续转发同一人");
            }
            for (ObjectNode rec : tx.all("auto_repost")) {
                String otherId = rec.path("materialId").asText(rec.path("id").asText(""));
                if (id.equals(otherId)) continue;
                if (!topicId.isBlank() && topicId.equals(rec.path("topicId").asText(""))
                        && Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(rec.path("status").asText(""))) {
                    throw new XDataProviderException(409, "同一事件选题已有发布或待确认记录，禁止重复发送");
                }
            }
            ObjectNode owner = tx.get("meta", "owner");
            if (owner == null || owner.path("accountId").asText("").isBlank()) {
                throw new XDataProviderException(400, "缺少内容助手所有者信息");
            }
            String ownerAccountId = owner.path("accountId").asText("");
            String ownerUsername = owner.path("username").asText("i");
            ObjectNode settings = AssistantWorkflow.settings(tx);
            long latestOwnerTweetAt = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUsername, id, nowMs);
            long requiredOwnerGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerTweetAt);
            if (requiredOwnerGapMs > 0L && latestOwnerTweetAt > 0L
                    && nowMs - latestOwnerTweetAt >= 0L && nowMs - latestOwnerTweetAt < requiredOwnerGapMs) {
                int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerTweetAt);
                long remMin = Math.max(1L, (requiredOwnerGapMs - (nowMs - latestOwnerTweetAt) + 59_999L) / 60_000L);
                throw new XDataProviderException(409,
                        "距我账号上一条推文未满 1小时+" + jitterMin + "分钟（还差约 " + remMin + " 分钟），暂不引用转发");
            }
            String existingComment = existing.path("commentText").asText("").strip();
            boolean reuseComment = Set.of("AUTH_REQUIRED", "FAILED").contains(status) && !existingComment.isBlank();
            ObjectNode liveMaterial = tx.get("material", id);
            String authorId = existing.path("authorId").asText(liveMaterial == null ? "" : liveMaterial.path("authorId").asText(""));
            ObjectNode liveSource = authorId.isBlank() ? null : tx.get("source", authorId);
            if (liveSource == null) {
                liveSource = object()
                        .put("authorId", authorId)
                        .put("username", existing.path("username").asText(""))
                        .put("displayName", existing.path("authorName").asText(""))
                        .put("autoRepostTextMode", "AI")
                        .put("autoRepostMinScore", 82);
            }
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            ObjectNode updated = tx.put("auto_repost", id, existing.deepCopy()
                    .put("status", "EVALUATING")
                    .put("error", ""));
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

        executor.submit(() -> {
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
                            false
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
                            false
                    );
                }
            } catch (Exception ex) {
                log.warn("Auto-repost retry failed for material {}: {}", id, ex.getMessage());
            }
        });
        return plan.updated();
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
                tx.all("auto_repost"), candidateAuthorId, candidateUsername, excludeMaterialId, nowMs);
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
        for (ObjectNode rec : tx.all("auto_repost")) {
            String recId = rec.path("materialId").asText(rec.path("id").asText("")).strip();
            if (!cleanExclude.isBlank() && cleanExclude.equals(recId)) continue;
            if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(rec.path("status").asText(""))) continue;
            long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0)));
            if (recAt > 0L && recAt <= nowMs) latest = Math.max(latest, recAt);
        }
        for (ObjectNode rec : tx.all("auto_rewrite")) {
            String recId = rec.path("materialId").asText(rec.path("id").asText("")).strip();
            if (!cleanExclude.isBlank() && cleanExclude.equals(recId)) continue;
            if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(rec.path("status").asText(""))) continue;
            long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0)));
            if (recAt > 0L && recAt <= nowMs) latest = Math.max(latest, recAt);
        }
        for (ObjectNode pub : tx.all("publication")) {
            if (pub.path("deleted").asBoolean(false)) continue;
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
        if (scheduledTweetService != null && ownerAccountId != null && !ownerAccountId.isBlank()) {
            long schedAt = scheduledTweetService.latestAccountPublishEpochMs(ownerAccountId, ownerUsername);
            if (schedAt > 0L && schedAt <= nowMs) latest = Math.max(latest, schedAt);
        }
        if (communityHubService != null) {
            long selfPostAt = communityHubService.latestCachedOwnerPostEpochMs(ownerAccountId, ownerUsername);
            if (selfPostAt > 0L && selfPostAt <= nowMs) latest = Math.max(latest, selfPostAt);
        }
        ObjectNode liveObserved = tx.get("meta", "owner_latest_tweet");
        if (liveObserved != null) {
            long observedAt = liveObserved.path("latestTweetAt").asLong(0L);
            if (observedAt > 0L && observedAt <= nowMs) latest = Math.max(latest, observedAt);
        }
        String normOwnerUser = (ownerUsername == null || ownerUsername.isBlank())
                ? "elidlicom" : ownerUsername.replaceFirst("^@+", "").strip();
        for (ObjectNode hm : tx.all("history_material")) {
            if (hm.path("selfThreadReply").asBoolean(false)) continue;
            String kind = hm.path("kind").asText("POST");
            if (!Set.of("POST", "QUOTE").contains(kind)) continue;
            boolean sameOwner = (ownerAccountId != null && !ownerAccountId.isBlank() && ownerAccountId.equals(hm.path("authorId").asText("")))
                    || normOwnerUser.equalsIgnoreCase(hm.path("username").asText("").replaceFirst("^@+", ""));
            if (!sameOwner) continue;
            long hmAt = hm.path("publishedAt").asLong(0L);
            if (hmAt > 0L && hmAt <= nowMs) latest = Math.max(latest, hmAt);
        }
        return latest;
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
            boolean samePerson = (!cleanAuthorId.isBlank() && cleanAuthorId.equals(recAuthorId))
                    || (!cleanUname.isBlank() && cleanUname.equalsIgnoreCase(recUname));
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
                boolean same = (!authorId.isBlank() && authorId.equals(m.path("authorId").asText("")))
                        || (!username.isBlank() && username.equalsIgnoreCase(m.path("username").asText("").replaceFirst("^@+", "")));
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
            long followers = Math.max(
                    source.path("followersCount").asLong(0),
                    material.path("authorFollowersCount").asLong(0));
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

    private static String composeOwnerQuoteVoiceProfile(AssistantStore.Tx tx) {
        OwnerQuoteStyleDistiller.resolveOrDistillQuoteStyle(tx);
        return OwnerQuoteStyleDistiller.composeOwnerDistilledPersona(tx);
    }

    private AuthorPreviewContext loadAuthorPreviewContext(String rawSourceKey, ObjectNode req, int limit) {
        String key = rawSourceKey == null ? "" : rawSourceKey.replaceFirst("^@+", "").strip();
        if (key.isBlank()) throw new IllegalArgumentException("缺少监听对象标识");

        AuthorPreviewContext initial = store.tx(tx -> {
            ObjectNode owner = tx.get("meta", "owner");
            String ownerUname = owner != null && !owner.path("username").asText("").isBlank()
                    ? owner.path("username").asText() : "elidlicom";

            ObjectNode foundSource = tx.get("source", key);
            if (foundSource == null || "REMOVED".equals(foundSource.path("status").asText())) {
                for (ObjectNode s : tx.all("source")) {
                    if ("REMOVED".equals(s.path("status").asText())) continue;
                    if (key.equalsIgnoreCase(s.path("username").asText("").replaceFirst("^@+", ""))
                            || key.equals(s.path("authorId").asText(""))) {
                        foundSource = s;
                        break;
                    }
                }
            }

            String targetAuthorId = foundSource != null
                    ? foundSource.path("authorId").asText(foundSource.path("id").asText(""))
                    : "";
            String targetUsername = foundSource != null
                    ? foundSource.path("username").asText("").replaceFirst("^@+", "")
                    : key;

            List<ObjectNode> matchingMaterials = new ArrayList<>();
            for (ObjectNode m : tx.all("material")) {
                String mAuthorId = m.path("authorId").asText("");
                String mUname = m.path("username").asText("").replaceFirst("^@+", "");
                boolean match = (!targetAuthorId.isBlank() && targetAuthorId.equals(mAuthorId))
                        || (!targetUsername.isBlank() && targetUsername.equalsIgnoreCase(mUname));
                if (!match) continue;
                String combined = AssistantTopics.text(m);
                if (combined == null || combined.isBlank()) continue;
                matchingMaterials.add(m.deepCopy());
            }
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

            List<ObjectNode> topMaterials = matchingMaterials.stream().limit(limit).toList();
            java.util.Map<String, ObjectNode> byMat = new java.util.HashMap<>();
            for (ObjectNode m : topMaterials) {
                String mid = m.path("id").asText("");
                if (!mid.isBlank()) {
                    ObjectNode rec = tx.get("auto_repost", mid);
                    if (rec != null) byMat.put(mid, rec.deepCopy());
                }
            }
            java.util.Map<String, Long> activeTimes = new java.util.HashMap<>();
            for (ObjectNode rec : tx.all("auto_repost")) {
                String recStatus = rec.path("status").asText("");
                if (!Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) continue;
                String recAid = rec.path("authorId").asText("");
                String recUname = rec.path("username").asText("").replaceFirst("^@+", "");
                boolean same = (!targetAuthorId.isBlank() && targetAuthorId.equals(recAid))
                        || (!targetUsername.isBlank() && targetUsername.equalsIgnoreCase(recUname));
                if (!same) continue;
                String recMid = rec.path("materialId").asText(rec.path("id").asText(""));
                long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("sourcePublishedAt").asLong(0)));
                if (!recMid.isBlank() && recAt > 0) activeTimes.put(recMid, recAt);
            }

            ObjectNode normalizedSource = foundSource.deepCopy();
            normalizedSource.put("autoRepostMinScore", SourceCollector.resolveAutoRepostMinScore(normalizedSource));
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            ObjectNode settings = AssistantWorkflow.settings(tx);
            String ownerAccountId = owner != null ? owner.path("accountId").asText("") : "";
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
        if (candidates.isEmpty()) {
            List<ObjectNode> fetched = sources.fetchOnDemandPreviewMaterials(effectiveSource);
            for (ObjectNode m : fetched) {
                if (candidates.size() >= limit) break;
                candidates.add(m);
            }
        }
        return new AuthorPreviewContext(
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

    private String buildPreviewCacheKey(
            String materialId,
            ObjectNode effectiveSource,
            String styleProfile,
            String preferencesText,
            boolean postPolishEnabled,
            String postPolishPrompt,
            String generationProvider,
            String generationModel
    ) {
        String textMode = effectiveSource.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        int minScore = SourceCollector.resolveAutoRepostMinScore(effectiveSource);
        String tpl = effectiveSource.path("autoRepostTemplate").asText("").strip();
        String ruleHint = effectiveSource.path("autoRepostRuleHint").asText("").strip();
        String activePolish = postPolishEnabled ? (postPolishPrompt == null ? "" : postPolishPrompt.strip()) : "";
        String genKey = (generationProvider == null || generationProvider.isBlank() ? "chatgpt" : generationProvider)
                + ":" + (generationModel == null ? "" : generationModel.strip());
        return "v10-pi-distill|" + materialId + "|" + textMode + "|" + minScore + "|" + tpl + "|" + ruleHint + "|"
                + genKey + "|" + styleProfile.hashCode() + "|" + preferencesText.hashCode() + "|" + activePolish.hashCode();
    }

    private String buildRewritePreviewCacheKey(
            String materialId,
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
        return "rw-v1|" + materialId + "|" + genKey + "|"
                + styleProfile.hashCode() + "|" + preferencesText.hashCode() + "|" + activePolish.hashCode();
    }

    static String classifyDecisionCode(
            String persistedStatus,
            boolean authorCooldownBlocked,
            boolean wouldRepost,
            boolean viralPass,
            boolean accountFit
    ) {
        if ("PUBLISHED".equals(persistedStatus)) return "PUBLISHED";
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

    private ObjectNode applySingleItemAuthorCooldownOverlay(
            ObjectNode decision,
            String materialId,
            long materialPublishedAt,
            AuthorPreviewContext ctx
    ) {
        ObjectNode out = decision.deepCopy();
        String persisted = out.path("persistedStatus").asText("");
        if ("PUBLISHED".equals(persisted)) return out;
        boolean contentPass = out.path("contentPass").asBoolean(
                out.path("viralPass").asBoolean(false) && out.path("accountFit").asBoolean(false));
        boolean exemptAuthorCd = PiAgentViralJudge.isDirectPassCategory(out.path("category").asText(""));
        out.put("contentPass", contentPass)
                .put("directPass", exemptAuthorCd && contentPass);
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
        String cacheKey = buildPreviewCacheKey(
                materialId, effectiveSource, ctx.styleProfile(), ctx.preferencesText(),
                ctx.postPolishEnabled(), ctx.postPolishPrompt(),
                ctx.generationProvider(), ctx.generationModel());

        ObjectNode cached = previewCache.get(cacheKey);
        if (cached != null && cached.has("preview")) {
            ObjectNode decision = cached.path("preview").deepCopy();
            decision.put("evaluated", true).put("cached", true);
            return applySingleItemAuthorCooldownOverlay(decision, materialId, m.path("publishedAt").asLong(0), ctx);
        }

        boolean sameRuleHint = ruleHint.equals(ctx.storedSource().path("autoRepostRuleHint").asText("").strip());
        boolean customGenRequested = !ctx.generationProvider().isBlank() && !"chatgpt".equalsIgnoreCase(ctx.generationProvider());
        ObjectNode rec = ctx.autoRepostsByMaterialId().get(materialId);
        if (rec != null && sameRuleHint && !customGenRequested) {
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
                boolean authorCooldown = !directPass && "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus);
                boolean effectiveGuardSkip = isGuardSkip && !(directPass && "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus));
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
                        directPass && "SKIPPED_AUTHOR_COOLDOWN".equals(recStatus) ? "" : recStatus,
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

        return object()
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
                .put("decisionCode", "PENDING_EVAL")
                .put("reason", "")
                .put("commentText", "")
                .put("textMode", textMode);
    }

    public ObjectNode authorTimeline(String rawSourceKey, ObjectNode body) {
        ObjectNode req = body == null ? object() : body;
        AuthorPreviewContext ctx = loadAuthorPreviewContext(rawSourceKey, req, 20);
        ObjectNode effectiveSource = ctx.effectiveSource();

        ObjectNode out = object();
        out.putObject("owner")
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
                effectiveSource.path("authorId").asText(effectiveSource.path("id").asText("")),
                effectiveSource.path("username").asText(""),
                nowMs);
        List<ObjectNode> timelineMaterials = new ArrayList<>(ctx.materials());
        Set<String> existingMatIds = new java.util.HashSet<>();
        for (ObjectNode m : timelineMaterials) {
            String mid = m.path("id").asText("").strip();
            if (!mid.isBlank()) existingMatIds.add(mid);
        }
        List<ObjectNode> extraRewriteMats = new ArrayList<>();
        for (ObjectNode m : rwBaseline.windowMaterials()) {
            String mid = m.path("id").asText("").strip();
            if (mid.isBlank() || existingMatIds.contains(mid)) continue;
            if (!isPureTextRewriteCandidate(m)) continue;
            if (!isHighTrafficForAuthor(m, rwBaseline.medViews(), rwBaseline.medLikes(), rwBaseline.medEng(), rwBaseline.sampleCount())) {
                continue;
            }
            extraRewriteMats.add(m);
        }
        extraRewriteMats.sort(Comparator.comparingDouble(
                (ObjectNode m) -> computeRewriteRankScore(m, nowMs, rwBaseline.medViews(), rwBaseline.medEng())).reversed());
        for (int i = 0; i < extraRewriteMats.size() && i < 6; i++) {
            ObjectNode extra = extraRewriteMats.get(i);
            timelineMaterials.add(extra);
            existingMatIds.add(extra.path("id").asText("").strip());
        }

        record RawTimelineRow(ObjectNode rawMaterial, ObjectNode materialNode, ObjectNode decisionNode, ObjectNode rewriteNode, long publishedAt, String id) { }
        List<RawTimelineRow> rows = new ArrayList<>();
        for (ObjectNode m : timelineMaterials) {
            ObjectNode decision = resolveStoredDecisionIfAvailable(m, ctx);
            if (!"PUBLISHED".equals(decision.path("persistedStatus").asText(""))) {
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
                    boolean exemptAuthorCd = PiAgentViralJudge.isDirectPassCategory(decision.path("category").asText(""));
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
            ObjectNode rewriteNode = buildRewriteStatusNode(m, decision, ctx, rwBaseline, nowMs);
            rows.add(new RawTimelineRow(
                    m,
                    buildMaterialSummaryNode(m, effectiveSource),
                    decision,
                    rewriteNode,
                    m.path("publishedAt").asLong(0),
                    m.path("id").asText("")
            ));
        }

        // Assign 1-based priority rank across eligible pure-text high-traffic candidates (recent + high heat first)
        List<RawTimelineRow> rankedRewrites = rows.stream()
                .filter(r -> r.rewriteNode().path("candidate").asBoolean(false))
                .sorted(Comparator
                        .comparingDouble((RawTimelineRow r) -> r.rewriteNode().path("rankScore").asDouble(0.0))
                        .thenComparingLong(RawTimelineRow::publishedAt)
                        .reversed())
                .toList();
        for (int i = 0; i < rankedRewrites.size(); i++) {
            rankedRewrites.get(i).rewriteNode().put("rank", i + 1);
        }

        // Enforce 16-hour non-consecutive same-author window chronologically (oldest -> newest) across preview items
        // Direct-pass categories (MODEL_RELEASE / FEATURE_UPDATE / QUOTA_RESET) are exempt from same-author 16h CD!
        if (!dualCd.globalCdActive()) {
            List<Long> occupiedSlots = new ArrayList<>(ctx.sameAuthorActiveRepostTimesByMaterialId().values());
            for (int i = rows.size() - 1; i >= 0; i--) {
                RawTimelineRow row = rows.get(i);
                ObjectNode dec = row.decisionNode();
                if (!dec.path("evaluated").asBoolean(false)) continue;
                if ("PUBLISHED".equals(dec.path("persistedStatus").asText(""))) continue;
                if (!dec.path("wouldAutoRepost").asBoolean(false)) continue;
                long ts = row.publishedAt() > 0 ? row.publishedAt() : clock.millis();
                boolean exemptAuthorCd = PiAgentViralJudge.isDirectPassCategory(dec.path("category").asText(""));
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
        ObjectNode req = body == null ? object() : body;
        boolean forceRegenerate = req.path("forceRegenerate").asBoolean(false);
        AuthorPreviewContext base = loadAuthorPreviewContext(rawSourceKey, req, 20);
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

        for (ObjectNode m : toEvaluate) {
            String mid = m.path("id").asText("");
            PiAgentViralJudge.ViralJudgment j = batchJudgment.byMaterialId().get(mid);
            if (j == null) continue;
            ObjectNode existingRec = base.autoRepostsByMaterialId().get(mid);
            String persistedStatus = existingRec == null ? "" : existingRec.path("status").asText("");
            boolean directPass = PiAgentViralJudge.isDirectPassCategory(j.category())
                    && j.accountFit() && j.fitScore() >= 65 && j.credibility() >= 55 && j.factScore() >= 50;
            boolean viralPass = directPass || (j.viralScore() >= minScore && j.factScore() >= 55 && j.credibility() >= 60);
            boolean fitPass = j.accountFit() && (directPass ? j.fitScore() >= 65 : j.fitScore() >= 68);
            boolean contentPass = j.shouldRepost();
            boolean rawWouldRepost = "PUBLISHED".equals(persistedStatus) || contentPass;
            String commentText = contentPass ? buildCommentText(useTemplate, effectiveSource, m, j) : "";
            String rawDecisionCode = classifyDecisionCode(
                    directPass && "SKIPPED_AUTHOR_COOLDOWN".equals(persistedStatus) ? "" : persistedStatus,
                    false, rawWouldRepost, viralPass, fitPass);

            String cacheKey = buildPreviewCacheKey(
                    mid, effectiveSource, base.styleProfile(), base.preferencesText(),
                    base.postPolishEnabled(), base.postPolishPrompt(),
                    base.generationProvider(), base.generationModel());
            ObjectNode cachedEntry = object();
            cachedEntry.putObject("owner")
                    .put("username", base.ownerUsername())
                    .put("displayName", base.ownerUsername())
                    .put("avatarUrl", "/author-avatar.webp");
            cachedEntry.set("source", buildSourceSummaryNode(effectiveSource, base));
            cachedEntry.set("material", buildMaterialSummaryNode(m, effectiveSource));
            cachedEntry.putObject("preview")
                    .put("evaluated", true)
                    .put("commentText", commentText)
                    .put("wouldAutoRepost", rawWouldRepost)
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
            previewCache.put(cacheKey, cachedEntry);
        }

        return authorTimeline(rawSourceKey, req)
                .put("evaluatedAccountOnce", true)
                .put("selectedMaterialId", batchJudgment.selectedMaterialId());
    }

    public ObjectNode previewRepost(String rawSourceKey, ObjectNode body) {
        ObjectNode req = body == null ? object() : body;
        String requestedMaterialId = req.path("materialId").asText("").strip();
        boolean forceRegenerate = req.path("forceRegenerate").asBoolean(false);
        boolean forceWriteComment = req.path("forceWriteComment").asBoolean(false) || forceRegenerate;
        AuthorPreviewContext base = loadAuthorPreviewContext(rawSourceKey, req, 20);
        ObjectNode effectiveSource = base.effectiveSource();
        List<ObjectNode> candidates = base.materials();
        if (candidates.isEmpty()) {
            String key = rawSourceKey == null ? "" : rawSourceKey.replaceFirst("^@+", "").strip();
            throw new XDataProviderException(404, "@" + effectiveSource.path("username").asText(key) + " 暂无可预览的近期推文");
        }

        ObjectNode selectedMaterial = candidates.get(0);
        if (!requestedMaterialId.isBlank()) {
            for (ObjectNode c : candidates) {
                if (requestedMaterialId.equals(c.path("id").asText(""))) {
                    selectedMaterial = c;
                    break;
                }
            }
        }

        String materialId = selectedMaterial.path("id").asText("");
        long publishedAt = selectedMaterial.path("publishedAt").asLong(0);
        String textMode = effectiveSource.path("autoRepostTextMode").asText("AI").toUpperCase(Locale.ROOT);
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = SourceCollector.resolveAutoRepostMinScore(effectiveSource);
        String tpl = effectiveSource.path("autoRepostTemplate").asText("").strip();
        String ruleHint = effectiveSource.path("autoRepostRuleHint").asText("").strip();
        String cacheKey = buildPreviewCacheKey(
                materialId, effectiveSource, base.styleProfile(), base.preferencesText(),
                base.postPolishEnabled(), base.postPolishPrompt(),
                base.generationProvider(), base.generationModel());

        if (!forceRegenerate) {
            ObjectNode cached = previewCache.get(cacheKey);
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
                ObjectNode out = object();
                out.putObject("owner")
                        .put("username", base.ownerUsername())
                        .put("displayName", base.ownerUsername())
                        .put("avatarUrl", "/author-avatar.webp");
                out.set("source", buildSourceSummaryNode(effectiveSource, base));
                out.set("material", buildMaterialSummaryNode(selectedMaterial, effectiveSource));
                var recentArr = out.putArray("recentMaterials");
                for (ObjectNode c : candidates.stream().limit(8).toList()) {
                    recentArr.addObject()
                            .put("id", c.path("id").asText(""))
                            .put("publishedAt", c.path("publishedAt").asLong(0))
                            .put("snippet", truncatePreview(AssistantTopics.text(c), 44));
                }
                out.set("preview", storedDecision);
                return out;
            }
        }

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
        ObjectNode existingRec = base.autoRepostsByMaterialId().get(materialId);
        String persistedStatus = existingRec == null ? "" : existingRec.path("status").asText("");

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
        boolean rawWouldRepost = "PUBLISHED".equals(persistedStatus) || contentPass;
        String rawDecisionCode = classifyDecisionCode(
                directPassPreview && "SKIPPED_AUTHOR_COOLDOWN".equals(persistedStatus) ? "" : persistedStatus,
                false, rawWouldRepost, viralPass, accountFit);

        ObjectNode baseOut = object();
        baseOut.putObject("owner")
                .put("username", base.ownerUsername())
                .put("displayName", base.ownerUsername())
                .put("avatarUrl", "/author-avatar.webp");
        baseOut.set("source", buildSourceSummaryNode(effectiveSource, base));
        baseOut.set("material", buildMaterialSummaryNode(selectedMaterial, effectiveSource));

        var recentArr = baseOut.putArray("recentMaterials");
        for (ObjectNode c : candidates.stream().limit(8).toList()) {
            recentArr.addObject()
                    .put("id", c.path("id").asText(""))
                    .put("publishedAt", c.path("publishedAt").asLong(0))
                    .put("snippet", truncatePreview(AssistantTopics.text(c), 44));
        }

        ObjectNode rawPreviewNode = baseOut.putObject("preview")
                .put("evaluated", true)
                .put("commentText", commentText)
                .put("wouldAutoRepost", rawWouldRepost)
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
                .put("persistedStatus", persistedStatus)
                .put("publishedTweetUrl", existingRec == null ? "" : existingRec.path("publishedTweetUrl").asText(""))
                .put("category", category)
                .put("summary", summary)
                .put("baseReason", baseReason)
                .put("reason", baseReason)
                .put("textMode", textMode)
                .put("cached", false)
                .put("fallbackUsed", false)
                .put("fallbackReason", "");

        previewCache.put(cacheKey, baseOut.deepCopy());
        ObjectNode out = baseOut.deepCopy();
        out.set("preview", applySingleItemAuthorCooldownOverlay(rawPreviewNode, materialId, publishedAt, base));
        return out;
    }

    public ObjectNode previewRewrite(String rawSourceKey, ObjectNode body) {
        ObjectNode req = body == null ? object() : body;
        String requestedMaterialId = req.path("materialId").asText("").strip();
        boolean forceRegenerate = req.path("forceRegenerate").asBoolean(false);
        AuthorPreviewContext base = loadAuthorPreviewContext(rawSourceKey, req, 20);
        ObjectNode effectiveSource = base.effectiveSource();
        long nowMs = clock.millis();
        String aid = effectiveSource.path("authorId").asText(effectiveSource.path("id").asText("")).strip();
        String uname = effectiveSource.path("username").asText("").replaceFirst("^@+", "").strip();
        AuthorRewriteBaseline rwBaseline = loadAuthorRewriteBaseline(aid, uname, nowMs);

        List<ObjectNode> pool = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (ObjectNode m : base.materials()) {
            String mid = m.path("id").asText("").strip();
            if (!mid.isBlank() && seen.add(mid)) pool.add(m);
        }
        for (ObjectNode m : rwBaseline.windowMaterials()) {
            String mid = m.path("id").asText("").strip();
            if (!mid.isBlank() && seen.add(mid)) pool.add(m);
        }
        if (pool.isEmpty()) {
            throw new XDataProviderException(404, "@" + uname + " 暂无可预览的近期推文");
        }

        ObjectNode selectedMaterial = null;
        if (!requestedMaterialId.isBlank()) {
            for (ObjectNode c : pool) {
                if (requestedMaterialId.equals(c.path("id").asText(""))) {
                    selectedMaterial = c;
                    break;
                }
            }
            if (selectedMaterial == null) {
                selectedMaterial = store.tx(tx -> {
                    ObjectNode m = tx.get("material", requestedMaterialId);
                    return m == null ? null : m.deepCopy();
                });
            }
            if (selectedMaterial == null) {
                throw new XDataProviderException(404, "未找到指定推文素材：" + requestedMaterialId);
            }
        } else {
            selectedMaterial = pool.stream()
                    .filter(AutoRepostService::isPureTextRewriteCandidate)
                    .filter(m -> isHighTrafficForAuthor(m, rwBaseline.medViews(), rwBaseline.medLikes(), rwBaseline.medEng(), rwBaseline.sampleCount()))
                    .max(Comparator.comparingDouble(
                            m -> computeRewriteRankScore(m, nowMs, rwBaseline.medViews(), rwBaseline.medEng())))
                    .orElse(pool.stream()
                            .filter(AutoRepostService::isPureTextRewriteCandidate)
                            .max(Comparator.comparingDouble(
                                    m -> computeRewriteRankScore(m, nowMs, rwBaseline.medViews(), rwBaseline.medEng())))
                            .orElse(pool.get(0)));
        }

        String pureText = extractPureRewriteSourceText(selectedMaterial);
        if (pureText.isBlank()) {
            throw new XDataProviderException(400, "该推文不满足纯文本洗稿条件（" + rewriteIneligibleReason(selectedMaterial) + "）");
        }

        String materialId = selectedMaterial.path("id").asText("");
        String cacheKey = buildRewritePreviewCacheKey(
                materialId, base.styleProfile(), base.preferencesText(),
                base.postPolishEnabled(), base.postPolishPrompt(),
                base.generationProvider(), base.generationModel());
        if (!forceRegenerate) {
            ObjectNode cached = rewritePreviewCache.get(cacheKey);
            if (cached != null && !cached.path("rewrite").path("rewrittenText").asText("").isBlank()) {
                ObjectNode copy = cached.deepCopy();
                copy.withObject("/rewrite").put("cached", true);
                copy.set("source", buildSourceSummaryNode(effectiveSource, base));
                return copy;
            }
        }

        String authorUname = selectedMaterial.path("username").asText(uname);
        String authorBio = resolveAuthorBio(effectiveSource, List.of(selectedMaterial));
        List<String> stances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                tx, authorUname, authorBio, pureText, "", 4));
        String activePolishPrompt = base.postPolishEnabled() ? base.postPolishPrompt() : "";

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
                    rwBaseline.medViews(),
                    rwBaseline.medLikes(),
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

        ObjectNode existingRewriteRec = rwBaseline.rewriteRecordsByMaterialId().get(materialId);
        String persistedStatus = existingRewriteRec == null ? "" : existingRewriteRec.path("status").asText("");
        String publishedTweetUrl = existingRewriteRec == null ? "" : existingRewriteRec.path("publishedTweetUrl").asText("");
        double rankScore = computeRewriteRankScore(selectedMaterial, nowMs, rwBaseline.medViews(), rwBaseline.medEng());
        boolean highTraffic = isHighTrafficForAuthor(
                selectedMaterial, rwBaseline.medViews(), rwBaseline.medLikes(), rwBaseline.medEng(), rwBaseline.sampleCount());

        ObjectNode rewriteNode = object()
                .put("candidate", true)
                .put("pureText", true)
                .put("highTraffic", highTraffic)
                .put("withinWindow", selectedMaterial.path("publishedAt").asLong(0L) > 0L
                        && nowMs - selectedMaterial.path("publishedAt").asLong(0L) <= REWRITE_MAX_AGE_MS)
                .put("engagement", computeWeightedEngagement(selectedMaterial))
                .put("rankScore", rankScore)
                .put("ineligibleReason", "")
                .put("evaluated", true)
                .put("suitable", rwJudgment.suitable())
                .put("suitabilityScore", rwJudgment.suitabilityScore())
                .put("hookPattern", rwJudgment.hookPattern())
                .put("coreInsight", rwJudgment.coreInsight())
                .put("reason", rwJudgment.reason())
                .put("rewrittenText", rwJudgment.rewrittenText())
                .put("publishedTweetUrl", publishedTweetUrl)
                .put("persistedStatus", persistedStatus)
                .put("status", "PUBLISHED".equals(persistedStatus) ? "PUBLISHED" : "EVALUATED")
                .put("cached", false);

        ObjectNode out = object();
        out.putObject("owner")
                .put("username", base.ownerUsername())
                .put("displayName", base.ownerUsername())
                .put("avatarUrl", "/author-avatar.webp");
        out.set("source", buildSourceSummaryNode(effectiveSource, base));
        out.set("material", buildMaterialSummaryNode(selectedMaterial, effectiveSource));
        out.set("rewrite", rewriteNode);
        rewritePreviewCache.put(cacheKey, out.deepCopy());
        return out;
    }

    private record AuthorRewriteBaseline(
            List<ObjectNode> windowMaterials,
            java.util.Map<String, ObjectNode> rewriteRecordsByMaterialId,
            long medViews,
            long medLikes,
            long medEng,
            int sampleCount
    ) { }

    private AuthorRewriteBaseline loadAuthorRewriteBaseline(String authorId, String username, long nowMs) {
        String aid = authorId == null ? "" : authorId.strip();
        String uname = username == null ? "" : username.replaceFirst("^@+", "").strip();
        return store.tx(tx -> {
            List<ObjectNode> windowMats = new ArrayList<>();
            List<Long> allViews = new ArrayList<>();
            List<Long> allLikes = new ArrayList<>();
            List<Long> allEng = new ArrayList<>();
            for (ObjectNode m : tx.all("material")) {
                boolean same = (!aid.isBlank() && aid.equals(m.path("authorId").asText("")))
                        || (!uname.isBlank() && uname.equalsIgnoreCase(m.path("username").asText("").replaceFirst("^@+", "")));
                if (!same) continue;
                long pubAt = m.path("publishedAt").asLong(0L);
                if (pubAt <= 0L || nowMs - pubAt > REWRITE_MAX_AGE_MS) continue;
                windowMats.add(m.deepCopy());
                long v = m.path("views").asLong(0L);
                long l = m.path("likes").asLong(0L);
                long e = computeWeightedEngagement(m);
                if (v > 0L) allViews.add(v);
                if (l > 0L) allLikes.add(l);
                if (e > 0L) allEng.add(e);
            }
            java.util.Map<String, ObjectNode> rwByMat = new java.util.HashMap<>();
            for (ObjectNode rec : tx.all("auto_rewrite")) {
                String recAid = rec.path("authorId").asText("");
                String recUname = rec.path("username").asText("").replaceFirst("^@+", "");
                boolean same = (!aid.isBlank() && aid.equals(recAid))
                        || (!uname.isBlank() && uname.equalsIgnoreCase(recUname));
                if (!same) continue;
                String mid = rec.path("materialId").asText(rec.path("id").asText("")).strip();
                if (!mid.isBlank()) rwByMat.put(mid, rec.deepCopy());
            }
            return new AuthorRewriteBaseline(
                    windowMats,
                    rwByMat,
                    medianOf(allViews),
                    medianOf(allLikes),
                    medianOf(allEng),
                    Math.max(allViews.size(), allEng.size())
            );
        });
    }

    private ObjectNode buildRewriteStatusNode(
            ObjectNode m,
            ObjectNode repostDecision,
            AuthorPreviewContext ctx,
            AuthorRewriteBaseline baseline,
            long nowMs
    ) {
        String materialId = m.path("id").asText("").strip();
        long pubAt = m.path("publishedAt").asLong(0L);
        boolean withinWindow = pubAt > 0L && nowMs - pubAt <= REWRITE_MAX_AGE_MS;
        boolean pureText = isPureTextRewriteCandidate(m);
        boolean highTraffic = isHighTrafficForAuthor(m, baseline.medViews(), baseline.medLikes(), baseline.medEng(), baseline.sampleCount());
        long eng = computeWeightedEngagement(m);
        double rankScore = computeRewriteRankScore(m, nowMs, baseline.medViews(), baseline.medEng());
        String repostStatus = repostDecision.path("persistedStatus").asText("");
        boolean alreadyHandledAsRepost = Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(repostStatus);

        ObjectNode persisted = baseline.rewriteRecordsByMaterialId().get(materialId);
        String persistedStatus = persisted == null ? "" : persisted.path("status").asText("");
        String publishedUrl = persisted == null ? "" : persisted.path("publishedTweetUrl").asText("");
        boolean isPublishedRewrite = "PUBLISHED".equals(persistedStatus);
        boolean isCandidate = isPublishedRewrite || (pureText && highTraffic && withinWindow && !alreadyHandledAsRepost);

        String ineligibleReason = "";
        if (!isCandidate) {
            if (!pureText) ineligibleReason = rewriteIneligibleReason(m);
            else if (!withinWindow) ineligibleReason = "超出 30 天洗稿窗口";
            else if (!highTraffic) ineligibleReason = "互动热度低于高流量洗稿阈值";
            else if (alreadyHandledAsRepost) ineligibleReason = "该帖已触发引用转发";
        }

        String cacheKey = buildRewritePreviewCacheKey(
                materialId, ctx.styleProfile(), ctx.preferencesText(),
                ctx.postPolishEnabled(), ctx.postPolishPrompt(),
                ctx.generationProvider(), ctx.generationModel());
        ObjectNode cachedEntry = rewritePreviewCache.get(cacheKey);
        if (cachedEntry != null && cachedEntry.has("rewrite")) {
            ObjectNode cachedRw = cachedEntry.path("rewrite").deepCopy();
            cachedRw.put("candidate", isCandidate)
                    .put("pureText", pureText)
                    .put("highTraffic", highTraffic)
                    .put("withinWindow", withinWindow)
                    .put("engagement", eng)
                    .put("rankScore", rankScore)
                    .put("rank", 0)
                    .put("ineligibleReason", ineligibleReason)
                    .put("cached", true);
            if (isPublishedRewrite) {
                cachedRw.put("persistedStatus", "PUBLISHED")
                        .put("publishedTweetUrl", publishedUrl)
                        .put("status", "PUBLISHED");
            }
            return cachedRw;
        }

        if (persisted != null && !persisted.path("rewrittenText").asText("").isBlank()) {
            boolean suitable = isPublishedRewrite || persisted.path("suitabilityScore").asInt(0) >= 68;
            return object()
                    .put("candidate", isCandidate)
                    .put("pureText", pureText)
                    .put("highTraffic", highTraffic)
                    .put("withinWindow", withinWindow)
                    .put("engagement", eng)
                    .put("rankScore", rankScore)
                    .put("rank", 0)
                    .put("ineligibleReason", ineligibleReason)
                    .put("evaluated", true)
                    .put("suitable", suitable)
                    .put("suitabilityScore", persisted.path("suitabilityScore").asInt(0))
                    .put("hookPattern", persisted.path("hookPattern").asText(""))
                    .put("coreInsight", persisted.path("summary").asText(""))
                    .put("reason", persisted.path("reason").asText(""))
                    .put("rewrittenText", persisted.path("rewrittenText").asText(""))
                    .put("publishedTweetUrl", publishedUrl)
                    .put("persistedStatus", persistedStatus)
                    .put("status", isPublishedRewrite ? "PUBLISHED" : ("SKIPPED".equals(persistedStatus) ? "SKIPPED" : "EVALUATED"))
                    .put("cached", false);
        }

        String status = isCandidate
                ? "READY"
                : ("SKIPPED".equals(persistedStatus) ? "SKIPPED" : "INELIGIBLE");
        return object()
                .put("candidate", isCandidate)
                .put("pureText", pureText)
                .put("highTraffic", highTraffic)
                .put("withinWindow", withinWindow)
                .put("engagement", eng)
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

    private void recordOwnerPublishAndScheduleNextSlot(long publishedAtMs) {
        long nextGapMs = store.tx(tx -> {
            ObjectNode prev = tx.get("meta", "owner_latest_tweet");
            long merged = prev == null ? publishedAtMs : Math.max(publishedAtMs, prev.path("latestTweetAt").asLong(0L));
            tx.put("meta", "owner_latest_tweet", object()
                    .put("username", "elidlicom")
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
                    if (!aid.isBlank()) targetIds.add(aid);
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
                String uname = src.path("username").asText("").replaceFirst("^@+", "");
                List<ObjectNode> list = new ArrayList<>();
                for (ObjectNode m : tx.all("material")) {
                    boolean same = (!sid.isBlank() && sid.equals(m.path("authorId").asText("")))
                            || (!uname.isBlank() && uname.equalsIgnoreCase(m.path("username").asText("").replaceFirst("^@+", "")));
                    if (!same) continue;
                    long pubAt = m.path("publishedAt").asLong(0L);
                    if (pubAt <= 0L || now - pubAt > MAX_FRESHNESS_AGE_MS) continue;
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
                processAuthorBatch(src, eligibleMaterials);
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
            ObjectNode owner = tx.get("meta", "owner");
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (owner == null || owner.path("accountId").asText("").isBlank()) {
                return new PatrolSnapshot("NO_OWNER", "", "elidlicom", settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }
            if (!settings.path("enabled").asBoolean(true)) {
                return new PatrolSnapshot("DISABLED", "", "elidlicom", settings, 0L, 0L, 0L, List.of(), List.of(), false, List.of());
            }
            String ownerAccountId = owner.path("accountId").asText("");
            String rawOwnerUser = owner.path("username").asText("").replaceFirst("^@+", "").strip();
            String ownerUsername = rawOwnerUser.isBlank() ? "elidlicom" : rawOwnerUser;

            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            LocalDate today = Instant.ofEpochMilli(nowMs).atZone(AssistantRetention.ZONE).toLocalDate();
            long todayStart = today.atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
            int publishedToday = 0;
            Map<String, Long> latestRepostByAuthorKey = new java.util.HashMap<>();
            Map<String, Long> latestRewriteByAuthorKey = new java.util.HashMap<>();
            for (ObjectNode rec : tx.all("auto_repost")) {
                String recStatus = rec.path("status").asText("");
                if (!rec.path("manual").asBoolean(false) && Set.of("PUBLISHED", "UNCERTAIN").contains(recStatus)) {
                    long recPubAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L));
                    if (recPubAt >= todayStart) publishedToday++;
                }
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0L)));
                    String aid = rec.path("authorId").asText("").strip();
                    String uname = rec.path("username").asText("").replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
                    if (!aid.isBlank() && recAt > 0L) latestRepostByAuthorKey.merge("id:" + aid, recAt, Math::max);
                    if (!uname.isBlank() && recAt > 0L) latestRepostByAuthorKey.merge("u:" + uname, recAt, Math::max);
                }
            }
            for (ObjectNode rec : tx.all("auto_rewrite")) {
                String recStatus = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "UNCERTAIN").contains(recStatus)) {
                    long recPubAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L));
                    if (recPubAt >= todayStart) publishedToday++;
                }
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    long recAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(rec.path("createdAt").asLong(0L)));
                    String aid = rec.path("authorId").asText("").strip();
                    String uname = rec.path("username").asText("").replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
                    if (!aid.isBlank() && recAt > 0L) latestRewriteByAuthorKey.merge("id:" + aid, recAt, Math::max);
                    if (!uname.isBlank() && recAt > 0L) latestRewriteByAuthorKey.merge("u:" + uname, recAt, Math::max);
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

            long localLatest = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, "elidlicom", "", nowMs);
            if (!"elidlicom".equalsIgnoreCase(ownerUsername)) {
                localLatest = Math.max(localLatest, resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUsername, "", nowMs));
            }
            ObjectNode observedNode = tx.get("meta", "owner_latest_tweet");
            long cachedLiveTs = observedNode == null ? 0L : observedNode.path("latestTweetAt").asLong(0L);
            long cachedCheckedAt = observedNode == null ? 0L : observedNode.path("checkedAt").asLong(0L);
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
        boolean liveCheckFailed = false;
        boolean recentlyCheckedLive = snapshot.cachedLiveCheckedAtMs() > 0L
                && nowMs - snapshot.cachedLiveCheckedAtMs() >= 0L
                && nowMs - snapshot.cachedLiveCheckedAtMs() < 5L * 60_000L;
        if (sources != null && !recentlyCheckedLive) {
            try {
                liveLatest = sources.fetchLatestAccountPostEpochMs("elidlicom", snapshot.ownerAccountId());
            } catch (Exception ex) {
                liveCheckFailed = true;
                log.warn("Patrol failed to fetch live @elidlicom timeline: {}", ex.getMessage());
            }
            if (liveLatest > 0L) {
                long observedTs = liveLatest;
                store.tx(tx -> {
                    ObjectNode prev = tx.get("meta", "owner_latest_tweet");
                    long merged = prev == null ? observedTs : Math.max(observedTs, prev.path("latestTweetAt").asLong(0L));
                    tx.put("meta", "owner_latest_tweet", object()
                            .put("username", "elidlicom")
                            .put("latestTweetAt", merged)
                            .put("checkedAt", clock.millis()));
                    return null;
                });
            }
        }

        long latestOwnerAt = Math.max(snapshot.localLatestOwnerTweetAtMs(), Math.max(0L, liveLatest));
        if (liveCheckFailed && latestOwnerAt <= 0L) {
            return object().put("status", "OWNER_CHECK_FAILED");
        }

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
            ObjectNode rpOnly = tryPatrolRepostSources(snapshot.unlockedRepostSources(), latestOwnerAt, jitterMinutes);
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
            ObjectNode rpResult = tryPatrolRepostSources(snapshot.unlockedRepostSources(), latestOwnerAt, jitterMinutes);
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
            ObjectNode rpFallback = tryPatrolRepostSources(snapshot.unlockedRepostSources(), latestOwnerAt, jitterMinutes);
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

    private ObjectNode tryPatrolRepostSources(List<ObjectNode> unlockedSources, long latestOwnerAt, int jitterMinutes) {
        if (unlockedSources == null || unlockedSources.isEmpty()) return null;
        // Pass 1: Prefer newly published content within the 48h freshness window
        for (ObjectNode source : unlockedSources) {
            List<ObjectNode> freshCandidates = loadRepostCandidatesForWindow(source, PATROL_MAX_FRESHNESS_AGE_MS, false);
            if (freshCandidates.isEmpty()) continue;
            String publishedMaterialId = processAuthorBatchInternal(source, freshCandidates, true, false);
            if (publishedMaterialId != null && !publishedMaterialId.isBlank()) {
                return object()
                        .put("status", "PUBLISHED")
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
            String publishedMaterialId = processAuthorBatchInternal(source, recentCandidates, true, true);
            if (publishedMaterialId != null && !publishedMaterialId.isBlank()) {
                return object()
                        .put("status", "PUBLISHED")
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
        String uname = source.path("username").asText("").replaceFirst("^@+", "").strip();
        long scanNow = clock.millis();
        int maxItems = recentArchiveFallback ? 12 : 8;
        return store.tx(tx -> {
            List<ObjectNode> local = new ArrayList<>();
            for (ObjectNode m : tx.all("material")) {
                boolean same = (!aid.isBlank() && aid.equals(m.path("authorId").asText("")))
                        || (!uname.isBlank() && uname.equalsIgnoreCase(m.path("username").asText("").replaceFirst("^@+", "")));
                if (!same) continue;
                long pubAt = m.path("publishedAt").asLong(0L);
                if (pubAt <= 0L || scanNow - pubAt > windowMs) continue;
                local.add(m.deepCopy());
            }
            local.sort(Comparator.comparingLong((ObjectNode m) -> m.path("publishedAt").asLong(0L)).reversed());
            return local.stream().limit(maxItems).toList();
        });
    }

    void processCandidate(ObjectNode sourceSnapshot, ObjectNode materialSnapshot) {
        if (sourceSnapshot == null || materialSnapshot == null) return;
        processAuthorBatch(sourceSnapshot, List.of(materialSnapshot));
    }

    void processAuthorBatch(ObjectNode sourceSnapshot, List<ObjectNode> materialSnapshots) {
        processAuthorBatchInternal(sourceSnapshot, materialSnapshots, false, false);
    }

    private String processAuthorBatchInternal(ObjectNode sourceSnapshot, List<ObjectNode> materialSnapshots, boolean patrolMode) {
        return processAuthorBatchInternal(sourceSnapshot, materialSnapshots, patrolMode, false);
    }

    private String processAuthorBatchInternal(
            ObjectNode sourceSnapshot,
            List<ObjectNode> materialSnapshots,
            boolean patrolMode,
            boolean recentArchiveFallback
    ) {
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
            ObjectNode owner = tx.get("meta", "owner");
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (owner == null || owner.path("accountId").asText("").isBlank()
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
                    if (patrolMode || recentArchiveFallback) {
                        String recStatus = existingRec.path("status").asText("");
                        boolean patrolRetryable = Set.of(
                                "SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS", "JUDGE_FAILED"
                        ).contains(recStatus);
                        if (!patrolRetryable) continue;
                    } else {
                        boolean canUnlockDeferred = existingRec.path("deferredPendingEval").asBoolean(false)
                                && now >= existingRec.path("deferredUntilMs").asLong(0L);
                        if (!canUnlockDeferred) continue;
                    }
                }
                ObjectNode liveMat = tx.get("material", mid);
                if (liveMat == null) continue;
                String topicId = AssistantTopics.topicOf(tx, mid);
                eligible.add(new ClaimedItem(mid, topicId, liveMat));
                if (recentArchiveFallback && eligible.size() >= 3) {
                    break;
                }
            }
            if (eligible.isEmpty()) return null;

            // Check duplicate topics & daily limit first for non-CD permanent guards, THEN check Dual CD BEFORE any LLM call!
            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            LocalDate today = Instant.ofEpochMilli(now).atZone(AssistantRetention.ZONE).toLocalDate();
            long todayStart = today.atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
            int publishedToday = 0;
            java.util.Set<String> activeTopics = new java.util.HashSet<>();
            for (ObjectNode rec : tx.all("auto_repost")) {
                String recStatus = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    String tid = rec.path("topicId").asText("");
                    if (!tid.isBlank()) activeTopics.add(tid);
                }
                if (!rec.path("manual").asBoolean(false) && Set.of("PUBLISHED", "UNCERTAIN").contains(recStatus)) {
                    long recPubAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L));
                    if (recPubAt >= todayStart) publishedToday++;
                }
            }
            for (ObjectNode rec : tx.all("auto_rewrite")) {
                String recStatus = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    String tid = rec.path("topicId").asText("");
                    if (!tid.isBlank()) activeTopics.add(tid);
                }
                if (Set.of("PUBLISHED", "UNCERTAIN").contains(recStatus)) {
                    long recPubAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L));
                    if (recPubAt >= todayStart) publishedToday++;
                }
            }

            List<ClaimedItem> nonDupItems = new ArrayList<>();
            for (ClaimedItem item : eligible) {
                if (!item.topicId().isBlank() && activeTopics.contains(item.topicId())) {
                    tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now)
                            .put("status", "SKIPPED_DUPLICATE")
                            .put("deferredPendingEval", false)
                            .put("reason", "同一事件选题已触发过自动转发"));
                } else {
                    nonDupItems.add(item);
                }
            }
            if (nonDupItems.isEmpty()) return null;

            DualCooldown dualCd = computeLiveDualCooldown(
                    tx, settings, ownerAccountId, ownerUsername, candidateAuthorId, candidateUsername, "", now);
            if (dualCd.globalCdActive()) {
                long wakeAtMs = dualCd.globalReadyAtMs();
                for (ClaimedItem item : nonDupItems) {
                    tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now)
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
                        for (ClaimedItem item : nonDupItems) {
                            tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now)
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
                for (ClaimedItem item : nonDupItems) {
                    tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now)
                            .put("status", "SKIPPED_LIMIT")
                            .put("deferredPendingEval", false)
                            .put("reason", "已达单日自动引用转发保护上限（" + dailyLimit + " 条）"));
                }
                return null;
            }

            List<ClaimedItem> evalItems = nonDupItems;
            if (dualCd.authorCdActive()) {
                if (patrolMode || recentArchiveFallback) {
                    // Strictly keep 16h same-author lock during interval patrol and recent-content fallback
                    return null;
                }
                evalItems = new ArrayList<>();
                for (ClaimedItem item : nonDupItems) {
                    if (mightBeDirectPassCandidate(item.material())) {
                        evalItems.add(item);
                    } else {
                        tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now)
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
                tx.put("auto_repost", item.materialId(), baseRecord(liveSource, item.material(), item.topicId(), now)
                        .put("status", "EVALUATING")
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
                processAuthorBatchInternal(sourceSnapshot, materialSnapshots, true, false);
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
                    ObjectNode rec = tx.get("auto_repost", item.materialId());
                    if (rec != null && "EVALUATING".equals(rec.path("status").asText(""))) {
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

    private boolean wasDispatchedOrPublished(String materialId) {
        if (materialId == null || materialId.isBlank()) return false;
        return store.tx(tx -> {
            ObjectNode rec = tx.get("auto_repost", materialId);
            return rec != null && Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(rec.path("status").asText(""));
        });
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
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec != null && "EVALUATING".equals(rec.path("status").asText())) {
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
                ObjectNode rec = tx.get("auto_repost", materialId);
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

    private static final java.util.regex.Pattern STATUS_URL_ID_PATTERN = java.util.regex.Pattern.compile("/status/([0-9]{2,20})");

    private static String extractStatusIdFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        var m = STATUS_URL_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
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

    public ObjectNode publishManualQuote(String accountId, String username, String rawSourceKey, ObjectNode body) {
        if (!store.isOwner(accountId)) {
            throw new XDataProviderException(403, "仅限内容助手所有者使用");
        }
        if (oauthService == null || publishClient == null) {
            throw new XDataProviderException(503, "引用发布服务未启用");
        }
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
        String pubKey = "manual-quote-" + clientRequestId;
        String sourceKey = rawSourceKey == null ? "" : rawSourceKey.replaceFirst("^@+", "").strip();

        record PreparedManualQuote(
                ObjectNode existingPublished,
                String quoteTweetId,
                String resolvedMaterialId,
                String topicId,
                String sourceUrl,
                String requestDigest
        ) { }

        PreparedManualQuote prepared = store.tx(tx -> {
            ObjectNode mat = materialId.isBlank() ? null : tx.get("material", materialId);
            String quoteTweetId = resolveMaterialQuoteTweetId(tx, materialId, null);
            if (quoteTweetId == null) {
                quoteTweetId = extractStatusIdFromUrl(requestedSourceUrl);
            }
            if (quoteTweetId == null) {
                throw new IllegalArgumentException("缺少有效原帖推文 ID，无法引用转发");
            }
            String resolvedMatId = !materialId.isBlank() ? materialId : quoteTweetId;
            String topicId = AssistantTopics.topicOf(tx, resolvedMatId);
            String resolvedUrl = !requestedSourceUrl.isBlank()
                    ? requestedSourceUrl
                    : (mat != null && !mat.path("sourceUrl").asText("").isBlank()
                            ? mat.path("sourceUrl").asText("")
                            : (!sourceKey.isBlank()
                                    ? ("https://x.com/" + sourceKey + "/status/" + quoteTweetId)
                                    : ("https://x.com/i/status/" + quoteTweetId)));

            String requestDigest = AssistantWorkflow.digest(object()
                    .put("materialId", resolvedMatId)
                    .put("quoteTweetId", quoteTweetId)
                    .put("commentText", commentText));
            ObjectNode existing = tx.get("publication", pubKey);
            if (existing != null) {
                if (!requestDigest.equals(existing.path("requestDigest").asText(""))) {
                    throw new XDataProviderException(409, "同一请求标识已用于其他引用内容");
                }
                String existingStatus = existing.path("status").asText("");
                if ("PUBLISHED".equals(existingStatus)) {
                    return new PreparedManualQuote(existing, quoteTweetId, resolvedMatId, topicId, resolvedUrl, requestDigest);
                }
                if ("SENDING".equals(existingStatus) || "UNCERTAIN".equals(existingStatus)) {
                    throw new XDataProviderException(409, "该引用发布正在处理或回执待确认，请勿重复提交");
                }
            }

            tx.put("publication", pubKey, object()
                    .put("requestDigest", requestDigest)
                    .put("status", "SENDING")
                    .put("source", "MANUAL_QUOTE_REPOST")
                    .put("topicId", topicId)
                    .put("materialId", resolvedMatId)
                    .put("quoteTweetId", quoteTweetId)
                    .put("quotedTweetId", quoteTweetId)
                    .put("text", commentText));
            return new PreparedManualQuote(null, quoteTweetId, resolvedMatId, topicId, resolvedUrl, requestDigest);
        });

        if (prepared.existingPublished() != null) {
            return prepared.existingPublished();
        }

        final String accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, accountId,
                () -> oauthService.getValidWriteAccessToken(accountId, false));

        final String publishedTweetId;
        try {
            publishedTweetId = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, accountId,
                    () -> XPublishClient.withQuoteTweetUrl(
                            prepared.sourceUrl(),
                            () -> publishClient.createTweet(accessToken, commentText, List.of(), prepared.quoteTweetId())));
        } catch (XPublishException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("publication", pubKey);
                if (rec != null) {
                    tx.put("publication", pubKey, rec.deepCopy()
                            .put("status", ex.isDeliveryUncertain() ? "UNCERTAIN" : "FAILED")
                            .put("error", safeError(ex)));
                }
                return null;
            });
            throw ex;
        }

        long doneMs = clock.millis();
        String uname = (username == null || username.isBlank()) ? "i" : username;
        String publishedUrl = "https://x.com/" + uname + "/status/" + publishedTweetId;

        ObjectNode result = store.tx(tx -> {
            ObjectNode pub = tx.put("publication", pubKey, object()
                    .put("requestDigest", prepared.requestDigest())
                    .put("status", "PUBLISHED")
                    .put("source", "MANUAL_QUOTE_REPOST")
                    .put("topicId", prepared.topicId())
                    .put("materialId", prepared.resolvedMaterialId())
                    .put("quoteTweetId", prepared.quoteTweetId())
                    .put("quotedTweetId", prepared.quoteTweetId())
                    .put("text", commentText)
                    .put("publishedTweetId", publishedTweetId)
                    .put("publishedTweetUrl", publishedUrl)
                    .put("publishedAt", doneMs));

            ObjectNode existingRepost = tx.get("auto_repost", prepared.resolvedMaterialId());
            ObjectNode mat = tx.get("material", prepared.resolvedMaterialId());
            ObjectNode nextRepost = existingRepost != null
                    ? existingRepost.deepCopy()
                    : object()
                            .put("materialId", prepared.resolvedMaterialId())
                            .put("topicId", prepared.topicId())
                            .put("authorId", mat != null ? mat.path("authorId").asText("") : "")
                            .put("username", mat != null ? mat.path("username").asText(sourceKey) : sourceKey)
                            .put("authorName", mat != null ? mat.path("authorName").asText("") : "")
                            .put("sourceUrl", prepared.sourceUrl())
                            .put("createdAt", doneMs);
            nextRepost.put("status", "PUBLISHED")
                    .put("manual", true)
                    .put("commentText", commentText)
                    .put("quoteTweetId", prepared.quoteTweetId())
                    .put("quoteVoiceVersion", 7)
                    .put("publishedTweetId", publishedTweetId)
                    .put("publishedTweetUrl", publishedUrl)
                    .put("publishedAt", doneMs)
                    .put("error", "");
            tx.put("auto_repost", prepared.resolvedMaterialId(), nextRepost);
            return pub;
        });
        clearPreviewCache();
        return result;
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
        final String accessToken;
        try {
            accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId,
                    () -> oauthService.getValidWriteAccessToken(ownerId, false));
        } catch (RuntimeException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec != null) {
                    ObjectNode next = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
                    tx.put("auto_repost", materialId, next
                            .put("status", "AUTH_REQUIRED")
                            .put("commentText", commentText)
                            .put("error", safeError(ex)));
                }
                return null;
            });
            return;
        }

        // Fail-closed checkpoint before POST /2/tweets so uncertain responses are never double-sent.
        record QuoteDispatchTarget(String quoteTweetId, String quoteTweetUrl) { }
        QuoteDispatchTarget dispatchTarget = store.tx(tx -> {
            ObjectNode rec = tx.get("auto_repost", materialId);
            if (rec == null || !"EVALUATING".equals(rec.path("status").asText())) return null;
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
            if (enforceQuietWindow && isInAutoRepostQuietWindow(dispatchMs)) {
                ObjectNode skipped = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
                tx.put("auto_repost", materialId, skipped
                        .put("status", "SKIPPED_QUIET_HOURS")
                        .put("commentText", commentText)
                        .put("reason", quietWindowReason()));
                return null;
            }
            boolean exemptAuthorCd = !strictSixteenHourAuthorLock && (judgment != null
                    ? PiAgentViralJudge.isDirectPassCategory(judgment.category())
                    : PiAgentViralJudge.isDirectPassCategory(rec.path("category").asText("")));
            ObjectNode recentSameAuthor = exemptAuthorCd
                    ? null
                    : findRecentSameAuthorRepost(
                            tx.all("auto_repost"),
                            rec.path("authorId").asText(""),
                            rec.path("username").asText(""),
                            materialId,
                            dispatchMs);
            if (recentSameAuthor != null) {
                ObjectNode skipped = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
                tx.put("auto_repost", materialId, skipped
                        .put("status", "SKIPPED_AUTHOR_COOLDOWN")
                        .put("commentText", commentText)
                        .put("reason", "16 小时内已转发过 @" + rec.path("username").asText("").replaceFirst("^@+", "") + "，避免连续转发同一人"));
                return null;
            }
            ObjectNode settings = AssistantWorkflow.settings(tx);
            long latestOwnerTweetAt = resolveLatestOwnerTweetEpochMs(tx, ownerId, ownerUsername, materialId, dispatchMs);
            long requiredOwnerGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerTweetAt);
            if (requiredOwnerGapMs > 0L && latestOwnerTweetAt > 0L
                    && dispatchMs - latestOwnerTweetAt >= 0L && dispatchMs - latestOwnerTweetAt < requiredOwnerGapMs) {
                int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerTweetAt);
                long remMin = Math.max(1L, (requiredOwnerGapMs - (dispatchMs - latestOwnerTweetAt) + 59_999L) / 60_000L);
                ObjectNode skipped = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
                tx.put("auto_repost", materialId, skipped
                        .put("status", "SKIPPED_COOLDOWN")
                        .put("commentText", commentText)
                        .put("reason", "距我账号上一条推文不足 1小时+" + jitterMin + "分钟（还差约 " + remMin + " 分钟）"));
                return null;
            }
            ObjectNode next = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
            tx.put("auto_repost", materialId, next
                    .put("status", "SENDING")
                    .put("commentText", commentText)
                    .put("quoteTweetId", resolvedQuoteId)
                    .put("dispatchedAt", dispatchMs));
            return new QuoteDispatchTarget(resolvedQuoteId, resolvedQuoteUrl);
        });
        if (dispatchTarget == null) return;
        String quoteTweetId = dispatchTarget.quoteTweetId();

        try {
            String publishedTweetId = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId,
                    () -> XPublishClient.withQuoteTweetUrl(
                            dispatchTarget.quoteTweetUrl(),
                            () -> publishClient.createTweet(accessToken, commentText, List.of(), quoteTweetId)));
            if (paidGate != null) {
                paidGate.markSucceeded("auto-repost:" + materialId);
            }
            long doneMs = clock.millis();
            String uname = ownerUsername.isBlank() ? "i" : ownerUsername;
            String publishedUrl = "https://x.com/" + uname + "/status/" + publishedTweetId;
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec != null) {
                    tx.put("auto_repost", materialId, rec.deepCopy()
                            .put("status", "PUBLISHED")
                            .put("quoteTweetId", quoteTweetId)
                            .put("publishedTweetId", publishedTweetId)
                            .put("publishedTweetUrl", publishedUrl)
                            .put("publishedAt", doneMs)
                            .put("error", ""));
                }
                tx.put("publication", "auto-repost-" + materialId, object()
                        .put("status", "PUBLISHED")
                        .put("source", "AUTO_QUOTE_REPOST")
                        .put("topicId", topicId)
                        .put("materialId", materialId)
                        .put("quoteTweetId", quoteTweetId)
                        .put("quotedTweetId", quoteTweetId)
                        .put("text", commentText)
                        .put("publishedTweetId", publishedTweetId)
                        .put("publishedTweetUrl", publishedUrl)
                        .put("publishedAt", doneMs));
                return null;
            });
            recordOwnerPublishAndScheduleNextSlot(doneMs);
        } catch (XPublishException ex) {
            String terminalStatus = ex.isDeliveryUncertain() ? "UNCERTAIN" : "FAILED";
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec != null) {
                    tx.put("auto_repost", materialId, rec.deepCopy()
                            .put("status", terminalStatus)
                            .put("error", safeError(ex)));
                }
                return null;
            });
            if (!ex.isDeliveryUncertain() && paidGate != null) {
                paidGate.scheduleIdempotentRetry(
                        "auto-repost:" + materialId,
                        "x_tweet_create",
                        com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                        ownerId,
                        safeError(ex),
                        () -> retry(materialId)
                );
            }
        } catch (RuntimeException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_repost", materialId);
                if (rec != null) {
                    tx.put("auto_repost", materialId, rec.deepCopy()
                            .put("status", "FAILED")
                            .put("error", safeError(ex)));
                }
                return null;
            });
            if (paidGate != null) {
                paidGate.scheduleIdempotentRetry(
                        "auto-repost:" + materialId,
                        "x_tweet_create",
                        com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                        ownerId,
                        safeError(ex),
                        () -> retry(materialId)
                );
            }
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

    private static ObjectNode baseRecord(ObjectNode source, ObjectNode material, String topicId, long now) {
        return object()
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
        if (m.path("hasMedia").asBoolean(false)) return "";
        if (!m.path("quotedId").asText("").isBlank() || !m.path("quotedText").asText("").isBlank()) return "";
        if (!m.path("articleUrl").asText("").isBlank()) return "";
        String textStatus = m.path("textStatus").asText("");
        if (Set.of("MEDIA_ONLY", "MEDIA_URL_ONLY", "NO_ANALYZABLE_TEXT", "QUOTE_TEXT_MISSING", "QUOTE_SOURCE_MISSING").contains(textStatus)) {
            return "";
        }
        if (!AssistantTopics.links(m).isEmpty()) return "";
        String raw = !m.path("articleText").asText("").isBlank()
                ? m.path("articleText").asText("").strip()
                : m.path("text").asText("").strip();
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

    static String rewriteIneligibleReason(ObjectNode m) {
        if (m == null) return "素材为空";
        if (m.path("hasMedia").asBoolean(false)) return "含图片或视频媒体";
        String kind = m.path("kind").asText("POST");
        if (!"POST".equalsIgnoreCase(kind)
                || !m.path("quotedId").asText("").isBlank()
                || !m.path("quotedText").asText("").isBlank()) {
            return "含引用或非原创主帖";
        }
        String textStatus = m.path("textStatus").asText("");
        if (Set.of("MEDIA_ONLY", "MEDIA_URL_ONLY").contains(textStatus)) return "仅媒体无纯文本";
        if (!m.path("articleUrl").asText("").isBlank() || !AssistantTopics.links(m).isEmpty()) return "含外部链接";
        String raw = !m.path("articleText").asText("").isBlank()
                ? m.path("articleText").asText("").strip()
                : m.path("text").asText("").strip();
        if (EMBEDDED_URL_PATTERN.matcher(raw).find()) return "正文含链接";
        if (THREAD_OR_VISUAL_DEPENDENCY_PATTERN.matcher(raw).find()) return "依赖看图或串帖上下文";
        return "纯文本过短或结构不足以独立成篇";
    }

    /**
     * Ranks pure-text rewrite candidates by recency first combined with author-relative heat.
     */
    static double computeRewriteRankScore(ObjectNode m, long nowMs, long medViews, long medEng) {
        if (m == null) return 0.0;
        long pubAt = m.path("publishedAt").asLong(0L);
        double ageHours = pubAt > 0L ? Math.max(0.0, (nowMs - pubAt) / 3_600_000.0) : 720.0;
        double recencyBoost = 48.0 / (1.0 + ageHours / 24.0);
        long eng = computeWeightedEngagement(m);
        long views = Math.max(0L, m.path("views").asLong(0L));
        double ratioBoost = medEng > 0L ? Math.min(6.0, (double) eng / medEng) : 1.5;
        double viewBoost = medViews > 0L ? Math.min(6.0, (double) views / medViews) : 1.5;
        double raw = recencyBoost + Math.log1p(eng) * 10.0 + Math.log1p(views) * 3.5 + (ratioBoost + viewBoost) * 6.0;
        return Math.round(raw * 10.0) / 10.0;
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
            String materialId,
            String topicId,
            ObjectNode material,
            String pureText,
            double rankScore,
            long medianViews,
            long medianLikes
    ) { }

    private ObjectNode tryPatrolRewriteSources(
            List<ObjectNode> orderedRewriteSources,
            String ownerId,
            String ownerUsername,
            long latestOwnerAt,
            int jitterMinutes
    ) {
        if (orderedRewriteSources == null || orderedRewriteSources.isEmpty()) return null;
        for (ObjectNode source : orderedRewriteSources) {
            String publishedMatId = processAuthorRewriteOnce(source, ownerId, ownerUsername);
            if (publishedMatId != null && !publishedMatId.isBlank()) {
                return object()
                        .put("status", "PUBLISHED")
                        .put("mode", "REWRITE")
                        .put("materialId", publishedMatId)
                        .put("sourceUsername", source.path("username").asText(""))
                        .put("latestOwnerTweetAt", latestOwnerAt)
                        .put("jitterMinutes", jitterMinutes);
            }
        }
        return null;
    }

    String processAuthorRewriteOnce(ObjectNode sourceSnapshot, String ownerId, String ownerUsername) {
        if (sourceSnapshot == null) return "";
        long now = clock.millis();
        if (isInAutoRepostQuietWindow(now)) {
            scheduleQuietWindowEndWakeup(now);
            return "";
        }
        if (sources != null) {
            sources.syncSourceMaterialsForRewrite(sourceSnapshot, REWRITE_MAX_AGE_MS);
        }

        record RewriteBatchContext(
                ObjectNode liveSource,
                List<RankedRewriteCandidate> topCandidates,
                String styleProfile,
                String preferencesText,
                String postPolishPrompt,
                String generationProvider,
                String generationModel
        ) { }

        RewriteBatchContext ctx = store.tx(tx -> {
            String sourceId = sourceSnapshot.path("authorId").asText(sourceSnapshot.path("id").asText("")).strip();
            ObjectNode liveSource = tx.get("source", sourceId);
            if (liveSource == null || !"ACTIVE".equals(liveSource.path("status").asText(""))
                    || !liveSource.path("autoRewriteEnabled").asBoolean(false)) {
                return null;
            }
            ObjectNode settings = AssistantWorkflow.settings(tx);
            if (!settings.path("enabled").asBoolean(true)) return null;

            long latestOwner = resolveLatestOwnerTweetEpochMs(tx, ownerId, ownerUsername, "", now);
            long requiredGap = resolveEffectiveOwnerGapMs(settings, latestOwner);
            if (requiredGap > 0L && latestOwner > 0L && now - latestOwner >= 0L && now - latestOwner < requiredGap) {
                return null;
            }

            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            LocalDate today = Instant.ofEpochMilli(now).atZone(AssistantRetention.ZONE).toLocalDate();
            long todayStart = today.atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
            int publishedToday = 0;
            Set<String> usedTopics = new java.util.HashSet<>();
            Set<String> usedMaterials = new java.util.HashSet<>();
            for (ObjectNode rec : tx.all("auto_repost")) {
                String st = rec.path("status").asText("");
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(st)) {
                    usedMaterials.add(rec.path("materialId").asText(rec.path("id").asText("")));
                    String tid = rec.path("topicId").asText("");
                    if (!tid.isBlank()) usedTopics.add(tid);
                }
                if (Set.of("PUBLISHED", "UNCERTAIN").contains(st)
                        && rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L)) >= todayStart) {
                    publishedToday++;
                }
            }
            for (ObjectNode rec : tx.all("auto_rewrite")) {
                String st = rec.path("status").asText("");
                String mid = rec.path("materialId").asText(rec.path("id").asText(""));
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN", "SKIPPED", "SKIPPED_DUPLICATE").contains(st)) {
                    usedMaterials.add(mid);
                }
                if (Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(st)) {
                    String tid = rec.path("topicId").asText("");
                    if (!tid.isBlank()) usedTopics.add(tid);
                }
                if (Set.of("PUBLISHED", "UNCERTAIN").contains(st)
                        && rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0L)) >= todayStart) {
                    publishedToday++;
                }
            }
            if (publishedToday >= dailyLimit) return null;

            String aid = liveSource.path("authorId").asText(sourceId).strip();
            String uname = liveSource.path("username").asText("").replaceFirst("^@+", "").strip();
            List<ObjectNode> authorMats = new ArrayList<>();
            List<Long> allViews = new ArrayList<>();
            List<Long> allLikes = new ArrayList<>();
            List<Long> allEng = new ArrayList<>();
            for (ObjectNode m : tx.all("material")) {
                boolean same = (!aid.isBlank() && aid.equals(m.path("authorId").asText("")))
                        || (!uname.isBlank() && uname.equalsIgnoreCase(m.path("username").asText("").replaceFirst("^@+", "")));
                if (!same) continue;
                long pubAt = m.path("publishedAt").asLong(0L);
                if (pubAt <= 0L || now - pubAt > REWRITE_MAX_AGE_MS) continue;
                authorMats.add(m);
                long v = m.path("views").asLong(0L);
                long l = m.path("likes").asLong(0L);
                long e = computeWeightedEngagement(m);
                if (v > 0L) allViews.add(v);
                if (l > 0L) allLikes.add(l);
                if (e > 0L) allEng.add(e);
            }
            if (authorMats.isEmpty()) return null;

            long medViews = medianOf(allViews);
            long medLikes = medianOf(allLikes);
            long medEng = medianOf(allEng);
            int sampleCount = Math.max(allViews.size(), allEng.size());

            List<RankedRewriteCandidate> ranked = new ArrayList<>();
            for (ObjectNode m : authorMats) {
                String mid = m.path("id").asText("").strip();
                if (mid.isBlank() || usedMaterials.contains(mid)) continue;
                if (!isPureTextRewriteCandidate(m)) continue;
                if (!isHighTrafficForAuthor(m, medViews, medLikes, medEng, sampleCount)) continue;
                String tid = AssistantTopics.topicOf(tx, mid);
                if (!tid.isBlank() && usedTopics.contains(tid)) continue;
                String pureText = extractPureRewriteSourceText(m);
                double score = computeRewriteRankScore(m, now, medViews, medEng);
                ranked.add(new RankedRewriteCandidate(mid, tid, m.deepCopy(), pureText, score, medViews, medLikes));
            }
            if (ranked.isEmpty()) return null;
            ranked.sort(Comparator.comparingDouble(RankedRewriteCandidate::rankScore).reversed());

            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            String polishPrompt = AssistantWorkflow.isPostPolishEnabled(settings)
                    ? AssistantWorkflow.resolvePostPolishPrompt(settings)
                    : "";
            var repostMod = settings.path("moduleConfigs").path("repost");
            String prov = repostMod.path("provider").asText("").strip();
            String model = repostMod.path("model").asText("").strip();
            return new RewriteBatchContext(
                    liveSource.deepCopy(),
                    ranked.stream().limit(3).toList(),
                    composeOwnerQuoteVoiceProfile(tx),
                    prefs.path("text").asText(""),
                    polishPrompt,
                    prov,
                    model
            );
        });

        if (ctx == null || ctx.topCandidates().isEmpty()) return "";

        for (RankedRewriteCandidate cand : ctx.topCandidates()) {
            long claimAt = clock.millis();
            if (isInAutoRepostQuietWindow(claimAt)) {
                scheduleQuietWindowEndWakeup(claimAt);
                return "";
            }
            boolean claimed = store.tx(tx -> {
                ObjectNode existing = tx.get("auto_rewrite", cand.materialId());
                if (existing != null && Set.of("PUBLISHED", "SENDING", "UNCERTAIN", "SKIPPED").contains(existing.path("status").asText(""))) {
                    return false;
                }
                tx.put("auto_rewrite", cand.materialId(), baseRecord(ctx.liveSource(), cand.material(), cand.topicId(), claimAt)
                        .put("autoKind", "REWRITE")
                        .put("mode", "REWRITE")
                        .put("status", "EVALUATING"));
                return true;
            });
            if (!claimed) continue;

            String authorUname = cand.material().path("username").asText(ctx.liveSource().path("username").asText(""));
            String authorBio = resolveAuthorBio(ctx.liveSource(), List.of(cand.material()));
            List<String> stances = store.tx(tx -> OwnerQuoteStyleDistiller.findRelevantOwnerStances(
                    tx, authorUname, authorBio, cand.pureText(), "", 4));

            final PiAgentViralJudge.RewriteJudgment rwJudgment;
            try {
                rwJudgment = judge.evaluateAndWriteRewrite(new PiAgentViralJudge.RewriteInput(
                        authorUname,
                        cand.material().path("authorName").asText(ctx.liveSource().path("displayName").asText("")),
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
                    ObjectNode rec = tx.get("auto_rewrite", cand.materialId());
                    if (rec != null) {
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
                    ObjectNode rec = tx.get("auto_rewrite", cand.materialId());
                    if (rec != null) {
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
                    true
            );
            if (published) {
                return cand.materialId();
            }
        }
        return "";
    }

    private ObjectNode retryRewrite(String materialId) {
        record RewriteRetryPlan(
                ObjectNode updated,
                boolean reuseText,
                String existingText,
                String ownerId,
                String ownerUsername,
                String topicId,
                ObjectNode source,
                ObjectNode material
        ) { }

        RewriteRetryPlan plan = store.tx(tx -> {
            ObjectNode existing = tx.get("auto_rewrite", materialId);
            if (existing == null) throw new XDataProviderException(404, "洗稿记录不存在");
            String status = existing.path("status").asText("");
            if (!RETRYABLE_STATUSES.contains(status)) {
                throw new XDataProviderException(409, "当前洗稿状态不允许重发，请先核实线上回执");
            }
            ObjectNode owner = tx.get("meta", "owner");
            if (owner == null || owner.path("accountId").asText("").isBlank()) {
                throw new XDataProviderException(400, "缺少内容助手所有者信息");
            }
            String ownerAccountId = owner.path("accountId").asText("");
            String ownerUsername = owner.path("username").asText("i");
            long nowMs = clock.millis();
            ObjectNode settings = AssistantWorkflow.settings(tx);
            long latestOwnerAt = resolveLatestOwnerTweetEpochMs(tx, ownerAccountId, ownerUsername, materialId, nowMs);
            long requiredGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerAt);
            if (requiredGapMs > 0L && latestOwnerAt > 0L && nowMs - latestOwnerAt >= 0L && nowMs - latestOwnerAt < requiredGapMs) {
                int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerAt);
                long remMin = Math.max(1L, (requiredGapMs - (nowMs - latestOwnerAt) + 59_999L) / 60_000L);
                throw new XDataProviderException(409, "全局时间锁冷却中（距上一条推文未满 1小时+" + jitterMin + "分钟，还差约 " + remMin + " 分钟）");
            }
            String existingText = existing.path("rewrittenText").asText("").strip();
            boolean reuse = Set.of("AUTH_REQUIRED", "FAILED").contains(status) && !existingText.isBlank();
            ObjectNode liveMat = tx.get("material", materialId);
            String aid = existing.path("authorId").asText(liveMat == null ? "" : liveMat.path("authorId").asText(""));
            ObjectNode liveSrc = aid.isBlank() ? null : tx.get("source", aid);
            if (liveSrc == null) {
                liveSrc = object().put("id", aid).put("authorId", aid)
                        .put("username", existing.path("username").asText(""))
                        .put("autoRewriteEnabled", true);
            }
            ObjectNode updated = tx.put("auto_rewrite", materialId, existing.deepCopy()
                    .put("status", "EVALUATING")
                    .put("error", ""));
            return new RewriteRetryPlan(
                    updated, reuse, existingText, ownerAccountId, ownerUsername,
                    existing.path("topicId").asText(""), liveSrc.deepCopy(),
                    liveMat == null ? null : liveMat.deepCopy()
            );
        });

        executor.submit(() -> {
            try {
                if (plan.reuseText()) {
                    dispatchRewriteAndPublish(materialId, plan.ownerId(), plan.ownerUsername(),
                            plan.topicId(), plan.existingText(), null, clock.millis(), false);
                } else {
                    processAuthorRewriteOnce(plan.source(), plan.ownerId(), plan.ownerUsername());
                }
            } catch (Exception ex) {
                log.warn("Auto-rewrite retry failed for {}: {}", materialId, ex.getMessage());
            }
        });
        return plan.updated();
    }

    private boolean dispatchRewriteAndPublish(
            String materialId,
            String ownerId,
            String ownerUsername,
            String topicId,
            String rewrittenText,
            PiAgentViralJudge.RewriteJudgment judgment,
            long evaluatedAt,
            boolean enforceQuietWindow
    ) {
        final String accessToken;
        try {
            accessToken = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId,
                    () -> oauthService.getValidWriteAccessToken(ownerId, false));
        } catch (RuntimeException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_rewrite", materialId);
                if (rec != null) {
                    ObjectNode next = applyRewriteJudgment(rec.deepCopy(), judgment, evaluatedAt, rewrittenText);
                    tx.put("auto_rewrite", materialId, next
                            .put("status", "AUTH_REQUIRED")
                            .put("error", safeError(ex)));
                }
                return null;
            });
            return false;
        }

        boolean readyToSend = store.tx(tx -> {
            ObjectNode rec = tx.get("auto_rewrite", materialId);
            if (rec == null || !"EVALUATING".equals(rec.path("status").asText(""))) return false;
            long dispatchMs = clock.millis();
            if (enforceQuietWindow && isInAutoRepostQuietWindow(dispatchMs)) {
                ObjectNode skipped = applyRewriteJudgment(rec.deepCopy(), judgment, evaluatedAt, rewrittenText);
                tx.put("auto_rewrite", materialId, skipped
                        .put("status", "SKIPPED_QUIET_HOURS")
                        .put("reason", quietWindowReason()));
                return false;
            }
            ObjectNode settings = AssistantWorkflow.settings(tx);
            long latestOwnerTweetAt = resolveLatestOwnerTweetEpochMs(tx, ownerId, ownerUsername, materialId, dispatchMs);
            long requiredOwnerGapMs = resolveEffectiveOwnerGapMs(settings, latestOwnerTweetAt);
            if (requiredOwnerGapMs > 0L && latestOwnerTweetAt > 0L
                    && dispatchMs - latestOwnerTweetAt >= 0L && dispatchMs - latestOwnerTweetAt < requiredOwnerGapMs) {
                int jitterMin = resolveOwnerTweetJitterMinutes(latestOwnerTweetAt);
                long remMin = Math.max(1L, (requiredOwnerGapMs - (dispatchMs - latestOwnerTweetAt) + 59_999L) / 60_000L);
                ObjectNode skipped = applyRewriteJudgment(rec.deepCopy(), judgment, evaluatedAt, rewrittenText);
                tx.put("auto_rewrite", materialId, skipped
                        .put("status", "SKIPPED_COOLDOWN")
                        .put("reason", "距我账号上一条推文不足 1小时+" + jitterMin + "分钟（还差约 " + remMin + " 分钟）"));
                return false;
            }
            ObjectNode sending = applyRewriteJudgment(rec.deepCopy(), judgment, evaluatedAt, rewrittenText);
            tx.put("auto_rewrite", materialId, sending
                    .put("status", "SENDING")
                    .put("dispatchedAt", dispatchMs));
            return true;
        });
        if (!readyToSend) return false;

        try {
            // Pure standalone original post — strictly NO quoteTweetId and NO quoteTweetUrl!
            String publishedTweetId = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId,
                    () -> publishClient.createTweet(accessToken, rewrittenText, List.of(), null));
            if (paidGate != null) {
                paidGate.markSucceeded("auto-rewrite:" + materialId);
            }
            long doneMs = clock.millis();
            String uname = ownerUsername.isBlank() ? "i" : ownerUsername;
            String publishedUrl = "https://x.com/" + uname + "/status/" + publishedTweetId;
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_rewrite", materialId);
                if (rec != null) {
                    tx.put("auto_rewrite", materialId, rec.deepCopy()
                            .put("status", "PUBLISHED")
                            .put("rewrittenText", rewrittenText)
                            .put("commentText", rewrittenText)
                            .put("publishedTweetId", publishedTweetId)
                            .put("publishedTweetUrl", publishedUrl)
                            .put("publishedAt", doneMs)
                            .put("error", ""));
                }
                tx.put("publication", "auto-rewrite-" + materialId, object()
                        .put("status", "PUBLISHED")
                        .put("source", "AUTO_REWRITE_POST")
                        .put("topicId", topicId)
                        .put("materialId", materialId)
                        .put("text", rewrittenText)
                        .put("publishedTweetId", publishedTweetId)
                        .put("publishedTweetUrl", publishedUrl)
                        .put("publishedAt", doneMs));
                return null;
            });
            recordOwnerPublishAndScheduleNextSlot(doneMs);
            return true;
        } catch (XPublishException ex) {
            String terminalStatus = ex.isDeliveryUncertain() ? "UNCERTAIN" : "FAILED";
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_rewrite", materialId);
                if (rec != null) {
                    tx.put("auto_rewrite", materialId, rec.deepCopy()
                            .put("status", terminalStatus)
                            .put("error", safeError(ex)));
                }
                return null;
            });
            return false;
        } catch (RuntimeException ex) {
            store.tx(tx -> {
                ObjectNode rec = tx.get("auto_rewrite", materialId);
                if (rec != null) {
                    tx.put("auto_rewrite", materialId, rec.deepCopy()
                            .put("status", "FAILED")
                            .put("error", safeError(ex)));
                }
                return null;
            });
            return false;
        }
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
