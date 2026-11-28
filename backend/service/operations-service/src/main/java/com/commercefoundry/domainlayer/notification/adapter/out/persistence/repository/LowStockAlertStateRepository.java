package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.LowStockAlertStateEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface LowStockAlertStateRepository extends JpaRepository<LowStockAlertStateEntity, String> {
    /** 단일 행 NOWAIT 잠금. 다른 점검이 잡고 있으면 기다리지 않고 실패한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select s from LowStockAlertStateEntity s where s.source = 'PRODUCT'")
    Optional<LowStockAlertStateEntity> findLocked();
}
