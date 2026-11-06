package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 결제 후 고객 취소 설정 (#16 PR-2).
 *
 * @param allowWhileMaking 제작 중(MAKING) 주문도 고객이 취소할 수 있는가. 기본 false — 맞춤 제작이 들어간 뒤의 취소는 운영자가 본다
 * @param retry 환불 · 재고 복원 재시도 스케줄러
 */
@ConfigurationProperties("order.cancellation")
public record OrderCancellationProperties(
    @DefaultValue("false") boolean allowWhileMaking,
    @DefaultValue @NestedConfigurationProperty Retry retry
) {
    public OrderCancellationProperties {
        Objects.requireNonNull(retry, "retry");
    }

    /**
     * 미확정 환불 · 재고 복원의 재시도 (#16 PR-2). 다음 시각은 {@code min(maxBackoff, initialBackoff × 2^횟수)} 다.
     * 환불 단계에서 {@code maxAttempts} 를 넘으면 REFUND_FAILED 로 어드민에 넘긴다 — payment 의 취소 키 유효 기간(14일) 안에서 끝나야 한다.
     *
     * @param enabled 스케줄러를 켜는가. 기본 false — 운영 개방은 결제 개방(#15)과 함께 정한다
     * @param interval 스케줄러 주기
     * @param batchSize 한 번에 집는 클레임 수 (1~100)
     * @param maxAttempts 단계별 재시도 상한 (1~1000)
     * @param initialBackoff 첫 재시도 간격
     * @param maxBackoff 재시도 간격 상한 — {@code initialBackoff} 이상
     * @param lease 고객 요청이 접수한 클레임을 직접 처리할 시간(30초~1시간). 접수 때 다음 시도 시각을 {@code now + lease} 로 두어 스케줄러가 처리 중인 클레임을
     *     겹쳐 집지 않게 한다. <b>결제 취소 read timeout({@code spring.cloud.openfeign.client.config.paymentCancelClient.read-timeout}, 10초)보다
     *     충분히 커야 한다</b> — 작으면 응답을 기다리는 동안 스케줄러가 같은 환불을 다시 보낸다(멱등이라 돈은 한 번이지만 낭비 · 경합이다)
     */
    public record Retry(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration interval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("48") int maxAttempts,
        @DefaultValue("1m") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff,
        @DefaultValue("2m") Duration lease
    ) {
        public Retry {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            Objects.requireNonNull(lease, "lease");
            if (lease.compareTo(Duration.ofSeconds(30)) < 0 || lease.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("order.cancellation.retry.lease must be 30s~1h (longer than payment cancel read timeout): " + lease);
            }
            if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("order.cancellation.retry.interval must be 1s~1h: " + interval);
            }
            if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 1000) {
                throw new IllegalArgumentException("order.cancellation.retry batch-size must be 1~100 and max-attempts 1~1000");
            }
            if (initialBackoff.compareTo(Duration.ofSeconds(1)) < 0 || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("order.cancellation.retry backoff must be initial >= 1s and initial <= max <= 1d");
            }
            // 환불 키 유효 기간(14일) 안에서 상한까지 다 돌아야 REFUND_FAILED 판정이 같은 키로 끝난다
            if (maxBackoff.multipliedBy(maxAttempts).compareTo(Duration.ofDays(14)) > 0) {
                throw new IllegalArgumentException("order.cancellation.retry max-backoff × max-attempts must be within 14d (payment cancellation key lifetime)");
            }
        }
    }
}
