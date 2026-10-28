package com.commercefoundry.global.messaging;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 처리한 이벤트 기록 (inbox, messaging-core README) — 컨슈머 그룹 × 이벤트 아이디 한 행. 업무 처리와 같은 커밋으로 넣어 같은 이벤트를 한 번만 처리한다.
 * 같은 키가 동시에 들어오면 커밋 때 유니크 키({@code uk_processed_event_consumer_group_event_id}) 위반으로 한쪽이 롤백되고, 재전달에서 걸러진다.
 *
 * <p>append-only 다. 보존 기간({@code commerce.messaging.inbox.retention}) 뒤 {@code processed_at} 순으로 지운다({@code idx_processed_event_processed_at}).
 *
 * <p>{@link Persistable} 로 새 행이면 {@code merge} 가 아니라 {@code persist}(INSERT) 하게 한다 — 아이디(Snowflake)를 직접 넣는 엔티티라
 * 기본 {@code save} 는 merge 로 가서 SELECT 를 한 번 더 보낸다.
 */
@Entity
@Table(
    name = "processed_event",
    comment = "처리한 이벤트 기록 (멱등 컨슈머 inbox)",
    uniqueConstraints = @UniqueConstraint(name = "uk_processed_event_consumer_group_event_id", columnNames = {"consumer_group", "event_id"}),
    indexes = @Index(name = "idx_processed_event_processed_at", columnList = "processed_at")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProcessedEventEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "처리 기록 아이디 (Snowflake)")
    private Long id;

    @Column(name = "consumer_group", nullable = false, length = 200, comment = "컨슈머 그룹 (예: commerce-foundry.prod.product.order-stock-release)")
    private String consumerGroup;

    @Column(name = "event_id", nullable = false, comment = "이벤트 아이디 (봉투 eventId)")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100, comment = "이벤트 종류 (예: order-expired)")
    private String eventType;

    @Column(name = "processed_at", nullable = false, comment = "처리 시각 — 보존 기간 정리 기준")
    private Instant processedAt;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
