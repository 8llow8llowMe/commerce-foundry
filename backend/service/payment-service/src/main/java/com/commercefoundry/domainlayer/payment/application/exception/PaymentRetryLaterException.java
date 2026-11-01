package com.commercefoundry.domainlayer.payment.application.exception;

import java.time.Duration;
import java.util.Objects;
import lombok.Getter;

/**
 * 같은 요청을 잠시 뒤 다시 보내면 되는 결제 오류 — 응답에 {@code Retry-After} 를 싣는다 ({@code coding-conventions.md} §8-1 "응답 헤더가 필요한 오류").
 * 예: 결제사에 승인 요청을 보내지 못해 결제 시도를 READY 로 되돌렸다(503 {@code PAYMENT_010}). 초 변환은 핸들러가 한다.
 */
@Getter
public class PaymentRetryLaterException extends PaymentException {

    private final Duration retryAfter;

    public PaymentRetryLaterException(PaymentErrorCode errorCode, Duration retryAfter) {
        super(errorCode);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }
}
