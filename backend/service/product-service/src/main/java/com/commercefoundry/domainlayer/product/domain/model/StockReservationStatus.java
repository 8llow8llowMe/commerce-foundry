package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 재고 선점 상태. 전이는 {@link StockReservation#confirm} · {@link StockReservation#release} 의 허용 전이 표로만 일어난다
 * ({@code order-flow-guide.md} §3).
 *
 * <pre>
 * RESERVED ──확정──▶ CONFIRMED
 *    └──────해제 · 만료──▶ RELEASED
 * </pre>
 */
public enum StockReservationStatus {

    /** 주문 생성 시 재고를 잡아 둔 상태 — 결제 대기 */
    RESERVED,
    /** 결제 완료로 확정 — 잡아 둔 재고가 그대로 팔린다 */
    CONFIRMED,
    /** 해제 · 만료 — 차감했던 재고를 되돌렸다 */
    RELEASED
}
