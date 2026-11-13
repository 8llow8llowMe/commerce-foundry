package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.entity.ProductDraftGenerationReceiptEntity;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.repository.ProductDraftGenerationReceiptRepository;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationReceiptPort;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductDraftGenerationReceiptRepositoryAdapter implements ProductDraftGenerationReceiptPort {
    private final ProductDraftGenerationReceiptRepository repository;
    private final EntityManager entityManager;

    @Override
    public Optional<ProductDraftGenerationReceipt> findByDraftIdAndGenerationNo(long draftId, int generationNo) {
        return repository.findByDraftIdAndGenerationNo(draftId, generationNo).map(ProductDraftGenerationReceiptEntity::toDomain);
    }

    @Override
    public Optional<ProductDraftGenerationReceipt> findByJobId(long jobId) {
        return repository.findByJobId(jobId).map(ProductDraftGenerationReceiptEntity::toDomain);
    }

    @Override
    public void insert(ProductDraftGenerationReceipt receipt) {
        try {
            entityManager.persist(new ProductDraftGenerationReceiptEntity(receipt));
            entityManager.flush();
        } catch (org.hibernate.exception.ConstraintViolationException exception) {
            throw new DataIntegrityViolationException("상품 초안 생성 등록 영수증 제약 위반", exception);
        }
    }
}
