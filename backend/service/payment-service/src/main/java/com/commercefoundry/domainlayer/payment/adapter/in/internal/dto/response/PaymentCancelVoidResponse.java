package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 결제 후 취소 무환불 판정 응답 (#691, {@code payment-guide.md} §8-1). 판정은 오류가 아니라 이 데이터다 — order 는 {@code outcome} 이
 * {@code NO_REFUND_CONFIRMED} 일 때만 클레임을 거절한다. 결제키 · PG 원문은 싣지 않는다.
 */
@Builder
@Schema(description = "결제 후 취소 무환불 판정 응답")
public record PaymentCancelVoidResponse(

    @Schema(description = "결제 아이디", example = "4611686018427387906")
    String paymentId,

    @Schema(description = "클레임 아이디", example = "4611686018427387907")
    String claimId,

    @Schema(description = "판정 — NO_REFUND_CONFIRMED(무환불 확정 · 봉인) | REFUNDED(이 클레임으로 환불됨) | UNCONFIRMED(지금은 확정 못 함)",
        example = "NO_REFUND_CONFIRMED")
    String outcome,

    @Schema(description = "판정 이유 — CLAIM_NEVER_REQUESTED · PG_REJECTED · CLAIM_REFUND_SUCCEEDED · CLAIM_REFUND_PENDING · REJECTION_SETTLING · "
        + "OTHER_CANCEL_PENDING · RETAINED_SHORT · PG_UNAVAILABLE · PG_CHECK_UNSUPPORTED · PG_LEDGER_MISMATCH · PAYMENT_STATE_UNSUPPORTED · "
        + "CLAIM_STATE_UNSUPPORTED · LEDGER_CHANGED", example = "CLAIM_NEVER_REQUESTED")
    String reason,

    @Schema(description = "판정에 쓴 결제 상태 — code 가 PaymentStatus 제어 코드다",
        example = "{\"code\":\"DONE\",\"name\":\"결제 완료\",\"description\":\"결제가 승인되었습니다.\"}")
    CodeNameDescriptionMetadata paymentStatus,

    @Schema(description = "남은 결제 금액 — 결제액 − 이 결제의 확정 취소 합계")
    MoneyItem retainedAmount,

    @Schema(description = "판정에 쓴 스냅숏 시점(PG 대조 미확정이면 tx1)에 이 클레임이 payment 에서 영구 거부되는 상태(VOIDED 또는 REJECTED)인지 — outcome 과 따로 본다. "
        + "NO_REFUND_CONFIRMED 이면 언제나 true 다. 거절 여부는 outcome 으로만 정한다", example = "true")
    boolean sealed
) {
}
