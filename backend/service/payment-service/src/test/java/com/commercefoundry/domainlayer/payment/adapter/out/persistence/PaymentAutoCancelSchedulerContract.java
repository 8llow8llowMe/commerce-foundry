package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.payment.adapter.in.scheduler.PaymentAutoCancelScheduler;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.AutoCancelCursorEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.AutoCancelCursorRepository;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancellationQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancellationQueryResult.State;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelExecutionProcessor;
import com.commercefoundry.domainlayer.payment.application.service.PaymentAutoCancelSchedulerFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.AutoCancelScanProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import({AutoCancelScanAdapter.class, AutoCancelScanProcessor.class, PaymentAutoCancelSchedulerFacade.class,
    PaymentAutoCancelSchedulerContract.SchedulerProperties.class})
abstract class PaymentAutoCancelSchedulerContract extends PaymentAutoCancelContract {
    @Autowired AutoCancelScanAdapter scan;
    @Autowired AutoCancelCursorRepository cursors;
    @Autowired PaymentAutoCancelSchedulerUseCase sweep;
    @Autowired javax.sql.DataSource source;

    @BeforeEach
    void seedSeparateCursors() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            cursors.deleteAllInBatch();
            for (var lane : AutoCancelLane.values()) cursors.save(new AutoCancelCursorEntity(lane));
        });
        ReflectionTestUtils.setField(AopTestUtils.<PaymentAutoCancelExecutionProcessor>getUltimateTargetObject(execution), "enabled", true);
    }

    Payment other(long offset, boolean late, boolean rejected) {
        String key = "synthetic-auto-key-" + offset;
        var inProgress = Payment.ready(PAYMENT_ID + offset, ORDER_ID + offset, MEMBER_ID, krw(129000), ORDER_NAME)
            .startApproval(key, UUID.randomUUID().toString(), STARTED_AT);
        var approved = late ? inProgress.reject("SYNTHETIC", "synthetic").recordLateApproval(approval(key, krw(129000)))
            : inProgress.approve(approval(key, krw(129000)));
        return rejected ? approved.markOrderSyncFailed("ORDER_CANCELED") : approved;
    }

    @Test
    void threeQueryPopulationsExcludeSyncedCanceledAndAlreadyKnownIntents() {
        save(done().markOrderSyncFailed("ORDER_CANCELED"));
        save(other(1, true, false));
        save(other(2, false, true).recordCancellation(krw(1000)));
        save(other(3, false, false));
        save(other(4, false, true).recordCancellation(krw(129000)));
        save(other(5, false, false).markOrderSynced());
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 5)).containsExactly(PAYMENT_ID, PAYMENT_ID + 2);
        assertThat(scan.reserveBatch(AutoCancelLane.LATE_APPROVED, clock.now, 5)).containsExactly(PAYMENT_ID + 1);
        assertThat(scan.reserveBatch(AutoCancelLane.PENDING, clock.now, 5)).isEmpty();
        processor.enqueue(PAYMENT_ID);
        // Finish the previous discovery cycle, then known metadata removes the row from subsequent discovery.
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 5)).isEmpty();
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 5)).containsExactly(PAYMENT_ID + 2);
        assertThat(scan.reserveBatch(AutoCancelLane.PENDING, clock.now, 5)).containsExactly(PAYMENT_ID);
    }

    @Test
    void fixedUpperAndCommittedProgressPreventPoisonAndNewRowsFromStarvingLaterCandidates() {
        save(done().markOrderSyncFailed("ORDER_CANCELED"));
        save(other(10, false, true));
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 1)).containsExactly(PAYMENT_ID);
        // Simulate a crash after reservation but before enqueue. The failed row cannot pin this cycle.
        save(other(20, false, true));
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 1)).containsExactly(PAYMENT_ID + 10);
        processor.enqueue(PAYMENT_ID + 10);
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 1)).isEmpty();
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 1)).containsExactly(PAYMENT_ID);
        processor.enqueue(PAYMENT_ID);
        assertThat(scan.reserveBatch(AutoCancelLane.REJECTED, clock.now, 1)).containsExactly(PAYMENT_ID + 20);
    }

    @Test
    void dueCutoffIsFrozenAndUnexpiredLeaseIsExcludedUntilNextCycle() {
        save(lateApproved());
        save(other(1, true, false));
        processor.enqueue(PAYMENT_ID);
        processor.enqueue(PAYMENT_ID + 1);
        var leased = processor.acquire(PAYMENT_ID).intent();
        assertThat(scan.reserveBatch(AutoCancelLane.PENDING, clock.now, 1)).containsExactly(PAYMENT_ID + 1);
        clock.now = clock.now.plusSeconds(121);
        assertThat(scan.reserveBatch(AutoCancelLane.PENDING, clock.now, 1)).isEmpty();
        assertThat(scan.reserveBatch(AutoCancelLane.PENDING, clock.now, 1)).containsExactly(PAYMENT_ID);
        var recovered = processor.acquire(PAYMENT_ID).intent();
        assertThat(recovered.claimId()).isEqualTo(leased.claimId());
        assertThat(recovered.pgKey()).isEqualTo(leased.pgKey());
        assertThat(recovered.attempts()).isEqualTo(2);
    }

    @Test
    void sweepDiscoversOldLateApprovalAndExecutesPendingOutsideTransactions() {
        save(lateApproved());
        when(gateway.inquireCancellation(any(), any(), anyString(), isNull())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(intent().claimId()).isPositive();
            assertThat(cancellations.findByClaimId(intent().claimId())).isPresent();
            return PgCancellationQueryResult.of(State.OPEN);
        });
        when(gateway.cancelAutomatic(any(), anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return PgCancellationQueryResult.of(State.RETRY);
        });
        sweep.sweep();
        assertThat(intent().attempts()).isEqualTo(1);
        assertThat(intent().nextAttemptAt()).isAfter(clock.now);
        verify(gateway, times(1)).cancelAutomatic(any(), anyString(), any());
        verify(gateway, never()).cancel(any(), anyLong(), any(), any());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> sweep.sweep()))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void independentJdbcLockProvidersExcludeConcurrentTicksAndReleaseAfterFailure() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = mock(PaymentAutoCancelSchedulerUseCase.class);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
            throw new IllegalStateException("synthetic tick failure");
        }).when(blocked).sweep();
        var second = mock(PaymentAutoCancelSchedulerUseCase.class);
        var props = new PaymentAutoCancelSchedulerProperties(true, null, null, null, null, null);
        var actualClock = Clock.tickMillis(ZoneOffset.UTC);
        var a = new PaymentAutoCancelScheduler(blocked, new JdbcLockProvider(source, "payment.shedlock"), props, actualClock);
        var b = new PaymentAutoCancelScheduler(second, new JdbcLockProvider(source, "payment.shedlock"), props, actualClock);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = pool.submit(a::sweep);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            b.sweep();
            verifyNoInteractions(second);
            release.countDown();
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            b.sweep();
            verify(second).sweep();
        } finally {
            release.countDown();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulerProperties {
        @Bean PaymentAutoCancelSchedulerProperties autoCancelSchedulerProperties() {
            return new PaymentAutoCancelSchedulerProperties(true, null, null, null, null, null);
        }
    }
}
