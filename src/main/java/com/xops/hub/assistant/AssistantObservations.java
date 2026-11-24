package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.provider.TwitterApiIoProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.xops.hub.assistant.AssistantStore.object;

/** Low-priority snapshots for age-matched post metrics and daily owner followers. */
@Service
public final class AssistantObservations {
    static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
    static final long WINDOW_TOLERANCE_MS = DurationHours.ONE;
    static final List<Integer> WINDOWS = List.of(24, 72, 168);
    private static final Set<String> METRICS = Set.of("views", "likes", "replies", "reposts", "bookmarks");
    private final AssistantStore store;
    private final Runnable reconcile;
    private final ObservationClient client;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "content-assistant-observations");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean observationRunning = new AtomicBoolean();
    private final AtomicBoolean followersRunning = new AtomicBoolean();

    @Autowired
    public AssistantObservations(AssistantStore store, AssistantPublishing publishing, TwitterApiIoProvider provider) {
        this(store, () -> {
            ObjectNode owner = store.tx(tx -> tx.get("meta", "owner"));
            if (owner != null) publishing.reconcile(owner.path("accountId").asText());
        }, new ObservationClient() {
            @Override public ObjectNode post(String ownerId, String postId) { return provider.observePost(ownerId, postId); }
            @Override public ObjectNode owner(String ownerId, String username) { return provider.observeOwner(ownerId, username); }
        }, Clock.systemUTC());
    }

    AssistantObservations(AssistantStore store, Runnable reconcile, ObservationClient client, Clock clock) {
        this.store = store;
        this.reconcile = reconcile;
        this.client = client;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${xops.content-assistant-observation-ms:300000}")
    public void tick() {
        if (observationRunning.compareAndSet(false, true)) worker.submit(() -> {
            try { runTick(); } finally { observationRunning.set(false); }
        });
    }

    void runTick() {
        Owner owner = owner();
        if (owner == null) return;
        com.xops.hub.provider.PaidCapabilityGate.runWithCaller(
                com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, owner.id(), () -> {
            reconcile.run();
            long now = clock.millis();
            syncPublishedTargets(owner, now);
            syncRecentHistoryTargets(owner, now);
            Target due = store.tx(tx -> nextDue(tx, now));
            if (due != null) observe(owner, due, now);
        });
    }

    @Scheduled(cron = "${xops.content-assistant-followers-cron:0 20 3 * * *}", zone = "Asia/Shanghai")
    public void followers() {
        if (followersRunning.compareAndSet(false, true)) worker.submit(() -> {
            try { recordFollowers(); } finally { followersRunning.set(false); }
        });
    }

    private void recordFollowers() {
        Owner owner = owner();
        if (owner == null) return;
        String day = LocalDate.now(clock.withZone(USER_ZONE)).toString();
        if (store.tx(tx -> tx.get("followers", day)) != null) return;
        try {
            ObjectNode observed = com.xops.hub.provider.PaidCapabilityGate.withCaller(
                    com.xops.hub.provider.PaidCapabilityGate.PRIVILEGED_USERNAME, owner.id(),
                    () -> client.owner(owner.id(), owner.username()));
            long observedAt = requiredObservedAt(observed);
            store.tx(tx -> {
                ObjectNode row = object().put("localDate", day).put("status", "OBSERVED").put("observedAt", observedAt);
                copyMetric(observed, row, "followers");
                List<ObjectNode> previous = tx.all("followers").stream()
                        .filter(item -> "OBSERVED".equals(item.path("status").asText()) && item.path("followers").isIntegralNumber())
                        .sorted(Comparator.comparingLong((ObjectNode item) -> item.path("observedAt").asLong()).reversed()).toList();
                if (!previous.isEmpty() && row.path("followers").isIntegralNumber()) {
                    ObjectNode old = previous.get(0);
                    row.put("previousObservedAt", old.path("observedAt").asLong())
                            .put("previousFollowers", old.path("followers").asLong())
                            .put("netChange", row.path("followers").asLong() - old.path("followers").asLong())
                            .put("daysSincePrevious", Math.max(1, ChronoUnit.DAYS.between(
                                    Instant.ofEpochMilli(old.path("observedAt").asLong()).atZone(USER_ZONE).toLocalDate(),
                                    Instant.ofEpochMilli(observedAt).atZone(USER_ZONE).toLocalDate())));
                }
                tx.put("followers", day, row);
                return null;
            });
        } catch (RuntimeException error) {
            store.tx(tx -> { tx.put("followers", day, object().put("localDate", day).put("status", "FAILED")
                    .put("attemptedAt", clock.millis()).put("error", safe(error))); return null; });
        }
    }

    private Owner owner() {
        return store.tx(tx -> {
            if (!AssistantWorkflow.settings(tx).path("enabled").asBoolean()) return null;
            ObjectNode value = tx.get("meta", "owner");
            if (value == null || value.path("accountId").asText("").isBlank() || value.path("username").asText("").isBlank()) return null;
            return new Owner(value.path("accountId").asText(), value.path("username").asText());
        });
    }

    private void syncPublishedTargets(Owner owner, long now) {
        store.tx(tx -> {
            for (ObjectNode publication : tx.page("publication", 200, 0)) {
                String postId = publication.path("publishedTweetId").asText("");
                long publishedAt = publication.path("publishedAt").asLong(0);
                if (postId.isBlank() || publishedAt <= 0) continue;
                putTarget(tx, postId, publishedAt, publication.path("id").asText(""), "ASSISTANT", publication);
                if (!publication.path("styleRefreshMarkedAt").isIntegralNumber()) {
                    ObjectNode status = Optional.ofNullable(tx.get("meta", "style_status")).orElse(object()).deepCopy();
                    status.put("refreshRequestedAt", now).put("publishedPostId", postId);
                    tx.put("meta", "style_status", status);
                    tx.put("publication", publication.path("id").asText(), publication.deepCopy().put("styleRefreshMarkedAt", now));
                }
            }
            return null;
        });
    }

    private void syncRecentHistoryTargets(Owner owner, long now) {
        store.tx(tx -> {
            ObjectNode marker = Optional.ofNullable(tx.get("meta", "observation_history_cursor")).orElse(object());
            long seen = marker.path("updatedAtWatermark").asLong(0);
            String lastId=marker.path("lastId").asText("");
            var batch=tx.after("history_material",seen,lastId,100);
            for (ObjectNode item : batch) {
                if (!owner.id().equals(item.path("authorId").asText())) continue;
                if (!Set.of("POST", "QUOTE").contains(item.path("kind").asText())
                        || "NO_ANALYZABLE_TEXT".equals(item.path("textStatus").asText())) continue;
                long publishedAt = item.path("publishedAt").asLong(0);
                if (publishedAt > 0 && publishedAt <= now) putTarget(tx, item.path("id").asText(), publishedAt, "", "OWNER_HISTORY", item);
            }
            if (!batch.isEmpty()) {
                var last=batch.get(batch.size()-1);
                tx.put("meta","observation_history_cursor",object().put("updatedAtWatermark",last.path("updatedAt").asLong()).put("lastId",last.path("id").asText()));
            }
            return null;
        });
    }

    private void putTarget(AssistantStore.Tx tx, String postId, long publishedAt, String publicationId, String source, ObjectNode origin) {
        if(postId.isBlank()) return;
        String draftId = origin.path("draftId").asText("");
        String skillId = ViralWritingSkills.normalizeId(origin.path("skillId").asText(""));
        if (skillId.isEmpty() && !draftId.isBlank()) {
            int draftVersion = origin.path("draftVersion").asInt(0);
            ObjectNode d = draftVersion > 0 ? tx.version("draft", draftId, draftVersion) : tx.get("draft", draftId);
            if (d == null) d = tx.get("draft", draftId);
            if (d != null) skillId = ViralWritingSkills.normalizeId(d.path("skillId").asText(""));
        }
        var existing=tx.get("observation_target",postId);
        if(existing!=null && (publicationId.isEmpty() || publicationId.equals(existing.path("publicationId").asText()))) {
            if (existing.path("skillId").asText("").isBlank() && !skillId.isEmpty()) {
                tx.put("observation_target", postId, existing.deepCopy().put("skillId", skillId));
            }
            return;
        }
        ObjectNode target = object().put("postId", postId).put("publishedAt", publishedAt)
                .put("publicationId", publicationId).put("source", source)
                .put("topicId", origin.path("topicId").asText("")).put("draftId", draftId)
                .put("draftVersion", origin.path("draftVersion").asInt(0));
        if (!skillId.isEmpty()) target.put("skillId", skillId);
        tx.put("observation_target", postId, target);
    }

    private Target nextDue(AssistantStore.Tx tx, long now) {
        List<Target> due = new ArrayList<>();
        for (ObjectNode target : tx.all("observation_target")) {
            for (int hours : WINDOWS) {
                String id = target.path("postId").asText() + ":" + hours;
                ObjectNode existing = tx.get("observation", id);
                if (existing != null && Set.of("OBSERVED", "MISSING_WINDOW", "OUTSIDE_WINDOW").contains(existing.path("status").asText())) continue;
                if (existing != null && existing.path("nextAttemptAt").asLong(0) > now) continue;
                long targetAt = target.path("publishedAt").asLong() + hours * DurationHours.ONE;
                if (now > targetAt + WINDOW_TOLERANCE_MS) {
                    ObjectNode missing = baseObservation(target, hours, targetAt).put("status", "MISSING_WINDOW")
                            .put("checkedAt", now).put("reason", "同龄观察窗口已错过，未用后来的累计值补造");
                    tx.put("observation", id, missing);
                    continue;
                }
                if (now >= targetAt - WINDOW_TOLERANCE_MS) due.add(new Target(target.deepCopy(), hours, targetAt));
            }
        }
        return due.stream().min(Comparator.comparingLong(Target::targetAt)).orElse(null);
    }

    private void observe(Owner owner, Target due, long attemptedAt) {
        String postId = due.record().path("postId").asText();
        String observationId = postId + ":" + due.hours();
        try {
            ObjectNode actual = client.post(owner.id(), postId);
            long observedAt = requiredObservedAt(actual);
            store.tx(tx -> {
                ObjectNode row = baseObservation(due.record(), due.hours(), due.targetAt())
                        .put("observedAt", observedAt).put("ageHours", (observedAt - due.record().path("publishedAt").asLong()) / 3_600_000d)
                        .put("offsetMinutes", (observedAt - due.targetAt()) / 60_000d);
                if (!withinWindow(observedAt, due.targetAt())) {
                    row.put("status", "OUTSIDE_WINDOW").put("reason", "实际取得时间超出同龄窗口，指标未计入成绩");
                } else {
                    row.put("status", "OBSERVED");
                    ArrayNode missing = row.putArray("missingMetrics");
                    for (String metric : METRICS) {
                        if (actual.path(metric).isIntegralNumber() && actual.path(metric).asLong() >= 0) row.put(metric, actual.path(metric).asLong());
                        else missing.add(metric);
                    }
                    addInteractionRate(row);
                    addBaseline(tx, row);
                }
                tx.put("observation", observationId, row);
                return null;
            });
        } catch (RuntimeException error) {
            store.tx(tx -> { tx.put("observation", observationId, baseObservation(due.record(), due.hours(), due.targetAt())
                    .put("status", "RETRYABLE_ERROR").put("attemptedAt", attemptedAt)
                    .put("nextAttemptAt", attemptedAt + 15 * 60_000L).put("error", safe(error))); return null; });
        }
    }

    private void addBaseline(AssistantStore.Tx tx, ObjectNode current) {
        int hours = current.path("windowHours").asInt();
        long publishedAt = current.path("publishedAt").asLong();
        List<ObjectNode> prior = tx.all("observation").stream()
                .filter(row -> "OBSERVED".equals(row.path("status").asText()) && row.path("windowHours").asInt() == hours)
                .filter(row -> row.path("publishedAt").asLong() < publishedAt)
                .sorted(Comparator.comparingLong((ObjectNode row) -> row.path("publishedAt").asLong()).reversed())
                .limit(20).toList();
        ObjectNode baseline = current.putObject("baseline");
        baseline.put("candidateCount", prior.size()).put("minimumRequired", 5).put("maximumCompared", 20);
        for (String metric : METRICS) {
            List<Long> values = prior.stream().map(row -> row.path(metric)).filter(JsonNode::isIntegralNumber)
                    .map(JsonNode::asLong).sorted().toList();
            ObjectNode summary = baseline.putObject(metric).put("sampleCount", values.size()).put("ready", values.size() >= 5);
            if (!values.isEmpty()) {
                long middle = median(values);
                summary.put("median", middle);
                if (values.size() >= 5 && middle > 0 && current.path(metric).isIntegralNumber())
                    summary.put("relativeToMedian", current.path(metric).asDouble() / middle)
                            .put("percentChange", (current.path(metric).asDouble() - middle) * 100d / middle);
            }
        }
        List<Double> rates = prior.stream().map(row -> row.path("interactionRate")).filter(JsonNode::isNumber)
                .map(JsonNode::asDouble).sorted().toList();
        ObjectNode rate = baseline.putObject("interactionRate").put("sampleCount", rates.size()).put("ready", rates.size() >= 5);
        if (!rates.isEmpty()) {
            double middle = medianDoubles(rates);
            rate.put("median", middle);
            if (rates.size() >= 5 && middle > 0 && current.path("interactionRate").isNumber())
                rate.put("relativeToMedian", current.path("interactionRate").asDouble() / middle)
                        .put("percentChange", (current.path("interactionRate").asDouble() - middle) * 100d / middle);
        }
        baseline.put("status", baseline.path("views").path("ready").asBoolean(false) ? "READY" : "INSUFFICIENT");
    }

    private static ObjectNode baseObservation(ObjectNode target, int hours, long targetAt) {
        ObjectNode row = object().put("postId", target.path("postId").asText()).put("publicationId", target.path("publicationId").asText(""))
                .put("source", target.path("source").asText()).put("publishedAt", target.path("publishedAt").asLong())
                .put("targetAt", targetAt).put("windowHours", hours).put("topicId", target.path("topicId").asText(""))
                .put("draftId", target.path("draftId").asText("")).put("draftVersion", target.path("draftVersion").asInt(0));
        String skillId = ViralWritingSkills.normalizeId(target.path("skillId").asText(""));
        if (!skillId.isEmpty()) row.put("skillId", skillId);
        return row;
    }

    static boolean withinWindow(long observedAt, long targetAt) { return Math.abs(observedAt - targetAt) <= WINDOW_TOLERANCE_MS; }
    static long median(List<Long> sorted) { int n = sorted.size(); return n % 2 == 1 ? sorted.get(n / 2) : Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2d); }
    static double medianDoubles(List<Double> sorted) { int n = sorted.size(); return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2d; }
    static void addInteractionRate(ObjectNode row) {
        if (!row.path("views").isIntegralNumber() || row.path("views").asLong() <= 0) return;
        long interactions = 0;
        for (String metric : List.of("likes", "replies", "reposts", "bookmarks")) {
            if (!row.path(metric).isIntegralNumber()) return;
            interactions += row.path(metric).asLong();
        }
        row.put("interactionRate", interactions / (double) row.path("views").asLong());
    }
    private static void copyMetric(JsonNode from, ObjectNode to, String field) { if (from.path(field).isIntegralNumber() && from.path(field).asLong() >= 0) to.put(field, from.path(field).asLong()); }
    private static long requiredObservedAt(JsonNode observation) {
        if (!observation.path("observedAt").isIntegralNumber() || observation.path("observedAt").asLong() <= 0) throw new IllegalArgumentException("上游观察缺少实际时间");
        return observation.path("observedAt").asLong();
    }
    private static String safe(RuntimeException error) { String message = error.getMessage(); return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length())); }

    @PreDestroy
    void stop() { worker.shutdownNow(); }

    interface ObservationClient { ObjectNode post(String ownerId, String postId); ObjectNode owner(String ownerId, String username); }
    private record Owner(String id, String username) { }
    private record Target(ObjectNode record, int hours, long targetAt) { }
    private static final class DurationHours { static final long ONE = 3_600_000L; }
}
