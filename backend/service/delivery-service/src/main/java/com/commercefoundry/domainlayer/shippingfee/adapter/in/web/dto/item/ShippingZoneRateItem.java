package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "무게 구간 — 바로 아래 구간 상한 + 1g 부터 이 상한(포함)까지의 무게 요금")
public record ShippingZoneRateItem(

    @Schema(description = "상한 무게 (그램, 포함)", example = "500")
    int maxWeightGrams,

    @Schema(description = "무게 요금")
    MoneyItem fee
) {
}
