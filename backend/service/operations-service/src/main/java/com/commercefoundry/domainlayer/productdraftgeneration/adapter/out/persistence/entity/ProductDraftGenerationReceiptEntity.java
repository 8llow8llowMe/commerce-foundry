package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serializable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Insert-only; the composite key (draft ID, generation number) is assigned, so the adapter uses persist rather than merge. */
@Entity
@Immutable
@IdClass(ProductDraftGenerationReceiptEntity.Key.class)
@Table(name = "product_draft_generation_receipt", comment = "상품 초안 생성 등록 영수증",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_draft_generation_receipt_job_id", columnNames = "job_id"),
    // 회차 상한은 product 가 보내는 값을 DB 가 마지막으로 지킨다 (#359 PR-2b-2 결정 1)
    check = @CheckConstraint(name = "ck_product_draft_generation_receipt_generation_no", constraint = "generation_no between 1 and 3"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDraftGenerationReceiptEntity {
    @Id
    @Column(name = "draft_id", nullable = false, updatable = false, comment = "초안 식별자 (FK: product.product_draft.id)")
    private long draftId;

    @Id
    @Column(name = "generation_no", nullable = false, updatable = false, comment = "생성 회차 (1~3)")
    private int generationNo;

    @Column(name = "job_id", nullable = false, updatable = false, comment = "작업 식별자 (FK: operations.job.id)")
    private long jobId;

    @Column(name = "input_hash", nullable = false, updatable = false, length = 64, comment = "정규화한 입력 SHA-256")
    private String inputHash;

    @Column(name = "requested_by_member_id", nullable = false, updatable = false, comment = "요청자 식별자 (FK: auth.member.id)")
    private long requestedByMemberId;

    @Column(name = "expires_at", nullable = false, updatable = false, comment = "초안 만료 시각")
    private Instant expiresAt;

    public ProductDraftGenerationReceiptEntity(ProductDraftGenerationReceipt receipt) {
        draftId = receipt.draftId();
        generationNo = receipt.generationNo();
        jobId = receipt.jobId();
        inputHash = receipt.inputHash();
        requestedByMemberId = receipt.requestedByMemberId();
        expiresAt = receipt.expiresAt();
    }

    public ProductDraftGenerationReceipt toDomain() {
        return new ProductDraftGenerationReceipt(draftId, generationNo, jobId, inputHash, requestedByMemberId, expiresAt);
    }

    /** Composite primary key; field names and types must match the {@code @Id} fields above. */
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private long draftId;
        private int generationNo;
    }
}
