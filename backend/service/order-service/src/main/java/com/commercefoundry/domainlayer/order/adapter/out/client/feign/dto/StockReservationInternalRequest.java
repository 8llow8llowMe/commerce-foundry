package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import java.util.List;

/**
 * product-service {@code POST /internal/v1/stock-reservations} 요청. product-service 의 DTO 를 가져다 쓰지 않는다 — 원격 계약은 이 adapter 안에서만 안다.
 *
 * @param orderId 주문 아이디 — 선점의 업무 키(같은 주문 · 같은 항목의 재요청은 기존 선점)
 * @param items 1~100개. 수량은 1~10000
 */
public record StockReservationInternalRequest(Long orderId, List<Item> items) {

    public record Item(Long skuId, Integer quantity) {
    }
}
