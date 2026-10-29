package com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.dto;

import java.time.Instant;

/**
 * delivery-service {@code GET /internal/v1/onboarding-facts/shipping-policy} 응답 본문 중 operations-service 가 읽는 칸.
 * delivery-service 의 DTO 를 가져다 쓰지 않는다. 모르는 칸은 무시한다.
 *
 * @param storeReady 국내 정책이 있는가. 빠지면(null) 계약 위반이다 — false 로 추측하지 않게 박싱 타입으로 받는다
 * @param memberFirstSavedAt 이 운영자의 국내 정책 최초 저장 시각(UTC ISO). 없으면 null
 */
public record ShippingPolicyFactInternalResponse(Boolean storeReady, Instant memberFirstSavedAt) {
}
