package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.ProductCustomRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 고객 조회(노출 조건이 붙는 것)는 {@link ProductCustomRepository} 에만 둔다 — 노출 조건을 한 곳에서 관리한다.
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>, ProductCustomRepository {

    /** 어드민 카테고리 삭제 판정 — 숨김 · 판매 종료 상품도 센다. {@code category_id} 인덱스가 없어 표를 훑는다(드문 어드민 쓰기). */
    boolean existsByCategoryId(Long categoryId);
}
