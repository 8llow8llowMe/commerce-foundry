package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;
import java.time.Instant;

/**
 * 한 클레임의 PG 취소 요청. 미확정 결과는 같은 키로만 다시 시도한다.
 *
 * @param createdAt 처음 기록한 시각. 저장 전이면 null
 * @param updatedAt 마지막으로 바뀐 시각. 종결 상태(SUCCEEDED · REJECTED · VOIDED)는 다시 바뀌지 않으므로 그 상태가 된 시각이다. 저장 전이면 null
 */
public record PaymentCancel(long claimId, long paymentId, Money amount, Status status, Instant createdAt, Instant updatedAt) {

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

    public PaymentCancel succeeded() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("대기 중인 취소만 확정할 수 있습니다");
        }
        return new PaymentCancel(claimId, paymentId, amount, Status.SUCCEEDED, createdAt, updatedAt);
    }

    public PaymentCancel rejected() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("대기 중인 취소만 거절할 수 있습니다");
        }
        return new PaymentCancel(claimId, paymentId, amount, Status.REJECTED, createdAt, updatedAt);
    }

    /** PENDING → SUCCEEDED | REJECTED. VOIDED 는 무환불 봉인으로 처음부터 만들어지고 다른 상태로 가지 않는다(#691). */
    public enum Status {
        PENDING, SUCCEEDED, REJECTED, VOIDED;

        /**
         * 이 claimId 로는 다시 PG 취소를 보내지 않는 종결 상태인가 — 결제 후 취소 준비가 409 {@code PAYMENT_016} 으로 영구 거절한다.
         * 무환불 판정 응답의 {@code sealed} 도 이 값이다(#691).
         */
        public boolean refusesRefund() {
            return this == REJECTED || this == VOIDED;
        }
    }
}
