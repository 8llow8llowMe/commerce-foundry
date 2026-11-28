package com.commercefoundry.domainlayer.order.application.port.out.query;

import java.time.Instant;

/**
 * 결제 반영 재고 복원 작업 관측 (#808) — 같은 스냅숏의 대기 · HELD 수와 가장 오래된 등록 시각. 수가 0 이면 시각은 null 이다.
 */
public record PaymentRestorationObservationQueryResult(long pendingCount, Instant oldestPendingCreatedAt, long heldCount, Instant oldestHeldCreatedAt) {
}
