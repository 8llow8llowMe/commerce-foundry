package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.validation.UniqueCustomOptionKeys;
import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.exception.CartValidationMessage;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 장바구니에 담기 — 단건 담기 본문이자 병합 요청의 한 항목이다. 금액은 받지 않는다 — 가격은 조회할 때마다 product-service 의 현재 값이다.
 */
@Schema(description = "장바구니에 담을 상품")
public record CartItemAddRequest(

    @Schema(description = "SKU 아이디 — 상품 상세 skus[].skuId. 문자열로 보내도 된다", example = "9004")
    @NotNull(message = CartValidationMessage.SKU_ID_REQUIRED)
    @Positive(message = CartValidationMessage.SKU_ID_POSITIVE)
    Long skuId,

    @Schema(description = "수량 (1~99). 같은 상품 · 같은 맞춤 제작 입력이 이미 있으면 그 줄에 더한다", example = "1")
    @NotNull(message = CartValidationMessage.QUANTITY_REQUIRED)
    @Min(value = 1, message = CartValidationMessage.QUANTITY_RANGE)
    @Max(value = 99, message = CartValidationMessage.QUANTITY_RANGE)
    Integer quantity,

    @Schema(description = "맞춤 제작 입력 (최대 20개, fieldKey 중복 불가). 맞춤 제작 상품에만 보낸다. 없으면 null 또는 빈 배열", nullable = true)
    @Size(max = CustomOptions.MAX_SIZE, message = CartValidationMessage.CUSTOM_OPTIONS_SIZE_MAX)
    @UniqueCustomOptionKeys(message = CartValidationMessage.CUSTOM_OPTION_KEY_DUPLICATED)
    List<@NotNull(message = CartValidationMessage.CUSTOM_OPTION_REQUIRED) @Valid CustomOptionRequest> customOptions
) {

    public CartItemAddCommand toCommand() {
        List<CustomOptionRequest> options = customOptions == null ? List.of() : customOptions;
        return new CartItemAddCommand(skuId, quantity, CustomOptions.of(options.stream().map(CustomOptionRequest::toCustomOption).toList()));
    }
}
