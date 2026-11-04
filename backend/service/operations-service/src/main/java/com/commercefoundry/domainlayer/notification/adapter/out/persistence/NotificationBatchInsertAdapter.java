package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationBatchInsertAdapter implements NotificationBatchInsertPort {
    private final NotificationRepository repository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Notification> insertAll(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty() || notifications.size() > 5000 || notifications.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid notification batch size");
        }
        var input = List.copyOf(notifications);
        var ids = new HashSet<Long>();
        // 전체 검증을 끝낸 뒤 Entity 생성/저장을 시작한다. 기존 Notification 생성자의 느슨한 문자열도 검증한다.
        for (var n : input) {
            if (n.version() != null || n.createdAt() != null || !n.unread() || !ids.add(n.id())) {
                throw new IllegalArgumentException("Only distinct new unread notifications can be inserted");
            }
            new NotificationRequest(n.kind(), n.severity(), n.title(), n.linkPath(), n.recipientMemberId(), n.sourceType(), n.sourceId());
        }
        var entities = input.stream().map(n -> NotificationEntity.builder().id(n.id()).recipientMemberId(n.recipientMemberId())
            .kind(n.kind()).severity(n.severity()).title(n.title()).linkPath(n.linkPath()).sourceType(n.sourceType()).sourceId(n.sourceId()).build()).toList();
        // 객체형 version=null의 신규 판정을 사용한다. flush 1회는 JDBC batching을 뜻하지 않는다.
        return repository.saveAllAndFlush(entities).stream().map(e -> new Notification(e.getId(), e.getRecipientMemberId(), e.getKind(), e.getSeverity(),
            e.getTitle(), e.getLinkPath(), e.getSourceType(), e.getSourceId(), e.getReadAt(), e.getCreatedAt(), e.getVersion())).toList();
    }
}
