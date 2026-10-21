package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "배송비 내역 — 받는 배송비는 amounts.shippingFee 다")
public record ShippingFeeItem(

    @Schema(description = "견적에 쓴 배송 범위 — 화면 분기용 제어 코드", allowableValues = {"DOMESTIC", "INTERNATIONAL"}, example = "DOMESTIC")
    String scope,

    @Schema(description = "정책 기본 배송비 (해외는 기본료). 무료배송이면 받지 않는다")
    MoneyItem baseFee,

    @Schema(description = "상품 합계가 무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가", example = "false")
    boolean freeShippingApplied,

    @Schema(description = "도서산간 추가 배송비 — 무료배송이어도 받는다. 아니거나 해외면 0")
    MoneyItem remoteAreaSurcharge,

    @Schema(description = "도서산간 구간 이름. 아니면 null", example = "제주", nullable = true)
    String remoteAreaName,

    @Schema(description = "무료배송 기준 금액. 정책에 기준이 없으면 null — '얼마 더 사면 무료배송' 안내에 쓴다", nullable = true)
    MoneyItem freeShippingThreshold,

    @Schema(description = "해외 무게 요금. 무료배송이면 받지 않는다. 국내면 null", nullable = true)
    MoneyItem weightFee,

    @Schema(description = "해외 국가 그룹 이름. 국내면 null", example = "아시아", nullable = true)
    String shippingZoneName
) {
}
