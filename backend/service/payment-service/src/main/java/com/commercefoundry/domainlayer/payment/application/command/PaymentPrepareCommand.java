package com.commercefoundry.domainlayer.payment.application.command;

/**
 * 결제 준비 — 어느 주문을 결제할지만 받는다. 금액 · 주문명은 order-service 에서 받는다 ({@code payment-guide.md} §3-1).
 *
 * @param orderId 주문 아이디
 */
public record PaymentPrepareCommand(long orderId) {
}
