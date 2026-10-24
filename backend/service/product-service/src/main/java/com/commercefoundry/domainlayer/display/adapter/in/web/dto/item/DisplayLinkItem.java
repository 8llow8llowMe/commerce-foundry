package com.commercefoundry.domainlayer.display.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "전시 섹션 이동 링크")
public record DisplayLinkItem(

    @Schema(description = "이동 주소 — '/' 로 시작하는 스토어 안 상대 경로 또는 https 주소", example = "/products?categoryId=11")
    String url,

    @Schema(description = "버튼 문구. 없으면 null — 화면 기본 문구를 쓴다. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "더 보기", nullable = true)
    String label
) {
}
