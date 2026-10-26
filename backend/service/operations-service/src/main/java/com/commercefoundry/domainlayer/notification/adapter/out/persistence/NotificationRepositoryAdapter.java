package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationRepositoryAdapter implements NotificationRepositoryPort {

    private final NotificationRepository notificationRepository;

    @Override
    public Notification save(Notification notification) {
        Notification stored = toDomain(notificationRepository.saveAndFlush(toEntity(notification)));
        if (stored.createdAt() != null || notification.createdAt() == null) {
            return stored;
        }
        return new Notification(
            stored.id(),
            stored.recipientMemberId(),
            stored.kind(),
            stored.severity(),
            stored.title(),
            stored.linkPath(),
            stored.sourceType(),
            stored.sourceId(),
            stored.readAt(),
            notification.createdAt(),
            stored.version()
        );
    }

    @Override
    public Optional<Notification> findById(long notificationId) {
        return notificationRepository.findById(notificationId).map(this::toDomain);
    }

    @Override
    public Optional<Notification> findBySource(String sourceType, long sourceId, NotificationKind kind) {
        return notificationRepository.findBySourceTypeAndSourceIdAndKind(sourceType, sourceId, kind).map(this::toDomain);
    }

    @Override
    public Page<Notification> findPage(long recipientMemberId, int page, int size) {
        return notificationRepository
            .findByRecipientMemberId(recipientMemberId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
            .map(this::toDomain);
    }

    @Override
    public long countUnread(long recipientMemberId) {
        return notificationRepository.countByRecipientMemberIdAndReadAtIsNull(recipientMemberId);
    }

    @Override
    public int markAllRead(long recipientMemberId, Instant now) {
        return notificationRepository.markUnreadAsRead(recipientMemberId, now);
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        return notificationRepository.deleteByCreatedAtBefore(cutoff);
    }

    private NotificationEntity toEntity(Notification notification) {
        return NotificationEntity.builder()
            .id(notification.id())
            .recipientMemberId(notification.recipientMemberId())
            .kind(notification.kind())
            .severity(notification.severity())
            .title(notification.title())
            .linkPath(notification.linkPath())
            .sourceType(notification.sourceType())
            .sourceId(notification.sourceId())
            .readAt(notification.readAt())
            .version(notification.version())
            .build();
    }

    private Notification toDomain(NotificationEntity entity) {
        return new Notification(
            entity.getId(),
            entity.getRecipientMemberId(),
            entity.getKind(),
            entity.getSeverity(),
            entity.getTitle(),
            entity.getLinkPath(),
            entity.getSourceType(),
            entity.getSourceId(),
            entity.getReadAt(),
            entity.getCreatedAt(),
            entity.getVersion()
        );
    }
}
