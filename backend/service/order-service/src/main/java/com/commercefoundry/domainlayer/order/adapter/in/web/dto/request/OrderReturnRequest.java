package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.application.command.OrderReturnCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "고객 부분 반품 접수. 환불 금액·비용 부담은 아직 산정하지 않는다")
public record OrderReturnRequest(
    @Schema(description = "반품할 주문 항목 (1~50개)")
    @NotEmpty(message = OrderValidationMessage.ITEMS_REQUIRED)
    @Size(max = 50, message = OrderValidationMessage.ITEMS_SIZE_MAX)
    List<@NotNull(message = OrderValidationMessage.ITEM_REQUIRED) @Valid Item> items
) {
    public OrderReturnCommand toCommand() {
        return new OrderReturnCommand(items.stream().map(item -> new OrderReturnCommand.Item(item.orderItemId(), item.quantity(), item.reason())).toList());
    }

    @Schema(name = "OrderReturnRequestItem", description = "반품 항목")
    public record Item(
        @Schema(description = "주문 항목 아이디", example = "4611686018427387905")
        @NotNull(message = OrderValidationMessage.RETURN_ITEM_ID_REQUIRED)
        @Positive(message = OrderValidationMessage.RETURN_ITEM_ID_POSITIVE)
        Long orderItemId,

        @Schema(description = "반품할 수량 (1~99)", example = "1")
        @NotNull(message = OrderValidationMessage.QUANTITY_REQUIRED)
        @Min(value = 1, message = OrderValidationMessage.QUANTITY_RANGE)
        @Max(value = 99, message = OrderValidationMessage.QUANTITY_RANGE)
        Integer quantity,

        @Schema(description = "접수 사유. 비용 부담 정책을 뜻하지 않는다", allowableValues = {"CHANGE_OF_MIND", "DEFECTIVE", "WRONG_ITEM"})
        @NotNull(message = OrderValidationMessage.RETURN_REASON_REQUIRED)
        ClaimItem.Reason reason
    ) {

        /** 클레임 사유에는 결제 후 취소 사유도 있다(#16 PR-2) — 반품 접수는 반품 사유 3종만 받는다. 없음은 {@code @NotNull} 이 먼저 막는다. */
        @Schema(hidden = true)
        @AssertTrue(message = OrderValidationMessage.RETURN_REASON_INVALID)
        public boolean isReturnReason() {
            return reason == null || reason.isReturnReason();
        }
    }
}
