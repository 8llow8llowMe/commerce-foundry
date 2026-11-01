package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

/** 어드민 주문 상태 일괄 변경 (#17) — 같은 상태로 여러 주문을 바꾼다(예: 결제 완료 → 상품 준비중). */
@Schema(description = "주문 상태 일괄 변경")
public record OrderStatusBulkChangeRequest(

    @Schema(description = "주문 아이디 (1~100개, 숫자 문자열, 겹치지 않게)", example = "[\"4611686018427387904\"]")
    @NotNull(message = OrderValidationMessage.ORDER_IDS_SIZE)
    @Size(min = 1, max = 100, message = OrderValidationMessage.ORDER_IDS_SIZE)
    List<@NotNull(message = OrderValidationMessage.ORDER_ID_FORMAT) @Pattern(regexp = "[1-9][0-9]{0,18}", message = OrderValidationMessage.ORDER_ID_FORMAT) String> orderIds,

    @Schema(description = "바꿀 상태", example = "PREPARING")
    @NotNull(message = OrderValidationMessage.STATUS_REQUIRED)
    OrderStatus status,

    @Schema(description = "사유 (200자 이하, 선택) — 모든 주문의 이력에 남는다", nullable = true)
    @Size(max = 200, message = OrderValidationMessage.REASON_LENGTH_MAX)
    @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]*", message = OrderValidationMessage.REASON_INVALID_CHARACTER)
    String reason
) {

    /** 같은 주문을 두 번 넣지 않는다 — 두 번째가 첫 번째의 결과를 덮어 보이지 않게. */
    @Schema(hidden = true)
    @AssertTrue(message = OrderValidationMessage.ORDER_IDS_DUPLICATE)
    public boolean isOrderIdsDistinct() {
        return orderIds == null || new HashSet<>(orderIds).size() == orderIds.size();
    }

    /** 19자리 숫자가 long 범위를 넘으면 400 이다(파싱 오류가 500 으로 새지 않게). 형식이 틀린 값은 원소 검증이 먼저 막는다. */
    @Schema(hidden = true)
    @AssertTrue(message = OrderValidationMessage.ORDER_ID_FORMAT)
    public boolean isOrderIdsInRange() {
        if (orderIds == null) {
            return true;
        }
        for (String orderId : orderIds) {
            if (orderId != null && orderId.matches("[1-9][0-9]{0,18}")) {
                try {
                    Long.parseLong(orderId);
                } catch (NumberFormatException exception) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 배송 완료는 고객의 반품 · 구매 확정 기간에 닿아 사유와 함께 한 건씩 바꾼다 — 일괄로는 받지 않는다. */
    @Schema(hidden = true)
    @AssertTrue(message = OrderValidationMessage.BULK_DELIVERED_NOT_ALLOWED)
    public boolean isStatusAllowedInBulk() {
        return status != OrderStatus.DELIVERED;
    }

    public List<Long> orderIdValues() {
        return orderIds.stream().map(Long::parseLong).toList();
    }

    public OrderStatusChangeCommand toCommand() {
        return new OrderStatusChangeCommand(status, reason);
    }
}
