package com.xops.hub.assistant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class AssistantRetention {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AssistantRetention() {
    }

    /** Epoch millis at 00:00:00 (Asia/Shanghai) of yesterday relative to {@code nowMillis}. */
    static long cutoffMillis(long nowMillis) {
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(ZONE).toLocalDate();
        return today.minusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }
}
