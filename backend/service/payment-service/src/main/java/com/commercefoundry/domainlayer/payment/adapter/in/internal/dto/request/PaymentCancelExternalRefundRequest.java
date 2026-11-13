package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo.Channel;
import com.commercefoundry.shared.commerce.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 외부 확인 환불 기록 요청 (#693). 주문 · 금액 · 통화는 order-service 가 접수 때 클레임에 저장한 값이다 — 다시 계산하지 않는다.
 * 운영자 식별 · 사유 · 확정 시각 · 거래키는 받지 않는다(감사는 order 의 몫, 거래키 저장은 별도 이슈).
 * {@code channel} 은 문자열로 받아 검증한다 — 없거나 {@code PG} · {@code OFF_PG} 가 아니면 400 {@code PAYMENT_124} 다(역직렬화 폴백 100 으로 새지 않게).
 */
@Schema(description = "외부 확인 환불 기록 요청")
public record PaymentCancelExternalRefundRequest(

    @Schema(description = "클레임이 속한 주문 아이디 (문자열로 보내도 된다)", example = "4611686018427387905", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = PaymentValidationMessage.ORDER_ID_REQUIRED)
    @Positive(message = PaymentValidationMessage.ORDER_ID_POSITIVE)
    Long orderId,

    @Schema(description = "접수 때 클레임에 저장한 환불액 — 최소 화폐 단위 정수", example = "129000")
    @Positive(message = PaymentValidationMessage.AMOUNT_VALUE_POSITIVE)
    long amount,

    @Schema(description = "환불 통화", example = "KRW")
    @NotNull(message = PaymentValidationMessage.CURRENCY_REQUIRED)
    CurrencyCode currency,

    @Schema(description = "운영자가 신고한 환불 경로 — PG(PG 상점관리자에서 환불) | OFF_PG(계좌 송금 등 PG 밖 환불)", example = "PG",
        allowableValues = {"PG", "OFF_PG"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = PaymentValidationMessage.EXTERNAL_REFUND_CHANNEL_INVALID)
    @Pattern(regexp = "PG|OFF_PG", message = PaymentValidationMessage.EXTERNAL_REFUND_CHANNEL_INVALID)
    String channel
) {

    /** 검증을 통과한 뒤에만 부른다. */
    public Channel channelValue() {
        return Channel.valueOf(channel);
    }
}
