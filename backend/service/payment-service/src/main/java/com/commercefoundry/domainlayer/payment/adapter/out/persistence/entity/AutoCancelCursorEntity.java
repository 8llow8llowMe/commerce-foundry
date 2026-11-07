package com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
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
import java.util.Objects;

@Entity
@Table(name = "auto_cancel_cursor", comment = "Automatic cancellation discovery and execution cursors", check = {
    @CheckConstraint(name = "ck_auto_cancel_cursor_lane", constraint = "lane in ('REJECTED','LATE_APPROVED','PENDING')"),
    @CheckConstraint(name = "ck_auto_cancel_cursor_bounds", constraint = "last_id >= 0 and cycle_upper_id >= last_id")
})
public class AutoCancelCursorEntity extends BaseEntity {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AutoCancelLane lane;
    @Column(name = "last_id", nullable = false)
    private long lastId;
    @Column(name = "cycle_upper_id", nullable = false)
    private long cycleUpperId;
    @Column(name = "due_before", nullable = false)
    private Instant dueBefore;
    @Version
    @Column(nullable = false)
    private Long version;

    protected AutoCancelCursorEntity() { }
    public AutoCancelCursorEntity(AutoCancelLane lane) {
        this.lane = Objects.requireNonNull(lane);
        this.dueBefore = Instant.EPOCH;
    }
    public long lastId() { return lastId; }
    public long cycleUpperId() { return cycleUpperId; }
    public Instant dueBefore() { return dueBefore; }
    public void beginCycle(long upperId, Instant cutoff) {
        if (upperId < 0) throw new IllegalArgumentException("Negative cycle upper bound");
        lastId = 0;
        cycleUpperId = upperId;
        dueBefore = Objects.requireNonNull(cutoff);
    }
    public void advance(long id) {
        if (id <= lastId || id > cycleUpperId) throw new IllegalArgumentException("Invalid automatic cursor progress");
        lastId = id;
    }
    public void finishCycle() {
        lastId = 0;
        cycleUpperId = 0;
    }
}
