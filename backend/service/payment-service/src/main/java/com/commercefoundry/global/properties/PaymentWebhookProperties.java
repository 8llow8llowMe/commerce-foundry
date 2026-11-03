package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.webhook")
public record PaymentWebhookProperties(boolean enabled, Integer batchSize, Duration lease, Integer maxAttempts,
    Duration initialBackoff, Duration maxBackoff, Duration maxAge) {
    public PaymentWebhookProperties {
        batchSize = batchSize == null ? 20 : batchSize;
        maxAttempts = maxAttempts == null ? 6 : maxAttempts;
        lease = bounded(lease, Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(30));
        initialBackoff = bounded(initialBackoff, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofMinutes(5));
        maxBackoff = bounded(maxBackoff, Duration.ofMinutes(5), initialBackoff, Duration.ofHours(1));
        maxAge = bounded(maxAge, Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofDays(7));
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("payment.webhook budgets outside supported bounds");
        }
    }
    private static Duration bounded(Duration value, Duration fallback, Duration min, Duration max) {
        Duration result = value == null ? fallback : value;
        if (result.compareTo(min) < 0 || result.compareTo(max) > 0) { throw new IllegalArgumentException("payment.webhook duration outside bounds"); }
        return result;
    }
    public Duration backoff(int attempts) {
        Duration result = initialBackoff.multipliedBy(1L << Math.min(20, Math.max(0, attempts - 1)));
        return result.compareTo(maxBackoff) > 0 ? maxBackoff : result;
    }
}
