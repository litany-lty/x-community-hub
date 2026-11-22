package com.xops.hub.assistant;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantObservationsTest {
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
