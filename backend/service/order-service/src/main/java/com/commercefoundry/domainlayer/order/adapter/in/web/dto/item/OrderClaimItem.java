package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "내 주문의 클레임(반품 · 결제 후 취소) 한 건 (#609)")
public record OrderClaimItem(
    @Schema(description = "클레임 아이디", example = "4611686018427387907")
    String claimId,

    @Schema(description = "유형 — RETURN(반품) · CANCELLATION(결제 후 취소)")
    CodeNameDescriptionMetadata type,

    @Schema(description = "상태 — REQUESTED · REFUNDED · COMPLETED · REFUND_FAILED · RESTORATION_FAILED")
    CodeNameDescriptionMetadata status,

    @Schema(description = "환불 예정액. null 은 아직 산정 전(반품 검수 전)이며 0원과 다르다", nullable = true)
    MoneyItem expectedRefund,

    @Schema(description = "접수 시각 (UTC)")
    Instant requestedAt,

    @Schema(description = "접수 항목. 주문 항목 아이디 오름차순")
    List<Line> items
) {
    @Schema(description = "클레임 항목")
    public record Line(
        @Schema(description = "주문 항목 아이디 — 주문 상세 items[].orderItemId 와 같다", example = "4611686018427387906")
        String orderItemId,

        @Schema(description = "수량", example = "1")
        int quantity,

        @Schema(description = "사유")
        CodeNameDescriptionMetadata reason
    ) {
    }
}
