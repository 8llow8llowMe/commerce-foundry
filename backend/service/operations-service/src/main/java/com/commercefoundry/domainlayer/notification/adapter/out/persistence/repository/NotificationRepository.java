package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    Optional<NotificationEntity> findBySourceTypeAndSourceIdAndKind(String sourceType, long sourceId, NotificationKind kind);

    Page<NotificationEntity> findByRecipientMemberId(long recipientMemberId, Pageable pageable);

    long countByRecipientMemberIdAndReadAtIsNull(long recipientMemberId);

    /**
     * 읽지 않은 행의 {@code read_at} 만 채운다. 상태 enum 이 아니라 시각 칸이라 건마다 merge 하지 않는다({@code coding-conventions.md} §9-1 · §9-5).
     * bulk UPDATE 는 감사 리스너를 타지 않으므로 {@code updated_at} 을 직접 세우고, 한 건 읽음의 버전 비교와 맞물리게 버전도 올린다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update NotificationEntity notification
           set notification.readAt = :now, notification.updatedAt = :now, notification.version = notification.version + 1
         where notification.recipientMemberId = :recipientMemberId and notification.readAt is null
        """)
    int markUnreadAsRead(long recipientMemberId, Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotificationEntity notification where notification.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
