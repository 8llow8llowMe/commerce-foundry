package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "어드민 전시 아이템")
public record DisplayElementAdminItem(

    @Schema(description = "아이템 아이디 — 섹션 수정(전체 교체)마다 새로 발급된다", example = "4611686018427387905")
    String itemId,

    @Schema(description = "아이템 유형", allowableValues = {"MEDIA", "PRODUCT"}, example = "PRODUCT")
    String itemType,

    @Schema(description = "미디어. itemType 이 MEDIA 가 아니면 null", nullable = true)
    DisplayMediaAdminItem media,

    @Schema(description = "가리키는 상품. itemType 이 PRODUCT 가 아니면 null", nullable = true)
    DisplayProductAdminItem product,

    @Schema(description = "아이템 설명 문구. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String caption
) {
}
