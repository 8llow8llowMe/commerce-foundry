package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** E1 비교 구현. 운영 Port/Entity/배치 설정에 연결하지 않는다. */
final class AnomalyProbeStore {
    enum Mode { BASELINE, A_SAVE_ALL, B_PERSIST }

    private static final AtomicLong IDS = new AtomicLong(1_000_000);
    private final NotificationRepository repository;
    private final EntityManager entityManager;

    AnomalyProbeStore(NotificationRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    List<Notification> insert(Mode mode, List<Notification> input) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Caller transaction required");
        }
        if (input == null || input.isEmpty() || input.size() > 5000) {
            throw new IllegalArgumentException("Expected 1..5000 notifications");
        }
        var ids = new HashSet<Long>();
        for (Notification value : input) {
            if (value == null || value.version() != null || value.createdAt() != null || !value.unread() || !ids.add(value.id())) {
                throw new IllegalArgumentException("Expected distinct new unread notifications");
            }
        }
        if (mode == Mode.BASELINE) {
            var adapter = new NotificationRepositoryAdapter(repository);
            return input.stream().map(adapter::insert).toList();
        }
        var entities = input.stream().map(AnomalyProbeStore::entity).toList();
        List<NotificationEntity> saved;
        if (mode == Mode.A_SAVE_ALL) {
            saved = repository.saveAllAndFlush(entities);
        } else {
            entities.forEach(entityManager::persist);
            entityManager.flush();
            saved = entities;
        }
        return saved.stream().map(AnomalyProbeStore::domain).toList();
    }

    static List<Notification> fixture(int n) {
        long first = IDS.getAndAdd(n + 1);
        return IntStream.range(0, n).mapToObj(i -> new Notification(first + i, 1 + i % 1000,
            NotificationKind.ANOMALY, NotificationSeverity.WARNING, "이상 거래 확인이 필요합니다", "/admin/payments",
            "PAYMENT_ANOMALY_ALERT", first + i, null, null, null)).toList();
    }

    static NotificationEntity entity(Notification value) {
        return NotificationEntity.builder().id(value.id()).recipientMemberId(value.recipientMemberId()).kind(value.kind())
            .severity(value.severity()).title(value.title()).linkPath(value.linkPath()).sourceType(value.sourceType())
            .sourceId(value.sourceId()).readAt(value.readAt()).version(value.version()).build();
    }

    static Notification domain(NotificationEntity value) {
        return new Notification(value.getId(), value.getRecipientMemberId(), value.getKind(), value.getSeverity(), value.getTitle(),
            value.getLinkPath(), value.getSourceType(), value.getSourceId(), value.getReadAt(), value.getCreatedAt(), value.getVersion());
    }
}
