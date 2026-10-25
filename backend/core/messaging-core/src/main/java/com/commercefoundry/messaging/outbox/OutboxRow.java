package com.commercefoundry.messaging.outbox;

import java.time.Instant;
import java.util.Objects;

/**
 * outbox 행 하나 (#304 A-3 칸 모양). {@code envelopeJson} 은 봉투 전체라 다시 보내도 같은 {@code eventId} 다.
 * 테이블 · 엔티티는 서비스가 소유한다 — 이 값은 {@link OutboxStore} 를 오가는 모양일 뿐이다.
 *
 * @param id              저장 뒤 아이디(Snowflake — 시간순). 새 행이면 null
 * @param availableAt     이 시각부터 보낸다 — 예약 · 백오프 같은 지연 발행
 * @param publishAttempts 실패한 발행 시도 수
 */
public record OutboxRow(
    Long id,
    String aggregateType,
    String aggregateId,
    String eventType,
    int schemaVersion,
    String envelopeJson,
    Instant availableAt,
    int publishAttempts
) {

    public OutboxRow {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(envelopeJson, "envelopeJson");
        Objects.requireNonNull(availableAt, "availableAt");
    }

    /** 로그용 — 봉투를 내지 않는다. */
    @Override
    public String toString() {
        return "OutboxRow[id=" + id + ", eventType=" + eventType + ", aggregateId=" + aggregateId + ", attempts=" + publishAttempts + "]";
    }
}
