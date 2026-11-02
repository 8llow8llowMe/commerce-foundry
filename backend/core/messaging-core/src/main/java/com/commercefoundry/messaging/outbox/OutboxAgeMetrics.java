package com.commercefoundry.messaging.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 미발행 나이 게이지의 안전한 값 공급자. DB 예외를 Micrometer 에 넘기면 DefaultGauge 가 원문 throwable 을 로그에 남긴다.
 * 예외는 계량하고 NaN(관측 불가)으로 바꾼다 — 정상값 0(미발행 없음/미래 예약)과 구분하며 예외 메시지 · 스택은 남기지 않는다.
 */
public final class OutboxAgeMetrics {

    private OutboxAgeMetrics() {
    }

    public static double oldestAgeSeconds(Supplier<Instant> oldestAvailableAt, Clock clock, MeterRegistry registry) {
        try {
            Instant oldest = oldestAvailableAt.get();
            if (oldest == null) {
                return 0;
            }
            Duration age = Duration.between(oldest, clock.instant());
            return age.isNegative() ? 0 : age.toMillis() / 1000.0;
        } catch (RuntimeException exception) {
            try {
                registry.counter("commerce.messaging.outbox.unpublished.oldest.age.observation.failed.total").increment();
            } catch (RuntimeException metricFailure) {
                // 레지스트리 기록 실패도 공급자 밖으로 던지지 않는다 — 관측 실패가 원문 로그/발행 실패로 이어지지 않게 한다.
            }
            return Double.NaN;
        }
    }
}
