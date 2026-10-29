package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "어드민 상품 옵션 값")
public record ProductOptionValueAdminItem(

    @Schema(description = "옵션 값 아이디", example = "4611686018427387904")
    String optionValueId,

    @Schema(description = "옵션 값 이름")
    String name,

    @Schema(description = "진열 순서")
    int displayOrder,

    @Schema(description = "은퇴 시각. 사용 중이면 null", nullable = true)
    Instant retiredAt
) {
}
