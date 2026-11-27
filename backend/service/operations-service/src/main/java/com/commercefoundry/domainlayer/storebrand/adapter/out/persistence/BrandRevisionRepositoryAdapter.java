package com.commercefoundry.domainlayer.storebrand.adapter.out.persistence;

import com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.entity.BrandRevisionEntity;
import com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.repository.BrandRevisionRepository;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.port.out.BrandRevisionPort;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.persistence.util.ConstraintViolations;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class BrandRevisionRepositoryAdapter implements BrandRevisionPort {
    private final BrandRevisionRepository repository;
    private final EntityManager entityManager;

    @Override
    public Optional<BrandRevision> latest() {
        return repository.findFirstByOrderByVersionDesc().map(BrandRevisionEntity::toDomain);
    }

    @Override
    public Optional<BrandRevision> find(long version) {
        return repository.findById(version).map(BrandRevisionEntity::toDomain);
    }

    @Override
    @Transactional
    public void append(BrandRevision revision) {
        // 동일 버전에서 시작한 두 편집은 같은 다음 PK를 만들므로 하나만 성공한다.
        try {
            entityManager.persist(BrandRevisionEntity.from(revision));
            entityManager.flush();
        } catch (RuntimeException exception) {
            if (ConstraintViolations.violates(exception, "store_brand_revision_pkey")) {
                throw new BrandAssetException(Code.CONFLICT);
            }
            throw exception;
        }
    }

    @Override
    public PageResponse<BrandRevision> publications(int page, int size) {
        return PageResponse.of(repository.findPublications(PageRequest.of(page, size)).map(BrandRevisionEntity::toDomain));
    }
}
