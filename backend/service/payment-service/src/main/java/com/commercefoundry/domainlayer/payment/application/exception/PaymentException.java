package com.commercefoundry.domainlayer.payment.application.exception;

import lombok.Getter;

/**
 * 결제 업무 오류 ({@code coding-conventions.md} §8-1). 응답 메시지는 오류 코드의 메시지다 — 원인 예외의 메시지는 응답 · 로그에 싣지 않는다.
 */
@Getter
public class PaymentException extends RuntimeException {

    private final PaymentErrorCode errorCode;

    public PaymentException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public PaymentException(PaymentErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public PaymentException(PaymentErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
