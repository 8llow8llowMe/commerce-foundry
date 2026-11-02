package com.commercefoundry.domainlayer.job.support;

import com.commercefoundry.global.properties.JobResultFileProperties;
import java.time.Duration;

public final class ResultFileFixtures {
    private ResultFileFixtures() { }

    public static JobResultFileProperties properties() {
        return new JobResultFileProperties(10 * 1024 * 1024, Duration.ofDays(7), Duration.ofHours(1), Duration.ofSeconds(60),
            Duration.ofMinutes(10), Duration.ofHours(1), 100, 10, true);
    }
}
