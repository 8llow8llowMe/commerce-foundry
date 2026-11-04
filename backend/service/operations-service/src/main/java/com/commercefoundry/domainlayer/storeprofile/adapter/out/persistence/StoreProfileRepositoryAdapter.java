package com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence;

import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.entity.StoreProfileEntity;
import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.repository.StoreProfileRepository;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileErrorCode;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileException;
import com.commercefoundry.domainlayer.storeprofile.application.port.out.StoreProfilePort;
import com.commercefoundry.domainlayer.storeprofile.application.port.out.query.StoreProfileQueryResult;
import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class StoreProfileRepositoryAdapter implements StoreProfilePort {

    private final StoreProfileRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreProfileQueryResult> find() {
        return repository.findById(1L).map(StoreProfileEntity::toQueryResult);
    }

    @Override
    @Transactional
    public StoreProfileQueryResult replace(Long expectedVersion, StoreProfile profile) {
        // 읽기·버전 비교·flush를 한 트랜잭션에 둔다. 동시 수정은 JPA @Version이 최종 판정한다.
        var current = repository.findById(1L);
        if (current.isPresent() ? !Objects.equals(current.get().toQueryResult().version(), expectedVersion) : expectedVersion != null) {
            throw new StoreProfileException(StoreProfileErrorCode.VERSION_CONFLICT);
        }
        try {
            var entity = current.orElseGet(() -> StoreProfileEntity.create(profile));
            entity.replace(profile);
            return repository.saveAndFlush(entity).toQueryResult();
        } catch (OptimisticLockingFailureException exception) {
            throw new StoreProfileException(StoreProfileErrorCode.VERSION_CONFLICT, exception);
        } catch (DataIntegrityViolationException exception) {
            // 최초 생성 경합의 PK 충돌만 409로 바꾼다. 다른 무결성 오류는 숨기지 않는다.
            if (current.isEmpty() && com.commercefoundry.persistence.util.ConstraintViolations.violates(exception, "store_profile_pkey")) {
                throw new StoreProfileException(StoreProfileErrorCode.VERSION_CONFLICT, exception);
            }
            throw exception;
        }
    }
}
