package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentResultInternalResponse;

/** payment-service 전용 최초 결제 반영 결과 조회. 결과가 아직 저장되지 않았으면 ORDER_028(404)이다. */
public interface OrderPaymentResultInternalUseCase {

    OrderPaymentResultInternalResponse getResult(long paymentId);
}
