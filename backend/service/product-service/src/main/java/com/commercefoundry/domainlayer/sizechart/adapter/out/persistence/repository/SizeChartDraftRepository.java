package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartDraftEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

public interface SizeChartDraftRepository extends JpaRepository<SizeChartDraftEntity, Long> {

    Optional<SizeChartDraftEntity> findByRequestedByMemberIdAndIdempotencyKey(long requestedByMemberId, UUID idempotencyKey);
    @Query("""
        select d from SizeChartDraftEntity d
        where (:productId is null or d.productId = :productId)
          and (:requestedByMemberId is null or d.requestedByMemberId = :requestedByMemberId)
          and d.expiresAt > :now
        """)
    Page<SizeChartDraftEntity> findDraftPage(Long productId, Long requestedByMemberId, Instant now, Pageable pageable);

    @Query("select d.id from SizeChartDraftEntity d where d.expiresAt <= :cutoff order by d.expiresAt, d.id")
    List<Long> findExpiredIds(Instant cutoff, Pageable pageable);

    // A second instance deleting the same IDs simply deletes zero rows. Expiry is immutable and rechecked here.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SizeChartDraftEntity d where d.id in :ids and d.expiresAt <= :cutoff")
    int deleteExpiredIds(List<Long> ids, Instant cutoff);
}
