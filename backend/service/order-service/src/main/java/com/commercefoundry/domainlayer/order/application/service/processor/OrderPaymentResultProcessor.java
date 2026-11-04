package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderPaymentResultInfo;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 결제 반영 기록 한 건만 읽는다. 주문이 없어 거절한 기록도 유효하므로 현재 주문을 조회하지 않는다. */
@Component
@RequiredArgsConstructor
public class OrderPaymentResultProcessor {

    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;

    public OrderPaymentResultInfo getResult(long paymentId) {
        OrderPayment payment = orderPaymentRepositoryPort.findByPaymentId(paymentId)
            .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_PAYMENT_RESULT_NOT_FOUND));
        return new OrderPaymentResultInfo(payment.paymentId(), payment.orderId(), payment.memberId(), payment.result(), payment.totalAmount(),
            payment.approvedAt(), payment.result() == OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE ? null : payment.stockReservationId());
    }
}
