package com.commercefoundry.domainlayer.shippingaddress.application.command;

import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;

/**
 * 배송지 내용 (생성 · 전체 교체 공용). 요청 검증을 지나온 값이고 정규화는 도메인({@link ShippingAddressContent#of})이 한다.
 * 기본 배송지 여부는 싣지 않는다 — 생성에서만 따로 받고, 전체 교체로는 바꾸지 않는다.
 */
public record ShippingAddressCommand(
    String label,
    String recipientName,
    String recipientPhone,
    String countryCode,
    String postalCode,
    String addressLine1,
    String addressLine2,
    String city,
    String stateProvince
) {

    public ShippingAddressContent toContent() {
        return ShippingAddressContent.of(label, recipientName, recipientPhone, countryCode, postalCode, addressLine1, addressLine2, city, stateProvince);
    }

    /** 개인정보(받는 사람 · 연락처 · 주소)를 싣지 않는다. */
    @Override
    public String toString() {
        return "ShippingAddressCommand[countryCode=" + countryCode + "]";
    }
}
