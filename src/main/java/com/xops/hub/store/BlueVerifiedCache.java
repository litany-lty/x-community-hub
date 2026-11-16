package com.xops.hub.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global 15-day cache of X blue-verified status.
 * Keeps the first authoritative observation for each user within the 15-day TTL window and refreshes once expired.
 */
public class BlueVerifiedCache {

    private static final Logger log = LoggerFactory.getLogger(BlueVerifiedCache.class);
    public static final long TTL_SECONDS = 15L * 86_400L;
    private static final int MAX_DISK_ENTRIES = 50_000;
    private static final String FILE_NAME = "blue_verified_cache_v1.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(boolean verified, long firstObservedAt) {
        @JsonCreator
        public Entry {
            firstObservedAt = Math.max(0L, firstObservedAt);
        }
    }

    private final Path cacheFile;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object fileWriteLock = new Object();

    public BlueVerifiedCache(String diskCacheDir, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.cacheFile = (diskCacheDir != null && !diskCacheDir.isBlank())
                ? Path.of(diskCacheDir).resolve(FILE_NAME)
                : null;
        loadFromDisk();
    }

    public Boolean resolveValid(String userId, String username, long nowEpochSecond) {
        Entry byId = validEntry(idKey(userId), nowEpochSecond);
        if (byId != null) {
            return byId.verified();
        }
        Entry byHandle = validEntry(handleKey(username), nowEpochSecond);
        return byHandle != null ? byHandle.verified() : null;
    }

    public Entry resolveValidEntry(String userId, String username, long nowEpochSecond) {
        Entry byId = validEntry(idKey(userId), nowEpochSecond);
        if (byId != null) {
            return byId;
        }
        return validEntry(handleKey(username), nowEpochSecond);
    }

    /**
     * Records an authoritative observation (first observation wins while within the 15-day TTL).
     *
     * @return the active cached boolean after applying the first-wins 15-day rule
     */
    public boolean observeAuthoritative(String userId, String username, boolean verified, long nowEpochSecond) {
        long safeNow = Math.max(1L, nowEpochSecond);
        Entry existing = resolveValidEntry(userId, username, safeNow);
        if (existing != null) {
            // Keep first observation under both ID and handle keys within 15 days.
            backfillMissingAliasKey(userId, username, existing, safeNow);
            return existing.verified();
        }
        Entry fresh = new Entry(verified, safeNow);
        String iKey = idKey(userId);
        String hKey = handleKey(username);
        if (iKey != null) {
            entries.put(iKey, fresh);
            dirty.set(true);
        }
        if (hKey != null) {
            entries.put(hKey, fresh);
            dirty.set(true);
        }
        return verified;
    }

    public void flushIfDirty() {
        if (cacheFile == null || !dirty.compareAndSet(true, false)) {
            return;
        }
        synchronized (fileWriteLock) {
            try {
                pruneIfOversized();
                Files.createDirectories(cacheFile.getParent());
                Path tmp = cacheFile.resolveSibling(
                        FILE_NAME + "." + ThreadLocalRandom.current().nextLong(1_000_000_000L) + ".tmp");
                try {
                    Files.writeString(tmp, objectMapper.writeValueAsString(new LinkedHashMap<>(entries)), StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception ex) {
                log.debug("Ignored blue verified cache write failure: {}", ex.getMessage());
            }
        }
    }

    public int size() {
        return entries.size();
    }

    private void backfillMissingAliasKey(String userId, String username, Entry existing, long nowEpochSecond) {
        String iKey = idKey(userId);
        if (iKey != null && validEntry(iKey, nowEpochSecond) == null) {
            entries.put(iKey, existing);
            dirty.set(true);
        }
        String hKey = handleKey(username);
        if (hKey != null && validEntry(hKey, nowEpochSecond) == null) {
            entries.put(hKey, existing);
            dirty.set(true);
        }
    }

    private Entry validEntry(String key, long nowEpochSecond) {
        if (key == null) {
            return null;
        }
        Entry e = entries.get(key);
        if (e == null || e.firstObservedAt() <= 0L) {
            return null;
        }
        if (nowEpochSecond - e.firstObservedAt() >= TTL_SECONDS) {
            return null;
        }
        return e;
    }

    private void loadFromDisk() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            Map<String, Entry> loaded = objectMapper.readValue(
                    Files.readString(cacheFile, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Entry>>() {
                    });
            if (loaded != null) {
                long now = System.currentTimeMillis() / 1000L;
                loaded.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && now - v.firstObservedAt() < TTL_SECONDS) {
                        entries.put(k, v);
                    }
                });
            }
        } catch (Exception ex) {
            log.debug("Ignored unreadable blue verified cache file: {}", ex.getMessage());
        }
    }

    private void pruneIfOversized() {
        if (entries.size() <= MAX_DISK_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        entries.entrySet().removeIf(e -> e.getValue() == null || now - e.getValue().firstObservedAt() >= TTL_SECONDS);
        if (entries.size() > MAX_DISK_ENTRIES) {
            int excess = entries.size() - MAX_DISK_ENTRIES;
            entries.entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(e -> e.getValue() != null ? e.getValue().firstObservedAt() : 0L))
                    .limit(excess)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(entries::remove);
        }
    }

    private static String idKey(String userId) {
        if (userId == null) return null;
        String t = userId.trim();
        return t.isEmpty() ? null : "id:" + t;
    }

    private static String handleKey(String username) {
        if (username == null) return null;
        String t = username.replaceFirst("^@+", "").trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : "h:" + t;
    }
}
