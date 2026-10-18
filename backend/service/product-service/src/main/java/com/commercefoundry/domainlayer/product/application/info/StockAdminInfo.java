package com.commercefoundry.domainlayer.product.application.info;

import java.time.Instant;

/**
 * 어드민 재고 조정 결과 — 조정을 반영한 뒤 다시 읽은 재고 행과 선점 수량.
 *
 * @param availableQuantity 판매 가능 수량. 결제 전 선점분이 이미 빠진 값이다 — 실물 수량이 아니다
 * @param reservedQuantity 결제를 기다리는 선점(RESERVED)이 차감해 둔 수량 합. 실물 = 판매 가능 + 선점 (+ 결제 완료 후 미출고분)
 * @param updatedAt 재고 행의 마지막 수정 시각
 */
public record StockAdminInfo(long skuId, boolean stockManaged, long availableQuantity, long reservedQuantity, Instant updatedAt) {
}
