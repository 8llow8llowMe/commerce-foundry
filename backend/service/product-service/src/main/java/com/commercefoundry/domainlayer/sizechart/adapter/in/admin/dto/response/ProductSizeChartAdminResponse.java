package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.ProductSizeRowAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateColumnAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "상품 사이즈표 (숨김 상품 포함, 값은 저장 단위)")
public record ProductSizeChartAdminResponse(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "템플릿 아이디", example = "4611686018427387905")
    String templateId,

    @Schema(description = "템플릿 이름", example = "여성 상의")
    String templateName,

    @Schema(description = "치수 저장 단위. 값은 변환하지 않는다", example = "{\"code\": \"CM\", \"name\": \"센티미터\", \"description\": \"치수를 센티미터로 둡니다.\"}")
    CodeNameDescriptionMetadata baseUnit,

    @Schema(description = "사이즈표 비고. 없으면 null", nullable = true)
    String note,

    @Schema(description = "템플릿 항목. 노출 순서 → 아이디 오름차순")
    List<SizeChartTemplateColumnAdminItem> columns,

    @Schema(description = "사이즈 행. 노출 순서 → 아이디 오름차순. 칸 값은 저장 단위 그대로다")
    List<ProductSizeRowAdminItem> rows
) {
}
