package com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.entity.StoreProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreProfileRepository extends JpaRepository<StoreProfileEntity, Long> {
}
