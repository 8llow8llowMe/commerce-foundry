package com.commercefoundry.domainlayer.order.application.port.out;

import java.util.SortedMap;

/**
 * product-service 결제 후 재고 복원 ({@code POST /internal/v1/stock-restorations}, {@code order-flow-guide.md} §3-2 · §3-4).
 * 클레임 아이디가 멱등 키다 — 같은 클레임 · 같은 본문의 재요청은 재고를 다시 늘리지 않고 200 이다. 다시 보내지 않는다 — 재시도는 부른 쪽(결제 이벤트 재전달)이 정한다.
 *
 * <p>호출자는 결제 반영 경합(확정 뒤 취소가 이김, #308 PR-C1) · 결제 후 취소 클레임(사유 {@code CANCELLATION}) · 반품 클레임(사유 {@code RETURN}, #17 B2a)이다.
 * 사유는 메서드가 정한다 — 부르는 쪽은 클레임 유형으로 메서드를 고른다. 같은 클레임 아이디에 다른 사유를 보내면 product 가 {@code PRODUCT_029} 로 거절한다.
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

    /**
     * 반품의 재고 복원 (#17 B2a) — 사유만 {@code RETURN} 이고 계약은 {@link #restoreCancellation} 과 같다. 승인 때 재고로 되돌림을 고른 반품만 부른다.
     *
     * <p>기본 구현은 거절한다 — 이 포트는 취소 복원 하나만 구현하는 시험 대역(람다)이 있어 함수형 모양을 유지한다. 운영 어댑터는 반드시 재정의한다.
     */
    default void restoreReturn(long claimId, long reservationId, SortedMap<Long, Integer> quantitiesBySkuId) {
        throw new UnsupportedOperationException("return stock restoration is not supported by this port");
    }
}
