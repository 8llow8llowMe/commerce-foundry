package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
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
import org.springframework.data.domain.Persistable;

/**
 * 템플릿 항목(열). 이름 유니크는 맞바꾸기 중간을 견디지 못해, 고칠 때는 임시 이름으로 민 뒤 최종 이름을 쓴다.
 * 순서는 맞바꿀 수 있어 유니크를 두지 않고 애플리케이션이 중복을 거절한다. 순서가 0 미만이면 check 가 막는다.
 */
@Entity
@Table(
    name = "size_chart_template_column",
    comment = "사이즈표 템플릿 항목",
    uniqueConstraints = @UniqueConstraint(name = "uk_size_chart_template_column_template_id_name", columnNames = {"template_id", "name"}),
    indexes = @Index(name = "idx_size_chart_template_column_template_id_display_order", columnList = "template_id, display_order"),
    check = @CheckConstraint(name = "ck_size_chart_template_column_display_order_non_negative", constraint = "display_order >= 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SizeChartTemplateColumnEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "템플릿 항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "template_id", nullable = false, comment = "사이즈표 템플릿 아이디 (FK: size_chart_template.id)")
    private Long templateId;

    @Column(name = "name", nullable = false, length = 30, comment = "항목 이름")
    private String name;

    @Column(name = "description", length = 200, comment = "항목 설명. 없으면 null")
    private String description;

    @Column(name = "display_order", nullable = false, comment = "항목 노출 순서 (오름차순, 같으면 아이디 오름차순)")
    private int displayOrder;

    /** 이름 유니크를 맞바꾸기 전에 아이디만으로 된 임시 이름을 넣는다. 요청 검증이 제어 문자를 거절하므로 저장 값과 겹치지 않는다. */
    public void stageTemporaryName() {
        this.name = "\u0001" + id;
    }

    public void change(String name, String description, int displayOrder) {
        this.name = name;
        this.description = description;
        this.displayOrder = displayOrder;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
