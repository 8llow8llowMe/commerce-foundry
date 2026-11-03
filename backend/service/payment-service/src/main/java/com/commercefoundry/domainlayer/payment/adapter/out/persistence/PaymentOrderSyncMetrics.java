package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결제의 주문 회신 지표 (#308 PR-C, {@code payment-guide.md} §6). 게이지는 읽힐 때 DB 를 한 번씩 조회한다 — 스크랩 주기마다 두 문장(각 2초 상한)이다.
 * <ul>
 *   <li>{@code commerce.payment.order-sync.pending.count} — 승인 뒤 {@code payment.order-result.pending-after}(기본 5분)가 지나도록 회신을 받지 않은 DONE · PARTIAL_CANCELED 결제.
 *       전액 취소된 결제는 order 가 회신하지 않는 것이 정상이라 세지 않는다. 릴레이 · 컨슈머가 밀리거나 회신 컨슈머 게이트가 닫혀 있으면 오른다.</li>
 *   <li>{@code commerce.payment.order-sync.rejected.count} — 주문이 거절했는데 전액 취소되지 않은 결제. 0 이 아니면 재대조(#591) · 자동 취소(#579) 전까지 운영자가 취소한다.</li>
 * </ul>
 * DB 예외는 관측 실패 카운터 {@code commerce.payment.order-sync.observation.failures} + NaN 으로 바꾼다 — Micrometer 가 원문 예외를 로그에 남기지 않게.
 * NaN 을 0(정상)으로 보지 않는다.
 */
@Slf4j
@Component
public class PaymentOrderSyncMetrics implements MeterBinder {

    public static final String PENDING = "commerce.payment.order-sync.pending.count";
    public static final String REJECTED = "commerce.payment.order-sync.rejected.count";
    public static final String OBSERVATION_FAILURES = "commerce.payment.order-sync.observation.failures";
    private static final List<PaymentStatus> HOLDING_MONEY = List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final Duration pendingAfter;

    public PaymentOrderSyncMetrics(
        PaymentRepository paymentRepository, Clock clock, @Value("${payment.order-result.pending-after:5m}") Duration pendingAfter
    ) {
        if (pendingAfter.isNegative()) {
            throw new IllegalArgumentException("payment.order-result.pending-after 는 0 이상이어야 합니다");
        }
        this.paymentRepository = paymentRepository;
        this.clock = clock;
        this.pendingAfter = pendingAfter;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING, this, metrics -> metrics.observe(registry,
                () -> paymentRepository.countAwaitingOrderReply(HOLDING_MONEY, clock.instant().minus(pendingAfter))))
            .description("승인 뒤 일정 시간이 지나도록 주문 회신을 받지 않은 결제 수 (전액 취소 제외)")
            .register(registry);
        Gauge.builder(REJECTED, this, metrics -> metrics.observe(registry, () -> paymentRepository.countOrderRejected(HOLDING_MONEY)))
            .description("주문이 거절했는데 전액 취소되지 않은 결제 수")
            .register(registry);
    }

    private double observe(MeterRegistry registry, LongSupplier count) {
        try {
            return count.getAsLong();
        } catch (RuntimeException exception) {
            registry.counter(OBSERVATION_FAILURES).increment();
            log.debug("payment order sync metric unavailable. error={}", exception.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
