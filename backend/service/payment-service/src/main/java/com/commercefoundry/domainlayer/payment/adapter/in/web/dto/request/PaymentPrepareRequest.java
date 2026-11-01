package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.payment.application.command.PaymentPrepareCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 결제 준비 요청. 금액 · 주문명은 받지 않는다 — order-service 가 계산한 값을 서버가 직접 받는다 ({@code payment-guide.md} §3-1).
 */
@Schema(description = "결제 준비 요청")
public record PaymentPrepareRequest(

    @Schema(description = "결제할 주문 아이디 (문자열로 보내도 된다)", example = "4611686018427387905", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = PaymentValidationMessage.ORDER_ID_REQUIRED)
    @Positive(message = PaymentValidationMessage.ORDER_ID_POSITIVE)
    Long orderId
) {

    public PaymentPrepareCommand toCommand() {
        return new PaymentPrepareCommand(orderId);
    }
}
