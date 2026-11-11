package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import com.commercefoundry.shared.commerce.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 무환불 판정 요청 (#691). 값은 order-service 가 접수 때 클레임에 저장한 것이다 — 다시 계산하지 않는다.
 * {@code orderId} 는 결제의 주문과 대조한다 — 다른 주문의 결제를 가리키면 409 {@code PAYMENT_019} 다.
 */
@Schema(description = "결제 후 취소 무환불 판정 요청")
public record PaymentCancelVoidRequest(

    @Schema(description = "클레임이 속한 주문 아이디 (문자열로 보내도 된다)", example = "4611686018427387905", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = PaymentValidationMessage.ORDER_ID_REQUIRED)
    @Positive(message = PaymentValidationMessage.ORDER_ID_POSITIVE)
    Long orderId,

    @Schema(description = "접수 때 클레임에 저장한 환불액 — 최소 화폐 단위 정수", example = "129000")
    @Positive(message = PaymentValidationMessage.AMOUNT_VALUE_POSITIVE)
    long amount,

    @Schema(description = "환불 통화", example = "KRW")
    @NotNull(message = PaymentValidationMessage.CURRENCY_REQUIRED)
    CurrencyCode currency
) {
}
