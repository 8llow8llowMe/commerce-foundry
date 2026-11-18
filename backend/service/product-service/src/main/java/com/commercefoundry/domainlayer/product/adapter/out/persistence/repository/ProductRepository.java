package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.ProductCustomRepository;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * 고객 조회(노출 조건이 붙는 것)와 삭제(soft delete) 조건이 붙는 조회는 {@link ProductCustomRepository} 에만 둔다 — 노출 · 삭제 조건을 한 곳에서 관리한다.
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>, ProductCustomRepository {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.version = p.version + 1, p.updatedAt = :updatedAt "
        + "where p.id = :productId and p.version = :version and p.deletedAt is null")
    int bumpVersion(long productId, long version, Instant updatedAt);

    /** 가격 backfill version fence (#20). 수정 시각은 바꾸지 않고, 삭제 상품도 대상이다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.version = p.version + 1 where p.id = :productId and p.version = :version")
    int fenceVersion(long productId, long version);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProductEntity p set p.categoryId = :categoryId, p.version = p.version + 1, p.updatedAt = :updatedAt "
        + "where p.id = :productId and p.version = :version and p.deletedAt is null")
    int updateCategory(long productId, long version, long categoryId, Instant updatedAt);

    /** 미연결 가격 snapshot 전용 제한 조회. count 질의와 노출·삭제·판매 상태 필터를 사용하지 않는다. */
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<ProductEntity> findAllByIdInOrderByIdAsc(Collection<Long> productIds, Pageable pageable);
}
