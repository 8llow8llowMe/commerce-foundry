package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** Single product-owned draft and replay receipt. Raw content is deliberately excluded from toString. */
@Entity
@Table(
    name = "size_chart_draft",
    comment = "AI size chart draft and application receipt",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_size_chart_draft_requested_by_member_id_idempotency_key", columnNames = {"requested_by_member_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_size_chart_draft_job_id", columnNames = "job_id")
    },
    indexes = {
        @Index(name = "idx_size_chart_draft_product_id_id", columnList = "product_id, id"),
        @Index(name = "idx_size_chart_draft_requested_by_member_id_id", columnList = "requested_by_member_id, id"),
        @Index(name = "idx_size_chart_draft_expires_at_id", columnList = "expires_at, id")
    },
    check = {
        @CheckConstraint(name = "ck_size_chart_draft_base_chart_pair",
            constraint = "(base_chart_id is null and base_chart_revision is null) or (base_chart_id is not null and base_chart_id > 0 and base_chart_revision is not null and "
                + "base_chart_revision >= 0)"),
        @CheckConstraint(name = "ck_size_chart_draft_candidate",
            constraint = "(status in ('READY', 'APPLIED') and job_id is not null and candidate_json is not null and candidate_hash is not null) or "
                + "(status not in ('READY', 'APPLIED') and candidate_json is null and candidate_hash is null)"),
        @CheckConstraint(name = "ck_size_chart_draft_apply_receipt",
            constraint = "(status = 'APPLIED' and apply_hash is not null and apply_receipt_json is not null and applied_by_member_id is not null and "
                + "applied_at is not null) or (status <> 'APPLIED' and apply_hash is null and apply_receipt_json is null and applied_by_member_id "
                + "is null and applied_at is null)"),
        @CheckConstraint(name = "ck_size_chart_draft_revision",
            constraint = "template_revision >= 0 and version >= 0"),
        @CheckConstraint(name = "ck_size_chart_draft_hash_length",
            constraint = "char_length(request_hash) = 64 and char_length(input_hash) = 64 and (candidate_hash is null or char_length(candidate_hash) = "
                + "64) and (apply_hash is null or char_length(apply_hash) = 64)"),
        @CheckConstraint(name = "ck_size_chart_draft_source_json_length",
            constraint = "char_length(source_json) between 1 and 32768"),
        @CheckConstraint(name = "ck_size_chart_draft_template_json_length",
            constraint = "char_length(template_json) between 1 and 32768"),
        @CheckConstraint(name = "ck_size_chart_draft_candidate_json_length",
            constraint = "char_length(candidate_json) between 1 and 32768"),
        @CheckConstraint(name = "ck_size_chart_draft_apply_receipt_json_length",
            constraint = "char_length(apply_receipt_json) between 1 and 32768")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SizeChartDraftEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false,
        comment = "Draft ID (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false,
        comment = "Product ID (FK: product.id)")
    private Long productId;

    @Column(name = "template_id", nullable = false, updatable = false,
        comment = "Template ID (FK: size_chart_template.id)")
    private Long templateId;

    @Column(name = "template_revision", nullable = false, updatable = false,
        comment = "Captured template revision")
    private Long templateRevision;

    @Column(name = "requested_by_member_id", nullable = false, updatable = false,
        comment = "Requesting admin ID (external FK: auth.member.id)")
    private Long requestedByMemberId;

    @Column(name = "idempotency_key", nullable = false, updatable = false,
        comment = "Actor scoped request key")
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false,
        comment = "Canonical request SHA-256 lowercase hex")
    private String requestHash;

    @Column(name = "input_hash", nullable = false, length = 64, updatable = false,
        comment = "Generation input SHA-256 lowercase hex")
    private String inputHash;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "source_json", nullable = false, updatable = false,
        comment = "Sanitized source snapshot, at most 32768 characters")
    private String sourceJson;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "template_json", nullable = false, updatable = false,
        comment = "Sanitized template snapshot, at most 32768 characters")
    private String templateJson;

    @Column(name = "base_chart_id", updatable = false,
        comment = "Captured chart ID (FK: product_size_chart.id), absent with revision")
    private Long baseChartId;

    @Column(name = "base_chart_revision", updatable = false,
        comment = "Captured chart revision, absent with ID")
    private Long baseChartRevision;

    @Column(name = "job_id",
        comment = "Generation job ID (external FK: operations.job.id)")
    private Long jobId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "candidate_json",
        comment = "Validated candidate snapshot, at most 32768 characters")
    private String candidateJson;

    @Column(name = "candidate_hash", length = 64,
        comment = "Candidate JSON SHA-256 lowercase hex")
    private String candidateHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16,
        comment = "REQUESTED / READY / FAILED / CANCELLED / APPLIED")
    private SizeChartDraftStatus status;

    @Version
    @Column(name = "version", nullable = false,
        comment = "Optimistic lock version")
    private Long version;

    @Column(name = "expires_at", nullable = false, updatable = false,
        comment = "Content expiry; mutations are rejected from this instant")
    private Instant expiresAt;

    @Column(name = "apply_hash", length = 64,
        comment = "Canonical apply request SHA-256 lowercase hex")
    private String applyHash;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "apply_receipt_json",
        comment = "Sanitized original apply response, at most 32768 characters")
    private String applyReceiptJson;

    @Column(name = "applied_by_member_id",
        comment = "Applying admin ID (external FK: auth.member.id)")
    private Long appliedByMemberId;

    @Column(name = "applied_at",
        comment = "Original apply time")
    private Instant appliedAt;

    /** Only a validated domain transition can reach the managed state. The adapter checks its original version. */
    public void change(SizeChartDraft next, Instant now) {
        next.validateUpdateFrom(toDomain(), now);
        jobId = next.jobId();
        candidateJson = next.candidate() == null ? null : next.candidate().json();
        candidateHash = next.candidate() == null ? null : next.candidate().hash();
        status = next.status();
        applyHash = next.applyReceipt() == null ? null : next.applyReceipt().applyHash();
        applyReceiptJson = next.applyReceipt() == null ? null : next.applyReceipt().receiptJson();
        appliedByMemberId = next.applyReceipt() == null ? null : next.applyReceipt().appliedByMemberId();
        appliedAt = next.applyReceipt() == null ? null : next.applyReceipt().appliedAt();
    }

    public SizeChartDraft toDomain() {
        SizeChartDraft.Input input = new SizeChartDraft.Input(productId, templateId, templateRevision, requestedByMemberId,
            idempotencyKey, requestHash, inputHash, sourceJson, templateJson, baseChartId, baseChartRevision);
        SizeChartDraft.Candidate candidate = candidateJson == null ? null : new SizeChartDraft.Candidate(candidateJson, candidateHash);
        SizeChartDraft.ApplyReceipt receipt = applyHash == null ? null
            : new SizeChartDraft.ApplyReceipt(applyHash, applyReceiptJson, appliedByMemberId, appliedAt);
        return new SizeChartDraft(id, input, jobId, candidate, status, version == null ? 0 : version, expiresAt, receipt);
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }

    @Override
    public String toString() {
        return "SizeChartDraftEntity[id=" + id + ", status=" + status + ", version=" + version + "]";
    }

    /** Lombok의 기본 builder 문자열에도 스냅숏을 노출하지 않는다. */
    public static class SizeChartDraftEntityBuilder {

        @Override
        public String toString() {
            return "SizeChartDraftEntityBuilder[redacted]";
        }
    }
}
