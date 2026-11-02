package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;

/**
 * 서비스 간 결제 조회 (#308). 호출자는 order-service 하나다 — {@code payment-approved} 이벤트를 알림으로만 보고 이 조회로 상태 · 금액 · 주문 · 회원을 확정한다
 * ({@code payment-guide.md} §6). 회원을 거르지 않는다 — 사용자 요청이 아니라 서비스 간 조회다.
 */
public interface PaymentInternalUseCase {

    /**
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 404 {@code PAYMENT_001} — 결제가 없다
     */
    PaymentInternalResponse getPayment(long paymentId);
}
