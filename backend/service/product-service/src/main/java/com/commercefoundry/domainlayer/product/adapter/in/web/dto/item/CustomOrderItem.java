package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "맞춤 제작 안내")
public record CustomOrderItem(

    @Schema(description = "주문 전 고지 문구", example = "맞춤 제작 상품은 제작 착수 후 단순 변심 취소가 불가합니다.", nullable = true)
    String notice,

    @Schema(description = "제작 소요일", example = "14", nullable = true)
    Integer leadTimeDays,

    @Schema(description = "입력 항목. 노출 순서 → 아이디 오름차순")
    List<CustomOrderFieldItem> fields
) {
}
