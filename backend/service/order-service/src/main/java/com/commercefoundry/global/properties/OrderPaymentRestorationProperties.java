package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 결제 반영 재고 복원 스윕 설정 (#582, {@code order-flow-guide.md} §3-6).
 *
 * @param sweep 스윕 스케줄러
 */
@ConfigurationProperties("order.payment-restoration")
public record OrderPaymentRestorationProperties(@DefaultValue @NestedConfigurationProperty Sweep sweep) {

    public OrderPaymentRestorationProperties {
        Objects.requireNonNull(sweep, "sweep");
    }

    /**
     * 복원 대기 작업을 같은 클레임 아이디로 다시 부르는 스윕. 다음 시각은 선점할 때 {@code min(maxBackoff, initialBackoff × 2^(시도-1))} 뒤로 미룬다 —
     * 선점이 곧 lease 라 {@code initialBackoff} 는 복원 호출 read timeout 보다 충분히 커야 한다.
     *
     * @param enabled 스케줄러를 켜는가. 기본 false — 켜는 순서는 DDL → 스윕 ON → 재대조(#591) → 소비 게이트({@code deploy-guide.md} §4-4)
     * @param interval 스케줄러 주기 (1s~1h)
     * @param batchSize 한 번에 집는 작업 수 (1~100)
     * @param minAge 복원 의도가 생긴 뒤 스윕이 처음 집기까지 기다리는 시간 (0~1d) — 컨슈머의 인라인 복원 · 재전달과 겹치지 않게 한다
     * @param initialBackoff 첫 재시도 간격 (1s 이상)
     * @param maxBackoff 재시도 간격 상한 — {@code initialBackoff} 이상, 1d 이하
     */
    public record Sweep(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration interval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5m") Duration minAge,
        @DefaultValue("1m") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff
    ) {
        public Sweep {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(minAge, "minAge");
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("order.payment-restoration.sweep.interval must be 1s~1h: " + interval);
            }
            if (batchSize < 1 || batchSize > 100) {
                throw new IllegalArgumentException("order.payment-restoration.sweep.batch-size must be 1~100: " + batchSize);
            }
            if (minAge.isNegative() || minAge.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("order.payment-restoration.sweep.min-age must be 0~1d: " + minAge);
            }
            if (initialBackoff.compareTo(Duration.ofSeconds(1)) < 0 || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("order.payment-restoration.sweep backoff must be initial >= 1s and initial <= max <= 1d");
            }
        }
    }
}
