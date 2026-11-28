package com.commercefoundry.domainlayer.order.application.info;

import java.time.Instant;

/**
 * 결제 반영 재고 복원 작업 관측 (#808) — 관측 시각까지 등록된 복원 대기 · 사람 확인(HELD) 작업의 수와 가장 오래된 등록 시각.
 * 수가 0 이면 시각은 null 이고, 시각이 있으면 관측 시각 이하다. 주문 · 결제 아이디는 담지 않는다.
 */
public record PaymentRestorationObservationInfo(
    Instant observedAt, long pendingCount, Instant oldestPendingCreatedAt, long heldCount, Instant oldestHeldCreatedAt
) {
}
