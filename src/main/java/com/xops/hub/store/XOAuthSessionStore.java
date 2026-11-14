package com.xops.hub.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 持久化浏览器登录会话记录（AES-256-GCM 加密落盘，最长 400 天滑动续期，支持服务端重启/崩溃恢复）。
 */
@Component
public class XOAuthSessionStore {

    public static final long MAX_SESSION_LIFETIME_SECONDS = 400L * 24 * 3600;
    public static final long SLIDE_THRESHOLD_SECONDS = 24L * 3600;
    private static final long RESTORE_GRACE_SECONDS = 60L;
    private static final long CLEANUP_INTERVAL_SECONDS = 3600L;
    private static final String CIPHER_PREFIX = "s1:";
    private static final String FILE_SUFFIX = ".enc";

    public record StoredAuthUser(
            String id,
            String username,
            String displayName,
            String avatarUrl,
            boolean verified,
            long loginAtEpochMs
    ) {
        public static StoredAuthUser from(AuthenticatedXUser user) {
            long loginMs = user.loginAt() != null ? user.loginAt().toEpochMilli() : 0L;
            return new StoredAuthUser(
                    user.id(),
                    user.username(),
                    user.displayName(),
                    user.avatarUrl(),
                    user.verified(),
                    loginMs
            );
        }

        public AuthenticatedXUser toAuthenticatedXUser() {
            Instant loginAt = loginAtEpochMs > 0L ? Instant.ofEpochMilli(loginAtEpochMs) : null;
            return new AuthenticatedXUser(id, username, displayName, avatarUrl, verified, loginAt);
        }
    }

    public record StoredAuthSession(
            String sessionHash,
            StoredAuthUser user,
            long createdAtEpochSec,
            long expiresAtEpochSec,
            String replacedBySessionHash
    ) {
        public AuthenticatedXUser toAuthenticatedXUser() {
            return user != null ? user.toAuthenticatedXUser() : null;
        }
    }

    private final XOpsProperties properties;
    private final ObjectMapper objectMapper;
    private final XOAuthTokenStore cryptoHelper;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupEpochSec = new AtomicLong(0L);

    @Autowired
    public XOAuthSessionStore(XOpsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.cryptoHelper = new XOAuthTokenStore(properties, objectMapper);
    }

    public void saveSession(String sessionId, AuthenticatedXUser user, long nowEpochSec) {
        if (sessionId == null || sessionId.isBlank() || user == null) {
            return;
        }
        String hash = sha256Hex(sessionId);
        Path file = resolveSessionFile(hash);
        if (file == null) {
            return;
        }
        StoredAuthUser storedUser = StoredAuthUser.from(user);
        synchronized (lockForHash(hash)) {
            writeRecordLocked(file, new StoredAuthSession(
                    hash,
                    storedUser,
                    nowEpochSec,
                    nowEpochSec + MAX_SESSION_LIFETIME_SECONDS,
                    null
            ));
        }
        maybeCleanupExpired(nowEpochSec);
    }

    public boolean touchIfStale(String sessionId, AuthenticatedXUser user, long nowEpochSec) {
        if (sessionId == null || sessionId.isBlank() || user == null) {
            return false;
        }
        String hash = sha256Hex(sessionId);
        Path file = resolveSessionFile(hash);
        if (file == null) {
            return false;
        }
        StoredAuthUser storedUser = StoredAuthUser.from(user);
        synchronized (lockForHash(hash)) {
            StoredAuthSession existing = readRecordLocked(file);
            if (existing == null) {
                writeRecordLocked(file, new StoredAuthSession(
                        hash,
                        storedUser,
                        nowEpochSec,
                        nowEpochSec + MAX_SESSION_LIFETIME_SECONDS,
                        null
                ));
                return true;
            }
            if (nowEpochSec >= existing.expiresAtEpochSec()) {
                deleteQuietly(file);
                return false;
            }
            long remaining = existing.expiresAtEpochSec() - nowEpochSec;
            boolean stale = remaining < (MAX_SESSION_LIFETIME_SECONDS - SLIDE_THRESHOLD_SECONDS);
            boolean userChanged = !storedUser.equals(existing.user());
            if (!stale && !userChanged) {
                return false;
            }
            writeRecordLocked(file, new StoredAuthSession(
                    hash,
                    storedUser,
                    existing.createdAtEpochSec(),
                    nowEpochSec + MAX_SESSION_LIFETIME_SECONDS,
                    existing.replacedBySessionHash()
            ));
            return true;
        }
    }

    public StoredAuthSession loadValid(String sessionId, long nowEpochSec) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String hash = sha256Hex(sessionId);
        Path file = resolveSessionFile(hash);
        if (file == null || !Files.exists(file)) {
            return null;
        }
        synchronized (lockForHash(hash)) {
            StoredAuthSession record = readRecordLocked(file);
            if (record == null || record.user() == null) {
                return null;
            }
            if (nowEpochSec >= record.expiresAtEpochSec()) {
                deleteQuietly(file);
                return null;
            }
            return record;
        }
    }

    public void rotateOnRestore(String oldSessionId, String newSessionId, AuthenticatedXUser user, long nowEpochSec) {
        if (newSessionId == null || newSessionId.isBlank() || user == null) {
            return;
        }
        saveSession(newSessionId, user, nowEpochSec);
        if (oldSessionId == null || oldSessionId.isBlank() || oldSessionId.equals(newSessionId)) {
            return;
        }
        String oldHash = sha256Hex(oldSessionId);
        String newHash = sha256Hex(newSessionId);
        Path oldFile = resolveSessionFile(oldHash);
        if (oldFile == null) {
            return;
        }
        synchronized (lockForHash(oldHash)) {
            StoredAuthSession oldRecord = readRecordLocked(oldFile);
            long created = oldRecord != null ? oldRecord.createdAtEpochSec() : nowEpochSec;
            long graceExpiry = oldRecord != null
                    ? Math.min(oldRecord.expiresAtEpochSec(), nowEpochSec + RESTORE_GRACE_SECONDS)
                    : nowEpochSec + RESTORE_GRACE_SECONDS;
            if (graceExpiry <= nowEpochSec) {
                deleteQuietly(oldFile);
                return;
            }
            writeRecordLocked(oldFile, new StoredAuthSession(
                    oldHash,
                    StoredAuthUser.from(user),
                    created,
                    graceExpiry,
                    newHash
            ));
        }
    }

    public void revokeSession(String... sessionIds) {
        Path dir = resolveSessionDir();
        if (dir == null || sessionIds == null) {
            return;
        }
        Set<String> revokedHashes = new HashSet<>();
        for (String id : sessionIds) {
            if (id != null && !id.isBlank()) {
                revokedHashes.add(sha256Hex(id));
            }
        }
        if (revokedHashes.isEmpty()) {
            return;
        }

        // 沿 replacedBySessionHash 正向追踪删除已轮换的子会话
        Set<String> queue = new HashSet<>(revokedHashes);
        while (!queue.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String hash : queue) {
                Path file = dir.resolve(hash + FILE_SUFFIX);
                synchronized (lockForHash(hash)) {
                    StoredAuthSession rec = readRecordLocked(file);
                    if (rec != null && rec.replacedBySessionHash() != null && !rec.replacedBySessionHash().isBlank()
                            && revokedHashes.add(rec.replacedBySessionHash())) {
                        next.add(rec.replacedBySessionHash());
                    }
                    deleteQuietly(file);
                }
            }
            queue = next;
        }

        // 清理仍指向已注销会话的多跳父会话宽限记录
        if (!Files.isDirectory(dir)) {
            return;
        }
        Map<String, String> parentToChild = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String hash = fileName.substring(0, fileName.length() - FILE_SUFFIX.length());
                        synchronized (lockForHash(hash)) {
                            StoredAuthSession rec = readRecordLocked(p);
                            if (rec != null && rec.replacedBySessionHash() != null
                                    && !rec.replacedBySessionHash().isBlank()) {
                                parentToChild.put(hash, rec.replacedBySessionHash());
                            }
                        }
                    });
        } catch (IOException ignored) {
        }

        Set<String> ancestorHashes = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, String> entry : parentToChild.entrySet()) {
                if (revokedHashes.contains(entry.getValue()) && revokedHashes.add(entry.getKey())) {
                    ancestorHashes.add(entry.getKey());
                    changed = true;
                }
            }
        }
        for (String hash : ancestorHashes) {
            synchronized (lockForHash(hash)) {
                deleteQuietly(dir.resolve(hash + FILE_SUFFIX));
            }
        }
    }

    private void maybeCleanupExpired(long nowEpochSec) {
        long prev = lastCleanupEpochSec.get();
        if (nowEpochSec - prev < CLEANUP_INTERVAL_SECONDS) {
            return;
        }
        if (!lastCleanupEpochSec.compareAndSet(prev, nowEpochSec)) {
            return;
        }
        Path dir = resolveSessionDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String hash = fileName.substring(0, fileName.length() - FILE_SUFFIX.length());
                        synchronized (lockForHash(hash)) {
                            StoredAuthSession rec = readRecordLocked(p);
                            if (rec == null || nowEpochSec >= rec.expiresAtEpochSec()) {
                                deleteQuietly(p);
                            }
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private StoredAuthSession readRecordLocked(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return null;
            }
            String json = cryptoHelper.decryptWithPrefix(CIPHER_PREFIX, raw);
            return objectMapper.readValue(json, StoredAuthSession.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeRecordLocked(Path file, StoredAuthSession record) {
        try {
            Files.createDirectories(file.getParent());
            String json = objectMapper.writeValueAsString(record);
            String encrypted = cryptoHelper.encryptWithPrefix(CIPHER_PREFIX, json);
            Path tmp = file.resolveSibling(
                    record.sessionHash() + "." + ThreadLocalRandom.current().nextLong(1_000_000_000L) + ".tmp"
            );
            Files.writeString(tmp, encrypted, StandardCharsets.UTF_8);
            XOAuthTokenStore.setOwnerOnlyPermissions(tmp);
            XOAuthTokenStore.atomicReplace(tmp, file);
            XOAuthTokenStore.setOwnerOnlyPermissions(file);
        } catch (Exception ex) {
            throw new IllegalStateException("持久化登录会话失败：" + ex.getMessage(), ex);
        }
    }

    private Path resolveSessionDir() {
        String dir = properties.getDiskCacheDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        return Path.of(dir).resolve("auth-sessions");
    }

    private Path resolveSessionFile(String sessionHash) {
        Path dir = resolveSessionDir();
        if (dir == null || sessionHash == null || sessionHash.isBlank()) {
            return null;
        }
        return dir.resolve(sessionHash + FILE_SUFFIX);
    }

    private Object lockForHash(String hash) {
        if (sessionLocks.size() > 2048) {
            sessionLocks.clear();
        }
        return sessionLocks.computeIfAbsent(hash, k -> new Object());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
