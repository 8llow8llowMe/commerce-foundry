package com.commercefoundry.domainlayer.payment.application.service.processor;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.application.model.PaymentCancellationInfo;
import com.commercefoundry.domainlayer.payment.application.model.PaymentCancellationInfo.State;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor.Outcome;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentAutoCancelExecutionProcessorTest {
    private final PaymentAutoCancelProcessor processor = mock(PaymentAutoCancelProcessor.class);
    private final PaymentGatewayProcessor gateway = mock(PaymentGatewayProcessor.class);
    private final Instant now = Instant.parse("2026-11-07T00:00:00Z");
    private final PaymentAutoCancel intent = PaymentAutoCancel.intent(PAYMENT_ID, now).acquire(42, krw(129_000), "token", now);
    private final PaymentAutoCancelExecutionProcessor executor = new PaymentAutoCancelExecutionProcessor(processor, gateway, Clock.fixed(now, ZoneOffset.UTC));

    private void enable() {
        ReflectionTestUtils.setField(executor, "enabled", true);
        when(processor.acquire(PAYMENT_ID)).thenReturn(new PaymentAutoCancelProcessor.Attempt(done(), intent));
    }

    @Test
    void collisionRetriesOnlyAcquireAndNeverCallsPgBeforeSuccessfulCommit() {
        enable();
        when(processor.acquire(PAYMENT_ID))
            .thenThrow(new com.commercefoundry.domainlayer.payment.application.exception.PaymentAutoCancelClaimCollisionException())
            .thenReturn(new PaymentAutoCancelProcessor.Attempt(done(), intent));
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null))
            .thenReturn(new PaymentCancellationInfo(State.RETRY, null));
        executor.execute(PAYMENT_ID);
        var ordered = inOrder(processor, gateway);
        ordered.verify(processor, times(2)).acquire(PAYMENT_ID);
        ordered.verify(gateway).inquireCancellation(done(), intent.amount(), intent.pgKey(), null);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
    }

    @Test
    void repeatedCollisionsStayBoundedWithoutAnyPgCall() {
        enable();
        when(processor.acquire(PAYMENT_ID))
            .thenThrow(new com.commercefoundry.domainlayer.payment.application.exception.PaymentAutoCancelClaimCollisionException());
        executor.execute(PAYMENT_ID);
        verify(processor, times(3)).acquire(PAYMENT_ID);
        verifyNoInteractions(gateway);
    }

    @Test
    void gateIsOffByDefaultAndDoesNotTouchDbOrPg() {
        executor.execute(PAYMENT_ID);
        verifyNoInteractions(processor, gateway);
    }

    @Test
    void externalFullCancellationCannotBeAttributedOrPostedAgain() {
        enable();
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null))
            .thenReturn(new PaymentCancellationInfo(State.UNATTRIBUTED, null));
        executor.execute(PAYMENT_ID);
        verify(processor).finish(PAYMENT_ID, "token", Outcome.MANUAL_REVIEW);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
        verify(processor, never()).recordEvidence(anyLong(), anyString(), anyString());
    }

    @Test
    void postEvidenceMustBeSavedAndMatchedByGetBeforeCompletion() {
        enable();
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null)).thenReturn(new PaymentCancellationInfo(State.OPEN, null));
        when(gateway.cancelAutomatic(done(), intent.pgKey(), intent.amount())).thenReturn(new PaymentCancellationInfo(State.EVIDENCE, "txn"));
        when(processor.recordEvidence(PAYMENT_ID, "token", "txn")).thenReturn(true);
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), "txn")).thenReturn(new PaymentCancellationInfo(State.CONFIRMED, null));
        executor.execute(PAYMENT_ID);
        var ordered = inOrder(gateway, processor);
        ordered.verify(processor).acquire(PAYMENT_ID);
        ordered.verify(gateway).inquireCancellation(done(), intent.amount(), intent.pgKey(), null);
        ordered.verify(gateway).cancelAutomatic(done(), intent.pgKey(), intent.amount());
        ordered.verify(processor).recordEvidence(PAYMENT_ID, "token", "txn");
        ordered.verify(gateway).inquireCancellation(done(), intent.amount(), intent.pgKey(), "txn");
        ordered.verify(processor).finish(PAYMENT_ID, "token", Outcome.CONFIRMED);
    }

    @Test
    void postSuccessAloneNeverCompletesAndRetryKeepsSameKey() {
        enable();
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null)).thenReturn(new PaymentCancellationInfo(State.OPEN, null));
        when(gateway.cancelAutomatic(done(), intent.pgKey(), intent.amount())).thenReturn(new PaymentCancellationInfo(State.EVIDENCE, "txn"));
        when(processor.recordEvidence(PAYMENT_ID, "token", "txn")).thenReturn(true);
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), "txn")).thenReturn(new PaymentCancellationInfo(State.RETRY, null));
        executor.execute(PAYMENT_ID);
        verify(processor).finish(PAYMENT_ID, "token", Outcome.RETRY);
        verify(processor, never()).finish(PAYMENT_ID, "token", Outcome.CONFIRMED);
        assertThat(intent.pgKey()).isEqualTo("payment-auto-cancel-v1-" + PAYMENT_ID);
    }

    @Test
    void crashAfterEvidenceResumesByGetWithoutPost() {
        enable();
        var recovered = intent.evidence("txn");
        when(processor.acquire(PAYMENT_ID)).thenReturn(new PaymentAutoCancelProcessor.Attempt(done(), recovered));
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), "txn")).thenReturn(new PaymentCancellationInfo(State.CONFIRMED, null));
        executor.execute(PAYMENT_ID);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
        verify(processor).finish(PAYMENT_ID, "token", Outcome.CONFIRMED);
    }

    @Test
    void crashBeforeEvidenceCommitRecoversMarkedGetWithoutAnotherPost() {
        enable();
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null))
            .thenReturn(new PaymentCancellationInfo(State.CONFIRMED, "recovered-txn"));
        when(processor.recordEvidence(PAYMENT_ID, "token", "recovered-txn")).thenReturn(true);
        executor.execute(PAYMENT_ID);
        var ordered = inOrder(processor);
        ordered.verify(processor).acquire(PAYMENT_ID);
        ordered.verify(processor).recordEvidence(PAYMENT_ID, "token", "recovered-txn");
        ordered.verify(processor).finish(PAYMENT_ID, "token", Outcome.CONFIRMED);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"OPEN", "CONFIRMED", "RETRY"})
    void expiredKeyAllowsGetConfirmationButNeverPost(State state) {
        enable();
        var expired = PaymentAutoCancel.intent(PAYMENT_ID, now.minusSeconds(15 * 86400))
            .acquire(42, krw(129_000), "old", now.minusSeconds(15 * 86400)).evidence("txn")
            .acquire(42, krw(129_000), "token", now);
        when(processor.acquire(PAYMENT_ID)).thenReturn(new PaymentAutoCancelProcessor.Attempt(done(), expired));
        when(gateway.inquireCancellation(done(), expired.amount(), expired.pgKey(), "txn"))
            .thenReturn(new PaymentCancellationInfo(state, state == State.CONFIRMED ? "txn" : null));
        executor.execute(PAYMENT_ID);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
        verify(processor).finish(PAYMENT_ID, "token", state == State.CONFIRMED ? Outcome.CONFIRMED : Outcome.MANUAL_REVIEW);
    }

    @Test
    void lostLeaseAfterPostCannotContinueToGetOrComplete() {
        enable();
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null)).thenReturn(new PaymentCancellationInfo(State.OPEN, null));
        when(gateway.cancelAutomatic(done(), intent.pgKey(), intent.amount())).thenReturn(new PaymentCancellationInfo(State.EVIDENCE, "txn"));
        when(processor.recordEvidence(PAYMENT_ID, "token", "txn")).thenReturn(false);
        executor.execute(PAYMENT_ID);
        verify(processor, never()).finish(anyLong(), anyString(), any());
        verify(gateway, never()).inquireCancellation(done(), intent.amount(), intent.pgKey(), "txn");
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"RETRY", "MANUAL_REVIEW"})
    void failedInquiryNeverPosts(State state) {
        enable();
        when(gateway.inquireCancellation(done(), intent.amount(), intent.pgKey(), null)).thenReturn(new PaymentCancellationInfo(state, null));
        executor.execute(PAYMENT_ID);
        verify(gateway, never()).cancelAutomatic(any(), anyString(), any());
        verify(processor).finish(PAYMENT_ID, "token", state == State.RETRY ? Outcome.RETRY : Outcome.MANUAL_REVIEW);
    }
}
