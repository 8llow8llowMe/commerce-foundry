package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.OrderReconciliationCursorEntity;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface OrderReconciliationCursorRepository extends JpaRepository<OrderReconciliationCursorEntity, OrderReconciliationLane> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OrderReconciliationCursorEntity c where c.lane = :lane")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    Optional<OrderReconciliationCursorEntity> findLocked(OrderReconciliationLane lane);
}
