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

@Entity
@Table(
    name = "product_option_value",
    comment = "상품 옵션 값",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_option_value_option_group_id_name", columnNames = {"option_group_id", "name"}),
    indexes = @Index(name = "idx_product_option_value_product_id", columnList = "product_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductOptionValueEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "옵션 값 아이디 (Snowflake)")
    private Long id;

    @Column(name = "option_group_id", nullable = false, comment = "옵션 그룹 아이디 (FK: product_option_group.id)")
    private Long optionGroupId;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id) — 상세를 상품 단위로 한 번에 읽기 위한 비정규화")
    private Long productId;

    @Column(name = "name", nullable = false, length = 50, comment = "옵션 값명")
    private String name;

    @Column(name = "display_order", nullable = false, comment = "그룹 안의 노출 순서")
    private int displayOrder;
}
