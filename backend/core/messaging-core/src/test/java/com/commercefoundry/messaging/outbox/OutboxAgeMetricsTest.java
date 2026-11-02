package com.commercefoundry.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OutboxAgeMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String GAUGE = "commerce.messaging.outbox.unpublished.oldest.age";
    private static final String FAILURES = GAUGE + ".observation.failed.total";

    @Test
    @DisplayName("등록된 Micrometer 게이지의 DB 조회 실패는 원문 로그 없이 NaN/실패 카운터가 되고 복구 뒤 정상 나이를 낸다")
    void registeredGaugeDoesNotLogOriginalFailure() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            AtomicReference<Supplier<Instant>> query = new AtomicReference<>(() -> {
                throw new IllegalStateException("PRIVATE METRIC MARKER");
            });
            Gauge.builder(GAUGE, query, source -> OutboxAgeMetrics.oldestAgeSeconds(source.get(), CLOCK, registry)).register(registry);

            assertThat(registry.get(GAUGE).gauge().value()).isNaN();
            assertThat(registry.get(GAUGE).gauge().value()).isNaN();
            assertThat(registry.get(FAILURES).counter().count()).isEqualTo(2);
            query.set(() -> NOW.minusMillis(1250));
            assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(1.25);
            assertThat(registry.get(FAILURES).counter().count()).isEqualTo(2);
            // DefaultGauge 의 WARN/DEBUG 도 root 에 전달된다. 예외를 넘기지 않아 본문/throwable 모두 남지 않는다.
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE METRIC MARKER", "Failed to apply the value function");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            registry.close();
            root.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    @DisplayName("미발행 없음/미래 예약은 정상값 0이며 관측 실패로 세지 않는다")
    void emptyOrFutureRowsAreHealthyZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            assertThat(OutboxAgeMetrics.oldestAgeSeconds(() -> null, CLOCK, registry)).isZero();
            assertThat(OutboxAgeMetrics.oldestAgeSeconds(() -> NOW.plusSeconds(1), CLOCK, registry)).isZero();
            assertThat(registry.find(FAILURES).counter()).isNull();
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("실패 카운터 자체가 실패해도 Micrometer 값 공급자 밖으로 예외를 내지 않는다")
    void registryFailureAlsoReturnsUnknown() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.counter(anyString())).thenThrow(new IllegalStateException("PRIVATE REGISTRY MARKER"));
        assertThat(OutboxAgeMetrics.oldestAgeSeconds(() -> {
            throw new IllegalStateException("PRIVATE QUERY MARKER");
        }, CLOCK, registry)).isNaN();
    }
}
