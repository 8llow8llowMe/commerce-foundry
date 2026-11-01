package com.commercefoundry.domainlayer.order.adapter.in.internal.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.domainlayer.order.application.info.OrderPaymentSummaryInfo;
import org.springframework.stereotype.Component;

/** 결제용 주문 요약 → 서비스 간 응답 (#14). 식별자 {@code long → String}, 상태 enum → 제어 코드를 여기서 바꾼다. */
@Component
public class OrderPaymentSummaryInternalPresenter {

    public OrderPaymentSummaryInternalResponse toResponse(OrderPaymentSummaryInfo info) {
        return OrderPaymentSummaryInternalResponse.builder()
            .orderId(String.valueOf(info.orderId()))
            .memberId(String.valueOf(info.memberId()))
            .status(info.status().name())
            .totalAmount(MoneyItems.of(info.totalAmount()))
            .orderName(info.orderName())
            .paymentDueAt(info.paymentDueAt())
            .build();
    }
}
