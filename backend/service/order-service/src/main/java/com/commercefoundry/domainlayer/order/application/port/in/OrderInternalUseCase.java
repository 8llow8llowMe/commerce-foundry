package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;
import java.time.Instant;

/** 서비스 간 주문 조회 · 반영 ({@code /internal/v1/orders/**}). 사용자 토큰은 오지 않으므로 회원 아이디는 호출 서비스가 확인해 넘긴다 ({@code security-guide.md} §9-4). */
public interface OrderInternalUseCase {

    /**
     * 결제 준비용 주문 요약 (#14). 상태가 {@code PENDING_PAYMENT} 가 아니어도 그대로 돌려준다 — 판정은 payment-service 몫이다.
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) — 없거나 그 회원의 주문이 아니다(가르지 않는다)
     */
    OrderPaymentSummaryInternalResponse getPaymentSummary(long orderId, long memberId);

    /**
     * 출고 반영 (#18) — 상품 준비중 → 배송 중. 이미 출고된 뒤면 바꾸지 않고 지금 상태를 돌려준다.
     *
     * @param deliveryId delivery-service 송장 아이디 — 추적 로그에만 쓴다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) · {@code ORDER_STATUS_TRANSITION_INVALID}(409)
     *     · {@code ORDER_CONCURRENTLY_CHANGED}(409 — 같은 요청을 다시 보내면 된다)
     */
    OrderFulfillmentInternalResponse ship(long orderId, long deliveryId, Instant shippedAt);

    /**
     * 배송 완료 반영 (#18) — 배송 중 → 배송 완료. 이미 배송 완료된 뒤면 바꾸지 않고 지금 상태를 돌려준다.
     *
     * @param deliveryId delivery-service 송장 아이디 — 추적 로그에만 쓴다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) · {@code ORDER_STATUS_TRANSITION_INVALID}(409)
     *     · {@code ORDER_CONCURRENTLY_CHANGED}(409 — 같은 요청을 다시 보내면 된다)
     */
    OrderFulfillmentInternalResponse completeDelivery(long orderId, long deliveryId, Instant deliveredAt);
}
