package com.commercefoundry.domainlayer.payment;

import static com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason.*;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.OrderPaymentReplyFailureAdapter;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand.Kind;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.port.out.*;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.application.service.OrderPaymentReplyEventFacade;
import com.commercefoundry.domainlayer.payment.application.service.OrderPaymentReplyObservation;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(OutputCaptureExtension.class)
class OrderPaymentReplyUnitTest {
    static final String CANARY = "private-sql-key-987654321";
    final OrderPaymentResultQueryPort query = mock(OrderPaymentResultQueryPort.class);
    final PaymentRepositoryPort payments = mock(PaymentRepositoryPort.class);
    final PaymentCancelPersistencePort cancels = mock(PaymentCancelPersistencePort.class);
    final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    final PaymentCancelPolicy policy = mock(PaymentCancelPolicy.class);
    final OrderPaymentReplyFailureAdapter failures = new OrderPaymentReplyFailureAdapter();
    final OrderPaymentReplyProcessor processor = new OrderPaymentReplyProcessor(query, payments, cancels, inbox,
        new TopicNames("test", "payment"), Clock.systemUTC(), failures, policy);
    final OrderPaymentReplyEventFacade facade = new OrderPaymentReplyEventFacade(processor, new OrderPaymentReplyObservation());

    static OrderPaymentReplyCommand command(Result result) {
        return new OrderPaymentReplyCommand(UUID.randomUUID(), result == Result.PAID ? Kind.PAID : Kind.REJECTED, PAYMENT_ID, ORDER_ID,
            result == Result.PAID ? null : result.name().substring(9));
    }
    static OrderPaymentResultQueryResult source(Result result) {
        return new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, result, krw(129000), APPROVED_AT, result == Result.PAID ? 42L : null);
    }
    @Test
    void rejectsMalformedCommandsAndDirectNullEntryWithoutLeakingInput() {
        for (String code : List.of("", "UNKNOWN", "order_canceled", CANARY)) {
            assertThatIllegalArgumentException().isThrownBy(() -> new OrderPaymentReplyCommand(UUID.randomUUID(), Kind.REJECTED, PAYMENT_ID, ORDER_ID, code));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPaymentReplyCommand(null, Kind.PAID, 1, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPaymentReplyCommand(UUID.randomUUID(), null, 1, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPaymentReplyCommand(UUID.randomUUID(), Kind.PAID, 0, -1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPaymentReplyCommand(UUID.randomUUID(), Kind.PAID, 1, 1, CANARY));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPaymentReplyCommand(UUID.randomUUID(), Kind.REJECTED, 1, 1, null));
        assertThatThrownBy(() -> facade.handle(null)).isInstanceOfSatisfying(OrderPaymentReplyException.class, e -> assertThat(e.getReason()).isEqualTo(INVALID_COMMAND));
        assertThatThrownBy(() -> processor.fetchSource(null)).isInstanceOf(OrderPaymentReplyException.class);
        assertThatThrownBy(() -> processor.record(null, null)).isInstanceOf(OrderPaymentReplyException.class);
        verifyNoInteractions(query, payments, inbox, policy);
    }
    @ParameterizedTest @EnumSource(Result.class)
    void validatesSevenResultCombinationsAndStoresOnlyChangedPayment(Result result) {
        when(query.query(PAYMENT_ID)).thenReturn(source(result));
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(done()));
        assertThat(facade.handle(command(result)).changed()).isTrue();
        verify(payments).save(result == Result.PAID ? done().markOrderSynced() : done().markOrderSyncFailed(result.name().substring(9)));
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(result == Result.PAID ? done().markOrderSynced()
            : done().markOrderSyncFailed(result.name().substring(9))));
        clearInvocations(payments, policy);
        assertThat(facade.handle(command(result)).changed()).isFalse();
        verify(payments, never()).save(any());
        verifyNoInteractions(policy);
    }
    @Test
    void fullCancellationAndZeroBalanceNeverAuthorizeHookAndLateApprovalIsRejected() {
        when(query.query(PAYMENT_ID)).thenReturn(source(Result.REJECTED_ORDER_CANCELED));
        for (Payment payment : List.of(done().recordCancellation(krw(129000)), done(), done().markOrderSynced())) {
            when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(cancels.findByPaymentId(PAYMENT_ID)).thenReturn(List.of(new PaymentCancel(42, PAYMENT_ID, krw(129000), PaymentCancel.Status.SUCCEEDED)));
            assertThat(facade.handle(command(Result.REJECTED_ORDER_CANCELED)).cancelCandidate()).isFalse();
        }
        when(query.query(PAYMENT_ID)).thenReturn(new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID,
            Result.REJECTED_ORDER_CANCELED, krw(129000), null, null));
        for (Payment payment : List.of(ready(), inProgress(), failed(), unknown(), aborted(), lateApproved(), lateApproved().recordCancellation(krw(129000)))) {
            when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
            assertThatThrownBy(() -> facade.handle(command(Result.REJECTED_ORDER_CANCELED)))
                .isInstanceOfSatisfying(OrderPaymentReplyException.class, e -> assertThat(e.getReason()).isEqualTo(INVALID_ORIGIN));
        }
        verifyNoInteractions(policy);
    }
    @Test
    void unwrapsCommitVersionAndExactUniqueButNeverMistakesOtherConstraintsForDuplicates() {
        assertThat(failures.classify(new TransactionSystemException(CANARY, new RollbackException(CANARY, new OptimisticLockException(CANARY)))))
            .isEqualTo(PAYMENT_VERSION);
        assertThat(failures.classify(new RollbackException(CANARY, new PaymentPersistenceConflictException(
            PaymentPersistenceConflictException.Kind.VERSION, PAYMENT_ID, ORDER_ID)))).isEqualTo(PAYMENT_VERSION);
        for (String name : List.of("uk_processed_event_consumer_group_event_id", "uk_processed_event_consumer_group_event_id_extra", "payment_pkey")) {
            for (String state : List.of("23505", "23514", "23502", "22001")) {
                var failure = new TransactionSystemException(CANARY, new RollbackException(CANARY,
                    new ConstraintViolationException(CANARY, new SQLException(CANARY, state), name)));
                assertThat(failures.classify(failure)).isEqualTo(name.endsWith("event_id") && state.equals("23505") ? INBOX_DUPLICATE : STORAGE_CONTRACT);
            }
        }
        assertThat(failures.classify(new TransactionSystemException(CANARY))).isEqualTo(STORAGE_UNAVAILABLE);
        var original = new TransactionSystemException(CANARY);
        original.initApplicationException(new OptimisticLockException(CANARY));
        assertThat(failures.classify(new RollbackException(CANARY, original))).isEqualTo(PAYMENT_VERSION);
        assertThat(failures.classify(new TransactionSystemException(CANARY, new SQLException(CANARY, "22001")))).isEqualTo(STORAGE_CONTRACT);
        var cycle = new RuntimeException(CANARY);
        var other = new RuntimeException(CANARY, cycle);
        cycle.initCause(other);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
            () -> assertThat(failures.classify(cycle)).isEqualTo(STORAGE_UNAVAILABLE));
    }
    @Test
    void localIdentityTotalCurrencyTimestampAndSourceShapeMismatchesNeverWrite() {
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(done()));
        var valid = source(Result.PAID);
        for (var invalid : List.of(
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID + 1, MEMBER_ID, Result.PAID, valid.amount(), APPROVED_AT, 42L),
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID + 1, Result.PAID, valid.amount(), APPROVED_AT, 42L),
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID, krw(128999), APPROVED_AT, 42L),
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID,
                com.commercefoundry.shared.commerce.Money.of(129000, com.commercefoundry.shared.commerce.CurrencyCode.USD), APPROVED_AT, 42L),
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID, valid.amount(), APPROVED_AT.plusNanos(1000), 42L),
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID, valid.amount(), null, 42L),
            new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID, valid.amount(), APPROVED_AT, null))) {
            when(query.query(PAYMENT_ID)).thenReturn(invalid);
            assertThatThrownBy(() -> facade.handle(command(Result.PAID))).isInstanceOf(OrderPaymentReplyException.class);
        }
        when(query.query(PAYMENT_ID)).thenReturn(valid);
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> facade.handle(command(Result.PAID))).isInstanceOf(OrderPaymentReplyException.class);
        verify(payments, never()).save(any());
        verify(inbox, never()).record(any(), any(), any(), any());
        verifyNoInteractions(policy);
    }
    @Test
    void storageAndHookFailuresAreSanitizedWithPositiveLogControl(CapturedOutput output) {
        when(query.query(PAYMENT_ID)).thenReturn(source(Result.REJECTED_ORDER_CANCELED));
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(done()));
        doThrow(new IllegalStateException(CANARY)).when(policy).onRejected(PAYMENT_ID);
        var event = command(Result.REJECTED_ORDER_CANCELED);
        assertThat(facade.handle(event).rejectionRecorded()).isTrue();
        when(payments.findById(PAYMENT_ID)).thenThrow(new TransactionSystemException(CANARY, new OptimisticLockException(CANARY)));
        assertThatThrownBy(() -> facade.handle(command(Result.REJECTED_ORDER_CANCELED))).isInstanceOfSatisfying(OrderPaymentReplyException.class, e -> {
            assertThat(e.getReason()).isEqualTo(PAYMENT_VERSION);
            assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).isEmpty();
            assertThat(e.getMessage()).doesNotContain(CANARY, Long.toString(PAYMENT_ID));
        });
        assertThat(output.getAll()).contains("HOOK_FAILED", "PAYMENT_VERSION")
            .doesNotContain(CANARY, event.eventId().toString(), Long.toString(PAYMENT_ID), Long.toString(ORDER_ID), PAYMENT_KEY, "Exception");
        verify(policy).onRejected(PAYMENT_ID);
    }
    @ParameterizedTest @CsvSource({"NOT_READY,SOURCE_NOT_READY,true", "UNAVAILABLE,SOURCE_UNAVAILABLE,true", "UNAUTHORIZED,SOURCE_UNAUTHORIZED,true",
        "REJECTED,SOURCE_CONTRACT,false", "CONTRACT_VIOLATION,SOURCE_CONTRACT,false", "RAW,SOURCE_UNAVAILABLE,true"})
    void sanitizesRemoteFailuresAndLogsPositiveControlWithoutIdentifiersOrCause(
        String failure, OrderPaymentReplyException.Reason expected, boolean retryable, CapturedOutput output
    ) {
        RuntimeException injected = failure.equals("RAW") ? new IllegalStateException(CANARY)
            : new OrderPaymentResultQueryException(OrderPaymentResultQueryException.Failure.valueOf(failure));
        injected.initCause(new IllegalStateException(CANARY));
        injected.addSuppressed(new IllegalStateException(CANARY));
        when(query.query(PAYMENT_ID)).thenThrow(injected);
        assertThatThrownBy(() -> facade.handle(command(Result.PAID))).isInstanceOfSatisfying(OrderPaymentReplyException.class, e -> {
            assertThat(e.getReason()).isEqualTo(expected);
            assertThat(e.getReason().retryable()).isEqualTo(retryable);
            assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).isEmpty();
            assertThat(e.getMessage()).doesNotContain(CANARY, Long.toString(PAYMENT_ID));
        });
        assertThat(output.getAll()).contains("Order payment reply: SOURCE_").doesNotContain(CANARY, Long.toString(PAYMENT_ID), "Exception");
        verifyNoInteractions(policy, payments);
    }
    @ParameterizedTest @EnumSource(value = Result.class, names = "PAID", mode = EnumSource.Mode.EXCLUDE)
    void rejectionAllowsAbsentApprovalTimeAndOnlyOrderCanceledMayReferenceReservation(Result result) {
        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(done()));
        when(query.query(PAYMENT_ID)).thenReturn(new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, result, krw(129000), null, null));
        assertThat(facade.handle(command(result)).rejectionRecorded()).isTrue();
        when(query.query(PAYMENT_ID)).thenReturn(new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, result, krw(129000), null, 42L));
        if (result == Result.REJECTED_ORDER_CANCELED) {
            assertThat(facade.handle(command(result)).rejectionRecorded()).isTrue();
        } else {
            assertThatThrownBy(() -> facade.handle(command(result))).isInstanceOfSatisfying(OrderPaymentReplyException.class,
                e -> assertThat(e.getReason()).isEqualTo(SOURCE_CONTRACT));
        }
    }
}
