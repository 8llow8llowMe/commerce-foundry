package com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "한 SKU 의 한 시각에 적용되는 원가")
public record SkuCostAtInternalItem(

    @Schema(description = "SKU 아이디", example = "4611686018427387999")
    String skuId,

    @Schema(description = "조회 시각 — 요청 값을 마이크로초로 자른 값 (UTC ISO-8601)", example = "2026-10-29T01:00:00Z")
    Instant at,

    @Schema(description = "유형별 적용 원가 — GOODS → PACKAGING 순. 그 시각에 원가가 없는 유형은 빠진다(0원으로 채우지 않는다). 빈 배열이면 SKU 는 있으나 원가 미입력이다")
    List<SkuCostInternalItem> costs
) {
}
