package com.commercefoundry.global.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * outbox 지표 (messaging-core README) — 미발행 행 수와 가장 오래된 미발행 행의 나이(초). 릴레이가 멈췄거나 밀리면 둘이 함께 오른다.
 * 게이지는 읽힐 때 DB 를 한 번씩 조회한다 — 스크랩 주기마다 두 문장이다.
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outboxRepository;
    private final Clock clock;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("commerce.messaging.outbox.unpublished.count", outboxRepository, repository -> repository.countByPublishedAtIsNull())
            .description("발행되지 않은 outbox 행 수")
            .register(registry);
        Gauge.builder("commerce.messaging.outbox.unpublished.oldest.age", this, OutboxMetrics::oldestAgeSeconds)
            .description("가장 오래된 미발행 outbox 행이 발행 가능해진 뒤 지난 시간")
            .baseUnit("seconds")
            .register(registry);
    }

    private double oldestAgeSeconds() {
        Instant oldest = outboxRepository.findOldestUnpublishedAvailableAt();
        if (oldest == null) {
            return 0;
        }
        Duration age = Duration.between(oldest, clock.instant());
        return age.isNegative() ? 0 : age.toMillis() / 1000.0;
    }
}
