package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 주문 상태 변경 이력 — 추가만 한다 ({@code order-flow-guide.md} §1-1). 언제는 생성 시각이다. 바꾼 사람 아이디는 고객 · 어드민에만 있다
 * (도메인 {@code StatusChangedBy#hasActorId} 와 같은 규칙).
 */
@Entity
@Table(
    name = "order_status_history",
    comment = "주문 상태 변경 이력 (추가만 한다)",
    indexes = @Index(name = "idx_order_status_history_order_id_id", columnList = "order_id, id"),
    check = @CheckConstraint(
        name = "ck_order_status_history_changed_by_id_consistent",
        constraint = "(changed_by_type in ('CUSTOMER', 'ADMIN') and changed_by_id is not null)"
            + " or (changed_by_type in ('SYSTEM', 'SERVICE') and changed_by_id is null)"
    )
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderStatusHistoryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "이력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, comment = "이전 상태. 주문 생성이면 null")
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30, comment = "바뀐 상태")
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false, length = 20, comment = "바꾼 주체 유형 (CUSTOMER / ADMIN / SYSTEM / SERVICE)")
    private StatusChangedBy changedByType;

    @Column(name = "changed_by_id", comment = "바꾼 회원 · 관리자 아이디 (외부 FK: auth-service member.id). SYSTEM · SERVICE 면 null")
    private Long changedById;

    @Column(name = "reason", length = 200, comment = "사유. 없으면 null")
    private String reason;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
