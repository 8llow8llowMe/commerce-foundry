package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "어드민 재고 조정 결과 — 조정을 반영한 현재 재고")
public record StockAdminResponse(

    @Schema(description = "SKU 아이디", example = "4611686018427387904")
    String skuId,

    @Schema(description = "재고 관리 여부. false 면 수량과 무관하게 품절이 아니다 (무제한)", example = "true")
    boolean stockManaged,

    @Schema(
        description = "판매 가능 수량 — 결제를 기다리는 선점분(reservedQuantity)이 이미 빠진 값이다. 실물 수량이 아니다. "
            + "다음 조정의 expectedQuantity 로 쓰고, 실사 수량을 입력할 때는 quantity = 실물 − reservedQuantity 로 보낸다",
        example = "12"
    )
    long availableQuantity,

    @Schema(
        description = "결제를 기다리는 선점(RESERVED)이 차감해 둔 수량 합. 결제되면 판매로 확정되고, 만료 · 취소되면 availableQuantity 로 돌아온다",
        example = "3"
    )
    long reservedQuantity,

    @Schema(description = "재고 마지막 수정 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant updatedAt
) {
}
