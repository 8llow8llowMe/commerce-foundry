package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.ProductRegistrationFactInternalResponse;

public interface ProductRegistrationFactInternalUseCase {
    ProductRegistrationFactInternalResponse getFact(long memberId);
}
