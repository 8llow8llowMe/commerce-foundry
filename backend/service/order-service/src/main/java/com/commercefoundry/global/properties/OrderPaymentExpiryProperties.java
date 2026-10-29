package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 미결제 주문 만료 스윕 설정 ({@code order.payment-expiry.sweep.*}, {@code order-flow-guide.md} §1-3). 잘못된 값은 기동을 막는다.
 *
 * @param enabled 스케줄러 빈을 만들지 여부. test 프로파일만 끈다
 * @param interval 첫 실행 지연이자 실행 간 간격(fixed delay). 1ms 이상
 * @param batchSize 한 배치에서 읽는 주문 수. 1–100
 * @param maxBatchesPerRun 한 실행의 최대 배치 수. 1–10 — 밀린 주문이 많아도 한 실행이 길어지지 않게 한다
 */
@ConfigurationProperties("order.payment-expiry.sweep")
public record OrderPaymentExpiryProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("30s") Duration interval,
    @DefaultValue("100") int batchSize,
    @DefaultValue("10") int maxBatchesPerRun
) {

    public OrderPaymentExpiryProperties {
        Objects.requireNonNull(interval, "order.payment-expiry.sweep.interval 설정이 없습니다.");
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("order.payment-expiry.sweep.interval 은 1ms 이상이어야 합니다: " + interval);
        }
        requireRange(batchSize, 100, "order.payment-expiry.sweep.batch-size");
        requireRange(maxBatchesPerRun, 10, "order.payment-expiry.sweep.max-batches-per-run");
    }

    private static void requireRange(int value, int max, String key) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(key + " 는 1 이상 " + max + " 이하여야 합니다: " + value);
        }
    }
}
