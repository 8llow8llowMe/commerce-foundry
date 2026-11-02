package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 게시 포인터와 최신 번호의 직렬화 루트. 연관관계 없이 리비전 번호만 보유한다. */
@Entity
@Table(name = "knowledge_document", comment = "상담 지식 문서",
    indexes = @Index(name = "idx_knowledge_document_locale_published_revision_id", columnList = "locale, published_revision, id"),
    check = @CheckConstraint(name = "ck_knowledge_document_revision", constraint = "latest_revision > 0 and (published_revision is null"
        + " or (published_revision > 0 and published_revision <= latest_revision))"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class KnowledgeDocumentEntity extends BaseEntity {

    @Id
    @Column(comment = "문서 아이디 (Snowflake)")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "지식 유형")
    private KnowledgeType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2, comment = "정확한 게시 언어")
    private KnowledgeLocale locale;

    @Column(nullable = false, comment = "최신 리비전 번호")
    private int latestRevision;

    @Column(comment = "현재 게시 리비전 번호 (FK: knowledge_revision.document_id + revision_number)")
    private Integer publishedRevision;

    @Version
    @Column(nullable = false, comment = "문서 낙관적 락 버전")
    private Long version;
}
