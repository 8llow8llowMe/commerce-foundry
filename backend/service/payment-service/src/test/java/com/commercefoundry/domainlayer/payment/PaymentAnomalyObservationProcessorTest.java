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
import org.springframework.dao.QueryTimeoutException;

class PaymentAnomalyObservationProcessorTest {
    @Test
    void readsClockOnceAndPreservesMicrosecondSnapshotAndSourceFailure() {
        var clock = mock(Clock.class);
        var repository = mock(PaymentAnomalyObservationPort.class);
        Instant cutoff = Instant.parse("2026-11-03T01:02:03.123456Z");
        when(clock.instant()).thenReturn(cutoff.plusNanos(789));
        var empty = new PaymentAnomalyObservation.Bucket(0, null);
        var expected = new PaymentAnomalyObservation(cutoff, empty, empty, empty, empty, empty, empty);
        when(repository.observeAnomalies(cutoff)).thenReturn(expected);
        var processor = new PaymentAnomalyObservationProcessor(repository, clock);
        assertThat(processor.observeAnomalies()).isSameAs(expected);
        verify(clock).instant();
        verify(repository).observeAnomalies(cutoff);
        verifyNoMoreInteractions(clock, repository);
        var failure = new QueryTimeoutException("sentinel-private");
        when(repository.observeAnomalies(cutoff)).thenThrow(failure);
        assertThatThrownBy(processor::observeAnomalies).isSameAs(failure);
    }
}
