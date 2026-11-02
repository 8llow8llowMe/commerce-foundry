package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderSummaryItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.persistence.dto.SliceResponse;

/**
 * 고객 주문 내역 · 상세 · 결제 전 취소 (#16 PR-1). memberId 는 컨트롤러가 JWT 주체에서만 꺼낸다 — 남의 주문은 없는 주문처럼 404 다.
 */
public interface OrderHistoryWebUseCase {

    /** 내 주문 최신순 한 조각. */
    SliceResponse<OrderSummaryItem> getOrders(long memberId, int page, int size);

    /** @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) — 없거나 남의 주문 */
    OrderDetailResponse getOrder(long memberId, long orderId);

    /**
     * 결제 전 취소 — 결제 대기를 취소 완료로 바꾸고 재고 선점 해제 이벤트({@code order-canceled})를 같은 트랜잭션에서 outbox 에 넣는다.
     * 이미 취소됐으면 바꾸지 않고 지금 주문을 돌려준다(재시도 안전).
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) ·
     *     {@code ORDER_STATUS_TRANSITION_INVALID}(409 — 결제 완료 · 만료 등, 동시 변경에 진 경우 포함)
     */
    OrderDetailResponse cancel(long memberId, long orderId, OrderCancelReason reason);

    /** 배송 완료 주문을 구매 확정한다. 이미 확정됐으면 현재 상세를 돌려준다. */
    OrderDetailResponse confirmPurchase(long memberId, long orderId);
}
