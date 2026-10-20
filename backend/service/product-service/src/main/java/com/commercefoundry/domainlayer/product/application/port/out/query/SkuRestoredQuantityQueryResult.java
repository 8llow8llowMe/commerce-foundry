package com.commercefoundry.domainlayer.product.application.port.out.query;

/**
 * 선점 하나에서 SKU 하나를 이미 복원한 수량 합 — 그 선점의 복원 기록 항목 수량을 SKU 별로 더한 값. 재고를 되돌리지 않은 항목(무제한 SKU)도 들어간다.
 *
 * @param quantity 1 이상. 복원한 적 없는 SKU 는 결과에 행이 없다 (0 으로 본다)
 */
public record SkuRestoredQuantityQueryResult(long skuId, long quantity) {
}
