package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "legal_document_revision", comment = "법적 문서의 불변 본문 리비전",
    uniqueConstraints = @UniqueConstraint(name = "uk_legal_document_revision_document_id_revision_number", columnNames = {"document_id", "revision_number"}),
    check = {@CheckConstraint(name = "ck_legal_document_revision_number", constraint = "revision_number > 0"),
        @CheckConstraint(name = "ck_legal_document_revision_publication", constraint = "(published_by is null and published_at is null)"
            + " or (published_by is not null and published_at is not null)")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentRevisionEntity {
    @Id
    @Column(comment = "리비전 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, updatable = false, comment = "문서 아이디 (FK: legal_document.id)")
    private Long documentId;

    @Column(nullable = false, updatable = false, comment = "문서 내 리비전 번호")
    private int revisionNumber;

    @Column(nullable = false, updatable = false, length = 200, comment = "불변 제목")
    private String title;

    @Column(nullable = false, updatable = false, length = 50000, comment = "불변 본문 (단순 텍스트)")
    private String body;

    @Column(nullable = false, updatable = false, comment = "작성 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long createdBy;

    @Column(nullable = false, updatable = false, comment = "작성 시각")
    private Instant createdAt;

    @Column(comment = "최초 게시 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long publishedBy;

    @Column(comment = "최초 게시 시각")
    private Instant publishedAt;

    public static LegalDocumentRevisionEntity create(LegalDocumentRevision revision) {
        var entity = new LegalDocumentRevisionEntity();
        entity.id = revision.id();
        entity.documentId = revision.documentId();
        entity.revisionNumber = revision.revisionNumber();
        entity.title = revision.title();
        entity.body = revision.body();
        entity.createdBy = revision.createdBy();
        entity.createdAt = revision.createdAt();
        return entity;
    }

    public void applyPublication(LegalDocumentRevision revision) {
        if (publishedAt == null) {
            this.publishedBy = revision.publishedBy();
            this.publishedAt = revision.publishedAt();
        }
    }

    public LegalDocumentRevision toDomain() {
        return new LegalDocumentRevision(id, documentId, revisionNumber, title, body, createdBy, createdAt, publishedBy, publishedAt);
    }
}
