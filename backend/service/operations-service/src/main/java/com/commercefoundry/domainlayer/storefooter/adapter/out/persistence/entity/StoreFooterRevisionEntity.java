package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_footer_revision", comment = "푸터 불변 이중 언어 리비전",
    uniqueConstraints = @UniqueConstraint(name = "uk_store_footer_revision_footer_id_revision_number", columnNames = {"footer_id", "revision_number"}),
    check = {@CheckConstraint(name = "ck_store_footer_revision_number", constraint = "revision_number > 0"),
        @CheckConstraint(name = "ck_store_footer_revision_root", constraint = "footer_id = 1"),
        @CheckConstraint(name = "ck_store_footer_revision_publication", constraint = "(published_by is null and published_at is null)"
            + " or (published_by is not null and published_at is not null)")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreFooterRevisionEntity {
    @Id
    @Getter
    @Column(comment = "리비전 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, updatable = false, comment = "푸터 루트 아이디 (FK: store_footer.id)")
    private Long footerId;

    @Column(nullable = false, updatable = false, comment = "리비전 번호")
    private int revisionNumber;

    @Column(length = 200, updatable = false, comment = "국문 저작권 문구")
    private String copyrightKo;

    @Column(length = 200, updatable = false, comment = "영문 저작권 문구")
    private String copyrightEn;

    @Column(nullable = false, updatable = false, comment = "작성 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long createdBy;

    @Column(nullable = false, updatable = false, comment = "작성 시각 (UTC)")
    private Instant createdAt;

    @Column(comment = "최초 게시 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long publishedBy;

    @Column(comment = "최초 게시 시각 (UTC)")
    private Instant publishedAt;

    public static StoreFooterRevisionEntity create(StoreFooterRevision revision) {
        var entity = new StoreFooterRevisionEntity();
        entity.id = revision.id();
        entity.footerId = 1L;
        entity.revisionNumber = revision.revisionNumber();
        entity.copyrightKo = revision.content().copyrightText().ko();
        entity.copyrightEn = revision.content().copyrightText().en();
        entity.createdBy = revision.createdBy();
        entity.createdAt = revision.createdAt();
        return entity;
    }

    public void applyPublication(StoreFooterRevision revision) {
        if (publishedAt == null) {
            publishedBy = revision.publishedBy();
            publishedAt = revision.publishedAt();
        }
    }

    public StoreFooterRevision toDomain(List<StoreFooterContent.Link> links) {
        return new StoreFooterRevision(id, revisionNumber, new StoreFooterContent(new StoreFooterContent.Text(copyrightKo, copyrightEn), List.copyOf(links)),
            createdBy, createdAt, publishedBy, publishedAt);
    }
}
