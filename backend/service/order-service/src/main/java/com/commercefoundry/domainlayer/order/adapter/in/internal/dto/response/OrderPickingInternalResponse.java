package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "배송 서비스 전용 주문 피킹 스냅숏. 개인정보는 포함하지 않는다.")
public record OrderPickingInternalResponse(
    @Schema(description = "주문 식별자")
    String orderId,

    @Schema(description = "현재 주문 상태")
    String status,

    @Schema(description = "배송 국가. 배송지 스냅숏이 없으면 null")
    String countryCode,

    @Schema(description = "주문 시점 상품 항목. 적격성 판정 전 원천 항목")
    List<Line> lines
) {
    public record Line(
        @Schema(description = "주문 시점 상품 식별자")
        String productId,

        @Schema(description = "SKU 식별자")
        String skuId,

        @Schema(description = "주문 수량")
        long quantity,

        @Schema(description = "주문 시점 맞춤 제작 여부")
        boolean customOrder
    ) { }
}
