package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.notification.domain.model.OutboxAlertState;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "outbox_alert_state", comment = "Payment outbox observation incident watermarks", check = {
    @CheckConstraint(name = "ck_outbox_alert_state_source", constraint = "source = 'PAYMENT'"),
    @CheckConstraint(name = "ck_outbox_alert_state_incident", constraint =
        "(status = 'NORMAL' and first_detected_at is null and last_alert_at is null) or (status = 'ACTIVE' and first_detected_at is not null and last_alert_at is not null)")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxAlertStateEntity extends BaseEntity {
    @Id
    @Column(length = 16, comment = "Allowlisted observation source")
    private String source;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, comment = "Observation incident status")
    private OutboxAlertState.Status status;
    @Column(nullable = false, comment = "Last accepted source observation timestamp")
    private Instant lastObservedAt;
    @Column(nullable = false, comment = "Last accepted local timestamp")
    private Instant lastAcceptedLocalAt;
    @Column(comment = "First qualifying source observation timestamp")
    private Instant firstDetectedAt;
    @Column(comment = "Last actual alert local timestamp")
    private Instant lastAlertAt;
    @Version
    @Column(nullable = false, comment = "Optimistic lock version")
    private Long version;

    public OutboxAlertStateEntity(OutboxAlertState state) {
        source = "PAYMENT";
        accept(state);
    }
    public void accept(OutboxAlertState state) {
        status = state.status();
        lastObservedAt = state.lastObservedAt();
        lastAcceptedLocalAt = state.lastAcceptedLocalAt();
        firstDetectedAt = state.firstDetectedAt();
        lastAlertAt = state.lastAlertAt();
    }
    public OutboxAlertState toDomain() {
        return new OutboxAlertState(status, lastObservedAt, lastAcceptedLocalAt, firstDetectedAt, lastAlertAt);
    }
}
