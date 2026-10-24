package com.commercefoundry.domainlayer.job.domain.model;

import java.time.Duration;

/**
 * 작업 종류별 실행 옵션. null 인 칸은 서비스 기본값({@code operations.jobs.*})을 쓴다 — {@link #orDefaults}.
 *
 * @param leaseDuration 워커가 작업을 쥐는 시간. heartbeat 가 이만큼 연장하고, 끊기면 이 시간 뒤 다른 워커가 회수한다. heartbeat 주기의 3배 이상
 * @param maxAttempts 시도 횟수 상한 (1 이상) — 첫 실행 포함
 * @param maxRunDuration 한 시도의 실행 시간 상한. 넘으면 FAILED(RUN_TIMEOUT, 재시도 없음)로 끝내고 멈춤 신호를 준다
 * @param maxConcurrencyPerInstance 인스턴스 하나에서 이 종류를 동시에 실행하는 수 상한 (1 이상). 인스턴스 전체 상한({@code worker.concurrency})을 넘지 못한다
 */
public record JobHandlerOptions(Duration leaseDuration, Integer maxAttempts, Duration maxRunDuration, Integer maxConcurrencyPerInstance) {

    public JobHandlerOptions {
        requirePositiveIfPresent(leaseDuration, "leaseDuration");
        requirePositiveIfPresent(maxRunDuration, "maxRunDuration");
        if (maxAttempts != null && maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 는 1 이상이어야 합니다: " + maxAttempts);
        }
        if (maxConcurrencyPerInstance != null && maxConcurrencyPerInstance < 1) {
            throw new IllegalArgumentException("maxConcurrencyPerInstance 는 1 이상이어야 합니다: " + maxConcurrencyPerInstance);
        }
    }

    /** 모든 칸이 기본값이다. */
    public static JobHandlerOptions defaults() {
        return new JobHandlerOptions(null, null, null, null);
    }

    /** 빈 칸을 {@code defaults} 로 채운다. 결과는 모든 칸이 있다. */
    public JobHandlerOptions orDefaults(JobHandlerOptions defaults) {
        return new JobHandlerOptions(
            leaseDuration != null ? leaseDuration : defaults.leaseDuration(),
            maxAttempts != null ? maxAttempts : defaults.maxAttempts(),
            maxRunDuration != null ? maxRunDuration : defaults.maxRunDuration(),
            maxConcurrencyPerInstance != null ? maxConcurrencyPerInstance : defaults.maxConcurrencyPerInstance()
        );
    }

    private static void requirePositiveIfPresent(Duration duration, String name) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(name + " 는 0 보다 커야 합니다: " + duration);
        }
    }
}
