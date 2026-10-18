package com.commercefoundry.domainlayer.shippingfee.domain.model;

import java.util.Optional;

/**
 * 배송비 정책이 적용되는 배송 범위. 범위마다 정책이 하나다 ({@code uk_shipping_policy_scope}).
 *
 * <p>지금은 국내(KR)만 있다. 해외 배송비(#22)는 {@code INTERNATIONAL} 을 더하고 {@link #ofCountry} 가 KR 밖의 국가를 그쪽으로 보낸다.
 */
public enum ShippingScope {

    DOMESTIC;

    /** 국내 배송 국가 코드 (ISO 3166-1 alpha-2). */
    public static final String DOMESTIC_COUNTRY_CODE = "KR";

    /**
     * 배송 국가가 속한 범위.
     *
     * @param countryCode ISO 3166-1 alpha-2 대문자
     * @return 배송할 수 없는 국가면 빈 값
     */
    public static Optional<ShippingScope> ofCountry(String countryCode) {
        if (DOMESTIC_COUNTRY_CODE.equals(countryCode)) {
            return Optional.of(DOMESTIC);
        }
        return Optional.empty();
    }
}
