package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** order-service 의 최초 저장 스냅숏. 현재 주문 상태나 재고 복원 상태를 뜻하지 않는다. */
public record OrderPaymentResultQueryResult(
    long paymentId, long orderId, long memberId, Result result, Money amount, Instant approvedAt, Long stockReservationId
) {

    /** payment 소유의 원격 결과 계약. order-service 도메인 타입에 의존하지 않는다. */
    public enum Result {
        PAID,
        REJECTED_ORDER_CANCELED,
        REJECTED_ORDER_NOT_FOUND,
        REJECTED_PAYMENT_MISMATCH,
        REJECTED_STOCK_UNAVAILABLE,
        REJECTED_ALREADY_PAID,
        REJECTED_PAYMENT_NOT_DONE
    }
}
