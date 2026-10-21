package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 배송비 견적에 쓴 정책의 배송 범위 — delivery-service 견적 응답의 {@code scope} 다. 국내(KR)는 {@link #DOMESTIC}, 그 밖의 국가는 {@link #INTERNATIONAL} 이다.
 *
 * <p>주문 배송비 스냅숏에 남는 값이라 domain 에 둔다 — 주문 도메인이 들고 다니고, domain 은 application 을 import 하지 않는다
 * ({@code coding-conventions.md} §8-1). delivery 견적 응답의 {@code scope} 문자열을 읽는 order 쪽 값이다.
 *
 * <p>임시 — #144(shared-commerce {@code com.commercefoundry.shared.commerce.ShippingScope}) 머지 후 <b>#13 에서</b> 이 enum 을 지우고 import 를 바꾼다 —
 * 해외 판정을 쓰는 코드보다 먼저. 이름 · 시그니처를 같게 맞춰 두었다 ({@code architecture-guide.md} §1-1).
 * 두 enum 이 공존하는 동안 잘못 import 한 쪽과 {@code equals} 로 비교하면 컴파일은 되고 늘 false 라, order 코드는 범위를 {@code ==} · {@code !=} 로만 비교한다
 * (타입이 다르면 컴파일 오류로 잡힌다).
 */
public enum ShippingScope {

    DOMESTIC,
    INTERNATIONAL;

    /** 국내 정책으로 견적되는 배송 국가 코드 (ISO 3166-1 alpha-2). 국내 · 해외 판정은 {@link #ofCountry} 한 곳에서 한다. */
    public static final String DOMESTIC_COUNTRY_CODE = "KR";

    private static final Pattern COUNTRY_CODE_FORMAT = Pattern.compile("^[A-Z]{2}$");

    /**
     * 배송 국가가 속한 범위 — delivery 가 견적 정책을 고르는 판정과 같다. 통화는 보지 않는다 ({@code order-flow-guide.md} §2-2).
     *
     * <p>국가 형식은 요청 검증이 먼저 거르므로, order 의 호출하는 쪽은 빈 값을 조립 버그로 보고 {@link IllegalArgumentException} 을 던진다.
     *
     * @param countryCode ISO 3166-1 alpha-2 대문자
     * @return 국가 코드 형식(대문자 두 글자)이 아니면(null 포함) 빈 값. KR 이면 {@link #DOMESTIC}, 그 밖은 {@link #INTERNATIONAL}
     */
    public static Optional<ShippingScope> ofCountry(String countryCode) {
        if (countryCode == null || !COUNTRY_CODE_FORMAT.matcher(countryCode).matches()) {
            return Optional.empty();
        }
        return Optional.of(DOMESTIC_COUNTRY_CODE.equals(countryCode) ? DOMESTIC : INTERNATIONAL);
    }
}
