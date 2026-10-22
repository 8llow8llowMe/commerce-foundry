package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "product_option_group",
    comment = "상품 옵션 그룹 (색상·사이즈)",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_option_group_product_id_name", columnNames = {"product_id", "name"})
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductOptionGroupEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "옵션 그룹 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "name", nullable = false, length = 50, comment = "옵션 그룹명")
    private String name;

    @Column(name = "display_order", nullable = false, comment = "노출 순서 (오름차순)")
    private int displayOrder;

    @Column(name = "retired_at", comment = "은퇴 시각. null 이면 사용 중. 은퇴한 그룹은 고객 상세에서 빠지고, 행은 주문 · 장바구니의 옵션명 표시용으로 남는다")
    private Instant retiredAt;
}
