package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 고객 메인과 같은 JSON 계약을 갖는 어드민 전용 응답. */
@Builder
@Schema(description = "전시 초안 미리보기 응답")
public record DisplayPreviewAdminResponse(

    @Schema(description = "현재 노출 가능한 섹션, 요청 배열 순서")
    List<Section> sections
) {
    @Builder
    @Schema(description = "메인 전시 섹션")
    public record Section(

        @Schema(description = "저장 아이디가 아닌 요청 위치 기반 임시 섹션 식별자", example = "preview-section-0")
        String sectionId,

        @Schema(
            description = "섹션 유형 — HERO · PRODUCT_PICK · STORY · LOOK · DETAIL. 화면은 code 로 블록 레이아웃을 고른다",
            example = "{\"code\": \"HERO\", \"name\": \"히어로\", \"description\": \"메인 상단의 큰 이미지 · 영상 배너입니다. 여러 장이면 슬라이드로 넘깁니다.\"}"
        )
        CodeNameDescriptionMetadata sectionType,

        @Schema(description = "제목. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "가을 생활한복", nullable = true)
        String title,

        @Schema(description = "부제목. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "일상에 입는 한복", nullable = true)
        String subtitle,

        @Schema(description = "본문 (개행 포함 가능). 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
        String body,

        @Schema(description = "이동 링크. 없으면 null", nullable = true)
        Link link,

        @Schema(description = "아이템 (섹션 안의 순서). 고객에게 보이지 않는 상품의 아이템은 빠진다 — 상품 픽의 상품이 모두 빠져도 섹션은 빈 배열로 남는다")
        List<Element> items
    ) {
    }

    @Builder
    @Schema(description = "전시 섹션 아이템 — 미디어 또는 상품 하나")
    public record Element(

        @Schema(description = "저장 아이디가 아닌 요청 위치 기반 임시 아이템 식별자", example = "preview-item-0")
        String itemId,

        @Schema(description = "아이템 유형 — 화면 분기용 제어 코드. MEDIA 면 media, PRODUCT 면 product 가 있다", allowableValues = {"MEDIA", "PRODUCT"}, example = "MEDIA")
        String itemType,

        @Schema(description = "미디어. itemType 이 MEDIA 가 아니면 null", nullable = true)
        Media media,

        @Schema(description = "상품 카드 — 상품 목록 항목과 같은 모양 · 값이다. itemType 이 PRODUCT 가 아니면 null", nullable = true)
        Product product,

        @Schema(description = "아이템 설명 문구. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "봄 신상", nullable = true)
        String caption
    ) {
    }

    @Builder
    @Schema(description = "전시 섹션 이동 링크")
    public record Link(

        @Schema(description = "이동 주소 — '/' 로 시작하는 스토어 안 상대 경로 또는 https 주소", example = "/products?categoryId=11")
        String url,

        @Schema(description = "버튼 문구. 없으면 null — 화면 기본 문구를 쓴다. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "더 보기", nullable = true)
        String label
    ) {
    }

    @Builder
    @Schema(description = "전시 미디어 — 이미지 또는 영상")
    public record Media(

        @Schema(description = "미디어 종류 — 화면 분기용 제어 코드", allowableValues = {"IMAGE", "VIDEO"}, example = "IMAGE")
        String mediaType,

        @Schema(description = "PC 주소", example = "https://cdn.example.com/displays/hero-pc.jpg")
        String pcUrl,

        @Schema(description = "모바일 주소. 없으면 null — pcUrl 을 쓴다", example = "https://cdn.example.com/displays/hero-mobile.jpg", nullable = true)
        String mobileUrl,

        @Schema(description = "영상 포스터(PC). VIDEO 면 항상 있고 IMAGE 면 null", nullable = true)
        String pcPosterUrl,

        @Schema(description = "영상 포스터(모바일). 없으면 null — pcPosterUrl 을 쓴다", nullable = true)
        String mobilePosterUrl,

        @Schema(description = "대체 텍스트. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "가을 생활한복 착용 사진", nullable = true)
        String altText
    ) {
    }

    @Builder
    @Schema(description = "전시 상품 카드 (상품 목록 항목과 같은 모양)")
    public record Product(

        @Schema(description = "상품 아이디", example = "4611686018427387904")
        String productId,

        @Schema(description = "상품명", example = "모코 생활한복 저고리")
        String name,

        @Schema(description = "대표 이미지. 없으면 null", nullable = true)
        Thumbnail thumbnail,

        @Schema(
            description = "hover 용 두 번째 정지 이미지. 상품 목록 항목과 같다. 1장 이하면 null",
            nullable = true
        )
        Thumbnail hoverImage,

        @Schema(description = "판매가 — 판매중 옵션 중 최저가. hasOptionSurcharge 가 true 면 '129,000원~' 처럼 표시한다")
        MoneyItem salePrice,

        @Schema(description = "정가 (할인 전). 할인이 없으면(정가 ≤ 표시 판매가 또는 1% 미만 할인) null — listPrice 와 discountRate 는 함께 null 이다", nullable = true)
        MoneyItem listPrice,

        @Schema(description = "정가 대비 할인율(%) — 소수점 아래는 버린다. 할인이 없으면 null — listPrice 와 discountRate 는 함께 null 이다", example = "13", nullable = true)
        Integer discountRate,

        @Schema(description = "판매중 옵션끼리 추가금이 다른가", example = "true")
        boolean hasOptionSurcharge,

        @Schema(description = "판매 상태 — ON_SALE · SOLD_OUT · STOP_SELLING", example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"구매할 수 있습니다.\"}")
        CodeNameDescriptionMetadata saleStatus,

        @Schema(description = "맞춤 제작 상품인가. 스토어 맞춤 제작 기능이 꺼져 있으면 false", example = "false")
        boolean customOrder,

        @Schema(description = "프리오더(예약 판매) 상품인가. 스토어 프리오더 기능이 꺼져 있으면 false", example = "false")
        boolean preorder
    ) {
    }

    @Builder
    @Schema(description = "전시 상품 대표 이미지")
    public record Thumbnail(

        @Schema(description = "이미지 아이디", example = "88000")
        String imageId,

        @Schema(description = "이미지 URL", example = "https://cdn.example.com/p/7301/thumb.jpg")
        String url,

        @Schema(description = "원본 가로 px. 모르면 null", example = "600", nullable = true)
        Integer width,

        @Schema(description = "원본 세로 px. 모르면 null", example = "800", nullable = true)
        Integer height,

        @Schema(description = "대체 텍스트", example = "저고리 정면", nullable = true)
        String altText
    ) {
    }
}
