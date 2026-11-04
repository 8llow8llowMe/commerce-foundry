package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 회신 관측. 보호 WIP 4dae63ad의 관측 hunk를 기반으로 strict cutoff·최대 나이·실패 방어를 보완했다.
 * 각 Gauge는 독립 DB 조회이며 원자 snapshot이나 업무 상태 판단 근거가 아니다. 이전 값을 캐시하지 않는다.
 * 질의별 timeout은 풀 대기나 전체 scrape의 2초 상한을 보장하지 않는다.
 */
@Component
public class PaymentOrderSyncMetrics implements MeterBinder {

    public static final String PENDING = "commerce.payment.order-sync.pending.count";
    public static final String OLDEST_AGE = "commerce.payment.order-sync.pending.oldest.age";
    public static final String REJECTED = "commerce.payment.order-sync.rejected.count";
    public static final String OBSERVATION_FAILURES = "commerce.payment.order-sync.observation.failures";
    private static final List<PaymentStatus> HOLDING_MONEY = List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

    private final PaymentRepository repository;
    private final Clock clock;
    private final Duration pendingAfter;

    public PaymentOrderSyncMetrics(PaymentRepository repository, Clock clock, @Value("${payment.order-result.pending-after:5m}") Duration pendingAfter) {
        if (pendingAfter.isNegative()) {
            throw new IllegalArgumentException("payment.order-result.pending-after 는 0 이상이어야 합니다");
        }
        this.repository = repository;
        this.clock = clock;
        this.pendingAfter = pendingAfter;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Counter failures = registry.counter(OBSERVATION_FAILURES);
        Gauge.builder(PENDING, this, metrics -> metrics.observe(failures,
                now -> count(repository.countAwaitingOrderReply(HOLDING_MONEY, now.minus(pendingAfter)))))
            .description("승인 후 기준 시간을 초과한 주문 미회신 결제 수")
            .register(registry);
        Gauge.builder(OLDEST_AGE, this, metrics -> metrics.observe(failures, metrics::oldestAge))
            .description("지연 미회신 결제의 승인 이후 최대 경과 초")
            .baseUnit("seconds")
            .register(registry);
        Gauge.builder(REJECTED, this, metrics -> metrics.observe(failures, now -> count(repository.countOrderRejected(HOLDING_MONEY))))
            .description("주문 거절 후 전액 취소되지 않은 결제 수")
            .register(registry);
    }

    private double oldestAge(Instant observedAt) {
        Instant cutoff = observedAt.minus(pendingAfter);
        Instant oldest = repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING_MONEY, cutoff);
        if (oldest == null) return 0;
        if (!oldest.isBefore(cutoff)) throw new IllegalStateException("Invalid observation result");
        Duration age = Duration.between(oldest, observedAt);
        return age.getSeconds() + age.getNano() / 1_000_000_000.0;
    }

    private static double count(long value) {
        if (value < 0) throw new IllegalStateException("Invalid observation result");
        return value;
    }

    private double observe(Counter failures, ToDoubleFunction<Instant> observation) {
        try {
            return observation.applyAsDouble(clock.instant());
        } catch (RuntimeException exception) {
            // Micrometer까지 원문/cause를 전파하면 WARN에 SQL·개인정보가 노출될 수 있다.
            failures.increment();
            return Double.NaN;
        }
    }
}
