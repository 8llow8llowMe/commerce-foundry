package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.PaymentAutoCancelScheduler;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelExecutionProcessor;
import com.commercefoundry.domainlayer.payment.application.service.PaymentAutoCancelSchedulerFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.AutoCancelScanProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PaymentAutoCancelSchedulerUnitTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    static PaymentAutoCancelSchedulerProperties props(boolean enabled) {
        return new PaymentAutoCancelSchedulerProperties(enabled, null, null, null, null, null);
    }
    @Test
    void defaultOffAndBoundedConfiguration() {
        assertThat(props(false).enabled()).isFalse();
        assertThat(props(false).pageSize()).isEqualTo(5);
        assertThat(props(false).maxPagesPerLane()).isEqualTo(1);
        assertThat(props(false).lockAtMostFor()).isEqualTo(Duration.ofMinutes(15));
        assertThatThrownBy(() -> new PaymentAutoCancelSchedulerProperties(true, null, null, Duration.ofSeconds(624), 5, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PaymentAutoCancelSchedulerProperties(true, null, null, Duration.ofSeconds(625), 5, 1).pageSize()).isEqualTo(5);
        assertThat(new PaymentAutoCancelSchedulerProperties(true, null, null, Duration.ofHours(4), 50, 2).maxPagesPerLane()).isEqualTo(2);
        for (int size : new int[]{0, 51}) {
            assertThatThrownBy(() -> new PaymentAutoCancelSchedulerProperties(true, null, null, Duration.ofHours(4), size, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PaymentAutoCancelSchedulerProperties(true, Duration.ZERO, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAutoCancelSchedulerProperties(true, null, null, Duration.ofSeconds(1), 5, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAutoCancelSchedulerProperties(true, null, null, Duration.ofHours(4), 50, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void disabledSchedulerAndFacadeTouchNothing() {
        var useCase = mock(PaymentAutoCancelSchedulerUseCase.class);
        var locks = mock(LockProvider.class);
        new PaymentAutoCancelScheduler(useCase, locks, props(false), clock).sweep();
        var scan = mock(AutoCancelScanProcessor.class);
        var intents = mock(PaymentAutoCancelProcessor.class);
        var executor = mock(PaymentAutoCancelExecutionProcessor.class);
        new PaymentAutoCancelSchedulerFacade(scan, intents, executor, props(false), clock).sweep();
        verifyNoInteractions(useCase, locks, scan, intents, executor);
    }
    @Test
    void usesIndependentLockAndAlwaysReleasesEvenWhenTickFails(CapturedOutput output) {
        var useCase = mock(PaymentAutoCancelSchedulerUseCase.class);
        var locks = mock(LockProvider.class);
        var held = mock(SimpleLock.class);
        when(locks.lock(any())).thenReturn(Optional.empty(), Optional.of(held));
        var scheduler = new PaymentAutoCancelScheduler(useCase, locks, props(true), clock);
        scheduler.sweep();
        verifyNoInteractions(useCase);
        doThrow(new IllegalStateException("SENTINEL_PRIVATE")).when(useCase).sweep();
        scheduler.sweep();
        verify(held).unlock();
        var config = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(locks, times(2)).lock(config.capture());
        assertThat(config.getValue().getName()).isEqualTo("payment-auto-cancel");
        assertThat(output).doesNotContain("SENTINEL_PRIVATE");
    }
    @Test
    void laneAndCandidateFailureAreIsolatedAndEachLaneHasIndependentBudget(CapturedOutput output) {
        var scan = mock(AutoCancelScanProcessor.class);
        var intents = mock(PaymentAutoCancelProcessor.class);
        var executor = mock(PaymentAutoCancelExecutionProcessor.class);
        when(scan.reserveBatch(AutoCancelLane.REJECTED, clock.instant(), 5)).thenThrow(new IllegalStateException("SENTINEL_PRIVATE"));
        when(scan.reserveBatch(AutoCancelLane.LATE_APPROVED, clock.instant(), 5)).thenReturn(List.of(1L, 2L));
        when(scan.reserveBatch(AutoCancelLane.PENDING, clock.instant(), 5)).thenReturn(List.of(3L, 4L));
        doThrow(new IllegalStateException("SENTINEL_PRIVATE")).when(intents).enqueue(1);
        doThrow(new IllegalStateException("SENTINEL_PRIVATE")).when(executor).execute(3);
        new PaymentAutoCancelSchedulerFacade(scan, intents, executor, props(true), clock).sweep();
        verify(intents).enqueue(2);
        verify(executor).execute(4);
        for (var lane : AutoCancelLane.values()) verify(scan, times(1)).reserveBatch(lane, clock.instant(), 5);
        verifyNoMoreInteractions(scan);
        assertThat(output).doesNotContain("SENTINEL_PRIVATE");
    }
}
