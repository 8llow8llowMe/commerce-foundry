package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 불변 리비전 머리. 게시 기록 두 컬럼만 최초 게시 때 한 번 채운다. */
@Entity
@Table(name = "store_navigation_revision", comment = "상단 메뉴 불변 리비전",
    uniqueConstraints = @UniqueConstraint(name = "uk_store_navigation_revision_navigation_id_revision_number",
        columnNames = {"navigation_id", "revision_number"}),
    check = {@CheckConstraint(name = "ck_store_navigation_revision_number", constraint = "revision_number > 0"),
        @CheckConstraint(name = "ck_store_navigation_revision_root", constraint = "navigation_id = 1"),
        @CheckConstraint(name = "ck_store_navigation_revision_publication", constraint = "(published_by is null and published_at is null)"
            + " or (published_by is not null and published_at is not null)")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNavigationRevisionEntity {
    @Id
    @Getter
    @Column(comment = "리비전 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, updatable = false, comment = "상단 메뉴 루트 아이디 (FK: store_navigation.id)")
    private Long navigationId;

    @Column(nullable = false, updatable = false, comment = "리비전 번호")
    private int revisionNumber;

    @Column(nullable = false, updatable = false, comment = "작성 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long createdBy;

    @Column(nullable = false, updatable = false, comment = "작성 시각 (UTC)")
    private Instant createdAt;

    @Column(comment = "최초 게시 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long publishedBy;

    @Column(comment = "최초 게시 시각 (UTC)")
    private Instant publishedAt;

    public static StoreNavigationRevisionEntity create(StoreNavigationRevision revision) {
        var entity = new StoreNavigationRevisionEntity();
        entity.id = revision.id();
        entity.navigationId = 1L;
        entity.revisionNumber = revision.revisionNumber();
        entity.createdBy = revision.createdBy();
        entity.createdAt = revision.createdAt();
        return entity;
    }

    /** 최초 게시만 기록한다. 이미 게시된 리비전을 다시 게시해도 처음 게시자·시각은 바뀌지 않는다. */
    public void applyPublication(StoreNavigationRevision revision) {
        if (publishedAt == null) {
            publishedBy = revision.publishedBy();
            publishedAt = revision.publishedAt();
        }
    }

    public StoreNavigationRevision toDomain(StoreNavigationContent content) {
        return new StoreNavigationRevision(id, revisionNumber, content, createdBy, createdAt, publishedBy, publishedAt);
    }
}
