package com.commercefoundry.domainlayer.look.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 상품 상세의 "이 상품이 든 룩". 룩 수에 상한(100)이 있어 페이지 없이 전체를 낸다. */
@Builder
@Schema(description = "상품이 든 룩 목록")
public record ProductLookListResponse(

    @Schema(description = "상품이 든 보이는 룩 — 배열 순서 = 노출 순서. 없거나 룩 기능이 꺼져 있으면 빈 배열")
    List<LookSummaryResponse> looks
) {
}
