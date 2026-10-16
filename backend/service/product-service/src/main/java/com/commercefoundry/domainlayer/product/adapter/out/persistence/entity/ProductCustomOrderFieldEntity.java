package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입력 범위 값(min · max · step)은 금액이 아니라 치수 · 글자 수라 {@code numeric(10,2)} 로 둔다.
 */
@Entity
@Table(
    name = "product_custom_order_field",
    comment = "맞춤 제작 입력 항목 (치수 등)",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_custom_order_field_product_id_field_key", columnNames = {"product_id", "field_key"})
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductCustomOrderFieldEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "입력 항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "field_key", nullable = false, length = 30, comment = "입력 항목 키 (chest, memo). 주문 항목 스냅숏과 검증 오류 field 이름에 쓴다")
    private String fieldKey;

    @Column(name = "label", nullable = false, length = 50, comment = "표시 이름")
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 20, comment = "입력 유형 (NUMBER / TEXT)")
    private CustomOrderInputType inputType;

    @Column(name = "unit", length = 10, comment = "단위 (cm)")
    private String unit;

    @Column(name = "required", nullable = false, comment = "필수 입력 여부")
    private boolean required;

    @Column(name = "min_value", precision = 10, scale = 2, comment = "NUMBER 최소값")
    private BigDecimal minValue;

    @Column(name = "max_value", precision = 10, scale = 2, comment = "NUMBER 최대값 / TEXT 최대 글자 수")
    private BigDecimal maxValue;

    @Column(name = "step_value", precision = 10, scale = 2, comment = "NUMBER 증분 (0.5 등)")
    private BigDecimal stepValue;

    @Column(name = "help_text", length = 200, comment = "도움말")
    private String helpText;

    @Column(name = "display_order", nullable = false, comment = "노출 순서")
    private int displayOrder;
}
