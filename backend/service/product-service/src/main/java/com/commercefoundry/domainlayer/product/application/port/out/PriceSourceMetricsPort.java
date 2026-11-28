package com.commercefoundry.domainlayer.product.application.port.out;

/**
 * 가격 원천 관측 지표(#20 D8). 운영자가 PRICE_BOOK 에서 해석에 실패해 응답에서 뺀 상품을 사유별로 세게 한다.
 * 구현은 지표 실패를 삼켜 호출한 응답을 바꾸지 않는다.
 */
public interface PriceSourceMetricsPort {

    /**
     * 서비스 간 SKU 조회가 가격 원천 해석 실패로 응답에서 뺀 상품 수를 사유별로 더한다.
     *
     * @param reason 고정 사유 이름 — 유한 집합(해석 사유 enum 이름 · {@code SKU_NOT_IN_PROJECTION})만 넣는다. 아이디 · 금액을 넣지 않는다
     * @param count  뺀 상품 수. 0 이하면 아무것도 하지 않는다
     */
    void recordExcludedProducts(String reason, int count);
}
