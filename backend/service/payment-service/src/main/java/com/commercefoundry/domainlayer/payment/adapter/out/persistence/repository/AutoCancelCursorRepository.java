package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.AutoCancelCursorEntity;
import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface AutoCancelCursorRepository extends JpaRepository<AutoCancelCursorEntity, AutoCancelLane> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AutoCancelCursorEntity c where c.lane = :lane")
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"))
    Optional<AutoCancelCursorEntity> findLocked(AutoCancelLane lane);
}
