package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AccountArchiveEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String FULL_TEXT = "The complete original post with its source and conclusion.";

    @Test
    void ordinaryRelationshipAttemptsExpireOnTheMillisecondBoundary() throws Exception {
        Instant now = Instant.parse("2026-11-28T05:00:00Z");
        long cutoff = now.minus(AccountStore.RECORD_RETENTION).toEpochMilli();
        var restored = mapper.readValue("""
                {"ver":1,"at":%d,"me":{"id":"42","username":"sample"},"ra":{"old":%d,"edge":%d,"fresh":%d}}
                """.formatted(now.getEpochSecond(), cutoff - 1, cutoff, cutoff + 1), AccountStore.StoredAccount.class);
        var kept = Map.of("edge", cutoff, "fresh", cutoff + 1);
        assertThat(AccountStore.pruneToRollingWindow(restored, now, false).relationshipAttemptedAt()).isEqualTo(kept);
        var refresh = AccountStore.merge(restored, restored.profile(), now, List.of(), List.of(), List.of(), List.of(),
                null, Map.of(), false);
        assertThat(refresh.relationshipAttemptedAt()).isEqualTo(kept);
        assertThat(AccountStore.mergeStoredSlice(null, restored, refresh, false).relationshipAttemptedAt()).isEqualTo(kept);
        assertThat(AccountStore.pruneToRollingWindow(restored, now, true).relationshipAttemptedAt()).containsKey("old");
    }

    @Test
    void foregroundAndBackgroundRefreshRetainLegacyAndPerFieldEvidenceThroughDisk(@TempDir Path dir) throws Exception {
        var restored = mapper.readValue("""
                {"ver":1,"at":100,"me":{"id":"%s","username":"sample"},
                 "t":[{"i":"post","c":10,"k":"P","x":"The complete original post with its source and conclusion.",
                       "s":[40,17,0,0,0]}]}
                """.formatted(AuthorIdentity.ARCHIVE_ACCOUNT_ID), AccountStore.StoredAccount.class);
        var viewsOnly = sample("The complete…", 90, 0, Set.of("likes", "replies", "reposts", "bookmarks"));
        var partial = refresh(restored, viewsOnly, 200);
        var row = partial.tweets().get(0);
        assertThat(row.text()).isEqualTo(FULL_TEXT);
        assertThat(row.stats()).containsExactly(90, 17, 0, 0, 0);
        assertThat(row.unavailableMetrics()).contains("likes").doesNotContain("views");
        assertThat(row.metricsFetchedAt()).isZero();

        var attempted = AccountStore.withMetricAttempts(partial, List.of("post"), Instant.ofEpochSecond(300));
        Path file = dir.resolve("store_v1.json.gz");
        AccountStore.write(file, attempted, mapper, true);
        AccountStore.StoredAccount decoded;
        try (var gz = new GZIPInputStream(Files.newInputStream(file))) {
            decoded = mapper.readValue(gz, AccountStore.StoredAccount.class);
        }
        var earlier = refresh(restored, sample("The complete…", 70, 6, Set.of()), 150);
        var latest = refresh(decoded, sample("", 70, 6, Set.of()), 150);
        Path account = Path.of("users/by-id", AuthorIdentity.ARCHIVE_ACCOUNT_ID, "store_v1.json.gz");
        Path archive = dir.resolve("archive"), cache = dir.resolve("cache"), bundle = dir.resolve("older.tar.gz");
        AccountStore.write(archive.resolve(account), earlier, mapper, true);
        AccountStore.write(cache.resolve(account), decoded, mapper, true);
        seedCommand("export-bundle", archive, "--out", bundle);
        seedCommand("import-bundle", cache, "--bundle", bundle);
        var imported = AccountStore.read(cache.resolve(account), mapper);
        assertThat(imported).isNotNull();
        for (var merged : List.of(latest, imported, AccountStore.mergeStoredSlice(restored, decoded, earlier, true),
                AccountStore.mergeStoredSlice(restored, earlier, decoded, true))) {
            row = merged.tweets().get(0);
            assertThat(row.text()).isEqualTo(FULL_TEXT);
            assertThat(row.stats()).containsExactly(90, 6, 0, 0, 0);
            assertThat(row.unavailableMetrics()).isEmpty();
            assertThat(row.metricsFetchedAt()).isEqualTo(150);
            assertThat(row.metricsAttemptedAt()).isEqualTo(300);
        }
        var corrected = refresh(latest, sample(FULL_TEXT, 0, 0, Set.of()), 310).tweets().get(0);
        assertThat(corrected.stats() == null ? new long[5] : corrected.stats()).containsExactly(0, 0, 0, 0, 0);
        assertThat(corrected.metricsFetchedAt()).isEqualTo(310);
    }

    @Test
    void aLightweightQuotePreviewKeepsTheVerifiedOriginalsFullText() throws Exception {
        var restored = mapper.readValue("""
                {"ver":1,"at":100,"me":{"id":"%s","username":"sample"},
                 "t":[{"i":"post","c":10,"k":"Q","ti":"other","x":"The complete original post with its source and conclusion."}]}
                """.formatted(AuthorIdentity.ARCHIVE_ACCOUNT_ID), AccountStore.StoredAccount.class);
        assertThat(refresh(restored, sample("The complete", 70, 1, Set.of()), 200).tweets().get(0).text()).isEqualTo(FULL_TEXT);
        assertThat(refresh(restored, sample("A different object", 70, 1, Set.of()), 200).tweets().get(0).text()).isEqualTo("A different object");
    }

    private static void seedCommand(String command, Path cache, String fileOption, Path bundle) throws Exception {
        var process = new ProcessBuilder("python3", "-B", "scripts/elidlicom-seed-tool.py", command,
                "--cache-dir", cache.toString(), fileOption, bundle.toString()).redirectErrorStream(true).start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(new String(process.getInputStream().readAllBytes())).isZero();
    }

    private static AccountStore.StoredAccount refresh(AccountStore.StoredAccount base, RawTweet tweet, long observed) {
        return AccountStore.merge(base, base.profile(), Instant.ofEpochSecond(observed), List.of(tweet),
                List.of(), List.of(), List.of(), null, Map.of("post", observed), true);
    }

    private static RawTweet sample(String text, long views, int likes, Set<String> unknown) {
        return new RawTweet("post", text, Instant.ofEpochSecond(10), TweetKind.POST, null, null, List.of(),
                false, false, views, likes, 0, 0, 0, false, "post", "", unknown);
    }
}
