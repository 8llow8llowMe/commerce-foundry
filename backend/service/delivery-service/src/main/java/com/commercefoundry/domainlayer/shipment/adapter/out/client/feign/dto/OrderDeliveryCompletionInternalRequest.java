package com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto;

import java.time.Instant;

/**
 * order-service {@code POST /internal/v1/orders/{orderId}/delivery-completion} 요청 ({@code api-design-guide.md} §2-18, #18 PR-B2). order-service 의 DTO 를 가져다 쓰지 않는다.
 *
 * @param deliveryId 송장 아이디 — 숫자 문자열. order 는 추적 로그에만 남긴다
 * @param deliveredAt 배송 완료 시각 (UTC ISO-8601) — order 의 {@code delivered_at} 이 되고 자동 구매 확정 기간의 시작이다
 */
public record OrderDeliveryCompletionInternalRequest(String deliveryId, Instant deliveredAt) {
}
