package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "어드민 룩 대표 이미지")
public record LookImageAdminItem(

    @Schema(description = "이미지 주소", example = "https://cdn.example.com/looks/autumn.jpg")
    String url,

    @Schema(description = "원본 가로 px. 모르면 null", example = "1200", nullable = true)
    Integer width,

    @Schema(description = "원본 세로 px. 모르면 null", example = "1600", nullable = true)
    Integer height,

    @Schema(description = "대체 텍스트. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더)", example = "가을 생활한복 코디", nullable = true)
    String altText
) {
}
