package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.model.AuthorIdentity;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.*;
import com.xops.hub.service.CommunityAnalyticsEngine.ReportPeriod;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConcurrentOwnerImportTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final XUserSummary owner = new XUserSummary(AuthorIdentity.ARCHIVE_ACCOUNT_ID, "author_now", "Author", "", "", false, 0, 0, 0, "");
    final long now = Instant.now().getEpochSecond();
    final Path tool = Path.of("scripts/elidlicom-seed-tool.py").toAbsolutePath();

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentRefreshAndImportKeepBothPostsFullTextAndNewestMetrics(boolean refreshFirst) throws Exception {
        Path cache = root.resolve("cache"), archive = root.resolve("archive"), bundle = root.resolve("history.tar.gz");
        var original = merge(null, now - 600, post("101", "original", 4));
        AccountStore.write(file(cache), original, mapper, true);
        AccountStore.write(file(archive), merge(null, now - 60, post("101", "original complete archive body", 6),
                post("102", "only in history", 1)), mapper, true);
        var export = new ProcessBuilder("python3", "-B", tool.toString(), "export-bundle", "--cache-dir",
                archive.toString(), "--out", bundle.toString()).redirectErrorStream(true).start();
        assertThat(export.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(export.exitValue()).as(new String(export.getInputStream().readAllBytes())).isZero();

        var properties = new XOpsProperties();
        properties.setDefaultProvider("TWITTERAPI_IO");
        properties.setTwitterapiIoKey("fixture");
        properties.setDiskCacheDir(cache.toString());
        var provider = mock(TwitterApiIoProvider.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(refreshFirst ? 1 : 0);
        when(provider.syncStoreByHours(anyString(), any(), eq(24), anyBoolean(), eq(true), isNull(), eq(owner.id())))
                .thenAnswer(call -> {
                    entered.countDown();
                    if (!finish.await(15, TimeUnit.SECONDS)) throw new AssertionError("stalled refresh");
                    return new TwitterApiIoProvider.SyncOutcome(merge(call.getArgument(1), now,
                            post("101", "original", 7), post("103", "only in refresh", 2)), true, false, false, 1);
                });
        var service = new CommunityHubService(properties, new DemoXDataProvider(), provider, mock(OfficialXApiV2Provider.class), mapper);
        var workers = Executors.newCachedThreadPool();
        Callable<Object> refresh = () -> service.analyzeWorkspaceBundle(owner.username(), ReportPeriod.HOURS_24,
                null, true, "test", owner.id(), false);
        Future<?> refreshed = null;
        Process importer = null;
        try {
            if (refreshFirst) {
                refreshed = workers.submit(refresh);
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            }
            // Run the actual seed CLI. Pause at its first POSIX lock so both interleavings are reproducible.
            String barrier = """
                    import fcntl, runpy, sys
                    original = fcntl.lockf
                    def checkpoint(file, op, *args):
                        try:
                            original(file, op | fcntl.LOCK_NB, *args)
                        except BlockingIOError:
                            print("waiting for refresh", flush=True)
                            original(file, op, *args)
                        else:
                            print("import owns lock", flush=True)
                            sys.stdin.readline()
                    fcntl.lockf = checkpoint
                    sys.argv = sys.argv[1:]
                    runpy.run_path(sys.argv[0], run_name="__main__")
                    """;
            importer = new ProcessBuilder("python3", "-B", "-c", barrier, tool.toString(), "import-bundle",
                    "--cache-dir", cache.toString(), "--bundle", bundle.toString()).redirectErrorStream(true).start();
            var lines = importer.inputReader();
            String first = workers.submit(lines::readLine).get(10, TimeUnit.SECONDS);
            assertThat(first).isEqualTo(refreshFirst ? "waiting for refresh" : "import owns lock");
            if (!refreshFirst) {
                refreshed = workers.submit(refresh);
                assertThat(entered.await(250, TimeUnit.MILLISECONDS)).as("refresh waits before reading the archive").isFalse();
            }
            finish.countDown();
            importer.getOutputStream().close();
            refreshed.get(15, TimeUnit.SECONDS);
            assertThat(importer.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(importer.exitValue()).as(lines.lines().collect(java.util.stream.Collectors.joining())).isZero();
            var saved = AccountStore.read(file(cache), mapper);
            assertThat(saved.tweets()).extracting(AccountStore.StoredTweet::id).containsExactlyInAnyOrder("101","102","103");
            var common = saved.tweets().stream().filter(t -> t.id().equals("101")).findFirst().orElseThrow();
            assertThat(common.text()).isEqualTo("original complete archive body");
            assertThat(common.stats()[1]).isEqualTo(7);
            assertThat(saved.inbound()).singleElement().satisfies(reply ->
                    assertThat(reply.snippet()).isEqualTo("historical reply in full"));
        } finally {
            finish.countDown();
            if (importer != null && importer.isAlive()) importer.destroyForcibly();
            workers.shutdownNow();
            service.stopBackgroundRefreshes();
        }
    }

    private Path file(Path cache) { return cache.resolve("users/by-id").resolve(owner.id()).resolve("store_v1.json.gz"); }
    private RawTweet post(String id, String body, int likes) {
        return new RawTweet(id, body, Instant.ofEpochSecond(now - 900), TweetKind.POST, null, null, List.of(),
                false, false, 10, likes, 0, 0, 0);
    }
    private AccountStore.StoredAccount merge(AccountStore.StoredAccount base, long observed, RawTweet... tweets) {
        var times = new java.util.HashMap<String, Long>();
        for (var t : tweets) times.put(t.id(), observed);
        var peer = new XUserSummary("900", "visitor", "Visitor", "", "", false, 0, 0, 0, "");
        var reply = new InboundInteraction("104", peer, Instant.ofEpochSecond(now - 720), InboundKind.REPLY_TO_ME,
                observed == now - 60 ? "historical reply in full" : "historical reply", "101", "101", owner.username(), owner.id());
        return AccountStore.merge(base, owner, Instant.ofEpochSecond(observed), List.of(tweets), List.of(reply),
                List.of(), List.of(), null, times, true);
    }
}
