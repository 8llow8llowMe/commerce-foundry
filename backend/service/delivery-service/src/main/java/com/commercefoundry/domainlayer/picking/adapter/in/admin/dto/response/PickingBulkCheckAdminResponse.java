package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "일괄 포장 검수 결과")
public record PickingBulkCheckAdminResponse(
    @Schema(description = "출고 묶음 식별자", example = "4611686018427387904")
    String batchId,

    @Schema(description = "요청 순서대로 정렬한 주문별 결과")
    List<OrderResult> results
) {
    public record OrderResult(
        @Schema(description = "주문 식별자", example = "4611686018427387905")
        String orderId,

        @Schema(description = "검수 성공 여부")
        boolean successful,

        @Schema(description = "주문별 검수 사유. NONE은 성공, DELIVERY_037은 저장 충돌이다.")
        CodeNameDescriptionMetadata reason,

        @Schema(description = "새로고침한 뒤 같은 수량으로 다시 보낼 수 있는지 여부")
        boolean retryable
    ) { }
}
