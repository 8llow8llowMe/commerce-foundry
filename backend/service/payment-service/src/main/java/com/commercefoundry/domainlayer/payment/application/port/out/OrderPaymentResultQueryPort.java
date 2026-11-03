package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;

/** 저장된 최초 주문 결제 반영 결과 조회. 미반영도 typed exception 으로 구분한다. */
public interface OrderPaymentResultQueryPort {

    OrderPaymentResultQueryResult query(long paymentId);
}
