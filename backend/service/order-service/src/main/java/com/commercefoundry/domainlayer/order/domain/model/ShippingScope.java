package com.commercefoundry.domainlayer.order.domain.model;

/**
 * 배송비 견적에 쓴 정책의 배송 범위 — delivery-service 견적 응답의 {@code scope} 다. 국내(KR)는 {@link #DOMESTIC}, 그 밖의 국가는 {@link #INTERNATIONAL} 이다.
 *
 * <p>주문 배송비 스냅숏에 남는 값이라 domain 에 둔다 — 주문 도메인이 들고 다니고, domain 은 application 을 import 하지 않는다
 * ({@code coding-conventions.md} §8-1). delivery-service 의 같은 이름 enum 을 가져다 쓰지 않는다 — 서비스끼리 모듈 의존을 두지 않는다.
 */
public enum ShippingScope {

    DOMESTIC,
    INTERNATIONAL
}
