package com.commercefoundry.domainlayer.payment;

import static com.commercefoundry.domainlayer.payment.OrderPaymentReplyUnitTest.*;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.OrderReconciliationScanAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.OrderReconciliationCursorEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.OrderReconciliationCursorRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.application.service.OrderReconciliationSchedulerFacade;
import com.commercefoundry.domainlayer.payment.application.service.OrderReconciliationObservation;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderReconciliationScanProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import({OrderReconciliationSchedulerFacade.class, OrderReconciliationScanProcessor.class, OrderReconciliationScanAdapter.class,
    OrderReconciliationObservation.class, OrderReconciliationContract.Config.class})
abstract class OrderReconciliationContract extends OrderPaymentReplyContract {
    @Autowired OrderReconciliationSchedulerFacade reconciliation;
    @Autowired OrderReconciliationScanAdapter scan;
    @Autowired OrderReconciliationCursorRepository cursors;
    @Autowired com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository repository;
    @Autowired SimpleMeterRegistry meters;
    @Autowired javax.sql.DataSource dataSource;

    @BeforeEach
    void resetCursor() {
        tx().executeWithoutResult(s -> {
            cursors.deleteAllInBatch();
            for (var lane : OrderReconciliationLane.values()) cursors.save(new OrderReconciliationCursorEntity(lane, Instant.EPOCH));
        });
        meters.clear();
    }

    @ParameterizedTest @EnumSource(Result.class)
    void lostReplyFetchesSevenSourcesOnceOutsideTransactionAndCommitsSameInbox(Result result) {
        result(result);
        reconciliation.reconcile();
        verify(query).query(PAYMENT_ID);
        assertThat(fresh().orderSynced()).isEqualTo(result == Result.PAID);
        assertThat(fresh().orderSyncFailureCode()).isEqualTo(result == Result.PAID ? null : result.name().substring(9));
        assertThat(inbox.exists(GROUP, OrderReconciliationSchedulerFacade.syntheticEventId(PAYMENT_ID))).isTrue();
        assertThat(meters.get(OrderReconciliationObservation.CHANGED).tag("result", result.name()).counter().count()).isEqualTo(1);
        assertThat(meters.get(OrderReconciliationObservation.CHANGED).tag("result", result.name()).counter().getId().getTags())
            .extracting(io.micrometer.core.instrument.Tag::getKey).containsExactly("result");
        reconciliation.reconcile();
        assertThat(inboxCount()).isEqualTo(1);
        assertThat(meters.get(OrderReconciliationObservation.CHANGED).tag("result", result.name()).counter().count()).isEqualTo(1);
        // REJECTED는 빠른 훅과 별개로 같은 tick에도 실행할 수 있다. 복원 완료를 가정하지 않는다.
        if (result != Result.PAID) verify(policy, atLeast(2)).onRejected(PAYMENT_ID);
    }

    @Test
    void cutoffIsStrictAndFrozenForCycleWithZeroAndFiveMinuteThresholds() {
        var lane = OrderReconciliationLane.RECONCILE;
        assertThat(scan.reserveBatch(lane, APPROVED_AT, 1)).isEmpty();
        assertThat(scan.reserveBatch(lane, APPROVED_AT.minusNanos(1000), 1)).isEmpty();
        assertThat(scan.reserveBatch(lane, APPROVED_AT.plusNanos(1000), 1)).containsExactly(PAYMENT_ID);
        assertThat(scan.reserveBatch(lane, APPROVED_AT.plus(Duration.ofMinutes(5)), 1)).isEmpty();
        assertThat(scan.reserveBatch(lane, APPROVED_AT.plus(Duration.ofMinutes(5)), 1)).containsExactly(PAYMENT_ID);
    }

    @Test
    void cycleCutoffDoesNotMoveWhileNewCandidatesBecomeOldEnough() {
        add(PAYMENT_ID + 1, APPROVED_AT.plusSeconds(2), false);
        assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, APPROVED_AT.plusSeconds(1), 1)).containsExactly(PAYMENT_ID);
        assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, APPROVED_AT.plusSeconds(3), 1)).isEmpty();
        assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, APPROVED_AT.plusSeconds(3), 2))
            .containsExactly(PAYMENT_ID, PAYMENT_ID + 1);
    }

    @Test
    void queryPopulationSeparatesRejectionsAndExcludesSyncedCanceledAndLateApprovals() {
        tx().executeWithoutResult(s -> payments.save(fresh().recordCancellation(krw(1000))));
        add(PAYMENT_ID + 1, APPROVED_AT, true);
        add(PAYMENT_ID + 2, APPROVED_AT, false);
        add(PAYMENT_ID + 3, APPROVED_AT, false);
        tx().executeWithoutResult(s -> {
            payments.save(payments.findById(PAYMENT_ID + 2).orElseThrow().markOrderSynced());
            payments.save(payments.findById(PAYMENT_ID + 3).orElseThrow().recordCancellation(krw(129000)));
        });
        assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, APPROVED_AT.plusSeconds(1), 10)).containsExactly(PAYMENT_ID);
        // Rejected rows have no age cutoff, including approved exactly at the supplied boundary.
        assertThat(scan.reserveBatch(OrderReconciliationLane.REJECTED, APPROVED_AT, 10)).containsExactly(PAYMENT_ID + 1);
        tx().executeWithoutResult(s -> {
            jdbc.update("delete from payment.payment");
            payments.save(lateApproved());
            cursors.findById(OrderReconciliationLane.RECONCILE).orElseThrow().finishCycle();
        });
        assertThat(scan.reserveBatch(OrderReconciliationLane.RECONCILE, APPROVED_AT.plusSeconds(1), 10)).isEmpty();
    }

    @Test
    void separateJdbcProvidersExcludeConcurrentTicksAndReleaseForNextTick() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var useCase = mock(com.commercefoundry.domainlayer.payment.application.port.in.OrderReconciliationSchedulerUseCase.class);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(useCase).reconcile();
        var config = OrderReconciliationUnitTest.props(true);
        var first = new com.commercefoundry.domainlayer.payment.adapter.in.scheduler.OrderReconciliationScheduler(useCase,
            new net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider(dataSource, "payment.shedlock"), config, java.time.Clock.systemUTC());
        var second = new com.commercefoundry.domainlayer.payment.adapter.in.scheduler.OrderReconciliationScheduler(useCase,
            new net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider(dataSource, "payment.shedlock"), config, java.time.Clock.systemUTC());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var running = pool.submit(first::reconcile);
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                pool.submit(second::reconcile).get(10, java.util.concurrent.TimeUnit.SECONDS);
                verify(useCase).reconcile();
            } finally { release.countDown(); }
            running.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        second.reconcile();
        verify(useCase, times(2)).reconcile();
    }

    @Test
    void keysetProgressSurvivesAdapterReconstructionAndBoundsNewInsertions() {
        var lane = OrderReconciliationLane.RECONCILE;
        add(PAYMENT_ID + 1, APPROVED_AT, false);
        var cutoff = APPROVED_AT.plusSeconds(1);
        assertThat(scan.reserveBatch(lane, cutoff, 1)).containsExactly(PAYMENT_ID);
        add(PAYMENT_ID + 2, APPROVED_AT, false);
        var restarted = new OrderReconciliationScanAdapter(cursors, repository);
        List<Long> resumed = tx().execute(s -> restarted.reserveBatch(lane, cutoff.plusSeconds(20), 1));
        assertThat(resumed).containsExactly(PAYMENT_ID + 1);
        assertThat(scan.reserveBatch(lane, cutoff, 1)).isEmpty(); // cycle upper bound excludes newly inserted id
        assertThat(scan.reserveBatch(lane, cutoff, 3)).containsExactly(PAYMENT_ID, PAYMENT_ID + 1, PAYMENT_ID + 2);
    }

    @Test
    void failedFrontCannotStarveFollowingPaymentOrRejectedLaneAndHookLossIsRetriedFromDatabase() {
        add(PAYMENT_ID + 1, APPROVED_AT, true);
        doThrow(new IllegalStateException("synthetic failure")).when(query).query(PAYMENT_ID);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(policy).onRejected(PAYMENT_ID + 1);
        reconciliation.reconcile();
        verify(policy).onRejected(PAYMENT_ID + 1);
        add(PAYMENT_ID + 2, APPROVED_AT, false);
        // Failed front was already checkpointed; next cycle remains bounded despite new arrivals.
        reconciliation.reconcile();
        reconciliation.reconcile();
        reconciliation.reconcile();
        verify(policy, atLeast(2)).onRejected(PAYMENT_ID + 1);
        assertThat(cursors.findById(OrderReconciliationLane.RECONCILE).orElseThrow().lastId()).isGreaterThanOrEqualTo(PAYMENT_ID);
    }

    @Test
    void committedRejectionWithLostOrFailedFastHookIsRetriedWithoutSourceOrInbox() {
        result(Result.REJECTED_ORDER_CANCELED);
        var event = command(Result.REJECTED_ORDER_CANCELED);
        processor.record(event, processor.fetchSource(event)); // process stops before fast hook
        clearInvocations(query);
        doThrow(new IllegalStateException("synthetic policy failure")).when(policy).onRejected(PAYMENT_ID);
        reconciliation.reconcile();
        reconciliation.reconcile(); // ends the first finite cycle
        reconciliation.reconcile(); // retry from DB, even with inbox present
        verify(policy, times(2)).onRejected(PAYMENT_ID);
        verifyNoInteractions(query);
        assertThat(fresh().orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
    }

    @Test
    void failedInboxRollsBackPaymentAndDoesNotCountChangedReconciliation() {
        tx().executeWithoutResult(s -> doThrow(new IllegalStateException("synthetic storage failure"))
            .when(inbox).record(anyString(), any(), anyString(), any()));
        reconciliation.reconcile();
        assertThat(fresh().orderSynced()).isFalse();
        assertThat(fresh().version()).isZero();
        assertThat(inboxCount()).isZero();
        assertThat(meters.find(OrderReconciliationObservation.CHANGED).counter()).isNull();
        verifyNoInteractions(policy);
    }

    @Test
    void failureAfterInboxInsertRollsBackBothAndNextCycleCanRecover() {
        result(Result.REJECTED_ORDER_CANCELED);
        tx().executeWithoutResult(s -> doAnswer(call -> {
            call.callRealMethod();
            em.flush();
            throw new IllegalStateException("synthetic failure after inbox");
        }).when(inbox).record(anyString(), any(), anyString(), any()));
        reconciliation.reconcile();
        assertThat(fresh().orderSyncFailureCode()).isNull();
        assertThat(inboxCount()).isZero();
        assertThat(meters.find(OrderReconciliationObservation.CHANGED).counter()).isNull();
        verifyNoInteractions(policy);
        tx().executeWithoutResult(s -> doCallRealMethod().when(inbox).record(anyString(), any(), anyString(), any()));
        reconciliation.reconcile();
        reconciliation.reconcile();
        assertThat(fresh().orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
        assertThat(inboxCount()).isEqualTo(1);
    }

    @Test
    void rejectsAmbientTransactionBeforeScanningOrFetching() {
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> reconciliation.reconcile())).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(query, policy);
    }

    @Test
    void sourceLocalMismatchAndSuccessMonotonicityArePreservedOnSyntheticPath() {
        var original = source(Result.PAID);
        when(query.query(PAYMENT_ID)).thenReturn(new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID + 1,
            Result.PAID, original.amount(), original.approvedAt(), original.stockReservationId()));
        reconciliation.reconcile();
        assertThat(fresh().orderSynced()).isFalse();
        assertThat(inboxCount()).isZero();
        result(Result.PAID);
        reconciliation.reconcile(); // finish old cycle
        reconciliation.reconcile();
        assertThat(fresh().orderSynced()).isTrue();
        result(Result.REJECTED_ORDER_CANCELED);
        facade.handle(command(Result.REJECTED_ORDER_CANCELED));
        assertThat(fresh().orderSynced()).isTrue();
        verifyNoInteractions(policy);
    }

    void add(long id, Instant at, boolean rejected) {
        var value = ready(id, ORDER_ID + id).startApproval("synthetic-key-" + id, java.util.UUID.randomUUID().toString(), at.minusSeconds(1))
            .approve(new PaymentApproval("synthetic-key-" + id, com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod.CARD,
                null, at, "DONE", krw(129000)));
        var payment = rejected ? value.markOrderSyncFailed("ORDER_CANCELED") : value;
        tx().executeWithoutResult(s -> payments.save(payment));
    }

    @TestConfiguration
    static class Config {
        @Bean PaymentOrderReconciliationProperties reconciliationProperties() {
            return new PaymentOrderReconciliationProperties(true, null, null, null, 1, 1);
        }
        @Bean SimpleMeterRegistry reconciliationRegistry() { return new SimpleMeterRegistry(); }
    }
}
