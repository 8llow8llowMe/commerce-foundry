package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.validation;

/**
 * 국가에 따라 규칙이 갈리는 칸 — 생성 · 전체 교체 요청이 같이 구현한다. {@link ValidShippingAddressRegion} 이 이 칸들만 본다.
 */
public interface ShippingAddressFields {

    String recipientPhone();

    String countryCode();

    String postalCode();

    String city();
}
