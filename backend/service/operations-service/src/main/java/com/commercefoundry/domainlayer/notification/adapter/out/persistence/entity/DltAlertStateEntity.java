package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dlt_alert_state", comment = "DLT alert metadata")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DltAlertStateEntity extends BaseEntity {
    @Id
    @Column(length = 249, comment = "Qualified allowlisted DLT topic")
    private String topic;
    @Column(comment = "Last actual notification timestamp")
    private Instant lastNotifiedAt;
    @Version
    @Column(nullable = false, comment = "Optimistic lock version")
    private Long version;

    public DltAlertStateEntity(String topic) {
        this.topic = topic;
    }

    public void notifiedAt(Instant instant) {
        lastNotifiedAt = instant;
    }
}
