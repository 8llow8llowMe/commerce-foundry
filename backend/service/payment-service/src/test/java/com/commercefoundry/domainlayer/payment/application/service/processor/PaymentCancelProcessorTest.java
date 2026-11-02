package com.commercefoundry.domainlayer.payment.application.service.processor;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class PaymentCancelProcessorTest {
    private final PaymentCancelPersistencePort cancelPort = mock(PaymentCancelPersistencePort.class);
    private final PaymentRepositoryPort paymentPort = mock(PaymentRepositoryPort.class);
    private final PaymentCancelProcessor processor = new PaymentCancelProcessor(cancelPort, paymentPort, Clock.systemUTC());
    private final Payment payment = PaymentFixtures.done();

    @Test
    void reservesSameClaimAndAmountWithoutDuplicateLedgerRow() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(pending));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));

        var prepared = processor.prepare(payment.id(), 42, krw(10_000));

        assertThat(prepared.cancel()).isEqualTo(pending);
        assertThat(prepared.cumulative()).isEqualTo(krw(10_000));
        verifyNoInteractions(paymentPort);
    }

    @Test
    void rejectsSameClaimWithAnotherAmountBeforePgCall() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));

        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(9_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_KEY_REUSED));
    }

    @Test
    void rejectsOverRefundAndAnotherPendingClaim() {
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(
            new PaymentCancel(41, payment.id(), krw(120_000), PaymentCancel.Status.SUCCEEDED)));
        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(10_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_AMOUNT_INVALID));

        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(
            new PaymentCancel(43, payment.id(), krw(1_000), PaymentCancel.Status.PENDING)));
        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(1_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
    }

    @Test
    void confirmedCancellationSavesPaymentAndLedgerTogether() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(129_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));
        given(cancelPort.findByPaymentId(payment.id())).willReturn(List.of(pending));
        given(paymentPort.save(payment.recordCancellation(krw(129_000)))).willReturn(payment.recordCancellation(krw(129_000)));

        Payment result = processor.complete(payment.id(), 42);

        assertThat(result.status()).isEqualTo(PaymentStatus.CANCELED);
        verify(cancelPort).save(pending.succeeded());
    }

    @Test
    void permanentRejectionMarksLedgerAndSameClaimCannotCallPgAgain() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(cancelPort.lockPayment(payment.id())).willReturn(Optional.of(payment));
        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending));
        processor.reject(payment.id(), 42);
        verify(cancelPort).save(pending.rejected());

        given(cancelPort.findByClaimId(42)).willReturn(Optional.of(pending.rejected()));
        assertThatThrownBy(() -> processor.prepare(payment.id(), 42, krw(10_000)))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
    }
}
