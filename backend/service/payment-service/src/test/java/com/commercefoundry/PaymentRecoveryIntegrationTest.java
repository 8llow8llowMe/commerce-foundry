package com.commercefoundry;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentRecoveryUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentEventPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentRecoveryProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 결제·outbox 테이블과 트랜잭션 프록시를 쓴다. 외부 PG와 시계만 대역이다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "payment.enabled=false", "payment.recovery.enabled=true", "payment.recovery.initial-delay=1h",
    "payment.recovery.page-size=2"
})
@ActiveProfiles("test")
class PaymentRecoveryIntegrationTest {
    @Autowired PaymentRecoveryUseCase recovery;
    @Autowired PaymentProcessor payments;
    @Autowired PaymentRecoveryProcessor rows;
    @MockitoSpyBean PaymentRepositoryPort repository;
    @Autowired PaymentRecoveryQueryPort queries;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean PaymentGatewayPort gateway;
    @MockitoBean Clock clock;
    @MockitoSpyBean PaymentEventPort events;
    private static final Instant NOW = STARTED_AT.plus(Duration.ofHours(1));

    @BeforeEach
    void clean() {
        jdbc.update("delete from payment.outbox");
        jdbc.update("delete from payment.payment");
        when(clock.instant()).thenReturn(NOW);
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PgInquiryQueryResult.Pending(call.getArgument(0));
        });
        recovery.recover(); // 빈 테이블로 직전 테스트의 상태별 커서를 초기화한다.
        clearInvocations(gateway);
    }

    @Test
    void facadeRejectsAnExternalTransactionBeforeAnyPgCall() {
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> recovery.recover()))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void approvalRecordRollbackLeavesInProgressThenInquiryCommitsOneActualOutbox() {
        Payment started = save(inProgress());
        doAnswer(call -> {
            call.callRealMethod();
            entityManager.flush(); // JDBC 조회 전에 지연된 outbox INSERT를 같은 트랜잭션에서 실제 실행한다.
            assertThat(outbox()).isEqualTo(1);
            throw new IllegalStateException("synthetic outbox outage after actual insert");
        }).when(events).approved(any());
        assertThatThrownBy(() -> payments.recordApprovalResult(started, new PgApprovalOutcome.Approved(approval(krw(129_000)))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(read(started.id())).isEqualTo(started);
        assertThat(outbox()).isZero();
        reset(events);
        when(gateway.inquireByPaymentKey(PAYMENT_KEY, started.pgOrderId())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(read(started.id()).status()).isEqualTo(PaymentStatus.UNKNOWN);
            return new PgInquiryQueryResult.Approved(approval(krw(129_000)));
        });
        recovery.recover();
        Payment done = read(started.id());
        assertThat(done.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(done.memberId()).isEqualTo(started.memberId());
        assertThat(done.paymentKey()).isEqualTo(started.paymentKey());
        assertThat(done.approvalIdempotencyKey()).isEqualTo(started.approvalIdempotencyKey());
        assertThat(done.approvalStartedAt()).isEqualTo(started.approvalStartedAt());
        assertThat(outbox()).isEqualTo(1);
        rows.finish(done, new PgInquiryQueryResult.Approved(approval(krw(129_000))), NOW);
        recovery.recover();
        assertThat(outbox()).isEqualTo(1);
        verify(gateway, never()).approve(any());
    }

    @Test
    void recoveryOutboxFailureRollsBackDoneAndRetryPublishesOnce() {
        Payment original = save(unknown());
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(new PgInquiryQueryResult.Approved(approval(krw(129_000))));
        doAnswer(call -> {
            call.callRealMethod();
            entityManager.flush(); // INSERT 이후 예외가 DONE과 outbox를 함께 롤백하는지 검증한다.
            assertThat(outbox()).isEqualTo(1);
            throw new IllegalStateException("synthetic after actual insert");
        }).when(events).approved(any());
        recovery.recover();
        assertThat(read(original.id())).isEqualTo(original);
        assertThat(outbox()).isZero();
        reset(events);
        recovery.recover();
        assertThat(read(original.id()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(outbox()).isEqualTo(1);
    }

    @Test
    void concurrentChangeDuringPgGetRejectsStaleApproval() {
        Payment original = save(unknown());
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenAnswer(call -> {
            tx().executeWithoutResult(status -> repository.save(read(original.id()).flagForManualReview(PaymentUnknownReason.MANUAL_REVIEW)));
            return new PgInquiryQueryResult.Approved(approval(krw(129_000)));
        });
        recovery.recover();
        Payment current = read(original.id());
        assertThat(current.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(current.unknownReason()).isEqualTo(PaymentUnknownReason.MANUAL_REVIEW);
        assertThat(current.version()).isEqualTo(original.version() + 1);
        assertThat(outbox()).isZero();
    }

    @Test
    void twoRealFinishTransactionsHaveOneVersionLoserAndOneOutbox() throws Exception {
        Payment original = save(unknown());
        CyclicBarrier readBarrier = new CyclicBarrier(2);
        doAnswer(call -> {
            var value = call.callRealMethod();
            readBarrier.await(10, TimeUnit.SECONDS); // 두 트랜잭션 모두 동일 버전을 실제로 읽은 뒤 저장을 진행한다.
            return value;
        }).when(repository).findById(original.id());
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<RuntimeException> finish = () -> {
                try {
                    rows.finish(original, new PgInquiryQueryResult.Approved(approval(krw(129_000))), NOW);
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            };
            var first = executor.submit(finish);
            var second = executor.submit(finish);
            var failures = java.util.Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(failures).filteredOn(java.util.Objects::nonNull).singleElement().satisfies(failure -> {
                assertThat(failure).isInstanceOf(PaymentPersistenceConflictException.class);
                assertThat(((PaymentPersistenceConflictException) failure).getKind()).isEqualTo(PaymentPersistenceConflictException.Kind.VERSION);
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            reset(repository);
        }
        assertThat(read(original.id()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(read(original.id()).version()).isEqualTo(original.version() + 1);
        assertThat(outbox()).isEqualTo(1);
    }

    @Test
    void returnedReadyUsesInquiryOnlyAndPreservesNullableCustomerKey() {
        Payment returned = reverted();
        returned = new Payment(returned.id(), returned.orderId(), returned.memberId(), returned.pgOrderId(), returned.gateway(), returned.amount(),
            returned.orderName(), returned.status(), returned.paymentKey(), null, null, null, returned.approvalStartedAt(), null,
            null, null, null, false, null, null, null, null);
        Payment saved = save(returned);
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(new PgInquiryQueryResult.Approved(approval(krw(129_000))));
        recovery.recover();
        assertThat(read(saved.id()).status()).isEqualTo(PaymentStatus.DONE);
        assertThat(read(saved.id()).approvalStartedAt()).isEqualTo(saved.approvalStartedAt());
        assertThat(read(saved.id()).approvalIdempotencyKey()).isNull();
        verify(gateway, never()).approve(any());
        assertThat(outbox()).isEqualTo(1);
    }

    @Test
    void failedReturnedReadyReleasesSlotButNotFoundNeverAborts() {
        Payment saved = save(reverted());
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(new PgInquiryQueryResult.NotFound());
        recovery.recover();
        assertThat(read(saved.id())).isEqualTo(saved);
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(new PgInquiryQueryResult.Failed(PAYMENT_KEY, "EXPIRED", null));
        recovery.recover();
        assertThat(read(saved.id()).abortReason()).isEqualTo(PaymentAbortReason.RECOVERY_NOT_APPROVED);
        assertThat(read(saved.id()).activeOrderId()).isNull();
        assertThat(outbox()).isZero();
    }

    @Test
    void policiesHandleConclusiveResultsBeforeAgeAndHoldUnresolvedOrMismatchedResults() {
        List<PgInquiryQueryResult> unresolved = List.of(new PgInquiryQueryResult.Pending(PAYMENT_KEY),
            new PgInquiryQueryResult.NotFound(), new PgInquiryQueryResult.Unknown(PaymentUnknownReason.TIMEOUT),
            new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR));
        for (var result : unresolved) {
            Payment saved = replace(unknown());
            rows.finish(saved, result, NOW);
            assertThat(read(saved.id())).isEqualTo(saved);
            rows.finish(saved, result, STARTED_AT.plus(Duration.ofHours(24)));
            assertThat(read(saved.id()).unknownReason()).isEqualTo(PaymentUnknownReason.MANUAL_REVIEW);
            saved = replace(reverted());
            rows.finish(saved, result, STARTED_AT.plus(Duration.ofHours(24)));
            assertThat(read(saved.id()).status()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(read(saved.id()).unknownReason()).isEqualTo(PaymentUnknownReason.MANUAL_REVIEW);
        }
        for (var result : List.of(new PgInquiryQueryResult.Pending("wrong"), new PgInquiryQueryResult.Failed("wrong", "EXPIRED", null),
            new PgInquiryQueryResult.Approved(approval(krw(1))), new PgInquiryQueryResult.Approved(approval(Money.of(129_000, CurrencyCode.USD))),
            new PgInquiryQueryResult.Approved(approval("wrong", krw(129_000))),
            new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT), new PgInquiryQueryResult.Unknown(PaymentUnknownReason.ALREADY_PROCESSED),
            new PgInquiryQueryResult.Unknown(PaymentUnknownReason.MANUAL_REVIEW))) {
            Payment saved = replace(unknown());
            rows.finish(saved, result, NOW);
            assertThat(read(saved.id()).unknownReason()).isEqualTo(PaymentUnknownReason.MANUAL_REVIEW);
            assertThat(outbox()).isZero();
        }
        Payment saved = replace(unknown());
        rows.finish(saved, new PgInquiryQueryResult.Failed(PAYMENT_KEY, "EXPIRED", null), STARTED_AT.plus(Duration.ofDays(2)));
        assertThat(read(saved.id()).status()).isEqualTo(PaymentStatus.FAILED);
        saved = replace(unknown());
        rows.finish(saved, new PgInquiryQueryResult.Approved(approval(krw(129_000))), STARTED_AT.plus(Duration.ofDays(2)));
        assertThat(read(saved.id()).status()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void actualKeysetHasStableTieBreakAndExcludesFreshManualAndKeylessReady() {
        for (int index = 0; index < 5; index++) {
            save(attempt(index).markUnknown(PaymentUnknownReason.TIMEOUT));
        }
        save(attempt(10).markUnknown(PaymentUnknownReason.MANUAL_REVIEW));
        save(ready(PAYMENT_ID + 11, ORDER_ID + 11));
        save(ready(PAYMENT_ID + 12, ORDER_ID + 12).startApproval("key12", "00000000-0000-4000-8000-000000000012", NOW));
        Payment legacy = ready(PAYMENT_ID + 13, ORDER_ID + 13);
        save(new Payment(legacy.id(), legacy.orderId(), legacy.memberId(), legacy.pgOrderId(), legacy.gateway(), legacy.amount(), legacy.orderName(),
            PaymentStatus.READY, "legacy-key", null, null, null, null, null, null, null, null, false, null, null, null, null));
        var first = queries.findCandidates(PaymentStatus.UNKNOWN, NOW.minusSeconds(60), null, 2);
        var second = queries.findCandidates(PaymentStatus.UNKNOWN, NOW.minusSeconds(60), first.getLast(), 2);
        assertThat(first).extracting(PaymentRecoveryQueryPort.Candidate::id).containsExactly(PAYMENT_ID, PAYMENT_ID + 1);
        assertThat(second).extracting(PaymentRecoveryQueryPort.Candidate::id).containsExactly(PAYMENT_ID + 2, PAYMENT_ID + 3);
        recovery.recover();
        verify(gateway, times(2)).inquireByPaymentKey(anyString(), anyString());
        recovery.recover();
        verify(gateway, times(4)).inquireByPaymentKey(anyString(), anyString());
        recovery.recover();
        verify(gateway, times(5)).inquireByPaymentKey(anyString(), anyString());
        recovery.recover();
        verify(gateway, times(7)).inquireByPaymentKey(anyString(), anyString());
        verify(gateway, never()).approve(any());
    }

    @Test
    void oneBadCandidateDoesNotStopOtherCandidates() {
        save(attempt(0).markUnknown(PaymentUnknownReason.TIMEOUT));
        save(attempt(1).markUnknown(PaymentUnknownReason.TIMEOUT));
        when(gateway.inquireByPaymentKey("key0", Long.toString(PAYMENT_ID))).thenThrow(new IllegalStateException("private PG text"));
        recovery.recover();
        verify(gateway).inquireByPaymentKey("key1", Long.toString(PAYMENT_ID + 1));
    }

    private Payment attempt(int index) {
        return ready(PAYMENT_ID + index, ORDER_ID + index).startApproval("key" + index, "00000000-0000-4000-8000-%012d".formatted(index), STARTED_AT);
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private Payment save(Payment payment) { return tx().execute(status -> repository.save(payment)); }
    private Payment read(long id) { return tx().execute(status -> repository.findById(id).orElseThrow()); }
    private long outbox() { return jdbc.queryForObject("select count(*) from payment.outbox", Long.class); }
    private Payment replace(Payment payment) {
        jdbc.update("delete from payment.payment");
        return save(payment);
    }
}
