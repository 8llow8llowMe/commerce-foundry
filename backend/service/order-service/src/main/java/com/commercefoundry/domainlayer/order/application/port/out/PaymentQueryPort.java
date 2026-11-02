package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import java.util.Optional;

/**
 * payment-service 결제 재조회 (#308, {@code payment-guide.md} §6) — {@code payment-approved} 이벤트에는 서명이 없어 컨슈머가 이 조회로 상태 · 금액 · 통화 · 주문 · 회원을
 * 다시 확인한다. 다시 보내지 않는다 — 재시도는 이벤트 재전달이 한다.
 */
public interface PaymentQueryPort {

    /**
     * @return 결제. 없으면(404 {@code PAYMENT_001}) 빈 값
     * @throws com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException 사유는 {@code Failure}
     */
    Optional<PaymentQueryResult> findPayment(long paymentId);
}
