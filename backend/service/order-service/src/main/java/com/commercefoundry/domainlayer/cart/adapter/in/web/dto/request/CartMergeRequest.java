package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;
import com.commercefoundry.domainlayer.cart.application.exception.CartValidationMessage;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "로컬 장바구니 병합")
public record CartMergeRequest(

    @Schema(description = "로그인 전 로컬 장바구니의 항목 (최대 50개). 오래 전에 담은 것부터 보낸다 — 나중 항목일수록 최근 담은 줄로 위에 표시된다. "
        + "형식이 틀린 항목이 있으면 요청 전체가 400 이다")
    @NotNull(message = CartValidationMessage.ITEMS_REQUIRED)
    @Size(max = CartItem.MAX_LINES_PER_CART, message = CartValidationMessage.ITEMS_SIZE_MAX)
    List<@NotNull(message = CartValidationMessage.ITEM_REQUIRED) @Valid CartItemAddRequest> items
) {

    public CartMergeCommand toCommand() {
        return new CartMergeCommand(items.stream().map(CartItemAddRequest::toCommand).toList());
    }
}
