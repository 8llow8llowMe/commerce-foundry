package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 어드민 주문 상세의 항목 한 줄 (#17) — 주문 시점 스냅숏. 고객 응답과 DTO 를 나눈다(진입마다 자기 계약). */
@Builder
@Schema(description = "주문 항목 (주문 시점)")
public record OrderAdminLineItem(

    @Schema(description = "SKU 아이디", example = "9004")
    String skuId,

    @Schema(description = "상품 아이디", example = "501")
    String productId,

    @Schema(description = "상품명", example = "모시 저고리")
    String productName,

    @Schema(description = "옵션명", example = "M · 쪽빛")
    String optionName,

    @Schema(description = "단가")
    MoneyItem unitPrice,

    @Schema(description = "수량", example = "2")
    int quantity,

    @Schema(description = "줄 금액 = 단가 × 수량")
    MoneyItem lineAmount,

    @Schema(description = "맞춤 제작 상품인가 — 제작 중 단계를 거칠 수 있다")
    boolean customOrder,

    @Schema(description = "맞춤 제작 입력 (치수 등) — 제작에 쓴다")
    List<OrderAdminCustomOptionItem> customOptions
) {
}
