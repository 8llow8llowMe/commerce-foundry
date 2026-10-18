package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "해외 배송비 정책 (스토어프론트 해외 배송 안내)")
public record InternationalShippingPolicyResponse(

    @Schema(description = "기본료 — 모든 해외 배송에 무게 요금과 함께 붙는다")
    MoneyItem baseFee,

    @Schema(description = "무료배송 기준 주문 금액(할인 후 상품 합계) — 이 금액 이상이면 기본료 · 무게 요금이 모두 없다. null 이면 무료배송 없음", nullable = true)
    MoneyItem freeShippingThreshold,

    @Schema(description = "관세 · 부가세 고객 부담 안내 문구. 없으면 null", example = "수입 관세 · 부가세는 받는 분이 부담합니다.", nullable = true)
    String customsNotice,

    @Schema(description = "국가 그룹. 어드민이 정한 표시 순서. 여기에 없는 국가로는 배송할 수 없다")
    List<ShippingZoneItem> zones
) {
}
