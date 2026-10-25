package com.commercefoundry.messaging.outbox;

import java.time.Instant;
import java.util.List;

/**
 * outbox 저장소 SPI — 이벤트를 내는 서비스가 자기 스키마의 {@code outbox} 테이블 · 엔티티로 구현하고 빈으로 둔다 (#304 A-3, README).
 * 엔티티를 core 에 두지 않는 이유: 테이블은 서비스 스키마 · DDL 스냅숏의 일부이고, 서비스마다 Snowflake · 감사 칸 규칙을 따른다.
 *
 * <p>{@link #append} 는 호출자(업무) 트랜잭션에 합류한다 — 업무와 같은 커밋으로 저장돼야 한다. 나머지는 릴레이가 트랜잭션 밖에서 부르므로
 * 메서드마다 자기 짧은 트랜잭션을 연다. 상태가 아닌 칸만 바꾸는 조건부 UPDATE 다 ({@code coding-conventions.md} §9-1).
 */
public interface OutboxStore {

    /** 새 행을 넣는다 ({@code id} 가 null). 업무 트랜잭션 안에서만 불린다. */
    void append(OutboxRow row);

    /** 보낼 행 — {@code published_at is null and available_at <= now}, 아이디 오름차순(같은 키의 순서), {@code limit} 건까지. */
    List<OutboxRow> findPublishable(Instant now, int limit);

    /** 보냈다고 표시한다. 아직 표시되지 않았을 때만 바꾼다 — 바꾼 행이 없으면 false(다른 실행이 먼저 표시했다). */
    boolean markPublished(long id, Instant publishedAt);

    /** 보내지 못했다 — 시도 수를 올리고 실패 코드(예외 클래스 · Kafka 오류 코드만, 메시지 원문 금지)를 남긴다. 다음 주기에 다시 보낸다. */
    void markFailed(long id, String errorCode);

    /** 보존 기간이 지난 발행 완료 행을 아이디 순으로 {@code limit} 건까지 지운다. 지운 수를 돌려준다. */
    int deletePublishedBefore(Instant cutoff, int limit);
}
