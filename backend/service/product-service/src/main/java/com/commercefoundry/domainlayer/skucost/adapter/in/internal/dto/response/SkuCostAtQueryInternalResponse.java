package com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item.SkuCostAtInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "시점 원가 조회 응답")
public record SkuCostAtQueryInternalResponse(

    @Schema(description = "요청 순서(같은 SKU · 시각을 합친 뒤)의 항목. 없는 SKU 는 빠진다 — 호출한 쪽이 누락을 보고 판단한다")
    List<SkuCostAtInternalItem> items
) {
}
