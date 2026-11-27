package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.persistence;

import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.persistence.entity.LegalDraftReceiptEntity;
import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.persistence.repository.LegalDraftReceiptRepository;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out.LegalDraftReceiptPort;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftReceipt;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class LegalDraftReceiptAdapter implements LegalDraftReceiptPort {
    private final LegalDraftReceiptRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;

    @Override
    public Optional<LegalDraftReceipt> findByKeyHash(String keyHash) {
        return repository.findById(keyHash).map(entity -> entity.toDomain(mapper));
    }

    @Override
    public Optional<LegalDraftReceipt> findByJobId(long jobId) {
        return repository.findByJobId(jobId).map(entity -> entity.toDomain(mapper));
    }

    @Override
    public void insert(LegalDraftReceipt receipt) {
        try {
            entityManager.persist(new LegalDraftReceiptEntity(receipt, mapper));
            entityManager.flush();
        } catch (ConstraintViolationException exception) {
            throw new DataIntegrityViolationException("법률 문서 생성 영수증 제약 위반", exception);
        }
    }

    @Override
    public void markReviewed(long jobId, Instant reviewedAt) {
        repository.markReviewed(jobId, reviewedAt);
    }
}
