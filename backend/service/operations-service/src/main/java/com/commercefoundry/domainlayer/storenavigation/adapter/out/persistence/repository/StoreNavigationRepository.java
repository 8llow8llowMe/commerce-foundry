package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreNavigationRepository extends JpaRepository<StoreNavigationEntity, Long> {
}
