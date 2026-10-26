package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 알림 저장소. 호출자의 트랜잭션 안에서 돈다. */
public interface NotificationRepositoryPort {

    Notification save(Notification notification);

    Optional<Notification> findById(long notificationId);

    Optional<Notification> findBySource(String sourceType, long sourceId, NotificationKind kind);

    Page<Notification> findPage(long recipientMemberId, int page, int size);

    long countUnread(long recipientMemberId);

    /** 그 어드민의 읽지 않은 행만 한 문장으로 읽음 처리하고, 바꾼 행 수를 돌려준다. */
    int markAllRead(long recipientMemberId, Instant now);

    int deleteCreatedBefore(Instant cutoff);
}
