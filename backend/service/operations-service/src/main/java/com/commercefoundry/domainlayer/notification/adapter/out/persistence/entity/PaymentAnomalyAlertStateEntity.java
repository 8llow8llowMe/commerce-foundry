package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
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
@Table(name = "payment_anomaly_alert_state", comment = "결제 이상 다섯 사건의 수락 워터마크", check = {
    @CheckConstraint(name = "ck_payment_anomaly_alert_state_family", constraint =
        "family in ('UNKNOWN','IN_PROGRESS','RETURNED_READY','LATE_APPROVED','DONE_ORDER_UNSYNCED')"),
    @CheckConstraint(name = "ck_payment_anomaly_alert_state_incident", constraint =
        "(status = 'NORMAL' and severity is null and first_detected_at is null and last_alert_at is null) or "
            + "(status = 'ACTIVE' and severity is not null and severity in ('WARNING','CRITICAL') "
            + "and first_detected_at is not null and last_alert_at is not null)"),
    @CheckConstraint(name = "ck_payment_anomaly_alert_state_severity", constraint =
        "status = 'NORMAL' or family = 'UNKNOWN' or (family in ('IN_PROGRESS','RETURNED_READY') and severity = 'WARNING') "
            + "or (family in ('LATE_APPROVED','DONE_ORDER_UNSYNCED') and severity = 'CRITICAL')"),
    @CheckConstraint(name = "ck_payment_anomaly_alert_state_chronology", constraint =
        "last_observed_at <= last_accepted_local_at and (status = 'NORMAL' or "
            + "(first_detected_at <= last_observed_at and first_detected_at <= last_alert_at and last_alert_at <= last_accepted_local_at))")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAnomalyAlertStateEntity extends BaseEntity {
    @Id
    @Column(length = 32, comment = "허용된 결제 이상 사건 키")
    private String family;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, comment = "사건 상태")
    private PaymentAnomalyPolicy.Status status;
    @Enumerated(EnumType.STRING)
    @Column(length = 16, comment = "활성 사건의 최고 긴급도")
    private NotificationSeverity severity;
    @Column(nullable = false, comment = "마지막 수락 원천 시각")
    private Instant lastObservedAt;
    @Column(nullable = false, comment = "마지막 수락 로컬 시각")
    private Instant lastAcceptedLocalAt;
    @Column(comment = "사건 개시 원천 시각")
    private Instant firstDetectedAt;
    @Column(comment = "마지막 알림 로컬 시각")
    private Instant lastAlertAt;
    @Version
    @Column(nullable = false, comment = "낙관적 락 버전")
    private Long version;

    public PaymentAnomalyAlertStateEntity(PaymentAnomalyPolicy.Family family, PaymentAnomalyPolicy.State state) {
        this.family = family.name();
        accept(state);
    }

    public void accept(PaymentAnomalyPolicy.State state) {
        var incident = state.incidents().get(PaymentAnomalyPolicy.Family.valueOf(family));
        status = incident.status();
        severity = incident.severity();
        firstDetectedAt = incident.firstDetectedAt();
        lastAlertAt = incident.lastAlertAt();
        lastObservedAt = state.lastObservedAt();
        lastAcceptedLocalAt = state.lastAcceptedLocalAt();
    }

    public PaymentAnomalyPolicy.Incident incident() {
        return new PaymentAnomalyPolicy.Incident(status, severity, firstDetectedAt, lastAlertAt);
    }
}
