package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentOrderSyncMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-04T01:02:03.123456Z");
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final Clock clock = mock(Clock.class);

    private SimpleMeterRegistry registry(Duration threshold) {
        when(clock.instant()).thenReturn(NOW);
        var registry = new SimpleMeterRegistry();
        new PaymentOrderSyncMetrics(repository, clock, threshold).bindTo(registry);
        return registry;
    }

    @Test
    void wholeAgeEmptyAndOldestRemovalAreNotExcessOverThreshold() {
        var registry = registry(Duration.ofMinutes(5));
        try {
            assertThat(registry.get(PaymentOrderSyncMetrics.OLDEST_AGE).gauge().getId().getBaseUnit()).isEqualTo("seconds");
            assertThat(value(registry, PaymentOrderSyncMetrics.PENDING)).isZero();
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isZero();
            assertThat(value(registry, PaymentOrderSyncMetrics.REJECTED)).isZero();
            when(repository.findOldestAwaitingOrderReplyApprovedAt(any(), any())).thenReturn(NOW.minusSeconds(900), NOW.minusSeconds(600), null);
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isEqualTo(900);
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isEqualTo(600);
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isZero();
            assertThat(failures(registry)).isZero();
        } finally {
            registry.close();
        }
    }

    @Test
    void eachCallbackReadsClockOnceAndCanObserveDifferentSnapshots() {
        var registry = registry(Duration.ofMinutes(5));
        try {
            when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(10), NOW.plusSeconds(20));
            clearInvocations(clock);
            assertThat(value(registry, PaymentOrderSyncMetrics.PENDING)).isZero();
            verify(clock).instant();
            verify(repository).countAwaitingOrderReply(any(), eq(NOW.minusSeconds(300)));
            clearInvocations(clock);
            when(repository.findOldestAwaitingOrderReplyApprovedAt(any(), any())).thenReturn(NOW.minusSeconds(600));
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isEqualTo(610);
            verify(clock).instant();
            verify(repository).findOldestAwaitingOrderReplyApprovedAt(any(), eq(NOW.minusSeconds(290)));
            clearInvocations(clock);
            value(registry, PaymentOrderSyncMetrics.REJECTED);
            verify(clock).instant();
        } finally {
            registry.close();
        }
    }

    @Test
    void threeFailuresCountThreeAndRecoverWithoutCachedValuesOrMicrometerWarnLeak() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        String sentinel = "sentinel-private-payment-key";
        var failure = new IllegalStateException(sentinel, new IllegalArgumentException(sentinel));
        var registry = registry(Duration.ZERO);
        try {
            root.warn("order-sync log capture control");
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).isEqualTo("order-sync log capture control"));
            appender.list.clear();
            when(repository.countAwaitingOrderReply(any(), any())).thenReturn(7L).thenThrow(failure).thenReturn(2L);
            when(repository.findOldestAwaitingOrderReplyApprovedAt(any(), any())).thenThrow(failure).thenReturn(NOW.minusSeconds(60));
            when(repository.countOrderRejected(any())).thenThrow(failure).thenReturn(1L);
            assertThat(value(registry, PaymentOrderSyncMetrics.PENDING)).isEqualTo(7);
            for (String name : new String[]{PaymentOrderSyncMetrics.PENDING, PaymentOrderSyncMetrics.OLDEST_AGE, PaymentOrderSyncMetrics.REJECTED}) {
                assertThat(value(registry, name)).isNaN();
            }
            assertThat(failures(registry)).isEqualTo(3);
            assertThat(value(registry, PaymentOrderSyncMetrics.PENDING)).isEqualTo(2);
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isEqualTo(60);
            assertThat(value(registry, PaymentOrderSyncMetrics.REJECTED)).isEqualTo(1);
            assertThat(failures(registry)).isEqualTo(3);
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(sentinel);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list).noneMatch(event -> event.getLoggerName().startsWith("io.micrometer")
                && event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
        } finally {
            root.detachAppender(appender);
            appender.stop();
            registry.close();
        }
    }

    @Test
    void oneFailedGaugeDoesNotPreventOtherCallbacks() {
        var registry = registry(Duration.ZERO);
        try {
            when(repository.countAwaitingOrderReply(any(), any())).thenThrow(new IllegalStateException("private"));
            when(repository.countOrderRejected(any())).thenReturn(4L);
            assertThat(value(registry, PaymentOrderSyncMetrics.PENDING)).isNaN();
            assertThat(value(registry, PaymentOrderSyncMetrics.REJECTED)).isEqualTo(4);
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isZero();
            assertThat(failures(registry)).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 300, 301})
    void returnedMinimumOutsideStrictPredicateIsFailure(long secondsAgo) {
        var registry = registry(Duration.ofMinutes(5));
        try {
            // 301초 전은 유효하며 경계/미래/최근은 불가능한 반환값이다.
            when(repository.findOldestAwaitingOrderReplyApprovedAt(any(), any())).thenReturn(NOW.minusSeconds(secondsAgo));
            if (secondsAgo > 300) assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isEqualTo(secondsAgo);
            else assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isNaN();
        } finally {
            registry.close();
        }
    }

    @Test
    void negativeCountsAndFutureMinimumAreFailures() {
        var registry = registry(Duration.ZERO);
        try {
            when(repository.countAwaitingOrderReply(any(), any())).thenReturn(-1L);
            when(repository.countOrderRejected(any())).thenReturn(-1L);
            when(repository.findOldestAwaitingOrderReplyApprovedAt(any(), any())).thenReturn(NOW.plusNanos(1_000));
            assertThat(value(registry, PaymentOrderSyncMetrics.PENDING)).isNaN();
            assertThat(value(registry, PaymentOrderSyncMetrics.REJECTED)).isNaN();
            assertThat(value(registry, PaymentOrderSyncMetrics.OLDEST_AGE)).isNaN();
            assertThat(failures(registry)).isEqualTo(3);
        } finally {
            registry.close();
        }
    }

    @Test
    void defaultAndZeroBindButNegativeThresholdRejectsStartup() {
        var runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withBean(PaymentRepository.class, () -> repository).withBean(Clock.class, () -> Clock.fixed(NOW, java.time.ZoneOffset.UTC))
            .withBean(PaymentOrderSyncMetrics.class);
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(PaymentOrderSyncMetrics.class));
        runner.withPropertyValues("payment.order-result.pending-after=0s").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("payment.order-result.pending-after=-1s").run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new PaymentOrderSyncMetrics(repository, clock, Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    private static double value(SimpleMeterRegistry registry, String name) { return registry.get(name).gauge().value(); }
    private static double failures(SimpleMeterRegistry registry) { return registry.get(PaymentOrderSyncMetrics.OBSERVATION_FAILURES).counter().count(); }
}
