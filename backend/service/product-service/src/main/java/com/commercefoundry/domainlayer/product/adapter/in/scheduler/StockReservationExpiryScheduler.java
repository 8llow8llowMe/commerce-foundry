package com.commercefoundry.domainlayer.product.adapter.in.scheduler;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ExpiredStockReservationInfo;
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
 * <p>건마다 유스케이스를 따로 불러 트랜잭션을 나눈다 — 한 건이 실패하거나 확정과 겹쳐 409 가 나도 로그만 남기고 다음 건으로 간다.
 * 한 실행 안에서는 배치의 마지막 건을 커서로 넘겨 다음 건부터 읽는다(keyset) — 실패한 건이 RESERVED 로 남아도 같은 실행이 그 건을 다시 읽어
 * 배치를 채우지 않는다(head-of-line blocking 없음). 실패한 건은 다음 실행이 처음부터 다시 읽는다.
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
        ExpiredStockReservationInfo cursor = null;
        for (int batch = 0; batch < sweep.maxBatchesPerRun(); batch++) {
            List<ExpiredStockReservationInfo> reservations = stockSchedulerUseCase.findExpiredReservations(cursor, sweep.batchSize());
            for (ExpiredStockReservationInfo reservation : reservations) {
                expired += expireOne(reservation.reservationId()) ? 1 : 0;
            }
            if (reservations.size() < sweep.batchSize()) {
                break;
            }
            cursor = reservations.getLast();
        }
        if (expired > 0) {
            log.info("stock reservations expired. count={}", expired);
        }
    }

    /**
     * 실패는 이 건만 롤백된다. 식별자 · 오류 코드만 남긴다. 확정 · 다른 인스턴스와 겹친 409({@code STOCK_RESERVATION_CONFLICT})는 정상 경합이라 warn,
     * 그 밖의 업무 오류 · 예상하지 못한 예외는 error 다.
     */
    private boolean expireOne(long reservationId) {
        try {
            return stockSchedulerUseCase.expire(reservationId);
        } catch (ProductException exception) {
            String code = exception.getErrorCode().getCode();
            if (exception.getErrorCode() == ProductErrorCode.STOCK_RESERVATION_CONFLICT) {
                log.warn("stock reservation expire conflicted. reservationId={}, errorCode={}", reservationId, code);
            } else {
                log.error("stock reservation expire failed. reservationId={}, errorCode={}", reservationId, code);
            }
            return false;
        } catch (RuntimeException exception) {
            log.error("stock reservation expire failed. reservationId={}, error={}", reservationId, exception.getClass().getSimpleName(), exception);
            return false;
        }
    }
}
