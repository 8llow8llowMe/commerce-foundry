package com.commercefoundry.domainlayer.display.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "전시 섹션 아이템 — 미디어 · 상품 또는 룩 참조 하나")
public record DisplayElementItem(

    @Schema(description = "아이템 아이디", example = "4611686018427387905")
    String itemId,

    @Schema(description = "아이템 유형 — 화면 분기용 제어 코드. MEDIA 면 media, PRODUCT 면 product, LOOK 면 look 이 있다", allowableValues = {"MEDIA", "PRODUCT", "LOOK"}, example = "MEDIA")
    String itemType,

    @Schema(description = "미디어. itemType 이 MEDIA 가 아니면 null", nullable = true)
    DisplayMediaItem media,

    @Schema(description = "상품 카드 — 상품 목록 항목과 같은 모양 · 값이다. itemType 이 PRODUCT 가 아니면 null", nullable = true)
    DisplayProductItem product,

    @Schema(description = "참조 룩의 현재 화보와 구성 상품. LOOK 아이템이 아니면 null", nullable = true)
    Look look,

    @Schema(description = "아이템 설명 문구. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "봄 신상", nullable = true)
    String caption
) {

    @Builder
    @Schema(description = "전시가 참조한 룩 — 화보와 구성 상품의 원천은 룩 관리다")
    public record Look(

        @Schema(description = "룩 아이디", example = "4611686018427387904")
        String lookId,

        @Schema(description = "룩 제목")
        String title,

        @Schema(description = "룩 설명. 없으면 null", nullable = true)
        String description,

        @Schema(description = "룩 대표 이미지")
        Image image,

        @Schema(description = "룩 순서의 고객 노출 상품 카드. 숨김 · 삭제 상품은 빠진다")
        java.util.List<DisplayProductItem> products
    ) {
    }

    @Builder
    @Schema(description = "룩 대표 이미지")
    public record Image(

        @Schema(description = "이미지 공개 주소")
        String url,

        @Schema(description = "원본 가로 px. 모르면 null", nullable = true)
        Integer width,

        @Schema(description = "원본 세로 px. 모르면 null", nullable = true)
        Integer height,

        @Schema(description = "대체 텍스트. 없으면 null", nullable = true)
        String altText
    ) {
    }
}
