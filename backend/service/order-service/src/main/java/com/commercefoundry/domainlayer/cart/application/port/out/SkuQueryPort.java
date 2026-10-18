package com.commercefoundry.domainlayer.cart.application.port.out;

import com.commercefoundry.domainlayer.cart.application.port.out.query.SkuQueryResult;
import java.util.Collection;
import java.util.List;

/**
 * product-service 의 SKU 현재 가격 · 판매 상태.
 */
public interface SkuQueryPort {

    /**
     * 몇 개든 받는다 — 원격 한 번에 받을 수 있는 수를 넘으면 구현이 나눠 부른다. 빈 컬렉션이면 호출하지 않고 빈 목록이다.
     *
     * @param skuIds 중복은 한 번만 조회한다
     * @return 요청 순서(중복 제거 후). 없는 SKU 는 빠진다
     * @throws com.commercefoundry.domainlayer.cart.application.exception.CartException {@code CART_PRODUCT_SERVICE_UNAVAILABLE} — 서킷 오픈 · 전송 실패 · 오류 응답
     */
    List<SkuQueryResult> findSkus(Collection<Long> skuIds);
}
