package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "legal_document", comment = "약관·개인정보처리방침 문서 루트",
    uniqueConstraints = @UniqueConstraint(name = "uk_legal_document_type_locale", columnNames = {"type", "locale"}),
    check = {@CheckConstraint(name = "ck_legal_document_locale", constraint = "locale in ('ko', 'en')"),
        @CheckConstraint(name = "ck_legal_document_pointers", constraint = "latest_revision > 0 and (published_revision is null"
            + " or (published_revision > 0 and published_revision <= latest_revision))")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentEntity {
    @Id
    @Column(comment = "문서 아이디 (Snowflake)")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false, comment = "문서 유형")
    private LegalDocumentKey.Type type;

    @Column(nullable = false, length = 2, updatable = false, comment = "문서 언어 (ko/en)")
    private String locale;

    @Column(nullable = false, comment = "최신 리비전 번호")
    private int latestRevision;

    @Column(comment = "현재 게시 리비전 번호 (미게시 null)")
    private Integer publishedRevision;

    @Version
    @Column(nullable = false, comment = "낙관적 편집 버전")
    private Long version;

    public static LegalDocumentEntity create(LegalDocument document) {
        var entity = new LegalDocumentEntity();
        entity.id = document.id();
        entity.type = document.key().type();
        entity.locale = document.key().locale();
        entity.apply(document);
        return entity;
    }

    public void apply(LegalDocument document) {
        this.latestRevision = document.latestRevision();
        this.publishedRevision = document.publishedRevision();
    }

    public LegalDocument toDomain() {
        return new LegalDocument(id, new LegalDocumentKey(type, locale), latestRevision, publishedRevision, version);
    }
}
