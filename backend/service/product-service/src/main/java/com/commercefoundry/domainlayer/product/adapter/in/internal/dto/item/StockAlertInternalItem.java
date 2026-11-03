package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "품절 임박 SKU. 숨김·판매 중지 여부는 별도 상태 칸으로 확인한다")
public record StockAlertInternalItem(

    @Schema(description = "SKU 아이디", example = "4611686018427387904")
    String skuId,

    @Schema(description = "상품 아이디", example = "4611686018427387905")
    String productId,

    @Schema(description = "상품명")
    String productName,

    @Schema(description = "SKU 옵션 값 아이디 조합. 옵션이 없으면 빈 문자열")
    String optionValueKey,

    @Schema(description = "현재 판매 가능 수량 (선점분 제외). 재고 미등록은 0")
    long availableQuantity,

    @Schema(description = "품절 임박 기준 수량")
    long thresholdQuantity,

    @Schema(description = "상품 노출 여부")
    boolean productVisible,

    @Schema(description = "상품 판매 상태")
    CodeNameDescriptionMetadata productSaleStatus,

    @Schema(description = "SKU 판매 상태")
    CodeNameDescriptionMetadata skuSaleStatus,

    @Schema(description = "정책 마지막 설정 시각")
    Instant policyUpdatedAt
) {
}
