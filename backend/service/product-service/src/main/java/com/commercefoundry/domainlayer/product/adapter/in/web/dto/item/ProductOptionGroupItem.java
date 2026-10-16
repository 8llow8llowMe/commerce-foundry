package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "옵션 그룹")
public record ProductOptionGroupItem(

    @Schema(description = "옵션 그룹 아이디", example = "501")
    String optionGroupId,

    @Schema(description = "옵션 그룹 이름", example = "색상")
    String name,

    @Schema(description = "노출 순서 (오름차순)", example = "1")
    int displayOrder,

    @Schema(description = "옵션 값. 노출 순서 → 아이디 오름차순")
    List<ProductOptionValueItem> values
) {
}
