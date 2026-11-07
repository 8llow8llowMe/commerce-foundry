package com.commercefoundry.domainlayer.payment.application.service;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import org.junit.jupiter.api.Test;

class PaymentCancellationFacadeTest {
    private final PaymentCancelProcessor processor = mock(PaymentCancelProcessor.class);
    private final PaymentGatewayProcessor gateway = mock(PaymentGatewayProcessor.class);
    private final PaymentCancellationFacade facade = new PaymentCancellationFacade(processor, gateway);
    private final Payment payment = PaymentFixtures.done();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1, -9000000001L})
    void nonpositiveClaimWithMatchingHeaderRejectsBeforeDbAndPg(long claimId) {
        assertThatThrownBy(() -> facade.cancel(payment.id(), claimId, 10000, krw(1).currency(), Long.toString(claimId)))
            .isInstanceOf(PaymentException.class);
        verifyNoInteractions(processor, gateway);
    }

    @Test
    void successfulPgCancelRecordsResultAndResponds() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(129_000), PaymentCancel.Status.PENDING);
        given(processor.prepare(payment.id(), 42, krw(129_000))).willReturn(new PaymentCancelProcessor.Prepared(payment, pending, krw(129_000)));
        given(gateway.cancel(payment, 42, pending.amount(), krw(129_000))).willReturn(PaymentGatewayProcessor.CancelOutcome.SUCCEEDED);
        given(processor.complete(payment.id(), 42)).willReturn(payment.recordCancellation(krw(129_000)));

        var result = facade.cancel(payment.id(), 42, 129_000, krw(1).currency(), "42");

        assertThat(result.status()).isEqualTo("CANCELED");
    }

    @Test
    void unknownPgResultKeepsLedgerPending() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(processor.prepare(payment.id(), 42, krw(10_000))).willReturn(new PaymentCancelProcessor.Prepared(payment, pending, krw(10_000)));
        given(gateway.cancel(payment, 42, pending.amount(), krw(10_000))).willReturn(PaymentGatewayProcessor.CancelOutcome.UNKNOWN);

        assertThatThrownBy(() -> facade.cancel(payment.id(), 42, 10_000, krw(1).currency(), "42"))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_UNCONFIRMED));
    }

    @Test
    void completedClaimReturnsWithoutAnotherPgCall() {
        PaymentCancel completed = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.SUCCEEDED);
        given(processor.prepare(payment.id(), 42, krw(10_000))).willReturn(new PaymentCancelProcessor.Prepared(payment, completed, krw(10_000)));

        facade.cancel(payment.id(), 42, 10_000, krw(1).currency(), "42");

        verifyNoInteractions(gateway);
    }

    @Test
    void mismatchedHeaderKeyIsRejectedBeforeAnyPersistenceOrPgCall() {
        assertThatThrownBy(() -> facade.cancel(payment.id(), 42, 10_000, krw(1).currency(), "43"))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_KEY_REUSED));
        verifyNoInteractions(processor, gateway);
    }

    @Test
    void permanentPgRejectionReleasesPendingLedgerForLaterClaim() {
        PaymentCancel pending = new PaymentCancel(42, payment.id(), krw(10_000), PaymentCancel.Status.PENDING);
        given(processor.prepare(payment.id(), 42, krw(10_000))).willReturn(new PaymentCancelProcessor.Prepared(payment, pending, krw(10_000)));
        given(gateway.cancel(payment, 42, pending.amount(), krw(10_000))).willReturn(PaymentGatewayProcessor.CancelOutcome.REJECTED);

        assertThatThrownBy(() -> facade.cancel(payment.id(), 42, 10_000, krw(1).currency(), "42"))
            .isInstanceOfSatisfying(PaymentException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.CANCELLATION_CONFLICT));
        org.mockito.Mockito.verify(processor).reject(payment.id(), 42);
    }
}
