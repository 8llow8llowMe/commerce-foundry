package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Collection;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValueEntity, Long> {

    List<ProductOptionValueEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductOptionValueEntity e set e.name = :name, e.displayOrder = :displayOrder, e.updatedAt = :updatedAt where e.id = :id")
    int updateLabel(long id, String name, int displayOrder, Instant updatedAt);

    /** 은퇴 (#9 PR-C2) — 이미 은퇴한 행은 은퇴 시각을 바꾸지 않는다. 상품 아이디까지 조건에 둬 남의 상품 행을 바꾸지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductOptionValueEntity e set e.retiredAt = :retiredAt, e.updatedAt = :retiredAt where e.productId = :productId and e.id in :ids and e.retiredAt is null")
    int retire(long productId, Collection<Long> ids, Instant retiredAt);
}
