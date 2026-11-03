package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/** 회신 지표의 관측 실패 경로 — DB 예외는 NaN + 관측 실패 카운터이고 원문 예외가 게이지 밖으로 새지 않는다 (#308 PR-C). */
class PaymentOrderSyncMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentOrderSyncMetrics metrics = new PaymentOrderSyncMetrics(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test
    void databaseFailureBecomesNaNAndObservationFailureCount() {
        when(repository.countAwaitingOrderReply(anyCollection(), any())).thenThrow(new QueryTimeoutException("DB_PRIVATE_SENTINEL"));
        when(repository.countOrderRejected(anyCollection())).thenThrow(new QueryTimeoutException("DB_PRIVATE_SENTINEL"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        assertThat(registry.get(PaymentOrderSyncMetrics.PENDING).gauge().value()).isNaN();
        assertThat(registry.get(PaymentOrderSyncMetrics.REJECTED).gauge().value()).isNaN();
        assertThat(registry.get(PaymentOrderSyncMetrics.OBSERVATION_FAILURES).counter().count()).isEqualTo(2.0);
    }

    @Test
    void pendingCountsOnlyMoneyHoldingStatusesOlderThanThreshold() {
        when(repository.countAwaitingOrderReply(anyCollection(), any())).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        assertThat(registry.get(PaymentOrderSyncMetrics.PENDING).gauge().value()).isEqualTo(3.0);
        verify(repository).countAwaitingOrderReply(List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED), NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void negativeThresholdIsRejected() {
        assertThatThrownBy(() -> new PaymentOrderSyncMetrics(repository, Clock.systemUTC(), Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
