package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 승인 내용 스냅숏. 상태 전이와 편집은 도메인을 거쳐 버전 비교 merge로 저장한다. */
@Entity
@Table(name = "knowledge_revision", comment = "상담 지식 리비전",
    uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_revision_document_id_revision_number", columnNames = {"document_id", "revision_number"}),
    indexes = @Index(name = "idx_knowledge_revision_status_valid_from_valid_to_id", columnList = "status, valid_from, valid_to, id"),
    check = {
        @CheckConstraint(name = "ck_knowledge_revision_number", constraint = "revision_number > 0 and document_id > 0"),
        @CheckConstraint(name = "ck_knowledge_revision_content", constraint = "length(trim(title)) > 0 and length(trim(body)) > 0"),
        @CheckConstraint(name = "ck_knowledge_revision_period", constraint = "valid_to is null or valid_to > valid_from"),
        @CheckConstraint(name = "ck_knowledge_revision_approval", constraint = "(approved_by is null and approved_at is null)"
            + " or (approved_by is not null and approved_by > 0 and approved_at is not null)"),
        @CheckConstraint(name = "ck_knowledge_revision_status_metadata", constraint = "(status <> 'DRAFT' or approved_by is null)"
            + " and (status not in ('APPROVED','PUBLISHED') or (approved_by is not null and approved_at is not null))"
            + " and (status <> 'PUBLISHED' or published_at is not null)"
            + " and (status not in ('DRAFT','APPROVED') or published_at is null)"
            + " and (published_at is null or approved_at is not null)")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class KnowledgeRevisionEntity extends BaseEntity {

    @Id
    @Column(comment = "리비전 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, comment = "문서 아이디 (FK: knowledge_document.id)")
    private Long documentId;

    @Column(nullable = false, comment = "문서 내 리비전 번호")
    private int revisionNumber;

    @Column(nullable = false, length = 200, comment = "승인 대상 제목")
    private String title;

    @Column(nullable = false, length = 8000, comment = "승인 대상 본문")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, comment = "지식 카테고리")
    private KnowledgeCategory category;

    @Column(nullable = false, comment = "유효 시작 시각 (포함)")
    private Instant validFrom;

    @Column(comment = "유효 종료 시각 (제외), null이면 무기한")
    private Instant validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "리비전 상태")
    private KnowledgeStatus status;

    @Column(comment = "승인 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long approvedBy;

    @Column(comment = "승인 시각")
    private Instant approvedAt;

    @Column(comment = "게시 시각")
    private Instant publishedAt;

    @Version
    @Column(nullable = false, comment = "리비전 낙관적 락 버전")
    private Long version;
}
