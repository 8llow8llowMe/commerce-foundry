package com.commercefoundry.domainlayer.outboxobservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.outboxobservation.application.port.out.OutboxObservationRepositoryPort;
import com.commercefoundry.domainlayer.outboxobservation.application.port.out.query.OutboxObservationQueryResult;
import com.commercefoundry.domainlayer.outboxobservation.application.service.processor.OutboxObservationProcessor;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxObservationProcessorTest {

    @Test
    void readsClockOnceAndUsesIdenticalMicrosecondCutoffForQueryAndResponse() {
        Clock clock = mock(Clock.class);
        var repository = mock(OutboxObservationRepositoryPort.class);
        Instant precise = Instant.parse("2026-11-03T01:02:03.123456789Z");
        Instant cutoff = Instant.parse("2026-11-03T01:02:03.123456Z");
        when(clock.instant()).thenReturn(precise);
        when(repository.observeReady(cutoff)).thenReturn(new OutboxObservationQueryResult(0, null));

        var result = new OutboxObservationProcessor(repository, clock).observeReady();

        assertThat(result.observedAt()).isEqualTo(cutoff);
        assertThat(result.readyUnpublishedCount()).isZero();
        assertThat(result.oldestReadyAvailableAt()).isNull();
        verify(clock).instant();
        verify(repository).observeReady(cutoff);
        verifyNoMoreInteractions(clock, repository);
    }
}
