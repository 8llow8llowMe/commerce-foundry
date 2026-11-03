package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ProductSkuVersionRepository extends Repository<ProductEntity, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductEntity p set p.version = p.version + 1, p.updatedAt = :updatedAt "
        + "where p.id = :productId and p.version = :version and p.deletedAt is null")
    int claimVersion(long productId, long version, Instant updatedAt);
}
