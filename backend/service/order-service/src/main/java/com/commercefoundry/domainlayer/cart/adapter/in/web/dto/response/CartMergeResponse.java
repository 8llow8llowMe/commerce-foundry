package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response;

import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartMergeSkippedItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "로컬 장바구니 병합 결과")
public record CartMergeResponse(

    @Schema(description = "병합 후 장바구니")
    CartResponse cart,

    @Schema(description = "담지 못하고 건너뛴 항목 — 요청 순서. 같은 SKU 라도 항목마다 따로 판정한다. 없으면 빈 배열")
    List<CartMergeSkippedItem> skippedItems
) {
}
