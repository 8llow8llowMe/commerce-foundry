package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import java.util.SortedMap;

/**
 * product-service 재고 선점 · 확정 ({@code order-flow-guide.md} §3-4). 다시 보내지 않는다 — 실패는 {@code StockReservationException} 사유로 나오고,
 * 재시도 여부는 부른 쪽(같은 키의 주문 생성 재요청 · 결제 이벤트 재전달)이 정한다. 자동 보상 해제는 없다. C3 는 요청 기록의 주문 아이디를 다시 쓴다.
 */
public interface StockReservationPort {

    /** 주문 생성의 선점 — RESERVED 만 받는다. SKU 아이디 오름차순, 수량은 합친 양수. */
    StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId);

    /**
     * 만료 뒤 결제의 재선점 (#308) — 같은 주문 아이디 · 같은 항목으로 다시 선점한다. RESERVED 와 함께 이미 확정된(CONFIRMED) 같은 주문의 활성 선점도 받는다 —
     * 앞선 처리가 재선점 · 확정까지 하고 주문 반영 전에 끊긴 뒤 재전달이 오면 product 가 그 선점을 200 으로 돌려준다. 응답의 만료 시각은 지났을 수 있다.
     */
    StockReservationQueryResult reReserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId);

    /**
     * 선점 확정 (#308, {@code POST /internal/v1/stock-reservations/{id}/confirmation}) — 멱등이다(이미 CONFIRMED 면 200).
     * 해제 · 만료(409 {@code PRODUCT_025})는 {@code RELEASED}, 없음(404 {@code PRODUCT_003})은 {@code NOT_FOUND}, 동시 변경(409 {@code PRODUCT_026})은 {@code CONFLICT} 사유다.
     */
    StockConfirmationQueryResult confirm(long reservationId);
}
