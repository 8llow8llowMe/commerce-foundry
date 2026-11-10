package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderClaimItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderSummaryItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.persistence.dto.SliceResponse;
import java.util.List;

/**
 * 고객 주문 내역 · 상세 · 취소 (#16). memberId 는 컨트롤러가 JWT 주체에서만 꺼낸다 — 남의 주문은 없는 주문처럼 404 다.
 */
public interface OrderHistoryWebUseCase {

    /** 내 주문 최신순 한 조각. */
    SliceResponse<OrderSummaryItem> getOrders(long memberId, int page, int size);

    /** @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) — 없거나 남의 주문 */
    OrderDetailResponse getOrder(long memberId, long orderId);

    /**
     * 내 주문의 클레임(반품 · 결제 후 취소) — 아이디 내림차순, 없으면 빈 목록 (#609).
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) — 없거나 남의 주문
     */
    List<OrderClaimItem> getClaims(long memberId, long orderId);

    /**
     * 고객 취소.
     *
     * <ul>
     *   <li>결제 전 — 결제 대기를 취소 완료로 바꾸고 재고 선점 해제 이벤트({@code order-canceled})를 같은 트랜잭션에서 outbox 에 넣는다.</li>
     *   <li>결제 후(#16 PR-2) — 결제 완료 · 상품 준비중(제작 중은 설정)을 취소 요청으로 접수하고 전액 환불 → 재고 복원을 잇는다. 환불이 확정되면 취소 완료,
     *       미확정 · 실패면 취소 요청 상태로 돌려준다(재시도 스케줄러 · 운영자가 잇는다).</li>
     * </ul>
     * 이미 취소됐거나 취소 요청이 접수돼 있으면 바꾸지 않고 지금 주문을 돌려준다(재시도 안전).
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) ·
     *     {@code ORDER_STATUS_TRANSITION_INVALID}(409 — 배송 중 이후 · 만료 등) · {@code ORDER_CONCURRENTLY_CHANGED}(409 — 동시 변경 뒤 다시 판정할 수 없음) ·
     *     {@code CANCELLATION_PAYMENT_MISSING} · {@code CANCELLATION_AMOUNT_MISMATCH}(500 — 결제 기록 이상)
     */
    OrderDetailResponse cancel(long memberId, long orderId, OrderCancelReason reason);

    /** 배송 완료 주문을 구매 확정한다. 이미 확정됐으면 현재 상세를 돌려준다. */
    OrderDetailResponse confirmPurchase(long memberId, long orderId);
}
