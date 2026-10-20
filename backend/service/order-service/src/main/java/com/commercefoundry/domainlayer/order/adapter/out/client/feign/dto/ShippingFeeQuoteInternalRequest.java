package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.commercefoundry.common.dto.item.MoneyItem;

/**
 * delivery-service {@code POST /internal/v1/shipping-fees/quotes} 요청 본문. delivery-service 의 DTO 를 가져다 쓰지 않는다 —
 * 서비스끼리 모듈 의존을 두지 않고, 원격 계약은 이 adapter 안에서만 안다.
 *
 * <p>null 칸(국내의 {@code weightGrams}, 우편번호 없는 해외의 {@code postalCode})도 그대로 보낸다 — delivery 는 국내면 무게를, 해외면 우편번호를 계산에 쓰지 않는다.
 *
 * @param orderAmount 할인 후 상품 합계(배송비 제외) — {@code {amount, currency}}
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode KR 이면 필수(숫자 5자리), 그 밖의 국가는 선택
 * @param weightGrams KR 외 국가면 필수(그램, 1 이상), KR 이면 null
 */
public record ShippingFeeQuoteInternalRequest(MoneyItem orderAmount, String countryCode, String postalCode, Integer weightGrams) {
}
