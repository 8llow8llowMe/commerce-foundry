package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 결제 후 취소 클레임의 무환불 판정 결과 (#691, {@code payment-guide.md} §8-1). 판정은 오류가 아니라 데이터다.
 *
 * @param retainedAmount 결제액 − 이 결제의 환불 합계(refundedTotal = SUCCEEDED + EXTERNAL_PG_REFUNDED + OFF_PG_REFUNDED, 판정에 쓴 로컬 원장 기준)
 * @param sealed 응답 시점에 이 claimId 가 payment 에서 영구 거부되는 상태인가(VOIDED 또는 REJECTED 행이 있음) — outcome 과 따로 본다.
 *     {@link Outcome#NO_REFUND_CONFIRMED} 이면 언제나 true 다
 */
public record PaymentCancelVoidInfo(
    long paymentId, long claimId, Outcome outcome, Reason reason, PaymentStatus paymentStatus, Money retainedAmount, boolean sealed
) {

    public PaymentCancelVoidInfo {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(paymentStatus, "paymentStatus");
        Objects.requireNonNull(retainedAmount, "retainedAmount");
        if (outcome == Outcome.NO_REFUND_CONFIRMED && !sealed) {
            throw new IllegalArgumentException("무환불 확정은 봉인된 클레임에만 답한다");
        }
    }

    public static PaymentCancelVoidInfo confirmed(long paymentId, long claimId, Reason reason, PaymentStatus paymentStatus, Money retainedAmount) {
        return new PaymentCancelVoidInfo(paymentId, claimId, Outcome.NO_REFUND_CONFIRMED, reason, paymentStatus, retainedAmount, true);
    }

    /** @param reason {@code CLAIM_REFUND_SUCCEEDED}(우리 PG 취소) 또는 {@code CLAIM_REFUNDED_EXTERNALLY}(외부 확인 환불, #693) */
    public static PaymentCancelVoidInfo refunded(long paymentId, long claimId, Reason reason, PaymentStatus paymentStatus, Money retainedAmount) {
        if (reason != Reason.CLAIM_REFUND_SUCCEEDED && reason != Reason.CLAIM_REFUNDED_EXTERNALLY) {
            throw new IllegalArgumentException("환불됨 판정의 이유가 아니다: " + reason);
        }
        return new PaymentCancelVoidInfo(paymentId, claimId, Outcome.REFUNDED, reason, paymentStatus, retainedAmount, false);
    }

    public static PaymentCancelVoidInfo unconfirmed(
        long paymentId, long claimId, Reason reason, PaymentStatus paymentStatus, Money retainedAmount, boolean sealed
    ) {
        return new PaymentCancelVoidInfo(paymentId, claimId, Outcome.UNCONFIRMED, reason, paymentStatus, retainedAmount, sealed);
    }

    public enum Outcome {
        /** 이 클레임으로 환불이 없었고 앞으로도 없다(봉인), PG 잔액이 원장과 같다 — 주문은 클레임을 거절해도 된다. */
        NO_REFUND_CONFIRMED,
        /** 이 클레임으로 환불이 확정됐다 — 거절하면 안 된다. */
        REFUNDED,
        /** 지금은 어느 쪽도 확정하지 못한다 — 거절을 막는다. */
        UNCONFIRMED
    }

    public enum Reason {
        /** 이 claimId 로 PG 취소를 보낸 적이 없다(행 없음 또는 VOIDED). */
        CLAIM_NEVER_REQUESTED,
        /** PG 가 이 클레임의 취소를 확정 거절했고 정착 유예가 지났다. */
        PG_REJECTED,
        /** 이 claimId 의 우리 PG 취소가 성공했다(SUCCEEDED). */
        CLAIM_REFUND_SUCCEEDED,
        /** 이 claimId 의 환불을 우리 PG 취소 밖에서 확인해 기록했다(EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED, #693). */
        CLAIM_REFUNDED_EXTERNALLY,
        CLAIM_REFUND_PENDING,
        /** PG 거절을 기록한 지 유예 시간이 지나지 않았다. */
        REJECTION_SETTLING,
        /** 같은 결제에 다른 클레임의 PENDING 취소가 있다 — PG 잔액이 곧 바뀔 수 있다. */
        OTHER_CANCEL_PENDING,
        /** 남은 결제 금액(결제액 − 환불 합계 refundedTotal)이 이 클레임의 환불액보다 적다. 요청액이 결제액보다 큰 경우도 여기다. */
        RETAINED_SHORT,
        /** PG 잔액 조회에 답이 없다(장애 · 응답 계약 위반 등). */
        PG_UNAVAILABLE,
        /** 대조 대상이 아닌 결제다(가상계좌 · KRW 외 · 결제키 없음) — 자동으로 확정할 수 없다. */
        PG_CHECK_UNSUPPORTED,
        /** PG 잔액이 원장 기대 잔액과 다르다 — 원장에 없는 환불(콘솔 취소 등)이 있다. */
        PG_LEDGER_MISMATCH,
        /** 결제 상태가 DONE · PARTIAL_CANCELED · CANCELED 가 아니다. */
        PAYMENT_STATE_UNSUPPORTED,
        /** 이 claimId 행이 판정 규칙이 모르는 상태다 — 닫힌 쪽(미확정)으로 답한다. */
        CLAIM_STATE_UNSUPPORTED,
        /** PG 조회 사이에 원장이 바뀌었다 — 봉인하지 않았다. 다시 부르면 새로 판정한다. */
        LEDGER_CHANGED
    }
}
