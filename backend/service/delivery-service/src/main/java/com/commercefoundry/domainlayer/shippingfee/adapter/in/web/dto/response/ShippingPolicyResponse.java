package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.RemoteAreaItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "국내 배송비 정책 (스토어프론트 배송 안내)")
public record ShippingPolicyResponse(

    @Schema(description = "기본 배송비")
    MoneyItem baseFee,

    @Schema(description = "무료배송 기준 주문 금액(할인 후 상품 합계) — 이 금액 이상이면 기본 배송비가 없다. null 이면 무료배송 없음", nullable = true)
    MoneyItem freeShippingThreshold,

    @Schema(description = "도서산간 구간. 시작 우편번호 오름차순. 없으면 빈 배열")
    List<RemoteAreaItem> remoteAreas
) {
}
