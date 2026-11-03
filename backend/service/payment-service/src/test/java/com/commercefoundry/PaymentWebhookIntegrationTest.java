package com.commercefoundry;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.STARTED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.approval;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.done;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.failed;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.inProgress;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ready;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.reverted;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.unknown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.payment.application.command.PaymentWebhookCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookConflictException;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentEventPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentWebhookInboxPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentWebhookProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentInquiry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 inbox/payment/outbox 트랜잭션과 익명 HTTP. PG·시계만 대역이다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-webhook;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "payment.enabled=false", "payment.webhook.enabled=true", "payment.webhook.initial-delay=1h", "payment.webhook.batch-size=2",
    "payment.recovery.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentWebhookIntegrationTest {
    private static final Instant NOW = STARTED_AT.plusSeconds(60);
    private static final String PATH = "/api/v1/payments/webhooks/toss";
    @Autowired MockMvcTester mvc;
    @Autowired PaymentWebhookUseCase receiver;
    @Autowired PaymentWebhookSchedulerUseCase worker;
    @Autowired PaymentWebhookProcessor rows;
    @Autowired PaymentProcessor paymentProcessor;
    @Autowired PaymentRepositoryPort payments;
    @MockitoSpyBean PaymentWebhookInboxPort inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PaymentGatewayPort gateway;
    @MockitoBean Clock clock;
    @MockitoSpyBean PaymentEventPort events;

    @BeforeEach
    void clean() {
        reset(events, inbox);
        jdbc.update("delete from payment.payment_webhook_inbox");
        jdbc.update("delete from payment.outbox");
        jdbc.update("delete from payment.payment");
        when(clock.instant()).thenReturn(NOW);
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PgInquiryQueryResult.Approved(approval(krw(129_000)));
        });
    }

    @Test
    void anonymousRealPayloadWithoutEventIdIsDurablyReceivedAndBodyAmountsAreIgnored() {
        Payment original = save(inProgress());
        var result = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(payload("2026-10-21T10:01:00.123456")).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        assertThat(count()).isOne();
        assertThat(read()).isEqualTo(original); // status=DONE/totalAmount=1/currency=USD를 본문에 넣어도 바뀌지 않는다.
        verifyNoInteractions(gateway);
        worker.process();
        assertThat(read().status()).isEqualTo(PaymentStatus.DONE);
        assertThat(read().amount()).isEqualTo(krw(129_000));
        assertThat(state()).isEqualTo(State.COMPLETED);
        assertThat(outbox()).isOne();
        verify(gateway, never()).approve(any());
        verify(gateway, never()).cancel(any(), anyLong(), any(), any());
    }

    @Test
    void duplicateReceiptNeverResetsCompletedAndDistinctCreatedAtCreatesNewWork() {
        save(inProgress());
        receive(); receive();
        assertThat(count()).isOne();
        worker.process();
        receive();
        assertThat(count()).isOne();
        assertThat(state()).isEqualTo(State.COMPLETED);
        receiver.receive(command("2026-10-21T01:02:00Z"));
        assertThat(count()).isEqualTo(2);
        worker.process();
        assertThat(outbox()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from payment.payment_webhook_inbox where state='COMPLETED'", Long.class)).isEqualTo(2);
    }

    @Test
    void simultaneousDuplicateInsertReturnsOnlyAfterCommittedExistingRow() throws Exception {
        save(inProgress());
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { barrier.await(10, TimeUnit.SECONDS); return call.callRealMethod(); }).when(inbox).insert(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(this::receive);
            var two = pool.submit(this::receive);
            one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        }
        assertThat(count()).isOne();
    }

    @Test
    void unknownUnboundAndMismatchedBindingsAreRejectedBeforeEnqueue() {
        assertThat(post(payload("2026-10-21T01:01:00Z"))).hasStatus(HttpStatus.NOT_FOUND);
        save(ready());
        assertThat(post(payload("2026-10-21T01:01:00Z"))).hasStatus(HttpStatus.BAD_REQUEST);
        jdbc.update("delete from payment.payment");
        save(inProgress());
        assertThat(post(payload("2026-10-21T01:01:00Z").replace(PAYMENT_KEY, "other-key"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(count()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void unsupportedMalformedOrMissingCanonicalFieldsAre400AndStorageFailureIs503() {
        save(inProgress());
        String valid = payload("2026-10-21T01:01:00Z");
        for (String invalid : new String[]{"{", "{}", valid.replace("PAYMENT_STATUS_CHANGED", "REFUND_FAILED"),
            valid.replace("2026-10-21T01:01:00Z", "not-a-time"), valid.replace("2026-10-21T01:01:00Z", "2026-02-30T10:00:00"),
            valid.replace(PAYMENT_KEY, ""), valid.replace(String.valueOf(PAYMENT_ID), "short")}) {
            assertThat(post(invalid)).hasStatus(HttpStatus.BAD_REQUEST);
        }
        assertThat(count()).isZero();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic")).when(inbox).insert(any());
        var unavailable = post(valid);
        assertThat(unavailable).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_WEBHOOK_004");
    }

    @Test
    void confirmationDuringGetDiscardsStaleResponseAndFreshDoneCompletesWithoutSecondEvent() {
        Payment started = save(inProgress()); receive();
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            paymentProcessor.recordApprovalResult(started, new PgApprovalOutcome.Approved(approval(krw(129_000))));
            return new PgInquiryQueryResult.Approved(approval(krw(129_000)));
        });
        worker.process();
        assertThat(read().status()).isEqualTo(PaymentStatus.DONE);
        assertThat(state()).isEqualTo(State.RETRY);
        assertThat(outbox()).isOne();
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        reset(gateway);
        when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(new PgInquiryQueryResult.Approved(approval(krw(129_000))));
        worker.process();
        assertThat(state()).isEqualTo(State.COMPLETED);
        assertThat(outbox()).isOne();
    }

    @Test
    void approvedReconcilesMutableLanesAndHoldsFailedOrBoundAbortedLateApproval() {
        for (Payment lane : new Payment[]{unknown(), reverted(), failed(), reverted().abortAfterInquiry(
            new PaymentInquiry.Failed(PAYMENT_KEY, "EXPIRED", null), PaymentAbortReason.RECOVERY_NOT_APPROVED)}) {
            clean(); save(lane); receive(); worker.process();
            boolean late = lane.status() == PaymentStatus.FAILED || lane.status() == PaymentStatus.ABORTED;
            assertThat(read().status()).isEqualTo(late ? PaymentStatus.LATE_APPROVED : PaymentStatus.DONE);
            assertThat(state()).isEqualTo(late ? State.MANUAL_REVIEW : State.COMPLETED);
            assertThat(outbox()).isEqualTo(late ? 0 : 1);
        }
    }

    @Test
    void mismatchAndCanceledUnknownKeepPaymentUnchangedForManualReview() {
        for (PgInquiryQueryResult result : new PgInquiryQueryResult[]{
            new PgInquiryQueryResult.Approved(approval(krw(1))),
            new PgInquiryQueryResult.Approved(approval(Money.of(129_000, CurrencyCode.USD))),
            new PgInquiryQueryResult.Approved(approval("other-key", krw(129_000))),
            new PgInquiryQueryResult.Pending("other-key"),
            new PgInquiryQueryResult.Unknown(PaymentUnknownReason.MANUAL_REVIEW),
            new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT)}) {
            clean(); Payment original = save(done()); receive();
            when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(result);
            worker.process();
            assertThat(read()).isEqualTo(original);
            assertThat(state()).isEqualTo(State.MANUAL_REVIEW);
            assertThat(outbox()).isZero();
        }
    }

    @Test
    void failedInquiryOnlyResolvesMutableLanesNeverDowngradesDone() {
        for (Payment lane : new Payment[]{inProgress(), unknown(), reverted(), done()}) {
            clean(); Payment original = save(lane); receive();
            when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(new PgInquiryQueryResult.Failed(PAYMENT_KEY, "EXPIRED", null));
            worker.process();
            if (lane.status() == PaymentStatus.DONE) {
                assertThat(read()).isEqualTo(original); assertThat(state()).isEqualTo(State.MANUAL_REVIEW);
            } else {
                assertThat(read().status()).isIn(PaymentStatus.FAILED, PaymentStatus.ABORTED); assertThat(state()).isEqualTo(State.COMPLETED);
            }
            assertThat(outbox()).isZero();
        }
    }

    @Test
    void timeoutNotFoundPendingRetryBoundedThenManual() {
        save(inProgress()); receive();
        PgInquiryQueryResult[] outcomes = {new PgInquiryQueryResult.Unknown(PaymentUnknownReason.TIMEOUT), new PgInquiryQueryResult.NotFound(),
            new PgInquiryQueryResult.Pending(PAYMENT_KEY), new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR)};
        for (int attempt = 0; attempt < 6; attempt++) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(attempt * 301L));
            when(gateway.inquireByPaymentKey(anyString(), anyString())).thenReturn(outcomes[attempt % outcomes.length]);
            worker.process();
            assertThat(state()).isEqualTo(attempt == 5 ? State.MANUAL_REVIEW : State.RETRY);
        }
        worker.process();
        verify(gateway, times(6)).inquireByPaymentKey(anyString(), anyString());
        assertThat(read().status()).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    void leaseExpiryReclaimRejectsOldWorkerAndKeepsOneEvent() {
        save(inProgress()); receive();
        var old = rows.claim(id()).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        var fresh = rows.claim(id()).orElseThrow();
        assertThatThrownBy(() -> rows.reconcile(old, new PgInquiryQueryResult.Approved(approval(krw(129_000)))))
            .isInstanceOf(PaymentWebhookConflictException.class);
        assertThat(outbox()).isZero();
        rows.reconcile(fresh, new PgInquiryQueryResult.Approved(approval(krw(129_000))));
        assertThat(state()).isEqualTo(State.COMPLETED);
        assertThat(outbox()).isOne();
    }

    @Test
    void finalInboxCasFailureRollsBackPaymentAndActualOutbox() {
        Payment original = save(inProgress()); receive();
        var work = rows.claim(id()).orElseThrow();
        doAnswer(call -> { call.callRealMethod(); entityManager.flush(); when(clock.instant()).thenReturn(NOW.plusSeconds(61)); return null; })
            .when(events).approved(any());
        assertThatThrownBy(() -> rows.reconcile(work, new PgInquiryQueryResult.Approved(approval(krw(129_000)))))
            .isInstanceOf(PaymentWebhookConflictException.class);
        assertThat(read()).isEqualTo(original);
        assertThat(outbox()).isZero();
        assertThat(state()).isEqualTo(State.PROCESSING);
    }

    @Test
    void outboxFailureRetainsDurableLeaseAndRestartResumesOnce() {
        Payment original = save(inProgress()); receive();
        doAnswer(call -> { call.callRealMethod(); entityManager.flush(); throw new IllegalStateException("synthetic after insert"); })
            .when(events).approved(any());
        worker.process();
        assertThat(read()).isEqualTo(original);
        assertThat(state()).isEqualTo(State.PROCESSING);
        assertThat(outbox()).isZero();
        reset(events);
        when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        worker.process();
        assertThat(read().status()).isEqualTo(PaymentStatus.DONE);
        assertThat(state()).isEqualTo(State.COMPLETED);
        assertThat(outbox()).isOne();
    }

    @Test
    void maxAgeExpiredWorkHoldsWithoutPgAndFacadeRejectsCallerTransaction() {
        save(inProgress()); receive();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(24)));
        worker.process();
        assertThat(state()).isEqualTo(State.MANUAL_REVIEW);
        verifyNoInteractions(gateway);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> worker.process()))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void repeatedExpiredClaimsDoNotExceedMaxAttemptsPgBudget() {
        save(inProgress()); receive();
        for (int attempt = 0; attempt < 6; attempt++) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(attempt * 61L));
            assertThat(rows.claim(id())).isPresent();
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(6 * 61L));
        worker.process();
        assertThat(state()).isEqualTo(State.MANUAL_REVIEW);
        verifyNoInteractions(gateway);
    }

    @Test
    void unsupportedLocalCanceledPartialAndLateStayUnchanged() {
        for (Payment lane : new Payment[]{done().recordCancellation(krw(129_000)), done().recordCancellation(krw(1)),
            failed().recordLateApproval(approval(krw(129_000)))}) {
            clean(); Payment original = save(lane); receive(); worker.process();
            assertThat(read()).isEqualTo(original);
            assertThat(state()).isEqualTo(State.MANUAL_REVIEW);
            assertThat(outbox()).isZero();
        }
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String body) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
    private static String payload(String createdAt) {
        return """
            {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"%s","data":{"orderId":"%s","paymentKey":"%s",
             "status":"DONE","totalAmount":1,"currency":"USD","orderName":"ignored","card":{"number":"ignored"}}}
            """.formatted(createdAt, PAYMENT_ID, PAYMENT_KEY);
    }
    private static PaymentWebhookCommand command(String createdAt) {
        return new PaymentWebhookCommand("PAYMENT_STATUS_CHANGED", createdAt, String.valueOf(PAYMENT_ID), PAYMENT_KEY);
    }
    private void receive() { receiver.receive(command("2026-10-21T01:01:00Z")); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private Payment save(Payment payment) { return tx().execute(status -> payments.save(payment)); }
    private Payment read() { return tx().execute(status -> payments.findById(PAYMENT_ID).orElseThrow()); }
    private long count() { return jdbc.queryForObject("select count(*) from payment.payment_webhook_inbox", Long.class); }
    private long outbox() { return jdbc.queryForObject("select count(*) from payment.outbox", Long.class); }
    private long id() { return jdbc.queryForObject("select id from payment.payment_webhook_inbox", Long.class); }
    private State state() { return State.valueOf(jdbc.queryForObject("select state from payment.payment_webhook_inbox", String.class)); }
}
