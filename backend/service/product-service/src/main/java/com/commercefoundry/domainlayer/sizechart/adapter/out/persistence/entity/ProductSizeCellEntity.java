package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 사이즈 행과 템플릿 항목이 만나는 칸. 값은 금액이 아니라 치수라 {@code numeric(10,2)} 다.
 * 0 초과 9999.99 이하인 것은 요청 검증과 같은 check 다.
 */
@Entity
@Table(
    name = "product_size_cell",
    comment = "상품 사이즈표 칸",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_size_cell_row_id_column_id", columnNames = {"row_id", "column_id"}),
    check = @CheckConstraint(name = "ck_product_size_cell_value_range", constraint = "cell_value > 0 and cell_value <= 9999.99")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductSizeCellEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "사이즈 칸 아이디 (Snowflake)")
    private Long id;

    @Column(name = "row_id", nullable = false, comment = "사이즈 행 아이디 (FK: product_size_row.id)")
    private Long rowId;

    @Column(name = "column_id", nullable = false, comment = "템플릿 항목 아이디 (FK: size_chart_template_column.id)")
    private Long columnId;

    @Column(name = "cell_value", nullable = false, precision = 10, scale = 2, comment = "치수 값. 0 초과 9999.99 이하, 소수 둘째 자리. 템플릿 저장 단위 그대로")
    private BigDecimal cellValue;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
