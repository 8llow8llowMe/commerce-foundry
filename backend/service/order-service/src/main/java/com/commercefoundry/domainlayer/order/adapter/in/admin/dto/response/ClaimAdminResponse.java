package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminRecoveryItem;
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
    ClaimAdminRecoveryItem recovery
) {
}
