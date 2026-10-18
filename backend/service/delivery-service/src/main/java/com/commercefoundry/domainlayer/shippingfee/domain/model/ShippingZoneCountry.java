package com.commercefoundry.domainlayer.shippingfee.domain.model;

import java.util.Objects;

/**
 * 해외 국가 그룹에 든 국가 하나. 국내(KR)는 해외 그룹에 넣을 수 없다 — 국내 정책이 따로 있다.
 *
 * @param countryCode ISO 3166-1 alpha-2 대문자
 */
public record ShippingZoneCountry(long id, String countryCode) {

    public ShippingZoneCountry {
        Objects.requireNonNull(countryCode, "countryCode");
        if (ShippingScope.ofCountry(countryCode).orElse(null) != ShippingScope.INTERNATIONAL) {
            throw new IllegalArgumentException("해외 국가 그룹에는 KR 이 아닌 대문자 두 글자 국가 코드만 넣을 수 있습니다: " + countryCode);
        }
    }
}
