package com.commercefoundry.domainlayer.product.application.info;

import java.time.Instant;

/**
 * 어드민이 보는 SKU 재고 한 건 — 조회 항목과 조정 결과가 같은 모양이다. 조정 결과는 조정을 반영한 뒤 다시 읽은 재고 행이다.
 *
 * @param availableQuantity 판매 가능 수량. 결제 전 선점분이 이미 빠진 값이다 — 실물 수량이 아니다
 * @param reservedQuantity 결제를 기다리는 선점(RESERVED)이 차감해 둔 수량 합. 실물 = 판매 가능 + 선점 (+ 결제 완료 후 미출고분)
 * @param updatedAt 재고 행의 마지막 수정 시각. 재고 행이 없는 SKU(품절로 보는 기본값)의 조회는 null 이다 — 조정 결과에는 언제나 있다
 */
public record StockAdminInfo(long skuId, boolean stockManaged, long availableQuantity, long reservedQuantity, Instant updatedAt) {
}
