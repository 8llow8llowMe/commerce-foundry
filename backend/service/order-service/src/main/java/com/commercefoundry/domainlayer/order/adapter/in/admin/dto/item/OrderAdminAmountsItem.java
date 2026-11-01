package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;

/** 어드민 주문 상세의 금액 (#17) — 주문 시점에 받은 금액이다. */
@Schema(description = "주문 금액 (주문 시점)")
public record OrderAdminAmountsItem(

    @Schema(description = "상품 합계")
    MoneyItem itemsAmount,

    @Schema(description = "받은 배송비")
    MoneyItem shippingFee,

    @Schema(description = "결제 금액 = 상품 합계 + 배송비")
    MoneyItem totalAmount
) {
}
