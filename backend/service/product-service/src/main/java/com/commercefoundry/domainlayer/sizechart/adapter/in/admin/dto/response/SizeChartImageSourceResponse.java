package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사이즈표 초안에 쓸 수 있는 상품 이미지 후보")
public record SizeChartImageSourceResponse(
    @Schema(description = "이미지 아이디. 확인과 초안 생성에 그대로 보냅니다", example = "IMG-4611686018427387905")
    String sourceId,

    @Schema(description = "미리보기 공개 주소. 외부 주소 이미지는 비어 있습니다", example = "https://cdn.example.com/products/700/4611686018427387905.jpg")
    String previewUrl,

    @Schema(description = "사이즈표 초안에 쓸 수 있는지", example = "true")
    boolean eligible,

    @Schema(description = "쓸 수 없는 사유. 쓸 수 있으면 비어 있습니다",
        example = "{\"code\":\"EXTERNAL_IMAGE\",\"name\":\"외부 주소 이미지\",\"description\":\"우리 저장소 밖의 이미지는 사용할 수 없습니다. 상품 이미지로 다시 등록한 뒤 선택해 주세요.\"}")
    CodeNameDescriptionMetadata reason
) {
}
