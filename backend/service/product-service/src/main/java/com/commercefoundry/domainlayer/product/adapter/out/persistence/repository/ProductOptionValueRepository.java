package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValueEntity, Long> {

    List<ProductOptionValueEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);
}
