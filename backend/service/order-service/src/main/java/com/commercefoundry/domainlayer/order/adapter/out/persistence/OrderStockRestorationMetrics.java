package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStockRestorationRepository;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 반영 재고 복원 작업 지표 (#582, {@code order-flow-guide.md} §3-6) — 복원 대기 · 사람 확인(HELD) 행 수와 가장 오래된 대기 작업의 나이(초).
 * 스윕이 꺼졌거나 product 장애가 길면 대기 수 · 나이가 함께 오르고, HELD 는 0 이 아니면 사람이 볼 대상이다.
 * 게이지는 읽힐 때 DB 를 한 번씩 조회한다. 조회 실패는 원문 예외를 Micrometer 로 넘기지 않고 NaN(관측 불가)과 실패 카운터로 바꾼다 — 정상 0 과 구분한다.
 */
@Component
@RequiredArgsConstructor
public class OrderStockRestorationMetrics implements MeterBinder {

    static final String PENDING_COUNT = "commerce.order.payment-restoration.pending.count";
    static final String HELD_COUNT = "commerce.order.payment-restoration.held.count";
    static final String PENDING_OLDEST_AGE = "commerce.order.payment-restoration.pending.oldest.age";
    static final String OBSERVATION_FAILED = "commerce.order.payment-restoration.observation.failed.total";

    private final OrderStockRestorationRepository orderStockRestorationRepository;
    private final Clock clock;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING_COUNT, this, metrics -> metrics.count(registry, repository -> repository.countByStatus(Status.RESTORE_PENDING)))
            .description("재고 복원을 기다리는 결제 반영 작업 수")
            .register(registry);
        Gauge.builder(HELD_COUNT, this, metrics -> metrics.count(registry, repository -> repository.countByStatus(Status.HELD)))
            .description("사람 확인으로 멈춘 결제 반영 재고 복원 작업 수")
            .register(registry);
        Gauge.builder(PENDING_OLDEST_AGE, this, metrics -> metrics.oldestPendingAgeSeconds(registry))
            .description("가장 오래된 복원 대기 작업이 등록된 뒤 지난 시간")
            .baseUnit("seconds")
            .register(registry);
    }

    private double count(MeterRegistry registry, ToLongFunction<OrderStockRestorationRepository> query) {
        try {
            return query.applyAsLong(orderStockRestorationRepository);
        } catch (RuntimeException exception) {
            return observationFailed(registry);
        }
    }

    private double oldestPendingAgeSeconds(MeterRegistry registry) {
        try {
            Instant oldest = orderStockRestorationRepository.findOldestCreatedAt(Status.RESTORE_PENDING);
            if (oldest == null) {
                return 0;
            }
            Duration age = Duration.between(oldest, clock.instant());
            return age.isNegative() ? 0 : age.toMillis() / 1000.0;
        } catch (RuntimeException exception) {
            return observationFailed(registry);
        }
    }

    private static double observationFailed(MeterRegistry registry) {
        try {
            registry.counter(OBSERVATION_FAILED).increment();
        } catch (RuntimeException metricFailure) {
            // 레지스트리 기록 실패도 공급자 밖으로 던지지 않는다.
        }
        return Double.NaN;
    }
}
