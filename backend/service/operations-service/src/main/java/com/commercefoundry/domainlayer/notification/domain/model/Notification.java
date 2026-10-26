package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 어드민 알림 한 건. 한 건 읽음은 {@link #markRead} 로만 한다. 이미 읽은 알림에 다시 부르면 같은 인스턴스를 돌려준다(저장하지 않는다).
 * 모두 읽음은 이 전이를 행마다 반복하지 않고, 저장소가 같은 {@code readAt} 칸을 한 문장으로 채운다.
 */
public record Notification(
    long id,
    long recipientMemberId,
    NotificationKind kind,
    NotificationSeverity severity,
    String title,
    String linkPath,
    String sourceType,
    long sourceId,
    Instant readAt,
    Instant createdAt,
    Long version
) {

    public Notification {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(sourceType, "sourceType");
        if (id <= 0 || recipientMemberId <= 0 || sourceId <= 0) {
            throw new IllegalArgumentException("id");
        }
    }

    public static Notification create(long id, NotificationRequest request) {
        return new Notification(
            id,
            request.recipientMemberId(),
            request.kind(),
            request.severity(),
            request.title(),
            request.linkPath(),
            request.sourceType(),
            request.sourceId(),
            null,
            null,
            null
        );
    }

    public boolean unread() {
        return readAt == null;
    }

    /** 이미 읽었으면 같은 인스턴스다. 호출자는 그 경우 저장하지 않는다. */
    public Notification markRead(Instant now) {
        Objects.requireNonNull(now, "now");
        if (readAt != null) {
            return this;
        }
        return new Notification(id, recipientMemberId, kind, severity, title, linkPath, sourceType, sourceId, now, createdAt, version);
    }
}
