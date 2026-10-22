package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.ProductCustomRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 고객 조회(노출 조건이 붙는 것)와 삭제(soft delete) 조건이 붙는 조회는 {@link ProductCustomRepository} 에만 둔다 — 노출 · 삭제 조건을 한 곳에서 관리한다.
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>, ProductCustomRepository {
}
