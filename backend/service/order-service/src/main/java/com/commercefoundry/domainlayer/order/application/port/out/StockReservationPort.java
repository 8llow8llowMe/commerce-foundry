package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import java.util.SortedMap;

/**
 * product-service 재고 선점 · 해제 ({@code order-flow-guide.md} §3). 둘 다 멱등이다 — 선점은 주문 아이디로, 해제는 선점 행의 상태로 판단한다.
 * 주문 생성은 선점만 부른다 — 실패해도 보상 해제를 하지 않는다(선점이 시도 사이에 주문 아이디로 공유된다). 해제는 결제 전 만료 · 취소(#96 · 클레임)가 쓴다.
 * 확정(결제 완료)은 #14 가 더한다.
 */
public interface StockReservationPort {

    /**
     * @param quantitiesBySkuId SKU 아이디 → 수량 (SKU 아이디 오름차순, 1~100개)
     * @throws com.commercefoundry.domainlayer.order.application.exception.StockReservationException 사유는 {@code Failure}
     */
    StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId);

    /**
     * 이미 해제된 선점이면 그대로 성공이다.
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.StockReservationException 사유는 {@code Failure}
     */
    void release(long reservationId);
}
