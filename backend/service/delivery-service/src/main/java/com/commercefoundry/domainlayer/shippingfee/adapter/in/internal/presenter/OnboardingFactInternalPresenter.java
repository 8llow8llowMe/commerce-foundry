package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingPolicyFactInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.info.ShippingPolicyFactInfo;
import org.springframework.stereotype.Component;

@Component
public class OnboardingFactInternalPresenter {

    public ShippingPolicyFactInternalResponse toResponse(ShippingPolicyFactInfo info) {
        return ShippingPolicyFactInternalResponse.builder()
            .storeReady(info.storeReady())
            .memberFirstSavedAt(info.memberFirstSavedAt())
            .build();
    }
}
