package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentRecoveryProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentRecoveryScanProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentRecoveryPropertiesTest {
    @Test
    void fullPagesAreBoundedAndNestedRunDoesNotOverlap() {
        var properties = new PaymentRecoveryProperties(true, null, null, null, null, null, null, 2, 2);
        var queries = mock(PaymentRecoveryQueryPort.class);
        var processor = mock(PaymentRecoveryProcessor.class);
        var gateway = mock(PaymentGatewayPort.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), java.time.ZoneOffset.UTC);
        var scan = new PaymentRecoveryScanProcessor(queries, processor, gateway, properties, clock);
        var candidates = List.of(new PaymentRecoveryQueryPort.Candidate(1, clock.instant().minusSeconds(3600)),
            new PaymentRecoveryQueryPort.Candidate(2, clock.instant().minusSeconds(3600)));
        when(queries.findCandidates(any(), any(), any(), anyInt())).thenAnswer(call -> {
            scan.recover(); // 실행 중 재진입은 같은 로컬 가드에서 끝난다.
            return candidates;
        });
        when(processor.prepare(anyLong(), any(), any())).thenReturn(Optional.empty());
        scan.recover();
        verify(queries, times(6)).findCandidates(any(), any(), any(), eq(2));
        verify(processor, times(12)).prepare(anyLong(), any(), any());
        verifyNoInteractions(gateway);
    }
    @Test
    void defaultsAreOffAndBoundedAndDisabledScanDoesNothing() {
        var properties = config(null, null, null);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.pageSize()).isEqualTo(10);
        assertThat(properties.maxPagesPerStatusPerTick()).isEqualTo(1);
        assertThat(properties.manualReviewAfter()).isEqualTo(Duration.ofHours(24));
        var queries = mock(PaymentRecoveryQueryPort.class);
        var processor = mock(PaymentRecoveryProcessor.class);
        var gateway = mock(PaymentGatewayPort.class);
        var clock = mock(Clock.class);
        new PaymentRecoveryScanProcessor(queries, processor, gateway, properties, clock).recover();
        verifyNoInteractions(queries, processor, gateway, clock);
    }

    @Test
    void rejectsInvalidDurationsAndPageBudgets() {
        for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(9)}) {
            assertThatThrownBy(() -> config(duration, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int size : new int[]{0, 101}) {
            assertThatThrownBy(() -> config(null, size, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int pages : new int[]{0, 11}) {
            assertThatThrownBy(() -> config(null, null, pages)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PaymentRecoveryProperties(false, Duration.ZERO, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private PaymentRecoveryProperties config(Duration manual, Integer size, Integer pages) {
        return new PaymentRecoveryProperties(false, null, null, null, null, null, manual, size, pages);
    }
}
