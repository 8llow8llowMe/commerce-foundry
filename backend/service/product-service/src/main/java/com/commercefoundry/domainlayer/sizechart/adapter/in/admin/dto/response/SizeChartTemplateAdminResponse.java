package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateColumnAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "사이즈표 템플릿")
public record SizeChartTemplateAdminResponse(

    @Schema(description = "템플릿 아이디", example = "4611686018427387904")
    String templateId,

    @Schema(description = "템플릿 이름", example = "여성 상의")
    String name,

    @Schema(description = "치수 저장 단위", example = "{\"code\": \"CM\", \"name\": \"센티미터\", \"description\": \"치수를 센티미터로 둡니다.\"}")
    CodeNameDescriptionMetadata baseUnit,

    @Schema(description = "항목. 노출 순서 → 아이디 오름차순")
    List<SizeChartTemplateColumnAdminItem> columns
) {
}
