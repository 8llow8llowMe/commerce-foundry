package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SizeChartDraftPropertiesTest {
    @Test
    void defaultLifetimeAndBoundsAreValidated() {
        var properties = new SizeChartDraftProperties();
        assertThat(properties.getContentTtl()).isEqualTo(Duration.ofDays(7));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (Duration valid : new Duration[] {Duration.ofDays(7)}) {
                properties.setContentTtl(valid);
                assertThat(validator.validate(properties)).isEmpty();
            }
            for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofHours(-1), Duration.ofDays(7).minusNanos(1), Duration.ofDays(7).plusNanos(1)}) {
                properties.setContentTtl(invalid);
                assertThat(validator.validate(properties)).isNotEmpty();
            }
        }
    }
    @Test
    void cleanupLimitsAndScheduleCannotBeUnboundedOrNonPositive() {
        var properties = new SizeChartDraftProperties();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(properties)).isEmpty();
            properties.setCleanupBatchSize(0);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setCleanupBatchSize(1001);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setCleanupBatchSize(100);
            properties.setCleanupMaxBatchesPerRun(0);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setCleanupMaxBatchesPerRun(101);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setCleanupMaxBatchesPerRun(10);
            properties.setCleanupFixedDelay(Duration.ZERO);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setCleanupFixedDelay(Duration.ofHours(1));
            properties.setCleanupInitialDelay(Duration.ZERO);
            assertThat(validator.validate(properties)).isNotEmpty();
        }
    }

}
