package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationAnomalyRecordEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationAnomalyStateEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationAnomalyRecordRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationAnomalyStateRepository;
import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.port.out.AnomalyRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalyState;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnomalyRepositoryAdapter implements AnomalyRepositoryPort {
    private static final String STATE_UNIQUE = "uk_notification_anomaly_state_source_key";
    private static final String RECORD_UNIQUE = "uk_notification_anomaly_record_topic_partition_id_offset_id";
    private final NotificationAnomalyStateRepository states;
    private final NotificationAnomalyRecordRepository records;

    @Override
    public boolean alreadyProcessed(AnomalyRecordCommand command) {
        return records.existsByTopicAndPartitionIdAndOffsetId(command.topic(), command.partition(), command.offset());
    }

    @Override
    public Optional<AnomalyState> findState(String sourceKey) {
        return states.findBySourceKey(sourceKey).map(entity -> new AnomalyState(entity.getId(), entity.getSourceKey(),
            entity.getLastSeenAt(), entity.getLastNotifiedAt(), entity.getVersion()));
    }

    @Override
    public void saveStateAndFlush(AnomalyState state) {
        try {
            states.saveAndFlush(NotificationAnomalyStateEntity.builder().id(state.id()).sourceKey(state.sourceKey())
                .lastSeenAt(state.lastSeenAt()).lastNotifiedAt(state.lastNotifiedAt()).version(state.version()).build());
        } catch (DataIntegrityViolationException exception) {
            throw translated(exception, STATE_UNIQUE);
        }
    }

    @Override
    public void insertRecord(long id, AnomalyRecordCommand command, Instant processedAt) {
        try {
            records.saveAndFlush(NotificationAnomalyRecordEntity.builder().id(id).topic(command.topic())
                .partitionId(command.partition()).offsetId(command.offset()).processedAt(processedAt).build());
        } catch (DataIntegrityViolationException exception) {
            throw translated(exception, RECORD_UNIQUE);
        }
    }

    @Override
    public int deleteProcessedBefore(Instant cutoff) {
        return records.deleteProcessedBefore(cutoff);
    }

    private RuntimeException translated(DataIntegrityViolationException exception, String knownConstraint) {
        // 실패한 TX에서는 재조회하지 않는다. 알려진 키 경합만 호출자의 새 TX 재시도 대상이다.
        if (ConstraintViolations.violates(exception, knownConstraint)) {
            return new OptimisticLockingFailureException("Concurrent anomaly notification record");
        }
        return new DataIntegrityViolationException("Anomaly notification persistence failed");
    }
}
