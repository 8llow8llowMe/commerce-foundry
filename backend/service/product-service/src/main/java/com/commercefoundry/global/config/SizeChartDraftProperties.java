package com.commercefoundry.global.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Content and actor/key idempotency expire together at seven days. Operations retains its own receipts. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "size-chart.draft")
public class SizeChartDraftProperties {
    @NotNull
    @DurationMin(days = 7)
    @DurationMax(days = 7)
    private Duration contentTtl = Duration.ofDays(7);

    @Min(1)
    @Max(1000)
    private int cleanupBatchSize = 100;

    @Min(1)
    @Max(100)
    private int cleanupMaxBatchesPerRun = 10;

    @NotNull
    @DurationMin(seconds = 1)
    @DurationMax(days = 1)
    private Duration cleanupFixedDelay = Duration.ofHours(1);

    @NotNull
    @DurationMin(seconds = 1)
    @DurationMax(days = 1)
    private Duration cleanupInitialDelay = Duration.ofMinutes(1);
}
