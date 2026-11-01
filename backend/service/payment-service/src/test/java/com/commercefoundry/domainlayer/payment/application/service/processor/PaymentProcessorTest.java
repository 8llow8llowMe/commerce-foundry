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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 결제 시도 조회 · 전이 저장 — 소유자 확인(404 PAYMENT_001), 새 시도 · 교체(같은 트랜잭션에서 ABORTED + 새 READY), 승인 시작 · 결과 기록의 전이 저장.
 */
class PaymentProcessorTest {

    private static final long NEW_PAYMENT_ID = 9_000_000_777L;
    private static final PayableOrder ORDER = new PayableOrder(ORDER_ID, krw(129_000), ORDER_NAME, Instant.parse("2026-10-21T02:00:00Z"));

    private PaymentRepositoryPort repository;
    private PaymentProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepositoryPort.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        given(ids.generateId()).willReturn(NEW_PAYMENT_ID);
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        processor = new PaymentProcessor(repository, ids);
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

        assertThat(processor.recordApprovalResult(inProgress, new PgApprovalOutcome.Approved(approval(krw(129_000)))).status()).isEqualTo(PaymentStatus.DONE);
        Payment failed = processor.recordApprovalResult(inProgress, new PgApprovalOutcome.Rejected("REJECT_CARD_COMPANY", "거절"));
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("REJECT_CARD_COMPANY");
        Payment unknown = processor.recordApprovalResult(inProgress, new PgApprovalOutcome.Unknown(PaymentUnknownReason.TIMEOUT));
        assertThat(unknown.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(unknown.unknownReason()).isEqualTo(PaymentUnknownReason.TIMEOUT);
        Payment reverted = processor.recordApprovalResult(inProgress, new PgApprovalOutcome.NotSent("CIRCUIT_OPEN"));
        assertThat(reverted.status()).isEqualTo(PaymentStatus.READY);
        assertThat(reverted.paymentKey()).isEqualTo(PAYMENT_KEY);
        verify(repository, org.mockito.Mockito.times(4)).save(any());
    }

    @Test
    @DisplayName("승인 결과의 금액이 어긋나면 DONE 으로 기록하지 않고 UNKNOWN(APPROVAL_MISMATCH) 으로 저장한다")
    void mismatchedApprovalIsHeldAsUnknown() {
        Payment recorded = processor.recordApprovalResult(PaymentFixtures.inProgress(), new PgApprovalOutcome.Approved(approval(krw(1_000))));

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
