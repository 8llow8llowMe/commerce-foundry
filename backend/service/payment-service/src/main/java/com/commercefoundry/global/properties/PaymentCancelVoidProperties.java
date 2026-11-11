package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 후 취소 무환불 판정 설정 ({@code payment-guide.md} §8-1, #691).
 *
 * @param rejectionSettleGrace PG 거절(REJECTED)을 기록한 뒤 무환불 근거로 쓰기까지 기다리는 시간 (기본 60초). 그 안에는 미확정(REJECTION_SETTLING)으로 답한다 —
 *     거절 응답 직후 PG 잔액 조회가 아직 정착하지 않은 값을 볼 수 있는 창을 피한다. 0 이하는 기동 실패다
 */
@ConfigurationProperties(prefix = "payment.cancellation.void")
public record PaymentCancelVoidProperties(Duration rejectionSettleGrace) {

    private static final Duration DEFAULT_REJECTION_SETTLE_GRACE = Duration.ofSeconds(60);

    public PaymentCancelVoidProperties {
        if (rejectionSettleGrace == null) {
            rejectionSettleGrace = DEFAULT_REJECTION_SETTLE_GRACE;
        }
        if (rejectionSettleGrace.isNegative() || rejectionSettleGrace.isZero()) {
            throw new IllegalArgumentException("payment.cancellation.void.rejection-settle-grace 는 0보다 커야 합니다");
        }
    }
}
