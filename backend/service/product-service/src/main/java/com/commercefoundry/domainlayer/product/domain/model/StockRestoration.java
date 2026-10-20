package com.commercefoundry.domainlayer.product.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 클레임(결제 후 취소 · 반품) 하나의 재고 복원 기록 (헤더 + 항목). 한 번 만들면 바꾸지 않는다(append-only) — 상태 전이가 없다.
 *
 * <p>확정된 선점({@link StockReservation}, CONFIRMED)은 상태를 그대로 두고, 복원은 이 기록으로 따로 남긴다. 같은 클레임의 재요청은
 * 클레임 아이디로 이 기록을 찾아 멱등으로 답하고, 선점 하나에 여러 클레임이 와도 이 기록들의 SKU 별 합이 복원 상한을 정한다
 * ({@link StockReservation#skuIdsExceedingRestorable}).
 *
 * @param claimId 클레임 아이디 (order-service). 클레임당 복원은 하나다 — 멱등 키
 * @param reservationId 복원 대상인 확정 선점
 * @param orderId 선점의 주문 아이디 (운영 조회용 복사본)
 * @param items SKU 아이디 오름차순, SKU 마다 한 건
 */
public record StockRestoration(long id, long claimId, long reservationId, long orderId, StockRestorationReason reason, List<StockRestorationItem> items) {

    public StockRestoration {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("복원 항목이 없습니다.");
        }
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i - 1).skuId() >= items.get(i).skuId()) {
                throw new IllegalArgumentException("복원 항목은 SKU 아이디 오름차순이고 SKU 마다 한 건이어야 합니다.");
            }
        }
        items = List.copyOf(items);
    }

    /** 재고를 실제로 되돌릴 항목 — SKU 아이디 오름차순이다. 무제한 SKU(restored=false)는 기록만 남는다. */
    public List<StockRestorationItem> restoredItems() {
        return items.stream().filter(StockRestorationItem::restored).toList();
    }

    /** 같은 클레임의 재요청인지 — 선점 · 사유 · SKU 별 수량이 정확히 같은가. */
    public boolean isSameRequest(long reservationId, StockRestorationReason reason, Map<Long, Integer> quantitiesBySkuId) {
        Map<Long, Integer> mine = items.stream().collect(Collectors.toMap(StockRestorationItem::skuId, StockRestorationItem::quantity));
        return this.reservationId == reservationId && this.reason == reason && mine.equals(quantitiesBySkuId);
    }
}
