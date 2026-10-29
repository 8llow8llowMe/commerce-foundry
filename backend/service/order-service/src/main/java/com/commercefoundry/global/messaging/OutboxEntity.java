package com.commercefoundry.global.messaging;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 이벤트 발행 대기열 (transactional outbox, messaging-core README). 업무 트랜잭션과 같은 커밋으로 넣고, 릴레이가 Kafka 로 보낸 뒤 {@code published_at} 을 채운다.
 *
 * <p>행을 넣은 뒤에는 상태가 아닌 칸(발행 시각 · 시도 수 · 실패 코드)만 조건부 UPDATE 로 바꾼다({@code OutboxRepository}) — 그때도 버전을 올린다.
 *
 * <p>인덱스 — 미발행 조회({@code published_at is null and available_at <= now order by id})와 정리({@code published_at < cutoff})가 함께 쓰는
 * {@code (published_at, available_at)}. README 의 부분 인덱스({@code where published_at is null})는 JPA 로 선언할 수 없어 이 모양으로 둔다 — 발행 완료 행은
 * 보존 기간(7일) 뒤 지워 인덱스가 커지지 않는다.
 */
@Entity
@Table(
    name = "outbox",
    comment = "이벤트 발행 대기열 (transactional outbox)",
    indexes = @Index(name = "idx_outbox_published_at_available_at", columnList = "published_at, available_at")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OutboxEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "outbox 행 아이디 (Snowflake — 시간순, 같은 키의 발행 순서)")
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50, comment = "집합 종류 (예: order)")
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100, comment = "집합 아이디 — Kafka 키(같은 키는 같은 파티션 · 순서)")
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, comment = "이벤트 종류 — 토픽 끝 토막 (예: order-expired)")
    private String eventType;

    @Column(name = "schema_version", nullable = false, comment = "페이로드 계약 버전 (1부터)")
    private int schemaVersion;

    /** 봉투 전체 JSON — 다시 보내도 같은 eventId 다. messaging-core 가 상한({@code max-payload-bytes})을 본다. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "payload", nullable = false, comment = "이벤트 봉투 JSON (개인정보 원문 없음)")
    private String payload;

    @Column(name = "available_at", nullable = false, comment = "이 시각부터 보낸다 — 예약 · 재시도 백오프 같은 지연 발행")
    private Instant availableAt;

    @Column(name = "published_at", comment = "Kafka 에 보낸 시각. 아직이면 null")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false, comment = "실패한 발행 시도 수")
    private int publishAttempts;

    @Column(name = "last_error_code", length = 100, comment = "마지막 발행 실패 코드 (예외 타입 · Kafka 오류 코드만, 메시지 원문 없음)")
    private String lastErrorCode;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전 — 발행 표시 · 실패 기록이 올린다")
    private Long version;
}
