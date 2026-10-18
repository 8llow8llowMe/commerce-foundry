package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재고 설정. 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param reservation 재고 선점
 */
@ConfigurationProperties(prefix = "stock")
public record StockProperties(Reservation reservation) {

    public StockProperties {
        Objects.requireNonNull(reservation, "stock.reservation 설정이 없습니다.");
    }

    /**
     * @param ttl 선점 유지 시간 — 만료 시각 = 선점 시각 + ttl. PG 결제창 제한 시간보다 길어야 한다 (결제창을 연 고객의 재고가 먼저 풀리지 않게)
     * @param expirySweep 만료 스케줄러
     */
    public record Reservation(Duration ttl, ExpirySweep expirySweep) {

        public Reservation {
            requirePositive(ttl, "stock.reservation.ttl");
            Objects.requireNonNull(expirySweep, "stock.reservation.expiry-sweep 설정이 없습니다.");
        }
    }

    /**
     * 만료 스케줄러. 한 번 돌 때 {@code batchSize} 건씩 읽어 건마다 트랜잭션으로 풀고, 배치가 가득 차면 {@code maxBatchesPerRun} 번까지 이어 읽는다.
     *
     * @param initialDelay 기동 뒤 첫 실행까지 기다리는 시간
     * @param fixedDelay 앞 실행이 끝난 뒤 다음 실행까지 기다리는 시간
     * @param batchSize 한 번에 읽는 선점 수
     * @param maxBatchesPerRun 한 번 실행에서 읽는 배치 수 상한 — 쌓인 만료 건이 많아도 한 실행이 끝없이 길어지지 않게
     */
    public record ExpirySweep(Duration initialDelay, Duration fixedDelay, int batchSize, int maxBatchesPerRun) {

        public ExpirySweep {
            requirePositive(initialDelay, "stock.reservation.expiry-sweep.initial-delay");
            requirePositive(fixedDelay, "stock.reservation.expiry-sweep.fixed-delay");
            if (batchSize <= 0) {
                throw new IllegalArgumentException("stock.reservation.expiry-sweep.batch-size 는 1 이상이어야 합니다: " + batchSize);
            }
            if (maxBatchesPerRun <= 0) {
                throw new IllegalArgumentException("stock.reservation.expiry-sweep.max-batches-per-run 은 1 이상이어야 합니다: " + maxBatchesPerRun);
            }
        }
    }

    private static void requirePositive(Duration duration, String key) {
        Objects.requireNonNull(duration, key + " 설정이 없습니다.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(key + " 는 0 보다 커야 합니다: " + duration);
        }
    }
}
