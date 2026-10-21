package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 주문 생성 요청. 금액은 계산에 쓰지 않는다 — 서버가 미리보기와 같은 코드로 다시 계산하고, {@code expectedTotalAmount} 는 대조에만 쓴다.
 * 같은 {@code Idempotency-Key} 로 다시 보낼 때는 본문을 바꾸지 않는다(바꾸면 409 {@code ORDER_019}). 로그에 남기지 않는다.
 */
@Schema(description = "주문 생성 요청")
public record OrderCreateRequest(

    @Schema(description = "주문할 줄 (1~50개). 같은 SKU · 같은 맞춤 제작 입력은 한 줄로 합쳐 보낸다(중복이면 400 ORDER_008)")
    @NotEmpty(message = OrderValidationMessage.ITEMS_REQUIRED)
    @Size(max = OrderQuoteCommand.MAX_LINES, message = OrderValidationMessage.ITEMS_SIZE_MAX)
    List<@NotNull(message = OrderValidationMessage.ITEM_REQUIRED) @Valid OrderLineRequest> items,

    @Schema(description = "배송지")
    @NotNull(message = OrderValidationMessage.DESTINATION_REQUIRED)
    @Valid
    ShippingAddressRequest shippingAddress,

    @Schema(description = "주문서에서 보여 준 결제 금액(선택) — 미리보기 amounts.totalAmount. 지금 계산과 다르면 재고를 잡기 전에 409(ORDER_010)다", nullable = true)
    @Valid
    ExpectedAmount expectedTotalAmount
) {

    public OrderCreateCommand toCommand() {
        return new OrderCreateCommand(items.stream().map(OrderLineRequest::toCommand).toList(), shippingAddress.toShippingAddress(),
            expectedTotalAmount == null ? null : Money.of(expectedTotalAmount.amount(), expectedTotalAmount.currency()));
    }

    @Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
    public record ExpectedAmount(

        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원, USD 는 센트)", example = "50000")
        @NotNull(message = OrderValidationMessage.EXPECTED_TOTAL_AMOUNT_REQUIRED)
        @PositiveOrZero(message = OrderValidationMessage.EXPECTED_TOTAL_AMOUNT_NON_NEGATIVE)
        Long amount,

        @Schema(description = "통화 (ISO 4217)", example = "KRW")
        @NotNull(message = OrderValidationMessage.EXPECTED_TOTAL_CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {
    }
}
