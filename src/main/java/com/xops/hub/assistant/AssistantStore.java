package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
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
    private final ObjectMapper mapper;
    private final Path db;
    private boolean initialized;

    @Autowired
    public AssistantStore(XOpsProperties properties, ObjectMapper mapper) {
        this(Path.of(properties.getDiskCacheDir()).resolve("content-assistant"), mapper);
    }

    public AssistantStore(Path directory, ObjectMapper mapper) {
        this.db = directory.toAbsolutePath().resolve("assistant.sqlite");
        this.mapper = mapper;
    }

    public static ObjectNode object() { return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }

    record TopicCounts(long total, long recommended, long needsConfirmation, long drafted) { }

    public synchronized <T> T tx(Function<Tx,T> action) {
        try {
            if (!initialized) Files.createDirectories(db.getParent());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
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

        TopicCounts topicCounts(int threshold) {
            String sql = """
                SELECT count(*),
                       count(CASE WHEN json_extract(r.data,'$.score')>=? THEN 1 END),
                       count(CASE WHEN json_extract(r.data,'$.status')='NEEDS_CONFIRMATION'
                                    OR json_extract(r.data,'$.needsConfirmation')=1 THEN 1 END),
                       count(CASE WHEN EXISTS(
                           SELECT 1 FROM heads dh JOIN revisions dr USING(kind,id,ver)
                           WHERE dh.kind='draft' AND json_extract(dr.data,'$.topicId')=h.id
                       ) THEN 1 END)
                FROM heads h JOIN revisions r USING(kind,id,ver)
                WHERE h.kind='topic' AND json_array_length(r.data,'$.materialIds')>0
                """;
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
                case "DRAFTED" -> "EXISTS(SELECT 1 FROM heads dh JOIN revisions dr USING(kind,id,ver) WHERE dh.kind='draft' AND json_extract(dr.data,'$.topicId')=h.id)";
                default -> "json_extract(r.data,'$.status')=?";
            };
            String normalizedStatus = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
            boolean bindStatusParam = !normalizedStatus.isEmpty()
                    && !"RECOMMENDED".equals(normalizedStatus)
                    && !"NEEDS_CONFIRMATION".equals(normalizedStatus)
                    && !"DRAFTED".equals(normalizedStatus);
            String sql="""
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
            String order = "published".equalsIgnoreCase(sort)
                    ? "coalesce(json_extract(r.data,'$.publishedAt'),h.changed) DESC,h.id"
                    : "h.changed DESC,h.id";
            String cleanUser = withoutAt(username == null ? "" : username.strip());
            String cleanQuery = q == null ? "" : q.strip();
            String cleanStatus = status == null ? "" : status.strip();
            return query("""
                SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind='material'
                AND (?='' OR instr(lower(coalesce(json_extract(r.data,'$.text'),'')||coalesce(json_extract(r.data,'$.quotedText'),'')||
                    coalesce(json_extract(r.data,'$.articleText'),'')),lower(?))>0
                    OR instr(lower(coalesce(json_extract(r.data,'$.username'),'')),lower(?))>0)
                AND (?='' OR instr(lower(json_extract(r.data,'$.username')),lower(?))>0)
                AND (?='' OR json_extract(r.data,'$.textStatus')=?)
                ORDER BY %s LIMIT ? OFFSET ?
                """.formatted(order),cleanQuery,cleanQuery,withoutAt(cleanQuery),cleanUser,cleanUser,cleanStatus,cleanStatus,size,offset);
        }

        public List<ObjectNode> matching(String kind,String field,String value,int limit) {
            return query("SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind=? AND json_extract(r.data,?)=? ORDER BY h.changed,h.id LIMIT ?",kind,"$."+field,value,limit);
        }

        List<ObjectNode> legacyTaskInputs(String kind, String scopeField, String scopeId) {
            return query("""
                SELECT data FROM revisions
                WHERE kind='task' AND ver=1 AND json_extract(data,'$.kind')=?
                  AND json_extract(data,'$.fingerprintRules') IS NULL
                  AND (?='' OR json_extract(data,?)=?)
                ORDER BY json_extract(data,'$.createdAt'),id
                """,kind,scopeId,"$."+scopeField,scopeId);
        }

        private ObjectNode first(List<ObjectNode> rows) { return rows.isEmpty()?null:rows.get(0); }

        public ObjectNode put(String kind,String id,ObjectNode data) {
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
    }

    public boolean authorize(AuthenticatedXUser user) {
        if (user == null || user.id()==null || user.id().isBlank()) return false;
        return tx(t -> {
            var owner = t.get("meta","owner");
            if (owner == null) {
                if (!"elidlicom".equalsIgnoreCase(user.username())) return false;
                owner = t.put("meta","owner",object().put("accountId",user.id()).put("username",user.username()));
            }
            if (!user.id().equals(owner.path("accountId").asText())) return false;
            if (user.username()!=null && !user.username().equals(owner.path("username").asText()))
                t.put("meta","owner",owner.put("username",user.username()));
            return true;
        });
    }

    public boolean isOwner(String id) {
        if (id==null || id.isBlank()) return false;
        return tx(t -> { var o=t.get("meta","owner"); return o!=null && id.equals(o.path("accountId").asText()); });
    }

    public boolean hasOwnerBinding() {
        return tx(t -> {
            var owner=t.get("meta","owner");
            return owner!=null && !owner.path("accountId").asText("").isBlank();
        });
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
