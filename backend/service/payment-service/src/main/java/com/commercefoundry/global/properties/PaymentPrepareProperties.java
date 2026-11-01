package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 준비 설정 ({@code payment-guide.md} "고객 API 계약").
 *
 * @param minRemaining 결제창을 열 때 주문의 결제 기한까지 남아 있어야 하는 최소 시간 (기본 10분). 그보다 가까우면 결제 준비를 거절한다(409 {@code PAYMENT_004}) —
 *     고객이 인증을 마치기 전에 order-service 가 미결제 주문을 정리해 "돈은 빠졌는데 주문은 만료" 가 되는 창을 줄인다. 토스 인증 뒤 승인 제한도 10분이다
 */
@ConfigurationProperties(prefix = "payment.prepare")
public record PaymentPrepareProperties(Duration minRemaining) {

    private static final Duration DEFAULT_MIN_REMAINING = Duration.ofMinutes(10);

    public PaymentPrepareProperties {
        if (minRemaining == null) {
            minRemaining = DEFAULT_MIN_REMAINING;
        }
        if (minRemaining.isNegative()) {
            throw new IllegalArgumentException("payment.prepare.min-remaining 은 0 이상이어야 합니다");
        }
    }
}
