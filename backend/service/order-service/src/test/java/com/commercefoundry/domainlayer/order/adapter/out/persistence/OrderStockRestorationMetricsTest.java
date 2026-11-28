package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStockRestorationRepository;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OrderStockRestorationMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-29T03:00:00Z");
    private final OrderStockRestorationRepository repository = mock(OrderStockRestorationRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    OrderStockRestorationMetricsTest() {
        new OrderStockRestorationMetrics(repository, Clock.fixed(NOW, ZoneOffset.UTC)).bindTo(registry);
    }

    @Test
    void reportsPendingHeldAndOldestPendingAge() {
        when(repository.countByStatus(Status.RESTORE_PENDING)).thenReturn(4L);
        when(repository.countByStatus(Status.HELD)).thenReturn(1L);
        when(repository.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(NOW.minus(Duration.ofSeconds(90)));

        assertThat(gauge(OrderStockRestorationMetrics.PENDING_COUNT)).isEqualTo(4);
        assertThat(gauge(OrderStockRestorationMetrics.HELD_COUNT)).isEqualTo(1);
        assertThat(gauge(OrderStockRestorationMetrics.PENDING_OLDEST_AGE)).isEqualTo(90);
    }

    @Test
    void noPendingOrFutureCreationIsZeroAge() {
        when(repository.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(null);
        assertThat(gauge(OrderStockRestorationMetrics.PENDING_OLDEST_AGE)).isZero();
        when(repository.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(NOW.plusSeconds(5));
        assertThat(gauge(OrderStockRestorationMetrics.PENDING_OLDEST_AGE)).isZero();
    }

    @Test
    void queryFailureIsNaNAndCountedNotZero() {
        when(repository.countByStatus(Status.RESTORE_PENDING)).thenThrow(new IllegalStateException("db down"));
        when(repository.findOldestCreatedAt(Status.RESTORE_PENDING)).thenThrow(new IllegalStateException("db down"));

        assertThat(gauge(OrderStockRestorationMetrics.PENDING_COUNT)).isNaN();
        assertThat(gauge(OrderStockRestorationMetrics.PENDING_OLDEST_AGE)).isNaN();
        assertThat(registry.get(OrderStockRestorationMetrics.OBSERVATION_FAILED).counter().count()).isEqualTo(2);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
