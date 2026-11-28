package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.provider.PaidCapabilityGate;
import com.xops.hub.provider.UpstreamBudgetGuard;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnerArchiveAndBudgetRetryTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();

    @Test void historicalCandidateCannotReplaceTheConfirmedAuthorHandle() {
        var store = new AssistantStore(dir, mapper);
        var now = Instant.parse("2026-11-28T01:00:00Z");
        String id = com.xops.hub.model.AuthorIdentity.ARCHIVE_ACCOUNT_ID;
        store.observeOAuthOwner(new com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser(id, "current", "", "", false, now));
        var hub = mock(com.xops.hub.service.CommunityHubService.class);
        var old = new com.xops.hub.model.RawSnapshotModels.XUserSummary(id, "former", "", "", "", false, 0, 0, 0, "");
        when(hub.longHistoryCandidates()).thenReturn(List.of(new com.xops.hub.service.CommunityHubService.LongHistoryCandidate(
                old, 0, true, 30, false, now.minusSeconds(86400).toEpochMilli())));
        var collector = new SourceCollector(store, mock(com.xops.hub.provider.TwitterApiIoProvider.class), hub, mapper, Clock.fixed(now, ZoneOffset.UTC));
        try {
            ReflectionTestUtils.invokeMethod(collector, "synchronizeHistoryCandidates");
            assertThat(store.tx(tx -> tx.get("history_source", id)).path("username").asText()).isEqualTo("current");
        } finally {
            collector.stop();
        }
    }

    @Test
    void aReassignedHandleContributesNeitherOwnerStyleNorLastPost() {
        var store = new AssistantStore(dir, mapper);
        var now = Instant.parse("2026-11-28T08:00:00Z");
        var auto = new AutoRepostService(store, mock(SourceCollector.class), mock(PiAgentViralJudge.class),
                mock(XOAuthService.class), mock(XPublishClient.class), Clock.fixed(now, ZoneOffset.UTC));
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "101").put("username", "shared"));
                tx.put("history_material", "old-own-post", mapper.createObjectNode().put("authorId", "101")
                        .put("username", "former").put("text", "This really is the original author's text")
                        .put("publishedAt", now.minusSeconds(7200).toEpochMilli()));
                tx.put("history_material", "foreign", mapper.createObjectNode().put("authorId", "202")
                        .put("username", "shared").put("text", "This text belongs to a different person")
                        .put("publishedAt", now.minusSeconds(60).toEpochMilli()));
                assertThat(OwnerQuoteStyleDistiller.collectOwnerAuthoredSamples(tx, 10))
                        .extracting(row -> row.path("id").asText()).containsExactly("old-own-post");
                assertThat(auto.resolveLatestOwnerTweetEpochMs(tx, "101", "shared", "", now.toEpochMilli()))
                        .isEqualTo(now.minusSeconds(7200).toEpochMilli());
                return null;
            });
        } finally {
            auto.shutdown();
        }
    }

    @Test
    void budgetBlockedTokenRefreshResumesAfterRestartWithoutReauthorization() {
        var store = new AssistantStore(dir, mapper);
        var now = new AtomicLong(Instant.parse("2026-11-28T01:00:00Z").toEpochMilli());
        var clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(call -> now.get());
        when(clock.instant()).thenAnswer(call -> Instant.ofEpochMilli(now.get()));
        var oauth = mock(XOAuthService.class);
        var publish = mock(XPublishClient.class);
        var blocked = new XDataProviderException(429, UpstreamBudgetGuard.BUDGET_REACHED_MESSAGE);
        when(oauth.getValidWriteAccessToken("101", false)).thenThrow(blocked, blocked).thenReturn("token");
        when(publish.createTweet("token", "已有评论", List.of(), "2001")).thenReturn("3001");
        store.tx(tx -> {
            tx.put("meta", "owner", mapper.createObjectNode().put("bindingSource", "EXPLICIT").put("accountId", "101").put("username", "author"));
            tx.put("source", "102", mapper.createObjectNode().put("authorId", "102").put("username", "source")
                    .put("status", "ACTIVE").put("autoRepostEnabled", true));
            tx.put("auto_repost", "2001", mapper.createObjectNode().put("status", "EVALUATING")
                    .put("materialId", "2001").put("topicId", "t1").put("authorId", "102").put("username", "source"));
            return null;
        });
        var gate = new PaidCapabilityGate(clock);
        var auto = new AutoRepostService(store, mock(SourceCollector.class), mock(PiAgentViralJudge.class), oauth, publish, clock);
        ReflectionTestUtils.setField(auto, "paidGate", gate);
        try {
            ReflectionTestUtils.invokeMethod(auto, "dispatchCommentAndPublish", "2001", "101", "author",
                    "t1", "已有评论", null, 0L, true);
            var row = store.tx(tx -> tx.get("auto_repost", "2001"));
            assertThat(row.path("status").asText()).isEqualTo("FAILED");
            assertThat(row.path("autoRetryAt").asLong()).isGreaterThan(now.get());
            var restarted = new PaidCapabilityGate(clock);
            ReflectionTestUtils.setField(auto, "paidGate", restarted);
            auto.recoverStaleRecords();
            now.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1);
            restarted.runDueRetries();
            verifyNoInteractions(publish);
            assertThat(restarted.getRetrySnapshot("auto-repost:2001").retryCount()).isZero();
            now.addAndGet(PaidCapabilityGate.RETRY_INTERVAL_MS + 1);
            restarted.runDueRetries();
            verify(publish).createTweet("token", "已有评论", List.of(), "2001");
            assertThat(store.tx(tx -> tx.get("auto_repost", "2001")).path("status").asText()).isEqualTo("PUBLISHED");
        } finally {
            auto.shutdown();
        }
    }
}
