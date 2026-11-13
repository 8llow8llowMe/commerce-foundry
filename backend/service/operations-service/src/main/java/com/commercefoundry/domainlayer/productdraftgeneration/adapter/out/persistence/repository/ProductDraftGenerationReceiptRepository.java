package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.entity.ProductDraftGenerationReceiptEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ProductDraftGenerationReceiptRepository extends Repository<ProductDraftGenerationReceiptEntity, ProductDraftGenerationReceiptEntity.Key> {
    Optional<ProductDraftGenerationReceiptEntity> findByDraftIdAndGenerationNo(long draftId, int generationNo);

    Optional<ProductDraftGenerationReceiptEntity> findByJobId(long jobId);
}
