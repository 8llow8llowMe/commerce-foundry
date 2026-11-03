package com.commercefoundry.domainlayer.payment.application.command;

/** 공급자 요청은 바인딩 확인과 fingerprint 계산에만 쓰며 저장하지 않는다. */
public record PaymentWebhookCommand(String eventType, String createdAt, String pgOrderId, String paymentKey) {
    @Override public String toString() { return "PaymentWebhookCommand[]"; }
}
