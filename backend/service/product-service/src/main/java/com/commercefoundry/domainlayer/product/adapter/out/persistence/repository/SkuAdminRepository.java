package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuAdminRepository extends JpaRepository<SkuEntity, Long> {
    List<SkuEntity> findAllByProductIdOrderByIdAsc(long productId);
}
