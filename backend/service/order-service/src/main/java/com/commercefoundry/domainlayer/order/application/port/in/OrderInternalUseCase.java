package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;

/** 서비스 간 주문 조회 ({@code /internal/v1/orders/**}). 사용자 토큰은 오지 않으므로 회원 아이디는 호출 서비스가 확인해 넘긴다 ({@code security-guide.md} §9-4). */
public interface OrderInternalUseCase {

    /**
     * 결제 준비용 주문 요약 (#14). 상태가 {@code PENDING_PAYMENT} 가 아니어도 그대로 돌려준다 — 판정은 payment-service 몫이다.
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code NOT_FOUND_ORDER}(404) — 없거나 그 회원의 주문이 아니다(가르지 않는다)
     */
    OrderPaymentSummaryInternalResponse getPaymentSummary(long orderId, long memberId);
}
