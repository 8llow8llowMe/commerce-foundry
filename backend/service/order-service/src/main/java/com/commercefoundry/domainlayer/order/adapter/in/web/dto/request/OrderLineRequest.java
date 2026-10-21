package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation.UniqueCustomOptionKeys;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 주문할 줄 하나. 금액은 받지 않는다 — 단가는 product-service 의 현재 판매가다.
 */
@Schema(description = "주문할 상품 한 줄")
public record OrderLineRequest(

    @Schema(description = "SKU 아이디 — 상품 상세 skus[].skuId. 문자열로 보내도 된다", example = "9004")
    @NotNull(message = OrderValidationMessage.SKU_ID_REQUIRED)
    @Positive(message = OrderValidationMessage.SKU_ID_POSITIVE)
    Long skuId,

    @Schema(description = "수량 (1~99). 같은 상품의 줄(옵션 · 맞춤 입력이 달라도) 합계는 상품의 1회 주문 최대 수량을 넘을 수 없다", example = "2")
    @NotNull(message = OrderValidationMessage.QUANTITY_REQUIRED)
    @Min(value = 1, message = OrderValidationMessage.QUANTITY_RANGE)
    @Max(value = OrderQuoteLine.MAX_QUANTITY, message = OrderValidationMessage.QUANTITY_RANGE)
    Integer quantity,

    @Schema(description = "맞춤 제작 입력 (최대 20개, fieldKey 중복 불가). 맞춤 제작 상품에만 보낸다. 없으면 null 또는 빈 배열", nullable = true)
    @Size(max = OrderCustomOptions.MAX_SIZE, message = OrderValidationMessage.CUSTOM_OPTIONS_SIZE_MAX)
    @UniqueCustomOptionKeys(message = OrderValidationMessage.CUSTOM_OPTION_KEY_DUPLICATED)
    List<@NotNull(message = OrderValidationMessage.CUSTOM_OPTION_REQUIRED) @Valid CustomOptionRequest> customOptions
) {

    public OrderItemCommand toCommand() {
        return new OrderItemCommand(skuId, quantity, customOptionsOf());
    }

    /** 정규화한 맞춤 제작 입력. 형식 검증을 지난 뒤에만 부른다. */
    public OrderCustomOptions customOptionsOf() {
        List<CustomOptionRequest> options = customOptions == null ? List.of() : customOptions;
        return OrderCustomOptions.of(options.stream().map(CustomOptionRequest::toCustomOption).toList());
    }
}
