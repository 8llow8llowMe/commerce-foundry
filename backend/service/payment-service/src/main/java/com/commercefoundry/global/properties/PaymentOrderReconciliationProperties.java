package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.order-result.reconciliation")
public record PaymentOrderReconciliationProperties(
    boolean enabled, Duration initialDelay, Duration fixedDelay, Duration lockAtMostFor, Integer pageSize, Integer maxPagesPerLane
) {
    public PaymentOrderReconciliationProperties {
        initialDelay = positive(initialDelay, Duration.ofMinutes(1));
        fixedDelay = positive(fixedDelay, Duration.ofMinutes(1));
        lockAtMostFor = positive(lockAtMostFor, Duration.ofMinutes(10));
        pageSize = pageSize == null ? 10 : pageSize;
        maxPagesPerLane = maxPagesPerLane == null ? 1 : maxPagesPerLane;
        if (pageSize < 1 || pageSize > 100 || maxPagesPerLane < 1 || maxPagesPerLane > 10) {
            throw new IllegalArgumentException("payment.order-result.reconciliation page budgets are outside supported bounds");
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        Duration result = value == null ? fallback : value;
        if (result.isNegative() || result.isZero()) {
            throw new IllegalArgumentException("payment.order-result.reconciliation durations must be positive");
        }
        return result;
    }
}
