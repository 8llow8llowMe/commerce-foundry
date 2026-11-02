package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.exception.StockAlertValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "SKU 품절 임박 기준 설정")
public record StockAlertPolicyRequest(

    @Schema(description = "판매 가능 수량이 이 값 이하일 때 점검 대상 (0은 품절만)", example = "5")
    @NotNull(message = StockAlertValidationMessage.THRESHOLD_REQUIRED)
    @PositiveOrZero(message = StockAlertValidationMessage.THRESHOLD_NON_NEGATIVE)
    Long thresholdQuantity
) {
}
