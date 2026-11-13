package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.Collection;
import java.util.Objects;

/**
 * 한 결제의 취소 원장 합계 ({@code payment-guide.md} §8-1 "합계 판정의 전제"). 취소 합계는 이 값 하나로만 낸다.
 *
 * <ul>
 *   <li>{@code pgCanceled} — PG 에서 실제로 취소된 금액: {@code SUCCEEDED} + {@code EXTERNAL_PG_REFUNDED}. PG 기대 잔액 = 결제액 − 이 값이고,
 *     PG 취소 누계 · 결제 상태 전이 · 자동 취소 남은 금액도 이 값이다</li>
 *   <li>{@code refundedTotal} — 고객에게 환불이 확정된 금액: {@code pgCanceled} + {@code OFF_PG_REFUNDED}. 결제 후 취소 한도 · 남은 금액(retained)이 이 값이다</li>
 * </ul>
 *
 * <p>{@code PENDING} · {@code REJECTED} · {@code VOIDED} 는 어느 합계에도 넣지 않는다.
 */
public record PaymentCancelLedger(Money pgCanceled, Money refundedTotal) {

    public PaymentCancelLedger {
        Objects.requireNonNull(pgCanceled, "pgCanceled");
        Objects.requireNonNull(refundedTotal, "refundedTotal");
        if (pgCanceled.currency() != refundedTotal.currency()) {
            throw new IllegalArgumentException("두 취소 합계의 통화가 다릅니다");
        }
        if (pgCanceled.isGreaterThan(refundedTotal)) {
            throw new IllegalArgumentException("PG 취소 합계는 환불 합계를 넘을 수 없습니다");
        }
    }

    /** @throws IllegalArgumentException 행의 통화가 {@code currency} 와 다를 때 */
    public static PaymentCancelLedger of(Collection<PaymentCancel> cancels, CurrencyCode currency) {
        Money pgCanceled = Money.zero(currency);
        Money refunded = Money.zero(currency);
        for (PaymentCancel cancel : cancels) {
            if (cancel.status().reducesPgBalance()) {
                pgCanceled = pgCanceled.plus(cancel.amount());
            }
            if (cancel.status().countsAsRefunded()) {
                refunded = refunded.plus(cancel.amount());
            }
        }
        return new PaymentCancelLedger(pgCanceled, refunded);
    }

    /** PG 밖(OFF_PG)으로 환불한 합계. PG 잔액에는 보이지 않는다. */
    public Money offPgRefunded() {
        return refundedTotal.minus(pgCanceled);
    }

    public boolean hasOffPgRefund() {
        return !offPgRefunded().isZero();
    }

    /** PG 가 갖고 있어야 할 잔액 = 결제액 − {@code pgCanceled}. */
    public Money expectedPgBalance(Money paid) {
        return paid.minus(pgCanceled);
    }

    /** 아직 환불되지 않은 금액 = 결제액 − {@code refundedTotal}. */
    public Money retained(Money paid) {
        return paid.minus(refundedTotal);
    }

    /**
     * PG 관측 잔액이 외부 확인 환불 신고를 설명하는가 (#693, {@code payment-guide.md} §8). 원장에 없는 환불액 = (결제액 − {@code pgCanceled}) − 관측 잔액.
     *
     * <ul>
     *   <li>{@code EXTERNAL_PG_REFUNDED} — 원장에 없는 환불액 = 금액이면 {@link Evidence#EXACT}, 금액보다 크면 {@link Evidence#SURPLUS}(다른 클레임의 몫이 섞였을 수 있음)</li>
     *   <li>{@code OFF_PG_REFUNDED} — 원장에 없는 환불액 = 0 일 때만 {@link Evidence#EXACT}</li>
     *   <li>관측 잔액이 기대 잔액보다 크면(원장의 PG 취소가 PG 에 없음) 어느 쪽도 {@link Evidence#NONE}</li>
     * </ul>
     *
     * @param refunded 기록하려는 상태 — {@code EXTERNAL_PG_REFUNDED} 또는 {@code OFF_PG_REFUNDED}
     */
    public Evidence externalRefundEvidence(Money paid, Money observedBalance, Money amount, PaymentCancel.Status refunded) {
        Objects.requireNonNull(observedBalance, "observedBalance");
        Objects.requireNonNull(amount, "amount");
        if (refunded == null || !refunded.refundedExternally()) {
            throw new IllegalArgumentException("외부 확인 환불 상태가 아닙니다: " + refunded);
        }
        Money expected = expectedPgBalance(paid);
        if (observedBalance.isGreaterThan(expected)) {
            return Evidence.NONE;
        }
        Money unexplained = expected.minus(observedBalance);
        if (refunded == PaymentCancel.Status.OFF_PG_REFUNDED) {
            return unexplained.isZero() ? Evidence.EXACT : Evidence.NONE;
        }
        if (unexplained.equals(amount)) {
            return Evidence.EXACT;
        }
        return unexplained.isGreaterThan(amount) ? Evidence.SURPLUS : Evidence.NONE;
    }

    /** 외부 확인 환불 신고에 대한 PG 증거. {@code EXACT} · {@code SURPLUS} 면 기록해도 된다. */
    public enum Evidence {
        /** 원장에 없는 환불액이 신고와 정확히 맞는다. */
        EXACT,
        /** PG 채널 — 원장에 없는 환불액이 신고 금액보다 크다. 기록하되 claim 별 귀속은 확인하지 못했다(거래키 저장 후속). */
        SURPLUS,
        /** 신고를 설명하지 못한다. */
        NONE;

        public boolean recordable() {
            return this != NONE;
        }
    }
}
