package com.commercefoundry.domainlayer.aiusage.application.port.out;

import com.commercefoundry.domainlayer.aiusage.application.port.out.query.AiUsageMonthQueryResult;
import java.time.Instant;
import java.util.Optional;

/**
 * 월 카운터. 쓰기는 모두 조건부 UPDATE 한 문장이다 — 읽고 판정해 쓰지 않는다. 같은 월 행을 여러 호출이 동시에 바꿔도 PostgreSQL 행 잠금이 줄 세우고
 * 조건을 다시 평가해 한도를 함께 넘기지 않는다. 금액은 micro-USD 다.
 */
public interface AiUsageMonthRepositoryPort {

    /**
     * 예약 누계 + {@code estimateMicros} 가 {@code limitMicros} 이하일 때만 더하고 한도 스냅숏을 바꾼다. 호출자의 트랜잭션 안에서 돈다.
     *
     * @return 더했으면 {@code true}. 한도를 넘거나 그 월 행이 없으면 {@code false}
     */
    boolean reserve(String period, long estimateMicros, long limitMicros, Instant now);

    /**
     * 그 월 행을 새 트랜잭션에서 만든다. 다른 쪽이 먼저 만들었으면(유니크 키 위반) 만들지 않는다.
     *
     * @return 이번에 만들었으면 {@code true}
     */
    boolean createIfAbsent(String period, long limitMicros, Instant now);

    /**
     * 진행 중 예상치를 확정 비용으로 옮긴다 — 예약 누계 −{@code estimateMicros} +{@code actualMicros}, 확정 누계 +{@code actualMicros}, 호출 수 +{@code calls}.
     * 호출자의 트랜잭션 안에서 돈다.
     *
     * @throws IllegalStateException 그 월 행이 없다 — 예약이 만든 행이라 있어야 한다
     */
    void settle(String period, long estimateMicros, long actualMicros, int calls, Instant now);

    /**
     * 예약 누계가 {@code thresholdMicros} 이상이고 아직 경고하지 않았으면 경고 시각을 세운다.
     *
     * @return 이번에 처음 세웠으면 {@code true}
     */
    boolean markWarned(String period, long thresholdMicros, Instant now);

    /**
     * 정산 뒤 — 예약 누계가 한도 이상이고 아직 초과를 표시하지 않았으면 초과 시각을 세운다.
     *
     * @return 이번에 처음 세웠으면 {@code true}
     */
    boolean markExceededAtLimit(String period, long limitMicros, Instant now);

    /**
     * 예약 거절 — "지금 이 예상치를 넣으면 한도를 넘는다"(누계 + 예상치 &gt; 한도)이고 아직 초과를 표시하지 않았을 때만 초과 시각을 세운다.
     * 예약 UPDATE 와 이 UPDATE 사이에 누계가 줄었으면(정산 · 다른 트랜잭션이 월 행을 막 만듦) 세우지 않는다.
     *
     * @return 이번에 처음 세웠으면 {@code true}
     */
    boolean markExceededByReserve(String period, long estimateMicros, long limitMicros, Instant now);

    Optional<AiUsageMonthQueryResult> find(String period);
}
