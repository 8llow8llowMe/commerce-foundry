package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "장바구니 한 줄. 가격 · 상태는 조회 시점의 현재 값이다")
public record CartLineItem(

    @Schema(description = "장바구니 줄 아이디 — 수량 변경 · 삭제에 쓴다", example = "4611686018427387904")
    String itemId,

    @Schema(description = "SKU 아이디", example = "9004")
    String skuId,

    @Schema(description = "상품 아이디. SKU 를 찾지 못했으면(삭제 등) null", example = "7301", nullable = true)
    String productId,

    @Schema(description = "상품명. SKU 를 찾지 못했으면 null", example = "모코 생활한복 저고리", nullable = true)
    String productName,

    @Schema(description = "옵션 표시명 (옵션 값을 ' / ' 로 이은 것). 옵션이 없으면 빈 문자열, SKU 를 찾지 못했으면 null", example = "아이보리 / L", nullable = true)
    String optionName,

    @Schema(description = "담은 수량", example = "2")
    int quantity,

    @Schema(description = "현재 판매가 (옵션 추가금 포함). SKU 를 찾지 못했으면 null", nullable = true)
    MoneyItem unitPrice,

    @Schema(description = "판매가 × 수량. SKU 를 찾지 못했으면 null", nullable = true)
    MoneyItem lineAmount,

    @Schema(
        description = "구매 가능 상태 — ON_SALE(구매 가능) · SOLD_OUT(품절 — 숨긴 상품이라도 품절이면 품절) · "
            + "STOP_SELLING(판매 중지 — 판매 종료 · 품절이 아닌 숨긴 상품 · SKU 없음)",
        example = "{\"code\":\"ON_SALE\",\"name\":\"구매 가능\",\"description\":\"지금 주문할 수 있습니다.\"}"
    )
    CodeNameDescriptionMetadata status,

    @Schema(description = "상품의 1회 주문 최대 수량. null 이면 99개까지", example = "10", nullable = true)
    Integer maxQuantityPerOrder,

    @Schema(description = "맞춤 제작 상품인가", example = "false")
    boolean customOrder,

    @Schema(description = "담긴 맞춤 제작 입력. 없으면 빈 배열")
    List<CustomOptionItem> customOptions,

    @Schema(description = "담은 수량이 지금 상한(99 · 1회 주문 최대 수량)을 넘는가 — 담은 뒤 상한이 줄어든 경우. true 면 합계에서 빠진다", example = "false")
    boolean quantityExceeded
) {
}
