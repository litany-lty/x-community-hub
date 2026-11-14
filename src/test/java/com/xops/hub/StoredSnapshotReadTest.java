package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.CommunityApiController;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
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
                .andExpect(jsonPath("$.day.fetchedAt").value(anchor.toString()))
                .andExpect(jsonPath("$.week.fetchedAt").value(anchor.toString()))
                .andExpect(jsonPath("$.month.fetchedAt").value(anchor.toString()))
                .andExpect(jsonPath("$.day.nextRefreshAt").value(anchor.plusSeconds(300).toString()))
                .andExpect(jsonPath("$.day.cacheRemainingSeconds").value(0))
                .andExpect(jsonPath("$.day.fromCache").value(true))
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
        writeStore("self-id", "other", Instant.parse("2026-01-02T07:30:16Z"));
        mvc.perform(get("/api/analyze/bundle").param("stored", "true").session(session("self-id", "self")))
                .andExpect(status().isConflict());
        verifyNoInteractions(demo, twitter, official);
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
