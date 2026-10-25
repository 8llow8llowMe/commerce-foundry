package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCustomOrderFieldEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCustomOrderFieldRepository extends JpaRepository<ProductCustomOrderFieldEntity, Long> {

    List<ProductCustomOrderFieldEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);

    List<ProductCustomOrderFieldEntity> findAllByProductIdInOrderByProductIdAscDisplayOrderAscIdAsc(Collection<Long> productIds);
}
