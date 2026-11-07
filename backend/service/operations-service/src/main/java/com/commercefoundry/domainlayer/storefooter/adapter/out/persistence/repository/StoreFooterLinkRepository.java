package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity.StoreFooterLinkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreFooterLinkRepository extends JpaRepository<StoreFooterLinkEntity, Long> {
    List<StoreFooterLinkEntity> findAllByRevisionIdInOrderByDisplayOrderAsc(List<Long> revisionIds);
}
