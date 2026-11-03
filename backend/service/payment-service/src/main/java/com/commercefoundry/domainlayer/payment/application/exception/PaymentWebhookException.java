package com.commercefoundry.domainlayer.payment.application.exception;

import lombok.Getter;

@Getter
public class PaymentWebhookException extends RuntimeException {
    private final PaymentWebhookErrorCode errorCode;
    public PaymentWebhookException(PaymentWebhookErrorCode errorCode) { super(errorCode.getMessage()); this.errorCode = errorCode; }
    public PaymentWebhookException(PaymentWebhookErrorCode errorCode, Throwable cause) { super(errorCode.getMessage(), cause); this.errorCode = errorCode; }
    public PaymentWebhookException(PaymentWebhookErrorCode errorCode, Object... args) { this(errorCode); }
}
