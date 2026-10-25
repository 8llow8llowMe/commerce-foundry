package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 아이템 아이디는 내지 않는다(#305) — 섹션 수정이 아이템 전체 교체라 저장할 때마다 새로 발급되고, 아이템 아이디를 받는 어드민 API 도 없다.
 * 아이템 순서는 배열 순서다.
 */
@Builder
@Schema(description = "어드민 전시 아이템")
public record DisplayElementAdminItem(

    @Schema(
        description = "아이템 유형 — MEDIA(미디어) · PRODUCT(상품). 수정 요청에는 code 를 그대로 보낸다",
        example = "{\"code\": \"PRODUCT\", \"name\": \"상품\", \"description\": \"상품 하나입니다. 고객 메인에는 상품 목록과 같은 카드로 보입니다.\"}"
    )
    CodeNameDescriptionMetadata itemType,

    @Schema(description = "미디어. itemType 이 MEDIA 가 아니면 null", nullable = true)
    DisplayMediaAdminItem media,

    @Schema(description = "가리키는 상품. itemType 이 PRODUCT 가 아니면 null", nullable = true)
    DisplayProductAdminItem product,

    @Schema(description = "아이템 설명 문구. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String caption
) {
}
