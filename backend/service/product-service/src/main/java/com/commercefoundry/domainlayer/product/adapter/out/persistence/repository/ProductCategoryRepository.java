package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 진열 카테고리 행. 카테고리 삭제의 연결 상품 판정은 삭제(soft delete) 상품을 빼야 해서 product 와 조인한다 —
 * {@code ProductCustomRepository#existsLiveProductInCategory} 에 둔다. 쓰기는 어드민 상품 등록 · 수정(#9 PR-E)에서 더한다.
 */
public interface ProductCategoryRepository extends JpaRepository<ProductCategoryEntity, Long> {

    List<ProductCategoryEntity> findAllByProductIdOrderByCategoryIdAsc(long productId);
}
