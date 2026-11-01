package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 요청의 금액 — 최소 화폐 단위 정수 + 통화 코드 ({@code api-design-guide.md} §2-3). 통화는 모양(대문자 3자)만 본다 — 지원하지 않는 통화는
 * 결제 시도의 통화와 달라 금액 불일치(400 {@code PAYMENT_008})로 거절된다.
 */
@Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
public record MoneyRequest(

    @Schema(description = "최소 화폐 단위 금액 (KRW 는 원)", example = "129000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = PaymentValidationMessage.AMOUNT_VALUE_REQUIRED)
    @Positive(message = PaymentValidationMessage.AMOUNT_VALUE_POSITIVE)
    Long amount,

    @Schema(description = "통화 코드 (ISO 4217 대문자 3자)", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = PaymentValidationMessage.CURRENCY_REQUIRED)
    @Pattern(regexp = "[A-Z]{3}", message = PaymentValidationMessage.CURRENCY_FORMAT_INVALID)
    String currency
) {
}
