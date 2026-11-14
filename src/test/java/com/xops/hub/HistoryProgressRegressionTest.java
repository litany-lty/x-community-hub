package com.xops.hub;

import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.model.RawSnapshotModels.XUserSummary;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryProgressRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedSiblingStreamKeepsAlreadyRequestedValidHistoryCoverage(boolean nativeHead) throws Exception {
        Instant oldPost = Instant.now().minusSeconds(3 * 86400);
        CountDownLatch failedStreamRequested = new CountDownLatch(1);
        try (var fixture = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) {
                return ExactFollowProviderTest.Reply.ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
            }
            boolean inbound = uri.getPath().endsWith("/mentions")
                    || (uri.getQuery() != null && uri.getQuery().contains("-from:self"));
            if (inbound) {
                failedStreamRequested.countDown();
                return new ExactFollowProviderTest.Reply(500, "{}");
            }
            if (uri.getPath().endsWith("/last_tweets") || uri.getPath().endsWith("/advanced_search")) {
                try {
                    assertThat(failedStreamRequested.await(2, TimeUnit.SECONDS)).isTrue();
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return ExactFollowProviderTest.Reply.ok("{\"tweets\":[{\"id\":\"kept\",\"createdAt\":\"" + oldPost + "\"}],\"has_next_page\":false}");
            }
            throw new AssertionError("No new history requests should follow a failed stream: " + uri);
        })) {
            var existing = nativeHead ? null : AccountStore.merge(null,
                    new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, ""),
                    Instant.now(), List.of(), List.of(), List.of(), List.of(),
                    new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
            var result = fixture.provider.syncStore("self", existing, 2, nativeHead, null);
            assertThat(result.degraded()).isTrue();
            assertThat(result.store().tweets()).singleElement().satisfies(t -> assertThat(t.id()).isEqualTo("kept"));
            assertThat(result.store().tweetCoverage()).hasSize(1);
            assertThat(result.store().inboundCoverage()).isEmpty();
            assertThat(TwitterApiIoProvider.snapshotOf(result.store(), "", true).coveredWindowDays()).isLessThan(2);
        }
    }
}
