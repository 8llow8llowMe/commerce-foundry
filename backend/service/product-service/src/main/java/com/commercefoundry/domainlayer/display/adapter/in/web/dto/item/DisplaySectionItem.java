package com.commercefoundry.domainlayer.display.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "메인 전시 섹션")
public record DisplaySectionItem(

    @Schema(description = "섹션 아이디", example = "4611686018427387904")
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
    DisplayLinkItem link,

    @Schema(description = "아이템 (섹션 안의 순서). 고객에게 보이지 않는 상품의 아이템은 빠진다 — 상품 픽의 상품이 모두 빠져도 섹션은 빈 배열로 남는다")
    List<DisplayElementItem> items
) {
}
