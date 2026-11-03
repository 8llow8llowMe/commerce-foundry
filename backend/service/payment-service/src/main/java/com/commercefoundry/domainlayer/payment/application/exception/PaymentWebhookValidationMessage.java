package com.commercefoundry.domainlayer.payment.application.exception;

public final class PaymentWebhookValidationMessage {
    public static final String EVENT = "PAYMENT_WEBHOOK_101:지원하는 이벤트 타입이 필요합니다.";
    public static final String CREATED = "PAYMENT_WEBHOOK_102:createdAt은 올바른 ISO 날짜·시각이어야 합니다.";
    public static final String DATA = "PAYMENT_WEBHOOK_103:data는 필수입니다.";
    public static final String ORDER = "PAYMENT_WEBHOOK_104:orderId는 6~64자 영문·숫자·밑줄·하이픈이어야 합니다.";
    public static final String KEY = "PAYMENT_WEBHOOK_105:paymentKey는 1~200자여야 합니다.";
    private PaymentWebhookValidationMessage() { }
}
