package com.commercefoundry.shared.commerce;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 배송 범위 — 국내(KR) · 그 밖. 배송비 정책(delivery) · 주문(order)이 같은 뜻으로 쓴다 ({@code architecture-guide.md} §1-1).
 *
 * <p>국내(KR)는 {@link #DOMESTIC}, 그 밖의 국가는 {@link #INTERNATIONAL} 이다. 국내 국가 판정은 {@link #ofCountry} 한 곳에 둔다 —
 * 서비스마다 {@code "KR"} 을 따로 비교하면 국내 규칙이 갈라진다.
 * 해외 범위에 든다고 배송할 수 있는 것은 아니다 — 배송 가능 여부는 delivery 가 판정한다(스토어의 해외 배송 설정 · 해외 정책의 국가 그룹).
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
