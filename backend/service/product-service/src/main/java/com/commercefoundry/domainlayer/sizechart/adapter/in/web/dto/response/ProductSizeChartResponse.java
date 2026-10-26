package com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartColumnItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartRowItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "고객 상품 사이즈표. 값은 displayUnit 기준이다")
public record ProductSizeChartResponse(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "템플릿 아이디", example = "4611686018427387905")
    String templateId,

    @Schema(description = "템플릿 이름", example = "여성 상의")
    String templateName,

    @Schema(description = "치수를 저장한 단위")
    CodeNameDescriptionMetadata baseUnit,

    @Schema(description = "이 응답의 치수 단위. unit 을 빼면 저장 단위와 같다")
    CodeNameDescriptionMetadata displayUnit,

    @Schema(description = "사이즈표 비고. 없으면 null", nullable = true)
    String note,

    @Schema(description = "항목. 노출 순서 → 아이디 오름차순")
    List<ProductSizeChartColumnItem> columns,

    @Schema(description = "사이즈 행. 칸 값은 displayUnit 으로 바꾼 값이다")
    List<ProductSizeChartRowItem> rows
) {
}
