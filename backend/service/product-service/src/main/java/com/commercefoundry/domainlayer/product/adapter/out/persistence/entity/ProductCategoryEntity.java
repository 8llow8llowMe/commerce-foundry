package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품이 진열되는 카테고리 (다중). 대표 카테고리({@code product.category_id})도 반드시 여기 있다 — 쓰기 쪽이 검증한다.
 *
 * <p>목록의 카테고리 필터는 이 테이블에 EXISTS 로 건다. JOIN 하면 한 상품이 여러 카테고리에 걸릴 때 행이 겹쳐 Slice 가 깨진다.
 */
@Entity
@Table(
    name = "product_category",
    comment = "상품 진열 카테고리 (다중)",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_category_product_id_category_id", columnNames = {"product_id", "category_id"}),
    indexes = @Index(name = "idx_product_category_category_id_product_id", columnList = "category_id, product_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductCategoryEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "상품 진열 카테고리 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "category_id", nullable = false, comment = "진열 카테고리 아이디 (FK: category.id)")
    private Long categoryId;

    @Column(name = "display_order", nullable = false, comment = "카테고리 안의 진열 순서 (오름차순). 어드민 수동 정렬용")
    private int displayOrder;
}
