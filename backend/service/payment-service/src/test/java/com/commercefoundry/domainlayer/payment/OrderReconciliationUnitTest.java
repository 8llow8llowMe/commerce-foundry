package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.OrderReconciliationScheduler;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderReconciliationSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.service.OrderReconciliationSchedulerFacade;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.commercefoundry.domainlayer.payment.application.service.OrderPaymentReplyObservation;
import com.commercefoundry.domainlayer.payment.application.service.OrderReconciliationObservation;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderReconciliationScanProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OrderReconciliationUnitTest {
    @Test
    void stableVersionedSyntheticKeyUsesUtf8AndPaymentIdentity() {
        assertThat(OrderReconciliationSchedulerFacade.syntheticEventId(42))
            .isEqualTo(UUID.nameUUIDFromBytes("payment-order-reconcile:v1:42".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(OrderReconciliationSchedulerFacade.syntheticEventId(42))
            .isNotEqualTo(OrderReconciliationSchedulerFacade.syntheticEventId(43));
    }

    @Test
    void defaultsOffAndRejectsUnboundedOrNegativeConfiguration() {
        var defaults = props(false);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.pageSize()).isEqualTo(10);
        assertThat(defaults.maxPagesPerLane()).isEqualTo(1);
        assertThatThrownBy(() -> new PaymentOrderReconciliationProperties(false, Duration.ZERO, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentOrderReconciliationProperties(false, null, null, Duration.ofSeconds(-1), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        for (int limit : new int[]{0, 101}) {
            assertThatThrownBy(() -> new PaymentOrderReconciliationProperties(false, null, null, null, limit, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void defaultOffAndContendedLockNeverInvokeUseCaseAndFailingTickReleasesLock(CapturedOutput output) {
        var useCase = mock(OrderReconciliationSchedulerUseCase.class);
        var locks = mock(LockProvider.class);
        new OrderReconciliationScheduler(useCase, locks, props(false), Clock.systemUTC()).reconcile();
        verifyNoInteractions(locks, useCase);
        when(locks.lock(any())).thenReturn(Optional.empty());
        var scheduler = new OrderReconciliationScheduler(useCase, locks, props(true), Clock.systemUTC());
        scheduler.reconcile();
        verifyNoInteractions(useCase);
        var lock = mock(SimpleLock.class);
        when(locks.lock(any())).thenReturn(Optional.of(lock));
        doThrow(new IllegalStateException("private-payment-9000000001", new IllegalArgumentException("private-cause"))).when(useCase).reconcile();
        scheduler.reconcile();
        verify(lock).unlock();
        var configuration = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(locks, times(2)).lock(configuration.capture());
        assertThat(configuration.getValue().getName()).isEqualTo("payment-order-reconciliation");
        assertThat(output.getAll()).contains("Order reconciliation scheduler: TICK_FAILED").doesNotContain("private-payment", "9000000001", "private-cause");
    }

    @Test
    void existingPendingThresholdAndLaneBudgetsRemainIndependentWhenFirstLaneFails() {
        var scan = mock(OrderReconciliationScanProcessor.class);
        var replies = mock(OrderPaymentReplyProcessor.class);
        var failures = mock(OrderPaymentReplyObservation.class);
        var observation = mock(OrderReconciliationObservation.class);
        var now = Instant.parse("2026-11-07T00:00:00Z");
        var cutoff = now.minus(Duration.ofMinutes(5));
        when(scan.reserveBatch(OrderReconciliationLane.RECONCILE, cutoff, 10)).thenThrow(new IllegalStateException("private-source"));
        when(scan.reserveBatch(OrderReconciliationLane.REJECTED, cutoff, 10)).thenReturn(List.of(42L));
        var facade = new OrderReconciliationSchedulerFacade(scan, replies, props(true), Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5), failures, observation);
        facade.reconcile();
        verify(scan).reserveBatch(OrderReconciliationLane.RECONCILE, cutoff, 10);
        verify(scan).reserveBatch(OrderReconciliationLane.REJECTED, cutoff, 10);
        verify(replies).afterRejection(42);
        verifyNoInteractions(observation);
        clearInvocations(scan, replies);
        new OrderReconciliationSchedulerFacade(scan, replies, props(true), Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO, failures, observation).reconcile();
        verify(scan).reserveBatch(OrderReconciliationLane.RECONCILE, now, 10);
        assertThatThrownBy(() -> new OrderReconciliationSchedulerFacade(scan, replies, props(false), Clock.systemUTC(), Duration.ofSeconds(-1), failures, observation))
            .isInstanceOf(IllegalArgumentException.class);
        clearInvocations(scan, replies);
        new OrderReconciliationSchedulerFacade(scan, replies, props(false), Clock.systemUTC(), Duration.ZERO, failures, observation).reconcile();
        verifyNoInteractions(scan, replies);
    }

    static PaymentOrderReconciliationProperties props(boolean enabled) {
        return new PaymentOrderReconciliationProperties(enabled, null, null, null, null, null);
    }
}
