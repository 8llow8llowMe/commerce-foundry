package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.picking.application.command.PickingOrderCheckCommand;
import com.commercefoundry.domainlayer.picking.application.exception.PickingValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

@Schema(description = "주문 단건 검수에서 직접 센 SKU 수량과 스캔한 바코드 수량")
public record PickingSingleCheckRequest(
    @Schema(description = "상품 옵션 아이디별 직접 입력 수량. 빈 객체는 수량 부족으로 기록할 수 있다.")
    @Size(max = 100, message = PickingValidationMessage.QUANTITIES)
    Map<@Pattern(regexp = "[1-9][0-9]{0,18}", message = PickingValidationMessage.SKU_ID)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.SKU_ID) String,
        @NotNull(message = PickingValidationMessage.QUANTITY)
        @Positive(message = PickingValidationMessage.QUANTITY)
        @Digits(integer = 19, fraction = 0, message = PickingValidationMessage.QUANTITY)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.QUANTITY) BigDecimal> quantities,

    @Schema(description = "바코드별 스캔 수량. 대소문자와 선행 0을 보존한다. 같은 SKU를 직접 입력한 수량과 합산한다.")
    @Size(max = 100, message = PickingValidationMessage.BARCODE_INPUTS)
    Map<@Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = PickingValidationMessage.BARCODE) String,
        @NotNull(message = PickingValidationMessage.QUANTITY)
        @Positive(message = PickingValidationMessage.QUANTITY)
        @Digits(integer = 19, fraction = 0, message = PickingValidationMessage.QUANTITY)
        @DecimalMax(value = "9223372036854775807", message = PickingValidationMessage.QUANTITY) BigDecimal> barcodeQuantities
) {
    @AssertTrue(message = PickingValidationMessage.QUANTITIES)
    @Schema(hidden = true)
    public boolean isCheckInputPresent() {
        return quantities != null || (barcodeQuantities != null && !barcodeQuantities.isEmpty());
    }

    @AssertTrue(message = PickingValidationMessage.BARCODE_INPUTS)
    @Schema(hidden = true)
    public boolean isCheckInputSizeValid() {
        int manualCount = quantities == null ? 0 : quantities.size();
        int barcodeCount = barcodeQuantities == null ? 0 : barcodeQuantities.size();
        return manualCount + barcodeCount <= 100;
    }

    public PickingOrderCheckCommand toCommand(long orderId) {
        return new PickingOrderCheckCommand(orderId,
            quantities == null ? Map.of() : PickingOrderCheckRequest.toQuantities(quantities),
            barcodeQuantities == null ? Map.of() : barcodeQuantities.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().longValueExact()
            )));
    }
}
