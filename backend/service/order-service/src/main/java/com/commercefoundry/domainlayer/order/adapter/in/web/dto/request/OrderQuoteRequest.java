package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "주문서 금액 미리보기 요청 — 금액은 보내지 않는다")
public record OrderQuoteRequest(

    @Schema(description = "주문할 줄 (1~50개). 같은 SKU · 같은 맞춤 제작 입력은 한 줄로 합쳐 보낸다(중복이면 400 ORDER_008)")
    @NotEmpty(message = OrderValidationMessage.ITEMS_REQUIRED)
    @Size(max = OrderQuoteCommand.MAX_LINES, message = OrderValidationMessage.ITEMS_SIZE_MAX)
    List<@NotNull(message = OrderValidationMessage.ITEM_REQUIRED) @Valid OrderLineRequest> items,

    @Schema(description = "배송비를 정하는 배송지")
    @NotNull(message = OrderValidationMessage.DESTINATION_REQUIRED)
    @Valid
    ShippingDestinationRequest destination
) {

    public OrderQuoteCommand toCommand() {
        return new OrderQuoteCommand(items.stream().map(OrderLineRequest::toCommand).toList(), destination.countryCode(), destination.normalizedPostalCode());
    }
}
