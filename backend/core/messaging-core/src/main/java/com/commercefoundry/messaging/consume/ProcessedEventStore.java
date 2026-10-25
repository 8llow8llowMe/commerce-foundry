package com.commercefoundry.messaging.consume;

import java.time.Instant;
import java.util.UUID;

/**
 * 처리한 이벤트 기록(inbox) SPI — 이벤트를 받는 서비스가 자기 스키마의 {@code processed_event} 테이블 · 엔티티로 구현한다 (#304 A-4, README).
 * {@link #exists} · {@link #record} 는 호출자(업무) 트랜잭션에 합류한다 — 상태를 바꾸는 커밋과 같은 커밋이어야 중복 처리를 막는다.
 */
public interface ProcessedEventStore {

    boolean exists(String consumerGroup, UUID eventId);

    /** 같은 키가 이미 있으면 커밋 때 유니크 위반으로 실패한다 — 동시에 두 번 받은 경우이고, 그 시도는 롤백 뒤 재전달에서 {@link #exists} 로 걸러진다. */
    void record(String consumerGroup, UUID eventId, String eventType, Instant processedAt);

    /** 보존 기간이 지난 기록을 {@code limit} 건까지 지운다(자기 트랜잭션). 지운 수를 돌려준다. */
    int deleteProcessedBefore(Instant cutoff, int limit);
}
