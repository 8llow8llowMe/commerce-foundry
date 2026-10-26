package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 어드민 알림 한 건 (#213). 한 건 읽음은 도메인 {@code markRead} 로만 바꾸고, 모두 읽음은 읽지 않은 행을 조건부 UPDATE 한 문장으로 채운다.
 * 같은 출처 · 종류는 유니크 키가 한 행으로 막는다.
 */
@Entity
@Table(
    name = "notification",
    comment = "어드민 알림",
    uniqueConstraints = @UniqueConstraint(name = "uk_notification_source_type_source_id_kind", columnNames = {"source_type", "source_id", "kind"}),
    indexes = {
        @Index(name = "idx_notification_recipient_member_id_id", columnList = "recipient_member_id, id"),
        @Index(name = "idx_notification_recipient_member_id_read_at", columnList = "recipient_member_id, read_at"),
        @Index(name = "idx_notification_created_at", columnList = "created_at")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "알림 아이디 (Snowflake)")
    private Long id;

    @Column(name = "recipient_member_id", nullable = false, comment = "받는 어드민 (외부 FK: auth-service member.id)")
    private long recipientMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32, comment = "알림 종류")
    private NotificationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16, comment = "심각도")
    private NotificationSeverity severity;

    @Column(name = "title", nullable = false, length = 120, comment = "제목 (개인정보 원문 없음)")
    private String title;

    @Column(name = "link_path", length = 200, comment = "어드민 화면 경로. 없으면 null")
    private String linkPath;

    @Column(name = "source_type", nullable = false, length = 32, comment = "출처 종류 (JOB 등)")
    private String sourceType;

    @Column(name = "source_id", nullable = false, comment = "출처 아이디. 작업이면 job.id")
    private long sourceId;

    @Column(name = "read_at", comment = "읽은 시각. 아직이면 null")
    private Instant readAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
