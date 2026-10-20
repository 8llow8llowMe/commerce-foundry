package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.commercefoundry.common.dto.item.MoneyItem;

/**
 * delivery-service {@code POST /internal/v1/shipping-fees/quotes} 요청 본문. delivery-service 의 DTO 를 가져다 쓰지 않는다 —
 * 서비스끼리 모듈 의존을 두지 않고, 원격 계약은 이 adapter 안에서만 안다.
 *
 * <p>delivery 의 본문 규칙 (delivery-service {@code ShippingFeeQuoteInternalRequest} · {@code QuoteDestinationValidator}):
 * <ul>
 *   <li>{@code countryCode} — {@code ^[A-Z]{2}$}</li>
 *   <li>KR — {@code postalCode} 필수, {@code ^[0-9]{5}$}</li>
 *   <li>KR 외 — {@code postalCode} 선택, 있으면 {@code ^[A-Za-z0-9 -]{1,20}$}(비었거나 공백뿐이면 없는 값). {@code weightGrams} 필수, 1 이상</li>
 * </ul>
 * 위반은 delivery 의 {@code DELIVERY_1xx}(400)이고 우리 쪽에서는 503 {@code ORDER_011} 이 된다 — 고객 입력 오류가 장애처럼 보인다.
 * 그래서 <b>호출하는 쪽(주문 요청 검증)이 이 규칙보다 느슨하면 안 된다</b>.
 *
 * <p>null 칸(국내의 {@code weightGrams}, 우편번호 없는 해외의 {@code postalCode})도 그대로 보낸다 — delivery 는 국내면 무게를, 해외면 우편번호를 계산에 쓰지 않는다.
 *
 * <p>{@link #toString()} 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다 ({@code coding-conventions.md} §8).
 *
 * @param orderAmount 할인 후 상품 합계(배송비 제외) — {@code {amount, currency}}
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode KR 이면 필수(숫자 5자리), 그 밖의 국가는 선택
 * @param weightGrams KR 외 국가면 필수(그램, 1 이상), KR 이면 null
 */
public record ShippingFeeQuoteInternalRequest(MoneyItem orderAmount, String countryCode, String postalCode, Integer weightGrams) {

    @Override
    public String toString() {
        return "ShippingFeeQuoteInternalRequest[orderAmount=%s, countryCode=%s, postalCode=%s, weightGrams=%s]"
            .formatted(orderAmount, countryCode, postalCode == null ? null : "***", weightGrams);
    }
}
