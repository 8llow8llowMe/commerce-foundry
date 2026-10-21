package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Objects;

/**
 * 주문 시점의 배송지 스냅숏 — {@code orders.order_shipping_address} 한 행. 회원 주소록(#7)을 바꿔도 지난 주문의 배송지는 바뀌지 않는다 ({@code order-flow-guide.md} §4).
 * 형식(국가 대문자 2자 · KR 우편번호 숫자 5자리 · 해외 우편번호 선택)은 요청 DTO 가 먼저 본다.
 * 개인정보라 로그에 남기지 않는다 — {@link #toString()} 은 국가만 보인다.
 *
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자) — 주문서 계산의 배송 국가와 같다
 * @param postalCode 우편번호. KR 이면 필수, 해외는 없으면 null
 * @param addressLine2 상세 주소. 없으면 null
 * @param deliveryMemo 배송 요청 사항. 없으면 null
 */
public record ShippingAddress(
    String recipientName, String recipientPhone, String countryCode, String postalCode, String addressLine1, String addressLine2, String deliveryMemo
) {

    public ShippingAddress {
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(recipientPhone, "recipientPhone");
        Objects.requireNonNull(countryCode, "countryCode");
        Objects.requireNonNull(addressLine1, "addressLine1");
    }

    @Override
    public String toString() {
        return "ShippingAddress[countryCode=" + countryCode + "]";
    }
}
