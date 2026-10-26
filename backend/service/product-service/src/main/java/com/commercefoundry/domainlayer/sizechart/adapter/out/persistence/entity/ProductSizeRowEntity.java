package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/** 사이즈표의 한 사이즈(행). 교체할 때 지우고 다시 넣으므로 이름 · 순서 유니크를 맞바꾸지 않는다. */
@Entity
@Table(
    name = "product_size_row",
    comment = "상품 사이즈표 행",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_size_row_size_chart_id_size_label", columnNames = {"size_chart_id", "size_label"}),
        @UniqueConstraint(name = "uk_product_size_row_size_chart_id_display_order", columnNames = {"size_chart_id", "display_order"})
    },
    check = @CheckConstraint(name = "ck_product_size_row_display_order_non_negative", constraint = "display_order >= 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductSizeRowEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "사이즈 행 아이디 (Snowflake)")
    private Long id;

    @Column(name = "size_chart_id", nullable = false, comment = "상품 사이즈표 아이디 (FK: product_size_chart.id)")
    private Long sizeChartId;

    @Column(name = "size_label", nullable = false, length = 20, comment = "사이즈 이름")
    private String sizeLabel;

    @Column(name = "display_order", nullable = false, comment = "사이즈 노출 순서 (같은 표 안에서 유일, 오름차순)")
    private int displayOrder;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
