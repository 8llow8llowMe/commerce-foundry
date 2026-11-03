package com.commercefoundry.domainlayer.payment.application.exception;

/** 조회 경계의 실패 분류. 원격 본문 · 값 · 원인 예외는 보존하지 않는다. */
public final class OrderPaymentResultQueryException extends RuntimeException {

    private final Failure failure;

    public OrderPaymentResultQueryException(Failure failure) {
        super("Order payment result query failed: " + failure.name());
        this.failure = failure;
    }

    public Failure getFailure() {
        return failure;
    }

    public enum Failure {
        NOT_READY, UNAVAILABLE, UNAUTHORIZED, REJECTED, CONTRACT_VIOLATION
    }
}
