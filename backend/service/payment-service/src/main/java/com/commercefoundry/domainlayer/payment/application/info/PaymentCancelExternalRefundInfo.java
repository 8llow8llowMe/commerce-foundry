package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 외부 확인 환불 기록 결과 (#693, {@code payment-guide.md} §8 "외부 확인 환불 기록 명령"). 판정은 오류가 아니라 데이터다.
 *
 * @param channel 기록된 채널 — {@link Outcome#RECORDED} 에만 있고 나머지는 null 이다
 * @param refundedAmount 이 결제의 환불 확정 합계(refundedTotal = SUCCEEDED + EXTERNAL_PG_REFUNDED + OFF_PG_REFUNDED, 판정에 쓴 원장 기준)
 * @param retainedAmount 결제액 − {@code refundedAmount}
 */
public record PaymentCancelExternalRefundInfo(
    long paymentId, long claimId, Outcome outcome, Reason reason, Channel channel, PaymentStatus paymentStatus, Money refundedAmount, Money retainedAmount
) {

    public PaymentCancelExternalRefundInfo {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(paymentStatus, "paymentStatus");
        Objects.requireNonNull(refundedAmount, "refundedAmount");
        Objects.requireNonNull(retainedAmount, "retainedAmount");
        if ((outcome == Outcome.RECORDED) != (channel != null)) {
            throw new IllegalArgumentException("채널은 기록됨 판정에만 싣는다: " + outcome);
        }
    }

    /** @param reason {@code PG_VERIFIED}(이번에 기록) 또는 {@code CLAIM_ALREADY_RECORDED}(이미 기록된 claimId — PG 재조회 없음) */
    public static PaymentCancelExternalRefundInfo recorded(
        long paymentId, long claimId, Reason reason, Channel channel, PaymentStatus paymentStatus, Money refundedAmount, Money retainedAmount
    ) {
        if (reason != Reason.PG_VERIFIED && reason != Reason.CLAIM_ALREADY_RECORDED) {
            throw new IllegalArgumentException("기록됨 판정의 이유가 아니다: " + reason);
        }
        return new PaymentCancelExternalRefundInfo(paymentId, claimId, Outcome.RECORDED, reason, channel, paymentStatus, refundedAmount, retainedAmount);
    }

    public static PaymentCancelExternalRefundInfo alreadyRefunded(long paymentId, long claimId, PaymentStatus paymentStatus, Money refundedAmount, Money retainedAmount) {
        return new PaymentCancelExternalRefundInfo(
            paymentId, claimId, Outcome.ALREADY_REFUNDED, Reason.CLAIM_REFUND_SUCCEEDED, null, paymentStatus, refundedAmount, retainedAmount);
    }

    public static PaymentCancelExternalRefundInfo unconfirmed(
        long paymentId, long claimId, Reason reason, PaymentStatus paymentStatus, Money refundedAmount, Money retainedAmount
    ) {
        if (reason == Reason.PG_VERIFIED || reason == Reason.CLAIM_ALREADY_RECORDED || reason == Reason.CLAIM_REFUND_SUCCEEDED) {
            throw new IllegalArgumentException("미확정 판정의 이유가 아니다: " + reason);
        }
        return new PaymentCancelExternalRefundInfo(paymentId, claimId, Outcome.UNCONFIRMED, reason, null, paymentStatus, refundedAmount, retainedAmount);
    }

    public enum Outcome {
        /** 이 claimId 의 환불이 외부 확인 환불로 원장에 있다(이번에 기록했거나 이미 기록됨). order 는 수동 환불을 확정해도 된다. */
        RECORDED,
        /** 이 claimId 의 우리 PG 취소가 이미 성공했다(SUCCEEDED) — 외부 기록을 쓰지 않았다. 환불은 확정이다. */
        ALREADY_REFUNDED,
        /** 지금은 기록하지 못한다 — order 는 수동 확정을 막는다. */
        UNCONFIRMED
    }

    /** 운영자가 신고한 환불 경로. payment 가 PG 잔액으로 검증한다. */
    public enum Channel {
        /** PG 상점관리자(콘솔)에서 환불했다 — PG 잔액이 줄었어야 한다. */
        PG(PaymentCancel.Status.EXTERNAL_PG_REFUNDED),
        /** PG 밖(계좌 송금 등)으로 환불했다 — PG 잔액은 원장 그대로여야 한다. */
        OFF_PG(PaymentCancel.Status.OFF_PG_REFUNDED);

        private final PaymentCancel.Status status;

        Channel(PaymentCancel.Status status) {
            this.status = status;
        }

        /** 이 채널로 기록할 원장 상태. */
        public PaymentCancel.Status status() {
            return status;
        }

        /** 기록된 원장 상태의 채널. */
        public static Channel of(PaymentCancel.Status status) {
            return switch (status) {
                case EXTERNAL_PG_REFUNDED -> PG;
                case OFF_PG_REFUNDED -> OFF_PG;
                default -> throw new IllegalArgumentException("외부 확인 환불 상태가 아니다: " + status);
            };
        }
    }

    public enum Reason {
        /** PG 잔액으로 신고 채널을 검증하고 이번에 기록했다. */
        PG_VERIFIED,
        /** 이 claimId 가 이미 외부 확인 환불로 기록돼 있다 — PG 를 다시 조회하지 않았다. 응답 채널은 기록된 채널이다. */
        CLAIM_ALREADY_RECORDED,
        /** 이 claimId 의 우리 PG 취소가 성공했다(SUCCEEDED). */
        CLAIM_REFUND_SUCCEEDED,
        /** 결제 상태가 DONE · PARTIAL_CANCELED 가 아니다. */
        PAYMENT_STATE_UNSUPPORTED,
        /** 이 claimId 의 PENDING 이 PG 재전송 기간(14일) + 여유 안이다 — PG 취소가 진행 중일 수 있다. */
        CLAIM_REFUND_PENDING,
        /** PG 거절을 기록한 지 유예 시간이 지나지 않았다. */
        REJECTION_SETTLING,
        /** 이 claimId 가 무환불 봉인(VOIDED)인데 PG 밖 환불을 신고했다 — PG 증거로만 바꿀 수 있다. */
        CLAIM_VOIDED,
        /** 이 claimId 행이 규칙이 모르는 상태다 — 닫힌 쪽(미확정)으로 답한다. */
        CLAIM_STATE_UNSUPPORTED,
        /** 같은 결제에 다른 클레임의 PENDING 취소가 있다 — PG 잔액이 곧 바뀔 수 있다. */
        OTHER_CANCEL_PENDING,
        /** 남은 결제 금액(결제액 − refundedTotal)이 이 환불액보다 적다. */
        RETAINED_SHORT,
        /** PG 잔액 조회에 답이 없다(장애 · 응답 계약 위반 등). */
        PG_UNAVAILABLE,
        /** 대조 대상이 아닌 결제다(가상계좌 · KRW 외 · 결제키 없음) — 외부 환불을 검증할 수 없다. */
        PG_CHECK_UNSUPPORTED,
        /** PG 잔액이 신고 채널로 설명되지 않는다 — PG 는 원장에 없는 환불액 ≥ 금액, PG 밖은 원장에 없는 환불액 = 0 이어야 한다. */
        PG_LEDGER_MISMATCH,
        /** PG 조회 사이에 원장이 바뀌었다 — 기록하지 않았다. 다시 부르면 새로 판정한다. */
        LEDGER_CHANGED
    }
}
