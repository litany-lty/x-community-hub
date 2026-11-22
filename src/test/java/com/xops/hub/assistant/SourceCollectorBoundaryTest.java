package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.RawSnapshotModels.TweetKind;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.provider.TwitterApiIoProvider.TextPost;
import com.xops.hub.provider.TwitterApiIoProvider.TextPostPage;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.CommunityHubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCollectorBoundaryTest {
    @TempDir Path tempDir;

    @Test
    void scanUsesThirtyMinuteOverlapWithoutLettingAnOldPinnedPostStopPagination() {
        long enabledAt = 1_000_000L;
        long lastScanStartedAt = enabledAt + 2 * 60 * 60 * 1000L;
        long boundary = SourceCollector.listenerScanSince(enabledAt, lastScanStartedAt);
        assertThat(boundary).isEqualTo(lastScanStartedAt - SourceCollector.SCAN_OVERLAP_MS);
        assertThat(SourceCollector.listenerScanSince(lastScanStartedAt, lastScanStartedAt))
                .isEqualTo(lastScanStartedAt);

        TextPost oldPinned = post("pinned", boundary - 1, true);
        TextPost current = post("current", boundary + 1, false);
        assertThat(SourceCollector.crossedListenerBoundary(List.of(oldPinned, current), boundary)).isFalse();
        assertThat(SourceCollector.crossedListenerBoundary(
                List.of(oldPinned, current, post("old", boundary - 1, false)), boundary)).isTrue();
    }

    @Test
    void cursorBindingJitterAndExpiredCursorRecoveryRecordGapAndRescanFromAnchor() {
        ObjectMapper mapper = new ObjectMapper();
        var record = mapper.createObjectNode().put("cursor", "c-1")
                .put("cursorProvider", "twitterapi.io").put("cursorEndpoint", "user/last_tweets");
        assertThat(SourceCollector.boundCursor(record, "cursor", "twitterapi.io", "user/last_tweets")).isEqualTo("c-1");
        assertThat(SourceCollector.boundCursor(record, "cursor", "other-provider", "user/last_tweets")).isEmpty();
        assertThat(SourceCollector.boundCursor(record, "cursor", "twitterapi.io", "other/endpoint")).isEmpty();

        assertThat(SourceCollector.sourceJitterMs("author-42")).isBetween(0L, 45_000L);
        assertThat(SourceCollector.shouldResetExpiredCursor(new XDataProviderException(400, "Invalid cursor"), 1)).isTrue();
        assertThat(SourceCollector.shouldResetExpiredCursor(new XDataProviderException(429, "Rate limit"), 2)).isFalse();
        assertThat(SourceCollector.shouldResetExpiredCursor(new RuntimeException("transient"), 1)).isFalse();
        assertThat(SourceCollector.shouldResetExpiredCursor(new RuntimeException("stagnant"), 2)).isTrue();

        AssistantStore store = new AssistantStore(tempDir, mapper);
        TwitterApiIoProvider provider = mock(TwitterApiIoProvider.class);
        CommunityHubService community = mock(CommunityHubService.class);
        when(community.longHistoryCandidates()).thenReturn(List.of());
        XUserSummary author = new XUserSummary("u-openai", "OpenAI", "OpenAI", "", "", true, 100, 10, 50, "");
        when(provider.resolvePublicAuthor(eq("OpenAI"))).thenReturn(author);

        SourceCollector collector = new SourceCollector(store, provider, community, mapper);
        try {
            store.tx(tx -> {
                tx.put("meta", "owner", mapper.createObjectNode().put("accountId", "owner-1").put("username", "elidlicom"));
                tx.put("meta", "settings", AssistantWorkflow.settings(tx).put("enabled", true).put("collectionEnabled", true));
                return null;
            });
            collector.add("@OpenAI");
            long now = System.currentTimeMillis();
            when(provider.fetchTextPostsPage(eq("OpenAI"), eq("u-openai"), eq(""), eq(100), any()))
                    .thenReturn(new TextPostPage(author, List.of(post("p1", now + 5_000L, false)), "stale-cursor", false))
                    .thenReturn(new TextPostPage(author, List.of(post("p2", now + 2_000L, false)), "", true));
            when(provider.fetchTextPostsPage(eq("OpenAI"), eq("u-openai"), eq("stale-cursor"), eq(100), any()))
                    .thenThrow(new XDataProviderException(400, "Invalid cursor token"));

            collector.tick();
            var afterPage1 = store.tx(tx -> tx.get("source", "u-openai"));
            assertThat(afterPage1.path("cursor").asText()).isEqualTo("stale-cursor");

            store.tx(tx -> tx.put("source", "u-openai", tx.get("source", "u-openai").put("lastAttemptAt", now - 10_000L)));
            collector.tick();
            var afterExpired = store.tx(tx -> tx.get("source", "u-openai"));
            assertThat(afterExpired.path("cursor").asText()).isEmpty();
            assertThat(afterExpired.path("anchorSince").asLong()).isGreaterThan(0L);
            assertThat(afterExpired.path("gaps").size()).isOne();
            assertThat(afterExpired.path("gaps").get(0).path("reason").asText()).contains("Invalid cursor");

            store.tx(tx -> tx.put("source", "u-openai", tx.get("source", "u-openai").put("lastAttemptAt", 1L)));
            collector.tick();
            var recovered = store.tx(tx -> tx.get("source", "u-openai"));
            assertThat(recovered.path("error").asText()).isEmpty();
            assertThat(recovered.has("anchorSince")).isFalse();
            var savedMaterial = store.tx(tx -> tx.get("material", "p2"));
            assertThat(savedMaterial).isNotNull();
        } finally {
            collector.stop();
        }
    }

    private static TextPost post(String id, long publishedAt, boolean pinned) {
        return new TextPost(id, "author-id", "author", Instant.ofEpochMilli(publishedAt), TweetKind.POST,
                "text", "", "", "", "", "", "", "", "", "text", "FULL", "NOT_PRESENT", "NOT_PRESENT", "TEXT_ONLY", false,
                "https://x.com/author/status/" + id, id, false, pinned, "ROW_ID_VERIFIED",
                null, null, null, null, null);
    }
}
