package com.commercefoundry.domainlayer.product.application.port.out.query;

/**
 * SKU 하나의 선점 수량 합 — RESERVED 선점 중 재고를 실제로 차감한({@code deducted}) 항목의 수량을 SKU 별로 더한 값.
 *
 * @param quantity 1 이상. 합이 없는 SKU 는 결과에 행이 없다 (0 으로 본다)
 */
public record SkuReservedQuantityQueryResult(long skuId, long quantity) {
}
