package com.commercefoundry.domainlayer.payment.domain.model;

/**
 * 승인 요청 전({@link PaymentStatus#READY})에 결제 시도를 버린 이유 ({@code payment-guide.md} §3-1).
 */
public enum PaymentAbortReason {

    /** 같은 주문의 결제 준비를 새로 받았다 */
    REPREPARED,
    /** 쿠폰 · 배송비 · 할인이 바뀌어 주문 금액이 달라졌다 */
    AMOUNT_CHANGED,
    /** 결제 없이 주문이 만료 · 취소됐다 */
    ORDER_EXPIRED,
    /** 어드민이 버렸다 */
    ADMIN
}
