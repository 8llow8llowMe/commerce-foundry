package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "어드민 전시 아이템이 가리키는 상품")
public record DisplayProductAdminItem(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "상품명. 상품 행이 없으면(데이터 오류) null", example = "모코 생활한복 저고리", nullable = true)
    String name,

    @Schema(description = "고객 노출 여부. false 면 고객 메인에서 이 아이템이 빠진다", example = "true")
    boolean visible
) {
}
