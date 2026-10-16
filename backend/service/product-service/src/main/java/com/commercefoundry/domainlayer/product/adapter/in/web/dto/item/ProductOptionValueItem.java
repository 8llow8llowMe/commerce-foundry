package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "옵션 값")
public record ProductOptionValueItem(

    @Schema(description = "옵션 값 아이디", example = "5011")
    String optionValueId,

    @Schema(description = "옵션 값 이름", example = "쪽빛")
    String name,

    @Schema(description = "그룹 안의 노출 순서 (오름차순)", example = "1")
    int displayOrder
) {
}
