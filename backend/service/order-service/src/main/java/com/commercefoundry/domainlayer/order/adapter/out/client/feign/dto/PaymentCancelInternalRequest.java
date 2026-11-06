package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.commercefoundry.shared.commerce.CurrencyCode;

/**
 * payment-service {@code POST /internal/v1/payments/{paymentId}/cancellations} 요청 (#16 PR-2). payment-service 의 DTO 를 가져다 쓰지 않는다.
 *
 * @param claimId 취소 클레임 아이디 — {@code Idempotency-Key} 헤더와 같은 값이어야 한다(다르면 409 {@code PAYMENT_019})
 * @param amount 환불액 (최소 단위)
 * @param currency 환불 통화 — 결제 통화와 같다
 */
public record PaymentCancelInternalRequest(long claimId, long amount, CurrencyCode currency) {
}
