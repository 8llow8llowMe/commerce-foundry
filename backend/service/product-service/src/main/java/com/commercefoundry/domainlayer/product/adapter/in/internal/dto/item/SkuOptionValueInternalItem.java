package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "SKU 옵션 한 칸 (주문 항목 스냅숏용 이름)")
public record SkuOptionValueInternalItem(

    @Schema(description = "옵션 그룹 이름", example = "색상")
    String optionGroupName,

    @Schema(description = "옵션 값 이름", example = "아이보리")
    String optionValueName
) {
}
