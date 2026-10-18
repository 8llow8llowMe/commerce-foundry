package com.commercefoundry.domainlayer.product.adapter.in.scheduler;

import com.commercefoundry.domainlayer.product.application.port.in.StockSchedulerUseCase;
import com.commercefoundry.global.properties.StockProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 없이 만료된 재고 선점을 풀어 재고를 되돌린다 ({@code order-flow-guide.md} §3). 주기 · 배치 크기는 {@code stock.reservation.expiry-sweep.*} 다.
 *
 * <p>건마다 유스케이스를 따로 불러 트랜잭션을 나눈다 — 한 건이 실패하거나 확정과 겹쳐 409 가 나도 경고만 남기고 다음 건으로 간다.
 * 실패한 건은 RESERVED 로 남아 다음 실행이 다시 읽는다.
 *
 * <p>인스턴스가 여럿이어도 분산 락(ShedLock)을 두지 않는다. 같은 건을 두 인스턴스가 동시에 풀면 {@code @Version} 이 하나만 통과시키고,
 * 진 쪽은 409 로 롤백돼 재고가 두 번 늘지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationExpiryScheduler {

    private final StockSchedulerUseCase stockSchedulerUseCase;
    private final StockProperties stockProperties;

    @Scheduled(
        initialDelayString = "${stock.reservation.expiry-sweep.initial-delay}",
        fixedDelayString = "${stock.reservation.expiry-sweep.fixed-delay}"
    )
    public void expireReservations() {
        StockProperties.ExpirySweep sweep = stockProperties.reservation().expirySweep();
        int expired = 0;
        for (int batch = 0; batch < sweep.maxBatchesPerRun(); batch++) {
            List<Long> reservationIds = stockSchedulerUseCase.findExpiredReservationIds(sweep.batchSize());
            for (Long reservationId : reservationIds) {
                expired += expireOne(reservationId) ? 1 : 0;
            }
            if (reservationIds.size() < sweep.batchSize()) {
                break;
            }
        }
        if (expired > 0) {
            log.info("stock reservations expired. count={}", expired);
        }
    }

    /** 실패는 이 건만 롤백된다. 식별자만 남긴다. */
    private boolean expireOne(long reservationId) {
        try {
            return stockSchedulerUseCase.expire(reservationId);
        } catch (RuntimeException exception) {
            log.warn("stock reservation expire failed. reservationId={}, error={}", reservationId, exception.getClass().getSimpleName());
            return false;
        }
    }
}
