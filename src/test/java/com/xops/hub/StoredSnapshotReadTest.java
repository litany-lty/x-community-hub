package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.CommunityApiController;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.model.RawSnapshotModels.RawTweet;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.provider.DemoXDataProvider;
import com.xops.hub.provider.OfficialXApiV2Provider;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.service.CommunityHubService;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StoredSnapshotReadTest {
    @TempDir Path cacheDir;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DemoXDataProvider demo = mock(DemoXDataProvider.class);
    private final TwitterApiIoProvider twitter = mock(TwitterApiIoProvider.class);
    private final OfficialXApiV2Provider official = mock(OfficialXApiV2Provider.class);
    private CommunityHubService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        XOpsProperties properties = new XOpsProperties();
        properties.setRequireLoginSelfOnly(false);
        properties.setDiskCacheDir(cacheDir.toString());
        properties.setCacheTtlSeconds(300);
        service = new CommunityHubService(properties, demo, twitter, official, mapper);
        mvc = MockMvcBuilders.standaloneSetup(new CommunityApiController(properties, service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void expiredPersistedSnapshotKeepsAnchorAndNeverRefreshesOrWrites() throws Exception {
        Instant anchor = Instant.parse("2026-01-02T07:30:16Z");
        Path store = writeStore("self-id", "self", anchor);
        byte[] before = Files.readAllBytes(store);
        var modified = Files.getLastModifiedTime(store);

        mvc.perform(get("/api/analyze/bundle").session(session("self-id", "self"))
                        .param("stored", "true").param("continueRelations", "true")
                        .param("refresh", "true").param("provider", "X_OFFICIAL_V2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours24.fetchedAt").value(anchor.toString()))
                .andExpect(jsonPath("$.hours24.nextRefreshAt").value(anchor.plusSeconds(300).toString()))
                .andExpect(jsonPath("$.hours24.cacheRemainingSeconds").value(0))
                .andExpect(jsonPath("$.hours24.fromCache").value(true))
                .andExpect(jsonPath("$.hasMoreHistoryForTarget").value(false));

        assertThat(Files.readAllBytes(store)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(store)).isEqualTo(modified);
        assertThat(service.getActiveCacheSize()).isZero();
        try (var files = Files.walk(cacheDir)) {
            assertThat(files.filter(Files::isRegularFile).toList()).containsExactly(store);
        }
        verifyNoInteractions(demo, twitter, official);
    }

    @Test
    void storedReadRequiresLoginAndSelfEvenWhenAnonymousQueriesAreEnabled() throws Exception {
        mvc.perform(get("/api/analyze/bundle").param("stored", "true").param("username", "other"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analyze/bundle").param("stored", "true")
                        .session(session("self-id", "self")).param("username", "other"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("", "self")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(demo, twitter, official);
    }

    @Test
    void missingSnapshotIs404AndMismatchedStoredIdentityIsRejected() throws Exception {
        mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("self-id", "self")))
                .andExpect(status().isNotFound());
        writeStore("different-id", "self", Instant.parse("2026-01-02T07:30:16Z"));
        mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("self-id", "self")))
                .andExpect(status().isConflict());
        verifyNoInteractions(demo, twitter, official);
    }

    @Test
    void verifiedLegacySnapshotIsReadableWithoutMigratingOrCallingUpstream() throws Exception {
        Instant now = Instant.now().minusSeconds(600);
        Path byId = writeStore("self-id", "self", now);
        Path old = cacheDir.resolve("users/self/store_v1.json.gz");
        Files.createDirectories(old.getParent());
        Files.move(byId, old);
        for (Path path : List.of(old, cacheDir.resolve("store_v1_self.json.gz"))) {
            if (!path.equals(old)) Files.move(old, path);
            mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("self-id", "self")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hours24.profile.id").value("self-id"));
            assertThat(path).exists();
            assertThat(byId).doesNotExist();
            mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("other-id", "self")))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(demo, twitter, official);
    }

    @Test
    void changedHandleReadsEachLegacyLayoutByIdAndRankingLeavesTheAuthorFlatFileUntouched() throws Exception {
        Instant anchor = Instant.now().minusSeconds(600);
        Path byId = writeStore("self-id", "old_self", anchor);
        var saved = AccountStore.read(byId, mapper);
        Path authorFile = cacheDir.resolve("store_v1_elidlicom.json.gz");
        var owner = new XUserSummary(com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID, "elidlicom",
                "Synthetic owner", "", "", false, 0, 0, 0, "");
        AccountStore.write(authorFile, AccountStore.merge(null, owner, anchor, List.of(), List.of(), List.of(), List.of(), null), mapper);
        byte[] authorBefore = Files.readAllBytes(authorFile);
        Files.delete(byId);
        for (Path path : List.of(cacheDir.resolve("users/old_self/store_v1.json.gz"), cacheDir.resolve("store_v1_old_self.json.gz"),
                cacheDir.resolve("twitterapi_io_v2_old_self.json"))) {
            if (path.toString().endsWith(".gz")) {
                AccountStore.write(path, saved, mapper);
            } else {
                mapper.writeValue(path.toFile(), java.util.Map.of("snapshot", AccountStore.toSnapshot(saved, "", false, 30)));
            }
            byte[] original = Files.readAllBytes(path);
            mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("self-id", "new_self")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.hours24.profile.id").value("self-id"));
            mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("new-id", "old_self")))
                    .andExpect(status().isNotFound());
            assertThat(byId).doesNotExist();
            assertThat(Files.readAllBytes(path)).isEqualTo(original);
            assertThat(Files.readAllBytes(authorFile)).isEqualTo(authorBefore);
            assertThat(cacheDir.resolve("users/elidlicom/store_v1.json.gz")).doesNotExist();
            Files.delete(path);
        }
        verifyNoInteractions(demo, twitter, official);
    }

    @Test
    void oneSuccessfulDateRemainsOneEvenWithThirtyDaysOfCoverage() throws Exception {
        Instant now = Instant.now().minusSeconds(600);
        Path store = writeStore("self-id", "self", now);
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        mapper.writeValue(store.resolveSibling("usage_v1.json").toFile(),
                new CommunityHubService.UserUsageEntry(1, 0, null, now, List.of(today), now.getEpochSecond(), now));
        mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("self-id", "self")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessPolicy.accumulatedDays").value(1))
                .andExpect(jsonPath("$.week").doesNotExist())
                .andExpect(jsonPath("$.accessPolicy.unlockedPeriods.length()").value(1));
    }

    @Test
    void retentionRemovesDormantRowsButPreservesOwnerAndRecentOrdinaryRows() throws Exception {
        Instant now = Instant.now();
        Instant old = now.minusSeconds(40 * 86400L);
        String ownerId = com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID;
        for (String handle : List.of("self", "renamedowner")) {
            var user = new XUserSummary(handle.equals("self") ? "self-id" : ownerId, handle, handle, "", "", false, 0, 0, 0, "");
            var records = List.of(
                    new RawTweet("old", "old", old, TweetKind.POST, null, null, List.of(), false, false, 1, 0, 0, 0, 0, false),
                    new RawTweet("recent", "recent", now.minusSeconds(3), TweetKind.POST, null, null, List.of(), false, false, 1, 0, 0, 0, 0, false));
            var received = List.of(new com.xops.hub.model.RawSnapshotModels.InboundInteraction(
                    "old-reply", user, old, com.xops.hub.model.RawSnapshotModels.InboundKind.REPLY_TO_ME, "old-comment", "root", "", ""));
            var stored = AccountStore.merge(null, user, now, records, received, List.of(), List.of(), null, java.util.Map.of(), true);
            var file = cacheDir.resolve("users/by-id/" + user.id() + "/store_v1.json.gz");
            AccountStore.write(file, stored, mapper, true);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "putSnapshotCache", "TWITTERAPI_IO:id:" + user.id(),
                    AccountStore.toSnapshot(stored, "", false, 30), null, 300L, now.plusSeconds(300), "builtin");
            if (handle.equals("self")) {
                for (String path : List.of("users/self/store_v1.json.gz", "store_v1_self.json.gz", "backup/store_v1_self.json.gz")) {
                    AccountStore.write(cacheDir.resolve(path), stored, mapper, true);
                }
                var legacy = mapper.createObjectNode();
                com.fasterxml.jackson.databind.node.ObjectNode legacySnapshot = mapper.valueToTree(AccountStore.toSnapshot(stored, "", false, 30));
                legacySnapshot.putNull("timelineTailTime").put("timelineExhausted", true);
                legacy.set("snapshot", legacySnapshot);
                legacy.putObject("bundle").put("oldText", "old-comment");
                mapper.writeValue(cacheDir.resolve("twitterapi_io_v2_self.json").toFile(), legacy);
                @SuppressWarnings("unchecked") var memory = (java.util.Map<String, AccountStore.StoredAccount>)
                        org.springframework.test.util.ReflectionTestUtils.getField(service, "memoryStores");
                memory.put("id:self-id", stored);
            }
        }
        service.pruneExpiredAccountRecords();
        for (String path : List.of("users/by-id/self-id/store_v1.json.gz", "users/self/store_v1.json.gz", "store_v1_self.json.gz")) {
            var pruned = AccountStore.read(cacheDir.resolve(path), mapper);
            assertThat(pruned.tweets()).extracting(AccountStore.StoredTweet::id).containsExactly("recent");
            assertThat(pruned.inbound()).isEmpty();
        }
        var legacy = mapper.readTree(cacheDir.resolve("twitterapi_io_v2_self.json").toFile());
        assertThat(legacy.path("snapshot").path("timeline")).hasSize(1);
        assertThat(legacy.path("snapshot").path("inboundInteractions")).isEmpty();
        assertThat(legacy.path("snapshot").path("timelineExhausted").asBoolean()).isFalse();
        assertThat(legacy.path("snapshot").hasNonNull("timelineTailTime")).isTrue();
        assertThat(legacy.has("bundle")).isFalse();
        @SuppressWarnings("unchecked") var memory = (java.util.Map<String, AccountStore.StoredAccount>)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "memoryStores");
        assertThat(memory.get("id:self-id").tweets()).extracting(AccountStore.StoredTweet::id).containsExactly("recent");
        assertThat(AccountStore.read(cacheDir.resolve("backup/store_v1_self.json.gz"), mapper).tweets()).hasSize(2);
        assertThat(AccountStore.read(cacheDir.resolve("users/by-id/" + ownerId + "/store_v1.json.gz"), mapper).tweets())
                .extracting(AccountStore.StoredTweet::id).containsExactlyInAnyOrder("old", "recent");
        assertThat(service.getActiveCacheSize()).isOne();
        var ordinaryFile = cacheDir.resolve("users/by-id/self-id/store_v1.json.gz");
        var lastWritten = Files.getLastModifiedTime(ordinaryFile);
        service.pruneExpiredAccountRecords();
        assertThat(Files.getLastModifiedTime(ordinaryFile)).isEqualTo(lastWritten);
        verifyNoInteractions(demo, twitter, official);
    }

    @Test
    void physicalPruningReadsTheStoreAfterAcquiringTheForegroundWriterLock() throws Exception {
        Instant now = Instant.now();
        var user = new XUserSummary("self-id", "self", "self", "", "", false, 0, 0, 0, "");
        var old = new RawTweet("expired", "", now.minusSeconds(40 * 86400L), TweetKind.POST, null, null,
                List.of(), false, false, 0, 0, 0, 0, 0, false);
        var fresh = new RawTweet("arrived", "", now, TweetKind.POST, null, null,
                List.of(), false, false, 0, 0, 0, 0, 0, false);
        Path file = cacheDir.resolve("users/by-id/self-id/store_v1.json.gz");
        var before = AccountStore.merge(null, user, now, List.of(old), List.of(), List.of(), List.of(), null, java.util.Map.of(), true);
        AccountStore.write(file, before, mapper, true);
        var writeLock = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "fetchLockFor", "id:self-id");
        var maintenance = new Thread(service::pruneExpiredAccountRecords);
        synchronized (writeLock) {
            maintenance.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (maintenance.isAlive() && maintenance.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(maintenance.getState()).isEqualTo(Thread.State.BLOCKED);
            var updated = AccountStore.merge(before, user, now, List.of(fresh), List.of(), List.of(), List.of(), null,
                    java.util.Map.of(), true);
            AccountStore.write(file, updated, mapper, true);
        }
        maintenance.join(5000);
        assertThat(maintenance.isAlive()).isFalse();
        assertThat(AccountStore.read(file, mapper).tweets()).extracting(AccountStore.StoredTweet::id).containsExactly("arrived");
    }

    private Path writeStore(String id, String username, Instant anchor) throws Exception {
        var profile = new XUserSummary(id, username, username, "", "", false, 0, 0, 0, "");
        var stored = AccountStore.merge(null, profile, anchor, List.of(), List.of(),
                List.of(new long[]{anchor.minusSeconds(721 * 3600L).getEpochSecond(), anchor.getEpochSecond()}),
                List.of(new long[]{anchor.minusSeconds(721 * 3600L).getEpochSecond(), anchor.getEpochSecond()}), null);
        Path file = cacheDir.resolve("users/by-id/self-id/store_v" + AccountStore.VERSION + ".json.gz");
        AccountStore.write(file, stored, mapper);
        return file;
    }

    private MockHttpSession session(String id, String username) {
        var session = new MockHttpSession();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser(id, username, username, "", false, Instant.now()));
        return session;
    }
}
