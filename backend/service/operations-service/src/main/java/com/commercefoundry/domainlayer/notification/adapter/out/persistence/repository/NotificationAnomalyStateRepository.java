package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationAnomalyStateEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAnomalyStateRepository extends JpaRepository<NotificationAnomalyStateEntity, Long> {
    Optional<NotificationAnomalyStateEntity> findBySourceKey(String sourceKey);
}
