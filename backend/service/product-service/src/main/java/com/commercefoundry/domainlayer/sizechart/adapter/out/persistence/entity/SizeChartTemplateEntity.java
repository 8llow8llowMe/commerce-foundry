package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 사이즈표 템플릿. 항목은 {@code size_chart_template_column}. 상품 표가 가리키는 동안에는 단위와 항목 집합을 바꾸지 않는다(애플리케이션 판정).
 *
 * <p>{@link Persistable} 로 새 행이면 INSERT 한다. Snowflake 아이디를 직접 넣는 엔티티라 기본 save 는 merge 로 가서 SELECT 를 한 번 더 보낸다.
 */
@Entity
@Table(
    name = "size_chart_template",
    comment = "사이즈표 템플릿",
    check = @CheckConstraint(name = "ck_size_chart_template_base_unit", constraint = "base_unit in ('CM', 'INCH')")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SizeChartTemplateEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "사이즈표 템플릿 아이디 (Snowflake)")
    private Long id;

    @Column(name = "name", nullable = false, length = 50, comment = "템플릿 이름")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", nullable = false, length = 10, comment = "치수 저장 단위 (CM 또는 INCH). 화면 변환은 응답에서만 한다")
    private SizeChartUnit baseUnit;

    public void change(String name, SizeChartUnit baseUnit) {
        this.name = name;
        this.baseUnit = baseUnit;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
