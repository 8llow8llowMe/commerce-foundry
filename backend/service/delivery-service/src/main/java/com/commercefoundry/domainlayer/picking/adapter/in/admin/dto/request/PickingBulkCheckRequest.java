package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.picking.application.command.PickingOrderCheckCommand;
import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSelection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "한 출고 묶음 안에서 검수할 주문 목록")
public record PickingBulkCheckRequest(
    @Schema(description = "서로 다른 주문 1~20건. 묶음의 대상 주문 수까지 보낼 수 있다.")
    @NotNull(message = PickingValidationMessage.BULK_CHECK_ITEMS)
    @Size(min = 1, max = PickingSelection.MAX_ORDERS, message = PickingValidationMessage.BULK_CHECK_ITEMS)
    List<@NotNull(message = PickingValidationMessage.BULK_CHECK_ITEM) @Valid Item> items
) {
    public List<PickingOrderCheckCommand> toCommands() {
        return items.stream().map(item -> new PickingOrderCheckCommand(Long.parseLong(item.orderId()), item.check().toQuantities())).toList();
    }

    public record Item(
        @Schema(description = "주문 식별자 문자열", example = "4611686018427387904")
        @NotNull(message = PickingValidationMessage.ORDER_ID)
        @Pattern(regexp = "[1-9][0-9]{0,18}", message = PickingValidationMessage.ORDER_ID)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.ORDER_ID)
        String orderId,

        @NotNull(message = PickingValidationMessage.BULK_CHECK_ITEM)
        @Valid PickingOrderCheckRequest check
    ) { }
}
