package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import java.time.Instant;
import java.util.List;

/**
 * product-service 재고 선점 응답. 이 클라이언트는 RESERVED 만 수락한다. 모르는 칸은 무시한다.
 *
 * @param status RESERVED · CONFIRMED · RELEASED (분기용 제어 코드)
 * @param expiresAt 만료 시각 (UTC)
 * @param items SKU 아이디 오름차순
 */
public record StockReservationInternalResponse(String reservationId, String orderId, String status, Instant expiresAt, List<Item> items) {

    public record Item(String skuId, Integer quantity, Boolean deducted) {
    }
}
