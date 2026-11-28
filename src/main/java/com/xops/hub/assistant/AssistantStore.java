package com.xops.hub.assistant;

import com.xops.hub.provider.XDataProviderException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.AuthorIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Single-process, immutable revisions and a transactional index of current records. */
@Component
public class AssistantStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssistantStore.class);
    private final ObjectMapper mapper;
    private final Path db;
    private boolean initialized;
    private final Map<String, ObjectNode> pendingPublicationFailures = new HashMap<>();

    @Autowired
    public AssistantStore(XOpsProperties properties, ObjectMapper mapper) {
        this(Path.of(properties.getDiskCacheDir()).resolve("content-assistant"), mapper);
    }

    public AssistantStore(Path directory, ObjectMapper mapper) {
        this.db = directory.toAbsolutePath().resolve("assistant.sqlite");
        this.mapper = mapper;
    }

    public static ObjectNode object() { return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }

    private Path pendingReceiptDirectory(String channel) {
        if (!channel.matches("[a-z-]+")) throw new IllegalArgumentException("无效的回执类型");
        return db.getParent().resolve("pending-receipts").resolve(channel);
    }

    private Path pendingReceiptPath(String channel, String key) {
        return pendingReceiptDirectory(channel).resolve(AssistantWorkflow.digest(object().put("key", key)) + ".json");
    }

    synchronized void savePendingReceipt(String channel, String key, ObjectNode receipt) {
        Path file = pendingReceiptPath(channel, key);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".receipt-", ".tmp");
            try {
                Files.write(temp, mapper.writeValueAsBytes(receipt), StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) { throw failure(ex); }
    }

    synchronized ObjectNode pendingReceipt(String channel, String key) {
        Path file = pendingReceiptPath(channel, key);
        if (!Files.isRegularFile(file)) return null;
        try { return (ObjectNode) mapper.readTree(file.toFile()); }
        catch (IOException ex) { throw failure(ex); }
    }

    synchronized List<ObjectNode> pendingReceipts(String channel) {
        Path directory = pendingReceiptDirectory(channel);
        if (!Files.isDirectory(directory)) return List.of();
        List<ObjectNode> receipts = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) receipts.add((ObjectNode) mapper.readTree(file.toFile()));
            return receipts;
        } catch (IOException ex) { throw failure(ex); }
    }

    synchronized void removePendingReceipt(String channel, String key) {
        try { Files.deleteIfExists(pendingReceiptPath(channel, key)); }
        catch (IOException ex) { throw failure(ex); }
    }

    synchronized void recordPublicationFailure(String key, ObjectNode reservation, String error, boolean uncertain) {
        ObjectNode outcome = object().put("key", key).put("attempt", reservation.path("sendAttempt").asText())
                .put("status", uncertain ? "UNCERTAIN" : "FAILED").put("error", error);
        pendingPublicationFailures.put(key, outcome);
        try {
            restorePublicationFailure(key);
        } catch (RuntimeException unavailable) {
            savePendingReceipt("direct-outcome", key, outcome);
        }
    }

    synchronized void reconcilePublicationFailures() {
        for (var saved : pendingReceipts("direct-outcome"))
            pendingPublicationFailures.putIfAbsent(saved.path("key").asText(), saved);
        for (String key : List.copyOf(pendingPublicationFailures.keySet())) {
            try { restorePublicationFailure(key); }
            catch (RuntimeException unavailable) {
                log.warn("Could not restore publication outcome {}", key, unavailable);
            }
        }
    }

    synchronized void restorePublicationFailure(String key) {
        ObjectNode outcome = pendingPublicationFailures.get(key);
        if (outcome == null) return;
        tx(tx -> {
            var row = tx.get("publication", key);
            if (row != null && "SENDING".equals(row.path("status").asText())
                    && outcome.path("attempt").asText().equals(row.path("sendAttempt").asText())) {
                tx.put("publication", key, row.put("status", outcome.path("status").asText()).set("error", outcome.get("error")));
            }
            return null;
        });
        removePendingReceipt("direct-outcome", key);
        pendingPublicationFailures.remove(key);
    }

    record TopicCounts(long total, long recommended, long needsConfirmation, long drafted) { }

    public synchronized <T> T tx(Function<Tx,T> action) {
        try {
            if (!initialized) Files.createDirectories(db.getParent());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                registerEditorialMaterial(c);
                if (!initialized) initialize(c);
                c.setAutoCommit(false);
                try {
                    T result = action.apply(new Tx(c));
                    c.commit();
                    return result;
                } catch (RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        } catch (SQLException | IOException e) {
            throw failure(e);
        }
    }

    private void registerEditorialMaterial(Connection connection) throws SQLException {
        org.sqlite.Function.create(connection, "editorial_material", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                String selected = value_text(0);
                if (selected == null || selected.matches("[0-9]{2,20}")) {
                    result(selected);
                    return;
                }
                String original = value_text(1);
                String research = value_text(2);
                if ((original == null || original.isBlank()) && research != null) {
                    try {
                        var frozen = AssistantPublishing.frozenAuthorSource(selected, mapper.readTree(research));
                        result(frozen.assignedMaterial(selected, value_text(3)));
                        return;
                    } catch (IOException ex) { throw new SQLException("无法读取稿件来源", ex); }
                }
                result(AssistantPublishing.attributedMaterial(selected, original, value_text(3)));
            }
        }, 4, org.sqlite.Function.FLAG_DETERMINISTIC);
    }

    private void initialize(Connection c) throws SQLException, IOException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            int version;
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); version = r.getInt(1); }
            if (version > 1) throw new IllegalStateException("内容助手数据库版本高于应用版本");
            if (version == 0) {
                boolean existing;
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
                    existing = r.next() && r.getInt(1) > 0;
                }
                if (existing) backup(c, "pre-migration-" + System.currentTimeMillis());
                c.setAutoCommit(false);
                s.execute("CREATE TABLE revisions(kind TEXT NOT NULL,id TEXT NOT NULL,ver INTEGER NOT NULL,data TEXT NOT NULL, PRIMARY KEY(kind,id,ver))");
                s.execute("CREATE TABLE heads(kind TEXT NOT NULL,id TEXT NOT NULL,ver INTEGER NOT NULL,changed INTEGER NOT NULL,PRIMARY KEY(kind,id))");
                s.execute("CREATE INDEX by_kind_time ON heads(kind,changed DESC,id)");
                s.execute("PRAGMA user_version=1");
                c.commit();
                c.setAutoCommit(true);
            }
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rewrite_material_cache(
                        id TEXT PRIMARY KEY, author_id TEXT NOT NULL, published_at INTEGER NOT NULL,
                        topic_id TEXT NOT NULL, data TEXT NOT NULL)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS rewrite_author_time ON rewrite_material_cache(author_id,published_at)");
            s.execute("CREATE INDEX IF NOT EXISTS rewrite_expiry ON rewrite_material_cache(published_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS retained_topic_sources(
                        topic_id TEXT NOT NULL, ver INTEGER NOT NULL, material_id TEXT NOT NULL, PRIMARY KEY(topic_id, ver))
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS rewrite_final_text ON revisions(trim(json_extract(data,'$.rewrittenText'))) WHERE kind='auto_rewrite'");
            s.execute("CREATE INDEX IF NOT EXISTS rewrite_preview_text ON revisions(json_extract(data,'$.textDigest')) WHERE kind='rewrite_preview'");
            // Repair retained originals that an earlier collector left behind an updated window copy.
            c.setAutoCommit(false);
            try {
                Tx tx = new Tx(c);
                for (ObjectNode cached : tx.query("""
                        SELECT w.data FROM rewrite_material_cache w
                        WHERE EXISTS (SELECT 1 FROM heads h WHERE h.id=w.id AND h.kind IN ('material','auto_rewrite','auto_repost','rewrite_source'))
                        """)) {
                    String id = cached.path("id").asText();
                    ObjectNode regular = tx.get("material", id);
                    if (regular != null && !regular.path("authorId").equals(cached.path("authorId"))) continue;
                    tx.observeFinalRewriteSource(id, regular, cached);
                    tx.updateRetainedMaterial(cached);
                }
                // Older releases did not remember quote corrections on the publication record.
                for (ObjectNode regular : tx.query("""
                        SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                        WHERE h.kind='material' AND EXISTS (SELECT 1 FROM heads q WHERE q.kind='auto_repost' AND q.id=h.id)
                          AND NOT EXISTS (SELECT 1 FROM rewrite_material_cache w WHERE w.id=h.id)
                        """)) {
                    tx.observeQuoteSource(regular.path("id").asText(), null, regular);
                }
                c.commit();
            } catch (RuntimeException | SQLException ex) {
                c.rollback();
                throw ex;
            }
            initialized = true;
        }
    }

    public final class Tx {
        private final Connection c;
        Tx(Connection c) { this.c = c; }

        private List<ObjectNode> query(String sql, Object... args) {
            try (PreparedStatement p = c.prepareStatement(sql)) {
                bind(p,args);
                try (ResultSet r = p.executeQuery()) {
                    var out = new ArrayList<ObjectNode>();
                    while (r.next()) out.add((ObjectNode)mapper.readTree(r.getString(1)));
                    return out;
                }
            } catch (SQLException | IOException e) { throw failure(e); }
        }

        private void update(String sql,Object... args) {
            try (PreparedStatement p = c.prepareStatement(sql)) { bind(p,args); p.executeUpdate(); }
            catch (SQLException e) { throw failure(e); }
        }

        private static void bind(PreparedStatement p,Object[] args) throws SQLException {
            for (int i=0;i<args.length;i++) p.setObject(i+1,args[i]);
        }

        public ObjectNode get(String kind,String id) {
            return first(query("SELECT data FROM revisions WHERE kind=? AND id=? ORDER BY ver DESC LIMIT 1",kind,id));
        }

        void cacheRewriteMaterial(ObjectNode material, String topicId) {
            String id = material.path("id").asText();
            ObjectNode previous = first(query("SELECT data FROM rewrite_material_cache WHERE id=?", id));
            if (previous == null) previous = get("material", id);
            if (previous != null && !previous.path("authorId").equals(material.path("authorId"))) return;
            if (previous != null) {
                material = material.deepCopy();
                for (String field : List.of("views", "likes", "reposts", "replies", "bookmarks")) {
                    if (!material.hasNonNull(field) && previous.hasNonNull(field)) material.set(field, previous.get(field));
                }
            }
            observeFinalRewriteSource(id, previous, material);
            update("""
                    INSERT INTO rewrite_material_cache(id,author_id,published_at,topic_id,data) VALUES(?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET published_at=excluded.published_at, data=excluded.data,
                        topic_id=coalesce(nullif(excluded.topic_id,''),rewrite_material_cache.topic_id)
                    WHERE rewrite_material_cache.author_id=excluded.author_id
                      AND (rewrite_material_cache.data<>excluded.data
                        OR (excluded.topic_id<>'' AND rewrite_material_cache.topic_id<>excluded.topic_id))
                    """, material.path("id").asText(), material.path("authorId").asText(), material.path("publishedAt").asLong(),
                    topicId, material.toString());
            updateRetainedMaterial(material);
        }

        private void updateRetainedMaterial(ObjectNode observed) {
            String id = observed.path("id").asText();
            ObjectNode regular = get("material", id);
            if (regular == null || !regular.path("authorId").equals(observed.path("authorId"))) return;
            ObjectNode merged = regular.deepCopy();
            for (String field : List.of("username", "authorName", "authorBio", "authorAvatarUrl", "authorVerified", "authorFollowersCount",
                    "kind", "text", "quotedText", "articleText", "textStatus", "authorTextStatus", "quotedTextStatus", "articleTextStatus",
                    "quotedId", "quotedAuthorId", "quotedUsername", "articleId", "articleUrl", "articleRole", "sourceUrl", "publishedAt",
                    "hasMedia", "media", "links", "selfThreadReply", "archiveOnly", "views", "likes", "reposts", "replies", "bookmarks")) {
                if (observed.hasNonNull(field)) merged.set(field, observed.get(field));
            }
            if (!merged.equals(regular)) {
                observeFinalRewriteSource(id, regular, merged);
                merged.put("contentFingerprint", AssistantWorkflow.materialContentFingerprint(merged));
                writeRevision("material", id, merged);
            }
        }

        private void observeQuoteSource(String id, ObjectNode previous, ObjectNode material) {
            ObjectNode record = get("auto_repost", id);
            if (record == null || record.path("manual").asBoolean() || record.path("quoteSourceNeedsReview").asBoolean()
                    || "PUBLISHED".equals(record.path("status").asText())) return;
            if (!record.hasNonNull("quoteSourceFingerprint") && !record.hasNonNull("previewMaterialFingerprint")
                    && record.path("commentText").asText().isBlank()) return;
            if (record.path("quoteSourceFingerprint").asText().isBlank() && previous != null
                    && AutoRepostService.matchesEvaluatedQuote(this, record, previous))
                record.put("quoteSourceFingerprint", AutoRepostService.quoteSourceFingerprint(previous));
            if (!AutoRepostService.matchesEvaluatedQuote(this, record, material))
                put("auto_repost", id, record.put("quoteSourceNeedsReview", true));
        }

        private void observeFinalRewriteSource(String id, ObjectNode previous, ObjectNode material) {
            observePreviewSource(id, previous, material);
            observeQuoteSource(id, previous, material);
            ObjectNode record = get("auto_rewrite", id);
            if (record == null || record.path("rewriteSourceNeedsReview").asBoolean()
                    || "PUBLISHED".equals(record.path("status").asText())) return;
            String original = record.path("rewriteSourceFingerprint").asText();
            if (record.path("rewrittenText").asText().isBlank()
                    && (original.isBlank() || !"EVALUATING".equals(record.path("status").asText()))) return;
            if (original.isBlank() && previous != null && AutoRepostService.sameSavedPreviewInput(record.path("previewMaterialFingerprint").asText(), previous))
                original = AutoRepostService.rewriteSourceFingerprint(previous);
            boolean known = !original.isBlank() ? AutoRepostService.isPureTextRewriteCandidate(material)
                    && original.equals(AutoRepostService.rewriteSourceFingerprint(material))
                    : AutoRepostService.matchesFinalRewriteSource(this, record, material);
            if (!known) record.put("rewriteSourceNeedsReview", true);
            if (known && original.isBlank()) original = AutoRepostService.rewriteSourceFingerprint(material);
            if (!known || !original.equals(record.path("rewriteSourceFingerprint").asText())) {
                if (!original.isBlank()) record.put("rewriteSourceFingerprint", original);
                put("auto_rewrite", id, record);
            }
        }

        boolean retainedMaterialAuthorMatches(String materialId, String authorId) {
            if (authorId.isBlank()) return false;
            ObjectNode regular = get("material", materialId);
            if (regular != null && !SourceCollector.belongsToSource(regular, authorId)) return false;
            return first(query("SELECT data FROM rewrite_material_cache WHERE id=? AND author_id<>?", materialId, authorId)) == null;
        }

        ObjectNode rewriteMaterial(String materialId, long nowMs) {
            ObjectNode material = get("material", materialId);
            ObjectNode cached = first(query("""
                    SELECT json_set(data,'$.rewriteTopicId',topic_id) FROM rewrite_material_cache
                    WHERE id=? AND published_at BETWEEN ? AND ?
                    """, materialId, nowMs - AutoRepostService.REWRITE_MAX_AGE_MS, nowMs));
            return cached != null && (material == null || cached.path("authorId").equals(material.path("authorId"))) ? cached : material;
        }

        void expireRewriteMaterials(long cutoffMillis) {
            update("DELETE FROM rewrite_material_cache WHERE published_at < ?", cutoffMillis);
        }

        List<ObjectNode> rewriteWindow(String authorId, String username, long since, long until) {
            return query("""
                    SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                    WHERE h.kind='material' AND json_extract(r.data,'$.publishedAt') BETWEEN ? AND ?
                      AND ((?<>'' AND json_extract(r.data,'$.authorId')=?)
                        OR (?='' AND ltrim(json_extract(r.data,'$.username'),'@')=? COLLATE NOCASE))
                      AND NOT EXISTS (SELECT 1 FROM rewrite_material_cache w WHERE w.id=h.id
                        AND w.author_id=json_extract(r.data,'$.authorId') AND w.published_at BETWEEN ? AND ?)
                    UNION ALL
                    SELECT json_set(w.data,'$.rewriteTopicId',w.topic_id) FROM rewrite_material_cache w
                    WHERE ((?<>'' AND w.author_id=?) OR (?='' AND ltrim(json_extract(w.data,'$.username'),'@')=? COLLATE NOCASE))
                      AND w.published_at BETWEEN ? AND ? AND NOT EXISTS (
                        SELECT 1 FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind='material' AND h.id=w.id
                        AND coalesce(json_extract(r.data,'$.authorId'),'')<>w.author_id)
                    """, since, until, authorId, authorId, authorId, username, since, until,
                    authorId, authorId, authorId, username, since, until);
        }

        /** Older releases assigned ownership from a handle alone. Only the archive ID or an explicit
         * local binding is evidence of ownership. Keep raw revisions available for recovery. */
        public ObjectNode ownerBinding() {
            var row = get("meta", "owner");
            if (row == null) return null;
            String id = row.path("accountId").asText("");
            return !id.isBlank() && (AuthorIdentity.ARCHIVE_ACCOUNT_ID.equals(id)
                    || "EXPLICIT".equals(row.path("bindingSource").asText())) ? row : null;
        }

        boolean publicationOwnerMatches(ObjectNode row, String ownerId) {
            ObjectNode owner = ownerBinding();
            if (owner == null || !owner.path("accountId").asText().equals(ownerId)
                    || row == null || row.path("ownerReviewRequired").asBoolean()) return false;
            String recorded = row.path("ownerAccountId").asText("");
            if (!recorded.isBlank()) return ownerId.equals(recorded);
            return first(query("""
                    SELECT data FROM revisions WHERE kind='meta' AND id='owner'
                      AND coalesce(json_extract(data,'$.accountId'),'')<>? LIMIT 1
                    """, ownerId)) == null;
        }

        ObjectNode observeOwner(String id, String username, long observedAt) {
            var owner = ownerBinding();
            if (!AuthorIdentity.trustedId(owner == null ? null : owner.path("accountId").asText()).equals(id)) return null;
            if (username == null || username.isBlank()) return owner;
            if (owner == null) {
                if (get("meta", "owner") != null || first(query("""
                        SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                        WHERE h.kind IN ('publication','auto_repost','auto_rewrite')
                          AND coalesce(json_extract(r.data,'$.ownerAccountId'),'')='' LIMIT 1
                        """)) != null) pauseLegacyOwnerWork();
                owner = object().put("accountId", id);
            } else if (observedAt <= owner.path("usernameObservedAt").asLong(0L)) {
                return owner;
            }
            return put("meta", "owner", owner.put("username", username).put("usernameObservedAt", observedAt));
        }

        private void pauseLegacyOwnerWork() {
            // All settings, material and original revisions remain available. Re-enabling the
            // assistant must not also resume an old account's model or publication requests.
            put("meta", "settings", AssistantWorkflow.settings(this).put("enabled", false));
            for (var sample : matching("style_sample", "textRole", "AUTHOR", -1)) {
                if (sample.path("authorId").asText("").isBlank())
                    put("style_sample", sample.path("id").asText(), sample.put("textRole", "LEGACY_UNVERIFIED"));
            }
            if (get("meta", "style_status") != null) put("meta", "style_status", object());
            var style = get("meta", "style");
            if (style != null && !"OWNER".equals(style.path("editor").asText()))
                put("meta", "style", object().put("profile", ""));
            for (var source : all("source")) {
                if ("ACTIVE".equals(source.path("status").asText()))
                    put("source", source.path("id").asText(), source.put("status", "PAUSED")
                            .put("autoRepostEnabled", false).put("autoRewriteEnabled", false));
            }
            for (var task : all("task")) {
                if (Set.of("QUEUED", "RUNNING", "WAITING_STYLE", "WAITING_SAMPLES").contains(task.path("status").asText()))
                    put("task", task.path("id").asText(), task.put("status", "CANCELLED")
                            .put("error", "旧作者绑定无法核验，任务已暂停"));
            }
            for (String kind : List.of("auto_repost", "auto_rewrite")) {
                for (var row : all(kind)) {
                    String status = row.path("status").asText("");
                    if (row.path("ownerAccountId").asText("").isBlank()) {
                        row.put("ownerReviewRequired", true);
                        row.remove(List.of("autoRetryAt", "automaticRetryRunning", "deferredPendingEval"));
                        if ("SENDING".equals(status)) row.put("status", "UNCERTAIN");
                        else if (!Set.of("PUBLISHED", "UNCERTAIN").contains(status)) row.put("status", "OWNER_REVIEW_REQUIRED");
                        put(kind, row.path("id").asText(), row.put("error", "旧记录的发布账号无法确认"));
                        continue;
                    }
                    if (Set.of("PUBLISHED", "UNCERTAIN").contains(status)) continue;
                    if (!Set.of("SENDING", "EVALUATING", "RETRY_QUEUED", "AUTH_REQUIRED", "FAILED", "JUDGE_FAILED",
                            "SKIPPED_COOLDOWN", "SKIPPED_AUTHOR_COOLDOWN", "SKIPPED_QUIET_HOURS", "SKIPPED_LIMIT").contains(status)
                            && !row.has("autoRetryAt") && !row.path("deferredPendingEval").asBoolean()) continue;
                    row.remove(List.of("autoRetryAt", "automaticRetryRunning", "deferredPendingEval"));
                    put(kind, row.path("id").asText(), row.put("status", "SENDING".equals(status) ? "UNCERTAIN" : "OWNER_REVIEW_REQUIRED")
                            .put("error", "旧作者绑定无法核验，已保留记录，请重新核实发布意图"));
                }
            }
            for (var row : all("publication")) {
                if (row.path("ownerAccountId").asText("").isBlank() && AssistantPublishing.canVerify(row))
                    put("publication", row.path("id").asText(), row.put("ownerReviewRequired", true));
            }
        }

        public ObjectNode version(String kind,String id,int version) {
            return first(query("SELECT data FROM revisions WHERE kind=? AND id=? AND ver=?",kind,id,version));
        }

        public List<ObjectNode> versions(String kind,String id) {
            return query("SELECT data FROM revisions WHERE kind=? AND id=? ORDER BY ver DESC",kind,id);
        }

        public List<ObjectNode> all(String kind) { return page(kind,-1,0); }

        public List<ObjectNode> page(String kind,int limit,int offset) {
            return query("SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind=? ORDER BY h.changed DESC,h.id LIMIT ? OFFSET ?",kind,limit,offset);
        }

        List<ObjectNode> ownerActivity(String kind, String ownerId, int limit) {
            ObjectNode binding = ownerBinding();
            if (binding == null || !binding.path("accountId").asText().equals(ownerId)) return List.of();
            boolean includeUnattributed = publicationOwnerMatches(object(), ownerId);
            return query("""
                    SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                    WHERE h.kind=? AND coalesce(json_extract(r.data,'$.ownerReviewRequired'),0)=0
                      AND (json_extract(r.data,'$.ownerAccountId')=?
                        OR (? AND coalesce(json_extract(r.data,'$.ownerAccountId'),'')=''))
                    ORDER BY h.changed DESC,h.id LIMIT ?
                    """, kind, ownerId, includeUnattributed, limit);
        }

        long lastConfirmedSchedule(String accountId, long now) {
            ObjectNode row = first(query("""
                    SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                    WHERE h.kind='scheduled_delivery' AND json_extract(r.data,'$.ownerAccountId')=?
                      AND json_extract(r.data,'$.status')='PUBLISHED' AND json_extract(r.data,'$.publishedAt') BETWEEN 1 AND ?
                    ORDER BY json_extract(r.data,'$.publishedAt') DESC LIMIT 1
                    """, accountId, now));
            return row == null ? 0 : row.path("publishedAt").asLong();
        }

        List<ObjectNode> publicationsByStatus(String status) {
            return recordsByStatus("publication", status);
        }

        List<ObjectNode> recordsByStatus(String kind, String status) {
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind=? AND json_extract(r.data,'$.status')=? ORDER BY h.changed DESC,h.id
                """, kind, status);
        }

        List<ObjectNode> retryableAutomaticRecords(String kind) {
            return query("""
                    SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                    WHERE h.kind=? AND coalesce(json_extract(r.data,'$.manual'),0)=0 AND (
                        json_extract(r.data,'$.status') IN ('AUTH_REQUIRED','JUDGE_FAILED','FAILED','SKIPPED_COOLDOWN',
                            'SKIPPED_AUTHOR_COOLDOWN','SKIPPED_QUIET_HOURS')
                        OR (h.kind='auto_rewrite' AND json_extract(r.data,'$.status')='SKIPPED_LIMIT'))
                    ORDER BY h.changed DESC,h.id
                    """, kind);
        }

        List<ObjectNode> automaticReceiptsSince(long since) {
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='publication' AND (json_extract(r.data,'$.source') GLOB 'AUTO_*'
                    OR (json_extract(r.data,'$.source') IS NULL AND (h.id GLOB 'auto-repost-*' OR h.id GLOB 'auto-rewrite-*')))
                  AND json_extract(r.data,'$.status') IN ('PUBLISHED','SENDING','UNCERTAIN')
                  AND coalesce(json_extract(r.data,'$.publishedAt'),json_extract(r.data,'$.dispatchedAt'),0)>=?
                """, since);
        }

        List<ObjectNode> repostActivityForCooldown(long since) {
            var owner = ownerBinding();
            if (owner == null) return List.of();
            String ownerId = owner.path("accountId").asText();
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE (h.kind='auto_repost' OR (h.kind='publication' AND (json_extract(r.data,'$.source') GLOB 'MANUAL_*'
                    OR (json_extract(r.data,'$.source') GLOB 'AUTO_*' AND json_extract(r.data,'$.source') NOT GLOB 'AUTO_REWRITE*'))))
                  AND json_extract(r.data,'$.status') IN ('PUBLISHED','SENDING','UNCERTAIN')
                  AND coalesce(json_extract(r.data,'$.publishedAt'),json_extract(r.data,'$.dispatchedAt'),
                               json_extract(r.data,'$.createdAt'),json_extract(r.data,'$.sourcePublishedAt'),h.changed)>=?
                """, since).stream().filter(row -> publicationOwnerMatches(row, ownerId)).toList();
        }

        ObjectNode protectedPublicationByDigest(String digest, String ownerId) {
            return first(query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='publication' AND json_extract(r.data,'$.requestDigest')=?
                  AND json_extract(r.data,'$.status') IN ('SENDING','UNCERTAIN','PUBLISHED')
                  AND coalesce(json_extract(r.data,'$.ownerReviewRequired'),0)=0
                  AND (json_extract(r.data,'$.ownerAccountId')=? OR (? AND coalesce(json_extract(r.data,'$.ownerAccountId'),'')=''))
                ORDER BY (json_extract(r.data,'$.status')='PUBLISHED') DESC,h.changed DESC LIMIT 1
                """, digest, ownerId, publicationOwnerMatches(object(), ownerId)));
        }

        List<ObjectNode> retainedRewritesWithText(String text) {
            return query("""
                    SELECT data FROM (
                        SELECT data, row_number() OVER (PARTITION BY id,json_extract(data,'$.ownerAccountId') ORDER BY ver DESC) AS copy
                        FROM revisions WHERE kind='auto_rewrite' AND trim(json_extract(data,'$.rewrittenText'))=?
                    ) WHERE copy=1
                    UNION ALL
                    SELECT r.data FROM revisions r JOIN heads h USING(kind,id,ver)
                    WHERE r.kind='rewrite_preview' AND json_extract(r.data,'$.textDigest')=?
                    """, text.strip(), rewriteTextDigest(text));
        }

        private String rewriteTextDigest(String text) {
            return AssistantWorkflow.digest(object().put("text", text == null ? "" : text.strip()));
        }

        // A known correction must outlive the source retention window. Only its fingerprint is kept here.
        private void observePreviewSource(String id, ObjectNode previous, ObjectNode material) {
            ObjectNode saved = get("rewrite_source", id);
            String fingerprint = AutoRepostService.rewriteSourceFingerprint(material);
            if (saved == null) {
                if (previous == null) return;
                String before = AutoRepostService.rewriteSourceFingerprint(previous);
                if (before.equals(fingerprint)) return;
                saveRewriteSource(previous);
            } else if (fingerprint.equals(saved.path("fingerprint").asText())) return;
            saveRewriteSource(material);
        }

        private ObjectNode saveRewriteSource(ObjectNode material) {
            return put("rewrite_source", material.path("id").asText(), object()
                    .put("fingerprint", AutoRepostService.rewriteSourceFingerprint(material))
                    .put("pureText", AutoRepostService.isPureTextRewriteCandidate(material)));
        }

        ObjectNode rewriteSourceState(String id) {
            return rewriteSourceState(id, true);
        }

        ObjectNode lastObservedRewriteMaterial(String id) {
            ObjectNode material = get("material", id);
            ObjectNode cached = first(query("SELECT data FROM rewrite_material_cache WHERE id=?", id));
            return cached != null && (material == null || material.path("authorId").equals(cached.path("authorId"))) ? cached : material;
        }

        private ObjectNode rewriteSourceState(String id, boolean keepUnchanged) {
            ObjectNode saved = get("rewrite_source", id);
            if (saved != null) return saved;
            // On upgrade, reconstruct known corrections from the original's retained revisions.
            ObjectNode material = lastObservedRewriteMaterial(id);
            if (material == null) return null;
            var history = versions("material", id);
            String current = AutoRepostService.rewriteSourceFingerprint(material);
            if (!keepUnchanged && history.stream().allMatch(row -> current.equals(AutoRepostService.rewriteSourceFingerprint(row))))
                return null;
            for (int i = history.size() - 1; i >= 0; i--) {
                String fingerprint = AutoRepostService.rewriteSourceFingerprint(history.get(i));
                if (saved == null || !saved.path("fingerprint").asText().equals(fingerprint))
                    saved = saveRewriteSource(history.get(i));
            }
            if (saved == null || !saved.path("fingerprint").asText().equals(current))
                saved = saveRewriteSource(material);
            return saved;
        }

        ObjectNode rewritePreviewWithText(String owner, String material, String text) {
            return first(query("""
                    SELECT data FROM revisions WHERE kind='rewrite_preview' AND json_extract(data,'$.textDigest')=?
                      AND json_extract(data,'$.ownerAccountId')=? AND json_extract(data,'$.materialId')=?
                    ORDER BY json_extract(data,'$.sourceVersion') DESC LIMIT 1
                    """, rewriteTextDigest(text), owner, material));
        }

        String rememberRewritePreview(String owner, String material, String text, int sourceVersion) {
            String digest = rewriteTextDigest(text);
            String key = owner + ":" + material + ":" + sourceVersion + ":" + digest;
            if (get("rewrite_preview", key) == null)
                put("rewrite_preview", key, object().put("ownerAccountId", owner).put("materialId", material)
                        .put("textDigest", digest).put("sourceVersion", sourceVersion));
            return key;
        }

        List<ObjectNode> sourceQuoteRows(Set<String> ids, String ownerId) {
            ObjectNode owner = ownerBinding();
            if (ids.isEmpty() || owner == null || !owner.path("accountId").asText().equals(ownerId)) return List.of();
            return query("""
                WITH wanted AS (SELECT value FROM json_each(?))
                SELECT json_set(data,'$.receiptRevision',r.rowid,'$.quoteProjection',h.kind='auto_repost')
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE coalesce(json_extract(data,'$.ownerReviewRequired'),0)=0
                  AND (json_extract(data,'$.ownerAccountId')=? OR (? AND coalesce(json_extract(data,'$.ownerAccountId'),'')=''))
                  AND (h.kind='auto_repost' OR (h.kind='publication' AND h.id NOT GLOB 'auto-rewrite-*'
                       AND coalesce(json_extract(data,'$.source'),'') NOT GLOB 'AUTO_REWRITE*'
                       AND (json_extract(data,'$.status') IN ('PUBLISHED','SENDING','UNCERTAIN','FAILED')
                            OR json_extract(data,'$.uncertainDelivery')=1 OR json_extract(data,'$.verifiedAt') IS NOT NULL)
                       AND (json_extract(data,'$.source') GLOB 'MANUAL_*' OR h.id GLOB 'auto-repost-*'
                            OR json_extract(data,'$.source')='DIRECT_QUOTE_PUBLISH'
                            OR coalesce(nullif(json_extract(data,'$.requestedQuoteTweetId'),''),
                                nullif(json_extract(data,'$.quoteTweetId'),''),nullif(json_extract(data,'$.quotedTweetId'),'')) IS NOT NULL)))
                  AND ((h.kind='auto_repost' AND h.id IN wanted) OR json_extract(data,'$.materialId') IN wanted
                       OR (h.id GLOB 'auto-repost-*' AND substr(h.id,13) IN wanted)
                       OR json_extract(data,'$.requestedQuoteTweetId') IN wanted
                       OR json_extract(data,'$.quoteTweetId') IN wanted OR json_extract(data,'$.quotedTweetId') IN wanted)
                ORDER BY r.rowid
                """, mapper.valueToTree(ids).toString(), ownerId, publicationOwnerMatches(object(), ownerId));
        }

        String publicationTopic(ObjectNode row, String materialId) {
            String origin = row == null ? "" : row.path("originTopicId").asText(row.path("topicId").asText(row.path("rewriteTopicId").asText("")));
            if (materialId != null && !materialId.isBlank()) {
                String current = AssistantTopics.topicOf(this, materialId);
                if (!current.isBlank()) return current;
                var archived = ids("SELECT topic_id FROM rewrite_material_cache WHERE id=? AND topic_id<>''", materialId);
                if (!archived.isEmpty()) origin = archived.get(0);
            }
            if (origin.isBlank()) return "";
            var destination = ids(TOPIC_LINEAGE + " SELECT destination FROM lineage WHERE origin=?", origin);
            return destination.isEmpty() ? origin : destination.get(0);
        }

        String frozenTopicMaterial(String topicId, int version) {
            if (topicId.isBlank() || version < 1) return "";
            var row = version("topic", topicId, version);
            if (row != null) {
                var members = row.path("materialIds");
                return members.size() == 1 ? members.get(0).asText("") : "";
            }
            var archived = ids("SELECT material_id FROM retained_topic_sources WHERE topic_id=? AND ver=?", topicId, version);
            return archived.isEmpty() ? "" : archived.get(0);
        }

        private static final String UNRESOLVED_SEND = """
                (json_extract(data,'$.status') IN ('SENDING','UNCERTAIN') OR json_extract(data,'$.uncertainDelivery')=1)
                """;

        ObjectNode matchingQuoteOrRewrite(String kind, String materialId, String originalId, String topicId, boolean includePublished) {
            return matchingQuoteOrRewrite(kind, materialId, originalId, topicId, includePublished, "", "");
        }

        ObjectNode matchingQuoteOrRewrite(String kind, String materialId, String originalId, String topicId,
                                          boolean includePublished, String excludeRecordId) {
            return matchingQuoteOrRewrite(kind, materialId, originalId, topicId, includePublished, "", excludeRecordId);
        }

        private ObjectNode matchingQuoteOrRewrite(String kind, String materialId, String originalId, String topicId,
                                                  boolean includePublished, String ownJob, String excludeRecordId) {
            ObjectNode binding = ownerBinding();
            if (binding == null) return null;
            String ownerId = binding.path("accountId").asText();
            return first(query(EDITORIAL_TOPICS + """
                SELECT data FROM assigned
                WHERE (kind=? OR (?='publication' AND kind='scheduled_delivery'))
                  AND coalesce(json_extract(data,'$.ownerReviewRequired'),0)=0
                  AND (json_extract(data,'$.ownerAccountId')=? OR
                       (coalesce(json_extract(data,'$.ownerAccountId'),'')='' AND NOT EXISTS (
                           SELECT 1 FROM revisions r WHERE r.kind='meta' AND r.id='owner'
                             AND coalesce(json_extract(r.data,'$.accountId'),'')<>?)))
                  AND (?='' OR kind<>? OR id<>?)
                  AND (kind<>'publication' OR json_extract(data,'$.jobId') IS NULL OR json_extract(data,'$.status')='PUBLISHED')
                  AND (?='' OR coalesce(json_extract(data,'$.jobId'),'')<>?)
                  AND (kind NOT IN ('publication','scheduled_delivery') OR json_extract(data,'$.source') GLOB 'MANUAL_*'
                       OR json_extract(data,'$.source') GLOB 'AUTO_*' OR json_extract(data,'$.source')='DIRECT_QUOTE_PUBLISH'
                       OR nullif(json_extract(data,'$.requestedQuoteTweetId'),'') IS NOT NULL
                       OR (json_extract(data,'$.source') IS NULL AND (id GLOB 'auto-repost-*' OR id GLOB 'auto-rewrite-*')))
                  AND ((kind IN ('auto_rewrite','auto_repost') AND id IN (nullif(?,''),nullif(?,'')))
                       OR material IN (nullif(?,''),nullif(?,''))
                       OR (kind='publication' AND id IN ('auto-repost-'||?,'auto-rewrite-'||?))
                       OR (coalesce(nullif(json_extract(data,'$.requestedQuoteTweetId'),''),
                                    nullif(json_extract(data,'$.quoteTweetId'),''),json_extract(data,'$.quotedTweetId'))=nullif(?,''))
                       OR currentTopic=nullif(?,''))
                  AND (""" + UNRESOLVED_SEND + """
                       OR (? AND json_extract(data,'$.status')='PUBLISHED'))
                ORDER BY (json_extract(data,'$.status')='PUBLISHED') DESC LIMIT 1
                """, kind, kind, ownerId, ownerId, excludeRecordId, kind, excludeRecordId, ownJob, ownJob, materialId, originalId, materialId, originalId,
                    materialId, materialId, originalId, topicId, includePublished));
        }

        void requireNoPendingQuoteOrRewrite(String materialId, String originalId, String topicId) {
            requireNoPendingQuoteOrRewrite(materialId, originalId, topicId, "", "");
        }

        void requireNoPendingQuoteOrRewrite(String materialId, String originalId, String topicId, String ownJob, String ownPublication) {
            for (String table : List.of("auto_repost", "auto_rewrite", "publication")) {
                String exclude = "publication".equals(table) ? ownPublication : "";
                if (matchingQuoteOrRewrite(table, materialId, originalId, topicId, false, ownJob, exclude) != null)
                    throw new XDataProviderException(409, "该原帖或选题正在发布或回执待核实，请勿重复提交");
            }
        }

        /** Compare both the submitted payload and its provenance across the queue and the assistant. */
        ObjectNode conflictingDelivery(String draftId, String quoteId, String text, List<String> media,
                                      String ownKind, String ownId, long queuedAt) {
            return conflictingDelivery(draftId, 0, quoteId, text, media, ownKind, ownId, queuedAt);
        }

        ObjectNode conflictingDelivery(String draftId, int draftVersion, String quoteId, String text, List<String> media,
                                      String ownKind, String ownId, long queuedAt) {
            String body = text == null ? "" : text.strip();
            if (body.isEmpty() && media.isEmpty()) return null;
            var binding = ownerBinding();
            if (binding == null) return null;
            var mediaDigests = mapper.valueToTree(media);
            String ownerId = binding.path("accountId").asText();
            var candidates = query("""
                    WITH deliveries AS (
                        SELECT kind,id,data,json_extract(data,'$.status') AS status,
                          coalesce(nullif(json_extract(data,'$.requestedText'),''),nullif(json_extract(data,'$.rewrittenText'),''),
                                   nullif(json_extract(data,'$.commentText'),''),json_extract(data,'$.text'),'') AS body
                        FROM heads JOIN revisions USING(kind,id,ver)
                        WHERE kind IN ('publication','scheduled_delivery','auto_repost','auto_rewrite')
                    )
                    SELECT json_object('record',json(data),'kind',kind) FROM deliveries
                    WHERE (status IN ('SENDING','UNCERTAIN','PUBLISHED') OR json_extract(data,'$.uncertainDelivery')=1)
                      AND (kind<>'publication' OR json_extract(data,'$.jobId') IS NULL OR status='PUBLISHED')
                      AND NOT (kind=? AND id=?)
                      AND (?<>'scheduled_delivery' OR ?='' OR coalesce(json_extract(data,'$.jobId'),'')<>?)
                      AND (instr(body,?)>0 OR instr(json_extract(data,'$.text'),?)>0
                           OR json_extract(data,'$.draftId')=nullif(?,''))
                    """, ownKind, ownId, ownKind, ownId, ownId, body, body, draftId);
            for (var hit : candidates) {
                var row = (ObjectNode) hit.get("record");
                if (!publicationOwnerMatches(row, ownerId)) continue;
                boolean published = "PUBLISHED".equals(row.path("status").asText());
                String otherDraft = row.path("draftId").asText("");
                boolean sameDraft = !otherDraft.isBlank() && otherDraft.equals(draftId);
                if (!published && sameDraft) return row;
                String otherBody = firstField(row, "requestedText", "rewrittenText", "commentText", "text");
                boolean matchesOutgoing = row.hasNonNull("text") && body.equals(row.path("text").asText().strip());
                boolean matchesRequested = body.equals(otherBody.strip());
                if (!matchesOutgoing && !matchesRequested) continue;
                boolean matchesFallback = matchesOutgoing && !matchesRequested;
                if (row.hasNonNull("mediaDigests") && !row.get("mediaDigests").equals(mediaDigests)) continue;
                String otherQuote = firstField(row, "requestedQuoteTweetId", "quoteTweetId", "quotedTweetId");
                if (otherQuote.isEmpty() && "auto_repost".equals(hit.path("kind").asText())) otherQuote = row.path("id").asText();
                boolean bothQuoted = quoteId != null && !quoteId.isBlank() && !otherQuote.isBlank();
                if (bothQuoted && !quoteId.equals(otherQuote)) continue;
                boolean sameOriginal = false;
                if (!bothQuoted && !sameDraft && draftId != null && !draftId.isBlank() && !otherDraft.isBlank()) {
                    String original = draftOriginal(draftId, draftVersion);
                    sameOriginal = !original.isEmpty() && original.equals(draftOriginal(otherDraft, row.path("draftVersion").asInt()));
                    if (!sameOriginal && !matchesFallback) continue;
                }
                boolean plainQueueToDraft = "scheduled_delivery".equals(hit.path("kind").asText())
                        && otherDraft.isBlank() && draftId != null && !draftId.isBlank();
                if (!published || sameDraft || bothQuoted || sameOriginal || plainQueueToDraft || matchesFallback
                        || row.hasNonNull("verifiedAt") || row.path("receiptRecovered").asBoolean()
                        || queuedAt > 0 && row.path("publishedAt").asLong() >= queuedAt) return row;
            }
            return null;
        }

        private String draftOriginal(String draftId, int revision) {
            ObjectNode draft = revision > 0 ? version("draft", draftId, revision) : get("draft", draftId);
            if (draft == null) return "";
            var original = AssistantPublishing.draftSourcePost(this, draft);
            return original == null ? "" : original.tweetId();
        }

        static String firstField(ObjectNode row, String... fields) {
            for (String field : fields) {
                String value = row.path(field).asText("");
                if (!value.isBlank()) return value;
            }
            return "";
        }

        private static String deliveryIdentity(String kind, ObjectNode row) {
            String id = row.path("id").asText();
            String job = row.path("jobId").asText("");
            if (!job.isBlank()) return "queue:" + job;
            return switch (kind) {
                case "scheduled_delivery", "scheduled_verification" -> "queue:" + id;
                case "auto_repost" -> "publication:auto-repost-" + id;
                case "auto_rewrite" -> "publication:auto-rewrite-" + id;
                default -> "publication:" + id;
            };
        }

        void requireAvailablePostId(String kind, ObjectNode target, String tweetId) {
            String identity = deliveryIdentity(kind, target);
            // A returned platform ID also belongs to its sender while its SQLite commit is being recovered.
            for (var pending : pendingReceipts("direct-accepted")) {
                if (tweetId.equals(pending.path("receipt").path("publishedTweetId").asText())
                        && !identity.equals("publication:" + pending.path("key").asText()))
                    throw new XDataProviderException(409, "该帖子 ID 已由另一条发布记录接收，正在补记回执");
            }
            var claims = query("""
                    SELECT json_object('kind',kind,'record',json(data)) FROM heads JOIN revisions USING(kind,id,ver)
                    WHERE kind IN ('publication','scheduled_delivery','scheduled_verification','auto_repost','auto_rewrite')
                      AND (json_extract(data,'$.publishedTweetId')=? OR json_extract(data,'$.acceptedReceipt.publishedTweetId')=?
                           OR (kind='scheduled_verification' AND json_extract(data,'$.decision.tweetId')=?
                               AND json_extract(data,'$.decision.published')=1))
                    """, tweetId, tweetId, tweetId);
            for (var claim : claims) {
                if (!identity.equals(deliveryIdentity(claim.path("kind").asText(), (ObjectNode)claim.get("record"))))
                    throw new XDataProviderException(409, "该帖子 ID 已登记在另一条发布记录，请核对");
            }
            ObjectNode observation = get("observation_target", tweetId);
            if (observation != null && !observation.path("publicationId").asText("").isBlank()) {
                String pub = observation.path("publicationId").asText();
                ObjectNode row = get("publication", pub);
                String credited = row == null ? "publication:" + pub : deliveryIdentity("publication", row);
                if (!identity.equals(credited)) throw new XDataProviderException(409, "该帖子 ID 已归属另一条发布记录");
            }
        }

        void removeQueueVerification(String id) {
            deleteByIds("scheduled_verification", List.of(id));
        }

        List<ObjectNode> publicationsPendingObservation(int limit) {
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='publication' AND trim(coalesce(json_extract(r.data,'$.publishedTweetId'),''))<>''
                  AND json_extract(r.data,'$.publishedAt')>0
                  AND (json_extract(r.data,'$.observationLinkedTweetId') IS NOT json_extract(r.data,'$.publishedTweetId')
                    OR NOT EXISTS (SELECT 1 FROM heads t WHERE t.kind='observation_target' AND t.id=json_extract(r.data,'$.publishedTweetId')))
                ORDER BY h.changed,h.id LIMIT ?
                """,limit);
        }

        List<ObjectNode> after(String kind,long updatedAt,String id,int limit) {
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind=? AND (h.changed>? OR (h.changed=? AND h.id>?))
                ORDER BY h.changed,h.id LIMIT ?
                """,kind,updatedAt,updatedAt,id,limit);
        }

        public long count(String kind) {
            try(var p=c.prepareStatement("SELECT count(*) FROM heads WHERE kind=?")){
                p.setString(1,kind); try(var r=p.executeQuery()){r.next(); return r.getLong(1);}
            } catch(SQLException e){throw failure(e);}
        }

        long countHistory(String kind, String accountId) {
            String sql = """
                    SELECT count(*) FROM heads h JOIN revisions r USING(kind,id,ver)
                    WHERE h.kind=? AND CASE h.kind
                        WHEN 'history_material' THEN json_extract(r.data,'$.authorId')=?
                        WHEN 'history_interaction' THEN coalesce(nullif(json_extract(r.data,'$.targetAuthorId'),''),
                            substr(h.id,1,instr(h.id,':')-1))=?
                    END
                    """;
            try (PreparedStatement p = c.prepareStatement(sql)) {
                bind(p, new Object[]{kind, accountId, accountId});
                try (ResultSet r = p.executeQuery()) { r.next(); return r.getLong(1); }
            } catch (SQLException e) { throw failure(e); }
        }

        // An empty merged topic belongs to its live destination, including chains of later merges.
        private static final String TOPIC_LINEAGE = """
                WITH RECURSIVE lineage(origin, destination) AS (
                    SELECT h.id,h.id FROM heads h JOIN revisions r USING(kind,id,ver)
                    WHERE h.kind='topic' AND EXISTS (SELECT 1 FROM json_each(r.data,'$.materialIds') m
                        JOIN heads live ON live.kind='material' AND live.id=m.value)
                    UNION
                    SELECT h.id,l.destination FROM heads h JOIN revisions r USING(kind,id,ver)
                    JOIN lineage l ON l.origin=json_extract(r.data,'$.mergedInto')
                    WHERE h.kind='topic' AND json_array_length(r.data,'$.materialIds')=0
                )
                """;
        // The accepted topic and evidence snapshots are immutable. Use the selected material's
        // live membership for display and retention, including receipts pinned to a draft revision.
        private static final String EDITORIAL_RECORDS = """
                selected_records AS (
                    SELECT h.kind,h.id,h.changed,r.data,json_extract(r.data,'$.topicId') AS origin,
                           coalesce(json_extract(r.data,'$.research'),rs.data,json_extract(d.data,'$.research'),ds.data) AS frozenResearch,
                           coalesce(nullif(json_extract(r.data,'$.materialId'),''),
                                    nullif(json_extract(r.data,'$.input.selectedMaterialId'),''),
                                    nullif(json_extract(r.data,'$.research.materialId'),''),
                                    nullif(json_extract(r.data,'$.research.snapshot.selectedMaterialId'),''),
                                    nullif(json_extract(rs.data,'$.materialId'),''),
                                    nullif(json_extract(rs.data,'$.snapshot.selectedMaterialId'),''),
                                    CASE WHEN json_array_length(r.data,'$.research.snapshot.materials')=1
                                         THEN nullif(json_extract(r.data,'$.research.snapshot.materials[0].id'),'') END,
                                    CASE WHEN json_array_length(rs.data,'$.snapshot.materials')=1
                                         THEN nullif(json_extract(rs.data,'$.snapshot.materials[0].id'),'') END,
                                    CASE WHEN coalesce(json_array_length(r.data,'$.research.snapshot.materials'),0)<2
                                              AND coalesce(json_array_length(rs.data,'$.snapshot.materials'),0)<2
                                         THEN coalesce(CASE WHEN json_array_length(rt.data,'$.materialIds')=1
                                                       THEN nullif(json_extract(rt.data,'$.materialIds[0]'),'') END, rtOld.material_id) END,
                                    nullif(json_extract(p.data,'$.materialId'),''),
                                    nullif(json_extract(d.data,'$.materialId'),''),
                                    nullif(json_extract(d.data,'$.research.materialId'),''),
                                    nullif(json_extract(d.data,'$.research.snapshot.selectedMaterialId'),''),
                                    nullif(json_extract(ds.data,'$.materialId'),''),
                                    nullif(json_extract(ds.data,'$.snapshot.selectedMaterialId'),''),
                                    CASE WHEN json_array_length(d.data,'$.research.snapshot.materials')=1
                                         THEN nullif(json_extract(d.data,'$.research.snapshot.materials[0].id'),'') END,
                                    CASE WHEN json_array_length(ds.data,'$.snapshot.materials')=1
                                         THEN nullif(json_extract(ds.data,'$.snapshot.materials[0].id'),'') END,
                                    CASE WHEN coalesce(json_array_length(d.data,'$.research.snapshot.materials'),0)<2
                                              AND coalesce(json_array_length(ds.data,'$.snapshot.materials'),0)<2
                                         THEN coalesce(CASE WHEN json_array_length(dt.data,'$.materialIds')=1
                                                       THEN nullif(json_extract(dt.data,'$.materialIds[0]'),'') END, dtOld.material_id) END,
                                    CASE WHEN h.kind IN ('auto_repost','auto_rewrite') THEN h.id END,
                                    nullif(json_extract(r.data,'$.requestedQuoteTweetId'),'')) AS selected
                    FROM heads h JOIN revisions r USING(kind,id,ver)
                    LEFT JOIN heads sh ON sh.kind='research' AND sh.id=json_extract(r.data,'$.researchId')
                    LEFT JOIN revisions rs ON rs.kind=sh.kind AND rs.id=sh.id AND rs.ver=sh.ver
                    LEFT JOIN heads ph ON ph.kind='publication' AND ph.id=json_extract(r.data,'$.publicationId')
                    LEFT JOIN revisions p ON p.kind=ph.kind AND p.id=ph.id AND p.ver=ph.ver
                    LEFT JOIN heads dh ON dh.kind='draft' AND dh.id=coalesce(nullif(json_extract(r.data,'$.draftId'),''),json_extract(p.data,'$.draftId'))
                    LEFT JOIN revisions d ON d.kind=dh.kind AND d.id=dh.id
                        AND d.ver=coalesce(nullif(json_extract(r.data,'$.draftVersion'),0),nullif(json_extract(p.data,'$.draftVersion'),0),dh.ver)
                    LEFT JOIN heads dsh ON dsh.kind='research' AND dsh.id=json_extract(d.data,'$.researchId')
                    LEFT JOIN revisions ds ON ds.kind=dsh.kind AND ds.id=dsh.id AND ds.ver=dsh.ver
                    LEFT JOIN revisions rt ON h.kind IN ('draft','task') AND rt.kind='topic'
                        AND rt.id=json_extract(r.data,'$.topicId') AND rt.ver=json_extract(r.data,'$.topicVersion')
                    LEFT JOIN retained_topic_sources rtOld ON h.kind IN ('draft','task')
                        AND rtOld.topic_id=json_extract(r.data,'$.topicId') AND rtOld.ver=json_extract(r.data,'$.topicVersion')
                    LEFT JOIN revisions dt ON dt.kind='topic' AND dt.id=json_extract(d.data,'$.topicId') AND dt.ver=json_extract(d.data,'$.topicVersion')
                    LEFT JOIN retained_topic_sources dtOld ON dtOld.topic_id=json_extract(d.data,'$.topicId') AND dtOld.ver=json_extract(d.data,'$.topicVersion')
                ), editorial AS (
                    SELECT s.kind,s.id,s.changed,s.data,s.origin,
                           editorial_material(s.selected,json_extract(s.data,'$.requestedQuoteTweetId'),s.frozenResearch,
                                              json_extract(live.data,'$.sourceUrl')) AS material
                    FROM selected_records s
                    LEFT JOIN heads mh ON mh.kind='material' AND mh.id=s.selected
                    LEFT JOIN revisions live ON live.kind=mh.kind AND live.id=mh.id AND live.ver=mh.ver
                )
                """;
        private static final String EDITORIAL_TOPICS = TOPIC_LINEAGE + "," + EDITORIAL_RECORDS + """
                , assigned AS (
                    SELECT e.*,coalesce(nullif(json_extract(m.data,'$.topicId'),''),l.destination,nullif(w.topic_id,''),e.origin) AS currentTopic
                    FROM editorial e
                    LEFT JOIN heads mh ON mh.kind='membership' AND mh.id=e.material
                    LEFT JOIN revisions m ON m.kind=mh.kind AND m.id=mh.id AND m.ver=mh.ver
                    LEFT JOIN rewrite_material_cache w ON w.id=e.material
                    LEFT JOIN lineage l ON l.origin=coalesce(nullif(w.topic_id,''),e.origin)
                )
                """;
        private static final String TOPIC_HAS_DRAFT =
                "h.id IN (SELECT currentTopic FROM assigned WHERE kind='draft')";

        private static final String DISPLAY_EDITORIAL_RECORDS = EDITORIAL_TOPICS + """
                SELECT CASE WHEN currentTopic IS origin THEN data
                            ELSE json_set(data,'$.originTopicId',origin,'$.topicId',currentTopic) END
                FROM assigned
                """;

        List<ObjectNode> topicRecords(String kind, String topicId, int limit, int offset) {
            String filter = topicId == null ? "" : " AND currentTopic=?";
            String sql = DISPLAY_EDITORIAL_RECORDS + "WHERE kind=?" + filter + " ORDER BY changed DESC,id LIMIT ? OFFSET ?";
            return topicId == null ? query(sql,kind,limit,offset) : query(sql,kind,topicId,limit,offset);
        }

        List<ObjectNode> topicDetailPublications(String topicId) {
            return query(DISPLAY_EDITORIAL_RECORDS + """
                    WHERE kind='publication' AND (currentTopic=? OR (
                        json_extract(data,'$.status')='SCHEDULED' AND coalesce(json_extract(data,'$.deleted'),0)=0
                        AND json_extract(data,'$.draftId') IN (SELECT id FROM assigned WHERE kind='draft' AND currentTopic=?)
                    )) ORDER BY changed DESC,id
                    """, topicId, topicId);
        }

        ObjectNode displayTopic(String kind, ObjectNode row) {
            if (row == null) return null;
            var topics = ids(EDITORIAL_TOPICS + "SELECT currentTopic FROM assigned WHERE kind=? AND id=? AND currentTopic IS NOT NULL",
                    kind,row.path("id").asText());
            String origin = row.path("topicId").asText("");
            if (!topics.isEmpty() && !topics.get(0).equals(origin)) {
                row.put("originTopicId",origin).put("topicId",topics.get(0));
            }
            return row;
        }

        Map<String,String> mergedTopics() {
            var merged = new LinkedHashMap<String,String>();
            try (var p = c.prepareStatement(TOPIC_LINEAGE + "SELECT origin,destination FROM lineage WHERE origin<>destination");
                 var rows = p.executeQuery()) {
                while (rows.next()) merged.put(rows.getString(1),rows.getString(2));
            } catch (SQLException error) { throw failure(error); }
            return merged;
        }

        TopicCounts topicCounts(int threshold) {
            String sql = EDITORIAL_TOPICS + """
                SELECT count(*),
                       count(CASE WHEN json_extract(r.data,'$.score')>=? THEN 1 END),
                       count(CASE WHEN json_extract(r.data,'$.status')='NEEDS_CONFIRMATION'
                                    OR json_extract(r.data,'$.needsConfirmation')=1 THEN 1 END),
                       count(CASE WHEN %s THEN 1 END)
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='topic' AND json_array_length(r.data,'$.materialIds')>0
                """.formatted(TOPIC_HAS_DRAFT);
            try (var p = c.prepareStatement(sql)) {
                p.setInt(1, threshold);
                try (var r = p.executeQuery()) {
                    r.next();
                    return new TopicCounts(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4));
                }
            } catch (SQLException e) { throw failure(e); }
        }

        List<ObjectNode> topics(int size,int offset,String q,String username,String status,String sort) {
            return topics(size, offset, q, username, status, "", 80, sort);
        }

        List<ObjectNode> topics(int size,int offset,String q,String username,String status,String tag,int threshold,String sort) {
            String latestPublished = """
                coalesce(json_extract(r.data,'$.publishedAt'),
                    (SELECT max(json_extract(mv.data,'$.publishedAt')) FROM json_each(r.data,'$.materialIds') mids
                     JOIN heads mh ON mh.kind='material' AND mh.id=mids.value
                     JOIN revisions mv ON mv.kind=mh.kind AND mv.id=mh.id AND mv.ver=mh.ver),
                    h.changed)
                """;
            String order = switch (sort) {
                case "score" -> "json_extract(r.data,'$.score') DESC,";
                case "value" -> "json_extract(r.data,'$.valueScore') DESC,";
                case "reach" -> "json_extract(r.data,'$.reachScore') DESC,";
                case "published" -> latestPublished + " DESC,";
                default -> "";
            };
            String authorMatch = """
                EXISTS(SELECT 1 FROM json_each(r.data,'$.materialIds') ids
                       JOIN heads m ON m.kind='material' AND m.id=ids.value
                       JOIN revisions v ON v.kind=m.kind AND v.id=m.id AND v.ver=m.ver
                       WHERE instr(lower(json_extract(v.data,'$.username')),lower(?))>0)
                """;
            String tagMatch = """
                EXISTS(SELECT 1 FROM json_each(r.data,'$.tags') t
                       WHERE instr(lower(t.value),lower(?))>0)
                """;
            String statusFilter = switch (status == null ? "" : status.strip().toUpperCase(Locale.ROOT)) {
                case "" -> "1=1";
                case "RECOMMENDED" -> "json_extract(r.data,'$.score')>=" + threshold;
                case "NEEDS_CONFIRMATION" -> "(json_extract(r.data,'$.status')='NEEDS_CONFIRMATION' OR json_extract(r.data,'$.needsConfirmation')=1)";
                case "DRAFTED" -> TOPIC_HAS_DRAFT;
                default -> "json_extract(r.data,'$.status')=?";
            };
            String normalizedStatus = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
            boolean bindStatusParam = !normalizedStatus.isEmpty()
                    && !"RECOMMENDED".equals(normalizedStatus)
                    && !"NEEDS_CONFIRMATION".equals(normalizedStatus)
                    && !"DRAFTED".equals(normalizedStatus);
            String sql=("DRAFTED".equals(normalizedStatus) ? EDITORIAL_TOPICS : "") + """
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='topic' AND json_array_length(r.data,'$.materialIds')>0
                AND (?='' OR instr(lower(json_extract(r.data,'$.title')),lower(?))>0
                          OR instr(lower(json_extract(r.data,'$.summary')),lower(?))>0
                          OR instr(lower(json_extract(r.data,'$.tags')),lower(?))>0
                          OR (?<>'' AND %s))
                AND (?='' OR %s)
                AND (?='' OR %s)
                AND %s
                ORDER BY %sh.changed DESC,h.id LIMIT ? OFFSET ?
                """.formatted(authorMatch, authorMatch, tagMatch, statusFilter, order);
            String search = q == null ? "" : q.strip();
            String authorQuery = withoutAt(search);
            String author = withoutAt(username == null ? "" : username.strip());
            String cleanTag = tag == null ? "" : tag.strip();
            if (bindStatusParam) {
                return query(sql,search,search,search,search,authorQuery,authorQuery,author,author,cleanTag,cleanTag,normalizedStatus,size,offset);
            }
            return query(sql,search,search,search,search,authorQuery,authorQuery,author,author,cleanTag,cleanTag,size,offset);
        }

        private static String withoutAt(String username) {
            return username.startsWith("@") ? username.substring(1) : username;
        }

        List<ObjectNode> materials(int size,int offset,String q,String username,String status) {
            return materials(size, offset, q, username, status, "updated");
        }

        List<ObjectNode> materials(int size,int offset,String q,String username,String status,String sort) {
            String order;
            if ("engagement".equalsIgnoreCase(sort)) {
                order = "(coalesce(json_extract(r.data,'$.reposts'),0)*8 + coalesce(json_extract(r.data,'$.replies'),0)*6 + coalesce(json_extract(r.data,'$.likes'),0) + coalesce(json_extract(r.data,'$.views'),0)/100) DESC,coalesce(json_extract(r.data,'$.publishedAt'),h.changed) DESC,h.id";
            } else if ("published".equalsIgnoreCase(sort)) {
                order = "coalesce(json_extract(r.data,'$.publishedAt'),h.changed) DESC,h.id";
            } else {
                order = "h.changed DESC,h.id";
            }
            String cleanUser = withoutAt(username == null ? "" : username.strip());
            String cleanQuery = q == null ? "" : q.strip();
            String cleanStatus = status == null ? "" : status.strip();
            if ("HIGH_SIGNAL".equalsIgnoreCase(cleanStatus)) {
                return query("""
                    SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind='material'
                    AND (?='' OR instr(lower(coalesce(json_extract(r.data,'$.text'),'')||coalesce(json_extract(r.data,'$.quotedText'),'')||
                        coalesce(json_extract(r.data,'$.articleText'),'')),lower(?))>0
                        OR instr(lower(coalesce(json_extract(r.data,'$.username'),'')),lower(?))>0
                        OR instr(lower(coalesce(json_extract(r.data,'$.authorName'),'')),lower(?))>0)
                    AND (?='' OR instr(lower(json_extract(r.data,'$.username')),lower(?))>0)
                    AND coalesce(json_extract(r.data,'$.textStatus'),'') NOT IN ('NO_ANALYZABLE_TEXT','MEDIA_ONLY','MEDIA_URL_ONLY')
                    AND (length(trim(coalesce(json_extract(r.data,'$.articleText'),'')))>0
                        OR length(trim(coalesce(json_extract(r.data,'$.quotedText'),'')))>0
                        OR length(coalesce(json_extract(r.data,'$.text'),''))>=120)
                    ORDER BY %s LIMIT ? OFFSET ?
                    """.formatted(order),cleanQuery,cleanQuery,withoutAt(cleanQuery),cleanQuery,cleanUser,cleanUser,size,offset);
            }
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind='material'
                AND (?='' OR instr(lower(coalesce(json_extract(r.data,'$.text'),'')||coalesce(json_extract(r.data,'$.quotedText'),'')||
                    coalesce(json_extract(r.data,'$.articleText'),'')),lower(?))>0
                    OR instr(lower(coalesce(json_extract(r.data,'$.username'),'')),lower(?))>0
                    OR instr(lower(coalesce(json_extract(r.data,'$.authorName'),'')),lower(?))>0)
                AND (?='' OR instr(lower(json_extract(r.data,'$.username')),lower(?))>0)
                AND (?='' OR json_extract(r.data,'$.textStatus')=?)
                ORDER BY %s LIMIT ? OFFSET ?
                """.formatted(order),cleanQuery,cleanQuery,withoutAt(cleanQuery),cleanQuery,cleanUser,cleanUser,cleanStatus,cleanStatus,size,offset);
        }

        public List<ObjectNode> matching(String kind,String field,String value,int limit) {
            return query("SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind=? AND json_extract(r.data,?)=? ORDER BY h.changed,h.id LIMIT ?",kind,"$."+field,value,limit);
        }

        List<ObjectNode> legacyTaskInputs(String kind, String scopeField, String scopeId) {
            return query("""
                SELECT first.data FROM revisions first
                JOIN heads h ON h.kind=first.kind AND h.id=first.id
                JOIN revisions current ON current.kind=h.kind AND current.id=h.id AND current.ver=h.ver
                WHERE first.kind='task' AND first.ver=1 AND json_extract(first.data,'$.kind')=?
                  AND (json_extract(first.data,'$.fingerprintRules') IS NULL
                       OR (json_extract(current.data,'$.inputFingerprint') IS NULL
                           AND json_extract(first.data,'$.settings') IS NOT json_extract(current.data,'$.settings')))
                  AND (?='' OR json_extract(first.data,?)=?)
                ORDER BY json_extract(first.data,'$.createdAt'),first.id
                """,kind,scopeId,"$."+scopeField,scopeId);
        }

        private ObjectNode first(List<ObjectNode> rows) { return rows.isEmpty()?null:rows.get(0); }

        private List<String> ids(String sql, Object... args) {
            try (PreparedStatement p = c.prepareStatement(sql)) {
                bind(p, args);
                try (ResultSet r = p.executeQuery()) {
                    var out = new ArrayList<String>();
                    while (r.next()) out.add(r.getString(1));
                    return out;
                }
            } catch (SQLException e) { throw failure(e); }
        }

        private int deleteByIds(String kind, Collection<String> ids) {
            for (String id : ids) {
                update("DELETE FROM heads WHERE kind=? AND id=?", kind, id);
                update("DELETE FROM revisions WHERE kind=? AND id=?", kind, id);
            }
            return ids.size();
        }

        List<String> purgeExpiredContent(long cutoffMillis, long rewriteCutoffMillis) {
            expireRewriteMaterials(rewriteCutoffMillis);
            int changes = 0;
            List<String> expiredMaterials = ids("""
                SELECT h.id
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='material'
                  AND coalesce(
                      CASE WHEN typeof(json_extract(r.data,'$.ownerImportedAt'))='integer'
                            AND json_extract(r.data,'$.ownerImportedAt')>0
                           THEN json_extract(r.data,'$.ownerImportedAt') END,
                      CASE WHEN EXISTS (
                          SELECT 1 FROM heads mh JOIN revisions mv USING(kind,id,ver)
                          WHERE mh.kind='membership' AND mh.id=h.id AND json_extract(mv.data,'$.actor')='OWNER_LINK'
                      ) THEN coalesce(json_extract(r.data,'$.collectedAt'),h.changed) END,
                      CASE WHEN typeof(json_extract(r.data,'$.publishedAt'))='integer'
                            AND json_extract(r.data,'$.publishedAt')>0
                           THEN json_extract(r.data,'$.publishedAt') END,
                      CASE WHEN typeof(json_extract(r.data,'$.collectedAt'))='integer'
                            AND json_extract(r.data,'$.collectedAt')>0
                           THEN json_extract(r.data,'$.collectedAt') END,
                      h.changed
                  ) < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM heads mh JOIN revisions mv USING(kind,id,ver)
                      WHERE mh.kind='membership' AND mh.id=h.id
                        AND typeof(json_extract(mv.data,'$.ownerImportedAt'))='integer'
                        AND json_extract(mv.data,'$.ownerImportedAt')>=?
                  )
                """, cutoffMillis, cutoffMillis);
            for (String id : expiredMaterials) {
                ObjectNode material = get("material", id);
                if (material.path("version").asInt() > 1 && id.matches("[0-9]+"))
                    rewriteSourceState(id, false);
                if (material.path("publishedAt").asLong() >= rewriteCutoffMillis
                        && get("source", material.path("authorId").asText()) != null) {
                    String topicId = AssistantTopics.topicOf(this, id);
                    update("""
                            INSERT INTO rewrite_material_cache(id,author_id,published_at,topic_id,data) VALUES(?,?,?,?,?)
                            ON CONFLICT(id) DO UPDATE SET topic_id=excluded.topic_id
                            WHERE rewrite_material_cache.author_id=excluded.author_id AND excluded.topic_id<>''
                            """, id, material.path("authorId").asText(), material.path("publishedAt").asLong(), topicId, material.toString());
                }
            }
            changes += deleteByIds("material", expiredMaterials);
            changes += deleteByIds("membership", expiredMaterials);

            record TopicCandidate(String id, long changed, ObjectNode data) { }
            var topicCandidates = new ArrayList<TopicCandidate>();
            try (PreparedStatement p = c.prepareStatement(TOPIC_LINEAGE + """
                SELECT h.id, h.changed, r.data
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='topic'
                  AND (
                      EXISTS (
                          SELECT 1 FROM json_each(r.data,'$.materialIds') m
                          WHERE NOT EXISTS (SELECT 1 FROM heads mh WHERE mh.kind='material' AND mh.id=m.value)
                      )
                      OR (
                          coalesce(json_array_length(r.data,'$.materialIds'),0)=0
                          AND NOT EXISTS (SELECT 1 FROM lineage WHERE origin=h.id AND destination<>origin)
                          AND coalesce(
                              CASE WHEN typeof(json_extract(r.data,'$.publishedAt'))='integer'
                                    AND json_extract(r.data,'$.publishedAt')>0
                                   THEN json_extract(r.data,'$.publishedAt') END,
                              h.changed
                          ) < ?
                      )
                  )
                """)) {
                p.setLong(1, cutoffMillis);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        topicCandidates.add(new TopicCandidate(
                                r.getString(1), r.getLong(2), (ObjectNode) mapper.readTree(r.getString(3))));
                    }
                }
            } catch (SQLException | IOException e) { throw failure(e); }

            List<String> deletedTopics = new ArrayList<>();
            for (TopicCandidate candidate : topicCandidates) {
                ObjectNode topic = candidate.data();
                var rawMembers = topic.path("materialIds");
                int originalCount = rawMembers.isArray() ? rawMembers.size() : 0;
                List<String> kept = new ArrayList<>();
                if (rawMembers.isArray()) {
                    for (var item : rawMembers) {
                        String mid = item.asText("");
                        if (!mid.isBlank() && get("material", mid) != null && !kept.contains(mid)) {
                            kept.add(mid);
                        }
                    }
                }
                long topicTime = topic.path("publishedAt").asLong(0) > 0
                        ? topic.path("publishedAt").asLong() : candidate.changed();
                if (kept.isEmpty() && (originalCount > 0 || topicTime < cutoffMillis)) {
                    deletedTopics.add(candidate.id());
                } else if (kept.size() != originalCount) {
                    var arr = topic.putArray("materialIds");
                    kept.forEach(arr::add);
                    topic.remove("publishedAt");
                    AssistantTopics.persist(this, candidate.id(), topic);
                    changes++;
                }
            }
            for (String id : deletedTopics) {
                // A live material may have split out of a topic whose remaining members just expired.
                update("""
                        INSERT OR IGNORE INTO retained_topic_sources(topic_id,ver,material_id)
                        SELECT id,ver,json_extract(data,'$.materialIds[0]') FROM revisions r
                        WHERE kind='topic' AND id=? AND json_array_length(data,'$.materialIds')=1
                          AND EXISTS (SELECT 1 FROM heads h WHERE h.kind='material' AND h.id=json_extract(r.data,'$.materialIds[0]'))
                        """, id);
            }
            changes += deleteByIds("topic", deletedTopics);
            update("DELETE FROM retained_topic_sources WHERE NOT EXISTS (SELECT 1 FROM heads h WHERE h.kind='material' AND h.id=material_id)");

            List<String> orphanMemberships = ids("""
                SELECT h.id
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='membership'
                  AND (
                      NOT EXISTS (SELECT 1 FROM heads mh WHERE mh.kind='material' AND mh.id=h.id)
                      OR NOT EXISTS (SELECT 1 FROM heads th WHERE th.kind='topic' AND th.id=json_extract(r.data,'$.topicId'))
                  )
                """);
            changes += deleteByIds("membership", orphanMemberships);

            var dirtyTerms = new LinkedHashMap<String, ObjectNode>();
            try (PreparedStatement p = c.prepareStatement("""
                SELECT h.id, r.data
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='topicTerm'
                  AND EXISTS (
                      SELECT 1 FROM json_each(r.data,'$.ids') t
                      WHERE NOT EXISTS (SELECT 1 FROM heads th WHERE th.kind='topic' AND th.id=t.value)
                  )
                """);
                 ResultSet r = p.executeQuery()) {
                while (r.next()) dirtyTerms.put(r.getString(1), (ObjectNode) mapper.readTree(r.getString(2)));
            } catch (SQLException | IOException e) { throw failure(e); }
            for (var entry : dirtyTerms.entrySet()) {
                List<String> aliveIds = new ArrayList<>();
                for (var tidNode : entry.getValue().path("ids")) {
                    String tid = tidNode.asText("");
                    if (!tid.isBlank() && get("topic", tid) != null && !aliveIds.contains(tid)) aliveIds.add(tid);
                }
                if (aliveIds.isEmpty()) {
                    changes += deleteByIds("topicTerm", List.of(entry.getKey()));
                } else {
                    var updated = object();
                    var arr = updated.putArray("ids");
                    aliveIds.forEach(arr::add);
                    put("topicTerm", entry.getKey(), updated);
                    changes++;
                }
            }

            List<String> expiredDrafts = ids("WITH " + EDITORIAL_RECORDS + """
                SELECT e.id FROM editorial e WHERE e.kind='draft'
                  AND (
                      (e.material IS NOT NULL AND NOT EXISTS (SELECT 1 FROM heads WHERE kind='material' AND id=e.material))
                      OR (e.material IS NULL AND coalesce(e.origin,'')<>''
                          AND NOT EXISTS (SELECT 1 FROM heads WHERE kind='topic' AND id=e.origin))
                      OR (e.material IS NULL AND coalesce(e.origin,'')='' AND e.changed < ?)
                  )
                """, cutoffMillis);
            changes += deleteByIds("draft", expiredDrafts);

            List<String> expiredTasks = ids("WITH " + EDITORIAL_RECORDS + """
                SELECT e.id FROM editorial e WHERE e.kind='task'
                  AND coalesce(json_extract(e.data,'$.kind'),'') NOT IN ('STYLE','COLLECT_STYLE','VERIFY')
                  AND (
                      (e.material IS NOT NULL AND NOT EXISTS (SELECT 1 FROM heads WHERE kind='material' AND id=e.material))
                      OR (e.material IS NULL AND coalesce(e.origin,'')<>''
                          AND NOT EXISTS (SELECT 1 FROM heads WHERE kind='topic' AND id=e.origin))
                      OR (coalesce(json_extract(e.data,'$.draftId'),'')<>''
                          AND NOT EXISTS (SELECT 1 FROM heads WHERE kind='draft' AND id=json_extract(e.data,'$.draftId')))
                      OR (e.material IS NULL AND coalesce(e.origin,'')=''
                          AND coalesce(json_extract(e.data,'$.draftId'),'')=''
                          AND coalesce(
                              CASE WHEN typeof(json_extract(e.data,'$.createdAt'))='integer'
                                    AND json_extract(e.data,'$.createdAt')>0
                                   THEN json_extract(e.data,'$.createdAt') END,
                              e.changed
                          ) < ?)
                  )
                """, cutoffMillis);
            changes += deleteByIds("task", expiredTasks);

            List<String> expiredResearch = ids("""
                WITH referenced(id) AS (
                    SELECT id FROM heads WHERE kind='task'
                    UNION ALL SELECT json_extract(v.data,'$.input.research.id') FROM heads t JOIN revisions v USING(kind,id,ver)
                        WHERE t.kind='task'
                    UNION ALL SELECT coalesce(nullif(json_extract(data,'$.researchId'),''),json_extract(data,'$.research.id'))
                        FROM revisions WHERE kind='draft'
                )
                SELECT h.id FROM heads h WHERE h.kind='research'
                    AND h.id NOT IN (SELECT id FROM referenced WHERE id IS NOT NULL)
                """);
            changes += deleteByIds("research", expiredResearch);

            List<String> expiredFingerprints = ids("""
                SELECT h.id
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='fingerprint'
                  AND NOT EXISTS (SELECT 1 FROM heads th WHERE th.kind='task' AND th.id=json_extract(r.data,'$.taskId'))
                """);
            changes += deleteByIds("fingerprint", expiredFingerprints);

            List<String> expiredAccepts = ids("""
                SELECT h.id
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='accept'
                  AND NOT EXISTS (SELECT 1 FROM heads th WHERE th.kind='task' AND th.id=json_extract(r.data,'$.taskId'))
                """);
            changes += deleteByIds("accept", expiredAccepts);

            List<String> expiredMergeDecisions = ids("""
                SELECT h.id
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='merge_decision'
                  AND (
                      (coalesce(json_extract(r.data,'$.materialId'),'')<>''
                       AND NOT EXISTS (SELECT 1 FROM heads mh WHERE mh.kind='material' AND mh.id=json_extract(r.data,'$.materialId')))
                      OR (coalesce(json_extract(r.data,'$.topicId'),'')<>''
                          AND NOT EXISTS (SELECT 1 FROM heads th WHERE th.kind='topic' AND th.id=json_extract(r.data,'$.topicId')))
                      OR h.changed < ?
                  )
                """, cutoffMillis);
            changes += deleteByIds("merge_decision", expiredMergeDecisions);

            List<String> oldEvidenceSources = ids(
                    "SELECT id FROM heads WHERE kind='evidence_source' AND changed < ?", cutoffMillis);
            if (!oldEvidenceSources.isEmpty()) {
                Set<String> referencedSources = new HashSet<>();
                for (ObjectNode research : all("research")) {
                    for (var read : research.path("sourceReads")) {
                        String url = AssistantEvidence.normalizedUrl(read.path("url").asText(""));
                        if (!url.isBlank()) referencedSources.add(AssistantEvidence.sourceId(url));
                    }
                }
                List<String> unreferenced = oldEvidenceSources.stream()
                        .filter(id -> !referencedSources.contains(id)).toList();
                changes += deleteByIds("evidence_source", unreferenced);
            }

            changes += deleteByIds("scheduled_delivery", ids("""
                    SELECT h.id FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind='scheduled_delivery'
                      AND h.changed<? AND json_extract(r.data,'$.settled')=1
                      AND (json_extract(r.data,'$.status')<>'PUBLISHED' OR EXISTS (
                          SELECT 1 FROM heads ph JOIN revisions pr ON pr.kind=ph.kind AND pr.id=ph.id AND pr.ver=ph.ver
                          WHERE ph.kind='publication' AND json_extract(pr.data,'$.jobId')=h.id
                            AND json_extract(pr.data,'$.status')='PUBLISHED' AND nullif(json_extract(pr.data,'$.publishedTweetId'),'') IS NOT NULL
                            AND (json_extract(r.data,'$.verifiedAt') IS NULL OR json_extract(pr.data,'$.verifiedAt') IS NOT NULL)))
                    """, cutoffMillis));

            List<String> expiredAutoReposts = ids("""
                SELECT h.id
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='auto_repost'
                  AND coalesce(json_extract(r.data,'$.status'),'') NOT IN ('SENDING','UNCERTAIN','EVALUATING','RETRY_QUEUED','OWNER_REVIEW_REQUIRED')
                  AND NOT (json_extract(r.data,'$.status') IN ('FAILED','SKIPPED_QUIET_HOURS','SKIPPED_COOLDOWN','SKIPPED_AUTHOR_COOLDOWN')
                           AND coalesce(json_extract(r.data,'$.autoRetryAt'),0)>0)
                  AND (coalesce(json_extract(r.data,'$.status'),'')<>'PUBLISHED' OR (
                      coalesce(json_extract(r.data,'$.publishedAt'),json_extract(r.data,'$.dispatchedAt'),h.changed) < ? AND EXISTS (
                      SELECT 1 FROM heads ph JOIN revisions pr ON pr.kind=ph.kind AND pr.id=ph.id AND pr.ver=ph.ver
                      WHERE ph.kind='publication' AND json_extract(pr.data,'$.status')='PUBLISHED'
                        AND ((ph.id='auto-repost-'||h.id AND coalesce(json_extract(r.data,'$.manual'),0)=0)
                          OR (json_extract(r.data,'$.publishedTweetId')<>'' AND
                              json_extract(pr.data,'$.publishedTweetId')=json_extract(r.data,'$.publishedTweetId')))
                  )))
                  AND coalesce(
                      CASE WHEN typeof(json_extract(r.data,'$.sourcePublishedAt'))='integer'
                            AND json_extract(r.data,'$.sourcePublishedAt')>0
                           THEN json_extract(r.data,'$.sourcePublishedAt') END,
                      CASE WHEN typeof(json_extract(r.data,'$.createdAt'))='integer'
                            AND json_extract(r.data,'$.createdAt')>0
                           THEN json_extract(r.data,'$.createdAt') END,
                      h.changed
                  ) < ?
                """, cutoffMillis, cutoffMillis);
            changes += deleteByIds("auto_repost", expiredAutoReposts);

            if (changes > 0) {
                update("DELETE FROM revisions WHERE NOT EXISTS (SELECT 1 FROM heads h WHERE h.kind=revisions.kind AND h.id=revisions.id)");
            }
            return expiredTasks;
        }

        private ObjectNode writeRevision(String kind,String id,ObjectNode data) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("记录缺少标识");
            long now = System.currentTimeMillis();
            update("INSERT INTO heads(kind,id,ver,changed) VALUES(?,?,1,?) ON CONFLICT(kind,id) DO UPDATE SET ver=ver+1,changed=excluded.changed",kind,id,now);
            var result = data.deepCopy().put("id",id).put("updatedAt",now);
            try (PreparedStatement p = c.prepareStatement("SELECT ver FROM heads WHERE kind=? AND id=?")) {
                bind(p,new Object[]{kind,id});
                try (ResultSet r = p.executeQuery()) { r.next(); result.put("version",r.getInt(1)); }
            } catch (SQLException e) { throw failure(e); }
            update("INSERT INTO revisions(kind,id,ver,data) VALUES(?,?,?,?)",kind,id,result.path("version").asInt(),result.toString());
            return result;
        }

        public ObjectNode put(String kind,String id,ObjectNode data) {
            ObjectNode result = writeRevision(kind, id, data);
            if ("material".equals(kind)) {
                ObjectNode cached = first(query("SELECT data FROM rewrite_material_cache WHERE id=?", id));
                ObjectNode before = version(kind, id, result.path("version").asInt() - 1);
                if (cached == null) observeFinalRewriteSource(id, before, result);
                if (cached != null && cached.path("authorId").equals(result.path("authorId"))) {
                    if (before == null) cacheRewriteMaterial(result, "");
                    else {
                        ObjectNode changed = cached.deepCopy();
                        Set<String> fields = new HashSet<>();
                        before.fieldNames().forEachRemaining(fields::add);
                        result.fieldNames().forEachRemaining(fields::add);
                        fields.removeAll(Set.of("version", "updatedAt", "collectedAt", "lastCollectedAt", "ownerImportedAt", "contentFingerprint"));
                        for (String field : fields) {
                            if (before.path(field).equals(result.path(field))) continue;
                            if (result.has(field)) changed.set(field, result.get(field)); else changed.remove(field);
                        }
                        if (!changed.equals(cached)) cacheRewriteMaterial(changed, "");
                    }
                }
            }
            return result;
        }
    }

    public boolean authorize(AuthenticatedXUser user) {
        return user != null && isOwner(user.id());
    }

    public void observeOAuthOwner(AuthenticatedXUser user) {
        if (user != null && user.loginAt() != null)
            tx(t -> t.observeOwner(user.id(), user.username(), user.loginAt().toEpochMilli()));
    }

    public boolean isOwner(String id) {
        return id != null && !id.isBlank() && AuthorIdentity.trustedId(boundOwnerId()).equals(id);
    }

    public Set<String> pendingScheduledJobs(String owner) {
        return tx(tx -> new HashSet<>(tx.ids("""
                SELECT h.id FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind='scheduled_delivery'
                  AND json_extract(r.data,'$.ownerAccountId')=? AND coalesce(json_extract(r.data,'$.settled'),0)=0
                """, owner)));
    }

    public boolean hasOwnerBinding() {
        return !boundOwnerId().isBlank();
    }

    public String boundOwnerId() {
        return tx(tx -> {
            var owner = tx.ownerBinding();
            return owner == null ? "" : owner.path("accountId").asText("");
        });
    }

    public List<String> purgeExpiredContent(long cutoffMillis) {
        return purgeExpiredContent(cutoffMillis, System.currentTimeMillis() - AutoRepostService.REWRITE_MAX_AGE_MS);
    }

    List<String> purgeExpiredContent(long cutoffMillis, long rewriteCutoffMillis) {
        if (!Files.isRegularFile(db)) return List.of();
        return tx(t -> t.purgeExpiredContent(cutoffMillis, rewriteCutoffMillis));
    }

    @Scheduled(cron="0 12 3 * * *",zone="Asia/Shanghai")
    public synchronized void backupDaily() throws SQLException,IOException {
        if (!Files.isRegularFile(db)) return;
        var date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+db)) { backup(c,date.toString()); }
        try (var stream=Files.list(db.getParent().resolve("backups"))) {
            for (Path old:stream.filter(p->p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.sqlite")).sorted(Comparator.reverseOrder()).skip(7).toList()) Files.delete(old);
        }
    }

    private void backup(Connection c,String name) throws SQLException,IOException {
        Path dir=Files.createDirectories(db.getParent().resolve("backups"));
        Path target=dir.resolve(name+".sqlite");
        if (Files.exists(target)) return;
        Path tmp=Files.createTempFile(dir,"snapshot-",".tmp");
        try {
            try (PreparedStatement s=c.prepareStatement("VACUUM INTO ?")) { s.setString(1,tmp.toString()); s.execute(); }
            Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static final long MIN_COLLECTION_BYTES=16L*1024*1024;

    public boolean hasCollectionSpace() {
        try { return Files.getFileStore(db.getParent()).getUsableSpace()>=MIN_COLLECTION_BYTES; }
        catch(IOException e){throw failure(e);}
    }

    public ObjectNode storage() {
        try {
            Path backupDir=db.getParent().resolve("backups");
            String backup="";
            if (Files.isDirectory(backupDir)) try (var files=Files.list(backupDir)) {
                backup=files.map(p->p.getFileName().toString()).filter(s->s.endsWith(".sqlite")).sorted(Comparator.reverseOrder()).findFirst().orElse("");
            }
            return object().put("databaseBytes",Files.size(db)).put("usableBytes",Files.getFileStore(db).getUsableSpace()).put("lastBackup",backup).put("collectionSpaceAvailable",hasCollectionSpace());
        } catch (IOException e) { throw failure(e); }
    }

    private static IllegalStateException failure(Exception cause) {
        return new IllegalStateException("内容助手持久化失败，请检查数据目录和磁盘空间",cause);
    }
}
