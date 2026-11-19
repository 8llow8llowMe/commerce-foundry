package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

@Schema(description = "주문 검수. SKU별 최종 수량을 보내며 수량이 같으면 이전 결과를 재사용한다.")
public record PickingOrderCheckRequest(
    @Schema(description = "상품 옵션 식별자 문자열을 키로 하는 양의 정수 수량. 빈 객체는 수량 부족으로 기록한다.")
    @NotNull(message = PickingValidationMessage.QUANTITIES)
    @Size(max = 100, message = PickingValidationMessage.QUANTITIES)
    Map<@Pattern(regexp = "[1-9][0-9]{0,18}", message = PickingValidationMessage.SKU_ID)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.SKU_ID) String,
        @NotNull(message = PickingValidationMessage.QUANTITY)
        @Positive(message = PickingValidationMessage.QUANTITY)
        @Digits(integer = 19, fraction = 0, message = PickingValidationMessage.QUANTITY)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.QUANTITY) BigDecimal> quantities
) {
    public Map<Long, Long> toQuantities() {
        return quantities.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            entry -> Long.parseLong(entry.getKey()), entry -> entry.getValue().longValueExact()
        ));
    }
}
