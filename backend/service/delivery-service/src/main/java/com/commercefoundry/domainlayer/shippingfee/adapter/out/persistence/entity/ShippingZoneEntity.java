package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 해외 배송 국가 그룹. 정책 교체마다 모두 지우고 새로 넣는다 — 아이디도 새로 발급된다.
 *
 * <p>유일 키({@code shipping_policy_id}, {@code name})가 정책별 조회 · 삭제의 인덱스를 겸한다 — 첫 컬럼이 {@code shipping_policy_id} 다.
 * 아이디를 직접 넣는(Snowflake) 엔티티라 {@link Persistable} 로 "새 엔티티" 를 알린다 ({@link ShippingRemoteAreaEntity} 와 같은 이유).
 */
@Entity
@Table(
    name = "shipping_zone",
    comment = "해외 배송 국가 그룹 (해외 배송비 정책별)",
    uniqueConstraints = @UniqueConstraint(name = "uk_shipping_zone_shipping_policy_id_name", columnNames = {"shipping_policy_id", "name"}),
    check = @CheckConstraint(name = "ck_shipping_zone_display_order_non_negative", constraint = "display_order >= 0")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingZoneEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "국가 그룹 아이디 (Snowflake). 정책 교체마다 새로 발급")
    private Long id;

    @Column(name = "shipping_policy_id", nullable = false, comment = "배송비 정책 아이디 (FK: shipping_policy.id)")
    private Long shippingPolicyId;

    @Column(name = "name", nullable = false, length = 50, comment = "국가 그룹 이름 (예: 아시아). 정책 안에서 유일")
    private String name;

    @Column(name = "display_order", nullable = false, comment = "표시 순서 (0 부터, 오름차순)")
    private int displayOrder;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    @Builder
    private ShippingZoneEntity(Long id, Long shippingPolicyId, String name, int displayOrder) {
        this.id = id;
        this.shippingPolicyId = shippingPolicyId;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
