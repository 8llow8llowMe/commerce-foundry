package com.commercefoundry.domainlayer.product.application.command;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 주문 하나의 재고 선점 요청. 같은 SKU 가 여러 번 오면 수량을 합치고 SKU 아이디 오름차순으로 놓는다 — 차감 순서가 곧 행 잠금 순서라
 * 모든 선점이 같은 순서로 잠가 교착을 막는다 ({@code order-flow-guide.md} §3).
 *
 * @param orderId 주문 아이디 (order-service). 주문당 활성 선점은 하나다 — 같은 주문의 재요청은 멱등이다
 * @param items SKU 아이디 오름차순, SKU 마다 한 건 (합친 뒤)
 */
public record StockReservationCommand(long orderId, List<Item> items) {

    public StockReservationCommand {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("선점 항목이 없습니다.");
        }
        items = merge(items);
    }

    /** SKU 아이디 → 수량 (오름차순). 같은 주문의 활성 선점과 항목을 비교할 때 쓴다. */
    public SortedMap<Long, Integer> quantitiesBySkuId() {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        items.forEach(item -> quantities.put(item.skuId(), item.quantity()));
        return Collections.unmodifiableSortedMap(quantities);
    }

    public List<Long> skuIds() {
        return items.stream().map(Item::skuId).toList();
    }

    /** 요청 DTO 가 수량을 1 이상으로 검증한다. 합친 수량이 int 를 넘으면 실패한다 — 그만한 재고는 없다. */
    private static List<Item> merge(List<Item> items) {
        Map<Long, Integer> merged = new TreeMap<>();
        for (Item item : items) {
            merged.merge(item.skuId(), item.quantity(), Math::addExact);
        }
        return merged.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
    }

    /** @param quantity 1 이상 */
    public record Item(long skuId, int quantity) {

        public Item {
            if (quantity <= 0) {
                throw new IllegalArgumentException("선점 수량은 1 이상이어야 합니다: " + quantity);
            }
        }
    }
}
