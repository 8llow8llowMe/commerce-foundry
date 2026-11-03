package com.commercefoundry.domainlayer.order.adapter.in.internal.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentResultInternalResponse;
import com.commercefoundry.domainlayer.order.application.info.OrderPaymentResultInfo;
import org.springframework.stereotype.Component;

@Component
public class OrderPaymentResultInternalPresenter {

    public OrderPaymentResultInternalResponse toResponse(OrderPaymentResultInfo info) {
        return new OrderPaymentResultInternalResponse(String.valueOf(info.paymentId()), String.valueOf(info.orderId()), String.valueOf(info.memberId()),
            info.result().name(), MoneyItems.of(info.amount()), info.approvedAt(),
            info.stockReservationId() == null ? null : String.valueOf(info.stockReservationId()));
    }
}
