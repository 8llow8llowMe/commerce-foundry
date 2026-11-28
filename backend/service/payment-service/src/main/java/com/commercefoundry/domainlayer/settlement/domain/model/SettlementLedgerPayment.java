package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.util.Objects;

/**
 * 대사가 보는 우리 결제 기록 — payment 컨텍스트의 결제 시도 · 취소 원장 합계를 정산 판정에 필요한 칸만 옮긴 값이다(옮기는 일은 정산 Processor 가 한다).
 *
 * @param paymentId 결제 시도 아이디
 * @param paymentKey PG 결제키
 * @param amount 결제 금액 (최소 화폐 단위)
 * @param pgCanceled PG 에서 실제로 취소된 누계 — payment 취소 원장의 {@code pgCanceled}(SUCCEEDED + EXTERNAL_PG_REFUNDED). PG 밖 환불은 넣지 않는다
 * @param currency 통화
 * @param approved PG 가 승인한 적이 있는 상태인가(결제됨 계열 — DONE · PARTIAL_CANCELED · CANCELED · LATE_APPROVED)
 * @param approvedAt PG 승인 시각. 승인 전이면 null
 */
public record SettlementLedgerPayment(long paymentId, String paymentKey, long amount, long pgCanceled, CurrencyCode currency, boolean approved, Instant approvedAt) {

    public SettlementLedgerPayment {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(currency, "currency");
        if (amount <= 0 || pgCanceled < 0) {
            throw new IllegalArgumentException("결제 금액은 양수, PG 취소 누계는 0 이상이어야 합니다");
        }
    }

    /** 결제키 전체를 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "SettlementLedgerPayment[paymentId=%d, paymentKey=%s, amount=%d, pgCanceled=%d, currency=%s, approved=%s]"
            .formatted(paymentId, PgSettlementItem.maskPaymentKey(paymentKey), amount, pgCanceled, currency, approved);
    }
}
