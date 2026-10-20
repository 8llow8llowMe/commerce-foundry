package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockRestorationItemInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "결제 후 재고 복원 — 신규 · 같은 클레임의 재요청 모두 같은 모양이다")
public record StockRestorationResponse(

    @Schema(description = "재고 복원 아이디", example = "4611686018427388101")
    String restorationId,

    @Schema(description = "클레임 아이디 (order-service)", example = "4611686018427388201")
    String claimId,

    @Schema(description = "복원 대상 확정 선점 아이디", example = "4611686018427388001")
    String reservationId,

    @Schema(
        description = "복원 사유 — 호출자(order-service)가 분기에만 쓰는 제어 코드다. 화면에 그대로 내보내지 않으므로 표시 문자열을 두지 않는다 "
            + "(coding-conventions §11 예외)",
        allowableValues = {"CANCELLATION", "RETURN"}, example = "CANCELLATION"
    )
    String reason,

    @Schema(description = "복원 항목. SKU 아이디 오름차순이고 같은 SKU 는 수량을 합친 한 건이다")
    List<StockRestorationItemInternalItem> items
) {
}
