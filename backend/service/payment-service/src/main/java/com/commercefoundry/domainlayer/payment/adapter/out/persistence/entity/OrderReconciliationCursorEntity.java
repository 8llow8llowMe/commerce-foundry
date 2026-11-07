package com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "order_reconciliation_cursor", comment = "주문 회신 복구 lane별 영속 순회 커서", check = {
    @CheckConstraint(name = "ck_order_reconciliation_cursor_lane", constraint = "lane in ('RECONCILE','REJECTED')"),
    @CheckConstraint(name = "ck_order_reconciliation_cursor_bounds", constraint = "last_id >= 0 and cycle_upper_id >= last_id")
})
public class OrderReconciliationCursorEntity extends BaseEntity {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, comment = "독립 처리 lane (RECONCILE 또는 REJECTED)")
    private OrderReconciliationLane lane;

    @Column(nullable = false, comment = "예약을 커밋한 마지막 결제 아이디")
    private long lastId;

    @Column(nullable = false, comment = "cycle 시작 시 결제 아이디 상한 (0이면 새 cycle)")
    private long cycleUpperId;

    @Column(nullable = false, comment = "cycle 시작 시 strict 승인 시각 cutoff (REJECTED는 사용 안 함)")
    private Instant approvedBefore;

    @Version
    @Column(nullable = false, comment = "커서 예약 낙관적 락 버전")
    private Long version;

    protected OrderReconciliationCursorEntity() { }

    public OrderReconciliationCursorEntity(OrderReconciliationLane lane, Instant approvedBefore) {
        this.lane = lane;
        this.approvedBefore = approvedBefore;
    }

    public long lastId() { return lastId; }
    public long cycleUpperId() { return cycleUpperId; }
    public Instant approvedBefore() { return approvedBefore; }

    public void beginCycle(long upperId, Instant cutoff) {
        lastId = 0;
        cycleUpperId = upperId;
        approvedBefore = cutoff;
    }

    public void advance(long id) {
        if (id <= lastId || id > cycleUpperId) throw new IllegalArgumentException("Invalid cursor progress");
        lastId = id;
    }

    public void finishCycle() {
        lastId = 0;
        cycleUpperId = 0;
    }
}
