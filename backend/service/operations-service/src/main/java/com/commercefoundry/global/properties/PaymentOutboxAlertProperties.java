package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("operations.notifications.payment-outbox-alert")
public record PaymentOutboxAlertProperties(boolean enabled, Duration threshold, Duration suppression, Duration initialDelay,
                                          Duration fixedDelay, Duration maxObservationAge, Duration lockAtMost) {
    public PaymentOutboxAlertProperties {
        threshold = bounded(threshold, Duration.ofMinutes(5), Duration.ofDays(1));
        suppression = bounded(suppression, Duration.ofHours(1), Duration.ofDays(7));
        initialDelay = bounded(initialDelay, Duration.ofMinutes(1), Duration.ofDays(1));
        fixedDelay = bounded(fixedDelay, Duration.ofMinutes(1), Duration.ofHours(1));
        maxObservationAge = bounded(maxObservationAge, Duration.ofSeconds(30), Duration.ofMinutes(5));
        lockAtMost = bounded(lockAtMost, Duration.ofMinutes(2), Duration.ofMinutes(10));
    }
    private static Duration bounded(Duration value, Duration fallback, Duration maximum) {
        value = value == null ? fallback : value;
        if (value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid payment outbox alert duration");
        }
        return value;
    }
}
