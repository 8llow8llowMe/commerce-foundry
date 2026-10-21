package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Objects;

/**
 * 배송비 견적에 쓴 정책의 배송 범위 — delivery-service 견적 응답의 {@code scope} 다. 국내(KR)는 {@link #DOMESTIC}, 그 밖의 국가는 {@link #INTERNATIONAL} 이다.
 *
 * <p>주문 배송비 스냅숏에 남는 값이라 domain 에 둔다 — 주문 도메인이 들고 다니고, domain 은 application 을 import 하지 않는다
 * ({@code coding-conventions.md} §8-1). delivery 견적 응답의 {@code scope} 문자열을 읽는 order 쪽 값이다. delivery 와 같은 enum 을
 * shared-commerce 로 올리는 일은 #144 에서 한다 ({@code architecture-guide.md} §1-1).
 */
public enum ShippingScope {

    DOMESTIC,
    INTERNATIONAL;

    /**
     * 국내 정책으로 견적되는 배송 국가. order-service 에서 국내 · 해외를 가르는 곳은 이 상수와 {@link #ofCountry} 하나다.
     *
     * <p>임시 — #144(shared-commerce 로 올림) 머지 후 그것으로 교체한다.
     */
    public static final String DOMESTIC_COUNTRY_CODE = "KR";

    /**
     * 배송 국가로 범위를 정한다 — delivery 가 견적 정책을 고르는 판정과 같다. 통화는 보지 않는다 ({@code order-flow-guide.md} §2-2).
     *
     * <p>임시 — #144(shared-commerce 로 올림) 머지 후 그것으로 교체한다.
     *
     * @param countryCode ISO 3166-1 alpha-2 대문자 (형식은 요청 검증이 거른다)
     */
    public static ShippingScope ofCountry(String countryCode) {
        Objects.requireNonNull(countryCode, "countryCode");
        return DOMESTIC_COUNTRY_CODE.equals(countryCode) ? DOMESTIC : INTERNATIONAL;
    }
}
