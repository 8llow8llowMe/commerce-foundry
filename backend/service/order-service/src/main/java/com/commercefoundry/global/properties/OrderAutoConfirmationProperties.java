package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 자동 구매 확정 설정 · 조회. */
@ConfigurationProperties("order.purchase-confirmation.auto")
public record OrderAutoConfirmationProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("7d") Duration period,
    @DefaultValue("1h") Duration interval,
    @DefaultValue("100") int batchSize,
    @DefaultValue("10") int maxBatchesPerRun
) {
    public OrderAutoConfirmationProperties {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(interval, "interval");
        if (period.compareTo(Duration.ofDays(1)) < 0 || period.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("order.purchase-confirmation.auto.period must be 1d~30d: " + period);
        }
        if (interval.compareTo(Duration.ofMillis(1)) < 0 || interval.compareTo(Duration.ofMillis(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("order.purchase-confirmation.auto.interval must be positive milliseconds: " + interval);
        }
        if (batchSize < 1 || batchSize > 100 || maxBatchesPerRun < 1 || maxBatchesPerRun > 10) {
            throw new IllegalArgumentException("order.purchase-confirmation.auto batch-size must be 1~100 and max-batches-per-run 1~10");
        }
    }
}
