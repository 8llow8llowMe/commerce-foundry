package com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageMonthEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * AI 사용 월 카운터. 쓰기는 모두 조건부 UPDATE 한 문장이고 반환값은 바뀐 행 수(0 또는 1)다 ({@code coding-conventions.md} §9-1 · §9-5).
 * 읽고 판정해 merge 하지 않는다 — 동시 예약이 각자 읽은 누계로 함께 한도를 넘는다. PostgreSQL(READ COMMITTED)은 같은 행의 UPDATE 를 줄 세우고 앞선 커밋 뒤
 * 조건을 다시 평가한다. 버전을 직접 올리고, bulk UPDATE 는 감사 리스너를 거치지 않아 {@code updatedAt} 을 인자로 받아 직접 세팅한다.
 */
public interface AiUsageMonthRepository extends JpaRepository<AiUsageMonthEntity, Long> {

    Optional<AiUsageMonthEntity> findByPeriod(String period);

    /** 누계 + 예상치가 한도 이하일 때만 예약한다. 한도 스냅숏도 이번 한도로 바꾼다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update AiUsageMonthEntity m
           set m.reservedMicros = m.reservedMicros + :estimateMicros, m.limitMicrosSnapshot = :limitMicros, m.updatedAt = :now, m.version = m.version + 1
         where m.period = :period and m.reservedMicros + :estimateMicros <= :limitMicros
        """)
    int reserve(String period, long estimateMicros, long limitMicros, Instant now);

    /** 진행 중 예상치를 확정 비용으로 옮긴다. 조건 없이 그 월 행을 바꾼다 — 정산은 한도를 다시 판정하지 않는다(이미 예약한 호출이다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update AiUsageMonthEntity m
           set m.reservedMicros = m.reservedMicros - :estimateMicros + :actualMicros, m.settledMicros = m.settledMicros + :actualMicros,
               m.callCount = m.callCount + :calls, m.updatedAt = :now, m.version = m.version + 1
         where m.period = :period
        """)
    int settle(String period, long estimateMicros, long actualMicros, long calls, Instant now);

    /** 예약 누계가 임계 이상이고 아직 경고하지 않았으면 경고 시각을 세운다 — 기간당 한 번. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update AiUsageMonthEntity m
           set m.warnedAt = :now, m.updatedAt = :now, m.version = m.version + 1
         where m.period = :period and m.warnedAt is null and m.reservedMicros >= :thresholdMicros
        """)
    int markWarned(String period, long thresholdMicros, Instant now);

    /** 정산 뒤 — 예약 누계가 한도 이상이고 아직 초과를 표시하지 않았으면 초과 시각을 세운다. 기간당 한 번. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update AiUsageMonthEntity m
           set m.exceededAt = :now, m.updatedAt = :now, m.version = m.version + 1
         where m.period = :period and m.exceededAt is null and m.reservedMicros >= :limitMicros
        """)
    int markExceededAtLimit(String period, long limitMicros, Instant now);

    /** 예약 거절 — 지금 이 예상치를 넣으면 한도를 넘고 아직 초과를 표시하지 않았을 때만 초과 시각을 세운다. 기간당 한 번. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update AiUsageMonthEntity m
           set m.exceededAt = :now, m.updatedAt = :now, m.version = m.version + 1
         where m.period = :period and m.exceededAt is null and m.reservedMicros + :estimateMicros > :limitMicros
        """)
    int markExceededByReserve(String period, long estimateMicros, long limitMicros, Instant now);
}
