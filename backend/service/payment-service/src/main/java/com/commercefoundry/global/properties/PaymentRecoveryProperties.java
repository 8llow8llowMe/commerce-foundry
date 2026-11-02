package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 신규 결제 게이트와 독립인 복구 설정과 인스턴스별 처리 예산. */
@ConfigurationProperties("payment.recovery")
public record PaymentRecoveryProperties(
    boolean enabled, Duration initialDelay, Duration fixedDelay, Duration unknownInquiryAfter,
    Duration staleInProgressAfter, Duration returnedReadyAfter, Duration manualReviewAfter,
    Integer pageSize, Integer maxPagesPerStatusPerTick
) {
    public PaymentRecoveryProperties {
        initialDelay = positive(initialDelay, Duration.ofMinutes(1));
        fixedDelay = positive(fixedDelay, Duration.ofMinutes(1));
        unknownInquiryAfter = positive(unknownInquiryAfter, Duration.ofMinutes(1));
        staleInProgressAfter = positive(staleInProgressAfter, Duration.ofMinutes(5));
        returnedReadyAfter = positive(returnedReadyAfter, Duration.ofMinutes(10));
        manualReviewAfter = positive(manualReviewAfter, Duration.ofHours(24));
        if (manualReviewAfter.compareTo(unknownInquiryAfter) < 0 || manualReviewAfter.compareTo(staleInProgressAfter) < 0
            || manualReviewAfter.compareTo(returnedReadyAfter) < 0) {
            throw new IllegalArgumentException("payment.recovery.manual-review-after must cover all inquiry thresholds");
        }
        pageSize = pageSize == null ? 10 : pageSize;
        maxPagesPerStatusPerTick = maxPagesPerStatusPerTick == null ? 1 : maxPagesPerStatusPerTick;
        if (pageSize < 1 || pageSize > 100 || maxPagesPerStatusPerTick < 1 || maxPagesPerStatusPerTick > 10) {
            throw new IllegalArgumentException("payment.recovery page budgets are outside supported bounds");
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        Duration result = value == null ? fallback : value;
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException("payment.recovery durations must be positive");
        }
        return result;
    }
}
