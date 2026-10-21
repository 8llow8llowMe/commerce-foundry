package com.commercefoundry.domainlayer.order.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingFeeItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "주문. 새로 만들면 201, 같은 Idempotency-Key 의 재요청이면 같은 주문을 지금 상태로 200")
public record OrderCreateResponse(

    @Schema(description = "주문 아이디 — 고객 · 상담에 쓰는 주문 식별자이자 결제 준비에 쓰는 값", example = "4611686018427387904")
    String orderId,

    @Schema(
        description = "주문 상태 — 생성 직후 PENDING_PAYMENT(결제 대기)",
        example = "{\"code\":\"PENDING_PAYMENT\",\"name\":\"결제 대기\",\"description\":\"주문이 접수되었고 결제를 기다리고 있습니다.\"}"
    )
    CodeNameDescriptionMetadata status,

    @Schema(description = "결제 기한 (UTC, ISO-8601) — 재고 선점 만료 시각. 지나면 재고가 풀리고 주문이 만료된다", example = "2026-10-18T09:30:00Z")
    Instant paymentDueAt,

    @Schema(description = "주문 통화 (ISO 4217) — 모든 금액이 이 통화다", example = "KRW")
    String currency,

    @Schema(description = "주문 항목 — 주문 시점 스냅숏, 요청 순서")
    List<OrderLineItem> items,

    @Schema(description = "금액 — amounts.totalAmount 가 결제할 금액이다")
    OrderAmountsItem amounts,

    @Schema(description = "배송비 내역 (주문 시점)")
    ShippingFeeItem shipping,

    @Schema(description = "배송지 (주문 시점)")
    ShippingAddressItem shippingAddress,

    @Schema(description = "주문 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant createdAt
) {
}
