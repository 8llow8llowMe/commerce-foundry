package com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationResultResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationResultInfo;
import org.springframework.stereotype.Component;

@Component
public class ProductDraftGenerationInternalPresenter {
    public ProductDraftGenerationRegistrationResponse registration(ProductDraftGenerationRegistrationInfo info) {
        return new ProductDraftGenerationRegistrationResponse(Long.toString(info.draftId()), info.generationNo(), Long.toString(info.jobId()), info.inputHash());
    }

    public ProductDraftGenerationResultResponse result(ProductDraftGenerationResultInfo info) {
        return new ProductDraftGenerationResultResponse(Long.toString(info.draftId()), info.generationNo(), Long.toString(info.jobId()),
            info.inputHash(), info.status(), info.failureCode(), info.resultJson());
    }
}
