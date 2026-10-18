package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockReservationItemInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "재고 선점 — 생성 · 확정 · 해제 모두 같은 모양이다")
public record StockReservationResponse(

    @Schema(description = "재고 선점 아이디. 확정 · 해제 경로에 쓴다", example = "4611686018427388001")
    String reservationId,

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "선점 상태", allowableValues = {"RESERVED", "CONFIRMED", "RELEASED"}, example = "RESERVED")
    String status,

    @Schema(description = "만료 시각 (UTC, ISO-8601). RESERVED 로 이 시각이 지나면 스케줄러가 해제한다", example = "2026-10-18T09:30:00Z")
    Instant expiresAt,

    @Schema(description = "선점 항목. SKU 아이디 오름차순이고 같은 SKU 는 수량을 합친 한 건이다")
    List<StockReservationItemInternalItem> items
) {
}
