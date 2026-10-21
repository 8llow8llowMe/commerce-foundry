package com.commercefoundry.domainlayer.order.adapter.in.web.dto.response;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingFeeItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "주문서 금액 미리보기 — 주문 생성과 같은 계산이다. 저장하지 않는다")
public record OrderQuoteResponse(

    @Schema(description = "주문 통화 (ISO 4217) — 모든 금액이 이 통화다", example = "KRW")
    String currency,

    @Schema(description = "주문 항목 — 요청 순서")
    List<OrderLineItem> items,

    @Schema(description = "금액")
    OrderAmountsItem amounts,

    @Schema(description = "배송비 내역")
    ShippingFeeItem shipping
) {
}
