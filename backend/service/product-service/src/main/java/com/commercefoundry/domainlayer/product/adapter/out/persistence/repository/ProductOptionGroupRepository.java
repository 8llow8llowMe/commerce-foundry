package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionGroupRepository extends JpaRepository<ProductOptionGroupEntity, Long> {

    List<ProductOptionGroupEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);
}
