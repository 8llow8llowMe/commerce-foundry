package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.DltAlertStateEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.DltPartitionCheckpointEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.DltAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.DltPartitionCheckpointRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.DltAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.DltAlertState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DltAlertStateAdapter implements DltAlertStatePort {
    private final DltAlertStateRepository states;
    private final DltPartitionCheckpointRepository checkpoints;

    @Override
    public DltAlertState lockTopic(String topic) {
        // DLT alert metadata only; see anomaly-notifications.md.
        var entity = states.findLocked(topic).orElseGet(() -> states.saveAndFlush(new DltAlertStateEntity(topic)));
        return new DltAlertState(topic, entity.getLastNotifiedAt());
    }

    @Override
    public long checkpoint(String topic, int partition) {
        return checkpoints.findByTopicAndPartitionNumber(topic, partition).map(DltPartitionCheckpointEntity::getWatermark).orElse(-1L);
    }

    @Override
    public void advance(String topic, int partition, long offset, DltAlertState state, long checkpointId) {
        var checkpoint = checkpoints.findByTopicAndPartitionNumber(topic, partition);
        if (checkpoint.isPresent()) {
            checkpoint.get().advance(offset);
        } else {
            checkpoints.save(new DltPartitionCheckpointEntity(checkpointId, topic, partition, offset));
        }
        states.findById(topic).orElseThrow().notifiedAt(state.lastNotifiedAt());
    }
}
