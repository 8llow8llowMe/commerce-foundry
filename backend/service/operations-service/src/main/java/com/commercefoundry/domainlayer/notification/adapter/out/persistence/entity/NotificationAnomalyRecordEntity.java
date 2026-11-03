package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "notification_anomaly_record", comment = "처리한 DLT 메타데이터 (원문 없음)",
    uniqueConstraints = @UniqueConstraint(name = "uk_notification_anomaly_record_topic_partition_id_offset_id", columnNames = {"topic", "partition_id", "offset_id"}),
    indexes = @Index(name = "idx_notification_anomaly_record_processed_at", columnList = "processed_at"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationAnomalyRecordEntity extends BaseEntity implements Persistable<Long> {
    @Id
    @Column(name = "id", comment = "처리 기록 아이디 (Snowflake)")
    private Long id;

    @Column(name = "topic", nullable = false, length = 249, comment = "허용된 namespace 포함 DLT 토픽")
    private String topic;

    @Column(name = "partition_id", nullable = false, comment = "DLT 파티션")
    private int partitionId;

    @Column(name = "offset_id", nullable = false, comment = "DLT 오프셋")
    private long offsetId;

    @Column(name = "processed_at", nullable = false, comment = "처리 커밋 기준 시각")
    private Instant processedAt;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
