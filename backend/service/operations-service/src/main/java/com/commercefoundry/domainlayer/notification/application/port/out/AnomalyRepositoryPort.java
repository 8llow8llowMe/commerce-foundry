package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalyState;
import java.time.Instant;
import java.util.Optional;

public interface AnomalyRepositoryPort {
    boolean alreadyProcessed(AnomalyRecordCommand command);
    Optional<AnomalyState> findState(String sourceKey);
    void saveStateAndFlush(AnomalyState state);
    void insertRecord(long id, AnomalyRecordCommand command, Instant processedAt);
    int deleteProcessedBefore(Instant cutoff);
}
