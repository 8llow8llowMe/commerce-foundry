package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigation;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_navigation", comment = "스토어 상단 메뉴 단일 설정 루트",
    check = {@CheckConstraint(name = "ck_store_navigation_singleton", constraint = "id = 1"),
        @CheckConstraint(name = "ck_store_navigation_pointers", constraint = "latest_revision > 0 and (published_revision is null"
            + " or (published_revision > 0 and published_revision <= latest_revision))"),
        @CheckConstraint(name = "ck_store_navigation_version", constraint = "version >= 0")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNavigationEntity {
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

    public static StoreNavigationEntity create(StoreNavigation navigation) {
        var entity = new StoreNavigationEntity();
        entity.id = 1L;
        entity.apply(navigation);
        return entity;
    }

    public void apply(StoreNavigation navigation) {
        latestRevision = navigation.latestRevision();
        publishedRevision = navigation.publishedRevision();
    }

    public StoreNavigation toDomain() {
        return new StoreNavigation(latestRevision, publishedRevision, version);
    }
}
