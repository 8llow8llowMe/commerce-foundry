package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.support.MoneyItems;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import org.springframework.stereotype.Component;

@Component
public class ShippingFeeInternalPresenter {

    public ShippingFeeQuoteInternalResponse toResponse(ShippingFeeQuote quote) {
        return ShippingFeeQuoteInternalResponse.builder()
            .shippingFee(MoneyItems.of(quote.totalFee()))
            .baseFee(MoneyItems.of(quote.baseFee()))
            .freeShippingApplied(quote.freeShippingApplied())
            .remoteAreaSurcharge(MoneyItems.of(quote.remoteAreaSurcharge()))
            .remoteAreaName(quote.remoteAreaName())
            .freeShippingThreshold(MoneyItems.of(quote.freeShippingThreshold()))
            .shippingPolicyVersion(quote.shippingPolicyVersion())
            .build();
    }
}
