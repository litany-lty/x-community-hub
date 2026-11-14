package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.model.RawSnapshotModels.*;
import com.xops.hub.provider.TwitterApiIoProvider;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetricObservationAuditTest {
    private final XUserSummary self = new XUserSummary("self-id", "self", "Self", "", "", false, 0, 0, 0, "");

    @Test void continuationKeepsWindowAnchorButDatesMetricsAtTheirActualObservation() throws Exception {
        Instant anchor = Instant.now().minusSeconds(600);
        Instant created = anchor.minusSeconds(3600);
        var cached = new RawTweet("cached", "cached", created, TweetKind.POST, null, null, List.of(),
                false, false, 10, 1, 0, 0, 0);
        var previous = store(anchor.minusSeconds(3600), List.of(cached));
        var base = AccountStore.withDailyRefreshState(AccountStore.merge(previous, self, anchor, List.of(), List.of(),
                List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()}),
                List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()}), null),
                anchor.getEpochSecond(), 0);
        Instant observationStart = Instant.now();
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return profile();
            if (uri.getPath().equals("/twitter/tweets")) return page(row("cached", "createdAt", created, "self-id", "self"));
            return page("");
        })) {
            var result = isolatedProvider(f).syncStoreByHours("self", base, 24, false, true, null, "self-id");
            assertThat(result.store().syncedAt()).isEqualTo(anchor.getEpochSecond());
            assertThat(result.store().dailyRefreshStartedAt()).isEqualTo(anchor.getEpochSecond());
            assertThat(result.store().tweets()).singleElement().satisfies(tweet -> {
                assertThat(tweet.stats()[0]).isEqualTo(100);
                assertThat(tweet.metricsFetchedAt()).isGreaterThanOrEqualTo(observationStart.getEpochSecond());
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-id", "wrong-handle", "missing-id"})
    void nativeOwnTimelineCannotCertifyInvalidIdentityRows(String mode) throws Exception {
        Instant anchor = Instant.now().minusSeconds(600);
        Instant created = Instant.now().minusSeconds(10);
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return profile();
            if (uri.getPath().endsWith("/last_tweets")
                    || (uri.getPath().endsWith("/advanced_search") && String.valueOf(uri.getRawQuery()).contains("from%3Aself"))) {
                return page(row(mode.equals("missing-id") ? "" : "foreign", "createdAt", created,
                        mode.equals("wrong-id") ? "other-id" : "self-id", mode.equals("wrong-handle") ? "other" : "self"));
            }
            return page("");
        })) {
            var result = isolatedProvider(f).syncStoreByHours("self", store(anchor, List.of()), 24, true, true, null, "self-id");
            assertThat(result.store().tweets()).isEmpty();
            assertThat(result.degraded()).isTrue();
            assertThat(AccountStore.missingRanges(result.store().tweetCoverage(), anchor.getEpochSecond() + 1,
                    result.store().syncedAt())).isNotEmpty();
        }
    }

    @Test void acceptedTimestampAliasCannotSilentlyDropTheRecordWhileCertifyingCoverage() throws Exception {
        Instant anchor = Instant.now().minusSeconds(600);
        Instant created = Instant.now().minusSeconds(10);
        try (var f = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) return profile();
            if (uri.getPath().endsWith("/last_tweets")
                    || (uri.getPath().endsWith("/advanced_search") && String.valueOf(uri.getRawQuery()).contains("from%3Aself"))) {
                return page(row("alias", "created_at", created, "self-id", "self"));
            }
            return page("");
        })) {
            var result = isolatedProvider(f).syncStoreByHours("self", store(anchor, List.of()), 24, true, true, null, "self-id");
            assertThat(result.store().tweets()).singleElement().satisfies(tweet -> {
                assertThat(tweet.id()).isEqualTo("alias");
                assertThat(tweet.createdAt()).isEqualTo(created.getEpochSecond());
            });
        }
    }

    private TwitterApiIoProvider isolatedProvider(ExactFollowProviderTest.Fixture fixture) {
        fixture.properties.setDiskCacheDir("");
        return new TwitterApiIoProvider(fixture.properties, new ObjectMapper().findAndRegisterModules(), fixture.pool);
    }

    private AccountStore.StoredAccount store(Instant anchor, List<RawTweet> tweets) {
        var coverage = List.of(new long[]{anchor.minus(AccountStore.RETENTION).getEpochSecond(), anchor.getEpochSecond()});
        return AccountStore.merge(null, self, anchor, tweets, List.of(), coverage, coverage,
                new AccountStore.GraphUpdate(List.of(), List.of(), Set.of(), false, false, false, false));
    }

    private ExactFollowProviderTest.Reply profile() {
        return ExactFollowProviderTest.Reply.ok("{\"data\":{\"id\":\"self-id\",\"userName\":\"self\",\"following\":0,\"followers\":0}}");
    }

    private ExactFollowProviderTest.Reply page(String rows) {
        return ExactFollowProviderTest.Reply.ok("{\"tweets\":[" + rows + "],\"has_next_page\":false}");
    }

    private String row(String id, String dateField, Instant at, String authorId, String handle) {
        return "{\"id\":\"" + id + "\",\"" + dateField + "\":\"" + at + "\",\"author\":{\"id\":\"" + authorId
                + "\",\"userName\":\"" + handle + "\"},\"viewCount\":100,\"likeCount\":7,\"replyCount\":2,"
                + "\"retweetCount\":1,\"quoteCount\":0,\"bookmarkCount\":3}";
    }
}
