package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 한 클레임의 PG 취소 요청. 미확정 결과는 같은 키로만 다시 시도한다.
 *
 * @param createdAt 처음 기록한 시각. 저장 전이면 null
 * @param updatedAt 마지막으로 바뀐 시각. 저장 전이면 null. {@code REJECTED} 에서는 거절을 기록한 시각이다(무환불 판정 · 외부 환불 기록의 유예 기준) —
 *     {@code REJECTED} 에서 나가는 전이는 외부 확인 환불(#693) 하나뿐이고, 그 뒤 행은 다시 바뀌지 않는다
 */
public record PaymentCancel(long claimId, long paymentId, Money amount, Status status, Instant createdAt, Instant updatedAt) {

    /**
     * 같은 claimId(PG 멱등 키)로 PG 취소를 다시 보낼 수 있는 기간 — 생성 뒤 이보다 오래된 {@code PENDING} 은 재전송하지 않는다.
     *
     * <p>출처: 토스 멱등 키(Idempotency-Key)는 첫 요청 후 15일만 유효하고, 그 뒤 같은 키 재전송은 새 요청으로 처리돼 이중 취소가 될 수 있다.
     * 그래서 결제 후 취소 준비({@code PaymentCancelProcessor.prepare})는 하루 여유를 둔 14일 기한(지나면 503 {@code PAYMENT_018})을 쓰고,
     * order 재시도도 {@code max-backoff × max-attempts ≤ 14d} 로 맞춘다({@code payment-guide.md} §8 "order → payment 결제 후 취소 호출").
     * 외부 확인 환불 기록은 이 기한 + 여유가 지난 {@code PENDING} 만 바꾼다(#693).
     */
    public static final Duration PG_RETRY_WINDOW = Duration.ofDays(14);

    public PaymentCancel(long claimId, long paymentId, Money amount, Status status) {
        this(claimId, paymentId, amount, status, null, null);
    }

    public PaymentCancel(long claimId, long paymentId, Money amount, Status status, Instant createdAt) {
        this(claimId, paymentId, amount, status, createdAt, null);
    }

    public PaymentCancel {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        if (claimId <= 0 || paymentId <= 0 || amount.isZero()) {
            throw new IllegalArgumentException("취소 식별자와 금액은 양수여야 합니다");
        }
    }

    /**
     * 환불을 요청한 적 없는 클레임을 무환불로 봉인한다 (#691). 이 claimId 로는 앞으로 PG 취소를 보내지 않는다 — 결제 후 취소 준비가 영구 거절한다.
     * 금액은 접수 때 클레임에 저장한 환불액이며 취소 합계에 넣지 않는다.
     */
    public static PaymentCancel voided(long claimId, long paymentId, Money amount) {
        return new PaymentCancel(claimId, paymentId, amount, Status.VOIDED);
    }

    /**
     * 우리 PG 취소 호출 없이 끝난 환불을 행이 없는 claimId 에 새로 기록한다 (#693).
     *
     * @param refunded {@code EXTERNAL_PG_REFUNDED} 또는 {@code OFF_PG_REFUNDED}
     */
    public static PaymentCancel externallyRefunded(long claimId, long paymentId, Money amount, Status refunded) {
        requireExternal(refunded);
        return new PaymentCancel(claimId, paymentId, amount, refunded);
    }

    public PaymentCancel succeeded() {
        return moveTo(Status.SUCCEEDED, "대기 중인 취소만 확정할 수 있습니다");
    }

    public PaymentCancel rejected() {
        return moveTo(Status.REJECTED, "대기 중인 취소만 거절할 수 있습니다");
    }

    /**
     * 기존 행을 외부 확인 환불로 바꾼다 (#693). 허용 전이는 {@link Status#canBecome} 표 그대로다 — PENDING · REJECTED → 두 상태, VOIDED → EXTERNAL_PG_REFUNDED.
     * 언제 바꿔도 되는지(PENDING 의 경과 시간 · REJECTED 의 유예 · PG 증거)는 호출자가 결제 행 잠금 아래에서 판정한다.
     *
     * @param refunded {@code EXTERNAL_PG_REFUNDED} 또는 {@code OFF_PG_REFUNDED}
     * @throws IllegalStateException 표에 없는 전이
     */
    public PaymentCancel externallyRefunded(Status refunded) {
        requireExternal(refunded);
        return moveTo(refunded, "외부 확인 환불로 바꿀 수 없는 취소 상태입니다: " + status);
    }

    private PaymentCancel moveTo(Status next, String refusal) {
        if (!status.canBecome(next)) {
            throw new IllegalStateException(refusal);
        }
        return new PaymentCancel(claimId, paymentId, amount, next, createdAt, updatedAt);
    }

    private static void requireExternal(Status refunded) {
        if (refunded == null || !refunded.refundedExternally()) {
            throw new IllegalArgumentException("외부 확인 환불 상태가 아닙니다: " + refunded);
        }
    }

    /**
     * PENDING → SUCCEEDED | REJECTED. VOIDED 는 무환불 봉인으로 처음부터 만들어진다(#691).
     * EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED 는 우리 PG 취소 호출 없이 끝난 환불을 원장에 남기는 종결 상태다(#693) —
     * 새로 만들거나 PENDING · REJECTED · VOIDED 에서 {@link #canBecome} 표대로만 온다.
     *
     * <p>취소 합계는 {@link PaymentCancelLedger} 한 곳에서만 낸다 — 상태를 직접 골라 더하지 않는다.
     */
    public enum Status {
        PENDING, SUCCEEDED, REJECTED, VOIDED,
        /** PG 상점관리자(콘솔) 등 우리 취소 호출 밖에서 PG 가 환불했다 — PG 잔액이 줄었다. */
        EXTERNAL_PG_REFUNDED,
        /** PG 밖(계좌 송금 등)으로 환불했다 — PG 잔액은 그대로다. 결제 상태(payment.status)에는 반영하지 않는다. */
        OFF_PG_REFUNDED;

        /**
         * 허용 전이 표 — 저장 어댑터도 이 표로 기존 행의 상태 변경을 거른다. 같은 상태로의 저장은 전이가 아니라 여기서 다루지 않는다.
         *
         * <ul>
         *   <li>PENDING → SUCCEEDED · REJECTED(우리 PG 취소 결과) · EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED(PG 재전송 기간이 지난 뒤의 외부 확인 환불)</li>
         *   <li>REJECTED → EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED(거절 뒤 콘솔 · 송금으로 환불)</li>
         *   <li>VOIDED → EXTERNAL_PG_REFUNDED(봉인 뒤 콘솔 환불 — PG 증거가 있을 때만). PG 밖 신고는 받지 않는다</li>
         *   <li>SUCCEEDED · EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED 는 종결이다</li>
         * </ul>
         */
        public boolean canBecome(Status next) {
            return switch (this) {
                case PENDING -> next == SUCCEEDED || next == REJECTED || next == EXTERNAL_PG_REFUNDED || next == OFF_PG_REFUNDED;
                case REJECTED -> next == EXTERNAL_PG_REFUNDED || next == OFF_PG_REFUNDED;
                case VOIDED -> next == EXTERNAL_PG_REFUNDED;
                case SUCCEEDED, EXTERNAL_PG_REFUNDED, OFF_PG_REFUNDED -> false;
            };
        }

        /**
         * 이 claimId 로는 다시 PG 취소를 보내지 않는 종결 상태인가 — 결제 후 취소 준비가 409 {@code PAYMENT_016} 으로 영구 거절한다.
         * 무환불 판정 응답의 {@code sealed} 도 이 값이다(#691).
         */
        public boolean refusesRefund() {
            return this == REJECTED || this == VOIDED;
        }

        /** PG 에서 실제로 취소된 금액인가 — PG 기대 잔액 · PG 취소 누계 · 결제 상태 전이에 넣는다. */
        public boolean reducesPgBalance() {
            return this == SUCCEEDED || this == EXTERNAL_PG_REFUNDED;
        }

        /** 이 클레임의 환불이 확정됐는가(경로 무관) — 환불 한도 · 남은 금액(retained)에 넣고, 결제 후 취소 재요청은 PG 없이 200 이다. */
        public boolean countsAsRefunded() {
            return reducesPgBalance() || this == OFF_PG_REFUNDED;
        }

        /** 우리 PG 취소 호출 없이 확정한 환불인가(#693). */
        public boolean refundedExternally() {
            return this == EXTERNAL_PG_REFUNDED || this == OFF_PG_REFUNDED;
        }
    }
}
