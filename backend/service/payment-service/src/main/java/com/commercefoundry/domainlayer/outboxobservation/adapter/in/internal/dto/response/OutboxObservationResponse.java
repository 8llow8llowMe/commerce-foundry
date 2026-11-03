package com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "결제 서비스 발행 대기 현황")
public record OutboxObservationResponse(
    @Schema(description = "발행 가능 여부를 판단한 관측 시각 (UTC)")
    Instant observedAt,

    @Schema(description = "관측 시각까지 발행 가능해진 미발행 행 수")
    long readyUnpublishedCount,

    @Schema(description = "발행 가능한 미발행 행 중 가장 이른 발행 가능 시각 (UTC), 없으면 null", nullable = true)
    Instant oldestReadyAvailableAt
) {
}
