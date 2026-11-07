package com.commercefoundry.domainlayer.sizechart;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import java.time.Instant;
import java.util.UUID;

public final class SizeChartTransportFixtures {
    public static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    public static final String HASH = "a".repeat(64);

    private SizeChartTransportFixtures() {
    }

    public static SizeChartDraft draft() {
        var input = new SizeChartDraft.Input(10, 20, 0, 100, UUID.fromString("11111111-1111-4111-8111-111111111111"),
            HASH, HASH, "{\"mode\":\"TEXT\",\"text\":\"M 40\"}", "{\"columns\":[]}", null, null);
        return SizeChartDraft.request(7, input, NOW.plusSeconds(60));
    }
}
