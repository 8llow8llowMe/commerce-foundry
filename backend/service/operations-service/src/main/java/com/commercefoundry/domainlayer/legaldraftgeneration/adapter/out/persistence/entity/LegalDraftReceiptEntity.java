package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftInput;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftReceipt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Entity
@Table(name = "legal_draft_generation_receipt", comment = "법률 문서 AI 생성 등록과 검토 확인",
    uniqueConstraints = @UniqueConstraint(name = "uk_legal_draft_generation_receipt_job", columnNames = "job_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDraftReceiptEntity {
    @Id
    @Column(name = "key_hash", length = 64, nullable = false, updatable = false)
    private String keyHash;

    @Column(name = "job_id", nullable = false, updatable = false, comment = "작업 식별자 (FK: operations.job.id)")
    private long jobId;

    @Column(name = "member_id", nullable = false, updatable = false, comment = "요청자 식별자 (FK: auth.member.id)")
    private long memberId;

    @Column(name = "input_json", columnDefinition = "text", nullable = false, updatable = false)
    private String inputJson;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public LegalDraftReceiptEntity(LegalDraftReceipt receipt, ObjectMapper mapper) {
        keyHash = receipt.keyHash();
        jobId = receipt.jobId();
        memberId = receipt.memberId();
        inputJson = mapper.writeValueAsString(receipt.input());
    }

    public LegalDraftReceipt toDomain(ObjectMapper mapper) {
        return new LegalDraftReceipt(keyHash, jobId, memberId, mapper.readValue(inputJson, LegalDraftInput.class), reviewedAt);
    }
}
