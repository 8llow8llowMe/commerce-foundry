package com.commercefoundry.domainlayer.payment.application.exception;

/** 원문·원인을 포함하지 않는 충돌. 실패 트랜잭션 밖에서 재조회한다. */
public class PaymentWebhookConflictException extends RuntimeException {
    public PaymentWebhookConflictException() { super("webhook inbox changed"); }
}
