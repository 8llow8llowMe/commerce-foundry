package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminRecoveryItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminReturnApprovalItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminReturnDecisionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "어드민 클레임 상세")
public record ClaimAdminResponse(
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

    @Schema(description = "접수 시각 (UTC)")
    Instant requestedAt,

    @Schema(description = "접수 항목. 주문 항목 아이디 오름차순")
    List<ClaimAdminLineItem> items,

    @Schema(description = "결제 후 취소 처리 상황 · 다시 시도 가능 여부 · 화면 버전 (#17)")
    ClaimAdminRecoveryItem recovery,

    @Schema(description = "반품 승인 기록 — 승인된 반품에만 있다(승인 전 · 거절 반품과 취소는 null)", nullable = true)
    ClaimAdminReturnApprovalItem returnApproval,

    @Schema(description = "반품 결정 미리보기 — 접수(REQUESTED) 반품에만 있다(그 밖은 null)", nullable = true)
    ClaimAdminReturnDecisionItem returnDecision
) {
}
