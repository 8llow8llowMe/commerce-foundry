package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리비전별 불변 메뉴 항목. 편집용 key 가 리비전 안에서만 유일하므로 PK 는 (리비전, key) 다.
 * 상위 항목은 같은 리비전의 key 로만 가리키며, 형제 순서 유일성은 parent_key 가 null 일 수 있어 도메인 검증이 맡는다.
 * Hibernate 는 복합 PK 컬럼을 속성 이름순(item_key, revision_id)으로 만든다. 리비전 단위 벌크 조회는 별도 인덱스가 받는다.
 */
@Entity
@IdClass(StoreNavigationItemEntity.Key.class)
@Table(name = "store_navigation_item", comment = "상단 메뉴 리비전별 불변 항목",
    indexes = @Index(name = "idx_store_navigation_item_revision_id", columnList = "revision_id"),
    check = {@CheckConstraint(name = "ck_store_navigation_item_type", constraint = "type in ('INTERNAL', 'EXTERNAL', 'CATEGORY', 'NONE')"),
        @CheckConstraint(name = "ck_store_navigation_item_order", constraint = "display_order between 0 and 999"),
        @CheckConstraint(name = "ck_store_navigation_item_parent", constraint = "parent_key is null or parent_key <> item_key"),
        @CheckConstraint(name = "ck_store_navigation_item_target", constraint = "(type in ('INTERNAL', 'EXTERNAL') and url is not null"
            + " and category_id is null) or (type = 'CATEGORY' and url is null and category_id > 0)"
            + " or (type = 'NONE' and url is null and category_id is null)")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNavigationItemEntity {
    @Id
    @Getter
    @Column(nullable = false, updatable = false, comment = "리비전 아이디 (FK: store_navigation_revision.id)")
    private Long revisionId;

    @Id
    @Column(nullable = false, updatable = false, length = 40, comment = "리비전 안 편집용 항목 key")
    private String itemKey;

    @Column(updatable = false, length = 40, comment = "같은 리비전의 상위 항목 key (최상위 null)")
    private String parentKey;

    @Column(nullable = false, updatable = false, length = 20, comment = "메뉴 유형 제어 코드")
    private String type;

    @Column(nullable = false, updatable = false, length = 40, comment = "국문 표시명")
    private String labelKo;

    @Column(nullable = false, updatable = false, length = 40, comment = "영문 표시명")
    private String labelEn;

    @Column(updatable = false, length = 2048, comment = "검증한 내부 경로 또는 외부 HTTPS 주소 (CATEGORY·NONE 은 null)")
    private String url;

    @Column(updatable = false, comment = "카테고리 아이디 (외부 FK: product-service category.id, CATEGORY 만)")
    private Long categoryId;

    @Column(nullable = false, updatable = false, comment = "같은 상위 항목 안 표시 순서")
    private int displayOrder;

    @Column(nullable = false, updatable = false, comment = "공개 노출 여부")
    private boolean visible;

    public static StoreNavigationItemEntity create(long revisionId, StoreNavigationContent.Item item) {
        var entity = new StoreNavigationItemEntity();
        entity.revisionId = revisionId;
        entity.itemKey = item.key();
        entity.parentKey = item.parentKey();
        entity.type = item.type();
        entity.labelKo = item.labels().ko();
        entity.labelEn = item.labels().en();
        entity.url = item.url();
        entity.categoryId = item.categoryId();
        entity.displayOrder = item.displayOrder();
        entity.visible = item.visible();
        return entity;
    }

    public StoreNavigationContent.Item toDomain() {
        return new StoreNavigationContent.Item(itemKey, parentKey, type, new StoreNavigationContent.Text(labelKo, labelEn), url,
            categoryId, displayOrder, visible);
    }

    /** 복합 PK. 필드 이름·타입은 위 {@code @Id} 필드와 같아야 한다. */
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long revisionId;
        private String itemKey;
    }
}
