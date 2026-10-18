package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SKU 재고. 아이디(= SKU 아이디) 목록 조회는 {@link #findAllById} 를 쓴다.
 */
public interface StockRepository extends JpaRepository<StockEntity, Long> {
}
