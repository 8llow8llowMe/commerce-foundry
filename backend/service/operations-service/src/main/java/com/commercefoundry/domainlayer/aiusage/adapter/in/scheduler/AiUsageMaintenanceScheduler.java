package com.commercefoundry.domainlayer.aiusage.adapter.in.scheduler;

import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMaintenanceUseCase;
import com.commercefoundry.global.support.WarnThrottle;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 사용량 원장 정리 트리거 (#215 B3 · #435). 주기 · 배치는 {@code operations.ai.reservation-sweep.*} · {@code operations.ai.retention.*} 다.
 *
 * <ul>
 *   <li>예약 회수: 결과(정산)가 끝내 오지 않은 PENDING 예약(프로세스가 죽었거나 정산 기록이 실패했다)을 ABANDONED 로 닫고 예상치를 비용으로 남긴다 —
 *       닫지 않으면 그 예상치만큼 월 한도를 영원히 쥔다.</li>
 *   <li>보존 정리: 보존 기간이 지난 닫힌 호출 행을 지운다. 월 카운터는 남는다.</li>
 * </ul>
 *
 * <p>{@code ai.enabled=false} 여도 돈다 — 끄기 전에 남은 PENDING 이 한도를 쥐지 않게, 보존 기간은 AI 를 켜고 끄는 것과 관계없이 지키게. 후보가 없으면 조회 한 번이다.
 *
 * <p>인스턴스가 여럿이어도 분산 락을 두지 않는다 — 회수는 건마다 "아직 PENDING 일 때만" 조건부 UPDATE 라 한 쪽만 닫고, 삭제는 겹쳐도 한 번만 없어진다
 * ({@link AiUsageMaintenanceUseCase}). 실패는 다음 주기가 다시 한다 — 주기마다 쌓지 않고 간격당 한 번만 WARN(예외 타입만)이다.
 */
@Slf4j
@Component
public class AiUsageMaintenanceScheduler {

    private final AiUsageMaintenanceUseCase aiUsageMaintenanceUseCase;
    private final WarnThrottle sweepWarnings;
    private final WarnThrottle retentionWarnings;

    public AiUsageMaintenanceScheduler(
        AiUsageMaintenanceUseCase aiUsageMaintenanceUseCase,
        @Value("${operations.ai.reservation-sweep.fixed-delay}") Duration sweepDelay,
        @Value("${operations.ai.retention.fixed-delay}") Duration retentionDelay
    ) {
        this.aiUsageMaintenanceUseCase = aiUsageMaintenanceUseCase;
        this.sweepWarnings = new WarnThrottle(sweepDelay);
        this.retentionWarnings = new WarnThrottle(retentionDelay);
    }

    @Scheduled(
        initialDelayString = "${operations.ai.reservation-sweep.initial-delay}",
        fixedDelayString = "${operations.ai.reservation-sweep.fixed-delay}"
    )
    public void abandonStale() {
        try {
            int abandoned = aiUsageMaintenanceUseCase.abandonStale();
            if (abandoned > 0) {
                // 정산이 오지 않은 호출이 있었다 — 프로세스 종료 · 원장 기록 실패의 신호라 INFO 가 아니라 WARN 이다
                log.warn("ai usage stale reservations abandoned. count={}", abandoned);
            }
        } catch (RuntimeException exception) {
            warn(sweepWarnings, "ai usage reservation sweep failed, will retry.", exception);
        }
    }

    @Scheduled(
        initialDelayString = "${operations.ai.retention.initial-delay}",
        fixedDelayString = "${operations.ai.retention.fixed-delay}"
    )
    public void deleteExpired() {
        try {
            int deleted = aiUsageMaintenanceUseCase.deleteExpired();
            if (deleted > 0) {
                log.info("ai usage expired calls deleted. count={}", deleted);
            }
        } catch (RuntimeException exception) {
            warn(retentionWarnings, "ai usage retention cleanup failed, will retry.", exception);
        }
    }

    /** 예외 메시지 · 스택은 싣지 않는다 — 원인 사슬에 SQL · 값이 실릴 수 있다. */
    private static void warn(WarnThrottle throttle, String message, RuntimeException exception) {
        long suppressed = throttle.tryAcquire(exception.getClass().getName());
        if (suppressed >= 0) {
            log.warn("{} error={}, suppressedSinceLastWarn={}", message, exception.getClass().getName(), suppressed);
        }
    }
}
