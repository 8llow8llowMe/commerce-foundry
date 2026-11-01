package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentSummaryQueryResult;
import java.util.Optional;

/**
 * 결제할 주문의 금액 · 상태를 order-service 에서 받는다 ({@code payment-guide.md} §3 — 금액은 서버가 정한다). 원격 호출이다 — DB 트랜잭션 안에서 부르지 않는다.
 */
public interface OrderPaymentSummaryPort {

    /**
     * 회원의 주문 요약.
     *
     * @return 주문이 없거나 그 회원의 주문이 아니면 비어 있다
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException {@code ORDER_SERVICE_UNAVAILABLE}(503) — 서킷 오픈 · 전송 실패 ·
     *     응답 계약 위반 · 서비스 인증 실패 · 그 밖의 거절
     */
    Optional<OrderPaymentSummaryQueryResult> find(long orderId, long memberId);
}
