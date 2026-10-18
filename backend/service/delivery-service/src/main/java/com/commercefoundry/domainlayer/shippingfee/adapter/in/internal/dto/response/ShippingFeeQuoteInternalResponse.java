package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "서비스 간 배송비 견적 응답")
public record ShippingFeeQuoteInternalResponse(

    @Schema(description = "받을 배송비 = (무료배송이면 0, 아니면 baseFee) + remoteAreaSurcharge")
    MoneyItem shippingFee,

    @Schema(description = "정책 기본 배송비. 무료배송이면 shippingFee 에 들어가지 않는다")
    MoneyItem baseFee,

    @Schema(description = "주문 금액이 무료배송 기준 이상이라 기본 배송비를 받지 않았는가", example = "false")
    boolean freeShippingApplied,

    @Schema(description = "도서산간 추가 배송비 — 무료배송이어도 받는다. 도서산간이 아니면 0")
    MoneyItem remoteAreaSurcharge,

    @Schema(description = "우편번호가 속한 도서산간 구간 이름. 아니면 null", example = "제주", nullable = true)
    String remoteAreaName,

    @Schema(
        description = "견적에 쓴 정책의 무료배송 기준. 없으면 null — 주문에 스냅숏해 클레임 시 배송비 재부과를 현재 정책이 아니라 이 값으로 계산한다",
        nullable = true
    )
    MoneyItem freeShippingThreshold,

    @Schema(description = "견적에 쓴 배송비 정책 version — 주문에 스냅숏해 클레임 재부과 계산의 근거로 남긴다", example = "3")
    long shippingPolicyVersion
) {
}
