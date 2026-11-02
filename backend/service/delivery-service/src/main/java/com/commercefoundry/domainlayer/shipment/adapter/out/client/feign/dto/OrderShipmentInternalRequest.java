package com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto;

import java.time.Instant;

/**
 * order-service {@code POST /internal/v1/orders/{orderId}/shipment} 요청 ({@code api-design-guide.md} §2-18). order-service 의 DTO 를 가져다 쓰지 않는다 —
 * 원격 계약은 이 adapter 안에서만 안다.
 *
 * @param deliveryId 송장 아이디 — 숫자 문자열(JS 정밀도와 무관하게 order 계약이 문자열이다). order 는 추적 로그에만 남긴다
 * @param shippedAt 출고 시각 (UTC ISO-8601)
 */
public record OrderShipmentInternalRequest(String deliveryId, Instant shippedAt) {
}
