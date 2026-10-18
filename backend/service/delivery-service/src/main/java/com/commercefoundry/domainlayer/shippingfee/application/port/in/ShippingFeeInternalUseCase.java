package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;

public interface ShippingFeeInternalUseCase {

    /** 주문서 · 주문 생성의 배송비 견적. 금액 계산 주체는 order-service 이고, 여기서는 배송비만 정한다. */
    ShippingFeeQuoteInternalResponse quote(ShippingFeeQuoteCommand command);
}
