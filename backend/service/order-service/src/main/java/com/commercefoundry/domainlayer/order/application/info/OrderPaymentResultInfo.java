package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** 저장된 최초 결제 반영 결과. 현재 주문 상태 · 복원 진행 상태와 독립적인 읽기 모델이다. */
public record OrderPaymentResultInfo(
    long paymentId, long orderId, long memberId, OrderPayment.Result result, Money amount, Instant approvedAt, Long stockReservationId
) {
}
