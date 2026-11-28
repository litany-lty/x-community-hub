package com.xops.hub.store;

import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.provider.XDataProviderException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Java refreshes may overlap each other. The seed tool takes the exclusive POSIX lock before importing. */
public final class OwnerArchiveAccess {
    private static final ConcurrentHashMap<Path, Readers> active = new ConcurrentHashMap<>();

    private OwnerArchiveAccess() {}

    private static final class Readers {
        final FileChannel channel;
        int count;

        Readers(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                channel.lock(0, Long.MAX_VALUE, true);
            } catch (IOException | RuntimeException ex) {
                channel.close();
                throw ex;
            }
        }
    }

    public static <T> T duringRefresh(String cacheDirectory, String accountId, Supplier<T> operation) {
        if (!AuthorIdentity.ARCHIVE_ACCOUNT_ID.equals(accountId) || cacheDirectory == null || cacheDirectory.isBlank()) {
            return operation.get();
        }
        Path file;
        try {
            file = Files.createDirectories(Path.of(cacheDirectory)).toRealPath().resolve("owner-archive.lock");
        } catch (IOException ex) {
            throw unavailable(ex);
        }
        // A JVM cannot hold overlapping FileLocks, including shared locks. Pin one channel for all its readers.
        active.compute(file, (path, readers) -> {
            try {
                if (readers == null) readers = new Readers(path);
                readers.count++;
                return readers;
            } catch (IOException ex) {
                throw unavailable(ex);
            }
        });
        try {
            return operation.get();
        } finally {
            active.compute(file, (path, readers) -> {
                if (--readers.count > 0) return readers;
                try {
                    readers.channel.close();
                } catch (IOException ex) {
                    org.slf4j.LoggerFactory.getLogger(OwnerArchiveAccess.class).warn("Failed to close owner archive lock", ex);
                }
                return null;
            });
        }
    }

    private static XDataProviderException unavailable(IOException cause) {
        return new XDataProviderException(503, "作者归档暂时无法锁定，请稍后重试", cause);
    }
}
