package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 일괄 상태 변경의 주문 한 건 결과 (#17). 실패한 건만 골라 다시 보내면 된다 — 이미 바뀐 건은 다시 보내도 UNCHANGED 다. */
@Builder
@Schema(description = "일괄 상태 변경 한 건 결과")
public record OrderStatusChangeResultItem(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "결과 — CHANGED(바꿈) · UNCHANGED(이미 그 상태) · FAILED(못 바꿈)", example = "CHANGED")
    String result,

    @Schema(description = "못 바꾼 이유의 오류 코드 — 성공이면 null", nullable = true, example = "ORDER_020")
    String errorCode,

    @Schema(description = "못 바꾼 이유 — 성공이면 null", nullable = true, example = "현재 주문 상태(결제 대기)에서는 할 수 없는 요청입니다.")
    String message
) {
}
