package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 결제 반영 재고 복원 작업 관측 (#808). 호출자는 operations-service 하나다. 건수와 시각만 싣고 주문 · 결제 아이디는 싣지 않는다. */
@Schema(description = "결제 반영 재고 복원 작업 현황")
public record PaymentRestorationObservationInternalResponse(
    @Schema(description = "건수를 센 기준 시각 (UTC). 이 시각까지 등록된 작업만 센다")
    Instant observedAt,

    @Schema(description = "재고 복원을 기다리는 작업 수")
    long pendingCount,

    @Schema(description = "가장 오래된 복원 대기 작업의 등록 시각 (UTC), 없으면 null", nullable = true)
    Instant oldestPendingCreatedAt,

    @Schema(description = "사람 확인으로 멈춘(HELD) 작업 수")
    long heldCount,

    @Schema(description = "가장 오래된 사람 확인 작업의 등록 시각 (UTC), 없으면 null", nullable = true)
    Instant oldestHeldCreatedAt
) {
}
