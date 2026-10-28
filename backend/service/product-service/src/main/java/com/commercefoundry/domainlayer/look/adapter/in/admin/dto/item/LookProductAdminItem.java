package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "어드민 룩 구성 상품")
public record LookProductAdminItem(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "상품명. 상품 행이 없으면(데이터 오류) null", example = "모코 생활한복 저고리", nullable = true)
    String name,

    @Schema(description = "고객 노출 여부. 숨긴 상품이면 false 다. 상품 행이 없으면 false", example = "true")
    boolean visible,

    @Schema(
        description = "저장한 판매 상태 — ON_SALE(판매중) · STOP_SELLING(판매 종료). 상품 행이 없으면 null",
        example = "{\"code\": \"ON_SALE\", \"name\": \"판매중\", \"description\": \"판매하도록 설정했습니다. 재고가 없으면 고객 화면에는 품절로 보입니다.\"}",
        nullable = true
    )
    CodeNameDescriptionMetadata saleStatus
) {
}
