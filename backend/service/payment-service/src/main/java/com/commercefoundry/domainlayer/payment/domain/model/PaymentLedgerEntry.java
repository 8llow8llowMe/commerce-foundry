package com.commercefoundry.domainlayer.payment.domain.model;

import java.util.Objects;

/**
 * 결제 시도 하나와 그 취소 원장 합계 — 정산 대사(#219, {@code payment-guide.md} §10)가 PG 정산 거래와 맞춰 보는 우리 쪽 기록이다.
 * 취소 합계는 {@link PaymentCancelLedger} 로만 낸다(상태를 골라 직접 더하지 않는다).
 *
 * @param payment 결제 시도
 * @param cancelLedger 그 결제의 취소 원장 합계 — 통화는 결제 통화와 같다
 */
public record PaymentLedgerEntry(Payment payment, PaymentCancelLedger cancelLedger) {

    public PaymentLedgerEntry {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(cancelLedger, "cancelLedger");
        if (cancelLedger.pgCanceled().currency() != payment.amount().currency()) {
            throw new IllegalArgumentException("취소 원장 통화가 결제 통화와 다릅니다");
        }
    }
}
