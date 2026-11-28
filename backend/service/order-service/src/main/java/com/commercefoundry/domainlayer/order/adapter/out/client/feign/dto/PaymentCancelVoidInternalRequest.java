package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.commercefoundry.shared.commerce.CurrencyCode;

/**
 * payment-service {@code POST /internal/v1/payments/{paymentId}/cancellations/{claimId}/void} 요청 (#17 A4, {@code payment-guide.md} §8-1).
 * payment-service 의 DTO 를 가져다 쓰지 않는다. 값은 접수 때 클레임에 저장한 것이다 — 다시 계산하지 않는다.
 *
 * @param orderId 클레임이 속한 주문 아이디 — payment 가 결제의 주문과 대조한다(다르면 409 {@code PAYMENT_019})
 * @param amount 클레임에 저장한 환불액 (최소 단위)
 * @param currency 환불 통화
 */
public record PaymentCancelVoidInternalRequest(long orderId, long amount, CurrencyCode currency) {
}
