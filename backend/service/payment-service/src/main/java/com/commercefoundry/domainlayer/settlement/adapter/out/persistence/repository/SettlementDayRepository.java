package com.commercefoundry.domainlayer.settlement.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.SettlementDayEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface SettlementDayRepository extends JpaRepository<SettlementDayEntity, Long> {

    /**
     * 매출일 행을 잠가 읽는다 — PostgreSQL {@code for no key update nowait}({@code lock.timeout=0}). 같은 매출일의 다른 실행이 잡고 있으면 기다리지 않고 바로 실패한다
     * ({@code coding-conventions.md} §9-1 비관적 잠금, support 비회원 자격의 NOWAIT 선례).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select d from SettlementDayEntity d where d.soldDate = :soldDate")
    Optional<SettlementDayEntity> findBySoldDateForUpdate(LocalDate soldDate);
}
