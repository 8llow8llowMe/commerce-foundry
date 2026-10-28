package com.commercefoundry.domainlayer.look.adapter.in.web.dto.response;

import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookImageItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookProductItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "고객 룩 상세")
public record LookDetailResponse(

    @Schema(description = "룩 아이디", example = "4611686018427387904")
    String lookId,

    @Schema(description = "제목. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더)", example = "가을 나들이 룩")
    String title,

    @Schema(description = "설명. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String description,

    @Schema(description = "대표 이미지")
    LookImageItem image,

    @Schema(description = "구성 상품 카드 (배열 순서 = 구성 순서). 숨긴 상품은 빠지고, 모두 빠지면 빈 배열이다 — 그때 화면은 '함께 담기' 를 숨긴다")
    List<LookProductItem> products
) {
}
