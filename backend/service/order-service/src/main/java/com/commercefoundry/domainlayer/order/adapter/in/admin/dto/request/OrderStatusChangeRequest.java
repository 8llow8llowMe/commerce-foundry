package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 어드민 주문 상태 변경 (#17). */
@Schema(description = "주문 상태 변경")
public record OrderStatusChangeRequest(

    @Schema(description = "바꿀 상태 — 상세의 nextStatuses 중 하나 (예: PREPARING 상품 준비중)", example = "PREPARING")
    @NotNull(message = OrderValidationMessage.STATUS_REQUIRED)
    OrderStatus status,

    @Schema(description = "사유 (200자 이하) — 주문 이력에 남는다. 배송 완료(DELIVERED)로 바꿀 때는 필수(배송을 확인한 근거)", example = "포장 시작", nullable = true)
    @Size(max = 200, message = OrderValidationMessage.REASON_LENGTH_MAX)
    @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]*", message = OrderValidationMessage.REASON_INVALID_CHARACTER)
    String reason
) {

    public OrderStatusChangeCommand toCommand() {
        return new OrderStatusChangeCommand(status, reason);
    }
}
