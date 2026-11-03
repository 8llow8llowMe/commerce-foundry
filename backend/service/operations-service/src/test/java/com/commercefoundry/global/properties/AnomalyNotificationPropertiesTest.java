package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AnomalyNotificationPropertiesTest {
    @Test
    void guardsRetentionRecipientAndRetryBudget() {
        assertThat(new AnomalyNotificationProperties(false, Duration.ofHours(1), Duration.ofDays(8), 1000, 3, Duration.ZERO).enabled()).isFalse();
        assertThatThrownBy(() -> new AnomalyNotificationProperties(false, Duration.ofHours(1), Duration.ofDays(7), 1000, 3, Duration.ZERO))
            .hasMessage("Invalid anomaly notification configuration");
        assertThatThrownBy(() -> new AnomalyNotificationProperties(false, Duration.ofHours(1), Duration.ofDays(8), 1001, 3, Duration.ZERO))
            .hasMessage("Invalid anomaly notification configuration");
        assertThatThrownBy(() -> new AnomalyNotificationProperties(false, Duration.ofHours(1), Duration.ofDays(8), 1000, 4, Duration.ZERO))
            .hasMessage("Invalid anomaly notification configuration");
    }
}
