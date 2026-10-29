package com.commercefoundry.domainlayer.onboarding.application.port.out.query;

import java.time.Instant;

/**
 * delivery-service 가 확인한 국내 배송비 정책 저장 사실 (#357).
 *
 * @param storeReady 국내 정책이 있는가 (누가 저장했든)
 * @param memberFirstSavedAt 이 운영자가 국내 정책을 처음 저장한 시각(UTC). 이력이 없으면 null
 */
public record ShippingPolicyFactQueryResult(boolean storeReady, Instant memberFirstSavedAt) {
}
