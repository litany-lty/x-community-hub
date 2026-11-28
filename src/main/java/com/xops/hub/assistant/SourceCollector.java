package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class SourceCollector {
    static boolean belongsToSource(ObjectNode material, String sourceId) {
        return material != null && sourceId != null && !sourceId.isBlank()
                && sourceId.equals(material.path("authorId").asText(""));
    }

    public static final Set<Integer> SUPPORTED_TIERS = Set.of(1, 5, 30);
    public static final int DEFAULT_AUTO_REPOST_MIN_SCORE = 70;
    private static final Pattern HANDLE = Pattern.compile("^[A-Za-z0-9_]{1,15}$");
    private static final int STYLE_SAMPLE_LIMIT = 300;
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
    private final AtomicBoolean manualHistoryQueued = new AtomicBoolean();
    private final AtomicBoolean styleRunning = new AtomicBoolean();
    @org.springframework.beans.factory.annotation.Value("${xops.content-assistant-background-collection-enabled:true}")
    private boolean backgroundCollectionEnabled = true;
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

    @FunctionalInterface
    interface BatchIngestHook {
        void onAuthorMaterialsIngested(ObjectNode source, List<ObjectNode> materials);
    }

    private volatile IngestHook ingestHook;
    private volatile BatchIngestHook batchIngestHook;

    void setIngestHook(IngestHook ingestHook) {
        this.ingestHook = ingestHook;
    }

    void setBatchIngestHook(BatchIngestHook batchIngestHook) {
        this.batchIngestHook = batchIngestHook;
    }

    void setPaidCapabilityGate(PaidCapabilityGate paidCapabilityGate) {
        this.paidCapabilityGate = paidCapabilityGate;
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

    public static int resolveAutoRepostMinScore(ObjectNode source) {
        if (source == null || !source.has("autoRepostMinScore")) return DEFAULT_AUTO_REPOST_MIN_SCORE;
        int raw = source.path("autoRepostMinScore").asInt(DEFAULT_AUTO_REPOST_MIN_SCORE);
        if (!source.path("autoRepostMinScoreCustomized").asBoolean(false) && raw == 82) {
            return DEFAULT_AUTO_REPOST_MIN_SCORE;
        }
        return Math.max(50, Math.min(99, raw));
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
                                .put("bio", copy.path("bio").asText(""))
                                .put("verified", copy.path("verified").asBoolean(false))
                                .put("followersCount", copy.path("followersCount").asLong(0))
                                .put("autoRepostEnabled", copy.path("autoRepostEnabled").asBoolean(false))
                                .put("autoRewriteEnabled", copy.path("autoRewriteEnabled").asBoolean(false))
                                .put("autoRepostTextMode", copy.path("autoRepostTextMode").asText("AI"))
                                .put("autoRepostTemplate", copy.path("autoRepostTemplate").asText(""))
                                .put("autoRepostMinScore", resolveAutoRepostMinScore(copy))
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

    /**
     * Fetches a single public X post by tweet ID and converts it into a content-assistant material node.
     */
    public ObjectNode fetchTweetMaterialById(String tweetId) {
        if (provider == null) {
            throw new XDataProviderException(503, "X 数据读取服务未启用");
        }
        String cleanId = tweetId == null ? "" : tweetId.strip();
        if (!cleanId.matches("[0-9]{2,20}")) {
            throw new IllegalArgumentException("无效的 X 推文 ID");
        }
        ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
        String ownerAccountId = owner == null ? "" : owner.path("accountId").asText("");
        TwitterApiIoProvider.FetchedSinglePost fetched = PaidCapabilityGate.withCaller(
                PaidCapabilityGate.PRIVILEGED_USERNAME,
                ownerAccountId, !ownerAccountId.isBlank(),
                () -> provider.fetchSingleTextPost(cleanId, clock.instant().plusSeconds(25))
        );
        return toMaterial(fetched.post(), fetched.author(), false);
    }

    /**
     * Fetches up to a handful of recent public posts on-demand when a monitored source has no
     * local materials yet, so avatar-click repost preview works immediately after adding a source.
     */
    List<ObjectNode> fetchOnDemandPreviewMaterials(ObjectNode source) {
        if (source == null || provider == null) return List.of();
        String authorId = source.path("authorId").asText(source.path("id").asText("")).strip();
        String username = source.path("username").asText("").replaceFirst("^@+", "").strip();
        if (username.isBlank() || authorId.isBlank()) return List.of();
        ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
        String ownerAccountId = owner == null ? "" : owner.path("accountId").asText("");
        TextPostPage page = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME,
                ownerAccountId, !ownerAccountId.isBlank(),
                () -> provider.fetchTextPostsPage(username, authorId, "", 10, clock.instant().plusSeconds(20))
        );
        if (page == null || page.posts() == null || page.posts().isEmpty()) return List.of();
        XUserSummary pageAuthor = page.author();
        return page.posts().stream()
                .limit(5)
                .map(post -> toMaterial(post, pageAuthor, false))
                .toList();
    }

    /**
     * Checks the latest non-reply tweet publish timestamp (epoch ms) on X for the given account
     * (used by the 30-minute @elidlicom idle quote-repost patrol).
     */
    long fetchLatestAccountPostEpochMs(String username, String accountId) {
        if (provider == null) return -1L;
        String cleanUsername = (username == null || username.isBlank())
                ? PaidCapabilityGate.PRIVILEGED_USERNAME
                : username.replaceFirst("^@+", "").strip();
        String safeAccountId = accountId == null ? "" : accountId.strip();
        if (safeAccountId.isBlank()) return -1L;
        Instant deadline = clock.instant().plusSeconds(25);
        boolean owner = store.isOwner(safeAccountId);
        Set<String> seen = new HashSet<>();
        String cursor = "";
        long latest = 0L;
        for (int pages = 0; pages < 5 && clock.instant().isBefore(deadline); pages++) {
            seen.add(cursor);
            String pageCursor = cursor;
            TextPostPage page = PaidCapabilityGate.withCaller(PaidCapabilityGate.PRIVILEGED_USERNAME, safeAccountId, owner,
                    () -> provider.fetchTextPostsPage(cleanUsername, safeAccountId, pageCursor, 20, deadline));
            if (page == null || page.author() == null || !safeAccountId.equals(page.author().id()) || page.posts() == null)
                throw new XDataProviderException(502, "未能核实作者的最新发帖时间");
            boolean hasTimelinePost = false;
            for (TextPost post : page.posts()) {
                if (post == null || post.kind() == null || !safeAccountId.equals(post.authorId())) return -1L;
                if (post.selfThreadReply() || post.kind() == TweetKind.REPLY || post.kind() == TweetKind.RETWEET) continue;
                if (post.publishedAt() == null) return -1L;
                long ts = post.publishedAt().toEpochMilli();
                if (ts <= 0 || ts > clock.millis()) return -1L;
                latest = Math.max(latest, ts);
                if (!post.pinned()) hasTimelinePost = true;
            }
            if (page.exhausted()) return latest;
            cursor = page.nextCursor();
            if (cursor == null || cursor.isBlank() || seen.contains(cursor)) break;
            if (hasTimelinePost) return latest;
        }
        return -1L;
    }

    /**
     * Refreshes recent public posts into local material storage for a configured quote-repost source
     * when its local snapshot is older than 5 minutes, and returns stored materials in recency order.
     */
    List<ObjectNode> syncSourceMaterialsForPatrol(ObjectNode source, long freshnessWindowMs) {
        if (source == null) return List.of();
        String authorId = source.path("authorId").asText(source.path("id").asText("")).strip();
        String username = source.path("username").asText("").replaceFirst("^@+", "").strip();
        if (username.isBlank() && authorId.isBlank()) return List.of();
        long nowMs = clock.millis();
        long lastSuccessAt = source.path("lastSuccessAt").asLong(0L);
        if (provider != null && !username.isBlank() && !authorId.isBlank() && (nowMs - lastSuccessAt >= 5L * 60_000L)) {
            try {
                ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
                String ownerAccountId = owner == null ? "" : owner.path("accountId").asText("");
                TextPostPage page = PaidCapabilityGate.withCaller(
                        PaidCapabilityGate.PRIVILEGED_USERNAME,
                        ownerAccountId, !ownerAccountId.isBlank(),
                        () -> provider.fetchTextPostsPage(username, authorId, "", 20, clock.instant().plusSeconds(25))
                );
                if (page != null && page.posts() != null && !page.posts().isEmpty()) {
                    long completedAt = clock.millis();
                    long commitCutoff = AssistantRetention.cutoffMillis(completedAt);
                    XUserSummary pageAuthor = page.author();
                    store.tx(tx -> {
                        for (TextPost post : page.posts()) {
                            if (post.publishedAt().toEpochMilli() < commitCutoff) continue;
                            AssistantWorkflow.ingest(tx, toMaterial(post, pageAuthor, false));
                        }
                        ObjectNode live = tx.get("source", authorId);
                        if (live != null && "ACTIVE".equals(live.path("status").asText(""))) {
                            cacheRewritePage(tx, authorId, page, completedAt);
                            updateSourceProfile(live, authorId, pageAuthor);
                            tx.put("source", authorId, live.put("lastSuccessAt", completedAt).put("error", ""));
                        }
                        return null;
                    });
                }
            } catch (Exception ignored) {
                // Fall back to existing local materials if upstream refresh fails transiently
            }
        }
        long maxAge = freshnessWindowMs > 0L ? freshnessWindowMs : 24L * 60L * 60_000L;
        return store.tx(tx -> storedRepostCandidates(tx, authorId, nowMs, maxAge));
    }

    /**
     * When a quote-repost source has no brand-new posts in the short freshness window, syncs recent
     * timeline posts if needed and returns stored posts in recency order
     * so the patrol can check prior decisions before choosing an evaluation batch.
     */
    List<ObjectNode> syncSourceMaterialsForRecentRepost(ObjectNode source, long maxAgeMs) {
        if (source == null) return List.of();
        String authorId = source.path("authorId").asText(source.path("id").asText("")).strip();
        String username = source.path("username").asText("").replaceFirst("^@+", "").strip();
        if (username.isBlank() && authorId.isBlank()) return List.of();
        long nowMs = clock.millis();
        long effectiveMaxAge = maxAgeMs > 0L ? maxAgeMs : 30L * 24L * 60L * 60_000L;
        int existingCount = store.tx(tx -> {
            int c = 0;
            for (ObjectNode m : tx.all("material")) {
                boolean same = (!authorId.isBlank() && authorId.equals(m.path("authorId").asText("")))
                        || (!username.isBlank() && username.equalsIgnoreCase(m.path("username").asText("").replaceFirst("^@+", "")));
                if (same && !m.path("selfThreadReply").asBoolean(false)) c++;
            }
            return c;
        });
        long lastSuccessAt = source.path("lastSuccessAt").asLong(0L);
        if (provider != null && !username.isBlank() && !authorId.isBlank()
                && (existingCount < 5 || nowMs - lastSuccessAt >= 10L * 60_000L)) {
            try {
                ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
                String ownerAccountId = owner == null ? "" : owner.path("accountId").asText("");
                TextPostPage page = PaidCapabilityGate.withCaller(
                        PaidCapabilityGate.PRIVILEGED_USERNAME,
                        ownerAccountId, !ownerAccountId.isBlank(),
                        () -> provider.fetchTextPostsPage(username, authorId, "", 30, clock.instant().plusSeconds(25))
                );
                if (page != null && page.posts() != null && !page.posts().isEmpty()) {
                    long completedAt = clock.millis();
                    long commitCutoff = AssistantRetention.cutoffMillis(completedAt);
                    XUserSummary pageAuthor = page.author();
                    store.tx(tx -> {
                        for (TextPost post : page.posts()) {
                            if (post.publishedAt().toEpochMilli() < commitCutoff) continue;
                            AssistantWorkflow.ingest(tx, toMaterial(post, pageAuthor, false));
                        }
                        ObjectNode live = tx.get("source", authorId);
                        if (live != null && "ACTIVE".equals(live.path("status").asText(""))) {
                            cacheRewritePage(tx, authorId, page, completedAt);
                            updateSourceProfile(live, authorId, pageAuthor);
                            tx.put("source", authorId, live.put("lastSuccessAt", completedAt).put("error", ""));
                        }
                        return null;
                    });
                }
            } catch (Exception ignored) {
                // Fall back to existing stored materials if upstream is transiently unavailable
            }
        }
        return store.tx(tx -> storedRepostCandidates(tx, authorId, nowMs, effectiveMaxAge));
    }

    static List<ObjectNode> storedRepostCandidates(AssistantStore.Tx tx, String authorId, long now, long window) {
        if (authorId.isBlank()) return List.of();
        return tx.rewriteWindow(authorId, "", now - window, now).stream()
                .sorted(Comparator.comparingLong((ObjectNode material) -> material.path("publishedAt").asLong()).reversed())
                .toList();
    }

    /**
     * Ensures recent posts and engagement metrics are synced for an account with pure-text wash-rewrite enabled,
     * then returns all stored posts of this author within {@code maxAgeMs}.
     */
    List<ObjectNode> syncSourceMaterialsForRewrite(ObjectNode source, long maxAgeMs) {
        if (source == null) return List.of();
        String authorId = source.path("authorId").asText(source.path("id").asText("")).strip();
        String username = source.path("username").asText("").replaceFirst("^@+", "").strip();
        if (authorId.isBlank()) return List.of();
        long nowMs = clock.millis();
        ObjectNode scan = provider == null || username.isBlank() ? null : store.tx(tx -> {
            ObjectNode live = tx.get("source", authorId);
            if (live == null || !"ACTIVE".equals(live.path("status").asText())
                    || !live.path("autoRewriteEnabled").asBoolean()
                    || !AssistantWorkflow.settings(tx).path("enabled").asBoolean(true)) return null;
            long lastAttempt = live.path("rewriteLastAttemptAt").asLong();
            if (live.path("rewriteBackfillComplete").asBoolean())
                lastAttempt = Math.max(lastAttempt, live.path("lastSuccessAt").asLong());
            if (lastAttempt > 0 && nowMs - lastAttempt < 10L * 60_000L) return null;
            return tx.put("source", authorId, live.put("rewriteLastAttemptAt", nowMs));
        });
        if (scan != null) {
            String cursor = boundCursor(scan, "rewriteCursor", TIMELINE_PROVIDER, TIMELINE_ENDPOINT);
            try {
                ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
                String ownerAccountId = owner == null ? "" : owner.path("accountId").asText("");
                TextPostPage page = PaidCapabilityGate.withCaller(
                        PaidCapabilityGate.PRIVILEGED_USERNAME,
                        ownerAccountId, !ownerAccountId.isBlank(),
                        () -> provider.fetchTextPostsPage(username, authorId, cursor, 40, clock.instant().plusSeconds(25))
                );
                if (page != null) {
                    long completedAt = clock.millis();
                    long commitCutoff = AssistantRetention.cutoffMillis(completedAt);
                    store.tx(tx -> {
                        ObjectNode live = tx.get("source", authorId);
                        if (live == null || !"ACTIVE".equals(live.path("status").asText())
                                || !live.path("autoRewriteEnabled").asBoolean()
                                || !scan.path("instanceId").equals(live.path("instanceId"))
                                || live.path("rewriteLastAttemptAt").asLong() != nowMs) return null;
                        for (TextPost post : page.posts()) {
                            if (!authorId.equals(post.authorId()) || post.publishedAt().toEpochMilli() < commitCutoff
                                    || post.publishedAt().toEpochMilli() > completedAt) continue;
                            AssistantWorkflow.ingest(tx, toMaterial(post, page.author(), false));
                        }
                        cacheRewritePage(tx, authorId, page, completedAt);
                        boolean complete = scan.path("rewriteBackfillComplete").asBoolean() || page.exhausted()
                                || page.nextCursor().isBlank() || page.nextCursor().equals(cursor)
                                || crossedListenerBoundary(page.posts(), completedAt - AutoRepostService.REWRITE_MAX_AGE_MS);
                        updateSourceProfile(live, authorId, page.author());
                        if (cursor.isBlank()) live.put("lastSuccessAt", completedAt);
                        tx.put("source", authorId, live.put("rewriteBackfillComplete", complete)
                                .put("rewriteCursor", complete ? "" : page.nextCursor()).put("rewriteCursorFailures", 0)
                                .put("rewriteCursorProvider", TIMELINE_PROVIDER).put("rewriteCursorEndpoint", TIMELINE_ENDPOINT));
                        return null;
                    });
                }
            } catch (RuntimeException ex) {
                if (!cursor.isBlank()) store.tx(tx -> {
                    ObjectNode live = tx.get("source", authorId);
                    if (live != null && live.path("rewriteLastAttemptAt").asLong() == nowMs
                            && cursor.equals(live.path("rewriteCursor").asText())) {
                        int failures = live.path("rewriteCursorFailures").asInt() + 1;
                        if (shouldResetExpiredCursor(ex, failures)) live.put("rewriteCursor", "");
                        tx.put("source", authorId, live.put("rewriteCursorFailures", failures));
                    }
                    return null;
                });
            }
        }
        long maxAge = maxAgeMs > 0 ? Math.min(maxAgeMs, AutoRepostService.REWRITE_MAX_AGE_MS) : AutoRepostService.REWRITE_MAX_AGE_MS;
        long until = clock.millis();
        return store.tx(tx -> tx.rewriteWindow(authorId, username, until - maxAge, until));
    }

    private void cacheRewritePage(AssistantStore.Tx tx, String authorId, TextPostPage page, long nowMs) {
        tx.expireRewriteMaterials(nowMs - AutoRepostService.REWRITE_MAX_AGE_MS);
        for (TextPost post : page.posts()) {
            long publishedAt = post.publishedAt().toEpochMilli();
            if (!authorId.equals(post.authorId()) || publishedAt > nowMs || publishedAt < nowMs - AutoRepostService.REWRITE_MAX_AGE_MS) continue;
            tx.cacheRewriteMaterial(toMaterial(post, page.author(), false), AssistantTopics.topicOf(tx, post.id()));
        }
    }

    public ObjectNode add(String input, int intervalMinutes) {
        int tierMinutes = validateTierMinutes(intervalMinutes);
        String username = parseUsername(input);
        ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
        XUserSummary author = withBoundOwner(owner, () -> provider.resolvePublicAuthor(username));
        long now = clock.millis();
        long cutoff = AssistantRetention.cutoffMillis(now);
        return store.tx(tx -> {
            ObjectNode existing = tx.get("source", author.id());
            boolean readded = existing != null && "REMOVED".equals(existing.path("status").asText());
            if (existing != null && !readded) {
                if (existing.path("intervalMinutes").asInt(5) != tierMinutes
                        || existing.path("avatarUrl").asText("").isBlank()
                        || (!value(author.bio()).isBlank() && existing.path("bio").asText("").isBlank())) {
                    ObjectNode updated = existing.deepCopy()
                            .put("displayName", value(author.displayName()))
                            .put("avatarUrl", value(author.avatarUrl()))
                            .put("bio", value(author.bio()).isBlank() ? existing.path("bio").asText("") : value(author.bio()))
                            .put("verified", author.verified())
                            .put("followersCount", author.followersCount())
                            .put("intervalMinutes", tierMinutes);
                    return tx.put("source", author.id(), updated);
                }
                return existing;
            }
            ObjectNode source = existing != null ? existing.deepCopy() : mapper.createObjectNode();
            source.put("authorId", author.id()).put("username", author.username())
                    .put("displayName", value(author.displayName()))
                    .put("avatarUrl", value(author.avatarUrl()))
                    .put("bio", value(author.bio()))
                    .put("verified", author.verified())
                    .put("followersCount", author.followersCount())
                    .put("intervalMinutes", tierMinutes)
                    .put("status", "ACTIVE")
                    .put("instanceId", UUID.randomUUID().toString()).put("enabledAt", now)
                    .put("cursor", "").put("cursorProvider", TIMELINE_PROVIDER).put("cursorEndpoint", TIMELINE_ENDPOINT)
                    .put("cursorFailures", 0).put("jitterMs", sourceJitterMs(author.id()))
                    .put("scanSince", cutoff)
                    .put("lastSuccessAt", 0).put("lastCompleteAt", 0)
                    .put("error", "");
            if (!source.has("autoRepostEnabled")) source.put("autoRepostEnabled", false);
            if (!source.has("autoRewriteEnabled")) source.put("autoRewriteEnabled", false);
            if (!source.has("autoRepostTextMode")) source.put("autoRepostTextMode", "AI");
            if (!source.has("autoRepostTemplate")) source.put("autoRepostTemplate", "");
            if (!source.has("autoRepostMinScore")) source.put("autoRepostMinScore", DEFAULT_AUTO_REPOST_MIN_SCORE);
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
            if (body.has("autoRewriteEnabled")) {
                if (!body.path("autoRewriteEnabled").isBoolean()) {
                    throw new IllegalArgumentException("autoRewriteEnabled 必须是布尔值");
                }
                boolean rewriteEnabled = body.path("autoRewriteEnabled").asBoolean();
                boolean wasRewriteEnabled = current.path("autoRewriteEnabled").asBoolean(false);
                next.put("autoRewriteEnabled", rewriteEnabled);
                if (rewriteEnabled && !wasRewriteEnabled) {
                    next.put("autoRewriteEnabledAt", now);
                    next.remove(List.of("rewriteBackfillComplete", "rewriteCursor", "rewriteLastAttemptAt", "rewriteCursorFailures"));
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
                if (!body.path("autoRepostMinScore").isIntegralNumber() || minScore < 50 || minScore > 99) {
                    throw new IllegalArgumentException("走热阈值范围为 50–99");
                }
                next.put("autoRepostMinScore", minScore)
                        .put("autoRepostMinScoreCustomized", true);
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
                if (!"ACTIVE".equals(current.path("status").asText()))
                    next.remove(List.of("rewriteBackfillComplete", "rewriteCursor", "rewriteLastAttemptAt", "rewriteCursorFailures"));
            }
            return tx.put("source", id, next);
        });
    }

    public ObjectNode styleSamples() {
        return store.tx(tx -> {
            List<ObjectNode> samples = OwnerQuoteStyleDistiller.collectOwnerAuthoredSamples(tx, STYLE_SAMPLE_LIMIT);
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
        if (!backgroundCollectionEnabled) return;
        ObjectNode settings = store.tx(AssistantWorkflow::settings);
        if (hasBoundOwner() && settings.path("enabled").asBoolean(false)
                && settings.path("collectionEnabled").asBoolean(false)) refreshStyleSamples();
    }

    public ObjectNode history() {
        return store.tx(tx -> {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode sources = result.putArray("sources");
            ObjectNode source = ownerHistorySource(tx);
            if (source == null) return result.put("materials", 0).put("interactions", 0).put("complete", false);
            String ownerId = source.path("id").asText();
            long materials = verifiedHistoryCount(tx, source, ownerId, true, 0);
            long interactions = verifiedHistoryCount(tx, source, ownerId, false, 0);
            sources.add(source.deepCopy().put("owner", true).put("timelineSavedCount", materials)
                    .put("inboundSavedCount", interactions));
            result.put("ownerAccountId", ownerId);
            result.put("materials", materials);
            result.put("interactions", interactions);
            result.put("complete", source.path("eligible").asBoolean(true)
                    && source.path("timelineExhausted").asBoolean() && source.path("inboundExhausted").asBoolean()
                    && source.path("timelineError").asText("").isBlank() && source.path("inboundError").asText("").isBlank());
            return result;
        });
    }

    private static ObjectNode ownerHistorySource(AssistantStore.Tx tx) {
        ObjectNode binding = tx.ownerBinding();
        if (binding == null || binding.path("accountId").asText("").isBlank()) return null;
        String ownerId = binding.path("accountId").asText();
        ObjectNode source = tx.get("history_source", ownerId);
        return source != null && ownerId.equals(source.path("authorId").asText(ownerId)) ? source : null;
    }

    private static long verifiedHistoryCount(AssistantStore.Tx tx, ObjectNode source, String ownerId,
            boolean timeline, long added) {
        String prefix = timeline ? "timeline" : "inbound";
        if (source.path(prefix + "CountById").asBoolean(false) && source.hasNonNull(prefix + "SavedCount")) {
            return source.path(prefix + "SavedCount").asLong() + added;
        }
        return tx.countHistory(timeline ? "history_material" : "history_interaction", ownerId);
    }

    public ObjectNode retryHistoryNow() {
        store.tx(tx -> {
            ObjectNode source = ownerHistorySource(tx);
            if (source != null) {
                ObjectNode next = source.deepCopy()
                        .put("timelineRetryExhausted", false)
                        .put("inboundRetryExhausted", false)
                        .put("timelineRetryKey", "").put("timelineRetryCount", 0)
                        .put("inboundRetryKey", "").put("inboundRetryCount", 0)
                        .put("timelineError", "")
                        .put("inboundError", "")
                        .put("error", "");
                tx.put("history_source", source.path("id").asText(), next);
            }
            return null;
        });
        provider.resetFreeCircuitForActiveSync();
        if (paidCapabilityGate != null) {
            paidCapabilityGate.resetByPrefix("history-source:");
        }
        if (manualHistoryQueued.compareAndSet(false, true)) {
            worker.submit(() -> {
                try { tick(true); } finally { manualHistoryQueued.set(false); }
            });
        }
        return history();
    }

    @Scheduled(fixedDelayString = "${xops.content-assistant-source-tick-ms:15000}")
    public void scheduledTick() {
        if (!tickRunning.compareAndSet(false, true)) return;
        worker.submit(() -> { try { tick(); } finally { tickRunning.set(false); } });
    }

    public List<String> purgeExpired() {
        long nowMs = clock.millis();
        return store.purgeExpiredContent(AssistantRetention.cutoffMillis(nowMs), nowMs - AutoRepostService.REWRITE_MAX_AGE_MS);
    }

    void tick() {
        tick(false);
    }

    private void tick(boolean historyRequested) {
        ObjectNode ownerNode = store.tx(AssistantStore.Tx::ownerBinding);
        String ownerAccountId = ownerNode == null ? "" : ownerNode.path("accountId").asText("");
        com.xops.hub.provider.PaidCapabilityGate.runWithCaller(
                com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, ownerAccountId, !ownerAccountId.isBlank(),
                () -> tickInternal(historyRequested));
    }

    private void tickInternal(boolean historyRequested) {
        purgeExpired();
        if (!backgroundCollectionEnabled && !historyRequested) return;
        if (!store.hasCollectionSpace()) return;
        ObjectNode settings = store.tx(AssistantWorkflow::settings);
        boolean assistantCollectionActive = backgroundCollectionEnabled && hasBoundOwner()
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
        if ((historyRequested || assistantCollectionActive || communityHubService.isSilentHistoryEnabled())
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
                        store.boundOwnerId(),
                        safeError(ex),
                        this::tick
                );
            }
        }
    }

    private void synchronizeHistoryCandidates() {
        var reported = communityHubService.longHistoryCandidates();
        store.tx(tx -> {
            ObjectNode binding = tx.ownerBinding();
            String ownerId = binding == null ? "" : binding.path("accountId").asText("");
            var candidates = new ArrayList<>(reported.stream().filter(candidate -> candidate != null && candidate.owner()
                    && candidate.user() != null && candidate.user().id() != null && !candidate.user().id().isBlank()
                    && com.xops.hub.model.AuthorIdentity.trustedId(ownerId).equals(candidate.user().id())).limit(1).toList());
            if (!ownerId.isBlank() && candidates.isEmpty()) {
                String username = binding.path("username").asText(PaidCapabilityGate.PRIVILEGED_USERNAME);
                XUserSummary user = new XUserSummary(ownerId, username, username, "", "", false, 0, 0, 0, "");
                candidates.add(new CommunityHubService.LongHistoryCandidate(user, 0, true, 0, true, 0));
            } else if (binding == null && !candidates.isEmpty()) {
                XUserSummary user = candidates.get(0).user();
                binding = tx.observeOwner(user.id(), user.username(), candidates.get(0).qualificationAt());
            }
            Set<String> eligible = new HashSet<>();
            for (CommunityHubService.LongHistoryCandidate candidate : candidates) {
                XUserSummary user = candidate.user();
                String username = binding != null && !binding.path("username").asText("").isBlank()
                        ? binding.path("username").asText() : user.username();
                eligible.add(user.id());
                ObjectNode current = tx.get("history_source", user.id());
                ObjectNode next = current != null ? current.deepCopy() : mapper.createObjectNode();
                String prevTlErr = current != null ? current.path("timelineError").asText("") : "";
                String prevInErr = current != null ? current.path("inboundError").asText("") : "";
                boolean clearTlBudgetErr = prevTlErr.contains("外部调用预算已达保护上限") || prevTlErr.contains("预算记录暂不可用");
                boolean clearInBudgetErr = prevInErr.contains("外部调用预算已达保护上限") || prevInErr.contains("预算记录暂不可用");
                if (clearTlBudgetErr) next.put("timelineError", "");
                if (clearInBudgetErr) next.put("inboundError", "");
                next.put("authorId", user.id()).put("username", username).put("rank", candidate.rank())
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
                        || !username.equals(current.path("username").asText())
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
        long now = clock.millis();
        boolean freeMode = provider.isFreeInboundConfigured();
        boolean allowFreeTimeline = provider.canReadTimelineForFree("");
        boolean allowFreeInbound = provider.canReadInboundForFree("");
        boolean allowPaidBackground = !provider.isBackgroundPaidBudgetExhausted();
        boolean canRunTimeline = freeMode ? allowFreeTimeline : (allowFreeTimeline || allowPaidBackground);
        boolean canRunInbound = freeMode ? allowFreeInbound : allowPaidBackground;
        ObjectNode due = store.tx(tx -> {
            ObjectNode s = ownerHistorySource(tx);
            return s != null && s.path("eligible").asBoolean(false)
                        && ((canRunTimeline && historyDimensionEligibleForTick(s, "timeline", now, allowFreeTimeline))
                            || (canRunInbound && historyDimensionEligibleForTick(s, "inbound", now, allowFreeInbound)))
                    ? s : null;
        });
        if (due == null) return;
        boolean timelineDue = canRunTimeline && historyDimensionEligibleForTick(due, "timeline", now, allowFreeTimeline);
        boolean inboundDue = canRunInbound && historyDimensionEligibleForTick(due, "inbound", now, allowFreeInbound);
        boolean timeline = timelineDue && (!inboundDue || "TIMELINE".equals(due.path("nextDimension").asText("TIMELINE")));
        executeHistoryDimension(due, timeline, now, false);
    }

    private boolean historyDimensionEligibleForTick(ObjectNode source, String prefix, long now, boolean freeAvailable) {
        if (paidCapabilityGate != null && source.path(prefix + "RetryCount").asInt(0) > 0) {
            String key = source.path(prefix + "RetryKey").asText("history-source:" + source.path("id").asText()
                    + ":" + prefix + ":" + source.path(prefix + "Cursor").asText(""));
            var retry = paidCapabilityGate.getRetrySnapshot(key);
            if (retry != null && retry.state() == PaidCapabilityGate.RetryState.PENDING) return false;
            long attempted = source.path(prefix + "LastAttemptAt").asLong(source.path("lastAttemptAt").asLong());
            if (now - attempted < HISTORY_FAILURE_BACKOFF_MS) return false;
        }
        return historyDimensionDue(source, prefix, now, freeAvailable);
    }

    private void retryHistoryDimensionOnce(String sourceId, boolean timeline) {
        String prefix = timeline ? "timeline" : "inbound";
        ObjectNode source = store.tx(SourceCollector::ownerHistorySource);
        if (source == null || !sourceId.equals(source.path("id").asText()) || !source.path("eligible").asBoolean(false)
                || source.path(prefix + "RetryExhausted").asBoolean(false)) {
            return;
        }
        String ownerId = store.boundOwnerId();
        PaidCapabilityGate.runWithCaller(PaidCapabilityGate.PRIVILEGED_USERNAME, ownerId, sourceId.equals(ownerId),
                () -> executeHistoryDimension(source, timeline, clock.millis(), true));
    }

    private void executeHistoryDimension(ObjectNode due, boolean timeline, long now, boolean fromScheduledRetry) {
        boolean freeMode = provider.isFreeInboundConfigured();
        if (fromScheduledRetry && freeMode) {
            provider.resetFreeCircuitForActiveSync();
        }
        String id = due.path("id").asText();
        long attemptAt = now;
        HistoryScan scan = null;
        try {
            if (timeline) {
                scan = prepareHistoryScan(id, "timeline", TIMELINE_ENDPOINT, due, attemptAt);
                long minPublished = scan.since();
                TextPostPage page = freeMode
                        ? provider.fetchHistoryPostsPage(due.path("username").asText(), id,
                                scan.cursor(), 100, Instant.now().plusSeconds(30), true)
                        : provider.fetchHistoryPostsPage(due.path("username").asText(), id,
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
                TextInteractionPage page = freeMode
                        ? provider.fetchInboundTextPage(due.path("username").asText(), id,
                                scan.cursor(), 100, Instant.now().plusSeconds(30), true)
                        : provider.fetchInboundTextPage(due.path("username").asText(), id,
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
            String retryCursor = failedScan == null ? "" : failedScan.cursor();
            String retryKey = "history-source:" + id + ":" + dimension + ":" + retryCursor;
            PaidCapabilityGate.RetrySnapshot snap = null;
            if (paidCapabilityGate != null && backgroundCollectionEnabled) {
                String capability = freeMode ? "fxtwitter_free_source" : "twitterapi_io_read";
                String ownerId = store.boundOwnerId();
                snap = paidCapabilityGate.scheduleIdempotentRetry(
                        retryKey,
                        capability,
                        PaidCapabilityGate.PRIVILEGED_USERNAME,
                        ownerId,
                        safeError(ex),
                        () -> retryHistoryDimensionOnce(id, timeline), false, id.equals(ownerId)
                );
            }
            PaidCapabilityGate.RetrySnapshot finalSnap = snap;
            boolean transientFreeFailure = freeMode && isTransientFreeFailure(ex);
            store.tx(tx -> {
                ObjectNode source = tx.get("history_source", id);
                if (source != null) {
                    int failCount = source.path(dimension + "RetryCount").asInt(0) + 1;
                    boolean exhausted = paidCapabilityGate != null
                            ? (paidCapabilityGate.isExhausted(retryKey)
                                || (finalSnap != null && (finalSnap.state() == PaidCapabilityGate.RetryState.EXHAUSTED
                                    || (fromScheduledRetry && finalSnap.retryCount() >= PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES)))
                                || failCount > PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES)
                            : failCount > PaidCapabilityGate.MAX_IDEMPOTENT_RETRIES;
                    ObjectNode next = source.deepCopy()
                            .put(dimension + "RetryKey", retryKey)
                            .put(dimension + "RetryCount", failCount)
                            .put(dimension + "RetryExhausted", exhausted)
                            .put(dimension + "Error", exhausted ? "免费接口连续重试 3 次未成功，等待手动触发重试" : safeError(ex))
                            .put("nextDimension", timeline ? "INBOUND" : "TIMELINE");
                    if (!exhausted && !transientFreeFailure
                            && failedScan != null && !failedScan.cursor().isBlank() && !UpstreamBudgetGuard.isBudgetReached(ex)) {
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

    private static boolean isTransientFreeFailure(Exception ex) {
        if (ex instanceof XDataProviderException xpe) {
            int code = xpe.getStatusCode();
            return code == 429 || code == 502 || code == 503 || code == 504 || code >= 500;
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("超时") || msg.contains("暂不可用") || msg.contains("503") || msg.contains("timeout");
    }

    private record HistoryScan(String cursor, long startedAt, long since, boolean backfill) {}

    static boolean historyDimensionDue(ObjectNode source, String prefix, long now) {
        return historyDimensionDue(source, prefix, now, false);
    }

    static boolean historyDimensionDue(ObjectNode source, String prefix, long now, boolean freeAvailable) {
        if (source.path(prefix + "RetryExhausted").asBoolean(false)) {
            return false;
        }
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
        long incrementalMs = (freeAvailable && "timeline".equals(prefix)) ? 90_000L : HISTORY_INCREMENTAL_INTERVAL_MS;
        return now - source.path("last" + Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1)
                + "CheckAt").asLong(0) >= incrementalMs;
    }

    private HistoryScan prepareHistoryScan(String sourceId, String prefix, String endpoint, ObjectNode source, long attemptAt) {
        String rawCursor = boundCursor(source, prefix + "Cursor", TIMELINE_PROVIDER, endpoint);
        boolean resetLegacyTimelineCursor = "timeline".equals(prefix)
                && !rawCursor.isBlank() && !rawCursor.startsWith("fx:")
                && (provider.isFreeInboundConfigured()
                    || (provider.isPageReadBudgetExhausted() && provider.canReadTimelineForFree("")));
        boolean resetLegacyInboundCursor = "inbound".equals(prefix)
                && !rawCursor.isBlank() && !rawCursor.startsWith("fx:")
                && provider.isFreeInboundConfigured();
        String cursor = (resetLegacyTimelineCursor || resetLegacyInboundCursor) ? "" : rawCursor;
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
                if (!sourceId.equals(post.authorId())) throw new XDataProviderException(409, "归档帖子的作者身份不符，已保留当前游标");
                ObjectNode existing = tx.get("history_material", post.id());
                if (existing != null && !belongsToSource(existing, sourceId)) {
                    if (!existing.path("authorId").asText("").isBlank()) {
                        throw new XDataProviderException(409, "历史素材的作者 ID 冲突，已保留原记录和当前游标");
                    }
                    tx.put("history_material_unverified", post.id(), existing.deepCopy());
                    existing = null;
                }
                ObjectNode material = mergeArchiveMaterial(existing, post, page.author(), completedAt);
                if (existing == null) added++;
                if (!sameRecord(existing, material)) tx.put("history_material", post.id(), material);
            }
            ObjectNode current = tx.get("history_source", sourceId);
            if (current != null) {
                ObjectNode next = current.deepCopy().put("lastSuccessAt", completedAt).put("timelineError", "")
                        .put("timelineRetryKey", "").put("timelineRetryCount", 0)
                        .put("timelineRetryExhausted", false)
                        .put("timelineCursor", scanComplete ? "" : page.nextCursor())
                        .put("timelineCursorProvider", TIMELINE_PROVIDER)
                        .put("timelineCursorEndpoint", TIMELINE_ENDPOINT)
                        .put("timelineCursorFailures", 0)
                        .put("nextDimension", "INBOUND")
                        .put("timelineSavedCount", verifiedHistoryCount(tx, current, sourceId, true, added))
                        .put("timelineCountById", true);
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
                if (existing != null && !differentKnown(existing, material, "targetAuthorId")
                        && !differentKnown(existing, material, "peerId")) {
                    ObjectNode observed = material;
                    material = existing.deepCopy();
                    for (var fields = observed.fields(); fields.hasNext();) {
                        var field = fields.next();
                        if (!field.getValue().isTextual() || !field.getValue().asText().isBlank()) {
                            material.set(field.getKey(), field.getValue());
                        }
                    }
                    if (existing.path("text").asText("").length() > material.path("text").asText("").length()) {
                        material.set("text", existing.get("text"));
                    }
                }
                if (!sameRecord(existing, material)) tx.put("history_interaction", materialId, material);
            }
            ObjectNode current = tx.get("history_source", sourceId);
            if (current != null) {
                ObjectNode next = current.deepCopy().put("lastSuccessAt", completedAt).put("inboundError", "")
                        .put("inboundRetryKey", "").put("inboundRetryCount", 0)
                        .put("inboundRetryExhausted", false)
                        .put("inboundCursor", scanComplete ? "" : page.nextCursor())
                        .put("inboundCursorProvider", TIMELINE_PROVIDER)
                        .put("inboundCursorEndpoint", INBOUND_ENDPOINT)
                        .put("inboundCursorFailures", 0)
                        .put("nextDimension", "TIMELINE")
                        .put("inboundSavedCount", verifiedHistoryCount(tx, current, sourceId, false, added))
                        .put("inboundCountById", true);
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
        // SQLite JSON reads small counts as IntNode even when the provider wrote a LongNode.
        return normalized.equals((a, b) -> a.isIntegralNumber() && b.isIntegralNumber()
                ? a.bigIntegerValue().compareTo(b.bigIntegerValue()) : a.equals(b) ? 0 : 1, next);
    }

    private static final List<String> ARCHIVE_METRICS = List.of("views", "likes", "replies", "reposts", "bookmarks");
    private static final List<String> QUOTE_FIELDS = List.of("quotedId", "quotedAuthorId", "quotedUsername", "quotedText", "quotedTextStatus");
    private static final List<String> ARTICLE_FIELDS = List.of("articleId", "articleUrl", "articleRole", "articleText", "articleTextStatus");

    private ObjectNode mergeArchiveMaterial(ObjectNode previous, TextPost post, XUserSummary author, long observedAt) {
        ObjectNode observed = toMaterial(post, author, true);
        if (previous == null) return observed.put("seedEvidenceAt", observedAt);
        ObjectNode merged = previous.deepCopy();
        boolean newQuote = differentKnown(previous, observed, "quotedId") || differentKnown(previous, observed, "quotedAuthorId");
        boolean newArticle = differentKnown(previous, observed, "articleRole")
                || (newQuote && "QUOTED".equals(previous.path("articleRole").asText()))
                || (differentKnown(previous, observed, "articleId") && !articleUsesPostId(previous) && !articleUsesPostId(observed));
        if (newQuote) merged.remove(QUOTE_FIELDS);
        if (newArticle) merged.remove(ARTICLE_FIELDS);
        observed.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull() && !(entry.getValue().isTextual() && entry.getValue().asText().isBlank())) {
                merged.set(entry.getKey(), entry.getValue());
            }
        });
        if (author == null) {
            for (String field : List.of("authorName", "authorAvatarUrl", "authorBio", "authorVerified", "authorFollowersCount")) {
                if (previous.hasNonNull(field)) merged.set(field, previous.get(field));
            }
        }
        for (String field : List.of("hasMedia", "selfThreadReply")) {
            if (previous.path(field).asBoolean()) merged.put(field, true);
        }
        retainRicherBody(merged, previous, observed, "text", "authorTextStatus");
        if (!newQuote) retainRicherBody(merged, previous, observed, "quotedText", "quotedTextStatus");
        if (!newArticle) {
            retainRicherBody(merged, previous, observed, "articleText", "articleTextStatus");
            if (articleUsesPostId(observed) && !articleUsesPostId(previous) && !previous.path("articleId").asText("").isBlank()) {
                merged.set("articleId", previous.get("articleId"));
            }
        }
        if ("QUOTE".equals(previous.path("kind").asText()) && "POST".equals(post.kind().name())) merged.put("kind", "QUOTE");
        String quoteStatus = merged.path("quotedTextStatus").asText();
        if ("QUOTE".equals(merged.path("kind").asText()) && merged.path("quotedText").asText("").isBlank()) {
            merged.put("textStatus", "QUOTE_TEXT_MISSING");
        } else if ("SOURCE_MISSING".equals(quoteStatus) && merged.path("quotedAuthorId").asText("").isBlank()) {
            merged.put("textStatus", "QUOTE_SOURCE_MISSING");
        } else if ("FULL".equals(merged.path("articleTextStatus").asText())) {
            merged.put("textStatus", "ARTICLE_FULL");
        } else if ("PREVIEW".equals(merged.path("articleTextStatus").asText())) {
            merged.put("textStatus", "ARTICLE_PREVIEW");
        } else if ("FULL".equals(merged.path("authorTextStatus").asText()) || "FULL".equals(quoteStatus)) {
            merged.put("textStatus", "TEXT_ONLY");
        }
        long previousAt = previous.path("seedEvidenceAt").asLong(previous.path("updatedAt").asLong());
        long newestAt = Math.max(previousAt, observedAt);
        ObjectNode metricTimes = mapper.createObjectNode();
        for (String field : ARCHIVE_METRICS) {
            if (!merged.hasNonNull(field)) continue;
            long fieldAt = previous.path("seedMetricTimes").path(field).asLong(previousAt);
            if (observed.hasNonNull(field) && (!previous.hasNonNull(field) || observedAt >= fieldAt)) {
                fieldAt = observedAt;
            } else if (previous.hasNonNull(field)) {
                merged.set(field, previous.get(field));
            }
            if (fieldAt != newestAt) metricTimes.put(field, fieldAt);
        }
        if (!sameRecord(previous, merged) || ARCHIVE_METRICS.stream().anyMatch(observed::hasNonNull)) {
            merged.put("seedEvidenceAt", newestAt);
            if (metricTimes.isEmpty()) merged.remove("seedMetricTimes");
            else merged.set("seedMetricTimes", metricTimes);
        }
        return merged;
    }

    private static boolean differentKnown(ObjectNode first, ObjectNode second, String field) {
        String oldValue = first.path(field).asText(""), newValue = second.path(field).asText("");
        return !oldValue.isBlank() && !newValue.isBlank() && !oldValue.equals(newValue);
    }

    // A provider with no article ID substitutes its carrier tweet ID. It identifies the post, not a different article.
    private static boolean articleUsesPostId(ObjectNode row) {
        String id = row.path("articleId").asText("");
        String carrier = "QUOTED".equals(row.path("articleRole").asText()) ? "quotedId" : "id";
        return !id.isBlank() && id.equals(row.path(carrier).asText());
    }

    private static void retainRicherBody(ObjectNode merged, ObjectNode previous, ObjectNode observed, String body, String status) {
        String oldText = previous.path(body).asText(""), newText = observed.path(body).asText("");
        if (oldText.isBlank()) return;
        boolean oldFull = "FULL".equals(previous.path(status).asText()), newFull = "FULL".equals(observed.path(status).asText());
        if (newText.isBlank() || (oldFull && !newFull) || (oldFull == newFull && oldText.length() > newText.length())) {
            merged.set(body, previous.get(body));
            if (previous.hasNonNull(status)) merged.set(status, previous.get(status));
        }
    }

    private void commitPage(String sourceKind, String sourceId, String expectedInstanceId, String expectedCursor,
            TextPostPage page, List<TextPost> posts, long scanSince, long completedAt,
            long scanStartedAt, boolean scanComplete) {
        List<ObjectNode> quoteCandidates = new ArrayList<>();
        ObjectNode latestSourceSnapshot = store.tx(tx -> {
            ObjectNode current = tx.get(sourceKind, sourceId);
            if (current == null || !"ACTIVE".equals(current.path("status").asText())
                    || !expectedInstanceId.equals(current.path("instanceId").asText())
                    || !expectedCursor.equals(current.path("cursor").asText(""))) return null;
            long commitCutoff = AssistantRetention.cutoffMillis(completedAt);
            XUserSummary pageAuthor = page.author();
            Set<String> newIds = new HashSet<>();
            for (TextPost post : posts) {
                if (post.publishedAt().toEpochMilli() < commitCutoff) continue;
                boolean existedBefore = tx.get("material", post.id()) != null;
                ObjectNode material = toMaterial(post, pageAuthor, false);
                AssistantWorkflow.ingest(tx, material);
                if (!existedBefore) newIds.add(post.id());
            }
            cacheRewritePage(tx, sourceId, page, completedAt);
            if (current.path("autoRepostEnabled").asBoolean()) {
                for (TextPost post : page.posts()) {
                    if (!sourceId.equals(post.authorId())) continue;
                    if (!newIds.contains(post.id())) {
                        ObjectNode earlier = tx.get("auto_repost", post.id());
                        if (earlier == null || !earlier.path("quoteSourceNeedsReview").asBoolean()
                                || !Set.of("EVALUATING", "JUDGE_FAILED", "SKIPPED").contains(earlier.path("status").asText())) continue;
                    }
                    ObjectNode saved = tx.rewriteMaterial(post.id(), completedAt);
                    if (saved != null) quoteCandidates.add(saved);
                }
            }
            ObjectNode next = current.deepCopy().put("lastSuccessAt", completedAt).put("error", "")
                    .put("scanSince", scanSince)
                    .put("cursor", scanComplete ? "" : page.nextCursor())
                    .put("cursorProvider", TIMELINE_PROVIDER)
                    .put("cursorEndpoint", TIMELINE_ENDPOINT)
                    .put("cursorFailures", 0);
            updateSourceProfile(next, sourceId, pageAuthor);
            if (scanComplete) {
                next.put("lastCompleteAt", scanStartedAt);
                next.remove("anchorSince");
            }
            return tx.put(sourceKind, sourceId, next);
        });
        BatchIngestHook batchHook = this.batchIngestHook;
        IngestHook hook = this.ingestHook;
        if (latestSourceSnapshot != null
                && latestSourceSnapshot.path("autoRepostEnabled").asBoolean(false)
                && !quoteCandidates.isEmpty()) {
            if (batchHook != null) {
                batchHook.onAuthorMaterialsIngested(latestSourceSnapshot, List.copyOf(quoteCandidates));
            } else if (hook != null) {
                for (ObjectNode item : quoteCandidates) {
                    hook.onMaterialIngested(latestSourceSnapshot, item);
                }
            }
        }
    }

    private static void updateSourceProfile(ObjectNode source, String authorId, XUserSummary author) {
        if (author == null || !authorId.equals(author.id())) return;
        if (!value(author.username()).isBlank()) source.put("username", author.username());
        if (!value(author.displayName()).isBlank()) source.put("displayName", author.displayName());
        if (!value(author.avatarUrl()).isBlank()) source.put("avatarUrl", author.avatarUrl());
        if (!value(author.bio()).isBlank()) source.put("bio", author.bio());
        source.put("verified", author.verified()).put("followersCount", author.followersCount());
    }

    private ObjectNode toMaterial(TextPost post, XUserSummary author, boolean archiveOnly) {
        ObjectNode material = mapper.createObjectNode()
                .put("id", post.id()).put("authorId", post.authorId()).put("username", post.username())
                .put("authorName", author != null && !value(author.displayName()).isBlank() ? author.displayName() : value(post.username()))
                .put("authorAvatarUrl", author != null ? value(author.avatarUrl()) : "")
                .put("authorBio", author != null ? value(author.bio()) : "")
                .put("authorVerified", author != null && author.verified())
                .put("authorFollowersCount", author != null ? author.followersCount() : 0L)
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
        ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
        if (owner == null || owner.path("accountId").asText("").isBlank()
                || owner.path("username").asText("").isBlank()) return;
        withBoundOwner(owner, () -> { collectStyleSamples(owner); return null; });
    }

    private static <T> T withBoundOwner(ObjectNode owner, Supplier<T> action) {
        if (PaidCapabilityGate.currentScope() != null || owner == null || owner.path("accountId").asText("").isBlank())
            return action.get();
        return PaidCapabilityGate.withCaller(owner.path("username").asText(), owner.path("accountId").asText(), true, action);
    }

    private void collectStyleSamples(ObjectNode owner) {
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
                        boolean isQuote = post.kind() == com.xops.hub.model.RawSnapshotModels.TweetKind.QUOTE;
                        ObjectNode sampleNode = mapper.createObjectNode()
                                .put("textRole", "AUTHOR")
                                .put("kind", post.kind().name())
                                .put("isQuote", isQuote)
                                .put("text", post.styleText())
                                .put("publishedAt", post.publishedAt().toEpochMilli());
                        if (isQuote && !value(post.quotedText()).isBlank()) {
                            sampleNode.put("quotedText", value(post.quotedText()));
                        }
                        tx.put("style_sample", post.id(), sampleNode);
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
        ObjectNode owner = store.tx(AssistantStore.Tx::ownerBinding);
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
