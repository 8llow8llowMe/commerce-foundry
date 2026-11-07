package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooter;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_footer", comment = "스토어 푸터 단일 설정 루트",
    check = {@CheckConstraint(name = "ck_store_footer_singleton", constraint = "id = 1"),
        @CheckConstraint(name = "ck_store_footer_pointers", constraint = "latest_revision > 0 and (published_revision is null"
            + " or (published_revision > 0 and published_revision <= latest_revision))"),
        @CheckConstraint(name = "ck_store_footer_version", constraint = "version >= 0")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreFooterEntity {
    @Id
    @Column(comment = "고정 루트 아이디 (1)")
    private Long id;

    @Version
    @Column(nullable = false, comment = "낙관적 편집 버전")
    private Long version;

    @Column(nullable = false, comment = "최신 리비전 번호")
    private int latestRevision;

    @Column(comment = "게시 리비전 번호 (미게시 null)")
    private Integer publishedRevision;

    public static StoreFooterEntity create(StoreFooter footer) {
        var entity = new StoreFooterEntity();
        entity.id = 1L;
        entity.apply(footer);
        return entity;
    }

    public void apply(StoreFooter footer) {
        latestRevision = footer.latestRevision();
        publishedRevision = footer.publishedRevision();
    }

    public StoreFooter toDomain() {
        return new StoreFooter(latestRevision, publishedRevision, version);
    }
}
