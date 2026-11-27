package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentAnomalyObservationProcessorTest {
    static final Instant CUTOFF = Instant.parse("2026-11-03T01:02:03.123456Z");
    static final PaymentAnomalyObservation.Bucket EMPTY = new PaymentAnomalyObservation.Bucket(0, null);
    final Clock clock = mock(Clock.class);
    final PaymentAnomalyObservationPort repository = mock(PaymentAnomalyObservationPort.class);

    @Test
    void readsClockOnceAndPreservesMicrosecondSnapshotAndSourceFailure() {
        when(clock.instant()).thenReturn(CUTOFF.plusNanos(789));
        var expected = sixBuckets(null);
        when(repository.observeAnomalies(CUTOFF)).thenReturn(expected);
        var processor = processor(false);
        assertThat(processor.observeAnomalies()).isEqualTo(expected);
        verify(clock).instant();
        verify(repository).observeAnomalies(CUTOFF);
        verifyNoMoreInteractions(clock, repository);
        var failure = new QueryTimeoutException("sentinel-private");
        when(repository.observeAnomalies(CUTOFF)).thenThrow(failure);
        assertThatThrownBy(processor::observeAnomalies).isSameAs(failure);
    }

    @Test
    void closedGateNeverQueriesAutoCancelAndForcesUnobservedBucket() {
        when(clock.instant()).thenReturn(CUTOFF);
        // 어댑터가 무엇을 돌려주든 게이트 OFF 면 7번째는 미관측(null)이다.
        when(repository.observeAnomalies(CUTOFF)).thenReturn(sixBuckets(new PaymentAnomalyObservation.Bucket(9, CUTOFF)));
        var result = processor(false).observeAnomalies();
        assertThat(result.autoCancelManualReview()).isNull();
        assertThat(result.unknown()).isEqualTo(EMPTY);
        verify(repository, never()).observeAutoCancelManualReview(any());
    }

    @Test
    void openGateReadsSecondStatementWithSameObservedAtAndClockReadOnce() {
        when(clock.instant()).thenReturn(CUTOFF.plusNanos(789));
        var bucket = new PaymentAnomalyObservation.Bucket(3, CUTOFF.minusSeconds(90));
        when(repository.observeAnomalies(CUTOFF)).thenReturn(sixBuckets(null));
        when(repository.observeAutoCancelManualReview(CUTOFF)).thenReturn(bucket);
        var result = processor(true).observeAnomalies();
        assertThat(result).isEqualTo(sixBuckets(bucket));
        verify(clock).instant();
        var order = inOrder(repository);
        order.verify(repository).observeAnomalies(CUTOFF);
        order.verify(repository).observeAutoCancelManualReview(CUTOFF);
        verifyNoMoreInteractions(clock, repository);
    }

    @Test
    void openGatePropagatesSecondStatementFailureAndFutureValue() {
        when(clock.instant()).thenReturn(CUTOFF);
        when(repository.observeAnomalies(CUTOFF)).thenReturn(sixBuckets(null));
        var processor = processor(true);
        var timeout = new QueryTimeoutException("sentinel-private");
        when(repository.observeAutoCancelManualReview(CUTOFF)).thenThrow(timeout);
        assertThatThrownBy(processor::observeAnomalies).isSameAs(timeout);
        var future = new DataIntegrityViolationException("결제 관측 기준 시각이 유효하지 않습니다.");
        doThrow(future).when(repository).observeAutoCancelManualReview(CUTOFF);
        assertThatThrownBy(processor::observeAnomalies).isSameAs(future);
    }

    private PaymentAnomalyObservationProcessor processor(boolean autoCancelEnabled) {
        var processor = new PaymentAnomalyObservationProcessor(repository, clock);
        ReflectionTestUtils.setField(processor, "autoCancelEnabled", autoCancelEnabled);
        return processor;
    }

    private static PaymentAnomalyObservation sixBuckets(PaymentAnomalyObservation.Bucket autoCancel) {
        return new PaymentAnomalyObservation(CUTOFF, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, autoCancel);
    }
}
