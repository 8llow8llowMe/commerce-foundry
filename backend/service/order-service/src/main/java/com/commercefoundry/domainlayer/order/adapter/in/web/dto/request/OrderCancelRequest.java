package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 결제 전 주문 취소 (#16). 자유 입력은 받지 않는다 — 사유 코드가 주문 이력과 재고 해제 이벤트에 그대로 실린다. */
@Schema(description = "주문 취소")
public record OrderCancelRequest(

    @Schema(description = "취소 사유 — CHANGE_OF_MIND(단순 변심) · ORDER_MISTAKE(주문 내용 잘못 선택) · PAYMENT_ISSUE(결제 문제) · OTHER(기타)", example = "CHANGE_OF_MIND")
    @NotNull(message = OrderValidationMessage.CANCEL_REASON_REQUIRED)
    OrderCancelReason reason
) {
}
