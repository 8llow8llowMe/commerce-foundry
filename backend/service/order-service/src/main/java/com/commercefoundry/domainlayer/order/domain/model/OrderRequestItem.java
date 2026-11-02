package com.commercefoundry.domainlayer.order.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 요청 기록이 담은 상품 단위 수량 ({@code order_request_item}, #436). 진행 중인 시도가 쥘 수량을 상품 수량 상한 재검사에서 세려고 둔다 —
 * 원문 · SKU · 맞춤 제작 입력은 남기지 않는다. 기록을 처음 넣을 때 함께 넣고 바뀌지 않는다(인수 · 재시도는 같은 본문이라 같은 값이다).
 *
 * @param productId 상품 아이디
 * @param quantity  그 상품 줄(다른 SKU · 다른 맞춤 입력)의 수량 합, 1 이상
 */
public record OrderRequestItem(long productId, int quantity) {

    public OrderRequestItem {
        if (productId <= 0 || quantity < 1) {
            throw new IllegalArgumentException("Invalid order request item: productId=%d, quantity=%d".formatted(productId, quantity));
        }
    }

    /** 견적 줄을 상품별로 합친다 — 상품 수량 상한이 상품 단위라 같은 단위로 남긴다. 순서는 처음 나온 상품 순서다. */
    public static List<OrderRequestItem> of(OrderQuote quote) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderQuoteLine line : quote.lines()) {
            quantities.merge(line.productId(), line.quantity(), Math::addExact);
        }
        return quantities.entrySet().stream().map(entry -> new OrderRequestItem(entry.getKey(), entry.getValue())).toList();
    }
}
