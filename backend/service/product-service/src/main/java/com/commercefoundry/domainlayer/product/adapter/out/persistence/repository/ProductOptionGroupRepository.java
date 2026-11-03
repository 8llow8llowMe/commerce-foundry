package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;

public interface ProductOptionGroupRepository extends JpaRepository<ProductOptionGroupEntity, Long> {

    List<ProductOptionGroupEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductOptionGroupEntity e set e.name = :name, e.displayOrder = :displayOrder, e.updatedAt = :updatedAt where e.id = :id")
    int updateLabel(long id, String name, int displayOrder, Instant updatedAt);
}
