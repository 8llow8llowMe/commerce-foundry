package com.commercefoundry.global.properties;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 감사 로그 보관 정리 ({@code auth.admin-audit.retention.*}, #286). 기본값은 {@code application.yml} 에 둔다.
 * 보관 기간이 1년 미만이거나 주기 · 배치 크기가 0 이하면 기동하지 않는다.
 *
 * @param period 이 기간보다 오래된 행을 지운다. 기본 2년. 달력 기준 1년 미만은 거절한다
 * @param initialDelay 기동 뒤 첫 실행까지 기다리는 시간
 * @param fixedDelay 앞 실행이 끝난 뒤 다음 실행까지 기다리는 시간
 * @param batchSize 한 트랜잭션에서 지우는 행 수
 * @param maxBatchesPerRun 한 번 실행에서 지우는 배치 수 상한 — 쌓인 행이 많아도 한 실행이 끝없이 길어지지 않게
 */
@ConfigurationProperties(prefix = "auth.admin-audit.retention")
public record AdminAuditRetentionProperties(
    Period period,
    Duration initialDelay,
    Duration fixedDelay,
    int batchSize,
    int maxBatchesPerRun
) {

    /** 윤년이 아닌 날. 365일이 달력 1년과 같아, 하한 판정이 날짜에 따라 흔들리지 않는다. */
    private static final LocalDate MINIMUM_ANCHOR = LocalDate.of(2025, 1, 1);

    public AdminAuditRetentionProperties {
        requireAtLeastOneYear(period);
        requirePositive(initialDelay, "auth.admin-audit.retention.initial-delay");
        requirePositive(fixedDelay, "auth.admin-audit.retention.fixed-delay");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("auth.admin-audit.retention.batch-size 는 1 이상이어야 합니다: " + batchSize);
        }
        if (maxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("auth.admin-audit.retention.max-batches-per-run 은 1 이상이어야 합니다: " + maxBatchesPerRun);
        }
    }

    private static void requireAtLeastOneYear(Period period) {
        Objects.requireNonNull(period, "auth.admin-audit.retention.period 설정이 없습니다.");
        LocalDate end = MINIMUM_ANCHOR.plus(period);
        if (end.isBefore(MINIMUM_ANCHOR.plusYears(1))) {
            throw new IllegalArgumentException("auth.admin-audit.retention.period 는 1년 이상이어야 합니다: " + period);
        }
    }

    private static void requirePositive(Duration duration, String key) {
        Objects.requireNonNull(duration, key + " 설정이 없습니다.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(key + " 는 0 보다 커야 합니다: " + duration);
        }
    }
}
