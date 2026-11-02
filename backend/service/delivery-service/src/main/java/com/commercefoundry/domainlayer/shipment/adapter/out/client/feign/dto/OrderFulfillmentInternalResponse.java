package com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto;

/**
 * order-service 출고 · 배송 완료 반영 응답 ({@code api-design-guide.md} §2-18). 아이디는 문자열이다. 모양 검증은 {@code OrderShipmentInternalResponseSupport} 가 한다.
 *
 * @param status 반영 뒤 주문 상태 제어 코드 — 정보다(판정에 쓰지 않는다)
 * @param countryCode 주문 배송지 스냅숏 국가 (ISO 3166-1 alpha-2)
 */
public record OrderFulfillmentInternalResponse(String orderId, String memberId, String status, String countryCode) {
}
