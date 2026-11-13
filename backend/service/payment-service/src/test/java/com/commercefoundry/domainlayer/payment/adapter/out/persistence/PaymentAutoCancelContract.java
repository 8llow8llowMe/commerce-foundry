package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.adapter.out.observation.PaymentAutoCancelReviewObservation;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.commercefoundry.domainlayer.payment.application.service.PaymentCancellationFacade;
import com.commercefoundry.domainlayer.payment.application.service.PaymentAutoCancelFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelExecutionProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentCancellationUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelUseCase;
import com.commercefoundry.shared.commerce.CurrencyCode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAutoCancelStore;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor.Outcome;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.time.Clock;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentAutoCancelClaimCollisionException;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentMapperImpl.class, PaymentRepositoryAdapter.class,
    PaymentCancelPersistenceAdapter.class, PaymentAutoCancelPersistenceAdapter.class, PaymentAutoCancelReviewObservation.class, PaymentAutoCancelProcessor.class,
    PaymentCancelProcessor.class, PaymentCancellationFacade.class, PaymentAutoCancelFacade.class, PaymentAutoCancelExecutionProcessor.class,
    PaymentGatewayProcessor.class, PaymentAutoCancelContract.TimeConfig.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PaymentAutoCancelContract {
    @Autowired PaymentRepositoryPort payments;
    @Autowired PaymentCancelPersistencePort cancellations;
    @Autowired PaymentAutoCancelStore intents;
    @Autowired PaymentAutoCancelProcessor processor;
    @Autowired PaymentCancelProcessor orderCancellations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired SimpleMeterRegistry meters;
    @Autowired PaymentAutoCancelRepository intentRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PaymentAutoCancelReviewObservation reviewObservation;
    @Autowired PaymentCancellationUseCase orderFacade;
    @Autowired PaymentAutoCancelUseCase automaticFacade;
    @Autowired PaymentAutoCancelExecutionProcessor execution;
    @Autowired PaymentGatewayProcessor remoteProcessor;
    @MockitoBean PaymentGatewayPort gateway;
    @MockitoBean SnowflakeIdGenerator ids;

    @BeforeEach
    void resetClock() {
        clock.now = Instant.parse("2026-11-07T00:00:00Z");
        var sequence = new java.util.concurrent.atomic.AtomicLong(9900000001L);
        when(ids.generateId()).thenAnswer(call -> sequence.getAndIncrement());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from payment.payment_auto_cancel");
        jdbc.update("delete from payment.payment_cancel");
        jdbc.update("delete from payment.payment");
    }

    void save(Payment payment) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.save(payment));
    }

    Payment reread() { return payments.findById(PAYMENT_ID).orElseThrow(); }
    PaymentAutoCancel intent() { return intents.find(PAYMENT_ID).orElseThrow(); }

    @Test
    void duplicateRejectionFreezesRemainingAndSharesLedgerWithPositiveClaims() {
        save(done().recordCancellation(krw(29_000)).markOrderSyncFailed("ORDER_CANCELED"));
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
            cancellations.save(new PaymentCancel(42, PAYMENT_ID, krw(29_000), PaymentCancel.Status.SUCCEEDED)));
        processor.enqueue(PAYMENT_ID);
        processor.enqueue(PAYMENT_ID);
        assertThat(intent().amount()).isNull();
        var attempt = processor.acquire(PAYMENT_ID);
        assertThat(attempt.intent().amount()).isEqualTo(krw(100_000));
        assertThat(attempt.intent().claimId()).isPositive();
        assertThat(processor.acquire(PAYMENT_ID)).isNull();
        assertThatThrownBy(() -> orderCancellations.prepare(PAYMENT_ID, 43, krw(1))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> orderCancellations.prepare(PAYMENT_ID, -PAYMENT_ID, krw(100_000))).isInstanceOf(RuntimeException.class);
        assertThat(processor.recordEvidence(PAYMENT_ID, attempt.intent().leaseToken(), "auto-txn")).isTrue();
        assertThat(processor.finish(PAYMENT_ID, attempt.intent().leaseToken(), Outcome.CONFIRMED)).isTrue();
        assertThat(reread().status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(reread().amount()).isEqualTo(krw(129_000));
        assertThat(cancellations.findByPaymentId(PAYMENT_ID)).hasSize(2).allMatch(c -> c.status() == PaymentCancel.Status.SUCCEEDED);
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.SUCCEEDED);
        processor.enqueue(PAYMENT_ID);
        assertThat(processor.acquire(PAYMENT_ID)).isNull();
    }

    @Test
    void externalPgRefundReducesRemainingLikeSucceeded() {
        // #693 EXTERNAL_PG_REFUNDED 는 PG 잔액을 줄였다 — 자동 취소 남은 금액 · 확정 누계는 SUCCEEDED 와 같이 뺀다
        save(done().recordCancellation(krw(29_000)).markOrderSyncFailed("ORDER_CANCELED"));
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
            cancellations.recordExternalRefund(new PaymentCancel(42, PAYMENT_ID, krw(29_000), PaymentCancel.Status.EXTERNAL_PG_REFUNDED)));
        processor.enqueue(PAYMENT_ID);
        var attempt = processor.acquire(PAYMENT_ID);
        assertThat(attempt.intent().amount()).isEqualTo(krw(100_000));
        assertThat(processor.recordEvidence(PAYMENT_ID, attempt.intent().leaseToken(), "auto-txn")).isTrue();
        assertThat(processor.finish(PAYMENT_ID, attempt.intent().leaseToken(), Outcome.CONFIRMED)).isTrue();
        assertThat(reread().status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.EXTERNAL_PG_REFUNDED);
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.SUCCEEDED);
    }

    @Test
    void offPgRefundSendsAutoCancelToManualReviewWithoutClaimOrPg() {
        // #693 PG 밖 환불은 PG 잔액에 남아 있다 — PG 잔액 전부를 자동 취소하면 이중 환불이라 사람이 정한다
        save(done().markOrderSyncFailed("ORDER_CANCELED"));
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
            cancellations.recordExternalRefund(new PaymentCancel(42, PAYMENT_ID, krw(29_000), PaymentCancel.Status.OFF_PG_REFUNDED)));
        processor.enqueue(PAYMENT_ID);
        assertThat(processor.acquire(PAYMENT_ID)).isNull();
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        assertThat(intent().claimId()).isNull();
        assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement()
            .satisfies(row -> assertThat(row.status()).isEqualTo(PaymentCancel.Status.OFF_PG_REFUNDED));
        assertThat(reread().status()).isEqualTo(PaymentStatus.DONE);
        verifyNoInteractions(gateway);
        verify(ids, never()).generateId();
    }

    @Test
    void offPgRefundRecordedAfterAcquireSendsFinishToManualReview() {
        // #693 finish 의 이중 방어 — 획득 뒤 OFF_PG 가 기록됐으면 CONFIRMED 여도 자동 확정하지 않는다
        save(done().markOrderSyncFailed("ORDER_CANCELED"));
        processor.enqueue(PAYMENT_ID);
        var attempt = processor.acquire(PAYMENT_ID);
        assertThat(processor.recordEvidence(PAYMENT_ID, attempt.intent().leaseToken(), "auto-txn")).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
            cancellations.recordExternalRefund(new PaymentCancel(43, PAYMENT_ID, krw(1_000), PaymentCancel.Status.OFF_PG_REFUNDED)));

        assertThat(processor.finish(PAYMENT_ID, attempt.intent().leaseToken(), Outcome.CONFIRMED)).isTrue();

        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        assertThat(reread().status()).isEqualTo(PaymentStatus.DONE);
        assertThat(cancellations.findByClaimId(attempt.intent().claimId()).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
    }

    @Test
    void competingWorkersAcquireExactlyOneCommittedLease() throws Exception {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<PaymentAutoCancelProcessor.Attempt> work = () -> {
                ready.countDown();
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Worker start timed out");
                }
                return processor.acquire(PAYMENT_ID);
            };
            var first = pool.submit(work);
            var second = pool.submit(work);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
            assertThat(intent().attempts()).isEqualTo(1);
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).hasSize(1);
        } finally {
            start.countDown();
        }
    }

    @Test
    void otherPendingClaimBlocksAutomaticPrepareUntilItsResolution() {
        save(done().markOrderSyncFailed("ORDER_CANCELED"));
        orderCancellations.prepare(PAYMENT_ID, 42, krw(29_000));
        processor.enqueue(PAYMENT_ID);
        assertThat(processor.acquire(PAYMENT_ID)).isNull();
        assertThat(intent().amount()).isNull();
        orderCancellations.complete(PAYMENT_ID, 42);
        clock.now = clock.now.plusSeconds(120);
        assertThat(processor.acquire(PAYMENT_ID).intent().amount()).isEqualTo(krw(100_000));
    }

    @Test
    void crashLeaseRecoveryReusesAmountKeyAndRejectsStaleCompletion() {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var first = processor.acquire(PAYMENT_ID).intent();
        clock.now = clock.now.plusSeconds(121);
        var resumed = processor.acquire(PAYMENT_ID).intent();
        assertThat(resumed.pgKey()).isEqualTo(first.pgKey());
        assertThat(resumed.amount()).isEqualTo(first.amount());
        assertThat(resumed.attempts()).isEqualTo(2);
        assertThat(resumed.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(processor.recordEvidence(PAYMENT_ID, first.leaseToken(), "old-txn")).isFalse();
        assertThat(processor.finish(PAYMENT_ID, first.leaseToken(), Outcome.CONFIRMED)).isFalse();
        assertThat(processor.recordEvidence(PAYMENT_ID, resumed.leaseToken(), "new-txn")).isTrue();
        processor.finish(PAYMENT_ID, resumed.leaseToken(), Outcome.CONFIRMED);
        assertThat(reread().status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(reread().activeOrderId()).isNull();
        assertThat(reread().failureCode()).isEqualTo(lateApproved().failureCode());
        assertThat(reread().approvedAt()).isEqualTo(APPROVED_AT);
    }

    @Test
    void unknownKeepsFrozenPendingAndExpiredKeyRequiresFixedManualReason() {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var first = processor.acquire(PAYMENT_ID).intent();
        processor.finish(PAYMENT_ID, first.leaseToken(), Outcome.RETRY);
        assertThat(cancellations.findByClaimId(intent().claimId()).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
        assertThat(intent().nextAttemptAt()).isAfter(clock.now);
        clock.now = clock.now.plusSeconds(15 * 86400);
        var expired = processor.acquire(PAYMENT_ID).intent();
        assertThat(expired.keyExpired(clock.now)).isTrue();
        processor.finish(PAYMENT_ID, expired.leaseToken(), Outcome.MANUAL_REVIEW);
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        assertThat(intent().manualReason()).isEqualTo(PaymentAutoCancel.MANUAL_REASON);
        assertThat(intent().pgKey()).isEqualTo(first.pgKey());
        processor.enqueue(PAYMENT_ID);
        assertThat(processor.acquire(PAYMENT_ID)).isNull();
    }

    @Test
    void metadataCompletionFailureRollsBackPaymentAndSharedLedger() {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var acquired = processor.acquire(PAYMENT_ID).intent();
        processor.recordEvidence(PAYMENT_ID, acquired.leaseToken(), "txn");
        jdbc.execute("alter table payment.payment_auto_cancel add constraint ck_test_no_success check (state <> 'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> processor.finish(PAYMENT_ID, acquired.leaseToken(), Outcome.CONFIRMED)).isInstanceOf(RuntimeException.class);
            assertThat(reread().status()).isEqualTo(PaymentStatus.LATE_APPROVED);
            assertThat(cancellations.findByClaimId(intent().claimId()).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
            assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.PENDING);
        } finally {
            jdbc.execute("alter table payment.payment_auto_cancel drop constraint ck_test_no_success");
        }
        processor.finish(PAYMENT_ID, acquired.leaseToken(), Outcome.CONFIRMED);
        assertThat(reread().status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void foreignSucceededClaimCollisionRollsBackAndFreshTransactionGetsNewPositiveId() {
        save(lateApproved());
        var foreign = new PaymentCancel(42, PAYMENT_ID + 100, krw(500), PaymentCancel.Status.SUCCEEDED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(foreign));
        processor.enqueue(PAYMENT_ID);
        when(ids.generateId()).thenReturn(42L, 9900000099L);
        assertThatThrownBy(() -> processor.acquire(PAYMENT_ID)).isInstanceOf(PaymentAutoCancelClaimCollisionException.class);
        assertThat(intent().claimId()).isNull();
        assertThat(intent().amount()).isNull();
        assertThat(intent().attempts()).isZero();
        assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.SUCCEEDED);
        assertThat(cancellations.findByClaimId(42).orElseThrow().paymentId()).isEqualTo(PAYMENT_ID + 100);
        var committed = processor.acquire(PAYMENT_ID).intent();
        assertThat(committed.claimId()).isEqualTo(9900000099L);
        assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement().satisfies(row -> {
            // The pre-#579 positive-only domain reader accepts this unmodified ledger format.
            assertThat(new PaymentCancel(row.claimId(), row.paymentId(), row.amount(), row.status(), row.createdAt(), row.updatedAt())).isEqualTo(row);
            assertThat(row.claimId()).isPositive();
        });
        clock.now = clock.now.plusSeconds(121);
        assertThat(processor.acquire(PAYMENT_ID).intent().claimId()).isEqualTo(committed.claimId());
        verifyNoInteractions(gateway);
    }

    @Test
    void committedAutomaticClaimCannotEnterOrderCancellationEvenWithMatchingIdentityAndAmount() {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        when(ids.generateId()).thenReturn(9900000099L);
        var acquired = processor.acquire(PAYMENT_ID).intent();
        for (int state = 0; state < 2; state++) {
            assertThatThrownBy(() -> orderFacade.cancel(PAYMENT_ID, acquired.claimId(), acquired.amount().amount(),
                acquired.amount().currency(), Long.toString(acquired.claimId())))
                .isInstanceOfSatisfying(PaymentException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_KEY_REUSED));
            assertThat(cancellations.findByClaimId(acquired.claimId()).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
            if (state == 0) processor.finish(PAYMENT_ID, acquired.leaseToken(), Outcome.MANUAL_REVIEW);
        }
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        verifyNoInteractions(gateway);
    }

    @Test
    void strictInsertCollisionAndUpsertIdentityMismatchNeverChangeExistingSucceededRow() {
        var original = new PaymentCancel(42, PAYMENT_ID + 100, krw(500), PaymentCancel.Status.SUCCEEDED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(original));
        var impostor = new PaymentCancel(42, PAYMENT_ID, krw(129000), PaymentCancel.Status.PENDING);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(impostor)))
            .isInstanceOf(com.commercefoundry.domainlayer.payment.application.exception.PaymentException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.insertNewAutoClaim(impostor)))
            .isInstanceOf(PaymentAutoCancelClaimCollisionException.class);
        var after = cancellations.findByClaimId(42).orElseThrow();
        assertThat(after.status()).isEqualTo(original.status());
        assertThat(after.paymentId()).isEqualTo(original.paymentId());
        assertThat(after.amount()).isEqualTo(original.amount());
    }

    @Test
    void immutableAmountCurrencyAndTerminalStatusAreCheckedBeforeUpsertMutation() {
        var original = new PaymentCancel(42, PAYMENT_ID, krw(500), PaymentCancel.Status.SUCCEEDED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(original));
        var invalid = java.util.List.of(
            new PaymentCancel(42, PAYMENT_ID, krw(501), PaymentCancel.Status.PENDING),
            new PaymentCancel(42, PAYMENT_ID, com.commercefoundry.shared.commerce.Money.of(500, CurrencyCode.USD), PaymentCancel.Status.PENDING),
            new PaymentCancel(42, PAYMENT_ID, krw(500), PaymentCancel.Status.PENDING));
        for (var row : invalid) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> cancellations.save(row)))
                .isInstanceOf(com.commercefoundry.domainlayer.payment.application.exception.PaymentException.class);
        }
        assertThat(cancellations.findByClaimId(42).orElseThrow().status()).isEqualTo(PaymentCancel.Status.SUCCEEDED);
        assertThat(cancellations.findByClaimId(42).orElseThrow().amount()).isEqualTo(krw(500));
    }

    @Test
    void nullableMetadataClaimMappingBecomesUniqueWhenAssigned() {
        var first = PaymentAutoCancel.intent(PAYMENT_ID, clock.now).acquire(123, krw(500), "a", clock.now);
        var duplicate = PaymentAutoCancel.intent(PAYMENT_ID + 1, clock.now).acquire(123, krw(500), "b", clock.now);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> intents.save(first));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> intents.save(duplicate)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(intent().claimId()).isEqualTo(123L);
        assertThat(intents.find(PAYMENT_ID + 1)).isEmpty();
    }

    @Test
    void competingInsertOnlyClaimsCannotOverwriteEachOtherOnPkRace() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.function.LongFunction<java.util.concurrent.Callable<Boolean>> insert = payment -> () -> {
                ready.countDown();
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out");
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx ->
                        cancellations.insertNewAutoClaim(new PaymentCancel(123, payment, krw(500), PaymentCancel.Status.PENDING)));
                    return true;
                } catch (PaymentAutoCancelClaimCollisionException collision) {
                    return false;
                }
            };
            var first = pool.submit(insert.apply(PAYMENT_ID));
            var second = pool.submit(insert.apply(PAYMENT_ID + 1));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            boolean b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a).isNotEqualTo(b);
            var row = cancellations.findByClaimId(123).orElseThrow();
            assertThat(row.paymentId()).isEqualTo(a ? PAYMENT_ID : PAYMENT_ID + 1);
            assertThat(row.status()).isEqualTo(PaymentCancel.Status.PENDING);
            assertThat(row.amount()).isEqualTo(krw(500));
        } finally {
            start.countDown();
        }
    }

    @Test
    void failedFirstMetadataSaveRollsBackNewLedgerAndNextAcquireMayGenerateAgain() {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        jdbc.execute("alter table payment.payment_auto_cancel add constraint ck_test_unassigned check (claim_id is null)");
        try {
            assertThatThrownBy(() -> processor.acquire(PAYMENT_ID)).isInstanceOf(RuntimeException.class);
            assertThat(intent().claimId()).isNull();
            assertThat(intent().attempts()).isZero();
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).isEmpty();
        } finally {
            jdbc.execute("alter table payment.payment_auto_cancel drop constraint ck_test_unassigned");
        }
        assertThat(processor.acquire(PAYMENT_ID).intent().claimId()).isPositive();
        verifyNoInteractions(gateway);
    }

    @Test
    void missingCommittedLedgerRequiresReviewWithoutRegeneratingClaim() {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var acquired = processor.acquire(PAYMENT_ID).intent();
        jdbc.update("delete from payment.payment_cancel where claim_id=?", acquired.claimId());
        clock.now = clock.now.plusSeconds(121);
        assertThat(processor.acquire(PAYMENT_ID)).isNull();
        assertThat(intent().claimId()).isEqualTo(acquired.claimId());
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        assertThat(cancellations.findByPaymentId(PAYMENT_ID)).isEmpty();
        verify(ids, times(1)).generateId();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancellationQueryResult.State.class,
        names = {"MANUAL_REVIEW", "UNATTRIBUTED", "RETRY"})
    void terminalAmbiguousAndExpiredGetOutcomesEmitOnceAndStayDurablyVisible(
        com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancellationQueryResult.State state) {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var first = processor.acquire(PAYMENT_ID).intent();
        clock.now = clock.now.plusSeconds(state.name().equals("RETRY") ? 15 * 86400 : 121);
        when(gateway.inquireCancellation(any(), any(), anyString(), isNull())).thenReturn(
            com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancellationQueryResult.of(state));
        var actual = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(execution);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "enabled", true);
        double before = meters.counter(PaymentAutoCancelReviewObservation.COUNTER).count();
        automaticFacade.execute(PAYMENT_ID);
        automaticFacade.execute(PAYMENT_ID);
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        assertThat(intent().claimId()).isEqualTo(first.claimId());
        assertThat(cancellations.findByClaimId(first.claimId()).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
        assertThat(meters.counter(PaymentAutoCancelReviewObservation.COUNTER).count()).isEqualTo(before + 1);
        assertThat(meters.get(PaymentAutoCancelReviewObservation.GAUGE).gauge().value()).isEqualTo(1);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
        // Simulate a new metrics process over the existing durable state: gauge survives without inventing counter events.
        var restarted = new SimpleMeterRegistry();
        try {
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("registry", restarted));
            new PaymentAutoCancelReviewObservation(intentRepository, beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
            assertThat(restarted.get(PaymentAutoCancelReviewObservation.GAUGE).gauge().value()).isEqualTo(1);
            assertThat(restarted.find(PaymentAutoCancelReviewObservation.COUNTER).counter()).isNull();
        } finally { restarted.close(); }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void manualTransitionEmitsOnlyAfterCommitAndRemainsObservable(org.springframework.boot.test.system.CapturedOutput output) {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var acquired = processor.acquire(PAYMENT_ID).intent();
        double before = meters.counter(PaymentAutoCancelReviewObservation.COUNTER).count();
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            intents.save(acquired.finish(false));
            reviewObservation.afterCommit();
            assertThat(meters.counter(PaymentAutoCancelReviewObservation.COUNTER).count()).isEqualTo(before);
            status.setRollbackOnly();
        });
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.PENDING);
        assertThat(meters.counter(PaymentAutoCancelReviewObservation.COUNTER).count()).isEqualTo(before);
        processor.finish(PAYMENT_ID, acquired.leaseToken(), Outcome.MANUAL_REVIEW);
        processor.finish(PAYMENT_ID, acquired.leaseToken(), Outcome.MANUAL_REVIEW);
        assertThat(meters.counter(PaymentAutoCancelReviewObservation.COUNTER).count()).isEqualTo(before + 1);
        assertThat(meters.get(PaymentAutoCancelReviewObservation.COUNTER).counter().getId().getTags()).isEmpty();
        assertThat(meters.get(PaymentAutoCancelReviewObservation.GAUGE).gauge().value()).isEqualTo(1);
        assertThat(output).contains("AUTO_CANCEL_MANUAL_REVIEW").doesNotContain(acquired.pgKey(), acquired.leaseToken());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void observationRegistrationFailureCannotRollBackManualState(org.springframework.boot.test.system.CapturedOutput output) {
        save(lateApproved());
        processor.enqueue(PAYMENT_ID);
        var acquired = processor.acquire(PAYMENT_ID).intent();
        doThrow(new IllegalStateException("SENTINEL_OBSERVATION_FAILURE")).when(reviewObservation).afterCommit();
        assertThat(processor.finish(PAYMENT_ID, acquired.leaseToken(), Outcome.MANUAL_REVIEW)).isTrue();
        assertThat(intent().state()).isEqualTo(PaymentAutoCancel.State.MANUAL_REVIEW);
        assertThat(cancellations.findByClaimId(acquired.claimId()).orElseThrow().status()).isEqualTo(PaymentCancel.Status.PENDING);
        assertThat(meters.get(PaymentAutoCancelReviewObservation.GAUGE).gauge().value()).isEqualTo(1);
        assertThat(output).doesNotContain("SENTINEL_OBSERVATION_FAILURE");
    }

    @Test
    void ambientTransactionsAreRejectedByActualSpringProxiesBeforePg() {
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> orderFacade.cancel(PAYMENT_ID, 42, 1, CurrencyCode.KRW, "42")))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> automaticFacade.execute(PAYMENT_ID)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> remoteProcessor.cancelAutomatic(done(), "payment-auto-cancel-v1-1", krw(1))))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> orderFacade.cancel(PAYMENT_ID, -PAYMENT_ID, 1, CurrencyCode.KRW, Long.toString(-PAYMENT_ID)))
            .isInstanceOf(com.commercefoundry.domainlayer.payment.application.exception.PaymentException.class);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void normalDoneAndAlreadyCanceledAreNoOp() {
        save(done());
        processor.enqueue(PAYMENT_ID);
        assertThat(intents.find(PAYMENT_ID)).isEmpty();
        save(reread().recordCancellation(krw(129_000)));
        processor.enqueue(PAYMENT_ID);
        assertThat(intents.find(PAYMENT_ID)).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean SimpleMeterRegistry meters() { return new SimpleMeterRegistry(); }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
