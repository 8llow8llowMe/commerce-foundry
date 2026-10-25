package com.commercefoundry.domainlayer.job.application.info;

import java.time.Duration;
import java.util.Objects;

/**
 * 실행 신호 한 건을 받은 결과. REDELIVER 만 이 신호를 다시 받고, 나머지는 신호 처리를 끝낸다.
 *
 * @param redeliverAfter 다시 받을 때까지 기다릴 시간 (REDELIVER 만)
 */
public record JobDispatchResult(Outcome outcome, Duration redeliverAfter) {

    public static final JobDispatchResult STARTED = new JobDispatchResult(Outcome.STARTED, null);
    public static final JobDispatchResult DEFERRED = new JobDispatchResult(Outcome.DEFERRED, null);
    public static final JobDispatchResult SKIPPED = new JobDispatchResult(Outcome.SKIPPED, null);

    public JobDispatchResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.REDELIVER) != (redeliverAfter != null)) {
            throw new IllegalArgumentException("redeliverAfter 는 REDELIVER 일 때만 둡니다.");
        }
    }

    public static JobDispatchResult redeliver(Duration after) {
        return new JobDispatchResult(Outcome.REDELIVER, after);
    }

    public enum Outcome {
        /** 가져가 실행 스레드에 넘겼다 */
        STARTED,
        /** 지금 실행할 수 없어(종류별 칸이 참 · 예약 시각 전) 같은 시도의 신호를 늦게 다시 냈다 — 이 신호는 끝났다 */
        DEFERRED,
        /** 실행할 것이 없다(이미 가져감 · 끝남 · 다른 시도의 신호 · 다른 워커가 이김) — 버린다 */
        SKIPPED,
        /** 이 인스턴스가 지금 아무것도 실행할 수 없다(전체 칸이 참 · 종료 중) — 이 신호를 늦춰 다시 받는다 */
        REDELIVER
    }
}
