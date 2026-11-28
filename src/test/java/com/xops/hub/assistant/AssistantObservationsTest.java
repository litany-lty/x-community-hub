package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AssistantObservationsTest {
    @Test
    void processesPublishedReceiptBeyondTwoHundredAndAttributesExistingHistoryWindows(@TempDir Path dir) {
        var store = new AssistantStore(dir, new ObjectMapper());
        var now = Instant.now();
        store.tx(tx -> {
            tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT").put("accountId", "owner").put("username", "elidlicom"));
            tx.put("meta", "settings", object().put("enabled", true));
            tx.put("publication", "older", object().put("publishedTweetId", "post").put("publishedAt", now.toEpochMilli())
                    .put("draftId", "draft").put("draftVersion", 7).put("topicId", "topic"));
            tx.put("observation_target", "post", object().put("postId", "post").put("source", "OWNER_HISTORY")
                    .put("publishedAt", now.toEpochMilli()));
            tx.put("observation", "post:24", object().put("postId", "post").put("source", "OWNER_HISTORY")
                    .put("status", "OBSERVED").put("views", 12));
            for (int i = 0; i < 201; i++) {
                tx.put("publication", "scheduled-" + i, object().put("status", "SCHEDULED"));
                tx.put("publication", "receipt-" + i, object().put("publishedTweetId", "post-" + i)
                        .put("publishedAt", now.toEpochMilli()));
            }
            return null;
        });
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var observations = new AssistantObservations(store, () -> {}, mock(AssistantObservations.ObservationClient.class), clock);
        try {
            observations.runTick();
        } finally { observations.stop(); }
        var resumed = new AssistantStore(dir, new ObjectMapper());
        observations = new AssistantObservations(resumed, () -> {}, mock(AssistantObservations.ObservationClient.class), clock);
        try {
            observations.runTick();
            assertThat((long) resumed.tx(tx -> tx.count("observation_target"))).isEqualTo(202);
            assertThat(resumed.tx(tx -> tx.get("observation_target", "post")).path("draftId").asText()).isEqualTo("draft");
            var attributed = resumed.tx(tx -> tx.get("observation", "post:24"));
            assertThat(attributed.path("publicationId").asText()).isEqualTo("older");
            assertThat(attributed.path("views").asInt()).isEqualTo(12);
            assertThat(resumed.tx(tx -> tx.get("publication", "older")).path("styleRefreshMarkedAt").isIntegralNumber()).isTrue();
        } finally { observations.stop(); }
    }

    @Test
    void acceptsOnlySnapshotsInsideTheOneHourAgeWindow() {
        long target = 1_000_000_000L;
        assertThat(AssistantObservations.withinWindow(target - 3_600_000L, target)).isTrue();
        assertThat(AssistantObservations.withinWindow(target + 3_600_000L, target)).isTrue();
        assertThat(AssistantObservations.withinWindow(target + 3_600_001L, target)).isFalse();
    }

    @Test
    void medianUsesActualAvailableSamples() {
        assertThat(AssistantObservations.median(List.of(10L, 20L, 50L, 80L, 100L))).isEqualTo(50L);
        assertThat(AssistantObservations.median(List.of(10L, 20L, 50L, 80L))).isEqualTo(35L);
    }

    @Test
    void interactionRateRequiresEveryConstituentMetric() {
        var incomplete = JsonNodeFactory.instance.objectNode().put("views", 100).put("likes", 5).put("replies", 1).put("reposts", 1);
        AssistantObservations.addInteractionRate(incomplete);
        assertThat(incomplete.has("interactionRate")).isFalse();

        incomplete.put("bookmarks", 3);
        AssistantObservations.addInteractionRate(incomplete);
        assertThat(incomplete.path("interactionRate").asDouble()).isEqualTo(0.1);
    }
}
