package com.commercefoundry.domainlayer.product.application.info;

import java.time.Instant;

/**
 * 어드민 재고 조정 결과 — 조정을 반영한 뒤 다시 읽은 재고 행.
 *
 * @param updatedAt 재고 행의 마지막 수정 시각
 */
public record StockAdminInfo(long skuId, boolean stockManaged, long availableQuantity, Instant updatedAt) {
}
