package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "병합에서 담지 못하고 건너뛴 항목")
public record CartMergeSkippedItem(

    @Schema(description = "요청 items 의 0부터 위치", example = "1")
    int index,

    @Schema(description = "그 항목의 SKU 아이디", example = "9005")
    String skuId,

    @Schema(
        description = "건너뛴 사유 — SKU_NOT_FOUND(없는 SKU) · NOT_PURCHASABLE(품절 · 판매 종료 · 숨김) · CUSTOM_OPTION_NOT_ALLOWED(맞춤 제작이 아닌데 입력이 있음) · "
            + "LINE_LIMIT_EXCEEDED(장바구니 50줄) · QUANTITY_LIMIT_EXCEEDED(1회 주문 최대 수량이 0)",
        allowableValues = {"SKU_NOT_FOUND", "NOT_PURCHASABLE", "CUSTOM_OPTION_NOT_ALLOWED", "LINE_LIMIT_EXCEEDED", "QUANTITY_LIMIT_EXCEEDED"},
        example = "NOT_PURCHASABLE"
    )
    String reason
) {
}
