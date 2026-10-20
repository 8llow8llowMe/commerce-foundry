package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "재고 복원 항목 한 건")
public record StockRestorationItemInternalItem(

    @Schema(description = "SKU 아이디", example = "9004")
    String skuId,

    @Schema(description = "복원 수량", example = "1")
    int quantity,

    @Schema(description = "재고를 실제로 되돌렸는가. 선점 때 차감하지 않은 무제한(재고 관리 안 함) SKU 는 false 이고 기록만 남는다", example = "true")
    boolean restored
) {
}
