package com.commercefoundry.domainlayer.settlement.domain.model;

import java.util.Objects;

/**
 * 대사 판정이 찾은 불일치 하나. 금액 칸의 뜻은 종류마다 다르다 — {@link SettlementReconciliation}.
 *
 * @param paymentKey PG 결제키
 * @param paymentId 결제 시도 아이디 — 결제 기록이 없으면(MISSING_IN_LEDGER) null
 * @param type 종류
 * @param expectedAmount 우리 기록 쪽 금액 (최소 화폐 단위)
 * @param actualAmount PG 쪽 금액 (최소 화폐 단위, 음수일 수 있다)
 */
public record SettlementDetection(String paymentKey, Long paymentId, SettlementMismatchType type, long expectedAmount, long actualAmount) {

    public SettlementDetection {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(type, "type");
        if ((type == SettlementMismatchType.MISSING_IN_LEDGER) != (paymentId == null)) {
            throw new IllegalArgumentException("결제 기록 누락에만 결제 아이디가 없습니다");
        }
    }

    /** 결제키 전체를 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "SettlementDetection[paymentKey=%s, paymentId=%s, type=%s, expectedAmount=%d, actualAmount=%d]"
            .formatted(PgSettlementItem.maskPaymentKey(paymentKey), paymentId, type, expectedAmount, actualAmount);
    }
}
