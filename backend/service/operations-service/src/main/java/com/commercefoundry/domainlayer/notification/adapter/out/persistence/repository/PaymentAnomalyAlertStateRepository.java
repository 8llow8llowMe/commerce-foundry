package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.PaymentAnomalyAlertStateEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface PaymentAnomalyAlertStateRepository extends JpaRepository<PaymentAnomalyAlertStateEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select s from PaymentAnomalyAlertStateEntity s order by s.family")
    List<PaymentAnomalyAlertStateEntity> findAllLocked();
}
