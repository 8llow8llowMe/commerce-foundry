package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import java.util.List;

/**
 * product-service 결제 후 재고 복원 응답 (#308 PR-C1) — 신규 · 같은 클레임의 재요청 모두 같은 모양이다. 모르는 칸은 무시한다.
 *
 * @param reason {@code CANCELLATION} · {@code RETURN} (분기용 제어 코드)
 * @param items SKU 아이디 오름차순, 같은 SKU 는 수량을 합친 한 건
 */
public record StockRestorationInternalResponse(String restorationId, String claimId, String reservationId, String reason, List<Item> items) {

    public record Item(String skuId, Integer quantity, Boolean restored) {
    }
}
