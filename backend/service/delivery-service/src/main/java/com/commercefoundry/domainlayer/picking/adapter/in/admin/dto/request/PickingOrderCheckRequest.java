package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

@Schema(description = "주문 검수. SKU별 최종 수량을 보내며 수량이 같으면 이전 결과를 재사용한다.")
public record PickingOrderCheckRequest(
    @Schema(description = "SKU 식별자 문자열을 키로 하는 수량. 빈 객체는 부족 검수로 기록한다.")
    @NotNull(message = PickingValidationMessage.QUANTITIES)
    @Size(max = 100, message = PickingValidationMessage.QUANTITIES)
    Map<@Pattern(regexp = "[1-9][0-9]{0,18}", message = PickingValidationMessage.SKU_ID) String,
        @NotNull(message = PickingValidationMessage.QUANTITY) @Positive(message = PickingValidationMessage.QUANTITY) Long> quantities
) {
    public Map<Long, Long> toQuantities() {
        try {
            Map<Long, Long> result = new LinkedHashMap<>();
            quantities.forEach((sku, quantity) -> result.put(Long.parseLong(sku), quantity));
            return Map.copyOf(result);
        } catch (NumberFormatException exception) {
            throw new PickingException(PickingErrorCode.INVALID_BATCH, exception);
        }
    }
}
