package com.commercefoundry.domainlayer.shippingfee.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 배송비 정책이 적용되는 배송 범위. 범위마다 정책이 하나다 ({@code uk_shipping_policy_scope}).
 *
 * <p>국내(KR)는 {@link #DOMESTIC}, 그 밖의 국가는 {@link #INTERNATIONAL} 이다. 해외 범위에 든다고 배송할 수 있는 것은 아니다 —
 * 해외 정책의 국가 그룹에 그 국가가 있어야 한다({@link InternationalShippingPolicy#findZone}).
 */
public enum ShippingScope {

    DOMESTIC,
    INTERNATIONAL;

    /** 국내 배송 국가 코드 (ISO 3166-1 alpha-2). */
    public static final String DOMESTIC_COUNTRY_CODE = "KR";

    private static final Pattern COUNTRY_CODE_FORMAT = Pattern.compile("^[A-Z]{2}$");

    /**
     * 배송 국가가 속한 범위.
     *
     * @param countryCode ISO 3166-1 alpha-2 대문자
     * @return 국가 코드 형식(대문자 두 글자)이 아니면 빈 값
     */
    public static Optional<ShippingScope> ofCountry(String countryCode) {
        if (countryCode == null || !COUNTRY_CODE_FORMAT.matcher(countryCode).matches()) {
            return Optional.empty();
        }
        return Optional.of(DOMESTIC_COUNTRY_CODE.equals(countryCode) ? DOMESTIC : INTERNATIONAL);
    }
}
