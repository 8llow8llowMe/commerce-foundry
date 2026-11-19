package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("order.payment-restoration.sweep")
public record OrderPaymentRestorationSweepProperties(
    @DefaultValue("1m") Duration interval,
    @DefaultValue("5m") Duration lockAtMostFor,
    @DefaultValue("5") int batchSize,
    @DefaultValue("1m") Duration initialBackoff,
    @DefaultValue("1h") Duration maxBackoff
) {

    public OrderPaymentRestorationSweepProperties {
        if (batchSize < 1 || batchSize > 10 || interval.compareTo(Duration.ofSeconds(1)) < 0
            || interval.compareTo(Duration.ofDays(1)) > 0 || initialBackoff.compareTo(Duration.ofMinutes(1)) < 0
            || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("order.payment-restoration.sweep settings are outside supported bounds");
        }
        // due 조회 5초 + 행별 DB 30초/HTTP 15초 + 락 I/O 및 지연 여유 60초.
        if (lockAtMostFor.compareTo(Duration.ofSeconds(65 + 45L * batchSize)) < 0
            || lockAtMostFor.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("order.payment-restoration.sweep lock-at-most-for is outside the batch budget");
        }
    }
}
