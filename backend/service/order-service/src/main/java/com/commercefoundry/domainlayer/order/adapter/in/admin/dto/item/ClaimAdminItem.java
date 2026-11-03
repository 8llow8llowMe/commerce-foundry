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

    @Schema(description = "접수 시각 (UTC)")
    Instant requestedAt
) {
}
