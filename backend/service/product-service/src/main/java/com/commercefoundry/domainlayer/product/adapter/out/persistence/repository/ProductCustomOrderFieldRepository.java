package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCustomOrderFieldEntity;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductCustomOrderFieldRepository extends JpaRepository<ProductCustomOrderFieldEntity, Long> {

    List<ProductCustomOrderFieldEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);

    List<ProductCustomOrderFieldEntity> findAllByProductIdInOrderByProductIdAscDisplayOrderAscIdAsc(Collection<Long> productIds);

    /** 키(fieldKey)는 바꾸지 않는다 — 주문 · 장바구니 입력이 키로 가리킨다 (#9 PR-B). 상품 아이디까지 조건에 둬 남의 상품 행을 바꾸지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ProductCustomOrderFieldEntity e
           set e.label = :label, e.inputType = :inputType, e.unit = :unit, e.required = :required,
               e.minValue = :minValue, e.maxValue = :maxValue, e.stepValue = :stepValue, e.helpText = :helpText,
               e.displayOrder = :displayOrder, e.updatedAt = :updatedAt
         where e.id = :id and e.productId = :productId
        """)
    int updateDefinition(long id, long productId, String label, CustomOrderInputType inputType, String unit, boolean required,
                         BigDecimal minValue, BigDecimal maxValue, BigDecimal stepValue, String helpText, int displayOrder, Instant updatedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProductCustomOrderFieldEntity e where e.productId = :productId and e.id in :ids")
    int deleteAllByProductIdAndIdIn(long productId, Collection<Long> ids);
}
