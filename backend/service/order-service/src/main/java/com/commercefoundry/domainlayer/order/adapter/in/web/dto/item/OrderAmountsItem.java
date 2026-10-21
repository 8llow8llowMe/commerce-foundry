package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "주문 금액 — totalAmount = itemsAmount + shippingFee. 모두 같은 통화다")
public record OrderAmountsItem(

    @Schema(description = "상품 합계 — Σ(단가 × 수량)")
    MoneyItem itemsAmount,

    @Schema(description = "배송비 (국내: 기본 배송비 또는 무료배송 0 + 도서산간 추가금, 해외: 기본료 + 무게 요금 또는 무료배송 0)")
    MoneyItem shippingFee,

    @Schema(description = "결제 금액 — 주문 생성 요청의 expectedTotalAmount 로 되돌려 보내면 그 사이 금액이 바뀌었을 때 409(ORDER_010)를 받는다")
    MoneyItem totalAmount
) {
}
