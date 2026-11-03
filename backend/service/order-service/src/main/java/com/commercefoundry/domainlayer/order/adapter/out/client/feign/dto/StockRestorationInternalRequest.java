package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import java.util.List;

/**
 * product-service {@code POST /internal/v1/stock-restorations} 요청 (#308 PR-C1). product-service 의 DTO 를 가져다 쓰지 않는다 — 원격 계약은 이 adapter 안에서만 안다.
 *
 * @param claimId 복원 클레임 아이디 — 멱등 키
 * @param reservationId 확정(CONFIRMED) 선점 아이디
 * @param reason {@code CANCELLATION} · {@code RETURN}
 * @param items 1~100개, SKU 아이디 오름차순. 수량은 1~10000
 */
public record StockRestorationInternalRequest(Long claimId, Long reservationId, String reason, List<Item> items) {

    public record Item(Long skuId, Integer quantity) {
    }
}
