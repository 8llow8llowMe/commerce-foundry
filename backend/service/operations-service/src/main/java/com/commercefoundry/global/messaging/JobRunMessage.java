package com.commercefoundry.global.messaging;

import java.time.Instant;

/**
 * 작업 실행 신호의 페이로드 ({@code architecture-guide.md} §10) — 토픽 {@code <ns>.operations.job-run}, 키는 작업 아이디. 발행 adapter 와 리스너가 함께 쓰는 계약이다.
 *
 * <p>작업 payload · 결과는 싣지 않는다 — 워커가 {@code operations.job} 에서 다시 읽는다. 필드는 추가만 하고, 삭제 · 의미 변경은 {@code schemaVersion+1} 이다
 * ({@code coding-conventions.md} §12).
 *
 * @param jobId 작업 아이디 (Snowflake, 문자열 — JS 정밀도)
 * @param type 작업 종류
 * @param attempt 이 신호가 실행하라는 시도의 기준 — QUEUED 작업이 지금까지 가져간 횟수. 워커는 저장된 시도 번호와 같을 때만 가져간다
 * @param scheduledAt 예약 시각 — outbox 가 이 시각까지 발행을 미룬다
 */
public record JobRunMessage(String jobId, String type, int attempt, Instant scheduledAt) {

    public static final String EVENT_TYPE = "job-run";
    public static final String AGGREGATE_TYPE = "job";
    public static final int SCHEMA_VERSION = 1;
}
