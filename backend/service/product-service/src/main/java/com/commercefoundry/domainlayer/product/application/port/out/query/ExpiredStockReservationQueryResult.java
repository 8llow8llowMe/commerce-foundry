package com.commercefoundry.domainlayer.product.application.port.out.query;

import java.time.Instant;

/**
 * 만료 대상 선점 한 건 — 만료 스윕의 keyset 커서를 겸한다. 다음 배치는 {@code (expiresAt, reservationId)} 다음부터 읽는다.
 */
public record ExpiredStockReservationQueryResult(long reservationId, Instant expiresAt) {
}
