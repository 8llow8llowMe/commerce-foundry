package com.commercefoundry.domainlayer.order.application.port.out;

import java.util.SortedMap;

/**
 * product-service 결제 후 재고 복원 ({@code POST /internal/v1/stock-restorations}, {@code order-flow-guide.md} §3-2 · §3-4).
 * 클레임 아이디가 멱등 키다 — 같은 클레임 · 같은 본문의 재요청은 재고를 다시 늘리지 않고 200 이다. 다시 보내지 않는다 — 재시도는 부른 쪽(결제 이벤트 재전달)이 정한다.
 *
 * <p>지금 호출자는 결제 반영 경합(확정 뒤 취소가 이김, #308 PR-C1) 하나이고 사유는 {@code CANCELLATION} 이다.
 * 실패는 {@code StockReservationException} 사유로 나온다 — 027 {@code NOT_CONFIRMED} · 028 {@code QUANTITY_EXCEEDED} · 029 {@code RESTORATION_MISMATCH} ·
 * 003 {@code NOT_FOUND} · 026 / 030 / 031 {@code CONFLICT} · 그 밖의 4xx {@code REJECTED} · 전송 실패 · 타임아웃 · 5xx · 서킷 · 계약 위반 {@code UNKNOWN}.
 */
public interface StockRestorationPort {

    /**
     * 결제 후 취소의 재고 복원. 정상 반환은 복원됨(같은 클레임의 기존 복원 포함)이다.
     *
     * @param claimId 복원 클레임 아이디 — 재시도마다 같은 값
     * @param reservationId 확정(CONFIRMED) 선점 아이디
     * @param quantitiesBySkuId SKU 아이디 오름차순 · 수량 합 — 같은 클레임이면 같은 값
     */
    void restoreCancellation(long claimId, long reservationId, SortedMap<Long, Integer> quantitiesBySkuId);
}
