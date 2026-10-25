package com.commercefoundry.messaging.publish;

import java.util.Objects;

/**
 * 발행 요청 — 일어난 사실 하나 (#304 A-1). {@code payload} 는 업무 레코드이고 JSON 으로 봉투에 들어간다.
 * 아이디 · 금액(long)+통화 · 상태 · 사유 코드 · 시각만 싣는다 — 개인정보 · PG 키 · 오류 원문은 싣지 않는다 (README).
 *
 * @param aggregateType 예 {@code payment} · {@code order} · {@code job}
 * @param aggregateId   파티션 키 — 같은 집합의 이벤트 순서가 지켜진다
 * @param eventType     {@code <aggregate>-<event>} (예 {@code payment-approved}) — 토픽 끝 토막
 * @param schemaVersion 페이로드 계약 버전(1부터)
 */
public record DomainEvent(String aggregateType, String aggregateId, String eventType, int schemaVersion, Object payload) {

    public DomainEvent {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 은 1 이상이어야 합니다.");
        }
    }

    /** 페이로드는 찍지 않는다 — 로그에 그대로 남지 않게. */
    @Override
    public String toString() {
        return "DomainEvent[aggregateType=" + aggregateType + ", aggregateId=" + aggregateId + ", eventType=" + eventType
            + ", schemaVersion=" + schemaVersion + "]";
    }
}
