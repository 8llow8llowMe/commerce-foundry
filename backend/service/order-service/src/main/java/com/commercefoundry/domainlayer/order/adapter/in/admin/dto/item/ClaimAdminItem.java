package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "어드민 클레임 목록 헤더")
public record ClaimAdminItem(
    @Schema(description = "클레임 아이디", example = "4611686018427387904")
    String claimId,

    @Schema(description = "주문 아이디", example = "4611686018427387905")
    String orderId,

    @Schema(description = "클레임 유형")
    CodeNameDescriptionMetadata type,

    @Schema(description = "클레임 상태")
    CodeNameDescriptionMetadata status,

    @Schema(description = "예상 환불액. null은 미산정이며 0원과 다르다", nullable = true)
    MoneyItem expectedRefund,

    @Schema(description = "환불할 결제 아이디 — 결제 후 취소에만 있다. 반품이면 null", nullable = true, example = "4611686018427387906")
    String paymentId,

    @Schema(description = "종결 실패 사유 코드 — 환불 실패 · 재고 복원 실패일 때만 있다", nullable = true, example = "PAYMENT_016")
    String failureCode,

    @Schema(description = "지금 단계(환불 · 재고 복원)의 미확정 재시도 횟수", example = "0")
    int attemptCount,

    @Schema(description = "재시도 스케줄러가 다시 집을 시각 (UTC) — 환불 대기 · 복원 대기일 때만 있다", nullable = true)
    Instant nextAttemptAt,

    @Schema(description = "운영자 수습 결정 사유. 없으면 null", nullable = true, example = "PG 거절 확인, 고객 안내 완료")
    String decisionReason,

    @Schema(description = "운영자 수습 결정을 한 관리자 회원 아이디. 없으면 null", nullable = true, example = "4611686018427387907")
    String decidedById,

    @Schema(description = "운영자 수습 결정 시각 (UTC). 없으면 null", nullable = true)
    Instant decidedAt,

    @Schema(description = "접수 시각 (UTC)")
    Instant requestedAt
) {
}
