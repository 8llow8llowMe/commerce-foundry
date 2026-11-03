package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.ProductCustomRepository;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 고객 조회(노출 조건이 붙는 것)와 삭제(soft delete) 조건이 붙는 조회는 {@link ProductCustomRepository} 에만 둔다 — 노출 · 삭제 조건을 한 곳에서 관리한다.
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>, ProductCustomRepository {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.version = p.version + 1, p.updatedAt = :updatedAt "
        + "where p.id = :productId and p.version = :version and p.deletedAt is null")
    int bumpVersion(long productId, long version, Instant updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.categoryId = :categoryId, p.version = p.version + 1, p.updatedAt = :updatedAt "
        + "where p.id = :productId and p.version = :version and p.deletedAt is null")
    int updateCategory(long productId, long version, long categoryId, Instant updatedAt);

}
