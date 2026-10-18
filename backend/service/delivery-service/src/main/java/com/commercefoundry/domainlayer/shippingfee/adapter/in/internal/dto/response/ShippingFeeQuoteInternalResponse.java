package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "서비스 간 배송비 견적 응답")
public record ShippingFeeQuoteInternalResponse(

    @Schema(
        description = "받을 배송비 — 국내 (무료배송이면 0, 아니면 baseFee) + remoteAreaSurcharge, 해외 무료배송이면 0, 아니면 baseFee + weightFee"
    )
    MoneyItem shippingFee,

    @Schema(description = "정책 기본 배송비 (해외는 모든 해외 배송에 붙는 기본료). 무료배송이면 shippingFee 에 들어가지 않는다")
    MoneyItem baseFee,

    @Schema(description = "주문 금액이 무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가", example = "false")
    boolean freeShippingApplied,

    @Schema(description = "도서산간 추가 배송비 — 무료배송이어도 받는다. 도서산간이 아니거나 해외면 0")
    MoneyItem remoteAreaSurcharge,

    @Schema(description = "우편번호가 속한 도서산간 구간 이름. 아니거나 해외면 null", example = "제주", nullable = true)
    String remoteAreaName,

    @Schema(
        description = "견적에 쓴 정책의 무료배송 기준. 없으면 null — 주문에 스냅숏해 클레임 시 배송비 재부과를 현재 정책이 아니라 이 값으로 계산한다",
        nullable = true
    )
    MoneyItem freeShippingThreshold,

    @Schema(description = "견적에 쓴 배송비 정책 version — 주문에 스냅숏해 클레임 재부과 계산의 근거로 남긴다", example = "3")
    long shippingPolicyVersion,

    @Schema(description = "견적에 쓴 정책의 배송 범위", example = "DOMESTIC", allowableValues = {"DOMESTIC", "INTERNATIONAL"})
    String scope,

    @Schema(description = "배송 국가가 속한 해외 국가 그룹 이름. 국내면 null", example = "아시아", nullable = true)
    String shippingZoneName,

    @Schema(description = "해외 무게 요금 — 무게가 든 구간(상한 포함)의 요금. 무료배송이면 shippingFee 에 들어가지 않는다. 국내면 null", nullable = true)
    MoneyItem weightFee
) {
}
