package com.commercefoundry.domainlayer.outboxobservation.application.service.processor;

import com.commercefoundry.domainlayer.outboxobservation.application.info.OutboxObservationInfo;
import com.commercefoundry.domainlayer.outboxobservation.application.port.out.OutboxObservationRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxObservationProcessor {

    private final OutboxObservationRepositoryPort repository;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 2)
    public OutboxObservationInfo observeReady() {
        // PostgreSQL timestamp precision is microseconds. Reuse this single cutoff in the query and response.
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result = repository.observeReady(observedAt);
        return new OutboxObservationInfo(observedAt, result.readyUnpublishedCount(), result.oldestReadyAvailableAt());
    }
}
