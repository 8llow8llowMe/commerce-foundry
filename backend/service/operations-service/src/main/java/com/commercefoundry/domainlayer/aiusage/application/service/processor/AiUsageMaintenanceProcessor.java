package com.commercefoundry.domainlayer.aiusage.application.service.processor;

import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageMonthRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageCallRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageCall;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import com.commercefoundry.global.properties.AiUsageProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원장 정리 — 결과가 오지 않은 예약의 회수(ABANDONED)와 오래된 호출 행 삭제 (#215 PR-B). */
@Component
@RequiredArgsConstructor
public class AiUsageMaintenanceProcessor {

    private final AiUsageCallRepositoryPort aiUsageCallRepositoryPort;
    private final AiUsageMonthRepositoryPort aiUsageMonthRepositoryPort;
    private final AiUsageProperties aiUsageProperties;

    /**
     * 멈춘 PENDING 한 배치를 닫는다. 호출자의 트랜잭션 안에서 돈다.
     *
     * <p>건마다 "아직 PENDING 일 때만" 조건부 UPDATE 다 — 같은 순간 도착한 정산 · 다른 인스턴스의 회수와 겹쳐도 한 쪽만 닫는다. 그 영향 행 수가 곧 "내가 닫았다" 의
     * 증거라 in 절 한 문장으로 묶지 않는다(묶으면 어느 행을 닫았는지 몰라 월 카운터를 얼마 옮길지 정할 수 없다). 멈춘 예약은 예외 상황이라 배치가 작다.
     * 월 카운터는 월별로 합쳐 한 번씩 옮긴다 — 월 이름 순서로 잡아 인스턴스 간 교착을 피한다. 예상치를 그대로 비용으로 남기므로 예약 누계는 그대로다.
     *
     * @return 닫은 건수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int abandonStale(Instant now) {
        AiUsageProperties.ReservationSweep sweep = aiUsageProperties.reservationSweep();
        Instant calledBefore = now.minus(sweep.staleAfter());
        List<AiUsageCall> stale = aiUsageCallRepositoryPort.findPendingCalledBefore(calledBefore, sweep.batchSize());

        Map<String, long[]> estimateAndCallsByPeriod = new TreeMap<>();
        int abandoned = 0;
        for (AiUsageCall call : stale) {
            AiUsageCall closed = call.abandon(now);
            if (!aiUsageCallRepositoryPort.closeIfPending(closed)) {
                continue;
            }
            abandoned++;
            long[] sum = estimateAndCallsByPeriod.computeIfAbsent(closed.period().value(), period -> new long[2]);
            sum[0] = Math.addExact(sum[0], closed.costMicros());
            sum[1]++;
        }
        estimateAndCallsByPeriod.forEach((period, sum) -> aiUsageMonthRepositoryPort.settle(
            period, sum[0], sum[0], Math.toIntExact(AiUsageStatus.ABANDONED.countsAsCall() ? sum[1] : 0), now
        ));
        return abandoned;
    }

    /**
     * 보존 기간이 지난 닫힌 호출 행 한 배치를 지운다. 배치마다 트랜잭션이다 — 오래 쌓인 첫 정리가 큰 트랜잭션 하나가 되지 않게.
     *
     * @return 지운 건수 — 배치 크기보다 작으면 더 지울 것이 없다
     */
    @Transactional
    public int deleteExpiredBatch(Instant now) {
        AiUsageProperties.Retention retention = aiUsageProperties.retention();
        return aiUsageCallRepositoryPort.deleteClosedCalledBefore(now.minus(Duration.ofDays(retention.days())), retention.batchSize());
    }
}
