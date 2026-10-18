package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.cart.application.exception.CartValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "장바구니 수량 변경")
public record CartItemQuantityUpdateRequest(

    @Schema(description = "바꿀 수량 (1~99). 상품의 1회 주문 최대 수량을 넘을 수 없다", example = "2")
    @NotNull(message = CartValidationMessage.QUANTITY_REQUIRED)
    @Min(value = 1, message = CartValidationMessage.QUANTITY_RANGE)
    @Max(value = 99, message = CartValidationMessage.QUANTITY_RANGE)
    Integer quantity
) {
}
