package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dlt_partition_checkpoint", comment = "DLT alert metadata",
    uniqueConstraints = @UniqueConstraint(name = "uk_dlt_partition_checkpoint_topic_partition_number", columnNames = {"topic", "partition_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DltPartitionCheckpointEntity extends BaseEntity {
    @Id
    @Column(comment = "Checkpoint identifier (Snowflake)")
    private Long id;
    @Column(nullable = false, length = 249, comment = "Qualified allowlisted DLT topic")
    private String topic;
    @Column(name = "partition_number", nullable = false, comment = "Kafka partition number")
    private int partitionNumber;
    @Column(nullable = false, comment = "Highest processed Kafka offset")
    private long watermark;

    @Version
    @Column(nullable = false, comment = "Optimistic lock version")
    private Long version;

    public DltPartitionCheckpointEntity(long id, String topic, int partition, long offset) {
        this.id = id;
        this.topic = topic;
        this.partitionNumber = partition;
        this.watermark = offset;
    }

    public void advance(long offset) {
        if (offset <= watermark) {
            throw new IllegalArgumentException("Checkpoint must increase");
        }
        watermark = offset;
    }
}
