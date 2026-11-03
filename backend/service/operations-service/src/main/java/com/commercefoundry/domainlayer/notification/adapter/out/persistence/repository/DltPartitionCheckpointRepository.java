package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.DltPartitionCheckpointEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DltPartitionCheckpointRepository extends JpaRepository<DltPartitionCheckpointEntity, Long> {
    Optional<DltPartitionCheckpointEntity> findByTopicAndPartitionNumber(String topic, int partitionNumber);
}
