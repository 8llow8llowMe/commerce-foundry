package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("operations.notifications.dlt-alert")
public record DltAlertProperties(boolean enabled, Duration suppression) {
    public DltAlertProperties {
        if (suppression == null) {
            suppression = Duration.ofHours(1);
        }
        if (suppression.isNegative() || suppression.isZero() || suppression.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("DLT suppression must be positive and at most 7 days");
        }
    }
}
