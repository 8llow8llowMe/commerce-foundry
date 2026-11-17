package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import java.util.Optional;

public interface ProductDraftGenerationReceiptPort {
    Optional<ProductDraftGenerationReceipt> findByDraftIdAndGenerationNo(long draftId, int generationNo);

    Optional<ProductDraftGenerationReceipt> findByJobId(long jobId);

    void insert(ProductDraftGenerationReceipt receipt);
}
