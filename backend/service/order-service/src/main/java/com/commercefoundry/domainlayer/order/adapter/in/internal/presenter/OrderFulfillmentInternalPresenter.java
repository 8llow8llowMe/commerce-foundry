package com.commercefoundry.domainlayer.order.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.order.application.info.OrderFulfillmentInfo;
import org.springframework.stereotype.Component;

/** 출고 · 배송 완료 반영 결과 → 서비스 간 응답 (#18). 식별자 {@code long → String}, 상태 enum → 제어 코드를 여기서 바꾼다. */
@Component
public class OrderFulfillmentInternalPresenter {

    public OrderFulfillmentInternalResponse toResponse(OrderFulfillmentInfo info) {
        return OrderFulfillmentInternalResponse.builder()
            .orderId(String.valueOf(info.orderId()))
            .memberId(String.valueOf(info.memberId()))
            .status(info.status().name())
            .countryCode(info.countryCode())
            .build();
    }
}
