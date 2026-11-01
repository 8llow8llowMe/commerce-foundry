package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity.ProductDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductDraftRepository extends JpaRepository<ProductDraftEntity, Long> {
}
