package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 결제 반영 재고 복원 (#582, {@code order-flow-guide.md} §3-6). 스윕 실행 설정(주기 · 배치 · 락)은 스윕을 켠 컨텍스트에만 있는
 * {@link OrderPaymentRestorationSweepProperties} 다. 여기는 스윕 여부와 무관하게 컨슈머 · 스윕이 함께 읽는 값이다.
 *
 * @param minAge 최초 복원 작업의 다음 시각에만 반영한다
 * @param backlogWarnAge 가장 오래된 복원 대기 작업이 이만큼 지나면 스윕 틱이 WARN 을 남긴다
 * @param backlogErrorAge 이만큼 지나면 ERROR 다. WARN 보다 길어야 한다
 * @param sweep 스윕 켜짐 여부 — 결제 전 취소 거절의 프로브 실패를 스윕으로 넘길 수 있는지 판단한다
 */
@ConfigurationProperties("order.payment-restoration")
public record OrderPaymentRestorationProperties(
    @DefaultValue("5m") Duration minAge,
    @DefaultValue("30m") Duration backlogWarnAge,
    @DefaultValue("2h") Duration backlogErrorAge,
    @DefaultValue Sweep sweep
) {
    public OrderPaymentRestorationProperties {
        Objects.requireNonNull(minAge, "order.payment-restoration.min-age");
        Objects.requireNonNull(backlogWarnAge, "order.payment-restoration.backlog-warn-age");
        Objects.requireNonNull(backlogErrorAge, "order.payment-restoration.backlog-error-age");
        Objects.requireNonNull(sweep, "order.payment-restoration.sweep");
        if (minAge.isNegative() || minAge.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("order.payment-restoration.min-age must be between 0 and 1d");
        }
        if (backlogWarnAge.compareTo(Duration.ofMinutes(1)) < 0 || backlogErrorAge.compareTo(backlogWarnAge) <= 0
            || backlogErrorAge.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("order.payment-restoration backlog ages must satisfy 1m <= warn < error <= 7d");
        }
    }

    /** 스윕 켜짐 여부만 읽는다. 같은 키로 스윕 빈 등록을 고르는 조건은 {@code OrderPaymentRestorationLockConfig} 다. */
    public record Sweep(@DefaultValue("false") boolean enabled) {
    }
}
