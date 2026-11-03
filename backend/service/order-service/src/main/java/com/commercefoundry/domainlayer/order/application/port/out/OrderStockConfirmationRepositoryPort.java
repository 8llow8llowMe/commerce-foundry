package com.commercefoundry.domainlayer.order.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * 결제 반영의 재선점 증거 (#308 PR-C1, {@code orders.order_stock_confirmation}) — 결제당 한 행.
 *
 * <p>만료 뒤 결제가 같은 주문 아이디로 재선점해 <b>새 선점 아이디</b>를 받았을 때, 그 선점을 확정하기 <b>전에</b> 남긴다. 확정 뒤 DB 기록 전에 프로세스가 끊기고
 * 그 사이 주문이 취소되면, 재전달은 이 행(과 주문의 선점 아이디)을 후보로 해제 프로브를 불러 확정된 선점을 찾는다({@code order-flow-guide.md} §3-6).
 */
public interface OrderStockConfirmationRepositoryPort {

    /**
     * 결제의 재선점 아이디를 넣거나 바꾼다(upsert). 호출 트랜잭션을 필수로 요구한다. 바뀌는 경우는 앞선 재선점이 확정 전에 다시 만료돼 더 새 선점을 받은 때뿐이다 —
     * 앞선 선점은 확정되지 않았다(확정됐다면 재선점이 같은 선점을 200 으로 돌려준다).
     */
    void upsert(long paymentId, long orderId, long stockReservationId, UUID eventId);

    /** 결제의 재선점 증거 선점 아이디. 없으면 빈 값이다. */
    Optional<Long> findStockReservationId(long paymentId);
}
