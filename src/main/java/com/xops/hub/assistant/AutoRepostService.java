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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xops.hub.assistant.AssistantStore.object;

@Service
public class AutoRepostService {
    private static final Logger log = LoggerFactory.getLogger(AutoRepostService.class);
    static final long MAX_FRESHNESS_AGE_MS = 45 * 60_000L;
    private static final int DEFAULT_DAILY_LIMIT = 10;
    private static final long DEFAULT_COOLDOWN_MS = 3 * 60_000L;
    private static final Set<String> RETRYABLE_STATUSES = Set.of(
            "AUTH_REQUIRED", "JUDGE_FAILED", "FAILED", "SKIPPED_COOLDOWN"
    );

    private final AssistantStore store;
    private final SourceCollector sources;
    private final PiAgentViralJudge judge;
    private final XOAuthService oauthService;
    private final XPublishClient publishClient;
    private final Clock clock;
    @Autowired(required = false)
    private com.xops.hub.provider.PaidCapabilityGate paidGate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-auto-repost");
        t.setDaemon(true);
        return t;
    });

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

    @PostConstruct
    void bindHook() {
        recoverStaleRecords();
        sources.setIngestHook(this::enqueue);
    }

    void recoverStaleRecords() {
        store.tx(tx -> {
            for (ObjectNode rec : tx.all("auto_repost")) {
                String id = rec.path("materialId").asText(rec.path("id").asText("")).strip();
                if (id.isBlank()) continue;
                String status = rec.path("status").asText("");
                if ("EVALUATING".equals(status)) {
                    tx.put("auto_repost", id, rec.deepCopy()
                            .put("status", "JUDGE_FAILED")
                            .put("error", "服务重启中断判定，可手动重试"));
                } else if ("SENDING".equals(status)) {
                    tx.put("auto_repost", id, rec.deepCopy()
                            .put("status", "UNCERTAIN")
                            .put("error", "发送途中服务重启，请先核实线上时间线避免重复发送"));
                }
            }
            return null;
        });
    }

    public ObjectNode retry(String materialId) {
        String id = materialId == null ? "" : materialId.strip();
        if (id.isBlank()) throw new IllegalArgumentException("缺少 materialId");

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
            ObjectNode style = tx.get("meta", "style");
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            ObjectNode updated = tx.put("auto_repost", id, existing.deepCopy()
                    .put("status", "EVALUATING")
                    .put("error", ""));
            return new RetryPlan(
                    updated,
                    reuseComment,
                    existingComment,
                    owner.path("accountId").asText(),
                    owner.path("username").asText("i"),
                    topicId,
                    liveSource.deepCopy(),
                    liveMaterial == null ? null : liveMaterial.deepCopy(),
                    style == null ? "" : style.path("profile").asText(""),
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
                            0L
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
                            clock.millis()
                    );
                }
            } catch (Exception ex) {
                log.warn("Auto-repost retry failed for material {}: {}", id, ex.getMessage());
            }
        });
        return plan.updated();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public void enqueue(ObjectNode source, ObjectNode material) {
        if (source == null || material == null) return;
        ObjectNode sourceCopy = source.deepCopy();
        ObjectNode materialCopy = material.deepCopy();
        executor.submit(() -> {
            try {
                processCandidate(sourceCopy, materialCopy);
            } catch (Exception ex) {
                log.warn("Auto-repost evaluation failed for material {}: {}",
                        materialCopy.path("id").asText(""), ex.getMessage());
            }
        });
    }

    void processCandidate(ObjectNode sourceSnapshot, ObjectNode materialSnapshot) {
        String materialId = materialSnapshot.path("id").asText("").strip();
        if (!materialId.matches("[0-9]+")) return;
        if (materialSnapshot.path("pinned").asBoolean(false)
                || materialSnapshot.path("selfThreadReply").asBoolean(false)) {
            return;
        }
        String kind = materialSnapshot.path("kind").asText("POST");
        if (!Set.of("POST", "QUOTE").contains(kind)) return;

        String combinedText = AssistantTopics.text(materialSnapshot);
        if (combinedText.isBlank() || combinedText.codePointCount(0, combinedText.length()) < 10) return;

        long now = clock.millis();
        long publishedAt = materialSnapshot.path("publishedAt").asLong(0);
        if (publishedAt <= 0 || now - publishedAt > MAX_FRESHNESS_AGE_MS) {
            return;
        }

        record ClaimContext(
                String ownerId,
                String ownerUsername,
                ObjectNode source,
                ObjectNode material,
                String topicId,
                String styleProfile,
                String preferencesText
        ) { }

        ClaimContext ctx = store.tx(tx -> {
            if (tx.get("auto_repost", materialId) != null) return null;
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
            long enabledAt = liveSource.path("autoRepostEnabledAt").asLong(0);
            if (enabledAt > 0 && publishedAt < enabledAt) {
                return null;
            }
            ObjectNode liveMaterial = tx.get("material", materialId);
            if (liveMaterial == null) return null;
            String topicId = AssistantTopics.topicOf(tx, materialId);

            // Prevent reposting duplicate breaking news already auto-reposted via another source in the same topic.
            int dailyLimit = Math.max(1, settings.path("autoRepostDailyLimit").asInt(DEFAULT_DAILY_LIMIT));
            long cooldownMs = Math.max(0L, settings.path("autoRepostCooldownMinutes").asLong(3)) * 60_000L;
            if (cooldownMs == 0 && !settings.has("autoRepostCooldownMinutes")) cooldownMs = DEFAULT_COOLDOWN_MS;
            LocalDate today = Instant.ofEpochMilli(now).atZone(AssistantRetention.ZONE).toLocalDate();
            long todayStart = today.atStartOfDay(AssistantRetention.ZONE).toInstant().toEpochMilli();
            int publishedToday = 0;
            long latestPublishedAt = 0L;
            for (ObjectNode rec : tx.all("auto_repost")) {
                String recStatus = rec.path("status").asText("");
                if (!topicId.isBlank() && topicId.equals(rec.path("topicId").asText(""))
                        && Set.of("PUBLISHED", "SENDING", "UNCERTAIN").contains(recStatus)) {
                    tx.put("auto_repost", materialId, baseRecord(liveSource, liveMaterial, topicId, now)
                            .put("status", "SKIPPED_DUPLICATE")
                            .put("reason", "同一事件选题已触发过自动转发"));
                    return null;
                }
                if (Set.of("PUBLISHED", "UNCERTAIN").contains(recStatus)) {
                    long recPubAt = rec.path("publishedAt").asLong(rec.path("dispatchedAt").asLong(0));
                    if (recPubAt >= todayStart) publishedToday++;
                    latestPublishedAt = Math.max(latestPublishedAt, recPubAt);
                }
            }
            if (publishedToday >= dailyLimit) {
                tx.put("auto_repost", materialId, baseRecord(liveSource, liveMaterial, topicId, now)
                        .put("status", "SKIPPED_LIMIT")
                        .put("reason", "已达单日自动引用转发保护上限（" + dailyLimit + " 条）"));
                return null;
            }
            if (latestPublishedAt > 0 && now - latestPublishedAt < cooldownMs) {
                tx.put("auto_repost", materialId, baseRecord(liveSource, liveMaterial, topicId, now)
                        .put("status", "SKIPPED_COOLDOWN")
                        .put("reason", "距离上一条自动转发间隔过短，已触发频控保护"));
                return null;
            }

            tx.put("auto_repost", materialId, baseRecord(liveSource, liveMaterial, topicId, now)
                    .put("status", "EVALUATING"));
            ObjectNode style = tx.get("meta", "style");
            ObjectNode prefs = AssistantWorkflow.preferences(tx);
            return new ClaimContext(
                    owner.path("accountId").asText(),
                    owner.path("username").asText("i"),
                    liveSource,
                    liveMaterial,
                    topicId,
                    style == null ? "" : style.path("profile").asText(""),
                    prefs.path("text").asText("")
            );
        });

        if (ctx == null) return;

        evaluateAndPublish(
                materialId,
                ctx.ownerId(),
                ctx.ownerUsername(),
                ctx.topicId(),
                ctx.source(),
                ctx.material(),
                ctx.styleProfile(),
                ctx.preferencesText(),
                now
        );
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
        String textMode = source.path("autoRepostTextMode").asText("AI").toUpperCase();
        boolean useTemplate = "TEMPLATE".equals(textMode);
        int minScore = source.path("autoRepostMinScore").asInt(82);
        long publishedAt = material.path("publishedAt").asLong(0);
        long ageMinutes = publishedAt > 0 ? Math.max(0L, (now - publishedAt) / 60_000L) : 0L;

        final PiAgentViralJudge.ViralJudgment judgment;
        try {
            judgment = judge.evaluate(new PiAgentViralJudge.EvaluationInput(
                    material.path("username").asText(""),
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
                    !useTemplate
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
                    tx.put("auto_repost", materialId, applyJudgment(rec.deepCopy(), judgment, evaluatedAt)
                            .put("status", "SKIPPED"));
                }
                return null;
            });
            return;
        }

        String commentText = buildCommentText(useTemplate, source, judgment);
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

        dispatchCommentAndPublish(materialId, ownerId, ownerUsername, topicId, commentText, judgment, evaluatedAt);
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
        boolean claimedSend = store.tx(tx -> {
            ObjectNode rec = tx.get("auto_repost", materialId);
            if (rec == null || !"EVALUATING".equals(rec.path("status").asText())) return false;
            ObjectNode next = judgment != null ? applyJudgment(rec.deepCopy(), judgment, evaluatedAt) : rec.deepCopy();
            tx.put("auto_repost", materialId, next
                    .put("status", "SENDING")
                    .put("commentText", commentText)
                    .put("dispatchedAt", clock.millis()));
            return true;
        });
        if (!claimedSend) return;

        try {
            String publishedTweetId = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId,
                    () -> publishClient.createTweet(accessToken, commentText, List.of(), materialId));
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
                        .put("quotedTweetId", materialId)
                        .put("text", commentText)
                        .put("publishedTweetId", publishedTweetId)
                        .put("publishedTweetUrl", publishedUrl)
                        .put("publishedAt", doneMs));
                return null;
            });
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

    private static String buildCommentText(boolean useTemplate, ObjectNode source, PiAgentViralJudge.ViralJudgment judgment) {
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
            aiText = judgment.summary();
        }
        return PiAgentViralJudge.truncateCodePoints(aiText, 220);
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
        return rec.put("viralScore", j.viralScore())
                .put("eventMagnitude", j.eventMagnitude())
                .put("publicResonance", j.publicResonance())
                .put("credibility", j.credibility())
                .put("category", j.category())
                .put("summary", j.summary())
                .put("reason", j.reason())
                .put("evaluatedAt", evaluatedAt);
    }

    private static Long optionalLong(ObjectNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).asLong() : null;
    }

    private static String safeError(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank()) m = ex.getClass().getSimpleName();
        return m.length() > 260 ? m.substring(0, 260) : m;
    }
}
