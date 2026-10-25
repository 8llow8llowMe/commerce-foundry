package com.commercefoundry.messaging.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 이벤트 봉투 (#304 A-5). Kafka 메시지 본문 전체이고 outbox 행의 페이로드이기도 하다 — {@code eventId} 가 행에 고정돼 다시 보내도 같은 아이디다.
 *
 * <p>{@code payload} 에는 아이디 · 금액(long)+통화 · 상태 · 사유 코드 · 시각 · SKU · 수량만 싣는다. 이름 · 연락처 · 주소 · 이메일 · 카드 정보 ·
 * PG 키 · 오류 원문은 싣지 않는다 — Kafka UI 로 본문이 열람되고 7일 보존된다. 세부는 컨슈머가 원본 서비스 internal API 로 읽는다.
 * 필드는 추가만 한다 — 삭제 · 의미 변경은 {@code schemaVersion+1} 과 새 토픽 접미사 {@code .v2} 로 한다.
 *
 * @param eventType     {@code <aggregate>-<event>} (예 {@code payment-approved}) — 토픽 이름의 끝 토막
 * @param schemaVersion 이 이벤트 종류의 페이로드 계약 버전(1부터)
 * @param producer      낸 서비스 이름
 * @param aggregateId   파티션 키 — 같은 주문 · 작업의 이벤트 순서가 지켜진다
 */
public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String producer,
    String aggregateType,
    String aggregateId,
    JsonNode payload
) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 은 1 이상이어야 합니다.");
        }
    }

    /** 로그 · 예외에 쓴다 — 페이로드를 내지 않는다. */
    @Override
    public String toString() {
        return "EventEnvelope[eventId=" + eventId + ", eventType=" + eventType + ", schemaVersion=" + schemaVersion + ", aggregateId=" + aggregateId + "]";
    }
}
