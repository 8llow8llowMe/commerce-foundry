package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartLineItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "장바구니")
public record CartResponse(

    @Schema(description = "줄 목록 — 최근에 담은 순. 비었으면 빈 배열")
    List<CartLineItem> items,

    @Schema(description = "구매 가능(ON_SALE)하고 수량이 상한 안인 줄의 금액 합. 그런 줄이 없으면 null — 배송비 · 할인은 주문서에서 계산한다", nullable = true)
    MoneyItem totalAmount,

    @Schema(description = "합계에 든 줄 수", example = "2")
    int purchasableCount
) {
}
