package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationRevisionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreNavigationRevisionRepository extends JpaRepository<StoreNavigationRevisionEntity, Long> {
    Optional<StoreNavigationRevisionEntity> findByNavigationIdAndRevisionNumber(long navigationId, int revisionNumber);
}
