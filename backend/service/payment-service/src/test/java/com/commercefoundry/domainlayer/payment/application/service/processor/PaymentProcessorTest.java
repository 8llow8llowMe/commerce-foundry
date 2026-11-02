package com.commercefoundry.domainlayer.payment.application.service.processor;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVAL_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_NAME;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.STARTED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.approval;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.payment.adapter.out.event.PaymentApprovedMessage;
import com.commercefoundry.domainlayer.payment.adapter.out.event.PaymentEventAdapter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.model.PayableOrder;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 시도 조회 · 전이 저장 — 소유자 확인(404 PAYMENT_001), 새 시도 · 교체(같은 트랜잭션에서 ABORTED + 새 READY), 승인 시작 · 결과 기록의 전이 저장,
 * DONE 기록과 같은 트랜잭션의 {@code payment-approved} 발행(#308).
 *
 * <p>발행은 실제 {@link PaymentEventAdapter} · {@link EventPublisher} 와 가짜 outbox({@link InMemoryOutboxStore} — 커밋 때만 보인다)로 본다. 결과 기록은
 * {@code @Transactional} 경계를 {@link TransactionTemplate}({@link NoOpTransactionManager})으로 흉내 내고, 저장소 목도 커밋 때만 {@link #committed} 에 남긴다 —
 * 저장 · 발행 중 하나가 실패하면 둘 다 남지 않는 것을 본다. 실제 DB 커밋 · outbox 건수는 {@code PaymentConfirmWebIntegrationTest} 가 본다.
 */
class PaymentProcessorTest {

    private static final long NEW_PAYMENT_ID = 9_000_000_777L;
    private static final PayableOrder ORDER = new PayableOrder(ORDER_ID, krw(129_000), ORDER_NAME, Instant.parse("2026-10-21T02:00:00Z"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T01:00:04Z"), ZoneOffset.UTC);

    private PaymentRepositoryPort repository;
    private InMemoryOutboxStore outbox;
    private SnowflakeIdGenerator ids;
    private PaymentProcessor processor;
    private final TransactionTemplate transaction = new TransactionTemplate(new NoOpTransactionManager());
    /** 커밋된 저장 — 트랜잭션 안의 저장은 커밋 때만 여기 남는다. */
    private final List<Payment> committed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepositoryPort.class);
        ids = mock(SnowflakeIdGenerator.class);
        given(ids.generateId()).willReturn(NEW_PAYMENT_ID);
        given(repository.save(any())).willAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        committed.add(payment);
                    }
                });
            }
            return payment;
        });
        outbox = new InMemoryOutboxStore();
        processor = processorWith(() -> outbox);
    }

    private PaymentProcessor processorWith(Supplier<OutboxStore> store) {
        EventPublisher publisher = new EventPublisher(store, new EventEnvelopeCodec(65_536), PaymentApprovedMessage.PRODUCER_SERVICE, CLOCK);
        return new PaymentProcessor(repository, new PaymentEventAdapter(publisher), ids);
    }

    /** {@code recordApprovalResult} 의 {@code @Transactional} 경계를 흉내 낸다. */
    private Payment record(PaymentProcessor target, Payment inProgress, PgApprovalOutcome outcome) {
        return transaction.execute(status -> target.recordApprovalResult(inProgress, outcome));
    }

    @Test
    @DisplayName("소유자가 아니거나 없으면 404 PAYMENT_001 — 아이디 · PG 주문번호 조회 모두")
    void ownership() {
        given(repository.findById(PAYMENT_ID)).willReturn(Optional.of(PaymentFixtures.ready()));
        given(repository.findByPgOrderId(Long.toString(PAYMENT_ID))).willReturn(Optional.of(PaymentFixtures.ready()));

        assertThat(processor.getOwned(PAYMENT_ID, MEMBER_ID).id()).isEqualTo(PAYMENT_ID);
        assertNotFound(() -> processor.getOwned(PAYMENT_ID, MEMBER_ID + 1));
        assertNotFound(() -> processor.getOwned(PAYMENT_ID + 1, MEMBER_ID));
        assertThat(processor.getOwnedByPgOrderId(MEMBER_ID, Long.toString(PAYMENT_ID)).id()).isEqualTo(PAYMENT_ID);
        assertNotFound(() -> processor.getOwnedByPgOrderId(MEMBER_ID + 1, Long.toString(PAYMENT_ID)));
        assertNotFound(() -> processor.getOwnedByPgOrderId(MEMBER_ID, "999999"));
    }

    @Test
    @DisplayName("새 결제 시도는 Snowflake 아이디(= PG 주문번호) · 주문 금액 · 주문명으로 READY 를 저장한다")
    void prepares() {
        Payment created = processor.prepare(MEMBER_ID, ORDER);

        assertThat(created.id()).isEqualTo(NEW_PAYMENT_ID);
        assertThat(created.pgOrderId()).isEqualTo(Long.toString(NEW_PAYMENT_ID));
        assertThat(created.status()).isEqualTo(PaymentStatus.READY);
        assertThat(created.amount()).isEqualTo(krw(129_000));
        assertThat(created.memberId()).isEqualTo(MEMBER_ID);
        verify(repository).save(created);
    }

    @Test
    @DisplayName("교체 — 기존 READY 를 ABORTED(사유)로 먼저 저장하고 새 READY 를 저장한다")
    void replaces() {
        Payment ready = PaymentFixtures.ready();

        Payment created = processor.replace(ready, PaymentAbortReason.AMOUNT_CHANGED, MEMBER_ID, ORDER);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        InOrder order = inOrder(repository);
        order.verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).status()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(saved.getAllValues().get(0).abortReason()).isEqualTo(PaymentAbortReason.AMOUNT_CHANGED);
        assertThat(saved.getAllValues().get(1)).isEqualTo(created);
        assertThat(created.id()).isEqualTo(NEW_PAYMENT_ID);
    }

    @Test
    @DisplayName("승인 시작 — 결제키 · 고객 멱등 키 · 시작 시각을 남긴 IN_PROGRESS 를 저장한다")
    void startsApproval() {
        Payment inProgress = processor.startApproval(PaymentFixtures.ready(), PAYMENT_KEY, APPROVAL_KEY, STARTED_AT);

        assertThat(inProgress.status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(inProgress.approvalIdempotencyKey()).isEqualTo(APPROVAL_KEY);
        verify(repository).save(inProgress);
    }

    @Test
    @DisplayName("결과 기록 — Approved → DONE, Rejected → FAILED, Unknown → UNKNOWN(이유), NotSent → READY(결제키 유지)")
    void recordsOutcomes() {
        Payment inProgress = PaymentFixtures.inProgress();

        assertThat(record(processor, inProgress, new PgApprovalOutcome.Approved(approval(krw(129_000)))).status()).isEqualTo(PaymentStatus.DONE);
        Payment failed = record(processor, inProgress, new PgApprovalOutcome.Rejected("REJECT_CARD_COMPANY", "거절"));
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("REJECT_CARD_COMPANY");
        Payment unknown = record(processor, inProgress, new PgApprovalOutcome.Unknown(PaymentUnknownReason.TIMEOUT));
        assertThat(unknown.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(unknown.unknownReason()).isEqualTo(PaymentUnknownReason.TIMEOUT);
        Payment reverted = record(processor, inProgress, new PgApprovalOutcome.NotSent("CIRCUIT_OPEN"));
        assertThat(reverted.status()).isEqualTo(PaymentStatus.READY);
        assertThat(reverted.paymentKey()).isEqualTo(PAYMENT_KEY);
        verify(repository, org.mockito.Mockito.times(4)).save(any());
    }

    @Test
    @DisplayName("DONE 기록은 같은 트랜잭션에서 payment-approved 한 건을 outbox 에 남긴다 — 키는 주문 아이디, 저장 뒤 발행이고 둘이 함께 커밋된다")
    void approvedPublishesOneEventWithDone() {
        Payment done = record(processor, PaymentFixtures.inProgress(), new PgApprovalOutcome.Approved(approval(krw(129_000))));

        assertThat(committed).containsExactly(done);
        List<OutboxRow> rows = outbox.rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().eventType()).isEqualTo(PaymentApprovedMessage.EVENT_TYPE);
        assertThat(rows.getFirst().aggregateType()).isEqualTo(PaymentApprovedMessage.AGGREGATE_TYPE);
        assertThat(rows.getFirst().aggregateId()).isEqualTo(Long.toString(ORDER_ID));
        assertThat(rows.getFirst().envelopeJson()).contains("\"paymentId\":\"" + PaymentFixtures.PAYMENT_ID + "\"");
    }

    @Test
    @DisplayName("DONE 이 아니면 발행하지 않는다 — FAILED · UNKNOWN · READY 되돌림 · 금액 불일치(UNKNOWN APPROVAL_MISMATCH) 모두 outbox 0건")
    void nonDoneOutcomesDoNotPublish() {
        Payment inProgress = PaymentFixtures.inProgress();

        record(processor, inProgress, new PgApprovalOutcome.Rejected("REJECT_CARD_COMPANY", "거절"));
        record(processor, inProgress, new PgApprovalOutcome.Unknown(PaymentUnknownReason.TIMEOUT));
        record(processor, inProgress, new PgApprovalOutcome.NotSent("CIRCUIT_OPEN"));
        record(processor, inProgress, new PgApprovalOutcome.Approved(approval(krw(1_000))));

        assertThat(committed).extracting(Payment::status)
            .containsExactly(PaymentStatus.FAILED, PaymentStatus.UNKNOWN, PaymentStatus.READY, PaymentStatus.UNKNOWN);
        assertThat(outbox.rows()).isEmpty();
    }

    @Test
    @DisplayName("발행이 실패하면 DONE 저장도 함께 롤백된다 — 예외가 그대로 올라가고(Facade 가 500 PAYMENT_013) 저장 · outbox 둘 다 남지 않는다")
    void publishFailureRollsBackDone() {
        OutboxStore failing = mock(OutboxStore.class);
        doThrow(new IllegalStateException("outbox unavailable")).when(failing).append(any());
        PaymentProcessor failingProcessor = processorWith(() -> failing);

        assertThatThrownBy(() -> record(failingProcessor, PaymentFixtures.inProgress(), new PgApprovalOutcome.Approved(approval(krw(129_000)))))
            .isInstanceOf(IllegalStateException.class);

        InOrder order = inOrder(repository, failing);
        order.verify(repository).save(any());
        order.verify(failing).append(any());
        assertThat(committed).isEmpty();
    }

    @Test
    @DisplayName("이미 DONE 인 결제의 멱등 전이(같은 인스턴스)는 저장도 발행도 하지 않는다 — 승인 이벤트가 두 번 나가지 않는다")
    void saveApprovedSkipsNoOp() {
        Payment done = PaymentFixtures.done();

        Payment result = transaction.execute(status -> processor.saveApproved(done, done));

        assertThat(result).isSameAs(done);
        verify(repository, never()).save(any());
        assertThat(outbox.rows()).isEmpty();
    }

    @Test
    @DisplayName("DONE 저장이 실패하면 발행하지 않는다 — outbox 0건")
    void saveFailureDoesNotPublish() {
        given(repository.save(any())).willThrow(new IllegalStateException("db unavailable"));

        assertThatThrownBy(() -> record(processor, PaymentFixtures.inProgress(), new PgApprovalOutcome.Approved(approval(krw(129_000)))))
            .isInstanceOf(IllegalStateException.class);

        assertThat(outbox.rows()).isEmpty();
    }

    @Test
    @DisplayName("결과 기록은 쓰기 트랜잭션이다 — 발행기는 트랜잭션 밖 · 읽기 전용 트랜잭션에서 저장하지 않는다")
    void recordApprovalResultIsWriteTransaction() throws NoSuchMethodException {
        Transactional transactional = PaymentProcessor.class.getMethod("recordApprovalResult", Payment.class, PgApprovalOutcome.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    @Test
    @DisplayName("승인 결과의 금액이 어긋나면 DONE 으로 기록하지 않고 UNKNOWN(APPROVAL_MISMATCH) 으로 저장한다")
    void mismatchedApprovalIsHeldAsUnknown() {
        Payment recorded = record(processor, PaymentFixtures.inProgress(), new PgApprovalOutcome.Approved(approval(krw(1_000))));

        assertThat(recorded.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(recorded.unknownReason()).isEqualTo(PaymentUnknownReason.APPROVAL_MISMATCH);
        verify(repository).save(recorded);
    }

    @Test
    @DisplayName("조회만 하는 메서드는 저장하지 않는다")
    void readsDoNotSave() {
        given(repository.findActiveByOrderId(ORDER_ID)).willReturn(Optional.of(PaymentFixtures.ready()));
        given(repository.findByApprovalIdempotencyKey(MEMBER_ID, APPROVAL_KEY)).willReturn(Optional.empty());

        assertThat(processor.findActive(ORDER_ID)).isPresent();
        assertThat(processor.findByApprovalKey(MEMBER_ID, APPROVAL_KEY)).isEmpty();
        verify(repository, never()).save(any());
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.NOT_FOUND_PAYMENT));
    }
}
