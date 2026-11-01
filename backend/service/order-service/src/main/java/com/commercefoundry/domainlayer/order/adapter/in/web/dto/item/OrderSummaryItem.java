package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "주문 내역 한 줄")
public record OrderSummaryItem(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "주문 상태", example = "{\"code\":\"PENDING_PAYMENT\",\"name\":\"결제 대기\",\"description\":\"주문이 접수되었고 결제를 기다리고 있습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "주문 이름 — 첫 상품명, 여러 줄이면 \"첫 상품명 외 N건\"", example = "모시 생활한복 외 1건")
    String orderName,

    @Schema(description = "주문 줄 수", example = "2")
    int itemCount,

    @Schema(description = "결제 금액 (주문 통화)")
    MoneyItem totalAmount,

    @Schema(description = "지금 취소할 수 있는가 — 결제 전(결제 대기)만 true", example = "true")
    boolean cancelable,

    @Schema(description = "결제 기한 (UTC, ISO-8601)", example = "2026-10-18T09:30:00Z")
    Instant paymentDueAt,

    @Schema(description = "주문 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant createdAt
) {
}
