package com.commercefoundry.domainlayer.payment.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentWebhookErrorCode {
    DISABLED("PAYMENT_WEBHOOK_001", "웹훅 수신이 중지되어 있습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    NOT_FOUND("PAYMENT_WEBHOOK_002", "결제 시도를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    BINDING("PAYMENT_WEBHOOK_003", "웹훅 결제 정보가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID("PAYMENT_WEBHOOK_100", "올바르지 않은 웹훅 요청입니다.", HttpStatus.BAD_REQUEST),
    UNAVAILABLE("PAYMENT_WEBHOOK_004", "웹훅을 저장할 수 없습니다. 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);
    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
