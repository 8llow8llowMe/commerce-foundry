package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

@Schema(description = "출고 묶음 생성. 대상 주문을 적어도 1건 담을 수 있어야 한다.")
public record PickingBatchCreateRequest(
    @Schema(description = "서로 다른 주문 식별자 문자열, 1~20개")
    @NotNull(message = PickingValidationMessage.ORDERS)
    @Size(min = 1, max = 20, message = PickingValidationMessage.ORDERS)
    @UniqueElements(message = PickingValidationMessage.DUPLICATE_ORDER_IDS)
    List<@NotNull(message = PickingValidationMessage.ORDER_ID)
        @Pattern(regexp = "[1-9][0-9]{0,18}", message = PickingValidationMessage.ORDER_ID)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.ORDER_ID) String> orderIds
) {
    public List<Long> toOrderIds() {
        return orderIds.stream().map(Long::parseLong).toList();
    }
}
