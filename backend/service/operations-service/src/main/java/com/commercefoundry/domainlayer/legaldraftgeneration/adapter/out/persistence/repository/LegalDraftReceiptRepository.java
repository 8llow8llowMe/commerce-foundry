package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.persistence.entity.LegalDraftReceiptEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalDraftReceiptRepository extends JpaRepository<LegalDraftReceiptEntity, String> {
    Optional<LegalDraftReceiptEntity> findByJobId(long jobId);

    @Modifying(clearAutomatically = true)
    @Query("update LegalDraftReceiptEntity r set r.reviewedAt = :reviewedAt where r.jobId = :jobId and r.reviewedAt is null")
    int markReviewed(@Param("jobId") long jobId, @Param("reviewedAt") Instant reviewedAt);
}
