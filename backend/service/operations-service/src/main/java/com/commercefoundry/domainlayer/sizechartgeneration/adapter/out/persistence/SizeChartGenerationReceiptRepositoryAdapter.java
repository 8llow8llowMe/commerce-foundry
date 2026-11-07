package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.entity.SizeChartGenerationReceiptEntity;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.repository.SizeChartGenerationReceiptRepository;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationReceiptPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SizeChartGenerationReceiptRepositoryAdapter implements SizeChartGenerationReceiptPort {
    private final SizeChartGenerationReceiptRepository repository;
    private final EntityManager entityManager;

    @Override
    public Optional<SizeChartGenerationReceipt> findByDraftId(long draftId) {
        return repository.findById(draftId).map(SizeChartGenerationReceiptEntity::toDomain);
    }

    @Override
    public Optional<SizeChartGenerationReceipt> findByJobId(long jobId) {
        return repository.findByJobId(jobId).map(SizeChartGenerationReceiptEntity::toDomain);
    }

    @Override
    public void insert(SizeChartGenerationReceipt receipt) {
        try {
            entityManager.persist(new SizeChartGenerationReceiptEntity(receipt));
            entityManager.flush();
        } catch (org.hibernate.exception.ConstraintViolationException exception) {
            throw new DataIntegrityViolationException("사이즈표 생성 등록 영수증 제약 위반", exception);
        }
    }
}
