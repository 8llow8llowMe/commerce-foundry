package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 외부 확인 환불 기록 응답 (#693, {@code payment-guide.md} §8). 판정은 오류가 아니라 이 데이터다 — order 는 {@code outcome} 이
 * {@code RECORDED} 또는 {@code ALREADY_REFUNDED} 일 때만 수동 환불을 확정한다. 결제키 · PG 원문 · 관측 잔액은 싣지 않는다.
 */
@Builder
@Schema(description = "외부 확인 환불 기록 응답")
public record PaymentCancelExternalRefundResponse(

    @Schema(description = "결제 아이디", example = "4611686018427387906")
    String paymentId,

    @Schema(description = "클레임 아이디", example = "4611686018427387907")
    String claimId,

    @Schema(description = "판정 — RECORDED(외부 확인 환불로 원장에 있음) | ALREADY_REFUNDED(우리 PG 취소가 이미 성공) | UNCONFIRMED(지금은 기록 못 함)",
        example = "RECORDED")
    String outcome,

    @Schema(description = "판정 이유 — PG_VERIFIED · CLAIM_ALREADY_RECORDED · CLAIM_REFUND_SUCCEEDED · PAYMENT_STATE_UNSUPPORTED · CLAIM_REFUND_PENDING · "
        + "REJECTION_SETTLING · CLAIM_VOIDED · CLAIM_STATE_UNSUPPORTED · OTHER_CANCEL_PENDING · RETAINED_SHORT · PG_UNAVAILABLE · PG_CHECK_UNSUPPORTED · "
        + "PG_LEDGER_MISMATCH · LEDGER_CHANGED", example = "PG_VERIFIED")
    String reason,

    @Schema(description = "기록된 채널 — RECORDED 에만 있다(이미 기록된 claimId 면 요청 채널이 아니라 기록된 채널). 그 밖은 null", example = "PG", nullable = true)
    String channel,

    @Schema(description = "응답 시점의 결제 상태 — code 가 PaymentStatus 제어 코드다. PG 밖 환불은 결제 상태를 바꾸지 않는다",
        example = "{\"code\":\"PARTIAL_CANCELED\",\"name\":\"부분 취소\",\"description\":\"승인 금액 중 일부가 취소되었습니다.\"}")
    CodeNameDescriptionMetadata paymentStatus,

    @Schema(description = "이 결제의 환불 확정 합계 — 우리 PG 취소 + 외부 확인 환불(PG · PG 밖)")
    MoneyItem refundedAmount,

    @Schema(description = "남은 결제 금액 — 결제액 − 환불 확정 합계")
    MoneyItem retainedAmount
) {
}
