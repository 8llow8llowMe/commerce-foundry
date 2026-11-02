package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;
import java.time.Instant;

/** 한 클레임의 PG 취소 요청. 미확정 결과는 같은 키로만 다시 시도한다. */
public record PaymentCancel(long claimId, long paymentId, Money amount, Status status, Instant createdAt) {

    public PaymentCancel(long claimId, long paymentId, Money amount, Status status) {
        this(claimId, paymentId, amount, status, null);
    }

    public PaymentCancel {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        if (claimId <= 0 || paymentId <= 0 || amount.isZero()) {
            throw new IllegalArgumentException("취소 식별자와 금액은 양수여야 합니다");
        }
    }

    public PaymentCancel succeeded() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("대기 중인 취소만 확정할 수 있습니다");
        }
        return new PaymentCancel(claimId, paymentId, amount, Status.SUCCEEDED, createdAt);
    }

    public PaymentCancel rejected() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("대기 중인 취소만 거절할 수 있습니다");
        }
        return new PaymentCancel(claimId, paymentId, amount, Status.REJECTED, createdAt);
    }

    public enum Status { PENDING, SUCCEEDED, REJECTED }
}
