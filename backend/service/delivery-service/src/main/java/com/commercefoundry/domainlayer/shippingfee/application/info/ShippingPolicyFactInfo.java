package com.commercefoundry.domainlayer.shippingfee.application.info;

import java.time.Instant;

/**
 * 운영자 온보딩의 "국내 배송비 정책 저장" 사실 (#357).
 *
 * @param storeReady         국내 정책 행이 있는가 — 누가 저장했든 스토어에 국내 배송비가 준비됐다
 * @param memberFirstSavedAt 그 운영자가 국내 정책을 처음 저장한 시각. 저장한 적이 없으면 null
 */
public record ShippingPolicyFactInfo(
    boolean storeReady,
    Instant memberFirstSavedAt
) {
}
