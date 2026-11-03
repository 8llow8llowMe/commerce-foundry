package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationAnomalyRecordEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationAnomalyRecordRepository extends JpaRepository<NotificationAnomalyRecordEntity, Long> {
    boolean existsByTopicAndPartitionIdAndOffsetId(String topic, int partition, long offset);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotificationAnomalyRecordEntity record where record.processedAt < :cutoff")
    int deleteProcessedBefore(Instant cutoff);
}
