package com.commercefoundry.domainlayer.display.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "전시 섹션 아이템 — 미디어 또는 상품 하나")
public record DisplayElementItem(

    @Schema(description = "아이템 아이디", example = "4611686018427387905")
    String itemId,

    @Schema(description = "아이템 유형 — 화면 분기용 제어 코드. MEDIA 면 media, PRODUCT 면 product 가 있다", allowableValues = {"MEDIA", "PRODUCT"}, example = "MEDIA")
    String itemType,

    @Schema(description = "미디어. itemType 이 MEDIA 가 아니면 null", nullable = true)
    DisplayMediaItem media,

    @Schema(description = "상품 카드 — 상품 목록 항목과 같은 모양 · 값이다. itemType 이 PRODUCT 가 아니면 null", nullable = true)
    DisplayProductItem product,

    @Schema(description = "아이템 설명 문구. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "봄 신상", nullable = true)
    String caption
) {
}
