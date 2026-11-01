package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 서비스 간 결제용 주문 요약 (#14). 호출자는 payment-service 하나다. 재고 선점 아이디는 싣지 않는다. */
@Builder
@Schema(description = "서비스 간 결제용 주문 요약. 결제할 수 있는 상태인지는 호출자(payment-service)가 status 로 판정한다")
public record OrderPaymentSummaryInternalResponse(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "주문한 회원 아이디", example = "4611686018427387905")
    String memberId,

    @Schema(description = "주문 상태 제어 코드. PENDING_PAYMENT 가 아니어도 그대로 낸다", example = "PENDING_PAYMENT")
    String status,

    @Schema(description = "결제할 총액 (상품 합계 + 배송비, 주문 시점 스냅숏)")
    MoneyItem totalAmount,

    @Schema(description = "PG 주문명. 줄 1개면 상품명, N개면 \"{첫 상품명} 외 {N-1}건\", 최대 100 코드포인트", example = "생활한복 저고리 외 2건")
    String orderName,

    @Schema(description = "결제 기한 (UTC)", example = "2026-10-18T09:30:00Z")
    Instant paymentDueAt
) {
}
