package com.xops.hub.service;

import com.xops.hub.store.AccountStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** The three handle-based snapshot layouts that predate stable account directories. */
final class AccountSnapshotFiles {
    private static final String STORE = "store_v" + AccountStore.VERSION;
    private static final String HANDLE = "[a-z0-9_]{1,15}";
    private static final Pattern HANDLE_DIRECTORY = Pattern.compile(HANDLE);
    private static final Pattern FLAT = Pattern.compile(STORE + "_" + HANDLE + "\\.json\\.gz");
    private static final Pattern LEGACY = Pattern.compile("twitterapi_io_v2_" + HANDLE + "\\.json");

    private AccountSnapshotFiles() {}

    static List<Path> legacyFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return files;
        Path users = root.resolve("users");
        if (Files.isDirectory(users, NOFOLLOW_LINKS)) {
            try (var entries = Files.newDirectoryStream(users)) {
                for (Path dir : entries) {
                    if (!HANDLE_DIRECTORY.matcher(dir.getFileName().toString()).matches()
                            || !Files.isDirectory(dir, NOFOLLOW_LINKS)) continue;
                    Path file = dir.resolve(STORE + ".json.gz");
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS)) files.add(file);
                }
            }
        }
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                if ((FLAT.matcher(name).matches() || LEGACY.matcher(name).matches()) && Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                    files.add(file);
                }
            }
        }
        return files;
    }
}
