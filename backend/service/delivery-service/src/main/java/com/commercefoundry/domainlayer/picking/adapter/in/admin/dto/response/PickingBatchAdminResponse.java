package com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "출고 묶음과 주문별 포장 검수 상태")
public record PickingBatchAdminResponse(
    @Schema(description = "출고 묶음 식별자", example = "4611686018427387904")
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

    @Schema(description = "묶음에서 제외된 주문과 사유")
    List<Exclusion> exclusions,

    @Schema(description = "검수 기록 순서대로 정렬한 결과 이력")
    List<Check> checks
) {
    public record Quantity(
        @Schema(description = "상품 옵션 식별자", example = "4611686018427387905")
        String skuId,

        @Schema(description = "수량")
        long quantity
    ) { }

    public record Order(
        @Schema(description = "주문 식별자", example = "4611686018427387906")
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

    public record Exclusion(
        @Schema(description = "제외된 주문 식별자", example = "4611686018427387907")
        String orderId,

        @Schema(description = "묶음에서 제외된 사유")
        CodeNameDescriptionMetadata reason
    ) { }

    public record Check(
        @Schema(description = "검수 요청 주문 식별자", example = "4611686018427387906")
        String orderId,

        @Schema(description = "검수 성공 여부")
        boolean successful,

        @Schema(description = "검수 결과 사유. NONE은 성공")
        CodeNameDescriptionMetadata reason,

        @Schema(description = "검수 요청 수량")
        List<Quantity> quantities
    ) { }
}
