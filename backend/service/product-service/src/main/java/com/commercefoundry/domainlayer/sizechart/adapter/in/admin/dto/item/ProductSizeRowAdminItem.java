package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "상품 사이즈표 행")
public record ProductSizeRowAdminItem(

    @Schema(description = "사이즈 이름", example = "44")
    String sizeLabel,

    @Schema(description = "사이즈 노출 순서 (0 이상)", example = "0")
    int displayOrder,

    @Schema(description = "항목 순서의 치수. 저장 값이다")
    List<ProductSizeCellAdminItem> cells
) {
}
