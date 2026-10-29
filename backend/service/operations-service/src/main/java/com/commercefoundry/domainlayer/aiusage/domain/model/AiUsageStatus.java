package com.commercefoundry.domainlayer.aiusage.domain.model;

/**
 * 원장 호출 행의 상태. 예약하면 {@link #PENDING}, 결과를 받으면 결과 구분 다섯 중 하나, 결과가 끝내 오지 않으면 회수가 {@link #ABANDONED} 로 닫는다.
 * PENDING 이 아닌 상태는 끝 — 다시 바뀌지 않는다.
 *
 * <p>정산 비용 규칙 ({@code bootstrap-conventions.md} AI 결과 분류표) — 응답을 받은 COMPLETED · UNUSABLE 은 실측, 보내지 않았거나 오류 응답인
 * NOT_SENT · FAILED 는 0, 과금 여부를 모르는 TIMEOUT · ABANDONED 는 예약한 예상치를 그대로 남긴다.
 */
public enum AiUsageStatus {

    /** 예약했다 — 결과를 기다린다. 예상치만큼 월 한도를 쥐고 있다. */
    PENDING,
    /** 응답을 받아 결과로 썼다 — 실측 비용. */
    COMPLETED,
    /** 응답을 받았지만 쓰지 못했다(거절 · 잘림 · 형식 오류) — 과금됐다, 실측 비용. */
    UNUSABLE,
    /** 오류 응답 — 비용 0, 호출 수에는 넣는다. */
    FAILED,
    /** 쓸 수 있는 응답이 없다 — 과금됐는지 몰라 예상치를 비용으로 남긴다. */
    TIMEOUT,
    /** 보내지 않았다(동시 상한) — 비용 0, 호출 수에도 넣지 않는다. */
    NOT_SENT,
    /** 결과가 오지 않아 회수했다(프로세스가 죽는 등) — TIMEOUT 처럼 예상치를 비용으로 남긴다. */
    ABANDONED;

    public boolean isPending() {
        return this == PENDING;
    }

    /** 호출 결과로 받을 수 있는 상태 — PENDING(시작) · ABANDONED(회수 전용)가 아니다. */
    public boolean isOutcome() {
        return this != PENDING && this != ABANDONED;
    }

    /** 실측 사용량으로 비용을 계산한다(응답을 받았다). */
    public boolean chargesActualUsage() {
        return this == COMPLETED || this == UNUSABLE;
    }

    /** 월 호출 수에 넣는다 — 보내지 않은 NOT_SENT 만 뺀다. ABANDONED 는 보냈을 수 있어 넣는다. */
    public boolean countsAsCall() {
        return this != PENDING && this != NOT_SENT;
    }

    /**
     * 이 상태로 끝났을 때 남기는 비용.
     *
     * @param actualMicros   실측 비용 — {@link #chargesActualUsage()} 일 때만 쓴다
     * @param estimateMicros 예약한 예상치
     */
    public long settledCostMicros(long actualMicros, long estimateMicros) {
        return switch (this) {
            case COMPLETED, UNUSABLE -> actualMicros;
            case FAILED, NOT_SENT -> 0L;
            case TIMEOUT, ABANDONED -> estimateMicros;
            case PENDING -> throw new IllegalStateException("PENDING 은 끝난 상태가 아닙니다.");
        };
    }
}
