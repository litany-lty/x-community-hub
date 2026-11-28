package com.xops.hub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.store.AccountStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Maintains the account file layouts read by CommunityHubService, without traversing arbitrary cache subdirectories. */
final class AccountCacheRetention {
    private static final Logger log = LoggerFactory.getLogger(AccountCacheRetention.class);
    private static final String STORE = "store_v" + AccountStore.VERSION;
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9_]{1,15}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern FLAT = Pattern.compile(STORE + "_(" + HANDLE.pattern() + ")\\.json\\.gz");
    private static final Pattern LEGACY = Pattern.compile("twitterapi_io_v2_(" + HANDLE.pattern() + ")\\.json");
    private final ObjectMapper mapper;
    private final Function<String, Object> accountLock;
    private final String ownerId;
    private final Instant now;

    AccountCacheRetention(ObjectMapper mapper, Function<String, Object> accountLock, String ownerId, Instant now) {
        this.mapper = mapper;
        this.accountLock = accountLock;
        this.ownerId = ownerId;
        this.now = now;
    }

    void pruneDirectory(Path root) throws IOException {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return;
        Path users = root.resolve("users");
        if (Files.isDirectory(users, NOFOLLOW_LINKS)) {
            pruneUserDirectories(users, false);
            pruneUserDirectories(users.resolve("by-id"), true);
        }
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                var flat = FLAT.matcher(name);
                var legacy = LEGACY.matcher(name);
                if (flat.matches()) pruneFile(file, flat.group(1), false, false);
                else if (legacy.matches()) pruneFile(file, legacy.group(1), false, true);
            }
        }
    }

    private void pruneUserDirectories(Path parent, boolean byId) throws IOException {
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return;
        try (var entries = Files.newDirectoryStream(parent)) {
            for (Path directory : entries) {
                String name = directory.getFileName().toString();
                if (!(byId ? ACCOUNT_ID : HANDLE).matcher(name).matches() || !Files.isDirectory(directory, NOFOLLOW_LINKS)) continue;
                if (byId && name.equals(ownerId)) continue;
                pruneFile(directory.resolve(STORE + ".json.gz"), name, byId, false);
            }
        }
    }

    private void pruneFile(Path file, String key, boolean byId, boolean legacy) {
        synchronized (accountLock.apply(byId ? "id:" + key : key)) {
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return;
            try {
                if (legacy) {
                    pruneLegacy(file, key);
                    return;
                }
                var current = AccountStore.read(file, mapper);
                if (current == null || !ordinaryAccount(current.profile(), key, byId)) return;
                var keep = AccountStore.pruneToRollingWindow(current, now, false);
                if (keep != current) AccountStore.write(file, keep, mapper, false);
            } catch (IOException | RuntimeException ex) {
                log.warn("Could not prune an account cache file: {}", ex.getClass().getSimpleName());
            }
        }
    }

    private boolean ordinaryAccount(XUserSummary profile, String key, boolean byId) {
        return profile != null && profile.id() != null && !profile.id().isBlank() && !ownerId.equals(profile.id())
                && (byId ? key.equals(profile.id()) : key.equalsIgnoreCase(profile.username()));
    }

    private void pruneLegacy(Path file, String handle) throws IOException {
        if (!(mapper.readTree(file.toFile()) instanceof ObjectNode wrapper)
                || !(wrapper.path("snapshot") instanceof ObjectNode snapshot) || !snapshot.hasNonNull("profile")) return;
        var profile = mapper.treeToValue(snapshot.get("profile"), XUserSummary.class);
        if (!ordinaryAccount(profile, handle, false) || !snapshot.hasNonNull("fetchedAt")) return;
        Instant fetched = mapper.treeToValue(snapshot.get("fetchedAt"), Instant.class);
        Instant cutoff = Instant.ofEpochSecond(now.minus(AccountStore.RECORD_RETENTION).getEpochSecond());
        boolean trimmedTimeline = trimLegacyRows(snapshot, "timeline", "timeline", cutoff, fetched);
        boolean trimmedInbound = trimLegacyRows(snapshot, "inboundInteractions", "mentions", cutoff, fetched);
        if (!trimmedTimeline && !trimmedInbound && !(wrapper.has("bundle") && fetched.isBefore(cutoff))) return;
        // Only the raw snapshot is consumed during migration. A derived report may also contain expired full text.
        wrapper.remove("bundle");
        Path temp = Files.createTempFile(file.getParent(), ".retention-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), wrapper);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private boolean trimLegacyRows(ObjectNode snapshot, String stream, String prefix, Instant cutoff, Instant fetched) throws IOException {
        if (!(snapshot.path(stream) instanceof ArrayNode rows)) return false;
        int before = rows.size();
        var iterator = rows.iterator();
        while (iterator.hasNext()) {
            var created = iterator.next().get("createdAt");
            if (created != null && !created.isNull() && mapper.treeToValue(created, Instant.class).isBefore(cutoff)) iterator.remove();
        }
        if (rows.size() == before) return false;
        String tail = prefix + "TailTime", exhausted = prefix + "Exhausted";
        Instant bound = fetched.isBefore(cutoff) ? fetched : cutoff;
        Instant previous = snapshot.hasNonNull(tail) ? mapper.treeToValue(snapshot.get(tail), Instant.class)
                : snapshot.path(exhausted).asBoolean() ? bound : fetched;
        snapshot.set(tail, mapper.valueToTree(previous.isAfter(bound) ? previous : bound));
        snapshot.put(exhausted, false);
        return true;
    }
}
