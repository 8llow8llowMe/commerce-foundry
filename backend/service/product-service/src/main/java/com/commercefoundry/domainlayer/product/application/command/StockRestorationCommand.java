package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 클레임 하나의 결제 후 재고 복원 요청. 같은 SKU 가 여러 번 오면 수량을 합치고 SKU 아이디 오름차순으로 놓는다 — 복원 순서가 곧 재고 행 잠금 순서라
 * 선점 · 해제와 같은 순서로 잠가 교착을 막는다 ({@code order-flow-guide.md} §3).
 *
 * <p>병합 규칙은 {@link StockReservationCommand} 와 같지만 항목 타입을 공유하지 않는다 — 두 요청은 따로 바뀔 수 있고, 몇 줄짜리 병합을 위해
 * 선점 명령에 복원을 묶지 않는다.
 *
 * @param claimId 클레임 아이디 (order-service). 클레임당 복원은 하나다 — 같은 클레임의 재요청은 멱등이다
 * @param reservationId 복원 대상인 확정(CONFIRMED) 선점
 * @param items SKU 아이디 오름차순, SKU 마다 한 건 (합친 뒤)
 */
public record StockRestorationCommand(long claimId, long reservationId, StockRestorationReason reason, List<Item> items) {

    public StockRestorationCommand {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("복원 항목이 없습니다.");
        }
        items = merge(items);
    }

    /** SKU 아이디 → 수량 (오름차순). 같은 클레임의 기존 복원과 비교하고 복원 상한을 검사할 때 쓴다. */
    public SortedMap<Long, Integer> quantitiesBySkuId() {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        items.forEach(item -> quantities.put(item.skuId(), item.quantity()));
        return Collections.unmodifiableSortedMap(quantities);
    }

    /** 요청 DTO 가 수량을 1~10000, 항목을 100개 이하로 검증해 합친 수량이 int 를 넘지 않는다. addExact 는 그 전제가 깨졌을 때의 방어다. */
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
                throw new IllegalArgumentException("복원 수량은 1 이상이어야 합니다: " + quantity);
            }
        }
    }
}
