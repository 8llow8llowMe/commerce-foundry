package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "피킹 batch 상세. 검수 완료는 출고·송장 등록 완료를 의미하지 않는다.")
public record PickingBatchAdminResponse(
    @Schema(description = "batch 식별자")
    String batchId,

    @Schema(description = "낙관적 락 버전")
    Long version,

    @Schema(description = "모든 주문이 검수 성공했는지 여부")
    boolean inspectionComplete,

    @Schema(description = "전체 피킹 수량")
    long totalQuantity,

    @Schema(description = "SKU별 전체 피킹 수량")
    List<Quantity> pickingQuantities,

    @Schema(description = "주문별 스냅숏과 검수 성공 여부")
    List<Order> orders,

    @Schema(description = "검수 시도별 결과 이력")
    List<Check> checks
) {
    public record Quantity(
        @Schema(description = "SKU 식별자")
        String skuId,

        @Schema(description = "수량")
        long quantity
    ) { }

    public record Order(
        @Schema(description = "주문 식별자")
        String orderId,

        @Schema(description = "생성 시 주문 상태")
        String status,

        @Schema(description = "배송 국가 코드")
        String countryCode,

        @Schema(description = "검수 성공 여부")
        boolean checked,

        @Schema(description = "SKU별 주문 수량")
        List<Quantity> quantities
    ) { }

    public record Check(
        @Schema(description = "검수 요청 주문 식별자")
        String orderId,

        @Schema(description = "검수 성공 여부")
        boolean successful,

        @Schema(description = "검수 결과 사유. NONE은 성공")
        CodeNameDescriptionMetadata reason,

        @Schema(description = "검수 요청 수량")
        List<Quantity> quantities
    ) { }
}
