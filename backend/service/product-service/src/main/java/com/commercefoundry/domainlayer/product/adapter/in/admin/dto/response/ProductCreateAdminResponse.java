package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 어드민 상품 등록 결과 (#9 E1) — 저장된 상품 상세(편집용 상세 조회와 같은 모양)와 상세 HTML 정제 여부. 정제 여부는 등록 응답에만 있다 —
 * 상세 조회 응답 모양을 바꾸지 않으려고 감싼다.
 */
@Builder
@Schema(description = "어드민 상품 등록 결과")
public record ProductCreateAdminResponse(

    @Schema(description = "저장된 상품 — 편집용 상세 조회(GET /api/v1/admin/products/{productId})와 같은 모양")
    ProductDetailAdminResponse product,

    @Schema(description = "상세 HTML 에 허용 목록 밖의 태그 · 속성 · 주소가 있어 빼고 저장했는가. true 면 화면이 저장된 descriptionHtml 을 다시 보여 주고 알린다",
        example = "false")
    boolean descriptionHtmlSanitized
) {
}
