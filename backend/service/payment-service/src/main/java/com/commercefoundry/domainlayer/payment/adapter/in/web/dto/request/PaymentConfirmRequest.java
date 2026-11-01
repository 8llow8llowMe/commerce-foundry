package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.payment.application.command.PaymentConfirmCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 결제 승인 요청 — 토스 결제위젯 successUrl 의 {@code paymentKey} · {@code orderId} · {@code amount} 를 그대로 보낸다. 금액은 서버의 결제 시도 금액과 대조할 뿐이다.
 */
@Schema(description = "결제 승인 요청 (successUrl 쿼리 값)")
public record PaymentConfirmRequest(

    @Schema(description = "토스 결제키 (successUrl 의 paymentKey)", example = "tgen_20261021000000abcdEFGH", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = PaymentValidationMessage.PAYMENT_KEY_REQUIRED)
    @Size(max = Payment.PAYMENT_KEY_MAX_LENGTH, message = PaymentValidationMessage.PAYMENT_KEY_LENGTH_INVALID)
    String paymentKey,

    @Schema(description = "결제 준비가 내려 준 pgOrderId (successUrl 의 orderId)", example = "4611686018427387906", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = PaymentValidationMessage.PG_ORDER_ID_REQUIRED)
    @Size(min = Payment.PG_ORDER_ID_MIN_LENGTH, max = Payment.PG_ORDER_ID_MAX_LENGTH, message = PaymentValidationMessage.PG_ORDER_ID_LENGTH_INVALID)
    @Pattern(regexp = "[A-Za-z0-9_-]*", message = PaymentValidationMessage.PG_ORDER_ID_FORMAT_INVALID)
    String pgOrderId,

    @Schema(description = "결제 금액 (successUrl 의 amount, 통화는 결제 준비 응답의 통화)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = PaymentValidationMessage.AMOUNT_REQUIRED)
    @Valid
    MoneyRequest amount
) {

    public PaymentConfirmCommand toCommand() {
        return new PaymentConfirmCommand(paymentKey, pgOrderId, amount.amount(), amount.currency());
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8) — 레코드 기본 {@code toString} 은 원문을 찍는다. */
    @Override
    public String toString() {
        return "PaymentConfirmRequest[paymentKey=%s, pgOrderId=%s, amount=%s]".formatted(Payment.maskPaymentKey(paymentKey), pgOrderId, amount);
    }
}
