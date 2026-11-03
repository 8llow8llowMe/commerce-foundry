package com.commercefoundry.domainlayer.outboxobservation.adapter.out.persistence;

import com.commercefoundry.domainlayer.outboxobservation.application.port.out.OutboxObservationRepositoryPort;
import com.commercefoundry.domainlayer.outboxobservation.application.port.out.query.OutboxObservationQueryResult;
import com.commercefoundry.global.messaging.OutboxRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxObservationRepositoryAdapter implements OutboxObservationRepositoryPort {

    private final OutboxRepository repository;

    @Override
    public OutboxObservationQueryResult observeReady(Instant observedAt) {
        var result = repository.observeReady(observedAt);
        return new OutboxObservationQueryResult(result.getReadyUnpublishedCount(), result.getOldestReadyAvailableAt());
    }
}
