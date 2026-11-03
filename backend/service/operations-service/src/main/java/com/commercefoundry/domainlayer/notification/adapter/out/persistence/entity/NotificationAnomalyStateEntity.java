package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_anomaly_state", comment = "DLT 출처별 알림 억제 상태",
    uniqueConstraints = @UniqueConstraint(name = "uk_notification_anomaly_state_source_key", columnNames = "source_key"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationAnomalyStateEntity extends BaseEntity {
    @Id
    @Column(name = "id", comment = "상태 아이디 (Snowflake)")
    private Long id;

    @Column(name = "source_key", nullable = false, length = 249, comment = "허용된 DLT 출처")
    private String sourceKey;

    @Column(name = "last_seen_at", nullable = false, comment = "최근 새 레코드 처리 시각")
    private Instant lastSeenAt;

    @Column(name = "last_notified_at", comment = "최근 개인 알림 발행 시각")
    private Instant lastNotifiedAt;

    @Version
    @Column(name = "version", nullable = false, comment = "창 판정 CAS 버전")
    private Long version;
}
