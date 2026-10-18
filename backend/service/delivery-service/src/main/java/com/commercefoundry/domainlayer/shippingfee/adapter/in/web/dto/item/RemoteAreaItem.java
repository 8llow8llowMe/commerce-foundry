package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "도서산간 우편번호 구간 — 양 끝을 포함한다")
public record RemoteAreaItem(

    @Schema(description = "구간 이름", example = "제주")
    String name,

    @Schema(description = "시작 우편번호 (포함)", example = "63000")
    String postalCodeFrom,

    @Schema(description = "끝 우편번호 (포함)", example = "63644")
    String postalCodeTo,

    @Schema(description = "추가 배송비 — 무료배송이어도 받는다")
    MoneyItem surcharge
) {
}
