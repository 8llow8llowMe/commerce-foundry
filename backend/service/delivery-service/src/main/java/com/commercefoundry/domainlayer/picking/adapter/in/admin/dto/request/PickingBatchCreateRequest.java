package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "피킹 batch 생성. 현재는 모든 주문이 적격이어야 한다.")
public record PickingBatchCreateRequest(
    @Schema(description = "서로 다른 주문 식별자 문자열, 1~20개")
    @NotNull(message = PickingValidationMessage.ORDERS)
    @Size(min = 1, max = 20, message = PickingValidationMessage.ORDERS)
    List<@NotNull(message = PickingValidationMessage.ORDER_ID)
        @Pattern(regexp = "[1-9][0-9]{0,18}", message = PickingValidationMessage.ORDER_ID) String> orderIds
) {
    public List<Long> toOrderIds() {
        try {
            return orderIds.stream().map(Long::parseLong).toList();
        } catch (NumberFormatException exception) {
            throw new PickingException(PickingErrorCode.INVALID_BATCH, exception);
        }
    }
}
