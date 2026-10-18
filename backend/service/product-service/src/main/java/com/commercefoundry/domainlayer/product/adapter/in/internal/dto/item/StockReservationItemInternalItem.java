package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "재고 선점 항목 한 건")
public record StockReservationItemInternalItem(

    @Schema(description = "SKU 아이디", example = "9004")
    String skuId,

    @Schema(description = "선점 수량", example = "2")
    int quantity,

    @Schema(description = "재고를 차감했는가. 무제한(재고 관리 안 함) SKU 는 false 이고 해제 때 되돌리지 않는다", example = "true")
    boolean deducted
) {
}
