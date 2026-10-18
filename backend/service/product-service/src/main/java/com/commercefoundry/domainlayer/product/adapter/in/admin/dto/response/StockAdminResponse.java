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

    @Schema(description = "판매 가능 수량 (선점분 제외). 다음 조정의 expectedQuantity 로 쓴다", example = "12")
    long availableQuantity,

    @Schema(description = "재고 마지막 수정 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant updatedAt
) {
}
