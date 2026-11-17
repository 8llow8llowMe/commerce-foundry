package com.commercefoundry.domainlayer.productdraftgeneration.application.port.in;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationResultResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;

public interface ProductDraftGenerationInternalUseCase {
    ProductDraftGenerationRegistrationResponse register(ProductDraftGenerationRegisterCommand command);

    ProductDraftGenerationResultResponse result(long jobId);
}
