package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Insert-only; the draft ID is assigned, so the adapter uses persist rather than merge. */
@Entity
@Immutable
@Table(name = "size_chart_generation_receipt", comment = "사이즈표 생성 등록 영수증",
    uniqueConstraints = @UniqueConstraint(name = "uk_size_chart_generation_receipt_job_id", columnNames = "job_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SizeChartGenerationReceiptEntity {
    @Id
    @Column(name = "draft_id", nullable = false, updatable = false, comment = "초안 식별자 (FK: product.size_chart_draft.id)")
    private long draftId;

    @Column(name = "job_id", nullable = false, updatable = false, comment = "작업 식별자 (FK: operations.job.id)")
    private long jobId;

    @Column(name = "input_hash", nullable = false, updatable = false, length = 64, comment = "정규화한 입력 SHA-256")
    private String inputHash;

    @Column(name = "requested_by_member_id", nullable = false, updatable = false, comment = "요청자 식별자 (FK: auth.member.id)")
    private long requestedByMemberId;

    @Column(name = "expires_at", nullable = false, updatable = false, comment = "초안 만료 시각")
    private Instant expiresAt;

    public SizeChartGenerationReceiptEntity(SizeChartGenerationReceipt receipt) {
        draftId = receipt.draftId();
        jobId = receipt.jobId();
        inputHash = receipt.inputHash();
        requestedByMemberId = receipt.requestedByMemberId();
        expiresAt = receipt.expiresAt();
    }

    public SizeChartGenerationReceipt toDomain() {
        return new SizeChartGenerationReceipt(draftId, jobId, inputHash, requestedByMemberId, expiresAt);
    }
}
