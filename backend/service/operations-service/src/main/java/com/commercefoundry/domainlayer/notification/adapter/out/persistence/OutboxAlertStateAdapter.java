package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.OutboxAlertStateEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.OutboxAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.OutboxAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.OutboxAlertState;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxAlertStateAdapter implements OutboxAlertStatePort {
    private final OutboxAlertStateRepository repository;
    public Optional<OutboxAlertState> findLocked() {
        return repository.findLocked().map(OutboxAlertStateEntity::toDomain);
    }
    public void save(OutboxAlertState state, boolean firstObservation) {
        if (firstObservation) {
            // A competing first INSERT aborts the whole transaction; never recover in this transaction.
            repository.saveAndFlush(new OutboxAlertStateEntity(state));
        } else {
            repository.findById("PAYMENT").orElseThrow().accept(state);
            repository.flush();
        }
    }
}
