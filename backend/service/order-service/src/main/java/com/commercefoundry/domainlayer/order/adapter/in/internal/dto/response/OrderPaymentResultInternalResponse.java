package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "저장된 최초 결제 반영 결과. 현재 주문 상태나 재고 복원 진행 상태가 아니다")
public record OrderPaymentResultInternalResponse(

    @Schema(description = "결제 아이디", example = "4611686018427387904")
    String paymentId,

    @Schema(description = "최초 반영 대상 주문 아이디", example = "4611686018427387905")
    String orderId,

    @Schema(description = "결제한 회원 아이디", example = "4611686018427387906")
    String memberId,

    @Schema(description = "최초 결제 반영 결과 제어 코드", allowableValues = {
        "PAID", "REJECTED_ORDER_CANCELED", "REJECTED_ORDER_NOT_FOUND", "REJECTED_PAYMENT_MISMATCH", "REJECTED_STOCK_UNAVAILABLE",
        "REJECTED_ALREADY_PAID", "REJECTED_PAYMENT_NOT_DONE"
    })
    String result,

    @Schema(description = "최초 반영 시 확인한 결제 금액 (통화 최소 단위 정수)")
    MoneyItem amount,

    @Schema(description = "최초 반영 시 확인한 승인 시각 (UTC). 거절 결과에서는 없을 수 있다", nullable = true)
    Instant approvedAt,

    @Schema(description = "확정한 재고 선점 아이디. PAID 는 필수, 취소 주문 거절은 있을 수 있고 나머지는 없다", nullable = true)
    String stockReservationId
) {
}
