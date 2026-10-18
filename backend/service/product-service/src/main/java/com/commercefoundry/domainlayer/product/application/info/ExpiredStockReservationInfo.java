package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult;
import java.time.Instant;

/**
 * 만료 대상 선점 한 건. 스케줄러가 배치의 마지막 건을 다음 조회의 커서로 넘긴다 — 한 실행 안에서 실패한 건을 다시 읽지 않는다.
 */
public record ExpiredStockReservationInfo(long reservationId, Instant expiresAt) {

    public static ExpiredStockReservationInfo from(ExpiredStockReservationQueryResult result) {
        return new ExpiredStockReservationInfo(result.reservationId(), result.expiresAt());
    }

    public ExpiredStockReservationQueryResult toCursor() {
        return new ExpiredStockReservationQueryResult(reservationId, expiresAt);
    }
}
