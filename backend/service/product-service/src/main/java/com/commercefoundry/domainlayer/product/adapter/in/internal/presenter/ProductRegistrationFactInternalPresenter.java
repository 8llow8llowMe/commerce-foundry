package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.ProductRegistrationFactInternalResponse;
import com.commercefoundry.domainlayer.product.application.info.ProductRegistrationFactInfo;
import org.springframework.stereotype.Component;

@Component
public class ProductRegistrationFactInternalPresenter {
    public ProductRegistrationFactInternalResponse toResponse(ProductRegistrationFactInfo info) {
        return new ProductRegistrationFactInternalResponse(info.storeReady(), info.memberFirstRegisteredAt());
    }
}
