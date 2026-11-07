package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity.StoreFooterRevisionEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreFooterRevisionRepository extends JpaRepository<StoreFooterRevisionEntity, Long> {
    Optional<StoreFooterRevisionEntity> findByFooterIdAndRevisionNumber(long footerId, int revisionNumber);
    Page<StoreFooterRevisionEntity> findAllByFooterId(long footerId, Pageable pageable);
}
