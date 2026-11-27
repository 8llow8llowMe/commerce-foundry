package com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.entity.BrandRevisionEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface BrandRevisionRepository extends Repository<BrandRevisionEntity, Long> {
    Optional<BrandRevisionEntity> findFirstByOrderByVersionDesc();
    Optional<BrandRevisionEntity> findById(Long version);

    @Query("select r from BrandRevisionEntity r where r.version = r.publishedVersion order by r.version desc")
    Page<BrandRevisionEntity> findPublications(Pageable pageable);
}
