package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreNavigationItemRepository extends JpaRepository<StoreNavigationItemEntity, StoreNavigationItemEntity.Key> {
    /** 리비전 여러 개를 한 번에 읽는다(이력 목록에서 N+1 을 피한다). 트리 순서는 도메인이 맞춘다. */
    List<StoreNavigationItemEntity> findAllByRevisionIdIn(List<Long> revisionIds);
}
