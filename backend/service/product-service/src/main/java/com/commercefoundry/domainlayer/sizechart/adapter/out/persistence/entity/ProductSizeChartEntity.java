package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity;

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
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.domain.Persistable;

/** 상품당 사이즈표 하나. 치수의 단위는 템플릿에 있다. */
@Entity
@Table(
    name = "product_size_chart",
    comment = "상품 사이즈표",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_size_chart_product_id", columnNames = "product_id"),
    indexes = @Index(name = "idx_product_size_chart_template_id", columnList = "template_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductSizeChartEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "상품 사이즈표 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "template_id", nullable = false, comment = "사이즈표 템플릿 아이디 (FK: size_chart_template.id)")
    private Long templateId;

    @Column(name = "note", length = 500, comment = "사이즈표 비고. 없으면 null")
    private String note;

    /** 자식 행만 바뀌어도 증가하는 업무 리비전. JPA 낙관적 잠금 버전과는 별개다. */
    @ColumnDefault("0")
    @Column(name = "revision", nullable = false, comment = "업무 리비전. 최초 0, 전체 교체마다 1 증가")
    private long revision;

    public void change(long templateId, String note) {
        this.templateId = templateId;
        this.note = note;
        this.revision++;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
