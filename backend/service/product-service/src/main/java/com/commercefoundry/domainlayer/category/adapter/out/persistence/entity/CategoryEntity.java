package com.commercefoundry.domainlayer.category.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객 화면은 전체 카테고리를 한 번에 읽어 트리로 만든다({@code CategoryTree}). 상위별 조회가 없어 {@code parent_id} 인덱스를 두지 않는다 —
 * 어드민 상위별 조회(#9)가 생기면 그때 더한다.
 */
@Entity
@Table(name = "category", comment = "상품 카테고리 (계층)")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CategoryEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "카테고리 아이디 (Snowflake)")
    private Long id;

    @Column(name = "parent_id", comment = "상위 카테고리 아이디 (FK: category.id). 최상위면 null")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 50, comment = "카테고리명")
    private String name;

    @Column(name = "display_order", nullable = false, comment = "같은 상위 안의 노출 순서 (오름차순)")
    private int displayOrder;

    @Column(name = "visible", nullable = false, comment = "스토어프론트 노출 여부")
    private boolean visible;
}
