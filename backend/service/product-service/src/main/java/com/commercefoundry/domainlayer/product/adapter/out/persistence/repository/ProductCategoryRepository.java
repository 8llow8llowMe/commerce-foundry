package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCategoryRepository extends JpaRepository<ProductCategoryEntity, Long> {

    /** {@code idx_product_category_category_id_product_id} 선두 컬럼을 탄다. */
    boolean existsByCategoryId(Long categoryId);
}
