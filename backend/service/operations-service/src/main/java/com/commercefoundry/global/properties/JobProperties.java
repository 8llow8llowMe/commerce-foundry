package com.commercefoundry.global.properties;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.RetryPolicy;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 백그라운드 작업 엔진 설정. 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param worker 워커(실행 신호 소비 · 실행 · lease)
 * @param leaseSweep lease 만료 판정 트리거
 * @param retry 재시도 기본값 — 핸들러 옵션이 시도 상한을 덮는다
 * @param result 결과
 * @param planner 정기 작업 등록. 스케줄이 없으면 등록하지 않는다
 */
@ConfigurationProperties(prefix = "operations.jobs")
public record JobProperties(Worker worker, LeaseSweep leaseSweep, Retry retry, Result result, Planner planner) {

    public JobProperties {
        Objects.requireNonNull(worker, "operations.jobs.worker 설정이 없습니다.");
        Objects.requireNonNull(leaseSweep, "operations.jobs.lease-sweep 설정이 없습니다.");
        Objects.requireNonNull(retry, "operations.jobs.retry 설정이 없습니다.");
        Objects.requireNonNull(result, "operations.jobs.result 설정이 없습니다.");
        Objects.requireNonNull(planner, "operations.jobs.planner 설정이 없습니다.");
    }

    /** 핸들러가 비운 옵션 칸을 채울 기본값. 종류별 동시 상한의 기본은 인스턴스 전체 상한이다. */
    public JobHandlerOptions defaultHandlerOptions() {
        return new JobHandlerOptions(worker.defaultLease(), retry.maxAttempts(), worker.defaultMaxRunDuration(), worker.concurrency());
    }

    /**
     * @param enabled false 면 이 인스턴스는 실행 신호를 받지 않는다(등록 · 조회만). 다른 인스턴스가 실행한다
     * @param concurrency 인스턴스 하나에서 동시에 실행하는 작업 수 — 실행 스레드 수와 같다
     * @param redeliveryDelay 전체 실행 칸이 찼거나 종료 중일 때 신호를 늦춰 다시 받기까지의 시간 — 그동안 이 인스턴스의 컨슈머가 멈춘다.
     *                        종류별 칸이 찬 신호를 다시 낼 때의 지연이기도 하다
     *     ({@link #MAX_REDELIVERY_DELAY} 이하 — 컨슈머 {@code max.poll.interval.ms} 보다 한참 짧게)
     * @param defaultLease 워커가 작업을 쥐는 시간 기본값. heartbeat 가 연장한다
     * @param heartbeatInterval lease 연장 · 진행률 기록 주기. lease 의 1/3 이하 — 한두 번 놓쳐도 lease 가 남게
     * @param defaultMaxRunDuration 한 시도의 실행 시간 상한 기본값. 넘으면 FAILED(RUN_TIMEOUT, 재시도 없음)로 끝내고 멈춤 신호를 준다
     * @param shutdownGrace 종료 때 실행 중인 작업을 기다리는 시간. 넘으면 남은 작업을 QUEUED 로 돌려놓는다
     */
    public record Worker(
        boolean enabled, int concurrency, Duration redeliveryDelay, Duration defaultLease,
        Duration heartbeatInterval, Duration defaultMaxRunDuration, Duration shutdownGrace
    ) {

        public static final Duration MAX_REDELIVERY_DELAY = Duration.ofMinutes(1);

        public Worker {
            if (concurrency <= 0) {
                throw new IllegalArgumentException("operations.jobs.worker.concurrency 는 1 이상이어야 합니다: " + concurrency);
            }
            requirePositive(redeliveryDelay, "operations.jobs.worker.redelivery-delay");
            if (redeliveryDelay.compareTo(MAX_REDELIVERY_DELAY) > 0) {
                throw new IllegalArgumentException(
                    "operations.jobs.worker.redelivery-delay 는 " + MAX_REDELIVERY_DELAY + " 이하여야 합니다: " + redeliveryDelay
                );
            }
            requirePositive(defaultLease, "operations.jobs.worker.default-lease");
            requirePositive(heartbeatInterval, "operations.jobs.worker.heartbeat-interval");
            requirePositive(defaultMaxRunDuration, "operations.jobs.worker.default-max-run-duration");
            requirePositive(shutdownGrace, "operations.jobs.worker.shutdown-grace");
            requireHeartbeatWithinLease(heartbeatInterval, defaultLease, "operations.jobs.worker.default-lease");
        }
    }

    /**
     * lease 만료 판정 — 모든 인스턴스가 돌린다(분산 락 없음, 건마다 {@code @Version}). 워커를 끈 인스턴스도 돌린다.
     *
     * @param initialDelay 기동 뒤 첫 판정까지 기다리는 시간
     * @param fixedDelay 앞 판정이 끝난 뒤 다음 판정까지 기다리는 시간
     * @param batchSize 한 번에 읽는 후보 수
     * @param maxBatchesPerRun 한 번의 판정에서 읽는 배치 수 상한 — 남은 건은 다음 판정이 이어 간다
     */
    public record LeaseSweep(Duration initialDelay, Duration fixedDelay, int batchSize, int maxBatchesPerRun) {

        public LeaseSweep {
            requirePositive(initialDelay, "operations.jobs.lease-sweep.initial-delay");
            requirePositive(fixedDelay, "operations.jobs.lease-sweep.fixed-delay");
            if (batchSize <= 0) {
                throw new IllegalArgumentException("operations.jobs.lease-sweep.batch-size 는 1 이상이어야 합니다: " + batchSize);
            }
            if (maxBatchesPerRun <= 0) {
                throw new IllegalArgumentException("operations.jobs.lease-sweep.max-batches-per-run 는 1 이상이어야 합니다: " + maxBatchesPerRun);
            }
        }
    }

    /**
     * 지수 백오프 — {@code min(maxDelay, baseDelay × multiplier^(n−1)) × (1 ± jitterRatio)}.
     *
     * @param maxAttempts 시도 횟수 상한 기본값 (첫 실행 포함)
     * @param baseDelay 첫 실패 뒤 지연
     * @param multiplier 실패마다 곱하는 배수 (1 이상)
     * @param maxDelay jitter 전 지연 상한
     * @param jitterRatio 0 이상 1 미만
     */
    public record Retry(int maxAttempts, Duration baseDelay, double multiplier, Duration maxDelay, double jitterRatio) {

        public Retry {
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("operations.jobs.retry.max-attempts 는 1 이상이어야 합니다: " + maxAttempts);
            }
            requirePositive(baseDelay, "operations.jobs.retry.base-delay");
            requirePositive(maxDelay, "operations.jobs.retry.max-delay");
            // 배수 · jitter 범위 · 상한 ≥ 기본 지연은 RetryPolicy 가 본다 — 바인딩 때 만들어 잘못된 값이면 기동하지 않게 한다
            toPolicy(baseDelay, multiplier, maxDelay, jitterRatio);
        }

        public RetryPolicy toPolicy() {
            return toPolicy(baseDelay, multiplier, maxDelay, jitterRatio);
        }

        private static RetryPolicy toPolicy(Duration baseDelay, double multiplier, Duration maxDelay, double jitterRatio) {
            return new RetryPolicy(baseDelay, multiplier, maxDelay, jitterRatio);
        }
    }

    /** @param maxJsonBytes 결과 JSON 상한 (UTF-8 바이트). 넘으면 FAILED({@code RESULT_TOO_LARGE}) — 큰 결과는 결과 파일(PR-E)로 낸다 */
    public record Result(int maxJsonBytes) {

        /** 결과 JSON 은 행에 그대로 담겨 목록 · 이벤트로 읽힌다 — 이보다 크면 결과 파일로 낸다. */
        public static final int MAX_JSON_BYTES_LIMIT = 1024 * 1024;

        public Result {
            if (maxJsonBytes <= 0 || maxJsonBytes > MAX_JSON_BYTES_LIMIT) {
                throw new IllegalArgumentException("operations.jobs.result.max-json-bytes 는 1 ~ " + MAX_JSON_BYTES_LIMIT + " 이어야 합니다: " + maxJsonBytes);
            }
        }
    }

    /**
     * 정기 작업 등록. 인스턴스가 여럿이어도 ShedLock 으로 한 번만 등록하고, 같은 슬롯은 멱등 키로 막는다 ({@code architecture-guide.md} §10).
     *
     * @param enabled false 면 이 인스턴스는 등록 주기를 돌리지 않는다
     * @param initialDelay 기동 뒤 첫 등록까지 기다리는 시간
     * @param fixedDelay 앞 등록이 끝난 뒤 다음 등록까지 기다리는 시간
     * @param lockAtMost 락을 쥐는 상한. 등록이 이보다 길면 다른 인스턴스가 이어서 잡을 수 있다
     * @param catchUp 이 기간보다 오래된 슬롯은 등록하지 않는다. 그 안의 가장 최근 슬롯만 등록한다
     * @param schedules 비어 있으면 락을 잡지 않고 등록하지 않는다
     */
    public record Planner(
        boolean enabled, Duration initialDelay, Duration fixedDelay, Duration lockAtMost, Duration catchUp, List<Schedule> schedules
    ) {

        public Planner {
            requirePositive(initialDelay, "operations.jobs.planner.initial-delay");
            requirePositive(fixedDelay, "operations.jobs.planner.fixed-delay");
            requirePositive(lockAtMost, "operations.jobs.planner.lock-at-most");
            requirePositive(catchUp, "operations.jobs.planner.catch-up");
            schedules = schedules == null ? List.of() : List.copyOf(schedules);
            requireDistinctSchedules(schedules);
        }

        /** 일 · 주 · 월. 시각은 {@link Schedule#time()} 과 {@link Schedule#zone()} 이다. */
        public enum Cadence {
            DAILY, WEEKLY, MONTHLY
        }

        /**
         * @param type 등록된 핸들러의 종류
         * @param cadence 일 · 주 · 월
         * @param time 그 주기 안의 시각
         * @param zone 시각을 해석하는 시간대
         * @param dayOfWeek 주 단위일 때만
         * @param dayOfMonth 월 단위일 때만. 1~28 — 2월에도 있게
         * @param payloadJson 핸들러 입력. 없으면 {@code {}}
         * @param priority 기록용. 보통 0
         */
        public record Schedule(
            String type, Cadence cadence, LocalTime time, ZoneId zone, DayOfWeek dayOfWeek, Integer dayOfMonth, String payloadJson, int priority
        ) {

            public Schedule {
                Objects.requireNonNull(type, "operations.jobs.planner.schedules.type");
                Objects.requireNonNull(cadence, "operations.jobs.planner.schedules.cadence");
                Objects.requireNonNull(time, "operations.jobs.planner.schedules.time");
                Objects.requireNonNull(zone, "operations.jobs.planner.schedules.zone");
                if (!Job.TYPE_PATTERN.matcher(type).matches()) {
                    throw new IllegalArgumentException("operations.jobs.planner.schedules.type 은 [A-Z0-9_]{1,64} 이어야 합니다: " + type);
                }
                if (payloadJson == null) {
                    payloadJson = "{}";
                }
                if (payloadJson.isBlank() || Job.payloadBytes(payloadJson) > Job.MAX_PAYLOAD_BYTES) {
                    throw new IllegalArgumentException(
                        "operations.jobs.planner.schedules.payload-json 은 비우지 않고 " + Job.MAX_PAYLOAD_BYTES + " 바이트 이하여야 합니다."
                    );
                }
                switch (cadence) {
                    case DAILY -> {
                        if (dayOfWeek != null || dayOfMonth != null) {
                            throw new IllegalArgumentException("일 단위 스케줄에는 day-of-week · day-of-month 를 두지 않습니다: " + type);
                        }
                    }
                    case WEEKLY -> {
                        if (dayOfWeek == null || dayOfMonth != null) {
                            throw new IllegalArgumentException("주 단위 스케줄은 day-of-week 만 둡니다: " + type);
                        }
                    }
                    case MONTHLY -> {
                        if (dayOfWeek != null || dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 28) {
                            throw new IllegalArgumentException("월 단위 스케줄은 day-of-month 1~28 만 둡니다: " + type);
                        }
                    }
                }
            }
        }

        private static void requireDistinctSchedules(List<Schedule> schedules) {
            Set<String> seen = new HashSet<>();
            for (Schedule schedule : schedules) {
                String identity = schedule.type() + "|" + schedule.cadence() + "|" + schedule.zone() + "|" + schedule.time()
                    + "|" + schedule.dayOfWeek() + "|" + schedule.dayOfMonth();
                if (!seen.add(identity)) {
                    throw new IllegalArgumentException("operations.jobs.planner.schedules 에 같은 슬롯이 두 번 있습니다: " + schedule.type());
                }
            }
        }
    }

    /** lease 가 heartbeat 주기의 3배보다 짧으면 heartbeat 한 번만 늦어도 만료 판정이 회수한다. 핸들러 옵션의 lease 에도 같은 규칙을 건다. */
    public static void requireHeartbeatWithinLease(Duration heartbeatInterval, Duration lease, String leaseKey) {
        if (heartbeatInterval.multipliedBy(3).compareTo(lease) > 0) {
            throw new IllegalArgumentException(leaseKey + "(" + lease + ") 는 operations.jobs.worker.heartbeat-interval(" + heartbeatInterval + ")의 3배 이상이어야 합니다.");
        }
    }

    private static void requirePositive(Duration duration, String key) {
        Objects.requireNonNull(duration, key + " 설정이 없습니다.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(key + " 는 0 보다 커야 합니다: " + duration);
        }
    }
}
