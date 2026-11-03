package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;


import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.DltAlertStateEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface DltAlertStateRepository extends JpaRepository<DltAlertStateEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select s from DltAlertStateEntity s where s.topic = :topic")
    Optional<DltAlertStateEntity> findLocked(String topic);
}
