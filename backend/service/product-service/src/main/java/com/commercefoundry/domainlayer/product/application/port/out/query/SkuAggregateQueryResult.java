package com.commercefoundry.domainlayer.product.application.port.out.query;

/**
 * 상품별 SKU 집계 — 목록이 SKU 를 상품마다 따로 읽지 않게 한 번의 group by 로 낸다.
 *
 * @param hasOptionSurcharge 추가금이 0보다 큰 SKU 가 있는가 (목록의 "옵션에 따라 가격 상이" 표시)
 * @param hasSellableSku 판매중(ON_SALE) SKU 가 있는가. SKU 가 없는 상품은 집계 결과에 행이 없다
 */
public record SkuAggregateQueryResult(long productId, boolean hasOptionSurcharge, boolean hasSellableSku) {
}
