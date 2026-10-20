package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.commercefoundry.common.dto.item.MoneyItem;

/**
 * delivery-service {@code POST /internal/v1/shipping-fees/quotes} 응답 본문 중 order-service 가 읽는 칸. 모르는 칸은 무시한다.
 *
 * <p>{@code freeShippingApplied} · {@code shippingPolicyVersion} 은 Wrapper 로 받는다 — 키가 빠진 응답이 false · 0 으로 읽혀 주문에 스냅숏되지 않고
 * 변환에서 계약 위반(503)이 되게.
 *
 * @param shippingFee 받을 배송비
 * @param baseFee 정책 기본 배송비 (해외는 기본료)
 * @param remoteAreaSurcharge 도서산간 추가 배송비. 아니거나 해외면 0
 * @param remoteAreaName 도서산간 구간 이름. 아니거나 해외면 null
 * @param freeShippingThreshold 무료배송 기준. 없으면 null
 * @param scope {@code DOMESTIC} · {@code INTERNATIONAL}
 * @param shippingZoneName 해외 국가 그룹 이름. 국내면 null
 * @param weightFee 해외 무게 요금. 국내면 null
 */
public record ShippingFeeQuoteInternalResponse(
    MoneyItem shippingFee, MoneyItem baseFee, Boolean freeShippingApplied, MoneyItem remoteAreaSurcharge, String remoteAreaName,
    MoneyItem freeShippingThreshold, Long shippingPolicyVersion, String scope, String shippingZoneName, MoneyItem weightFee
) {
}
